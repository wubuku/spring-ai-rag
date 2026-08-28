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
              -> CompositeChatDocumentRetriever
                   -> ProjectDocumentRetriever
                   -> optional StaticKnowledgeDocumentRetriever
              -> bounded candidate pool -> weighted RRF
              -> ProjectDocumentJoiner
              -> ProjectRerankPostProcessor
              -> CitationQueryAugmenter
       -> AGENT:
            BudgetedToolCallAdvisor
              -> KnowledgeSearchTool
              -> optional searchStaticKnowledge / Runtime Skill / allowlisted HTTP tools
              -> bounded candidate pool -> rerank -> final top N
              -> server-owned ToolContext
       -> PLAIN:
            ChatClient only
  -> ChatSessionCoordinator
       -> atomic history + source snapshot + JDBC-compatible Memory projection
```

`ProjectDocumentRetriever` adapts the project's stronger retrieval stack to
Spring AI's Modular RAG contract. Vector search, Chinese/English full text,
RRF fusion, a bounded rerank candidate pool, reranking, Embedding Profile filtering,
Collection/API-key ACL,
document type, and document ID scope therefore remain shared by direct Search,
KNOWLEDGE, and the AGENT tool.

When static knowledge is enabled, `CompositeChatDocumentRetriever` also
combines the startup-built `StaticKnowledgeDocumentRetriever`. That branch
performs bounded lexical retrieval only, calls no embedding model, writes no
database row, uses `STATIC_KNOWLEDGE` sources, and bypasses the external
reranker. Runtime Skills expose only bounded catalog/load/reference data in
AGENT. Configured HTTP tools additionally require a Skill/capability loaded in
the current request and remain constrained by HTTPS allowlists, SSRF defense,
and the shared tool budget.

For the `spring-ai` `KNOWLEDGE` query strategy, the project-owned
`BoundedMultiQueryExpander` sits between Spring AI query expansion and retrieval.
It bounds the total original-plus-variant fan-out with the server-side
`max-retrieval-queries` setting, removes exact duplicate text, and preserves each
`Query` history/context. The trace records planned query count, whether the
budget limited the configured variants, duplicate count, and degraded fallback
without storing query text. The KNOWLEDGE query budget is separate from the AGENT
tool-retrieval budget, so raising an Agent tool limit cannot silently multiply
fixed RAG database or embedding calls.

After all KNOWLEDGE queries finish retrieval, `ProjectDocumentJoiner` combines
their `Document` lists before reranking. The stable identity is
`documentId:chunkIndex`; repeated identities keep the candidate with the
highest finite score, while missing identities remain independent. Output is
ordered by finite score and stable identity, so Spring AI's internal `HashMap`
iteration does not decide equal-score ordering. The join is bounded local work
and adds no SQL, embedding, rerank-provider, or Chat-model calls.

The Chat response exposes four integer diagnostics under
`metadata.retrieval.documentJoin`: input documents, unique documents, removed
duplicates, and score-driven replacements. The same low-cardinality summary is
stored on the corresponding retrieval-trace attempt. Query text, document IDs,
content, metadata values, and model output are not included in that summary.

For the distinction between message windows, query compression, and durable
summaries, plus tool-loop budgets and non-document tool extension boundaries,
see [Chat Memory, RAG, And Tool Calling](chat-memory-rag-tool-calling.md).

The old `QueryRewriteAdvisor`, `HybridSearchAdvisor`, and `RerankAdvisor` remain
component-level/compatibility APIs, but they are not the production
mode-aware Chat pipeline.

### 3.3 Model-Invocation Usage Attribution

`BudgetedChatModel` is the model-call boundary for durable usage attribution.
It records at most one terminal event for each `ChatModel.call` or stream
subscription that belongs to a Chat execution. The event contains the logical
execution and call ordinal, principal/session/trace, canonical model reference,
Chat mode, purpose, outcome, streaming flag, normalized provider usage,
invocation-start pricing snapshot, and bounded duration. The attribution covers
the final Chat call, query transform/expansion, summary, fallback candidates,
application retries, and AGENT rounds that pass through the mode-aware or
compatibility Chat entry points.

V53 adds the append-only `rag_llm_usage_event` table. The
`JdbcLlmUsageRecorder` uses bounded synchronous confirmation for non-streaming
calls and bounded asynchronous recording for streaming calls. Recorder timeout,
queue rejection, and database failure are fail-open for Chat correctness and
are exposed only as a process-local lost-event counter/metric. A retention job
deletes old rows in bounded batches.

`GET /api/v1/rag/usage` reads aggregate projections only. Normal principals
are restricted to their own owner rows; ADMIN and environment root may select
all or one principal. Token totals and configured costs are explicit aggregate
values; unavailable provider usage or pricing is counted rather than guessed.
The ledger is observability data, not provider billing, settlement, or a
hard-limit enforcement source.

### 3.4 Integration Data-Plane Observability

The external integration data plane uses a separate request-observation path:

```text
IntegrationObservationFilter
  -> fixed method/path classifier
  -> final HTTP status + wall duration
  -> stable principal projection
  -> authorized Collection request context
  -> bounded IntegrationObservationRecorder queue
  -> grouped PostgreSQL upsert
       -> rag_api_operation_hourly
       -> rag_api_collection_operation_hourly
