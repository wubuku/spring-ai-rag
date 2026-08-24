# REST API Reference

📖 [English](rest-api.md) · 📖 [中文](rest-api-zh-CN.md)

> Swagger UI available at `/swagger-ui.html` after startup.
>
> Main base path: `/api/v1/rag`. The disabled-by-default OpenAI compatibility
> preview uses `/v1`.

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

`rag.rate-limit.backend=local` retains the process-local fixed-minute window and
the `ip`, `api-key`, or `user` strategy. It is suitable for one instance only.

`backend=postgresql` requires `strategy=principal`. It counts an authenticated
stable database principal in a PostgreSQL UTC-minute bucket shared by all
instances. A principal-level `requestsPerMinute` policy overrides the global
default, and credential rotation does not reset the bucket. This backend never
uses a raw credential or IP fallback. A quota-store failure returns `503`
(`RATE_LIMIT_STORE_UNAVAILABLE`, or the OpenAI error envelope under `/v1`) and
does not silently fall back to local counters.

**Rate limit response headers (on normal requests):**

| Header | Description |
|--------|-------------|
| `X-RateLimit-Limit` | Max requests per minute |
| `X-RateLimit-Remaining` | Remaining quota in current window |

**Rate limit exceeded response:**

```
HTTP/1.1 429 Too Many Requests
Retry-After: 17
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

## OpenAI Chat Completions Compatibility Preview

Set `RAG_OPENAI_COMPATIBILITY_ENABLED=true` to register:

- `GET /v1/models`
- `GET /v1/models/{id}`
- `POST /v1/chat/completions`

A model ID is a configured RAG alias such as `rag-default`, representing Chat
mode, memory policy, and a backend candidate chain. An alias never stores a
Collection. Each request supplies retrieval scope through `rag.scope` or
repeated `X-RAG-Collection-Key` headers, then the server intersects that scope
with the current API-key ACL.

```json
{
  "model": "rag-default",
  "messages": [
    {"role": "system", "content": "Ground answers in the knowledge base."},
    {"role": "user", "content": "Find the visual tone guidelines"}
  ],
  "stream": false,
  "rag": {
    "scope": {
      "mode": "SELECTED_COLLECTIONS",
      "collection_keys": ["brand:guides"]
    },
    "document_ids": [42]
  }
}
```

Collection headers are repeated and are never comma-joined:

```bash
curl http://localhost:8081/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${RAG_BUSINESS_API_KEY}" \
  -H 'X-RAG-Collection-Key: brand:guides' \
  -H 'X-RAG-Collection-Key: brand:faq' \
  -d '{"model":"rag-default","messages":[{"role":"user","content":"Find the visual tone guidelines"}]}'
