# ToplingDB Linux 实测执行合同

## 基线与环境

- 仓库：`hugegraph/hugegraph`；远端 `org`；分支 `toplingdb`；PR #179。
- 工作树：`/home/soc-baidu/.codex/worktrees/f29e/hugegraph`；本地分支 `codex/toplingdb-linux-validation`，推送目标 `org/toplingdb`。
- 合同正文提交：`3d64b67de2aec6299addd63cd26bdd7678d225ee`。恢复时以 `org/toplingdb` 的 HEAD 为准。产品修复提交：`0d2d334c5214b2dc467c9368b28035135a9d2386`。
- Kubernetes：`KUBECONFIG=/home/soc-baidu/.kube/config`，context `kind-kind`。不使用 k3s，不执行全局清理。
- 2026-09-24 仍在运行的历史集群：`hg-closure-standard-111`、`hg-closure-standard-333`，镜像 `closure-std-9abae9dbaaa1`，源码快照 `f2f300356-worktree-9abae9dbaaa1`。它们不是当前 SHA 的通过证据。
- 2026-09-24 goal 已启动。当前波次只测 `hg-closure-standard-111` 的停止、重启和已确认写入，不改动 3+3+3。

## 活动真相与优先级

最新用户确认 > [AGENTS.md](../../AGENTS.md) 与产品设计 > [todo.md](todo.md) > 本文件。

- 本文件是唯一执行合同和恢复入口。
- [todo.md](todo.md) 独占分项状态、等待和后置标记。
- [local-status.md](local-status.md) 与 [evidence-index.md](evidence-index.md) 只作本地证据说明。
- 根目录 `goal.md` 已退出活动真相。
- [lessons.md](lessons.md) 仅在形成可复用证据时更新，不记录日常进度。
- 原始证据只放被忽略的 `evidence/`，不提交。

## 阶段与门禁

| 阶段 | 当前状态 | 完成条件 |
| --- | --- | --- |
| P0 合同刷新 | 文档已刷新，随本提交推送 | `state.md` 与 `todo.md` 进入 `org/toplingdb`；goal 尚未启动 |
| P1 历史标准集群补证 | Store 多数派 Pod 删除已有证据；网络分区后置，snapshot 仍失败 | 1+1+1 生命周期、3+3+3 功能与 HA 有日志和业务证据；结果绑定 `9aba`，不算当前 SHA |
| P2 当前 SHA 构建与 JNI | 标准镜像构建进行中 | 标准与 Topling 镜像隔离，实际 JNI 映射证明无静默 fallback |
| P3 当前 SHA 功能 | 未开始 | 单机、1+1+1、3+3+3 两个 provider 的功能证据 |
| P4 生命周期与 provider | 未开始 | 停止重启、持久化、truncate、snapshot/restore、混合 provider 与错误复用拒绝 |
| P5 HA | 未开始 | 副本退出、切换、分区、多数派恢复、写入一致性和恢复时间 |
| P6 Loader | 数据已准备，导入未开始 | 固定百万点子集导入、重试、计数、邻接、重启 |
| P7 Benchmark | 等核心功能通过 | 同 SHA、固定资源、至少 3 轮；此前不写性能结论 |

分项计数和依赖以 todo.md 为准。P1 可先使用现有集群；P2 起必须使用新 namespace，不在 `9aba` namespace 上原位升级。

## 执行规则

- 用户可授权的确认范围内操作均已预授权，包括本地改动、Git、推送、PR、测试和审查响应。不得因授权提示再次询问、等待、后置或把整体标为 blocked。不得伪造凭据或能力，不得越过安全边界，不得做范围外操作，不得在证据、日志、状态、提交或 PR 中保存凭据值。
- 每一波绑定源码 SHA、镜像 digest、实际 JNI hash、namespace 或 Compose project、命令、退出码、计数、skip 和未覆盖边界。
- 同一时间只运行一个重任务。Maven 全量、镜像构建、Helm 变更和 Chaos 不叠加。
- 单项最多尝试 3 次。仍失败则记录错误、证据、恢复动作和依赖，在 todo.md 后置该项及其依赖，继续独立项。
- 等待构建、下载或集群时，只做不冲突的只读准备、失败分析和文档维护。
- 文档变更由 1 名独立审查者复核。行为变更由 3 名独立只读审查者复核，修复后重审受影响部分，最多 3 轮；仍未通过则后置，不宣称完成。
- 验证和适用审查通过后提交。每完成一波或一个阻塞修复，就把代码和脱敏文档推到 `org/toplingdb`。不 force-push，不新建分支或 PR。
- 本机可以修复可复现的明确缺陷，但必须带回归测试，不做无关重构。Store shutdown 保持 fail-closed。
- 会话交接或配额等待前更新本文件的门禁、最新提交、证据、等待项和下一动作。
- 只有全部剩余项经过恢复、重排和独立工作后仍共同依赖同一个逻辑冲突、安全边界或强制性外部依赖时，才把整体标为 blocked。

## 已知边界

- 单节点 kind 只能证明逻辑分区、Pod 恢复和持久化，不能证明物理多机或真实网络分区容错。
- Chaos Mesh 安装状态在合同刷新时未复查。缺失时先做 Pod 级 HA，网络分区项后置。
- macOS ARM/Intel Cypher 必须由对应平台 CI 验证，本机结果不能替代。
- 不提交 `evidence/`、`.codex-handoff/`、RocksDB 数据、tmp、原始大日志、镜像或 benchmark 原始大文件。

## 下一动作
当前 SHA 标准镜像构建已启动，绑定 `cc14333f0da8b624ed618cf3bdefcd28658764e3`。tag 为 `closure-std-cc14333f0`，只构建 linux/amd64 的 pd、store、server-hstore、server-standalone。上下文是干净归档加未改动的 `docker/`，因为 `docker/` 在 `.gitattributes` 里是 `export-ignore`。日志是 `evidence/build/current-sha-standard-image-build.log`，进程 3441890。不要再启动第二个 bake，也不要升级两个 `9aba` namespace。Topling 镜像等标准构建结束后再开始。
