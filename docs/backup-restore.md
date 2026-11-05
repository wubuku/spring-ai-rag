# Data Backup And Isolated Restore

> [English](backup-restore.md) | [中文](backup-restore-zh-CN.md)

## Boundaries

`scripts/rag-backup.py` is an explicitly invoked PostgreSQL logical backup/restore tool, not cloud disaster recovery,
incremental backup, PITR or secret management. Normal startup never exports data. Original uploaded bytes reside in
`fs_files.content_bin`; documents, vectors, evidence snapshots/history, permissions, tasks and deletion state share
one database snapshot. Temporary PDF conversion directories need no separate backup. External Resource URI files,
environment configuration and model credentials are excluded and must be supplied by the target deployment.

Backups **contain sensitive business data**, potentially including sensitive content stored in business tables;
they are not anonymized artifacts. The tool does not package `.env`, logs or external model configuration files.
Directories are 0700 and files 0600. Use trusted encrypted storage and access controls; checksums prove integrity,
not authenticity. Never commit backups or restore untrusted SQL/archives.

### Collection export is not a backup

`GET /api/v1/rag/collections/by-key/export` emits only one Collection's metadata and
document content/metadata/source identity. It contains **no chunk text, embedding
vectors, audit records, permissions, or deletion state**. `POST
/api/v1/rag/collections/import` writes documents with `EmbeddingPolicy.SKIP`; call
`embed-vector-reembed` afterwards to restore retrieval. A same-database rebuild must
also change `sourceNamespace` so the import does not match the source documents by
external identity.

