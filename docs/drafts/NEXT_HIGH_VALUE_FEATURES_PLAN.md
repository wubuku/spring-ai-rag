# rerank 后文档级证据去冗余实施规划

> **状态**：规划内容已冻结；尚未开始生产代码实施
>
> **规划日期**：2026-08-23
>
> **代码基线**：本地 `main` / `origin/main` @ `0fd37b6d`
>
> **实施分支**：`docs/next-retrieval-quality-plan-20260823`
>
> **worktree**：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> **配套进度**：[NEXT_HIGH_VALUE_FEATURES_PROGRESS.md](NEXT_HIGH_VALUE_FEATURES_PROGRESS.md)
>
> **近距离上下文**：[项目上下文](../project-context-zh-CN.md)、
> [架构说明](../architecture-zh-CN.md)、[配置参考](../configuration-zh-CN.md)、
> [质量默认值](../quality-defaults-zh-CN.md)、[测试指南](../testing-guide-zh-CN.md)、
> [交付工作流](../delivery-workflow-zh-CN.md)

本文是下一轮小范围功能的自包含实施入口。上一轮“有界 rerank 候选池”已经合入
`main`，其规划和进度已归档为
`docs/drafts/archive/2026-08-23_NEXT_HIGH_VALUE_FEATURES_*`，稳定事实已经同步到双语
架构、配置、质量与故障排查文档。本轮只处理一个直接影响检索质量、Chat 证据质量和
上下文利用率的问题，不引入权限、用量账本、数据库迁移或新的远程模型调用。

## 1. 执行摘要

当前 rerank 已能在默认 20 个候选中选择最终 top N，但最终结果仍以 **chunk** 为单位。
一个长文档通常按 `1000` 字符、`100` 字符 overlap 分成多个 chunk；当这些相邻 chunk
都与查询高度相关时，它们可能连续占据最终 top N：

```text
rank 1 -> document A / chunk 3
rank 2 -> document A / chunk 4
rank 3 -> document A / chunk 5
rank 4 -> document B / chunk 0
rank 5 -> document C / chunk 1
```

这在 chunk 相关性上可能合理，但对最终证据集合不一定最优：

- Search 的前几项重复来自同一文档，文档覆盖不足；
- `KNOWLEDGE` 把相邻、重叠文本重复放入 Prompt，浪费 RAG token budget；
- `AGENT` 工具输出和 citation budget 被同一文档的多个 chunk 占用；
- Evaluation 按稳定文档身份计分，重复 chunk 不增加 Recall@K；
- HTTP cross-encoder 已经为有界候选付出请求成本，却可能把最终名额集中在一个文档。

本轮在共享 `ReRankingService` 中增加一个 **保持 provider 排名的文档级去冗余选择器**：

```yaml
rag:
  rerank:
    preferred-max-chunks-per-document: 2
```

处理顺序固定为：

```text
有界候选池
  -> provider 对候选给出完整有序排名
  -> 第一遍按 documentId 优先最多保留 2 个 chunk
  -> 若结果不足 maxResults，再按 provider 原排名回填被跳过的 chunk
  -> 最终仍不超过 maxResults
```

默认第一遍优先每文档最多 `2` 个 chunk，是对“文档覆盖”和“同一长文档的连续信息”之间
的保守平衡。只有候选中存在足够替代证据时，该偏好才会改变最终集合；若可用文档不足，
会回填同文档 chunk，因此选择器不会把 provider 已返回的可用结果进一步减到
`min(finalLimit, providerRankedCount)` 以下。该值是**多样化第一遍的软上限**，不是最终
列表的绝对每文档上限。最终输出始终是 provider 排名的子序列，保持原有 score 顺序、
稳定身份、citation 顺序和 provider 权威性。

## 2. 为什么本轮选择这个功能

### 2.1 候选功能比较

本轮探索过以下候选：

