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

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RocksDB Provider SPI Loader that manages the loading and selection
 * of RocksDB providers using Java's ServiceLoader mechanism.
 */
public class RocksDBProviderLoader {

    private static final Logger LOG = LoggerFactory.getLogger(RocksDBProviderLoader.class);

    private static final RocksDBProviderLoader INSTANCE = new RocksDBProviderLoader();

    private final Map<String, RocksDBProvider> providerCache = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    private RocksDBProviderLoader() {
        // Private constructor for singleton
    }

    public static RocksDBProviderLoader getInstance() {
        return INSTANCE;
    }

    /**
     * Load all available RocksDB providers using SPI
     */
    public synchronized void loadProviders() {
        if (loaded) {
            return;
        }

        LOG.info("Loading RocksDB providers via SPI...");

        ServiceLoader<RocksDBProvider> serviceLoader = ServiceLoader.load(RocksDBProvider.class);

        for (RocksDBProvider provider : serviceLoader) {
            try {
                if (provider.isAvailable()) {
                    providerCache.put(provider.getProviderName(), provider);
                    LOG.info("Loaded RocksDB provider: {} (priority: {})",
                             provider.getProviderName(), provider.getPriority());
                } else {
                    LOG.warn("RocksDB provider {} is not available in current environment",
                             provider.getProviderName());
                }
            } catch (Exception e) {
                LOG.error("Failed to load RocksDB provider: {}", provider.getClass().getName(), e);
            }
        }

        if (providerCache.isEmpty()) {
            LOG.warn(
                    "No RocksDB providers found! Make sure providers are properly registered in " +
                    "META-INF/services");
        } else {
            LOG.info("Successfully loaded {} RocksDB provider(s): {}",
                     providerCache.size(), providerCache.keySet());
        }

        loaded = true;
    }

    /**
     * Get a specific provider by name
     *
     * @param providerName provider name
     * @return RocksDB provider or null if not found
     */
    public RocksDBProvider getProvider(String providerName) {
        if (!loaded) {
            loadProviders();
        }

        return providerCache.get(providerName);
    }

    /**
     * Get the best available provider based on priority
     *
     * @return best available RocksDB provider
     */
    public RocksDBProvider getBestProvider() {
        if (!loaded) {
            loadProviders();
        }

        if (providerCache.isEmpty()) {
            throw new RuntimeException("No RocksDB providers available");
        }

        // Find provider with highest priority
        RocksDBProvider bestProvider = null;
        int highestPriority = Integer.MIN_VALUE;

        for (RocksDBProvider provider : providerCache.values()) {
            if (provider.isAvailable() && provider.getPriority() > highestPriority) {
                bestProvider = provider;
                highestPriority = provider.getPriority();
            }
        }

        if (bestProvider == null) {
            throw new RuntimeException("No available RocksDB providers found");
        }

        LOG.info("Auto-selected RocksDB provider: {} (priority: {})",
                 bestProvider.getProviderName(), bestProvider.getPriority());
        return bestProvider;
    }

    /**
     * Get all loaded providers
     *
     * @return collection of all providers
     */
    public Collection<RocksDBProvider> getAllProviders() {
        if (!loaded) {
            loadProviders();
        }

        return Collections.unmodifiableCollection(providerCache.values());
    }

    /**
     * Get names of all available providers
     *
     * @return set of provider names
     */
    public Set<String> getAvailableProviderNames() {
        if (!loaded) {
            loadProviders();
        }

        return Collections.unmodifiableSet(providerCache.keySet());
    }

    /**
     * Check if a specific provider is available
     *
     * @param providerName provider name
     * @return true if provider is available
     */
    public boolean isProviderAvailable(String providerName) {
        if (!loaded) {
            loadProviders();
        }

        RocksDBProvider provider = providerCache.get(providerName);
        return provider != null && provider.isAvailable();
    }

    /**
     * Reload all providers
     */
    public synchronized void reload() {
        loaded = false;
        providerCache.clear();
        loadProviders();
    }

    // Static convenience methods

    /**
     * Open RocksDB with simple options
     *
     * @param options  RocksDB options
     * @param dataPath database path
     * @return opened RocksDB instance
     * @throws RocksDBException if opening fails
     */
    public static RocksDB openRocksDB(Options options, String dataPath) throws RocksDBException {
        return openRocksDB(options, dataPath, null, null);
    }

    /**
     * Open RocksDB with options and optional parameters
     *
     * @param options    RocksDB options
     * @param dataPath   database path
     * @param optionPath optional path to options file
     * @param openHttp   optional HTTP server flag
     * @return opened RocksDB instance
     * @throws RocksDBException if opening fails
     */
    public static RocksDB openRocksDB(Options options, String dataPath, String optionPath,
                                      Boolean openHttp) throws RocksDBException {
        RocksDBProvider provider = getInstance().getBestProvider();
        return provider.openRocksDB(options, dataPath, optionPath, openHttp);
    }

    /**
     * Open RocksDB with column families
     *
     * @param dbOptions     database options
     * @param dataPath      database path
     * @param cfDescriptors column family descriptors
     * @param cfHandles     column family handles (output)
     * @return opened RocksDB instance
     * @throws RocksDBException if opening fails
     */
    public static RocksDB openRocksDB(DBOptions dbOptions, String dataPath,
                                      List<ColumnFamilyDescriptor> cfDescriptors,
                                      List<ColumnFamilyHandle> cfHandles) throws RocksDBException {
        return openRocksDB(dbOptions, dataPath, cfDescriptors, cfHandles, null, null);
    }

    /**
     * Open RocksDB with column families and optional parameters
     *
     * @param dbOptions     database options
     * @param dataPath      database path
     * @param cfDescriptors column family descriptors
     * @param cfHandles     column family handles (output)
     * @param optionPath    optional path to options file
     * @param openHttp      optional HTTP server flag
     * @return opened RocksDB instance
     * @throws RocksDBException if opening fails
     */
    public static RocksDB openRocksDB(DBOptions dbOptions, String dataPath,
                                      List<ColumnFamilyDescriptor> cfDescriptors,
                                      List<ColumnFamilyHandle> cfHandles,
                                      String optionPath, Boolean openHttp) throws RocksDBException {
        RocksDBProvider provider = getInstance().getBestProvider();
        return provider.openRocksDB(dbOptions, dataPath, cfDescriptors, cfHandles, optionPath,
                                    openHttp);
    }

    /**
     * Close RocksDB instance
     *
     * @param rocksDB RocksDB instance to close
     */
    public static void closeRocksDB(RocksDB rocksDB) {
        RocksDBProvider provider = getInstance().getBestProvider();
        provider.closeRocksDB(rocksDB);
    }

    /**
     * Get provider by name (static method)
     *
     * @param providerName provider name
     * @return RocksDB provider or null if not found
     */
    public static RocksDBProvider getProviderByName(String providerName) {
        return getInstance().getProvider(providerName);
    }
}