```

V54 stores UTC hourly request totals and authorized Collection contributions.
The request table counts each request once. The Collection table records the
Collections that were successfully resolved within the current authorization
scope; its rows are contributions and must not be added together as a request
total. Unknown or unauthorized Collection keys never enter Collection
rollups.
Both rollup tables constrain the finite operation enum with database `CHECK`
constraints. A later migration that adds an `IntegrationOperation` must extend
both constraints in the same migration and prove global and Collection rollup
writes against real PostgreSQL. Updating only the Java enum lets the business
request succeed while the asynchronous observation batch is rejected.

Recording is asynchronous and fail-open. Queue overflow, repository failure,
and bounded shutdown drain loss do not change the business response or trigger
provider/mutation retries. The query API explicitly reports
`completeness.mode=BEST_EFFORT`, the current process's dropped count, retention,
and the oldest included bucket.

`GET /api/v1/rag/integration-observability` enforces current authorization
before reading historical aggregates. NORMAL principals can query only
themselves and their current Collection scope. Environment root and database
ADMIN can query globally or select a database principal. Queries are bounded
by time range, HOUR/DAY granularity, a finite operation enum, and a maximum
Collection breakdown.

Micrometer exposes request count/duration plus queue, flush, cleanup, and drop
signals using fixed low-cardinality dimensions only. Stable principal IDs and
Collection IDs exist only in PostgreSQL aggregates; credentials, request
bodies, response bodies, queries, external IDs, dynamic URLs, and exception
text are never recorded. These rollups support diagnosis, not billing,
security audit, hard quota, or mutation recovery.

### 3.5 Bounded Staged Credential Rotation

V55 extends versioned API credentials with a bounded two-credential state:

```text
one stable rag_api_principal
  -> one current rag_api_key       (enabled, retire_at is null)
  -> at most one retiring key      (enabled, retire_at is a future deadline)
  -> at most one PENDING rag_api_key_rotation operation
```

Prepare changes the old current row into the retiring row and creates the next
credential version as current in one principal-serialized transaction. The
operation ledger stores only a generated rotation ID, principal/credential
references, idempotency and request-fingerprint hashes, overlap/deadline,
status, and lifecycle timestamps. It never stores the raw secret or original
header value.

Authentication remains authoritative on every request and requires
`retire_at IS NULL OR retire_at > now`. Therefore the overlap closes at the
deadline even if scheduled cleanup is delayed. Complete disables the retiring
credential; cancel disables the replacement and restores the retiring row as
current; expiry disables the retiring credential; principal revocation
disables the whole family. Partial unique indexes enforce at most one current,
one retiring, and one pending operation per principal.

Both credentials project the same stable principal policy, Collection ACL,
operation capabilities, Chat/session owner, usage attribution, and PostgreSQL
quota. Staged rotation changes only authentication material and cannot create
a second authorization identity or quota bucket.

### 3.6 Dual-Table Conversation Memory

| Table | Purpose | Managed by |
|-------|---------|------------|
| `spring_ai_chat_memory` | Recent, recoverable LLM context window | Spring AI JDBC plus project projection |
| `rag_chat_history` | Principal-owned business history, sources, and audit | Application transaction |

```
Committed turn
  -> rag_chat_history (owner, user/assistant, sources, mode/model/status)
  -> spring_ai_chat_memory (recoverable user/plain-assistant messages only)
  -> rag_chat_history.metadata.toolTranscript (bounded, completely paired tool exchanges)
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

### 3.7 Domain Extension Mechanism

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
- V52 adds optional caller-scoped idempotency to Collection create. The
  successful Collection row and operation ledger commit together under a
  server-derived owner plus key hash. Exact replay returns the Collection's
  current state without another create audit; semantic key reuse conflicts,
  and disabled or unavailable ledger state fails closed.
