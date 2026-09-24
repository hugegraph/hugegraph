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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.hugegraph.backend.BackendException;
import org.rocksdb.BackupEngine;
import org.rocksdb.BackupEngineOptions;
import org.rocksdb.BackupInfo;
import org.rocksdb.Env;
import org.rocksdb.RestoreOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

/** Owns native RocksDB backup lifecycle for one stable database identity. */
public class RocksDbBackupExecutor {

    private final String databaseId;

    public RocksDbBackupExecutor(String databaseId) {
        if (databaseId == null || databaseId.isEmpty() ||
            databaseId.contains("/") || databaseId.contains("\\")) {
            throw new IllegalArgumentException("Invalid database identity: " + databaseId);
        }
        this.databaseId = databaseId;
    }

    public long capture(RocksDB database, Path repositoryRoot) {
        try (BackupEngine engine = this.open(repositoryRoot)) {
            engine.createNewBackup(database, true);
            List<BackupInfo> infos = engine.getBackupInfo();
            if (infos.isEmpty()) {
                throw new BackendException("RocksDB BackupEngine returned no backup info");
            }
            return infos.get(infos.size() - 1).backupId();
        } catch (RocksDBException e) {
            throw new BackendException("Failed to capture RocksDB backup", e);
        }
    }

    public void verify(Path repositoryRoot, long backupId) {
        try (BackupEngine engine = this.open(repositoryRoot)) {
            for (int corrupted : engine.getCorruptedBackups()) {
                if (corrupted == backupId) {
                    throw new BackendException("RocksDB backup '%s' is corrupted", backupId);
                }
            }
            if (!this.backupIds(engine).contains(backupId)) {
                throw new BackendException("RocksDB backup '%s' doesn't exist", backupId);
            }
        } catch (RocksDBException e) {
            throw new BackendException("Failed to verify RocksDB backup", e);
        }
    }

    public void restore(Path repositoryRoot, long backupId, Path stagePath) {
        if (backupId < 0 || backupId > Integer.MAX_VALUE) {
            throw new BackendException("Invalid RocksDB backup id '%s'", backupId);
        }
        try {
            Files.createDirectories(stagePath);
        } catch (IOException e) {
            throw new BackendException("Failed to create restore stage '%s'", e, stagePath);
        }
        try (BackupEngine engine = this.open(repositoryRoot);
             RestoreOptions options = new RestoreOptions(false)) {
            String path = stagePath.toString();
            engine.restoreDbFromBackup((int) backupId, path, path, options);
        } catch (RocksDBException e) {
            throw new BackendException("Failed to restore RocksDB backup '%s'", e, backupId);
        }
    }

    public void deleteUnreferenced(Path repositoryRoot, Set<Long> retainedIds) {
        try (BackupEngine engine = this.open(repositoryRoot)) {
            for (long backupId : this.backupIds(engine)) {
                if (!retainedIds.contains(backupId)) {
                    if (backupId > Integer.MAX_VALUE) {
                        throw new BackendException("Invalid RocksDB backup id '%s'",
                                                   backupId);
                    }
                    engine.deleteBackup((int) backupId);
                }
            }
            engine.garbageCollect();
        } catch (RocksDBException e) {
            throw new BackendException("Failed to clean RocksDB backups", e);
        }
    }

    private BackupEngine open(Path repositoryRoot) throws RocksDBException {
        Path path = repositoryRoot.resolve("databases").resolve(this.databaseId);
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new BackendException("Failed to create backup repository '%s'", e, path);
        }
        try (BackupEngineOptions options = new BackupEngineOptions(path.toString())) {
            return BackupEngine.open(Env.getDefault(), options);
        }
    }

    private Set<Long> backupIds(BackupEngine engine) {
        Set<Long> ids = new HashSet<>();
        for (BackupInfo info : engine.getBackupInfo()) {
            ids.add((long) info.backupId());
        }
        return ids;
    }
}