| 候选 | 质量收益 | 延迟/成本 | 实施范围 | 本轮决定 |
|---|---|---|---|---|
| rerank 后文档级证据去冗余 | 直接改善文档覆盖、Prompt 证据利用率和 citation 多样性 | 本地 O(candidate)；HTTP 只增加有界响应排名深度 | 共享 rerank 服务、配置、测试和文档 | **实施** |
| Search/AGENT/Evaluation 统一多查询扩展 | 可能改善召回 | 增加 embedding/SQL，LLM 扩展还增加模型调用 | 跨入口查询契约、缓存、诊断和预算 | 延后 |
| `EACH_COLLECTION` 保底召回 | 特定多知识库场景有价值 | bounded fan-out 和更高延迟 | 新公开 API、融合和 WebUI | 继续作为非紧急 backlog |
| 权限/OIDC、用量账本 | 不直接改善当前质量与速度 | 高复杂度 | 跨安全/数据模型 | 不进入本轮 |

跨入口 query expansion 的价值依赖额外 embedding、SQL 或 Chat 模型调用，并且
`KNOWLEDGE`、`AGENT`、Search 和 Evaluation 当前编排不同。它应在有独立延迟预算和
质量数据后单独规划。本轮去冗余只消费上一轮已经有界的候选列表，不增加数据库查询、
embedding 调用或 Chat LLM 调用，收益和风险更容易证明。

### 2.2 现有 diversity 分数为什么不够

`HeuristicRerankProvider` 已有文本 Jaccard diversity 分数，但它：

1. 只作为 relevance/原始分数旁边的一个软加权项；
2. 不知道“同一逻辑文档”的身份，只比较 chunk 文本；
3. 对中文无空格文本的词集合效果有限；
4. 只影响 heuristic provider，HTTP provider 不共享这一策略；
5. 不能保证最终 top N 在有替代候选时获得更多文档覆盖。

因此本轮不继续调大 `diversity-weight`，而是在 provider 排名之后增加轻量、确定性的
共享选择规则。provider 仍决定候选相关性顺序，选择器只在同一文档已经占用过多名额时
跳过后续 chunk。

## 3. 当前代码基线与调用链

### 3.1 已交付的候选池

`HybridRetrieverService.searchInScopeDetailed(...)` 当前：

- 先得到调用方最终 `maxResults`；
- 仅在请求 rerank、全局 rerank 和有效 provider 同时启用时，把召回上限扩大为
  `max(maxResults, rag.rerank.candidate-limit)`；
- 默认 candidate limit 为 `20`，绑定范围为 `1..100`；
- hybrid 向量/全文分支各维持候选上限的 `2x` 召回，再用 weighted RRF 融合；
- 共享检索器不执行 rerank，由各上层调用 `ReRankingService`。

因此本轮已有一个天然、安全的选择池，不需要再次扩大 SQL limit。

### 3.2 当前 provider 契约

`ReRankingService.rerank(query, results, maxResults)` 当前把最终 `maxResults` 直接传给
provider：

- heuristic provider 排序后立刻 `.limit(maxResults)`；
- HTTP provider 请求体的 `top_n` 也是最终 `maxResults`；
- no-op provider 只截断原列表；
- service 最后防御性截断 provider 的超量输出。

如果只在当前 provider 输出之后做文档 cap，被 provider 提前截掉的候补文档已经不可见，
无法把它们换入最终 top N。因此本轮必须把“provider ranking depth”和“最终 caller limit”
分开，但仍保持候选池上限不变。

### 3.3 必须保持一致的调用链

| 路径 | rerank 调用位置 | 本轮期望 |
|---|---|---|
| Chat `KNOWLEDGE` | `ProjectRerankPostProcessor` | 最终 Prompt/citation 使用去冗余后的结果 |
| Chat `AGENT` | `KnowledgeSearchTool` | Tool JSON、trace 和 citation 使用同一结果 |
| Search POST | `RagSearchController` | 公开 JSON 直接体现文档覆盖 |
| JSON record Search | `JsonRecordService` | record 通常单 chunk，不改变稳定身份或 payload enrich |
| Evaluation | `EvaluationCaseExecutor` | document-level Recall/NDCG 反映真实生产选择 |
| 旧 Advisor | `RerankAdvisor` | 继续复用共享服务；不改变 advisor 顺序 |
| GET Search / rerank disabled | 不调用有效 rerank | 完全不受影响 |

