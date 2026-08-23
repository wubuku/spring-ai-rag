# 有界的独立 rerank 候选池实施规划

> **状态**：规划编写中，尚未开始生产代码实施
>
> **规划日期**：2026-08-23
>
> **代码基线**：本地 `main` / `origin/main` @ `802cd991`
>
> **实施分支**：`codex/weighted-rrf-retrieval-20260823`
>
> **worktree**：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> **配套进度**：[2026-08-23_NEXT_HIGH_VALUE_FEATURES_PROGRESS.md](2026-08-23_NEXT_HIGH_VALUE_FEATURES_PROGRESS.md)
>
> **近距离上下文**：[项目上下文](../../project-context-zh-CN.md)、
> [架构说明](../../architecture-zh-CN.md)、[REST API](../../rest-api-zh-CN.md)、
> [检索质量默认值](../../quality-defaults-zh-CN.md)、[测试指南](../../testing-guide-zh-CN.md)、
> [交付工作流](../../delivery-workflow-zh-CN.md)

本文是下一轮功能的自包含实施入口。它建立在上一轮加权 RRF 已合入当前基线的事实之上，
目标是用一个边界清晰、可快速交付的检索质量切片改善 rerank 的输入召回，同时保持响应
延迟、请求体大小和现有调用方契约可控。本文只规划，不在规划阶段修改生产代码。

## 1. 执行摘要

当前检索服务把调用方的 `maxResults` 同时用于：

1. 向量/全文数据库查询的初始 `LIMIT`；
2. hybrid 融合后的输出截断；
3. rerank provider 的输入候选数量；
4. rerank 后的最终输出数量。

对启用 rerank 的受管调用链，这使 rerank 只能在已经截断的 top N 内排序。若相关候选在初始召回中排名稍后，它根本
没有机会被启发式或 HTTP cross-encoder 重新发现。HTTP provider 还会把全部传入候选写入
`documents`，因此不能简单地把所有查询都改成很大的召回量。

本轮增加一个**有界的、独立于请求的 rerank 候选池**：

```yaml
rag:
  rerank:
    candidate-limit: 20
```

当一次请求确实会经过有效 rerank 时，服务先召回：

```text
candidateLimit = max(requestedMaxResults, configuredCandidateLimit)
```

然后由现有 heuristic/HTTP/no-op provider 选择最终的 `requestedMaxResults`。
`maxResults` 仍表示最终对外返回数量；`candidate-limit` 只表示 rerank 前的内部候选
上限。受管 `RetrievalConfig.maxResults` 当前限制为 `1..100`，所以这些启用 rerank 的
调用链中 candidate pool 的最终上界仍为 `100`。旧版 GET Search 的 `limit` 仍兼容
`1..1000`，但该入口显式构造 `useRerank=false`、不调用 rerank，因此本轮不扩大其查询
池，也不收窄其既有上限。candidate-limit 本身固定限制在 `1..100`，防止配置错误导致
无界 SQL 召回或过大的 HTTP 请求体。

本轮同时补齐两个已经存在的调用链缺口：`KnowledgeSearchTool` 和受管评估执行器当前
没有在 `useRerank=true` 时调用 `ReRankingService`。如果只扩大共享检索服务的候选池，
这两个路径会暴露中间候选而不是最终 top N。它们将复用现有 rerank provider 和降级语义，
不引入第二套排序算法。旧 `HybridSearchAdvisor` 链则明确保持原有两段式语义：检索
advisor 不扩大候选池，后续 `RerankAdvisor` 仍使用自己的最终数量配置。

## 2. 范围、目标与非目标

### 2.1 本轮目标

1. 在 `rag.rerank` 下增加有界配置 `candidate-limit`，默认 `20`，有效范围 `1..100`。
2. 仅当以下条件全部成立时扩大 rerank 候选池：
   - 当前请求的 `RetrievalConfig.useRerank=true`；
   - 全局 `rag.rerank.enabled=true`；
   - provider 不是 `none`、`noop` 或 `off`。
3. 保持最终结果数量不超过请求的 `maxResults`。
4. 让 `KNOWLEDGE`、`AGENT`、直接 Search、JSON record 和 Evaluation 使用一致的
   candidate-pool → rerank → final top N 语义。
5. 继续沿用 HTTP provider 失败后的 heuristic fallback；只有异常继续冒泡到
   `ReRankingService` 或调用链时，才沿用现有的 `degraded/error` 诊断。
6. 通过真实 PostgreSQL/pgvector 集成测试、现有质量 goldenset 和必要的调用链测试证明：
   候选池扩大生效、关闭条件不扩大、最终数量不回退。
