# Quality Stability And Production Recovery Acceptance

Additive capabilities: [trustworthy assertions, active diagnostics and offline quality admission](trustworthy-evaluation-admission.md); existing API contracts and ordinary Chat evaluation costs are unchanged.

> [English](quality-production-acceptance.md) | [中文](quality-production-acceptance-zh-CN.md)

These are explicitly invoked quality, two-node, and recovery checks, not a new scheduler or additional evaluation
charges on ordinary Chat. Public contracts remain in the [REST API](rest-api.md) and [Client Contract Kit](client-contract-kit.md).

## Versioned Real Question-Answer Matrix

The existing `run-knowledge-qa-evaluation.py` command still defaults to three smoke cases and two rounds.
`--profile quality` accepts 1–200 unique cases and 2–10 rounds (default three), selecting one variant per invocation.
The service executes actual KNOWLEDGE_QA and deterministic expected-source, required/forbidden-fact,
insufficient-evidence and citation assertions. Judge must be disabled. Valid citation syntax does not establish factual support.

```bash
python3 scripts/test-support/knowledge-qa-quality-self-test.py
# Privately configure KNOWLEDGE_QA_BASE_URL/API_KEY/DEFINITION/SUITE_KEY first:
python3 scripts/run-knowledge-qa-evaluation.py --profile quality --repeats 3 \
  --output-directory .verification/my-quality-run
```

The output directory must not exist: directory0700/files0600. Run/comparison/summary artifacts contain private sources,
facts and traces; never commit them. Failed answers remain visible while bounded later rounds collect additional evidence.
Execution/provider/transport failures stop subsequent paid rounds. Missing/duplicate/unknown case IDs, wrong variants,
deterministic failures, drift, or incomplete configuration/model attribution fail closed.
In addition to server comparison, the client checks the full configurationSnapshot, code, embedding profile,
definition SHA and actual model set.

Per-case summaries retain round, actual model, latency, usage, citation status, trace, failureReasons and aggregate metrics.
`ANSWERABLE_REFUSAL` means an answerable case was replaced or refused, not a proven retrieval/model root cause.
Do not invent cost or exact tokens without configured pricing/provider usage, or label measured latency a production SLA.
QA metrics add `actualModel` (provider-reported name, otherwise configured name), `modelRef` (route alias),
`modelAttribution` (reported/configured/unknown attribution), `modelDefaults`, and `evidenceAssessment`; existing fields and HTTP envelopes remain unchanged.
`modelDefaults` contains only non-null configured temperature/topP/topK/maxTokens/frequencyPenalty/presencePenalty values,
not credentials, request content, or effective per-request budget overrides. These values, route aliases, and actual names participate in drift checks.
Active QA uses existing diagnostics settings, privacy projections, and owner authorization. A case `traceId`
is the retrieval-diagnostics UUID, not the HTTP request trace string. Disabled/unavailable diagnostics may leave it null;
diagnostics failures do not change quality assertions, and provider failures retain a trace when possible.
For smoke-output compatibility, top-level `qaPassRate` / `totalTokens` still describe the first round;
use `caseResults` / `aggregateMetrics` for all rounds. Case usage is the final-answer provider report,
not independent query-expansion calls. Use the existing LLM usage ledger for all-call accounting rather than treating this as a full-chain billed total.

`testdata/knowledge-qa-quality/v1/` contains24 synthetic cases/corpus, not a customer goldenset: numbers, units,
tables, negation, paraphrases, versions, cross-document facts, hostile reference instructions, contradictions,
insufficiency and Collection isolation. Version fixture changes in a new directory; never weaken assertions to fit a model.
Unit tests additionally cover truncation, safe citations and valid-looking citations attached to incorrect facts.

## Controlled Two-Node And Recovery Acceptance

```bash
python3 scripts/verify-quality-production-e2e.py
```

Creates an owned PostgreSQL container, two independent backends and a loopback controlled upstream. Covers cross-node
upsert replay/CAS without mutation, principal overload fairness, immediate revocation/deletion, positive/negative
24-case repeated gates, dependency recovery, killed-node lease takeover and terminal budget reservations,
plus deletion/authorization gates after restoring an old backup. Private artifacts live in `.verification/quality-production/`;
owned processes/containers are cleaned up. Controlled answers prove protocol/assertion behavior, not real model quality.
Only disposable databases are used; user development databases are not cleaned.

