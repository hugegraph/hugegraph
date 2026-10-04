# TP 拆分暂停交接

状态：用户明确暂停（2026-10-04T23:32:52.255188+08:00，Asia/Singapore）。仅恢复授权后继续；没有新建 goal、自动合入或自动监控。原 Linux goal 继续暂停。已运行的本地验证全部结束，成功容器已移除，静态浏览器服务已停止；GitHub CI 继续异步运行。

## 当前权威入口

- 本文件；主索引 `/Users/zhu/github/hugegraph-server/.goal-task/toplingdb-pr-split/state.md`。
- P2 `/Users/zhu/github/hugegraph-topling-split-lifecycle`，当前功能分支已和发布 head 一致。
- P3 `/Users/zhu/github/hugegraph-topling-split-recovery`，当前功能分支已和发布 head 一致。
- P4 `/Users/zhu/github/hugegraph-topling-split-provider`，当前功能分支为最新前置，保留 46 个未发布候选文件；精确 hash 在 `pause-local-state.json`。未发布候选未通过最终组合验证。
- 网站候选 `/Users/zhu/github/hugegraph-topling-split-evidence/website-docs/full-source`；已发布内容与 `website/source-manifest.json` 13 文件哈希一致。
- 本地、Apache、org master 同步到 `89cd937cb2cea4f08730195d158721ff27065271`。P1 Apache #3265 已合入、org #262 已关闭，不再追加 P1；旧服务升级/metrics/cluster 放入后续相关 PR。

## 已发布与已完成

