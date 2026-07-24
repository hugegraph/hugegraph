# HugeGraph 集成 ToplingDB 与 HStore 技术文档

## 一、整体架构概览

HugeGraph 的存储层采用**可插拔后端架构（Pluggable Backend Architecture）**，通过 `BackendStoreProvider` SPI 支持多种存储引擎。当前支持的后端类型为：`memory`、`rocksdb`、`hbase`、`hstore`。

```
┌─────────────────────────────────────────────────────────┐
│                   HugeGraph Server                        │
│                                                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │         BackendProviderFactory                    │    │
│  │  (根据配置选择: rocksdb / hstore / hbase / memory) │    │
│  └──────────┬───────────────────────┬───────────────┘    │
│             │                       │                     │
│   ┌─────────▼──────────┐  ┌────────▼──────────┐         │
│   │ RocksDBStoreProvider│  │  HstoreProvider   │         │
│   │   (type="rocksdb")  │  │  (type="hstore")  │         │
│   └─────────┬──────────┘  └────────┬──────────┘         │
│             │                       │                     │
│   ┌─────────▼──────────┐  ┌────────▼──────────┐         │
│   │ RocksDBStdSessions  │  │HstoreSessionsImpl │         │
│   └─────────┬──────────┘  └────────┬──────────┘         │
│             │                       │                     │
│   ┌─────────▼──────────┐  ┌────────▼──────────┐         │
│   │RocksDBProviderLoader│  │ HgStoreClient     │         │
│   │ (SPI: Standard /    │  │ (gRPC → Store节点) │         │
│   │  ToplingDB)          │  └────────┬──────────┘         │
│   └─────────┬──────────┘           │                     │
└─────────────┼───────────────────────┼─────────────────────┘
              │                       │
    ┌─────────▼──────────┐  ┌────────▼──────────────────┐
    │ RocksDB / ToplingDB │  │  hugegraph-store 集群     │
    │    (本地嵌入式)      │  │  (Raft + PD + RocksDB/   │
    │                     │  │   ToplingDB 节点)          │
    └─────────────────────┘  └──────────────────────────┘
```

**关键设计点**：ToplingDB 和 HStore 是**正交的两个维度**：

- **ToplingDB** = 增强版 RocksDB 存储引擎（可替换标准 RocksDB）
- **HStore** = 分布式存储架构（客户端-服务端 + Raft 共识）
- 两者可以组合使用：HStore 节点内部也能使用 ToplingDB

---

## 二、ToplingDB 接入详解

### 2.1 设计思路：SPI + 反射，零硬依赖

ToplingDB 的集成采用了 **Java SPI（Service Provider Interface） + 反射** 的方式，核心原则是：

1. **编译时不依赖 ToplingDB 特有 API** —— 通过 `Class.forName("org.rocksdb.SidePluginRepo")` 探测
2. **运行时自动选择最优 Provider** —— 优先级机制（ToplingDB=200 > Standard=100）
3. **完全向后兼容** —— 没有 ToplingDB JAR 时自动降级为标准 RocksDB

### 2.2 模块结构

```
hugegraph-rocksdb-provider/              ← 独立 Maven 模块
├── pom.xml                              ← 依赖 rocksdbjni:8.10.2-SNAPSHOT (ToplingDB 增强版)
└── src/main/java/org/apache/hugegraph/rocksdb/provider/
    ├── RocksDBProvider.java             ← SPI 接口定义
    ├── AbstractRocksDBProvider.java     ← 模板方法基类
    ├── StandardRocksDBProvider.java     ← 标准 RocksDB 实现 (priority=100)
    ├── ToplingRocksDBProvider.java      ← ToplingDB 实现 (priority=200)
    └── RocksDBProviderLoader.java       ← ServiceLoader 加载器 (单例)
```

SPI 注册文件位于 `META-INF/services/org.apache.hugegraph.rocksdb.provider.RocksDBProvider`：

```
org.apache.hugegraph.rocksdb.provider.StandardRocksDBProvider
org.apache.hugegraph.rocksdb.provider.ToplingRocksDBProvider
```

