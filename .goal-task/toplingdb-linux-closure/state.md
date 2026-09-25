# ToplingDB Linux 实测执行合同

## 基线与环境

- 仓库 `hugegraph/hugegraph`；fetch/push 远端 `org`；唯一分支 `toplingdb`；PR #179。不新建分支或 PR，不 force-push，不直接合入 master。
- 执行工作树：`/home/soc-baidu/.codex/worktrees/f29e/hugegraph`。本地分支 `codex/toplingdb-linux-validation`，推送目标 `org/toplingdb`。主 checkout `/home/soc-baidu/github/hugegraph` 停在较旧的 `f9829899c`，不要在那里继续。
- 2026-09-25 刷新：`git fetch org toplingdb` 后，推送前 fetch 发现 `org/toplingdb` 前进到 `a35ebeb17`，包含 Store 空地址重入、follower partitions GET 和 PD task/balanceLeaders 异常体修复。本地文档提交已 rebase 到其上。镜像和功能证据仍绑定源码 `e109012a07e2e9918f4a98d3faa23e21b93435d1`，不是这些新提交。
- 工作区另有未提交的 channel refresh：`AbstractGrpcClient.java`、`KvPageScanner.java`、`KvBatchScanner.java`、`KvBatchScanner5.java`、`GrpcStoreStreamClient.java`、`query/CommonKvStreamObserver.java`、`query/QueryExecutor.java`，以及未跟踪的 `AbstractGrpcClientChannelRefreshTest.java`。3 名独立审查通过且没有未解决的高严重度问题之前，禁止提交或推送这些文件。不要把它们和文档提交混在一起。
- 工作区另有未提交的 WAL 失败路径修复：`RocksDBStdSessions.java` 和 `RocksDBSessionsTest.java`。`RocksDBSessionsTest` 20 个通过、0 失败、0 跳过。最新差异没有新的三份审查，禁止提交或推送，也不要和 channel refresh 或文档混在同一个提交里。
- 不要提交 `evidence/`、`.codex-handoff/`、RocksDB 数据、`tmp/` 或 `cacerts.jks`。
- `e109012a0` 已包含 gRPC 沙箱白名单、嵌套 WAL 恢复和三份 Topling profile 的 `memtable_as_log_index: false`。`closure-top-mmapfix` 仍是旧的脏工作区镜像，不能代表这个 SHA。
- Kubernetes 只用 `KUBECONFIG=/home/soc-baidu/.kube/config` 和 context `kind-kind`。不用 k3s，不清理无关资源。只有 snapshot/restore 或网络分区门禁需要时，才安装对应 CRD，并且不能改动两个历史 namespace。
- 历史 namespace `hg-closure-standard-111`、`hg-closure-standard-333` 在 2026-09-24 仍为 Running，镜像是 `closure-std-9abae9dbaaa1`。它们不是当前 SHA 的通过证据，禁止原位升级。

## 活动真相与优先级

最新用户确认 > [AGENTS.md](../../AGENTS.md) 与产品设计 > [todo.md](todo.md) > 本文件。

- 本文件是唯一执行合同和恢复入口。
- [todo.md](todo.md) 独占分项状态、等待和后置标记。
- 同目录的 `local-status.md`、`evidence-index.md` 和 `goal.md` 都不是活动真相。
- 不创建 `design.md`。只有出现可复用证据时才更新 `lessons.md`。
- 原始证据留在本地 `evidence/`。不得提交 `evidence/`、`.codex-handoff/`、RocksDB 数据、`tmp/`、`cacerts.jks`、镜像或 benchmark 原始大文件。

## 用户确认的本机范围

本机持续完成 todo.md 的全部 Linux 实测。每项最终只能是绑定证据的通过、失败，或写明解除条件的后置。允许修复可复现的阻塞缺陷并推送；行为修复必须有回归测试。另一台机器可以在同一分支提交代码。每次提交或推送前先 `git fetch org toplingdb`；远端前进时整合双方改动，禁止 force-push 或丢弃另一侧提交。`state.md` 与 `todo.md` 冲突时保留双方证据，再按本地证据改写当前状态。

2026-09-24 用户再次确认：继续用本机 goal 做完 todo.md 的全部 Linux 实测，复用本目录和 `toplingdb`，不新开分支或 PR。状态文档在阶段边界和会话结束前推送，不为每一条笔记单独推送。可复现阻塞缺陷可以先审查、提交并推送。本机可以修改代码。

2026-09-25 用户确认：继续本机 goal，复用本目录、当前工作树、`org/toplingdb` 和 PR #179。 同日再次确认不新开 goal-task 目录；本机 goal 从当前 `state.md` 与 `todo.md` 继续。本机记录并持续完成 todo.md 里全部 Linux 实测；阶段边界和会话结束前更新并推送 `state.md` 与 `todo.md`。明显且可复现的阻塞缺陷可以直接修复、补回归测试，审查通过后提交并推送。这不是只读限制，但不是授权实现新的跨分区图快照协议。

2026-09-25 用户确认生成 goal 并立即复用本目录：本机持续完成 todo.md 的全部 Linux 实测，阶段边界和会话结束前更新并推送 `state.md` 与 `todo.md`。明显且可复现的阻塞缺陷可以直接修复、补回归测试，审查通过后与文档分开提交并推送。不新开分支、PR 或 goal-task 目录，不 force-push。

## 阶段

| 阶段 | 状态 | 依赖 |
| --- | --- | --- |
| P0 合同与修复 | HEAD `a7a4a6f1b` 等于 `org/toplingdb`，只在 `e109012a0` 上增加文档。channel refresh 未提交；审查前先判断另外三处 `onError` 会不会留下旧 Store channel | 3 名独立审查前不提交 Java，也不构建脏工作区完整镜像 |
| P1 历史集群 | `9aba` 有 Pod 级证据；snapshot 失败一次；网络分区后置 | 不阻塞当前 SHA |
| P2 当前 SHA 镜像与 JNI | 两个 provider 的 e109 单机镜像和 JNI 已证明。HStore server 两个 tag 是同一镜像。e109 的 1+1+1 与 3+3+3 namespace 已存在 | 不升级 `9aba`、cc143 或 mmapfix namespace |
| P3 当前 SHA 功能 | 四项已勾选。未修改 Server 现已覆盖批量、索引、Gremlin/Cypher、多图、口令认证、边更新删除和角色 403 | 不重复已通过项 |
| P4 生命周期与 provider | 混合 provider 和错误 provider 已勾选。生命周期总项未勾选：e109 崩溃、删图和 truncate 已有证据，HStore `snapshot_create` 仍是 500 | 不新做跨分区图快照，不重复已有 kill -9 |
| P5 当前 SHA HA | 三项都未勾选。单节点进程或 Pod 恢复不能代替网络分区。Compose 与 Helm 差异已记录且故意不改 | 不把 kind 写成物理多机，不顺手改 HA 配置 |
| P6 Loader | 固定子集导入已勾选。完整 e109 Server 在 Store IP 变化后仍连接旧地址，邻接复测未通过。全量 LAW 后置 | 先完成 channel refresh 审查，再用该提交的完整镜像复测 |
| P7 Benchmark | 未开始 | 核心功能未收口前禁止性能结论 |

分项计数和完成标记以 todo.md 为准。当前 SHA 测试使用新 namespace。同一时间只运行一个重任务；Maven 全量、镜像构建、Helm 变更和故障注入不叠加。

## 已核实的镜像事实

标准构建单元 `hg-closure-std-image-cc14333f0-r2` 已结束：`ActiveState=inactive`、`Result=success`、`ExecMainStatus=0`。同名第一单元是 failed，不能当作成功结果。日志是 `evidence/build/current-sha-standard-image-build-r2.log`。

| 镜像 | ID | 结论 |
| --- | --- | --- |
| `hugegraph/hugegraph:closure-std-cc14333f0` | `35267423defa` | 2026-09-24 创建，revision `cc14333f0`，rootfs 与 `9aba` 在第 12 层开始不同 |
| `hugegraph/server:closure-std-cc14333f0` | `14eb8067b416` | 2026-09-24 创建，revision `cc14333f0`，第 8 层开始不同 |
| `hugegraph/store:closure-std-cc14333f0` | `2ed96d2c4bd4` | 2026-09-24 创建，revision `cc14333f0`，第 9 层开始不同 |
| `hugegraph/pd:closure-std-cc14333f0` | `a0d9264b2ea2` | 已接受为 cc143 产物。10 层 rootfs 与 `9aba` 相同是因为 cc143 Maven 产出的 PD 树校验和命中原层；运行清单 14 个文件一致，服务 JAR `5fa7c6299ad283fb369cd03c78f5b3970015f069b8eea8dbc9cdf0481b062da5`。证据 `evidence/build/pd-image-acceptance-cc14333f0.json` |

两个构建上下文的 PD 源码只有测试 README、`.gitignore` 和 `PDCoreTestBase` 数据路径不同；构建使用 `-Dmaven.test.skip=true`，这些差异不进入运行包。因此 PD 镜像可以与另外三张 `closure-std-cc14333f0` 镜像一起部署。禁止把 2026-09-01 的 `hugegraph/*:topling` 用作当前 SHA。

Topling 构建上下文必须包含已存在的 `hugegraph-server/hugegraph-dist/src/assembly/static/lib/topling/rocksdbjni-8.10.2-20260725.141011-1.jar` 和 `install-dist/scripts/build-topling-distribution.sh`。`.gitattributes` 的 `export-ignore` 会排除 `docker/` 与 `install-dist/scripts/`，不能直接用裸 `git archive` 做上下文。

## 执行规则

