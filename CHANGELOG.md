# Changelog

📖 [English](CHANGELOG.md) · 📖 [中文](CHANGELOG-zh-CN.md)

---

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.0.0-SNAPSHOT] - 2026-04-11

### Added
- `evaluateAnswerQuality` bounded timeout + fallback: `CompletableFuture.orTimeout()` 10s timeout with graceful degradation to "unknown" quality when LLM call hangs or times out
- `EmailNotificationService` exponential backoff retry: 3 retries with exponential backoff (2s/4s/8s) for SMTP failures, retryable exception classification
- `RetrievalEvaluationServiceImpl` Micrometer metrics: `rag.evaluation.duration` (Timer) + `rag.evaluation.count/batch_count/hits/misses` (Counter)
- Document date range filter: `GET /documents?createdAfter=&createdBefore=` parameters on `listDocuments` endpoint
- `CollectionMapper` utility extraction: `toCollectionSummary/toDocumentSummary/toCollectionResponse` methods extracted from `RagCollectionController` for reuse
- `DocumentMapper` utility extraction: `toDocumentResponse/toBatchEmbedResult/toReembedResult` methods extracted from `RagDocumentController`
- `EmbeddingCircuitBreaker`: circuit breaker wrapping `EmbeddingBatchService.embedBatch()` — open on consecutive failures, half-open probe, resume on recovery
- SSE heartbeat mechanism: `SseEmitters.sendHeartbeat()` + `RagSseProperties.heartbeat-interval-seconds` (default 30s) prevent proxy idle-connection closure
- SSE emitter helper: `SseEmitters` utility class (`sendProgress/sendDone/sendError/sendHeartbeat/completeWithError`) extracted from `RagChatController`
- `@Version` optimistic locking on 6 mutable entities (`RagDocument`, `RagCollection`, `RagAlert`, `RagAbExperiment`, `RagRetrievalLog`, `RagUserFeedback`)
- k6 load test suite (K6-1 to K6-7): Collection CRUD / A/B experiments / Alerts & Feedback / chat latency split / ramp-to-saturation / persistent session stress / vector search stress
- Mock LLM server `scripts/mock-llm-server.js`: OpenAI-compatible `/v1/chat/completions` + `/v1/embeddings`, SSE streaming, configurable delay/error rate
- WebUI: SSE streaming with typewriter effect, file upload progress, dark mode auto-follow-system + manual toggle lock
- WebUI: search history with localStorage persistence + deduplication
- WebUI: error boundary with client error reporting (`POST /api/v1/rag/client-errors`)
- WebUI: i18n framework with `react-i18next` (English + Chinese, Settings language switcher)
- WebUI: W12 Document version comparison UI (LCS diff algorithm, two-version side-by-side)
- WebUI: W13 A/B Testing real-time dashboard (Recharts bar charts, significance testing, variant tables)

### Fixed
- `listDocuments` N+1 query: batch-fetch all collection names in single `findAllById()` call — O(N)→O(1) DB round trips
- `evaluateAnswerQuality` exception paths: `TimeoutException` returns quality "unknown" with "timed out" reason; `InterruptedException` propagates with "interrupted" reason
- `batchEmbedDocumentsWithProgress` ClassCastException: `embeddingsStored` from `countByDocumentId` returns Long, not Integer
- `RagChatService.invokeChatClient()` null-check: added null guard for LLM result before accessing `getContent()`
- SSE `sendHeartbeat()` format: uses `.comment()` SSE API for proper comment format instead of `.name("heartbeat")`
- SSE `sendError()` fallback: completes normally on successful send, falls back to `completeWithError()` only on failure
- `ChatModelRouter.resolve()` NPE: added null-check on `providerId` to prevent NPE in multi-model routing
- `MiniMax` base-url `/v1` removal (MiniMax endpoint already contains `/v1`)
- `MiniMax` `role:system` incompatibility: `ApiCompatibilityAdapter` detects and auto-converts system→user with `[System]` prefix
- V17 Flyway migration: fixed table names (`rag_document`→`rag_documents`, `rag_alert`→`rag_alerts`, etc.)
- OpenApiContractTest context loading: added `@MockBean RagClientErrorRepository` (needed after `ClientErrorServiceImpl` constructor change)
- CorsConfig causing test failures: RagControllerIntegrationTest uses static `@TestConfiguration` providing real `RagProperties` instance
- `RetrievalUtils` NaN scores: `fuseResults` now handles all-zero vector/fulltext scores gracefully (no division-by-zero NaN)
- Search score NaN: `ddText` score null-safe fallback `?? 0.0`
- SSE streaming parsing: use `split('\n\n')` for proper SSE event separation