### 2.3 核心接口 RocksDBProvider

> 源码位置：`hugegraph-rocksdb-provider/src/main/java/org/apache/hugegraph/rocksdb/provider/RocksDBProvider.java`

```java
public interface RocksDBProvider {
    String getProviderName();       // "standard" 或 "topling"
    int getPriority();              // 数值越大优先级越高
    boolean isAvailable();          // 运行时环境检测

    // 核心 open 方法 —— 1:1 替换 RocksDB.open()
    RocksDB openRocksDB(Options options, String dataPath) throws RocksDBException;

    RocksDB openRocksDB(Options options, String dataPath,
                        String optionPath, Boolean openHttp) throws RocksDBException;

    RocksDB openRocksDB(DBOptions dbOptions, String dataPath,
                        List<ColumnFamilyDescriptor> cfDescriptors,
                        List<ColumnFamilyHandle> cfHandles,
                        String optionPath, Boolean openHttp) throws RocksDBException;

    // 核心 close 方法
    void closeRocksDB(RocksDB rocksDB);
}
```

### 2.4 ToplingDB Provider 的工作原理

> 源码位置：`hugegraph-rocksdb-provider/src/main/java/org/apache/hugegraph/rocksdb/provider/ToplingRocksDBProvider.java`

#### Step 1: 可用性检测

```java
@Override
public boolean isAvailable() {
    try {
        Class.forName("org.rocksdb.SidePluginRepo");  // ToplingDB 特有类
        return true;
    } catch (ClassNotFoundException e) {
        return false;
    }
}
```

#### Step 2: 反射初始化 SidePluginRepo

```java
private Object initializeToplingRepo(Object options, String dataPath, String optionPath) {
    // 动态加载 SidePluginRepo 类
    Class<?> sidePluginRepoClass = Class.forName("org.rocksdb.SidePluginRepo");
    Object repo = sidePluginRepoClass.getConstructor().newInstance();

    // 将 Options 注入 repo
    String dbName = getDbName(dataPath);
    Method putMethod = sidePluginRepoClass.getMethod("put", String.class, Options.class);
    putMethod.invoke(repo, dbName, options);

    // 加载 YAML 配置文件
    Method importAutoFileMethod = sidePluginRepoClass.getMethod("importAutoFile", String.class);
    importAutoFileMethod.invoke(repo, optionPath);

    return repo;
}
```

#### Step 3: 通过 SidePluginRepo 打开 DB

```java
Method openDBMethod = sidePluginRepoClass.getMethod("openDB", String.class);
Object result = openDBMethod.invoke(repo, converseOptionsToJsonString(dataPath, null));
```

传递给 `openDB` 的 JSON 格式：

```json
{
  "method": "DB::Open",
  "params": {
    "db_options": "$dbo",
    "cf_options": "$default",
    "column_families": { "default": "$default" },
    "path": "/data/hugegraph/graph"
  }
}
```

#### Step 4: 启动 HTTP 监控服务器（可选）

```java
if (Boolean.TRUE.equals(openHttp)) {
    Method openHttpMethod = sidePluginRepoClass.getMethod("startHttpServer");
    openHttpMethod.invoke(repo);
}
```

#### Step 5: 关闭时清理 SidePluginRepo

```java
@Override
protected void performProviderSpecificClose(RocksDB rocksDB) {
    Object repo = rocksDBToRepoMap.remove(rocksDB);
    if (repo != null) {
        Method closeAllDBMethod = repo.getClass().getMethod("closeAllDB");
        closeAllDBMethod.invoke(repo);
    }
}
```

### 2.5 Provider 加载器

> 源码位置：`hugegraph-rocksdb-provider/src/main/java/org/apache/hugegraph/rocksdb/provider/RocksDBProviderLoader.java`

