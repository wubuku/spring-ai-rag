# Project Context

> [English](project-context.md) | [中文](project-context-zh-CN.md)

> **Purpose**: Give contributors and Agents stable, code-backed project context.
> **Last reviewed**: 2026-08-14.
> This document records current facts. Target designs and unimplemented capabilities must be labeled as plans.

Documentation hub: [index.md](index.md). Commands: [developer-reference.md](developer-reference.md).

## 1. Positioning

spring-ai-rag is a general RAG framework built on Spring AI:

- Model-agnostic: Chat and Embedding providers are independent.
- Domain-decoupled: `DomainRagExtension` customizes prompts and retrieval.
- Componentized: API, core, starter, document processing, and WebUI are separate.
- Observable: retrieval logs, evaluation, feedback, A/B tests, alerts, and metrics.
- Deliverable: Docker, Helm, an embedded WebUI bundle, and release gates.

## 2. Module Boundaries

| Module | Responsibility |
|--------|----------------|
| `spring-ai-rag-api` | DTOs and SPIs; no business implementation |
| `spring-ai-rag-core` | RAG implementation, controllers, advisors, services, runnable app |
| `spring-ai-rag-starter` | Spring Boot auto-configuration and embedded integration |
| `spring-ai-rag-documents` | Document chunking, cleaning, and processing |
| `spring-ai-rag-webui` | React administration UI |
| `demos` | Basic, component, domain, and multi-model examples |

The system supports two runtime topologies:

1. Run the Core application directly.
2. Add the Starter to another Spring Boot application.

Security, rate limiting, and auto-configuration changes must verify both.

## 3. RAG Execution

Default advisor order:

```text
QueryRewriteAdvisor (+10)
  -> HybridSearchAdvisor (+20)
  -> RerankAdvisor (+30)
  -> MessageChatMemoryAdvisor
```

Key rules:

- Chat and search support Collection / Document scope.
- An explicitly empty scope must fail closed instead of becoming a full-corpus query.
- `RerankAdvisor` injects context into the user message for providers that restrict multiple system messages.
- Spring AI memory and business audit history are stored separately.

See [architecture.md](architecture.md).

## 4. Retrieval And Quality

- Embedding defaults to SiliconFlow `BAAI/bge-m3`.
- Vector dimension is `1024` and must match PostgreSQL `VECTOR(1024)`.
- Retrieval combines vector and full-text signals.
- The production profile recommends query rewrite and local heuristic reranking.
- Goldenset metrics include Precision@K, MRR, and nDCG.

The small online goldenset gave perfect baseline and quality scores. Deterministic MRR tests demonstrate reranking gain; the online sample is not statistical evidence.

See [quality-defaults.md](quality-defaults.md).

## 5. Multi-Model Runtime

- Legacy provider beans remain for default-model compatibility.
- `ConfiguredChatModelFactory` creates and caches real instances by `provider/modelId`.
- `ChatModelRouter` owns explicit selection, defaults, and fallback.
- Chat, Settings, and model comparison accept concrete model references.
- External `models.json` can override YAML model configuration.

See [multi-model-external-config.md](multi-model-external-config.md).

## 6. Data And APIs

### Database

- PostgreSQL with pgvector.
- Flyway is currently V1–V24.
- `vector` is required, `pg_trgm` is recommended, and `pg_jieba` is optional.
- Chat memory, business history, retrieval logs, evaluation, feedback, A/B tests, alerts, API keys, and files are stored separately.

### HTTP

The main namespace is `/api/v1/rag/**`:

| Area | Capability |
|------|------------|
| `/chat`, `/chat/stream` | RAG chat |
| `/documents` | Document management and embedding |
| `/search` | Hybrid retrieval |
| `/collections` | Knowledge collections |
| `/evaluation` | Evaluation and feedback |
| `/api-keys` | API-key management |
| `/files` | PDF and file import |

See [rest-api.md](rest-api.md) and [SSE-PROTOCOL.md](SSE-PROTOCOL.md).

