# Architecture Design

> 📖 [English](architecture.md) · 📖 [中文](architecture-zh-CN.md)

> **spring-ai-rag** — A model-agnostic, domain-decoupled, componentized general-purpose RAG service framework.
> This document is intended for core developers and architecture reviewers.
>
> Doc hub: [index.md](index.md) · Completion status: [IMPLEMENTATION_COMPARISON.md](IMPLEMENTATION_COMPARISON.md)

---

## 1. Design Principles

| Principle | Meaning | Implementation |
|-----------|---------|----------------|
| **Model-agnostic** | Switching LLMs requires only config changes, not code | Spring AI ChatClient abstraction + three-Bean pattern |
| **Domain-decoupled** | General RAG core separated from business domains | DomainRagExtension interface + SPI registration |
| **Component independence** | Every Advisor / Service can be used standalone | Interface-first design, Spring Bean auto-wiring |
| **Observable** | Every Pipeline step is traceable and measurable | Micrometer metrics + retrieval logs + A/B experiments |

---

## 2. Module Structure

```
spring-ai-rag (parent pom)
├── spring-ai-rag-api          # Interfaces, DTOs, DomainRagExtension
├── spring-ai-rag-core         # Core implementation (all business logic)
│   ├── advisor/               # RAG Pipeline Advisors
│   ├── config/               # Spring configuration classes
│   ├── controller/           # REST endpoints
│   ├── entity/               # JPA entities
│   ├── exception/            # Business exceptions
│   ├── extension/            # Domain extension mechanism
│   ├── filter/               # Authentication filters
│   ├── metrics/              # Monitoring metrics
│   ├── repository/           # Data access layer
│   ├── retrieval/            # Retrieval services (embedding/rewrite/rerank)
│   └── service/              # Business service layer
├── spring-ai-rag-starter     # Spring Boot Starter auto-configuration
├── spring-ai-rag-documents   # Document processing components (chunking/cleaning)
└── demos/
    ├── demo-basic-rag         # Basic RAG example
    ├── demo-multi-model       # Multi-model example
    ├── demo-component-level   # Component-level integration example
    └── demo-domain-extension  # Domain extension example
```

**Dependency direction**: `api ← core ← starter`, `api ← documents`, `starter + documents ← demos`.

---

## 3. Core Design Patterns

### 3.1 Three-Bean ChatModel Pattern

Switch models via `app.llm.provider` config — no code changes required:

```
                    ┌─────────────────────┐
                    │  app.llm.provider   │
                    │  openai | anthropic │
                    └─────────┬───────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼                               ▼
   ┌──────────────────┐            ┌──────────────────┐
   │ openAiChatModel  │            │ anthropicChatModel│
   │ @Conditional...  │            │ @Conditional...   │
   │ provider=openai  │            │ provider=anthropic│
   └────────┬─────────┘            └────────┬─────────┘
            │                               │
            └───────────────┬───────────────┘
                            ▼
                 ┌─────────────────────┐
     @Primary →  │    chatModel        │
                 │ Auto-selects available Bean │
                 └──────────┬──────────┘
                            ▼
                 ┌─────────────────────┐
                 │ ChatClient.Builder  │
                 │  (Spring AI abstraction) │
                 └─────────────────────┘
```

