# ToplingDB Linux 任务入口

更新于 2026-10-02。本轮只将 ToplingDB（TP）适配自身引入或放大的问题列为验收门禁。
标准 provider 也复现且在 TP 引入前已存在的问题，沿 [todo.md](todo.md) 独立跟进；
本 PR 新增共用代码导致的回归仍由本任务负责。

## 阶段摘要

- 原 `toplingdb` 分支与 [PR #179](https://github.com/hugegraph/hugegraph/pull/179) 继续使用。
  已完成修复和此前文档已发布；当前代码同步提交为 `de541b11d66429b5c3c7272062330192e20de5aa`。
- `master` 新增 `176fb56dd747ef0f60a126cf721aa12d17627c31`（删除 RedirectFilter）；
  已以普通双亲合并保留双方改动；干净编译、34 项定向回归、真实 TP truncate 1 项及三人审查通过。
- Linux 冻结验收源码仍为 `9d797c7608e244f03436ce11294d9bd72aba4d2d`，38 项 CI 成功。
  构建、标准回归、真实 TP JNI、三组件产物、启动和真实服务证据已取得。
- #249 的 WAL 扩展为本 PR 新增共用代码，其复制/发布失败和损坏保护仍需收口；
  #213 正式 JNI 来源、许可与支持基线未完成；#252 标准/TP 三轮性能对照尚未执行。
- 同进程缓存、saved schema/epoch、全图一致性与通用请求排空归独立问题。
  未提交 journal/gate 候选留在隔离目录，不因这些通用缺口扩大当前 TP 任务。
- 用户已将额度约束改为至少保留周额度 7%，以工具实际剩余额度决定执行安排。

## 执行与交付边界

- 详细 Linux 状态、实测身份、证据和下一动作只维护 [linux.md](linux.md)。
- 使用 `gh` 进行 GitHub 核对与操作；禁止直接调用 Git 或通过别名隐藏 Git。
- 同一时间仅运行一个重任务、一批测试服务；启动脚本只在专属测试容器执行。
- 使用干净、身份明确的源码与产物，数据/WAL/provider 根隔离；不复用历史 `f29e` 补丁。
- 修复涉及生产行为或持久化数据时，由三名独立只读审查者检查，并在修正后复审和实测。
- 原始日志、数据库、镜像、大文件和凭据不提交；首次失败保留，重试不能覆盖。
- 代码与证据文档分别提交，发布前核对远端变化；不自动合并 PR 或关闭未完成 issue。

## 按需导航

| 要查看的内容 | 权威位置 |
| --- | --- |
| Linux 当前验收与证据 | [linux.md](linux.md#验收清单) |
| issue 归属与依赖 | [todo.md](todo.md) |
| Linux / 通用经验 | [lessons-linux.md](lessons-linux.md) / [lessons.md](lessons.md) |
| 此前 Mac 开发 | [mac.md](mac.md)，Linux 默认不加载 |
| 本次精简前完整记录 | [Linux 历史](linux-history-20261002.md) / [阶段与索引历史](state-history-20261002.md) |
| 更早档案 | [证据索引](evidence-index.md) |

最新 PR head 与检查结果通过 `gh` 核对；文档整理不改变代码和产物的适用版本。
仓库 [AGENTS.md](../../AGENTS.md) 的工程约束继续适用。
