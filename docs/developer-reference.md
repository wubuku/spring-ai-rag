# Developer Reference

> [English](developer-reference.md) | [中文](developer-reference-zh-CN.md)

> **Purpose**: Provide copyable build, startup, database, model, WebUI, E2E, and release-verification commands.
> **Maintenance**: Commands must match repository scripts. Local Agent state may link here, but this document never depends on local state.

Documentation hub: [index.md](index.md). Stable project context: [project-context.md](project-context.md).

## 1. Fixed Conventions

| Item | Value |
|------|-------|
| Java | 21+ |
| Maven | 3.9+ |
| Service / backend-only default port | `8081` |
| `dev.sh` backend port | `18082` |
| Local profile | `postgresql` |
| Real-LLM E2E port | `18081` |
| Embedding | SiliconFlow `BAAI/bge-m3` |
| Vector dimension | `1024` |
| Flyway | V1–V57 |

Do **not** append `/v1` to an OpenAI or Embedding `base-url`. Spring AI appends `/v1/chat/completions` or `/v1/embeddings`.

## 2. Build And Test

```bash
mvn clean compile
mvn test
mvn clean package -DskipTests
```

One module or test:

```bash
mvn test -pl spring-ai-rag-core
mvn test -pl spring-ai-rag-core -Dtest=RagDocumentControllerTest
```

Coverage:

```bash
mvn clean test jacoco:report
open spring-ai-rag-core/target/site/jacoco/index.html
```

See [testing-guide.md](testing-guide.md) for the test strategy.

### Documentation System

Run the project-documentation boundary, link, bilingual-structure, invariant, command, whitespace, and secret checks with:

```bash
./scripts/verify-project-docs.sh
```

For the document CRUD, external synchronization, version restore, disposable
PostgreSQL, reference client, and WebUI acceptance flow:

```bash
./scripts/verify-document-lifecycle.sh
```

For the focused V42/V51 Sync Run HTTP acceptance against disposable PostgreSQL,
including authenticated authorization and durable item receipts:

```bash
./scripts/verify-document-sync-runs.sh
```

The gate creates temporary restricted read/write and read-only principals and
verifies ACLs, cursor pagination, terminal-rescan semantics, failed-receipt
recovery, `no-store`, and sensitive-data protection. Evidence excludes
credentials, cursors, external IDs, and business payloads.

For caller-scoped, durable Collection-create idempotency across PostgreSQL,
two backend instances, and process restart:

```bash
./scripts/verify-collection-provisioning.sh
```

The gate covers V52 migration and constraints, exact replay, semantic key
reuse, owner isolation, restricted ACLs, concurrent first create, current
soft-deleted state, one create audit, ledger failure closure, and secret-safe
database facts. Use `COLLECTION_PROVISIONING_VERIFY_PHASE=http` to rerun only
the disposable dual-instance HTTP phase.

For V56 Collection content cleanup, permanent-key tombstones, reference
cascades, and the WebUI preview/apply flow:

```bash
./scripts/verify-collection-purge.sh
```

The default run uses Testcontainers for five real PostgreSQL scenarios. A
caller-provided disposable database can be selected with
`COLLECTION_PURGE_IT_JDBC_URL`, `COLLECTION_PURGE_IT_USERNAME`,
`COLLECTION_PURGE_IT_PASSWORD`, and
`COLLECTION_PURGE_IT_CLEAN_CONFIRM=YES`. The script also runs focused backend
tests, the Maven clean compile gate, the complete WebUI gates, no-screenshot
Collection Mock Playwright, lock/document/shell-syntax/whitespace checks, and
writes evidence under `.verification/collection-purge/<run-id>/`.

For the durable model-invocation usage ledger and its principal-scoped
aggregate API:

```bash
LLM_USAGE_LEDGER_VERIFY_RUN_ID=usage-ledger-gate \
./scripts/verify-llm-usage-ledger.sh
```

