# KNOWLEDGE 多查询扩展的预算感知与有界 fan-out 规划

> **状态**：规划草案，尚未开始生产代码实施
>
> **规划日期**：2026-08-24
>
> **代码基线**：本地 `main` / `origin/main` @ `b8b61853`
>
> **规划分支**：`docs/next-high-value-features-plan-20260824`
>
> **worktree**：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> **配套进度**：[NEXT_HIGH_VALUE_FEATURES_PROGRESS.md](NEXT_HIGH_VALUE_FEATURES_PROGRESS.md)
>
> **近距离上下文**：[项目上下文](../project-context-zh-CN.md)、
> [架构说明](../architecture-zh-CN.md)、[配置参考](../configuration-zh-CN.md)、
> [Chat 记忆、RAG 与工具调用](../chat-memory-rag-tool-calling-zh-CN.md)、
> [质量默认值](../quality-defaults-zh-CN.md)、[测试指南](../testing-guide-zh-CN.md)、
> [交付工作流](../delivery-workflow-zh-CN.md)

本文是下一轮小范围功能的自包含实施入口。上一轮 rerank 后文档级证据去冗余已经合入
`main`，其规划和进度已归档到
`docs/drafts/archive/2026-08-24_NEXT_HIGH_VALUE_FEATURES_*`，对应的稳定事实已经进入
双语架构、配置、质量、开发者参考和排障文档。本轮不重复规划已交付的 weighted RRF、
有界 rerank 候选池或文档级证据去冗余。

## 1. 执行摘要

本轮只处理生产 `KNOWLEDGE` Chat 链路中的一个问题：**多查询扩展的 LLM 生成预算、
实际检索预算和诊断信息没有形成同一个明确的有界契约**。

当前 `postgresql`、`local` 和 `prod` profile 使用 Spring AI 的：

```text
历史感知查询压缩
  -> MultiQueryExpander 生成原始查询之外的查询变体
  -> ProjectDocumentRetriever 并行执行项目混合检索
  -> ConcatenationDocumentJoiner 合并、去重并按分数排序
  -> 项目 rerank
  -> Prompt budget 裁剪
  -> citation augment
  -> ChatModel
```

当前默认值为保留原始请求并生成两个变体，因此计划执行三次检索。问题在于：

- `rag.chat.knowledge.query-expander-variants` 可配置为最多五个变体；
- `ModeAwareChatClientFactory` 当前把 `rag.chat.agent.max-retrieval-calls` 同时作为
  KNOWLEDGE 和 AGENT 的 attempt 级检索预算，默认是三次；
- 当配置的变体数超过三次检索预算时，Spring AI 仍会先调用 ChatModel 生成多余变体，
  `RetrievalAugmentationAdvisor` 随后为它们创建并行检索任务；
- `ProjectDocumentRetriever` 在 `RetrievalTraceCollector.tryBeginRetrieval` 处静默拒绝
  超预算查询，因此多余变体不会增加结果，却会造成不必要的模型输出、调度和诊断歧义；
- 每个变体都独立经过向量/全文分支。默认三路查询最多产生三次 query embedding 和三组
  hybrid SQL 候选，再由现有 rerank candidate limit 收敛，fan-out 的中间成本可能远大于
  最终结果数量；
- Spring AI 内置 `MultiQueryExpander` 要求模型返回精确行数，但不去除重复行。重复变体
  会占用查询预算，却不会扩大检索覆盖；
- 当前 Chat 响应中的 `metadata.retrieval` 只有执行次数等摘要，不能明确说明配置的变体
  是否被预算截断、多少重复变体被去掉或计划执行了多少查询。

本轮增加一个**只属于 KNOWLEDGE 的检索查询预算**，并以一个轻量的项目包装器约束
Spring AI expander：

```yaml
rag:
  chat:
    knowledge:
      max-retrieval-queries: ${RAG_CHAT_KNOWLEDGE_MAX_RETRIEVAL_QUERIES:3}
```

默认值 `3` 保持现有生产路径的三路检索行为；当调用方把变体数调高时，系统在生成 prompt
之前就把请求的变体数裁剪到预算以内，不再让多余变体进入 `RetrievalAugmentationAdvisor`。
包装器还会按原顺序去掉空白和精确重复变体，并把低基数的计划摘要写入已有 retrieval
metadata。AGENT 的工具调用预算、Search API、Evaluation API、权限边界、数据库 schema
和公开请求字段都不在本轮修改范围内。

