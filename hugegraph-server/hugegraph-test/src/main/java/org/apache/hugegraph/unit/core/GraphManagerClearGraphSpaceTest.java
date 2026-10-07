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

import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.core.GraphManager;
import org.apache.hugegraph.event.EventHub;
import org.apache.hugegraph.meta.MetaManager;
import org.apache.hugegraph.space.GraphSpace;
import org.apache.hugegraph.space.Service;
import org.apache.hugegraph.task.TaskScheduler;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.junit.Test;
import org.mockito.Mockito;

import sun.misc.Unsafe;

public class GraphManagerClearGraphSpaceTest {

    /**
     * Graph and service keys are "{graphspace}-{name}", and a graph space
     * name may be a prefix of another one, so clearing "gs" must leave the
     * graphs and services of "gs2" and "gs_x" alone
     */
    @Test
    public void testClearKeepsGraphsOfSpacesNamedLikeIt() throws Exception {
        HugeGraph cleared = mockGraph();
        HugeGraph kept = mockGraph();
        HugeGraph keptUnderscore = mockGraph();
        MetaManager meta = Mockito.mock(MetaManager.class);

        GraphManager manager = allocateGraphManager();
        Whitebox.setInternalState(manager, "PDExist", true);
        Whitebox.setInternalState(manager, "metaManager", meta);
        Whitebox.setInternalState(manager, "localGraphs", Collections.emptySet());
        Whitebox.setInternalState(manager, "removingGraphs", new HashSet<String>());
        Whitebox.setInternalState(manager, "eventHub", new EventHub("clear-test"));
        Map<String, GraphSpace> spaces = new ConcurrentHashMap<>();
        for (String space : new String[]{"gs", "gs2", "gs_x"}) {
            spaces.put(space, new GraphSpace(space));
        }
        Whitebox.setInternalState(manager, "graphSpaces", spaces);
        Map<String, Graph> graphs = new ConcurrentHashMap<>();
        graphs.put("gs-g", cleared);
        graphs.put("gs2-g", kept);
        graphs.put("gs_x-g", keptUnderscore);
        Whitebox.setInternalState(manager, "graphs", graphs);
        Map<String, Service> services = new ConcurrentHashMap<>();
        services.put("gs-s", Mockito.mock(Service.class));
        services.put("gs2-s", Mockito.mock(Service.class));
        services.put("gs_x-s", Mockito.mock(Service.class));
        Whitebox.setInternalState(manager, "services", services);

        manager.clearGraphSpace("gs");

        Mockito.verify(cleared).clearBackend();
        Mockito.verify(kept, Mockito.never()).clearBackend();
        Mockito.verify(keptUnderscore, Mockito.never()).clearBackend();
        Assert.assertFalse(graphs.containsKey("gs-g"));
        Assert.assertTrue(graphs.containsKey("gs2-g"));
        Assert.assertTrue(graphs.containsKey("gs_x-g"));

        Mockito.verify(meta).service("gs", "s");
        Mockito.verify(meta, Mockito.never()).service("gs2", "s");
        Mockito.verify(meta, Mockito.never()).service("gs_x", "s");
    }

    private static HugeGraph mockGraph() {
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        Mockito.when(graph.taskScheduler())
               .thenReturn(Mockito.mock(TaskScheduler.class));
        return graph;
    }

    private static GraphManager allocateGraphManager() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        return (GraphManager) unsafe.allocateInstance(GraphManager.class);
    }
}
