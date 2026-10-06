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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

    public enum Reason {
        // Pending work saw no acknowledgment for the retry budget
        RETRY_BUDGET,
        // A frame could not be sent: the stream is gone
        SEND_FAILED,
        // The ReadIndex barrier or the read of the records failed
        HANDSHAKE_FAILED,
        // This node is no longer the leader, and no new leader was announced yet
        NOT_LEADER,
        // A committed change could not be registered for dispatch: every session resyncs
        DISPATCH_FAILED
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

    private final AtomicLong notifications = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final Map<Reason, AtomicLong> invalidations = new EnumMap<>(Reason.class);

    public SchemaSyncTracker(PDConfig.SchemaSync config, BooleanSupplier leader,
                             BiConsumer<String, Long> closer) {
        this(config, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pd-schema-sync");
            thread.setDaemon(true);
            return thread;
        }), () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), leader, closer);
    }

    public SchemaSyncTracker(PDConfig.SchemaSync config, ScheduledExecutorService timer,
                             LongSupplier clock, BooleanSupplier leader,
                             BiConsumer<String, Long> closer) {
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
     * newer revision wins.
     *
     * @return false if the session is gone
     */
    public boolean synced(long clientId, Map<String, String> records) {
        Session closed;
        synchronized (this) {
            Session session = this.sessions.get(clientId);
            if (session == null) {
                return false;
            }
            long now = this.clock.getAsLong();
            for (Map.Entry<String, String> record : records.entrySet()) {
                long revision = revisionOf(record.getKey(), record.getValue());
                if (revision > 0L && record.getKey().startsWith(session.prefix)) {
                    offer(session, record.getKey(), revision, record.getValue(), now);
                }
            }
            Reason reason = process(session, now, true);
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
        this.sessions.clear();
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
     * Advances the session's progress on the key. An ACK of a revision never sent, or from an
     * unknown session, is ignored; an older ACK records progress but leaves a newer revision
     * pending.
     *
     * @return whether the ACK matched a sent revision of the session
     */
    public synchronized boolean ack(long clientId, String key, long revision) {
        Session session = this.sessions.get(clientId);
        Pending pending = session == null ? null : session.pending.get(key);
        if (pending == null || revision <= 0L || revision > pending.sent) {
            return false;
        }
        if (revision > pending.acked) {
            pending.acked = revision;
            pending.since = this.clock.getAsLong();
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
                pending.sent = pending.target;
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
        this.wakeAt = at;
        long delay = Math.max(0L, at - this.clock.getAsLong());
        this.timer.schedule(this::poll, delay, TimeUnit.MILLISECONDS);
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

    private static long revisionOf(String key, String value) {
        try {
            return JsonParser.parseString(value).getAsJsonObject().get("rev").getAsLong();
        } catch (RuntimeException e) {
            log.warn("Skip the unreadable graph record {}: {}", key, value);
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
        // When the target last changed, and when it first got ahead of sent
        private long changedAt;
        private long unsentSince;
        // Since when the entry waits for acknowledgment progress
        private long since;
        private long retryAt;
        private int attempts;
    }
}
