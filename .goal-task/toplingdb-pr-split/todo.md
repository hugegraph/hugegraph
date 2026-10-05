# 当前执行项 · 2026-10-05

## 2026-10-06 新评论实修进行中（用户要求修复、验证、推送后再交付）

定时自动化tp-pr已经PAUSED；用户明确无需继续定时检查。三PR现有意见由既有workers分工处理，无新事项/无auto-merge。261由p2在R06/p261-comments-fix/source（254381b完整gh archive）负责transportcancel和DONE重复query；3旧实现负回归已失败，positive001发现双onError并修幂等onTransportCancel，fixture补单worker barrier后最终negative002/positive003待实际Java17完成；p3独立review已绑定最新patch。旧ordering事实回复，不盲resolve。

267由p4在R06/p267-comments-fix（目录本身完整2724tree，a021逐blob/mode一致）负责6条；R06/p267-comments-evidence/candidate-001.patch包含物理sameFile、marker notExists、保守拒绝directDB symlink/会删除的内部WAL target链/link..、Storepool close重试、残留前缀+真实native7method。root独立review001通过；真实Docker父双bind WAL尾部必须执行，不能只reopen；冻结negative/candidate排共享heavy锁。

266当前remote仍18dd，root拥有CodeSource、动态OLAP CF、prepare/preload及tests/docs；p3拥有Heartbeat fatalexit及metrics/nativeowner。source=R06/p266-sync/source，正在编辑，不把旧2730parity转移到新candidate。CodeSource控失败+OLAP归属已修；R06/p266-comments-fix/routing-validation-003为最终独立A/B（003修fixture显式createCF，002错误在构造未到truncate，保留失败；003negative必须精确Table graph+ap_123 not opened）。prepare Java17 source inline探针临时DB验证OPTIONS write_buffer_size=17M后close并绑定jar/native easy-migrate.sha256；preload重新核checksum、拒已知竞争JNI/保留其他preload。实际prepare001旧官方JAR原脚本接受、新脚本拒绝；可信JNI新脚本通过配置实效，001后续host复制Docker绝对libaio symlink失败不是产品bug；preload-002保留symlink重跑真实双JNI/拒绝。shellselection host通过。paired网站EN/CN新说明在R06/website-capability待核心验证后同步push。

p3 HStore003 standalone真实HTTP均通过；004真实TP PDStore首6vertex/REST/Gremlin/edge通过，Store实际stop=-6 SidePluginRepo db not closed（script0不能代替）。已定位SystemMetricService.loadRocksDbInfo每DB queryGraphDB clone未close，修try-withresources+真实native引用回归，不改Engine强制关闭。Heartbeat5fatal码独立协调exit helper+真实子JVM A/B：旧2方法卡hookjoin，新6测试过，5进程实际255+hookjoined。p3 patch5files=R06/store-shutdown-comments-candidate/fix.patch，root独立review通过；metrics A/B/nativeowner诊断排锁，最终统一Maven+重构包HStore stop/restart待完成。p2对root266生产初审无material，最终payload需hash复核。

所有heavy单E/run-exclusive锁，实际17immutable。rootnative镜像含snappy digest0d1cf670c86ab4a311e4be6643f0d7e8f189aca04b37afbe123d65136f337a9d；不重用11或旧prepared资产。新prepare需真实JDK17，不是JRE；旧准备目录没有checksum receipt明确拒。各修验证通过后及时gh-only非force分批push，回复且resolve验证当前head的问题；争议保留。不得用后续CI未完作为阻止即时反馈/推送理由。

## 2026-10-06 半小时评论复查（已执行，自动化 PAUSED）

五PR当前head未变；完整review threads/reviews/普通comments保存在E/resume-20261006/comment-check-0342。对照旧快照与本次请求时间，261新增2条，266新增4条并重开1条，267新增3条（另有3条较早未处理），268/网站510无新增待处理。现有open线程分别3/5/6/0/0；261的旧ordering争议不按已证明缺陷计数，保留已回复线程。

