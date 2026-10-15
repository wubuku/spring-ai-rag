# Client API 兼容、运行时证据安全与逐请求 Trace 实施规划

> **状态**：规划已完成；OpenAI request required 语义修订后的连续 `3/3` 无修改复核已通过，允许进入生产实施。
>
> **编写日期**：2026-10-15
>
> **实施位置**：当前 `main` 工作区 / `main` 分支；不创建额外 worktree。
>
> **基线提交**：`3482d702 feat: add QA evaluation workbench and bounded PDF import`。
>
> **近距离规范**：[规划、实施与验收工作流](../delivery-workflow-zh-CN.md)、[项目上下文](../project-context-zh-CN.md)、
> [REST API](../rest-api-zh-CN.md)、[SSE 协议](../SSE-PROTOCOL.md)、[测试指南](../testing-guide-zh-CN.md)。

## 1. 执行摘要

本批只实施用户选定的三个方向：

1. **Client API 契约兼容性与黑盒回归**：把现有“OpenAPI producer contract”提升为可审查、版本化、可在运行服务上执行的兼容门禁，覆盖原有路径、方法、请求字段、响应字段、类型和错误 envelope；同时提供一个不依赖 WebUI 的 HTTP 黑盒回归入口。
2. **运行时证据充分性与安全回答**：把现有 `CitationValidator`、Citation Registry、检索诊断和上下文预算结果汇聚成顶层机器可读 `evidenceAssessment`；`KNOWLEDGE` 模式默认执行确定性安全门，证据不足时返回受控安全回答，不让 LLM judge 或 metadata 解析成为线上安全前提。
3. **Trace 与逐请求可观测性**：保留现有 `X-Trace-Id`、W3C `traceparent` 和 `X-RAG-Retrieval-Trace-Id`，增加请求 Trace 与检索 Trace 的关联记录、错误响应可见性、诊断查询过滤和 SSE/JSON 的一致输出，使外部 Client 可以从一次 HTTP 请求追到检索诊断、答案证据和错误。

本批**不实施**：

- `EACH_COLLECTION` 或新的租户隔离模型；
- OAuth/OIDC、多租户身份体系；
- Token/Cost hard limit、billing；
- Parser SPI、多向量数据库、Graph/Wiki/MCP 大架构；
- WebUI 作为主要验收目标；
- 删除、改名、重解释既有 endpoint、请求字段、响应字段、legacy alias 或 OpenAI 兼容路径。

## 2. 交付原则与兼容承诺

### 2.1 对外 Client API 是主契约

本批以 HTTP/OpenAPI/SSE/OpenAI-compatible 入口为验收中心，WebUI 只做受影响的构建和现有测试回归。允许的变化是：

- 新增可选请求字段；
- 新增响应字段、响应头、SSE payload 字段或诊断查询参数；
- 在既有错误 envelope 中新增 `traceId` 等可选字段；
- 为既有响应增加机器可读证据状态，但保留 `answer`、`sources`、`metadata`、`traceId`、`sessionId`、`turnId`、`usage`、`finishReason` 等字段。

禁止的变化是：

- 删除或改名现有 endpoint、HTTP method、请求字段、响应字段、SSE event 名称或 OpenAI body 字段；
- 把既有可选请求字段改为必填；
- 把既有字段 JSON 类型改掉；
- 让普通 Chat 为了评测而额外调用 LLM；
- 让 Trace、证据诊断或观测旁路失败导致已经可以成功的业务请求失败；
- 让 API Key、Token、原始 prompt、完整 query 或未授权 chunk 进入新的观测输出。

### 2.2 新能力默认启用，保留应急回退