```java
public class RocksDBProviderLoader {
    private static final RocksDBProviderLoader INSTANCE = new RocksDBProviderLoader();

    public synchronized void loadProviders() {
        ServiceLoader<RocksDBProvider> serviceLoader = ServiceLoader.load(RocksDBProvider.class);
        for (RocksDBProvider provider : serviceLoader) {
            if (provider.isAvailable()) {
                providerCache.put(provider.getProviderName(), provider);
            }
        }
    }

    public RocksDBProvider getBestProvider() {
        // 选择 priority 最高的可用 Provider
        RocksDBProvider bestProvider = null;
        int highestPriority = Integer.MIN_VALUE;
        for (RocksDBProvider provider : providerCache.values()) {
            if (provider.isAvailable() && provider.getPriority() > highestPriority) {
                bestProvider = provider;
                highestPriority = provider.getPriority();
            }
        }
        return bestProvider;
    }

    // 静态便捷方法，供消费方直接调用
    public static RocksDB openRocksDB(Options options, String dataPath,
                                      String optionPath, Boolean openHttp) {
        RocksDBProvider provider = getInstance().getBestProvider();
        return provider.openRocksDB(options, dataPath, optionPath, openHttp);
    }

    public static void closeRocksDB(RocksDB rocksDB) {
        RocksDBProvider provider = getInstance().getBestProvider();
        provider.closeRocksDB(rocksDB);
    }
}
```

### 2.6 调用链路

以 `hugegraph-server` 单机模式为例：

> 源码位置：`hugegraph-server/hugegraph-rocksdb/src/main/java/org/apache/hugegraph/backend/store/rocksdb/RocksDBStdSessions.java:385-431`

```
RocksDBStdSessions.openRocksDB(config, dataPath, walPath)
  │
  ├─ config.get(RocksDBOptions.OPTION_PATH)   → e.g. "conf/topling.yaml"
  ├─ config.get(RocksDBOptions.OPEN_HTTP)     → true/false
  │
  └─ RocksDBProviderLoader.openRocksDB(options, dataPath, optionPath, openHttp)
       │
       └─ getInstance().getBestProvider()
            │
            ├─ [有 ToplingDB JAR] → ToplingRocksDBProvider (priority=200)
            │     └─ 反射: SidePluginRepo → importAutoFile → openDB → startHttpServer
            │
            └─ [无 ToplingDB JAR] → StandardRocksDBProvider (priority=100)
                  └─ 直接 RocksDB.open(options, dataPath)
```

关键代码（`RocksDBStdSessions.java` 第 385-391 行）：

```java
// Only enable HTTP server for GRAPH_STORE when openHttp is true
boolean openHttp = Boolean.TRUE.equals(config.get(RocksDBOptions.OPEN_HTTP)) &&
                   BackendStoreProvider.GRAPH_STORE.equals(getDbName(dataPath));
RocksDB rocksdb = RocksDBProviderLoader.openRocksDB(options, dataPath,
                                                    config.get(RocksDBOptions.OPTION_PATH),
                                                    openHttp);
```

### 2.7 Native Library 预加载

ToplingDB 依赖额外的 `.so` 动态库，通过启动脚本处理。

> 源码位置：`hugegraph-server/hugegraph-dist/src/assembly/static/bin/common-topling.sh`

**`bin/preload-topling.sh`** → 调用 **`bin/common-topling.sh`** 中的 `preload_toplingdb()` 函数：

```bash
function preload_toplingdb() {
    local lib_dir="$1"
    local dest_dir="$2"

    # 1. 从 rocksdbjni*.jar 中解压 .so 文件
    extract_so_with_jar "$jar_file" "$dest_dir"

    # 2. 处理 Ubuntu 24.04+ 的 libaio 兼容性
    ensure_libaio_symlink

    # 3. 下载并预加载 jemalloc（优先使用系统已安装的）
    download_and_setup_jemalloc "$top"

    # 4. 设置 LD_LIBRARY_PATH
    export LD_LIBRARY_PATH="$dest_dir${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

    # 5. 设置 LD_PRELOAD 预加载 librocksdbjni-linux64.so
    export LD_PRELOAD="${LD_PRELOAD:+$LD_PRELOAD:}$dest_dir/librocksdbjni-linux64.so"

    # 6. 解压 HTML/CSS（ToplingDB Web 监控界面资源）
    extract_html_css_from_jar "$jar_file" "$dest_dir"
}
```