## 2. 为什么本轮选择这个功能

### 2.1 候选比较

| 候选 | 检索/Chat 价值 | 延迟与成本 | 实施范围 | 本轮决定 |
|---|---|---|---|---|
| KNOWLEDGE 多查询扩展预算感知、有界去重与诊断 | 直接减少无效 query fan-out，保持默认召回并使调参可解释 | 不增加默认调用；配置超预算时减少 LLM 输出和 embedding/SQL 路数 | `RagChatProperties`、Chat factory、一个 expander wrapper、trace、测试和双语参考文档 | **实施** |
| Search、AGENT、Evaluation 统一多查询扩展 | 可能提高跨入口召回一致性 | 要同时设计请求级预算、缓存、工具语义、评估语义，容易放大延迟 | 跨多个入口和公开行为 | 延后 |
| `EACH_COLLECTION` 覆盖召回 | 对明确的多知识库覆盖需求有价值 | bounded fan-out、融合和额外 SQL 延迟 | 新 API 语义、检索器、质量指标、WebUI | 继续保持非紧急 backlog |
| 跨请求检索结果缓存 | 可能降低重复查询延迟 | 需要 scope/filter/rerank/config 版本化，存在授权和陈旧性风险 | cache key、失效、观测、并发和安全 | 延后 |
| OIDC/复杂权限或 token/cost ledger | 平台能力价值存在 | 与当前质量、Chat 结果和响应速度关系弱，范围大 | 安全、schema、前端和运维 | 不进入本轮 |

本轮选择的切片只消费已有 Spring AI Modular RAG 链路，不复制检索算法，不创建新的
检索入口，也不依赖外部 Client 的特定需求。它可以先用 Mock 和现有 Chat 测试证明契约，
再用 PostgreSQL 和真实 Chat smoke 证明实际 fan-out。

### 2.2 当前实现事实

已经在 `b8b61853` 基线上核对以下事实：

1. `ModeAwareChatClientFactory.buildKnowledgeAdvisor(...)` 给 `KNOWLEDGE` 配置
   `MultiQueryExpander`、`ProjectDocumentRetriever`、rerank post processor、prompt budget
   post processor 和 citation augmenter。
2. 只有 `query-transformer` 为 `spring-ai` 时才创建 query transformer 和 expander；
   `none` 不做多查询扩展。当前生产 profile 的具体配置见
   [configuration](../configuration-zh-CN.md) 和
   [Chat 链路说明](../chat-memory-rag-tool-calling-zh-CN.md)。
3. Spring AI `MultiQueryExpander` 的 `numberOfQueries` 表示模型必须返回的变体行数；
   `includeOriginal=true` 会在变体列表前插入原始查询；模型返回空值或行数不匹配时会
   回退为输入查询。
4. Spring AI `RetrievalAugmentationAdvisor` 会为扩展后的每个 `Query` 创建异步检索任务，
   最后交给 `ConcatenationDocumentJoiner` 合并、按 `Document` identity 去重和按 score
   排序。项目自身的授权上下文沿每个 query 保留。
5. `ProjectDocumentRetriever` 每执行一个 query 都调用 `trace.tryBeginRetrieval`；
   超过 attempt 预算时返回空列表。正常结果会记录到 `RetrievalTraceSession`，但超预算
   的扩展 query 不会成为一个带有明确“被裁剪”原因的独立 outcome。
6. `HybridRetrieverService` 对每个 query 独立执行 query embedding，以及向量和全文分支；
   启用 rerank 时每一路可以使用 `candidate-limit` 的候选。已有 rerank candidate pool
   只约束最终 rerank 输入，不会消除多查询之间的数据库 fan-out。
7. `RetrievalTraceCollector.summary()` 已经通过 Chat metadata 返回
   `retrievalCalls`、`toolRounds` 和 `sourceCount`，适合增加低基数
   `queryExpansion` 摘要；不得在该摘要中写入原始查询、模型输出或高基数标签。
