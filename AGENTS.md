# AGENTS.md — Agent 协作入口

> 给 **AI Agent / 自动化助手** 的项目入口。  
> **原则：短入口 + 按需下钻**。细节不写在这里，写在链接文档里。  
> 人类开发者也可当「从哪读文档」的地图用。

**项目**：spring-ai-rag — 基于 Spring AI 的通用 RAG 框架（模型无关 · 领域解耦 · 组件化）  
**默认端口**：`8081` · **主 profile**：`postgresql` · **向量维度**：`1024`（BGE-M3）

---

## 1. 你是谁、先做什么

1. 读本文件的 **硬性规则** 与 **文档地图**。  
2. 需要命令 / 环境细节 → [developer-reference-zh-CN.md](docs/developer-reference-zh-CN.md)。
3. 需要稳定项目认知 → [project-context-zh-CN.md](docs/project-context-zh-CN.md)。
4. 需要完整导航 → [docs/index-zh-CN.md](docs/index-zh-CN.md)（[EN](docs/index.md)）。  
5. Claude Code 本地极短提示 → [CLAUDE.md](CLAUDE.md)。

改代码前确认：影响的是 **api / core / starter / documents / webui / demos** 哪一层；配置改动同步文档与测试。

---

## 2. 硬性规则（违反易导致联调失败）

1. **OpenAI / Embedding 的 `base-url` 不要带 `/v1`**  
   Spring AI 会再拼 `/v1/...`，带了会变成 `/v1/v1/...` → 401/404。见 [developer-reference-zh-CN.md](docs/developer-reference-zh-CN.md)。
2. **本地开发 profile 用 `postgresql`**（`SPRING_PROFILES_ACTIVE=postgresql`），不要默认用会连不上的配置。
3. **服务默认端口 8081**；一键开发栈 `scripts/dev.sh` 默认后端端口 **18082**；真实 LLM 联调常用 **18081**。
4. **Embedding Profile 与固定向量列**：当前活动 Profile 为 1024 维，写入 `embedding_1024 VECTOR(1024)`；换模型必须创建新 Profile 并重嵌入，不能只改 dimensions。
5. **写代码同步写测试**；`mvn test` 不过不算完成。Mock Playwright ≠ 真实 LLM 联调。
6. **WebUI** 是独立前端：改 `spring-ai-rag-webui/` 后需构建；静态资源路径见 [developer-reference-zh-CN.md](docs/developer-reference-zh-CN.md) / [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)。
7. **Flyway 迁移** 在 `spring-ai-rag-core/src/main/resources/db/migration/`，当前 **V1–V49**；改 schema 必须加迁移，不要只改实体或改写已执行迁移。
8. **真实 LLM 联调**用 `scripts/start-real-e2e-server.sh` + `scripts/real-llm-e2e-smoke.sh`（默认 18081，避免与日常 8081 冲突）；细节见 [developer-reference-zh-CN.md](docs/developer-reference-zh-CN.md)、[docs/testing-guide-zh-CN.md](docs/testing-guide-zh-CN.md)。
9. **密钥与隐私**：API Key、Token **不得**写入文档或提交到 git；只放 `.env`（已 gitignore）。
10. **注释与文档语言**：代码注释、Javadoc、用户可见说明优先**中文**（项目约定）；对外英文文档与中文成对维护时需同步。
11. **境内 Docker / 构建网络**：拉镜像或构建超时先看 [docs/china-network-guide-zh-CN.md](docs/china-network-guide-zh-CN.md)，优先 `scripts/docker-build-local.sh`；不要把区域镜像硬编码进 Dockerfile。
12. **1.0 发版 / 验产物**：按 [docs/release-checklist-zh-CN.md](docs/release-checklist-zh-CN.md) 与 `scripts/verify-release.sh`；检索默认与 goldenset 见 [docs/quality-defaults-zh-CN.md](docs/quality-defaults-zh-CN.md)。
13. **WebUI 对齐**：普通页面内容默认 `text-align: start`；上传投放区等明确空间语义才允许居中，新增居中必须通过 `npm run check:alignment`。见 [WebUI 水平对齐指南](docs/webui-alignment-guidelines-zh-CN.md)。
14. **数据访问并发**：禁止显式悲观锁、`SKIP LOCKED` 和 PostgreSQL advisory lock；使用条件写入/CAS、版本号、唯一约束、lease 与有界重试。运行 `scripts/verify-no-pessimistic-locks.sh`。
15. **大块特性交付**：在专用 worktree/分支开发，持续合并已推送的 `origin/main`；合并后按固定顺序完整复验，最终把特性分支合回并推送 `main`。见 [delivery-workflow-zh-CN.md](docs/delivery-workflow-zh-CN.md) §8。
16. **复杂功能交付**：修改代码前完成自包含规划和规划 `3/3`；实现后先过基本集成硬门槛，再做实现 `3/3`。测试证据不能由 review 或用户首次手测替代。见 [规划、实施与验收工作流](docs/delivery-workflow-zh-CN.md)。
17. **外部 Client 边界**：把外部项目需求改写为本仓自包含的通用能力、契约和测试；跟踪代码与文档不得依赖外部项目名称、私有协议或背景知识。见 [delivery-workflow-zh-CN.md](docs/delivery-workflow-zh-CN.md) §2。

