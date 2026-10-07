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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hugegraph.HugeException;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.meta.MetaDriver;
import org.apache.hugegraph.pd.grpc.kv.TxnCompare;
import org.apache.hugegraph.pd.grpc.kv.TxnOp;
import org.apache.hugegraph.pd.grpc.kv.TxnRecord;
import org.apache.hugegraph.pd.grpc.kv.TxnRequest;
import org.apache.hugegraph.pd.grpc.kv.TxnResponse;
import org.apache.hugegraph.schema.EdgeLabel;
import org.apache.hugegraph.schema.IndexLabel;
import org.apache.hugegraph.schema.PropertyKey;
import org.apache.hugegraph.schema.SchemaElement;
import org.apache.hugegraph.schema.SchemaLabel;
import org.apache.hugegraph.schema.VertexLabel;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.JsonUtil;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

public class SchemaMetaManager extends AbstractMetaManager {

    private static final Logger LOG = Log.logger(SchemaMetaManager.class);

    /**
     * Commits tried by addIndexLabel while other Servers keep changing the base label
     */
    private static final int INDEX_LABEL_COMMIT_ATTEMPTS = 3;

    private final HugeGraph graph;
    // The incarnation of the graph instance that writes, shared by its schema transactions;
    // null where no single graph instance writes, which skips the incarnation check
    private final AtomicLong incarnation;

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
     * Writes the ID and NAME keys of the schema elements in one commit that bumps the graph
     * record, so a crash can't leave half of a change behind
     */
    public void saveSchema(String graphSpace, String graph, SchemaElement... schemas) {
        TxnRequest.Builder txn = TxnRequest.newBuilder();
        for (SchemaElement schema : schemas) {
            this.addPuts(txn, graphSpace, graph, schema);
        }
        this.bump(graphSpace, graph, txn);
    }

    /**
     * Writes the index label and adds it to its base label (VL/EL) in one commit, and returns
     * the base label as written.
     * <p>
     * The JVM locks of SchemaTransactionV2 don't serialize two Servers adding index labels to
     * the same base label: both would read it, add their own index label, and the later write
     * would drop the earlier reference. So the base label is read from PD, and the commit only
     * holds while its ID key still has the value read; otherwise it is read again and retried.
     * This covers only this read-modify-write of the base label. Whether other concurrent DDL
     * across Servers needs conditional commits as well is an open question for the maintainers
     * (design section 7, concurrency and idempotency).
     */
    public SchemaLabel addIndexLabel(String graphSpace, String graph, SchemaLabel baseLabel,
                                     IndexLabel indexLabel) {
        String idKey = this.schemaKeys(graphSpace, graph, baseLabel).get(0);
        for (int i = 0; i < INDEX_LABEL_COMMIT_ATTEMPTS; i++) {
            String stored = this.metaDriver.get(idKey);
            if (stored == null || stored.isEmpty()) {
                throw new HugeException("The %s '%s' of index label '%s' doesn't exist",
                                        baseLabel.type().readableName(), baseLabel.name(),
                                        indexLabel.name());
            }
            SchemaLabel label = this.parseLabel(baseLabel.type(), stored);
            label.addIndexLabel(indexLabel.id());

            TxnRequest.Builder txn = TxnRequest.newBuilder().addCompares(
                    TxnCompare.newBuilder().setKey(idKey).setKind(TxnCompare.Kind.EQUALS)
                              .setValue(stored));
            this.addPuts(txn, graphSpace, graph, indexLabel);
            this.addPuts(txn, graphSpace, graph, label);
            TxnResponse response = this.tryBump(graphSpace, graph, txn);
            if (response.getSucceeded()) {
                return label;
            }
            if (response.getFailure() != TxnResponse.Failure.COMPARE_FAILED) {
                throw rejected(graphSpace, graph, response);
            }
            LOG.info("The {} '{}' changed while index label '{}' was added to it, retry",
                     baseLabel.type().readableName(), baseLabel.name(), indexLabel.name());
        }
        throw new HugeException("Failed to add index label '%s' to %s '%s': it kept " +
                                "changing on other servers in %s attempts", indexLabel.name(),
                                baseLabel.type().readableName(), baseLabel.name(),
                                INDEX_LABEL_COMMIT_ATTEMPTS);
    }

    public void removeSchema(String graphSpace, String graph, SchemaElement schema) {
        TxnRequest.Builder txn = TxnRequest.newBuilder();
        for (String key : this.schemaKeys(graphSpace, graph, schema)) {
            txn.addOps(TxnOp.newBuilder().setType(TxnOp.Type.DELETE).setKey(key));
        }
        this.bump(graphSpace, graph, txn);
    }

