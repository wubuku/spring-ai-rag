# 文档索引

> 📖 [English](index.md) · 📖 [中文](index-zh-CN.md)

> spring-ai-rag 文档导航。先读本页，再按任务下钻，避免一次加载全部细节。

**项目一句话**：基于 Spring AI 的通用 RAG 框架——**模型无关 · 领域解耦 · 组件化**。  
**版本**：`1.0.0` · **默认端口**：`8081` · **主 profile**：`postgresql`

---

## 1. 从哪里开始

| 你是谁 / 要做什么 | 先读 | 再读 |
|------------------|------|------|
| 第一次了解项目 | [README-zh-CN.md](../README-zh-CN.md) | [getting-started-zh-CN.md](getting-started-zh-CN.md) |
| 本地跑通服务 | [getting-started-zh-CN.md](getting-started-zh-CN.md) | [developer-reference-zh-CN.md](developer-reference-zh-CN.md)、`.env.example` |
| 改核心架构 / Pipeline | [architecture-zh-CN.md](architecture-zh-CN.md) | [IMPLEMENTATION_COMPARISON.md](IMPLEMENTATION_COMPARISON.md) |
| 改配置项 | [configuration-zh-CN.md](configuration-zh-CN.md) | `spring-ai-rag-core/src/main/resources/application.yml` |
| 对接 / 调试 HTTP API | [rest-api-zh-CN.md](rest-api-zh-CN.md) | Swagger：`/swagger-ui.html` |
| 同步外部文档 / 内容源 | [REST API：外部文档幂等同步](rest-api-zh-CN.md) | [项目上下文：外部文档同步](project-context-zh-CN.md)、[真实 HTTP 验收](developer-reference-zh-CN.md) |
| 查看当前后续改进 / TODO | [TODO-zh-CN.md](TODO-zh-CN.md) | [英文 TODO](TODO.md) |
| 规划外部 API Key 加固 | [OpenAI 兼容就绪度与代码库上下文](openai-compatibility-readiness-zh-CN.md) | [API Key 加固独立实施规划](drafts/2026-08-14_API_KEY_HARDENING_IMPLEMENTATION_PLAN.md) |
| 规划 OpenAI 兼容服务 | [OpenAI 兼容就绪度与代码库上下文](openai-compatibility-readiness-zh-CN.md) | [OpenAI Chat Completions 兼容规划](drafts/2026-07-21_OPENAI_CHAT_COMPLETIONS_COMPATIBILITY_PLAN.md) |
| 规划嵌入模型换模 / 向量迁移 | [项目上下文](project-context-zh-CN.md) | [Embedding Profile 与固定维度向量迁移规划](drafts/2026-08-15_EMBEDDING_PROFILE_VECTOR_MIGRATION_PLAN.md) |
| 使用 JSONB 结构化记录检索 | [REST API](rest-api-zh-CN.md) | [JSONB 实施规划与进度](drafts/2026-08-15_JSONB_PAYLOAD_RETRIEVAL_IMPLEMENTATION_PLAN.md) |
| 做领域定制 | [extension-guide-zh-CN.md](extension-guide-zh-CN.md) | `demos/demo-domain-extension` |
| 写测试 / 跑回归 | [testing-guide-zh-CN.md](testing-guide-zh-CN.md) | [developer-reference-zh-CN.md](developer-reference-zh-CN.md) E2E 段 |
| 线上部署 | [DEPLOYMENT.md](DEPLOYMENT.md) | `docker/`、`k8s/` |
| 出问题排查 | [troubleshooting-zh-CN.md](troubleshooting-zh-CN.md) | 下文「常见陷阱」 |
| Agent / Claude 协作 | [AGENTS.md](../AGENTS.md) → 本页 | [project-context-zh-CN.md](project-context-zh-CN.md)、[developer-reference-zh-CN.md](developer-reference-zh-CN.md) |
| Claude Code 使用 grok-4.5 | [claude-grok-proxy-zh-CN.md](claude-grok-proxy-zh-CN.md) | `scripts/run-claude-grok.sh` |

英文文档与中文同名（去掉 `-zh-CN`），内容应对齐。

---

## 2. 文档地图

### 产品与上手

| 文档 | 说明 |
|------|------|
| [README-zh-CN.md](../README-zh-CN.md) / [README.md](../README.md) | 项目门面、特性、快速开始 |
| [getting-started-zh-CN.md](getting-started-zh-CN.md) | 从零跑通第一个 RAG 问答 |
| [CONTRIBUTING-zh-CN.md](../CONTRIBUTING-zh-CN.md) | 贡献流程与开发约定 |
| [CHANGELOG-zh-CN.md](../CHANGELOG-zh-CN.md) | 版本变更 |

### 设计与实现