8. `Search`、`Evaluation` 和 `AGENT` 有独立编排。它们不使用本轮的 Spring AI
   `MultiQueryExpander`，不应因本轮共享配置而改变行为。

### 2.3 不把本轮扩大为跨入口统一扩展

跨入口统一扩展表面上更完整，但其正确实施需要同时回答：

- Search API 是否允许请求级启用 LLM expansion；
- AGENT 的模型自主 tool query 与服务端扩展如何避免重复；
- Evaluation 如何在 goldenset 中区分原始 query、扩展 query 和最终结果；
- 每个入口是否共享 embedding、candidate、rerank 和延迟预算；
- 扩展查询是否应写入可持久化诊断、是否需要隐私脱敏。

这些问题不阻断本轮。先把生产 KNOWLEDGE 的预算语义做成可验证的单入口契约，能够为
后续跨入口规划提供真实的 query count、p95 和质量证据。

## 3. 目标、非目标和完成定义

### 3.1 目标

1. KNOWLEDGE 每个 Chat attempt 的计划检索 query 数有明确、独立、可配置的上限。
2. 默认配置不改变当前生产行为：`includeOriginal=true`、`variants=2` 时仍计划三次
   检索。
3. 当配置的变体数超过上限时，在调用 Spring AI expander 前减少模型要求生成的行数，
   不让超预算 query 进入异步检索。
4. 去除空白和精确重复变体，保持原始顺序；同一 query 不重复执行。
5. AGENT 仍使用现有 `max-retrieval-calls`，不会因为新增 KNOWLEDGE 配置而改变工具
   调用次数、工具并发或工具结果预算。
6. 在 Chat metadata 和持久化 retrieval trace 中提供低基数计划摘要，足以回答：
   配置的变体数、有效变体数、是否被上限裁剪、计划查询数、去重数和实际执行次数。
7. 在不新增远程调用的默认路径下，以自动化测试和固定范围运行证据证明质量不退化、
   fan-out 不超过预算、响应契约和 citation 不变。

### 3.2 非目标

- 不改变 Search POST/GET 的查询语义，不给 Search API 增加 LLM expansion 开关。
- 不改变 AGENT 的模型 tool calling、tool schema、工具轮数或工具查询内容。
- 不把旧 `QueryRewritingService`、`QueryRewriteAdvisor` 或 `HybridSearchAdvisor` 迁移到
  新链路；它们仍保持兼容组件语义。
- 不实现 `EACH_COLLECTION`、跨 Collection fan-out、跨请求检索缓存、权限体系或用量
 账本。
- 不增加 Flyway migration、表、索引、公开 DTO 字段或 WebUI 业务控件。
- 不把原始扩展 query 文本写入默认响应 metadata、Prometheus label 或持久化日志；既有
 诊断是否保存 query text 仍由现有 `store-query-text` 配置控制。
- 不承诺通过减少 query fan-out 一定提高所有 goldenset 的 Recall；质量门禁必须同时
 观察召回与 p95。

### 3.3 完成定义

本轮规划对应的后续实施只有在以下条件全部满足时才算完成：

1. 规划文档达到连续三轮无修改检查；
2. `KNOWLEDGE` 的新预算配置、wrapper、trace 摘要和测试均已实现；
3. 默认三路行为、`max=1` 原始查询直通、超配置裁剪、重复/空白去重和 LLM 失败回退
   均有自动化断言；
4. PostgreSQL 集成测试证明每个 query 只做一次 vector/full-text 召回，实际检索次数
   不超过预算，结果 join/rerank/citation 仍正确；
5. `mvn clean compile test-compile`、本任务后端聚焦集成矩阵、前端 TypeScript/build/
   核心 Mock Playwright、隔离启动和适用的真实 Chat smoke 均有证据；
6. 实现代码达到连续三轮无修改检查；
7. 受影响的配置、架构、测试和排障长青文档按中英文成对同步；
8. 特性分支先跟进 `origin/main`，再合入并推送 `main`，最终状态已核对。

## 4. 冻结的行为契约

### 4.1 新配置及默认值

在 `RagChatProperties.KnowledgeProperties` 增加：

```yaml
rag:
  chat:
    knowledge:
      max-retrieval-queries: ${RAG_CHAT_KNOWLEDGE_MAX_RETRIEVAL_QUERIES:3}
```

