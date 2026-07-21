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

import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * ToplingDB provider using the Easy Migrate approach.
 * ToplingDB internally hooks RocksDB.open() calls when the environment variable
 * TOPLINGDB_EASY_MIGRATE_CONF is set, enabling advanced features (CSPP MemTable,
 * ToplingZipTable, DispatcherTable, HTTP monitoring, etc.) without reflection.
 */
public class ToplingRocksDBProvider extends AbstractRocksDBProvider {

    private static final Logger LOG = LoggerFactory.getLogger(ToplingRocksDBProvider.class);

    private static final String PROVIDER_NAME = "topling";
    private static final String SIDE_PLUGIN_REPO_CLASS = "org.rocksdb.SidePluginRepo";
    private static final String EASY_MIGRATE_ENV = "TOPLINGDB_EASY_MIGRATE_CONF";

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName(SIDE_PLUGIN_REPO_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            LOG.debug("ToplingDB SidePluginRepo not found on classpath: {}", e.getMessage());
            return false;
        }
    }

    @Override
    protected RocksDB doOpenRocksDB(Options options, String dataPath) throws RocksDBException {
        return RocksDB.open(options, dataPath);
    }

    @Override
    protected RocksDB doOpenRocksDB(DBOptions dbOptions, String dataPath,
                                    List<ColumnFamilyDescriptor> cfDescriptors,
                                    List<ColumnFamilyHandle> cfHandles) throws RocksDBException {
        return RocksDB.open(dbOptions, dataPath, cfDescriptors, cfHandles);
    }

    @Override
    protected void performProviderSpecificClose(RocksDB rocksDB) {
        // Easy Migrate mode: standard close is sufficient
    }

    @Override
    public void initialize() {
        String conf = System.getenv(EASY_MIGRATE_ENV);
        if (conf == null || conf.isBlank()) {
            LOG.warn("{} not set — ToplingDB advanced features will not activate. "
                     + "Ensure the startup script sets this environment variable.", EASY_MIGRATE_ENV);
        } else {
            LOG.info("ToplingDB Easy Migrate active, conf={}", conf);
        }
    }

    @Override
    public void shutdown() {
        LOG.info("ToplingDB provider shutdown");
    }
}