所有 core 生产调用方已经集中调用 `ReRankingService`，因此本轮不在各 Controller/tool
复制算法。独立的 `demos/demo-component-level` 不属于根 Maven reactor；基线实测其手动组装
源码已有 5 个与当前 core API 漂移相关的编译错误，其中只有一个涉及早已移除的
`ReRankingService(RagProperties)` 构造器。本轮不扩大范围修复该 demo，也不新增或恢复旧
构造器；通过保持 `RerankProvider` 方法签名和 core Spring 构造方式不变，确保本功能不引入
新的 consumer 破坏。demo 整体修复应作为独立维护任务处理。

## 4. 冻结的行为契约

### 4.1 配置

新增：

```yaml
rag:
  rerank:
    preferred-max-chunks-per-document: ${RAG_RERANK_PREFERRED_MAX_CHUNKS_PER_DOCUMENT:2}
```

配置语义：

- 默认值：`2`；
- 绑定范围：`0..100`；
- `1`：优先最大化不同文档覆盖；
- `2`：默认保留同一长文档的连续/互补证据；
- `0`：明确关闭文档多样化选择，并恢复当前 provider top N 行为；
- `1..100`：启用第一遍软上限；`1` 最大化不同文档覆盖，`100` 允许受管候选池中的同一
  文档占满全部候选；
- 候选中没有足够替代文档时，第二遍可以超过该值回填同文档 chunk，保证选择器输出数量
  为 `min(finalLimit, providerRankedCount)`；
- 它不是公开请求参数，外部调用方不能按请求放大或关闭；
- 只在全局 rerank 有效时参与最终选择；rerank 关闭时不改变纯检索结果。

不增加额外 boolean flag。`0` 是独立于候选数量的可靠关闭值，避免使用 `100` 这类依赖
当前 candidate limit 上界的近似回滚。

### 4.2 provider ranking depth

共享服务计算：

```text
finalLimit =
  positive maxResults
    ? maxResults
    : positive rag.rerank.top-n
      ? top-n
      : candidate count

selectorActive =
  rerank enabled
  && normalized provider.getName() not in {none, noop, off}
  && preferredMaxChunksPerDocument > 0
  && preferredMaxChunksPerDocument < finalLimit
  && candidateLimit > finalLimit
  && candidate count > finalLimit

providerCandidates =
  selectorActive
    ? first min(candidate count, candidateLimit) candidates
    : all candidates

rankingDepth =
  selectorActive
    ? providerCandidates count
    : finalLimit
```

然后调用：

```text
provider.rerank(query, providerCandidates, rankingDepth)
```

约束：

- selector active 时，service 防御性地把 provider 输入和 `rankingDepth` 一起限制到
  `candidate-limit <= 100`；标准检索调用链已经返回该有界候选池，因此不会再次丢弃候选；
- selector 是否启用以工厂实际选出的 `provider.getName()` 为准，而不是重新解释原始配置；
  这样 `api`/`siliconflow`/`remote` 别名、未知名称回落 heuristic，以及测试或手动注入的
  no-op provider 都与真实执行对象保持一致；
- HTTP provider 的 `documents` 与 `top_n` 都只覆盖上述有界 provider candidates；
- heuristic provider 只对同一批候选完整排序，额外成本为最多 100 项的内存排序；
- cap=`0`、cap 大于等于 final limit、候选数不超过 final limit、
  `candidate-limit <= final limit`、provider=`none/noop/off` 或全局 rerank disabled 时，
  provider 仍接收原候选和原来的 final limit，不扩大 HTTP 返回或本地排序深度；
- 这层 service 防御使旧 Advisor、测试替身或未来直接调用方即使传入异常大的列表，也不会让
  本功能把远程 rerank 请求体扩大到 `candidate-limit` 之外；
- provider 返回少于 `rankingDepth` 时不伪造结果，也不从未排名的原始候选补齐；
- 因此 provider 自身欠量时最终结果可以少于 final limit；数量不变量只保证选择器不会
  在 provider 已返回结果的基础上继续减量；
- provider 返回 `null` 仍抛出明确异常；
- provider 返回超过 `rankingDepth` 时先防御性截断到 ranking depth。

### 4.3 两阶段文档选择

选择器输入是 provider 已排序结果，输出规则如下：

1. 若 selector 未启用、输入为空、`finalLimit <= 0` 或输入数量不超过 final limit，
   沿用已有边界语义；
