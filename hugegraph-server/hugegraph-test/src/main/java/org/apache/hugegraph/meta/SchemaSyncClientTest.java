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

package org.apache.hugegraph.meta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.HugeGraphParams;
import org.apache.hugegraph.api.cypher.CypherAPI;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.meta.SchemaSyncClient.GraphSync;
import org.apache.hugegraph.meta.SchemaSyncClient.Record;
import org.apache.hugegraph.meta.SchemaSyncClient.State;
import org.apache.hugegraph.meta.SchemaSyncClient.SyncingException;
import org.apache.hugegraph.pd.grpc.kv.WatchEvent;
import org.apache.hugegraph.pd.grpc.kv.WatchKv;
import org.apache.hugegraph.pd.grpc.kv.WatchResponse;
import org.apache.hugegraph.pd.grpc.kv.WatchState;
import org.apache.hugegraph.pd.grpc.kv.WatchType;
import org.apache.hugegraph.task.DistributedTaskScheduler;
import org.apache.hugegraph.task.HugeTask;
import org.apache.hugegraph.task.TaskStatus;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.tinkerpop.gremlin.server.util.ThreadFactoryUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.core.HttpHeaders;

public class SchemaSyncClientTest {

    private static final String PREFIX = "HUGEGRAPH/hg/SCHEMA_SYNC/";
    private static final String G1 = PREFIX + "DEFAULT/g1";
    private static final String G2 = PREFIX + "DEFAULT/g2";

    private final AtomicLong clock = new AtomicLong();
    // "clientId key rev" of every ACK sent
    private final List<String> acks = new ArrayList<>();
    private SchemaSyncClient client;

    @Before
    public void setup() {
        this.client = new SchemaSyncClient(PREFIX, (clientId, key, rev) -> {
            this.acks.add(clientId + " " + key + " " + rev);
            return true;
        }, this.clock::get);
        // Past the startup wait of register()
        this.advance(TimeUnit.DAYS.toMillis(1));
    }

    @After
    public void teardown() {
        Whitebox.setInternalState(SchemaSyncClient.class, "instance", null);
        SchemaSyncClient.endRequest();
    }

    @Test
    public void testHandshakeInvalidatesAndAcks() {
        AtomicInteger cleared = new AtomicInteger();
        GraphSync g1 = this.register("g1", 5L, 1L, cleared::incrementAndGet);
        Assert.assertEquals(1, cleared.get());

        this.frame(WatchState.Starting, 7L);
        Assert.assertEquals(State.SYNCING, this.client.state());
        Assert.assertThrows(SyncingException.class, g1::check,
                            e -> Assert.assertContains("syncing", e.getMessage()));

        this.client.onFrame(records(7L, G1, record(9L, 1L, "LIVE")));
        Assert.assertEquals(2, cleared.get());
        Assert.assertEquals(9L, g1.appliedRevision());
        Assert.assertEquals(List.of("7 " + G1 + " 9"), this.acks);
        // Still SYNCING until Synced
        Assert.assertThrows(SyncingException.class, g1::check);

        this.frame(WatchState.Synced, 7L);
        Assert.assertEquals(State.READY, this.client.state());
        g1.check();
        Assert.assertTrue(g1.serving());
    }

    /**
     * A graph that is CREATING is not served until its record is LIVE; an older CREATING
     * record that comes after that changes nothing
     */
    @Test
    public void testCreatingGraphIsNotServedUntilLive() {
        GraphSync g1 = this.register("g1", 5L, 1L, "CREATING");
        this.sync(7L, G1, record(6L, 1L, "CREATING"));
        Assert.assertFalse(g1.serving());
        Assert.assertThrows(SyncingException.class, g1::check,
                            e -> Assert.assertContains("is being created", e.getMessage()));

        this.client.onFrame(records(7L, G1, record(8L, 1L, "LIVE")));
        Assert.assertTrue(g1.serving());
        g1.check();
        this.client.onFrame(records(7L, G1, record(6L, 1L, "CREATING")));
        Assert.assertTrue(g1.serving());
    }