It is useful for migration and content-level rebuilds, but it is **not a recoverable
backup** and does not satisfy RPO/RTO. Disaster recovery always uses the full
`rag-backup.py` snapshot described here. Round-trip equivalence is verified by
`scripts/verify-collection-export-roundtrip-e2e.py` and documented in
[rest-api.md](rest-api.md#collection-export-and-import).

## Prerequisites

- Python 3.10+, `psql`, `pg_dump`, `pg_restore`; tools matching the server's major version are recommended.
- Explicit `PGHOST`, `PGPORT`, `PGDATABASE`; authenticate using `PGUSER`, securely injected `PGPASSWORD` or `.pgpass`.
  Connection URIs and `PGSERVICE` are unsupported; never put passwords in arguments, manifests or logs.
- Stop source writers and background workers before backup. `--quiesced` is an operator assertion, not permission
  for the tool to stop arbitrary services. Dump itself is consistent; quiescence makes the task/deletion recovery point clear.
- Restore only into a **new empty isolated database without connected business services**, using a controlled role
  authorized to create extensions/tables. Cluster roles/passwords are not exported; `--no-owner --no-privileges`
  delegates object ownership and privileges to the target deployment role.
  The target PostgreSQL server must already have required extensions such as pgvector installed;
  the dump does not contain server-extension binaries.

## Backup And Verification

Securely configure the libpq environment above without printing credentials:

```bash
python3 scripts/rag-backup.py backup /secure/backups/rag-20261018 \
  --ack-sensitive-data --quiesced
python3 scripts/rag-backup.py verify /secure/backups/rag-20261018
```

The parent must exist and the bundle directory must not. Artifacts are `database.dump` and version=1
`manifest.json` (Flyway version, SHA-256, size, UTC timestamp, irreversible connection identity digest).
Failures do not publish a complete manifest. Inspect incomplete directories privately; the tool never overwrites
or deletes them. A PASS result does not establish that business deletion reconciliation is complete.

## Isolated Restore And Traffic Readiness

Point the environment to the empty target, then run:

```bash
python3 scripts/rag-backup.py restore /secure/backups/rag-20261018 --ack-isolated-target
```

Restore validates archive/manifest/size/hash, refuses the source or nonempty target and never runs clean/drop/truncate.
`pg_restore --single-transaction --exit-on-error` makes SQL restoration atomic; the resulting Flyway version is checked.
Keep the target exclusive: do not permit concurrent writers between the emptiness check and restore. Validate with
the same application version, then apply newer Flyway migrations through normal upgrades. Never edit executed migrations
or relabel old data as a new schema.

**An older backup can resurrect data deleted after its recovery point.** Before production traffic, replay deletions
from the latest available deletion/retention ledger, reconcile tombstones and external authorization, and verify vectors,
original bytes and history have no forbidden residue. Without post-backup deletion records, do not claim RPO=0 or
continuous privacy deletion: keep the target isolated and state its recovery point. Never manually relabel unfinished
budget reservations as settled; use existing lease/reconciliation and verify its outcome.

Traffic gates include health/Flyway, active embedding profile, original-byte hashes, retrieval/evidence snapshots,
deleted fixtures, scoped API-key read/write and Collection isolation, history, Native/OpenAI JSON/SSE and actual
consumer contracts. Define RPO from the backup schedule and measure RTO in drills; one local run is not a production SLA.

## Backup Type And Write Consistency

- **Logical backup**: `rag-backup.py` runs `pg_dump` into a custom archive containing schema,
  data, sequences and extension declarations. It is not a physical volume snapshot,
  incremental backup or PITR. Restore applies SQL in one transaction, so a failure rolls back
  completely and never leaves a half-restored database.
- **Quiescence requirement**: the tool requires explicit `--quiesced`. It is an operator
  assertion that application writers and background workers are stopped; the tool never stops
  services itself. The database dump is an MVCC-consistent point either way; quiescing makes
  task, lease and deletion state explainable at the recovery point. **The supported entry point
  does not offer online backup**; omitting `--quiesced` is rejected.
- **Writes in progress**: if you bypass the tool and run `pg_dump` against an active database,
  the database snapshot is still point-in-time consistent and contains no torn rows (for example,
  a document row without its derived rows). Application-level tasks may nevertheless sit in an
  intermediate state (a created document whose embedding job has not settled). The restored
  deployment must let existing lease/reconciliation take over; this is not a claim of RPO=0.
  `verify-backup-restore-drill.py` proves the database-level no-torn-row property with concurrent
  writes plus a native logical snapshot, and also proves the official tool rejects non-quiesced
  backup.

## Restore Verification Checklist

`verify-backup-restore-drill.py` asserts the following on real PostgreSQL + pgvector:

1. **Table-by-table content**: row counts and content digests for every public table (documents,
   chunks, vectors, Collections, evaluation suite/version/run/case result, feedback,
   `fs_files`/import batch, idempotency operations, sync history, failed jobs, processing
   run/stage, audit log) match exactly;
2. **Migration history**: `flyway_schema_history.installed_rank` starts at 1 and is contiguous,
   with the same row count;
3. **Vector index**: the HNSW index exists, `indisvalid/indisready` are true, and a real
  1024-dimension `EXPLAIN` uses an index scan rather than a sequential scan;
4. **Serviceability**: the same application version starts on the restored database and passes
   health, search, chat, document-by-id and feedback-history calls;
5. **Restore boundary**: the target is a fresh empty database, distinct from the source, and
   the source is never modified during restore.

## Deletion Completeness And Cascade Scope

`verify-deletion-integrity.py` deletes each family on a real database and scans the whole
schema for orphans:

| Deleted object | Cascaded or retained |
|----------------|----------------------|
| Local document hard delete | chunks, `rag_embeddings` and vector columns, versions, embedding/local index state, embedding jobs, processing run/stage, feedback/chat source references, idempotency receipts, repair ledger (cascade or reference cleanup) |
| External document source delete | source tombstone (`enabled=false`), immediately invisible to retrieval; physical removal is done by Collection purge |
| Collection permanent purge | documents, chunks, vectors, feedback, audit rows, idempotency operations, repair previews, **sync run/item**, operation slots; the Collection row remains as a purged tombstone |
| Feedback | `rag_user_feedback_document` cascades |
| Evaluation suite | version → run → case result cascade |
| Upload file / import batch | deleting the `fs_files` entry file cascades the batch pointing at it; deleting a batch does not delete the file body (file library is independent) |

Known non-deletion items and retention policy:

- `rag_audit_log`: Collection purge removes audit rows scoped to that Collection; other audit
  rows follow the existing retention policy and are not removed by a single-document delete.
- `fs_files`: uploaded files belong to the file library. Collection purge and import-batch
  deletion never guess file ownership from paths. Delete files explicitly when required
  (there is no public deletion API today; the audit script uses controlled SQL to verify the
  FK direction).
- Sync history run/item: the item's `document_id` is set to NULL after hard document deletion
  (source sync trail retained); Collection purge deletes the whole run and its items.
- `rag_document_relocated_addresses.document_id` is set to NULL, retaining the address marker.

Rebuild with the same id / idempotency key / lease: explicitly reusing a deleted document id
does not bring back old chunks or vectors; reusing an `Idempotency-Key` after its target
document was deleted clears the stale receipt and creates a new document (no permanent 409);
a failure in any item of a batch delete rolls back the whole transaction, so retry converges
without a half-deleted state.

## Reproducible Isolated Drill

```bash
mvn clean compile test-compile
python3 scripts/test-support/rag-backup-self-test.py
python3 scripts/verify-dependency-recovery-e2e.py
python3 scripts/verify-backup-restore-drill.py   # real data + pg_dump/pg_restore single pass
python3 scripts/verify-deletion-integrity.py     # per-family delete + orphan/rebuild/atomicity audit
```

`verify-backup-restore-drill.py` seeds real documents, vectors, evaluation/feedback/file/task
data through the demo with the mock provider, quiesces the app, runs a full backup via
`scripts/rag-backup.py`, restores into a second fresh database, compares row counts and content
digests for every table, checks Flyway continuity and the HNSW `EXPLAIN` plan, then starts the
app on the restored database and runs health, search and chat. It drops all temporary databases
on exit; `--keep` retains them. Temporary databases use the `rag_backup_drill_*` prefix.
`verify-deletion-integrity.py` uses `rag_delete_drill_*` and covers per-family deletion,
whole-database orphan scans, same-id/idempotency rebuild, batch failure rollback and capacity
counter recovery.

The drill creates only its own PostgreSQL container/databases, uses controlled upstreams, actually dumps/restores,
compares all table contents and checks files/vectors/permissions/deletions and external HTTP. It also exercises faults,
mixed load, hard/graceful restart and negative restore cases. Evidence is untracked under `.verification/dependency-recovery/`.
Owned processes and container are cleaned automatically. This proves bounded recovery for this build, not real-model
quality, off-site backup reachability or cloud failover.

See the [Testing Guide](testing-guide.md) for other gates. The older `verify-recovery-readiness.sh` still establishes
migration/governance readiness only and cannot replace this drill.

## Post-Restore Deletion And Authorization Gate

Backup-tool PASS never authorizes traffic: output adds `trafficReleaseAuthorized=false`.
`rag-recovery-gate.py` is an automated read-only check, not database mutation or network isolation.
Quiesce the latest authoritative source, use explicit libpq variables and an existing0700 private parent:

```bash
python3 scripts/rag-recovery-gate.py checkpoint /secure/recovery/latest.json --ack-quiesced
# Switch libpq to the isolated restored database; obtain the digest through a trusted independent channel:
python3 scripts/rag-recovery-gate.py verify /secure/recovery/latest.json \
  --backup-directory /secure/backups/rag-20261018 \
  --checkpoint-sha256 "$TRUSTED_CHECKPOINT_SHA256" --ack-authoritative-checkpoint
```

The non-overwriting0600 file contains identity/security-state SHA256 fingerprints, schema/inventory version and UTC
time, not source text or keys. A digest is not a signature: retain it independently, never manufacture authority by hashing
an unknown file afterward. The checkpoint remains sensitive and correlatable; never commit it.
Checks require matching backup source/schema, a different target, complete inventory, no incomplete/failed erasure,
and a checkpoint newer than the backup. Default freshness is one hour; `--max-age-seconds` permits at most86400,
with30-second clock skew. Enlarging the window weakens freshness. Source/target each use one read-only REPEATABLE
READ transaction; more than100000 entries/table fails rather than truncates.

Inventory v1: document revision/hash/lifecycle, Collection, principal/credential policy, chat history/summary/turn
operations, retrieval logs, LLM usage and Evaluation suites, with descendants relying on existing FK cascades.
Authorization expires/retire deadlines remain; last_used/alert scanning and other non-policy activity are ignored.
PREVIEWED/APPLYING/PARTIAL_FAILED erasure blocks the gate; newly created erasure UUIDs are not compared.
Originals/vectors, external files, environment-root/legacy credentials and other tables still need existing recovery/API/configuration checks.

Restored records cannot exceed authority or differ in security state. Records added after backup may be absent, so this
is not RPO=0. Failure exits nonzero with table-level counts only. Reconcile through existing delete/revoke/erasure APIs,
quiesce and verify again; do not forge SQL state around business fences. Keep isolated without trusted recent records.
Gate PASS only establishes checkpoint-time inventory consistency: `trafficReleaseAuthorized=false` remains and
does not replace runtime/API/external-resource checks or subsequent revocations.
See [Quality And Production Acceptance](quality-production-acceptance.md) for the complete rehearsal.