```

When body and headers are both present, they must identify the same Collection
key set. Omitting both uses `CALLER_VISIBLE`; setting
`RAG_OPENAI_REQUIRE_EXPLICIT_SCOPE=true` makes omission a `400`.

Current compatibility subset:

- text-only `system`, `developer`, `user`, and `assistant` messages;
- string content or content parts containing only `{type:"text", text:"..."}`;
- `n=1`, with both `stream=false` and `stream=true`;
- `temperature`, `top_p`, token limits, tools/functions, logprobs, structured
  output, and `stream_options` are not supported and return explicit OpenAI
  error envelopes instead of being ignored;
- unknown aliases return `404 model_not_found`.

Non-streaming requests return `chat.completion`. Streaming returns standard
`data: <chunk>` records followed by `data: [DONE]`; project-specific tool and
source events are not exposed on this protocol. Authentication, rate-limit,
and runtime errors use the same
`{"error":{"message","type","param","code"}}` envelope.

This endpoint is disabled by default and is a controlled-network preview. See
[OpenAI compatibility readiness](openai-compatibility-readiness.md) for public
and multi-instance production boundaries.

## Chat — RAG Q&A

### `POST /api/v1/rag/chat/ask`

Non-streaming Chat, returning one complete answer. The same endpoint supports
three explicit execution modes:

| Mode | Behavior |
|---|---|
| `KNOWLEDGE` | Always runs Spring AI Modular RAG through the project hybrid retriever; default when omitted |
| `AGENT` | Lets a Tool Calling-capable model invoke the authorized `searchKnowledge` tool as needed |
| `PLAIN` | Model + conversation memory only; no knowledge retrieval |

**Request body:**

```json
{
  "message": "What is Spring AI?",
  "sessionId": "session-001",
  "mode": "KNOWLEDGE",
  "domainId": "medical",
  "model": "openrouter/xiaomi/mimo-v2-pro",
  "collectionScopeMode": "SELECTED_COLLECTIONS",
  "collectionKeys": ["medical:guidelines:v3", "medical:drugs:v2"],
  "documentIds": [10, 20],
  "maxResults": 5,
  "useHybridSearch": true,
  "useRerank": true,
  "filters": {
    "metadataContains": { "source": "manual" }
  },
  "metadata": {}
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `message` | string | ✅ | Query content (≤10000 chars) |
| `sessionId` | string | | Session ID, maximum 36 characters; generated when omitted |
| `mode` | enum | | `KNOWLEDGE`, `AGENT`, or `PLAIN`; default `KNOWLEDGE` |
| `domainId` | string | | Domain extension ID |
| `model` | string | | Runtime model reference from `GET /rag/models`; omitted uses the default chain |
| `collectionScopeMode` | enum | | `CALLER_VISIBLE`, `ANY_COLLECTION`, or `SELECTED_COLLECTIONS` |
| `collectionKeys` | string[] | | Preferred stable Collection scope |
| `collectionIds` | long[] | | Deprecated numeric compatibility scope |
| `documentIds` | long[] | | Restrict retrieval to these documents; intersects with collections |
| `maxResults` | int | | Number of retrieval results, default 5 |
| `useHybridSearch` | boolean | | Enable vector + full-text retrieval, default true |
| `useRerank` | boolean | | Enable reranking, default true |
| `filters` | object | | Optional JSONB containment; `PLAIN` rejects it with `400` |
| `metadata` | object | | Extended metadata. When enabled, includes protocol-level `citationValidation` |

`citationValidation` only parses the agreed `[S1]` tokens. It is not a coverage
score.

`maxResults`, `useHybridSearch`, and `useRerank` are effective execution
overrides for `KNOWLEDGE` and `AGENT`. `PLAIN` rejects these fields and any
Collection/document retrieval scope when explicitly supplied.

`AGENT` requires a model whose registry entry has
`capabilities.toolCalling=true` and whose Spring AI adapter exposes tool
options. An explicitly selected incompatible model returns
`MODEL_CAPABILITY_UNSUPPORTED`; default routing skips incompatible candidates.

`domainId` selects an extension only by explicit ID; an unknown ID returns
`UNKNOWN_DOMAIN`. Domain prompts do not inject retrieval context. A legacy
template containing `{context}` remains compatible with `KNOWLEDGE`, but
`AGENT` or `PLAIN` returns `DOMAIN_MODE_UNSUPPORTED` unless the extension
implements `getSystemPromptTemplate(ChatMode)` with safe instructions for that
mode.

**Response:**

```json
{
  "answer": "Spring AI is an AI application framework in the Spring ecosystem...",
  "traceId": "a1b2c3",
  "sessionId": "session-001",
  "mode": "KNOWLEDGE",
  "requestedModel": "openrouter/xiaomi/mimo-v2-pro",
  "resolvedModel": "openrouter/xiaomi/mimo-v2-pro",
  "usage": {
    "promptTokens": 120,
    "completionTokens": 36,
    "totalTokens": 156
  },
  "finishReason": "STOP",
  "sources": [
    {
      "citationId": "S1",
      "documentId": "1",
      "chunkIndex": 0,
      "title": "Introduction to Spring AI",
      "score": 0.92,
      "chunkText": "Spring AI provides ChatClient...",
      "collectionKey": "spring-ai:docs",
      "documentType": "PDF"
    }
  ],
  "metadata": {
    "sessionId": "session-001",
    "retrievalExecuted": true,
    "retrieval": {
      "retrievalCalls": 3,
      "toolRounds": 0,
      "sourceCount": 1,
      "documentJoin": {
        "inputDocuments": 9,
        "uniqueDocuments": 6,
        "duplicateDocumentsRemoved": 3,
        "scoreReplacements": 2
      }
    }
  },
  "stepMetrics": []
}
```

Source scores are ranking signals for the current query/configuration, not
probabilities or percentages. `PLAIN` returns an empty source list.
`metadata.retrievalExecuted` is based on actual retrieval attempts: it is
always `false` for `PLAIN`, always `true` for a completed `KNOWLEDGE` pipeline,
and may be `false` for `AGENT` when the model answers without calling
`searchKnowledge`.

For `KNOWLEDGE`, `metadata.retrieval.documentJoin` is an additive,
low-cardinality summary of the project join step before reranking. Its four
fields are nonnegative integers. It does not contain query text, document IDs,
document content, metadata values, or model output. The field is absent from
AGENT, PLAIN, direct Search, Evaluation, and legacy-advisor execution.

---

### `POST /api/v1/rag/chat/stream`

SSE streaming Q&A, returns answer chunks progressively.

**Request body:** Same as `/ask`.

The same 36-character `sessionId` limit applies. Longer values return `400 VALIDATION_FAILED` before Chat Memory persistence.

**Response:** `text/event-stream` with `content`, `tool_start`, `tool_result`,
`sources`, `done`, and `error` events. `done` and `error` are mutually
exclusive. See [SSE-PROTOCOL.md](SSE-PROTOCOL.md) for payloads, ordering,
heartbeat, cancellation, and fallback semantics.

**curl example:**

```bash
curl -N -X POST http://localhost:8081/api/v1/rag/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"What is RAG?","sessionId":"s1","mode":"KNOWLEDGE","model":"openrouter/xiaomi/mimo-v2-pro"}'
```

### Durable Chat turn idempotency

`POST /api/v1/rag/chat/ask`, `POST /api/v1/rag/chat`, and
`POST /api/v1/rag/chat/stream` accept one optional `Idempotency-Key` header.
The value is principal-scoped and is stored only as a SHA-256 hash. A keyed
request receives an opaque UUID `turnId` in the `X-RAG-Turn-Id` response
header; successful JSON responses also include the same value in
`ChatResponse.turnId` and `metadata.turnId`.

The first successful request persists the provider-independent business
response, history row, and server Memory update in one PostgreSQL transaction.
Repeating the same key with the same canonical request replays the immutable
response without invoking the provider or adding another history row. The
`X-RAG-Idempotent-Replay` header is `false` on the first response and `true` on
replay. Native SSE adds the same `turnId` and `idempotentReplay` fields to its
`done` event; a replay emits the complete answer/sources/done snapshot and does
not reproduce the original tool-event timing.

The same key with a different request returns `409 IDEMPOTENCY_KEY_REUSED`.
When another request currently owns the operation lease, the endpoint returns
`409 IDEMPOTENCY_OPERATION_IN_PROGRESS` with a bounded `Retry-After` header.
Malformed or repeated `Idempotency-Key` headers return
`400 IDEMPOTENCY_KEY_INVALID`. If durable coordination is unavailable or
disabled, a keyed request fails closed with `503 IDEMPOTENCY_DISABLED` rather
than falling back to a non-idempotent execution path. A key can be reused
after its terminal operation has been removed by the bounded retention
cleanup.

### `GET /api/v1/rag/chat/turns/{turnId}`

Returns the current principal-scoped status of an opaque Chat turn. It never
accepts an idempotency key or key hash.

```json
{
  "turnId": "opaque-uuid",
  "sessionId": "session-001",
  "status": "SUCCEEDED",
  "transport": "NATIVE_SSE",
  "createdAt": "2026-08-22T15:00:00Z",
  "updatedAt": "2026-08-22T15:00:01Z",
  "completedAt": "2026-08-22T15:00:01Z",
  "replayAvailable": true,
  "response": {
    "answer": "..."
  }
}
```

`includeResponse=false` (the default) returns status and a current
authorization-aware `replayAvailable` value without returning the response
snapshot. `includeResponse=true` returns the snapshot only when the current
principal can still read every cited source; otherwise it returns `403`.
Unknown, expired, or cross-principal turn IDs return `404 CHAT_TURN_NOT_FOUND`.

---

### `GET /api/v1/rag/chat/history/{sessionId}`

Query chat history for the authenticated principal's session, newest first.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `limit` | int | 50 | Number of records to return; clamped to 1–500 |

**Response:** `ChatHistoryResponse[]`

```json
[
  {
    "id": 1,
    "sessionId": "s1",
    "userMessage": "What is RAG?",
    "aiResponse": "RAG is Retrieval-Augmented Generation...",
    "relatedDocumentIds": [1],
    "metadata": {},
    "sources": [{
      "citationId": "S1",
      "documentId": "1",
      "title": "RAG Guide",
      "chunkText": "RAG combines retrieval and generation."
    }],
    "status": "COMPLETE",
    "mode": "KNOWLEDGE",
    "requestedModel": null,
    "resolvedModel": "minimax/MiniMax-M2.7",
    "createdAt": "2026-08-17T12:00:00"
  }
]
```

An unknown session and a session owned by another principal both return
`404 SESSION_NOT_FOUND`.

---

### `DELETE /api/v1/rag/chat/history/{sessionId}`

Clear the authenticated principal's business history and Spring AI Memory for
the session as one lease-protected operation. If the session currently has an
active request, the endpoint returns `409 SESSION_BUSY`.

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

Export the authenticated principal's conversation history as a downloadable
file. Unknown and foreign sessions return `404 SESSION_NOT_FOUND`.

| Parameter | Type | Location | Description |
|-----------|------|---------|-------------|
| `format` | string | query | `json` or `md` (default: `json`) |
| `limit` | int | query | Max turn records to export; `0` means all (default `0`) |

**Response:** `application/json; charset=utf-8` or
`text/markdown; charset=utf-8`, with
`Content-Disposition: attachment; filename="{sessionId}.{format}"`.

**JSON format response body:**
```json
{
  "sessionId": "s1",
  "totalMessages": 1,
  "messages": [
    { "role": "user", "content": "Hello", "timestamp": "..." },
    {
      "role": "assistant",
      "content": "Hi!",
      "timestamp": "...",
      "sources": [{
        "citationId": "S1",
        "documentId": "1",
        "title": "Greeting Guide",
        "chunkText": "..."
      }]
    }
  ]
}
```

**Markdown format response body:**
```markdown
# Chat Export: `s1`

**Total messages:** 1
**Exported at:** 2026-08-17T12:00:00

## User [2026-08-17T11:59:59]
Hello

## Assistant [2026-08-17T12:00:00]
Hi!

### Sources

- **S1**: Greeting Guide
```

---

## API Keys — Key Management

In root mode, every endpoint in this section requires the environment root.
Root-created keys have database role `NORMAL` and product profile `FULL_RAG`:
they can read and write the RAG data plane but cannot manage keys. Without a
root credential, legacy ADMIN/NORMAL management semantics remain.

### `GET /api/v1/rag/api-keys`

List credential history for compatibility and audit. Raw secrets and hashes are
never returned. New management UIs should use the principal endpoint below.

**Response 200**:
```json
[{
  "keyId": "rag_k_abc123",
  "principalId": "rag_p_service",
  "credentialVersion": 2,
  "currentCredential": true,
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

### `GET /api/v1/rag/api-keys/principals`

List one row per stable caller. The response contains current credential
metadata but no raw secret, hash, or complete credential history:

```json
[{
  "principalId": "rag_p_service",
  "name": "Production Server",
  "role": "NORMAL",
  "allowedCollectionKeys": ["customer-42:manual:v3"],
  "requestsPerMinute": 120,
  "policyVersion": 3,
  "status": "ACTIVE",
  "currentCredentialId": "rag_k_abc123",
  "currentCredentialVersion": 2,
  "lastUsedAt": "2026-08-23T12:00:00",
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
  "allowedCollectionKeys": ["customer-42:manual:v3"],
  "requestsPerMinute": 120
}
```

The raw secret appears only in the `201 Created` response, which includes
`Cache-Control: no-store`:

```json
{
  "keyId": "rag_k_xyz789",
  "principalId": "rag_k_xyz789",
  "credentialVersion": 1,
  "policyVersion": 1,
  "rawKey": "rag_sk_...",
  "name": "My API Key",
  "allowedCollectionKeys": ["customer-42:manual:v3"],
  "allowedCollectionIds": [1, 2],
  "expiresAt": "2026-10-01T00:00:00",
  "requestsPerMinute": 120
}
```

### `PUT /api/v1/rag/api-keys/principals/{principalId}/policy`

Atomically update name, expiry, Collection ACL, and optional principal quota.
`expectedPolicyVersion` is required; a stale value returns
`409 POLICY_VERSION_CONFLICT`. Omit `allowedCollectionKeys` for unrestricted
Collection access and omit `requestsPerMinute` to use the global quota.

```json
{
  "expectedPolicyVersion": 1,
  "name": "My API Key",
  "expiresAt": "2027-10-01T00:00:00",
  "allowedCollectionKeys": ["customer-42:manual:v3"],
  "requestsPerMinute": 240
}
```

### `POST /api/v1/rag/api-keys/{keyId}/rotate`

Disable the current credential and create the next credential version for the
same stable principal. Owner, role, policy version, ACL, expiry, and quota stay
unchanged. A stale credential ID returns `409 CREDENTIAL_NOT_CURRENT`; the new
raw secret appears only in this `201 Created` response.

### `DELETE /api/v1/rag/api-keys/{keyId}`

Revoke the principal family through its current credential ID. Repeating DELETE
for that last version is idempotent; a stale older credential returns
`409 CREDENTIAL_NOT_CURRENT`. Legacy mode prevents concurrent operations from
revoking the last ADMIN (`409 LAST_ADMIN_REQUIRED`); environment root mode may
explicitly revoke it. Success returns `204 No Content`.

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
      "score": 0.5,
      "vectorScore": 0.92,
      "fulltextScore": 0.0,
      "chunkText": "Retrieved text snippet...",
      "source": "pdf-import:550e8400-e29b-41d4-a716-446655440000/default.md",
      "originalFilename": "spring-ai-reference.pdf",
      "fileDirectoryPath": "550e8400-e29b-41d4-a716-446655440000/",
      "indexedFilePath": "550e8400-e29b-41d4-a716-446655440000/default.md",
      "originalFilePath": "550e8400-e29b-41d4-a716-446655440000/original.pdf",
      "metadata": {}
    }
  ],
  "total": 3,
  "query": "Spring AI"
}
```

Score semantics:

- In hybrid mode, `score` is the scaled weighted Reciprocal Rank Fusion (RRF)
  signal used to order results within the same query and retrieval
  configuration. With `K=60`, each channel contributes
  `(K+1) * weight / (K+rank)` and a candidate found by both channels receives
  both contributions. Provider-specific raw scores determine rank only; they
  are not compared across channels. It is not a calibrated probability or
  relevance percentage, and it can exceed `1.0` when the configured weights
  sum above `1.0`.
- `vectorScore` is the raw vector cosine-similarity score. In a fused result,
  `0` means that the result did not receive a vector contribution.
- `fulltextScore` is the raw provider-specific full-text score. In a fused
  result, `0` means that the result did not receive a full-text contribution.
- Compare result order first. Raw component scores are useful for diagnosing
  whether a result was found through semantic vector retrieval, keyword
  retrieval, or both; they are not directly interchangeable.
- Ties in the final fused score use deterministic `documentId`, then
  `chunkIndex` ordering.

Provenance fields:

- `source` and `originalFilename` come from the current `rag_documents` row;
  ordinary documents may also return them.
- `fileDirectoryPath`, `indexedFilePath`, and `originalFilePath` are returned
  only when the service validates a safe relative
  `pdf-import:{uuid}/default.md` source.
- These paths let clients trace a hit to the Files directory, the converted
  Markdown that was embedded, and the original PDF. Fetch the original through
  `/api/v1/rag/files/raw` with the API key in a request header; do not put
  credentials in URLs.
- Historical rows that already have a `pdf-import:` source gain these fields
  without re-embedding. An older PDF import that was globally content-hash
  merged into a row without that source cannot be reconstructed reliably.

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
  },
  "filters": {
    "metadataContains": {"source": "manual"},
    "payloadContains": {"status": "active"}
  }
}
```

`filters.metadataContains` / `filters.payloadContains` must be non-empty JSON
objects and use PostgreSQL `@>` pushed into every candidate query. Invalid
objects, oversized filters, or unknown fields return `400`. The
`X-RAG-Retrieval-Trace-Id` response header identifies the caller-visible
diagnostic.

---

## Retrieval Diagnostics

| Endpoint | Description |
|----------|-------------|
| `GET /api/v1/rag/retrieval-traces` | Page caller-visible diagnostics; filter by `outcomeCode`, `emptyReasonCode`, or `citationStatus` |
| `GET /api/v1/rag/retrieval-traces/{traceId}` | One diagnostic detail |

Query text is omitted by default. Persist failures are fail-open.

---

## JSON Structured Records — JSONB Payload Retrieval

Structured-record endpoints keep two caller-supplied values separate:

- `retrievalText` is the natural-language description used for `content_hash`,
  chunking, full-text search, and embedding.
- `jsonbPayload` is the business JSON stored as PostgreSQL JSONB and returned
  after a successful scoped retrieval.

The service does not generate or validate the relationship between the two
fields. Callers use `collectionKey + sourceNamespace + externalId` as the
stable external identity; the service resolves it to the internal
`(collectionId, sourceNamespace, documentType=json-record, externalId)`
identity. Deprecated
`collectionId` remains compatible. JSON records do not participate in global
content-hash deduplication, and there is no `payloadHash`.

### `POST /api/v1/rag/json-records/upsert`

Create or update one record. New external clients should send
`embeddingPolicy` explicitly. Legacy `embed` still maps to `SYNC`/`SKIP`.

```json
{
  "collectionKey": "customer-42:catalog:v1",
  "sourceNamespace": "catalog-main",
  "externalId": "product:sku-10001",
  "sourceRevision": "row-version:42",
  "expectedSourceRevision": "row-version:41",
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
  "embeddingPolicy": "ASYNC"
}
```

Payload-only updates create a version snapshot but do not invalidate a fresh
embedding. `retrievalText` changes invalidate the active embedding and persist
a re-embedding job according to `embeddingPolicy`. A new revision for an
existing identity requires `expectedSourceRevision` by default; exact replay
remains idempotent.

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
  "payloadContains": {
    "status": "active",
    "attributes": {"wireless": true}
  },
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
Optional `payloadContains` must be a non-empty JSON object. It uses PostgreSQL
`jsonb @>` exact subtree containment and is pushed into vector, pg_trgm,
English FTS, and pg_jieba candidate SQL. Defaults limit it to 16 KiB and depth
8; arrays follow PostgreSQL JSONB containment semantics.

With `RAG_JSON_AGENT_TOOL_ENABLED=true`, `AGENT` mode also registers
`searchJsonRecords`. The model may provide only `query`, optional
`payloadContains`, and `maxResults`; Collection, document, and API-key scope is
injected by the server. The tool accepts no SQL, JSONPath, or Collection
arguments.

### `GET /api/v1/rag/json-records/{documentId}`

Returns the current structured record by internal document ID, including
`collectionKey`, deprecated `collectionId`, `externalId`, `retrievalText`, and
`jsonbPayload`. Upsert and search responses also return both Collection
identities. Collection export/import, clone, and document-version responses
preserve the structured fields and payload snapshots.

### JSON-record external lookup and tombstone

```text
GET /api/v1/rag/json-records/by-external-id
  ?collectionKey=customer-42%3Acatalog%3Av1
  &sourceNamespace=catalog-main
  &externalId=product%3Asku-10001

