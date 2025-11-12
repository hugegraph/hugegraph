# ToplingDB Operations Guide

This guide outlines key operational practices for deploying, monitoring, tuning, and upgrading ToplingDB in production environments. It is intended for system administrators, DevOps engineers, and database maintainers seeking to ensure stability, performance, and scalability.

---

## Monitoring Metrics

- **Key Performance Indicators (KPI)**
  - Write throughput (bytes/sec, ops/sec)
  - Read latency (P95/P99)
  - MemTable usage and flush frequency
  - Block cache hit ratio

- **Alert Thresholds**
  - L0 file count exceeding `level0_stop_writes_trigger`
  - Background job saturation (`max_background_jobs`)
  - WAL size growth beyond expected limits
  - Cache eviction rate anomalies

- **Monitoring Tool Integration**
  - Export metrics via HTTP endpoints
  - Integrate with Prometheus using custom exporters
  - Visualize trends and thresholds in Grafana dashboards
  - Use SidePlugin’s web server (`listening_ports`) for live inspection

---

## Performance Tuning

- **Cache Size Optimization**
  - Adjust `capacity` in `lru_cache` based on workload and memory budget
  - Tune `high_pri_pool_ratio` to prioritize index/filter caching

- **Compression Algorithm Selection**
  - Use `kSnappyCompression` for balanced speed and space
  - Disable compression (`kNoCompression`) for latency-sensitive workloads

- **Compaction Strategy Adjustment**
  - Set `level0_file_num_compaction_trigger` to control L0 flush frequency
  - Use `level_compaction_dynamic_file_size: true` to adapt SST sizing
  - Tune `max_subcompactions` and `max_background_jobs` for parallelism

- **I/O Tuning Parameters**
  - Evaluate `convert_to_sst: kFileMmap` to bypass traditional flush
  - Set `sync_sst_file: false` for performance, with caution on durability
  - Adjust `compaction_readahead_size` for sequential disk access

---

## Capacity Planning

- **Disk Space Estimation**
  - Base on `write_buffer_size`, `target_file_size_base`, and compaction amplification
  - Include space for WAL, MANIFEST, and temporary files

- **Memory Requirement Calculation**
  - Sum of MemTable (`mem_cap`), block cache (`capacity`), and background buffers
  - Consider `max_write_buffer_number` and `min_write_buffer_number_to_merge`

- **CPU Resource Planning**
  - Allocate cores for compaction (`max_background_compactions`)
  - Reserve CPU for Hugegraph query threads and SidePlugin HTTP services
  - Monitor mutex contention (`use_adaptive_mutex`) and shard parallelism

---

## Upgrade Procedure

- **Version Compatibility Check**
  - Review changelogs and YAML schema changes
  - Validate plugin compatibility (e.g., `cspp`, `DispatcherTable`)

- **Data Backup Strategy**
  - Snapshot SST files and MANIFEST
  - Backup column family metadata and configuration files

- **Rolling Upgrade Steps**
  - Drain traffic from target node
  - Stop ToplingDB process and apply new binary
  - Validate startup with `create_if_missing: false`
  - Rejoin cluster and monitor metrics

- **Rollback Plan**
  - Restore previous binary and configuration
  - Revert SST and MANIFEST from backup
  - Disable incompatible plugins if needed
