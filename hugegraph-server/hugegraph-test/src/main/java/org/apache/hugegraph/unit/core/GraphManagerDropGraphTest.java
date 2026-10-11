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

package org.apache.hugegraph.unit.core;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hugegraph.exception.HugeException;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.core.GraphManager;
import org.apache.hugegraph.event.EventHub;
import org.apache.hugegraph.meta.MetaManager;
import org.apache.hugegraph.meta.managers.FakeSchemaSyncPd;
import org.apache.hugegraph.meta.managers.SchemaMetaManager;
import org.apache.hugegraph.pd.grpc.kv.TxnResponse;
import org.apache.hugegraph.space.GraphSpace;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import sun.misc.Unsafe;

public class GraphManagerDropGraphTest {

    private static final String RECORD = "HUGEGRAPH/hg/SCHEMA_SYNC/DEFAULT/g";
    private static final String SCHEMA = "HUGEGRAPH/hg/GRAPHSPACE/DEFAULT/g/SCHEMA";
    private static final String CONFIG = "HUGEGRAPH/hg/GRAPHSPACE/DEFAULT/GRAPH_CONF/g";
    private static final String DROPPING = "HUGEGRAPH/hg/GRAPH_DROPPING/DEFAULT/g";

    /**
     * A drop is one commit with the incarnation of the instance, and only after it the stores
     * are cleared: nothing destructive runs before PD accepted the drop. The commit says that
     * this Server clears the stores, and the drop is finished only after they are cleared.
     */
    @Test
    public void testDropCommitsBeforeItClears() throws Exception {
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        Mockito.when(graph.schemaIncarnation()).thenReturn(2L);
        SchemaMetaManager schemaMeta = Mockito.mock(SchemaMetaManager.class);
        Mockito.when(schemaMeta.dropGraph("DEFAULT", "g", 2L, true)).thenReturn(
                TxnResponse.newBuilder().setSucceeded(true).build());
        MetaManager meta = Mockito.mock(MetaManager.class);
        Mockito.when(meta.schemaMetaManager()).thenReturn(schemaMeta);
        GraphManager manager = newManager(meta, graph);

        manager.dropGraph("DEFAULT", "g", true);

        InOrder order = Mockito.inOrder(graph, meta, schemaMeta);
        order.verify(schemaMeta).dropGraph("DEFAULT", "g", 2L, true);
        order.verify(meta).notifyGraphRemove("DEFAULT", "g");
        order.verify(graph).clearBackendForDrop();
        order.verify(schemaMeta).finishDrop("DEFAULT", "g");
        // The config and the schema in PD go with the DROP commit alone
        Mockito.verify(meta, Mockito.never()).removeGraphConfig(Mockito.any(), Mockito.any());
        Mockito.verify(graph, Mockito.never()).clearBackend();
        Mockito.verify(schemaMeta, Mockito.never()).creatingIncarnation(Mockito.any(),
                                                                        Mockito.any());
    }

    /**
     * Two Servers drop the same graph at once: PD drops it once, and only the Server whose
     * DROP commit it was clears the stores. The second drop finds the incarnation dropped by
     * another Server and only removes its own instance: once the first Server finished, a
     * new graph of the name may exist, and a late clear would take its data.
     */
    @Test
    public void testTwoDropsAtOnceDropOnce() throws Exception {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        MetaManager metaA = meta(liveGraph(pd));
        MetaManager metaB = meta(new SchemaMetaManager(pd.driver(), "hg", null));
        pd.put(SCHEMA + "/PROPERTY_KEY/ID/1", "{}");
        HugeGraph graphA = Mockito.mock(HugeGraph.class);
        Mockito.when(graphA.schemaIncarnation()).thenReturn(1L);
        HugeGraph graphB = Mockito.mock(HugeGraph.class);
        Mockito.when(graphB.schemaIncarnation()).thenReturn(1L);
        GraphManager serverA = newManager(metaA, graphA);
        GraphManager serverB = newManager(metaB, graphB);

        // Server B drops the graph while server A clears its stores
        Mockito.doAnswer(invocation -> {
            serverB.dropGraph("DEFAULT", "g", true);
            Assert.assertNotEquals("", pd.get(DROPPING));
            return null;
        }).when(graphA).clearBackendForDrop();
        serverA.dropGraph("DEFAULT", "g", true);
        String dropped = pd.get(RECORD);
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", dropped);
        Assert.assertEquals("", pd.get(CONFIG));
        Assert.assertEquals("", pd.get(SCHEMA + "/PROPERTY_KEY/ID/1"));
        Assert.assertEquals("", pd.get(DROPPING));
        Mockito.verify(graphA).clearBackendForDrop();
        Mockito.verify(graphB, Mockito.never()).clearBackendForDrop();
        Mockito.verify(graphB, Mockito.atLeastOnce()).close();

        // Both Servers are done with the graph, and PD dropped it once
        Assert.assertThrows(IllegalArgumentException.class, () -> {
            serverB.dropGraph("DEFAULT", "g", true);
        });
        Assert.assertEquals(dropped, pd.get(RECORD));
    }

