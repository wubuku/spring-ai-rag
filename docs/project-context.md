# Project Context

> [English](project-context.md) | [中文](project-context-zh-CN.md)

> **Purpose**: Give contributors and Agents stable, code-backed project context.
> **Last reviewed**: 2026-08-27.
> This document records current facts. Target designs and unimplemented capabilities must be labeled as plans.

Documentation hub: [index.md](index.md). Commands: [developer-reference.md](developer-reference.md).

## 1. Positioning

spring-ai-rag is a general RAG framework built on Spring AI:

- Model-agnostic: Chat and Embedding providers are independent.
- Domain-decoupled: `DomainRagExtension` customizes prompts and retrieval.
- Componentized: API, core, starter, document processing, and WebUI are separate.
- Observable: retrieval logs, evaluation, feedback, A/B tests, alerts, and metrics.
- Deliverable: Docker, Helm, an embedded WebUI bundle, and release gates.

## 2. Module Boundaries

| Module | Responsibility |
|--------|----------------|
| `spring-ai-rag-api` | DTOs and SPIs; no business implementation |
| `spring-ai-rag-core` | RAG implementation, controllers, advisors, services, runnable app |
| `spring-ai-rag-starter` | Spring Boot auto-configuration and embedded integration |
| `spring-ai-rag-documents` | Document chunking, cleaning, and processing |
| `spring-ai-rag-webui` | React administration UI |
| `demos` | Basic, component, domain, and multi-model examples |

The system supports two runtime topologies:

1. Run the Core application directly.
2. Add the Starter to another Spring Boot application.

Security, rate limiting, and auto-configuration changes must verify both.

## 3. RAG Execution

Production Chat uses an explicit mode-aware execution path:

```text
RagChatController
  -> CollectionRetrievalScopeResolver
  -> ChatCommandMapper
  -> ChatExecutionService
     -> KNOWLEDGE: Spring AI RetrievalAugmentationAdvisor
        + CompositeChatDocumentRetriever
          + ProjectDocumentRetriever
          + optional StaticKnowledgeDocumentRetriever
        + ProjectRerankPostProcessor
        + CitationQueryAugmenter
     -> AGENT: Spring AI ToolCallAdvisor
        + KnowledgeSearchTool
        + optional static-knowledge / Runtime Skill / allowlisted HTTP tools
        + server-owned ToolContext
     -> PLAIN: ChatClient + Memory only
  -> principal-scoped session lease
  -> atomic history + source snapshot + JDBC-compatible Memory projection
```

Key rules:

- `KNOWLEDGE` is the compatibility default and always retrieves.
- `AGENT` lets a tool-capable model decide whether and how often to call the
  authorized knowledge search tool. The tool schema cannot carry Collection,
  document, or credential scope.
- `PLAIN` performs no retrieval and rejects retrieval-specific request
  overrides.
- Chat and Search share the project hybrid retriever and support Collection /
  Document scope. A non-empty scope that resolves to no documents fails closed
  instead of becoming a full-corpus query.
- Optional static knowledge builds a `GLOBAL`, non-embedding, immutable lexical
  snapshot from classpath/filesystem/JAR resources at startup. KNOWLEDGE always
  combines it, AGENT may call `searchStaticKnowledge`, and PLAIN does not read
  it. Private or tenant data still belongs in the ACL-protected project store.
- Runtime Skills provide bounded, untrusted operational instructions only to
  AGENT. `loadSkill`, `readSkillReference`, and configured read-only HTTPS tools
  share request-local budgets; authorization remains server-owned through Tool
  policy, the Skill capability gate, and endpoint allowlists.
- The old `QueryRewriteAdvisor`, `HybridSearchAdvisor`, and `RerankAdvisor`
  remain component-level/compatibility APIs, not the production Chat path.
- `RagAdvisorProvider` defaults to `KNOWLEDGE + ATTEMPT`. ATTEMPT providers
  run outside Memory and the mode advisor; explicitly opted-in `MODEL_CALL`
  providers run inside the mode advisor and therefore execute for each AGENT
  tool-call round. Arbitrary provider orders are mapped into stable,
  non-overlapping bands.
- `DomainRagExtension` applies only when the request explicitly selects
  `domainId`; omitting it does not select the first bean. Domain prompts provide
  instructions while RAG/tools inject evidence. Legacy `{context}` prompts
  cannot be used directly in `AGENT/PLAIN`, and
  `postProcessAnswer/isApplicable` are not part of the new Chat path.
- Spring AI Memory and business history are stored separately but completed
  turns are committed atomically. Spring AI `1.1.8` JDBC stores only
  recoverable user/plain-assistant messages; complete tool exchanges are saved
  as a bounded `toolTranscript` in business-history metadata for later summary
  input.
