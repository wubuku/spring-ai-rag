# OpenAI Compatibility Readiness and Codebase Context

> 📖 [English](openai-compatibility-readiness.md) · 📖 [中文](openai-compatibility-readiness-zh-CN.md)

> **Purpose**: Record the current code facts that must be understood before implementing
> a server-side OpenAI Chat Completions adapter and external-caller API keys.
> **Code baseline**: `main` / commit `3b61b26`; API-key MVP implementation commit: `ccc0e42`
> **Last verified**: 2026-08-15
> **Status**: The project does not currently expose `/v1/chat/completions`. This is a
> current-state reference, not a claim of implemented compatibility.

Documentation hub: [index.md](index.md). For the target architecture, migration, tests,
and rollback, see the
[OpenAI Chat Completions compatibility plan](drafts/2026-07-21_OPENAI_CHAT_COMPLETIONS_COMPATIBILITY_PLAN.md).
The independent credential, authorization, rotation, revocation, and multi-instance quota
work is specified in the
[API-key hardening implementation plan](drafts/2026-08-14_API_KEY_HARDENING_IMPLEMENTATION_PLAN.md).

---

## 1. Conclusion

Exposing this project through an OpenAI Chat Completions interface has clear value:

> Present a complete RAG deployment, including retrieval policy, knowledge scope,
> domain prompts, and model routing, as a standard `model` that OpenAI SDKs, agent
> frameworks, IDEs, and gateways can consume.

Protocol compatibility and service readiness are different concerns. The project has now
implemented and verified the standalone-service MVP of a root API key unlocking the WebUI
and issuing business API keys. That MVP is still insufficient for public, multi-instance,
production-ready external callers. **Full API-key lifecycle, authorization, and
multi-instance quota hardening remain prerequisites for enabling `/v1`, not follow-up work.**

The adapter is not an agent/subagent orchestrator. It provides a stable
"RAG-as-a-model" boundary; orchestration belongs to the caller or a later independent
module.

---

## 2. Relevant Module Boundaries

| Module | Current responsibility | Constraint for compatibility work |
|--------|------------------------|-----------------------------------|
| `spring-ai-rag-api` | DTOs and SPIs | Protocol DTOs need an isolated package and must not change the existing `ChatRequest` contract |
| `spring-ai-rag-core` | RAG implementation and runnable app | Owns the internal execution layer, compatibility controller, deployment registry, and error mapping |
| `spring-ai-rag-starter` | Auto-configuration | Must register authentication, rate limiting, and observability for `/v1` |
| `spring-ai-rag-documents` | Document processing | Must remain independent of the OpenAI protocol |
| `spring-ai-rag-webui` | React admin UI | The MVP now provides root-key unlock; it need not switch APIs, while public management still requires hardening |

There are two runtime topologies:

1. Run the application from `spring-ai-rag-core`.
2. Add `spring-ai-rag-starter` to another Spring Boot application.

Security cannot work in only one topology. Authentication and rate-limit
`FilterRegistrationBean` definitions currently live in the starter, so characterization
tests must lock down both topologies before implementation.

---

## 3. Current RAG Chat Execution Facts

- The public API is under `/api/v1/rag/**`; chat endpoints are `/chat`, `/ask`, and
  `/chat/stream`.
- `ChatRequest` centers on one `message`; it is not equivalent to a complete Chat
  Completions `messages[]` request.
- `RagChatService` currently builds requests with `.system(...)` and `.user(...)`.
- The default Advisor chain includes query rewrite, hybrid search, reranking, and
  `MessageChatMemoryAdvisor`.
- `AdvisorUtils` can extract the last non-empty user message from a message list, which is
  reusable for a future full-message mapping.
- The non-streaming path has candidate-model fallback; streaming fallback and metadata
  semantics are not symmetric.
- Non-streaming calls write `rag_chat_history`; business-audit behavior differs for
  streaming calls.
- Current SSE emits only part of an OpenAI-like `choices[].delta.content` shape and uses a
  custom completion event. It lacks complete standard chunk fields, an OpenAI error
  envelope, and exact `data: [DONE]`, so it is not Chat Completions compatible.

See [architecture.md](architecture.md), [rest-api.md](rest-api.md), and
[SSE-PROTOCOL.md](SSE-PROTOCOL.md) for the current architecture and HTTP contracts.

---

## 4. Do Not Confuse the Two OpenAI-Compatible Directions

| Direction | Status | Meaning |
|-----------|--------|---------|
| `spring-ai-rag -> OpenAI-compatible provider` | Implemented | This project calls OpenAI, DeepSeek, SiliconFlow, and similar upstream APIs |
| `OpenAI client / Agent -> spring-ai-rag` | Not implemented | This project serves `/v1/chat/completions` and Models APIs |

The existing `OpenAiCompatibleAdapter` under `adapter/` handles **upstream model message
capability differences**. It is not a server-side Chat Completions protocol adapter, and
new code must not assume otherwise.

---

## 5. Current API-Key Capabilities

The current implementation provides an accepted standalone-service MVP:

- Raw secrets use a `rag_sk_` prefix; public identifiers use `rag_k_...`.
- Authentication looks up a SHA-256 hash.
- Create, list, revoke, rotate, expiration, and `last_used_at` are supported.
- Roles are `ADMIN` and `NORMAL`.
- V24 adds `allowed_collection_ids`, allowing data paths to enforce collection ACLs.
- `RAG_ROOT_API_KEY` provides an environment-root principal and automatically protects
  `/api/**` in root mode.
