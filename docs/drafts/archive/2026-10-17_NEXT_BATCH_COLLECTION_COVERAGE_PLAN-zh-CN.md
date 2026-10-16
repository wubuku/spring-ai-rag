# 下一批改进规划：有界 Collection 覆盖召回

> [English](2026-10-17_NEXT_BATCH_COLLECTION_COVERAGE_PLAN.md) | [中文](2026-10-17_NEXT_BATCH_COLLECTION_COVERAGE_PLAN-zh-CN.md)
>
> 状态：规划复核 `3/3`、实现、最终验收及 Git 交付均已完成；验收结果见相邻进度记录。
> 日期：2026-10-16
> 实施分支：`feat/collection-coverage`

## 1. 决策摘要

本批次实施一个当前外部 Client 真正可用、边界清晰的 RAG 能力：在调用方明确选择多个
Collection 时，允许请求使用 `EACH_COLLECTION` 覆盖模式，让每个已授权且有命中的
Collection 都有机会进入最终候选；当多个 Collection 的候选竞争同一个 global top-k 时，
该策略可避免某个 Collection 因排序竞争而完全没有候选入选。本规划没有线上或 goldenset
证据证明这种情况已在当前生产环境系统性发生。

本批次推荐落地顺序只有一个主方向：

1. 先冻结新增的覆盖契约和安全边界；
2. 再实现一次 query embedding、按 Collection 有界召回、稳定融合与诊断；
3. 最后把同一能力接入 Native Search、Native Chat、OpenAI 兼容请求，以及 API/集成/真实
   运行验收。

### 为什么现在做这个

- 本批从当前已存在的多 Collection global top-k 检索和统一授权范围出发，增加 Client 可显式请求的
  覆盖策略；不把当前 global 行为描述为缺陷，也不依赖其他批次的交付状态作为实施前提。
  现有资料没有证明该覆盖策略必须成为默认值。
- 当调用方明确需要不同 Collection 都有机会进入最终证据时，global top-k 会让所有候选共同竞争；
  EACH 是一个独立可选的质量/排序策略。其价值是提供可控选择，而不是改变普通并集检索的正确性。
- 代码已经有 `CollectionScopeMode.SELECTED_COLLECTIONS`、ACL allow-list、统一
  `RetrievalScope`、混合检索、重排和 trace 诊断，适合增量加入覆盖策略。
- 当前 TODO 已明确记录 `EACH_COLLECTION` 的候选上限、只允许 selected scope、必须
  bounded fan-out、不能强行填充无命中 Collection 等边界；本规划把这些边界落实为可执行
  的 API 和测试契约。
- 当前活跃规划将此前 deferred 的覆盖能力纳入实施范围，但不声称 global top-k 存在已证实的
  生产缺陷。正式 TODO 已同步：当前批次以外部 HTTP Client/API 为硬验收目标，内置 WebUI 高级选项
  可后续单独增加，不能替代 API 验收；在本批完整验收发布前，旧 API 语义仍是对外契约。
- OAuth/OIDC、完整租户层级和供应商 billing 结算仍需要独立的身份/财务模型，本批不因“高价值”
  而把它们混入检索改动。

## 2. 当前基线与已核对事实

以下事实来自当前代码，不依赖历史规划作为真相：

| 领域 | 当前事实 | 近距离入口 |
|---|---|---|
| 外部范围 | Search/Chat 接受 `CALLER_VISIBLE`、`ANY_COLLECTION`、`SELECTED_COLLECTIONS`；多个 selected Collection 当前组成 global top-k | `spring-ai-rag-api/src/main/java/com/springairag/api/enums/CollectionScopeMode.java`、`docs/rest-api-zh-CN.md` §Collection 检索范围语义 |
| 授权解析 | `CollectionRetrievalScopeResolver` 先做身份解析和 API Key allow-list 校验，再生成授权后的 `RetrievalScope` | `spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionRetrievalScopeResolver.java` |
| 检索主链 | `HybridRetrieverService` 执行 vector/full-text 分支、weighted RRF fusion；`HybridSearchAdvisor` 将结果交给后续 rerank | `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java`、`spring-ai-rag-core/src/main/java/com/springairag/core/advisor/HybridSearchAdvisor.java` |
| 证据/诊断 | `RetrievalOutcome`、`RetrievalScopeSummary` 和 trace 已能记录范围、分支、候选数和结果 | `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalOutcome.java` |
| 幂等 | Chat keyed operation 保存 `effectiveScope` 和 `retrievalOptions` 快照；重放不能重新解释请求 | `spring-ai-rag-core/src/main/java/com/springairag/core/chat/ChatTurnOperationService.java`、`ChatCommandMapper.java` |
| OpenAI | `rag.scope` 已支持 mode、collection ids/keys；OpenAI mapper 复用授权后的统一 scope | `spring-ai-rag-api/src/main/java/com/springairag/api/openai/OpenAiChatCompletionRequest.java`、`spring-ai-rag-core/src/main/java/com/springairag/core/openai/OpenAiRequestRetrievalScopeAdapter.java` |
| 数据库 | Collection 关联、ACL、embedding profile 和检索索引均已存在；本批不改变 schema | `spring-ai-rag-core/src/main/resources/db/migration/`、`RetrievalScopeSql.java` |
| 并发约束 | `ragSearchExecutor` 是 4 线程 `newFixedThreadPool`，队列无界；本批须对 EACH 分支增加进程级在途上限。项目禁止数据库悲观锁、`SKIP LOCKED` 和 advisory lock | `PerformanceConfig.java`、`AGENTS.md` §2、`docs/delivery-workflow-zh-CN.md` |

