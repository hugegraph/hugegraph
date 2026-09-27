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
