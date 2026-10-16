# Next-Batch Plan: Bounded Collection-Coverage Retrieval

> [English](2026-10-17_NEXT_BATCH_COLLECTION_COVERAGE_PLAN.md) | [中文](2026-10-17_NEXT_BATCH_COLLECTION_COVERAGE_PLAN-zh-CN.md)
>
> Status: planning review `3/3`, implementation, final acceptance, and Git delivery are complete. See the adjacent progress ledger for evidence.
> Date: 2026-10-16
> Implementation branch: `feat/collection-coverage`

## 1. Decision Summary

This batch adds one bounded, client-facing RAG capability: when a caller explicitly selects
multiple Collections, `EACH_COLLECTION` coverage lets every authorized Collection with a hit
contribute to the final candidate set. When candidates from several Collections compete for one
global top-k, this strategy can prevent a Collection from contributing no candidate because of that
ranking competition. This plan has no production or goldenset evidence that such an outcome is
already occurring systematically in the current deployment.

### Why This Batch

The batch is intentionally narrow: freeze the additive contract, implement one query embedding
with bounded per-Collection retrieval and deterministic assembly, then validate Native Search,
Native Chat, the OpenAI-compatible request, and the external-client regression matrix.

Existing `GLOBAL` behavior remains the default. This is an explicit cost/latency exception to the
project preference for default-on improvements: changing every multi-Collection request into a
fan-out would alter the published ranking contract and impose uncontrolled database load. No
operator toggle or data migration is required; a client can use `EACH_COLLECTION` directly.

This is an additive client-selectable quality/ranking strategy, not a claim that the existing union
retrieval is incorrect or that production quality data proves coverage must become the default. It
serves callers that explicitly need each selected Collection to have a chance to contribute to final
evidence; ordinary union retrieval remains valid and unchanged.

The plan relies only on the current multi-Collection GLOBAL retrieval and authorized-scope behavior;
it does not assume that other active batches are fully delivered.

## 2. Baseline and Scope

The current API supports `CALLER_VISIBLE`, `ANY_COLLECTION`, and `SELECTED_COLLECTIONS`; selected
Collections are one union competing for global top-k. `CollectionRetrievalScopeResolver` performs
identity and ACL resolution, `RetrievalScope` is the authorized internal boundary, and
`HybridRetrieverService` performs vector/full-text fusion before optional reranking. Chat keyed
operations persist an effective scope snapshot, and the OpenAI adapter reuses the same scope.

The checked implementation points are `spring-ai-rag-api/.../CollectionScopeMode.java`,
`spring-ai-rag-core/.../CollectionRetrievalScopeResolver.java`, `RetrievalScope.java`,
`HybridRetrieverService.java`, `HybridSearchAdvisor.java`, `RetrievalOutcome.java`,
`ChatTurnOperationService.java`, `ChatCommandMapper.java`, `OpenAiChatCompletionRequest.java`,
`OpenAiRequestRetrievalScopeAdapter.java`, `PerformanceConfig.java`, and
`docs/rest-api-zh-CN.md` (Collection retrieval scope semantics). The DB schema already contains
Collection associations, ACL, embedding profiles, and retrieval indexes; this batch changes no
schema. The current `ragSearchExecutor` has four threads and an unbounded queue.

The bilingual formal TODO now marks coverage as part of this active batch. External HTTP Client/API
acceptance is mandatory; a built-in WebUI advanced option is a separate optional follow-up, not a gate.
The old API semantics remain public until the batch passes acceptance and is released.

In scope: additive coverage mode, Native Search/Chat, OpenAI `rag.scope.coverage_mode`, maximum
20 selected Collections, one embedding on an isolated bounded executor, globally bounded branch
admission, deterministic assembly, diagnostics, and full compatibility evidence.

Out of scope: `ANY_COLLECTION + EACH_COLLECTION`, implicit whole-catalog fan-out, OAuth/OIDC,
tenant hierarchy, billing settlement, Redis coordination, schema migration, and WebUI redesign.

## 3. Goals, Contract, and Completion

### 3.1 Goals

