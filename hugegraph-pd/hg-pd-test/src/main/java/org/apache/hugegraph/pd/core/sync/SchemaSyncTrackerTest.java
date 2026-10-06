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

package org.apache.hugegraph.pd.core.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.hugegraph.pd.common.PDException;
import org.apache.hugegraph.pd.config.PDConfig;
import org.apache.hugegraph.pd.grpc.kv.WatchResponse;
import org.apache.hugegraph.pd.grpc.kv.WatchState;
import org.apache.hugegraph.pd.sync.SchemaSyncTracker;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import io.grpc.stub.StreamObserver;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives {@link SchemaSyncTracker} with a manual clock and no timer: each poll() is a timer
 * tick at the current time
 */
public class SchemaSyncTrackerTest {

    private static final String PREFIX = "HUGEGRAPH/hg/SCHEMA_SYNC/";
    private static final String A = PREFIX + "DEFAULT/a";
    private static final String B = PREFIX + "DEFAULT/b";

    private long now;
    private boolean leader = true;
    private final List<Long> closed = new ArrayList<>();
    private SchemaSyncTracker tracker;

    @Before
    public void setUp() {
        PDConfig.SchemaSync config = new PDConfig().new SchemaSync();
        config.setCoalesceWindow(50L);
        config.setMaxWait(500L);
        config.setRetryBackoff(1000L);
        config.setRetryBudget(5000L);
        config.setKeepaliveInterval(2000L);
        this.now = 1000L;
        this.tracker = new SchemaSyncTracker(config, null, () -> this.now, () -> this.leader,
                                             (prefix, clientId) -> this.closed.add(clientId));
    }

    @Test
    public void testSyncPrefix() {
        Assert.assertTrue(SchemaSyncTracker.isSyncPrefix(PREFIX));
        Assert.assertTrue(SchemaSyncTracker.isSyncPrefix(PREFIX + "DEFAULT/"));
        Assert.assertFalse(SchemaSyncTracker.isSyncPrefix("HUGEGRAPH"));
        Assert.assertFalse(SchemaSyncTracker.isSyncPrefix("HUGEGRAPH/hg/SCHEMA/"));
    }

    @Test
    public void testBurstIsCoalesced() {
        Recorder session = register(1L);
        commit(A, 10L);
        at(1030L);
        commit(A, 11L);
        at(1079L);
        Assert.assertTrue(session.events().isEmpty());
        // 50 ms after the last change
        at(1080L);
        Assert.assertEquals(Collections.singletonList(A + "=11"), session.events());
        Assert.assertEquals(1L, this.tracker.notificationCount());
    }

    @Test
    public void testMaxWaitBoundsContinuousChanges() {
        Recorder session = register(1L);
        for (long rev = 1L; rev <= 20L; rev++) {
            commit(A, rev);
            at(this.now + 30L);
            if (!session.events().isEmpty()) {
                break;
            }
        }
        // First change at 1000, never 50 ms quiet: sent at the max wait, 1500
        Assert.assertEquals(1510L, this.now);
        Assert.assertEquals(Collections.singletonList(A + "=17"), session.events());
    }

    @Test
    public void testLateAckDoesNotClearNewerRevision() {
        Recorder session = register(1L);
        commit(A, 10L);
        at(1050L);
        commit(A, 12L);
        at(1100L);
        Assert.assertEquals(List.of(A + "=10", A + "=12"), session.events());

        Assert.assertTrue(this.tracker.ack(1L, A, 10L));
        Assert.assertEquals(1, this.tracker.pendingCount(false));
        Assert.assertTrue(this.tracker.ack(1L, A, 12L));
        Assert.assertEquals(0, this.tracker.pendingCount(false));
        // Done: nothing is resent
        at(10000L);
        Assert.assertEquals(2, session.events().size());
    }

    @Test
    public void testAckOfUnsentRevisionOrUnknownSessionIsIgnored() {
        Recorder session = register(1L);
        commit(A, 10L);
        // Committed, not sent yet
        Assert.assertFalse(this.tracker.ack(1L, A, 10L));
        at(1050L);
        Assert.assertFalse(this.tracker.ack(1L, A, 11L));
        Assert.assertFalse(this.tracker.ack(2L, A, 10L));
        Assert.assertFalse(this.tracker.ack(1L, B, 10L));
        Assert.assertEquals(1, this.tracker.pendingCount(false));
        Assert.assertTrue(this.tracker.ack(1L, A, 10L));
        Assert.assertEquals(1, session.events().size());
    }