待办优先：266 heartbeat fatal System.exit 与hook join死锁（4187994450）；267 data/WAL物理同目录却字符串不同导致误删已恢复WAL（4187969072）。其余：261半关闭后transport cancel传播（4187953146）和DONE后二次QUERY_REQUEST先分配泄漏（4187953158）；266 CodeSource null（4187965595）、独立OLAP动态CF路由（4187994473）、竞争JNI启动拒绝（重开4187979887）、EasyMigrate能力/配置实际效果校验（4187994484）；267直接DB symlink断链身份（4187969081）、WAL target链/link/..（4187969086）、未知marker存在性fail-closed（4187735084）、失败恢复同Store close/open（4187735095）、测试残留前缀（4187733562）。均未修复推送/未resolve，不能标记完成。源码核查与reviewer运行探针分层记录，未把reviewer结果冒充本地重跑。

Java17进展：当前18dd全部2730远端blob与源一致，p266-source-head-18dd-parity.json。Server/PD/Store三包clean package通过（构建跳过测试）；独立Core 101通过/5能力条件跳过/零失败，六新增回归全执行。真实HTTP run003：memory项目同名删除/access0/重建、RocksDB schema/index task清理后重建读写通过并正常stop；HStore需补测试镜像缺失libsnappy后继续。不能称整体CI/API/TP配置能力验收通过。

本次跟进仅检查一次，tp-pr已通过automation_update设PAUSED；不修改其他自动化。逐条audit输出在comment-check-0342，后续修复仍只用实际Java17、不等CI才反馈、不自动merge。

用户已恢复，入口见 [handoff-current.md](handoff-current.md)。不沿用旧四级 stack 范围。

| 项目 | 当前结果 | 剩余工作 |
| --- | --- | --- |
| P1 / master | 已合入并同步核对 | 四代码PR已sync9ed，后续继续核最新master，不追加P1 |
| 核心 #266 | 三组件/普通包/实际std+TP服务及HStore验收通过，独立review完成 | 最新两运行review已修复验证推送并resolve；261/266冲突已推；Java17 auth/index companion对照与真实API/HStore验证后补commit并review |
| 规范 #268 | 从266拆出，2文件直接master，policy评论resolve | 全格式覆盖修正已推并reply/resolve，独立评审/合入 |
| 生命周期 #261 | 普通scan/query/重启、blockedcallback timeout保PID+释放完成通过；已解决comments resolve，ordering争议回复 | TTL严格重叠未验证；master既有codec问题独立跟进；门槛完成后提醒用户review合入 |
| 恢复 #267 | clean master12文件，所有新修复审查、wholecompile/native/crash/physicalparentbind通过，已推 | 已syncJava17master，新17 native恢复验证待补；父根挂载/旧pending升级深入评审，核对WAL alias补丁 |
| 旧恢复 #263 | 已close由267替代，checkpoint评论resolve，分支保留 | 无当前合入入口 |
| 旧集成 #264 | OPEN拆分源，原20thread逐条回复去向；其他协作持续提交 | 保留并发内容；专用包/Docker分别迁出，不把后置建议称已修复 |
| 专用 distributions → Docker | 用户认可小PR独立拓扑 | 核心后逐个交付，不重新绑通用改造 |
| 网站 #510 | 匹配核心，中英/链接/严格fresh产物/浏览器验收，剩余两comments resolve | HStore清provider评论两语已推resolve，Java17要求也已推；之后配对review/merge |
| JNI 许可/正式发布 | 用户后置 | 独立后续，不称已完成 |
| 资源/context | 已审计删旧冗余和恢复targets/诊断停止容器，失败数据完整保留 | 核心/P2 targets、约12.5GiB重复产物及8停止容器收敛，推当前Java17专用records |

源/hash、命令及证据只保存在当前handoff和本机evidence；大日志/数据库不进入功能PR。
