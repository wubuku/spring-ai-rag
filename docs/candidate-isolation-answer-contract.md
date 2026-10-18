# Candidate Isolation, Stage Deadlines, and Answer Contracts

> [English](candidate-isolation-answer-contract.md) | [中文](candidate-isolation-answer-contract-zh-CN.md)

External clients are the delivery boundary: existing endpoints, required parameters, HTTP statuses, error codes,
finishReason, and SSE terminal ordering remain unchanged. Protections are enabled by default; business-specific
output constraints are optional. There is no schema change, migration, manual data work, judge call, automatic
repair call, or automatic diagnostic capture on ordinary Chat requests.

## 1. Candidate Fault Isolation

`BudgetedChatModel` obtains a candidate permit before admission, model-call accounting, and cost reservation.
OPEN candidates create no phantom invocation, usage, or reservation. They may fall through to the next candidate,
but are not retried themselves. Streaming fallback is allowed only before the first event; never splice models.

The fault domain is the actual `ChatModel` object identity plus its configured model name, not an arbitrary client
alias. Aliases sharing an instance share state; distinct instances are isolated. Different endpoints normally
have different instances. No URL, credential, or content is exposed as a public key. State is node-local memory,
cleared on restart; multiple nodes probe independently, without a distributed-circuit guarantee.
There are at most512 domains; capacity exhaustion fails closed instead of evicting OPEN entries to hide failures.

Only classified temporary upstream failures count. Permanent request errors, authorization/budget rejection,
cancellation, and normal safety refusals do not. HALF_OPEN allows one concurrent probe; ignored/cancelled calls
release the probe slot. Each permit terminates at most once. All candidates OPEN still uses `LLM_CIRCUIT_OPEN`.
Permits bind a circuit generation: late old success/failure/cancellation cannot close or release a new probe.
Window updates are atomic within a short in-memory critical section.
Existing public service circuit methods and constructors remain; Spring production wiring uses candidate gates
instead of a global gate that blocks healthy models. See [upstream recovery](upstream-client-recovery.md).

## 2. Independent Optional-Stage Deadlines

Existing conditions still decide whether query rewriting/expansion runs. New protection does not enable otherwise
disabled enhancement calls. Actual waiting is `min(stage limit, remaining request time - answer reserve)`.
Insufficient time yields `SKIPPED_DEADLINE`, a full zero-queue pool `SKIPPED_CAPACITY`, timeout `TIMED_OUT`, and
ordinary dependency failure `FAILED`. Keep the original query; never swallow authorization, hard budgets, or other
business rejection. Caller cancellation propagates rather than continuing the final answer.

Time reserves also require **headroom in the same physical admission pool**. QUERY_TRANSFORM/QUERY_EXPAND use
optional admission before billing/call accounting. Foreground enhancement runs only below half of each global,
principal, and provider limit; mandatory calls retain their existing hard limits. Background enhancement has
threshold `maxBackgroundModelCalls - min(maxBackgroundModelCalls, runConcurrency * maxConcurrentRuns)`, reserving
slots for configured evaluation concurrency. With two default background slots and two effective concurrent cases,
background enhancement automatically reports SKIPPED_CAPACITY; actual retrieval/final LLM/assertions still run.
No quota expansion, disabled protection, or expected-fact injection is used to manufacture a pass; online enhancement
is not switched off. This is explicit resource degradation, not a fake evaluation pipeline. Skips incur no reservation,
provider call, or HTTP429. `rag.admission.optional.skipped` uses fixed pool labels only. Late physical calls keep their
same-pool permits until they really finish.
Evaluation `configurationSnapshot.queryEnhancementPolicy` records policy version/admission limits, while per-case
`metrics.queryEnhancements` records effective degradation; configured enhancement is not falsely reported as executed.

| Configuration / environment | Default |
|---|---:|
| `rag.circuit-breaker.enabled` / `RAG_CIRCUIT_BREAKER_ENABLED` | `true` |
| `rag.chat.knowledge.query-transform-timeout-seconds` | `30` (existing setting) |
| `rag.chat.knowledge.query-expansion-timeout-seconds` / `RAG_CHAT_QUERY_EXPANSION_TIMEOUT_SECONDS` | `5` |
| `rag.chat.knowledge.query-enhancement-answer-reserve-seconds` / `RAG_CHAT_QUERY_ENHANCEMENT_ANSWER_RESERVE_SECONDS` | `10` |
| `rag.chat.knowledge.query-enhancement-max-concurrency` / `RAG_CHAT_QUERY_ENHANCEMENT_MAX_CONCURRENCY` | `8` |

New time limits accept1–300 seconds, concurrency1–64. Existing circuit thresholds/windows/cooldown remain.
Workers are separately bounded and do not inherit authentication/private diagnostic ThreadLocals; immutable
execution context carries authorization and budgets. Timeout requests cancellation, but providers ignoring
interrupts retain their physical worker/permit/reservation until the call actually ends. The main answer can
continue; this does not forcibly kill a provider. `metadata.executionBudget.queryEnhancements` adds the two fixed
stage names, statuses, and durations without content. Limits/concurrency or enhancement switches are reversible.

Completed physical calls temporarily clear an existing interrupt flag only during bounded reservation settlement and
usage recording, then restore it in `finally`. Cancellation, physical permits, original failures, accounting timeouts
and provider retry behavior remain unchanged; a late completion must not lose accounting merely because it was interrupted.

## 3. Request-Level Answer Contracts

Native `POST /api/v1/rag/chat/ask` and `/stream` accept an optional field:

