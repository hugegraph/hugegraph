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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RocksDB Provider Loader that manages provider discovery and selection.
 * Providers are discovered via Java SPI (ServiceLoader) and selected
 * explicitly by configuration name (rocksdb.provider).
 */
public class RocksDBProviderLoader {

    private static final Logger LOG = LoggerFactory.getLogger(RocksDBProviderLoader.class);

    private static final RocksDBProviderLoader INSTANCE = new RocksDBProviderLoader();

    private final Map<String, RocksDBProvider> providerRegistry = new ConcurrentHashMap<>();
    private volatile RocksDBProvider activeProvider;
    private volatile boolean loaded = false;

    private RocksDBProviderLoader() {
    }

    public static RocksDBProviderLoader getInstance() {
        return INSTANCE;
    }

    /**
     * Load all available RocksDB providers via SPI into the registry.
     */
    public synchronized void loadProviders() {
        if (loaded) {
            return;
        }

        LOG.info("Loading RocksDB providers via SPI...");

        ServiceLoader<RocksDBProvider> serviceLoader = ServiceLoader.load(RocksDBProvider.class);

        for (RocksDBProvider provider : serviceLoader) {
            providerRegistry.put(provider.getProviderName(), provider);
            LOG.info("Discovered RocksDB provider: {} (available: {})",
                     provider.getProviderName(), provider.isAvailable());
        }

        if (providerRegistry.isEmpty()) {
            LOG.warn("No RocksDB providers found! Ensure providers are registered in "
                     + "META-INF/services");
        }

        loaded = true;
    }

    /**
     * Select and activate a provider by name. Throws if not found or not available.
     *
     * @param providerName the provider name matching rocksdb.provider config value
     * @return the activated provider
     */
    public synchronized RocksDBProvider selectProvider(String providerName) {
        if (!loaded) {
            loadProviders();
        }

        RocksDBProvider provider = providerRegistry.get(providerName);
        if (provider == null) {
            throw new IllegalStateException(String.format(
                    "RocksDB provider '%s' not found. Available: %s. "
                    + "If using ToplingDB, ensure the addon is installed in lib/.",
                    providerName, providerRegistry.keySet()));
        }

        if (!provider.isAvailable()) {
            throw new IllegalStateException(String.format(
                    "RocksDB provider '%s' found but not available in current environment. "
                    + "Check native libraries and LD_PRELOAD configuration.",
                    providerName));
        }

        this.activeProvider = provider;
        provider.initialize();
        LOG.info("Activated RocksDB provider: {}", providerName);
        return provider;
    }

    /**
     * Idempotent version of selectProvider. If a provider is already active, skip.
     * This avoids redundant initialization when multiple RocksDB instances are opened.
     *
     * @param providerName the provider name
     */
    public synchronized void selectProviderIfNeeded(String providerName) {
        if (activeProvider != null) {
            return;
        }
        selectProvider(providerName);
    }

    /**
     * Get the currently active provider. Throws if no provider has been selected.
     *
     * @return the active provider
     */
    public RocksDBProvider getActiveProvider() {
        RocksDBProvider provider = activeProvider;
        if (provider == null) {
            throw new IllegalStateException(
                    "No RocksDB provider has been activated. "
                    + "Ensure rocksdb.provider is configured and selectProvider() is called.");
        }
        return provider;
    }

    /**
     * Get names of all discovered providers.
     */
    public Set<String> getAvailableProviderNames() {
        if (!loaded) {
            loadProviders();
        }
        return Collections.unmodifiableSet(providerRegistry.keySet());
    }

    /**
     * Check if a specific provider is discovered (regardless of availability).
     */
    public boolean isProviderAvailable(String providerName) {
        if (!loaded) {
            loadProviders();
        }
        RocksDBProvider provider = providerRegistry.get(providerName);
        return provider != null && provider.isAvailable();
    }

    /**
     * Reset loader state. After reload, selectProvider must be called again.
     */
    public synchronized void reload() {
        loaded = false;
        activeProvider = null;
        providerRegistry.clear();
        loadProviders();
    }

    // ========== Static convenience methods ==========

    public static RocksDB openRocksDB(Options options, String dataPath) throws RocksDBException {
        return openRocksDB(options, dataPath, null, null);
    }

    public static RocksDB openRocksDB(Options options, String dataPath, String optionPath,
                                      Boolean openHttp) throws RocksDBException {
        return getInstance().getActiveProvider()
                .openRocksDB(options, dataPath, optionPath, openHttp);
    }

    public static RocksDB openRocksDB(DBOptions dbOptions, String dataPath,
                                      List<ColumnFamilyDescriptor> cfDescriptors,
                                      List<ColumnFamilyHandle> cfHandles) throws RocksDBException {
        return openRocksDB(dbOptions, dataPath, cfDescriptors, cfHandles, null, null);
    }

    public static RocksDB openRocksDB(DBOptions dbOptions, String dataPath,
                                      List<ColumnFamilyDescriptor> cfDescriptors,
                                      List<ColumnFamilyHandle> cfHandles,
                                      String optionPath, Boolean openHttp) throws RocksDBException {
        return getInstance().getActiveProvider()
                .openRocksDB(dbOptions, dataPath, cfDescriptors, cfHandles, optionPath, openHttp);
    }

    public static void closeRocksDB(RocksDB rocksDB) {
        getInstance().getActiveProvider().closeRocksDB(rocksDB);
    }

    public static RocksDBProvider getProviderByName(String providerName) {
        return getInstance().getProvider(providerName);
    }

    /**
     * Get a provider by name without requiring it to be active.
     */
    public RocksDBProvider getProvider(String providerName) {
        if (!loaded) {
            loadProviders();
        }
        return providerRegistry.get(providerName);
    }
}
