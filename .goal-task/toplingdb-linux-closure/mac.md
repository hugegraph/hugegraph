# Mac 开发与恢复入口

与 [state.md](state.md) 一起读取即可恢复本机工作。本机 Linux 容器的核心实测记在这里，
远端 Linux 服务器验收只在 [linux.md](linux.md) 维护。

## 基线与下一动作

本机执行基线 `7c7519b79c445c8e3c03a510f9c6aa7484fc0103`，工作目录
`/Users/zhu/.codex/worktrees/topling-local-fixes/hugegraph-server`，detached HEAD。
远端 hugegraph 为 https://github.com/hugegraph/hugegraph.git，推送目标 `HEAD:toplingdb`。
主 checkout 的无关文件及 Linux 历史未提交补丁未搬入本轮。
恢复时重新核对 HEAD、工作区和远端；本轮最终提交见下方交付记录。

2026-09-27：六项代码、核心实测、三人复审和四批代码远端核对已完成。文档核查已通过。
交付后下一动作是由 Linux 接收确定代码 head，执行待验场景；不自动合并 PR，不启动性能或完整部署矩阵。

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

证据目录为 `/tmp/topling-local-20260927`，原日志不提交；以下均退出 0，失败尝试保留以便追溯。
证据针对本轮实际工作树，交付后用确定源码提交复跑，不能继承为未来 SHA 已通过。

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
cluster-test 与 Server HBase job 在 hg-pd-test 编译因既有 IndexAPIClusterStateTest 缺 PDService/IndexAPI 失败；
该测试及依赖 POM 不在本轮 diff，保留 CI 门禁并独立跟进，不将本机通过写成全 CI 通过。
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
