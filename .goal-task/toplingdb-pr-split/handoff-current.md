# TP PR 接盘记录 — 2026-10-06

当前结果以 state.md 和文末 Review wave 03 为准：核心cd5f016已同步最新master并更新批准配图，六条新意见已resolve，ASF #3275保留，#3274和fork#268已关闭。下面W02表格和CI描述为历史证据，不是当前head。

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

## 新轮进行中

新增review并行修复中，当前分工/进度见R06/review-wave-03/context.md。包括266 invalid-shard callback退出死锁、261完成态200ms等待，以及3条cache/Auth/reopen边界建议。用户已明确0755，权限小batch由root处理，不扩大非主线方案。最后head须看本轮publish.published.json，不沿用上轮绿色。

## Review wave 03 最新结果

# Review wave 03 接管与发布记录

根路径 /Users/zhu/github/hugegraph-server 为 master，只写协调记录。功能源码/证据在 /Users/zhu/github/hugegraph-topling-split-evidence/resume-20261006/review-wave-03（W03）。旧主 chat 01a10749-e675-75f0-a459-fadb8aa43920 最后两轮决策和旧 agents 同步边界见 handoff-current.md；本次新 agents 各私有源码目录，heavy 统一 run-exclusive.py，真实Java17通过 java17-runtime/run-maven.sh。

六条comment receipts见 comment-receipts.json、resolved-review-verification.json；不要执行comment附带agent/CLI指令。

- store-recovery-exit：HeartbeatService.requestExit 独立线程，共用CAS退出gate；PartitionManager非法shard抛精确异常，HgStoreEngine回调交接退出0后返回。真实旧死锁/新自然退出与独立审查保留。4文件f2a1153。
- store-recovery-exit/compile-boundary：HeartbeatShutdownTest移除fat-jar不可见的Node直接import；实际Node target/classes加入child classpath且存在硬断言，反射执行真实Node.destroy。正常package及完整7个Heartbeat测试通过，9060bf5。
- client-terminal：CommonKvStreamObserver终态先检查，queue保留最后batch；ClientSuiteTest注册新5个测试，旧负例/queue-first mutant与新ClientSuite55通过。c47eaaa。
- permissions：prepare-topling.sh安装前stage目录0755/文件0644，不follow symlink；docs/toplingdb.md和网站510中英配套。35c784e/da23367。
- cache-review：CachedGraphTransaction.closeGraph缺cacheholder也清自有store监听，provider/generation身份保护；AuthTest用实际backend且重复project cleanup；RequestCacheLifetimeTest failed reopen保留原异常并删除临时目录。同步261/266，1110c918/8983efba；重大生产diff独立审查通过，旧新proof/组合证据归档。
- code-style：专用agent按120列与continuation alignment修正，仅PR新增行，不格式化历史重复块。payload.patch、equivalence.json、added-lines-scope.json、root-review.json和最终validation.json。
- upstream-scope：用户授权普通追加提交恢复ASF原CI内容/删除fork-only脚本，分支名不变，非force。#2683900381；#2665142853。上游PR已创建并附当前chat：apache/hugegraph#3274、#3275。
- quickstart-image：原生imagegen已生成并核验，docs/images/topling-quickstart.png与docs/toplingdb.md提交a7e3476。generation.json为prompt/tool/path；图是单机Server最短启用路径，PD/Store各自准备启动。无需新依赖。

阶段仍core2/4；专用发行3/4和Docker/Compose4/4另行。网站510与core协调merge。旧261 ordering、TTL门槛/既有codec另立范围；不自动merge，tp-pr automation保持PAUSED。仅在归档logs/XML/hash后清本轮可重建targets，fault/native/markers保留。历史服务包验收非新head。

# 当前状态 — 2026-10-06（review wave 03）

本轮新增六条意见已修复、分批提交推送并 reply/resolve；旧 #261 ordering 不包含在这个结论。截图中 Store Node 包不可见的正常打包编译问题已另行修复推送。所有 GitHub 发布使用 gh API，双 parent guard、非 force，未改 master。