    /**
     * The creator applies its own commit at once: its graph is served before the event of
     * the commit arrives, which then clears nothing
     */
    @Test
    public void testCreatorServesItsGraphAfterItsCommit() {
        Whitebox.setInternalState(SchemaSyncClient.class, "instance", this.client);
        AtomicInteger cleared = new AtomicInteger();
        GraphSync g1 = this.client.register("DEFAULT", "g1", new Record(5L, 1L, "CREATING"),
                                            cleared::incrementAndGet);
        // The schema the create wrote while CREATING
        this.sync(7L, G1, record(6L, 1L, "CREATING"));
        int clears = cleared.get();
        Assert.assertFalse(g1.serving());

        // Another incarnation's commit is not this graph's
        SchemaSyncClient.created("DEFAULT", "g1", 9L, 2L);
        Assert.assertFalse(g1.serving());
        SchemaSyncClient.created("DEFAULT", "g1", 9L, 1L);
        Assert.assertTrue(g1.serving());

        this.client.onFrame(records(7L, G1, record(6L, 1L, "CREATING")));
        this.client.onFrame(records(7L, G1, record(9L, 1L, "LIVE")));
        Assert.assertTrue(g1.serving());
        Assert.assertEquals(clears, cleared.get());
        Assert.assertEquals(9L, g1.appliedRevision());
    }

    @Test
    public void testOldAndDuplicateRevisionsAreAckedWithoutClearing() {
        AtomicInteger cleared = new AtomicInteger();
        GraphSync g1 = this.register("g1", 5L, 1L, cleared::incrementAndGet);
        this.sync(7L, G1, record(9L, 1L, "LIVE"));
        Assert.assertEquals(2, cleared.get());

        this.client.onFrame(records(7L, G1, record(9L, 1L, "LIVE")));
        this.client.onFrame(records(7L, G1, record(8L, 1L, "LIVE")));
        Assert.assertEquals(2, cleared.get());
        Assert.assertEquals(9L, g1.appliedRevision());
        Assert.assertEquals(List.of("7 " + G1 + " 9", "7 " + G1 + " 9", "7 " + G1 + " 8"),
                            this.acks);

        // A graph not open here has nothing cached: acknowledged as processed
        this.client.onFrame(records(7L, G2, record(4L, 1L, "LIVE")));
        Assert.assertEquals("7 " + G2 + " 4", this.acks.get(3));
    }

    @Test
    public void testFailedInvalidationIsNotAcked() {
        AtomicBoolean fail = new AtomicBoolean();
        GraphSync g1 = this.register("g1", 5L, 1L, () -> {
            if (fail.get()) {
                throw new IllegalStateException("clear failed");
            }
        });
        this.sync(7L, G1, record(9L, 1L, "LIVE"));
        this.acks.clear();

        fail.set(true);
        this.client.onFrame(records(7L, G1, record(10L, 1L, "LIVE")));
        Assert.assertEquals(9L, g1.appliedRevision());
        Assert.assertTrue(this.acks.isEmpty());
        // The session is READY, but the caches of the graph may hold the old schema
        Assert.assertEquals(State.READY, this.client.state());
        Assert.assertFalse(g1.serving());
        Assert.assertThrows(SyncingException.class, g1::check,
                            e -> Assert.assertContains("could not be cleared for revision 10",
                                                       e.getMessage()));
        // A Synced of a new session doesn't serve it either
        this.frame(WatchState.Starting, 8L);
        this.client.onFrame(records(8L, G1, record(10L, 1L, "LIVE")));
        this.frame(WatchState.Synced, 8L);
        Assert.assertFalse(g1.serving());
        Assert.assertTrue(this.acks.isEmpty());

        // PD resends it
        fail.set(false);
        this.client.onFrame(records(8L, G1, record(10L, 1L, "LIVE")));
        Assert.assertEquals(10L, g1.appliedRevision());
        Assert.assertEquals(List.of("8 " + G1 + " 10"), this.acks);
        Assert.assertTrue(g1.serving());
    }

    @Test
    public void testUnreadableRecordIsNotAcked() {
        GraphSync g1 = this.register("g1", 5L, 1L, () -> { });
        this.sync(7L, G1, record(9L, 1L, "LIVE"));
        this.acks.clear();

        this.client.onFrame(records(7L, G1, record(10L, 1L, "GONE")));
        this.client.onFrame(records(7L, G1, "{\"rev\":11,\"state\":\"LIVE\"}"));
        Assert.assertTrue(this.acks.isEmpty());
        Assert.assertEquals(9L, g1.appliedRevision());
        Assert.assertTrue(g1.serving());
    }