- `BudgetedChatModel` records at most one bounded terminal usage event for each
  model call or stream subscription belonging to a Chat execution. V53 stores
  the append-only event in `rag_llm_usage_event`; attribution covers Chat,
  query transform/expansion, summaries, fallback candidates, application
  retries, and AGENT rounds that pass through the mode-aware or compatibility
  entry points.
- Usage recording is fail-open for Chat correctness. Non-streaming events use
  bounded synchronous confirmation, streaming events use bounded asynchronous
  recording, and retention deletes old rows in bounded batches. No prompt,
  answer, tool payload, credential, or exception body is stored. The
  principal-scoped `GET /api/v1/rag/usage` endpoint exposes aggregate token and
  configured-cost estimates, not provider billing or hard-limit settlement.

### Chat Sessions And Streaming

- Session history, export, clear, Memory baselines, and active leases are
  scoped to the authenticated principal.
- V32 stores `owner_principal_id`, a JSONB source snapshot, and turn status in
  `rag_chat_history`; `rag_chat_session_lease` provides cross-instance
  single-flight and token fencing.
- A missing session and a session owned by another principal both return
  `SESSION_NOT_FOUND`.
- Chat SSE emits `content`, `tool_start`, `tool_result`, `sources`, `done`, and
  `error`. `done` and `error` are mutually exclusive terminal events.
- Client cancellation disposes the model subscription and does not persist an
  incomplete turn. Streaming fallback is permitted only before the first
  client-visible event.
- Citation scores are ranking signals, not calibrated probabilities.

### Current Collection Semantics

Collection is not merely a display category. It is an active knowledge-base
boundary across ingestion, retrieval, and authorization:

- `rag_collection.id` is the internal `Long` primary/foreign-key identity.
  Caller-supplied `collectionKey` is the preferred external identity. It is
  1-128 visible ASCII characters, case-sensitive, globally unique, immutable,
  and remains reserved after soft deletion.
- `rag_documents.collection_id` defines the one-to-many relationship. A
  document belongs to at most one Collection and may also be unassigned.
- Chat and Search accept `CALLER_VISIBLE`, `ANY_COLLECTION`, and
  `SELECTED_COLLECTIONS`. Omitted mode plus a Collection list preserves the
  legacy selected behavior; omitting both mode and lists means
  `CALLER_VISIBLE`. Restricted API keys never gain access through a mode:
  caller-visible and any-Collection both resolve to the key's allow-list.
- `CollectionRetrievalScopeResolver` validates the request, batches
  case-sensitive key resolution through `CollectionIdentityResolver`, applies
  `ApiKeyCollectionAccess`, and produces an immutable `RetrievalScope`.
  Explicit `documentIds` are an additional intersection. Empty or invalid
  selected input returns `400`; restricted unknown/unauthorized keys return
  `403`; unrestricted unknown keys return `404`.
- Collection CRUD, restore, clone, document association, import/export,
  document ingestion, upload, PDF-to-RAG, WebUI, and API-key management expose
  the stable key. Database relationships and retrieval remain numeric.
- Collection create accepts an optional `Idempotency-Key`. The first keyed
  create returns `201`; exact same-owner replay returns `200`, a replay header,
  and the Collection's current state without another create audit. Semantic
  reuse conflicts and an unavailable ledger fails closed. The WebUI generates
  one UUID per submit so Axios retries reuse the same command identity.
- WebUI Chat and Search expose all three modes. Selected mode supports
  server-side Collection search, 50-item pages, cross-page multi-selection,
  and up to 100 keys. Collections, Documents, Files, and API Keys also use keys
  at their external boundary.

Current boundaries:

- Collection `embeddingModel` and `dimensions` are management/import-export
  metadata. They do not select a per-Collection EmbeddingModel; ingestion and
  query embedding still use the global embedding configuration.
- Vector retrieval excludes disabled documents and requires a fresh completed
  state for the active Embedding Profile. Full-text retrieval uses the
  profile-neutral local chunks and requires the current local index state.
- Deleting a Collection attempts a soft delete. If it contains any externally managed
  document with a nonblank `externalId`, the service returns `409` and does not
  delete the Collection, because unlinking would destroy the stable
  `collectionKey + sourceNamespace + externalId` identity. Otherwise it only unlinks legacy
  documents; it does not delete documents or embeddings. Unlinked documents
  may still appear in unscoped full-corpus retrieval. The deleted Collection's
  key cannot be reused.
- `RetrievalScopeSql` pushes Collection filters directly into vector and all
  full-text SQL paths: `d.collection_id IS NOT NULL` for any assigned
  Collection, or `d.collection_id = ANY (?)` with a JDBC `bigint[]` parameter
  for selected Collections. Explicit document IDs use a separate `bigint[]`
  predicate.
