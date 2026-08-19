# Project Context

> [English](project-context.md) | [中文](project-context-zh-CN.md)

> **Purpose**: Give contributors and Agents stable, code-backed project context.
> **Last reviewed**: 2026-08-19.
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
        + ProjectDocumentRetriever
        + ProjectRerankPostProcessor
        + CitationQueryAugmenter
     -> AGENT: Spring AI ToolCallAdvisor
        + KnowledgeSearchTool
        + server-owned ToolContext
     -> PLAIN: ChatClient + Memory only
  -> principal-scoped session lease
  -> atomic history + source snapshot + JDBC Memory commit
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
  turns are committed atomically.

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
`externalId` globally unique across the service. Ordinary upsert cannot
atomically change the Collection of an externally managed document. A changed
`collectionKey` addresses another placement and does not delete the old one.
The compatibility flow of tombstoning the old address and upserting the target
creates a new internal document and version history; an explicit atomic
relocation preserving `documentId` remains future work.

See [REST API — External Documents](rest-api.md) for the complete
request/response contract, conflict handling, and client synchronization
best practices, and
[External Document Synchronization Client Guide](external-document-sync-client-guide.md)
for the runnable connector algorithm.

The reference client covers incremental webhook/CDC events and the authoritative
snapshot protocol. A Sync Run is scoped to one `collectionKey +
sourceNamespace`, stores only lease hashes and item fingerprints, and supports
`begin`, bounded `batch-upsert`, `preview-missing`, `complete`, and `abort`.
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
- Flyway is currently V1–V43.
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
  lifecycle state.
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

### HTTP

The main namespace is `/api/v1/rag/**`:

| Area | Capability |
|------|------------|
| `/chat`, `/chat/stream` | KNOWLEDGE / AGENT / PLAIN chat and structured SSE |
| `/documents` | Local create/PATCH/disable/restore/version-restore/permanent-delete, lifecycle, and embedding |
| `/search` | Hybrid retrieval |
| `/collections` | Knowledge collections |
| `/evaluation` | Evaluation and feedback |
| `/api-keys` | API-key management |
| `/files` | PDF and file import |
| `/json-records` | JSONB structured-record upsert, search, and detail |
| `/documents/upsert` | External triple identity, revision CAS, and tombstone synchronization |
| `/document-sync-runs` | Authoritative external snapshot reconciliation |
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
- Root-created keys have a fixed `FULL_RAG` data-plane profile. They can read
  and write RAG data and may be Collection-scoped, but cannot manage keys.
- Business-key expiry is required and must be in the future, with no fixed
  maximum lifetime. Raw secrets appear only in create or rotate responses.
- Root mode accepts only Bearer or `X-API-Key` headers, rejects query
  credentials, and disables legacy ADMIN bootstrap/raw-secret logging.

Without a root credential, legacy ADMIN/NORMAL/static-key behavior remains.

Database API keys support:

- Hash lookup.
- `ADMIN` / `NORMAL` roles.
- Expiration, revocation, rotation, and `last_used_at`.
- `allowedCollectionKeys` is the preferred external field; deprecated
  `allowedCollectionIds` remains compatible. V24 storage and runtime
  authorization still use internal IDs in `rag_api_key.allowed_collection_ids`.
- Data-plane ACLs for Chat, Search, Collections, Documents, and PDF-to-RAG.

This MVP is limited to a single instance, TLS, and a trusted management
network. It is not yet a complete multi-tenant external credential system:

- The schema retains a plaintext column.
- NORMAL-key delegation needs stronger boundaries.
- Rotation lacks a stable principal or family.
- There is no transactional last-ADMIN guard.
- Multi-instance revocation, shared limiting, and write amplification remain unresolved.

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

The controlled preview is suitable for trusted-network integration, but it is
not public, multi-instance production readiness. API-key families, shared
quotas, and immediate cross-instance revocation remain explicit future
boundaries.

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
