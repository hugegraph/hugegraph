# ToplingDB 显式配置 + 独立 Provider 重构 SOP

## 一、背景与问题

### 1.1 现状

当前 ToplingDB 集成方案存在以下核心问题：

| 问题 | 具体表现 |
|------|----------|
| **隐式激活** | `ToplingRocksDBProvider` 通过 `Class.forName("org.rocksdb.SidePluginRepo")` 自动探测 classpath，只要 ToplingDB JAR 存在就自动接管所有 RocksDB 操作（priority=200 > standard=100），用户无法显式选择 |
| **共享坐标污染** | ToplingDB 与标准 RocksDB 共用 `org.rocksdb:rocksdbjni` 坐标（仅版本/来源不同），导致 Maven 依赖仲裁不可控 |
| **影响纯 RocksDB 用户** | 不需要 ToplingDB 的用户也被 SNAPSHOT 依赖、GitHub Packages 仓库配置所困扰 |

### 1.2 目标

1. **显式配置**：用户通过 `rocksdb.provider=standard|topling` 明确选择引擎，不再依赖 classpath 自动探测
2. **部署时 Drop-in**：运维不需要 Maven 配置，下载 ToplingDB addon 包放入部署目录即可
3. **模块结构不变**：保留现有 `hugegraph-rocksdb-provider` 单模块，内部简化逻辑

---

## 二、分发模型

### 2.1 构建与部署分离

ToplingDB 本身就需要下载 native `.so` 库和可能的外置二进制文件，因此对终端用户来说是一个 **部署时 addon**，而非编译时依赖。

```
┌──────────────────────────────────────────────────────────┐
│                   默认构建产物                             │
│  mvn clean package                                       │
│  ├── lib/rocksdbjni-8.10.2.jar       (标准 RocksDB)      │
│  └── lib/hugegraph-rocksdb-provider.jar                  │
└──────────────────────────────────────────────────────────┘

                    ↓ 运维选择启用 ToplingDB ↓

┌──────────────────────────────────────────────────────────┐
│            ToplingDB Addon 包 (独立下载)                   │
│  toplingdb-addon-1.0.0-linux-x86_64.tar.gz               │
│  ├── lib/toplingdb-jni-1.0.0.jar     (替换 rocksdbjni)   │
│  ├── native/librocksdbjni-linux64.so                     │
│  ├── native/libterark-*.so (可选)                        │
│  ├── web/ (ToplingDB HTTP 监控 UI)                        │
│  └── conf/rocksdb_plus.yaml.template                     │
└──────────────────────────────────────────────────────────┘
```

### 2.2 两种角色

| 角色 | ToplingDB 怎么来 | 需要 Maven 配置？ |
|------|-----------------|-----------------|
| **运维/部署者** | 下载 addon 包，解压到部署目录，改配置 | 不需要 |
| **开发者** | POM 中 `-P toplingdb` 引入（仅编译/调试用） | 需要 |

### 2.3 部署流程（运维视角）

```bash
# 1. 下载 ToplingDB addon 包
wget https://github.com/hugegraph/toplingdb/releases/download/v1.0.0/toplingdb-addon-1.0.0-linux-x86_64.tar.gz

# 2. 解压到 HugeGraph 安装目录
tar -xzf toplingdb-addon-1.0.0-linux-x86_64.tar.gz -C /opt/hugegraph/

# 3. 替换标准 RocksDB JAR（互斥）
rm /opt/hugegraph/lib/rocksdbjni-*.jar

# 4. 修改配置
vi conf/hugegraph.properties
# rocksdb.provider=topling
# rocksdb.option_path=./conf/graphs/rocksdb_plus.yaml

# 5. 启动
bin/start-hugegraph.sh
```

---

## 三、模块改造设计

### 3.1 保持单模块结构

`hugegraph-rocksdb-provider` 模块保持不变，内部简化为配置驱动：