- Add `collectionCoverageMode` with `GLOBAL` (the existing default) and `EACH_COLLECTION`.
- Allow EACH only with an explicit `SELECTED_COLLECTIONS` scope, capped at 20 Collections.
- Compute one query embedding and perform bounded vector/full-text retrieval per authorized
  Collection.
- Preserve one candidate from each Collection with a hit, then fill the remaining limit in a stable
  order with document/chunk deduplication. Never fabricate results for an empty Collection.
- Report final coverage, missing/incomplete Collections, branch degradation, and bounded timing or
  budget data using only the caller's authorized Collection identities.
- Use the same internal contract for Native Search, Native Chat, and OpenAI `rag.scope` while
  preserving existing HTTP and Java callers.
- Reuse one coverage-aware rerank path for direct Chat retrieval, `ProjectRerankPostProcessor`, and
  the independent `KnowledgeSearchTool` path. Tool `maxResults` remains server-capped; if a model
  requests fewer results than selected Collections, raise it only to the selected count and never
  beyond the caller's Chat maximum.

### 3.2 Non-goals

- No `ANY_COLLECTION + EACH_COLLECTION`, implicit catalog-wide fan-out, schema migration, or new
  WebUI controls/interactions. Because the shared Native/OpenAI HTTP contract changes, still run
  WebUI TypeScript, relevant Vitest, production build, and core Mock Playwright as supplementary
  compatibility evidence; these checks do not replace external Client API black-box/HTTP integration
  tests, and screenshots are not acceptance evidence.
- No change to Collection ACL/principal resolution, quota, hard budget, or deletion behavior.
- No OAuth/OIDC, full tenant/workspace hierarchy, Redis coordination, or billing settlement.
- Coverage does not fabricate results: a completed branch with no qualifying hit contributes zero.

### 3.3 Completion Definition

The batch is complete only when:

- Planning and implementation reviews each reach three consecutive no-change rounds.
- Native Search/Chat and OpenAI mapping pass unit, MockMvc, and integration checks; PostgreSQL /
  Testcontainers prove global-versus-each, ACL, no-hit, dedupe, limits, one embedding, and branch
  failure behavior.
- `mvn clean test`, the gated PostgreSQL matrix, Client Contract Kit/OpenAI black-box regression,
  required real-LLM smoke, and `scripts/dev.sh` startup validation pass. An unavailable external
  dependency is recorded as a limitation, never counted as a pass.
- Because the shared HTTP API contract changes, WebUI TypeScript, relevant Vitest, production build,
  and core Mock Playwright pass as supplementary checks; no WebUI feature is added, and these checks
  do not replace external Client API acceptance.
- Per `docs/delivery-workflow-zh-CN.md`, start the real frontend/backend on isolated ports with a
  disposable database and run the real API Key Playwright from `spring-ai-rag-webui/`:
  `BASE_URL=http://127.0.0.1:15273 RAG_ROOT_API_KEY="$RAG_ROOT_API_KEY" npx playwright test e2e/api-key-real.spec.ts`.
  This browser check only supplements proof of the real Vite proxy/auth/HTTP path; it never replaces
  Client Contract Kit, Native/OpenAI API black-box checks, PostgreSQL evidence, or real Search/Chat
  API acceptance. No screenshots are used to establish correctness.
- A versioned multi-Collection goldenset compares GLOBAL and EACH on identical fixtures/queries,
  hard-asserts coverage, expected/forbidden sources, and diagnostics, and records MRR, Recall@K,
  nDCG, candidate counts, and p95 latency. Coverage/ACL/resource bounds are hard gates; because EACH
  intentionally changes ranking, it is not required to outscore GLOBAL and no single LLM judge can
  declare it passed.
- A JSON-record upsert using `embeddingPolicy=SYNC` may still return `INDEXING` if the background
  worker claims the embedding job first. The regression runner must poll the documented
  `GET /api/v1/rag/documents/{id}` lifecycle endpoint every 500 ms, with a 60-second per-document
  default deadline, until both `searchability=READY` and `embeddingStatus=READY` before Search;
  either branch's explicit failure state or timeout fails the gate. `INDEXING` is neither an immediate
  pass nor an immediate failure.
