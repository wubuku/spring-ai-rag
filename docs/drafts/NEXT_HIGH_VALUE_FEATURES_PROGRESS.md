# 有界的独立 rerank 候选池进度

> **状态**：规划编写中，生产实现尚未开始
>
> **开始日期**：2026-08-23
>
> **当前分支**：`codex/weighted-rrf-retrieval-20260823`
>
> **当前 worktree**：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> **代码基线**：本地 `main` / `origin/main` @ `802cd991`
>
> **实施规划**：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)

本文件是跨会话恢复账本，不替代代码、迁移或双语长青文档。每次取得关键进展时，先更新
本文件，再进入下一阶段。规划完成后本文件记录规划审查结果；实施开始后继续记录代码切片、
验证证据、审查计数和恢复入口。

## 1. 当前阶段

| 阶段 | 状态 | 说明 |
|---|---|---|
| 代码、长青文档和上一轮归档探索 | 已完成 | 已核对当前加权 RRF 基线、rerank provider、所有检索调用链和文档生命周期 |
| 上一轮 plan/progress 归档 | 已完成 | 已归档为 `2026-08-23_WEIGHTED_RRF_RETRIEVAL_*`，并修正归档后的相对链接 |
| 新规划编写 | 已完成 | 已冻结有界 candidate pool、最终数量语义和 Agent/Evaluation 漏接修复边界 |
| 规划连续审查 | `3/3` | 已完成三轮固定范围、只读复核；期间未再修改规划正文。最终规划 SHA-256 记录于本文件 §6 |
| 文档门禁、commit、push | 进行中 | 规划 `3/3` 已达成，正在执行最终文档门禁、全量变更核对、commit、fetch/merge 检查和 push |
| 生产代码实施 | 未开始 | 规划交付后等待下一阶段指示 |

## 2. 已冻结的关键决策

- 本轮只有一个功能主题：有界的独立 rerank 候选池。
- `candidateLimit = max(requestedMaxResults, configuredCandidateLimit)`。
- 配置默认 `20`，setter/绑定边界为 `1..100`；推荐环境变量为
  `RAG_RERANK_CANDIDATE_LIMIT`。
- 只有请求启用 rerank、全局启用 rerank 且 provider 不是 `none`/`noop`/`off` 时扩大；
  纯向量/全文不可用时只扩大仍可用的向量分支，不额外强行启用全文。
- hybrid 分支延续现有每通道 `fusionLimit * 2` 召回比例，RRF 融合输出最多 `fusionLimit`。
- `maxResults` 仍是最终 Search/Chat/tool/record/evaluation 对外数量，不是候选池数量。
- `KnowledgeSearchTool` 和 `EvaluationCaseExecutor` 需要接入现有 `ReRankingService`，避免
  只扩大候选而不完成最终 rerank。
- 不改数据库、公开请求字段、权限、用量账本、WebUI 业务流程和新的 rerank provider。
- 旧版 GET Search 继续支持 `limit=1..1000`，因其显式 `useRerank=false`，不扩大候选池；
  candidate-limit 的 `1..100` 上界只适用于启用 rerank 的受管 `RetrievalConfig` 调用链。
- 旧 `HybridSearchAdvisor` 继续只负责检索，必须显式传入 `useRerank=false`；后续
  `RerankAdvisor` 的独立最终 `maxResults`、advisor 顺序和成本边界保持不变。

## 3. 已核对的调用链与风险

1. `ProjectDocumentRetriever` → `ProjectRerankPostProcessor` 已有 rerank，候选池扩大后
   只需确认输入/输出数量。
2. `KnowledgeSearchTool` 当前直接把 `HybridRetrieverService` 结果做 tool JSON，缺少
   `ReRankingService`；这是本轮必须修复的 Chat `AGENT` 风险。
3. `RagSearchController` POST、`JsonRecordService` 已有 rerank；GET 明确关闭 rerank，
   不应触发候选池。
4. `EvaluationCaseExecutor` 当前只执行检索，虽然 variant schema 支持 `rerank`，因此
   本轮必须明确其最终评估语义，不能让 candidate pool 伪装成最终结果。
5. 旧 `HybridSearchAdvisor` 后接 `RerankAdvisor`，两者的检索 limit/最终 limit 分离；本轮
   通过显式 `useRerank=false` 防止共享检索器误扩大候选池，不修改 advisor 顺序。
6. `HttpRerankProvider` 会把全部候选放入 `documents`，`top_n` 保持最终数量；候选上限
   是本轮的成本边界。

## 4. 规划审查记录

规划审查曾因以下实质缺口多次重置；最终已连续完成 `3/3` 轮无修改复核：

- 实现后的双语 `configuration`、`quality-defaults`、`troubleshooting` 和 `architecture`
  文件范围不完整；
- `KnowledgeSearchTool` 的 tool-level `limit` 没有写入传给 service 的 effective config，
  可能被会话级 `maxResults` 覆盖。
- `ReRankingService` 没有统一防御性截断 provider 的超量返回，无法仅靠 provider 契约证明
  所有调用方最终不超过 `maxResults`。
- Chat `maxUniqueSources` 默认小于 candidate pool 上限，中间候选若先写入 trace 可能使
  rerank 选中的后段结果没有 citation；已补充候选阶段不占 budget、最终结果优先于本次
  未登记候选、既有 source 不驱逐且 citation ID 稳定的规则。
- `KnowledgeSearchTool` 的 query cache 没有记录可覆盖的最终数量，大小请求可能互相泄漏
  或被较小缓存永久限制；已补充按请求数量复用/截断/重新检索规则。
- `ReRankingService` 对 provider `null` 结果没有统一错误语义；已补充显式失败与调用链降级
  断言。
