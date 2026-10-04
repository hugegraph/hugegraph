# TP core launcher handoff — model switch checkpoint

2026-10-05, lane `/root/p4_resume`. User is switching model. All implementation and validation stopped after saving this checkpoint. No new build, task, remote commit/push or review resolution was started for tp-core by this lane.

## User decision and authority

Build a clean replacement PR on latest master, prioritizing a usable Topling runtime for Server, PD and Store. Generic query lifecycle hardening, interrupted recovery/generation protocols, and broad directory ownership protection move to independent follow-ups. Do not copy the old P2/P3/P4 tree or inherit their stacked branch history. User authorizes implementation; merges remain separately user-approved. Repository operations remain gh-only; no direct git and no force push.

Workspace: `/Users/zhu/github/hugegraph-topling-split-evidence/resume-20261005/tp-core/source`.
Root supplied master `91fd925d`. IMPORTANT: GitHub tarball honors export-ignore; root discovered 67 tracked configuration/CI/Docker files missing from the extraction and was restoring only missing files from the full Git tree. This lane has not independently verified completion. Check root's latest baseline manifest before diffing; missing extracted files must never be treated as intentional deletions. Preserve other agents' edits.

## Implemented in the new master-based workspace

Only the following four Java files were edited. They have NOT been compiled or tested in tp-core. `launcher-current-files.json` records SHA-256; byte copies are under `launcher-current-source/` as a model-switch checkpoint, not an instruction to overwrite concurrent changes.

| File | SHA-256 |
| --- | --- |
| `hugegraph-commons/hugegraph-common/src/main/java/org/apache/hugegraph/util/RocksDBRuntime.java` | `d6aee0e8918ef4674ba9aa3937ae7eb4dbb7cbf7b917011ca54d8a8a0a5e6e3e` |
| `hugegraph-pd/hg-pd-service/src/main/java/org/apache/hugegraph/pd/boot/HugePDServer.java` | `7bf0ade607c4227e779c7e02cce2ac7effb3c4719aa576216664c8a853f16cfa` |
| `hugegraph-store/hg-store-node/src/main/java/org/apache/hugegraph/store/node/StoreNodeApplication.java` | `d3cfe1e1241c0d0be2b6e8d6d8c0755a034ab96e0333c766d055d9f6df32f4eb` |
| `hugegraph-store/hg-store-node/src/main/java/org/apache/hugegraph/store/node/grpc/HgStoreNodeService.java` | `18d6724deae5108bd1da4a1b7c4c97113e545f2714631aba17156dd924e95e92` |

1. New Commons `org.apache.hugegraph.util.RocksDBRuntime.verify(String configuredProvider)`.
   - `rocksdb`/`topling` are the only accepted values.
   - Loads `org.rocksdb.RocksDB` with initialization=false using the thread context ClassLoader; considers Topling selected only if `org.rocksdb.SidePluginRepo` has the same CodeSource URL.
   - Standard RocksDB with an unrelated Topling marker on another origin stays standard.
   - Invalid configured value => IllegalArgumentException; missing RocksDB Java class or actual runtime mismatch => IllegalStateException with diagnostic.
   - No filesystem inspection/claims, locks, marker files, config preparse or native initialization. Commons must remain Java 8 bytecode compatible.
2. `HugePDServer.main` now creates SpringApplication, adds a context initializer checking the bound environment's `rocksdb.provider` (default rocksdb), and passes args to run. Existing master shutdown hook is retained. This is not a PD-close fix.
3. `StoreNodeApplication` retains no-args start() and adds start(String... args); main forwards args. After the existing PD listener, the context initializer checks final environment provider; run receives args.
4. `HgStoreNodeService.init` checks the actual bound RocksDB options immediately before RaftRocksdbOptions.initRocksdbGlobalConfig. It validates provider/runtime only, not data or Raft roots.

P3 agent confirmed this helper interface and owns the Server adapter integration: verify(config.get(RocksDBOptions.PROVIDER)) before the private native open paths, plus minimal provider/truncate/status behavior. Do not edit those Server adapter files from this lane.

## Approved design, NOT yet implemented

The launcher contract is explicit `TOPLINGDB_ROCKSDB_PROVIDER=topling`. Unset means standard RocksDB. Business configuration continues through existing Java parsing and must configure `rocksdb.provider=topling` for Topling; the thin helper rejects disagreement. No HugeConfig command, no ConfigData read-only subprocess and no awk inference of all business configuration.

Root accepted a component-private runtime directory OUTSIDE lib: `<component>/topling/rocksdbjni.jar` and `librocksdbjni-linux64.so`. This avoids changing standard Server recursive classpath scanning or adding lib symlink filtering. Each component gets its own runtime assets. Topling adds its selected JAR ahead of standard classes; Spring Boot launchers use external JAR + executable boot JAR with JarLauncher, standard keeps -jar behavior.