2. 第一遍按 provider 顺序扫描：
   - 非空 `documentId` 作为逻辑文档身份；
   - 非空身份使用原始字符串的精确相等比较，不做 trim、大小写折叠或其他规范化；
   - 同一身份优先最多选择 `preferredMaxChunksPerDocument` 项；
   - `documentId` 为 null/blank 时不与其他结果合并，每项按独立候选处理；
   - 达到 final limit 立即结束；
3. 若第一遍不足 `min(finalLimit, providerRankedCount)`，再按 provider 顺序扫描被跳过的
   结果，直到达到该数量；
4. 最终按 provider 原始 index 输出所有已选择项。

第 4 步保证结果始终是 provider 排名的子序列。即使第二遍回填较早被跳过的高分 chunk，
它也会回到原本的 rank 位置，不会出现低分结果排在高分结果之前。

示例一，有足够替代文档：

```text
provider: A0, A1, A2, B0, C0
final=4, cap=2
output:   A0, A1, B0, C0
```

示例二，替代文档不足：

```text
provider: A0, A1, A2, B0
final=4, cap=2
first pass: A0, A1, B0
backfill:   A2
output:     A0, A1, A2, B0
```

provider 已返回 4 项，因此结果数量仍为 4，且顺序与 provider 一致。

### 4.4 分数、身份和 metadata

- 不重算、不归一化、不伪造 `score`；
- 选择器直接复用 provider 返回的 `RetrievalResult` 对象，不复制或重建结果；
- 不修改 `vectorScore`、`fulltextScore`、`chunkIndex`、title、source、文件追溯字段或
  metadata；
- 不把 document-level cap 写成 calibrated score；
- 不合并 chunk 文本；相邻 chunk 合并需要 offset、token 和引用语义，留给独立规划；
- 选择器不自行分配或重写 citation ID；最终结果按既有 `RetrievalTraceCollector` 语义记录：
  首次进入当前 attempt 的 source 按最终列表顺序获得 ID，跨多次 AGENT tool 调用的既有
  source 保留 first-seen ID，不强制重新连续编号；
- JSON record 通常每文档只有一个 record-level chunk，行为自然保持不变。

### 4.5 失败与降级

- HTTP provider 自身超时、空响应和非法 index 继续走既有 heuristic fallback；
  fallback 使用同一个 `rankingDepth`，随后应用相同文档选择；
- provider 异常离开共享 service 后，各调用链保持既有行为：
  - 有 `RetrievalOutcome` 的受管 Search、`ProjectRerankPostProcessor`、
    `KnowledgeSearchTool` 和 `JsonRecordService` 使用“候选原顺序截断”的 degraded
    fallback，不在异常路径执行新的 cap；
  - 无 `RetrievalOutcome` 的兼容 Search 路径、`EvaluationCaseExecutor` 和旧
    `RerankAdvisor` 继续传播异常，不新增静默降级；
- 理由：异常时优先保持已有、可预测的兼容顺序，不让一次 provider 故障同时改变
  排序和证据集合；
- `none/noop/off` 不扩大候选池，也不进入该选择逻辑；
- 全局 rerank disabled 时 `ReRankingService` 保持原行为。

## 5. 实施范围

### 5.1 生产代码

预计修改：

- `spring-ai-rag-core/src/main/java/com/springairag/core/config/RagRerankProperties.java`
  - 新增并 clamp `preferredMaxChunksPerDocument`；
- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/ReRankingService.java`
  - 分离 provider ranking depth 与最终 limit；
  - selector active 时将 provider 输入防御性限制到 `candidate-limit`；
  - 在 provider 输出后调用共享选择器；
- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/rerank/RerankProvider.java`
  及 provider 实现
  - 把第三个参数的内部语义和命名从 caller final limit 明确为 provider ranking depth，
    不改变 Java 方法签名或 provider 行为；
- 新增一个纯 Java、无 Spring 依赖的选择器，推荐：
  `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RerankResultSelector.java`；
- `application.yml`、`application-prod.yml`
  - 增加默认配置与环境变量。
