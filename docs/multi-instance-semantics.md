# Multi-Instance Semantics

The project runs as a single instance by default, but the `postgresql` profile supports
multiple application replicas sharing one PostgreSQL. This document describes which state
is shared across replicas, which stays process-local, and what the deployment templates do
when `replicas > 1`. See the [configuration reference](configuration.md) for settings and
[DEPLOYMENT.md](DEPLOYMENT.md) for deployment steps.

## What Is Shared and What Is Not

| Capability | Coordination | Multi-replica behavior |
|------------|--------------|------------------------|
| Embedding jobs | Conditional-write claim + lease | One replica claims a job; expired leases can be taken over; the old owner is fenced |
| External document sync | Database unique index | One `ACTIVE` run per collection + namespace at a time |
| Chunking auto-upgrade | Claim + lease + CAS | One replica upgrades a document; an expired lease can be recovered |
| Chat sessions | `rag_chat_session_lease` | One request commits per session; an expired lease is taken over after a crash |
| SSE cross-instance resume | `rag_chat_turn_operations` snapshot + `Last-Event-ID` | A keyed stream resumes on any replica by event id; an unkeyed live stream is single-instance only |
| API rate limits | Shared PostgreSQL buckets | Counters are shared, so quotas are not multiplied by replica count |
| LLM budget | Database conditional writes | Two replicas share one window and never double-spend |
| HNSW index startup | Read-only build progress observation | Built once; an in-flight index owned by another replica is never dropped |
| Background cleanup | Bounded idempotent deletes + session lease | May fire on several replicas, but every row is deleted once |
| Admission concurrency | In-process semaphore | **Per replica**; total concurrency is roughly replicas × configured limit |

In short: durable jobs, leases, quotas, and cleanup effects converge through PostgreSQL.
In-process caches, the Admission pool, and `rate-limit.backend=local` do not share state
across replicas.

## Indexing and Document Work

### Embedding Jobs

`EmbeddingJobRepository.claim` uses a conditional update: only one replica can move a job
from `QUEUED` to `RUNNING`. The worker holds a lease with an expiry time, so another replica
can take over after the lease expires when the owner crashes or stops renewing. Writes and
heartbeats from the old owner are rejected by owner-token fencing, so they cannot overwrite
the new owner's progress.

### External Document Synchronization

A partial unique index on `rag_document_sync_runs` allows only one `status = 'ACTIVE'` run
per `collection_id + source_namespace`. A second replica cannot insert another active run;
it can only reuse or wait for the existing one. Upserts and deletions therefore do not run
twice across two instances.

### Chunking Upgrade

The automatic upgrade job coordinates through claims and a 120-second lease on
`rag_chunking_upgrade_tasks`. Every replica may scan, but only one replica can move a
`document_id + embedding_profile_id` task to `RUNNING`; another replica can retry after the
lease expires, and the old owner's completion write is rejected by the conditional update.

### HNSW Index Startup

Every replica calls `EmbeddingProfileIndexManager.ensureIndex` during startup. The previous
implementation dropped and recreated the index immediately when `CREATE INDEX CONCURRENTLY`
conflicted, which could remove an index another replica was building and make replicas
interrupt each other.

The current implementation relies on observation, not locks:

- Index exists and is valid: return immediately;
- Index missing: try `CREATE INDEX CONCURRENTLY IF NOT EXISTS`;
- Index exists but invalid: only treat it as a crash remnant after four consecutive
  observations (250 ms by default) of "invalid with no build progress", then drop and rebuild;
- `pg_stat_progress_create_index` shows another replica building: wait for it, up to 60 seconds.

A rolling scale-out or a simultaneous restart therefore does not repeat a full HNSW build
and does not delete an in-flight index.

## Background Cleanup

`DataGovernanceRetentionCoordinator` triggers retention cleanup on every replica at the
same cron. The project has no singleton scheduler that runs on only one replica; multi-replica
correctness depends on conditional deletes in each cleanup:

- Audit, client-error, evaluation, LLM usage, retrieval diagnostics, integration observability,
  and budget history cleanup delete bounded batches by key; when they run concurrently, one
  transaction deletes a row and the other observes zero rows;
- Chat history TTL cleanup takes a maintenance lease per session and then deletes bounded
  batches; active sessions are skipped;
