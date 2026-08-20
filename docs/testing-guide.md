# Testing Guide

> 📖 [English](testing-guide.md) · 📖 [中文](testing-guide-zh-CN.md)

> Spring AI RAG project philosophy on testing: "Tests are production code" — write tests alongside code, `mvn test` must pass before considering work done.

> **Standing rules** (project requirement — do not weaken):  
> - Production code and tests are written together and treated equally  
> - Work is “done” only when `mvn test` fully passes  
> - After REST endpoint changes, run E2E (`scripts/e2e-test.sh`)  
> - After WebUI changes, run Playwright (`scripts/webui-e2e-test.js` / `npm run test:e2e`)  
> - After meaningful improvements: restart the service → confirm `http://localhost:8081` is up → run regression  

Doc hub: [index.md](index.md) · Commands: [developer-reference.md](developer-reference.md) · Plan-to-delivery workflow: [delivery-workflow.md](delivery-workflow.md)

## Testing Pyramid

```
    ┌──────────┐
    │  E2E Tests │  scripts/e2e-test.sh
    ├──────────┤
    │ Integration │  @SpringBootTest
    ├──────────┤
    │   Unit     │  JUnit 5 + Mockito
    └──────────┘
```

## Quick Start

```bash
# Run all unit + integration tests
export $(cat .env | grep -v '^#' | xargs) && mvn test

# Test only a specific module
mvn test -pl spring-ai-rag-core

# Run a specific test class
mvn test -pl spring-ai-rag-core -Dtest=RagDocumentControllerTest

# Skip tests during build
mvn clean package -DskipTests
```

## One-command Documentation Verification

`scripts/verify-project-docs.sh` codifies the project-documentation checklist: OpenClaw local-state isolation, `.agents/skills/` trackability, relative links, EN/ZH heading structure, entry size limits, fixed project conventions, documented commands, shell syntax, whitespace, and added-line secret scanning.

```bash
./scripts/verify-project-docs.sh
```

The default release verification also runs this gate.

## One-command Release Verification

`scripts/verify-release.sh` codifies the 1.0 release gates, including embedded-WebUI reference, file-existence, and Git-trackability checks. Per-step stdout/stderr, a status table, and a Markdown summary are written to `target/release-verification/<run-id>/`:

```bash
# Default: static checks, Maven, WebUI, Playwright, Helm, Docker
./scripts/verify-release.sh

# Reuse an existing node_modules directory
./scripts/verify-release.sh --skip-npm-ci

# Add HTTP E2E and goldenset against a running PostgreSQL-profile service
BASE_URL=http://127.0.0.1:8081 \
  ./scripts/verify-release.sh --with-runtime-e2e --with-goldenset

# Add versioned live retrieval regression against a running service
BASE_URL=http://127.0.0.1:18081 \
  ./scripts/verify-release.sh --with-quality-regression

# The real-LLM server normally runs on 18081 via start-real-e2e-server.sh
./scripts/verify-release.sh --with-real-llm

# Complete local gate: start a PostgreSQL-profile server, run HTTP E2E,
# goldenset, quality regression, and real-LLM smoke, archive logs, then stop it
./scripts/verify-release.sh --with-local-runtime
```

`--with-local-runtime` requires PostgreSQL/pgvector plus working database, embedding, and chat-LLM settings in `.env`. It exclusively owns port `18081` by default and fails when the port is occupied, so it never reuses or kills an unrelated service. Override the port with `RUNTIME_SERVER_PORT`. On success, failure, or interruption, the script archives its logs and stops the service it started.

Docker uses a mainland-China mirror first and falls back to official sources. See the [mainland China network guide](china-network-guide.md). External-service failures must remain failed or explicitly skipped; they must not be reported as a release pass.

## Test Categories

### Unit Tests (JUnit 5 + Mockito)

**Goal**: Verify logic of individual classes/methods without external service dependencies.

**Naming convention**: `{ClassName}Test.java`

