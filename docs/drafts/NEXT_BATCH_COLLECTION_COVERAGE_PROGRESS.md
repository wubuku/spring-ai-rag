# Next-Batch Progress: Bounded Collection-Coverage Retrieval

> [English](NEXT_BATCH_COLLECTION_COVERAGE_PROGRESS.md) | [中文](NEXT_BATCH_COLLECTION_COVERAGE_PROGRESS-zh-CN.md)
>
> Plan: [English](NEXT_BATCH_COLLECTION_COVERAGE_PLAN.md) | [中文](NEXT_BATCH_COLLECTION_COVERAGE_PLAN-zh-CN.md)

## Current Status

- Branch: `feat/collection-coverage`
- Baseline: `b473b002` (pushed `main`)
- Planning review: after adding bounded lifecycle readiness polling for asynchronous embedding, three fresh no-change reviews passed on 2026-10-17 03:24–03:26 (`3/3`); scopes/results are recorded below.
- Implementation review: `3/3`; three consecutive read-only rounds completed without edits on 2026-10-17 05:27–05:36. Scope covered retrieval/ranking bounds, API/idempotency compatibility, authorization/recovery/resource limits, and acceptance tooling.
- Phase: planning and implementation reviews, external Client API, database, real-model, SDK, startup, and supplementary WebUI acceptance are complete. Remaining work is local commit, sync with latest `origin/main`, merge/retest if needed, merge and push to `main`, then archive this plan and progress ledger.

## Latest Planning Review Record

At 2026-10-17 03:06, a live API acceptance run found that the OpenAI-compatible controller is
conditional on `RAG_OPENAI_COMPATIBILITY_ENABLED` and defaults off, while the configured public model
alias is `rag-default`. The plan did not specify enabling that route in the disposable test overlay or
passing the correct alias to the API compatibility probe, so the probe reached a 404 static-resource
path. The bilingual plan now sets the flag only in the disposable overlay and documents the alias;
production defaults remain unchanged. Planning review reset to `0/3`. The first subsequent plan-review
pass found stale `3/3` status in both plan headers, conflicting with the new counter; the headers are
fixed and the review restarted from `0/3`. No clean review round has run after that correction.
The first restarted review also found the English progress Phase still claimed planning `3/3` was
complete; that status line is corrected, so planning review is reset again to `0/3`.

After those status corrections, three non-overlapping read-only reviews completed without findings
or edits from 03:12 to 03:13 CST on 2026-10-17:

| Time (CST) | Scope | Findings and treatment | Result |
|---|---|---|---|
| 03:12 | First-reader perspective, goals/non-goals, completion definition, plan/progress status chain | No issues; no edits | `1/3` |
| 03:12–03:13 | OpenAI conditional config, model alias, `scripts/dev.sh` environment export, external Client probe inputs, disposable DB boundary | Code/config match the plan; no issues or edits | `2/3` |
| 03:13 | Bilingual links/status, delivery boundaries, documentation integrity and whitespace | All 16 `verify-project-docs.sh` checks passed; no issues or edits | `3/3` |

The plan body was not modified during these three rounds; status headers and this progress record were
synchronized only after the third clean review.

That `03:12–03:13` review applied only to the plan version available then. A real runtime regression at
03:19 on 2026-10-17 found that a JSON-record upsert requested with `SYNC` can legitimately return
`INDEXING` if an embedding worker claims the job first, while the runner incorrectly required immediate
readiness. The bilingual plan now requires bounded polling of the existing document-detail lifecycle
until searchable, with failure/timeout still failing closed. Planning review reset to `0/3`; no clean
round has run after this latest correction.
The first review pass on that revision found the English progress Phase still claimed the latest
planning review was complete; that line is corrected, so the counter remains `0/3` and restarts from
round one.
The subsequent repository-wide status scan found that the active draft indexes and bilingual TODOs
still advertised the old `3/3`; those references now say `0/3`, and review restarts from round one after
this status-propagation correction.

After synchronizing the active status references, three non-overlapping read-only reviews of the latest
plan completed without findings or edits:

| Time (CST) | Scope | Findings and treatment | Result |
|---|---|---|---|
| 03:24 | Current plan/progress/TODO/draft-index status, first-reader path, completion definition | All active references consistently said `0/3`; no issue or edits | `1/3` |
| 03:25 | REST lifecycle contract, SYNC worker race, detail endpoint/status fields, 60-second/500-ms bounds | Matches implementation and REST docs; no issue or edits | `2/3` |
| 03:25–03:26 | Bilingual status/links, delivery boundaries, repository-wide active references and doc gates | TODO/index state consistent; all 16 `verify-project-docs.sh` checks passed; no edits | `3/3` |

