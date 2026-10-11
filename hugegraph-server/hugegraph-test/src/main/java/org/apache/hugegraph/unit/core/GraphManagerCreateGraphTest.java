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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.hugegraph.exception.HugeException;
import org.apache.hugegraph.HugeFactory;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.StandardHugeGraph;
import org.apache.hugegraph.api.profile.GraphsAPI;
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
import org.mockito.InOrder;
import org.mockito.Mockito;

import com.google.common.collect.ImmutableSet;

import jakarta.ws.rs.core.SecurityContext;
import sun.misc.Unsafe;

/**
 * An HStore graph is created in PD by one commit that writes its config and starts a new
 * incarnation (CREATING), and made LIVE by another once it initialized, so two Servers can't
 * both create it and a failed create leaves nothing behind
 */
public class GraphManagerCreateGraphTest {

    private static final String RECORD = "HUGEGRAPH/hg/SCHEMA_SYNC/DEFAULT/g";
    private static final String SCHEMA = "HUGEGRAPH/hg/GRAPHSPACE/DEFAULT/g/SCHEMA";
    private static final String CONFIG = "HUGEGRAPH/hg/GRAPHSPACE/DEFAULT/GRAPH_CONF/g";

    private FakeSchemaSyncPd pd;
    private GraphManager manager;

    @Before
    public void setup() throws Exception {
        this.pd = new FakeSchemaSyncPd();
        this.manager = this.newManager();
    }

    @Test
    public void testCreateCommitsTheGraph() {
        HugeGraph opened = this.open(1L);
        try {
            HugeGraph created = this.create(this.manager);
            Assert.assertSame(opened, created);
        } finally {
            factoryGraphs().remove("DEFAULT-g");
        }
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", this.pd.get(RECORD));
        Assert.assertContains("\"store\":\"g\"", this.pd.get(CONFIG));
        // A new name: nothing to clear before the stores are initialized
        Mockito.verify(opened, Mockito.never()).clearBackendForDrop();

        Assert.assertThrows(ExistedException.class, () -> this.create(this.newManager()));
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", this.pd.get(RECORD));
    }