```json
{
  "message": "Return the verified quantity and status",
  "mode": "KNOWLEDGE",
  "answerRequirements": {
    "rules": [
      {"id":"code","type":"TOKEN","value":"SAFE-42"},
      {"id":"count","type":"QUANTITY","value":"0","unit":"条"},
      {"id":"shape","type":"JSON_FIELD","field":"status"}
    ]
  }
}
```

This demonstrates shape, not universally available facts; supply existing scope normally.
OpenAI `POST /v1/chat/completions` uses `rag.answer_requirements` (camelCase `answerRequirements` alias accepted).
Absent/explicit null preserves old behavior and idempotency fingerprints. Changed rules change the fingerprint;
reusing a key with changed rules returns the existing409. Invalid rules fail before invocation: JSON uses400;
unkeyed Native streams retain HTTP200 + error/BAD_REQUEST, without a successful terminal event. Keyed fingerprint
validation occurs before claim. Older
servers may ignore unknown fields; clients must verify the returned contract summary instead of assuming support.

- `rules`:1–16 entries, unique id1–64 characters; unknown types/fields and arbitrary regex are rejected.
- `TOKEN`: value up to128 characters; deterministic identifier boundaries reject `SAFE-420` for `SAFE-42`.
- `QUANTITY`: value/unit up to32 characters; bounded decimal matching (18 integer/9 fractional digits), with
  both quantity and unit required. Never invent units/facts. See [structured assertions](trustworthy-evaluation-admission.md).
- `JSON_FIELD`: a top-level simple identifier (letter first, then letters/digits/underscores, at most64 characters).
  The whole answer must be one JSON object with a non-null field. No fences, trailing text, duplicate keys, or
  nested JSONPath. Parsing is bounded to64 levels,128-character numbers, and262144 answer characters.

Rules are not evidence, do not override authorization/safety, and are not semantic truth judges.
Native `metadata.answerContract` and OpenAI `rag.answerContract` add `status`, `rules[{id,type,passed}]`,
`safeAnswerApplied`, and a low-sensitivity `reasonCode` for normal checks, never matched content.
Statuses: `PASSED`, `FAILED`, `SKIPPED_SAFETY_REFUSAL`.

Failure returns a fixed safe explanation with `answerOutcome=SAFE_FALLBACK`, `reasonCode=answer_contract_failed`,
without paid repair. Existing evidence safeguards take precedence. Explicit provider safety refusals remain normal
business outcomes: skip contracts, never retry/change models to bypass them. HTTP and finishReason are unchanged.
All modes with contracts buffer within the existing bound before emitting content. Overflow forces safe failure;
noncompliant tokens cannot leak first and be corrected later. Unconstrained PLAIN/AGENT retain existing live streams;
KNOWLEDGE retains its evidence buffer. Keyed success, state projection, and exact replay preserve contract/evidence/
answer-outcome summaries, not private diagnostic content. Clients inspect summaries, not just200 or `[DONE]`.

## 4. Field-Level Diagnostic Completeness

Existing double acknowledgement, ACL, expiry, artifact integrity, no-store, and capture limits remain as documented
under [active diagnostics](trustworthy-evaluation-admission.md). Ordinary Chat does not capture private content.
`capture.captureCompleteness` independently covers `lastInvocationMessages`, `evidence`, `sourceProjections`,
`modelAnswer`, and `answer`: each has `present/complete/truncated/redacted/originalBytes/capturedBytes/reasons`.
Counts accumulate all inputs for that field during the capture, not just the final overwritten value.
Truncation/redaction is sticky; later small values cannot erase earlier incompleteness. UTF-8 truncates at code-point
boundaries; no hash of omitted/redacted content is disclosed.

`knowledge-qa-diagnostics.py replay` retains the overall hard gate. With an incomplete overall capture but complete
final answer, `partialVerification` can check final-answer lexical assertions only; a complete original model answer
adds model assertions. Top-level remains `INCONCLUSIVE`, with scope
`FINAL_ANSWER_LEXICAL_ONLY_NOT_EVIDENCE_OR_AUTHORIZATION`. It cannot verify evidence/authorization/final input or
upgrade an original FAILED. Old artifacts without per-field information, missing/redacted answers, invalid artifacts,
and assertion-engine disagreements cannot claim partial success. Local replay has `networkCalls=0`, no model fee.

## 5. Acceptance

```bash
TESTCONTAINERS_PG_IMAGE=postgres:16-pgvector python3 scripts/verify-candidate-contracts-e2e.py
python3 scripts/test-support/trustworthy-evaluation-self-test.py
python3 scripts/verify-dev-lifecycle-e2e.py --ack-paid-models
python3 scripts/verify-quality-real-e2e.py --ack-paid-models --providers openai,minimax --repeats 2 \
  --definition testdata/knowledge-qa-quality/v2/definition.json --with-diagnostics --ack-sensitive-content --with-answer-contracts
```

The first uses isolated real PostgreSQL/HTTP with a controlled provider for recovery/contracts/budgets, **not real
model quality**. The dev gate privately loads`.env`, isolates its database/backend/frontend ports, and verifies real
models, legacy OpenAPI, browsers, and start/stop ownership; do not share launcher state with another dev.sh instance.
Real-model, multi-node, recovery, and compatibility gates are in the [testing guide](testing-guide.md).
Serialize all Maven processes sharing a workspace. No client migration is required; disabling circuits is an explicit,
reversible diagnostic switch, not the default legacy path.
