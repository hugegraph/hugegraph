/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hugegraph.meta.managers;

import static org.apache.hugegraph.meta.MetaManager.META_PATH_DELIMITER;
import static org.apache.hugegraph.meta.MetaManager.META_PATH_EDGE_LABEL;
import static org.apache.hugegraph.meta.MetaManager.META_PATH_GRAPHSPACE;
import static org.apache.hugegraph.meta.MetaManager.META_PATH_GRAPH_DROPPING;
import static org.apache.hugegraph.meta.MetaManager.META_PATH_HUGEGRAPH;
import static org.apache.hugegraph.meta.MetaManager.META_PATH_ID;
import static org.apache.hugegraph.meta.MetaManager.META_PATH_INDEX_LABEL;
import static org.apache.hugegraph.meta.MetaManager.META_PATH_NAME;
import static org.apache.hugegraph.meta.MetaManager.META_PATH_PROPERTY_KEY;
import static org.apache.hugegraph.meta.MetaManager.META_PATH_SCHEMA;
import static org.apache.hugegraph.meta.MetaManager.META_PATH_SCHEMA_SYNC;
import static org.apache.hugegraph.meta.MetaManager.META_PATH_VERTEX_LABEL;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.exception.ExistedException;
import org.apache.hugegraph.exception.HugeException;
import org.apache.hugegraph.id.Id;
import org.apache.hugegraph.meta.MetaDriver;
import org.apache.hugegraph.pd.grpc.kv.TxnCompare;
import org.apache.hugegraph.pd.grpc.kv.TxnOp;
import org.apache.hugegraph.pd.grpc.kv.TxnRecord;
import org.apache.hugegraph.pd.grpc.kv.TxnRequest;
import org.apache.hugegraph.pd.grpc.kv.TxnResponse;
import org.apache.hugegraph.struct.schema.EdgeLabel;
import org.apache.hugegraph.struct.schema.IndexLabel;
import org.apache.hugegraph.struct.schema.PropertyKey;
import org.apache.hugegraph.struct.schema.SchemaElement;
import org.apache.hugegraph.struct.schema.SchemaLabel;
import org.apache.hugegraph.struct.schema.VertexLabel;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.util.JsonUtil;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

public class SchemaMetaManager extends AbstractMetaManager {

    private static final Logger LOG = Log.logger(SchemaMetaManager.class);

    /**
     * Commits tried by a schema change while other Servers keep changing the same element
     */
    private static final int COMMIT_ATTEMPTS = 3;

    private static final String CREATING = "CREATING";
    private static final String DROPPED = "DROPPED";

    private final HugeGraph graph;
    // The incarnation of the graph instance that writes, shared by its schema transactions;
    // null where no single graph instance writes, which skips the incarnation check
    private final AtomicLong incarnation;
    // The incarnation by record key of each graph this Server is creating
    private final Map<String, Long> creating = new ConcurrentHashMap<>();
    // Names this Server process in the drop marker of each graph it drops, see dropOwner()
    private String dropOwner;

    public SchemaMetaManager(MetaDriver metaDriver, String cluster, HugeGraph graph) {
        this(metaDriver, cluster, graph, null);
    }

    public SchemaMetaManager(MetaDriver metaDriver, String cluster, HugeGraph graph,
                             AtomicLong incarnation) {
        super(metaDriver, cluster);
        this.graph = graph;
        this.incarnation = incarnation;
    }

    /**
     * Creates the schema elements: writes their ID and NAME keys in one commit that bumps the
     * graph record, so a crash can't leave half of a change behind. The commit holds only
     * while all of these keys are absent. The JVM locks of the schema builders don't reach
     * across Servers, so two Servers can both find a name free and create it with two ids:
     * only one of them commits, and the other leaves no ID key behind that the NAME key
     * doesn't point to.
     *
     * @throws ExistedException if one of the ids or names exists
     */
    public void addSchema(String graphSpace, String graph, SchemaElement... schemas) {
        TxnRequest.Builder txn = TxnRequest.newBuilder();
        String[] contents = new String[schemas.length];
        for (int i = 0; i < schemas.length; i++) {
            contents[i] = this.addCreates(txn, graphSpace, graph, schemas[i]);
        }
        TxnResponse response = this.tryBump(graphSpace, graph, txn);
        if (!response.getSucceeded()) {
            if (response.getFailure() == TxnResponse.Failure.COMPARE_FAILED) {
                // Two compares per element, of its ID key and of its NAME key
                int failed = response.getFailedCompare();
                SchemaElement existed = schemas[failed / 2];
                if (failed % 2 == 0) {
                    throw new ExistedException(existed.type().readableName() + " id",
                                               existed.id());
                }
                throw new ExistedException(existed.type(), existed.name());
            }
            throw rejected(graphSpace, graph, response);
        }
        for (int i = 0; i < schemas.length; i++) {
            schemas[i].stored(contents[i]);
        }
    }

