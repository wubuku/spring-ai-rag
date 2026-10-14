# WeKnora 借鉴第二批：引用协议与流式安全实施规划

> 状态：已完成；引用兜底表述、实现与验收均已收敛，规划复审和实现复审均连续 `3/3` 通过。
> 工作区已有此前启动的未提交实现改动，
> 继续实施前必须先按 progress 账本审阅并保留全部既有改动。
>
> 本规划只定义第二批的第一组可交付改进：请求级引用注册表的兼容增量、原生 Chat SSE
> 事件序号与断线重试去重。自适应分块、检索上下文组装、Route Policy self-check 和
> Parser SPI 不属于本批次。

## 1. 决策摘要

### 1.1 直接实施的范围

本批次将把现有“按 `S1` 编号的来源列表”提升为一个**请求/attempt 生命周期内的引用注册
表**，并让原生 Chat SSE 具备可选的单调事件序号。实现目标是：

1. 每个进入授权检索结果集的来源只获得一个稳定的请求级 handle，例如 `c1`；同一
   `documentId + chunkIndex` 在同一 attempt 内重复命中时复用 handle。
2. 保留现有 `citationId: S1`，不破坏非流式 API、历史数据、WebUI 和已有客户端；新
   `sourceHandle: c1` 作为可选字段增量返回。
3. 知识模式的提示词和全部内置检索 Agent 工具结果同时提供新 handle；服务端校验新旧两种
   引用形式，未注册的 handle 仍然被判定为非法引用。
4. 原生 `/api/v1/rag/chat/stream` 的业务事件 payload 增加 `sequence`，每个响应从 `1`
   开始递增；WebUI 在同一逻辑 turn 的重试中忽略已处理或倒退的序号，避免 snapshot
   replay 造成答案重复。
5. `sources` 仍先于 `done`，`done` 与 `error` 仍互斥；OpenAI 兼容 `/v1/chat/completions`
   继续只输出 OpenAI 协议，不暴露原生 sources/tool 事件或本批次的扩展字段。

### 1.2 明确不做的事情

- 不新增数据库表、Flyway 迁移或独立的持久化引用注册表。
- 不新增“按 handle 查询来源”的 HTTP 端点；handle 只能在当前回答和随响应返回的来源快照
  中使用。
- 不在本批次实现断线后的服务器端事件日志、`Last-Event-ID` 续传或跨进程 replay。当前
  已有 keyed turn response snapshot 负责最终回放；本批只解决客户端重试时的重复消费。
- 不复制外部项目的内部协议、名称或领域对象；生产契约只使用本仓通用的 `sourceHandle`
  和 `citationRegistry` 元数据。
- 不新增按 handle 查询来源或每个 UI citation 单独查询 ACL/版本的端点；handle 本身不是
  授权凭据。必须保留现有 keyed response replay 前的 `ChatAuthorizationService.verifyReplay()`
  来源/授权复核和 fail-closed 行为，不得因为新增 handle 或 snapshot 字段而跳过或弱化它。
  本批仅保证未进入本次 registry 的引用不能通过引用校验，不另造一套重复授权机制。
- 不改动 `/v1/chat/completions` 的标准 OpenAI SSE 事件格式。
- 本批统一覆盖全部一方内置检索 Agent 工具：`KnowledgeSearchTool`、
  `StaticKnowledgeSearchTool`、`JsonRecordSearchTool`。外部自定义工具若要产出可校验引用，
  必须另行定义与请求级 registry 的集成契约，不在本批通过猜测或包装任意工具结果来兼容。

### 1.3 默认值与可逆边界

