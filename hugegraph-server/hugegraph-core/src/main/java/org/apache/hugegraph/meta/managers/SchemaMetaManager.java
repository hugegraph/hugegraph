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
import static org.apache.hugegraph.meta.MetaManager.META_PATH_SCHEMA_SYNC_OWNER;
import static org.apache.hugegraph.meta.MetaManager.META_PATH_VERTEX_LABEL;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
     * Commits tried by an index label change while other Servers keep changing its base label
     */
    private static final int COMMIT_ATTEMPTS = 3;

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
     * The JVM locks of SchemaTransactionV2 don't serialize two Servers changing the index
     * labels of the same base label: both would read it, change it, and the later write would
     * undo the earlier change. So the base label is read from PD, and the commit only holds
     * while its ID key still has the value read; otherwise it is read again and retried.
     * This covers only this read-modify-write of the base label, here and in
     * removeIndexLabel. Whether other concurrent DDL across Servers needs conditional commits
     * as well is an open question for the maintainers (design section 7, concurrency and
     * idempotency).
     */
    public SchemaLabel addIndexLabel(String graphSpace, String graph, SchemaLabel baseLabel,
                                     IndexLabel indexLabel) {
        SchemaLabel label = this.updateLabel(graphSpace, graph, baseLabel, indexLabel.name(),
                                             stored -> stored.addIndexLabel(indexLabel.id()),
                                             txn -> this.addPuts(txn, graphSpace, graph,
                                                                 indexLabel));
        if (label == null) {
            throw new HugeException("The %s '%s' of index label '%s' doesn't exist",
                                    baseLabel.type().readableName(), baseLabel.name(),
                                    indexLabel.name());
        }
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
            SchemaLabel label = this.parseLabel(baseLabel.type(), stored);
            change.accept(label);

            TxnRequest.Builder txn = TxnRequest.newBuilder().addCompares(
                    compare(idKey, TxnCompare.Kind.EQUALS, stored));
            otherOps.accept(txn);
            this.addPuts(txn, graphSpace, graph, label);
            TxnResponse response = this.tryBump(graphSpace, graph, txn);
            if (response.getSucceeded()) {
                return label;
            }
            if (response.getFailure() != TxnResponse.Failure.COMPARE_FAILED) {
                throw rejected(graphSpace, graph, response);
            }
            LOG.info("The {} '{}' changed while its index label '{}' was changed, retry",
                     baseLabel.type().readableName(), baseLabel.name(), indexLabel);
        }
        throw new HugeException("Failed to change index label '%s' of %s '%s': it kept " +
                                "changing on other servers in %s attempts", indexLabel,
                                baseLabel.type().readableName(), baseLabel.name(),
                                COMMIT_ATTEMPTS);
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
     * <p>
     * The PD client sends a TXN again when the connection fails, so a CREATE that PD applied
     * may come back as GRAPH_EXISTS. The CREATE writes an owner token of its own with the
     * record, and a GRAPH_EXISTS whose LIVE record was written with that token is the CREATE's
     * own success.
     */
    public TxnResponse createGraph(String graphSpace, String graph) {
        String owner = UUID.randomUUID().toString();
        TxnResponse response = this.tryCommit(graphSpace, graph, TxnRequest.newBuilder()
                                                      .addOps(this.ownerPut(graphSpace, graph,
                                                                            owner)),
                                              TxnRecord.Op.CREATE, 0L);
        if (!response.getSucceeded() &&
            response.getFailure() == TxnResponse.Failure.GRAPH_EXISTS &&
            this.isOwner(graphSpace, graph, owner, response.getIncarnation())) {
            return response.toBuilder().setSucceeded(true)
                           .setFailure(TxnResponse.Failure.NONE).build();
        }
        if (!response.getSucceeded() &&
            response.getFailure() != TxnResponse.Failure.GRAPH_EXISTS) {
            throw rejected(graphSpace, graph, response);
        }
        return response;
    }

    /**
     * Publishes the config of a created graph in one commit with a BUMP of its incarnation,
     * so the config appears only while the record is LIVE with that incarnation: a drop of the
     * name that released the reservation meanwhile fails the publish instead of leaving a
     * config behind for a DROPPED record. The config is serialized as GraphMetaManager does.
     */
    public void publishGraphConfig(String graphSpace, String graph, long incarnation,
                                   Map<String, Object> configs) {
        TxnRequest.Builder txn = TxnRequest.newBuilder().addOps(
                TxnOp.newBuilder().setType(TxnOp.Type.PUT)
                     .setKey(this.graphConfKey(graphSpace, graph))
                     .setValue(JsonUtil.toJson(configs)));
        this.commit(graphSpace, graph, txn, TxnRecord.Op.BUMP, incarnation);
    }

    /**
     * Starts the drop of a graph instance: removes the graph config in one commit with a BUMP
     * of the instance's incarnation, and only while the config is the one read just before.
     * The config and the stores of a graph are shared by all of its incarnations, so this
     * comes before the stores are cleared, and only one drop gets past it: a drop of a stale
     * instance (the graph was dropped or recreated meanwhile) fails, and so does a second drop
     * running at the same time, which finds the config gone. The record stays LIVE until the
     * DROP commit, so no CREATE gets in while the stores are cleared.
     * <p>
     * The commit writes an owner token, which is returned. A drop that failed after this step
     * passes its token to go on, since the config is gone by then. A drop that stopped after
     * this step otherwise leaves a LIVE record without config, which a drop of the graph by
     * name on a Server without an instance of it releases.
     *
     * @param started the owner token of a drop of this instance that failed after this step,
     *                or null
     */
    public String removeGraphConfig(String graphSpace, String graph, long incarnation,
                                    String started) {
        String confKey = this.graphConfKey(graphSpace, graph);
        String config = this.metaDriver.get(confKey);
        if (config == null || config.isEmpty()) {
            if (started != null && this.isOwner(graphSpace, graph, started, incarnation)) {
                return started;
            }
            throw new HugeException("Graph '%s' in graph space '%s' has no config: it is " +
                                    "being created or dropped, or a drop of it didn't " +
                                    "finish", graph, graphSpace);
        }
        String owner = UUID.randomUUID().toString();
        TxnRequest.Builder txn = TxnRequest.newBuilder()
                .addCompares(compare(confKey, TxnCompare.Kind.EQUALS, config))
                .addOps(TxnOp.newBuilder().setType(TxnOp.Type.DELETE).setKey(confKey))
                .addOps(this.ownerPut(graphSpace, graph, owner));
        TxnResponse response = this.tryCommit(graphSpace, graph, txn, TxnRecord.Op.BUMP,
                                              incarnation);
        if (response.getSucceeded()) {
            return owner;
        }
        if (response.getFailure() == TxnResponse.Failure.COMPARE_FAILED) {
            // A commit applied before its response was lost, see createGraph
            if (this.isOwner(graphSpace, graph, owner, incarnation)) {
                return owner;
            }
            throw new HugeException("The config of graph '%s' in graph space '%s' changed " +
                                    "while it was dropped: another drop or create of it is " +
                                    "running", graph, graphSpace);
        }
        throw rejected(graphSpace, graph, response);
    }

    /**
     * Clears the schema of the graph and marks it DROPPED in one commit, after which writes
     * of this incarnation are rejected. A dropping instance passes its own incarnation, so it
     * can't drop a newer one.
     */
    public void dropGraph(String graphSpace, String graph, long expectedIncarnation) {
        TxnRequest.Builder txn = TxnRequest.newBuilder().addOps(
                TxnOp.newBuilder().setType(TxnOp.Type.DELETE_PREFIX)
                     .setKey(graphNameKey(graphSpace, graph)));
        this.commit(graphSpace, graph, txn, TxnRecord.Op.DROP, expectedIncarnation);
    }

    /**
     * Drops a LIVE record of the given incarnation whose graph has no config, as dropGraph
     * does, in one commit that checks the config is absent: a create that publishes its
     * config first keeps its graph, and one that publishes after this fails. Returns false if
     * the graph has a config.
     */
    public boolean dropGraphWithoutConfig(String graphSpace, String graph, long incarnation) {
        TxnRequest.Builder txn = TxnRequest.newBuilder()
                .addCompares(compare(this.graphConfKey(graphSpace, graph),
                                     TxnCompare.Kind.ABSENT, ""))
                .addOps(TxnOp.newBuilder().setType(TxnOp.Type.DELETE_PREFIX)
                             .setKey(graphNameKey(graphSpace, graph)));
        TxnResponse response = this.tryCommit(graphSpace, graph, txn, TxnRecord.Op.DROP,
                                              incarnation);
        if (response.getSucceeded()) {
            return true;
        }
        if (response.getFailure() == TxnResponse.Failure.COMPARE_FAILED) {
            return false;
        }
        throw rejected(graphSpace, graph, response);
    }

    /**
     * The incarnation a graph instance opens with, read from its record: every BUMP and DROP
     * of the instance then carries it, so the instance can't write into a later incarnation.
     * An absent record (a graph created before the upgrade) is created here by an empty BUMP,
     * so the instance never writes with the unchecked incarnation 0. That BUMP holds only
     * while the record is still absent, since the read is not ordered with other commits: a
     * record that appeared meanwhile is taken only as the record another Server created for
     * the same graph (LIVE with incarnation 1, checked by a BUMP), and otherwise the graph was
     * dropped or recreated while it opened and the open fails. The incarnation of a DROPPED
     * record is kept as well, so the writes of the instance are rejected as GRAPH_DROPPED
     * while the graph stays dropped, and as INCARNATION_MISMATCH once it is recreated.
     */
    public long openIncarnation(String graphSpace, String graph) {
        Map<String, Object> value = this.readRecord(graphSpace, graph);
        if (value != null) {
            if (!"LIVE".equals(value.get("state"))) {
                LOG.warn("Graph '{}' in graph space '{}' opens with a {} schema sync record {}",
                         graph, graphSpace, value.get("state"), value);
            }
            return ((Number) value.get("inc")).longValue();
        }
        TxnRequest.Builder txn = TxnRequest.newBuilder().addCompares(
                compare(this.recordKey(graphSpace, graph), TxnCompare.Kind.ABSENT, ""));
        TxnResponse response = this.tryCommit(graphSpace, graph, txn, TxnRecord.Op.BUMP, 0L);
        if (response.getSucceeded()) {
            return response.getIncarnation();
        }
        if (response.getFailure() != TxnResponse.Failure.COMPARE_FAILED) {
            throw rejected(graphSpace, graph, response);
        }
        response = this.tryCommit(graphSpace, graph, TxnRequest.newBuilder(),
                                  TxnRecord.Op.BUMP, 1L);
        if (!response.getSucceeded()) {
            throw new HugeException("Graph '%s' in graph space '%s' was dropped or recreated " +
                                    "while it opened (%s)", graph, graphSpace,
                                    response.getFailure());
        }
        return 1L;
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
     * Whether the last CREATE or drop start of the graph was the one with this owner token and
     * the record is still LIVE with the incarnation. A commit, so it reads what PD applied.
     */
    private boolean isOwner(String graphSpace, String graph, String owner, long incarnation) {
        if (incarnation == 0L) {
            return false;
        }
        TxnRequest.Builder txn = TxnRequest.newBuilder().addCompares(
                compare(this.ownerKey(graphSpace, graph), TxnCompare.Kind.EQUALS, owner));
        return this.tryCommit(graphSpace, graph, txn, TxnRecord.Op.BUMP, incarnation)
                   .getSucceeded();
    }

    private static TxnCompare compare(String key, TxnCompare.Kind kind, String value) {
        return TxnCompare.newBuilder().setKey(key).setKind(kind).setValue(value).build();
    }

    private TxnOp ownerPut(String graphSpace, String graph, String owner) {
        return TxnOp.newBuilder().setType(TxnOp.Type.PUT)
                    .setKey(this.ownerKey(graphSpace, graph)).setValue(owner).build();
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

    /**
     * HUGEGRAPH/{cluster}/SCHEMA_SYNC_OWNER/{graphspace}/{graph}, the owner token of the last
     * CREATE or drop start of the graph; outside the SCHEMA_SYNC directory that the schema
     * sync watches
     */
    private String ownerKey(String graphSpace, String graph) {
        return String.join(META_PATH_DELIMITER,
                           META_PATH_HUGEGRAPH,
                           this.cluster,
                           META_PATH_SCHEMA_SYNC_OWNER,
                           graphSpace,
                           graph);
    }

    private String graphConfKey(String graphSpace, String graph) {
        return GraphMetaManager.graphConfKey(this.cluster, graphSpace, graph);
    }
}
