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
| Flyway | V1–V46 |

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

For the focused V42 Sync Run HTTP acceptance against a disposable PostgreSQL
database:

```bash
./scripts/verify-document-sync-runs.sh
```

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

### Chat Capability Verification

Run the Chat redesign gate serially because it includes Maven clean output:

```bash
./scripts/verify-chat-capability.sh
```

The script verifies `KNOWLEDGE`, `AGENT`, and `PLAIN` mode execution, Spring AI
Tool Calling boundaries, principal-scoped memory/history, V32 session leases,
V46 durable summary CAS, bounded execution metadata, structured SSE, WebUI
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

The dataset and committed baseline live under `testdata/regression/`. The
runner creates fixtures by stable
`collectionKey + sourceNamespace(default) + externalId`, checks Hit
Rate, MRR, Recall@K, nDCG, metric floors, baseline regression, Collection-decoy
leakage, and an explicit-empty JSONB case, then writes JSON artifacts and a
Markdown summary under `.verification/quality-regression/<run-id>/`. When
`RAG_API_KEY` is not set explicitly, it safely reads `RAG_API_KEY` /
`RAG_ROOT_API_KEY` from `.env` without printing the credential.

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