7. 在双语配置、架构、故障排查和质量文档中记录已实现的稳定事实。

### 2.2 明确非目标

- 不新增公开 HTTP 请求字段，不改变 `maxResults`、`useRerank` 或响应 JSON 字段。
- 不修改数据库 schema、Flyway migration、Collection scope、JSONB filter 或权限模型。
- 不实现按 Collection 保底召回、跨 Collection fan-out、新的跨请求/持久化缓存、用量账本
  或成本计费；本轮只修复现有 attempt-local tool query cache 在不同最终数量下的边界，
  不改变其生命周期、键空间或共享范围。
- 不重新设计 RRF 公式、向量/全文权重、query rewrite 或 prompt token budget。
- 不新增新的 rerank provider；本轮只改变已有 provider 的候选输入规模。
- 不把 `rag.rerank.top-n` 改名或删除；它继续作为调用方没有提供正数最终 limit 时的
  provider fallback，上层已经提供正数 `maxResults` 时仍以请求最终数量为准。
- 不为了本轮候选池改造 WebUI 交互或页面展示；前端只执行共享契约的回归门禁。
- 不要求真实 Chat LLM 证明排序算法本身；真实 embedding/数据库检索质量回归是必要时的
  证据，生成模型调用不改变时标记为不适用。

## 3. 当前基线与已核对事实

### 3.1 分支和工作区

- 当前专用分支是 `codex/weighted-rrf-retrieval-20260823`，基于最新本地
  `main`/`origin/main` 的 `802cd991`。
- 当前 worktree 为 `/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`。
- 规划开始前工作区只有上一轮 plan/progress 的归档重命名；本轮归档链接修复和新规划、
  进度文档属于当前任务修改，不能覆盖或丢弃其他协作者的变化。
- 上一轮 `weighted/scaled RRF` 的实现、分数语义和稳定排序已经在当前代码与
  [架构文档](../../architecture-zh-CN.md)中作为事实维护；上一轮 plan/progress 已移至
  `docs/drafts/archive/`，不作为本轮实施入口。

### 3.2 检索服务的真实行为

`HybridRetrieverService.searchInScopeDetailed(...)` 当前：

- 从 `RetrievalConfig.maxResults` 或调用方的 `limit` 得到 `effectiveLimit`；
- vector-only/全文不可用时以 `effectiveLimit` 查询向量；
- hybrid 可用时向量和全文各以 `effectiveLimit * 2` 查询，再以
  `effectiveLimit` 调用 `RetrievalUtils.fuseResults(...)`；
- 上一轮已经把融合算法改为固定 `K=60` 的 scaled weighted RRF；
- 该服务本身不调用 `ReRankingService`，而是把融合结果交给上层调用方继续处理。

因此候选池功能的最小正确改动是：在服务内部计算一个用于融合的 `retrievalLimit`，在
符合本轮受管 rerank 条件且最终数量为 `1..100` 时取
`max(effectiveLimit, configuredCandidateLimit)`，否则仍取原来的 `effectiveLimit`；
hybrid 分支仍保持各自 `retrievalLimit * 2` 的现有召回比例。内部或旧兼容调用若传入
大于 `100` 的最终数量，不因本轮配置而扩大查询池，避免候选上限与历史调用契约冲突。

### 3.3 rerank 配置和 provider 的真实行为

`RagRerankProperties` 当前已有：

- `enabled`；
- `provider`，工厂把 `none`/`noop`/`off` 映射为 no-op，其他未知值默认 heuristic；
- `topN`；
- HTTP base URL、api key、model、timeout 和 heuristic fallback。

`ReRankingService.rerank(query, results, maxResults)` 当前优先使用调用方传入的正数
`maxResults`，否则才回退到 `rag.rerank.top-n`。因此生产调用中，新增的
`candidate-limit`必须控制**输入候选数量**，不能借用 `top-n` 表示同一件事。

`HttpRerankProvider` 当前把全部传入候选转换为 `documents`，将最终数量写入 `top_n`；
candidate pool 扩大后这一行为正好符合“多候选输入、少量最终输出”的目标。HTTP 失败仍
进入现有 heuristic fallback，不在本轮改变异常或 timeout 语义。

`KnowledgeSearchTool` 当前计算了工具参数的 `limit`，但把
`context.options().toConfig()`（其中仍是会话级 `maxResults`）传给检索服务。实施时必须
构造 `maxResults=limit` 的 effective config，确保模型传入的更小工具上限先约束检索、
rerank 和最终 tool JSON；这不是新增契约，而是本轮必须一并修复的数量一致性缺口。

