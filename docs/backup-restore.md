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

## Reproducible Isolated Drill

```bash
mvn clean compile test-compile
python3 scripts/test-support/rag-backup-self-test.py
python3 scripts/verify-dependency-recovery-e2e.py
```

The drill creates only its own PostgreSQL container/databases, uses controlled upstreams, actually dumps/restores,
compares all table contents and checks files/vectors/permissions/deletions and external HTTP. It also exercises faults,
mixed load, hard/graceful restart and negative restore cases. Evidence is untracked under `.verification/dependency-recovery/`.
Owned processes and container are cleaned automatically. This proves bounded recovery for this build, not real-model
quality, off-site backup reachability or cloud failover.

See the [Testing Guide](testing-guide.md) for other gates. The older `verify-recovery-readiness.sh` still establishes
migration/governance readiness only and cannot replace this drill.
