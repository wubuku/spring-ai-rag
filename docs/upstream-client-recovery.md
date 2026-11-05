# Upstream Failures and Client Recovery

Circuits now default to [candidate isolation](candidate-isolation-answer-contract.md#1-candidate-fault-isolation),
not a global gate blocking healthy models; refusals remain normal outcomes and existing error envelopes remain unchanged.

> [English](upstream-client-recovery.md) | [中文](upstream-client-recovery-zh-CN.md)

## Compatibility and Defaults

Classification and protections are enabled by default, without migration or operator configuration.
Existing HTTP statuses, error/code, failureClass and JSON/SSE envelopes remain stable. Recognized upstream
failures are no longer emitted as 500 (see the mapping below); unclassified exceptions keep the existing
contract: 500 for native and 503 for the OpenAI-compatible layer. HTTP200 alone never proves an SSE answer
succeeded. See the [Client Kit](client-contract-kit.md) for existing coarse failure classes.

### Upstream failure HTTP contract (measured with real LongCat calls)

| Upstream failure | Native `/api/v1/rag/chat/ask` | `X-RAG-Failure-Class` | `failureDetails.reason` | OpenAI-compatible |
|---|---|---|---|---|
| 429 (upstream `Retry-After: 2`) | 429 | RATE_LIMITED | UPSTREAM_RATE_LIMITED | 503 with `Retry-After` passed through |
| 500 / 503 | 503 | DEPENDENCY_UNAVAILABLE | UPSTREAM_UNAVAILABLE | 503 |
| Read timeout | 504 | TIMEOUT | UPSTREAM_TIMEOUT | 503 |
| Connection reset/close | 503 | DEPENDENCY_UNAVAILABLE | UPSTREAM_CONNECTION | 503 |
| Upstream disconnect after SSE start | 200 + `error` terminal event | — | UPSTREAM_RESPONSE_INVALID | 200 + `error` event |
| Upstream 401/403 | 503 | LLM_UNAVAILABLE | UPSTREAM_AUTHENTICATION / UPSTREAM_REJECTED | 503 |

429 and 5xx still use bounded retries (attempts, backoff bound and deadline all apply) instead of failing
immediately. `Retry-After` is honored only when the wait fits safely, and the final error response echoes
a client-acceptable `Retry-After`.

`failureDetails.upstreamMessage` carries the real reason extracted from the upstream error body: masked,
control characters removed, whitespace collapsed and truncated to 300 characters (for example MiniMax's
`rate_limit_error ... Token Plan quota ... (2056)`). It only appears when a body can be extracted; it is a
new optional `NON_NULL` field and does not change existing field semantics. It separates distinct root
causes that share one status code, such as a plain rate limit versus quota exhaustion. Even without a
body, ordinary 429/5xx/timeout failures still carry `reason`, `upstreamStatus` and `retryable`.

Evidence is produced by
[testing-guide.md](testing-guide.md#real-model-failure-budget-and-provider-neutrality).


Error objects add `failureDetails`:

```json
{"origin":"UPSTREAM","reason":"UPSTREAM_RATE_LIMITED","retryable":true,"retryAfterMs":2000,"upstreamStatus":429,"upstreamMessage":"…masked real reason…"}
```

Origins are UPSTREAM/REQUEST/SERVICE. Reasons contain no provider bodies, URLs or credentials.
`upstreamMessage` is a separate optional field: a masked, control-character-stripped, 300-character
truncation of the upstream error body. Native JSON and SSE expose it in their error object; OpenAI
exposes it inside error. Older snapshots may omit it.
Provider401/403 must not become caller authentication failures. Safety words in plain text do not prove policy refusal.

## Reasons and Execution Decisions

| reason | Default behavior |
|---|---|
| UPSTREAM_AUTHENTICATION / UPSTREAM_REJECTED | No retry/fallback; correct deployment configuration or unsupported request |
| UPSTREAM_RATE_LIMITED / UPSTREAM_UNAVAILABLE / UPSTREAM_TIMEOUT / UPSTREAM_CONNECTION | Existing retry switches plus attempt, deadline and cost budgets; fallback only before the first stream event |
| UPSTREAM_RESPONSE_INVALID | Do not retry a parse failure as network I/O |
| REQUEST_DEADLINE / CANCELLED / BUSINESS_REJECTED / UNCLASSIFIED | No automatic retry/fallback or dependency circuit failure count |

Bounded cause traversal uses structured statuses, not provider error text. SDK exceptions without structured
evidence remain conservative; not every SDK exposes an HTTP status. Chat SDK-internal retries are disabled
in favor of application-budgeted retries. Backoff uses configured exponential intervals and jitter.
An Anthropic streaming transport filter preserves structured status and discards error bodies before the SDK
can concatenate them into an untyped exception or log message.
Valid Retry-After seconds/HTTP dates yield hints capped at10 seconds. When the requested wait reaches the
configured bound or cannot fit the deadline, execution ends rather than retrying before the provider permits.
Ignored business errors and cancellation release half-open probe occupancy rather than count as model failures.

retryable indicates potentially transient cause, not replay safety. Clients do not retry automatically:
query keyed turns first; report partial streams instead of concatenating another answer. Only explicitly safe
requests may be retried with bounds. A terminal failed turn still replays its failure with the same key.
Python/Node examples disable SDK automatic retries and provide local recovery-decision functions.

## Business Answer Outcomes

Native metadata.answerOutcome and OpenAI rag.answerOutcome provide status/reasonCode:

- MODEL_SAFETY_REFUSAL: explicit provider refusal/content_filter; normal result, no bypass retries/fallback.
- SAFE_FALLBACK: evidence protection replaced output; the factual question was not necessarily answered.
- OUTPUT_TRUNCATED: length/max_tokens completion; original finishReason remains.
- OUTPUT_INCOMPLETE: missing model finishReason; do not claim an incomplete output is a complete answer.
  Native SSE may still send `done`, but `status=incomplete` so it never masquerades as `complete`.
- ANSWER: other completed responses; neither this status nor a valid citation proves factual correctness.

Precedence is refusal, safe fallback, truncation/incompleteness, answer. Failures/cancellation keep existing error
paths, not fabricated business answers. Existing empty-stream behavior remains; the Consumer Kit rejects
streams without a terminal event.

## Embedding Failure Degradation

Upload/import (SYNC/ASYNC job) and explicit `POST /rag/documents/{id}/embed` return `FAILED` instead of a
500 stack trace for provider 5xx/429/timeouts/connection drops/malformed responses. The `/embed` body adds
`failureClass`, `failureDetails` and `retryable`, and mirrors the class in `X-RAG-Failure-Class`. Commit-time
version/Collection races return 409 with `CONFLICT` and no stack trace.

Retrieval degrades to full-text search when the query embedding fails (hybrid search still returns full-text
results), recorded as `operation=retrieval.embedding`. Whole-batch upstream failures no longer fan out per
chunk, so provider calls do not scale with chunk count; per-item business errors still retry individually.

Retries are bounded: the transport `RetryTemplate` defaults to 3 attempts with exponential backoff
(2s base, 10s cap, Retry-After respected), and the job layer defaults to `default-max-attempts=3`.
Each provider call is bounded by `rag.embedding.request-timeout-ms` (default 30000, range 100-120000),
so a call never waits forever even without an admission deadline. The per-document worst case is
`ceil(chunks/batchSize) × transport attempts × job attempts`.

Documents without a Collection (`collection_id IS NULL`) can still generate/replace vectors; submission is
rejected only when the Collection is not writable. Failed commits leave `rag_embeddings` untouched and record
`last_error` on the job, so no half-written vectors survive.

Observability: `rag.upstream.failures` is tagged by `operation/provider/reason/retryable`, with `operation`
covering `chat`, `chat.stream`, `embedding.document`, `embedding.job` and `retrieval.embedding`.
Structured logs carry `provider`, `reason`, `retryable` plus `jobId/documentId/profileId/traceId`.

## WebUI Failure Presentation

The WebUI never renders `X-RAG-Failure-Class`, `HTTP 503` or provider English text directly;
`spring-ai-rag-webui/src/utils/failureClass.ts` is the single interpretation layer:

- Classes and `failureDetails.reason` map to paired EN/ZH copy (`failures.*`), with the reason
  preferred over the coarse class. Without a class header, HTTP status is the fallback:
  409 conflict, 429 rate limit, 502/503 upstream unavailable, 504 timeout.
- Only `CLIENT_REQUEST`/`CONFLICT`/`NOT_FOUND` append the server reason; rate limits, upstream
  outages, timeouts and auth failures get localized copy only, never a technical stack string.
- `AUTHENTICATION`/`AUTHORIZATION`/`CLIENT_REQUEST`/`NOT_FOUND` offer no retry entry; every
  other class, including unknown ones, does.
- Chat treats `done.status=incomplete` and a truncated SSE stream as terminal: it shows an
  incomplete marker plus a retry entry, never renders the reply as a clean completion or leaves
  a loading cursor, and reuses the same prompt on retry.
- An `/embed` HTTP 200 with `status=FAILED` is reported as a failure, and upload failures read
  the class instead of surfacing the HTTP status.

The matching Mock Playwright coverage is
`spring-ai-rag-webui/e2e/failure-modes.spec.ts` (see the [testing guide](testing-guide.md)).

## Private Diagnostics and Acceptance

[Dual-acknowledged diagnostics](trustworthy-evaluation-admission.md) add modelAnswer (same invocation before
safety replacement), answer (delivered text), answerOutcome and citationValidation. modelAnswer is capped
at32KiB; total captured text is capped at224KiB. Redaction/truncation makes capture incomplete.
Ordinary Chat neither captures text nor makes additional model calls. Bodies stay out of metrics/shared diagnostic
logs. Local replay adds original/final assertion summaries but preserves the original hard-gate verdict and history.
Citation prompts list request-local IDs without repairing invalid citations. Numeric units are required only
when the question explicitly asks for them, using source units.

Run `python3 scripts/verify-upstream-recovery-e2e.py` for the chat failure matrix (isolated PostgreSQL, real
HTTP, controlled provider), `python3 scripts/verify-embedding-failure-e2e.py` for embedding upload/import/
retrieval degradation, call bounds, metrics and logs, and `bash scripts/verify-client-consumer-contract.sh`
for offline contracts and both recovery helpers. Both failure scripts run without real models in the nightly
`failure-modes` job. Controlled fault tests are not real-model quality evidence. Real embedding/LLM, two-node
recovery and dev lifecycle are accepted separately.