| 文档 | 说明 |
|------|------|
| [architecture-zh-CN.md](architecture-zh-CN.md) | 模块、三 Bean、Advisor 链、双表记忆、领域扩展 |
| [project-context-zh-CN.md](project-context-zh-CN.md) | 稳定模块、运行行为、安全边界与 1.0 基线 |
| [rest-api-zh-CN.md](rest-api-zh-CN.md) § 外部文档：幂等同步 | 外部客户的 upsert、CAS、删除恢复和同步最佳实践 |
| [TODO-zh-CN.md](TODO-zh-CN.md) / [TODO.md](TODO.md) | 当前未纳入 API 的后续改进与触发条件 |
| [extension-guide-zh-CN.md](extension-guide-zh-CN.md) | `DomainRagExtension` 扩展开发 |
| [IMPLEMENTATION_COMPARISON.md](IMPLEMENTATION_COMPARISON.md) | 与参考项目对比、Phase 完成状态 |
| [hybrid-search-enhancement-plan.md](hybrid-search-enhancement-plan.md) | 混合检索增强规划（可能滞后于代码） |
| [multi-model-enhancement-plan.md](multi-model-enhancement-plan.md) | 多模型增强规划 |
| [multi-model-external-config-zh-CN.md](multi-model-external-config-zh-CN.md) | 外部 `models.json` 配置 |
| [OpenAI 兼容就绪度与代码库上下文](openai-compatibility-readiness-zh-CN.md) | 当前 RAG 执行、运行拓扑、API Key 能力和外部服务安全缺口 |
| [API Key 加固独立实施规划](drafts/2026-08-14_API_KEY_HARDENING_IMPLEMENTATION_PLAN.md) | 外部调用凭据、policy、轮换、吊销、审计、配额和迁移前置工程（规划检查完成，待批准） |
| [OpenAI Chat Completions 兼容规划](drafts/2026-07-21_OPENAI_CHAT_COMPLETIONS_COMPATIBILITY_PLAN.md) | 将 RAG deployment 暴露为兼容模型服务；消费独立 API Key 前置工程（规划检查完成，待批准） |
| [Embedding Profile 与固定维度向量迁移规划](drafts/2026-08-15_EMBEDDING_PROFILE_VECTOR_MIGRATION_PLAN.md) | 已实施：清理无效 `rag_vector_store` 路径，使用不可变模型身份、固定维度列、Profile 级状态和可回滚换模流程 |
| [JSONB 实施规划与进度](drafts/2026-08-15_JSONB_PAYLOAD_RETRIEVAL_IMPLEMENTATION_PLAN.md) | 已实施：调用者提供 JSONB 与自然语言描述，只索引/嵌入描述，并按 collection / external ID 幂等管理 |

### 配置、API、数据

| 文档 | 说明 |
|------|------|
| [configuration-zh-CN.md](configuration-zh-CN.md) | 配置项参考 |
| [rest-api-zh-CN.md](rest-api-zh-CN.md) | REST API 参考 |
| [SSE-PROTOCOL.md](SSE-PROTOCOL.md) | 流式协议与心跳 |
| [api-versioning.md](api-versioning.md) | `/api/v1` 版本策略 |
| [postgresql-extensions.md](postgresql-extensions.md) | pgvector / pg_trgm / pg_jieba |
| [pgvector-index-comparison.md](pgvector-index-comparison.md) | HNSW vs IVFFlat 等 |

### 质量、运维、排障

| 文档 | 说明 |
|------|------|
| [testing-guide-zh-CN.md](testing-guide-zh-CN.md) | 单元 / 集成 / E2E / 覆盖率 |
| [developer-reference-zh-CN.md](developer-reference-zh-CN.md) | 可复制的构建、启动、数据库、模型、E2E 与发布命令 |
| [quality-defaults-zh-CN.md](quality-defaults-zh-CN.md) | 生产检索默认值与 goldenset 度量 |
| [release-checklist-zh-CN.md](release-checklist-zh-CN.md) | 1.0 发布门禁与产物清单 |
| [china-network-guide-zh-CN.md](china-network-guide-zh-CN.md) | 中国境内 Docker / Maven / npm / Playwright 网络避坑 |
| [troubleshooting-zh-CN.md](troubleshooting-zh-CN.md) | 按症状排障 |
| [DEPLOYMENT.md](DEPLOYMENT.md) | 部署 |
| `docs/prometheus/`、`docs/grafana/` | 监控看板与采集 |

### Agent / 项目协作

