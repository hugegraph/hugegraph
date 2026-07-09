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

package org.apache.hugegraph.rocksdb.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

public class StandardRocksDBProviderTest {

    private StandardRocksDBProvider provider;
    private Path tempDir;

    @Before
    public void setUp() throws IOException {
        provider = new StandardRocksDBProvider();
        tempDir = Files.createTempDirectory("rocksdb-test-");
    }

    @After
    public void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                 .sorted(Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(File::delete);
        }
    }

    /**
     * Test: getProviderName() returns "standard" - the identifier used for config matching.
     */
    @Test
    public void testProviderName() {
        assertEquals("standard", provider.getProviderName());
    }

    /**
     * Test: isAvailable() returns true when the RocksDB native library can be loaded.
     * In a normal test environment with rocksdbjni on classpath, this should always pass.
     */
    @Test
    public void testIsAvailable() {
        assertTrue(provider.isAvailable());
    }

    /**
     * Test: openRocksDB with simple Options creates a working database that can be closed.
     * Verifies the basic open/close lifecycle works with the standard provider.
     */
    @Test
    public void testOpenAndCloseRocksDB() throws RocksDBException {
        Options options = new Options().setCreateIfMissing(true);
        String dbPath = tempDir.resolve("test-db").toString();

        RocksDB db = provider.openRocksDB(options, dbPath);
        assertNotNull(db);

        // Verify the database is functional
        db.put("key".getBytes(), "value".getBytes());
        byte[] result = db.get("key".getBytes());
        assertNotNull(result);
        assertEquals("value", new String(result));

        provider.closeRocksDB(db);
    }

    /**
     * Test: openRocksDB with column families creates a working database with multiple CFs.
     * Verifies the DBOptions + ColumnFamilyDescriptor path works correctly.
     */
    @Test
    public void testOpenWithColumnFamilies() throws RocksDBException {
        String dbPath = tempDir.resolve("test-cf-db").toString();

        // First create the database with default CF
        Options createOptions = new Options().setCreateIfMissing(true);
        RocksDB createDb = RocksDB.open(createOptions, dbPath);
        createDb.close();

        // Now reopen with column families via the provider
        DBOptions dbOptions = new DBOptions().setCreateIfMissing(true);
        List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();
        cfDescriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY));
        List<ColumnFamilyHandle> cfHandles = new ArrayList<>();

        RocksDB db = provider.openRocksDB(dbOptions, dbPath, cfDescriptors, cfHandles);
        assertNotNull(db);
        assertEquals(1, cfHandles.size());

        // Write and read through the CF handle
        db.put(cfHandles.get(0), "cf-key".getBytes(), "cf-value".getBytes());
        byte[] result = db.get(cfHandles.get(0), "cf-key".getBytes());
        assertEquals("cf-value", new String(result));

        provider.closeRocksDB(db, cfHandles);
    }

    /**
     * Test: Passing optionPath to the standard provider does not cause an error.
     * The standard provider ignores optionPath (it's a ToplingDB-only parameter)
     * and should open the database normally with a warning log.
     */
    @Test
    public void testOpenIgnoresOptionPath() throws RocksDBException {
        Options options = new Options().setCreateIfMissing(true);
        String dbPath = tempDir.resolve("test-option-path").toString();

        RocksDB db = provider.openRocksDB(options, dbPath, "/some/path.yaml", null);
        assertNotNull(db);
        provider.closeRocksDB(db);
    }

    /**
     * Test: Passing openHttp=true to the standard provider does not cause an error.
     * The HTTP monitoring server is a ToplingDB-only feature, the standard provider
     * should ignore it and open normally with a warning log.
     */
    @Test
    public void testOpenIgnoresOpenHttp() throws RocksDBException {
        Options options = new Options().setCreateIfMissing(true);
        String dbPath = tempDir.resolve("test-open-http").toString();

        RocksDB db = provider.openRocksDB(options, dbPath, null, true);
        assertNotNull(db);
        provider.closeRocksDB(db);
    }

    /**
     * Test: closeRocksDB(null) is safe and does not throw any exception.
     * Callers should not need to null-check before calling close.
     */
    @Test
    public void testCloseWithNull() {
        provider.closeRocksDB(null);
    }

    /**
     * Test: The provider works correctly when accessed through the RocksDBProviderLoader
     * static convenience methods after selectProvider("standard") is called.
     * This is the actual usage path in production code.
     */
    @Test
    public void testOpenViaProviderLoader() throws RocksDBException {
        RocksDBProviderLoader loader = RocksDBProviderLoader.getInstance();
        loader.reload();
        loader.selectProvider("standard");

        Options options = new Options().setCreateIfMissing(true);
        String dbPath = tempDir.resolve("test-loader").toString();

        RocksDB db = RocksDBProviderLoader.openRocksDB(options, dbPath, null, null);
        assertNotNull(db);
        RocksDBProviderLoader.closeRocksDB(db);
    }
}
