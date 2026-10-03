# ToplingDB 操作经验

按当前操作选择分节，不默认全部加载。任务状态见 [state.md](state.md)，实测证据见对应环境记录。
此处只保留可复用做法；具体实验时序和历史参数不重复搬入。

## 通用：构建、归因与身份

- 标准和 TP 同败不能证明问题早已存在。对比引入前源码、同配置服务及触发它的共用代码；auth 回归就是请求清理使共享 schema cache 变冷后暴露的漏删。
- 实际 JNI 身份必须同时核对唯一 Java JAR 的 CodeSource、native maps/hash 及 adapter 来源。标签、环境变量和标准 JNI 下的 TP 分支测试都不能替代它。
- 用干净完整源码构建。GitHub archive 可能因 export-ignore 缺少 install-dist；不复用旧 target/classpath。逐组件记录源码、参数、包/JAR 和实际镜像身份。
- Maven 与 Surefire fork JVM 同时传专属 java.io.tmpdir；仅设置 MAVEN_OPTS 不够。单列 assumption skip、ignored、断言结果和 JVM 退出码。
- compile 成功不证明 package/install 可用。Boot 可执行 JAR 不能直接作普通模块依赖：薄主 JAR 与 exec 附件分开，发行包选正确附件。
- runtime 依赖更新要同步 known-dependencies、LICENSE/NOTICE；不要为迎合旧清单重新加入已移除的库。
- 已关闭 native handle 从集合移除时按对象身份判断，不能调用 native-backed equals。独立 launcher 应正常关闭 TaskManager/EventHub；超时杀进程不算通过。
- native JAR 校验和相同只证明字节身份；来源、固定依赖、CPU/ABI 基线和许可证是独立核查项。

依据：[Linux 身份与验证](linux.md)、[Mac 构建与 native 回归](mac.md)。

## 通用：数据与生命周期

- provider marker、目录或 CURRENT 存在不等于 backend 已初始化；由 init-store 检查真实表/CF，再执行图 API。
- snapshot 验证需覆盖数据内容、失败重开和 pending 恢复并发。保存的 schema manager/builder 可绕过新入口，局部 gate 测试不证明完整缓存/全图协议安全。
- 故障后保留 checkpoint、pending 与锁。只解除测试人工 hold 或注入物，并核对数据/marker/锁身份；不能删除恢复材料强行启动。
- checkpoint 默认可能不含 WAL。故障 fixture 要使用同库真实 WAL，记录采样身份及校验，不宣称默认 checkpoint 有 WAL tail。
- 同时开库锁与同时恢复 pending 是两个场景；进程被拒绝后仍可能 versions 200，必须检查实际数据库与图 API。
- REST FINISHED 清理须配套 session 登记/末次关闭并发保护、auth 关联删除和冷缓存回归；同步请求通过不能外推异步、流式及停机排空。
- system/primitive 的名称不代表真实标签范围。auth 用户可为 -27；级联删除只排除已证实的特殊 OLAP 类型，并验证无关关系保留。
- 首次失败保留，重试成功不能覆盖它；正常退出、native 关闭、在途请求结果和重启后数据分别断言。

依据：[Linux 故障证据](linux.md#真实服务与故障证据)、[auth 归因](linux.md#2026-10-03-ci-归因与后续计划)。

## Linux / 容器

- 启动 suite 会清进程、端口和 cron，只能运行在专属容器。单宿主 kind/多进程不能证明物理多机 HA；Ready 不能证明已确认数据可见。
- 单 DB bind mount 不能只用 FileStore 判定。读取 mountinfo 并处理转义；Docker/direct/init 都应在认领根目录前检查最终所有图的 data/WAL 根。当前预检依赖 util-linux mountpoint 2.37+，非挂载码为 32；纯 HStore 不要求它。预检后外部改变挂载仍是竞态边界。
- TP 稀疏文件需区分逻辑长度与实际块数；避免盲目全量哈希，并注明未逐字节覆盖范围。root 容器的 ACL/属主变化可能让宿主故障注入失效。
- 容器重启后重新读取随机发布端口。REST 若只绑定容器内回环或网络别名，宿主访问/默认 healthcheck 可能不适用；按实际地址验证。
- TP 的 LD_PRELOAD 和 java.library.path 必须指向同一已核验 native，避免 fallback 再加载一份触发 factory abort。只有精确已知合成 CF 断言允许例外，其他 abort 必须失败；abort 后用新临时根复测。
- 容器 CPU 配额影响 REST worker/503；记录有效阈值，改大资源后的成功不能覆盖原失败或作为性能对照。
- snapshot 缺 MANIFEST、WAL ENOSPC、发布失败应分别触达目标阶段并保留内层错误；单库恢复成功不能写成整图恢复成功。
- 长 Gremlin 响应可能 gzip；先正确解压，再分别核对 HTTP、停机和重启。Store 地址变化后最终重连成功不能证明旧故障已修复。

依据：[Linux 验收记录](linux.md)。原实验细节按本页下方历史链接取用。

## Mac

- Docker CLI/socket 挂起但 OrbStack 正常时，可对单条命令移除大小写 HTTP_PROXY/HTTPS_PROXY/ALL_PROXY，curl 使用 --noproxy '*'；先检查本地 API，不改全局代理。
- Mac amd64 容器的 JNI 结果须注明模拟架构及资源限制；不能外推性能或 Linux 服务器部署结论。
- 假 runtime/marker spy 只证明调用路径。fixture 必须满足所有前置配置，只在目标阶段注入失败，并断言实际 bootstrap；非零退出本身不足。
- Maven 未进入编译/测试时，先核 reactor 与父坐标，不能报告回归通过。

依据：[Mac 开发与实测](mac.md)。

## 需要追溯实验时

[精简前经验及证据索引](https://github.com/hugegraph/hugegraph/tree/4720da91b5a3c99b426c2ac035533ebc84d4e4cb/.goal-task/toplingdb-linux-closure)
保留原始触发条件、日志编号与旧失败。按具体问题取用，不作为默认上下文。
