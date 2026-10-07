/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.meta.managers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hugegraph.HugeException;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.HugeGraphParams;
import org.apache.hugegraph.backend.id.IdGenerator;
import org.apache.hugegraph.backend.tx.SchemaTransactionV2;
import org.apache.hugegraph.meta.MetaDriver;
import org.apache.hugegraph.meta.MetaManager;
import org.apache.hugegraph.meta.PdMetaDriver;
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
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.util.JsonUtil;
import org.apache.hugegraph.util.LockUtil;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.google.common.collect.ImmutableSet;

public class SchemaMetaManagerTest {

    private static final String SCHEMA = "HUGEGRAPH/hg/GRAPHSPACE/DEFAULT/g/SCHEMA/";
    private static final String RECORD = "HUGEGRAPH/hg/SCHEMA_SYNC/DEFAULT/g";

    private MetaDriver driver;
    private AtomicLong incarnation;
    private SchemaMetaManager manager;

    @Before
    public void setup() {
        this.driver = Mockito.mock(PdMetaDriver.class);
        Mockito.when(this.driver.commit(Mockito.any())).thenReturn(succeeded(1));
        this.incarnation = new AtomicLong();
        this.manager = new SchemaMetaManager(this.driver, "hg", null, this.incarnation);
    }

    @Test
    public void testSaveSchemaIsOneCommit() {
        // The first commit creates the record, the later ones carry its incarnation
        assertSaved(new PropertyKey(null, IdGenerator.of(1), "name"), "PROPERTY_KEY", 0L);
        assertSaved(new VertexLabel(null, IdGenerator.of(2), "person"), "VERTEX_LABEL", 1L);
        assertSaved(new EdgeLabel(null, IdGenerator.of(3), "knows"), "EDGE_LABEL", 1L);
        assertSaved(new IndexLabel(null, IdGenerator.of(4), "byName"), "INDEX_LABEL", 1L);
        Mockito.verify(this.driver, Mockito.never()).put(Mockito.any(), Mockito.any());
    }

    @Test
    public void testRemoveSchemaIsOneCommit() {
        this.manager.removeSchema("DEFAULT", "g", new VertexLabel(null, IdGenerator.of(2),
                                                                  "person"));
        TxnRequest request = this.lastCommit();
        Assert.assertEquals(2, request.getOpsCount());
        assertOp(request.getOps(0), TxnOp.Type.DELETE, SCHEMA + "VERTEX_LABEL/ID/2");
        assertOp(request.getOps(1), TxnOp.Type.DELETE, SCHEMA + "VERTEX_LABEL/NAME/person");
        assertBump(request, 0L);
        Mockito.verify(this.driver, Mockito.never()).delete(Mockito.any());
    }

    @Test
    public void testClearAllSchemaIsOneCommit() {
        this.manager.clearAllSchema("DEFAULT", "g");
        TxnRequest request = this.lastCommit();
        Assert.assertEquals(1, request.getOpsCount());
        String prefix = SCHEMA.substring(0, SCHEMA.length() - 1);
        assertOp(request.getOps(0), TxnOp.Type.DELETE_PREFIX, prefix);
        assertBump(request, 0L);
        // The prefix delete must not take the record with it
        Assert.assertFalse(RECORD.startsWith(prefix));
        Mockito.verify(this.driver, Mockito.never()).deleteWithPrefix(Mockito.any());
    }