    @Test
    public void testDroppedOrRecreatedGraphIsDisabled() {
        GraphSync g1 = this.register("g1", 5L, 1L, () -> { });
        GraphSync g2 = this.register("g2", 5L, 1L, () -> { });
        this.frame(WatchState.Starting, 7L);
        this.client.onFrame(records(7L, G1, record(9L, 1L, "DROPPED")));
        this.client.onFrame(records(7L, G2, record(9L, 2L, "LIVE")));
        this.frame(WatchState.Synced, 7L);

        Assert.assertThrows(SyncingException.class, g1::check,
                            e -> Assert.assertContains("dropped", e.getMessage()));
        Assert.assertThrows(SyncingException.class, g2::check,
                            e -> Assert.assertContains("incarnation 2", e.getMessage()));
        // Both were processed
        Assert.assertEquals(2, this.acks.size());
        // A graph that opens on a DROPPED record is not served either
        GraphSync g3 = this.register("g3", 5L, 1L, "DROPPED");
        Assert.assertFalse(g3.serving());
    }

    @Test
    public void testGraphAbsentFromHandshakeIsGone() {
        GraphSync g1 = this.register("g1", 5L, 1L, () -> { });
        GraphSync g2 = this.register("g2", 5L, 1L, () -> { });
        this.frame(WatchState.Starting, 7L);
        // Opened during the handshake: its record may come later, it is not judged by it
        GraphSync g3 = this.register("g3", 6L, 1L, () -> { });
        this.client.onFrame(records(7L, G1, record(9L, 1L, "LIVE")));
        this.frame(WatchState.Synced, 7L);

        Assert.assertTrue(g1.serving());
        Assert.assertThrows(SyncingException.class, g2::check,
                            e -> Assert.assertContains("record is gone", e.getMessage()));
        Assert.assertTrue(g3.serving());
    }

    /**
     * The instance counts as open at Starting, but opened after PD read the records: its
     * record, committed after the read, is pending work of the session and arrives after
     * Synced
     */
    @Test
    public void testGraphOpenedAfterPdReadTheRecordsIsServedWhenItsRecordArrives() {
        GraphSync g1 = this.register("g1", 5L, 1L, () -> { });
        AtomicInteger cleared = new AtomicInteger();
        GraphSync g2 = this.register("g2", 10L, 1L, cleared::incrementAndGet);
        this.frame(WatchState.Starting, 7L);
        this.client.onFrame(records(7L, G1, record(9L, 1L, "LIVE")));
        this.frame(WatchState.Synced, 7L);
        Assert.assertTrue(g1.serving());
        // Gone or not, it is not served until its record arrives
        Assert.assertFalse(g2.serving());

        this.client.onFrame(records(7L, G2, record(10L, 1L, "LIVE")));
        Assert.assertTrue(g2.serving());
        Assert.assertEquals(10L, g2.appliedRevision());
        Assert.assertEquals("7 " + G2 + " 10", this.acks.get(this.acks.size() - 1));
        // Cleared when registered and when disabled; the record it opened with is applied
        Assert.assertEquals(2, cleared.get());
    }

    @Test
    public void testGraphAbsentFromHandshakeStaysGoneForOtherRecords() {
        GraphSync g1 = this.register("g1", 10L, 1L, () -> { });
        GraphSync g2 = this.register("g2", 10L, 1L, () -> { });
        GraphSync g3 = this.register("g3", 10L, 1L, () -> { });
        this.frame(WatchState.Starting, 7L);
        this.frame(WatchState.Synced, 7L);

        // A later incarnation, an older record and a DROPPED one are not the opened record
        this.client.onFrame(records(7L, PREFIX + "DEFAULT/g1", record(12L, 2L, "LIVE")));
        this.client.onFrame(records(7L, PREFIX + "DEFAULT/g2", record(9L, 1L, "LIVE")));
        this.client.onFrame(records(7L, PREFIX + "DEFAULT/g3", record(12L, 1L, "DROPPED")));
        Assert.assertFalse(g1.serving());
        Assert.assertFalse(g2.serving());
        Assert.assertFalse(g3.serving());
        // ...and a LIVE one after the drop is not either
        this.client.onFrame(records(7L, PREFIX + "DEFAULT/g3", record(13L, 1L, "LIVE")));
        Assert.assertFalse(g3.serving());

        // A dropped instance stays disabled when the next session misses its record
        this.frame(WatchState.Starting, 8L);
        this.frame(WatchState.Synced, 8L);
        this.client.onFrame(records(8L, PREFIX + "DEFAULT/g3", record(14L, 1L, "LIVE")));
        Assert.assertFalse(g3.serving());
    }