- Retrieval computes one global top-k across the effective Collection union.
  It does not yet provide an `EACH_COLLECTION` mode that guarantees a result
  contribution from every selected Collection.

See [architecture.md](architecture.md).

### File Artifacts And The RAG Bridge

`fs_files` stores path-addressed import artifacts and synthesizes directories
from path prefixes. The current WebUI Files workflow is PDF-specific: one
import creates a UUID directory containing the original PDF, `default.md`, and
converted assets. These artifacts are previewable but are not searchable RAG
documents.

**Add to RAG** bridges the layers by reading `default.md`, creating or reusing
a `rag_documents` row by the `pdf-import:{uuid}/default.md` source,
associating an optional Collection, and triggering embedding. Different UUIDs
retain distinct documents even when their content is identical; the content
hash only controls embedding freshness. The Search API/WebUI can then trace a
hit to its Files directory, indexed Markdown, and original PDF. The Documents
upload path instead ingests supported text files directly into `rag_documents`
without creating `fs_files` artifacts.
See [File Management, PDF Import, And RAG Integration](file-management-and-pdf-rag.md).

### WebUI Browser Navigation Contract

The WebUI uses React Router `BrowserRouter` with the production basename
`/webui`. Stable page context that can be reloaded from backend data belongs in
the path or query string instead of component-local state. The currently
addressable state includes:

- Search: committed `query`, `hybrid`, `scopeMode`, and repeated
  `collectionKey` values;
- Files: directory `path`, previewed `file`, and import-time `sort=asc`;
- Documents: `collectionKey`, `keyword`, and `page`;
- Chat sessions and A/B experiment details: `/chat/{sessionId}` and
  `/abtest/{experimentId}`;
- active Settings, Evaluation, and Alerts tabs: `tab`.

Cross-page links, browser back/forward, and direct deep links can therefore
restore these contexts. The Root API Key remains page-memory only: a full
reload first opens `/webui/unlock`, then returns to the original pathname and
query and reloads its data after a successful unlock. API keys, raw file
content, unsubmitted form drafts, modals, menus, hover/focus, and in-progress
upload state must not be placed in URLs.

When adding or changing a page, any state that users reasonably expect to
restore through back, forward, reload, or a shared address requires matching
Router state and a Mock Playwright round-trip test. Transient UI state remains
local.

## 4. Retrieval And Quality

- Embedding defaults to SiliconFlow `BAAI/bge-m3`.
- Embeddings use the immutable `rag_embedding_profiles` identity and the
  fixed-length `rag_embeddings.embedding_1024 VECTOR(1024)` column. A document
  is fresh for a Profile only when its state is `COMPLETED` and its content hash
  matches the current document.
- Retrieval combines vector and full-text signals.
- The production profile recommends query rewrite and local heuristic reranking.
- Goldenset metrics include Precision@K, MRR, and nDCG.
- `testdata/regression/retrieval-core-v1.json` uses stable
  `collectionKey + sourceNamespace + externalId` identities for live retrieval regression. The
  runner checks metric floors, the committed baseline, Collection leakage, and
  explicit empty-result behavior.

The small online goldenset gave perfect baseline and quality scores. Deterministic MRR tests demonstrate reranking gain; the online sample is not statistical evidence.

See [quality-defaults.md](quality-defaults.md).

### JSON Structured Records

The JSON record API stores caller-owned business data in
`RagDocument.jsonbPayload` / `rag_documents.jsonb_payload` and stores the
caller-owned natural-language description in the existing `content` field,
exposed as `retrievalText`. Only `retrievalText` is hashed, chunked, full-text
indexed, embedded, and eligible for normal RAG prompt context. The service
does not derive or verify the description against the JSON.

JSON records expose `collectionKey + sourceNamespace + externalId` as their stable external
identity and resolve it to the internal
`(collectionId, sourceNamespace, documentType=json-record, externalId)`
idempotency key.
Deprecated ID input remains compatible, and responses include both identities.
They do not use global content-hash deduplication, so different payloads may
share the same description. Payload-only updates create an auditable document
version without invalidating a fresh embedding; there is intentionally no
`payloadHash`. The dedicated search API enriches ranked results with the
current JSONB payload after retrieval, and does not copy payload into embedding
metadata or ordinary chat prompts.
Search optionally accepts `payloadContains`, using PostgreSQL `jsonb @>` subtree
containment pushed into every vector and full-text candidate query. V34 provides
a partial GIN `jsonb_path_ops` index. The disabled-by-default Spring AI
`searchJsonRecords` tool reuses the same service and authorized context and
does not accept model-supplied Collections, SQL, or JSONPath.