- 确认范围内用户可授权的操作均已预授权，包括本地改动、Git、fetch、非强制推送、PR 更新、测试和审查响应。不得因授权提示再次询问、等待、后置或把整体标为 blocked。不得伪造凭据或能力，不得越过安全边界，不得做范围外操作，不得记录凭据值。
- 每一波绑定源码 SHA、镜像 ID、revision、实际 JNI、namespace 或 Compose project、命令、退出码、计数、skip 和未覆盖边界。
- 单项最多尝试 3 次。仍失败时记录错误、证据、恢复动作和依赖，在 todo.md 后置该项及其依赖，继续独立项。阶段边界或解除条件变化时复查。
- 等待构建、下载或集群时，只做不冲突的只读准备、失败分析和文档维护。
- 文档由 1 名独立只读审查者复核。行为变更由 3 名独立只读审查者复核；大变更分别覆盖正确性与测试、设计与边界、安全与可维护性，小变更三人各自审完整差异。修复后重审受影响部分，最多 3 轮。审查者不可用时保留门禁并完成后继独立工作，不把整体标为 blocked。
- 验证和适用审查通过后提交。阻塞代码修复审查通过后立即推送到 `org/toplingdb`。状态文档在阶段边界和会话结束前推送，不为每一条笔记单独推送。用户明确要求这些推送，覆盖“默认不推送”。远端拒绝时保留本地提交并继续不冲突的实测。
- Store shutdown 保持 fail-closed，不为超时测试强行关库。阻塞修复不做无关重构。
- 每次有效循环按门禁报告粗略进度、本轮结果、剩余工作和一个下一动作。交接、压缩或配额等待前更新本文件的阶段、最新提交、证据、等待项和下一动作。
- 只有全部剩余项经过恢复、重排和独立工作后仍共同依赖同一个逻辑冲突、安全边界或强制性外部依赖时，才把整体标为 blocked。

## 已知边界

- 单节点 kind 只能证明逻辑恢复、Pod 恢复和持久化，不能证明物理多机或真实网络分区容错。
- 2026-09-24 检查时没有 Chaos Mesh 与 VolumeSnapshot CRD。Pod 级 HA 先做。snapshot/restore 和网络分区仍是完成门禁；单节点 kind 不能被写成物理多机容错。
- macOS ARM/Intel Cypher、发行审批和公共仓库发布不由本机完成。
- Loader 输入目录是 `/home/soc-baidu/.codex/validation-data/toplingdb-linux-closure/derived/twitter-prefix-1000000`。导入前核对 manifest 与 checksum；文件缺失时后置，不把全量 LAW 下载当作默认补救。

## Initialization TODO

无未完成的一次性初始化。嵌套 WAL 修复已在 `457295ac8`，并包含于已推送的 `a7a4a6f1b`。2026-09-25 核对 `hg-closure-top-image-e109012a0` 为 `inactive/success`，不要因为旧记录重新启动它。

## 下一动作

1. 文档重审 `evidence/build/doc-rereview-1.md` 是 HIGH_SEVERITY=0。只提交 `state.md` 和 `todo.md`，不提交 Java、WAL 修复或 channel refresh。
2. WAL 最新源码仍没有新的三份审查，不要自动开第四轮，也不要提交。进程 `1322802` 如果还在重连，让它结束，不采用它的结论。
3. 已完成的 kill、SIGTERM、邻接、边 CRUD 和角色 403 不重跑。HStore snapshot、网络分区、全量 LAW、benchmark 和 HA 配置对齐仍后置。

## 进展日志

本节按时间保留旧记录，后文覆盖前文。当前状态以阶段表和 todo.md 为准。

PD 标准镜像已按校验和接受。Topling 构建单元 `hg-closure-top-image-cc14333f0` 已成功退出。PD `3bce8e03d227`、Store `195cad38c8a7`、standalone `52be45a93334` 的 runtime 标签是 topling，并且包含 Topling JNI；标准 PD/Store/standalone 不含该 `.so`。`hugegraph/server:closure-top-cc14333f0` 与标准 HStore server `14eb8067b416` 是同一镜像，因为 `Dockerfile-hstore` 没有 Topling 阶段，镜像内也没有 Topling `.so`。运行中的 JNI 映射尚未证明。标准 1+1+1 已部署在 `hg-closure-std-cc143-111`，Helm 退出 0，三个 Pod Ready。PD 与 Store 映射的 JNI 都是 `8b8fb2ed3ab69581cf1897bd116d484f073e66e9a5b6d61effc7b4c783d66dff`，不等于 Topling 镜像内 `.so` 的 `c25ff6e676290db6db47df0954640aa609c391450ec90e1a8eec1f87e174dd38`。Server 没有映射 RocksDB/Topling。Store 启动时 GitHub jemalloc 下载停在 0 字节，终止 curl 后走了脚本原有的跳过路径。证据 `evidence/helm-standard-cc143-111-runtime.json`。标准 1+1+1 API 套件已通过：155 通过、0 失败、50 跳过，日志 `evidence/build/helm-standard-cc143-111-api-r4.log`。Topling 1+1+1 已 Ready。PD 与 Store 都映射 `/library/librocksdbjni-linux64.so`，SHA-256 `c25ff6e676290db6db47df0954640aa609c391450ec90e1a8eec1f87e174dd38`，与标准进程的 `8b8fb2ed…` 不同。证据 `evidence/helm-topling-cc143-111-jni.json`。Topling 1+1+1 API 套件被停掉。删 schema 任务 182 已失败，测试却无休眠地等待 success。Store 批量写入报错 `memtable_as_log_index is true but WriteBatch has no mmap wal`，共 216 次。三个 Topling profile 已改为 false，单测 `ToplingProfileConfigTest` 通过。`waitTaskSuccess` 现在遇到 failed 或 cancelled 会立刻失败，避免无休眠空转。Store `6581fdf5fdbb` 与 PD `8dbbc94de64d` 已导出，runtime 都是 topling，revision `cc14333f0-memtable-as-log-index-false`，包内 `memtable_as_log_index: false`。standalone `5178c8b35c20` 也已导出且配置为 false。三张镜像构建成功。`hg-closure-top-mmapfix-111` Helm 退出 0，三个 Pod Ready。Store JNI `c25ff6e6…dd38`，provider=topling，API 前后 mmap WAL 错误 0。API 套件 155/0/0/50 通过，日志 `evidence/build/helm-topling-mmapfix-111-api.log`。跟随单元 `hg-closure-top-mmapfix-follow` 会在构建成功后安装新 namespace，构建失败则不安装。tag `closure-top-mmapfix`。1+1+1 修复验证已完成。Topling 3+3+3 `hg-closure-top-mmapfix-333` 已 9/9 Ready，Helm 退出 0。三台 Store JNI 都是 `c25ff6e6…dd38`，mmap WAL 错误 0。API 155/0/0/50。三台 Server 读到同一顶点。随后删除 leader Store-2，51.084 秒恢复，旧数据和新写入保持可读。这是单节点 kind 的 Pod 删除，不是物理分区。Server 副本删除期间保留副本继续读写，10.7 秒恢复。多数派删除 Store-0/2 后已提交数据仍可读，故障中新写入超时；Ready 不等于写恢复，稍后合计 12 个 leader 才能写入。证据 `evidence/helm-topling-mmapfix-333-store-majority.json`。仍不复用 `hg-closure-top-cc143-111` 的数据卷。证据 `evidence/topling-mmap-wal-batch-failure.json`。


2026-09-24 23:15 补充：Topling `hg-closure-top-mmapfix-111` 的 Store Pod 在同一 PVC 上重建，12.615 秒 Ready，重启前顶点 `persist-1790262903` 仍返回。jemalloc 下载再次被终止。这还不是删图、truncate 或 snapshot。


2026-09-24 23:17 补充：标准 `hg-closure-std-cc143-111` Store 同 PVC 重建 12.796 秒，顶点 `std-persist-1790263000` 仍在，JNI 仍是标准哈希 `8b8fb2ed…6dff`。


2026-09-24 23:21 补充：当前 SHA 标准与 Topling 1+1+1 的删图重建和 clear/truncate 已通过。snapshot_create 为 500 UnsupportedOperationException，本机无 VolumeSnapshot CRD。

2026-09-24 23:40 Loader：在 `hg-closure-top-mmapfix-111` 新建 `law_twitter_1m`。Loader 1.7.0 退出 0，顶点插入 1000000、边插入 2098771、失败和重试都是 0。重启前 32 个样本的度数、字典序前 20 和 compact_id 全部匹配，9 个自环存在。JNI 仍是 `c25ff6e6…dd38`，mmap WAL 错误 0。Store 同 PVC `b9f47929` 从 `15:38:34Z` 到 Ready `15:40:55Z`，含 0 字节 jemalloc 下载被终止后走脚本原有跳过路径。重启后除 `54148543` IN 扫描 UNAVAILABLE 外，其余样本复测通过。Gremlin count 被 SecurityException 拒绝，没有服务器端总数。

2026-09-25 00:02：`54148543` 在 Store Ready 约 11 分钟后，IN limit=142 返回 200，142 条且前 20 个 ID 与预期一致，证据 `evidence/loader-law-twitter-1m-in142-settled.json`。紧接着的混合扫描有 5 次和 6 次 `INTERNAL: Panic! This is a bug!`，50ms 间隔仍有 1 次；同一顶点重复 40 或 60 次没有复现。之后 1 轮加 4 轮混合扫描全部 200，证据 `evidence/loader-scan-panic-sweeps.json`。该字符串不在 Topling JNI 二进制和 Store 日志里。

2026-09-25 00:00：标准 3+3+3 `hg-closure-std-cc143-333` Helm 退出 0。三台 Store 的 0 字节 jemalloc 下载被终止后走脚本跳过路径，9/9 Ready。PD 与 Store 映射的 JNI 都是标准哈希 `8b8fb2ed…6dff`，不是 Topling。API 套件已开始，日志 `evidence/build/helm-standard-cc143-333-api.log`。

2026-09-25 00:05：标准 3+3+3 `ApiTestSuite` 155/0/0/50，`API_EXIT 0`，日志 `evidence/build/helm-standard-cc143-333-api.log`。图 `std333_fn` 在三台 Server 上都读到同一顶点，证据 `evidence/helm-standard-cc143-333-write-consistency.json`。删除 leaderCount=5 的 Store-1 后 22.550 秒 Ready，PVC `pvc-dcd9b334` 不变，JNI 仍是 `8b8fb2ed…6dff`，旧顶点和恢复后新写入可读；恢复后 leader 合计 12，但新 Store-1 为 0。证据 `evidence/helm-standard-cc143-333-store-leader.json`。删除一台 Server 后保留副本继续读写，替代副本 10.613 秒 Ready 并读到故障期间写入。证据 `evidence/helm-standard-cc143-333-server-replica.json`。这是单节点 kind 的 Pod 删除。