DELETE /api/v1/rag/json-records/by-external-id
  ?collectionKey=customer-42%3Acatalog%3Av1
  &sourceNamespace=catalog-main
  &externalId=product%3Asku-10001
  &sourceRevision=row-version%3A43
  &expectedSourceRevision=row-version%3A42
```

Deletion creates a tombstone without deleting JSONB history or stable
identity. A later upsert with a new source revision restores the same internal
document.

<a id="external-documents-idempotent-synchronization"></a>

## External Documents — Idempotent Synchronization

These endpoints are for ordinary text documents whose source system owns the
document identity and revision. They do not fetch URLs or files. The caller
reads the source, then sends the current representation to the RAG service.

The stable external address is
`collectionKey + sourceNamespace + externalId`. The ordinary external-text
endpoint requires `collectionKey`, which must identify a real active
Collection. JSON-record upsert retains deprecated `collectionId` input for
compatibility, but it resolves to the same canonical key-based address.
`sourceNamespace` is optional: omitted or blank input is normalized to the
compatibility value `default`. A connector should choose and send a stable
explicit namespace when multiple connectors share a Collection or when source
reconciliation is used. It is an identity boundary, not an authorization
boundary; untrusted connectors should use separate Collections. `externalId`
is trimmed, case-sensitive, limited to 255 characters, and must remain stable
for the lifetime of the source object. `collectionKey` and `sourceNamespace`
accept up to 128 characters. These limits must not be reduced in a later
migration. `sourceRevision` is an opaque, non-empty caller token, such as an
ETag, upstream row version, commit ID, or canonical payload hash. The service
does not compare opaque revisions by ordering.

The external address, state revision, and internal ID are separate concepts.
Clients continue to address the document by the tuple; `sourceRevision`
describes only the complete desired state at that address; the returned
`documentId` is diagnostic. A Collection is currently both the placement
target and the ACL boundary, so `externalId` is not globally unique and one
source object may be explicitly placed in multiple Collections.
`sourceNamespace=default` is not a default Collection marker. An unassigned
(`NULL`) Collection is a local-document state, not an alternative external
synchronization target.

### `POST /api/v1/rag/documents/upsert`

Create or update one ordinary document. The endpoint requires a writable
Collection under the current API key's Collection ACL.

```json
{
  "collectionKey": "customer-42:manual:v3",
  "sourceNamespace": "cms-main",
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
  "embeddingPolicy": "ASYNC"
}
```

`title` and `content` are required. `documentType` defaults to `text`;
`json-record` must use the JSON structured-record API. New clients should send
`embeddingPolicy=ASYNC`; legacy `embed=true/false` still maps to `SYNC/SKIP`.
The request content limit is 1,000,000 characters.

The service keeps the same internal `documentId` when a source document is
updated. Content changes create a version snapshot, immediately exclude old
chunks/embeddings, and persist a new-generation job in the same transaction.
`SYNC` performs a bounded wait on that job; `ASYNC` returns immediately.
Metadata-only and source-revision-only changes do not call the provider when a
fresh embedding already exists.

Ordinary upsert does not change placement. Changing `collectionKey` addresses
another tuple. Use the explicit relocation API below when the same internal ID,
version history, and derived rows must be preserved.

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
  "sourceNamespace": "cms-main",
  "documentRevision": 4,
  "embeddingAction": "QUEUED",
  "embeddingJobId": "67b62d78-9358-4fe4-b0f5-1fb8af34e2d5",
  "lifecycle": {
    "documentState": "ACTIVE",
    "searchability": "INDEXING",
    "localIndexStatus": "READY",
    "embeddingStatus": "INDEXING",
    "activeEmbeddingProfileKey": "bge-m3-1024",
    "activeJobId": "67b62d78-9358-4fe4-b0f5-1fb8af34e2d5",
    "lastErrorCode": null,
    "lastError": null,
    "retryable": true
  },
  "errorCode": null,
  "error": null
}
```

