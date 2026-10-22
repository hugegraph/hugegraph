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

package org.apache.hugegraph.unit.core;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.core.GraphManager;
import org.apache.hugegraph.event.EventHub;
import org.apache.hugegraph.exception.HugeException;
import org.apache.hugegraph.meta.MetaManager;
import org.apache.hugegraph.meta.managers.FakeSchemaSyncPd;
import org.apache.hugegraph.meta.managers.SchemaMetaManager;
import org.apache.hugegraph.pd.grpc.kv.TxnResponse;
import org.apache.hugegraph.space.GraphSpace;
import org.apache.hugegraph.task.TaskScheduler;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

public class GraphManagerDropGraphTest {

    private static final String RECORD = "HUGEGRAPH/hg/SCHEMA_SYNC/DEFAULT/g";
    private static final String SCHEMA = "HUGEGRAPH/hg/GRAPHSPACE/DEFAULT/g/SCHEMA";
    private static final String CONFIG = "HUGEGRAPH/hg/GRAPHSPACE/DEFAULT/GRAPH_CONF/g";

    /**
     * A drop is one commit with the incarnation of the instance, which deletes the config
     * and the schema in PD, and only after it the stores are cleared: nothing destructive
     * runs before PD accepted the drop
     */
    @Test
    public void testDropCommitsBeforeItClears() {
        HugeGraph graph = mockGraph(2L);
        SchemaMetaManager schemaMeta = Mockito.mock(SchemaMetaManager.class);
        Mockito.when(schemaMeta.dropGraph("DEFAULT", "g", 2L)).thenReturn(
                TxnResponse.newBuilder().setSucceeded(true).build());
        MetaManager meta = meta(schemaMeta);
        GraphManager manager = newManager(meta, graph);
        try {
            manager.dropGraph("DEFAULT", "g", true);
        } finally {
            manager.close();
        }

        InOrder order = Mockito.inOrder(graph, meta, schemaMeta);
        order.verify(schemaMeta).dropGraph("DEFAULT", "g", 2L);
        order.verify(meta).notifyGraphRemove("DEFAULT", "g");
        order.verify(graph).clearBackendForDrop();
        // The config and the schema in PD go with the DROP commit alone
        Mockito.verify(meta, Mockito.never()).removeGraphConfig(Mockito.any(), Mockito.any());
        Mockito.verify(graph, Mockito.never()).clearBackend();
    }

    /**
     * A drop whose clear failed after its commit goes on when it is sent again: the
     * incarnation is dropped already, nothing recreated the graph, and the stores are
     * cleared then
     */
    @Test
    public void testDropGoesOnAfterAFailedClear() throws Exception {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager schemaMeta = liveGraph(pd);
        HugeGraph graph = mockGraph(1L);
        Mockito.doThrow(new HugeException("Store unavailable")).doNothing()
               .when(graph).clearBackendForDrop();
        GraphManager manager = newManager(meta(schemaMeta), graph);
        try {
            Assert.assertThrows(HugeException.class, () -> {
                manager.dropGraph("DEFAULT", "g", true);
            }, e -> Assert.assertContains("Store unavailable", e.getMessage()));
            Assert.assertEquals("", pd.get(CONFIG));
            Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", pd.get(RECORD));

            manager.dropGraph("DEFAULT", "g", true);
        } finally {
            manager.close();
        }
        Mockito.verify(graph, Mockito.times(2)).clearBackendForDrop();
        Mockito.verify(graph, Mockito.atLeastOnce()).close();
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", pd.get(RECORD));
    }

    /**
     * A graph that is not on HStore has no record and incarnation 0: its drop removes the
     * config as before and sends no commit
     */
    @Test
    public void testDropOfANonHstoreGraphLeavesTheRecords() {
        HugeGraph graph = mockGraph(0L);
        SchemaMetaManager schemaMeta = Mockito.mock(SchemaMetaManager.class);
        MetaManager meta = meta(schemaMeta);
        GraphManager manager = newManager(meta, graph);
        try {
            manager.dropGraph("DEFAULT", "g", true);
        } finally {
            manager.close();
        }
        Mockito.verify(meta).removeGraphConfig("DEFAULT", "g");
        Mockito.verify(graph).clearBackend();
        Mockito.verify(graph, Mockito.never()).clearBackendForDrop();
        Mockito.verifyNoInteractions(schemaMeta);
    }

