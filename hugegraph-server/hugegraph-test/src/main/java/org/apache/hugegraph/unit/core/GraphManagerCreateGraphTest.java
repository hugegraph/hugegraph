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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.hugegraph.HugeFactory;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.StandardHugeGraph;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.core.GraphManager;
import org.apache.hugegraph.event.EventHub;
import org.apache.hugegraph.exception.ExistedException;
import org.apache.hugegraph.exception.HugeException;
import org.apache.hugegraph.meta.MetaDriver;
import org.apache.hugegraph.meta.MetaManager;
import org.apache.hugegraph.meta.managers.FakeSchemaSyncPd;
import org.apache.hugegraph.meta.managers.SchemaMetaManager;
import org.apache.hugegraph.pd.grpc.kv.TxnRecord;
import org.apache.hugegraph.pd.grpc.kv.TxnRequest;
import org.apache.hugegraph.pd.grpc.kv.TxnResponse;
import org.apache.hugegraph.space.GraphSpace;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.hugegraph.util.Events;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * An HStore graph is created in PD by one commit that starts a new incarnation of its record,
 * before the graph opens and initializes; its config is written last, as before
 */
public class GraphManagerCreateGraphTest {

    private static final String RECORD = "HUGEGRAPH/hg/SCHEMA_SYNC/DEFAULT/g";
    private static final String CONFIG = "HUGEGRAPH/hg/GRAPHSPACE/DEFAULT/GRAPH_CONF/g";

    private FakeSchemaSyncPd pd;
    private MetaManager meta;
    private GraphManager manager;

    @Before
    public void setup() {
        this.pd = new FakeSchemaSyncPd();
        this.manager = this.newManager();
    }

    @After
    public void teardown() {
        factoryGraphs().remove("DEFAULT-g");
        this.manager.close();
    }

    @Test
    public void testCreateCommitsTheRecordFirstAndTheConfigLast() {
        HugeGraph opened = this.open(1L);
        Mockito.doAnswer(invocation -> {
            // The record exists before the graph initializes, the config not yet
            Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", this.pd.get(RECORD));
            Assert.assertEquals("", this.pd.get(CONFIG));
            return null;
        }).when(opened).initBackend();
        String created = this.pd.get(RECORD);

        Assert.assertSame(opened, this.create(this.manager));

        // The config, written last in one commit with a BUMP of the incarnation
        Assert.assertContains("\"store\":\"g\"", this.pd.get(CONFIG));
        Assert.assertNotEquals(created, this.pd.get(RECORD));
        Mockito.verify(this.meta, Mockito.never())
               .addGraphConfig(Mockito.any(), Mockito.any(), Mockito.any());
        // A new name: nothing to clear before the stores are initialized
        Mockito.verify(opened, Mockito.never()).clearBackendForDrop();

        // With the config in PD the name is taken
        GraphManager other = this.newManager();
        try {
            Assert.assertThrows(ExistedException.class, () -> this.create(other));
        } finally {
            other.close();
        }
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", this.pd.get(RECORD));
    }

    /**
     * A create that fails in initBackend with something other than a BackendException, such
     * as a PD error on a schema commit, must not leave its instance in HugeFactory: the next
     * create would get that instance, which writes with the old incarnation. It releases the
     * name it holds since its CREATE, so a retry creates the graph with the next incarnation
     * and clears what the stores hold first, as a retry could before records.
     */
    @Test
    public void testCreateAfterAFailedInitBackend() throws Exception {
        HugeGraph failed = this.open(1L);
        Mockito.doThrow(new HugeException("Failed to commit a txn of 2 ops to pd"))
               .when(failed).initBackend();
        Assert.assertThrows(HugeException.class, () -> this.create(this.manager),
                            e -> Assert.assertContains("Failed to commit a txn",
                                                       e.getMessage()));
        Mockito.verify(failed).close();
        Assert.assertFalse(factoryGraphs().containsKey("DEFAULT-g"));
        Assert.assertEquals("", this.pd.get(CONFIG));

        // The failed create released the name, without clearing the stores
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", this.pd.get(RECORD));
        Mockito.verify(failed, Mockito.never()).clearBackendForDrop();

        // The next create opens a new instance, with the next incarnation
        HugeGraph opened = this.open(2L);
        Assert.assertSame(opened, this.create(this.manager));
        InOrder order = Mockito.inOrder(opened);
        order.verify(opened).clearBackendForDrop();
        order.verify(opened).initBackend();
        Assert.assertContains("\"inc\":2,\"state\":\"LIVE\"", this.pd.get(RECORD));
    }

