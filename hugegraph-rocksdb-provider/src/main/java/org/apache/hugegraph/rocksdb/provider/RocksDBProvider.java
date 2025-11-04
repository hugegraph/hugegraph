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

import java.util.List;

/**
 * Simplified RocksDB Provider SPI interface for pluggable RocksDB implementations.
 * This interface only abstracts the core RocksDB.open() and rocksdb.close() operations,
 * making it a direct replacement for standard RocksDB API calls.
 * <p>
 * The design philosophy is to keep it minimal - only replace the open/close operations
 * while maintaining full compatibility with standard RocksDB API signatures.
 * <p>
 * Implementations should be registered via Java SPI mechanism in
 * META-INF/services/org.apache.hugegraph.rocksdb.provider.RocksDBProvider
 */
public interface RocksDBProvider {

    /**
     * Get the provider name/type identifier
     *
     * @return provider name (e.g., "standard", "topling")
     */
    String getProviderName();

    /**
     * Get the priority of this provider (higher priority providers are preferred)
     *
     * @return priority value
     */
    int getPriority();

    /**
     * Check if this provider is available in the current environment
     *
     * @return true if the provider can be used
     */
    boolean isAvailable();

    // ========== Core RocksDB Open Operations ==========

    /**
     * Open RocksDB - direct replacement for RocksDB.open(options, dataPath)
     *
     * @param options  RocksDB options
     * @param dataPath database path
     * @return opened RocksDB instance
     * @throws RocksDBException if opening fails
     */
    RocksDB openRocksDB(Options options, String dataPath) throws RocksDBException;

    /**
     * Open RocksDB with additional parameters for special providers (like ToplingDB)
     *
     * @param options    RocksDB options
     * @param dataPath   database path
     * @param optionPath optional configuration file path (can be null)
     * @param openHttp   whether to start HTTP server (can be null, defaults to false)
     * @return opened RocksDB instance
     * @throws RocksDBException if opening fails
     */
    RocksDB openRocksDB(Options options, String dataPath, String optionPath, Boolean openHttp)
            throws RocksDBException;

    /**
     * Open RocksDB with column families - direct replacement for
     * RocksDB.open(dbOptions, dataPath, cfDescriptors, cfHandles)
     *
     * @param dbOptions     database options
     * @param dataPath      database path
     * @param cfDescriptors column family descriptors
     * @param cfHandles     list to store column family handles
     * @return opened RocksDB instance
     * @throws RocksDBException if opening fails
     */
    RocksDB openRocksDB(DBOptions dbOptions, String dataPath,
                        List<ColumnFamilyDescriptor> cfDescriptors,
                        List<ColumnFamilyHandle> cfHandles) throws RocksDBException;

    /**
     * Open RocksDB with additional parameters for special providers (like ToplingDB)
     * This method supports optionPath and openHttp parameters while maintaining
     * the same signature as the standard openRocksDB method.
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
    RocksDB openRocksDB(DBOptions dbOptions, String dataPath,
                        List<ColumnFamilyDescriptor> cfDescriptors,
                        List<ColumnFamilyHandle> cfHandles,
                        String optionPath, Boolean openHttp) throws RocksDBException;

    // ========== Core RocksDB Close Operations ==========

    /**
     * Close RocksDB - direct replacement for rocksdb.close()
     * This method handles both the RocksDB instance and any associated column family handles.
     *
     * @param rocksDB RocksDB instance to close
     */
    void closeRocksDB(RocksDB rocksDB);

    /**
     * Close RocksDB with column family handles
     *
     * @param rocksDB   RocksDB instance to close
     * @param cfHandles column family handles to close (can be null)
     */
    default void closeRocksDB(RocksDB rocksDB, List<ColumnFamilyHandle> cfHandles) {
        // Close column family handles first
        if (cfHandles != null) {
            for (ColumnFamilyHandle cfHandle : cfHandles) {
                if (cfHandle != null) {
                    cfHandle.close();
                }
            }
        }

        // Close the RocksDB instance
        closeRocksDB(rocksDB);
    }

    // ========== Provider Lifecycle ==========

    /**
     * Perform provider-specific initialization
     * This method is called once when the provider is first used.
     */
    default void initialize() {
        // Default implementation does nothing
    }

    /**
     * Perform provider-specific cleanup
     * This method is called when the provider is no longer needed.
     */
    default void shutdown() {
        // Default implementation does nothing
    }
}