当前正式 API 语义继续保持：多个 selected Collection 默认是一个并集，返回 global top-k；
本批只新增显式覆盖模式，不重定义 `collectionScopeMode`。

## 3. 目标、非目标与完成定义

### 3.1 目标

1. 新增 `collectionCoverageMode`，支持 `GLOBAL`（既有行为）和 `EACH_COLLECTION`。
2. 只允许 `EACH_COLLECTION` 与显式 `SELECTED_COLLECTIONS` 一起使用；首版最多 20 个
   Collection。
3. 同一请求只计算一次 query embedding；vector/full-text 按已授权 Collection 做有界召回。
4. 先保证每个有命中的 Collection 至少贡献一个候选，再按稳定分数填充剩余 top-k；同一
   document/chunk 去重，不能用低质量伪结果填充无命中 Collection。
5. 把实际覆盖结果、缺失 Collection、分支失败和预算/耗时摘要放入已有诊断链路，且只包含
  当前请求已授权的 Collection 身份。
6. Native Search、Native Chat、OpenAI 兼容 `rag.scope` 三条入口使用同一内部契约。
7. 保持既有外部 Client/API/Java 调用方兼容：旧请求不变，旧响应结构不删除，新增字段可忽略。
8. Chat 的直接 KNOWLEDGE 检索与模型工具 `KnowledgeSearchTool` 是独立结果消费路径；两者
   与 `ProjectRerankPostProcessor` 均复用同一个 coverage-aware rerank helper。Tool 参数
   `maxResults` 仍由 server options 封顶，模型不能扩大已授权 scope；若模型传入的工具结果数
   小于 selected Collection 数，effective limit 向上钳制到该数量，以保持请求级覆盖保证。

### 3.2 非目标

- 不实现 `ANY_COLLECTION + EACH_COLLECTION`，不隐式遍历全库 Collection。
- 不修改默认 global top-k 行为，不要求运维改环境变量，不增加数据库迁移。
- 不修改 Collection ACL、principal、quota、预算硬限制或数据删除语义。
- 不在本批实现 OAuth/OIDC、完整 tenant/workspace 层级、Redis 分布式检索协调或 billing 结算。
- 本批不新增 WebUI 控件或交互；但 Native/OpenAI 共享 HTTP API 契约有增量变化，仍须按交付规范运行
  WebUI TypeScript、相关 Vitest、生产构建和核心 Mock Playwright，作为客户端契约的补充回归证据；
  这些前端检查不能替代外部 Client API 黑盒/HTTP 集成验收，且不得以截图判定通过。
- 不把“每个 Collection 都必须有结果”解释为制造结果；无命中 Collection 必须明确记录为零。

### 3.3 完成定义

只有以下条件全部满足才算本批完成：

- 规划检查连续 `3/3`，实施后实现检查连续 `3/3`，中途发生实质修复则对应计数重置；
- Native Search/Chat 和 OpenAI mapper 有单元/MockMvc/集成覆盖；
- PostgreSQL/Testcontainers 验证多 Collection 的 global 与 each 行为、ACL、无命中、去重、
  limit、Embedding 单次调用和分支失败降级；
- 增加版本化多 Collection goldenset，使用同一夹具/问题对照 GLOBAL 与 EACH，确定性断言覆盖、
  expected/forbidden 来源和诊断一致；记录 MRR、Recall@K、nDCG、候选数和 p95 延迟。覆盖/ACL/
  资源上限是硬门槛；由于 EACH 有意改变排序，不要求其排序分数必须高于 GLOBAL，也不以 LLM
  judge 单独宣布通过。
- 既有 `mvn clean test`、完整 gated PostgreSQL 集成矩阵、Client/OpenAI 黑盒回归和必要真实
  LLM 冒烟通过；
- `scripts/dev.sh` 可启动，旧端点/旧请求的响应和 SSE/幂等语义保持回归通过；
- 共享 HTTP API 契约变化对应的 WebUI TypeScript、相关 Vitest、生产构建和核心 Mock Playwright
  通过；这只补充验证共享契约，没有 WebUI 功能需求，也不能替代 Client API 验收；
- 按 `docs/delivery-workflow-zh-CN.md`，在独立端口/临时数据库真实启动前后端后，运行真实 API Key
  Playwright：从 `spring-ai-rag-webui/` 执行
  `BASE_URL=http://127.0.0.1:15273 RAG_ROOT_API_KEY="$RAG_ROOT_API_KEY" npx playwright test e2e/api-key-real.spec.ts`；
  该浏览器验收仅补充证明 WebUI 到后端的真实代理/认证/HTTP 连通，不能替代 Client Contract Kit、
  Native/OpenAI API 黑盒、PostgreSQL 验收或真实 Search/Chat API 测试；不得用截图作为通过依据；
- REST、项目上下文、测试指南和双语索引更新；
- commit/push 后 `main == origin/main`、工作区干净、测试端口和本批进程已清理。

## 4. 对外契约冻结

