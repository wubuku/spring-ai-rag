# 后续改进 TODO

> 📖 [English](TODO.md) · [中文](TODO-zh-CN.md)
>
> 最后复核：2026-08-19。本文只记录当前代码和正式 API 之外的后续事项，不代表
> 已发布的 API 能力。

## 文档生命周期与派生索引后续项

| 项目 | 优先级 | 当前缺口 |
|------|--------|----------|
| 权威来源全量快照对账 | 本批已交付 | V42 API 和 reference client 支持有界权威 run、preview fingerprint、删除保护和 reconciliation tombstone |
| 外部文档原子 Collection 迁移 | P1 | 普通 upsert 改 `collectionKey` 会寻址另一身份；当前只能 tombstone + 新建，不能保留内部 ID、版本历史或避免短暂重复/空窗 |
| 历史版本受控恢复 | 本批已交付 | 开启 feature flag 后，本地 `FULL` 快照可恢复为新 revision；外部文档仍由来源系统负责 |
| 本地 chunk/full-text 与远程向量解耦 | P1 / 较大 | 全文检索仍依赖 `rag_embeddings` 与 `COMPLETED` Profile；provider 故障会同时中断关键词检索 |

### 当前边界

- 外部同步支持 webhook/CDC 增量投递和权威 Sync Run。一次批次不完整时不得按
  missing 推断删除；只有显式声明的安全快照模式才能启用 tombstone。
- 当前外部地址仍是 `collectionKey + sourceNamespace + externalId`。在独立 tenant/
  connector 授权边界出现前，不把唯一约束降为全局 `sourceNamespace + externalId`；
  跨 Collection 移动应使用同时校验源/目标 ACL 的显式原子操作。
- 版本恢复不能直接回退计数器或覆盖历史；当前操作会创建新 revision、新完整快照，并复用
  现有 mutation impact、持久化 job 和 commit fencing。
- 只有 `snapshotCompleteness=FULL` 的历史版本才具备完整恢复资格；旧兼容快照只用于审计。
- 后续本地全文派生必须保持“旧正文立即退出”的一致性，同时允许新正文先进入
  `KEYWORD_ONLY`，远程 embedding 成功后再进入 `READY`。
- 所有并发协调继续使用条件 DML/CAS、唯一约束、lease 和有界重试，禁止显式悲观锁。

剩余实施范围和批次顺序以
[当前活跃规划](drafts/README-zh-CN.md) 为准；已发布契约仍以
[REST API](rest-api-zh-CN.md) 和
[外部文档同步 Client 指南](external-document-sync-client-guide-zh-CN.md) 为准。

## `EACH_COLLECTION` 召回覆盖模式

| 项目 | 优先级 | 状态 |
|------|--------|------|
| 在显式选中的多个 Collection 中保证每个 Collection 都有机会贡献候选 | 非紧急 Backlog / 无目标版本 | 延后，暂不阻塞当前版本 |

### 当前行为

当前支持的 `SELECTED_COLLECTIONS` 是**范围过滤**：多个 `collectionKeys` 组成一个候选
并集，所有候选在同一条混合检索链路中竞争全局 top-k。某个 Collection 没有与查询
足够相关的内容时，可以不返回该 Collection 的结果。这是当前有意提供的语义，详见
[REST API：Collection 检索范围语义](rest-api-zh-CN.md#collection-检索范围语义)。

`ANY_COLLECTION` 也不是逐 Collection 召回。它只表示“检索所有已归属某个 Collection
的可检索文档”；它与 `EACH_COLLECTION` 解决的是不同问题：

- `ANY_COLLECTION`：候选范围是什么？
- `EACH_COLLECTION`：候选范围确定后，结果是否要对每个 Collection 做覆盖保证？

因此，当前 API 不接受 `EACH_COLLECTION` 或类似的 `collectionCoverageMode` 字段。
调用方不应自行假设每个 selected Collection 都会出现在结果中。

### 为什么暂不实现

这不是给现有范围过滤增加一个枚举值，而是独立的召回与排序策略：

1. 每个 Collection 需要独立候选配额或独立 top-k。
2. 多 Collection 查询会产生 bounded fan-out；不能把每个 Collection
   无界地展开成一次数据库/模型调用。
3. 各 Collection 的候选必须统一去重、融合和重排，结果数量不能简单相加。
4. 没有相关内容的 Collection 不能为了满足“每个都有结果”而强行填充低质量结果。
5. 需要额外的延迟、候选数量、覆盖率和质量指标，否则无法判断该模式是否值得其成本。

当前直接下推 `d.collection_id` 的过滤已经解决了“多个大型 Collection 不应先展开全部
document ID”的主要性能问题；普通的并集检索不需要 `EACH_COLLECTION` 才能正确工作。
因此，在没有明确的产品覆盖需求和质量证据前，实现它会增加复杂度和性能风险，而不会
解决当前范围检索的核心问题。

### 何时重新评估

满足以下任一条件时，可以为它单独立项：

- 产品明确要求“每个用户选中的知识库都必须有机会出现在答案依据中”；
- goldenset 或线上指标显示，全局 top-k 长期被少数大型 Collection 占满，导致其他
  Collection 的有效结果系统性不可见；
- 调用方能够接受有限的 selected Collection 数量、额外延迟和候选预算；
- 已经定义“没有相关内容时允许某个 Collection 返回零个结果，不强制填充”的产品语义。

### 未来实施的硬约束

后续实施时应保持以下边界，避免破坏当前 API：

- 新增独立的覆盖字段，例如 `collectionCoverageMode`，不要重定义
  `collectionScopeMode`；后者继续只表达候选范围。
- 首版只允许 `EACH_COLLECTION + SELECTED_COLLECTIONS`，不开放
  `ANY_COLLECTION + EACH_COLLECTION`。后者可能隐式遍历大量 Collection。
- 首版设置较小且明确的 Collection 上限，推荐不超过 20；候选数量和并发也必须有
  bounded budget。
- 查询 embedding 只计算一次；每 Collection 的候选召回、统一 fusion/rerank、
  去重和最终 top-k 必须有明确顺序。
- 某个 Collection 无相关候选时返回“该 Collection 无命中”的事实，不强行制造低质量
  结果。
- 增加 PostgreSQL 集成测试、延迟/候选数指标、质量 goldenset 和 WebUI 高级选项；
  不将其作为默认模式。

### 关联文档

- [项目上下文：Collection 当前语义](project-context-zh-CN.md#collection-当前语义)
- [REST API：Collection 检索范围语义](rest-api-zh-CN.md#collection-检索范围语义)
