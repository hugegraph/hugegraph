# Linux 验收与恢复入口

## 当前范围与源码

更新于 2026-10-02。当前门禁仅覆盖 ToplingDB（TP）适配自身引入或放大的问题；
本 PR 新增共用代码的回归也必须修复。详细旧命令与首次失败在
[精简前完整记录](linux-history-20261002.md)，旧门禁归属不覆盖本页。

| 源码或交付 | 当前用途与状态 |
| --- | --- |
| 冻结 `9d797c7608e244f03436ce11294d9bd72aba4d2d` | Linux 基线验收使用该 SHA，用户确认其 38 项 CI 成功。 |
| 本地四项修复 | 挂载预检 `388ec8970`、任务查询 `54e5bebae`、session pool 末次关闭 `6d5893fd6`、REST FINISHED 清理 `e17f1b6d8` 已整合并推送。 |
| 此前发布 `94da2e8de5214e84f1a16d58e3124db60a96783d` | 已发布完成；旧认证/执行审批阻碍已解除。 |
| 合并 `ed6c295f0e599e5cd506a5d6aa54361b00a42081` | 保留 upstream `82034fb9f` 的 legacy scheduler 删除；三人只读审查、干净编译和 15 项定向回归通过。 |
| master `176fb56dd747ef0f60a126cf721aa12d17627c31` | 已同步删除 inactive RedirectFilter 的改动，保留本分支已完成修复。 |
| 代码同步 `de541b11d66429b5c3c7272062330192e20de5aa` | 双亲为 94da2e8de 和 176fb56dd；普通合并已发布。格式/干净编译 exit 0，34 项标准定向回归、真实 TP truncate 1 项通过，三人只读审查无阻塞。 |
| 隔离 journal/gate 候选 | 留在冻结 SHA 的未提交工作树，未整合进 PR，不作为 PR 实现或实测通过证据。 |

代码同步 de541b11 的 PR 查询为 MERGEABLE，compare master...toplingdb 的 behind 为 0。
本次 CI 快照返回 37 项，其中 7 成功、30 执行或排队（`master-176fb56/pr-after-merge.json`）；
不代表全部通过。文档提交后的最新 head 检查另行核对，不能继承冻结 SHA 的 38 项 CI。
后续通过 `gh` 核对 PR 与 master；不直接调用 Git，不重排、force-push 或自动合并 PR。

## 问题归属依据

以 TP 引入前 `337dc865`、upstream `82034fb9f`、冻结 SHA 和上述已发布 PR head 作比较。
`StandardHugeGraph` 的 create/resume、`AbstractBackendStoreProvider` 的逐 store 调用和
`OpenedRocksDB.createCheckpoint()` 的创建机制在 TP 引入前已存在。`SchemaManager` 全文件在 TP 引入前、
upstream 与冻结版本相同；`CachedSchemaTransaction` 的 upstream 与冻结版本相同。

源码确认恢复缺少缓存刷新、跨生命周期保护及全图协调的机制早已存在，应独立跟进。
保存对象应拒绝旧 epoch 是未提交候选新增的要求；三项红测没有在接入前版本实测，
不能宣称历史版本已复现相同断言。依据在 `resume-20261002/attribution/sources.json`
和 `method-comparison.json`；标准与 TP 同败不能单独证明问题早已存在。
#212 通用关停排空及 #248 旧 clear 可见性调查也独立跟进，保留其原始失败。

#249 的 WAL 扩展 `457295ac8` 是本 PR 新增共用代码：复制/发布失败、数据损坏保护、
失败后 reopen 和相关锁仍是 TP 适配验收内容，不能将整个 issue 忽略。
#252 仅等待相关 TP 正确性与资源条件，不等待通用在线恢复改造完成。

## 验收清单