| 项目 | 推荐默认 | 理由 | 可逆边界 |
|---|---|---|---|
| 新引用前缀 | `c`，从 `c1` 开始 | 与旧 `S1` 视觉区分，避免把内部数据库 ID 暴露为身份 | 只要保留 `citationId`，可关闭新字段或回退提示词 |
| 旧引用兼容 | 始终接受 `[S1]` | 已有模型输出和历史回答不应因升级失效 | 未来可在新版本另行废弃，不影响本批数据库数据 |
| 新引用返回字段 | `ChatSource.sourceHandle`，可选 | 不改变必填字段和旧客户端反序列化 | 删除字段即可回退；历史 JSON 缺字段按 `null` 读取 |
| registry 元数据 | `metadata.citationRegistry`，版本 `1` | 让诊断和回放能知道引用协议版本 | 只影响 metadata，不影响主答案 |
| SSE 序号 | 原生 Chat SSE payload 的 `sequence`，从 `1` 开始 | 无需改变 `SseEmitter` 基础工具和标准 OpenAI 端点 | 老服务无该字段时 WebUI 继续按旧逻辑处理 |
| 序号异常 | `sequence <= lastSequence` 忽略；缺失序号按旧协议接受 | 兼容旧部署；去重保证仅适用于客户端和服务端都发送/识别 sequence 的请求 | 只在 WebUI decoder 层切换，服务端不需要数据修复 |
| 来源身份 | `documentId:chunkIndex`；缺少任一部分不注册，显式 `chunkIndex=0` 是有效值 | 与当前检索去重语义一致，同时避免把 DTO 默认值误作已验证身份 | 将来若引入稳定 chunk revision，可升级 registry key 和版本 |

## 2. 当前基线与问题

以下事实在实施前已通过代码和正式文档核对；代码和测试仍是最终真相：

- `ChatSource` 当前只有 `citationId`、文档/块信息、评分和过滤后的 metadata，没有请求级
  source handle。
- `RetrievalResult.chunkIndex` 当前是 primitive `int`；缺失字段会静默变成 `0`，与合法的首块
  索引无法区分。它属于公开 API DTO，直接把 getter/setter 改为 `Integer` 会改变 JVM 方法签名。
  本批必须保留现有公开 `int getChunkIndex()` / `void setChunkIndex(int)` 签名，同时增加仅供内部
  识别缺失值的存在性标记/nullable accessor；正常显式设置的 `0` 与未设置必须可区分，旧 JSON
  表示和已有 Java 二进制调用保持兼容。
- `RetrievalTraceCollector` 已在 attempt 内按 `documentId:chunkIndex` 去重、按首次出现
  顺序分配 `S1`，并通过 `maxUniqueSources` 防止来源无限增长；它是本批 registry 的最佳
  承载点，不另造并发缓存。
- `CitationQueryAugmenter` 把 KNOWLEDGE 资料标记为 `[S1]`；三个内置检索 Agent 工具都向
  模型返回 `citationId`，其中 `StaticKnowledgeSearchTool` 和 `JsonRecordSearchTool` 尚未
  返回 `sourceHandle`；`CitationValidator` 只识别 `[S1]`。只改知识库工具会导致同一回答中
  不同检索工具的引用协议不一致。
- `ChatExecutionService.extractSources()` 将最终来源快照从 Spring AI 文档上下文或
  `RetrievalTraceCollector.sources()` 生成，最终结果 metadata 会持久化到 history/turn
  snapshot。`ChatSessionCoordinator` 已将完整 `sources` 写入 durable history，故不需要
  新迁移来保存 `sourceHandle`。
- keyed turn replay 并非直接序列化任意 `ChatResponse`：
  `ChatTurnOperationService.stableSource()` 手动复制每个 `ChatSource`，
  `stableMetadata()` 只保留白名单字段。因此新增 handle 若不补到 source copy、registry
  metadata 若不补到 metadata allowlist，原答复可能有 handle 而幂等重放丢失它们；实现和测试
  必须显式覆盖这两个边界。
- 原生 controller 将 `ChatEvent` 映射为 `content/tool_start/tool_result/sources/done/error`；
  keyed stream 走完整 response snapshot，重试会再次发送同一逻辑回答。当前 WebUI 已检查
  turn ID 和终态，但没有按事件序号去重。
- keyed turn 的 status/replay 在反序列化响应快照前由 `ChatAuthorizationService.verifyReplay()`
  复核调用方和来源授权；`ChatAuthorizationSourceVerifyTest` 覆盖来源删除、禁用、移动及
  Collection ACL 收紧。此既有安全边界属于不可回归项。
- OpenAI compatibility controller 只将 `ContentDelta` 和 `Completed` 映射为标准 OpenAI
  chunk，并以 `[DONE]` 收尾；它不应被原生扩展污染。

近距离参考：

