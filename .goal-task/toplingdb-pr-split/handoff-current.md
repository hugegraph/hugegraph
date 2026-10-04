# TP 当前接手入口 · 2026-10-05

## 决策与边界

用户恢复后继续执行。保留 Server/PD/Store 三组件 TP 接入；通用事务/查询/scan/TTL 生命周期和中断恢复独立后续。master → TP core → 专用 distributions → Docker/Compose；master 独立分支承载生命周期与恢复。允许新 PR 代替旧 stacked PR，禁止自动 merge、force push。版本库操作 gh-only。代码 120 列，Markdown 160 附近软换行或整段不换行。

## 已推送

- org master 91fd925d4cdcfb6503a5bca3244909dfcf18d57c；P1 已合。
- 新核心 #266：task/topling-core-20261005，直接基于 master，draft；最新 027cf8dbeab83e2ff71b421f98413a774fc6348c。显式可信 JNI prepare/选择、三组件实际 provider 校验、Server TP truncate、最小必要 native owner 释放。51 文件，约 +2.1k，包含聚焦测试/配置/文档。没有通用 P2/P3 大框架。
- #261 最新 e72b5fcdae07519ae394556986ae7e7734ce3e30：普通 scan、receipt、half-close/client、cleanup 日志节流均编译/相关 tests/独立审查通过并发布。正常完成/receipt/half-close 线程已 resolve。4179347742 已修复并回复，新 resolve 因 GraphQL rate limit 尚待确认。ORDER_WITHIN_VERTEX 4179028917 已回复技术依据，留 open 等反例。真实 active query/scan/TTL + 阻塞 callback 停机验收仍需补，不能宣布可合入。
- #263 f8b29c82f1319894e931218c816165c04f42754a：原四评论已 resolve；新增 checkpoint 清理评论 4179176067 未解决。savedWal 损坏校验时机未解决，source008 手工路径解析有回归不得发布。须从 master 提取干净恢复分支。
- #264 d577cea9bee1379bfe4a2a34c3c76d7700ed3468：存在另一会话新增 PD 生命周期修复，保留，不覆盖。旧 stacked integration 将由 #266 替代；尚未关闭。
- 网站 apache/hugegraph-doc#510 最新 7d24a523661287119ea177b63846f199abf4a3e6，两语言指南已收窄到普通 distributions 的显式 TP 接入，配对 #266；完整 tracked tree/链接/静态和浏览器搜索验收通过并发布。

## 源与验证

E=/Users/zhu/github/hugegraph-topling-split-evidence；C=E/resume-20261005/tp-core。
C/source 为完整 2715 文件快照，非 Git worktree；master tar 漏掉 export-ignore tracked 文件的问题已补齐并验证。唯一差异待所有运行结束再同步：C/source/docs/toplingdb.md 仍编译时旧文档；远端 docs 已更新，最终 payload 在 C/docs-final-payload/docs/toplingdb.md。运行中禁止更改完整冻结 source。

build-001：完整 format/whole clean compile、Commons/Server Sessions/PD native owner+readiness、三标准 packages PASS。
build-004：完整 format/whole clean compile、Server 租约三项/Store heartbeat 四项 tests、三 packages PASS。
build-005：PD 去掉被 TP native 拒绝的 metadata dbpath 尾部 /，同物理目录；完整 format/whole clean compile/PD owner+KV tests、三 packages PASS。source_changes/formatchanges 均为空。最终 archive 路径和 SHA 见 build-005/receipt.json。

实际 Linux amd64 Docker 在 Darwin arm64 模拟，不可称原生 x86 主机验证。使用显式固定外部 JNI+SHA，非 root、network none、无 host ports、4CPU/6GB。不删除 native locks/pending/data 来获得 PASS。

- PDStore core-std-002 / build004 PASS：真实写读更新、两轮重启、8 次正常停机、PD 占用 gRPC failed refresh 后重开。
- TP core-tp-002 / build004 FAIL：native 拒绝 /rocksdb/ 尾分隔符，保留证据；已一字符修复。
- TP core-tp-003 / build005 正在运行：初次写读和第一次重启更新/读取/正常 stop 已通过；待最终重启和 occupied gRPC gate。
- Server server-std-001 / build002 PASS；server-tp-001 FAIL exit134（请求缓存后端租约未归还）。no-graph-001 A/B PASS。最小请求/任务归还租约修复已编译/聚焦测试/审查/发布，必须以 build005 重跑全流程。
- Server server-std-002 / build005 已排共享锁，新增实际 dump-store 命令验收。接着 server-tp-002。HStore frontend → TP PD/Store 已准备证据 driver，独立检查后执行。

## 当前运行与接着做

共享重任务锁 E/run-exclusive.py，勿并行重批。PDStore TP session 76427；Server standard 排队 session 28628。每次用新 run ID，失败保留日志/数据/容器；成功仅删除该 run 容器。不要打印整个 result 的 source_manifest（数千条）。

1. 完成 TP PDStore + 当前 Server 标准/TP + HStore 三组件实际运行，定位失败再最小修复。
2. 最终远端 tree 逐 blob 比对 build005 source（文档用 final payload），绑定编译产物和验收。
3. 更新 #266 过时 body，只有实际 gate 通过再 ready for review；刷新 exact head CI，当前多项 queued，不能把 push/旧 CI 当合入许可。
4. 精确处理 #261 节流评论 resolve，旧 #264 评论映射与替代说明；#263 checkpoint/savedWal 在独立恢复 follow-up 处理。
5. 收敛必要记录并发布 task/topling-split-records-20261003；保持 current handoff 状态及时更新。

Agents：p2_resume 只读旧 #264 评论映射，p3_resume 只读 clean recovery 最小方案，p4_resume HStore driver 输入/断言检查。root 负责统一重任务、发布/评论/PR/docs。没有新 user-owned thread/goal/automation；旧 Linux goal 仍暂停。

已归档删除 3 冗余顶层目录和 9 旧快照；当前失败 native 证据和旧 lifecycle/recovery/provider 未发布内容保留；其他暂停线程 toplingdb/toplingdb-sync 不动。