    @Test
    public void testOpenIncarnation() {
        // No record (a graph created before the upgrade): an empty BUMP creates it, only
        // while the record is still absent
        Assert.assertEquals(1L, this.manager.openIncarnation("DEFAULT", "g"));
        TxnRequest request = this.lastCommit();
        Assert.assertEquals(0, request.getOpsCount());
        Assert.assertEquals(1, request.getComparesCount());
        Assert.assertEquals(RECORD, request.getCompares(0).getKey());
        Assert.assertEquals(TxnCompare.Kind.ABSENT, request.getCompares(0).getKind());
        assertBump(request, 0L);

        Mockito.clearInvocations(this.driver);
        Mockito.when(this.driver.get(RECORD))
               .thenReturn("{\"rev\":9,\"inc\":3,\"state\":\"LIVE\"}");
        Assert.assertEquals(3L, this.manager.openIncarnation("DEFAULT", "g"));
        // A dropped graph keeps its incarnation, so a later recreate doesn't let it write
        Mockito.when(this.driver.get(RECORD))
               .thenReturn("{\"rev\":10,\"inc\":3,\"state\":\"DROPPED\"}");
        Assert.assertEquals(3L, this.manager.openIncarnation("DEFAULT", "g"));
        Mockito.verify(this.driver, Mockito.never()).commit(Mockito.any());
    }

    /**
     * An instance that opened a graph created before the upgrade must not write with the
     * unchecked incarnation 0, or it could still write and drop the graph after another
     * Server dropped and recreated it
     */
    @Test
    public void testInstanceOpenedOnALegacyGraphCantWriteARecreatedOne() {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        AtomicLong incA = new AtomicLong();
        SchemaMetaManager serverA = new SchemaMetaManager(pd.driver(), "hg", null, incA);
        AtomicLong incB = new AtomicLong();
        SchemaMetaManager serverB = new SchemaMetaManager(pd.driver(), "hg", null, incB);

        incA.set(serverA.openIncarnation("DEFAULT", "g"));
        Assert.assertEquals(1L, incA.get());
        incB.set(serverB.openIncarnation("DEFAULT", "g"));
        Assert.assertEquals(1L, incB.get());

        // Server B drops the graph and creates it again
        serverB.dropGraph("DEFAULT", "g", incB.get());
        Assert.assertTrue(serverB.createGraph("DEFAULT", "g").getSucceeded());
        AtomicLong incNew = new AtomicLong(serverB.openIncarnation("DEFAULT", "g"));
        Assert.assertEquals(2L, incNew.get());
        new SchemaMetaManager(pd.driver(), "hg", null, incNew).saveSchema(
                "DEFAULT", "g", new PropertyKey(null, IdGenerator.of(1), "new"));

        // The instance of server A belongs to the dropped incarnation
        Assert.assertThrows(HugeException.class, () -> {
            serverA.saveSchema("DEFAULT", "g", new PropertyKey(null, IdGenerator.of(1), "old"));
        }, e -> Assert.assertContains("INCARNATION_MISMATCH", e.getMessage()));
        Assert.assertThrows(HugeException.class, () -> {
            serverA.dropGraph("DEFAULT", "g", incA.get());
        }, e -> Assert.assertContains("INCARNATION_MISMATCH", e.getMessage()));
        Assert.assertContains("\"inc\":2,\"state\":\"LIVE\"", pd.get(RECORD));
        Assert.assertContains("\"name\":\"new\"", pd.get(SCHEMA + "PROPERTY_KEY/ID/1"));
    }

    /**
     * The read of an absent record is not ordered with other commits, so the record may be
     * written between the read and the BUMP that creates it. The open takes such a record only
     * as the one another Server created for the same legacy graph, never a recreated graph.
     */
    @Test
    public void testOpenTakesOnlyALegacyRecordWrittenMeanwhile() {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager serverA = new SchemaMetaManager(pd.driver(), "hg", null);
        SchemaMetaManager serverB = new SchemaMetaManager(pd.driver(), "hg", null);

        // Server B opens the legacy graph between the read and the BUMP of server A
        pd.beforeNextCommit(() -> {
            Assert.assertEquals(1L, serverB.openIncarnation("DEFAULT", "g"));
        });
        Assert.assertEquals(1L, serverA.openIncarnation("DEFAULT", "g"));
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", pd.get(RECORD));

        // The graph is dropped and recreated between the read and the BUMP of server A
        FakeSchemaSyncPd pd2 = new FakeSchemaSyncPd();
        SchemaMetaManager serverC = new SchemaMetaManager(pd2.driver(), "hg", null);
        SchemaMetaManager serverD = new SchemaMetaManager(pd2.driver(), "hg", null);
        pd2.beforeNextCommit(() -> {
            serverD.createGraph("DEFAULT", "g");
            serverD.dropGraph("DEFAULT", "g", 1L);
            Assert.assertEquals(2L, serverD.createGraph("DEFAULT", "g").getIncarnation());
        });
        Assert.assertThrows(HugeException.class, () -> {
            serverC.openIncarnation("DEFAULT", "g");
        }, e -> Assert.assertContains("dropped or recreated while it opened", e.getMessage()));
        Assert.assertContains("\"inc\":2,\"state\":\"LIVE\"", pd2.get(RECORD));
    }