### 2.8 配置选项

> 源码位置：`hugegraph-server/hugegraph-rocksdb/src/main/java/org/apache/hugegraph/backend/store/rocksdb/RocksDBOptions.java:89-103`

| 配置项 | 类型 | 说明 |
|--------|------|------|
| `rocksdb.option_path` | String | ToplingDB YAML 配置文件路径（如 `conf/topling.yaml`） |
| `rocksdb.open_http` | Boolean | 是否启动 ToplingDB 内嵌 Web 监控服务器 |

安全验证规则（`ToplingRocksDBProvider.validateOptionPath()`）：

- 路径必须匹配正则 `^[a-zA-Z0-9/_.-]+\.yaml$`
- 不允许包含 `..` 或 `://`
- 必须位于 `./conf/` 目录下（路径遍历防护）
- 文件必须存在且可读
- 文件大小限制 10MB（防止 DoS）
- YAML 使用 `SafeConstructor` 解析（防止反序列化攻击）

### 2.9 StandardRocksDBProvider（降级方案）

> 源码位置：`hugegraph-rocksdb-provider/src/main/java/org/apache/hugegraph/rocksdb/provider/StandardRocksDBProvider.java`

当 ToplingDB 不可用时，`StandardRocksDBProvider`（priority=100）接管：

```java
public class StandardRocksDBProvider extends AbstractRocksDBProvider {
    @Override
    public boolean isAvailable() {
        RocksDB.loadLibrary();  // 只要标准 RocksDB 能加载就可用
        return true;
    }

    @Override
    protected RocksDB doOpenRocksDB(Options options, String dataPath) {
        return RocksDB.open(options, dataPath);  // 直接调用标准 API
    }

    // ToplingDB 专有参数会被忽略并打印 warn 日志
    @Override
    protected RocksDB doOpenRocksDB(Options options, String dataPath,
                                    String optionPath, Boolean openHttp) {
        if (optionPath != null) {
            LOG.warn("Standard RocksDB does not support optionPath, ignoring: {}", optionPath);
        }
        return RocksDB.open(options, dataPath);
    }
}
```

---

## 三、HStore 接入详解

### 3.1 设计思路：分布式存储客户端

HStore 是 HugeGraph 的**分布式存储后端**，它将一个独立的 `hugegraph-store` 集群（多节点 Raft + RocksDB）封装为与单机 RocksDB 相同的 `BackendStoreProvider` 接口。对上层查询引擎完全透明。

### 3.2 模块结构

```
hugegraph-server/hugegraph-hstore/       ← 客户端适配模块
├── HstoreProvider.java                  ← BackendStoreProvider 实现 (type="hstore")
├── HstoreStore.java                     ← 抽象 Store (HstoreSchemaStore / HstoreGraphStore)
├── HstoreSessions.java                  ← 会话抽象层
├── HstoreSessionsImpl.java             ← 具体实现：通过 gRPC 与 Store 集群通信
├── HstoreTable.java                     ← 表操作抽象
├── HstoreTables.java                    ← 具体表定义 (vertex, edge, index 等)
├── HstoreFeatures.java                  ← 后端能力声明
├── HstoreOptions.java                   ← 配置项（如 partition_count）
└── HstoreMetrics.java                   ← 指标收集

hugegraph-store/                          ← 存储节点服务（独立部署）
├── hg-store-grpc/                       ← gRPC 协议定义
├── hg-store-common/                     ← 公共类
├── hg-store-client/                     ← 客户端库 (HgStoreClient)
├── hg-store-core/                       ← 核心逻辑（Raft、分区管理）
├── hg-store-node/                       ← 节点服务主程序
├── hg-store-rocksdb/                    ← 节点层 RocksDB 访问（也支持 ToplingDB）
├── hg-store-cli/                        ← 命令行工具
├── hg-store-test/                       ← 测试
└── hg-store-dist/                       ← 打包分发
```

