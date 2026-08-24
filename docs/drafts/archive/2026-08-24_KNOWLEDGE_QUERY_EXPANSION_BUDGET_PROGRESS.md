# KNOWLEDGE 多查询扩展预算感知进度

> **状态**：规划检查与生产代码实施已完成，待 Git 交付与归档
>
> **开始日期**：2026-08-24
>
> **当前分支**：`feat/knowledge-query-expansion-budget-20260824`
>
> **当前 worktree**：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> **代码基线**：本地 `main` / `origin/main` @ `b8b61853`
>
> **规划文档**：[2026-08-24_KNOWLEDGE_QUERY_EXPANSION_BUDGET_PLAN.md](2026-08-24_KNOWLEDGE_QUERY_EXPANSION_BUDGET_PLAN.md)

本文是跨会话恢复账本，不替代代码、测试或双语长青文档。每次取得关键进展时，先更新
本文，再执行下一阶段。规划完成并开始实施后，继续在本文记录切片、验证证据和收敛计数；
实施完成后，先把稳定事实提升到双语长青文档，再与规划文档一起归档。

## 1. 当前阶段

| 阶段 | 状态 | 说明 |
|---|---|---|
| 最新 main、worktree 与上一轮归档核对 | 已完成 | 当前分支、`main`、`origin/main` 均基于 `b8b61853`；工作区初始干净；上一轮 plan/progress 已在 `docs/drafts/archive/` |
| 代码与文档探索 | 已完成 | 已核对 Spring AI `MultiQueryExpander`、`RetrievalAugmentationAdvisor`、`ProjectDocumentRetriever`、Chat factory、trace、配置和测试 |
| 候选比较与本轮范围冻结 | 已完成 | 推荐只处理 KNOWLEDGE 多查询扩展的独立检索预算、预执行裁剪、精确去重和低基数诊断 |
| 活动规划编写 | 已完成 | `NEXT_HIGH_VALUE_FEATURES_PLAN.md` 已创建；规划 SHA-256 待三轮检查完成后冻结 |
| 规划连续检查 | `3/3` | 2026-08-24 已完成三轮固定范围只读检查；期间未修改规划正文 |
| 规划门禁、commit、push | 已完成 | 规划提交 `7b2f24ed` 已推送；实施从该提交切出专用分支 |
| 生产代码实施 | 已完成 | 配置、bounded expander、预算隔离、trace、测试、脚本和双语长青文档均已完成 |
| 实现基本集成硬门槛 | 已完成 | 聚焦后端、PostgreSQL、Maven、服务启动、WebUI、Mock Playwright、文档和空白门禁全部通过 |
| 真实 LLM 隔离验收 | 已完成 | `20260824-real-bounded-expansion`：18 passed / 0 failed / 0 skipped；真实 WebUI/provider E2E 与 provider smoke 均通过 |
| 实现连续检查 | `3/3` | 三轮固定范围只读检查连续无实质问题且未修改实现代码 |
| 特性分支合并 main | 未开始 | 先提交并推送特性分支，再同步 `origin/main`、合并复验并合入 `main` |

## 2. 已冻结的关键决策

- 本轮只针对生产 `KNOWLEDGE` Chat 的 Spring AI Modular RAG 查询扩展。
- 新增 `rag.chat.knowledge.max-retrieval-queries`，默认 `3`，范围 `1..5`；
  默认配置继续执行原始 query 加两个变体。
- `query-expander-variants` 保持现有 `1..5` 配置语义，但在创建 expander 前按
  `max-retrieval-queries` 计算 effective variants。
- `includeOriginal=true,maxQueries=1` 不调用 expansion ChatModel，直接检索 transformed
  query 一次。
- 新增项目内部 `BoundedMultiQueryExpander`，按原顺序 trim、丢弃空白、精确去重并保留
  query context/history，不执行任何检索或额外模型调用。
- KNOWLEDGE 使用自身 query budget；AGENT 继续使用 `max-retrieval-calls`，Search、
  Evaluation、旧 Advisor 和 PLAIN 不改变。
- trace 只增加低基数 queryExpansion 摘要，不保存模型输出或原始变体文本。
- 不新增 API request 字段、数据库 migration、权限、用量账本、跨入口统一扩展或
  `EACH_COLLECTION`。

## 3. 探索证据摘要

1. `ModeAwareChatClientFactory` 当前只在 `query-transformer=spring-ai` 时创建
   `MultiQueryExpander`；生产默认保留 original 并生成两个 variant。