    /**
     * The PD client sends a TXN again when its response is lost. A CREATE that PD applied must
     * then not fail with GRAPH_EXISTS on its own record.
     */
    @Test
    public void testCreateGraphSentAgainAfterALostResponse() {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager manager = new SchemaMetaManager(pd.driver(), "hg", null);
        pd.resendNextCommit();
        TxnResponse response = manager.createGraph("DEFAULT", "g");
        Assert.assertTrue(response.getSucceeded());
        Assert.assertEquals(1L, response.getIncarnation());
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", pd.get(RECORD));

        // A CREATE of another Server still sees GRAPH_EXISTS
        SchemaMetaManager other = new SchemaMetaManager(pd.driver(), "hg", null);
        response = other.createGraph("DEFAULT", "g");
        Assert.assertFalse(response.getSucceeded());
        Assert.assertEquals(TxnResponse.Failure.GRAPH_EXISTS, response.getFailure());
    }

    @Test
    public void testCreateGraphOfALiveGraph() {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager manager = new SchemaMetaManager(pd.driver(), "hg", null);
        Assert.assertEquals(1L, manager.createGraph("DEFAULT", "g").getIncarnation());

        // Returned, not thrown, so the caller can tell an orphaned record from a live graph
        TxnResponse response = manager.createGraph("DEFAULT", "g");
        Assert.assertFalse(response.getSucceeded());
        Assert.assertEquals(TxnResponse.Failure.GRAPH_EXISTS, response.getFailure());
        Assert.assertEquals(1L, response.getIncarnation());

        Mockito.when(this.driver.commit(Mockito.any())).thenReturn(
                TxnResponse.newBuilder().setFailure(TxnResponse.Failure.INVALID_RECORD)
                           .build());
        Assert.assertThrows(HugeException.class, () -> {
            this.manager.createGraph("DEFAULT", "g");
        }, e -> Assert.assertContains("INVALID_RECORD", e.getMessage()));
    }

    @Test
    public void testInstanceOpenedOnADroppedGraphCantWriteARecreatedOne() {
        Mockito.when(this.driver.get(RECORD))
               .thenReturn("{\"rev\":10,\"inc\":3,\"state\":\"DROPPED\"}");
        this.incarnation.set(this.manager.openIncarnation("DEFAULT", "g"));
        // PD answers as for a record recreated with incarnation 4
        Mockito.when(this.driver.commit(Mockito.any())).thenReturn(
                TxnResponse.newBuilder()
                           .setFailure(TxnResponse.Failure.INCARNATION_MISMATCH)
                           .setIncarnation(4).build());
        Assert.assertThrows(HugeException.class, () -> {
            this.manager.saveSchema("DEFAULT", "g",
                                    new PropertyKey(null, IdGenerator.of(1), "name"));
        }, e -> Assert.assertContains("INCARNATION_MISMATCH", e.getMessage()));
        assertBump(this.lastCommit(), 3L);
        Assert.assertEquals(3L, this.incarnation.get());
    }

