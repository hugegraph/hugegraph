# ToplingDB PR 拆分任务

更新：2026-10-04（Asia/Singapore）。用户已确认 deep 模式与本文件初始化。
用户已明确恢复执行；已发布四个功能PR与配套网站文档PR，尚未合入，原 Linux goal 保持暂停。

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
