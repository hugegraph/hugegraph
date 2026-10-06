# TP 三 PR 接盘记录 — 2026-10-06

已确认的修复全部分批 commit 推送，对应 review 意见已回复并 resolve；保留 261 旧 ordering 争议，不自动合并。最后两项新增测试 fixture 修复也已推。

## 最后两轮决策与接管

旧主 chat `01a10749-e675-75f0-a459-fadb8aa43920` 最后两轮先做新增评论核查并暂停 tp-pr 自动化，再要求实际 Java17 验证、修复、推送并回复意见。旧主因 ModelTrace guard 停止，部分修复留在独立源码目录。当前 chat `01a10dc0-9a54-7463-9890-ed318c763f08` 收拢结果并发布。

旧 P2/P4 已暂停，P3 已完成；跨树 direct message 受 multi-agent v2 限制，旧主 guard 也未转达。已在 P2 context/P4 handoff 写共享 notice，P4 最终报告识别新主发布。不得称 agent 树已直接迁移。本轮新的修复/审查 agents 按各私有目录工作，结果统一收拢到 W。

## 运行路径与分支

根 `/Users/zhu/github/hugegraph-server` 为 master，仅更新协调记录；功能源码在独立 archive/source。通过 gh Git Data API 发布，双重 parent guard、非 force 更新、发布后 blob/mode 核验，不直接推 master。

E=/Users/zhu/github/hugegraph-topling-split-evidence
R06=E/resume-20261006
T=R06/takeover-20261006
W=R06/review-wave-02

| PR | 分支 | 最新 head | 当前冻结/绑定源码 |
| --- | --- | --- | --- |
| 261 生命周期 | task/topling-split-lifecycle | b7035f8ed24e1c76c04e972890a88aece3d23ad9 | W/query-cancel-fix/current/source |
| 266 TP core (2/4) | task/topling-core-20261005 | ddbe2c30e19cdcdf82c365b97505dc6258b19f11 | W/security-fixture/candidate |
| 267 恢复 | task/rocksdb-recovery-20261005 | 8b2e13290278f88def3e1080923205df36b1266d | W/recovery-alias/source |
| 网站510 | hugegraph/hugegraph-doc:task/topling-split-docs | 713667a26f2fd926b321bc7a3b521fde6924eed5 | T/standard-preload-fix/website |

更早完整路径、产品运行链和拆分背景见 T/handoff.md；旧 268 规范与 264 拆分源未改。本地重型命令统一 `python3 E/run-exclusive.py ...`，实际 Java17 wrapper 必须 `bash R06/java17-runtime/run-maven.sh SOURCE ...`，不并发 heavy/shared m2。

## 当前修复与验证

- 261：request/task transaction 与 scan/query 清理；half-close 后保留已有 credit，transport cancel/deadline 独立幂等，DONE 后新 query 在 iterator 分配前拒绝。最终 session detach 释放自有 WriteBatch/WriteOptions；图级 schema/element cache 和失效监听保留到 graph.close。
- 266：可信 Topling Java/native pair 的配置实效探针及 checksum receipt；两种 provider 模式竞争 JNI 拒绝；OLAP owning DB 路由、受控 CodeSource 错误、heartbeat fatal 独立退出协调、metrics session 正常/异常归还。同步必要 request/cache/native 生命周期修复。
- 267：物理 data/WAL 身份、危险链接与未知 marker 拒绝、同 Store 失败后重开；pending metadata 同目录 staged-write/force/hard-link 无覆盖发布；checkpoint 通过 parent bind alias 的物理同/内/含重叠，在 marker 发布、install/owned cleanup 前拒绝。root 权限 fixture 根据实际 exists/notExists 双 false 判定故障，finally 恢复 mode。
- 原始负对照证明 native owner 未释放、合法 schema append 更新失败、部分 marker 写失败后无法重开、物理 alias 删除 live/source。新定向回归通过，重大生产 diff 均经独立审查，源冻结/发布 binding 明确。
- 261 57 cache/native/OLAP + 3 Auth 通过，零 skip；完整 77 query/scan 最初有单例超时，未改源单例回放通过。有效 ThreadMXBean/jcmd 证实 InProcess directExecutor fixture 提前 teardown 引发 responseLock/ClientStream 锁环。只在 normal fixture 等真实 sender.onCompleted 返回，未改生产/增大 timeout；受控旧例死锁、新例通过，最小原方法及完整 77 复验通过，已先推 b7035f8。原 failure/dump/A-B 保留。
- 266 a8dd 全源2732绑定、format/whole clean compile、相关70回归通过；编译后的正常 UnitTestSuite 注册新缓存测试。CI 后发现 Java17 upgrade mock fixture 漏复制 preload，唯一 test-java17-upgrade-contracts.sh 补资产校验和复制，已推 ddbe2c。旧源码复现 CI exact missing-asset 错误；完整 security/upgrade/foreground SIGINT/bash syntax 在 Java17 通过。
- 267 8b2e132 全源2724绑定、format/whole clean compile、两完整 native 类76方法无skip、MultiGraphs仅2个既有HStore-only skip通过，真实双 parent bind 和 uid501 权限断言执行，全源零漂移，fresh gh head相同。