    @SuppressWarnings("unchecked")
    public PropertyKey getPropertyKey(String graphSpace, String graph,
                                      Id propertyKey) {
        String content = this.metaDriver.get(propertyKeyIdKey(graphSpace, graph,
                                                              propertyKey));
        if (content == null || content.length() == 0) {
            return null;
        } else {
            return PropertyKey.fromMap(JsonUtil.fromJson(content, Map.class), this.graph);
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
            return PropertyKey.fromMap(JsonUtil.fromJson(content, Map.class), this.graph);
        }
    }

    @SuppressWarnings("unchecked")
    public List<PropertyKey> getPropertyKeys(String graphSpace, String graph) {
        Map<String, String> propertyKeysKvs = this.metaDriver.scanWithPrefix(
                propertyKeyPrefix(graphSpace, graph));
        List<PropertyKey> propertyKeys =
                new ArrayList<>(propertyKeysKvs.size());
        for (String value : propertyKeysKvs.values()) {
            propertyKeys.add(PropertyKey.fromMap(JsonUtil.fromJson(value, Map.class), this.graph));
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
            return VertexLabel.fromMap(JsonUtil.fromJson(content, Map.class), this.graph);
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
            return VertexLabel.fromMap(JsonUtil.fromJson(content, Map.class), this.graph);
        }
    }

    @SuppressWarnings("unchecked")
    public List<VertexLabel> getVertexLabels(String graphSpace, String graph) {
        Map<String, String> vertexLabelKvs = this.metaDriver.scanWithPrefix(
                vertexLabelPrefix(graphSpace, graph));
        List<VertexLabel> vertexLabels =
                new ArrayList<>(vertexLabelKvs.size());
        for (String value : vertexLabelKvs.values()) {
            vertexLabels.add(VertexLabel.fromMap(
                    JsonUtil.fromJson(value, Map.class), this.graph));
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
            return EdgeLabel.fromMap(JsonUtil.fromJson(content, Map.class), this.graph);
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
            return EdgeLabel.fromMap(JsonUtil.fromJson(content, Map.class), this.graph);
        }
    }

    @SuppressWarnings("unchecked")
    public List<EdgeLabel> getEdgeLabels(String graphSpace, String graph) {
        Map<String, String> edgeLabelKvs = this.metaDriver.scanWithPrefix(
                edgeLabelPrefix(graphSpace, graph));
        List<EdgeLabel> edgeLabels =
                new ArrayList<>(edgeLabelKvs.size());
        for (String value : edgeLabelKvs.values()) {
            edgeLabels.add(EdgeLabel.fromMap(
                    JsonUtil.fromJson(value, Map.class), this.graph));
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
            return IndexLabel.fromMap(JsonUtil.fromJson(content, Map.class), this.graph);
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
            return IndexLabel.fromMap(JsonUtil.fromJson(content, Map.class), this.graph);
        }
    }

