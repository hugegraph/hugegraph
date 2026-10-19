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

package org.apache.hugegraph.pd.sync;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import org.apache.hugegraph.pd.common.PDException;
import org.apache.hugegraph.pd.grpc.kv.WatchEvent;
import org.apache.hugegraph.pd.grpc.kv.WatchKv;
import org.apache.hugegraph.pd.grpc.kv.WatchResponse;
import org.apache.hugegraph.pd.grpc.kv.WatchState;
import org.apache.hugegraph.pd.grpc.kv.WatchType;
import org.apache.hugegraph.pd.store.KvTxnApplier;
import org.apache.hugegraph.pd.store.TxnApplyListener;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

/**
 * The pending work of the schema sync watches on the leader: a watchPrefix on
 * HUGEGRAPH/{cluster}/SCHEMA_SYNC/ is a session, named by its clientId. Per session and graph
 * record key the tracker keeps the latest committed revision, the revision sent and the
 * revision acknowledged, in leader memory only; a new leader starts with no sessions. A change
 * is sent once no newer change of its key arrived for the coalescing window and no later than
 * the max wait, resent with doubling backoff until acknowledged, and a session without
 * acknowledgment progress for the retry budget is closed, never treated as done. No raft write
 * or ReadIndex runs under the tracker lock; streams are closed outside it.
 */
@Slf4j
public class SchemaSyncTracker implements TxnApplyListener {

    private static final Pattern SYNC_PREFIX =
            Pattern.compile("HUGEGRAPH/[^/]+/SCHEMA_SYNC/.*");
    private static final int MAX_BACKOFF_SHIFT = 16;
    // Unacknowledged revisions sent to a session for one key; with this many the session is
    // closed and syncs again, rather than have a sent revision forgotten and its ACK rejected
    private static final int MAX_DELIVERED = 64;

    /**
     * Why a session was closed: the metric label is the name in lower case, in every locale
     */
    public enum Reason {
        RETRY_BUDGET,     // No acknowledgment progress for the retry budget
        SEND_FAILED,      // A frame could not be sent: the stream is gone
        HANDSHAKE_FAILED, // The ReadIndex, the read of the records or a record failed
        NOT_LEADER,       // No longer the leader, and no new leader announced yet
        DISPATCH_FAILED,  // A committed change could not be registered: every session resyncs
        BACKLOG,          // MAX_DELIVERED revisions of one key sent and not acknowledged
        LEADER_CHANGED;   // A new leader was seen: the clients sync with it

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The timings of delivery in milliseconds, fixed in this release; a Server treats about three
     * missed keepalives as the expiry of its session
     */
    public static final class Timings {

        public static final Timings DEFAULT = new Timings(50L, 500L, 1000L, 30000L, 5000L);

        private final long coalesceWindow;
        private final long maxWait;
        private final long retryBackoff;
        private final long retryBudget;
        private final long keepaliveInterval;

        public Timings(long coalesceWindow, long maxWait, long retryBackoff, long retryBudget,
                       long keepaliveInterval) {
            this.coalesceWindow = coalesceWindow;
            this.maxWait = maxWait;
            this.retryBackoff = retryBackoff;
            this.retryBudget = retryBudget;
            this.keepaliveInterval = keepaliveInterval;
        }
    }

    /**
     * Reads every graph record under a prefix after a raft ReadIndex barrier
     */
    public interface RecordReader {

        Map<String, String> read(String prefix) throws PDException;
    }

    private final Timings timings;
    // Null: the caller runs poll(), as the tests do
    private final ScheduledExecutorService timer;
    private final LongSupplier clock;
    private final BooleanSupplier leader;
    // Ends the watch stream of (prefix, clientId); called outside the lock
    private final BiConsumer<String, Long> closer;
    private final Map<Long, Session> sessions = new HashMap<>();
    private long wakeAt = Long.MAX_VALUE;
    // The one scheduled poll(); an earlier one is cancelled when a sooner wake-up replaces it
    private ScheduledFuture<?> wakeTask;
    // Set by stop(): no session is taken and no stream is closed from then on
    private volatile boolean stopped;

