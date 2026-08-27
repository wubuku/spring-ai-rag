# 1.0 Release Checklist

> 📖 [English](release-checklist.md) · 📖 [中文](release-checklist-zh-CN.md)
>
> Before a feature enters this release checklist, complete its planning, basic
> integration gates, and convergence reviews under the
> [Planning, Implementation, And Acceptance Workflow](delivery-workflow.md).

Release candidate: `1.0.0`
Release date: `2026-07-21`

## Metadata and Artifacts

- [x] Root modules and standalone demos use Maven version `1.0.0`
- [x] OpenAPI reports `1.0.0`
- [x] Helm `version` and `appVersion` are `1.0.0`
- [x] Docker/Helm default image tag is `1.0.0`
- [x] Local, Docker, and Helm default port is `8081`
- [x] Flyway inventory is V1-V56
- [x] JSONB structured-record API, payload snapshots, and collection lifecycle are covered
- [x] `scripts/verify-jsonb-records.sh` records focused backend/database/frontend verification
- [x] Document PATCH/disable/restore/permanent-delete and external triple identity are covered
- [x] `scripts/verify-document-lifecycle.sh` records CRUD, derived-index, client, and WebUI verification
- [x] OpenAI-compatible base URLs do not end in `/v1`
- [x] English and Chinese release notes are present

## Product Gates

- [x] Production query rewrite and heuristic rerank defaults are enabled
- [x] Goldenset runner reports baseline and quality Precision@K/MRR/nDCG
- [x] API Key collection ACL is persisted and enforced across all data paths
- [x] Chat and Settings support runtime model selection
- [x] Explicit unknown/unavailable models return HTTP 400
- [x] External `models.json` fully replaces YAML when loaded

## Verification Gates

- [x] `mvn clean test`
- [x] `mvn package -pl spring-ai-rag-core -am -Pwebui -DskipTests`
- [x] WebUI lint, full Vitest, and production build
- [x] Full Playwright suite
- [x] Helm lint/template and Docker image build
- [x] PostgreSQL-profile server startup and `scripts/e2e-test.sh`
- [x] Retrieval goldenset run
- [x] Versioned live retrieval regression is available through `--with-quality-regression` or `--with-local-runtime`
- [x] Real LLM smoke test when local `.env` credentials are available
- [x] Secret scan and `git diff --check`
- [x] Three consecutive no-change convergence reviews

### 2026-08-17 Additive Gates

- [x] OpenAI compatibility verification covers aliases, scope/ACL, JSON/SSE, and error envelopes
- [x] Embedding-job verification covers V33, coalescing, leases, and atomic conditional claims
- [x] `verify-no-pessimistic-locks.sh` prevents explicit pessimistic-lock, `SKIP LOCKED`, and advisory-lock regressions
- [x] JSONB verification covers `payloadContains` and the V34 GIN planner
- [x] The live retrieval dataset and baseline are committed, and external-dependency failures return nonzero

### 2026-08-19 Document Lifecycle Gates

- [x] V40/V41 add business revisions, complete snapshots, source namespaces, and generation fencing
- [x] V42 adds authoritative external snapshot reconciliation runs and deletion markers
- [x] V43 separates profile-neutral local keyword chunks from remote vector state
- [x] Content changes immediately stale old derived results; metadata/payload/Collection-only updates do not re-embed
- [x] The external reference client and bilingual client best practices are tracked
- [x] PostgreSQL lifecycle acceptance explicitly requires `skipped=0`

### 2026-08-21 Relocation And Derivation Integrity Gates

- [x] V44 provides atomic cross-Collection relocation, dual-Collection ACL,
  exact idempotent replay, and permanent retired-address protection
- [x] V45 provides shared strict derivation freshness, paged/aggregate
  diagnostics, and a preview-first controlled-repair ledger
- [x] `verify-document-relocation.sh` and `verify-derivation-integrity.sh`
  capture backend, database, frontend Mock, and documentation gates, with
  PostgreSQL acceptance explicitly requiring `skipped=0`

### 2026-08-22 Chat Turn Reliability Gates

- [x] V47 provides principal-scoped Chat turn idempotency, immutable replay
  snapshots, bounded leases/reclaim, status lookup, and history turn identity.
- [x] Chat JSON/SSE and OpenAI-compatible JSON/SSE share the durable operation
  boundary; keyed replay does not invoke the provider again.
