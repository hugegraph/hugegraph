# ToplingDB Linux 实测执行合同

## 基线与环境

- 仓库 `hugegraph/hugegraph`；fetch/push 远端 `org`；唯一分支 `toplingdb`；PR #179。不新建分支或 PR，不 force-push，不直接合入 master。
- 执行工作树：`/home/soc-baidu/.codex/worktrees/f29e/hugegraph`。本地分支 `codex/toplingdb-linux-validation`，推送目标 `org/toplingdb`。主 checkout `/home/soc-baidu/github/hugegraph` 停在较旧的 `f9829899c`，不要在那里继续。
- 2026-09-25 03:29 刷新：本地 HEAD 与 `org/toplingdb` 都是 `e109012a07e2e9918f4a98d3faa23e21b93435d1`，ahead 0、behind 0。未提交的只有 `state.md`、`todo.md` 和本地证据；不要提交 `evidence/`、`.codex-handoff/`、RocksDB 数据、`tmp/` 或 `cacerts.jks`。
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

## 阶段

| 阶段 | 状态 | 依赖 |
| --- | --- | --- |
| P0 合同与修复 | `e109012a0` 含沙箱白名单、WAL 嵌套恢复和 `memtable_as_log_index: false`。工作区只剩文档 | 不把 overlay 镜像写成完整 SHA 构建 |
| P1 历史集群 | `9aba` 有 Pod 级证据；snapshot 失败一次；网络分区后置 | 不阻塞当前 SHA |
| P2 当前 SHA 镜像与 JNI | 两个 provider 的 e109 单机镜像和 JNI 已证明。HStore server 两个 tag 是同一镜像。新 namespace 尚未加载 | 不升级历史或现有 namespace |
| P3 当前 SHA 功能 | 两个 provider 的单机、1+1+1、3+3+3 都有当前 SHA 功能证据。单机 API 套件的 13 个失败都是 GraphSpace 在单机模式不支持。显式覆盖矩阵未完成 | 无套件占用 |
| P4 生命周期与 provider | e109 标准和 Topling 单机独立 WAL 快照都已回滚。混合、kill -9 仍只绑定旧镜像。HStore snapshot 仍是 500 | 不升级历史 namespace |
| P5 当前 SHA HA | 单节点 leader、副本和多数派已有证据。Compose 与 Helm 的差异已记录但未对齐。网络分区仍是单节点限制 | 不把 kind 写成物理多机 |
| P6 Loader | 导入 1000000/2098771，失败 0。扫描 Panic 的白名单类已进入 `closure-e109012a0`，但完整镜像上的百万点扫描尚未复测。overlay 结果不能勾选 | 不覆盖现有功能图 |
| P7 Benchmark | 未开始 | 核心功能未全部通过前禁止性能结论 |

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

- 动作：嵌套 data/WAL 路径改为失败关闭后再审一次，通过后才提交行为修复和合同。授权已包含。完成条件：`org/toplingdb` 含对应提交且不是 force-push。当前结果：2026-09-25 fetch 后本地 `b74befca17fa05d65a410a48572e4fd76ef3b93d` 仍比 `org/toplingdb` 的 `f9829899c3fd9e2b26b377949f2ab70ae00a2602` ahead 1、behind 0。行为修复和合同都还在工作区，不能提交。

## 下一动作

不要开始第二个重任务，也不要开始 benchmark。Topling 完整镜像构建由 systemd 用户单元 `hg-closure-top-image-e109012a0` 运行，MainPID 167352。第一次失败是上下文里的无许可证 `.source-revision` 触发 RAT；该文件已移出上下文。第二次已通过根模块 RAT（Unapproved 0），正在编译。上下文是 `/home/soc-baidu/.codex/validation-runtime/toplingdb-linux-closure/build-context-e109012a0`，源码 `e109012a07e2e9918f4a98d3faa23e21b93435d1`，tag `closure-e109012a0`，只构建 linux/amd64 的 pd、store、server-hstore、server-standalone。日志用 `journalctl --user -u hg-closure-top-image-e109012a0`。不要覆盖 `closure-std-cc14333f0` 或 `closure-top-mmapfix`。

HStore 图快照仍未实现。证据 `evidence/hstore-snapshot-gap.json`。不要把 Raft `/snapshot` 写成图快照通过。

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

下一步：HStore 图快照仍未实现。重连修复还要审查后才能提交。核心功能未完成前不开始 benchmark。
