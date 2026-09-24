# 本机实测分项

状态标记只放本文件。未勾选表示当前 SHA 尚无完成证据。`9aba` 历史结果仅是线索。

## 执行顺序

完成标记仍只看下面的复选框。本机按这个顺序继续：

1. 标准 `e109012a0` 1+1+1 的写入、12.239 秒 Store 替换、JNI 不变和 Server 重新解析后的读回已留证。标准和 Topling 的 `e109` 3+3+3 都已写入并被三台 Server 读到。标准删图重建和 truncate 已留证。Store 重连修复已在标准 1+1+1 的 overlay Server 上复测通过，但仍未审查、未提交，也不是完整 SHA 镜像。标准和 Topling 的 e109 3+3+3 都有单 Store leader 删除和 Server 副本删除证据。标准 e109 3+3+3 在 overlay Server 上完成了两台 Store 同时删除和恢复。标准和 Topling 的 e109 3+3+3 都在 overlay Server 上完成了两台 Store 同时删除和恢复。标准 1+1+1 固定子集已导入，32 个邻接样本和 9 个自环在 Store 重启前后都通过，计数是 1000000:2098771。标准和 Topling 的 e109 1+1+1 都完成了删图重建和 truncate。标准和 Topling 的 e109 3+3+3 都有 PD leader 删除证据。两边的 HStore snapshot_create 仍是 500 UnsupportedOperationException。
2. P6：Topling 导入、重启前样本、Server 替换后的 32 样本和 `1000000:2098771` 计数已经留证。Store 客户端没有自行改连新 IP，所以重启项仍不勾选。
3. P3 剩余：标准 3+3+3、两个 provider 的单机，以及 API 155 没有覆盖的功能矩阵。
4. P4 剩余：异常退出、PD/Store 混合组合、错误 provider 拒绝原数据。snapshot 没有 CRD 时保持带解除条件的后置。
5. P5 剩余：标准 3+3+3 HA，补齐 Topling 尚未覆盖的切换，并对齐现有 HA Compose 与 Helm。网络分区只记录单节点 kind 限制。
6. P1 中没有被当前 SHA 取代、且不需要升级历史 namespace 的缺口。
7. P7：核心功能通过后，按同 SHA 和固定资源做至少 3 轮标准/Topling 对照。未跑项目不写性能收益。

## P1 历史标准集群

- [x] 1+1+1 停止、重启，并核对重启前已确认写入。仅绑定历史镜像 `closure-std-9abae9dbaaa1`，不算当前 SHA。证据 `evidence/helm-standard-111-stop-restart-confirmed.json`。
- [ ] 1+1+1 删图重建、truncate、snapshot/restore 到新卷。删图重建和 truncate 已在 `9aba` 通过；snapshot/restore 第 1 次失败，未勾选。证据 `evidence/helm-standard-111-lifecycle.json`。
- [ ] 3+3+3 三个 Server 的功能、认证和写入一致性。顶点和边已在三个 Server 上读到；错误口令返回 401。DEFAULT 图空间 `auth=false`，无角色新用户可以建图和删图，角色拒绝尚未验证。证据 `evidence/helm-standard-333-write-consistency.json`、`evidence/helm-standard-333-auth-function.json`。
- [ ] 3+3+3 leader/follower 退出、Server 副本切换、多数派丢失与恢复、恢复时间。Store 多数派 Pod 删除已有证据。网络分区未做：单节点 kind 没有独立网络路径，本机也没有 Chaos Mesh。解除条件是多节点集群和 Chaos Mesh CRD。证据 `evidence/helm-standard-333-store-majority.json`。
- [ ] 3+3+3 网络分区。本机没有 Chaos Mesh CRD 或 namespace，后置；不阻止其他测试。

历史线索：1+1+1 API 155/0/0/50，Store 同 PVC 重建一次；3+3+3 为 9/9 Ready 和 JNI 采样。镜像均为 `closure-std-9abae9dbaaa1`。

## P2 当前 SHA 构建