2026-09-25 00:12：标准 3+3+3 同时删除 Store-0 和 Store-2，保留当时 leaderCount 为 0 的 Store-1。已提交顶点在故障期间仍返回 200；故障期间新写入 12 秒超时。两台 Pod 在 23.102 秒 Ready，PVC 未变，当时 leader 合计已是 12，但立即写入仍超时。删除后 35.929 秒新写入返回 201，旧顶点仍在，超时的那次写入没有留下。证据 `evidence/helm-standard-cc143-333-store-majority.json`。Ready 不等于写恢复。这不是网络分区。

2026-09-25 00:16：当前 SHA 单机已启动。标准镜像 `35267423defa` 的 JNI 是 `8b8fb2ed…6dff`，provider=rocksdb；`std-persist-1790266399` 在 docker restart 后 9.426 秒可读。Topling 镜像 `5178c8b35c20` 映射 `/hugegraph-server/library/librocksdbjni-linux64.so`，哈希 `c25ff6e6…dd38`，`memtable_as_log_index: false`，mmap WAL 错误 0，`top-alone-1790266203` 重启后仍在。标准单机 API 套件 13 个失败全部来自 `GraphSpace management is not supported in standalone mode`。证据 `evidence/standalone-cc143-function.json`、`evidence/standalone-std-cc143-restart-clean.json`、`evidence/standalone-cc143-restart.json`。

2026-09-25 00:20：当前 SHA 认证。标准 3+3+3 与 Topling 1+1+1 的图列表：admin 200，错误口令、缺认证、未知用户、已删除用户都是 401。两个单机同样如此。`/versions` 返回 200，不能当作认证边界。两个集群的 DEFAULT 图空间 `auth=false`。Topling 上无角色用户建图 201，观察者角色也能建图，随后由 admin 删除，用户也已删除。标准集群那次无角色建图是 Store 500，不是 403。角色拒绝仍未验证。证据 `evidence/current-sha-auth-enforcement.json` 与 `evidence/current-sha-auth-standalone.json`。

2026-09-25 00:22：标准和 Topling 都在非主键属性 `title` 上创建二级索引，`limit=2` 返回 2 条。标准按 title 查询只返回目标顶点。Topling 第一次返回 `INTERNAL: Panic! This is a bug!`，紧接着 5 次都是 200 且只命中目标。证据 `evidence/current-sha-index-query.json` 与 `evidence/current-sha-topling-index-panic-retry.json`。

2026-09-25 00:25：标准和 Topling 各用两张已有图做隔离。顶点只出现在写入图，另一张图的同名查询没有该顶点。Gremlin `hasLabel().limit(1)` 和图查询都返回该顶点；Gremlin `addV` 写入后 REST 也能读到。Cypher `MATCH` 在两个 provider 上都返回同一顶点。证据 `evidence/current-sha-multigraph-query.json`、`evidence/current-sha-cypher.json`、`evidence/current-sha-gremlin-write.json`。

2026-09-25 00:28：标准和 Topling 的批量接口各写入 3 个顶点，全部可读。同一批次再放入一个未定义属性时返回 400，批次中合法顶点没有留下，之前的 3 个仍在。证据 `evidence/current-sha-batch-tx.json`。`snapshot_create` 在标准 `std333_fn` 和 Topling `hugegraph` 上都是 500 `createSnapshot`。

2026-09-25 00:30：混合 1+1+1 `hg-closure-mix-pdstd-storetop`，PD 镜像 `closure-std-cc14333f0`，Store 镜像 `closure-top-mmapfix`。PD JNI `8b8fb2ed…6dff`，Store JNI `c25ff6e6…dd38`。图 `mixprobe` 写入 `mix-1790267151` 后返回 200。Store 启动时 0 字节 jemalloc 下载被终止后走原有跳过路径。证据 `evidence/current-sha-mixed-pd-std-store-top.json`。

2026-09-25 00:32：反向混合 `hg-closure-mix-pdtop-storestd`。PD `closure-top-mmapfix` 映射 `/hugegraph-pd/library/librocksdbjni-linux64.so`，哈希 `c25ff6e6…dd38`。Store `closure-std-cc14333f0` 映射 `/tmp/librocksdbjni13856924616744549206.so`，哈希 `8b8fb2ed…6dff`。图 `revprobe` 写入 `rev-1790267261` 后返回 200。标准 Store 的 0 字节 jemalloc 下载被终止后走原有跳过路径。证据 `evidence/current-sha-mixed-pd-top-store-std.json`。

2026-09-25 00:36：错误 provider。Topling 镜像以 `HG_SERVER_ROCKSDB_PROVIDER=topling` 打开 `provider=rocksdb` 标记，退出 1，`provider marker mismatch`。标准镜像打开 `provider=topling` 标记同样退出 1。标记 SHA-256 不变。原单机顶点仍返回 200。证据 `evidence/current-sha-wrong-provider.json`。反向混合的标准 Store Java 被 `kill -9` 后容器 restartCount=1，`rev-1790267261` 在 Ready 变为 true 之前已经返回 200，PVC `pvc-5d209bd7-d94b-4fdf-9ef4-de284929ac86` 未变。0 字节 jemalloc 下载再次被终止。证据 `evidence/current-sha-store-crash.json`。

2026-09-25 00:40：Topling Store `hg-closure-top-mmapfix-111-hugegraph-store-0` 的 Java 被 `kill -9`。restartCount=1，PVC `pvc-b9f47929` 未变。新进程从 `16:34:18Z` 到 Ready `16:34:55Z`，37 秒，含 0 字节 jemalloc 下载被终止。Ready 之前 `persist-1790262903` 和 Twitter 顶点 `56862681` 已返回 200。JNI 仍是 `/hugegraph-store/library/librocksdbjni-linux64.so` 的 `c25ff6e6…dd38`。证据 `evidence/current-sha-topling-store-crash.json`。

2026-09-25 00:42：HA Compose 与 Helm 对照写在 `evidence/ha-compose-helm-alignment.md`。运行中的标准 3+3+3 确认 PD readiness 是 `/v1/ready`、liveness 是 `/v1/health`，Store 三个探针都是 `/v1/health`，Server 三个探针都是 `/versions`。两边没有改成同一个配置。

2026-09-25 00:45：Topling `law_twitter_1m` 在 Store `kill -9` 恢复后重跑 32 个邻接样本。三轮 64 次查询的失败数是 4、2、0，失败都是 `INTERNAL: Panic! This is a bug!`。四个失败点立刻重试都是 200，其中 `54148543` IN 返回 142 条。证据 `evidence/current-sha-panic-recheck.json`、`evidence/current-sha-panic-recheck-retry.json`。

2026-09-25 00:50：单机 snapshot。标准容器 `snapshot_create` 和 `snapshot_resume` 都是 200，日志显示删除并替换了 `rocksdb-data/data/{s,g,m}`。快照后写入的 `snap-after-1790268023` 在容器重启后仍返回 200，快照前的顶点也在。Topling 单机同样：`top-snap-before-1790268142` 和 `top-snap-after-1790268142` 在恢复并重启后都返回 200。证据 `evidence/standalone-std-snapshot.json`、`evidence/standalone-std-snapshot-after-restart.json`、`evidence/standalone-top-snapshot.json`。恢复实现只搬迁 data 目录，`rocksdb.wal_path` 是旁边的 wal 目录。HStore 仍走 `BackendStore.createSnapshot` 的默认 `UnsupportedOperationException`。

2026-09-25 00:55：单机恢复失败的原因是独立 WAL。`RocksDBStdSessions.resumeSnapshot` 只替换 data 目录，`rocksdb.wal_path` 里快照之后的日志会在重新打开时重放。本地修复会在 WAL 目录和 data 目录互不包含时删除 WAL 再打开。回归 `RocksDBSessionsTest.testSnapshotWithSeparateWalDirectory` 与原 `testSnapshot` 均为 0 失败。修复未审查、未提交，运行中的镜像没有重建。

2026-09-25 01:00：把包含 WAL 删除的 `hugegraph-rocksdb-1.7.0.jar` 拷进两个单机容器后再测。标准容器里 `fix-before-1790268566` 在恢复并重启后仍在，`fix-after-1790268566` 不在。Topling 容器里 `top-fix-before-1790268622` 仍在，`top-fix-after-1790268622` 不在。日志有 `Delete separate WAL directory`。这不是镜像重建。证据 `evidence/standalone-std-snapshot-fixed-jar.json`、`evidence/standalone-top-snapshot-fixed-jar.json`。

2026-09-25 01:05：基于原单机镜像只替换 `hugegraph-rocksdb-1.7.0.jar` 建了本地标签。标准 `closure-std-walrestore` 是 `sha256:b66c835a3b3ec43d9180a9aa36b90e857915e63620e044dfa15cb1e8f9e73c86`，恢复并重启后快照前顶点仍在，快照后顶点不在。Topling `closure-top-walrestore` 是 `sha256:80b9acbc4f96fca34e60e056a20c1bd575c5cdf6f5ed7f89412df94860117875`，结果相同。证据 `evidence/standalone-walrestore-image.json`。原 closure 标签没有被覆盖。

## 当前动作

2026-09-25 03:25：完整 Topling 单机镜像 `hugegraph/hugegraph:closure-e109012a0`（`sha256:4832e9d484cf557ddd654170b82c8d4ee4375f88189ec5f12838daad0bd16cf6`）上，`snapshot_create` 和 `snapshot_resume` 都是 200。容器 `hg-e109-top-snap` 重启 19.089 秒后，快照前顶点 `full-before-1790277911` 仍在，快照后顶点不在。日志有 3 次 `Replaced separate WAL`，mmap WAL 错误 0。进程映射 `/hugegraph-server/library/librocksdbjni-linux64.so`，SHA-256 `c25ff6e676290db6db47df0954640aa609c391450ec90e1a8eec1f87e174dd38`。镜像内 RocksDB 类含 `replaceSeparateWalDirectory`，安全类 SHA-256 `fbfe9aaef619665e7a590bc0f1959b2c71cbb37d4eaf38d3e6887d4817c781c0` 且包含 `useCustomAllocator`。PD、Store、standalone 的 `memtable_as_log_index` 都是 false。这不是 overlay。证据 `evidence/standalone-e109-top-snapshot.json`。