| 项目 | 已有结果 | 剩余 TP 门禁与解除条件 |
| --- | --- | --- |
| #250/#251/#253 配置与隔离 | direct/init、Docker、默认/自定义 graphs、额外图、provider 冲突及所有根已实测；全图挂载预检修复已发布。 | master 同步后按实际变更回归；数据身份或 provider 改变时重新验证打开前拒绝与数据不变。 |
| #254 adapter truncate | 真实 TP 多键 truncate 1/0，清空、CF 保留、重新写读与关闭通过；标准对照通过。 | 新增相关源码变化才重跑，标准 JNI 不替代 TP 结果。 |
| #255 native diagnostic | 三组件真实 probe 通过；精确已知合成 CF 断言按约定例外；服务关闭另有实测。 | 其他 native 错误仍失败；若发现 TP 独有真实 DB/CF 关闭错误，最小复现并修复。 |
| #249 WAL 扩展 | std/TP helper、服务校验拒绝、s 库 WAL/数据树复制故障保留与重试、同时开库锁已有证据。 | 补齐服务级 WAL 发布故障及新增共用代码的损坏保护/故障恢复边界，检查材料与数据断言。 |
| #213 JNI 发行链 | 本机 ABI、CPU 与真实加载核查完成，输入字节身份固定。 | 来源闭包、许可、不可变发行坐标、CPU/系统支持下限需 producer 完成交付；本任务不发布正式 JNI。 |
| #252 性能 | 标准/TP 三轮对照 0/3。已有并发 GET 仅为正确性/接纳检查。 | 相关 TP 正确性通过、资源可用后固定源码、workload、数据、配置和资源，至少三轮并保存统计。 |

所有通过都绑定下方源码与产物；新 master 同步和未提交候选必须分别验证。

## 2026-09-28 固定源码验收进度

原始证据根：
`/home/soc-baidu/.codex/validation-runtime/toplingdb-linux-closure/accept-9d797c7-20260928/evidence/`。
数据库和临时输出在同任务的 `data/` 根；命令、退出码、计数、skip 和首次响应留在对应 evidence。

| 实测 | 实际计数或结果 | 证据索引 |
| --- | --- | --- |
| 干净标准构建 | `mvn clean install -DskipTests` exit 0；仅证明构建，不计测试。 | `01-clean-install.log` |
| 冻结标准 Core | 818/0 failure/0 error/42 skip，Maven exit 0。 | `02-*` |
| 标准 session/helper | 27+16=43 项，0 failure/error/skip。 | `03-*` |
| PD 定向 | IndexAPIClusterStateTest 2/0/0/0。 | `04-*` |
| 真实 TP helper | 27/0/0，唯一 TP JNI，实际 native maps 核对。 | `05-*`、`06-*`、`tp-classpath-source.txt` |
| 真实 TP adapter truncate | 1/0/0，包含清空、CF 保留、重新写读、再次 truncate。 | `07-*` |
| 配置/发行 fixture 与完整安全回归 | Server/PD/Store 配置、entrypoint、ownership、runtime packaging、标准/TP 包合同均 exit 0。 | `08-*` 至 `11-*` |
| 标准三组件 runtime lifecycle | Server、PD、Store 各 exit 0、phase complete。 | `12-standard-runtime-*.log` |
| TP diagnostic | 三组件前置 probe exit 0；合成 lifecycle 精确断言 exit 134，分类结果 known-cf-assertion。 | `13-tp-diagnostic-{server,pd,store}/` |
| 标准/TP 完整启动 | 专属容器中同 backend 各连续两轮 16 passed/0 failed。 | `26-*`、`27-*` |
| 整合修复后标准 API | Schema 1、Edge 5、Vertex 4，共 10/0/0/0，服务停机 0。 | `253-*` |
| 整合修复后标准 Core | 819/0 failure/0 error/42 skip，Maven exit 0；多出的 1 项为 task tx 回归。 | `258-standard-core/` |
| 发布合并源码定向 | clean compile exit 0；五类回归 15/0/0，JVM exit 0。 | `resume-20261002/remote-merge-targeted-tests-final.log` |

Maven 和 Surefire JVM 均使用专属 `java.io.tmpdir`；真实 TP 测试替换唯一标准 JNI，
记录 RocksDB.class CodeSource 与 `/proc/self/maps`。构建跳测与计数差异均不能隐去。
启动 suite 覆盖 init-store、daemon、前台 HTTP、monitor、SIGKILL 137、SIGTERM 143 传播；
目录或 marker 存在不代替 init-store 的真实表/CF 初始化。

## 产物与 runtime 身份

