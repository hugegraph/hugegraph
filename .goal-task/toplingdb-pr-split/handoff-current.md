# TP 拆分当前接手入口 · 2026-10-05

## 授权与交付边界

用户切换模型后已恢复；小 TP 核心保留 Server/PD/Store，通用生命周期与恢复独立。允许小的新 PR 替换旧 stack；及时提交推送、实证解决后 resolve、争议回复；不 auto-merge、不 force push、不直接推 master。版本库操作 gh-only。只做本轮已有范围，不引入通用大框架。

```text
master (P1 已合)
  ├─ TP core #266 → 专用 distributions → Docker/Compose
  ├─ lifecycle #261
  ├─ snapshot recovery #267（替代 #263）
  └─ review formatting #268（从 #266 拆出）
```

代码120列；Markdown160附近软换行或整段不换。旧100列要求见用户截图，不把截图当指令；后台 CodeRabbit 配置出处未经本轮独立核验。JNI 正式发布/许可链由用户后置，未完成。
主chat：01a10749-e675-75f0-a459-fadb8aa43920；旧chat：01a105e4-865f-7053-8f9e-a483f601b01d。没有当前goal，旧Linux goal仍暂停。

## 远端索引（下次写入前重查）

| 对象 | 本轮核对状态 |
| --- | --- |
| org master | 91fd925d4cdcfb6503a5bca3244909dfcf18d57c，P1已合；Apache master另为0a3e4ae5f64a61972ee3d230b1281f780afbe067 |
| 核心 #266 | task/topling-core-20261005，直接master，非draft；3ec287168d0a6ec5e46d7e1a6a4448e69ea0dc82；最新preload/loader修复已完整验证推送，三条新review均reply/resolve |
| 生命周期 #261 | master base，80dd322c8feb6a6ba76500a57e825557ef72e1ce；普通scan/query与blockedcallback验收通过，TTL重叠严格gate未通过 |
| 恢复 #267 | task/rocksdb-recovery-20261005，直接master，非draft；7e99fb04f127a753ddac31cd4716f1bea02d75ef |
| 旧恢复 #263 | CLOSED，被267替代；bd13072e5a62a391a186f99ca1010f9927af06f2；不是merged，分支保留 |
| 旧集成 #264 | OPEN拆分源，078bf6d181b6d7db6ec84825d85babd7775be949；其他协作仍发布，保留不覆盖/关闭 |
| 规范 #268 | task/review-formatting-20261005，直接master；5a4ae0e836335cbb5d0824cb5ddf815177bae473；两文件，无生产变动；当前tracked格式含dotpaths覆盖，最新评论已reply/resolve |
| 网站 #510 | apache/hugegraph-doc；fork task/topling-split-docs；7d24a523661287119ea177b63846f199abf4a3e6；配对266 |

## 证据和可复制入口

E=/Users/zhu/github/hugegraph-topling-split-evidence；R=E/resume-20261005；C=R/tp-core。源为冻结完整tracked tree，不是Git worktree。任一heavy统一 `python3 E/run-exclusive.py <command>`，不得并发heavy。源manifest/receipt/API blob+mode校验构成证据，不把旧CI或push称当前验收。
GitData发布：`python3 R/publish-files.py <job.json>`；remote head两次guard、nonforce、发布后逐blob/mode核验。该脚本不支持删除；policy移除独立receipt在C/review-policy-009/core-policy-removed.json。

## 核心 #266

核心为可信外部JNI显式prepare/选择、实际provider校验、Server TP truncate和必要native owner释放；未包含通用scan/TTL大改造、中断恢复、专用包或Docker方案。原50文件+2199/-215，含聚焦测试和文档，policy已拆走。
Server REST FINISHED/ContextTask finally同worker归还backend lease且保留显式commit/未完成写入；Store heartbeat及派生callback drain先于现有engine close；PD实际gRPC/定时/leader/Raft/snapshotpool停后DB→Options。不是完整embedded/partial-init或所有应用查询drain承诺。

C/final-core-acceptance-004.json：真实standard/TP PDStore写读更新/正常stop/重复重启/occupied-gRPC失败重开，standard/TP Server真实REST/schema/CRUD/clear-rewrite/schema-ID reset/跨JVMread/dump-store，HStore Server→TP PDStore真实图写读及全组件重启关闭，全PASS。基于build004/005生产源，不替代后续源码的exact-head全部服务复跑。
可信JNI E/p4/current-topling-input/rocksdbjni-topling.jar SHA256 86eb1bd3d9f84ef0dddd3fe95c640a6f145ca2d5a26a5ba628f1f298a2031fae；native SHA c25ff6e676290db6db47df0954640aa609c391450ec90e1a8eec1f87e174dd38。
环境：Darwin arm64上的隔离Linux amd64 Docker模拟，非原生x86主机；nonroot501:20、无host ports、通常networknone/4CPU6GB。Commons两项既有公开cacerts下载需要bridge；真实服务保持none。