- API contract manifest 和黑盒检查默认可运行；发布验证脚本默认执行静态/运行时可用的 contract 门禁。
- `KNOWLEDGE` 的确定性证据评估默认启用；安全回答默认启用。配置只保留应急关闭开关，默认值不能是 legacy/关闭。
- 新安全门不依赖既有 `rag.evaluation.citation-validation-enabled` 开关；安全门启用时始终运行独立的确定性 `CitationValidator`。既有开关只控制兼容的 `metadata.citationValidation` 是否输出，关闭它不能让外部回答绕过安全判定。
- 请求 Trace 生成和响应头传播默认启用；当前默认 `sampling-rate=1.0` 保持不变。采样仍只控制 MDC/详细日志，不能让请求失去可返回的 Trace ID。
- 诊断持久化继续 fail-open；数据库写入失败时业务响应照常返回，但响应仍保留请求级 Trace header。
- 不需要手工数据迁移。本批将 `requestTraceId` 写入已有 `rag_retrieval_logs.metadata` JSONB，并用现有 Flyway 已存在的表和 retention 机制查询；最高迁移版本保持 V63。若实施中发现 JSONB 无法安全、受 ACL 约束地查询，才允许新增自动 Flyway 迁移，且必须先修改本规划并重新完成规划 `3/3`。

### 2.3 安全回答的冻结默认

只对 `ChatMode.KNOWLEDGE` 执行安全门；`PLAIN` 不适用证据评估，`AGENT` 保持现有工具/调用语义并只记录诊断，不在本批引入新的 Agent 结论裁剪规则。

确定性状态：

| 状态 | 判定 | 默认回答行为 |
|---|---|---|
| `NOT_APPLICABLE` | `PLAIN` 或非知识问答 | 保留原回答 |
| `SUPPORTED` | 有可见来源、引用只指向 Registry 中合法 source，且上下文未被预算裁剪 | 保留原回答 |
| `NO_EVIDENCE` | `KNOWLEDGE` 没有可见来源 | 返回安全回答 |
| `MISSING_CITATION` | 有来源，但答案没有合法引用 | 返回安全回答 |
| `INVALID_CITATION` | 答案引用了不存在的 source handle/citation id | 返回安全回答 |
| `PARTIAL_CITATION` | 同时存在合法引用和非法引用 | 返回安全回答 |
| `CONTEXT_LIMITED` | 证据上下文发生 budget drop/truncate 或检索预算明确耗尽 | 返回安全回答 |
| `EVALUATION_UNAVAILABLE` | 确定性证据评估器异常或输入无法安全判定 | 返回安全回答 |

安全回答是固定、可配置但不含内部详情的文本，默认中文：
`根据当前授权范围内可检索到的资料，无法确认该问题的答案。请补充更具体的信息，或提供相关资料后再试。`

安全回答不返回原始未受支持答案；`evidenceAssessment.safeAnswerApplied=true`，并保留来源、Trace、usage、finish reason 和诊断 metadata。这样 Client 可以区分“模型给出了内容”和“服务主动拒绝无证据作答”。安全门只改变 `KNOWLEDGE` 的回答内容，不改变 HTTP 成功状态、不新增 LLM 调用、不改变已有字段含义。

## 3. 已核对的现状与缺口

以下事实已直接核对代码、迁移、测试和正式文档；实施不得只按旧 draft 推断：

### 3.1 Client API 与测试

- Native Chat 入口在 `spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagChatController.java`，至少包括 `/rag/chat/ask`、兼容 `/rag/chat`、`/rag/chat/stream`、turn status、history 和 export；对外版本前缀由 `@ApiVersion("v1")` 产生 `/api/v1/rag/...`。
- Search 入口在 `RagSearchController`，支持 GET/POST；OpenAI 受控兼容入口在 `OpenAiCompatibilityController` 的 `/v1/models`、`/v1/models/{id}` 和 `/v1/chat/completions`。
- `ChatResponse` 已有 `answer`、`traceId`、`sources`、session/turn、mode、model、usage、finish reason、metadata、stepMetrics；`SearchResponse` 当前为 `results`、`total`、`query`。
- `ErrorResponse` 已同时保留 RFC 7807 字段和历史 alias：`type/title/status/detail/instance` 与 `error/message/timestamp/path`。
- `OpenApiContractTest` 当前验证 `/v3/api-docs`、关键 schema/path/type，但没有仓库内版本化的“旧契约必须继续存在”manifest，也没有统一的运行服务黑盒入口。
- 已有 `scripts/business-client-contract-e2e.sh`、`scripts/verify-openai-compatibility.sh`、`scripts/verify-quality-regression.sh`、`scripts/verify-release.sh`，本批不重做它们已有职责，而是提供可复用的 contract helper 并接入发布门禁。