**Key implementation** (`SpringAiConfig.java`):
- Non-selected providers return `null` (not an error)
- `chatModel` uses `@ConditionalOnMissingBean` to avoid conflicts
- `ApiAdapterFactory` auto-detects API compatibility (e.g., MiniMax doesn't support multiple system messages)

### 3.2 Mode-Aware Chat Pipeline

Production Chat does not infer retrieval intent with language-specific regular
expressions. The public request selects an explicit mode; omitting it preserves
the RAG-compatible `KNOWLEDGE` default:

| Mode | Spring AI composition | Retrieval behavior |
|---|---|---|
| `KNOWLEDGE` | `RetrievalAugmentationAdvisor` | Always runs authorized RAG once |
| `AGENT` | `ToolCallAdvisor` + `searchKnowledge` | The model may retrieve zero or more times |
| `PLAIN` | `ChatClient` + Memory | Does not retrieve |

```text
RagChatController
  -> CollectionRetrievalScopeResolver
  -> ChatCommandMapper
  -> ChatExecutionService
       -> request-local MessageChatMemoryAdvisor
       -> KNOWLEDGE:
            RetrievalAugmentationAdvisor
              -> ProjectDocumentRetriever
              -> ProjectRerankPostProcessor
              -> CitationQueryAugmenter
       -> AGENT:
            BudgetedToolCallAdvisor
              -> KnowledgeSearchTool
              -> server-owned ToolContext
       -> PLAIN:
            ChatClient only
  -> ChatSessionCoordinator
       -> atomic history + source snapshot + JDBC Memory commit
```

`ProjectDocumentRetriever` adapts the project's stronger retrieval stack to
Spring AI's Modular RAG contract. Vector search, Chinese/English full text,
RRF fusion, reranking, Embedding Profile filtering, Collection/API-key ACL,
document type, and document ID scope therefore remain shared by direct Search,
KNOWLEDGE, and the AGENT tool.

The old `QueryRewriteAdvisor`, `HybridSearchAdvisor`, and `RerankAdvisor` remain
component-level/compatibility APIs, but they are not the production
mode-aware Chat pipeline.

### 3.3 Dual-Table Conversation Memory

| Table | Purpose | Managed by |
|-------|---------|------------|
| `spring_ai_chat_memory` | LLM context window | Spring AI auto-management |
| `rag_chat_history` | Principal-owned business history, sources, and audit | Application transaction |

```
Committed turn
  -> rag_chat_history (owner, user/assistant, sources, mode/model/status)
  -> spring_ai_chat_memory (bounded model context)
  -> one transaction guarded by rag_chat_session_lease
```

`ChatSessionCoordinator` provides single-flight execution per
`owner_principal_id + session_id`, renews a token-fenced database lease, and
commits history plus JDBC Memory atomically. History, export, clear, and
conversation baselines are scoped to the authenticated principal. Missing and
foreign sessions both produce `SESSION_NOT_FOUND`, avoiding session
enumeration.

Client cancellation disposes the model stream and does not commit an
incomplete turn. Streaming fallback is allowed only before the first
client-visible event.

### 3.4 Domain Extension Mechanism

Explicit domain customization uses the `DomainRagExtension` interface:

```java
public interface DomainRagExtension {
    String getDomainId();
    String getDomainName();
    String getSystemPromptTemplate();
    default String getSystemPromptTemplate(ChatMode mode);
    default RetrievalConfig getRetrievalConfig();
}
```

**Registration flow**:
1. Implement `DomainRagExtension` and annotate with `@Component`
2. `DomainExtensionRegistry` auto-discovers all implementations at construction
3. A request explicitly carrying `domainId` activates that extension; unknown IDs
   return `UNKNOWN_DOMAIN`
4. Omitting `domainId` uses generic Chat defaults and is independent of Spring bean
   registration order

`CitationQueryAugmenter` or `KnowledgeSearchTool` injects retrieval context, so domain
prompts should not contain `{context}`. Legacy templates remain compatible in
`KNOWLEDGE`; `AGENT/PLAIN` require the mode-aware method or return
`DOMAIN_MODE_UNSUPPORTED`. `postProcessAnswer()` and `isApplicable()` are legacy APIs
and are not invoked by the production Chat path.

---

## 4. Data Flow

### 4.1 RAG Q&A Request Flow

```
POST /api/v1/rag/chat/ask
  │
  ▼
RagChatController
  │ resolve principal + Collection/document scope
  ▼
ChatCommandMapper
  │ merge request overrides, domain retrieval config, and defaults
  ▼
ChatExecutionService
  ├── KNOWLEDGE -> Spring AI Modular RAG + project retrieval
  ├── AGENT     -> Spring AI Tool Calling + authorized search tool
  └── PLAIN     -> model + memory without retrieval
  ▼
ChatSessionCoordinator
  │ lease fencing + atomic history/source/memory commit
  ▼
ChatResponse or structured SSE events
```

### 4.2 Collection-Scoped Retrieval

Collection is an active retrieval boundary, not only a document category in
the administration UI.

```text
ChatRequest / SearchRequest
  collectionScopeMode? + collectionKeys? + deprecated collectionIds?
  + documentIds?
        |
        v
CollectionRetrievalScopeResolver
  - infer the compatibility mode when collectionScopeMode is omitted
  - validate the mode/list combination and 100/1000 item limits
  - use CollectionIdentityResolver for batched key -> Long ID resolution
  - apply ApiKeyCollectionAccess before producing an effective scope
        |
        v
RetrievalScope
  collectionFilter = NONE | ANY_ASSIGNED | SELECTED
  collectionIds + documentIds + server-owned documentType + matchNone
        |
        +-- Chat -> RagChatService -> HybridSearchAdvisor
        +-- Search -> RagSearchController
        +-- JSON records -> JsonRecordService
        |
        v
HybridRetrieverService.searchInScope
  + RetrievalScopeSql
  - NONE: no Collection predicate
  - ANY_ASSIGNED: d.collection_id IS NOT NULL
  - SELECTED: d.collection_id = ANY (?) with a JDBC bigint[] parameter
  - documents: e.document_id = ANY (?) with a JDBC bigint[] parameter
  - JSON records: d.document_type = ?
        |
        v
The same predicates constrain vector, English FTS, pg_jieba, and pg_trgm
```

Request semantics:

| Mode | Unrestricted caller | Restricted API key |
|------|---------------------|--------------------|
| `CALLER_VISIBLE` | All retrievable documents, including unassigned documents | Documents in the key's Collection allow-list |
| `ANY_COLLECTION` | All retrievable documents whose `collection_id` is not null | Documents in the key's Collection allow-list; never expands access |
| `SELECTED_COLLECTIONS` | The union of the selected Collections | The selected set must be a subset of the allow-list |

When `collectionScopeMode` is omitted, a present Collection list implies
`SELECTED_COLLECTIONS`; otherwise the request uses `CALLER_VISIBLE`.
`CALLER_VISIBLE` and `ANY_COLLECTION` reject any present Collection list.
`SELECTED_COLLECTIONS` requires a non-empty key or ID list. Supplying both
lists requires them to identify the same set, independent of ordering.
`documentIds` is always an additional intersection, not an authorization
bypass. Requests accept at most 100 Collection identities and 1000 document
IDs.

Keys are resolved exactly and case-sensitively in one batch. An unknown key
returns `404` to an unrestricted caller and `403` to a restricted caller to
avoid revealing out-of-scope Collections. Deprecated unknown numeric IDs keep
their compatibility behavior: they match no rows for an unrestricted caller,
while restricted retrieval returns `403`.

Data model and current boundaries:

- `rag_collection.id` remains the internal `BIGINT` primary key.
  `rag_collection.collection_key VARCHAR(128) COLLATE "C"` is the stable
  external identity. It is globally unique, immutable, case-sensitive, and
  remains reserved after soft deletion.
- `rag_documents.collection_id` is nullable, so a document belongs to at most
  one Collection. Unassigned documents cannot be selected through
  Collection scope, but may appear in unscoped retrieval or through explicit
  `documentIds`. Database foreign keys and retrieval SQL remain numeric.
- Collection create/import/clone require a caller-supplied key. By-key CRUD,
  restore, document association, and export use query parameters rather than
  path segments because valid keys may contain URL-reserved punctuation.
- API-key management exposes `allowedCollectionKeys`; V24 storage and runtime
  authorization continue to use internal IDs in
  `rag_api_key.allowed_collection_ids`.
- Deleting a Collection attempts a soft delete. If it contains any externally managed
  document with a nonblank `externalId`, the service returns `409` and does not
  delete the Collection, because clearing `collection_id` would destroy the
  stable `collectionKey + externalId` identity. Otherwise it clears
  `collection_id` only from legacy documents and does not delete documents or
  `rag_embeddings`, so those documents may still be found by full-corpus
  retrieval.
- `rag_collection.embedding_model` and `dimensions` do not participate in
  per-Collection model routing. The active global EmbeddingModel is bound to
  one immutable Embedding Profile for each write and query.
- Vector and full-text retrieval require the active Profile, a fresh
  `COMPLETED` document embedding state, a matching current content hash, and an
  enabled document.
- Collection constraints are pushed directly into retrieval SQL through
  `d.collection_id`; selected IDs and explicit document IDs use PostgreSQL
  `bigint[]` JDBC array parameters. Scope cost therefore depends on the number
  of selected Collections rather than all documents contained in them.
- Results use one global top-k across the effective Collection union. The
  separate "each selected Collection must contribute results" behavior
  (`EACH_COLLECTION`) is not implemented.
- WebUI Chat and Search expose all three modes. Selected mode supports
  server-side search, 50-item pages, cross-page multi-selection, and at most
  100 Collection keys.

Source anchors:

- [CollectionIdentityResolver](../spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionIdentityResolver.java)
- [ApiKeyCollectionAccess](../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java)
- [CollectionRetrievalScopeResolver](../spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionRetrievalScopeResolver.java)
- [RetrievalScope](../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalScope.java)
- [RetrievalScopeSql](../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalScopeSql.java)
- [RagChatService](../spring-ai-rag-core/src/main/java/com/springairag/core/config/RagChatService.java)
- [HybridSearchAdvisor](../spring-ai-rag-core/src/main/java/com/springairag/core/advisor/HybridSearchAdvisor.java)
- [RagSearchController](../spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagSearchController.java)
- [HybridRetrieverService](../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java)

Regression-test anchors:

- [CollectionRetrievalScopeResolverTest](../spring-ai-rag-core/src/test/java/com/springairag/core/service/CollectionRetrievalScopeResolverTest.java)
- [RetrievalScopeSqlTest](../spring-ai-rag-core/src/test/java/com/springairag/core/retrieval/RetrievalScopeSqlTest.java)
- [MultiCollectionRetrievalPostgresIntegrationTest](../spring-ai-rag-core/src/test/java/com/springairag/core/integration/MultiCollectionRetrievalPostgresIntegrationTest.java)
- [RagSearchControllerTest](../spring-ai-rag-core/src/test/java/com/springairag/core/controller/RagSearchControllerTest.java)
- [HybridSearchAdvisorTest](../spring-ai-rag-core/src/test/java/com/springairag/core/advisor/HybridSearchAdvisorTest.java)
- [ApiKeyCollectionAccessTest](../spring-ai-rag-core/src/test/java/com/springairag/core/security/ApiKeyCollectionAccessTest.java)

### 4.3 Document Embedding Flow

```
POST /api/v1/rag/documents/{id}/embed
  │
  ▼
DocumentEmbedService
  │ 1. Read RagDocument.content
  ▼
HierarchicalTextChunker
  │ 2. Chunk by Markdown structure
  ▼
TextCleaner
  │ 3. Clean (remove HTML, normalize whitespace)
  ▼
EmbeddingBatchService
  │ 4. Call EmbeddingModel per batchSize
  │ 5. Validate all results, then atomically replace the active Profile rows
  │    in rag_embeddings.embedding_1024 VECTOR(1024)
  ▼
Done
```

The active Profile is registered in `rag_embedding_profiles`. Per-document
cache and completion state is stored in `rag_document_embedding_state`. Model
calls happen outside the write transaction; only a short transaction deletes
and replaces the Profile-scoped rows. A failed or incomplete batch leaves the
previous completed vectors available.

### 4.4 JSON Structured-Record Flow

```text
POST /api/v1/rag/json-records/upsert
  |
  v
JsonRecordService
  | persist jsonbPayload + retrievalText
  | external identity: collectionKey + externalId
  | resolver -> internal collectionId + json-record + externalId
  v
RagDocument.content = retrievalText
RagDocument.jsonbPayload = business JSONB
  |
  +--> DocumentEmbedService (retrievalText only)
  |      +--> one record-level chunk
  |      +--> active Profile embedding
  |
  +--> version snapshot (content + jsonb payload)

POST /api/v1/rag/json-records/search
  |
  v
HybridRetrieverService -> ranked document IDs
  |
  v
batch payload enrichment from rag_documents
  |
  v
ranked JSON record response
```

`jsonbPayload` is never copied into embedding metadata or ordinary chat
context. The caller owns the semantic relationship between the payload and
`retrievalText`; the service stores both without trying to reconcile them.
Payload-only updates create an audit version but preserve a fresh embedding.
Public upsert/search requests prefer Collection keys and responses return the
key alongside the deprecated internal ID; persistence, advisory locks, and
retrieval candidates remain keyed by internal IDs.
This is deliberately a dedicated JSONB path, leaving room for a future
`xmlPayload` path with the same retrieval-text boundary without introducing a
generic `payload` column.

### 4.5 External Document Synchronization Flow

```text
POST /api/v1/rag/documents/upsert
  |
  v
ExternalDocumentService
  | resolve writable collection through API-key ACL
  | pg_advisory_xact_lock(collectionId + externalId)
  | CAS / exact-replay decision + version snapshot
  v
rag_documents (same documentId)
  | content change -> current embedding becomes stale
  v
DocumentEmbedService (after the persistence transaction)
  | chunk -> provider -> validate -> atomically replace active Profile rows
  v
fresh COMPLETED state, or FAILED state for the current content hash
```

Ordinary external documents and JSON records share the Collection-level unique
identity `(collection_id, external_id)` and the advisory-lock namespace.
`sourceRevision` is opaque; the service only checks equality and an optional
`expectedSourceRevision` compare-and-set, and does not compare revision size or
freshness. Source deletion is a tombstone (`enabled=false` plus
`source_deleted_at`), allowing a distinct subsequent `sourceRevision` to
restore the same internal document. Retrieval requires
an enabled document and a fresh completed state for the active Embedding
Profile, so old vectors remain physically available for diagnosis without
being returned to callers.

---

## 5. Database Design

### 5.1 ER Relationships

```
rag_collection (1) ──→ (N) rag_documents
rag_documents  (1) ──→ (N) rag_document_embedding_state
rag_documents  (1) ──→ (N) rag_embeddings
rag_embedding_profiles (1) ──→ (N) rag_document_embedding_state
rag_embedding_profiles (1) ──→ (N) rag_embeddings

rag_chat_history        # Conversation history (standalone table)
rag_retrieval_logs      # Retrieval logs
rag_ab_experiments      # A/B experiment definitions
rag_ab_results          # A/B experiment results
rag_user_feedback       # User feedback
rag_alerts              # Alert records
rag_slo_config          # SLO configuration
rag_retrieval_evaluations  # Retrieval quality evaluations
rag_audit_log           # Audit logs (collection operations)
```

### 5.2 Key Table Structures

| Table | Key Columns | Description |
|-------|-------------|-------------|
| `rag_collection` | id, collection_key, name, description, embedding_model | Internal numeric identity plus stable external Collection key |
| `rag_documents` | title, content, content_hash, collection_id, external_id, source_revision, source_deleted_at, jsonb_payload | Document metadata, source state, and structured-record payload |
| `rag_document_versions` | document_id, version_number, content_snapshot, source_revision_snapshot, jsonb_payload_snapshot | Document, source revision, and JSONB version audit |
| `rag_embedding_profiles` | profile_key, provider, model_name, dimensions, distance_metric | Immutable vector-space identity |
| `rag_document_embedding_state` | document_id, embedding_profile_id, content_hash, status, chunk_count | Profile-scoped cache and completion state |
| `rag_embeddings` | document_id, chunk_index, embedding_profile_id, embedding_1024 VECTOR(1024), content | Profile-scoped text chunks + vectors |
| `rag_chat_history` | session_id, user_message, ai_response | Business audit |
| `rag_retrieval_logs` | query, strategy, result_count, latency_ms | Retrieval quality tracking |

**Index Strategy**:
- `rag_collection.collection_key`: named global unique constraint / B-Tree;
  visible-ASCII CHECK and UPDATE trigger enforce the key contract and
  immutability
- `rag_embeddings.embedding_1024`: Profile-specific partial HNSW indexes (vector nearest-neighbor search)
- `rag_documents.content_hash`: B-Tree (hash-based deduplication)
- `rag_documents`: GIN index (full-text search with jiebacfg Chinese tokenizer)

### 5.3 Full-Text Search Configuration

PostgreSQL full-text search uses `pg_jieba` Chinese tokenizer extension:
- Search configuration: `jiebacfg` (based on jieba tokenizer)
- Supports Chinese tokenization + ranking (`ts_rank`)
- In HybridRetrieverService, vector retrieval and full-text search results are fused via RRF (Reciprocal Rank Fusion)

---

## 6. Configuration System

### 6.1 RagProperties Unified Configuration

`@ConfigurationProperties(prefix = "rag")` manages all business configurations in a type-safe manner:

```yaml
rag:
  retrieval:
    top-k: 10                    # Number of results to return
    min-score: 0.5               # Minimum similarity score
    hybrid-alpha: 0.7            # Vector/full-text weight
    rerank-top-k: 5              # Results to keep after reranking
  chunk:
    max-size: 500                # Maximum chunk size (characters)
    overlap: 50                  # Overlap size
  memory:
    max-messages: 20             # Conversation memory window
    window-size: 10              # Number of historical messages
```

### 6.2 Multi-Environment Configuration

| Config Source | Description |
|---------------|-------------|
| `application.yml` | Default configuration |
| `.env` | Environment variables (API keys, database passwords) |
| `RagProperties` | Type-safe business configuration |
| `spring.ai.openai.*` / `spring.ai.anthropic.*` | LLM configuration |
| `siliconflow.*` | Embedding model configuration |

---

## 7. Monitoring & Operations

### 7.1 Metrics Collection (Micrometer)

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `rag.requests` | Counter | success/failure | Request count |
| `rag.latency` | Timer | endpoint | End-to-end latency |
| `rag.llm.tokens` | Counter | direction=in/out | Token consumption |
| `rag.retrieval.results` | Gauge | strategy | Retrieval result count |

### 7.2 Alerting Mechanism

`AlertService` based on `rag_slo_config` threshold configuration:
- Latency alert: P95 > threshold triggers WARNING
- Error rate alert: error rate > threshold triggers CRITICAL
- Silence period: same alert won't repeat within 60 minutes

### 7.3 A/B Experiments

`AbTestService` supports retrieval strategy comparison:
1. Define experiment (Strategy A vs B + traffic split ratio)
2. Requests are routed per ratio to execute different strategies
3. Collect results (latency, accuracy, user ratings)
4. Statistically analyze effect differences

---

## 8. Key Design Decisions

### Why Advisor Chain instead of Pipeline Pattern?

**Chosen**: Spring AI `BaseAdvisor` chain
**Alternative**: MaxKB4j's `PipelineManage + AbsStep` pattern
**Rationale**:
- Advisor integrates natively with Spring AI, no extra abstraction needed
- Execution order controlled via `Ordered` interface, declarative configuration
- Context attributes mechanism is sufficient for passing intermediate results
- Each Advisor can be tested independently and used standalone

### Why Dual-Table Conversation Memory?

**Chosen**: `spring_ai_chat_memory` + `rag_chat_history` coexistence
**Rationale**:
- Spring AI auto-managed table keeps only the last N entries for LLM context
- Business audit table retains complete history, supports queries and analysis
- Two tables with separated responsibilities, no interference

### Why Separate Embedding Model from Chat Model Configuration?

**Chosen**: `siliconflow.*` independent embedding model configuration
**Rationale**:
- Embedding model and chat model may come from different providers
- Embedding model switching is infrequent (requires rebuilding all vectors), chat model switching is frequent
- Separate configuration reduces risk of accidental operations

---

## Appendix: Tech Stack

| Component | Technology | Version |
|-----------|------------|---------|
| Runtime | Java | 21+ (LTS, Virtual Threads) |
| Framework | Spring Boot | 3.5.x |
| AI Framework | Spring AI | 1.1.x |
| Primary DB | PostgreSQL + pgvector | 42.7.x / 0.7.x |
| ORM | Spring Data JPA | 3.3.x |
| Migration | Flyway | 10.x |
| Build | Maven | 3.9.x |
| Embedding Model | BGE-M3 (via SiliconFlow) | 1024 dimensions |
| Tokenizer | pg_jieba | — |
| Cache | Caffeine | 3.x |
| Monitoring | Micrometer + Actuator | — |
| API Docs | SpringDoc OpenAPI | 2.x |
