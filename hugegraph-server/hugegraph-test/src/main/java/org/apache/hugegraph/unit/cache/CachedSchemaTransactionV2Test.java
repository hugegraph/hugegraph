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
import java.util.Set;
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
import org.apache.hugegraph.backend.tx.GraphTransaction;
import org.apache.hugegraph.config.CoreOptions;
import org.apache.hugegraph.event.EventHub;
import org.apache.hugegraph.job.schema.EdgeLabelRemoveJob;
import org.apache.hugegraph.job.schema.VertexLabelRemoveJob;
import org.apache.hugegraph.meta.MetaManager;
import org.apache.hugegraph.meta.PdMetaDriver;
import org.apache.hugegraph.meta.managers.GraphMetaManager;
import org.apache.hugegraph.meta.managers.SchemaMetaManager;
import org.apache.hugegraph.pd.grpc.kv.TxnOp;
import org.apache.hugegraph.pd.grpc.kv.TxnRequest;
import org.apache.hugegraph.pd.grpc.kv.TxnResponse;
import org.apache.hugegraph.schema.EdgeLabel;
import org.apache.hugegraph.schema.IndexLabel;
import org.apache.hugegraph.schema.PropertyKey;
import org.apache.hugegraph.schema.SchemaLabel;
import org.apache.hugegraph.schema.VertexLabel;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.testutil.Whitebox;
import org.apache.hugegraph.type.define.DataType;
import org.apache.hugegraph.type.define.IndexType;
import org.apache.hugegraph.type.define.SchemaStatus;
import org.apache.hugegraph.unit.FakeObjects;
import org.apache.hugegraph.util.LockUtil;
import org.apache.tinkerpop.gremlin.structure.Transaction;
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
    private PdMetaDriver driver;
    private SchemaMetaManager pd;
    private HugeGraph graph;
    private HugeGraphParams params;
    private CachedSchemaTransactionV2 tx;
    private Object graphMetaManager;
    private boolean listenerRegistered;

    @Before
    public void setup() {
        PdMetaDriver driver = Mockito.mock(PdMetaDriver.class);
        this.driver = driver;
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
        Mockito.when(this.graph.option(CoreOptions.TASK_SYNC_DELETION)).thenReturn(false);
        Mockito.when(this.graph.tx()).thenReturn(Mockito.mock(Transaction.class));
        HugeGraphParams params = Mockito.mock(HugeGraphParams.class);
        this.params = params;
        Mockito.when(params.graph()).thenReturn(this.graph);
        Mockito.when(params.name()).thenReturn(GRAPH);
        Mockito.when(params.schemaIncarnation()).thenReturn(new AtomicLong(1L));
        Mockito.when(params.configuration()).thenReturn(FakeObjects.newConfig());
        Mockito.when(params.schemaEventHub()).thenReturn(new EventHub("schema"));
        Mockito.when(params.graphEventHub()).thenReturn(new EventHub("graph"));
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
        Mockito.when(params.schemaTransaction()).thenReturn(this.tx);
    }

    @After
    public void teardown() {
        this.tx.close();
        caches().remove(cacheName("ID_CACHE_PREFIX"));
        caches().remove(cacheName("NAME_CACHE_PREFIX"));
        caches().remove("vertex-" + SPACE_GRAPH);
        caches().remove("edge-" + SPACE_GRAPH);
        caches().remove("vertex-DEFAULT-other");
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
    public void testFullListingIsCachedAgainAfterAWrite() {
        this.pd.saveSchema("DEFAULT", GRAPH, this.pk(1, "age", DataType.TEXT));
        Assert.assertEquals(1, this.tx.getPropertyKeys().size());
        // Leaves the listing flag of property keys false
        this.tx.addPropertyKey(this.pk(2, "name", DataType.TEXT));

        // Lists from PD once, then from the cache
        Assert.assertEquals(2, this.tx.getPropertyKeys().size());
        Mockito.clearInvocations(this.driver);
        Assert.assertEquals(2, this.tx.getPropertyKeys().size());
        Assert.assertEquals(2, this.tx.getPropertyKeys().size());
        Mockito.verify(this.driver, Mockito.never()).scanWithPrefix(Mockito.any());
    }

    /**
     * Cached vertices and edges hold the label objects of the schema: a schema change of
     * another Server clears them with the schema caches
     */
    @Test
    public void testClearAlsoClearsTheVertexAndEdgeCaches() {
        Cache<Id, Object> vertices = CacheManager.instance().cache("vertex-" + SPACE_GRAPH,
                                                                   100L);
        Cache<Id, Object> edges = CacheManager.instance().cache("edge-" + SPACE_GRAPH, 100L);
        Cache<Id, Object> other = CacheManager.instance().cache("vertex-DEFAULT-other", 100L);
        vertices.update(IdGenerator.of(1), "v1");
        edges.update(IdGenerator.of(2), "e2");
        other.update(IdGenerator.of(3), "v3");

        CachedSchemaTransactionV2.clearSchemaCache(SPACE_GRAPH);
        Assert.assertEquals(0L, vertices.size());
        Assert.assertEquals(0L, edges.size());
        // Another graph keeps its caches
        Assert.assertEquals(1L, other.size());
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

    /**
     * Removing the index labels saves new label objects, the cache no longer updates the one
     * the job read: restoring the status must not save that one, with the ids of the index
     * labels already removed
     */
    @Test
    public void testVertexLabelRemoveFailedPartwayKeepsRemovedIndexLabelsOut() {
        VertexLabel label = new VertexLabel(this.graph, IdGenerator.of(1), "person");
        this.saveWithIndexLabels(label);
        // Read and cached as the job finds it
        Assert.assertEquals(2, this.tx.getVertexLabel(label.id()).indexLabels().size());

        Assert.assertThrows(IllegalStateException.class, () -> {
            Whitebox.invokeStatic(VertexLabelRemoveJob.class,
                                  new Class<?>[]{HugeGraphParams.class, Id.class},
                                  "removeVertexLabel", this.params, label.id());
        }, e -> Assert.assertContains("index data", e.getMessage()));

        VertexLabel stored = this.tx.getVertexLabel(label.id());
        Assert.assertEquals(SchemaStatus.UNDELETED, stored.status());
        // Both index labels left the label before the failure, none comes back
        Assert.assertEquals(Set.of(), stored.indexLabels());
    }

    @Test
    public void testEdgeLabelRemoveFailedPartwayKeepsRemovedIndexLabelsOut() {
        EdgeLabel label = new EdgeLabel(this.graph, IdGenerator.of(1), "knows");
        this.saveWithIndexLabels(label);
        Assert.assertEquals(2, this.tx.getEdgeLabel(label.id()).indexLabels().size());

        Assert.assertThrows(IllegalStateException.class, () -> {
            Whitebox.invokeStatic(EdgeLabelRemoveJob.class,
                                  new Class<?>[]{HugeGraphParams.class, Id.class},
                                  "removeEdgeLabel", this.params, label.id());
        }, e -> Assert.assertContains("index data", e.getMessage()));

        EdgeLabel stored = this.tx.getEdgeLabel(label.id());
        Assert.assertEquals(SchemaStatus.UNDELETED, stored.status());
        // Both index labels left the label before the failure, none comes back
        Assert.assertEquals(Set.of(), stored.indexLabels());
    }

    /**
     * Saves the label with two index labels; removing the index data of the second index
     * label fails, after it left the label
     */
    private void saveWithIndexLabels(SchemaLabel label) {
        PropertyKey name = this.pk(1, "name", DataType.TEXT);
        label.properties(name.id());
        IndexLabel byName = this.indexLabel(11, "byName", label, name);
        IndexLabel byName2 = this.indexLabel(12, "byName2", label, name);
        label.addIndexLabel(byName.id());
        label.addIndexLabel(byName2.id());
        this.pd.saveSchema("DEFAULT", GRAPH, name, byName, byName2, label);

        GraphTransaction graphTx = Mockito.mock(GraphTransaction.class);
        AtomicInteger removals = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            if (removals.incrementAndGet() == 2) {
                throw new IllegalStateException("Failed to remove the index data");
            }
            return null;
        }).when(graphTx).removeIndex(Mockito.any(IndexLabel.class));
        Mockito.when(this.params.graphTransaction()).thenReturn(graphTx);
    }

    private IndexLabel indexLabel(long id, String name, SchemaLabel base, PropertyKey field) {
        IndexLabel indexLabel = new IndexLabel(this.graph, IdGenerator.of(id), name);
        indexLabel.baseType(base.type());
        indexLabel.baseValue(base.id());
        indexLabel.indexType(IndexType.SECONDARY);
        indexLabel.indexFields(field.id());
        return indexLabel;
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
