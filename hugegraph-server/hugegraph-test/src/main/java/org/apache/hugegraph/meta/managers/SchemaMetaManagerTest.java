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

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hugegraph.exception.HugeException;
import org.apache.hugegraph.id.IdGenerator;
import org.apache.hugegraph.meta.MetaDriver;
import org.apache.hugegraph.meta.MetaManager;
import org.apache.hugegraph.meta.PdMetaDriver;
import org.apache.hugegraph.pd.grpc.kv.TxnOp;
import org.apache.hugegraph.pd.grpc.kv.TxnRecord;
import org.apache.hugegraph.pd.grpc.kv.TxnRequest;
import org.apache.hugegraph.pd.grpc.kv.TxnResponse;
import org.apache.hugegraph.struct.schema.EdgeLabel;
import org.apache.hugegraph.struct.schema.IndexLabel;
import org.apache.hugegraph.struct.schema.PropertyKey;
import org.apache.hugegraph.struct.schema.SchemaElement;
import org.apache.hugegraph.struct.schema.VertexLabel;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.testutil.Whitebox;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class SchemaMetaManagerTest {

    private static final String SCHEMA = "HUGEGRAPH/hg/GRAPHSPACE/DEFAULT/g/SCHEMA/";
    private static final String RECORD = "HUGEGRAPH/hg/SCHEMA_SYNC/DEFAULT/g";
    private static final String CONFIG = "HUGEGRAPH/hg/GRAPHSPACE/DEFAULT/GRAPH_CONF/g";

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
        assertWritten(new PropertyKey(null, IdGenerator.of(1), "name"), "PROPERTY_KEY", 1L);
        assertWritten(new VertexLabel(null, IdGenerator.of(2), "person"), "VERTEX_LABEL", 1L);
        assertWritten(new EdgeLabel(null, IdGenerator.of(3), "knows"), "EDGE_LABEL", 1L);
        assertWritten(new IndexLabel(null, IdGenerator.of(4), "byName"), "INDEX_LABEL", 1L);
        Mockito.verify(this.driver, Mockito.never()).put(Mockito.any(), Mockito.any());
        // The incarnation was learned at open, never read on a write
        Mockito.verify(this.driver, Mockito.never()).get(Mockito.any());
    }

    /**
     * Removing an element that is not in PD through MetaManager commits nothing and does not
     * fail on the missing element
     */
    @Test
    public void testRemovingAMissingElementCommitsNothing() {
        MetaManager meta = Mockito.mock(MetaManager.class, Mockito.CALLS_REAL_METHODS);
        Whitebox.setInternalState(meta, "schemaMetaManager", this.manager);
        Assert.assertEquals(IdGenerator.ZERO,
                            meta.removePropertyKey("DEFAULT", "g", IdGenerator.of(1)));
        Assert.assertEquals(IdGenerator.ZERO,
                            meta.removeVertexLabel("DEFAULT", "g", IdGenerator.of(2)));
        Assert.assertEquals(IdGenerator.ZERO,
                            meta.removeEdgeLabel("DEFAULT", "g", IdGenerator.of(3)));
        Assert.assertEquals(IdGenerator.ZERO,
                            meta.removeIndexLabel("DEFAULT", "g", IdGenerator.of(4)));
        Mockito.verify(this.driver, Mockito.never()).commit(Mockito.any());
    }

    @Test
    public void testUpdateSchemaIsOneCommit() {
        VertexLabel person = new VertexLabel(null, IdGenerator.of(2), "person");
        person.property(IdGenerator.of(9));
        this.manager.updateSchema("DEFAULT", "g", person);
        TxnRequest request = this.lastCommit();
        Assert.assertEquals(0, request.getComparesCount());
        Assert.assertEquals(2, request.getOpsCount());
        assertOp(request.getOps(0), TxnOp.Type.PUT, SCHEMA + "VERTEX_LABEL/ID/2");
        assertOp(request.getOps(1), TxnOp.Type.PUT, SCHEMA + "VERTEX_LABEL/NAME/person");
        Assert.assertContains("\"properties\":[9]", request.getOps(0).getValue());
        assertBump(request, 0L);
        Mockito.verify(this.driver, Mockito.never()).put(Mockito.any(), Mockito.any());
    }

    /**
     * The index label and its base label with the index label added go in one commit: the
     * two writes SchemaTransactionV2.addIndexLabel did before are one atomic change
     */
    @Test
    public void testIndexLabelAndBaseLabelInOneCommit() {
        VertexLabel person = new VertexLabel(null, IdGenerator.of(2), "person");
        IndexLabel byName = new IndexLabel(null, IdGenerator.of(4), "byName");
        person.addIndexLabel(byName.id());
        this.manager.addSchema("DEFAULT", "g", byName, person);
        TxnRequest request = this.lastCommit();
        Assert.assertEquals(4, request.getOpsCount());
        assertOp(request.getOps(0), TxnOp.Type.PUT, SCHEMA + "INDEX_LABEL/ID/4");
        assertOp(request.getOps(1), TxnOp.Type.PUT, SCHEMA + "INDEX_LABEL/NAME/byName");
        assertOp(request.getOps(2), TxnOp.Type.PUT, SCHEMA + "VERTEX_LABEL/ID/2");
        assertOp(request.getOps(3), TxnOp.Type.PUT, SCHEMA + "VERTEX_LABEL/NAME/person");
        Assert.assertContains("\"indexLabels\":[4]", request.getOps(2).getValue());
        assertBump(request, 0L);
        Mockito.verify(this.driver, Mockito.times(1)).commit(Mockito.any());
    }

    @Test
    public void testRemoveSchemaIsOneCommit() {
        VertexLabel person = new VertexLabel(null, IdGenerator.of(2), "person");
        this.manager.removeSchema("DEFAULT", "g", person);
        TxnRequest request = this.lastCommit();
        Assert.assertEquals(0, request.getComparesCount());
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

    /**
     * A graph opens with its record, read after PD's ReadIndex barrier; an absent record (a
     * graph from before records) is created with one BUMP; an unreadable one fails the open
     */
    @Test
    public void testOpenRecord() {
        Mockito.when(this.driver.get(RECORD)).thenReturn(
                "{\"rev\":9,\"inc\":3,\"state\":\"DROPPED\"}");
        TxnResponse opened = this.manager.openRecord("DEFAULT", "g");
        Assert.assertEquals(9L, opened.getRevision());
        Assert.assertEquals(3L, opened.getIncarnation());
        Assert.assertEquals("DROPPED", opened.getState());
        Mockito.verify(this.driver, Mockito.never()).commit(Mockito.any());

        Mockito.when(this.driver.get(RECORD)).thenReturn("");
        Mockito.when(this.driver.commit(Mockito.any())).thenReturn(record(5, 1, "LIVE"));
        opened = this.manager.openRecord("DEFAULT", "g");
        Assert.assertEquals(1L, opened.getIncarnation());
        TxnRequest request = this.lastCommit();
        Assert.assertEquals(0, request.getOpsCount());
        Assert.assertEquals(0, request.getComparesCount());
        assertBump(request, 0L);

        Mockito.clearInvocations(this.driver);
        // Not JSON, not a record, or a record PD's applier and tracker would reject
        for (String value : new String[]{"not json", "[1]", "{}", "{\"rev\":1,\"inc\":1}",
                                         "{\"rev\":1,\"inc\":\"1\",\"state\":\"LIVE\"}",
                                         "{\"rev\":1,\"inc\":0,\"state\":\"LIVE\"}",
                                         "{\"rev\":-1,\"inc\":1,\"state\":\"LIVE\"}",
                                         "{\"rev\":0,\"inc\":1,\"state\":\"LIVE\"}",
                                         "{\"inc\":1,\"state\":\"LIVE\"}",
                                         "{\"rev\":1,\"inc\":1.5,\"state\":\"LIVE\"}",
                                         "{\"rev\":1,\"inc\":1,\"state\":\"live\"}"}) {
            Mockito.when(this.driver.get(RECORD)).thenReturn(value);
            Assert.assertThrows(HugeException.class, () -> this.manager.openRecord("DEFAULT", "g"),
                                e -> Assert.assertContains("can't be read", e.getMessage()));
        }
        Mockito.verify(this.driver, Mockito.never()).commit(Mockito.any());
    }

    /**
     * An instance that opened a graph created before the upgrade gets incarnation 1 from the
     * BUMP that creates its record, so it can't write or drop the graph after another Server
     * dropped and recreated it
     */
    @Test
    public void testInstanceOpenedOnALegacyGraphCantWriteARecreatedOne() {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        AtomicLong incA = new AtomicLong();
        SchemaMetaManager serverA = new SchemaMetaManager(pd.driver(), "hg", null, incA);
        AtomicLong incB = new AtomicLong();
        SchemaMetaManager serverB = new SchemaMetaManager(pd.driver(), "hg", null, incB);

        incA.set(serverA.openRecord("DEFAULT", "g").getIncarnation());
        Assert.assertEquals(1L, incA.get());
        incB.set(serverB.openRecord("DEFAULT", "g").getIncarnation());
        Assert.assertEquals(1L, incB.get());

        // Server B drops the graph and creates it again
        Assert.assertTrue(serverB.dropGraph("DEFAULT", "g", incB.get()).getSucceeded());
        Assert.assertEquals(2L, serverB.createGraph("DEFAULT", "g"));
        AtomicLong incNew = new AtomicLong(serverB.openRecord("DEFAULT", "g")
                                                  .getIncarnation());
        Assert.assertEquals(2L, incNew.get());
        new SchemaMetaManager(pd.driver(), "hg", null, incNew).addSchema(
                "DEFAULT", "g", new PropertyKey(null, IdGenerator.of(1), "new"));

        // The instance of server A belongs to the dropped incarnation
        Assert.assertThrows(HugeException.class, () -> {
            serverA.addSchema("DEFAULT", "g", new PropertyKey(null, IdGenerator.of(2), "old"));
        }, e -> Assert.assertContains("INCARNATION_MISMATCH", e.getMessage()));
        // The instance keeps the incarnation it opened with
        Assert.assertEquals(1L, incA.get());
        Assert.assertEquals("", pd.get(SCHEMA + "PROPERTY_KEY/ID/2"));
        Assert.assertEquals(TxnResponse.Failure.INCARNATION_MISMATCH,
                            serverA.dropGraph("DEFAULT", "g", incA.get()).getFailure());
        Assert.assertContains("\"inc\":2,\"state\":\"LIVE\"", pd.get(RECORD));
        Assert.assertContains("\"name\":\"new\"", pd.get(SCHEMA + "PROPERTY_KEY/ID/1"));
    }

    /**
     * A create commits only while the graph config is absent and the record is absent or
     * DROPPED as read before the commit; the config is written last by the create. A LIVE
     * record without a config, left by a create that died or by a graph opened from a local
     * config, keeps the name taken until a drop of the name releases it; the next create then
     * gets the next incarnation, and the schema the dead create left is gone.
     */
    @Test
    public void testCreateGraph() {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager manager = new SchemaMetaManager(pd.driver(), "hg", null);
        Assert.assertEquals(1L, manager.createGraph("DEFAULT", "g"));
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", pd.get(RECORD));
        new SchemaMetaManager(pd.driver(), "hg", null, new AtomicLong(1L)).addSchema(
                "DEFAULT", "g", new PropertyKey(null, IdGenerator.of(1), "a"));

        // The creator died before it wrote the config: the name is taken until a drop
        Assert.assertEquals(0L, manager.createGraph("DEFAULT", "g"));
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\",\"src\":\"create\"",
                              pd.get(RECORD));
        Assert.assertTrue(manager.dropUnfinishedCreate("DEFAULT", "g"));
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", pd.get(RECORD));
        Assert.assertEquals("", pd.get(SCHEMA + "PROPERTY_KEY/ID/1"));
        Assert.assertFalse(manager.dropUnfinishedCreate("DEFAULT", "g"));
        Assert.assertEquals(2L, manager.createGraph("DEFAULT", "g"));
        Assert.assertContains("\"inc\":2,\"state\":\"LIVE\"", pd.get(RECORD));

        // With the config in place the name is taken, a legacy graph's name too, and a
        // release of an unfinished create leaves the graph alone
        pd.put(CONFIG, "{\"store\":\"g\"}");
        Assert.assertEquals(0L, manager.createGraph("DEFAULT", "g"));
        Assert.assertFalse(manager.dropUnfinishedCreate("DEFAULT", "g"));
        Assert.assertContains("\"inc\":2,\"state\":\"LIVE\"", pd.get(RECORD));
        Assert.assertEquals("{\"store\":\"g\"}", pd.get(CONFIG));

        // A record no create wrote, as a graph opened from a local config has, is left alone
        String local = "HUGEGRAPH/hg/SCHEMA_SYNC/DEFAULT/local";
        Assert.assertEquals(1L, manager.openRecord("DEFAULT", "local").getIncarnation());
        String opened = pd.get(local);
        Assert.assertFalse(opened.contains("src"));
        Assert.assertEquals(0L, manager.createGraph("DEFAULT", "local"));
        Assert.assertFalse(manager.dropUnfinishedCreate("DEFAULT", "local"));
        Assert.assertEquals(opened, pd.get(local));
        pd.put("HUGEGRAPH/hg/GRAPHSPACE/DEFAULT/GRAPH_CONF/legacy", "{}");
        Assert.assertEquals(0L, manager.createGraph("DEFAULT", "legacy"));
        Assert.assertEquals("", pd.get("HUGEGRAPH/hg/SCHEMA_SYNC/DEFAULT/legacy"));

        Mockito.when(this.driver.commit(Mockito.any())).thenReturn(
                TxnResponse.newBuilder().setFailure(TxnResponse.Failure.INVALID_RECORD)
                           .build());
        Assert.assertThrows(HugeException.class, () -> {
            this.manager.createGraph("DEFAULT", "g");
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
        Assert.assertEquals(1L, serverB.createGraph("DEFAULT", "g"));
        pd.put(CONFIG, "{\"store\":\"g\"}");
        SchemaMetaManager instanceA = new SchemaMetaManager(pd.driver(), "hg", null,
                                                            new AtomicLong(1L));

        // The drop commits first
        pd.beforeNextCommit(() -> serverB.dropGraph("DEFAULT", "g", 1L));
        Assert.assertThrows(HugeException.class, () -> {
            instanceA.addSchema("DEFAULT", "g", new PropertyKey(null, IdGenerator.of(1), "a"));
        }, e -> Assert.assertContains("GRAPH_DROPPED", e.getMessage()));
        Assert.assertEquals("", pd.get(SCHEMA + "PROPERTY_KEY/ID/1"));
        Assert.assertEquals("", pd.get(CONFIG));

        // The write commits first
        Assert.assertEquals(2L, serverB.createGraph("DEFAULT", "g"));
        SchemaMetaManager instanceA2 = new SchemaMetaManager(pd.driver(), "hg", null,
                                                             new AtomicLong(2L));
        instanceA2.addSchema("DEFAULT", "g", new PropertyKey(null, IdGenerator.of(1), "a"));
        Assert.assertTrue(serverB.dropGraph("DEFAULT", "g", 2L).getSucceeded());
        Assert.assertEquals("", pd.get(SCHEMA + "PROPERTY_KEY/ID/1"));
        Assert.assertEquals("", pd.get(SCHEMA + "PROPERTY_KEY/NAME/a"));
        Assert.assertContains("\"inc\":2,\"state\":\"DROPPED\"", pd.get(RECORD));
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

    private void assertWritten(SchemaElement schema, String type, long incarnation) {
        Mockito.clearInvocations(this.driver);
        this.manager.addSchema("DEFAULT", "g", schema);
        TxnRequest request = this.lastCommit();
        Assert.assertEquals(0, request.getComparesCount());
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

    private static TxnResponse record(long revision, long incarnation, String state) {
        return TxnResponse.newBuilder().setSucceeded(true).setRevision(revision)
                          .setIncarnation(incarnation).setState(state).build();
    }

    private static TxnResponse succeeded(long incarnation) {
        return TxnResponse.newBuilder().setSucceeded(true).setRevision(7)
                          .setIncarnation(incarnation).build();
    }
}