固定 TP 输入如下，适用于本轮 Linux x86_64，不套用于其他架构：

- JAR SHA-256：`86eb1bd3d9f84ef0dddd3fe95c640a6f145ca2d5a26a5ba628f1f298a2031fae`。
- native SHA-256：`c25ff6e676290db6db47df0954640aa609c391450ec90e1a8eec1f87e174dd38`。
- 本机 ELF64 x86_64、glibc 2.43，CPU 支持 AVX2/BMI1/BMI2，ldd 无缺失。
  ELF 最高符号需求为 GLIBC_2.38、GLIBCXX_3.4.32、CXXABI_1.3.13；这只证明本机兼容。

Server、PD、Store 的标准/TP tar 均从干净冻结源码构建。镜像来源为独立干净 checkout；
SOURCE_REVISION 为完整冻结 SHA、SOURCE_URL 为仓库 URL，标签 accept-9d797c7-{std,tp}。
独立构建和 linux/amd64 目标的完整参数、full digest 与 inspect 在 `14-image-*`、`15-image-*`。
下表 SHA-256 缩写仅作索引；原始完整值保留在上述证据及历史记录。

| 组件镜像 | RepoDigest / amd64 imageID 前缀 | 实际 runtime 与隔离 |
| --- | --- | --- |
| PD std | `3e7ae66980e1` / `86c21a3588fd` | 标准 JNI；digest 为多架构 manifest。 |
| Store std | `79197a51369d` / 同 digest | 标准 JNI，无 TP JAR/native。 |
| standalone Server std | `7733edc06dda` / 同 digest | 标准 JNI，无 TP JAR/native。 |
| HStore Server std/TP | `51953abb919f` / 同 digest | 两标签相同 imageID；Server 本地不加载 TP，实际存储由 PD/Store 承担。 |
| PD TP | `31fa84ca7629` / 同 digest | PD 服务内 JAR/native maps 和哈希已核对。 |
| Store TP | `a0df11c186ec` / 同 digest | Store 服务内 JAR/native maps 和哈希已核对。 |
| standalone Server TP | `29874890b975` / 同 digest | Server 服务内 JAR/native maps 和哈希已核对。 |

镜像文件来源见 `16-*`，standalone 服务映射见 `17-*`；三镜像 open/put/get/close probe
见 `66-*`，PD/Store 长驻服务映射后补于 `216-*`。文件存在或 provider 环境变量不替代加载证据。

整合 `e17f1b6d8` 的 TP Server 实际 imageID/digest：
`sha256:7379d24f6231eba72762593ee5d47964014e31f3b0d4ef5ec2e68ed24402ac42`。
该镜像使用包含四项修复的源码；其服务身份、核心 JAR 来源和 maps 留 `242-*`。

发布合并 `ed6c295f0` 的 TP Server manifest/imageID：
`sha256:bf12f2d0dbbdeb4205b53c24ab700cf268e3ac89148e21e525b01345ef7d4a1b`。
OCI config：`sha256:822ec5c80f9ae46537e9324eb014d0090e74a4f25036f98eeb7960309cd17947`。
该镜像从无 target 的新源码上下文 `--no-cache --pull` 构建；
source label、实际服务 PID JAR/native 哈希在 `resume-20261002/remote-merge-image-identity.json`
及 `runtime-processes-hashes`，不继承冻结服务结果。

新的 master 同步使用 `gh api` 获取完整干净源码并三方合并；16 个路径中唯一冲突是
ApplicationConfig import，保留 HugeFactory/FINISHED 清理并删除旧 redirect import/注册。
格式未额外修改源码；六类标准定向测试 34/0/0、JVM exit 0（AccessLogFilterTest 19 项，
移除的 1 项仅验证已删除 feature 的注册顺序）。实际 TP truncate 1/0/0，JAR/native
哈希与上述输入相同，CodeSource 指向新隔离 reactor，native maps 指向核验文件。
证据在 `resume-20261002/master-176fb56/`：三方源码、resolved.patch、merge-manifest、
build/test commands、日志、退出码、native identity、三份 review 和 published merge SHA。
新代码尚未重建三组件服务镜像；本次定向结果不替代完整服务验收或性能结果。

## 真实服务与故障证据

