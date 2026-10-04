# 当前执行项 · 2026-10-05

用户已恢复，入口见 [handoff-current.md](handoff-current.md)。不沿用旧四级 stacked PR 的交付范围。

| 项目 | 当前结果 | 剩余工作 |
| --- | --- | --- |
| P1 / master | 已合入并同步核对 | 新 PR 始终核对最新 master；不追加 P1 |
| TP 核心 #266 | 基于 master 独立建立并及时推送；完整格式、编译、聚焦测试、三普通发行包通过 | 最终真实 PD/Store TP、Server 标准/TP、HStore 三组件验收；更新 body、CI 与 review 状态 |
| 生命周期 #261 | 普通 scan、receipt、half-close/client、cleanup 日志节流已验证推送；前三及节流评论已 resolve | ordering 争议留回复；active query/scan/TTL + 阻塞 callback 的真实服务关闭验收；确认可供用户 review 时及时通知 |
| 恢复 #263 / 新替代 | 原四评论已解决；已确认独立 follow-up | UUID checkpoint 失败清理、savedWal 损坏校验；从 master 提取干净范围，不发布回归 source008 |
| 旧 integration #264 | 将由 #266 及专用 distributions/Docker 后续替代 | 逐评论映射，不把移出范围写成已修复；保留另一会话 d577 新修复 |
| 专用 distributions → Docker | 用户认可独立拓扑 | 在核心之后小 PR，避免重新继承通用大改造 |
| 网站 #510 | 两语言指南已收窄匹配 #266，完整内容和必要验收通过推送 | 复核 comment 及最终配对交付，不 claim release/JNI 合规 |
| JNI 许可/正式发布链 | 用户明确后置 | 不阻塞本轮功能验证，不称已完成审查 |
| 证据/资源/context | 旧冗余已清理；失败 native 材料保留 | 成功运行清理自己的容器；收敛当前记录并同步专用 records 分支 |

已完成/正在运行的 source/hash、产物、命令和证据位置只在 handoff-current 与本机 evidence 保存，不将大日志、数据库或流水账混入功能 PR。
