# Next High-Value Features for the RAG Service

> [English](2026-08-17_NEXT_MOST_WORTHWHILE_FEATURES_PLAN.md) | [中文](2026-08-17_NEXT_MOST_WORTHWHILE_FEATURES_PLAN-zh-CN.md)

> **Status**: Planning draft; production-code implementation is not authorized by this document.  
> **Date**: 2026-08-17.  
> **Code baseline**: `b8a478f` (`fix(chat): preserve exact terms with Spring AI query expansion`).  
> **Goal**: Select the next features that close real product loops, reuse the existing stack, and remain implementable with bounded risk.  
> **Planning gate**: The document must pass three consecutive systematic reviews with no document modification before implementation starts.

This document describes target design, not current capability. Current facts come from code and live `docs/` references; implementation must re-check code, migrations, and dependency versions.

## 1. Executive Decision

The project is no longer missing a basic RAG demo. It already has:

- PostgreSQL + pgvector;
- vector/full-text hybrid retrieval, optional reranking, and Embedding Profile freshness;
- Collection scope resolution, API-key Collection allow-lists, and document intersections;
- Spring AI `RetrievalAugmentationAdvisor`, `ToolCallAdvisor`, `MultiQueryExpander`,
  `CompressionQueryTransformer`, and the default document joiner;
- `KNOWLEDGE`, `AGENT`, and `PLAIN` Chat modes;
- isolated Chat memory, session leases, source tracing, SSE, and fallback;
- idempotent external-document synchronization;
- JSONB structured records;
- evaluation, goldensets, feedback, A/B tests, metrics, and release gates.

The next batch should close these four loops instead of adding another custom regex, Agent framework, or file-format adapter:

| Priority | Feature | Value | Batch | Effort |
|---|---|---|---|---|
| P0 | OpenAI Chat Completions adapter with request-scoped Collection selection | Makes RAG consumable by OpenAI SDKs, Agents, IDEs, and gateways without binding a Collection to a model | A | M–L |
| P0 | Durable embedding/reindex jobs | Makes bulk ingestion, re-embedding, retry, recovery, and restart behavior operationally reliable | B | M |
| P1 | JSON structured-record retrieval loop | Adds bounded structural filtering and an Agent-specific JSON retrieval tool | C | M |
| P1 | Real retrieval regression gates | Turns MRR/nDCG/goldensets into repeatable, comparable evidence that can block regressions | C | S–M |

Recommended order:

1. Implement request-scoped Collection handling and the OpenAI adapter;
2. Implement durable ingestion/reindex jobs;
3. Make regression gates a quality prerequisite, then implement JSON retrieval in parallel;
4. Use real traffic and quality evidence before investing in connectors, coverage retrieval, or GraphRAG.

## 2. Explicit Tradeoffs

### 2.1 Collection must not be fixed in `model` or `deployment`

This is the most important correction in this plan.

`model` selects a model instance, Chat mode, and retrieval policy. It must not imply a fixed Collection. In real integrations, the same model serves multiple tenants, projects, and request scopes; the caller needs to switch knowledge scope per request.

The recommended semantics are:

```text
model
  -> backend model / fallback / Chat pipeline / generation and retrieval defaults
request
  -> dynamic Collection scope
server
  -> request scope + current API-key ACL + document intersection
  -> existing CollectionRetrievalScopeResolver
```

Without a request scope, retain the existing `CALLER_VISIBLE` behavior. This is dynamically derived from the caller, not a fixed Collection. A caller that needs a hard boundary can enable `requireExplicitScope=true`, but the service still must not configure one fixed default Collection.

### 2.2 Reuse Spring AI instead of reimplementing it

The implementation must prefer:

- `ChatClient`;
- `RetrievalAugmentationAdvisor`;
- `ToolCallAdvisor`;
- `MultiQueryExpander`;
- `CompressionQueryTransformer`;
- the Spring AI default document joiner;
- `ProjectDocumentRetriever`;
- `KnowledgeSearchTool` and the server-owned `AuthorizedRetrievalContext`.

Project code should own only the differentiated parts: Collection/ACL pushdown, hybrid retrieval, Embedding Profile freshness, JSONB filtering, source tracing, durable jobs, and quality gates.

Do not:

- use Java regexes to infer retrieval intent instead of Tool Calling;
- duplicate retrieval SQL in an OpenAI Controller;
- use prompts, `user`, arbitrary metadata, or model-visible tool arguments as authorization;
- generate natural-language descriptions from JSON or validate caller-owned JSON/text correspondence;
- introduce Kafka, Redis, or a workflow engine before the database-backed worker is shown to be insufficient.

### 2.3 Explicitly deferred

Not in this batch:

- API-key secret-schema hardening, key families, rotation governance, shared quotas, and usage billing;
- XML, DOCX, PPTX, XLSX, and external connectors;
- `EACH_COLLECTION` coverage retrieval;
- per-Collection embedding models;
- GraphRAG, entity linking, and multimodal retrieval;
- MCP productization, `/v1/responses`, and generic Agent orchestration;
- using a fixed default Collection to solve the OpenAI scope problem.

Deferring key hardening does not remove authorization. The OpenAI adapter reuses existing authentication and Collection ACL and is initially for internal or controlled-network use; it does not claim public commercial production readiness. Public exposure can be a separate hardening project.