### 3.4 必须覆盖的调用链

| 路径 | 当前入口 | 当前 rerank 状态 | 本轮要求 |
|---|---|---|---|
| Chat `KNOWLEDGE` | `ProjectDocumentRetriever` → `ProjectRerankPostProcessor` | 已调用 | 召回池扩大后仍由 post processor 输出最终 top N |
| Chat `AGENT` 知识工具 | `KnowledgeSearchTool` | 当前只检索，未调用 `ReRankingService` | 补齐 rerank，工具输出不超过最终 `maxResults` |
| 直接 Search POST | `RagSearchController` → `ReRankingService` | 已调用 | 召回池扩大后仍最终截断 |
| 直接 Search GET | `RagSearchController` | 明确 `useRerank=false` | 不扩大候选池，保持当前延迟 |
| JSON record | `JsonRecordService` | 已调用 | 复用现有 rerank，保持记录映射和 trace |
| 受管 Evaluation | `EvaluationCaseExecutor` | 当前只检索，未调用 `ReRankingService` | 补齐 rerank，评估 `rerank` variant 反映真实排序 |
| 旧 advisor 链 | `HybridSearchAdvisor` → `RerankAdvisor` | 已调用；前者是检索，后者有独立最终 limit | `HybridSearchAdvisor` 显式以 `useRerank=false` 调共享检索器；不改变 advisor 顺序，由 `RerankAdvisor` 继续负责最终 top N |

这张表是本轮防止“只修一条 mode-aware Chat 路径”的调用链清单。任何新增调用方若直接
使用 `searchInScopeDetailed` 并声明 `useRerank=true`，必须在同一切片中接入最终 rerank，
或者明确把 `useRerank` 设为 `false`。

## 4. 冻结的行为契约

### 4.1 配置契约

```text
configuredCandidateLimit = clamp(rawValue, 1, 100)
candidateLimit = max(requestedMaxResults, configuredCandidateLimit)
```

推荐默认值为 `20`，理由是它给 rerank 足够的召回余量，同时把单次 HTTP `documents`
数量限制在一个可接受的上界。`maxResults` 当前也限制为 `1..100`，所以 candidate pool
的最终上界仍为 `100`。

配置建议：

```yaml
rag:
  rerank:
    enabled: true
    provider: heuristic
    top-n: 5
    candidate-limit: 20
```

`RagRerankProperties.setCandidateLimit` 应在配置绑定时把值限制到 `1..100`，而不是让
负数或极大值进入 SQL/HTTP 层。单元测试需要覆盖默认值、`1`、`100`、低于 `1` 和高于
`100` 的输入。该限制是运维安全边界，不是请求方可绕过的权限控制。

### 4.2 何时扩大候选池

共享检索服务使用以下等价判断：

```text
expand =
    requestConfig != null
    && requestConfig.useRerank
    && effectiveLimit >= 1
    && effectiveLimit <= 100
    && rag.rerank.enabled
    && normalizedProvider not in {none, noop, off}
```

provider 名称判断应与 `RerankProviderFactory` 保持一致：trim 后忽略大小写；
空白/未知 provider 仍按工厂的 heuristic 默认分支处理，不应意外关闭候选池。

以下路径不扩大：

- GET Search 当前显式 `useRerank=false`；
- 全局 rerank 关闭；
- provider 为 no-op；
- 旧 `HybridSearchAdvisor`（它会显式传入 `useRerank=false`，后接的 `RerankAdvisor` 保持
  自己的最终 `maxResults` 语义）；
- 最终数量不在受管 `1..100` 范围内的内部调用；
- scope `matchNone` 的 fail-closed 早退；
- hybrid/full-text 不可用时不会额外启动全文分支，但有效 rerank 的向量候选仍可使用
  `candidateLimit`；
- 请求本身没有有效候选或 embedding/provider 已 timeout 时，不改变既有降级语义。

### 4.3 召回、融合和最终数量

设受管请求最终数量为 `N`（`1 <= N <= 100`），有效候选池为
`C=max(N, candidateLimit)`。不满足受管范围的内部调用维持原有 `N`：

- vector-only 或全文不可用：向量 SQL 使用 `LIMIT C`；
- hybrid 可用：向量和全文各使用 `LIMIT C*2`，然后以 `C` 做 weighted RRF 融合；
- rerank 调用使用融合后的最多 `C` 个候选，并把 `N` 作为 provider 的最终 top N；
- 候选池扩大不改变现有 `minScore`、scope、JSONB filter、embedding freshness 或
  provider timeout 过滤；它只让更多已经满足候选资格的结果进入 rerank；
