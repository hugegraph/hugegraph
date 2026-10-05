# ToplingDB PR 拆分任务

## 2026-10-06 半小时评论复查（已执行，自动化 PAUSED）

五PR当前head未变；完整review threads/reviews/普通comments保存在E/resume-20261006/comment-check-0342。对照旧快照与本次请求时间，261新增2条，266新增4条并重开1条，267新增3条（另有3条较早未处理），268/网站510无新增待处理。现有open线程分别3/5/6/0/0；261的旧ordering争议不按已证明缺陷计数，保留已回复线程。

待办优先：266 heartbeat fatal System.exit 与hook join死锁（4187994450）；267 data/WAL物理同目录却字符串不同导致误删已恢复WAL（4187969072）。其余：261半关闭后transport cancel传播（4187953146）和DONE后二次QUERY_REQUEST先分配泄漏（4187953158）；266 CodeSource null（4187965595）、独立OLAP动态CF路由（4187994473）、竞争JNI启动拒绝（重开4187979887）、EasyMigrate能力/配置实际效果校验（4187994484）；267直接DB symlink断链身份（4187969081）、WAL target链/link/..（4187969086）、未知marker存在性fail-closed（4187735084）、失败恢复同Store close/open（4187735095）、测试残留前缀（4187733562）。均未修复推送/未resolve，不能标记完成。源码核查与reviewer运行探针分层记录，未把reviewer结果冒充本地重跑。

Java17进展：当前18dd全部2730远端blob与源一致，p266-source-head-18dd-parity.json。Server/PD/Store三包clean package通过（构建跳过测试）；独立Core 101通过/5能力条件跳过/零失败，六新增回归全执行。真实HTTP run003：memory项目同名删除/access0/重建、RocksDB schema/index task清理后重建读写通过并正常stop；HStore需补测试镜像缺失libsnappy后继续。不能称整体CI/API/TP配置能力验收通过。

本次跟进仅检查一次，tp-pr已通过automation_update设PAUSED；不修改其他自动化。逐条audit输出在comment-check-0342，后续修复仍只用实际Java17、不等CI才反馈、不自动merge。

> 当前入口：[handoff-current.md](handoff-current.md)。用户已恢复；旧暂停与四级 stack 仅属历史。

> 2026-10-06当前：4代码PR已同步master9ed(Java17/Gremlin)，261/266冲突已静态审查解决并推。所有新验证actualJava17 only；266两个schema/auth配套修复正在Java17正反对照，HStore真实API仍待运行。五PR新说明已更新。03:46本地本线程一次评论复查，未确认新增意见不算完成。具体heads/commands/receipts见handoff-current最前执行基线。

## 2026-10-05历史快照（由当前入口覆盖）

- 266 exact3ec：范围已拆薄，生产Java/脚本新增约835，其余测试/文档；最新完整API CI有Project access删除重建、schema清理、HStore顶点可见性失败，需基线对照并修，暂不合。
- 268 exact5a：两文件格式规范，0未解决comments、当前检查通过，最适合先review；合入仍看门禁。
- 267 exact7e：12文件独立恢复替代263，native本地通过，Mac双架构同head重跑成功；仅cluster取消，兼容父根挂载/旧pending升级须深入review。
- 261 exact80dd：普通scan/query/blockedcallback通过；TTL关闭重叠strictgate未验证，ordering争议已回复保持open，后置。
- 510 exact7d：匹配266；新增comment要求HStore Server明确清provider环境，中英两页待修，配对core合入。
- 旧264最新6b946e30保持OPEN拆分来源，44checks成功不改变不合整包策略；旧263关闭且branch保留。
- 审计排序和评分：R/review-priority-current/summary.md，整体7/10，拓扑清晰9/10、当前合入闭环6/10；所有源码/产物/失败数据证据保留。当前无heavy运行。

## 以下为历史记录，不作为当前 head 或完成状态

更新：2026-10-04（Asia/Singapore）。用户已确认 deep 模式与本文件初始化。
用户于2026-10-05明确恢复当前任务；原 Linux goal 保持暂停。当前进展以恢复记录为准。

## 2026-10-05 恢复

已读取新的 ToplingDB_PR_261_263_264_Comments.md，并重新查询全部 PR。证据目录为 /Users/zhu/github/hugegraph-topling-split-evidence/resume-20261005。P2最新head c9b928ba，仅新增CI并发控制；P3/P4/网站head未变。未解决thread数量为3/4/17/2。当前核修P2普通扫描cleanup、正常完成与半关闭、任务拒绝及dist依赖；P3恢复代际和owned staging；P4 truncate status与配置选择及历史guard反馈。功能修复尚未发布或resolve，P2仍不可合入。用户要求优先更新远端master，已完成正常merge：P2 811fc77d、P3 aec1cb47、prerequisites 5d60f570、P4 16b6b57e；四者包含org/master 91fd925d（含Apache 0a3e4ae5）。P4两处CI冲突已保留矩阵/并发/可见Docker日志后发布；三个PR master behind均0。

## 2026-10-05 分批发布与清理