The gate runs focused attribution/recorder/API tests, migrates an isolated
PostgreSQL database through V53, runs the full Maven and WebUI gates, verifies
the no-pessimistic-lock and project-documentation rules, and executes the
no-screenshot Mock Playwright Metrics checks. It writes secret-safe evidence
under `.verification/llm-usage-ledger/<run-id>/`. The script does not perform
real provider calls; after this gate passes, use the real-LLM lifecycle
procedure in [testing-guide.md](testing-guide.md) with an isolated service and
disposable database.

For the V43 local-keyword/vector derivation boundary:

```bash
KEYWORD_VECTOR_VERIFY_RUN_ID=full-gate-4 \
KEYWORD_VECTOR_PLAYWRIGHT_PORT=4191 \
./scripts/verify-keyword-vector-decoupling.sh
```

This gate requires real PostgreSQL lifecycle/full-text integration tests,
`mvn clean compile test-compile`, and the WebUI TypeScript, Vitest,
production-build, alignment, and no-screenshot Mock Playwright checks.

## 3. Start And Health Check

One-command backend and frontend development:

```bash
./scripts/dev.sh
```

The launcher exports the complete repository-root `.env` to Maven / Spring Boot
and starts. It also allows the exact Vite origin on the backend and verifies a
root-authenticated management POST before reporting ready:

```text
Backend: http://127.0.0.1:18082
WebUI:   http://127.0.0.1:15173/webui/unlock
```

If neither `.env` nor the caller environment defines `RAG_ROOT_API_KEY`, the
launcher generates an ephemeral root credential for the current backend process.
On macOS it is copied to the clipboard and is never written to files or logs.
Status, stop, and port overrides:

```bash
./scripts/dev.sh --status
./scripts/dev.sh --stop
BACKEND_PORT=19082 FRONTEND_PORT=15174 ./scripts/dev.sh
RAG_DEV_OPEN_BROWSER=false ./scripts/dev.sh
```

The launcher never performs automatic Flyway repair. If startup detects a
migration checksum mismatch, it prints the relevant cause. Restore the applied
migration and place later changes in a new migration instead of rewriting
schema history.

Backend only:

```bash
bash scripts/start-server.sh
```

Manual start:

```bash
set -a
source .env
set +a
export SPRING_PROFILES_ACTIVE=postgresql
mvn spring-boot:run -pl spring-ai-rag-core -DskipTests
```

Port cleanup and health for the backend-only `8081` process:

```bash
lsof -ti :8081 | xargs kill -9 2>/dev/null
curl -fsS http://127.0.0.1:8081/actuator/health
```

Swagger: `http://127.0.0.1:8081/swagger-ui.html`

For the full-stack launcher, use `18082` instead:

```bash
curl -fsS http://127.0.0.1:18082/actuator/health
```

## 4. Database

- PostgreSQL connection values come from `.env`.
- Required extension: `vector`.
- Recommended extension: `pg_trgm`; `pg_jieba` is optional.
- Migrations: `spring-ai-rag-core/src/main/resources/db/migration/`.

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

Prefer a Docker PostgreSQL image with the extensions installed. See [postgresql-extensions.md](postgresql-extensions.md).

## 5. Model Configuration

### Embedding

```text
Provider: SiliconFlow
Model: BAAI/bge-m3
Dimensions: 1024
Base URL: https://api.siliconflow.cn
```

### Chat Providers

| Provider | Configuration |
|----------|---------------|
| OpenAI-compatible | `spring.ai.openai.*` |
| Anthropic | `spring.ai.anthropic.*` |
| MiniMax | `spring.ai.minimax.*` |

Select the default provider with `LLM_PROVIDER` / `app.llm.provider`. See [multi-model-external-config.md](multi-model-external-config.md) for model instances and external configuration.

Store real credentials only in `.env`; never put them in shell examples, Markdown, or Git.

## 6. WebUI

```bash
cd spring-ai-rag-webui
npm ci
npm run lint
npm run test:run
npm run build
```

Development:

```bash
npm run dev
```

Direct use listens on `http://127.0.0.1:15173/webui/` and proxies `/api` to
`http://127.0.0.1:8081` by default. For normal full-stack development, run
`./scripts/dev.sh` from the repository root so the launcher keeps ports and the
proxy target aligned.

The release build embeds assets under:

```text
spring-ai-rag-core/src/main/resources/static/webui/
```