    @SuppressWarnings("unchecked")
    public List<IndexLabel> getIndexLabels(String graphSpace, String graph) {
        Map<String, String> indexLabelKvs = this.metaDriver.scanWithPrefix(
                indexLabelPrefix(graphSpace, graph));
        List<IndexLabel> indexLabels =
                new ArrayList<>(indexLabelKvs.size());
        for (String value : indexLabelKvs.values()) {
            indexLabels.add(IndexLabel.fromMap(
                    JsonUtil.fromJson(value, Map.class), this.graph));
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
     * Starts a new incarnation of the graph; it must come before the graph writes schema. PD
     * rejects it with GRAPH_EXISTS while the record is LIVE, so of two Servers creating the
     * same graph only one gets the name: that response is returned, with the LIVE incarnation,
     * and any other rejection throws.
     */
    public TxnResponse createGraph(String graphSpace, String graph) {
        TxnResponse response = this.tryCommit(graphSpace, graph, TxnRequest.newBuilder(),
                                              TxnRecord.Op.CREATE, 0L);
        if (!response.getSucceeded() &&
            response.getFailure() != TxnResponse.Failure.GRAPH_EXISTS) {
            throw rejected(graphSpace, graph, response);
        }
        return response;
    }

    /**
     * Clears the schema of the graph and marks it DROPPED in one commit, after which writes
     * of this incarnation are rejected. A dropping instance passes its own incarnation, so it
     * can't drop a newer one; 0 skips the check.
     */
    public void dropGraph(String graphSpace, String graph, long expectedIncarnation) {
        TxnRequest.Builder txn = TxnRequest.newBuilder().addOps(
                TxnOp.newBuilder().setType(TxnOp.Type.DELETE_PREFIX)
                     .setKey(graphNameKey(graphSpace, graph)));
        this.commit(graphSpace, graph, txn, TxnRecord.Op.DROP, expectedIncarnation);
    }

    /**
     * The incarnation a graph instance opens with, read from its record: every BUMP and DROP
     * of the instance then carries it, so the instance can't write into a later incarnation.
     * An absent record (a graph created before the upgrade) is created here by an empty BUMP,
     * so the instance never writes with the unchecked incarnation 0. The incarnation of a
     * DROPPED record is kept as well, so the writes of the instance are rejected as
     * GRAPH_DROPPED while the graph stays dropped, and as INCARNATION_MISMATCH once it is
     * recreated.
     */
    public long openIncarnation(String graphSpace, String graph) {
        Map<String, Object> value = this.readRecord(graphSpace, graph);
        if (value == null) {
            return this.commit(graphSpace, graph, TxnRequest.newBuilder(), TxnRecord.Op.BUMP,
                               0L).getIncarnation();
        }
        if (!"LIVE".equals(value.get("state"))) {
            LOG.warn("Graph '{}' in graph space '{}' opens with a {} schema sync record {}",
                     graph, graphSpace, value.get("state"), value);
        }
        return ((Number) value.get("inc")).longValue();
    }

    /**
     * The incarnation of the graph while its record is LIVE, 0 if the record is absent or
     * DROPPED
     */
    public long liveIncarnation(String graphSpace, String graph) {
        Map<String, Object> value = this.readRecord(graphSpace, graph);
        if (value == null || !"LIVE".equals(value.get("state"))) {
            return 0L;
        }
        return ((Number) value.get("inc")).longValue();
    }

    /**
     * Fails unless the graph record is LIVE with the given incarnation (0 skips the check).
     * The commits of an incarnation are checked by PD, but a step outside PD that can't be
     * undone, such as clearing the stores of a graph before its DROP commit, must be checked
     * before it runs: the stores of a graph are shared by all of its incarnations.
     */
    public void checkLive(String graphSpace, String graph, long incarnation) {
        if (incarnation == 0L) {
            return;
        }
        long live = this.liveIncarnation(graphSpace, graph);
        if (live != incarnation) {
            throw new HugeException("Graph '%s' in graph space '%s' was dropped or recreated " +
                                    "(incarnation %s, LIVE incarnation %s)", graph, graphSpace,
                                    incarnation, live);
        }
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
        long expected = this.incarnation == null ? 0L : this.incarnation.get();
        TxnResponse response = this.commit(graphSpace, graph, txn, TxnRecord.Op.BUMP,
                                           expected);
        if (this.incarnation != null) {
            // An incarnation not learned at open: this BUMP created the record
            this.incarnation.compareAndSet(0L, response.getIncarnation());
        }
    }

    /**
     * Like bump, but returns a rejected commit instead of throwing
     */
    private TxnResponse tryBump(String graphSpace, String graph, TxnRequest.Builder txn) {
        long expected = this.incarnation == null ? 0L : this.incarnation.get();
        TxnResponse response = this.tryCommit(graphSpace, graph, txn, TxnRecord.Op.BUMP,
                                              expected);
        if (response.getSucceeded() && this.incarnation != null) {
            this.incarnation.compareAndSet(0L, response.getIncarnation());
        }
        return response;
    }

    private TxnResponse commit(String graphSpace, String graph, TxnRequest.Builder txn,
                               TxnRecord.Op op, long expectedIncarnation) {
        TxnResponse response = this.tryCommit(graphSpace, graph, txn, op, expectedIncarnation);
        if (!response.getSucceeded()) {
            throw rejected(graphSpace, graph, response);
        }
        return response;
    }

    private TxnResponse tryCommit(String graphSpace, String graph, TxnRequest.Builder txn,
                                  TxnRecord.Op op, long expectedIncarnation) {
        txn.setRecord(TxnRecord.newBuilder().setKey(this.recordKey(graphSpace, graph))
                               .setOp(op).setExpectedIncarnation(expectedIncarnation));
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
                reason = "the graph exists";
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

    private void addPuts(TxnRequest.Builder txn, String graphSpace, String graph,
                         SchemaElement schema) {
        String content = serialize(schema);
        for (String key : this.schemaKeys(graphSpace, graph, schema)) {
            txn.addOps(TxnOp.newBuilder().setKey(key).setValue(content));
        }
    }

    @SuppressWarnings("unchecked")
    private SchemaLabel parseLabel(HugeType type, String content) {
        Map<String, Object> map = JsonUtil.fromJson(content, Map.class);
        if (type == HugeType.VERTEX_LABEL) {
            return VertexLabel.fromMap(map, this.graph);
        }
        E.checkArgument(type == HugeType.EDGE_LABEL,
                        "Invalid base type '%s' of an index label", type);
        return EdgeLabel.fromMap(map, this.graph);
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
}
