# Mac 开发与恢复入口

与 [state.md](state.md) 一起读取即可恢复本机工作。本机 Linux 容器的核心实测记在这里，
远端 Linux 服务器验收只在 [linux.md](linux.md) 维护。

## 基线与下一动作

本机执行基线 `7c7519b79c445c8e3c03a510f9c6aa7484fc0103`，工作目录
`/Users/zhu/.codex/worktrees/topling-local-fixes/hugegraph-server`，detached HEAD。
远端 hugegraph 为 https://github.com/hugegraph/hugegraph.git，推送目标 `HEAD:toplingdb`。
主 checkout 的无关文件及 Linux 历史未提交补丁未搬入本轮。
恢复时重新核对 HEAD、工作区和远端；本轮最终提交见下方交付记录。

2026-09-27 更正：上一轮已交付本机定向验证，但整体 CI 门禁未收口。
完整 CI 随后发现恢复锁相关的标准多盘回归，以及本 PR 新增 PD 测试的 package/install 编译失败。
本机补救代码已验证并提交，远端 CI 待复验；不能把前轮 goal 的 complete 标记当作当前代码已具备合入条件。
当前下一动作是核对补救提交的推送结果及新 head 的相关远端 CI；
Linux 可先按已交付 SHA 验证独立项目，但须在新提交后复验受影响场景。不自动合并 PR。

## 开发清单与验收