```
hugegraph-rocksdb-provider/
├── src/main/java/.../rocksdb/provider/
│   ├── RocksDBProvider.java              (接口，移除 getPriority())
│   ├── AbstractRocksDBProvider.java      (模板基类，保持不变)
│   ├── RocksDBProviderLoader.java        (改为配置驱动选择)
│   ├── StandardRocksDBProvider.java      (保持不变)
│   └── ToplingRocksDBProvider.java       (保持不变)
├── src/main/resources/META-INF/services/ (SPI 注册，保持不变)
└── pom.xml                               (依赖改造)
```

### 3.2 POM 依赖改造

```xml
<!-- hugegraph-rocksdb-provider/pom.xml -->
<dependencies>
    <!-- 标准 RocksDB（默认 profile，正式 release 版本） -->
    <dependency>
        <groupId>org.rocksdb</groupId>
        <artifactId>rocksdbjni</artifactId>
        <version>${rocksdb.version}</version>
    </dependency>

    <!-- ToplingDB JNI（仅开发者 profile，独立坐标） -->
    <!-- 运维通过 addon 包 drop-in，不走 Maven -->
</dependencies>

<!-- Root pom.xml 中的 Profile -->
<profiles>
    <profile>
        <id>standard-rocksdb</id>
        <activation>
            <activeByDefault>true</activeByDefault>
        </activation>
        <properties>
            <rocksdb.engine.groupId>org.rocksdb</rocksdb.engine.groupId>
            <rocksdb.engine.artifactId>rocksdbjni</rocksdb.engine.artifactId>
            <rocksdb.engine.version>${rocksdb.version}</rocksdb.engine.version>
        </properties>
    </profile>
    <profile>
        <id>toplingdb</id>
        <properties>
            <rocksdb.engine.groupId>org.apache.hugegraph</rocksdb.engine.groupId>
            <rocksdb.engine.artifactId>toplingdb-jni</rocksdb.engine.artifactId>
            <rocksdb.engine.version>${toplingdb.version}</rocksdb.engine.version>
        </properties>
    </profile>
</profiles>
```

`hugegraph-rocksdb-provider/pom.xml` 中依赖改为变量引用：

```xml
<dependency>
    <groupId>${rocksdb.engine.groupId}</groupId>
    <artifactId>${rocksdb.engine.artifactId}</artifactId>
    <version>${rocksdb.engine.version}</version>
</dependency>
```

### 3.3 RocksDBProviderLoader 改造

从"按 priority 自动选最高"改为"按配置名精确匹配"：

```java
public class RocksDBProviderLoader {

    private final Map<String, RocksDBProvider> providerRegistry = new ConcurrentHashMap<>();
    private volatile RocksDBProvider activeProvider;

    /**
     * 加载所有 SPI 注册的 Provider 到 registry
     */
    public synchronized void loadProviders() {
        ServiceLoader<RocksDBProvider> loader = ServiceLoader.load(RocksDBProvider.class);
        for (RocksDBProvider provider : loader) {
            providerRegistry.put(provider.getProviderName(), provider);
            LOG.info("Discovered RocksDB provider: {}", provider.getProviderName());
        }
    }

    /**
     * 根据配置值选择并激活 Provider
     */
    public synchronized RocksDBProvider selectProvider(String providerName) {
        if (providerRegistry.isEmpty()) {
            loadProviders();
        }

        RocksDBProvider provider = providerRegistry.get(providerName);
        if (provider == null) {
            throw new IllegalStateException(String.format(
                "RocksDB provider '%s' not found. Available: %s. " +
                "If using ToplingDB, ensure the addon is installed in lib/.",
                providerName, providerRegistry.keySet()));
        }

        if (!provider.isAvailable()) {
            throw new IllegalStateException(String.format(
                "RocksDB provider '%s' found but not available. " +
                "Check native libraries and LD_PRELOAD.",
                providerName));
        }

        this.activeProvider = provider;
        provider.initialize();
        LOG.info("Activated RocksDB provider: {}", providerName);
        return provider;
    }

    /**
     * 获取已激活的 Provider
     */
    public RocksDBProvider getActiveProvider() {
        if (activeProvider == null) {
            throw new IllegalStateException(
                "No RocksDB provider activated. Call selectProvider() first.");
        }
        return activeProvider;
    }

    // static 便捷方法内部改为 getActiveProvider()
    public static RocksDB openRocksDB(Options options, String dataPath,
                                      String optionPath, Boolean openHttp) throws RocksDBException {
        return getInstance().getActiveProvider()
                .openRocksDB(options, dataPath, optionPath, openHttp);
    }

    public static void closeRocksDB(RocksDB rocksDB) {
        getInstance().getActiveProvider().closeRocksDB(rocksDB);
    }
}
```

