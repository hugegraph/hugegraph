# TP 拆分当前接手入口 · 2026-10-05

## 2026-10-06 用户要求先推送修复，继续本地实测

三PR本轮修复已gh-only非force提交并逐blob验证：261=719222bb8b7031524b7ee76423aaaadaa5769c3f（六files）、266=ebe465840f7169a839f85a873d5090bf08ee77c8（两批共13files）、267=82b75b07076b3b37c8d495657530b98a33945b9d（六files，重开fixture使用真实Store CF）。配套网站510=e0cb5ea822388f5bf6215aa7df2796cfddc0b568。receipt=R06/takeover-20261006/publish-*.published.json和R06/website-capability/publish.published.json。用户明确先推送方便review，随后继续测试；push不代表验收通过，不提前resolve。

261最终positive003新三项和相关取消通过，但既有扫描清理等待回调失败，P2在同冻结源码诊断；267candidate001的重开测试曾错建额外testCF，新fixture保持实际Store CF并待重验（生产未变）。266统一Java17格式通过，wholecompile进行中；新包HStore关闭/重启仍待完成。已在P2 context/P4 handoff追加新主发布notice和exactreceipt，提醒勿使用旧parent发布；这不是跨树消息送达证明。


## 2026-10-06 新主接管（覆盖旧主暂停前状态）

新主chat=01a10dc0-9a54-7463-9890-ed318c763f08；接管清单=/Users/zhu/github/hugegraph-topling-split-evidence/resume-20261006/takeover-20261006/handoff.md。已读取旧主最后两轮、三个worker当前状态及gh当前head。根checkout是master，仅协调；实际修复source/branch以接管清单为准。

266已分批提交推送6800d29b9acd8f46cad590a12a859b4adec7ae79（CodeSource控失败+动态OLAP清理四files），routing003实际Java17正反回归/格式/wholecleancompile通过、发布逐blob核验，4187965595和4187994473已reply/resolve。剩余9files包含prepare/preload、docs、heartbeat和metrics；13file候选冻结到takeover/source，统一格式/compile/相关测试/三包构建通过sharedheavy锁排队；新包HStore关闭重启仍待完成。旧p266-sync/source已修改，历史18dd2730parity不能证明当前树与18dd一致。

P2仍负责261最终positive003并分批发布；P4仍负责267，candidate001完整native回归发现sameStore重开失败，不可宣称完成；不在其可写目录重复修复。P3局部heartbeat/metrics真实AB已完成。子线程direct messaging被multi-agentv2限制拒绝、跨树collaboration不可达；旧主收到仅协调请求但确认guard未转达，agents尚未切换同步。shared记录takeover/agent-sync.md不是送达证明。定时任务继续PAUSED，不auto-merge。


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

## 2026-10-06 master同步与Java17执行基线

用户明确要求：261/266先尽早解决冲突merge master推，后续本地验证与补充另提交；以后只在实际Java17构建/测试/实测，禁止用11或21代替。旧Java11记录仅历史，不能转作新head验收。org/master当前9ed28845a0d75047bc26e3ffb3f0a3efcb5c250a（39a3 Java17+8fd Gremlin）；源码runtime/CI已升级17。

四代码PR已同步、master behind=0且mergeable=true，尚未合入：261=254381b28adcb2775e9fc4792a3eab9d2c3567f2；266=18dd8b123a23a74d77182947ea569cf1b45e462f；267=a021511e2aab9f8fef136d4751a9675c85ef21e3；268=78231ce338732cce7b974602dc2c0feb95f65918。全部追加历史/nonforce；261唯一UnitSuite冲突保留双方tests，266两launcher冲突以masterJava17 module/security/bootstrap为基底保留TP前置classpath+quoted CP，独立静态review通过。同步证据R20261006/p261-sync、p266-sync、sync-pushed-status.json；267/268 helper证据实际位于E/resume-20261005/resume-20261006/p267-p268-sync（注意这一历史嵌套路径）。

网站510=f6f5cbd0cc972b5caa9201cfde6d54fc61ee3ae6：HStore Server清provider环境两语言已推且4180479210 reply/resolve，随后明确Java17/Maven3.6.3+。所有5PR描述已改Java17基线/旧验收历史/新17未完成状态，receipt R20261006/descriptions-java17。PR标题依赖阶段保持，非独立前置不绑进核心。

actualJava17 Linuxamd64镜像固定 `local/hg-java17-amd64-validation@sha256:8eb147ec33c90d10ab7a6f8ec610739ae70028367987b3d3e1ff8d18aeb88221`，Temurin17.0.20.1/spec17、nonroot50120、Maven3.9.16、Python3.12、unzip/ldd；官方17-noble base与toolchain记录R20261006/java17-runtime。它是hostarm64上的amd64模拟，非physicalx86。一个heavy共用E/run-exclusive.py；旧11images不使用。

