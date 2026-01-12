# ToplingDB Support and Configuration

- **Status**: Implemented
- **Pull Request**: [#15](https://github.com/hugegraph/hugegraph/pull/15)

## Background knowledge

[ToplingDB](https://github.com/topling/toplingdb) is a high-performance, cloud-native key-value store built as a fork of RocksDB.

ToplingDB extends RocksDB with several advanced features:

- **Searchable Compression**: ToplingDB introduces compression algorithms that preserve searchability, enabling efficient queries directly on compressed data.
- **SidePlugin Architecture**: It supports configuration via YAML files through a plugin system, allowing tuning parameters without recompilation.
- **Built-in Observability**: A lightweight HTTP server exposes internal metrics and configuration states, making it easier to monitor and debug storage behavior.
- **Distributed Compaction**: Designed for cloud environments, ToplingDB supports distributed compaction strategies to reduce write amplification and improve throughput.
- **Compatibility**: Drop-in replacement for RocksDB in most use cases.

## Motivation

Introduce a new optional storage component in HugeGraph to support [ToplingDB](https://github.com/topling/toplingdb)), a configurable and observable extension of the RocksDB storage engine.

ToplingDB resolves key limitations in HugeGraph’s  current `rocksdbjni` integration, which relies heavily on hard-coding parameters and lacks runtime configurability and observability.

By enabling YAML-based configuration and exposing a Web Server interface, ToplingDB allows users to fine-tune performance and monitor engine behavior without modifying code or restarting services.

This is especially valuable in environments where storage workloads vary across deployments, and where operational transparency is critical for debugging and optimization.

For example, in production clusters with heterogeneous hardware or mixed graph workloads, users can adjust compaction, caching, and I/O settings to match their performance goals.

Additionally, ToplingDB maintains full compatibility with the existing RocksDB API, allowing seamless migration and fallback. Users can opt into ToplingDB via configuration, without impacting legacy data or workflows.

By supporting ToplingDB, HugeGraph empowers users with greater control over storage behavior, simplifies deployment through automated dynamic library loading, and enhances operational insight—all while preserving compatibility and ease of use.

## Goals

**Introduce ToplingDB as a configurable and observable alternative to RocksDB.**

Enable users to select ToplingDB via configuration, allowing tuning parameters through YAML files without recompilation and real-time monitoring via Web Server—without sacrificing compatibility with existing RocksDB APIs.

## Design

### Configuration Parameters

To support ToplingDB in HugeGraph, two new configuration parameters have been introduced: `rocksdb.option_path` and `rocksdb.open_http`. These options allow users to configure RocksDB parameters and enable real-time observability.

#### `rocksdb.option_path`: External YAML Configuration

This parameter allows users to specify a YAML file that defines ToplingDB settings such as compaction strategy, cache size, compression type, and more.

- **Purpose**: Replace hard-coding parameters with flexible, file-based configuration.

- **Usage**: Add the following line to your `hugegraph.properties` file:

  ```properties
  rocksdb.option_path=./conf/graphs/rocksdb_plus.yaml
  ```

  The specified YAML file will be automatically loaded during database initialization if ToplingDB is available.

  For security reasons, HugeGraph only allows YAML files to be stored under the `$HUGEGRAPH_HOME/conf/graphs` directory.

  For details on the YAML structure and supported configuration fields, please refer to [SidePlugin](https://github.com/topling/sideplugin-wiki-en/wiki).

- **Implementation**: During initialization, HugeGraph checks whether the configured JAR contains ToplingDB APIs. If so, it uses reflection to load the SidePluginRepo class and calls `importAutoFile(optionPath)` to parse the YAML file. The resulting configuration is applied to the RocksDB instance.

- **Fallback**: If the YAML file is not provided or ToplingDB is unavailable, HugeGraph will fall back to standard RocksDB behavior.

#### `rocksdb.open_http`: Enable Web Server for Observability

This boolean flag controls whether the embedded Web Server in ToplingDB should be started. The server exposes runtime metrics, configuration status, and internal RocksDB statistics via a browser-accessible interface.

- **Purpose**: Provide real-time visibility into the storage engine for debugging and performance tuning.

- **Usage**: Add the following line to your `hugegraph.properties` file:

  ```properties
  rocksdb.open_http=true
  ```

  The listening port is defined in the YAML file specified by `option_path`, under the key `http.listening_ports`:
  
  ```yaml
  http:
    document_root: /dev/shm/rocksdb_resource
    listening_ports: '127.0.0.1:2011' # by default, only local access is allowed
  ```

  For security reasons, the default configuration only allows local access.
  When adjusting this setting, users should carefully manage port and network access permissions to avoid potential security incidents.
  To preview the Web Server interface and its layout, see [Web Server](https://github.com/topling/sideplugin-wiki-en/wiki/WebView).

- **Implementation**: If `open_http` is set to true and the database instance is `GRAPH_STORE`, HugeGraph invokes `startHttpServer()` on the ToplingDB repo object. This exposes a browser-accessible dashboard for monitoring RocksDB internals.

- **Scope**: For simplicity, the Web Server is only enabled for the `GRAPH_STORE` instance, which holds the main graph data.

- **Security**: The Web Server does **not** provide built-in authentication. In production environments, configure firewalls or network access controls carefully to prevent unauthorized access.

### Reflection-Based Loading Mechanism

To support ToplingDB without introducing hard dependencies, HugeGraph uses Java reflection to detect and load enhanced APIs at runtime.

During initialization, HugeGraph checks whether the current JAR contains the class `com.topling.sideplugin.SidePluginRepo`. If present, it assumes ToplingDB is available and proceeds to:

1. **Load the SidePluginRepo class via reflection**   This avoids compile-time coupling and allows fallback to standard RocksDB if the class is missing.
   - If the ToplingDB API cannot be found, HugeGraph silently falls back to the standard RocksDB API for startup.
2. **Invoke** `importAutoFile(optionPath)`   This method parses the YAML configuration file specified by `rocksdb.option_path` to configure storage engine parameters.
   - If the `option_path` is incorrect or parsing fails, ToplingDB throws an error and terminates the startup process.
3. **Call** `open()` **with a JSON descriptor**   The parsed configuration is converted to a JSON structure and passed to the ToplingDB engine to initialize the database.
4. **Optionally start the Web Server**   If `rocksdb.open_http` is true and the instance is `GRAPH_STORE`, HugeGraph invokes `startHttpServer()` via reflection to enable observability.
   - If the Web Server cannot be started due to misconfiguration **or if the specified HTTP port is already in use**, ToplingDB throws an error and the startup process is terminated

This design ensures that ToplingDB can be integrated as an optional enhancement, without breaking compatibility or requiring changes to the core HugeGraph codebase.

## Impact

### For Users

The way users operate remains unchanged by default, and adding ToplingDB configuration provides additional functionality.
The ToplingDB integration is fully embedded into the existing startup scripts (`init-store.sh` and `start-hugegraph.sh`). Users only need to set `rocksdb.option_path` to specify the YAML file path and adjust its contents as needed to tune the storage engine.

### For Developers

Developers need to make two adjustments to enable ToplingDB during development:

1. **Maven Repository Configuration**: since ToplingDB is published via GitHub Packages, developers must add the GitHub repository to their `settings.xml` to fetch the correct JAR:

   ```xml
    <!-- Configure GitHub account information -->
    <!-- The <server> section is used to configure authentication for GitHub Packages -->
   <servers>
       <server>
           <id>github</id>
           <username>YOUR_GITHUB_ACTOR</username>
           <!-- Ensure that YOUR_GITHUB_TOKEN has at least the read:packages permission -->
           <password>YOUR_GITHUB_TOKEN</password>
       </server>
   </servers>
   
   <profiles>
       <profile>
            <id>...</id>
           <repositories>
               ...
               <!-- The repository id here must match the server id defined above -->
               <repository>
                   <id>github</id>
                   <url>https://maven.pkg.github.com/hugegraph/toplingdb</url>
                   <snapshots>
                       <enabled>true</enabled>
                   </snapshots>
               </repository>
           </repositories>
       </profile>
   </profiles>
   ```

2. **IDE Environment Setup**: developers must configure runtime environment variables to preload required native libraries.
   The `preload-topling.sh` script not only extracts the necessary dynamic libraries and web server static resources into the `library` directory next to the `bin` directory,
   but also sets the required environment variables in the current process.
   When executed in a terminal using `source preload-topling.sh`, these variables take effect immediately in that shell session.

   However, when launching HugeGraph from an IDE, the program typically runs in a separate process,
   so environment variables defined in scripts run from the terminal are not inherited.
   In this case, developers need to manually configure the IDE's run/debug environment variables to ensure proper preloading of native libraries.

   In your IDE’s Run/Debug Configuration, set:

   ```bash
   LD_LIBRARY_PATH="/path/to/your/library:${LD_LIBRARY_PATH}"
   LD_PRELOAD="libjemalloc.so:librocksdbjni.so"
   ```

These steps ensure that ToplingDB loads correctly in development environments and behaves consistently with production deployments.

## Links

- **ToplingDB**: [https://github.com/topling/toplingdb](https://github.com/topling/toplingdb)
- **Configuration YAML of ToplingDB**: [https://github.com/topling/sideplugin-wiki-en/wiki](https://github.com/topling/sideplugin-wiki-en/wiki)
- **Web Server of ToplingDB**: [https://github.com/topling/sideplugin-wiki-en/wiki/WebView](https://github.com/topling/sideplugin-wiki-en/wiki/WebView)
