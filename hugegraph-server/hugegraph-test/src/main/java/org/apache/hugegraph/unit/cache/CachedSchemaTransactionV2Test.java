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

package org.apache.hugegraph.unit.cache;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.HugeGraphParams;
import org.apache.hugegraph.backend.cache.Cache;
import org.apache.hugegraph.backend.cache.CacheManager;
import org.apache.hugegraph.backend.cache.CachedSchemaTransactionV2;
import org.apache.hugegraph.backend.cache.RamCache;
import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.backend.id.IdGenerator;
import org.apache.hugegraph.backend.store.BackendStore;
import org.apache.hugegraph.backend.store.BackendStoreProvider;
import org.apache.hugegraph.event.EventHub;
import org.apache.hugegraph.meta.MetaManager;
import org.apache.hugegraph.meta.PdMetaDriver;
import org.apache.hugegraph.meta.managers.GraphMetaManager;
import org.apache.hugegraph.meta.managers.SchemaMetaManager;
import org.apache.hugegraph.pd.grpc.kv.TxnOp;
import org.apache.hugegraph.pd.grpc.kv.TxnRequest;
import org.apache.hugegraph.pd.grpc.kv.TxnResponse;
import org.apache.hugegraph.schema.PropertyKey;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.hugegraph.type.define.DataType;
import org.apache.hugegraph.unit.FakeObjects;
import org.apache.hugegraph.util.LockUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class CachedSchemaTransactionV2Test {

    private static final String GRAPH = "sync-v2";
    private static final String SPACE_GRAPH = "DEFAULT-" + GRAPH;

    // The PD KV store
    private final Map<String, String> kvs = new ConcurrentHashMap<>();
    // Set to hold the next PD get of a key until released
    private final AtomicReference<CountDownLatch[]> holdGet = new AtomicReference<>();
    private HookedCache idCache;
    private SchemaMetaManager pd;
    private HugeGraph graph;
    private CachedSchemaTransactionV2 tx;
    private Object graphMetaManager;
    private boolean listenerRegistered;

    @Before
    public void setup() {
        PdMetaDriver driver = Mockito.mock(PdMetaDriver.class);
        Mockito.when(driver.get(Mockito.any())).thenAnswer(invocation -> {
            String value = this.kvs.get(invocation.<String>getArgument(0));
            CountDownLatch[] latches = this.holdGet.getAndSet(null);
            if (latches != null) {
                latches[0].countDown();
                Assert.assertTrue(latches[1].await(10L, TimeUnit.SECONDS));
            }
            return value;
        });
        Mockito.when(driver.scanWithPrefix(Mockito.any())).thenAnswer(invocation -> {
            String prefix = invocation.getArgument(0);
            return this.kvs.entrySet().stream()
                           .filter(e -> e.getKey().startsWith(prefix))
                           .collect(Collectors.toMap(Map.Entry::getKey,
                                                     Map.Entry::getValue));
        });
        Mockito.when(driver.commit(Mockito.any())).thenAnswer(invocation -> {
            TxnRequest request = invocation.getArgument(0);
            for (TxnOp op : request.getOpsList()) {
                if (op.getType() == TxnOp.Type.PUT) {
                    this.kvs.put(op.getKey(), op.getValue());
                } else {
                    this.kvs.remove(op.getKey());
                }
            }
            return TxnResponse.newBuilder().setSucceeded(true).setRevision(1L)
                              .setIncarnation(1L).build();
        });

        this.graph = Mockito.mock(HugeGraph.class);
        Mockito.when(this.graph.graphSpace()).thenReturn("DEFAULT");
        Mockito.when(this.graph.name()).thenReturn(GRAPH);
        Mockito.when(this.graph.spaceGraphName()).thenReturn(SPACE_GRAPH);
        HugeGraphParams params = Mockito.mock(HugeGraphParams.class);
        Mockito.when(params.graph()).thenReturn(this.graph);
        Mockito.when(params.name()).thenReturn(GRAPH);
        Mockito.when(params.schemaIncarnation()).thenReturn(new AtomicLong(1L));
        Mockito.when(params.configuration()).thenReturn(FakeObjects.newConfig());
        Mockito.when(params.schemaEventHub()).thenReturn(new EventHub("schema"));
        BackendStore store = Mockito.mock(BackendStore.class);
        Mockito.when(store.provider()).thenReturn(Mockito.mock(BackendStoreProvider.class));
        Mockito.when(params.loadGraphStore()).thenReturn(store);

        // The listeners need a connected MetaManager, which a unit test has not
        AtomicBoolean registered = Whitebox.getInternalState(
                CachedSchemaTransactionV2.class, "metaEventListenerRegistered");
        this.listenerRegistered = registered.getAndSet(true);
        this.graphMetaManager = Whitebox.getInternalState(MetaManager.instance(),
                                                          "graphMetaManager");
        Whitebox.setInternalState(MetaManager.instance(), "graphMetaManager",
                                  Mockito.mock(GraphMetaManager.class));
        LockUtil.init(SPACE_GRAPH);

        this.idCache = new HookedCache();
        caches().put(cacheName("ID_CACHE_PREFIX"), this.idCache);
        this.pd = new SchemaMetaManager(driver, "hg", this.graph);
        this.tx = new CachedSchemaTransactionV2(driver, "hg", params);
    }

    @After
    public void teardown() {
        this.tx.close();
        caches().remove(cacheName("ID_CACHE_PREFIX"));
        caches().remove(cacheName("NAME_CACHE_PREFIX"));
        LockUtil.destroy(SPACE_GRAPH);
        Whitebox.setInternalState(MetaManager.instance(), "graphMetaManager",
                                  this.graphMetaManager);
        AtomicBoolean registered = Whitebox.getInternalState(
                CachedSchemaTransactionV2.class, "metaEventListenerRegistered");
        registered.set(this.listenerRegistered);
    }

    @Test
    public void testReadStartedBeforeAClearDoesNotCacheWhatItRead() throws Exception {
        this.pd.saveSchema("DEFAULT", GRAPH, this.pk(1, "age", DataType.TEXT));
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        this.holdGet.set(new CountDownLatch[]{reading, release});
        AtomicReference<PropertyKey> read = new AtomicReference<>();
        Thread reader = new Thread(() -> read.set(this.tx.getPropertyKey(IdGenerator.of(1))));
        reader.start();
        Assert.assertTrue(reading.await(10L, TimeUnit.SECONDS));

        // The read holds the old value; the change commits and the caches are cleared
        this.pd.saveSchema("DEFAULT", GRAPH, this.pk(1, "age", DataType.INT));
        CachedSchemaTransactionV2.clearSchemaCache(SPACE_GRAPH);
        release.countDown();
        reader.join(10000L);
        Assert.assertEquals(DataType.TEXT, read.get().dataType());

        // The old value was not cached: the next read fetches the new one
        Assert.assertEquals(DataType.INT,
                            this.tx.getPropertyKey(IdGenerator.of(1)).dataType());
        Assert.assertEquals(DataType.INT,
                            this.tx.getPropertyKey(IdGenerator.of(1)).dataType());
    }

    @Test
    public void testFullListingRetriesWhenAClearCutsItShort() {
        for (int i = 1; i <= 3; i++) {
            this.pd.saveSchema("DEFAULT", GRAPH, this.pk(i, "pk" + i, DataType.TEXT));
        }
        // Lists from PD and marks the listing as cached
        Assert.assertEquals(3, this.tx.getPropertyKeys().size());

        AtomicInteger clears = new AtomicInteger();
        this.idCache.afterFirst = () -> {
            clears.incrementAndGet();
            CachedSchemaTransactionV2.clearSchemaCache(SPACE_GRAPH);
        };
        List<PropertyKey> listed = this.tx.getPropertyKeys();
        Assert.assertEquals(1, clears.get());
        Assert.assertEquals(3, listed.size());
    }

    @Test
    public void testWritesInvalidateInsteadOfCachingTheWrittenObject() {
        this.pd.saveSchema("DEFAULT", GRAPH, this.pk(1, "age", DataType.TEXT));
        Assert.assertEquals(1, this.tx.getPropertyKeys().size());
        Assert.assertEquals(DataType.TEXT,
                            this.tx.getPropertyKey(IdGenerator.of(1)).dataType());

        PropertyKey update = this.pk(1, "age", DataType.INT);
        this.tx.updatePropertyKey(update);
        PropertyKey added = this.pk(2, "name", DataType.TEXT);
        this.tx.addPropertyKey(added);
        Assert.assertEquals(0L, this.idCache.size());

        // Read back from PD, not the objects the writer held
        PropertyKey read = this.tx.getPropertyKey(IdGenerator.of(1));
        Assert.assertEquals(DataType.INT, read.dataType());
        Assert.assertNotSame(update, read);
        Assert.assertNotSame(added, this.tx.getPropertyKey(IdGenerator.of(2)));
        // The listing flag was reset: the added key is listed
        Assert.assertEquals(2, this.tx.getPropertyKeys().size());
    }

    private PropertyKey pk(long id, String name, DataType dataType) {
        PropertyKey pk = new PropertyKey(this.graph, IdGenerator.of(id), name);
        pk.dataType(dataType);
        return pk;
    }

    private static Map<String, Cache<Id, ?>> caches() {
        return Whitebox.getInternalState(CacheManager.instance(), "caches");
    }

    private static String cacheName(String prefixField) {
        String prefix = Whitebox.getInternalState(CachedSchemaTransactionV2.class,
                                                  prefixField);
        return prefix + "-" + SPACE_GRAPH;
    }

    private static class HookedCache extends RamCache {

        // Runs once, after the first element of the next traversal
        private volatile Runnable afterFirst;

        HookedCache() {
            super(1000L);
        }

        @Override
        public void traverse(Consumer<Object> consumer) {
            Runnable hook = this.afterFirst;
            this.afterFirst = null;
            AtomicBoolean first = new AtomicBoolean(true);
            super.traverse(value -> {
                consumer.accept(value);
                if (hook != null && first.getAndSet(false)) {
                    hook.run();
                }
            });
        }
    }
}