Relationship to the existing [OpenAI compatibility readiness](../openai-compatibility-readiness.md) document: that live document remains the source of current status and keeps the requirement that API-key hardening precede public or multi-instance production. This plan only defines a disabled-by-default controlled-network MVP. If implementation is approved, the readiness document must distinguish “controlled preview available” from “public production ready”; the existence of a `/v1` endpoint must not be treated as the latter.

## 3. Current Facts and Gaps

### 3.1 Modules and stable capabilities

| Area | Current fact | Constraint |
|---|---|---|
| `spring-ai-rag-api` | DTOs, enums, and SPIs | Put new protocol, job, and JSON-filter DTOs in separate packages; do not change the old `ChatRequest` contract |
| `spring-ai-rag-core` | Controllers, services, retrieval, Chat, migrations, and runnable application | Hosts the adapter, worker, JSON filter, and evaluation runner |
| `spring-ai-rag-starter` | Auto-configuration, filters, and starter-consumer topology | New features must be tested in standalone core and starter-consumer topologies |
| `spring-ai-rag-documents` | Cleaning, chunking, and PDF-related processing | Must not depend on the OpenAI protocol or database job implementation |
| `spring-ai-rag-webui` | React console with Search, Chat, Documents, and Evaluation | Add UI only where operators genuinely need task or evaluation state |
| Database | PostgreSQL + pgvector, Flyway V1–V32 | New schema uses V33+ forward-only migrations |

Stable references:

- [Project context](../project-context.md)
- [Architecture](../architecture.md)
- [REST API](../rest-api.md)
- [Developer reference](../developer-reference.md)

### 3.2 Collection is already a real retrieval boundary

The current path is:

```text
collectionKeys / collectionIds
  -> CollectionRetrievalScopeResolver
  -> ApiKeyCollectionAccess
  -> RetrievalScope
  -> RetrievalScopeSql
  -> vector / full-text / rerank / Chat / JSON record
```

Current scopes:

- `CALLER_VISIBLE`: all retrievable documents visible to the caller;
- `ANY_COLLECTION`: all retrievable documents assigned to a Collection;
- `SELECTED_COLLECTIONS`: the union of explicitly selected Collections;
- `documentIds`: intersected with Collection scope;
- restricted API keys: no scope mode can expand the allow-list.

