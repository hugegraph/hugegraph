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

package org.apache.hugegraph.backend.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.apache.hugegraph.HugeGraphParams;
import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.backend.id.IdGenerator;
import org.apache.hugegraph.backend.store.ram.IntObjectMap;
import org.apache.hugegraph.backend.tx.SchemaTransactionV2;
import org.apache.hugegraph.config.CoreOptions;
import org.apache.hugegraph.event.EventHub;
import org.apache.hugegraph.event.EventListener;
import org.apache.hugegraph.meta.MetaDriver;
import org.apache.hugegraph.meta.MetaManager;
import org.apache.hugegraph.meta.MetaManager.SchemaCacheClearEvent;
import org.apache.hugegraph.perf.PerfUtil;
import org.apache.hugegraph.schema.IndexLabel;
import org.apache.hugegraph.schema.SchemaElement;
import org.apache.hugegraph.schema.SchemaLabel;
import org.apache.hugegraph.schema.VertexLabel;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.Events;

import com.google.common.collect.ImmutableSet;

public class CachedSchemaTransactionV2 extends SchemaTransactionV2 {

    /*
     * V1 and V2 transactions can coexist in the same JVM for graphs with the
     * same name but different backends. Keep their cache entries isolated:
     * besides holding different schema data, their attachments use different
     * SchemaCaches classes and therefore can't be shared safely.
     */
    private static final String ID_CACHE_PREFIX = "schema-v2-id";
    private static final String NAME_CACHE_PREFIX = "schema-v2-name";

    // MetaDriver doesn't expose unlisten, register the meta listener once.
    // Lifecycle: this JVM-global flag is intentionally never reset by
    // unlistenChanges() (the underlying gRPC watch is process-wide). The driver
    // watch self-heals across transport reconnects (PdMetaDriver via KvClient,
    // EtcdMetaDriver via Watch.Listener re-subscribe), so the subscription stays
    // live and the flag staying true is correct.
    private static final AtomicBoolean metaEventListenerRegistered =
            new AtomicBoolean(false);

    private static final Object META_LISTENER_LOCK = new Object();

    /**
     * Per-JVM identifier emitted with every schema-cache-clear meta event so
     * the listener can skip its own echo. Lifecycle: generated once per
     * classloader at class init, never reused, regenerated on JVM restart.
     * This is not a stable node identity, only a local self-echo filter.
     */
    private static final String SCHEMA_CACHE_CLEAR_SOURCE =
            UUID.randomUUID().toString();

    private final Cache<Id, Object> idCache;
    private final Cache<Id, Object> nameCache;

    private final SchemaCaches<SchemaElement> arrayCaches;

    private EventListener storeEventListener;
    private EventListener cacheEventListener;

    public CachedSchemaTransactionV2(MetaDriver metaDriver,
                                     String cluster,
                                     HugeGraphParams graphParams) {
        super(metaDriver, cluster, graphParams);

        final long capacity = graphParams.configuration()
                                         .get(CoreOptions.SCHEMA_CACHE_CAPACITY);
        this.idCache = this.cache(ID_CACHE_PREFIX, capacity);
        this.nameCache = this.cache(NAME_CACHE_PREFIX, capacity);

        SchemaCaches<SchemaElement> attachment = this.idCache.attachment();
        if (attachment == null) {
            int acSize = (int) (capacity >> 3);
            attachment = this.idCache.attachment(new SchemaCaches<>(acSize));
        }
        this.arrayCaches = attachment;
        this.listenChanges();
    }

    private static Id generateId(HugeType type, Id id) {
        // NOTE: it's slower performance to use:
        // String.format("%x-%s", type.code(), name)
        return IdGenerator.of(type.string() + "-" + id.asString());
    }

    private static Id generateId(HugeType type, String name) {
        return IdGenerator.of(type.string() + "-" + name);
    }

    public void close() {
        this.clearCache(false);
        this.unlistenChanges();
    }

    private Cache<Id, Object> cache(String prefix, long capacity) {
        final String name = cacheName(prefix, this.graph().spaceGraphName());
        // NOTE: must disable schema cache-expire due to getAllSchema()
        return CacheManager.instance().cache(name, capacity);
    }