### 3.2 证据与回答

- `CitationValidator` 已支持 `[S1]` 和 `[c1]`，返回 `NOT_APPLICABLE`、`VALID`、`MISSING_CITATION`、`INVALID_CITATION`、`PARTIAL`；它明确不做自然语言 claim coverage。
- `ChatExecutionService.toResult()` 已提取 sources、citation registry、retrieval summary，并将 `citationValidation` 放在 metadata；`ChatTurnOperationService` 会过滤并持久化可重放 metadata。
- `CitationQueryAugmenter` 已在无资料时给模型安全提示，但当前仍主要依赖模型遵守提示；普通 JSON/SSE 响应没有独立、稳定的顶层证据状态，流式回答也会在最终确定性校验前发送内容。
- `PromptBudgetDocumentPostProcessor` 和 `RetrievalTraceCollector` 已记录上下文 budget drop/truncate、query expansion、document join、context assembly 等诊断；这些事实可用于 `CONTEXT_LIMITED` 判定。

### 3.3 Trace 与持久化

- `RequestTraceFilter` 支持 `X-Trace-Id`、`traceparent`、MDC、采样、可选 W3C response header；当前采样为 0 时新生成的 Trace 不写入 response header/MDC。
- `RetrievalTraceSession` 生成独立 UUID 检索 trace，`rag_retrieval_logs`（V35）已有 `trace_id`、owner、operation、outcome、empty reason、metadata 和按 principal 的只读查询。
- `RetrievalTraceController` 已提供 `GET /rag/retrieval-traces` 与 `GET /rag/retrieval-traces/{traceId}`，当前可按 operation/outcome/empty reason/session/citation status 过滤。
- `LlmUsageEvent` 已有 `requestTraceId`，`rag_audit_log` 已有 `trace_id`；本批把请求 Trace 显式带入 retrieval session metadata，补齐 Chat/Search/OpenAI 与检索诊断的关联，不扩大高基数 Micrometer 标签。
- 当前迁移目录实际最高为 `V63`，本批默认不新增迁移；JSONB metadata 查询必须继续做 principal 过滤和 bounded page，不能绕过 ACL。

## 4. 目标设计

### 4.1 API compatibility manifest

新增 `testdata/api-contract/v1-compatibility.json`，采用轻量、可审查格式而不是提交完整且噪声很大的 OpenAPI JSON。第一版 manifest 的最小路径集合冻结如下（OpenAPI 的实际部署前缀由检查器按 path suffix 匹配）：

| path suffix | method | request | response/行为 | 是否必须 |
|---|---|---|---|---|
| `/rag/chat/ask` | `POST` | `ChatRequest` | `ChatResponse` | 必须 |
| `/rag/chat` | `POST` | `ChatRequest` | `ChatResponse` | 必须，旧兼容入口 |
| `/rag/chat/stream` | `POST` | `ChatRequest` | SSE：`content/sources/done/error` | 必须 |
| `/rag/search` | `GET` | query `query` 等 | `SearchResponse` 或既有错误 envelope | 必须 |
| `/rag/search` | `POST` | `SearchRequest` | `SearchResponse` 或既有错误 envelope | 必须 |
| `/rag/retrieval-traces` | `GET` | page/size + 既有过滤 + 可选 `requestTraceId` | `RetrievalTracePageResponse` | 必须 |
| `/rag/retrieval-traces/{traceId}` | `GET` | path UUID | `RetrievalTraceDetailResponse` | 必须 |
| `/v1/models` | `GET` | 无 | OpenAI model list | OpenAI compatibility 开启时 |
| `/v1/models/{id}` | `GET` | path id | OpenAI model | OpenAI compatibility 开启时 |
| `/v1/chat/completions` | `POST` | `OpenAiChatCompletionRequest` | 既有 OpenAI JSON/SSE shape | OpenAI compatibility 开启时 |

