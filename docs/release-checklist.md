# 1.0 Release Checklist

> 📖 [English](release-checklist.md) · 📖 [中文](release-checklist-zh-CN.md)

Release candidate: `1.0.0`
Release date: `2026-07-21`

## Metadata and Artifacts

- [x] Root modules and standalone demos use Maven version `1.0.0`
- [x] OpenAPI reports `1.0.0`
- [x] Helm `version` and `appVersion` are `1.0.0`
- [x] Docker/Helm default image tag is `1.0.0`
- [x] Local, Docker, and Helm default port is `8081`
- [x] Flyway inventory is V1-V43
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
