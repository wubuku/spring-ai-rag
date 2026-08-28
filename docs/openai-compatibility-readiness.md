# OpenAI Compatibility Readiness and Codebase Context

> 📖 [English](openai-compatibility-readiness.md) · 📖 [中文](openai-compatibility-readiness-zh-CN.md)

> **Purpose**: Record the current server-side OpenAI Chat Completions
> implementation, controlled-preview boundary, and remaining public /
> multi-instance production security work.
> **Code baseline**: `main@f1cdcba1`, including Chat-turn reliability, V48
> managed-principal hardening, and V55 bounded staged credential rotation.
> **Last verified**: 2026-08-28
> **Status**: `/v1/models` and `/v1/chat/completions` are implemented but
> disabled by default. This document does not claim public production readiness.

Documentation hub: [index.md](index.md). The callable contract and current
configuration live in the [REST API reference](rest-api.md) and
[configuration reference](configuration.md). Consult the
[plan archive](drafts/archive/README.md) only when auditing historical target
designs.

---

## 1. Conclusion

Exposing this project through an OpenAI Chat Completions interface has clear value:

> Present a complete RAG deployment, including retrieval policy, knowledge scope,
> domain prompts, and model routing, as a standard `model` that OpenAI SDKs, agent
> frameworks, IDEs, and gateways can consume.

Protocol compatibility and public production readiness are different concerns.
The project has implemented the standalone-service API-key MVP, V48 stable
managed principals, and a disabled-by-default `/v1` adapter. Stable ownership,
versioned credentials, bounded staged rotation, immediate cross-instance
revocation, PostgreSQL shared request quotas, and fail-closed quota-store
behavior are now present. The adapter remains a controlled preview rather than
a blanket public-production claim because legacy compatibility, identity
federation, operator recovery, deployment controls, and token/cost governance
remain separate concerns.

The accurate current position is: **the Core standalone-service topology provides a
Chat Completions compatibility subset suitable for basic trusted-network integration,
but it is neither a complete OpenAI API replacement nor a public-production claim.**
The adapter is not an agent/subagent orchestrator. It provides a stable
"RAG-as-a-model" boundary; orchestration belongs to the caller or a later independent
module.

---

## 2. Relevant Module Boundaries

| Module | Current responsibility | Constraint for compatibility work |
|--------|------------------------|-----------------------------------|
| `spring-ai-rag-api` | DTOs and SPIs | OpenAI DTOs live in an isolated `openai` package and do not change the existing `ChatRequest` contract |
| `spring-ai-rag-core` | RAG implementation and runnable app | Owns the shared execution layer, compatibility controller, model-alias registry, and error mapping |
| `spring-ai-rag-starter` | Auto-configuration | Explicitly imports the shared authentication, rate-limiting, and observability configuration for `/v1`; the compatibility controller and execution beans still require host scanning of `com.springairag` or equivalent explicit imports |
| `spring-ai-rag-documents` | Document processing | Must remain independent of the OpenAI protocol |
| `spring-ai-rag-webui` | React admin UI | Root unlock manages one row per stable principal, including policy CAS, quota, staged/immediate credential rotation, revocation, and shown-once secrets |

There are two runtime topologies:

1. Run the application from `spring-ai-rag-core`.
2. Add `spring-ai-rag-starter` to another Spring Boot application.

The Core standalone service scans `com.springairag` through
`SpringAiRagApplication` and therefore registers the full compatibility entry point.
The Starter's `GeneralRagAutoConfiguration` currently imports only the shared Web
security configuration; it does not auto-import `OpenAiCompatibilityController`, the
mapper, the alias registry, or their complete dependency graph. The demos compensate
with `scanBasePackages = "com.springairag"`. Therefore, "adding only the Starter
dependency provides `/v1`" is not an accepted fact. Shared Filter assembly is tested,
but there is no Starter-consumer `/v1` HTTP integration test that works without the
extra component scan.

---

## 3. Current RAG Chat Execution Facts

- The native API remains under `/api/v1/rag/**`; `/v1/**` is registered only
  when the compatibility feature flag is enabled.