manifest 对上述路径冻结的字段集合为：

- `ChatRequest`：必须 `message`；保持可选的 `sessionId`、`maxResults`、`useHybridSearch`、`useRerank`、`mode`、`domainId`、`model`、`collectionScopeMode`、`collectionIds`、`collectionKeys`、`documentIds`、`metadata`、`filters`；
- `OpenAiChatCompletionRequest`：必须保留请求级必填 `model`、`messages`（当前 mapper 对二者均执行缺失/空值拒绝）；`stream`、`n`、`rag` 以及当前已建模的 `temperature`、`top_p`、`max_tokens`、`max_completion_tokens`、`tools`、`tool_choice`、`functions`、`function_call`、`logprobs`、`response_format`、`stream_options` 保持可选；`Message` 的 `role`、`content`、`name`、`tool_calls`、`function_call` 和 RAG scope/filter 字段及 snake_case 别名不得被删除或改类型；当前明确拒绝的字段仍保持既有拒绝语义，不把它们悄悄当成已生效能力；
- `ChatResponse`：必须保留 `answer`、`sources`、`metadata`；继续保留 `traceId`、`sessionId`、`turnId`、`mode`、`requestedModel`、`resolvedModel`、`usage`、`finishReason`、`stepMetrics`，新增 `evidenceAssessment`；
- `SearchResponse`：必须保留 `results`、`total`、`query`；
- `ErrorResponse`：必须保留 `type`、`title`、`status`、`detail`、`instance`、`error`、`message`、`timestamp`、`path`，新增可选 `traceId`；
- `RetrievalTracePageResponse`/`RetrievalTraceDetailResponse`：不删除现有分页、trace、operation、outcome、session、时间、scores、metadata 字段，新增的 `requestTraceId` 只能是字符串或 null；为已有 Java record 构造器保留兼容重载，旧调用方不必补新参数；
- `ChatExecutionResult`：新增证据字段时保留当前 11 参数 Java 构造器，旧测试、starter 扩展和内部调用方继续可编译；旧构造器将新字段置为 null/默认值，新 canonical 构造器只由内部适配层使用；
- `EvidenceAssessment`：所有字段均为新增字段，`status/reasonCode` 为字符串，计数为非负整数，布尔字段为 boolean，`invalidCitationIds` 为字符串数组；
- Native SSE event 名称和最低 payload 字段冻结为：`content.choices`、`sources.sources`、`done.status`、`error.error`；新增 `done.evidenceAssessment`，不删除既有 `traceId/sessionId/metadata`；
- OpenAI compatibility：继续保留 `id/object/created/model/choices/usage` 以及 SSE chunk 的 `id/object/created/model/choices`，不把 native `evidenceAssessment` 注入 OpenAI body。

- 关键 endpoint 的 path suffix、HTTP method、request schema、response schema；
- 既有请求字段的 JSON 类型和“必须保持可选”的字段集合；
- 既有响应字段的 JSON 类型和必须保留字段集合；
- ErrorResponse 的 RFC 7807 字段、历史 alias 和可选新增 `traceId`；
- SSE 的稳定 event 名称：`content`、`sources`、`done`、`error`，以及关键 payload 字段；
- OpenAI compatibility 的 `/v1/models`、`/v1/models/{id}`、`/v1/chat/completions` body 核心字段。

新增 `scripts/lib/api-contract-check.mjs`：

