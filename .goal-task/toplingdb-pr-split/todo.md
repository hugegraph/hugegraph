# 当前执行项 · 2026-10-05

用户已恢复，入口见 [handoff-current.md](handoff-current.md)。不沿用旧四级 stack 范围。

| 项目 | 当前结果 | 剩余工作 |
| --- | --- | --- |
| P1 / master | 已合入并同步核对 | 新PR核最新master，不追加P1 |
| 核心 #266 | 三组件/普通包/实际std+TP服务及HStore验收通过，独立review完成 | 最新两运行review已修复验证推送并resolve；等当前head CI和用户review |
| 规范 #268 | 从266拆出，2文件直接master，policy评论resolve | 全格式覆盖修正已推并回复；GraphQL额度恢复后resolve最后一条，独立评审/合入 |
| 生命周期 #261 | 普通scan/query/重启、blockedcallback timeout保PID+释放完成通过；已解决comments resolve，ordering争议回复 | TTL严格重叠未验证；master既有codec问题独立跟进；门槛完成后提醒用户review合入 |
| 恢复 #267 | clean master12文件，所有新修复审查、wholecompile/native/crash/physicalparentbind通过，已推 | 当前CI、review；父根挂载及旧pending升级约束须评审 |
| 旧恢复 #263 | 已close由267替代，checkpoint评论resolve，分支保留 | 无当前合入入口 |
| 旧集成 #264 | OPEN拆分源，原20thread逐条回复去向；其他协作持续提交 | 保留并发内容；专用包/Docker分别迁出，不把后置建议称已修复 |
| 专用 distributions → Docker | 用户认可小PR独立拓扑 | 核心后逐个交付，不重新绑通用改造 |
| 网站 #510 | 匹配核心，中英/链接/严格fresh产物/浏览器验收，剩余两comments resolve | 配对review/merge，不称release/JNI合规完成 |
| JNI 许可/正式发布 | 用户后置 | 独立后续，不称已完成 |
| 资源/context | 已审计删旧冗余和恢复targets/诊断停止容器，失败数据完整保留 | 核心最终targets已收敛，推最新专用records |

源/hash、命令及证据只保存在当前handoff和本机evidence；大日志/数据库不进入功能PR。