- Idempotency-record cleanup also uses bounded conditional deletes;
- Rate-limit bucket cleanup locates rows by `ctid` with a batch limit, so repeated execution
  is idempotent.

Cleanup is therefore "possibly triggered twice, idempotent in effect", not "executed once
globally". If operations need strict singleton scheduling, enable scheduling only on one
replica at the deployment layer (for example, a dedicated worker deployment) instead of relying
on in-application mutual exclusion.

## Quotas and Rate Limits

### API Rate Limits

`rag.rate-limit.backend=postgresql` uses
`INSERT ... ON CONFLICT DO UPDATE ... WHERE request_count < limit RETURNING` on
`rag_api_rate_limit_bucket`: all replicas share one fixed UTC minute window per principal,
so accepted requests do not become replica count × `requests-per-minute`. Store failures
fail closed with `503` instead of falling back to local counting.

`rag.rate-limit.backend=local` counts inside one process and is suitable only for explicit
single-instance environments. When multi-instance mode is declared and both
`rate-limit.enabled=true` and `backend=local` are set, the application refuses to start.

### LLM Budget

Budget windows and reservations are updated through database conditional writes, so two
`BudgetReservationService` instances share the same window. Concurrent reservation, settlement,
and expiry reclamation never double-spend; see [LLM budget settlement safety](budget-settlement-safety.md).

### Admission Concurrency

`AdmissionService` is an in-process concurrency pool, not a shared quota. With N replicas,
global concurrency is roughly `N × configured limit`. Lower the per-replica limit or control
concurrency at the gateway when a strict global limit is required; this is not a cross-instance
shared capability.

## Chat Sessions

Chat requests for the same `owner_principal_id + session_id` are serialized through
`rag_chat_session_lease`:

- The first replica acquires the lease with a conditional write; a concurrent request on
  another replica gets `SESSION_BUSY` (HTTP 409) and reads or writes no history for that session;
- On success, history and memory commit in one transaction, then the lease is released;
- After a replica crash, the lease expires and another replica can take over; the old replica's
  commit fails with `CHAT_SESSION_LEASE_LOST` because the lease token no longer matches, so a
  late result cannot land in the new owner's history.

Concurrent chat on two instances for one session therefore neither mixes nor loses history;
the losing request receives an explicit conflict error.

## SSE Cross-Instance Resume

A chat stream sent with `Idempotency-Key` (`/api/v1/rag/chat/stream`) **supports cross-instance
resume**:

- The server persists the full turn result and event snapshot in `rag_chat_turn_operations`
  before emitting events. A client disconnect mid-stream does not discard the snapshot or
  cancel the server-side keyed turn;
- A client that sends the same `Idempotency-Key` and a `Last-Event-ID` to another replica gets
  only events with an id greater than the cursor, plus `X-RAG-Idempotent-Replay: true`;
- A malformed or missing `Last-Event-ID` degrades to a full replay instead of an empty stream.

A live stream **without** `Idempotency-Key` is **single-instance only**: it relies on the
emitter inside the originating process. `Last-Event-ID` is ignored and a request on another
replica starts a new turn and generates a full response. That is deterministic behavior, not
silent frame loss; clients that need cross-instance resume must use keyed turns. An unkeyed
stream cannot resume if the originating replica crashes mid-stream.

`verify-multi-instance-e2e.py` disconnects a keyed stream on A, resumes it on B with
`Last-Event-ID`, and asserts that "first segment + resume = full replay" with no duplicate ids;
it also verifies that an unkeyed stream starts a fresh full turn on B.

## Crash Recovery

Every durable state that needs takeover uses an "expired means reclaimable" lease rather than
a permanently stuck lock:

- Embedding jobs: another replica reclaims after the lease expires;
- Chunking upgrade: a `RUNNING` task with `lease_expires_at < NOW()` is returned to `RETRY/COOLDOWN`;
- Chat sessions: an expired lease is taken over by the next request;
- Invalid HNSW indexes left by a failed replica: another replica deletes and rebuilds them after
  confirming there is no build progress;
- Alert delivery, collection purge, and embedding-profile backfill workers also use claims/leases
  with idempotent retries.

No manual SQL unlock is required; recovery happens on the next poll or the next request.

## Deployment Templates

### Helm