### 4.1 Native API

在 `SearchRequest`、`ChatRequest` 增加可选字段：

```json
{
  "collectionScopeMode": "SELECTED_COLLECTIONS",
  "collectionKeys": ["manual-a", "manual-b"],
  "collectionCoverageMode": "EACH_COLLECTION"
}
```

GET `/api/v1/rag/search` 同时增加可选 query 参数 `collectionCoverageMode`；既有 GET 调用
不变。POST Search 仍返回原有 `List<RetrievalResult>`，不把列表包成新 envelope。

规则：

- 缺省或 `GLOBAL`：完全保持当前 global top-k 行为；旧客户端不需要迁移。
- `EACH_COLLECTION`：必须显式提供 `SELECTED_COLLECTIONS` 和非空 selected Collection。
- `EACH_COLLECTION` 的 selected 数量不得超过 20；请求 `limit/maxResults` 必须在
  `selectedCount..100` 范围内，否则返回既有 400 错误语义，不返回部分覆盖结果。GET Search
  的 GLOBAL 旧 `limit<=1000` 行为不变；仅显式 EACH 使用新增的 100 结果资源上限。
- Native Chat 由 `ChatRequest` 现有 50 上限和 `ChatCommandMapper` 校验。OpenAI 兼容请求当前
  没有请求级 retrieval `maxResults` 字段；EACH 时 `OpenAiChatRequestMapper` 将 effective
  maxResults 设为 `max(现有 OpenAI 默认值, selectedCount)`（selectedCount 最多 20），再经独立
  mapper 校验；不新增客户端字段，也不改变 GLOBAL 默认值。
- Native Chat 的 `PLAIN + EACH_COLLECTION` 必须以 HTTP 400、现有 `ErrorResponse` 和 `BAD_REQUEST`
  错误码拒绝；不得像当前 `resolveScope()` 路径一样提前返回 unscoped scope 并静默忽略该参数。
  在 `/ask`、`/chat` 与 `/stream` 的幂等 replay/claim、LLM 调用和 SSE 响应提交前校验；`PLAIN`
  未提供 coverage 或显式使用 `GLOBAL` 时保持原行为。`KNOWLEDGE + EACH_COLLECTION` 继续按下述
  selected scope 和授权规则执行。
- OpenAI 的 EACH 仅适用于 `rag.mode=KNOWLEDGE`；`PLAIN` 配置 `rag.scope.coverage_mode=EACH_COLLECTION`
  必须以 `unsupported_parameter` 在 `rag.scope.coverage_mode` 上拒绝，不能静默接受后忽略。
- 受限 API Key 仍先经过 allow-list；覆盖模式不能扩大权限。未知/未授权 Collection 继续
  使用现有 404/403 语义。
- `CALLER_VISIBLE`、`ANY_COLLECTION` 与 `EACH_COLLECTION` 组合返回 400；不能把隐式 allow-list
  当作调用方明确 selected 语义。
- 旧字段 `collectionIds`、旧数字路径、旧响应字段和旧错误 envelope 不删除、不重命名。

`RetrievalResult` 增加可选的 `collectionId` 和 `collectionKey` 投影，帮助 Client 判断覆盖
结果来源；旧客户端可忽略新增字段。为避免改变 POST Search 的 `List<RetrievalResult>` body，
Native GET/POST Search 在仅使用 EACH 时通过新增 `X-RAG-Collection-Coverage` 响应头返回紧凑
JSON 摘要；Native Chat 将摘要加入既有 response metadata，OpenAI 将摘要投影到
`rag.collectionCoverage`。GLOBAL 和旧请求不新增该 header/metadata。摘要含覆盖范围内的最终结果计数、
missing IDs、未完成 IDs、degraded 标记与有限规范化原因，不含原始异常；body envelope 和 SSE
event 名称不变。Search 摘要头属于 API 契约，必须在 retrieval diagnostics 关闭、未配置或
trace 创建/持久化失败时仍返回，不能依赖 trace 生命周期。启用项目 CORS 的浏览器 Client
还必须能够读取该响应头：`CorsConfig` 对 `/api/**` 和 `/v1/**` 响应暴露
`X-RAG-Collection-Coverage`，不新增运维配置，也不改变现有允许来源/请求头策略；未启用 CORS
时维持现状。

### 4.2 OpenAI 兼容 API

在已有 `rag.scope` 中增加可选字段，兼容 snake_case 与 Java 风格别名：

```json
{
  "rag": {
    "mode": "KNOWLEDGE",
    "scope": {
      "mode": "SELECTED_COLLECTIONS",
      "collection_keys": ["manual-a", "manual-b"],
      "coverage_mode": "EACH_COLLECTION"
    }
  }
}
```

OpenAI 旧请求完全不变；`rag` 顶层和结构化证据扩展继续按已有能力投影。覆盖模式不能触发
额外 LLM 调用，响应仍通过同一个 `rag` 证据/trace 投影表达结果。

### 4.3 默认与回退

本批不提供“改环境变量才能启用”的新能力：客户端显式发送 `EACH_COLLECTION` 即可直接使用。
但是 `GLOBAL` 保持为未指定时的默认值，因为它是当前正式 API 的既有语义，而不是一个需要
运维切换的 legacy 实现；默认强制 fan-out 会改变延迟、数据库负载和答案排序，属于不受控的
兼容/成本变化。这个边界通过 API、集成和性能上限测试固定，未来若有证据改变默认值，必须另
立版本化规划。