    private final AtomicLong notifications = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final Map<Reason, AtomicLong> invalidations = new EnumMap<>(Reason.class);

    public SchemaSyncTracker(BooleanSupplier leader, BiConsumer<String, Long> closer) {
        this(Timings.DEFAULT, leader, closer);
    }

    public SchemaSyncTracker(Timings timings, BooleanSupplier leader,
                             BiConsumer<String, Long> closer) {
        this(timings, newTimer(), () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()),
             leader, closer);
    }

    public SchemaSyncTracker(Timings timings, ScheduledExecutorService timer, LongSupplier clock,
                             BooleanSupplier leader, BiConsumer<String, Long> closer) {
        this.timings = timings;
        this.timer = timer;
        this.clock = clock;
        this.leader = leader;
        this.closer = closer;
        for (Reason reason : Reason.values()) {
            this.invalidations.put(reason, new AtomicLong());
        }
    }

    private static ScheduledExecutorService newTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "pd-schema-sync");
            thread.setDaemon(true);
            return thread;
        });
        // A replaced wake-up leaves the queue at once
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    public static boolean isSyncPrefix(String prefix) {
        return SYNC_PREFIX.matcher(prefix).matches();
    }

    /**
     * Starts a session: registers it first and reads the records after, so a change committed
     * meanwhile is in the records read or pending. A session that does not reach Synced is
     * closed, once, and the client tries again.
     */
    public boolean handshake(String prefix, long clientId, StreamObserver<WatchResponse> observer,
                             RecordReader reader) {
        boolean synced = false;
        try {
            register(prefix, clientId, observer);
            synced = synced(clientId, reader.read(prefix));
        } catch (PDException | RuntimeException e) {
            log.warn("Schema sync handshake of client {} on {} failed", clientId, prefix, e);
        }
        if (!synced) {
            // The only close of a failed handshake: synced() leaves the stream to this call
            invalidate(prefix, clientId, Reason.HANDSHAKE_FAILED);
        }
        return synced;
    }

    /**
     * Handshake step 1: from now on every committed change under the prefix is pending work
     */
    public synchronized void register(String prefix, long clientId,
                                      StreamObserver<WatchResponse> observer) {
        if (this.stopped) {
            // The timer is down: a session taken now would never be polled
            return;
        }
        Session session = new Session(prefix, clientId, observer);
        session.aliveAt = this.clock.getAsLong() + this.timings.keepaliveInterval;
        this.sessions.put(clientId, session);
        wake(session.aliveAt);
    }

    /**
     * Handshake last step: the records read after the barrier join the pending work and go out
     * at once, then Synced. An unreadable record fails the handshake: Synced must not claim a
     * view that lacks it. A failed session is removed here and closed by handshake(), once.
     */
    public synchronized boolean synced(long clientId, Map<String, String> records) {
        Session session = this.sessions.get(clientId);
        if (session == null) {
            return false;
        }
        long now = this.clock.getAsLong();
        Reason reason = null;
        for (Map.Entry<String, String> record : records.entrySet()) {
            String key = record.getKey();
            if (!key.startsWith(session.prefix)) {
                continue;
            }
            long revision = revisionOf(record.getValue());
            if (revision <= 0L) {
                log.warn("Schema sync handshake of client {} read the unreadable graph " +
                         "record {}: {}", clientId, key, record.getValue());
                reason = Reason.HANDSHAKE_FAILED;
                break;
            }
            offer(session, key, revision, record.getValue(), now);
        }
        if (reason == null) {
            reason = process(session, now, true);
        }
        if (reason == null && !send(session, frame(clientId, WatchState.Synced))) {
            reason = Reason.SEND_FAILED;
        }
        if (reason == null) {
            wake(nextDue(session));
            return true;
        }
        remove(clientId, reason);
        return false;
    }

    /**
     * Ends a session and closes its stream, also when the tracker no longer knows it
     */
    public void invalidate(String prefix, long clientId, Reason reason) {
        synchronized (this) {
            remove(clientId, reason);
        }
        close(prefix, clientId);
    }

    /**
     * On a leader change: no session and no acknowledgment progress outlives the term
     */
    public synchronized void clear() {
        if (!this.sessions.isEmpty()) {
            this.invalidations.get(Reason.LEADER_CHANGED).addAndGet(this.sessions.size());
            log.info("Drop {} schema sync sessions: {}", this.sessions.size(),
                     Reason.LEADER_CHANGED);
            this.sessions.clear();
        }
    }

    /**
     * On shutdown, after the gRPC server ended every stream: drops the sessions without a close
     * (which deletes a key through raft, about to go down) and stops the timer
     */
    public void stop() {
        synchronized (this) {
            this.stopped = true;
            if (!this.sessions.isEmpty()) {
                log.info("Drop {} schema sync sessions: PD is stopping", this.sessions.size());
                this.sessions.clear();
            }
        }
        if (this.timer != null) {
            this.timer.shutdownNow();
        }
    }

    @Override
    public void onTxnApplied(long index, String recordKey, long revision, long incarnation,
                             String state) {
        /*
         * The format KvTxnApplier stores, without the origin mark of a CREATE's record, which
         * a handshake read carries and the receiver ignores
         */
        String value = "{\"rev\":" + revision + ",\"inc\":" + incarnation + ",\"state\":\"" +
                       state + "\"}";
        List<Session> closed = new ArrayList<>();
        synchronized (this) {
            try {
                long now = this.clock.getAsLong();
                for (Session session : this.sessions.values()) {
                    if (recordKey.startsWith(session.prefix)) {
                        Pending pending = offer(session, recordKey, revision, value, now);
                        wake(due(pending));
                    }
                }
                return;
            } catch (RuntimeException e) {
                // A change that is committed but not pending could be lost for good: make
                // every session resync through a new handshake instead
                log.error("Failed to register {} rev {} for dispatch, close every schema " +
                          "sync session", recordKey, revision, e);
                for (Long clientId : new ArrayList<>(this.sessions.keySet())) {
                    closed.add(remove(clientId, Reason.DISPATCH_FAILED));
                }
            }
        }
        closed.forEach(session -> close(session.prefix, session.clientId));
    }

    /**
     * Advances the session's progress on the key: only a revision sent to this session and not
     * covered yet does, and it covers every revision sent before it; the last accepted one is
     * confirmed again after a lost response; anything else is rejected and changes nothing
     */
    public synchronized boolean ack(long clientId, String key, long revision) {
        Session session = this.sessions.get(clientId);
        Pending pending = session == null ? null : session.pending.get(key);
        if (pending == null || revision <= 0L) {
            return false;
        }
        if (revision == pending.acked) {
            return true;
        }
        if (!pending.delivered.contains(revision)) {
            return false;
        }
        pending.acked = revision;
        pending.since = this.clock.getAsLong();
        while (!pending.delivered.isEmpty() && pending.delivered.peekFirst() <= revision) {
            pending.delivered.pollFirst();
        }
        return true;
    }

    /**
     * Sends what is due, resends what is overdue, renews the sessions and closes the ones out of
     * budget; a deposed leader closes them all, since it receives no commits
     */
    public void poll() {
        // Outside the lock: it may wait on the raft node
        boolean isLeader = this.leader.getAsBoolean();
        List<Session> closed = new ArrayList<>();
        synchronized (this) {
            this.wakeAt = Long.MAX_VALUE;
            long now = this.clock.getAsLong();
            for (Session session : new ArrayList<>(this.sessions.values())) {
                Reason reason = isLeader ? process(session, now, false) : Reason.NOT_LEADER;
                if (reason == null && now >= session.aliveAt) {
                    session.aliveAt = now + this.timings.keepaliveInterval;
                    if (!send(session, frame(session.clientId, WatchState.Alive))) {
                        reason = Reason.SEND_FAILED;
                    }
                }
                if (reason == null) {
                    wake(nextDue(session));
                } else {
                    closed.add(remove(session.clientId, reason));
                }
            }
        }
        closed.forEach(session -> close(session.prefix, session.clientId));
    }

    public synchronized int sessionCount() {
        return this.sessions.size();
    }

    /**
     * @param unsent true counts the changes not sent yet, false the ones sent and not acked
     */
    public synchronized int pendingCount(boolean unsent) {
        int count = 0;
        for (Session session : this.sessions.values()) {
            for (Pending pending : session.pending.values()) {
                if (unsent ? pending.target > pending.sent : pending.acked < pending.sent) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * The longest wait of a pending change for acknowledgment progress, in milliseconds
     */
    public synchronized long oldestPendingAge() {
        long now = this.clock.getAsLong();
        long oldest = 0L;
        for (Session session : this.sessions.values()) {
            for (Pending pending : session.pending.values()) {
                if (pending.target > pending.acked) {
                    oldest = Math.max(oldest, now - pending.since);
                }
            }
        }
        return oldest;
    }

    public long notificationCount() {
        return this.notifications.get();
    }

    public long retryCount() {
        return this.retries.get();
    }

    public long invalidationCount(Reason reason) {
        return this.invalidations.get(reason).get();
    }

    private Pending offer(Session session, String key, long revision, String value, long now) {
        Pending pending = session.pending.computeIfAbsent(key, k -> new Pending());
        if (revision <= pending.target) {
            return pending;
        }
        if (pending.target == pending.acked) {
            // Was complete: the wait for progress starts now
            pending.since = now;
        }
        if (pending.target == pending.sent) {
            pending.unsentSince = now;
        }
        pending.target = revision;
        pending.value = value;
        pending.changedAt = now;
        return pending;
    }

    /**
     * Sends the session's due changes and resends in one frame (all: every unsent change, due
     * or not); returns why the session must close, or null
     */
    private Reason process(Session session, long now, boolean all) {
        WatchResponse.Builder frame = null;
        for (Map.Entry<String, Pending> entry : session.pending.entrySet()) {
            Pending pending = entry.getValue();
            if (pending.acked < pending.sent &&
                now - pending.since >= this.timings.retryBudget) {
                log.warn("Schema sync session {} did not acknowledge {} rev {} in {} ms",
                         session.clientId, entry.getKey(), pending.sent,
                         this.timings.retryBudget);
                return Reason.RETRY_BUDGET;
            }
            if (pending.target > pending.sent && (all || due(pending) <= now)) {
                if (pending.delivered.size() >= MAX_DELIVERED) {
                    log.warn("Schema sync session {} did not acknowledge {} revisions of {}",
                             session.clientId, pending.delivered.size(), entry.getKey());
                    return Reason.BACKLOG;
                }
                pending.sent = pending.target;
                pending.delivered.addLast(pending.sent);
                pending.attempts = 0;
                this.notifications.incrementAndGet();
            } else if (pending.target == pending.sent && pending.acked < pending.sent &&
                       pending.retryAt <= now) {
                pending.attempts++;
                this.retries.incrementAndGet();
            } else {
                continue;
            }
            long backoff = this.timings.retryBackoff <<
                           Math.min(pending.attempts, MAX_BACKOFF_SHIFT);
            pending.retryAt = now + backoff;
            if (frame == null) {
                frame = WatchResponse.newBuilder().setState(WatchState.Started)
                                     .setClientId(session.clientId);
            }
            WatchKv kv = WatchKv.newBuilder().setKey(entry.getKey()).setValue(pending.value)
                                .build();
            frame.addEvents(WatchEvent.newBuilder().setType(WatchType.Put).setCurrent(kv));
        }
        if (frame != null && !send(session, frame.build())) {
            return Reason.SEND_FAILED;
        }
        return null;
    }

    private long due(Pending pending) {
        long due = Long.MAX_VALUE;
        if (pending.target > pending.sent) {
            due = Math.min(pending.changedAt + this.timings.coalesceWindow,
                           pending.unsentSince + this.timings.maxWait);
        }
        if (pending.acked < pending.sent) {
            // Also while a newer change waits in its window: the resend and the budget of the
            // revision sent stay due, and the resend carries that change
            due = Math.min(due, Math.min(pending.retryAt,
                                         pending.since + this.timings.retryBudget));
        }
        return due;
    }

    private long nextDue(Session session) {
        long next = session.aliveAt;
        for (Pending pending : session.pending.values()) {
            next = Math.min(next, due(pending));
        }
        return next;
    }

    private void wake(long at) {
        if (this.timer == null || at >= this.wakeAt) {
            return;
        }
        long delay = Math.max(0L, at - this.clock.getAsLong());
        ScheduledFuture<?> task = this.timer.schedule(this::poll, delay, TimeUnit.MILLISECONDS);
        // Otherwise the replaced task would run too, and start a second chain of polls
        if (this.wakeTask != null) {
            this.wakeTask.cancel(false);
        }
        this.wakeTask = task;
        this.wakeAt = at;
    }

    private Session remove(long clientId, Reason reason) {
        Session session = this.sessions.remove(clientId);
        if (session != null) {
            this.invalidations.get(reason).incrementAndGet();
            log.info("Close schema sync session {} on {}: {}", clientId, session.prefix, reason);
        }
        return session;
    }

    private void close(String prefix, long clientId) {
        if (this.stopped) {
            return;
        }
        try {
            this.closer.accept(prefix, clientId);
        } catch (RuntimeException e) {
            log.warn("Failed to close schema sync session {}", clientId, e);
        }
    }

    private static boolean send(Session session, WatchResponse frame) {
        try {
            synchronized (session.observer) {
                session.observer.onNext(frame);
            }
            return true;
        } catch (RuntimeException e) {
            log.info("Failed to send to schema sync session {}: {}", session.clientId,
                     e.toString());
            return false;
        }
    }

    private static WatchResponse frame(long clientId, WatchState state) {
        return WatchResponse.newBuilder().setState(state).setClientId(clientId).build();
    }

    /**
     * The rev of a record value, or 0 if a TXN would not accept it: rev and inc positive
     * integers, state LIVE or DROPPED, as KvTxnApplier and the Server read it
     */
    private static long revisionOf(String value) {
        try {
            JsonObject json = JsonParser.parseString(value).getAsJsonObject();
            JsonPrimitive rev = json.getAsJsonPrimitive("rev");
            JsonPrimitive inc = json.getAsJsonPrimitive("inc");
            JsonPrimitive state = json.getAsJsonPrimitive("state");
            String graphState = state.isString() ? state.getAsString() : null;
            if (rev.isNumber() && inc.isNumber() &&
                inc.getAsBigDecimal().longValueExact() > 0L &&
                (KvTxnApplier.LIVE.equals(graphState) ||
                 KvTxnApplier.DROPPED.equals(graphState))) {
                return rev.getAsBigDecimal().longValueExact();
            }
        } catch (RuntimeException e) {
            // Not a JSON object, a field missing or not a primitive, or not a long
        }
        return 0L;
    }

    private static final class Session {

        private final String prefix;
        private final long clientId;
        private final StreamObserver<WatchResponse> observer;
        // One entry per record key the session has seen; complete when acked reaches target
        private final Map<String, Pending> pending = new HashMap<>();
        private long aliveAt;

        private Session(String prefix, long clientId, StreamObserver<WatchResponse> observer) {
            this.prefix = prefix;
            this.clientId = clientId;
            this.observer = observer;
        }
    }

    private static final class Pending {

        // Latest committed revision and its record value
        private long target;
        private String value;
        private long sent;
        private long acked;
        // The revisions sent above acked, ascending: the only ones an ACK may name
        private final Deque<Long> delivered = new ArrayDeque<>();
        // When the target last changed, and when it first got ahead of sent
        private long changedAt;
        private long unsentSince;
        // Since when the entry waits for acknowledgment progress
        private long since;
        private long retryAt;
        private int attempts;
    }
}
