# KNOWLEDGE 多查询证据合并优化实施规划

> 状态：实施与基本集成硬门槛完成，实现连续检查 `3/3` 完成，待 Git 交付
>
> 规划日期：2026-08-24
>
> 代码基线：本地 `main` / `origin/main` @ `7c7d846b`
>
> 实施分支：`feat/knowledge-evidence-joiner-20260824`
>
> worktree：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> 配套进度：[NEXT_HIGH_VALUE_FEATURES_PROGRESS.md](NEXT_HIGH_VALUE_FEATURES_PROGRESS.md)
>
> 近距离上下文：[项目上下文](../project-context-zh-CN.md)、
> [架构说明](../architecture-zh-CN.md)、[配置参考](../configuration-zh-CN.md)、
> [Chat、RAG 与工具调用](../chat-memory-rag-tool-calling-zh-CN.md)、
> [测试指南](../testing-guide-zh-CN.md)、[交付工作流](../delivery-workflow-zh-CN.md)

本文是本轮实施的自包含入口。它只规划一个小范围、可独立交付的 KNOWLEDGE 检索质量
改进，不引入外部客户项目背景，不把权限、用量账本或 Collection 覆盖模式带入本轮。
规划完成并连续三轮检查无实质问题后，按本文一次性实施；中断后可从本文和配套进度文档
恢复，不需要重新猜测核心契约。

## 1. 执行摘要

KNOWLEDGE 模式当前使用 Spring AI `RetrievalAugmentationAdvisor`。查询转换和有界多查询
扩展完成后，advisor 默认使用 `ConcatenationDocumentJoiner` 合并各个查询的文档：

```text
原始/转换 query
  -> 有界查询扩展
  -> 每个 query 独立调用 ProjectDocumentRetriever
  -> Spring AI 默认 joiner：按 Document.getId 去重，保留第一次出现的对象
  -> 按 Document.score 降序排列
  -> ProjectRerankPostProcessor
  -> PromptBudgetDocumentPostProcessor
  -> citation query augmenter
```

项目的 `RetrievalDocumentMapper` 为每个检索 chunk 设置稳定的
`documentId:chunkIndex` 作为 `Document.id`，所以默认 joiner 能够去除同一 chunk 的
重复结果。但是默认策略还有两个生产质量问题：

1. 同一 chunk 被不同查询命中时，默认 joiner 保留第一次出现的对象，而不是得分最高的
   对象。不同查询的向量相似度、全文分数和融合分数可能不同，后续 rerank 看到的分数
   可能不是该 chunk 在本次查询集合中的最佳证据分数。
2. `RetrievalAugmentationAdvisor` 内部使用普通 `HashMap` 收集查询结果，不能把 Map
   遍历中的“第一次出现”当作稳定业务顺序。默认 joiner 的 tie 行为和保留对象受内部
   Map 顺序影响，使多查询结果在相同分数时不够可解释。

本轮增加项目自有的 `ProjectDocumentJoiner`，仅替换 KNOWLEDGE advisor 的 joiner：

```text
每个 query 的 Document 列表
  -> 按 query 文本建立不记录内容的规范遍历顺序
  -> 以稳定 Document.id 合并
  -> 同一 id 保留有限最高 score 的对象
  -> 统计低基数 join 摘要
  -> 按 score 降序、id 稳定次序输出
  -> 既有 rerank / prompt budget / citation 链路
```

设 query 数为 `q`、候选文档总数为 `n`、唯一输出数为 `u`，这是一项本地
`O(q log q + n + u log u)` 的确定性处理；`q` 已默认限制为 3，`n` 受每次检索结果
上限约束。本轮不增加 embedding、数据库、rerank provider 或 Chat 模型调用。它直接
改善多查询 KNOWLEDGE 的输入证据质量，并在重复较多时减少后续 rerank 和 prompt
处理的重复对象。AGENT、直接 Search、JSON record、Evaluation 和 legacy advisor
不改变。

## 2. 为什么本轮实施这个功能

