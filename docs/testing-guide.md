# Testing Guide

> 📖 [English](testing-guide.md) · 📖 [中文](testing-guide-zh-CN.md)

> Spring AI RAG project philosophy on testing: "Tests are production code" — write tests alongside code, `mvn test` must pass before considering work done.

> **Standing rules** (project requirement — do not weaken):  
> - Production code and tests are written together and treated equally  
> - Work is “done” only when `mvn test` fully passes  
> - After REST endpoint changes, run E2E (`scripts/e2e-test.sh`)  
> - After WebUI changes, run Playwright (`scripts/webui-e2e-test.js` / `npm run test:e2e`)  
> - After meaningful improvements: restart the service → confirm `http://localhost:8081` is up → run regression  

Doc hub: [index.md](index.md) · Commands: [../TOOLS.md](../TOOLS.md)

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
2. `POST /api/v1/rag/documents` — Create document
3. `GET /api/v1/rag/documents/{id}` — Get document (with JSONB metadata)
4. `GET /api/v1/rag/documents` — Document list (pagination)
5. `POST /api/v1/rag/documents/{id}/embed` — Generate embedding vectors
6. `GET /api/v1/rag/search` — Direct retrieval
7. `POST /api/v1/rag/chat/ask` — RAG Q&A
8. `POST /api/v1/rag/chat/stream` — Streaming response (SSE)
9. `GET /api/v1/rag/chat/history/{sessionId}` — Conversation history
10. `DELETE /api/v1/rag/documents/{id}` — Delete document + verify 404

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

Unit tests use H2 in-memory database (default); integration tests can use Testcontainers:

```java
@Testcontainers
@SpringBootTest
class PgVectorStoreIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
    }
}
```

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
