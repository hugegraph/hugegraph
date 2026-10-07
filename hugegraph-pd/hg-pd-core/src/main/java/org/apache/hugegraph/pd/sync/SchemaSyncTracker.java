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
import org.apache.hugegraph.pd.config.PDConfig;
import org.apache.hugegraph.pd.grpc.kv.WatchEvent;
import org.apache.hugegraph.pd.grpc.kv.WatchKv;
import org.apache.hugegraph.pd.grpc.kv.WatchResponse;
import org.apache.hugegraph.pd.grpc.kv.WatchState;
import org.apache.hugegraph.pd.grpc.kv.WatchType;
import org.apache.hugegraph.pd.store.TxnApplyListener;

import com.google.gson.JsonParser;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

/**
 * The pending work of the schema sync watches on the leader. A schema sync watch is a
 * watchPrefix on HUGEGRAPH/{cluster}/SCHEMA_SYNC/ (or a narrower prefix below it); each stream
 * is one session, named by its clientId. Per session and graph record key the tracker keeps the
 * latest committed revision, the revision sent and the revision acknowledged, in memory only: a
 * new leader starts with no sessions, and every client reconnects and syncs again.
 * <p>
 * A committed record change reaches {@link #onTxnApplied} on the apply thread of every node;
 * only the leader has sessions, so a follower does nothing. A change is sent once no newer
 * change of its key arrived for the coalescing window, and no later than the maximum wait after
 * the first change that was not sent. An unacknowledged revision is resent with doubling
 * backoff, always as the latest value of the key. A session whose pending work saw no
 * acknowledgment for the retry budget is closed, never treated as done; Alive frames renew a
 * session until then.
 * <p>
 * onNext only queues a frame, so a slow session delays no other. The apply thread takes the
 * tracker lock, so no raft write or ReadIndex ever runs under it; streams are closed outside.
 */
@Slf4j
public class SchemaSyncTracker implements TxnApplyListener {

    private static final Pattern SYNC_PREFIX =
            Pattern.compile("HUGEGRAPH/[^/]+/SCHEMA_SYNC/.*");
    private static final int MAX_BACKOFF_SHIFT = 16;
    /*
     * Revisions of a key sent to a session and not acknowledged yet. One joins each time a newer
     * revision goes out, at most once per coalescing window. A session with this many has
     * fallen too far behind: it is closed and syncs again, rather than have a sent revision
     * forgotten and its ACK rejected.
     */
    private static final int MAX_DELIVERED = 64;

    public enum Reason {
        // Pending work saw no acknowledgment for the retry budget
        RETRY_BUDGET,
        // A frame could not be sent: the stream is gone
        SEND_FAILED,
        // The ReadIndex barrier or the read of the records failed, or a record was unreadable
        HANDSHAKE_FAILED,
        // This node is no longer the leader, and no new leader was announced yet
        NOT_LEADER,
        // A committed change could not be registered for dispatch: every session resyncs
        DISPATCH_FAILED,
        // A record has MAX_DELIVERED revisions sent to the session and not acknowledged
        BACKLOG,
        // A new leader was seen: every session is dropped, and the clients sync with it
        LEADER_CHANGED;

        /**
         * The metric label: the name in lower case, the same in every locale
         */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final PDConfig.SchemaSync config;
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

    private final AtomicLong notifications = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final Map<Reason, AtomicLong> invalidations = new EnumMap<>(Reason.class);

    public SchemaSyncTracker(PDConfig.SchemaSync config, BooleanSupplier leader,
                             BiConsumer<String, Long> closer) {
        this(config, newTimer(), () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), leader,
             closer);
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

    public SchemaSyncTracker(PDConfig.SchemaSync config, ScheduledExecutorService timer,
                             LongSupplier clock, BooleanSupplier leader,
                             BiConsumer<String, Long> closer) {
        // A zero or negative timing would make poll() reschedule itself without pause
        config.validate();
        this.config = config;
        this.timer = timer;
        this.clock = clock;
        this.leader = leader;
        this.closer = closer;
        for (Reason reason : Reason.values()) {
            this.invalidations.put(reason, new AtomicLong());
        }
    }

    public static boolean isSyncPrefix(String prefix) {
        return SYNC_PREFIX.matcher(prefix).matches();
    }

    /**
     * Reads every graph record under a prefix, after a barrier that makes the read see every
     * change committed before the call (a raft ReadIndex)
     */
    public interface RecordReader {

        Map<String, String> read(String prefix) throws PDException;
    }

    /**
     * Starts a session: registers it first and reads the records after, so a change committed
     * meanwhile is in the records read or reaches the session as pending work. Reading first
     * and watching then could lose a change committed in between. A session that does not
     * reach Synced is closed, so the client reconnects and tries again.
     *
     * @return whether the session reached Synced
     */
    public boolean handshake(String prefix, long clientId, StreamObserver<WatchResponse> observer,
                             RecordReader reader) {
        register(prefix, clientId, observer);
        boolean synced = false;
        try {
            synced = synced(clientId, reader.read(prefix));
        } catch (PDException | RuntimeException e) {
            log.warn("Schema sync handshake of client {} on {} failed", clientId, prefix, e);
        }
        if (!synced) {
            invalidate(prefix, clientId, Reason.HANDSHAKE_FAILED);
        }
        return synced;
    }