### 2.1 当前证据

已核对以下代码和正式文档：

- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/ModeAwareChatClientFactory.java`
  构造 KNOWLEDGE advisor，并当前未显式设置 `DocumentJoiner`。
- `spring-ai-rag-core/src/main/java/com/springairag/core/rag/ProjectDocumentRetriever.java`
  将每个查询路由到授权后的混合检索器。
- `spring-ai-rag-core/src/main/java/com/springairag/core/rag/RetrievalDocumentMapper.java`
  将 `RetrievalResult` 映射为稳定的 `Document.id=documentId:chunkIndex`，并把 score 和
  其他允许的 metadata 带入 Document。
- `spring-ai-rag-core/src/main/java/com/springairag/core/rag/ProjectRerankPostProcessor.java`
  在 join 之后把 Document 转回 `RetrievalResult`，所以 join 时保留哪一个重复对象会
  影响后续 rerank 的输入。
- `spring-ai-rag-core/src/main/java/com/springairag/core/rag/BoundedMultiQueryExpander.java`
  已将 KNOWLEDGE 查询总数限制在 `rag.chat.knowledge.max-retrieval-queries`，因此本轮
  join 输入有明确上界，不会产生无界 O(n) 工作。
- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/RetrievalTraceCollector.java`
  已提供 attempt-local、线程安全的低基数检索摘要，适合记录 join 结果而不记录 query
  文本。
- `spring-ai-rag-core/src/main/java/com/springairag/core/diagnostics/RetrievalTraceSession.java`
  负责把 attempt 摘要持久化到 retrieval diagnostics；若 `documentJoin` 需要同时出现在
  Chat response 和持久化 trace 中，必须像 `queryExpansion` 一样显式向父 session 传播。
- Spring AI 1.1.8 的 `ConcatenationDocumentJoiner` 源码确认：以 `Document.getId` 去重，
  重复时保留 existing，随后只按 `Document.score` 降序排序；advisor 先按 expanded
  query 顺序等待异步检索完成，再收集到普通 `HashMap`，所以完成顺序不是问题，但 Map
  遍历顺序不能用作稳定 tie-break。

稳定事实和现有调用链见[架构说明](../architecture-zh-CN.md)与[Chat、RAG 与工具调用](../chat-memory-rag-tool-calling-zh-CN.md)；
本文不复制这些文档的完整内容。

### 2.2 候选项比较

| 候选 | 质量收益 | 延迟/成本 | 实施风险 | 本轮决定 |
|---|---|---|---|---|
| KNOWLEDGE 项目自有证据 joiner | 同一 chunk 选择最佳分数，结果排序更稳定，减少重复后续处理 | 本地有界排序与合并，无新远程调用 | 仅替换一个 advisor 组件，边界可单测 | 实施 |
| 跨请求检索结果缓存 | 可降低重复 Search/Chat 的数据库延迟 | 需要 mutation/Collection/profile 失效语义，容易返回旧证据 | 高于本轮合理范围 | 延后 |
| Search/AGENT/Evaluation 全入口多查询扩展 | 可能提升召回 | 增加 embedding/SQL 和调用预算 | 跨契约，需独立质量基线 | 延后 |
| 自适应向量/全文权重 | 可能提升特定 query 的召回 | 需要更多 goldenset 和稳定语言/意图判定 | 默认行为变化较大 | 延后 |
| `EACH_COLLECTION` 覆盖召回 | 解决特定多知识库覆盖问题 | bounded fan-out、融合和延迟显著增加 | 当前没有产品需求证据 | 保持 backlog |
| 权限、OIDC、用量账本 | 不直接改善当前核心目标 | 跨安全、schema 和运营 | 不符合当前优先级 | 不实施 |

### 2.3 价值边界

本轮的成功不是“所有重复结果都被强行合并”，而是：

