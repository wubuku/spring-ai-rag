# 历史规划与实施记录归档

> [English](README.md) | [中文](README-zh-CN.md)

本目录保存已完成、已取消或已被替代的 plan、progress 和阶段性调研。它们用于追溯设计
来源、实施顺序和历史验证证据，**不保证与当前代码保持同步，也不是 Agent 默认阅读入口**。

## 何时阅读

- 调查某项设计为何采用当前形态。
- 追踪一次历史迁移、验收或风险决策。
- 长青文档明确指出需要查看历史证据。

日常开发应先读代码、迁移、`docs/project-context*`、专题 reference/guide、`docs/TODO*`
和 [`docs/drafts/` 当前活跃规划](../README-zh-CN.md)。

## 归档规则

- 保留原始文件名、日期和内容语境；只修复目录迁移导致的相对链接。
- 不继续维护其中的版本号、行号、测试计数或“当前状态”表述。
- 发现仍有效且对日常开发重要的事实时，将其提炼到双语长青文档，而不是复活历史稿。
- 新归档文件使用 `YYYY-MM-DD_` 前缀；同一批 plan/progress 保持可辨认的共同主题名。

## 主要历史主题

- OpenAI 兼容与 API Key：`2026-07-21_OPENAI_*`、`2026-08-14_API_KEY_*`
- Collection、检索范围与 Embedding Profile：`2026-08-15_COLLECTION_*`、
  `2026-08-15_EMBEDDING_*`、`2026-08-16_MULTI_COLLECTION_*`
- JSONB、外部文档同步与文件可追溯：`2026-08-15_JSONB_*`、
  `2026-08-16_EXTERNAL_DOCUMENT_*`、`2026-08-16_FILE_RAG_*`
- Chat、WebUI 与后续功能批次：`2026-08-17_*`、`2026-08-18_*`
- 文档生命周期实施账本：`2026-08-19_DOCUMENT_LIFECYCLE_IMPLEMENTATION_PROGRESS.md`
- 本地关键词/向量派生解耦：`2026-08-19_KEYWORD_VECTOR_DECOUPLING_*`
- 外部文档迁移与派生完整性修复：`2026-08-21_NEXT_HIGH_VALUE_FEATURES_*`
- 外部 Client 接入边界历史规划：
  `2026-08-21_EXTERNAL_CLIENT_INTEGRATION_BOUNDARY_*`
- 稳定受管 API Principal 与共享配额：`2026-08-23_MANAGED_API_PRINCIPAL_HARDENING_*`
- 已被替代的 Token 用量账本规划：`2026-08-23_TOKEN_USAGE_LEDGER_*`
- 已停止、供未来重新评估的 LLM invocation 用量与配置成本可观测性实施候选：
  `2026-08-25_LLM_INVOCATION_USAGE_LEDGER_IMPLEMENTATION_CANDIDATE.md`
- 加权 RRF 检索融合与有界 rerank 候选池：`2026-08-23_WEIGHTED_RRF_RETRIEVAL_*`、
  `2026-08-23_NEXT_HIGH_VALUE_FEATURES_*`
- KNOWLEDGE 多查询扩展预算与有界 fan-out：
  `2026-08-24_KNOWLEDGE_QUERY_EXPANSION_BUDGET_*`
- KNOWLEDGE 多查询证据合并、heuristic CJK 词法重排、标题感知相关性与 Latin/数字边界：
  `2026-08-24_KNOWLEDGE_EVIDENCE_JOINER_*`、
  `2026-08-24_HEURISTIC_CJK_RERANK_*`、
  `2026-08-24_TITLE_AWARE_HEURISTIC_RERANK_*`、
  `2026-08-24_BOUNDARY_AWARE_HEURISTIC_RERANK_*`
- Chat 静态资源知识、运行时 Skill、allowlisted HTTP Tool 与工具感知记忆：
  `2026-08-25_CHAT_RESOURCE_SKILL_MEMORY_EVOLUTION_*`
- 外部投影身份边界、全数据面 ACL 合同、provider 失败保留语义与可复现发布 manifest：
  `2026-08-25_EXTERNAL_PROJECTION_CONTRACT_CLOSURE_*`
- principal 级 `RAG_READ` / `RAG_WRITE` 策略与中央数据面强制授权：
  `2026-08-26_OPERATION_SCOPED_API_CAPABILITIES_*`
- 业务 binding 能力画像、通用 Client 生命周期与真实模型发布验收闭环：
  `2026-08-26_BUSINESS_BINDING_CAPABILITY_PROFILES_*`
- 托管 principal 幂等 provisioning 与运行时能力发现：
  `2026-08-26_MANAGED_PROVISIONING_CAPABILITY_DISCOVERY_*`
- Sync Run 持久化逐项回执、状态过滤和终态稳定游标遍历：
  `2026-08-26_SYNC_RUN_ITEM_RECEIPTS_*`
- 外部接入运行时限制自发现、数据面操作聚合与隐私安全的运维查询：
  `2026-08-27_EXTERNAL_INTEGRATION_OPERABILITY_*`
- Collection 受保护清理、永久 key tombstone、引用内容清除与事件驱动 embedding 唤醒：
  `2026-08-28_COLLECTION_PURGE_AND_RETIREMENT_*`
- 受管 API Principal 到期预警、事务后事件对账、阶段升级与通知 claim：
  `2026-08-27_MANAGED_API_PRINCIPAL_EXPIRY_ALERTS_*`
- WebUI 全站工作上下文、文件可发现性、统一 Dialog 与 Files 工作区收敛：
  `2026-08-28_NEXT_HIGH_VALUE_FEATURES_*`

该列表只帮助历史定位，不代表优先级或当前能力状态。