- REST, configuration, project-context, testing, developer, and release documentation is synchronized in both
  languages.
- Git delivery is complete: `main == origin/main`, clean workspace, and test processes/ports are
  cleaned up.

## 4. External Contract

### 4.1 Native API

Native `SearchRequest` and `ChatRequest` gain optional `collectionCoverageMode`; GET Search gains the
matching optional query parameter. EACH requires explicit `SELECTED_COLLECTIONS`, at most 20
selected Collections, and `selectedCount <= limit/maxResults <= 100`; invalid combinations keep the
existing 400 envelope. GET Search's GLOBAL limit of 1000 and Native Chat's existing 50-result
validation remain unchanged.

Native Chat must reject `PLAIN + EACH_COLLECTION` with HTTP 400, the existing `ErrorResponse`, and
`BAD_REQUEST`; the current `resolveScope()` early return must not silently discard the option. Check
this before idempotent replay/claim, model invocation, or committing an SSE response for `/ask`,
`/chat`, and `/stream`. `PLAIN` with coverage omitted or `GLOBAL` preserves existing behavior;
`KNOWLEDGE + EACH_COLLECTION` continues through the selected-scope and authorization rules below.

`RetrievalResult` gains nullable `collectionId` and `collectionKey` projections. To preserve the
POST Search `List<RetrievalResult>` response, Native GET/POST Search expose a compact JSON summary
through the `X-RAG-Collection-Coverage` response header only for EACH. Native Chat adds the summary
to its existing response metadata. The header remains available when diagnostics are disabled or
trace creation/persistence fails. With CORS enabled, browser clients can read it on `/api/**` and
`/v1/**`; allowed origins, request headers, and the default CORS-disabled behavior do not change.
Existing bodies, error meanings, SSE event names, and constructors remain intact.

```json
{
  "collectionScopeMode": "SELECTED_COLLECTIONS",
  "collectionKeys": ["manual-a", "manual-b"],
  "collectionCoverageMode": "EACH_COLLECTION"
}
```

### 4.2 OpenAI-Compatible API

OpenAI adds `rag.scope.coverage_mode` and the `coverageMode` JSON alias. The protocol has no
request-level retrieval `maxResults`; for EACH, `OpenAiChatRequestMapper` sets the effective value
to `max(existingOpenAiDefault, selectedCount)` and validates it without adding a client field or
changing GLOBAL defaults. EACH is valid only for `rag.mode=KNOWLEDGE`; PLAIN plus coverage must
return `unsupported_parameter` at the coverage field rather than silently ignore the option.
OpenAI projects the optional summary to `rag.collectionCoverage` and preserves the existing
four-argument `OpenAiRagExtension` constructor.

### 4.3 Default and Fallback

`GLOBAL` remains the default. Existing numeric IDs, request fields, and error envelopes remain
available; unknown or unauthorized Collection behavior stays 404/403, and coverage never widens an
API-key allow-list. No operator toggle or data migration is required. Historical Chat snapshots
without the new field decode as GLOBAL. Reverting the additive code restores the prior behavior
without data repair.

## 5. Internal Algorithm and Failure Semantics

### 5.1 Authorized Scope

Request adapters pass the coverage option through `CollectionRetrievalScopeResolver`. The resolver
parses IDs/keys and checks active status and the current API-key allow-list before storing coverage
on the authorized `RetrievalScope`. Coverage cannot be read from arbitrary metadata or model tool
arguments. Chat execution snapshots persist the selected mode; older snapshots without the field
decode as GLOBAL. To preserve idempotency records still within their retention window across an
upgrade, effective GLOBAL must leave the existing Native/OpenAI canonical fingerprint JSON and
SHA-256 unchanged. Only `EACH_COLLECTION` adds `coverageMode` to the request fingerprint; omitted
and explicit GLOBAL requests retain the historical hash.

### 5.2 EACH Retrieval and Reranking

1. Add `CollectionCoverageMode` and carry it in the authorized `RetrievalScope`, preserving old
   constructors/factories as GLOBAL overloads.