Ordinary non-blank short documents are retained as at least one chunk.
`minChunkSize` is a best-effort chunk-quality target, not a document-loss
filter. JSON records use one record-level chunk.

<a id="external-document-synchronization"></a>

### External Document Synchronization

Ordinary external documents use the stable triple
`collectionKey + sourceNamespace + externalId`, with caller-supplied opaque
`sourceRevision`. `POST /documents/upsert` preserves the internal `documentId`,
supports exact replay and strict-by-default `expectedSourceRevision` CAS, and
records complete snapshots. Content changes invalidate retrieval freshness and
persist a new-generation embedding job in the same transaction. Metadata,
payload, and source-revision-only changes do not call the embedding provider.
Old workers must pass generation, hash, chunker-version, Profile, and lease commit
fences and cannot overwrite newer content. Source deletion is an enabled=false
tombstone restorable by a distinct subsequent `sourceRevision`.
`POST /documents/batch-upsert`,
`GET /documents/by-external-id`, and the corresponding source-delete endpoint
are available to external connectors. JSON records retain their dedicated
payload/retrieval-text semantics.

For ordinary external-text synchronization, `collectionKey` is required and
must resolve to an active real Collection. JSON-record upsert retains
deprecated numeric input, but resolves it to the same canonical key-based
address. Local documents may have `collection_id IS NULL`, which means
unassigned, not a default Collection. The non-null
`source_namespace` column normalizes omitted or blank input to the compatibility
value `default`; clients may choose another namespace when the configuration
allows it. Current identifier limits are 128 characters for `collectionKey`
and `sourceNamespace`, and 255 for `externalId`; future migrations must not
reduce them.

No `__DEFAULT__` sentinel is defined. `default` is the literal compatibility
namespace, and it may also be sent explicitly; it never creates or selects a
default Collection. `sourceNamespace` is an identity/source-reconciliation
partition, not a retrieval-scope or ACL dimension. Search and Chat currently
cross namespaces within the effective authorized Collection scope.

The tuple is the current placement address and ACL scope; it does not make
`externalId` globally unique across the service. Ordinary upsert does not move
placement. V44 explicit relocation atomically changes Collection under dual
Collection ACL, source-revision CAS, Collection lifecycle tokens, and Sync Run
namespace fencing while preserving `documentId`, history, and derived rows.
The old address enters a permanent retired-address ledger, so delayed lookup or
mutation returns a stable 409; reverse relocation resolves the matching marker
in the same transaction. This write capability defaults off behind a feature
flag.

See [REST API — External Documents](rest-api.md) for the complete
request/response contract, conflict handling, and client synchronization
best practices, and
[External Document Synchronization Client Guide](external-document-sync-client-guide.md)
for the runnable connector algorithm.

The reference client covers incremental webhook/CDC events and the authoritative
snapshot protocol. A Sync Run is scoped to one `collectionKey +
sourceNamespace`, stores only lease hashes and item fingerprints, and supports
`begin`, bounded `batch-upsert`, `preview-missing`, `complete`, and `abort`.
V51 adds cursor indexes to the existing item ledger and exposes a durable
receipt query requiring `RAG_READ`. It returns a current-state summary and
masked errors for response-loss recovery without exposing bodies, payloads,
metadata, fingerprints, lease/hash material, or provider details. Terminal
traversal is stable; active-run traversal is eventually consistent and must be
rescanned from the beginning after termination.
`ONLINE_CUT + TOMBSTONE` is the safe full-snapshot deletion mode; the client
uses `OFFLINE_MANIFEST + NONE` unless it can establish a source consistency cut.
Missing documents are tombstoned only after the preview fingerprint and
deletion-protection confirmation pass. A post-begin source mutation is protected
by the namespace mutation sequence and cannot be overwritten by an older
snapshot.

### Local Document Lifecycle

Local documents expose `documentRevision` as the business CAS token; JPA
`rowVersion` remains internal optimistic coordination. PATCH, disable, restore,
and permanent delete require the expected revision. A content mutation commits
the business revision, complete snapshot, freshness state, and durable job
atomically; provider calls happen after the transaction. Old chunks are
excluded immediately and the document stays unavailable until lifecycle
`searchability=READY`. Title, source, metadata, and Collection-only changes
read current document values immediately and do not re-embed. Externally
managed documents reject local CRUD and must use source identity and tombstones.

Version-history APIs support list, read, diff, and controlled restore. Restore
is enabled explicitly, accepts only a local document and a `FULL` snapshot, and
creates a new business revision plus a new `RESTORE` version. It never rewinds
history or lets an external connector mutate source-owned documents. The
restore path reuses normal content-change generation fencing; a metadata-only
restore does not call the embedding provider.