## 7. Security And Collection ACL

Two compatible operating modes are available.

Standalone-service MVP mode is enabled explicitly by `RAG_ROOT_API_KEY`:

- The environment root protects `/api/**` independently of the legacy auth flag.
- The root unlocks the administration UI at `/webui/unlock`; the browser keeps
  it only in page memory and requires it again after refresh.
- Only the root can create, list, rotate, and revoke business keys.
- Root-created keys have a fixed `FULL_RAG` data-plane profile. They can read
  and write RAG data and may be Collection-scoped, but cannot manage keys.
- Business-key expiry is required and limited to 90 days. Raw secrets appear
  only in create or rotate responses.
- Root mode accepts only Bearer or `X-API-Key` headers, rejects query
  credentials, and disables legacy ADMIN bootstrap/raw-secret logging.

Without a root credential, legacy ADMIN/NORMAL/static-key behavior remains.

Database API keys support:

- Hash lookup.
- `ADMIN` / `NORMAL` roles.
- Expiration, revocation, rotation, and `last_used_at`.
- `allowedCollectionIds`.
- Data-plane ACLs for Chat, Search, Collections, Documents, and PDF-to-RAG.

This MVP is limited to a single instance, TLS, and a trusted management
network. It is not yet a complete multi-tenant external credential system:

- The schema retains a plaintext column.
- NORMAL-key delegation needs stronger boundaries.
- Rotation lacks a stable principal or family.
- There is no transactional last-ADMIN guard.
- Multi-instance revocation, shared limiting, and write amplification remain unresolved.

See [openai-compatibility-readiness.md](openai-compatibility-readiness.md) and the
[API-key hardening implementation plan](drafts/2026-08-14_API_KEY_HARDENING_IMPLEMENTATION_PLAN.md).

## 8. OpenAI Compatibility Direction

Do not confuse the two directions:

```text
Implemented: spring-ai-rag -> OpenAI-compatible provider
Not implemented: OpenAI client / Agent -> spring-ai-rag
```

The project does not currently expose a standard `POST /v1/chat/completions` or Models API. Existing SSE only emits a partial OpenAI-like delta and is not Chat Completions compatible.

The planned compatibility layer presents a complete RAG deployment as a `model`. It is disabled and stateless by default, and requires external API-key, Bearer-authentication, and multi-instance rate-limit hardening first.

See the [OpenAI Chat Completions compatibility plan](drafts/2026-07-21_OPENAI_CHAT_COMPLETIONS_COMPATIBILITY_PLAN.md).

## 9. Stable 1.0 Baseline

Implemented:

- Production quality defaults and a retrieval goldenset.
- Collection-to-API-key ACLs.
- Runtime model instances and UI model selection.
- Maven, demos, OpenAPI, Helm, and Docker standardized on `1.0.0`.
- Embedded production WebUI bundle.
- Mainland-China-friendly Docker build path.
- One-command release verification.

Full gate on 2026-07-21:

```text
19 passed, 0 failed, 0 skipped
Maven 3213 tests
Vitest 153
Playwright 37
HTTP E2E 66/66
Real LLM 10/10
```

See [P1 / 1.0 readiness progress](drafts/2026-07-21_P1_10_READINESS_PROGRESS.md).

## 10. Explicit Boundaries

- The immutable `1.0.0` source/image tag has not been created; the release pipeline owns it.
- Server-side OpenAI compatibility remains a plan, not a current feature.
- OpenClaw `TOOLS.md`, `MEMORY.md`, `memory/`, `HEARTBEAT.md`, and related files are local state outside the project documentation system.
- Project Skills live under `.agents/skills/`; workflows may link here but must not duplicate project facts.

## 11. Source-Of-Truth Order

When information conflicts, use:

1. Current code and migrations.
2. Live references and guides under `docs/`.
3. Entry rules in `AGENTS.md` and `CLAUDE.md`.
4. `docs/drafts/` and `*-plan.md`.
5. Local Agent state.