### Changed
- `RetrievalEvaluationServiceImpl`: bounded timeout 10s → LLM call, fallback quality=3/3/3 "unknown" on timeout
- `ApiCompatibilityAdapter`: 8 new edge case tests (6→14 total), `supportsSystemMessage()`/`normalizeMessages()` fully covered
- `ModelRegistry`: 27 new routing tests (10→37 total), `getPrimaryEmbeddingModel()`/`getEmbeddingModelByProvider()` fully covered
- `DocumentEmbedService`: `batchEmbedDocumentsWithProgress` refactored — extracted `sendDocumentProgress/updateBatchCounters/phaseForStatus/phaseMessage/buildBatchResult`
- OpenAPI contract test: excludes `DataSourceAutoConfiguration` + `HibernateJpaAutoConfiguration` to avoid DB dependency
- SSE streaming: OpenAI-compatible `data:{"choices":[{"delta":{"content":"..."}}]}` format with JSON escaping
- Vite config: dev server proxy `/api` → `http://localhost:8081` (enables frontend-only dev with live backend)

### Documentation
- `docs/pgvector-index-comparison.md`: HNSW vs IVFFlat algorithm comparison, decision matrix, parameter tuning, migration SQL
- `docs/grafana/rag-service-dashboard.json`: 44-panel Grafana dashboard (Advisor/Model/Cache/SlowQuery panels)
- `docs/SSE-PROTOCOL.md`: SSE streaming protocol documentation (OpenAI-compatible format)
- `docs/hybrid-search-enhancement-plan.md`: Phase 1-4 hybrid search enhancement roadmap
- `IMPLEMENTATION_COMPARISON.md` stats updated: 1513+ tests, zero TODO/FIXME

### Changed (Technical)
- Java 17 → **Java 21** (LTS, virtual threads)
- Spring Boot 3.4.x → **3.5.3**
- Spring AI 1.1.2 → **1.1.4**
- Maven 3.9.x → **3.9.14**
- `spring.threads.virtual.enabled=true` (virtual threads for I/O-intensive operations)
- GitHub Actions: Java 21 + `setup-java cache=maven` + Codecov coverage upload
- Dockerfile: multi-stage (Maven builder → jlink JRE → distroless), eclipse-temurin:17-jre, non-root user, <200MB

### Testing
- JaCoCo coverage: Core 93% instruction / 78% branch
- All 13 controllers have dedicated unit test files (100% coverage)
- All service classes have unit test files
- 142 vitest tests (WebUI) + 12/12 Playwright E2E

## [1.0.0-SNAPSHOT] - 2026-04-10

### Added
- `@Version` optimistic locking on 6 mutable entities: RagDocument, RagCollection, RagAlert, RagAbExperiment, RagRetrievalLog, RagUserFeedback
- `V17__add_version_column.sql`: Flyway migration adding version columns for optimistic locking
- `SilenceSchedule` integration: `AlertServiceImpl` checks active silencing windows before firing alerts — `SilenceScheduleRequest`/`SilenceAlertRequest` DTOs
- `ApiSloHandlerInterceptor` 12 unit tests: all HTTP methods, SLO compliance calculation, concurrent latency recording
- `RagCircuitBreakerProperties` 4 unit tests: all property fields validated
- `ModelController` multi-model coverage: `multiModelEnabled=false` test, `listModels/getModel/compareModels` all tested
- `RagAlertTest` (9 tests) + `RagAbExperimentTest` (8 tests) entity unit tests
- `RagRetrievalLogRepositoryTest` 22 tests: all query methods tested
- `RagRetrievalLogEntityTest` 7 tests: version/tostring/defaults
- `DocumentMapper` extraction: `toDocumentResponse/toBatchEmbedResult/toReembedResult` extracted from RagDocumentController
- `CollectionMapper` extraction: `toCollectionSummary/toDocumentSummary/toCollectionResponse` extracted from RagCollectionController