V43 decouples local keyword derivation from remote vectors. A non-`SKIP` content
mutation prepares the current chunks in `rag_document_chunks` and records
freshness in `rag_document_local_index_state` before remote provider work
completes. The old local generation is excluded immediately, so a provider
failure cannot expose stale text. The lifecycle is `KEYWORD_ONLY` when the
local index is current but the active Profile vector is queued, processing,
not requested, or failed; it becomes `READY` only when both branches are
current. `embeddingFresh` describes only vector freshness and must not be used
to decide whether keyword retrieval is available. `SKIP` removes current local
chunks and reports `NOT_REQUESTED`.

V45 adds a shared `DerivationIntegrityRepository`. Per-document lifecycle/cache,
legacy embedding readiness, and new Collection derivation readiness all verify
the same physical invariants rather than trusting state and row count. Summary
classification is aggregated in SQL, while detail and preview results are
bounded to 100 items. Controlled repair uses token hashes, fingerprints,
owner/ACL checks, leases, and a durable item ledger; local rebuild and vector-job
enqueue commit separately, and HTTP never loops over provider calls. Read-only
diagnostics are available by default; side-effecting repair defaults off behind
a feature flag.

## 5. Multi-Model Runtime

- Legacy provider beans remain for default-model compatibility.
- `ConfiguredChatModelFactory` creates and caches real instances by `provider/modelId`.
- `ChatModelRouter` owns explicit selection, defaults, and fallback.
- Chat, Settings, and model comparison accept concrete model references.
- External `models.json` can override YAML model configuration.
- Each model exposes normalized `capabilities.streaming` and
  `capabilities.toolCalling`. Streaming defaults to compatible `true` when
  omitted; Tool Calling must be explicitly `true`.
- `AGENT` rejects an explicitly selected model without Tool Calling support.
  Default routing skips ineligible candidates.

See [multi-model-external-config.md](multi-model-external-config.md).

## 6. Data And APIs

### Database

- PostgreSQL with pgvector.
- Flyway is currently V1–V58.
- V27/V28 add, backfill, validate, uniquely constrain, and make immutable the
  Collection business key; V29 adds JSONB structured records; V30 adds the
  external-document synchronization schema; V31 normalizes stored external
  document identities without rewriting the already-released V30 migration;
  V32 adds principal-owned Chat history, source snapshots, turn status, and
  session leases; V33 adds durable embedding jobs, leases, and active-job
  coalescing indexes; V34 adds the JSON-record payload-containment partial GIN
  index; V35 extends retrieval diagnostics; V36 adds ordinary-document metadata
  containment indexes; V37 extends embedding-job operations fields; V38 adds
  managed evaluation suites; V39 replaces explicit pessimistic coordination
  with atomic counters, concurrency slots, and CAS state; V40/V41 add document
  business revisions, complete snapshots, source namespaces, derivation
  generations, lifecycle/idempotency schema, and contracted triple-identity
  and active-job constraints; V42 adds authoritative external snapshot runs,
  idempotent item ledgers, and source/reconciliation deletion markers; V43
  adds profile-neutral local keyword chunks and independent local-index
  lifecycle state; V44 adds relocation idempotent responses and the permanent
  retired-address ledger; V45 adds derivation repair preview/item control-plane
  tables; V46 adds the owner/session-scoped `rag_chat_memory_summary` table with
  a forward-only history cursor and optimistic version CAS for bounded
  conversation summaries; V47 adds principal-scoped durable Chat turn
  operations, immutable replay snapshots, bounded lease/reclaim state, and
  opaque turn identity shared by operation status and business history; V48
  adds stable API principals, versioned credentials, a plaintext-secret guard,
  shared quota buckets, and the legacy ADMIN guard; V49 adds principal-scoped
  `RAG_READ` / `RAG_WRITE` operation capabilities and a database constraint;
  V50 adds the requester-scoped provisioning idempotency ledger, storing only
  key/fingerprint hashes and result metadata, never raw credentials; V51 adds
  bounded run/status cursor indexes for the Sync Run item ledger; V52 adds the
  owner-scoped Collection-create idempotency ledger with a restricted
  Collection foreign key; V53 adds the principal-scoped append-only
  model-invocation usage ledger; V54 adds bounded UTC hourly integration
  operation and authorized Collection-contribution rollups; V55 adds bounded
  staged API credential rotation, overlap deadlines, and a secret-free
  rotation operation ledger; V56 adds the retired Collection tombstone, Chat
  commit fence, normalized Chat/feedback document references and completeness
  markers, and a durable purge preview that stores neither bodies nor plaintext
  confirmation tokens; V57 adds deduplicated managed API-principal expiry
  state, notification versions, and a fair recovery-scan cursor; V58 adds
  `rag_alert_notification_delivery`, using stable UUIDs, uniqueness,
  leases/CAS, cumulative attempt budgets, and low-sensitivity receipts for
  durable at-least-once provider delivery.
