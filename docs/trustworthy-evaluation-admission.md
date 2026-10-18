# Trustworthy Assertions, Active Diagnostics And Quality Admission

> [English](trustworthy-evaluation-admission.md) | [中文](trustworthy-evaluation-admission-zh-CN.md)

Available by default without changing existing Chat/Search/Evaluation URLs, success envelopes or required parameters.
Ordinary Chat incurs no evaluation calls. No new persistent table or schema migration is needed.
See [quality/recovery acceptance](quality-production-acceptance.md) for the broader delivery baseline.

## Optional Deterministic Assertions

KNOWLEDGE_QA cases may add `assertions` and `expectedOutcome`. Existing definitions and Java constructor signatures remain valid.
Legacy requiredFacts/forbiddenFacts retain NFKC/lowercase contains semantics and are ANDed with new assertions; history is not rewritten.
`testdata/knowledge-qa-quality/v1/` is unchanged. `v2/definition.json` is independently versioned; v2 success never replaces v1 failure.

```json
{
  "assertions": [
    {"id":"count","type":"QUANTITY","value":"0","unit":"条"},
    {"id":"code","type":"TOKEN","value":"SAFE-42"},
    {"id":"phrase","type":"TEXT_ANY","alternatives":["不支持离线"]},
    {"id":"range","type":"NUMBER_RANGE","min":"10","max":"20","unit":"ms"},
    {"id":"forbidden-code","type":"TOKEN","value":"UNSAFE","forbidden":true}
  ],
  "expectedOutcome":"ANSWER"
}
```

- At most32 rules, unique id1–64 characters; unknown fields/types, nonboolean forbidden, regex and mixed-type fields are rejected.
- TOKEN uses ASCII identifier boundaries: 0 does not match10 and SAFE-42 does not match SAFE-420. It is not natural-language tokenization.
- TEXT_ANY selects from1–8 literal phrases; it does not infer synonyms, negation scope, intent or whether an injection was executed.
- QUANTITY supports finite decimals, well-grouped thousands and common Chinese integers adjacent to a literal unit, with optional whitespace.
  No cross-sentence joining or implicit unit conversion. NUMBER_RANGE is an inclusive same-unit interval.
- Decimal values/min/max are strings, limited to18 integer/9 fractional digits; units have at most32 characters.
- Persistent metrics contain matched/passed and normalized Unicode code-point offsets, not answers or matchedText.
  Local replay additionally returns at most128 code-points of matching text. Shared Java/Python fixtures: `testdata/answer-assertions/v1/`.

expectedOutcome accepts ANSWER, INSUFFICIENT_EVIDENCE or MODEL_SAFETY_REFUSAL; omitted fields retain existing behavior.
Only explicit provider refusal/content_filter signals establish model safety refusal, never a text guess.
Safety refusal is a normal business result, not an error to retry or evade via another model. Explicitly expected refusal may lack citations,
but authorization and required/forbidden facts still apply. Missing required facts may fail quality without being a service incident.
Additive metrics are assertions/observedOutcome; reasons are STRUCTURED_ASSERTION_FAILED/OUTCOME_MISMATCH.
Judge remains auxiliary and cannot override deterministic failure.

Align assertions with the question's output requirements: QUANTITY deliberately does not treat a bare record count of0 as the literal quantity0条.
Explicitly request a value plus unit when units matter. For bare values, define the output format before choosing rules instead of imposing an unstated unit.
TOKEN only provides ASCII identifier boundaries; it is not a general bare-number or semantic parser.
A lexical/format failure does not establish a factual error. v2 is a strict literal example, not a semantic gold standard covering every valid expression.
Calibration requires a new immutable definition and fresh execution, never replacing failed artifacts or presenting a new diagnostic answer as historical output.

## Explicit Active Diagnostic API

`POST /api/v1/rag/evaluation/suites/{suiteKey}/versions/{version}/diagnose`

```json
{"caseId":"numeric-limit","variantKey":"default","ackPaidModels":true,"ackSensitiveContent":true}
```

This is a **new paid synchronous execution**, not historical model replay: executionKind=NEW_PAID_EXECUTION_NOT_HISTORICAL_REPLAY.
The case/variant must belong to the immutable version, with KNOWLEDGE_QA and judge disabled.
Both acknowledgements are required; otherwise400 with no model call. Existing WRITE capability, suite owner and current Collection ACL apply.
Another owner receives404; ACL is checked again after execution so revoked access cannot receive captured bodies.
This is not an administrative bypass for inspecting other owners' diagnostics.

The native object response has Cache-Control:no-store, version checksum, case/variant/status/metrics/traceId/latencyMs/capture.
HTTP200 with status=FAILED is a failed diagnostic result, not successful model execution; validation/permission errors retain existing error envelopes.
Capture includes lastInvocationMessages from the actual last model call, final answer, evidence from advisor DOCUMENT_CONTEXT and
client sourceProjections. Projections are not full evidence. No actual prompt means capture cannot be complete.
Same-call pre-safety modelAnswer, answerOutcome and citationValidation are additive; answer remains the delivered text.
See [upstream/client recovery](upstream-client-recovery.md). Prompt/modelAnswer/answer are each32KiB UTF-8;
evidence and source projections each allow16 items of4KiB. Text totals at most224KiB;
JSON escaping may expand it. Redaction/truncation is explicit; complete requires prompt, modelAnswer, answer and evidence without either condition.
No body is written to Evaluation case metrics or a new historical store. Existing usage/trace retention and erasure still apply.

