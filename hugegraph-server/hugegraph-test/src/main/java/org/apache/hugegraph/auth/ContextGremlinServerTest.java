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

package org.apache.hugegraph.auth;

import javax.script.Bindings;
import javax.script.SimpleBindings;

import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.event.EventHub;
import org.apache.hugegraph.security.HugeGraphGremlinLangScriptEngine;
import org.apache.hugegraph.security.HugeGraphGremlinLangScriptEngineFactory;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.hugegraph.util.Events;
import org.apache.tinkerpop.gremlin.groovy.engine.GremlinExecutor;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.util.DefaultGraphManager;
import org.apache.tinkerpop.gremlin.server.util.ServerGremlinExecutor;
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph;
import org.junit.Test;
import org.mockito.Mockito;

public class ContextGremlinServerTest {

    @Test
    public void testDropIgnoresStaleGraphAndRetiresCurrentSource() throws Exception {
        String name = "same-name";
        String sourceName = "__g_" + name;
        HugeGraph oldGraph = Mockito.mock(HugeGraph.class);
        HugeGraph newGraph = Mockito.mock(HugeGraph.class);
        Mockito.when(oldGraph.spaceGraphName()).thenReturn(name);
        Mockito.when(newGraph.spaceGraphName()).thenReturn(name);

        ContextGremlinServer server = Mockito.mock(ContextGremlinServer.class);
        ServerGremlinExecutor serverExecutor = Mockito.mock(ServerGremlinExecutor.class);
        GremlinExecutor executor = Mockito.mock(GremlinExecutor.class);
        DefaultGraphManager manager = new DefaultGraphManager(new Settings());
        Bindings bindings = new SimpleBindings();
        Whitebox.setInternalState(executor, "globalBindings", bindings);
        Mockito.when(server.getServerGremlinExecutor()).thenReturn(serverExecutor);
        Mockito.when(serverExecutor.getGraphManager()).thenReturn(manager);
        Mockito.when(serverExecutor.getGremlinExecutor()).thenReturn(executor);

        HugeGraphGremlinLangScriptEngine engine = (HugeGraphGremlinLangScriptEngine)
                new HugeGraphGremlinLangScriptEngineFactory().getScriptEngine();
        EventHub events = new EventHub("gremlin-graph-drop-test");
        Whitebox.setInternalState(server, "gremlinLangEngine", engine);
        Whitebox.setInternalState(server, "eventHub", events);
        GraphTraversalSource source = engine.add(EmptyGraph.instance().traversal());
        manager.putGraph(name, newGraph);
        manager.putTraversalSource(sourceName, source);
        bindings.put(name, newGraph);
        Whitebox.invoke(ContextGremlinServer.class, "listenChanges", server);

        try {
            assertNotificationSucceeded(events.notifySync(Events.GRAPH_DROP, oldGraph));
            Assert.assertSame(newGraph, manager.getGraph(name));
            Assert.assertSame(source, manager.getTraversalSource(sourceName));
            Assert.assertSame(newGraph, bindings.get(name));
            Assert.assertEquals(1, engine.traversalSourceCount());
            Mockito.verify(newGraph, Mockito.never()).close();

            assertNotificationSucceeded(events.notifySync(Events.GRAPH_DROP, newGraph));
            Assert.assertNull(manager.getGraph(name));
            Assert.assertNull(manager.getTraversalSource(sourceName));
            Assert.assertFalse(bindings.containsKey(name));
            Assert.assertEquals(0, engine.traversalSourceCount());
            Mockito.verify(newGraph).close();

            assertNotificationSucceeded(events.notifySync(Events.GRAPH_DROP, newGraph));
            Mockito.verify(newGraph).close();
            Mockito.verify(oldGraph, Mockito.never()).close();
        } finally {
            engine.clear();
            Whitebox.invoke(ContextGremlinServer.class, "unlistenChanges", server);
        }
    }

    private static void assertNotificationSucceeded(EventHub.NotifyResult result) {
        Assert.assertEquals(1, result.attempted());
        Assert.assertEquals(1, result.succeeded());
    }
}