更全的陷阱列表：[docs/index-zh-CN.md](docs/index-zh-CN.md) §4、[docs/troubleshooting-zh-CN.md](docs/troubleshooting-zh-CN.md)。

---

## 3. 任务地图

| 任务 | 入口 |
|------|------|
| 完整文档导航 | [docs/index-zh-CN.md](docs/index-zh-CN.md)（[EN](docs/index.md)） |
| 项目概览 / 从零跑通 | [README-zh-CN.md](README-zh-CN.md) → [getting-started-zh-CN.md](docs/getting-started-zh-CN.md) |
| 构建、启动、DB、模型、E2E | [developer-reference-zh-CN.md](docs/developer-reference-zh-CN.md) |
| 包结构、稳定能力、关键边界 | [project-context-zh-CN.md](docs/project-context-zh-CN.md) |
| 架构 / 能力状态 / 领域扩展 | [architecture-zh-CN.md](docs/architecture-zh-CN.md) · [IMPLEMENTATION_COMPARISON.md](docs/IMPLEMENTATION_COMPARISON.md) · [extension-guide-zh-CN.md](docs/extension-guide-zh-CN.md) |
| 配置 / HTTP API / SSE | [configuration-zh-CN.md](docs/configuration-zh-CN.md) · [rest-api-zh-CN.md](docs/rest-api-zh-CN.md) · [SSE-PROTOCOL.md](docs/SSE-PROTOCOL.md) |
| 文件管理 / PDF 导入 / 添加到 RAG | [file-management-and-pdf-rag-zh-CN.md](docs/file-management-and-pdf-rag-zh-CN.md)（[EN](docs/file-management-and-pdf-rag.md)） |
| 外部文档同步 / 重索引 | [rest-api-zh-CN.md](docs/rest-api-zh-CN.md#external-documents-idempotent-synchronization) § 外部文档：幂等同步 · [project-context-zh-CN.md](docs/project-context-zh-CN.md#external-document-synchronization) |
| 后续改进 TODO | [TODO-zh-CN.md](docs/TODO-zh-CN.md)（[EN](docs/TODO.md)） |
| 多模型与外部 `models.json` | [multi-model-external-config-zh-CN.md](docs/multi-model-external-config-zh-CN.md) |
| 测试 / 质量默认 / 发版 | [testing-guide-zh-CN.md](docs/testing-guide-zh-CN.md) · [quality-defaults-zh-CN.md](docs/quality-defaults-zh-CN.md) · [release-checklist-zh-CN.md](docs/release-checklist-zh-CN.md) |
| 规划 / 实施 / 验收 / Git 交付 | [delivery-workflow-zh-CN.md](docs/delivery-workflow-zh-CN.md)（[EN](docs/delivery-workflow.md)） |
| 部署 / 境内网络 / 排障 | [DEPLOYMENT.md](docs/DEPLOYMENT.md) · [china-network-guide-zh-CN.md](docs/china-network-guide-zh-CN.md) · [troubleshooting-zh-CN.md](docs/troubleshooting-zh-CN.md) |
| Claude Code / grok 代理 | [CLAUDE.md](CLAUDE.md) · [claude-grok-proxy-zh-CN.md](docs/claude-grok-proxy-zh-CN.md) |
| 文档治理 | [.agents/skills/project-docs/SKILL.md](.agents/skills/project-docs/SKILL.md) |

代码根目录为 `spring-ai-rag-{api,core,starter,documents}/`、`spring-ai-rag-webui/`、`demos/`；脚本入口统一查 [developer-reference-zh-CN.md](docs/developer-reference-zh-CN.md)。
多数正式文档有去掉 `-zh-CN` 的英文对应文件，修改时必须成对更新。`docs/drafts/` 与 `*-plan.md` 可能滞后于代码。
`docs/drafts/` 只放当前活跃规划；`docs/drafts/archive/` 仅供历史追溯，不是默认事实入口。

---

## 4. 协作约定

- **先链后写**：能链到 `docs/` 的，不要在 `AGENTS.md` / `CLAUDE.md` 展开长文。
- **改行为就改文档**：配置 → `configuration*`；API → `rest-api*`；架构 → `architecture*`；命令 → `developer-reference*`。
- **测试门禁**：功能完成 = 实现 + 测试 +（相关）文档；禁止「只改实现、测试红着交」。  
- **交付顺序**：规划收敛 → 实施与进度记录 → 基本集成硬门槛 → 限定范围实现收敛 → Git 交付；细节只维护在 `delivery-workflow*`。
- **不要**把 `HEARTBEAT.md`、`memory/日期.md` 当架构真相来源；以代码与正式 `docs/` 为准。  
- OpenClaw 的 `TOOLS.md`、`MEMORY.md`、`memory/` 等是本地状态，不属于项目文档体系；项目级 Skills 位于 `.agents/skills/`。
- 外部参考仓库路径见 [docs/index-zh-CN.md](docs/index-zh-CN.md) §5（本机对照用，非 submodule）。

---

## 5. 维护本文件

- 仅在 **规则变更**、**文档地图过时**、**模块/端口/迁移版本号变化** 时更新。  
- 保持 **短**：新增内容优先写到专题文档，再在本文件加一行链接。  
- 与 [CLAUDE.md](CLAUDE.md)、[docs/index-zh-CN.md](docs/index-zh-CN.md) 交叉链接保持一致。