- API-key management exposes `allowedCollectionKeys`; V48 stores the
  authoritative internal-ID ACL on `rag_api_principal.allowed_collection_ids`.
  The active credential carries a compatibility snapshot, while request-time
  authorization uses an immutable principal policy loaded by an indexed join.
  V49 stores `RAG_READ` / `RAG_WRITE` on the same principal policy. A central
  filter after authentication and before shared rate limiting enforces
  operation capabilities for NORMAL principals; unknown mutations require
  write by default. V50 stores successful principal-provisioning operations by
  requester owner plus idempotency-key hash. The request fingerprint is based
  on resolved effective policy, and replay reads the principal's current
  credential state without storing or reconstructing the original secret.
- Deleting a Collection attempts a soft delete. If it contains any externally managed
  document with a nonblank `externalId`, the service returns `409` and does not
  delete the Collection, because clearing `collection_id` would destroy the
  stable `collectionKey + sourceNamespace + externalId` identity. Otherwise it clears
  `collection_id` only from legacy documents and does not delete documents or
  `rag_embeddings`, so those documents may still be found by full-corpus
  retrieval.
- `rag_collection.embedding_model` and `dimensions` do not participate in
  per-Collection model routing. The active global EmbeddingModel is bound to
  one immutable Embedding Profile for each write and query.
- Vector retrieval requires the active Profile, a fresh `COMPLETED` document
  embedding state, a matching current content hash, and an enabled document.
  Full-text retrieval uses the profile-neutral local chunk generation and its
  matching local-index state instead; it can remain available as
  `KEYWORD_ONLY` while vector work is pending or failed.
- Collection constraints are pushed directly into retrieval SQL through
  `d.collection_id`; selected IDs and explicit document IDs use PostgreSQL
  `bigint[]` JDBC array parameters. Scope cost therefore depends on the number
  of selected Collections rather than all documents contained in them.
- Optional `filters.metadataContains` / `filters.payloadContains` use
  PostgreSQL `@>` on the same candidate SQL (V36 GIN). This is not a query
  language; unknown fields or empty objects return `400`.
- Retrieval diagnostics (V35) store outcome / empty-reason / filter summaries
  and omit query text by default. Empty Search/Chat responses include
  `X-RAG-Retrieval-Trace-Id`. Persist failures are fail-open.
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

An optional durable path is enabled with `rag.embedding-jobs.enabled=true`:

```text
POST /api/v1/rag/embedding-jobs
  | documentIds or authorized Collection scope
  v
rag_embedding_jobs (V33/V37)
  | active-job coalescing + bounded retry
  | origin / requested_by_principal_id
  | publish a local Spring event after the transaction commits
  v
EmbeddingJobWorker
  | event-driven wake-up; 30s recovery scan only for missed work/restarts
  | conditional UPDATE ... RETURNING claim + lease + heartbeat
  | worker-concurrency limit
  | recheck cancellation/Profile/version/content hash around provider calls
  v
DocumentEmbedService.embedDocumentForJob
  | atomically replace active-Profile vectors after the commit guard passes
  v
SUCCEEDED | FAILED | CANCELLED | STALE
```

The job table never copies document content. Write APIs can choose
`embeddingPolicy` `SYNC` / `ASYNC` / `SKIP`; omitted values keep
`embed=true→SYNC` and `embed=false→SKIP`. Explicit embed/re-embed rejects
`SKIP`. `ASYNC` requires `rag.embedding-jobs.enabled=true` or returns `503`.
The worker is enabled by default from V41 because content mutations persist a
durable job for both `SYNC` and `ASYNC`. The readiness API returns exclusive
Collection buckets. The after-commit Spring event is only a low-latency local
notification; the database job/state remains responsible for durability,
multi-instance exclusion, and recovery. Jobs created in one transaction
coalesce to one event, and the worker coalesces concurrent wake-ups. A scheduled
scan runs every `30s` by default only to recover lost events, restarted
instances, or worker failures.

### 4.4 JSON Structured-Record Flow

