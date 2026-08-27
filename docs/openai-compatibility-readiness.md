# OpenAI Compatibility Readiness and Codebase Context

> 📖 [English](openai-compatibility-readiness.md) · 📖 [中文](openai-compatibility-readiness-zh-CN.md)

> **Purpose**: Record the current server-side OpenAI Chat Completions
> implementation, controlled-preview boundary, and remaining public /
> multi-instance production security work.
> **Code baseline**: current delivery baseline, including Chat-turn reliability,
> V48 managed-principal hardening, and V55 bounded staged credential rotation.
> **Last verified**: 2026-08-27
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
| `spring-ai-rag-webui` | React admin UI | Root unlock manages one row per stable principal, including policy CAS, quota, staged/immediate credential rotation, revocation, and shown-once secrets |

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
- Create, list, revoke, immediate rotate, bounded staged rotate, expiration, and
  `last_used_at` are supported.
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

## 6. Remaining Boundaries for External Production Callers

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
7. PostgreSQL limiting uses the authenticated stable principal and is shared
   across instances; immediate or staged rotation does not reset it. The local
   backend remains an explicit single-instance compatibility option.
8. Credential and PostgreSQL quota-store failures fail closed. Public
   enablement should still remove legacy static/query compatibility.
9. Existing `/api/v1/rag/**` contracts remain independent and continue to work when
   compatibility is disabled.
10. Authentication, authorization, rate limiting, and observability are tested in both
    core-standalone and starter-consumer topologies.
11. A mixed V54/V55 fleet must freeze API-key management writes and staged
    prepare. Staged rotation is enabled only after all instances run V55; before
    application rollback to V54, operators must clear enabled retiring
    credentials and `PENDING` rotation operations while retaining the V55
    schema.

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
