# 当前执行项

本文件维护逐项状态；执行约定、固定基线与恢复入口见 [state.md](state.md)。

| 项目 | 状态 | 下一动作 / 恢复条件 |
| --- | --- | --- |
| 拆分归属与依赖 | 分析完成，P4部分必要性待证 | 198文件/404清单项无漏项；P1、P2独立，P3依赖P2，P4依赖必要前置 |
| P1候选 | 本地已构造 | 14文件含依赖、metrics、旧数据fixture、文档、CI和Iterator测试适配；PDStore JRaft改1.3.14修复实测旧API调用，Server暂保留1.3.11 |
| P1代码审查 | 最终14文件三人复审通过 | JRaft ABI修正及README旧版本修正后，三名独立审查者无未解决静态发现；Store meter实测仍待补 |
| P1 Mac跨版本JNI | 旧候选通过 | 2组/6JVM/0 skip/exit0；最终fixture加入TP标志拒绝后重跑；三版JAR与Central SHA1相符 |
| P1原生Linux跨版本JNI | 当前候选通过 | do专属目录，最终12文件payload哈希一致；2组/6JVM/0 skip/exit0，CodeSource/nativeVersion/哈希已记录；仅轻量JNI，不是完整服务验收 |
| P1格式与干净编译 | 当前初稿通过，修正依赖后重跑 | 原生Linux editorconfig:format exit0；Mac首次protoc架构错误；旧容器现已确认exit1非OOM（protoc下载截断）。Linux v2离线缺Server protoc3.21.7；已核POM三项compiler，校验Central SHA1后补齐，v3干净编译exit0（2:04）；JRaft修正后全仓clean compile exit0（1:50），格式通过 |
| P1模块测试 | 失败已保留，分项恢复中 | Commons首次349/7 errors/0 skips：缺Maven PATH及默认选择导致Perf类重复定义；按UnitTestSuite复测349/0 errors/0 skips通过。PD二次common83/0 errors，core71/8 errors/0 skips：JRaft调用已移除blockCacheCompressedSize，PDStore已升1.3.14；v3 common83全通过/core108项106通过2既有Ignore；Store core44+rocksdb3全通过；Commons349+RPC24通过 |
| 容器清理 | 23个本任务容器已清理 | 失败日志及必要临时数据归档E/cleanup-20261003；未触碰其他任务容器；后续成功任务自动删除容器，镜像/缓存保留继续测试 |
| 本机Docker | 已恢复并复核 | OrbStack详情和Docker API均确认旧构建exited(1)，句柄已结束；未重启旧容器，失败日志保留，新v3是独立重跑 |
| 原生Linux备用环境 | 只读预检完成 | do：x86_64、Java11、glibc2.42、39GiB空闲，总RAM约2GiB且有用户服务；独立目录/root/hugegraph-topling-pr-split-20261003/source完整clone并核2635个base blobs，P1 12文件补丁逐hash一致；仅低负载JNI验证，不改变旧服务 |
| P1服务与包验收 | 标准包初稿通过，服务未完成 | 最终三组件package exit0；各包恰一份标准8.10.2 JNI，PDStore恰一份JRaft1.3.14、Server1.3.11。旧基线包也已完整构建。服务驱动已编译、旧PDStore真实启动；首次seed表名不受支持已修正，旧Store停止超时证据保留，升级/重启/metrics尚未通过 |
| P2候选 | 本地43文件，完整验收待完成 | 完整事务/auth/schema、Store回调排空、stop和文档；三名静态审查及回调修复复审通过；格式+全仓cleancompile、node29项通过；Server unit755项754通过1既有skip |
| P2 stream回归修复 | 定向红绿与三人复审通过 | 拒绝/异步失败只发错误终态，响应回调串行，避免自中断，正常取消先确立完成；新增5项测试。精确目标类+缓存peer probe：来源5失败、当前5通过，不能替代干净reactor；stop脚本已接入专属容器CI |
| P3候选 | 本地10文件增量，未测试 | 已修正拆分漏带的cached-owner检查/锁内注册/typed fallback；三名静态审查及遗漏修复复审通过；完整运行仍未执行 |
| P2/P3运行验收 | P2 core非root全量复测中；P3待串行 | P2 core首次822/1失败/45skip，失败来自root可创建/g；非root首次tmpfs noexec已修，原用例定向通过，未改断言。API/真实关闭及P3恢复锁故障实测仍待完成 |
| P4通用生产修复分组 | 已排除，实测回归可重开 | base/source调用链和依赖对照无新增TP必要性；PD follower REST组、Gremlin/HStore白名单组按既有通用问题独立跟进 |
| P4 JNI获取与许可证 | core来源已补证，插件闭包/材料仍缺 | 当前JAR SHA256 86eb1bd3d9f84ef0dddd3fe95c640a6f145ca2d5a26a5ba628f1f298a2031fae，只有linux64 native且无LICENSE/NOTICE/POM；producer构建日志与binary内嵌core SHA一致，固定rockside/boost许可已读取；自动clone其他插件的精确版本与最终链接许可闭包未齐，已异步向用户询问生产方材料 |
| P4代码与发布验证 | 109文件候选，审查修复已完成，待实测 | 修复truncate提交pending写、SST truncate静默无效、Compose全局pull policy丢失、打包发布失败损坏旧输出；三人增量复审通过，shell故障注入通过，Java红绿/完整构建/native仍待完成。许可证文件随包交付仍开放 |
| org子PR | P1/P2 已发布，CI启动 | P1 #262 head28a7d653；P2 #261 head7d1472b8，均独立base176fb。当前head验证和剩余门槛在PR中逐项列明；P3/P4按完成步骤及时提交，不auto-merge |
| Apache子PR | 未发布 | 重新核上游base/差异/CI；对应用户确认单独取得；源整合#3134保留 |
| 最终整合 | 未开始 | 所有所需单元合入Apache、标准/真实TP整合验证、文档许可及三人审查通过 |

单项等待后移，不阻止独立分析、候选构造及文档/许可核查；上述任何历史或静态结论均不替代最终head实测。