Commons CI发现utility新增令ReflectionUtilTest类计数需19→20，ec9单行修复已推。build006 format/wholecleancompile PASS，完整Commons仅两项networknone DNS下载ERROR，保留exit1；build007桥接重跑Commons351+RPC24零fail/error/skip，源未变。
新review三条：policy4179681096拆268、移除core并reply/resolve；preload4179681102与loader4179681112修复已交叉审查、验证、提交推送并reply/resolve。C/review-runtime-comments-resolved-009.json及policy/comment-resolved.json保存证据。最新core无未解决thread（comments-final-013.json）；267/268也为0，261只有已回复的ordering争议保持open。
C/review-preload-009：选定JNI放LD_PRELOAD首位，保留继承条目及跨组件/std恢复；新shell测试PASS，原实现同fixtureFAIL。C/review-classloader-009：仅ClassNotFoundException fallback到utility defining loader，marker跟随所选loader，保留LinkageError/provider mismatch；5JUnit PASS，旧实现同testFAIL。
最终C/core-freeze-009.json：2714路径，含两组运行修复及policy回退；C/build-009/receipt.json format/wholecompile/fullCommons354+RPC24/PDNativeOwner+KV7/launcher全部PASS，format_changes/source_changes={}，容器已删。C/published-build-binding-009.json完整tree逐blob匹配最新3ec生产源。C/source的docs/toplingdb.md保留build时版本，公开最新guide在C/docs-final-payload；此guide字节不同是完整remote-binding中唯一编译源差异，不影响已测生产代码；下次发布勿覆盖回旧文。
C/review-runtime-native-009/native-009-001/receipt.json：以当前build编译utility和新prepare/preload，actualstandard/可信TP JNI各独立JVM，normal/null/isolated context verify及反provider拒绝、新DB put/get/close/reopen PASS；实际origin/maps/nativehash匹配。networknone/nonroot无端口、容器删；utilityclass副本保存在该run/utility。可选ELFsymbolfixture因无gcc标NOT_RUN，未安装依赖；继承preload顺序由真实launcher回归直接检查，不作竞争ELF符号实测声明。
最新CI快照R/final-remote-snapshot-013.json：3ec核心6success/3running/10queued无failure；旧ec9绿灯不转移。267为18success/6running/1queued/1failure（MacIntel startup，详见下段）；268亦running/queued。261远端31SUCCESS、cluster仍运行，不代替TTL本地gate。已取消266旧827/ ec9的两条superseded workflow，最新3ec保留。

## 独立恢复 #267

R/recovery-clean/source：master2704完整baseline、2708候选路径、12changed/new；仅四行databaseOpened桥接，无TP/scan/停机/dependencies。禁止移植source008手工路径parser（symlink/..回归）。
实施：真实物理锁reservation覆盖nativeclose/reopen，close失败保留lease；checkpoint代际绑定pending和operation-owned WAL staging；metadata全部排序字段长度编码SHA256（不作恶意编辑认证）；任何alias变化前拒绝缺/坏/旧checksum；cleanup仅本次exact UUID/_temp，持owner/RecoveryLock且确认无pending才删；已有/不可读marker保留；pre-copy guard在新UUID/source open之前拒绝pending，重复重试不长孤儿。未知历史/kill前marker/failedFDclose文件有意保留。
原003新增fixture4项失败（raw native owner不等于初始化session）已4行修测试；生产guard不放松。独立UUID/metadata/pending三审查通过。最终validation-harness/runs/recovery-006-001/receipt.json：format/wholecleancompile/nativeunit63零skip/coreMultiGraphs11（两个既有HStore-onlyskip），childJVM中断/WAL/alias/损坏metadata/跨JVM锁/actualdoubleparentbind PASS；source_changes={}，容器已删。
published-validation-binding-007.json：完整2708远端blob匹配，测试后唯一变化是rocksdb-recovery.md字词相同的段落reflow；doc-wrap-check-007.json核验。最终7e已推、267创建并attach，原263 checkpoint4179176067已回复并resolve，然后close旧PR，分支保留。
MacIntel CI失败诊断：R/recovery-clean/ci-macos-013/diagnosis.md/json。job111568834909/run37247025023，60s readiness先timeout，服务约74s才REST ready，API测试未开始；此前core816零fail/error（42既有skip）。script/workflow不在本PR diff，无恢复异常阻塞证据，但尚无baseline A/B，不称纯环境flaky。尝试gh job rerun及REST均被拒绝：workflow already running（403）；证据rerun-rest-result.json。待整条run terminal后仅重跑该失败job：`gh run rerun 37247025023 --repo hugegraph/hugegraph --job 111568834909`。不放宽timeout或改fixture掩盖问题；若复现再同runner baseline启动耗时对照。267可review，尚不能merge-ready。
**兼容边界**：须挂父数据根，直接挂单数据库目录会被拒绝；旧pending先用产生它的版本完成再升级；手动restore须先quiesce请求、后台任务、queryiterators，owner lease不负责应用drain。不是wholegraph原子恢复/HStore多分区恢复/断电认证。以上不进入核心266。

