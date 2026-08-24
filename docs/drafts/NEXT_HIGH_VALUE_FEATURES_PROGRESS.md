# KNOWLEDGE 多查询扩展预算感知进度

> **状态**：规划编写完成，规划检查进行中；尚未开始生产代码实施
>
> **开始日期**：2026-08-24
>
> **当前分支**：`docs/next-high-value-features-plan-20260824`
>
> **当前 worktree**：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> **代码基线**：本地 `main` / `origin/main` @ `b8b61853`
>
> **规划文档**：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)

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
| 规划门禁、commit、push | 进行中 | 文档/差异门禁已通过；待完成 commit、fetch/merge 与 push |
| 生产代码实施 | 未开始 | 规划提交不包含生产代码 |
| 实现基本集成硬门槛 | 未开始 | 仅在后续进入实施阶段后执行 |
| 实现连续检查 | `0/3` | 仅在基本集成硬门槛全部通过后开始 |
| 特性分支合并 main | 未开始 | 仅适用于后续代码实施交付 |

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
2. 本地 commit，`git fetch origin` 后按规则 merge 上游，再 push 规划分支；
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

## 7. 规划阶段验证证据

- 文档门禁：`./scripts/verify-project-docs.sh`，2026-08-24 11:36 +0800，10 项全部通过。
- 空白检查：`git diff --check`，2026-08-24 11:36 +0800，通过。
- 规划 SHA-256（冻结于三轮检查后）：
  `NEXT_HIGH_VALUE_FEATURES_PLAN.md` =
  `4f4af0e98eea975d60213ba9b8f3246c226d108004bd3289433f417bb5d5917c`。