`action` is `CREATED`, `UPDATED`, or `UNCHANGED`. The nested lifecycle is the
canonical readiness contract:

- `localIndexStatus=READY` means current keyword chunks exist;
- `embeddingStatus=READY` means the active Profile has current vectors;
- `searchability=READY` means both branches are current;
- `searchability=KEYWORD_ONLY` means keyword retrieval is current while the
  vector branch is `INDEXING`, `FAILED`, or `NOT_REQUESTED`;
- `searchability=NOT_REQUESTED` means neither branch was requested;
- `searchability=DISABLED` applies to disabled/tombstoned documents.

The compatibility top-level `embeddingStatus` and `embeddingFresh` fields
describe the remote embedding branch only. Do not use them to decide whether
keyword retrieval is available. A provider failure does not roll back the
document or expose old chunks: the new local chunks remain searchable as
`KEYWORD_ONLY`, while old content is excluded by hash and generation
freshness. Replaying the same request or using the embedding retry operation
can restore `searchability=READY`.

Production defaults to `strictExternalCas=true`: a new revision for an
existing identity requires `expectedSourceRevision`. Exact replay is checked
first so a client can safely retry after losing a successful response.
The same revision with different managed fields returns `409`. A mismatching
expected revision also returns `409`. Omit it for a new identity. Compatibility
deployments can disable strict CAS, but connectors should not rely on
last-write-wins delivery.