- [x] The Chat capability gate records the V47 PostgreSQL matrix and Mock
  Playwright retry/replay/stop evidence.

### 2026-08-25 Business Service Integration Readiness Gates

- [x] `/api/v1/rag/auth/me` explicitly returns principal role,
  restricted/unrestricted mode, and the current credential's own stable
  Collection-key allow-list, failing closed when policy resolution is
  incomplete.
- [x] `verify-business-client-readiness.sh` covers PostgreSQL integration
  matrices, Maven/WebUI gates, real Spring Boot, 160 business-credential HTTP
  assertions including deployed binding preflight, and real API-key
  Playwright.
- [x] The contract covers external identity/revision bounds, bidirectional
  full-data-plane generic-`403` anti-enumeration, and preservation of Record
  identity/revision/payload/enabled/documentRevision after a provider `503`.
- [x] The clean-tree final gate writes `release-manifest.json`, pinning the
  full Git SHA, project/OpenAPI `1.0.0`, API base path, runtime Flyway equal to
  the repository's latest migration, PostgreSQL image, and HTTP contract count
  without storing secrets or business payloads.
- [x] `business-client-binding-preflight.sh` is read-only by default and its
  real canary/cleanup reports are schema-valid and secret-safe.
- [x] The bilingual Business Service Integration Guide records root/business
  credentials, binding, CAS/tombstone/ASYNC, rotation, upgrade, and rollback
  boundaries.

### 2026-08-26 Operation-Scoped API Capability Gates

- [x] V49 adds database-constrained `RAG_READ` / `RAG_WRITE` policy to stable
  principals, with V48 data defaulting compatibly to full read/write.
- [x] A central capability filter runs after authentication and before shared
  rate limiting. Read-only principals may Search/Chat, while writes return
  `403`; OpenAI-compatible errors use `insufficient_permissions`.
- [x] Create, policy CAS, rotation, `/auth/me`, WebUI, and PostgreSQL/live-HTTP
  acceptance share the authoritative capability value. Invalid sets are not
  persisted, and ADMIN cannot be downgraded to read-only.
- [x] Deployed binding preflight distinguishes exact `READ_ONLY` and
  `READ_WRITE` credential profiles. The release manifest records both verified
  profiles, while the HTTP contract uses a read-only query principal and a
  read/write dispatcher to prove successful reads, write `403`, unchanged
  state, and capability inheritance across rotation.
- [x] The real-LLM gate uses an explicit `RAG_READ` principal. Write rejection
  and idempotent replay do not increase the provider counter. Native and
  OpenAI-compatible JSON/SSE plus staged complete/cancel/pending-family-revoke
  lifecycles require exactly nine successful real provider calls.

### 2026-08-26 Provisioning And Capability Discovery Gates

- [x] V50 adds a requester-scoped provisioning operation ledger with
  key/fingerprint hashes and result metadata only; raw credentials remain
  shown once and are never persisted for replay.
- [x] A keyed create returns `201`; exact cross-instance replay returns `200`,
  `X-RAG-Idempotent-Replay: true`, and `rawKey: null`; semantic key reuse
  returns `409 IDEMPOTENCY_KEY_REUSED`.
- [x] PostgreSQL and real HTTP acceptance cover concurrent first create,
  requester isolation, rotation/revocation replay state, bounded cleanup, and
  fail-closed ledger/configuration errors.
- [x] Authenticated `GET /api/v1/rag/integration-capabilities` reports the
  versioned protocol, caller policy/Collection projection, supported
  data-plane behavior, optional features, and stable limits without secrets.
- [x] The unified managed-principal gate covers PostgreSQL, Maven, WebUI
  typecheck/Vitest/build/alignment, Mock Playwright, two-instance HTTP, real
  browser DOM/network assertions, and optional real-provider Chat.

### 2026-08-26 Sync Run Item Receipt Gates

- [x] V51 adds unfiltered and status-filtered keyset indexes to the existing
  Sync Run item ledger without duplicating mutation data or storing bodies,
  payloads, metadata, credentials, or provider details.
- [x] `GET /api/v1/rag/document-sync-runs/{runId}/items` requires `RAG_READ`
  plus Collection/run/namespace binding, supports status filtering and bounded
  1–200 pagination with a run/status-bound opaque cursor, and reports a
  separate current-ledger summary.
