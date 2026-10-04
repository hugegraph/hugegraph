# TP 模型切换交接 · 2026-10-05

用户现在要求立即保存高信息熵上下文，确认可切换后再继续。停止新增实施/测试批次/任务；本文件为下个模型第一入口。没有为本会话创建goal；旧Linux goal仍暂停。

## 用户最新决定（覆盖旧大集成目标）

保留Server/PD/Store三组件TP接入，但通用生命周期、事务/auth/schema整理、全面查询硬化和可中断恢复协议独立后续，不作为TP核心合入前置。用户认可拓扑：master（P1已合）→干净TP核心→后续专用发行包→后续Docker/Compose；通用生命周期与快照恢复各自独立。每个PR应有独立可验收结果，不能把约1万行配套全揉入核心。

允许使用新分支/新PR拆分替代旧PR。必须从master构造干净核心，不可只改旧#264 base：其祖先含P2/P3，合入后会污染后续通用PR的合并关系。旧PR保留提交/讨论作拆分来源，替代链接齐备后再明确处理；没有关闭原PR、没有建新核心PR。已有agent合理修复仍及时提交对应旧PR，不因scope重组积压；已验证发布后resolve，存疑/不同判断回复说明。不要强推或自动merge。

用户希望主动同步核心发现、设计取舍、发布/评论闭环、验收阻塞和可review门槛。当前关注可合入的通知改为新TP核心，非把P2/P3当TP门槛。

## 远端已完成（需恢复时再gh刷新）

| PR/分支 | 最后确认head | 状态 |
| --- | --- | --- |
| Apache master | 0a3e4ae5f64a61972ee3d230b1281f780afbe067 | P1 Apache#3265已合，org#262关闭 |
| org master | 91fd925d4cdcfb6503a5bca3244909dfcf18d57c | 含Apache新master及CI并发控制 |
| #261 task/topling-split-lifecycle | 526782342b87e541bcf28010470fef554af118cb | 已同步master，新增dist exec依赖runtime scope已推并resolve |
| #263 task/topling-split-recovery | f8b29c82f1319894e931218c816165c04f42754a | source007五文件generation/operation staging/生产恢复测试已推；原4comments已resolve；另有未解决本地review见下 |
| #264 task/topling-split-provider | e9a202a240becdf926c9c2531b35a046354f21c1 | master及scope修复同步；lib symlink/无效config/glibc文档已推并resolve3条；truncate/status两文件已推 |
| prerequisites | fc42a940589b160853f52d9a99e4642628981302 | 含P2打包scope；未含最新P3 f8修复 |
| website apache/hugegraph-doc#510 | 6c0f2dafa829d373992702f20321319452704097 | 本轮两个guide本地改动未推；后续须匹配缩范围实现 |


其他会话并发发布过Docker named-context修复e8a2fe7，merge为0579ba2，root在其上发truncate e9a202a。已核7个Docker/CI/docs文件变化并保留，不能用旧本地文件整树覆盖。root初次小提交误把util.sh mode置644，已追加7ccd3d65恢复原755；publisher已修为保留原mode。

## 当前工作目录与核心未发布状态

E=/Users/zhu/github/hugegraph-topling-split-evidence
R=E/resume-20261005
主记录：/Users/zhu/github/hugegraph-server/.goal-task/toplingdb-pr-split

新的核心工作源：R/tp-core/source，基于org/master91fd925d。**尚未建Git分支/PR，未编译、未实测。**

关键缺口：GitHub tarball遵守export-ignore，漏了67个tracked文件（.editorconfig/.gitattributes/.github/docker等）。root已取完整R/tp-core/master-tree.json，但尚未逐blob补足。先按tree补缺失文件，禁止覆盖agents已改文件，然后重新生成baseline manifest/候选diff。原master-manifest.json只有tar的2637文件，不能声称完整HEAD快照。

新核心约定：显式TOPLINGDB_ROCKSDB_PROVIDER=topling；默认standard；业务rocksdb.provider与实际runtime一致；基本prepare用外部受信JAR+SHA并核native/平台，组件私有TOP/topling目录，避免改变standard lib扫描。仅支持明确发行launcher、独立空目录、停机后切runtime。不要搬全量HugeConfig/ConfigData预选、严格目录marker/ancestor/leaf/mount协议、P3恢复锁/状态协议、P2通用事务/scan框架。专用发行包生成和Docker后续独立。