## 5. 内部算法与失败语义

### 5.1 授权与范围

1. Controller/adapter 把请求字段交给 `CollectionRetrievalScopeResolver`。
2. Resolver 先按现有规则解析 IDs/keys、校验 active 状态和 API Key allow-list，再把
   `coverageMode` 写入授权后的 `RetrievalScope`。
3. 覆盖模式只保留在 scope，不从请求 metadata 或模型参数读取；Chat keyed snapshot 必须
   保存它，重放时使用原始有效 scope。为兼容升级前仍在保留期内的幂等记录，effective
   `GLOBAL` 不得改变既有 request fingerprint 的 canonical JSON 或 SHA-256：Native/OpenAI
   fingerprint 仅在 `EACH_COLLECTION` 时写入 `coverageMode`；旧请求和显式 GLOBAL 与历史哈希一致。
   Execution snapshot 仍写明当前 coverage mode，旧 snapshot 缺失该字段时解码为 GLOBAL。

### 5.2 `EACH_COLLECTION` 检索

1. 仅对已授权 selected IDs 执行，最多 20 个；先建立单 Collection scope，并在提交任何任务前
   启动不超过现有 `retrievalTimeoutSeconds` 的请求 deadline。
2. query embedding 每请求只计算一次，提交到新增的隔离有界线程池：4 个固定线程、32 个队列
   槽、拒绝时立即降级，不增加运维配置。与 embedding 同时，立即向检索池提交所有启用的
   full-text 分支；全文分支不得等待 query embedding。
3. 只由请求协调线程在共享 deadline 内等待 embedding future；成功后再提交 vector 分支，失败、
   超时或 embedding 池拒绝时记录对应 vector 失败状态，同时允许已提交的 full-text 分支继续。
   严禁 vector 分支占用 `ragSearchExecutor` worker 阻塞等待 embedding，否则慢 embedding 可能占满
   四个检索 worker，使全文任务排队并违反独立推进保证。embedding API 是同步接口，
   `CompletableFuture` 超时不能强制取消已运行的 provider 调用；线程/队列容量有界，底层模型客户端
   超时仍是最终资源释放边界。
4. 每 Collection 的 vector/full-text 任务总数最多为
   `2 * selectedCount`，连同 embedding 总任务数最多 `1 + 2 * selectedCount`。vector/full-text
   使用现有 4-thread `ragSearchExecutor`，不改 GLOBAL 调度。该池由 `Executors.newFixedThreadPool`
   创建且队列无界，因此 EACH 分支提交前必须取得进程级非阻塞许可；在途（排队+运行）分支
   总数最多 80。无许可或 executor 拒绝时，将分支标记为 capacity-limited，继续返回其他已完成
   的独立分支，并在诊断中明确暴露部分覆盖。
5. 所有分支使用同一绝对截止时间；排队任务开始时若 deadline 已过，则跳过 SQL 并记录 timeout。调用线程等待不随
   Collection 数量线性增长；已经运行的同步 provider/SQL 不承诺被强制取消，资源许可要等实际
   任务退出后才释放。
6. 每个 Collection 内先复用现有 vector/full-text weighted RRF，保留现有 minScore、filters、
   freshness、excludeIds 和 rerank candidate budget。每个启用的 vector/full-text 分支沿用现有
   `2 * retrievalLimit` over-fetch，但单分支最多取 200 行；最多 20 个 Collection、每个最多两个
   分支，因此融合前每请求原始分支结果最多 8,000 行。完成各 Collection 内 fusion 后，EACH 合并候选池
   仍最多 100 项。不得因增加覆盖而扩大这些已冻结的单分支/单请求上限。
7. 收集各 Collection 的结果后按稳定 identity 去重：先取每个有结果 Collection 的第一名，
   再按 fused score、documentId、chunkIndex 的现有稳定顺序填充。仅当请求 `useRerank=true`、
   服务端 `rag.rerank.enabled=true` 且配置 provider 不是 `none`/`noop`/`off` 时才视为 rerank
   实际启用；否则 EACH 仍执行覆盖合并，但不扩大到 rerank 候选池、不调用 rerank，候选池深度
   仅为 `finalLimit`。该判定与现有 `candidateRetrievalLimit` 一致。rerank 实际启用时，保留到
   `retrievalLimit = max(finalLimit, rerank.candidate-limit)` 作为候选池；EACH 的
   `finalLimit` 和 rerank candidate limit 都不超过 100。否则只保留最终 `limit`。
8. 若某 Collection 无命中，它贡献零个结果，并在 coverage diagnostics 中列为 missing；不
   降低 minScore、不伪造结果、不扩大到其他 Collection。