- OpenAI text-only `messages[]` map into the transport-neutral `ChatCommand`;
  the native single-message `ChatRequest` contract remains unchanged.
- `model` must be a public alias. An alias binds Chat mode, memory, and an
  internal model candidate chain, never a fixed Collection.
- Collection scope comes from body `rag.scope` or repeated
  `X-RAG-Collection-Key`, then delegates to the shared resolver and API-key ACL.
- Non-streaming returns `chat.completion`; streaming returns complete
  `chat.completion.chunk` records and exact `data: [DONE]`. Authentication,
  rate-limit, and controller errors use an OpenAI error envelope.
- `/v1` exposes no client-controlled stable session ID. Distinct requests receive
  distinct internal sessions by default; multi-turn clients should resend the complete
  `messages` list as they do with standard Chat Completions. The alias `SERVER` memory
  setting must not be described as an externally stable cross-request session contract.
- Optional `Idempotency-Key` reuses the durable Chat turn. Exact replay preserves the
  completion ID and result. With this header, `stream=true` completes and persists the
  turn before emitting snapshot SSE, so it does not provide token-by-token first-byte
  latency.
- Native Chat SSE keeps RAG events such as `tool_start`, `tool_result`,
  `sources`, and `done`; it is a separate contract from the `/v1` stream.

See [architecture.md](architecture.md), [rest-api.md](rest-api.md), and
[SSE-PROTOCOL.md](SSE-PROTOCOL.md) for the current architecture and HTTP contracts.

---

## 4. Implementation Status Assessment, 2026-08-28

### 4.1 Maturity Summary

| Area | Current state | Conclusion |
|------|---------------|------------|
| Core standalone basic integration | Implemented with focused tests | Usable for basic OpenAI Chat Completions client integration on a trusted network |
| Basic SDK wire shape | Mostly compatible | Standard JSON fields parse in the OpenAI Python SDK; the repository has no committed SDK-over-HTTP regression gate |
| RAG semantics | Implemented | Model aliases, request-scoped Collection/document selection, JSONB filters, and API-key ACLs reuse the shared execution path |
| Security data plane | Baseline hardening implemented | Bearer/header API keys, capabilities, ACLs, shared quota, revocation, and rotation are in the `/v1` Filter chain |
| Starter consumer | Partially integrated | Security config is auto-imported; the complete `/v1` entry point still depends on extra host scanning and lacks a real consumer HTTP acceptance test |
| Complete Chat Completions protocol | Not implemented | Text-only and `n=1`; sampling, tools, structured output, and multimodal inputs are unsupported |
| Public production service | Not claimed ready | Legacy boundaries, identity, budget, TLS, networking, recovery, and operational controls remain |

Accordingly, "supports OpenAI Chat Completions" means a **controlled compatibility
subset**. Public descriptions should say "OpenAI Chat Completions-compatible preview",
not "fully OpenAI API compatible."

### 4.2 Current Protocol Matrix

| Category | Supported | Explicitly unsupported or constrained |
|----------|-----------|---------------------------------------|
| Endpoints | `GET /v1/models`, `GET /v1/models/{id}`, `POST /v1/chat/completions` | Responses, Embeddings, Files, Batches, legacy Completions, and every other OpenAI API |
| Messages | `system`, `developer`, `user`, `assistant`; strings or text-only parts; at most 100 messages and 1,000,000 total characters | Image/audio/file parts, tool/function messages, `name`, blank content; at least one user message is required |
| Generation parameters | `model`, `stream`, `n=1` | `temperature`, `top_p`, token limits, `n>1`, logprobs, tools/functions, `response_format`, `stream_options`; unknown fields also fail closed |
| RAG extensions | `rag.scope`, `rag.document_ids`, `rag.filters`, alias-policy-controlled `rag.mode` / `rag.memory`, and repeated `X-RAG-Collection-Key` | `PLAIN` rejects retrieval scope/filter; request overrides are disabled by default; structured sources are not placed in the OpenAI response |
| Non-streaming response | Standard `chat.completion`, one choice, assistant text, `finish_reason`, and optional usage | No structured citation/source/tool-call objects |
| Streaming response | Role chunk, content chunks, finish chunk, and `data: [DONE]` | No `stream_options.include_usage`; failures after stream start can only be sent inside the established SSE response |
| Idempotency | Optional `Idempotency-Key`, stable replay, `X-RAG-Turn-Id`, and replay header | Keyed streaming is completion-then-snapshot SSE, not a live token stream |
| Conversation | Complete messages can be sent on every request | No client-controlled session ID and no stable server-conversation contract across distinct requests |

