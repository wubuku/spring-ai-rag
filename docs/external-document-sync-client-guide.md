# External Document Synchronization Client Guide

> [English](external-document-sync-client-guide.md) | [中文](external-document-sync-client-guide-zh-CN.md)

This guide defines the recommended integration pattern for systems that own
documents outside spring-ai-rag and need create/update/delete changes to
propagate safely into chunks, full-text indexes, and embeddings.

It covers incremental webhook/CDC synchronization and the authoritative
full-snapshot reconciliation API.

## 1. Choose A Stable Source Identity

The current API uses this tuple as the stable external address of one RAG
document placement:

```text
collectionKey + sourceNamespace + externalId
```

| Field | Rule |
|---|---|
| `collectionKey` | Stable target Collection business key. The ordinary external-document endpoint requires it and it must identify a real active Collection. JSON-record upsert retains deprecated `collectionId` input, but new clients and all later external-address operations should use the resolved key. |
| `sourceNamespace` | Optional stable connector/source ownership space such as `cms-main` or `erp-products`. Omitted or blank is normalized to `default`; choose and send an explicit value when multiple connectors share a Collection or use source reconciliation. |
| `externalId` | Immutable source object ID. Do not derive it from title or content. |
| `sourceRevision` | Opaque token for the complete desired source state: ETag, row version, commit ID, or canonical-state hash. |
| `expectedSourceRevision` | CAS precondition for updates and tombstones. |

The current maximum lengths are deliberately generous and must not be reduced:
`collectionKey` and `sourceNamespace` accept up to 128 characters, and
`externalId` accepts up to 255 characters. These are client-controlled
identifiers, not server-generated hashes.

`sourceNamespace=default` is the compatibility namespace used when the field
is omitted or blank. It does not identify a default Collection. `collectionKey`
is the canonical Collection component of every external address; `null`
Collection membership is reserved for local/unassigned documents and is not
an external default target.

Keep these concepts separate:

- **External address**: `collectionKey + sourceNamespace + externalId`, used
  for every lookup, upsert, and deletion.
- **Source object ID**: `sourceNamespace + externalId`, derived stably by the
  connector from the source system.
- **State revision**: `sourceRevision`, the complete desired state currently
  delivered at that address.
- **Internal ID**: `documentId`, used only for server-side diagnostics and
  operations.

`collectionKey` is part of the external address because the current project
has no independent tenant resource and a Collection is both a placement target
and an ACL boundary. One source object may also be intentionally placed in
multiple Collections. Do not flatten the tuple into an opaque concatenated
string, and do not require `externalId` to be globally unique across the
service.

`sourceNamespace` is an identity boundary, not an authorization boundary. Two
untrusted connectors must use different Collections, because a key with write
access to a Collection is not isolated to one namespace.

The RAG service's internal `documentId` is useful for diagnostics only. A
connector must persist and address the source identity above.

### Collection relocation boundary

The current ordinary upsert locates a document by the target tuple. It
**cannot** atomically move an existing externally managed document to another
Collection. Changing only `collectionKey` addresses another placement and may
create a second document; it is not an ordinary update of the original.

Until an explicit relocation API exists, a move requires:

1. tombstoning the old tuple with a new revision;
2. upserting the complete state at the target tuple;
3. observing both operations until they converge.

This compatibility flow is not atomic. It creates a new internal `documentId`,
independent version history, and new derivation work. Systems that cannot
accept a transient duplicate/gap or must preserve history should keep the
Collection assignment stable and wait for a controlled atomic relocation
operation instead of simulating a move with ordinary upsert.

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
| `content` / `retrievalText` | increments | old generation immediately excluded; new local chunks may be `KEYWORD_ONLY` | yes |
| title/source/metadata only | increments | current document metadata is visible immediately | no |
| `jsonbPayload` only | increments | current payload is visible immediately | no |
| Change `collectionKey` on ordinary upsert | not an update of the same identity | old address remains unless explicitly tombstoned | target address follows create semantics |
| Disable/tombstone | increments | immediately excluded | no |
| Restore | increments | reused if already fresh; otherwise queued by policy | only when needed |
| Permanent local delete | document removed | immediately excluded | pending jobs and derived rows removed/cancelled |

The service never serves chunks from an old content hash while a new content
revision is indexing. V43 prepares profile-neutral local chunks independently
from remote vectors, so the document may be available to keyword retrieval as
`KEYWORD_ONLY` while the vector branch is queued, processing, or failed.
`READY` means both local and vector branches are current.

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

For a single-source Collection, omitting `sourceNamespace` is equivalent to
sending `default`. For a Collection shared by multiple connectors, always
choose a stable explicit namespace before the first delivery and keep it
unchanged for the lifetime of that connector's identity.

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
source document. When the local index is current, lifecycle becomes
`KEYWORD_ONLY` with a failed embedding branch; old chunks remain excluded.
The same accepted revision can be replayed or retried through embedding
operations. `embeddingFresh` and the compatibility top-level
`embeddingStatus` describe only the vector branch.

## 6. Readiness

Mutation success means the source state was accepted. It does not always mean
the document is searchable yet.

Use the document lifecycle read model:

- `READY`: current content is searchable through both keyword and vector branches;
- `KEYWORD_ONLY`: current local keyword chunks are searchable, but vector work is
  queued, processing, not requested, or failed;
- `INDEXING`: the current local index is not ready and durable work is queued or running;
- `FAILED`: the current local derivation failed and is retryable when indicated;
- `NOT_REQUESTED`: the caller used `SKIP`;
- `DISABLED`: disabled or tombstoned.

For operational decisions, inspect `localIndexStatus`, `embeddingStatus`, and
`searchability` together. Do not treat `embeddingFresh=false` as proof that
the document cannot be found by keyword retrieval.

For bulk workflows, prefer Collection embedding readiness over polling every
document.

## 7. Authoritative Snapshot Reconciliation

When the source can produce a complete, consistent view, use the Sync Run
protocol instead of inferring deletion from an incomplete batch:

1. `POST /api/v1/rag/document-sync-runs` with a stable `clientRunId`, explicit
   `snapshotMode`, `missingPolicy`, and the opaque
   `X-RAG-Sync-Lease` header.
2. Send bounded `batch-upsert` requests. Items inherit the Collection and
   `sourceNamespace` from the run and must include `externalId` and
   `sourceRevision`.
3. Call `preview-missing`, retain its opaque preview token, then call
   `complete`.
4. Use `TOMBSTONE` only for a source-consistent `ONLINE_CUT`. The safe static
   manifest default is `OFFLINE_MANIFEST + NONE`.
5. `EXCLUSIVE_OFFLINE + TOMBSTONE` requires
   `confirmExclusiveOffline=true` and means the connector guarantees exclusive
   source writes for the whole run. Treat it as a deliberate destructive
   operation, not a default.

Failed items must be retried with the same fingerprint before a tombstone run
can complete. The service protects documents changed after the snapshot
boundary, applies deletion thresholds, and never stores bodies or JSONB
payloads in the run ledger. Every run mutation rechecks the current API-key
Collection ACL; the lease token is not an ACL bypass. See the [REST API
contract](rest-api.md#external-snapshot-synchronization-runs) for exact fields,
responses, and error codes.

## 8. Reference Client

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

## 9. Production Checklist

- Use a Collection-restricted API key, never the environment root key.
- Make source identity and revisions stable before the first import.
- Fix the Collection-placement rule before the first import; do not treat a
  changed `collectionKey` as an ordinary update.
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
