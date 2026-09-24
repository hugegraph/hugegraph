# 本机实测分项

状态标记只放本文件。未勾选表示当前 SHA 尚无完成证据。`9aba` 历史结果仅是线索。

## P1 历史标准集群

- [x] 1+1+1 停止、重启，并核对重启前已确认写入。仅绑定历史镜像 `closure-std-9abae9dbaaa1`，不算当前 SHA。证据 `evidence/helm-standard-111-stop-restart-confirmed.json`。
- [ ] 1+1+1 删图重建、truncate、snapshot/restore 到新卷。
- [ ] 3+3+3 三个 Server 的功能、认证和写入一致性。
- [ ] 3+3+3 leader/follower 退出、Server 副本切换、多数派丢失与恢复、恢复时间。
- [ ] 3+3+3 网络分区。Chaos Mesh 不可用时后置，不阻止 Pod 级 HA。

历史线索：1+1+1 API 155/0/0/50，Store 同 PVC 重建一次；3+3+3 为 9/9 Ready 和 JNI 采样。镜像均为 `closure-std-9abae9dbaaa1`。

## P2 当前 SHA 构建

- [ ] 从执行时的 `org/toplingdb` HEAD 构建隔离的标准镜像和 Topling 镜像。
- [ ] 证明 Topling 进程实际映射 Topling JNI，标准镜像不包含或不会静默加载 Topling。
- [ ] 用新 namespace 加载镜像，不升级两个 `9aba` namespace。

## P3 当前 SHA 功能

- [ ] 标准 RocksDB 单机功能。
- [ ] 标准 RocksDB 1+1+1 与 3+3+3 功能。
- [ ] Topling 单机、1+1+1、3+3+3 功能。
- [ ] 两个 provider 分别覆盖 schema、CRUD、事务、索引、分页或批量、Gremlin/Cypher、多图和认证正反例。

## P4 生命周期与 provider

- [ ] 两个 provider 的停止、重启、异常退出、同数据恢复、删图重建、truncate、snapshot/restore。
- [ ] PD/Store 两种混合组合可按预期运行。
- [ ] 错误 provider 复用原数据被拒绝，原数据不变。

## P5 当前 SHA HA

- [ ] 标准 RocksDB 3+3+3 的副本退出、切换、分区、多数派恢复、写入一致性和恢复时间。
- [ ] Topling 3+3+3 的同样证据。
- [ ] 现有 HA Compose 与 Helm 配置约定对齐并分别留证。

## P6 Loader

- [ ] 使用已准备的 LAW Twitter-2010 固定百万点子集完成导入。
- [ ] 核对失败重试、计数、ID、方向、邻接和重启。
- [ ] 全量导入仅在容量评估通过后附加执行，不阻塞固定子集结论。

数据线索：41,652,230 个原始 ID 已检查；固定子集有 2,098,771 条边、9 个自环、0 条重复边。导入尚未执行。

## P7 Benchmark

- [ ] 核心功能通过后，按同 SHA 和固定资源完成标准/Topling 至少 3 轮对照。
- [ ] 保存原始结果和统计，不把未跑项目写成性能收益。

## 阻塞修复

- [ ] 测试暴露的可复现缺陷在 `toplingdb` 分支修复并附回归测试。
- [ ] 行为修复完成 3 名独立审查和必要重审后再勾选。
- [ ] 保持 Store shutdown fail-closed，不为超时测试强行关库。

## 明确不由本机构成完成

- macOS ARM/Intel 最终 SHA 的 Cypher CI。
- 发行审批和公共仓库发布。