    @Test
    public void testIncarnationSentWithEveryBump() {
        this.incarnation.set(3L);
        Mockito.when(this.driver.commit(Mockito.any())).thenReturn(succeeded(3));
        PropertyKey pk = new PropertyKey(null, IdGenerator.of(1), "name");
        this.manager.saveSchema("DEFAULT", "g", pk);
        assertBump(this.lastCommit(), 3L);
        this.manager.removeSchema("DEFAULT", "g", pk);
        assertBump(this.lastCommit(), 3L);
        // Learned at open, never read on a write
        Mockito.verify(this.driver, Mockito.never()).get(Mockito.any());
    }

    @Test
    public void testIncarnationOfACreatedRecord() {
        // No record yet: the first BUMP is not checked and creates incarnation 1
        PropertyKey pk = new PropertyKey(null, IdGenerator.of(1), "name");
        this.manager.saveSchema("DEFAULT", "g", pk);
        assertBump(this.lastCommit(), 0L);
        Assert.assertEquals(1L, this.incarnation.get());
        this.manager.saveSchema("DEFAULT", "g", pk);
        assertBump(this.lastCommit(), 1L);
    }

    @Test
    public void testCreateAndDropGraph() {
        this.manager.createGraph("DEFAULT", "g");
        TxnRequest request = this.lastCommit();
        // The owner token of the CREATE
        Assert.assertEquals(1, request.getOpsCount());
        assertOp(request.getOps(0), TxnOp.Type.PUT, "HUGEGRAPH/hg/SCHEMA_SYNC_OWNER/DEFAULT/g");
        Assert.assertEquals(TxnRecord.Op.CREATE, request.getRecord().getOp());
        Assert.assertEquals(RECORD, request.getRecord().getKey());

        this.manager.dropGraph("DEFAULT", "g", 2L);
        request = this.lastCommit();
        assertOp(request.getOps(0), TxnOp.Type.DELETE_PREFIX,
                 SCHEMA.substring(0, SCHEMA.length() - 1));
        Assert.assertEquals(TxnRecord.Op.DROP, request.getRecord().getOp());
        Assert.assertEquals(2L, request.getRecord().getExpectedIncarnation());
    }

    @Test
    public void testRejectedCommitThrows() {
        Mockito.when(this.driver.commit(Mockito.any())).thenReturn(
                TxnResponse.newBuilder().setFailure(TxnResponse.Failure.GRAPH_DROPPED)
                           .build());
        Assert.assertThrows(HugeException.class, () -> {
            this.manager.saveSchema("DEFAULT", "g",
                                    new PropertyKey(null, IdGenerator.of(1), "name"));
        }, e -> {
            Assert.assertContains("GRAPH_DROPPED", e.getMessage());
            Assert.assertContains("dropped or recreated", e.getMessage());
        });
    }