### 3.3 HstoreProvider —— 入口

> 源码位置：`hugegraph-server/hugegraph-hstore/src/main/java/org/apache/hugegraph/backend/store/hstore/HstoreProvider.java`

```java
public class HstoreProvider extends AbstractBackendStoreProvider {

    @Override
    public String type() {
        return "hstore";
    }

    @Override
    public String driverVersion() {
        return "1.13";
    }

    @Override
    protected BackendStore newSchemaStore(HugeConfig config, String store) {
        return new HstoreStore.HstoreSchemaStore(this, this.namespace(), store);
    }

    @Override
    protected BackendStore newGraphStore(HugeConfig config, String store) {
        return new HstoreStore.HstoreGraphStore(this, this.namespace(), store);
    }
}
```

### 3.4 HstoreSessionsImpl —— 核心连接层

> 源码位置：`hugegraph-server/hugegraph-hstore/src/main/java/org/apache/hugegraph/backend/store/hstore/HstoreSessionsImpl.java`

这是 HStore 最关键的实现类，负责与分布式存储集群通信：

```java
public class HstoreSessionsImpl extends HstoreSessions {
    private static volatile PDClient defaultPdClient;       // PD 元数据客户端
    private static volatile HgStoreClient hgStoreClient;    // Store 数据客户端

    // 初始化 Store 节点连接（单例，只执行一次）
    private void initStoreNode(HugeConfig config) {
        if (!initializedNode) {
            synchronized (this) {
                if (!initializedNode) {
                    // 创建 PD 客户端（Placement Driver，负责元数据和路由）
                    PDConfig pdConfig = PDConfig.of(config.get(CoreOptions.PD_PEERS))
                                                .setAuthority(PDAuthConfig.service(),
                                                              PDAuthConfig.token())
                                                .setEnableCache(true);
                    defaultPdClient = PDClient.create(pdConfig);

                    // 基于 PD 客户端创建 Store 客户端（负责数据读写）
                    hgStoreClient = HgStoreClient.create(defaultPdClient);
                    initializedNode = Boolean.TRUE;
                }
            }
        }
    }

    // 打开会话时向 PD 注册图的分区信息
    @Override
    public void open() {
        if (!infoInitializedGraph.contains(this.graphName)) {
            Integer partitionCount = this.config.get(HstoreOptions.PARTITION_COUNT);
            defaultPdClient.setGraph(Metapb.Graph.newBuilder()
                                                 .setGraphName(this.graphName)
                                                 .setPartitionCount(partitionCount)
                                                 .build());
            infoInitializedGraph.add(this.graphName);
        }
        this.session.open();
    }

    // 表操作委托给远程 Store 节点
    @Override
    public synchronized void createTable(String... tables) { ... }

    @Override
    public synchronized void dropTable(String... tables) { ... }

    @Override
    public boolean existsTable(String table) { ... }
}
```

### 3.5 HstoreStore —— 存储抽象

> 源码位置：`hugegraph-server/hugegraph-hstore/src/main/java/org/apache/hugegraph/backend/store/hstore/HstoreStore.java`

```java
public abstract class HstoreStore extends AbstractBackendStore<Session> {

    // 支持的索引类型
    private static final Set<HugeType> INDEX_TYPES = ImmutableSet.of(
        HugeType.SECONDARY_INDEX, HugeType.VERTEX_LABEL_INDEX,
        HugeType.EDGE_LABEL_INDEX, HugeType.RANGE_INT_INDEX,
        HugeType.RANGE_FLOAT_INDEX, HugeType.RANGE_LONG_INDEX,
        HugeType.RANGE_DOUBLE_INDEX, HugeType.SEARCH_INDEX,
        HugeType.SHARD_INDEX, HugeType.UNIQUE_INDEX
    );

    // 两种具体实现
    public static class HstoreSchemaStore extends HstoreStore { ... }
    public static class HstoreGraphStore extends HstoreStore { ... }
}
```

### 3.6 HStore 运行时数据流