1. 读取 `/v3/api-docs` JSON 与 manifest；
2. 按 path suffix 匹配实际 API，避免把部署 context path 固化进 manifest；
3. 检查 path/method 未删除；request 可选字段未变 required；response 字段未删除、类型未改变；schema ref 仍存在；
4. 检查新增 `EvidenceAssessment`、`ChatResponse.evidenceAssessment`、Trace 查询参数属于增量，不反向改变旧字段；
5. 输出机器可读失败原因，并以非零退出码阻断发布。

新增 `scripts/verify-api-compatibility.sh`：

- `BASE_URL` 默认 `http://127.0.0.1:8081`，可由环境覆盖；
- 先检查 `/v3/api-docs` 和 manifest；
- 再执行无需模型的黑盒调用：Chat 空/非法请求、Search 空/非法请求、retrieval trace 列表参数、错误 envelope 和 `X-Trace-Id` response header；允许认证配置导致的 401/403，但必须仍返回统一错误形状；
- `API_CONTRACT_RUN_LIVE=1` 且提供 `API_CONTRACT_API_KEY`、`API_CONTRACT_QUERY` 时，再执行一个受控 Search 成功路径和 native Chat JSON/SSE smoke；不设置时不伪造成功证据；
- 不写入或打印 API key/token，不把响应中的 chunk/prompt 写入仓库；日志只保存状态码、字段检查和截断后的错误摘要。

接入 `scripts/verify-release.sh`：静态 OpenAPI/manifest 检查随基本发布门禁执行；需要运行服务的黑盒部分由 `--with-runtime-e2e` 或显式 `API_CONTRACT_BASE_URL` 触发，避免普通无服务 `mvn test` 被隐式绑定到外部环境。

### 4.2 EvidenceAssessment

新增 API DTO `EvidenceAssessment`，字段冻结为：

- `status`：上述稳定状态字符串；
- `retrievalExecuted`：本请求是否实际执行过检索；
- `sourceCount`：最终对 Client 可见的 source 数量；
- `citedSourceCount`：合法引用覆盖的 source 数量；
- `invalidCitationIds`：去重后的非法引用 id，按现有 validator 规则输出，不暴露原始 prompt；
- `contextLimited`：是否发生上下文预算/检索预算裁剪；
- `safeAnswerApplied`：是否替换为安全回答；
- `reasonCode`：稳定机器码，至少覆盖 `NO_SOURCES`、`MISSING_CITATION`、`INVALID_CITATION`、`PARTIAL_CITATION`、`CONTEXT_BUDGET_LIMITED`、`EVALUATION_UNAVAILABLE`、`SUPPORTED`、`NOT_APPLICABLE`。

`ChatResponse` 增加可选顶层 `evidenceAssessment`；原有 `metadata.citationValidation` 保留，不要求 Client 迁移到 metadata。`ChatExecutionResult`、幂等 response snapshot/replay、RagChatService/native SSE 的 done payload 贯穿该字段。OpenAI-compatible body 不新增非标准字段，保持其现有 JSON shape；其响应继续通过 `X-Trace-Id` 和服务端日志/检索诊断关联。

安全门实现边界：

- `ChatExecutionService` 在最终 aggregated response 上运行纯确定性评估；不调用 judge，不请求第二次模型。
- 非流式 `KNOWLEDGE` 直接返回安全回答或原回答。
- 流式 `KNOWLEDGE` 默认不提前发送未评估的 provider 内容：暂存本轮内容，完成确定性评估后发送一个或少量 `content` delta，再发送 `sources`/`done`。`PLAIN` 和 `AGENT` 保持现有增量流式行为。
- 流式缓冲使用有界字符数，超限按 `CONTEXT_LIMITED`/安全回答处理，不把不受控 provider 输出写入 metadata 或 snapshot。
- `ChatEvent` 不删除既有事件；`Completed.metadata` 和 native SSE `done.evidenceAssessment` 提供新增状态。
- `KNOWLEDGE` 证据安全配置位于 `rag.chat.knowledge`，默认 `evidence-safety-enabled=true`，默认安全回答文本可配置，并冻结 `max-buffered-answer-chars=64000`（实现中限制在 1,024～262,144）；只用于应急关闭或本地兼容排障，文档明确关闭会恢复不安全 legacy 行为。

