# Future TODO

> 📖 [English](TODO.md) · [中文](TODO-zh-CN.md)
>
> Last reviewed: 2026-08-23. This file records follow-up work outside the
> current code and public API; it does not describe shipped capabilities.

## Managed API Principals And Multi-Instance Hardening

| Item | Priority | Current status |
|---|---|---|
| Stable principals and versioned credential families | Next planned batch / not implemented | Rotation still creates an independent `keyId`; the `db:{keyId}` owner used by Chat, evaluation, diagnostics, and durable operations changes with it |
| Immediate cross-instance revocation and low-write last-used auditing | Next planned batch / not implemented | Authentication still has a 30-second JVM positive cache and synchronously updates `last_used_at` after every success |
| PostgreSQL shared principal quota | Next planned batch / not implemented | Rate limiting is process-local, so replicas multiply the quota and rotation has no stable limiter family |
| Schema-level plaintext-secret prohibition | Next planned batch / not implemented | The V23 `api_key` column and index remain; the current service does not write the column, but the schema still permits a non-null value |

These are four symptoms of one identity lifecycle and should ship as one
independently verifiable batch rather than as separate patches. The recommended
direction separates long-lived ownership/policy from rotatable credentials:
existing keys are deterministically backfilled with `principalId=old keyId` to
preserve historical owners; authentication carries an immutable
principal/policy snapshot; every instance consults PostgreSQL on the first
authentication after a revocation; and shared quotas count by stable principal.
The target design, compatibility boundary, and one-pass acceptance matrix are
in the [current active plan](drafts/NEXT_HIGH_VALUE_FEATURES_PLAN.md). None of
these items is a shipped API until implementation and acceptance finish.

This batch does not add OAuth/OIDC, tenant hierarchies, Redis, token/cost
billing, per-Collection embedding-profile routing, or `EACH_COLLECTION`, and it
does not guess family relationships among historical rotation rows.

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