### Fixed
- `CollectionMapper.toCollectionSummary` N+1: batch-fetch all document counts in single query
- `ChatModelRouter` NPE: added null-check on providerId in `resolve()` method
- `SseEmitters.sendHeartbeat()`: uses `.comment()` SSE API for proper heartbeat comment format
- `SseEmitters.sendError()`: completes normally on successful send, falls back to `completeWithError()` only on failure
- WebUI `useSearchHistory` test stability: separate `act()` blocks for sequential operations

## [1.0.0-SNAPSHOT] - 2026-04-09

### Added
- `EmbeddingCircuitBreaker`: wraps `EmbeddingBatchService.embedBatch()` — open on consecutive failures, half-open probe, resume on recovery
- SSE heartbeat: `SseEmitters.sendHeartbeat()` + configurable interval (default 30s) to keep connections alive through proxies
- `SseEmitters` utility class: `sendProgress/sendDone/sendError/sendHeartbeat/completeWithError` — extracted from `RagChatController`
- k6 load tests K6-5/K6-6/K6-7: ramp-to-saturation VU discovery + persistent session stress + vector search stress
- `@Version` optimistic locking on 4 entities: RagDocument, RagCollection, RagAlert, RagAbExperiment
- `listDocuments` N+1 fix: batch-fetch collection names in single DB call — O(N)→O(1) round trips
- `evaluateAnswerQuality` exception paths: `TimeoutException` + `InterruptedException` test coverage
- `ModelRegistry` routing tests: 27 new tests (10→37) covering `getPrimaryChatModel/getModelByProvider/getPrimaryEmbeddingModel`
- R6 main source i18n: 26 files Javadoc/comments translated to English
- R6 test DisplayName i18n: HybridSearchAdvisorTest, QueryRewriteAdvisorTest, RagChatServiceTest, AlertServiceImplTest, ChatMemoryMultiTurnTest, metrics package
- `DigestUtils` extraction: SHA-256 utility extracted from `BatchDocumentService`
- `OpenApiConfig` refactoring: 9 `if` blocks → table-driven switch expression, `ExampleDef` record

### Fixed
- `evaluateAnswerQuality` timeout: `CompletableFuture.orTimeout()` 10s with graceful degradation
- `batchEmbedDocumentsWithProgress` ClassCastException: `embeddingsStored` cast from Long (countByDocumentId returns Long)
- Search score NaN: `ddText` score null-safe `?? 0.0`
- SSE `sendHeartbeat()` format: `.comment()` for proper SSE comment format
- SSE `sendError()` fallback: complete normally on success, `completeWithError()` only on failure
- `ChatModelRouter` NPE: null-check on `providerId` in `resolve()`

## [1.0.0-SNAPSHOT] - 2026-04-08

### Added
- `euclideanDistance` + `dotProduct` to `RetrievalUtils`: L2 distance and inner product for pgvector `<#>` operator
- `LLM-as-judge` answer quality evaluation: `RetrievalEvaluationService.evaluateAnswerQuality()` uses LLM to score RAG answers (3-dimension: relevance/coverage/conciseness)
- `RagRetrievalLogRepositoryTest`: 22 unit tests for all query methods
- `SearchCapabilitiesTest`: 15 unit tests covering no-arg/init=false/init=true, Chinese FTS/English FTS/trigram detection
- `PgEnglishFtsProviderTest`: 10 unit tests for English fulltext search provider
- `SilenceSchedule` CRUD REST API: `SilenceScheduleRequest/Response` DTOs, `SilenceAlertRequest`, alert suppression during silence windows
- `NotificationConfig` 11 unit tests: DingTalk HMAC-SHA256 + Email SMTP, alert type filtering
- `Collection clone endpoint`: `POST /{id}/clone` deep-copies collection (new name + "(Copy)") with all documents
- `Multi-collection search`: `POST /search` `collectionIds` parameter searches across multiple collections
- WebUI W12: Document version comparison UI with LCS diff algorithm
- WebUI W13: A/B Testing real-time dashboard with Recharts bar charts + significance testing
- WebUI W14: Dark mode auto-follow-system + manual toggle lock + A to resume auto