### 4.3 Trace correlation

不改变现有两个 ID 的语义：

- `X-Trace-Id` / `traceparent`：请求级、跨 HTTP 的 correlation id；
- `X-RAG-Retrieval-Trace-Id`：一次 Search/Chat/OpenAI retrieval session 的 UUID。

具体变化：

1. `RequestTraceFilter` 在 tracing enabled 时无论是否采样都生成/保留 `requestTraceId` request attribute 和 `X-Trace-Id` response header；sampling 仍只控制 MDC 详细日志。禁用 tracing 时保持现有无 header 行为。
2. `RetrievalTraceSession` 增加可选 request trace id；`toMetadata()` 写入 bounded `requestTraceId`，不写 API key、token、prompt 或完整 query。
3. `ChatCommand` 增加可选 `requestTraceId` 字段，并保留现有 Java 构造器与旧 mapper 调用；Native/OpenAI controller 或 mapper 从 request attribute 注入它。`ChatExecutionService` 的 `ChatResponse.traceId` 优先使用 command 中的 request trace id，其次才兼容旧的 retrieval session/MDC 读取，因此采样未命中、诊断关闭和纯 Java 测试都不会错误丢失或强行生成 Trace。
4. `ErrorResponse` 增加可选 `traceId`；GlobalExceptionHandler 从 request attribute 填充，既有 RFC 7807 和 alias 原样保留。OpenAI error body 不改变，响应头仍可关联。
5. `GET /rag/retrieval-traces` 增加可选 `requestTraceId` 过滤；repository 使用 owner/principal 条件、分页和现有 JSONB metadata 查询。详情和列表项增加可选 `requestTraceId`，旧构造方式通过兼容重载保留。
6. Native JSON/SSE 的 Chat 已有/继续保留 `traceId`；SSE `content/sources/done/error` 不删除既有字段，`done` 增加 `evidenceAssessment` 和 retrieval trace correlation；Search/OpenAI 继续使用 response headers。

所有新增观测字段都 bounded，Micrometer tag 不新增 requestTraceId、principal、collection、document 或 query 等高基数值。

## 5. 文件级实施顺序

### Slice A：契约基线与验证器

1. 新增 `testdata/api-contract/v1-compatibility.json`。
2. 新增 `scripts/lib/api-contract-check.mjs` 和 `scripts/verify-api-compatibility.sh`，先用静态 fixture/self-test 验证 helper。
3. 扩展 `OpenApiContractTest` 或新增 focused contract test，确保新 DTO、旧 alias、关键 endpoint 进入 `/v3/api-docs`。
4. 接入 `scripts/verify-release.sh` 的静态 contract gate；保持 runtime gate 显式触发。

### Slice B：确定性证据状态与安全回答

1. 新增 `EvidenceAssessment` DTO 和纯 Java evaluator/service；补齐状态、原因、budget 裁剪判定，并确保 evaluator 不受旧 `citation-validation-enabled` metadata 开关短路。
2. 扩展 `ChatRequest`/`RagChatProperties.KnowledgeProperties` 的可选安全配置，默认启用；保留旧构造器和默认值。
3. 修改 `ChatExecutionResult`、`ChatResponse`、`ChatExecutionService`，在 JSON 和 stream 两条路径统一评估；保留 `ChatExecutionResult` 现有 Java 构造器重载；更新 `ChatTurnOperationService` snapshot/replay 白名单。
4. 扩展 native SSE `done` payload；OpenAI adapter 只保持现有 shape，验证其能接收安全回答且不泄漏额外字段。
5. 写 evidence evaluator、Chat execution、stream buffering、idempotent replay、SSE payload focused tests。

### Slice C：逐请求 Trace 关联

