# ToplingDB issue 归属与依赖索引

新会话拆分计划见 [pr-split-handoff.md](pr-split-handoff.md)。当前范围见 [state.md](state.md)，实测及下一动作只在 [linux.md](linux.md) 维护。
本表把 TP 新增/放大的问题与通用独立调查分开；发现新归因时按源码证据重新分类。

| Issue / 范围 | 归属 | 依赖与记录位置 |
| --- | --- | --- |
| #250/#251/#253 配置、目录、挂载、provider | TP 适配门禁。 | [Linux 清单](linux.md#验收清单)，源码变化时回归真实拒绝与数据不变。 |
| #254 truncate / #255 diagnostic | TP JNI 与 adapter 门禁。 | [Linux 清单](linux.md#验收清单)，需要真实身份；合成 CF 精确例外不覆盖其他错误。 |
| #249 WAL 扩展 457295ac8 | PR 新增共用代码，仍负责其回归。 | [Linux 故障证据](linux.md#真实服务与故障证据)；发布/冷启及 pending 并发已完成标准/真实 TP 对照，待证据与交付复核。 |
| #213 JNI 来源、许可、支持下限 | 正式发行独立后置，不阻塞当前任务。 | [Linux 发行核查](linux.md#jni-来源与正式发布)，由 producer 提供材料。 |
| #252 三轮标准/TP 对照 | 可选后置，不阻塞当前任务。 | 决定执行时另按固定 workload/配置/资源记录，不依赖通用在线恢复改造。 |
| #212 真实 native 生命周期 | 新的 TP 独有错误仍归适配；通用请求排空独立。 | [Linux 生命周期记录](linux.md#独立调查与隔离候选)，保留冻结首次失败。 |
| #248 clear 首次重启不可见 | 通用调查，旧失败保留。 | [Linux 对照记录](linux.md#独立调查与隔离候选)，不同源码/schema/部署不能覆盖旧失败。 |
| 历史 ProjectApiTest CI 回归（已修复） | 已由 master/单点实测确认本 PR 的 FINISHED 清理触发，属于当前任务。 | [CI 归因与计划](linux.md#2026-10-03-ci-归因与后续计划)，用户 -27 缺口已由红绿实测闭合；3aa44152 已发布到 PR #179，三人复审、标准55/TP5及最终双 provider 服务通过；源快照 1d976571 的 38 项 CI 已通过；拆分后需按新源码重验。 |
| master 同步 | 保留双方改动及四项修复的发布兼容性。 | [Linux 源码状态](linux.md#当前范围与源码)，不替代冻结 SHA 实测。 |

## 通用问题独立跟进

| 问题 | 现有归属与边界 |
| --- | --- |
| snapshot 缓存/全图协调、保存对象生命周期 | 相关机制在标准源码早已缺失；旧 epoch 拒绝断言仅在未提交候选实测，详见 [源码归因](linux.md#问题归属依据)。 |
| 跨 Server schema cache | [Apache #3235](https://github.com/apache/hugegraph/issues/3235)、[PR #3237](https://github.com/apache/hugegraph/pull/3237)、[组织 PR #236](https://github.com/hugegraph/hugegraph/pull/236)。 |
| clear 缓存、truncate 吞异常、同名图重建 | 分别沿组织 #242/#243/#244，schema PR 不覆盖全部行为。 |
| Store session 指标 / JVM 不退出 | 组织 #241/#211；标准也复现，旧补丁不夹带。 |
| 健康检查与 Gremlin 停机排空 | 开库拒绝后 versions 200、在途请求 500 属通用语义；详见 [独立调查](linux.md#独立调查与隔离候选)。 |
| Store 地址、路由/扫描、Loader | 组织 #245；Apache #3124/#3130，历史 channel refresh 保持独立。 |
| HStore 图快照与完整 HA/部署配置 | 组织 #246/#247；PR #235 单机备份不能当 HStore 图协议，单宿主不证明物理多机 HA。 |
| 临时端口 / HStore 联合索引 | 组织 #216/#217，按原 issue 跟进。 |

父汇总为 [#240](https://github.com/hugegraph/hugegraph/issues/240)，#214 仅作历史里程碑。
旧完整索引保留在 [同期归档](https://github.com/hugegraph/hugegraph/blob/4720da91b5a3c99b426c2ac035533ebc84d4e4cb/.goal-task/toplingdb-linux-closure/state-history-20261002.md#todomd-同期原始索引) 与
[todo-history-20260926.md](https://github.com/hugegraph/hugegraph/blob/4720da91b5a3c99b426c2ac035533ebc84d4e4cb/.goal-task/toplingdb-linux-closure/todo-history-20260926.md)；不将旧授权、状态或门禁作为当前安排。
