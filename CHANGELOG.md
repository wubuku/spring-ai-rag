# Changelog

📖 [English](CHANGELOG.md) · 📖 [中文](CHANGELOG-zh-CN.md)

---

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
