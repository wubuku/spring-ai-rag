# Upstream Failures and Client Recovery

Circuits now default to [candidate isolation](candidate-isolation-answer-contract.md#1-candidate-fault-isolation),
not a global gate blocking healthy models; refusals remain normal outcomes and existing error envelopes remain unchanged.

> [English](upstream-client-recovery.md) | [中文](upstream-client-recovery-zh-CN.md)

## Compatibility and Defaults

Classification and protections are enabled by default, without migration or operator configuration.
Existing HTTP statuses, error/code, failureClass and JSON/SSE envelopes remain stable. Unclassified native
errors remain 500; OpenAI-compatible errors remain 503. HTTP200 alone never proves an SSE answer succeeded.
See the [Client Kit](client-contract-kit.md) for existing coarse failure classes.

Error objects add `failureDetails`:

```json
{"origin":"UPSTREAM","reason":"UPSTREAM_RATE_LIMITED","retryable":true,"retryAfterMs":2000,"upstreamStatus":429}
```

Origins are UPSTREAM/REQUEST/SERVICE. Reasons contain no provider bodies, URLs or credentials.
Native JSON and SSE expose it in their error object; OpenAI exposes it inside error. Older snapshots may omit it.
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
- ANSWER: other completed responses; neither this status nor a valid citation proves factual correctness.

Precedence is refusal, safe fallback, truncation/incompleteness, answer. Failures/cancellation keep existing error
paths, not fabricated business answers. Existing empty-stream behavior remains; the Consumer Kit rejects
streams without a terminal event.

## Private Diagnostics and Acceptance

[Dual-acknowledged diagnostics](trustworthy-evaluation-admission.md) add modelAnswer (same invocation before
safety replacement), answer (delivered text), answerOutcome and citationValidation. modelAnswer is capped
at32KiB; total captured text is capped at224KiB. Redaction/truncation makes capture incomplete.
Ordinary Chat neither captures text nor makes additional model calls. Bodies stay out of metrics/shared diagnostic
logs. Local replay adds original/final assertion summaries but preserves the original hard-gate verdict and history.
Citation prompts list request-local IDs without repairing invalid citations. Numeric units are required only
when the question explicitly asks for them, using source units.

Run `python3 scripts/verify-upstream-recovery-e2e.py` for isolated PostgreSQL/real HTTP with a controlled
provider, and `bash scripts/verify-client-consumer-contract.sh` for offline contracts and both recovery helpers.
Controlled fault tests are not real-model quality evidence. Real embedding/LLM, two-node recovery and dev
lifecycle are accepted separately.