1. 扩展 `RequestTraceFilter` request attribute/header 行为和测试；新增稳定的 request-trace attribute 常量。
2. 扩展 `ChatCommand`（保留旧构造器）、`RetrievalTraceSession`、`RetrievalDiagnosticsService`、Chat/Search/OpenAI controller 传递 request trace。
3. 扩展 `ErrorResponse`/GlobalExceptionHandler、retrieval trace DTO、repository 查询和 controller 参数；为已有 retrieval trace record 保留旧构造器重载；不新增 migration。
4. 补充 principal isolation、JSONB filter、trace header、error envelope、Search/Chat/OpenAI correlation 测试。

### Slice D：文档、黑盒、全面验收

1. 同步 `docs/rest-api.md`、`docs/rest-api-zh-CN.md`、`docs/configuration.md`、`docs/configuration-zh-CN.md`、`docs/architecture.md`、`docs/architecture-zh-CN.md`、`docs/testing-guide.md`、`docs/testing-guide-zh-CN.md`；必要时更新 `docs/business-client-integration*` 与 `docs/SSE-PROTOCOL.md`。
2. 更新活动 progress；实施阶段先过基本集成硬门槛，再做实现 `3/3` 检查。
3. 运行 Mock/Focused、Maven、PostgreSQL/HTTP、`scripts/dev.sh`、API black-box、OpenAI compatibility、retrieval diagnostics、project docs、lock guard 和 diff checks。
4. `.env` 已授权时执行有界真实 LLM smoke，验证证据状态/安全回答/trace 关联；真实模型不是确定性测试的替代。

## 6. 验收矩阵

| 能力 | 必测内容 | 证据 |
|---|---|---|
| Manifest | `/v3/api-docs` path/method/schema/type、可选字段不变 required、错误 alias 保留 | Node helper 输出 + Maven contract test |
| Black-box | 无模型 400/401/403/error envelope/header；可选 Search/Chat JSON/SSE 成功 | `verify-api-compatibility.sh` summary |
| Evidence DTO | 8 个状态、原因码、source/citation 计数、非法 id、contextLimited | evaluator/unit tests |
| Native Chat JSON | supported 保留原答；无来源/漏引/非法引/裁剪返回安全答；旧字段全在 | controller/integration tests |
| Native Chat SSE | `content/sources/done/error` 事件不删除；KNOWLEDGE 不提前泄漏未评估答案；done 有 evidence | SSE payload tests |
| Idempotency | 首次与 replay 的 answer、evidence、metadata 一致；旧 snapshot 可读 | turn operation tests |
| OpenAI | 非流式/流式 shape 不变；安全答可用；error/header 可关联 | OpenAI controller tests + script |
| Trace | sampling 0 仍有 request header/attribute；MDC sampling 语义不变；W3C 保持 | filter tests |
| Trace query | requestTraceId 过滤只返回当前 principal；详情/list 暴露关联值 | repository/service/controller tests + PostgreSQL IT |
| Failure isolation | 诊断写入失败、JSONB 过滤失败或观测旁路异常不阻断业务 | fail-open tests |
| Build/runtime | compile/test-compile、全 Maven、`scripts/dev.sh` readiness、真实服务 manifest/blackbox | logs and summaries |
| Docs/Git | 双语文档、project docs、无锁、diff check、无 secret；工作区最终干净 | commands + git status |

WebUI 对本批不做新的产品功能验收；但因共享 DTO/OpenAPI 可能受影响，至少运行现有 TypeScript/Vitest/build，失败需如实记录。

## 7. 失败、回滚与恢复

