# RocksDB Provider Pattern 参考文档

> 本文档记录 `hugegraph-rocksdb-provider` 模块的 SPI 设计模式，供未来参考。
> 如果后续需要重新引入 Provider 抽象层（例如支持新的存储引擎变体），可基于此设计恢复。

## 设计概述

采用 Java SPI (ServiceLoader) + Strategy Pattern，通过配置项 `rocksdb.provider` 在运行时选择 RocksDB 引擎实现。

```
RocksDBProvider (接口)
    └── AbstractRocksDBProvider (模板方法基类)
            ├── StandardRocksDBProvider  ("standard")
            └── ToplingRocksDBProvider   ("topling")

RocksDBProviderLoader (单例注册中心，对外提供静态方法)
```

## 核心接口

```java
public interface RocksDBProvider {
    String getProviderName();
    boolean isAvailable();
    void initialize();
    void shutdown();

    RocksDB openRocksDB(Options options, String dataPath) throws RocksDBException;
    RocksDB openRocksDB(DBOptions dbOptions, String dataPath,
                        List<ColumnFamilyDescriptor> cfDescriptors,
                        List<ColumnFamilyHandle> cfHandles) throws RocksDBException;
    void closeRocksDB(RocksDB db);
    void closeRocksDB(RocksDB db, List<ColumnFamilyHandle> cfHandles);
}
```

## 抽象基类（模板方法）

```java
public abstract class AbstractRocksDBProvider implements RocksDBProvider {

    protected static final Logger LOG = LoggerFactory.getLogger(AbstractRocksDBProvider.class);

    @Override
    public final RocksDB openRocksDB(Options options, String dataPath) throws RocksDBException {
        LOG.info("Opening RocksDB via [{}] provider at: {}", getProviderName(), dataPath);
        return doOpenRocksDB(options, dataPath);
    }

    @Override
    public final RocksDB openRocksDB(DBOptions dbOptions, String dataPath,
                                     List<ColumnFamilyDescriptor> cfDescriptors,
                                     List<ColumnFamilyHandle> cfHandles) throws RocksDBException {
        LOG.info("Opening RocksDB with {} CFs via [{}] provider at: {}",
                 cfDescriptors.size(), getProviderName(), dataPath);
        return doOpenRocksDB(dbOptions, dataPath, cfDescriptors, cfHandles);
    }

    @Override
    public void closeRocksDB(RocksDB db) {
        if (db != null) {
            performProviderSpecificClose(db);
            db.close();
        }
    }

    @Override
    public void closeRocksDB(RocksDB db, List<ColumnFamilyHandle> cfHandles) {
        if (cfHandles != null) {
            cfHandles.forEach(ColumnFamilyHandle::close);
        }
        closeRocksDB(db);
    }

    protected abstract RocksDB doOpenRocksDB(Options options, String dataPath) throws RocksDBException;
    protected abstract RocksDB doOpenRocksDB(DBOptions dbOptions, String dataPath,
                                            List<ColumnFamilyDescriptor> cfDescriptors,
                                            List<ColumnFamilyHandle> cfHandles) throws RocksDBException;
    protected abstract void performProviderSpecificClose(RocksDB rocksDB);
}
```

## ProviderLoader（单例注册中心）

```java
public class RocksDBProviderLoader {

    private static final RocksDBProviderLoader INSTANCE = new RocksDBProviderLoader();
    private final Map<String, RocksDBProvider> providers = new ConcurrentHashMap<>();
    private volatile RocksDBProvider activeProvider;

    public static RocksDBProviderLoader getInstance() { return INSTANCE; }

    public void reload() {
        providers.clear();
        ServiceLoader<RocksDBProvider> loader = ServiceLoader.load(RocksDBProvider.class);
        for (RocksDBProvider provider : loader) {
            providers.put(provider.getProviderName(), provider);
        }
    }

    public void selectProvider(String name) {
        RocksDBProvider provider = providers.get(name);
        if (provider == null) throw new IllegalArgumentException("Unknown provider: " + name);
        if (!provider.isAvailable()) throw new IllegalStateException("Provider not available: " + name);
        provider.initialize();
        activeProvider = provider;
    }

    // 静态便捷方法
    public static RocksDB openRocksDB(Options options, String dataPath) throws RocksDBException {
        return INSTANCE.activeProvider.openRocksDB(options, dataPath);
    }

    public static RocksDB openRocksDB(DBOptions dbOptions, String dataPath,
                                      List<ColumnFamilyDescriptor> cfDescriptors,
                                      List<ColumnFamilyHandle> cfHandles) throws RocksDBException {
        return INSTANCE.activeProvider.openRocksDB(dbOptions, dataPath, cfDescriptors, cfHandles);
    }

    public static void closeRocksDB(RocksDB db) {
        INSTANCE.activeProvider.closeRocksDB(db);
    }
}
```

## SPI 注册

文件：`META-INF/services/org.apache.hugegraph.rocksdb.provider.RocksDBProvider`

```
org.apache.hugegraph.rocksdb.provider.StandardRocksDBProvider
org.apache.hugegraph.rocksdb.provider.ToplingRocksDBProvider
```

## 调用方使用方式

```java
// 初始化（在 HugeGraph 启动时调用一次）
RocksDBProviderLoader loader = RocksDBProviderLoader.getInstance();
loader.reload();
loader.selectProvider(config.get("rocksdb.provider"));  // "standard" or "topling"

// 使用（在 RocksDBStdSessions / RocksDBSession 中）
RocksDB db = RocksDBProviderLoader.openRocksDB(options, dataPath);
RocksDBProviderLoader.closeRocksDB(db);
```

## 去除原因

Easy Migrate 重构后，`ToplingRocksDBProvider` 和 `StandardRocksDBProvider` 的 `doOpenRocksDB` 实现完全相同（都是 `RocksDB.open()`）。差异化逻辑已移至启动脚本：

- JAR 切换：`preload-topling.sh` 根据配置替换 classpath 中的 JAR
- 环境变量：启动脚本设置 `TOPLINGDB_EASY_MIGRATE_CONF`
- Native 库：启动脚本设置 `LD_PRELOAD` / `LD_LIBRARY_PATH`

Provider 抽象层不再承载实质性逻辑，因此可安全移除。

## 何时考虑恢复

- 需要在 Java 层根据不同引擎执行不同的 open/close 逻辑
- 需要支持第三种 RocksDB 变体（如 SpeeDB、TerarkDB 等）
- 需要在运行时动态切换引擎（而非启动时确定）