```text
POST /api/v1/rag/json-records/upsert
  |
  v
JsonRecordService
  | persist jsonbPayload + retrievalText
  | external identity: collectionKey + sourceNamespace + externalId
  | resolver -> internal collectionId + namespace + json-record + externalId
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
  +-- optional payloadContains -> jsonb_payload @> ?::jsonb
  |   pushed into vector / pg_trgm / English FTS / pg_jieba candidate SQL
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
key alongside the deprecated internal ID; persistence and retrieval candidates
remain keyed by internal IDs. The database unique constraint, JPA `@Version`,
and bounded transaction retries coordinate one external identity without
advisory locks.
V34 adds a partial GIN `jsonb_path_ops` index for enabled `json-record`
containment queries. The optional Spring AI `searchJsonRecords` tool is disabled
by default. When enabled, the server injects authorized scope, while the model
may provide only a natural-language query, JSON subtree, and result count, not
Collections, SQL, or JSONPath.
This is deliberately a dedicated JSONB path, leaving room for a future
`xmlPayload` path with the same retrieval-text boundary without introducing a
generic `payload` column.

### 4.5 External Document Synchronization Flow

```text
POST /api/v1/rag/documents/upsert
  |
  v
ExternalDocumentService / DocumentMutationService
  | resolve writable collection through API-key ACL
  | triple identity + source/document revision CAS
  | exact-replay decision + complete snapshot
  v
rag_documents (same documentId)
  | content change -> new content hash / request generation
  | commit state + durable job in the same transaction
  | after-commit Spring event
  v
EmbeddingJobWorker / EmbeddingJobExecutor (after commit)
  | chunk -> provider -> generation/hash/chunker/Profile/lease commit fence
  | atomically replace active Profile rows
  v
fresh COMPLETED state, or FAILED state for the current content hash
```

Ordinary external documents and JSON records share the unique triple
`(collection_id, source_namespace, external_id)`. The unique index is the final
arbiter for concurrent first creation. Existing rows use source-revision and
document-revision CAS with only bounded conflict retries.
`sourceRevision` is opaque; the service only checks equality and an optional
`expectedSourceRevision` compare-and-set, and does not compare revision size or
freshness. Production defaults to strict CAS. Source deletion is a tombstone
(`enabled=false` plus
`source_deleted_at`), allowing a distinct subsequent `sourceRevision` to
restore the same internal document. Retrieval requires
an enabled document and a fresh completed state for the active Embedding
Profile, so old vectors remain physically available for diagnosis without
being returned to callers.

Local PATCH, disable, restore, and permanent delete use the public
`documentRevision`, not the JPA `rowVersion` that embedding-only writes may
change. Content mutations commit the document row, complete snapshot,
freshness state, and durable job in one transaction. Metadata, JSONB payload,
and Collection-only changes allocate no embedding generation.
`DocumentLifecycleService` normalizes document state, active Profile state, and
job state into `READY/INDEXING/FAILED/NOT_REQUESTED/DISABLED`.

**Data-access concurrency rule:** production data access must not use explicit
`SELECT ... FOR UPDATE`, `SKIP LOCKED`, JPA `PESSIMISTIC_*`, or PostgreSQL
advisory locks. Workers use state/owner/lease/snapshot predicates with
`UPDATE/DELETE ... RETURNING`; version allocation uses atomic counters;
concurrency slots and external identities use unique constraints plus
`ON CONFLICT DO NOTHING`; document commits use version/content-hash CAS.
PostgreSQL may still take ordinary short-lived row or index locks internally
for writes; those are not application-requested pessimistic coordination.
`scripts/verify-no-pessimistic-locks.sh` enforces this rule.

### 4.6 Guarded Collection Purge And Retirement

```text
POST /collections/by-key/purge/preview
  | root / database ADMIN / explicitly local loopback authorization
  | counts + reference integrity + active run/repair/session lease checks
  v
rag_collection_purge_preview
  | token hash + owner + fingerprint + Collection/Chat fence versions
  | no body or plaintext token persistence
  v
POST /collections/by-key/purge
  | validate preview and acquire APPLYING lease
  | Collection-first conditional version/chat-fence advancement
  | rebuild and compare the frozen plan
  v
one transaction deletes documents/derivations/referencing feedback/chat/audit
  | retain a minimal rag_collection tombstone
  v