    @Test
    public void testChangeAckedBeforeTheGraphOpenedStillApplies() {
        this.sync(7L, G1, record(9L, 1L, "LIVE"));
        this.client.onFrame(records(7L, G1, record(12L, 1L, "DROPPED")));
        // The instance read the record before the drop, and registers after it was acked
        GraphSync g1 = this.register("g1", 9L, 1L, () -> { });
        Assert.assertFalse(g1.serving());
    }

    /**
     * A new instance opens with the record of its incarnation, read from PD, while the
     * session still has a record of the incarnation before it to deliver. That one is older:
     * it must not disable the new instance.
     */
    @Test
    public void testOlderRecordOfAnEarlierIncarnationKeepsANewInstanceServed() {
        this.sync(7L, G2, record(4L, 1L, "LIVE"));
        this.acks.clear();
        for (String state : new String[]{"LIVE", "DROPPED"}) {
            AtomicInteger cleared = new AtomicInteger();
            GraphSync g1 = this.client.register("DEFAULT", "g1", new Record(12L, 2L, "LIVE"),
                                                cleared::incrementAndGet);
            this.client.onFrame(records(7L, G1, record(11L, 1L, state)));
            Assert.assertTrue(state, g1.serving());
            this.client.onFrame(records(7L, G1, record(12L, 2L, "LIVE")));
            Assert.assertTrue(state, g1.serving());
            g1.check();
            Assert.assertEquals(12L, g1.appliedRevision());
            // Cleared when registered only, and both records acknowledged
            Assert.assertEquals(1, cleared.get());
            Assert.assertEquals(List.of("7 " + G1 + " 11", "7 " + G1 + " 12"), this.acks);
            this.acks.clear();
            g1.close();
        }

        // A newer DROPPED record still disables the instance
        GraphSync g1 = this.register("g1", 12L, 2L, () -> { });
        this.client.onFrame(records(7L, G1, record(13L, 2L, "DROPPED")));
        Assert.assertThrows(SyncingException.class, g1::check,
                            e -> Assert.assertContains("dropped", e.getMessage()));
    }

    /**
     * The session keeps the newest record of a graph: an older one that comes late doesn't
     * replace it, and an instance that registers afterwards starts from the newest
     */
    @Test
    public void testOlderRecordDoesNotReplaceTheLatestOfTheSession() {
        this.sync(7L, G1, record(12L, 2L, "LIVE"));
        this.client.onFrame(records(7L, G1, record(13L, 2L, "DROPPED")));
        this.client.onFrame(records(7L, G1, record(12L, 2L, "LIVE")));
        // The instance read the record before the drop
        GraphSync g1 = this.register("g1", 12L, 2L, () -> { });
        Assert.assertFalse(g1.serving());
        Assert.assertThrows(SyncingException.class, g1::check,
                            e -> Assert.assertContains("dropped", e.getMessage()));
    }

    /**
     * The clear of register() applies the record the instance opened with, or a newer one
     * this session acknowledged before: a duplicate or older delivery doesn't clear again
     */
    @Test
    public void testRegisterAppliesTheRecordItOpenedWith() {
        AtomicInteger cleared = new AtomicInteger();
        GraphSync g1 = this.register("g1", 9L, 1L, cleared::incrementAndGet);
        Assert.assertEquals(9L, g1.appliedRevision());
        this.sync(7L, G1, record(9L, 1L, "LIVE"));
        Assert.assertEquals(1, cleared.get());
        Assert.assertEquals(List.of("7 " + G1 + " 9"), this.acks);
        Assert.assertTrue(g1.serving());

        this.client.onFrame(records(7L, G2, record(12L, 1L, "LIVE")));
        AtomicInteger cleared2 = new AtomicInteger();
        GraphSync g2 = this.register("g2", 10L, 1L, cleared2::incrementAndGet);
        Assert.assertEquals(12L, g2.appliedRevision());
        this.client.onFrame(records(7L, G2, record(12L, 1L, "LIVE")));
        this.client.onFrame(records(7L, G2, record(11L, 1L, "LIVE")));
        Assert.assertEquals(1, cleared2.get());
        this.client.onFrame(records(7L, G2, record(13L, 1L, "LIVE")));
        Assert.assertEquals(2, cleared2.get());
        Assert.assertEquals(13L, g2.appliedRevision());
    }