- 所有对外结果都必须在 rerank 后不超过 `N`；
- rerank 关闭或 no-op 时，检索服务仍以原来的 `N` 作为融合输出上限，不返回扩大池。

`ReRankingService` 必须在 provider 返回后再次执行最终数量保护：当调用方传入正数
`maxResults` 时，返回列表最多保留前 `maxResults` 项；provider 返回过多结果不能让
Search、Chat、tool、record 或 Evaluation 绕过这一边界。provider 返回 `null` 的行为
沿用现有 SPI 约定并作为错误处理，不在本轮静默伪造成功结果。

`RetrievalOutcome` 的诊断应继续区分 raw candidate count、fusion result count 和
rerank result count。候选池扩大后，`rerankStage` 的输入数量可能大于最终数量，但
HTTP/Chat/Search 响应的 results/sources/records 数量不能因此变大。

Chat attempt 的 `maxUniqueSources` 默认是 `20`，而候选池允许达到 `100`。因此不能
先把整个中间 candidate pool 当作最终可引用 source 写入 trace：rerank 选中候选池后段
结果时，前面的中间结果可能已经耗尽 source budget，导致最终结果没有 citation。实现上
应把 `recordOutcome` 保留为“最终结果记录”语义，新增一个仅记录阶段诊断、不占用
source/citation 位置的候选记录入口；`ProjectDocumentRetriever` 和 Agent tool 在
rerank 前使用候选入口，rerank 完成后使用最终入口。一次 attempt 的候选 outcome 在
trace/session 中只能作为临时阶段记录；rerank 成功或降级后，最终 outcome 替换该阶段
记录，不能把同一次检索计为两次独立 retrieval。若 rerank 关闭，则候选入口必须在
调用方确认没有后续排序后升级为最终结果。候选阶段不占用 source/citation 位置，
rerank 后的最终结果才尝试登记 source。既有 source snapshot（无论是否已经暴露）都
不驱逐，以保持已分配的 citation ID 稳定；因此“最终结果优先”具体指最终结果优先于
本次尚未登记的候选池，而不是允许最终结果挤掉历史 source。若旧 snapshot 已达到
`maxUniqueSources`，新的最终结果按既有预算规则没有 citation，也不能因 candidate pool
扩大而突破预算。

同一 attempt 内的 query cache 也必须遵守请求级最终数量：只有缓存至少覆盖当前
tool-level `maxResults` 时才直接复用；对更小请求截断，对更大请求重新检索。候选池或
rerank 中间结果不得成为对外 tool cache。完成 rerank（包括降级）后必须把最终结果写回
query cache，避免不同 tool-level 数量互相泄漏或被先前的小请求永久限制。缓存条目必须
同时保存最终结果和该结果实际覆盖的 `requestedLimit`，不能用“当前返回条数”推断覆盖
范围，因为数据库可能只返回少于请求上限的结果。

### 4.4 失败与回滚

- HTTP rerank 成功：按 provider 返回的相关性顺序输出最多 `N` 个；
- HTTP rerank 超时、空响应、非法 index 或其他异常：沿用 `HttpRerankProvider` 已有的
  heuristic fallback；若 provider 实现或 `ReRankingService` 将异常/null 继续交给调用链，
  则由调用链按现有边界写入 `RERANK` degraded/error 诊断，不能把 provider 内部 fallback
  误报成外层失败；
- heuristic/no-op 行为不改变最终数量规则；
- `ReRankingService` 在启用 rerank 时收到 provider `null` 结果必须抛出明确的运行时
  错误；所有调用链按各自现有边界处理，禁止把 `null` 当作成功结果。HTTP provider
  内部 fallback 仍按既有行为执行；
- 若新增的 Agent rerank 调用失败，工具应记录现有 `RERANK` degraded/error 诊断，返回
  原召回顺序但不超过 `N`，并把这个最终有界结果写入 trace/cache；Evaluation case
  应保留现有 provider/database 失败分类，不把未重排结果误标为 rerank 质量通过；
- 回滚只需撤销配置字段、候选池计算和两条漏接链路的 rerank 接入，不涉及数据库回滚。

## 5. 文件范围与实施顺序

### 5.1 允许修改的生产文件

1. `spring-ai-rag-core/src/main/java/com/springairag/core/config/RagRerankProperties.java`
   - 增加 `candidateLimit`、默认值和 `1..100` 有界 setter。
2. `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java`
   - 注入/读取 rerank 配置；
   - 增加统一的有效 rerank/candidate-limit 判断；
   - 仅改变检索分支的内部 limit，不改变已有 scope、filter、timeout、RRF 和降级逻辑。