- 对同一稳定 chunk identity 只保留一个候选；
- 重复候选中保留有限最高分对象及其对应 metadata；
- 让后续 rerank、prompt budget 和 citation 只处理唯一 chunk；
- 结果排序不依赖查询完成先后；
- 不扩大任意调用方的检索数量，不引入新的模型调用；
- 用低基数摘要证明 join 确实发生，不写入 query、文档正文或异常堆栈。

## 3. 冻结的范围

### 3.1 生产代码范围

实施只允许触及以下生产边界：

1. 新增 `com.springairag.core.rag.ProjectDocumentJoiner`，实现 Spring AI
   `DocumentJoiner`；该类无状态、无外部依赖，不注册额外 Spring bean。
2. `ModeAwareChatClientFactory` 内部持有并复用该 joiner，在
   `buildKnowledgeAdvisor(...)` 显式设置；保持 factory 现有 public 构造器不变。
3. 在 `RetrievalTraceCollector` 增加 join 摘要的线程安全记录和输出。
4. 在 `RetrievalTraceSession` 增加 attempt 级 join 摘要传播，使持久化 diagnostics 与
   Chat response 的低基数摘要一致。
5. 为上述行为补充单元测试、Chat 集成测试和必要的现有多查询测试断言。

不新增 Flyway 迁移，不修改 API 请求字段，不修改 Search/AGENT/Evaluation 的公开语义，
不增加新的配置项，不增加新的远程调用，不修改数据库查询，不修改 rerank provider SPI。

### 3.2 文档范围

行为变化完成后同步双语长青文档：

- `docs/architecture.md` 与 `docs/architecture-zh-CN.md`：KNOWLEDGE join 顺序和最佳
  score 保留语义；
- `docs/chat-memory-rag-tool-calling.md` 与对应中文文档：多查询证据合并与 AGENT
  非适用边界；
- `docs/rest-api.md` 与 `docs/rest-api-zh-CN.md`：Chat response 的
  `metadata.retrieval.documentJoin` 低基数摘要；
- `docs/testing-guide.md` 与对应中文文档：join 单测、Chat Mock/真实模式证据；
- `docs/quality-defaults.md` 与对应中文文档：重复 chunk 合并对质量和延迟的预期；
- 必要时更新 `docs/troubleshooting*`，但不为没有新故障边界的内容制造重复段落。

规划和进度文档保持单语；完成后移动到
`docs/drafts/archive/2026-08-24_*`，仅把仍有效的行为提升到上述双语长青文档。

## 4. 冻结的行为契约

### 4.1 稳定 identity

- 首选 `Document.getId()` 作为 join identity。
- 项目生产映射的 ID 是 `documentId:chunkIndex`，因此同一逻辑 chunk 在多个查询中只
  出现一次。
- Spring AI `Document` 的正常构造已保证 ID 非空；joiner 仍防御 mock、反射或未来
  非标准实现返回 null/blank 的情况，不把这些对象互相合并，每个无身份对象按规范输入
  位置作为唯一 key 保留。
- 不按标题、正文、source 或 metadata 推测业务身份，避免把不同文档的相同文本错误合并。

### 4.2 最佳候选选择

对同一 identity 的重复对象：

1. 有限数值 score 优先于 null、NaN 或无穷 score；
2. 两个 score 都有限时，保留较高 score；
3. joiner 先按 `Query.text()` 字典序遍历 query，再保持每个 query 的 source-list 与
   document 原顺序。项目 wiring 中，`BoundedMultiQueryExpander` 已去除空白和精确
   重复文本；无 expander 时只有一个 query，因此生产输入的 query 文本唯一。分数相等
   时保留该规范遍历中的先遇对象；
4. 同一 identity 的候选都为 null、NaN 或无穷 score 时，保留规范遍历中的首个对象，
   无效分数之间不触发 `scoreReplacements`；
5. 不重新计算 score，不混合 vectorScore/fulltextScore，不拼接两个 Document 的 metadata；
6. `duplicateDocumentsRemoved = inputCount - uniqueCount`；
7. `scoreReplacements` 只记录无效分被有限分替换，或较低有限分被较高有限分替换的次数。