```
HugeGraph Server (hugegraph-hstore 客户端)
    │
    ├─ HstoreSessionsImpl
    │     ├─ PDClient ──────────── gRPC ──→ PD (Placement Driver)
    │     │     • 获取 partition 路由表        │   • 管理分区分配
    │     │     • 注册图元数据                  │   • 调度数据均衡
    │     │                                    │
    │     └─ HgStoreClient ──── gRPC ──→ Store Node (hg-store-node)
    │           • put / get / scan / delete     │   • Raft 共识保证一致性
    │           • 按 partition 路由到对应节点     │   • RocksDB / ToplingDB 存储
    │                                           │   • 数据分片与副本
    │                                           │
    └─ HstoreTable                              └─→ hg-store-rocksdb
          • 序列化/反序列化 vertex/edge/index          │
          • 构建 HgScanQuery                          └─ RocksDBProviderLoader (同样的 SPI)
                                                          ├─ ToplingRocksDBProvider
                                                          └─ StandardRocksDBProvider
```

### 3.7 BackendProviderFactory —— 后端注册与发现

> 源码位置：`hugegraph-server/hugegraph-core/src/main/java/org/apache/hugegraph/backend/store/BackendProviderFactory.java`

```java
public class BackendProviderFactory {
    // 允许的后端类型白名单
    private static final List<String> ALLOWED_BACKENDS =
        List.of("memory", "rocksdb", "hbase", "hstore");

    public static BackendStoreProvider open(HugeGraphParams params) {
        String backend = config.get(CoreOptions.BACKEND).toLowerCase();  // e.g. "hstore"
        BackendStoreProvider provider = newProvider(config);

        // 如果开启 Raft 模式，包装为 RaftBackendStoreProvider
        if (raftMode) {
            provider = new RaftBackendStoreProvider(params, provider);
        }
        provider.open(graph);
        return provider;
    }
}
```

### 3.8 关键配置

HStore 模式配置示例：

```properties
# hugegraph.properties
backend=hstore
pd.peers=127.0.0.1:8686,127.0.0.1:8687,127.0.0.1:8688
hstore.partition_count=24
```

Store 节点也支持 ToplingDB（`hugegraph-store/hg-store-dist/src/assembly/static/conf/application-pd.yml`）：

```yaml
rocksdb:
  option_path: conf/topling-store.yaml
  open_http: true
```

---

## 四、两者的关系与对比

| 维度 | ToplingDB | HStore |
|------|-----------|--------|
| **是什么** | 增强版存储引擎 | 分布式存储架构 |
| **替换什么** | 替换标准 RocksDB 的 open/close | 替换单机嵌入式存储模式 |
| **接入方式** | Java SPI + 反射，运行时探测 | BackendStoreProvider 插件注册 |
| **影响范围** | 仅引擎层（open/close 两个点） | 全栈（路由、分片、复制、事务） |
| **能否组合** | ✅ 可用于 HStore 节点内部 | ✅ 节点内部可用 ToplingDB |
| **侵入性** | 极低，零编译时依赖 | 中等，需要完整实现 Store 接口 |
| **依赖模块** | `hugegraph-rocksdb-provider` | `hugegraph-hstore` + `hugegraph-store` 集群 |
| **部署要求** | 只需替换 JAR + 运行启动脚本 | 需要独立部署 PD + Store 节点集群 |

### 组合部署矩阵

| 部署模式 | 配置 | 效果 |
|----------|------|------|
| 单机 + 标准 RocksDB | `backend=rocksdb` + 标准 JAR | StandardRocksDBProvider |
| 单机 + ToplingDB | `backend=rocksdb` + ToplingDB JAR | ToplingRocksDBProvider（自动） |
| 分布式 + 标准 RocksDB | `backend=hstore` + Store 节点用标准 JAR | HStore + StandardRocksDBProvider |
| 分布式 + ToplingDB | `backend=hstore` + Store 节点用 ToplingDB JAR | HStore + ToplingRocksDBProvider |

---

## 五、Maven 依赖链

