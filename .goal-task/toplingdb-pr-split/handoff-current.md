# TP 三 PR 接管与评论修复 — 2026-10-06

第一轮意见已修复验证。第二轮新增意见的修复已分批推送，独立回归通过；当前组合验证进行中。旧主 chat `01a10749-e675-75f0-a459-fadb8aa43920` 已停止；新主 chat `01a10dc0-9a54-7463-9890-ed318c763f08` 负责后续。P2/P4 已暂停，P3 已完成；结果收拢到 R06/takeover-20261006。

## 当前路径和分支

根 `/Users/zhu/github/hugegraph-server` 是 master，只保存协调记录；功能修复使用独立 archive/source，发布使用 gh Git Data API，无 force/no master 直接推送。

| PR | 分支 | 最新 head | 当前源码 |
| --- | --- | --- | --- |
| 261 生命周期 | task/topling-split-lifecycle | b41b1138fc2519f8acd04ff0317406e4b11d3753 | R06/p261-comments-fix/source |
| 266 TP core (2/4) | task/topling-core-20261005 | a8dd7030fe5738fd6df81c4a4ff5a659feaf3b48 | R06/takeover-20261006/current-core-source |
| 267 恢复 | task/rocksdb-recovery-20261005 | 56f898587b619a96bc593268acc78095184c63a9 | R06/p267-comments-fix |
| 网站510 (2/4) | hugegraph/hugegraph-doc:task/topling-split-docs | 713667a26f2fd926b321bc7a3b521fde6924eed5 | R06/takeover-20261006/standard-preload-fix/website |

E=/Users/zhu/github/hugegraph-topling-split-evidence；R06=E/resume-20261006。完整运行路径、最后两轮决策、关键文件清单、分工及剩余阶段见 R06/takeover-20261006/handoff.md。旧268规范和264拆分源本轮未改。

## 本轮行为与验证

- 261：half-close 后的真实 transport cancel/deadline 独立、幂等处理；DONE 后重复 query 在 iterator 分配前拒绝。两项旧实现负回归成立。新主最终77测试全部通过、零跳过、源码无变化，2735远端blob一致。测试事件顺序竞态仅改同步点，不放宽断言。67个归档遗漏的 export-ignore 文件已用 Git blob API补齐，没有产品改动。
- 266：受控 CodeSource 错误、动态 OLAP DB 清理路由、真实 EasyMigrate 配置探针/校验记录、竞争 JNI 拒绝、fatal heartbeat 独立退出协调、metrics session 正常/异常归还。13文件独立审查、实际Java17格式/全仓clean compile/相关测试/三包构建通过。2731源码blob与ebe4658完整一致。新包HStore六顶点/一边REST/Gremlin、三个组件正常stop、重启数据/索引一致、再次正常stop全部通过，成功容器已删。
- 266 最后新意见：默认/rocksdb 模式也拒绝继承的 RocksDB JNI，保留无关预载和受管runtime切换；Bash3.2/nounset空数组正常。3文件独立审查、旧脚本负回归、新选择回归和真实Java17/JNI单库写读关闭通过，已推6ac5415。完整服务包实测绑定ebe4658；最后变更仅脚本/选择测试/指南，真实JNI另测，不把旧包改称6ac完整构建。
- 267：物理data/WAL身份、危险链接删除前拒绝、不明marker fail-closed、失败恢复同Store重开、残留前缀。真实父目录双bind WAL尾部旧实现丢失/新保留；真实Store重开旧失败/新通过。Java17格式/全仓编译、native70零跳过、MultiGraphs9通过+2既有HStore跳过；2724远端blob与已测候选全部一致。
- 网站510：中英同步说明JDK17准备、配置实效探针、校验记录、两种provider模式的JNI拒绝；发布逐blob核验，未重跑完整网站suite。

第一轮已验证意见全部reply/resolve；第二轮9条新意见已回复并resolve，旧ordering意见保持open；261旧ordering争议保留已回复open，不盲resolve。最终检查CI仍有队列，261/266无失败，267一个cancelled及队列，均不能称CI完成。

## 关键证据

