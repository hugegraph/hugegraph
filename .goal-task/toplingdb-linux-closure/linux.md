# Linux 验收与恢复入口

## 2026-09-28 固定源码验收进度

本轮冻结源码为 `9d797c7608e244f03436ce11294d9bd72aba4d2d`。用户确认其 38 项 CI
全部成功；下方“新 head CI 待验”是先前提交时的记录，不是本轮 CI 结论，也不代表服务器验收通过。
原始证据目录：`/home/soc-baidu/.codex/validation-runtime/toplingdb-linux-closure/accept-9d797c7-20260928/evidence/`。

- 主机 `soc-baidu-System-Product-Name`，Linux x86_64，i9-13900KS、32 逻辑 CPU、123 GiB 内存，
  根盘约 1 TiB 可用；`/tmp` 为 tmpfs。Java 11.0.32.1、Maven 3.9.12、Docker 29.1.3。
- 接收时主 checkout `/home/soc-baidu/github/hugegraph` 为干净的 `toplingdb` 分支，HEAD 即冻结 SHA。
  `org` 为 `https://github.com/hugegraph/hugegraph.git`；`git fetch org toplingdb --prune` 后
  `FETCH_HEAD` 为同一 SHA，ahead/behind `0/0`。证据文档随后在原分支本地提交
  `bfeb756bed18bd25efabb183973d436b4bb02008`，因 GitHub 凭据失效尚未推送。
  历史 `f29e` 工作区在 `6790d53bf` 且含
  WAL/channel 等未提交内容，本轮未改动或夹带。
- TP JAR SHA-256 `86eb1bd3d9f84ef0dddd3fe95c640a6f145ca2d5a26a5ba628f1f298a2031fae`；
  解包 native SHA-256 `c25ff6e676290db6db47df0954640aa609c391450ec90e1a8eec1f87e174dd38`。
  native 为 ELF64 x86_64，`ldd` 无缺失库；运行时映射和本机指令兼容见下方真实 probe，
  通用 x86_64 CPU 下限仍按 #213 保留发布前门禁。
- 从零个 `target` 的干净源码运行 `mvn clean install -DskipTests -Dmaven.javadoc.skip=true -ntp`，
  exit 0，三组件标准 tar 已生成；原始日志 `01-clean-install.log`。此命令跳过测试，不能计作回归通过。
- 主机曾有 18 个历史 `hg-closure-*` namespace、77 个 Pod、69 个 PVC。用户授权清理历史
  k8s/kind 服务以释放资源；删除前清单和删除日志已保存，仅清理这些 namespace。最终 namespace、
  PVC、PV 均为 0；内存可用从约 12 GiB 回升到 117 GiB，根盘空余由约 1.0 TiB 增至约 1.1 TiB。
  kind 控制面及系统组件保留。原始记录为 `historical-*-before.txt`、`historical-*-after.txt`、
  `historical-namespace-delete.log`、`historical-namespace-wait.log`。
- Maven 测试均设置 Maven JVM `-Djava.io.tmpdir=.../tmp`，同时传给 Surefire `argLine` 和
  `-Djava.io.tmpdir`。标准 Core `mvn test -pl hugegraph-server/hugegraph-test -am -P core-test,rocksdb`
  exit 0，818/0 失败/0 错误/42 skip；标准 session/snapshot 定向命令 exit 0，27+16=43 项，
  0 失败/错误/skip；PD `IndexAPIClusterStateTest` exit 0，2/0/0/0。证据为 `02`、`03`、`04` 日志。
- `mvn compile dependency:build-classpath` exit 0，classpath 中唯一标准 rocksdbjni 被上述 TP JAR
  取代。`ToplingCoreProbe.java` 以 `/proc/self/maps` 核对 `LD_PRELOAD` 的本轮 native，打印
  `RocksDB.class` 来源。真实 TP snapshot helper exit 0，27/0/0；adapter 多 key truncate
  exit 0，1/0/0。测试覆盖清空、CF 保留、重新写读和二次 truncate；标准 provider 的对应测试在
  43 项中通过。原始日志 `05`、`06`、`07`，classpath 来源见 `tp-classpath-source.txt`。
- 三组件 TP 发行包生成 exit 0；配置选择、Server entrypoint 根、provider ownership、
  diagnostic 分类、PD/Store Docker entrypoint、runtime packaging fixture 均 exit 0；
  三组件标准/TP 发行包合同检查 exit 0；完整 Java security properties 回归 exit 0。
  证据为 `08` 至 `11` 日志。标准 JNI 的 Server、PD、Store runtime lifecycle 各 exit 0 且 phase complete，
  见 `12-standard-runtime-*.log`。
- 真实 TP native diagnostic 的 Server、PD、Store 前置 probe 都 exit 0、phase complete，
  合成 CF lifecycle 均出现 #212 的精确断言并 exit 134；分类 wrapper 各 exit 0、结果为
  `known-cf-assertion`，不是服务关闭通过。原始 `probe.log`、`lifecycle.log`、SHA 和报告位于
  `13-tp-diagnostic-{server,pd,store}/`。真实服务生命周期仍需独立验证。