Neither plan text nor status documents changed during these three rounds; status synchronization
occurred after the third clean review.

The review sequence restarted after the Native Chat error-precedence/no-side-effect revision at
2026-10-17 01:14. The following three non-overlapping rounds were read-only; no plan text changed
between them, and the consecutive counter reached `3/3`:

| Time | Scope | Findings and treatment | Result |
|---|---|---|---|
| 01:17:04 | First-reader perspective, goals/non-goals, API scope, status and bilingual entry points | No issues; no edits | `1/3` |
| 01:17:33 | Native/OpenAI DTOs and mappers, authorization/fan-out bounds, Chat idempotency and response compatibility | No issues; code facts match the plan; no edits | `2/3` |
| 01:18:26 | Acceptance matrix, real-dependency/frontend gates, rollback/recovery, Git delivery order | No issues; gates are closed; no edits | `3/3` |

That `3/3` applied only to the revision available at 01:14–01:18. Later substantive findings reset the
counter and were repaired. The latest consecutive review is:

| Time (CST) | Scope | Findings and treatment | Result |
|---|---|---|---|
| 02:23 | Goals/non-goals, first-reader clarity, GLOBAL default, Native/OpenAI additive contract, external-client acceptance | Before counting, corrected the stale bilingual status line that conflicted with later reset records; no further issue in this round and no plan-body edit | `1/3` |
| 02:25 | DTO/mappers, authorization order, Collection/limit bounds, concurrency budgets, Chat snapshots, historical idempotency fingerprints | Requirements match implementation paths; the known GLOBAL fingerprint defect has an explicit repair and regression requirement; no plan-body edit | `2/3` |
| 02:25–02:27 | Acceptance matrix, external API black-box priority, runtime supplements, bilingual links, rollback and Git delivery | All 16 `verify-project-docs.sh` checks passed; no omission or contradiction found; no plan-body edit | `3/3` |

No plan body changed during these three rounds; status and progress were synchronized once afterward.
Any later substantive planning defect still requires repair and a fresh consecutive `3/3` review.

## Implementation Checkpoint

- Overall batch progress: approximately `95%` (feature implementation and acceptance are complete; Git delivery remains). All external API gates ran independently of WebUI checks; WebUI is supplementary evidence and cannot replace external Client black-box or real SDK tests.
- Native Chat `/ask` and `/chat` share the non-streaming entry point; `/stream` has its own entry. All reject `PLAIN + EACH_COLLECTION` before idempotent `prepareTurn()` and use the existing `GlobalExceptionHandler` for HTTP `400 BAD_REQUEST`. PLAIN omitted/GLOBAL and KNOWLEDGE EACH retain their existing paths.
- Added `RagChatControllerCoverageValidationWebTest` for the real `/api/v1` routes, ask/chat/stream errors, HTTP problem+json with SSE Accept, error fields, zero idempotency-service/model interactions, and positive PLAIN omitted/GLOBAL plus KNOWLEDGE EACH paths. `mvn -pl spring-ai-rag-core -am -Dtest=RagChatControllerCoverageValidationWebTest -Dsurefire.failIfNoSpecifiedTests=false test`: 6 passed.
- The final release gate completed on 2026-10-17 at 05:26: `./scripts/verify-release.sh --skip-docker --skip-helm --skip-npm-ci` reported `18 passed, 0 failed, 13 skipped`. Full-reactor `mvn clean test`, WebUI lint/TypeScript, 1,030 Vitest tests, production build, 525 design-system tests, and 93 Mock Playwright tests passed. Docker, Helm, npm ci, and optional runtime gates were skipped by this invocation; this does not imply those gates passed. Relevant external API, collection-coverage, and SDK checks have separate live evidence below.
- External Client API black-box acceptance passed against an isolated live service; evidence is under `.verification/api-contract/collection-coverage-live-20261017-050812/`. Collection coverage HTTP regression at `.verification/collection-coverage/20261017-050321/summary.md` reports GLOBAL and EACH_COLLECTION hit rate, MRR, Recall@K, and nDCG of `1.0000`, and checks source authorization plus the coverage summary.
- Official Python and Node OpenAI SDK live HTTP acceptance passed for models list, JSON, SSE, idempotency, error envelopes, and authentication: `.verification/openai-sdk-e2e/collection-coverage-real-fixed-20261017-051508/summary.md`. The live Client Consumer contract, real-model Search/Chat/Agent/SSE, and isolated-port/database `scripts/dev.sh` lifecycle were also independently verified. UI results do not replace these API checks.
- All 26 gated PostgreSQL/Testcontainers suites passed; isolated `scripts/dev.sh` startup and disposable database cleanup completed. Final release summary: `target/release-verification/collection-coverage-final-20261017/summary.md`.
- Optional Native/OpenAI fields, authorized scope, legacy GLOBAL fingerprints, and legacy Java constructor signatures are covered by contract/integration tests and live Client checks. No data migration is needed; existing V1–V60 Flyway migrations remain unchanged.