2026-09-25 03:29：标准镜像单元 `hg-closure-std-image-e109012a0` 退出 0。tag `closure-std-e109012a0` 没有覆盖 Topling 的 `closure-e109012a0`。standalone `sha256:e27221a071e5`、PD `4c9011a7bd64`、Store `f53fa5f612c2` 的 runtime 都是 standard，revision 是 `e109012a0`。HStore `hugegraph/server:closure-std-e109012a0` 与 `closure-e109012a0` 是同一镜像 `5b9f40a9d1fc`，runtime 为 hstore，因为 `Dockerfile-hstore` 没有 Topling 阶段。标准单机镜像内没有 `librocksdbjni*.so`，RocksDB 类 SHA-256 与 Topling 镜像相同，且含 WAL 恢复。

2026-09-25 03:31：标准单机 `hg-e109-std-snap` 使用 `hugegraph/hugegraph:closure-std-e109012a0`。`snapshot_create` 和 `snapshot_resume` 都是 200，重启 5.069 秒后快照前顶点仍在、快照后顶点不在，`Replaced separate WAL` 3 次。进程映射 `/tmp/librocksdbjni16856703238417969007.so`，SHA-256 `8b8fb2ed3ab69581cf1897bd116d484f073e66e9a5b6d61effc7b4c783d66dff`，不是 Topling JNI。证据 `evidence/standalone-e109-std-snapshot.json`。

2026-09-25 03:35：新 namespace `hg-closure-top-e109-111` Helm 退出 0，3/3 Ready。PD 与 Store 都映射 `/library/librocksdbjni-linux64.so`，SHA-256 `c25ff6e6…dd38`。Server 没有 RocksDB JNI 映射。Store 的 jemalloc 下载停在 0 字节，终止 curl 后走原有跳过路径。mmap WAL 错误 0。没有升级历史 namespace。证据 `evidence/helm-topling-e109-111-jni.json`。

2026-09-25 03:39：同一 namespace 导入 `law_twitter_1m`，Loader 退出 0，161.21 秒，1000000 点、2098771 边、失败 0。重启前 32 个邻接样本 0 不一致，9 个自环都在。证据 `evidence/loader-law-twitter-1m-e109.json`、`evidence/loader-law-twitter-1m-e109-verify.json`。

Store 删除后的复测无效：脚本把仍在终止的旧 Pod 读成 0.074 秒 Ready。新 Pod IP 是 `10.244.0.129`，Server 的 gRPC 仍连接旧 IP `10.244.0.128:8500`，`54148543` 的 IN 查询 30 秒后 `ConnectTimeoutException`。这不是沙箱 Panic。稳定后顶点 `56862681` 仍返回 200。PVC `pvc-782709b8-cc8d-4f60-9c17-ec8440d73cfb` 未变。Gremlin 计数还没有可用别名。证据 `evidence/loader-law-twitter-1m-e109-note.json`。

2026-09-25 03:47：旧 Server 在 `19:47:22Z` 被删除，新 Server `nj8xz` 同时启动，Ready 时间是 `19:47:32Z`，间隔 10 秒。这只是为了重新解析 Store，不是 Store 恢复时间。之后 32 个样本 0 不一致，9 个自环都在。Gremlin 别名 `__g_DEFAULT-law_twitter_1m` 返回 `1000000:2098771`，耗时 0.57 秒。Store JNI 仍是 `c25ff6e6…dd38`。Server 没有自行更新旧 IP `10.244.0.128`。证据 `evidence/loader-law-twitter-1m-e109-reresolve.json`、`evidence/loader-law-twitter-1m-e109-count.json`、`evidence/e109-server-reresolve.json`。

2026-09-25 03:53：标准 namespace `hg-closure-std-e109-111` Helm 退出 0，3/3 Ready。PD 与 Store 映射的是 `/tmp/librocksdbjni*.so`，SHA-256 `8b8fb2ed…6dff`，不是 Topling。Server 没有 RocksDB JNI 映射。jemalloc 下载停在 0 字节后按原路径跳过。证据 `evidence/helm-standard-e109-111-jni.json`。

2026-09-25 03:56：标准 `hg-closure-std-e109-111` 写入 `std-e109-1790279740` 返回 201。Store UID 从 `f2267618` 换成 `6bc1d4cf`，IP 从 `10.244.0.134` 换成 `10.244.0.136`，新 Pod Ready 用了 12.239 秒。PVC `pvc-4b0bd019-0456-44a6-b2c3-b76266773958` 未变。重启前后 JNI 都是标准 `8b8fb2ed…6dff`。旧 Server 仍连接 `10.244.0.134:8500` 并超时。替换 Server 后 11.527 秒 Ready，按 label 列出的顶点仍在，id 是 `1:std-e109-1790279740`。证据 `evidence/helm-standard-e109-111-persist.json`、`evidence/helm-standard-e109-111-reread.json`。

下一步：标准与 Topling 的 HStore 客户端都不会在 Store Pod IP 变化后改连。先记为当前 SHA 的可复现重连缺陷，不把它写成恢复通过。标准 `hg-closure-std-e109-333` 在 03:58:59 Helm 退出 0，9/9 Ready。三台 PD 和三台 Store 的 JNI 都是标准 `8b8fb2ed…6dff`，三台 Server 没有 RocksDB JNI。写入 `std333-e109` 返回 201，三台 Server 都读到。证据 `evidence/helm-standard-e109-333-function.json`。

2026-09-25 04:03：Topling `hg-closure-top-e109-333` 第一次安装误用了 `rocksdb` provider，标记冲突后已卸载并删除该 namespace 的 PVC，再按 `topling` 重装。Helm 退出 0，9/9 Ready。三台 PD 和三台 Store 的 JNI 都是 `c25ff6e6…dd38`，三台 Server 没有 RocksDB JNI，mmap WAL 错误 0。写入返回 201，三台 Server 都读到。证据 `evidence/helm-topling-e109-333-function.json`。

2026-09-25 04:04：标准 `hg-closure-std-e109-111` 上图 `lifecycle_std_e109` 创建 201，truncate 204 后旧顶点不在，再写 201 且只剩新顶点。删图 204 后读取 404，重建 201 并能再写。证据 `evidence/helm-standard-e109-111-lifecycle.json`。

2026-09-25 04:17：未提交修复打进 overlay 镜像 `hugegraph/server:closure-e109-channelrefresh`（`sha256:3a129d448516`），只升级了 `hg-closure-std-e109-111` 的 Server。同一个 Server Pod 在 Store IP 从 `10.244.0.136` 变成 `10.244.0.196` 后，第一次读取超时，42.645 秒时第二次读取返回 200 并找到 `std-e109-1790279740`。没有再重启 Server。这不是完整 SHA 镜像。证据 `evidence/helm-standard-e109-111-channel-refresh.json`。单测仍是 2 通过。提交前还要 3 名独立审查。

2026-09-25 04:21：标准 `hg-closure-std-e109-333` 删除 leaderCount 5 的 Store-2。新 UID Ready 用了 12.3 秒，PVC `pvc-dcdbb110-cb56-40e5-b48f-f6f6cf067a0e` 未变，JNI 仍是标准 `8b8fb2ed…6dff`。旧顶点可读，新写入返回 201 且按属性可查到。恢复后 Store-2 的 leaderCount 是 0，Store-0 是 7，Store-1 是 5。Ready 不是领导权。这是单节点 Pod 删除，不是网络分区。证据 `evidence/helm-standard-e109-333-store-leader.json`。

2026-09-25 04:24：Topling `hg-closure-top-e109-333` 删除 leaderCount 6 的 Store-0。新 UID 12.381 秒 Ready，PVC `pvc-f86f7974-a386-4a05-b161-8cf32d766780` 未变，JNI 仍是 `c25ff6e6…dd38`，mmap WAL 错误 0。旧顶点和恢复后的新写入都能按属性读到。恢复后 Store-0 leaderCount 是 0，Store-1 是 4，Store-2 是 8。证据 `evidence/helm-topling-e109-333-store-leader.json`。

2026-09-25 04:25：标准 `hg-closure-std-e109-333` 删除一台 Server。保留的 Server 在删除期间写入 `std333-server-replica-1790281502` 返回 201 并读到。替代 Pod `mcm6w` 11.647 秒 Ready，也读到该顶点。证据 `evidence/helm-standard-e109-333-server-replica.json`。

2026-09-25 04:26：Topling `hg-closure-top-e109-333` 删除一台 Server。保留副本在删除期间写入 `top333-server-replica-1790281578` 返回 201，并读到删除前的顶点。替代 Pod `stmjs` 10.607 秒 Ready，也读到新顶点。证据 `evidence/helm-topling-e109-333-server-replica.json`。

2026-09-25 04:27：当前 SHA 的标准 HStore `snapshot_create` 仍是 500，异常是 `BackendStore.createSnapshot` 的 `UnsupportedOperationException: createSnapshot`。这不是 Raft `DO_SNAPSHOT`。证据 `evidence/helm-standard-e109-111-snapshot.json`。

2026-09-25 04:28：Topling `hg-closure-top-e109-111` 上图 `lifecycle_top_e109` 创建 201，truncate 204 后旧顶点不在，再写只剩新顶点。删图 204 后读取 404，重建后能再写。`law_twitter_1m` 仍在。证据 `evidence/helm-topling-e109-111-lifecycle.json`。同一 SHA 的 Topling `snapshot_create` 也是 500 `UnsupportedOperationException: createSnapshot`。证据 `evidence/helm-topling-e109-111-snapshot.json`。