| 场景 | 有效实测与边界 | 原始证据 |
| --- | --- | --- |
| standalone 冻结 TP | 两顶点一边、Gremlin、首次同根重启通过；首次 SIGTERM 出现 db not closed/exit 137。 | `17-*`、`18-*`、`20-*`、`21-*` |
| 自定义 graphs 与额外图 | 两图实际 CF、data/WAL 全在专属父根，CRUD 与重启通过；冻结版本关闭仍失败。 | `28-*` 至 `32-*` |
| provider 反向冲突 | 标准/TP 指向对方根均 exit 1，在数据库打开前拒绝；路径/大小/mtime及小文件哈希一致。稀疏大文件未全字节校验。 | `24-*`、`25-*` |
| 挂载预检修复 | direct/init/Docker 在所有图开库前检查 m/g/s，修复 fixture 23 PASS；三人审查与真实拒绝验证通过。 | `77-*`、`78-*`、`99-*`、`106-*`、归档修复段落 |
| 1+1+1、1+3+3 | 冻结三组件 CRUD、重启与恢复实测；PD/Store 独立数据根。单宿主不证明物理多机 HA。 | `34-*` 至 `55-*` |
| 整合 TP 服务 | CRUD、20/20 GET、首次重启、两次 SIGTERM 0，无 native 关闭断言。 | `242-*` |
| 流量中 SIGTERM | 3977/3977 GET 为 200，服务退出 0，同根重启数据校验通过。 | `259-*` |
| 真实 SIGKILL | exit 137；新有效实验的同根首次图 GET 200、CRUD/Gremlin 通过，最终正常停机 0。 | `264-*`；`262-*` 首次请求失败保留。 |
| ed6c295f0 服务兼容性 | 16 CPU/4 GiB 下 20/20 GET、同根重启、两次 SIGTERM 0，实际 TP 身份吻合。 | `resume-20261002/merge-service-v2/` |
| snapshot 成功路径 | std/TP create/resume 200；重启后快照前数据可读、快照后数据 404。同进程缓存失败另列通用调查。 | `56-*` 至 `65-*` |
| checkpoint 校验拒绝 | 缺 MANIFEST 首次 resume 400、无 pending；原文件/hash恢复后 reopen、resume 与重启断言通过。 | `108-*` 至 `116-*` |
| WAL staging 复制故障 | std/TP 的 64 MiB WAL 根注入 96 MiB 日志，实际 ENOSPC；s pending 与全部 checkpoint 保留，移除仅人工源后 s 重试成功。 | `123-*` 至 `130-*` |
| 数据树复制/校验故障 | std/TP 注入 checkpoint 悬空链接，失败材料保留；移除仅测试链接后 reopen/verify 成功。未取到内层栈，只证明 s 成员路径。 | `243-*` 至 `246-*`、`251-*` |
| 同时开库锁 | std/TP 第二进程被 RecoveryLockException 拒绝，第一进程数据不变；未删除 lock 强开。不是 pending 恢复并发。 | `254-*`、`256-*` |

WAL 发布失败、嵌套/独立 WAL 与内容校验已有 JNI helper 覆盖，服务级 WAL 发布故障仍待补。
s 成员重试后 g/m checkpoint 残留不能声称全图恢复完成；该原有逐库协调缺口归独立调查。

首次失败原样保留：启动容器缺 compiler/预建目录，脚本端口/断言错误，SIGKILL 过早 GET
连接重置，JNI 路径遗漏导致重复加载 exit 134，merge 服务 4 CPU 下 17×200/3×503。
后者日志明确 maxWorkerThreads=8 的负载保护；16 CPU 后续成功不覆盖首轮失败。
详见 [历史完整记录](linux-history-20261002.md)，私有 native 日志不直接公开环境内容。

## 独立调查与隔离候选

