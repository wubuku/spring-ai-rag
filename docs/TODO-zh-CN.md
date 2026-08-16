# 后续改进 TODO

> 📖 [English](TODO.md) · [中文](TODO-zh-CN.md)
>
> 最后复核：2026-08-16。本文只记录当前代码和正式 API 之外的后续事项，不代表
> 已发布的 API 能力。

## `EACH_COLLECTION` 召回覆盖模式

| 项目 | 优先级 | 状态 |
|------|--------|------|
| 在显式选中的多个 Collection 中保证每个 Collection 都有机会贡献候选 | P2/P3 | 延后，暂不阻塞当前版本 |

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
- 已经定义“没有相关内容时允许少于每个 Collection 一个结果”的产品语义。

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

- [多 Collection 检索范围调研](drafts/2026-08-15_MULTI_COLLECTION_RETRIEVAL_SCOPE_RESEARCH.md)
- [多 Collection 检索范围实施规划](drafts/2026-08-16_MULTI_COLLECTION_RETRIEVAL_IMPLEMENTATION_PLAN.md)
- [项目上下文：Collection 当前语义](project-context-zh-CN.md#collection-当前语义)
- [REST API：Collection 检索范围语义](rest-api-zh-CN.md#collection-检索范围语义)
