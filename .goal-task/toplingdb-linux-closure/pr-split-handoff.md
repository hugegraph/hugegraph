# ToplingDB PR 拆分与 Apache 上游交接

更新于 2026-10-03。本文是新会话的执行入口，不要求读取原聊天记录。
目标是把 [hugegraph/hugegraph PR #179](https://github.com/hugegraph/hugegraph/pull/179)
拆成约四个容易审查、能够逐个安全合入的 PR。分支创建在 **hugegraph/hugegraph（org）**，
子 PR 默认先指向 **org 的 master** 做实测与 review；最终合入端为 **apache/hugegraph 的 master**。

## 用户已确定的边界

- 每个子 PR 完成构建、相关实测与 CI 后，**逐个交给用户 review；收到针对该 PR 的确认后才合入**。
  仓库写权限不等于合入授权。不要启用 auto-merge，也不要直接推送 Apache master。
- 分支保存在 org，优先独立基于 org master，并核对与 Apache master 的差异。
  只有证实存在代码或行为依赖才使用 stacked PR；转向上游时重新确认 base、补丁范围和 CI。
  不追求四个 PR 行数相等，不允许先合入已知不安全的中间版本再由后续 PR 修补。
- 保留原 `toplingdb` 和 PR #179 作为整合与审计参考；不 force-push、重排其历史或提前关闭它。
  子 PR 使用新分支，发布前检查是否已有其他会话创建的拆分 PR，避免重复工作。
- GitHub 和版本库操作全部使用 `gh`；不要直接调用 Git，也不要通过别名隐藏 Git 命令。
  `gh repo clone`、`gh pr checkout` 及 `gh api` 的内置能力可以使用。
- 性能 #252 可选；正式 JNI 发布链 #213 独立跟进，不扩大为本轮必须完成的发行流水线。
  但 Apache 子 PR 中的依赖来源、许可证标注和实际获取方式仍须清楚，不能用旧验收免除其审查。
- 原 Linux goal 已按用户要求暂停。新会话开展拆分任务，不自动恢复旧 goal。
  用户已取消 7% 周额度保留限制，允许用完剩余额度；不得擅自兑换额度重置。

## 固定参考与实时状态

| 对象 | 已核对的参考 |
| --- | --- |
| 源 PR | hugegraph/hugegraph #179，head 分支 `toplingdb`。源 PR 和初始子 PR 均指向 org master；子 PR 最终再向 Apache master 提交。 |
| 本次分析源码/文档快照 | `1d97657194a908e6701779064f6b8e101570e10d`；本交接发布会再增加文档提交。新会话必须重新读取源 PR head 并固定工作 SHA。 |
| 最后生产代码修复 | `3aa44152e8e13749d82ba58449b93254384ed0ed`，后续到上述快照仅文档变化。 |
| Apache master | 本次核对为 `176fb56dd747ef0f60a126cf721aa12d17627c31`，新会话重新核对，不能假定没有前进。 |
| 源 PR CI | **1d976571 的 38 项检查已全部成功**。旧“33 项排队”记录已经过时；新 head/子 PR 的 CI 必须重新查询。 |
| 历史 Linux 冻结基线 | `9d797c7608e244f03436ce11294d9bd72aba4d2d`；不是新子 PR 的开发 base。 |
| 临时 auth 分支 | `toplingdb-auth-cascade-20261003` 指向已同步进原分支的修复，是临时隔离分支，不是四个上游子 PR 之一。 |

旧 API 失败已经归因并修复：master 的项目删除重建正常，源 PR 的 FINISHED 清理使 schema
缓存变冷，隐藏关联边未被枚举，留下 access/belong。保留清理并修正级联删除后通过。
**必须将请求清理、必要的 schema/index 修正和 auth 配套回归放在同一完整单元。**
曾用 `primitive()` 排除负 ID 范围的候选 `43b88c1` 不完整：真实用户标签为 -27，仍会漏删
belong。最终实现仅排除特殊 OLAP 标签；不要恢复旧候选或仅摘取不完整的中间提交。

## 建议四个功能单元

以下是待验证的逻辑划分，本会话尚未构造四个子分支，也没有任何“每层 CI 已通过”的结论。
路径均相对仓库根；同一个文件可能必须按修改块拆分。

| 单元 | 范围与源码入口 | 独立合入条件 |
| --- | --- | --- |
| 1. 标准运行时依赖 | 根/PD/Server/Store POM、Store `RocksDBMetricsConst`、依赖清单及 LICENSE/NOTICE。PD RocksDB 6.29.5、Store 7.7.3 → 8.10.2，Server 原已为 8.10.2。 | 默认 RocksDB 构建、服务及升级兼容正常；新建库重启不能替代旧数据打开验证。JRaft 1.3.11/1.3.13 → 1.3.14 是否必要须另证，非必要不夹带。 |
| 2. 事务与关闭生命周期 | Server `HugeFactory`、`StandardHugeGraph`、`BackendSessionPool`、REST `ApplicationConfig`、auth `ContextTask`、必要的 GraphTransaction/GraphIndexTransaction 修正；Store grpc/扫描与关停修复。 | 标准模式独立通过 Core/API、关闭及并发回归；项目和用户关联删除均正确，其他对象保留。不要把 auth 修复与触发它的清理接口拆开。 |
| 3. Snapshot/WAL 安全 | Server rocksdb 模块的 `RocksDBSnapshotRestore`、`OpenedRocksDB`、`RocksDBStdSessions`，`RocksDBStore` 的恢复/锁相关修改。 | 标准 provider 可独立验证成功与故障恢复、独立/嵌套 WAL、材料保留、锁/并发；不承诺尚未实现的全图原子恢复。 |
| 4. 完整 TP 接入 | provider 选项、TP truncate 差异、JNI 选择、ABI 与所有数据/WAL 根预检、三组件包、启动脚本、Docker、对应 CI 和产品文档。 | 合入时 TP 是完整可选能力，默认标准包不混入 TP；真实 JNI、拒绝路径、服务/重启/关闭通过。 |

预期在核对两边 master 后，独立准备单元 1～3，先以 org master 为子 PR 的默认 base。
单元 4 依赖必要前置；org 已合入的前置若尚未进入 Apache，上游仍须显式处理这条依赖。
发现实际依赖时再增加 stack 边，并在 PR 描述中写出 base、前置 PR、合入顺序和重新验证范围。
不要仅为“平行”复制公共实现，也不要仅为“stack”制造不必要串行等待。

特别注意以下跨文件依赖：

- `RocksDBStore` 同时含 provider/truncate、opened/session 和恢复锁修改，不能整文件随意归入一层。
- `RocksDBSessionsTest` 同时含原有测试、新 WAL 用例和 TP adapter 分支测试，需要按测试目的分配。
- `OpenedRocksDB` 的关闭和恢复锁移交是配套实现，锁释放顺序不能拆散。
- 当前标准 runtime CI 也调用 `preload-topling.sh`；workflow 与它依赖的脚本/配置必须在同一层可用。
- `testAdapterToplingTruncateWithMultipleKeys` 在标准 JNI 下也能检查 Java 分支；名字不证明已加载 TP。
  真正 TP 验证另核唯一 JNI JAR、实际 native maps 和哈希。

## 规模与保留范围

在 1d976571 相对 176fb56dd 的快照中：209 文件，+16,167/-646 行，228 个提交。
生产 Java 34 文件 +1,329/-311；运行脚本 18 文件 +1,466/-49；测试 62 文件 +6,016/-181；
构建/CI/配置 41 文件 +1,881/-97；文档/许可证文本 42 文件 +5,475/-8；另有 11 张图片和 1 个 JNI JAR。
测试包括 44 个 Java 文件和 18 个 Shell 文件，不全是 TP 专属。

四单元新增量曾粗估约 30、2,300、1,600、8,500 行；不是配额或最终补丁大小。
第一个还需补升级验证，最后一个含大量启动/发行测试与文档。不要为了接近估算而遗漏实现。
`.goal-task/`、`.specs/` 和 `docs/toplingdb/images/` 合计约 3,666 行历史/规划/绘图材料，
应保留在审计来源中，默认不搬入 Apache 子 PR。产品操作说明、必要许可证和有效测试必须随实现。
实验 JNI JAR 约 9.1 MiB，图片约 12.6 MiB，均未计入文本行数；交付方式单独给用户 review。

## 已有验证与不能外推的结论

实测结论、运行产物身份与原始证据位置只维护在 [linux.md](linux.md)，Mac 对照见 [mac.md](mac.md)。
源码重组或变更 base 后，按实际影响复测；源 PR 绿灯不能证明拆分后的中间树安全。
已有新库结果不证明 PD/Store 旧数据升级或降级兼容，这是单元 1 需要补齐的边界。

新环境先核架构、ABI、CPU 与权限，不能将 Linux x86_64 native 身份套到其他架构。
旧 evidence 绝对路径和 /tmp 脚本可能不可用；用仓库测试、CI 与 linux.md 重建必要验证。
未取得原始材料时标明“旧记录”，不宣称独立复核。首次失败保留，大日志/数据库/镜像不提交。

## 新会话启动与执行顺序

1. 默认只读根 `AGENTS.md`、state.md 和本文；linux/mac/todo/lessons 按待拆分范围取用，
   再读相应模块 AGENTS 和 CONTRIBUTING。不递归加载任务目录；历史材料仅在具体取证时从固定提交读取。
   检查已有本地改动/分支/资源及 org 和上游现有 PR，不覆盖其他会话的工作。
2. 用 gh 刷新源 PR、org master、Apache master 和检查结果，记录不可变 SHA。需要新源码时使用完整 checkout；
   GitHub archive 的 export-ignore 曾漏掉 install-dist 文件，不能直接拿不完整归档构建。
3. 生成逐文件/修改块归属清单：子 PR、理由、依赖、配套测试/文档、未纳入及原因。
   先验证 1～3 可否独立；明确真正的 stack 关系，不按旧提交顺序机械 cherry-pick。
4. 在 org 创建新分支，构造第一个完整子 PR 并干净验证，再逐步推进。
   默认先向 org master 创建 draft PR 用于实测与 review；具备提交条件后再向 Apache master 提交。
   上游 PR 可复用 org 分支，但必须重新检查上游差异与 CI，不能把 org 的绿灯直接当成上游通过。
   每个子 PR 标明源码身份、实际执行/skip 计数、标准/TP 覆盖边界、风险及与后续 PR 的关系。
5. 生产行为或持久化修改由三名独立只读审查者检查，修改后复审与实测。重任务/测试服务串行，启动清理只在专属容器。
   发布前按仓库要求运行格式、干净编译及相关测试；Commons 需显式启用测试。不要为绿灯删除断言或放宽例外。
6. 每个子 PR 满足检查后，向用户提供仓库、PR、head 和验证结果，等待该 PR 的合入确认。
   org 与 Apache 是不同的合入操作，不能将一边的确认视为另一边已获授权。
   确认后使用正常合入流程，记录 merge SHA；再刷新依赖 PR 的 base、差异和 CI。禁止绕过必需检查/使用管理权限强合。
7. 全部所需单元合入后，核对最终 Apache master 的功能集合与源 PR 意图，执行标准/真实 TP 整合验证。
   原 PR #179 和未完成 issue 的关闭/清理再向用户确认，保留审计索引。

首次核对命令示例（在适合的新目录运行 clone）：

```bash
gh pr view 179 --repo hugegraph/hugegraph --json headRefOid,baseRefOid,statusCheckRollup
gh api repos/hugegraph/hugegraph/commits/master --jq .sha
gh api repos/apache/hugegraph/commits/master --jq .sha
gh api --paginate 'repos/hugegraph/hugegraph/pulls/179/files?per_page=100'
gh repo clone hugegraph/hugegraph hugegraph-source -- --branch toplingdb
```

新环境先检查 gh 版本和认证。org 内的初始 PR 可使用
`gh pr create --repo hugegraph/hugegraph --base master --head <branch> --draft`。
最终提交上游时，当前 gh 的 `pr create --head <org>:<branch>` 帮助提示不支持组织 owner；
可用 `gh api` 创建 cross-fork draft PR，目标为 `apache/hugegraph`、base 为 master、
head 为 `hugegraph:<branch>`，并核对返回的 base/head repo。
需要保存提交时可使用 gh Git Data API 创建 tree/commit/ref；更新 ref 必须核对预期父提交并 `force:false`。
不要把新环境的工具限制自动等同没有仓库写权限，更不要绕过其执行策略。

## 需要提出具体方案后交用户判断的事项

- 若四单元无法各自独立构建/合入，说明真实依赖以及增加 stack 或调整数量的代价，再决定拆分边界。
- 若旧数据升级验证失败、需不可逆迁移、改变默认行为或删除已有支持，先提供复现和迁移/回滚方案。
- JNI 获取与交付方式、必要的上游网站文档配套 PR，以及不能从来源解释的许可证标注，均应在相关子 PR review 中明确。
- org 分支及初始 PR、Apache 最终合入端、逐 PR 确认已经由用户决定，不重复询问。