    @Test
    public void testAddIndexLabelIsOneCommit() {
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        Mockito.when(graph.graphSpace()).thenReturn("DEFAULT");
        Mockito.when(graph.spaceGraphName()).thenReturn("DEFAULT-g");
        HugeGraphParams params = Mockito.mock(HugeGraphParams.class);
        Mockito.when(params.graph()).thenReturn(graph);
        Mockito.when(params.name()).thenReturn("g");
        Mockito.when(params.schemaIncarnation()).thenReturn(this.incarnation);
        GraphMetaManager graphMeta = Mockito.mock(GraphMetaManager.class);
        Object oldGraphMeta = Whitebox.getInternalState(MetaManager.instance(),
                                                        "graphMetaManager");
        Whitebox.setInternalState(MetaManager.instance(), "graphMetaManager", graphMeta);
        LockUtil.init("DEFAULT-g");
        try {
            SchemaTransactionV2 tx = new SchemaTransactionV2(this.driver, "hg", params);
            VertexLabel person = new VertexLabel(null, IdGenerator.of(2), "person");
            String stored = JsonUtil.toJson(person.asMap());
            Mockito.when(this.driver.get(SCHEMA + "VERTEX_LABEL/ID/2")).thenReturn(stored);
            IndexLabel byName = new IndexLabel(null, IdGenerator.of(4), "byName");
            byName.baseType(HugeType.VERTEX_LABEL);
            byName.baseValue(person.id());
            tx.addIndexLabel(person, byName);

            TxnRequest request = this.lastCommit();
            // Only while the base label is stored as it was read
            Assert.assertEquals(1, request.getComparesCount());
            Assert.assertEquals(SCHEMA + "VERTEX_LABEL/ID/2", request.getCompares(0).getKey());
            Assert.assertEquals(TxnCompare.Kind.EQUALS, request.getCompares(0).getKind());
            Assert.assertEquals(stored, request.getCompares(0).getValue());
            Assert.assertEquals(4, request.getOpsCount());
            Assert.assertEquals(SCHEMA + "INDEX_LABEL/ID/4", request.getOps(0).getKey());
            Assert.assertEquals(SCHEMA + "INDEX_LABEL/NAME/byName", request.getOps(1).getKey());
            Assert.assertEquals(SCHEMA + "VERTEX_LABEL/ID/2", request.getOps(2).getKey());
            Assert.assertEquals(SCHEMA + "VERTEX_LABEL/NAME/person",
                                request.getOps(3).getKey());
            Assert.assertContains("\"indexLabels\":[4]", request.getOps(3).getValue());
            assertBump(request, 0L);
            Mockito.verify(graphMeta).notifyGraphVertexCacheClear("DEFAULT", "g");
            Assert.assertEquals(ImmutableSet.of(byName.id()), person.indexLabels());
        } finally {
            LockUtil.destroy("DEFAULT-g");
            Whitebox.setInternalState(MetaManager.instance(), "graphMetaManager",
                                      oldGraphMeta);
        }
    }

    /**
     * Two Servers add an index label to the same vertex label at once. JVM locks don't order
     * them, so the second commit must not drop the index label of the first.
     */
    @Test
    public void testAddIndexLabelKeepsAConcurrentIndexLabel() {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        AtomicLong incA = new AtomicLong();
        SchemaMetaManager serverA = new SchemaMetaManager(pd.driver(), "hg", null, incA);
        AtomicLong incB = new AtomicLong();
        SchemaMetaManager serverB = new SchemaMetaManager(pd.driver(), "hg", null, incB);
        serverA.createGraph("DEFAULT", "g");
        incA.set(serverA.openIncarnation("DEFAULT", "g"));
        incB.set(serverB.openIncarnation("DEFAULT", "g"));
        serverA.saveSchema("DEFAULT", "g", new VertexLabel(null, IdGenerator.of(2), "person"));

        // Each Server holds the label as it was before either index label
        VertexLabel personOfA = new VertexLabel(null, IdGenerator.of(2), "person");
        VertexLabel personOfB = new VertexLabel(null, IdGenerator.of(2), "person");
        IndexLabel byName = indexLabel(4, "byName", personOfA);
        IndexLabel byAge = indexLabel(5, "byAge", personOfB);
        // Server B commits after server A read the label and before server A commits
        pd.beforeNextCommit(() -> serverB.addIndexLabel("DEFAULT", "g", personOfB, byAge));
        SchemaLabel written = serverA.addIndexLabel("DEFAULT", "g", personOfA, byName);

        Assert.assertEquals(ImmutableSet.of(byName.id(), byAge.id()), written.indexLabels());
        for (String key : new String[]{SCHEMA + "VERTEX_LABEL/ID/2",
                                       SCHEMA + "VERTEX_LABEL/NAME/person"}) {
            VertexLabel stored = VertexLabel.fromMap(JsonUtil.fromJson(pd.get(key), Map.class),
                                                     null);
            Assert.assertEquals(ImmutableSet.of(byName.id(), byAge.id()),
                                stored.indexLabels());
        }
        Assert.assertContains("\"name\":\"byName\"", pd.get(SCHEMA + "INDEX_LABEL/ID/4"));
        Assert.assertContains("\"name\":\"byAge\"", pd.get(SCHEMA + "INDEX_LABEL/ID/5"));
    }