```
hugegraph (root pom.xml)
│
├── hugegraph-rocksdb-provider                ← 独立模块
│   └── rocksdbjni:8.10.2-SNAPSHOT            ← ToplingDB 增强版 RocksDB JNI
│   └── snakeyaml:2.2                         ← YAML 配置解析
│   └── json-smart:2.3                        ← JSON 构建（SidePluginRepo 参数）
│
├── hugegraph-server/hugegraph-rocksdb        ← 单机 RocksDB 后端
│   └── depends on: hugegraph-rocksdb-provider
│
├── hugegraph-server/hugegraph-hstore         ← HStore 客户端
│   └── depends on: hg-store-client           ← gRPC 客户端
│   └── depends on: hg-pd-client              ← PD 客户端
│
└── hugegraph-store/hg-store-rocksdb          ← Store 节点存储层
    └── depends on: hugegraph-rocksdb-provider ← 同一个 SPI 模块!
```

ToplingDB Maven artifact 来源（`.github/configs/settings.xml`）：

```xml
<repository>
    <id>github-toplingdb</id>
    <url>https://maven.pkg.github.com/hugegraph/toplingdb</url>
</repository>
```

---

## 六、关键源码文件索引

| 文件 | 作用 |
|------|------|
| `hugegraph-rocksdb-provider/src/.../RocksDBProvider.java` | SPI 接口定义 |
| `hugegraph-rocksdb-provider/src/.../ToplingRocksDBProvider.java` | ToplingDB 适配核心 |
| `hugegraph-rocksdb-provider/src/.../StandardRocksDBProvider.java` | 标准 RocksDB 降级方案 |
| `hugegraph-rocksdb-provider/src/.../RocksDBProviderLoader.java` | ServiceLoader + Provider 选择 |
| `hugegraph-server/hugegraph-rocksdb/src/.../RocksDBStdSessions.java` | 单机模式调用入口 |
| `hugegraph-server/hugegraph-rocksdb/src/.../RocksDBOptions.java` | 配置项定义 |
| `hugegraph-server/hugegraph-rocksdb/src/.../OpenedRocksDB.java` | close 调用入口 |
| `hugegraph-server/hugegraph-hstore/src/.../HstoreProvider.java` | HStore 后端入口 |
| `hugegraph-server/hugegraph-hstore/src/.../HstoreSessionsImpl.java` | HStore gRPC 通信核心 |
| `hugegraph-server/hugegraph-hstore/src/.../HstoreStore.java` | HStore 存储抽象 |
| `hugegraph-store/hg-store-rocksdb/src/.../RocksDBSession.java` | Store 节点 RocksDB 层 |
| `hugegraph-server/hugegraph-core/src/.../BackendProviderFactory.java` | 后端工厂（注册与发现） |
| `hugegraph-server/hugegraph-dist/src/.../bin/common-topling.sh` | Native 库预加载脚本 |
| `hugegraph-server/hugegraph-dist/src/.../bin/preload-topling.sh` | 启动时调用入口 |

---

## 七、总结

1. **ToplingDB 接入的精髓**是"最小侵入"：只在 `RocksDB.open()` 和 `rocksdb.close()` 两个调用点做替换，通过 Java SPI 机制实现零编译时依赖、运行时自动发现和优雅降级。所有 ToplingDB 特有 API（`SidePluginRepo`）均通过反射调用，确保标准 RocksDB 环境下代码完全正常运行。

2. **HStore 接入的精髓**是"透明分布式"：通过实现 `BackendStoreProvider` 接口，将分布式集群（PD + Store 节点 + Raft 共识）封装为与单机 RocksDB 相同的语义。上层查询引擎无需任何修改即可从单机切换到分布式。

3. **两者共享 `hugegraph-rocksdb-provider` 模块**：无论是单机 `hugegraph-server/hugegraph-rocksdb` 路径，还是分布式 `hugegraph-store/hg-store-rocksdb` 路径，都依赖同一个 Provider SPI 模块。这意味着 ToplingDB 的性能优势在两种部署模式下都能获得。
