# Standard RocksDB runtime upgrade

The standard runtime aligns RocksDB JNI to 8.10.2. Validate existing data before
rolling out this dependency change. Keep a restorable backup made by the old
runtime; do not assume that data opened or modified by 8.10.2 can be reopened by
an older runtime. Rollback requires restoring the backup with the matching old
application and dependencies, rather than replacing the JAR in an upgraded data
directory.

PD and Store also upgrade JRaft from 1.3.13 to 1.3.14. The older JRaft table
configuration copier calls compressed-cache methods removed from RocksDB 8.x,
which prevents Raft storage initialization. Treat the JNI and JRaft changes as
one service upgrade and validate existing Raft logs with the complete new package.

## Reproducible JNI fixture

Run [the fixture](../install-dist/scripts/rocksdb-upgrade/run.sh) with a JDK and
explicit, trusted standard `org.rocksdb:rocksdbjni` JARs. It adds no build
dependency and does not download or select JARs implicitly. From the repository
root, using the Maven local repository as an example:

```bash
bash install-dist/scripts/rocksdb-upgrade/run.sh \
  "$HOME/.m2/repository/org/rocksdb/rocksdbjni/6.29.5/rocksdbjni-6.29.5.jar" \
  "$HOME/.m2/repository/org/rocksdb/rocksdbjni/7.7.3/rocksdbjni-7.7.3.jar" \
  "$HOME/.m2/repository/org/rocksdb/rocksdbjni/8.10.2/rocksdbjni-8.10.2.jar" \
  /tmp/hugegraph-rocksdb-upgrade-new-run
```

The output directory must not exist. All original databases, upgraded copies,
classes and logs remain there on success or failure. Reruns require a new output
path. The runner prints each command and exit code; each JVM prints its actual
RocksDB class CodeSource, JAR SHA-256 and native version. Record the tested source
revision and JAR acquisition provenance alongside `run.log`.

For each old version, three separate JVMs perform these steps:

1. Create the default and a named column family, write three SST records per
   family, then write two additional synchronous WAL records per family. Check
   that the two records remain in each active memtable and that nonempty SST
   and WAL files exist. Halt without closing RocksDB, preventing a shutdown
   flush from disguising missing WAL recovery.
2. Preserve the original directory and open only a copy with 8.10.2. Check all
   ten old values and exact iterator counts. Update one SST value, delete an
   SST value and a WAL value, append a new value, and flush both families.
3. Reopen the upgraded copy in another 8.10.2 JVM. Check the eight remaining
   values, deleted keys and exact iterator counts.

A successful run reports two upgrade pairs, six JVM phases, zero skips, and
assertion counts per phase. `halt(0)` is intentional only in the seed phase;
any missing seed PASS line or nonzero command exit means the run failed.

This proves the exercised **JNI SST/WAL fixture** only. It does not prove
HugeGraph schema/index/auth compatibility, production tuning combinations,
PD/Store service upgrades, JRaft log-storage recovery, replica catch-up or TP
compatibility. Those require old-version service-created data, the new complete
application artifacts, relevant queries/writes, restart/recovery and service
checks. Keep those acceptance gates separate; fixture success does not waive
one. Do not delete lock, pending or checkpoint files to make an upgrade pass.

## Store monitoring changes

The JNI upgrade removes registration of these unavailable ticker constants:

- `BLOCK_CACHE_INDEX_BYTES_EVICT`, `BLOCK_CACHE_FILTER_BYTES_EVICT`
- `NO_FILE_CLOSES`, `RATE_LIMIT_DELAY_MILLIS`, `NO_ITERATORS`
- `NUMBER_FILTERED_DELETES`
- `BLOCK_CACHE_COMPRESSED_MISS`, `BLOCK_CACHE_COMPRESSED_HIT`,
  `BLOCK_CACHE_COMPRESSED_ADD`, `BLOCK_CACHE_COMPRESSED_ADD_FAILURES`
- `WRITE_TIMEDOUT`

It also removes these histogram constants:

- `STALL_L0_SLOWDOWN_COUNT`, `STALL_MEMTABLE_COMPACTION_COUNT`,
  `STALL_L0_NUM_FILES_COUNT`
- `HARD_RATE_LIMIT_DELAY_COUNT`, `SOFT_RATE_LIMIT_DELAY_COUNT`

Store meter names use `rocks.stats.` plus the lowercase constant name. For
histograms, the removed meters include `.max`, `.mean`, `.min`, `.summary`
(with its quantile tags), `.summary.sum` and `.summary.count`. Exporters may
normalize names further. Update dashboards and alerts that reference these
series; their disappearance is not a zero reading. No replacement series or
semantic equivalence is asserted here. See
[RocksDBMetricsConst](../hugegraph-store/hg-store-node/src/main/java/org/apache/hugegraph/store/node/metrics/RocksDBMetricsConst.java)
and [meter registration](../hugegraph-store/hg-store-node/src/main/java/org/apache/hugegraph/store/node/metrics/RocksDBMetrics.java).