- 新增 `scripts/verify-rerank-document-diversity.sh`
  - 编排聚焦测试、可处置 PostgreSQL、隔离 `dev.sh`、真实 Search/Playwright、质量回归和
    适用的真实 LLM smoke；
  - 把命令日志与摘要写入 gitignore 的 `.verification/`，并在退出时清理本脚本创建的服务
    与数据库；
- 新增 `spring-ai-rag-webui/e2e/rerank-document-diversity-real.spec.ts`
  - 只针对 runner 创建的真实后端与 fixture；
  - 经 Vite proxy 发送真实 POST Search，断言网络响应和 Search JSON；
  - 再通过现有 Search 页面验证真实 GET、DOM 与代理链路，但不把该明确关闭 rerank 的
    GET 入口当作文档多样化证据；
  - 不使用截图作为通过证据。

按代码实际需要，可以把选择器保持为 `ReRankingService` 的 package-private helper；
只有当独立类明显提升测试可读性时才新增文件，不为了抽象而抽象。

预计不修改：

- 数据库 schema / Flyway；
- `RetrievalConfig`、Search/Chat 请求 DTO 和公开 JSON；
- `HybridRetrieverService` 的候选池计算；
- WebUI 组件和 API client；
- 已在基线失修且不属于根 reactor 的 `demos/demo-component-level`；
- Collection、ACL、filter、query rewrite、chunker；
- Chat prompt、SSE、Memory 或 LLM provider。

### 5.2 测试代码

一次性补齐以下矩阵：

1. `RagRerankPropertiesTest`
   - 默认 `2`；
   - `0`、`1`、`100`；
   - 低于 `0` clamp 为 `0`；
   - 高于 `100` clamp 为 `100`。
2. `ReRankingServiceTest` / 选择器测试
   - selector active 时 provider 接收
     `min(input.size, candidate-limit)` 个候选，ranking depth 与该数量一致；
   - 异常大的直接输入只把前 `candidate-limit` 项发送给 provider；
   - cap=0 时 provider 仍接收完整原候选列表，但 ranking depth 保持 final limit，选择完全
     关闭；
   - cap 大于等于 final limit 时不扩大 provider ranking depth；
   - candidate limit 小于等于 final limit 时不扩大 provider ranking depth；
   - selector 生效判断使用实际 provider 名称，覆盖 HTTP 配置别名、未知名称回落 heuristic
     和显式注入 no-op provider；
   - preferred cap=2 时换入其他文档；
   - preferred cap=1 时最大化文档覆盖；
   - 不足 distinct documents 时按原 rank 回填；
   - 回填允许超过 preferred cap，但只发生在第一遍不足 final limit 时；
   - 最终结果数量等于 `min(finalLimit, providerRankedCount)`，score 顺序和对象字段不变；
   - null/blank document ID 不被错误合并；
   - provider 少返回、超量返回、null 返回；
   - rerank disabled 不应用 cap。
3. `HttpRerankProviderTest`
   - `top_n` 使用 service 传入的 ranking depth；
   - 全排名映射保持 index、score 和 provenance；
   - fallback 也能返回 ranking depth 内的完整排序。
4. `RerankProviderFactoryTest`
   - `none/noop/off` 归一到 no-op provider；
   - `http/api/siliconflow/remote` 归一到 HTTP provider；
   - 未知名称保持既有 heuristic fallback。
5. 真实 PostgreSQL/pgvector 集成
   - 扩展 `HybridRetrieverRrfPostgresIntegrationTest`；
   - 建立一个拥有多个高分 chunk 的文档和至少两个其他候选文档；
   - 使用真实向量 SQL得到有界候选，再用真实 `ReRankingService` 和可控 provider 排名；
   - 证明有足够替代文档时 preferred cap 生效、替代不足时选择器不在 provider 输出上
     继续减量、cap=0 恢复 provider top N。
6. 调用链回归
   - Search、`ProjectRerankPostProcessor`、`KnowledgeSearchTool`、
     `EvaluationCaseExecutor` 和 `JsonRecordService` 现有测试继续通过；
   - 只在共享服务层增加算法断言，不在每个调用方复制同一选择器测试；
   - 至少一条首次检索 Chat citation 测试证明最终 source 顺序与去冗余结果一致；
   - 现有多次 AGENT tool 调用测试证明 first-seen citation ID 仍稳定，不因选择器重编号。

