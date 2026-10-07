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
import org.apache.hugegraph.HugeFactory;
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
    private static final String CONFIG = "HUGEGRAPH/hg/GRAPHSPACE/DEFAULT/GRAPH_CONF/g";

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
        Whitebox.setInternalState(this.manager, "creatingGraphs",
                                  ConcurrentHashMap.<String>newKeySet());
        Whitebox.setInternalState(this.manager, "droppingGraphs",
                                  new ConcurrentHashMap<String, String>());
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

        // The second create runs before the first one publishes its config
        Assert.assertThrows(ExistedException.class, this::reserve);
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", this.pd.get(RECORD));
        Assert.assertEquals("{}", this.pd.get(SCHEMA + "/PROPERTY_KEY/ID/1"));

        // And the first create still publishes it
        this.publish(1L);
        Assert.assertContains("\"store\":\"g\"", this.pd.get(CONFIG));
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", this.pd.get(RECORD));
    }

    /**
     * A CREATE that PD applied but whose response was lost is sent again by the PD client;
     * the create must get its own reservation, not fail with an existing graph
     */
    @Test
    public void testReserveSentAgainAfterALostResponse() {
        this.pd.resendNextCommit();
        Assert.assertEquals(1L, this.reserve());
        this.publish(1L);
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", this.pd.get(RECORD));
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
        Assert.assertThrows(HugeException.class, () -> this.publish(1L),
                            e -> Assert.assertContains("GRAPH_DROPPED", e.getMessage()));
        Assert.assertEquals("", this.pd.get(CONFIG));

        // And another create took the name before the graph opened
        Assert.assertEquals(2L, this.reserve());
        HugeGraph openedLater = Mockito.mock(HugeGraph.class);
        Mockito.when(openedLater.schemaIncarnation()).thenReturn(2L);
        Assert.assertThrows(HugeException.class, () -> this.checkReserved(1L, openedLater));
        Assert.assertThrows(HugeException.class, () -> this.publish(1L),
                            e -> Assert.assertContains("INCARNATION_MISMATCH",
                                                       e.getMessage()));
        Assert.assertEquals("", this.pd.get(CONFIG));
    }

    /**
     * A drop by name that releases a reservation without config and a create that publishes
     * its config are ordered by PD: the drop that comes between the reservation check and the
     * config write of a create fails the create, and a drop after the config write leaves the
     * graph alone
     */
    @Test
    public void testDropByNameAndPublishDontInterleave() {
        Assert.assertEquals(1L, this.reserve());
        this.pd.beforeNextCommit(() -> this.manager.dropGraph("DEFAULT", "g", true));
        Assert.assertThrows(HugeException.class, () -> this.publish(1L));
        Assert.assertEquals("", this.pd.get(CONFIG));
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", this.pd.get(RECORD));

        Assert.assertEquals(2L, this.reserve());
        this.publish(2L);
        // This Server has no instance of the graph yet
        Assert.assertThrows(IllegalArgumentException.class, () -> {
            this.manager.dropGraph("DEFAULT", "g", true);
        });
        Assert.assertContains("\"inc\":2,\"state\":\"LIVE\"", this.pd.get(RECORD));
        Assert.assertContains("\"store\":\"g\"", this.pd.get(CONFIG));
    }

    /**
     * A create whose graph fails to open gives the reserved name back, rather than leaving a
     * LIVE record that blocks the name
     */
    @Test
    public void testFailedCreateDropsTheReservation() {
        // HugeFactory holds an instance of another backend under the name, so the open fails
        HugeGraph other = Mockito.mock(HugeGraph.class);
        Mockito.when(other.backend()).thenReturn("rocksdb");
        Map<String, HugeGraph> factoryGraphs = factoryGraphs();
        factoryGraphs.put("DEFAULT-g", other);
        try {
            Assert.assertThrows(Exception.class, () -> {
                this.manager.createGraph("DEFAULT", "g", "admin", hstoreConfigs(), true);
            }, e -> Assert.assertContains("has been used by backend", rootMessage(e)));
        } finally {
            factoryGraphs.remove("DEFAULT-g");
        }
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", this.pd.get(RECORD));
        Assert.assertEquals("", this.pd.get(CONFIG));

        // The name can be created again
        Assert.assertEquals(2L, this.reserve());
    }

    /**
     * Only an HStore graph keeps its schema in PD and opens with the incarnation of its
     * record, so a graph on another backend gets no record
     */
    @Test
    public void testCreateOfANonHstoreGraphReservesNothing() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("backend", "not-a-backend");
        configs.put("store", "g");
        Assert.assertThrows(Exception.class, () -> {
            this.manager.createGraph("DEFAULT", "g", "admin", configs, true);
        });
        Assert.assertEquals("", this.pd.get(RECORD));
    }

    /**
     * A create that fails in initBackend with something other than a BackendException, such
     * as a PD error on a schema commit, must not leave its instance in HugeFactory: the next
     * create would get that instance, which still writes with the dropped incarnation
     */
    @Test
    public void testCreateAfterAFailedInitBackend() throws Exception {
        HugeGraph failed = mockHstoreGraph(1L);
        Mockito.doThrow(new HugeException("Failed to commit a txn of 2 ops to pd"))
               .when(failed).initBackend();
        Map<String, HugeGraph> factoryGraphs = factoryGraphs();
        factoryGraphs.put("DEFAULT-g", failed);
        try {
            Assert.assertThrows(HugeException.class, () -> {
                this.manager.createGraph("DEFAULT", "g", "admin", hstoreConfigs(), true);
            }, e -> Assert.assertContains("Failed to commit a txn", e.getMessage()));
            Mockito.verify(failed).close();
            Assert.assertFalse(factoryGraphs.containsKey("DEFAULT-g"));
            Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", this.pd.get(RECORD));

            // The next create opens a new instance, with the next incarnation
            HugeGraph opened = mockHstoreGraph(2L);
            factoryGraphs.putIfAbsent("DEFAULT-g", opened);
            HugeGraph created = this.manager.createGraph("DEFAULT", "g", "admin",
                                                         hstoreConfigs(), true);
            Assert.assertSame(opened, created);
            Assert.assertContains("\"inc\":2,\"state\":\"LIVE\"", this.pd.get(RECORD));
            Assert.assertContains("\"store\":\"g\"", this.pd.get(CONFIG));
        } finally {
            factoryGraphs.remove("DEFAULT-g");
        }
    }

    private static String rootMessage(Throwable e) {
        while (e.getCause() != null) {
            e = e.getCause();
        }
        return e.getMessage();
    }

    private static HugeGraph mockHstoreGraph(long incarnation) {
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        Mockito.when(graph.backend()).thenReturn("hstore");
        Mockito.when(graph.spaceGraphName()).thenReturn("DEFAULT-g");
        Mockito.when(graph.schemaIncarnation()).thenReturn(incarnation);
        return graph;
    }

    private static Map<String, Object> hstoreConfigs() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("backend", "hstore");
        configs.put("serializer", "binary");
        configs.put("store", "g");
        return configs;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, HugeGraph> factoryGraphs() {
        return (Map<String, HugeGraph>) Whitebox.getInternalState(HugeFactory.class, "GRAPHS");
    }

    private void publish(long incarnation) {
        Map<String, Object> configs = new HashMap<>();
        configs.put("store", "g");
        Whitebox.invoke(GraphManager.class,
                        new Class<?>[]{String.class, String.class, long.class, Map.class},
                        "publishGraphConfig", this.manager, "DEFAULT", "g", incarnation,
                        configs);
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