### `POST /api/v1/rag/documents/batch-upsert`

Accepts `{ "items": [ ... ] }` with 1–50 items and a total content limit of
5,000,000 characters. Each item is processed independently and results retain
input order. An item may report `action=PERSISTENCE_FAILED` for a persistence
failure or `embeddingStatus=FAILED` when persistence succeeded but embedding
failed; successful items in the same batch are not rolled back.

### `POST /api/v1/rag/documents/relocate`

This endpoint is disabled by default; enable it with
`RAG_DOCUMENT_RELOCATION_ENABLED=true`. It changes only an externally managed
document's Collection placement. It does not change the namespace, external ID,
source revision, content, or derived rows, and it never calls the embedding
provider. The caller needs access to both Collections and must send one
`Idempotency-Key` header per business relocation; network retries reuse that key.

```json
{
  "sourceCollectionKey": "customer-42:draft:v1",
  "targetCollectionKey": "customer-42:published:v1",
  "sourceNamespace": "cms-main",
  "externalId": "cms:article:10001",
  "expectedSourceRevision": "etag:8b4d9f"
}
```

Success returns the same `documentId`, preserved `sourceRevision`, a new document
revision, the target Collection, `derivationAction=PRESERVED`, and the actual
post-relocation lifecycle. The same principal/key/request exactly replays the
first successful response. Reusing the key for another request returns
`409 IDEMPOTENCY_KEY_REUSED`. Active source/target Sync Runs, revision/CAS
conflicts, an existing target identity, and a target retired by another
relocation return stable `409` errors.

