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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.hugegraph.HugeException;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.core.GraphManager;
import org.apache.hugegraph.event.EventHub;
import org.apache.hugegraph.exception.ExistedException;
import org.apache.hugegraph.meta.MetaManager;
import org.apache.hugegraph.meta.managers.FakeSchemaSyncPd;
import org.apache.hugegraph.meta.managers.SchemaMetaManager;
import org.apache.hugegraph.space.GraphSpace;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import sun.misc.Unsafe;

/**
 * The graph name is reserved in PD by a CREATE of the graph record before the graph is
 * created, so two Servers can't both create it
 */
public class GraphManagerCreateGraphTest {

    private static final String RECORD = "HUGEGRAPH/hg/SCHEMA_SYNC/DEFAULT/g";
    private static final String SCHEMA = "HUGEGRAPH/hg/GRAPHSPACE/DEFAULT/g/SCHEMA";

    private FakeSchemaSyncPd pd;
    private MetaManager meta;
    private Map<String, Map<String, Object>> graphConfigs;
    private GraphManager manager;

    @Before
    public void setup() throws Exception {
        this.pd = new FakeSchemaSyncPd();
        this.graphConfigs = new HashMap<>();
        SchemaMetaManager schemaMeta = new SchemaMetaManager(this.pd.driver(), "hg", null);
        this.meta = Mockito.mock(MetaManager.class);
        Mockito.when(this.meta.schemaMetaManager()).thenReturn(schemaMeta);
        Mockito.when(this.meta.graphConfigs("DEFAULT")).thenReturn(this.graphConfigs);
        Mockito.when(this.meta.graphSpace("DEFAULT")).thenReturn(new GraphSpace("DEFAULT"));

        this.manager = allocateGraphManager();
        Whitebox.setInternalState(this.manager, "PDExist", true);
        Whitebox.setInternalState(this.manager, "metaManager", this.meta);
        Whitebox.setInternalState(this.manager, "serviceGraphSpace", "DEFAULT");
        Whitebox.setInternalState(this.manager, "pdPeers", "127.0.0.1:8686");
        Whitebox.setInternalState(this.manager, "config",
                                  new HugeConfig(new PropertiesConfiguration()));
        Whitebox.setInternalState(this.manager, "eventHub", new EventHub("create-test"));
        Whitebox.setInternalState(this.manager, "graphs", new ConcurrentHashMap<String, Graph>());
        Map<String, GraphSpace> spaces = new ConcurrentHashMap<>();
        spaces.put("DEFAULT", new GraphSpace("DEFAULT"));
        Whitebox.setInternalState(this.manager, "graphSpaces", spaces);
    }

    @Test
    public void testReserveRejectsALiveGraph() {
        Assert.assertEquals(1L, this.reserve());
        // Another Server creating the same graph, which has its config by now
        this.graphConfigs.put("DEFAULT-g", new HashMap<>());
        Assert.assertThrows(ExistedException.class, this::reserve);
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", this.pd.get(RECORD));
    }

    /**
     * A LIVE record without a graph config may be a create still opening its graph on another
     * Server, which publishes its config only after that, so a second create must not drop it
     */
    @Test
    public void testReserveKeepsAReservationWithoutConfig() {
        Assert.assertEquals(1L, this.reserve());
        this.pd.put(SCHEMA + "/PROPERTY_KEY/ID/1", "{}");

        Assert.assertThrows(ExistedException.class, this::reserve);
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", this.pd.get(RECORD));
        Assert.assertEquals("{}", this.pd.get(SCHEMA + "/PROPERTY_KEY/ID/1"));
    }

    /**
     * A record left LIVE by a create or drop that never finished is released by dropping the
     * graph by name, after which the name can be created again
     */
    @Test
    public void testDropReleasesAnUnfinishedCreate() {
        Assert.assertEquals(1L, this.reserve());
        this.pd.put(SCHEMA + "/PROPERTY_KEY/ID/1", "{}");

        this.manager.dropGraph("DEFAULT", "g", true);
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", this.pd.get(RECORD));
        Assert.assertEquals("", this.pd.get(SCHEMA + "/PROPERTY_KEY/ID/1"));
        Assert.assertEquals(2L, this.reserve());
    }

    @Test
    public void testDropOfAnUnknownGraphDropsNoRecord() {
        Assert.assertThrows(IllegalArgumentException.class, () -> {
            this.manager.dropGraph("DEFAULT", "g", true);
        });
        Assert.assertEquals("", this.pd.get(RECORD));

        // A DROPPED record is left as it is
        Assert.assertEquals(1L, this.reserve());
        this.manager.dropGraph("DEFAULT", "g", true);
        String dropped = this.pd.get(RECORD);
        Assert.assertThrows(IllegalArgumentException.class, () -> {
            this.manager.dropGraph("DEFAULT", "g", true);
        });
        Assert.assertEquals(dropped, this.pd.get(RECORD));
    }

    /**
     * A create publishes its config only for the incarnation it reserved: not after a drop of
     * the name released its reservation while the graph opened, and not when the graph opened
     * with the incarnation of a later create
     */
    @Test
    public void testCreateChecksItsReservationBeforePublishing() {
        Assert.assertEquals(1L, this.reserve());
        HugeGraph opened = Mockito.mock(HugeGraph.class);
        Mockito.when(opened.schemaIncarnation()).thenReturn(1L);
        this.checkReserved(1L, opened);

        // A drop by name released the reservation
        this.manager.dropGraph("DEFAULT", "g", true);
        Assert.assertThrows(HugeException.class, () -> this.checkReserved(1L, opened));

        // And another create took the name before the graph opened
        Assert.assertEquals(2L, this.reserve());
        HugeGraph openedLater = Mockito.mock(HugeGraph.class);
        Mockito.when(openedLater.schemaIncarnation()).thenReturn(2L);
        Assert.assertThrows(HugeException.class, () -> this.checkReserved(1L, openedLater));
    }

    /**
     * A create whose graph fails to open gives the reserved name back, rather than leaving a
     * LIVE record that blocks the name
     */
    @Test
    public void testFailedCreateDropsTheReservation() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("backend", "not-a-backend");
        configs.put("store", "g");
        Assert.assertThrows(Exception.class, () -> {
            this.manager.createGraph("DEFAULT", "g", "admin", configs, true);
        });
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", this.pd.get(RECORD));
        Mockito.verify(this.meta, Mockito.never())
               .addGraphConfig(Mockito.any(), Mockito.any(), Mockito.any());

        // The name can be created again
        Assert.assertEquals(2L, this.reserve());
    }

    private long reserve() {
        Long incarnation = Whitebox.invoke(GraphManager.class, "reserveGraph", this.manager,
                                           "DEFAULT", "g");
        return incarnation;
    }

    private void checkReserved(long incarnation, HugeGraph graph) {
        Whitebox.invoke(GraphManager.class,
                        new Class<?>[]{String.class, String.class, long.class, HugeGraph.class},
                        "checkReserved", this.manager, "DEFAULT", "g", incarnation, graph);
    }

    private static GraphManager allocateGraphManager() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        return (GraphManager) unsafe.allocateInstance(GraphManager.class);
    }
}
