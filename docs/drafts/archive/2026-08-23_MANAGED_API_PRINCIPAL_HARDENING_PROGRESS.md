# 下一轮高价值功能规划进度

> **状态**：实施中，基本门槛、双实例与真实 LLM 合同已通过，正在执行统一最终门槛
>
> **当前分支**：`feat/managed-api-principal-hardening-20260823`
>
> **当前 worktree**：`/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-managed-api-principals`
>
> **规划代码基线**：`main` / `origin/main` @ `05a21706`
>
> **实施基线**：`origin/main` @ `c62d50fe`
>
> **实施规划**：[2026-08-23_MANAGED_API_PRINCIPAL_HARDENING_PLAN.md](2026-08-23_MANAGED_API_PRINCIPAL_HARDENING_PLAN.md)

本文件是跨会话恢复账本，不替代代码与双语长青文档。每次取得关键进展时先更新本文件，
再进入下一阶段。

## 1. 当前阶段

| 阶段 | 状态 | 说明 |
|---|---|---|
| main 规划基线 | 已完成 | 按用户约定直接在最新本地 `main @ 05a21706` 的专用 main worktree 规划；实施阶段再建立隔离特性 worktree |
| 上一轮规划归档 | 已完成 | Chat turn 幂等 plan/progress 已归档为 `2026-08-22_*`，长期事实已在上一轮交付时同步至长青文档 |
| 代码与文档探索 | 已完成 | 已交叉核对 API Key、认证/ACL、principal owner、限流、迁移、两种装配拓扑、WebUI 与现有测试 |
| 功能筛选与方案冻结 | 已完成 | 本轮冻结为稳定受管 principal、版本化凭据、即时跨实例撤销与 PostgreSQL 共享配额 |
| 自包含规划编写 | 已完成 | 已冻结 V48、认证/ACL、管理 API、CAS、共享 quota、WebUI、rollout 与一次性验收矩阵 |
| 双语长青文档同步 | 已完成 | TODO/readiness 中英文已同步当前缺口、优先级与未实施边界 |
| 规划连续审查 | 已完成 | 最终连续 `3/3` 无修改审查已通过，文档门槛 10/10 |
| 规划 Git 交付 | 已完成 | 规划与上一轮归档已在 `main @ c62d50fe` 推送，main 工作区干净 |
| 隔离实施基线 | 已完成 | 从最新 `origin/main @ c62d50fe` 创建专用 feature branch/worktree |
| 验收矩阵与测试骨架 | 已完成 | 新增 focused PostgreSQL 迁移/lifecycle/concurrency/quota 验收；HTTP、双实例和真实 LLM 入口已由规划冻结，专项脚本随全栈阶段收口 |
| V48 与持久化 | 已完成 | principal、credential version、raw-null guard、quota bucket、ADMIN guard 已落地；V47 fixture 升级已通过 |
| 认证与 ACL 迁移 | 已完成 | immutable principal/policy、每请求权威认证、bounded last-used touch、稳定 owner 与 worker reload 已迁移并编译通过 |
| 管理 API 与共享 quota | 已完成 | lifecycle/policy CAS、PostgreSQL quota、ADMIN guard、有界 cleanup、fail-closed 与固定标签 metrics 均已实现并通过 focused 测试 |
| WebUI | 已完成 | 每 principal 一行、policy CAS、quota、当前凭据 rotate/revoke 与 shown-once secret 已完成；页面 Vitest 13/13、TypeScript、production build、alignment、核心 Mock Playwright 通过 |
| 基本集成硬门槛 | 已完成 | 统一门槛 `20260823-premerge-hard-gate-rerun` 13/13：PostgreSQL 40/40、Maven 全量、WebUI Vitest 218/218、TypeScript/build/alignment、核心 Mock Playwright、project-docs 10/10 均通过 |
| 真实全栈与 LLM | 已完成 | 同一统一门槛中，双实例共享 DB、真实 frontend/backend Playwright、真实 `grok-4.5` 五路径有界调用全部通过 |
| 实现连续审查 | 已完成 | feature 与合入后的 `main` 精确树均完成连续 `3/3` 限定范围无修改审查；最终进度提交后再执行一次连续 `3/3` 只读确认 |
| 最终 Git 交付 | 已完成 | feature 已推送并快进合入 `main`；完整门槛 13/13、隔离 `dev.sh`、最终审查均通过，已将 `main@d4195a48` 推送到 `origin/main` |

