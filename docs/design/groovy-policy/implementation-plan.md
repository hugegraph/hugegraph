# Groovy 策略部署与验证手册

本实现基于纯升级分支 `task/tp381-apache-integration-20260907`，使用 TinkerPop 3.8.1、Java 17 和 Groovy 4.0.25。设计及兼容差异见同目录的设计文档，验证结果见验证记录。

## 1. 配置模式

Server 和 Store 都通过 JVM 系统属性读取模式。默认值是 `legacy`。

```bash
# 在对应进程的启动环境中设置，保留已有 JVM 参数
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dhugegraph.script.security.mode=combined"
```

实验性的独立策略模式使用 `-Dhugegraph.script.security.mode=policy-only`。部署者需要重启对应进程，并检查启动日志中的模式、策略版本和 SecurityManager 实际状态。已有 JVM 参数中不要同时保留多个模式值。

新模式接受标准 WsAndHttpChannelizer 和单一 gremlin-groovy 引擎。已有认证配置继续生效。WebSocket Session 使用每会话独立的受限引擎，保留允许的数据变量和原事务流程。脚本 bytecode lambda 和未批准的 source options 会被拒绝；Session 原生提交、回滚指令，以及会话脚本中的 `g.tx().commit()` / `rollback()` 调用保留；文本 Groovy 的选定闭包仍可使用。

## 2. 脚本示例

QUERY 支持参数化查询及经过检查的业务闭包。

```groovy
g.V().hasLabel('person').has('name', name).valueMap().toList()
```

```groovy
List<Integer> values = []
for (int i = 0; i < 3; i++) {
    values.add(i)
}
values.collect { it + 1 }.sum()
```

Schema 初始化由服务端选择 SCHEMA profile。

```groovy
schema.propertyKey('name').asText().ifNotExist().create()
schema.vertexLabel('person').properties('name').useCustomizeStringId().ifNotExist().create()
```

Store 条件按标签名和属性名读取当前元素，并返回 Boolean。属性缺失时返回 null，可以先判断是否存在。

```groovy
element.label().equals('person') && element.property('age') != null &&
        (int) element.property('age') > 18
```

以下脚本会被新策略拒绝。

```groovy
System.getProperty('java.version')
new File('/tmp/data')
'x'.execute()
Class.forName('java.lang.Runtime')
this.binding
'x'.metaClass
```

## 3. 验证命令

构建和测试在远程独立目录运行。测试节点为 `10.21.76.114`，不在资源不足的本机执行 Maven。需要完整的 JDK 17，包含 `JAVA_HOME/conf/security/java.security`。

```bash
mvn editorconfig:format
mvn clean compile -Dmaven.javadoc.skip=true
mvn test -pl hugegraph-server/hugegraph-test -am -P unit-test
mvn test -pl hugegraph-store/hg-store-test -am -P store-core-test
```

Server 新增测试位于 UnitTestSuite，Store 新增测试位于 CoreSuiteTest。三种 Server 模式使用独立 JVM，防止系统属性、SecurityManager 和引擎缓存相互影响。

只运行本次相关测试时可使用以下命令。

```bash
mvn test -pl hugegraph-server/hugegraph-test -am -P unit-test \
  -Dtest=ScriptPolicyCompilationTest,PolicyScriptEngineTest,ScriptRequestGuardTest,PolicyServerModeTest,PolicyGraphModeTest,PolicySessionEngineTest,PolicySessionLifecycleTest \
  -Dsurefire.failIfNoSpecifiedTests=false

mvn test -pl hugegraph-store/hg-store-test -am -P store-core-test \
  -Dtest=ScanPolicyFailureTest,StorePolicyModeTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Store 显式选择 `store-core-test`，避免默认启用的多个 profile 重复执行同一组 `-Dtest` 用例。

已有兼容回归使用原 profile。

```bash
mvn test -pl hugegraph-server/hugegraph-test -am -P core-test,rocksdb
mvn test -pl hugegraph-server/hugegraph-test -am -P api-test,rocksdb
mvn test -pl hugegraph-server/hugegraph-test -am -P tinkerpop-structure-test,memory
mvn test -pl hugegraph-server/hugegraph-test -am -P tinkerpop-process-test,memory
```

## 4. 性能对照

手动入口为 `org.apache.hugegraph.benchmark.ScriptPolicyBenchmark`，位于测试模块，不自动加入 CI。每组使用独立 JVM，相同 classpath、堆配置和数据，按交替顺序运行。

| 代号 | 引擎 | SecurityManager | JVM 模式 |
|---|---|---|---|
| A | 原引擎 | 未安装 | legacy |
| B | 原引擎 | 已安装 | legacy |
| C | 新策略引擎 | 已安装 | combined |
| E | 新策略引擎 | 未安装 | policy-only |

微基准覆盖预热后的闭包查询、预编译 Store 条件和冷编译。每轮输出样本数、P50 / P95 / P99、吞吐及完成标记。只有进程退出成功且出现 `BENCHMARK_COMPLETE` 的轮次才计入结果。

这组测试不包含真实存储、网络、身份认证及 Store 元素视图转换。Store 在真实部署中是否安装 SecurityManager，仍以该进程启动状态为准。微基准中的 B / C 安装状态只用于成本对照。

## 5. 策略维护与排查

方法签名资源随代码发布。修改允许方法集合时，资源清单和拒绝测试一起更新，启动校验会拒绝未同步的依赖签名。策略不能通过请求、脚本或远程配置临时放宽。

JMX 中的 ScriptPolicy 对象提供每个 profile 的活动引擎数和统计。静态编译或规则检查失败时返回 `SCRIPT_COMPILE_DENIED`。编译队列已满和等待超时分别返回 `SCRIPT_COMPILE_OVERLOADED` 与 `SCRIPT_COMPILE_TIMEOUT`。`SCRIPT_EXECUTION_FAILED` 表示求值失败，具体图权限和业务错误仍需结合原入口的请求记录排查。

回滚通过修改 JVM 模式并重启完成，不需要转换存储数据。切回 `legacy` 会撤销新增规则；单次请求失败不会自动触发回滚。