- [x] Terminal traversal is stable. Active traversal is explicitly eventually
  consistent, requiring `externalId` deduplication and a fresh terminal rescan.
  Errors are masked on write and read, capped at 500 characters, and responses
  use `no-store`.
- [x] Capability discovery reports runtime availability through
  `features.optional.documentSyncRunItemReceipts` as an additive protocol
  `1.0` field.
- [x] PostgreSQL service and authenticated HTTP acceptance cover V1-V52,
  restricted read/write and read-only principals, ACL anti-enumeration, cursor
  binding, active/terminal paging, exact failed replay, missing reconciliation,
  and secret-safe evidence.

### 2026-08-26 Collection Provisioning Idempotency Gates

- [x] V52 adds a separate owner-scoped Collection-create operation ledger with
  key/fingerprint hashes and a restricted Collection foreign key; raw keys and
  request bodies are never persisted.
- [x] Unkeyed create remains `200`; keyed first create is `201`; exact
  cross-instance or post-restart replay is `200` with
  `X-RAG-Idempotent-Replay: true`; semantic reuse returns
  `409 IDEMPOTENCY_KEY_REUSED`.
- [x] Replay returns the Collection's current state and document count,
  including soft-deleted state, without restoring the resource or writing a
  second create audit.
- [x] Configuration-disabled and ledger-unavailable keyed requests fail closed
  with `503`; same-owner races use unique constraints and bounded rereads
  without explicit pessimistic locks.
- [x] `verify-collection-provisioning.sh` covers focused contracts, nine
  PostgreSQL tests, two real backend instances, restart recovery, owner/ACL
  isolation, database facts, and secret-safe evidence. WebUI tests prove one
  generated UUID is reused across Axios retries.
- [x] Capability discovery reports the additive protocol `1.0` field
  `features.provisioning.collectionCreateIdempotencyKey`.

### 2026-08-26 Model-Invocation Usage Ledger Gates

- [x] V53 adds the append-only `rag_llm_usage_event` table with bounded fields,
  principal/session/trace attribution, invocation-start price snapshots,
  normalized usage, terminal outcome, and duration.
- [x] `BudgetedChatModel` records exactly one terminal event per model call or
  stream subscription across Chat, query transform/expansion, summaries,
  fallbacks, application retries, and AGENT rounds.
- [x] Recording is fail-open with bounded synchronous non-streaming confirmation,
  bounded asynchronous streaming recording, local loss accounting, and bounded
  retention cleanup; prompts, answers, tool payloads, credentials, and exception
  bodies are excluded.
- [x] `GET /api/v1/rag/usage` provides inclusive UTC aggregate windows, stable
  breakdowns, explicit unavailable-usage/pricing counters, and principal-scoped
  authorization.
- [x] `verify-llm-usage-ledger.sh` covers the focused backend/API tests,
  PostgreSQL V1-V53 integration, Maven, WebUI, Mock Playwright, lock,
  documentation, and whitespace gates.
- [ ] Real-LLM lifecycle acceptance records plain, knowledge, agent, fallback,
  summary, replay, and usage-ledger evidence without storing prompts, answers,
  keys, or tool payloads.

### 2026-08-27 External Integration Operability Gates

- [x] V54 adds bounded UTC hourly request totals and authorized Collection
  contributions without request/response bodies, queries, payloads, external
  IDs, credentials, dynamic URLs, or exception text.
- [x] `/integration-capabilities` keeps protocol `1.0` and additively publishes
  structured-record, Sync Run, and observability runtime limits plus
  `features.optional.integrationObservability`.
- [x] `GET /api/v1/rag/integration-observability` provides self/current-ACL
  NORMAL access and root/ADMIN management access with bounded HOUR/DAY windows,
  status/operation/Collection breakdowns, and explicit best-effort
  completeness.
- [x] Recording uses an asynchronous bounded queue, grouped PostgreSQL upsert,
  bounded retention/shutdown drain, fail-open business semantics, and fixed
  low-cardinality Micrometer tags.
- [x] The deployed binding preflight can require minimum JSON batch item/
  payload limits and operation observability; its report and the readiness
  release manifest retain only non-sensitive runtime facts.
- [ ] The final merged-baseline readiness gate records focused/PostgreSQL/
  Maven/WebUI/Mock/real-HTTP evidence, real LLM/Embedding acceptance, and three
  consecutive no-change reviews.

