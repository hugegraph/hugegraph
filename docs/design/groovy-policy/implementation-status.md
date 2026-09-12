# Groovy 策略实现与验证记录

验证日期为 2026 年 9 月 13 日。实现基于纯升级分支 `task/tp381-apache-integration-20260907` 的 `cbb1d72e817994b1c74f4097625e2e22dc912d7b`，运行环境为 TinkerPop 3.8.1、Java 17、Groovy 4.0.25。本文记录本目录设计对应实现的功能证据，不代表完整发布验收或独立沙箱的生产安全资格。

默认模式仍为 `legacy`。`combined` 启用新策略并保留部署基线中的 HugeSecurityManager 配置，`policy-only` 启用新策略且要求未安装 SecurityManager，仍属实验模式。本实现保持 Groovy，不切换 GremlinLang，也不自动纳入 1.8 纯升级发布要求。

## 兼容行为

Store 条件继续按标签名和属性名访问元素。`element.property('age')` 在属性缺失时返回 `null`，可用空值判断或 `element.properties().containsKey('age')` 筛选缺失属性；缺失本身不终止扫描。Schema 读取、记录解码和条件求值错误会终止扫描并传播失败，即使此前已经发出部分记录。日期及 Blob 转换为数据值，多值属性中的成员也递归转换；LIST 保留顺序和重复项，SET 提供去重后的值。策略视图不暴露原始元素和可变 Schema 对象。

WebSocket Session 使用独立策略引擎，保留受检数据变量和已有事务执行流程，支持原生事务提交、回滚以及会话脚本中的 `g.tx().commit()` / `rollback()`。会话绑定认证身份和连接，图别名逐请求重新解析。数据和别名仅在请求成功完成后保存；编译、求值、序列化失败或超时不会把本次数据修改留到下一次请求。闭包、遍历、图和事务对象不能作为跨请求状态保存。HTTP 标准 Gremlin endpoint 沿用原有无会话链路的行为。

允许类型的静态 import 和别名调用接受相同的方法签名检查，原生 Cypher 继续经过已有解析与遍历执行链路。`.iterate()`、部分消费的遍历和顶层普通 Iterator 保持剩余结果的逐项输出、检查和关闭语义。

## 构建和功能测试

全部构建和测试在 `10.21.76.114` 的独立目录完成，使用 JDK 17.0.20 和 Maven 3.9.11。最终远程验证所用源码、资源和测试文件与交付工作区一致。

| 验证 | 结果与覆盖范围 |
|---|---|
| 整仓格式 | `mvn editorconfig:format` 通过，未产生额外格式修改 |
| 整仓编译 | `mvn clean compile -Dmaven.javadoc.skip=true` 通过 |
| Server 定向测试 | 51 项，失败 0、错误 0、跳过 0；覆盖编译与方法规则、绑定和结果检查、HTTP / WS / bytecode / Cypher、Session 状态及生命周期、内存与 RocksDB 图操作 |
| Server 最终复核 | 增加超时请求携带数据及别名覆盖的断言后，`PolicyServerModeTest` 4 项再次通过；重复运行不计为新增用例 |
| Store 定向测试 | Maven 10 项，失败 0、错误 0、跳过 0；覆盖真实二进制记录、业务属性名、缺失属性、多值日期 / Blob、扫描错误和客户端取消 |

Server 的 51 项来自 `ScriptPolicyCompilationTest`、`PolicyScriptEngineTest`、`ScriptRequestGuardTest`、`PolicyServerModeTest`、`PolicyGraphModeTest`、`PolicySessionEngineTest` 和 `PolicySessionLifecycleTest`。Store 的 10 项来自 `ScanPolicyFailureTest` 和 `StorePolicyModeTest`。

Session 回归检查了隐式绑定与局部变量的区别、惰性迭代中的数据修改，以及编译、求值、序列化和超时失败后的状态隔离。图测试在内存及 RocksDB 后端验证原生和脚本事务的提交、回滚与可见性。`combined` 图测试在安装 HugeSecurityManager 的独立 JVM 中执行，包含 Schema 和 task-worker 路径；测试先完成对应的服务启动及身份准备。

## 独立审查与证据边界

最终独立审查覆盖基线至当前实现的完整差异，包括新增文件。审查者未使用 skills 或历史审查结论，检查了编译限制、上下文恢复、Session 状态保存与清理、事务、动态图生命周期、模块间调用及测试有效性，没有发现新的可确认代码问题。该结论属于源码审查，不代替运行验证。

| 范围 | 当前证据的限制 |
|---|---|
| 身份与权限 | 网络探针主要采用 AllowAll 与 EmptyGraph；已有身份切换和鉴权图测试不等同于真实网络登录、撤权和多用户会话验收 |
| 分布式后端 | 真实图测试覆盖内存与 RocksDB，Store 测试覆盖序列化及扫描链路；未完成整套 HStore 集成兼容验证 |
| 资源生命周期 | 已有关闭、失败清理和取消回归，不足以证明持续冷编译洪峰、缓存淘汰和类加载器长期稳定性 |
| 独立防护 | `policy-only` 保持实验状态，没有全面替代 HugeSecurityManager 的生产安全结论 |
| 性能 | 本轮按要求暂停，未运行 benchmark，不依据历史 Java 11 / 17 数据推断本方案收益 |