9. rerank 实际启用时必须执行一次，且在单次调用中对上一步产生的有界候选池完整排序：候选池大小为
   `min(inputSize, max(finalLimit, rerank.candidate-limit))`，始终不超过 100；provider 的
   `top_n/rankingDepth` 设为候选池大小。之后才按 rerank 分数选择最终
   `limit`，强制保留每个有命中 Collection 的至少一个结果，再以稳定顺序填满。不能先让
   provider 截成最终 `limit` 再尝试恢复覆盖。provider 异常、返回条数少于输入候选池，或
   返回结果遗漏原候选池中有命中的 Collection 时，均视为 rerank 不完整，使用原始有界候选池
   执行同样的覆盖选择。Collection 检索的引用/证据组装只读取最终覆盖选择列表，不读取 rerank
   前的候选项；独立合并的 `STATIC_KNOWLEDGE` 等非 Collection 上下文仍按既有流程参与引用/证据，且
   不计入 Collection 覆盖数。继续沿用现有 citation registry/证据授权链；GLOBAL 继续使用原 rerank 路径。
10. 任意一个 Collection 分支超时、失败或因容量拒绝时，其他 Collection 仍可返回；分支状态写入已有
   `RetrievalBranchStage`/coverage metadata。若所有分支都失败，沿用当前空结果/失败归因。

### 5.3 诊断摘要

在 `RetrievalOutcome.toMetadataMap()` 的 `scope` 或同级 `collectionCoverage` 增加有界摘要；
rerank 后 `RetrievalOutcome.withRerank` 必须按覆盖范围内最终的 Collection 检索结果重新计算
`perCollectionResultCounts`、`returnedCount` 和 `missingCollectionIds`，使 Search trace、Chat
trace、OpenAI `rag.collectionCoverage` 不报告 rerank 前的候选池计数。Chat 的
`CompositeChatDocumentRetriever` 还会独立合并 `STATIC_KNOWLEDGE` 等非 Collection 来源；这些
上下文仍按现有流程提供给模型，但不属于本覆盖契约，不计入 `returnedCount` 或
`perCollectionResultCounts`。因此覆盖摘要表达的是 selected Collection 的检索结果，不是整个
Chat prompt 的全部证据数量；Native Search 没有该类额外上下文：

```json
{
  "mode": "EACH_COLLECTION",
  "requestedCount": 3,
  "returnedCount": 2,
  "missingCount": 1,
  "missingCollectionIds": [42],
  "incompleteCollectionIds": [],
  "degraded": false,
  "degradationReasons": [],
  "perCollectionResultCounts": {"7": 2, "9": 1, "42": 0}
}
```

只记录授权后的 numeric ID、有限计数和固定原因码；不保存 query 正文、chunk 正文、任意
metadata、credential 或 provider 异常。摘要进入现有 retrieval trace、Native Search 的
`X-RAG-Collection-Coverage` header、Chat metadata，并通过 `OpenAiRagExtension` 新增的可选
`collectionCoverage` 字段投影到 OpenAI `rag`。该 record 必须保留当前四参数 Java 构造器，
新增字段使用委托构造器保持源码兼容；不新增非标准 SSE event。字段语义固定如下：

- `requestedCount` 是授权解析、去重后的 selected Collection 数；`returnedCount` 是 rerank 后
  最终 Collection 检索列表长度（只计 `collectionId` 属于本次授权 selected IDs 的结果，不计
  `STATIC_KNOWLEDGE` 或其他非 Collection 上下文）；`missingCollectionIds` 是该列表中计数为 0 的
  已授权 ID；
  `incompleteCollectionIds` 是至少一个检索分支失败、超时或容量拒绝的 ID，可能与 missing
  集合重叠，也可能仍有其他分支返回结果。
- `degraded` 当且仅当 `degradationReasons` 非空。完整执行但无命中的 Collection 只属于
  missing，不属于 degraded。rerank 不完整并回退时 `degraded=true`、原因是
  `RERANK_FALLBACK`，但不因此标记检索 Collection 为 incomplete。
- 对外原因码只允许以下固定集合，并按此顺序去重输出：`EMBEDDING_CAPACITY`、
  `EMBEDDING_TIMEOUT`、`EMBEDDING_ERROR`、`BRANCH_CAPACITY`、`BRANCH_TIMEOUT`、
  `BRANCH_ERROR`、`RERANK_FALLBACK`。provider 原始异常只在受控日志/trace 内按既有脱敏规则处理，
  绝不进入 header/body。
- `perCollectionResultCounts` 以十进制 Collection ID 字符串为 key，值为最终 Collection 检索列表中的结果数；
  每个授权 selected ID 都必须出现（无结果为 `0`），所有 value 之和等于 `returnedCount`；
  `missingCount` 等于 `missingCollectionIds` 数量。OpenAI `rag.collectionCoverage`、Native Chat
  metadata、Native Search header 使用同一 JSON 结构。新字段仅在 EACH 输出；GLOBAL 不输出摘要。

## 6. 文件级实施顺序

### Slice A：API 与授权范围

- 新增 `CollectionCoverageMode` enum；
- 扩展 `SearchRequest`、`ChatRequest`、OpenAI `RagOptions.Scope`；
- 为 `RetrievalScope` 增加 coverage 属性，同时保留旧构造器和工厂方法；
- 扩展 resolver、GET/POST Search、Chat command mapper、OpenAI scope adapter 与
  `OpenAiChatRequestMapper` 的独立参数错误校验；