## Latest Implementation Review Rounds

Three consecutive, non-overlapping, read-only reviews completed on 2026-10-17 with no file edits:

| Time (CST) | Scope | Findings and action | Result |
|---|---|---|---|
| 05:27–05:31 | Candidate merge/ranking, cross-Collection deduplication, complete rerank validation, per-branch/request/Collection hard bounds, PostgreSQL regression | No material issue; implementation matches boundary tests; no edits | `1/3` |
| 05:31–05:34 | Native/OpenAI additive request/response contracts, SSE/error path, legacy GLOBAL idempotency fingerprints, legacy Java constructors | No material issue; a fixed SHA-256 test locks the legacy GLOBAL fingerprint; no edits | `2/3` |
| 05:34–05:36 | API-key authorization, snapshot recovery, resource pools/timeouts, privacy-safe coverage summary, live HTTP runner, bilingual docs | No material issue; no schema migration is needed; no edits | `3/3` |

No code, test, or plan-body edits occurred during the three rounds. Earlier findings and repairs remain in the historical record below.

## Latest Implementation Review Finding

At 2026-10-17 02:07:50, the first read-only implementation review found that `ChatRequestFingerprint`
adds `coverageMode=GLOBAL` to every KNOWLEDGE request while `ChatTurnOperationService` compares the
stored fingerprint by exact SHA-256. A successful pre-upgrade idempotent operation still within its
retention window would therefore be misclassified as key reuse and return 409 instead of replaying.
The bilingual plan now requires GLOBAL to preserve the old canonical JSON/hash and only EACH to add
the field, plus a persisted-old-hash replay test and EACH/GLOBAL isolation. Both plan-review and
implementation-review counters reset to `0`; do not deliver until the fix and post-fix gates pass.
Earlier test passes are pre-fix evidence and final gates must be rerun.

At 2026-10-17 02:12:20, planning review round 3 found that the project delivery workflow requires
real frontend/backend Playwright on isolated ports and a disposable database after shared API changes,
while the plan listed only Mock Playwright and `scripts/dev.sh` lifecycle. The bilingual completion
definition, Slice D, and acceptance matrix now require the repository's executable
`e2e/api-key-real.spec.ts` command and state that it supplements but never replaces external Client
API acceptance. This omission resets planning review to `0/3`; restart the three no-edit rounds after
this revision.

A further source review of `spring-ai-rag-webui/e2e/api-key-real.spec.ts` found that its `finally`
block deletes the final credential but does not delete the principal row. The bilingual plan now
expects credential deletion and no secret persistence in URL/console/browser storage, then disposal
of the entire temporary database; it does not claim row-level principal deletion and forbids using
the operator database. This acceptance-expectation correction is part of the plan-review repair, so
the planning counter remains `0/3`.

## Why Planning Review Restarted

At 2026-10-17 01:14, the second controller-path review found that `prepareTurn()` reads the
idempotency record before replay/claim. A test checking only “no replay/claim/write” could still
allow an invalid PLAIN request to perform the lookup or return an idempotency-key conflict before
the intended 400. The bilingual contract, Slice A, and acceptance matrix now require rejection
before idempotency preparation/lookup, assert problem+json `error/status/path` with the real
`GlobalExceptionHandler`, and verify `prepare/inspectExisting/claim`, operation writes, and model
are untouched. This external API error-precedence gap resets planning review to `0/3`; no clean
round has run since the revision.

