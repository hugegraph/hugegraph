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

package org.apache.hugegraph.store.meta;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.hugegraph.rocksdb.access.RocksDBSession;
import org.apache.hugegraph.store.UnitTestBase;
import org.apache.hugegraph.store.business.BusinessHandlerImpl;
import org.apache.hugegraph.store.meta.base.DBSessionBuilder;
import org.apache.hugegraph.store.util.HgStoreException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class GraphIDManagerTest extends UnitTestBase {

    private static final int PARTITION_ID = 0;
    private static final int GRAPH_ID_LIMIT = 8;

    private String dbName;
    private File dbPath;
    private RocksDBSession session;
    private DBSessionBuilder sessionBuilder;
    private int previousMaxGraphId;

    @Before
    public void init() {
        this.previousMaxGraphId = GraphIdManager.maxGraphID;
        GraphIdManager.maxGraphID = GRAPH_ID_LIMIT;
        this.dbName = "graph-id-manager-" + System.nanoTime();
        this.dbPath = new File("target/graph-id-manager-test", this.dbName);
        Map<String, Object> config = new HashMap<>();
        config.put("rocksdb.write_buffer_size", "1048576");
        config.put("rocksdb.bloom_filter_bits_per_key", "10");
        BusinessHandlerImpl.initRocksdb(config, null);
        this.session = factory.createGraphDB(this.dbPath.getAbsolutePath(),
                                             this.dbName);
        this.sessionBuilder = partId -> this.session.clone();
    }

    @After
    public void clear() {
        GraphIdManager.maxGraphID = this.previousMaxGraphId;
        if (this.session != null) {
            this.session.close();
        }
        if (this.dbName != null) {
            factory.releaseGraphDB(this.dbName);
        }
        if (this.dbPath != null) {
            UnitTestBase.deleteDir(this.dbPath);
        }
    }

    @Test
    public void testAllocateBoundaryIdBeforeWrap() {
        GraphIdManager manager = this.newManager();

        Assert.assertEquals(0L, manager.getCId("boundary", 4));
        Assert.assertEquals(1L, manager.getCId("boundary", 4));
        Assert.assertEquals(2L, manager.getCId("boundary", 4));
        Assert.assertEquals(3L, manager.getCId("boundary", 4));
    }

    @Test
    public void testReturnMinusOneWhenAllIdsAreUsed() {
        GraphIdManager manager = this.newManager();

        for (int i = 0; i < 4; i++) {
            Assert.assertEquals(i, manager.getCId("full", 4));
        }
        Assert.assertEquals(-1L, manager.getCId("full", 4));
    }

    @Test
    public void testReuseReleasedId() {
        GraphIdManager manager = this.newManager();

        for (int i = 0; i < 4; i++) {
            Assert.assertEquals(i, manager.getCId("release", 4));
        }
        manager.delCId("release", 1L);

        Assert.assertEquals(1L, manager.getCId("release", 4));
        Assert.assertEquals(-1L, manager.getCId("release", 4));
    }

    @Test
    public void testWrapToBeginningAfterBoundary() {
        GraphIdManager manager = this.newManager();

        Assert.assertEquals(0L, manager.getCId("wrap", 4));
        Assert.assertEquals(1L, manager.getCId("wrap", 4));
        manager.delCId("wrap", 0L);
        Assert.assertEquals(2L, manager.getCId("wrap", 4));
        Assert.assertEquals(3L, manager.getCId("wrap", 4));

        Assert.assertEquals(0L, manager.getCId("wrap", 4));
    }

    @Test
    public void testPersistGraphIdAcrossManagerRestart() {
        GraphIdManager firstManager = this.newManager();

        Assert.assertEquals(0L, firstManager.getGraphIdOrCreate("first"));

        GraphIdManager restartedManager = this.newManager();
        Assert.assertEquals(0L, restartedManager.getGraphId("first"));
        Assert.assertEquals(1L, restartedManager.getGraphIdOrCreate("second"));
    }

    @Test
    public void testGraphIdDomainExcludesMissingSentinel() {
        GraphIdManager manager = this.newManager();

        for (int i = 0; i < GRAPH_ID_LIMIT; i++) {
            Assert.assertEquals(i, manager.getGraphIdOrCreate("graph-" + i));
        }
        Assert.assertEquals(GRAPH_ID_LIMIT, manager.getGraphId("missing"));
        Assert.assertThrows(HgStoreException.class,
                            () -> manager.getGraphIdOrCreate("overflow"));
    }

    @Test
    public void testConcurrentCreateSameGraphReturnsSameId() throws Exception {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>(threadCount);

        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    GraphIdManager manager = this.newManager();
                    start.await();
                    return manager.getGraphIdOrCreate("same-graph");
                }));
            }
            start.countDown();

            Set<Long> graphIds = new HashSet<>();
            for (Future<Long> future : futures) {
                graphIds.add(future.get(10L, TimeUnit.SECONDS));
            }
            Assert.assertEquals(1, graphIds.size());
            Assert.assertTrue(graphIds.contains(0L));
        } finally {
            executor.shutdownNow();
        }
    }

    private GraphIdManager newManager() {
        return new GraphIdManager(this.sessionBuilder, PARTITION_ID);
    }
}