`SELECTED_COLLECTIONS` currently performs one global top-k over the selected union; it does not guarantee a contribution from every Collection. That is intentional and is not required for this batch. See [Collection retrieval scope semantics](../rest-api.md#collection-retrieval-scope-semantics) and [the EACH_COLLECTION TODO](../TODO.md).

### 3.3 Chat already uses Spring AI orchestration

The production Chat kernel is `ChatExecutionService`:

- `KNOWLEDGE` uses `RetrievalAugmentationAdvisor`;
- `AGENT` uses `ToolCallAdvisor`/`BudgetedToolCallAdvisor` and `KnowledgeSearchTool`;
- `PLAIN` does not retrieve;
- query expansion uses Spring AI `MultiQueryExpander`;
- history-aware follow-ups use Spring AI `CompressionQueryTransformer`;
- `ProjectDocumentRetriever` adapts the project hybrid retriever to Spring AI;
- `AuthorizedRetrievalContext` carries immutable scope, retrieval options, principal, and budgets;
- Chat supports model-candidate fallback, streaming fallback, source snapshots, and session leases.

Commit `b8a478f` added exact-term preservation to the Spring AI query-expander prompt and keeps the original query by default. Future quality work should validate that behavior with regression data rather than adding another natural-language regex.

### 3.4 Five concrete gaps

| Gap | Current fact | Consequence |
|---|---|---|
| External protocol | The main surface is `/api/v1/rag/**`; standard `/v1/chat/completions` and `/v1/models` do not exist | OpenAI SDKs, IDEs, Agents, and gateways cannot connect directly |
| OpenAI dynamic scope | OpenAI has no standard Collection field; an older draft fixed Collection scope in deployments | Without an extension, the interface is either ambiguous or unusably tied to one knowledge base |
| Ingestion jobs | `DocumentEmbedService` has synchronous, batch, SSE, and freshness behavior but no durable job/lease/worker/dead-letter lifecycle | Bulk re-embedding consumes HTTP requests and recovery is manual |
| JSON retrieval | JSON records persist `jsonbPayload + retrievalText` and can be hybrid-searched, but have no payload filter or JSON-specific Chat/Tool outlet | Structured data can be found but not safely filtered or handed to an Agent |
| Quality gate | Evaluation mainly accepts caller-produced retrieved/relevant IDs; the goldenset script runs real search but is not a versioned baseline/threshold gate | Retrieval changes are judged by intuition and regressions are hard to block |

Do not create a separate “professional reranker framework” item: the code already
has the `RerankProvider` SPI, heuristic and HTTP providers, and fallback behavior.
The next step is to compare heuristic versus a real cross-encoder/Rerank API inside
the regression gate and record the evidence in the quality docs. Add only a minimal
provider adapter if the existing HTTP contract cannot cover the target service.

## 4. Architecture and Dependencies

```text
Request-scoped Collection selection
    │
    ▼
OpenAI Chat Completions adapter
    │  reuses ChatExecutionService / Spring AI Advisor / Tool Calling
    │
    ├───────────────┐
    ▼               ▼
Durable ingestion   JSON structured-record retrieval
    │               │
    └──────┬────────┘
           ▼
Real retrieval regression gate
```

Dependencies:

1. Batch A extracts request-scope composition into a transport-neutral service; its Controller only maps protocol fields;
2. Batch B reuses `DocumentEmbedService`, `EmbeddingPersistenceService`, content hashes, document versions, and freshness;
3. Batch C reuses `JsonRecordService`, `HybridRetrieverService`, and `AuthorizedRetrievalContext`;
4. Every batch starts with repeatable regression cases so defaults are changed based on evidence.

## 5. Batch A: OpenAI Chat Completions with Request-Scoped Collections

### 5.1 Product goal

Allow common OpenAI SDKs to call the service:

```python
client.chat.completions.create(
    model="rag-default",
    messages=[{"role": "user", "content": "Find content about worn sofas"}],
    extra_body={
        "rag": {
            "scope": {
                "mode": "SELECTED_COLLECTIONS",
                "collection_keys": ["furniture-en", "support-faq"]
            }
        }
    },
)
```

`rag-default` selects the model/Chat pipeline but does not select a fixed knowledge base. Callers may also use repeated headers:

```http
X-RAG-Collection-Key: furniture-en
X-RAG-Collection-Key: support-faq
```

Clients without the extension use dynamic `CALLER_VISIBLE`. A pipeline that requires explicit selection returns `RAG_SCOPE_REQUIRED` instead of silently selecting a fixed Collection.

### 5.2 Scope and authorization rules

#### Request extension

Recommended namespaced extension:

```json
{
  "model": "rag-default",
  "messages": [
    {"role": "user", "content": "Find related content"}
  ],
  "stream": true,
  "rag": {
    "scope": {
      "mode": "SELECTED_COLLECTIONS",
      "collection_keys": ["support-en", "support-faq"]
    },
    "document_ids": [153, 154],
    "mode": "KNOWLEDGE",
    "memory": "STATELESS"
  }
}
```

Field boundaries:

- `model`: configured Chat model/pipeline alias only;
- `rag.scope`: this request's Collection scope;
- `rag.document_ids`: optional additional intersection;
- `rag.mode` and `rag.memory`: request overrides only when policy allows;
- prompts, metadata, and tool arguments are never authorization.

Resolution rules:

1. No body scope and no Collection headers means `CALLER_VISIBLE`;
2. A body scope uses the existing `CALLER_VISIBLE`, `ANY_COLLECTION`, or `SELECTED_COLLECTIONS` semantics;
3. repeated `X-RAG-Collection-Key` is equivalent to `SELECTED_COLLECTIONS + collectionKeys`;
4. body and headers must resolve to the same keys or return 400;
5. the adapter delegates to `CollectionRetrievalScopeResolver`; it does not implement a second resolver;
6. the current API-key allow-list always applies;
7. `document_ids` only narrows and never bypasses Collection;
8. an explicit empty scope, invalid key, size overflow, or body/header conflict returns 400;
9. an unauthorized Collection returns 403; the service does not silently drop it and continue;
10. no match retains empty/match-none semantics and never becomes a corpus-wide query.

The effective scope is:

```text
effectiveScope
  = requestScope(or CALLER_VISIBLE)
  ∩ currentApiKeyCollectionAcl
  ∩ documentIdsIntersection
```

There is deliberately no `deploymentScope`, because this plan does not bind a fixed Collection to a model/deployment.

### 5.3 Model aliases and execution boundary

The first slice uses a separate YAML alias registry and does not need a deployment
database. The existing external `models.json` continues to describe backend
provider/model configuration only; it must not also carry the public RAG alias
semantics, so backend identity and protocol identity remain separate:

```yaml
rag:
  openai-compatibility:
    enabled: false
    require-explicit-scope: false
    models:
      rag-default:
        candidates:
          - openrouter/model-a
          - openrouter/model-b
        mode: KNOWLEDGE
        memory: STATELESS
        allow-request-mode-override: false
        allow-request-generation-overrides: false
```

Rules:

- aliases contain no Collection keys;
- aliases cannot bypass `ChatModelRouter` capability checks or fallback;
- missing aliases return an explicit model error; a configured alias with no
  executable backend candidate or a temporarily unavailable provider is a service
  availability error, not a disguised “model does not exist” error;
- the response `model` is the requested alias; the backend model is only in controlled trace/optional extension;
- default memory is `STATELESS`, because OpenAI callers usually submit the complete message history;
- a controlled `AGENT` pipeline is possible, but external tool/function-call passthrough is out of scope for this batch.

Execution:

```text
OpenAI DTO
  -> message/content parsing
  -> model alias resolution
  -> request scope resolution
  -> ChatCommand / internal message list
  -> ChatExecutionService
      -> Spring AI RetrievalAugmentationAdvisor or ToolCallAdvisor
      -> ProjectDocumentRetriever / KnowledgeSearchTool
  -> OpenAI response/SSE mapper
```

The new Controller must not call the old Controller or copy retrieval SQL. If the current `ChatCommand` only carries one message, extend the internal transport-neutral command so `messages[]` is preserved before ChatClient invocation; keep the old `/api/v1/rag/chat/**` DTO contract unchanged.

Default message-mapping decisions:

- map both `system` and `developer` to Spring AI `SystemMessage`, preserving input order and using a stable separator to retain boundaries;
- map `user` and `assistant` to the corresponding Spring AI messages in order;
- accept string content or content parts containing text only;
- reject `tool`, function-call, image, and audio messages with an explicit `unsupported_message_type`; never drop them silently;
- `developer` is not placed in the ordinary user prompt and never controls Collection/ACL.

### 5.4 Protocol scope

MVP:

- `GET /v1/models`;
- `GET /v1/models/{id}`;
- `POST /v1/chat/completions`;
- text-only `system`, `developer`, `user`, and `assistant` messages;
- `n=1`;
- non-streaming response;
- standard `data:` SSE and `data: [DONE]`;
- real usage when the provider supplies it, otherwise omit it;
- OpenAI error envelope;
- `Authorization: Bearer` mapped to the current API-key principal;
- existing auth, trace, SLO, CORS, and rate-limit entry points extended to `/v1/**`, without full key hardening.

Fixed error semantics:

| Case | HTTP | OpenAI error type/code |
|---|---:|---|
| Invalid JSON, messages, scope, header conflict, or unsupported parameter | 400 | `invalid_request_error` / stable project code |
| Alias does not exist or is not exposed to the caller | 404 | `invalid_request_error` / `model_not_found` |
| Request exceeds the current API-key Collection ACL | 403 | `permission_error` / `collection_not_allowed` |
| Explicit scope is required but missing | 400 | `invalid_request_error` / `RAG_SCOPE_REQUIRED` |
| Missing or invalid credential | 401 | `authentication_error` |
| Alias exists but has no executable candidate, or provider/credential store is temporarily unavailable | 503 | `server_error` / stable project code |

Reject in the first slice:

- images, audio, and multimodal input;
- `n>1`, logprobs, and strict structured output;
- Responses, Assistants, and Batch APIs;
- external function/tool-call passthrough;
- server-hosted conversations;
- encoding Collection in the standard `model` field.

### 5.5 Implementation slices

1. Add `RagCompatibilityProperties` and an alias registry; validate aliases, candidates, capabilities, and conflicts at startup;
2. Add OpenAI request/response/error DTOs and the `rag.scope` DTO;
3. Add `RequestRetrievalScopeAdapter` for body/header parsing and delegation to `CollectionRetrievalScopeResolver`;
4. Extend the internal Chat command for complete messages, stateless memory, and request pipeline policy;
5. Add an independent `/v1` Controller and response/SSE mapper;
6. Extend Bearer, CORS, SLO, trace, and rate-limit path coverage to `/v1/**`;
7. Preserve `/api/v1/rag/**`;
8. Update `rest-api*`, `configuration*`, `SSE-PROTOCOL.md`, and the OpenAI readiness document;
9. Add `scripts/verify-openai-compatibility.sh` using HTTP/JSON/SSE assertions, never screenshots.

### 5.6 Acceptance and rollback

Must prove:

- no scope uses caller-visible dynamically, with no fixed Collection;
- selected scope narrows but never expands API-key ACL;
- equal body/header scopes pass and conflicts return 400;
- unauthorized scope returns 403; unknown alias returns 404 (`model_not_found`); explicit empty scope returns 400;
- an empty intersection cannot issue an unrestricted query;
- `/v1/models` does not expose Collection keys;
- complete-message mapping, system/developer semantics, and stateless memory;
- non-streaming fields, usage omission, and OpenAI error envelope;
- SSE ordering, `[DONE]`, pre-first-chunk fallback, and no post-first-chunk switch;
- both standalone core and starter consumer topologies;
- existing Chat/Search/Collection ACL integration regressions.

Rollback:

- set `rag.openai-compatibility.enabled=false` to disable the new entry;
- do not modify V1–V32;
- keep `/api` unchanged;
- remove aliases to return to the existing model API.

## 6. Batch B: Durable Embedding and Reindex Jobs

### 6.1 Why it is high value

`DocumentEmbedService` already provides synchronous single-document embedding, batch≤50, SSE, cache/freshness, and failure recording. HTTP requests still own provider calls, so large re-embedding has:

- request timeout risk;
- lost progress after restart;
- manual retry after transient provider failures;
- no common queue, lease, retry, failed-list, or operator API;
- caller-managed protection against old vectors racing with new content.

The first rollout gate is fixed at `rag.embedding-jobs.enabled=false`. When disabled,
the worker is not scheduled and create/retry endpoints return the stable
`503 EMBEDDING_JOBS_DISABLED`; they must not silently create work that nobody will
execute. Query and cancel remain available for already-created jobs so operators can
drain work after stopping the worker. After PostgreSQL, concurrency, restart, and
provider-failure gates pass, `application-prod.yml` may explicitly set it to `true`.
This flag controls only the asynchronous job surface; it does not change the legacy
synchronous embed APIs or JSON upsert's default synchronous behavior.

### 6.2 Recommended data model

Use PostgreSQL, not an external queue. Add a V33+ migration for `rag_embedding_jobs`:

| Field | Meaning |
|---|---|
| `id` | UUID or database-generated external job ID |
| `job_type` | `EMBED_DOCUMENT` in the first slice; profile re-embedding is a fan-out command, not a separate row type |
| `batch_id` | Optional grouping ID for one fan-out; a single-document request may also receive its own batch ID |
| `document_id` | target document |
| `embedding_profile_id` | active Profile captured at creation; the first-slice worker runs it only while it still matches the current active Profile |
| `force` | whether to regenerate even when the current embedding is fresh; `false` for repair, `true` for an explicit reindex batch |
| `content_hash` | text identity at creation |
| `document_version` | `RagDocument.version` captured at job creation; checked with `content_hash` on commit to protect newer content |
| `status` | `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `STALE` |
| `attempt_count` / `max_attempts` | bounded retry |
| `available_at` | backoff time |
| `lease_owner` / `lease_expires_at` | worker lease |
| `cancel_requested_at` | cooperative cancellation marker for a `RUNNING` job |
| `progress` | structured non-sensitive progress |
| `last_error` | masked and bounded |
| timestamps | lifecycle |

Every first-slice row must point to one concrete document and one target Profile. A
Profile- or scope-wide reindex first resolves and authorizes documents, then creates
one `EMBED_DOCUMENT` row per document and associates them with `batch_id`. It does
not create a special row with a null `document_id`; the worker handles only one job
shape, which keeps claiming, status, and recovery deterministic.

The current embedding write path supports one active Profile, and
`DocumentEmbedService`/`EmbeddingBatchService` are bound to the active
EmbeddingModel. The first-slice creation API therefore does not accept an arbitrary
`embeddingProfileId`; it captures the server's active Profile. Before calling the
provider, the worker compares it again. If the active Profile has changed, the old
job becomes `STALE` and an operator creates a new batch for the new active Profile.
The first slice neither rebuilds EmbeddingModel instances for historical Profiles
nor writes an old Profile with the new active model.

The current `DocumentEmbedService` performs the provider call and vector commit in
one synchronous method. The worker must not call it blindly and assume it can
intercept the commit afterward. Add a minimal worker-aware commit gate (for example
an internal `EmbeddingCommitGuard`, or a prepare/generate/commit internal API):
legacy synchronous endpoints use an allow-all guard, while the job worker rechecks
`cancel_requested_at`, active Profile, document version, and content hash immediately
before `EmbeddingPersistenceService.replace`. Do not copy chunking or persistence
logic into the worker; the existing persistence CAS remains the final consistency
guard.

Use a partial unique index for active-job idempotency. The mutable `status` is not
part of the unique columns:

```sql
UNIQUE (document_id, embedding_profile_id, content_hash)
WHERE status IN ('QUEUED', 'RUNNING')
```

`force` is not part of the unique key. If an active job already exists for the same
document/Profile/hash, a new request reuses it; when the new request asks for
`force=true` and the existing row is `false`, the service upgrades that row to
`force=true` in the same transaction instead of creating a second provider call.
`batch_id` is the logical coalescing batch for that active job identity; reuse
returns the original `batchId` and marks the response `COALESCED`, so one job is not
pretended to belong to multiple physical batches.

Ordinary documents use `contentHash`; JSON records use the `retrievalText` `contentHash`; external documents retain the existing source-revision/version semantics. Use the existing `RagDocument.version` for `document_version`; keep `DocumentVersion.versionNumber` as the audit version instead of inventing a third version system. Do not copy full content or `jsonbPayload` into a job.

If Batch B and Batch C are implemented in the same release, the recommended allocation is V33 for the job table and V34 for the JSONB filter index. If other merged work consumes a version first, use the next sequential versions after the current latest and update the progress ledger and docs; never rewrite V1–V32.

### 6.3 Worker and API

Worker:

1. bounded `@Scheduled` polling;
2. `FOR UPDATE SKIP LOCKED` claim of queued or lease-expired jobs inside a transaction;
3. if a claimed job already has `cancel_requested_at`, mark it `CANCELLED`
   without calling the provider;
4. provider call outside the transaction;
5. confirm the job Profile is still the active Profile, otherwise mark it `STALE`
   without calling the provider;
6. use a worker-aware internal `DocumentEmbedService` API for the active-Profile-bound
   freshness checks and pass through the job's `force` flag;
7. after the provider returns, reload the job and compare the current active
   Profile again; persist only when cancellation was not requested, the Profile
   did not switch, and document version, content hash, and profile still match;
   otherwise mark the job `STALE`;
8. retry after lease expiry;
9. end in `FAILED` after a bounded attempt count;
10. expose queued/running/succeeded/failed/stale counts, wait time, processing time, and provider-error categories through Micrometer.

Suggested API (the first slice accepts either `documentIds` or one authorized
Collection scope, expands to at most 1,000 documents, and returns a `batchId` plus
per-document job summaries):

```json
{
  "documentIds": [153, 154],
  "force": true,
  "maxAttempts": 3
}
```

`documentIds` and a Collection scope are mutually exclusive; scope fan-out uses
the current active Profile. `force=false` repairs stale/failed work, while
`force=true` explicitly reindexes even fresh rows. The server caps `maxAttempts`
with a bounded configuration maximum and never accepts infinite retry.

- `POST /api/v1/rag/embedding-jobs`;
- `GET /api/v1/rag/embedding-jobs/{id}` for one document task;
- `GET /api/v1/rag/embedding-jobs` filtered by `batchId`, status, Collection, or document;
- `POST /api/v1/rag/embedding-jobs/{id}/retry`;
- `POST /api/v1/rag/embedding-jobs/{id}/cancel`.

Cancellation is fixed as follows: `QUEUED` becomes `CANCELLED` immediately;
`RUNNING` only sets `cancel_requested_at` and does not force-kill an in-flight
provider call. After the provider returns and before replacing vectors, the worker
reloads the job; a cancellation request skips vector persistence and marks the job
`CANCELLED`. Repeated cancel/retry on a terminal job returns its current state or
an explicit 409 and never changes completed vectors.

Keep `/{id}/embed`, `/batch/embed`, and SSE synchronous in the first slice. External and JSON upserts may opt into `embedMode=ASYNC`; defaults remain unchanged.

### 6.4 Acceptance and rollback

Must prove:

- concurrent workers claim one job only;
- expired leases recover;
- queued and expired-running jobs resume after restart;
- provider failures back off and end in `FAILED`;
- newer content makes an old hash/version job `STALE`, never overwriting a new vector;
- after an active-Profile switch, queued or expired jobs for the old Profile become
  `STALE` and never borrow the new model;
- if the active Profile switches during a provider call, the returned result is
  also discarded and the job becomes `STALE`;
- duplicate active jobs are idempotent;
- `force=true` atomically upgrades an existing `force=false` active job and
  returns `COALESCED`;
- the partial unique index still blocks duplicates while jobs move between
  `QUEUED` and `RUNNING`;
- cancellation during a provider call takes effect before vector replacement;
- after a worker crash, an expired job with a cancellation marker does not call
  the provider again;
- create/list/retry/cancel obey Collection ACL;
- old sync APIs, JSON payload-only updates, and freshness behavior remain unchanged;
- PostgreSQL integration tests, `mvn clean compile test-compile`, and startup validation pass;
- `scripts/verify-embedding-jobs.sh` emits a stable machine-readable/human-readable summary.

Rollback:

- stop the worker without affecting synchronous endpoints;
- with `rag.embedding-jobs.enabled=false`, do not accept new work that would remain
  queued with no worker;
- stop creating new async jobs while retaining historical job visibility;
- add tables/indexes only; never delete vectors or rewrite executed migrations.

## 7. Batch C-1: JSON Structured-Record Retrieval

### 7.1 Preserve the core contract

The caller remains responsible for:

- `jsonbPayload`, the business JSON;
- `retrievalText`, natural-language text derived by the caller;
- whether the two correspond or are current.

The service continues to:

- hash only `retrievalText`;
- chunk, full-text index, and embed only `retrievalText`;
- avoid `payloadHash`;
- avoid converting payload to text;
- avoid injecting payload into ordinary `KNOWLEDGE` prompts;
- skip provider calls on payload-only updates while recording version snapshots.

### 7.2 First slice: bounded payload containment

Extend `POST /api/v1/rag/json-records/search` with:

```json
{
  "query": "worn sofa",
  "collectionKeys": ["furniture-records"],
  "payloadContains": {
    "status": "active",
    "category": "sofa"
  },
  "config": {
    "maxResults": 10
  }
}
```

The first slice promises only PostgreSQL JSONB containment:

```sql
jsonb_payload @> CAST(:payloadContains AS jsonb)
```

Limits:

- object only;
- complete-subtree containment, with object fields interpreted as AND;
- default serialized `payloadContains` limit of 16 KiB and maximum nesting depth of 8;
- an empty object returns 400 in the first slice, so “no structural filter” is not
  mistaken for an active filter;
- no SQL, arbitrary JSONPath, regex, or expression input;
- no range comparisons, arbitrary array matching, or case-insensitive matching in the first slice;
- push the filter into vector/full-text candidate qualification before each
  provider's `LIMIT`, result fusion, and reranking; never retrieve top-k and then
  post-filter in Java, or relevant records can be lost;
- extend `HybridRetrieverService`/full-text providers with a server-owned filter
  parameter, or use a parameter-bound JSONB prefilter subquery; never concatenate
  JSONB input into SQL;
- Collection, `document_type=json-record`, enabled, and embedding-freshness predicates remain mandatory;
- add a V33+ `jsonb_path_ops` GIN index only after PostgreSQL integration tests confirm the plan;
- the filter changes candidate eligibility, not embedding semantics.

The HTTP result keeps current payload, retrieval text, score, and identity fields.

### 7.3 Second slice: optional JSON Agent tool

Add an opt-in `searchJsonRecords` Spring AI tool:

```json
{
  "query": "worn sofa",
  "payloadContains": {"status": "active"},
  "maxResults": 5
}
```

The model-visible schema must not expose:

- Collection IDs;
- API keys;
- principal;
- scope mode;
- SQL/JSONPath;
- payload-field authorization policy.

The server-owned `AuthorizedRetrievalContext` supplies:

- immutable resolved Collection scope;
- `documentType=json-record`;
- result budget;
- payload output budget;
- citation trace.

Recommended defaults: at most 5 records per tool call, at most 32 KiB of payload
per record, and the existing Agent `maxToolResultCharacters` as the total tool-result
budget. When the budget is exceeded, omit payloads at record boundaries rather than
returning fabricated partial JSON.

Register the tool only for an explicitly enabled `AGENT` pipeline. Return:

- citation ID, document ID, external ID, and title;
- retrieval-text snippet;
- JSON payload within a byte budget;
- payload omitted/truncated status;
- filter-hit summary.

If the payload exceeds budget, omit it or return a valid structured truncation marker; never return malformed JSON. Ordinary `KNOWLEDGE` remains retrieval-text-only.

### 7.4 Implementation and acceptance

Implementation order:

1. DTO validation and filter canonicalization;
2. forward-only migration and GIN index;
3. scoped, filtered, freshness-aware `JsonRecordService` query;
4. Controller integration tests;
5. reuse the same service from the tool;
6. tool schema, budgets, citations, and feature flag;
7. JSONB E2E script and documentation.

Must prove:

- payload-only update does not call embeddings;
- retrieval-text changes invalidate old vectors;
- filter hit/miss behavior;
- nested objects, empty objects, oversized objects, and invalid values;
- Collection ACL and document type cannot be bypassed by filters;
- tool arguments cannot expand scope;
- payload budget produces valid JSON;
- PostgreSQL plan/index behavior;
- existing JSON upsert/search E2E remains green.

## 8. Batch C-2: Real Retrieval Regression Gates

### 8.1 Existing foundation and actual gap

The project already has `RetrievalEvaluationServiceImpl`, the Evaluation WebUI, and `run-retrieval-goldenset.sh`. The goldenset script already calls the real Search API and then the Evaluation API to calculate MRR, Precision@K, and nDCG; do not redesign the metrics.

The real gaps are:

- relevant identities depend too much on runtime document IDs or fixture titles;
- no stable dataset/version identity;
- no consistent recording of scope, retrieval config, active Embedding Profile, and code version;
- no explicit baseline/threshold/delta gate;
- failures, skips, and provider unavailability can be mistaken for quality success;
- Search and Chat retrieval semantics lack fixed exact-term, scope, and empty-result cases.

### 8.2 Low-risk first stage

Start with a **file-backed dataset + runner + CI gate**, not a full evaluation database:

```json
{
  "dataset": "retrieval-core",
  "version": 1,
  "k": 5,
  "cases": [
    {
      "id": "exact-term-sofa",
      "query": "worn sofa",
      "scope": {
        "mode": "SELECTED_COLLECTIONS",
        "collectionKeys": ["furniture-records"]
      },
      "relevant": [
        {"collectionKey": "furniture-records", "externalId": "record-1"}
      ],
      "minimum": {"hitRate": 1.0, "mrr": 0.5}
    }
  ]
}
```

Identity preference:

1. JSON/external documents: `collectionKey + externalId`;
2. ordinary documents: stable source identity;
3. titles only for test fixtures, never as a long-lived production identity;
4. database IDs are not the durable dataset key.

The runner must:

1. create/synchronize fixtures or use a dedicated Collection;
2. invoke the real Search/retrieval service with the case scope;
3. record original query, effective query, scope summary, retrieved identities, and latency;
4. calculate Precision@K, Recall@K, MRR, nDCG, and Hit Rate;
5. compare baseline and minimum thresholds;
6. return FAILED/SKIPPED and exit non-zero on provider, embedding, or database failure;
7. emit machine-readable JSON and a human-readable summary.

### 8.3 Second stage

After the file gate is stable in CI/release:

- persist `rag_eval_datasets`, `rag_eval_cases`, and `rag_eval_runs`;
- add WebUI dataset/run/trend views;
- align Chat and Search traces;
- turn feedback into candidate cases;
- make A/B experiments reference the same dataset;
- keep LLM-as-judge as a separate answer-quality metric, not the first retrieval blocking gate.

### 8.4 Required regression cases

- the original exact term must appear in at least one lexical query;
- query expansion must not drop Chinese proper nouns, product names, model IDs, codes, or quoted phrases;
- Search and Chat with the same Collection scope must not diverge into full-corpus vs empty-corpus behavior;
- body/header scope conflict;
- unauthorized Collection;
- payload-only JSON update;
- stale embedding;
- explicit zero-hit behavior when no relevant document exists.

Suggested scripts:

- `scripts/run-retrieval-regression.sh`;
- `scripts/verify-quality-regression.sh`.

## 9. Unified Implementation Workflow

### Phase 0: preflight

1. Record `git status --short`; never stash, hard-reset, or discard another developer's WIP;
2. reread [AGENTS.md](../../AGENTS.md), the [project-docs Skill](../../.agents/skills/project-docs/SKILL.md),
   architecture, REST, configuration, and testing documents;
3. verify the current commit, latest Flyway version, Spring AI dependency, and frontend scripts;
4. create a dedicated `docs/drafts/*_PROGRESS.md` ledger;
5. run baseline compile/document gates before attributing failures to this batch.

### Phase 1: Batch A

1. write scope composition, body/header conflict, and ACL integration tests first;
2. implement alias, DTO, Controller, and non-streaming behavior;
3. implement SSE, error envelope, and starter wiring;
4. add the one-command HTTP contract script;
5. update live API/configuration/SSE documentation.

### Phase 2: Batch B

1. migration, repository, and claim/lease tests;
2. single-document jobs;
3. retry/stale/restart behavior;
4. reindex scanning and API;
5. WebUI state and one-command verification.

### Phase 3: Batch C

1. stabilize the file-based regression gate;
2. implement JSON payload filtering and its index;
3. implement the JSON Agent tool;
4. add dataset persistence/UI only after the file gate is reliable.

## 10. Quality Gates for Every Batch

Backend:

- focused unit and Controller/contract tests;
- PostgreSQL/Testcontainers integration tests wherever possible;
- `mvn clean compile test-compile`;
- standalone core and starter consumer;
- service startup;
- old `/api/v1/rag/**` regression;
- `bash -n`, `git diff --check`.

Frontend, only when WebUI changes:

- the project's TypeScript check (`npm run tsc` or the actual repository command);
- production build;
- Vitest;
- Mock Playwright;
- DOM visibility/accessibility, network requests/responses, interface JSON, read-only database queries, and assertions only; no screenshots.

Documentation and release:

- `./scripts/verify-project-docs.sh`;
- synchronized Chinese/English pairs;
- no edits to executed Flyway migrations;
- no secrets, payload dumps, or OpenClaw local state in Git;
- use `scripts/docker-build-local.sh` and the [China network guide](../china-network-guide.md) for mainland-China builds.

After the basic gates pass, perform three fixed-scope convergence reviews:

1. protocol/API, Collection scope, ACL, and old contracts;
2. schema, concurrency, idempotency, freshness, and rollback;
3. WebUI, scripts, documentation, defaults, and real entry points.

Any substantive issue resets the counter to zero after the fix and requires the basic gates plus all three reviews again. Three clean reviews with no modification are required before declaring a batch complete.

## 11. Value, Risk, and Reversibility

| Feature | Cost of not doing it | Main risk | Reversible boundary |
|---|---|---|---|
| Dynamic-scope OpenAI adapter | External ecosystems cannot consume RAG without custom integration | Protocol layer accidentally expands scope or loses message history | Feature flag, independent `/v1`, unchanged `/api` |
| Durable jobs | Large ingestion is not operationally reliable and retries are manual | Old jobs overwrite new content or duplicate provider calls | New table/worker; old synchronous APIs remain |
| JSON filter/tool | Structured data can only be coarsely searched and cannot be safely handed to Agents | Arbitrary JSONPath or oversized payload risks | Only `@>`; separate config and JSON endpoint |
| Regression gate | Retrieval changes cannot prove gains and user-visible failures recur | Unstable fixtures create false passes | File dataset and opt-in gate; development need not be blocked by default |

## 12. Decisions That Must Not Be Reopened During Implementation

| Question | Default decision | Reason | Reversible boundary |
|---|---|---|---|
| Collection in model/deployment? | No | Requests need dynamic scope switching | Enable explicit-scope-required, but do not fix keys |
| OpenAI request without scope? | `CALLER_VISIBLE` | Preserves current semantics and ordinary SDK compatibility | Configure explicit scope required |
| Scope transport? | `rag.scope` plus repeated headers | Covers extra-body clients and header-only clients | Keep the standard no-extension path |
| Task queue? | PostgreSQL `SKIP LOCKED`; one row per document, profile reindex fan-outs into a batch | PostgreSQL is already the primary data plane and avoids special task rows | Replace worker backend only if load evidence requires it |
| JSON filter? | Object containment via `jsonb @>`; 16 KiB, depth 8, empty object rejected, and pushed before `LIMIT` | Indexable, bounded, and explicit | Add a separate query DSL later |
| JSON/text correspondence? | Caller responsibility | The service stores and retrieves; it does not know domain truth | Add a domain validation plugin separately |
| Evaluation first stage? | File dataset + runner | Reuses existing APIs and produces evidence quickly | Persist datasets/runs after evidence is stable |
| API-key hardening/quota? | Defer | Immediate value is usable capability, not a billing control plane | Separate precondition for public exposure |
| XML/Office? | Defer | Reliability and quality loops come first | Prioritize formats using real demand and goldenset evidence |

## 13. Planning Completion Criteria

The planning phase is complete only when:

- every current fact is locatable in code or live documentation;
- no fixed-default-Collection design remains;
- `model` selects model/pipeline while scope is request-scoped;
- body/header/no-extension semantics, ACL, and error boundaries are explicit;
- Spring AI and project-owned responsibilities are separated;
- every candidate has implementation boundaries, data/API shapes, tests, scripts, docs, and rollback;
- API-key hardening/quotas, XML/Office, and lower-value work are explicitly deferred;
- Chinese and English structure and facts are synchronized;
- the document passes three consecutive no-modification systematic reviews.

Nearby references:

- [Project context](../project-context.md)
- [Architecture](../architecture.md)
- [Collection retrieval scope semantics](../rest-api.md#collection-retrieval-scope-semantics)
- [OpenAI compatibility readiness](../openai-compatibility-readiness.md)
- [Existing OpenAI compatibility plan](2026-07-21_OPENAI_CHAT_COMPLETIONS_COMPATIBILITY_PLAN.md)
- [JSONB implementation plan](2026-08-15_JSONB_PAYLOAD_RETRIEVAL_IMPLEMENTATION_PLAN.md)
- [External document synchronization plan](2026-08-16_EXTERNAL_DOCUMENT_UPSERT_AND_REINDEX_IMPLEMENTATION_PLAN.md)
- [Testing guide](../testing-guide.md)
- [Quality defaults](../quality-defaults.md)
- [Project documentation Skill](../../.agents/skills/project-docs/SKILL.md)
