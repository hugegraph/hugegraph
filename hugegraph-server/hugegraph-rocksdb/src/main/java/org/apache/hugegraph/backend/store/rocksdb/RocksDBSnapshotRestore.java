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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.apache.hugegraph.backend.BackendException;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

/**
 * Fail-closed snapshot installation. The marker lives outside the data directory
 * and the original checkpoint remains the recovery source until after reopening.
 * A subsequent open retries the recorded checkpoint before native recovery runs.
 */
final class RocksDBSnapshotRestore {

    private static final Logger LOG = Log.logger(RocksDBSnapshotRestore.class);

    private final File data;
    private final File wal;
    private final File snapshot;
    private final Path marker;
    private final FileOperations files;
    private final Path configuredWal;
    private final List<Path> walLinks = new ArrayList<>();
    private final List<Path> walLinkTargets = new ArrayList<>();

    RocksDBSnapshotRestore(String data, String wal, String snapshot,
                           FileOperations files) throws IOException {
        this.data = new File(data).getCanonicalFile();
        this.wal = wal == null || wal.isEmpty() ? this.data :
                   new File(wal).getCanonicalFile();
        this.snapshot = new File(snapshot).getCanonicalFile();
        this.marker = marker(this.data.toString());
        this.files = files;
        this.configuredWal = wal == null || wal.isEmpty() ? this.data.toPath() :
                             new File(wal).toPath().toAbsolutePath().normalize();
        Path component = this.configuredWal.getRoot();
        for (Path name : this.configuredWal) {
            component = component.resolve(name);
            if (Files.isSymbolicLink(component)) {
                this.walLinks.add(component);
                this.walLinkTargets.add(Files.readSymbolicLink(component));
            }
        }
        if (overlaps(this.snapshot, this.data) ||
            this.wal.toPath().startsWith(this.snapshot.toPath())) {
            throw new IOException("Checkpoint must not overlap data or WAL: " + this.snapshot);
        }
        validateCheckpoint(this.snapshot);
    }