**Location**: Each module's `src/test/java/`

**Example**:
```java
@SpringBootTest
@AutoConfigureMockMvc
class RagDocumentControllerTest {

    @MockBean
    private RagDocumentService ragDocumentService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldReturnDocumentById() throws Exception {
        when(ragDocumentService.findById(1L)).thenReturn(Optional.of(doc));

        mockMvc.perform(get("/api/v1/rag/documents/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Test"));
    }
}
```

**Mock guidelines**:
- Use `@MockBean` for Spring context integration
- Service layer can use `@Mock` + `@ExtendWith(MockitoExtension.class)` for pure unit tests
- Use `@DataJpaTest` for database-related slice tests

### Integration Tests (@SpringBootTest)

**Goal**: Verify component collaboration with real Spring context.

**Naming convention**: `{ClassName}IntegrationTest.java`

**Key annotations**:
```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers   // if PostgreSQL is needed
class RagChatControllerIntegrationTest {
    // Test complete RAG Pipeline: query → rewrite → retrieval → rerank → LLM
}
```

### E2E Tests (Shell + curl)

**Goal**: Verify HTTP endpoint full链路 (real service running).

**Script**: `scripts/e2e-test.sh`

**Usage**:
```bash
# Start the service
export $(cat .env | grep -v '^#' | xargs) && bash scripts/start-server.sh

# Run E2E tests in another terminal
export $(cat .env | grep -v '^#' | xargs) && bash scripts/e2e-test.sh
```

E2E test coverage:
1. `GET /api/v1/rag/health` — Health check
2. `POST /api/v1/rag/collections` plus by-key get/update/list/delete — Collection Key lifecycle
3. `POST /api/v1/rag/documents` — Create document
4. `GET /api/v1/rag/documents/{id}` — Get document (with document metadata)
5. `GET /api/v1/rag/documents` — Document list (pagination)
6. `POST /api/v1/rag/documents/{id}/embed` — Generate embedding vectors
7. `GET /api/v1/rag/search` — Direct retrieval
8. `POST /api/v1/rag/chat/ask` — RAG Q&A
9. `POST /api/v1/rag/chat/stream` — Streaming response (SSE)
10. `GET /api/v1/rag/chat/history/{sessionId}` — Conversation history
11. `DELETE /api/v1/rag/documents/{id}` — Delete document + verify 404

The script generates a unique visible-ASCII `collectionKey` for each run and
uses by-key Collection routes plus `collectionKey(s)` on ingestion and
retrieval requests. This prevents repeat runs from colliding with the global
unique constraint.

For the JSONB structured-record live HTTP acceptance flow, use:

```bash
BASE_URL=http://127.0.0.1:18081 \
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/jsonb-records-e2e.sh
```

It covers JSON-record upsert, collection-scoped search, detail, payload-only
updates, `retrievalText` updates, clone/export/import, and allow/deny behavior
with a temporary restricted API key. It needs a real embedding provider; if no
root credential is available, use `--skip-acl` explicitly and record the skip.
The script does not call a Chat LLM and does not print API keys or complete
payloads.

### External Document Synchronization Acceptance Gate

The ordinary external-document path has service, MockMvc, OpenAPI, migration,
and live HTTP coverage. Run the live flow against a PostgreSQL-profile server:

```bash
BASE_URL=http://127.0.0.1:18081 \
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/external-documents-e2e.sh
```

It covers stable identity, exact replay, source-revision CAS, same-revision
conflict, batch isolation, lookup by external identity, tombstone deletion and
replay, restoration, and embedding freshness. The default run requires a
working embedding provider for the content-changing update. Use
`EXTERNAL_DOCUMENT_E2E_EMBED=false` only for an explicitly documented provider
outage; that run validates persistence and no-embedding status but does not
count as the completed embedding acceptance gate.

The migration matrix normally uses Testcontainers:

```bash
mvn -pl spring-ai-rag-core \
  -Dtest=ExternalDocumentSyncPostgresIntegrationTest \
  -Dexternal-document.it.enabled=true \
  test
```

