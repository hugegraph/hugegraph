# 当前状态 — 2026-10-06（review wave 03）

本轮新增六条意见已修复、分批提交推送并 reply/resolve；旧 #261 ordering 不包含在这个结论。截图中 Store Node 包不可见的正常打包编译问题已另行修复推送。所有 GitHub 发布使用 gh API，双 parent guard、非 force，未改 master。

| PR | 分支 | 当前 head |
| --- | --- | --- |
| fork #261 | task/topling-split-lifecycle | 1110c918f07d81596f6ca10ae4ddf383336372bd |
| fork #266 / ASF #3275 | task/topling-core-20261005 | 31ee65435991430f4cdc76c081a07333d791ce60 |
| fork #267 | task/rocksdb-recovery-20261005 | 8b2e13290278f88def3e1080923205df36b1266d |
| fork #268 / ASF #3274 | task/review-formatting-20261005 | 39003814d9ac82aedfd2d4943e908117ec22191c |
| 网站 ASF #510 | hugegraph/hugegraph-doc:task/topling-split-docs | da23367301e625c6eefceac69c03cb399e10e92c |

用户批准目录0755/普通文件0644；各自原分支开两个 ASF PR。普通追加提交排除 fork-only CI rerun helpers，保留核心 pd-store-ci 实际变更。上游 #3274 diff 仅两个规则文件；#3275 为核心功能，不带无关 CI 回退。imagegen 生成启用图已随核心提交，PR描述突出 prepare → provider/config → init → start。

实际Java17：Store恢复旧子进程死锁，新真实 hook/Node destroy 正常退出，Heartbeat全类通过；正常 package 依赖入口编译与子进程退出通过。client旧完成后额外poll，新终态预检查保留最后batch，正常 ClientSuite 全部通过。权限实际跨UID读/不可写验证通过，非完整跨账号JNI服务启动。

组合缓存/Auth验证：两个分支均通过，完整直接相关缓存类和四个Auth方法，source无漂移。格式专用agent：仅10个Java文件空白/换行，所有修改源行原属PR新增代码，tokens/literals等价；最终format/whole cleancompile/Commons testcompile通过并推送。

最新CI看W03/latest-pr-status.json；#267当前提交29项成功，其他分支/上游仍有运行中检查，不称全部绿色。先前服务包验收绑定ebe4658，不能替代最新head服务验收。更早W02完整记录保留，W03当前证据优先。