`k8s/values.yaml` defaults to `replicaCount: 1` but sets `autoscaling.enabled: true` with up to
5 replicas; `k8s/values-production.yaml` uses 3 replicas and an HPA of 3–10. The templates therefore
already expose a `replicas > 1` path, so the project must not be treated as single-instance only.

`k8s/templates/deployment.yaml` injects the following when `replicaCount > 1` or autoscaling is
enabled:

```yaml
env:
  - name: RAG_MULTI_INSTANCE_ENABLED
    value: "true"
```

The Deployment uses the `Recreate` strategy: multi-replica is supported in steady state, but an
upgrade stops all old Pods first and provides no rolling zero-downtime semantics. Production values
also configure a `podDisruptionBudget` (`minAvailable: 2`) to protect availability during node eviction.

### Docker Compose

`docker/docker-compose.yml` defines one application container and has no replica or scaling path.
Multi-instance semantics still hold with Compose, but require external orchestration (multiple
containers, one database, and a load balancer in front); the Compose file does not provide it.

## Startup Assertion

`rag.multi-instance.enabled` declares that this process shares one PostgreSQL with other replicas.
When enabled, startup rejects local rate-limit configurations that replica count would multiply:

```yaml
rag:
  multi-instance:
    enabled: ${RAG_MULTI_INSTANCE_ENABLED:false}
```

The rejected combination is `rag.rate-limit.enabled=true` with `rag.rate-limit.backend=local`.
The error tells the operator to switch to `backend=postgresql` and `strategy=principal` instead
of silently multiplying the quota by N.

## Verification

Multi-instance semantics are verified by gated PostgreSQL suites that are not part of `mvn test`.
Those suites create two independent component instances in one JVM (two stores, two cleanup
services, two chat coordinators, two index managers) and exercise the database coordination
layer directly; two real processes on different ports are covered by
`scripts/verify-multi-instance-e2e.py` and `scripts/verify-managed-api-principals.sh`.

| Command | Coverage |
|---------|----------|
| `./scripts/verify-multi-instance-e2e.py` | Two real processes (18086/18087 by default) on one PostgreSQL: concurrent Flyway startup applies migrations once, keyed SSE `Last-Event-ID` resume across instances with no duplicates or gaps, unkeyed stream generates a fresh full turn, same idempotency key concurrently produces one side effect, two workers consume one job batch without double writes, SIGKILL followed by lease-expiry takeover, writes on A immediately visible on B, revoked credentials rejected immediately, and shared rate limits not multiplied by replicas |
| `./scripts/verify-gated-it.sh MultiInstancePostgresIntegrationTest` | Shared rate-limit quota, concurrent TTL cleanup, concurrent HNSW startup, crash-remnant repair |
| `./scripts/verify-gated-it.sh ChatSessionPostgresIntegrationTest` | Two instances racing for one session, crash lease takeover, old-owner fencing |
| `./scripts/verify-gated-it.sh EmbeddingJobsPostgresIntegrationTest,DocumentSyncRunsPostgresIntegrationTest` | Embedding claim/lease and a single active run per namespace |
| `./scripts/verify-gated-it.sh LlmBudgetAndGovernancePostgresIntegrationTest` | Two instances sharing one budget window without double-spending |
| `./scripts/verify-managed-api-principals.sh` | Two real backends (18181/18182 by default) sharing one disposable PostgreSQL to verify cross-instance quota and identity contracts |

The nightly gated layer runs every suite; see the [testing guide](testing-guide.md) for switches
and the suite inventory.

## Boundaries and Limitations

- Cleanup scheduling fires on every replica; the project guarantees idempotence, not singleton execution.
- An SSE live stream without `Idempotency-Key` is not resumable across instances; another replica starts a new turn.
- The Admission concurrency pool is per-replica behavior, not a global quota.
- Concurrent replica startup waits up to 60 seconds for an HNSW build; exceeding the limit fails
  startup instead of waiting forever.
- The Helm `Recreate` strategy does not provide zero-downtime rolling upgrades; design compatible
  migrations and a rolling strategy separately when zero downtime is required.
- Flyway runs migrations on every replica at startup and serializes them with its migration lock;
  do not hand-edit executed migrations.
- These semantics assume the `postgresql` profile; do not assume cross-replica sharing with other
  storage backends.