### 3.4 Provider 接口简化

```java
public interface RocksDBProvider {

    /** Provider 标识名，与 rocksdb.provider 配置值匹配 */
    String getProviderName();  // "standard" or "topling"

    /** 当前环境是否可用 */
    boolean isAvailable();

    // open/close 方法签名保持不变...

    // 移除 getPriority() — 不再需要优先级竞争
}
```

### 3.5 新增配置项

在 `RocksDBOptions.java`（server 和 store 两处）新增：

```java
public static final ConfigOption<String> PROVIDER =
        new ConfigOption<>(
                "rocksdb.provider",
                "The RocksDB engine provider. 'standard' for vanilla RocksDB, " +
                "'topling' for ToplingDB (requires addon installation).",
                allowValues("standard", "topling"),
                "standard"
        );
```

### 3.6 调用方改造

在 RocksDB Backend 初始化时读取配置并激活 Provider：

```java
// RocksDBStdSessions 构造函数 或 RocksDBStoreProvider.open() 中
String providerName = config.get(RocksDBOptions.PROVIDER);
RocksDBProviderLoader.getInstance().selectProvider(providerName);

// 后续 open/close 调用方式不变
RocksDBProviderLoader.openRocksDB(options, dataPath, optionPath, openHttp);
```

---

## 四、ToplingDB Addon 包

### 4.1 包内容

```
toplingdb-addon-1.0.0-linux-x86_64.tar.gz
├── lib/
│   └── toplingdb-jni-1.0.0.jar              ← 替换 rocksdbjni-*.jar
├── native/
│   ├── librocksdbjni-linux64.so
│   ├── libterark-zip-rocksdb-trial.so       (可选)
│   └── libjemalloc.so                       (可选)
├── web/
│   ├── index.html
│   └── style.css
├── conf/
│   └── rocksdb_plus.yaml.template
└── install.sh                               (可选安装脚本)
```

### 4.2 启动脚本适配

`common-topling.sh` 查找逻辑改为优先使用 `native/` 目录：

```bash
# 优先从 native/ 目录加载（addon 已安装）
if [ -d "$HUGEGRAPH_HOME/native" ] && ls "$HUGEGRAPH_HOME/native"/librocksdbjni*.so >/dev/null 2>&1; then
    export LD_PRELOAD="$HUGEGRAPH_HOME/native/librocksdbjni-linux64.so"
else
    # 回退：从 JAR 中提取（兼容旧方式）
    jar_file=$(ls -1 "$lib_dir"/toplingdb-jni*.jar 2>/dev/null | head -1)
    if [ -z "$jar_file" ]; then
        jar_file=$(ls -1 "$lib_dir"/rocksdbjni*.jar 2>/dev/null | head -1)
    fi
    extract_so_from_jar "$jar_file"
fi
```

---

## 五、配置使用

### 5.1 标准 RocksDB（默认）

```properties
backend=rocksdb
# rocksdb.provider=standard  ← 默认值，可不写
```

### 5.2 ToplingDB

```properties
backend=rocksdb
rocksdb.provider=topling
rocksdb.option_path=./conf/graphs/rocksdb_plus.yaml
rocksdb.open_http=true
```

### 5.3 错误提示

| 场景 | 错误信息 |
|------|----------|
| 配置 `topling` 但未安装 addon | `RocksDB provider 'topling' not found. Available: [standard]. Install ToplingDB addon to lib/.` |
| addon JAR 在但 native lib 缺失 | `RocksDB provider 'topling' found but not available. Check native libraries and LD_PRELOAD.` |

