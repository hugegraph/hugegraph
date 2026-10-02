# ToplingDB 经验导航

只记录已观察、可复用的结论；经验不管理任务状态，也不覆盖当前用户决定。
按需读取 [Mac 经验](lessons-mac.md) 或 [Linux 经验](lessons-linux.md)。

## 通用经验

| 触发条件 | 做法与边界 | 依据 |
| --- | --- | --- |
| 问题在 TP 实测中出现 | 先核对责任代码和已有 PR；通用问题独立跟进，本分支新增代码的回归仍负责 | [历史归属核对](development-handoff-history-20260926.md#2026-09-26-范围收敛) |
| 引用旧通过结果 | 绑定源码和实际运行产物；标准 provider、局部接口或旧 SHA 不能证明当前 TP 全量通过 | [证据规则](evidence-index.md#证据使用规则) |
| 测试成功但故障路径未审查 | 分开记录测试覆盖与审查门禁，不以测试数量替代恢复安全结论 | [WAL 历史审查](todo-history-20260926.md#历史阻塞修复当前归属以上方范围表为准) |
| 已关闭 native handle 在清理集合中 | 用对象身份移除，不能依赖 native-backed equals；本轮 equals 导致 JVM SIGSEGV，identity 修复后真实生命周期和 Java 断言通过 | [本轮回归](mac.md#最小验证与审查)，hs_err_pid71994.log / smoke-lifecycle-fixed.log |
| Surefire 使用 fork JVM 与自定义 temp 目录 | 临时路径同时传 Maven property 和 argLine，避免 fork 仍共享旧测试目录与 pending marker；只设 MAVEN_OPTS 不够 | [定向命令](mac.md#最小验证与审查)，java-tests-delivery.log |
| Maven compile 通过、package/install 下游找不到同模块类 | 用 clean 重现 artifact 布局；Boot 主 JAR 的 BOOT-INF/classes 不能作普通编译依赖，保留薄主 JAR并单独附 exec，发行包只选 exec | [CI 补救](mac.md#ci-补救与完成状态更正)，clean package 失败/修复及 root clean install |
| 定向测试通过但完整 CI 未结束 | 只记录已交付、待 CI；本 PR 早期新增代码的失败仍由本分支收口，不以是否在最后一次 diff 内判断归属 | [18 个失败 job 的归因](mac.md#ci-补救与完成状态更正) |
| runtime 依赖已移除或换版本 | 同步原 CI generator 的 known 清单，先确认实际 runtime 集合唯一差异；CI 对新增与删除都失败，不为匹配旧清单加入已移除库 | [第二轮 CI 补救](mac.md#第二轮-ci-补救安全启动-fixture-与依赖清单)，inventory-before-sorted.diff |
| provider admission 已创建根目录或 marker | 目录认领与 backend 初始化分开判断；初始化走既有表/CF 检查，不能用目录或 CURRENT 存在跳过。信号测试先验证真实活进程，看门狗超时不能当退出码传播成功 | [完整真实启动回归](mac.md#第三轮-ci-补救真实启动与-backend-初始化) |
| 二进制 JNI 与包仓库校验和相同 | 只证明字节身份；发布前仍核源码及 SidePlugin 固定提交、构建参数/CPU 基线、不可变坐标和许可证。上游 POM、包页与本地 LICENSE 不一致时保留法务/发布门禁 | [本轮 #213 核查](linux.md#2026-09-28-固定源码验收进度)，`118-*` |
| 从 GitHub 源码归档创建构建上下文 | 先检查 export-ignore；归档可能缺 installer 等文件。以 gh 获取不可变完整 tree/blob，并逐文件比 Git blob SHA 和执行权限；字节匹配才复用本地源码，不夹带旧 target 或数据 | [完整源码重建](linux.md#2026-10-02-恢复后的实际执行) |
| 独立 JUnit launcher 返回后 JVM 不退出 | 断言通过与进程退出分开记录；thread dump 若 main 已结束、全局 worker idle，应显式按 API 关闭 TaskManager/EventHub，而不是只关闭单图或把 SIGTERM 143 算通过 | [2026-10-02 定向回归](linux-history-20261002.md#2026-10-02-恢复与发布核对) |
| snapshot 只检查新入口可用性 | 保存的 schema manager/builder 会直接访问旧 tx/cache；用真实复制故障和成功恢复后的读写各自红测。单类 gate/iterator 测试不能替代完整语义操作、后台队列、缓存和 native 生命周期接入 | [2026-10-02 六项红测](linux-history-20261002.md#2026-10-02-恢复与发布核对) |
| CI 失败的 auth 源码与 master 相同 | 还要对比请求清理、事务与 schema 生命周期，并做同配置基线实测；多个 backend 同败只能证明不是某个 native 独有，不能证明本 PR 未放大问题 | [当前归因计划](linux.md#2026-10-03-ci-归因与后续计划) |
| 名称看似表明保留 ID 范围 | 核对实际分配入口及运行对象 ID；primitive() 范围含动态 auth 用户 -27，不能据此排除关联删除。按真实特殊类型隔离 OLAP，并覆盖双向关系及无关对象保留 | [auth 回归](linux.md#2026-10-03-ci-归因与后续计划) |
| JUnit launcher 显示 ignored=0 | 另记录 Result.getAssumptionFailureCount()；wasSuccessful 与 ignored 不能单独证明没有 assumption skip | [最终计数](linux.md#2026-10-03-ci-归因与后续计划) |