- 规划的非目标曾笼统写成“不实现缓存”，与必须修复的既有 attempt-local query cache 边界
  冲突；已改为禁止新增跨请求/持久化缓存，仅允许修复既有缓存的数量覆盖语义。
- 当前 trace 的 `recordOutcome` 会立即占用 source/citation 预算；已补充候选阶段记录入口
  与最终结果记录入口，并将 `ProjectDocumentRetriever`/Agent tool 的调用顺序纳入实施范围。
- 仅修改 `RetrievalTraceCollector` 不足以落地候选/最终记录时序；已将
  `ProjectDocumentRetriever`、`ProjectRerankPostProcessor` 及其测试列入范围。
- Tool cache 不能用缓存结果条数推断覆盖范围；已要求缓存保存 `requestedLimit` coverage
  并据此决定命中、截断或重新检索。
- 旧版 GET Search 的 `limit=1000` 与本轮 candidate-limit `1..100` 的范围边界未在规划中
  明确；已补充兼容说明，避免实施时错误收窄既有 GET 契约。
- 旧 Advisor 链未在规划中明确 `HybridSearchAdvisor` 的 config 标记；已补充显式
  `useRerank=false`、生产文件范围和 AdvisorChainIntegrationTest 覆盖，避免把检索 limit
  错当成 rerank 最终 limit。
- 原先没有说明候选阶段 outcome 是否会与最终 outcome 在父 trace 中重复计数；已冻结为同一
  attempt 内由最终 outcome 替换候选阶段记录。
- 原先把 HTTP provider 内部 fallback 与调用链外层 degraded/error 诊断混为一谈；已明确
  provider 内部超时/空响应/非法 index 继续 heuristic fallback，只有异常或 null 冒泡到
  `ReRankingService`/调用链时才记录外层 degraded/error。
- 隔离 runtime 验收命令原先只激活 `postgresql`，会保留全局 rerank 关闭的基础配置，无法
  证明 candidate pool；已改为 `postgresql,prod`，同时保留 PostgreSQL 数据源与生产质量
  默认 rerank。

最终三轮无修改复核摘要：

1. 需求闭环与自包含性：核对目标、非目标、默认决策、source/citation/cache 语义、恢复入口；
   未发现问题。
2. 代码与契约可行性：交叉核对检索服务、配置绑定、provider fallback、Agent/Evaluation、
   GET/旧 Advisor、scope/filter、并发/超时和成本边界；未发现问题。
3. 实施与验证可交付性：核对文件范围、测试矩阵、PostgreSQL、前端 DOM/网络验收、隔离
   `scripts/dev.sh`、回滚和 Git 交付顺序；未发现问题。

三轮复核均未修改 `NEXT_HIGH_VALUE_FEATURES_PLAN.md`；规划检查计数达到 `3/3`。

固定检查范围见
[规划文档 §7](NEXT_HIGH_VALUE_FEATURES_PLAN.md#7-规划检查与实现收敛)：

1. 需求闭环、自包含性、非目标和默认决策；
2. 代码调用链、配置/数据/API/并发/失败语义和兼容性；
3. 文件范围、验收矩阵、隔离运行、回滚和 Git 交付。

发现实质问题时必须修改规划并将计数重置为 `0`；无问题轮次不改 plan/progress 正文，
避免破坏连续三轮无修改证据。达到 `3/3` 后才记录最终规划检查摘要和 SHA-256。

## 5. 实施恢复入口

规划交付后，下一位 Agent 应按以下顺序恢复：

1. 先读取本文件、规划 §3/§4/§5，并核对 `git status`、当前 HEAD 和 `origin/main`；
2. 在 `RagRerankPropertiesTest`、检索服务候选池测试、`HybridSearchAdvisorTest`、
   `KnowledgeSearchToolTest`、`EvaluationCaseExecutorTest` 和 `ReRankingServiceTest` 中
   一次性补齐验收断言；
3. 修改共享检索 limit，先固定旧 Advisor 的 `useRerank=false`，再接入两条漏接 rerank 链路；
4. 先运行聚焦后端和 PostgreSQL 集成，再执行 `mvn clean compile test-compile`；
5. 执行 WebUI tsc/Vitest/build/核心 Mock Playwright，再按需要启动隔离全栈；
6. 基本门槛全部通过后从实现审查 `0/3` 开始，严格按规划 §7.2 收敛；
7. 若 `origin/main` 有新提交，先 merge 到特性分支，按合并后固定顺序完整复验，再合并
   `main` 并 push。

## 6. 验证证据记录

当前尚未运行生产代码测试；本阶段适用证据：

| 验证项 | 状态 | 证据 |
|---|---|---|
| 规划前代码/文档探索 | 已完成 | `HybridRetrieverService`、rerank provider、Agent/Evaluation/Search/JSON 调用链已核对 |
| 归档迁移链接修复 | 已完成 | 归档 plan/progress 的近距离链接已改为 archive 相对路径/同目录文件 |
| 规划三轮检查 | 已完成 | 固定范围连续 `3/3` 无修改；最终一轮完成后未再改 plan |
| `./scripts/verify-project-docs.sh` | 已通过 | 三轮收敛后重复执行，10 项检查通过 |
| `git diff --check` | 已通过 | 三轮收敛后重复执行，无 whitespace 错误 |

最终规划 SHA-256：

```text
79b892d095bbcd0e93bb0ce4b1f86d10cbdfa6ddb1fd90ae4e56e9b2538cc8ed
```

## 7. 实施审查记录

生产实现尚未开始。实施完成并通过基本集成硬门槛后，按规划固定范围执行三轮只读审查；
任何影响正确性、检索质量、响应成本、兼容性或验证可信度的实质修复都会把计数重置为
`0/3`，并重新运行受影响门槛。
