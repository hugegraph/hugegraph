# RocksDB Plus (ToplingDB) Support and Configuration

- **Status**: Implemented
- **Pull Request**: [#15](https://github.com/hugegraph/hugegraph/pull/15)



## Background knowledge

[ToplingDB](https://github.com/topling/toplingdb) is a high-performance, cloud-native key-value store built as a fork of RocksDB.

ToplingDB extends RocksDB with several advanced features:

- **Searchable Compression**: ToplingDB introduces compression algorithms that preserve searchability, enabling efficient queries directly on compressed data.
- **SidePlugin Architecture**: It supports dynamic configuration via YAML files through a plugin system, allowing runtime tuning without recompilation.
- **Built-in Observability**: A lightweight HTTP server exposes internal metrics and configuration states, making it easier to monitor and debug storage behavior.
- **Distributed Compaction**: Designed for cloud environments, ToplingDB supports distributed compaction strategies to reduce write amplification and improve throughput.
- **Compatibility**: Drop-in replacement for RocksDB in most use cases.



## Motivation

Introduce a new optional storage component in HugeGraph to support RocksDB Plus ([ToplingDB](https://github.com/topling/toplingdb)), a configurable and observable extension of the RocksDB storage engine.

RocksDB Plus resolves key limitations in HugeGraph’s  current `rocksdbjni` integration, which relies heavily on hard-coding parameters and lacks runtime configurability and observability.

By enabling YAML-based configuration and exposing a Web Server interface, RocksDB Plus allows users to fine-tune performance and monitor engine behavior without modifying code or restarting services.

This is especially valuable in environments where storage workloads vary across deployments, and where operational transparency is critical for debugging and optimization.

For example, in production clusters with heterogeneous hardware or mixed graph workloads, users can dynamically adjust compaction, caching, and I/O settings to match their performance goals.

Additionally, RocksDB Plus maintains full compatibility with the existing RocksDB API, allowing seamless migration and fallback. Users can opt into RocksDB Plus via configuration, without impacting legacy data or workflows.

By supporting RocksDB Plus, HugeGraph empowers users with greater control over storage behavior, simplifies deployment through automated dynamic library loading, and enhances operational insight—all while preserving compatibility and ease of use.



## Goals

**Introduce RocksDB Plus as a configurable and observable alternative to RocksDB.**

Enable users to select RocksDB Plus via configuration, allowing dynamic tuning through YAML files and real-time monitoring via Web Server—without sacrificing compatibility with existing RocksDB APIs.



## Design

### Configuration Parameters

To support RocksDB Plus in HugeGraph, two new configuration parameters have been introduced: `rocksdb.option_path` and `rocksdb.open_http`. These options allow users to dynamically configure RocksDB behavior and enable real-time observability.

#### `rocksdb.option_path`: External YAML Configuration

This parameter allows users to specify a YAML file that defines RocksDB Plus settings such as compaction strategy, cache size, compression type, and more.

- **Purpose**: Replace hard-coding parameters with flexible, file-based configuration.

- **Usage**: Add the following line to your `hugegraph.properties` file:

  ```
  rocksdb.option_path=./conf/graphs/rocksdb_plus.yaml
  ```

  The specified YAML file will be automatically loaded during database initialization if RocksDB Plus is available.

  For details on the YAML structure and supported configuration fields, please refer to [SidePlugin](https://github.com/topling/sideplugin-wiki-en/wiki).

- **Implementation**: During initialization, HugeGraph checks whether the configured JAR contains RocksDB Plus APIs. If so, it uses reflection to load the SidePluginRepo class and calls `importAutoFile(optionPath)` to parse the YAML file. The resulting configuration is applied to the RocksDB instance.

- **Fallback**: If the YAML file is not provided or RocksDB Plus is unavailable, HugeGraph will fall back to standard RocksDB behavior.



#### `rocksdb.open_http`: Enable Web Server for Observability

This boolean flag controls whether the embedded Web Server in RocksDB Plus should be started. The server exposes runtime metrics, configuration status, and internal RocksDB statistics via a browser-accessible interface.

- **Purpose**: Provide real-time visibility into the storage engine for debugging and performance tuning.

- **Usage**: Add the following line to your `hugegraph.properties` file:

  ```
  rocksdb.open_http=true
  ```

  The listening port is defined in the YAML file specified by `option_path`, under the key `http.listening_ports`.

  To preview the Web Server interface and its layout, see [Web Server](https://github.com/topling/sideplugin-wiki-en/wiki/WebView).

- **Implementation**: If `open_http` is set to true and the database instance is `GRAPH_STORE`, HugeGraph invokes `startHttpServer()` on the RocksDB Plus repo object. This exposes a browser-accessible dashboard for monitoring RocksDB internals.

- **Scope**: For simplicity, the Web Server is only enabled for the `GRAPH_STORE` instance, which holds the main graph data.



### Reflection-Based Loading Mechanism

To support RocksDB Plus without introducing hard dependencies, HugeGraph uses Java reflection to detect and load enhanced APIs at runtime.

During initialization, HugeGraph checks whether the current JAR contains the class `com.topling.sideplugin.SidePluginRepo`. If present, it assumes RocksDB Plus is available and proceeds to:

1. **Load the SidePluginRepo class via reflection**   This avoids compile-time coupling and allows fallback to standard RocksDB if the class is missing.
2. **Invoke** `importAutoFile(optionPath)`   This method parses the YAML configuration file specified by `rocksdb.option_path`, dynamically applying storage engine parameters.
3. **Call** `open()` **with a JSON descriptor**   The parsed configuration is converted to a JSON structure and passed to the RocksDB Plus engine to initialize the database.
4. **Optionally start the Web Server**   If `rocksdb.open_http` is true and the instance is `GRAPH_STORE`, HugeGraph invokes `startHttpServer()` via reflection to enable observability.

This design ensures that RocksDB Plus can be integrated as an optional enhancement, without breaking compatibility or requiring changes to the core HugeGraph codebase.



## Impact

### For Users

User experience remains unchanged.
The RocksDB Plus integration is fully embedded into the existing startup scripts (`init-store.sh` and `start-hugegraph.sh`). Users only need to set `rocksdb.option_path` to specify the YAML file path and adjust its contents as needed to tune the storage engine.


### For Developers

Developers need to make two adjustments to enable RocksDB Plus during development:

1. **Maven Repository Configuration**   Since RocksDB Plus is published via GitHub Packages, developers must add the GitHub repository to their `settings.xml` to fetch the correct JAR:

   ```
   <repository>
       <id>github</id>
       <url>https://maven.pkg.github.com/hugegraph/toplingdb</url>
       <snapshots>
           <enabled>true</enabled>
       </snapshots>
   </repository>
   ```

2. **IDE Environment Setup**   When using IDEs like IntelliJ IDEA, developers must configure runtime environment variables to preload the required native libraries. Developers can execute `preload-topling.sh` to extract the native libraries. The dynamic libraries and static resources required by the Web Server are extracted into the `library` directory located alongside the `bin` directory.

   In your IDE’s Run/Debug Configuration, set:

   ```
   LD_LIBRARY_PATH=/path/to/your/library:$LD_LIBRARY_PATH
   LD_PRELOAD=libjemalloc.so:librocksdbjni.so
   ```

These steps ensure that RocksDB Plus loads correctly in development environments and behaves consistently with production deployments.



## Links

* **ToplingDB**: https://github.com/topling/toplingdb
* **Configuration YAML of ToplingDB**: https://github.com/topling/sideplugin-wiki-en/wiki
* **Web Server of ToplingDB**: https://github.com/topling/sideplugin-wiki-en/wiki/WebView