### Fixed
- `RetrievalUtils` NaN scores in `fuseResults`: all-zero vector/fulltext scores produce 0.0 instead of NaN
- V17 Flyway: table name fixes (`rag_document`→`rag_documents`, `rag_alert`→`rag_alerts`)
- SSE streaming: proper event separation with `split('\n\n')`
- `OpenApiContractTest` context loading: 53 test errors resolved — CorsConfig needs RagProperties
- MiniMax base-url `/v1` removal (MiniMax API endpoint already contains `/v1`)
- WebUI `VersionHistoryModal`: null-safety for `getVersion` responses

## [1.0.0-SNAPSHOT] - 2026-04-07

### Added
- `HybridRetrieverService` language-adaptive FTS: `QueryLang` enum (ZH/EN_OR_OTHER) + `LanguageDetector` using CJK Unicode block detection
- `SearchCapabilities` class: `detectLang/getCapabilities/isAvailable` for Chinese FTS/English FTS/trigram probing
- `PgEnglishFtsProvider`: English fulltext search using `search_vector` tsvector + `websearch_to_tsquery`
- `PgJiebaFulltextProvider` enhancement: `websearch_to_tsquery('jiebacfg', ?)` + pre-built `search_vector_zh` GIN index
- V15/V16 Flyway migrations: `search_vector` column + GIN index (English FTS) + conditional trigram index
- `ApiSloTrackerService` 16 unit tests: concurrent latency recording, per-endpoint compliance, SLO breach counting
- `AlertController` +14 CRUD tests: SLO config and silence schedule endpoints fully tested
- SSE streaming embed progress: `POST /documents/{id}/embed/stream` + `BatchEmbedProgressEvent`
- `ChatHistoryCleanupServiceTest`: 6 tests (TTL disabled/exception/normal path/null cutoff)
- `DocumentEmbedService` refactoring: `maybeEmit()` null-safe callback + `emitEmbeddingProgress()` batch progress

### Fixed
- SSE heartbeat: `sendHeartbeat()` uses `.comment()` SSE API for proper comment format
- SSE `sendError()` fallback: complete normally on success, `completeWithError()` only on failure
- `RetrievalUtils` NaN: all-zero input scores produce 0.0 instead of NaN
- V17 migration: table names fixed (`rag_document`→`rag_documents`, `rag_alert`→`rag_alerts`)
- `ComponentHealthService` NPE: `ChatModelRouter` `getModelForRequest()` null check

### Changed
- All 13 controllers: 100% English Swagger/OpenAPI annotations (@Tag/@Operation/@ApiResponse/@Parameter descriptions)
- All API DTOs: @Schema descriptions translated to English (35 files)
- Service interfaces: Javadoc translated to English (RetrievalEvaluationService, UserFeedbackService, DocumentVersionService)

## [1.0.0-SNAPSHOT] - 2026-04-06