    /**
     * The config and the stores of a graph are shared by its incarnations: a Server whose
     * instance is of incarnation 1 removes nothing once the graph was recreated as
     * incarnation 2, since PD refuses its DROP commit and nothing comes before it
     */
    @Test
    public void testStaleInstanceRemovesNothingOfARecreatedGraph() {
        FakeSchemaSyncPd pd = new FakeSchemaSyncPd();
        SchemaMetaManager schemaMeta = liveGraph(pd);
        // Another Server dropped and recreated the graph, which wrote its schema
        Assert.assertTrue(schemaMeta.dropGraph("DEFAULT", "g", 1L).getSucceeded());
        Assert.assertEquals(2L, schemaMeta.createGraph("DEFAULT", "g"));
        pd.put(CONFIG, "{\"store\":\"g\"}");
        pd.put(SCHEMA + "/PROPERTY_KEY/ID/1", "{}");
        HugeGraph stale = mockGraph(1L);
        MetaManager meta = meta(schemaMeta);
        GraphManager manager = newManager(meta, stale);
        try {
            // Wrapped like any failure before the clear, with the reason as its cause
            Assert.assertThrows(HugeException.class, () -> {
                manager.dropGraph("DEFAULT", "g", true);
            }, e -> Assert.assertContains("dropped or recreated", e.getCause().getMessage()));
        } finally {
            manager.close();
        }

        Mockito.verify(stale, Mockito.never()).clearBackendForDrop();
        Mockito.verify(meta, Mockito.never()).removeGraphConfig(Mockito.any(), Mockito.any());
        Mockito.verify(meta, Mockito.never()).notifyGraphRemove(Mockito.any(), Mockito.any());
        Assert.assertContains("\"inc\":2,\"state\":\"LIVE\"", pd.get(RECORD));
        Assert.assertEquals("{}", pd.get(SCHEMA + "/PROPERTY_KEY/ID/1"));
        Assert.assertEquals("{\"store\":\"g\"}", pd.get(CONFIG));
    }

    /**
     * A graph created and published on the fake PD: incarnation 1, config in place
     */
    private static SchemaMetaManager liveGraph(FakeSchemaSyncPd pd) {
        SchemaMetaManager schemaMeta = new SchemaMetaManager(pd.driver(), "hg", null);
        Assert.assertEquals(1L, schemaMeta.createGraph("DEFAULT", "g"));
        pd.put(CONFIG, "{\"store\":\"g\"}");
        return schemaMeta;
    }

    private static MetaManager meta(SchemaMetaManager schemaMeta) {
        MetaManager meta = Mockito.mock(MetaManager.class);
        Mockito.when(meta.schemaMetaManager()).thenReturn(schemaMeta);
        return meta;
    }

    private static HugeGraph mockGraph(long incarnation) {
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        // No tasks to drain: the drop closes task admission and goes on
        TaskScheduler scheduler = Mockito.mock(TaskScheduler.class);
        Mockito.when(scheduler.close()).thenReturn(true);
        Mockito.when(graph.taskScheduler()).thenReturn(scheduler);
        Mockito.when(graph.spaceGraphName()).thenReturn("DEFAULT-g");
        Mockito.when(graph.schemaIncarnation()).thenReturn(incarnation);
        return graph;
    }

    /**
     * A mock hub: the executor of EventHub is static, and a test that ran earlier in the
     * suite may have shut it down
     */
    private static GraphManager newManager(MetaManager meta, HugeGraph graph) {
        GraphManager manager = new GraphManager(new HugeConfig(new PropertiesConfiguration()),
                                                Mockito.mock(EventHub.class));
        Whitebox.setInternalState(manager, "PDExist", true);
        Whitebox.setInternalState(manager, "metaManager", meta);
        Whitebox.setInternalState(manager, "localGraphs", Collections.emptySet());
        Map<String, GraphSpace> spaces = new ConcurrentHashMap<>();
        spaces.put("DEFAULT", new GraphSpace("DEFAULT"));
        Whitebox.setInternalState(manager, "graphSpaces", spaces);
        Map<String, Graph> graphs = Whitebox.getInternalState(manager, "graphs");
        graphs.put("DEFAULT-g", graph);
        return manager;
    }
}
