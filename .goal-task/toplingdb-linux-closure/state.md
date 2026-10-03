# ToplingDB 当前入口

更新于 2026-10-03。原 Linux goal 按用户要求暂停；下一任务是拆分 PR #179。

## 默认阅读范围

新会话先读仓库 AGENTS.md、本页和 [拆分交接](pr-split-handoff.md)。
不要递归加载本目录、两套环境记录或历史文件。其余材料仅在处理对应问题时读取。

| 文件 | 唯一职责 | 何时读取 |
| --- | --- | --- |
| [pr-split-handoff.md](pr-split-handoff.md) | 拆分边界、依赖、验证及合入顺序 | 拆分任务启动时 |
| [todo.md](todo.md) | 未完成问题的归属和 issue 索引 | 判定阻塞或安排后续时 |
| [linux.md](linux.md) | Linux 源码/产物身份、实际结果与证据位置 | Linux 复测或核查结论时 |
| [mac.md](mac.md) | Mac 开发、容器测试及其适用边界 | 在 Mac 执行或查对应证据时 |
| [lessons.md](lessons.md) | 已验证的操作陷阱，含平台分节 | 执行相关操作前按节读取 |

## 当前事实与决定

- 源分支 `toplingdb`、[PR #179](https://github.com/hugegraph/hugegraph/pull/179) 保留，不重写历史。
- 最后生产代码为 `3aa44152e8e13749d82ba58449b93254384ed0ed`；修复已发布，标准/真实 TP 实测通过。
  源快照 `1d976571` 的 38 项 CI 已全部成功；后续 head 和拆分后的 CI 必须重新查询。
- 分支建在 org，子 PR 默认先向 `hugegraph/hugegraph master` 提交并实测，最终合入 Apache master。
  每个 PR 交用户 review，收到对应确认后才合入；具体流程见拆分交接。
- TP 适配及本 PR 新增共用代码的回归仍负责；此前已有通用问题独立跟进。
  性能 #252 可选，正式 JNI 发布链 #213 独立后置，均不阻塞本轮 Linux 任务。
- 用户允许用完剩余额度；版本库操作使用 gh。重任务串行，启动清理只在专属容器执行。

## 历史取证

当前目录只维护上述六份文件。历史快照、旧入口和旧证据索引保留在
[精简前固定提交](https://github.com/hugegraph/hugegraph/tree/4720da91b5a3c99b426c2ac035533ebc84d4e4cb/.goal-task/toplingdb-linux-closure)。
仅调查具体旧结论时按文件读取；旧授权、资源状态和失败状态均不作为当前计划。
原始日志、数据库、镜像仍留在原专属 evidence/data 目录，本次整理不删除它们。