    @Test
    public void testExpiredOrClosedSessionBlocksUntilANewOneIsReady() {
        GraphSync g1 = this.register("g1", 5L, 1L, () -> { });
        this.sync(7L, G1, record(9L, 1L, "LIVE"));
        Assert.assertTrue(g1.serving());

        // No frame for the session timeout
        this.advance(15001L);
        Assert.assertEquals(State.STALE, this.client.state());
        Assert.assertThrows(SyncingException.class, g1::check);
        // Its frames resume: PD kept the session
        this.frame(WatchState.Alive, 7L);
        Assert.assertTrue(g1.serving());

        this.client.onSessionClosed(7L);
        Assert.assertEquals(State.STALE, this.client.state());
        // Late frames of the closed session change nothing
        this.frame(WatchState.Alive, 7L);
        this.client.onFrame(records(7L, G1, record(10L, 1L, "LIVE")));
        Assert.assertEquals(State.STALE, this.client.state());
        Assert.assertEquals(9L, g1.appliedRevision());

        this.sync(8L, G1, record(10L, 1L, "LIVE"));
        Assert.assertTrue(g1.serving());
        Assert.assertEquals(10L, g1.appliedRevision());
        Assert.assertEquals("8 " + G1 + " 10", this.acks.get(this.acks.size() - 1));
    }

    @Test
    public void testStoppedWatchAsksForARestart() {
        GraphSync g1 = this.register("g1", 5L, 1L, () -> { });
        this.sync(7L, G1, record(9L, 1L, "LIVE"));
        this.client.onStopped(new IllegalStateException("PERMISSION_DENIED"));
        Assert.assertEquals(State.CLOSED, this.client.state());
        Assert.assertFalse(g1.serving());
        Assert.assertThrows(SyncingException.class, g1::check, e -> {
            Assert.assertContains("not served until the Server restarts", e.getMessage());
            Assert.assertFalse(e.getMessage().contains("retry later"));
        });
        // A late close of its last session doesn't make it look recoverable
        this.client.onSessionClosed(7L);
        Assert.assertEquals(State.CLOSED, this.client.state());
    }

    /**
     * No Synced comes after the watch stopped: a graph open that waits for the initial sync
     * returns at once, not at the end of the startup wait
     */
    @Test
    public void testStoppedWatchReleasesAnOpenThatWaitsForTheInitialSync() throws Exception {
        // A client inside its startup wait: the clock stands still
        SchemaSyncClient starting = new SchemaSyncClient(PREFIX, (clientId, key, rev) -> true,
                                                         this.clock::get);
        Thread opener = new Thread(() -> {
            starting.register("DEFAULT", "g1", new Record(5L, 1L, "LIVE"), () -> { });
        });
        opener.setDaemon(true);
        opener.start();
        try {
            for (int i = 0; i < 1000 && opener.getState() != Thread.State.TIMED_WAITING; i++) {
                Thread.sleep(10L);
            }
            Assert.assertEquals(Thread.State.TIMED_WAITING, opener.getState());

            starting.onStopped(new IllegalStateException("UNIMPLEMENTED"));
            opener.join(5000L);
            Assert.assertFalse(opener.isAlive());
        } finally {
            opener.interrupt();
        }
    }

    /**
     * The state gauge reports no state while no HStore graph is open, also after the last one
     * closed
     */
    @Test
    public void testNoStateIsReportedWhileNoGraphIsOpen() {
        Whitebox.setInternalState(SchemaSyncClient.class, "instance", null);
        Assert.assertNull(SchemaSyncClient.currentState());
        Whitebox.setInternalState(SchemaSyncClient.class, "instance", this.client);
        Assert.assertNull(SchemaSyncClient.currentState());

        GraphSync g1 = this.register("g1", 5L, 1L, () -> { });
        this.sync(7L, G1, record(9L, 1L, "LIVE"));
        Assert.assertEquals(State.READY, SchemaSyncClient.currentState());
        g1.close();
        Assert.assertNull(SchemaSyncClient.currentState());
    }

