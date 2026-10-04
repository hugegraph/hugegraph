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

package org.apache.hugegraph.pd.core.store;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReadWriteLock;

import org.apache.commons.io.FileUtils;
import org.apache.hugegraph.pd.config.PDConfig;
import org.apache.hugegraph.pd.store.HgKVStoreImpl;
import org.apache.hugegraph.testutil.Whitebox;
import org.junit.Assert;
import org.junit.Test;
import org.rocksdb.RocksDB;

public class HgKVStoreShutdownTest {

    @Test
    public void testNativeCloseWaitsForOwnerAndAllowsPersistentReopen() throws Exception {
        RocksDB.loadLibrary();
        Path directory = Files.createTempDirectory("pd-shutdown-test-");
        ExecutorService closer = Executors.newSingleThreadExecutor();
        HgKVStoreImpl store = new HgKVStoreImpl();
        PDConfig config = new PDConfig();
        config.setDataPath(directory.toString());
        byte[] key = "key".getBytes(StandardCharsets.UTF_8);
        byte[] value = "value".getBytes(StandardCharsets.UTF_8);
        try {
            store.init(config);
            store.put(key, value);
            Assert.assertEquals(1, store.scanPrefix(key).size());
            Assert.assertEquals(1, store.scanRange(key, "kez".getBytes(StandardCharsets.UTF_8)).size());
            ReadWriteLock owners = Whitebox.getInternalState(store, "readWriteLock");
            CountDownLatch attempted = new CountDownLatch(1);
            owners.readLock().lock();
            Future<?> close;
            try {
                close = closer.submit(() -> {
                    attempted.countDown();
                    store.close();
                });
                Assert.assertTrue(attempted.await(5, TimeUnit.SECONDS));
                Assert.assertThrows(TimeoutException.class, () -> close.get(100, TimeUnit.MILLISECONDS));
                Assert.assertNotNull(Whitebox.getInternalState(store, "db"));
            } finally {
                owners.readLock().unlock();
            }
            close.get(5, TimeUnit.SECONDS);
            Assert.assertNull(Whitebox.getInternalState(store, "db"));
            Assert.assertNull(Whitebox.getInternalState(store, "dbOptions"));
            store.close();
            Assert.assertThrows(IllegalStateException.class, () -> store.get(key));
            HgKVStoreImpl reopened = new HgKVStoreImpl();
            try {
                reopened.init(config);
                Assert.assertArrayEquals(value, reopened.get(key));
                reopened.removeByPrefix(key);
                Assert.assertTrue(reopened.scanPrefix(key).isEmpty());
            } finally {
                reopened.close();
            }
        } finally {
            store.close();
            closer.shutdownNow();
            Assert.assertTrue(closer.awaitTermination(5, TimeUnit.SECONDS));
            FileUtils.deleteDirectory(directory.toFile());
        }
    }
}