Basic OpenAI SDK calls generally fit this subset: set the SDK `base_url` to the
service's `/v1`, use a RAG credential as the API key, use an alias returned by
`/v1/models`, and send only the fields above. Agents and IDEs that automatically send
tools, JSON schemas, sampling parameters, or multimodal parts must disable those
features first; otherwise they receive an explicit `unsupported_parameter` rather than
a silent downgrade.

### 4.3 Code Navigation

| Concern | Code entry point |
|---------|------------------|
| OpenAI DTOs | `spring-ai-rag-api/.../api/openai/` |
| `/v1` controller and JSON/SSE mapping | `spring-ai-rag-core/.../controller/OpenAiCompatibilityController.java` |
| Request validation, messages, and `ChatCommand` mapping | `spring-ai-rag-core/.../openai/OpenAiChatRequestMapper.java` |
| Model alias and mode/memory/candidate policy | `spring-ai-rag-core/.../openai/OpenAiModelAliasRegistry.java` |
| Collection header/body merge and ACL | `spring-ai-rag-core/.../openai/OpenAiRequestRetrievalScopeAdapter.java` |
| OpenAI error envelope | `OpenAiCompatibilityExceptionHandler.java`, `OpenAiProtocolException.java` |
| Authentication, capability, and quota | `ApiKeyAuthFilter.java`, `ApiCapabilityFilter.java`, `RateLimitFilter.java` |
| Shared Chat execution | `ChatExecutionService.java`, `ChatTurnOperationService.java` |
| Focused tests and gate | `OpenAi*Test.java`, `OpenAiCompatibilityControllerWebTest.java`, `scripts/verify-openai-compatibility.sh` |

Each `...` above is under the module's
`src/main/java/com/springairag/` or `src/test/java/com/springairag/` tree. The table is a
fast navigation aid, not a substitute for source search.

### 4.4 Current Evidence and Evidence Gaps

On 2026-08-28 at `main@f1cdcba1`, the following command passed:

```bash
./scripts/verify-openai-compatibility.sh
```

All four steps passed. The focused Maven set ran 55 tests across aliases, scope
mapping, complete text-only messages, non-streaming envelopes, protocol errors, SSE
ordering, `[DONE]`, shared Chat fallback, and Web-security assembly. `test-compile`,
shell syntax, and `git diff --check` then passed. A one-off wire-shape probe also
confirmed that the standard non-streaming response parses in the locally installed
OpenAI Python SDK.

This evidence does **not** yet prove:

1. Committed end-to-end JSON and SSE tests from an isolated Spring Boot service through
   the official Python and JavaScript SDKs; the focused gate is mainly MockMvc and unit
   contract coverage.
2. Automatic `/v1` availability in a normal Starter-only consumer that does not scan
   `com.springairag`.
3. The complete feature-flag, Bearer authentication, read-only capability, Collection
   ACL, shared quota, and real HTTP-stream matrix in one dedicated gate.
4. That common third-party Agents, IDEs, and gateways will not automatically send an
   unsupported OpenAI parameter.

### 4.5 Improvement Priorities

1. **P0: Add an SDK-level dedicated E2E gate.** Start isolated PostgreSQL, a mock
   upstream model, and a real Spring Boot service. Use official Python and JavaScript
   SDKs to verify models, JSON, SSE, errors, Bearer, a read-only principal, Collection
   ACL, the feature flag, and `[DONE]`. Use HTTP/JSON/database read-only assertions,
   never screenshots.