    /**
     * Server A drops the graph. While it clears the stores, after its DROP commit, no Server
     * creates the name again: the new graph would lose what it wrote to the stores. Once A
     * finished, the create goes through.
     */
    @Test
    public void testCreateOnAnotherServerWaitsForTheStoreClear() throws Exception {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        HugeGraph graphA = Mockito.mock(HugeGraph.class);
        Mockito.when(graphA.schemaIncarnation()).thenReturn(1L);
        GraphManager serverA = newManager(meta(liveGraph(pd)), graphA);
        SchemaMetaManager serverB = new SchemaMetaManager(pd.driver(), "hg", null);

        AtomicReference<Throwable> createOfB = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            // A has committed its DROP and is clearing the shared stores
            Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", pd.get(RECORD));
            try {
                serverB.createGraph("DEFAULT", "g", configs());
            } catch (Throwable e) {
                createOfB.set(e);
            }
            Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", pd.get(RECORD));
            Assert.assertEquals("", pd.get(CONFIG));
            return null;
        }).when(graphA).clearBackendForDrop();

        serverA.dropGraph("DEFAULT", "g", true);

        Assert.assertTrue(createOfB.get() instanceof HugeException);
        Assert.assertContains("is being dropped", createOfB.get().getMessage());
        Assert.assertEquals(2L, serverB.createGraph("DEFAULT", "g", configs()));
    }

    /**
     * A Server whose drop did not get past clearing the stores keeps the name: a drop of the
     * graph by name on another Server, such as a client sending its request again, does not
     * end that drop, and a second request on the same Server does not run beside it. The
     * dropping Server goes on when the drop is sent to it again.
     */
    @Test
    public void testOnlyTheDroppingServerFinishesItsDrop() throws Exception {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        HugeGraph graphA = Mockito.mock(HugeGraph.class);
        Mockito.when(graphA.schemaIncarnation()).thenReturn(1L);
        GraphManager serverA = newManager(meta(liveGraph(pd)), graphA);
        SchemaMetaManager schemaMetaB = new SchemaMetaManager(pd.driver(), "hg", null);
        MetaManager metaB = meta(schemaMetaB);
        Mockito.when(metaB.graphConfigs("DEFAULT")).thenReturn(Collections.emptyMap());
        GraphManager serverB = newManager(metaB, null);

        AtomicReference<Throwable> secondDropOnA = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            try {
                serverA.dropGraph("DEFAULT", "g", true);
            } catch (Throwable e) {
                secondDropOnA.set(e);
            }
            throw new HugeException("Store unavailable");
        }).doNothing().when(graphA).clearBackendForDrop();
        Assert.assertThrows(HugeException.class, () -> {
            serverA.dropGraph("DEFAULT", "g", true);
        }, e -> Assert.assertContains("Store unavailable", e.getMessage()));
        Assert.assertContains("is being dropped", secondDropOnA.get().getMessage());
        Mockito.verify(graphA, Mockito.times(1)).clearBackendForDrop();

        Assert.assertThrows(HugeException.class, () -> {
            serverB.dropGraph("DEFAULT", "g", true);
        }, e -> Assert.assertContains("is being dropped", e.getMessage()));
        Assert.assertNotEquals("", pd.get(DROPPING));
        Assert.assertThrows(HugeException.class, () -> {
            schemaMetaB.createGraph("DEFAULT", "g", configs());
        }, e -> Assert.assertContains("is being dropped", e.getMessage()));

        serverA.dropGraph("DEFAULT", "g", true);
        Mockito.verify(graphA, Mockito.times(2)).clearBackendForDrop();
        Assert.assertEquals("", pd.get(DROPPING));
        Assert.assertEquals(2L, schemaMetaB.createGraph("DEFAULT", "g", configs()));
    }

    /**
     * The dropping Server stopped before it cleared the stores, so its drop marker stays.
     * Nothing takes it over by itself. An explicit release on any Server frees the name,
     * and it is refused while that Server itself is dropping the graph.
     */
    @Test
    public void testReleaseOfADropLeftUnfinished() throws Exception {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        // The DROP commit of a Server that is gone
        Assert.assertTrue(liveGraph(pd).dropGraph("DEFAULT", "g", 1L, true).getSucceeded());
        SchemaMetaManager schemaMeta = new SchemaMetaManager(pd.driver(), "hg", null);
        GraphManager manager = newManager(meta(schemaMeta), null);

        Assert.assertThrows(HugeException.class, () -> {
            schemaMeta.createGraph("DEFAULT", "g", configs());
        }, e -> Assert.assertContains("is being dropped", e.getMessage()));
        manager.releaseDroppedGraph("DEFAULT", "g");
        Assert.assertEquals("", pd.get(DROPPING));
        Assert.assertEquals(2L, schemaMeta.createGraph("DEFAULT", "g", configs()));
        Assert.assertThrows(IllegalArgumentException.class, () -> {
            manager.releaseDroppedGraph("DEFAULT", "g");
        }, e -> Assert.assertContains("has no unfinished drop", e.getMessage()));

        // Not while a drop on this Server still clears the stores
        schemaMeta.commitCreate("DEFAULT", "g", 2L);
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        Mockito.when(graph.schemaIncarnation()).thenReturn(2L);
        GraphManager dropping = newManager(meta(schemaMeta), graph);
        AtomicReference<Throwable> release = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            try {
                dropping.releaseDroppedGraph("DEFAULT", "g");
            } catch (Throwable e) {
                release.set(e);
            }
            return null;
        }).when(graph).clearBackendForDrop();
        dropping.dropGraph("DEFAULT", "g", true);
        Assert.assertContains("is being dropped by this server", release.get().getMessage());
    }

    /**
     * A drop whose clear failed after its commit goes on when it is sent again: the
     * incarnation is dropped already, and the stores are cleared then
     */
    @Test
    public void testDropGoesOnAfterAFailedClear() throws Exception {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager schemaMeta = liveGraph(pd);
        MetaManager meta = Mockito.mock(MetaManager.class);
        Mockito.when(meta.schemaMetaManager()).thenReturn(schemaMeta);
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        Mockito.when(graph.schemaIncarnation()).thenReturn(1L);
        Mockito.doThrow(new HugeException("Store unavailable")).doNothing()
               .when(graph).clearBackendForDrop();
        GraphManager manager = newManager(meta, graph);

        Assert.assertThrows(HugeException.class, () -> {
            manager.dropGraph("DEFAULT", "g", true);
        }, e -> Assert.assertContains("Store unavailable", e.getMessage()));
        Assert.assertEquals("", pd.get(CONFIG));
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", pd.get(RECORD));

        manager.dropGraph("DEFAULT", "g", true);
        Mockito.verify(graph, Mockito.times(2)).clearBackendForDrop();
        Mockito.verify(graph, Mockito.atLeastOnce()).close();
        Assert.assertEquals("", pd.get(DROPPING));
    }

    /**
     * PD applied the DROP commit, but every response was lost and the commit failed on the
     * Server. The drop sent again finds the incarnation dropped with the drop marker of
     * this Server, which it chose before the commit, and goes on.
     */
    @Test
    public void testDropGoesOnAfterACommitWithoutAResponse() throws Exception {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager schemaMeta = liveGraph(pd);
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        Mockito.when(graph.schemaIncarnation()).thenReturn(1L);
        GraphManager manager = newManager(meta(schemaMeta), graph);

        pd.loseNextCommit();
        Assert.assertThrows(HugeException.class, () -> {
            manager.dropGraph("DEFAULT", "g", true);
        }, e -> Assert.assertContains("Failed to commit a txn", e.getMessage()));
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", pd.get(RECORD));
        Assert.assertEquals("", pd.get(CONFIG));
        Mockito.verify(graph, Mockito.never()).clearBackendForDrop();

        manager.dropGraph("DEFAULT", "g", true);
        Mockito.verify(graph).clearBackendForDrop();
        Assert.assertEquals("", pd.get(DROPPING));
        Assert.assertEquals(2L, schemaMeta.createGraph("DEFAULT", "g", configs()));
    }

    /**
     * The DROP commit is sent again when its response is lost; PD answers with its first
     * outcome and the drop goes on
     */
    @Test
    public void testDropSentAgainAfterALostResponse() throws Exception {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager schemaMeta = liveGraph(pd);
        MetaManager meta = Mockito.mock(MetaManager.class);
        Mockito.when(meta.schemaMetaManager()).thenReturn(schemaMeta);
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        Mockito.when(graph.schemaIncarnation()).thenReturn(1L);
        GraphManager manager = newManager(meta, graph);

        pd.resendNextCommit();
        manager.dropGraph("DEFAULT", "g", true);
        Mockito.verify(graph).clearBackendForDrop();
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", pd.get(RECORD));
        Assert.assertEquals("", pd.get(CONFIG));
    }

    /**
     * An update of the nickname or the read mode that read the graph config before a drop
     * writes it back after the DROP commit deleted it. The name then can't be created, and
     * the graph opens from that config with the dropped incarnation. A drop of it removes
     * the config, clears no stores (the drop that committed did), and frees the name.
     */
    @Test
    public void testDropRemovesAConfigWrittenBackAfterTheDrop() throws Exception {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager schemaMeta = liveGraph(pd);
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        Mockito.when(graph.schemaIncarnation()).thenReturn(1L);
        newManager(meta(schemaMeta), graph).dropGraph("DEFAULT", "g", true);
        Assert.assertEquals("", pd.get(DROPPING));
        pd.put(CONFIG, "{\"store\":\"g\",\"nickname\":\"late\"}");
        Assert.assertEquals(0L, schemaMeta.createGraph("DEFAULT", "g", configs()));

        HugeGraph reopened = Mockito.mock(HugeGraph.class);
        Mockito.when(reopened.schemaIncarnation()).thenReturn(1L);
        newManager(meta(schemaMeta), reopened).dropGraph("DEFAULT", "g", true);
        Assert.assertEquals("", pd.get(CONFIG));
        Mockito.verify(reopened, Mockito.never()).clearBackendForDrop();
        Mockito.verify(reopened, Mockito.atLeastOnce()).close();
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", pd.get(RECORD));
        Assert.assertEquals(2L, schemaMeta.createGraph("DEFAULT", "g", configs()));
    }

    /**
     * A graph that is not on HStore has no record and incarnation 0: its drop removes the
     * config as before and sends no commit, which would otherwise drop the record of an HStore
     * graph of the same name without checking its incarnation
     */
    @Test
    public void testDropOfANonHstoreGraphLeavesTheRecords() throws Exception {
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        SchemaMetaManager schemaMeta = Mockito.mock(SchemaMetaManager.class);
        MetaManager meta = Mockito.mock(MetaManager.class);
        Mockito.when(meta.schemaMetaManager()).thenReturn(schemaMeta);
        GraphManager manager = newManager(meta, graph);

        manager.dropGraph("DEFAULT", "g", true);
        Mockito.verify(meta).removeGraphConfig("DEFAULT", "g");
        Mockito.verify(graph).clearBackendForDrop();
        Mockito.verifyNoInteractions(schemaMeta);
    }

    /**
     * The config and the stores of a graph are shared by its incarnations: a Server whose
     * instance is of incarnation 1 removes nothing once the graph was recreated as
     * incarnation 2, since PD refuses its DROP commit and nothing comes before it
     */
    @Test
    public void testStaleInstanceRemovesNothingOfARecreatedGraph() throws Exception {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager schemaMeta = liveGraph(pd);
        HugeGraph stale = Mockito.mock(HugeGraph.class);
        Mockito.when(stale.schemaIncarnation()).thenReturn(1L);
        // Another Server dropped and recreated the graph, which wrote its schema
        schemaMeta.dropGraph("DEFAULT", "g", 1L, false);
        Assert.assertEquals(2L, schemaMeta.createGraph("DEFAULT", "g", configs()));
        pd.put(SCHEMA + "/PROPERTY_KEY/ID/1", "{}");
        MetaManager meta = Mockito.mock(MetaManager.class);
        Mockito.when(meta.schemaMetaManager()).thenReturn(schemaMeta);
        GraphManager manager = newManager(meta, stale);

        Assert.assertThrows(HugeException.class, () -> {
            manager.dropGraph("DEFAULT", "g", true);
        }, e -> Assert.assertContains("dropped or recreated", e.getMessage()));

        Mockito.verify(stale, Mockito.never()).clearBackendForDrop();
        Mockito.verify(meta, Mockito.never()).removeGraphConfig(Mockito.any(), Mockito.any());
        Mockito.verify(meta, Mockito.never()).notifyGraphRemove(Mockito.any(), Mockito.any());
        Assert.assertContains("\"inc\":2,\"state\":\"CREATING\"", pd.get(RECORD));
        Assert.assertEquals("{}", pd.get(SCHEMA + "/PROPERTY_KEY/ID/1"));
        Assert.assertContains("\"store\":\"g\"", pd.get(CONFIG));
    }

    /**
     * A create whose Server died leaves the graph CREATING, with its config and some schema.
     * A drop of the graph by name on a Server without an instance of it drops that
     * incarnation in one commit, and the name can be created again.
     */
    @Test
    public void testDropReleasesAGraphLeftCreating() throws Exception {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager creator = new SchemaMetaManager(pd.driver(), "hg", null);
        Assert.assertEquals(1L, creator.createGraph("DEFAULT", "g", configs()));
        pd.put(SCHEMA + "/PROPERTY_KEY/ID/1", "{}");
        // The creating Server is gone; this one never opened the graph
        SchemaMetaManager schemaMeta = new SchemaMetaManager(pd.driver(), "hg", null);
        Assert.assertEquals(0L, schemaMeta.createGraph("DEFAULT", "g", configs()));
        MetaManager meta = Mockito.mock(MetaManager.class);
        Mockito.when(meta.schemaMetaManager()).thenReturn(schemaMeta);
        GraphManager manager = newManager(meta, null);

        manager.dropGraph("DEFAULT", "g", true);
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", pd.get(RECORD));
        Assert.assertEquals("", pd.get(CONFIG));
        Assert.assertEquals("", pd.get(SCHEMA + "/PROPERTY_KEY/ID/1"));
        Mockito.verify(meta).notifyGraphRemove("DEFAULT", "g");
        Assert.assertEquals(2L, schemaMeta.createGraph("DEFAULT", "g", configs()));

        // A LIVE graph without an instance here is not dropped that way
        schemaMeta.commitCreate("DEFAULT", "g", 2L);
        Mockito.when(meta.graphConfigs("DEFAULT")).thenReturn(Collections.emptyMap());
        Assert.assertThrows(IllegalArgumentException.class, () -> {
            manager.dropGraph("DEFAULT", "g", true);
        });
        Assert.assertContains("\"inc\":2,\"state\":\"LIVE\"", pd.get(RECORD));
    }

    private static SchemaMetaManager liveGraph(FakeSchemaSyncPd pd) {
        SchemaMetaManager schemaMeta = new SchemaMetaManager(pd.driver(), "hg", null);
        Assert.assertEquals(1L, schemaMeta.createGraph("DEFAULT", "g", configs()));
        schemaMeta.commitCreate("DEFAULT", "g", 1L);
        return schemaMeta;
    }

    private static MetaManager meta(SchemaMetaManager schemaMeta) {
        MetaManager meta = Mockito.mock(MetaManager.class);
        Mockito.when(meta.schemaMetaManager()).thenReturn(schemaMeta);
        return meta;
    }

    private static Map<String, Object> configs() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("store", "g");
        return configs;
    }

    private static GraphManager newManager(MetaManager meta, HugeGraph graph)
                                           throws Exception {
        GraphManager manager = allocateGraphManager();
        Whitebox.setInternalState(manager, "PDExist", true);
        Whitebox.setInternalState(manager, "metaManager", meta);
        Whitebox.setInternalState(manager, "localGraphs", Collections.emptySet());
        Whitebox.setInternalState(manager, "removingGraphs", new HashSet<String>());
        Whitebox.setInternalState(manager, "droppingGraphs",
                                  ConcurrentHashMap.<String>newKeySet());
        Whitebox.setInternalState(manager, "eventHub", new EventHub("drop-test"));
        Map<String, GraphSpace> spaces = new ConcurrentHashMap<>();
        spaces.put("DEFAULT", new GraphSpace("DEFAULT"));
        Whitebox.setInternalState(manager, "graphSpaces", spaces);
        Map<String, Graph> graphs = new ConcurrentHashMap<>();
        if (graph != null) {
            graphs.put("DEFAULT-g", graph);
        }
        Whitebox.setInternalState(manager, "graphs", graphs);
        return manager;
    }

    private static GraphManager allocateGraphManager() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        return (GraphManager) unsafe.allocateInstance(GraphManager.class);
    }
}
