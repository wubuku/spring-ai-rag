# Future TODO

> 📖 [English](TODO.md) · [中文](TODO-zh-CN.md)
>
> Last reviewed: 2026-08-26. This file records follow-up work outside the
> current code and public API; it does not describe shipped capabilities.

## Delivered Integration Gates

- [x] `business-client-binding-preflight.sh` provides a non-root, read-only
  default check for a deployed instance's readiness, OpenAPI, principal policy,
  exact Collection allow-list, and active Collections.
- [x] The opt-in canary mode validates ASYNC persistence, exact replay,
  readiness, payload search, CAS conflict, tombstone, restore, and final
  tombstone with bounded failure cleanup.
- [x] The readiness contract runs the preflight as a black-box client and
  checks secret-safe reports for read-only success, allow-list mismatch,
  Bearer canary success, and provider failure cleanup.

## Managed API Principal Follow-Ups

| Item | Priority | Current status |
|---|---|---|
| Stable principals, versioned credentials, and immediate revocation | Shipped in V48 | Ownership/policy is separate from credentials; every authentication performs an authoritative join without a positive decision cache |
| PostgreSQL shared principal quota | Shipped in V48 | Replicas share fixed-minute buckets by stable principal; rotation does not reset quota and database failure fails closed |
| Schema-level plaintext-secret prohibition | Shipped in V48 | Migration clears plaintext, removes its index, and enforces `api_key IS NULL` |
| Operation-scoped `RAG_READ` / `RAG_WRITE` enforcement | Separate future plan | Capabilities are currently product-level descriptions; a database business principal still has the full RAG read/write data plane |
| Principal-provisioning idempotency key | Separate future plan | After a create timeout, an operator must reconcile by stable name/binding; create cannot yet be retried blindly |
| Machine-readable integration protocol version / capability discovery | Separate future plan | Compatibility is currently pinned through OpenAPI, a Git commit, `/auth/me`, and the business-service contract gate |
| OAuth/OIDC and an independent tenant hierarchy | Separate future plan | The current system remains environment root plus managed business principals and has no third-party identity federation |
| Persistent token/cost observability per model invocation | Future implementation candidate | Final Chat response usage covers only the last response and omits query transform/expand, AGENT rounds, summaries, fallbacks, and application retries; the candidate has not completed planning `3/3` |
| Token/cost hard limits, billing, and settlement | Separate future plan | These require authorization, reservation, settlement, cross-instance overspend protection, and crash recovery; they cannot rely directly on a best-effort observability ledger |
| Management recovery and removal of legacy compatibility | Evaluate before public enablement | Legacy static/query behavior remains a compatibility boundary; operator recovery relies on the environment root |

V48 performs a deterministic one-principal-per-credential backfill for rows
present during migration; it does not guess unprovable family relationships
among older rotation rows. Per-invocation usage and configured-cost
observability is preserved in the
[historical archive](drafts/archive/README.md) as a future implementation
candidate, but it is not the current active plan and is not presented as a
provider invoice or hard-limit settlement source. Operation-scoped
authorization, provisioning idempotency, protocol capability discovery,
OAuth/OIDC, tenant hierarchy, Redis, token/cost hard limits and billing,
per-Collection embedding-profile routing, and `EACH_COLLECTION` remain
independent planning subjects.

## Document Lifecycle And Derived-Index Follow-Ups

| Item | Priority | Current gap |
|------|----------|-------------|
| Authoritative source snapshot reconciliation | Shipped in this batch | V42 API and reference client support bounded authoritative runs, preview fingerprints, deletion protection, and reconciliation tombstones |
| Atomic Collection relocation for external documents | Shipped in this batch | V44 provides dual ACL, exact idempotent replay, Sync Run fencing, and a permanent retired-address guard; the feature flag defaults off |
| Collection derivation-integrity diagnostics and controlled repair | Shipped in this batch | V45 provides shared physical freshness, bounded Collection diagnostics, and durable preview/apply/status for at most 100 items; side-effecting repair defaults off |
| Controlled historical-version restore | Shipped in this batch | Local `FULL` snapshots can be restored as a new revision when the feature flag is enabled; external documents remain source-owned |
| Decouple local chunks/full text from remote vectors | Shipped in this batch | V43 stores profile-neutral local chunks/state; provider failures leave current content available as `KEYWORD_ONLY` while stale generations remain excluded |

### Current Boundaries

- External synchronization supports incremental webhook/CDC delivery and
  authoritative Sync Runs. An incomplete batch must never imply deletion of
  missing objects; only an explicit safe snapshot mode can enable tombstones.
- The external address remains
  `collectionKey + sourceNamespace + externalId`. Until the project has an
  independent tenant/connector authorization boundary, do not weaken the
  uniqueness scope to global `sourceNamespace + externalId`; cross-Collection
  movement uses explicit relocation with both source and target ACL validation;
  ordinary upsert must not simulate a move.
