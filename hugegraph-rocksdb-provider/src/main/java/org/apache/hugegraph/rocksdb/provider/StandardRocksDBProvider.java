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
 * Standard RocksDB provider implementation that uses the standard RocksDB library.
 * This provider handles the traditional RocksDB opening and configuration logic.
 */
public class StandardRocksDBProvider extends AbstractRocksDBProvider {

    private static final Logger LOG = LoggerFactory.getLogger(StandardRocksDBProvider.class);

    private static final String PROVIDER_NAME = "standard";
    private static final int PROVIDER_PRIORITY = 100; // Lower priority than ToplingDB

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public int getPriority() {
        return PROVIDER_PRIORITY;
    }

    @Override
    public boolean isAvailable() {
        try {
            // Check if standard RocksDB is available
            RocksDB.loadLibrary();
            return true;
        } catch (Exception e) {
            LOG.warn("Standard RocksDB is not available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    protected RocksDB doOpenRocksDB(Options options, String dataPath) throws RocksDBException {
        try {
            return RocksDB.open(options, dataPath);
        } catch (RocksDBException e) {
            throw e;
        }
    }

    @Override
    public RocksDB openRocksDB(Options options, String dataPath, String optionPath,
                               Boolean openHttp) throws RocksDBException {
        return doOpenRocksDB(options, dataPath, optionPath, openHttp);
    }

    @Override
    protected RocksDB doOpenRocksDB(Options options, String dataPath, String optionPath,
                                    Boolean openHttp) throws RocksDBException {
        LOG.debug("Opening standard RocksDB with Options and extended parameters at path: {}",
                  dataPath);

        // Log warnings for unsupported parameters
        if (optionPath != null) {
            LOG.warn("Standard RocksDB does not support optionPath parameter, ignoring: {}",
                     optionPath);
        }
        if (openHttp != null && openHttp) {
            LOG.warn("Standard RocksDB does not support HTTP server, ignoring openHttp parameter");
        }

        // Use standard opening
        return RocksDB.open(options, dataPath);
    }

    @Override
    protected RocksDB doOpenRocksDB(DBOptions dbOptions, String dataPath,
                                    List<ColumnFamilyDescriptor> cfDescriptors,
                                    List<ColumnFamilyHandle> cfHandles) throws RocksDBException {
        try {
            return RocksDB.open(dbOptions, dataPath, cfDescriptors, cfHandles);
        } catch (RocksDBException e) {
            throw e;
        }
    }

    @Override
    protected RocksDB doOpenRocksDB(DBOptions dbOptions, String dataPath,
                                    List<ColumnFamilyDescriptor> cfDescriptors,
                                    List<ColumnFamilyHandle> cfHandles,
                                    String optionPath, Boolean openHttp) throws RocksDBException {
        // Standard RocksDB doesn't support optionPath and openHttp parameters
        // Log a warning if these parameters are provided
        if (optionPath != null && !optionPath.isEmpty()) {
            LOG.warn("Standard RocksDB provider does not support optionPath parameter: {}",
                     optionPath);
        }
        if (openHttp != null && openHttp) {
            LOG.warn("Standard RocksDB provider does not support openHttp parameter");
        }

        // Fallback to standard column family opening
        return doOpenRocksDB(dbOptions, dataPath, cfDescriptors, cfHandles);
    }

    @Override
    public void initialize() {
        try {
            // Load RocksDB native library
            RocksDB.loadLibrary();
            LOG.debug("Standard RocksDB library loaded successfully");
        } catch (Exception e) {
            LOG.error("Failed to load standard RocksDB library", e);
            throw new RuntimeException("Failed to initialize standard RocksDB provider", e);
        }
    }

    @Override
    protected void performProviderSpecificClose(RocksDB rocksDB) {
        // Standard RocksDB doesn't require special close operations
        // The base class will handle the standard rocksDB.close() call
        LOG.debug("Standard RocksDB close completed");
    }

    @Override
    public void shutdown() {
        LOG.info("Standard RocksDB provider shutdown completed");
    }
}