### Added
- `EmailNotificationService`: SMTP alert delivery with JavaMailSender (optional dependency), 3 retries
- `DingTalkNotificationService` resilience: escapeJson ordering fix + HTTP retry with exponential backoff
- `C40` `@Indexed` annotation review: V13 adds missing indexes (rag_collection(name), rag_documents(document_type), rag_documents(enabled))
- `C24` HikariCP slow query monitoring: `RagSlowQueryProperties` + `SlowQueryMetricsService` + `GET /api/v1/rag/metrics/slow-queries`
- `C41` Advisor chain Micrometer: 8 meters (`rag.advisor.{step}.duration/count/results/skipped`) for QueryRewrite/HybridSearch/Rerank
- `C26` SpringDoc snippets + example responses: `OpenApiConfig.exampleResponseCustomizer()` for 9 endpoints
- `C16` pgvector HNSW vs IVFFlat comparison guide: `docs/pgvector-index-comparison.md` with algorithm comparison + migration SQL
- `C38` HikariCP production tuning: `validation-timeout=5000ms`, `initialization-fail-timeout=10000ms`, `register-mbeans=true`, prepared statement cache
- `C20` Dockerfile optimization: multi-stage (Maven→jlink→distroless), eclipse-temurin:17-jre, non-root user, <200MB
- `C39` Mock LLM Server: `scripts/mock-llm-server.js` — OpenAI-compatible `/v1/chat/completions` + `/v1/embeddings`, configurable delay/error rate

### Fixed
- `SpringAiConfig` OpenAiApi builder: removed non-existent `.proxy()` method call
- OpenApiContractTest: 53 test context-load failures — fixed with `@MockBean RagClientErrorRepository`
- CorsConfig in `@WebMvcTest`: RagControllerIntegrationTest uses static `@TestConfiguration` providing real `RagProperties`
- MiniMax base-url `/v1`: removed (MiniMax endpoint already contains `/v1`)
- SiliconFlow embedding base-url `/v1/embeddings`: removed (OpenAiApi appends `/v1/embeddings`)

### Changed
- `SseEmitters` extracted as utility class: `sendProgress/sendDone/sendError/sendHeartbeat/completeWithError`
- SSE streaming: OpenAI-compatible `data:{"choices":[{"delta":{"content":"..."}}]}` format with JSON escaping
- Vite dev proxy: `/api` → `http://localhost:8081` (frontend-only dev)

## [1.0.0-SNAPSHOT] - 2026-04-05 (Evening)

### Added
- `POST /cache/invalidate`: Admin endpoint to clear Caffeine embedding cache
- `GET /metrics/slow-queries`: HikariCP slow query statistics REST endpoint
- `GET /metrics/slo`: API SLO compliance rates (p50/p95/p99 per endpoint)
- `POST /client-errors`: WebUI error boundary reports client-side errors
- `GET /client-errors/count`: Client error count endpoint
- `ChatExportService` + `GET /chat/export/{sessionId}`: Export conversation history as JSON/Markdown
- `BatchCreateResponse` DTO: unified batch create response type
- `Demo E2E scripts`: `demo-basic-rag-e2e.sh` (8082) / `demo-multi-model-e2e.sh` (8083) / `demo-component-level-e2e.sh` (8084) / `demo-domain-extension-e2e.sh` (8085)

### Fixed
- `AlertServiceImpl` bean ambiguity: `@Autowired List<NotificationService>` resolved via `@Qualifier`
- `ChatRequest.model` field: nullable (model is optional in multi-model routing)
- `.env` variables: added `export` prefix for Maven subprocess environment variable inheritance
- Spring Boot 3.5: `springdoc 2.6.0` → `2.8.4` for Spring Boot 3.5.3 compatibility

### Changed
- All API DTOs `@Schema` descriptions: English only (35 files)
- 8 DTO validation messages: English only (30+ constraint messages)
- Controllers @Operation/@ApiResponse: English only (13 controllers, 100%)
- Service Javadoc: English only (7 service interfaces/impls)

## [1.0.0-SNAPSHOT] - 2026-04-05

### Added
- `ChatHistoryCleanupService`: `messageTtlDays` config (default 30 days) + daily 3 AM auto-cleanup of expired chat history (cron-configurable)
- `ChatHistoryCleanupServiceTest`: 6 tests covering TTL disabled / exception / normal path / null cutoff
- `rag.memory.message-ttl-days` / `rag.memory.cleanup-cron` config items (docs/configuration.md synced)

## [1.0.0-SNAPSHOT] - 2026-04-04 (Afternoon)

