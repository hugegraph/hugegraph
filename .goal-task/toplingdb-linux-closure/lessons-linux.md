# Linux 验收经验

[通用经验](lessons.md) · [验收入口](linux.md)

| 触发条件 | 做法与边界 | 依据 |
| --- | --- | --- |
| 单节点 kind 的 Pod 恢复与 Ready | 核对已确认数据和实际拓扑；不得表述为物理多机或网络分区容错 | [历史终态](todo-history-20260926.md#终态但未勾选) |
| PD 升级但 Server/Store 保持旧镜像 | 逐组件记录 revision、digest 和 JNI；标签或某个组件升级不能代表全栈源码一致 | [PD 327737f16 历史记录](state-history-20260926.md) |
| Store IP 变化后请求最终恢复 | 同时保留旧 IP 重连失败与后来成功；成功本身不能证明原因已修复或必须靠重启 | [Loader 历史记录](todo-history-20260926.md#p6-loader) |
| 停机仍有 db not closed | 区分正常关库证据、残余 native 告警与卡住 worker 超时；不强制关库制造通过 | [关闭边界证据](todo-history-20260926.md#终态但未勾选) |
| 为恢复协议检测单 DB bind mount | 相同文件系统 bind 的 FileStore 可以与父目录相等；读取当前 process mountinfo 并处理路径转义。挂载父数据根，拒绝单 DB mount，旁侧锁才协调。本轮真实启动中目标 g 未开库，但并行 m/s 已部分初始化，不能称全图原子拒绝 | [Mac 容器实际验证](mac.md#最小验证与审查)，[本轮 Linux](linux.md#2026-09-28-固定源码验收进度) `77-*` / `78-*` |
| 单 DB 挂载需要启动前全图拒绝 | Docker 必须在认领 provider 根和建立状态目录前检查最终图配置的所有本地 m/g/s；直接启动与 init-store 也要使用同一预检。本修复要求 util-linux `mountpoint` 2.37+，并以非挂载退出码 32 为门禁；BusyBox 和更旧版本明确拒绝，纯 HStore 不要求该命令。预检后的外部挂载变更仍是竞态边界 | [隔离修复与真实 Docker 证据](linux.md#2026-09-28-固定源码验收进度) `98-*` / `99-*` / `100-*` |
| Topling memtable 稀疏文件使全量哈希异常缓慢 | 同时记录逻辑长度与已分配块；失败前后先比文件清单、大小、块数、mtime，内容哈希限定合理逻辑大小，并明确大稀疏文件未逐字节覆盖 | [本轮验收](linux.md#2026-09-28-固定源码验收进度)，`24-*` / `25-*` |
| Docker 随机发布端口在 `docker start` 后变化 | 每次重启后重新读取 `docker port`；自定义 REST 配置若仍绑定容器内 127.0.0.1，宿主机映射端口不可访问 | [本轮自定义图](linux.md#2026-09-28-固定源码验收进度)，`29-*` / `32-*` |
| 服务 snapshot 的 checkpoint 缺失 MANIFEST | 校验在 pending marker/数据安装前拒绝，本轮标准/TP REST 均返回 400；失败的当前进程可能已关 native handle，不在其中重试。停机后按哈希恢复源文件并重启，先证原数据仍可见，再 resume、再重启验证快照后数据消失；通用 smoke 若默认计数固定，两次数据断言不能混用 | [本轮服务失败恢复](linux.md#2026-09-28-固定源码验收进度)，`108-*` 至 `111-*`、`116-*` |
| 服务 WAL staging 复制遇 ENOSPC | 数据与 checkpoint 留在同一专属磁盘根，仅给 WAL 根有限 tmpfs；加入数字 `.log` 后内层堆栈定位 staging copy。失败后确认 pending/checkpoint，再只移除测试加的日志，保留 marker/锁并由新进程恢复。按 m/g/s 分别核对：单库 pending 消失和基线 CRUD 通过不证明全图 resume，剩余 checkpoint 必须记作门禁 | [本轮服务 WAL 复制故障](linux.md#2026-09-28-固定源码验收进度)，`123-*` 至 `130-*` |
| provider marker 已存在、Pod Ready 或 `/versions` HTTP 200 | 继续由 init-store 和真实数据/CF 文件、图 API 确认 backend 初始化；预认领空父根只解决目录安全门禁，不算图打开成功。故障重开时本轮 `/versions` 200、图 API 404 | [本轮自定义图](linux.md#2026-09-28-固定源码验收进度)，`28-*` 至 `30-*`；[WAL 故障重开](linux.md#2026-09-28-固定源码验收进度)，`124-*` |
| 标准 JNI 停机 exit 0，但 TP 报 `db not closed` | 同负载打开 BackendSessionPool DEBUG，对照最后的 sessionCount 和 DB close 路径；标准无断言不证明所有 Java session 已释放，也不据此强制关库 | [本轮最小对照](linux.md#2026-09-28-固定源码验收进度)，`71-*` / `76-*` |
| REST 响应后释放线程事务 | 在当前同步 Jersey 路径用 `FINISHED` 等响应写出/关闭后清理；新 session 登记与末次 native close 需同锁防竞态。先用旧实现红灯/新实现绿灯验证竞态，再用真实 TP CRUD、并发读、首次重启与两次 SIGTERM 验证；异步、流式请求或停机时接纳排空仍须独立验收 | [本轮 #212 证据](linux.md#2026-09-28-固定源码验收进度)，`226-*` 至 `242-*` |
| 真实 TP 整类测试遇合成 CF native abort | 保留 exit 134 和原日志；abort 后临时数据目录可能留下不可复用的 CF 状态，随后单测的失败不能当成候选回归。换任务专属空目录重跑单测，并分别记录该次通过与前次整类失败 | [本轮 JNI 对照](linux.md#2026-09-28-固定源码验收进度)，`231-pool-tp/*` |
| checkpoint 数据树注入失败 | 容器 root 新建子目录会把宿主用户的继承 ACL 有效写权限收紧；只对任务专属根使用一次性辅助容器注入，保留宿主首次 Permission denied。无效链接使恢复写 pending 后返回包装错误，移除链接后应比较原文件哈希及 marker 字节再正常重开；没有内层栈时只称复制/校验阶段，且单库 pending 消失不证明全图回滚 | [本轮标准/TP #249 实验](linux.md#2026-09-28-固定源码验收进度)，`243-*` 至 `246-*`、`250-*` 至 `251-*` |
| HStore Server 绑定网络别名 | 容器内置 healthcheck 若访问 localhost 而 REST 只绑定 `server:8080` 会误判 unhealthy；测试容器显式对准实际内部绑定地址，仍以图数据请求确认业务。clear API 必须带精确确认消息；首次重启后先保存按 ID 的第一笔外部图 GET，再单独跑完整 verify | [本轮 #248 第三次时序](linux.md#2026-09-28-固定源码验收进度)，`247-*` 至 `249-*` |
| 两个进程同时打开同一 RocksDB 根 | 对照标准/TP 的实际 JNI 映射、锁异常、首进程数据与 CURRENT/MANIFEST 哈希、锁文件及停机重开；第二进程即使被锁拒绝，也可能维持 `/versions` 200/healthy，必须用图 API 和 init-store 实际 CF 判断 backend 是否可用。第二进程不自行退出不等于锁保护失败；不要把同时开库与同时恢复 pending 混为同一门禁 | [本轮 #249 进程锁](linux.md#2026-09-28-固定源码验收进度)，`254-*` 至 `256-*` |
| 长 Gremlin 请求与 SIGTERM 重叠 | 分别断言客户端 HTTP 结果、服务退出码、native 关闭日志及同根重启数据；服务 exit 0 不说明在途查询成功。Gremlin 响应可能 gzip 压缩，测试解析前显式解压；本轮长请求收到代理 `NoHttpResponseException`/HTTP 500。停机调用顺序与 REST 销毁共享图的时机要一并处理，不能只交换 Gremlin/REST 的 stop 顺序 | [本轮 #212 长请求](linux.md#2026-09-28-固定源码验收进度)，`260-*`、`261-*` |
| TP JNI 预加载后再次 loadLibrary | LD_PRELOAD 与 java.library.path 必须指向同一已核验 native，核对实际映射；搜索路径缺失可能使 fallback 提取另一副本并产生重复 factory abort。不能归为已知合成 CF 例外 | [2026-10-02 启动错误](linux-history-20261002.md#2026-10-02-恢复与发布核对) |
| 容器缩减 CPU 后并发 GET 返回 503 | 记录有效 worker 阈值与 CPU 配额。默认 REST worker 为 2×CPU，本轮 4 CPU 的阈值 8 拒绝 3/20；保留原失败，同根在较大配额下复核，不把新配置成功覆盖原结果或当成性能对照 | [2026-10-02 同根服务](linux-history-20261002.md#2026-10-02-恢复与发布核对) |
| checkpoint 默认没有 WAL，需触达最终发布分支 | 仅使用本库快照前稳定采样的真实 WAL 副本，保留 SHA、CRC32C 与 WriteBatch 头核验；明确这是故障 fixture，不声称默认 checkpoint 自然包含 WAL tail，也不等同完整 native dump | [最新发布故障实测](linux.md#2026-10-02-恢复后的实际执行) |
| 独立恢复竞争 JVM 声称加载 TP | 同时核对唯一 JNI Java JAR 的 CodeSource、native 映射/哈希与 adapter 来源；标准 Java JAR 加 TP native 不算完整 TP 身份。使用全新数据根及测试控制文件，比对数据 typed tree、pending、checkpoint 和锁 dev/inode/字节；只解除人工 hold，不删恢复 marker/锁 | [pending 并发实测](linux.md#2026-10-02-恢复后的实际执行) |