    private static String cacheName(String prefix, String spaceGraphName) {
        return prefix + "-" + spaceGraphName;
    }

    /**
     * Clears the schema caches of the graph and advances their generation, under their lock,
     * so a read that started before can't put what it read into them
     */
    public static void clearSchemaCache(String spaceGraphName) {
        Map<String, Cache<Id, Object>> caches = CacheManager.instance().caches();
        Cache<Id, Object> nameCache = caches.get(cacheName(NAME_CACHE_PREFIX,
                                                           spaceGraphName));
        Cache<Id, Object> idCache = caches.get(cacheName(ID_CACHE_PREFIX,
                                                         spaceGraphName));
        SchemaCaches<?> arrayCaches = idCache == null ? null : idCache.attachment();
        if (arrayCaches == null) {
            // No transaction of the graph got far enough to read through these caches
            if (nameCache != null) {
                nameCache.clear();
            }
            if (idCache != null) {
                idCache.clear();
            }
            return;
        }
        arrayCaches.clearAll(nameCache, idCache);
    }

    private void listenChanges() {
        // Listen store event: "store.init", "store.clear", ...
        Set<String> storeEvents = ImmutableSet.of(Events.STORE_INIT,
                                                  Events.STORE_CLEAR,
                                                  Events.STORE_TRUNCATE);
        this.storeEventListener = event -> {
            if (storeEvents.contains(event.name())) {
                LOG.debug("Graph {} clear schema cache on event '{}'",
                          this.graph(), event.name());
                boolean notify = !Events.STORE_INIT.equals(event.name());
                this.clearCache(notify);
                return true;
            }
            return false;
        };
        this.graphParams().loadGraphStore().provider().listen(this.storeEventListener);

        // Listen cache event: "cache"(invalid cache item)
        this.cacheEventListener = event -> {
            LOG.debug("Graph {} received schema cache event: {}",
                      this.graph(), event);
            Object[] args = event.args();
            E.checkArgument(args.length > 0 && args[0] instanceof String,
                            "Expect event action argument");
            if (Cache.ACTION_INVALID.equals(args[0])) {
                event.checkArgs(String.class, HugeType.class, Id.class);
                this.invalidateCache((HugeType) args[1], (Id) args[2], null);
                return true;
            } else if (Cache.ACTION_CLEAR.equals(args[0])) {
                event.checkArgs(String.class, HugeType.class);
                this.clearCache(false);
                return true;
            }
            return false;
        };
        EventHub schemaEventHub = this.graphParams().schemaEventHub();
        if (!schemaEventHub.containsListener(Events.CACHE)) {
            schemaEventHub.listen(Events.CACHE, this.cacheEventListener);
        }

        listenSchemaCacheClear();
    }

    private static void listenSchemaCacheClear() {
        synchronized (META_LISTENER_LOCK) {
            if (metaEventListenerRegistered.get()) {
                return;
            }
            try {
                MetaManager.instance().listenSchemaCacheClear(
                        CachedSchemaTransactionV2::handleSchemaCacheClearEvent);
                // Set AFTER the underlying watch is live so a concurrent
                // caller that observes the flag is guaranteed an active
                // subscription, and a failure leaves the flag false so the
                // next caller retries registration.
                metaEventListenerRegistered.set(true);
            } catch (Exception e) {
                throw e instanceof RuntimeException
                      ? (RuntimeException) e
                      : new RuntimeException(
                              "Failed to register schema cache clear listener",
                              e);
            }
        }
    }

