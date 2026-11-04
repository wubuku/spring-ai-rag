# Next Batch Plan: Client SDK, Structured Evidence, and Recovery Readiness

> [English](2026-10-16_CLIENT_SDK_EVIDENCE_READINESS_PLAN.md) | [中文](2026-10-16_CLIENT_SDK_EVIDENCE_READINESS_PLAN-zh-CN.md)
>
> Status: accepted after implementation review 3/3; awaiting Git delivery
> Date: 2026-10-16

## 1. Goal And Conclusion

This batch targets external Client APIs rather than the WebUI. It delivers three
bounded capabilities: a minimal Python/Node/curl onboarding kit, an additive
structured-evidence projection for the controlled OpenAI-compatible response,
and a repeatable sanitized PostgreSQL/Flyway recovery-readiness gate. The last
gate is not a backup product or cloud disaster-recovery service.

The implementation preserves existing endpoints, methods, request fields,
response fields, Native SSE events, and error semantics. No database migration is
needed: evidence is projected from existing `ChatExecutionResult`/
`ChatResponse` data, and the recovery gate uses disposable databases.

## 2. Current Context

### 2.1 Existing Capabilities

- `/api/v1/rag/**` is the Native Client API and already exposes sources, evidence
  assessment, trace IDs, metadata, and Native SSE.
- `/v1/models` and `/v1/chat/completions` are a controlled, disabled-by-default
  OpenAI-compatible subset supporting text-only messages, `n=1`, JSON/SSE,
  header authentication, and keyed replay.
- `ChatExecutionResult`, `ChatResponse`, and `ChatEvent.SourcesAvailable` already
  carry the final sources, evidence assessment, and citation registry needed for
  an additive projection; the controller must not rerun retrieval.
- Official SDK HTTP acceptance, the Client Consumer Contract Kit, real-model
  smoke, and Starter-only consumer coverage already exist as adjacent gates.
- The source tree currently ends at Flyway V64; this batch does not change schema.

### 2.2 Key Constraints

- Preserve legacy endpoints, methods, request fields, response fields, SSE events,
  and error semantics.
- Add only: OpenAI gets an optional top-level `rag`; Native API fields are not
  removed or renamed, and old clients may ignore unknown fields.
- Evidence may contain only sources authorized for the request and finally exposed
  to the model/result. Do not leak intermediate candidates, cross-Collection data,
  credentials, prompts, or full exception bodies.
- JSON and snapshot-SSE idempotent replay must preserve answer, evidence, citation
  registry, and completion identity without another provider/retrieval call.
- Do not introduce explicit pessimistic locks, `SKIP LOCKED`, or advisory locks.
- External Client API tests are the acceptance authority; WebUI tests are secondary.

## 3. Target Protocol

### 3.1 Non-Streaming OpenAI Response

Add an optional top-level `rag` to the existing `chat.completion` response. It
contains `traceId`, `evidenceAssessment`, privacy-safe `sources`, and the
request-local `citationRegistry`. `sources` use `OpenAiRagSource`, omit
`chunkText` and arbitrary metadata, and contain only final authorized sources.
Existing OpenAI fields keep their types and meaning.

### 3.2 Streaming OpenAI Response

Keep the existing role/content/finish/`data: [DONE]` sequence. Put `rag` only on
the finish chunk, with no private SSE event. `SourcesAvailable -> Completed`
ordering drives the final evidence snapshot. If there is no safe evidence or the
execution fails, the extension remains absent or carries only safe final facts.
Keyed snapshot replay projects the same evidence and completion identity.

### 3.3 SDK Onboarding Kit

The guide and examples must document service-root URL versus `/v1`, model aliases,
complete messages, authentication, Collection scope, JSON/SSE, idempotency,
errors, and the Native API boundary. Python and Node examples print only safe
summaries and default to offline/syntax validation. Real HTTP is explicit through
`CLIENT_SDK_LIVE=1`; offline or Mock success is never real-model quality evidence.

## 4. Implementation Scope