2. Resolve and validate coverage in the existing resolver and all three request adapters.
3. For EACH mode, create each authorized single-Collection scope and start one absolute request
   deadline no longer than `retrievalTimeoutSeconds` before scheduling work. Compute the query
   embedding once on a new isolated four-thread executor with a fixed queue capacity of 32 and
   immediate rejection on saturation. Submit every enabled full-text branch to the retrieval executor
   immediately and independently of the embedding future. Only the request-coordination thread waits
   for the embedding future, bounded by the shared deadline; submit vector branches only after
   embedding succeeds. On embedding failure, timeout, or embedding-pool rejection, record vector
   outcomes while allowing already submitted full-text work to continue. Never let vector tasks block
   `ragSearchExecutor` workers waiting for embedding: a slow embedding could otherwise occupy all four
   workers and starve independent full-text branches. The embedding API is synchronous and cannot be
   forcibly cancelled by `CompletableFuture` timeout; the executor bounds stuck calls and its queue,
   while the model-client timeout remains the ultimate resource-release boundary. Submit at most
   `2 * selectedCount` vector/full-text branches to the existing four-thread `ragSearchExecutor`.
   Because it is created with `Executors.newFixedThreadPool` and an unbounded queue, guard EACH
   submissions with a non-blocking process-wide permit limit of 80 outstanding branch tasks (queued
   plus running); do not alter GLOBAL scheduling. If branch permits reject work, record a bounded
   capacity status and return whatever independent branches completed. Every branch uses the shared
   absolute deadline; queued branches starting after it skip SQL and record timeout. The caller wait
   therefore does not scale linearly with Collection count, while capacity saturation can produce
   explicitly diagnosed partial coverage. Fuse vector/full-text results within each Collection,
   retain one result per hit Collection, then fill by stable score and identity order with deduplication.
   Each active vector/full-text branch uses the existing `2 * retrievalLimit`
   over-fetch, capped at 200 rows; with at most 20 selected Collections and two branches each, raw
   branch results are capped at 8,000 rows per request before per-Collection fusion. The merged EACH
   candidate pool remains capped at 100. Reranking is actually active only when the request sets `useRerank=true`,
   server configuration has `rag.rerank.enabled=true`, and the configured provider is not
   `none`/`noop`/`off`. This matches the existing `candidateRetrievalLimit` rule. If any condition is
   false, EACH coverage still runs but uses only `finalLimit` candidates and does not call a provider.
   When reranking is active, keep up to `max(finalLimit, configuredCandidateLimit)` results as its
   candidate pool; both values are at most 100 for this mode.
4. When reranking is actually active, invoke the provider once with ranking depth/top_n equal to the bounded
   candidate-pool size. Only after that full-pool ranking, select the final limit while retaining at
   least one hit from each non-empty Collection; fill remaining slots by stable rerank score. Treat
   provider exceptions, output shorter than the input candidate pool, or omission of any Collection
   that had candidates as incomplete reranking, and apply the same coverage selection to the original
   bounded candidate pool. GLOBAL keeps its existing rerank path. For Collection retrieval,
   citation/evidence assembly sees only the final coverage-selected list, never pre-rerank candidates.
   Independently merged `STATIC_KNOWLEDGE` and other non-Collection context remain eligible for
   citations/evidence under existing processing and stay excluded from Collection coverage counts.
5. Add coverage details to existing outcome/trace metadata, the Search response header, Chat
   metadata, and optional OpenAI `rag.collectionCoverage`; preserve the current four-argument `OpenAiRagExtension`
   constructor through a delegating overload. Persist coverage in Chat fingerprints and execution
   snapshots; old snapshots without the field decode as GLOBAL. After rerank, recompute coverage
   counts/missing IDs from the final collection-scoped retrieval list so Search/Chat/OpenAI diagnostics
   do not expose stale candidate-pool counts. `CompositeChatDocumentRetriever` independently merges
   `STATIC_KNOWLEDGE` and other non-Collection context; that context remains available to the model but
   is outside this coverage contract and is excluded from `returnedCount` and
   `perCollectionResultCounts`. The summary describes selected-Collection retrieval, not all evidence
   in the Chat prompt. Include only authorized IDs, counts, and fixed reason codes; never
   expose query/chunk text, arbitrary metadata, credentials, or provider exceptions.
