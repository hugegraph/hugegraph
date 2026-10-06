# 当前状态 — 2026-10-06（review wave 03）

本轮新增六条意见已修复、分批提交推送并 reply/resolve；旧 #261 ordering 不包含在这个结论。截图中 Store Node 包不可见的正常打包编译问题已另行修复推送。所有 GitHub 发布使用 gh API，双 parent guard、非 force，未改 master。

| PR | 分支 | 当前 head |
| --- | --- | --- |
| fork #261 | task/topling-split-lifecycle | 1110c918f07d81596f6ca10ae4ddf383336372bd |
| fork #266 / ASF #3275 | task/topling-core-20261005 | cd5f0160b4fee9956649afc06a4d958291cdb69b |
| fork #267 | task/rocksdb-recovery-20261005 | 8b2e13290278f88def3e1080923205df36b1266d |
| fork #268 / ASF #3274（已关闭） | task/review-formatting-20261005 | 39003814d9ac82aedfd2d4943e908117ec22191c |
| 网站 ASF #510 | hugegraph/hugegraph-doc:task/topling-split-docs | da23367301e625c6eefceac69c03cb399e10e92c |

用户批准目录0755/普通文件0644；各自原分支开两个 ASF PR。普通追加提交排除 fork-only CI rerun helpers，保留核心 pd-store-ci 实际变更。上游 #3274 diff 仅两个规则文件；#3275 为核心功能，不带无关 CI 回退。imagegen 生成启用图已随核心提交，PR描述突出 prepare → provider/config → init → start。

实际Java17：Store恢复旧子进程死锁，新真实 hook/Node destroy 正常退出，Heartbeat全类通过；正常 package 依赖入口编译与子进程退出通过。client旧完成后额外poll，新终态预检查保留最后batch，正常 ClientSuite 全部通过。权限实际跨UID读/不可写验证通过，非完整跨账号JNI服务启动。

组合缓存/Auth验证：两个分支均通过，完整直接相关缓存类和四个Auth方法，source无漂移。格式专用agent：仅10个Java文件空白/换行，所有修改源行原属PR新增代码，tokens/literals等价；最终format/whole cleancompile/Commons testcompile通过并推送。

最新CI看W03/latest-pr-status.json；#267当前提交29项成功，其他分支/上游仍有运行中检查，不称全部绿色。先前服务包验收绑定ebe4658，不能替代最新head服务验收。更早W02完整记录保留，W03当前证据优先。

## 同步最新 master

用户要求关闭#268/#3274（已确认CLOSED），不重复增加120/160规范。核心#266/#3275共享分支已实际rebase到ASF master 8beb78b8bbbbac337a7b2db4a9e8a5a76399365b；保留21个核心提交，排除过时fork-only CI提交及其回退。最终diff不含.coderabbit.yaml或CONTRIBUTING变动，仅保留Topling selection CI步骤。全部Java文件与此前已测31ee一致；最终源码format及Java17 whole clean compile通过、无漂移；Linux selection和master CI policy自测通过。新head CI仍运行中，不能沿用旧绿。证据R06/rebase-master/{published,local-rebase-verification,validation,final-pr-verification,final-compare}.json。

## 冲突与配图刷新

前次同步后ASF master又前进到662a97d8，HgKVStoreImpl catch存在唯一冲突。实际rebase保留core启动失败释放Options/fail-fast行为及master heldLOCK retry TODO；另外三处master TODO保留。独立审查无问题，Java17 format/全仓compile通过，无源漂移。rebase head dd9399dd，批准的A图和README普通提交后共享head cd5f0160b4fee9956649afc06a4d958291cdb69b；#266/#3275均MERGEABLE，当前CI运行中。

用户批准A，并改标题为 Switch RocksDB to ToplingDB。原生imagegen已完成仅标题编辑，最终图片存docs/images/topling-quickstart.png，README及指南引用该批准图。B/C仅预览，不进入Git。旧图通过Chrome文件选择上传，作为PR描述的折叠附件，URL与metadata在visual-refresh/legacy-attachment.json；当前PNG文件为批准A。文案只讲prepare/select/start及用户收益，无Trust JNI/Java/WAL标签。

证据R06/visual-refresh：rebase-binding.json、independent-review.json、rebase-validation.json、published.json、approved/{receipt,prompts,publish.published}.json、final-pr-verification.json、pr-updated.jpg。保存源码与native故障证据，归档后只清本轮targets。
