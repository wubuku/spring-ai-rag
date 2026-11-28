# Active Plans

> [English](README.md) | [中文](README-zh-CN.md)

`docs/drafts/` contains only plans and progress ledgers that are **still being
prepared or implemented**. It is not the long-term home for stable project
facts; shipped behavior must be extracted into the corresponding bilingual
evergreen documents.

Read the [Planning, Implementation, And Acceptance Workflow](../delivery-workflow.md)
before creating or maintaining an active plan/progress ledger. This page defines
only the draft lifecycle; it does not duplicate plan content or review-loop rules.

## Rules

1. Use a stable filename for an active plan, such as
   `NEXT_HIGH_VALUE_FEATURES_PLAN.md`, instead of inferring status from a date.
2. Once implementation starts, a matching `*_PROGRESS.md` may record enough
   context to resume the work safely.
3. When work is completed, cancelled, or superseded, first update the relevant
   evergreen `docs/`, then move the plan/progress file with `git mv` into
   [`archive/`](archive/README.md).
4. Preserve or add a `YYYY-MM-DD_` prefix when archiving; the date represents
   the archival or last-valid point in time.
5. `AGENTS.md`, `docs/index*`, and evergreen documents link only to active plans
   or the archive index, not to an individual historical draft as current truth.

## Source-Of-Truth Order

When information conflicts, use:

1. Current code, migrations, and automated tests.
2. Evergreen guides and references under `docs/`.
3. Current active plans in this directory.
4. Historical plans and implementation ledgers under `archive/`.

Active plans are listed here and in `docs/index*`.

## Current Active Plans

- [WeKnora reference-project research and borrowing plan](WEKNORA_REFERENCE_RESEARCH.md)
  (Chinese; research draft, not an implementation commitment)
- [Hardening batch loop plan](HARDENING_LOOP_PLAN.md) (Chinese): a recurring
  plan/implementation/acceptance loop focused on test hardening, WebUI UI/UX
  refactoring, and technical-debt cleanup; new features are out of scope for
  this stage.
- [Durable long-running operations, Embedding Profiles, client contracts, and quality drift](NEXT_BATCH_ASYNC_EMBEDDING_CLIENT_QUALITY_PLAN.md)
  (Chinese): async purge, fixed-dimension Profile routing, and client contracts have landed;
  remaining external blockers are MiniMax balance restoration plus key rotation, and
  Git delivery has not started.
- [Multi-tenancy status](MULTI_TENANCY_STATUS.md) (Chinese): read-only audit of the current
  ACL and ownership boundary; gaps 1 and 2 were closed by V71 and the draft tracks the rest.
- [Multi-tenancy platform roadmap and pluggable design](MULTI_TENANCY_TECH_ROADMAP.md)
  (Chinese only): the 2026-11-04 route decision (single repository and schema, tenant-aware
  tables from day one, single-tenant as fixed-`default` N=1, mandatory RLS for multi-tenant),
  EE/CE archaeology, four implementation phases, six decisions, and three risks. The direction
  was decided and has since been implemented: all 12 work packages completed 2026-11-14 and
  merged to `main` (`1ad305c7`, plus the V85 corrective migration `cc181480`). See the
  implementation plan and progress ledger below for the authoritative record.
- [Multi-tenancy external reference lessons (supplementary archaeology)](MULTI_TENANCY_EXTERNAL_LESSONS.md)
  (Chinese only): a read-only archaeology supplement covering `../seedance-research/`
  (a runnable multi-tenancy PoC plus production checklists) and
  `../AI-Native-Business-Operating-System/` (real-PostgreSQL migration rehearsal); it fixes the
  relative-path convention, lists what the roadmap already covers, what needs strengthening, and
  the new blind spots, and gives reproduction commands. The direction decision was unchanged
  and the implementation has since landed (see the plan and progress ledger below).
- [Multi-tenancy implementation plan](MULTI_TENANCY_IMPLEMENTATION_PLAN.md)
  (Chinese only): frozen plan built on the status audit, technical roadmap, and external
  reference archaeology; 12 work packages, a one-shot acceptance matrix, and a `3/3`
  planning-check record. **Implemented**: WP1–WP12 completed 2026-11-14 and merged to `main`
  (`1ad305c7`, plus the V85 corrective migration `cc181480`).