At 2026-10-17 01:12, the third acceptance review found that frontend regression was required but
the plan said only “core Mock Playwright spec,” without a directly executable command. After
checking `spring-ai-rag-webui/package.json` and `playwright.preview.config.ts`, the bilingual plan
now specifies `npx tsc -b --pretty false`, `npm run test:run`, `npm run build`, and
`npm run test:e2e:mock`; the last command uses the production preview and excludes `-real` specs.
This reproducibility gap resets planning review to `0/3`; no clean round has run since the fix.

At 2026-10-17 01:07, the third delivery-gate review found that this batch adds shared Native/OpenAI
HTTP request and response fields, while the project workflow requires TypeScript, relevant Vitest,
production build, and core Mock Playwright whenever a shared API contract changes, even without
editing WebUI files. The plan had marked WebUI as unaffected and omitted these checks. The bilingual
non-goal, completion definition, Slice D, and acceptance matrix now include them as supplementary
compatibility evidence that cannot replace Client API black-box/HTTP integration tests. This
substantive acceptance gap resets planning review to `0/3`; no clean round has run since the fix.

At 2026-10-17 01:03, a first-reader review found that the Chinese and English draft indexes still
explained the current `0/3` only by the earlier bilingual-structure correction and did not mention
the later Native Chat contract, Slice A, and SSE HTTP-error acceptance revisions. Both indexes
now describe the current restart context so a successor does not mistake the older cause as the
latest one. The index status correction leaves planning at `0/3`; no clean round has run since it.

At 2026-10-17 01:01, the bilingual check found that English Slice A required MockMvc to register
the real `GlobalExceptionHandler` and send the SSE `Accept` header, but Chinese Slice A only said
to use MockMvc generally. The Chinese implementation step now matches the English evidence. This
material difference could have produced different implementation evidence across languages, so
planning remains at `0/3`; no clean round has run since the fix.

At 2026-10-17 01:00, the second technical acceptance review found that the `/stream` rejection
required HTTP 400 but did not explicitly require MockMvc to register the real
`GlobalExceptionHandler` or to send `Accept: text/event-stream`. Without those checks, a test could
assert only an exception or accidentally accept an SSE frame or changed error envelope. The
bilingual Slice A and acceptance matrix now assert the existing HTTP error response under the SSE
Accept header, plus no model call or operation creation/claim. This important external API error
contract detail resets planning review to `0/3`; no clean round has run since this revision.

At 2026-10-17 00:57, the second cross-check found that the Native Chat `PLAIN + EACH_COLLECTION`
rejection had been added to the contract and acceptance matrix but omitted from Slice A's
file-level implementation sequence. Slice A now explicitly requires validation before idempotent
prepare/replay/claim on `/ask`, `/chat`, and `/stream`, HTTP 400 `BAD_REQUEST`, preserved omitted/
GLOBAL PLAIN behavior, and MockMvc assertions for no model call or operation creation/claim. This
implementation-closure gap resets planning review to `0/3`; no clean round has run since the revision.

At 2026-10-17 00:47, the third planning-review pass found that `RagChatController.resolveScope()`
returns an unscoped scope immediately for `ChatMode.PLAIN`, silently ignoring the new
`EACH_COLLECTION` parameter. The OpenAI-compatible entry had a planned PLAIN rejection, but Native
Chat lacked the matching external contract. The bilingual plan now requires HTTP 400 `BAD_REQUEST`
before replay/claim, model invocation, or SSE commitment on `/ask`, `/chat`, and `/stream`, and adds
API regression assertions while preserving omitted/`GLOBAL` PLAIN behavior. This compatibility gap
resets planning review to `0/3`; no clean round has run since the correction.

At 2026-10-17 00:44, a first-reader status check found the plan page, formal TODOs, and active-plan
indexes still presented the earlier `3/3` as current, contradicting this progress file's `0/3`.
Those bilingual status references now mark the earlier pass as historical and the current review as
`0/3`. This substantive status inconsistency was corrected before starting a fresh review sequence.

At 2026-10-16 21:04, code cross-checking found that `CompositeChatDocumentRetriever` merges
independent `STATIC_KNOWLEDGE` results with Collection retrieval, while the summary defined
`returnedCount` as the whole final user-visible list and required per-selected-Collection counts to
sum to it. Static knowledge has no authorized selected Collection ID, so both requirements could not
hold simultaneously. The bilingual plan now defines `returnedCount` and `perCollectionResultCounts`
over only the final post-rerank Collection-scoped retrieval list; static and other non-Collection
context remains available to Chat but is excluded from these counts. A matching acceptance assertion
was added. This substantive plan revision resets the counter to `0/3`; the previously reported `1/3`
does not count toward the new consecutive review sequence.