R06/takeover-20261006：publish-*.published.json、core-final-review.json、build-receipt.json、package-remote-binding.json、current-core-source-binding.json、api-regression/runs/takeover-ebe4658-hstore-001/receipt.json、p261-final-002/receipt.json、recovery-published-binding.json、*-comment-receipts.json。最后标准模式修复：standard-preload-fix/{selection-receipt-002.json,native-002/receipt.json,independent-review.json,publish.published.json,comment-receipt.json,website/publish.published.json}。

原P4最终冻结验证：R06/p267-comments-evidence/validation/runs/a021-candidate-002/receipt.json。旧/失败对照与source保留；不得把失败fixture、磁盘失败、native001工具路径错误当作产品通过。清理仅新主38个target和错误bind新建空目录，源码/新包/日志保留，receipt在本轮目录。

## 后续和边界

- 当前head的CI和人工review；不自动合并、不因push/历史CI转移验收。新增意见再逐条核对当前源。
- 261原ordering争议仍open；TTL严格关闭重叠门槛未完成，旧codec问题与master一致，另立范围跟进，原证据R05/p2-runtime-current/harness-80dd/final-runtime-summary。
- core266（2/4）之后是专用发行包（3/4）→ Docker/Compose（4/4），尚未建PR；JNI正式发布/许可链按原约定后置。261/267/268均直接基于master，任一合入后其余同步master并复验，不恢复264整包路线。
- 只用实际Java17，Linuxamd64 Docker在Darwinarm64上模拟，非physicalx86；heavy统一E/run-exclusive.py，共享m2不并发heavy。
- tp-pr定时自动化仍PAUSED，用户无需定时检查；本轮未建立新定时任务。
- 跨树direct message被multi-agentv2限制；旧主也因guard未转达。已在P2context/P4handoff追加发布notice，P4最终报告识别新主82b75b0。旧agents现已暂停；不要宣称直接迁移了agent树。

## 第二轮 review 修复（已推送，组合验证进行中）

W=R06/review-wave-02。三个 PR 最新 head 如上。source 是各发布 job 的逐文件镜像，不把第一轮包/服务验证改称当前 head 的验收。

- 261/266：最后 native session owner 关闭 WriteBatch/WriteOptions；真实 JNI 旧负例失败，新18项全部通过。发布 native-publish/{261,266}。
- 261/266：request cleanup 保留 graph 级 schema/element cache 和失效监听，graph.close 才清理对应 generation。真实 RocksDB schema append、实际 API 更新路径与缓存监听48项通过，旧实现两项准确失败；独立审查无发现。新增测试已注册 UnitTestSuite，文档随分支同步。发布 cache-publish/{261,266}。
- 261/266：三个 Auth cleanup 测试根据实际 graph backend 判定，普通 core-test/rocksdb 不再因缺少 system property 全跳过；OLAP 使用合法 sentinel ID 并验证预期错误来源。实际4项通过，旧 assumptions跳过3项、OLAP保护突变准确失败。发布 minor-publish/{261,266}。
- 267：pending marker 先在同目录写完整并 force，再以 hard link 无覆盖发布。真实部分写入故障后无最终损坏 marker，正常打开/重新恢复成功；四项回归通过，旧实现留下损坏 marker。指南写清硬链接文件系统要求，独立审查的该文档发现已处理。发布 marker-publish。
- 当前由 schema_cache_scope 对组合 Java17格式/全仓编译/相关测试及最新266全部源绑定验证；recovery_marker_publish 随后对当前267完整相关suite验证。统一 heavy 锁。结果未出前不得写组合通过。

当前261 PD Docker CI在RAT插件依赖下载阶段遭Maven Central HTTP502，未到产品编译；单job重跑因workflow仍运行而拒绝。日志W/261-docker-pd-failure.log与ci-network-failure.json，需workflow结束后再重跑该失败job。不是代码错误证据，也不能称CI通过。

最新源码完整绑定：261 b41b含2736 tracked blobs，266 a8dd含2732，267 56f898含2724。原native/cache/minor/marker独立回归已完成；三当前head组合回归按共享锁分别执行，不跨分支冒称通过。9条新增意见回复与PR描述在W/{comment-receipts.json,description-receipts.json}，包含组合验证进行中及旧服务包证据范围。