    /**
     * Handshake step 1: from now on every committed change under the prefix is pending work of
     * the session. A session registered again under the same clientId starts over.
     */
    public synchronized void register(String prefix, long clientId,
                                      StreamObserver<WatchResponse> observer) {
        Session session = new Session(prefix, clientId, observer);
        session.aliveAt = this.clock.getAsLong() + this.config.getKeepaliveInterval();
        this.sessions.put(clientId, session);
        wake(session.aliveAt);
    }

    /**
     * Handshake last step, after the ReadIndex barrier and the read of every record under the
     * prefix: the records join the pending work and go out at once, followed by Synced. A
     * change committed during the handshake is in the records or already pending, and the
     * newer revision wins. A record under the prefix whose revision cannot be read fails the
     * handshake: Synced must not claim a view that lacks it.
     *
     * @return false if the session is gone or was closed
     */
    public boolean synced(long clientId, Map<String, String> records) {
        Session closed;
        synchronized (this) {
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
            closed = remove(clientId, reason);
        }
        close(closed);
        return false;
    }

    /**
     * Ends a session and closes its stream, also when the tracker no longer knows the session;
     * the client reconnects with a new session
     */
    public void invalidate(String prefix, long clientId, Reason reason) {
        synchronized (this) {
            remove(clientId, reason);
        }
        close(prefix, clientId);
    }

    /**
     * On a leader change: the clients reconnect to the new leader and sync again, so no session
     * and no acknowledgment progress outlives the term
     */
    public synchronized void clear() {
        if (!this.sessions.isEmpty()) {
            this.invalidations.get(Reason.LEADER_CHANGED).addAndGet(this.sessions.size());
            log.info("Drop {} schema sync sessions: {}", this.sessions.size(),
                     Reason.LEADER_CHANGED);
            this.sessions.clear();
        }
    }

    @Override
    public void onTxnApplied(long index, String recordKey, long revision, long incarnation,
                             String state) {
        // The format KvTxnApplier stores, so an event and a handshake read look the same
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
        closed.forEach(this::close);
    }

    /**
     * Advances the session's progress on the key. Only a revision sent to this session and not
     * covered by an accepted ACK yet advances it, and covers every revision sent before it; an
     * older one records progress but leaves a newer revision pending. The last accepted
     * revision is confirmed again, as after a lost response, but advances nothing. Any other
     * revision, and an ACK from an unknown session or for a key the session never had, is
     * rejected and changes nothing, so an invented revision cannot keep alive a session that
     * processes nothing.
     *
     * @return whether the ACK matched a sent revision of the session
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
     * Sends what is due, resends what is overdue, renews the sessions and closes the ones out
     * of budget. A deposed leader closes them all rather than renew them: it may never hear
     * of its successor, and receives no commits meanwhile.
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
                    session.aliveAt = now + this.config.getKeepaliveInterval();
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
        closed.forEach(this::close);
    }

    public synchronized int sessionCount() {
        return this.sessions.size();
    }

    /**
     * @param unsent true counts the changes not sent yet, false the ones sent and not
     *               acknowledged
     */
    public synchronized int pendingCount(boolean unsent) {
        int count = 0;
        for (Session session : this.sessions.values()) {
            for (Pending pending : session.pending.values()) {
                if (unsent ? pending.target > pending.sent : pending.target == pending.sent &&
                                                             pending.acked < pending.sent) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * The longest time, in milliseconds, a pending change has waited without acknowledgment
     * progress
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
     * Sends the session's due changes and resends, in one frame
     *
     * @param all send every unsent change, due or not
     * @return why the session must close, or null
     */
    private Reason process(Session session, long now, boolean all) {
        WatchResponse.Builder frame = null;
        for (Map.Entry<String, Pending> entry : session.pending.entrySet()) {
            Pending pending = entry.getValue();
            if (pending.acked < pending.sent &&
                now - pending.since >= this.config.getRetryBudget()) {
                log.warn("Schema sync session {} did not acknowledge {} rev {} in {} ms",
                         session.clientId, entry.getKey(), pending.sent,
                         this.config.getRetryBudget());
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
            long backoff = this.config.getRetryBackoff() <<
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
        if (pending.target > pending.sent) {
            return Math.min(pending.changedAt + this.config.getCoalesceWindow(),
                            pending.unsentSince + this.config.getMaxWait());
        }
        if (pending.acked < pending.sent) {
            return Math.min(pending.retryAt, pending.since + this.config.getRetryBudget());
        }
        return Long.MAX_VALUE;
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

    private void close(Session session) {
        if (session != null) {
            close(session.prefix, session.clientId);
        }
    }

    private void close(String prefix, long clientId) {
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
     * @return the rev of a graph record value, or 0 if it has none
     */
    private static long revisionOf(String value) {
        try {
            return JsonParser.parseString(value).getAsJsonObject().get("rev").getAsLong();
        } catch (RuntimeException e) {
            return 0L;
        }
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
