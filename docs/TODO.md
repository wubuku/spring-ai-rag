# Future TODO

> 📖 [English](TODO.md) · [中文](TODO-zh-CN.md)
>
> Last reviewed: 2026-08-16. This file records follow-up work outside the
> current code and public API; it does not describe shipped capabilities.

## `EACH_COLLECTION` retrieval-coverage mode

| Item | Priority | Status |
|------|----------|--------|
| Give every explicitly selected Collection an opportunity to contribute candidates | P2/P3 | Deferred; not a current-release blocker |

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
  legitimately contribute fewer than one result.

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

- [Multi-Collection retrieval-scope research](drafts/2026-08-15_MULTI_COLLECTION_RETRIEVAL_SCOPE_RESEARCH.md)
- [Multi-Collection retrieval implementation plan](drafts/2026-08-16_MULTI_COLLECTION_RETRIEVAL_IMPLEMENTATION_PLAN.md)
- [Project context: Current Collection Semantics](project-context.md#current-collection-semantics)
- [REST API: Collection Retrieval Scope](rest-api.md#collection-retrieval-scope)
