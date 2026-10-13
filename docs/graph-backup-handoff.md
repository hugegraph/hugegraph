# RocksDB Server-Managed Backup: Engineering Handoff

Snapshot: **2026-10-13**, branch `feat/rocksdb-server-managed-backup`, reviewed
implementation commit `b112fbea761216de0d9d0bd59f455cd7272b389c`.
Comparison base: `83ef9f3fa23cd65284d4567f7aa734cf90aa0749` (`master` locally).
This is a source review plus one compile attempt, **not a release sign-off**.
Documentation baseline: `8ef59e0e`; this follow-up rechecked source locations
without rerunning compilation or tests. Both documentation passes leave the
implementation unchanged; known code issues remain open.

## Start here

1. Confirm branch and working tree with `git status --short --branch`; compare
   implementation changes with `git diff b112fbea -- hugegraph-server/` before
   relying on the findings below.
2. Fix the duplicate declaration and establish a native-test baseline. This only
   unblocks investigation; it does not make restore safe.
3. Fix recovery-path, switch-rollback and retention ordering defects below,
   adding a regression test for each before expanding the E2E suite.
4. Resolve reader admission/draining and graph-wide crash recovery before
   claiming online restore or restart safety. Only then run release acceptance.

Read the [usage contract](graph-backup.md) first, then
[Server guidance](../hugegraph-server/AGENTS.md). Paths below are relative to
`hugegraph-server/` unless otherwise stated. Update this snapshot as work lands;
do not treat historical verification as evidence for a newer implementation.

## Goal and current shape

Provide graph-level backup through Server REST/tasks rather than client-side
copies of live RocksDB files. Use native BackupEngine backups per physical DB,
then publish a graph manifest linking all native backup ids. Restore selected
versions through staging and provider/session close/reopen. No new dependency
was introduced; the module uses its existing RocksDB JNI 8.10.2 dependency.

| Area | Entry point | Present in source |
| --- | --- | --- |
| REST | `hugegraph-api/.../api/graphs/GraphBackupsAPI.java` | Create, restore, list, detail; input validation; request-id lookup in retained task records |
| Jobs | `hugegraph-core/.../backup/GraphBackupJob.java`, `GraphRestoreJob.java` | Persisted JSON input and task result; reconstructable job classes |
| Concurrency | `hugegraph-core/.../backup/GraphWriteFence.java`, `backend/tx/AbstractTransaction.java` | Per-graph operation/capture coordination and mutation-commit hook |
| Provider/session lifecycle | `hugegraph-rocksdb/.../rocksdb/RocksDBStoreProvider.java`, `RocksDBStore.java`; `hugegraph-core/.../backend/store/BackendSessionPool.java` | Enumerate physical DB paths, stage/switch/rollback, force-close sessions |
| Orchestration | `hugegraph-rocksdb/.../rocksdb/backup/RocksDbGraphBackupService.java` | Capture/verify/publish/prune; prepare/close/switch/reopen/cleanup |
| Native IO and paths | Same `backup/` package: `RocksDbBackupExecutor.java`, `BackupPathUtils.java`, `GraphBackupManifest.java` | Native version capture/verify/restore/deletion; path-component checks; manifest metadata |
| Config | `hugegraph-core/.../config/CoreOptions.java`; distribution `conf/graphs/hugegraph.properties` | `backup.repository_root`, default `./backups` |

Here `...` expands to `src/main/java/org/apache/hugegraph/`. The config file lives
under `hugegraph-dist/src/assembly/static/`.

## Confirmed blockers and coverage gaps

1. **Compilation fails.** `RocksDbGraphBackupService.readManifest()` declares
   `String fileName = path.getFileName().toString();` twice consecutively
   (lines 263–264 at the snapshot). Remove the duplicate, then rebuild before
   investigating runtime behavior. Do not infer that fixing it makes tests pass.
2. **Startup recovery looks in the wrong directory.** `RocksDBStore.open()`
   lines 518–524 passes configured root/disk paths to recovery, while `wrapPath()`
   lines 693–701 appends the store name for the actual DB. Switch markers are
   siblings of actual DB directories (`restoreMarker()`, lines 319–320), so
   startup misses those markers. Recover actual DB paths and coordinate all
   stores before accepting traffic; test process termination between moves.
3. **A failed switch-marker write can escape rollback.** In
   `RocksDBStore.switchNativeBackups()` lines 250–269, staged data is installed
   before writing the switched marker and adding the current DB to the rollback
   map. If that marker write fails, the target already exists, so the catch does
   not restore its old directory; it rolls back earlier DBs and deletes the
   current marker. Register rollback ownership before installation and test this
   exact failure point to rule out a mixed-generation graph.
4. **Retention can leave published but unrestorable versions.** Service
   `prune()` lines 346–359 deletes native backups before obsolete manifests.
   A failure partway through leaves old manifests visible but their data missing.
   Unpublish obsolete manifests before retryable native garbage collection and
   test interruption plus concurrent list/detail.
5. **E2E environment is hard-coded.** `GraphBackupApiTest` line 43 uses
   `C:/hg-e2e-*`. On Linux this is a relative path, resolved separately by test
   and server working directories; on another Windows host C: may not be writable.
   Use a portable server-visible scratch root and define whether tests require
   co-location. Verify cleanup against the server's actual directory.
