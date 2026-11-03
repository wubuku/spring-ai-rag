# Deletion Semantics and Data Retention

This document defines permanent deletion, soft deletion, orphan criteria, and
retention boundaries. Command and endpoint details live in the
[REST API](rest-api.md); collection removal lives in
[Collection permanent purge](configuration.md#permanent-collection-purge-and-retirement).

## Orphan Definition

An orphan is a row whose logical owner no longer exists but which must disappear
under its own semantics. By that definition:

- **Orphans**: chunks, vectors, version snapshots, chat source references, and
  feedback references that still point at a permanently deleted document.
- **Not orphans**: chat history and feedback bodies, evaluation snapshots, and
  `fs_files` artifacts. They record historical facts or independent content and
  stay meaningful after document deletion; their own retention policies govern them.
- Idempotency rows are not orphans either: deleting a document nulls
  `result_document_id`, and the operation row is retained until its TTL.

## Document Deletion

- **Disable**: soft delete. Content and history stay; retrieval stops matching
  immediately (`enabled = false`).
- **External source deletion**: writes a `source_deleted_at` tombstone and sets
  `enabled` to false. Content is not physically removed, so sync can replay.
- **Permanent delete** (`DELETE /api/v1/rag/documents/{id}`, requires
  `expectedRevision`): in one transaction it removes vectors, explicitly clears
  `rag_chat_history_source_document` and `rag_user_feedback_document`, then deletes
  the document row; chunks, versions, jobs, and processing runs cascade through
  foreign keys. Retrieval cannot match the document immediately afterwards.
- Only local documents can be permanently deleted. Externally managed documents
  must first be retired at the source or through collection purge.

## Collection Deletion

Two semantics exist and must not be conflated:

- **Soft delete/retirement** (legacy numeric-ID endpoint): returns 409 when
  externally managed documents exist; otherwise it unlinks local documents and
  marks the collection deleted. Unlinked documents remain searchable — that is the
  retained legacy semantic, not a cascading delete.
- **Permanent purge**: cascades documents, chunks, vectors, versions, source and
  feedback references, plus related sessions and feedback for the collection key,
  and reserves the key against reuse. Preview, confirmation token, lease, and
  batching are documented in configuration. Production clients should use purge
  instead of the legacy numeric-ID delete.

## Sessions and Uploaded Files

- Deleting a session cascades its history rows and
  `rag_chat_history_source_document` references by `session_id`; other sessions
  keep their references.
- `fs_files` is independent content storage and belongs to no document: deleting a
  document **does not** delete imported or converted artifacts. File rows are not
  orphans, and there is no file deletion API. Cleanup needs a file-level lifecycle
  first; a document delete must not remove shared artifacts as a side effect.

## Retrieval and Evaluation Filtering

- Vector and keyword retrieval require `enabled = true`, and recall context also
  requires `source_deleted_at IS NULL`, so disabled, tombstoned, and permanently
  deleted documents never produce ghost results.
- Evaluation fingerprints include enabled and disabled documents to detect
  collection content drift; that is an offline snapshot, not an online retrieval
  entry point. A deleted collection key is rejected by the collection resolver, so
  evaluation does not run against it.

## Concurrent Deletion

Deletion and retrieval/import do not rely on explicit pessimistic locks: document
deletion uses revision-conditional writes, and collection purge uses owner leases,
idempotency keys, and reservations. With concurrent delete vs retrieval, a read
transaction may still see the old row before the delete commits, but nothing may
reappear afterwards. Concurrent purge vs import may end in a clean foreign-key
rejection or a purge rollback/retry, never in a document that outlives its
collection. The gated test asserts both no deadlocks and no dirty reads.

## Retention and Cleanup (Decision Record)

Existing cleanup covers chat history (`rag.memory.message-ttl-days`, 30 days by
default), chat/document idempotency records (24 hours), retrieval diagnostics
(7 days), usage ledger (400 days), audit/evaluation (400 days), client errors
(30 days), integration observability (90 days), collection-purge preview/result
cleanup, and bounded cleanup of terminal tasks and budget records.

**Decision**: this round adds no new scheduled cleanup task. `fs_files` artifacts
and post-deletion history/evaluation snapshots have no clear retention owner; until
a file deletion API and a product retention policy exist, adding a timer would
delete traceable evidence without a mandate. Revisit with a dedicated migration and
configuration when a file lifecycle or compliance requirement appears.

## Gated Verification

Real PostgreSQL acceptance:

```bash
./scripts/verify-gated-it.sh DeletionConsistencyPostgresIntegrationTest
```

Its switch is `-Ddeletion-consistency.it.enabled=true`. It covers
chunks/vectors/versions/references/idempotency consistency after permanent delete,
session-delete references, no retrieval hits after deletion, and concurrent
delete-vs-retrieval / purge-vs-import end states with no orphans or deadlocks. The
nightly gated run executes it alongside the other pure-database suites.