- [x] 从 cc143 上下文构建标准与 Topling 镜像。标准 PD/Store/standalone 与 Topling PD `3bce8e03d227`、Store `195cad38c8a7`、standalone `52be45a93334` 已分开。HStore server 的 `closure-top` tag 只是标准镜像 `14eb8067b416` 的别名，不含 Topling `.so`。证据 `evidence/build/topling-image-acceptance-cc14333f0.json`。
- [x] 证明 `hugegraph/pd:closure-std-cc14333f0`（`a0d9264b2ea2`）等于 cc143 产物。Maven 产出校验和命中原层，14 个运行文件与 `9aba` 清单一致；源码差异仅在跳过的测试。证据 `evidence/build/pd-image-acceptance-cc14333f0.json`。
- [x] 证明 Topling 进程实际映射 Topling JNI，标准镜像不包含或不会静默加载 Topling。标准 PD/Store 映射 `8b8fb2ed3ab69581cf1897bd116d484f073e66e9a5b6d61effc7b4c783d66dff`；Topling PD/Store 映射 `library/librocksdbjni-linux64.so` 的 `c25ff6e676290db6db47df0954640aa609c391450ec90e1a8eec1f87e174dd38`。证据 `evidence/helm-standard-cc143-111-runtime.json`、`evidence/helm-topling-cc143-111-jni.json`。
- [ ] 用新 namespace 加载镜像，不升级两个 `9aba` namespace。

## P3 当前 SHA 功能

- [x] 标准 RocksDB 单机功能。镜像 `hugegraph/hugegraph:closure-std-cc14333f0`（`35267423defa`），JNI `8b8fb2ed…6dff`，provider=rocksdb。写入可读，docker restart 后 9.426 秒仍读到 `std-persist-1790266399`。API 套件 13 个失败都是单机不支持 GraphSpace。证据 `evidence/standalone-cc143-function.json`、`evidence/standalone-std-cc143-restart-clean.json`。
- [x] 标准 RocksDB 1+1+1 与 3+3+3 功能。1+1+1 与 3+3+3 API 都是 155/0/0/50，镜像 `closure-std-cc14333f0`，JNI `8b8fb2ed…6dff`。3+3+3 三台 Server 读到 `std333-1790265733` 所在图 `std333_fn` 的同一顶点。证据 `evidence/build/helm-standard-cc143-333-api.log`、`evidence/helm-standard-cc143-333-write-consistency.json`。
- [x] Topling 单机、1+1+1、3+3+3 功能。1+1+1 与 3+3+3 API 都是 155/0/0/50，三个 Store 无 mmap WAL 错误。3+3+3 三台 Server 读到同一顶点 `mmapfix-333-1790262197`。单机镜像 `5178c8b35c20` 的 JNI 是 `c25ff6e6…dd38`，`top-alone-1790266203` 重启后仍在。单机 GraphSpace 套件未重跑，标准单机已证明该模式会拒绝。证据 `evidence/standalone-cc143-function.json`。
- [ ] 两个 provider 分别覆盖 schema、CRUD、事务、索引、分页或批量、Gremlin/Cypher、多图和认证正反例。认证：标准和 Topling 的集群与单机上，正确口令访问图列表为 200，错误口令、缺认证、未知用户和已删用户都是 401。`/versions` 不鉴权。DEFAULT 图空间 `auth=false`，无角色用户在 Topling 上建图返回 201，角色拒绝没有生效；开启 auth 会创建 Kubernetes namespace，本次没有改。索引和分页：两个 provider 都创建了非主键二级索引，limit=2 返回 2 条。标准按 title 查询只命中目标顶点。Topling 第一次同样查询返回 Panic，随后 5 次都是 200 且只命中目标。多图隔离、Gremlin 读写和 Cypher 查询已通过。批量写入 3 条都可读；同一批次里夹一条非法属性时返回 400，合法的那条没有留下，之前的 3 条仍在。角色拒绝：在 `hg-closure-top-mmapfix-111` 复用现有 namespace 创建 `closure_roleprobe`（auth=true）后，无角色用户建图返回 403 `User not authorized`，图空间和用户已删除，没有新增 Kubernetes namespace。证据 `evidence/current-sha-role-denial.json`。DEFAULT 图空间 auth=false 的历史行为仍在。证据 `evidence/current-sha-auth-enforcement.json`、`evidence/current-sha-auth-standalone.json`、`evidence/current-sha-index-page.json`、`evidence/current-sha-index-query.json`、`evidence/current-sha-topling-index-panic-retry.json`。