2. **P0: Make the Starter product boundary explicit.** Either auto-configure the full
   `/v1` bean graph or state that compatibility service endpoints are Core-standalone
   only. Lock the decision with a consumer HTTP test; do not let broad demo scanning
   hide the boundary.
3. **P1: Add a client integration guide.** Include minimal official-SDK text-only
   examples, `base_url=/v1`, model aliases, complete messages, Collection header/body,
   error handling, and keyed-stream behavior.
4. **P1: Decide the source-return contract.** OpenAI responses currently omit structured
   RAG sources. Clients that require traceable sources should keep using the native API
   until a cross-SDK extension-field policy is defined.
5. **P2: Expand only from real client demand.** Prioritize sampling, structured output,
   tools, or multimodal support based on a client matrix, without weakening alias policy
   or server-owned tool authorization.
6. **Separate production-readiness track:** legacy shutdown, OAuth/OIDC, token/cost hard
   limits, TLS, network isolation, Secret management, backup/recovery, and alerting.

---

## 5. Do Not Confuse the Two OpenAI-Compatible Directions

| Direction | Status | Meaning |
|-----------|--------|---------|
| `spring-ai-rag -> OpenAI-compatible provider` | Implemented | This project calls OpenAI, DeepSeek, SiliconFlow, and similar upstream APIs |
| `OpenAI client / Agent -> spring-ai-rag` | Controlled preview, disabled by default | This project serves `/v1/chat/completions` and Models APIs |

The existing `OpenAiCompatibleAdapter` under `adapter/` handles **upstream model message
capability differences**. It is not a server-side Chat Completions protocol adapter, and
new code must not assume otherwise.

---

## 6. Current API-Key Capabilities

The current implementation provides an accepted standalone-service MVP:

- Raw secrets use a `rag_sk_` prefix; public identifiers use `rag_k_...`.
- Authentication looks up a SHA-256 hash.
- Create, list, revoke, immediate rotate, bounded staged rotate, expiration, and
  `last_used_at` are supported.
- Roles are `ADMIN` and `NORMAL`.
- V24 adds `allowed_collection_ids`, allowing data paths to enforce collection ACLs.
- `RAG_ROOT_API_KEY` provides an environment-root principal and automatically protects
  `/api/**` and `/v1/**` in root mode.
- The root unlocks `/webui/unlock` and can create, list, rotate, and revoke business keys.
- Root-created keys may be read-only `RAG_READ` or full
  `RAG_READ + RAG_WRITE`; omission preserves full read/write compatibility. They
  require a future expiry without a fixed maximum lifetime and are usable by external
  callers without the WebUI.
- Root mode accepts `Authorization: Bearer` and `X-API-Key` headers and rejects query
  credentials; legacy static/query compatibility remains when root mode is disabled.
- The WebUI keeps credentials in page memory and clears legacy localStorage credentials
  during upgrade.
- V48 separates stable principal policy from versioned credential hashes;
  rotation preserves `db:{principalId}` ownership and policy.
- V55 permits one current and at most one deadline-bounded retiring credential
  per stable principal. Prepare is idempotent without replaying the shown-once
  replacement secret; complete, cancel, deadline expiry, policy-expiry
  clamping, and family revocation converge without widening ACL or quota.
- Authentication performs an authoritative credential/principal join on every
  request; revocation and retiring-credential deadline expiry are visible to
  every instance on its next authentication.
- A PostgreSQL fixed UTC-minute backend shares request quota by stable principal
  and fails closed without raw-key or IP fallback.
- The legacy plaintext column is constrained to `NULL`, and legacy ADMIN
  revocation has a transactional last-ADMIN guard.

Relevant code:

- [RagApiKey](../spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagApiKey.java)
- [RagApiPrincipal](../spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagApiPrincipal.java)
- [ApiKeyManagementService](../spring-ai-rag-core/src/main/java/com/springairag/core/service/ApiKeyManagementService.java)
- [ApiKeyController](../spring-ai-rag-core/src/main/java/com/springairag/core/controller/ApiKeyController.java)
- [ApiKeyAuthFilter](../spring-ai-rag-core/src/main/java/com/springairag/core/filter/ApiKeyAuthFilter.java)
- [ApiKeyCollectionAccess](../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java)