- [Multi-tenancy progress ledger](MULTI_TENANCY_IMPLEMENTATION_PROGRESS.md),
  [table catalog](MULTI_TENANCY_TABLE_CATALOG.md), and
  [upgrade rehearsal](MULTI_TENANCY_UPGRADE_REHEARSAL.md) (Chinese only): the per-package
  verification ledger with known-unverified items, the 76-table ownership adjudication
  (48 tenant-private / 24 platform-global / 4 credential; machine-readable source
  `TenantTableCatalog.java`), and big-table `CONCURRENTLY` /
  rollback-precondition rehearsal notes.
- [Knowledge-graph module review](KNOWLEDGE_GRAPH_MODULE_REVIEW.md) (Chinese only): critical
  review of an external knowledge-graph proposal, item-by-item alignment with this
  repository's real implementation, and a phased landing path; it becomes a formal document
  only after decisions D1–D3 are settled.
- [Multi-tenancy maturity assessment and P0 audit](MULTI_TENANCY_MATURITY_AND_P0_AUDIT.md)
  (Chinese only): code-backed maturity verdict, 5 P0 defects/blockers (RLS policy semantics,
  tenant API key provisioning gap, non-ACTIVE tenant 403 short-circuit, `default` tenant
  protection & `lifecycle-actors`, mixed-corpus HNSW recall/migration rehearsal), and 4 P1
  operational gaps with fix specs.
- [Operator Console design and phased implementation plan](OPERATOR_CONSOLE_DESIGN_AND_PLAN.md)
  and [Mock-First UI construction manual](OPERATOR_CONSOLE_UI_CONSTRUCTION_MANUAL.md)
  (Chinese only): RAG Operator Console (multi-tenant admin console) planning package modeled
  on ESA and `../AI-Agent-Operator-Console/`, covering 6-view IA, security/isolation & V86
  API contracts, Phase 0–4 roadmap, and a 9-step Mock-First UI construction manual.
- [Operator Console UI/UX audit](OPERATOR_CONSOLE_UI_UX_AUDIT.md) and
  [Customer Web UI conservative alignment audit](CUSTOMER_WEBUI_UI_UX_AUDIT.md)
  (Chinese only): product-manager and full-stack UI/UX audits benchmarking against BOS and
  ESA's shared `classic-light`/`classic-dark` design language and company glossary, covering
  Operator Console polish and conservative token/copy alignment for `spring-ai-rag-webui`.
- [Scenemill system seam alignment and hardening plan](SCENEMILL_SEAM_ALIGNMENT_AND_HARDENING_PLAN.md)
  (Chinese only): code-level seam hardening plan (3/3 reviewed) for upstream Scenemill's N:1
  shared-collection and multi-tenancy evolution, covering P0s (pgvector `hnsw.iterative_scan`,
  `/integration-capabilities` tenant-field parity with `/auth/me`,
  `JsonRecordUpsertResponse.contentHash` and non-`ACTIVE` tenant `403` contract) and P1s
  (dual-mode `ScenemillSeamContractTest`, response header parity, and `scenemill-prod`
  `STRICT`-mode provisioning template).
- [Scenemill STRICT provisioning template](SCENEMILL_STRICT_PROVISIONING.md)
  (Chinese only): reference orchestration of the `scenemill-prod` tenant, six collections and
  four restricted keys for a shared RAG instance, including the non-`ACTIVE` tenant `403`
  contract check.
The processing/recovery, citation/streaming, chunking-correctness, and default-feature/automatic-upgrade/recovery batches,
including supplemental real-LLM acceptance, are in the
[historical archive](archive/README.md).

The Knowledge Chat post-retrieval evidence assembly and completeness regression
plan is also in the [historical archive](archive/README.md).

The API Route Policy self-check and default denial plan is now in the
[historical archive](archive/README.md).

Completed, stopped, and superseded materials remain available in the
[historical archive](archive/README.md).
