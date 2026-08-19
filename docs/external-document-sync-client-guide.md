# External Document Synchronization Client Guide

> [English](external-document-sync-client-guide.md) | [中文](external-document-sync-client-guide-zh-CN.md)

This guide defines the recommended integration pattern for systems that own
documents outside spring-ai-rag and need create/update/delete changes to
propagate safely into chunks, full-text indexes, and embeddings.

It covers incremental webhook/CDC synchronization. Authoritative full-snapshot
reconciliation is not part of the current API.

## 1. Choose A Stable Source Identity

Every externally managed object is identified by:

```text
collectionKey + sourceNamespace + externalId
```

| Field | Rule |
|---|---|
| `collectionKey` | Stable target Collection business key. |
| `sourceNamespace` | Stable connector/source ownership space such as `cms-main` or `erp-products`. Always send it explicitly. |
| `externalId` | Immutable source object ID. Do not derive it from title or content. |
| `sourceRevision` | Opaque token for the complete desired source state: ETag, row version, commit ID, or canonical-state hash. |
| `expectedSourceRevision` | CAS precondition for updates and tombstones. |

`sourceNamespace` is an identity boundary, not an authorization boundary. Two
untrusted connectors must use different Collections, because a key with write
access to a Collection is not isolated to one namespace.

The RAG service's internal `documentId` is useful for diagnostics only. A
connector must persist and address the source identity above.

## 2. Incremental CRUD Contract

### Create Or Update Text

Use `POST /api/v1/rag/documents/upsert`.

An upsert is a **complete desired representation**, not a merge patch. Send
`title`, `content`, source fields, metadata, and an explicit
`embeddingPolicy`. Omitted optional fields are normalized as empty values; do
not rely on the server retaining fields from an older revision.

For a new identity, omit `expectedSourceRevision`. For every later revision,
send the last revision accepted by the service:

```json
{
  "collectionKey": "customer-42:manual:v3",
  "sourceNamespace": "cms-main",
  "externalId": "article:10001",
  "sourceRevision": "etag:8b4d9f",
  "expectedSourceRevision": "etag:7a3c21",
  "title": "Refund policy",
  "content": "The current refund policy is ...",
  "source": "cms",
  "documentType": "markdown",
  "metadata": {"locale": "en-US"},
  "embeddingPolicy": "ASYNC"
}
```

The production default is strict external CAS. A new revision for an existing
identity without `expectedSourceRevision` is rejected. An exact replay of an
already accepted revision remains idempotent.

### Delete From The Source

Use `DELETE /api/v1/rag/documents/by-external-id` with all identity fields, a
new deletion revision, and the expected current revision.

This creates a tombstone. It immediately removes the document from retrieval
but preserves its stable identity and audit history. A later UPSERT with a new
revision restores the same internal document. This is intentionally different
from local permanent deletion.

### JSON Structured Records

JSON records use the same identity, revision, CAS, replay, and tombstone model
through `/api/v1/rag/json-records`. The caller supplies:

- `retrievalText`: the natural-language description that is chunked and embedded;
- `jsonbPayload`: the structured value returned and filtered as JSONB.

A payload-only update does not invoke the embedding provider. A
`retrievalText` update does.

## 3. CRUD And Derived-Index Propagation

The document row is the source of truth. Chunks and embeddings are derived
state.

| Change | Document revision | Old retrieval result | New embedding job |
|---|---:|---|---|
| Create with `SYNC`/`ASYNC` | increments | not applicable | yes |
| `content` / `retrievalText` | increments | immediately stale and excluded | yes |
| title/source/metadata only | increments | current document metadata is visible immediately | no |
| `jsonbPayload` only | increments | current payload is visible immediately | no |
| Collection assignment only | increments | scope changes immediately | no |
| Disable/tombstone | increments | immediately excluded | no |
| Restore | increments | reused if already fresh; otherwise queued by policy | only when needed |
| Permanent local delete | document removed | immediately excluded | pending jobs and derived rows removed/cancelled |

The service never serves chunks from an old content hash while a new content
revision is indexing. With the current storage model, that document is
temporarily unavailable to both keyword and vector retrieval until the new
derived state is `READY`.