---

## 7. Remaining Boundaries for External Production Callers

| Gap | Current code fact | Direct impact |
|-----|-------------------|---------------|
| Creation and delegation | Root MVP disables NORMAL self-service management; legacy mode retains historical creation/delegation semantics | Full hardening must close the legacy compatibility and policy-delegation gaps |
| Bootstrap and recovery | Root mode disables empty-table ADMIN/raw-secret bootstrap; legacy mode records a low-cardinality error when no usable ADMIN exists | Operators still need an explicit environment-root provisioning and recovery procedure |
| URL and credential format | `/v1/*` uses Bearer/header authentication; root mode rejects query credentials and emits OpenAI error envelopes | Public enablement should remove legacy query/static compatibility and require managed principals |
| Identity federation | Managed principals are service-issued secrets, not OAuth/OIDC identities | Public multi-tenant deployments may need issuer, audience, tenant, and revocation contracts outside this credential family |
| Cost governance | Shared quotas count requests in fixed UTC-minute windows | Token, provider-cost, daily budget, and billing-ledger controls remain independent capabilities |
| Operations | Code-level shared quotas, staged rotation, and revocation are implemented | TLS, network isolation, database capacity, alerting, backup/restore, Secret-store integration, and rotation runbooks remain deployment responsibilities |

These are service-readiness requirements, not protocol details. Historical V48
design rationale remains in the active delivery plan while the batch is being
completed; live API and configuration facts are maintained in the references.

---

## 8. Current Implementation And Public-Enablement Boundaries

1. Compatibility is disabled by default and enabled through an explicit feature flag.
2. `model` identifies a RAG alias; arbitrary backend model names cannot bypass retrieval or
   authorization policy.
3. Requests are stateless by default. `/v1` exposes no client-controlled session ID, so
   multi-turn callers must resend complete messages across requests.
4. Legacy and OpenAI HTTP/SSE mappers consume a shared structured internal result; one
   controller must not call another controller.
5. A model alias contains no fixed Collection. Effective scope comes from the
   request and API-key ACL and rejects unauthorized expansion.
6. In root mode, `/v1` accepts Bearer / `X-API-Key` and rejects query-string secrets.
7. PostgreSQL limiting uses the authenticated stable principal and is shared
   across instances; immediate or staged rotation does not reset it. The local
   backend remains an explicit single-instance compatibility option.
8. Credential and PostgreSQL quota-store failures fail closed. Public
   enablement should still remove legacy static/query compatibility.
9. Existing `/api/v1/rag/**` contracts remain independent and continue to work when
   compatibility is disabled.
10. Core standalone has focused contract tests. The Starter currently locks only shared
    security-config assembly; the complete consumer `/v1` HTTP bean graph remains a P0
    verification gap.
11. A mixed V54/V55 fleet must freeze API-key management writes and staged
    prepare. Staged rotation is enabled only after all instances run V55; before
    application rollback to V54, operators must clear enabled retiring
    credentials and `PENDING` rotation operations while retaining the V55
    schema.

---

## 9. Maintenance And Hardening Reading Order

1. This document: current controlled-preview facts and security boundaries.
2. [REST API](rest-api.md) and [configuration](configuration.md): callable contract.
3. [Project context](project-context.md) and [testing guide](testing-guide.md):
   stable capability boundaries and current verification entry points.
4. [Historical plan archive](drafts/archive/README.md): read only for design
   provenance and old verification evidence; current code and live references
   win when they differ.

---

## 10. Maintenance Rules

- Update this document when current implementation facts change; update the plan when the
  target design changes. Do not mix current state with planned state.
- After APIs or configuration ship, update `rest-api*`, `configuration*`, and testing
  documentation together.
- Never record real API keys, tokens, database passwords, or other secrets here.
- Keep the English and Chinese versions synchronized.