    /**
     * Consumer invoked by the MetaManager schema-cache-clear watch. Extracted
     * as a package-private static method so end-to-end tests can drive the
     * publish -> callback -> {@link #clearSchemaCache(String)} path without
     * depending on a live etcd/PD watch.
     */
    static <T> void handleSchemaCacheClearEvent(T response) {
        List<SchemaCacheClearEvent> events =
                MetaManager.instance()
                           .extractSchemaCacheClearEventsFromResponse(
                                   response);
        if (events == null) {
            return;
        }
        /*
         * The cache clear event of Servers before the PD schema sync; SchemaSyncClient now
         * invalidates every Server, the writer included. Kept for the mixed-version window
         * (with DDL frozen meanwhile), it can be removed once all Servers run this version.
         */
        for (SchemaCacheClearEvent event : events) {
            if (SCHEMA_CACHE_CLEAR_SOURCE.equals(event.source())) {
                continue;
            }
            String graphName = event.graph();
            LOG.debug("Graph {} clear schema cache on meta event", graphName);
            clearSchemaCache(graphName);
        }
    }

    public void clearCache(boolean notify) {
        this.arrayCaches.clearAll(this.nameCache, this.idCache);

        if (notify) {
            this.maybeNotifySchemaCacheClear();
        }
    }

    private void resetCachedAllIfReachedCapacity() {
        if (this.idCache.size() >= this.idCache.capacity()) {
            LOG.warn("Schema cache reached capacity({}): {}",
                     this.idCache.capacity(), this.idCache.size());
            this.cachedTypes().clear();
        }
    }

    private void unlistenChanges() {
        // Unlisten store event
        this.graphParams().loadGraphStore().provider()
            .unlisten(this.storeEventListener);

        // Unlisten cache event
        EventHub schemaEventHub = this.graphParams().schemaEventHub();
        schemaEventHub.unlisten(Events.CACHE, this.cacheEventListener);
    }

    private CachedTypes cachedTypes() {
        return this.arrayCaches.cachedTypes();
    }

    /**
     * Drops an element from the caches and the complete-listing flag of its type, under the
     * cache lock and advancing the generation, so a read that started before the change can't
     * put the old element back
     */
    private void invalidateCache(HugeType type, Id id, String name) {
        Id prefixedId = generateId(type, id);
        synchronized (this.arrayCaches) {
            Object value = this.idCache.get(prefixedId);
            this.idCache.invalidate(prefixedId);
            if (value != null) {
                SchemaElement schema = (SchemaElement) value;
                this.nameCache.invalidate(generateId(schema.type(), schema.name()));
            }
            if (name != null) {
                this.nameCache.invalidate(generateId(type, name));
            }
            this.arrayCaches.remove(type, id);
            this.cachedTypes().put(type, false);
            this.arrayCaches.generation++;
        }
    }

    /**
     * Writes invalidate after the commit instead of caching the written object: a change
     * notified meanwhile can't be overwritten by an object from before the commit
     */
    private void invalidateCache(SchemaElement schema) {
        this.invalidateCache(schema.type(), schema.id(), schema.name());
        // Cached vertices and edges hold the label object, no longer updated in place
        EventHub graphEventHub = this.graphParams().graphEventHub();
        if (schema.type() == HugeType.VERTEX_LABEL) {
            graphEventHub.notifySync(Events.CACHE, Cache.ACTION_CLEAR, HugeType.VERTEX);
        }
        if (schema.type() == HugeType.VERTEX_LABEL || schema.type() == HugeType.EDGE_LABEL) {
            graphEventHub.notifySync(Events.CACHE, Cache.ACTION_CLEAR, HugeType.EDGE);
        }
    }

    /**
     * Puts what a read found into the caches, unless they were invalidated since the read
     * started at the given generation
     */
    private void populate(long generation, Runnable update) {
        synchronized (this.arrayCaches) {
            if (this.arrayCaches.generation == generation) {
                update.run();
            }
        }
    }

    @Override
    protected void updateSchema(SchemaElement schema,
                                Consumer<SchemaElement> updateCallback) {
        super.updateSchema(schema, updateCallback);

        this.invalidateCache(schema);
        // Status transitions are internal bookkeeping; notifying here causes a
        // broadcast storm for every updateSchemaStatus() call from background jobs.
    }

    @Override
    protected void addSchema(SchemaElement schema) {
        super.addSchema(schema);

        this.invalidateCache(schema);

        // Schema additions must always propagate to remote nodes regardless
        // of TASK_SYNC_DELETION (which only gates removal flows).
        this.notifySchemaCacheClear();
    }

