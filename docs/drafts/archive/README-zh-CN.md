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

该列表只帮助历史定位，不代表优先级或当前能力状态。