契约固定为：

| 配置 | 默认 | 范围 | 语义 |
|---|---:|---:|---|
| `query-expander-variants` | `2` | `1..5` | 请求 LLM 生成的变体数量，现有配置保持兼容 |
| `query-expander-include-original` | `true` | boolean | 是否把变换后的原始 query 作为一条检索 query |
| `max-retrieval-queries` | `3` | `1..5` | 一个 KNOWLEDGE attempt 最多计划执行的检索 query 数 |

推荐的环境变量名为 `RAG_CHAT_KNOWLEDGE_MAX_RETRIEVAL_QUERIES`。绑定值超出范围时按
现有 Chat properties 的安全方式收敛到 `1..5`；实现不得把非法值转换为无界 fan-out。
配置不作为请求参数公开，不能由 Chat 请求临时放大。

计算规则：

```text
configuredVariants = clamp(query-expander-variants, 1, 5)
maxQueries = clamp(max-retrieval-queries, 1, 5)
reservedOriginal = query-expander-include-original ? 1 : 0
effectiveVariants =
    min(configuredVariants, max(0, maxQueries - reservedOriginal))
plannedQueries =
    effectiveVariants + reservedOriginal
```

特殊值语义：

- `includeOriginal=true` 且 `maxQueries=1`：不创建多查询 expander，直接用 transformed
  query 执行一次检索，不调用扩展 ChatModel。
- `includeOriginal=false` 且 `maxQueries=1`：最多执行一个模型生成的变体；如果扩展模型
  失败，沿用 Spring AI expander 的安全回退结果，不增加第二次检索。
- 默认 `includeOriginal=true`、`variants=2`、`maxQueries=3`：行为与当前生产默认一致。
- `query-transformer=none`：不创建 transformer 或 expander；`max-retrieval-queries`
  不改变单次原始 query 的行为。
- 配置超过上限时只减少本次 expander 的请求数量，不修改调用方的 `maxResults`、
  rerank candidate limit、prompt token budget 或 Agent tool budget。

### 4.2 预算归属

`ModeAwareChatClientFactory.create(...)` 创建 attempt trace 时：

- `KNOWLEDGE` 使用 `knowledge.max-retrieval-queries` 作为 `maxRetrievalCalls`；
- `AGENT` 继续使用 `agent.max-retrieval-calls`；
- `PLAIN` 不创建检索查询，保留现有无检索语义；
- fallback candidate 会创建新的 attempt collector，因此每个候选 attempt 都有自己的
  有界预算，已经消耗的前一个失败 attempt 不会被错误地复用；
- `KnowledgeSearchTool` 和 `JsonRecordSearchTool` 只在 AGENT 使用，不读取
  `knowledge.max-retrieval-queries`。

这样可以消除当前“KNOWLEDGE 借用 AGENT 预算”的隐式耦合，同时不改变 AGENT 现有策略。
本轮不增加跨工具总预算；跨候选尝试的总模型/检索预算仍由现有 `ChatExecutionBudget`
负责。

### 4.3 有界、去重的 expander

新增项目内部 `BoundedMultiQueryExpander`，实现 Spring AI
`QueryExpander`，包装现有 `MultiQueryExpander`。它不重写 Spring AI 的 prompt 生成
协议，只负责输出边界：

1. 调用 delegate 一次；
2. 按 delegate 原顺序读取结果；
3. 对每个 query text 做 `trim`；空白项丢弃；
4. 使用 trim 后的**精确字符串**去重，不做大小写折叠、翻译、词干化、标点归一化或
   领域语义判断；
5. 保持原始 query 的优先位置（当 `includeOriginal=true` 时应位于第一项）；
6. 最终列表最多 `plannedQueries` 项；如果 delegate 返回空或全部无效，至少返回原
   transformed query，保证 RAG advisor 不收到空 query 列表；
7. 保留每个原始 `Query` 的 history/context，不能重新构造一个丢失
   `AuthorizedRetrievalContext` 的 query；
8. 不在 wrapper 中调用 embedding、数据库、rerank 或 ChatModel。