### 2026-08-27 Bounded Staged Credential Rotation Gates

- [x] V55 permits at most one current and one deadline-bounded retiring
  credential per stable principal, plus one PENDING rotation operation.
- [x] Prepare requires `Idempotency-Key`, shows the replacement secret once,
  returns secret-free exact replay, and exposes runtime limits through
  `features.credentialRotation`.
- [x] Complete, cancel, deadline expiry, policy-expiry clamp, immediate
  compatibility rotation, and family revoke converge without duplicating
  principal identity, ACL, capabilities, Chat ownership, usage, or quota.
- [x] WebUI makes staged rotation the recommended path, keeps secrets in page
  memory, supports complete/cancel and response-loss recovery, and retains the
  immediate path as an explicit secondary action.
- [x] Pre-merge V55 acceptance records 54/54 PostgreSQL tests, Maven/WebUI/
  Mock/real-Playwright gates, dual-instance HTTP lifecycle evidence, nine real
  provider calls with zero rejected/replay increments, and real Embedding/RAG.
- [x] Reran the same complete matrix from the post-merge baseline after merging
  the latest `origin/main`: `v55-minimax-postmerge-20260828-r2` passed 13/13.
  Independent real RAG `v55-minimax-postmerge-20260827-r5` passed `10/10`,
  including revision-guarded permanent deletion and final database facts.

### 2026-08-27 Guarded Collection Purge And Retirement Gates

- [x] V56 adds the permanent-key tombstone, Chat commit fence, normalized
  Chat/feedback document references and completeness markers, and a durable
  purge preview that stores neither bodies nor plaintext tokens.
- [x] Preview/apply authorizes environment root, database ADMIN, or an explicit
  auth-disabled direct-loopback caller. The caller-aware capability protocol is
  `1.1` and publishes synchronous purge bounds.
- [x] Collection-first conditional writes protect document mutation, sync,
  repair, feedback, Chat, restore/relocation/clone, and purge without explicit
  pessimistic locks.
- [x] The five-scenario PostgreSQL matrix covers complete cascades, unrelated
  data/independent-file retention, active lease/run blocking, malformed
  historical-reference fail-closed behavior, authorization, rollback, exact
  replay, cleanup, and retired scopes.
- [x] The WebUI exposes the action only to environment root when capability is
  visible; the token is not rendered, exact key input is required, success
  remains accessible while the active list refreshes, and `409` is not retried
  automatically.
- [x] `verify-collection-purge.sh` fixes the focused backend, PostgreSQL, Maven
  clean, WebUI, Mock Playwright, lock, documentation, shell, and whitespace
  gates.
- [x] Completed isolated PostgreSQL plus real LLM/Embedding lifecycle acceptance
  for pre-retirement write/retrieval/Chat, purge, and post-retirement explicit
  rejection/default-scope exclusion. `real-provider-20260828-r8` passed
  `12/12`; global and Collection purge rollups are positive, logs contain no
  observation drop/provider/database failure, and durable evidence contains no
  key, body, plaintext token, or full model answer.
- [ ] After syncing the latest `origin/main`, rerun the focused gate and real
  lifecycle acceptance from the merged baseline.

### Final Evidence (2026-07-21)

- One command: `VERIFY_RUN_ID=20260721-release-complete ./scripts/verify-release.sh --with-local-runtime`
- Archive: `target/release-verification/20260721-release-complete/summary.md`
- Release gates: 19 passed, 0 failed, 0 skipped
- Maven: 3213 tests (API 530, Documents 74, Core 2557, Starter 52)
- WebUI: lint, 153 Vitest tests, production build, embedded-bundle integrity, and 37 Playwright tests
- Deployment: Helm lint/template; non-root `linux/arm64` Docker image using DaoCloud base images and an Aliyun Maven mirror
- Runtime: PostgreSQL-profile startup and 66/66 HTTP E2E checks
- Retrieval: baseline/quality MRR 1.0, Precision@5 0.24, nDCG 1.0, and `GOLDENSET_OK`
- Real models: MiniMax-M3 plus SiliconFlow BGE-M3; ask/stream and data cleanup passed 10/10

## Publication

- [x] Commit release changes only after every applicable verification gate passes
- [x] Push the verified commit to the current upstream branch
- [ ] Create immutable source/image tag `1.0.0` in the release pipeline