- [WeKnora 调研结论与路线](../WEKNORA_REFERENCE_RESEARCH.md#42-p0-请求级引用注册表与流式引用安全)
- [当前 SSE 协议](../../SSE-PROTOCOL.md)
- [Chat 执行内核](../../../spring-ai-rag-core/src/main/java/com/springairag/core/chat/ChatExecutionService.java)
- [检索追踪与来源去重](../../../spring-ai-rag-core/src/main/java/com/springairag/core/chat/RetrievalTraceCollector.java)
- [原生 SSE controller](../../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagChatController.java)
- [WebUI decoder](../../../spring-ai-rag-webui/src/hooks/useSSE.ts)

## 3. 目标契约

### 3.1 `ChatSource`

在现有 JSON 中新增可选字段：

```json
{
  "citationId": "S1",
  "sourceHandle": "c1",
  "documentId": "42",
  "chunkIndex": 3,
  "title": "Brand Guide",
  "chunkText": "..."
}
```

语义：

- `citationId` 是现有兼容标识，继续用于历史响应和旧客户端。
- `sourceHandle` 是本次请求内的模型可引用句柄，不是数据库主键、下载 URL 或跨请求稳定
  身份；它不允许单独用于服务端资源寻址。
- 如果来源未被本次 registry 纳入，不能出现在最终 sources 快照；因此正常响应中
  `sourceHandle` 与 `citationId` 成对出现，老历史记录缺少 `sourceHandle` 时不补造。

### 3.2 registry metadata

成功结果 metadata 增加：

```json
{
  "citationRegistry": {
    "version": 1,
    "handlePrefix": "c",
    "sourceCount": 2,
    "handles": ["c1", "c2"]
  }
}
```

只记录协议诊断所需的短值，不写入原始查询、Token、ACL 细节或未过滤 metadata。列表顺序
必须与最终 `sources` 顺序一致。

### 3.3 模型引用输入与校验

- KNOWLEDGE prompt 以 registry 实际返回的 `[cN]` 标记资料，同时在系统/用户指令中说明历史
  `[SN]` 输出仍可接受；服务端 validator 同时识别两种形式。`CitationQueryAugmenter`
  必须从 `Query.context` 的 server-owned `AuthorizedRetrievalContext` 取 registry，并按
  `documentId + chunkIndex` 解析每个 `Document` 的真实 handle；不能用文档数组下标盲造
  `cN`。解析不到 handle 的文档不注入引用资料（生产路径不应发生），没有授权上下文的
  独立单测/兼容调用只保留现有 `[SN]` 标记，不伪造新 handle。
- Agent tool result 每项同时返回 `sourceHandle: cN` 和旧 `citationId: SN`，tool description
  说明回答应优先使用 `[cN]`。
- validator 内部把每个 source 的 `sourceHandle` 与 `citationId` 作为同一来源的 alias；同一
  来源若同时以 `[c1]`/`[S1]` 出现只计一次，`CitationValidation` 对外仍优先返回旧的
  `S#` canonical id，避免已有评估/UI 统计格式改变；未注册的 `[c999]`、`[S999]` 进入
  `invalidCitations`。
- `ChatExecutionService.buildSystemPrompt()` 的 KNOWLEDGE 指令明确优先使用检索资料给出的
  `[cN]`，同时允许兼容 `[SN]`；AGENT 指令要求仅引用内置工具实际返回的 `[cN]`/`[SN]`，
  不可自行编造或把工具之外的 ID 当作来源。没有授权 retrieval context 的兼容调用仍允许
  `CitationQueryAugmenter` 保留 `[SN]`，不得为迁就新提示而伪造 handle。
- PLAIN 模式仍返回 `NOT_APPLICABLE`，即使用户文本偶然包含类似 token。

### 3.4 原生 Chat SSE

以下事件的 JSON payload 增加正整数 `sequence`：`content`、`tool_start`、`tool_result`、
`sources`、`done`、`error`。同一 HTTP 响应中严格递增；心跳 comment 不编号。`sources` 的
来源对象包含 `sourceHandle`（新响应）和 `citationId`（兼容响应）。

WebUI 规则（当前 hook 总是先发送 `Idempotency-Key`，原生 keyed stream 的 snapshot 事件最多
为 `content/sources/done` 三个业务事件）：

1. 没有 `sequence` 的旧事件照旧处理。
2. 本次 `send()` 生命周期保存 `lastSequence`；事件带序号且 `sequence <= lastSequence`
   时忽略，不触发任何回调。
3. 新事件序号大于上次值时先更新 `lastSequence` 再分发。
4. 当前 keyed stream 在执行完成后返回确定性的响应快照：answer、sources（若非空）、done
   的帧形状和序号顺序在同一 idempotency key 的重放中保持一致；WebUI 跨 HTTP 重试保留
   `lastSequence`，因此重放的已消费帧被去重，后续更高序号的 sources/done 仍能完成该 turn。
   若以后改为真正的增量 keyed stream，必须先重新设计跨重试序号（不能只保留每个 HTTP
   响应从 1 开始的计数）。
5. 终态仍由现有 `terminalRef` 控制；序号去重不能让 `error` 和 `done` 同时生效。
6. 不把序号暴露为 UI 内容，不改变现有回调签名。

## 4. 文件级实施顺序

### Slice A：引用注册表和 DTO

1. 新增 `spring-ai-rag-core/src/main/java/com/springairag/core/chat/CitationRegistry.java`，
   负责 source key、`cN`/`SN` 双标识、注册上限、快照 metadata 和只读解析。source key
   必须同时包含非空 `documentId` 与已显式设置的 `chunkIndex`；身份不完整的结果不得注册、不得
   消耗引用预算，也不得通过后续 handle 查询暴露。`RetrievalResult` 保留公开 primitive
   getter/setter 方法签名，新增 `hasChunkIndex()`/nullable accessor 一类内部 presence API；这些
   helper 用 `@JsonIgnore` 防止 Jackson 将其序列化成额外 JSON 属性。默认未设置保持“缺失”，
   显式 `0` 保持有效。缺失值不能在 Jackson/API 历史 JSON 形状中引入新变化，旧 getter 继续
   提供既有默认数值；所有 registry/内部 identity 路径改用 presence API，避免将默认 `0` 误注册。
   逐一审计该字段的生产读取/复制/排序/映射、`equals`/`hashCode` 路径，
   避免空值语义导致 NPE、错误相等或被回填为 `0`。正常检索生产者必须继续显式提供索引；无法提供
   索引的候选只是不具备 citation identity，不得注册或被错误合并。具体包括：
   `RetrievalDocumentMapper` 不为 null metadata 写入 `chunkIndex`；`ProjectRerankPostProcessor`
   与 `StaticKnowledgeSearchTool` 不得将缺失值转成 `0`；`RetrievalUtils` 的排序对 null 安全，
   且不把多个缺失 identity 的候选拼成相同 `documentId:null` 键跨通道错误合并，无法确认同一
   chunk 时按独立候选保留。用现有 mapper、processor、static-tool 和 retrieval-utils 测试验证
   null 穿透、合法零索引及缺失 identity 不坍缩。
2. 在 `RetrievalTraceCollector` 内持有 registry，保留现有公开方法行为；`citationId()` 继续
   返回 `S#`，新增 `sourceHandle()` 和 registry snapshot 方法。
3. 在 `ChatSource` 增加 `sourceHandle` getter/setter、equals/hashCode 和 schema 说明；同步
   WebUI `ChatSource` 类型。

### Slice B：模型上下文、工具和校验

1. 更新 `ChatExecutionService.buildSystemPrompt()`：KNOWLEDGE 模式优先要求 `[cN]` 并明确
   `[SN]` 仍兼容；AGENT 模式要求引用工具结果中真实返回的 handle、不得编造来源引用。同步
   prompt 单测断言两种模式的指令以及 PLAIN 模式无引用要求。
2. 只在 `ChatExecutionService.extractSources()` 的结果组装处填充 `sourceHandle`，不让
   通用 `RetrievalDocumentMapper` 依赖请求状态；确保 KNOWLEDGE、AGENT、重试候选三条路径
   都从各自 attempt 的同一 registry 解析 handle。
3. 修改 `CitationQueryAugmenter`：有授权上下文时，只有 registry 能解析出 `cN` 的文档才注入
   提示词；无法解析时 fail closed 跳过该文档，不能退回到可能未经 registry 确认的 `[SN]`。
   没有授权上下文的独立测试/兼容调用才保留现有 `[SN]` 编号；不能把 `documentId`、文件路径
   或 ACL 信息注入为 handle。
4. 修改 `KnowledgeSearchTool`、`StaticKnowledgeSearchTool`、`JsonRecordSearchTool`：每个实际
   输出的来源同时返回 `sourceHandle` 和旧 `citationId`，工具定义说明模型优先以 `[cN]`
   引用；只返回注册表成功纳入的来源，`resultCount` 必须与预算裁剪后的输出条目一致。对
   `StaticKnowledgeSearchTool`，在加入 JSON envelope 后重新按序列化字符数执行有界裁剪：从
   尾部移除来源、同步移除对应 exposed 结果并更新 `resultCount`；若单条仍超限，缩短 snippet，
   最终仍超限则 fail closed 返回无 sources 的最小错误结果。对 `JsonRecordSearchTool`，记录
   裁剪、payload omission 和序列化超限回退不得留下“工具结果有 handle、最终 sources 无此来源”
   的伪引用；三种工具最终输出都不得超过 `maxToolResultCharacters`。
5. 修改 `CitationValidator` 接受 `[cN]` 和 `[SN]`，保持现有 `CitationValidation` 状态和
   去重统计语义。
6. 在 `ChatExecutionService.toResult()` 中基于最终 `ChatSource` 列表投影 registry metadata
   （不直接把 attempt 中可能被去重/预算淘汰的全部 registry 条目写进结果），流式收尾直接
   携带该 metadata；确保 persist/replay 使用同一来源快照，不在 controller 临时重编号。
7. 更新 `ChatTurnOperationService.stableSource()` 复制可选 `sourceHandle`，并将
   `citationRegistry` 纳入 `stableMetadata()` allowlist；扩展 snapshot/claim-complete 测试验证
   序列化后的 keyed response 及 replay 同时保留来源 handle 和 metadata，历史响应缺字段仍兼容。
   不修改 `ChatAuthorizationService.verifyReplay()` 的来源、principal、scope/ACL 复核；将
   `ChatAuthorizationSourceVerifyTest` 保留为本批必跑安全回归。

### Slice C：原生 SSE 序号和 WebUI 去重

1. 在 `RagChatController` 的普通 stream 和 keyed snapshot emitter 中为每个业务事件维护
   本响应的递增计数；统一由 helper 将 `sequence` 写入 payload，并保留无序号的旧反射/单测
   helper overload 以降低测试迁移风险。
2. 扩展 `ChatEvent.SourcesAvailable` 增加可选 registry metadata，并保留原两参数构造器；
   `ChatExecutionService` 传入结果 metadata 中的 registry 快照，controller 原生 `sources`
   同时保留旧 `sessionId/sources` 字段并新增 `citationRegistry`。keyed snapshot replay
   则从 `ChatResponse.metadata` 读取同一快照，不在 controller 重新编号；`done` metadata
   继续包含完整 registry metadata。
3. 不修改 `OpenAiCompatibilityController.mapEvent()` 的输出形态；补充测试证明标准端点
   不出现 `sourceHandle`、`sequence` 或原生 event name。
4. 修改 `useSSE.ts` 保存并校验序号；解析旧事件、CRLF、多行 data、EOF、终态和 retry 的
   既有行为必须保持。

### Slice D：文档、测试、收敛

1. 更新 `docs/SSE-PROTOCOL.md`、`docs/rest-api.md`、`docs/rest-api-zh-CN.md`、
   `docs/architecture.md`、`docs/architecture-zh-CN.md`、`docs/testing-guide*.md` 中的
   Chat source/SSE 事实；若未新增配置，不修改 configuration 文档。
2. 增加/更新 core 单测、controller Web/集成测试、WebUI Vitest 和中英文 i18n 提示；优先
   覆盖序号、兼容引用、未注册引用、同源 alias 去重、身份不完整/预算外来源不注册、三种
   内置工具都输出双标识、工具字符预算裁剪、retry 去重、sources-before-done 和 OpenAI
   隔离。特别验证 WebUI 在首次 keyed 快照只收到 `content(sequence=1)` 后重连时忽略重复
   content、接受 `sources(sequence=2)` 与 `done(sequence=3)`；controller replay 测试应证明
   同一快照每次都采用确定性序号。更新 `spring-ai-rag-webui/src/i18n/locales/en.json` 与
   `spring-ai-rag-webui/src/i18n/locales/zh-CN.json` 中仍声称“只检查 `[S1]`”的提示。
3. 更新 `scripts/real-llm-e2e-smoke.sh`，使用现有隔离 collection/document 增加一个有界
   `mode=AGENT` 请求，提示模型调用知识检索工具并引用检索到的验证文本；检查 response
   `sources` 中 `sourceHandle`/`citationId` 成对存在、registry handles 与 sources 对齐，且
   有界 `metadata.toolTranscript` 能证明实际工具结果返回双标识。真实模型答案引用文风可有
   差异，不用单一 exact wording 作为唯一判据。该脚本仍从 `.env` 读取凭据，不输出或持久化
   secret；真实调用设置超时并复用现有清理逻辑。只在用户已明确授权真实 provider 调用且环境
   凭据可用时执行该 smoke；否则保留脚本和确定性 Mock 覆盖，并在进度中明确记为 `NOT RUN`
   （未授权或环境缺失），不得把 Mock 结果表述为真实模型通过。
4. 按 progress 记录基本集成硬门槛、实现三轮检查和 Git 交付证据。
5. 本批无 feature flag；新字段和序号是向后兼容的增量。发布期间新 WebUI 若被路由到不带
   `sequence` 的旧后端，仍可完成响应，但重放事件无法去重；旧 WebUI 遇到新后端也继续工作，
   只是不会利用序号。默认先部署并确认所有后端实例都运行支持 sequence 的版本，再依赖
   “retry 不重复消费”的体验；执行端与客户端必须纳入同一兼容发布窗口。

## 5. 兼容、失败和回滚

### 5.1 兼容策略

- Java DTO 新字段使用 nullable + `NON_NULL`；旧客户端不认识时自然忽略。
- 后端读取旧 history JSON 时不强制要求 `sourceHandle`；旧 `S1` 仍可展示和校验。
- WebUI 对无序号 SSE 继续走旧路径；新 decoder 不改变回调接口。
- 混合版本期间，无序号旧服务仍可服务新 WebUI，但无法提供重放去重保证；必须等所有后端
  实例升级后，才能把该保证视为生产不变量。旧 WebUI 对新增序号字段向前兼容。
- OpenAI 端点继续只发送标准协议，避免破坏 OpenAI SDK。

### 5.2 失败语义

- registry 注册失败、来源 key 不完整或超过预算时不暴露该来源；不得生成一个看似有效但
  无法对应来源的 handle。
- 既有 retrieval budget/工具字符预算限制仍优先；被预算淘汰的结果不进入 `sources`、
  `handles` 或 tool `resultCount`。
- SSE 序号生成只影响输出包装；业务执行失败仍通过既有 `error` 终态表达。
- decoder 收到非法 JSON 或非法序号时忽略该业务事件；若最终没有合法 `done`，沿用已有
  retry/error 处理。

### 5.3 回滚

回滚不需要迁移或数据修复：

1. 先回退 WebUI decoder 代码，再回退后端字段/序号输出；旧客户端能继续读取 `S1` 和无
   `sequence` 响应。
2. 已写入 history/turn snapshot 的 `sourceHandle` 和 `citationRegistry` 属于可选 JSON，
   旧代码反序列化会忽略新字段；不删除历史记录。
3. 若新 prompt 导致 provider 引用质量下降，立即只回退 prompt 主标识为 `[S#]`，保留
   validator 对 `[cN]` 的兼容，后续另行评估。

## 6. 验收矩阵

| 层次 | 验证 | 通过标准 |
|---|---|---|
| API/Core identity | `spring-ai-rag-api` 的 `DtoTest`，以及 `CitationRegistryTest`、`RetrievalTraceCollector`、`RetrievalDocumentMapperTest`、`ProjectRerankPostProcessorTest`、`StaticKnowledgeSearchToolTest`、`RetrievalUtilsTest` | `DtoTest` 验证公开 primitive getter/setter 保持、helper 不进入 JSON、缺失与显式 `0` 可区分、equals/hashCode 稳定且旧 JSON 数值形状不变；重复来源复用 `cN/SN`；预算外或缺少 documentId/chunkIndex 的来源无 handle、不占预算；缺失索引经 mapper/processor/static-tool 后仍保持缺失，合法 `0` 仍可注册；排序对缺失值安全，无法确认 identity 的候选不按 `documentId:null` 错误合并；snapshot 顺序稳定 |
| Citation | `CitationValidator`、`CitationQueryAugmenter`、`KnowledgeSearchTool`、`StaticKnowledgeSearchTool`、`JsonRecordSearchTool` 及预算测试 | `[cN]`/`[SN]` 均可校验；未知 token 无效；三个内置工具只输出已注册来源且双标识一致；序列化输出不超字符预算，裁剪条目不进入 exposed final sources |
| Execution/replay | `ChatExecutionService`、`ChatTurnOperationSnapshotTailTest`、`ChatTurnOperationClaimCompleteTailTest`、`ChatAuthorizationSourceVerifyTest` | KNOWLEDGE/AGENT/PLAIN 的引用提示、候选 fallback、流式收尾 metadata 一致；幂等 response snapshot/replay 保留 sourceHandle 与 citationRegistry，旧 source JSON 仍兼容，现有来源/ACL replay 复核继续拒绝失效或越权来源 |
| Native HTTP | `RagChatControllerTest`、`RagChatControllerNativeSnapshotTest`、`RagChatControllerSseLifecycleTailTest`、`SseStreamE2ETest` | `sequence` 单调，sources 在 done 前，done/error 互斥，重放 sources 一致 |
| OpenAI HTTP | `OpenAiCompatibilityControllerWebTest` 及其现有 SSE 生命周期测试 | 仍只有 OpenAI chunk + `[DONE]`，不出现原生扩展 |
| WebUI | `useSSE.test.ts`、TypeScript、生产构建、`npm run check:design-system`、核心 Mock Playwright `npm run test:e2e:mock -- e2e/chat.spec.ts` | 旧事件兼容；重复/倒退 sequence 不重复回调；模拟 keyed 快照断线后重放，重复 content 被忽略且后续 sources/done 成功消费；WebUI 设计系统门禁通过 |
| Build/runtime | `mvn clean compile test-compile`、完整 `mvn test`、PostgreSQL profile 服务健康检查 | 后端完整编译和全量测试通过，服务可用项目默认 `postgresql` profile 启动；不以单元测试替代运行时门槛 |
| 文档 | `./scripts/verify-project-docs.sh`、`git diff --check` | 双语链接和事实一致，无密钥、断链、格式错误 |
| 安全并发 | `./scripts/verify-no-pessimistic-locks.sh` | 没有 `FOR UPDATE`、`SKIP LOCKED`、advisory lock |
| 回归/真实模型 | 受影响 Maven + 前端测试 + `./scripts/verify-gated-it.sh`；若用户明确授权且凭据可用，再运行 `scripts/real-llm-e2e-smoke.sh`（包含新增 Agent 场景） | 既有生命周期、幂等、ACL、历史和 WebUI 回归全绿；获准时由有界真实 provider smoke 验证 Tool Calling/提示变更；未获准或真实依赖/凭据不可用时记录 `NOT RUN` 原因，不能用 Mock 冒充真实模型通过 |

## 7. 实施门槛、检查和完成定义

### 7.1 实施前规划检查

规划进入实施前完成三轮且连续无实质问题：

1. 需求闭环、自包含性、默认决策和非目标；
2. 代码、API、数据、授权、并发和兼容性可行性；
3. 文件顺序、测试矩阵、回滚、发布和恢复风险。

### 7.2 实现后检查

实现后以相同 `0 → 3` 规则检查：

1. 引用注册和授权边界；
2. SSE 序号、终态、重试和 OpenAI 隔离；
3. 测试证据、长青文档、回滚和 Git 交付。

发现影响正确性、兼容性、安全、数据一致性或可实施性的实质问题，立即修复并将计数重置
为 `0`；行号漂移、格式润色和实施中自然出现的非实质细节不重置。

### 7.3 完成定义

本批执行按用户明确指示直接使用当前 `main` 工作区和 `main` 分支；这是针对
[交付工作流 §8](../../delivery-workflow-zh-CN.md#8-git-交付与并发工作区)“大块功能使用专用分支”
默认规则的本批一次性例外，不据此修改通用规则，也不新建分支或 worktree。必须无损保留当前
工作区全部既有改动，并在提交前核对完整 diff。

- 代码、测试和受影响双语长青文档完成；
- 基本集成硬门槛先于最终 review；
- 规划检查与实现检查均达到连续 `3/3`；
- `git diff --check`、项目文档门禁、禁悲观锁门禁和适用测试通过；
- 在 `main` 提交并推送，`main...origin/main` 为 `0 0`，工作区干净；
- plan/progress 同步稳定事实后，用 `git mv` 归档到 `docs/drafts/archive/`。

## 8. 规划检查记录

规划正文完成后，按项目工作流执行三轮只读检查。检查期间若发现实质问题，必须先修正文档并
将计数重置为 `0`，不得带着未解决问题进入实现。最终三轮结果和时间写入对应 progress，避免
用本节在检查中途改变规划正文。