    /**
     * A graph opened from conf/graphs on another Server has a LIVE record and no config in
     * PD: a create of its name is refused, and nothing of the graph is touched
     */
    @Test
    public void testCreateOfAGraphLoadedFromALocalConfigIsRefused() {
        new SchemaMetaManager(this.pd.driver(), "hg", null).openRecord("DEFAULT", "g");
        this.pd.put("HUGEGRAPH/hg/GRAPHSPACE/DEFAULT/g/SCHEMA/PROPERTY_KEY/ID/1", "{}");

        Assert.assertThrows(ExistedException.class, () -> this.create(this.manager));
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", this.pd.get(RECORD));
        Assert.assertEquals("{}", this.pd.get(
                "HUGEGRAPH/hg/GRAPHSPACE/DEFAULT/g/SCHEMA/PROPERTY_KEY/ID/1"));
        Assert.assertFalse(factoryGraphs().containsKey("DEFAULT-g"));
        Assert.assertEquals("", this.pd.get(CONFIG));
    }

    /**
     * The drop a Server without an instance sends for a name: a graph opened from conf/graphs
     * on another Server has a LIVE record without a config but no create made it, so the drop
     * answers that the graph doesn't exist here, as before, and leaves its schema alone
     */
    @Test
    public void testDropOfAGraphLoadedFromALocalConfigElsewhereIsRefused() {
        new SchemaMetaManager(this.pd.driver(), "hg", null).openRecord("DEFAULT", "g");
        String schemaKey = "HUGEGRAPH/hg/GRAPHSPACE/DEFAULT/g/SCHEMA/PROPERTY_KEY/ID/1";
        this.pd.put(schemaKey, "{}");

        Assert.assertThrows(IllegalArgumentException.class, () -> {
            this.manager.dropGraph("DEFAULT", "g", true);
        }, e -> Assert.assertContains("doesn't exist", e.getMessage()));
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", this.pd.get(RECORD));
        Assert.assertEquals("{}", this.pd.get(schemaKey));
    }

    /**
     * A drop of the name that commits late in a create, here from another Server while the
     * graph initializes, wins: the config write of the create is rejected with the dropped
     * incarnation, the create rolls back, and the name is free for the next create
     */
    @Test
    public void testDropDuringTheCreateWinsAndTheNameStaysFree() throws Exception {
        HugeGraph opened = this.open(1L);
        Mockito.doAnswer(invocation -> {
            SchemaMetaManager other = new SchemaMetaManager(this.pd.driver(), "hg", null);
            Assert.assertTrue(other.dropUnfinishedCreate("DEFAULT", "g"));
            return null;
        }).when(opened).initBackend();

        Assert.assertThrows(HugeException.class, () -> this.create(this.manager),
                            e -> Assert.assertContains("GRAPH_DROPPED", e.getMessage()));
        Assert.assertEquals("", this.pd.get(CONFIG));
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", this.pd.get(RECORD));
        Mockito.verify(opened).close();
        Assert.assertFalse(factoryGraphs().containsKey("DEFAULT-g"));

        HugeGraph again = this.open(2L);
        Assert.assertSame(again, this.create(this.manager));
        Assert.assertContains("\"inc\":2,\"state\":\"LIVE\"", this.pd.get(RECORD));
        Assert.assertContains("\"store\":\"g\"", this.pd.get(CONFIG));
    }