- #213 发行链核查：本机 glibc 2.43，系统 libstdc++ 提供 `GLIBCXX_3.4.32`、
  `CXXABI_1.3.13`，CPU 含 AVX2、BMI1、BMI2；本轮真实 native probe 成功。
  解包 ELF64 x86_64 native 的最高 glibc 需求为 `GLIBC_2.38`（`118-*`）；这证明本机兼容，
  不等于可移植的 x86_64 CPU 最低基线已确定。
  [#213](https://github.com/hugegraph/hugegraph/issues/213) 仍为 open；上游
  [成功的 JNI workflow run](https://github.com/hugegraph/toplingdb/actions/runs/30160609061)
  使用提交 `31afa28f3d31606c1d5769a42a96ecd93420e8bc`、Ubuntu 24.04/GCC 13。
  本地 JAR 的 SHA-1 与对应时间戳的 [GitHub Packages 资产侧文件](https://github.com/hugegraph/toplingdb/packages/3151853)
  相同，但这是字节身份，不是签名证明。该 [workflow](https://github.com/hugegraph/toplingdb/blob/31afa28f3d31606c1d5769a42a96ecd93420e8bc/.github/workflows/topling-jni.yml)
  的 `make clean` 会自动取得其他 SidePlugin，HugeGraph 本地没有此 JAR 的完整源码/运行清单；
  workflow 也未固定 CPU `-march`，故源码闭包及通用 CPU 下限未收口。
  对同 SHA-256 native 追加 `readelf --version-info`/`-d`/`-n` 检查（`159-*`）后，
  实际 ELF 最高需求为 `GLIBC_2.38`、`GLIBCXX_3.4.32`、`CXXABI_1.3.13`，
  动态依赖清单已保留；note 只有 build-id/gold version，没有可作为 CPU 下限的 ISA 声明。
  这些是符号检查结果，不是已声明或在低配 CPU/发行版实测的支持基线。
  上游 [POM template](https://github.com/hugegraph/toplingdb/blob/31afa28f3d31606c1d5769a42a96ecd93420e8bc/java/pom.xml.template)
  列 Apache-2.0 与 GPLv2，包页标 GPLv2，而本地 [release LICENSE](../../install-dist/release-docs/LICENSE)
  将该包列为 Apache 2.0；JAR 内未发现 LICENSE/NOTICE。许可证选择需发布/法务审查，
  不将本轮含 SNAPSHOT JNI 的 `-topling` 发行包视为正式发行，也不自行发布。
  2026-09-28 再查公开 [#213](https://github.com/hugegraph/hugegraph/issues/213)：
  issue 仍为 Open，其 2026-09-26 状态明确正式发行链未完成。将本轮 JAR
  构建提交 `31afa28f...` 与 issue 引用的较新 producer 提交
  `e819a6dfecd54f0c1c3a0ddb33623af816131a79` 的
  （本次 `git ls-remote` 的 producer HEAD 也为该提交）
  [JNI workflow](https://github.com/hugegraph/toplingdb/blob/e819a6dfecd54f0c1c3a0ddb33623af816131a79/.github/workflows/topling-jni.yml)
  按字节比较，两份 SHA-256 同为
  `2ff2f379da8639d5931d6b8409cf3e61f463e8f13ea2f5b3e19c377c56e658f8`、
  `diff` exit 0；浅 checkout、动态 SidePlugin 获取、`deploy-file` 仍在，
  未增加本轮 JAR 的完整 provenance/attestation 或明示 CPU 下限。
  原始 workflow、哈希和比对见 `252-producer-release-recheck/`；较新 producer
  提交不改变旧 JAR 的来源证明或许可门禁。

镜像均从独立干净 checkout `/home/soc-baidu/.codex/worktrees/topling-linux-image/hugegraph`
构建，HEAD 为冻结 SHA。Bake 参数为 `SOURCE_REVISION=9d797c7...`、
`SOURCE_URL=https://github.com/hugegraph/hugegraph`、`IMAGE_TAG=accept-9d797c7-{std,tp}`；
首个标准 PD 使用 `--no-cache`，随后同 SHA 构建层复用；标准后续目标限定 `linux/amd64`，
TP 目标本身限定 `linux/amd64`。四个标准和四个 TP 目标 exit 均为 0，构建日志及 metadata 为
`14-image-*`、`15-image-*`。以下 digest 为本机 Docker RepoDigest，imageID 取
`docker image inspect --platform linux/amd64`，均核对 revision 标签为完整冻结 SHA：

| 镜像 | digest / amd64 imageID（sha256 前缀） | runtime |
| --- | --- | --- |
| `hugegraph/pd:accept-9d797c7-std` | `3e7ae66980e1` / `86c21a3588fd` | standard；digest 为多架构 manifest |
| `hugegraph/store:accept-9d797c7-std` | `79197a51369d` / 同 digest | standard |
| `hugegraph/hugegraph:accept-9d797c7-std` | `7733edc06dda` / 同 digest | standard |
| `hugegraph/server:accept-9d797c7-std` | `51953abb919f` / 同 digest | hstore |
| `hugegraph/pd:accept-9d797c7-tp` | `31fa84ca7629` / 同 digest | topling |
| `hugegraph/store:accept-9d797c7-tp` | `a0df11c186ec` / 同 digest | topling |
| `hugegraph/hugegraph:accept-9d797c7-tp` | `29874890b975` / 同 digest | topling |
| `hugegraph/server:accept-9d797c7-tp` | `51953abb919f` / 同 digest | hstore，和标准标签同 imageID |

三组件 TP 镜像中 JAR/native 文件的 SHA-256 与本轮输入一致，容器内 `ldd` 无缺失；
标准三组件与 HStore Server 镜像无 TP JAR/native。原始输出 `16-image-files-*.log` 与
`16-standard-and-hstore-isolation.log`。文件存在不代替运行时映射。

standalone TP 容器 `a9073ec4...` 实际 imageID `29874890...`，只将本轮专属数据根挂载到
`/hugegraph-server/topling-data`；`/versions` HTTP 200、core 1.7.0。Java classpath 首项为
上述 TP JAR，`/proc/1733/maps` 确认装入 `/hugegraph-server/library/librocksdbjni-linux64.so`，
文件 SHA 与本轮一致，数据、WAL 和 init marker 均在 TP 根下。原始证据 `17-*`。
`run-server-e2e-smoke-test.sh create tp9d797c7a` exit 0，创建并查询两顶点、一条边、Gremlin
计数 2；首次停机后用同一数据根重新建容器 `442b1f53...`，`verify` exit 0，数据均可见，
见 `18-*`、`20-*`。

#212 实际失败：首次 TP SIGTERM 停机日志记录 HugeGraph 图关闭后出现
`SidePluginRepo... db not closed` 断言；第二次复现同断言，`docker wait` 得到进程 exit 137
（45 秒 stop timeout），见 `19-*`、`21-*`。同 SHA 标准容器 CRUD create exit 0，SIGTERM
停机进程 exit 0、无该 native 断言，见 `22-*`、`23-*`。重启后读取成功不覆盖 TP 停机失败。

#212 最小只读对照（同 SHA、同独立调试配置）：各启动一台全新 standalone，只发一次
`GET .../graph/vertices?limit=1`，返回 200/空列表后 SIGTERM。TP exit 137 且仍报
`db not closed`；标准 exit 0。两者最终 g/m/s 的 `BackendSessionPool.close()` 日志都显示
sessionCount 为 1、当前线程引用归零。完整 CRUD 对照中两者最终各为 9，TP exit 137，
标准 exit 0，见 `67-*` 至 `76-*`。源码 `BackendSessionPool.close()` 只在全局计数为 0
时调用 `doClose()`，随后才由 `RocksDBStdSessions` 关闭 DB；因此本轮确认共用 Java
会话未收尽，TP native 析构对此给出可见断言。不能据此排除 producer 的独立
CF bookkeeping 问题，也不能在 worker 未停时强行 close DB 制造通过。
对 `73-*`、`75-*` 逐线程配对 `connect` 与引用归零的 `close` 后，`156-*` 显示
标准和 TP 各有 15 次新建、12 次归零关闭；唯一未配对的是发出顶点 GET 的
`grizzly-http-server-*` 线程在 g/m/s 的三个 session。日志支持 HTTP 请求线程会话
未收尽这一具体线索，但无法仅凭日志证明其负责全部 native CF 引用；下一步应在
独立复现中核对请求结束事务清理与线程退出顺序，再做最小修复和三人复审。
只读源码追踪 `157-*` 进一步显示：`VertexAPI.list()` 仅调用 `g.tx().close()`，
TinkerPop `doClose()` 重置自身状态；关闭 schema/system/graph 后端事务并删除
thread-local 的 `destroyTransaction()` 在 `StandardHugeGraph.closeTx()` 中，
而服务图关闭是在另一条停机线程执行。这与 HTTP 线程残留相符；尚未实测修复，
也不能直接把每次 `g.tx().close()` 改成强制清库而不验证 REST/Gremlin 行为。
同样的逐线程解析用于完整 CRUD 日志 `68-*`/`70-*`，结果 `158-*` 为标准与 TP
各 42 次新建、15 次归零关闭，未配对的 27 个 session 分布在九个 HTTP 线程的
g/m/s，与最终每库 sessionCount 9 一致；非 HTTP 线程无未配对项。
隔离分支 `codex/toplingdb-lifecycle-212` 曾试作“每个 REST 响应后清理当前线程事务”
最小候选；三名独立只读审查者发现其清理异常可覆盖已提交写入的成功响应，且最后一个
session 归零与 native `doClose()` 之间允许另一请求取得新 session，造成活跃请求持有
已关闭 DB。响应过滤器还早于实体序列化；现有停机回调只在自身线程清理，不能收尽其他
HTTP 工作线程的 thread-local。候选源码和审查结论留 `160-*`，已从 worktree 移除、
没有构建/测试/提交。安全修复需要独立 DB 所有权、停止新 session 的关停阶段和
活跃操作排空屏障；#212 仍为真实服务失败，不用强制关闭规避。

#212 会话池并发前置修复另在隔离分支 `codex/toplingdb-lifecycle-212` 完成，
其父提交为 `8b09df2b71ea39efe7929c567041839cddbfad93`（冻结 SHA 的后代），
候选提交 `c2e8664f18796b2eaef7f4c0719091ae7396b73b`。该改动只让新 session
登记与最后一个 session 的 native `doClose()` 共用池锁；已有 thread-local 快路径不变。
定向并发测试在原实现下稳定检出“后端已关闭但另一线程仍持有活跃 session”
（`227-*`，1 test/1 failure，预期红灯），在候选实现下 1/0/0/0、exit 0
（`226-*`）。三名独立只读审查者对最终测试握手及代码复审均未发现新的 P0/P1。
标准 RocksDB 定向回归 `228-*` 为 38/0 failure/0 error/1 skip、exit 0；
`mvn editorconfig:format`（`229-*`）和全仓 `mvn clean compile`
（`230-*`）均 exit 0。真实 TP JNI 的整类 `RocksDBSessionsTest` 在合成 CF
清理时触发精确已知的 `cfh must in cfh_to_view` 断言并 exit 134，
JUnit 未返回计数（`231-pool-tp/rocksdb-sessions.log`）；同一临时数据目录
随后单测因残留 CF 1 test/2 failures，保留该失败；换专属空目录后
多 key adapter truncate 1/0/0、进程 exit 0，JAR CodeSource 与 native maps
均匹配本轮 SHA-256（`231-pool-tp/adapter-fresh.log`）。这只验证池竞态修复及
定向 JNI 兼容性；REST worker 会话残余、服务停机 exit 137、关停接纳/排空
仍未解决。该候选在上述测试时尚未并入原 `toplingdb`；后续整合实测见下段。

#212 REST 请求完成清理另在隔离分支提交 `1786c8422f75d20de09b7544c22fe66972fb6deb`：
Jersey `FINISHED` 后执行现有当前线程事务清理，异常记日志而不覆盖已发送响应。
三名独立只读审查者对该最终 diff 未发现 P0/P1；本仓库当前 REST 路径为同步处理，
未发现异步/流式接口，后续若引入异步须重新验证回调线程。`232-*` 定向编译及
`233-*` 格式、全仓 clean compile 均 exit 0。隔离 TP 镜像 `234-*` 从该提交无缓存
构建，实际 imageID/digest `sha256:22b1adb2c26c6f87c8563591c6ca5024598839fa611858b3efdc71a4b9f44e80`，
JAR/native 哈希匹配。`235-*` 真实 TP CRUD、预期 404、首次重启 verify、两次
SIGTERM 均通过，容器 exit 0；运行时探针证实 TP JAR CodeSource/native maps，
服务日志中 tx refs 降为 0，无 `db not closed`。同源码标准镜像 `236-*` 使用
该次精确源码构建层，imageID/digest
`sha256:fc077cad510df01a4828df1a5f5e69fff3c712de828fec2899a882f13e773c4b`；
标准 JAR SHA-256 `a59c02c628dd3bec82de027c7e6edb5b11c1c09c251a85325c7f5ff081948133`，
未混入 TP JAR/native。首次标准对照 `237-*` 把 provider 误写为 `java`，
entrypoint 在开库前 exit 1；该次日志保留，测试等待进程定向终止 exit 143。
修正为 `rocksdb` 并使用新数据根后，`238-*` 标准 CRUD/404/首次重启及两次
SIGTERM 都通过、exit 0。专属标准服务容器的 `VertexApiTest` 定向回归
`239-*` 为 4/0/0/0、Maven exit 0，容器停机 exit 0。

上述两项代码分别 cherry-pick 到原 `toplingdb` 为 `6d5893fd624d54ae1991c822543d96e151ace39b`、
`e17f1b6d8a7afa9fd46f2ccf9ee3c31f208889bd`。fetch 后 `org/toplingdb` 仍为
冻结 SHA；整合前 ahead/behind 为 21/0，整合后为 23/0，均未推送。
精确整合 HEAD `e17f1b6d8` 从干净工作树无缓存构建 TP Server 镜像 `241-*`，
实际 imageID/digest 为
`sha256:7379d24f6231eba72762593ee5d47964014e31f3b0d4ef5ec2e68ed24402ac42`；
revision/source 标签分别为完整 HEAD 和 `https://github.com/hugegraph/hugegraph`，
JAR/native 文件 SHA-256 仍与本轮输入一致。独立新数据根的 `242-*` 服务
CRUD create、预期 404、20 个并发顶点 GET（20/20 HTTP 200）、首次重启
verify 均通过，脚本 exit 0；首次、第二次 SIGTERM 进程都 exit 0。
两次 JVM 日志均记录 TP JAR CodeSource 和 native `/proc/self/maps`，并在停机前
显示 tx refs 从 1 降至 0；无 `db not closed` 或清理异常。任务容器已移除，
数据与原始日志保留；余下运行容器仅 kind 控制面和本任务 BuildKit，内存可用
约 116 GiB，根盘空余约 1.2 TB。该结果解决本轮 REST CRUD/并发读场景下的
可复现关闭失败；#212 更广的异步/关停接纳排空、所有 native DB/CF 生命周期
及 #255 合成 CF 断言仍需独立核查，不自动关闭 issue，也不把该结果外推为 HA。

追加当前干净本地源码 `91bf84f30...` 的标准 provider REST API 回归（`253-*`）：
专属容器在精确整合镜像中选择标准 JAR，测试专用 agent 看到服务 JVM 的
`RocksDB.class` CodeSource 为 `/hugegraph-server/lib/rocksdbjni-8.10.2.jar`，
native `/proc/self/maps` 为 `/tmp/librocksdbjni...so`，无 TP library 映射。
Maven 与 Surefire JVM 均使用专属临时目录，`mvn clean test -P api-test,rocksdb`
定向 Schema 1、Edge 5、Vertex 4，合计 10/0 failure/0 error/0 skip、exit 0；
服务停机 exit 0，无 REST 清理异常或 native 关闭断言。该定向回归覆盖多种同步
REST 读写路径，不替代完整 API suite、异步请求或 #212 全部 CF 生命周期。
当前本地 HEAD `862b78bdf640687a58228e9271443eb4ac6f60f2` 又从干净源码
执行 `mvn clean test -pl hugegraph-server/hugegraph-test -am -P core-test,rocksdb`
（`258-*`），Maven/Surefire 的 `java.io.tmpdir` 与 `config_path` 显式指向专属
目录，RocksDB data/WAL/index 也在任务数据根。`CoreTestSuite` 实际
819/0 failure/0 error/42 skip、Maven exit 0，耗时 7:25。
suite 另创建两个相对 `rocksdb-index*` 目录，已原样移入专属 data 目录并记
`258-standard-core/relative-index-relocation.txt`，原工作树恢复干净。
此为整合后**标准 JNI** Core 全量回归，不替代真实 TP JNI 或 #249 在线安全。

#212 同一整合 TP Server 镜像另做真实流量中 SIGTERM（`259-*`），独立新数据根
CRUD create exit 0、JVM TP JAR/native 映射匹配。16 个客户端 worker 持续 GET
顶点列表，`traffic-results.txt` 记录 3977 次请求，3977 次 HTTP 200、0 错误；
写入停机请求时间后下一 UTC 秒仍有 3173 次请求开始，服务日志在停机请求当秒
已出现 `HugeGraphServer stopping`。首次 SIGTERM 进程 exit 0，日志中 tx refs
降至 0、无 `db not closed` 或 REST 清理异常；相同数据根重启完整 verify exit 0，
最终 SIGTERM 仍 exit 0，脚本 exit 0。该时间重叠证明本次有请求流量覆盖
shutdown 窗口，**不证明所有长响应、异步请求或任意接纳/排空竞态均安全**。

#212 长 Gremlin 在途请求停机另用相同整合 TP 镜像和专属新根核查。
首次 `260-*` 短查询实际 HTTP 200、解压后计数 2，但脚本未解 gzip 就交给
`jq`，解析 exit 5；长请求/SIGTERM 尚未开始，该容器停机 0，原始响应保留。
`261-*` 修正 `curl --compressed` 后短查询 HTTP 200、计数 2；8 秒
`Thread.sleep` Gremlin 请求在 1 秒后仍处于进程内，记录在途与停机时间后
对容器 SIGTERM。长请求的客户端最终收到 HTTP **500**，响应为 REST 代理
连接 Gremlin `127.0.0.1:8182` 的 `NoHttpResponseException`，curl 自身 exit 0；
不能将该在途业务请求计作成功。服务停机日志约 7 秒后完成，第一次进程 exit 0、
tx refs 降至 0、无 native `db not closed`；同根重启完整 verify exit 0，
最终 SIGTERM exit 0，脚本 exit 0。该样本证明 native 关闭与持久数据可重开，
也保留了**长 Gremlin 在途请求停机时失败**的边界；不宣称请求排空或业务连续性通过。
源码 `HugeGraphServer.stop()` 先等待 Gremlin stop，再等待 REST stop，
与本次 REST 代理访问已关闭的 `8182` 所得异常吻合；这是基于源码与日志的
停机顺序归因，未做独立修复实验。若调整顺序，必须先解决 REST 销毁事件会
关闭共享图的时机，不能直接交换两行 stop 调用。按通用启动/停机问题在 todo.md
另列跟进，保留 TP native 关闭通过和在途业务失败两个分开的结论。

#249 在同一精确整合 TP 镜像 `sha256:7379d24f...02ac42` 上增加数据树故障实验。
新专属根 `data/datatree-fault-tp-243` 的基线 CRUD 和 `snapshot_create` HTTP 200；
为让宿主注入测试链接预设 ACL，但容器创建的 checkpoint 子目录将有效权限收紧为
只读，首次 `ln` 报 Permission denied，`243-*` 脚本 exit 1，尚未调用 resume。
原 checkpoint 文件 SHA-256 清单先保存并复查全部匹配。只使用任务专属 bind 根的
一次性辅助容器加入 m/g/s 三条指向不存在目标的 `copy_fault.link`，有效文件未变。
`244-*` 新服务在故障前完整 verify 通过；首次 `snapshot_resume` HTTP 400，
响应指出 `s` 库 pending 恢复失败，`data/s.resume-pending` 与三库 checkpoint
和测试链接均保留，原文件哈希仍匹配，TP JAR CodeSource/native JVM maps 与本轮
身份一致，停机 exit 0。脚本要求在响应/容器日志中直接找到链接名，因只返回包装
错误而 exit 1；内层复制还是 `verifyTree` 拒绝未取到栈，不能精确归到其中之一。
故障未修复时的 `245-*` 重启停在 init-store，10 秒窗口内 `/versions` 与图请求
连接重置，脚本 exit 0 但人为停止的进程 exit 143；marker 内容、链接和源文件
哈希仍不变，不把这次 10 秒取样当作完整 fail-closed 门禁。
随后 `246-*` 只移除三条人工链接，移除前后原 checkpoint 文件 SHA-256 匹配，
`s.resume-pending` 字节未变；不删除 marker/锁。相同数据根启动后基线两顶点一边
完整 verify exit 0，`s` pending 自行消失，容器 SIGTERM exit 0；
`snapshot_data/s` 被消费，`g/m` checkpoint 仍在。故本实验只证明先遇到故障的
`s` 库在数据树复制/校验失败后可重试，**没有证明全图恢复、WAL 发布故障或
在线恢复安全**；这些仍是 #249 的阻塞项。所有原始日志和数据保留 `243-*` 至
`246-*`，测试容器已清理。

#249 标准 provider 对照使用同一精确整合源码/双 runtime 镜像，专属新数据根
`data/datatree-fault-standard-251`。测试专用 identity agent 的首次 `jar` 打包
因主机无该命令而失败，已保留 `250-*` 记录；以 ZIP 格式组装 JAR 后 SHA-256
`333eb0752ec22bef88711e395fc4d7dc404e3d88bee35c80dca9153673c17384`。
服务 JVM 实际 `RocksDB.class` CodeSource 为标准
`/hugegraph-server/lib/rocksdbjni-8.10.2.jar`（JAR SHA-256
`a59c02c628dd3bec82de027c7e6edb5b11c1c09c251a85325c7f5ff081948133`），
`/proc/self/maps` 的 native 是 `/tmp/librocksdbjni...so`，不含
`/hugegraph-server/library/` TP 映射；不以 provider 环境变量单独认定标准 JNI。
`251-*` 的基线 CRUD、`snapshot_create` HTTP 200，m/g/s 加入三条相同无效
测试链接后 `snapshot_resume` HTTP 400；`s.resume-pending`、三库 checkpoint
和原文件 SHA-256 保留。只移除人工链接，marker 字节及原文件哈希不变；
同根重启后完整 verify exit 0，`s` pending 自行清除，`g/m` checkpoint
仍在，首次及最终 SIGTERM 都 exit 0，脚本 exit 0。标准和 TP 两种 JNI
都证明首个 `s` 数据树复制/校验故障可重试，**不证明全图原子恢复**；
内层异常未直接取到，不能精确宣称触发了 `copyDirectory` 还是 `verifyTree`。

#249 进程锁另在精确整合 TP Server 镜像和任务专属根 `data/tp-process-lock-254`
实测。第一容器基线 CRUD 与真实 TP JAR/native JVM 映射通过；第二容器绑定**同一**
data/WAL 根时在 `RocksDBStdSessions.lockForOpen()` 报
`RecoveryLockException: database open/recovery lock held`（`m` 库）。第二容器
没有自行退出，`/versions` HTTP 200、健康状态可为 healthy，但图数据 GET
HTTP 404；原脚本错误等待它自行退出，90 秒后 timeout 124、脚本 exit 1，
原始 `254-*` 保留。同期第一容器完整 verify exit 0、关键 CURRENT/MANIFEST
SHA-256 检查 exit 0、三个 `.resume-lock` 文件清单不变，两个容器随后各停机 exit 0。
未删除任何锁文件；`255-*` 用同根新 TP 容器正常打开、完整 verify exit 0，
运行时 TP JAR/native 映射匹配，停机 exit 0。
标准 provider 使用同一整合镜像但服务 JVM 实际映射标准 JAR 和 `/tmp` native
做 `256-*` 对照：第二进程也在 `m` 库 `lockForOpen()` 遭同类拒绝，
`/versions` HTTP 200、图 GET 404；第一进程的数据哈希/锁清单不变，
故障中及停机重开后完整 verify 都通过，首次/最终停机 exit 0，脚本 exit 0。
两种 provider 的跨进程同时打开**锁保护通过**；健康检查未反映 backend 打开失败
属于通用启动链独立问题，不能以 `/versions` 或容器健康证明第二库已初始化。
本测试没有让两个进程同时恢复同一 pending，#249 恢复并发门禁仍未通过。
为选择可控的恢复持锁窗口，`257-*` 用仓库实际 Commons IO 2.7 在专属目录
试验含 FIFO 的 `FileUtils.copyDirectory`，5 秒 timeout exit 124，证明**本地
文件复制探针**会等待 FIFO。随后用于核对辅助容器 Java/mkfifo 环境的任务专属
只读 `docker run --rm` 命令在执行前被自动审批拒绝：“approval required by
policy, but AskForApproval is set to Never”。未创建容器或更改 checkpoint、
marker、锁；不换 Docker 命令规避。该探针不能替代真实服务并发恢复，门禁保持未覆盖。

#248 冻结 SHA 的第三次**有效**时序实验使用固定 imageID：PD
`sha256:31fa84ca...cbb596`、Store `sha256:a0df11c1...afc17e`、
HStore Server `sha256:51953abb...d17722e`，均为 `9d797c7...` 标签/修订；
本次为单宿主 1 PD + 1 Store + 2 Server，不能宣称物理多机 HA。
`247-*` 新根的 PD/Store 正常、Server 宿主 `/versions` 200，但镜像内置健康检查
探测 `localhost:8080`，与绑定 `server:8080` 不符而超时；该次未清库或写入，
脚本 exit 1，容器/网络已清理。`248-*` 的首个预检把 Store imageID 中的
`247` 误改成 `248`，在创建资源前 exit 1；保留该原始记录并校正。
同目录第二次运行已写入旧数据，但 clear 请求漏带必需的确认消息，HTTP 400，
未发生清库，脚本 exit 1，容器/网络清理，数据根保留。
`249-*` 复用此未清库数据根，先完整 verify 原数据，再带编码确认消息执行
`DELETE .../clear` HTTP 204；新 RUN_ID 的两顶点一边 create exit 0。
重启前主 Server 指定顶点 GET HTTP 200、ID/属性匹配；第二 Server 使用同一
PD/Store 只读 GET HTTP 200、同一 ID/属性，停机 exit 0。只重启主 Server，
停机 exit 0；重启后首个**外部图数据请求**即指定顶点 GET HTTP 200，
ID/属性仍匹配，后续完整 verify exit 0，脚本 exit 0。原始时间戳、响应、
组件镜像身份、启动/停机日志与数据留 `247-*` 至 `249-*`；healthcheck 只查
`/versions`，不能替代首次图数据 GET。本批次未重新读取 PD/Store 服务 PID 的
native maps，沿用同 imageID 在 `216-*` 的先前映射证据，不能称新进程映射再次取证。
这是第三个有效场景未复现旧 #248 线索，**不能据此关闭 issue**，也不覆盖历史
第一次失败。容器和网络均已清理，仅 kind 控制面/BuildKit 仍运行。
三个准备批次的 HStore Server 镜像声明 `/hugegraph-server` 卷，清理后有六个
任务匿名卷各约 419.6–419.7 MB、合计约 2.5 GB；定向 `docker volume rm`
此前被自动审批拒绝，本轮不换命令规避，卷名与尺寸留原始 `249-*` 资源记录。
具体见 `249-task-anonymous-volumes.txt` 和 `249-resource-after-*.txt`。

#248 增补冻结 SHA 的**标准 PD/Store 对照**，仍使用 HStore Server，独立新根
`data/clear-first-restart-std-265/`；按原场景执行
`bash evidence/265-std-clear-first-restart/run.sh`，exit 0。PD imageID
`sha256:3e7ae669...8db282`、Store `sha256:79197a51...8031d` 均为
`9d797c7...` 的标准包；两个 HStore Server 使用同一固定 imageID
`sha256:51953abb...d17722e`。PD/Store 配置 provider=`rocksdb`，
实际标准挂载根出现 PD metadata/raft 及 Store metadata、分区 DB 的
`CURRENT`/`IDENTITY`，未用的 TP 根没有文件；本次未重新采集 JVM JAR/native
maps，故不把标签和变量单独当作运行时映射证明。
新根先 create 两顶点一边，`DELETE .../clear` HTTP 204，再 create 新 ID
`riscv-smoke-v1-clearstd265`；主 Server 重启前和第二 Server 的按 ID GET
均 HTTP 200，第二 Server 读到属性 `first`，自身停机 exit 0。仅重启主 Server，
首次外部图数据 GET curl exit 0、HTTP 200、ID/属性匹配，随后完整 smoke verify
exit 0（两顶点、一边、Gremlin count=2），主 Server 第一次停机 exit 0。
脚本清理四个容器和专属网络，原始请求/日志/镜像与数据证据留 `265-*`。
这是第四个有效时序样本、首个本轮标准 PD/Store 对照，仍未复现旧线索；
不能否定 `a35ebeb17` 的首次失败或关闭 issue，也不是物理多机测试。
镜像声明根 `VOLUME` 另产生四个匿名卷，共约 1.46 GB、零引用；ID/大小留
`265-*/task-anonymous-volume{,-sizes}.txt`。此前定向卷删除已被自动审批拒绝，
本轮不重试或绕过；当前运行容器为零，kind/BuildKit 继续停止。

再对照 [#248 原始记录](https://github.com/hugegraph/hugegraph/issues/248) 与历史
`f29e` 工作区的 `a35-top-111-{drop-clear,server-sigterm}.json`：旧失败使用
`person`/`name` 主键顶点，HTTP 200 的 `vertices` 列表查询在首次 Server
重启后变空；原 ID 形如 `2:a35cleared111`。上方三次固定 SHA 复现主要用
自定义字符串 ID 的按 ID GET，不能覆盖该属性查询路径。旧 Store 数据主要落在
`/hugegraph-store/storage`，TP 根仅约 4 KiB；旧场景实际 provider 身份也不能
只凭镜像或 native 文件 SHA 推定。
为补查询路径，在**先后执行、没有重叠服务**的两批独立根按旧顺序做 HStore
drop→重建图→建立 `name`/`person` 主键 schema→写入→属性过滤读→clear→
写入新顶点→第二 Server 属性读→仅重启主 Server→首次属性读：

- 标准 PD/Store `267-std-legacy-clear/run.sh`：drop/clear 分别 HTTP 204，
  重建图 201，schema 202/201，第二 Server 属性读 200 且目标命中，
  主 Server 重启后**首次属性请求** HTTP 200、目标命中；两个 Server 首次停机
  exit 0。后续额外按 ID GET 因脚本未给主键字符串 ID 加 API 所需引号返回
  HTTP 400，故整个脚本 exit 1；`first-property-after-restart.*` 与该 400
  分别保留，不能把脚本整体记为通过。
- TP PD/Store `268-tp-legacy-clear/run.sh`：同样的 drop/clear、schema、
  第二 Server 和主 Server 首次属性读均通过；修正额外 ID GET 编码后
  按 ID 读 200，脚本 exit 0，两个 Server 首次停机 exit 0。

两批 PD/Store/Server imageID 均逐组件保留在 `image-identities.txt`，源修订
为冻结 SHA；标准与 TP 挂载根各有 PD metadata/raft 和 Store metadata/
分区 DB 的 `CURRENT`，无并行旧服务。当前新进程未单独采集 JAR/native maps，
TP 相同 imageID 的服务映射另见 `216-*`；本测试仅按实际根及已有产物身份限定结论。
新 schema 的 `person` ID 是 `1`，旧失败为 `2`，且旧 Store 根选择不同；
这两轮仍**未完全重建**旧状态，不能否定首次失败或关闭 #248。
两批容器/网络已清理、数据和原始日志保留；镜像自动创建标准四个、TP 两个
零引用匿名卷，合计约 2.30 GB，ID/大小见各自 `task-anonymous-volume*.txt`。
此前定向删卷被自动审批拒绝，不绕过；当前无运行容器。

#248 又补一轮更接近旧失败时序的固定 SHA TP 单宿主 HStore 对照。
旧 `a35ebeb17` 顺序是 clear 后写入、**PD 先重启**、再只重启 Server；
上一轮 `268-*` 没有 PD 重启。新独立根 `data/clear-oldroot-pd-270/`
将 TP PD/Store 的空 bind 目录分别挂到旧版容器内
`/hugegraph-pd/pd_data`、`/hugegraph-store/storage`，provider 仍明确选
`topling`，另一组 TP 默认根为空。命令
`bash evidence/270-tp-oldroot-pd-restart/run.sh` exit 0；Server、PD、Store
仍用固定 SHA imageID，详情 `image-identities.txt`。仅此一批服务运行，结束后
四个容器和网络已清理。
脚本按 drop→重建图→`person`/`name` 主键 schema→写入→属性查询→clear→
重新写入执行。PD SIGTERM 后 exit 143，重启后受认证的 `/v1/cluster`
HTTP 200、`Cluster_OK`、`PState_Normal`、1 PD/1 Store/12 分区；新顶点
属性查询仍命中。Store 在 PD 中断期间记录瞬时连接失败/PD unreachable，
恢复后第二 Server 属性查询 HTTP 200、目标命中。随后仅重启主 Server，
首次属性查询 HTTP 200、目标命中，按 ID GET 亦 HTTP 200；两台 Server
首次正常停机 exit 0。失败和成功时序的原始日志、状态、请求正文分别保留，
未用后续成功掩盖 PD 中断期间的 Store 错误。
测试专用 Java agent 源码/JAR/hash 与自检留 `269-component-identity-agent/`。
它从 **PD 首次启动及重启后的进程、Store 实际 JVM** 的已加载 `RocksDB` CodeSource
和 `/proc/self/maps` 输出 TP JAR `86eb1bd3…2031fae`、native
`c25ff6e6…174dd38` 的全 SHA-256；不是只凭镜像标签、环境变量或磁盘上
存在 `.so` 判定。旧容器内路径 `/storage` 在此轮确实能加载 TP，因此旧路径
本身**不足以证明标准 provider fallback**；旧集群的实际配置/运行时映射
仍需其历史原始环境证据，不能由新实验反推。
本轮 `person` schema ID 为 `1`，旧失败为 `2`；源码、Docker/kind 部署也
不同，#248 仍未重现且不关闭。新镜像自动产生两个零引用 Server 匿名卷
各约 420 MB，ID/大小留 `270-*/task-anonymous-volume*.txt`；此前删卷
被自动审批拒绝，不重试或绕过。当前无运行容器。

#250/#251/#253 反向数据根冲突：标准镜像指向已停机 TP 根、TP 镜像指向已停机标准根，
两次 Docker 启动均 exit 1，报对应 `provider marker mismatch`，未进入 Java 数据库打开。
只读容器在失败前后比较全目录文件相对路径、逻辑大小、已分配块数、mtime，及所有逻辑大小
小于 50 MiB 文件的 SHA-256，均一致；稀疏 memtable 的空洞内容未逐字节哈希。
原始证据 `24-*`、`25-*`。首次试图在宿主机全文件哈希因 marker 权限和稀疏文件
1.36 TiB 逻辑长度中止，不能引用那份不完整清单作为通过证据。

真实单 DB bind mount 边界：由仓库 helper 先认领空父根及 data 根，再将独立目录挂到
`.../topling-data/data/g`。实际 TP `init-store` exit 1，`RocksDBSnapshotRestore.lock` 在
g 的 native open 前报“Database directory cannot itself be a mount point”；挂载的 g
目录保持空（`77-*`、`78-*`）。但同次并行初始化的 m/s 已生成 `CURRENT` 和恢复锁，
因此只能证明目标 DB fail-closed，不能声称整个图在拒绝时没有任何旁路写入；若要求
全图原子预检，需要在并行开库前集中检查所有 DB 挂载点。

完整启动套件只在专属 Docker 测试容器执行，先前宿主机安全配置脚本已通过。标准镜像内
同一 backend 连续两轮 `test-start-hugegraph.sh`，各 16 passed/0 failed、容器 exit 0，
覆盖实际 init-store、daemon、前台 HTTP、monitor cron、SIGKILL 137 与 SIGTERM 143 传播，
见 `26-startup-standard-container-retry.log`。TP 镜像内按实际默认根预建 `data`/`wal`
后两轮各 16/0、容器 exit 0，见 `27-startup-topling-container-retry.log`。
首次标准测试容器因仅有 JRE、内置安全脚本需要 `jdk.compiler`，在启动套件前 exit 1；
TP 首次因空挂载根缺预建 `data` 在 init-store 前 exit 1。两项前置失败原日志分别为
`26-startup-standard-container.log`、`27-startup-topling-container.log`，未计入测试通过。
启动套件的 16/0 不替代真实 Docker entrypoint 的 #212 停机失败。

自定义 `graphs=./conf/custom-graphs` 实际服务：先前直接在未认领 TP 根下预建子目录，
启动 exit 1，报 `refusing unmarked non-empty data path`（`28-*`）；从新空根通过
`verify-rocksdb-provider.sh server topling ... true` 正式认领后再建 data/WAL 子目录，
以 `HG_SERVER_ENFORCE_PROVIDER_MARKER=true` 启动。首次复制配置仍只绑定容器内
`127.0.0.1:8080`，外部映射端口连接重置，日志明确绑定地址（`29-*`）；显式设置
`HG_SERVER_REST_URL=http://0.0.0.0:8080` 后，HTTP `/graphs` 返回 `hugegraph`、`extra`。
两图各自 e2e create exit 0，分别建两顶点一边并查询、Gremlin 计数 2；文件与日志显示
`data/{g,m,s}`、`wal/{g,m,s}` 和 `extra/data/{g,m,s}`、`extra/wal/{g,m,s}` 全在
本轮专属父挂载根下（`30-*`）。SIGTERM 后两个图各有 Java close 日志，但再次出现
TP native `db not closed`，进程 exit 137（`31-*`）。同容器重启后动态宿主机端口从
32774 变为 32775；旧端口等待脚本被停止，正确端口的两图 verify 均 exit 0（`32-*`）。
额外 graph 的配置识别、实际 CF 文件、CRUD 和重启可见性已验证；预认领 marker 本身不计作初始化。

隔离 Docker 1+1+1 TP 实服务使用专属网络、PD/Store 各自父数据根和本轮镜像。
PD `56087d61...` 对应 imageID `31fa84ca...`，Store `8346efbe...` 对应 `a0df11c1...`，
HStore Server `666816e3...` 对应无本地 TP runtime 的 `51953abb...`；
PD/Store `/v1/health` 可达，Server `/versions` 报 core 1.7.0。Store CRUD 后出现分区
`db/00000` 等及 Raft `CURRENT`，并非只凭 Ready 判定。`run-server-e2e-smoke-test.sh create`
exit 0，两顶点一边和 Gremlin 计数均通过（`34-*` 至 `38-*`）。
Server 单独停机 exit 0，重启后 verify exit 0；Store 停机 exit 143、重启后 verify exit 0；
PD 停机 exit 143、重启后 verify exit 0。Store 启动日志含短暂 `getRaftAddress` 地址解析错误，
PD 停机日志含 gRPC `CANCELLED`，保留原日志，不据后续成功删除首次告警（`39-*`、`42-*`、`43-*`）。

#248 单次复现：在此 1+1+1 上 `DELETE .../clear` HTTP 204，随后新建数据并在重启前
固定 GET HTTP 200/ID 匹配（`40-*`）。只重启 Server，保持 PD/Store 不变；首次重启后的
首个顶点 GET 就是 HTTP 200 且值正确，完整两顶点一边 verify 也 exit 0（`41-*`）。
本拓扑本时序未复现旧 #248 线索，不能据此关闭 issue。1+1+1 容器及网络已清理，数据根和
原始日志仍在专属 evidence 目录（`44-*`）。

PD/Store 运行命令由 `docker top` 确认外部 TP JAR 在 Boot JAR 前，容器镜像文件哈希及
独立 runtime probe 均匹配；但对服务 PID 的 `/proc/maps`，宿主机权限拒绝，
`docker exec`、提权与共享 PID 容器命令被自动审批拦截。因此 PD/Store **服务进程** native
映射在该批测试时尚未独立证实；后续 `216-*` 已在长驻 JVM 内补证。
不把 classpath、环境变量、镜像标签当作映射证据。

单机 Docker 1+3+3 扩展验收：三个 PD、三个 Store 与一个 HStore Server 使用同一冻结 SHA，
PD/Store 各有独立父数据根，七个容器实际 imageID 见 `48-333-component-identities.txt`；
三 PD/三 Store 健康，pd0 初为 Raft leader，三 Store 在 CRUD 后各有 12 个分区 DB `CURRENT`。
Server `/versions` 与完整 e2e create 均 exit 0（`45-*` 至 `48-*`）。pd1/pd2 起始切主阶段的
`PDException Error code = 100` 保留在初始日志，后续成功不擦除该异常。

单 Store 故障：停止 store0 后首次和约 25 秒后第二次完整 verify 都 exit 1，边扫描
HTTP 500、gRPC `UNAVAILABLE`；恢复 store0 后首次 verify exit 0（`49-*`、`50-*`）。
因此本轮未证明该单 Store 故障窗口内的完整查询连续性。根因尚待与通用 Store 路由/扫描
问题（todo.md 中 #245 等）隔离，不夹带历史 channel refresh 补丁。

#248 在此拓扑再次单次复现：`clear` HTTP 204，重新写入并确认重启前 GET HTTP 200；
首次 Server 重启后的第一笔 GET 为 200、值正确，完整 verify exit 0（`51-*`、`52-*`）。
旧线索仍未定位。计划第三次加入第二台 Server 的交叉读取，但专属 bind staging 的
`docker run --rm --mount` 被自动审批拒绝，命令未执行、未创建容器或数据卷；
此项不计作第三次未复现（`133-248-third-preflight.txt`）。停掉原 PD leader pd0 后，
第一次边查询 HTTP 500、`InterruptedException`，
约 25 秒后的 verify exit 0，pd2 日志记录成为 leader；恢复 pd0 后 verify exit 0
（`53-*`、`54-*`）。这证明有恢复和一个失败窗口，不能写成零中断或物理多机 HA。
七个容器及网络已清理，原始日志和专属数据仍保留（`55-*`）。

standalone #249 真实服务 snapshot 对照：标准与 TP 均在本轮镜像独立父数据根上先经
REST 建两顶点一边，`PUT snapshot_create` HTTP 200，再写并读快照后第三顶点（201/200），
`PUT snapshot_resume` HTTP 200。resume 后**同进程**中快照前数据仍可读，但快照后顶点
两种 provider 都仍返回 200，未满足即时可见性断言；首次响应分别为 `58-*`、`63-*`。
各自重启后第一笔快照后顶点 GET 均为 404，快照前两顶点一边的完整 verify exit 0，
说明本轮持久数据/WAL 恢复成功，但同进程缓存未失效问题仍需沿通用缓存项归因。
TP 重启前停机再现 #212 `db not closed` 且 exit 137；标准停机 exit 0。
全套原始证据为 `56-*` 至 `65-*`。复制、发布、校验、嵌套/独立 WAL、pending/锁并发的
失败注入只由本轮真实 JNI helper 覆盖，尚无服务 API 故障注入通过证据；不扩大声明。

为补强镜像本身而不冒充长驻服务映射，另将仓库 TP JAR 编译的
`ImageRuntimeIdentity.java` 作为只读 probe 装入 Server/PD/Store 三个本轮镜像，
分别实际 open、put、get、close；三个进程均 exit 0，打印 `RocksDB.class` 组件本地
TP JAR 来源及其 `/proc/self/maps` 中的 native 路径，见 `66-image-runtime-*.log`。
它证明镜像中可真实装入并使用 TP JNI，当时仍不等于 PD/Store 长驻服务 PID 的
maps 已读到；后续 `216-*` 独立完成了服务内映射验证。

固定 SHA 的单 DB 挂载缺陷在隔离工作树
`/home/soc-baidu/.codex/worktrees/topling-linux-image/hugegraph` 的
`codex/toplingdb-linux-mount-preflight` 分支修复，提交
`b4905385124a1c7fbd2668526510ac438f53398d` 的父提交正是本轮冻结 SHA。
Docker entrypoint 在认领 provider/建立 `.hugegraph-state` 前检查最终所有本地图的
`m/g/s`，直接启动和 init-store 走同一预检；保留 Docker 主根在改写配置前对冲突 marker、
符号链接和未认领非空目录的只读拒绝。Linux 仅对实际本地 RocksDB 图要求
util-linux `mountpoint` 2.37+ 与可读 `/proc/self/mountinfo`；非本地图不引入此依赖。
预检后外部特权进程再更改挂载的竞态不在此保证内，Java 原有逐 DB guard 仍在。
三名独立只读审查者分别检查数据安全、兼容性、测试/文档；初审指出 Docker 早期认领、
Linux mountinfo fail-open、纯 HStore 误需 mountpoint、旧 util-linux 退出码和文档覆盖边界，
修正后复审均无剩余阻塞。此分支不含旧 `f29e` WAL/channel 改动，未切换或重排原活动分支。

修复验收：`bash -n`、`shellcheck` 均 exit 0（`106-*`），入口 fixture exit 0、23 PASS
（`99-*`）；`mvn editorconfig:format` exit 0、格式化 0 个文件（`107-*`），
`mvn clean compile` 最终 exit 0（`97-*`），
`mvn clean package -pl hugegraph-server/hugegraph-dist -am -Dmaven.test.skip=true` exit 0（`101-*`）。
首次完整编译 `96-*` 因先前生成的 TP 发行目录中的第三方网页被 Apache RAT 扫入而失败；
将该生成目录移到专属 data 目录后原命令成功，未修改源码规避检查。
标准与 TP Server 发行包重新生成；TP 包合同通过（`102-*`），helper 字节比对与
包内 TP JAR/native SHA-256 核对通过（`106-*`），后两者仍分别为
`86eb1bd3...`、`c25ff6e6...`。
`98-*` 为真实 Docker 的额外图 `data/g` 同文件系统 bind mount：进程 exit 1、
主数据根前后文件列表完全相同、目标挂载源哨兵不变；`100-*` 为父数据根挂载的真实 TP
CRUD 和首次重启后完整 verify 均 exit 0，但重启停机仍报 #212 `db not closed`，
不能计作生命周期通过。两项在提交前以同工作树脚本只读覆盖到冻结 SHA 镜像，
验证修复逻辑的真实挂载效果；未在当时保存覆盖脚本哈希，故不冒充新提交镜像的
产物身份或精确提交字节测试。`93-*` 至 `95-*`
另有标准 provider 单 DB 负例及父根正例。测试容器均已移除；两项 Docker 测试留下的
匿名 `rocksdb-data` 卷见 `98-container-inspect.txt`、`100-container-inspect.txt`，
尝试定向删除时自动审批要求授权而当前策略不允许，因此未清理或扩大到其他卷。

已提交源码的新镜像重新完整构建：`103-candidate-image-build.log` 中 Maven 27 个 reactor
项目成功，TP standalone 镜像 manifest digest 与实际 imageID 均为
`sha256:3ffb619f7fa7cfcaa704d189c0fd425e1b2b8ebd56f757b7aa4d2f3fc476b45a`；
`112-*` 标准 standalone 镜像 digest/imageID 均为
`sha256:ecdf8aa88b43293c78e298732ecf401d2e658e7c5cf9f117d0659bfc9af46111`。
两镜像 revision 标签均为完整 `b490538...`。TP 镜像内 JAR/native 哈希仍为本轮预期，
helper 与 Docker entrypoint 哈希和源码一致，util-linux `mountpoint` 为 2.39.3，
`ldd` 无缺库（`103-candidate-image-identity.txt`、`103-candidate-image-files.txt`）。
直接使用该 TP 新镜像（无脚本覆盖）的额外图单 DB bind mount 负例 exit 1，主根前后
均为空（`104-*`）；父根正例完整 CRUD create/首次重启 verify 均 exit 0（`105-*`），
但停机仍出现 #212 native 断言。宿主用户读长驻服务 PID maps 被拒；`docker top`
记录 TP JAR 在 Java classpath 首位，独立 `ImageRuntimeIdentity` probe 在镜像内打印
TP JAR CodeSource/native 映射并完成读写关闭（`105-candidate-image-runtime-probe-corrected.log`）。
第一次 probe 未带启动链所设的库路径而触发双加载崩溃，原始
`105-candidate-image-runtime-probe.log` 保留，不作为服务失败或通过证据。
同一双 runtime TP 镜像选择**标准** provider 时，真实额外图挂载负例 exit 1 且主根不变，
CRUD create 和两次 reopen verify 均 exit 0；Java classpath 含标准 JAR、无 TP JAR，
停机 exit 0（`113-*`、`114-*`）。`113` 首次脚本因误以为标准 JAR 必在 classpath
首位而 exit 1，实际 CRUD 已成功，后由 `114` 独立完成重启断言。单独的标准候选镜像
已构建并核对身份，但未启动其服务；该标准 provider 实测使用双 runtime TP 镜像，
不可混称为标准镜像服务实测。新测试对镜像声明的两个根均使用任务专属 bind mount，
没有继续创建匿名卷；容器已清理。

#249 冻结 SHA 的真实 TP 服务补充了 checkpoint 校验失败和重新打开：在独立父数据根
CRUD 基线、`snapshot_create` HTTP 200 后写入并读到第三顶点；将
`snapshot_data/{m,g,s}` 的 MANIFEST 移到专属备份，`snapshot_resume` 首次 HTTP 400，
响应为 checkpoint MANIFEST 缺失，未产生 `.resume-pending`（校验发生在 begin 前），
数据目录未进入安装阶段。首次脚本错误预期 500 而 exit 1，原始 `108-*` 不覆盖；
停机后将三个 MANIFEST 原样恢复且 SHA-256 均匹配（`109-manifest-restore-hashes.log`）。
重新打开时快照前数据和快照后第三顶点均可读；通用 smoke 的 Gremlin“恰好两点”
断言因刻意添加第三点而 exit 1，原始 `109-*` 保留。改用逐顶点断言后
`snapshot_resume` HTTP 200；重启后基线完整 verify exit 0，独立新容器再次读取
快照前顶点 HTTP 200/200、快照后顶点 HTTP 404（`110-*`、`111-*`）。
恢复期间 TP 停机 exit 137/#212 仍在。此服务测试只覆盖**校验前拒绝**，故 pending
应为空；复制/发布失败、独立/嵌套 WAL 和精确锁竞态仍仅有本轮真实 JNI helper
测试，发行包没有选择这些测试故障模式的服务配置，不能称服务级失败注入已通过。
`116-*` 以同一冻结 SHA 的双 runtime TP 镜像显式选择**标准** provider 作服务对照，
`docker top` 有标准 JAR、无 TP JAR：snapshot_create HTTP 200，移走三份 MANIFEST 后
resume HTTP 400 且无 pending；两次停机均 exit 0。原 MANIFEST 哈希恢复后重新打开，
快照前两点和快照后第三点均 HTTP 200，重新 resume HTTP 200，首次重启完整基线
verify exit 0、第三点 HTTP 404。此对照证明双 runtime 冻结镜像在标准 provider、
标准 JAR classpath 下的服务故障路径；长驻服务 native 映射未独立取证，
也不等于使用独立标准镜像进行此服务故障注入。

#249 继续在**冻结 SHA** 做真实服务 WAL staging 复制故障：TP 与标准 provider 分别
使用专属父数据根 bind mount，checkpoint 与数据保持同一宿主文件系统；标准 provider
使用上文的双 runtime TP 镜像并选择标准 JAR，非独立标准镜像，长驻 native 映射仍未取证。仅配置的 WAL
根挂 64 MiB tmpfs。两次 `snapshot_create` HTTP 200，原 checkpoint 中数字 `.log`
合计均为 0 字节；向 `snapshot_data/{m,g,s}` 各加入一份 96 MiB 的测试用
`999999999.log`。首次 `snapshot_resume` 两者均 HTTP 400，`data/s.resume-pending`
和三个 checkpoint 均保留；测试流程未删除 pending marker，lock 状态未逐文件取证
（`123-*`、`127-*`）。
两次 REST 错误响应只显示上层 BackendException，首次脚本因误从响应/stdout 查找
ENOSPC 而 exit 1，原始响应和日志保留。保持故障源再启动的容器内部日志分别在
`124-internal-logs/hugegraph-server.log` 与 `128-internal-logs/hugegraph-server.log`
明确指向 `data/s/999999999.log` 向 `wal/s/.resume-staging-*/999999999.log`
复制时的 `FileSystemException: No space left on device`；pending/source 再次保留。
TP 重新打开失败时 `/versions` HTTP 200、图 API HTTP 404，不能以容器 healthy
替代图后端成功；手动停机 exit 0 使 `124` 的错误退出码断言失败。标准重试进程
在查询时关闭连接，使 `128` 脚本 exit 56；底层堆栈证据仍完整，不能将脚本失败
写成恢复通过。

停掉故障进程后，只移除三份人工加入的 checkpoint 日志；移除前 SHA-256 与加入时
一致，`s.resume-pending` 在此操作前后字节相同；本次未取证 `.resume-lock` 的逐文件状态。
以相同 WAL 路径/64 MiB 容量启动新进程：TP `125-*`、标准 provider `129-*`
的基线两顶点一边完整 verify 均 exit 0，`s.resume-pending` 消失；TP 停机仍
exit 137/#212，标准停机 exit 0。**这证明 s 库的 WAL staging 复制失败可重试，
不能证明全图 snapshot_resume 完成**：两种 provider 在 s 恢复后都仍留有
`snapshot_data/g` 和 `snapshot_data/m`，而 s checkpoint 已被消费（`130-*`）。
源码按 store 顺序调用恢复，首个错误中断后续 store；启动时只自动处理该 DB 的
pending。此次没有快照后写入，因此基线可读不能当作 g/m 已回滚的断言。
服务级 WAL **复制**故障已补测；主数据树复制失败、WAL 发布失败、嵌套 WAL 的服务
故障路径仍仅有真实 JNI helper 覆盖，全图失败原子性和恢复协调仍是 #249 门禁。
只读源码复核表明，provider 和 RocksDBStore 各有一个遇错即停的 store/DB 循环；
g/m 无 `.resume-pending`，仅凭残留 checkpoint 无法在重启时判定是待恢复事务。
下一步先设计持久的图级恢复意图和成员清单，进入恢复前预验证所有源并阻止图读写；
所有 DB 安装及重开成功后先持久记录提交状态，再清理成员 checkpoint。还需覆盖首库失败后
立即崩溃、重启重放、独立/嵌套 WAL 与最终清理的确定性回归。此为修复方案方向，
尚未实现或通过三名独立只读审查及真实服务复测。
上述 REST 路径的持久化协议、崩溃点及测试切面详见专属 evidence 中的
`132-graph-resume-protocol-design.md`；它排除了仅用于 Raft snapshot 的
`StoreSnapshotFile`。冻结 SHA 的新隔离 checkout 位于
`/home/soc-baidu/.codex/worktrees/topling-snapshot-recovery/hugegraph`，分支
`codex/toplingdb-graph-snapshot-recovery`；建立时只做只读设计，未改动冻结源码。

随后该隔离分支新增真实标准 RocksDB 图级同进程回归及缓存清理实验（未提交）：
冻结源码加测试先在快照后顶点首读断言失败，exit 1、1 项/1 失败（`134-*`）；
实验性 graph/schema 缓存通知后，顶点/schema 回归 exit 0、1/0/0（`135-*`），
加入边断言的版本也 exit 0、1/0/0（`136-*`）。红灯与含边绿灯的测试源码版本不同，
不能写成逐字节同一测试；这些均为标准 JNI，本轮未以真实 TP 服务复测。
三名独立只读审查者指出并发旧读可在清理后回填缓存、后续 DB 恢复失败仍可能对外
暴露部分图、`EventHub.NotifyResult.success()` 不检查监听器返回 false 三项门禁。
故候选不提交/推送，不作为同进程可见性修复通过。完整 Core 套件在阻塞审查结论后
主动终止，session 返回 exit 143、无完整计数，不能写为通过（`137-*`，退出状态见
`137-exit-code.txt`）；测试数据已移入专属 data
目录。审查与红绿证据的准确边界见 `138-cache-candidate-review.md`。

#249 图级 journal 骨架另在冻结 SHA 的干净隔离分支
`codex/toplingdb-graph-journal`（`/home/soc-baidu/.codex/worktrees/topling-graph-journal/hugegraph`）
推进，**尚未提交或接入真实恢复**。它已实现持久 `PREPARED/COMMITTED` 记录、组进程锁、
成员路径重叠拒绝及启动前发现 marker 时 fail-closed 的框架；没有安装/重开全部 DB、
延后单 DB checkpoint 消费、启动自动重放或在线流量隔离，不能判 #249 通过。
纯 journal 定向回归先 3/0/0/0，fixture 编译和 DATA_DISKS 类型各失败一次后 4/0/0/0，
加入交叉路径与 provider pre-open 后 6/0/0/0。现有 MultiGraphsTest 首轮因
过早解析无 marker 的无效 `/g` 配置而 11 项中 1 失败/2 skip，改为持组锁检查 marker、
存在时直接 fail-closed 后 11 项 0 失败/0 错误/2 skip。所有失败原始日志和恢复见 `140-*` 至
`147-journal-scaffold-status.md`；两次测试数据库已移出 worktree。首名独立只读
审查者发现跨成员数据/源覆盖与无锁检查竞态两个 P1，修正后复审这两个直接路径无阻塞；
完整三人审查、发行包和真实标准/TP 服务故障回归仍待完成。
源码又确认 `snapshot_create` 逐 DB 建 checkpoint，不保证并发写入下同一逻辑时刻；
后续必须明确停写/流量隔离条件并验证，不能用 journal 本身代替一致性快照。

上述为 `147-*` 时的骨架阶段；随后同一**未提交**候选加入单 DB 延后消费、provider
整组 m/g/s 恢复与启动前重放。真实标准 JNI 定向测试：成员 native 重开仍保留源
`148-*` 7/0/0，COMMITTED 后消费 `149-*` 7/0/0，provider 三库成功路径
`150-*` 8/0/0，首库已安装后由新 provider 重放三库并验证快照前值 `151-*`
9/0/0；现有 helper 27 + session 16 + 新 journal 9 的定向合计 `152-*`
52/0/0/0。均为宿主标准 JNI，**非真实 TP 服务**，不覆盖全量 Core/发行包。
独立只读安全复审指出在线门禁仍不完整：初版已打开 TinkerPop tx 可跨恢复提交；
新 `snapshotGate` 关闭这一路径后，schema 自动提交不增加 tx refs、预先保存的
SchemaManager 可绕过失败拒读、长寿命 iterator 可在 native 关闭后继续遍历。
因此此候选仍不得提交/推送；需要统一图级停流与 epoch、确定性并发故障测试、
三名独立只读审查者复审和真实标准/TP 服务复测。命令、退出码、审查与未覆盖边界见
`153-group-replay-candidate-status.md`。此前单 DB mount 的隔离修复候选与本分支分开保留。
随后对同一未提交候选做 clean 编译并重建 classpath，在测试 JVM 中核验候选各类的
CodeSource、本轮 TP JAR SHA-256 与实际 native 映射/SHA-256；`161-*` 定向 journal
9 项/0 失败/0 ignored、exit 0。首次执行的 classpath 来源预检因测试模块自身
`target/classes` 未显式列入而 exit 1，**未运行 JNI/JUnit**；修正后 clean 运行及完整原日志
均保留。此 fixture 的 `FakeObjects.newConfig()` 未设置 `rocksdb.provider`，默认仍是
`rocksdb`：结果只证明真实 TP JNI 二进制下的 journal 测试路径，不独立证明 Java
`topling` provider 分支或真实 TP 服务恢复。上述在线门禁、完整回归和三人复审仍未通过。
再从同一候选源码 clean 构建 Server 标准/TP 发行包和独立测试镜像（`163-*`）；TP tar
SHA-256 为 `90f6ca2ddf4ca27be9ff05b2d3eceb4ed40ab55c6ba06c0ff61796b661ad152c`，
镜像 manifest digest/实际 imageID 同为
`sha256:5c920aa8bb8e1c289ff700b0b70fb173b7b15ad35efe8550e734bf95184317da`。
镜像 label 的 revision 是冻结 SHA，另以 `71580a3e…dd52a` 标记九个未提交候选源码路径；
包内 TP JAR/native 哈希与本轮固定值一致。此镜像仅供隔离测试，不是正式产物。
首次真实 TP 单服务 `162-*`：新挂载根上 CRUD、`snapshot_create` HTTP 200、快照后
新增顶点 POST 201/GET 200；`snapshot_resume` HTTP 400，明确为
`Close active graph transactions before snapshot restore`，脚本 exit 1。候选的
`tx.closed()` 预检在正常 REST 访问后仍拒绝服务级恢复；三库 live `CURRENT` 与 checkpoint
`CURRENT` 均保留，未产生 pending/journal，不能把前述 JUnit 9/0 计作服务通过。
不删除文件，另用同一数据根正常重开 `164-*`，快照前两点与快照后点首次 GET 均 200、
ID 匹配，脚本 exit 0；三个 checkpoint 仍在、pending 仍无，证明该预检拒绝未改变
已确认数据。此容器 SIGTERM 后仍报 `SidePluginRepo ... db not closed`，45 秒 exit 137，
#212 仍失败。控制容器均已移除，原始日志与数据留在专属目录；生产并发安全、实际整组
恢复、即时缓存可见性及后续首次重启断言均未由此测试通过。
为区分 CRUD 残留与启动期事务，再在另一专属根执行最小服务对照（`165-*`）：
无 CRUD、顶点读取或 Gremlin 请求，仅 `/versions` 后 `snapshot_create` 200，紧接
`snapshot_resume` 仍 HTTP 400、同一活跃事务拒绝；脚本 exit 1，停机 exit 0。
在保持三库 checkpoint/锁文件原状下正常重开，待 `/versions` 后空闲 60 秒再试（`166-*`），
仍 HTTP 400、脚本 exit 1、停机 exit 0。第二次尝试前采集的十份 checkpoint 文件
在失败后 `sha256sum -c` 全部匹配、exit 0；live/checkpoint 的三库 `CURRENT` 仍在，
没有 pending/journal。命令、时间、响应、退出码和数据边界见 `167-*`。
因此当前 `tx.closed()` 门禁在普通服务序列里无法进入实际整组恢复，不能仅因
“拒绝发生在写入前”判 #249 通过；需要可验证的图级排空/维护流程，并保留 schema、
惰性迭代器和缓存的在线安全检查。

为定位这次普通服务序列的活跃事务，用测试专用 Java 11 agent（`168-txrefs-agent/`）
只读输出 TinkerPop 事务引用和所属线程，挂载到上述独立候选容器；未更改镜像或业务数据。
`169-txrefs-service-run.sh` 中 `snapshot_resume` 仍 HTTP 400，诊断脚本 exit 0、容器
停机 exit 0，三库 checkpoint 文件前后 SHA-256 校验 exit 0。同一 Server JVM
最初 `refs=1 threads=[main]`，随后持续为 `refs=1 threads=[task-db-worker-1]`，
见原始日志和 `170-txrefs-owner.md`。源码追踪表明 `restoreTasks()` 的状态扫描
在 task DB worker 中调用 `queryTask(Map...)`，该路径的
`graph.backendStoreFeatures()` 会经 `StandardHugeGraph.graphTransaction()` 打开
上层事务；按 ID 查询的 `GraphTransaction.queryTaskInfos(Object...)` 有相同入口。
启动收尾只关闭主线程事务，不能释放 worker 上的引用。这解释当前预检拒绝，
但单独的 refs 证据不证明全部 native CF 引用也来自此处。

在冻结 SHA 的另一个隔离分支 `codex/toplingdb-lifecycle-212` 试过对任意
`StandardTaskScheduler.call()` 在 `finally` 回滚当前图事务：冻结代码红测
`171-*` 2/2 失败，候选绿测 `172-*` 2/0，现有任务定向 `173-*` 19/0。
三名独立只读审查者发现该通用回滚会丢弃调用者有意保留的未提交写入，且有关闭竞态；
代码已移除，原 patch、测试和 SHA 留在 `174-*`，不提交该方案。
目前更窄的**未提交**候选只把 task 状态和按 ID 查询的 feature 来源改为正在使用的
`TaskTransaction.storeFeatures()`，不在任意任务后回滚。状态查询在冻结代码
`175-*` 1/1 失败、改单行后 `176-*` 1/0；按 ID 查询在单行版 `178-*`
1/1 失败、加对应改动后 `179-*` 1/0。旧的单行候选内存 Core `177-*`
819/0、96 skip、exit 0；这是中间版，不能算最终候选完整回归。最终候选
RocksDB 定向 `180-*` 中持久任务 `TaskCoreTest` 17/0 与新 worker 事务测试
1/0，启动断言在内存 `181-*` 和 RocksDB `182-*` 均 1/0；这些是引擎级
证据，不是 TP 服务停机或 graph journal 恢复通过。候选的 RocksDB Core
完整套件 `183-*` 为 819/0 失败/0 错误/42 skip、Maven exit 0；其运行时还包含
一处无调用点的 server-info feature 改动，随后已撤回该行，最终仅保留两处任务查询
改动，需以最终 diff 的定向实测和复审为准。只读审查指出关闭期间的 task 调用
接纳竞态在冻结代码中已存在，本次窄修复不解决它，也不应宣称并发关闭安全；
真实标准/TP 服务及 journal 恢复仍须复验。
随后从最终两处候选源码 `mvn clean test-compile` exit 0，直接 JUnit 运行新测试时
`RocksDB.class` 来源为本轮 TP JAR，`/proc/self/maps` 确认映射本轮 native，JAR/native
SHA-256 均匹配。`184-*` 的 JUnit 为 Tests=1、Failures=0、Ignored=0，
但 JVM 退出时触发
`SidePluginRepo ... db not closed`，进程 exit 134；**完整运行失败**，不能只摘取
JUnit 计数算通过。此定向配置没有 `rocksdb.provider=topling`，因此它证明真实 TP
JNI 在该直接测试 harness 中退出失败，不能冒充 Java adapter 的 TP provider 分支通过。
该 harness 只运行测试类，没有执行 `CoreTestSuite` 的 `@AfterClass clear()` 关图步骤；
因此 exit 134 不能单独当作正常 suite 或服务的 #212 复现，需以带明确关图的进程
复测区分。原始命令、配置、classpath、映射和退出日志均在 `184-*`。
按此修正测试专用 harness，在确切隔离提交 `8b09df2b7` 上重新 clean test-compile，
从独立数据根以本轮 TP JAR/native 运行同一单类测试，并在 JUnit 后明确调用
`CoreTestSuite.clear()`。`192-*` 显示 JUnit Tests=1、Failures=0、Ignored=0，
关图日志出现、JVM exit 0，
无 `db not closed` 断言；实际 JAR 来源/native 映射与 SHA-256 仍匹配。
因此 `184-*` 的 134 是缺少 suite 清理的 harness 失败，不能计作 #212 的
独立复现；本轮真实 CRUD 服务 `187-*` 的停机 137 则仍是 #212 失败。

将上述两处任务查询改动**仅用于测试**叠加到仍未提交的图级 journal 候选上，
11 个源码路径逐文件 SHA-256 清单为 `185-combined-build/source-manifest.json`，
清单 SHA-256 `ec8f93bea90d6fb22bd44359cbeaa1fb173f6acc7cb32cfe68d3999af197b78a`；
父 HEAD 仍是冻结 SHA。`docker buildx --no-cache --target topling` 从该源码重建，
构建脚本 exit 0，镜像 digest/实际 imageID 同为
`sha256:5fae943216db879feab2fd3b713c858aba3e14873da4288719332771603bdc86`。
镜像标签 `local/hugegraph:journal-taskdb-candidate-ec8f93be-20260928` 仅是定位名；
label 同时记录固定 HEAD、未提交源码清单哈希和 `topling`，包内 TP JAR/native SHA-256
再次匹配本轮输入。原始构建、镜像身份和哈希见 `185-combined-build/`；它不是正式发行物。

首次最小服务脚本复制旧 checkpoint 数据根时，因 Docker 所建 provider marker 文件权限
在容器启动前 exit 1，原目录未改动；提权复制被自动审批拒绝，未绕过。保留部分复制目录与
`186-combined-minimal.log`，改用新的空数据根让服务自己建快照。该真实 TP 服务仅请求
`/versions`、`snapshot_create` 与 `snapshot_resume`，两个 snapshot API 均 HTTP 200，
三库 live `CURRENT` 均在、无 pending；脚本 exit 0，SIGTERM 后容器 exit 0。
测试探针恢复后记录 `refs=0 threads=[]`。原始 `186-combined-minimal-retry.*` 和
`186-combined-minimal/` 保留。这证明空数据的组恢复入口可进入，不证明带数据的即时可见性。

另一全新专属数据根执行完整真实 TP 服务脚本 `187-combined-service-smoke.sh`：
CRUD 创建两顶点一边并查询成功，`snapshot_create` HTTP 200，快照后新增顶点
POST 201/GET 200，`snapshot_resume` HTTP 200。测试专用 agent 在服务 JVM
输出 `RUNTIME_JAR` 为镜像内 TP JAR、`RUNTIME_NATIVE` 为
`/hugegraph-server/library/librocksdbjni-linux64.so` 的实际 `/proc/self/maps` 映射；
与上述镜像文件 SHA-256 相结合确认本次实际 TP 加载。恢复后**同进程首次 GET**
对快照后顶点仍为 200，快照前两点为 200；首次停机出现
`SidePluginRepo ... db not closed`，容器 exit 137。随后在同一数据根首次重启，
快照后顶点首笔 GET 为 404、快照前两点均为 200，原 CRUD verify 成功。
测试容器移除后另做文件系统检查（`193-data-postrun-inventory.txt`）：三库 live
`CURRENT` 存在、checkpoint `CURRENT` 缺失、无 pending；这是事后状态，
没有作为恢复 HTTP 调用瞬间的文件原子性证明。脚本按即时可见性断言 exit 1，
不能用重启后的成功覆盖该失败。原始 HTTP 状态/正文、运行时映射、容器日志、镜像
身份与首次停机码见 `187-*`；测试专用 agent 源码/JAR/hash 在 `188-runtime-agent/`。
该 journal 联合候选仍因 schema、迭代器和缓存的在线安全门禁保持未提交；#249、#212
均未通过最终验收。两轮测试容器已移除，kind 基础组件和 BuildKit 之外无本轮服务。
`187-*` 涵盖 REST CRUD 与服务内 Gremlin Server 的正常停机流程，没有独立
Gremlin 查询负载后关闭的断言；该模式仍待复测，不能把服务日志中的
“Gremlin Server - shutdown complete”当作其业务会话关闭通过。
随后用**冻结 SHA 的原 TP 镜像** `sha256:29874890b97578ba6cd786bb04b6de313f08f4cb1625726b7f13d868fe0678cc`
及新专属数据根补独立 Gremlin 只读查询/停机。首次 `197-*` 返回 HTTP 200，
响应是 gzip；脚本未解压便交给 `jq`，故脚本 exit 5，原压缩响应保留，
事后解压可见 `.result.data=[0]`，该容器停机 exit 0。另用全新根与
`curl --compressed` 的 `198-*` 重跑，`g.V().count()` HTTP 200、数据 `[0]`、
脚本 exit 0、SIGTERM 后容器 exit 0，未见 native 断言。测试 agent 的
`RUNTIME_JAR` 和 `RUNTIME_NATIVE` 再次确认实际 TP JAR 来源与 native 映射；
镜像内两个文件的本轮哈希已在固定镜像验收记录。此通过仅涵盖该只读 Gremlin
请求，不能覆盖 REST CRUD 后的 #212 exit 137 或更复杂 Gremlin 写入/会话关闭。
窄任务查询修复经三名独立只读审查者最终复核未发现该 diff 的 P0/P1，原有关闭期间
`call()` 接纳竞态未在本修复中解决；逐人结论抄录于
`194-three-reviewer-transcript.md`。`mvn editorconfig:format` 与
`mvn clean compile -Dmaven.javadoc.skip=true` 分别 exit 0（`189-*`、`190-*`）。
fetch 核对 `org/toplingdb` 仍为冻结 SHA、与隔离分支提交前 `0/0`
（提交后对远端 `1/0`，原始核对见 `193-code-remote-verification.txt`）后，在
`codex/toplingdb-lifecycle-212` 独立提交代码和测试
`8b09df2b71ea39efe7929c567041839cddbfad93`；随后本地 cherry-pick 到原
`toplingdb` 分支为 `54e5bebae6fa3cf737c5c39bfd05867dde4bd397`，前一提交
`388ec897098476c82fe6c3467d3f85109fd620ad` 是挂载预检修复 `b4905385` 的
本地整合。两者均未推送远端，
且 #249 整体不因此判通过。journal 的 11 路径联合候选仍是未提交实验。
整合后工作树 `54e5bebae6fa3cf737c5c39bfd05867dde4bd397` 运行
`mvn editorconfig:format` exit 0（`202-*`）。首次 `mvn clean compile`（`203-*`）
在 Apache RAT 阶段因旧生成的 PD TP 发行目录 `rocksdb_resource/index.html` 和
`style.css` 无批准 header 而 exit 1，尚未到达此次改动所在的 Server 模块编译；
将三组件旧生成发行目录
与 tar **搬迁保留**到专属数据证据区，原 tar SHA 和路径见 `204-*`，没有修改源码
或跳过 RAT。原命令重跑 `205-*` exit 0；合并入口 fixture `206-*` exit 0、23 PASS。
使用新的数据根及同时传给 Maven/Surefire JVM 的专属临时目录，标准 RocksDB
`CoreTestSuite` `207-*` 为 819 tests、0 failures、0 errors、42 skipped，Maven exit 0。
测试生成的两个非跟踪索引目录已移到专属数据证据区，原分支 `git status` 仅余证据文档。
上述是本地两修复整合后的**标准**回归；此前真实 TP 服务分别在挂载修复候选镜像和
task/journal 联合测试镜像上验证，不能直接继承为精确整合镜像的结果。
随后以干净 `e5df44f9d3ed92ce4d8fee2b2ba523e19d8d37ec` 整合 HEAD
从零 Docker 构建 TP Server 测试镜像（`209-integrated-image/`）：构建 exit 0，
Maven 27 模块成功；镜像 manifest digest/实际 imageID 同为
`sha256:bec31d598635e5814d0a56e39f7b5eccea0f117c8c1323619a1bab383f7d3dc9`，
revision label 为完整整合 HEAD，镜像内 TP JAR/native SHA-256 仍分别为
`86eb1bd3…2031fae`、`c25ff6e6…174dd38`。此镜像仅供本轮隔离验证。
新镜像的额外图 `data/g` 单 DB bind mount 负例（`210-*`）中，容器 exit 1、
脚本 exit 0；日志精确报“DB 目录本身不得为挂载点”，主根前后文件列表均为空，
挂载源哨兵不变，证明该分支合并后仍在 Java DB 打开前拒绝且未部分认领主根。
另在专属新数据根用同镜像运行 TP CRUD/首次重启（`211-*`）：smoke create、verify
各 exit 0；服务 JVM agent 输出 TP JAR `CodeSource` 与本轮 native 的实际
`/proc/self/maps` 映射。首次 SIGTERM 日志出现 `SidePluginRepo ... db not closed`，
容器 exit 137；同根首次重启 verify 成功，最终 SIGTERM 再次 exit 137。
脚本因此 exit 1，不能把 CRUD/重启通过误写成生命周期通过。事后检查 m/g/s
三个 live `CURRENT` 均在；`212-integrated-postrun-resource.txt` 显示测试容器
已移除，运行中的仍仅 kind 控制面和 BuildKit，内存 available 117 GiB、根盘可用
1.2 TB。该精确整合镜像的真实 #212 仍失败，#249 journal/即时缓存门禁亦未解除。

补 PD/Store **长驻服务 JVM** 的真实 native 身份门禁：使用冻结 SHA 的 TP PD 镜像
`sha256:31fa84ca7629405aff5cd2f3c5d70f859b503e094fdd5c53c1fa913b16cbb596`
与 TP Store 镜像
`sha256:a0df11c186ecf8351a247ed660bb3c20cba1b603362747a45097603a70afc17e`，
均再核对 revision label 为冻结 SHA。测试专用只读 Java agent（`214-*`）挂载到两个
实际服务 JVM；它等到 `/proc/self/maps` 出现 native 后，输出 `RocksDB.class`
CodeSource、映射行、两个文件的 SHA-256 和 PID，不触发额外 DB open。独立 Docker
桥接网络只运行 1 PD + 1 Store，REST 只发布到 `127.0.0.1`，不同的空 bind 根分别
存放 PD/Store 数据；一次性 PD 密钥由脚本生成并经环境注入容器，运行期间也在
Docker 容器元数据中，证据写入时替换其值，测试后容器已移除。
首次 `215-*` 在 PD 启动早期健康请求连接重置时由 Python 未捕获异常导致脚本
exit 1，Store 尚未启动，没有 native 身份结论；PD 容器/网络已清理，PD 数据根
与原日志保留。修复健康轮询的连接重置处理后，用**新**数据根和网络执行 `216-*`：
PD、Store 的 `/v1/health` 均 HTTP 200；两者仍运行时，服务 JVM 分别打印
`/hugegraph-pd/lib/topling/...jar`、`/hugegraph-store/lib/topling/...jar` 的实际
CodeSource，JAR SHA-256 均为 `86eb1bd3…2031fae`；各自映射
`library/librocksdbjni-linux64.so`，native SHA-256 均为 `c25ff6e6…174dd38`。
`216-pdstore-live-run.exit` 为 0，脚本保存健康响应、imageID/labels、运行中
inspect、服务日志和清理结果。事后 `219-*` 在任务 bind 根确认 PD 的
metadata/raft 和 Store 的 metadata 均有 `CURRENT`、`MANIFEST`、`IDENTITY`，
没有只凭 provider marker 判定 backend 初始化。PD/Store SIGTERM 后进程均 exit 143，未见
`db not closed` 断言；这是映射验收通过，**不代表优雅关闭门禁通过**。
两容器与网络均已移除；`217-*` 复核仍仅 kind 控制面和 BuildKit 常驻、
available 内存 117 GiB、根盘可用 1.2 TB。镜像默认 `VOLUME` 在两次测试中
另建三个匿名卷，ID/创建时间见 `218-task-anonymous-volumes.txt`；任务 bind 根
分别保留。`218-task-volume-sizes.txt` 的 `docker system df -v` 对这三个卷
各报 0B、0 引用；旧的定向卷删除
曾被自动审批拒绝，本轮未绕过或批量 prune。

收尾资源复核（`131-resource-final.txt`，此前阶段见 `117-resource-final.txt`）：
所有本轮验收服务容器已移除，仅 kind 控制面与 BuildKit 容器运行；集群内只有
`kube-system` 和 `local-path-storage` 基础 Pod，无旧 HugeGraph 服务批次。
随后按用户要求再次清理历史服务占用：清理前 Docker 有 15 个已退出的 HugeGraph/原生构建容器，
`docker system df` 报告容器层 90.96 GB、其中 90.95 GB 可回收；先将各容器完整
`docker inspect`、`docker logs`（gzip）和日志 SHA-256 留在专属 `154-*` evidence 目录，
再用 `docker rm` 移除这 15 个已退出容器（exit 0），未删除其挂载卷和验证数据。
清理后 `155-resource-after-container-cleanup.txt` 显示 Docker 仅运行 kind 控制面和
BuildKit 两个容器，容器层 3.981 MB；根分区可用从 1.1 TB 增至 1.2 TB，约回收
84 GiB 实际磁盘空间。kind 仍只有五个基础命名空间，无 HugeGraph Pod；内存可用 116 GiB。
Docker 报告 80 个卷共 7.58 TB 属于逻辑计数，包含验证数据，未据此批量 prune；
后续仍按一次一批服务启动，并在每轮结束时回收容器、保留任务证据。
先前两项任务匿名卷仍在，定向删除被自动审批拒绝；未清理非任务资源。
`195-resource-after-service.txt` 再核对联合候选测试后资源：内存 available 117 GiB，
根盘可用 1.2 TB，运行容器仍只有 kind 控制面与 BuildKit；kind 只有五个基础
namespace、无 HugeGraph Pod。Docker 的 80 个卷包含验证数据，继续保留，
不按“可回收”逻辑计数批量清理。
`199-resource-after-gremlin.txt` 表明后续两轮 Gremlin 容器也均已移除，仍只有
上述两个常驻容器；内存 available 117 GiB，根盘可用 1.2 TB。

用户再次要求只保留一批服务占资源。`263-resource-cleanup/before.txt` 确认 kind
仅有控制面和基础 Pod，Docker 只运行 kind 控制面及本任务 BuildKit；两者各占约
990 MiB 内存。执行 `docker stop -t 30 buildx_buildkit_hg-topling-closure-local0
kind-control-plane` exit 0，容器保留为 exited、卷和镜像未删除，当前无运行容器；
available 内存从约 116 GiB 升至 118 GiB，根盘可用仍约 1.2 TB。
`263-resource-cleanup/{before,stop,after}.txt` 保留命令前后状态。历史服务容器层
此前已回收约 84 GiB；本次停容器只释放运行资源，不把镜像/验证数据当作可丢弃垃圾。

整合 TP Server 镜像 `sha256:7379d24f6231eba72762593ee5d47964014e31f3b0d4ef5ec2e68ed24402ac42`
（revision `e17f1b6d8`，源自冻结 SHA 后的本地修复）新增真实崩溃恢复实验，命令
`bash evidence/264-tp-sigkill-restart/run.sh`，脚本 exit 0。独立 bind 根
`data/tp-sigkill-restart-264/` 中先由 Server smoke 创建 property key、vertex/edge
label、2 顶点和 1 边并读回，`JAVA_TOOL_OPTIONS` agent 在运行进程打印 TP JAR
CodeSource 与 `library/librocksdbjni-linux64.so` 实际映射。对该任务容器执行
`docker kill --signal=KILL`，退出码 137；`g/m/s` 的 CURRENT/MANIFEST
在 kill 前后 SHA-256 校验均为 OK，三份 `.resume-lock` 文件名/大小未变。
同一容器和同一数据根重启，先等待 `/versions` HTTP 成功，再只发一次顶点 GET：
curl exit 0、HTTP 200、ID 与 `riscv-smoke-v1-kill264` 匹配；随后完整 smoke verify
exit 0（两顶点、一边及 Gremlin count=2），最终 SIGTERM stop exit 0。
容器已移除，数据及 `264-*` 原始日志保留；未删锁/marker、未重试失败图请求。
前一轮新根 `262-*` 的 create、SIGKILL 137、文件校验与锁检查成功，但重启后
脚本在服务监听前直接 GET，curl exit 56/连接重置，故该轮不能计作数据恢复通过；
原始失败保留。`264-*` 仅证明此单 Server 场景的崩溃后读取，不能替代 #249
全图 snapshot 原子恢复、在线 pending 并发或多节点故障验收。

此段记录前的状态取样：本地 `toplingdb` HEAD 为
`9dd7b5b85d98a3fd6903262f91625ee39850d69e`、工作树干净；fetch 后
`org/toplingdb` 仍为冻结 SHA，该取样的 ahead/behind 为 `19/0`。
代码与证据文档已分别提交，远端推送此前被自动审批拒绝且 GitHub 认证失效，
关联 issue 仅保留本地进展草稿，尚未更新远端；不绕过拒绝或强推。
后续独立复核 #212 更广的关闭并发/DB-CF 生命周期、#249 恢复后即时缓存与
schema/iterator 在线安全、服务级复制/发布故障及全图失败重放，再复核单 Store
查询连续性和 #248 原始时序；#249 恢复并发要求是在线业务还是维护排空的澄清
仍待用户答复，不据此假定在线安全已通过。#213 正式 JNI 来源/许可仍需上游发布审查。
#252 的固定 workload 三轮对照继续依赖相关正确性和资源门禁；PD/Store 长驻
TP native 映射门禁已通过 `216-*`，无需重复作为性能前置障碍。

与 [state.md](state.md) 一起读取；先确定要验收的 Mac 交付提交，再按需查看证据，不全量重跑历史清单。
下方旧服务器环境与历史结果来自先前记录；本轮实际结果以上方进度为准。
Mac 上的 Linux 容器核心实测身份与结果见 [mac.md](mac.md#本机核心实测身份)，不能替代此处服务器验收。

## 环境和恢复边界

- 历史工作区 `/home/soc-baidu/.codex/worktrees/f29e/hugegraph`，本地分支 codex/toplingdb-linux-validation，
  远端 org/toplingdb。旧主 checkout `/home/soc-baidu/github/hugegraph` 不作为恢复依据。
- 恢复先核对主机、工作区、HEAD、远端 URL 与未提交文件。Mac 代码经推送后再整合，禁止 force-push 或覆盖本地改动。
- 历史未提交 channel refresh 及 WAL 文件有审查未收口记录。channel refresh 属通用问题，维持独立跟进；
  WAL 当前由 Mac 基于已提交版本重新设计，不自动复用或提交 Linux 旧补丁，不自动重开旧补丁审查轮次。
- Kubernetes 历史配置为 /home/soc-baidu/.kube/config、kind-kind。只在确认资源归属后操作，
  不原位升级历史 namespace、不清理无关资源；单节点 kind 不证明物理多机或真实网络分区。
- 同一时间只运行一个重任务；Maven 全量、镜像构建、部署和故障注入不叠加。
  不提交 evidence/、数据库、镜像、原始大 JSON、数据集或凭据。

## 验收清单

场景沿用交接清单；状态按上方固定 SHA 的实际执行更新，不继承旧 SHA 的通过。

| 项目 | 验收场景与预期 | 状态 |
| --- | --- | --- |
| #250/#251/#253 | 直接及容器启动，默认/自定义目录和额外图；实际 JNI 与 Java provider 一致；冲突在数据库打开前失败，原数据不变 | 冻结 SHA 单 DB mount 全图失败；隔离修复候选通过且本地整合 `388ec8970`，待推送；PD/Store 长驻 TP 映射已通过 `216-*` |
| #254 | 用真实 TP JNI 经 adapter 执行多 key truncate，旧数据全空、CF 保留、可重新读写并关闭；标准 provider 对照自身预期分支 | 本轮通过：TP 1/0/0，标准对照通过 |
| #255 | 真正运行 runtime diagnostic，检查前置探测、错误分类、原始日志和 JNI 身份；仅已知断言得到例外，其他错误阻塞 | probe 通过；合成 CF 精确断言例外，整合候选的 REST CRUD 服务关闭通过，完整 CF 生命周期未收口 |
| #249 | 标准/TP 确定提交分别验证 snapshot 成功与故障恢复，包含独立/嵌套 WAL、失败后重启及源文件校验 | helper、持久回滚、服务校验拒绝、标准/TP 的 s 库 WAL/数据树故障后重试及跨进程同时打开锁保护通过；全图恢复、pending 恢复并发、在线缓存安全、WAL 发布服务故障仍待验 |
| #212 | 核对真实 DB/CF 和服务生命周期的残余关闭告警，区分已知合成断言、正常关库和卡住 worker | 冻结 SHA 真实复现；本地整合 `e17f1b6d8` 的 REST CRUD/并发读、重启及两次 SIGTERM 通过；更广关停/CF 门禁待验 |
| #248 | 复查 clear 后首次 Server 重启丢可见性的单次线索，固定确认写入及查询证据；第二次成功不覆盖第一次异常 | 三次有效实验未复现，第三次含第二 Server 交叉读取和主 Server 首笔 GET；旧线索未关闭 |
| #213 | 核实不可变 JNI 坐标、源码/工具链、CPU 基线、校验和及许可/发布链 | ABI/CPU 已核，正式发布链未完成 |
| #252 | 相关正确性与资源条件满足后，同源码、workload、资源和配置做标准/TP 至少三轮性能对照，保留原始结果及统计 | 后置，等待正确性门禁 |

多节点和部署验收在 Linux 阶段按实际拓扑记录；通用 HA 建设、图级快照或完整平台矩阵不作为所有 TP 项统一前提。
服务器侧历史未提交内容保持原样；本轮没有复用旧镜像作新源码证据。

## 此前交接与复跑依据

第三轮启动集成修正已完成本机真实回归：`89979877b` CI 为 37 成功/1 失败，
唯一失败根因为 startup fixture 在前序安全校验认领 data 目录后误跳过 backend 初始化。
修正后安全脚本→完整启动套件→同一 backend 再运行均通过，两轮 startup 各 16/0。
接收后须包含 startup fixture 修正 `8d92909b78f05e762e3e741a0949d6d33dac9374` 及最新文档；
fetch 后核对提交已在原分支，保留既有未提交改动。
脚本 cleanup 会操作进程、默认端口和 cron，仅在专用隔离测试容器/工作区运行，不在既有服务主机直接执行。
上述文字写于提交时；本轮 38 项 CI 成功与实际服务器验收进度以上方记录为准。
旧目录/marker 不能证明 backend 已初始化。

### 第二轮修正与源码基线

第二轮 CI 补救已完成本机回归与审查，确定源码提交 `218f52309a7109d19d76b3161674d24c86c2c4c3`。
其基线 `e4fef4b95` 已有 36 项成功、2 项失败，分别为安全启动 fixture
使用缺失 REST 被 provider selector 在 JVM 前拒绝，以及 SLF4J 旧版本的 known inventory 未同步删除。
生产恢复/隔离代码仍沿下方第一轮代码 SHA；当前接收须包含本次 fixture/清单修复提交及最新文档，
fetch 后核对该提交已在原分支，保留服务器旧未提交内容。新 head CI 尚待验，不继承基线的 36 项成功。
安全启动回归使用构建后的 Server 发行包执行，要求脚本输出 PASS，不把单次 Maven 成功当整个 CI 成功。

### 第一轮生产代码与复跑命令

第一轮 CI 补救已通过本机验证与审查；其远端结果见上方 36 成功/2 失败记录。旧 `cf25a438a` 有 18 个失败 job，
其中 16 个是本 PR 新增 PD 测试在打包后的 classpath 问题，2 个是新恢复锁导致的标准多盘契约回归。
不得沿用旧 complete 标记作为合入依据。该轮确定代码 head 为 `dfd4ce07e98e5846a2a093750f4b59a3061ab393`，
其中 PD 打包提交 `5401221996d7af71a0ea9a4243b5f0d1529237cc`；fetch 后核对它们已在原 `toplingdb` 分支，
再在保留本地旧补丁的前提下整合、构建和验收。
除原清单外须复跑完整标准 CoreTestSuite、多盘/shared CF、恢复锁并发、PD clean package/install
及实际 Boot 发行包/TP launcher。helper 最终版本预期 27/0/0，旧 24 项通过仍只归属旧 SHA。
同一 owner 的初始化与恢复互斥已覆盖；未声明任意共享副本独立恢复后都可重绑定 native 引用。
新增构建/核心门禁从干净源码按顺序执行，不以旧 target/classes 代替打包依赖验证：

```bash
CI_TEST_TEMP=$(mktemp -d /tmp/topling-ci-closure.XXXXXX)
mvn clean install -DskipTests -Dmaven.javadoc.skip=true -ntp
mvn test -pl hugegraph-server/hugegraph-test -am -P core-test,rocksdb \
  "-DargLine=-Xms512m -Xmx2g -Djava.io.tmpdir=$CI_TEST_TEMP" \
  "-Djava.io.tmpdir=$CI_TEST_TEMP" -Dmaven.javadoc.skip=true -ntp
mvn test -pl hugegraph-server/hugegraph-test -am -P unit-test,rocksdb \
  -Dtest=RocksDBSessionsTest,RocksDBSnapshotRestoreTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false \
  "-DargLine=-Xmx512m -Djava.io.tmpdir=$CI_TEST_TEMP" \
  "-Djava.io.tmpdir=$CI_TEST_TEMP" -Dmaven.javadoc.skip=true -ntp
mvn test -pl hugegraph-pd/hg-pd-test -am -P pd-rest-test \
  -Dtest=IndexAPIClusterStateTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false \
  "-DargLine=-Xmx512m -Djava.io.tmpdir=$CI_TEST_TEMP" \
  "-Djava.io.tmpdir=$CI_TEST_TEMP" -Dmaven.javadoc.skip=true -ntp
```

预期 Core 818 项、0 失败/错误、42 个原有 skip；session/helper 43 项无失败/错误/skip；PD mock 2 项无失败/错误/skip。
发行包只含一个 `lib/hg-pd-service-*-exec.jar`，实际 standard java -jar 与 TP JarLauncher 都须验证。

以下为上一轮代码交付，保留来源，不作为补救后的当前默认验收提交：

确定代码交付 head 为 `741c64a5c3858d7d3489d209acec0935b0a8af58`：
配置组 `8054b6552e67b872e601d3d3a8cb6021bab4f1aa`，WAL/adapter
`4bf7612e2acaef4d230b9b581a2afd31591332fe`，CI/诊断为
`ceea35428a1f182cda294a93636da3dd9c2a3438`，最后 head 只补 Docker ABI probe 的空目录 fixture。
四批代码已非强制推送，远端分支与 PR head 已核对为 `741c64a5c`；
接收时 fetch 原 toplingdb 分支，
核对完整 SHA 后在干净工作树构建；文档后续提交不改变该源码。
JNI JAR SHA-256 为 `86eb1bd3d9f84ef0dddd3fe95c640a6f145ca2d5a26a5ba628f1f298a2031fae`，
native 为 `c25ff6e676290db6db47df0954640aa609c391450ec90e1a8eec1f87e174dd38`；
这是此次验收输入身份，不代表服务器已经加载或验收通过。
如果服务器存在旧未提交 WAL/channel 补丁，先保留并逐项核对，不覆盖或夹带进本轮源码。

配置组的预期是 direct/init、Docker 都以实际图配置为准，冲突在 JNI 打开前失败；
真实服务测试要核对选中 JAR、native 映射、所有 data/WAL 根与失败前后文件。
WAL 预期是完整恢复快照前数据、移除快照后数据；失败保留 checkpoint/pending，
下次正常 open 必须先重试一致安装，缺源或配置不符则失败，不能伪报成功或重放旧日志。
单 DB 挂载布局被拒绝，Compose/K8S 挂载父数据根。

定向回归命令从仓库根执行，使用本次专用临时路径并确保传给 fork JVM：

```bash
mkdir -p /tmp/topling-closure-tests
mvn test -pl hugegraph-server/hugegraph-test -am -P unit-test,rocksdb \
  -Dtest=RocksDBSessionsTest,RocksDBSnapshotRestoreTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false \
  '-DargLine=-Xmx512m -Djava.io.tmpdir=/tmp/topling-closure-tests' \
  -Djava.io.tmpdir=/tmp/topling-closure-tests -Dmaven.javadoc.skip=true -ntp

TRAVIS_DIR=hugegraph-server/hugegraph-dist/src/assembly/travis
bash "$TRAVIS_DIR/test-topling-runtime-selection.sh"
bash "$TRAVIS_DIR/test-topling-server-entrypoint-roots.sh"
bash "$TRAVIS_DIR/test-topling-provider-ownership.sh"
bash "$TRAVIS_DIR/test-topling-native-diagnostic.sh"
```

该 Maven 命令默认使用标准 JNI。TP 回归必须把测试 classpath 中标准 rocksdbjni 替换为
本次确定 TP JAR，并核对 native 映射，不能只改 provider 环境变量。
本轮真实 TP 测试使用如下 reactor classpath 方法。先按
[开发文档](../../docs/toplingdb/toplingdb-development.md) 配齐 native 系统依赖；此命令不替代镜像打包验收。
输出路径仅属于此次测试，构建必须成功，不使用旧 classpath 或旧 classes。

```bash
mvn compile dependency:build-classpath -pl hugegraph-server/hugegraph-test -am \
  -Dmdep.outputFile=/tmp/topling-closure-tests/reactor-classpath.txt \
  -Dmaven.javadoc.skip=true -ntp
python3 - <<'PYCP'
from pathlib import Path
root = Path.cwd()
base = Path('/tmp/topling-closure-tests')
cp = (base / 'reactor-classpath.txt').read_text().strip().split(':')
standard = [p for p in cp if '/org/rocksdb/rocksdbjni/' in p]
assert len(standard) == 1, standard
tp = root / 'hugegraph-server/hugegraph-dist/src/assembly/static/lib/topling/rocksdbjni-8.10.2-20260725.141011-1.jar'
assert tp.is_file()
cp = [str(tp) if p == standard[0] else p for p in cp]
cp.insert(0, str(root / 'hugegraph-server/hugegraph-test/target/classes'))
(base / 'tp-classpath.txt').write_text(':'.join(cp))
PYCP
TP_JAR="$PWD/hugegraph-server/hugegraph-dist/src/assembly/static/lib/topling/rocksdbjni-8.10.2-20260725.141011-1.jar"
TP_NATIVE_DIR=/tmp/topling-closure-tests/native
mkdir -p "$TP_NATIVE_DIR"
unzip -p "$TP_JAR" librocksdbjni-linux64.so > "$TP_NATIVE_DIR/librocksdbjni-linux64.so"
sha256sum "$TP_JAR" "$TP_NATIVE_DIR/librocksdbjni-linux64.so"
ldd "$TP_NATIVE_DIR/librocksdbjni-linux64.so"
cat > /tmp/topling-closure-tests/ToplingCoreProbe.java <<'JAVA'
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.rocksdb.RocksDB;
public class ToplingCoreProbe {
    public static void main(String[] args) throws Exception {
        Class.forName("org.rocksdb.SidePluginRepo");
        RocksDB.loadLibrary();
        String maps = new String(Files.readAllBytes(Paths.get("/proc/self/maps")));
        String library = Paths.get(System.getenv("LD_PRELOAD")).toRealPath().toString();
        if (!maps.contains(library)) throw new AssertionError("TP native not mapped");
        System.out.println("TP JAR: " + RocksDB.class.getProtectionDomain().getCodeSource().getLocation());
        Class<?> type = Class.forName(args[0]);
        Result result = "all".equals(args[1]) ? new JUnitCore().run(type) :
                        new JUnitCore().run(Request.method(type, args[1]));
        for (Failure failure : result.getFailures()) System.err.println(failure.getTrace());
        System.out.printf("Tests=%d Failures=%d Ignored=%d%n", result.getRunCount(),
                          result.getFailureCount(), result.getIgnoreCount());
        if (!result.wasSuccessful() || result.getRunCount() == 0) System.exit(1);
    }
}
JAVA
TP_TEST_CP=$(cat /tmp/topling-closure-tests/tp-classpath.txt)
for target in 'org.apache.hugegraph.backend.store.rocksdb.RocksDBSnapshotRestoreTest all' \
              'org.apache.hugegraph.unit.rocksdb.RocksDBSessionsTest testAdapterToplingTruncateWithMultipleKeys'; do
  read -r test_class test_method <<< "$target"
  TOPLINGDB_EASY_MIGRATE_CONF="$PWD/hugegraph-server/hugegraph-dist/src/assembly/static/conf/toplingdb.yaml" \
  LD_PRELOAD="$TP_NATIVE_DIR/librocksdbjni-linux64.so" LD_LIBRARY_PATH="$TP_NATIVE_DIR" \
    java -Xmx512m -cp "$TP_TEST_CP" /tmp/topling-closure-tests/ToplingCoreProbe.java \
      "$test_class" "$test_method"
done
```

检查 ldd 不得含 not found；实际 JAR/native hash 必须匹配交付身份，不满足时先修环境。
新补救代码预期 helper 27/0/0、adapter 1/0/0；旧交付 helper 为 24/0/0。服务器需再记录自身产物和结果。

对已构建的 Topling component（Server、PD、Store 分别执行），运行仓库 wrapper：

```bash
COMPONENT_DIR=/absolute/path/to/built/topling-component
bash "$TRAVIS_DIR/run-topling-native-diagnostic.sh" /tmp/topling-native-diagnostic \
  bash "$TRAVIS_DIR/test-rocksdb-runtime.sh" topling "$COMPONENT_DIR"
```

期望 probe exit 0 且 complete；CF lifecycle 若通过则报告 passed，若严格匹配已知 #212 断言且
exit 134 则报告 known-cf-assertion。任何前置、其他错误或混合失败必须 wrapper exit 1。
已知合成断言例外不代表真实服务生命周期关闭告警消失。镜像打包、服务重启、正式发布、性能和必要
多节点验收仍须记录独立实际结果；本轮没有为所有项目强加统一 3+3+3 前提。

正式 Docker/服务矩阵尚未全部通过；`ceea35428` 的旧运行包含失败与待定，不能继承为当前 head 通过。
Server ABI probe 的默认空目录 fixture 已补齐、本机复现验证并推送 `741c64a5c`；
文档交付前的 `741c64a5c` 检查仍有 Commons 失败及相关 job 待定；图片 LF 修复在本次
文档批次发布后才进入远端，需按包含修复的 SHA 再核对，不能把本机核心实测当作矩阵已通过。
hg-pd-test 缺 PDService/IndexAPI 的新增测试属于本 PR，正在以薄 JAR + exec 发行包修复；
PD 修复已有完整 clean install、mock 2 项与实际 Boot health UP；
新 head 的三组件打包/运行与完整 CI 仍需核对，见上方补救接收说明。

## 每次验收的证据

记录 issue、源码 SHA/是否干净、构建输入、镜像 digest 和 revision、实际 JNI 路径及 hash、
配置、拓扑/namespace、命令、退出码、计数与 skip、前后数据断言、未覆盖边界和原始证据位置。
镜像标签、Pod Ready 或重试后成功不能单独代表通过；失败项目保留失败证据和解除条件。
Mac 完成与 Linux 完成分别记录，不用此文件的待验状态阻止独立 Mac 工作。

## 历史结果摘要

- #212 已有 938e4b9c4、7afe25947 生命周期修复，以及旧 SHA 三轮真实验证；不能说从未验证。
- e109012a0 已修复 memtable_as_log_index 配置兼容；a35ebeb17 标准/TP standalone snapshot 成功路径
  曾证明快照前数据保留、快照后消失。它们不证明本轮 #249 的失败安全。
- a35 单机、1+1+1、3+3+3 有 CRUD、重启、JNI、固定子集数据等局部证据；仍有 db not closed 告警。
  PD 曾升级到 327737f16，Server/Store 仍可能是 a35，必须逐组件核对，不能写成同一整体版本。
- clear 后一次首次 Server 重启查询为空沿 #248；旧 Store IP 残余连接沿通用 #245，后续读到数据不证明根因修复。
- HStore snapshot_create 曾返回 500，不等同 standalone snapshot；网络分区未完成，benchmark 未开始。
- 全量 LAW 曾因数据规模和共享验证存储后置；固定子集 1000000 顶点、2098771 边的成功不代表全量或失败重试覆盖。
- 旧 WAL 20 项通过只是未提交版本的局部测试，最新差异未完成审查，不可当作交付依据。

原始证据逻辑位置见 [证据索引](evidence-index.md)；完整提交、镜像身份和时间线见
[历史日志](state-history-20260926.md)，历史勾选及终态见 [历史清单](todo-history-20260926.md)，
旧环境快照见 [历史环境](local-status-history-20260926.md)。仅在调查具体问题时读取。
