# Business Service Integration Guide

> [English](business-client-integration.md) | [中文](business-client-integration-zh-CN.md)

This guide covers production backend-to-backend integration with
spring-ai-rag: credentials, Collections, JSON Records, retries, upgrades, and
acceptance. See the
[External Document Sync Client Guide](external-document-sync-client-guide.md)
for the full source-synchronization algorithm.

## Production Integration Fast Path

For an independent backend service integrating with the RAG data plane, the
following sequence covers the production launch blockers:

1. Pin the exact source commit or immutable image that passed the integration
   acceptance gate, and record it with the deployment.
2. Start the service and run Flyway using the
   [Configuration Reference](configuration.md) and
   [Deployment Guide](DEPLOYMENT.md). Use the environment root only for
   operator actions.
3. Have an operator create stable `collectionKey` values, then use
   [API key management](rest-api.md#api-keys--key-management) to create
   restricted business principals for query and dispatch responsibilities
   with exact `capabilities` and `allowedCollectionKeys`.
4. At startup, every business-service instance must discover
   `/integration-capabilities`, then run `/auth/me` and the Collection by-key
   probes, or run the
   [deployed binding preflight](#41-deployed-binding-preflight). Fail closed on
   any mismatch; never fall back to root, a legacy key, or an unrestricted
   principal.
5. Compile authoritative business objects into allow-listed JSON projections.
   Use a stable identity triple and opaque revisions, and express complete
   desired state through
   [JSON Record](rest-api.md#json-structured-records--jsonb-payload-retrieval)
   upserts and tombstones.
6. Use `embeddingPolicy=ASYNC` for delivery by default. Check
   lifecycle/readiness before semantic retrieval is required; mutation success
   does not mean embedding is complete.
7. Query with explicit Collection scope and business `payloadContains`
   filters. Treat RAG hits as candidates, reread authoritative entities and
   permissions, then build a client-safe DTO.
8. Run [one-command integration acceptance](#8-one-command-integration-acceptance)
   before launch, and rerun binding preflight after deployment or credential
   rotation.

See the [REST API Reference](rest-api.md#authentication) for complete request
and response contracts and the
[Developer Reference](developer-reference.md#business-service-integration-readiness-verification)
for copyable commands.

## 1. Authority Boundaries

The RAG service owns:

- persisted Collections, documents, JSONB payloads, derived indexes, and
  embedding jobs;
- API principals, credentials, Collection ACLs, shared quotas, rotation, and
  revocation;
- CAS, exact replay, and tombstones at
  `collectionKey + sourceNamespace + externalId`;
- lifecycle/readiness decisions for current document searchability.

The caller owns:

- source business objects, stable external IDs, complete desired state, and
  opaque `sourceRevision` values;
- secure credential storage and deployment binding;
- network retries, checkpoints, dead letters, and reread/manual repair after
  `409`;
- deciding when embedding readiness is required. Mutation success does not
  imply `READY`.
- projecting only explicitly allowed retrieval fields and stable locators,
  then rereading authoritative entities, rechecking permissions, and building
  a safe DTO for the final client after retrieval.

Collection is the current authorization boundary. `sourceNamespace` isolates
external identity but does not restrict which records a credential may access
inside a Collection. Returned payloads, URLs, internal document IDs, and
`retrievalText` are not authoritative business responses that can be sent
directly to a client. Browsers, mobile apps, and untrusted clients must not
hold business credentials.

## 2. Credentials And Current Identity

### Environment root

`RAG_ROOT_API_KEY` is an operator credential. It can use the data plane and
manage database business principals. Keep it only in controlled operator
environments; do not place it in business services or persistent WebUI storage.

### Database business principal

Principals created by root are fixed to `NORMAL`, but operation capabilities
can be assigned by responsibility:

- `RAG_READ` permits GET/HEAD/OPTIONS plus read-oriented POST operations such
  as Search and Chat;
- `RAG_READ + RAG_WRITE` additionally permits upsert, delete, and other
  data-plane mutations.

Business principals cannot call API-key management endpoints. Give each
production service or connector its own restricted principal limited to the
target Collections. When read and delivery responsibilities can be separated,
use a read-only query principal and a read/write dispatcher principal so the
query service does not hold write authority.

The raw credential appears only in a create or rotate response. Store it in a
secret manager within that response boundary. The service cannot return it
again, and list/introspection responses contain neither the raw secret nor its
hash.

Send credentials only in a header:

```http
X-API-Key: <business-credential>
```

`Authorization: Bearer <business-credential>` is also supported. Never put a
credential in a query string, URL, log, command-line argument, business
payload, or browser storage. Root mode rejects `?apiKey=`.

### Introspection and binding

At service startup, first call:

```text
GET /api/v1/rag/integration-capabilities
```

Require protocol `spring-ai-rag-integration` version `1.0`, then verify that
the required provisioning/data-plane features and limits are present. The
response is low-sensitivity and projected for the current caller; it does not
replace identity binding. A client using authoritative snapshots must require
`features.optional.documentSyncRuns=true`; response-loss recovery,
failed-item lookup, or terminal audit additionally requires
`features.optional.documentSyncRunItemReceipts=true`. Older clients must
ignore unknown optional fields and must not treat a missing field as enabled.
Automated control-plane setup that retries Collection creation must require
`features.provisioning.collectionCreateIdempotencyKey=true`.
The durable receipt endpoint is
`GET /api/v1/rag/document-sync-runs/{runId}/items`; see the
[External Document Synchronization Client Guide](external-document-sync-client-guide.md#7-authoritative-snapshot-reconciliation)
for recovery and terminal-rescan behavior.

Then call:

```text
GET /api/v1/rag/auth/me
```

Core response for a restricted principal:

```json
{
  "principalType": "DATABASE_API_KEY",
  "principalId": "rag_p_example",
  "rootMode": true,
  "capabilities": ["RAG_READ", "RAG_WRITE"],
  "credentialId": "rag_k_example",
  "credentialVersion": 1,
  "policyVersion": 1,
  "principalRole": "NORMAL",
  "collectionAccessMode": "RESTRICTED",
  "allowedCollectionKeys": ["customer-42:records:v1"]
}
```

`rootMode` says whether the server has an environment root configured; it does
not say the current credential is root. Check `principalType` first, then the
expected `principalId`, credential/policy versions, `capabilities`,
`collectionAccessMode`, and complete allow-list. Require the exact capability
set for the deployment responsibility rather than checking only for one
contained value. An unrestricted principal returns
`collectionAccessMode=UNRESTRICTED` and `allowedCollectionKeys=null`.

Introspection describes policy only. Probe each expected key with
`GET /api/v1/rag/collections/by-key?collectionKey=...` to confirm that the
Collection currently exists and is active. If the ACL cannot be resolved
completely, introspection returns `503`; binding must fail closed rather than
accepting a partial allow-list. Responses always include
`Cache-Control: no-store`.

## 3. Stable Identity And Limits

| Field | Rule |
|---|---|
| `collectionKey` | 1-128 visible ASCII `0x21..0x7e` characters; case-sensitive, globally unique, immutable after creation, and reserved after soft deletion |
| `sourceNamespace` | Omitted or blank becomes `default`; an explicit value is trimmed, limited to 128 characters, and restricted to `0x20..0x7e` |
| `externalId` | Non-blank after trimming and at most 255 characters; remains opaque/Unicode and comes from a stable immutable source ID |
| `sourceRevision` | Caller-supplied non-empty opaque complete-state version, at most 255 characters after trimming; never compare it numerically or lexically |
| `expectedSourceRevision` | Optional CAS precondition; when non-blank it is at most 255 characters after trimming |

JSON Records separate:

- `retrievalText`: natural-language text used for chunking, keyword indexing,
  and embeddings;
- `jsonbPayload`: structured data returned as JSON and filtered with PostgreSQL
  JSONB containment through `payloadContains`.

A payload-only change does not call the embedding provider. A
`retrievalText` change updates derived state.

## 4. Provisioning And Deployment Binding

Recommended sequence:

1. An operator uses environment root to create each target Collection with a
   caller-generated `Idempotency-Key`, reusing it until the logical command
   receives a definitive result.
2. The operator creates a restricted business principal with a unique name,
   expiry, RPM, `allowedCollectionKeys`, explicit `capabilities`, and a
   caller-generated `Idempotency-Key`.
3. Receive the raw credential once and immediately store it in a secret
   manager.
4. The business service reads it only from an environment variable, mounted
   secret, or equivalent secret provider.
5. At startup, call `/auth/me` and compare the principal, capabilities, and
   allow-list exactly.
6. Probe the target Collections by key before consuming business events.
7. Run the contract gate in section 8 after deployment.

For Collection creation, the first keyed success returns `201`; an exact
same-owner replay returns `200` plus `X-RAG-Idempotent-Replay: true` and the
Collection's current state. A different effective request with the same
owner/key returns `409 IDEMPOTENCY_KEY_REUSED`. Replay after Collection update
or soft deletion returns the current state and never restores the resource.
Ledger failure returns `503` instead of falling back to an unsafe create.

Principal creation uses the same header discipline but has a distinct ledger
and shown-once-secret contract. Its first success returns `201` and the raw
credential once; an exact retry returns `200`,
`X-RAG-Idempotent-Replay: true`, and `rawKey=null`. If the first response was
lost, replay can confirm the principal but cannot recover the raw secret;
rotate the current credential instead of creating an orphan principal. For
both endpoints, reuse a key only with the exact same normalized request. The
default replay guarantee is the configured 400-day retention window, so
callers must not reuse an old key after that window.

### 4.1 Deployed Binding Preflight

For a deployed instance, use
`scripts/business-client-binding-preflight.sh` as a caller-side binding gate.
It does not require root and does not create Collections or principals.

The default execution mode is read-only, while the default credential profile
remains `READ_WRITE` for compatibility. Execution mode says whether the
preflight mutates data; credential profile says which authority the caller
should hold. The preflight verifies readiness, OpenAPI `1.0.0`, required
operations, `/integration-capabilities`, `/auth/me`, the capability profile,
exact restricted allow-list equality, and the active state of every expected
Collection:

```bash
RAG_BINDING_BASE_URL=https://rag.example \
RAG_BINDING_CREDENTIAL_FILE=/run/secrets/rag-credential \
RAG_BINDING_EXPECTED_COLLECTIONS_FILE=/etc/rag/collections.json \
RAG_BINDING_TARGET_LABEL=production-a \
RAG_BINDING_EXPECTED_CAPABILITY_PROFILE=READ_ONLY \
  ./scripts/business-client-binding-preflight.sh
```

`RAG_BINDING_EXPECTED_CAPABILITY_PROFILE` accepts only:

- `READ_ONLY` → exactly `["RAG_READ"]`;
- `READ_WRITE` → exactly `["RAG_READ","RAG_WRITE"]`, also the compatibility
  default when omitted.

The credential file must be a regular file readable only by its owner and
contain exactly one current `rag_sk_<64 lowercase hex characters>` credential.
The Collection file is a JSON array of 1-100 unique visible-ASCII keys. The
runner rejects query credentials, user-info, redirects, non-loopback HTTP, and
insecure TLS bypasses. It accepts `X-API-Key` by default; set
`RAG_BINDING_AUTH_SCHEME=BEARER` to use the equivalent Bearer header.

Callers can fail closed when a deployment's runtime contract is below their
minimum batch envelope or lacks operation observability:

```bash
RAG_BINDING_MIN_JSON_BATCH_ITEMS=10 \
RAG_BINDING_MIN_JSON_BATCH_PAYLOAD_BYTES=1048576 \
RAG_BINDING_REQUIRE_OPERATION_OBSERVABILITY=true \
  ./scripts/business-client-binding-preflight.sh
```

The minimum item/payload values are optional positive integers. The
observability requirement accepts only `true` or `false`. The runner always
requires capability protocol `1.0` and valid machine-readable
structured-record limits; configured minimums add deployment-specific
constraints without changing the protocol version.

An explicitly provisioned, non-business canary Collection may opt into a
bounded mutation smoke. It requires both the mode and confirmation flag, and
the canary key must be the only expected key in that binding:

```bash
RAG_BINDING_PREFLIGHT_MODE=CANARY_MUTATION \
RAG_BINDING_CANARY_CONFIRM=YES \
RAG_BINDING_CANARY_COLLECTION_KEY=preflight-canary \
RAG_BINDING_AUTH_SCHEME=BEARER \
RAG_BINDING_EXPECTED_CAPABILITY_PROFILE=READ_WRITE \
  ./scripts/business-client-binding-preflight.sh
```

The mutation flow uses one run-scoped external identity and validates ASYNC
persistence, exact replay, readiness, `payloadContains` search, CAS `409`,
tombstone, restore, and a final tombstone. It never physically deletes the
record. If the provider fails or the process is interrupted after the initial
upsert, the exit cleanup reconciles the same identity and attempts one bounded
tombstone; it never creates a second identity.
`CANARY_MUTATION` accepts only the `READ_WRITE` profile, preventing a
read-only credential from being misconfigured for mutation acceptance.

Every run writes `preflight-report.json`, `summary.md`, and `steps.tsv` under
`RAG_BINDING_PREFLIGHT_EVIDENCE_DIR` (or the default verification directory).
The JSON report records the caller's `expectedCapabilityProfile` separately
from `principal.capabilityProfile`, which remains `null` until introspection
has verified the profile. It also records the capability protocol, verified
JSON batch limits, observability feature, and configured minimum requirements.
The report otherwise contains only low-sensitivity labels, counts, versions,
status, and failure categories. It does not contain credentials, URLs,
Collection keys, external IDs, payloads, or response bodies. Treat a failed
preflight as a binding failure; do not continue delivery or weaken the checks
to make a deployment pass.

## 5. JSON Record Mutation Contract

Use `embeddingPolicy=ASYNC` by default with
`POST /api/v1/rag/json-records/upsert`:

- omit `expectedSourceRevision` for a new address;
- send the last accepted revision as `expectedSourceRevision` on updates;
- the same revision and same complete managed content is an exact idempotent
  replay;
- the same revision with different content, or a wrong CAS value, returns
  `409`;
- mutation success guarantees the main record and durable job are committed,
  not that the embedding is fresh;
- a terminal provider failure preserves the main record, revision, payload,
  and enabled state; lifecycle reports `embeddingStatus=FAILED` without a
  second business mutation deleting or overwriting the record;
- asynchronous embedding completion can race with a following external upsert
  or tombstone while both update the document version. The service retries
  database-concurrency failures in a fresh transaction, up to three attempts;
  business revision-CAS conflicts are never retried. A caller-visible `409`
  therefore still means a real revision conflict or failure to converge within
  that bounded internal budget, and the caller should reconcile with GET as
  described below;
- source deletion uses `DELETE /json-records/by-external-id` and creates a
  tombstone;
- a later upsert with a new revision restores the same `documentId`.

When search readiness matters, read document lifecycle or Collection embedding
readiness:

| `searchability` | Meaning |
|---|---|
| `READY` | Current keyword and vector derivations are ready |
| `KEYWORD_ONLY` | Current keyword retrieval works; vectors are queued, failed, or not requested |
| `INDEXING` | Current local derivation is not ready |
| `FAILED` | Current derivation failed; use `retryable` and error codes |
| `NOT_REQUESTED` | The caller used `SKIP` |
| `DISABLED` | The document is disabled or tombstoned |

### Query, Merge, And Authoritative Reread

Retrieve structured records with
`POST /api/v1/rag/json-records/search`. New callers should send explicit
`collectionKeys` and use `payloadContains` for scope and state filters that are
safe to express in the projection. The API-key allow-list remains an
independent authorization ceiling and cannot be replaced by request scope.

Collections in one request share a global top-k, so each Collection is not
guaranteed a candidate. If distinct scopes require their own recall
opportunity or filters, issue separate bounded queries, then deduplicate,
merge, and truncate by a stable rule. Do not treat result order as business
authorization or final display order.

Use each hit only to obtain a stable locator that the caller previously
projected. The caller must batch-reread current authoritative entities,
revalidate existence, state, tenant/project scope, and user permission, then
return only an allow-listed business DTO. Silently discard stale,
unauthorized, or unresolved candidates. Never pass RAG payloads, URLs,
internal IDs, credential material, or private transport fields directly to a
browser or another untrusted client.

## 6. Retries And Credential Lifecycle

| Result | Caller action |
|---|---|
| Network timeout, `408`, `425`, `429`, `5xx` | Bounded exponential backoff with jitter; replay the exact request |
| `409` | Stop that identity, read current state, and construct a new desired mutation; never auto-overwrite |
| `400` | Contract/data error; move to a dead letter |
| `401` | Invalid, expired, rotated, or revoked credential; stop delivery |
| `403` | ACL/binding error; do not infer whether a Collection exists |

A restricted principal receives the same generic `403` before record lookup
for search, lookup, upsert, and tombstone against an unauthorized or unknown
Collection. The error envelope does not echo the target key, Collection
existence, or internal IDs.

The per-model embedding-provider retry budget is controlled by
`rag.embedding.retry-max-attempts` /
`RAG_EMBEDDING_RETRY_MAX_ATTEMPTS`, range 1-10 and default 10 for compatibility.
Only transient/network failures are retried with exponential backoff. This
budget is independent from the durable embedding-job
`default-max-attempts`/`max-attempts` budget; bound both in production.

Rotation is initiated by an operator using root. Prefer staged rotation for a
rolling deployment:

1. Discover `features.credentialRotation` and ensure `staged=true`.
2. Prepare from the current credential with a deployment-scoped
   `Idempotency-Key`. Persist the shown-once replacement secret and stable
   `rotationId` atomically in the operator workflow.
3. On timeout, replay the exact prepare with the same idempotency key. The
   replay confirms metadata but returns `rawKey=null`; never create another
   principal to compensate for a lost response.
4. Roll instances to the replacement credential and verify `/auth/me`, the
   exact Collection binding, and representative read/write probes. During the
   bounded overlap, old and new credentials share one principal and quota.
5. Complete the rotation only after every instance is healthy. Cancel before
   the deadline if rollout must be abandoned. After the deadline, the retiring
   credential fails authentication even if cleanup has not run.

The immediate `/rotate` endpoint remains available when an atomic cutover is
intentional; it invalidates the old credential immediately. Treat revocation,
principal expiry, and overlap expiry as terminal errors, not unbounded retry
conditions.

## 7. Deployment, Upgrade, And Rollback

- Use `/actuator/health/liveness` for liveness and
  `/actuator/health/readiness` for readiness. The readiness group represents
  process/Spring readiness and database availability; it does not promise that
  the external embedding provider or a Collection is retrieval-ready.
- Read document lifecycle or
  `/api/v1/rag/collections/embedding-readiness` for embedding availability.
  Use `/auth/me` plus Collection by-key probes for business binding.
- Empty and upgraded databases must run Flyway V1-V56 in order. V49 adds
  operation capabilities to stable principals; V50 adds the successful
  provisioning idempotency ledger without storing raw credentials; V51 adds
  unfiltered and status-filtered keyset indexes for Sync Run item receipts;
  V52 adds a separate owner-scoped Collection-create idempotency ledger; V53
  adds the model-invocation usage ledger; V54 adds bounded hourly integration
  operation and authorized Collection-contribution rollups; V55 adds bounded
  staged credential rotation and its secret-free operation ledger; V56 adds
  permanent Collection-retirement tombstones, Chat/feedback document-reference
  indexes, and durable purge previews.
- Pin production callers to an accepted Git commit or an immutable image built
  from it. Maven/API version remains `1.0.0`.
- The added `/auth/me` fields remain backward-compatible. Older clients ignore
  them; clients that depend on capability/ACL verification must run the
  contract gate before rollout.
- V49 through V56 are forward-compatible additive migrations; do not destructively
  roll back their schema. If the application is rolled back to a version that
  does not understand operation capabilities, keyed principal/Collection
  provisioning, item receipts, usage aggregation, integration observability,
  staged rotation, or Collection retirement, retain the schema and stop clients
  that require those contracts rather than starting permissively or assuming
  the missing endpoint remains available.
- During a mixed V54/V55 fleet, freeze API-key management writes and do not
  prepare staged rotations; a V54 binary does not understand two enabled
  credential rows. Enable staged rotation only after every instance runs V55.
  Before rolling application code back to V54, verify that there are no
  enabled retiring credentials and no `PENDING` rotation operations.
- Keep `rag.collection-purge.enabled=false` during a mixed V55/V56 fleet. Older
  binaries do not understand `purged_at` and cannot enforce post-retirement
  restore/write/retrieval fences. Enable purge only after every instance runs
  V56; after any purge completes, do not roll data-plane traffic back to V55.

### Operation Observability

When `features.optional.integrationObservability=true`, a principal with
`RAG_READ` can query:

```text
GET /api/v1/rag/integration-observability
```

The default window is the latest 24 hours and the default bucket is `HOUR`.
Use `operation` and `collectionKey` to narrow a diagnosis. NORMAL principals
remain restricted to themselves and their current Collection authorization;
root and database ADMIN may select `principalId` or query the global view.

The response is a best-effort operational aggregate, not a mutation receipt,
audit trail, quota counter, provider bill, or settlement source. Each request
contributes once to totals; a multi-Collection request may also contribute to
multiple authorized Collection rows, so Collection contributions must not be
summed as request totals. Use JSON Record lookup/revision, Sync Run receipts,
and lifecycle/readiness as the authoritative recovery mechanisms.

## 8. One-Command Integration Acceptance

Full gate:

```bash
./scripts/verify-business-client-readiness.sh
```

Focused Collection provisioning reliability gate:

```bash
./scripts/verify-collection-provisioning.sh
```

Reproducible gate for a final candidate commit:

```bash
BUSINESS_CLIENT_REQUIRE_CLEAN_GIT=true \
./scripts/verify-business-client-readiness.sh
```

Rerun only the real service, HTTP, and real-frontend phase:

```bash
BUSINESS_CLIENT_VERIFY_PHASE=real \
./scripts/verify-business-client-readiness.sh
```

The full gate runs focused backend tests, four isolated PostgreSQL integration
matrices, `mvn clean compile test-compile`, WebUI typecheck/Vitest/production
build, core Mock Playwright, documentation/lock/secret/diff gates, and then a
real Spring Boot service, the HTTP contract including deployed binding
preflight, and real API-key Playwright. The HTTP contract proves that a
keyed principal create is safe across instances, never replays a secret,
reports current credential state after rotation/revocation, and exposes a
caller-projected runtime capability contract. Non-default limits of three JSON
batch items and 2048 batch bytes prove the capability values match the real
`400` enforcement boundary. The contract also queries operation/status/
Collection rollups, denies cross-principal and cross-Collection observation,
and verifies persistence across a service restart. It also proves that a
read-only query principal can lookup/search but cannot upsert/delete and that
the rejected writes leave revision/state unchanged; a read/write dispatcher
continues to own mutations, and rotation preserves capabilities. It also runs
a representative tenant/shared topology: one query principal is bound to both
Collections, separate dispatchers cannot cross-write, another tenant remains
inaccessible, scoped searches merge deterministically, sanitized projections
can be rebuilt into a client-safe DTO, and query rotation preserves both
Collection bindings without any data-plane root fallback. Client-owned
generic record-mutation envelopes are compiled by the test client into stable
hashed identities and allow-listed projections. `TENANT_PRIVATE` record
update/delete/restore and post-rotation delete, plus `SHARED_CATALOG` record
publish/revoke, all use real HTTP and prove that `privateAttachment`, URLs, and
internal event/record/fingerprint material do not enter RAG.

To accept real envelopes exported by an external client, set
`BUSINESS_CLIENT_CLIENT_ENVELOPE_DIR=<fixture-dir>`; see the lifecycle and file
requirements in the [testing guide](testing-guide.md#business-service-readiness-gate).
This is test-client input, not a dependency from RAG to the external project or
an assertion that RAG adopts the example envelope protocol or owns compilation
of the external outbox.

Defaults use isolated ports `18084`, `18085`, `15184`, and `15185` with a
disposable `pgvector/pgvector:pg16`. Evidence is written under
`.verification/business-client-readiness/<run-id>/`; exit traps delete private
credential files. `release-manifest.json` records the result, verification
phase, full Git SHA, initial tree state, project/OpenAPI versions, API base
path, latest Flyway migration, passed steps, PostgreSQL image, HTTP
contract-check count, verified `READ_ONLY`/`READ_WRITE` profiles, and the
observed JSON batch item/payload limits plus operation-observability state.
Runtime facts that were not reached are JSON `null`; the manifest stores no
credential, URL, payload, external ID, or private path. The deterministic
embedding stub verifies the real Spring AI embedding HTTP path and the 503
failure-preservation contract. This capability does not change Chat, so the
gate does not call a Chat LLM.

## 9. Current Limitations

- The identity system is environment root plus database business principals;
  it does not provide OAuth/OIDC federation or an independent tenant hierarchy.
- Capability discovery describes supported protocol behavior and current
  principal projection; `/auth/me`, Collection probes, and deployment-specific
  binding checks remain required.
- Operation observability is an hourly, best-effort aggregate. Queue/database
  failure can drop observations, the current-instance drop count is not a
  cluster-wide loss ledger, and the API does not provide per-request traces.
- The operation catalog is intentionally finite and focused on the integration
  data plane. Do not work around that boundary by adding principal IDs,
  Collection keys, dynamic URLs, or external IDs as Micrometer tags.

See the [TODO](TODO.md#managed-api-principal-follow-ups) for these follow-up
boundaries.