## 2. 已确认事实

- 原始工作区仍在 `docs/chat-context-tool-orchestration-plan-20260821` 且干净，本轮不在该
  老分支叠加修改；规划工作在独立的 main worktree 进行。
- 最新本地 `main` 与规划开始时的 `origin/main` 均为 `05a21706`。
- 上一轮 Chat turn 幂等能力已合并、完整验收并推送；本轮不得重复规划已交付能力。
- 规划已完成并获准实施；生产代码只在当前隔离 feature worktree 修改，main 工作区保持为
  交付入口。
- 当前 `RagApiKey` 同时承载 credential、role、Collection ACL 与 expiry；rotation 禁用旧
  `keyId` 后创建新的独立 Key，且 legacy rotation 会把 role 重置为 `NORMAL`。
- Chat history/memory/turn operation、evaluation suite/run、retrieval diagnostics 和多项持久化
  operation 使用 `db:{keyId}` 作为 owner。轮换改变 `keyId`，会切断同一调用方的 owner
  namespace；对已经发生的历史轮换不存在可靠的自动 family 推断方法。
- 认证存在 30 秒进程内正向缓存，吊销只清理当前 JVM；`last_used_at` 每次认证同步写库。
  当前限流是本进程 `ConcurrentHashMap`，多副本会放大 quota，legacy fallback 还可能把
  raw header 当 limiter identifier。
- V23 仍允许 `rag_api_key.api_key` 明文列和索引，尽管当前 service 不写该字段。
- `ApiKeyCollectionAccess`、Controller、异步 evaluation worker 与 Chat replay 授权广泛依赖
  `RagApiKey` 实体；实施必须一次性迁移到不可变认证 principal/policy snapshot，不能只改
  Chat principal 字符串。
- 两种运行拓扑必须共同覆盖：standalone core 负责认证装配，starter 负责限流装配；当前
  root-mode Web 集成测试仍以 mocked management service 为主，缺少真实 PostgreSQL、双实例
  撤销与共享 quota 证据。

## 3. 功能选择结论

### 3.1 本轮选中

**稳定受管 API principal 与多实例配额加固**：把长期 owner/policy 从可轮换 credential
中分离；引入版本化 credential family、无正向认证缓存的即时吊销、低写放大的使用时间、
PostgreSQL 原子共享 quota，以及与之配套的管理 API、WebUI 和跨实例验收。

选择理由：这是外部 Client 在生产环境长期调用 RAG 服务时的身份连续性、数据隔离和成本
安全基础，也直接修复现有 readiness 文档已明确列出的公开/多实例启用前置缺口。它复用
项目现有 PostgreSQL、条件 DML/CAS、唯一约束和 root-managed Key 边界，不引入 Redis、
OAuth 或新的身份体系。

### 3.2 本轮不选

- **Collection 级多 embedding profile 路由**：价值高，但当前写入、检索、job、readiness
  和完整性诊断都读取单一 active profile；实现还需模型 factory、向量空间分组检索、
  Collection profile 迁移和重嵌入协议，应独立规划。
- **`EACH_COLLECTION`**：正式 TODO 已冻结为无目标版本的非紧急 backlog，缺少明确覆盖率
  产品需求与质量证据。
- **继续扩展 Chat 编排**：会话摘要、工具预算、durable turn 和真实 provider 验证均已
  在前两轮交付；当前外部生产安全收益低于 principal/credential 基础加固。

## 4. 当前实施顺序

1. 一次性建立 V48 migration/backfill、credential lifecycle、并发、quota、HTTP 与双实例
   验收骨架；之后不在 review 阶段零碎补测试。