If Docker is unavailable, provide a dedicated disposable PostgreSQL database
through `EXTERNAL_DOCUMENT_IT_JDBC_URL`, `EXTERNAL_DOCUMENT_IT_USERNAME`, and
`EXTERNAL_DOCUMENT_IT_PASSWORD`, and explicitly set
`EXTERNAL_DOCUMENT_IT_CLEAN_CONFIRM=YES`. The test calls `Flyway.clean()`
repeatedly; never point these variables at a development or production
database.

<a id="document-lifecycle-verification"></a>

### Document CRUD And Derived-Index Lifecycle Gate

```bash
./scripts/verify-document-lifecycle.sh
```

The script serially runs:

1. the no-pessimistic-lock static gate;
2. focused local CRUD, external TEXT/JSON, Collection/PDF/batch entry-point,
   and generation-job tests;
3. V39-to-V43, triple-identity, freshness, local-generation/vector-generation
   fencing, transaction
   rollback, and hard-delete cascade acceptance on disposable PostgreSQL, with
   Surefire XML parsing that requires `skipped=0`;
4. reference-client HTTP retry, CAS, checkpoint resume, and secret-not-at-rest
   tests;
5. `mvn clean compile test-compile` and the full backend suite;
6. WebUI Vitest, production build, alignment, and Documents Mock Playwright;
7. bilingual project-documentation and `git diff --check` gates.

The focused V42 HTTP contract can also be run independently:

```bash
./scripts/verify-document-sync-runs.sh
```

It migrates a disposable database through V43, exercises Sync Run begin,
batch idempotency, failure retry, preview/complete tombstoning, namespace
isolation, and the no-pessimistic-lock gate.

### Local Keyword / Vector Decoupling Gate

```bash
KEYWORD_VECTOR_VERIFY_RUN_ID=full-gate-4 \
KEYWORD_VECTOR_PLAYWRIGHT_PORT=4191 \
./scripts/verify-keyword-vector-decoupling.sh
```

This focused one-command gate migrates an isolated PostgreSQL database through
V43, runs the local-chunk lifecycle and English/Chinese/trigram full-text
integration tests, requires `skipped=0`, runs
`mvn clean compile test-compile`, and verifies the WebUI with TypeScript,
Vitest, production build, alignment, and no-screenshot Mock Playwright. It
also runs the pessimistic-lock static gate and writes evidence under
`.verification/keyword-vector-decoupling/<run-id>/`.

PostgreSQL selection is: explicit `DOCUMENT_LIFECYCLE_IT_JDBC_URL`, a
disposable database created from current-shell/`.env` `POSTGRES_*`, then a
pgvector container started directly by the Docker CLI. A caller-provided JDBC
URL must identify a disposable database. Mock Playwright uses DOM, network, and
test assertions only; screenshots are disabled. Evidence is written under
`.verification/document-data-plane/<run-id>/summary.md`.

## Coverage

JaCoCo is integrated into all modules:

```bash
# Generate coverage report
mvn clean test jacoco:report

# Report location
# spring-ai-rag-core/target/site/jacoco/index.html
# spring-ai-rag-api/target/site/jacoco/index.html
# spring-ai-rag-documents/target/site/jacoco/index.html
# spring-ai-rag-starter/target/site/jacoco/index.html
```

**Coverage targets**:
- Instruction coverage ≥ 90%
- Branch coverage ≥ 75%

**View coverage**:
```bash
# Quick view (terminal output)
mvn jacoco:check

# Merge multi-module reports
mvn jacoco:report-aggregate
```

## Test Database

Unit tests use mocks or H2-compatible paths. The Embedding Profile migration has
an explicit PostgreSQL integration test because it requires pgvector and validates
Flyway V1-V43, fixed vector columns, Profile-specific indexes, atomic replacement,
Legacy adoption, retrieval freshness, and Spring Data repository queries.