    @Test
    public void testAddIndexLabelGivesUpWhenTheLabelKeepsChanging() {
        VertexLabel person = new VertexLabel(null, IdGenerator.of(2), "person");
        Mockito.when(this.driver.get(SCHEMA + "VERTEX_LABEL/ID/2"))
               .thenReturn(JsonUtil.toJson(person.asMap()));
        Mockito.when(this.driver.commit(Mockito.any())).thenReturn(
                TxnResponse.newBuilder().setFailure(TxnResponse.Failure.COMPARE_FAILED)
                           .build());
        IndexLabel byName = indexLabel(4, "byName", person);
        Assert.assertThrows(HugeException.class, () -> {
            this.manager.addIndexLabel("DEFAULT", "g", person, byName);
        }, e -> Assert.assertContains("kept changing on other servers in 3 attempts",
                                      e.getMessage()));
        Mockito.verify(this.driver, Mockito.times(3)).commit(Mockito.any());
        Mockito.verify(this.driver, Mockito.times(3)).get(SCHEMA + "VERTEX_LABEL/ID/2");

        // Any other rejection is not retried
        Mockito.clearInvocations(this.driver);
        Mockito.when(this.driver.commit(Mockito.any())).thenReturn(
                TxnResponse.newBuilder().setFailure(TxnResponse.Failure.GRAPH_DROPPED)
                           .build());
        Assert.assertThrows(HugeException.class, () -> {
            this.manager.addIndexLabel("DEFAULT", "g", person, byName);
        }, e -> Assert.assertContains("GRAPH_DROPPED", e.getMessage()));
        Mockito.verify(this.driver, Mockito.times(1)).commit(Mockito.any());

        // A base label removed meanwhile
        Mockito.when(this.driver.get(SCHEMA + "VERTEX_LABEL/ID/2")).thenReturn("");
        Assert.assertThrows(HugeException.class, () -> {
            this.manager.addIndexLabel("DEFAULT", "g", person, byName);
        }, e -> Assert.assertContains("doesn't exist", e.getMessage()));
    }