    @Override
    public void addIndexLabel(SchemaLabel baseLabel, IndexLabel indexLabel) {
        super.addIndexLabel(baseLabel, indexLabel);

        // Both were written in one commit, invalidate them as addSchema and updateSchema do
        this.invalidateCache(indexLabel);
        if (!baseLabel.equals(VertexLabel.OLAP_VL)) {
            this.invalidateCache(baseLabel);
        }
        this.notifySchemaCacheClear();
    }

    @Override
    public void removeSchema(SchemaElement schema) {
        super.removeSchema(schema);

        this.invalidateCache(schema);

        this.maybeNotifySchemaCacheClear();
    }

    private void maybeNotifySchemaCacheClear() {
        // Only suppress notifications for removal tasks when
        // TASK_SYNC_DELETION=true: the caller propagates cache invalidation
        // synchronously, so the meta-event broadcast would be redundant.
        if (!this.graph().option(CoreOptions.TASK_SYNC_DELETION)) {
            this.notifySchemaCacheClear();
        }
    }

    private void notifySchemaCacheClear() {
        MetaManager.instance()
                   .notifySchemaCacheClear(this.graph().graphSpace(),
                                           this.graph().name(),
                                           SCHEMA_CACHE_CLEAR_SOURCE);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T extends SchemaElement> T getSchema(HugeType type, Id id) {
        // try get from optimized array cache
        if (id.number() && id.asLong() > 0L) {
            SchemaElement value = this.arrayCaches.get(type, id);
            if (value != null) {
                return (T) value;
            }
        }

        long generation = this.arrayCaches.generation;
        Id prefixedId = generateId(type, id);
        Object value = this.idCache.get(prefixedId);
        boolean missed = value == null;
        if (missed) {
            // Read outside the cache lock
            value = super.getSchema(type, id);
        }
        if (value != null) {
            SchemaElement schema = (SchemaElement) value;
            this.populate(generation, () -> {
                if (missed) {
                    this.resetCachedAllIfReachedCapacity();
                    this.idCache.update(prefixedId, schema);
                    this.nameCache.update(generateId(schema.type(), schema.name()), schema);
                }
                // update optimized array cache
                this.arrayCaches.updateIfNeeded(schema);
            });
        }
        return (T) value;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T extends SchemaElement> T getSchema(HugeType type,
                                                    String name) {
        Id prefixedName = generateId(type, name);
        Object value = this.nameCache.get(prefixedName);
        if (value == null) {
            value = super.getSchema(type, name);
            if (value != null) {
                // Note: reload all schema if the cache is inconsistent with storage layer
                this.clearCache(false);
                this.loadAllSchema();
            }
        }
        return (T) value;
    }

    @Override
    protected <T extends SchemaElement> List<T> getAllSchema(HugeType type) {
        while (true) {
            long generation = this.arrayCaches.generation;
            if (!this.cachedTypes().getOrDefault(type, false)) {
                break;
            }
            List<T> results = new ArrayList<>();
            // Get from cache
            this.idCache.traverse(value -> {
                @SuppressWarnings("unchecked")
                T schema = (T) value;
                if (schema.type() == type) {
                    results.add(schema);
                }
            });
            // An invalidation during the traversal may have cut the list short: list again
            if (this.arrayCaches.generation == generation &&
                this.cachedTypes().getOrDefault(type, false)) {
                return results;
            }
        }

        long generation = this.arrayCaches.generation;
        // Read outside the cache lock
        List<T> results = super.getAllSchema(type);
        long free = this.idCache.capacity() - this.idCache.size();
        if (results.size() <= free) {
            this.populate(generation, () -> {
                for (T schema : results) {
                    Id prefixedId = generateId(schema.type(), schema.id());
                    this.idCache.update(prefixedId, schema);

                    Id prefixedName = generateId(schema.type(), schema.name());
                    this.nameCache.update(prefixedName, schema);
                }
                this.cachedTypes().putIfAbsent(type, true);
            });
        }
        return results;
    }

    private void loadAllSchema() {
        getAllSchema(HugeType.PROPERTY_KEY);
        getAllSchema(HugeType.VERTEX_LABEL);
        getAllSchema(HugeType.EDGE_LABEL);
        getAllSchema(HugeType.INDEX_LABEL);
    }

    @Override
    public void clear() {
        // Clear schema info firstly
        super.clear();
        this.clearCache(false);
        this.notifySchemaCacheClear();
    }

    private static final class SchemaCaches<V extends SchemaElement> {

        private final int size;

        private final IntObjectMap<V> pks;
        private final IntObjectMap<V> vls;
        private final IntObjectMap<V> els;
        private final IntObjectMap<V> ils;

        private final CachedTypes cachedTypes;

        /*
         * Advanced by every invalidation, under the lock of this object, which is shared by all
         * schema transactions of the graph: a read populates the caches only if the generation
         * did not change since it started
         */
        private volatile long generation;

        public SchemaCaches(int size) {
            // TODO: improve size of each type for optimized array cache
            this.size = size;

            this.pks = new IntObjectMap<>(size);
            this.vls = new IntObjectMap<>(size);
            this.els = new IntObjectMap<>(size);
            this.ils = new IntObjectMap<>(size);

            this.cachedTypes = new CachedTypes();
        }

        public void updateIfNeeded(V schema) {
            if (schema == null) {
                return;
            }
            Id id = schema.id();
            if (id.number() && id.asLong() > 0L) {
                this.set(schema.type(), id, schema);
            }
        }

        @PerfUtil.Watched
        public V get(HugeType type, Id id) {
            assert id.number();
            long longId = id.asLong();
            if (longId <= 0L) {
                assert false : id;
                return null;
            }
            int key = (int) longId;
            if (key >= this.size) {
                return null;
            }
            switch (type) {
                case PROPERTY_KEY:
                    return this.pks.get(key);
                case VERTEX_LABEL:
                    return this.vls.get(key);
                case EDGE_LABEL:
                    return this.els.get(key);
                case INDEX_LABEL:
                    return this.ils.get(key);
                default:
                    return null;
            }
        }

        public void set(HugeType type, Id id, V value) {
            assert id.number();
            long longId = id.asLong();
            if (longId <= 0L) {
                assert false : id;
                return;
            }
            int key = (int) longId;
            if (key >= this.size) {
                return;
            }
            switch (type) {
                case PROPERTY_KEY:
                    this.pks.set(key, value);
                    break;
                case VERTEX_LABEL:
                    this.vls.set(key, value);
                    break;
                case EDGE_LABEL:
                    this.els.set(key, value);
                    break;
                case INDEX_LABEL:
                    this.ils.set(key, value);
                    break;
                default:
                    // pass
                    break;
            }
        }

        public void remove(HugeType type, Id id) {
            assert id.number();
            long longId = id.asLong();
            if (longId <= 0L) {
                return;
            }
            int key = (int) longId;
            V value = null;
            if (key >= this.size) {
                return;
            }
            switch (type) {
                case PROPERTY_KEY:
                    this.pks.set(key, value);
                    break;
                case VERTEX_LABEL:
                    this.vls.set(key, value);
                    break;
                case EDGE_LABEL:
                    this.els.set(key, value);
                    break;
                case INDEX_LABEL:
                    this.ils.set(key, value);
                    break;
                default:
                    // pass
                    break;
            }
        }

        public void clear() {
            this.pks.clear();
            this.vls.clear();
            this.els.clear();
            this.ils.clear();

            this.cachedTypes.clear();
        }

        public synchronized void clearAll(Cache<Id, Object> nameCache,
                                          Cache<Id, Object> idCache) {
            // Clear name cache first so the (name -> id -> object) lookup path fails fast
            // instead of returning a stale object backed by an already-empty id cache
            if (nameCache != null) {
                nameCache.clear();
            }
            this.clear();
            if (idCache != null) {
                idCache.clear();
            }
            this.generation++;
        }

        public CachedTypes cachedTypes() {
            return this.cachedTypes;
        }
    }

    private static class CachedTypes
            extends ConcurrentHashMap<HugeType, Boolean> {

        private static final long serialVersionUID = -2215549791679355996L;
    }
}