    /**
     * The Cypher API doesn't reach the graph through API.graph(), and its client reports any
     * failed query as a 400 in a 200 response: the request is checked before it is sent
     */
    @Test
    public void testCypherRequestsAreChecked() {
        Whitebox.setInternalState(SchemaSyncClient.class, "instance", this.client);
        this.register("g1", 5L, 1L, () -> { });
        this.frame(WatchState.Starting, 7L);
        CypherAPI api = new CypherAPI();
        HttpHeaders headers = Mockito.mock(HttpHeaders.class);
        Assert.assertThrows(SyncingException.class, () -> {
            api.query(headers, "DEFAULT", "g1", "MATCH (n) RETURN n");
        }, e -> Assert.assertContains("syncing", e.getMessage()));
        Assert.assertThrows(SyncingException.class, () -> {
            api.post(headers, "DEFAULT", "g1", "MATCH (n) RETURN n");
        });

        // Served: the request goes on, here to the missing credentials
        this.client.onFrame(records(7L, G1, record(9L, 1L, "LIVE")));
        this.frame(WatchState.Synced, 7L);
        Assert.assertThrows(NotAuthorizedException.class, () -> {
            api.query(headers, "DEFAULT", "g1", "MATCH (n) RETURN n");
        });
    }

    /**
     * A Gremlin request can start before the first HStore graph opens and access it after:
     * its first access is checked like any other
     */
    @Test
    public void testRequestStartedBeforeTheFirstGraphOpenedIsChecked() {
        // No HStore graph open in this JVM
        Whitebox.setInternalState(SchemaSyncClient.class, "instance", null);
        SchemaSyncClient.beginRequest();
        Whitebox.setInternalState(SchemaSyncClient.class, "instance", this.client);
        GraphSync g1 = this.register("g1", 5L, 1L, () -> { });
        this.frame(WatchState.Starting, 7L);
        Assert.assertThrows(SyncingException.class, g1::admit);
    }

