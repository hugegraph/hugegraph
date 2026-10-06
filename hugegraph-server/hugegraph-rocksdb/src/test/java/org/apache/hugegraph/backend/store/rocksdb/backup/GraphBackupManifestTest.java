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

import org.junit.Assert;
import org.junit.Test;

public class GraphBackupManifestTest {

    @Test
    public void testManifestRoundTripKeepsAllDatabaseBackupIds() {
        Map<String, Long> databases = new LinkedHashMap<>();
        databases.put("schema", 11L);
        databases.put("graph", 12L);

        GraphBackupManifest manifest = new GraphBackupManifest(
                "v1", "daily", 3L, databases);

        Assert.assertEquals("v1", manifest.version());
        Assert.assertEquals("daily", manifest.repository());
        Assert.assertEquals(3L, manifest.createdAt());
        Assert.assertEquals(databases, manifest.databases());
        Assert.assertEquals(Collections.unmodifiableMap(databases),
                            manifest.databases());
    }
}