“先遇到”只用于分数完全相等的 tie。它不采用原始 `HashMap` 遍历顺序，也不记录 query
文本。joiner 不作为通用公开 SPI 暴露；若未来绕过项目 expander 输入重复 query 文本，
必须先在调用方恢复唯一文本不变量。正常的不同分数重复项始终选择最高有限分数。项目
生产 mapper 保证同一
`Document.id` 表示同一 chunk，并保持 `Document.score` 与 metadata 中的 `score`
一致；joiner 只返回完整的选中对象，不跨对象拼接字段。

### 4.3 输出排序

- 先按有效 score 降序；
- 所有有限 score 文档排在 null、NaN 和无穷 score 文档之前，即使有限 score 为负数；
- 有限 score 相等时按非空 `Document.id` 的字典序稳定排序；
- 同分时有非空 ID 的文档排在无身份文档之前；
- 两个无身份对象按其输入位置稳定排序；
- null、NaN 和无穷 score 之间不比较数值，按前述 ID/输入位置规则稳定排序；
- 不在 joiner 中执行最终 `maxResults` 截断，保留现有 rerank 和 prompt budget 的职责；
- 不改变已有 Document 对象的 ID、文本、metadata 或 score，只返回选中的对象引用。

### 4.4 低基数诊断

`RetrievalTraceCollector` 增加可选 `documentJoin` 摘要：

```json
{
  "inputDocuments": 9,
  "uniqueDocuments": 6,
  "duplicateDocumentsRemoved": 3,
  "scoreReplacements": 2
}
```

约束：

- 只记录整数；
- 不记录 query、Document.id、正文、metadata 值或模型输出；
- 每个 KNOWLEDGE attempt 最多记录一次最终 join 摘要；
- 同一摘要同时进入 Chat response 的 `metadata.retrieval.documentJoin` 与持久化
  retrieval trace 对应 attempt 的 `documentJoin`；
- 没有显式项目 join 的 AGENT、Search、Evaluation 和 legacy advisor 不应凭空出现该字段；
- 若未来同一 trace 有多次 join，采用最后一次摘要并保持字段低基数；本轮实现只会发生一次。

## 5. 实施切片

### Slice A：规划冻结与测试设计

- 完成本文连续三轮固定范围检查；
- 在进度文档记录分支、基线、验收矩阵和恢复入口；
- 在代码修改前确认工作区干净、`origin/main` 未产生新提交；
- 先准备一次性测试矩阵，不采用“review 发现一个点再补一个测试”的方式。

### Slice B：joiner 与 trace

- 新增 `ProjectDocumentJoiner`；
- 增加 `RetrievalTraceCollector.recordDocumentJoin(...)`、摘要读取和 thread-safe 替换；
- 增加 `RetrievalTraceSession.recordDocumentJoin(...)` 和 attempt metadata 输出；
- 为 null/blank identity、重复低分、高分替换、相同分数、null/non-finite score 编写
  完整单测；
- 断言多个无效分数只保留规范首个对象，且不会增加 `scoreReplacements`；
- 测试不同 `HashMap` 插入顺序、匿名/有身份同分、负有限分与无效分数，证明输出和选中
  对象确定；
- 测试复杂度使用有界 query 数、候选上限和确定性结果，不使用脆弱的墙钟阈值。

### Slice C：KNOWLEDGE wiring

- 在 `ModeAwareChatClientFactory` 内部持有无状态项目 joiner，不新增构造参数；
- 在 `RetrievalAugmentationAdvisor.Builder` 调用 `.documentJoiner(...)`；
- 增加一个多查询测试：同一 `Document.id` 由两个 query 返回不同 score，断言
  rerank post-processor 只看到最高分对象，且 trace 有 join 摘要；
- 增加 Chat result metadata 与 `RetrievalTraceSession.toMetadata(false)` 断言，证明
  response/persisted attempt 两条输出路径都包含同一低基数摘要且不含 query 或文档 ID；