### Added
- `CircuitBreakerHealthIndicator`: `/actuator/health/llmCircuitBreaker` endpoint — CLOSED/HALF_OPEN=UP, OPEN=DOWN, NOT_CONFIGURED=UNKNOWN
- `POST /models/compare` model comparison endpoint + `ModelMetricsService` + rest-api.md supplement

#### Multi-Model
- demo-multi-model: `MultiModelController` + `MiniMaxAdapter` + `OpenAiCompatibleAdapter` (system message support configurable)
- `ApiCompatibilityAdapter` interface: `supportsSystemMessage()` + `normalizeMessages()` default methods
- `ChatModelRouter.getModelForRequest()`: request-level dynamic model selection + FallbackChain

### Fixed
- `RetrievalConfig`: `vectorWeight`/`fulltextWeight` added `@DecimalMin(0.0)` / `@DecimalMax(1.0)` validation
- `RagSearchController` GET `/search`: out-of-range weights [0.0, 1.0] return 400 + error+received
- Removed leftover `TestController.java` + reverted hardcoded API keys in application.yml (security fix)

## [1.0.0-SNAPSHOT] - 2026-04-04

### Added

#### Testing
- `DomainExtensionPipelineIntegrationTest`: 22 tests covering domain extension pipeline full flow (Registry lookup/skip/default, medical domain symptom recognition/high-recall config, legal domain multi-extension coexistence)
- `RagSearchControllerBenchmarkTest`: 100 concurrent search requests + 50 concurrent <1s throughput benchmark

#### E2E & Demo
- E2E script expanded: Collection CRUD tests (create/list/detail/update/delete/document association) + Cache stats + Metrics overview, 14 end-to-end items total
- demo-domain-extension `MedicalRagControllerTest` fixed Java 24 strict type inference causing Mockito overload resolution failure

#### Resilience
- LLM circuit breaker infrastructure (`LlmCircuitBreaker` three-state automaton: CLOSED→OPEN→HALF_OPEN) + `LlmCircuitOpenException` (503)
- `RagChatService` integrates circuit breaker: failure rate triggers open, cool-down probes half-open, success rate recovers

### Fixed
- `DocumentEmbedService.embedDocumentWithProgress` NPE: `maybeEmit()` null-safe callback utility + chunks==null fix on cache hit
- MiniMax API incompatible with `role:system`: `ApiCompatibilityAdapter.supportsSystemMessage()` + `normalizeMessages()` auto-convert system → user messages

### Changed
- Proactive inspection (Round 4 late night): catch comment standardization (Health probe / Resilience / best-effort three categories)
- Domain extension Registry naming consistency and test path fixes

## [1.0.0-SNAPSHOT] - 2026-04-03

### Added

#### Core RAG
- Hybrid search (pgvector vector + PostgreSQL fulltext retrieval fusion)
- Configurable fulltext strategy (auto/pg_jieba/pg_trgm/none, supports Chinese tokenization via pg_jieba)
- Query rewriting (rule mode + LLM-assisted mode)
- Reranking service (Cross-Encoder mode)
- Advisor chain pipeline (QueryRewrite → HybridSearch → Rerank → ChatMemory)
- Content-hash embedding cache (avoids re-embedding unchanged documents, Caffeine L1 cache)
- Document version history (content_hash changes auto-recorded)

#### Model Support
- OpenAI-compatible models (DeepSeek, Zhipu, etc.)
- Anthropic models
- Three-Bean mode auto-switching (`app.llm.provider` config)
- Multi-model parallel comparison service
- SiliconFlow BGE-M3 embedding model (1024 dimensions)

#### Domain Extension
- `DomainRagExtension` interface (domain prompts + retrieval config + answer post-processing)
- `PromptCustomizer` chain customization
- `DomainExtensionRegistry` auto-registration