| 对象 | 当前 head | 已完成 | 仍需门槛 |
| --- | --- | --- | --- |
| [P2 #261](https://github.com/hugegraph/hugegraph/pull/261) | `9a44b2b715f522fa94a0247aa8cf992fadc4074a` | 代码120/Markdown160软换行或整段规范；SCAV2响应/worker/cleanup三态；取消回调隔离；顺序iterator清理；真实wrapper失败黏性/并发close；lazy filter接手后预取。最终12文件已推送。 | 新评论、真实API/服务关闭及当前head CI |
| [P3 #263](https://github.com/hugegraph/hugegraph/pull/263) | `556c41fb9f8bb89d4988e9bd4b0dfb136337c313` | 物理owner登记、关闭失败保留、同lease移交，6恢复文件发布；继承最终P2。 | 新checkpoint/WAL评论、真实服务故障恢复及当前head CI |
| [P4 #264](https://github.com/hugegraph/hugegraph/pull/264) | `36058b0c41621c89b069c4e7290aaa531d68e0c4` | 正常同步master/P2/P3，增量边界保留；额外guard/runtime/launcher/Docker候选保留本地。 | 候选复审/完整验证/发布、所有未解决评论及当前head CI |
| [网站 #510](https://github.com/apache/hugegraph-doc/pull/510) | `6c0f2dafa829d373992702f20321319452704097` | WAL、SUMMARY/sidebar、runtime manifest、停机顺序及EOF修正发布；5条已闭环。 | bare PD/Store所有权1条需最终P4联动，协调两边合入 |

P1旧“Markdown100列”评论已按用户明确规范在下一个P2 PR落实后说明并resolve。来源可追溯 #262 discussion_r4176511971，标记 Source: Coding guidelines；当次review主配置来自Organization UI，但没有直接读UI，不能断言文字是手工配置。P2 `.coderabbit.yaml` 使用原glob覆盖同名继承路径，并保留其他父设置；外部global override仍可能优先，恢复后读新review实际配置。

## 当前证据与边界

- `p2-final-module-003/receipt.json`：format、whole clean compile、Store五profile和Node测试 exit0、source_changes空。ServerSuite88项全通过，包含36query及18wrapper/filter测试。三名独立只读审查V3无阻断，作者修复了两轮真实资源归属finding。
- 两个wrapper用例使用实际RocksDB/RocksIterator，但callback/reference关闭故障为注入；native关闭失败为替身，不能声称真实C++关闭故障已复现。
- `p3-new-master-001/receipt.json`：新P1基线上的恢复/WAL、关闭故障、跨JVM/alias、真实parent bind回归 exit0/hash未变。发布6恢复文件与此payload逐字匹配；最终P2继承不改Native payload。没有宣称最后组合的真实服务验收。
- Linux本地执行为 Darwin arm64 下 amd64 emulation；不能当原生x86服务证据。
- 网站 `website/artifact-provenance.json` 严格Hugo/真实latest assembly/产物校验PASS；`website/browser.log` 14桌面/移动/语言/历史路由/搜索/sidebar测试PASS。链接、50聚焦source和2导航测试通过。14个需Git枚举的旧fixture没有运行。产物metadata标记API快照base+候选，公开head的13文件逐字校验另见 `website/published.json`；不要把metadata base当实际新head。
- `resolved-threads.json` 记录P2两条、P3物理owner一条、网站五条resolve；发布后出现新评论，未核修/未resolve。
- `pause-ci.json` 为最后快照：大部分新head检查QUEUED，P4部分runtime和网站prepare运行中。不能把历史绿色或成功push当当前完成。

## 新评论：恢复后先核，禁止当前合入

完整body与thread IDs在 `pause-remote-review-state.json`，不得仅按标题猜测或自动resolve。暂停前最后读取未解决数量：P2=1，P3=4，P4=13，网站=1。

- P2新 `discussion_r4178230241`：正常gRPC完成也cancel Context，引发虚假的CANCELLED WARN/ERROR；需真实in-process grpc正常完成/取消对照，不能只改日志掩盖。
- P3新P1 `discussion_r4178238846`：pending非消费restore只按canonical固定_temp路径绑定，hardLinkSnapshot可能替换source导致checkpoint代际混合。先核原执行链/数据安全，修复前不能合入P3。
- P3 WAL staging孤儿两条 `4178215791`/`4178238847`：中断留下 `.resume-staging-*`；不要直接删除失败材料，按持锁、正确恢复成功和来源归属设计cleanup。
- P3 `4178215794`：无生产调用的 replaceSeparateWalDirectory 和3个Whitebox测试，需改测试覆盖真实installWal路径。
- P4原8条仍未闭环；其中父marker/leaf symlink已有未验证候选。
- P4新反馈 `4178226959/61/65/68/69`：标准未opt-in部署被mountpoint2.37、data_disks/symlink/include等约束破坏；lib命令行symlink find -P漏JAR；ERR trap泄露大量awk实现；option-path/open-http无实际读取。部分可能被本地旧候选吸收，逐项对照实际source，不能直接判全部已修。
- 网站bare PD/Store文档thread `4176198151` 必须等最终P4实现验证/发布再resolve，且不得独立合入网站。

## 恢复顺序

1. 读当前AGENTS、本文件及主state；用gh重新查询master、每个PR的head/base/checks/全部新comments。全部版本库操作继续gh-only，正常追加/merge，不force，不自动合入。
2. 优先完成P2新反馈及真实API/服务门槛；P2准备好review/合入时及时通知用户，附当前head/验证缺口。现在不能通知为可合入。
3. P3先核checkpoint代际P1，再WAL staging/真实恢复path；保留物理owner实现和当前通过证据。
4. P4候选重冻结/交叉复审，串行shell/native/PDStore tests+标准及Topling打包+真实动态开图/dump/启动/关闭+三Docker构建验收。当前RootRestore的两个缺失import已补并随P3继承吸收；P4特有validateDBDirectoryForOpen提取仍在未验证候选。
5. 逐项合理修复、验证、推送后resolve，网站bare ownership与P4联动；PR合入逐个由用户确认。

## 已准备但没有执行

- `p2-api-source/`：最终P2 source +官方固定tag的jacoco输入。`p2-api-freeze.json`，`api-assets/provenance.json`；ikanalyzer与实际Maven缓存逐字相同。
- `p2-service/`：真实API、scan/TTL正常停机重启driver；`plan.json` 已更新为P1合入后的标准8.10.2/JRaft1.3.14。旧 preflight-976ecc29/next-batches.md 的版本/HEAD已过时，不可原样复用。
- 当前service harness只涵盖普通scan/TTL，没有完整SCAN_V2真实query及强制阻塞callback的30秒timeout/PID保留fixture，恢复后补真实观测点再执行。
- `handoff-repair-20261004/p4-guard-comments/validation.md` 的Linux私有fixture已写；只bash-n，尚未执行。不要把该旧manifest当最新整个P4 source（前置已吸收）。

重任务继续共享锁：`python3 /Users/zhu/github/hugegraph-topling-split-evidence/run-exclusive.py <command>`。同一时间一个重任务/一批业务测试服务；只专属容器/目录，不触碰业务DB、他人容器/进程/端口；不删pending/checkpoint/锁绕过失败。首次失败/当前证据保留，不覆盖原receipt或复用旧数据目录。

## 保留与后置

- 所有旧checkouts/test数据保留于 `master-sync-20261004/pre-sync-checkouts/` 与本目录 `pre-pause-checkouts/`；新P2/P3 checkout是干净发布分支，新P4保留46文件未提交候选。未执行大范围清理。
- `follow-up-items.md`：既有sendData Error协议终态、InnerKeyFilter count旧基数、普通OrderedMultiPartitionIterator关闭失败缓存、旧Server iterator/CF租约及GremlinJob问题单列；不扩展本轮。JNI许可/正式发布链为用户明确后置，不宣称已合规。
- CLI升级本轮没有执行，审查用会话内agents；不沿用旧CLI版本/模型可用性的历史结论。

恢复入口（不自动开始goal）：

```bash
cd /Users/zhu/github/hugegraph-server
cat .goal-task/toplingdb-pr-split/state.md
cat /Users/zhu/github/hugegraph-topling-split-evidence/comment-closure-20261004/handoff-pause.md
gh pr view 261 -R hugegraph/hugegraph --json headRefOid,baseRefName,statusCheckRollup
gh pr view 263 -R hugegraph/hugegraph --json headRefOid,baseRefName,statusCheckRollup
gh pr view 264 -R hugegraph/hugegraph --json headRefOid,baseRefName,statusCheckRollup
gh pr view 510 -R apache/hugegraph-doc --json headRefOid,baseRefName,statusCheckRollup
```