wrapper 的去重是确定性的，因此相同 LLM 输出在不同线程调度下产生相同的计划 query
序列。Spring AI advisor 仍可并行执行这些不同 query；本轮不把它们改成串行。

### 4.4 诊断摘要

扩展现有 `RetrievalTraceCollector.summary()` 和持久化
`RetrievalTraceSession.toMetadata(...)`，增加低基数对象：

```json
{
  "queryExpansion": {
    "enabled": true,
    "configuredVariants": 5,
    "effectiveVariants": 2,
    "includeOriginal": true,
    "maxRetrievalQueries": 3,
    "plannedQueries": 3,
    "budgetLimited": true,
    "duplicateVariantsRemoved": 1
  }
}
```

契约要求：

- 字段值只使用整数、布尔值和固定键，不把 query 文本、模型响应或异常全文写入摘要；
- `enabled=false` 或 `query-transformer=none` 时可以省略该对象，不能伪造
  `plannedQueries=0` 作为一次检索；
- `actualRetrievalQueries` 不从 expander 推测，使用现有 `retrievalCalls` 作为事实；
- expander 失败时记录 `enabled=true`、计划预算和 `actualRetrievalQueries`，另加固定
  `degraded=true` 或既有错误摘要字段；不得把 provider 异常堆栈写入 metadata；
- 响应 metadata 与持久化 trace 使用同一摘要来源，避免 Chat 响应和诊断页面显示不同
  的预算状态；
- 不新增数据库列，不改变 `rag_retrieval_logs` 的 query 脱敏策略，不新增 Prometheus
  高基数标签。

`effectiveQuery` 当前可能因为多个并行 query 的完成顺序而指向任一实际 query；本轮不
把它重新定义为“唯一主 query”，也不以它替代新的 queryExpansion 预算摘要。若实施时
发现新增摘要必须改变该字段，必须先修改本规划并重置规划/实现检查计数。

### 4.5 失败与兼容语义

- query transformer 超时或失败：保持现有 history-aware fallback；不启动额外 expansion
  重试，不突破 query budget。
- expansion ChatModel 返回 null、空白、行数不匹配或重复：沿用 delegate 的原始 query
  fallback，再由 wrapper 做边界清理；不得让 Chat 请求失败。
- 某个 hybrid query 的向量或全文分支失败：沿用 `HybridRetrieverService` 当前分支
  降级为空结果并记录 stage；其他 query 的结果仍可 join。
- 超预算 query 不应在正常实施后进入 advisor；若直接调用 `ProjectDocumentRetriever`
  或自定义 expander 仍产生超额 query，collector 继续防御性拒绝。
- rerank 失败、prompt budget 裁剪、citation 分配和模型 fallback 语义保持现状。
- `Search`、`AGENT`、`PLAIN`、旧 Advisor 和 Evaluation 不读取本配置，不改变原有
  请求/错误契约。

## 5. 文件级实施顺序

实施时按以下顺序推进，每个切片完成后先更新进度文档，再执行下一切片。

### Slice A：配置和预算边界

预计修改：

- `spring-ai-rag-core/src/main/java/com/springairag/core/config/RagChatProperties.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/config/RagChatPropertiesValidator.java`
  （仅在需要增加跨字段校验时）
- `spring-ai-rag-core/src/main/resources/application.yml`
- `spring-ai-rag-core/src/main/resources/application-prod.yml`
- 对应配置与校验测试

要求：

- 新 getter/setter 默认值和边界有单测；
- 不复用 AGENT 配置名，不把新配置绑定到公开 Chat request DTO；
- `includeOriginal=true,maxQueries=1` 的有效变体数必须为 0，不能产生负数或空配置
  异常。

### Slice B：有界 expander 和 Chat factory

预计新增/修改：

- `spring-ai-rag-core/src/main/java/com/springairag/core/rag/BoundedMultiQueryExpander.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/ModeAwareChatClientFactory.java`
- 必要时 `spring-ai-rag-core/src/main/java/com/springairag/core/rag/ProjectDocumentRetriever.java`
  （只允许用于保持 context/trace 契约，不复制检索逻辑）

要求：