RETIRED + bounded successful-result replay
```

V56 normalizes document references for Chat history and feedback and records
`content_reference_index_complete`. Malformed historical JSON does not block
migration, but any incomplete row makes purge fail closed until repaired and
reindexed. If one persisted Chat turn references a target document, the whole
owner/session history, Spring AI memory, summaries, and turn replay are
deleted so excerpts in answers or tool results cannot remain.

Every production write path that creates, moves, restores, or copies
Collection content consumes active-write tokens in sorted Collection-ID order
before touching document/control-plane rows. Purge uses the same
Collection-first ordering. A business write that commits first advances the
version and invalidates the old preview; a purge that commits first makes the
business reservation fail and roll back. Chat commit separately advances
`chat_commit_fence_version`, and its session lease remains live until summary
compaction stops writing. The embedding worker's final
generation/hash/profile/lease guard becomes ineligible after document/job
deletion, so a late provider response cannot write back into a retired
Collection.

The final tombstone permanently reserves `collectionKey`, fixes the name, and
clears description/metadata. Independent `fs_files` rows have no reliable
Collection foreign key and are never deleted by guessing from filenames or
paths. The capability is disabled by default and has bounded synchronous
limits; oversized plans are rejected instead of partially segmented.

### 4.7 OpenAI Chat Completions Compatibility Flow

```text
OpenAI client
  | GET /v1/models or POST /v1/chat/completions
  v
OpenAiCompatibilityController
  | model alias + text-only messages[]
  v
OpenAiChatRequestMapper
  | body rag.scope / repeated X-RAG-Collection-Key
  v
CollectionRetrievalScopeResolver + API-key ACL
  v
transport-neutral ChatCommand
  v
ChatExecutionService
  |
  +-- non-stream -> chat.completion JSON
  +-- stream     -> chat.completion.chunk* -> data: [DONE]
```

The compatibility controller is not registered by default. A model alias binds
mode, memory, and internal model candidates, never a fixed Collection;
Collection scope is resolved per request. It shares the execution core with
native `/api/v1/rag/chat/stream` but uses isolated DTOs, error envelopes, and
standard SSE mapping without native RAG
`tool_start/tool_result/sources/done` events.

### 4.8 Managed Quality Suites And Citations

When enabled, Chat writes protocol-level `citationValidation` into metadata.
It only parses the agreed `[S1]` tokens and is not a coverage score. Managed
suites (V38) are disabled by default: a version is immutable after creation,
and relevant documents must use
`collectionKey + sourceNamespace + externalId`. Compare is
limited to the same version and marks environment drift separately. The suite
worker reloads the owner's current database principal (`db:{principalId}`) and
its current credential/policy before search; a missing, revoked, expired, or
ACL-restricted principal finishes the run as `FAILED` /
`AUTHORIZATION_CHANGED`.
`local:` / `root:` / `legacy:` principals stay unrestricted, matching HTTP
auth-disabled behavior. Optional `POST /evaluation/semantic` adapts Spring AI
1.1.4 evaluators by reflection (`FactCheckingEvaluator.builder`,
`RelevancyEvaluator(ChatClient.Builder)`, `EvaluationRequest` with `Document`
context) and returns `DISABLED` when the class or ChatClient is missing.

---

## 5. Database Design

### 5.1 ER Relationships

```
rag_collection (1) ──→ (N) rag_documents
rag_documents  (1) ──→ (N) rag_document_embedding_state
rag_documents  (1) ──→ (N) rag_embeddings
rag_documents  (1) ──→ (N) rag_embedding_jobs
rag_documents  (1) ──→ (N) rag_document_chunks
rag_documents  (1) ──→ (1) rag_document_local_index_state
rag_embedding_profiles (1) ──→ (N) rag_document_embedding_state
rag_embedding_profiles (1) ──→ (N) rag_embeddings
rag_embedding_profiles (1) ──→ (N) rag_embedding_jobs
rag_chat_history (1) ──→ (N) rag_chat_history_source_document
rag_user_feedback (1) ──→ (N) rag_user_feedback_document
rag_collection (1) ──→ (N) rag_collection_purge_preview