Start a PostgreSQL 16 + pgvector database, then run:

```bash
mvn -pl spring-ai-rag-core -am \
  -Dtest=EmbeddingProfilePostgresIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Drag.it.jdbc-url=jdbc:postgresql://127.0.0.1:35267/embedding_profile_test \
  -Drag.it.username=postgres \
  -Drag.it.password=postgres \
  test
```

The test is skipped when `rag.it.jdbc-url` is absent, so the explicit command is
the required migration acceptance gate.

### Collection Key Acceptance Gate

Collection identity has focused DTO, resolver, ACL, service, controller,
MockMvc, OpenAPI, and PostgreSQL coverage. The real PostgreSQL/Testcontainers
test executes V27/V28 and verifies legacy backfill collision avoidance,
1/128-character boundaries, visible-ASCII checks, case sensitivity, soft-delete
reservation, SQL-level immutability, and concurrent uniqueness:

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -pl spring-ai-rag-core -am \
  -Dapi.version=1.40 \
  -Dcollection-key.it.enabled=true \
  -Dtest=CollectionKeyPostgresIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

Before runtime acceptance, run the compile and focused integration gates
serially:

```bash
mvn clean compile test-compile

mvn -pl spring-ai-rag-core -am \
  -Dtest='*Collection*,*ApiKey*,OpenApiContractTest,RagControllerIntegrationTest,RagSearchControllerTest,RagChatControllerTest,PdfImportControllerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

WebUI acceptance requires `npm run test:run`, `npm run build`, and the Mock API
Playwright suite. Runtime smoke should then create a unique key and cover
create, by-key get/update, clone with a new target key, export/import with a new
key, soft delete, restore, duplicate conflict, and document/search/chat key
inputs.

### Multi-Collection Retrieval Acceptance Gate

The scope implementation has DTO, resolver, ACL, SQL-fragment, vector/full-text
provider, Chat/Search/JSON, MockMvc, OpenAPI, WebUI, and PostgreSQL coverage.
The real PostgreSQL/Testcontainers test starts `pgvector/pgvector:pg16`, runs
Flyway V1-V43 from an empty schema, and exercises the vector query with actual
PostgreSQL `bigint[]` bindings:

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -pl spring-ai-rag-core -am \
  -Dapi.version=1.40 \
  -Dmulti.collection.it.enabled=true \
  -Dtestcontainers.pg.image=pgvector/pgvector:pg16 \
  -Dtest=MultiCollectionRetrievalPostgresIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

The matrix verifies that unrestricted `CALLER_VISIBLE` includes unassigned
documents, `ANY_COLLECTION` excludes them, selected A+B cannot return another
Collection, an empty selected Collection stays empty, selected Collections and
explicit document IDs intersect in SQL, JSON records honor `document_type`, and
disabled, stale, or wrong-Profile documents remain excluded. Focused provider
tests separately verify that English FTS, pg_jieba, and pg_trgm use the same
`RetrievalScopeSql` predicates.

WebUI acceptance covers all three modes, multi-selection, server-side
Collection search and pagination, selected-empty blocking, and the Chat SSE
object request. Run `npm run test:run`, `npx tsc -b --pretty false`,
`npm run build`, and the core Mock Playwright suite.

### JSONB Structured-Record Acceptance Gate

The JSONB implementation has both mocked HTTP/service coverage and a real
PostgreSQL/Testcontainers test. The latter starts `pgvector/pgvector:pg16`,
executes Flyway V1-V43 from an empty database, and verifies JSONB round-trip,
nested `payloadContains`, V34 GIN planner use, payload-only versioning,
identical descriptions with distinct records, and cascade cleanup:

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -pl spring-ai-rag-core -am \
  -Dapi.version=1.40 \
  -Djsonb.it.enabled=true \
  -Dtest=JsonbStructuredRecordsPostgresIntegrationTest \
  test
```

The focused all-layer gate is:

```bash
./scripts/verify-jsonb-records.sh
```