6. Keep GLOBAL on the existing path and preserve all old method signatures through overloads.
7. Reuse the same coverage-aware rerank helper in Search, Chat's `ProjectRerankPostProcessor`, and
   the separate `KnowledgeSearchTool` retrieval/rerank path. Tool arguments remain capped by server
   options and cannot expand the authorized scope. For EACH, if the model requests fewer tool results
   than the selected-Collection count, raise the effective tool limit to that count; the caller's
   Chat maxResults is already validated to be at least that large.

### 5.3 Diagnostic Summary

Expose only authorized selected Collection IDs, requested/returned/missing counts, bounded
per-Collection result counts, incomplete Collection IDs, a degraded flag, and fixed reason codes.
Never include query or chunk text, arbitrary metadata, credentials, or raw provider exceptions.
Recompute coverage after reranking so Search, Chat, trace metadata, and optional OpenAI
`rag.collectionCoverage` describe the final Collection-scoped retrieval list. Do not add a
non-standard SSE event or change the existing top-level POST Search array contract.

Freeze these field meanings for Native Search's header, Native Chat metadata, and OpenAI
`rag.collectionCoverage`:

- `requestedCount` is the number of distinct authorized selected Collections; `returnedCount` is the
  final post-rerank Collection-scoped retrieval list length, counting only results whose
  `collectionId` belongs to the authorized selected IDs. It excludes `STATIC_KNOWLEDGE` and all other
  non-Collection Chat context. `missingCollectionIds` lists authorized IDs with zero final
  Collection-scoped results. `incompleteCollectionIds` lists Collections with at least one failed,
  timed-out, or capacity-rejected retrieval branch; it can overlap `missingCollectionIds` or contain
  a Collection that has results from another branch.
- `degraded` is true exactly when `degradationReasons` is non-empty. A fully completed no-hit is
  missing but not degraded. An incomplete rerank that falls back sets `RERANK_FALLBACK` but does not
  mark retrieval Collections incomplete.
- The only public reason codes, emitted once each in this fixed order, are `EMBEDDING_CAPACITY`,
  `EMBEDDING_TIMEOUT`, `EMBEDDING_ERROR`, `BRANCH_CAPACITY`, `BRANCH_TIMEOUT`, `BRANCH_ERROR`, and
  `RERANK_FALLBACK`. Never serialize raw provider exceptions.
- `perCollectionResultCounts` uses decimal Collection-ID strings as keys and final Collection-scoped
  result counts as values. Include every authorized selected ID (zero when empty); their sum equals
  `returnedCount`, and `missingCount` equals the length of `missingCollectionIds`. All client
  projections use the same JSON shape; GLOBAL emits no coverage summary.

## 6. File-Level Implementation Order

The implementation is split into four ordered slices:

### Slice A: API and Authorized Scope

Add the enum and optional DTO/OpenAI fields, preserve old Java constructors and scope factories,
resolve and validate coverage after identity and API-key allow-list checks, keep GET Search's GLOBAL
limit unchanged, add the Search summary header without changing response bodies, expose it for browser
clients through CORS, and test the mapping. At the Native Chat `/ask`, `/chat`, and `/stream` entry
points, reject `PLAIN + EACH_COLLECTION` before idempotency preparation/look-up, replay/claim, model
invocation, or SSE response commitment with HTTP 400 `BAD_REQUEST`. Preserve PLAIN omitted/GLOBAL
behavior. MockMvc uses the real `GlobalExceptionHandler` and asserts the existing problem+json
`ErrorResponse` fields `error`, `status`, and `path`; verify `prepare`, `inspectExisting`, and `claim`
are not called, no operation is written, and no model call occurs. For `/stream`, send
`Accept: text/event-stream` and assert the HTTP error response, not an SSE frame.

### Slice B: Retrieval Assembly

Add Collection projections, the isolated bounded embedding executor, non-blocking branch admission,
one embedding, a shared deadline, deterministic deduplication and coverage merge, final diagnostics,
and one shared coverage-aware rerank path for Search, Chat, and `KnowledgeSearchTool`.