- /root/p3_resume负责新source Server adapter：RocksDBOptions、RocksDBSessions、RocksDBStdSessions、RocksDBStore及RocksDBSessionsTest。已落地5文件，保留CF truncate/pending discard/backend-version/status传播；master直接owner，OpenedRocksDB零diff。实际open调用org.apache.hugegraph.util.RocksDBRuntime.verify(String)。尚未验证。
- /root/p4_resume负责新source launcher/shared shell、prepare/preload、assembly、启用配置、Commons薄runtime helper和PD/Store初始化及Store最终bound核对。目前helper/PDStore Java检查已写，shell/assembly其余进度看launcher-handoff。未启动验证。
- 正常PD native owner关闭链尚无实施owner：P4旧候选有真实历史failure→pass证据，但不能整个搬入。P2agent只负责查必要性证据，未领此实施。新核心必须实际验证三组件读写/正常停机/重启，不能豁免PD native assertion。
- root负责新核心docs/CI与规划/发布；尚未开始核心docs/CI修改，不复制旧9k配套。

## 旧P2未发布工作（后续独立，不丢）

目录/Users/zhu/github/hugegraph-topling-split-lifecycle；R/p2-lifecycle/source-006及p2-v3-freeze-006.json为最近完整通过的冻结。p2-resume-module-006 receipt：format、whole clean compile、Store五profile、Node tests全部exit0，source_changes={}，容器移除。

source006含五个普通scan入口清理屏障/黏性失败、正常完成与cancel、拒绝任务、unsupported idle批量终态清理、aggregate半关闭。P3独立review通过（R/p2-independent-p3.md）。**本地之后有未测客户端配套：QueryExecutor/CommonKvStreamObserver提前close真正cancel与半关闭协调，以及加强真实client cancel断言；006不能覆盖它们。** 不可仅发布服务端half-close让旧client早关失效。按两组收敛：普通scan一组；halfclose+client配套另一组。

最新gh查询#261仍4open：4178230241正常完成伪cancel；4178878594请求半关闭；新4179028913 receipt出stateLock后观察DONE竞态（需要最小修）；新4179028917有序scan背压释放queueLock（agent核为不成立：有序putData(data,hasNext)只要当前vertex未尽就offer阻塞并返回true，评论误套无序重载；尚待将证据回复，未resolve）。精确thread数据R/pr-261-latest-status.json。

旧query0同步资源释放及aggregate parent拒绝UNKNOWN是已识别通用旧问题，未纳本轮新修；不能宣称所有query RPC已覆盖。当前停止新增实现，agent将写R/p2-lifecycle/handoff-model-switch.md。

## 旧P3已推与剩余边界

E/p3-resume-20261005/source-007五文件精确payload-007.json已推f8。validation007：真实checkpoint/WAL、独立JVM halt stage/retire/publish/pre-reopen、重复中断→真实native重开读写、foreign staging保留/own staging收敛、完整恢复suite及parent-bind通过；全编译在006（production bytes相同）。原四线程已reply/resolve，R/p3-comments-closed.json。

额外未解决：source007对savedWal的语义比较晚于restoreLink，损坏合法absolute savedWal时会先重建缺失alias再拒绝（无数据删除证据）。source008加手工resolveWal却引入合法symlink/..语义回归；**008绝不发布**。排队validation008已root取消PID17527，未开容器。后续若继续应避免扩大手工path parser，可考虑绑定完整metadata校验后沿用实际canonical流程，尚未实施。

独立拆离P2只识别databaseOpened()四行API桥接；不能带P2整套test suite。报告R/tp-scope/p3-base-bridge.md及p3-dependencies.md。

## 旧P4候选与证据（后续拆分来源）