| Issue | 本轮行为与证据 | 开发状态 |
| --- | --- | --- |
| [#250](https://github.com/hugegraph/hugegraph/issues/250) | 图配置决定有效 provider，环境双向冲突、多图混用明确失败；缺省保持 RocksDB | 实现及定向回归完成，最终交付见下方 |
| [#251](https://github.com/hugegraph/hugegraph/issues/251) | 读取实际 REST graphs，路径与 Java 启动工作目录一致；相对、绝对、空格、缺失目录、backend 大小写均有 fixture | 实现及定向回归完成，最终交付见下方 |
| [#253](https://github.com/hugegraph/hugegraph/issues/253) | direct/init 与 Docker 在 DB 打开前检查全部 data/WAL 根；标记原子认领、并发复查，拒绝冲突、符号链接、根目录和非空 data_disks | 实现及定向回归完成，最终交付见下方 |
| [#254](https://github.com/hugegraph/hugegraph/issues/254) | 实际 adapter 覆盖空、单 key、乱序多 key、空/二进制/无符号边界、多 CF、清空后读写；标准重建 CF 与 TP 范围删除分别断言 | 标准及真实 TP adapter 回归完成，原问题为覆盖不足，未据此认定历史数据损坏 |
| [#255](https://github.com/hugegraph/hugegraph/issues/255) | 独立前置 probe 必须通过；仅 cf-lifecycle + 134 + 精确已知断言且无其他错误可例外，取消无条件 continue-on-error | fixtures、真实 TP 分类完成；已知合成 CF 断言仍存在，#212 未关闭 |
| [#249](https://github.com/hugegraph/hugegraph/issues/249) | 恢复前持久化 pending、保留原 checkpoint，校验安装，正常 open 先重试未完成恢复；锁贯穿 DB 生命周期与重开，拒绝单 DB 挂载 | 标准与 TP 成功/故障回归完成；不声明断电持久性或非协作旧进程安全 |

配置解析不能等价支持的 includes、续行、转义 key、重复/插值相关值明确拒绝，避免脚本与 Java 分叉。
标准 RocksDB 的既有非空无标记目录保留兼容；新空根会认领标准 marker，TP 仍要求明确隔离且已存在的根。
挂载父 data 根受支持；单 DB 卷挂载在打开前拒绝，Linux 还拒绝同文件系统 bind mount。
恢复源只在 native 重开成功后尝试清理；异常保留源与 pending，不能删除 guard 强行启动。

## 最小验证与审查

以下为上一轮 `cf25a438a50f93f96efce74b9e4b0cacce4fac91` 所包含代码的历史定向证据，
不代表完整 CI 已通过，也不继承为本次补救代码已通过。证据目录 `/tmp/topling-local-20260927`，原日志不提交。

| 验证 | 结果与边界 | 日志 |
| --- | --- | --- |
| root `mvn -o editorconfig:format` / `mvn -o clean compile -Dmaven.javadoc.skip=true -ntp` | 格式与完整 reactor 编译通过；离线使用已安装依赖 | format-delivery.log / compile-delivery.log |
| Mac 标准 JNI 定向测试 | RocksDBSessionsTest 16 + RocksDBSnapshotRestoreTest 24，40 项无失败/错误/skip | java-tests-delivery.log |
| Linux arm64 shell | selector 31、roots 16、ownership 8，55 个 PASS；真实 provider verifier，runtime/启动用隔离 fixture | linux-shell-final.log |
| native diagnostic fixtures | 27 个 shell 场景及 2 个 Java 清理断言，29 个 PASS；覆盖混合错误、大小写、错误状态/阶段、缺前置、真实 C++ 表达式 | diagnostic-cpp-final.log |
| Mac 标准 runtime | probe 与实际 CF lifecycle 通过；修复已关闭 handle 的 equals 调用后复跑 | smoke-probe.log / smoke-lifecycle-fixed.log |
| TP adapter truncate / independent WAL | 各 1 项通过，实际加载 TP JAR 与 native | tp-adapter-truncate.log / tp-wal-separate.log |
| TP WAL helper | 24 项无失败/skip，覆盖故障安装、短复制/同长损坏、缺源、启动重试、独立/嵌套 WAL、符号链接、进程锁、重复恢复 | tp-wal-faults-delivery.log |
| TP native 分类 | probe 读写/遍历/关库成功；合成 CF phase 实际 134，分类 known-cf-assertion，wrapper 退出 0 | tp-native-diagnostic-verified-fixed.log 及同名目录 |
| 挂载 admission | TP 的单 DB host mount、同文件系统 bind、带空格 mount 均在变更前拒绝；父根双别名共享锁，关库后可重开；Mac HFS+ 卷在数据/锁写入前拒绝 | bind-guard-all-final.log / mac-mount-guard-final-fixed.log |

原有 Server/Docker、PD/Store entrypoint fixtures 通过；
entrypoints-linux-final.log 保存 Linux entrypoint 最新结果。
新 helper/fixture shellcheck 无新发现，actionlint 对比基线无新增发现。
GitHub 本次代码 head 的 Commons/dependency-check 等失败日志指出 TP 图片索引三文件缺末尾 LF；
文档批次补 LF，`mvn -o editorconfig:check -ntp` 通过（editorconfig-check-delivery.log）。
741c 的 Commons 检查仍失败，此时 LF 修复尚未推送；须核对本次文档批次的新 head，不能继承旧 run。
旧交付将 hg-pd-test 编译失败当作本轮 diff 外的独立门禁，这个归属判断不完整：
IndexAPIClusterStateTest 由本 PR 的 `327737f16` 引入，虽早于上轮 goal，仍由本分支负责。
本次归因和修复见下方 CI 补救，不能将“定向测试已交付”写成“本机代码收口完成”。
没有完整打包三类正式镜像，没有做性能压测、多节点拓扑、断电或进程强杀恢复实验。

Java 命令在仓库根目录执行，临时路径同时传给 Maven 与 Surefire fork：

```bash
mvn -o test -pl hugegraph-server/hugegraph-test -am -P unit-test,rocksdb \
  -Dtest=RocksDBSessionsTest,RocksDBSnapshotRestoreTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false \
  '-DargLine=-Xmx512m -Djava.io.tmpdir=/tmp/topling-local-20260927/java-delivery-final' \
  -Djava.io.tmpdir=/tmp/topling-local-20260927/java-delivery-final \
  -Dmaven.javadoc.skip=true -ntp
```

三名独立只读审查者检查行为和数据安全 diff，并对修正后的受影响部分复审。
审查发现的锁转移、direct marker 绕过、配置/存储 symlink、根目录、并发 marker、
诊断大小写和同文件系统 bind 漏检均已处理；最后结论见交付记录。

Docker Server 的 ABI CI 探测绕过 entrypoint，新增根校验使未准备默认路径的旧 fixture 失败。
补齐临时 probe 的 data/WAL 空目录后，真实 selector/marker/native probe 通过，
未关闭校验或改写用户图配置；日志 docker-ci-server-probe-final.log。

## CI 补救与完成状态更正

2026-09-27 核对 `cf25a438a50f93f96efce74b9e4b0cacce4fac91` 的全部 18 个失败 job，
实际只有两条根因，不是 18 个互不相关的故障：

- 16 个 job 在 package/install 后编译新增 PD 测试失败。Spring Boot repackage 把主 artifact
  改成 BOOT-INF/classes 布局，compile 阶段的 target/classes 能解析，打包后的依赖 JAR 不能解析。
  修复为薄主 JAR + exec 可执行 JAR；发行包只带 exec，启动脚本保持匹配唯一 Boot JAR。
- 2 个 Mac job 在 `MultiGraphsTest.testCreateGraphsWithMultiDisksForRocksDB` 失败。
  新恢复 guard 把既有 checked RocksDBException 契约改成 BackendException，破坏锁争用/shared CF 分支。
  公共 open 现保留 checked IOError，并用结构化 contention 分类，拒绝目录名里的错误子串绕过。
  只有活 native owner 可复用，检查、复制、session 初始化与缓存登记对同一 owner 的恢复互斥。

失败 run：[Server](https://github.com/hugegraph/hugegraph/actions/runs/36270918750)、
[PD/Store](https://github.com/hugegraph/hugegraph/actions/runs/36270918555)、
[Commons](https://github.com/hugegraph/hugegraph/actions/runs/36270918548)、
[cluster](https://github.com/hugegraph/hugegraph/actions/runs/36270918538)、
[CodeQL](https://github.com/hugegraph/hugegraph/actions/runs/36270918537)、
[dependency-check](https://github.com/hugegraph/hugegraph/actions/runs/36270918547)。

| 补救验证 | 当前结果 | 证据文件（同一 /tmp 目录） |
| --- | --- | --- |
| clean PD package 最小复现 | 修复前缺两个符号，修复后 BUILD SUCCESS；未 clean 的旧 classes 曾掩盖失败 | repair-pd-clean-package-before.log / repair-pd-clean-package-after.log |
| 完整 root clean install | 所有模块成功；首次离线缺未缓存 shade plugin，联网补齐后实跑成功 | repair-root-clean-install-online.log |
| PD artifact / distribution | 主 JAR 有普通 classes，exec 有 BOOT-INF；发行包唯一 exec，manifest JarLauncher/HugePDServer 正确 | root install 后 zip、manifest 和 dist 核对 |
| PD 新增 mock 回归 | IndexAPIClusterStateTest 2/0/0/0 | repair-pd-index-tests.log |
| PD 实际发行包启动 | Mac JDK 11，独立临时端口/data，java -jar 后 actuator health UP；仅终止本次进程 | repair-pd-boot/server.log / result.json |
| 标准多盘最小复现 | 修复前 1 个失败；首次契约修复后通过，最终版本纳入完整核心套件 | repair-multidisks-before.log / repair-multidisks-after.log |
| 标准 CoreTestSuite | 最终源码 suite 818/0/0/42；合并执行命令整体失败因 session 单测用了旧共享 temp，不能声称该命令退出 0 | repair-core-and-unit-final.log 及 CoreTestSuite XML |
| 标准 session/helper | 最终版本用新专属 temp 43/0/0/0，命令 BUILD SUCCESS；合并命令的旧 pending/缺 checkpoint 失败日志保留，未删除旧 marker 绕过保护 | repair-unit-tests-complete.log / repair-core-and-unit-final.log |
| 真实 TP helper / adapter | 最终版本 27/0/0 与 1/0/0；首个仅锁复制的补丁被真实 TP 并发用例证伪，扩展至初始化后通过 | repair-tp-helper-final.log（失败）/ repair-tp-helper-complete.log / repair-tp-adapter-final.log |

三名只读审查者复查本次最终 diff；发现的异常消息误判与检查/复制/初始化竞态已修复，最终均无阻塞发现。
此次保证同一 owner 的初始化与恢复互斥；不同副本后续独立恢复的引用重绑定属于既有共享生命周期边界，
未扩展为支持任意副本并发恢复。真实服务验收应沿实际 Store 生命周期执行，不能泛化单个并发用例。
本机补救验证完成，代码 head 为 `dfd4ce07e98e5846a2a093750f4b59a3061ab393`，PD 打包提交 `5401221996d7af71a0ea9a4243b5f0d1529237cc`。
最终格式、完整 clean compile 与 git diff --check 通过。71 个相对链接/锚点与 Linux bash 命令语法通过，
四份历史档案保持逐字节一致；一名文档审查者指出的临时目录隔离问题已修正。
当前整体收口仍待相关远端 CI，不能仅凭本机验证、推送或旧 goal complete 再标记完成。
Linux 接收新源码后复跑多盘/恢复锁、PD 打包与实际服务场景；服务器结果仍只在 linux.md 维护。

## 本机核心实测身份

Mac arm64，Homebrew Java 11.0.32.1、Maven 3.9.16。OrbStack 容器运行 Linux amd64/JDK 11，
CPU 架构经模拟，限 2 CPU/2 GiB/Java heap 512 MiB，测试时禁网；不用于性能结论。
镜像 `local/topling-core-check:20260927` 只提供 JDK/native 依赖，源码和编译 classes 从工作树只读挂载。
镜像 manifest `312e13b20d9e990dbd3b01bbdfb963b4464c7fe6f037b33a5009fa367ee94737`。

- JNI JAR：`rocksdbjni-8.10.2-20260725.141011-1.jar`，SHA-256
  `86eb1bd3d9f84ef0dddd3fe95c640a6f145ca2d5a26a5ba628f1f298a2031fae`。
- Linux native：SHA-256 `c25ff6e676290db6db47df0954640aa609c391450ec90e1a8eec1f87e174dd38`。
- 实际 `/proc/self/maps` 与 JAR CodeSource 已检查；ldd 无 missing 依赖，Easy Migrate 配置取本仓库。
- 挂载实测只在该次临时容器授予 SYS_ADMIN 以创建 bind mount；未修改宿主挂载或 Docker 全局设置。

需要核心 1+3+3 或容器实测时允许继续；性能及高资源非核心矩阵留给 Linux。
本轮六项根因可由上述核心执行路径覆盖，因此没有增加无关拓扑。

## 交付记录

- 配置与根隔离：`8054b6552e67b872e601d3d3a8cb6021bab4f1aa`。
- WAL 与 adapter：`4bf7612e2acaef4d230b9b581a2afd31591332fe`。
- CI 与诊断：`ceea35428a1f182cda294a93636da3dd9c2a3438`。
- Docker ABI fixture 修正：`741c64a5c3858d7d3489d209acec0935b0a8af58`，为完整代码交付 head；生产源码与此前三批保持一致。
- 三名只读审查者在最终受影响 diff 复审后均无阻塞发现；未替代实际测试。
- 新 fixture 的 executable bit 已核对并直接在 Linux 运行，避免 CI 裸调用权限失败。
- 四批代码已非强制推送，ls-remote 与 PR #179 head 均为 `741c64a5c3858d7d3489d209acec0935b0a8af58`。
- 68 个相对链接/锚点通过，四份历史档案与原基线逐字节一致；Linux 文档内 probe 原文提取实跑 adapter 1/0/0。
- 一名独立文档审查者核查迁移与最终交接；指出的旧状态和 CI 新旧 head 歧义已修正。
- 文档与关联 issue 进展随本轮发布；恢复时核对包含本记录的提交及 GitHub 实时状态，不继承其他版本或服务器验收结果。
Linux 待验项与可执行命令见 [linux.md](linux.md)，Mac 完成不关闭它们。

## 历史开发证据

文档拆分提交为 7c7519b79；四份历史档案与 4e1db45215 基线原文逐字节一致。
初始化阶段 59 个链接/锚点及一名只读审查通过，不是代码验证。
旧 master 同步、Raft 测试和通用指标 patch 见 [历史开发交接](development-handoff-history-20260926.md)，
它们没有被移入此次 TP 代码交付，也不作为本次功能通过证据。
