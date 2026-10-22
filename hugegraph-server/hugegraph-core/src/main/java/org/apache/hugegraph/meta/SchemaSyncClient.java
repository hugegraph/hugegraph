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

import static org.apache.hugegraph.meta.MetaManager.META_PATH_DELIMITER;
import static org.apache.hugegraph.meta.MetaManager.META_PATH_HUGEGRAPH;
import static org.apache.hugegraph.meta.MetaManager.META_PATH_SCHEMA_SYNC;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.apache.hugegraph.backend.tx.ISchemaTransaction;
import org.apache.hugegraph.exception.HugeException;
import org.apache.hugegraph.pd.grpc.kv.WatchEvent;
import org.apache.hugegraph.pd.grpc.kv.WatchResponse;
import org.apache.hugegraph.util.JsonUtil;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

/**
 * Keeps the schema caches of the HStore graphs open in this JVM in step with their graph
 * records in PD, HUGEGRAPH/{cluster}/SCHEMA_SYNC/{graphspace}/{graph}. One schema sync watch
 * per JVM is one session: PD sends every record, then Synced, then each committed change; a
 * reconnect starts a new session that syncs again.
 * <p>
 * A record newer than what a graph instance applied clears the graph's schema caches under
 * their lock (which advances their generation, see CachedSchemaTransactionV2) and is then
 * acknowledged; an older or duplicate one is acknowledged again without clearing. A failed
 * clear is not acknowledged, so PD sends it again, and the graph is not served until a clear
 * succeeds. The writing Server takes the same path for its own changes. A DROPPED record,
 * another incarnation, or a record missing from the initial sync disables the instance: it is
 * no longer served. A missing record is gone unless the record of the instance arrives later:
 * an instance can open after PD read the records of a session that it still counts as open at
 * the start of that session.
 * <p>
 * Requests are served only while the session is READY. It is SYNCING from Starting to Synced,
 * and STALE once the stream closed or sent nothing for the session timeout, measured on the
 * monotonic clock; a closed session stays STALE until a new one is READY. A session that sent
 * nothing for the session timeout has a stream held open by a PD host that is gone, so the
 * watch is restarted then, and the new session syncs as an initial connection does. A watch that stopped
 * for good is CLOSED until the Server restarts. The gate only stops new requests: one already
 * admitted to a graph runs to its end. A script of a Gremlin session runs on the session's own
 * thread, where no request starts or ends, so each of its graph accesses is checked.
 */
public final class SchemaSyncClient {

    private static final Logger LOG = Log.logger(SchemaSyncClient.class);

    public enum State {
        SYNCING,
        READY,
        STALE,
        // The watch stopped for good after a non-retryable error
        CLOSED
    }

    @FunctionalInterface
    public interface Acker {

        boolean ack(long clientId, String key, long revision);
    }

    // The graphs a Gremlin request of the current thread was admitted to
    private static final ThreadLocal<Set<GraphSync>> REQUEST = new ThreadLocal<>();
    /*
     * The name prefix of the thread a Gremlin session runs its scripts on (TinkerPop's
     * ThreadFactoryUtil.create("session-%d") in op.session.Session). Session scripts don't go
     * through the request pool that marks the start and end of a request.
     */
    private static final String GREMLIN_SESSION_THREAD = "gremlin-server-session-";

    // About three PD keepalives
    private static final long SESSION_TIMEOUT = 15000L;
    private static volatile SchemaSyncClient instance;

    private final String prefix;
    private final Acker acker;
    // Sends the ACKs off the stream's thread, see onFrame()
    private final Executor ackExecutor;
    // Replaces the stream of the watch, see checkExpiry()
    private final Runnable restarter;
    private final LongSupplier nanoClock;
    private final long startedAt;
    // The open graph instances by record key
    private final Map<String, GraphSync> graphs = new ConcurrentHashMap<>();
    // The latest record of each key in the current session, guarded by this
    private final Map<String, Record> records = new HashMap<>();
    private final AtomicLong invalidations = new AtomicLong();

    // The current session, 0 when there is none; guarded by this
    private long clientId;
    // The last session whose watch was restarted; guarded by this
    private long restarted;
    private volatile State state = State.SYNCING;
    private volatile long lastFrameAt;

    SchemaSyncClient(String prefix, Acker acker, LongSupplier nanoClock) {
        this(prefix, acker, () -> { }, nanoClock);
    }

