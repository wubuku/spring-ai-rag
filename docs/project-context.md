# Project Context

> [English](project-context.md) | [中文](project-context-zh-CN.md)

> **Purpose**: Give contributors and Agents stable, code-backed project context.
> **Last reviewed**: 2026-08-17.
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

Default advisor order:

```text
QueryRewriteAdvisor (+10)
  -> HybridSearchAdvisor (+20)
  -> RerankAdvisor (+30)
  -> MessageChatMemoryAdvisor
```

Key rules:

- Chat and search support Collection / Document scope.
- A non-empty Collection / Document scope that resolves to no documents must fail closed instead of becoming a full-corpus query.
- `RerankAdvisor` injects context into the user message for providers that restrict multiple system messages.
- Spring AI memory and business audit history are stored separately.

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
- Vector and full-text retrieval exclude disabled documents and require a fresh
  completed state for the active Embedding Profile.
- Deleting a Collection attempts a soft delete. If it contains any externally managed
  document with a nonblank `externalId`, the service returns `409` and does not
  delete the Collection, because unlinking would destroy the stable
  `collectionKey + externalId` identity. Otherwise it only unlinks legacy
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

The small online goldenset gave perfect baseline and quality scores. Deterministic MRR tests demonstrate reranking gain; the online sample is not statistical evidence.

See [quality-defaults.md](quality-defaults.md).

### JSON Structured Records

The JSON record API stores caller-owned business data in
`RagDocument.jsonbPayload` / `rag_documents.jsonb_payload` and stores the
caller-owned natural-language description in the existing `content` field,
exposed as `retrievalText`. Only `retrievalText` is hashed, chunked, full-text
indexed, embedded, and eligible for normal RAG prompt context. The service
does not derive or verify the description against the JSON.

JSON records expose `collectionKey + externalId` as their stable external
identity and resolve it to the internal
`(collectionId, documentType=json-record, externalId)` idempotency key.
Deprecated ID input remains compatible, and responses include both identities.
They do not use global content-hash deduplication, so different payloads may
share the same description. Payload-only updates create an auditable document
version without invalidating a fresh embedding; there is intentionally no
`payloadHash`. The dedicated search API enriches ranked results with the
current JSONB payload after retrieval, and does not copy payload into embedding
metadata or ordinary chat prompts.

Ordinary non-blank short documents are retained as at least one chunk.
`minChunkSize` is a best-effort chunk-quality target, not a document-loss
filter. JSON records use one record-level chunk.

<a id="external-document-synchronization"></a>

### External Document Synchronization

Ordinary external documents use the same stable external identity shape:
`collectionKey + externalId`, with caller-supplied opaque `sourceRevision`.
`POST /documents/upsert` preserves the internal `documentId`, supports exact
replay and optional `expectedSourceRevision` CAS, and records version snapshots.
Content changes invalidate retrieval freshness before synchronous re-embedding;
embedding failure is persisted against the new content hash and old vectors are
excluded from retrieval. Source deletion is an enabled=false tombstone that can
be restored by a distinct subsequent `sourceRevision`; the service does not
compare revision size or freshness. `POST /documents/batch-upsert`,
`GET /documents/by-external-id`, and the corresponding source-delete endpoint
are available to external connectors. JSON records retain their dedicated
payload/retrieval-text semantics.

See [REST API — External Documents](rest-api.md) for the complete
request/response contract, conflict handling, and client synchronization
best practices.

## 5. Multi-Model Runtime

- Legacy provider beans remain for default-model compatibility.
- `ConfiguredChatModelFactory` creates and caches real instances by `provider/modelId`.
- `ChatModelRouter` owns explicit selection, defaults, and fallback.
- Chat, Settings, and model comparison accept concrete model references.
- External `models.json` can override YAML model configuration.

See [multi-model-external-config.md](multi-model-external-config.md).

## 6. Data And APIs

### Database

- PostgreSQL with pgvector.
- Flyway is currently V1–V31.
- V27/V28 add, backfill, validate, uniquely constrain, and make immutable the
  Collection business key; V29 adds JSONB structured records; V30 adds the
  external-document synchronization schema; V31 normalizes stored external
  document identities without rewriting the already-released V30 migration.
- `vector` is required, `pg_trgm` is recommended, and `pg_jieba` is optional.
- Chat memory, business history, retrieval logs, evaluation, feedback, A/B tests, alerts, API keys, and files are stored separately.

### HTTP

The main namespace is `/api/v1/rag/**`:

| Area | Capability |
|------|------------|
| `/chat`, `/chat/stream` | RAG chat |
| `/documents` | Document management and embedding |
| `/search` | Hybrid retrieval |
| `/collections` | Knowledge collections |
| `/evaluation` | Evaluation and feedback |
| `/api-keys` | API-key management |
| `/files` | PDF and file import |
| `/json-records` | JSONB structured-record upsert, search, and detail |
| `/documents/upsert` | Idempotent ordinary external-document synchronization |

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

See [openai-compatibility-readiness.md](openai-compatibility-readiness.md) and the
[API-key hardening implementation plan](drafts/2026-08-14_API_KEY_HARDENING_IMPLEMENTATION_PLAN.md).

## 8. OpenAI Compatibility Direction

Do not confuse the two directions:

```text
Implemented: spring-ai-rag -> OpenAI-compatible provider
Not implemented: OpenAI client / Agent -> spring-ai-rag
```

The project does not currently expose a standard `POST /v1/chat/completions` or Models API. Existing SSE only emits a partial OpenAI-like delta and is not Chat Completions compatible.

The planned compatibility layer presents a complete RAG deployment as a `model`. It is disabled and stateless by default, and requires external API-key, Bearer-authentication, and multi-instance rate-limit hardening first.

See the [OpenAI Chat Completions compatibility plan](drafts/2026-07-21_OPENAI_CHAT_COMPLETIONS_COMPATIBILITY_PLAN.md).

## 9. Stable 1.0 Baseline

Implemented:

- Production quality defaults and a retrieval goldenset.
- Collection-to-API-key ACLs.
- Runtime model instances and UI model selection.
- Maven, demos, OpenAPI, Helm, and Docker standardized on `1.0.0`.
- Embedded production WebUI bundle.
- Mainland-China-friendly Docker build path.
- One-command release verification.

Full gate on 2026-07-21:

```text
19 passed, 0 failed, 0 skipped
Maven 3213 tests
Vitest 153
Playwright 37
HTTP E2E 66/66
Real LLM 10/10
```

See [P1 / 1.0 readiness progress](drafts/2026-07-21_P1_10_READINESS_PROGRESS.md).

## 10. Explicit Boundaries

- The immutable `1.0.0` source/image tag has not been created; the release pipeline owns it.
- Server-side OpenAI compatibility remains a plan, not a current feature.
- OpenClaw `TOOLS.md`, `MEMORY.md`, `memory/`, `HEARTBEAT.md`, and related files are local state outside the project documentation system.
- Project Skills live under `.agents/skills/`; workflows may link here but must not duplicate project facts.

## 11. Source-Of-Truth Order

When information conflicts, use:

1. Current code and migrations.
2. Live references and guides under `docs/`.
3. Entry rules in `AGENTS.md` and `CLAUDE.md`.
4. `docs/drafts/` and `*-plan.md`.
5. Local Agent state.