- 在 Native Chat `/ask`、`/chat` 和 `/stream` 入口、幂等 prepare/replay/claim 之前拒绝
  `PLAIN + EACH_COLLECTION`，返回 HTTP 400 `BAD_REQUEST` 和既有错误结构；保留 PLAIN 缺省/GLOBAL
  行为；MockMvc 注册真实 `GlobalExceptionHandler`，断言既有 `ErrorResponse` 的 `error/status/path`
  字段及 problem+json 响应类型，并验证 `prepare/inspectExisting/claim` 和模型均未调用、没有
  operation 创建/认领；`/stream` 发送 `Accept: text/event-stream`，仍应返回 HTTP 错误响应而非 SSE 帧；
- 在 `RagSearchController` 对 EACH Search 增加可选响应摘要头，不改变 GET/POST body envelope；
  更新 OpenAPI 与 Client Contract Kit 断言；在 `CorsConfig` 为启用 CORS 的浏览器调用暴露该头，
  并补充 CORS 配置映射测试；
- 补请求校验、OpenAPI 注解和兼容构造测试。
- EACH 专属结果上限为 100（GET Search GLOBAL 仍保持既有 1000 上限），并校验
  `selectedCount <= limit/maxResults <= 100`。

### Slice B：检索装配

- 扩展 `RetrievalResult` 及 vector/full-text SQL 的 collection 投影；
- 在 `PerformanceConfig` 增加隔离、有界 embedding executor；在 `HybridRetrieverService` 增加
  单次异步 embedding、EACH 分支全局非阻塞许可、deadline 检查、稳定 coverage merge；
- 保留旧 `search`/`searchInScope` 签名，GLOBAL 继续走既有路径；
- 将覆盖摘要接入 `RetrievalOutcome`/trace；
- 为 EACH 增加统一的单次有界全候选 rerank helper，并接入 Search、
  `ProjectRerankPostProcessor`、`KnowledgeSearchTool`；失败/部分返回时回退仍保覆盖，避免
  provider top-k 截断丢失 Collection；让 citation、evidence assessment 只看到最终列表。

### Slice C：幂等、请求指纹与 OpenAI

- fingerprint 只为 `EACH_COLLECTION` 增加 canonical 字段；`GLOBAL` 必须与改动前的 fingerprint
  字节和 SHA-256 一致，避免旧的持久化幂等键在升级后变成 409；execution snapshot 显式保存
  coverage，旧 snapshot 缺字段按 GLOBAL 解析；
- OpenAI mapper 将 scope coverage 解析到统一 scope；
- 确认 Native/OpenAI JSON/SSE、旧 GLOBAL keyed replay、EACH keyed replay/冲突隔离、错误 envelope
  和旧 SDK 请求不变。

### Slice D：测试、文档与验收

- 单元/MockMvc/真实 PostgreSQL 集成测试；
- 新增版本化 `testdata/regression/retrieval-collection-coverage-v1.json`，并新增或扩展
  `scripts/verify-collection-coverage.sh`：同一固定数据分别请求 GLOBAL/EACH，硬断言每个
  expected non-empty selected Collection 的来源覆盖、forbidden/ACL 隔离、最终响应摘要及资源
  上限；同时记录 MRR、Recall@K、nDCG、候选数和 p95 延迟，不将排序指标冒充覆盖正确性的替代品；
  JSON record upsert 即使请求 `embeddingPolicy=SYNC`，也可能因 embedding job 已被后台 worker
  抢先执行而暂时返回 `INDEXING`；回归脚本必须按文档 `GET /api/v1/rag/documents/{id}` 每 500ms
  有界轮询，单文档默认最多等待 60s，直到 lifecycle `searchability=READY` 且
  `embeddingStatus=READY` 后才执行 Search；任一分支明确失败或超时必须失败，不能把 `INDEXING`
  当通过或当作立即失败；
- 更新 REST、configuration、architecture/project-context、testing/developer/release 文档及双语版本；
- 新增或扩展专用验证脚本，接入 `verify-release.sh`，默认不调用真实模型；
- 因本批增量修改共享 HTTP API 契约，仍在 `spring-ai-rag-webui/` 执行
  `npx tsc -b --pretty false`、`npm run test:run`、`npm run build`、`npm run test:e2e:mock`
  （构建并以 preview server 运行全部非 `-real` Mock E2E）；不得使用截图作为通过依据，且这些前端
  检查不替代 Client Contract Kit/外部 API 回归；
- 按正式交付工作流，在 `scripts/dev.sh` 运行的真实前后端和隔离数据库上追加
  `BASE_URL=http://127.0.0.1:15273 RAG_ROOT_API_KEY="$RAG_ROOT_API_KEY" npx playwright test e2e/api-key-real.spec.ts`；
  固定后端/前端端口为 `18282/15273`；用随机 loopback 端口启动 disposable
  `pgvector/pgvector:pg16`，从 `.env` 生成权限 `0600` 临时环境 overlay，仅替换 PostgreSQL
  host/port/database/user/password 和 `SPRING_DATASOURCE_URL`，保留模型和 root API credential；
  以 `DEV_ENV_FILE` 指向 overlay 并设置 `RAG_DEV_OPEN_BROWSER=false`。执行 OpenAI 兼容 API
  黑盒探针时，另在该临时 overlay 显式设置 `RAG_OPENAI_COMPATIBILITY_ENABLED=true`（生产默认仍为
  关闭），并使用现有公开模型别名 `rag-default`；API 兼容脚本传入
  `API_CONTRACT_MODEL=rag-default`。这些仅是本次隔离验收配置，不改变生产默认值。此测试会创建/轮换/撤销 API Key，
  禁止连到运维配置的数据库。运行后停止 dev 栈并删除仅由本批创建的容器和临时 overlay；这是补充
  运行时证据，外部 client HTTP/API 才是本批兼容验收重点；
