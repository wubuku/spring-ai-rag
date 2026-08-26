# Business Service Integration Guide

> [English](business-client-integration.md) | [中文](business-client-integration-zh-CN.md)

This guide covers production backend-to-backend integration with
spring-ai-rag: credentials, Collections, JSON Records, retries, upgrades, and
acceptance. See the
[External Document Sync Client Guide](external-document-sync-client-guide.md)
for the full source-synchronization algorithm.

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

Collection is the current authorization boundary. `sourceNamespace` isolates
external identity but does not restrict which records a credential may access
inside a Collection. Browsers, mobile apps, and untrusted clients must not hold
business credentials.

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

At service startup, call:

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

1. An operator uses environment root to create the target Collection.
2. The operator creates a restricted business principal with a unique name,
   expiry, RPM, `allowedCollectionKeys`, and explicit `capabilities`.
3. Receive the raw credential once and immediately store it in a secret
   manager.
4. The business service reads it only from an environment variable, mounted
   secret, or equivalent secret provider.
5. At startup, call `/auth/me` and compare the principal, capabilities, and
   allow-list exactly.
6. Probe the target Collections by key before consuming business events.
7. Run the contract gate in section 8 after deployment.

Provisioning currently has no idempotency key. After a create timeout, do not
blindly create another principal. Reconcile by stable name and target binding
in the operator management plane. If the principal exists but the raw
credential was not stored safely, rotate its current credential instead of
creating an orphan principal.

### 4.1 Deployed Binding Preflight

For a deployed instance, use
`scripts/business-client-binding-preflight.sh` as a caller-side binding gate.
It does not require root and does not create Collections or principals.

The default execution mode is read-only, while the default credential profile
remains `READ_WRITE` for compatibility. Execution mode says whether the
preflight mutates data; credential profile says which authority the caller
should hold. The preflight verifies readiness, OpenAPI `1.0.0`, required
operations, `/auth/me`, the capability profile, exact restricted allow-list
equality, and the active state of every expected Collection:

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
has verified the profile. It otherwise contains only low-sensitivity labels,
counts, versions, status, and failure categories. It does not contain
credentials, URLs, Collection keys, external IDs, payloads, or response
bodies. Treat a failed preflight as a binding failure; do not continue
delivery or weaken the checks to make a deployment pass.

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

Rotation is initiated by an operator using root. The new raw credential is
again shown once; the old credential becomes invalid immediately, while the
new credential preserves `principalId` and policy. Distribute the new secret
safely, roll all instances, and verify `/auth/me` before ending the old
deployment. Treat revocation and expiry as terminal errors, not unbounded retry
conditions.

## 7. Deployment, Upgrade, And Rollback

- Use `/actuator/health/liveness` for liveness and
  `/actuator/health/readiness` for readiness. The readiness group represents
  process/Spring readiness and database availability; it does not promise that
  the external embedding provider or a Collection is retrieval-ready.
- Read document lifecycle or
  `/api/v1/rag/collections/embedding-readiness` for embedding availability.
  Use `/auth/me` plus Collection by-key probes for business binding.
- Empty and upgraded databases must run Flyway V1-V49 in order. V49 adds
  operation capabilities to stable principals; existing principals default to
  full read/write and may later be narrowed through policy CAS.
- Pin production callers to an accepted Git commit or an immutable image built
  from it. Maven/API version remains `1.0.0`.
- The added `/auth/me` fields remain backward-compatible. Older clients ignore
  them; clients that depend on capability/ACL verification must run the
  contract gate before rollout.
- V49 is a forward-compatible additive migration; do not destructively roll
  back its schema. If the application is rolled back to a version that does not
  understand operation capabilities, retain the V49 schema and stop clients
  that require a read-only boundary rather than starting permissively.

## 8. One-Command Integration Acceptance

Full gate:

```bash
./scripts/verify-business-client-readiness.sh
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

The full gate runs focused backend tests, three isolated PostgreSQL integration
matrices, `mvn clean compile test-compile`, WebUI typecheck/Vitest/production
build, core Mock Playwright, documentation/lock/secret/diff gates, and then a
real Spring Boot service, the HTTP contract including deployed binding
preflight, and real API-key Playwright. The HTTP contract proves that a
read-only query principal can lookup/search but cannot upsert/delete and that
the rejected writes leave revision/state unchanged; a read/write dispatcher
continues to own mutations, and rotation preserves capabilities. It also runs
a representative tenant/shared topology: one query principal is bound to both
Collections, separate dispatchers cannot cross-write, another tenant remains
inaccessible, scoped searches merge deterministically, sanitized projections
can be rebuilt into a browser-safe DTO, and query rotation preserves both
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
path, latest Flyway migration, passed steps, PostgreSQL image, and HTTP
contract-check count, plus the verified `READ_ONLY` and `READ_WRITE` profiles.
Runtime facts that were not reached are JSON `null`; the manifest stores no
credential, URL, payload, external ID, or private path. The deterministic
embedding stub verifies the real Spring AI embedding HTTP path and the 503
failure-preservation contract. This capability does not change Chat, so the
gate does not call a Chat LLM.

## 9. Current Limitations

- Principal provisioning has no idempotency key.
- There is no separate runtime capability-discovery endpoint. Compatibility
  is pinned through OpenAPI, a Git commit, and the offline
  `release-manifest.json`.
- The identity system is environment root plus database business principals;
  it does not provide OAuth/OIDC federation or an independent tenant hierarchy.

See the [TODO](TODO.md#managed-api-principal-follow-ups) for these follow-up
boundaries.
