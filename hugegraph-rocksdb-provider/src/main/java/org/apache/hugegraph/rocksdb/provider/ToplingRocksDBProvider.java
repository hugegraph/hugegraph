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
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import net.minidev.json.JSONObject;

/**
 * ToplingRocksDBProvider provides ToplingDB-specific RocksDB functionality.
 * This provider supports advanced ToplingDB features including:
 * - YAML-based configuration via optionPath
 * - HTTP server for monitoring and management
 * - SidePluginRepo integration for enhanced performance
 */
public class ToplingRocksDBProvider extends AbstractRocksDBProvider {

    private static final Logger LOG = LoggerFactory.getLogger(ToplingRocksDBProvider.class);

    private static final String PROVIDER_NAME = "topling";
    private static final int PROVIDER_PRIORITY = 200; // Higher priority than standard
    private static final String SIDE_PLUGIN_REPO_CLASS = "org.rocksdb.SidePluginRepo";

    // Store repo objects for proper cleanup
    private final Map<RocksDB, Object> rocksDBToRepoMap = new ConcurrentHashMap<>();

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
            // Check if SidePluginRepo class is available
            Class.forName(SIDE_PLUGIN_REPO_CLASS);
            LOG.info("ToplingDB SidePluginRepo found, ToplingRocksDBProvider is available");
            return true;
        } catch (ClassNotFoundException e) {
            LOG.debug(
                    "ToplingDB SidePluginRepo not found, ToplingRocksDBProvider is not available:" +
                    " {}",
                    e.getMessage());
            return false;
        }
    }

    @Override
    protected RocksDB doOpenRocksDB(Options options, String dataPath) throws RocksDBException {
        LOG.info("Opening RocksDB with Options at path: {}", dataPath);

        // For simple Options-based opening without optionPath, use standard RocksDB.open
        return RocksDB.open(options, dataPath);
    }

    @Override
    public RocksDB openRocksDB(Options options, String dataPath, String optionPath,
                               Boolean openHttp) throws RocksDBException {
        return doOpenRocksDB(options, dataPath, optionPath, openHttp);
    }

    @Override
    protected RocksDB doOpenRocksDB(Options options, String dataPath, String optionPath,
                                    Boolean openHttp) throws RocksDBException {
        // Check if we should use ToplingDB features
        boolean useTopling = validateConfiguration(optionPath);

        if (useTopling) {
            return openWithToplingFeatures(options, dataPath, optionPath, openHttp);
        } else {
            LOG.warn(
                    "optionPath: {} is not ToplingDB configuration, opening RocksDB without " +
                    "ToplingDB features",
                    optionPath);
            return RocksDB.open(options, dataPath);
        }
    }

    @Override
    protected RocksDB doOpenRocksDB(DBOptions dbOptions, String dataPath,
                                    List<ColumnFamilyDescriptor> cfDescriptors,
                                    List<ColumnFamilyHandle> cfHandles) throws RocksDBException {
        LOG.info("Opening RocksDB with DBOptions and column families at path: {}", dataPath);

        // For column family opening without ToplingDB features, use standard RocksDB.open
        return RocksDB.open(dbOptions, dataPath, cfDescriptors, cfHandles);
    }

    @Override
    protected RocksDB doOpenRocksDB(DBOptions dbOptions, String dataPath,
                                    List<ColumnFamilyDescriptor> cfDescriptors,
                                    List<ColumnFamilyHandle> cfHandles,
                                    String optionPath, Boolean openHttp) throws RocksDBException {
        // Check if we should use ToplingDB features
        boolean useTopling = validateConfiguration(optionPath);

        if (useTopling) {
            // For ToplingDB with column families, we need to use the standard RocksDB.open method
            // but with ToplingDB-specific initialization through SidePluginRepo
            return openWithToplingFeaturesAndCF(dbOptions, dataPath, cfDescriptors, cfHandles,
                                                optionPath, openHttp);
        } else {
            LOG.warn(
                    "optionPath: {} is not ToplingDB configuration, opening RocksDB without " +
                    "ToplingDB features",
                    optionPath);
            return RocksDB.open(dbOptions, dataPath, cfDescriptors, cfHandles);
        }
    }

    /**
     * Opens RocksDB using ToplingDB features with SidePluginRepo
     */
    private RocksDB openWithToplingFeatures(Options options, String dataPath,
                                            String optionPath, Boolean openHttp)
            throws RocksDBException {
        try {
            // Initialize ToplingDB repo with common operations
            Object repo = initializeToplingRepo(options, dataPath, optionPath);

            // Open database with default column families
            Class<?> sidePluginRepoClass = repo.getClass();
            Method openDBMethod = sidePluginRepoClass.getMethod("openDB", String.class);
            Object result = openDBMethod.invoke(repo, converseOptionsToJsonString(dataPath, null));

            // Start HTTP server if needed
            startHttpServerIfNeeded(repo, dataPath, openHttp, optionPath);

            // Validate and store result
            return validateAndStoreResult(result, repo, dataPath, 0);

        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RocksDBException) {
                throw (RocksDBException) cause;
            } else {
                throw new RocksDBException(
                        "Failed to open DB with SidePluginRepo: " + cause.getMessage());
            }
        } catch (Exception e) {
            throw new RocksDBException("Failed to open ToplingDB: " + e.getMessage());
        }
    }

    /**
     * Open RocksDB with ToplingDB features and column families support
     */
    private RocksDB openWithToplingFeaturesAndCF(DBOptions dbOptions, String dataPath,
                                                 List<ColumnFamilyDescriptor> cfDescriptors,
                                                 List<ColumnFamilyHandle> cfHandles,
                                                 String optionPath, Boolean openHttp)
            throws RocksDBException {

        try {
            // Initialize ToplingDB repo with common operations
            Object repo = initializeToplingRepo(dbOptions, dataPath, optionPath);

            // Prepare column family names for JSON
            List<String> cfNames = new java.util.ArrayList<>();
            for (ColumnFamilyDescriptor cfDescriptor : cfDescriptors) {
                cfNames.add(new String(cfDescriptor.getName()));
            }

            // Open database with column families
            Class<?> sidePluginRepoClass = repo.getClass();
            Method openDBMethod = sidePluginRepoClass.getMethod("openDB", String.class, List.class);
            Object result = openDBMethod.invoke(repo,
                                                converseOptionsToJsonString(dataPath, cfNames),
                                                cfHandles);

            // Start HTTP server if needed
            startHttpServerIfNeeded(repo, dataPath, openHttp, optionPath);

            // Validate and store result
            return validateAndStoreResult(result, repo, dataPath, cfDescriptors.size());

        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RocksDBException) {
                throw (RocksDBException) cause;
            } else {
                throw new RocksDBException(
                        "Failed to open DB with SidePluginRepo: " + cause.getMessage());
            }
        } catch (Exception e) {
            LOG.error("Failed to open ToplingDB with column families", e);
            throw new RocksDBException("Failed to open ToplingDB: " + e.getMessage());
        }
    }

    /**
     * Common operations for ToplingDB SidePluginRepo initialization and setup
     */
    private Object initializeToplingRepo(Object options, String dataPath, String optionPath)
            throws RocksDBException {
        try {
            // Dynamically load the SidePluginRepo class by its name at runtime.
            Class<?> sidePluginRepoClass = Class.forName(SIDE_PLUGIN_REPO_CLASS);
            Object repo = sidePluginRepoClass.getConstructor().newInstance();

            String dbName = getDbName(dataPath);

            // Put options into repo - handle both Options and DBOptions
            if (options instanceof Options) {
                Method putMethod =
                        sidePluginRepoClass.getMethod("put", String.class, Options.class);
                putMethod.invoke(repo, dbName, options);
            } else if (options instanceof DBOptions) {
                Method putMethod =
                        sidePluginRepoClass.getMethod("put", String.class, DBOptions.class);
                putMethod.invoke(repo, dbName, options);
            } else {
                throw new RocksDBException(
                        "Unsupported options type: " + options.getClass().getName());
            }

            // Import auto file
            Method importAutoFileMethod =
                    sidePluginRepoClass.getMethod("importAutoFile", String.class);
            importAutoFileMethod.invoke(repo, optionPath);

            return repo;

        } catch (ClassNotFoundException e) {
            LOG.error(
                    "SidePluginRepo not found. This version of rocksdbjni does not support " +
                    "topling.",
                    e);
            throw new IllegalStateException(
                    "Topling features (SidePluginRepo) are required but not found in the " +
                    "rocksdbjni library.",
                    e);
        } catch (Exception e) {
            LOG.error("Failed to initialize ToplingDB SidePluginRepo", e);
            throw new RocksDBException("SidePluginRepo reflection error: " + e.getMessage());
        }
    }

    /**
     * Start HTTP server if conditions are met
     */
    private void startHttpServerIfNeeded(Object repo, String dataPath, Boolean openHttp,
                                         String optionPath)
            throws RocksDBException {
        try {
            if (Boolean.TRUE.equals(openHttp)) {
                Class<?> sidePluginRepoClass = repo.getClass();
                Method openHttpMethod = sidePluginRepoClass.getMethod("startHttpServer");
                openHttpMethod.invoke(repo);
                LOG.info("Topling HTTP Server has been started according to the " +
                         "listening_ports specified in " + optionPath);
            }
        } catch (Exception e) {
            LOG.error("Failed to start HTTP server", e);
            throw new RocksDBException("Failed to start HTTP server: " + e.getMessage());
        }
    }

    /**
     * Validate and store RocksDB result with repo mapping
     */
    private RocksDB validateAndStoreResult(Object result, Object repo, String dataPath, int cfCount)
            throws RocksDBException {
        if (result instanceof RocksDB) {
            RocksDB rocksDB = (RocksDB) result;
            // Store the repo reference for later cleanup
            rocksDBToRepoMap.put(rocksDB, repo);
            if (cfCount > 0) {
                LOG.info("Successfully opened ToplingDB with {} column families at path: {}",
                         cfCount, dataPath);
            } else {
                LOG.info("Successfully opened ToplingDB with default column families at path: {}",
                         dataPath);
            }
            return rocksDB;
        } else {
            throw new RocksDBException("ToplingDB openDB returned unexpected result type: " +
                                       (result != null ? result.getClass().getName() : "null"));
        }
    }

    /**
     * Utility function to convert options to JSON string for ToplingDB
     * Moved from RocksDBStdSessions
     */
    private static String converseOptionsToJsonString(String dataPath, List<String> cfs) {
        if (dataPath == null || dataPath.trim().isEmpty()) {
            throw new IllegalArgumentException("dataPath cannot be null or empty");
        }
        // sanitize path to avoid trailing slash causing empty namepart in native side
        String sanitizedPath = sanitizePath(dataPath);
        // construct CFOptions
        JSONObject columnFamilies = new JSONObject();
        // multi CFs
        if (cfs != null) {
            for (String cf : cfs) {
                columnFamilies.put(cf, "$default");
            }
        } else { // single default CF
            columnFamilies.put("default", "$default");
        }

        // construct params
        JSONObject params = new JSONObject();
        params.put("db_options", "$dbo");
        params.put("cf_options", "$default");
        params.put("column_families", columnFamilies);
        params.put("path", sanitizedPath);

        // construct wrapper
        JSONObject wrapper = new JSONObject();
        wrapper.put("method", "DB::Open");
        wrapper.put("params", params);

        return wrapper.toString();
    }

    /**
     * Utility function to get database name from path
     * Moved from RocksDBStdSessions
     */
    private static String getDbName(String dataPath) {
        String sanitizedPath = sanitizePath(dataPath);
        return Paths.get(sanitizedPath).getFileName().toString();
    }

    /**
     * Ensure path has no trailing separators and is normalized
     */
    private static String sanitizePath(String dataPath) {
        String p = dataPath.trim();
        // remove trailing separators
        while (p.endsWith("/") || p.endsWith(java.io.File.separator)) {
            p = p.substring(0, p.length() - 1);
        }
        // normalize using Paths to collapse redundant parts
        try {
            return Paths.get(p).normalize().toString();
        } catch (Exception e) {
            // fallback to original trimmed path
            return p;
        }
    }

    private static boolean validateConfiguration(String optionPath) {
        boolean result = false;
        if (!StringUtils.isEmpty(optionPath)) {
            try {
                // Validate option path first
                validateOptionPath(optionPath);

                // Read and validate YAML content
                String yamlContent = Files.readString(Paths.get(optionPath));
                validateYamlContent(yamlContent);

                Class.forName(SIDE_PLUGIN_REPO_CLASS);
                result = true;
                LOG.info(
                        "SidePluginRepo found. Will attempt to open default CF RocksDB using " +
                        "Topling.");
            } catch (ClassNotFoundException e) {
                LOG.warn("SidePluginRepo not found, even though 'optionPath' was provided. " +
                         "Falling back to the standard RocksDB default CF opening method. " +
                         "The configuration in '{}' will be ignored.", optionPath);
            } catch (Exception e) {
                LOG.warn(
                        "Failed to validate optionPath '{}': {}. Falling back to standard RocksDB.",
                        optionPath, e.getMessage());
            }
        }
        return result;
    }

    /**
     * Validate option path using RocksDBOptions utility method.
     * This method will be available when hugegraph-rocksdb is in classpath.
     */
    private static void validateOptionPath(String optionPath) {
        try {
            // Use reflection to call RocksDBOptions.validateOptionPath
            Class<?> rocksDBOptionsClass =
                    Class.forName("org.apache.hugegraph.backend.store.rocksdb.RocksDBOptions");
            Method validateMethod =
                    rocksDBOptionsClass.getMethod("validateOptionPath", String.class);
            validateMethod.invoke(null, optionPath);
        } catch (Exception e) {
            // Fallback to basic validation if RocksDBOptions is not available
            LOG.warn("RocksDBOptions.validateOptionPath not available, using basic validation: {}",
                     e.getMessage());
            if (optionPath == null || optionPath.isBlank()) {
                throw new IllegalArgumentException("option_path can't be null or empty");
            }
            if (!optionPath.toLowerCase().endsWith(".yaml") &&
                !optionPath.toLowerCase().endsWith(".yml")) {
                throw new IllegalArgumentException("option_path must end with .yaml or .yml");
            }
        }
    }

    /**
     * Validate YAML content using RocksDBOptions utility method.
     * This method will be available when hugegraph-rocksdb is in classpath.
     */
    private static void validateYamlContent(String yamlContent) {
        try {
            // Use reflection to call RocksDBOptions.validateYamlContent
            Class<?> rocksDBOptionsClass =
                    Class.forName("org.apache.hugegraph.backend.store.rocksdb.RocksDBOptions");
            Method validateMethod =
                    rocksDBOptionsClass.getMethod("validateYamlContent", String.class);
            validateMethod.invoke(null, yamlContent);
        } catch (Exception e) {
            // Fallback to basic validation if RocksDBOptions is not available
            LOG.warn(
                    "RocksDBOptions.validateYamlContent not available, skipping YAML validation: " +
                    "{}",
                    e.getMessage());
        }
    }

    @Override
    public void initialize() {
        LOG.info("Initializing ToplingRocksDBProvider");
        // ToplingDB-specific initialization if needed
    }

    @Override
    protected void performProviderSpecificClose(RocksDB rocksDB) {
        LOG.info("Performing ToplingDB-specific cleanup for RocksDB instance");

        // Get the repo object associated with this RocksDB instance
        Object repo = rocksDBToRepoMap.remove(rocksDB);

        if (repo != null) {
            LOG.info("SidePluginRepo instance found, attempting to call closeAllDB().");
            try {
                // Get the class of the repo object at runtime.
                Class<?> sidePluginRepoClass = repo.getClass();
                Method closeAllDBMethod = sidePluginRepoClass.getMethod("closeAllDB");

                // Invoke the method on the repo instance.
                closeAllDBMethod.invoke(repo);
                LOG.info("Successfully called closeAllDB() on SidePluginRepo.");
            } catch (Exception e) {
                // Catch potential reflection exceptions (e.g., NoSuchMethodException)
                // and log them. This is safer than letting them crash the application.
                LOG.error("Failed to reflectively call closeAllDB() on SidePluginRepo.", e);
            }
        } else {
            LOG.debug("No SidePluginRepo found for this RocksDB instance, using standard close.");
        }
    }

    @Override
    public void shutdown() {
        LOG.info("Shutting down ToplingRocksDBProvider");
        // ToplingDB-specific shutdown if needed
    }
}