rag_chat_history        # Conversation history plus normalized source references
rag_retrieval_logs      # Retrieval logs
rag_ab_experiments      # A/B experiment definitions
rag_ab_results          # A/B experiment results
rag_user_feedback       # User feedback
rag_alerts              # Alert records
rag_slo_config          # SLO configuration
rag_retrieval_evaluations  # Retrieval quality evaluations
rag_evaluation_suites      # Managed quality suites (V38, disabled by default)
rag_evaluation_suite_versions
rag_evaluation_runs
rag_evaluation_case_results
rag_audit_log           # Audit logs (collection operations)
```

### 5.2 Key Table Structures

| Table | Key Columns | Description |
|-------|-------------|-------------|
| `rag_collection` | id, collection_key, name, description, embedding_model, purged_at, chat_commit_fence_version | Internal numeric identity, stable external key, retired tombstone, and Chat commit fence |
| `rag_documents` | title, content, content_hash, collection_id, source_namespace, external_id, source_revision, document_revision, source_deleted_at, jsonb_payload | Document source of truth, business CAS, external identity, and structured payload |
| `rag_document_versions` | document_id, version_number, complete snapshot fields, snapshot_completeness | Complete audit snapshots for document mutations |
| `rag_embedding_profiles` | profile_key, provider, model_name, dimensions, distance_metric | Immutable vector-space identity |
| `rag_document_embedding_state` | document_id, embedding_profile_id, content_hash, chunker_version, request_generation, active_job_id, status, chunk_count | Profile-scoped freshness, active generation, and completion state |
| `rag_embeddings` | document_id, chunk_index, embedding_profile_id, embedding_1024 VECTOR(1024), content | Profile-scoped text chunks + vectors |
| `rag_embedding_jobs` | document_id, embedding_profile_id, content_hash, request_generation, document_kind, chunker_version, status, lease_expires_at, origin | Generation-aware durable embedding/reindex state machine |
| `rag_document_chunks` | document_id, local_index_generation, content_hash, chunker_version, chunk_text, chunk_index | Profile-neutral local keyword chunks |
| `rag_chat_history_source_document` / `rag_user_feedback_document` | history_id/feedback_id, document_id | V56 normalized content references for safe purge attribution |
| `rag_collection_purge_preview` | owner_key, collection_id, token_hash, fingerprint, status, result_payload | Body-free preview/apply lease and result replay without plaintext tokens |
| `rag_document_local_index_state` | document_id, local_index_status, local_index_generation, content_hash, chunker_version, chunk_count | Current local keyword generation and freshness |
| `rag_api_principal` | principal_id, role, allowed_collection_ids, capabilities, policy_version, requests_per_minute, expiry_alert_checked_at | Stable caller owner, authoritative policy, and V57 fair expiry-alert scan cursor |
| `rag_api_key` | key_id, principal_id, credential_version, key_hash, enabled, retire_at | Versioned credential with at most one current and one bounded retiring version per principal |
| `rag_alerts` | dedupe_key, condition_state, state_version, notified_version, status, version | Ordinary alerts plus V57 managed conditions; PostgreSQL/CAS converges active dedupe, phase transitions, and notification claims |
| `rag_alert_notification_delivery` | id, alert_id, notification_version, provider, status, attempt_count, attempt_budget, lease_token, lease_until | V58 durable at-least-once provider delivery ledger with stable UUIDs, uniqueness, leases/CAS, and low-sensitivity receipts |
| `fs_import_batches` | import_id, source_type, original_filename, display_name, entry_path, original_path, file_count | V59 readable PDF-import metadata anchored to the entry artifact while UUID-backed paths remain stable |
| `rag_api_rate_limit_bucket` | principal_id, window_start, request_count | Shared fixed UTC-minute quota bucket |
| `rag_api_provisioning_operation` | owner_id, idempotency_key_hash, request_fingerprint_sha256, principal_id, completed_at | Successful provisioning replay ledger without raw credentials (V50) |
| `rag_api_key_rotation` | rotation_id, principal_id, source_credential_id, target_credential_id, expires_at, status | Bounded staged-rotation ledger without raw credentials or header values (V55) |
| `rag_collection_provisioning_operation` | owner_id, idempotency_key_hash, request_fingerprint_sha256, collection_id, completed_at | Successful Collection-create replay ledger without raw keys or request bodies (V52) |
| `rag_document_sync_runs` | id, collection_id, source_namespace, status, lease_token_hash, cumulative counters | Authoritative source-snapshot run control plane; only the lease hash is stored |
| `rag_document_sync_run_items` | run_id, external_id, document_kind, source_revision, status, error_message, seen_at | Idempotent item ledger and durable low-sensitivity receipt source without bodies, payloads, or metadata |
| `rag_chat_history` | session_id, user_message, ai_response | Business audit |
| `rag_retrieval_logs` | query, strategy, result_count, latency_ms, outcome_code, empty_reason_code | Retrieval diagnostics (V35) |
| `rag_evaluation_suites` | suite_key, owner_principal_id | Managed quality suites (V38) |

**Index Strategy**:
- `rag_collection.collection_key`: named global unique constraint / B-Tree;
  visible-ASCII CHECK and UPDATE trigger enforce the key contract and
  immutability
- `rag_embeddings.embedding_1024`: Profile-specific partial HNSW indexes (vector nearest-neighbor search)
- `rag_documents.content_hash`: B-Tree (hash-based deduplication)
- `rag_document_chunks.search_vector_en`: GIN index for English FTS
- `rag_document_chunks.chunk_text`: optional pg_trgm GIN index
- `rag_document_chunks`: optional `jiebacfg` expression GIN index when pg_jieba is installed
- `rag_documents.jsonb_payload`: V34 partial GIN `jsonb_path_ops` for enabled JSON-record `@>` containment
- `rag_documents.metadata`: V36 GIN for `metadataContains` `@>` pushdown
- `rag_embedding_jobs`: active-job partial unique, claim, batch, document, and status/created indexes
- `rag_api_provisioning_operation`: unique owner/key-hash identity and a
  completed-at cleanup index
- `rag_api_key`: partial unique indexes for one enabled current
  (`retire_at IS NULL`) and one enabled retiring (`retire_at IS NOT NULL`)
  credential per principal, plus an active-retirement deadline index
- `rag_api_key_rotation`: unique principal/idempotency hash, one-PENDING
  partial unique index, expiry scan index, and terminal-retention index
- `rag_collection_provisioning_operation`: unique owner/key-hash identity,
  restricted Collection foreign key, hash-shape checks, and completed-at
  cleanup index
- `rag_document_sync_run_items`: V51 B-Trees on
  `(run_id, seen_at, external_id)` and
  `(run_id, status, seen_at, external_id)` for bounded unfiltered and
  status-filtered keyset receipt queries
- `rag_alert_notification_delivery`: unique alert/version/provider identity
  plus eligible-retry, expired-lease, and provider/status/operator query indexes

### 5.3 Full-Text Search Configuration

PostgreSQL full-text search reads the current generation from
`rag_document_chunks` and `rag_document_local_index_state`:
- English FTS uses the generated `search_vector_en` column
- pg_trgm uses the optional `chunk_text gin_trgm_ops` index
- pg_jieba uses the optional `to_tsvector('jiebacfg', chunk_text)` expression index
- Search configuration: `jiebacfg` (based on jieba tokenizer)
- Supports Chinese tokenization + ranking (`ts_rank`)
- `rag_embeddings` remains the vector source of truth; HybridRetrieverService
  fuses vector and full-text results via scaled weighted RRF (Reciprocal Rank
  Fusion). With fixed `K=60`, provider scores determine rank within each
  channel, channel contributions are weighted and summed for overlapping
  candidates, and final-score ties use deterministic document identity order.
  Raw vector and full-text scores remain diagnostic fields and are not compared
  across provider scales.

### 5.4 Bounded Rerank And Document Coverage

Effective reranking uses one shared sequence for Search POST, `KNOWLEDGE`,
the Agent search tool, JSON records, Evaluation, and the legacy advisor:

```text
bounded retrieval candidates
  -> provider ranking over the bounded pool
  -> first-pass document coverage preference
  -> provider-order backfill when coverage is insufficient
  -> final caller-visible top N
