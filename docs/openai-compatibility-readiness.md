# OpenAI Compatibility Readiness and Codebase Context

> 📖 [English](openai-compatibility-readiness.md) · 📖 [中文](openai-compatibility-readiness-zh-CN.md)

> **Purpose**: Record the current server-side OpenAI Chat Completions
> implementation, controlled-preview boundary, and remaining public /
> multi-instance production security work.
> **Code baseline**: `main`, including the 2026-08-17 controlled compatibility preview.
> **Last verified**: 2026-08-17
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
The project has implemented the standalone-service API-key MVP and a
disabled-by-default `/v1` adapter. It is suitable for controlled trusted-network
preview and SDK integration tests, but not public, multi-instance production.
**API-key families, immediate cross-instance revocation, shared quotas, and a
complete fail-closed policy remain prerequisites for public enablement.**

The adapter is not an agent/subagent orchestrator. It provides a stable
"RAG-as-a-model" boundary; orchestration belongs to the caller or a later independent
module.

---

## 2. Relevant Module Boundaries

| Module | Current responsibility | Constraint for compatibility work |
|--------|------------------------|-----------------------------------|
| `spring-ai-rag-api` | DTOs and SPIs | OpenAI DTOs live in an isolated `openai` package and do not change the existing `ChatRequest` contract |
| `spring-ai-rag-core` | RAG implementation and runnable app | Owns the shared execution layer, compatibility controller, model-alias registry, and error mapping |
| `spring-ai-rag-starter` | Auto-configuration | Registers authentication, rate limiting, and observability for `/v1` in both topologies |
| `spring-ai-rag-documents` | Document processing | Must remain independent of the OpenAI protocol |
| `spring-ai-rag-webui` | React admin UI | The MVP now provides root-key unlock; it need not switch APIs, while public management still requires hardening |

There are two runtime topologies:

1. Run the application from `spring-ai-rag-core`.
2. Add `spring-ai-rag-starter` to another Spring Boot application.

Security cannot work in only one topology. `/api/*` and `/v1/*` share
authentication and rate-limit filters, with focused tests covering the
core-standalone and starter-consumer bean boundaries.

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
- Native Chat SSE keeps RAG events such as `tool_start`, `tool_result`,
  `sources`, and `done`; it is a separate contract from the `/v1` stream.

See [architecture.md](architecture.md), [rest-api.md](rest-api.md), and
[SSE-PROTOCOL.md](SSE-PROTOCOL.md) for the current architecture and HTTP contracts.

---

## 4. Do Not Confuse the Two OpenAI-Compatible Directions

| Direction | Status | Meaning |
|-----------|--------|---------|
| `spring-ai-rag -> OpenAI-compatible provider` | Implemented | This project calls OpenAI, DeepSeek, SiliconFlow, and similar upstream APIs |
| `OpenAI client / Agent -> spring-ai-rag` | Controlled preview, disabled by default | This project serves `/v1/chat/completions` and Models APIs |

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
- Root-created keys have a fixed `FULL_RAG` profile, require a future expiry
  without a fixed maximum lifetime, and are usable by external callers without
  the WebUI.
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
| URL and credential format | `/v1/*` uses Bearer/header authentication; root mode rejects query credentials and emits OpenAI error envelopes | Public enablement should remove legacy query/static compatibility and require managed principals |
| Failure semantics | Database validation and static fallback coexist | Credential-store failures must not downgrade into an authorization bypass |

These are service-readiness requirements, not protocol details. If public-service
hardening resumes, it must jointly design stable families/principals, rotatable
versions, explicit policy, shared quotas, migration, and fail-closed failure
semantics while preserving the compatibility boundaries recorded here.

---

## 7. Current Implementation And Public-Enablement Boundaries

1. Compatibility is disabled by default and enabled through an explicit feature flag.
2. `model` identifies a RAG alias; arbitrary backend model names cannot bypass retrieval or
   authorization policy.
3. Requests are stateless by default; server-side memory requires explicit policy.
4. Legacy and OpenAI HTTP/SSE mappers consume a shared structured internal result; one
   controller must not call another controller.
5. A model alias contains no fixed Collection. Effective scope comes from the
   request and API-key ACL and rejects unauthorized expansion.
6. In root mode, `/v1` accepts Bearer / `X-API-Key` and rejects query-string secrets.
7. Current limiting uses the authenticated stable principal ID but remains
   process-local. Public multi-instance use requires a shared quota that
   rotation cannot reset.
8. Credential-store failure returns `503`. Public enablement must also remove
   downgrade ambiguity from legacy static fallbacks.
9. Existing `/api/v1/rag/**` contracts remain independent and continue to work when
   compatibility is disabled.
10. Authentication, authorization, rate limiting, and observability are tested in both
    core-standalone and starter-consumer topologies.

---

## 8. Maintenance And Hardening Reading Order

1. This document: current controlled-preview facts and security boundaries.
2. [REST API](rest-api.md) and [configuration](configuration.md): callable contract.
3. [Project context](project-context.md) and [testing guide](testing-guide.md):
   stable capability boundaries and current verification entry points.
4. [Historical plan archive](drafts/archive/README.md): read only for design
   provenance and old verification evidence; current code and live references
   win when they differ.

---

## 9. Maintenance Rules

- Update this document when current implementation facts change; update the plan when the
  target design changes. Do not mix current state with planned state.
- After APIs or configuration ship, update `rest-api*`, `configuration*`, and testing
  documentation together.
- Never record real API keys, tokens, database passwords, or other secrets here.
- Keep the English and Chinese versions synchronized.
