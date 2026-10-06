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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hugegraph.meta.SchemaSyncClient.GraphSync;
import org.apache.hugegraph.meta.SchemaSyncClient.Record;
import org.apache.hugegraph.meta.SchemaSyncClient.State;
import org.apache.hugegraph.meta.SchemaSyncClient.SyncingException;
import org.apache.hugegraph.pd.grpc.kv.WatchEvent;
import org.apache.hugegraph.pd.grpc.kv.WatchKv;
import org.apache.hugegraph.pd.grpc.kv.WatchResponse;
import org.apache.hugegraph.pd.grpc.kv.WatchState;
import org.apache.hugegraph.pd.grpc.kv.WatchType;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.testutil.Whitebox;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

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

        // PD resends it
        fail.set(false);
        this.client.onFrame(records(7L, G1, record(10L, 1L, "LIVE")));
        Assert.assertEquals(10L, g1.appliedRevision());
        Assert.assertEquals(List.of("7 " + G1 + " 10"), this.acks);
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

    @Test
    public void testChangeAckedBeforeTheGraphOpenedStillApplies() {
        this.sync(7L, G1, record(9L, 1L, "LIVE"));
        this.client.onFrame(records(7L, G1, record(12L, 1L, "DROPPED")));
        // The instance read the record before the drop, and registers after it was acked
        GraphSync g1 = this.register("g1", 9L, 1L, () -> { });
        Assert.assertFalse(g1.serving());
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

    private GraphSync register(String graph, long rev, long inc, Runnable invalidator) {
        return this.client.register("DEFAULT", graph, new Record(rev, inc, false),
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