266拆分遗漏的2必要companion已定位：close schema cache后system auth边没有被枚举→project/access、user/belong漏删；cached vertex/edge持旧SchemaLabel indexIDs→Undefined index/清理失败。来源261明确修复；已仅移植GraphTransaction/GraphIndexTransaction+AuthTest/IndexLabelCoreTest/GraphTransactionTest五文件与coreguideJava17一处，未搬scan/TTL/queryTaskInfos。2730完整源R20261006/p266-sync/source；parent2307，candidate freeze=p266-candidate-java17-freeze.json；before/patch/payload与独立review=p266-ci-rootcause/companion-candidate。Java17 focused AB001candidate格式/wholecleancompile通过、缓存index两回归通过但Auth三项因backend系统属性null跳过；不是6项全PASS。AB002同两副本显式-Dbackend=memory负对照确4类症状复现、2boundaryPASS无skip，project primary+teardown会产生同方法多XML testcase，不能强制原始testcase总数=6而丢failure；AB003 candidate显式-Dbackend=memory六个独立方法全部PASS，零failure/error/skip，完整源码hash未变。六文件修复已单独提交并推送18dd8b123a23a74d77182947ea569cf1b45e462f，receipt R20261006/p266-companion-publish.published.json。不得改运行中源/driver，结果以java17-validation receipts为准。

实际HTTP最短3case driver已准备未实跑：R20261006/api-regression，绑定新Java17包/PID/backend；Project固定名create-delete-recreate且access0；schema/index task等完成并再写读；HStore首轮6真实vertices必须REST/Gremlin一致才取真实IDs连edge。禁止改测试名/弱断言/假ID/手造序列化绕过错误。HStore首次枚举缺ID不由两个companion静态分析证明已修，仍需实跑。

用户要求半小时复查新增comments：当前thread heartbeat id=tp-pr ACTIVE，下次本地2026-10-06 03:46:33，执行一次后自动pause；覆盖261/266/267/268/网站510，新增/更新review+普通comment逐条单独确认，已修复验证推送当前head才resolve；未确认意见保持open，有新增未确认不得标记任务完成。automation receipt=R20261006/comment-followup.json。不自动merge、不等CI全完才反馈，只需快速当前状态。此前创建的DTSTART/COUNT尝试被工具拒绝、没有重复automation；实际为hourlyBYMINUTE46BYSECOND33，prompt明确此次结束pause自身。

当前优先：已完成Java17正反对照并推送6files；正在构建当前18dd新17发布包，随后真实HTTP/API/HStore；其后261/267针对性17验收与TTL严格gate。保持小PR主线，CI与评论异步跟进，不依赖旧JDK11结果，不做未经因果确认的test适配掩盖。

## 2026-10-06 合入阶段与标题

按新交付计划编号：已合P1（Apache3265，1/4）→ core266（2/4）→ 专用发行包（3/4，尚未建PR）→ Docker/Compose（4/4，尚未建PR）。网站510与core同阶段配套，标2/4；261生命周期、267恢复、268规范直接基于master，均无阶段编号。旧264仅拆分来源，不编号、不恢复整包合入路线。并行PR共享文件，任一合入后其余同步master并复验。

- hugegraph/hugegraph#266: feat: enable ToplingDB for Server/PD/Store (2/4)
- apache/hugegraph-doc#510: docs: explain ToplingDB runtime setup (2/4)
- hugegraph/hugegraph#261: fix: release query resources and drain Store RPCs
- hugegraph/hugegraph#267: fix(rocksdb): make snapshot restore retryable
- hugegraph/hugegraph#268: chore: align review rules for code and Markdown
- hugegraph/hugegraph#264: feat: integrate ToplingDB (split source only)

标题已逐一通过REST修改并读回核验，head/base未改；receipt在 /Users/zhu/github/hugegraph-topling-split-evidence/resume-20261006/pr-titles-updated.json。

## 最新审计状态（覆盖下文旧CI快照）

R/review-priority-current/prs.json与reviews.json：266 exact3ec当前8失败check，实质为Project access删除重建、schema index清理、HStore顶点可见性及级联；不认为只是启动timeout。两个ci-diagnosis报告已落盘，须受控baseline对照责任及request/task lease交互后修复，不能合入。267 exact7e Mac ARM/Intel同head已重跑成功，唯一cancelled为cluster；之前Mac等待重试门槛已解除。268 exact5a检查通过、0open；261 exact80dd仍只有ordering争议1open及TTL本地缺口。510 exact7d新增env隔离comment4180479210，需EN/CN明确HStore Server unset provider或设rocksdb，尚未修/resolve。旧264最新6b946e30、135files/+10510/44成功checks，继续保留来源，不因绿灯恢复整包路线。详细评分/规模/合入顺序见R/review-priority-current/summary.md。

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
| 旧集成 #264 | OPEN拆分源，6b946e30d38725e1fe664be9a65cc757c7aef953；其他协作仍发布，保留不覆盖/关闭 |
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