2. 实施 V48 和 principal/credential repository，再迁移认证 snapshot 与所有数据面 ACL。
3. 实施 management lifecycle、PostgreSQL quota 与两种装配拓扑。
4. 完成 WebUI 与中英文行为文档，执行基础集成硬门槛。
5. 执行隔离端口双实例、真实全栈 Playwright 和有界真实 LLM 验收。
6. merge 最新 `origin/main` 后按固定顺序完整复验，再进入实现连续 `3/3` 审查和 Git 交付。

## 5. 验收矩阵状态

| 门槛 | 状态 | 证据 |
|---|---|---|
| V1–V48 migration/backfill/raw-null PostgreSQL | 通过 | PostgreSQL 完整矩阵 40/40；`ManagedApiPrincipalPostgresIntegrationTest` 8/8，V47 明文 fixture 升级并由 schema 拒绝回写 |
| credential create/rotate/revoke/policy concurrency | 通过 | 同一 PostgreSQL suite：create/auth/rotate/revoke、并发单赢家、policy CAS、最后 ADMIN 并发保护 |
| owner continuity 与全数据面 ACL | 通过 | PostgreSQL stable principal、worker/ACL 编译迁移，以及双实例 HTTP replay、rotation 后 history continuity 与 revoke 授权均已验证 |
| 双实例即时撤销与 shared quota | 通过 | 线程并发 quota `20/50`、rotation 不重置、cleanup batch `2/5`；双进程 HTTP 精确 `6/12` 全局放行并跨实例即时 rotate/revoke，store 故障 fail-closed `503` |
| `mvn clean compile test-compile` / full Maven | 通过 | 统一门槛 `20260823-premerge-hard-gate-rerun` 重新执行 compile/test-compile 与全量 Maven，失败/错误 0 |
| WebUI Vitest/TypeScript/build/alignment | 通过 | 全量 Vitest 29 files / 218 tests、TypeScript、production build、alignment 均通过 |
| 核心 Mock Playwright（无截图） | 通过 | `api-key-mvp.spec.ts` 1/1；DOM、管理请求、credential version、secret 无持久化断言通过 |
| 真实 frontend/backend Playwright（无截图） | 通过 | 双 backend + Vite + 真实 PostgreSQL；DOM、网络/响应、identity、secret 不进入 URL/console/storage 断言 1/1 |
| 真实 LLM native JSON/SSE、OpenAI JSON/SSE、rotation/revoke | 通过 | `20260823-real-llm-probe-5`：native JSON 首次/跨实例 replay、轮换后 history 与 JSON、native SSE、OpenAI JSON/SSE；A/B counter 汇总恰好 `+5`，replay `+0`，最终跨实例 revoke 为 401 |
| merge 后完整复验 | 通过 | `20260823-post-origin-main-final` 13/13；PostgreSQL/Maven/前端/真实双实例/真实 WebUI/真实 LLM 全部门槛在 `origin/main@c62d50fe` 基线上重新执行并通过 |
| 合入 main 后完整复验 | 通过 | `20260823-main-merged-final` 13/13；另以 backend `18191`、frontend `15191` 和一次性 PostgreSQL 执行 `dev.sh`，readiness、Vite/HMR、代理 root identity 与管理写入自检全部通过 |
| 实现连续三轮审查 | `0/3` | 基本集成硬门槛通过前不开始 |

## 6. 实施问题与修复记录