### Slice C: Idempotency and OpenAI Mapping

Add a fingerprint field only for `EACH_COLLECTION`; prove effective GLOBAL has byte-for-byte
historical canonical JSON/SHA-256 so persisted keyed operations continue to replay instead of
returning 409 after upgrade. Persist the mode in execution snapshots, decode older snapshots without
the field as GLOBAL, map OpenAI scope, and verify Native/OpenAI JSON/SSE plus GLOBAL/EACH keyed replay
and conflict isolation.

### Slice D: Evidence and Documentation

Add unit, MockMvc, PostgreSQL, SDK, startup, regression, and bounded real-model evidence. Update the
REST, configuration, architecture/project-context, testing/developer/release docs in both languages
and wire deterministic checks into release verification. Add
`testdata/regression/retrieval-collection-coverage-v1.json` and
`scripts/verify-collection-coverage.sh` to compare GLOBAL/EACH on identical fixed data, hard-check
expected non-empty Collection coverage, forbidden/ACL isolation, final response summaries, and
resource ceilings, while recording MRR, Recall@K, nDCG, candidate counts, and p95 latency.
Since this batch changes the shared HTTP contract, also run from `spring-ai-rag-webui/`:
`npx tsc -b --pretty false`, `npm run test:run`, `npm run build`, and `npm run test:e2e:mock`
(build plus preview server running every non-`-real` Mock E2E). Do not use screenshots as pass
evidence or these frontend checks as a replacement for Client Contract Kit/external API regression.
Per the delivery workflow, also run the real browser/API Key test against the isolated `scripts/dev.sh`
frontend/backend and disposable database:
`BASE_URL=http://127.0.0.1:15273 RAG_ROOT_API_KEY="$RAG_ROOT_API_KEY" npx playwright test e2e/api-key-real.spec.ts`.
Use backend/frontend ports `18282/15273`. Start a disposable `pgvector/pgvector:pg16` container on a
random loopback port and create a mode-`0600` temporary environment overlay from `.env`, replacing
only PostgreSQL host/port/database/user/password and `SPRING_DATASOURCE_URL` with that container;
preserve the configured model and root API credentials. Point `DEV_ENV_FILE` to this overlay and
start with `RAG_DEV_OPEN_BROWSER=false`. For live OpenAI-compatible API probes, additionally set
`RAG_OPENAI_COMPATIBILITY_ENABLED=true` only in this temporary overlay (the production default
remains disabled), and use the existing public alias `rag-default`; pass
`API_CONTRACT_MODEL=rag-default` to the API compatibility gate. These are isolated acceptance
settings, not production-default changes. Never run this mutating key-rotation test against the
operator's configured database. Stop the dev stack and remove only this batch's container and
temporary overlay afterward. This is supplementary runtime-topology evidence; external Client
HTTP/API compatibility remains the primary acceptance.

Concrete code areas are `spring-ai-rag-api` request DTOs and enums; the existing Native/OpenAI
request adapters and `CollectionRetrievalScopeResolver`; `PerformanceConfig` and
`HybridRetrieverService`; result mapping/provenance and `RetrievalOutcome`; `RerankAdvisor`,
`ProjectRerankPostProcessor`, and `KnowledgeSearchTool`; Chat fingerprints/snapshots/replay; and the
Search/Chat/OpenAI response projections. Preserve old constructors and scope factories with
delegating overloads. Deterministic verification must not call a real model by default.

## 7. Acceptance Matrix

Acceptance must prove invalid combinations and limits, ACL isolation, one embedding, isolated
embedding timeout/capacity behavior, an outstanding EACH branch ceiling of 80, queued work skipping
SQL after the shared deadline, per-Collection coverage and missing-hit semantics, branch failure
degradation, correct Search-header/Chat-metadata/OpenAI-field visibility with diagnostics both
enabled and disabled, unchanged legacy response bodies and absence of new GLOBAL headers, OpenAI
PLAIN rejection at the
correct field, one rerank call that returns the
bounded candidate pool before coverage selection, coverage-preserving fallback on provider failure,
short output, or a missing hit Collection, final coverage counts after rerank, `KnowledgeSearchTool` coverage, one citation
pass, OpenAI mapping and replay, old API compatibility, PostgreSQL
gated integration, startup cleanup, and a bounded real-LLM KNOWLEDGE check when credentials are
available.

