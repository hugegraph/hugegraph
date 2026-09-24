# ToplingDB Linux 收敛：跨机器交接目标

## 固定协作面

- 仓库：`hugegraph/hugegraph`
- 分支：`toplingdb`
- 单一 PR：<https://github.com/hugegraph/hugegraph/pull/179>
- 不使用 Codex 官方 handoff，不新建并行 PR。本次仅因把交接文档从 `.codex-handoff` 迁入默认 `.goal-task` 目录而执行一次性 force-push；后续正常同步不再 force-push。
- 开发机负责产品代码、测试和必要的代码文档；本机负责 kind/Helm/真实服务、benchmark 和实测文档。
- 双方都通过同一个 `toplingdb` 分支同步。代码提交和实测文档提交都进入 PR #179。

## 本轮目标

完成 ToplingDB 在 HugeGraph 1.7.0 分支上的收敛验证，并把未完成的边界明确留给下一轮：

1. 保持标准 RocksDB 与 Topling runtime 隔离，禁止静默 fallback。
2. 完成 Server、PD、Store、HStore 的真实生命周期、停止/重启、持久化与 provider mismatch 验证。
3. 完成 1+1+1 和 3+3+3 拓扑的功能、恢复和 HA 验证；Ready 只算部署前提，不算 HA 证明。
4. 完成 LAW Twitter-2010 固定子集的 Loader 导入、邻接、重启与失败重试核对。
5. 在核心功能通过后，再做同 SHA、同资源的标准/Topling benchmark 对照；未跑完前不写性能收益结论。

## 验证绑定规则

每一轮实测至少记录：

- 被测源码 commit SHA；
- 镜像 tag、image digest 或 OCI revision；
- 实际加载的 JNI 文件 hash；
- 拓扑、namespace/Compose project、命令和退出码；
- 测试计数、skip 原因、失败日志位置和未覆盖边界。

历史证据若没有绑定当前 commit，只能作为线索，不能外推为当前提交通过。

## 当前不可变边界

- 不直接合入 master。
- 不清理或干扰已有的 kind、port-forward、HBase 或任务外资源。
- 只提交 `.goal-task/toplingdb-linux-closure/` 下的脱敏 Goal、状态、TODO、证据索引等文档；不提交该目录的 `evidence/`、`.codex-handoff/` chunks/archive、RocksDB 数据、tmp、原始大日志、镜像或 benchmark 原始大文件。
- 不把标准 RocksDB 结果写成 Topling 结果，不把 1+1+1 结果写成 HA 结果。