3. `spring-ai-rag-core/src/main/java/com/springairag/core/advisor/HybridSearchAdvisor.java`
   - 为旧兼容 Advisor 链构造 `useRerank=false` 的 `RetrievalConfig`；
   - 保留 Advisor 自己的检索 limit 与后续 `RerankAdvisor` 最终 limit，不让新 candidate pool
     改变旧链的请求成本和输出语义。
4. `spring-ai-rag-core/src/main/java/com/springairag/core/rag/KnowledgeSearchTool.java`
   - 接入 `ReRankingService`；
   - 在记录最终 trace/output 前执行 rerank；
   - 使用 tool-level limit 构造 effective config；
   - 只复用覆盖当前 limit 的最终结果缓存，并把较小请求截断、较大请求重新检索；
   - 在 rerank 完成或降级后把最终结果写回 trace/cache；
   - 保留工具字符预算、citation 和异常处理。
5. `spring-ai-rag-core/src/main/java/com/springairag/core/evaluation/EvaluationCaseExecutor.java`
   - 接入 `ReRankingService`；
   - 对 `useRerank=true` 的 evaluation variant 执行相同最终 top N；
   - 保留现有 case 失败分类和稳定 identity 映射。
   - 同步更新所有直接构造该 executor 的测试/集成测试调用点，显式注入测试用
     `ReRankingService` 或 `null`。
6. `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/ReRankingService.java`
   - 对 provider 输出执行最终 `maxResults` 防御性截断；
   - 对启用 rerank 时的 `null` provider 输出抛出明确错误，保持现有 provider/fallback 语义。
7. `spring-ai-rag-core/src/main/resources/application.yml`
   - 增加 `rag.rerank.candidate-limit: ${RAG_RERANK_CANDIDATE_LIMIT:20}`。
8. `spring-ai-rag-core/src/main/resources/application-prod.yml`
   - 显式记录生产默认 candidate limit，允许环境变量覆盖。
9. `docs/configuration.md`、`docs/configuration-zh-CN.md`
   - 记录 candidate-limit、环境变量、边界和与 top-n 的区别。
10. `docs/quality-defaults.md`、`docs/quality-defaults-zh-CN.md`
   - 记录候选池默认值、goldenset 观察项和质量/延迟回退边界。
11. `docs/troubleshooting.md`、`docs/troubleshooting-zh-CN.md`
   - 记录候选池导致 HTTP 请求体或延迟增加时的排查与回滚指引。
12. `docs/architecture.md`、`docs/architecture-zh-CN.md`
    - 记录 candidate pool → fusion → rerank → final top N 的稳定管线语义，以及旧 Advisor
      链的两段式兼容边界。
13. `spring-ai-rag-core/src/main/java/com/springairag/core/chat/RetrievalTraceCollector.java`
    - 增加候选阶段记录与最终结果记录的区分；候选阶段只更新阶段诊断，不进入
      sources/citation budget；
    - 将 query cache 条目改为“最终结果 + 覆盖 limit”，提供按请求 limit 判断命中和截断
      的接口；
    - 让 rerank 后的最终结果在本次尚未登记的候选池之前获得 source/citation 位置；
    - 保留既有 source 和稳定 citation ID，不以候选池扩张突破 unique-source budget；
    - 让一次 attempt 的候选阶段诊断被最终 outcome 替换，避免父 trace 重复计数。
14. `spring-ai-rag-core/src/main/java/com/springairag/core/rag/ProjectDocumentRetriever.java`
    - 先记录候选阶段 outcome；当请求不使用 rerank 时直接记录最终 outcome；
    - 保留 retrieval budget、scope/filter 和现有文档映射。
15. `spring-ai-rag-core/src/main/java/com/springairag/core/rag/ProjectRerankPostProcessor.java`
    - 将 rerank 后结果记录为最终 outcome，并保留 RERANK stage、degraded/error 诊断；
    - 确保候选池不先占用 citation budget。

### 5.2 允许修改的测试文件

1. `spring-ai-rag-core/src/test/java/com/springairag/core/config/RagRerankPropertiesTest.java`
   - 默认值、边界 clamp 和 setter 测试。
2. `spring-ai-rag-core/src/test/java/com/springairag/core/retrieval/HybridRetrieverOutcomeTest.java`
   或新增同包 `HybridRetrieverCandidatePoolTest.java`
   - useRerank/global/provider 三项条件矩阵；
   - vector-only、hybrid、full-text unavailable；
   - candidate limit 小于/大于请求 `maxResults`；
   - 关闭 rerank 时查询 limit 仍等于请求数量。