### 4.1 API And Core

- Add OpenAI RAG extension DTOs while preserving the old record constructors.
- Project `rag` from existing execution/response objects in non-stream JSON,
  ordinary SSE, keyed JSON replay, and keyed snapshot SSE.
- Add focused assertions for standard-field compatibility, event order, replay,
  authorization, and absence of source text/arbitrary metadata.

### 4.2 SDK And Acceptance Scripts

- Add bilingual `docs/client-sdk-quickstart*`, pinned Python/Node examples, and
  `scripts/verify-client-sdk-quickstart.sh`.
- Add `scripts/verify-recovery-readiness.sh` using disposable PostgreSQL. Run all
  repository migrations, compare actual `flyway_schema_history` with the source
  inventory, run budget/governance PostgreSQL tests, and emit a redacted manifest.
- Add explicit `--with-client-sdk` and `--with-recovery-readiness` options to
  `scripts/verify-release.sh`; do not add real-model cost to the default release run.
- Never touch a caller-owned database, fabricate backup evidence, or record keys,
  prompts, answers, payloads, or full exceptions.

### 4.3 Evergreen Documentation

Keep the English/Chinese pairs synchronized across REST API, OpenAI readiness,
Client Contract Kit, developer reference, testing guide, release checklist, and
the documentation index. The docs must state that `rag` is additive, sources are
privacy-safe, full source text remains Native-only, and the two new gates are opt-in.

## 5. Non-Goals

- OAuth/OIDC, tenant schema, billing, provider billing, tools, structured output,
  sampling, multimodal content, and `EACH_COLLECTION`.
- Changes to Native request/response semantics, the existing citation protocol, or
  any already-executed Flyway migration.
- Presenting the recovery script as a complete backup or disaster-recovery system.

## 6. Test And Acceptance Matrix

### 6.1 Before Implementation

Complete at least three systematic plan reviews covering DTO/controller paths,
event order, keyed replay, existing Client Contract gates, migration facts,
release entry points, and bilingual documentation. Any substantive defect resets
the counter to zero.

### 6.2 After Implementation

Cover DTO serialization/legacy constructors/null omission; OpenAI plain, evidence,
no-evidence, safe-answer, and error cases; JSON/SSE/replay consistency; Native API
regression; official Python/Node SDK and Client Consumer Contract; disposable
PostgreSQL/Flyway; Maven, lock, docs, and whitespace gates; isolated `scripts/dev.sh`
startup/health/API/stop; and real-LLM JSON/SSE/evidence/idempotency only when usable
`.env` credentials exist. Missing credentials must be reported as not run.

### 6.3 Compatibility Hard Gates

- Existing OpenAI standard-field snapshots and legacy constructors remain intact;
  only optional `rag` is added.
- A client reading only `choices[0].message.content` gets the same answer.
- Standard SDK JSON/SSE parsing still succeeds and `[DONE]` remains unchanged.
- Native endpoints, methods, fields, SSE event names, and error codes remain compatible.
- No-evidence, unauthorized-source, provider-failure, and replay paths leak sensitive data.

## 7. Delivery Order

1. Finish and record plan review 3/3.
2. Add DTOs, projections, and focused tests.
3. Add SDK examples, guide, and offline/live gate.
4. Add recovery/migration acceptance entry point.
5. Update bilingual evergreen docs and release options.
6. Complete implementation review 3/3.
7. Run layered tests, real-service acceptance, and `scripts/dev.sh` verification.
8. Commit the feature branch, merge current `origin/main`, re-verify, merge/push
   `main`, and confirm a clean workspace.

## 8. Recovery Context

Continue by reading this plan and its `*_PROGRESS` ledger, then the REST API OpenAI
section, Client Consumer Contract Kit, and delivery workflow. Start code navigation
at `OpenAiCompatibilityController`, `OpenAiChatCompletionResponse`,
`OpenAiChatCompletionChunk`, and the existing OpenAI controller tests. WebUI success
must never replace external Client API evidence.
