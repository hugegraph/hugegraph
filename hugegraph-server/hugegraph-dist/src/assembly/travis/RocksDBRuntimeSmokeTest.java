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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;

public final class RocksDBRuntimeSmokeTest {

    private static final byte[] CF = bytes("runtime-smoke");
    private static final byte[] KEY = bytes("key");
    private static final byte[] VALUE = bytes("value-before-restart");
    private static final byte[] RECREATED_VALUE = bytes("value-after-recreate");

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: <rocksdb|topling> <db-path> <expected-native-path|none> <probe|lifecycle>");
        }

        String provider = args[0];
        String dbPath = args[1];
        String expectedNativePath = args[2];
        String mode = args[3];
        if (!"probe".equals(mode) && !"lifecycle".equals(mode)) {
            throw new IllegalArgumentException("Unsupported mode: " + mode);
        }
        phase("runtime-check");
        verifyProvider(provider);
        verifyEasyMigrateConfig(provider);
        RocksDB.loadLibrary();
        verifyNativeLibrary(expectedNativePath);

        if ("probe".equals(mode)) {
            probeReadWrite(dbPath);
        } else {
            createAndWrite(dbPath);
            reopenDropAndRecreate(dbPath);
        }
        phase("complete");
        System.out.printf("Runtime smoke test passed: provider=%s, version=%s%n",
                          provider, RocksDB.rocksdbVersion());
    }

    private static void phase(String phase) {
        System.out.println("Topling diagnostic phase: " + phase);
        System.out.flush();
    }

    private static void probeReadWrite(String dbPath) throws Exception {
        try (Options options = new Options().setCreateIfMissing(true);
             RocksDB db = RocksDB.open(options, dbPath)) {
            db.put(KEY, VALUE);
            assertBytes(VALUE, db.get(KEY), "probe read");
            try (RocksIterator iterator = db.newIterator()) {
                iterator.seekToFirst();
                if (!iterator.isValid()) {
                    throw new AssertionError("probe iterator returned no data");
                }
                assertBytes(KEY, iterator.key(), "probe iterator key");
                assertBytes(VALUE, iterator.value(), "probe iterator value");
            }
        }
    }

    private static void verifyProvider(String provider) {
        boolean hasToplingApi;
        try {
            Class.forName("org.rocksdb.SidePluginRepo", false,
                          RocksDBRuntimeSmokeTest.class.getClassLoader());
            hasToplingApi = true;
        } catch (ClassNotFoundException ignored) {
            hasToplingApi = false;
        }

        if (!"rocksdb".equals(provider) && !"topling".equals(provider)) {
            throw new IllegalArgumentException("Unsupported provider: " + provider);
        }
        if ("rocksdb".equals(provider) && hasToplingApi) {
            throw new IllegalStateException("Standard provider loaded a Topling JAR");
        }
        if ("topling".equals(provider) && !hasToplingApi) {
            throw new IllegalStateException("Topling provider loaded no Topling API");
        }
    }

    private static void verifyEasyMigrateConfig(String provider) {
        String config = System.getenv("TOPLINGDB_EASY_MIGRATE_CONF");
        if ("rocksdb".equals(provider)) {
            if (config != null && !config.isEmpty()) {
                throw new IllegalStateException(
                        "Standard RocksDB test must not enable Easy Migrate");
            }
            return;
        }
        if (config == null || config.isEmpty()) {
            throw new IllegalStateException(
                    "ToplingDB functional test requires Easy Migrate config");
        }
        if (!Files.isReadable(Paths.get(config))) {
            throw new IllegalStateException(
                    "Easy Migrate config is not readable: " + config);
        }
        System.out.println("Verified Easy Migrate config: " +
                           Paths.get(config).toAbsolutePath().normalize());
    }

    private static void verifyNativeLibrary(String expectedNativePath)
            throws Exception {
        if ("none".equals(expectedNativePath)) {
            return;
        }
        String maps = new String(Files.readAllBytes(Paths.get("/proc/self/maps")),
                                 StandardCharsets.UTF_8);
        String absolutePath = Paths.get(expectedNativePath).toAbsolutePath()
                                   .normalize().toString();
        if (!maps.contains(absolutePath)) {
            throw new IllegalStateException("Expected native library is not mapped: " +
                                            absolutePath);
        }
        for (String line : maps.split("\\R")) {
            if (line.contains("librocksdbjni") && !line.contains(absolutePath)) {
                throw new IllegalStateException(
                        "An unexpected RocksDB JNI library is also mapped: " + line);
            }
        }
        System.out.println("Verified native library: " + absolutePath);
    }

    private static void createAndWrite(String dbPath) throws Exception {
        try (DiagnosticResources resources = new DiagnosticResources()) {
            try {
                Options options = resources.add(new Options().setCreateIfMissing(true));
                RocksDB db = resources.add(RocksDB.open(options, dbPath));
                ColumnFamilyOptions cfOptions = resources.add(new ColumnFamilyOptions());
                ColumnFamilyHandle handle = resources.add(db.createColumnFamily(
                        new ColumnFamilyDescriptor(CF, cfOptions)));
                db.put(handle, KEY, VALUE);
                assertBytes(VALUE, db.get(handle, KEY), "initial read");
                try (RocksIterator iterator = db.newIterator(handle)) {
                    iterator.seekToFirst();
                    if (!iterator.isValid()) {
                        throw new AssertionError("iterator returned no data");
                    }
                    assertBytes(KEY, iterator.key(), "iterator key");
                    assertBytes(VALUE, iterator.value(), "iterator value");
                }
                phase("cf-lifecycle");
            } catch (Exception | Error failure) {
                // Record before native cleanup can abort and hide the Java error.
                failed(failure);
                throw failure;
            }
        }
    }

    private static void reopenDropAndRecreate(String dbPath) throws Exception {
        phase("runtime-check");
        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        try (Options options = new Options()) {
            for (byte[] name : RocksDB.listColumnFamilies(options, dbPath)) {
                descriptors.add(new ColumnFamilyDescriptor(name));
            }
        }

        try (DiagnosticResources resources = new DiagnosticResources()) {
            try {
                DBOptions options = resources.add(new DBOptions().setCreateIfMissing(false));
                List<ColumnFamilyHandle> handles = new ArrayList<>();
                RocksDB db = resources.add(RocksDB.open(options, dbPath, descriptors, handles));
                for (ColumnFamilyHandle handle : handles) {
                    resources.add(handle);
                }
                ColumnFamilyHandle smoke = findHandle(descriptors, handles, CF);
                assertBytes(VALUE, db.get(smoke, KEY), "read after reopen");
                phase("cf-lifecycle");
                db.dropColumnFamily(smoke);
                smoke.close();
                resources.remove(smoke);

                ColumnFamilyOptions cfOptions = resources.add(new ColumnFamilyOptions());
                ColumnFamilyHandle recreated = resources.add(db.createColumnFamily(
                        new ColumnFamilyDescriptor(CF, cfOptions)));
                phase("runtime-check");
                db.put(recreated, KEY, RECREATED_VALUE);
                assertBytes(RECREATED_VALUE, db.get(recreated, KEY),
                            "read after CF recreation");
                phase("cf-lifecycle");
            } catch (Exception | Error failure) {
                failed(failure);
                throw failure;
            }
        }
    }

    private static void failed(Throwable failure) {
        phase("failed");
        failure.printStackTrace(System.err);
        System.err.flush();
    }

    // Mark a thrown close failure before closing the next resource: otherwise a
    // secondary native abort could hide it and look like the known CF assertion.
    static final class DiagnosticResources implements AutoCloseable {

        private final List<AutoCloseable> resources = new ArrayList<>();

        <T extends AutoCloseable> T add(T resource) {
            this.resources.add(resource);
            return resource;
        }

        void remove(AutoCloseable resource) {
            // Native-backed equals() may dereference a handle after close().
            for (int i = 0; i < this.resources.size(); i++) {
                if (this.resources.get(i) == resource) {
                    this.resources.remove(i);
                    return;
                }
            }
        }

        @Override
        public void close() throws Exception {
            Throwable failure = null;
            for (int i = this.resources.size() - 1; i >= 0; i--) {
                try {
                    this.resources.get(i).close();
                } catch (Exception | Error current) {
                    failed(current);
                    if (failure == null) {
                        failure = current;
                    } else {
                        failure.addSuppressed(current);
                    }
                }
            }
            if (failure instanceof Exception) {
                throw (Exception) failure;
            }
            if (failure != null) {
                throw (Error) failure;
            }
        }
    }

    private static ColumnFamilyHandle findHandle(
            List<ColumnFamilyDescriptor> descriptors,
            List<ColumnFamilyHandle> handles,
            byte[] name) {
        for (int i = 0; i < descriptors.size(); i++) {
            if (Arrays.equals(name, descriptors.get(i).getName())) {
                return handles.get(i);
            }
        }
        throw new IllegalStateException("Column family was not reopened");
    }

    private static void assertBytes(byte[] expected, byte[] actual,
                                    String operation) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(operation + " returned unexpected data");
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private RocksDBRuntimeSmokeTest() {
    }
}
