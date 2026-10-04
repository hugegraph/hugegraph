# 当前执行项

本文件维护逐项状态；执行约定、固定基线与恢复入口见 [state.md](state.md)。

| 项目 | 状态 | 下一动作 / 恢复条件 |
| --- | --- | --- |
| 拆分归属与依赖 | 分析完成，P4部分必要性待证 | 198文件/404清单项无漏项；P1、P2独立，P3依赖P2，P4依赖必要前置 |
| P1候选 | 已通过 Apache #3265 合入；org #262 已关闭 | 不再追加 P1 修改；旧服务升级、指标及集群剩余工作放到后续相关 PR |
| P1兼容测试收敛 | 已提交，当前head兼容CI通过 | 长期src/test/rocksdb-compatibility；两路径6 JVM、未变/JRaft-only skip、去重/unknown fail和format/compile通过；不并入服务/集群验收 |
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
| P4代码与发布验证 | #264已发布9af9d14；打包/标准回归通过 | 109增量文件，继承树native42/transaction3/MultiGraphs11(2既有skip)/Store34/Node11；新PD包、Compose、dirty roots、marker修复及actual cleanpackage/Topling生成通过；真实JNI服务仍待执行 |
| org子PR | 四个非Draft PR已发布 | #262 169d09bf、#261 976ecc29、#263 2eb60f03、#264 9af9d14；全部保留编号，不自动合入；前置组合f7428943；current CI未全完成 |
| 网站配套文档 | apache/hugegraph-doc #510已发布 | b2e9a633；9文件，真实latest assembly/artifact、213 source/render、24既有ranking、12指南浏览器验证通过；14 Git fixture按gh-only明确未跑；当前CI成功或预期skip |
| Apache子PR | 仅网站#510已发布；功能PR待交付 | 四功能单元按各自CI、服务gate及org/Apache逐PR授权交付；源#3134保留 |
| 评论反馈闭环 | 原14+P2两条闭环；P4四条新功能闭环 | P4五功能修复已提交实测；standard镜像默认根actual anonymous-volume startup通过并resolve；新四条启动/dump/JDK/docs仍open；JNI合规线程按用户后续TODO关闭，未claim合规。每个里程碑刷新所有5PR |
| Store package CI回归 | P2/P3已提交并实际package通过 | ordinary library+exec classifier，Dist选exec且旧文件名不变；P3 typedcause fixture只修测试；最新CI继续刷新，勿沿用旧失败/旧成功 |
| 原有快照/查询迭代器生命周期 | 独立后续TODO；未修复 | P2固定基线、原P3与本轮scan/query/native关闭路径相同，不是本轮引入；需延长CF借用至iterator close并协调恢复，不把本轮锁修复测试当该并发边界通过 |
| 原有GremlinJob失败时finally提交 | 独立后续TODO；未修复 | 已有execute finally会先提交，事务边界cleanup不能撤回；本轮仅覆盖ContextTask残余开放事务清理，不声称修复此旧行为 |
| 最终整合 | 未开始 | 所有所需单元合入Apache、标准/真实TP整合验证、产品文档及三人审查通过；JNI发版合规按用户最新指示后续处理 |

单项等待后移，不阻止独立分析、候选构造及文档/许可核查；上述任何历史或静态结论均不替代最终head实测。

用户已恢复手动执行。当前single-heavy为P4 topling-owned-close-001（Std002完整PASS）；004编译/测试/打包完整PASS，3标准和3Topling输入已保留。最新heads/证据与恢复入口见state.md。P1旧服务关闭失败、P2 API/TTL关闭、P3服务故障与P4真实JNI/native、服务重启、镜像匿名volume和集群gate均保留。

RISC-V旧head smoke完成后EXIT关闭失败原证据保留；976只让stop诊断可见，最新RISC-V CI已成功但不能替代服务gate。100ms channel轮询开销仍为待负载验证项。历史本机验证、当前CI、实际服务及合入交付分别记录，不互相替代。

| 新运行门槛 | 当前状态 | 下一动作 |
| --- | --- | --- |
| P4标准匿名volume镜像 | 三组件PASS，五旧feedback闭环 | runtime目标以clean包替换Mavenstage；不是完整Dockerfilebuild，专属资源已清理 |
| P4真实Server | Linux/amd64模拟actual PASS | 3 JVM身份/CRUD/restart/normalstop/拒绝材料保全通过；physical x86及额外truncate还需 |
| P4 PD/Store actual | 原Topo PDclose SIGABRT；Std001前3cycle PASS、bootstrap Bolt未自然退FAIL | 004已补owned client收回，PD22 PASS；Std002唯一heavy实际4cycle/失败自然退/数据重开，后续Topo同gate |
| P4新反馈 | 原4条+dynamic/customstore两条确认；004候选未发布 | bare/config/dump/JDK/Docker/Java动态guard已整合且focused测试通过；实际services与2边界最终修复后正常提交/resolve |
| P1 current cluster CI | 超5hr沉默后任务内取消取日志，不是PASS | 旧无界startup/NOP已证，具体阻塞unknown；隔离重现取node logs/stacks后再actual复测 |

### 出行窗口执行队列（2026-10-04）

- [x] P1/P2/P3/P4固定head源码、API现有3个官方固定commit资产已缓存并核hash/JAR；Maven2.7GB与2本地镜像保留。
- [x] P1离线launcher缓存校验已实际preflight：腐败hash拒绝，有效缓存到本地image检查，不访问GH、不启动heavy；真实cluster另待单heavy。
- [ ] 只运行一个heavy；完成P4当前批次后保存产物，修两处004确认问题并复审/实测。
- [ ] 持续本地服务/测试并留证；GH失败排队，联网恢复后复核head并完成提交/comment/resolve。
- [ ] 每个可review里程碑及时向用户发送链接、重点与未完成验收；无关键取舍不等待用户。

新增反馈待闭环：P2 SCAN_V2 worker/partition drain正在准备最小候选（P2既有查询实现未由本PR引入，缺口属于新增关闭保证）；P3同JVM contender关闭第二fd释放POSIX owner锁已确认，由P3引入，需物理file identity注册和独立JVM/restore窗口实测；网站WAL/SUMMARY/裸路径覆盖3条候选已准备，等待实际验证与正常追加。当前cluster P1/P2/P3均CANCELLED≠PASS；P4旧head3Topling DockerFAIL，修复context输入候选仍未发布。

### 暂停（用户明确，2026-10-04）

主会话停止代码/测试/发布；PR#262独立会话继续。13:00后主会话P2/P3共11个本地文件的before/current及diff已保留，尚未revert。P2还有清理失败不应解除屏障的未解决复审项；P3实际native与parent-bind批次PASS。不要据此继续修复或提交，先等用户决定模型/是否还原与恢复。审计入口见state.md。
