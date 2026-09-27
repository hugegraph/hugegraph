# Linux 验收与恢复入口

## 2026-09-28 固定源码验收进度

本轮冻结源码为 `9d797c7608e244f03436ce11294d9bd72aba4d2d`。用户确认其 38 项 CI
全部成功；下方“新 head CI 待验”是先前提交时的记录，不是本轮 CI 结论，也不代表服务器验收通过。
原始证据目录：`/home/soc-baidu/.codex/validation-runtime/toplingdb-linux-closure/accept-9d797c7-20260928/evidence/`。

- 主机 `soc-baidu-System-Product-Name`，Linux x86_64，i9-13900KS、32 逻辑 CPU、123 GiB 内存，
  根盘约 1 TiB 可用；`/tmp` 为 tmpfs。Java 11.0.32.1、Maven 3.9.12、Docker 29.1.3。
- 主 checkout `/home/soc-baidu/github/hugegraph` 为干净的 `toplingdb` 分支，HEAD 即冻结 SHA。
  `org` 为 `https://github.com/hugegraph/hugegraph.git`；`git fetch org toplingdb --prune` 后
  `FETCH_HEAD` 为同一 SHA，ahead/behind `0/0`。历史 `f29e` 工作区在 `6790d53bf` 且含
  WAL/channel 等未提交内容，本轮未改动或夹带。
- TP JAR SHA-256 `86eb1bd3d9f84ef0dddd3fe95c640a6f145ca2d5a26a5ba628f1f298a2031fae`；
  解包 native SHA-256 `c25ff6e676290db6db47df0954640aa609c391450ec90e1a8eec1f87e174dd38`。
  native 为 ELF64 x86_64，`ldd` 无缺失库；运行时映射和 CPU 指令兼容仍待实际 probe。
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
  `CXXABI_1.3.13`，CPU 含 AVX2、BMI1、BMI2，满足已记录的此 native 最低 ABI/Haswell 指令要求。
  [#213](https://github.com/hugegraph/hugegraph/issues/213) 仍为 open；当前上游
  [JNI workflow](https://github.com/hugegraph/toplingdb/blob/memtable_as_log_index/.github/workflows/topling-jni.yml)
  使用 Ubuntu 24.04/GCC 13 并可发布到 GitHub Packages，尚未由本轮证明源码/子模块锁定、
  不可变坐标、签名、许可证选择和正式 HugeGraph 发行链。此项保持发布前门禁，不自行发布。

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
映射本轮尚未独立证实，保留门禁；不把 classpath、环境变量、镜像标签当作映射证据。

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
旧线索仍未定位。停掉原 PD leader pd0 后，第一次边查询 HTTP 500、`InterruptedException`，
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
它证明镜像中可真实装入并使用 TP JNI，仍不等于此前 PD/Store 长驻服务 PID 的 maps 已读到。

下一步：推送本轮证据文档并更新关联 issue（当前 GitHub 认证失效）；后续收口 #212 停机断言、
单 Store 查询连续性、snapshot resume 即时可见性、单 DB mount 全图预检边界，以及 PD/Store
长驻服务 native 映射门禁后，再决定 #252 固定 workload 的至少三轮对照。
性能仍依赖相关正确性与资源门禁。每项按本轮实际命令、计数和数据断言继续更新。

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
| #250/#251/#253 | 直接及容器启动，默认/自定义目录和额外图；实际 JNI 与 Java provider 一致；冲突在数据库打开前失败，原数据不变 | 实测局部通过；单 DB mount 全图预检与 PD/Store 长驻映射待证 |
| #254 | 用真实 TP JNI 经 adapter 执行多 key truncate，旧数据全空、CF 保留、可重新读写并关闭；标准 provider 对照自身预期分支 | 本轮通过：TP 1/0/0，标准对照通过 |
| #255 | 真正运行 runtime diagnostic，检查前置探测、错误分类、原始日志和 JNI 身份；仅已知断言得到例外，其他错误阻塞 | probe 通过；合成 CF 精确断言例外，真实关闭失败 |
| #249 | 标准/TP 确定提交分别验证 snapshot 成功与故障恢复，包含独立/嵌套 WAL、失败后重启及源文件校验 | helper 与重启后持久回滚通过；同进程缓存失败，服务故障注入待验 |
| #212 | 核对真实 DB/CF 和服务生命周期的残余关闭告警，区分已知合成断言、正常关库和卡住 worker | TP standalone 真实复现，归因待继续 |
| #248 | 复查 clear 后首次 Server 重启丢可见性的单次线索，固定确认写入及查询证据；第二次成功不覆盖第一次异常 | 两种拓扑各一次未复现，旧线索未关闭 |
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