- builder 在创建 Spring AI `MultiQueryExpander` 前计算 effective variant count；
- effective variant count 为 0 时不构建 expander，避免无意义的扩展 ChatModel 调用；
- KNOWLEDGE trace 使用独立预算，AGENT trace 仍使用 AGENT 配置；
- wrapper 不丢失 authorized context、history 或 query metadata；
- 默认三路查询的 query count、retriever 调用次数和结果 join 顺序不变。

### Slice C：低基数 trace 诊断

预计修改：

- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/RetrievalTraceCollector.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/diagnostics/RetrievalTraceSession.java`
- 必要的 response/diagnostics tests

要求：

- wrapper 通过项目已有 trace context 记录计划摘要；
- Chat response metadata 和持久化 trace 使用同一字段；
- 默认关闭 query text 持久化时不泄漏 query variant；
- 并发检索完成顺序不影响整数/布尔摘要；
- trace metadata 保持有界，不能写入模型原文或完整异常堆栈。

### Slice D：测试、脚本和双语长青文档

预计修改/新增：

- `ModeAwareChatClientFactoryTest`
- `RagChatPropertiesValidationTest` 或新增 focused test
- `BoundedMultiQueryExpanderTest`
- `RetrievalTraceCollectorTest`、`RetrievalDiagnosticsServiceTest`
- `spring-ai-rag-core/src/test/java/com/springairag/core/integration/` 下的相关
  PostgreSQL 集成测试
- 现有 `scripts/verify-chat-capability.sh`，或新增一个只聚焦本功能且复用其隔离资源
  边界的 runner
- 双语 `docs/configuration*`、`docs/architecture*`、`docs/testing-guide*`、
  `docs/developer-reference*`、必要时 `docs/troubleshooting*`

要求：

- runner 不能覆盖同一 worktree 已存在的 `.dev` 状态或监听端口；
- 临时数据库、环境 overlay、日志和真实 key 清理边界必须明确；
- 前端不新增页面功能，但由于 Chat metadata/网络契约受影响，仍执行规定的前端门禁；
- 规划完成后，稳定行为才提升到双语长青文档，plan/progress 再归档。

## 6. 一次性验收矩阵

验收测试在实现前冻结，不能在 review 阶段发现一个问题就零碎补一条测试。以下矩阵是
实施阶段的一次性最低范围；若代码范围缩小，必须在 progress 中明确标为 N/A 及理由。

### 6.1 后端 focused/单元测试

至少覆盖：

1. 默认 properties：`variants=2, includeOriginal=true, maxQueries=3`；
2. `maxQueries=1` 的原始 query 直通且不调用 expansion model；
3. `variants=5,maxQueries=3,includeOriginal=true` 只请求生成两个变体；
4. `includeOriginal=false` 的预算计算；
5. expander 输出空白、重复、包含原始 query、顺序变化和超额时的确定性结果；
6. delegate 返回 null/异常时的原始 query fallback 和 `degraded` 摘要；
7. `KNOWLEDGE` 使用 knowledge budget，`AGENT` 仍使用 agent budget；
8. context/history/metadata 沿 expanded Query 保留；
9. response summary、persisted metadata 的 queryExpansion 字段不含原始 query；
10. rerank、citation、prompt budget 仍看到 join 后同一组 documents，默认顺序不变。

### 6.2 PostgreSQL/端到端后端集成

新增或扩展一个以真实 PostgreSQL/pgvector 和 Flyway 当前全量迁移为基础的测试夹具：

- 使用确定性 embedding stub 或现有测试 embedding，使原始 query 和两个变体可区分；
- 记录 embedding provider 调用次数、vector/full-text query 次数和最终 Chat retriever
  调用次数；
- `maxQueries=3` 只允许三次 query retrieval；
- `variants=5,maxQueries=3` 仍只允许三次，不允许第四至第六个 query 触达 SQL；
- 同一变体重复时不产生第二次 SQL；
- join 后 rerank、unique source、citation ID 和 prompt evidence 数量保持现有契约；
- 查询失败时其他 query 的结果仍返回，诊断 outcome/stage 与现有降级语义一致；
- 只读查询可验证迁移和 `rag_retrieval_logs.metadata` 中的摘要，没有新增 schema。

测试必须尽可能经过 Chat factory -> Spring AI advisor -> ProjectDocumentRetriever ->
HybridRetrieverService -> PostgreSQL，而不是只直接调用 wrapper。

### 6.3 Maven 和服务启动门槛

实现代码改动后先执行：

```bash
mvn clean compile test-compile
```

随后以 `postgresql` profile 启动隔离服务，确认：

- 服务能够启动并通过 `/actuator/health`；
- 配置绑定默认值正确；
- dummy provider 启动路径不因新配置缺失而失败；
- 无显式悲观锁或 advisory lock。

### 6.4 前端与 Mock Playwright

本轮不增加 WebUI 控件，但 Chat metadata 属于共享响应行为，不能只因为没有编辑
`spring-ai-rag-webui/` 就跳过前端门禁：

```bash
cd spring-ai-rag-webui
npx tsc -b --pretty false
npm run test:run
npm run build
npx playwright test e2e/chat.spec.ts e2e/streaming-upload.spec.ts
```

Playwright 只使用 DOM 可见性、可访问状态、网络请求/响应和 JSON 断言；不使用截图。
至少断言既有 Chat 页面行为、sources/citation 展示和新增 metadata 不会破坏响应解析。

### 6.5 隔离端口真实全栈

复用 `scripts/dev.sh` 的环境加载约定，使用非 `main` worktree 专用端口和可处置数据库：

- 后端默认使用 `18083`，前端使用 `15175`；已有端口被占用时直接失败，不覆盖现有栈；
- 先用 Mock/本地测试 provider 验证启动、Chat HTTP/SSE、metadata JSON 和 trace ID；
- 通过 real Playwright 的网络断言确认 KNOWLEDGE 请求返回 answer、sources、retrieval
  summary 和稳定的 retrieval trace；
- 通过 PostgreSQL 只读查询确认持久化 metadata 的字段存在、值有界且没有 query text
  泄漏；
- 退出时只清理本 runner 创建的进程、overlay 和数据库。

### 6.6 真实 LLM 验证

用户已允许在必要时使用 `.env` 中的真实 provider。执行顺序固定为：

1. Mock focused tests、PostgreSQL 集成、Maven 和前端门禁全部通过；
2. 再用真实 ChatModel 做少量有界 KNOWLEDGE smoke，观察后端日志和 provider 请求；
3. 验证默认三路请求、配置超预算时仍不超过三次检索，并确认原始精确词仍可在 sources
   或答案证据中出现；
4. 真实 LLM 失败、密钥不可用、模型不支持调用或网络不稳定时，保留明确失败证据，不
   把 Mock 结果冒充真实验证；
5. 不把 key、完整 prompt、完整用户问题或 provider token 写入文档和 Git。

## 7. 质量、延迟、回滚和风险边界

### 7.1 质量与性能判定

默认值不改变计划 query 数，因此主要风险来自 wrapper 去重和并发 trace 处理。验收必须
比较：

- goldenset 的 Hit Rate、MRR、Recall@K、nDCG；
- KNOWLEDGE sources 的 unique document count、citation 合法性和答案非空率；
- query retrieval count、embedding call count、vector/full-text SQL count；
- Chat 首 token/完整响应 p50/p95 和 retrieval stage p95；
- expansion model call count 与 response token；
- rerank candidate count、最终 prompt evidence 数量和 `maxResults` 契约。

成功标准不是单纯“调用次数更少”，而是默认质量不回退、超配置成本按预算收敛、没有
额外的默认远程调用。

### 7.2 回滚

实现以配置和内部 wrapper 为边界，推荐回滚顺序：

1. 将 `RAG_CHAT_KNOWLEDGE_MAX_RETRIEVAL_QUERIES=3` 恢复默认，保证现有三路行为；
2. 若 wrapper 或 metadata 有问题，暂时把 `query-transformer=none`，KNOWLEDGE 回到单
   query 检索，但这是诊断/应急配置，不是代码完成语义；
3. 代码回滚不需要数据库逆迁移，因为本轮不新增 schema；
4. 回滚后重新执行 PostgreSQL、Maven、前端、启动和适用真实 Chat 门禁，不能沿用回滚前
   结果。

### 7.3 主要风险与处理

| 风险 | 处理 |
|---|---|
| `maxQueries` 与 `includeOriginal` 语义混淆 | 在 properties、factory 和测试中固定公式；`max=1` 明确写入契约 |
| 生成的变体重复导致结果不足 | wrapper 精确去重；delegate 无有效结果时回退 transformed query |
| wrapper 丢失授权 context | 只使用 `Query.mutate()` 保留 history/context，并有单测断言 |
| KNOWLEDGE 与 AGENT 预算耦合继续存在 | collector 创建时按 mode 分支，增加两套配置/测试 |
| 并行完成顺序导致诊断不稳定 | 摘要只记录整数/布尔计划值；不把任一完成 query 当作唯一计划 query |
| 质量因少检索而下降 | 默认不减少；goldenset 和真实 Chat sources 作为硬证据 |
| 观测写入原始 query 或高基数标签 | 只写 bounded summary，复用现有 query redaction |
| 真实 runner 覆盖用户运行栈 | 启动前检查端口和 `.dev` 状态，使用独立 overlay/数据库 |

## 8. 规划检查与实施收敛规则

### 8.1 规划检查

规划完成后执行连续三轮、固定范围、只读的系统性检查：

1. 价值闭环、范围、默认值、query budget 公式、去重和回滚语义；
2. Spring AI advisor、Query context、授权、并发、AGENT 隔离、trace 和成本边界；
3. PostgreSQL 夹具、Maven/前端/真实运行验收矩阵、文档生命周期和 Git 交付。

发现内容错误、逻辑矛盾、关键缺失或不可实施设计时，立即修改 plan，计数归零，并在
progress 记录时间、范围、问题、措施和结果。措辞、格式、未来实施时自然出现的行号漂移
不触发重置。只有连续 `3/3` 且期间没有修改 plan 正文，才能进入实施。

### 8.2 实施收敛

代码实现完成后，先更新 progress，再执行基本集成硬门槛：

```text
本任务后端 focused/PostgreSQL 集成
  -> mvn clean compile test-compile
  -> 前端 tsc/test/build/核心 Mock Playwright
  -> 隔离端口真实全栈和 scripts/dev.sh
  -> 获准且必要的真实 LLM smoke
