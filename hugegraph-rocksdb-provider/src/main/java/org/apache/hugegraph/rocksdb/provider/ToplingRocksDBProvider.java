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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import net.minidev.json.JSONObject;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;

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

    // Validation constants migrated from RocksDBOptions
    private static final Pattern SAFE_PATH_PATTERN =
            Pattern.compile("^[a-zA-Z0-9/_.-]+\\.yaml$");
    private static final String ALLOWED_CONFIG_DIR = "./conf/";
    private static final long MAX_CONFIG_FILE_SIZE = 1024 * 1024 * 10; // 10 MB

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
        initialize();
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
        Object repo = null;
        RocksDB opened = null;
        boolean registered = false;
        try {
            // Initialize ToplingDB repo with common operations
            repo = initializeToplingRepo(options, dataPath, optionPath);

            // Open database with default column families
            Class<?> sidePluginRepoClass = repo.getClass();
            Method openDBMethod = sidePluginRepoClass.getMethod("openDB", String.class);
            Object result = openDBMethod.invoke(repo, converseOptionsToJsonString(dataPath, null));

            // Validate and store result before starting HTTP server
            opened = validateAndStoreResult(result, repo, dataPath, 0);
            registered = true;

            // Start HTTP server if needed
            startHttpServerIfNeeded(repo, dataPath, openHttp, optionPath);

            return opened;

        } catch (InvocationTargetException e) {
            cleanupFailedOpen(opened, repo, registered, null);
            Throwable cause = e.getCause();
            if (cause instanceof RocksDBException) {
                throw (RocksDBException) cause;
            }
            throw new RocksDBException(
                    "Failed to open DB with SidePluginRepo: " + cause.getMessage());
        } catch (Exception e) {
            cleanupFailedOpen(opened, repo, registered, null);
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
        Object repo = null;
        RocksDB opened = null;
        boolean registered = false;
        try {
            // Initialize ToplingDB repo with common operations
            repo = initializeToplingRepo(dbOptions, dataPath, optionPath);

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

            // Validate and store result before starting HTTP server
            opened = validateAndStoreResult(result, repo, dataPath, cfDescriptors.size());
            registered = true;

            // Start HTTP server if needed
            startHttpServerIfNeeded(repo, dataPath, openHttp, optionPath);

            return opened;

        } catch (InvocationTargetException e) {
            cleanupFailedOpen(opened, repo, registered, cfHandles);
            Throwable cause = e.getCause();
            if (cause instanceof RocksDBException) {
                throw (RocksDBException) cause;
            }
            throw new RocksDBException(
                    "Failed to open DB with SidePluginRepo: " + cause.getMessage());
        } catch (Exception e) {
            cleanupFailedOpen(opened, repo, registered, cfHandles);
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
     * Cleanup resources when opening RocksDB fails after successful openDB call.
     * This prevents resource leaks when HTTP server startup or other post-open operations fail.
     *
     * @param opened     The RocksDB instance that was opened (may be null)
     * @param repo       The SidePluginRepo instance (may be null)
     * @param registered Whether the RocksDB was registered in rocksDBToRepoMap
     * @param cfHandles  List of column family handles to close (may be null)
     */
    private void cleanupFailedOpen(RocksDB opened, Object repo, boolean registered,
                                   List<ColumnFamilyHandle> cfHandles) {
        if (opened == null && repo == null) {
            // Nothing to clean up
            return;
        }

        LOG.warn("Cleaning up resources after failed RocksDB open operation");

        // Remove from map if registered
        if (registered && opened != null) {
            rocksDBToRepoMap.remove(opened);
        }

        // Close column family handles if provided
        if (cfHandles != null && !cfHandles.isEmpty()) {
            for (ColumnFamilyHandle cfHandle : cfHandles) {
                if (cfHandle != null) {
                    try {
                        cfHandle.close();
                    } catch (Exception e) {
                        LOG.warn("Failed to close column family handle during cleanup", e);
                    }
                }
            }
        }

        // Close RocksDB instance
        if (opened != null) {
            try {
                opened.close();
            } catch (Exception e) {
                LOG.warn("Failed to close RocksDB instance during cleanup", e);
            }
        }

        // Close SidePluginRepo
        if (repo != null) {
            try {
                Class<?> sidePluginRepoClass = repo.getClass();
                Method closeAllDBMethod = sidePluginRepoClass.getMethod("closeAllDB");
                closeAllDBMethod.invoke(repo);
                LOG.debug("Successfully called closeAllDB() on SidePluginRepo during cleanup");
            } catch (Exception e) {
                LOG.warn("Failed to call closeAllDB() on SidePluginRepo during cleanup", e);
            }
        }
    }

    /**
     * Utility function to convert options to JSON string for ToplingDB
     * Moved from RocksDBStdSessions
     */
    private static String converseOptionsToJsonString(String dataPath, List<String> cfs)
            throws RocksDBException {
        if (dataPath == null || dataPath.trim().isEmpty()) {
            throw new RocksDBException("RocksDB dataPath cannot be null or empty");
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
        // 1. Path format validation
        if (optionPath == null || optionPath.isBlank()) {
            throw new IllegalArgumentException("option_path can't be null or empty");
        }
        if (!SAFE_PATH_PATTERN.matcher(optionPath).matches() ||
            optionPath.contains("..") || optionPath.contains("://")) {
            throw new IllegalArgumentException("Invalid option_path format: " + optionPath);
        }
        String lower = optionPath.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".yaml") || lower.endsWith(".yml"))) {
            throw new IllegalArgumentException("option_path must end with .yaml or .yml");
        }

        // 2. Normalize path and constrain under allowed directory
        Path allowedDir = Paths.get(ALLOWED_CONFIG_DIR).toAbsolutePath().normalize();
        Path configPath = Paths.get(optionPath).toAbsolutePath().normalize();

        if (!configPath.startsWith(allowedDir)) {
            throw new SecurityException("option_path must be under " + ALLOWED_CONFIG_DIR);
        }

        // 3. Validate file existence and readability
        if (!Files.isRegularFile(configPath) || !Files.isReadable(configPath)) {
            throw new IllegalArgumentException(
                    "Config file not found or not readable: " + configPath);
        }

        // 4. File size limit (prevent DoS)
        final long fileSize;
        try {
            fileSize = Files.size(configPath);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to access config file size: " + configPath,
                                               e);
        }
        if (fileSize > MAX_CONFIG_FILE_SIZE) {
            throw new IllegalArgumentException("Config file too large: " + fileSize + " bytes");
        }
    }

    /**
     * Validate YAML content using RocksDBOptions utility method.
     * This method will be available when hugegraph-rocksdb is in classpath.
     */
    private static void validateYamlContent(String yamlContent) {
        // Use a safe YAML parser and disable dangerous features
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        try {
            yaml.load(yamlContent);
            // TODO: validate config schema
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid YAML configuration", e);
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
