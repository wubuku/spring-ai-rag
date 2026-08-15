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

### 3.2 Advisor Chain RAG Pipeline

Spring AI's `BaseAdvisor` mechanism chains the retrieval flow; each Advisor is independent and pluggable:

```
User Query
  │
  ▼
┌─────────────────────────┐  order=+10
│  QueryRewriteAdvisor    │  Query rewrite: synonym expansion + domain qualifiers + LLM-assisted
│  Input: raw query       │
│  Output: rewritten query│
└───────────┬─────────────┘
            ▼
┌─────────────────────────┐  order=+20
│  HybridSearchAdvisor    │  Hybrid retrieval: vector similarity + full-text search + RRF fusion
│  Input: rewritten query │
│  Output: context attributes│  (hybrid.search.results)
└───────────┬─────────────┘
            ▼
┌─────────────────────────┐  order=+30
│  RerankAdvisor          │  Rerank + context injection: Top-K results injected into Prompt
│  Input: retrieval results│
│  Output: enhanced Prompt │  (via augmentUserMessage)
└───────────┬─────────────┘
            ▼
┌─────────────────────────┐
│  MessageChatMemoryAdvisor│  Conversation memory: multi-turn context
└───────────┬─────────────┘
            ▼
        ChatModel → Response
```

**Data passing**: Advisors share data via `ChatClientRequest.context().getAttributes()`, avoiding method signature coupling.

### 3.3 Dual-Table Conversation Memory

| Table | Purpose | Managed by |
|-------|---------|------------|
| `spring_ai_chat_memory` | LLM context window | Spring AI auto-management |
| `rag_chat_history` | Business audit + history | Application layer writes |

```
Request → ChatClient → spring_ai_chat_memory (for LLM)
                  ↘ rag_chat_history (business audit, retains user_message + ai_response)
```

### 3.4 Domain Extension Mechanism

Domain customization via `DomainRagExtension` interface — core framework requires no changes:

```java
public interface DomainRagExtension {
    String getDomainId();                           // Domain identifier
    String customizeSystemPrompt(String base);      // Customize system prompt
    Map<String, Double> getRetrievalWeights();      // Custom retrieval weights
    List<RetrievalResult> postProcess(...);         // Post-process retrieval results
}
```

**Registration flow**:
1. Implement `DomainRagExtension` and annotate with `@Component`
2. `DomainExtensionRegistry` auto-discovers all implementations at construction
3. When a request carries a `domainId` parameter, the corresponding extension is auto-activated
4. If no match, use `DefaultDomainRagExtension` (pass-through with no modifications)

---

## 4. Data Flow

### 4.1 RAG Q&A Request Flow

```
POST /api/v1/rag/chat/ask
  │
  ▼
RagChatController
  │ validate request
  ▼
ChatClient.prompt(query)
  │
  ├──→ QueryRewriteAdvisor    (rewrite query)
  ├──→ HybridSearchAdvisor    (vector + full-text retrieval)
  ├──→ RerankAdvisor          (rerank + inject context)
  ├──→ MessageChatMemoryAdvisor (multi-turn memory)
  │
  ▼
ChatModel (DeepSeek / Anthropic / ...)
  │
  ▼
Response + rag_chat_history persistence
```

### 4.2 Collection-Scoped Retrieval

Collection is an active retrieval boundary, not only a document category in
the administration UI.

```text
ChatRequest / SearchRequest
  collectionIds? + documentIds?
        |
        v
ApiKeyCollectionAccess.resolveCollectionIds
  - unrestricted: preserve the requested scope
  - restricted: constrain to allowedCollectionIds; reject outside IDs with 403
        |
        v
CollectionDocumentResolver
  - collectionIds -> RagDocument IDs
  - intersect with explicit documentIds
  - preserve an empty scope when a non-empty request resolves to zero documents
        |
        +-- Chat -> RagChatService.RetrievalScope
        |           -> HybridSearchAdvisor
        +-- Search -> RagSearchController
                    -> HybridRetrieverService
        |
        v
Vector and full-text SQL
  WHERE document_id IN (...)
        |
        v
Only chunks inside the effective scope are returned
```

Request semantics:

| Input | Unrestricted caller | Restricted API key |
|-------|---------------------|--------------------|
| Omit `collectionIds` or send `[]` | No Collection filter | Use the key's allow-list |
| Non-empty `collectionIds` | Search one or more Collections | Must be a subset of the allow-list or return `403` |
| Include `documentIds` as well | Intersect with Collection membership | Apply ACL first, then intersect |
| Non-empty scope resolves to zero documents | Return empty; never search the full corpus | Return empty; never search the full corpus |

Data model and current boundaries:

- `rag_documents.collection_id` is nullable, so a document belongs to at most
  one Collection. Unassigned documents cannot be selected through
  `collectionIds`, but may appear in unscoped retrieval or through explicit
  `documentIds`.
- Deleting a Collection soft-deletes it and clears `collection_id` from its
  documents. It does not delete documents or `rag_embeddings`, so those
  documents may still be found by full-corpus retrieval.
- `rag_collection.embedding_model` and `dimensions` do not participate in
  runtime model routing. Retrieval uses the global EmbeddingModel and the
  shared `VECTOR(1024)` schema.
- Collection and Document `enabled` states are not yet fully included in
  retrieval SQL.
- The current MVP expands Collections to a complete document-ID list and then
  generates `IN (...)`. Large Collections should move toward retrieval SQL
  that joins `rag_documents` and filters directly on `collection_id`.
- WebUI Chat currently selects one Collection while the backend accepts
  multiple. The standalone Search page has no Collection selector yet.

Source anchors:

- [ApiKeyCollectionAccess](../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java)
- [CollectionDocumentResolver](../spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionDocumentResolver.java)
- [RagChatService](../spring-ai-rag-core/src/main/java/com/springairag/core/config/RagChatService.java)
- [HybridSearchAdvisor](../spring-ai-rag-core/src/main/java/com/springairag/core/advisor/HybridSearchAdvisor.java)
- [RagSearchController](../spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagSearchController.java)
- [HybridRetrieverService](../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java)

Regression-test anchors:

- [CollectionDocumentResolverTest](../spring-ai-rag-core/src/test/java/com/springairag/core/service/CollectionDocumentResolverTest.java)
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
  │ 5. Write to rag_embeddings (VECTOR(1024))
  ▼
Done
```

**Alternative path**: `POST /embed/vs` uses Spring AI `PgVectorStore.add()` for simplified flow (embedding + storage in one step).

---

## 5. Database Design

### 5.1 ER Relationships

```
rag_collection (1) ──→ (N) rag_documents
rag_documents  (1) ──→ (N) rag_embeddings

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
| `rag_collection` | name, description, embedding_model | Knowledge base / collection |
| `rag_documents` | title, content, content_hash, collection_id | Document metadata |
| `rag_embeddings` | document_id, chunk_index, embedding VECTOR(1024), content | Text chunks + vectors |
| `rag_chat_history` | session_id, user_message, ai_response | Business audit |
| `rag_retrieval_logs` | query, strategy, result_count, latency_ms | Retrieval quality tracking |

**Index Strategy**:
- `rag_embeddings.embedding`: IVFFlat index (vector nearest-neighbor search)
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
