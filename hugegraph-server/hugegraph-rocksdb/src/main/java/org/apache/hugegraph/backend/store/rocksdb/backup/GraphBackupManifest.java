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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.hugegraph.util.E;

public final class GraphBackupManifest {

    private final String version;
    private final String repository;
    private final long createdAt;
    private final Map<String, Long> databases;

    public GraphBackupManifest(String version, String repository,
                               long createdAt, Map<String, Long> databases) {
        E.checkArgumentNotNull(version, "Backup version can't be null");
        E.checkArgumentNotNull(repository, "Backup repository can't be null");
        E.checkArgumentNotNull(databases, "Backup databases can't be null");
        this.version = version;
        this.repository = repository;
        this.createdAt = createdAt;
        this.databases = Collections.unmodifiableMap(
                new LinkedHashMap<>(databases));
    }

    public String version() {
        return this.version;
    }

    public String repository() {
        return this.repository;
    }

    public long createdAt() {
        return this.createdAt;
    }

    public Map<String, Long> databases() {
        return this.databases;
    }
}