6. **Coverage is much narrower than the intended contract.** The added API test
   creates two vertices, captures two versions, deletes a vertex and restores
   each version; it checks vertex presence and a label property. It does not
   create edges/indexes, restart Server, exercise idempotency or retention,
   test concurrent traffic, corrupt manifests, or inject restore failures.
7. **Publication is not unconditionally atomic.** `writeManifest()` falls back
   to ordinary replacement if `ATOMIC_MOVE` is unsupported and does not fsync
   the manifest/directory. Specify the supported filesystem/crash contract and
   test it rather than promising unconditional atomic/durable publication.

## Safety boundary requiring a decision

`GraphWriteFence` coordinates mutation commits and backup/restore operations
within one JVM; its lock names do not mean it protects graph reads. Restore
force-closes native sessions, while there is no read-admission/drain mechanism in
this fence. Concurrent-reader failure is a source-level risk, not a reproduced
JVM crash. Choose and enforce either a maintenance boundary that drains readers
and background work, or lifecycle coordination through iterator completion.
An operator recommendation to stop traffic is not an enforced safety boundary.

Per-database restore markers also do not establish a graph-wide commit point.
Fixing the marker lookup alone is insufficient: kill/restart tests must prove
that schema, graph and other opened stores recover to the same generation.

## Validation evidence at this snapshot

Environment: Windows 11, Temurin Java 11.0.32.1, Maven 3.9.16.

| Check | Result |
| --- | --- |
| `mvn -pl hugegraph-server/hugegraph-rocksdb -am compile -DskipTests -Dmaven.javadoc.skip=true` | **FAIL**: compiler reports duplicate `fileName` at service line 264; dependencies reached compilation successfully |
| Native backup/manifest tests | Source inspected; **not run** because compilation is blocked |
| Server API E2E / restart / fault injection | **Not run**; no server was started in this review |

Existing tests:

- `hugegraph-rocksdb/src/test/java/.../backup/RocksDbBackupExecutorTest.java`:
  three native versions (before/after/deleted) and selected restore values.
- Same directory, `GraphBackupManifestTest.java`: metadata/map getter assertions;
  despite its name, it does not serialize/deserialize JSON on disk.
- `hugegraph-test/src/main/java/.../api/GraphBackupApiTest.java`: REST/task golden
  path above, registered in `ApiTestSuite`. This is a live-server test, not an
  automatically bootstrapped unit test. `BaseApiTest` uses localhost:8080 and
  admin credentials configured by the CI runner; use a disposable environment.

## Next work and acceptance gates

Work in this order, retaining separate evidence for each gate:

1. Fix the compile blocker and portable E2E paths; establish native-test and
   existing API-suite baselines, then fix defects 2–4 with failure-injection
   regressions before treating a passing golden path as meaningful safety evidence.
   Record test counts and Surefire reports, not just Maven exit status.
2. Complete data fidelity coverage: vertex/edge ids and properties, schema ids,
   index definitions and indexed query results across two different versions;
   repeat with separate WAL/data paths and disk-mapped physical databases.
3. Prove operation safety: simultaneous backups/restores, writes during capture,
   schema/index/background tasks, readers during restore and stale sessions or
   caches after provider reopen. Define a maintenance/drain policy if required.
4. Prove task semantics: same request twice returns one task, changed payload is
   rejected, restart before execution and during capture/switch behaves safely.
   Reconstructed jobs do not by themselves prove exactly-once effects; restore
   also rewinds the task store and may affect task visibility/recovery.
5. Inject failures at native capture, verify, manifest publication, each directory
   move, provider reopen, rollback and cleanup. Include process termination:
   per-database markers exist, but their current lookup path is wrong and they
   do not establish a graph-wide crash commit boundary.
   Assert either usable old data or usable restored data, never a mixed graph.
6. Test `keep_num` 0/1/N, corrupted/missing native backups, malformed manifests,
   path traversal/symlinks, insufficient space/permissions and unsupported
   backends. Check that list/detail remain consistent during pruning and that a
   prune failure does not obscure a successfully published backup.
7. Before merging a user-visible feature, align in-repository docs and any needed
   paired `apache/hugegraph-doc` PR per the contribution guide. Do not mark
   documentation or release validation complete on the basis of this handoff.

### Commands for the next engineer

Run from repository root **after fixing compilation**:

```bash
# Compile without cleaning generated PD/Store source directories
mvn -pl hugegraph-server/hugegraph-rocksdb -am compile \
    -DskipTests -Dmaven.javadoc.skip=true

# Select only the new native tests; reactor dependencies may have no matches
mvn -pl hugegraph-server/hugegraph-rocksdb -am test \
    -Dtest=RocksDbBackupExecutorTest,GraphBackupManifestTest \
    -Dsurefire.failIfNoSpecifiedTests=false

# Requires a configured, running disposable server
mvn test -pl hugegraph-server/hugegraph-test -am -P api-test,rocksdb
```

For Linux CI-equivalent packaging/auth/start/test/stop setup, read
[run-api-test.sh](../hugegraph-server/hugegraph-dist/src/assembly/travis/run-api-test.sh)
and [Server CI](../.github/workflows/server-ci.yml). The runner modifies packaged
configuration and performs network downloads; inspect prerequisites before using
it. Merely invoking the API test Maven command does not start a server.

Before pushing code changes, follow root guidance: formatting, clean compilation
and relevant module tests. For documentation-only updates, check relative links
and `git diff --check`. Do not clean or hand-edit generated gRPC Java sources as
an incidental part of this feature.
