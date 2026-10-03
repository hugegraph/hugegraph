# 当前执行项

本文件维护逐项状态；执行约定、固定基线与恢复入口见 [state.md](state.md)。

| 项目 | 状态 | 下一动作 / 恢复条件 |
| --- | --- | --- |
| 拆分归属与依赖 | 分析完成，P4部分必要性待证 | 198文件/404清单项无漏项；P1、P2独立，P3依赖P2，P4依赖必要前置 |
| P1候选 | 本地已构造 | 14文件含依赖、metrics、旧数据fixture、文档、CI和Iterator测试适配；PDStore JRaft改1.3.14修复实测旧API调用，Server暂保留1.3.11 |
| P1兼容测试收敛 | 已提交f550935e，兼容CI通过 | 长期测试移入src/test/rocksdb-compatibility，Maven实际版本比较及去重；两真实兼容路径6 JVM通过，未变/JRaft-only跳过，去重及未解析版本失败验证通过；format+全仓clean compile通过，P4同步，未合入 |
| P1代码审查 | 最终14文件三人复审通过 | JRaft ABI修正及README旧版本修正后，三名独立审查者无未解决静态发现；Store meter实测仍待补 |
| P1 Mac跨版本JNI | 旧候选通过 | 2组/6JVM/0 skip/exit0；最终fixture加入TP标志拒绝后重跑；三版JAR与Central SHA1相符 |
| P1原生Linux跨版本JNI | 当前候选通过 | do专属目录，最终12文件payload哈希一致；2组/6JVM/0 skip/exit0，CodeSource/nativeVersion/哈希已记录；仅轻量JNI，不是完整服务验收 |
| P1格式与干净编译 | 当前初稿通过，修正依赖后重跑 | 原生Linux editorconfig:format exit0；Mac首次protoc架构错误；旧容器现已确认exit1非OOM（protoc下载截断）。Linux v2离线缺Server protoc3.21.7；已核POM三项compiler，校验Central SHA1后补齐，v3干净编译exit0（2:04）；JRaft修正后全仓clean compile exit0（1:50），格式通过 |
| P1模块测试 | 失败已保留，分项恢复中 | Commons首次349/7 errors/0 skips：缺Maven PATH及默认选择导致Perf类重复定义；按UnitTestSuite复测349/0 errors/0 skips通过。PD二次common83/0 errors，core71/8 errors/0 skips：JRaft调用已移除blockCacheCompressedSize，PDStore已升1.3.14；v3 common83全通过/core108项106通过2既有Ignore；Store core44+rocksdb3全通过；Commons349+RPC24通过 |
| 容器清理 | 23个本任务容器已清理 | 失败日志及必要临时数据归档E/cleanup-20261003；未触碰其他任务容器；后续成功任务自动删除容器，镜像/缓存保留继续测试 |
| 本机Docker | 已恢复并复核 | OrbStack详情和Docker API均确认旧构建exited(1)，句柄已结束；未重启旧容器，失败日志保留，新v3是独立重跑 |
| 原生Linux备用环境 | 只读预检完成 | do：x86_64、Java11、glibc2.42、39GiB空闲，总RAM约2GiB且有用户服务；独立目录/root/hugegraph-topling-pr-split-20261003/source完整clone并核2635个base blobs，P1 12文件补丁逐hash一致；仅低负载JNI验证，不改变旧服务 |
| P1服务与包验收 | 标准包初稿通过，服务未完成 | 最终三组件package exit0；各包恰一份标准8.10.2 JNI，PDStore恰一份JRaft1.3.14、Server1.3.11。旧基线包也已完整构建。服务驱动已编译、旧PDStore真实启动；首次seed表名不受支持已修正，旧Store停止超时证据保留，升级/重启/metrics尚未通过；fresh run3已完成旧服务128条数据/元数据/分区身份读写核验；旧Store在ContextClosedListener.wait循环中停止超时，线程栈和数据保留，容器已清理；尚未升级PASS |
| P2候选 | 本地43文件，完整验收待完成 | 完整事务/auth/schema、Store回调排空、stop和文档；三名静态审查及回调修复复审通过；格式+全仓cleancompile、node29项通过；Server unit755项754通过1既有skip |
| P2 stream回归修复 | 定向红绿与三人复审通过 | 拒绝/异步失败只发错误终态，响应回调串行，避免自中断，正常取消先确立完成；新增5项测试。精确目标类+缓存peer probe：来源5失败、当前5通过，不能替代干净reactor；stop脚本已接入专属容器CI |
| P3候选 | 格式、干净编译及恢复/WAL定向测试通过 | 10文件增量；三名静态审查及遗漏修复复审通过；恢复27+Sessions13项全通过无skip；MultiGraphs11项中2skip、其余通过，双真实bind mount均拒绝；#263非Draft已发布 |
| P2/P3运行验收 | P2 core非root全量通过；P3定向及挂载验证通过 | P2首次root权限失败与tmpfs noexec首次失败均保留，未改断言；完整非root822项、0失败、0错误、45skip、exit0，成功容器已删除。P2 API及扫描/TTL真实关闭harness已准备、未执行（需先打包P2三组件）；P3恢复/WAL、多图及两个真实挂载别名拒绝均通过；探针classpath首次失败已保留 |
| P4通用生产修复分组 | 已排除，实测回归可重开 | base/source调用链和依赖对照无新增TP必要性；PD follower REST组、Gremlin/HStore白名单组按既有通用问题独立跟进 |
| P4 JNI获取与许可证 | 来源已补证；许可材料TODO（用户延期） | 当前JAR SHA256 86eb1bd3d9f84ef0dddd3fe95c640a6f145ca2d5a26a5ba628f1f298a2031fae，只有linux64 native且无LICENSE/NOTICE/POM；producer构建日志与binary内嵌core SHA一致，固定rockside/boost许可已读取；四个auto-cloned插件精确版本已从当前ELF的命名版本函数定位并gh核验；许可/发版材料由用户明确单列后续TODO，本轮不再追查或作为功能交付阻塞，不宣称已通过许可审查 |
| P4代码与发布验证 | 109文件候选，标准回归通过，继承本轮Store修复后待复测 | 原unit797、core822与清表红绿通过；反馈继承后format+cleancompile及Sessions21+Snapshot29+Transaction3共53无失败/无skip，容器删除。首次反射夹具错误已保留并修正。当前同步P2 scan/channel与Store普通/exec打包修复；恰3路复审中，最终候选复测后发布4/4；真实JNI与服务未完成 |
| org子PR | P1/P2/P3已发布，CI进行中 | P1 #262 head169d09bf；P2 #261 head976ecc29，均非Draft、独立base176fb。P1兼容测试收敛已追加提交、本地相关验证通过，新head rocksdb-compatibility CI通过，其他CI待刷新；P2原stop CI通过，其余CI待刷新；当前head验证和剩余门槛在PR中逐项列明；P3 #263 head2eb60f03 基于P2，P4按完成步骤及时提交，不auto-merge |
| 网站配套文档 | latest真实变换/artifact验证通过，最终内容待重建搜索 | 9文件双语候选（8篇Markdown+既有route-map两最新页面），API719文件baseline e6389aa7；链接与轻量Python、严格Hugo通过，真实OINK migrate/check+导航+URLscope+security+latestartifact已通过。独立内容审查修正pre-JVM mount说明仅限单机Server，最终此修正的重建、source/render/search检查待串行槽 |
| Apache子PR | 未发布 | 重新核上游base/差异/CI；对应用户确认单独取得；源整合#3134保留 |
| 评论反馈闭环 | 原14条及P2新2条已修复闭环 | P2正常追加b7803527，随后976ecc29仅暴露RISC-V退出关闭诊断；当前P1/P3仍169d09bf/6910e301。新增独立interrupt不部分成功、borrowed线程不被中断、统一CANCELLED。format+cleancompile、中央Store34/Node11、实际package与ordinary/exec/Distidentity通过，恰3名final复审及test-only TOCTOU补充复审均通过，两线程已reply+resolve |
| Store package CI回归 | 修复已提交P2，P3最终继承树验证中 | 10个当前失败job属Node BOOT-INF替换主artifact导致中央testcompile失败，非cluster已执行。Node普通+exec、Dist选exec保持原文件名与startup，实际packageidentity通过，新head CI仍须完成；另确认P3 macOS多图typedwrapper/rootcause测试契约失配，test-only修正并已通过MultiGraphs11（2既有skip），生产cause保留 |
| 原有快照/查询迭代器生命周期 | 独立后续TODO；未修复 | P2固定基线、原P3与本轮scan/query/native关闭路径相同，不是本轮引入；需延长CF借用至iterator close并协调恢复，不把本轮锁修复测试当该并发边界通过 |
| 原有GremlinJob失败时finally提交 | 独立后续TODO；未修复 | 已有execute finally会先提交，事务边界cleanup不能撤回；本轮仅覆盖ContextTask残余开放事务清理，不声称修复此旧行为 |
| 最终整合 | 未开始 | 所有所需单元合入Apache、标准/真实TP整合验证、产品文档及三人审查通过；JNI发版合规按用户最新指示后续处理 |

单项等待后移，不阻止独立分析、候选构造及文档/许可核查；上述任何历史或静态结论均不替代最终head实测。

用户已恢复执行：当前唯一重任务为P3最终继承树format/cleancompile/native42/core事务+MultiGraphs及Storepackage测试。确认CI package阶段Node BOOT-INF主artifact不能供测试编译，已分普通library与exec发行artifact并同步P3/P4；普通JAR及exec布局初查通过，Dist需真实打包核验。P1旧服务关闭失败、P2真实服务及P4真实TP门槛继续保留。

RISC-V旧head native/persist smoke到完成标记后EXIT关闭失败，细因未确定；仅取消诊断吞掉，bash-n与真实脚本隔离故障注入验证before/after均失败exit1/PID保留而after输出stop日志。timeout和生产stop未更改，真实关闭门槛未通过。高并发100ms channel轮询开销单列待负载验证，不把未测性能推算当正确性阻塞。
