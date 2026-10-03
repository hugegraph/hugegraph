# Standalone RocksDB snapshot recovery

Standalone RocksDB snapshot restore retains its source checkpoint until the
replacement is installed and reopened. Before changing data, it records the
source checkpoint and WAL location in a sibling `<data-path>.resume-pending`
file. A subsequent HugeGraph open retries an interrupted installation before
native recovery. Missing sources, incomplete metadata or a changed WAL
configuration stop opening instead of replaying uncertain data.

Preserve the checkpoint, pending marker and configured paths after a failure.
Restore access or free space, then retry normal startup with the same runtime
and configuration. Do not delete the pending marker to bypass the guard.
Successful native reopening clears it and then attempts checkpoint cleanup,
preserving the existing consume-on-success behavior.

A sibling `<data-path>.resume-lock` serializes cooperating opens and restores.
The OS lock remains held through native close and reopen, and is released when
the database finally closes. The lock file remains on disk; its presence alone
does not prove an active owner. Do not delete it to force another opener through.
Older binaries and unrelated writers do not honor this protocol and must not
access these directories concurrently.

Mount the parent data root, such as `rocksdb-data`, rather than an individual
store directory such as `data/g`. Java opening rejects a store that is itself a
volume mount, including same-filesystem bind mounts on Linux. Such aliases can
hide the sibling guards, and directory replacement cannot replace a mount point.
This Java check is per database: it does not promise a pre-JVM scan of every
graph or that no sibling database has initialized before another open fails.
Keep mount layout unchanged throughout startup and recovery.

Independent WAL, WAL inside data, and data inside a WAL root use in-place log
replacement. Preserve WAL symlink configuration across retries. Local fault
tests cover interrupted operations and reopening; they do not establish
power-cut durability. This mechanism does not provide an atomic whole-graph
restore or an HStore multi-partition snapshot protocol.