| 时间 | 门槛 | 发现 | 处理 | 结果 |
|---|---|---|---|---|
| 2026-08-23 13:14 CST | PostgreSQL 完整集成矩阵 | 两个历史 Chat 集成测试仍把 Flyway 最新版本断言为 V47；新增主体与配额测试本身通过 | 一次性搜索活跃测试与事实文档，将当前迁移边界同步为 V48；归档文档保留历史值 | 重跑四个集成类共 40 项，失败/错误/跳过均为 0 |
| 2026-08-23 13:20 CST | Maven 全量测试 | 2876 项中 0 个断言失败、34 个上下文错误：共享 quota 装配无条件要求 JDBC；关闭 JPA 的 OpenAPI 合同测试缺 principal repository；root-mode 测试仍 mock 旧实体认证入口 | PostgreSQL store/maintenance 改为 JDBC 条件装配，PostgreSQL backend 缺 store 时启动失败；补齐 OpenAPI mock 并迁移 Web 测试到不可变 principal snapshot | 聚焦装配、合同与 root-mode 链路 39/39 通过；全量门槛从头重跑 |
| 2026-08-23 13:44 CST | 双实例真实服务启动 | V48 迁移完成，但普通配置类上的 `@ConditionalOnBean(JdbcTemplate.class)` 在自动配置时序中未注册 quota store，PostgreSQL backend 按设计 fail-closed 拒绝启动 | 改用 `rag.rate-limit.backend=postgresql` 条件注册 store/maintenance，并新增有/无 JdbcTemplate 的 ApplicationContextRunner 启动测试 | focused 配置测试通过；两个真实 Spring Boot 实例随后均成功启动并共享同一 V48 数据库 |
| 2026-08-23 13:48 CST | 真实 WebUI Playwright | 隔离 Vite origin 未加入后端 CORS allowlist，首个 JSON POST 被 403；修复后，首版 principal/credential 兼容共用 ID 又令纯文本 locator 违反 strict mode | 验收服务显式复用 `dev.sh` 的 CORS 配置方式；按唯一 principal 名称定位行并用语义化 `<code>` 断言当前 credential | 无截图真实 WebUI Playwright 1/1，通过 create/policy CAS/rotate/revoke/identity 与 secret 非持久化断言 |
| 2026-08-23 14:00 CST | 真实 LLM 验收脚本 | provider meter 在 Actuator 中可能使用带/不带 `.total` 的名称；长 run ID 违反 sessionId 36 字符上限；双实例 counter 原为只读 A，遗漏 B 上的两次调用 | 复用既有 Chat smoke 的双 metric 名读取；改用 28 字符随机 session；分别读取 A/B 并求和；Docker 动态端口发现增加有界重试 | `20260823-real-llm-probe-5` 全合同通过：五次真实 provider 调用、一次 replay 零增量、轮换连续性与最终撤销均有证据 |
| 2026-08-23 14:13 CST | 统一完整门槛 | 前 12 项全部通过；真实全栈阶段的一次性 PostgreSQL 在就绪轮询中出现不可复现的 Docker 启动瞬态，原脚本仅报告未就绪，缺少容器状态和日志 | 将就绪轮询扩展为 120 秒的有界状态检查；容器提前退出或超时时输出状态与启动日志，便于把基础设施失败与业务失败分离 | 同镜像手工探针启动并通过 `pg_isready`；清理探针后从第 1 项重跑完整 13 项门槛 |
| 2026-08-23 14:22 CST | 统一完整门槛复跑 | 无业务失败 | 无需修复 | `20260823-premerge-hard-gate-rerun` 13/13；双实例 shared quota 精确 `6/12`，真实 WebUI 1/1，真实 LLM provider 总调用 `5`、replay 增量 `0`、rotation continuity 与 revoke 通过 |

## 7. 规划审查记录

| 时间 | 范围 | 发现 | 处理 | 连续无修改计数 |
|---|---|---|---|---|
| 2026-08-23 12:02 CST | 事实、范围与代码交叉验证预检 | 重复 revoke 的“current”判定未冻结；`RagApiKey` 引用审计范围过宽 | 固定以 `nextCredentialVersion - 1` 判定最后一版；允许实体用于 credential persistence/management，但禁止进入数据面 ACL/request context | 重置为 `0/3` |
| 2026-08-23 12:05 CST | 状态元数据收尾 | 正文已通过首个 `3/3`，但 header/table 仍写“审查中/未开始” | 只更新最终状态；按最严格规则重置，随后重新执行三轮固定范围只读审查 | 重置为 `0/3` |

连续无问题轮次只在执行输出中留存总结，不修改本文件，避免破坏“连续三轮无修改”的终止
条件。若后续发现实质问题并修改，则在这里追加问题轮并重新从 `0/3` 开始。