- The root unlocks `/webui/unlock` and can create, list, rotate, and revoke business keys.
- Root-created keys have a fixed `FULL_RAG` profile, require expiry no more than 90 days,
  and are usable by external callers without the WebUI.
- Root mode accepts `Authorization: Bearer` and `X-API-Key` headers and rejects query
  credentials; legacy static/query compatibility remains when root mode is disabled.
- The WebUI keeps credentials in page memory and clears legacy localStorage credentials
  during upgrade.

Relevant code:

- [RagApiKey](../spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagApiKey.java)
- [ApiKeyManagementService](../spring-ai-rag-core/src/main/java/com/springairag/core/service/ApiKeyManagementService.java)
- [ApiKeyController](../spring-ai-rag-core/src/main/java/com/springairag/core/controller/ApiKeyController.java)
- [ApiKeyAuthFilter](../spring-ai-rag-core/src/main/java/com/springairag/core/filter/ApiKeyAuthFilter.java)
- [ApiKeyCollectionAccess](../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java)

---

## 6. Critical Gaps for External Production Callers

| Gap | Current code fact | Direct impact |
|-----|-------------------|---------------|
| Plaintext secret schema | V23 and `RagApiKey` retain an `api_key` column and index; the current service does not write it, but the schema permits storage | The system cannot prove that a secret is returned once and never persisted |
| Creation and delegation | Root MVP disables NORMAL self-service management; legacy mode retains historical creation/delegation semantics | Full hardening must close the legacy compatibility and policy-delegation gaps |
| Rotation identity | Rotation disables one key and creates an independent key | No stable object carries role, owner, policy, or quota |
| ADMIN protection | There is no transactional last-ADMIN guard | Concurrent operations can remove the final management credential |
| Bootstrap | Root MVP disables empty-table ADMIN/raw-secret bootstrap; legacy mode without root retains historical behavior | Full hardening still needs one unified bootstrap/recovery contract |
| Revocation consistency | Authentication has a 30-second in-process positive cache | Revocation is not immediately consistent across instances |
| Last-used writes | Every authentication synchronously updates `last_used_at` | High request rates create database write amplification |
| Rate limiting | The MVP prefers a stable key ID after authentication, but counters remain in-process and shared quotas are absent | Replicas multiply quotas, so global quota semantics are not available |
| Raw key as limiter ID | Root/authenticated paths use a stable principal ID; legacy or unauthenticated fallback can still use the raw header | Full hardening must remove raw secrets as limiter identifiers entirely |
| URL and credential format | Root MVP provides Bearer/header auth and rejects query credentials for `/api/**`; `/v1/*` is still absent | New `/v1/*` routes still need explicit compatibility authentication and Bearer wiring |
| Failure semantics | Database validation and static fallback coexist | Credential-store failures must not downgrade into an authorization bypass |

These are service-readiness requirements, not protocol details. Stable families and
principals, rotatable versions, explicit policy, shared quotas, migration, and fail-closed
failure semantics are fully specified by the
[API-key hardening implementation plan](drafts/2026-08-14_API_KEY_HARDENING_IMPLEMENTATION_PLAN.md).
Section 12 of the compatibility plan only defines how `/v1` consumes those capabilities.

---

## 7. Boundaries the Implementation Must Preserve

1. Compatibility is disabled by default and enabled through an explicit feature flag.
2. `model` identifies a RAG deployment; raw backend model names cannot bypass retrieval or
   authorization policy.
3. Requests are stateless by default; server-side memory requires explicit policy.
4. Legacy and OpenAI HTTP/SSE mappers consume a shared structured internal result; one
   controller must not call another controller.
5. Effective collection scope is the intersection of deployment, API-key policy, and
   allowed request overrides, evaluated fail-closed.
6. `/v1` accepts only database-backed Bearer credentials and never query-string secrets.
7. Rate limits use a stable principal/family ID, so rotation cannot reset quota.
8. Credential, policy, or shared-limiter failure returns service unavailable and never
   falls back to static-key authorization; management lifecycle audit follows the
   fail-closed rules in the independent API-key plan.
9. Existing `/api/v1/rag/**` contracts remain independent and continue to work when
   compatibility is disabled.
10. Authentication, authorization, rate limiting, and observability are tested in both
    core-standalone and starter-consumer topologies.

---

## 8. Implementation Reading Order

1. This document: current implementation facts and security boundaries.
2. [API-key hardening implementation plan](drafts/2026-08-14_API_KEY_HARDENING_IMPLEMENTATION_PLAN.md):
   credential model, lifecycle, authorization, quota, migration, tests, and rollback.
3. [Compatibility plan sections 1–12](drafts/2026-07-21_OPENAI_CHAT_COMPLETIONS_COMPATIBILITY_PLAN.md):
   product decisions, protocol, execution architecture, and the external-credential
   integration contract.
4. Compatibility plan sections 16–19: phased delivery, tests, observability, and rollout.
5. Re-read current code and Flyway migrations before implementation; plans can lag later
   code changes.

---

## 9. Maintenance Rules

- Update this document when current implementation facts change; update the plan when the
  target design changes. Do not mix current state with planned state.
- After APIs or configuration ship, update `rest-api*`, `configuration*`, and testing
  documentation together.
- Never record real API keys, tokens, database passwords, or other secrets here.
- Keep the English and Chinese versions synchronized.