2026-09-25 04:30：标准 `hg-closure-std-e109-333` 删除 PD leader `pd-1`。删除期间 Server 写入 `std333-pd-leader-1790281814` 返回 201 并读到。新 UID 41.772 秒 Ready，PVC `pvc-047f56ea-490b-470f-b7ea-0125c78e7485` 未变，JNI 仍是标准 `8b8fb2ed…6dff`。恢复后 leader 在 `pd-0`，成员数 3，状态 `Cluster_OK`。这是单节点 Pod 删除。证据 `evidence/helm-standard-e109-333-pd-leader.json`。

2026-09-25 04:32：Topling `hg-closure-top-e109-333` 删除 PD leader `pd-0`。删除期间写入 `top333-pd-leader-1790281933` 返回 201 并读到。新 UID 41.714 秒 Ready，PVC `pvc-70af47fe-0e10-427f-8afb-f7cd1074da67` 未变，JNI 仍是 `c25ff6e6…dd38`。恢复后 leader 主机名仍是 `pd-0`，成员数 3。状态在删除前后都是 `Cluster_Not_Ready`，但 3 台 Store 在线，`dataState` 是 `PState_Normal`。证据 `evidence/helm-topling-e109-333-pd-leader.json`。

2026-09-25 04:35：标准 `hg-closure-std-e109-333` 的三台 Server 换成 overlay `closure-e109-channelrefresh` 后，同时删除 Store-0 和 Store-1（leaderCount 7 和 5）。Store-2 保留。故障期间新写入超时。两台新 Pod 22.678 秒都 Ready，PVC 未变，JNI 仍是标准 `8b8fb2ed…6dff`。46 秒时旧顶点可读，新写入返回 201。恢复后 leaderCount 是 4、2、6。没有重启 Server。这是单节点 Pod 删除，不是网络分区，也不是完整 SHA 镜像。证据 `evidence/helm-standard-e109-333-store-majority.json`。

2026-09-25 04:38：Topling `hg-closure-top-e109-333` 的三台 Server 换成同一张 overlay 后，同时删除 Store-1 和 Store-2（leaderCount 4 和 8），保留 Store-0。故障期间新写入超时。两台新 Pod 21.427 秒都 Ready，PVC 未变，JNI 仍是 `c25ff6e6…dd38`，mmap WAL 错误 0。44.711 秒时旧顶点可读，新写入返回 201。恢复后 leaderCount 是 9、0、3。没有重启 Server。这是单节点 Pod 删除，不是网络分区，也不是完整 SHA 镜像。证据 `evidence/helm-topling-e109-333-store-majority.json`。

2026-09-25 04:42：标准 `hg-closure-std-e109-111` 导入图 `law_twitter_1m_std`。Loader 退出 0，80.973 秒，1000000 点、2098771 边、失败 0。PD `4c9011a7bd64`、Store `f53fa5f612c2` 是标准 `e109012a0` 镜像。Server 是 overlay `closure-e109-channelrefresh` `3a129d448516`。证据 `evidence/loader-law-twitter-1m-std-e109.json`。第一次启动误写了 Topling 证据目录的 `start.json` 和 `loader.log`；Topling 导入结论仍以 `loader-law-twitter-1m-e109.json` 里的退出 0 和计数为准。

2026-09-25 04:45：标准 `law_twitter_1m_std` 重启前 32 个邻接样本 0 不一致，9 个自环通过，Gremlin 计数 `1000000:2098771`。证据 `evidence/loader-law-twitter-1m-std-e109-adjacency.json`。随后删除 Store-0，新 UID 12.835 秒 Ready，PVC `pvc-4b0bd019-0456-44a6-b2c3-b76266773958` 未变，JNI 仍是标准 `8b8fb2ed…6dff`。同一个 overlay Server 没有重启。恢复后 32 个样本和 9 个自环再次通过，计数仍是 `1000000:2098771`。证据 `evidence/loader-law-twitter-1m-std-e109-restart.json`、`evidence/loader-law-twitter-1m-std-e109-adjacency-after.json`。

2026-09-25 04:48：Topling `hg-closure-top-e109-111` 的 Server 换成 overlay `closure-e109-channelrefresh`。重启前 `law_twitter_1m` 的 32 个样本、9 个自环和计数 `1000000:2098771` 通过。删除 Store-0 后新 UID 14.505 秒 Ready，PVC `pvc-782709b8-cc8d-4f60-9c17-ec8440d73cfb` 未变，JNI 仍是 `c25ff6e6…dd38`。同一个 Server Pod `mm7h8` 没有重启。第一轮复测只有 `54148543` IN 返回 500，立刻重试得到 142 条；第二轮 32 个样本和 9 个自环通过，计数仍是 `1000000:2098771`。证据 `evidence/loader-law-twitter-1m-e109-restart-overlay.json`、`evidence/loader-law-twitter-1m-e109-adjacency-after-overlay.json`、`evidence/loader-law-twitter-1m-e109-adjacency-after-retry.json`。

2026-09-25 04:52：标准和 Topling 的 e109 1+1+1 上各建独立图做功能矩阵。`/versions` 无认证返回 200；图列表无认证和错误口令都是 401，正确口令是 200。二级索引创建 202，`limit=2` 返回 2 条，按 `title=beta` 只命中一个目标顶点。Gremlin 写入和 Cypher 查询都是 200。Server 是 overlay `closure-e109-channelrefresh`，PD/Store 仍是 `e109012a0` 镜像。证据 `evidence/e109-functional-matrix.json`。

下一步以上面的“下一动作”为准。HStore 图快照仍未实现，重连修复审查前不得提交，核心功能未完成前不开始 benchmark。

2026-09-25 05:08：标准和 Topling 的 e109 1+1+1 批量原子性通过。合法批次 201，三条顶点可读；混入 `missing` 返回 400，回滚顶点不在，原顶点仍在。Store JNI 分别是标准 `/tmp/librocksdbjni*.so` `8b8fb2ed…6dff` 和 Topling `/hugegraph-store/library/librocksdbjni-linux64.so` `c25ff6e6…dd38`。同名顶点不能从另一张图读到。顶点追加 `city=sg` 为 200，JSON 字符串 ID 删除为 204。无角色建图为 403，图空间和用户已删除，Kubernetes namespace 没有增加。两个 `9aba` namespace 仍是 `closure-std-9abae9dbaaa1`，P2 已勾选。证据 `evidence/e109-batch-tx.json`、`evidence/e109-multigraph-isolation.json`、`evidence/e109-crud-role.json`。

2026-09-25 05:14：标准和 Topling 的 e109 1+1+1 边 `knows` 创建 201，`since` 从 1 更新到 2，删除 204 后查询为空。JNI 仍分别是 `8b8fb2ed…6dff` 和 `c25ff6e6…dd38`。证据 `evidence/e109-edge-crud.json`。e109 单机 `/graphs`：无认证、错误口令和未知用户都是 401，admin 是 200，`/versions` 无认证 200；没有创建或删除用户，也没有重启快照容器。证据 `evidence/e109-standalone-auth.json`。e109 集群删除用户后同一口令访问图列表从 200 变为 401。证据 `evidence/e109-deleted-user-auth.json`。

2026-09-25 05:19：e109 1+1+1 Store Java `kill -9` 后，标准 restartCount 0 到 1，30.850 秒 Ready，PVC 不变，`standard-batch-1790283889-1` 仍可读，JNI 仍是 `/tmp` 上的 `8b8fb2ed…6dff`。Topling 同样 0 到 1，32.704 秒 Ready，`topling-batch-1790283890-1` 仍可读，JNI 仍是 `library/librocksdbjni-linux64.so` 的 `c25ff6e6…dd38`。两边启动时 0 字节 jemalloc curl 被终止后走原有跳过路径。Topling 重启后的日志里 mmap WAL 错误是 0。第一次检测误匹配了脚本自身命令行，没有杀掉 Java，结果作废。证据 `evidence/e109-store-kill9.json`。

2026-09-25 05:22：标准 e109 3+3+3 只杀掉 Store-2 的 Java。杀掉前 leaderCount 是 4、2、6，目标是 Store-2。同一 Pod UID `0673df3e-232d-4857-85c8-16927a7fbad7` 和 PVC `pvc-dcdbb110-cb56-40e5-b48f-f6f6cf067a0e` 未变，restartCount 0 到 1，33.144 秒 Ready。0 字节 jemalloc 被终止。JNI 仍是 `/tmp` 上的 `8b8fb2ed…6dff`。图 `hugegraph` 顶点 `std333-e109-1790279980` 在三台 Server 上都可读。Ready 后 leader 合计仍是 12，Store-2 为 0。这是领导权转移，不是数据丢失。证据 `evidence/e109-std-333-store-kill9.json`。

2026-09-25 05:24：Topling e109 3+3+3 只杀掉 Store-0 的 Java。杀掉前 leaderCount 是 9、0、3。同一 Pod UID `f9b50cb1-82bc-42ac-b5a0-d9dd7ba32f87` 和 PVC `pvc-f86f7974-a386-4a05-b161-8cf32d766780` 未变，restartCount 0 到 1，37.334 秒 Ready。0 字节 jemalloc 被终止。JNI 仍是 `library/librocksdbjni-linux64.so` 的 `c25ff6e6…dd38`，mmap WAL 错误 0。顶点 `top333-e109-1790280210` 在三台 Server 上都可读。Ready 后 leader 合计仍是 12，Store-0 为 0。随后标准和 Topling 都写入新顶点并被三台 Server 读到。证据 `evidence/e109-top-333-store-kill9.json`、`evidence/e109-333-write-after-kill9.json`。

2026-09-25 05:28：标准 e109 3+3+3 只杀掉 PD-0 的 Java。杀掉前它是 leader，`Cluster_OK`，成员数 3。同一 Pod UID `ee7cfd5c-d267-462b-84e1-07a264a3b0ab` 和 PVC `pvc-bb325c48-0449-4117-8630-6b4436869cf5` 未变，restartCount 0 到 1，14.514 秒 Ready。JNI 仍是 `/tmp` 上的 `8b8fb2ed…6dff`。Ready 当下状态一度是 `Cluster_Not_Ready`，leader 转到 PD-1，成员数仍是 3。随后复查为 `Cluster_OK`、`PState_Normal`、3 个 Store 在线。旧顶点 `std333-e109-1790279980` 仍可读，恢复后新顶点写入 201 并可读。故障期间写入也返回 201。这是进程崩溃，不是 Pod 删除。证据 `evidence/e109-std-333-pd-kill9.json`。

