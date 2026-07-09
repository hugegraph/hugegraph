# ToplingDB Troubleshooting

## Issues

### Issue 1: YAML Configuration Error

Sample log output:

```java
2025-10-15 01:55:50 [db-open-1] [INFO] o.a.h.r.p.ToplingRocksDBProvider - SidePluginRepo found. Will attempt to open default CF RocksDB using Topling.
21:1: (891B):ERROR:
sideplugin/rockside/3rdparty/rapidyaml/src/c4/yml/parse.cpp:3310: ERROR parsing yml: parse error: incorrect indentation?
```

**Solution**:

1. Check that YAML indentation is correct (must use spaces, not tabs).
2. Validate YAML syntax:

   ```bash
   python -c "import yaml; yaml.safe_load(open('conf/graphs/rocksdb_server.yaml'))"
   ```

3. Review the specific error message in the logs for further clues.

HugeGraph performs basic path and YAML validation before enabling ToplingDB. If this
pre-validation fails, HugeGraph falls back to standard RocksDB behavior inside the ToplingDB
provider. If the file passes basic YAML validation but `SidePluginRepo.importAutoFile()` or
`openDB()` fails, startup fails with the corresponding ToplingDB error and the YAML file should
be fixed.

---

### Issue 2: ToplingDB Provider Not Available

When `rocksdb.provider=topling` is configured but the runtime `rocksdbjni` JAR does not contain
`org.rocksdb.SidePluginRepo`, HugeGraph fails provider activation instead of silently switching
to the standard provider.

**Solution**:

1. If you want standard RocksDB, set:

   ```properties
   rocksdb.provider=standard
   ```

2. If you want ToplingDB, confirm that Maven or the deployment addon provides a ToplingDB-capable
   `rocksdbjni` artifact.
3. Inspect the runtime JAR:

   ```bash
   jar tf lib/rocksdbjni-*.jar | grep org/rocksdb/SidePluginRepo.class
   ```

---

### Issue 3: Web Server Port Conflict

Sample log output:

```java
2025-10-15 01:57:34 [db-open-1] [INFO] o.a.h.r.p.ToplingRocksDBProvider - SidePluginRepo found. Will attempt to open default CF RocksDB using Topling.
2025-10-15 01:57:34 [db-open-1] [ERROR] o.a.h.b.s.r.RocksDBStore - Failed to open RocksDB 'rocksdb-data/data/g'
org.rocksdb.RocksDBException: rocksdb::Status rocksdb::SidePluginRepo::StartHttpServer(): null context when constructing CivetServer. Possible problem binding to port.
    at org.rocksdb.SidePluginRepo.startHttpServer(Native Method) ~[rocksdbjni-8.10.2-20250804.074027-4.jar:?]
```

**Solution**:

1. Check if the port is already in use:

   ```bash
   lsof -i :2011
   ```

2. Modify the `listening_ports` setting in the YAML configuration file.
3. Restart the HugeGraph Server.

---

### Issue 4: Database Initialization Failure

This error indicates the database lock file cannot be acquired, possibly due to insufficient write permissions or another process holding the lock:

```java
Caused by: org.rocksdb.RocksDBException: While lock file: rocksdb-data/data/m/LOCK: Resource temporarily unavailable
        at org.rocksdb.SidePluginRepo.nativeOpenDBMultiCF(Native Method)
        at org.rocksdb.SidePluginRepo.openDB(SidePluginRepo.java:22)
```

**Solution**:

1. Confirm the configuration file path is correct:

   ```properties
   rocksdb.provider=topling
   rocksdb.option_path=./conf/graphs/rocksdb_server.yaml
   ```

2. Check permissions on the data directory to ensure the running user has read/write access.
3. Review detailed logs:

   ```bash
   bin/init-store.sh 2>&1 | tee init.log
   ```

---

### Issue 5: Ubuntu 24.04+ libaio Compatibility

Some ToplingDB native libraries may expect the legacy `libaio.so.1` name, while Ubuntu 24.04+
ships the time64 library as `libaio.so.1t64`. The preload script currently creates a
compatibility symlink when running on Ubuntu 24.04+ and `libaio.so.1` is missing.

If startup fails with a missing `libaio.so.1` error, install the system package first:

```bash
sudo apt-get install libaio1t64
```

Then retry startup. If your environment does not allow scripts to use `sudo`, create the
compatibility symlink manually or ask the system administrator to provide it.

---

## Log Analysis

### Enable Debug Logging

```properties
# conf/log4j2.xml
<Logger name="org.apache.hugegraph.backend.store.rocksdb" 
        level="DEBUG"/>
```

### Key Log Locations

- Application logs: `logs/hugegraph-server.log`
- RocksDB logs: `data/rocksdb/LOG`
- Web Server logs: check the `access_log` setting in the YAML configuration

Additional notes:

- Enable debug logging only during troubleshooting, as it may generate large log files and impact performance.
- Rotate and archive logs regularly to prevent disk space exhaustion.

---

## Performance Diagnostics

### High CPU Usage

1. Review and tune **compaction** configuration (e.g., compaction style, trigger thresholds).
2. Adjust **thread pool size** to match available CPU cores and workload characteristics.
3. Optimize **write batching** to reduce per-operation overhead.
4. Monitor for **hot keys** or skewed workloads that may cause uneven CPU usage.
5. Use performance profiling tools (e.g., `perf`, `async-profiler`) to identify hotspots.

---

### Excessive Memory Usage

1. Adjust **block cache size** to balance between read performance and memory footprint.
2. Review **write buffer** (memtable) configuration, including number and size.
3. Monitor for **memory leaks** in the application layer or plugins.
4. Enable **JVM GC logging** to analyze garbage collection behavior.

---

### Disk I/O Bottlenecks

1. Use **SSD storage** for RocksDB data directories to improve latency and throughput.
2. Tune **WAL (Write-Ahead Log)** configuration, such as enabling `wal_dir` on a separate disk.
3. Optimize **compaction strategy** (e.g., level-based vs. universal compaction) based on workload.
4. Monitor **disk utilization** and IOPS using tools like `iostat` or `dstat`.
5. Separate **data, WAL, and log directories** onto different physical devices if possible.

---

### General Recommendations

- Always benchmark configuration changes in a staging environment before applying them to production.
- Use monitoring systems (e.g., Prometheus + Grafana) to track CPU, memory, and I/O metrics over time.
- Regularly review RocksDB’s internal statistics (`rocksdb.stats`) for deeper insights into performance.
- Automate log collection and alerting to quickly detect anomalies.