    @Test
    public void testRetryThenInvalidate() {
        Recorder session = register(1L);
        Recorder healthy = register(2L);
        commit(A, 10L);
        at(1050L);
        Assert.assertTrue(this.tracker.ack(2L, A, 10L));
        // Resends at +1 s and then +2 s
        at(2049L);
        Assert.assertEquals(1, session.events().size());
        at(2050L);
        at(4050L);
        Assert.assertEquals(3, session.events().size());
        Assert.assertEquals(2L, this.tracker.retryCount());
        Assert.assertTrue(this.closed.isEmpty());

        // 5 s without progress since the change at 1000
        at(6000L);
        Assert.assertEquals(Collections.singletonList(1L), this.closed);
        Assert.assertEquals(1L, this.tracker.invalidationCount(
                SchemaSyncTracker.Reason.RETRY_BUDGET));
        Assert.assertFalse(this.tracker.ack(1L, A, 10L));
        Assert.assertEquals(1, this.tracker.sessionCount());
        Assert.assertEquals(1, healthy.events().size());
    }

    @Test
    public void testAckProgressExtendsBudget() {
        register(1L);
        commit(A, 10L);
        at(1050L);
        at(5000L);
        Assert.assertTrue(this.tracker.ack(1L, A, 10L));
        commit(A, 11L);
        at(5050L);
        // 5 s after the last progress, not after the first change
        at(9900L);
        Assert.assertTrue(this.closed.isEmpty());
        at(10000L);
        Assert.assertEquals(Collections.singletonList(1L), this.closed);
    }

    @Test
    public void testFailedSendClosesOnlyThatSession() {
        Recorder broken = register(1L);
        Recorder healthy = register(2L);
        broken.fail = true;
        commit(A, 10L);
        at(1050L);
        Assert.assertEquals(Collections.singletonList(1L), this.closed);
        Assert.assertEquals(1L, this.tracker.invalidationCount(
                SchemaSyncTracker.Reason.SEND_FAILED));
        Assert.assertEquals(Collections.singletonList(A + "=10"), healthy.events());
    }

    @Test
    public void testHandshakeKeepsChangeCommittedMidway() {
        Recorder session = new Recorder();
        Assert.assertTrue(this.tracker.handshake(PREFIX, 1L, session, prefix -> {
            // Applied after the registration, before the records were read
            commit(B, 21L);
            return Map.of(A, record(20L), B, record(19L),
                          "HUGEGRAPH/other/SCHEMA_SYNC/x", record(5L));
        }));

        List<WatchResponse> frames = session.frames;
        Assert.assertEquals(2, frames.size());
        Assert.assertEquals(WatchState.Synced, frames.get(1).getState());
        List<String> initial = session.events();
        Collections.sort(initial);
        Assert.assertEquals(List.of(A + "=20", B + "=21"), initial);
        Assert.assertEquals(2, this.tracker.pendingCount(false));
        Assert.assertFalse(this.tracker.synced(9L, Map.of()));
    }

    @Test
    public void testFailedHandshakeClosesSession() {
        Recorder session = new Recorder();
        Assert.assertFalse(this.tracker.handshake(PREFIX, 1L, session, prefix -> {
            throw new PDException(-1, "read index timed out");
        }));
        Assert.assertTrue(session.frames.isEmpty());
        Assert.assertEquals(Collections.singletonList(1L), this.closed);
        Assert.assertEquals(0, this.tracker.sessionCount());
        Assert.assertEquals(1L, this.tracker.invalidationCount(
                SchemaSyncTracker.Reason.HANDSHAKE_FAILED));

        // The stream is gone by the time Synced would go out
        Recorder broken = new Recorder();
        broken.fail = true;
        Assert.assertFalse(this.tracker.handshake(PREFIX, 2L, broken, prefix -> Map.of()));
        Assert.assertEquals(List.of(1L, 2L, 2L), this.closed);
        Assert.assertEquals(1L, this.tracker.invalidationCount(
                SchemaSyncTracker.Reason.SEND_FAILED));
    }