After commit, the old address is permanently retired. Lookup, upsert, delete,
batch, and Sync Run items at that address return
`409 EXTERNAL_IDENTITY_RELOCATED`, preventing delayed events from recreating a
duplicate. Only an explicit reverse relocation of the same document resolves
the target's old marker atomically. The error reveals the target Collection key
only when the caller still has target ACL.

### `GET /api/v1/rag/documents/by-external-id`

Query the current ordinary document without exposing an internal ID as the
source-system identity:

```text
GET /api/v1/rag/documents/by-external-id?collectionKey=customer-42%3Amanual%3Av3&sourceNamespace=cms-main&externalId=cms%3Aarticle%3A10001
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
  &sourceNamespace=cms-main
  &externalId=cms%3Aarticle%3A10001
  &sourceRevision=etag%3Adeleted-9
  &expectedSourceRevision=etag%3A8b4d9f
```

This creates a tombstone (`enabled=false`, `sourceDeletedAt` set) and returns
`DELETED`. Replaying the same deletion revision returns `UNCHANGED`. An upsert
with a distinct subsequent `sourceRevision` can restore the same internal
document ID; the service does not compare revision size or freshness. The old
deletion revision cannot be replayed as an upsert. The legacy
`DELETE /documents/{documentId}` remains a hard-delete operation and has
different semantics.

### Recommended synchronization pattern

1. Assign a stable `sourceNamespace` per connector and derive `externalId`
   from the source object's immutable identity.
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
7. Stabilize Collection-placement rules before the first import. Do not
   simulate an atomic move by changing `collectionKey` on ordinary upsert.

See the
[External Document Synchronization Client Guide](external-document-sync-client-guide.md)
for delivery, retry, checkpoint, dead-letter, and production guidance. A
runnable reference implementation lives under `examples/external-sync-client/`.

---

## External Snapshot Synchronization Runs

Authoritative snapshot reconciliation is disabled by default and is enabled
with `RAG_DOCUMENT_SYNC_RUNS_ENABLED=true`. A run is scoped to one
`collectionKey + sourceNamespace`; the lease token is supplied in
`X-RAG-Sync-Lease`, but only its SHA-256 hash is persisted. Run items store
identity, fingerprint, status, and error information, never document content,
JSONB payloads, or the clear-text lease.

### `POST /api/v1/rag/document-sync-runs`

Begin or exactly replay a run:

```json
{
  "collectionKey": "customer-42:catalog:v1",
  "sourceNamespace": "cms-main",
  "clientRunId": "catalog-cut-2026-08-19T12:00:00Z",
  "snapshotMode": "ONLINE_CUT",
  "missingPolicy": "TOMBSTONE",
  "leaseSeconds": 900
}
```

`snapshotMode` and `missingPolicy` are explicit. `ONLINE_CUT` can use
`TOMBSTONE` after the connector establishes a consistent source cut.
`OFFLINE_MANIFEST` only permits `NONE`; the reference client uses this safe
combination for static manifests. `EXCLUSIVE_OFFLINE + TOMBSTONE` is a
dangerous opt-in and additionally requires `"confirmExclusiveOffline": true`;
the flag is rejected for every other mode/policy combination. At most one
active run exists for a Collection and namespace. Replaying the same
`clientRunId` requires the same lease and contract; a different lease or
contract returns `409`. Every run mutation rechecks the caller's current API
Key Collection ACL; a lease token does not bypass a later ACL restriction.

### `POST /api/v1/rag/document-sync-runs/{runId}/batch-upsert`

Send at most 100 bounded items using the run's Collection and namespace. Items
use the existing TEXT or JSON_RECORD representations and must include a stable
`externalId` and opaque `sourceRevision`. Exact item replay is idempotent.
An item that was changed after the run's snapshot boundary returns
`SKIPPED_NEWER_MUTATION` and is not overwritten. Failed items can be retried
with the same fingerprint.

### `POST /api/v1/rag/document-sync-runs/{runId}/preview-missing`

Returns a bounded identity summary, candidate fingerprint, counts by document
kind, and the number of newer mutations that were protected. The preview token
is required by `complete`; it is bound to the current candidate fingerprint.

### `POST /api/v1/rag/document-sync-runs/{runId}/complete`

```json
{
  "previewToken": "returned-by-preview",
  "confirmMissingCount": 1
}
```

For `missingPolicy=TOMBSTONE`, the candidate count must remain unchanged after
preview. A configured absolute/percentage deletion threshold protects against
an incomplete source manifest; exceeding it requires an explicit matching
`confirmMissingCount`. Reconciliation tombstones set `enabled=false` and
`deletionOrigin=RECONCILIATION` without inventing a source revision. A later
source upsert or explicit source tombstone continues to use the normal external
CAS path.

If the run still has any item whose current ledger status is `FAILED`,
completion with `missingPolicy=TOMBSTONE` returns `409 SYNC_RUN_INCOMPLETE`
and performs no missing tombstone. The client must retry failed items with
the same fingerprint or abort the run. A `missingPolicy=NONE` run may complete
with failed items because it never infers deletion from missing identities.

### `POST /api/v1/rag/document-sync-runs/{runId}/abort`

Abort an active run. Expired leases are fenced with conditional updates; no
pessimistic database lock is used. `GET /{runId}` and `GET /` provide authorized
status and history.

## Local Version Restore

Version restore is disabled by default and is enabled with
`RAG_DOCUMENT_VERSION_RESTORE_ENABLED=true`. It is intentionally limited to a
local document and a `snapshotCompleteness=FULL` version. External documents
remain owned by their source connector.

### `POST /api/v1/rag/documents/{documentId}/versions/{versionNumber}/restore`

