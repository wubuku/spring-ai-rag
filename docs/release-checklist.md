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
- [x] Flyway inventory is V1-V49
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
  matrices, Maven/WebUI gates, real Spring Boot, 129 business-credential HTTP
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
  and idempotent replay do not increase the provider counter, while native and
  OpenAI-compatible JSON/SSE require exactly five real provider calls.

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