| 文档 | 体积策略 | 说明 |
|------|----------|------|
| [CLAUDE.md](../CLAUDE.md) | **极短** | Claude Code 本地启动与硬性提示 |
| [claude-grok-proxy-zh-CN.md](claude-grok-proxy-zh-CN.md) | 使用指南 | `run-claude-grok.sh` 最小配置、覆盖变量与排障 |
| [AGENTS.md](../AGENTS.md) | **短** | Agent 总入口：原则 + 规则 + 文档地图 |
| [project-context-zh-CN.md](project-context-zh-CN.md) | 长青 | 面向开发者和 Agent 的稳定项目认知 |
| [developer-reference-zh-CN.md](developer-reference-zh-CN.md) | 参考 | 构建、运行、模型、E2E 与发布命令 |
| [.agents/skills/project-docs/](../.agents/skills/project-docs/SKILL.md) | Skill | 文档体系工作流 |
| [.agents/skills/pm-24x7/](../.agents/skills/pm-24x7/SKILL.md) | Skill | 可选的 OpenClaw 7×24 项目经理工作流 |

OpenClaw 的 `TOOLS.md`、`MEMORY.md`、`memory/`、`HEARTBEAT.md` 等本地状态文件有意保持 gitignore，不属于项目文档体系；本地状态可以链接这些长青文档。
---

## 3. 代码入口（读代码时）

| 路径 | 用途 |
|------|------|
| `spring-ai-rag-api/` | DTO、SPI（`DomainRagExtension` 等） |
| `spring-ai-rag-core/` | 实现 + `SpringAiRagApplication` 可运行入口 |
| `spring-ai-rag-core/.../advisor/` | QueryRewrite → HybridSearch → Rerank |
| `spring-ai-rag-core/.../config/RagProperties.java` | `rag.*` 配置绑定 |
| `spring-ai-rag-core/src/main/resources/application.yml` | 主配置（端口 8081） |
| `spring-ai-rag-core/src/main/resources/db/migration/` | Flyway **V1–V30** |
| `spring-ai-rag-starter/` | 自动配置 `GeneralRagAutoConfiguration` |
| `spring-ai-rag-documents/` | 分块 / 清洗 |
| `spring-ai-rag-webui/` | React 管理台（独立 npm 工程） |
| `demos/` | basic / component / domain / multi-model |
| `scripts/` | `start-server.sh`、`e2e-test.sh`、k6、Playwright |

---

## 4. 常见陷阱（先记这几条）

细节与排障步骤见 [troubleshooting-zh-CN.md](troubleshooting-zh-CN.md)、[developer-reference-zh-CN.md](developer-reference-zh-CN.md)。

1. **OpenAI / Embedding `base-url` 不要带 `/v1`**  
   Spring AI 会再拼 `/v1/chat/completions` 或 `/v1/embeddings`，带了会变成 `/v1/v1/...` → 401/404。
2. **本地务必** `SPRING_PROFILES_ACTIVE=postgresql`（见 `.env` / `CLAUDE.md`）。
3. **本地、Docker 与 Helm 的默认 HTTP 端口均为 8081**。
4. **向量维度 1024** 必须与 BGE-M3 / `VECTOR(1024)` 一致；换模型要同步改库。
5. **WebUI** 需单独 `npm` 构建，或拷贝到 `spring-ai-rag-core/src/main/resources/static/webui/`。
6. **测试与代码同等重要**：`mvn test` 不过不算完成；重要改动后跑 E2E（见 [testing-guide-zh-CN.md](testing-guide-zh-CN.md)）。
7. **境内 Docker 拉取超时**：使用 `scripts/docker-build-local.sh`，不要把区域镜像硬编码进 Dockerfile（见 [china-network-guide-zh-CN.md](china-network-guide-zh-CN.md)）。

---

## 5. 外部参考仓库（本机路径）

设计对照用，非本仓库内容：

| 路径 | 用途 |
|------|------|
| `../spring-ai-skills-demo` | Spring AI ChatClient / Advisor / VectorStore |
| `../taisan/MaxKB4j` | Pipeline 模式、模型抽象 |
| `../RuiChuangQi-AI/src/dermai-rag-service` | 混合检索 / 改写 / 重排迁移来源 |

对比结论见 [IMPLEMENTATION_COMPARISON.md](IMPLEMENTATION_COMPARISON.md)。

---

## 6. 维护约定

- **入口文档保持短**：`CLAUDE.md`、`AGENTS.md` 只放硬性提示 + 链接；稳定认知下沉到 `project-context*`，命令下沉到 `developer-reference*`。
- **本地状态保持本地**：OpenClaw 状态文件继续 gitignore，可以引用项目长青文档，项目文档不能反向依赖它们。
- **改行为就改文档**：配置项 → `configuration*`；API → `rest-api*`；架构决策 → `architecture*`。**中英文成对存在时必须同步更新。**
- **规划类文档**（`*-plan.md`、`drafts/`）可能滞后于代码，以代码与 `IMPLEMENTATION_COMPARISON.md` 为准。
- **不要把密钥写进文档**；密钥只在 `.env`（已 gitignore）。
