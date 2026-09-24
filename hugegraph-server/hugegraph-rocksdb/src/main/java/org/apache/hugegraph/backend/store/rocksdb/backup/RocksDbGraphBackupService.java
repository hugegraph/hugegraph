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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.hugegraph.backup.GraphBackupService;
import org.apache.hugegraph.backup.GraphWriteFence;
import org.apache.hugegraph.backend.BackendException;
import org.apache.hugegraph.backend.store.rocksdb.RocksDBStore;
import org.apache.hugegraph.backend.store.rocksdb.RocksDBStoreProvider;
import org.apache.hugegraph.config.CoreOptions;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.JsonUtil;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

public final class RocksDbGraphBackupService implements GraphBackupService {

    private static final Logger LOG = Log.logger(RocksDbGraphBackupService.class);
    private static final String MANIFESTS = "manifests";
    private static final String STAGES = "stages";
    private static final String REPOSITORY_PATTERN =
            "[A-Za-z0-9][A-Za-z0-9._-]{0,62}";
    private static final String VERSION_PATTERN =
            "v-[0-9]{13}-[0-9a-f]{8}";

    private final RocksDBStoreProvider provider;
    private final HugeConfig config;
    private final String graphName;
    private final String graphScope;

    public RocksDbGraphBackupService(RocksDBStoreProvider provider,
                                     HugeConfig config, String graphName) {
        this.provider = provider;
        this.config = config;
        this.graphName = graphName;
        this.graphScope = Base64.getUrlEncoder().withoutPadding().encodeToString(
                graphName.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Map<String, Object> create(String repository, int keepNum) {
        E.checkArgument(keepNum >= 0, "Keep number must be non-negative");
        GraphWriteFence fence = GraphWriteFence.of(this.graphName);
        fence.enterOperation();
        try {
            Path root = repository(repository);
            String version = "v-" + System.currentTimeMillis() + "-" +
                             UUID.randomUUID().toString().substring(0, 8);
            Map<String, Long> databases = new LinkedHashMap<>();
            fence.enterCapture();
            try {
                for (RocksDBStore store : this.stores()) {
                    databases.putAll(store.createNativeBackups(root));
                }
            } finally {
                fence.leaveCapture();
            }

            GraphBackupManifest manifest = new GraphBackupManifest(
                    version, repository, System.currentTimeMillis(), databases);
            validateManifest(manifest);
            for (Map.Entry<String, Long> entry : databases.entrySet()) {
                new RocksDbBackupExecutor(entry.getKey()).verify(root,
                                                                entry.getValue());
            }
            writeManifest(root, manifest);
            prune(root, keepNum);
            return result(manifest);
        } finally {
            fence.leaveOperation();
        }
    }

    @Override
    public Map<String, Object> restore(String repository, String version) {
        GraphWriteFence fence = GraphWriteFence.of(this.graphName);
        fence.enterOperation();
        try {
            Path root = repository(repository);
            GraphBackupManifest manifest = select(root, version);
            validateManifest(manifest);
            for (Map.Entry<String, Long> entry : manifest.databases().entrySet()) {
                new RocksDbBackupExecutor(entry.getKey()).verify(root,
                                                                entry.getValue());
            }

            Map<RocksDBStore, Map<String, Path>> originalPaths =
                    new LinkedHashMap<>();
            Map<RocksDBStore, Map<Path, Path>> switched = new LinkedHashMap<>();
            Path stage = root.resolve(STAGES).resolve(manifest.version() + "-" +
                                                      UUID.randomUUID());
            boolean providerClosed = false;
            boolean providerReopenStarted = false;
            fence.enterCapture();
            try {
                for (RocksDBStore store : this.stores()) {
                    originalPaths.put(store, store.backupDatabasePaths());
                    store.restoreNativeBackups(root, manifest.databases(), stage);
                }
                this.provider.closeAndForceCloseSessions();
                providerClosed = true;
                for (Map.Entry<RocksDBStore, Map<String, Path>> entry :
                        originalPaths.entrySet()) {
                    Map<Path, Path> paths = entry.getKey().switchNativeBackups(
                            stage, entry.getValue());
                    if (!paths.isEmpty()) {
                        switched.put(entry.getKey(), paths);
                    }
                }
                providerReopenStarted = true;
                this.provider.reopen(this.config);
                providerClosed = false;
                for (RocksDBStore store : switched.keySet()) {
                    store.completeNativeBackupSwitch();
                }
                cleanupOldPaths(switched);
                return result(manifest);
            } catch (RuntimeException e) {
                rollbackRestore(switched, providerClosed, providerReopenStarted, e);
                throw e;
            } finally {
                fence.leaveCapture();
                deleteDirectory(stage);
            }
        } finally {
            fence.leaveOperation();
        }
    }

    @Override
    public List<Map<String, Object>> list(String repository) {
        Path root = repository(repository);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Path path : manifests(root)) {
            GraphBackupManifest manifest = readManifest(path);
            validateManifest(manifest);
            result.add(result(manifest));
        }
        return result;
    }

    @Override
    public Map<String, Object> get(String repository, String version) {
        GraphBackupManifest manifest = select(repository(repository), version);
        validateManifest(manifest);
        return result(manifest);
    }

    private List<RocksDBStore> stores() {
        return this.provider.backupStores();
    }

    private Path repository(String name) {
        E.checkArgument(name != null && name.matches(REPOSITORY_PATTERN),
                        "Invalid repository name '%s'", name);
        Path configured = Path.of(this.config.get(CoreOptions.BACKUP_REPOSITORY_ROOT))
                              .toAbsolutePath().normalize();
        Path graphRoot = configured.resolve(this.graphScope).normalize();
        Path root = graphRoot.resolve(name).normalize();
        E.checkState(root.getParent() != null && root.getParent().equals(graphRoot),
                     "Backup repository escapes configured root");
        try {
            Files.createDirectories(root.resolve(MANIFESTS));
            Files.createDirectories(root.resolve(STAGES));
        } catch (IOException e) {
            throw new BackendException("Failed to create backup repository '%s'",
                                       e, root);
        }
        return root;
    }

    private void writeManifest(Path root, GraphBackupManifest manifest) {
        Path target = root.resolve(MANIFESTS).resolve(manifest.version() + ".json");
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("version", manifest.version());
            content.put("repository", manifest.repository());
            content.put("created_at", manifest.createdAt());
            content.put("databases", manifest.databases());
            Files.writeString(temp, JsonUtil.toJson(content));
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                           StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BackendException("Failed to publish backup manifest '%s'",
                                       e, target);
        }
    }

