# 当前交接状态

## 分支与提交

- 交接文档路径：`.goal-task/toplingdb-linux-closure/`；原始证据仅保存在同目录下被忽略的 `evidence/`。
- 目标分支：`toplingdb`
- 目标 PR：<https://github.com/hugegraph/hugegraph/pull/179>
- 本轮产品代码提交：`0d2d334c5214b2dc467c9368b28035135a9d2386`
- 提交标题：`fix: harden Topling validation and store shutdown`
- 本地 worktree 分支名：`codex/toplingdb-linux-validation`；push 目标是远端 `org/toplingdb`。
- 本次为修正交接文档路径执行一次性 force-push；后续远端移动时先 fetch、比较并正常整合，不再 force-push。

## 本轮提交覆盖

- Store shutdown barrier、scan response、ContextClosedListener 和 TTL cleaner 生命周期收敛。
- `ParallelScanIterator` 的取消、队列和 ordered scan 锁释放修复。
- Store JRaft 1.3.14 版本对齐及 snapshot/raft 测试适配。
- Server graph index/config path 候选修复及对应回归。
- Cluster 启动、停止、中断、清理和 logging binding 定向测试。
- Commons scoped IPv6 校验修复。
- PD、Server、Store、Cluster 的 README/测试入口和 stop 脚本可执行位。

## 已验证结果

本轮新增锁泄漏回归在提交树上通过：

- `ScanShutdownTest`：10 tests，0 failures，0 errors，0 skipped。
- 命令：`mvn -pl hugegraph-store/hg-store-node -am -Dtest=ScanShutdownTest -Dsurefire.failIfNoSpecifiedTests=false test -ntp`
- 该结果绑定 `0d2d334c5214b2dc467c9368b28035135a9d2386`；它不替代完整 Store、HStore、Topling 或 HA 验证。

历史标准 RocksDB 证据（本地 evidence，不作为当前提交的自动通过）：

- standalone CoreTestSuite：818 tests，0 failures/errors，42 skips。
- standalone API：161 tests，0 failures/errors，14 skips。
- Helm 1+1+1 API：155 tests，0 failures/errors，50 skips。
- PD common 83、core 104（2 skip）、client 83、rest 22。
- Commons 351、RPC 24、Struct 8。
- Store common 2、rocksdb 3、raft 9、server 6；补充 audit client 50、core 22。
- Helm 3+3+3：9/9 pods Ready，JNI hash 已采样；HA/恢复行为未完成。

## 已知边界

- 当前所有真实服务结果主要来自标准 RocksDB；Topling 服务验收仍未完成。
- Store shutdown 没有总 deadline 是刻意 fail-closed，避免 DB/iterator 仍在使用时关库。
- `ScanBatchResponse3` executor rejection 和 drain 期间 `getState()` 返回 `UNAVAILABLE` 是后续 triage，不阻塞本轮 push。
- 本地 active kind namespace 和 port-forward 不要被另一台机器清理或复用为破坏性测试目标。