- 执行合并后最终门禁、三轮实现检查、提交和推送。

## 7. 验收矩阵

| 验收面 | 必须证明的事实 | 证据 |
|---|---|---|
| DTO/校验 | 缺省 GLOBAL；非法组合、空 selected、>20、limit 不足或 EACH limit >100 返回 400；GLOBAL GET 原 1000 不变 | API 单测、MockMvc |
| ACL | restricted key 不能通过 coverage 逃逸；unknown/denied 语义不变 | PostgreSQL/HTTP 集成 |
| 算法 | 每个有命中 selected Collection 至少一项；无命中为零；单分支最多 200 行、每请求融合前总计最多 8,000 行、合并候选池与最终列表最多 100 项；稳定去重 | `RetrievalUtils`/service 单测、PostgreSQL |
| Embedding | EACH 仅调用一次；full-text 先独立提交，vector 只在请求协调线程取得 embedding 后提交，检索 worker 不得阻塞等待 embedding；慢 embedding 时 full-text 仍可完成；embedding 超时/容量拒绝不占检索 worker；慢/卡住的同步调用受独立线程池和队列上限约束 | Service 单测（含 latch）、集成计数器 |
| 分支失败/容量 | 单个 vector/fulltext/Collection 超时或容量拒绝不吞掉其余可返回结果；EACH 在途分支总数不超过 80；deadline 后排队任务不执行 SQL | Service 并发单测、PostgreSQL |
| Chat | 覆盖结果进入最终 prompt、sources、citation、evidence metadata；rerank 只运行一次且在
  候选池深度返回，再做覆盖约束；失败回退保留覆盖；静态/非 Collection 上下文不计入覆盖摘要 | Chat service/controller 集成及 provider 调用记录 |
| Native Chat 模式校验 | `/ask`、`/chat`、`/stream` 的 `PLAIN + EACH_COLLECTION` 在任何幂等准备/预查询、replay/claim、模型调用或 SSE 响应提交前返回 HTTP 400 `BAD_REQUEST`；既有 PLAIN 缺省/`GLOBAL` 与 `KNOWLEDGE + EACH_COLLECTION` 语义不变 | 注册真实 `GlobalExceptionHandler` 的 MockMvc API 回归，断言 problem+json 的 `error/status/path`；验证 `prepare/inspectExisting/claim` 与模型无调用且无 operation 写入；`/stream` 使用 `Accept: text/event-stream` 并断言 HTTP 错误响应而非 SSE 帧 |
| 历史幂等兼容 | 对 effective GLOBAL，Native/OpenAI 请求的旧 canonical fingerprint 与旧 SHA-256 完全不变；升级前持久化的成功记录可 replay，不产生 409；EACH 字段参与指纹且与 GLOBAL 隔离；旧 execution snapshot 缺字段仍为 GLOBAL | fingerprint canonical/hash golden 单测；以旧哈希预置操作记录的 Service/PostgreSQL replay 测试；Native/OpenAI keyed API 回归 |
| Tool Chat | `KnowledgeSearchTool` 的工具 maxResults 低于 selected 数时仍向上钳制且不超过 caller maxResults；再次检索/rerank 保持授权与覆盖 | Tool callback 测试 + Chat 工具集成 |
| Quality/Cost | 固定版本 goldenset 对照 GLOBAL/EACH；覆盖/来源/禁止来源/响应摘要为硬断言；upsert 返回 `INDEXING` 时按文档 detail lifecycle 每 500ms 轮询，单文档默认最多等待 60s，直至 `searchability=READY` 且 `embeddingStatus=READY`；FAILED/超时失败；MRR、Recall@K、nDCG、候选数、p95 有对照报告，不要求 EACH 排序分数必然高于 GLOBAL | PostgreSQL/真实 embedding gated regression |
| Rerank | 输入命中多个 Collection、provider 原 top-k 会挤掉一个 Collection 时，最终仍覆盖每个
  有命中 Collection；请求次数仍为一次 | 服务单测 + Search/Chat 路径测试 |