## Private Recording And Offline Replay

Inject service root/API key through KNOWLEDGE_QA_BASE_URL/KNOWLEDGE_QA_API_KEY, never command literals or tracked documents.

```bash
python3 scripts/knowledge-qa-diagnostics.py record \
  --suite-key my-quality-suite --version 1 --case-id numeric-limit \
  --definition testdata/knowledge-qa-quality/v2/definition.json \
  --ack-paid-models --ack-sensitive-content
python3 scripts/knowledge-qa-diagnostics.py replay --directory /private/diagnostic-directory
python3 scripts/knowledge-qa-diagnostics.py purge --root /private/diagnostics-root
```

Default root: .verification/knowledge-diagnostics. Directories0700/files0600; three JSON files plus SHA256 manifest, TTL24h.
Symlink paths, oversized files, duplicate JSON keys, nonfinite numbers, tampering and mismatched definitions are rejected; response limit1MiB.
record performs bounded cleanup of expired owned directories; explicitly run purge offline if necessary, scanning at most1000 entries per call.
It is not a background scheduler. Purge only deletes expired owned directories with the exact file inventory and matching checksums;
tampered directories are not presumed safe to delete.
Replay makes no network/model call. It rechecks lexical assertions and preserves the original hard gate; original FAILED cannot become PASSED.
Incomplete/expired/redacted artifacts cannot obtain full replay success. Checksums establish internal integrity, not provider authenticity/signatures.
Downloaded artifacts are the recipient's responsibility; server erasure cannot retract them. Never commit or publish them in shared logs.

## Change And Release Admission

Quality runner outputs now include definition.json and a SHA manifest. New run configurationSnapshot adds collectionContentSha256.
Each Collection hash uses stable external identity, original content, metadata, enabled/deleted state, not database IDs/updated_at.
Hash collection reads at most100001 rows; beyond100000 total documents, affected hashes are null and quality admission fails closed,
without blocking the existing Run API or imposing a new prerequisite on old clients. Historical runs are neither backfilled nor automatically approved.
Inject KNOWLEDGE_QA_BASE_URL/API_KEY first. The standalone runner never imports or cleans documents automatically:
v2 shares the v1 synthetic corpus, installed in isolation by the controlled/real acceptance scripts.
Production evaluation should use a custom versioned definition referencing existing Collections.

```bash
python3 scripts/run-knowledge-qa-evaluation.py --profile quality --repeats 3 \
  --definition testdata/knowledge-qa-quality/v2/definition.json --suite-key candidate-quality \
  --output-directory .verification/candidate-quality
python3 scripts/verify-knowledge-quality-admission.py \
  --candidate-directory /private/candidate --baseline-directory /private/approved-baseline \
  --baseline-sha256 "$APPROVED_BASELINE_MANIFEST_SHA256"
```

The baseline requires independent/manual review and its manifest SHA from trusted release configuration.
The tool never approves the latest result, accepts a baseline automatically or switches a model.
Require2–10 complete rounds, exact case/variant inventory, deterministic results, environment fingerprints and corpus/definition identity;
missing/incomplete/tampered/drifting inputs block admission. Candidate/baseline must share definition, corpus and variant.
Model/configuration/code/Embedding Profile changes are allowed and reported explicitly.
Per-case output shows regressions, latency/token ratios and failure reasons. Cost/latency are report-only by default;
explicit --max-latency-ratio/--max-token-ratio impose hard limits, including missing metrics.
Quality failures are not service error rates; normal safety refusals are reported separately.

`scripts/verify-release.sh --with-knowledge-quality` consumes KNOWLEDGE_QUALITY_CANDIDATE, KNOWLEDGE_QUALITY_BASELINE
and KNOWLEDGE_QUALITY_BASELINE_SHA256. This gate is fully offline, fails without valid artifacts and never silently pays for new runs.
Without the option, existing release behavior is unchanged.

## Verification

```bash
python3 scripts/test-support/trustworthy-evaluation-self-test.py
python3 scripts/verify-trustworthy-evaluation-e2e.py
# Inject authorized real LLM/Embedding credentials first; explicitly add two paid diagnostics:
python3 scripts/verify-quality-real-e2e.py --ack-paid-models \
  --definition testdata/knowledge-qa-quality/v2/definition.json \
  --with-diagnostics --ack-sensitive-content
```

Controlled acceptance covers repeated24-case runs, approved baselines/corpus-drift negatives, real PostgreSQL hashes, both acknowledgements,
ACL revocation, no stored diagnostic body, provider failure and offline replay. Real acceptance defaults to two providers ×24cases ×3rounds,
one new diagnostic per provider, Client JSON/SSE/official SDK and real Embedding/rerank calls.
It does not promise100% factual correctness for arbitrary models/corpora. Preserve failures; controlled success is not real-model quality evidence.