3. `spring-ai-rag-core/src/test/java/com/springairag/core/advisor/HybridSearchAdvisorTest.java`
   - 验证旧 Advisor 链传给共享检索器的 config 明确为 `useRerank=false`；
   - 验证旧 Advisor 的检索 limit 不因 candidate-limit 配置改变。
4. `spring-ai-rag-core/src/test/java/com/springairag/core/rag/KnowledgeSearchToolTest.java`
   - 验证工具把扩大后的候选交给 rerank；
   - 验证输出、trace source 和 citation 数量不超过最终 `maxResults`；
   - 验证缓存命中后更小的 tool-level `maxResults` 会截断；
   - 验证缓存来自较小请求时，更大的 tool-level `maxResults` 会重新检索；
   - 验证 rerank 选中候选池后段结果时仍可获得 citation；
   - 验证 rerank 异常时仍按字符预算返回有界原顺序结果并写入 degraded trace。
5. 新增 `spring-ai-rag-core/src/test/java/com/springairag/core/evaluation/EvaluationCaseExecutorTest.java`
   - 验证 rerank variant 调用 `ReRankingService`；
   - 验证关闭 rerank 不调用；
   - 验证 provider 异常不伪造通过结果。
6. `spring-ai-rag-core/src/test/java/com/springairag/core/retrieval/ReRankingServiceTest.java`
   - 增加 provider 返回超过 `maxResults` 时的最终数量保护测试；
   - 增加 provider 返回 `null` 时的失败测试。
7. `spring-ai-rag-core/src/test/java/com/springairag/core/integration/HybridRetrieverRrfPostgresIntegrationTest.java`
   - 增加真实 PostgreSQL/pgvector 的 candidate-limit 场景；
   - 保留原有 RRF 交叠候选和原始分数断言。
8. `spring-ai-rag-core/src/test/java/com/springairag/core/integration/AdvisorChainIntegrationTest.java`
   - 保持旧 Advisor 链的检索与独立 rerank 顺序、最终数量和异常语义。
9. `spring-ai-rag-core/src/test/java/com/springairag/core/chat/RetrievalTraceCollectorTest.java`
   - 验证候选阶段不占用 source budget、最终 rerank 结果优先于本次未登记候选获得 citation；
   - 验证既有 source（已暴露或未暴露）不会被淘汰且 citation ID 稳定；
   - 验证候选 outcome 被同一 attempt 的最终 outcome 替换，不会重复计数；
   - 验证 query cache 使用保存的 coverage limit：较小请求截断、较大请求重新检索，
     结果较少但 coverage 足够时仍可命中。
10. `spring-ai-rag-core/src/test/java/com/springairag/core/rag/ProjectDocumentRetrieverTest.java`
   - 验证候选阶段/最终阶段记录顺序；
   - 验证 rerank 关闭时候选结果仍成为最终可引用结果。
11. `spring-ai-rag-core/src/test/java/com/springairag/core/rag/ProjectRerankPostProcessorTest.java`
   - 验证 rerank 成功、异常降级和最终 source/citation 记录。
12. 相关已有 controller/service/integration 测试
   - 只在构造函数注入 `ReRankingService` 或结果数量契约需要更新时修改；
   - 不为本轮重新编写无关的 API 测试。

### 5.3 生产实施顺序

1. 更新 progress，记录规划检查 `3/3` 前的恢复入口和当前 SHA。
2. 先补齐配置属性和 focused tests，使测试明确捕获未扩大候选池/错误最终数量。
3. 修改 `HybridRetrieverService` 的内部召回 limit，保持 RRF 和 SQL filter 不变。
4. 修改旧 `HybridSearchAdvisor` 的 config 标记，保持它与 `RerankAdvisor` 的两段式契约。
5. 接入 `KnowledgeSearchTool` 与 `EvaluationCaseExecutor` 的现有 rerank facade。
6. 为 `ReRankingService` 增加最终数量保护。
7. 修复 `KnowledgeSearchTool` 的 tool-level `maxResults` config 传递、候选/最终 trace
   时序和 coverage-aware cache，并更新双语配置、架构、质量和故障排查文档。
8. 更新 YAML 默认值，运行文档和配置绑定相关测试。
9. 运行一次性验收矩阵中的后端、PostgreSQL、前端和运行时门槛。
10. 基本门槛全部通过后执行限定范围的实现三轮审查；任何实质修复重置计数并重跑受影响
   门槛。

## 6. 一次性验收矩阵

### 6.1 后端聚焦测试

先运行候选池、rerank 漏接链路、controller/service 相关测试的完整集合，不在 review
过程中临时“发现一个问题补一个测试”。建议命令：

