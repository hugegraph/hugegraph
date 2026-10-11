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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hugegraph.exception.HugeException;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.HugeGraphParams;
import org.apache.hugegraph.id.IdGenerator;
import org.apache.hugegraph.backend.tx.SchemaTransactionV2;
import org.apache.hugegraph.exception.ExistedException;
import org.apache.hugegraph.meta.MetaDriver;
import org.apache.hugegraph.meta.MetaManager;
import org.apache.hugegraph.meta.PdMetaDriver;
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
import org.apache.hugegraph.struct.schema.Userdata;
import org.apache.hugegraph.struct.schema.VertexLabel;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.type.define.SchemaStatus;
import org.apache.hugegraph.util.DateUtil;
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
    private static final String CONFIG = "HUGEGRAPH/hg/GRAPHSPACE/DEFAULT/GRAPH_CONF/g";
    private static final String DROPPING = "HUGEGRAPH/hg/GRAPH_DROPPING/DEFAULT/g";

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
    public void testAddSchemaIsOneCommit() {
        this.incarnation.set(1L);
        assertAdded(new PropertyKey(null, IdGenerator.of(1), "name"), "PROPERTY_KEY", 1L);
        assertAdded(new VertexLabel(null, IdGenerator.of(2), "person"), "VERTEX_LABEL", 1L);
        assertAdded(new EdgeLabel(null, IdGenerator.of(3), "knows"), "EDGE_LABEL", 1L);
        assertAdded(new IndexLabel(null, IdGenerator.of(4), "byName"), "INDEX_LABEL", 1L);
        Mockito.verify(this.driver, Mockito.never()).put(Mockito.any(), Mockito.any());
    }

    @Test
    public void testUpdateSchemaIsOneCommit() {
        // Read from PD, so it is written only while PD holds what was read
        Mockito.when(this.driver.get(SCHEMA + "VERTEX_LABEL/ID/2"))
               .thenReturn(JsonUtil.toJson(new VertexLabel(null, IdGenerator.of(2),
                                                           "person").asMap()));
        VertexLabel person = this.manager.getVertexLabel("DEFAULT", "g", IdGenerator.of(2));
        String read = person.stored();
        person.property(IdGenerator.of(9));
        Assert.assertSame(person, this.manager.updateSchema("DEFAULT", "g", person));

        TxnRequest request = this.lastCommit();
        Assert.assertEquals(1, request.getComparesCount());
        Assert.assertEquals(SCHEMA + "VERTEX_LABEL/ID/2", request.getCompares(0).getKey());
        Assert.assertEquals(TxnCompare.Kind.EQUALS, request.getCompares(0).getKind());
        Assert.assertEquals(read, request.getCompares(0).getValue());
        Assert.assertEquals(2, request.getOpsCount());
        assertOp(request.getOps(0), TxnOp.Type.PUT, SCHEMA + "VERTEX_LABEL/ID/2");
        assertOp(request.getOps(1), TxnOp.Type.PUT, SCHEMA + "VERTEX_LABEL/NAME/person");
        Assert.assertContains("\"properties\":[9]", request.getOps(0).getValue());
        assertBump(request, 0L);
        // The next update of the instance expects what this one wrote
        Assert.assertEquals(request.getOps(0).getValue(), person.stored());
    }

    @Test
    public void testRemoveSchemaIsOneCommit() {
        VertexLabel person = new VertexLabel(null, IdGenerator.of(2), "person");
        String stored = JsonUtil.toJson(person.asMap());
        Mockito.when(this.driver.get(SCHEMA + "VERTEX_LABEL/ID/2")).thenReturn(stored);
        this.manager.removeSchema("DEFAULT", "g", person);
        TxnRequest request = this.lastCommit();
        // Only what was read is deleted
        Assert.assertEquals(1, request.getComparesCount());
        Assert.assertEquals(SCHEMA + "VERTEX_LABEL/ID/2", request.getCompares(0).getKey());
        Assert.assertEquals(TxnCompare.Kind.EQUALS, request.getCompares(0).getKind());
        Assert.assertEquals(stored, request.getCompares(0).getValue());
        Assert.assertEquals(2, request.getOpsCount());
        assertOp(request.getOps(0), TxnOp.Type.DELETE, SCHEMA + "VERTEX_LABEL/ID/2");
        assertOp(request.getOps(1), TxnOp.Type.DELETE, SCHEMA + "VERTEX_LABEL/NAME/person");
        assertBump(request, 0L);
        Mockito.verify(this.driver, Mockito.never()).delete(Mockito.any());

        // Removed already: nothing to commit
        Mockito.clearInvocations(this.driver);
        Mockito.when(this.driver.get(SCHEMA + "VERTEX_LABEL/ID/2")).thenReturn("");
        this.manager.removeSchema("DEFAULT", "g", person);
        Mockito.verify(this.driver, Mockito.never()).commit(Mockito.any());
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
    public void testAdoptRecord() {
        // One ADOPT commit, which PD answers with the record (created if it was absent)
        Mockito.when(this.driver.commit(Mockito.any())).thenReturn(adopted(9, 3, "LIVE"));
        Assert.assertEquals(3L, this.manager.adoptRecord("DEFAULT", "g").getIncarnation());
        TxnRequest request = this.lastCommit();
        Assert.assertEquals(0, request.getOpsCount());
        Assert.assertEquals(0, request.getComparesCount());
        Assert.assertEquals(TxnRecord.Op.ADOPT, request.getRecord().getOp());
        Assert.assertEquals(RECORD, request.getRecord().getKey());
        Mockito.verify(this.driver, Mockito.never()).get(Mockito.any());

        // A dropped graph keeps its incarnation, so a later recreate doesn't let it write
        Mockito.when(this.driver.commit(Mockito.any())).thenReturn(adopted(10, 3, "DROPPED"));
        Assert.assertEquals(3L, this.manager.adoptRecord("DEFAULT", "g").getIncarnation());

        // A graph another Server is creating doesn't open here
        Mockito.when(this.driver.commit(Mockito.any())).thenReturn(adopted(11, 4, "CREATING"));
        Assert.assertThrows(SchemaMetaManager.GraphCreatingException.class, () -> {
            this.manager.adoptRecord("DEFAULT", "g");
        }, e -> Assert.assertContains("is being created", e.getMessage()));
    }

    /**
     * A graph is CREATING from its CREATE until its creator commits it: only the creating
     * Server opens it meanwhile, every Server once it is LIVE
     */
    @Test
    public void testOnlyTheCreatorOpensACreatingGraph() {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager serverA = new SchemaMetaManager(pd.driver(), "hg", null);
        SchemaMetaManager serverB = new SchemaMetaManager(pd.driver(), "hg", null);
        Assert.assertEquals(1L, serverA.createGraph("DEFAULT", "g", configs()));
        Assert.assertContains("\"inc\":1,\"state\":\"CREATING\"", pd.get(RECORD));
        Assert.assertContains("\"store\":\"g\"", pd.get(CONFIG));

        TxnResponse opened = serverA.adoptRecord("DEFAULT", "g");
        Assert.assertEquals(1L, opened.getIncarnation());
        Assert.assertEquals("CREATING", opened.getState());
        Assert.assertThrows(SchemaMetaManager.GraphCreatingException.class, () -> {
            serverB.adoptRecord("DEFAULT", "g");
        });
        Assert.assertEquals(1L, serverB.creatingIncarnation("DEFAULT", "g"));

        serverA.commitCreate("DEFAULT", "g", 1L);
        Assert.assertEquals("LIVE", serverB.adoptRecord("DEFAULT", "g").getState());
        Assert.assertEquals(0L, serverB.creatingIncarnation("DEFAULT", "g"));
        // The creator is done: a later CREATING record of the name is not its own
        serverA.dropGraph("DEFAULT", "g", 1L, false);
        Assert.assertEquals(2L, serverB.createGraph("DEFAULT", "g", configs()));
        Assert.assertThrows(SchemaMetaManager.GraphCreatingException.class, () -> {
            serverA.adoptRecord("DEFAULT", "g");
        });
    }

    /**
     * An instance that opened a graph created before the upgrade gets incarnation 1 from the
     * ADOPT that creates its record, so it can't write or drop the graph after another Server
     * dropped and recreated it
     */
    @Test
    public void testInstanceOpenedOnALegacyGraphCantWriteARecreatedOne() {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        AtomicLong incA = new AtomicLong();
        SchemaMetaManager serverA = new SchemaMetaManager(pd.driver(), "hg", null, incA);
        AtomicLong incB = new AtomicLong();
        SchemaMetaManager serverB = new SchemaMetaManager(pd.driver(), "hg", null, incB);

        incA.set(serverA.adoptRecord("DEFAULT", "g").getIncarnation());
        Assert.assertEquals(1L, incA.get());
        incB.set(serverB.adoptRecord("DEFAULT", "g").getIncarnation());
        Assert.assertEquals(1L, incB.get());

        // Server B drops the graph and creates it again
        Assert.assertTrue(serverB.dropGraph("DEFAULT", "g", incB.get(), false)
                                 .getSucceeded());
        Assert.assertEquals(2L, serverB.createGraph("DEFAULT", "g", configs()));
        AtomicLong incNew = new AtomicLong(serverB.adoptRecord("DEFAULT", "g")
                                                  .getIncarnation());
        Assert.assertEquals(2L, incNew.get());
        new SchemaMetaManager(pd.driver(), "hg", null, incNew).addSchema(
                "DEFAULT", "g", new PropertyKey(null, IdGenerator.of(1), "new"));
        serverB.commitCreate("DEFAULT", "g", 2L);

        // The instance of server A belongs to the dropped incarnation
        Assert.assertThrows(HugeException.class, () -> {
            serverA.addSchema("DEFAULT", "g", new PropertyKey(null, IdGenerator.of(2), "old"));
        }, e -> Assert.assertContains("INCARNATION_MISMATCH", e.getMessage()));
        Assert.assertEquals("", pd.get(SCHEMA + "PROPERTY_KEY/ID/2"));
        Assert.assertEquals(TxnResponse.Failure.INCARNATION_MISMATCH,
                            serverA.dropGraph("DEFAULT", "g", incA.get(), true).getFailure());
        Assert.assertEquals("", pd.get(DROPPING));
        Assert.assertContains("\"inc\":2,\"state\":\"LIVE\"", pd.get(RECORD));
        Assert.assertContains("\"name\":\"new\"", pd.get(SCHEMA + "PROPERTY_KEY/ID/1"));
        Assert.assertContains("\"store\":\"g\"", pd.get(CONFIG));
    }

    /**
     * The PD client sends a TXN again when its response is lost, with the request id it had,
     * and PD answers with the first outcome: a CREATE that PD applied succeeds, not fails with
     * GRAPH_EXISTS on its own record, and a DROP is not refused as GRAPH_DROPPED
     */
    @Test
    public void testCommitsSentAgainAfterALostResponse() {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager manager = new SchemaMetaManager(pd.driver(), "hg", null);
        pd.resendNextCommit();
        Assert.assertEquals(1L, manager.createGraph("DEFAULT", "g", configs()));
        Assert.assertContains("\"inc\":1,\"state\":\"CREATING\"", pd.get(RECORD));

        // A CREATE of another Server still finds the graph
        SchemaMetaManager other = new SchemaMetaManager(pd.driver(), "hg", null);
        Assert.assertEquals(0L, other.createGraph("DEFAULT", "g", configs()));

        pd.resendNextCommit();
        manager.commitCreate("DEFAULT", "g", 1L);
        pd.resendNextCommit();
        Assert.assertTrue(manager.dropGraph("DEFAULT", "g", 1L, false).getSucceeded());
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", pd.get(RECORD));
    }

    /**
     * Each commit is one logical commit with a request id of its own
     */
    @Test
    public void testEveryCommitHasItsOwnRequestId() {
        this.manager.addSchema("DEFAULT", "g", new PropertyKey(null, IdGenerator.of(1), "a"));
        this.manager.addSchema("DEFAULT", "g", new PropertyKey(null, IdGenerator.of(1), "a"));
        ArgumentCaptor<TxnRequest> captor = ArgumentCaptor.forClass(TxnRequest.class);
        Mockito.verify(this.driver, Mockito.times(2)).commit(captor.capture());
        String first = captor.getAllValues().get(0).getRequestId();
        Assert.assertFalse(first.isEmpty());
        Assert.assertNotEquals(first, captor.getAllValues().get(1).getRequestId());
    }

    @Test
    public void testCreateGraphOfAnExistingGraph() {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager manager = new SchemaMetaManager(pd.driver(), "hg", null);
        Assert.assertEquals(1L, manager.createGraph("DEFAULT", "g", configs()));
        // CREATING, then LIVE
        Assert.assertEquals(0L, manager.createGraph("DEFAULT", "g", configs()));
        manager.commitCreate("DEFAULT", "g", 1L);
        Assert.assertEquals(0L, manager.createGraph("DEFAULT", "g", configs()));

        // A graph from before the upgrade has a config and no record: not taken either
        pd.put("HUGEGRAPH/hg/GRAPHSPACE/DEFAULT/GRAPH_CONF/legacy", "{}");
        Assert.assertEquals(0L, manager.createGraph("DEFAULT", "legacy", configs()));
        Assert.assertEquals("", pd.get("HUGEGRAPH/hg/SCHEMA_SYNC/DEFAULT/legacy"));
        Assert.assertEquals("{}", pd.get("HUGEGRAPH/hg/GRAPHSPACE/DEFAULT/GRAPH_CONF/legacy"));

        Mockito.when(this.driver.commit(Mockito.any())).thenReturn(
                TxnResponse.newBuilder().setFailure(TxnResponse.Failure.INVALID_RECORD)
                           .build());
        Assert.assertThrows(HugeException.class, () -> {
            this.manager.createGraph("DEFAULT", "g", configs());
        }, e -> Assert.assertContains("INVALID_RECORD", e.getMessage()));
    }

    /**
     * A schema write of an instance and a drop of its graph on another Server: PD orders the
     * two, so the write either lands first and goes with the drop, or comes after it and is
     * rejected; no schema is left for a DROPPED graph
     */
    @Test
    public void testDropAndAConcurrentSchemaWrite() {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager serverB = new SchemaMetaManager(pd.driver(), "hg", null);
        serverB.createGraph("DEFAULT", "g", configs());
        serverB.commitCreate("DEFAULT", "g", 1L);
        SchemaMetaManager instanceA = new SchemaMetaManager(pd.driver(), "hg", null,
                                                            new AtomicLong(1L));

        // The drop commits first
        pd.beforeNextCommit(() -> serverB.dropGraph("DEFAULT", "g", 1L, false));
        Assert.assertThrows(HugeException.class, () -> {
            instanceA.addSchema("DEFAULT", "g", new PropertyKey(null, IdGenerator.of(1), "a"));
        }, e -> Assert.assertContains("GRAPH_DROPPED", e.getMessage()));
        Assert.assertEquals("", pd.get(SCHEMA + "PROPERTY_KEY/ID/1"));
        Assert.assertEquals("", pd.get(CONFIG));

        // The write commits first
        Assert.assertEquals(2L, serverB.createGraph("DEFAULT", "g", configs()));
        serverB.commitCreate("DEFAULT", "g", 2L);
        SchemaMetaManager instanceA2 = new SchemaMetaManager(pd.driver(), "hg", null,
                                                             new AtomicLong(2L));
        instanceA2.addSchema("DEFAULT", "g", new PropertyKey(null, IdGenerator.of(1), "a"));
        Assert.assertTrue(serverB.dropGraph("DEFAULT", "g", 2L, false).getSucceeded());
        Assert.assertEquals("", pd.get(SCHEMA + "PROPERTY_KEY/ID/1"));
        Assert.assertEquals("", pd.get(SCHEMA + "PROPERTY_KEY/NAME/a"));
        Assert.assertContains("\"inc\":2,\"state\":\"DROPPED\"", pd.get(RECORD));
    }

    @Test
    public void testInstanceOpenedOnADroppedGraphCantWriteARecreatedOne() {
        Mockito.when(this.driver.commit(Mockito.any())).thenReturn(adopted(10, 3, "DROPPED"));
        this.incarnation.set(this.manager.adoptRecord("DEFAULT", "g").getIncarnation());
        // PD answers as for a record recreated with incarnation 4
        Mockito.when(this.driver.commit(Mockito.any())).thenReturn(
                TxnResponse.newBuilder()
                           .setFailure(TxnResponse.Failure.INCARNATION_MISMATCH)
                           .setIncarnation(4).build());
        Assert.assertThrows(HugeException.class, () -> {
            this.manager.addSchema("DEFAULT", "g",
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
        this.manager.addSchema("DEFAULT", "g", pk);
        assertBump(this.lastCommit(), 3L);
        this.manager.updateSchema("DEFAULT", "g", pk);
        assertBump(this.lastCommit(), 3L);
        Mockito.when(this.driver.get(SCHEMA + "PROPERTY_KEY/ID/1")).thenReturn(pk.stored());
        this.manager.removeSchema("DEFAULT", "g", pk);
        assertBump(this.lastCommit(), 3L);
        Mockito.verify(this.driver, Mockito.times(3)).commit(Mockito.any());
        // Learned at open, never read on a write
        Mockito.verify(this.driver, Mockito.never()).get(RECORD);
    }

    @Test
    public void testCreateAndDropGraph() {
        this.manager.createGraph("DEFAULT", "g", configs());
        TxnRequest request = this.lastCommit();
        // The config, only while it and a drop marker are absent, no schema, and a CREATE
        // of the record
        Assert.assertEquals(2, request.getComparesCount());
        Assert.assertEquals(CONFIG, request.getCompares(0).getKey());
        Assert.assertEquals(TxnCompare.Kind.ABSENT, request.getCompares(0).getKind());
        Assert.assertEquals(DROPPING, request.getCompares(1).getKey());
        Assert.assertEquals(TxnCompare.Kind.ABSENT, request.getCompares(1).getKind());
        Assert.assertEquals(2, request.getOpsCount());
        assertOp(request.getOps(0), TxnOp.Type.PUT, CONFIG);
        assertOp(request.getOps(1), TxnOp.Type.DELETE_PREFIX,
                 SCHEMA.substring(0, SCHEMA.length() - 1));
        Assert.assertContains("\"store\":\"g\"", request.getOps(0).getValue());
        Assert.assertEquals(TxnRecord.Op.CREATE, request.getRecord().getOp());
        Assert.assertEquals(RECORD, request.getRecord().getKey());

        this.manager.commitCreate("DEFAULT", "g", 1L);
        request = this.lastCommit();
        Assert.assertEquals(0, request.getOpsCount());
        Assert.assertEquals(TxnRecord.Op.COMMIT_CREATE, request.getRecord().getOp());
        Assert.assertEquals(1L, request.getRecord().getExpectedIncarnation());

        this.manager.dropGraph("DEFAULT", "g", 2L, false);
        request = this.lastCommit();
        Assert.assertEquals(2, request.getOpsCount());
        assertOp(request.getOps(0), TxnOp.Type.DELETE, CONFIG);
        assertOp(request.getOps(1), TxnOp.Type.DELETE_PREFIX,
                 SCHEMA.substring(0, SCHEMA.length() - 1));
        Assert.assertEquals(TxnRecord.Op.DROP, request.getRecord().getOp());
        Assert.assertEquals(2L, request.getRecord().getExpectedIncarnation());

        // A drop whose caller clears the stores afterwards also writes the drop marker
        this.manager.dropGraph("DEFAULT", "g", 2L, true);
        request = this.lastCommit();
        Assert.assertEquals(3, request.getOpsCount());
        assertOp(request.getOps(2), TxnOp.Type.PUT, DROPPING);
        Assert.assertFalse(request.getOps(2).getValue().isEmpty());
        Assert.assertEquals(TxnRecord.Op.DROP, request.getRecord().getOp());
    }

    /**
     * A drop clears the stores after its DROP commit, outside of it. Until the dropping
     * Server finished that, the name can't be created again: a new graph would lose what it
     * wrote to the stores. Only that Server ends its drop; another one can't.
     */
    @Test
    public void testCreateWaitsForAnUnfinishedDrop() {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager serverA = new SchemaMetaManager(pd.driver(), "hg", null);
        SchemaMetaManager serverB = new SchemaMetaManager(pd.driver(), "hg", null);
        serverA.createGraph("DEFAULT", "g", configs());
        serverA.commitCreate("DEFAULT", "g", 1L);
        Assert.assertFalse(serverB.dropPending("DEFAULT", "g"));
        Assert.assertFalse(serverB.releaseDrop("DEFAULT", "g"));

        Assert.assertTrue(serverA.dropGraph("DEFAULT", "g", 1L, true).getSucceeded());
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", pd.get(RECORD));
        Assert.assertTrue(serverA.ownsDrop("DEFAULT", "g"));
        Assert.assertFalse(serverB.ownsDrop("DEFAULT", "g"));
        Assert.assertTrue(serverB.dropPending("DEFAULT", "g"));
        Assert.assertThrows(HugeException.class, () -> {
            serverB.createGraph("DEFAULT", "g", configs());
        }, e -> Assert.assertContains("is being dropped", e.getMessage()));
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", pd.get(RECORD));
        Assert.assertEquals("", pd.get(CONFIG));

        // Not the marker of server B
        serverB.finishDrop("DEFAULT", "g");
        Assert.assertTrue(serverA.ownsDrop("DEFAULT", "g"));

        serverA.finishDrop("DEFAULT", "g");
        Assert.assertEquals("", pd.get(DROPPING));
        Assert.assertEquals(2L, serverB.createGraph("DEFAULT", "g", configs()));

        // A marker left by a Server that stopped is released on request, by any Server
        serverB.commitCreate("DEFAULT", "g", 2L);
        serverB.dropGraph("DEFAULT", "g", 2L, true);
        Assert.assertTrue(serverA.releaseDrop("DEFAULT", "g"));
        Assert.assertEquals("", pd.get(DROPPING));
        Assert.assertEquals(3L, serverA.createGraph("DEFAULT", "g", configs()));
    }

    @Test
    public void testRejectedCommitThrows() {
        Mockito.when(this.driver.commit(Mockito.any())).thenReturn(
                TxnResponse.newBuilder().setFailure(TxnResponse.Failure.GRAPH_DROPPED)
                           .build());
        Assert.assertThrows(HugeException.class, () -> {
            this.manager.addSchema("DEFAULT", "g",
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
            // Only while the base label is stored as it was read, and the index label
            // is not there yet
            Assert.assertEquals(3, request.getComparesCount());
            Assert.assertEquals(SCHEMA + "VERTEX_LABEL/ID/2", request.getCompares(0).getKey());
            Assert.assertEquals(TxnCompare.Kind.EQUALS, request.getCompares(0).getKind());
            Assert.assertEquals(stored, request.getCompares(0).getValue());
            Assert.assertEquals(SCHEMA + "INDEX_LABEL/ID/4", request.getCompares(1).getKey());
            Assert.assertEquals(TxnCompare.Kind.ABSENT, request.getCompares(1).getKind());
            Assert.assertEquals(SCHEMA + "INDEX_LABEL/NAME/byName",
                                request.getCompares(2).getKey());
            Assert.assertEquals(TxnCompare.Kind.ABSENT, request.getCompares(2).getKind());
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
        serverA.createGraph("DEFAULT", "g", configs());
        serverA.commitCreate("DEFAULT", "g", 1L);
        incA.set(serverA.adoptRecord("DEFAULT", "g").getIncarnation());
        incB.set(serverB.adoptRecord("DEFAULT", "g").getIncarnation());
        serverA.addSchema("DEFAULT", "g", new VertexLabel(null, IdGenerator.of(2), "person"));

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
        serverB.createGraph("DEFAULT", "g", configs());
        VertexLabel person = new VertexLabel(null, IdGenerator.of(2), "person");
        person.addIndexLabel(IdGenerator.of(4));
        person.addIndexLabel(IdGenerator.of(5));
        serverB.addSchema("DEFAULT", "g", person);
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
            pd.beforeNextCommit(() -> store(pd, without5));
            tx.removeIndexLabelFromBaseLabel(byName);
            VertexLabel stored = VertexLabel.fromMap(JsonUtil.fromJson(pd.get(idKey),
                                                                       Map.class), null);
            Assert.assertEquals(ImmutableSet.of(), stored.indexLabels());

            // Server B adds index label 6 after this Server read the vertex label
            VertexLabel with6 = new VertexLabel(null, IdGenerator.of(2), "person");
            with6.addIndexLabel(IdGenerator.of(4));
            with6.addIndexLabel(IdGenerator.of(6));
            store(pd, without5);
            pd.beforeNextCommit(() -> store(pd, with6));
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

    /**
     * Two Servers both find a name free and create it with ids of their own. Only one
     * commits: the other must not leave a second ID key that the NAME key doesn't point to.
     */
    @Test
    public void testConcurrentCreatesOfOneSchemaName() {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager serverA = new SchemaMetaManager(pd.driver(), "hg", null);
        SchemaMetaManager serverB = new SchemaMetaManager(pd.driver(), "hg", null);
        serverA.addSchema("DEFAULT", "g", new PropertyKey(null, IdGenerator.of(1), "age"));
        String stored = pd.get(SCHEMA + "PROPERTY_KEY/ID/1");
        long revision = revision(pd);

        Assert.assertThrows(ExistedException.class, () -> {
            serverB.addSchema("DEFAULT", "g", new PropertyKey(null, IdGenerator.of(2), "age"));
        }, e -> Assert.assertContains("property key 'age' has existed", e.getMessage()));
        Assert.assertEquals("", pd.get(SCHEMA + "PROPERTY_KEY/ID/2"));
        Assert.assertEquals(stored, pd.get(SCHEMA + "PROPERTY_KEY/NAME/age"));
        // An id that is taken, under another name
        Assert.assertThrows(ExistedException.class, () -> {
            serverB.addSchema("DEFAULT", "g", new PropertyKey(null, IdGenerator.of(1), "city"));
        });
        Assert.assertEquals(stored, pd.get(SCHEMA + "PROPERTY_KEY/ID/1"));
        Assert.assertEquals("", pd.get(SCHEMA + "PROPERTY_KEY/NAME/city"));
        // Nothing was written, so the graph record did not move either
        Assert.assertEquals(revision, revision(pd));

        // The same for an index label, whose commit also holds its base label
        VertexLabel person = new VertexLabel(null, IdGenerator.of(2), "person");
        serverA.addSchema("DEFAULT", "g", person);
        serverA.addIndexLabel("DEFAULT", "g", person, indexLabel(4, "byName", person));
        String label = pd.get(SCHEMA + "VERTEX_LABEL/ID/2");
        Assert.assertThrows(ExistedException.class, () -> {
            serverB.addIndexLabel("DEFAULT", "g", person, indexLabel(5, "byName", person));
        }, e -> Assert.assertContains("index label 'byName' has existed", e.getMessage()));
        Assert.assertEquals("", pd.get(SCHEMA + "INDEX_LABEL/ID/5"));
        Assert.assertEquals(label, pd.get(SCHEMA + "VERTEX_LABEL/ID/2"));
    }

    /**
     * Server B reads a vertex label, server A commits an index label on it, then B saves the
     * label it read with one more property. B's save must not take the index label out of
     * the vertex label again.
     */
    @Test
    public void testUpdateKeepsAnIndexLabelCommittedMeanwhile() {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager serverA = new SchemaMetaManager(pd.driver(), "hg", null);
        SchemaMetaManager serverB = new SchemaMetaManager(pd.driver(), "hg", null);
        VertexLabel person = new VertexLabel(null, IdGenerator.of(2), "person");
        serverA.addSchema("DEFAULT", "g", person);

        VertexLabel readByB = serverB.getVertexLabel("DEFAULT", "g", IdGenerator.of(2));
        serverA.addIndexLabel("DEFAULT", "g", person, indexLabel(4, "byName", person));

        readByB.property(IdGenerator.of(9));
        SchemaElement written = serverB.updateSchema("DEFAULT", "g", readByB);

        for (String key : new String[]{"VERTEX_LABEL/ID/2", "VERTEX_LABEL/NAME/person"}) {
            VertexLabel stored = storedVertexLabel(pd, key);
            Assert.assertEquals(ImmutableSet.of(IdGenerator.of(4)), stored.indexLabels());
            Assert.assertEquals(ImmutableSet.of(IdGenerator.of(9)), stored.properties());
        }
        // What B caches from now on is the label as written
        Assert.assertNotSame(readByB, written);
        Assert.assertEquals(ImmutableSet.of(IdGenerator.of(4)),
                            ((VertexLabel) written).indexLabels());
        Assert.assertEquals(pd.get(SCHEMA + "VERTEX_LABEL/ID/2"), written.stored());

        // The instance B read still stands for what B read: one more change of it keeps
        // the index label as well
        readByB.nullableKey(IdGenerator.of(9));
        serverB.updateSchema("DEFAULT", "g", readByB);
        VertexLabel stored = storedVertexLabel(pd, "VERTEX_LABEL/ID/2");
        Assert.assertEquals(ImmutableSet.of(IdGenerator.of(4)), stored.indexLabels());
        Assert.assertEquals(ImmutableSet.of(IdGenerator.of(9)), stored.nullableKeys());
    }

    /**
     * Two Servers change one vertex label they both read before: each change of a list or
     * of the userdata lands, whichever commits last. A field that both set ends as the later
     * commit set it, and that commit still keeps the rest of the earlier one.
     */
    @Test
    public void testUpdateMergesChangesOfAnotherServer() {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager serverA = new SchemaMetaManager(pd.driver(), "hg", null);
        SchemaMetaManager serverB = new SchemaMetaManager(pd.driver(), "hg", null);
        VertexLabel person = new VertexLabel(null, IdGenerator.of(2), "person");
        person.property(IdGenerator.of(1));
        person.userdata("owner", "a");
        person.userdata("team", "x");
        person.userdata(Userdata.CREATE_TIME, DateUtil.now());
        person.ttl(100L);
        serverA.addSchema("DEFAULT", "g", person);

        VertexLabel ofA = serverA.getVertexLabel("DEFAULT", "g", "person");
        VertexLabel ofB = serverB.getVertexLabel("DEFAULT", "g", "person");
        ofA.property(IdGenerator.of(7));
        ofA.userdata("note", "from a");
        ofA.removeUserdata("team");
        ofA.ttl(200L);
        serverA.updateSchema("DEFAULT", "g", ofA);
        ofB.property(IdGenerator.of(8));
        ofB.userdata("owner", "b");
        serverB.updateSchema("DEFAULT", "g", ofB);

        VertexLabel stored = storedVertexLabel(pd, "VERTEX_LABEL/NAME/person");
        Assert.assertEquals(ImmutableSet.of(IdGenerator.of(1), IdGenerator.of(7),
                                            IdGenerator.of(8)), stored.properties());
        Assert.assertEquals("from a", stored.userdata().get("note"));
        Assert.assertEquals("b", stored.userdata().get("owner"));
        Assert.assertFalse(stored.userdata().containsKey("team"));
        // What B read and did not change stays as A wrote it
        Assert.assertEquals(200L, stored.ttl());
        Assert.assertEquals(ofA.userdata().get(Userdata.CREATE_TIME),
                            stored.userdata().get(Userdata.CREATE_TIME));
        Assert.assertEquals(pd.get(SCHEMA + "VERTEX_LABEL/ID/2"),
                            pd.get(SCHEMA + "VERTEX_LABEL/NAME/person"));

        // B's instance was written merged. A change of A after that is not undone by what
        // B's instance changed before: only its new change is applied
        VertexLabel laterOfA = serverA.getVertexLabel("DEFAULT", "g", "person");
        laterOfA.userdata("owner", "c");
        serverA.updateSchema("DEFAULT", "g", laterOfA);
        ofB.userdata("extra", "1");
        serverB.updateSchema("DEFAULT", "g", ofB);
        stored = storedVertexLabel(pd, "VERTEX_LABEL/ID/2");
        Assert.assertEquals("c", stored.userdata().get("owner"));
        Assert.assertEquals("1", stored.userdata().get("extra"));
        Assert.assertEquals("from a", stored.userdata().get("note"));

        // Both Servers set the status of the label they read
        VertexLabel statusOfA = serverA.getVertexLabel("DEFAULT", "g", "person");
        VertexLabel statusOfB = serverB.getVertexLabel("DEFAULT", "g", "person");
        statusOfA.status(SchemaStatus.REBUILDING);
        statusOfA.property(IdGenerator.of(9));
        serverA.updateSchema("DEFAULT", "g", statusOfA);
        statusOfB.status(SchemaStatus.DELETING);
        serverB.updateSchema("DEFAULT", "g", statusOfB);
        stored = storedVertexLabel(pd, "VERTEX_LABEL/ID/2");
        Assert.assertEquals(SchemaStatus.DELETING, stored.status());
        Assert.assertTrue(stored.properties().contains(IdGenerator.of(9)));
    }

    /**
     * An update of an element that another Server removed since it was read must not put
     * it back, and a remove must not take an element created under the name since
     */
    @Test
    public void testUpdateAndRemoveOfAnElementRemovedMeanwhile() {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager serverA = new SchemaMetaManager(pd.driver(), "hg", null);
        SchemaMetaManager serverB = new SchemaMetaManager(pd.driver(), "hg", null);
        serverA.addSchema("DEFAULT", "g", new VertexLabel(null, IdGenerator.of(2), "person"));
        VertexLabel readByB = serverB.getVertexLabel("DEFAULT", "g", "person");

        serverA.removeSchema("DEFAULT", "g", readByB);
        Assert.assertEquals("", pd.get(SCHEMA + "VERTEX_LABEL/ID/2"));
        readByB.property(IdGenerator.of(9));
        Assert.assertThrows(HugeException.class, () -> {
            serverB.updateSchema("DEFAULT", "g", readByB);
        }, e -> Assert.assertContains("it was removed on another server", e.getMessage()));
        Assert.assertEquals("", pd.get(SCHEMA + "VERTEX_LABEL/ID/2"));
        Assert.assertEquals("", pd.get(SCHEMA + "VERTEX_LABEL/NAME/person"));

        // The name is created again, with another id
        serverA.addSchema("DEFAULT", "g", new VertexLabel(null, IdGenerator.of(7), "person"));
        String created = pd.get(SCHEMA + "VERTEX_LABEL/NAME/person");
        long revision = revision(pd);
        serverB.removeSchema("DEFAULT", "g", readByB);
        Assert.assertEquals(created, pd.get(SCHEMA + "VERTEX_LABEL/NAME/person"));
        Assert.assertEquals(created, pd.get(SCHEMA + "VERTEX_LABEL/ID/7"));
        Assert.assertEquals(revision, revision(pd));
    }

    @Test
    public void testUpdateGivesUpWhenTheElementKeepsChanging() {
        VertexLabel person = new VertexLabel(null, IdGenerator.of(2), "person");
        String stored = JsonUtil.toJson(person.asMap());
        Mockito.when(this.driver.get(SCHEMA + "VERTEX_LABEL/ID/2")).thenReturn(stored);
        VertexLabel read = this.manager.getVertexLabel("DEFAULT", "g", IdGenerator.of(2));
        Mockito.when(this.driver.commit(Mockito.any())).thenReturn(
                TxnResponse.newBuilder().setFailure(TxnResponse.Failure.COMPARE_FAILED)
                           .build());
        Assert.assertThrows(HugeException.class, () -> {
            this.manager.updateSchema("DEFAULT", "g", read);
        }, e -> Assert.assertContains("kept changing on other servers in 3 attempts",
                                      e.getMessage()));
        Mockito.verify(this.driver, Mockito.times(3)).commit(Mockito.any());
        Assert.assertEquals(stored, read.stored());
    }

    @SuppressWarnings("unchecked")
    private static long revision(FakeSchemaSyncPd pd) {
        Map<String, Object> record = JsonUtil.fromJson(pd.get(RECORD), Map.class);
        return ((Number) record.get("rev")).longValue();
    }

    private static IndexLabel indexLabel(long id, String name, VertexLabel base) {
        IndexLabel indexLabel = new IndexLabel(null, IdGenerator.of(id), name);
        indexLabel.baseType(HugeType.VERTEX_LABEL);
        indexLabel.baseValue(base.id());
        return indexLabel;
    }

    /**
     * What another Server wrote of the vertex label, as PD holds it afterwards
     */
    private static void store(FakeSchemaSyncPd pd, VertexLabel label) {
        String content = JsonUtil.toJson(label.asMap());
        pd.put(SCHEMA + "VERTEX_LABEL/ID/" + label.id().asString(), content);
        pd.put(SCHEMA + "VERTEX_LABEL/NAME/" + label.name(), content);
    }

    @SuppressWarnings("unchecked")
    private static VertexLabel storedVertexLabel(FakeSchemaSyncPd pd, String key) {
        return VertexLabel.fromMap(JsonUtil.fromJson(pd.get(SCHEMA + key), Map.class), null);
    }

    private void assertAdded(SchemaElement schema, String type, long incarnation) {
        Mockito.clearInvocations(this.driver);
        this.manager.addSchema("DEFAULT", "g", schema);
        TxnRequest request = this.lastCommit();
        // Only while neither key exists
        Assert.assertEquals(2, request.getComparesCount());
        for (int i = 0; i < 2; i++) {
            Assert.assertEquals(request.getOps(i).getKey(), request.getCompares(i).getKey());
            Assert.assertEquals(TxnCompare.Kind.ABSENT, request.getCompares(i).getKind());
        }
        Assert.assertEquals(request.getOps(0).getValue(), schema.stored());
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

    private static Map<String, Object> configs() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("store", "g");
        return configs;
    }

    private static TxnResponse adopted(long revision, long incarnation, String state) {
        return TxnResponse.newBuilder().setSucceeded(true).setRevision(revision)
                          .setIncarnation(incarnation).setState(state).build();
    }

    private static TxnResponse succeeded(long incarnation) {
        return TxnResponse.newBuilder().setSucceeded(true).setRevision(7)
                          .setIncarnation(incarnation).build();
    }
}