- The data-access layer forbids explicit `SELECT ... FOR UPDATE`,
  `SKIP LOCKED`, JPA `PESSIMISTIC_*`, and PostgreSQL advisory locks.
  Concurrent writes use conditional `UPDATE/DELETE ... RETURNING`, `@Version`,
  unique constraints, leases, and bounded retries. Ordinary short-lived
  database locks caused internally by DML are outside this prohibition.
- `vector` is required, `pg_trgm` is recommended, and `pg_jieba` is optional.
- `rag_document_chunks` is the full-text source of truth; its English generated
  `tsvector`, optional pg_trgm GIN index, and optional `jiebacfg` expression
  index are created by V43 when the corresponding capability exists.
- `rag_document_local_index_state` stores one current local generation per
  document. It is independent of embedding Profile state and is advanced with
  conditional DML/generation checks, never pessimistic locks.
- Chat memory, business history, retrieval logs, evaluation, feedback, A/B tests, alerts, API keys, and files are stored separately.
- `rag_llm_usage_event` is an append-only observability ledger. It stores
  bounded invocation attribution, normalized provider usage, invocation-start
  configured pricing, outcome, and duration. It intentionally excludes
  prompts, answers, tool arguments/results, credentials, and exception bodies.
  Recording is fail-open, aggregate reads are principal-scoped, and retention
  is bounded.
- `rag_api_operation_hourly` counts each classified integration request once;
  `rag_api_collection_operation_hourly` stores authorized Collection
  contributions. Both contain only UTC hourly aggregates with bounded
  operation/status/latency dimensions. Recording is asynchronous and fail-open,
  current authorization is rechecked on query, and Collection contributions
  must not be summed as request totals. These tables are diagnostic
  observability, not billing, audit, quota, or mutation receipts.
- Alerts and provider deliveries are persisted in one transaction. An
  after-commit Spring Event provides low-latency wake-up, while the default
  one-minute Scheduled scan only recovers lost events, restarts, and expired
  leases. Provider I/O always runs outside database transactions. The delivery
  ledger excludes webhook/SMTP secrets, recipients, business bodies, and stack
  traces; operators can query only low-sensitivity receipts and manually retry
  eligible terminal failures.

### HTTP

The main namespace is `/api/v1/rag/**`:

| Area | Capability |
|------|------------|
| `/chat`, `/chat/stream` | KNOWLEDGE / AGENT / PLAIN chat and structured SSE |
| `/documents` | Local CRUD/lifecycle/embedding plus external idempotent sync and atomic relocation |
| `/search` | Hybrid retrieval |
| `/collections` | Knowledge collections, embedding/derivation readiness, bounded derivation repair, and the disabled-by-default guarded purge/retirement control plane |
| `/evaluation` | Evaluation and feedback |
| `/api-keys` | API-key management with optional idempotent principal provisioning |
| `/integration-capabilities` | Authenticated, versioned runtime integration contract |
| `/integration-observability` | Principal/ACL-scoped best-effort integration operation rollups |
| `/alerts/notification-deliveries` | Operator-visible low-sensitivity delivery receipts, filters, cursor pagination, and manual retry |
| `/files` | PDF and file import |
| `/json-records` | JSONB structured-record upsert, search, and detail |
| `/documents/upsert` | External triple identity, revision CAS, and tombstone synchronization |
| `/document-sync-runs` | Authoritative external snapshot reconciliation and durable item receipts |
| `/embedding-jobs` | Enabled-by-default durable embedding/reindex jobs |
| `/retrieval-traces` | Caller-visible retrieval diagnostics |
| `/collections/embedding-readiness` | Collection embedding readiness buckets |
| `/v1/models`, `/v1/chat/completions` | Disabled-by-default controlled OpenAI compatibility preview |

See [rest-api.md](rest-api.md) and [SSE-PROTOCOL.md](SSE-PROTOCOL.md).

## 7. Security And Collection ACL

Two compatible operating modes are available.

Standalone-service MVP mode is enabled explicitly by `RAG_ROOT_API_KEY`:

- The environment root protects `/api/**` independently of the legacy auth flag.
- The root unlocks the administration UI at `/webui/unlock`; the browser keeps
  it only in page memory and requires it again after refresh.
- Only the root can create, list, rotate, and revoke business keys.
- Root-created NORMAL keys may use read-only `RAG_READ` or full
  `RAG_READ + RAG_WRITE` data-plane capabilities and may be Collection-scoped,
  but cannot manage keys. Omission remains backward-compatible full read/write.
- Business-key expiry is required and must be in the future, with no fixed
  maximum lifetime. Raw secrets appear only in create or rotate responses.