## P4 生命周期与 provider

- [ ] 两个 provider 的停止、重启、异常退出、同数据恢复、删图重建、truncate、snapshot/restore。反向混合的标准 Store 被 `kill -9` 后 restartCount=1，顶点 `rev-1790267261` 在 Ready 翻成 true 前已返回 200，PVC `pvc-5d209bd7` 未变。证据 `evidence/current-sha-store-crash.json`。Topling Store `kill -9` 后 37 秒 Ready，`persist-1790262903` 和 `56862681` 在 Ready 前已可读，JNI 仍是 `c25ff6e6…dd38`。证据 `evidence/current-sha-topling-store-crash.json`。Topling 1+1+1 Store 同 PVC `b9f47929` 重建 12.615 秒后仍读到 `persist-1790262903`，mmap WAL 错误 0。标准 `closure-std-cc14333f0` 的 1+1+1 Store 同 PVC `4f1945d3` 重建 12.796 秒后仍读到 `std-persist-1790263000`，重启前后 JNI 都是 `8b8fb2ed…6dff`，不是 Topling。标准和 Topling 1+1+1 都完成删图重建与 clear/truncate：drop 204 后图 404，重建后旧顶点不在、新顶点可读；clear 204 后图仍在、数据清空、可再写。HStore `snapshot_create` 仍是 500 `createSnapshot`。单机标准和 Topling 的 create/resume 都返回 200，但快照后的写入在重启后仍可读，恢复没有回滚。 2026-09-25 03:25 完整 Topling 镜像 `closure-e109012a0` 上，快照前顶点仍在、快照后顶点不在，证据 `evidence/standalone-e109-top-snapshot.json`。03:31 标准完整镜像 `closure-std-e109012a0` 同样通过，证据 `evidence/standalone-e109-std-snapshot.json`。HStore 仍未通过。证据 `evidence/current-sha-snapshot.json`、`evidence/standalone-std-snapshot-after-restart.json`、`evidence/standalone-top-snapshot.json`。证据 `evidence/helm-current-sha-drop-truncate.json` 与 `evidence/helm-topling-mmapfix-111-drop.json`。证据 `evidence/helm-topling-mmapfix-111-persist.json` 与 `evidence/helm-standard-cc143-111-persist.json`。
- [x] PD/Store 两种混合组合可按预期运行。标准 PD + Topling Store 读到 `mix-1790267151`，JNI 分别是 `8b8fb2ed…6dff` 和 `c25ff6e6…dd38`。Topling PD + 标准 Store 读到 `rev-1790267261`，JNI 分别是 `c25ff6e6…dd38` 和 `8b8fb2ed…6dff`。证据 `evidence/current-sha-mixed-pd-std-store-top.json`、`evidence/current-sha-mixed-pd-top-store-std.json`。
- [x] 错误 provider 复用原数据被拒绝，原数据不变。Topling 镜像打开 `provider=rocksdb` 标记退出 1，标准镜像打开 `provider=topling` 标记退出 1，标记哈希不变。原单机顶点 `std-persist-1790266399` 和 `top-alone-1790266203` 仍返回 200。证据 `evidence/current-sha-wrong-provider.json`。

## P5 当前 SHA HA