| Acceptance area | Required evidence |
|---|---|
| Request validation | Missing/invalid scopes, empty selection, more than 20 Collections, insufficient limit, and EACH limit over 100 return 400; legacy GLOBAL GET limit remains 1000. |
| Native Chat mode validation | `/ask`, `/chat`, and `/stream` reject `PLAIN + EACH_COLLECTION` before idempotency preparation/lookup, replay/claim, model invocation, or SSE commitment, using HTTP 400 `BAD_REQUEST`; PLAIN omitted/`GLOBAL` and KNOWLEDGE EACH behavior remain unchanged. MockMvc uses the real `GlobalExceptionHandler`, asserts problem+json `error/status/path`, and verifies `prepare/inspectExisting/claim`, operation writes, and model are untouched; `/stream` with `Accept: text/event-stream` returns the HTTP error, not an SSE frame. |
| Historical idempotency compatibility | Effective GLOBAL preserves the exact pre-change Native/OpenAI canonical fingerprint and SHA-256; a persisted successful pre-upgrade operation replays rather than returning 409; EACH participates in the fingerprint and cannot collide with GLOBAL; old snapshots without the field decode as GLOBAL. Fingerprint golden tests plus service/PostgreSQL replay seeded with an old hash and Native/OpenAI keyed API regression. |
| Goldenset ingestion readiness | If a JSON-record upsert returns `INDEXING`, poll the documented document lifecycle endpoint every 500 ms for at most 60 seconds per document until `searchability=READY` and `embeddingStatus=READY`; fail on either branch's failure state or timeout before Search. |
| OpenAI HTTP acceptance | KNOWLEDGE maps `rag.scope.coverage_mode`; PLAIN is rejected at the correct field; JSON/SSE/replay remain compatible and old SDKs can read `answer`. Live probes explicitly enable the default-disabled route in the disposable overlay and use alias `rag-default`. |
| Authorization | Restricted API keys cannot escape their allow-list; unknown/denied semantics stay unchanged. |
| Retrieval | Each hit Collection contributes at least one item; empty Collections remain empty; each branch returns at most 200 raw rows, total raw branch results are at most 8,000 per request, merged candidates/results are capped at 100, and deduplication is stable. |
| Async bounds | One embedding; submit full-text independently first, submit vector only after the request coordinator obtains the embedding, and never block retrieval workers on embedding; latch tests prove full-text completes while embedding is stalled; isolated embedding rejection/timeout; branch permit ceiling 80; queued expired tasks skip SQL; partial work is diagnosed. |
| Reranking and Chat | One full candidate-pool rerank; coverage survives provider exception/short output/missing Collection fallback; direct Chat and `KnowledgeSearchTool` preserve authorization, citations, and evidence metadata; static/non-Collection context is excluded from Collection coverage counts. |
| Response compatibility | Old bodies/errors/SSE order stay unchanged; EACH Search header works with diagnostics enabled and disabled; no new GLOBAL headers; Chat/OpenAI coverage fields are visible. |
| Shared-contract frontend regression | Although no WebUI feature is added, the existing WebUI types, tests, build, and Mock workflow remain compatible with the shared API additions; run `npx tsc -b --pretty false`, `npm run test:run`, `npm run build`, and `npm run test:e2e:mock` from `spring-ai-rag-webui/`. This evidence supplements but never replaces external Client API acceptance; screenshots are not used. |
| Real frontend/backend Playwright | Run the mutating API Key browser test only through the real Vite proxy on ports `18282/15273` and a disposable PostgreSQL database. Verify create/rotation/revocation, final credential deletion, and that secrets do not enter URLs, console, or browser storage. The test does not delete the principal row; destroy the whole disposable database container afterward and never connect to the operator database. This supplements runtime-topology/proxy coverage and cannot replace external Client API black-box or PostgreSQL tests. | From `spring-ai-rag-webui/`: `BASE_URL=http://127.0.0.1:15273 RAG_ROOT_API_KEY="$RAG_ROOT_API_KEY" npx playwright test e2e/api-key-real.spec.ts`; no screenshot acceptance. |
| CORS | With CORS enabled and an allowed origin, browser code can read `X-RAG-Collection-Coverage`; allowed origins/headers and the default-disabled behavior remain unchanged. |
| Quality/cost | Versioned goldenset compares GLOBAL/EACH; coverage, expected/forbidden sources, response summary, ACL, and resource limits are hard assertions; MRR/Recall@K/nDCG/candidate count/p95 are reported without assuming EACH must outrank GLOBAL. |
| Integration and release | PostgreSQL-gated tests, Client Contract Kit and OpenAI replay, real `e2e/api-key-real.spec.ts` through the isolated `scripts/dev.sh` stack, process/database cleanup, plus bounded real KNOWLEDGE smoke when credentials are available. |