## 7. E2E

### HTTP E2E

```bash
bash scripts/start-server.sh
BASE_URL=http://127.0.0.1:8081 bash scripts/e2e-test.sh
```

### WebUI Playwright

```bash
cd spring-ai-rag-webui
npm run build
npx vite preview --host 127.0.0.1 --port 4173
BASE_URL=http://127.0.0.1:4173 npx playwright test
```

### Real LLM

```bash
./scripts/start-real-e2e-server.sh
BASE_URL=http://127.0.0.1:18081 \
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/real-llm-e2e-smoke.sh
```

The flow performs provider preflight, unique-document creation, embedding, search, ask, and
stream. When `RAG_ROOT_API_KEY` is configured, pass it through `RAG_API_KEY` (or the
equivalent `X-API-Key` header); the script also loads the root key from `.env`. Mock
Playwright is not a substitute for real-LLM validation.

The guarded Collection purge real-provider lifecycle uses
`scripts/real-collection-purge-e2e-smoke.sh`. It requires a running isolated
service and disposable database, then verifies event-first embedding, real
retrieval/Chat, purge/replay, retired rejection, and the tombstone. See the
[testing guide](testing-guide.md#guarded-collection-purge-and-retirement-acceptance-gate)
for the full command and evidence-safety boundary.

This Chat-turn idempotency delivery has a separate PLAIN smoke that does not require an
Embedding provider:

```bash
BASE_URL=http://127.0.0.1:18081 \
./scripts/real-llm-chat-idempotency-smoke.sh
```

The script forces the OpenAI-compatible Chat provider and verifies native JSON/SSE first
requests, same-key replay, key conflict, turn status lookup, and the before/after
`/actuator/metrics/rag.chat.provider.calls` counter to prove replay did not invoke the
provider again. Start the server with an isolated PostgreSQL database and dedicated ports;
never put API keys in shell history or documentation.

### Chat Capability Verification

Run the Chat redesign gate serially because it includes Maven clean output:

```bash
./scripts/verify-chat-capability.sh
```

The script verifies `KNOWLEDGE`, `AGENT`, and `PLAIN` mode execution, Spring AI
Tool Calling boundaries, principal-scoped memory/history, V32 session leases,
V46 durable summary CAS, V47 durable Chat-turn idempotency/replay, V48 stable managed principals and shared quota, bounded execution metadata, structured SSE, WebUI
mode/capability/source rendering, and Chat export snapshots. It also runs the
`NextHighValueFeaturesPostgresIntegrationTest` matrix and the independent
domain-extension and read-only SQL tool demo tests, then records every step under
`.verification/chat-capability/<run-id>/summary.md`.

PostgreSQL/Testcontainers defaults:

```bash
TESTCONTAINERS_API_VERSION=1.40 \
TESTCONTAINERS_RYUK_DISABLED=true \
./scripts/verify-chat-capability.sh
```

If Docker is unavailable, the script records the PostgreSQL gate as `SKIP`
instead of claiming it passed. `--skip-postgres` is an explicit equivalent and
must remain visible in the recorded summary. The known Docker API `1.32`
versus daemon minimum `1.40` problem is documented in
[china-network-guide.md](china-network-guide.md).

The Chat Mock Playwright gate runs against a strict, overridable Vite preview
port:

```bash
CHAT_PLAYWRIGHT_PORT=4199 ./scripts/verify-chat-capability.sh
```

It uses DOM, network, URL, and test assertions only; screenshots are not
correctness evidence. Real provider calls are opt-in:

```bash
./scripts/verify-chat-capability.sh --with-real-llm
```

With `--with-real-llm`, the gate creates a disposable PostgreSQL database,
starts an isolated `scripts/dev.sh` stack (backend `18083`, WebUI `15175` by
default), runs the real WebUI `chat-real.spec.ts` and provider smoke, then
cleans up the stack, overlay environment, and database. The `.env` or caller
environment must provide `RAG_ROOT_API_KEY`; override the ports with
`CHAT_REAL_BACKEND_PORT` and `CHAT_REAL_FRONTEND_PORT` when needed. Without
that option the real LLM step is recorded as `SKIP`.

### OpenAI Compatibility Verification

```bash
./scripts/verify-openai-compatibility.sh
```

The gate covers model aliases, request-scoped Collection scope/ACL, complete
text-only messages, non-streaming OpenAI JSON, compatible error envelopes, SSE
chunk ordering, and `[DONE]`, followed by relevant `test-compile`, shell
syntax, and whitespace checks. Evidence is written under
`.verification/openai-compatibility/<run-id>/`. The runtime controller remains
disabled unless `RAG_OPENAI_COMPATIBILITY_ENABLED=true`.

### Durable Embedding Jobs Verification

```bash
./scripts/verify-embedding-jobs.sh
```

The gate covers the service, worker, HTTP API, V33 migration, active-job
coalescing, and two-worker atomic conditional claims. It starts an isolated
`pgvector/pgvector:pg16` container by default. Reuse a caller-provided isolated
database with `EMBEDDING_JOBS_IT_JDBC_URL`, `EMBEDDING_JOBS_IT_USERNAME`, and
`EMBEDDING_JOBS_IT_PASSWORD`. Evidence is written under
`.verification/embedding-jobs/<run-id>/`.

<a id="document-lifecycle-verification"></a>

### Document Lifecycle Verification

```bash
./scripts/verify-document-lifecycle.sh
```

This verifies local create/PATCH/disable/restore/permanent-delete, external
TEXT/JSON `collectionKey + sourceNamespace + externalId`, revision CAS,
complete snapshots, generation-aware re-embedding after content changes,
no re-embedding for non-text changes, WebUI CRUD, and the reference client.

The script prefers a disposable database created from current-shell or `.env`
`POSTGRES_*`, avoiding Testcontainers/new-Docker protocol negotiation issues.
Alternatively provide `DOCUMENT_LIFECYCLE_IT_JDBC_URL`,
`DOCUMENT_LIFECYCLE_IT_USERNAME`, and `DOCUMENT_LIFECYCLE_IT_PASSWORD`. Never
point these at development or production. Evidence is stored under
`.verification/document-data-plane/<run-id>/`.

### Document Relocation And Derivation Integrity Verification

```bash
./scripts/verify-document-relocation.sh
./scripts/verify-derivation-integrity.sh
```

Both focused gates run the no-pessimistic-lock check, focused HTTP tests,
disposable-PostgreSQL integration tests, `mvn clean compile test-compile`,
WebUI typecheck/Vitest/production build/alignment, no-screenshot Mock
Playwright, bilingual documentation checks, and the whitespace gate. Evidence
is written under `.verification/relocation/<run-id>/` and
`.verification/derivation-integrity/<run-id>/`.

Testcontainers is the default. To use a caller-created disposable database,
set `NEXT_HIGH_VALUE_IT_JDBC_URL`, `NEXT_HIGH_VALUE_IT_USERNAME`, and
`NEXT_HIGH_VALUE_IT_PASSWORD`, plus the explicit safety acknowledgement
`NEXT_HIGH_VALUE_IT_CLEAN_CONFIRM=YES`. The test cleans the database, so these
variables must never point at development or production. Override the Mock
Playwright preview's initial port with `NEXT_HIGH_VALUE_PLAYWRIGHT_PORT`.

### Managed API Principal Verification

```bash
MANAGED_API_REAL_ENV_FILE=.env \
MANAGED_API_REAL_LLM_PROVIDER=minimax \
./scripts/verify-managed-api-principals.sh --with-real-llm
```

After the Mock and build gates pass, this command uses two backends (default
`18181` and `18182`), one Vite frontend (default `15181`), and disposable
PostgreSQL for real full-stack plus bounded real-LLM acceptance. The V55
matrix verifies idempotent provisioning across two instances, runtime
capability discovery, read-only/read-write capabilities for NORMAL principals,
policy CAS, staged prepare/replay/complete/cancel/deadline/family revoke,
shared quota during overlap, and write rejection before quota accounting.
Real-LLM mode requires nine successful provider calls across immediate data
access and staged complete/cancel/revoke lifecycles, while replayed or rejected
requests must not increase the provider counter. Override port
conflicts with `MANAGED_API_BACKEND_A_PORT`, `MANAGED_API_BACKEND_B_PORT`, and
`MANAGED_API_FRONTEND_PORT`. `MANAGED_API_REAL_LLM_PROVIDER` accepts `openai`,
`minimax`, or `anthropic`; the script validates and loads only the selected
provider. Evidence is written under
`.verification/managed-api-principals/<run-id>/`.

### Managed API Principal Expiry Alert Verification

```bash
# Focused backend, V1-V57 PostgreSQL, and frontend Mock gates
API_KEY_EXPIRY_ALERT_VERIFY_PHASE=focused \
./scripts/verify-api-key-expiry-alerts.sh

# Add Maven clean, lock, documentation, shell, diff, and added-line secret gates
./scripts/verify-api-key-expiry-alerts.sh
```

The script covers expiry-property validation, after-commit Spring Events, the
asynchronous proxy contract, create/update/revoke lifecycle integration,
operator-only Alerts APIs, notification channels, V57 multi-instance
deduplication/CAS, phase transitions, automatic resolution, fair fallback
scans, the WebUI `firedAt` contract, and no-screenshot Alerts Mock Playwright.
It uses `pgvector/pgvector:pg16` Testcontainers by default.
`API_PRINCIPAL_EXPIRY_ALERT_IT_JDBC_URL` and related variables may instead
select an explicitly disposable database. Evidence is written under
`.verification/api-key-expiry-alerts/<run-id>/`.

<a id="business-service-integration-readiness-verification"></a>

### Business Service Integration Readiness Verification

```bash
./scripts/verify-business-client-readiness.sh
```

The gate starts with focused API/core tests, creates disposable PostgreSQL
integration databases serially, runs `mvn clean compile test-compile`, WebUI
typecheck/Vitest/production build, core Mock Playwright, and the
documentation/lock/secret/diff gates. It then starts disposable PostgreSQL, a
deterministic embedding stub, real Spring Boot, and a real Vite frontend for
the generic business-credential HTTP contract and real API-key Playwright.

Rerun only the real-service phase:

```bash
BUSINESS_CLIENT_VERIFY_PHASE=real \
./scripts/verify-business-client-readiness.sh
```

Require a clean Git tree for a final candidate commit:

```bash
BUSINESS_CLIENT_REQUIRE_CLEAN_GIT=true \
./scripts/verify-business-client-readiness.sh
```

Default ports are backend `18084`, embedding stub `18085`, Mock frontend
`15184`, and real frontend `15185`. Override them with
`BUSINESS_CLIENT_BACKEND_PORT`, `BUSINESS_CLIENT_EMBEDDING_PORT`,
`BUSINESS_CLIENT_MOCK_FRONTEND_PORT`, and
`BUSINESS_CLIENT_REAL_FRONTEND_PORT`. Override the PostgreSQL image with
`BUSINESS_CLIENT_POSTGRES_IMAGE`. Evidence is written under
`.verification/business-client-readiness/<run-id>/`; exit traps clean private
credential files, containers, ports, and processes. The real HTTP contract
includes read-only/canary binding preflight, runtime-limit enforcement,
principal/Collection-scoped operation observability, restart persistence, and
Record preservation after a provider `503`. `release-manifest.json` pins the
full Git SHA, initial tree state, project/OpenAPI versions, API base path,
latest Flyway migration, passed steps, PostgreSQL image, HTTP-check count,
verified credential profiles, and observed JSON batch item/payload limits plus
operation-observability state. Runtime facts not reached are JSON `null`; it
stores no credential, URL, payload, external ID, or private path.

The deployed binding runner can also be executed independently against an
already running instance:

```bash
./scripts/business-client-binding-preflight.sh
```

It is read-only by default. Set the `RAG_BINDING_*` inputs documented in the
[Business Service Integration Guide](business-client-integration.md).
`RAG_BINDING_MIN_JSON_BATCH_ITEMS`,
`RAG_BINDING_MIN_JSON_BATCH_PAYLOAD_BYTES`, and
`RAG_BINDING_REQUIRE_OPERATION_OBSERVABILITY` add fail-closed runtime
requirements. Mutation mode is opt-in and must use a dedicated canary
Collection; its report is machine-readable and contains no credential, URL,
Collection key, external ID, or payload.

This gate verifies the real Spring AI embedding HTTP path. The capability does
not change Chat, so it does not call a Chat LLM. See the
[Business Service Integration Guide](business-client-integration.md) for the
integration contract and deployment binding.

### Retrieval diagnostics / metadata filters / embedding operations / managed quality

```bash
./scripts/verify-retrieval-diagnostics.sh
./scripts/verify-retrieval-filters.sh
./scripts/verify-embedding-operations.sh
./scripts/verify-managed-quality.sh
./scripts/verify-no-pessimistic-locks.sh
# or run A–D together:
./scripts/verify-next-high-value-features.sh
```

These gates cover V35 diagnostics, V36 metadata `@>` pushdown, V37 embedding
operations pagination/readiness, V38 managed suites plus citation validation,
and the post-V39 data-access concurrency rule. The lock gate statically rejects
`FOR UPDATE`, `SKIP LOCKED`, JPA `PESSIMISTIC_*`, and PostgreSQL advisory
locks. The other gates start isolated PostgreSQL by default; override with the
matching `*_IT_JDBC_URL` variables.

### JSONB Structured-Record Verification

Run the focused, repeatable gate for the JSONB implementation and its
surrounding API, database, WebUI, documentation, and whitespace checks:

```bash
./scripts/verify-jsonb-records.sh
```

Use `--skip-playwright` only when browser binaries are unavailable and record
the skipped gate. The script starts isolated PostgreSQL itself, avoiding the
Testcontainers 1.20.4 / newer Docker daemon API negotiation issue. Reuse a
caller-provided isolated database with `JSONB_IT_JDBC_URL`,
`JSONB_IT_USERNAME`, and `JSONB_IT_PASSWORD`; override the image with
`TESTCONTAINERS_PG_IMAGE`. Logs and a Markdown summary are written to
`.verification/jsonb-verification/<run-id>/`.
The Mock Playwright preview uses `JSONB_PLAYWRIGHT_PORT` (default `4174`) with
strict port binding and never reuses an unrelated process. If that port is
occupied, choose an unused one, for example:

```bash
JSONB_PLAYWRIGHT_PORT=4199 ./scripts/verify-jsonb-records.sh
```

Run this gate serially: its `mvn clean` step must not overlap another Maven
test process that uses the same module `target/` directories.

### JSONB Live HTTP E2E

Run the JSON structured-record HTTP flow against an already running PostgreSQL
profile service:

```bash
BASE_URL=http://127.0.0.1:18081 \
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/jsonb-records-e2e.sh
```

The script verifies JSON-record upsert, collection-scoped search, detail,
payload-only updates, `retrievalText` updates, clone/export/import, and
allow/deny behavior using a temporary restricted API key created by the root.
`embed=true` calls the real embedding provider but does not call a Chat LLM.
Use `--skip-acl` only when the server intentionally has no usable root
credential, and record that skip. The script never prints API keys or complete
payloads; temporary responses are removed from ignored
`.verification/jsonb-e2e/` storage on exit.

<a id="external-document-synchronization-http-e2e"></a>

### External Document Synchronization HTTP E2E

Run the ordinary external-document synchronization flow against an already
running PostgreSQL-profile service:

```bash
BASE_URL=http://127.0.0.1:18081 \
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/external-documents-e2e.sh
```

The script verifies create with `embed=false`, exact replay, update with
`expectedSourceRevision`, CAS conflict, same-revision conflict, batch upsert,
lookup by external identity, tombstone deletion/replay, and restoration with a
distinct subsequent `sourceRevision`. By default it also verifies successful re-embedding after content
change. Set `EXTERNAL_DOCUMENT_E2E_EMBED=false` only when the embedding provider
is intentionally unavailable; the run then records the embedding check as
skipped rather than claiming a completed vector path. Logs are written under
ignored `.verification/external-documents-e2e/` and the script never prints API
keys or complete document content.

## 8. Goldenset And Release Gates

Retrieval goldenset:

```bash
BASE_URL=http://127.0.0.1:8081 ./scripts/run-retrieval-goldenset.sh
```

Versioned live retrieval regression:

```bash
BASE_URL=http://127.0.0.1:18081 ./scripts/verify-quality-regression.sh
```

Rerank document-diversity acceptance, including focused backend tests,
PostgreSQL/pgvector, WebUI gates, isolated `dev.sh`, real Search/Playwright,
goldenset, versioned regression, and real LLM checks:

```bash
./scripts/verify-rerank-document-diversity.sh
```

The runner refuses to replace an existing `.dev` stack, defaults to isolated
ports `18083`/`15175`, creates a disposable PostgreSQL database (local first,
Docker fallback), keeps the generated root key in the shell only, and writes
evidence under `.verification/rerank-document-diversity/`. After the real
provider baseline passes, it restarts the service with cap=`0` and cap=`2`
against the same database and fixture, collecting 20 Search and 5 Chat samples
per variant by default. It correlates trace IDs through read-only
`rag_retrieval_logs` queries and writes retrieval/rerank p95, HTTP response
payload, and final document-coverage observations to `runtime-comparison.json`
and `runtime-comparison.md`. Wall-clock and payload values are evidence, not
unstable threshold gates.

The correlated database result count is the latest retrieval-outcome count.
Search requires it to match the final HTTP count. KNOWLEDGE Chat records the
relationship separately because its HTTP sources are produced after advisor
query joining, reranking, and prompt-budget processing.

Chat samples retry explicit transient HTTP `429/502/503/504` responses within
the positive `RERANK_DIVERSITY_CHAT_MAX_ATTEMPTS` bound (default `2`). All
retries are logged; Search and non-retryable failures remain fail-fast.

The dataset and committed baseline live under `testdata/regression/`. The
runner creates fixtures by stable
`collectionKey + sourceNamespace(default) + externalId`, checks Hit
Rate, MRR, Recall@K, nDCG, metric floors, baseline regression, Collection-decoy
leakage, and an explicit-empty JSONB case, then writes JSON artifacts and a
Markdown summary under `.verification/quality-regression/<run-id>/`. When
`RAG_API_KEY` is not set explicitly, it safely reads `RAG_API_KEY` /
`RAG_ROOT_API_KEY` from `.env` without printing the credential.
Run `./scripts/run-retrieval-regression.sh --self-test` without a service to
check recognition of the current `READY` and compatible `COMPLETED/CACHED`
successful embedding statuses.

One-command release verification:

```bash
./scripts/verify-release.sh
./scripts/verify-release.sh --with-quality-regression
./scripts/verify-release.sh --with-local-runtime
```

`--with-quality-regression` adds the versioned gate against the running
`BASE_URL`. `--with-local-runtime` includes HTTP E2E, goldenset, quality
regression, and real-LLM smoke.

Logs and summaries are written to `target/release-verification/<run-id>/`. See [release-checklist.md](release-checklist.md).

## 9. Docker And Mainland-China Networking

Preferred local build:

```bash
./scripts/docker-build-local.sh
```

Keep Dockerfile base images overridable instead of hard-coding regional mirrors. See [china-network-guide.md](china-network-guide.md) for DaoCloud, Aliyun Maven, npm, Playwright, and Git proxy guidance.

## 10. Key Paths

| Path | Purpose |
|------|---------|
| `spring-ai-rag-api/` | DTOs and SPIs |
| `spring-ai-rag-core/` | Core implementation and runnable app |
| `spring-ai-rag-starter/` | Auto-configuration |
| `spring-ai-rag-documents/` | Document processing |
| `spring-ai-rag-webui/` | React admin UI |
| `scripts/` | Startup, E2E, goldenset, documentation and release verification |
| `docker/` | Dockerfile and Compose |
| `k8s/` | Helm chart |

## 11. Troubleshooting

- General: [troubleshooting.md](troubleshooting.md)
- Configuration: [configuration.md](configuration.md)
- Mainland-China networking: [china-network-guide.md](china-network-guide.md)
- Claude Code + grok: [claude-grok-proxy.md](claude-grok-proxy.md)