`embeddingPolicy` controls waiting and scheduling:

- `ASYNC`: recommended for connector throughput; commit the mutation and durable
  job, then return;
- `SYNC`: use only when a caller needs a bounded wait for the same durable job;
- `SKIP`: explicitly leave the new content as `NOT_REQUESTED`.

## 4. Delivery Algorithm

For each source event:

1. Build the complete desired representation.
2. Allocate an immutable delivery `eventId`.
3. Send the source object's new opaque revision and the last accepted revision
   as `expectedSourceRevision`.
4. On success, checkpoint the delivery event and accepted revision atomically.
5. On a network timeout, replay the exact same request.
6. On `409`, stop that identity, re-read both the source and current RAG
   document, then generate a new event. Never retry the stale event as a
   last-write-wins update.
7. Observe lifecycle/readiness separately when downstream workflows require
   the content to be searchable.

Do not compare opaque revision strings lexically or numerically in the RAG
client. Ordering belongs to the source system.

## 5. Retry And Error Classification

| Result | Client action |
|---|---|
| HTTP 2xx | Checkpoint success. Inspect lifecycle separately if search readiness is required. |
| Network timeout/reset | Replay the exact request with bounded backoff. |
| `408`, `425`, `429`, `5xx` | Bounded exponential backoff with jitter; honor a reasonable `Retry-After`. |
| `409` | CAS or exact-replay conflict. Re-read source and RAG state; do not overwrite automatically. |
| `400` | Invalid event or contract mismatch. Dead-letter after recording the identity and safe error code. |
| `401` / `403` | Stop and repair credentials or Collection ACL. |
| `404` on tombstone | Reconcile source/client state; do not silently turn it into create. |

Embedding provider failure after a document mutation does not roll back the
source document. The lifecycle becomes `FAILED`, old chunks remain excluded,
and the same accepted revision can be replayed or retried through embedding
operations.

## 6. Readiness

Mutation success means the source state was accepted. It does not always mean
the document is searchable yet.

Use the document lifecycle read model:

- `READY`: current content is searchable;
- `INDEXING`: durable work is queued or running;
- `FAILED`: the current derivation failed and is retryable when indicated;
- `NOT_REQUESTED`: the caller used `SKIP`;
- `DISABLED`: disabled or tombstoned.

For bulk workflows, prefer Collection embedding readiness over polling every
document.

## 7. Reference Client

The standard-library-only client is in
`examples/external-sync-client/`. It implements:

- streaming immutable JSONL `UPSERT` and `TOMBSTONE` events;
- exact-request retries with bounded backoff and jitter;
- SQLite byte-offset and event checkpoints;
- duplicate `eventId` detection;
- input-file fingerprint validation before resume;
- `0600` checkpoint permissions on POSIX systems;
- structured summaries without document bodies or secrets.

Run:

```bash
export RAG_BASE_URL=http://127.0.0.1:8081
export RAG_API_KEY='...'

python3 examples/external-sync-client/sync_client.py apply-events \
  --events examples/external-sync-client/sample-events.jsonl \
  --checkpoint .external-sync/catalog.sqlite3
```

The API key is accepted only through an environment variable. It is not
accepted on the command line and is never stored in the checkpoint. Treat one
JSONL file as immutable after processing starts; use a new file and checkpoint
for the next delivery batch.

## 8. Production Checklist

- Use a Collection-restricted API key, never the environment root key.
- Make source identity and revisions stable before the first import.
- Use `ASYNC` for normal bulk/CDC delivery.
- Persist checkpoint and source revision only after HTTP success.
- Keep request logs free of API keys, full content, and sensitive payloads.
- Dead-letter permanent 4xx errors with identity and error code, not full body.
- Alert on lifecycle `FAILED`, queue age, and non-ready Collection counts.
- Test duplicate delivery, out-of-order delivery, timeout-after-commit, delete,
  restore, and client restart before production.
- Do not infer authoritative deletion from an incomplete batch. Current
  incremental synchronization deletes only explicit `TOMBSTONE` events.

The precise HTTP fields and response shapes remain defined by
[REST API](rest-api.md#external-documents-idempotent-synchronization).
