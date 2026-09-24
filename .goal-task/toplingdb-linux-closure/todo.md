# 待办与分工

状态：`进行中`。勾选只代表已有可复核证据，不代表整个 G 组完成。

## 开发机优先

- [ ] 基于 `0d2d334c5214b2dc467c9368b28035135a9d2386` 或其后继 commit，继续解决 PR #179 的未完成代码边界。
- [ ] 复核 `GraphIndexTransaction.constructSearchQuery` 的 incoming branch 条件：当前本地测试通过，但 HBase/IN 分支回归风险仍需在最终 SHA 验证。
- [ ] 复核 `ScanBatchResponse3` 的 executor rejection 路径：listener 异常逃逸仍是后续 triage 项。
- [ ] 复核 `HgStoreStreamImpl.getState()` 在 scan drain 期间返回 `UNAVAILABLE` 是否符合客户端契约；当前不阻塞提交。
- [ ] 保持 Store shutdown 的 fail-closed 语义：stuck callback 应保持 shutdown pending，不能为了超时测试强行关库。
- [ ] 如需改动同一分支，先 fetch `org/toplingdb`，正常 merge/rebase 后非 force push。

## 本机优先

- [ ] 继续标准 RocksDB 的 1+1+1 全生命周期：停止/重启、同 PVC recreate、图删除重建、truncate、snapshot/restore 到新卷。
- [ ] 继续 3+3+3 行为验证：leader/follower 退出、Server 副本切换、PD/Store 多数派丢失与恢复、确认写入一致性、恢复时间。
- [ ] 在独立 namespace 中完成 Topling 单机、1+1+1、3+3+3 的 native mapping、生命周期和功能验证。
- [ ] 完成两个 PD/Store provider 混合组合及错误 provider 复用拒绝。
- [ ] 执行 Loader 固定百万点子集导入、失败/重试/计数、ID/方向/邻接和重启核对。
- [ ] 核心功能全部通过后，再启动 benchmark；benchmark 必须绑定最终候选 SHA 和固定资源。
- [ ] 每轮只更新本目录中的实测文档或 PR 评论，不把原始证据大文件提交进仓库。

## 尚未关闭的验证缺口

- Topling runtime 的真实服务结果仍缺。
- 3+3+3 目前只有 pod Ready 和 JNI 采样，HA/恢复行为未完成。
- 1+1+1 的 155/0/0/50 API 结果只覆盖标准 RocksDB，不能替代 HStore 全生命周期和 Topling。
- macOS ARM/Intel 的 Cypher 失败仍需对应平台最终 SHA CI 验证，Linux 结果不能替代。
- benchmark 尚未开始，不能声称 Topling 性能收益。