- API manifest 只做“最低兼容基线”，如果真实 API 新增合法字段，不需要修改 manifest；只有删除/改类型/把旧可选字段变必填才阻断。
- Evidence evaluator 是纯确定性旁路；异常时不得让请求变成 500，但对 `KNOWLEDGE` 回答采用 fail-closed：返回固定安全回答，`status=EVALUATION_UNAVAILABLE`、`reasonCode=EVALUATION_UNAVAILABLE`、`safeAnswerApplied=true`，并记录受控日志。只有诊断持久化/Trace 查询失败才 fail-open 为“没有诊断详情”；不得把观测故障误当成证据充分。配置显式关闭安全门时才允许保留原回答。
- 流式安全缓冲超限使用固定安全回答并完成 SSE；不发送 provider 原文，不等待第二次模型调用。
- Trace persistence/query 失败只降级为没有诊断详情；业务 response 和 `X-Trace-Id` 保持可用。
- 代码回滚只需回滚本批提交；不需要手工 SQL。因为不新增 migration、不修改旧迁移，数据库可直接运行旧/新服务版本。新增 JSON metadata 对旧服务透明。
- 若实施中必须改 schema，先停止代码实施，补充新的 Flyway 版本、升级/回退边界和迁移测试，并把规划检查计数重置为 `0/3`。
- 会话中断恢复入口：先读本文件第 3、4、5、6 节，再读 progress 的最新账本；不要把聊天记录当唯一上下文。

## 8. 规划检查记录

按 `docs/delivery-workflow-zh-CN.md` 执行；任一实质问题修复后计数归零。规划必须达到连续三轮无修改才允许进入 Slice A。

### 第 1 轮：需求闭环、自包含性、默认决策与非目标

- 时间：2026-10-15。
- 范围：用户选定的三项是否全部覆盖；API 兼容承诺；安全回答默认；Trace 关联；无迁移/回滚边界；中断后可恢复性。
- 发现：未发现实质问题。
- 处理：无需修改。
- 结果：通过；计数 `1/3`。

### 第 2 轮：代码、API、数据、安全、并发与可行性

- 时间：2026-10-15。
- 范围：Controller/DTO/SSE/OpenAI/Filter/Repository/JSONB/ACL/幂等 snapshot/stream buffer 的实际代码交叉验证。
- 发现：未发现实质问题；已确认 request Trace 有独立 command 传递载体、OpenAI 请求字段有兼容基线、旧构造器有保留要求，且 JSONB 查询保留 principal 隔离。
- 处理：无需修改。
- 结果：通过；计数 `2/3`。

### 第 3 轮：实施顺序、验收、发布、回滚与恢复

- 时间：2026-10-15。
- 范围：文件切片顺序、基本硬门槛、黑盒与真实 LLM 边界、双语文档、脚本、Git 交付和故障恢复。
- 发现：未发现实质问题。
- 处理：无需修改。
- 结果：通过；计数 `3/3`。规划收敛，允许进入 Slice A。

### 修订后最终复核：OpenAI request required 语义

- 第 1 轮：2026-10-15；范围为修订后的 OpenAI `model`/`messages` required 语义、manifest 基线和需求闭环；未发现问题，未修改文档。
- 第 2 轮：2026-10-15；范围为修订后的代码事实、API/SSE/OpenAI、ACL、幂等、Trace 和迁移边界；未发现问题，未修改文档。
- 第 3 轮：2026-10-15；范围为修订后的实施顺序、测试验收、文档和 Git 交付；未发现问题，未修改文档。
- 结果：连续 `3/3` 无修改复核通过，允许开始 Slice A。

## 9. 完成定义

只有同时满足以下条件，本批才算完成：

1. 规划连续 `3/3` 无修改已记录；
2. 三个 Slice 均实施，生产代码、测试、脚本和相关双语文档同步完成；
3. API 兼容 manifest/黑盒检查通过，既有 endpoint/field/alias/OpenAI/SSE 契约没有破坏性变化；
4. EvidenceAssessment 默认启用，安全回答覆盖 JSON、SSE、幂等 replay，普通 Chat 不额外调用评测模型；
5. Trace 可以在 response header、Chat/Error body、retrieval diagnostics 中关联，ACL 和 fail-open 证据通过；
6. 基本集成硬门槛和适用的全量/回归/迁移（如有）测试通过；
7. 实现阶段连续 `3/3` 无修改已记录；
8. 正式文档、progress、验证摘要和 Git 状态可复核；按用户交付要求执行 commit/push 后，工作区恢复干净。
