# Testing Guide

> 📖 [English](testing-guide.md) · 📖 [中文](testing-guide-zh-CN.md)

> Spring AI RAG project philosophy on testing: "Tests are production code" — write tests alongside code, `mvn test` must pass before considering work done.

> **Standing rules** (project requirement — do not weaken):  
> - Production code and tests are written together and treated equally  
> - Work is “done” only when `mvn test` fully passes  
> - After REST endpoint changes, run E2E (`scripts/e2e-test.sh`)  
> - After WebUI changes, run Playwright (`scripts/webui-e2e-test.js` / `npm run test:e2e`)  
> - After meaningful improvements: restart the service → confirm `http://localhost:8081` is up → run regression  

Doc hub: [index.md](index.md) · Commands: [developer-reference.md](developer-reference.md)

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

# The real-LLM server normally runs on 18081 via start-real-e2e-server.sh
./scripts/verify-release.sh --with-real-llm

# Complete local gate: start a PostgreSQL-profile server, run HTTP E2E,
# goldenset, and real-LLM smoke, archive logs, then stop that server
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
Flyway V1-V30, fixed vector columns, Profile-specific indexes, atomic replacement,
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
Flyway V1-V30 from an empty schema, and exercises the vector query with actual
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
executes Flyway V1-V30 from an empty database, and verifies JSONB round-trip,
payload-only versioning, identical descriptions with distinct records, and
cascade cleanup:

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
