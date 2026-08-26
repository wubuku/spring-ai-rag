# Documentation Index

> 📖 [English](index.md) · 📖 [中文](index-zh-CN.md)

> Navigation hub for spring-ai-rag. Read this page first, then drill down by task — avoid loading every detail at once.

**One-liner**: A Spring AI–based general RAG framework — **model-agnostic · domain-decoupled · componentized**.  
**Version**: `1.0.0` · **Default port**: `8081` · **Primary profile**: `postgresql`

---

## 1. Where to start

| Who you are / what you need | Read first | Then |
|----------------------------|------------|------|
| New to the project | [README.md](../README.md) | [getting-started.md](getting-started.md) |
| Run the service locally | [getting-started.md](getting-started.md) | [developer-reference.md](developer-reference.md), `.env.example` |
| Change core architecture / pipeline | [architecture.md](architecture.md) | [IMPLEMENTATION_COMPARISON.md](IMPLEMENTATION_COMPARISON.md) |
| Understand Chat memory, context budgets, RAG, and tool calling | [Chat Memory, RAG, And Tool Calling](chat-memory-rag-tool-calling.md) | [Architecture](architecture.md), [Configuration](configuration.md) |
| Change configuration | [configuration.md](configuration.md) | `spring-ai-rag-core/src/main/resources/application.yml` |
| Integrate / debug HTTP APIs | [rest-api.md](rest-api.md) | Swagger: `/swagger-ui.html` |
| Connect a backend business service to the RAG data plane and complete production acceptance | [Business Service Integration Guide](business-client-integration.md) | [REST API: Authentication](rest-api.md#authentication), [one-command acceptance](developer-reference.md#business-service-integration-readiness-verification) |
| Understand Files, PDF import, and Add to RAG | [File management and PDF-to-RAG workflow](file-management-and-pdf-rag.md) | [REST API: PDF and file artifacts](rest-api.md#pdf-and-file-artifact-apis) |
| Choose Collection retrieval scope for an external client | [REST API: External-client best practices](rest-api.md#external-client-best-practices) | [Deferred coverage-mode TODO](TODO.md#each_collection-retrieval-coverage-mode) |
| Synchronize external documents / content sources | [External Document Sync Client Guide](external-document-sync-client-guide.md) | [REST API contract](rest-api.md#external-documents-idempotent-synchronization), [one-command lifecycle verification](developer-reference.md#document-lifecycle-verification) |
| Review current future work / TODO | [TODO.md](TODO.md) | [Chinese TODO](TODO-zh-CN.md) |
| Understand external API-key / OpenAI compatibility security boundaries | [OpenAI compatibility readiness and codebase context](openai-compatibility-readiness.md) | [Configuration reference](configuration.md) |
| Integrate the OpenAI compatibility preview | [REST API: OpenAI compatibility preview](rest-api.md#openai-chat-completions-compatibility-preview) | [OpenAI compatibility readiness and boundaries](openai-compatibility-readiness.md) |
| Plan embedding-model / vector migration | [Project context](project-context.md) | [Architecture](architecture.md) |
| Use JSONB structured-record retrieval | [REST API: JSONB Payload Retrieval](rest-api.md#json-structured-records--jsonb-payload-retrieval) | [External Document Sync Client Guide](external-document-sync-client-guide.md) |
| Run durable embedding jobs / quality regression | [Developer reference](developer-reference.md) | [Testing guide](testing-guide.md) |
| Govern or modify WebUI horizontal alignment | [WebUI horizontal-alignment guidelines](webui-alignment-guidelines.md) | [Testing guide](testing-guide.md) |
| Domain customization | [extension-guide.md](extension-guide.md) | `demos/demo-domain-extension` |
| Write / run tests | [testing-guide.md](testing-guide.md) | [developer-reference.md](developer-reference.md) E2E section |
| Plan, implement, accept, and deliver substantial work | [Planning, Implementation, And Acceptance Workflow](delivery-workflow.md) | [Current active plans](drafts/README.md), [Testing guide](testing-guide.md) |
| Production deploy | [DEPLOYMENT.md](DEPLOYMENT.md) | `docker/`, `k8s/` |
| Troubleshooting | [troubleshooting.md](troubleshooting.md) | “Common pitfalls” below |
| Agent / Claude collaboration | [AGENTS.md](../AGENTS.md) → this page | [project-context.md](project-context.md), [developer-reference.md](developer-reference.md) |
| Use grok-4.5 with Claude Code | [claude-grok-proxy.md](claude-grok-proxy.md) | `scripts/run-claude-grok.sh` |

Chinese counterparts use the same basename with a `-zh-CN` suffix where available.

---

## 2. Documentation map

### Product & getting started

| Doc | Description |
|-----|-------------|
| [README.md](../README.md) / [README-zh-CN.md](../README-zh-CN.md) | Project front door, features, quick start |
| [getting-started.md](getting-started.md) | First RAG Q&A from zero |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | Contribution workflow |
| [CHANGELOG.md](../CHANGELOG.md) | Release notes |

### Design & implementation

| Doc | Description |
|-----|-------------|
| [architecture.md](architecture.md) | Modules, runtime model routing, three Chat modes, dual memory, domain extension |
| [chat-memory-rag-tool-calling.md](chat-memory-rag-tool-calling.md) / [中文](chat-memory-rag-tool-calling-zh-CN.md) | Current Spring AI Chat Memory, Modular RAG, and Tool Calling boundaries, gaps, and direction |
| [project-context.md](project-context.md) | Stable modules, runtime behavior, security boundaries, and 1.0 baseline |
| [file-management-and-pdf-rag.md](file-management-and-pdf-rag.md) | Files versus Documents, PDF conversion artifacts, Add to RAG, and current lifecycle boundaries |
| [rest-api.md](rest-api.md#external-documents-idempotent-synchronization) § External Documents — Idempotent Synchronization | External-client upsert, CAS, deletion/recovery, and synchronization best practices |
| [external-document-sync-client-guide.md](external-document-sync-client-guide.md) | External-client triple identity, incremental CRUD, retry/checkpoint, and search-readiness guidance |
| [business-client-integration.md](business-client-integration.md) / [中文](business-client-integration-zh-CN.md) | Credentials, binding, JSON Records, rotation, upgrades, and the contract gate for backend business services |
| [TODO.md](TODO.md) / [TODO-zh-CN.md](TODO-zh-CN.md) | Current follow-up work outside the public API and its revisit criteria |
| [extension-guide.md](extension-guide.md) | `DomainRagExtension` development |
| [IMPLEMENTATION_COMPARISON.md](IMPLEMENTATION_COMPARISON.md) | Comparison with reference projects; phase status |
| [hybrid-search-enhancement-plan.md](hybrid-search-enhancement-plan.md) | Hybrid search plan (may lag code) |
| [multi-model-enhancement-plan.md](multi-model-enhancement-plan.md) | Multi-model plan |
| [multi-model-external-config.md](multi-model-external-config.md) | External `models.json` config |
| [OpenAI compatibility readiness and codebase context](openai-compatibility-readiness.md) | Disabled-by-default controlled preview, request-scoped Collections, compatibility subset, and public-service gaps |
| [WebUI horizontal-alignment guidelines](webui-alignment-guidelines.md) / [中文](webui-alignment-guidelines-zh-CN.md) | WebUI defaults, justified centering exceptions, the `alignment-policy` gate, and verification commands |
| [Current active plans](drafts/README.md) | Lists only work still being prepared or implemented; code and evergreen docs remain authoritative |
| [Historical plan and implementation archive](drafts/archive/README.md) | Design and verification provenance only; not a default reading path for Agents |

### Config, API, data

| Doc | Description |
|-----|-------------|
| [configuration.md](configuration.md) | Configuration reference |
| [rest-api.md](rest-api.md) | REST API reference |
| [SSE-PROTOCOL.md](SSE-PROTOCOL.md) | Streaming protocol & heartbeat |
| [api-versioning.md](api-versioning.md) | `/api/v1` versioning |
| [postgresql-extensions.md](postgresql-extensions.md) | pgvector / pg_trgm / pg_jieba |
| [pgvector-index-comparison.md](pgvector-index-comparison.md) | HNSW vs IVFFlat, etc. |

### Quality, ops, troubleshooting

| Doc | Description |
|-----|-------------|
| [testing-guide.md](testing-guide.md) | Unit / integration / E2E / coverage |
| [developer-reference.md](developer-reference.md) | Copyable build, start, database, model, E2E, and release commands |
| [quality-defaults.md](quality-defaults.md) | Production retrieval defaults and goldenset measurement |
| [release-checklist.md](release-checklist.md) | 1.0 release gates and artifact checklist |
| [china-network-guide.md](china-network-guide.md) | Mainland China Docker / Maven / npm / Playwright network pitfalls |
| [troubleshooting.md](troubleshooting.md) | Symptom-based troubleshooting |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Deployment |
| `docs/prometheus/`, `docs/grafana/` | Metrics & dashboards |

### Agent / project collaboration

| Doc | Size policy | Description |
|-----|-------------|-------------|
| [CLAUDE.md](../CLAUDE.md) | **Very short** | Claude Code local start & hard tips |
| [claude-grok-proxy.md](claude-grok-proxy.md) | Guide | Minimum `run-claude-grok.sh` setup, overrides, and troubleshooting |
| [AGENTS.md](../AGENTS.md) | **Short** | Agent hub: principles + rules + map |
| [project-context.md](project-context.md) | Evergreen | Stable project knowledge for contributors and Agents |
| [developer-reference.md](developer-reference.md) | Reference | Build, runtime, model, E2E, and release commands |
| [delivery-workflow.md](delivery-workflow.md) / [中文](delivery-workflow-zh-CN.md) | Evergreen | Self-contained planning, resumable progress, acceptance gates, convergence reviews, and Git delivery |
| [.agents/skills/project-docs/](../.agents/skills/project-docs/SKILL.md) | Skill | Documentation-system workflow |
| [.agents/skills/pm-24x7/](../.agents/skills/pm-24x7/SKILL.md) | Skill | Optional always-on OpenClaw project-manager workflow |

OpenClaw local state such as `TOOLS.md`, `MEMORY.md`, `memory/`, and `HEARTBEAT.md` is intentionally gitignored and is not part of the project documentation system. Local state may link to these evergreen documents.
---

## 3. Code entry points

| Path | Purpose |
|------|---------|
| `spring-ai-rag-api/` | DTOs, SPI (`DomainRagExtension`, etc.) |
| `spring-ai-rag-core/` | Implementation + runnable `SpringAiRagApplication` |
| `spring-ai-rag-core/.../chat/`, `.../rag/` | Mode-aware Chat execution, Modular RAG, and Tool Calling |
| `spring-ai-rag-core/.../config/RagProperties.java` | `rag.*` binding |
| `spring-ai-rag-core/src/main/resources/application.yml` | Main config (port 8081) |
| `spring-ai-rag-core/src/main/resources/db/migration/` | Flyway **V1–V49** |
| `spring-ai-rag-starter/` | Auto-config `GeneralRagAutoConfiguration` |
| `spring-ai-rag-documents/` | Chunking / cleaning |
| `spring-ai-rag-webui/` | React admin UI (standalone npm) |
| `demos/` | basic / component / domain / multi-model |
| `scripts/` | Startup, E2E, OpenAI/jobs/JSONB, document-relocation, derivation-integrity, retrieval diagnostics/filters, embedding operations, managed quality, goldenset, and quality-regression verification |

---

## 4. Common pitfalls

Details: [troubleshooting.md](troubleshooting.md), [developer-reference.md](developer-reference.md).

1. **Do not put `/v1` on OpenAI / Embedding `base-url`**  
   Spring AI appends `/v1/chat/completions` or `/v1/embeddings`; a trailing `/v1` becomes `/v1/v1/...` → 401/404.
2. **Local runs need** `SPRING_PROFILES_ACTIVE=postgresql` (see `.env` / `CLAUDE.md`).
3. **Default HTTP port is 8081** for local, Docker, and Helm deployments.
4. **Vector dimension 1024** must match BGE-M3 / `VECTOR(1024)`; changing models means changing the DB too.
5. **WebUI** is a separate `npm` build, or copy into `spring-ai-rag-core/src/main/resources/static/webui/`.
6. **Tests equal production code**: `mvn test` must pass; run E2E after meaningful changes ([testing-guide.md](testing-guide.md)).
7. **Docker pulls time out in mainland China**: use `scripts/docker-build-local.sh`; do not hard-code a regional mirror in the Dockerfile ([china-network-guide.md](china-network-guide.md)).

---

## 5. External reference repos (local paths)

For design comparison only — not part of this repository:

| Path | Use |
|------|-----|
| `../spring-ai-skills-demo` | Spring AI ChatClient / Advisor / VectorStore |
| `../taisan/MaxKB4j` | Pipeline pattern, model abstraction |
| `../RuiChuangQi-AI/src/dermai-rag-service` | Hybrid search / rewrite / rerank origin |

Conclusions: [IMPLEMENTATION_COMPARISON.md](IMPLEMENTATION_COMPARISON.md).

---

## 6. Maintenance rules

- **Keep entry docs short**: `CLAUDE.md` and `AGENTS.md` hold hard tips + links only; sink stable context into `project-context*` and commands into `developer-reference*`.
- **Keep local state local**: OpenClaw state files are gitignored and may reference project docs, never the reverse.
- **Docs follow code**: config → `configuration*`; API → `rest-api*`; design → `architecture*`. **Keep EN/ZH pairs in sync** when both exist.
- **Plan docs** (`*-plan.md`, `drafts/`) may lag; prefer current code and
  evergreen reference/guides. Use `IMPLEMENTATION_COMPARISON.md` only as
  supplementary status and historical comparison.
- **Never commit secrets**; keys live only in `.env` (gitignored).