- Root mode accepts only Bearer or `X-API-Key` headers, rejects query
  credentials, and disables legacy ADMIN bootstrap/raw-secret logging.

Without a root credential, legacy ADMIN/NORMAL/static-key behavior remains.

Managed database callers consist of a stable `rag_api_principal` and versioned
`rag_api_key` credentials:

- The principal owns role, Collection ACL, expiry, policy version, an optional
  quota, and canonical operation capabilities; a credential owns only its
  hash, version, active state, and optional retirement deadline.
- V48 deterministically backfills existing keys with `principalId=old keyId`,
  preserving historical `db:{keyId}` owners. Later rotations replace only the
  credential and retain the stable owner.
- Every authentication performs an authoritative credential/principal join and
  places an immutable policy snapshot in the request. Other instances reject a
  revoked credential on their next request. `last_used_at` is approximate and
  written at most once per five-minute process-local suppression window.
- The schema clears the legacy plaintext column, drops its index, and enforces
  `api_key IS NULL`; a raw secret appears only in create/rotate responses.
- Principal-row serialization, monotonic credential versions, policy CAS, and
  a singleton legacy-ADMIN guard protect management concurrency.
- With `backend=postgresql`, all instances share a stable-principal UTC
  fixed-minute quota. Rotation does not reset usage, and store failure fails
  closed with `503`.
- An authenticated central capability filter enforces `RAG_READ` /
  `RAG_WRITE` for database NORMAL principals. Reads and explicit read-only POST
  routes require read; other mutations require write by default. Capability
  `403` responses occur before quota accounting. Rotation inherits
  capabilities, while policy CAS may update them. ADMIN, root, legacy-static,
  and auth-disabled paths remain unrestricted.
- Root-managed principal creation accepts an optional `Idempotency-Key`.
  The first successful request returns `201` and shows the raw credential once;
  an exact replay returns `200`, current credential metadata, and an explicit
  `rawKey: null`. Reusing the same owner/key for different effective semantics
  returns `409`. Rotation or revocation changes the current credential
  projection returned by later replay without making the original secret
  recoverable.
- V55 retains the immediate `/rotate` compatibility path and adds bounded
  staged rotation for rolling deployments. Prepare requires an
  `Idempotency-Key`, returns a stable `rotationId`, shows the replacement
  secret once, and permits one current plus one retiring credential until a
  server-enforced deadline. Complete disables the retiring credential; cancel
  disables the replacement and restores the previous credential; expiry and
  family revocation fail closed. Exact prepare replay never returns the raw
  secret. Both credentials share the same principal, policy, Chat/session
  owner, usage attribution, and PostgreSQL quota.
- V57 publishes a Spring Event after principal creation, expiry-policy update,
  and family revocation to wake an asynchronous expiry-alert worker.
  PostgreSQL stores the unique active condition plus phase and notification
  versions. An hourly fair Scheduled scan is only a fallback for missed events
  and time-threshold crossings. Extension beyond the warning window or
  revocation resolves the condition, and ordinary business principals cannot
  access the Alerts control plane.
- Collection creation also accepts an optional `Idempotency-Key`, but uses a
  separate V52 ledger and never stores a response snapshot. Replay returns the
  bound Collection's current state and document count; soft deletion remains
  visible and is never reversed. Disabled or unavailable keyed provisioning
  returns `503` rather than falling back to an ordinary create.
- `GET /api/v1/rag/integration-capabilities` is an authenticated, no-store,
  low-sensitivity contract for protocol version, the caller's effective
  capabilities and Collection scope, supported data-plane behaviors, optional
  features, and runtime input limits. It publishes structured-record
  batch/payload/search/filter bounds, fixed Sync Run batch/page bounds, and
  observability retention/query limits. `features.credentialRotation`
  publishes immediate/staged/cancel support, idempotency and secret-replay
  behavior, default/maximum overlap, and operation retention.
  `documentSyncRunItemReceipts`
  explicitly reports whether durable receipt lookup is available, while
  `features.provisioning.collectionCreateIdempotencyKey` reports the V52
  control-plane capability. Caller-aware
  `features.optional.collectionPurge` is true only when the service flag is
  enabled and the identity is environment root, database ADMIN, or an
  explicitly local loopback caller; it also publishes synchronous purge
  limits. Restricted ACL
  projection fails closed with `503` when all Collection keys cannot be
  resolved.
- `GET /api/v1/rag/integration-observability` requires `RAG_READ` for NORMAL
  principals, uses an exclusive upper time bound, and supports HOUR/DAY,
  operation, Collection, and management principal filters. NORMAL principals
  are self/current-ACL only; root and database ADMIN can query broader scopes.
  Disabled or incompletely resolved scope returns `503`.