    /**
     * Writes a changed schema element in one commit that bumps the graph record. The commit
     * holds only while PD has the content the element was read as, see
     * {@link SchemaElement#stored()}: a Server that changes an element it read a while ago,
     * such as a cached one, must not undo what another Server wrote to it since. If the
     * content differs, what this Server changed is applied to what PD holds now, and that is
     * committed instead, again only while PD still holds it: a field this Server set gets its
     * value, elements it added to or removed from a list (the properties or index labels of a
     * label) or the userdata are added and removed there, and all else stays as PD holds it.
     * <p>
     * An element that wasn't read from PD has no such content. It is written as it is, while
     * PD holds what it held when this call read it.
     *
     * @return the element as written: the given one, or the merged one
     */
    public SchemaElement updateSchema(String graphSpace, String graph, SchemaElement schema) {
        String idKey = this.schemaKeys(graphSpace, graph, schema).get(0);
        String base = schema.stored();
        String mine = serialize(schema);
        String expected = base != null ? base : this.metaDriver.get(idKey);
        SchemaElement update = schema;
        for (int i = 0; i < COMMIT_ATTEMPTS; i++) {
            boolean absent = expected == null || expected.isEmpty();
            TxnRequest.Builder txn = TxnRequest.newBuilder().addCompares(
                    absent ? compare(idKey, TxnCompare.Kind.ABSENT, "") :
                    compare(idKey, TxnCompare.Kind.EQUALS, expected));
            String content = this.addPuts(txn, graphSpace, graph, update);
            TxnResponse response = this.tryBump(graphSpace, graph, txn);
            if (response.getSucceeded()) {
                // All that the given element changed so far is in PD now: its later
                // updates apply only what it changes from here on
                schema.stored(mine);
                update.stored(content);
                return update;
            }
            if (response.getFailure() != TxnResponse.Failure.COMPARE_FAILED) {
                throw rejected(graphSpace, graph, response);
            }
            expected = this.metaDriver.get(idKey);
            if (base == null) {
                continue;
            }
            if (expected == null || expected.isEmpty()) {
                throw new HugeException("Can't update %s '%s': it was removed on another " +
                                        "server", schema.type().readableName(), schema.name());
            }
            LOG.info("The {} '{}' changed on another server since it was read, merge",
                     schema.type().readableName(), schema.name());
            Map<String, Object> merged = merge(toMap(base), toMap(mine), toMap(expected));
            update = this.parse(schema.type(), JsonUtil.toJson(merged));
        }
        throw new HugeException("Failed to update %s '%s': it kept changing on other " +
                                "servers in %s attempts", schema.type().readableName(),
                                schema.name(), COMMIT_ATTEMPTS);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(String content) {
        return JsonUtil.fromJson(content, Map.class);
    }

    /**
     * A schema element as JSON maps, with what this Server changed (mine against base) applied
     * to what PD holds now (theirs)
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> mine,
                                             Map<String, Object> theirs) {
        Map<String, Object> merged = new HashMap<>(theirs);
        Set<String> keys = new HashSet<>(base.keySet());
        keys.addAll(mine.keySet());
        for (String key : keys) {
            Object b = base.get(key);
            Object m = mine.get(key);
            Object t = theirs.get(key);
            if (Objects.equals(m, b)) {
                // Not changed here
                continue;
            }
            if (m instanceof List && t instanceof List) {
                List<Object> before = b instanceof List ? (List<Object>) b :
                                      Collections.emptyList();
                List<Object> list = new ArrayList<>((List<Object>) t);
                list.removeIf(e -> before.contains(e) && !((List<Object>) m).contains(e));
                for (Object e : (List<Object>) m) {
                    if (!before.contains(e) && !list.contains(e)) {
                        list.add(e);
                    }
                }
                merged.put(key, list);
            } else if (m instanceof Map && t instanceof Map) {
                merged.put(key, merge(b instanceof Map ? (Map<String, Object>) b :
                                      Collections.emptyMap(), (Map<String, Object>) m,
                                      (Map<String, Object>) t));
            } else if (m == null) {
                merged.remove(key);
            } else {
                merged.put(key, m);
            }
        }
        return merged;
    }

    /**
     * Writes the index label and adds it to its base label (VL/EL) in one commit, and returns
     * the base label as written.
     * <p>
     * The JVM locks of SchemaTransactionV2 don't serialize two Servers changing the index
     * labels of the same base label: both would read it, change it, and the later write would
     * undo the earlier change. So the base label is read from PD, and the commit only holds
     * while its ID key still has the value read; otherwise it is read again and retried.
     * The index label itself is created as in {@link #addSchema}, only while its keys are
     * absent.
     *
     * @throws ExistedException if the id or the name of the index label exists
     */
    public SchemaLabel addIndexLabel(String graphSpace, String graph, SchemaLabel baseLabel,
                                     IndexLabel indexLabel) {
        String[] content = new String[1];
        SchemaLabel label = this.updateLabel(graphSpace, graph, baseLabel, indexLabel.name(),
                                             stored -> stored.addIndexLabel(indexLabel.id()),
                                             txn -> content[0] = this.addCreates(
                                                     txn, graphSpace, graph, indexLabel));
        if (label == null) {
            throw new HugeException("The %s '%s' of index label '%s' doesn't exist",
                                    baseLabel.type().readableName(), baseLabel.name(),
                                    indexLabel.name());
        }
        indexLabel.stored(content[0]);
        return label;
    }

    /**
     * Removes the index label from its base label (VL/EL) with the compare and retry of
     * addIndexLabel, and returns the base label as written, or null if it doesn't exist
     */
    public SchemaLabel removeIndexLabel(String graphSpace, String graph, SchemaLabel baseLabel,
                                        IndexLabel indexLabel) {
        return this.updateLabel(graphSpace, graph, baseLabel, indexLabel.name(),
                                stored -> stored.removeIndexLabel(indexLabel.id()),
                                txn -> { });
    }

    private SchemaLabel updateLabel(String graphSpace, String graph, SchemaLabel baseLabel,
                                    String indexLabel, Consumer<SchemaLabel> change,
                                    Consumer<TxnRequest.Builder> otherOps) {
        String idKey = this.schemaKeys(graphSpace, graph, baseLabel).get(0);
        for (int i = 0; i < COMMIT_ATTEMPTS; i++) {
            String stored = this.metaDriver.get(idKey);
            if (stored == null || stored.isEmpty()) {
                return null;
            }
            SchemaLabel label = (SchemaLabel) this.parse(baseLabel.type(), stored);
            change.accept(label);

            TxnRequest.Builder txn = TxnRequest.newBuilder().addCompares(
                    compare(idKey, TxnCompare.Kind.EQUALS, stored));
            otherOps.accept(txn);
            String content = this.addPuts(txn, graphSpace, graph, label);
            TxnResponse response = this.tryBump(graphSpace, graph, txn);
            if (response.getSucceeded()) {
                label.stored(content);
                return label;
            }
            if (response.getFailure() != TxnResponse.Failure.COMPARE_FAILED) {
                throw rejected(graphSpace, graph, response);
            }
            if (response.getFailedCompare() > 0) {
                // Not the compare of the base label: the keys of the index label exist
                throw new ExistedException(HugeType.INDEX_LABEL, indexLabel);
            }
            LOG.info("The {} '{}' changed while its index label '{}' was changed, retry",
                     baseLabel.type().readableName(), baseLabel.name(), indexLabel);
        }
        throw new HugeException("Failed to change index label '%s' of %s '%s': it kept " +
                                "changing on other servers in %s attempts", indexLabel,
                                baseLabel.type().readableName(), baseLabel.name(),
                                COMMIT_ATTEMPTS);
    }

    /**
     * Deletes the ID and NAME keys of the schema element in one commit that bumps the graph
     * record, only while its ID key holds what this call read. An element that is gone
     * already is left alone, so a remove that another Server finished first can't delete
     * the NAME key of an element created under the name since.
     */
    public void removeSchema(String graphSpace, String graph, SchemaElement schema) {
        List<String> keys = this.schemaKeys(graphSpace, graph, schema);
        for (int i = 0; i < COMMIT_ATTEMPTS; i++) {
            String stored = this.metaDriver.get(keys.get(0));
            if (stored == null || stored.isEmpty()) {
                return;
            }
            TxnRequest.Builder txn = TxnRequest.newBuilder().addCompares(
                    compare(keys.get(0), TxnCompare.Kind.EQUALS, stored));
            for (String key : keys) {
                txn.addOps(TxnOp.newBuilder().setType(TxnOp.Type.DELETE).setKey(key));
            }
            TxnResponse response = this.tryBump(graphSpace, graph, txn);
            if (response.getSucceeded()) {
                return;
            }
            if (response.getFailure() != TxnResponse.Failure.COMPARE_FAILED) {
                throw rejected(graphSpace, graph, response);
            }
        }
        throw new HugeException("Failed to remove %s '%s': it kept changing on other " +
                                "servers in %s attempts", schema.type().readableName(),
                                schema.name(), COMMIT_ATTEMPTS);
    }

    @SuppressWarnings("unchecked")
    public PropertyKey getPropertyKey(String graphSpace, String graph,
                                      Id propertyKey) {
        String content = this.metaDriver.get(propertyKeyIdKey(graphSpace, graph,
                                                              propertyKey));
        if (content == null || content.length() == 0) {
            return null;
        } else {
            return this.parse(content, PropertyKey::fromMap);
        }
    }

    @SuppressWarnings("unchecked")
    public PropertyKey getPropertyKey(String graphSpace, String graph,
                                      String propertyKey) {
        String content = this.metaDriver.get(propertyKeyNameKey(graphSpace,
                                                                graph,
                                                                propertyKey));
        if (content == null || content.length() == 0) {
            return null;
        } else {
            return this.parse(content, PropertyKey::fromMap);
        }
    }

    @SuppressWarnings("unchecked")
    public List<PropertyKey> getPropertyKeys(String graphSpace, String graph) {
        Map<String, String> propertyKeysKvs = this.metaDriver.scanWithPrefix(
                propertyKeyPrefix(graphSpace, graph));
        List<PropertyKey> propertyKeys =
                new ArrayList<>(propertyKeysKvs.size());
        for (String value : propertyKeysKvs.values()) {
            propertyKeys.add(this.parse(value, PropertyKey::fromMap));
        }
        return propertyKeys;
    }

    @SuppressWarnings("unchecked")
    public VertexLabel getVertexLabel(String graphSpace, String graph,
                                      Id vertexLabel) {
        String content = this.metaDriver.get(vertexLabelIdKey(graphSpace, graph,
                                                              vertexLabel));
        if (content == null || content.length() == 0) {
            return null;
        } else {
            return this.parse(content, VertexLabel::fromMap);
        }
    }

    @SuppressWarnings("unchecked")
    public VertexLabel getVertexLabel(String graphSpace, String graph,
                                      String vertexLabel) {
        String content = this.metaDriver.get(vertexLabelNameKey(graphSpace,
                                                                graph,
                                                                vertexLabel));
        if (content == null || content.length() == 0) {
            return null;
        } else {
            return this.parse(content, VertexLabel::fromMap);
        }
    }

    @SuppressWarnings("unchecked")
    public List<VertexLabel> getVertexLabels(String graphSpace, String graph) {
        Map<String, String> vertexLabelKvs = this.metaDriver.scanWithPrefix(
                vertexLabelPrefix(graphSpace, graph));
        List<VertexLabel> vertexLabels =
                new ArrayList<>(vertexLabelKvs.size());
        for (String value : vertexLabelKvs.values()) {
            vertexLabels.add(this.parse(value, VertexLabel::fromMap));
        }
        return vertexLabels;
    }

    @SuppressWarnings("unchecked")
    public EdgeLabel getEdgeLabel(String graphSpace, String graph,
                                  Id edgeLabel) {
        String content = this.metaDriver.get(edgeLabelIdKey(graphSpace, graph,
                                                            edgeLabel));
        if (content == null || content.length() == 0) {
            return null;
        } else {
            return this.parse(content, EdgeLabel::fromMap);
        }
    }

    @SuppressWarnings("unchecked")
    public EdgeLabel getEdgeLabel(String graphSpace, String graph,
                                  String edgeLabel) {
        String content = this.metaDriver.get(edgeLabelNameKey(graphSpace,
                                                              graph,
                                                              edgeLabel));
        if (content == null || content.length() == 0) {
            return null;
        } else {
            return this.parse(content, EdgeLabel::fromMap);
        }
    }

    @SuppressWarnings("unchecked")
    public List<EdgeLabel> getEdgeLabels(String graphSpace, String graph) {
        Map<String, String> edgeLabelKvs = this.metaDriver.scanWithPrefix(
                edgeLabelPrefix(graphSpace, graph));
        List<EdgeLabel> edgeLabels =
                new ArrayList<>(edgeLabelKvs.size());
        for (String value : edgeLabelKvs.values()) {
            edgeLabels.add(this.parse(value, EdgeLabel::fromMap));
        }
        return edgeLabels;
    }

    @SuppressWarnings("unchecked")
    public IndexLabel getIndexLabel(String graphSpace, String graph,
                                    Id indexLabel) {
        String content = this.metaDriver.get(indexLabelIdKey(graphSpace, graph,
                                                             indexLabel));
        if (content == null || content.length() == 0) {
            return null;
        } else {
            return this.parse(content, IndexLabel::fromMap);
        }
    }

    @SuppressWarnings("unchecked")
    public IndexLabel getIndexLabel(String graphSpace, String graph,
                                    String edgeLabel) {
        String content = this.metaDriver.get(indexLabelNameKey(graphSpace,
                                                               graph,
                                                               edgeLabel));
        if (content == null || content.length() == 0) {
            return null;
        } else {
            return this.parse(content, IndexLabel::fromMap);
        }
    }

    @SuppressWarnings("unchecked")
    public List<IndexLabel> getIndexLabels(String graphSpace, String graph) {
        Map<String, String> indexLabelKvs = this.metaDriver.scanWithPrefix(
                indexLabelPrefix(graphSpace, graph));
        List<IndexLabel> indexLabels =
                new ArrayList<>(indexLabelKvs.size());
        for (String value : indexLabelKvs.values()) {
            indexLabels.add(this.parse(value, IndexLabel::fromMap));
        }
        return indexLabels;
    }

    private String propertyKeyPrefix(String graphSpace, String graph) {
        // HUGEGRAPH/{cluster}/GRAPHSPACE/{graphspace}/GRAPH/{graph
        // }/SCHEMA/PROPERTY_KEY/NAME
        return String.join(META_PATH_DELIMITER,
                           META_PATH_HUGEGRAPH,
                           this.cluster,
                           META_PATH_GRAPHSPACE,
                           graphSpace,
                           graph,
                           META_PATH_SCHEMA,
                           META_PATH_PROPERTY_KEY,
                           META_PATH_NAME);
    }

    private String propertyKeyIdKey(String graphSpace, String graph, Id id) {
        // HUGEGRAPH/{cluster}/GRAPHSPACE/{graphspace}/GRAPH/{graph
        // }/SCHEMA/PROPERTY_KEY/ID/{id}
        return String.join(META_PATH_DELIMITER,
                           META_PATH_HUGEGRAPH,
                           this.cluster,
                           META_PATH_GRAPHSPACE,
                           graphSpace,
                           graph,
                           META_PATH_SCHEMA,
                           META_PATH_PROPERTY_KEY,
                           META_PATH_ID,
                           id.asString());
    }

    private String propertyKeyNameKey(String graphSpace, String graph,
                                      String name) {
        // HUGEGRAPH/{cluster}/GRAPHSPACE/{graphspace}/GRAPH/{graph
        // }/SCHEMA/PROPERTY_KEY/NAME/{name}
        return String.join(META_PATH_DELIMITER,
                           META_PATH_HUGEGRAPH,
                           this.cluster,
                           META_PATH_GRAPHSPACE,
                           graphSpace,
                           graph,
                           META_PATH_SCHEMA,
                           META_PATH_PROPERTY_KEY,
                           META_PATH_NAME,
                           name);
    }

    private String vertexLabelPrefix(String graphSpace, String graph) {
        // HUGEGRAPH/{cluster}/GRAPHSPACE/{graphspace}/GRAPH/{graph
        // }/SCHEMA/VERTEX_LABEL/NAME
        return String.join(META_PATH_DELIMITER,
                           META_PATH_HUGEGRAPH,
                           this.cluster,
                           META_PATH_GRAPHSPACE,
                           graphSpace,
                           graph,
                           META_PATH_SCHEMA,
                           META_PATH_VERTEX_LABEL,
                           META_PATH_NAME);
    }

    private String vertexLabelIdKey(String graphSpace, String graph, Id id) {
        // HUGEGRAPH/{cluster}/GRAPHSPACE/{graphspace}/GRAPH/{graph
        // }/SCHEMA/VERTEX_LABEL/ID/{id}
        return String.join(META_PATH_DELIMITER,
                           META_PATH_HUGEGRAPH,
                           this.cluster,
                           META_PATH_GRAPHSPACE,
                           graphSpace,
                           graph,
                           META_PATH_SCHEMA,
                           META_PATH_VERTEX_LABEL,
                           META_PATH_ID,
                           id.asString());
    }

    private String vertexLabelNameKey(String graphSpace, String graph,
                                      String name) {
        // HUGEGRAPH/{cluster}/GRAPHSPACE/{graphspace}/GRAPH/{graph
        // }/SCHEMA/VERTEX_LABEL/NAME/{name}
        return String.join(META_PATH_DELIMITER,
                           META_PATH_HUGEGRAPH,
                           this.cluster,
                           META_PATH_GRAPHSPACE,
                           graphSpace,
                           graph,
                           META_PATH_SCHEMA,
                           META_PATH_VERTEX_LABEL,
                           META_PATH_NAME,
                           name);
    }

    private String edgeLabelPrefix(String graphSpace, String graph) {
        // HUGEGRAPH/{cluster}/GRAPHSPACE/{graphspace}/GRAPH/{graph
        // }/SCHEMA/PROPERTYKEY/NAME
        return String.join(META_PATH_DELIMITER,
                           META_PATH_HUGEGRAPH,
                           this.cluster,
                           META_PATH_GRAPHSPACE,
                           graphSpace,
                           graph,
                           META_PATH_SCHEMA,
                           META_PATH_EDGE_LABEL,
                           META_PATH_NAME);
    }

    private String edgeLabelIdKey(String graphSpace, String graph, Id id) {
        // HUGEGRAPH/{cluster}/GRAPHSPACE/{graphspace}/GRAPH/{graph
        // }/SCHEMA/PROPERTYKEY/ID/{id}
        return String.join(META_PATH_DELIMITER,
                           META_PATH_HUGEGRAPH,
                           this.cluster,
                           META_PATH_GRAPHSPACE,
                           graphSpace,
                           graph,
                           META_PATH_SCHEMA,
                           META_PATH_EDGE_LABEL,
                           META_PATH_ID,
                           id.asString());
    }

    private String edgeLabelNameKey(String graphSpace, String graph,
                                    String name) {
        // HUGEGRAPH/{cluster}/GRAPHSPACE/{graphspace}/GRAPH/{graph
        // }/SCHEMA/EDGE_LABEL/NAME/{name}
        return String.join(META_PATH_DELIMITER,
                           META_PATH_HUGEGRAPH,
                           this.cluster,
                           META_PATH_GRAPHSPACE,
                           graphSpace,
                           graph,
                           META_PATH_SCHEMA,
                           META_PATH_EDGE_LABEL,
                           META_PATH_NAME,
                           name);
    }

    private String indexLabelPrefix(String graphSpace, String graph) {
        // HUGEGRAPH/{cluster}/GRAPHSPACE/{graphspace}/GRAPH/{graph
        // }/SCHEMA/INDEX_LABEL/NAME
        return String.join(META_PATH_DELIMITER,
                           META_PATH_HUGEGRAPH,
                           this.cluster,
                           META_PATH_GRAPHSPACE,
                           graphSpace,
                           graph,
                           META_PATH_SCHEMA,
                           META_PATH_INDEX_LABEL,
                           META_PATH_NAME);
    }

    private String indexLabelIdKey(String graphSpace, String graph, Id id) {
        // HUGEGRAPH/{cluster}/GRAPHSPACE/{graphspace}/GRAPH/{graph
        // }/SCHEMA/INDEX_LABEL/ID/{id}
        return String.join(META_PATH_DELIMITER,
                           META_PATH_HUGEGRAPH,
                           this.cluster,
                           META_PATH_GRAPHSPACE,
                           graphSpace,
                           graph,
                           META_PATH_SCHEMA,
                           META_PATH_INDEX_LABEL,
                           META_PATH_ID,
                           id.asString());
    }

    private String indexLabelNameKey(String graphSpace, String graph,
                                     String name) {
        // HUGEGRAPH/{cluster}/GRAPHSPACE/{graphspace}/GRAPH/{graph
        // }/SCHEMA/INDEX_LABEL/NAME/{name}
        return String.join(META_PATH_DELIMITER,
                           META_PATH_HUGEGRAPH,
                           this.cluster,
                           META_PATH_GRAPHSPACE,
                           graphSpace,
                           graph,
                           META_PATH_SCHEMA,
                           META_PATH_INDEX_LABEL,
                           META_PATH_NAME,
                           name);
    }

    private String graphNameKey(String graphSpace, String graph) {
        // HUGEGRAPH/{cluster}/GRAPHSPACE/{graphspace}/GRAPH/{graph}/SCHEMA
        return String.join(META_PATH_DELIMITER,
                           META_PATH_HUGEGRAPH,
                           this.cluster,
                           META_PATH_GRAPHSPACE,
                           graphSpace,
                           graph,
                           META_PATH_SCHEMA);
    }

    public void clearAllSchema(String graphSpace, String graph) {
        this.bump(graphSpace, graph, TxnRequest.newBuilder().addOps(
                TxnOp.newBuilder().setType(TxnOp.Type.DELETE_PREFIX)
                     .setKey(graphNameKey(graphSpace, graph))));
    }

    /**
     * Creates the graph in one commit: writes its config, which must be absent, deletes any
     * schema an earlier graph of the name left, and starts a new incarnation of its record
     * with CREATE, which PD refuses while the record is CREATING or LIVE. Of two Servers
     * creating one graph only one gets past it. The graph is CREATING
     * until {@link #commitCreate}, and no other Server opens it meanwhile (see
     * {@link #adoptRecord}). A create that fails drops the graph with {@link #dropGraph}; one
     * whose Server died leaves it CREATING until the graph is dropped by name.
     * <p>
     * The commit also needs the drop marker of the name to be absent: while a Server still
     * clears the stores of the dropped incarnation, a new one would lose what it wrote.
     *
     * @return the new incarnation, or 0 if the graph exists or is being created
     * @throws HugeException if the drop of an earlier graph of the name has not finished
     */
    public long createGraph(String graphSpace, String graph, Map<String, Object> configs) {
        String confKey = this.graphConfKey(graphSpace, graph);
        TxnRequest.Builder txn = TxnRequest.newBuilder()
                .addCompares(compare(confKey, TxnCompare.Kind.ABSENT, ""))
                .addCompares(compare(this.droppingKey(graphSpace, graph),
                                     TxnCompare.Kind.ABSENT, ""))
                .addOps(TxnOp.newBuilder().setType(TxnOp.Type.PUT).setKey(confKey)
                             .setValue(JsonUtil.toJson(configs)))
                .addOps(TxnOp.newBuilder().setType(TxnOp.Type.DELETE_PREFIX)
                             .setKey(graphNameKey(graphSpace, graph)));
        TxnResponse response = this.tryCommit(graphSpace, graph, txn, TxnRecord.Op.CREATE, 0L);
        if (response.getSucceeded()) {
            this.creating.put(this.recordKey(graphSpace, graph), response.getIncarnation());
            return response.getIncarnation();
        }
        if (response.getFailure() == TxnResponse.Failure.COMPARE_FAILED &&
            response.getFailedCompare() == 1) {
            throw new HugeException("Graph '%s' in graph space '%s' is being dropped: the " +
                                    "stores of the dropped graph are not cleared yet",
                                    graph, graphSpace);
        }
        if (response.getFailure() == TxnResponse.Failure.COMPARE_FAILED ||
            response.getFailure() == TxnResponse.Failure.GRAPH_EXISTS) {
            return 0L;
        }
        throw rejected(graphSpace, graph, response);
    }

    /**
     * Makes a graph this Server created LIVE, once it initialized; fails if the graph was
     * dropped meanwhile
     */
    public void commitCreate(String graphSpace, String graph, long incarnation) {
        try {
            this.commit(graphSpace, graph, TxnRequest.newBuilder(), TxnRecord.Op.COMMIT_CREATE,
                        incarnation);
        } finally {
            this.creating.remove(this.recordKey(graphSpace, graph), incarnation);
        }
    }

    /**
     * Drops a graph incarnation in one commit: deletes the graph config and the schema, and
     * marks the record DROPPED, after which writes of the incarnation are rejected. PD refuses
     * it unless the record is CREATING or LIVE with this incarnation, so a stale instance can't
     * drop a newer incarnation, and of two drops only one succeeds. Clearing the stores is up
     * to the caller, after this commit.
     * <p>
     * Clearing the stores is outside the commit, so a caller that clears them says so, and
     * the commit then writes the drop marker of the graph with the id of this Server process.
     * {@link #createGraph} is refused while the marker exists, so no new incarnation writes
     * to the stores while they are cleared. The caller removes it with {@link #finishDrop}.
     *
     * @param clearing whether the caller clears the stores of the graph after the commit
     * @return the response; GRAPH_DROPPED means this incarnation was dropped already
     */
    public TxnResponse dropGraph(String graphSpace, String graph, long incarnation,
                                 boolean clearing) {
        TxnRequest.Builder txn = TxnRequest.newBuilder()
                .addOps(TxnOp.newBuilder().setType(TxnOp.Type.DELETE)
                             .setKey(this.graphConfKey(graphSpace, graph)))
                .addOps(TxnOp.newBuilder().setType(TxnOp.Type.DELETE_PREFIX)
                             .setKey(graphNameKey(graphSpace, graph)));
        if (clearing) {
            txn.addOps(TxnOp.newBuilder().setType(TxnOp.Type.PUT)
                            .setKey(this.droppingKey(graphSpace, graph))
                            .setValue(this.dropOwner()));
        }
        try {
            return this.tryCommit(graphSpace, graph, txn, TxnRecord.Op.DROP, incarnation);
        } finally {
            this.creating.remove(this.recordKey(graphSpace, graph), incarnation);
        }
    }

    /**
     * Whether the drop marker of the graph is the one this Server process wrote: its drop
     * has not finished, and only this process may clear the stores and finish it. Asked with
     * a commit that only compares, so the answer comes through PD's raft like the DROP did.
     */
    public boolean ownsDrop(String graphSpace, String graph) {
        TxnRequest txn = TxnRequest.newBuilder()
                                   .addCompares(compare(this.droppingKey(graphSpace, graph),
                                                        TxnCompare.Kind.EQUALS,
                                                        this.dropOwner()))
                                   .setRequestId(UUID.randomUUID().toString()).build();
        return this.metaDriver.commit(txn).getSucceeded();
    }

    /**
     * Made before the first DROP commit that carries it and kept as long as this manager, so
     * a drop that is sent again, whatever became of its first commit, finds its own marker
     */
    private synchronized String dropOwner() {
        if (this.dropOwner == null) {
            this.dropOwner = UUID.randomUUID().toString();
        }
        return this.dropOwner;
    }

    /**
     * Whether a drop of the graph has not finished, on any Server
     */
    public boolean dropPending(String graphSpace, String graph) {
        String owner = this.metaDriver.get(this.droppingKey(graphSpace, graph));
        return owner != null && !owner.isEmpty();
    }

    /**
     * Removes the drop marker this Server process wrote, once it cleared the stores: the
     * name can be created again
     */
    public void finishDrop(String graphSpace, String graph) {
        if (!this.removeDropMarker(graphSpace, graph, this.dropOwner())) {
            LOG.warn("The drop marker of graph '{}' in graph space '{}' was released " +
                     "before its stores were cleared", graph, graphSpace);
        }
    }

    /**
     * Removes the drop marker of a graph whatever Server wrote it. Only for a drop whose
     * Server stopped before it finished: a Server that still clears the stores would clear
     * what a new graph of the name wrote.
     *
     * @return false if the graph has no drop marker
     */
    public boolean releaseDrop(String graphSpace, String graph) {
        String owner = this.metaDriver.get(this.droppingKey(graphSpace, graph));
        if (owner == null || owner.isEmpty()) {
            return false;
        }
        this.removeDropMarker(graphSpace, graph, owner);
        return true;
    }

    private boolean removeDropMarker(String graphSpace, String graph, String owner) {
        String key = this.droppingKey(graphSpace, graph);
        TxnRequest txn = TxnRequest.newBuilder()
                                   .addCompares(compare(key, TxnCompare.Kind.EQUALS, owner))
                                   .addOps(TxnOp.newBuilder().setType(TxnOp.Type.DELETE)
                                                .setKey(key))
                                   .setRequestId(UUID.randomUUID().toString()).build();
        return this.metaDriver.commit(txn).getSucceeded();
    }

    /**
     * The record a graph instance opens with, from one ADOPT commit through PD's raft: an
     * absent record (a graph from before this change, or one loaded from a local config) is
     * created LIVE with incarnation 1, any other is returned as it is. Every BUMP of the
     * instance carries its incarnation, so it can't write into a later incarnation; a DROPPED
     * record keeps its incarnation, so its writes are rejected. A CREATING record opens only on
     * the Server that is creating it: anywhere else the graph is not available until its
     * create commits.
     *
     * @throws GraphCreatingException if another Server is creating the graph
     */
    public TxnResponse adoptRecord(String graphSpace, String graph) {
        TxnResponse response = this.commit(graphSpace, graph, TxnRequest.newBuilder(),
                                           TxnRecord.Op.ADOPT, 0L);
        String key = this.recordKey(graphSpace, graph);
        if (CREATING.equals(response.getState()) &&
            !Long.valueOf(response.getIncarnation()).equals(this.creating.get(key))) {
            throw new GraphCreatingException(graphSpace, graph);
        }
        if (DROPPED.equals(response.getState())) {
            LOG.warn("Graph '{}' in graph space '{}' opens with a DROPPED schema sync " +
                     "record (incarnation {})", graph, graphSpace, response.getIncarnation());
        }
        return response;
    }

    /**
     * The incarnation of the graph while its record is CREATING, else 0
     */
    public long creatingIncarnation(String graphSpace, String graph) {
        Map<String, Object> value = this.readRecord(graphSpace, graph);
        if (value == null || !CREATING.equals(value.get("state"))) {
            return 0L;
        }
        return ((Number) value.get("inc")).longValue();
    }

    private static TxnCompare compare(String key, TxnCompare.Kind kind, String value) {
        return TxnCompare.newBuilder().setKey(key).setKind(kind).setValue(value).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readRecord(String graphSpace, String graph) {
        String record = this.metaDriver.get(this.recordKey(graphSpace, graph));
        if (record == null || record.isEmpty()) {
            return null;
        }
        return JsonUtil.fromJson(record, Map.class);
    }

    private void bump(String graphSpace, String graph, TxnRequest.Builder txn) {
        this.commit(graphSpace, graph, txn, TxnRecord.Op.BUMP, this.expectedIncarnation());
    }

    /**
     * Like bump, but returns a rejected commit instead of throwing
     */
    private TxnResponse tryBump(String graphSpace, String graph, TxnRequest.Builder txn) {
        return this.tryCommit(graphSpace, graph, txn, TxnRecord.Op.BUMP,
                              this.expectedIncarnation());
    }

    private long expectedIncarnation() {
        return this.incarnation == null ? 0L : this.incarnation.get();
    }

    private TxnResponse commit(String graphSpace, String graph, TxnRequest.Builder txn,
                               TxnRecord.Op op, long expectedIncarnation) {
        TxnResponse response = this.tryCommit(graphSpace, graph, txn, op, expectedIncarnation);
        if (!response.getSucceeded()) {
            throw rejected(graphSpace, graph, response);
        }
        return response;
    }

    /**
     * Each call is one logical commit with a request id of its own: the PD client sends it
     * again with the same id when the connection fails, and PD answers that resend with the
     * outcome of the first apply
     */
    private TxnResponse tryCommit(String graphSpace, String graph, TxnRequest.Builder txn,
                                  TxnRecord.Op op, long expectedIncarnation) {
        txn.setRecord(TxnRecord.newBuilder().setKey(this.recordKey(graphSpace, graph))
                               .setOp(op).setExpectedIncarnation(expectedIncarnation));
        txn.setRequestId(UUID.randomUUID().toString());
        return this.metaDriver.commit(txn.build());
    }

    private static HugeException rejected(String graphSpace, String graph,
                                          TxnResponse response) {
        String reason;
        switch (response.getFailure()) {
            case GRAPH_DROPPED:
            case INCARNATION_MISMATCH:
                reason = "the graph was dropped or recreated";
                break;
            case GRAPH_EXISTS:
                reason = "the graph exists or is being created";
                break;
            case INVALID_RECORD:
                reason = "its schema sync record in PD can't be read";
                break;
            default:
                reason = "a compare failed";
                break;
        }
        return new HugeException("The schema change of graph '%s' in graph space '%s' was " +
                                 "rejected (%s): %s", graph, graphSpace,
                                 response.getFailure(), reason);
    }

    /**
     * Adds the writes of the ID and NAME keys of the schema element
     *
     * @return the content written to both keys
     */
    private String addPuts(TxnRequest.Builder txn, String graphSpace, String graph,
                           SchemaElement schema) {
        String content = serialize(schema);
        for (String key : this.schemaKeys(graphSpace, graph, schema)) {
            txn.addOps(TxnOp.newBuilder().setType(TxnOp.Type.PUT).setKey(key)
                            .setValue(content));
        }
        return content;
    }

    /**
     * Like addPuts, for an element that must not exist: with a compare per key
     */
    private String addCreates(TxnRequest.Builder txn, String graphSpace, String graph,
                              SchemaElement schema) {
        for (String key : this.schemaKeys(graphSpace, graph, schema)) {
            txn.addCompares(compare(key, TxnCompare.Kind.ABSENT, ""));
        }
        return this.addPuts(txn, graphSpace, graph, schema);
    }

    /**
     * The schema element of a value read from PD, which it keeps as its stored content
     */
    private <T extends SchemaElement> T parse(String content,
                                              BiFunction<Map<String, Object>, HugeGraph, T>
                                                      fromMap) {
        T schema = fromMap.apply(toMap(content), this.graph);
        schema.stored(content);
        return schema;
    }

    private SchemaElement parse(HugeType type, String content) {
        switch (type) {
            case PROPERTY_KEY:
                return this.parse(content, PropertyKey::fromMap);
            case VERTEX_LABEL:
                return this.parse(content, VertexLabel::fromMap);
            case EDGE_LABEL:
                return this.parse(content, EdgeLabel::fromMap);
            case INDEX_LABEL:
                return this.parse(content, IndexLabel::fromMap);
            default:
                throw new AssertionError(String.format(
                        "Invalid type '%s' for a schema commit", type));
        }
    }

    private List<String> schemaKeys(String graphSpace, String graph, SchemaElement schema) {
        Id id = schema.id();
        String name = schema.name();
        switch (schema.type()) {
            case PROPERTY_KEY:
                return Arrays.asList(propertyKeyIdKey(graphSpace, graph, id),
                                     propertyKeyNameKey(graphSpace, graph, name));
            case VERTEX_LABEL:
                return Arrays.asList(vertexLabelIdKey(graphSpace, graph, id),
                                     vertexLabelNameKey(graphSpace, graph, name));
            case EDGE_LABEL:
                return Arrays.asList(edgeLabelIdKey(graphSpace, graph, id),
                                     edgeLabelNameKey(graphSpace, graph, name));
            case INDEX_LABEL:
                return Arrays.asList(indexLabelIdKey(graphSpace, graph, id),
                                     indexLabelNameKey(graphSpace, graph, name));
            default:
                throw new AssertionError(String.format(
                        "Invalid type '%s' for a schema commit", schema.type()));
        }
    }

    /**
     * HUGEGRAPH/{cluster}/SCHEMA_SYNC/{graphspace}/{graph}, outside the SCHEMA directory of the
     * graph that clearAllSchema deletes by prefix
     */
    private String recordKey(String graphSpace, String graph) {
        return String.join(META_PATH_DELIMITER,
                           META_PATH_HUGEGRAPH,
                           this.cluster,
                           META_PATH_SCHEMA_SYNC,
                           graphSpace,
                           graph);
    }

    private String graphConfKey(String graphSpace, String graph) {
        return GraphMetaManager.graphConfKey(this.cluster, graphSpace, graph);
    }

    /**
     * HUGEGRAPH/{cluster}/GRAPH_DROPPING/{graphspace}/{graph}, the drop marker of a graph:
     * the id of the Server process that dropped it and has not cleared its stores yet
     */
    private String droppingKey(String graphSpace, String graph) {
        return String.join(META_PATH_DELIMITER,
                           META_PATH_HUGEGRAPH,
                           this.cluster,
                           META_PATH_GRAPH_DROPPING,
                           graphSpace,
                           graph);
    }

    /**
     * Opening a graph that another Server is creating: it is not available until its create
     * commits, or until a drop of it releases the name
     */
    public static class GraphCreatingException extends HugeException {

        private static final long serialVersionUID = -3482961513470176541L;

        public GraphCreatingException(String graphSpace, String graph) {
            super("Graph '%s' in graph space '%s' is being created and is not available yet; " +
                  "if its create was cut short, drop the graph", graph, graphSpace);
        }
    }
}
