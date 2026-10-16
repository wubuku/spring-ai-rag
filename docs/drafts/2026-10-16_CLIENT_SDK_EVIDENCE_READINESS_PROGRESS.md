# Implementation Progress: Client SDK, Structured Evidence, and Recovery Readiness

> [English](2026-10-16_CLIENT_SDK_EVIDENCE_READINESS_PROGRESS.md) | [中文](2026-10-16_CLIENT_SDK_EVIDENCE_READINESS_PROGRESS-zh-CN.md)

## Status

- Branch: `feat/client-sdk-evidence-readiness`
- Baseline: `03943a68`; the workspace was clean and synchronized with `origin/main` before branching.
- Current stage: plan review completed 3/3; implementation, gates, bilingual evergreen docs, full
  regression, external-client acceptance, real-model acceptance, and implementation review completed
  3/3; ready for Git delivery.
- Database migration: none expected for this batch.

## Plan Review Record

The plan review completed three consecutive no-change rounds after fixing the active-draft
navigation. Scope covered DTO/controller paths, event order, keyed replay, existing gates,
migration facts, and bilingual documentation.

## Implementation Record

Implemented so far: additive `rag` projections for OpenAI JSON/SSE/replay, privacy-safe
`OpenAiRagSource`, Python/Node SDK examples and quickstart gate, disposable PostgreSQL/Flyway
recovery-readiness gate with database-history reconciliation, explicit release options, and
synchronized REST/readiness/Contract Kit/developer/testing/release documentation.

The recovery gate uses disposable PostgreSQL, runs the repository migrations, compares the actual
`flyway_schema_history` latest version with the source inventory, and writes a redacted manifest.
It is not a backup or disaster-recovery product.

## Test Record

Passed evidence:

- `mvn clean test` passed for the full reactor; the pre-existing external starter consumer test remains
  conditionally skipped unless its explicit external environment flag is enabled.
- The full `scripts/verify-gated-it.sh` PostgreSQL/Testcontainers matrix passed, including Flyway V1→V64
  migration and the budget/governance, document-processing, recovery, and client-facing integration suites.
- `OpenAiRagExtensionTest`, `OpenAiCompatibilityEventMappingTailTest` (`9/9`), Client Consumer Contract,
  SDK quickstart self-test, OpenAI compatibility self-test, recovery readiness, project-docs, diff, and
  pessimistic-lock gates passed.
- `./scripts/verify-openai-sdk-e2e.sh --self-test` passed all 20 runner mutations; the Mock HTTP path
  was not treated as real-model evidence.
- `scripts/dev.sh` started the PostgreSQL backend and Vite proxy on isolated ports; readiness, auth,
  integration capabilities, collections, proxy auth, and final stop/port cleanup passed. The OpenAI
  compatibility endpoint is intentionally not enabled by the default development `.env`.
- With the configured `.env` credentials, the real MiniMax/Embedding smoke passed: provider preflight,
  isolated collection/document creation, real embedding, scoped search, KNOWLEDGE answer, citation
  evidence, Native SSE, and evidence assessment. AGENT validation was capability-gated by the selected
  provider and recorded as a skip, not as a false pass.
- `./scripts/verify-release.sh --skip-docker --with-openai-sdk --with-client-sdk --with-recovery-readiness`
  passed with `22 passed, 0 failed, 7 skipped`; the skipped items were the explicitly disabled Docker
  build and optional runtime/goldenset/quality/real-LLM/next-high-value gates, which were not silently
  counted as passes.

No API key, prompt, answer body, source body, or complete provider error was persisted in the repository.
Implementation review follows the same three-consecutive-no-change rule as planning.

## Implementation Review Record

Three consecutive read-only rounds completed after the final acceptance evidence was recorded:

1. Transaction, migration, concurrency, recovery, authorization, and source redaction boundaries — no findings.
2. External API additive compatibility, JSON/SSE/replay consistency, legacy Java constructors, and SDK secret-safety — no findings.
3. Release entry points, bilingual navigation, runtime cleanup, generated-artifact isolation, and final diff hygiene — no findings.