Admission remains **instance-local**, not a shared-provider global concurrency guarantee across replicas.
Existing principal limits protect online fairness, not strict background fairness. Existing database mechanisms coordinate
shared quota, budget and tasks. See [Backup And Restore](backup-restore.md#post-restore-deletion-and-authorization-gate)
for trusted recovery inputs, inventory and exclusions.

## Explicit Real Model Calls

Run offline/controlled checks first, then privately export authorized model/embedding environment variables:

```bash
python3 scripts/verify-quality-real-e2e.py --ack-paid-models --embedding-profiles profile_a,profile_b,profile_c
```

Defaults to `openai,minimax`; each LLM×Embedding Profile runs 24 cases×3 rounds:432 QA executions plus Client JSON/SSE/SDK and HTTP
rerank checks. The three profiles must include two different models at one dimension and one at another. The runner creates isolated Collection copies and rewrites the same evaluation definition per profile to exercise real embedding and retrieval routing. `--providers openai` or `--repeats 2` explicitly narrows coverage; never report one provider as two.
`--definition /private/subset.json` selects a versioned subset referencing the same synthetic corpus;
report its actual case count rather than presenting it as the full24-case matrix. This executor explicitly
enables existing diagnostics persistence, requires complete case trace attribution, and probes trace readability;
it does not change diagnostics defaults for service deployments.
It neither reads nor prints `.env`. Inject `SPRING_AI_OPENAI_*`, `SPRING_AI_MINIMAX_*` and, for each profile,
`RAG_QUALITY_PROFILE_<KEY>_API_KEY`, `_BASE_URL`, `_PROVIDER`, `_MODEL` and `_DIMENSIONS` into the environment; base URLs must not include `/v1`. Profile settings reach Spring through its private process environment, not command-line arguments or result summaries. External models configuration is disabled to identify the selected provider; HTTP rerank uses existing
credential inheritance. Runs use an owned isolated database and `.verification/quality-real/` artifacts, never normal startup.

For models with configurable output dimensions, explicitly set optional
`RAG_QUALITY_PROFILE_<KEY>_REQUEST_DIMENSIONS=true`; the field is omitted by default.
Before starting the database, the runner checks `/v1/models` and `/v1/embeddings` per Profile,
verifying model presence, actual dimensions and finite nonzero vectors. Failures expose only sanitized
status and prevent matrix execution. Use `--preflight-only` for this check alone; preflight is not quality acceptance.

Report real quality failures separately from structural/API failures. Neither can be erased through review, model switching,
rerunning or deleting earlier results. Preserve a combined runner's nonzero result when any model misses its quality gate;
passing Client API checks does not admit that model under the quality matrix. Explain service behavior, factual quality,
and normal safety refusals separately. Repeated finite cases improve traceable confidence, not a claim of100% factual accuracy
or equivalent performance on arbitrary customer corpora.

The default answer prompt restates authentic-citation and explicit-insufficiency requirements after the evidence
and question, reducing constraint loss in long contexts. This does not synthesize citations or add a second
model call; deterministic gates and safe-answer rules remain unchanged.
An explicit model safety-alignment refusal is a normal business result, not a service exception. It must not
be ignored or bypassed through retries or model switching. Acceptance checks JSON/SSE success envelopes,
explicit refusals or controlled safe answers, a unique terminal event, and settled budgets separately from
quality results such as unanswered expected facts. Missing citations alone do not prove an upstream safety block.
The two-node exercise explicitly simulates safety refusals across PLAIN/KNOWLEDGE and Native/OpenAI protocols;
this does not establish a real model's safety-policy behavior. Citation instructions never override safety rules.
An explicit upstream `refusal` field or `content_filter` finish reason is normalized into a visible safe answer
even with an empty body, with additive `metadata.modelSafetyRefusal=true`. QA metrics retain that signal and
the summary lists `MODEL_SAFETY_REFUSAL`, not an execution failure. Ordinary empty bodies or safety-related
words alone are not interpreted as structured refusals. The OpenAI-compatible optional `rag.modelSafetyRefusal`
projects the same signal; existing four-/five-argument Java constructors remain available.
The summary records this normal outcome in `refusalOutcomes` and per-case `refusalType`, not in
`failureCategories`. Unanswered required facts remain separate quality misses, not service exceptions.
This is not a standalone content moderator and cannot
retract PLAIN fragments already sent; KNOWLEDGE still passes through its existing evidence safety gate first.
KNOWLEDGE uses a controlled safety message with `reasonCode=provider_safety_refusal` for explicit structured
refusals rather than presenting safety policy as missing evidence. Normalization precedes ChatClient advisors,
stream aggregation and shared-memory commit, avoiding empty-body persistence errors or lost refusal signals.

## Complete Delivery

Follow the [delivery workflow](delivery-workflow.md): serialize Maven and SDK builds; run full/gated PostgreSQL,
repository, external Client/OpenAPI, frontend/real joint, dev.sh lifecycle and real-model checks before implementation3/3.
Backup/gate success never substitutes for API, security, original/vector or external-resource acceptance.
