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

package org.apache.hugegraph.backend.store.rocksdb;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.hugegraph.backend.BackendException;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.unit.FakeObjects;
import org.apache.hugegraph.testutil.Whitebox;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RocksDBSnapshotRestoreTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void testDataCopyFailureRetainsCheckpointAndRetries() throws Exception {
        this.failureAndRetry("data-copy", "separate");
    }

    @Test
    public void testWalCopyFailureRetainsCheckpointAndRetries() throws Exception {
        this.failureAndRetry("copy", "separate");
    }

    @Test
    public void testShortCopyIsNotPublished() throws Exception {
        this.failureAndRetry("short-copy", "separate");
    }

    @Test
    public void testSameLengthCorruptionIsNotPublished() throws Exception {
        this.failureAndRetry("corrupt-copy", "separate");
    }

    @Test
    public void testRetirementFailureRetainsMarkerAndRetries() throws Exception {
        this.failureAndRetry("retire", "separate");
    }

    @Test
    public void testPublishFailureRetainsMarkerAndRetries() throws Exception {
        this.failureAndRetry("publish", "separate");
    }

    @Test
    public void testParentWalRetirementFailurePreservesData() throws Exception {
        this.failureAndRetry("retire", "parent");
    }

    @Test
    public void testNestedWalPublishFailureRetries() throws Exception {
        this.failureAndRetry("publish", "nested");
    }

    @Test
    public void testSymlinkWalPublishFailurePreservesLink() throws Exception {
        this.failureAndRetry("publish", "symlink");
    }

    @Test
    public void testSymlinkInsideDataSurvivesInterruptedRestore() throws Exception {
        this.failureAndRetry("data-copy", "nested-symlink");
    }

    @Test
    public void testIndirectSymlinkInsideDataSurvivesInterruptedRestore() throws Exception {
        this.failureAndRetry("data-copy", "ancestor-symlink");
    }

    @Test
    public void testCorruptedDataCopyIsNotOpened() throws Exception {
        this.failureAndRetry("corrupt-data-copy", "separate");
    }

    private static void fakeCheckpoint(File snapshot) throws IOException {
        Files.write(new File(snapshot, "CURRENT").toPath(), "MANIFEST-000001\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(snapshot, "MANIFEST-000001").toPath(), new byte[]{1, 2, 3});
    }

    private void failureAndRetry(String fault, String layout) throws Exception {
        File root = this.temporary.newFolder();
        File data = new File(root, "data");
        File wal = new File(root, "wal");
        File snapshot = new File(root, "snapshot");
        if ("parent".equals(layout)) {
            data = new File(wal, "data");
            snapshot = new File(wal, "checkpoints/snapshot");
        } else if ("nested".equals(layout)) {
            wal = new File(data, "wal");
        }
        FileUtils.forceMkdir(data);
        if (layout.contains("symlink")) {
            File target = new File(root, "target");
            FileUtils.forceMkdir(target);
            File linkParent = "nested-symlink".equals(layout) || "ancestor-symlink".equals(layout) ? data : root;
            wal = new File(linkParent, "wal-link");
            Files.createSymbolicLink(wal.toPath(), target.toPath());
            if ("ancestor-symlink".equals(layout)) {
                wal = new File(wal, "child-wal");
                FileUtils.forceMkdir(wal);
            }
        } else {
            FileUtils.forceMkdir(wal);
        }
        FileUtils.forceMkdir(snapshot);
        fakeCheckpoint(snapshot);
        byte[] tail = "checkpoint".getBytes(StandardCharsets.UTF_8);
        FileUtils.writeByteArrayToFile(new File(snapshot, "000123.log"), tail);
        FileUtils.writeByteArrayToFile(new File(snapshot, "keep.txt"), tail);
        // Same-name, same-length old WAL must never be accepted as the checkpoint.
        FileUtils.writeByteArrayToFile(new File(wal, "000123.log"),
                                      "old-oldwal".getBytes(StandardCharsets.UTF_8));
        FileUtils.writeByteArrayToFile(new File(wal, "000124.log"), tail);
        RocksDBSnapshotRestore restore = new RocksDBSnapshotRestore(
                data.toString(), wal.toString(), snapshot.toString(), new FaultyFiles(fault));
        restore.begin();
        try {
            restore.install();
            fail("Expected injected " + fault);
        } catch (IOException expected) {
            assertTrue(new File(data + ".resume-pending").isFile());
            assertArrayEquals(tail, Files.readAllBytes(new File(snapshot, "000123.log").toPath()));
        }
        // The public resume path can retry the SAME source even if its WAL alias
        // disappeared between deleting data and copying the checkpoint.
        RocksDBSnapshotRestore.start(data.toString(), wal.toString(), snapshot.toString());
        RocksDBSnapshotRestore retry = RocksDBSnapshotRestore.prepareOpen(data.toString(), wal.toString());
        assertNotNull(retry);
        assertArrayEquals(tail, Files.readAllBytes(new File(wal, "000123.log").toPath()));
        assertFalse(new File(wal, "000124.log").exists());
        assertFalse(new File(data, "000123.log").exists());
        assertTrue(new File(data, "keep.txt").isFile());
        assertTrue(new File(snapshot, "000123.log").isFile());
        if (layout.contains("symlink")) {
            assertTrue(Files.isSymbolicLink("ancestor-symlink".equals(layout) ?
                                           wal.getParentFile().toPath() : wal.toPath()));
        }
        retry.complete();
        assertFalse(new File(data + ".resume-pending").exists());
    }

    @Test
    public void testWalNameCollisionFailsClosed() throws Exception {
        File data = this.temporary.newFolder("data");
        File wal = this.temporary.newFolder("wal");
        File snapshot = this.temporary.newFolder("snapshot");
        fakeCheckpoint(snapshot);
        FileUtils.writeByteArrayToFile(new File(snapshot, "000123.log"), new byte[]{1});
        FileUtils.forceMkdir(new File(wal, "000123.log"));
        RocksDBSnapshotRestore restore = new RocksDBSnapshotRestore(
                data.toString(), wal.toString(), snapshot.toString(),
                new RocksDBSnapshotRestore.FileOperations());
        restore.begin();
        try {
            RocksDBSnapshotRestore.prepareOpen(data.toString(), wal.toString());
            fail("WAL directory collision must fail closed");
        } catch (BackendException expected) {
            assertTrue(new File(data + ".resume-pending").isFile());
            assertTrue(new File(snapshot, "000123.log").isFile());
        }
    }

    @Test
    public void testFreshSessionRecoversPendingSnapshotBeforeNativeOpen() throws Exception {
        File data = this.temporary.newFolder("data");
        File wal = this.temporary.newFolder("wal");
        File snapshot = new File(this.temporary.newFolder("snapshot-root"), "rocks");
        HugeConfig config = FakeObjects.newConfig();
        RocksDBSessions original = new RocksDBStdSessions(config, "db", "store", data.toString(), wal.toString());
        byte[] key = new byte[]{1};
        try {
            original.createTable("test");
            original.session().put("test", key, new byte[]{2});
            original.session().commit();
            original.createSnapshot(snapshot.toString());
            original.session().put("test", key, new byte[]{3});
            original.session().commit();
        } finally {
            original.close();
        }
        RocksDBSnapshotRestore restore = new RocksDBSnapshotRestore(
                data.toString(), wal.toString(), snapshot.toString(), new FaultyFiles("data-copy"));
        restore.begin();
        try {
            restore.install();
            fail("Expected data copy failure");
        } catch (IOException expected) {
            assertTrue(new File(data + ".resume-pending").exists());
        }
        RocksDBSessions fresh = new RocksDBStdSessions(config, "db", "store", data.toString(), wal.toString(),
                                                       Collections.singletonList("test"));
        try {
            assertArrayEquals(new byte[]{2}, fresh.session().get("test", key));
            assertFalse(new File(data + ".resume-pending").exists());
            assertFalse(snapshot.exists());
        } finally {
            fresh.close();
        }
    }

    @Test
    public void testBothOpenOverloadsRejectIncompleteMarker() throws Exception {
        File data = this.temporary.newFolder("data");
        File marker = new File(data + ".resume-pending");
        Files.write(marker.toPath(), new byte[0]);
        HugeConfig config = FakeObjects.newConfig();
        for (boolean withTables : new boolean[]{false, true}) {
            try {
                if (withTables) {
                    new RocksDBStdSessions(config, "db", "store", data.toString(), data.toString(),
                                           Collections.emptyList());
                } else {
                    new RocksDBStdSessions(config, "db", "store", data.toString(), data.toString());
                }
                fail("Native open must not bypass incomplete marker");
            } catch (BackendException expected) {
                assertEquals(0, data.list().length);
                assertTrue(marker.isFile());
            }
        }
    }

    @Test
    public void testCompetingRecoveryCannotEnterWhileOwnerHoldsLock() throws Exception {
        File data = this.temporary.newFolder("data");
        try (FileChannel owner = RocksDBSnapshotRestore.lock(data.toString())) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread contender = new Thread(() -> {
                try {
                    new RocksDBStdSessions(FakeObjects.newConfig(), "db", "store",
                                           data.toString(), data.toString());
                    failure.set(new AssertionError("Competing open acquired recovery lock"));
                } catch (BackendException expected) {
                    // Fail before data/WAL or native DB is touched.
                } catch (Throwable e) {
                    failure.set(e);
                } finally {
                    done.countDown();
                }
            });
            contender.start();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
            assertEquals(0, data.list().length);
        }
        try (FileChannel retry = RocksDBSnapshotRestore.lock(data.toString())) {
            assertTrue(retry.isOpen());
        }
    }

    @Test
    public void testLiveDatabasePreventsPendingRecovery() throws Exception {
        File data = this.temporary.newFolder("data");
        File snapshot = new File(this.temporary.newFolder("snapshot-root"), "rocks");
        RocksDBSessions owner = new RocksDBStdSessions(FakeObjects.newConfig(), "db", "store",
                                                       data.toString(), data.toString());
        try {
            owner.createSnapshot(snapshot.toString());
            RocksDBSnapshotRestore restore = new RocksDBSnapshotRestore(
                    data.toString(), data.toString(), snapshot.toString(),
                    new RocksDBSnapshotRestore.FileOperations());
            restore.begin();
            byte[] manifest = Files.readAllBytes(new File(data, "CURRENT").toPath());
            try {
                new RocksDBStdSessions(FakeObjects.newConfig(), "db", "store",
                                       data.toString(), data.toString());
                fail("Must not restore under an open native owner");
            } catch (BackendException expected) {
                assertArrayEquals(manifest, Files.readAllBytes(new File(data, "CURRENT").toPath()));
                assertTrue(owner.databaseOpened());
                assertTrue(snapshot.isDirectory());
            }
        } finally {
            owner.forceCloseRocksDB();
        }
        RocksDBSessions recovered = new RocksDBStdSessions(FakeObjects.newConfig(), "db", "store",
                                                           data.toString(), data.toString());
        recovered.forceCloseRocksDB();
        assertFalse(new File(data + ".resume-pending").exists());
    }

    @Test
    public void testRepeatedAdapterSnapshotResumePreservesConsumeSemantics() throws Exception {
        File root = this.temporary.newFolder("adapter");
        // createCheckpoint's existing assertion requires a snapshot-prefixed parent.
        File data = new File(root, "snapshot-data/store");
        File wal = new File(root, "wal");
        RocksDBSessions sessions = new RocksDBStdSessions(FakeObjects.newConfig(), "db", "store",
                                                          data.toString(), wal.toString());
        RocksDBStore store = new RocksDBStore.RocksDBGraphStore(new RocksDBStoreProvider(), "db", "store");
        Whitebox.setInternalState(store, "sessions", sessions);
        Map<String, RocksDBSessions> databases = Whitebox.getInternalState(store, "dbs");
        databases.put(data.toString(), sessions);
        byte[] key = new byte[]{1};
        try {
            sessions.createTable("test");
            sessions.session().put("test", key, new byte[]{2});
            sessions.session().commit();
            store.createSnapshot("snapshot");
            String snapshot = sessions.buildSnapshotPath("snapshot");
            for (boolean consume : new boolean[]{false, false, true}) {
                sessions.session().put("test", key, new byte[]{3});
                sessions.session().commit();
                store.resumeSnapshot("snapshot", consume);
                assertArrayEquals(new byte[]{2}, sessions.session().get("test", key));
                assertEquals(!consume, new File(snapshot).exists());
                assertFalse(new File(data + "_temp").exists());
                assertFalse(new File(data + ".resume-pending").exists());
            }
        } finally {
            sessions.close();
        }
    }

    @Test
    public void testMissingManifestRejectedBeforeDataMutation() throws Exception {
        File data = this.temporary.newFolder("data");
        File snapshot = this.temporary.newFolder("snapshot");
        File sentinel = new File(data, "live");
        Files.write(sentinel.toPath(), new byte[]{42});
        try {
            RocksDBSnapshotRestore.start(data.toString(), data.toString(), snapshot.toString());
            fail("Incomplete snapshot must not be installed");
        } catch (IOException expected) {
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(sentinel.toPath()));
            assertFalse(new File(data + ".resume-pending").exists());
        }
    }

    @Test
    public void testNativeOpenFailureKeepsRecoverySourceAndMarker() throws Exception {
        File data = this.temporary.newFolder("data");
        File snapshot = this.temporary.newFolder("snapshot");
        fakeCheckpoint(snapshot);
        RocksDBSnapshotRestore.start(data.toString(), data.toString(), snapshot.toString());
        try {
            new RocksDBStdSessions(FakeObjects.newConfig(), "db", "store", data.toString(), data.toString());
            fail("Invalid native manifest must not report success");
        } catch (org.rocksdb.RocksDBException expected) {
            assertTrue(new File(data + ".resume-pending").isFile());
            assertArrayEquals(new byte[]{1, 2, 3},
                              Files.readAllBytes(new File(snapshot, "MANIFEST-000001").toPath()));
        }
    }

    @Test
    public void testRecoveryLockExcludesAnotherProcess() throws Exception {
        File data = this.temporary.newFolder("data");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        try (FileChannel owner = RocksDBSnapshotRestore.lock(data.toString())) {
            Process child = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").toString(),
                                               "-cp", classpath, getClass().getName(), data.toString())
                            .redirectErrorStream(true).start();
            try {
                assertTrue("Competing process did not finish", child.waitFor(10, TimeUnit.SECONDS));
                String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(output, 23, child.exitValue());
                assertEquals(0, data.list().length);
            } finally {
                child.destroyForcibly();
            }
        }
    }

    public static void main(String[] args) {
        try (FileChannel channel = RocksDBSnapshotRestore.lock(args[0])) {
            System.out.println("acquired");
        } catch (BackendException expected) {
            System.exit(23);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void testNativeCloseToRestoreTransitionRetainsSameLock() throws Exception {
        File data = this.temporary.newFolder("data");
        File snapshot = new File(this.temporary.newFolder("snapshot-root"), "rocks");
        RocksDBSessions sessions = new RocksDBStdSessions(FakeObjects.newConfig(), "db", "store",
                                                          data.toString(), data.toString());
        try {
            sessions.createTable("test");
            sessions.session().put("test", new byte[]{1}, new byte[]{2});
            sessions.session().commit();
            sessions.createSnapshot(snapshot.toString());
            OpenedRocksDB old = Whitebox.getInternalState(sessions, "rocksdb");
            FileChannel held = old.closeForRestore();
            assertFalse(old.isOwningHandle());
            assertTrue(held.isOpen());
            // Deterministic observation of the exact native-closed/marker-not-yet-created window.
            try {
                new RocksDBStdSessions(FakeObjects.newConfig(), "db", "store",
                                       data.toString(), data.toString());
                fail("Native close must not release recovery ownership");
            } catch (BackendException expected) {
                assertFalse(new File(data + ".resume-pending").exists());
            }
            RocksDBSnapshotRestore.start(data.toString(), data.toString(), snapshot.toString());
            sessions.reloadRocksDB();
            OpenedRocksDB replacement = Whitebox.getInternalState(sessions, "rocksdb");
            assertSame(held, Whitebox.getInternalState(replacement, "recoveryLock"));
            assertArrayEquals(new byte[]{2}, sessions.session().get("test", new byte[]{1}));
        } finally {
            sessions.close();
        }
        try (FileChannel available = RocksDBSnapshotRestore.lock(data.toString())) {
            assertTrue(available.isOpen());
        }
    }

    @Test
    public void testForceCloseReleasesNativeAndRecoveryOwnership() throws Exception {
        File data = this.temporary.newFolder("data");
        RocksDBSessions sessions = new RocksDBStdSessions(FakeObjects.newConfig(), "db", "store",
                                                          data.toString(), data.toString());
        sessions.forceCloseRocksDB();
        assertFalse(sessions.databaseOpened());
        RocksDBSessions fresh = new RocksDBStdSessions(FakeObjects.newConfig(), "db", "store",
                                                       data.toString(), data.toString());
        fresh.forceCloseRocksDB();
    }

    @Test
    public void testRootAndLinuxMountPointRejectedBeforeCreatingRecoveryLock() {
        try {
            RocksDBSnapshotRestore.lock(File.listRoots()[0].toString());
            fail("Filesystem root must not be a store directory");
        } catch (BackendException expected) {
            assertTrue(expected.getCause().getMessage().contains("filesystem root"));
        }
        if (System.getProperty("os.name").startsWith("Linux")) {
            Path lock = new File("/proc.resume-lock").toPath();
            assertFalse(Files.exists(lock));
            try {
                RocksDBSnapshotRestore.lock("/proc");
                fail("Linux mount point must not be a store directory");
            } catch (BackendException expected) {
                assertTrue(expected.getCause().getMessage().contains("mount point"));
            }
            assertFalse(Files.exists(lock));
        }
    }

    private static class FaultyFiles extends RocksDBSnapshotRestore.FileOperations {

        private final String fault;

        FaultyFiles(String fault) {
            this.fault = fault;
        }

        @Override
        void copyDirectory(File source, File target) throws IOException {
            if ("data-copy".equals(this.fault)) {
                throw new IOException("injected data copy failure");
            }
            super.copyDirectory(source, target);
            if ("corrupt-data-copy".equals(this.fault)) {
                Files.write(new File(target, "MANIFEST-000001").toPath(), new byte[]{3, 2, 1});
            }
        }

        @Override
        void copyFile(File source, File target) throws IOException {
            if ("copy".equals(this.fault)) {
                throw new IOException("injected copy failure");
            }
            super.copyFile(source, target);
            if ("short-copy".equals(this.fault)) {
                Files.write(target.toPath(), new byte[]{0});
            } else if ("corrupt-copy".equals(this.fault)) {
                Files.write(target.toPath(), new byte[(int) target.length()]);
            }
        }

        @Override
        void move(Path source, Path target) throws IOException {
            if (("retire".equals(this.fault) && target.toString().endsWith(".aside")) ||
                ("publish".equals(this.fault) && target.toString().endsWith(".log"))) {
                throw new IOException("injected " + this.fault + " failure");
            }
            super.move(source, target);
        }
    }
}