```bash
mvn -pl spring-ai-rag-core -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=RagRerankPropertiesTest,HybridRetrieverOutcomeTest,HybridSearchAdvisorTest,KnowledgeSearchToolTest,EvaluationCaseExecutorTest,ReRankingServiceTest,RetrievalTraceCollectorTest,ProjectDocumentRetrieverTest,ProjectRerankPostProcessorTest,RagSearchControllerTest,JsonRecordServiceTest,AdvisorChainIntegrationTest \
  test
```

验收断言必须覆盖：

- 请求 `maxResults=5`、candidate limit `20` 时 rerank 输入可达 `20`，最终输出最多 `5`；
- 旧版 GET Search 的 `limit=1000` 仍可用，且不扩大 candidate pool、不进入 rerank；
- Agent tool 参数 `maxResults=1` 时 effective config、rerank limit、tool JSON 和 citation
  数量均不超过 `1`，不被会话级默认数量覆盖；
- provider 即使错误返回超过最终数量的列表，`ReRankingService` 仍将结果截断到请求
  `maxResults`；
- `useRerank=false`、全局关闭、provider no-op 三种情况下不扩大；
- 旧 Advisor 链显式 `useRerank=false`，其独立 `RerankAdvisor` 最终 limit 和原有检索 limit
  不变；
- vector-only 的查询/融合候选上限不超过 `100`；hybrid 的最终融合池不超过 `100`，
  每个全文/向量通道仍按现有 `2x` 比例查询，最大单通道 SQL limit 为 `200`；
- full-text unavailable/timeout 保持原有 outcome/error；HTTP provider 内部 fallback
  保持原有返回语义，只有异常/null 冒泡时才产生外层 degraded/error；
- Agent tool 和 Evaluation 不再把中间 candidate pool 当最终结果；
- JSON record 的文档回表、去重、payload filter 和 trace/citation 不回退。

### 6.2 PostgreSQL/pgvector 集成矩阵

使用现有 Testcontainers 约定：

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
DOCKER_API_VERSION=1.40 \
mvn -pl spring-ai-rag-core -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dhybrid-rrf.it.enabled=true \
  -Dtest=HybridRetrieverRrfPostgresIntegrationTest \
  test
```

集成测试从空库执行当前 Flyway 迁移，写入活动 Embedding Profile、文档、向量和可控全文
候选。必须证明：

1. 真实向量 SQL 在 rerank 有效时可提供 candidate pool；
2. 融合后的候选数量可能大于最终 `maxResults`，但交给上层 rerank 后最终数量恢复；
3. 关闭 rerank 后仍为旧的 `maxResults` 查询/融合边界；
4. Collection/scope、embedding freshness 和原有 weighted RRF 结果不变。

如果 Docker 或 disposable PostgreSQL 不可用，测试必须明确记录为环境限制，不能把
Mock provider 结果当作 PostgreSQL 集成证据。

### 6.3 Maven 和通用后端门槛

```bash
mvn clean compile test-compile
./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-project-docs.sh
git diff --check
```

服务必须使用 `postgresql` profile 启动并通过 health check。由于本轮不新增 migration，
仍必须确认 Flyway 从干净数据库启动成功。

### 6.4 前端门槛

本轮不修改 `spring-ai-rag-webui/`，但 Search API 的结果数量和响应语义属于共享契约，
所以按项目硬门槛执行：

```bash
cd spring-ai-rag-webui
npx tsc -b --pretty false
npm run test:run
npm run build
npx vite preview --host 127.0.0.1 --port 4198 --strictPort
# 另一个 shell：
BASE_URL=http://127.0.0.1:4198 \
  npx playwright test e2e/search.spec.ts e2e/navigation.spec.ts --project=chromium
```

Playwright 只能用 DOM 可见性、可访问状态、网络请求/响应、接口 JSON 和自动化断言；
禁止使用截图作为验收证据。若只读 Search 页面没有暴露 rerank candidate pool，Mock
spec 仍需确认最终结果展示没有因为后端内部候选池而超出请求数量。

### 6.5 隔离端口真实全栈

基本门槛通过后，用本 worktree 的 `.env` 和 `scripts/dev.sh` 启动隔离栈：

```bash
BACKEND_PORT=18083 FRONTEND_PORT=15175 \
  SPRING_PROFILES_ACTIVE=postgresql,prod RAG_DEV_OPEN_BROWSER=false \
  ./scripts/dev.sh
```

确认后端 health、前端 `http://127.0.0.1:15175/webui/`、Vite proxy 和 Search API
请求，再运行：

