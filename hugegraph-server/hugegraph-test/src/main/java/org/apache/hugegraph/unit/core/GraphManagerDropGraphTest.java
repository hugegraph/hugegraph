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
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hugegraph.HugeException;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.core.GraphManager;
import org.apache.hugegraph.event.EventHub;
import org.apache.hugegraph.meta.MetaManager;
import org.apache.hugegraph.meta.managers.FakeSchemaSyncPd;
import org.apache.hugegraph.meta.managers.SchemaMetaManager;
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

    /**
     * A Server drops only the incarnation its graph instance belongs to, so it can't drop
     * the graph another Server recreated meanwhile
     */
    @Test
    public void testDropSendsTheInstanceIncarnation() throws Exception {
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        Mockito.when(graph.schemaIncarnation()).thenReturn(2L);
        SchemaMetaManager schemaMeta = Mockito.mock(SchemaMetaManager.class);
        Mockito.when(schemaMeta.removeGraphConfig("DEFAULT", "g", 2L, null))
               .thenReturn("owner");
        MetaManager meta = Mockito.mock(MetaManager.class);
        Mockito.when(meta.schemaMetaManager()).thenReturn(schemaMeta);
        GraphManager manager = newManager(meta, graph);

        manager.dropGraph("DEFAULT", "g", true);

        InOrder order = Mockito.inOrder(graph, meta, schemaMeta);
        order.verify(schemaMeta).removeGraphConfig("DEFAULT", "g", 2L, null);
        order.verify(meta).notifyGraphRemove("DEFAULT", "g");
        order.verify(graph).clearBackendForDrop();
        order.verify(schemaMeta).dropGraph("DEFAULT", "g", 2L);
        // The config is removed by the checked commit alone
        Mockito.verify(meta, Mockito.never()).removeGraphConfig(Mockito.any(), Mockito.any());
        // The schema in PD is deleted by the DROP commit alone, not by a clear commit first
        Mockito.verify(graph, Mockito.never()).clearBackend();
    }

    /**
     * Two Servers drop the same graph at once. Only one may clear the stores: the other one
     * could otherwise still be clearing them after the first one finished and a new
     * incarnation of the graph was created and wrote data
     */
    @Test
    public void testOnlyOneOfTwoDropsClearsTheStores() throws Exception {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager schemaMeta = new SchemaMetaManager(pd.driver(), "hg", null);
        Assert.assertEquals(1L, schemaMeta.createGraph("DEFAULT", "g").getIncarnation());
        pd.put(CONFIG, "{\"store\":\"g\"}");
        MetaManager meta = Mockito.mock(MetaManager.class);
        Mockito.when(meta.schemaMetaManager()).thenReturn(schemaMeta);
        HugeGraph graphA = Mockito.mock(HugeGraph.class);
        Mockito.when(graphA.schemaIncarnation()).thenReturn(1L);
        HugeGraph graphB = Mockito.mock(HugeGraph.class);
        Mockito.when(graphB.schemaIncarnation()).thenReturn(1L);
        GraphManager serverA = newManager(meta, graphA);
        GraphManager serverB = newManager(meta, graphB);

        // Server B drops the graph while server A clears its stores
        AtomicReference<Throwable> dropOfB = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            try {
                serverB.dropGraph("DEFAULT", "g", true);
            } catch (Throwable e) {
                dropOfB.set(e);
            }
            return null;
        }).when(graphA).clearBackendForDrop();
        serverA.dropGraph("DEFAULT", "g", true);

        Assert.assertNotNull(dropOfB.get());
        Assert.assertContains("has no config", dropOfB.get().getMessage());
        Mockito.verify(graphB, Mockito.never()).clearBackendForDrop();
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", pd.get(RECORD));
        Assert.assertEquals("", pd.get(CONFIG));
    }

    /**
     * A drop that removed the config and then failed, here while clearing the stores, goes on
     * when it is retried on the same Server, although the config is gone
     */
    @Test
    public void testDropGoesOnAfterAFailedClear() throws Exception {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager schemaMeta = new SchemaMetaManager(pd.driver(), "hg", null);
        schemaMeta.createGraph("DEFAULT", "g");
        pd.put(CONFIG, "{\"store\":\"g\"}");
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
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", pd.get(RECORD));

        manager.dropGraph("DEFAULT", "g", true);
        Mockito.verify(graph, Mockito.times(2)).clearBackendForDrop();
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", pd.get(RECORD));

        // Another Server, which didn't start that drop, can't take it over
        FakeSchemaSyncPd pd2 = new FakeSchemaSyncPd();
        SchemaMetaManager schemaMeta2 = new SchemaMetaManager(pd2.driver(), "hg", null);
        schemaMeta2.createGraph("DEFAULT", "g");
        pd2.put(CONFIG, "{\"store\":\"g\"}");
        String owner = schemaMeta2.removeGraphConfig("DEFAULT", "g", 1L, null);
        Assert.assertNotNull(owner);
        Assert.assertThrows(HugeException.class, () -> {
            schemaMeta2.removeGraphConfig("DEFAULT", "g", 1L, "another-owner");
        }, e -> Assert.assertContains("has no config", e.getMessage()));
        Assert.assertEquals(owner, schemaMeta2.removeGraphConfig("DEFAULT", "g", 1L, owner));
    }

    /**
     * The commit that removes the config is sent again when its response is lost; the drop
     * that sent it goes on
     */
    @Test
    public void testDropSentAgainAfterALostResponse() throws Exception {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager schemaMeta = new SchemaMetaManager(pd.driver(), "hg", null);
        schemaMeta.createGraph("DEFAULT", "g");
        pd.put(CONFIG, "{\"store\":\"g\"}");
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
     * instance is of incarnation 1 must not remove them once the graph was recreated as
     * incarnation 2, though PD would reject its DROP commit afterwards
     */
    @Test
    public void testStaleInstanceRemovesNothingOfARecreatedGraph() throws Exception {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager schemaMeta = new SchemaMetaManager(pd.driver(), "hg", null);
        Assert.assertEquals(1L, schemaMeta.createGraph("DEFAULT", "g").getIncarnation());
        HugeGraph stale = Mockito.mock(HugeGraph.class);
        Mockito.when(stale.schemaIncarnation()).thenReturn(1L);
        // Another Server dropped and recreated the graph, which wrote its schema
        schemaMeta.dropGraph("DEFAULT", "g", 1L);
        Assert.assertEquals(2L, schemaMeta.createGraph("DEFAULT", "g").getIncarnation());
        pd.put(SCHEMA + "/PROPERTY_KEY/ID/1", "{}");
        pd.put(CONFIG, "{\"store\":\"g\"}");
        MetaManager meta = Mockito.mock(MetaManager.class);
        Mockito.when(meta.schemaMetaManager()).thenReturn(schemaMeta);
        GraphManager manager = newManager(meta, stale);

        Assert.assertThrows(HugeException.class, () -> {
            manager.dropGraph("DEFAULT", "g", true);
        }, e -> Assert.assertContains("dropped or recreated", e.getMessage()));

        Mockito.verify(stale, Mockito.never()).clearBackendForDrop();
        Mockito.verify(meta, Mockito.never()).removeGraphConfig(Mockito.any(), Mockito.any());
        Mockito.verify(meta, Mockito.never()).notifyGraphRemove(Mockito.any(), Mockito.any());
        Assert.assertContains("\"inc\":2,\"state\":\"LIVE\"", pd.get(RECORD));
        Assert.assertEquals("{}", pd.get(SCHEMA + "/PROPERTY_KEY/ID/1"));
        Assert.assertEquals("{\"store\":\"g\"}", pd.get(CONFIG));
    }

    /**
     * A graph dropped by another Server and not recreated can't be dropped again by a Server
     * whose instance is stale either
     */
    @Test
    public void testStaleInstanceRemovesNothingOfADroppedGraph() throws Exception {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager schemaMeta = new SchemaMetaManager(pd.driver(), "hg", null);
        schemaMeta.createGraph("DEFAULT", "g");
        schemaMeta.dropGraph("DEFAULT", "g", 1L);
        HugeGraph stale = Mockito.mock(HugeGraph.class);
        Mockito.when(stale.schemaIncarnation()).thenReturn(1L);
        MetaManager meta = Mockito.mock(MetaManager.class);
        Mockito.when(meta.schemaMetaManager()).thenReturn(schemaMeta);
        GraphManager manager = newManager(meta, stale);

        Assert.assertThrows(HugeException.class, () -> {
            manager.dropGraph("DEFAULT", "g", true);
        });
        Mockito.verify(stale, Mockito.never()).clearBackendForDrop();
        Mockito.verify(meta, Mockito.never()).removeGraphConfig(Mockito.any(), Mockito.any());
    }

    private static GraphManager newManager(MetaManager meta, HugeGraph graph)
                                           throws Exception {
        GraphManager manager = allocateGraphManager();
        Whitebox.setInternalState(manager, "PDExist", true);
        Whitebox.setInternalState(manager, "metaManager", meta);
        Whitebox.setInternalState(manager, "localGraphs", Collections.emptySet());
        Whitebox.setInternalState(manager, "removingGraphs", new HashSet<String>());
        Whitebox.setInternalState(manager, "droppingGraphs",
                                  new ConcurrentHashMap<String, String>());
        Whitebox.setInternalState(manager, "eventHub", new EventHub("drop-test"));
        Map<String, GraphSpace> spaces = new ConcurrentHashMap<>();
        spaces.put("DEFAULT", new GraphSpace("DEFAULT"));
        Whitebox.setInternalState(manager, "graphSpaces", spaces);
        Map<String, Graph> graphs = new ConcurrentHashMap<>();
        graphs.put("DEFAULT-g", graph);
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