2026-09-25 05:29：Topling e109 3+3+3 只杀掉 PD-0 的 Java。杀掉前它是 leader，`Cluster_OK`，成员数 3。同一 Pod UID `781cc001-b38b-4214-8f3f-dff283e61caf` 和 PVC `pvc-70af47fe-0e10-427f-8afb-f7cd1074da67` 未变，restartCount 0 到 1，12.534 秒 Ready。JNI 是 `/hugegraph-pd/library/librocksdbjni-linux64.so` 的 `c25ff6e6…dd38`，mmap WAL 错误 0。Ready 当下是 `Cluster_Not_Ready`，leader 转到 PD-1。随后复查为 `Cluster_OK`、`PState_Normal`、3 个 Store 在线。旧顶点仍可读，恢复后新写入 201 并可读。证据 `evidence/e109-top-333-pd-kill9.json`。

2026-09-25 05:31：标准 e109 3+3+3 杀掉 Server `smq6m` 的 Java。同一 Pod UID `e28ea87b-1af7-418e-be4c-860804a1eecf`，restartCount 0 到 1，12.514 秒 Ready。镜像仍是 overlay `closure-e109-channelrefresh`。保留的 Server 在故障期间读到旧顶点，故障期间和恢复后的写入都是 201。恢复后的 Server 和第三台 Server 也能读到旧顶点和新顶点。Topling 杀掉 Server `plp4x`，UID `b2ac6461-96c2-4463-8fad-d07a890125a4` 不变，9.441 秒 Ready，同样的读写结果。这是进程崩溃，不是 Pod 删除或网络分区。证据 `evidence/e109-std-333-server-kill9.json`、`evidence/e109-top-333-server-kill9.json`、`evidence/e109-333-server-kill9-third-read.json`。
2026-09-25 05:32：复查 kind 仍只有 `kind-control-plane` 一个节点，Kubernetes v1.37.0。没有 Chaos Mesh 或 VolumeSnapshot CRD。网络分区和卷快照继续后置。

2026-09-25 05:40：核对 P6。Topling 完整 Server `sha256:5b9f40a9d1fc` 导入退出 0，1000000 点、2098771 边，重启前 32 个样本和 9 个自环通过。Store Pod IP 从 `10.244.0.128` 变为 `10.244.0.129` 后，`54148543` IN 返回 500，原因是连接旧地址超时。overlay `sha256:3a129d448516` 上，标准重启 12.835 秒、Topling 14.505 秒，重试后都是 32/0/9，Gremlin `1000000:2098771`。标准导入本身也用了这张 overlay。channel refresh 仍是未提交差异。证据 `evidence/e109-loader-restart-gap.json`。

另外，一次性单机容器补了已删除用户：创建 201，删除前图列表 200，删除 204，之后 401。标准 JNI `8b8fb2ed…6dff`，Topling JNI `c25ff6e6…dd38`。没有改动快照容器，容器已删除。图空间管理在单机模式仍是 400。证据 `evidence/e109-standalone-deleted-user.json`、`evidence/e109-standalone-graphspace-support.json`。

2026-09-25 05:45：`AbstractGrpcClientChannelRefreshTest` 直接用 JUnit 4.13.2 跑了 2 个测试，0.404 秒，结果 OK。普通 `mvn -pl hg-store-client` 因已安装 POM 里的 `${revision}` 无法解析依赖；reactor classpath 编译后由 `JUnitCore` 执行。证据 `evidence/build/channel-refresh-junit.txt`。测试通过仍不能代替 3 名独立审查。

全量 LAW 容量：原始图 41652230 节点、1468365182 条弧，压缩图约 2.6GB；固定子集 1000000 点、2098771 边。主机剩余约 1.1T。Store PVC 请求是 50Gi，但容器里的 df 看到的是主机磁盘。全量大约是子集边数的 700 倍，不放进当前 e109 namespace。证据 `evidence/full-law-capacity.json`。

源码复核：`HstoreProvider` 没有覆盖 `createSnapshot`，`BackendStore` 的默认实现抛出 `UnsupportedOperationException("createSnapshot")`。这不是可在本机补的一行修复。

2026-09-25 05:50：复核 HA Compose 与 Helm。Compose 的 PD 健康检查仍是 `/v1/health`，Store 等待 3 个 PD，Server 等待 3 个 Store。Helm 在 3 副本时 readiness 是 `/v1/ready`，startup/liveness 是 `/v1/health`；只有 `pd.replicas == 1` 时 liveness helper 才变成 `/v1/ready`。`values-cluster.yaml` 仍要求反亲和和 5Gi/8Gi Store 内存，并且不启用 Hubble。没有改配置，也没有改运行中的集群。P5 对齐项继续不勾选。

2026-09-25 05:57：用未修改的 `hugegraph/server:closure-e109012a0`（`sha256:5b9f40a9d1fc`）分别在标准和 Topling 的 e109 1+1+1 里短时启动一个带 Server 标签的 Pod。网络策略要求 `app.kubernetes.io/component=server`，并且要显式设置 `HG_SERVER_USE_PD=true`。两边批量写入 3 个顶点都是 201，混入未定义属性返回 400，回滚顶点不在，原来的顶点仍在。图随后删除，Pod 也删除。这不是 overlay，也不证明 Store IP 变化后的重连。证据 `evidence/e109-fullserver-std-batch.json`、`evidence/e109-fullserver-top-batch.json`。

2026-09-25 06:05：未修改的 `hugegraph/server:closure-e109012a0`（`sha256:5b9f40a9d1fc`）临时 Pod 上，标准和 Topling 都创建了二级索引。`limit=2` 返回 2 条。按 `title=two` 只返回 `beta` 和 `gamma`。Gremlin 写入任务成功，顶点可读。Cypher 查询 `alpha` 返回 200 且结果包含 `alpha`。测试图已删除，Pod 已删除。Store JNI 仍分别是 `/tmp` 上的 `8b8fb2ed…6dff` 和 `library/librocksdbjni-linux64.so` 的 `c25ff6e6…dd38`。证据 `evidence/e109-fullserver-index-query.json`。

2026-09-25 06:12：未修改 Server 镜像的临时 Pod 上，标准和 Topling 的 `/versions` 无认证都是 200，图列表无认证和错误口令是 401，正确口令是 200。新建用户删除前访问为 200，删除后为 401。两个图互相隔离，A 图顶点在 B 图不可见，两个图都已删除，Pod 也已删除。Store JNI 仍分别是 `8b8fb2ed…6dff` 和 `c25ff6e6…dd38`。P3 因此勾选。证据 `evidence/e109-fullserver-isolation-auth.json`。

2026-09-25 06:13：当前源码上跑了 `GrpcShutdownBarrierTest` 和 `ScanShutdownTest`，JUnit 4.13.1，11 个测试 OK，用时 0.938 秒。其中扫描迭代器失败会打出预期的 error 日志，测试仍通过，表示资源关闭没有被当成成功完成。证据 `evidence/build/store-shutdown-junit.txt`。没有为了让测试通过而强行关库。

2026-09-25 06:16：标准和 Topling 的 e109 3+3+3 都在现有 overlay Server 上完成删图重建和 clear/truncate。删图 204 后读取 404，重建后旧顶点不在、新顶点可读；clear 204 后图仍在且数据清空，之后可以再写。`snapshot_create` 仍是 500 `createSnapshot`。另一轮写入被三台 Server 读到，从第二台删图后三台都返回 404。Store JNI 仍分别是 `8b8fb2ed…6dff` 和 `c25ff6e6…dd38`。证据 `evidence/e109-333-drop-truncate.json`。

2026-09-25 06:20：未修改的 `hugegraph/server:closure-e109012a0`（`sha256:5b9f40a9d1fc`）临时 Pod 连到标准和 Topling 的 e109 3+3+3。两边删图 204 后读取 404，重建后旧顶点不在、新顶点可读；clear 204 后图仍在且数据清空，之后可以再写。`snapshot_create` 仍是 500。图和 Pod 已删除。Store JNI 仍分别是 `8b8fb2ed…6dff` 和 `c25ff6e6…dd38`。证据 `evidence/e109-333-fullserver-drop-truncate.json`。

2026-09-25 06:24：历史 namespace `hg-closure-standard-333` 仍是 `closure-std-9abae9dbaaa1`，没有升级。复用该 namespace 创建 auth 图空间后，无角色用户建图返回 403 `User not authorized`。图空间和用户已删除，没有新增 Kubernetes namespace。这不是当前 SHA 证据。证据 `evidence/helm-standard-333-role-denial.json`。

2026-09-25 06:28：历史 `hg-closure-standard-111` 仍是 `closure-std-9abae9dbaaa1`。对已有图 `hugegraph` 调用 `snapshot_create` 返回 500 `UnsupportedOperationException: createSnapshot`。`evidence/helm-standard-111-lifecycle.json` 里的新卷恢复是文件系统拷贝，1120 个文件一致，但恢复后已确认顶点全部 404。禁止再做这种目录拷贝。这不是当前 SHA，也没有 VolumeSnapshot CRD。证据 `evidence/helm-standard-111-snapshot-api.json`。

2026-09-25 06:32：复查 4 个 e109 namespace 里全部 16 个 PD 和 Store Java 进程。8 个标准进程都映射 `/tmp/librocksdbjni*.so`，SHA-256 是 `8b8fb2ed…6dff`，没有映射 Topling 的 `library/librocksdbjni-linux64.so`。8 个 Topling 进程都映射该库，SHA-256 是 `c25ff6e6…dd38`。没有发现静默 fallback。证据 `evidence/e109-jni-after-restarts.json`。
2026-09-25 05:39：`KvBatchScanner.KvBatchReceiver`、`KvBatchScanner5.OrderAgent` 和 `CommonKvStreamObserver` 在 `UNAVAILABLE` 时会丢掉按地址缓存的 channel。`QueryExecutor` 把查询地址交给 observer。`GrpcStoreStreamClient.doBatchScan3` 把 Store 地址交给批量扫描。JUnit 4.13.2 跑 `AbstractGrpcClientChannelRefreshTest`，6 个测试 OK，0.473 秒。日志里的 ERROR 是测试故意送入的 `INVALID_ARGUMENT` 和 `UNAVAILABLE`。这不是独立审查，不能提交，也不能用来勾选 P6。证据 `evidence/build/channel-refresh-junit.txt`。