It also runs the API DTO, chunker, JSON service/controller/OpenAPI, Maven
compile, WebUI build, Mock Playwright, project-docs, and whitespace checks.
`.verification/jsonb-verification/<run-id>/summary.md` is the recorded result.
The browser preview binds strictly to `JSONB_PLAYWRIGHT_PORT` (default `4174`);
use an unused port when another local service occupies it. Run the complete
gate serially because the Maven clean phase removes module `target/` output.

### OpenAI Compatibility Preview Acceptance Gate

```bash
./scripts/verify-openai-compatibility.sh
```

The fixed scope covers model aliases, body/header Collection-scope merging,
API-key ACLs, complete text-only messages, unknown-alias errors,
non-streaming JSON, SSE role/content/finish chunk order, and final `[DONE]`.
The script also runs focused tests, `test-compile`, shell syntax, and whitespace
checks, writing evidence under
`.verification/openai-compatibility/<run-id>/`.

### Durable Embedding Jobs Acceptance Gate

```bash
./scripts/verify-embedding-jobs.sh
```

The script serially runs service/worker/controller focused tests, starts
isolated PostgreSQL, migrates an empty database through V1–V43, verifies V33
active-job coalescing, atomic force upgrades, and concurrent-worker atomic
conditional claims, then runs `test-compile`, shell syntax, and whitespace
checks. Set `EMBEDDING_JOBS_IT_JDBC_URL` to reuse an existing isolated database.

### Next High-Value Features Acceptance Gates

```bash
./scripts/verify-retrieval-diagnostics.sh
./scripts/verify-retrieval-filters.sh
./scripts/verify-embedding-operations.sh
./scripts/verify-managed-quality.sh
./scripts/verify-no-pessimistic-locks.sh
# or run A–D serially:
./scripts/verify-next-high-value-features.sh
```

The fixed scopes cover retrieval diagnostics (V35), metadata/payload filters
(V36), embedding operations (V37, including SYNC/ASYNC/SKIP, ACL pagination,
and readiness), citation / managed suites (V38), and the post-V39 static gate
that prohibits explicit pessimistic coordination. The aggregator must run
serially because Maven clean removes module `target/` output.

### Live Retrieval Quality Regression Gate

```bash
BASE_URL=http://127.0.0.1:18081 \
  ./scripts/verify-quality-regression.sh
```

The gate first validates the contract between
`testdata/regression/retrieval-core-v1.json` and the committed baseline, then
calls real embedding/search APIs. Stable
`collectionKey + sourceNamespace(default) + externalId` identities define
relevance. It checks Hit Rate, MRR, Recall@K, nDCG, per-case
minimums, baseline regression, Collection-decoy leakage, and an explicit-empty
JSONB result. Provider, database, or embedding failures return nonzero and are
never reported as quality passes.

### Chat Capability Redesign Acceptance Gate

The Chat implementation is verified as three explicit modes:

- `KNOWLEDGE`: Spring AI Modular RAG with the project's hybrid retrieval and
  rerank implementation.
- `AGENT`: Spring AI Tool Calling with the server-owned retrieval scope.
- `PLAIN`: ordinary ChatClient plus memory, without knowledge retrieval.

Run the repeatable local gate:

```bash
./scripts/verify-chat-capability.sh
```

It runs the Chat execution, Tool Calling, memory/history, structured SSE,
controller/integration, and export tests; the V32 PostgreSQL lease/atomicity
test; `mvn clean compile test-compile`; full `mvn test`; installation of the
current reactor artifacts followed by the independent
`demos/demo-domain-extension` consumer tests; a Spring Boot startup/health
smoke with temporary PostgreSQL and dummy model endpoints; WebUI Vitest,
TypeScript, production build; Chat core Mock Playwright; project-docs; and
whitespace checks. Logs and a Markdown result are written to
`.verification/chat-capability/<run-id>/summary.md`.