2. Spring AI `MultiQueryExpander` 会调用一次 ChatModel，要求精确行数；`includeOriginal`
   只是把 original 插入结果，不是生成预算。
3. `RetrievalAugmentationAdvisor` 为每个扩展 query 异步调用 `DocumentRetriever`，再由
   `ConcatenationDocumentJoiner` 合并去重和排序。
4. `ProjectDocumentRetriever` 每次 query 都先向 `RetrievalTraceCollector` 申请预算；
   当前预算来自 `agent.max-retrieval-calls`，超额 query 只返回空列表。
5. 每个 query 都会进入 `HybridRetrieverService` 的 vector/fulltext 分支，已有 rerank
   candidate limit 不限制跨 query fan-out。
6. `RetrievalTraceCollector.summary()` 已是 Chat response 的 retrieval metadata 入口，
   `RetrievalTraceSession.toMetadata()` 是持久化 trace metadata 入口，适合共享摘要。
7. `Search`、`AGENT`、`Evaluation` 当前不是同一条 Spring AI MultiQueryExpander 路径，
   不应因为本轮配置而被动改变。

## 4. 规划检查记录

检查规则：只检查会影响价值闭环、可实施性、成本安全、兼容性、数据一致性或验收证据的
实质问题。措辞、格式和实施中自然出现的行号漂移不触发重置。无问题轮次不修改本文或
规划正文。

| 轮次 | 时间 | 范围 | 发现 | 处理 | 连续计数 |
|---|---|---|---|---|---:|
| 1 | 2026-08-24 11:25 +0800 | 价值、目标/非目标、默认值、budget 公式、去重、回滚 | 未发现实质问题 | 只读交叉核对现有配置、profile 和 Spring AI expander 契约 | 1 |
| 2 | 2026-08-24 11:31 +0800 | Spring AI advisor、Query context、授权、并发、AGENT 隔离、trace、成本 | 未发现实质问题 | 只读交叉核对 `Query.mutate()`、advisor 并发和 Chat budget/trace 路径 | 2 |
| 3 | 2026-08-24 11:36 +0800 | PostgreSQL、Maven、前端、真实运行、LLM、文档生命周期、Git 交付 | 未发现实质问题 | 只读核对现有验收 runner、端口约定、Playwright 截图禁用和 Git 工作流 | 3 |

三轮检查均未修改 `NEXT_HIGH_VALUE_FEATURES_PLAN.md`；规划正文达到连续 `3/3`。
检查过程中唯一需要修复的实质性文档生命周期问题发生在三轮开始前：中英文
`docs/drafts/README*` 仍声称没有活跃规划，已登记当前 plan/progress；该修复使检查计数
从 `0` 重新开始。

## 5. 实施恢复入口

规划检查达到 `3/3` 后：

1. 运行 `./scripts/verify-project-docs.sh`、`git diff --check`，记录结果和 plan SHA-256；
2. 规划分支已完成提交并推送；实施分支从该规划提交继续，生产代码不回写规划分支；
3. 进入实施时先重新检查 `git status`、`origin/main` 和当前分支；
4. 按 plan §5 的 Slice A -> B -> C -> D 推进，每个关键切片前先更新本文；
5. 代码改动后先跑 plan §6 的基本集成硬门槛，再开始实现 `3/3`；
6. 如发现实质设计错误，立即修改 plan、把规划计数归零，并重新执行规划三轮。

## 6. 当前未决但不阻断事项

- 是否将专项验证作为 `verify-chat-capability.sh` 的一个步骤，还是新建独立 runner：
  推荐优先扩展现有 Chat capability runner，只有资源隔离或运行时间无法接受时才拆出
  `verify-knowledge-query-budget.sh`；无论采用哪种方式，验收矩阵和隔离端口/数据库边界
  不变。
- 真实 provider 使用哪一个已存在模型：实施时读取 `.env` 的可用配置，不把模型名或 key
  写入规划、日志摘要或 Git；这不阻断接口、预算和 Mock 验收设计。

## 7. 实施进度

### 2026-08-24 进行中

- 已从已推送的规划提交 `7b2f24ed` 切出
  `feat/knowledge-query-expansion-budget-20260824`。
- 切分支前工作区干净；未使用 `stash` 或破坏性回退。
- 下一切片：增加 KNOWLEDGE 独立的 `max-retrieval-queries` 配置和预算计算，
  然后实现 bounded expander 与 trace 摘要。
- 已完成 Slice A-C：配置绑定/预算公式、`BoundedMultiQueryExpander`、KNOWLEDGE 与
  AGENT budget 隔离、响应 trace 与持久化 attempt metadata 摘要。