2026-09-25 06:55：标准 `hg-closure-std-e109-111` 新增临时 Pod `hg-e109-std-fullserver-load`，镜像 `hugegraph/server:closure-e109012a0`（`sha256:5b9f40a9d1fc`），不是 overlay。新图 `law_twitter_1m_std_fullsha` Loader 退出 0，57.254 秒，1000000 点、2098771 边、失败 0。32 个邻接样本 0 不一致，9 个自环都在。Store Pod 没有删除或重启，JNI 是 `/tmp/librocksdbjni4527535612841869204.so`，SHA-256 `8b8fb2ed…6dff`。临时 Pod 已删除。Store IP 变化后的复测仍未做，P6 不勾选。证据 `evidence/loader-law-twitter-1m-std-e109-fullserver.json`。
2026-09-25 07:05：`KvBatchScanner5.refreshUnavailable` 被 `OrderAgent.onError` 调用，并用会话代理补了 3 个回归。`AbstractGrpcClientChannelRefreshTest` 现在是 9 个测试 OK，0.467 秒。证据 `evidence/build/channel-refresh-junit.txt`。三份只读审查还没结束，不能提交。
2026-09-25 07:12：审查 1 结论 HIGH_SEVERITY=1，证据 `evidence/build/channel-refresh-review-1.md`。修复后 `closeChannelIfUnavailable` 只移除仍拥有失败 channel 的池；旧 channel 的再次 UNAVAILABLE 不会删除新池。`AbstractGrpcClientChannelRefreshTest` 12 个测试 OK，0.482 秒。第二轮审查进行中，不能提交。
2026-09-25 07:20：`ContextClosedListenerTest` 2 个 OK，0.487 秒。关闭线程在 worker 清理完成前不会结束，gRPC 回调未结束时 disposable bean 没有关库。没有为了测试强行关库。证据 `evidence/build/context-closed-listener-junit.txt`。channel refresh 的第二轮审查仍在进行，不能提交。
2026-09-25 07:19：`git fetch org toplingdb` 后本地 HEAD 与 `org/toplingdb` 仍是 `a7a4a6f1b`，ahead 0、behind 0。channel refresh 第二轮三份只读审查的进程仍在运行，最终结论文件还没写出，所以仍然不能提交。
2026-09-25 07:25：第二轮审查 1 和 2 是 HIGH_SEVERITY=none，审查 3 是 HIGH_SEVERITY=1。问题是关掉旧池后的 `Channel shutdown` UNAVAILABLE 会经 `evictNode` 再删掉新池。现已不把这种状态当成节点摘除；没有 ManagedChannel 的失败也不再删除当前池。`KvPageScannerTest` 3 个通过，`AbstractGrpcClientChannelRefreshTest` 14 个通过。证据 `evidence/build/channel-refresh-rereview-1.md`、`channel-refresh-rereview-2.md`、`channel-refresh-rereview-3.md`、`channel-refresh-junit.txt`、`kv-page-scanner-junit.txt`。第三轮审查已启动，仍不能提交。
2026-09-25 07:34：第三轮三份只读审查的进程仍在运行，约 7 分钟，最终结论文件还没写出。没有改 Java，也没有提交。
2026-09-25 07:40：第三轮审查 3 结论 HIGH_SEVERITY=1，证据 `evidence/build/channel-refresh-round3-3.md`。`evictsOnUnavailable` 现在拒绝 `Subchannel shutdown invoked`。`shutdownChannels` 改为 `shutdownNow`。`AbstractGrpcClientChannelRefreshTest` 14 个 OK，0.46 秒，证据 `evidence/build/channel-refresh-junit.txt`。这是第 3 轮审查后的修复，按合同不再自动开始第 4 轮，Java 提交后置。
2026-09-25 07:45：标准和 Topling 的 e109 3+3+3 各对一台未被本次 kill -9 过的 Store 发送 SIGTERM，没有再发 kill -9。标准 store-1 同一 Pod UID 和 PVC，restartCount 0 到 1，71.680 秒后 Ready，顶点仍返回 200，JNI 仍是 `/tmp` 上的 `8b8fb2ed…6dff`。上一容器日志有 `closing all rocksdb`，没有 `db not closed`。证据 `evidence/e109-std-333-store-sigterm.json`。Topling store-1 同样同一 UID 和 PVC，restartCount 0 到 1，83.606 秒后 Ready，顶点仍返回 200，JNI 是 `library/librocksdbjni-linux64.so` 的 `c25ff6e6…dd38`。上一容器在关闭末尾仍打印 `SidePluginRepo` `db not closed`。证据 `evidence/e109-top-333-store-sigterm.json`、`evidence/e109-top-333-store-sigterm-shutdown.txt`。两边的 Ready 时间都包含 0 字节 jemalloc curl 被终止后走原有跳过路径。这不是 snapshot，P4 仍不勾选。
2026-09-25 07:50：标准和 Topling 的 e109 3+3+3 各对一台 restartCount 为 0 的 Server 发送 SIGTERM，没有 kill -9。标准 `cq2ss` 9.371 秒后 Ready，Topling `5zmlm` 14.616 秒后 Ready。Pod UID 不变，restartCount 0 到 1。另一台副本在重启前后都能读到原顶点，状态都是 200。这两台 Server 镜像是 overlay `closure-e109-channelrefresh`，不是未修改的 `sha256:5b9f40a9d1fc`，不能勾选当前 SHA。证据 `evidence/e109-std-333-server-sigterm.json`、`evidence/e109-top-333-server-sigterm.json`。
2026-09-25 07:55：标准和 Topling 的 e109 3+3+3 各对 PD-1 发送 SIGTERM，没有 kill -9。镜像都是 `hugegraph/pd:closure-e109012a0`。标准 PD-1 同一 UID 和 PVC，restartCount 0 到 1，8.459 秒 Ready，JNI 仍是 `/tmp` 上的 `8b8fb2ed…6dff`。顶点在另一台 Server 上重启前后都是 200。信号前集群已经是 `Cluster_Not_Ready`，成员数 3、在线 Store 3、`PState_Normal`，信号后仍是 `Cluster_Not_Ready`，所以不能把 Not_Ready 归因于这次 SIGTERM，也不能写成恢复到 OK。Topling PD-1 同样同一 UID 和 PVC，60.214 秒 Ready，JNI 是 `library/librocksdbjni-linux64.so` 的 `c25ff6e6…dd38`。信号前也是 `Cluster_Not_Ready`，信号后复查为 `Cluster_OK`，成员数 3，在线 Store 3。顶点仍是 200。证据 `evidence/e109-std-333-pd-sigterm.json`、`evidence/e109-top-333-pd-sigterm.json`。这不是网络分区，P5 仍不勾选。
2026-09-25 08:00：第三轮审查 2 结论 HIGH_SEVERITY=1，证据 `evidence/build/channel-refresh-round3-2.md`。`getChannels` 在某个创建线程失败时仍会发布含 null 的数组，然后因为不可用而无限重建。现改为创建失败时关闭本次已创建的 channel、不发布数组，并向调用方抛出异常。创建移出 `channels` 锁。`AbstractGrpcClientChannelRefreshTest` 15 个 OK，0.505 秒。证据 `evidence/build/channel-refresh-junit.txt`。这仍没有第 4 轮审查，不提交。
2026-09-25 08:00：标准 e109 3+3+3 的 `/v1/cluster` 仍是 `Cluster_Not_Ready`，但 API message 是 OK，3 个 Store 都是 Up，在线 Store 3，`PState_Normal`。PD-0/2 最近 2000 行和 PD-1 现有 81 行都没有 `cluster is not ready`。这个状态来自缓存的 `getClusterStats()`，GET 本身不重算。不是网络分区。证据 `evidence/e109-std-333-cluster-not-ready.json`。
2026-09-25 08:05：标准和 Topling 的 e109 1+1+1 唯一 PD 都做了 SIGTERM，没有 kill -9。镜像分别是 `hugegraph/pd:closure-std-e109012a0` 和 `hugegraph/pd:closure-e109012a0`。标准 8.433 秒、Topling 60.063 秒后 Ready，Pod UID 和 PVC 不变，restartCount 0 到 1。JNI 分别仍是 `/tmp` 上的 `8b8fb2ed…6dff` 和 `library/librocksdbjni-linux64.so` 的 `c25ff6e6…dd38`。信号前两边都是 `Cluster_OK`、成员数 1、在线 Store 1。信号后 `/v1/cluster` 都变成 `Cluster_Not_Ready`，但在线 Store 仍是 1，`PState_Normal`。脚本先查了 `hugegraph` 图，顶点 404，这是查错图，不是数据丢失。恢复后查 `batch_std_e109` 和 `batch_top_e109` 的 `1:` 前缀 ID 都是 200。证据 `evidence/e109-std-111-pd-sigterm.json`、`evidence/e109-top-111-pd-sigterm.json`。这不是当前 SHA 的 snapshot，也不证明物理分区。
2026-09-25 08:10：按 PD 成员重查 `/v1/cluster`。标准 3+3+3 的 leader `pd-0` 是 `Cluster_OK`，follower `pd-1` 和 `pd-2` 仍报 `Cluster_Not_Ready`。Topling 3+3+3 的 leader `pd-2` 是 `Cluster_OK`，follower `pd-0` 和 `pd-1` 仍报 `Cluster_Not_Ready`。两边在线 Store 都是 3，`initial-store-count` 是 3，`PState_Normal`。1+1+1 唯一 PD 现在都是 `Cluster_OK`，`initial-store-count` 是 1，在线 Store 1。SIGTERM 刚结束时的 `Cluster_Not_Ready` 没有保持。因此 follower 的 Not_Ready 不能当成集群故障或网络分区。证据 `evidence/e109-pd-cluster-state-by-member.json`。