| 问题 | 事实与归属 | 下一步边界 |
| --- | --- | --- |
| 通用缓存/全图恢复 | std/TP snapshot_resume 后同进程仍读到快照后顶点，重启后均 404。 | 按独立缓存/恢复 issue 跟进；不作为 TP 性能的统一前提。 |
| saved schema/epoch | 未提交 journal 候选的 std/TP 三项各 3/3 失败，旧 manager 旁路、旧 builder 创建 id=2 且 fresh manager 回读。 | 只证明候选未满足新增要求，历史断言未经基线实测；完整图接入不并入当前 TP 适配。 |
| journal/gate/iterator 候选 | 隔离 backend TP 恢复→truncate和启动重放各 1/0；synthetic gate 10、iterator 9，共 19/0，三人审查和复审完成。 | 不证明真实 native iterator/整体安全；16 条未提交路径与数据保留。 |
| #212 关停排空 | 真实 TP CRUD 关闭问题经四项修复改善；长 Gremlin 在途 SIGTERM仍 HTTP 500。 | 通用请求排空独立；仍关注新的 TP 独有 native 错误。 |
| #248 clear 可见性 | 冻结 std/TP、按 ID/属性、第二 Server、PD 先重启均未复现旧失败。 | 旧 a35ebeb17/schema ID 2/部署差异与首次失败保留，不自动关 issue。 |
| 路由/健康语义 | 单 Store 故障边扫描 500、PD 切主失败窗口、开库失败却 versions 200。 | 标准对照与通用平台归因在 todo.md；不声称零中断 HA。 |

候选路径：`/home/soc-baidu/.codex/worktrees/topling-graph-journal/hugegraph`。
本轮候选实测、三人报告、source manifest 和 retained data inventory 在 `resume-20261002/`；
失败后哈希无前置对照时只作留证，不能声称数据前后字节不变。
暂停扩展前另改三文件加入恢复后回调与材料保留测试，仅静态检查，未编译/运行/审查；
这些修改同样留在隔离候选，不计本 PR 修复或通过。

## JNI 来源与正式发布

[#213](https://github.com/hugegraph/hugegraph/issues/213) 保留正式发行门禁。JAR 对应 producer
构建提交 `31afa28f3d31606c1d5769a42a96ecd93420e8bc`、Ubuntu 24.04/GCC 13；资产 SHA-1
相同仅证明字节身份。workflow 动态取得 SidePlugin、shallow checkout、deploy-file，
缺少完整不可变源码闭包、attestation 和明确 CPU 下限。
2026-10-02 比较 fix-jni-ci `a46a3dfe4687f7744f0f5291a182f0d5378faae0` workflow 与旧构建版本，
文件逐字节相同；见 `resume-20261002/producer-*` 与 `252-producer-release-recheck/`。
producer POM 列 Apache-2.0/GPLv2、包页标 GPLv2，本地 release LICENSE 列 Apache 2.0，
JAR 未发现 LICENSE/NOTICE；需要 producer/发行审查，不自行发布正式产物。

## 执行环境与下一动作

主机首次核对为 Linux x86_64、i9-13900KS、32 逻辑 CPU、123 GiB RAM；Java 11.0.32.1、
Maven 3.9.12、Docker 29.1.3。历史服务清理回收约 84 GB；kind/BuildKit 已停止保留数据，
上一轮退出时无运行容器。资源值按执行时重新核对，避免累积多批服务。
历史 `/home/soc-baidu/.codex/worktrees/f29e/hugegraph` 的 WAL/channel 补丁仍不复用。

1. 代码同步及定向验证已完成；通过 gh 核对 master 是否继续前进和新 head 检查，不把新源码当冻结验收结果。
2. 检查 #249 新增 WAL 代码的复制/发布/损坏保护证据，补服务级 WAL 发布故障与失败后 reopen，不扩展成通用图恢复改造。
3. 按 #255 精确诊断合同核对任何新 native 错误；#213 继续取得 producer 发行材料。
4. TP 相关正确性与资源允许后执行 #252 三轮对照，记录缓存、顺序、数据规模与原始统计。
5. 分开提交代码与本次文档；通过 gh 核对发布源码与关联 issue 进展，不自动关闭未完成项目。

每项证据须记录源码、构建参数、实际 imageID/digest、JAR/native、命令/退出码、计数/skip、
数据断言和未覆盖边界。全部重任务串行，临时目录同时传 Maven/Surefire；启动清理只在专属容器。
代码与文档分别发布；最新远端 head 通过 gh 核对。本页保留适用版本，旧流水账移至历史档案。