Planned files, not changed yet:
- Server static/bin `prepare-topling.sh` and `preload-topling.sh` (new shared implementation).
- Server `hugegraph-server.sh`, `init-store.sh`, `dump-store.sh` launcher integration.
- PD/Store `start-hugegraph-*.sh` launcher integration and application argument forwarding.
- PD/Store assembly descriptors copying the two shared helpers and shared native config.
- Shared `conf/toplingdb.yaml`; install the same source into each component's own conf directory. Old three configs are mostly identical and should not be blindly copied three times. Preserve known-working native settings, especially memtable_as_log_index=false for Java WriteBatch; keep HTTP disabled.
- PD/Store provider default configuration entries (master defaults remain standard).
- Focused shell selection/default/explicit/parent-child config inheritance tests; Commons runtime-origin tests and suite registration. None created yet.

Prepare requirements: external trusted absolute JAR + exact SHA-256; copy to a private temporary directory, verify hash, Topling Java marker, unique expected JNI entry and Linux x86_64 ELF/platform before use. Preserve actual native dependency/compatibility checks; SHA alone is insufficient. Installation only after stopping the component. Root requested simple temporary staging + cleanup, no new recovery state protocol. Old common-topling.sh is about 300 lines with unrelated downloader/jemalloc helpers: do not copy it wholesale. System dependency installation belongs in explicit deployment instructions, not automatic arbitrary downloads at launch.

Preload requirements: no startup downloads/extraction or business config parsing. Default standard should not require any Topling file/platform tool. Remove only this helper's previous active native path and automatic Easy Migrate default; preserve unrelated environment and explicit user override. Export automatic-config provenance to prevent parent/child PD→Store config leakage. Use each component's own conf/toplingdb.yaml by default. Selected CP is consumed by launchers; placing runtime outside lib removes the need to edit util.sh scanning.

## Owners and explicitly unfinished work

- Root: Docker/Compose/CI, product docs, baseline completion, PR assembly/publication and scope coordination.
- P3 agent: Server adapter/options/truncate/status and its tests. Helper interface agreed.
- This lane: launchers/shared shell/assembly/config and thin Commons + PD/Store runtime checks described above.
- NORMAL PD CLOSE HAS NO IMPLEMENTATION OWNER YET. Root explicitly confirmed this at model-switch pause. P2 agent said it remains on old P2 work and dependency analysis; do not assume it implemented tp-core PD close.
- Normal native lifecycle still requires evidence: stop background DB producers, finish Raft/snapshot work, close PD's actual metadata DB/options and prevent lazy reopening; Store's actual native owners must close. This lane has not run PD/Store live shutdown. Do not copy the old full HTTP/gRPC callback accounting, sticky-failure, failed-refresh or recovery machinery as a shortcut.

Strict directory marker ancestry, leaf/mount restrictions, dynamic/embedded/bare all-entry protection, central directory guards, dedicated Topling distribution generation, and interrupted recovery protocols are outside this clean core scope. Documentation must say supported distribution launchers, independent empty data directories and stopped runtime switching; it must not claim all-entry isolation or cross-provider in-place data compatibility.

## Old candidate and evidence boundaries

Old whole candidate remains `/Users/zhu/github/hugegraph-topling-split-provider`, separate from this clean master-based source. Do not copy it wholesale.
- Old P4 truncate/status native regression passed; root independently published those two files at e9a202a240becdf926c9c2531b35a046354f21c1. New clean Server adaptation belongs to P3 agent and still needs new-source validation.
- Old lane evidence: `E/resume-20261005/p4-review/work-report.md`, `owned-files-current.json`, java-001/002/003 logs, receipts and reports. Source snapshots may have been archived by root cleanup; receipts/manifests remain authoritative.
- java-003: Runtime 2, Dynamic 19, PD selector 1, Store selector 2 and bound guard 2 passed; real Server package built. Packaged fixture stopped on literal `/top/./conf` vs `/top/conf` equality, so subsequent shell/packaging gates were NOT completed. All that used old 36058-based candidate, not this new master source. No live or queued lane test remains.
- Thin auto-config payload under `E/resume-20261005/p4-review/auto-config-payload/` is based on e9a202a2, independent of the large selector/guard rewrite. It covers automatic CONF provenance, explicit preservation and safe ERR trap. Its actual candidate script passed parent/child component-selection tests using explicit JAR/ELF/platform substitutes, not real JNI. Root controls any old-PR publication; re-query current head/status before claiming it published.
- P3 independent review reports are under p4-review/p3-independent-review.md. Source008 fixed savedWal mutation ordering but introduced verified symlink/.. normalization mismatch. This is separate future P3 work, not a dependency to copy into TP core.

## Resume order

1. Read this checkpoint and root's current tp-scope/context; confirm export-ignore restoration/baseline manifest and other agents' ownership.
2. Inspect current four Java files against saved SHA before editing; keep concurrent changes.
3. Implement the small approved shared preparation/explicit-selection/launcher/assembly/config path. Coordinate HugePDServer startup with the eventual normal-close owner.
4. Add bounded relevant tests; request/coordinate the one shared heavy batch slot via E/run-exclusive.py. No build was started for this new source yet.
5. Validate actual standard defaults and all three components with the pinned real Topling JNI before any ready/merge claim. Root handles fresh-head publication and independent review.