A 2026-10-16 21:06 first-reader review also found that the decision summary described smaller
Collections as being “systematically hidden” by global top-k. That read as an observed production
fact, contradicting the plan's own statement that no online quality evidence supports making coverage
the default, and exceeded what the checked code/metrics establish. The bilingual plan now states the
conditional ranking effect and explicitly says there is no production or goldenset evidence that this
is happening systematically in the current deployment. Planning restarts at `0/3`; no clean round has
been counted since this revision.

A 2026-10-16 21:11 contract review found that `CorsConfig` configures allowed origins, methods, and
request headers but not `Access-Control-Expose-Headers`. The planned `X-RAG-Collection-Coverage`
response header would therefore be unreadable to browser clients even when the origin is permitted.
The bilingual plan now requires exposing this response header on the existing `/api/**` and `/v1/**`
CORS mappings, without changing allowed-origin/request-header policy or the default-disabled CORS
behavior, and adds `CorsConfigTest` plus configuration-documentation evidence. This substantive
contract gap keeps planning at `0/3`; no clean round has been counted since the revision.

A 2026-10-16 21:16 first-reader review found that the plan called prior Client-contract, security-budget,
data-governance, recovery, and SDK work “complete,” while the active-plan index still marks related
delivery as in progress or pending Git delivery. The plan now removes that unsupported completion
dependency and relies only on the verified current GLOBAL retrieval and authorization behavior; it
does not assume other active batches are fully delivered. The bilingual plan was revised and planning
remains `0/3`; no clean round has been counted since the revision.

A 2026-10-16 21:19 API/cost review found that the plan used “reranking active” to decide whether to
expand per-Collection retrieval into a candidate pool but did not freeze that condition. Existing code
requires request `useRerank`, server `rag.rerank.enabled`, and a provider other than `none`/`noop`/`off`
before expanding candidate retrieval. Without this definition, EACH could incur avoidable candidate
fan-out when the client requests reranking but the server disables it or configures a no-op provider.
The bilingual plan now matches the existing `candidateRetrievalLimit` rule: EACH coverage remains
active, but inactive reranking uses only `finalLimit` candidates and calls no provider. Planning resets
to `0/3`.

An audit of the partial implementation found that `ReRankingService.rerank(..., finalLimit)` may
truncate provider output to the final limit before `retainCoverage` runs. That helper cannot restore
a Collection already dropped by provider top-k. The corrected design keeps the bounded candidate
pool through one full-depth rerank call, then selects the final limit while preserving coverage;
provider failure, short output, or a missing hit Collection falls back to the original candidate pool. EACH also caps
`limit/maxResults` at 100 (the legacy GET GLOBAL limit of 1000 is unchanged) to bound fan-out cost.
Technical review also requires a shared fan-out deadline no longer than the existing retrieval
timeout (queued tasks skip SQL after expiry), plus recomputing final Collection counts in outcome and
trace metadata after rerank. Cross-check also found the independent `KnowledgeSearchTool` rerank path.
OpenAI has no request-level `maxResults`; EACH computes effective max as max(existing default,
selectedCount), at most 20, without adding a request field or changing GLOBAL defaults. Rerank partial output is now defined as provider exception,
fewer returned rows than candidates, or omission of any hit Collection; each falls back to the
original candidate pool. OpenAI EACH is limited to `rag.mode=KNOWLEDGE`; PLAIN must reject the option
at `rag.scope.coverage_mode` instead of silently ignoring it. GLOBAL remains unchanged. Because these
affect correctness, cost, and client acceptance, the planning counter remains `0/3`; review restarts
at round one after revision. The `KnowledgeSearchTool` plan also raises an undersized model-supplied
result limit to selected-Collection count, bounded by the caller's Chat maximum.