The demo is outside the root reactor. Running Maven directly in the demo may
otherwise resolve an older local `spring-ai-rag-starter:1.0.0`; the one-click
gate installs the current workspace artifacts before testing that consumer.

The browser gate uses DOM visibility, request/response assertions, URL
assertions, and test assertions. Screenshots are not used as correctness
evidence. The browser suite covers mode/model requests, AGENT tool lifecycle,
sources, history source restoration, selected Collections, and mobile
overflow.

The PostgreSQL gate is attempted by default. If Docker is unavailable or the
daemon rejects the negotiated API version, the script records both Docker and
PostgreSQL gates as `SKIP`; `PASS_WITH_SKIPS` is not a complete release gate.
This repository has observed Testcontainers negotiating Docker API `1.32` while
the local daemon required at least `1.40`. Use:

```bash
TESTCONTAINERS_API_VERSION=1.40 \
TESTCONTAINERS_RYUK_DISABLED=true \
./scripts/verify-chat-capability.sh
```

`ChatSessionPostgresIntegrationTest` also accepts a disposable external
database to bypass Testcontainers Docker-API negotiation. It calls
`Flyway.clean()`, so never point it at development or production:

```bash
mvn -pl spring-ai-rag-core \
  -Dtest=ChatSessionPostgresIntegrationTest \
  -Dchat.it.enabled=true \
  -Dchat.it.jdbc-url=jdbc:postgresql://127.0.0.1:5432/disposable_chat_test \
  -Dchat.it.username=postgres \
  -Dchat.it.password=postgres \
  -Dchat.it.clean-confirm=YES \
  test
```

Use `--skip-postgres` only when the environment is intentionally unavailable
and retain the generated summary. The local workaround and mainland-China
registry/certificate notes are documented in
[china-network-guide.md](china-network-guide.md) and
[troubleshooting.md](troubleshooting.md).

The backend smoke binds strictly to `CHAT_STARTUP_PORT=4210`; override it when
that port is occupied. Use `--skip-startup` only when Docker is intentionally
unavailable and retain the resulting `SKIP` record.

Real provider verification is optional and explicit:

```bash
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/verify-chat-capability.sh --with-real-llm
```

The script loads real model configuration from `.env`; when
`RAG_ROOT_API_KEY` is configured, it uses `X-API-Key` for data-plane calls.
Without this option, the real LLM gate is recorded as `SKIP`; local tests and
Mock Playwright never imply a real model/tool-capable endpoint was validated.

## Rules for Writing New Tests

1. **Write tests before implementation** (TDD-friendly)
2. **At least one positive test + one boundary test per public method**
3. **Controller tests** use `MockMvc`, no real HTTP server
4. **Service tests** use `@Mock` pure unit tests or `@SpringBootTest` integration tests
5. **Never `@Ignore` tests** — fix them or delete them
6. **Test names use `should_ describe expected behavior` format**
7. **Each test is independent** — no execution order dependency

## Performance Benchmark Tests

`RetrievalBenchmarkTest` verifies core operations complete within reasonable time:

| Operation | Target | Actual |
|-----------|--------|--------|
| Vector retrieval | < 500ms | ~1.9ms |
| Fusion retrieval | < 500ms | ~6ms |
| Cosine calculation (100k) | < 200ms | ~75ms |

## FAQ

### `mvn test` reports "Connection refused"

PostgreSQL not started or `.env` not loaded:

```bash
# Confirm PostgreSQL is running
pg_isready -h localhost -p 5432

# Load environment variables
export $(cat .env | grep -v '^#' | xargs)
```

### Embedding model tests too slow

Embedding model calls (SiliconFlow API) require network; mock in CI:

```java
@MockBean
private EmbeddingModel embeddingModel;

@BeforeEach
void setup() {
    when(embeddingModel.embed(any(String.class)))
        .thenReturn(new float[1024]); // Return fixed vector
}
```

### JaCoCo coverage inaccurate

```bash
# Clean and retest
mvn clean test jacoco:report
```
