# REST API Reference

📖 [English](rest-api.md) · 📖 [中文](rest-api-zh-CN.md)

> Swagger UI available at `/swagger-ui.html` after startup.
>
> Base path: `/api/v1/rag`

---

## General

### Authentication

Send credentials in a header:

```
Authorization: Bearer your-api-key
X-API-Key: your-api-key
```

Providing both headers with different values returns `401`.

Setting `RAG_ROOT_API_KEY` enables standalone-service root mode:

- Every `/api/**` request automatically requires the environment root or a
  valid database business key.
- Query credentials (`?apiKey=`) return `401`; SSE uses `fetch` with a header.
- The environment root can use the RAG data plane and manage API keys.
- Database business keys have `FULL_RAG` read/write access but receive `403`
  from `/api-keys` management endpoints.
- Legacy `rag.security.api-key` does not participate in root-mode
  authentication.

Without a root credential, legacy behavior remains. Setting
`rag.security.enabled=true` accepts the headers above and continues to support
`?apiKey=` with the existing database ADMIN/NORMAL and static-key semantics.

#### `GET /api/v1/rag/auth/me`

Returns the current principal and capabilities. The WebUI uses this endpoint to
confirm that the submitted credential is the environment root:

```json
{
  "principalType": "ENVIRONMENT_ROOT",
  "principalId": "environment-root",
  "rootMode": true,
  "capabilities": ["RAG_READ", "RAG_WRITE", "API_KEY_MANAGE"]
}
```

A database business key returns `DATABASE_API_KEY` with
`["RAG_READ", "RAG_WRITE"]`; the WebUI refuses to unlock the management
console with it. Responses include `Cache-Control: no-store`.

Database business keys expose `allowedCollectionKeys`; deprecated
`allowedCollectionIds` remains in compatibility responses:

- Omit the scope to grant unrestricted access to all collections.
- A non-empty list limits search, chat, collection, document, upload, and
  PDF-to-RAG paths to those collections.
- Explicit requests for an unknown or unauthorized key return `403`, so a
  restricted caller cannot enumerate Collections.
- Retrieval without an explicit collection filter is constrained to the
  key's allow-list.

### Collection Identity

Collections have two identifiers:

- `id` is the internal database `BIGINT` primary key and foreign key.
- `collectionKey` is the caller-supplied stable external business identifier
  and the preferred API identity.

`collectionKey` is required for create, import, and clone targets. It must
contain 1-128 visible ASCII characters (`U+0021` through `U+007E`), is
case-sensitive, and is stored exactly as supplied. The service does not trim,
normalize, change case, truncate, or generate it. It is globally unique,
immutable after creation, and remains reserved after soft deletion. Callers
without a business naming scheme can generate a UUID locally.

Because keys may contain URL-reserved punctuation, by-key endpoints use query
parameters and callers must URL-encode the value. Deprecated numeric routes
and `collectionId(s)` fields remain available. When both forms are supplied,
they must resolve to the same Collection set; ordering is ignored, but a
mismatch returns `400`.

### Collection Retrieval Scope

Chat and Search accept `collectionScopeMode`:

| Mode | Unrestricted caller | Restricted API key |
|------|---------------------|--------------------|
| `CALLER_VISIBLE` | All retrievable documents, including unassigned documents | Documents inside the key's Collection allow-list |
| `ANY_COLLECTION` | All retrievable documents with `collection_id IS NOT NULL` | Documents inside the key's Collection allow-list; never expands access |
| `SELECTED_COLLECTIONS` | The union of explicitly selected Collections | The selected Collections must be a subset of the allow-list |

Compatibility inference keeps existing clients working:

- Omit the mode and all Collection fields: infer `CALLER_VISIBLE`.
- Omit the mode and send non-empty `collectionKeys` or deprecated
  `collectionIds`: infer `SELECTED_COLLECTIONS`.
- `CALLER_VISIBLE` and `ANY_COLLECTION` reject any present Collection list.
- `SELECTED_COLLECTIONS` requires a non-empty key or ID list.
- Explicit empty Collection lists return `400`.
- At most 100 Collection identities and 1000 `documentIds` may be supplied.
- If keys and IDs are both present, they must identify the same set.
- `documentIds` and `documentType`, when used, are intersected with the
  authorized Collection scope.

Unknown unrestricted keys return `404`. For restricted callers, unknown or
unauthorized keys return `403` to avoid leaking Collection existence.
Deprecated unrestricted numeric IDs that do not exist simply match no rows.

Vector, English FTS, pg_jieba, and pg_trgm retrieval apply the effective scope
directly in PostgreSQL with `d.collection_id = ANY (?)`,
`d.collection_id IS NOT NULL`, and optional JDBC `bigint[]` document filters.
They do not expand a Collection into all of its document IDs. Multiple
Collections form one candidate union and compete for the global top-k;
per-Collection quota/coverage (`EACH_COLLECTION`) is not supported.

#### External-client best practices

1. New clients should send `collectionScopeMode` explicitly. Compatibility
   inference exists mainly for gradual migration of older clients and should
   not become an implicit business rule in new integrations.
2. Persist and transmit `collectionKey`, not the database `collectionId`.
   Numeric IDs remain only for legacy compatibility and internal diagnosis.
3. Use `SELECTED_COLLECTIONS + collectionKeys` when a user explicitly chooses
   one or more knowledge bases. Use `CALLER_VISIBLE` only when all
   caller-visible content is intended. Use `ANY_COLLECTION` when unassigned
   documents must be excluded without naming specific knowledge bases.
4. Send `collectionKeys` only with `SELECTED_COLLECTIONS`. Do not send an
   explicit empty list. Deduplicate on the client, stay within 100 keys, and
   sort stably for consistent cache keys, logs, and tests.
5. Give each production connector or business service a restricted API key
   with `allowedCollectionKeys`. The request scope expresses the current
   business intent; the API-key allow-list is an independent authorization
   ceiling.
6. Carry the same scope semantics through Chat, SSE Chat, and GET/POST Search.
   Before release, inspect direct retrieval through Search and then validate
   Chat generation so retrieval defects can be separated from LLM behavior.