    /**
     * A create paused in its GRAPH_CREATE notification while the name is dropped and created
     * again on the same Server: its config write fails with the old incarnation, and its
     * rollback leaves the registration of the newer instance in HugeFactory alone, so request
     * cleanup still finds the graph the Server serves
     */
    @Test
    public void testRollbackOfAStaleCreateKeepsTheReplacementRegistered() throws Exception {
        HugeGraph stale = this.open(1L);
        HugeGraph replacement = Mockito.mock(StandardHugeGraph.class);
        Mockito.when(replacement.spaceGraphName()).thenReturn("DEFAULT-g");
        EventHub hub = Whitebox.getInternalState(this.manager, "eventHub");
        EventHub.NotifyResult notified = Mockito.mock(EventHub.NotifyResult.class);
        Mockito.when(notified.success()).thenReturn(true);
        AtomicBoolean raced = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            if (Events.GRAPH_CREATE.equals(invocation.getArgument(0)) &&
                raced.compareAndSet(false, true)) {
                // Dropped and created again meanwhile, by a newer instance
                SchemaMetaManager other = new SchemaMetaManager(this.pd.driver(), "hg", null);
                Assert.assertTrue(other.dropUnfinishedCreate("DEFAULT", "g"));
                Assert.assertEquals(2L, other.createGraph("DEFAULT", "g"));
                factoryGraphs().put("DEFAULT-g", replacement);
            }
            return notified;
        }).when(hub).notifySync(Mockito.anyString(), Mockito.any());

        Assert.assertThrows(HugeException.class, () -> this.create(this.manager),
                            e -> Assert.assertContains("INCARNATION_MISMATCH", e.getMessage()));
        Mockito.verify(stale).close();
        Assert.assertSame(replacement, factoryGraphs().get("DEFAULT-g"));
        Assert.assertEquals("", this.pd.get(CONFIG));
        Assert.assertContains("\"inc\":2,\"state\":\"LIVE\"", this.pd.get(RECORD));
    }

    /**
     * Only an HStore graph keeps its schema in PD and opens with the incarnation of its
     * record, so a graph on another backend gets no record
     */
    @Test
    public void testCreateOfANonHstoreGraphWritesNoRecord() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("backend", "not-a-backend");
        configs.put("store", "g");
        Assert.assertThrows(Exception.class, () -> {
            this.manager.createGraph("DEFAULT", "g", "admin", configs, true);
        });
        Assert.assertEquals("", this.pd.get(RECORD));
    }

    /**
     * HugeFactory hands back an instance of another incarnation: the create fails before it
     * initializes anything
     */
    @Test
    public void testCreateOfAnInstanceOfAnotherIncarnationFails() {
        HugeGraph stale = this.open(5L);
        Assert.assertThrows(IllegalStateException.class, () -> this.create(this.manager),
                            e -> Assert.assertContains("instead of 1", e.getMessage()));
        Mockito.verify(stale, Mockito.never()).initBackend();
        // Released: the name is free for a retry
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", this.pd.get(RECORD));
    }

    /**
     * The CREATE commit got no response, here lost after PD applied it: the record shows the
     * CREATE committed, LIVE with its mark and the incarnation it expected and no config, so
     * the create goes on with it instead of answering "exists" to its own commit
     */
    @Test
    public void testCreateWhoseCommitGotNoResponseGoesOnWithItsRecord() {
        this.loseTheResponseOfTheFirst(TxnRecord.Op.CREATE, true);
        HugeGraph opened = this.open(1L);

        Assert.assertSame(opened, this.create(this.manager));
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", this.pd.get(RECORD));
        Assert.assertContains("\"store\":\"g\"", this.pd.get(CONFIG));
    }

    /**
     * The CREATE commit got no response and did not commit: the create fails, the name stays
     * free, and a retry creates the graph
     */
    @Test
    public void testCreateWhoseCommitGotNoResponseAndDidNotCommitFails() {
        this.loseTheResponseOfTheFirst(TxnRecord.Op.CREATE, false);

        Assert.assertThrows(HugeException.class, () -> this.create(this.manager),
                            e -> Assert.assertContains("Failed to commit", e.getMessage()));
        Assert.assertEquals("", this.pd.get(RECORD));
        Assert.assertEquals("", this.pd.get(CONFIG));

        HugeGraph opened = this.open(1L);
        Assert.assertSame(opened, this.create(this.manager));
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", this.pd.get(RECORD));
    }

    /**
     * The config write got no response, here lost after PD applied it: PD holds the config
     * and the incarnation is LIVE, so the create is done and announces the graph
     */
    @Test
    public void testConfigWriteThatGotNoResponseIsDoneWhenPdHoldsTheConfig() {
        this.loseTheResponseOfTheFirst(TxnRecord.Op.BUMP, true);
        HugeGraph opened = this.open(1L);

        Assert.assertSame(opened, this.create(this.manager));
        Assert.assertContains("\"store\":\"g\"", this.pd.get(CONFIG));
        Mockito.verify(this.meta).notifyGraphAdd("DEFAULT", "g");
    }

    /**
     * The config write got no response and did not commit: the create fails and releases the
     * name, so a retry creates the graph
     */
    @Test
    public void testConfigWriteThatGotNoResponseAndDidNotCommitReleasesTheName() {
        this.loseTheResponseOfTheFirst(TxnRecord.Op.BUMP, false);
        this.open(1L);

        Assert.assertThrows(HugeException.class, () -> this.create(this.manager),
                            e -> Assert.assertContains("Failed to commit", e.getMessage()));
        Assert.assertContains("\"inc\":1,\"state\":\"DROPPED\"", this.pd.get(RECORD));
        Assert.assertEquals("", this.pd.get(CONFIG));
    }

    private void loseTheResponseOfTheFirst(TxnRecord.Op op, boolean applied) {
        MetaDriver driver = this.pd.driver();
        AtomicBoolean lost = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            TxnRequest request = invocation.getArgument(0);
            if (request.getRecord().getOp() == op && lost.compareAndSet(false, true)) {
                if (applied) {
                    this.pd.commit(request);
                }
                throw new HugeException("Failed to commit a txn of 1 ops to pd");
            }
            return this.pd.commit(request);
        }).when(driver).commit(Mockito.any());
        Mockito.when(this.meta.schemaMetaManager())
               .thenReturn(new SchemaMetaManager(driver, "hg", null));
    }

    /**
     * A CREATE that PD answered with a failure did not commit, so the create releases nothing:
     * here PD failed it on a record it can't read, and the LIVE record found afterwards is
     * another Server's create, which keeps it
     */
    @Test
    public void testCreateThatPdAnsweredWithAFailureKeepsTheRecord() {
        MetaDriver driver = this.pd.driver();
        Mockito.doAnswer(invocation -> {
            TxnRequest request = invocation.getArgument(0);
            if (request.getRecord().getOp() != TxnRecord.Op.CREATE) {
                return this.pd.commit(request);
            }
            // PD fails this one, and another Server's create commits after it
            Assert.assertTrue(this.pd.commit(request).getSucceeded());
            return TxnResponse.newBuilder().setSucceeded(false)
                              .setFailure(TxnResponse.Failure.INVALID_RECORD).build();
        }).when(driver).commit(Mockito.any());
        Mockito.when(this.meta.schemaMetaManager())
               .thenReturn(new SchemaMetaManager(driver, "hg", null));

        Assert.assertThrows(HugeException.class, () -> this.create(this.manager),
                            e -> Assert.assertContains("INVALID_RECORD", e.getMessage()));
        Assert.assertContains("\"inc\":1,\"state\":\"LIVE\"", this.pd.get(RECORD));
    }

    private HugeGraph create(GraphManager server) {
        Map<String, Object> configs = new HashMap<>();
        configs.put("backend", "hstore");
        configs.put("serializer", "binary");
        configs.put("store", "g");
        return server.createGraph("DEFAULT", "g", "admin", configs, true);
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

    @SuppressWarnings("unchecked")
    private static Map<String, HugeGraph> factoryGraphs() {
        return (Map<String, HugeGraph>) Whitebox.getInternalState(HugeFactory.class, "GRAPHS");
    }

    /**
     * A Server on the fake PD, with a SchemaMetaManager of its own. A mock hub: the executor
     * of EventHub is static, and a test that ran earlier in the suite may have shut it down.
     */
    private GraphManager newManager() {
        SchemaMetaManager schemaMeta = new SchemaMetaManager(this.pd.driver(), "hg", null);
        this.meta = Mockito.mock(MetaManager.class);
        Mockito.when(this.meta.schemaMetaManager()).thenReturn(schemaMeta);
        Mockito.when(this.meta.graphConfigs("DEFAULT")).thenReturn(new HashMap<>());
        Mockito.when(this.meta.graphSpace("DEFAULT")).thenReturn(new GraphSpace("DEFAULT"));

        // The create waits for its GRAPH_CREATE listeners: every one of the mock hub succeeds
        EventHub hub = Mockito.mock(EventHub.class);
        EventHub.NotifyResult notified = Mockito.mock(EventHub.NotifyResult.class);
        Mockito.when(notified.success()).thenReturn(true);
        Mockito.when(hub.notifySync(Mockito.anyString(), Mockito.any())).thenReturn(notified);
        GraphManager manager = new GraphManager(new HugeConfig(new PropertiesConfiguration()),
                                                hub);
        Whitebox.setInternalState(manager, "PDExist", true);
        Whitebox.setInternalState(manager, "metaManager", this.meta);
        Whitebox.setInternalState(manager, "serviceGraphSpace", "DEFAULT");
        Whitebox.setInternalState(manager, "pdPeers", "127.0.0.1:8686");
        Whitebox.setInternalState(manager, "localGraphs", Collections.emptySet());
        Map<String, GraphSpace> spaces = new ConcurrentHashMap<>();
        spaces.put("DEFAULT", new GraphSpace("DEFAULT"));
        Whitebox.setInternalState(manager, "graphSpaces", spaces);
        return manager;
    }
}