- 增加非 KNOWLEDGE 边界测试或复用现有 AGENT/Search 测试，证明新摘要不会污染其他模式。

### Slice D：长青文档与专项验收资产

- 双语同步架构、Chat、REST metadata、质量默认和测试指南；
- 如现有 Chat capability runner 有固定 metadata 断言，扩展其 JSON 断言；
- 不使用截图作为前端验收证据；
- 更新进度文档后再进入硬门槛。

### Slice E：基本硬门槛、三轮实现审查与 Git

- 先完成全部基本硬门槛，再执行三轮限定范围只读审查；
- 任何实质代码修复都把实现审查计数归零，并重跑受影响门槛；
- 基于最新 `origin/main` merge（若发生变化），按 merge 后基线重新完整验收；
- 本地 commit、fetch/merge、push 特性分支；
- 将特性分支 merge 到本地 `main`，推送 `origin/main`，确认两个 worktree 状态。

## 6. 一次性验收矩阵

### 6.1 后端

必须执行：

```bash
mvn clean compile test-compile
```

相关测试至少包括：

- `ProjectDocumentJoinerTest`
- `RetrievalTraceCollectorTest`
- `ModeAwareChatClientFactoryTest`
- `BoundedMultiQueryExpanderTest`
- `HybridRetrieverRrfPostgresIntegrationTest`
- Chat capability 相关后端 focused tests

还需执行：

- PostgreSQL/pgvector 相关集成矩阵，确认真实迁移和既有检索链路未退化；
- 全量 Maven test；
- `./scripts/verify-no-pessimistic-locks.sh`；
- `./scripts/verify-project-docs.sh`；
- `git diff --check`。

本轮不新增 schema，但 PostgreSQL 门槛仍必须通过，因为 join 位于真实 Chat 检索调用链
的数据库结果之后，不能只靠孤立单测报告完成。

### 6.2 前端与 Mock Chat

虽然不修改 WebUI 源码，Chat response metadata 和真实后端 JSON 仍是共享契约，必须执行：

- WebUI TypeScript；
- WebUI Vitest；
- WebUI production build；
- 核心 Mock Playwright；
- 只用 DOM 可见性、可访问状态、网络请求/响应和 JSON 断言；
- 不使用截图作为通过证据。

Mock Playwright 至少验证：

1. 既有 KNOWLEDGE Chat 页面仍能发送并渲染回答、来源和 citation；
2. additive response metadata 不破坏现有 response 解析、历史恢复与 AGENT/PLAIN 流程。

`retrieval.documentJoin` 的字段类型、隐私和持久化一致性由后端 Chat 集成测试断言；
WebUI 当前不渲染该诊断对象，不为本轮制造只检查 mock fixture 自身的前端断言。

### 6.3 隔离端口真实运行

在非 main worktree 使用隔离端口和可处置数据库，优先使用项目既有一键脚本和真实配置：

- 后端/前端启动方式遵循 `docs/developer-reference*`；
- 使用 `.env` 文件路径加载真实 provider 配置，不打印、不复制密钥；
- 使用真实 Chat KNOWLEDGE 请求，验证：
  - 服务健康；
  - 多 query 路径可执行；
  - `documentJoin` 摘要结构正确；
  - sources/citation 仍与最终证据一致；
  - 回答非空且没有 invalid citation；
  - 没有额外的默认模型调用路径；
- 真实阶段期间持续查看日志，认证、模型、协议或超时错误立即记录为失败；
- 只做 DOM/网络/JSON/日志/数据库只读证据，不截图验收。

真实 LLM 调用前先通过 Mock 和 PostgreSQL 流程测试。若 `.env` 的 key 不可用，必须明确
报告外部环境限制，不能把 Mock 结果冒充真实 provider 通过。

### 6.4 质量和性能证据

本轮不承诺固定百分比的线上质量提升。验收应证明：