| OpenAI | `rag.scope.coverage_mode` 在 KNOWLEDGE 映射一致；PLAIN 使用时在正确字段报 400；JSON/SSE/replay 兼容；旧 SDK 仍可读 answer。真实 HTTP 探针在隔离 overlay 显式开启默认关闭的兼容入口，并使用 `rag-default` 别名 | OpenAI mapper/controller/SDK E2E、隔离运行时 Client API 探针 |
| API 兼容 | 旧 GET/POST/Chat/OpenAI body、错误、SSE 顺序不变；仅 EACH Search 带可选覆盖摘要 header，且不依赖 trace 开关/存储；Chat/OpenAI 增量摘要只出现在 EACH | Client Contract Kit、MockMvc/SDK 回归 |
| 共享契约前端回归 | 虽不新增 WebUI 功能，本批 API 字段/响应扩展仍须证明现有 WebUI 类型、测试、构建和关键流程没有被破坏；仅为补充证据，不代替外部 Client API 验收，不使用截图判定 | `spring-ai-rag-webui` TypeScript、相关 Vitest、生产构建、核心 Mock Playwright |
| 真实前后端 Playwright | 用随机 loopback 端口 disposable `pgvector/pgvector:pg16` 和权限 `0600` 的临时 `.env` overlay，在独立后端/前端端口 `18282/15273` 启动 `scripts/dev.sh`；真实浏览器通过 Vite proxy 调用受认证 API，验证 API Key 创建/轮换/撤销、credential 最终删除以及秘密不进入 URL/console/browser storage。测试不负责删除 principal 行；运行后销毁整个临时数据库容器，绝不连接运维数据库。此为运行拓扑/代理补充验收，不能替代外部 Client API 黑盒与 PostgreSQL 测试 | 从 `spring-ai-rag-webui/` 执行 `BASE_URL=http://127.0.0.1:15273 RAG_ROOT_API_KEY="$RAG_ROOT_API_KEY" npx playwright test e2e/api-key-real.spec.ts`；无截图验收 |
| CORS | CORS 启用且来源获准时，浏览器代码可读取 `X-RAG-Collection-Coverage`；不改变 allowed origins/headers 和 CORS 默认关闭语义 | `CorsConfigTest` 与 CORS 配置文档 |
| 运行时 | 独立端口和临时数据库下 `scripts/dev.sh` 可启动；真实前后端 Playwright 通过；端口、进程和数据库清理 | `/actuator/health/readiness`、`e2e/api-key-real.spec.ts`、`./scripts/dev.sh --stop` 与容器清理验收 |
| 真实模型 | 至少一次真实 KNOWLEDGE Search/Chat 证明最终来源仍受授权和覆盖约束 | `.env` 有效时的有界真实验收；不可用则明确记录限制 |

## 8. 风险、回滚和可逆边界

- **数据库负载与并发**：EACH 只对显式 selected 且最多 20 个 Collection 生效，每请求最多
  41 个异步任务（embedding + 40 个 branch task）；embedding 使用 4-thread/32-queue 独立池，
  分支使用现有 4-thread 检索池，并以进程级许可将 EACH 排队+运行分支限制为 80。deadline 后
  排队分支跳过 SQL；已运行的同步 provider/SQL 不强制取消，许可在实际任务结束后释放。容量拒绝
  可以返回部分覆盖并须在诊断明确呈现。沿用每个 vector/full-text 分支 `2 * retrievalLimit`
  over-fetch、单分支最多 200 行；融合前每请求原始分支结果最多 8,000 行，合并候选池和最终结果各
  最多 100 项。无迁移，回滚只需恢复代码/文档。
- **线程池嵌套**：不在 outer per-Collection task 中再次调用会向同一 executor 提交的完整
  search；实现使用一层 bounded branch tasks，避免线程池饥饿。
- **结果排序变化**：仅 `EACH_COLLECTION` 请求改变排序；GLOBAL 和旧请求复用原路径。
- **结构化响应兼容**：新增 `collectionId`/`collectionKey` 和可选 coverage metadata/header，
  不改列表 envelope、旧 record 构造器或 SSE event 名称。header 只在显式 EACH 时出现，且只
  包含授权 IDs、计数和固定原因码。
- **幂等兼容**：只有 `EACH_COLLECTION` 将 coverage 写入 fingerprint；effective GLOBAL 保持
  改动前 canonical JSON/SHA-256，确保仍在保留期内的旧 operation 可按原键 replay。新的 execution
  snapshot 显式保存模式，旧 snapshot 缺字段按 GLOBAL 解析；EACH 与 GLOBAL 的 key reuse 必须冲突。
- **异常信息泄露**：coverage summary 只返回已授权 ID 和计数；provider 错误继续使用现有
  normalized code。
- **回滚**：若验收发现正确性、成本、权限或兼容问题，停止合并，保留旧 GLOBAL 路径；不
  删除已有数据库数据、不回写迁移、不使用 reset/stash。
- **质量解释**：EACH 改变的是跨 Collection 候选分配而非普遍排序升级；goldenset 指标用于
  透明呈现覆盖收益与排序/延迟代价。覆盖、ACL、禁止来源、硬资源界限为门槛，单一 LLM judge
  或“分数高于 GLOBAL”不作为唯一通过依据。

## 9. 规划/实施进度记录与检查规则

实施进度写入相邻的
[`2026-10-17_NEXT_BATCH_COLLECTION_COVERAGE_PROGRESS-zh-CN.md`](2026-10-17_NEXT_BATCH_COLLECTION_COVERAGE_PROGRESS-zh-CN.md)
和英文对应文件。每个 checkpoint 记录：分支/commit、工作区、已完成 slice、命令和结果、
当前检查计数、已知问题、下一步和恢复入口。

规划在修改代码前执行三轮互不重叠的检查：

1. 需求闭环、自包含性、默认决策和非目标；
2. 代码、API、授权、并发、成本和兼容可行性；
3. 实施顺序、验收矩阵、文档、发布、回滚和恢复。

任何影响正确性、兼容性、安全、数据一致性或可实施性的发现都立即修正文档并将计数重置
为 `0`；连续三轮无修改后才进入实现。实现基本硬门槛通过后，再按同样规则执行三轮只读
实现检查；修复实现必须重跑受影响测试和全部基本硬门槛。