2026-09-25：用户确认继续本机 goal，复用本目录和 PR #179。刷新下一动作。channel refresh 的重审与第 3 轮结论文件都已落盘，但 Java 在 07:54 之后又改过，JUnit 现为 15 个 OK，不能把旧审查当成最终差异通过。1+1+1 Store SIGTERM 证据仍不存在，作为下一独立实测。本次只更新合同，没有提交、推送或启动测试。

2026-09-25：标准和 Topling 的 e109 1+1+1 Store 各发一次 SIGTERM，没有 kill -9。标准 store-0 同一 UID `add022ef` 和 PVC `pvc-4b0bd019`，restartCount 1 到 2，12.692 秒 Ready。JNI 是 `/tmp/librocksdbjni*.so` 的 `8b8fb2ed…6dff`。上一容器有 `closing all rocksdb`，没有 `db not closed`。Topling store-0 同一 UID `0ffd5049` 和 PVC `pvc-782709b8`，restartCount 1 到 2，60.533 秒 Ready。JNI 是 `library/librocksdbjni-linux64.so` 的 `c25ff6e6…dd38`。上一容器在 `closing all rocksdb` 后仍有 `SidePluginRepo` `db not closed`。两边信号前顶点都是 200；信号后第一次读取也是 200。PD `/v1/cluster` 前后都是 `Cluster_OK`、成员数 1、在线 Store 1、`PState_Normal`。Ready 时间包含终止 0 字节 jemalloc curl，没有再杀 Java。读取用的 Server 都是 overlay `closure-e109-channelrefresh`，不是未修改 SHA。HStore snapshot 仍是缺口，P4 不勾选。证据 `evidence/e109-std-111-store-sigterm.json`、`evidence/e109-top-111-store-sigterm.json`。

2026-09-25：核对 `hugegraph/server:closure-e109012a0`。主机 Docker ID 是 `sha256:5b9f40a9d1fcfe84a3012a63b9b04f2825a5162de5f0dd9c8d317a310062ab3b`。kind 使用同 tag 启动的探针 Pod 算出 `docker-entrypoint.sh` 为 `4e416ba9…9b1d`、`bin/start-hugegraph.sh` 为 `ed274913…4fca`，与主机镜像一致。kubelet 报告 import digest `sha256:d0c5346b760fa244dc1cce3228fadcd7511a1c625c2fdda5bdf9fa2de16a4b81`，不是 Docker engine ID。证据 `evidence/e109-server-image-identity.json`。随后只把 e109 3+3+3 两个 Server Deployment 从 overlay 切回该镜像，没有改 `9aba`。标准 `dsdqd` SIGTERM 后 10.872 秒 Ready，UID `fe7d50b2` 不变，restartCount 0 到 1。保留副本 `fzf99` UID 不变，顶点 `1:std333-e109-1790279980` 在重启前、期间和之后都是 200。Topling `7zjgx` 11.971 秒，UID `69657dda` 不变，restartCount 0 到 1。保留副本 `92njw` 读 `1:top333-e109-1790280210`，三次都是 200。两边都不是 kill -9，上一容器没有 `db not closed`。P4 和 P5 仍不勾选。证据 `evidence/e109-std-333-server-sigterm-fullsha.json`、`evidence/e109-top-333-server-sigterm-fullsha.json`。

2026-09-25：e109 1+1+1 两台 Server 从 overlay 切回 `hugegraph/server:closure-e109012a0`，kubelet image ID 仍是 import digest `sha256:d0c5346b760f`。切换前 overlay 和切换后的新 Pod 都读到原顶点 200。标准 `zmpkw` SIGTERM 后 15.218 秒 Ready，UID 不变，restartCount 0 到 1，图 `batch_std_e109` 顶点 `1:standard-batch-1790283889-1` 恢复后为 200。Topling `vct4k` 14.416 秒，UID 不变，restartCount 0 到 1，图 `batch_top_e109` 顶点 `1:topling-batch-1790283890-1` 恢复后为 200。不是 kill -9。证据 `evidence/e109-std-111-server-sigterm-fullsha.json`、`evidence/e109-top-111-server-sigterm-fullsha.json`。P4、P5 仍不勾选。

2026-09-25：`git fetch org toplingdb` 后 HEAD 与远端仍是 `a7a4a6f1b`，ahead/behind 0/0。单节点 kind 仍没有 Chaos Mesh CRD。通过当前未修改 Helm Server 重读固定子集，没有重启 Store。标准 `law_twitter_1m_std_fullsha` 与 Topling `law_twitter_1m` 都是 32 个样本 0 不一致、9 个自环通过，Gremlin `1000000:2098771`。标准 JNI 是 `/tmp/librocksdbjni*.so` 的 `8b8fb2ed…6dff`，Topling JNI 是 `library/librocksdbjni-linux64.so` 的 `c25ff6e6…dd38`。Server 镜像是 `hugegraph/server:closure-e109012a0`，import digest `sha256:d0c5346b760f`。这不覆盖 Store IP 变化，P6 不勾选。证据 `evidence/loader-law-twitter-1m-std-e109-fullsha-helmserver.json`、`evidence/loader-law-twitter-1m-top-e109-fullsha-helmserver.json`。

2026-09-25：为已提交的嵌套 WAL 修复 `457295ac8` 启动 3 个只读 `codex exec review`，沙箱 read-only，不审未提交的 channel refresh。进程是 `1223803`、`1223805`、`1223807`。最终文件尚未写出，日志里的草稿不能当结论。

2026-09-25：WAL 初审结束。`wal-review-1.md` 未列高严重度，成功路径被接受。`wal-review-2.md` 和 `wal-review-3.md` 各有一个 P1，都是失败路径会在快照数据已经换入后留下或丢失 WAL tail。本地修复先退役全部活动 `*.log`，再发布 checkpoint tail；分开目录改名失败时把 tail 复制进 WAL 目录；WAL 路径是符号链接时不替换链接本身。`RocksDBSessionsTest` 17 个通过、0 失败、0 跳过，耗时 1.303 秒，新增 `testSymlinkedWalDirectoryKeepsLink`、`testNestedWalMoveFailureDoesNotReplayLaterLog`、`testSeparateWalPublishFailureStillInstallsTail`。证据 `evidence/build/rocksdb-sessions-junit.txt`。重审已启动且未完成，所以不提交。

2026-09-25：第一轮 WAL 重审里，`wal-rereview-1.md` 和 `wal-rereview-2.md` 都是 HIGH_SEVERITY=2。问题是同名旧日志只按文件名被当成 checkpoint tail，以及复制中断后的短文件会在源 tail 删除前被接受。本地改为先在同一目录把旧 `*.log` 改名为 `.aside-`，再把 checkpoint 写到 `.partial-` 临时文件，核对长度后改成正式日志。失败时不删除 data 目录里的源文件。`RocksDBSessionsTest` 19 个通过、0 失败、0 跳过，耗时 1.274 秒。证据 `evidence/build/rocksdb-sessions-junit.txt`。第二轮重审已启动，尚未落盘，所以不提交。

2026-09-25：第二轮重审 2 完成，HIGH_SEVERITY=1。它确认短复制不会再以正式日志名发布，但同长度、不同内容的旧 WAL 仍可能被 `matchesCheckpointLogs` 接受，随后删除 checkpoint 源文件。证据 `evidence/build/wal-round2-2.md`。重审 1 和 3 的进程 `1322802`、`1322806` 还活着，日志分别停在 09:23:12 和 09:28:14，最终文件未写出。

2026-09-25：迟到的第一轮重审 3 是 HIGH_SEVERITY=3，见 `evidence/build/wal-rereview-3.md`。它和 `wal-round2-2.md` 的 HIGH_SEVERITY=1 一起说明：只比长度不够，rename 失败不能把旧 WAL 留在活动目录，WAL 目录挪走失败时也不能直接放弃安装。本地已改为比对文件字节；同目录 rename 失败时删除活动 `*.log` 再写入核对过的 tail；目录级 rename 失败时改走原地安装。`RocksDBSessionsTest` 20 个通过、0 失败、0 跳过，耗时 1.355 秒。证据 `evidence/build/rocksdb-sessions-junit.txt`。按三轮上限不再自动开审查，因此不提交。

2026-09-25：第二轮重审 3 在字节比对写入后才落盘，`evidence/build/wal-round2-3.md` 仍写 HIGH_SEVERITY=1，理由是只按同名和长度接受旧 WAL。当前 `matchesCheckpointLogs` 已调用 `sameBytes`，所以这份结论对不上最新源码。重审 1 进程 `1322802` 仍可能在跑，它同样开始于这次修改之前。不把这两份当成最新差异的通过或失败结论，也不再开下一轮。

2026-09-25：基线补上未提交的 WAL 文件 `RocksDBStdSessions.java` 和 `RocksDBSessionsTest.java`，避免文档把工作区写成只有 channel refresh。已启动一份只读文档审查，进程 `1358552`，最终文件尚未写出。WAL 审查 `1322802` 仍卡在重连，没有新结论。

2026-09-25：文档审查 HIGH_SEVERITY=2，见 `evidence/build/doc-review-1.md`。P3 原先把边更新/删除和角色 403 算进未修改 Server，但那些证据在 overlay 或 mmapfix。已在当前未修改 Helm Server 上补做：标准和 Topling 边创建 201、更新 200、`since=2`、删除 204 后为空；无角色建图 403，没有新增 Kubernetes namespace。证据 `evidence/e109-edge-crud-fullsha.json`、`evidence/e109-role-denial-fullsha.json`。channel refresh 的 todo 时间线改为 07:40 的 14 个测试和 08:00 的 15 个测试，不再把它们写成同一份最终差异。文档重审未做，所以还不提交。

2026-09-25：文档重审完成，HIGH_SEVERITY=0，证据 `evidence/build/doc-rereview-1.md`。P3 的边更新和角色 403 已改由未修改 Server 证据支持。channel refresh 的 14 个测试和 15 个测试不再写成同一份差异。图空间 `closure_role_std` 和 `closure_role_top` 复查都是 400 Cannot find graph space。随后只提交这两份合同文件。
