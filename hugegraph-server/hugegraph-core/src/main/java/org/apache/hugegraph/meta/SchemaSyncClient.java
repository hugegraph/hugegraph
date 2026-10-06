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

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.apache.hugegraph.HugeException;
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
 * clear is not acknowledged, so PD sends it again. The writing Server takes the same path for
 * its own changes. A DROPPED record, another incarnation, or a record missing from the initial
 * sync disables the instance: it is no longer served.
 * <p>
 * Requests are served only while the session is READY. It is SYNCING from Starting to Synced,
 * and STALE once the stream closed or sent nothing for the session timeout, measured on the
 * monotonic clock; a closed session stays STALE until a new one is READY. The gate only stops
 * new requests: one already admitted to a graph runs to its end.
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

    private static volatile long sessionTimeout = 15000L;
    private static volatile SchemaSyncClient instance;

    private final String prefix;
    private final Acker acker;
    private final LongSupplier nanoClock;
    private final long startedAt;
    // The open graph instances by record key
    private final Map<String, GraphSync> graphs = new ConcurrentHashMap<>();
    // The latest record of each key in the current session, guarded by this
    private final Map<String, Record> records = new HashMap<>();
    private final AtomicLong invalidations = new AtomicLong();

    // The current session, 0 when there is none; guarded by this
    private long clientId;
    private volatile State state = State.SYNCING;
    private volatile long lastFrameAt;

    SchemaSyncClient(String prefix, Acker acker, LongSupplier nanoClock) {
        this.prefix = prefix;
        this.acker = acker;
        this.nanoClock = nanoClock;
        this.startedAt = nanoClock.getAsLong();
        this.lastFrameAt = this.startedAt;
    }

    /**
     * Sets how long a session may send nothing before it is STALE, from the Server option
     * schema_sync.session_timeout; keep it a few PD keepalive intervals
     */
    public static void sessionTimeout(long millis) {
        sessionTimeout = millis;
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
            SchemaSyncClient client = new SchemaSyncClient(prefix, driver::ack, System::nanoTime);
            driver.listenSync(prefix, client::onFrame, client::onSessionClosed,
                              client::onStopped);
            instance = client;
        }
        return instance;
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
        if (instance != null) {
            REQUEST.set(new HashSet<>());
        }
    }

    public static void endRequest() {
        REQUEST.remove();
    }

    /**
     * The session state, or null when no HStore graph opened in this JVM
     */
    public static State currentState() {
        SchemaSyncClient client = instance;
        return client == null ? null : client.state();
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
        if (state == State.READY && silent > TimeUnit.MILLISECONDS.toNanos(sessionTimeout)) {
            /*
             * Expired. The client can't replace a stream that stays open, so the session is
             * served again once its frames resume: PD kept its pending work all along, while a
             * session PD gave up on is closed and replaced by a new one.
             */
            return State.STALE;
        }
        return state;
    }

    /**
     * Tracks an opened graph instance, after it read (or created) its record. Its caches are
     * cleared first, as an earlier instance of the graph may have left entries. The first
     * graph waits up to the session timeout for the initial sync, so the startup of the
     * Server finds its graphs served.
     */
    public GraphSync register(String graphSpace, String graph, Record record,
                              Runnable invalidator) {
        String key = this.key(graphSpace, graph);
        GraphSync sync = new GraphSync(this, graphSpace + "/" + graph, key,
                                       record.incarnation, invalidator);
        invalidator.run();
        synchronized (this) {
            this.graphs.put(key, sync);
            Record seen = this.records.get(key);
            if (seen != null && seen.revision > record.revision) {
                // A change this session acknowledged before the instance was tracked
                this.live(sync, seen);
            } else {
                this.live(sync, record);
            }
            long deadline = this.startedAt + TimeUnit.MILLISECONDS.toNanos(sessionTimeout);
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
        // Outside the lock: no network call while holding it
        for (Map.Entry<String, Long> ack : acks.entrySet()) {
            this.ack(session, ack.getKey(), ack.getValue());
        }
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
            this.records.put(key, record);
            GraphSync sync = this.graphs.get(key);
            if (sync != null && this.live(sync, record) &&
                record.revision > sync.appliedRevision) {
                // Under the cache lock, which also advances the cache generation
                sync.invalidator.run();
                sync.appliedRevision = record.revision;
                this.invalidations.incrementAndGet();
            }
            return record;
        } catch (RuntimeException e) {
            LOG.warn("Failed to process the schema sync record {} = {}, not acknowledged",
                     key, value, e);
            return null;
        }
    }

    private void synced() {
        for (GraphSync sync : this.graphs.values()) {
            if (sync.presentAtStart && !this.records.containsKey(sync.key)) {
                // Missing is not "nothing to sync": the graph is gone
                sync.disable("its schema sync record is gone");
            }
            sync.presentAtStart = false;
        }
        this.state = State.READY;
        this.notifyAll();
        LOG.info("Schema sync session {} is ready", this.clientId);
    }

    private boolean live(GraphSync sync, Record record) {
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
        private final Runnable invalidator;
        private volatile long appliedRevision;
        private volatile String disabled;
        // Open when the current session started, guarded by the client
        private boolean presentAtStart;

        private GraphSync(SchemaSyncClient client, String name, String key, long incarnation,
                          Runnable invalidator) {
            this.client = client;
            this.name = name;
            this.key = key;
            this.incarnation = incarnation;
            this.invalidator = invalidator;
        }

        public boolean serving() {
            return this.disabled == null && this.client.state() == State.READY;
        }

        public void check() {
            String disabled = this.disabled;
            if (disabled != null) {
                throw new SyncingException("Graph '%s' is no longer served: %s",
                                           this.name, disabled);
            }
            State state = this.client.state();
            if (state != State.READY) {
                throw new SyncingException("The schema of graph '%s' is syncing with PD " +
                                           "(%s), retry later", this.name, state);
            }
        }

        /**
         * Checks the first access of a Gremlin request to the graph; later accesses of an
         * admitted request are in flight and not interrupted
         */
        public void admit() {
            Set<GraphSync> request = REQUEST.get();
            if (request != null && !request.contains(this)) {
                this.check();
                request.add(this);
            }
        }

        public long appliedRevision() {
            return this.appliedRevision;
        }

        public void close() {
            this.client.graphs.remove(this.key, this);
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

        public Record(long revision, long incarnation, boolean dropped) {
            this.revision = revision;
            this.incarnation = incarnation;
            this.dropped = dropped;
        }

        @SuppressWarnings("unchecked")
        public static Record parse(String value) {
            Map<String, Object> map = JsonUtil.fromJson(value, Map.class);
            return new Record(((Number) map.get("rev")).longValue(),
                              ((Number) map.get("inc")).longValue(),
                              !"LIVE".equals(map.get("state")));
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
     * A request to a graph that is not served now; the REST layer answers 503
     */
    public static class SyncingException extends HugeException {

        private static final long serialVersionUID = 4609836219472396417L;

        public SyncingException(String message, Object... args) {
            super(message, args);
        }
    }
}