- [ ] 标准 RocksDB 3+3+3 的副本退出、切换、分区、多数派恢复、写入一致性和恢复时间。Store-1 leaderCount 5 删除后 22.550 秒 Ready，PVC 不变，旧数据和新写入可读。Server 副本删除期间保留副本继续读写，10.613 秒恢复。多数派：同时删除 Store-0/2 后已提交数据仍可读，故障中新写入超时；Ready 23.102 秒时 leader 合计已是 12，写入仍超时，35.929 秒才写入成功。分区未做：同样只记录单节点限制，解除条件是多节点集群和 Chaos Mesh。证据 `evidence/helm-standard-cc143-333-store-leader.json`、`evidence/helm-standard-cc143-333-server-replica.json`、`evidence/helm-standard-cc143-333-store-majority.json`。
- [ ] Topling 3+3+3 的同样证据。Store-2（leaderCount 5）删除后 51.084 秒 Ready，PVC `52fd8dad` 未变，领导权转到 Store-0/1，旧顶点和恢复后新写入都可读，mmap WAL 错误 0。恢复时间含 GitHub jemalloc 下载卡死后被终止。网络分区未做。多数派：同时删除 Store-0 和 Store-2 后，已提交顶点在故障期间仍返回 200，故障期间新写入超时；Pod Ready 21.596 秒时领导权尚未恢复，稍后 leaderCount 合计 12，新写入 201。Server 副本 `d8dp9` 删除期间，保留的 `vw8q2` 仍读到旧顶点并写入新顶点，10.7 秒后替代副本 Ready。证据 `evidence/helm-topling-mmapfix-333-store-leader.json` 与 `evidence/helm-topling-mmapfix-333-server-replica-direct.json`。
- [ ] 现有 HA Compose 与 Helm 配置约定对齐并分别留证。对照已写入 `evidence/ha-compose-helm-alignment.md`。端口、3+3+3、Store `/v1/health`、Server `/versions` 和默认 RocksDB 一致。PD readiness、启动依赖、反亲和、资源、provider marker、Hubble 和凭据来源不一致，所以不勾选。

## P6 Loader

- [x] 使用已准备的 LAW Twitter-2010 固定百万点子集完成导入。图 `law_twitter_1m`，namespace `hg-closure-top-mmapfix-111`，Loader 退出 0，1000000 点、2098771 边、失败 0。证据 `evidence/loader-law-twitter-1m.json`。
- [ ] 核对失败重试、计数、ID、方向、邻接和重启。2026-09-25 修复前五轮失败数 4、1、1、0、0。白名单提交 `08dd6f4f1` 后，overlay Server 上两轮各 320 次扫描失败都是 0。不是完整镜像重建，仍不勾选。2026-09-25 03:39 完整 e109 namespace 导入成功，重启前 32 样本通过；Store 重建后的复测因旧 IP `10.244.0.128` 超时，不能勾选。失败和重试为 0；重启前 32 个样本与 9 个自环通过。Ready 后立即的大扫描曾 UNAVAILABLE；稳定后 `54148543` IN 为 142 且前 20 匹配。混合扫描间歇 `Panic`。Store 崩溃恢复后又出现 4 次和 2 次，第三轮为 0；失败点立刻重试成功。仍未勾选。证据 `evidence/loader-law-twitter-1m-in142-settled.json`、`evidence/loader-scan-panic-sweeps.json`。
- [ ] 全量导入仅在容量评估通过后附加执行，不阻塞固定子集结论。

数据线索：41,652,230 个原始 ID 已检查；固定子集有 2,098,771 条边、9 个自环、0 条重复边。导入尚未执行。

## P7 Benchmark

- [ ] 核心功能通过后，按同 SHA 和固定资源完成标准/Topling 至少 3 轮对照。
- [ ] 保存原始结果和统计，不把未跑项目写成性能收益。

## 阻塞修复

- [ ] 测试暴露的可复现缺陷在 `toplingdb` 分支修复并附回归测试。
- [ ] 行为修复完成 3 名独立审查和必要重审后再勾选。嵌套 WAL 修复已在 `457295ac8` 提交，e109 Topling 单机完整镜像已证明独立 WAL 回滚；审查结论尚未按当前 SHA 收口。
- [ ] 保持 Store shutdown fail-closed，不为超时测试强行关库。

## 明确不由本机构成完成

- macOS ARM/Intel 最终 SHA 的 Cypher CI。
- 发行审批和公共仓库发布。