- P2发布打包依赖scope修复52678234，原comment已reply/resolve；下游P3/prerequisites/P4已正常同步。
- P4发布lib symlink、无效配置项/glibc文档，三条comments已reply/resolve。发布API曾将util.sh的mode置为644，已立即追加恢复为原755，内容不变，后续publisher保留文件mode。
- 检测到其他会话并发发布Docker named-context修复e8a2fe7并merge为0579ba2；保留并核其7文件更新后，P4再发布独立truncate status修复e9a202a（两个文件，故障注入回归通过并独立审查）。不以这些局部发布宣称整个P4已验收。
- P2最新完整候选与验证位于E/resume-20261005/p2-lifecycle；客户端提前close需配合新的half-close语义真正取消RPC，是本次直接兼容影响。主服务验收driver位于E/resume-20261005/p2-services，尚未运行。
- P3 source007真实JVM halt各边界及完整恢复suite/bind通过；source008额外修savedWal布局预校验顺序，待最终验证。
- P4 source003为当前配置/guard组合定位测试源；其余候选未发布，仍需组合/包/native验收。三名agent各负责P2/P3/P4，并交叉只读审查；root负责统筹、发布、服务验收和评论。
- 用户要求及时清理。E/cleanup-20261005保存审计、归档、hash验证与删除清单。已移除顶层split-runtime、split-old-runtime、split-source；源/Git状态/日志/诊断保存在同名tar.gz，可重建产物排除清单也保存。移除9个worker确认不再使用的中间source快照。当前三个工作区保留；旧hugegraph-toplingdb及hugegraph-toplingdb-sync属于此前会话的原始worktree，仍保留。E/m2-repository/acceptance镜像和所有在跑/排队源不清理。

## 历史暂停快照

用户要求先收敛已完成事项，然后暂停，不启动新事项。当前没有本地构建、业务测试服务或自动监控；GitHub CI 仍异步运行。恢复时先读 [暂停交接](handoff-pause.md)，再重新查询实际 head、checks 和新评论。

- P1 已合入 Apache #3265，org #262 已关闭。master 已同步到 `89cd937c`；后续旧服务升级、metrics/cluster 另放相关 PR。
- P2 #261 `9a44b2b7`：最终12文件已发布，三路V3独立审查、format/clean compile、Store/Node回归通过；原两条SCAN_V2反馈已resolve。新增正常完成被误报CANCELLED评论待核；真实API/query/scan/TTL/timeout-PID和新head CI未完成，尚不能报可合入。
- P3 #263 `556c41fb`：物理owner/关闭失败/lease移交6文件发布，恢复与跨JVM/bind回归通过；原owner反馈已resolve。新增checkpoint代际绑定P1、WAL staging和生产测试路径反馈待核，不能合入。
- P4 #264 `36058b0c`：已正常同步最新P2/P3前置；46个额外候选文件保留本地，未完成最终组合验证/发布，13条未解决反馈见交接快照。
- 网站 #510 `6c0f2daf`：8文件更新发布，strict Hugo/产物/14浏览器验证通过，5条已resolve；bare ownership一条需最终P4联动。
- 代码120列、Markdown约160列软换行或整段规则已在P2更新；P1旧100列评论已说明并闭环。

证据：`/Users/zhu/github/hugegraph-topling-split-evidence/comment-closure-20261004`。当前P2/P3目录为发布分支，新P4目录为最新前置加未发布候选，旧源码和数据已保留在pre-pause/pre-sync备份。最终门槛以暂停交接及实际新head为准，不沿用后文历史状态。

## 入口与依据