- 相同 chunk 的重复输入只保留一个；
- 较高 score 被保留；
- join 输出数量不超过唯一 identity 数；
- rerank 和 prompt budget 不再处理重复 Document；
- join 是 query 数与候选数均有界的本地操作，无额外远程调用；
- 既有 retrieval goldenset / quality regression 不回退；
- 真实 Chat sources/citation 合法。

不使用 `<1ms` 等脆弱墙钟断言；以算法边界、调用计数、JSON 结果和已有质量回归为主，
必要时记录阶段 p95 作为观察数据。

## 7. 非目标、风险与回滚

### 非目标

- 不实现跨请求检索缓存；
- 不改变 Search、AGENT、JSON record、Evaluation 或 legacy advisor；
- 不改变 `maxResults`、rerank candidate limit、citation ID 分配或文档权限；
- 不新增数据库表、迁移、索引、租户或 token/cost 账本；
- 不引入新的 LLM、embedding、rerank provider 或前端控制项；
- 不把相同正文但不同 `Document.id` 的文档当成重复项。

### 风险与处理

| 风险 | 处理 |
|---|---|
| Document.id 缺失导致错误合并 | 正常 Spring Document 不允许空 ID；防御性无身份对象按规范输入位置独立保留 |
| 分数 null/NaN/Infinity 破坏排序 | 所有有限 score 优先；非有限值不按 0 与负有限分混排 |
| 高分对象 metadata 与正文不一致 | 只保留同一 Document 对象，不跨对象拼装字段 |
| advisor Map 遍历造成同分对象不稳定 | 先建立 query 与列表位置的规范遍历顺序；不同分数按最高分，同分按规范顺序保留 |
| join 摘要泄露高基数内容 | 只输出四个整数 |
| response 摘要与持久化 trace 分叉 | collector 每次更新时同步父 session，并测试两条输出路径 |
| 新 joiner 误影响 AGENT/legacy | 仅在 KNOWLEDGE advisor builder 显式注入，并有模式边界测试 |
| 为无状态 joiner 改动 public 构造器 | 由 factory 内部持有实例，不增加 Spring bean 或构造参数 |
| join 后结果变少 | 只去掉同一 ID 的重复项，不做额外 top-k 截断；既有 rerank/prompt budget 继续负责上限 |

### 回滚

1. 代码回滚为默认 `RetrievalAugmentationAdvisor` joiner 即可；
2. 不需要数据库逆迁移；
3. 回滚后重新执行相关后端、Maven、前端共享契约、真实 Chat 和文档门禁；
4. 不以回滚前的测试结果替代回滚后的最终结论。

## 8. 规划检查与完成定义

### 规划检查

实施前连续三轮、固定范围、只读检查：

1. 价值、范围、identity、最佳 score、排序和回滚语义；
2. Spring AI advisor、并发、授权上下文、trace、模式边界、API 兼容性和成本；
3. 测试矩阵、PostgreSQL/真实运行、前端共享契约、双语文档、Git 交付和恢复入口。

发现影响正确性、成本安全、兼容性、数据一致性或可实施性的实质问题，立即修改本文并
将计数器归零；格式、措辞、自然产生的行号漂移不触发归零。无问题轮次不修改本文。
只有连续 `3/3` 且规划正文未被修改，才开始 Slice B。

### 完成定义

只有以下条件全部满足才算本轮完成：

1. 规划和进度文档可恢复，规划检查达到 `3/3`；
2. KNOWLEDGE join 行为有单元、Chat 集成和必要 PostgreSQL/真实运行证据；
3. `mvn clean compile test-compile`、相关测试和全量 Maven 通过；
4. WebUI TypeScript、Vitest、production build 和核心 Mock Playwright 通过；
5. 必要真实 provider Chat smoke 通过或明确记录环境限制；
6. 双语长青文档同步，项目文档门禁通过；
7. 实现检查达到连续 `3/3`，期间没有未经重验的实质修改；
8. 特性分支提交、推送，并已 merge 到 `main`，`main` 合入后完整复验通过；
9. 最终核对本地/远端 HEAD、两个 worktree 和工作区状态。