### 5.3 质量证据

现有小型 goldenset 每份文档只有一个 chunk，不能单独证明本功能收益。因此验收使用两类证据：

1. **确定性多 chunk 集成夹具**
   - 真实 PostgreSQL/pgvector；
   - provider 顺序可控；
   - 断言 unique document count 从被单文档占满提升到至少两个/三个；
   - 断言 final result count、排序和相关文档 identity。
2. **既有真实 embedding 回归**
   - 在可处置数据库上运行 `run-retrieval-goldenset.sh`，由脚本创建自己的 fixture；
   - `verify-quality-regression.sh`；
   - MRR、nDCG、Hit Rate、Recall@K 不得回退；
   - 不删除或改写已有 goldenset/回归数据。

实现时可增加一个单独、可重复的多 chunk quality fixture，但必须运行在 disposable
PostgreSQL 中，或使用明确命名且可精确识别的测试数据。禁止在共享本地数据库执行模糊
批量清理；不再使用按空白拆 TSV 的 shell 循环。

## 6. 验收计划

### 6.1 后端基本硬门槛

先一次性完成测试实现，再按顺序运行：

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
DOCKER_API_VERSION=1.40 \
mvn -pl spring-ai-rag-core -am \
  -Dhybrid-rrf.it.enabled=true \
  -Dtest=HybridRetrieverRrfPostgresIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

随后运行本任务聚焦矩阵，至少包含：

- `RagRerankPropertiesTest`
- `ReRankingServiceTest`
- `HeuristicRerankProviderTest`
- `HttpRerankProviderTest`
- `RerankProviderFactoryTest`
- `RagSearchControllerTest`
- `ProjectRerankPostProcessorTest`
- `KnowledgeSearchToolTest`
- `RetrievalTraceCollectorTest`
- `EvaluationCaseExecutorTest`
- `JsonRecordServiceTest`
- `RerankAdvisorTest`
- `AdvisorChainIntegrationTest`

再运行：

```bash
mvn clean compile test-compile
./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-project-docs.sh
git diff --check
```

`demos/demo-component-level` 的独立编译不是本轮完成门槛：基线已经因
`HybridRetrieverService` 可见性和多个 Advisor 构造器漂移等 5 个问题失败。本轮不得以
修改共享 core API 的方式顺带兼容该 demo；实现 diff 只需确认没有改变现有
`RerankProvider` 方法签名或 core Spring 构造器。

### 6.2 前端共享契约回归

本轮不改 WebUI，但 Search JSON 和 Chat sources 属于共享契约，仍执行：

```bash
cd spring-ai-rag-webui
npm run typecheck
npm run test:run
npm run build
```

在隔离 preview 端口运行：

```bash
npx playwright test \
  e2e/search.spec.ts \
  e2e/chat.spec.ts \
  e2e/navigation.spec.ts \
  --project=chromium
```

这些 spec 使用 Mock API；证据只使用 DOM、可访问状态、网络请求/响应和 JSON 自动化断言，
不使用截图。

### 6.3 隔离真实全栈

专用 `scripts/verify-rerank-document-diversity.sh` 必须先创建可处置 PostgreSQL（优先本机
临时数据库，无法使用时回退 Docker 容器），再生成 gitignore 下的临时 env 文件。
该文件只 `source` 当前 worktree 的 `.env` 并覆盖测试数据库、端口、rerank 配置，不复制
API key 到文件。然后使用隔离端口启动：

```bash
BACKEND_PORT=18083 \
FRONTEND_PORT=15175 \
DEV_ENV_FILE=.verification/rerank-document-diversity/<run>/dev.env \
RAG_DEV_OPEN_BROWSER=false \
SPRING_PROFILES_ACTIVE=postgresql,prod \
./scripts/dev.sh
```

runner 必须用 trap 只停止本次 `dev.sh` 管理的进程，并只删除带本次随机 run id 的测试数据库；
调用方提供数据库时必须要求显式 clean confirmation。不得连接或清理默认
`spring_ai_rag_dev`、共享 goldenset 数据库或其他 worktree 的服务。

