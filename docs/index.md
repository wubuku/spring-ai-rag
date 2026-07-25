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
| Run the service locally | [getting-started.md](getting-started.md) | [TOOLS.md](../TOOLS.md), `.env.example` |
| Change core architecture / pipeline | [architecture.md](architecture.md) | [IMPLEMENTATION_COMPARISON.md](IMPLEMENTATION_COMPARISON.md) |
| Change configuration | [configuration.md](configuration.md) | `spring-ai-rag-core/src/main/resources/application.yml` |
| Integrate / debug HTTP APIs | [rest-api.md](rest-api.md) | Swagger: `/swagger-ui.html` |
| Domain customization | [extension-guide.md](extension-guide.md) | `demos/demo-domain-extension` |
| Write / run tests | [testing-guide.md](testing-guide.md) | [TOOLS.md](../TOOLS.md) E2E section |
| Production deploy | [DEPLOYMENT.md](DEPLOYMENT.md) | `docker/`, `k8s/` |
| Troubleshooting | [troubleshooting.md](troubleshooting.md) | “Common pitfalls” below |
| Agent / Claude collaboration | [AGENTS.md](../AGENTS.md) → this page | [CLAUDE.md](../CLAUDE.md), [TOOLS.md](../TOOLS.md), [MEMORY.md](../MEMORY.md) |
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
| [architecture.md](architecture.md) | Modules, three-Bean model, Advisor chain, dual memory, domain extension |
| [extension-guide.md](extension-guide.md) | `DomainRagExtension` development |
| [IMPLEMENTATION_COMPARISON.md](IMPLEMENTATION_COMPARISON.md) | Comparison with reference projects; phase status |
| [hybrid-search-enhancement-plan.md](hybrid-search-enhancement-plan.md) | Hybrid search plan (may lag code) |
| [multi-model-enhancement-plan.md](multi-model-enhancement-plan.md) | Multi-model plan |
| [multi-model-external-config.md](multi-model-external-config.md) | External `models.json` config |

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
| [quality-defaults.md](quality-defaults.md) | Production retrieval defaults and goldenset measurement |
| [release-checklist.md](release-checklist.md) | 1.0 release gates and artifact checklist |
| [china-network-guide.md](china-network-guide.md) | Mainland China Docker / Maven / npm / Playwright network pitfalls |
| [troubleshooting.md](troubleshooting.md) | Symptom-based troubleshooting |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Deployment |
| `docs/prometheus/`, `docs/grafana/` | Metrics & dashboards |

### Agent / local collaboration (progressive entry)

| Doc | Size policy | Description |
|-----|-------------|-------------|
| [CLAUDE.md](../CLAUDE.md) | **Very short** | Claude Code local start & hard tips |
| [claude-grok-proxy.md](claude-grok-proxy.md) | Guide | Minimum `run-claude-grok.sh` setup, overrides, and troubleshooting |
| [AGENTS.md](../AGENTS.md) | **Short** | Agent hub: principles + rules + map |
| [TOOLS.md](../TOOLS.md) | Medium | Build commands, DB, models, paths, E2E |
| [MEMORY.md](../MEMORY.md) | Medium-long | Dev cheat sheet (packages, API, pitfalls) |
| `memory/YYYY-MM-DD.md` | Logs | Daily work notes |
| [SOUL.md](../SOUL.md) / [IDENTITY.md](../IDENTITY.md) / [USER.md](../USER.md) | Role | Agent persona (not product docs) |
| [HEARTBEAT.md](../HEARTBEAT.md) | Status log | Automation heartbeat; **not** architecture |
| [skills/project-docs/](../skills/project-docs/SKILL.md) | Skill | Documentation system skill (alongside `pm-24x7`; self-contained) |

---

## 3. Code entry points

| Path | Purpose |
|------|---------|
| `spring-ai-rag-api/` | DTOs, SPI (`DomainRagExtension`, etc.) |
| `spring-ai-rag-core/` | Implementation + runnable `SpringAiRagApplication` |
| `spring-ai-rag-core/.../advisor/` | QueryRewrite → HybridSearch → Rerank |
| `spring-ai-rag-core/.../config/RagProperties.java` | `rag.*` binding |
| `spring-ai-rag-core/src/main/resources/application.yml` | Main config (port 8081) |
| `spring-ai-rag-core/src/main/resources/db/migration/` | Flyway **V1–V24** |
| `spring-ai-rag-starter/` | Auto-config `GeneralRagAutoConfiguration` |
| `spring-ai-rag-documents/` | Chunking / cleaning |
| `spring-ai-rag-webui/` | React admin UI (standalone npm) |
| `demos/` | basic / component / domain / multi-model |
| `scripts/` | `start-server.sh`, `e2e-test.sh`, k6, Playwright |

---

## 4. Common pitfalls

Details: [troubleshooting.md](troubleshooting.md), [TOOLS.md](../TOOLS.md).

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

- **Keep entry docs short**: `CLAUDE.md` and `AGENTS.md` hold hard tips + links only; sink detail into this tree or `TOOLS.md` / `MEMORY.md`.
- **Docs follow code**: config → `configuration*`; API → `rest-api*`; design → `architecture*`. **Keep EN/ZH pairs in sync** when both exist.
- **Plan docs** (`*-plan.md`, `drafts/`) may lag; prefer code and `IMPLEMENTATION_COMPARISON.md`.
- **Never commit secrets**; keys live only in `.env` (gitignored).