## 8. Risks, Rollback, and Reversibility

- EACH is opt-in and limited to explicitly selected Collections (maximum 20). One request schedules
  at most 41 asynchronous tasks: one embedding plus 40 retrieval branches.
- Embedding uses four fixed workers and a 32-slot bounded queue. Rejection and deadline expiry are
  reported; a running synchronous provider call cannot be force-cancelled and remains bounded by the
  executor until the model client's own timeout releases it.
- Each EACH vector/full-text query keeps the existing 2x retrieval over-fetch, with an explicit cap
  of 200 rows per branch. Across at most 40 branches, pre-fusion raw results are capped at 8,000
  rows per request; the merged candidate pool and final result list remain capped at 100.
- EACH vector/full-text branches use the existing four-thread `ragSearchExecutor`, whose queue is
  unbounded for legacy scheduling. A non-blocking process-wide semaphore caps this feature's queued
  plus running branches at 80 without changing GLOBAL behavior. Rejected work is not silently
  represented as a successful full-coverage result; diagnostics must identify capacity-limited
  branches and partial coverage.
- An absolute per-request retrieval deadline is no longer than the existing configured timeout.
  Queued work checks it before SQL. Work already inside synchronous SQL/provider calls is not claimed
  to be cancelled by `CompletableFuture`; capacity permits remain held until the actual task ends.
- No schema migration or operator setting is introduced. Revert the feature code and docs to restore
  legacy behavior; do not drop data or modify applied migrations.
- **Ranking:** only explicit EACH requests change candidate ordering; GLOBAL and omitted-mode requests
  stay on the existing path.
- **Response evolution:** Collection projections and EACH-only response metadata/header are
  additive; do not change response envelopes, old constructors, or SSE event names.
- **Idempotency compatibility:** only `EACH_COLLECTION` enters the request fingerprint. Effective
  GLOBAL preserves the previous canonical JSON/SHA-256 so retained operations replay under the same
  key after upgrade. New snapshots store the mode; snapshots without it decode as GLOBAL; key reuse
  between EACH and GLOBAL must conflict.
- **Privacy:** summaries contain only authorized IDs, counts, and normalized fixed reason codes.
- **Rollback:** stop merge on correctness, cost, authorization, or compatibility failures. Preserve
  the GLOBAL path and user data; do not rewrite migrations, reset, or stash.
- **Quality interpretation:** EACH changes cross-Collection candidate allocation, not universally
  ranking quality. Report retrieval metrics and latency transparently; coverage, ACL, forbidden
  sources, and hard resource limits are gates. A single LLM judge or a higher score than GLOBAL is
  not the sole pass criterion.

## 9. Progress and Review Rules

The adjacent bilingual progress files are the recovery source. Each checkpoint records the branch,
baseline, workspace state, completed slices, commands and outcomes, review counter, known issues, and
the next recovery action. Planning and implementation each require three consecutive, non-overlapping,
read-only review rounds. A substantive correctness, compatibility, security, consistency, cost, or
feasibility finding requires an immediate plan/code repair as applicable and resets that phase to
`0/3`; a clean round does not modify the plan. Implementation must not expand until planning reaches
`3/3`. Follow `docs/delivery-workflow-zh-CN.md` for the full acceptance and Git-delivery gates.