`dev.sh` 在同一 worktree 共享 `.dev` 状态，因此 runner 启动前必须确认该 worktree 当前没有
launcher-managed backend/frontend；若已有运行栈则直接失败并保留现场，不调用 `dev.sh`
覆盖或停止它。runner 自己生成至少 32 字符的临时 root key，只保存在当前 shell 环境中，
同时传给 `dev.sh`、curl 和 real Playwright；日志与 summary 不输出 key，不依赖
`dev.sh` 的 clipboard/一次性展示来回收凭据。

验证：

1. backend health 为 `UP`；
2. WebUI 静态入口和 Vite proxy 正常；
3. root identity/capability 正常；
4. Search 真实网络响应不超过请求 `maxResults`；
5. 新增 real Playwright spec：
   - 解锁真实 WebUI 后，通过同一 Vite proxy 直接发送 POST `/api/v1/rag/search`，对响应
     JSON 断言 documentId、chunkIndex、score、provider 顺序和结果数量；
   - 再使用现有 Search 页面发起真实 GET，断言 DOM 中的标题、片段、展示顺序和结果计数，
     证明 WebUI、认证和代理消费链路正常；
   - GET Search 当前明确 `useRerank=false`，因此它只作为共享前端契约回归，不承担本功能
     的算法或配置绑定证明；
   - 不要求 DOM 暴露当前 UI 未展示的 chunkIndex 或原始数值 score；
6. retrieval trace 的 candidate/final count 和 rerank latency 合理；
7. 在同一可处置数据库与服务上运行不带 `--skip-create` 的 goldenset 和版本化 regression，
   由各脚本创建可精确识别的 fixture，并记录 MRR/nDCG/Recall/latency；runner 必须把
   shell-only root key 显式以 `API_KEY` 传给 `run-retrieval-goldenset.sh`，并以
   `RAG_ROOT_API_KEY` 传给 `verify-quality-regression.sh`，不能依赖 `.env` 中可能被
   root 模式拒绝的旧 `RAG_API_KEY`。

真实 provider 的浮点排序不作为文档多样化算法的确定性证明；“有足够替代文档时换入其他
文档、替代不足时回填”的硬断言由 §5.2 的可控 provider + 真实 PostgreSQL/pgvector 集成
测试承担。真实全栈用于证明配置绑定、数据库迁移、HTTP JSON、Vite proxy 和 UI 消费链路
没有断裂。

### 6.4 真实 LLM

本轮不修改 Chat provider、query expansion、prompt 或 SSE，因此真实 LLM 不是算法正确性的
主要证据。不过该功能会改变 `KNOWLEDGE` 的最终证据集合：在 Mock、PostgreSQL 和 Search
真实全栈全部通过后，若 `.env` 有可用 Chat/Embedding key 和可用模型，先运行现有
`real-llm-e2e-smoke.sh --skip-stream` 证明 provider 基线，再使用 runner 已创建并嵌入的
多文档 fixture 执行一次有界 `KNOWLEDGE` 请求：

- 请求显式 scope 到 runner 的随机 collection，避免检索到其他数据；
- 检查最终 sources 至少包含预期的不同 documentId；
- answer 只引用返回的 `[Sx]`，citation validation 不出现 invalid token；
- 观察日志，出现 provider/model/config 错误时立即停止，不静默等待；
- 不把一次非确定性答案文案当成排序算法证明。

若没有可用 Tool Calling 模型，不影响本轮结论；`AGENT` 的共享服务行为由自动化测试覆盖。

### 6.5 性能门槛

文档选择器只处理最多 100 项，使用常数次线性扫描，整体为 O(n)。增加确定性的纯计算
复杂度测试：

- 100 candidates；
- 多个重复 documentId；
- 使用计数型测试 helper 断言总候选访问次数不超过 `3 * candidateCount`，不使用嵌套候选
  查找或按 index 排序，且不发生额外 provider、SQL、embedding 或 Chat LLM 调用；
- 不把单次/平均墙钟 `<1ms` 写成 CI 正确性门禁，避免机器负载造成不稳定失败。

真实验收同时比较变更前后：

- Search/Chat retrieval p95；
- rerank provider latency；
- HTTP rerank response payload；
- final unique document count。