    /**
     * Two Servers create the same graph at once: the second one's commit comes while the
     * first one initializes its graph, and fails; the first one finishes
     */
    @Test
    public void testConcurrentCreatesOfOneName() throws Exception {
        GraphManager serverB = this.newManager();
        HugeGraph opened = this.open(1L);
        AtomicReference<Throwable> createOfB = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            Assert.assertContains("\"state\":\"CREATING\"", this.pd.get(RECORD));
            try {
                this.create(serverB);
            } catch (Throwable e) {
                createOfB.set(e);
            }
            return null;
        }).when(opened).initBackend();
        try {
            Assert.assertSame(opened, this.create(this.manager));
        } finally {
            factoryGraphs().remove("DEFAULT-g");
        }
        Assert.assertTrue(createOfB.get() instanceof ExistedException);
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", this.pd.get(RECORD));
    }

    /**
     * The commit that creates the graph is sent again when its response is lost; PD answers
     * with its first outcome, so the create succeeds instead of finding its own graph
     */
    @Test
    public void testCreateSentAgainAfterALostResponse() {
        this.open(1L);
        try {
            this.pd.resendNextCommit();
            this.create(this.manager);
        } finally {
            factoryGraphs().remove("DEFAULT-g");
        }
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", this.pd.get(RECORD));
    }

    /**
     * A create whose graph fails to open drops the graph again, rather than leaving it
     * CREATING with its config
     */
    @Test
    public void testFailedCreateDropsTheGraph() {
        // HugeFactory holds an instance of another backend under the name, so the open fails
        HugeGraph other = Mockito.mock(StandardHugeGraph.class);
        Mockito.when(other.backend()).thenReturn("rocksdb");
        Map<String, HugeGraph> factoryGraphs = factoryGraphs();
        factoryGraphs.put("DEFAULT-g", other);
        try {
            Assert.assertThrows(Exception.class, () -> this.create(this.manager),
                                e -> Assert.assertContains("has been used by backend",
                                                           rootMessage(e)));
        } finally {
            factoryGraphs.remove("DEFAULT-g");
        }
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", this.pd.get(RECORD));
        Assert.assertEquals("", this.pd.get(CONFIG));
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
     * create would get that instance, which still writes with the dropped incarnation. The
     * next create starts the next incarnation and clears what the stores hold first.
     */
    @Test
    public void testCreateAfterAFailedInitBackend() throws Exception {
        HugeGraph failed = this.open(1L);
        Mockito.doThrow(new HugeException("Failed to commit a txn of 2 ops to pd"))
               .when(failed).initBackend();
        Map<String, HugeGraph> factoryGraphs = factoryGraphs();
        try {
            Assert.assertThrows(HugeException.class, () -> this.create(this.manager),
                                e -> Assert.assertContains("Failed to commit a txn",
                                                           e.getMessage()));
            Mockito.verify(failed).close();
            Assert.assertFalse(factoryGraphs.containsKey("DEFAULT-g"));
            Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", this.pd.get(RECORD));
            Assert.assertEquals("", this.pd.get(CONFIG));

            // The next create opens a new instance, with the next incarnation
            HugeGraph opened = this.open(2L);
            Assert.assertSame(opened, this.create(this.manager));
            InOrder order = Mockito.inOrder(opened);
            order.verify(opened).clearBackendForDrop();
            order.verify(opened).initBackend();
            Assert.assertContains("\"inc\":2,\"state\":\"LIVE\"", this.pd.get(RECORD));
            Assert.assertContains("\"store\":\"g\"", this.pd.get(CONFIG));
        } finally {
            factoryGraphs.remove("DEFAULT-g");
        }
    }

    /**
     * HugeFactory hands back an instance of another incarnation: the create fails before it
     * initializes anything, and drops its own incarnation
     */
    @Test
    public void testCreateOfAnInstanceOfAnotherIncarnationFails() {
        HugeGraph stale = this.open(5L);
        try {
            Assert.assertThrows(IllegalStateException.class, () -> this.create(this.manager),
                                e -> Assert.assertContains("instead of 1", e.getMessage()));
        } finally {
            factoryGraphs().remove("DEFAULT-g");
        }
        Mockito.verify(stale, Mockito.never()).initBackend();
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", this.pd.get(RECORD));
    }

    /**
     * A drop of the graph by name on another Server while it is created (here while it
     * initializes) wins: the create can't commit, and fails
     */
    @Test
    public void testCreateFailsWhenItsGraphIsDroppedMeanwhile() throws Exception {
        GraphManager serverB = this.newManager();
        HugeGraph opened = this.open(1L);
        Mockito.doAnswer(invocation -> {
            serverB.dropGraph("DEFAULT", "g", true);
            return null;
        }).when(opened).initBackend();
        try {
            Assert.assertThrows(HugeException.class, () -> this.create(this.manager),
                                e -> Assert.assertContains("GRAPH_DROPPED", e.getMessage()));
            Mockito.verify(opened).close();
        } finally {
            factoryGraphs().remove("DEFAULT-g");
        }
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", this.pd.get(RECORD));
        Assert.assertEquals("", this.pd.get(CONFIG));
        Assert.assertEquals("", this.pd.get(SCHEMA));
    }

    /**
     * The CREATE commit writes the graph config, so the graph space lists the graph from
     * then on, while only the creating Server opens it. Listing the graph space on another
     * Server leaves that graph out and still returns the others, also for a graph whose
     * creating Server died and left it CREATING.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testListOnAnotherServerSkipsAGraphBeingCreated() {
        // Another Server started to create graph g
        SchemaMetaManager creator = new SchemaMetaManager(this.pd.driver(), "hg", null);
        Assert.assertEquals(1L, creator.createGraph("DEFAULT", "g", hstoreConfigs()));

        // This Server finds its config in PD, and has graph live open
        MetaManager meta = Whitebox.getInternalState(this.manager, "metaManager");
        Map<String, Map<String, Object>> configs = new HashMap<>();
        configs.put("DEFAULT-g", hstoreConfigs());
        configs.put("DEFAULT-live", new HashMap<>());
        Mockito.when(meta.graphConfigs("DEFAULT")).thenReturn(configs);
        Map<String, Graph> graphs = Whitebox.getInternalState(this.manager, "graphs");
        HugeGraph live = Mockito.mock(HugeGraph.class);
        Mockito.when(live.configuration())
               .thenReturn(new HugeConfig(new PropertiesConfiguration()));
        graphs.put("DEFAULT-live", live);
        // The open of graph g on this Server, as StandardHugeGraph does it
        HugeGraph opening = this.open(1L);
        Mockito.doAnswer(invocation -> {
            meta.schemaMetaManager().adoptRecord("DEFAULT", "g");
            return null;
        }).when(opening).graphSpace("DEFAULT");
        SecurityContext context = Mockito.mock(SecurityContext.class);
        Mockito.when(context.isUserInRole(Mockito.anyString())).thenReturn(true);

        GraphsAPI api = new GraphsAPI();
        try {
            Assert.assertNull(this.manager.graph("DEFAULT", "g"));
            Map<String, Object> listed = (Map<String, Object>) api.list(this.manager,
                                                                        "DEFAULT", context);
            Assert.assertEquals(ImmutableSet.of("live"), listed.get("graphs"));
            List<Map<String, Object>> profiles = (List<Map<String, Object>>) api.listProfile(
                    this.manager, "DEFAULT", null, context);
            Assert.assertEquals(1, profiles.size());
            Assert.assertEquals("live", profiles.get(0).get("name"));

            // Once the create committed, the graph opens here and is listed
            creator.commitCreate("DEFAULT", "g", 1L);
            listed = (Map<String, Object>) api.list(this.manager, "DEFAULT", context);
            Assert.assertEquals(ImmutableSet.of("live", "g"), listed.get("graphs"));
        } finally {
            factoryGraphs().remove("DEFAULT-g");
        }
    }

    /**
     * A create of a name whose earlier graph is still being dropped (its stores are not
     * cleared yet) fails, and writes nothing
     */
    @Test
    public void testCreateWhileTheNameIsBeingDropped() {
        SchemaMetaManager dropper = new SchemaMetaManager(this.pd.driver(), "hg", null);
        Assert.assertEquals(1L, dropper.createGraph("DEFAULT", "g", hstoreConfigs()));
        dropper.commitCreate("DEFAULT", "g", 1L);
        Assert.assertTrue(dropper.dropGraph("DEFAULT", "g", 1L, true).getSucceeded());

        HugeGraph opened = this.open(2L);
        try {
            Assert.assertThrows(HugeException.class, () -> this.create(this.manager),
                                e -> Assert.assertContains("is being dropped",
                                                           e.getMessage()));
            Mockito.verify(opened, Mockito.never()).clearBackendForDrop();
            Mockito.verify(opened, Mockito.never()).initBackend();
            Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", this.pd.get(RECORD));
            Assert.assertEquals("", this.pd.get(CONFIG));

            dropper.finishDrop("DEFAULT", "g");
            Assert.assertSame(opened, this.create(this.manager));
            Mockito.verify(opened).clearBackendForDrop();
        } finally {
            factoryGraphs().remove("DEFAULT-g");
        }
    }

    private HugeGraph create(GraphManager server) {
        return server.createGraph("DEFAULT", "g", "admin", hstoreConfigs(), true);
    }

    /**
     * The instance GraphFactory.open returns for the graph: it opened with the incarnation.
     * HugeFactory keeps its graphs as StandardHugeGraph.
     */
    private HugeGraph open(long incarnation) {
        HugeGraph graph = Mockito.mock(StandardHugeGraph.class);
        Mockito.when(graph.backend()).thenReturn("hstore");
        Mockito.when(graph.spaceGraphName()).thenReturn("DEFAULT-g");
        Mockito.when(graph.schemaIncarnation()).thenReturn(incarnation);
        factoryGraphs().put("DEFAULT-g", graph);
        return graph;
    }

    private static String rootMessage(Throwable e) {
        while (e.getCause() != null) {
            e = e.getCause();
        }
        return e.getMessage();
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

    /**
     * A Server on the fake PD, with a SchemaMetaManager of its own
     */
    private GraphManager newManager() throws Exception {
        SchemaMetaManager schemaMeta = new SchemaMetaManager(this.pd.driver(), "hg", null);
        MetaManager meta = Mockito.mock(MetaManager.class);
        Mockito.when(meta.schemaMetaManager()).thenReturn(schemaMeta);
        Mockito.when(meta.graphConfigs("DEFAULT")).thenReturn(new HashMap<>());
        Mockito.when(meta.graphSpace("DEFAULT")).thenReturn(new GraphSpace("DEFAULT"));

        GraphManager manager = allocateGraphManager();
        Whitebox.setInternalState(manager, "PDExist", true);
        Whitebox.setInternalState(manager, "metaManager", meta);
        Whitebox.setInternalState(manager, "serviceGraphSpace", "DEFAULT");
        Whitebox.setInternalState(manager, "pdPeers", "127.0.0.1:8686");
        Whitebox.setInternalState(manager, "config",
                                  new HugeConfig(new PropertiesConfiguration()));
        Whitebox.setInternalState(manager, "eventHub", new EventHub("create-test"));
        Whitebox.setInternalState(manager, "graphs", new ConcurrentHashMap<String, Graph>());
        Whitebox.setInternalState(manager, "creatingGraphs",
                                  ConcurrentHashMap.<String>newKeySet());
        Whitebox.setInternalState(manager, "localGraphs", Collections.emptySet());
        Whitebox.setInternalState(manager, "removingGraphs", new HashSet<String>());
        Whitebox.setInternalState(manager, "droppingGraphs",
                                  ConcurrentHashMap.<String>newKeySet());
        Map<String, GraphSpace> spaces = new ConcurrentHashMap<>();
        spaces.put("DEFAULT", new GraphSpace("DEFAULT"));
        Whitebox.setInternalState(manager, "graphSpaces", spaces);
        return manager;
    }

    private static GraphManager allocateGraphManager() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        return (GraphManager) unsafe.allocateInstance(GraphManager.class);
    }
}