```bash
(cd spring-ai-rag-webui && \
  BASE_URL=http://127.0.0.1:15175 \
  npx playwright test e2e/search.spec.ts e2e/navigation.spec.ts \
  --project=chromium)
```

完成后：

```bash
BACKEND_PORT=18083 FRONTEND_PORT=15175 ./scripts/dev.sh --stop
```

本轮真实 Chat LLM smoke 标记为 `N/A`：没有修改 Chat provider、prompt 生成或
SSE 序列化。若真实 embedding key 可用，必须运行已有 retrieval goldenset/quality
regression，观察日志并记录 MRR/Hit Rate/latency；若 key 不可用，明确记录外部依赖限制。

## 7. 规划检查与实现收敛

### 7.1 规划连续三轮检查

规划检查计数从 `0/3` 开始。三轮固定范围：

1. **需求闭环与自包含性**：目标是否只聚焦检索质量/Chat 结果/响应速度，非目标是否
   排除了权限与用量账本，决策和恢复入口是否足够完整。
2. **代码与契约可行性**：重新核对 `HybridRetrieverService`、旧 Advisor 两段式链和其他调用链、配置绑定、
   provider fallback、Agent/Evaluation 最终数量、scope/filter、并发/超时和兼容性。
3. **实施与验证可交付性**：文件范围、测试矩阵、PostgreSQL、前端共享契约、隔离端口、
   质量回归、回滚和 Git 交付顺序是否可执行。

发现会影响正确性、质量、兼容性、成本安全、数据一致性或验证可信度的问题，立即修改
本文并将计数重置为 `0`。措辞、格式和实施中自然会暴露的行号漂移不触发重置。无问题
轮次不修改 plan/progress 正文；只有连续三轮无修改后，才一次性把 `3/3` 结果写入
progress。

### 7.2 实现连续三轮检查

基本集成硬门槛全部通过后，计数从 `0/3` 开始，且只读、固定范围：

1. 候选池计算、配置上下界、rerank 条件、分支 limit、scope/filter、timeout 和失败恢复；
2. `KNOWLEDGE`/`AGENT`/Search/JSON/Evaluation 的最终 `maxResults`、旧 Advisor 两段式
   兼容性、provider fallback、
   trace/citation、HTTP 请求体成本和兼容性；
3. PostgreSQL/Mock Playwright/真实运行时证据、文档同步、回滚和 Git 状态。

只有发现本轮范围内的实质缺陷才修改；修改后重跑受影响测试和全部基本硬门槛，并把实现
审查计数重置为 `0`。连续三轮无修改才允许进入特性分支交付。

## 8. 发布、回滚与完成定义

### 8.1 发布控制

- 默认 candidate limit 为 `20`，可通过 `RAG_RERANK_CANDIDATE_LIMIT` 调整到 `1..100`；
- 不增加请求方可任意放大的参数，避免外部 Client 通过请求直接制造高成本 rerank；
- 想快速回退质量/延迟风险时，将 candidate limit 设为 `1`，或临时关闭全局 rerank；
- 调整配置后必须重启服务并重新运行 retrieval goldenset，不把旧进程结果当新配置证据。

### 8.2 回滚边界

若质量回归下降、p95 延迟超出既有预算、HTTP 请求体过大或任一调用链违反最终数量，
先把 candidate limit 恢复为 `1`/旧行为，再修复代码。代码回滚只涉及本轮列出的 Java、
YAML、测试和双语文档，不执行数据库回滚。

### 8.3 完成定义

只有以下条件全部满足，实施阶段才可报告完成：

1. 本规划完成连续 `3/3` 无修改检查，进度账本可恢复；
2. candidate pool 条件矩阵、最终数量和 Agent/Evaluation 漏接链路有自动化断言；
3. 真实 PostgreSQL/pgvector 集成、相关后端测试和 `mvn clean compile test-compile` 通过；
4. WebUI tsc、Vitest、production build 和核心 Mock Playwright 通过，且未使用截图验收；
5. 隔离端口 `scripts/dev.sh` 栈可启动、health/proxy/Search API/Playwright 通过；
6. 适用时完成 retrieval goldenset/quality regression；真实 Chat LLM 按本轮范围记录
   `N/A` 或环境限制；
7. 实现完成后连续 `3/3` 限定范围审查无实质问题；
8. 行为变化同步到双语配置/架构/质量/故障排查文档；
9. 特性分支先 merge 最新 `origin/main` 并按合并后固定顺序重新验收，再 merge 到
   `main`、push，并确认 `main`、`origin/main` 和工作区状态。
