# 拆分方案

源码 head `f6ce602cc257ea52ad96aef6b1a7aae05bf666f3`，org base `176fb56dd747ef0f60a126cf721aa12d17627c31`。
当前 API 相对 org base 返回 198 个变更文件，与旧交接的209文件统计不同；以当前比较结果为准。
清单共404项：392个实际patch hunk，12个binary/no-patch条目。
[逐文件/修改块清单](split-map.tsv)覆盖全部文件及源patch hunk；混合hunk明确列各方法/配置归属，构造时拆分，不整块机械应用。
清单中的 DEFER 不是已完成或丢弃：本轮构造若证明它属于新增/放大回归或必要验证支撑，即重新归属并验收。

## 单元与依赖

| 单元 | 构造基线 | 行为和配套验证 |
| --- | --- | --- |
| P1 标准RocksDB版本对齐 | org master | PD 6.29.5、Store 7.7.3统一8.10.2，Server版本不变；移除已删除metrics枚举；同步LICENSE/清单，新增跨版本旧数据验证和升级/监控说明 |
| P2 事务及关闭 | org master | FINISHED清理、auth冷schema关联删除、index当前schema、线程session最后关闭；Store gRPC/TTL排空及stop状态契约；标准Core/API、并发和真实关闭验证 |
| P3 Snapshot/WAL | P2（真实依赖） | databaseOpened接口来自P2；recoveryLock、native关闭/重开移交、pending/WAL发布恢复完整保留；标准故障矩阵和挂载说明 |
| P4 完整TP接入 | 必要P1/P2/P3前置 | provider选择/truncate、所有根预检、JNI/ABI、三组件包、Docker/CI、真实TP验收及产品文档 |

P1与P2独立构造并已分别完成相应构建和定向验证；完整服务及CI门槛仍需分别满足。P3调用P2新增的无副作用状态查询，旧closed()只看session计数，不能替代native ownership；不为名义独立复制公共接口。
P2 Server与Store生命周期互不直接依赖，如后续review确需减小PR，可按完整子系统调整数量，不拆开各自关闭链。

## 混合修改块

- 根POM：P1引入rocksdb.version并将PDStore JRaft升至1.3.14：PD core实测1.3.13调用8.10.2已删除的blockCacheCompressedSize而失败。Server原有1.3.11暂保留，若新路径依赖另证；HgStoreStateMachineTest的Iterator适配随1.3.14纳入。P4的资源排除另行处理。
- RocksDBSessions.databaseOpened、RocksDBStore.opened和无session副作用测试属于P2。
- OpenedRocksDB全部恢复锁/关闭改动属于P3；RocksDBStdSessions的两个open路径、reload/forceClose/resume一并处理。
- RocksDBStore的typed contention、cached-owner copy/register和resume写锁属于P3；provider/truncate属于P4。
- RocksDBSessionsTest按opened、WAL/snapshot、清表/TP方法拆；UnitTestSuite按实际单元注册测试。
- LICENSE/known-dependencies按实际依赖拆，不照搬来源重复条目或与本单元无关的补录。
- runtime workflow依赖preload-topling等脚本，不整块提前搬入P1/P2/P3；必要的标准升级CI使用独立入口。

## 特殊验收与文档

- P1新增fixture覆盖旧版本创建SST/WAL→8.10.2读写→再次重启，保留原样本与失败。JNI fixture不能替代PD/Store真实元数据、图/分区及JRaft日志/服务恢复。
- P1不宣称原地降级；任何不可逆兼容问题先保留复现，给迁移/回滚方案再交用户判断。
- P2的请求清理必须配套project/user关联删除与无关对象保留测试；Store关闭移除旧leader transfer等待，需要真实多节点/重启验证，不能只看mock。
- P3的Java开库拒绝单DB挂载影响默认RocksDB，应在该PR提供通用恢复/挂载说明。JVM前全graph预检尚在P4时不得提前宣称具备它。
- P3不承诺全图原子恢复、断电持久性或不合作旧进程的互斥保护；pending/checkpoint/锁材料不得通过删文件绕过。
- P4保留精确known-CF-assertion边界，其它native失败必须失败；真实JAR/native maps/hash独立核验。实验JNI交付及许可证闭包需在PR review中明确，正式发布流水线后置。

## 交付顺序

按用户最新要求，完成可审查步骤和适用提交前验证后及时发布非Draft PR，显式保留未完成服务/CI门槛；不必等待四个单元全部验收结束。P1/P2独立指向master；P3已经stack在P2上；P4使用包含P1与P3的临时前置组合分支以保持增量diff，待前置取得明确合入确认并合入后刷新/调整base，必要时正常合并master以建立祖先关系，不重写历史。标题编号(1/4)至(4/4)表示功能单元，不表示线性依赖。
各PR只在对应仓库取得明确确认后合入。Apache已有同源整合PR #3134，保留为审计参考，不另行修改、合入或关闭。
Apache master当前比org多`02628ed5`（Struct TokenGenerator/AuthOptions去重）；每次上游交付重新比较，保留上游变化。

## 集成分析审查

已完成1名独立只读分析审查，确认清单无漏项/重复，P1最小核心可构造。P4尚未冻结：PD IndexAPI/StoreNodeService/对应测试、HugeSecurityManager白名单/测试均为通用生产行为组，必须先证明属于本次新增或放大的必要路径，否则独立跟进，不以P4兜底夹带。
配置隔离Utils/ConfigPathTest成组，若前置验证使用config_path则提前核查是否需要；cluster端口、进程管理及SLF4J三组测试支撑分别追踪。

## P4依赖交付待审方案

当前精确JNI SHA256为`86eb1bd3d9f84ef0dddd3fe95c640a6f145ca2d5a26a5ba628f1f298a2031fae`，本机解包确认仅linux64 native、无LICENSE/NOTICE/POM。固定producer `31afa28f...` 的README明确core可选Apache2/GPLv2，POM同时列两者；workflow会动态取得SidePlugin，所以不能把core许可直接当精确binary闭包。
P4已实现上游源码不检入opaque JNI、由显式路径和校验和输入构造可选TP包/镜像；标准发行保持不带TP，原org整合分支保留实验产物。外部输入与打包失败保全修复已通过静态复审及shell故障注入，全仓干净编译通过；完整测试和真实native验收尚未结束。此方式不免除精确二进制的来源与许可证门槛，缺少完整材料时不发布可再分发产物。正式producer发布链继续独立后置。

## 构造后已确认修正

- P3拆分曾漏掉一个相邻hunk，现已恢复cached-owner存活检查、同锁copy/open/register及typed recovery错误不走CF文字fallback；完整open方法与来源字节一致，三人静态复审完成；恢复/WAL定向测试、多图及真实bind mount拒绝已验证，服务级故障演练仍待完成。
- P2扫描关闭路径的executor拒绝/异步失败会在onError后继续空页/成功完成；现统一失败终态、串行observer回调、跳过当前线程中断，并在正常取消前先确立完成终态。补5项定向回归及Store stop容器CI入口。来源类5/5失败、当前类5/5通过的probe使用缓存peer类，仅是局部红绿证据；三人静态复审已通过，仍需最终源码完整构建及真实服务验收。
