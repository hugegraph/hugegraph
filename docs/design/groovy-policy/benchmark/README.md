# 性能实验材料

本目录记录引擎提交 `2e72e539` 与最终网络提交 `ba6c32e0` 的远程实验。两者间引擎微基准涉及的代码未变。网络探针只用于手动测量，不加入 Server 测试套件或发行包。

`engine-rounds.json` 与 `network-rounds.json` 保存逐轮测量。`run` 是实际执行顺序，`variant` 为 A / B / C / E，延迟字段单位是微秒，`ops_s` 是每秒完成数。

运行前先通过 Maven 构建 Server 测试模块及其依赖，取得该测试进程实际使用的 classpath，其中本项目模块应优先使用当前产物。网络探针编译到源码根目录内的独立目录；从源码根目录启动，使 HugeSecurityManager 的已有相对路径规则与验证环境一致。

```bash
javac -cp "$(cat server-probe-classpath.txt)" \
  -d target/policy-network-benchmark \
  docs/design/groovy-policy/benchmark/PolicyNetworkBenchmark.java

java -Xms1024m -Xmx1024m -XX:ActiveProcessorCount=2 \
  @hugegraph-server/hugegraph-dist/src/assembly/static/bin/jvm-module.options \
  -Dhugegraph.script.security.mode=combined \
  -cp "target/policy-network-benchmark:$(cat server-probe-classpath.txt)" \
  org.apache.hugegraph.benchmark.PolicyNetworkBenchmark C
```

引擎微基准使用相同依赖 classpath，主类为 `org.apache.hugegraph.benchmark.ScriptPolicyBenchmark`，堆改为 512 MiB。A / B 对应 `legacy`，C 对应 `combined`，E 对应 `policy-only`。探针根据代号安装或不安装 HugeSecurityManager，并检查实际状态。

每轮使用独立 JVM，顺序为 `A B C E / E C B A / A B C E / E C B A / A B C E`。只有进程退出码为零、出现对应完成标记、全部结果校验通过且输出完整测量行，才计入有效结果。不要与编译、功能测试或另一组 benchmark 同时运行。

具体数据和适用范围见[性能实验记录](../performance.md)。