```json
{
  "expectedDocumentRevision": 7,
  "embeddingPolicy": "ASYNC",
  "visibilityMode": "KEEP_CURRENT"
}
```

The request uses the current document revision as a CAS token. A successful
restore creates a new business revision and a new `RESTORE` version; it does
not rewind or delete later history. `visibilityMode=SNAPSHOT` also restores
the snapshot's enabled state, while `KEEP_CURRENT` retains the current
visibility. A content-changing restore schedules the normal new-generation
derivation path; a metadata-only restore does not call the embedding provider.

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

Returns content, metadata, `documentRevision`, and lifecycle by internal ID.
`lifecycle.searchability` is one of `READY`, `KEYWORD_ONLY`, `INDEXING`,
`FAILED`, `NOT_REQUESTED`, or `DISABLED`. Inspect
`localIndexStatus` and `embeddingStatus` separately: `embeddingFresh=false`
does not imply that keyword retrieval is unavailable.

---

### `PATCH /api/v1/rag/documents/{id}`

Presence-aware merge patch for locally managed documents. It requires
`expectedDocumentRevision` and may update `title`, `content`, `source`,
`metadata`, and `collectionKey`. Unknown fields or a request without mutable
fields return `400`.

```json
{
  "expectedDocumentRevision": 3,
  "content": "Updated document body",
  "embeddingPolicy": "ASYNC"
}
```

A content change increments the revision, records a complete snapshot, stales
old derived results, and queues the new job in one transaction. Title, source,
metadata, and Collection-only changes do not call the embedding provider.
An explicitly stale `expectedDocumentRevision` returns
`409 DOCUMENT_REVISION_CONFLICT`. If concurrent requests both pass the initial
check but race at commit, the optimistic-lock loser returns
`409 CONCURRENT_MODIFICATION`. Clients should re-read the document and latest
revision before deciding whether to retry either conflict; never overwrite
automatically.

### `POST /api/v1/rag/documents/{id}/disable`

The body is `{"expectedDocumentRevision":4}`. Disable immediately excludes the
document from retrieval and cancels active work while retaining content,
versions, and derived data for restoration.

### `POST /api/v1/rag/documents/{id}/restore`

The body includes `expectedDocumentRevision` and optional `embeddingPolicy`.
If current derived state is still fresh, restoration is immediately `READY`;
otherwise it is rebuilt according to the policy.

---

### `DELETE /api/v1/rag/documents/{id}`

Permanently deletes a locally managed document. The query parameter
`expectedDocumentRevision` is required, and versions, state, and jobs cascade.
Externally managed documents must use the source tombstone endpoint; local
PATCH/disable/restore/permanent-delete operations reject them.

---

### `GET /api/v1/rag/documents/stats`

Get document statistics (total count, embedded count, etc.).

---

### `POST /api/v1/rag/documents/{id}/embed`

Generate embedding vectors for a specified document.

---

## Embedding Jobs — Durable Re-Embedding

The durable, cancellable, retryable embedding/reindex worker is enabled by
default because content mutations use persistent jobs for both `SYNC` and
`ASYNC`. Explicitly disabling it causes content mutations that need scheduling
to return `503 EMBEDDING_JOBS_DISABLED`; reads and mutations that do not affect
derived input remain available.

### `POST /api/v1/rag/embedding-jobs`

Create jobs from either explicit document IDs or a Collection scope, never
both. Success returns `202 Accepted`. An active job for the same
document/Profile/content hash is coalesced and reports `coalesced=true`.

```json
{
  "collectionScopeMode": "SELECTED_COLLECTIONS",
  "collectionKeys": ["customer-42:manual:v3"],
  "force": false,
  "maxAttempts": 3
}
```

A Collection scope may expand to at most 1000 enabled documents. Callers may
instead send `{"documentIds":[1,2,3],"force":true}`. API-key Collection ACLs
apply to every target.

### Query And Control

| Endpoint | Description |
|----------|-------------|
| `GET /api/v1/rag/embedding-jobs/{id}` | Get one authorized-visible job |
| `GET /api/v1/rag/embedding-jobs?batchId=&status=&collectionKey=&page=0&size=50` | Filter and page jobs; Collection ACL is applied before `LIMIT` |
| `GET /api/v1/rag/collections/embedding-readiness?collectionKey=` | Exclusive Collection readiness buckets: fresh/queued/running/failed/stale |
| `POST /api/v1/rag/embedding-jobs/{id}/cancel` | Request cancellation |
| `POST /api/v1/rag/embedding-jobs/{id}/retry?maxAttempts=4` | Retry a `FAILED`, `STALE`, or `CANCELLED` job |