```

The provider remains authoritative for score and order. The document selector
only chooses a subsequence of provider-ranked results and reuses the same
`RetrievalResult` objects. For each exact nonblank `documentId`, the first pass
prefers at most `preferred-max-chunks-per-document` chunks; null or blank IDs
remain independent. A second pass restores skipped chunks in provider order
when distinct documents cannot fill the final limit, so the preference does
not reduce the number of ranked results available to the caller.

The built-in heuristic provider preserves the existing whitespace-token
semantics for non-CJK text. Contiguous HAN, HIRAGANA, KATAKANA, HANGUL, and
BOPOMOFO runs use adjacent code-point bigrams; a one-character run keeps that
character, and Latin/digit runs inside mixed text remain separate features.
Each query or chunk contributes at most 512 features, which are precomputed
once per rerank and reused instead of being tokenized for every candidate
pair. Diversity excludes only the candidate's own list position, so another
nonblank chunk with identical text has similarity `1`; null or blank chunks
receive no diversity reward merely because they lack lexical information.
Successful HTTP rerank responses are unchanged, while HTTP fallback reuses
the same heuristic behavior.

Heuristic relevance reads both chunk content and the authoritative document
title:
`effectiveRelevance = max(contentRelevance, 0.9 * titleRelevance)`.
The title is normalized once with `Locale.ROOT` and never enters the chunk
feature set or diversity calculation. A title match can therefore correct
ordering when the chunk does not repeat a product ID, term, or topic name,
without adding duplicate relevance or changing inter-document similarity.
Null or blank titles preserve the previous scoring path exactly. Successful
HTTP-provider requests still send chunk content only; title relevance applies
only when HTTP falls back to heuristic. This adds no SQL, embedding, HTTP, or
Chat-model call.

Before content and title relevance are calculated, the query's ordered lexical
features are prepared once and reused for every candidate. Ordinary terms that
contain no CJK code point and start and end with a Unicode letter or digit use
complete alphanumeric boundaries: adjacent non-CJK letters or digits block an
occurrence, while punctuation, separators, text edges, and CJK/non-CJK script
transitions are boundaries. Explicit outer sentence/wrapper punctuation is
removed from query terms, but `+`, `#`, `-`, `_`, `/`, and `\` remain part of
technical identifiers. CJK features and symbol-ending terms such as `C++` and
`C#` keep substring matching. This prevents terms such as `rag`, `ai`, and
`9042` from matching inside `storage`, `OpenAI`, or `19042`, while preserving
`RAG?` -> `RAG-based`, `SpringAI` -> `中文SpringAI检索`, and
`9042` -> `型号9042说明`.