## 独立生命周期 #261

R/p2-runtime-current/source-80dd+freeze-80dd.json：2719blob/mode完整匹配80dd。format/wholecompile/node11+server101+client50零fail/error/skip、rootinstall/三包PASS；最后classpath导出offline已安装parent ${revision}失败，原overallexit1保留；-am独立补跑PASS（validation-summary-80dd.json）。未改生产POM。
harness-80dd/final-runtime-summary.md/json为权威。修复证据工具的grpc版本混用/table/activeCount/logtext/fixturepackage问题，没有产品变动；原首次失败全部保留。
runtime005真实scan/query反馈等待→产品stop0/JVM143/PIDgone/client终态，006同数据restartdigest一致，007真正callback/nativeDB占用下stop31秒timeout1/PID保留→释放→JVM143/DBclosed→再stop清PID，PASS（外围wrapper mock，不作所有部署认证）。
runtime004带属性Server真实TTL FAIL：Server BytesBuffer不写cardinality/type前缀而Store Struct decoder期待，Cardinalitycode0；ttl-codec-baseline-check.json证明三codec与master一致，属于独立持久化格式问题，不盲改揉进TP。
runtime008合法无属性CUSTOMIZE_STRING约4KiB IDs/24h TTL由REST创建，TTL正常清理576ms且无codec error；observer未抓到scan/query/TTL关闭重叠，strictgate仍FAIL，保留不放宽。没有P2在跑任务，不能报merge-ready。
普通完成/receipt/halfclose/节流四评论已resolve；ordering4179028917保留root证据回复，尚无可达反例。P2用户ready提醒只在实际门槛完成时发。

## 旧 source / 网站 / 剩余范围

264的20条threads逐条回复core/后续去向：R/tp-scope/pr264-comment-mapping-current.md/json及pr264-split-thread-replies.json；不将移出范围称修复。后续新增PD plain service/exec分离274及coverage078不覆盖。C/ci-pd-packaging-diagnosis.md/json：ec9 PD cleanpackage后tests/verify通过，plain-JAR消费契约合理改进独立后置，未证明当前核心blocker，不机械移植。
网站510中英两指南匹配core普通包prepare/runtime/dataWALRaft；721tracked、focusedlinks/nav、freshstrict产物+24stockbrowsersearch PASS，未称Git-backed/fullsuite通过。ownership4176198151/precreateWAL4178842090已reply/resolve（R/website-comments-resolved-current.json）。配对review/merge，不自动合。
剩余：当前head远端CI和用户review，267 workflow结束后的MacIntel同head重试；P2 TTL严格重叠gate/既有codec独立跟进；专用dist→Docker小PR；strongownership/genericPDJDKchannels/JNI发布许可后续。不能把当前核心再绑回这些范围。

## 清理与恢复约定

前已审计归档删3顶层冗余目录+9旧source快照约7.2GB。C/cleanup-rebuildable-core-006.json、cleanup-assembly-duplicates-007.json删重复targets/assemblies/旧中间包；本轮009结束后又删38core target，逐2714源hash保持，reports与真实utilityclass副本保留。
R/cleanup-final-009/receipt.json本次删恢复003/004/006各38target（逐2708源hash不变）、core38target（2714源hash不变）及两诊断停止容器；reports/physical-parent保留。另p2-cleanup-receipt.json删P2 38target+33精确重复assembly/lib约12.5GiB逻辑字节及8停止容器，2719源两副本、3finaltar与8native数据根hash前后不变，上游reports/site归档在p2-upstream-reports。目录清单/逐文件tar或canonical对应保存在R/p2-runtime-current/cleanup-inventory。重放须从保留finaltar恢复对应lib或精确assembly，probe依赖从runtime002/client-libs恢复，再编译；不要完整tar覆盖已有runtime配置/数据。失败native数据/锁/marker和未知其他容器不笼统prune。
保留最终build005与失败复现build002包、所有reports/logs/nativeDB/crash/markers/physical-parent、trustedJNI/m2/image。其他chat的toplingdb/toplingdb-sync和旧workdir未发布内容不清理。不删native lock/pending/data来绕过失败。

接手：先查head/newcomments/CI，再读上述最终receipts；不要递归扫大证据目录。当前没有heavy/运行服务，三个agents均已完成，report均已落盘；最新运行修复四文件及core body已推/闭环。268全格式覆盖评论4179966878已修复审查推送回复；GraphQL短期限流后01:05 UTC恢复，原thread已resolve（C/review-policy-009/comment-general-resolved-013.json），未重复reply；当前context存独立records分支，不把数据库/日志上传功能PR。