#### REST API
- RAG Q&A (non-streaming + SSE streaming)
- Document management (CRUD + embed + batch operations)
- Knowledge base collection management (export/import)
- Retrieval evaluation + user feedback
- A/B experiment framework
- Monitoring alerts + SLO
- Health checks
- Cache statistics endpoint (GET /api/v1/rag/cache/stats)
- API Key authentication filter
- API rate limiting (sliding window + per-user tiered limits, 429 + Retry-After)
- API versioning (@ApiVersion annotation, supports /api/v1/ + /api/v2/ coexistence)
- RFC 7807 Problem Detail error response format
- Enhanced request validation (@Valid + ConstraintViolationException unified handling)

#### Observability
- Micrometer metrics (retrieval latency, token usage, hit rate)
- Actuator health checks
- Retrieval logging + performance benchmarks
- Request tracing (RequestTraceFilter + MDC traceId + logback formatting)
- Distributed tracing enhancement (configurable sampling rate + W3C traceparent format + spanId nested tracing)
- Unified exception handling (narrowed to specific exception types)

#### Infrastructure
- Flyway database migrations (V1–V10, includes pg_trgm/pg_jieba indexes)
- HikariCP connection pool optimization
- Async processing configuration
- Response caching (Caffeine L1, configurable TTL/LRU)
- CORS security configuration
- i18n framework (MessageSource + Chinese/English bilingual error messages)
- Docker support (multi-stage build + docker-compose)
- GitHub Actions CI (PostgreSQL service + JaCoCo coverage reporting)

#### Documentation
- README.md (project front page)
- CONTRIBUTING.md (contribution guide)
- docs/architecture.md (architecture design details)
- docs/configuration.md (complete configuration reference, includes rate-limit/CORS/cache/tracing config)
- docs/testing-guide.md (testing guide)
- docs/getting-started.md (developer getting started)
- docs/rest-api.md (REST API reference, includes RFC 7807 + cache stats endpoint)
- docs/extension-guide.md (domain extension guide)
- docs/troubleshooting.md (troubleshooting)
- docs/postgresql-extensions.md (PostgreSQL extension dependency analysis)

#### Testing
- 890 unit/integration tests
- JaCoCo coverage integration (>90% instruction coverage)
- E2E test script
- Performance benchmark (single retrieval <500ms)
- SSE streaming E2E tests + multi-turn conversation memory verification

### Technical Stack

| Component | Version |
|-----------|---------|
| Java | 17+ |
| Spring Boot | 3.4.x |
| Spring AI | 1.1.2 |
| PostgreSQL + pgvector | 15+ / 0.7.x |
| Maven | 3.9+ |

---

## [1.1.0-SNAPSHOT] - 2026-04-03 Evening

### Added
- SSE streaming embed progress endpoint `POST /documents/{id}/embed/stream` (real-time push: PREPARING→CHUNKING→EMBEDDING→STORING→COMPLETED)
- RAG metrics REST endpoint `GET /api/v1/rag/metrics` (totalRequests/successRate/tokens key metrics)
- Demo E2E shell script `scripts/demo-e2e.sh` (startup + health wait + 10-item verification + color output)
- MiniMax API compatibility fix: role:system auto-converted to user message (prevents dirty data errors)

### Fixed
- SpringAiConfig missing @EnableConfigurationProperties causing RagProperties injection failure
- demo-component-level @ComponentHealthService missing @Service annotation
- pom.xml GraalVM profile comment `--` causing XML parsing error
- .env variables missing export prefix causing Maven subprocess to not inherit environment variables

## [1.1.0-SNAPSHOT] - 2026-04-04 Early Morning

### Added
- `LlmCircuitBreaker` + `LlmCircuitOpenException` (LLM circuit breaker infrastructure)
- `RagSearchControllerBenchmarkTest` (100 concurrent / 50 concurrent throughput verification)
- E2E script expanded to 14 items (Collection CRUD + cache/stats + metrics/overview)
- `DomainExtensionPipelineIntegrationTest` (22 tests, DomainExtensionRegistry + medical/legal domain extension pipeline verification)
- API version coexistence (@ApiVersion annotation supports /api/v1/ + /api/v2/ simultaneous existence)