Statuses are `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, and
`STALE`. The job table never copies document content; it stores only
document/Profile/content hash/version, lease, retry, and terminal-state data.

## Collection Derivation Integrity and Controlled Repair

`GET /api/v1/rag/collections/derivation-readiness?collectionKey=` returns
exclusive Collection-level buckets for the active Profile: `READY`,
`KEYWORD_ONLY`, `INDEXING`, `NOT_REQUESTED`, `LOCAL_UNAVAILABLE`, and
`CORRUPT`. Classification verifies contiguous current-generation local chunks,
hash/chunker/text/positions, vector-to-local chunk correspondence, and fixed
vector dimensions. The legacy `embedding-readiness` endpoint uses this same
physical source of truth instead of trusting state plus row count.

`GET /api/v1/rag/collections/derivation-readiness/documents` accepts
`collectionKey`, optional `bucket`, `page`, and `size`. Size is bounded to
1–100. Responses omit content, metadata, JSON payloads, chunk text, and vectors;
they contain controlled states/counts, an error capped at 500 characters, and
recommended actions.

Side-effecting repair is disabled by default; enable it with
`RAG_DOCUMENT_DERIVATION_REPAIR_ENABLED=true`:

| Endpoint | Semantics |
|----------|-----------|
| `POST /api/v1/rag/collections/derivation-repairs/preview` | Builds a stable plan of at most 100 items by bucket/vector condition; the clear token appears only in this response and only its hash is stored |
| `POST /api/v1/rag/collections/derivation-repairs/apply` | Validates repair ID, Collection, token, fingerprint, owner, and ACL; local rebuild and vector enqueue use separate short transactions |
| `GET /api/v1/rag/collections/derivation-repairs/{repairId}` | Recovers durable item results and embedding job IDs after interruption or restart; owner and Collection ACL are rechecked |

A preview normally must start apply within 15 minutes, an operation is bounded
to one hour, and terminal results are retained for 24 hours. Apply does not loop
over synchronous provider calls: it reuses the formal local chunk path and only
persists required vector jobs. A document whose revision, hash, Collection, or
visibility changed after preview is reported as `SKIPPED_CHANGED`; the old plan
is never applied to its new state.

---

---

### `POST /api/v1/rag/documents/batch`

Batch create documents. Optional `embeddingPolicy` (`SYNC` / `ASYNC` / `SKIP`)
overrides the legacy `embed` flag; `ASYNC` enqueues in the same transaction and
returns `embeddingJobId`. Set the top-level `collectionKey` as the default
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

<a id="pdf-and-file-artifact-apis"></a>

### PDF And File Artifact APIs

`POST /api/v1/rag/files/pdf` converts one PDF and stores the original,
`default.md`, and extracted assets under a new UUID path in `fs_files`. It does
not create a searchable RAG document. The legacy `collection` form field is
currently ignored; it is not a RAG `collectionKey`.

`GET /api/v1/rag/files/tree?path=...` lists direct files and synthetic
directories. Each entry includes:

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Last path segment |
| `path` | string | Full virtual path |
| `type` | string | `file` or `directory` |
| `mimeType` | string/null | File MIME type; null for directories |
| `size` | long | Bytes; zero for directories |
| `createdAt` | timestamp/null | File storage time; for a directory, the latest time among descendant files |

`POST /api/v1/rag/files/{uuid}/embed` reads `{uuid}/default.md`, creates or
reuses a RAG document by the stable
`pdf-import:{uuid}/default.md` source, and triggers embedding. Repeating the
same UUID/source reuses one logical document. Different UUIDs create distinct
documents even when converted content is identical. The content hash remains
the embedding-freshness signal; it is no longer the PDF identity.
`POST /api/v1/rag/files/pdf-to-rag` combines import and RAG registration.

The data-layer relationship and WebUI behavior are documented in
[File Management, PDF Import, And RAG Integration](file-management-and-pdf-rag.md).

#### PDF-to-RAG Collection Scope

The following multipart endpoints prefer `collectionKey`; deprecated
`collectionId` remains compatible:

- `POST /api/v1/rag/files/pdf-to-rag`
- `POST /api/v1/rag/files/{uuid}/embed`

`embed=false` on `/pdf-to-rag` returns JSON immediately. `embed=true`, or
omitting `embed`, returns an SSE progress stream. For `/{uuid}/embed`,
`embed=sync` returns JSON and `embed=sse`, or omission, returns SSE. If both
Collection identifiers are supplied, they must match.

The legacy `collection` parameter on `POST /api/v1/rag/files/pdf` is unrelated
and currently ignored. Imports always use a UUID directory. It is not a RAG
Collection identity.

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
`collectionKey + sourceNamespace + externalId` identity. Explicitly hard-delete or migrate those
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
  "documentId": 1,
  "expectedDocumentRevision": 3
}
```

sent to:

```http
POST /api/v1/rag/collections/by-key/documents?collectionKey=customer-42%3Amanual%3Av3
```

Because `rag_documents.collection_id` is single-valued, this operation
reassociates or moves an ordinary document that already belongs to another
Collection, without re-embedding it. The caller must be allowed to access both
the source document and target Collection. `expectedDocumentRevision` is
required and must match the document's current public revision; stale writers
receive `409`. Externally managed documents with a
nonblank `externalId` return `409`; keep synchronizing those documents by their
stable `collectionKey + sourceNamespace + externalId` identity instead of moving them through
this compatibility association route.

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

## Managed Quality Suites

Available when `RAG_EVALUATION_MANAGED_SUITES_ENABLED=true`. Disabled servers
return `503 EVALUATION_SUITES_DISABLED`. A suite version is immutable after
creation. Relevant documents must use
`collectionKey + sourceNamespace + externalId`; omitted `sourceNamespace`
defaults to `default`. Internal document IDs are not a durable goldenset
identity.

| Endpoint | Description |
|----------|-------------|
| `POST /api/v1/rag/evaluation/suites` | Create a suite |
| `GET /api/v1/rag/evaluation/suites` | List suites for the current principal |
| `GET /api/v1/rag/evaluation/suites/{suiteKey}` | Get one suite owned by the current principal |
| `POST /api/v1/rag/evaluation/suites/{suiteKey}/versions` | Import an immutable version |
| `POST /api/v1/rag/evaluation/runs` | Create a PENDING run |
| `GET /api/v1/rag/evaluation/runs/{runId}` | Get a run and its case results |
| `GET /api/v1/rag/evaluation/runs/compare` | Compare two runs of the same version |
| `POST /api/v1/rag/evaluation/semantic` | Optional Spring AI FactChecking/Relevancy adapter; returns `DISABLED` when unavailable |
| `POST /api/v1/rag/evaluation/semantic/batch` | Same adapter for a bounded batch of items |

Citation validation only checks `[S1]` tokens. Compare marks `environmentDrift`
when the embedding profile, code revision, or corpus snapshot differs; drift is
not reported as a quality improvement.

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
      "capabilities": {
        "streaming": true,
        "toolCalling": true
      },
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
      "capabilities": {
        "streaming": true,
        "toolCalling": false
      },
      "source": "configured"
    }
  ]
}
```

Models that are configured but missing credentials remain in the list with
`available: false` and an `unavailableReason`.

`capabilities.streaming` defaults to `true` when omitted for backward
compatibility. `capabilities.toolCalling` defaults to `false` and must be
explicitly enabled only after the concrete upstream model/endpoint has been
verified. `AGENT` mode requires Tool Calling; the WebUI uses this field to
disable incompatible selections.

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
        "available": true,
        "capabilities": {
          "streaming": true,
          "toolCalling": true
        }
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