    SchemaSyncClient(String prefix, Acker acker, Runnable restarter, LongSupplier nanoClock) {
        this(prefix, acker, restarter, Runnable::run, nanoClock);
    }

    SchemaSyncClient(String prefix, Acker acker, Runnable restarter, Executor ackExecutor,
                     LongSupplier nanoClock) {
        this.prefix = prefix;
        this.acker = acker;
        this.ackExecutor = ackExecutor;
        this.restarter = restarter;
        this.nanoClock = nanoClock;
        this.startedAt = nanoClock.getAsLong();
        this.lastFrameAt = this.startedAt;
    }

    /**
     * The client of this JVM, which opens the schema sync watch when the first HStore graph
     * opens
     */
    public static synchronized SchemaSyncClient start(MetaManager meta) {
        if (instance == null) {
            PdMetaDriver driver = (PdMetaDriver) meta.metaDriver();
            String prefix = String.join(META_PATH_DELIMITER, META_PATH_HUGEGRAPH,
                                        meta.cluster(), META_PATH_SCHEMA_SYNC) +
                            META_PATH_DELIMITER;
            SchemaSyncClient client = new SchemaSyncClient(prefix, driver::ack,
                                                           () -> driver.restartSync(prefix),
                                                           Executors.newSingleThreadExecutor(
                                                                   daemon("schema-sync-ack")),
                                                           System::nanoTime);
            driver.listenSync(prefix, client::onFrame, client::onSessionClosed,
                              client::onStopped);
            ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(
                    daemon("schema-sync-expiry"));
            expiry.scheduleWithFixedDelay(client::checkExpiry, 1L, 1L, TimeUnit.SECONDS);
            instance = client;
        }
        return instance;
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Fails a request to the graph unless it is served, for REST requests
     */
    public static void check(String graphSpace, String graph) {
        GraphSync sync = lookup(graphSpace, graph);
        if (sync != null) {
            sync.check();
        }
    }

    public static boolean serving(String graphSpace, String graph) {
        GraphSync sync = lookup(graphSpace, graph);
        return sync == null || sync.serving();
    }

    /**
     * Marks the start of a Gremlin request on this thread; its first access to each graph is
     * checked, see {@link GraphSync#admit()}
     */
    public static void beginRequest() {
        // Also before the first HStore graph opens: one can open while the request runs
        REQUEST.set(new HashSet<>());
    }

    public static void endRequest() {
        REQUEST.remove();
    }

    /**
     * The session state, or null while no HStore graph is open in this JVM
     */
    public static State currentState() {
        SchemaSyncClient client = instance;
        return client == null || client.graphs.isEmpty() ? null : client.state();
    }

    public static long invalidationCount() {
        SchemaSyncClient client = instance;
        return client == null ? 0L : client.invalidations.get();
    }

    private static GraphSync lookup(String graphSpace, String graph) {
        SchemaSyncClient client = instance;
        return client == null ? null : client.graphs.get(client.key(graphSpace, graph));
    }

    public State state() {
        State state = this.state;
        long silent = this.nanoClock.getAsLong() - this.lastFrameAt;
        if (state == State.READY && silent > TimeUnit.MILLISECONDS.toNanos(SESSION_TIMEOUT)) {
            // Expired: checkExpiry() restarts the watch, a new session is READY after Synced
            return State.STALE;
        }
        return state;
    }

    /**
     * Restarts the watch once its session sent nothing for the session timeout. PD sends
     * Alive every 5 s, so the stream is held open by a PD host that lost power or was cut
     * off, and no error or completion would arrive until TCP gives up, hours later. Runs
     * once a second; each session is restarted once.
     */
    void checkExpiry() {
        long session;
        synchronized (this) {
            session = this.clientId;
            long silent = this.nanoClock.getAsLong() - this.lastFrameAt;
            if (session == 0L || session == this.restarted || this.state == State.CLOSED ||
                silent <= TimeUnit.MILLISECONDS.toNanos(SESSION_TIMEOUT)) {
                return;
            }
            this.restarted = session;
        }
        LOG.warn("Schema sync session {} sent nothing for {} ms, restart the watch",
                 session, SESSION_TIMEOUT);
        try {
            this.restarter.run();
        } catch (RuntimeException e) {
            LOG.warn("Failed to restart the schema sync watch, retry in a second", e);
            synchronized (this) {
                if (this.restarted == session) {
                    this.restarted = 0L;
                }
            }
        }
    }

    /**
     * Tracks an opened graph instance, after it read (or created) its record. Its caches are
     * cleared first, as an earlier instance of the graph may have left entries, which applies
     * the newer of that record and a change this session acknowledged before. The first
     * graph waits up to the session timeout for the initial sync, so the startup of the
     * Server finds its graphs served.
     */
    public GraphSync register(String graphSpace, String graph, Record record,
                              Runnable invalidator) {
        String key = this.key(graphSpace, graph);
        GraphSync sync = new GraphSync(this, graphSpace + "/" + graph, key,
                                       record.incarnation, record.revision, invalidator);
        synchronized (this) {
            // Under the lock of the client: no record is processed between the clear and put
            invalidator.run();
            this.graphs.put(key, sync);
            Record seen = this.records.get(key);
            // A change this session acknowledged before the instance was tracked
            Record applied = seen != null && seen.revision > record.revision ? seen : record;
            sync.appliedRevision = applied.revision;
            this.live(sync, applied);
            long deadline = this.startedAt + TimeUnit.MILLISECONDS.toNanos(SESSION_TIMEOUT);
            long left;
            while (this.state == State.SYNCING &&
                   (left = deadline - this.nanoClock.getAsLong()) > 0L) {
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return sync;
    }

    void onFrame(WatchResponse frame) {
        long session = frame.getClientId();
        Map<String, Long> acks = new LinkedHashMap<>();
        synchronized (this) {
            switch (frame.getState()) {
                case Starting:
                    this.startSession(session);
                    break;
                case Started:
                    if (this.current(session)) {
                        for (WatchEvent event : frame.getEventsList()) {
                            String key = event.getCurrent().getKey();
                            Record record = this.process(key, event.getCurrent().getValue());
                            if (record != null) {
                                acks.put(key, record.revision);
                            }
                        }
                    }
                    break;
                case Synced:
                    if (this.current(session)) {
                        this.synced();
                    }
                    break;
                default:
                    // Alive renews the session
                    this.current(session);
                    break;
            }
        }
        /*
         * Outside the lock and off the stream's thread: an ACK to a PD that stopped answering
         * waits for its deadline, and the frames behind it, Synced and Alive, must not
         */
        if (!acks.isEmpty()) {
            this.ackExecutor.execute(() -> {
                for (Map.Entry<String, Long> ack : acks.entrySet()) {
                    // PD ignores an ACK of a session that ended: don't wait on one in an outage
                    if (this.session() != session) {
                        return;
                    }
                    this.ack(session, ack.getKey(), ack.getValue());
                }
            });
        }
    }

    private synchronized long session() {
        return this.clientId;
    }

    synchronized void onSessionClosed(long session) {
        if (session != this.clientId) {
            return;
        }
        this.clientId = 0L;
        if (this.state != State.CLOSED) {
            this.state = State.STALE;
        }
        LOG.warn("Schema sync session {} closed, the HStore graphs are not served until a " +
                 "new session synced", session);
    }

    synchronized void onStopped(Throwable cause) {
        this.clientId = 0L;
        this.state = State.CLOSED;
        // No Synced will come: release the opens that wait for it in register()
        this.notifyAll();
        LOG.error("The schema sync watch stopped, the HStore graphs are no longer served",
                  cause);
    }

    private String key(String graphSpace, String graph) {
        return this.prefix + graphSpace + META_PATH_DELIMITER + graph;
    }

    private void startSession(long session) {
        this.clientId = session;
        this.state = State.SYNCING;
        this.lastFrameAt = this.nanoClock.getAsLong();
        this.records.clear();
        for (GraphSync sync : this.graphs.values()) {
            sync.presentAtStart = true;
        }
        LOG.info("Schema sync session {} started", session);
    }

    private boolean current(long session) {
        if (session == 0L || session != this.clientId) {
            LOG.debug("Ignore a frame of the ended schema sync session {}", session);
            return false;
        }
        this.lastFrameAt = this.nanoClock.getAsLong();
        return true;
    }

    /**
     * @return the record once it is processed and may be acknowledged, else null
     */
    private Record process(String key, String value) {
        try {
            Record record = Record.parse(value);
            Record seen = this.records.get(key);
            if (seen == null || record.revision >= seen.revision) {
                this.records.put(key, record);
            }
            GraphSync sync = this.graphs.get(key);
            /*
             * A record older than what the instance applied changes nothing of it: it can be
             * of an earlier incarnation, still pending in the session when a new instance
             * opened with its own, newer record. Revisions grow across incarnations.
             */
            if (sync != null && record.revision >= sync.appliedRevision &&
                this.live(sync, record) && record.revision > sync.appliedRevision) {
                this.invalidate(sync, record.revision);
            }
            return record;
        } catch (RuntimeException e) {
            LOG.warn("Failed to process the schema sync record {} = {}, not acknowledged",
                     key, value, e);
            return null;
        }
    }

    private void invalidate(GraphSync sync, long revision) {
        try {
            // Under the cache lock, which also advances the cache generation
            sync.invalidator.run();
        } catch (RuntimeException e) {
            // Not served until a clear succeeds, on a resend or in a new session
            sync.uncleared = revision;
            throw e;
        }
        sync.uncleared = 0L;
        sync.appliedRevision = revision;
        this.invalidations.incrementAndGet();
    }

    private void synced() {
        for (GraphSync sync : this.graphs.values()) {
            if (sync.presentAtStart && sync.disabled == null &&
                !this.records.containsKey(sync.key)) {
                /*
                 * Missing is not "nothing to sync": the graph is gone. Unless the instance
                 * opened after PD read the records but before Starting got here: then its
                 * record was committed after the read, and arrives later in the session.
                 */
                sync.disable("its schema sync record is gone");
                sync.absent = true;
            }
            sync.presentAtStart = false;
        }
        this.state = State.READY;
        this.notifyAll();
        LOG.info("Schema sync session {} is ready", this.clientId);
    }

    private boolean live(GraphSync sync, Record record) {
        if (sync.absent) {
            if (record.dropped || record.incarnation != sync.incarnation) {
                // Gone for good, whatever comes after
                sync.absent = false;
            } else if (record.revision >= sync.openRevision) {
                // Not gone: its record was committed after PD read the records of the session
                sync.enable();
            }
        }
        if (record.dropped) {
            sync.disable("the graph was dropped");
        } else if (record.incarnation != sync.incarnation) {
            sync.disable("the graph was recreated as incarnation " + record.incarnation);
        }
        return sync.disabled == null;
    }

    private void ack(long session, String key, long revision) {
        try {
            if (!this.acker.ack(session, key, revision)) {
                LOG.debug("PD ignored the ACK of {} rev {} in session {}",
                          key, revision, session);
            }
        } catch (RuntimeException e) {
            LOG.warn("Failed to acknowledge {} rev {}, PD will resend it", key, revision, e);
        }
    }

    /**
     * The sync state of one open graph instance
     */
    public static final class GraphSync {

        private final SchemaSyncClient client;
        private final String name;
        private final String key;
        private final long incarnation;
        // The revision of the record the instance opened with
        private final long openRevision;
        private final Runnable invalidator;
        private volatile long appliedRevision;
        // The revision whose clear failed, 0 when the last clear succeeded
        private volatile long uncleared;
        private volatile String disabled;
        // Open when the current session started, guarded by the client
        private boolean presentAtStart;
        // Disabled only because an initial sync missed its record, guarded by the client
        private boolean absent;

        private GraphSync(SchemaSyncClient client, String name, String key, long incarnation,
                          long openRevision, Runnable invalidator) {
            this.client = client;
            this.name = name;
            this.key = key;
            this.incarnation = incarnation;
            this.openRevision = openRevision;
            this.invalidator = invalidator;
        }

        public boolean serving() {
            return this.disabled == null && this.uncleared == 0L &&
                   this.client.state() == State.READY;
        }

        public void check() {
            String disabled = this.disabled;
            if (disabled != null) {
                throw new SyncingException("Graph '%s' is no longer served: %s",
                                           this.name, disabled);
            }
            State state = this.client.state();
            if (state == State.CLOSED) {
                throw new SyncingException("The schema sync watch of this Server stopped, " +
                                           "graph '%s' is not served until the Server " +
                                           "restarts", this.name);
            }
            if (state != State.READY) {
                throw new SyncingException("The schema of graph '%s' is syncing with PD " +
                                           "(%s), retry later", this.name, state);
            }
            long uncleared = this.uncleared;
            if (uncleared != 0L) {
                throw new SyncingException("The schema caches of graph '%s' could not be " +
                                           "cleared for revision %s, retry later",
                                           this.name, uncleared);
            }
        }

        /**
         * Checks the first access of a Gremlin request to the graph; later accesses of an
         * admitted request are in flight and not interrupted
         */
        public void admit() {
            Set<GraphSync> request = REQUEST.get();
            if (request == null) {
                if (Thread.currentThread().getName().startsWith(GREMLIN_SESSION_THREAD)) {
                    // A session script: no request boundary here, so every access is checked
                    this.check();
                }
                return;
            }
            if (!request.contains(this)) {
                this.check();
                request.add(this);
            }
        }

        /**
         * The schema transaction as a SchemaManager gets it. A Gremlin session can keep
         * graph.schema() across scripts, and the lookups and builders of the manager call its
         * transaction directly, so each call is admitted here as a graph access is: checked on
         * a session's thread, once per request in a Gremlin request, not at all elsewhere.
         */
        public ISchemaTransaction admitted(ISchemaTransaction transaction) {
            return (ISchemaTransaction) Proxy.newProxyInstance(
                    ISchemaTransaction.class.getClassLoader(),
                    new Class<?>[]{ISchemaTransaction.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() != Object.class) {
                            this.admit();
                        }
                        try {
                            return method.invoke(transaction, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        public long appliedRevision() {
            return this.appliedRevision;
        }

        public void close() {
            this.client.graphs.remove(this.key, this);
        }

        private void enable() {
            this.absent = false;
            this.disabled = null;
            LOG.info("Graph '{}' is served again: its schema sync record arrived", this.name);
        }

        private void disable(String reason) {
            if (this.disabled != null) {
                return;
            }
            this.disabled = reason;
            LOG.warn("Graph '{}' is no longer served: {}", this.name, reason);
            try {
                this.invalidator.run();
            } catch (RuntimeException e) {
                LOG.warn("Failed to clear the schema caches of graph '{}'", this.name, e);
            }
        }
    }

    /**
     * A graph record value, {"rev":<n>,"inc":<n>,"state":"LIVE"|"DROPPED"}
     */
    public static final class Record {

        private final long revision;
        private final long incarnation;
        private final boolean dropped;

        public Record(long revision, long incarnation, String state) {
            if (!"LIVE".equals(state) && !"DROPPED".equals(state)) {
                throw new HugeException("Unknown schema sync record state '%s'", state);
            }
            this.revision = revision;
            this.incarnation = incarnation;
            this.dropped = "DROPPED".equals(state);
        }

        /**
         * The record as PD's applier and tracker read it: rev and inc positive integers,
         * state LIVE or DROPPED. Anything else is unreadable, and a graph
         * opened with it could never sync, since PD rejects every commit and handshake on it.
         */
        @SuppressWarnings("unchecked")
        public static Record parse(String value) {
            Map<String, Object> map;
            try {
                map = JsonUtil.fromJson(value, Map.class);
            } catch (RuntimeException e) {
                throw new HugeException("Unreadable schema sync record '%s'", e, value);
            }
            Long rev = map == null ? null : integral(map.get("rev"));
            Long inc = map == null ? null : integral(map.get("inc"));
            Object state = map == null ? null : map.get("state");
            if (rev == null || rev <= 0L || inc == null || inc <= 0L ||
                !("LIVE".equals(state) || "DROPPED".equals(state))) {
                throw new HugeException("Unreadable schema sync record '%s'", value);
            }
            return new Record(rev, inc, (String) state);
        }

        private static Long integral(Object number) {
            if (number instanceof Integer || number instanceof Long) {
                return ((Number) number).longValue();
            }
            if (number instanceof Number) {
                double value = ((Number) number).doubleValue();
                if (value == Math.rint(value) && Math.abs(value) < 0x1p53) {
                    return (long) value;
                }
            }
            return null;
        }

        public long revision() {
            return this.revision;
        }

        public long incarnation() {
            return this.incarnation;
        }

        public boolean dropped() {
            return this.dropped;
        }
    }

    /**
     * A request to a graph that is not served now. The REST API answers 503, Gremlin
     * (/gremlin) and Cypher requests through it included. The Gremlin Server's own endpoints
     * can't tell it from another script failure: over WebSocket the response has status
     * SERVER_ERROR (500) and this class in its "exceptions" attribute, and its HTTP endpoint
     * answers 500 with this class in "Exception-Class"; a client retries on that.
     */
    public static class SyncingException extends HugeException {

        private static final long serialVersionUID = 4609836219472396417L;

        public SyncingException(String message, Object... args) {
            super(message, args);
        }
    }
}
