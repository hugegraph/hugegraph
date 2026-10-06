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
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hugegraph.HugeException;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.HugeGraphParams;
import org.apache.hugegraph.backend.id.IdGenerator;
import org.apache.hugegraph.backend.tx.SchemaTransactionV2;
import org.apache.hugegraph.meta.MetaDriver;
import org.apache.hugegraph.meta.MetaManager;
import org.apache.hugegraph.meta.PdMetaDriver;
import org.apache.hugegraph.pd.grpc.kv.TxnOp;
import org.apache.hugegraph.pd.grpc.kv.TxnRecord;
import org.apache.hugegraph.pd.grpc.kv.TxnRequest;
import org.apache.hugegraph.pd.grpc.kv.TxnResponse;
import org.apache.hugegraph.schema.EdgeLabel;
import org.apache.hugegraph.schema.IndexLabel;
import org.apache.hugegraph.schema.PropertyKey;
import org.apache.hugegraph.schema.SchemaElement;
import org.apache.hugegraph.schema.VertexLabel;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.util.LockUtil;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

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
        Assert.assertEquals(0L, this.manager.openIncarnation("DEFAULT", "g"));
        Mockito.when(this.driver.get(RECORD))
               .thenReturn("{\"rev\":9,\"inc\":3,\"state\":\"LIVE\"}");
        Assert.assertEquals(3L, this.manager.openIncarnation("DEFAULT", "g"));
        // A dropped graph keeps its incarnation, so a later recreate doesn't let it write
        Mockito.when(this.driver.get(RECORD))
               .thenReturn("{\"rev\":10,\"inc\":3,\"state\":\"DROPPED\"}");
        Assert.assertEquals(3L, this.manager.openIncarnation("DEFAULT", "g"));
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
        Assert.assertEquals(0, request.getOpsCount());
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
            IndexLabel byName = new IndexLabel(null, IdGenerator.of(4), "byName");
            byName.baseType(HugeType.VERTEX_LABEL);
            byName.baseValue(person.id());
            tx.addIndexLabel(person, byName);

            TxnRequest request = this.lastCommit();
            Assert.assertEquals(4, request.getOpsCount());
            Assert.assertEquals(SCHEMA + "INDEX_LABEL/ID/4", request.getOps(0).getKey());
            Assert.assertEquals(SCHEMA + "INDEX_LABEL/NAME/byName", request.getOps(1).getKey());
            Assert.assertEquals(SCHEMA + "VERTEX_LABEL/ID/2", request.getOps(2).getKey());
            Assert.assertEquals(SCHEMA + "VERTEX_LABEL/NAME/person",
                                request.getOps(3).getKey());
            Assert.assertContains("\"indexLabels\":[4]", request.getOps(3).getValue());
            assertBump(request, 0L);
            Mockito.verify(graphMeta).notifyGraphVertexCacheClear("DEFAULT", "g");
        } finally {
            LockUtil.destroy("DEFAULT-g");
            Whitebox.setInternalState(MetaManager.instance(), "graphMetaManager",
                                      oldGraphMeta);
        }
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