    private static void validateCheckpoint(File snapshot) throws IOException {
        Path current = new File(snapshot, "CURRENT").toPath();
        if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS) || Files.size(current) > 128L) {
            throw new IOException("Checkpoint CURRENT is missing or invalid: " + snapshot);
        }
        String manifest = new String(Files.readAllBytes(current), StandardCharsets.UTF_8).trim();
        if (!manifest.matches("MANIFEST-[0-9]+") ||
            !Files.isRegularFile(new File(snapshot, manifest).toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Checkpoint MANIFEST is missing or invalid: " + snapshot);
        }
    }

    private static boolean overlaps(File first, File second) {
        return first.toPath().startsWith(second.toPath()) ||
               second.toPath().startsWith(first.toPath());
    }

    private static Path marker(String data) throws IOException {
        return new File(new File(data).getCanonicalPath() + ".resume-pending").toPath();
    }

    static FileChannel lock(String data) {
        FileChannel channel = null;
        try {
            Path directory = new File(data).getCanonicalFile().toPath();
            Path parent = directory.getParent();
            if (parent == null) {
                throw new IOException("Database directory cannot be a filesystem root: " + directory);
            }
            // Sibling guards must share the DB's backing parent. Binding just the
            // DB directory hides those guards at aliases and cannot be replaced
            // during restore (EBUSY). Mount the parent data root instead.
            if (Files.isDirectory(directory) &&
                (System.getProperty("os.name").startsWith("Linux") ? isLinuxMountPoint(directory) :
                 !Files.getFileStore(directory).equals(Files.getFileStore(parent)))) {
                throw new IOException("Database directory cannot itself be a mount point; " +
                                      "mount its parent data root instead: " + directory);
            }
            Path path = new File(directory + ".resume-lock").toPath();
            Files.createDirectories(path.getParent());
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            if (channel.tryLock() == null) {
                throw new IOException("Database is already open or recovering: " + data);
            }
            return channel;
        } catch (IOException | OverlappingFileLockException e) {
            unlock(channel);
            throw new BackendException("Cannot lock database for open/recovery: '%s'", e, data);
        }
    }

    private static boolean isLinuxMountPoint(Path directory) throws IOException {
        // FileStore equality cannot detect a bind mount within the same filesystem.
        // Read this process's namespace; kernel mountinfo escapes whitespace and backslashes.
        for (String mount : Files.readAllLines(Paths.get("/proc/self/mountinfo"),
                                              StandardCharsets.UTF_8)) {
            String[] fields = mount.split(" ", 7);
            if (fields.length < 7) {
                throw new IOException("Invalid Linux mountinfo record");
            }
            String path = fields[4].replace("\\040", " ").replace("\\011", "\t")
                                   .replace("\\012", "\n").replace("\\134", "\\");
            if (directory.equals(Paths.get(path))) {
                return true;
            }
        }
        return false;
    }

    static void unlock(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                throw new BackendException("Failed to release database recovery lock", e);
            }
        }
    }

    static void start(String data, String wal, String snapshot) throws IOException {
        Path marker = marker(data);
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            Properties pending = new Properties();
            try (InputStream input = Files.newInputStream(marker)) {
                pending.load(input);
            }
            Path configuredWal = wal == null || wal.isEmpty() ? new File(data).getCanonicalFile().toPath() :
                                 new File(wal).toPath().toAbsolutePath().normalize();
            if (!new File(snapshot).getCanonicalPath().equals(pending.getProperty("snapshot")) ||
                !configuredWal.toString().equals(pending.getProperty("configured-wal"))) {
                throw new IOException("Another or incomplete checkpoint restore is pending: " + marker);
            }
            // prepareOpen will reconstruct links and revalidate the recorded source.
            return;
        }
        new RocksDBSnapshotRestore(data, wal, snapshot, new FileOperations()).begin();
    }

    void begin() throws IOException {
        Properties state = new Properties();
        state.setProperty("snapshot", this.snapshot.toString());
        state.setProperty("wal", this.wal.toString());
        state.setProperty("configured-wal", this.configuredWal.toString());
        state.setProperty("wal-links", Integer.toString(this.walLinks.size()));
        for (int i = 0; i < this.walLinks.size(); i++) {
            state.setProperty("wal-link-" + i, this.walLinks.get(i).toString());
            state.setProperty("wal-link-target-" + i, this.walLinkTargets.get(i).toString());
        }
        // CREATE_NEW prevents replacing the only description of an interrupted restore.
        try (OutputStream output = Files.newOutputStream(this.marker,
                                                        StandardOpenOption.CREATE_NEW,
                                                        StandardOpenOption.WRITE)) {
            state.store(output, "Pending RocksDB checkpoint restore; do not remove");
        } catch (FileAlreadyExistsException e) {
            Properties pending = new Properties();
            try (InputStream input = Files.newInputStream(this.marker)) {
                pending.load(input);
            }
            if (!state.equals(pending)) {
                throw new IOException("Another checkpoint restore is pending: " + this.marker, e);
            }
        }
        try (FileChannel channel = FileChannel.open(this.marker, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    static RocksDBSnapshotRestore prepareOpen(String data, String wal) {
        try {
            Path marker = marker(data);
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Invalid recovery marker " + marker);
            }
            Properties state = new Properties();
            try (InputStream input = Files.newInputStream(marker)) {
                state.load(input);
            }
            String snapshot = state.getProperty("snapshot");
            String savedWal = state.getProperty("wal");
            if (snapshot == null || savedWal == null) {
                throw new IOException("Incomplete recovery marker " + marker);
            }
            Path configuredWal = wal == null || wal.isEmpty() ? new File(data).getCanonicalFile().toPath() :
                                 new File(wal).toPath().toAbsolutePath().normalize();
            if (!configuredWal.toString().equals(state.getProperty("configured-wal"))) {
                throw new IOException("WAL configuration changed: " + marker);
            }
            int links;
            try {
                links = Integer.parseInt(state.getProperty("wal-links"));
            } catch (NumberFormatException e) {
                throw new IOException("Invalid WAL links in recovery marker " + marker, e);
            }
            if (links < 0 || links > configuredWal.getNameCount()) {
                throw new IOException("Invalid WAL link count: " + marker);
            }
            for (int i = 0; i < links; i++) {
                String link = state.getProperty("wal-link-" + i);
                String target = state.getProperty("wal-link-target-" + i);
                if (link == null || target == null || !configuredWal.startsWith(new File(link).toPath())) {
                    throw new IOException("Invalid WAL link in recovery marker " + marker);
                }
                restoreLink(new File(link).toPath(), new File(target).toPath());
            }
            RocksDBSnapshotRestore restore =
                    new RocksDBSnapshotRestore(data, wal, snapshot, new FileOperations());
            if (!restore.wal.toString().equals(savedWal)) {
                throw new IOException("WAL configuration changed during recovery: " + marker);
            }
            restore.install();
            return restore;
        } catch (IOException e) {
            throw new BackendException("Pending snapshot recovery failed for '%s'; " +
                                       "preserve the .resume-pending marker and checkpoint", e, data);
        }
    }

    void install() throws IOException {
        // Copy, never move: partial data copies and WAL failures must be retryable.
        this.files.deleteDirectory(this.data);
        this.files.copyDirectory(this.snapshot, this.data);
        verifyTree(this.snapshot.toPath(), this.data.toPath());
        for (int i = 0; i < this.walLinks.size(); i++) {
            restoreLink(this.walLinks.get(i), this.walLinkTargets.get(i));
        }
        installWal(this.data, this.wal, this.files);
    }

    private static void verifyTree(Path source, Path copy) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path target = copy.resolve(source.relativize(path));
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Missing checkpoint directory: " + target);
                    }
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                           Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    verify(path.toFile(), target.toFile());
                } else {
                    throw new IOException("Invalid checkpoint file: " + path);
                }
            }
        }
    }

    private static void restoreLink(Path link, Path target) throws IOException {
        if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(link.getParent());
            Files.createSymbolicLink(link, target);
        } else if (!Files.isSymbolicLink(link) || !Files.readSymbolicLink(link).equals(target)) {
            throw new IOException("WAL link changed during recovery: " + link);
        }
    }

    void complete() {
        try {
            Files.delete(this.marker);
        } catch (IOException e) {
            throw new BackendException("Failed to finish snapshot recovery at '%s'", e, this.marker);
        }
        // Restore historically consumes its source. Only clean it after native
        // reopen succeeded AND the marker no longer directs retries to it.
        try {
            FileUtils.deleteDirectory(this.snapshot);
        } catch (IOException e) {
            LOG.warn("Snapshot restored but source cleanup failed: {}", this.snapshot, e);
        }
    }

    static void installWal(File data, File wal, FileOperations files) throws IOException {
        data = data.getCanonicalFile();
        wal = wal.getCanonicalFile();
        if (data.equals(wal)) {
            return;
        }
        List<File> source = logs(data);
        FileUtils.forceMkdir(wal);
        Path staging = Files.createTempDirectory(wal.toPath(), ".resume-staging-");
        try {
            for (File log : source) {
                File staged = staging.resolve(log.getName()).toFile();
                files.copyFile(log, staged);
                verify(log, staged);
            }
            // Retire old logs before publishing any checkpoint log. Directory moves
            // would move nested data or replace the user's WAL symlink.
            for (File old : logs(wal)) {
                files.move(old.toPath(), staging.resolve(old.getName() + ".aside"));
            }
            for (File log : source) {
                files.move(staging.resolve(log.getName()), new File(wal, log.getName()).toPath());
            }
            List<File> installed = logs(wal);
            if (installed.size() != source.size()) {
                throw new IOException("Unexpected WAL files during checkpoint restore: " + wal);
            }
            for (File log : source) {
                verify(log, new File(wal, log.getName()));
            }
            for (File log : source) {
                Files.delete(log.toPath());
            }
        } finally {
            // Source checkpoint is untouched, including when cleanup itself fails.
            FileUtils.deleteDirectory(staging.toFile());
        }
    }

    private static void verify(File source, File copy) throws IOException {
        if (!FileUtils.contentEquals(source, copy)) {
            throw new IOException("Checkpoint WAL content mismatch: " + copy);
        }
    }

    private static List<File> logs(File directory) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) {
            throw new IOException("Cannot list WAL directory " + directory);
        }
        List<File> logs = new ArrayList<>();
        for (File child : children) {
            if (child.getName().matches("[0-9]+\\.log")) {
                if (!Files.isRegularFile(child.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("WAL must be a regular file: " + child);
                }
                logs.add(child);
            }
        }
        return logs;
    }

    // Package-private seam for deterministic IO faults, without mocking native DBs.
    static class FileOperations {

        void copyDirectory(File source, File target) throws IOException {
            FileUtils.copyDirectory(source, target);
        }

        void deleteDirectory(File directory) throws IOException {
            FileUtils.deleteDirectory(directory);
        }

        void copyFile(File source, File target) throws IOException {
            FileUtils.copyFile(source, target);
        }

        void move(Path source, Path target) throws IOException {
            Files.move(source, target);
        }
    }
}
