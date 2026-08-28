# Historical Plan And Implementation Archive

> [English](README.md) | [中文](README-zh-CN.md)

This directory preserves completed, cancelled, or superseded plans, progress
ledgers, and point-in-time research. They support design provenance,
implementation history, and audit evidence. **They are not kept current with
the code and are not a default reading path for Agents.**

## When To Read It

- Investigating why a current design took its present form.
- Tracing a historical migration, acceptance run, or risk decision.
- Following an explicit pointer from an evergreen document to historical evidence.

For daily work, start with code, migrations, `docs/project-context*`, the
relevant evergreen guide/reference, `docs/TODO*`, and the
[current active plans under `docs/drafts/`](../README.md).

## Archival Rules

- Preserve the original filename, date, and context. Only repair relative links
  broken by the directory move.
- Do not continuously update versions, line numbers, test counts, or claims of
  current status in archived files.
- If a still-valid fact matters to daily development, extract it into a
  bilingual evergreen document instead of reviving the historical draft.
- New archived files use a `YYYY-MM-DD_` prefix. Related plan/progress files
  retain a recognizable shared topic name.

## Main Historical Topics

- OpenAI compatibility and API keys: `2026-07-21_OPENAI_*`,
  `2026-08-14_API_KEY_*`
- Collections, retrieval scope, and Embedding Profiles:
  `2026-08-15_COLLECTION_*`, `2026-08-15_EMBEDDING_*`,
  `2026-08-16_MULTI_COLLECTION_*`
- JSONB, external-document synchronization, and file traceability:
  `2026-08-15_JSONB_*`, `2026-08-16_EXTERNAL_DOCUMENT_*`,
  `2026-08-16_FILE_RAG_*`
- Chat, WebUI, and later feature batches: `2026-08-17_*`, `2026-08-18_*`
- Document-lifecycle implementation ledger:
  `2026-08-19_DOCUMENT_LIFECYCLE_IMPLEMENTATION_PROGRESS.md`
- Local keyword/vector derivation decoupling:
  `2026-08-19_KEYWORD_VECTOR_DECOUPLING_*`
- External-document relocation and derivation-integrity repair:
  `2026-08-21_NEXT_HIGH_VALUE_FEATURES_*`
- Historical external Client integration-boundary plan:
  `2026-08-21_EXTERNAL_CLIENT_INTEGRATION_BOUNDARY_*`
- Managed API principal hardening and the superseded token-usage ledger:
  `2026-08-23_MANAGED_API_PRINCIPAL_HARDENING_*`,
  `2026-08-23_TOKEN_USAGE_LEDGER_*`
- Stopped implementation candidate for per-invocation LLM usage and configured
  cost observability:
  `2026-08-25_LLM_INVOCATION_USAGE_LEDGER_IMPLEMENTATION_CANDIDATE.md`
- Weighted RRF retrieval fusion and bounded rerank candidate pools:
  `2026-08-23_WEIGHTED_RRF_RETRIEVAL_*`,
  `2026-08-23_NEXT_HIGH_VALUE_FEATURES_*`
- KNOWLEDGE multi-query expansion budget and bounded fan-out:
  `2026-08-24_KNOWLEDGE_QUERY_EXPANSION_BUDGET_*`
- KNOWLEDGE multi-query evidence joining, heuristic CJK lexical reranking,
  title-aware relevance, and Latin/digit boundaries:
  `2026-08-24_KNOWLEDGE_EVIDENCE_JOINER_*`,
  `2026-08-24_HEURISTIC_CJK_RERANK_*`,
  `2026-08-24_TITLE_AWARE_HEURISTIC_RERANK_*`,
  `2026-08-24_BOUNDARY_AWARE_HEURISTIC_RERANK_*`
- Chat static resource knowledge, runtime Skills, allowlisted HTTP tools, and
  tool-aware memory: `2026-08-25_CHAT_RESOURCE_SKILL_MEMORY_EVOLUTION_*`
- External-projection identity bounds, full-data-plane ACL contracts,
  provider-failure preservation, and reproducible release manifests:
  `2026-08-25_EXTERNAL_PROJECTION_CONTRACT_CLOSURE_*`
- Operation-scoped `RAG_READ` / `RAG_WRITE` principal policy and central
  data-plane enforcement:
  `2026-08-26_OPERATION_SCOPED_API_CAPABILITIES_*`
- Business-binding capability profiles, generic Client lifecycle acceptance,
  and real-provider release closure:
  `2026-08-26_BUSINESS_BINDING_CAPABILITY_PROFILES_*`
- Managed-principal idempotent provisioning and runtime capability discovery:
  `2026-08-26_MANAGED_PROVISIONING_CAPABILITY_DISCOVERY_*`
- Durable Sync Run item receipts, status filtering, and stable terminal-run
  cursor traversal: `2026-08-26_SYNC_RUN_ITEM_RECEIPTS_*`
- External-integration runtime-limit discovery, data-plane operation rollups,
  and privacy-safe operability queries:
  `2026-08-27_EXTERNAL_INTEGRATION_OPERABILITY_*`
- Guarded Collection purge, permanent key tombstones, referenced-content
  cleanup, and event-driven embedding wake-up:
  `2026-08-28_COLLECTION_PURGE_AND_RETIREMENT_*`
- Managed API principal expiry warning, after-commit reconciliation, phase
  escalation, and notification claims:
  `2026-08-27_MANAGED_API_PRINCIPAL_EXPIRY_ALERTS_*`

This list is only an aid for historical discovery. It does not state current
priority or capability status.