- `GET /api/v1/rag/usage` requires `RAG_READ` and accepts an inclusive UTC date
  range. Normal principals are restricted to themselves; ADMIN and environment
  root may select all or one principal. The default range is the latest 30 UTC
  days and the maximum is 366 days. Missing usage or pricing is represented
  explicitly rather than inferred as zero.
- Chat, Search, Collections, Documents, PDF-to-RAG, evaluation, and background
  workers all use the immutable ACL snapshot or reload policy by stable owner.
- Collection purge is disabled by default. When enabled, preview/token/
  fingerprint checks, Collection-first conditional writes, the Chat fence, and
  session leases remove target content and referencing feedback/persisted Chat
  state in one transaction. A permanent-key tombstone remains; retired
  Collections cannot be restored, written, explicitly retrieved, exported, or
  cloned. The independent file subsystem is never deleted by path guessing.

This completes the managed-principal multi-instance foundation, not a complete
tenant identity platform. OAuth/OIDC, tenant hierarchy, token/cost billing,
management recovery, and removal of all legacy static/query compatibility are
outside this batch. Public deployment still requires TLS, network controls,
credential operations, capacity planning, and monitoring.

See [openai-compatibility-readiness.md](openai-compatibility-readiness.md) for
these boundaries and the prerequisites for public enablement.

## 8. OpenAI Compatibility Direction

Do not confuse the two directions:

```text
Implemented: spring-ai-rag -> OpenAI-compatible provider
Controlled preview: OpenAI client / Agent -> spring-ai-rag
```

With `rag.openai-compatibility.enabled=true`, the project exposes
`GET /v1/models`, `GET /v1/models/{id}`, and text-only
`POST /v1/chat/completions`. A model alias identifies RAG mode, memory, and a
backend candidate chain, never a fixed Collection. Requests select scope
through `rag.scope` or repeated `X-RAG-Collection-Key` headers, then use the
shared Collection resolver and API-key ACL. Both JSON and standard SSE reuse the
transport-neutral `ChatCommand` / `ChatExecutionService`.

The feature is disabled by default. Its current subset is text-only, `n=1`, and
does not support tools, structured output, or sampling parameters. Native
`/api/v1/rag/chat/stream` retains project-specific tool, source, and terminal
events; the two SSE protocols are not interchangeable.

The controlled preview is suitable for trusted-network integration. Stable
principals, shared quotas, and immediate multi-instance revocation are now
implemented, but that alone is not public production readiness. Remaining
operational and legacy boundaries are maintained in the readiness reference.

See [OpenAI compatibility readiness](openai-compatibility-readiness.md) for the
current status and boundaries.

## 9. Stable 1.0 Baseline

Implemented:

- Production quality defaults and a retrieval goldenset.
- Collection-to-API-key ACLs.
- Runtime model instances and UI model selection.
- Maven, demos, OpenAPI, Helm, and Docker standardized on `1.0.0`.
- Embedded production WebUI bundle.
- Mainland-China-friendly Docker build path.
- One-command release verification.
- Request-scoped Collection selection in the controlled OpenAI compatibility preview.
- Enabled-by-default durable embedding jobs and the document lifecycle coordinator.
- Local revision-CAS CRUD, external source triple identity, and a runnable reference client.
- JSONB containment / Agent tool support and versioned live retrieval regression.

Full gate on 2026-07-21:

```text
19 passed, 0 failed, 0 skipped
Maven 3213 tests
Vitest 153
Playwright 37
HTTP E2E 66/66
Real LLM 10/10
```

See the [testing guide](testing-guide.md) and
[1.0 release gates](release-checklist.md) for repeatable current verification.
Historical counts remain available only as archived evidence.

## 10. Explicit Boundaries

- The immutable `1.0.0` source/image tag has not been created; the release pipeline owns it.
- Server-side OpenAI compatibility exists as a disabled-by-default controlled
  preview; it is not public or multi-instance production readiness.
- The durable embedding worker is enabled by default; production deployments
  must monitor capacity and provider cost. The JSON Agent tool remains disabled
  by default.
- Concurrency control does not use application-requested pessimistic locks;
  `scripts/verify-no-pessimistic-locks.sh` is the regression gate.
- OpenClaw `TOOLS.md`, `MEMORY.md`, `memory/`, `HEARTBEAT.md`, and related files are local state outside the project documentation system.
- Project Skills live under `.agents/skills/`; workflows may link here but must not duplicate project facts.

## 11. Source-Of-Truth Order

When information conflicts, use:

1. Current code and migrations.
2. Live references and guides under `docs/`.
3. Entry rules in `AGENTS.md` and `CLAUDE.md`.
4. Current active plans under `docs/drafts/`.
5. Historical plans and implementation records under `docs/drafts/archive/`.
6. Local Agent state.