- Version restore creates a new revision and complete snapshot, does not rewind
  counters or overwrite history, and reuses the current mutation impact,
  durable-job, and commit-fencing paths.
- Only versions with `snapshotCompleteness=FULL` are eligible for complete
  restoration. Older compatibility snapshots remain audit-only.
- V43 local full-text derivation excludes old content immediately and allows new
  content to become `KEYWORD_ONLY` before remote embedding succeeds and
  promotes the lifecycle to `READY`.
- Concurrency remains based on conditional DML/CAS, unique constraints, leases,
  and bounded retries; explicit pessimistic locking remains forbidden.
- Derivation repair rebuilds or queues derived data only. It does not modify
  content, is bounded to 100 items, never stores clear tokens, and does not loop
  over synchronous embedding-provider calls in the HTTP request.

See [current active plans](drafts/README.md) for remaining implementation scope
and batch ordering. Shipped contracts remain in the [REST API reference](rest-api.md) and
[External Document Sync Client Guide](external-document-sync-client-guide.md).

## `EACH_COLLECTION` retrieval-coverage mode

| Item | Priority | Status |
|------|----------|--------|
| Give every explicitly selected Collection an opportunity to contribute candidates | Non-urgent backlog / no target release | Deferred; not a current-release blocker |

### Current behavior

The current `SELECTED_COLLECTIONS` mode is **scope filtering**: multiple
`collectionKeys` form one candidate union, and all candidates compete in one
hybrid-retrieval pipeline for the global top-k. A Collection with no content
relevant enough to the query may contribute no result. This is intentional;
see [REST API: Collection Retrieval Scope](rest-api.md#collection-retrieval-scope).

`ANY_COLLECTION` is not per-Collection retrieval either. It means “search all
retrievable documents that belong to some Collection.” It answers a different
question from `EACH_COLLECTION`:

- `ANY_COLLECTION`: what is the candidate scope?
- `EACH_COLLECTION`: after the scope is known, must the result set provide
  coverage for every Collection?

Consequently, the current API does not accept `EACH_COLLECTION` or a similar
`collectionCoverageMode` field. Clients must not assume that every selected
Collection appears in the result set.

### Why it is deferred

This is not just another scope-filter enum. It is a separate retrieval and
ranking policy:

1. Each Collection needs its own candidate quota or per-Collection top-k.
2. A multi-Collection request creates bounded fan-out; it must not turn into
   an unbounded database/model call per Collection.
3. Candidates from all Collections need deduplication, fusion, and reranking;
   the result count cannot simply be added together.
4. A Collection with no relevant content must not be padded with low-quality
   results just to satisfy a coverage rule.
5. Latency, candidate count, coverage, and quality metrics are needed to
   evaluate whether the extra cost is justified.

The current direct pushdown of `d.collection_id` already addresses the main
performance problem of multi-Collection range filtering: large Collections
are not expanded into all document IDs first. Ordinary union retrieval does
not need `EACH_COLLECTION` to be correct. Without a clear product requirement
and quality evidence, implementing it would add complexity and performance
risk without solving the current scope-filtering problem.

### When to revisit

Open a dedicated implementation plan when one of these conditions holds:

- The product explicitly requires every selected knowledge base to have an
  opportunity to appear in the answer evidence.
- A goldenset or production metric shows that global top-k is repeatedly
  dominated by a few large Collections, systematically hiding useful results
  from other Collections.
- Callers can accept a bounded number of selected Collections, extra latency,
  and an explicit candidate budget.
- Product semantics define that a Collection with no relevant content may
  legitimately contribute zero results without forced padding.

### Future implementation constraints

Any later implementation should preserve the current API boundary:

- Add a separate coverage field such as `collectionCoverageMode`; do not
  redefine `collectionScopeMode`, which continues to express candidate scope.
- Initially allow only `EACH_COLLECTION + SELECTED_COLLECTIONS`. Do not
  expose `ANY_COLLECTION + EACH_COLLECTION`, which could implicitly fan out
  across a large number of Collections.
- Use a small, explicit Collection limit, recommended at no more than 20,
  with bounded candidate and concurrency budgets.
- Compute the query embedding once. Define the order of per-Collection
  candidate retrieval, global fusion/reranking, deduplication, and final
  top-k selection.
- If a Collection has no relevant candidates, report that fact rather than
  manufacturing a low-quality result.
- Add PostgreSQL integration tests, latency/candidate metrics, quality
  goldensets, and an advanced WebUI option; do not make it the default mode.

### Related documents

- [Project context: Current Collection Semantics](project-context.md#current-collection-semantics)
- [REST API: Collection Retrieval Scope](rest-api.md#collection-retrieval-scope)
