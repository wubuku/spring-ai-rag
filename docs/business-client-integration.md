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

Principals created by root are currently fixed to `NORMAL`. They have
product-level `RAG_READ` and `RAG_WRITE` but cannot call API-key management
endpoints. Give each production service or connector its own restricted
principal limited to the target Collections.

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
expected `principalId`, credential/policy versions, `collectionAccessMode`,
and complete allow-list. An unrestricted principal returns
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
| `collectionKey` | 1-128 visible ASCII characters, case-sensitive, globally unique, immutable after creation, and reserved after soft deletion |
| `sourceNamespace` | At most 128 characters; omitted or blank becomes `default` |
| `externalId` | At most 255 characters and derived from a stable immutable source ID |
| `sourceRevision` | Caller-supplied non-empty opaque complete-state version; never compare it numerically or lexically |
| `expectedSourceRevision` | CAS precondition for updates and tombstones |

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
   expiry, RPM, and `allowedCollectionKeys`.
3. Receive the raw credential once and immediately store it in a secret
   manager.
4. The business service reads it only from an environment variable, mounted
   secret, or equivalent secret provider.
5. At startup, call `/auth/me` and compare the principal and allow-list exactly.
6. Probe the target Collections by key before consuming business events.
7. Run the contract gate in section 8 after deployment.

Provisioning currently has no idempotency key. After a create timeout, do not
blindly create another principal. Reconcile by stable name and target binding
in the operator management plane. If the principal exists but the raw
credential was not stored safely, rotate its current credential instead of
creating an orphan principal.

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

Rotation is initiated by an operator using root. The new raw credential is
again shown once; the old credential becomes invalid immediately, while the
new credential preserves `principalId` and policy. Distribute the new secret
safely, roll all instances, and verify `/auth/me` before ending the old
deployment. Treat revocation and expiry as terminal errors, not unbounded retry
conditions.

## 7. Deployment, Upgrade, And Rollback

- Use `/actuator/health` for process readiness and `/auth/me` plus Collection
  by-key probes for business binding.
- Empty and upgraded databases must run Flyway V1-V48 in order. This capability
  adds no migration, but existing migrations are still mandatory.
- Pin production callers to an accepted Git commit or an immutable image built
  from it. Maven/API version remains `1.0.0`.
- This change only adds `/auth/me` fields. Older clients ignore them; clients
  that depend on them must run the contract gate before rollout.
- No schema rollback is required. If the server is rolled back to a version
  without the new fields, a new client that requires binding verification must
  stop or roll back rather than starting permissively.

## 8. One-Command Integration Acceptance

Full gate:

```bash
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
real Spring Boot service, 64 HTTP contract assertions, and real API-key
Playwright.

Defaults use isolated ports `18084`, `18085`, `15184`, and `15185` with a
disposable `pgvector/pgvector:pg16`. Evidence is written under
`.verification/business-client-readiness/<run-id>/`; exit traps delete private
credential files. The deterministic embedding stub verifies the real Spring AI
embedding HTTP path. This capability does not change Chat, so the gate does not
call a Chat LLM.

## 9. Current Limitations

- `RAG_READ`/`RAG_WRITE` are currently product-level descriptions, not
  independently enforced operation-scoped permissions.
- Principal provisioning has no idempotency key.
- There is no separate machine-readable integration protocol
  version/capability-discovery endpoint. Compatibility is pinned through
  OpenAPI, a Git commit, and this contract gate.
- The identity system is environment root plus database business principals;
  it does not provide OAuth/OIDC federation or an independent tenant hierarchy.

See the [TODO](TODO.md#managed-api-principal-follow-ups) for these follow-up
boundaries.
