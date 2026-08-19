# External Sync Reference Client

This Python 3.11+ client demonstrates the supported Batch A incremental
external-document protocol. It streams an immutable JSONL event file and calls
the RAG service with stable source identity, source-revision CAS, bounded
retries, and a resumable SQLite checkpoint.

The client intentionally implements only `apply-events`. Authoritative snapshot
reconciliation is a later batch and is not exposed as a placeholder command.

## Run

```bash
export RAG_BASE_URL=http://127.0.0.1:8081
export RAG_API_KEY='...'

python3 examples/external-sync-client/sync_client.py apply-events \
  --events examples/external-sync-client/sample-events.jsonl \
  --checkpoint .external-sync/catalog.sqlite3
```

Validate input without credentials or HTTP calls:

```bash
python3 examples/external-sync-client/sync_client.py apply-events \
  --events examples/external-sync-client/sample-events.jsonl \
  --dry-run
```

The API key can only be read from an environment variable. It is never accepted
as a command-line argument and is not stored in the checkpoint. The checkpoint
directory is restricted to mode `0700` and the SQLite file to `0600` where the
platform supports POSIX permissions.

## Input Contract

Each JSONL line is one `UPSERT` or `TOMBSTONE` event. See
`event.schema.json`. Keep all identity fields stable and explicit:

- `collectionKey`: target Collection;
- `sourceNamespace`: one connector/source ownership space;
- `externalId`: immutable source object identity;
- `sourceRevision`: opaque revision of the desired source state;
- `expectedSourceRevision`: current revision expected by an update/delete;
- `eventId`: immutable delivery identity used for local duplicate detection.

An UPSERT is a full desired representation, not a merge patch. Reusing an
`eventId` with different content is rejected. The input file itself is also
immutable after checkpointing; use a new file and checkpoint path for the next
delivery batch.

Use `embeddingPolicy=ASYNC` for normal synchronization. A successful document
mutation can still be temporarily `INDEXING` or `FAILED`; monitor document
lifecycle or Collection readiness separately.

For the complete integration algorithm and retry/error guidance, see:

- `docs/external-document-sync-client-guide.md`
- `docs/external-document-sync-client-guide-zh-CN.md`
