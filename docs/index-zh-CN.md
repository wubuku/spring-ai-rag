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
| 本地跑通服务 | [getting-started-zh-CN.md](getting-started-zh-CN.md) | [TOOLS.md](../TOOLS.md)、`.env.example` |
| 改核心架构 / Pipeline | [architecture-zh-CN.md](architecture-zh-CN.md) | [IMPLEMENTATION_COMPARISON.md](IMPLEMENTATION_COMPARISON.md) |
| 改配置项 | [configuration-zh-CN.md](configuration-zh-CN.md) | `spring-ai-rag-core/src/main/resources/application.yml` |
| 对接 / 调试 HTTP API | [rest-api-zh-CN.md](rest-api-zh-CN.md) | Swagger：`/swagger-ui.html` |
| 做领域定制 | [extension-guide-zh-CN.md](extension-guide-zh-CN.md) | `demos/demo-domain-extension` |
| 写测试 / 跑回归 | [testing-guide-zh-CN.md](testing-guide-zh-CN.md) | [TOOLS.md](../TOOLS.md) E2E 段 |
| 线上部署 | [DEPLOYMENT.md](DEPLOYMENT.md) | `docker/`、`k8s/` |
| 出问题排查 | [troubleshooting-zh-CN.md](troubleshooting-zh-CN.md) | 下文「常见陷阱」 |
| Agent / Claude 协作 | [AGENTS.md](../AGENTS.md) → 本页 | [CLAUDE.md](../CLAUDE.md)、[TOOLS.md](../TOOLS.md)、[MEMORY.md](../MEMORY.md) |

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
| [extension-guide-zh-CN.md](extension-guide-zh-CN.md) | `DomainRagExtension` 扩展开发 |
| [IMPLEMENTATION_COMPARISON.md](IMPLEMENTATION_COMPARISON.md) | 与参考项目对比、Phase 完成状态 |
| [hybrid-search-enhancement-plan.md](hybrid-search-enhancement-plan.md) | 混合检索增强规划（可能滞后于代码） |
| [multi-model-enhancement-plan.md](multi-model-enhancement-plan.md) | 多模型增强规划 |
| [multi-model-external-config-zh-CN.md](multi-model-external-config-zh-CN.md) | 外部 `models.json` 配置 |

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
| [quality-defaults-zh-CN.md](quality-defaults-zh-CN.md) | 生产检索默认值与 goldenset 度量 |
| [release-checklist-zh-CN.md](release-checklist-zh-CN.md) | 1.0 发布门禁与产物清单 |
| [china-network-guide-zh-CN.md](china-network-guide-zh-CN.md) | 中国境内 Docker / Maven / npm / Playwright 网络避坑 |
| [troubleshooting-zh-CN.md](troubleshooting-zh-CN.md) | 按症状排障 |
| [DEPLOYMENT.md](DEPLOYMENT.md) | 部署 |
| `docs/prometheus/`、`docs/grafana/` | 监控看板与采集 |

### Agent / 本机协作（渐进式入口）

| 文档 | 体积策略 | 说明 |
|------|----------|------|
| [CLAUDE.md](../CLAUDE.md) | **极短** | Claude Code 本地启动与硬性提示 |
| [AGENTS.md](../AGENTS.md) | **短** | Agent 总入口：原则 + 规则 + 文档地图 |
| [TOOLS.md](../TOOLS.md) | 中 | 构建命令、DB、模型、路径、E2E 命令 |
| [MEMORY.md](../MEMORY.md) | 中长 | 日常开发速查（包结构、API、踩坑） |
| `memory/YYYY-MM-DD.md` | 日志 | 按日工作记录 |
| [SOUL.md](../SOUL.md) / [IDENTITY.md](../IDENTITY.md) / [USER.md](../USER.md) | 角色 | Agent 人格与用户偏好（非产品文档） |
| [HEARTBEAT.md](../HEARTBEAT.md) | 状态日志 | 自动化心跳；**不要**当架构文档读 |
| [skills/project-docs/](../skills/project-docs/SKILL.md) | 技能 | 文档体系建设技能（与 `pm-24x7` 并列；自包含） |

---

## 3. 代码入口（读代码时）

| 路径 | 用途 |
|------|------|
| `spring-ai-rag-api/` | DTO、SPI（`DomainRagExtension` 等） |
| `spring-ai-rag-core/` | 实现 + `SpringAiRagApplication` 可运行入口 |
| `spring-ai-rag-core/.../advisor/` | QueryRewrite → HybridSearch → Rerank |
| `spring-ai-rag-core/.../config/RagProperties.java` | `rag.*` 配置绑定 |
| `spring-ai-rag-core/src/main/resources/application.yml` | 主配置（端口 8081） |
| `spring-ai-rag-core/src/main/resources/db/migration/` | Flyway **V1–V24** |
| `spring-ai-rag-starter/` | 自动配置 `GeneralRagAutoConfiguration` |
| `spring-ai-rag-documents/` | 分块 / 清洗 |
| `spring-ai-rag-webui/` | React 管理台（独立 npm 工程） |
| `demos/` | basic / component / domain / multi-model |
| `scripts/` | `start-server.sh`、`e2e-test.sh`、k6、Playwright |

---

## 4. 常见陷阱（先记这几条）

细节与排障步骤见 [troubleshooting-zh-CN.md](troubleshooting-zh-CN.md)、[TOOLS.md](../TOOLS.md)。

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

- **入口文档保持短**：`CLAUDE.md`、`AGENTS.md` 只放硬性提示 + 链接；细节下沉到本目录或 `TOOLS.md` / `MEMORY.md`。
- **改行为就改文档**：配置项 → `configuration*`；API → `rest-api*`；架构决策 → `architecture*`。**中英文成对存在时必须同步更新。**
- **规划类文档**（`*-plan.md`、`drafts/`）可能滞后于代码，以代码与 `IMPLEMENTATION_COMPARISON.md` 为准。
- **不要把密钥写进文档**；密钥只在 `.env`（已 gitignore）。