    /**
     * Two Servers change the index labels of the same vertex label at once. The removal of an
     * index label must not put back one that another Server removed, nor drop one that another
     * Server added.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testRemoveIndexLabelKeepsConcurrentChanges() {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager serverB = new SchemaMetaManager(pd.driver(), "hg", null);
        serverB.createGraph("DEFAULT", "g");
        VertexLabel person = new VertexLabel(null, IdGenerator.of(2), "person");
        person.addIndexLabel(IdGenerator.of(4));
        person.addIndexLabel(IdGenerator.of(5));
        serverB.saveSchema("DEFAULT", "g", person);
        IndexLabel byName = indexLabel(4, "byName", person);

        HugeGraph graph = Mockito.mock(HugeGraph.class);
        Mockito.when(graph.graphSpace()).thenReturn("DEFAULT");
        Mockito.when(graph.spaceGraphName()).thenReturn("DEFAULT-g");
        HugeGraphParams params = Mockito.mock(HugeGraphParams.class);
        Mockito.when(params.graph()).thenReturn(graph);
        Mockito.when(params.name()).thenReturn("g");
        Mockito.when(params.schemaIncarnation()).thenReturn(new AtomicLong(1L));
        GraphMetaManager graphMeta = Mockito.mock(GraphMetaManager.class);
        Object oldGraphMeta = Whitebox.getInternalState(MetaManager.instance(),
                                                        "graphMetaManager");
        Whitebox.setInternalState(MetaManager.instance(), "graphMetaManager", graphMeta);
        LockUtil.init("DEFAULT-g");
        try {
            SchemaTransactionV2 tx = new SchemaTransactionV2(pd.driver(), "hg", params);
            String idKey = SCHEMA + "VERTEX_LABEL/ID/2";

            // Server B removes index label 5 after this Server read the vertex label
            VertexLabel without5 = new VertexLabel(null, IdGenerator.of(2), "person");
            without5.addIndexLabel(IdGenerator.of(4));
            pd.beforeNextCommit(() -> serverB.saveSchema("DEFAULT", "g", without5));
            tx.removeIndexLabelFromBaseLabel(byName);
            VertexLabel stored = VertexLabel.fromMap(JsonUtil.fromJson(pd.get(idKey),
                                                                       Map.class), null);
            Assert.assertEquals(ImmutableSet.of(), stored.indexLabels());

            // Server B adds index label 6 after this Server read the vertex label
            VertexLabel with6 = new VertexLabel(null, IdGenerator.of(2), "person");
            with6.addIndexLabel(IdGenerator.of(4));
            with6.addIndexLabel(IdGenerator.of(6));
            serverB.saveSchema("DEFAULT", "g", without5);
            pd.beforeNextCommit(() -> serverB.saveSchema("DEFAULT", "g", with6));
            tx.removeIndexLabelFromBaseLabel(byName);
            for (String key : new String[]{idKey, SCHEMA + "VERTEX_LABEL/NAME/person"}) {
                stored = VertexLabel.fromMap(JsonUtil.fromJson(pd.get(key), Map.class), null);
                Assert.assertEquals(ImmutableSet.of(IdGenerator.of(6)), stored.indexLabels());
            }
        } finally {
            LockUtil.destroy("DEFAULT-g");
            Whitebox.setInternalState(MetaManager.instance(), "graphMetaManager",
                                      oldGraphMeta);
        }
    }

    private static IndexLabel indexLabel(long id, String name, VertexLabel base) {
        IndexLabel indexLabel = new IndexLabel(null, IdGenerator.of(id), name);
        indexLabel.baseType(HugeType.VERTEX_LABEL);
        indexLabel.baseValue(base.id());
        return indexLabel;
    }

    private void assertSaved(SchemaElement schema, String type, long incarnation) {
        Mockito.clearInvocations(this.driver);
        this.manager.saveSchema("DEFAULT", "g", schema);
        TxnRequest request = this.lastCommit();
        Assert.assertEquals(2, request.getOpsCount());
        assertOp(request.getOps(0), TxnOp.Type.PUT,
                 SCHEMA + type + "/ID/" + schema.id().asString());
        assertOp(request.getOps(1), TxnOp.Type.PUT, SCHEMA + type + "/NAME/" + schema.name());
        Assert.assertEquals(request.getOps(0).getValue(), request.getOps(1).getValue());
        Assert.assertContains("\"name\":\"" + schema.name() + "\"",
                              request.getOps(0).getValue());
        assertBump(request, incarnation);
    }

    private TxnRequest lastCommit() {
        ArgumentCaptor<TxnRequest> captor = ArgumentCaptor.forClass(TxnRequest.class);
        Mockito.verify(this.driver, Mockito.atLeastOnce()).commit(captor.capture());
        List<TxnRequest> requests = captor.getAllValues();
        return requests.get(requests.size() - 1);
    }

    private static void assertOp(TxnOp op, TxnOp.Type type, String key) {
        Assert.assertEquals(type, op.getType());
        Assert.assertEquals(key, op.getKey());
    }

    private static void assertBump(TxnRequest request, long expectedIncarnation) {
        Assert.assertEquals(RECORD, request.getRecord().getKey());
        Assert.assertEquals(TxnRecord.Op.BUMP, request.getRecord().getOp());
        Assert.assertEquals(expectedIncarnation,
                            request.getRecord().getExpectedIncarnation());
    }

    private static TxnResponse succeeded(long incarnation) {
        return TxnResponse.newBuilder().setSucceeded(true).setRevision(7)
                          .setIncarnation(incarnation).build();
    }
}
