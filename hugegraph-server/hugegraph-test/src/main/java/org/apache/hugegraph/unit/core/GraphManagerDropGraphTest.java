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
import org.apache.hugegraph.meta.managers.SchemaMetaManager;
import org.apache.hugegraph.space.GraphSpace;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import sun.misc.Unsafe;

public class GraphManagerDropGraphTest {

    /**
     * A Server drops only the incarnation its graph instance belongs to, so it can't drop
     * the graph another Server recreated meanwhile
     */
    @Test
    public void testDropSendsTheInstanceIncarnation() throws Exception {
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        Mockito.when(graph.schemaIncarnation()).thenReturn(2L);
        SchemaMetaManager schemaMeta = Mockito.mock(SchemaMetaManager.class);
        MetaManager meta = Mockito.mock(MetaManager.class);
        Mockito.when(meta.schemaMetaManager()).thenReturn(schemaMeta);

        GraphManager manager = allocateGraphManager();
        Whitebox.setInternalState(manager, "PDExist", true);
        Whitebox.setInternalState(manager, "metaManager", meta);
        Whitebox.setInternalState(manager, "localGraphs", Collections.emptySet());
        Whitebox.setInternalState(manager, "removingGraphs", new HashSet<String>());
        Whitebox.setInternalState(manager, "eventHub", new EventHub("drop-test"));
        Map<String, GraphSpace> spaces = new ConcurrentHashMap<>();
        spaces.put("DEFAULT", new GraphSpace("DEFAULT"));
        Whitebox.setInternalState(manager, "graphSpaces", spaces);
        Map<String, Graph> graphs = new ConcurrentHashMap<>();
        graphs.put("DEFAULT-g", graph);
        Whitebox.setInternalState(manager, "graphs", graphs);

        manager.dropGraph("DEFAULT", "g", true);

        InOrder order = Mockito.inOrder(graph, schemaMeta);
        order.verify(graph).clearBackend();
        order.verify(schemaMeta).dropGraph("DEFAULT", "g", 2L);
    }

    private static GraphManager allocateGraphManager() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        return (GraphManager) unsafe.allocateInstance(GraphManager.class);
    }
}