验证是 Darwin arm64 上模拟 Linux amd64，标准 JNI 的实际本地证据；焦点测试不代表完整 unit/core、生产或分布式验收。T 中完整 HStore 新包 REST/Gremlin、正常 stop/restart 持久化/索引读回与再次正常 stop 通过，但绑定 ebe4658，不能转称当前新 Java head 服务包验收。security shell fixture 沿用该历史 Java/libs 包并覆盖 a8dd 全部 bin/conf，明确仅证明 shell fixture。

## 关键修改文件

- `hugegraph-server/hugegraph-rocksdb/src/main/java/org/apache/hugegraph/backend/store/rocksdb/RocksDBStdSessions.java`：最后 native owner 释放。
- `hugegraph-server/hugegraph-core/src/main/java/org/apache/hugegraph/StandardHugeGraph.java` 与 `backend/cache/{CacheListenerHolder,CachedGraphTransaction,CachedSchemaTransaction}.java`：graph cache/listener 范围。
- `hugegraph-server/hugegraph-test/src/main/java/org/apache/hugegraph/unit/cache/RequestCacheLifetimeTest.java`、`UnitTestSuite.java`、既有两个 cache 测试：schema append/API 路径/失效与关闭重开。
- `hugegraph-server/hugegraph-test/src/main/java/org/apache/hugegraph/core/AuthTest.java`、`backend/tx/GraphTransactionTest.java`：实际 backend guard 与合法 OLAP sentinel ID/异常来源。
- `hugegraph-server/hugegraph-rocksdb/src/main/java/org/apache/hugegraph/backend/store/rocksdb/RocksDBSnapshotRestore.java` 与对应测试：marker 发布、物理 checkpoint overlap。
- `hugegraph-store/hg-store-test/src/main/java/org/apache/hugegraph/store/node/grpc/query/AggregativeQueryShutdownTest.java`：实际 terminal 条件等待，早 cancel/no-cancel 断言保留。
- `hugegraph-server/hugegraph-dist/src/assembly/travis/test-java17-upgrade-contracts.sh`：复制 preload 依赖。
- 指南：261 docs/transaction-lifecycle.md；266 docs/toplingdb.md；267 docs/rocksdb-recovery.md；网站510中英同步。

## 证据与剩余工作

发布/意见/描述：W 下各 lane 的 publish.published.json、comment-receipts.json、description-receipts.json；261/266 最新测试 fixture 为 query-cancel-fix/security-fixture。冻结正负例、失败现场及所有 source/hash manifest 保留。

T 第一轮证据保留：build-receipt.json、package-remote-binding.json、api-regression/runs/takeover-ebe4658-hstore-001/receipt.json、p261-final-002/receipt.json、recovery-published-binding.json；原P4 R06/p267-comments-evidence/validation/runs/a021-candidate-002/receipt.json。

- 261/267 曾对当时 head 的远端 CI 全成功；261 PD Docker Maven Central502重跑成功。最后 261 b703/266 ddbe 测试 fixture 新提交 CI 需重新核查，不能沿用旧 head 绿色。266 a8dd 失败只在 mock preload 资产缺失，compile/package/Docker/auth-on smoke通过，后续unit/core等steps skipped；新head不得预先宣称通过。
- 261 旧 ordering 争议已回复未 resolve；TTL严格 shutdown-overlap gate 未完成，旧 Server/Struct codec 问题另立范围。原证据 R05/p2-runtime-current/harness-80dd/final-runtime-summary。
- core266阶段2/4后是专用发行包3/4、Docker/Compose4/4，尚未建PR；正式JNI发布/许可链按原约定后置。261/267/268均基于master；任一合入后其余同步master并复验，不恢复264整包路线。网站510与core协调merge，不自动合并。
- tp-pr自动化仍PAUSED。本轮未新建定时任务。
- 仅清明确本轮、已完成且可重建的target/临时JAR，先留日志/XML/hash。故障/native/marker与原包保留；cleanup receipts 在各lane，root completed-target-cleanup另留审计。不清未知历史数据。