The selector operates on at most `candidate-limit` results and adds no SQL,
embedding, rerank-provider, or Chat-model calls. A value of `0` disables this
selection and restores provider top-N behavior.

---

## 6. Configuration System

### 6.1 RagProperties Unified Configuration

`@ConfigurationProperties(prefix = "rag")` manages all business configurations in a type-safe manner:

```yaml
rag:
  retrieval:
    default-limit: 10            # Number of results to return
    min-score: 0.3               # Minimum similarity score
    vector-weight: 0.5           # Vector/RRF channel weight
    fulltext-weight: 0.5         # Full-text/RRF channel weight
  rerank:
    enabled: true
    provider: heuristic
    top-n: 5                      # Final fallback when callers omit maxResults
    candidate-limit: 20           # Pre-rerank candidate-pool limit, bounded to 1..100
    preferred-max-chunks-per-document: 2  # First-pass document coverage preference
  chunk:
    default-chunk-size: 1000
    default-chunk-overlap: 100
  memory:
    max-messages: 20             # Conversation memory window
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

V57 also provides managed API-principal expiry conditions:

- Principal creation, expiry-policy updates, and family revocation publish a
  Spring Event after commit; a bounded asynchronous worker immediately reloads
  and reconciles the authoritative principal.
- An hourly Scheduled fallback handles only missed events and time-threshold
  crossings, scans least-recently checked candidates first, and bounds each
  pass to avoid frequent database polling.
- A PostgreSQL partial unique index, JPA version, and conditional DML/CAS keep
  at most one active alert per principal and upgrade the same row through
  `WARNING → CRITICAL → EXPIRED`.
- Extension beyond the window or revocation resolves the alert. Independent
  state/notified-version claims prevent duplicate notification attempts for
  repeated events in the same phase.
- The Alerts control plane is limited to environment root, database ADMIN,
  legacy static, and auth-disabled direct loopback. Ordinary business
  principals are rejected before repository access.

V58 upgrades external Email/DingTalk delivery from in-process best effort to a
durable outbox:

- The alert fact and matching provider deliveries commit in one transaction.
  A Spring Event only wakes the bounded worker after commit; the default
  one-minute Scheduled scan recovers lost events, restarts, and expired leases.
- Replicas coordinate with uniqueness, lease tokens, conditional DML/CAS, and
  bounded backoff, without a broker or pessimistic locks. Provider network I/O
  always runs outside database transactions.
- Delivery states are `PENDING`, `IN_PROGRESS`, `RETRY_WAIT`, `DELIVERED`,
  `FAILED`, and `SUPERSEDED`. Semantics are at-least-once; a stable delivery
  UUID makes rare duplicates traceable.
- Managed-phase escalation or resolution supersedes older work that has not
  started. Manual retry preserves cumulative attempt auditing and rechecks the
  source alert before delivery.
- Operator APIs and WebUI expose only provider, status, attempts,
  low-sensitivity error/HTTP codes, and timestamps. The ledger excludes
  webhook/SMTP secrets, recipients, business payloads, provider response
  bodies, and stack traces.

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
- JDBC Memory keeps only the last N recoverable user/plain-assistant messages
  for LLM context
- The business audit table retains complete history, sources, and a bounded
  tool-exchange projection for querying, summarization, and analytics
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
