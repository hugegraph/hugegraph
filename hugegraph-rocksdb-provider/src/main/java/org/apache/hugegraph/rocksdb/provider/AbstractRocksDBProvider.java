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
 * Abstract base class for RocksDB providers that provides common utility methods.
 * This class focuses only on the core abstraction of RocksDB open/close operations
 * without complex configuration management.
 */
public abstract class AbstractRocksDBProvider implements RocksDBProvider {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractRocksDBProvider.class);

    @Override
    public final RocksDB openRocksDB(Options options, String dataPath) throws RocksDBException {
        LOG.debug("Opening RocksDB with provider: {} at path: {}", getProviderName(), dataPath);

        // Initialize provider if needed
        initialize();

        try {
            // Delegate to provider-specific implementation
            RocksDB rocksDB = doOpenRocksDB(options, dataPath);

            return rocksDB;
        } catch (Exception e) {
            LOG.error("Failed to open RocksDB with provider: {} at path: {}",
                      getProviderName(), dataPath, e);
            throw e;
        }
    }

    @Override
    public final RocksDB openRocksDB(DBOptions dbOptions, String dataPath,
                                     List<ColumnFamilyDescriptor> cfDescriptors,
                                     List<ColumnFamilyHandle> cfHandles) throws RocksDBException {
        // Initialize provider if needed
        initialize();

        try {
            // Delegate to provider-specific implementation
            RocksDB rocksDB = doOpenRocksDB(dbOptions, dataPath, cfDescriptors, cfHandles);

            return rocksDB;
        } catch (Exception e) {
            LOG.error("Failed to open RocksDB with column families using provider: {} at path: {}",
                      getProviderName(), dataPath, e);
            throw e;
        }
    }

    @Override
    public final RocksDB openRocksDB(DBOptions dbOptions, String dataPath,
                                     List<ColumnFamilyDescriptor> cfDescriptors,
                                     List<ColumnFamilyHandle> cfHandles,
                                     String optionPath, Boolean openHttp) throws RocksDBException {

        LOG.debug("Opening RocksDB with extended parameters using provider: {} at path: {}",
                  getProviderName(), dataPath);
        // Initialize provider if needed
        initialize();

        try {
            // Delegate to provider-specific implementation
            RocksDB rocksDB =
                    doOpenRocksDB(dbOptions, dataPath, cfDescriptors, cfHandles, optionPath,
                                  openHttp);
            return rocksDB;
        } catch (Exception e) {
            throw e;
        }
    }

    @Override
    public void closeRocksDB(RocksDB rocksDB) {
        if (rocksDB != null) {
            // Perform provider-specific close operations
            performProviderSpecificClose(rocksDB);
            rocksDB.close();
        }
    }

    // ========== Abstract methods for provider-specific implementations ==========

    /**
     * Provider-specific implementation for opening RocksDB with Options
     *
     * @param options  RocksDB options
     * @param dataPath database path
     * @return opened RocksDB instance
     * @throws RocksDBException if opening fails
     */
    protected abstract RocksDB doOpenRocksDB(Options options, String dataPath)
            throws RocksDBException;

    /**
     * Provider-specific implementation for opening RocksDB with Options and extended parameters
     *
     * @param options    RocksDB options
     * @param dataPath   database path
     * @param optionPath optional configuration file path (can be null)
     * @param openHttp   whether to start HTTP server (can be null, defaults to false)
     * @return opened RocksDB instance
     * @throws RocksDBException if opening fails
     */
    protected abstract RocksDB doOpenRocksDB(Options options, String dataPath, String optionPath,
                                             Boolean openHttp) throws RocksDBException;

    /**
     * Provider-specific implementation for opening RocksDB with column families
     *
     * @param dbOptions     database options
     * @param dataPath      database path
     * @param cfDescriptors column family descriptors
     * @param cfHandles     list to store column family handles
     * @return opened RocksDB instance
     * @throws RocksDBException if opening fails
     */
    protected abstract RocksDB doOpenRocksDB(DBOptions dbOptions, String dataPath,
                                             List<ColumnFamilyDescriptor> cfDescriptors,
                                             List<ColumnFamilyHandle> cfHandles)
            throws RocksDBException;

    /**
     * Provider-specific implementation for opening RocksDB with extended parameters
     *
     * @param dbOptions     database options
     * @param dataPath      database path
     * @param cfDescriptors column family descriptors
     * @param cfHandles     list to store column family handles
     * @param optionPath    optional configuration file path (can be null)
     * @param openHttp      whether to start HTTP server (can be null, defaults to false)
     * @return opened RocksDB instance
     * @throws RocksDBException if opening fails
     */
    protected abstract RocksDB doOpenRocksDB(DBOptions dbOptions, String dataPath,
                                             List<ColumnFamilyDescriptor> cfDescriptors,
                                             List<ColumnFamilyHandle> cfHandles,
                                             String optionPath, Boolean openHttp)
            throws RocksDBException;

    /**
     * Template method for provider-specific close operations.
     * Subclasses can override this method to perform additional cleanup.
     *
     * @param rocksDB RocksDB instance being closed
     */
    protected void performProviderSpecificClose(RocksDB rocksDB) {
        // Default implementation does nothing
        // Subclasses can override for specific cleanup
    }
}