- 源 PR：https://github.com/hugegraph/hugegraph/pull/179 ，来源分支 `toplingdb`。
- 固定交接提交：`f6ce602cc257ea52ad96aef6b1a7aae05bf666f3`。
- [旧状态入口](https://github.com/hugegraph/hugegraph/blob/f6ce602cc257ea52ad96aef6b1a7aae05bf666f3/.goal-task/toplingdb-linux-closure/state.md)。
- [拆分交接](https://github.com/hugegraph/hugegraph/blob/f6ce602cc257ea52ad96aef6b1a7aae05bf666f3/.goal-task/toplingdb-linux-closure/pr-split-handoff.md)。
- 先读执行环境中的根 `AGENTS.md`、本文件和上述两个交接文件；模块 AGENTS、`docs/CONTRIBUTING.md` 随工作范围读取。
- 旧目录 `todo.md`、`linux.md`、`mac.md`、`lessons.md` 仅按具体问题读取，不递归加载目录或历史日志。
- 权威顺序：用户最新确认 > 产品要求/有效设计 > issue/待办 > 本状态索引。旧验证只作历史线索，不能作为本机或新 head 的证据。

## 准备阶段核对的基线

| 对象 | 2026-10-03 通过 gh 核对的值 |
| --- | --- |
| 源 PR | OPEN；head `f6ce602cc257ea52ad96aef6b1a7aae05bf666f3`；base org/master |
| org/master | `176fb56dd747ef0f60a126cf721aa12d17627c31` |
| Apache master | `02628ed502236213322e420c845266dc27e1a37a` |
| 源 PR 检查 | 准备时未全通过；执行中刷新同一head为38 SUCCESS，仅代表源PR，不代替拆分验收 |
| 当前目录 | `/Users/zhu/github/hugegraph-server`，HEAD 文件指向 master；未核验本地完整改动集合 |
| 工具与资源 | gh、Maven、Java 11、Docker 命令可见；约 78 GiB 可用；未验证 Docker daemon、Linux ABI 或 TP 运行能力 |
| GitHub 权限 | org API 返回 push 权限；Apache 合入能力未验证 |
| 旧任务目录 | 当前本地不存在，通过 gh 读取固定提交中的文件；不复制历史目录 |

以上为准备时快照，执行前刷新。已查看 org 开放 PR 列表，不能据此断言不存在其他分支、已关闭/合入 PR 或上游重复工作。

## 当前执行材料

任务记录已提交到org的`task/topling-split-records-20261003`，本次同步父提交`bf03864cc93d2ee47d58304c2abe84dcaf7818a5`；包含4份清单/状态文档及PR说明配图/提示词，不进入功能PR，不改变代码或源分支。此后本地新增状态在下一里程碑同步。

- [拆分方案](design.md)、[逐文件/修改块归属](split-map.tsv)：198文件、404项（392个patch块、12个binary/no-patch）；P1独立，P2独立，P3依赖P2无副作用开库查询，P4依赖必要前置。
- 完整只读来源：`/Users/zhu/github/hugegraph-topling-split-source`；通过gh clone获取，与固定head的2712个blob哈希全部一致。
- P1隔离构造：`/Users/zhu/github/hugegraph-topling-split-runtime`；gh clone org/master后逐blob核验基线，再修改POM/metrics/LICENSE/清单。PDStore JRaft按实测ABI错误升至1.3.14，Server保留1.3.11。
- 以下绝对路径为本机/专属主机证据索引，未随记录分支上传，不是公开附件。
- 原始API/源码核验证据：`/Users/zhu/github/hugegraph-topling-split-evidence`；来源清单、base/source完整Git tree、源码哈希核验JSON均已保存。
- P1已发布[PR #262](https://github.com/hugegraph/hugegraph/pull/262)，head `169d09bfae7edb15a713d0170534c9e0b9ea0480`；P2已发布[PR #261](https://github.com/hugegraph/hugegraph/pull/261)，head `976ecc29d5c571b25cf96659461ac6e6e0a05dc1`。均基于org/master独立提交，不触碰master或原toplingdb。按用户最新要求，完成可审查步骤及适用提交前检查后及时非强制提交并创建PR，后续服务/CI门槛在PR中明确保留。
- P3已发布[PR #263](https://github.com/hugegraph/hugegraph/pull/263)，head `2eb60f0378e31d484e0c97660f68078e6deb69ba`，基于P2，仅10个增量文件；非Draft，远端10份文件与已验证源码一致。P4已发布[PR #264](https://github.com/hugegraph/hugegraph/pull/264)，head `9af9d146659a2c8f290d231be7d0777421585f38`，base组合分支`task/topling-split-prerequisites`（head `f7428943e73e407d494aba8819ffef2462521902`）；109份增量源码。配套网站[PR #510](https://github.com/apache/hugegraph-doc/pull/510)，head `b2e9a63329201bbb52ba34e6464214a9377844da`。
- 本机Darwin arm64，Docker linux aarch64/10 CPU/约12.6GB，初次预检时无运行容器；Docker已恢复，原容器确认exit1非OOM，未重启；独立v3干净编译及标准打包已通过。真实Linux x86_64 TP能力待核实；不能将ARM测试当原生x86验收。
- 本地主要checkout与org/master的blob差异仅Struct AuthOptions及TokenGenerator删除，符合Apache新增提交内容；未触碰这些文件，不把blob比较当索引状态。
- 已发现Apache同源PR #3134，head同源、OPEN，保留不改。org现有toplingdb-auth-cascade分支不是拆分分支；P1/P2候选已发布，当前PR链接及head见上文。

## 初始化待办

以下在未来执行任务时进行，范围内已授权；完成后删去步骤，只保留有效事实。

1. 按P4需要核验Linux x86_64执行方式、ABI/CPU、真实JNI来源；本机架构与容器已核，不能沿用历史native结论。
2. 逐单元发布前刷新head/base/重复PR，复核归属与真实产物证据，补齐仍为DEFER的必要性判断。

## 目标边界与候选拆分

交付约四个易审查、各中间树安全、可独立合入且 CI 正常的功能单元；数量与边界以源码及依赖分析为准。

1. 标准运行时依赖对齐：PD/Store RocksDB 对齐及必要指标、许可证、依赖清单；补旧数据升级验证。JRaft 升级是否必要另证，不夹带。
2. 事务、请求清理和关闭生命周期：必要 schema/index 与 auth 级联删除修复一起交付。必须覆盖项目及用户关联删除、无关对象保留；不恢复仅排除负 ID 的不完整候选。
3. Snapshot/WAL 故障安全：关闭与恢复锁移交配套；验证成功、失败恢复、独立/嵌套 WAL、材料保留、锁和并发。不承诺未实现的全图原子恢复。
4. 完整可选 TP：provider/truncate、唯一 JNI 选择、ABI 和所有 data/WAL 根预检、三组件发行包、启动、Docker、CI 与产品文档。标准默认包不得混入 TP。

`RocksDBStore`、`RocksDBSessionsTest` 等跨单元文件按修改块/测试目的归属；当前标准 runtime CI 也依赖 `preload-topling.sh`，脚本、配置和 workflow 必须在对应中间树齐备。
优先独立 PR，仅真实依赖使用 stack；不按旧提交机械拆分，不为行数均衡破坏完整修复，不先合入已知不安全中间树。
TP 适配及来源 PR 新增共用代码引入或放大的回归在范围内；此前已有通用问题独立跟进，不扩大为所有平台修复。
性能 #252 可选，正式 JNI 发布链 #213 独立后置；上游依赖获取方式与真实JNI身份/功能验证仍需完成。用户后续明确JNI许可证与发版合规材料单列TODO另行处理，不阻塞本轮功能PR提交、验收与交付；不得把延期描述成已通过许可审查。

## 仓库、提交与合入约定

- 子分支建在 `hugegraph/hugegraph`；所有 PR 使用非Draft状态，默认 base 为 org/master，最终提交到 `apache/hugegraph` master。
- 版本库操作只使用 gh 内置命令或 gh api，不直接调用 git，不通过别名/脚本隐藏调用。允许 gh 自身的 clone/checkout 内置实现。
- 可用 Git Data API 创建 tree/commit/ref；更新 ref 前核对预期父提交并使用 `force:false`。与其他会话冲突时刷新分析，不覆盖。
- 本范围所需源码、环境、依赖、提交、分支、推送、建 PR、测试和审查响应已授权，不重复询问；授权不包含每次合入。
- 每个 PR 验证通过后逐个交用户 review，提供仓库、URL、head、证据与剩余风险。org 和 Apache 的合入各自需要该 PR 的明确确认，不能跨仓库沿用确认。
- 不 auto-merge，不直接推送 master，不绕过必需检查或使用管理员强合。保留原 toplingdb 和 #179，不 force-push、不重写历史、不提前关闭。
- 上游提交重新核对 base、真实前置、完整 diff 和 CI；org 绿灯或前置已合入 org 不证明 Apache 就绪。合入后记录 merge SHA，刷新后续依赖及受影响验证。
- 用户最新明确：每个可审查步骤完成后及时提交、创建或更新PR，不等四个单元或完整最终验收结束；未完成验证逐项写在PR中，不能将创建PR视为验收通过。独立单元使用多agent并行推进。用户进一步明确所有PR使用非Draft状态；ready仅表示开放评审，未完成验收仍须显式列明，不能自动合入。
- 通过适用提交前验证和审查后及时提交；代码与验收记录分别提交。提交遵循 `类型(范围): 动词开头的简短描述`，范围可省略，body 列 3–5 个核心改动。
- 任务记录保存在本地或专用记录分支，不混入功能 PR；不提交大日志、数据库和镜像。`.goal-task/`、历史 `.specs/` 和绘图资料默认不搬入上游，必要产品文档、有效测试和许可证随实现；当前JNI发版合规材料按下方用户明确延期条款处理。
- 根 AGENTS 和 CONTRIBUTING 的产品文档要求适用：网站文档受影响时准备配套 `apache/hugegraph-doc` PR 并协调合入，不能仅留后续 issue。
- 远程变更前记录确切目标及影响。JNI 交付、不可逆迁移、默认行为变更或已有支持删除先给出具体证据与方案，交用户判断；不擅自扩展范围。
- 不记录、输出、提交凭证值；仅记录脱敏的动作及结果。

## 验证与审查约定

- 各子 PR 的实际 head 使用干净源码验证；整合分支或旧快照的 CI/实测不能代替拆分树。base/源码改变后按影响复测。
- 记录源码 SHA、命令、退出码、测试总数/实际执行/skip、产物身份、证据位置及未覆盖项。跳过、排队、推送成功和 Pod Ready 均不能充当通过。
- 默认 RocksDB 构建及服务要正常；依赖升级用旧版本实际数据验证打开/升级，记录回滚边界，新建库重启不能替代。
- 真实 TP 验证核查实际加载 JAR 来源、唯一 JNI、native maps 与哈希；标准 JNI 下 adapter 测试或 provider 变量不足以证明。覆盖实际服务、重启、关闭及拒绝路径。
- 发布前按仓库要求运行 `mvn editorconfig:format`、`mvn clean compile -Dmaven.javadoc.skip=true` 和相关模块测试；Commons 必须显式 `-DskipCommonsTests=false`。精确测试命令从相应模块指导/当前源码取得。
- 文档核查链接、路径及补丁空白；仓库给出的 `git diff --check` 按用户 gh-only 约束改用 gh 获取补丁后等效检查，记录方法，不直接执行 git。
- 同一时间只运行一个重任务、一批测试服务。启动脚本的进程/端口/cron 清理只在专属测试容器内执行；仅清理本任务所有资源。用户进一步要求及时释放测试容器：成功结束且证据已保存的容器立即删除，失败容器完成诊断及必要样本归档后删除；保留仍需使用的镜像、缓存和数据。
- 保留首次失败证据，不删 pending/checkpoint/锁文件强行启动，不减弱断言或忽略退出码。修复根因后运行受影响测试，阶段冻结时做广泛验证，避免无理由反复重跑。
- 每个生产行为/持久化子 PR 及最终整合重大阶段由恰好 3 名独立只读审查者检查：先看全局风险，再分别侧重正确性/测试、设计/边界、安全/可维护性。小 diff 则各自全量审查。实施者自查不替代独立审查。
- 修正后复审受影响 diff 并实测；默认最多 3 轮修复/复审，未通过则记录依赖、后移该项，不宣称完成。纯文档或分析交付默认 1 名独立审查者。缺少审查能力时保留未满足门槛并继续独立工作。

## 持续执行与恢复

- 并行仅用于有明确收益的独立轻量分析/实现/审查；写入者分配互不重叠文件责任，说明存在其他协作者，不撤销他人改动。集成人负责最终 diff 与接口衔接验证。
- 构建、CI、下载或用户合入确认等待期间，继续下一独立单元的只读依赖分析、测试准备、产品文档/许可证核查、失败分析或状态整理；不增加并行重任务。
- 单项默认最多 3 次有依据的尝试；持续失败则记录原始错误、恢复尝试、阻塞依赖及恢复条件，将该项和其依赖后移。waiting/deferred/needs input 不停止其他可推进项，也不免除门槛。
- 资源压力先降并发、缩批次或使用已授权替代资源；网络、CI 排队、首次失败或可选依赖缺失均不构成终止。
- 只有所有有意义的剩余工作在恢复、替代方案和重新排序后仍共同依赖同一阻塞，且该条件连续至少 3 个 goal 回合出现，才设置整体 blocked；三次单项重试不等于三回合。仅用户明确要求才暂停。
- 每次有效循环简报完成结果、验证、剩余项及一个首要下一步；仅门槛分母明确时报告比例，不在所有门槛通过前报 100%。重大提交后报告 SHA 和交付变化。
- 压缩上下文、额度等待或交接前更新本文件：有效基线、最新提交、未提交改动、验证/审查证据、等待项、资源与下一步。只引用实际可用证据，旧机器路径标为历史。
- 额度不足时保存检查点，通过权威状态查询重置时间；仅在已核实原生恢复/唤醒能力时安排恢复，不凭空承诺自动续跑，不创建重复 goal、不忙轮询、不兑换额度重置。
- 本文件是唯一执行恢复入口；详细待办确实拥挤时才新增 todo.md，逐项状态仅在该文件维护，本文件保留计数和依赖摘要；有新的稳定设计或可复用证据时才按需新增 design.md/lessons.md。不创建 goal.md 或历史副本。

## 当前门槛与下一步

逐项状态仅维护在[todo.md](todo.md)。当前已构造P1/P2/P3及P4累积候选、发布四个功能PR与一个网站PR；重任务串行推进。PD测试证实旧JRaft与8.10.2存在API不兼容，P1已采用实测通过的最小升级；P4外部输入实现已完成；JNI发版合规材料按用户决定单列后续TODO。

- 全部必要功能单元合入 Apache、最终验证及审查通过、产品文档到位且无未解决高严重性功能发现后，才可完成任务；保留原 PR/分支，关闭或清理另经用户确认。
- 下一步：串行执行P4标准匿名volume镜像启动与真实Topling Server/PD/Store验收；确认实际服务JAR/native唯一加载、数据读写重启、正常关闭和拒绝路径。P1旧服务升级、P2 API/扫描TTL关闭、P3服务故障和真实Raft集群仍保留。JNI发版合规单列后续TODO。

## PR 描述规则（用户最新要求）

描述突出 before → after 的核心行为差异，通俗精炼；按内容选择对照表、ASCII、Mermaid或有用的生成配图。不在正文堆放提交哈希、日志、测试计数和排错流水账；详细证据保存在任务记录与CI，只保留简短验证结论及必要未完成项。所有PR保持非Draft。用户要求功能PR标题末尾标注 `(1/4)` 至 `(4/4)` 以便识别；编号代表功能单元，不代表线性依赖。

## JNI 发版材料后续 TODO（用户明确延期）

用户明确当前JNI许可/授权与发版合规问题之后单独处理，不影响本轮功能交付。本轮保留已查证的来源、固定版本和原始许可文本，不继续追查或以此等待/阻塞功能PR。后续发版任务需处理topling-dcompact许可授予、cspp_memtable.o与top_zip_table_builder.o预编译对象的授权及对应源码、其余传递依赖归属与随包声明；延期不代表这些材料已齐备。真实JNI来源/哈希/唯一加载、服务、重启及关闭验证不在延期范围。

## P1 兼容性测试边界（用户最新要求）

P1在现有非Draft #262上以正常新提交调整，不合入、不force-push。长期保留最小Java兼容夹具、薄脚本与一个CI job，位置为`hugegraph-server/hugegraph-test/src/test/rocksdb-compatibility/`，不进入生产二进制。用Maven effective POM读取Server/PD/Store真实RocksDB版本，PR比较base/head，push比较before/after；只执行发生变化且去重后的版本对，不维护历史矩阵，无法确定时硬失败。本次仅PD 6.29.5到8.10.2、Store 7.7.3到8.10.2，Server不变跳过。修改测试/CI需实测变化执行、未变跳过及兼容路径；真实服务、Raft、集群与TP仍单独验收。P4同步继承路径与CI；不要把此测试扩成升级工具或平台。

## 最新恢复检查点：2026-10-04

用户已恢复手动执行，并要求每个里程碑刷新全部已提交PR的head、CI、issue/review评论和未resolve讨论；确认问题后修复、实测、正常追加提交，再及时resolve。原平台goal仍paused；不创建重复goal/automation，不自动合入。

当前功能heads：P1 169d09bfae7edb15a713d0170534c9e0b9ea0480、P2 976ecc29d5c571b25cf96659461ac6e6e0a05dc1、P3 2eb60f0378e31d484e0c97660f68078e6deb69ba、P4 9af9d146659a2c8f290d231be7d0777421585f38。均非Draft并保留(1/4)至(4/4)。P1/P2独立，P3 stack于P2；P4 base组合P1与P3（已含P2），组合分支只展示TP增量，不合入master。所有org/Apache合入仍逐PR待用户确认。

P1兼容夹具与边界完成，两旧版本路径、未变/JRaft-only跳过、去重、未知版本硬失败以及真实6 JVM已通过；当前head兼容CI成功。P2事务/扫描关闭和Node普通library+exec artifact反馈已修复，中央Store34、Node11、full format/cleancompile/package/Distidentity通过；三个独立review及补充test-only复审通过，对应线程resolve。976仅暴露RISC-V stop日志，未降低exit/timeout也未证明真实关闭修复。P3正常merge继承P2，恢复native42、transaction3、MultiGraphs11（2既有skip）、Store34/Node11及package通过；typedcause断言仅改测试，原始cause保留。

P4新反馈修复9af：PD unclassified executable JAR恢复打包；Compose volume集合及每个HA节点严格挂载断言；三种Topling数据根从目录/归档排除；三个standard镜像建立默认数据根；空standard PD/Store根原子claim。恰3名独立最终review无重要未解决发现。实际Linux full format/cleancompile/cleanpackage、三组件真实JNI输入的Topling生成、发行目录/tar及dirty input不变、provider ownership/entrypoint真实争用通过；Compose v5实际render及HA负例通过（不冒称v2实测）。泄漏负例baseline0、directory1、archive1已核，先前harness误断言失败原样留证。四条功能反馈已reply+resolve；标准匿名volume镜像线程仍等真实镜像启动再resolve。JNI许可线程按用户接受的后续TODO关闭，未宣称合规已通过。

网站#510：9文件候选逐字节远端核验，双语最新指南及6篇历史notice保留原route；实际latest OINK变换/strict Hugo/artifact验证、213 source/render、24原搜索ranking及12指南/移动/语言切换/搜索浏览器验证通过。gh-only排除14项未变Gitworktree安全fixture，不声称full suite全运行。配套PR所有CI已完成成功或publish预期skip，无未resolve讨论。

本里程碑CI/评论证据在E/feedback-20261004/resume-milestone：P1/P2/P3当前head无新开放讨论；大部分CI成功，但cluster与部分PD/Store/HStore仍在排队/运行。P2新headRISC-V现成功，不替代服务关闭gate。P4新head绝大部分尚排队/运行，不报绿。新评论/CI随下一里程碑再刷新。

真实服务gate未完成：P1旧PD/Store run3已有128条数据、digest、metadata及partitionidentity通过，旧Store实际ContextClosedListener.wait循环关闭超时，栈和数据保留、容器清理，尚未升级PASS；P2独立API/TTL/scan及正常close待执行；P3服务故障与真实Raft/集群待执行；P4真实JNI actual CodeSource/native maps/hash、服务重启/正常close待执行。旧iterator租约和GremlinJob finally提交为固定base已有通用TODO，没有宣称修复。

执行材料：P4源码/109文件manifest在E/p4；新反馈证据F/p4；干净standard发行目录F/p4/actual-new-feedback/clean-standard/{server,pd,store}。源码树的standard发行目录是dirty fixture，勿用于真实服务。Topling pristine tar在P4/hugegraph-{server,pd,store}/apache-hugegraph-{component}-1.7.0-topling.tar.gz，实际服务先提取到全新专属根，不使用distribution test修改后的目录。JNI JAR SHA256 86eb1bd3d9f84ef0dddd3fe95c640a6f145ca2d5a26a5ba628f1f298a2031fae，SO c25ff6e676290db6db47df0954640aa609c391450ec90e1a8eec1f87e174dd38；这些仅身份与打包证据，不是actual加载结论。

仅一个heavy batch。准备harness分别在E/p4/runtime-server-acceptance、runtime-pdstore-acceptance、runtime-standard-images，尚未开始实际服务。当前可复用local/hg-topling-split-validation:20261004（JDK11/Python3/rsync/unzip/flock）；本机ARM64下linux/amd64属模拟，不当原生physical x86结论。重任务随进程caffeinate -i；成功证据保存后立即删专属容器，失败先诊断归档再exact删，镜像/缓存保留。不重启清理他人资源，不删pending/checkpoint/lock绕过失败。

上述E为/Users/zhu/github/hugegraph-topling-split-evidence，F为E/feedback-20261004，P4为/Users/zhu/github/hugegraph-topling-split-provider；绝对本机证据不随记录分支上传，远端状态仅为恢复索引。

## 本轮实际运行与新反馈检查点

P4 head仍9af9d14，未改产品，所有旧五条功能feedback现已resolve。三标准runtime Docker目标原样Dockerfile、named build context替换已验证Maven包，以native ARM64执行；匿名root volumes/defaultdata/provider marker/标准JNI/健康均通过。first标准harness先起Store导致PD尚未ready，原FAIL保留；只加PD readiness barrier的新环境通过。所有专属容器、匿名volumes、network及builder已exact清理，镜像保留。这不是完整Dockerfile Maven build，也不是正常关闭验收。

Topling Server server-gzip-corrected实际PASS：三服务JVM CodeSource、8.10.2 getters、唯一mapped native/hash、真实REST/Gremlin schema/vertex/edge读写、独立重启、修改删除再次重启、3次产品normalstop exit0+JVM143+PID清除，以及wrongprovider/真实旧标准库拒绝、材料不变。Linux/amd64模拟执行，不能冒称physical x86。首轮urllib未处理gzip身份应答FAILED，保存harness/raw logs/maps/stacks/data，精确修正parser和getter后fresh run通过。旧synthetic CF lifecycle仍exact known assertion134单列，不是service waiver；实际service没有abort waiver。

PDStore top-pdstore-native-wait-002实际部分通过：两actual服务JVM native身份、128KV digest、PD metadata及partition身份passed，Store产品stop0/JVM143/PID消失passed；PD产品stop0但JVM SIGABRT(-6)，native SidePluginRepo db-not-closed，重启未执行。首轮身份observer过早读null cachedversion的FAIL保留，getter只在原120s期限内等待app真实loadLibrary完成，没主动loadJNI/开DB/伪造版本。两轮failure容器已exact删，真实data/logs/maps/stacks/seed manifest保留。PD缺有序Raft/metadata关闭被TP放大，9文件限定candidate准备中，不往旧并行JVMhook盲加两行、不豁免nativeabort；owned请求/后台drain、Raftjoin与snapshotpool顺序、唯一finalclose、iterator/Slice/Options均需review和RED/GREEN。

P4新4条review4175351859/61/64/68已核，仍open：barePD/Store漏实际Spring config的ownership；dump选默认StdJNI而非selected positional graph；JDK dotted字符串算术校验；Server Docker README误称checked-in JAR且漏external secret/digest。bare/dump候选仅E/p4/new-runtime-feedback；root-candidates含JDKmajor与README，12 actual Bash解析cases+syntax通过但未产品整合/完整launcher验证，不能resolve。PDStore Spring EnvironmentPrepared有PD远端addFirst配置源，禁止awk猜data root，选actual initializer阶段；bareCLI此前未forward不擅称CLI override有效。dump按真正positional文件，不新增通用解析框架。

真实Topling truncate追加harness在E/p4/runtime-server-acceptance/truncate-preparation。first新增graph共用live meta/data/m被P3 lock正确拒绝，未进clear，FAIL原样留证及容器exact清理，没有删锁/放宽。追加gate收敛用全新专属环境的默认hugegraph，clear204→schema/data空→重建→独立JVM重启；候选修改中，未执行修正后版本。

P1 current cluster CI37141983744连续simple阶段超过5hr，root核exact169d09bf/PR262后取消以获得日志，取消不算PASS。运行用merge e0ff07c8包含currentP1，15选定blob同字节。日志仅到SimpleClusterSuiteTest+SLF4J NOP，此后沉默，无child logs/stacks/artifacts，不能认定底层rootcause；实际current P1/P2/P3旧AbstractEnv无界startup等待（本地P4已有5min不能替代）。P2/P3运行未取消。P1仍要独立隔离复现并取得node logs/stacks，不能盲rerun或降低simple/multi gate。E/F/ci-cluster-pending-analysis保存完整取消原因/源码及不足。

后续P2 service harness只准备：exact976三组件需actual fresh package；API upstream需要3个既有下载资产（JaCoCo agent/CLI及ikanalyzer），不得skip report。reaper、PID strict、API cleanup failure、TTL propertykeys精确202已证据层修正，真实API/TTL还没跑。P3 F/p3/service-fault-acceptance有6case prepared plan，但exact2eb Server包缺，不能借P4包；恢复internal切点仍42native，服务不冒称全图原子。


## 当前检查点：2026-10-04 最终 runtime 反馈收敛

P1/P2/P3/网站当前head和非Draft保持；最近两次全PR核查均仅P4有4条open discussion（4175351859/61/64/68），未以未发布候选resolve。P4远端仍9af9d14，本地frozen003是43文件增量（new9），不属于已发布head；用户已获总体粗估65–70%与P1/P2/P3/网站可review提示。JNI许可发版TODO仍不阻塞功能。

E/p4/runtime-validation/runtime-green-with-dynamic-001全部PASS：format/全cleancompile、dynamic6真实StdJNI、PD16两个fresh-fork executions、Server5跨线程session scope+2版本回归、三Std组件package/三Topo生成与clean检查。其6目录及6tar已保存E同目录/preserved-package-002，原archive绑定仍保留旧原路径与hash，不重写成新产物。frozen002三路审发现CI cleanup旧路径、Servercomma入口不一致/leafsymlink bypass、Meta initrollback close失败误报；frozen003已修，CI完整graphStep实际PASS。

E/p4/runtime-validation/runtime-final-edges-001当前单heavy：新Linux薄shell与dynamic13 PASS，PD20两个executions PASS（新增ownedBolt线程回收/独立JVM自然退）；全package/checks仍在推进。产品禁止被并行改动。最终三路003复审追加两点：前序Raftshutdown错误仍要关独立ownedRPC client；dynamic all-leaf mount preflight必须复用已有P3检测。E/p4/pd-client-finally-fix（root2文件候选）与server-mount-preflight-fix（worker2产品+2测试，dynamic15+真实Dockerbind5）尚未应用。旧storedVersion临时reader假设与bareTopmissingdir按既有契约撤回；不修旧generic、不扩大磁盘解析/升级平台。

真实Std PDStore：E/p4/runtime-pdstore-close-preparation/runs/standard-owned-close-001 128KV/digest/PDmeta/partitionidentity、两restart与update/delete、6正常stop/JVM143/PID消失、每次真实nestedStdJar/唯一SO/hash/version都通过。后续真实8686占用触发context取消/启动异常，Raftjoined+metadata/optionsclosed均有，但ownedRaftRpcClient漏关，唯一nonDaemon Bolt-heal thread使JVM180s不自然退，整个run仍FAIL。failure-cleanup产品TERM143只清理不算PASS；退出容器exact removed无-v，data/logs/maps/stacks/seed保留。frozen003补该owner shutdown与4tests（JRaft1.3.14/Bolt1.6.4实际jar/bytecode身份已存）；仍需后续真实Std与Toping4cycle+失败自然退出+原data重开GREEN。

后续顺序：完成当前单heavy并保存；应用finally/mount候选，真实kernel bind fixtures验证，不加cap/sysadmin或触碰业务库；同原3lane复审final004；fresh全编译相关测试/3包；真实Std→TopPDStore及Serverclear/dynamic/dump/负向与normalstop；normal gh append expected9af/no force，核exacthead后回复resolve4反馈/更新说明与记录。P1旧服务升级/cluster诊断，P2 API/扫描TTL/close，P3真实服务故障/Raft/集群等原gates仍未降低。

## 2026-10-04 出行期间执行约束与 review 通知

用户未来6–7小时在路上，信号不稳定，可能频繁断网；此约束对GitHub操作、下载和CI反馈刷新有显著影响。优先提前缓存不可变源码、已有Maven依赖、测试资产与镜像；本地测试使用Maven offline与Docker network=none，断网不中止已准备的本地批次。网络失败显式记录并排队，恢复后复核head再提交、回复或resolve，不能把离线旧快照说成当前远端状态。不创建新任务、不自动合入。

用户希望及时获知需要自己做的事项与可review内容；每个可review里程碑在当前聊天发送PR链接、重点和剩余验收，关键取舍才向用户提问。目前P1/#262、P2/#261、P3/#263、网站/#510代码可review；真实服务/集群gate尚未完成；P4/#264等下一版收敛提交后再发完整review通知。无需等待全部完成才通知，也不因用户路上未回复停止独立工作。

P4 frozen004为45文件未发布候选，远端仍9af9d14。runtime-final-edges-001已完整PASS并保存3种组件standard/Topling目录与归档；runtime-final-boundaries-001仍是唯一heavy，增加15项dynamic、5项真实kernel-mounted leaf契约和22项PD生命周期测试，最终结果未确认。004三路审发现两处待修：关闭失败后RaftEngine.init须拒绝残留group/server owner；embedded standard optimized disk预检只检查实际映射的store leaf，避免误拒绝unused leaf。服务正常退出/失败自然退出、Serverclear/dynamic/dump仍需fresh最终包实际验收，不提前resolve远端4条反馈。

离线准备目录：/Users/zhu/github/hugegraph-topling-split-evidence/offline-travel-20261004。prepare.py使用gh获取P1/P2/P3固定head源码与已有API套件3个官方资产，并保存sha256/固定commit/本地镜像身份；inputs.json中的ready才代表缓存完成，未验证依赖完整性不声称所有测试均可离线完成。

离线输入已ready：P1/P2/P3固定head源码tar完整可读、3个API资产实际JAR与apache/hugegraph-doc固定commit blob匹配、2本地镜像identity保存。gh raw二进制解码失败原记录保留，改用官方immutable blob核已有缓存成功，不引入依赖。P1 launcher新增显式offline-source/cache receipt，hash错误hard fail；有效缓存preflight进入本地image inspect，未冒称full cluster运行。最新联网comments快照新增P2聚合SCAN_V2关闭、P3同JVM contender OS锁保护、网站WAL/SUMMARY/裸路径三条，P4增加dynamic/customstore两条（已覆盖于未发布候选）；全部仍open等待实际修复验证。P1/P2/P3 current cluster现均CANCELLED，不当PASS；P4 Topling3Docker旧head仍FAIL，新context候选未发布。

P4 runtime-final-boundaries-001完整PASS（receipt exit0/sourcechanges空/container已清理）：format/wholecleancompile、dynamic15、真实kernel-mounted契约5、PD22两个fresh executions、ServerScope5+version2、3标准package+3Topling生成/clean检查。6目录与6tar原样转存该run/preserved-package-004并绑定receipt hash，之后Maven不会覆盖这些产物。Std PD/Store实际服务standard-owned-close-002现为唯一heavy，用此004新包验证4cycle、8正常关闭、8686占用导致partialbootstrap后自然退出与原数据重开。P4产品源码在这些service批次中保持004不变。P4源码tar9af也已完整缓存；离线具备四PR全部固定基线。

用户再次强调全PR review comments及时刷新、确认后修复更新；不确定之处不猜测，先报告证据边界，需要关键范围/取舍时向用户确认。当前P2 aggregate-close-feedback已交付6文件冻结候选和6 meaningful测试，仅Java11语法检查未运行；P3 physical-lock-feedback候选正在准备，仅task evidence写入。原3独立lane复用进行审查，容量不足时串行，不再增加review lane。P3 registry不能因为JDK fileKey允许缺失而新增平台限制，fallback使用Files.isSameFile并对身份查询失败显式失败，不猜物理相等。

P4 standard-owned-close-002完整PASS：004真实PD/Store标准JAR与唯一native 8.10.2、128数据/PDmeta/partition/更新删除新增、两普通重启、4cycle共8次产品正常stop/JVM143/PID消失；真实8686占用的partialbootstrap在Raftjoin/metadata/options close后自然exit1，无TERM/abort豁免，原数据再重开/校验通过。9实际JVM身份均验证，exclusive网络none、Linuxamd64模拟、非physical x86；成功容器已exact清理。先前Std001的Bolt180s FAIL证据保留。接续topling-owned-close-001现唯一heavy，仍用004源/包，P4源码保持不变；最终2边界候选/P2/P3新反馈未发布。

网络恢复后用户授权新开P1/#262收尾会话，已创建 thread 01a10592-3085-73e0-ba74-11b0f4403e14（local hugegraph-server项目）。该会话独占P1源码runtime，核Server JRaft统一1.3.14与全局单变量的真实兼容性，以及Markdown不需80/100换行的原因并按≤160/完整段落偏好修本PR；不自动merge/no force/gh-only。本会话不再编辑P1，后续主动读新会话结果并同步P4前置。两会话所有新heavy用E/run-exclusive.py标准fcntl共享锁，不能同时跑；原P2 aggregate-close-001已exit0/源码不变/容器清理。

P2 round2六文件已仅本地应用，并实际format/wholecleancompile/Store suites/Node测试完整PASS，仍未发布/resolve；新13项聚合query响应与关闭测试的XML随receipt保存。P3五文件物理RecoveryLock候选仅本地应用，还未实际编译/native/kernel验证。P4源码仍004，Std002与Topling001 actual4cycle、8normalclose、bootstrap自然退出/原数据重开均完整PASS；新增最终2边界及P2/P3继承尚待整合/实测。

## 用户明确暂停：2026-10-04T14:42:45.412108+08:00

只暂停本会话；用户明确确认独立PR#262会话继续。主goal已set paused，两个在跑的只读review已interrupt；主会话最后P3 physical-lock-001已于14:32正常exit0并exact清理，当前无本会话heavy，不能开启新goal工作或发布/修复。保留P2 round2清理失败屏障未解决finding，不在暂停后修正。

13:00至暂停的审计：E/pause-audit-20261004-1300/README.md与audit.json，P2 6文件/P3 5文件均13:58:42本地应用、没有功能PR提交；逐文件before/current/hash与p2.patch/p3.patch已保存。P4 11:06冻结源码133路径核验保持不变，13:00后只测试/包转存/未应用候选。记录分支13:12:59正常提交bf86ae33（3任务MD），本地之后还有检查点未发布。#262独立candidate另附只读审计快照，仍运行，不属于主会话暂停。未执行revert、不改历史；恢复或还原必须等用户新指示。

## 当前会话接手修复：2026-10-04T16:17:23.928226+08:00

用户在当前会话选择保留结构、局部重写，并授权执行重构与修复，要求交叉验证、简洁设计、不确定关键事项先汇报确认。旧会话保持暂停；不新建goal，不改原Topling分支。P1独立收尾已完成：org#262与Apache#3265同head e7c2b59c0123c6c4564ed6c34113600cbe062a7d；剩余旧PD/Store升级与metrics/cluster。

本轮证据目录 E/handoff-repair-20261004，已核P2/P3暂停11文件未漂移，并保留start/before。P2局部修清理成功/协议终态/worker归零，保留Service/Listener；P3保留物理owner，补真实descriptor/native失败边界并局部修复。P4两处owner/mapping候选与WAL文档已hash guard应用。网站六条反馈正在同步；尚未提交、resolve或合入。所有heavy继续 E/run-exclusive.py 共用fcntl锁。

P2/P3实现候选已冻结，开始串行实际format/wholecleancompile与模块/failure/kernel fixtures。collaboration新审查线程遇配额上限，最终交叉审查改用已安装Codex CLI ephemeral/read-only独立三路，不降低门槛；待实际启动及结果，不能预报审查通过。
