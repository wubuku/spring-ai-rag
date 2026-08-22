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
- Chat, WebUI, and later feature batches: `2026-08-17_*`,
  `2026-08-18_*`, `2026-08-22_NEXT_HIGH_VALUE_FEATURES_*`
- Document-lifecycle implementation ledger:
  `2026-08-19_DOCUMENT_LIFECYCLE_IMPLEMENTATION_PROGRESS.md`
- Local keyword/vector derivation decoupling:
  `2026-08-19_KEYWORD_VECTOR_DECOUPLING_*`
- External-document relocation and derivation-integrity repair:
  `2026-08-21_NEXT_HIGH_VALUE_FEATURES_*`
- Cross-project integration plan for the first external Client:
  `2026-08-21_FIRST_EXTERNAL_CLIENT_INTEGRATION_*` (superseded because it
  exceeded this repository's documentation boundary)

This list is only an aid for historical discovery. It does not state current
priority or capability status.