A later concurrency cross-check found two substantive gaps. `HybridRetrieverService` currently calls
`embeddingModel.embed` synchronously, so retrieval timeout cannot bound the wait and full-text work
can be delayed by embedding. `PerformanceConfig` creates `ragSearchExecutor` with
`Executors.newFixedThreadPool(4)`, whose queue is unbounded; a per-request task cap alone does not
bound queue growth across concurrent callers. The revised plan isolates embedding on a four-worker,
32-slot bounded executor with immediate rejection and gates EACH branch submissions with a
non-blocking process-wide limit of 80 queued/running tasks. All branches share an absolute deadline;
expired queued branches skip SQL, and timeout/capacity degradation is diagnostic. Synchronous
provider/SQL calls cannot be forcibly cancelled by `CompletableFuture`; permits remain held until
the underlying task actually exits, and the model-client timeout remains the ultimate provider
release boundary. The bilingual plan must be synchronized before read-only review restarts from `0/3`.

The client-contract review then found that partial results were described only in internal traces.
Since Native POST Search returns a bare list, clients could not distinguish a true no-hit Collection
from an unfinished branch. The additive contract now returns a compact
`X-RAG-Collection-Coverage` JSON header for EACH Native GET/POST Search, uses existing Chat metadata
and OpenAI `rag.collectionCoverage`, and includes `incompleteCollectionIds`, `degraded`, and fixed
reason codes. GLOBAL/legacy requests get no new field/header; Search bodies and SSE event names stay
unchanged. This substantive revision keeps the planning counter at `0/3`; no clean review round has
yet been counted.

A bilingual-parity check found that the English plan lacked the Chinese plan's explicit goals,
completion definition, implementation semantics, and acceptance matrix, so an English-only
successor could not continue independently. Those sections have now been added; this substantive
documentation repair leaves planning at `0/3` and requires a fresh first review round.

The response-summary contract then proved underspecified: field calculations and the allowed public
reason codes/order were not frozen. The plan now defines requested/missing/incomplete/degraded
semantics, distinguishes complete no-hit from failed retrieval and rerank fallback, and fixes seven
sanitized reason codes with deterministic ordering. This is an API-contract correction; planning
remains at `0/3`.

The baseline review also found that the formal TODO describes global union top-k as correct and does
not claim evidence for making coverage the default. The plan's former “most obvious quality gap”
wording overstated available evidence; it now describes EACH as an opt-in additive strategy, not a
fix for a proven defect. The draft index also incorrectly said production code had not changed,
despite existing uncommitted WIP; both language indexes now accurately state that partial
implementation exists and further production changes are paused until planning reaches `3/3`. The
counter remains `0/3` after these corrections.

A further cross-check against the TODO and existing evaluation tooling found that latency, candidate
counts, coverage, and quality goldenset evidence were required but the plan only listed functional
integration and smoke tests. It now adds a versioned multi-Collection GLOBAL/EACH comparison,
deterministic coverage/source/ACL/resource assertions, and MRR/Recall@K/nDCG/candidate/p95 reporting.
EACH is not required to beat GLOBAL because its ranking policy differs, and a single LLM judge is not
a pass criterion. Planning remains `0/3` after this substantive acceptance update.

At 2026-10-16 22:04, a first-reader review found “citation/evidence assembly sees only the final list”
ambiguous: a successor could interpret it as excluding independently merged static knowledge from
Chat citations, despite the plan retaining that context in the prompt and excluding it only from
Collection coverage counts. Code confirms `CompositeChatDocumentRetriever` independently merges the
static documents, `StaticKnowledgeDocumentRetriever` records their sources, and Chat extracts sources
from the assembled document context. The bilingual plan now states that Collection citations use only
the final coverage-selected results (never pre-rerank candidates), while non-Collection context remains
eligible for citations/evidence under existing behavior and stays outside the coverage counts. This
substantive clarification resets planning review to `0/3`; no clean round counts after the revision.

At 2026-10-16 22:08, the technical/cost review found that a merged candidate-pool cap of 100 and a
branch-task cap did not explicitly bound raw rows fetched before fusion. The current EACH implementation
uses `2 * retrievalLimit` for each vector/full-text branch; with a maximum retrieval limit of 100, 20
Collections, and two branches per Collection, this can return up to 8,000 pre-fusion rows per request.
The bilingual plan now freezes the existing 2x over-fetch, caps each branch at 200 rows and raw
pre-fusion rows at 8,000 per request, retains the merged candidate/final-result cap of 100, and adds
hard acceptance assertions for these limits. This substantive cost-bound clarification resets planning
review to `0/3`; no clean round counts after the revision.

