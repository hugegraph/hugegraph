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
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.HugeGraphParams;
import org.apache.hugegraph.backend.cache.Cache;
import org.apache.hugegraph.backend.cache.CacheManager;
import org.apache.hugegraph.backend.cache.CachedSchemaTransactionV2;
import org.apache.hugegraph.backend.cache.RamCache;
import org.apache.hugegraph.id.Id;
import org.apache.hugegraph.id.IdGenerator;
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
import org.apache.hugegraph.struct.schema.EdgeLabel;
import org.apache.hugegraph.struct.schema.IndexLabel;
import org.apache.hugegraph.struct.schema.PropertyKey;
import org.apache.hugegraph.struct.schema.SchemaLabel;
import org.apache.hugegraph.struct.schema.VertexLabel;
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
    // Runs when PD is asked for a listing
    private volatile Runnable onScan;
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
            Runnable hook = this.onScan;
            if (hook != null) {
                hook.run();
            }
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
        Mockito.when(params.spaceGraphName()).thenReturn(SPACE_GRAPH);
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
        // The caches and listeners of a graph outlive its transactions
        CachedSchemaTransactionV2.closeGraph(this.params);
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
        this.readStartedBeforeAClear(() -> this.tx.getPropertyKey(IdGenerator.of(1)));
    }

    @Test
    public void testReadByNameStartedBeforeAClearDoesNotCacheWhatItRead() throws Exception {
        this.readStartedBeforeAClear(() -> this.tx.getPropertyKey("age"));
    }

    private void readStartedBeforeAClear(Supplier<PropertyKey> lookup) throws Exception {
        this.pd.addSchema("DEFAULT", GRAPH, this.pk(1, "age", DataType.TEXT));
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        this.holdGet.set(new CountDownLatch[]{reading, release});
        AtomicReference<PropertyKey> read = new AtomicReference<>();
        Thread reader = new Thread(() -> read.set(lookup.get()));
        reader.start();
        Assert.assertTrue(reading.await(10L, TimeUnit.SECONDS));

        // The read holds the old value; the change commits and the caches are cleared
        this.pd.updateSchema("DEFAULT", GRAPH, this.pk(1, "age", DataType.INT));
        CachedSchemaTransactionV2.clearSchemaCache(SPACE_GRAPH);
        release.countDown();
        reader.join(10000L);
        Assert.assertEquals(DataType.TEXT, read.get().dataType());

        // The old value was not cached: the next read fetches the new one
        Assert.assertEquals(DataType.INT, lookup.get().dataType());
        Assert.assertEquals(DataType.INT, lookup.get().dataType());
    }

    /**
     * After a clear, a lookup by name that misses caches the element it read. It does not
     * clear the caches and list the schema again: with concurrent lookups every such clear
     * discards what the others load, and the caches stay empty while the load lasts
     */
    @Test
    public void testConcurrentLookupsByNameAfterAClearRefillTheCaches() throws Exception {
        int keys = 20;
        int rounds = 50;
        for (int i = 1; i <= keys; i++) {
            this.pd.addSchema("DEFAULT", GRAPH, this.pk(i, "pk" + i, DataType.TEXT));
        }
        Assert.assertEquals(keys, this.tx.getPropertyKeys().size());
        CachedSchemaTransactionV2.clearSchemaCache(SPACE_GRAPH);
        Mockito.clearInvocations(this.driver);

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger found = new AtomicInteger();
        Thread[] readers = new Thread[8];
        for (int t = 0; t < readers.length; t++) {
            readers[t] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < rounds * keys; i++) {
                    if (this.tx.getPropertyKey("pk" + (i % keys + 1)) != null) {
                        found.incrementAndGet();
                    }
                }
            });
            readers[t].start();
        }
        start.countDown();
        for (Thread reader : readers) {
            reader.join(30000L);
        }
        Assert.assertEquals(readers.length * rounds * keys, found.get());
        // A reader misses a name once at most, and no miss lists the schema
        Mockito.verify(this.driver, Mockito.atMost(readers.length * keys)).get(Mockito.any());
        Mockito.verify(this.driver, Mockito.never()).scanWithPrefix(Mockito.any());

        // Refilled: by name and by id from the caches
        Mockito.clearInvocations(this.driver);
        for (int i = 1; i <= keys; i++) {
            Assert.assertSame(this.tx.getPropertyKey("pk" + i),
                              this.tx.getPropertyKey(IdGenerator.of(i)));
        }
        Mockito.verifyNoInteractions(this.driver);
    }

    @Test
    public void testFullListingRetriesWhenAClearCutsItShort() {
        for (int i = 1; i <= 3; i++) {
            this.pd.addSchema("DEFAULT", GRAPH, this.pk(i, "pk" + i, DataType.TEXT));
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

    /**
     * The invalidation of one element removes it from the id cache: a listing that traverses
     * the cache at that moment must not return the rest as the complete list
     */
    @Test
    public void testFullListingDuringAnInvalidationOfOneElementIsComplete() throws Exception {
        for (int i = 1; i <= 3; i++) {
            this.pd.addSchema("DEFAULT", GRAPH, this.pk(i, "pk" + i, DataType.TEXT));
        }
        Assert.assertEquals(3, this.tx.getPropertyKeys().size());

        AtomicReference<List<PropertyKey>> listed = new AtomicReference<>();
        // The listing returned from the caches, or went on to PD as they are not complete
        CountDownLatch moved = new CountDownLatch(1);
        Thread lister = new Thread(() -> {
            try {
                listed.set(this.tx.getPropertyKeys());
            } finally {
                moved.countDown();
            }
        });
        this.idCache.afterInvalidate = () -> {
            this.onScan = moved::countDown;
            lister.start();
            try {
                Assert.assertTrue(moved.await(10L, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        };
        // PD holds three keys before and after
        this.tx.updatePropertyKey(this.pk(2, "pk2", DataType.INT));
        lister.join(10000L);
        Assert.assertNotNull(listed.get());
        Assert.assertEquals(3, listed.get().size());
    }

    /**
     * The builders change the cached object before the commit: after a write that PD did not
     * take, the cache must not keep serving that object
     */
    @Test
    public void testFailedUpdateDoesNotLeaveTheChangedObjectCached() {
        this.pd.addSchema("DEFAULT", GRAPH, this.pk(1, "age", DataType.TEXT));
        PropertyKey cached = this.tx.getPropertyKey(IdGenerator.of(1));
        Assert.assertSame(cached, this.tx.getPropertyKey(IdGenerator.of(1)));

        cached.userdata("note", "not committed");
        Mockito.doThrow(new IllegalStateException("PD rejected the commit"))
               .when(this.driver).commit(Mockito.any());
        Assert.assertThrows(RuntimeException.class, () -> this.tx.updatePropertyKey(cached));

        PropertyKey read = this.tx.getPropertyKey(IdGenerator.of(1));
        Assert.assertNotSame(cached, read);
        Assert.assertFalse(read.userdata().containsKey("note"));
    }

    @Test
    public void testFullListingIsCachedAgainAfterAWrite() {
        this.pd.addSchema("DEFAULT", GRAPH, this.pk(1, "age", DataType.TEXT));
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
        this.pd.addSchema("DEFAULT", GRAPH, this.pk(1, "age", DataType.TEXT));
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
     * An index label is added to its base label before their one commit: after a commit PD
     * did not take, the cached base label must not keep the index label's id
     */
    @Test
    public void testFailedIndexLabelCommitDoesNotLeaveTheChangedBaseLabelCached() {
        PropertyKey name = this.pk(1, "name", DataType.TEXT);
        VertexLabel label = new VertexLabel(this.graph, IdGenerator.of(1), "person");
        label.properties(name.id());
        this.pd.addSchema("DEFAULT", GRAPH, name, label);
        VertexLabel cached = this.tx.getVertexLabel(label.id());
        Assert.assertSame(cached, this.tx.getVertexLabel(label.id()));

        IndexLabel byName = this.indexLabel(11, "byName", cached, name);
        Mockito.doThrow(new IllegalStateException("PD rejected the commit"))
               .when(this.driver).commit(Mockito.any());
        Assert.assertThrows(RuntimeException.class, () -> this.tx.addIndexLabel(cached, byName));

        VertexLabel read = this.tx.getVertexLabel(label.id());
        Assert.assertNotSame(cached, read);
        Assert.assertEquals(Set.of(), read.indexLabels());
        Assert.assertNull(this.tx.getIndexLabel(byName.id()));
    }

    /**
     * The cache no longer updates objects in place, so two writers of one base label may hold
     * different objects: the index labels are changed on the label as stored, read under the
     * write lock, not on the object the caller passed
     */
    @Test
    public void testIndexLabelChangesApplyToTheStoredBaseLabel() {
        PropertyKey name = this.pk(1, "name", DataType.TEXT);
        VertexLabel label = new VertexLabel(this.graph, IdGenerator.of(1), "person");
        label.properties(name.id());
        IndexLabel byName = this.indexLabel(11, "byName", label, name);
        label.addIndexLabel(byName.id());
        this.pd.addSchema("DEFAULT", GRAPH, name, byName, label);
        // The object a creation read before the removal below
        VertexLabel stale = this.tx.getVertexLabel(label.id());
        Assert.assertEquals(Set.of(byName.id()), stale.indexLabels());

        this.tx.removeIndexLabelFromBaseLabel(this.tx.getIndexLabel(byName.id()));
        Assert.assertEquals(Set.of(), this.tx.getVertexLabel(label.id()).indexLabels());

        IndexLabel byName2 = this.indexLabel(12, "byName2", label, name);
        this.tx.addIndexLabel(stale, byName2);
        // The removed index label is not back
        Assert.assertEquals(Set.of(byName2.id()), this.tx.getVertexLabel(label.id())
                                                         .indexLabels());
    }

    /**
     * One index-replacement DDL: index label 11 is removed from a base label while 12 is
     * added to it, both under the base label's write lock. The writer that holds the lock
     * first invalidates the cache only after it released the lock, and a read in between can
     * fill the cache with the label from before that commit. The next writer must take the
     * index labels from PD, not from such a cache entry, in either order of the two.
     */
    @Test
    public void testIndexLabelChangesReadTheStoredBaseLabelNotTheCache() {
        PropertyKey name = this.pk(1, "name", DataType.TEXT);
        VertexLabel label = new VertexLabel(this.graph, IdGenerator.of(1), "person");
        label.properties(name.id());
        IndexLabel byName = this.indexLabel(11, "byName", label, name);
        IndexLabel byName2 = this.indexLabel(12, "byName2", label, name);
        label.addIndexLabel(byName.id());
        this.pd.addSchema("DEFAULT", GRAPH, name, byName, label);
        // Cached with index label 11, as a reader fills the cache
        Assert.assertEquals(Set.of(byName.id()),
                            this.tx.getVertexLabel(label.id()).indexLabels());

        // The removal of 11 committed, its invalidation has not run yet
        VertexLabel removed = new VertexLabel(this.graph, label.id(), "person");
        removed.properties(name.id());
        this.pd.updateSchema("DEFAULT", GRAPH, removed);
        Assert.assertEquals(Set.of(byName.id()),
                            this.tx.getVertexLabel(label.id()).indexLabels());
        // The add of 12 holds the lock now
        this.tx.addIndexLabel(label, byName2);
        Assert.assertEquals(Set.of(byName2.id()), this.storedIndexLabels(label));

        // The other order: the add of 13 committed, the removal of 12 runs on a stale cache
        IndexLabel byName3 = this.indexLabel(13, "byName3", label, name);
        Assert.assertEquals(Set.of(byName2.id()),
                            this.tx.getVertexLabel(label.id()).indexLabels());
        VertexLabel added = new VertexLabel(this.graph, label.id(), "person");
        added.properties(name.id());
        added.addIndexLabel(byName2.id());
        added.addIndexLabel(byName3.id());
        this.pd.addSchema("DEFAULT", GRAPH, byName3, added);
        this.tx.removeIndexLabelFromBaseLabel(byName2);
        Assert.assertEquals(Set.of(byName3.id()), this.storedIndexLabels(label));
    }

    /**
     * A label update writes the object its caller read, from the cache: an index label
     * removed or added in between keeps its change, since index label membership belongs to
     * the index label helpers
     */
    @Test
    public void testLabelUpdateKeepsTheStoredIndexLabels() {
        PropertyKey name = this.pk(1, "name", DataType.TEXT);
        PropertyKey age = this.pk(2, "age", DataType.INT);
        VertexLabel label = new VertexLabel(this.graph, IdGenerator.of(1), "person");
        label.properties(name.id());
        IndexLabel byName = this.indexLabel(11, "byName", label, name);
        label.addIndexLabel(byName.id());
        this.pd.addSchema("DEFAULT", GRAPH, name, age, byName, label);

        // An append reads the label, then the removal of byName runs, then the append writes
        VertexLabel read = this.tx.getVertexLabel(label.id());
        this.tx.removeIndexLabelFromBaseLabel(this.tx.getIndexLabel(byName.id()));
        read.properties(age.id());
        read.nullableKeys(age.id());
        this.tx.updateVertexLabel(read);
        VertexLabel stored = this.pd.getVertexLabel("DEFAULT", GRAPH, label.id());
        Assert.assertEquals(Set.of(), stored.indexLabels());
        Assert.assertTrue(stored.properties().contains(age.id()));

        // The other order: an index label added in between stays
        VertexLabel again = this.tx.getVertexLabel(label.id());
        IndexLabel byAge = this.indexLabel(12, "byAge", label, age);
        this.tx.addIndexLabel(this.tx.getVertexLabel(label.id()), byAge);
        this.tx.updateVertexLabel(again);
        Assert.assertEquals(Set.of(byAge.id()), this.storedIndexLabels(label));
    }

    private Set<Id> storedIndexLabels(VertexLabel label) {
        return this.pd.getVertexLabel("DEFAULT", GRAPH, label.id()).indexLabels();
    }

    /**
     * The same for a removal: the index label left the cached base label before the commit
     */
    @Test
    public void testFailedIndexLabelRemovalDoesNotLeaveTheChangedBaseLabelCached() {
        PropertyKey name = this.pk(1, "name", DataType.TEXT);
        VertexLabel label = new VertexLabel(this.graph, IdGenerator.of(1), "person");
        label.properties(name.id());
        IndexLabel byName = this.indexLabel(11, "byName", label, name);
        label.addIndexLabel(byName.id());
        this.pd.addSchema("DEFAULT", GRAPH, name, byName, label);
        VertexLabel cached = this.tx.getVertexLabel(label.id());
        Assert.assertEquals(Set.of(byName.id()), cached.indexLabels());

        Mockito.doThrow(new IllegalStateException("PD rejected the commit"))
               .when(this.driver).commit(Mockito.any());
        Assert.assertThrows(RuntimeException.class, () -> {
            this.tx.removeIndexLabelFromBaseLabel(this.tx.getIndexLabel(byName.id()));
        });

        VertexLabel read = this.tx.getVertexLabel(label.id());
        Assert.assertNotSame(cached, read);
        Assert.assertEquals(Set.of(byName.id()), read.indexLabels());
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
        this.pd.addSchema("DEFAULT", GRAPH, name, byName, byName2, label);

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
        // Runs once, after the next removal of an element
        private volatile Runnable afterInvalidate;

        HookedCache() {
            super(1000L);
        }

        @Override
        public void invalidate(Id id) {
            super.invalidate(id);
            Runnable hook = this.afterInvalidate;
            this.afterInvalidate = null;
            if (hook != null) {
                hook.run();
            }
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