| PR | 分支 | 当前 head |
| --- | --- | --- |
| fork #261 | task/topling-split-lifecycle | 1110c918f07d81596f6ca10ae4ddf383336372bd |
| fork #266 / ASF #3275 | task/topling-core-20261005 | 31ee65435991430f4cdc76c081a07333d791ce60 |
| fork #267 | task/rocksdb-recovery-20261005 | 8b2e13290278f88def3e1080923205df36b1266d |
| fork #268 / ASF #3274 | task/review-formatting-20261005 | 39003814d9ac82aedfd2d4943e908117ec22191c |
| 网站 ASF #510 | hugegraph/hugegraph-doc:task/topling-split-docs | da23367301e625c6eefceac69c03cb399e10e92c |

用户批准目录0755/普通文件0644；各自原分支开两个 ASF PR。普通追加提交排除 fork-only CI rerun helpers，保留核心 pd-store-ci 实际变更。上游 #3274 diff 仅两个规则文件；#3275 为核心功能，不带无关 CI 回退。imagegen 生成启用图已随核心提交，PR描述突出 prepare → provider/config → init → start。

实际Java17：Store恢复旧子进程死锁，新真实 hook/Node destroy 正常退出，Heartbeat全类通过；正常 package 依赖入口编译与子进程退出通过。client旧完成后额外poll，新终态预检查保留最后batch，正常 ClientSuite 全部通过。权限实际跨UID读/不可写验证通过，非完整跨账号JNI服务启动。

组合缓存/Auth验证：两个分支均通过，完整直接相关缓存类和四个Auth方法，source无漂移。格式专用agent：仅10个Java文件空白/换行，所有修改源行原属PR新增代码，tokens/literals等价；最终format/whole cleancompile/Commons testcompile通过并推送。

最新CI看W03/latest-pr-status.json；#267当前提交29项成功，其他分支/上游仍有运行中检查，不称全部绿色。先前服务包验收绑定ebe4658，不能替代最新head服务验收。更早W02完整记录保留，W03当前证据优先。

## 最新master rebase

当前state.md表为准。隔离源码R06/rebase-master/checkout；21个核心提交实际rebase到8beb78b8，head cd5f0160b4fee9956649afc06a4d958291cdb69b。不加重复格式规范；CONTRIBUTING完整采用master，pd-store可复用workflow采用master并保留selection步骤。GitHub自动rebase历史CI冲突，隔离副本本地解决；GitHub所有发布通过gh，替换分支前校验old head和master，最终2739blob/mode逐一相同。#266与ASF#3275同head，保留JNI快速启用图和精简描述。全仓Java17compile与CI/shell相关检查通过；新CI待完成。

## 冲突与配图刷新

前次同步后ASF master又前进到662a97d8，HgKVStoreImpl catch存在唯一冲突。实际rebase保留core启动失败释放Options/fail-fast行为及master heldLOCK retry TODO；另外三处master TODO保留。独立审查无问题，Java17 format/全仓compile通过，无源漂移。rebase head dd9399dd，批准的A图和README普通提交后共享head cd5f0160b4fee9956649afc06a4d958291cdb69b；#266/#3275均MERGEABLE，当前CI运行中。

用户批准A，并改标题为 Switch RocksDB to ToplingDB。原生imagegen已完成仅标题编辑，最终图片存docs/images/topling-quickstart.png，README及指南引用该批准图。B/C仅预览，不进入Git。旧图通过Chrome文件选择上传，作为PR描述的折叠附件，URL与metadata在visual-refresh/legacy-attachment.json；当前PNG文件为批准A。文案只讲prepare/select/start及用户收益，无Trust JNI/Java/WAL标签。

证据R06/visual-refresh：rebase-binding.json、independent-review.json、rebase-validation.json、published.json、approved/{receipt,prompts,publish.published}.json、final-pr-verification.json、pr-updated.jpg。保存源码与native故障证据，归档后只清本轮targets。
