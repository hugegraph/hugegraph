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
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.exception.HugeException;
import org.apache.hugegraph.id.Id;
import org.apache.hugegraph.meta.MetaDriver;
import org.apache.hugegraph.pd.grpc.kv.TxnCompare;
import org.apache.hugegraph.pd.grpc.kv.TxnOp;
import org.apache.hugegraph.meta.SchemaSyncClient;
import org.apache.hugegraph.pd.grpc.kv.TxnRecord;
import org.apache.hugegraph.pd.grpc.kv.TxnRequest;
import org.apache.hugegraph.pd.grpc.kv.TxnResponse;
import org.apache.hugegraph.struct.schema.EdgeLabel;
import org.apache.hugegraph.struct.schema.IndexLabel;
import org.apache.hugegraph.struct.schema.PropertyKey;
import org.apache.hugegraph.struct.schema.SchemaElement;
import org.apache.hugegraph.struct.schema.VertexLabel;
import org.apache.hugegraph.util.JsonUtil;

public class SchemaMetaManager extends AbstractMetaManager {

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
     * Writes the schema elements, each as its ID and NAME keys, in one commit that bumps the
     * graph record, so a crash can't leave half of a change behind. The keys are overwritten:
     * the schema builders check for an existing name before, and one Server at a time writes
     * the schema of a graph.
     */
    public void addSchema(String graphSpace, String graph, SchemaElement... schemas) {
        TxnRequest.Builder txn = TxnRequest.newBuilder();
        for (SchemaElement schema : schemas) {
            this.addPuts(txn, graphSpace, graph, schema);
        }
        this.bump(graphSpace, graph, txn);
    }

    /**
     * Writes a changed schema element in one commit that bumps the graph record
     */
    public void updateSchema(String graphSpace, String graph, SchemaElement schema) {
        this.addSchema(graphSpace, graph, schema);
    }

