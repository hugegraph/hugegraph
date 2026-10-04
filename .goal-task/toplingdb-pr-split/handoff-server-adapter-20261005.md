# Server TP adapter 高信息交接

状态：用户准备切模型，已停止新增实现和验证。本 lane 没有运行新 tp-core 的格式、编译、单测、JNI 或服务验收，也未 commit/push/resolve。

## 权威位置与基线

- 新工作区 `/Users/zhu/github/hugegraph-topling-split-evidence/resume-20261005/tp-core/source`。
- 基线 Apache master `91fd925d`；缓存 tree SHA `91fd925d4cdcfb6503a5bca3244909dfcf18d57c`。完整原始包 `/Users/zhu/github/hugegraph-topling-split-evidence/resume-20261005/tp-core/master-91fd925d.tar.gz`，原始manifest/tree同目录。
- 本 lane只改下表5个文件。`OpenedRocksDB.java`、P2事务/扫描、P3恢复协议没有修改；`UnitTestSuite`也没有改，既有RocksDBSessionsTest已登记。
- 精确5文件SHA保存在本目录 `server-adapter-hashes.json`；相对91fd925d的完整局部patch为 `server-adapter.patch`。

## 文件与当前SHA-256

| 路径 | SHA-256 |
| --- | --- |
| `hugegraph-server/hugegraph-rocksdb/src/main/java/org/apache/hugegraph/backend/store/rocksdb/RocksDBOptions.java` | `acb9ce8a879ea3a30fce7039b313572d6c009709c538b98d1e1bc3645f6e58d3` |
| `hugegraph-server/hugegraph-rocksdb/src/main/java/org/apache/hugegraph/backend/store/rocksdb/RocksDBSessions.java` | `2235fa327dc6a60c41069e6c5b1337f2058c89a0346ef5e758cef56908445e36` |
| `hugegraph-server/hugegraph-rocksdb/src/main/java/org/apache/hugegraph/backend/store/rocksdb/RocksDBStdSessions.java` | `f3c3cfa8ea02b46ffe753bd0123596f1c7987b0c892b6cc564eda5d30a805166` |
| `hugegraph-server/hugegraph-rocksdb/src/main/java/org/apache/hugegraph/backend/store/rocksdb/RocksDBStore.java` | `1a62de3768cab94150fccc2e7e9b4bb484275ae1222992cf4a9ff605771a0a7d` |
| `hugegraph-server/hugegraph-test/src/main/java/org/apache/hugegraph/unit/rocksdb/RocksDBSessionsTest.java` | `3ca915cefce98f463d31c3f62bc0500a962719243c27f454a6f13f07dd8aab78` |

## 已落地行为（全部尚未在新基线验证）

- RocksDBOptions增加`rocksdb.provider`，合法值rocksdb/topling，默认rocksdb。
- RocksDBStdSessions两个实际私有open入口在选项/native开库前调用`RocksDBRuntime.verify(config.get(RocksDBOptions.PROVIDER))`。仍是master的直接OpenedRocksDB字段，没有AtomicReference/RecoveryLock/reopen协议。keyRange首次invalid先status；末次invalid先status再明确失败；RocksDBException保留cause。
- RocksDBSessions增加clearTables：先收集全部范围，然后range删除+末key删除；commit或rollback；配对useSession/close。增加databaseOpened四行桥接，仅调用master已有protected opened。
- RocksDBStore从配置保存toplingProvider；TP truncate拒绝SST生成session、先discard pending writes、保留CF执行clearTables，再init恢复backend版本。标准truncate保留master的clear→init→reset顺序。优化table disks/OLAP沿用既有映射处理。
- Store.opened使用databaseOpened，避免新TP truncate检查创建额外线程本地session。SystemStore.init写backend-version时局部useSession/close归还本次借用；没有引入通用生命周期框架。
- 既有SessionsTest增加10个测试入口：TP/标准健康truncate；TP/标准空表及范围外pending writes；TP/标准backend-version保留；first/last key status故障。健康用例覆盖空表、单key、binary边界、CF handles保留和重写。status用例走真实truncate→clearTables→keyRange，只有失败RocksIterator边界替身，真实DB/健康CF仍在。反射已适配master直接OpenedRocksDB字段。

## 与其他lane的接口/所有权

- P4 lane负责Commons helper、共享launcher、PD/Store启用。已对齐：`org.apache.hugegraph.util.RocksDBRuntime.verify(String configuredProvider)`，static void；只核rocksdb/topling合法性与实际runtime身份，不写文件，不加marker/lock/祖先目录/leaf/mount框架。
- 写交接时helper状态：存在（由 P4 lane 所有；未审阅/验证完整实现）。不要由Server lane抢写其文件。
- root负责docs/CI与整体组合验证；所有lane共享工作区，不可回滚其他人的改动。

## 恢复时先做的检查

1. 复读本交接与root总context，核5文件hash和共享helper当前状态；先确认最新范围再改动。
2. 本次测试是标准JNI执行TP adapter分支，不是真实TP JNI验收。BaseRocksDBUnitTest固定FakeObjects默认rocksdb；直接替换TP JNI会因runtime/config不符被拒绝。真实TP需要显式provider=topling的独立driver，不能为“测试通过”放宽runtime guard。
3. 从旧P4复制的adapterStore测试helper末尾仍有“fixture也可原样在TP JNI上运行”的旧注释；上述默认配置前提使该表述不准确，恢复后应收窄注释。当前遵守停止指令没有继续修文。
4. 需对最终5文件+Commons组合先format/whole clean compile/相关测试，再独立审查和三组件真实TP启动读写关闭重启。尚未得到任何新基线运行结果。
5. 重测试统一使用`/Users/zhu/github/hugegraph-topling-split-evidence/run-exclusive.py`并与root协调。同一时间一批重任务。

## 旧P3恢复工作与当前任务隔离

- root已将P3 source007五文件修复发布到#263，报告head `f8b29c82`，4条comments已closed；该远端状态来自root通知，本lane此刻未再次查询。旧P3不进入新TP core。
- 旧证据`E/p3-resume-20261005/source-007`、`payload-007.json`、`p3-freeze-007.json`、`validation-007/receipt.json`：SHA匹配、exit0/source_changes空；完整恢复suite、真实WAL halt/restart、parent-bind通过。production全编译在validation006，007只改fixture。
- 旧source008含手工alias解析器，已确认symlink/..语义错误，绝不可发布/搬进TP core。root已取消其排队，不占重锁。本lane不继续修复008。
- P3 base拆离P2的最小bridge报告是`p3-base-bridge.md`；TP去P3依赖报告是`p3-dependencies.md`。这些是上下文，不是继续恢复协议实施的授权。