7. Selected Collections share one global top-k; every Collection is not
   guaranteed to contribute a result. Do not treat the current behavior as a
   per-Collection coverage guarantee. See
   [TODO: `EACH_COLLECTION`](TODO.md#each_collection-retrieval-coverage-mode)
   for the deferred design boundary.
8. Correct invalid combinations or limits after a `400`. Treat `403` for a
   restricted caller as unauthorized without inferring whether the Collection
   exists. An unrestricted caller receives `404` for an unknown key.

### Rate Limiting

When `rag.rate-limit.enabled` is true, all API requests are subject to a sliding-window rate limit.

Two strategies are supported (`rag.rate-limit.strategy`):
- `ip`: Rate limit by client IP address
- `api-key`: Rate limit by API Key (falls back to IP if no key is provided); tiered limits use `rag.rate-limit.key-limits`

**Rate limit response headers (on normal requests):**

| Header | Description |
|--------|-------------|
| `X-RateLimit-Limit` | Max requests per minute |
| `X-RateLimit-Remaining` | Remaining quota in current window |

**Rate limit exceeded response:**

```
HTTP/1.1 429 Too Many Requests
Retry-After: 60
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
```

### Error Responses (RFC 7807 Problem Detail)

All error responses follow [RFC 7807](https://datatracker.ietf.org/doc/html/rfc7807) `application/problem+json` format:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "message content must not be blank",
  "instance": "/api/v1/rag/chat/ask"
}
```

| Field | Description |
|-------|-------------|
| `type` | Problem type URI (defaults to `about:blank`) |
| `title` | HTTP status text |
| `status` | HTTP status code |
| `detail` | Specific error description |
| `instance` | Request path where the error occurred |

**Parameter validation errors** (400) merge multiple field errors:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "message: must not be blank; sessionId: must not be blank",
  "instance": "/api/v1/rag/chat/ask"
}
```

---

## Chat — RAG Q&A

### `POST /api/v1/rag/chat/ask`

Non-streaming RAG Q&A, returns a complete answer.

**Request body:**

```json
{
  "message": "What is Spring AI?",
  "sessionId": "session-001",
  "domainId": "medical",
  "model": "openrouter/xiaomi/mimo-v2-pro",
  "collectionScopeMode": "SELECTED_COLLECTIONS",
  "collectionKeys": ["medical:guidelines:v3", "medical:drugs:v2"],
  "documentIds": [10, 20],
  "maxResults": 5,
  "useHybridSearch": true,
  "useRerank": true,
  "metadata": {}
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `message` | string | ✅ | Query content (≤10000 chars) |
| `sessionId` | string | | Session ID, maximum 36 characters; generated when omitted |
| `domainId` | string | | Domain extension ID |
| `model` | string | | Runtime model reference from `GET /rag/models`; omitted uses the default chain |
| `collectionScopeMode` | enum | | `CALLER_VISIBLE`, `ANY_COLLECTION`, or `SELECTED_COLLECTIONS` |
| `collectionKeys` | string[] | | Preferred stable Collection scope |
| `collectionIds` | long[] | | Deprecated numeric compatibility scope |
| `documentIds` | long[] | | Restrict retrieval to these documents; intersects with collections |
| `maxResults` | int | | Number of retrieval results, default 5 |
| `useHybridSearch` | boolean | | Enable vector + full-text retrieval, default true |
| `useRerank` | boolean | | Enable reranking, default true |
| `metadata` | object | | Extended metadata |

**Response:**

```json
{
  "answer": "Spring AI is an AI application framework in the Spring ecosystem...",
  "sessionId": "session-001",
  "sources": [
    {
      "documentId": 1,
      "title": "Introduction to Spring AI",
      "score": 0.92,
      "chunk": "Spring AI provides ChatClient..."
    }
  ]
}
```

---

### `POST /api/v1/rag/chat/stream`

SSE streaming Q&A, returns answer chunks progressively.

**Request body:** Same as `/ask`.

The same 36-character `sessionId` limit applies. Longer values return `400 VALIDATION_FAILED` before Chat Memory persistence.

**Response:** `text/event-stream`

```
data: {"choices":[{"delta":{"content":"Spring AI is"}}]}

data: {"choices":[{"delta":{"content":" an AI framework"}}]}

event:done
data:{"traceId":"...","status":"complete"}
```

**curl example:**

```bash
curl -N -X POST http://localhost:8081/api/v1/rag/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "What is RAG?", "sessionId": "s1", "model": "openrouter/xiaomi/mimo-v2-pro"}'
```

---

### `GET /api/v1/rag/chat/history/{sessionId}`

Query chat history for a session.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `limit` | int | 50 | Number of records to return |

**Response:** `List<Map<String, Object>>`

```json
[
  {
    "id": 1,
    "session_id": "s1",
    "user_message": "What is RAG?",
    "ai_response": "RAG is Retrieval-Augmented Generation...",
    "created_at": "2026-04-02T16:00:00Z"
  }
]
```

---

### `DELETE /api/v1/rag/chat/history/{sessionId}`

Clear chat history for a session (only affects `rag_chat_history` table, not `spring_ai_chat_memory`).

**Response:**

```json
{
  "message": "Session history cleared",
  "sessionId": "s1",
  "deletedCount": 10
}
```

---

### `GET /api/v1/rag/chat/export/{sessionId}`

Export chat conversation history as a downloadable file.

| Parameter | Type | Location | Description |
|-----------|------|---------|-------------|
| `format` | string | query | `json` or `md` (default: `json`) |
| `limit` | int | query | Max messages to export (default: 50) |

**Response:** Binary file download with `Content-Type: application/octet-stream` and `Content-Disposition: attachment; filename="conversation-{sessionId}.{format}"`

**JSON format response body:**
```json
{
  "conversationId": "s1",
  "exportedAt": "2026-04-05T12:00:00Z",
  "messageCount": 10,
  "messages": [
    { "role": "user", "content": "Hello", "timestamp": "..." },
    { "role": "assistant", "content": "Hi!", "sources": [], "timestamp": "..." }
  ]
}
```

**Markdown format response body:**
```markdown
# Conversation: s1
Exported: 2026-04-05

---

## User (2026-04-05T10:00:00)
Hello

## Assistant (2026-04-05T10:00:01)
Hi!
```

---

## API Keys — Key Management

In root mode, every endpoint in this section requires the environment root.
Root-created keys have database role `NORMAL` and product profile `FULL_RAG`:
they can read and write the RAG data plane but cannot manage keys. Without a
root credential, legacy ADMIN/NORMAL management semantics remain.

### `GET /api/v1/rag/api-keys`

List business-key metadata. Raw secrets and hashes are never returned.

**Response 200**:
```json
[{
  "keyId": "rag_k_abc123",
  "name": "Production Server",
  "role": "NORMAL",
  "allowedCollectionKeys": ["customer-42:manual:v3"],
  "allowedCollectionIds": [1, 2],
  "enabled": true,
  "createdAt": "2026-08-14T00:00:00",
  "lastUsedAt": null,
  "expiresAt": "2026-10-01T00:00:00"
}]
```

### `POST /api/v1/rag/api-keys`

In root mode, `expiresAt` is required and must be in the future. There is no
fixed maximum lifetime. `allowedCollectionKeys` is optional; omit it for all
collections. `allowedCollectionIds` is deprecated.

**Request body**:
```json
{
  "name": "My API Key",
  "expiresAt": "2026-10-01T00:00:00",
  "allowedCollectionKeys": ["customer-42:manual:v3"]
}
```

The raw secret appears only in the `201 Created` response, which includes
`Cache-Control: no-store`:

```json
{
  "keyId": "rag_k_xyz789",
  "rawKey": "rag_sk_...",
  "name": "My API Key",
  "allowedCollectionKeys": ["customer-42:manual:v3"],
  "allowedCollectionIds": [1, 2],
  "expiresAt": "2026-10-01T00:00:00"
}
```

### `POST /api/v1/rag/api-keys/{keyId}/rotate`

Disable the old key and create a same-name replacement with the same Collection
scope. The new raw secret appears only in this `201 Created` response.
Root-mode rotation preserves an existing future expiry. A legacy key without
an expiry receives a one-year expiry; expired or disabled keys are rejected.

### `DELETE /api/v1/rag/api-keys/{keyId}`

Immediately disable a business key. Success returns `204 No Content`.

The management console is available at `/webui/unlock`. It keeps the root
credential only in page memory and requires it again after refresh or sign-out.
External callers do not use the WebUI; they only need a distributed business
key:

```bash
curl "http://localhost:8081/api/v1/rag/search?query=Spring%20AI" \
  -H "Authorization: Bearer ${RAG_BUSINESS_API_KEY}"
```

---

## Search — Direct Retrieval

> Does not go through LLM generation; used for debugging and previewing retrieval results.

### `GET /api/v1/rag/search`

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `query` | string | ✅ | Search query text |
| `limit` | int | 10 | Number of results to return |
| `useHybrid` | bool | true | Use hybrid search |
| `vectorWeight` | double | 0.5 | Vector search weight |
| `fulltextWeight` | double | 0.5 | Full-text search weight |
| `collectionScopeMode` | enum | `CALLER_VISIBLE` | Explicit Collection scope mode |
| `collectionKeys` | string[] | | Preferred repeated Collection scope parameter |
| `collectionIds` | long[] | | Deprecated repeated numeric scope parameter |

**Response:**

```json
{
  "results": [
    {
      "documentId": 1,
      "title": "Introduction to Spring AI",
      "score": 0.92,
      "chunk": "Retrieved text snippet...",
      "metadata": {}
    }
  ],
  "total": 3,
  "query": "Spring AI"
}
```

---

### `POST /api/v1/rag/search`

Submit more complex retrieval configuration via request body.

**Request body:**

```json
{
  "query": "Spring AI",
  "collectionScopeMode": "SELECTED_COLLECTIONS",
  "collectionKeys": ["customer-42:manual:v3"],
  "documentIds": [1, 2, 3],
  "config": {
    "maxResults": 10,
    "useHybridSearch": true,
    "vectorWeight": 0.6,
    "fulltextWeight": 0.4,
    "minScore": 0.3
  }
}
```

---

## JSON Structured Records — JSONB Payload Retrieval

Structured-record endpoints keep two caller-supplied values separate:

- `retrievalText` is the natural-language description used for `content_hash`,
  chunking, full-text search, and embedding.
- `jsonbPayload` is the business JSON stored as PostgreSQL JSONB and returned
  after a successful scoped retrieval.

The service does not generate or validate the relationship between the two
fields. Callers use `collectionKey + externalId` as the stable external
identity; the service resolves it to the internal
`(collectionId, documentType=json-record, externalId)` identity. Deprecated
`collectionId` remains compatible. JSON records do not participate in global
content-hash deduplication, and there is no `payloadHash`.

### `POST /api/v1/rag/json-records/upsert`

Create or update one record. `embed` defaults to `true`; setting it to
`false` persists the record and leaves embedding to a later document operation.

```json
{
  "collectionKey": "customer-42:catalog:v1",
  "externalId": "product:sku-10001",
  "title": "Compact wireless keyboard",
  "retrievalText": "A compact wireless keyboard supports Bluetooth and dual-mode 2.4G connectivity.",
  "jsonbPayload": {
    "sku": "10001",
    "protocols": ["bluetooth", "2.4g"],
    "stock": 42
  },
  "source": "catalog",
  "metadata": {
    "tenant": "demo"
  },
  "embed": true
}
```

Payload-only updates create a version snapshot but do not invalidate a fresh
embedding. `retrievalText` changes invalidate the active embedding and are
re-embedded when `embed=true`.

### `POST /api/v1/rag/json-records/batch-upsert`

Accepts `{ "items": [ ... ] }`. Items are processed independently in input
order; one failed item is returned in the item result without rolling back
successful items.

### `POST /api/v1/rag/json-records/search`

Searches only JSON records in the required `collectionKeys` scope, using the
same hybrid retrieval path as ordinary search. Deprecated `collectionIds`
remains compatible. The response preserves ranking and includes the current
`collectionKey`, `retrievalText`, and `jsonbPayload` for each result.

```json
{
  "query": "wireless keyboard with Bluetooth",
  "collectionKeys": ["customer-42:catalog:v1"],
  "config": {
    "maxResults": 10,
    "useHybridSearch": true,
    "useRerank": true
  }
}
```

API-key Collection ACL is applied before retrieval. A restricted key cannot
expand the request beyond its `allowedCollectionKeys`; unknown and unauthorized
keys return `403`.

### `GET /api/v1/rag/json-records/{documentId}`

Returns the current structured record by internal document ID, including
`collectionKey`, deprecated `collectionId`, `externalId`, `retrievalText`, and
`jsonbPayload`. Upsert and search responses also return both Collection
identities. Collection export/import, clone, and document-version responses
preserve the structured fields and payload snapshots.

## External Documents — Idempotent Synchronization

These endpoints are for ordinary text documents whose source system owns the
document identity and revision. They do not fetch URLs or files. The caller
reads the source, then sends the current representation to the RAG service.

The stable identity is the pair `collectionKey + externalId`. `externalId` is
trimmed, case-sensitive, limited to 255 characters, and must remain stable for
the lifetime of the source object. `sourceRevision` is an opaque, non-empty
caller token, such as an ETag, upstream row version, commit ID, or canonical
payload hash. The service does not compare opaque revisions by ordering.

### `POST /api/v1/rag/documents/upsert`

Create or update one ordinary document. The endpoint requires a writable
Collection under the current API key's Collection ACL.

```json
{
  "collectionKey": "customer-42:manual:v3",
  "externalId": "cms:article:10001",
  "sourceRevision": "etag:8b4d9f",
  "expectedSourceRevision": "etag:7a3c21",
  "title": "Refund policy",
  "content": "The current refund policy is ...",
  "source": "cms",
  "documentType": "markdown",
  "metadata": {
    "locale": "en-US"
  },
  "embed": true
}
```

`title` and `content` are required. `documentType` defaults to `text`;
`json-record` must use the JSON structured-record API. `embed` defaults to
`true`. The request content limit is 1,000,000 characters.

The service keeps the same internal `documentId` when a source document is
updated. Content changes create a version snapshot, invalidate the old
embedding for retrieval, and synchronously rebuild the active Profile
embedding. Metadata-only or source-revision-only changes do not call the
provider when a fresh embedding already exists.

The response is HTTP 200 even when persistence succeeds but embedding fails:

```json
{
  "documentId": 42,
  "collectionKey": "customer-42:manual:v3",
  "externalId": "cms:article:10001",
  "sourceRevision": "etag:8b4d9f",
  "action": "UPDATED",
  "contentChanged": true,
  "versionNumber": 4,
  "embeddingStatus": "COMPLETED",
  "embeddingProfileKey": "bge-m3-1024",
  "embeddingFresh": true,
  "processingStatus": "COMPLETED",
  "sourceDeletedAt": null,
  "errorCode": null,
  "error": null
}
```

`action` is `CREATED`, `UPDATED`, or `UNCHANGED`. `embeddingStatus` is one of
`COMPLETED`, `CACHED`, `NOT_REQUESTED`, or `FAILED`. `NOT_REQUESTED` always
means that this request used `embed=false`; it can still report
`embeddingFresh=true` when a usable embedding already existed. `FAILED` means
the new document content was committed but is not retrievable until embedding
is retried; the old vector may remain physically stored for diagnosis but is
excluded by the freshness predicate. Replaying the same request retries
embedding when the document is still stale.

`expectedSourceRevision` is an optional compare-and-set guard. Exact replay is
checked first so a client can safely retry after losing a successful response.
The same revision with different managed fields returns `409`. A mismatching
expected revision also returns `409`. If the guard is omitted, the last
serialized writer wins, so connectors with out-of-order delivery should use
the guard.

### `POST /api/v1/rag/documents/batch-upsert`

Accepts `{ "items": [ ... ] }` with 1–50 items and a total content limit of
5,000,000 characters. Each item is processed independently and results retain
input order. One item can report `PERSISTENCE_FAILED` or `FAILED` without
rolling back successful items in the same batch.

### `GET /api/v1/rag/documents/by-external-id`

Query the current ordinary document without exposing an internal ID as the
source-system identity:

```text
GET /api/v1/rag/documents/by-external-id?collectionKey=customer-42%3Amanual%3Av3&externalId=cms%3Aarticle%3A10001
```

The response is the normal document detail shape plus `externalId`,
`sourceRevision`, `sourceDeletedAt`, processing status, and embedding
freshness. An unauthorized Collection returns `403`; a missing identity
returns `404`.

### `DELETE /api/v1/rag/documents/by-external-id`

Record a source-managed deletion without losing the stable identity:

```text
DELETE /api/v1/rag/documents/by-external-id
  ?collectionKey=customer-42%3Amanual%3Av3
  &externalId=cms%3Aarticle%3A10001
  &sourceRevision=etag%3Adeleted-9
  &expectedSourceRevision=etag%3A8b4d9f
```

This creates a tombstone (`enabled=false`, `sourceDeletedAt` set) and returns
`DELETED`. Replaying the same deletion revision returns `UNCHANGED`. A later
upsert with a new revision can restore the same internal document ID. The old
deletion revision cannot be replayed as an upsert. The legacy
`DELETE /documents/{documentId}` remains a hard-delete operation and has
different semantics.

### Recommended synchronization pattern

1. Derive a stable `externalId` from the source object's immutable identity.
2. Send the source's current opaque revision with every upsert and deletion.
3. Persist the returned revision and internal ID only for diagnostics; use the
   external identity for future calls.
4. Use `expectedSourceRevision` when delivery can be duplicated or arrive out
   of order. Treat `409` as a synchronization conflict requiring a fresh
   source read, not as a create failure.
5. Treat `embeddingFresh=false` or `embeddingStatus=FAILED` as an operational
   retry condition. Replaying the same request is safe.
6. Use a separate API key per connector and restrict it to the connector's
   Collections. Do not put the root key in an external connector.

---

## Documents — Document Management

### `POST /api/v1/rag/documents`

Create a document. Use `collectionKey` to associate it with a Collection;
deprecated `collectionId` remains available. When both are supplied, they must
identify the same active Collection.

```json
{
  "title": "Introduction to Spring AI",
  "content": "Spring AI is...",
  "source": "manual",
  "documentType": "text",
  "metadata": {},
  "collectionKey": "customer-42:manual:v3"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `title` | string | ✅ | Document title |
| `content` | string | ✅ | Document content |
| `source` | string | | Source identifier |
| `documentType` | string | | Document type |
| `metadata` | object | | Extended metadata |
| `collectionKey` | string | | Preferred stable Collection key |
| `collectionId` | long | | Deprecated numeric compatibility field |

Document detail, list, version, Collection-document, and create responses keep
numeric IDs for compatibility and add `collectionKey` wherever a Collection
identity is returned.

---

### `GET /api/v1/rag/documents`

Paginated document query.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `offset` | int | 0 | Number of documents to skip |
| `limit` | int | 20 | Maximum number of documents to return |
| `title` | string | | Optional title filter |
| `documentType` | string | | Optional document-type filter |
| `processingStatus` | string | | Optional processing-status filter |
| `enabled` | boolean | | Optional enabled-state filter |
| `collectionId` | long | | Deprecated Collection ID filter |
| `collectionKey` | string | | Preferred stable Collection key filter |
| `createdAfter` | timestamp | | Lower bound for `createdAt` |
| `createdBefore` | timestamp | | Upper bound for `createdAt` |

---

### `GET /api/v1/rag/documents/{id}`

Get a single document by ID.

---

### `DELETE /api/v1/rag/documents/{id}`

Delete a document.

---

### `GET /api/v1/rag/documents/stats`

Get document statistics (total count, embedded count, etc.).

---

### `POST /api/v1/rag/documents/{id}/embed`

Generate embedding vectors for a specified document.

---

---

### `POST /api/v1/rag/documents/batch`

Batch create documents. Set the top-level `collectionKey` as the default
Collection for all items; an item may provide its own key. An item-level
identity overrides the default after normal ID/key consistency and ACL checks.
Set `embed=true` to embed in the same request.

```json
{
  "collectionKey": "customer-42:manual:v3",
  "embed": true,
  "force": false,
  "documents": [
    { "title": "doc1", "content": "content 1" },
    {
      "title": "doc2",
      "content": "content 2",
      "collectionKey": "customer-42:faq:v1"
    }
  ]
}
```

Deprecated top-level and item-level `collectionId` fields remain compatible.
`POST /documents/batch/create-and-embed` is deprecated; use this endpoint with
`embed=true`.

---

### `DELETE /api/v1/rag/documents/batch`

Batch delete documents.

```json
{
  "documentIds": [1, 2, 3]
}
```

---

### `POST /api/v1/rag/documents/batch/embed`

Batch embed documents (documents must already exist).

```json
{
  "documentIds": [1, 2, 3]
}
```

### `POST /api/v1/rag/documents/batch/embed/stream`

Batch embed documents via SSE streaming with real-time progress events.

**SSE Events:**
- `progress` — `BatchEmbedProgressEvent` with current document index, total docs, phase (PREPARING/CHUNKING/EMBEDDING/STORING/COMPLETED/FAILED), success/cached/failed counts
- `done` — Final confirmation with total count and status
- `error` — Error details (on validation failure)

**Request body:**
```json
{
  "ids": [1, 2, 3]
}
```

**Example SSE progress event:**
```json
{
  "currentDocIndex": 2,
  "totalDocs": 10,
  "currentDocId": 42,
  "phase": "EMBEDDING",
  "current": 5,
  "total": 10,
  "message": "Document 3/10: Generating embedding for chunk 5/10",
  "successCount": 1,
  "failedCount": 0,
  "cachedCount": 1
}
```

---

### `POST /api/v1/rag/documents/upload`

Upload text files and embed in one step. Suitable for direct file submission from frontend.

**Content-Type:** `multipart/form-data`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `files` | MultipartFile[] | ✅ | File list (max 100) |
| `collectionKey` | string | No | Preferred target Collection key |
| `collectionId` | long | No | Deprecated numeric Collection ID |
| `force` | boolean | No | `true` = force re-embed |

**Supported file types:** txt / md / json / xml / html / csv / log

**Response:**

```json
{
  "processed": 10,
  "success": 10,
  "failed": 0,
  "results": [
    {
      "filename": "Product Manual.txt",
      "documentId": 1,
      "title": "Product Manual",
      "embedded": true,
      "chunks": 5,
      "error": null
    }
  ]
}
```

---

### PDF-to-RAG Collection Scope

The following multipart endpoints prefer `collectionKey`; deprecated
`collectionId` remains compatible:

- `POST /api/v1/rag/files/pdf-to-rag`
- `POST /api/v1/rag/files/{uuid}/embed`

`embed=false` on `/pdf-to-rag` returns JSON immediately. `embed=true`, or
omitting `embed`, returns an SSE progress stream. For `/{uuid}/embed`,
`embed=sync` returns JSON and `embed=sse`, or omission, returns SSE. If both
Collection identifiers are supplied, they must match.

The `collection` parameter on `POST /api/v1/rag/files/pdf` is unrelated: it is
only a virtual file-directory prefix and is not a RAG Collection identity.

---

### `GET /api/v1/rag/documents/{id}/versions`

Get document version history (recorded automatically when content_hash changes, newest first).

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `page` | int | 0 | Page number |
| `size` | int | 20 | Page size |

**Response:**

```json
{
  "documentId": 1,
  "totalVersions": 5,
  "page": 0,
  "size": 20,
  "versions": [
    {
      "versionNumber": 5,
      "contentHash": "a1b2c3...",
      "title": "Introduction to Spring AI",
      "size": 2048,
      "changeType": "CONTENT_CHANGED",
      "createdAt": "2026-04-03T00:30:00Z"
    }
  ]
}
```

---

### `GET /api/v1/rag/documents/{id}/versions/{versionNumber}`

Get a specific version of a document (includes content snapshot).

**Response:**

```json
{
  "versionNumber": 3,
  "contentHash": "d4e5f6...",
  "title": "Introduction to Spring AI",
  "content": "Full content of version 3...",
  "size": 1024,
  "changeType": "INITIAL",
  "createdAt": "2026-04-02T10:00:00Z"
}
```

---

## Collections — Knowledge Base Management

### `POST /api/v1/rag/collections`

Create a Collection. `collectionKey` is required and follows the identity
contract above. Duplicate keys, including keys retained by soft-deleted
Collections, return `409`.

```json
{
  "collectionKey": "medical-knowledge-base",
  "name": "Medical Knowledge Base",
  "description": "Medical domain document collection",
  "embeddingModel": "BAAI/bge-m3",
  "dimensions": 1024,
  "enabled": true,
  "metadata": {}
}
```

The response contains both internal `id` and external `collectionKey`.

---

### `GET /api/v1/rag/collections`

Paginated collection query.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `offset` | int | 0 | Number of collections to skip |
| `limit` | int | 20 | Maximum number of collections to return |
| `name` | string | | Optional collection-name filter |
| `query` | string | | Case-insensitive substring match on name or exact stored key text |
| `enabled` | boolean | | Optional enabled-state filter |

Restricted API keys see only their allowed Collections.

---

### By-Key Lifecycle Routes

These are the preferred lifecycle endpoints. URL-encode `collectionKey` as a
query parameter:

- `GET /api/v1/rag/collections/by-key?collectionKey=...`
- `PUT /api/v1/rag/collections/by-key?collectionKey=...`
- `DELETE /api/v1/rag/collections/by-key?collectionKey=...`
- `POST /api/v1/rag/collections/by-key/restore?collectionKey=...`
- `GET /api/v1/rag/collections/by-key/documents?collectionKey=...`
- `POST /api/v1/rag/collections/by-key/documents?collectionKey=...`
- `GET /api/v1/rag/collections/by-key/export?collectionKey=...`

The update body contains only mutable fields: `name`, `description`,
`embeddingModel`, `dimensions`, `enabled`, and `metadata`. Supplying
`collectionKey` in an update body returns `400`; renaming a key is not
supported.

Delete is a soft delete and unlinks legacy documents without deleting documents
or embeddings. A Collection containing any document with a nonblank
`externalId` returns `409`, because unlinking would destroy the
`collectionKey + externalId` identity. Explicitly hard-delete or migrate those
external-managed documents before deleting the Collection. The key remains
reserved. Restore preserves the same key and does not re-link legacy documents
automatically.

---

### `POST /api/v1/rag/collections/clone`

Clone a Collection using stable source and target keys:

```json
{
  "sourceCollectionKey": "customer-42:manual:v3",
  "collectionKey": "customer-42:manual:v4"
}
```

The target key is required and must be unused. Documents are copied with
pending processing status; embeddings are not copied. Restricted API keys
cannot create, import, or clone Collections.

---

### Collection Documents

List documents with:

```http
GET /api/v1/rag/collections/by-key/documents?collectionKey=customer-42%3Amanual%3Av3
```

Associate one existing document with:

```json
{
  "documentId": 1
}
```

sent to:

```http
POST /api/v1/rag/collections/by-key/documents?collectionKey=customer-42%3Amanual%3Av3
```

---

### Collection Export and Import

Export through:

```http
GET /api/v1/rag/collections/by-key/export?collectionKey=customer-42%3Amanual%3Av3
```

**Response:**

```json
{
  "collectionKey": "customer-42:manual:v3",
  "name": "Medical Knowledge Base",
  "description": "Medical domain document collection",
  "embeddingModel": "BAAI/bge-m3",
  "dimensions": 1024,
  "enabled": true,
  "metadata": {},
  "documents": [
    {
      "title": "doc1",
      "content": "...",
      "source": "manual",
      "documentType": "text",
      "metadata": {},
      "size": 1024
    }
  ],
  "exportedAt": "2026-04-03T00:00:00Z",
  "documentCount": 10
}
```

---

### `POST /api/v1/rag/collections/import`

Import and create a new Collection with documents from exported JSON data.
`collectionKey` is required. Because export preserves the source key and keys
are globally unique, change it before importing as a second Collection.

**Request body:** Use the JSON data returned by `/export`, with the target
`collectionKey`.

**Response:**

```json
{
  "id": 5,
  "collectionKey": "customer-42:manual:import-2026-08",
  "name": "Medical Knowledge Base",
  "importedDocuments": 10,
  "documentCount": 10
}
```

---

### Deprecated Numeric Collection Routes

The following compatibility routes remain available but are deprecated in
OpenAPI:

- `GET`, `PUT`, and `DELETE /api/v1/rag/collections/{id}`
- `POST /api/v1/rag/collections/{id}/restore`
- `POST /api/v1/rag/collections/{id}/clone?collectionKey=...`
- `GET /api/v1/rag/collections/{id}/documents`
- `POST /api/v1/rag/collections/{id}/documents`
- `GET /api/v1/rag/collections/{id}/export`

They have the same ACL, immutability, soft-delete, and target-key rules as the
preferred routes.

---

## Evaluation — Retrieval Evaluation

### `POST /api/v1/rag/evaluation/evaluate`

Execute a single retrieval evaluation.

---

### `POST /api/v1/rag/evaluation/batch`

Execute batch evaluations.

---

### `GET /api/v1/rag/evaluation/metrics/calculate`

Calculate retrieval metrics (Precision, Recall, MRR, etc.).

---

### `GET /api/v1/rag/evaluation/report`

Get evaluation report.

---

### `GET /api/v1/rag/evaluation/history`

Get evaluation history.

---

### `GET /api/v1/rag/evaluation/metrics/aggregated`

Get aggregated metrics.

---

### `POST /api/v1/rag/evaluation/feedback`

Submit user feedback.

```json
{
  "query": "What is RAG?",
  "feedbackType": "helpful",
  "comment": "Accurate answer",
  "rating": 5
}
```

---

### `GET /api/v1/rag/evaluation/feedback/stats`

Get feedback statistics.

---

### `GET /api/v1/rag/evaluation/feedback/history`

Get feedback history.

---

### `GET /api/v1/rag/evaluation/feedback/type/{feedbackType}`

Query feedback by type.

---

## A/B Tests — Experiment Management

### `POST /api/v1/rag/ab/experiments`

Create an A/B experiment.

### `PUT /api/v1/rag/ab/experiments/{id}`

Update an experiment.

### `POST /api/v1/rag/ab/experiments/{id}/start`

Start an experiment.

### `POST /api/v1/rag/ab/experiments/{id}/pause`

Pause an experiment.

### `POST /api/v1/rag/ab/experiments/{id}/stop`

Stop an experiment.

### `GET /api/v1/rag/ab/experiments/running`

Get running experiments.

### `GET /api/v1/rag/ab/experiments/{id}/variant`

Get experiment variant assignment.

### `POST /api/v1/rag/ab/experiments/{id}/results`

Record experiment results.

### `GET /api/v1/rag/ab/experiments/{id}/analysis`

Get experiment analysis report.

### `GET /api/v1/rag/ab/experiments/{id}/results`

Get experiment results list.

---

## Alerts — Monitoring & Alerting

### `GET /api/v1/rag/alerts/active`

Get active alerts.

### `GET /api/v1/rag/alerts/history`

Get alert history.

### `GET /api/v1/rag/alerts/stats`

Get alert statistics.

### `POST /api/v1/rag/alerts/{alertId}/resolve`

Resolve an alert.

### `POST /api/v1/rag/alerts/silence`

Silence an alert.

### `POST /api/v1/rag/alerts/fire`

Manually trigger an alert (for testing).

### `GET /api/v1/rag/alerts/slos`

Get all SLO definitions.

### `GET /api/v1/rag/alerts/slos/{sloName}`

Get details of a specific SLO.

### `POST /api/v1/rag/alerts/slos`

Create a new SLO configuration.

**Request body:**
```json
{
  "sloName": "latency_p99",
  "sloType": "LATENCY",
  "targetValue": 200.0,
  "unit": "ms",
  "description": "P99 latency should be under 200ms",
  "enabled": true
}
```

**Response:** `201 Created` with created SLO config.

### `PUT /api/v1/rag/alerts/slos/configs/{sloName}`

Update an existing SLO configuration.

**Request body:** Same as POST.

**Response:** `200 OK` with updated SLO config, or `404 Not Found`.

### `DELETE /api/v1/rag/alerts/slos/configs/{sloName}`

Delete an SLO configuration.

**Response:** `204 No Content`, or `404 Not Found`.

### `GET /api/v1/rag/alerts/slos/configs`

List all SLO configurations.

**Response:** Array of SLO config objects.

### `GET /api/v1/rag/alerts/silence-schedules`

List all silence schedules.

### `POST /api/v1/rag/alerts/silence-schedules`

Create a new silence schedule.

**Request body:**
```json
{
  "name": "weekend-maintenance",
  "alertKey": "high-latency",
  "silenceType": "RECURRING",
  "startTime": "2026-04-10T02:00:00+08:00",
  "endTime": "2026-04-10T04:00:00+08:00",
  "description": "Scheduled maintenance window",
  "enabled": true
}
```

**Response:** `201 Created` with created schedule.

### `GET /api/v1/rag/alerts/silence-schedules/{name}`

Get a specific silence schedule.

### `PUT /api/v1/rag/alerts/silence-schedules/{name}`

Update a silence schedule.

### `DELETE /api/v1/rag/alerts/silence-schedules/{name}`

Delete a silence schedule.

**Response:** `204 No Content`.

---

## Health — Health Checks

### `GET /api/v1/rag/health`

Service health check.

**Response:**

```json
{
  "status": "UP",
  "timestamp": "2026-08-15T16:00:00Z",
  "components": {
    "database": "UP",
    "pgvector": "UP",
    "tables": "UP",
    "cache": "UP"
  }
}
```

The detailed component endpoint is `GET /api/v1/rag/health/components`. It
returns the same component names with latency, extension, table-count, and
cache details.

---

## Cache — Cache Monitoring

### `GET /api/v1/rag/cache/stats`

Get embedding cache statistics.

**Response:**

```json
{
  "hitCount": 1523,
  "missCount": 478,
  "hitRate": 0.761,
  "totalRequests": 2001,
  "cacheSize": 342,
  "timestamp": "2026-04-03T10:00:00Z"
}
```

### `DELETE /api/v1/rag/cache/invalidate`

Admin endpoint: clear the embedding Caffeine cache, forcing subsequent embedding requests to call the remote API again.

**Response:**

```json
{
  "cleared": 42,
  "message": "Cache invalidated"
}
```

**Field descriptions:**

| Field | Type | Description |
|-------|------|-------------|
| `cleared` | int | Number of cache entries cleared |
| `message` | string | Human-readable status |

---

## Metrics — RAG Metrics Monitoring

### `GET /api/v1/rag/metrics`

Get RAG service key metrics summary (request count, success rate, retrieval result count, token consumption).

**Response:**

```json
{
  "totalRequests": 1523,
  "successfulRequests": 1498,
  "failedRequests": 25,
  "successRate": 0.984,
  "totalRetrievalResults": 8764,
  "totalLlmTokens": 245321
}
```

**Field descriptions:**

| Field | Type | Description |
|-------|------|-------------|
| `totalRequests` | long | Total requests since service startup |
| `successfulRequests` | long | Successful requests (LLM returned normally) |
| `failedRequests` | long | Failed requests (LLM call exception) |
| `successRate` | double | Success rate (successful/total) |
| `totalRetrievalResults` | long | Cumulative retrieval result count |
| `totalLlmTokens` | long | Cumulative LLM token consumption |

---

### `GET /api/v1/rag/metrics/slow-queries`

Get slow query statistics from the database connection pool (HikariCP). Returns aggregated stats and recent slow query records.

Requires `rag.slow-query.enabled = true`.

**Response:**

```json
{
  "totalQueryCount": 15234,
  "totalQueryDurationMs": 4567890,
  "slowQueryCount": 12,
  "thresholdMs": 1000,
  "averageQueryDurationMs": 300,
  "recentSlowQueries": [
    {
      "timestampMs": 1712123456789,
      "sql": "SELECT * FROM rag_embeddings WHERE ...",
      "durationMs": 2345
    }
  ]
}
```

**Field descriptions:**

| Field | Type | Description |
|-------|------|-------------|
| `totalQueryCount` | long | Total query count since service startup |
| `totalQueryDurationMs` | long | Total query duration in ms |
| `slowQueryCount` | long | Number of queries exceeding threshold |
| `thresholdMs` | long | Configured slow query threshold in ms |
| `averageQueryDurationMs` | long | Average query duration in ms |
| `recentSlowQueries` | array | Last N slow query records (configurable via `rag.slow-query.keep-count`) |

---

### `GET /api/v1/rag/metrics/slo`

Get API SLO compliance metrics per endpoint using a sliding time window. Tracks p50/p95/p99 latency and compliance percentage against per-endpoint thresholds.

Requires `rag.slo.enabled = true`.

**Response:**

```json
{
  "enabled": true,
  "windowSeconds": 300,
  "endpoints": [
    {
      "endpoint": "rag.search.post",
      "method": "POST",
      "thresholdMs": 500,
      "compliancePercent": 98.5,
      "requestCount": 1523,
      "sloCount": 1500,
      "breachCount": 23,
      "stats": {
        "p50Ms": 45.2,
        "p95Ms": 380.5,
        "p99Ms": 490.0,
        "minMs": 12.3,
        "maxMs": 1200.5,
        "avgMs": 87.4
      }
    }
  ]
}
```

**Field descriptions:**

| Field | Type | Description |
|-------|------|-------------|
| `enabled` | boolean | Whether SLO tracking is active |
| `windowSeconds` | int | Sliding time window size in seconds |
| `endpoints[].endpoint` | string | Endpoint identifier (e.g., `rag.search.post`) |
| `endpoints[].thresholdMs` | long | SLO threshold in milliseconds |
| `endpoints[].compliancePercent` | double | Percentage of requests within SLO (0–100) |
| `endpoints[].requestCount` | int | Total requests in the window |
| `endpoints[].sloCount` | int | Requests meeting SLO |
| `endpoints[].breachCount` | int | Requests breaching SLO |

---

## Models — Runtime Model Selection

### `GET /api/v1/rag/models`

Get provider/model references that can be sent in `ChatRequest.model`.

**Response:**

```json
{
  "multiModelEnabled": true,
  "defaultProvider": "minimax",
  "defaultModel": "minimax/MiniMax-M2.7",
  "availableProviders": ["openrouter", "minimax"],
  "fallbackChain": ["openrouter/xiaomi/mimo-v2-pro"],
  "models": [
    {
      "ref": "openrouter/xiaomi/mimo-v2-pro",
      "provider": "openrouter",
      "providerName": "OpenRouter",
      "modelId": "xiaomi/mimo-v2-pro",
      "name": "MiMo V2 Pro",
      "apiType": "openai-completions",
      "available": true,
      "reasoning": false,
      "contextWindow": 600000,
      "maxTokens": 32000,
      "source": "configured"
    },
    {
      "ref": "minimax/MiniMax-M2.7",
      "provider": "minimax",
      "providerName": "MiniMax",
      "modelId": "MiniMax-M2.7",
      "name": "MiniMax M2.7",
      "apiType": "anthropic-messages",
      "available": true,
      "reasoning": false,
      "contextWindow": 200000,
      "maxTokens": 8192,
      "source": "configured"
    }
  ]
}
```

Models that are configured but missing credentials remain in the list with
`available: false` and an `unavailableReason`.

### `GET /api/v1/rag/models/{provider}`

Get a provider summary and its model-level entries.

**Path parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `provider` | string | Provider identifier such as `openrouter` or `minimax` |

**Response (provider exists):**

```json
{
  "available": true,
  "details": {
    "provider": "openrouter",
    "available": true,
    "displayName": "OpenRouter",
    "models": [
      {
        "ref": "openrouter/xiaomi/mimo-v2-pro",
        "modelId": "xiaomi/mimo-v2-pro",
        "available": true
      }
    ]
  }
}
```

**Response (provider not found):** `404 Not Found`

### `POST /api/v1/rag/models/compare`

Compare responses from multiple model references in parallel.

**Request body:**

```json
{
  "query": "Explain the basic principles of quantum computing",
  "providers": [
    "openrouter/xiaomi/mimo-v2-pro",
    "minimax/MiniMax-M2.7"
  ],
  "timeoutSeconds": 30
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `query` | string | ✅ | Query text |
| `providers` | string[] | ✅ | Provider or `provider/model` references to compare |
| `timeoutSeconds` | int | ❌ | Per-model timeout in seconds (default 30) |

**Response:**

```json
{
  "query": "Explain the basic principles of quantum computing",
  "providers": [
    "openrouter/xiaomi/mimo-v2-pro",
    "minimax/MiniMax-M2.7"
  ],
  "results": [
    {
      "modelName": "openrouter/xiaomi/mimo-v2-pro",
      "success": true,
      "response": "Quantum computing is...",
      "latencyMs": 1200,
      "promptTokens": 50,
      "completionTokens": 180,
      "totalTokens": 230,
      "error": null
    },
    {
      "modelName": "minimax/MiniMax-M2.7",
      "success": true,
      "response": "The core of quantum computing is...",
      "latencyMs": 950,
      "promptTokens": 50,
      "completionTokens": 165,
      "totalTokens": 215,
      "error": null
    }
  ]
}
```

### `GET /api/v1/rag/metrics/models`

Get per-model invocation metrics (call count, error rate).

**Response:**

```json
{
  "multiModelEnabled": true,
  "models": [
    {
      "provider": "openai",
      "calls": 1523,
      "errors": 25,
      "errorRate": 0.016,
      "displayName": "OpenAI (DeepSeek/Compatible)"
    },
    {
      "provider": "minimax",
      "calls": 234,
      "errors": 3,
      "errorRate": 0.013,
      "displayName": "MiniMax"
    }
  ]
}
```

---

## Client Errors — WebUI Error Reporting

### `POST /api/v1/rag/client-errors`

Receive and record client-side errors from the WebUI for server-side aggregation and analysis. Used by the WebUI ErrorBoundary component.

**Request body:**

```json
{
  "errorType": "Error",
  "errorMessage": "Cannot read properties of undefined",
  "stackTrace": "TypeError: Cannot read properties of undefined\n    at Chat.render (Chat.tsx:42:10)",
  "componentStack": "at Chat (Chat.tsx:38)\nat App (App.tsx:12)",
  "pageUrl": "/webui/chat",
  "sessionId": "sess-abc123",
  "userId": null
}
```

**Field descriptions:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `errorType` | string | Yes | Error type (e.g., `Error`, `TypeError`, `ReferenceError`) |
| `errorMessage` | string | Yes | Error message text |
| `stackTrace` | string | No | JavaScript stack trace (max 8192 chars) |
| `componentStack` | string | No | React component stack trace (max 4096 chars) |
| `pageUrl` | string | No | Page URL where error occurred (max 512 chars) |
| `sessionId` | string | No | WebUI session identifier (max 64 chars) |
| `userId` | string | No | Authenticated user ID (max 64 chars) |

**Response:** `202 Accepted` (empty body)

---

### `GET /api/v1/rag/client-errors/count`

Get the total number of recorded client-side errors.

**Response:**

```json
{
  "count": 42
}
```

**Response fields:**

| Field | Type | Description |
|-------|------|-------------|
| `count` | integer | Total number of recorded client errors |