    /**
     * Deletes the ID and NAME keys of the schema element in one commit that bumps the graph
     * record
     */
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
     * Starts the next incarnation of a graph in one commit: the record becomes LIVE with a new
     * incarnation, and any schema an earlier graph of the name left is deleted. The commit
     * holds only while the graph config is absent and the record is as read here, absent or
     * DROPPED. A LIVE record is never taken over: a graph opened from a local config has one,
     * and so does a create that died between its commit and its config write, which leaves a
     * name that answers "exists" until it is dropped, see {@link #dropUnfinishedCreate}. A
     * commit that got no response goes on if the record shows that it committed.
     *
     * @return the new incarnation, or 0 if the graph exists
     */
    public long createGraph(String graphSpace, String graph) {
        String recordKey = this.recordKey(graphSpace, graph);
        String stored = this.metaDriver.get(recordKey);
        TxnCompare record;
        // The incarnation PD gives the CREATE
        long expected;
        if (stored == null || stored.isEmpty()) {
            record = compare(recordKey, TxnCompare.Kind.ABSENT, "");
            expected = 1L;
        } else {
            SchemaSyncClient.Record dropped = this.readRecord(graphSpace, graph, stored);
            if (!dropped.dropped()) {
                return 0L;
            }
            record = compare(recordKey, TxnCompare.Kind.EQUALS, stored);
            expected = dropped.incarnation() + 1L;
        }
        TxnRequest.Builder txn = TxnRequest.newBuilder()
                .addCompares(compare(this.graphConfKey(graphSpace, graph),
                                     TxnCompare.Kind.ABSENT, ""))
                .addCompares(record)
                .addOps(TxnOp.newBuilder().setType(TxnOp.Type.DELETE_PREFIX)
                             .setKey(graphNameKey(graphSpace, graph)));
        TxnResponse response;
        try {
            response = this.tryCommit(graphSpace, graph, txn, TxnRecord.Op.CREATE, 0L);
        } catch (RuntimeException e) {
            /*
             * No response: the CREATE committed if the record is LIVE with the mark of a
             * CREATE and the incarnation it expected, and the config is absent. A create of
             * the name that another Server committed meanwhile looks the same: concurrent
             * creates of one name are outside the single-writer contract.
             */
            if (committed(e, () -> {
                String now = this.metaDriver.get(recordKey);
                if (now == null || now.isEmpty()) {
                    return false;
                }
                SchemaSyncClient.Record created = this.readRecord(graphSpace, graph, now);
                String config = this.metaDriver.get(this.graphConfKey(graphSpace, graph));
                return !created.dropped() && created.incarnation() == expected &&
                       "create".equals(toMap(now).get("src")) &&
                       (config == null || config.isEmpty());
            })) {
                return expected;
            }
            throw e;
        }
        if (response.getSucceeded()) {
            return response.getIncarnation();
        }
        if (response.getFailure() == TxnResponse.Failure.COMPARE_FAILED) {
            return 0L;
        }
        throw rejected(graphSpace, graph, response);
    }

    /**
     * Whether a commit that got no response committed, as the state read from PD shows; a
     * failed read is attached to the cause and counts as not committed
     */
    private static boolean committed(RuntimeException cause, BooleanSupplier check) {
        try {
            return check.getAsBoolean();
        } catch (RuntimeException e) {
            cause.addSuppressed(e);
            return false;
        }
    }

    private SchemaSyncClient.Record storedRecord(String graphSpace, String graph) {
        String stored = this.metaDriver.get(this.recordKey(graphSpace, graph));
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        return this.readRecord(graphSpace, graph, stored);
    }

    /**
     * Writes the config of a graph this Server is creating, the last step of the create, in
     * one commit with a BUMP of its incarnation. A drop that committed since the CREATE, from
     * this or another Server, rejects it, so the create fails instead of leaving a config next
     * to a DROPPED record, which would keep the name taken with no graph to drop. A commit
     * that got no response is done when PD holds this config and the incarnation is LIVE.
     *
     * @throws HugeException if PD rejects the commit, GRAPH_DROPPED or INCARNATION_MISMATCH,
     *                       or the commit got no response and PD does not hold the config
     */
    public void publishGraphConfig(String graphSpace, String graph, Map<String, Object> configs,
                                   long incarnation) {
        String configKey = this.graphConfKey(graphSpace, graph);
        String config = JsonUtil.toJson(configs);
        TxnRequest.Builder txn = TxnRequest.newBuilder()
                .addOps(TxnOp.newBuilder().setType(TxnOp.Type.PUT)
                             .setKey(configKey).setValue(config));
        TxnResponse response;
        try {
            response = this.tryCommit(graphSpace, graph, txn, TxnRecord.Op.BUMP, incarnation);
        } catch (RuntimeException e) {
            // No response: it committed if PD holds this config and the incarnation is LIVE
            if (committed(e, () -> {
                SchemaSyncClient.Record now = this.storedRecord(graphSpace, graph);
                return now != null && !now.dropped() && now.incarnation() == incarnation &&
                       config.equals(this.metaDriver.get(configKey));
            })) {
                return;
            }
            throw e;
        }
        if (!response.getSucceeded()) {
            throw rejected(graphSpace, graph, response);
        }
    }

    /**
     * Drops the incarnation of a LIVE record that a CREATE wrote ("src":"create") and that has
     * no graph config, as a create that died between its CREATE commit and its config write
     * leaves it, and deletes the schema it left. Clears no store: no Server loaded such a graph,
     * and the next create clears the stores before it initializes them. A LIVE record without
     * the mark is left alone: a graph opened from a local config has one, also while another
     * Server serves it.
     *
     * @return false if the record is absent, DROPPED, without the mark, or changed since it was
     * read, or the graph has a config by now
     */
    public boolean dropUnfinishedCreate(String graphSpace, String graph) {
        return this.dropUnfinishedCreate(graphSpace, graph, 0L);
    }

    /**
     * As {@link #dropUnfinishedCreate(String, String)}, for the record of the given
     * incarnation only, or of any when it is 0: a Server releases the name its own create
     * left after it failed
     */
    public boolean dropUnfinishedCreate(String graphSpace, String graph, long incarnation) {
        String recordKey = this.recordKey(graphSpace, graph);
        String stored = this.metaDriver.get(recordKey);
        if (stored == null || stored.isEmpty()) {
            return false;
        }
        SchemaSyncClient.Record record = this.readRecord(graphSpace, graph, stored);
        if (record.dropped() || !"create".equals(toMap(stored).get("src")) ||
            incarnation > 0L && record.incarnation() != incarnation) {
            return false;
        }
        TxnRequest.Builder txn = TxnRequest.newBuilder()
                .addCompares(compare(this.graphConfKey(graphSpace, graph),
                                     TxnCompare.Kind.ABSENT, ""))
                .addCompares(compare(recordKey, TxnCompare.Kind.EQUALS, stored))
                .addOps(TxnOp.newBuilder().setType(TxnOp.Type.DELETE_PREFIX)
                             .setKey(graphNameKey(graphSpace, graph)));
        TxnResponse response = this.tryCommit(graphSpace, graph, txn, TxnRecord.Op.DROP,
                                              record.incarnation());
        if (response.getSucceeded() ||
            response.getFailure() == TxnResponse.Failure.GRAPH_DROPPED) {
            return true;
        }
        if (response.getFailure() == TxnResponse.Failure.COMPARE_FAILED) {
            return false;
        }
        throw rejected(graphSpace, graph, response);
    }

    /**
     * Drops a graph incarnation in one commit: deletes the graph config and the schema, and
     * marks the record DROPPED, after which writes of the incarnation are rejected. PD refuses
     * it for another incarnation, so a stale instance drops nothing of a recreated graph.
     * Clearing the stores is up to the caller, after this commit.
     *
     * @return the response; GRAPH_DROPPED means this incarnation was dropped already, by an
     * earlier attempt of this drop whose store clear failed, and nothing recreated the graph
     */
    public TxnResponse dropGraph(String graphSpace, String graph, long incarnation) {
        TxnRequest.Builder txn = TxnRequest.newBuilder()
                .addOps(TxnOp.newBuilder().setType(TxnOp.Type.DELETE)
                             .setKey(this.graphConfKey(graphSpace, graph)))
                .addOps(TxnOp.newBuilder().setType(TxnOp.Type.DELETE_PREFIX)
                             .setKey(graphNameKey(graphSpace, graph)));
        return this.tryCommit(graphSpace, graph, txn, TxnRecord.Op.DROP, incarnation);
    }

    /**
     * The record a graph instance opens with: read after PD's ReadIndex barrier, or created
     * LIVE with incarnation 1 when absent (a graph from before records, or one loaded from a
     * local config). Every BUMP of the instance carries its incarnation, so it can't write
     * into a later incarnation; a DROPPED record keeps its incarnation, so its writes are
     * rejected. A record that can't be read fails the open: PD rejects every commit on it.
     */
    public TxnResponse openRecord(String graphSpace, String graph) {
        String record = this.metaDriver.get(this.recordKey(graphSpace, graph));
        if (record == null || record.isEmpty()) {
            return this.commit(graphSpace, graph, TxnRequest.newBuilder(), TxnRecord.Op.BUMP,
                               0L);
        }
        SchemaSyncClient.Record value = this.readRecord(graphSpace, graph, record);
        return TxnResponse.newBuilder().setSucceeded(true)
                          .setRevision(value.revision())
                          .setIncarnation(value.incarnation())
                          .setState(value.dropped() ? "DROPPED" : "LIVE").build();
    }

    /**
     * Reads a record as PD and the sync client read it, so an instance never opens with, and
     * a create never decides on, a record PD would reject, such as an incarnation of 0
     */
    private SchemaSyncClient.Record readRecord(String graphSpace, String graph, String record) {
        try {
            return SchemaSyncClient.Record.parse(record);
        } catch (RuntimeException e) {
            throw new HugeException("The schema sync record of graph '%s' in graph space " +
                                    "'%s' can't be read: %s", e, graph, graphSpace, record);
        }
    }

    private static TxnCompare compare(String key, TxnCompare.Kind kind, String value) {
        return TxnCompare.newBuilder().setKey(key).setKind(kind).setValue(value).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(String content) {
        return JsonUtil.fromJson(content, Map.class);
    }

    private void bump(String graphSpace, String graph, TxnRequest.Builder txn) {
        this.commit(graphSpace, graph, txn, TxnRecord.Op.BUMP, this.expectedIncarnation());
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
     */
    private void addPuts(TxnRequest.Builder txn, String graphSpace, String graph,
                         SchemaElement schema) {
        String content = serialize(schema);
        for (String key : this.schemaKeys(graphSpace, graph, schema)) {
            txn.addOps(TxnOp.newBuilder().setType(TxnOp.Type.PUT).setKey(key)
                            .setValue(content));
        }
    }

    private <T extends SchemaElement> T parse(String content,
                                              BiFunction<Map<String, Object>, HugeGraph, T>
                                                      fromMap) {
        return fromMap.apply(toMap(content), this.graph);
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
}