- focused 后端测试首次发现测试导入问题和边界断言问题，均已修复；当前 focused
  测试通过 `20/20`。随后补充空 delegate 输出的 `degraded=true` 语义，需重跑 focused。
- 已完成后端基本硬门槛：`mvn clean compile test-compile` 于
  2026-08-24 12:03 +0800 通过，Reactor 全部模块成功。
- Chat capability 基本验收脚本已完成：`2026-08-24 12:17 +0800`，
  `17 passed / 0 failed / 1 skipped`。通过项包括后端聚焦测试、PostgreSQL 集成、
  `mvn clean compile test-compile`、全量 Maven 测试、演示模块、隔离后端启动、
  WebUI Vitest/TypeScript/生产构建、核心 Mock Playwright、禁悲观锁、项目文档和
  `git diff --check`；唯一跳过项是默认关闭的真实 LLM 隔离 WebUI/provider E2E。
- 全量 Maven 证据：API、Documents、Core、Starter 均 `BUILD SUCCESS`；Core 全量测试
  `2934` 项通过、`7` 项跳过、无失败；全量 Reactor 测试于
  `2026-08-24 12:15 +0800` 完成。
- 真实 LLM 隔离验收已完成：运行
  `CHAT_VERIFY_RUN_ID=20260824-real-bounded-expansion ./scripts/verify-chat-capability.sh --with-real-llm`，
  使用隔离后端端口 `18083`、前端端口 `15175`，真实 WebUI/provider E2E 与 provider
  smoke 均通过；整套结果为 `18 passed / 0 failed / 0 skipped`。
- 实现连续检查已完成 `3/3`：第 1 轮检查配置预算公式、factory 裁剪和 expander
  边界；第 2 轮检查 trace 并发、授权 context、AGENT 隔离、失败回退和 metadata 泄漏；
  第 3 轮检查测试证据、启动脚本、双语长青文档、回滚和 Git 交付边界。三轮均未发现
  影响正确性、成本安全、兼容性或数据一致性的实质问题，期间未修改实现代码。
- 当前下一步：提交并推送特性分支；同步 `origin/main` 后按固定顺序完整复验，再合入
  `main` 并推送，最后归档本轮 plan/progress。

## 8. 规划阶段验证证据

- 文档门禁：`./scripts/verify-project-docs.sh`，2026-08-24 11:36 +0800，10 项全部通过。
- 空白检查：`git diff --check`，2026-08-24 11:36 +0800，通过。
- 规划 SHA-256（冻结于三轮检查后）：
  `NEXT_HIGH_VALUE_FEATURES_PLAN.md` =
  `4f4af0e98eea975d60213ba9b8f3246c226d108004bd3289433f417bb5d5917c`。
- Maven 编译门槛：`mvn clean compile test-compile`，2026-08-24 12:03 +0800，
  Reactor 全部模块 `SUCCESS`。
- WebUI 基础门槛：2026-08-24 12:06 +0800，`npm run typecheck`、`npm run test:run`
  （29 个文件、218 个测试）和 `npm run build` 全部通过。
- 核心 Mock Playwright：2026-08-24 12:07 +0800，`e2e/chat.spec.ts` 与
  `e2e/streaming-upload.spec.ts` 共 14/14 通过；使用 DOM、网络 Mock 和自动化断言，
  未使用截图作为验收证据。
- 后端聚焦测试：2026-08-24 12:07 +0800，`mvn -pl spring-ai-rag-core -am` 聚焦矩阵
  共 231/231 通过。
- PostgreSQL 集成矩阵：2026-08-24 12:09 +0800，`NextHighValueFeaturesPostgresIntegrationTest`
  10/10、`HybridRetrieverRrfPostgresIntegrationTest` 4/4 通过；Testcontainers
  使用 `pgvector/pgvector:pg16`，空库 Flyway 迁移到 V48。
- Chat capability 全量脚本：2026-08-24 12:17 +0800，
  `CHAT_VERIFY_RUN_ID=20260824-verify-bounded-expansion ./scripts/verify-chat-capability.sh`，
  17 项通过、0 项失败、1 项跳过；完整证据见
  `.verification/chat-capability/20260824-verify-bounded-expansion/summary.md`。
- Chat capability 全量真实验收：2026-08-24 12:26 +0800，
  `CHAT_VERIFY_RUN_ID=20260824-real-bounded-expansion ./scripts/verify-chat-capability.sh --with-real-llm`，
  18 项通过、0 项失败、0 项跳过；完整证据见
  `.verification/chat-capability/20260824-real-bounded-expansion/summary.md`。