/Users/zhu/github/hugegraph-topling-split-provider仍保留大组合未发布候选，不能整树发新核心。
R/p4-review/java001：format/wholecompile通过，Sessions25含status两故障用例通过、Dynamic16通过；Runtime重复注册测试失败。
java003：Runtime2、Dynamic19、PD Local1、Store Local2+Bound2全部通过，Server真实package成功；packaged fixture路径字符串含/./比较失败，后续shell没跑。exit1/source_changes={}。整体selector/强guard没有完整验收，已后移，不再为此扩大实现。
薄auto-conf+safe trap独立payload：R/p4-review/auto-config-payload/，基于e9a202a前后需再核head；parent/child PD↔Store/explicit/default切换fixture通过，JNI为明确替身；尚未发布。
P4 truncate精确两文件已发布e9，R/p4-truncate-publish.published.json；独立review R/p4-truncate-independent-p3.md。

## 最后完整性复核与修正

三名agent均已保存交接并停止。新core9个改动文件SHA已逐项核对两个agent清单，记录R/tp-core/model-switch-checkpoint.json。当前无本任务运行/排队重任务，docker ps无运行容器；无需继承旧exec session继续等待。

**补充重要证据边界：P2 source006和P3 source007同样缺GitHub archive export-ignore排除的67个tracked配置文件，根.editorconfig不存在。** 路径审计R/p2-006-master-path-audit.json、p3-007-master-path-audit.json。此前模块compile/tests确实通过，但不能把archive说成完整HEAD树，也不能把无根配置的editorconfig:format当最终格式验证。恢复后按对应精确head完整Git tree补足tracked配置，重新做适用格式/最终检查；不删除或否定已有真实native/模块测试证据。已发布小修还需按补齐基线检查；不要冒称CI全绿。

三个子交接入口：R/p2-lifecycle/handoff-model-switch.md；R/tp-scope/server-adapter-handoff.md；R/tp-scope/launcher-handoff.md。范围/依赖依据：R/scope-review.md、R/tp-scope/p2-dependencies.md、p3-dependencies.md、p3-base-bridge.md。

## 验收/工具约束

所有版本库操作gh-only（gh或gh api），不用git、无force。正常追加提交，commit类型feat/fix/chore/docs/refactor，简短小写动词标题+3-5正文点。代码120列、Markdown160软换或整段，不强100。新核心下一PR仍需带正确review规范（原P2 .coderabbit.yaml/贡献指南小块，按新master核后挑入）。
一个重任务：python3 E/run-exclusive.py <command>。专属Docker无网络/host端口、linux/amd64在Darwin arm64模拟；不能说原生x86。共享镜像local/hg-topling-split-acceptance:20261004与E/m2-repository保留；不触业务DB、他人进程/容器，不删pending/lock/checkpoint绕过失败。

新API+SCAN_V2半关闭/活动query+scan+TTL driver在R/p2-services（未执行，不必当新核心门槛）。真实阻塞callback fixture在R/p2-lifecycle/BlockedScanCallback.java、blocked-callback.sh、run-blocked-callback.py（未执行）。不把这些“已准备”当验证通过。

发布助手R/publish-files.py：expected-head检查、保留mode、非force更新ref、逐blob核验。旧PR发布前重新查HEAD，存在其他会话并发写#264。

## 清理已完成

E/cleanup-20261005记录source/Git/日志/非构建数据归档hash校验和删除范围。移除3个旧顶层split-runtime、split-old-runtime、split-source以及9个中间快照。约7.2GiB为当时磁盘可用增量观测（同时有其他测试）。当前3工作区和E保留。hugegraph-toplingdb、hugegraph-toplingdb-sync是旧会话原始worktree，未动。不要再引用已删除source001等目录执行；需要时用cleanup归档还原。失败logs/receipts/reports仍在。

## 恢复优先顺序

1. 读本文件、三个agent交接文件与scope-review.md；刷新实际PR heads/comments、ps/docker状态。
2. 核所有agents已停止写；记录当前新core diff；先补master tar缺67tracked文件再编译。
3. 继续最小TP核心，分工正常PD/Store native close必要修复，完成三组件实际验收；不重新把通用矩阵塞回来。
4. 已验证旧P2修复按独立组及时提交；P3已推部分保留，008不推；有依据的评论回复/resolve。
5. master干净分支创建新核心PR，附旧#264替代/后续映射；不自动merge。专用包/Docker/通用改造独立后续。