    @Test
    public void testTaskSchedulerStartsNoTaskWhileTheGraphIsNotServed() {
        Whitebox.setInternalState(SchemaSyncClient.class, "instance", this.client);
        GraphSync g1 = this.register("g1", 5L, 1L, () -> { });
        HugeGraph graph = Mockito.mock(HugeGraph.class);
        Mockito.when(graph.graphSpace()).thenReturn("DEFAULT");
        HugeGraphParams params = Mockito.mock(HugeGraphParams.class);
        Mockito.when(params.graph()).thenReturn(graph);
        Mockito.when(params.name()).thenReturn("g1");
        Mockito.when(params.spaceGraphName()).thenReturn("DEFAULT-g1");
        Mockito.when(params.configuration()).thenReturn(
                new HugeConfig(new PropertiesConfiguration()));
        Mockito.when(params.started()).thenReturn(true);

        ScheduledThreadPoolExecutor cron = new ScheduledThreadPoolExecutor(1);
        List<ExecutorService> executors = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            executors.add(Executors.newSingleThreadExecutor());
        }
        // The task statuses the scheduler looked for
        List<TaskStatus> queried = new ArrayList<>();
        try {
            DistributedTaskScheduler scheduler = new DistributedTaskScheduler(
                    params, cron, executors.get(0), executors.get(1), executors.get(2),
                    executors.get(3), executors.get(4)) {

                @Override
                protected <V> Iterator<HugeTask<V>> queryTaskWithoutResultByStatus(
                        TaskStatus status) {
                    queried.add(status);
                    return Collections.emptyIterator();
                }
            };

            /*
             * SYNCING: no NEW task is looked for, so none starts, and no FAILED one to start
             * again. Tasks that run here are still cancelled and deleted.
             */
            List<TaskStatus> unserved = List.of(TaskStatus.RUNNING, TaskStatus.CANCELLING,
                                                TaskStatus.DELETING);
            this.frame(WatchState.Starting, 7L);
            scheduler.cronSchedule();
            Assert.assertEquals(unserved, queried);

            queried.clear();
            this.client.onFrame(records(7L, G1, record(9L, 1L, "LIVE")));
            this.frame(WatchState.Synced, 7L);
            Assert.assertTrue(g1.serving());
            scheduler.cronSchedule();
            Assert.assertEquals(List.of(TaskStatus.NEW, TaskStatus.RUNNING, TaskStatus.FAILED,
                                        TaskStatus.CANCELLING, TaskStatus.DELETING),
                                queried);

            // STALE again
            queried.clear();
            this.client.onSessionClosed(7L);
            scheduler.cronSchedule();
            Assert.assertEquals(unserved, queried);
        } finally {
            cron.shutdownNow();
            executors.forEach(ExecutorService::shutdownNow);
        }
    }

    @Test
    public void testGateBlocksNewRequestsButNotInFlightOnes() {
        Whitebox.setInternalState(SchemaSyncClient.class, "instance", this.client);
        GraphSync g1 = this.register("g1", 5L, 1L, () -> { });
        this.frame(WatchState.Starting, 7L);

        // REST
        Assert.assertThrows(SyncingException.class,
                            () -> SchemaSyncClient.check("DEFAULT", "g1"));
        Assert.assertFalse(SchemaSyncClient.serving("DEFAULT", "g1"));
        SchemaSyncClient.check("DEFAULT", "not-open-here");
        // Gremlin
        SchemaSyncClient.beginRequest();
        Assert.assertThrows(SyncingException.class, g1::admit);
        SchemaSyncClient.endRequest();

        this.client.onFrame(records(7L, G1, record(9L, 1L, "LIVE")));
        this.frame(WatchState.Synced, 7L);
        SchemaSyncClient.check("DEFAULT", "g1");
        SchemaSyncClient.beginRequest();
        g1.admit();

        this.client.onSessionClosed(7L);
        // Admitted before: in flight, not interrupted
        g1.admit();
        SchemaSyncClient.endRequest();
        SchemaSyncClient.beginRequest();
        Assert.assertThrows(SyncingException.class, g1::admit);
        Assert.assertThrows(SyncingException.class,
                            () -> SchemaSyncClient.check("DEFAULT", "g1"));

        // A closed instance is no longer looked up
        g1.close();
        SchemaSyncClient.check("DEFAULT", "g1");
    }

    /**
     * TinkerPop runs the scripts of a Gremlin session on a thread of the session, not through
     * the request pool that marks a request, so the gate checks each graph access there
     */
    @Test
    public void testGremlinSessionScriptsAreChecked() throws InterruptedException {
        GraphSync g1 = this.register("g1", 5L, 1L, () -> { });
        this.frame(WatchState.Starting, 7L);
        // Neither a request nor a session, as a running task or the Server itself
        g1.admit();
        Assert.assertThrows(SyncingException.class, () -> this.inGremlinSession(g1::admit));

        this.sync(7L, G1, record(9L, 1L, "LIVE"));
        this.inGremlinSession(g1::admit);
        this.inGremlinSession(g1::admit);

        this.client.onSessionClosed(7L);
        Assert.assertThrows(SyncingException.class, () -> this.inGremlinSession(g1::admit),
                            e -> Assert.assertContains("STALE", e.getMessage()));
    }

    /**
     * Runs on a thread named as TinkerPop names the thread of a Gremlin session
     */
    private void inGremlinSession(Runnable runnable) throws InterruptedException {
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Thread thread = ThreadFactoryUtil.create("session-%d").newThread(() -> {
            try {
                runnable.run();
            } catch (RuntimeException e) {
                failure.set(e);
            }
        });
        thread.start();
        thread.join(10000L);
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    private GraphSync register(String graph, long rev, long inc, Runnable invalidator) {
        return this.client.register("DEFAULT", graph, new Record(rev, inc, "LIVE"),
                                    invalidator);
    }

    private GraphSync register(String graph, long rev, long inc, String state) {
        return this.client.register("DEFAULT", graph,
                                    Record.parse(record(rev, inc, state)), () -> { });
    }

    private void sync(long clientId, String key, String value) {
        this.frame(WatchState.Starting, clientId);
        this.client.onFrame(records(clientId, key, value));
        this.frame(WatchState.Synced, clientId);
    }

    private void frame(WatchState state, long clientId) {
        this.client.onFrame(WatchResponse.newBuilder().setState(state)
                                         .setClientId(clientId).build());
    }

    private void advance(long millis) {
        this.clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    private static WatchResponse records(long clientId, String key, String value) {
        WatchKv kv = WatchKv.newBuilder().setKey(key).setValue(value).build();
        return WatchResponse.newBuilder().setState(WatchState.Started).setClientId(clientId)
                            .addEvents(WatchEvent.newBuilder().setType(WatchType.Put)
                                                 .setCurrent(kv))
                            .build();
    }

    private static String record(long rev, long inc, String state) {
        return "{\"rev\":" + rev + ",\"inc\":" + inc + ",\"state\":\"" + state + "\"}";
    }
}