    @Test
    public void testKeepaliveRenewsUntilClosed() {
        Recorder session = register(1L);
        at(2999L);
        Assert.assertTrue(session.frames.isEmpty());
        at(3000L);
        Assert.assertEquals(WatchState.Alive, session.frames.get(0).getState());
        Assert.assertEquals(1L, session.frames.get(0).getClientId());
        this.tracker.invalidate(PREFIX, 1L, SchemaSyncTracker.Reason.HANDSHAKE_FAILED);
        at(10000L);
        Assert.assertEquals(1, session.frames.size());
        Assert.assertEquals(Collections.singletonList(1L), this.closed);
    }

    @Test
    public void testLeaderChangeDropsSessions() {
        register(1L);
        commit(A, 10L);
        at(1050L);
        this.tracker.clear();
        Assert.assertEquals(0, this.tracker.sessionCount());
        Assert.assertEquals(0, this.tracker.pendingCount(false));
        Assert.assertFalse(this.tracker.ack(1L, A, 10L));
        // A follower has no sessions: an applied change goes nowhere
        commit(A, 11L);
        Assert.assertEquals(0, this.tracker.pendingCount(true));
    }

    @Test
    public void testDeposedLeaderClosesSessions() {
        Recorder session = register(1L);
        commit(A, 10L);
        // Lost the leadership, no successor announced: close, do not renew or send
        this.leader = false;
        at(5000L);
        Assert.assertTrue(session.frames.isEmpty());
        Assert.assertEquals(Collections.singletonList(1L), this.closed);
        Assert.assertEquals(1L, this.tracker.invalidationCount(
                SchemaSyncTracker.Reason.NOT_LEADER));
    }

    @Test
    public void testDispatchFailureClosesEverySession() {
        // The timer takes the first wake-up, then refuses: the change cannot be scheduled
        ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
        when(timer.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(mock(ScheduledFuture.class))
                .thenThrow(new RejectedExecutionException("timer is down"));
        this.tracker = new SchemaSyncTracker(new PDConfig().new SchemaSync(), timer,
                                             () -> this.now, () -> this.leader,
                                             (prefix, clientId) -> this.closed.add(clientId));
        register(1L);
        register(2L);
        commit(A, 10L);
        Collections.sort(this.closed);
        Assert.assertEquals(List.of(1L, 2L), this.closed);
        Assert.assertEquals(0, this.tracker.sessionCount());
        Assert.assertEquals(2L, this.tracker.invalidationCount(
                SchemaSyncTracker.Reason.DISPATCH_FAILED));
        Assert.assertFalse(this.tracker.ack(1L, A, 10L));
    }

    @Test
    public void testInvalidateClosesUnknownSession() {
        this.tracker.invalidate(PREFIX, 7L, SchemaSyncTracker.Reason.HANDSHAKE_FAILED);
        Assert.assertEquals(Collections.singletonList(7L), this.closed);
        // Nothing was tracked, so nothing is counted
        Assert.assertEquals(0L, this.tracker.invalidationCount(
                SchemaSyncTracker.Reason.HANDSHAKE_FAILED));
    }

    private Recorder register(long clientId) {
        Recorder recorder = new Recorder();
        this.tracker.register(PREFIX, clientId, recorder);
        return recorder;
    }

    private void commit(String key, long revision) {
        this.tracker.onTxnApplied(revision, key, revision, 1L, "LIVE");
    }

    private void at(long time) {
        this.now = time;
        this.tracker.poll();
    }

    private static String record(long revision) {
        return "{\"rev\":" + revision + ",\"inc\":1,\"state\":\"LIVE\"}";
    }

    private static final class Recorder implements StreamObserver<WatchResponse> {

        private final List<WatchResponse> frames = new ArrayList<>();
        private boolean fail;

        @Override
        public void onNext(WatchResponse value) {
            if (this.fail) {
                throw new IllegalStateException("call already closed");
            }
            this.frames.add(value);
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }

        /**
         * Every event sent, as key=rev
         */
        private List<String> events() {
            return this.frames.stream().flatMap(frame -> frame.getEventsList().stream())
                              .map(event -> event.getCurrent().getKey() + "=" +
                                            event.getCurrent().getValue()
                                                 .replaceAll(".*\"rev\":(\\d+).*", "$1"))
                              .collect(Collectors.toList());
        }
    }
}