---

## 六、实施步骤

### Phase 1：改造 RocksDBProviderLoader（核心）

1. 移除 `getBestProvider()` 的 priority 竞争逻辑
2. 新增 `selectProvider(String name)` 按名精确匹配
3. 新增 `getActiveProvider()` 替代原 `getBestProvider()`
4. `RocksDBProvider` 接口移除 `getPriority()`
5. **验证**：编译通过，配置 `standard` 时行为与原来一致

### Phase 2：新增配置项 + 调用方改造

1. `RocksDBOptions.java` (server + store) 新增 `rocksdb.provider`
2. `RocksDBStdSessions` 初始化时调用 `selectProvider(config.get(PROVIDER))`
3. `hg-store-rocksdb` 的 `RocksDBSession` 同理
4. **验证**：`provider=standard` 正常工作

### Phase 3：POM 独立坐标 + Profile

1. Root `pom.xml` 新增 `standard-rocksdb` / `toplingdb` 两个 Profile
2. `hugegraph-rocksdb-provider/pom.xml` 依赖改为 `${rocksdb.engine.*}` 变量
3. 移除 `8.10.2-SNAPSHOT` 硬编码，标准 profile 用正式 release
4. **验证**：`mvn package` 默认无 SNAPSHOT，`mvn package -P toplingdb` 引入 ToplingDB

### Phase 4：Addon 包 + 脚本改造

1. 设计 addon 包打包流程（CI）
2. 改造 `common-topling.sh` 支持 `native/` 目录
3. 编写 `install.sh`
4. **验证**：全新部署环境通过 addon 安装 ToplingDB 正常启动

### Phase 5：清理

1. 移除 `.github/configs/settings.xml` 中 GitHub Packages 仓库（默认构建不再需要）
2. 更新配置文件模板
3. 更新文档
4. **验证**：完整测试矩阵

---

## 七、验证矩阵

| 验证项 | 操作 | 预期 |
|--------|------|------|
| 默认构建 | `mvn clean package` | 无 SNAPSHOT，无 GitHub Token，产物仅含标准 RocksDB |
| 开发者构建 | `mvn clean package -P toplingdb` | 含 ToplingDB JNI |
| 标准模式启动 | `provider=standard` | 使用原生 RocksDB |
| ToplingDB 启动 | `provider=topling` + addon 安装 | 使用 ToplingDB |
| 配置不匹配 | `provider=topling` + 未安装 addon | 明确错误信息 |
| Addon 安装 | 解压 addon + 改配置 | 无需重新编译 |
| HStore 模式 | Store 节点同验证 | 一致 |

---

## 八、设计决策

### 为什么保持单模块？

- `hugegraph-rocksdb-provider` 已被 `hugegraph-server/hugegraph-rocksdb` 和 `hugegraph-store/hg-store-rocksdb` 共同依赖，是两者共享 open/close 逻辑的自然位置
- 拆成 api + standard + topling 三个模块增加了维护成本，但 Provider 实现本身代码量很小，不值得拆
- 单模块内通过配置驱动切换，足够简洁

### 为什么运维走 Drop-in 而非 Maven？

- ToplingDB 本身就需要 native lib 下载，addon 包是天然的分发单元
- 运维不需要理解 Maven Profile，下载解压改配置即可
- 离线环境友好
- CI/CD 中普通构建不需要 GitHub Token

### 为什么 `rocksdb.provider` 而非 `backend=toplingdb`？

ToplingDB 是 RocksDB 引擎层替换，不是新的存储后端。表结构、序列化、查询全部复用 RocksDB Backend 代码。`rocksdb.provider=topling` 语义精确。

---

## 九、时间估算

| Phase | 工作量 |
|-------|--------|
| Phase 1：Loader 改造 | 0.5 天 |
| Phase 2：配置项 + 调用方 | 0.5 天 |
| Phase 3：POM + Profile | 0.5 天 |
| Phase 4：Addon + 脚本 | 1.5 天 |
| Phase 5：清理 | 0.5 天 |
| **合计** | **3.5 天** |