    @SuppressWarnings("unchecked")
    private GraphBackupManifest readManifest(Path path) {
        try {
            Map<String, Object> content = JsonUtil.fromJson(
                    Files.readString(path), Map.class);
            Map<String, Object> values = (Map<String, Object>) content.get("databases");
            Map<String, Long> databases = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                databases.put(entry.getKey(), ((Number) entry.getValue()).longValue());
            }
            GraphBackupManifest manifest = new GraphBackupManifest(
                    (String) content.get("version"),
                    (String) content.get("repository"),
                    ((Number) content.get("created_at")).longValue(),
                    databases);
            String fileName = path.getFileName().toString();
            E.checkState(fileName.endsWith(".json"),
                         "Backup manifest must use a JSON file: '%s'", path);
            String fileVersion = fileName.substring(0, fileName.length() - 5);
            E.checkState(fileVersion.equals(manifest.version()),
                         "Backup manifest version does not match file '%s'", path);
            Path repository = path.getParent().getParent();
            E.checkState(repository.getFileName().toString().equals(
                                 manifest.repository()),
                         "Backup manifest repository does not match path '%s'",
                         path);
            return manifest;
        } catch (IOException | RuntimeException e) {
            throw new BackendException("Invalid backup manifest '%s'", e, path);
        }
    }

    private GraphBackupManifest select(Path root, String version) {
        List<Path> paths = manifests(root);
        E.checkArgument(!paths.isEmpty(), "No graph backup exists in '%s'", root);
        if (version == null) {
            return readManifest(paths.get(paths.size() - 1));
        }
        E.checkArgument(!version.trim().isEmpty(), "Backup id must not be blank");
        E.checkArgument(version.matches(VERSION_PATTERN),
                        "Invalid backup id '%s'", version);
        return readManifest(root.resolve(MANIFESTS).resolve(version + ".json"));
    }

    private void validateManifest(GraphBackupManifest manifest) {
        Set<String> expected = new TreeSet<>();
        for (RocksDBStore store : this.stores()) {
            expected.addAll(store.backupDatabasePaths().keySet());
        }
        Set<String> actual = new TreeSet<>(manifest.databases().keySet());
        E.checkState(expected.equals(actual),
                     "Backup '%s' does not contain a complete graph: expected " +
                     "databases %s but got %s", manifest.version(), expected, actual);
        for (Long backupId : manifest.databases().values()) {
            E.checkState(backupId != null && backupId >= 0 &&
                         backupId <= Integer.MAX_VALUE,
                         "Invalid native backup id '%s' in graph backup '%s'",
                         backupId, manifest.version());
        }
    }

    private List<Path> manifests(Path root) {
        try {
            if (!Files.isDirectory(root.resolve(MANIFESTS))) {
                return new ArrayList<>();
            }
            try (java.util.stream.Stream<Path> stream = Files.list(
                    root.resolve(MANIFESTS))) {
                return stream.filter(path -> path.getFileName().toString()
                                               .endsWith(".json"))
                             .sorted(Comparator.comparing(path ->
                                     path.getFileName().toString()))
                             .collect(Collectors.toList());
            }
        } catch (IOException e) {
            throw new BackendException("Failed to list graph backups", e);
        }
    }

    private void prune(Path root, int keepNum) {
        List<Path> paths = manifests(root);
        if (keepNum == 0 || paths.size() <= keepNum) {
            return;
        }
        Map<String, Set<Long>> retained = new LinkedHashMap<>();
        for (Path path : paths.subList(paths.size() - keepNum, paths.size())) {
            for (Map.Entry<String, Long> entry :
                    readManifest(path).databases().entrySet()) {
                retained.computeIfAbsent(entry.getKey(), key -> new TreeSet<>())
                        .add(entry.getValue());
            }
        }
        for (RocksDBStore store : stores()) {
            for (String identity : store.backupDatabasePaths().keySet()) {
                Set<Long> databaseRetained = retained.getOrDefault(identity,
                                                                   new TreeSet<>());
                new RocksDbBackupExecutor(identity).deleteUnreferenced(root,
                                                                        databaseRetained);
            }
        }
        for (Path path : paths.subList(0, paths.size() - keepNum)) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                throw new BackendException("Failed to prune backup manifest '%s'",
                                           e, path);
            }
        }
    }

    private static Map<String, Object> result(GraphBackupManifest manifest) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("backup_id", manifest.version());
        result.put("repository", manifest.repository());
        result.put("created_at", manifest.createdAt());
        result.put("databases", manifest.databases());
        return result;
    }

    private static void deleteDirectory(Path path) {
        try {
            FileUtils.deleteDirectory(path.toFile());
        } catch (IOException e) {
            throw new BackendException("Failed to delete restore stage '%s'", e, path);
        }
    }

    private static void cleanupOldPaths(
            Map<RocksDBStore, Map<Path, Path>> switched) {
        for (Map<Path, Path> paths : switched.values()) {
            for (Path old : paths.values()) {
                try {
                    FileUtils.deleteDirectory(old.toFile());
                } catch (IOException e) {
                    LOG.warn("Failed to remove old RocksDB data after restore: {}",
                             old, e);
                }
            }
        }
    }

    private void rollbackRestore(Map<RocksDBStore, Map<Path, Path>> switched,
                                 boolean providerClosed,
                                 boolean providerReopenStarted,
                                 RuntimeException cause) {
        if (providerReopenStarted) {
            try {
                this.provider.close();
            } catch (RuntimeException e) {
                cause.addSuppressed(e);
            }
        }
        List<Map.Entry<RocksDBStore, Map<Path, Path>>> entries =
                new ArrayList<>(switched.entrySet());
        java.util.Collections.reverse(entries);
        try {
            for (Map.Entry<RocksDBStore, Map<Path, Path>> entry : entries) {
                entry.getKey().rollbackNativeBackups(entry.getValue());
            }
        } catch (RuntimeException e) {
            cause.addSuppressed(e);
        }
        if (providerClosed) {
            try {
                this.provider.reopen(this.config);
            } catch (RuntimeException e) {
                cause.addSuppressed(e);
            }
        }
    }
}
