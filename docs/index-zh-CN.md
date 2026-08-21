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
| 理解 Chat 记忆、上下文预算、RAG 与工具调用 | [Chat 记忆、RAG 与工具调用](chat-memory-rag-tool-calling-zh-CN.md) | [架构文档](architecture-zh-CN.md)、[配置参考](configuration-zh-CN.md) |
| 改配置项 | [configuration-zh-CN.md](configuration-zh-CN.md) | `spring-ai-rag-core/src/main/resources/application.yml` |
| 对接 / 调试 HTTP API | [rest-api-zh-CN.md](rest-api-zh-CN.md) | Swagger：`/swagger-ui.html` |
| 理解文件管理、PDF 导入和添加到 RAG | [文件管理与 PDF-to-RAG 流程](file-management-and-pdf-rag-zh-CN.md) | [REST API：PDF 与文件产物](rest-api-zh-CN.md#pdf-与文件产物-api) |
| 为外部 client 选择 Collection 检索范围 | [REST API：外部客户端最佳实践](rest-api-zh-CN.md#外部客户端最佳实践) | [后续覆盖模式 TODO](TODO-zh-CN.md#each_collection-召回覆盖模式) |
| 同步外部文档 / 内容源 | [外部文档同步 Client 指南](external-document-sync-client-guide-zh-CN.md) | [REST API 契约](rest-api-zh-CN.md#external-documents-idempotent-synchronization)、[一键生命周期验收](developer-reference-zh-CN.md#document-lifecycle-verification) |
| 查看当前后续改进 / TODO | [TODO-zh-CN.md](TODO-zh-CN.md) | [英文 TODO](TODO.md) |
| 了解外部 API Key / OpenAI 兼容安全边界 | [OpenAI 兼容就绪度与代码库上下文](openai-compatibility-readiness-zh-CN.md) | [配置参考](configuration-zh-CN.md) |
| 接入 OpenAI 兼容预览 | [REST API：OpenAI 兼容预览](rest-api-zh-CN.md#openai-chat-completions-兼容预览) | [OpenAI 兼容就绪度与边界](openai-compatibility-readiness-zh-CN.md) |
| 规划嵌入模型换模 / 向量迁移 | [项目上下文](project-context-zh-CN.md) | [架构文档](architecture-zh-CN.md) |
| 使用 JSONB 结构化记录检索 | [REST API：JSONB Payload 检索](rest-api-zh-CN.md#json-结构化记录jsonb-payload-检索) | [外部文档同步 Client 指南](external-document-sync-client-guide-zh-CN.md) |
| 运行持久化嵌入任务 / 质量回归 | [开发者参考](developer-reference-zh-CN.md) | [测试指南](testing-guide-zh-CN.md) |
| 治理或修改 WebUI 水平对齐 | [WebUI 水平对齐指南](webui-alignment-guidelines-zh-CN.md) | [测试指南](testing-guide-zh-CN.md) |
| 做领域定制 | [extension-guide-zh-CN.md](extension-guide-zh-CN.md) | `demos/demo-domain-extension` |
| 写测试 / 跑回归 | [testing-guide-zh-CN.md](testing-guide-zh-CN.md) | [developer-reference-zh-CN.md](developer-reference-zh-CN.md) E2E 段 |
| 规划、实施、验收并交付复杂功能 | [规划、实施与验收工作流](delivery-workflow-zh-CN.md) | [当前活跃规划](drafts/README-zh-CN.md)、[测试指南](testing-guide-zh-CN.md) |
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
| [architecture-zh-CN.md](architecture-zh-CN.md) | 模块、运行时模型路由、三种 Chat 模式、双表记忆、领域扩展 |
| [chat-memory-rag-tool-calling-zh-CN.md](chat-memory-rag-tool-calling-zh-CN.md) / [English](chat-memory-rag-tool-calling.md) | Spring AI Chat Memory、Modular RAG、Tool Calling 的当前边界、缺口与演进方向 |
| [project-context-zh-CN.md](project-context-zh-CN.md) | 稳定模块、运行行为、安全边界与 1.0 基线 |
| [file-management-and-pdf-rag-zh-CN.md](file-management-and-pdf-rag-zh-CN.md) | 文件管理与文档管理的区别、PDF 转换产物、添加到 RAG 和当前生命周期边界 |
| [rest-api-zh-CN.md](rest-api-zh-CN.md#external-documents-idempotent-synchronization) § 外部文档：幂等同步 | 外部客户的 upsert、CAS、删除恢复和同步最佳实践 |
| [external-document-sync-client-guide-zh-CN.md](external-document-sync-client-guide-zh-CN.md) | 外部 client 的三元身份、增量 CRUD、重试/checkpoint 与可检索就绪指引 |
| [TODO-zh-CN.md](TODO-zh-CN.md) / [TODO.md](TODO.md) | 当前未纳入 API 的后续改进与触发条件 |
| [extension-guide-zh-CN.md](extension-guide-zh-CN.md) | `DomainRagExtension` 扩展开发 |
| [IMPLEMENTATION_COMPARISON.md](IMPLEMENTATION_COMPARISON.md) | 与参考项目对比、Phase 完成状态 |
| [hybrid-search-enhancement-plan.md](hybrid-search-enhancement-plan.md) | 混合检索增强规划（可能滞后于代码） |
| [multi-model-enhancement-plan.md](multi-model-enhancement-plan.md) | 多模型增强规划 |
| [multi-model-external-config-zh-CN.md](multi-model-external-config-zh-CN.md) | 外部 `models.json` 配置 |
| [OpenAI 兼容就绪度与代码库上下文](openai-compatibility-readiness-zh-CN.md) | 默认关闭的受控预览、请求级 Collection scope、当前兼容子集与公网安全缺口 |
| [WebUI 水平对齐指南](webui-alignment-guidelines-zh-CN.md) / [English](webui-alignment-guidelines.md) | WebUI 普通内容、合理居中例外、`alignment-policy` 门禁和验证命令 |
| [当前活跃规划](drafts/README-zh-CN.md) | 只列仍在准备或实施的方案；实施事实仍以代码与长青文档为准 |
| [历史规划与实施记录归档](drafts/archive/README-zh-CN.md) | 仅用于追溯设计与验证证据，不是 Agent 默认阅读入口 |

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
| [delivery-workflow-zh-CN.md](delivery-workflow-zh-CN.md) / [English](delivery-workflow.md) | 长青 | 自包含规划、进度恢复、验收硬门槛、三轮收敛与 Git 交付 |
| [.agents/skills/project-docs/](../.agents/skills/project-docs/SKILL.md) | Skill | 文档体系工作流 |
| [.agents/skills/pm-24x7/](../.agents/skills/pm-24x7/SKILL.md) | Skill | 可选的 OpenClaw 7×24 项目经理工作流 |

OpenClaw 的 `TOOLS.md`、`MEMORY.md`、`memory/`、`HEARTBEAT.md` 等本地状态文件有意保持 gitignore，不属于项目文档体系；本地状态可以链接这些长青文档。
---

## 3. 代码入口（读代码时）

| 路径 | 用途 |
|------|------|
| `spring-ai-rag-api/` | DTO、SPI（`DomainRagExtension` 等） |
| `spring-ai-rag-core/` | 实现 + `SpringAiRagApplication` 可运行入口 |
| `spring-ai-rag-core/.../chat/`、`.../rag/` | 模式化 Chat 执行、Modular RAG 与 Tool Calling |
| `spring-ai-rag-core/.../config/RagProperties.java` | `rag.*` 配置绑定 |
| `spring-ai-rag-core/src/main/resources/application.yml` | 主配置（端口 8081） |
| `spring-ai-rag-core/src/main/resources/db/migration/` | Flyway **V1–V45** |
| `spring-ai-rag-starter/` | 自动配置 `GeneralRagAutoConfiguration` |
| `spring-ai-rag-documents/` | 分块 / 清洗 |
| `spring-ai-rag-webui/` | React 管理台（独立 npm 工程） |
| `demos/` | basic / component / domain / multi-model |
| `scripts/` | 启动、E2E、OpenAI/jobs/JSONB、文档迁移、派生完整性、检索诊断/过滤、embedding 运营、受管质量、goldenset 与质量回归 |

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
- **规划类文档**（`*-plan.md`、`drafts/`）可能滞后于代码；以当前代码和长青 reference/guide
  为准，`IMPLEMENTATION_COMPARISON.md` 仅作为补充状态与历史对照。
- **不要把密钥写进文档**；密钥只在 `.env`（已 gitignore）。
