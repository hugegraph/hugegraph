/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.backend.store.rocksdb.backup;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Assert;
import org.junit.Test;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;

public class RocksDbBackupExecutorTest {

    static {
        RocksDB.loadLibrary();
    }

    @Test
    public void testCaptureAndRestoreSelectedNativeVersion() throws Exception {
        Path root = Files.createTempDirectory("rocksdb-backup-");
        Path databasePath = root.resolve("database");
        Path repository = root.resolve("repository");
        RocksDbBackupExecutor executor = new RocksDbBackupExecutor("graph-data");
        byte[] key = "key".getBytes(StandardCharsets.UTF_8);

        try (Options options = new Options().setCreateIfMissing(true);
             RocksDB database = RocksDB.open(options, databasePath.toString())) {
            database.put(key, "before".getBytes(StandardCharsets.UTF_8));
            long first = executor.capture(database, repository);

            database.put(key, "after".getBytes(StandardCharsets.UTF_8));
            long second = executor.capture(database, repository);
            database.delete(key);
            long third = executor.capture(database, repository);

            executor.verify(repository, first);
            executor.verify(repository, second);
            executor.verify(repository, third);

            assertValue(executor, repository, first, root.resolve("first"), key, "before");
            assertValue(executor, repository, second, root.resolve("second"), key, "after");
            assertValue(executor, repository, third, root.resolve("third"), key, null);
        }
    }

    private static void assertValue(RocksDbBackupExecutor executor, Path repository,
                                    long backupId, Path target, byte[] key,
                                    String expected) throws Exception {
        executor.restore(repository, backupId, target);
        try (Options options = new Options();
             RocksDB database = RocksDB.open(options, target.toString())) {
            byte[] value = database.get(key);
            if (expected == null) {
                Assert.assertNull(value);
            } else {
                Assert.assertEquals(expected, new String(value, StandardCharsets.UTF_8));
            }
        }
    }
}