At 2026-10-16 22:24, delivery-scope review found that the formal bilingual TODO treated a built-in
WebUI advanced option as a future hard constraint, while this batch explicitly prioritizes external
HTTP Client/API behavior and excludes UI redesign. To honor the service's API-first product boundary
without substituting UI tests for client acceptance, the bilingual TODO and plan now mark coverage as
active in this batch, keep the current API contract until release acceptance, and defer a built-in UI
option as a separate optional follow-up. External API/Client acceptance remains mandatory. This
substantive scope/documentation correction resets planning review to `0/3`; no clean round counts after it.

At 2026-10-16 22:26, a first-reader status check found the Chinese TODO said the plan had passed an
initial review despite the current counter being `0/3`; the English status also omitted the existing
partial WIP and implementation pause. Both TODOs now state that planning review is `0/3`, partial WIP
exists, production changes remain paused until review passes, and the public API remains unchanged
until acceptance/release. This status correction is included in the current `0/3`; no clean round has
been counted since the revisions.

At 2026-10-16 22:09, the final acceptance-matrix parity check found that the English plan required
hard assertions for 200 rows per branch, 8,000 raw rows per request, and a 100-item merged/final cap,
while the Chinese row still said only “bounded.” The Chinese matrix now names the same numeric limits.
This acceptance-parity correction resets the planning review to `0/3`; no clean round counts after it.

At 2026-10-16 22:17, concurrency feasibility review found an underspecified scheduling hazard: if
vector tasks wait for the asynchronous embedding future from inside `ragSearchExecutor`, four slow
embedding waits can occupy all retrieval workers and prevent independently runnable full-text tasks
from progressing. The bilingual plan now freezes the safe order: submit full-text branches
independently, let only the request-coordination thread await embedding within the shared deadline,
then submit vector branches after success; embedding failure leaves full-text work running. It adds an
explicit latch-based acceptance assertion. This substantive concurrency clarification resets planning
review to `0/3`; no clean round counts after the revision.

On 2026-10-17, the documentation integrity gate found that the English plan's heading hierarchy did
not match the Chinese plan. The English plan was reorganized into the same nine-section tree while
preserving its contract and implementation details. This structural documentation finding resets the
current planning review to `0/3`; a fresh three-round read-only review is required.

## Frozen Decisions

- This batch is bounded `EACH_COLLECTION` coverage only; no OAuth/OIDC, tenant hierarchy, billing settlement, or WebUI redesign.
- `GLOBAL` remains the existing default; explicit EACH mode needs no operator toggle.
- EACH requires explicit `SELECTED_COLLECTIONS`, at most 20 Collections, and a result limit at least the selected count.
- EACH has a mode-specific result ceiling of 100; GET Search's existing 1000 GLOBAL limit remains unchanged to bound explicit fan-out cost.
- Query embedding runs once per request on an isolated bounded executor; at most 80 EACH branches may be queued/running process-wide; empty Collections get no fabricated result.
- Existing endpoints, fields, constructors, SSE events, errors, and idempotency remain compatible; the OpenAI RAG extension keeps its old four-argument constructor while adding an optional field.

## Planning Review Log

The following rounds document the initial plan review; they are superseded by the substantive
findings above and do not count toward the current review. The current counter is `0/3`.

| Round | Time | Scope | Findings/Action | Result |
|---|---|---|---|---|
| Round 1 | 2026-10-16 | Requirements, self-containment, defaults, non-goals, recovery, code anchors | No issue; no modification | `1/3` |
| Round 2 | 2026-10-16 | API/DTO, authorized scope, OpenAI adapter, executor, result mapping, source compatibility | No issue; no modification | `2/3` |
| Round 3 | 2026-10-16 | Implementation order, test matrix, gates, bilingual docs, rollback, Git delivery | No issue; no modification | `3/3` |

## Implementation Slices

| Slice | Content | Status |
|---|---|---|
| A | API DTOs, authorized scope, OpenAI mapping | Partially implemented; review pending |
| B | One embedding, bounded fan-out, stable assembly, diagnostics | Partially implemented; rerank coverage closure pending |
| C | Chat fingerprint/snapshot and JSON/SSE/replay | Partially implemented; targeted tests pending |
| D | PostgreSQL/HTTP/SDK/startup/real-model/docs/Git acceptance | Not started |

## Recovery Entry

1. Run `git status --short --branch`.
2. Read plan sections 4–9 and the latest checkpoint here.
3. Never use `git reset --hard`, `git stash`, or `git clean`.
4. If a substantive planning issue is found, fix the documents and reset planning to `0/3` before touching production code.