```

全部硬门槛通过后，执行三轮互不重叠的只读检查：

1. 预算、并发、失败恢复、授权 context 和 metadata 泄漏；
2. Spring AI/API/Chat/AGENT/前端兼容性、质量和延迟；
3. 测试证据、运行启动、文档、回滚、main 跟进和 Git 交付。

只修改影响正确性、成本安全、兼容性或数据一致性的缺陷；每次实质修复都重置计数并从
受影响门槛重跑。连续三轮无修改才允许结束。

## 9. Git/worktree 交付

本轮规划分支已经基于最新本地 `main` 创建。后续若实施本规划，必须：

1. 在当前专用 worktree/分支完成规划确认后再进入代码修改；
2. 开发期间定期 `git fetch origin`，如 `origin/main` 有新提交，先以 merge 方式合入
   特性分支，按合并后基线重新跑完整验收；
3. 不使用 `git stash`、`git reset --hard` 或覆盖其他协作者修改；
4. 本地 commit 后先 fetch/merge 上游，再 push；
5. 特性分支完成并验证后合入 `main`，推送 `main`；
6. 最终核对 `git status --short --branch`、本地 HEAD、`main`、`origin/main` 和远端
   特性分支，确认没有被本任务遗留的未提交修改。

本轮规划阶段的提交只包含规划归档/活动文档；生产代码实施必须在后续明确进入实施阶段
后进行，不能把规划提交误报为功能交付。

## 10. 实施恢复入口

中断后按以下顺序恢复：

1. 读取本文第 4、5、6、8、9 节和
   [NEXT_HIGH_VALUE_FEATURES_PROGRESS.md](NEXT_HIGH_VALUE_FEATURES_PROGRESS.md)；
2. 检查当前分支、worktree、`origin/main` 和工作区状态；
3. 如果规划检查不是 `3/3`，继续固定范围审查，不改动无关文档；
4. 如果已经 `3/3`，从 Slice A 开始，完成每个 Slice 后先写 progress；
5. 代码实现不得扩大到 Search、AGENT、Evaluation、权限或用量账本，除非先修改规划并
   重新执行规划三轮检查。