默认 heuristic 路径不应新增远程调用；HTTP 路径在标准调用链中的 documents 数不变，
`top_n` 提升到同一个有界候选数；对异常大的直接 service 输入，documents 也会被截到
candidate limit。

## 7. 文档同步

实施完成后同步以下双语长青文档：

- `configuration.md` / `configuration-zh-CN.md`
  - 配置、默认值、范围、环境变量和 `0` 回滚语义；
- `architecture.md` / `architecture-zh-CN.md`
  - candidate pool → provider full ranking → document cap → final top N；
- `quality-defaults.md` / `quality-defaults-zh-CN.md`
  - 默认 `2` 的理由、质量/延迟观测和回滚；
- `troubleshooting.md` / `troubleshooting-zh-CN.md`
  - 同文档 chunk 过多或跨 chunk 信息不足时的调参方法；
- 必要时 `project-context*`
  - 只增加稳定摘要，不复制配置细节。

本 plan/progress 保持单语。功能完成后先提升稳定事实，再按 draft 生命周期用日期前缀归档。

## 8. 规划检查与实现收敛

### 8.1 规划连续三轮检查

规划完成后计数从 `0/3` 开始。三轮固定范围：

1. **价值与契约**：是否直接改善检索/Chat 质量或速度；目标、非目标、默认值、回填和
   score/order 语义是否完整；
2. **代码可行性**：provider ranking depth、所有调用链、异常降级、identity、citation、
   cache、JSON record、配置边界和性能成本；
3. **验收可交付性**：PostgreSQL 多 chunk 夹具、质量指标、前端共享契约、真实运行、
   真实 LLM 适用性、文档、回滚和 Git 流程。

发现影响正确性、质量、响应成本、兼容性、数据安全或验证可信度的问题时，立即修改规划，
计数重置为 `0`。措辞/格式和实施时自然暴露的行号变化不触发重置。无问题轮次不改 plan/
progress；达到连续 `3/3` 后一次性把摘要和 plan SHA-256 写入 progress。

### 8.2 实现连续三轮检查

生产代码修改后，先通过 §6 的全部基本集成硬门槛，再执行：

1. 配置、ranking depth、两阶段选择器、score/order、identity 和 provider fallback；
2. Search/Chat/Agent/JSON/Evaluation 数量、citation/trace/cache、异常降级和兼容性；
3. PostgreSQL/质量/性能/前端/真实运行证据、双语文档、回滚和 Git 状态。

只修复本轮范围内影响正确性、质量、成本、兼容性或数据一致性的缺陷。任何实质修复都把
计数重置为 `0`，并重跑受影响测试和全部基本门槛；连续三轮无修改才进入 Git 交付。

## 9. 发布、回滚与完成定义

### 9.1 发布和回滚

- 默认 `preferred-max-chunks-per-document=2`；
- 想提高单文档连续证据时可调到 `3` 或更高；
- 想最大化文档覆盖时可调到 `1`；
- 想恢复旧 provider top N 行为时调到 `0`；
- 配置修改后重启服务，重新运行多 chunk 夹具、goldenset 和 p95 对比；
- 无数据库迁移，代码回滚只涉及 Java/YAML/测试/文档。

### 9.2 完成定义

只有以下全部满足才可报告实施完成：

1. plan 完成连续 `3/3` 无修改检查，progress 可恢复；
2. 配置与两阶段算法有完整自动化断言；
3. PostgreSQL/pgvector 多 chunk 集成证明文档覆盖提升，且选择器不在 provider 输出上
   继续减量；
4. 聚焦后端矩阵和 `mvn clean compile test-compile` 通过；
5. WebUI typecheck、Vitest、build 和核心 Mock Playwright 通过；
6. 隔离 `scripts/dev.sh` 全栈、真实 Search 和既有质量回归通过；
7. 适用时真实 KNOWLEDGE LLM smoke 通过，或明确记录环境限制；
8. 实现连续 `3/3` 无实质问题；
9. 稳定事实同步到双语长青文档，plan/progress 归档；
10. fetch 最新 `origin/main`；若有变化则 merge 到特性分支并按合并后固定顺序完整复验；
11. 特性分支 push 后合并并 push `main`，确认 `main == origin/main` 且两个 worktree 干净。
