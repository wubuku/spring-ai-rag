# 对话能力重构实施规划

> **状态**：规划已冻结并实施完成；正文已按最终查询扩展实现同步。原始冻结正文
> SHA-256：
> `cd0edd9cc0c24017806217df348aa5c015b4d417ecef0a26dc5b72a3fded5b1c`。
> **规划日期**：2026-08-17
> **代码基线**：`main` @ `43600dd7f94f112dee694fab4ed3fd28088a8e26`，Spring AI
> `1.1.4`，Flyway 当前上限 V31。
> **工作区说明**：该基线已包含 Query Rewrite、Chat 会话导航、PDF 来源追踪等前序
> 实现。实施时不得批量回退这些能力；先用 characterization tests 固定有效行为，再
> 逐步替换被本规划明确淘汰的实现。
> **规划边界**：本文定义实施方案；实际上线状态和验证证据以配套实施进度文档为准。
> 2026-08-17 的正文同步只修正首轮查询扩展事实，不改变已冻结的架构决策。

相关现状与接口文档：

- [项目架构](../../architecture-zh-CN.md)
- [REST API](../../rest-api-zh-CN.md)
- [SSE 协议](../../SSE-PROTOCOL.md)
- [配置参考](../../configuration-zh-CN.md)
- [测试指南](../../testing-guide-zh-CN.md)
- [多 Collection 检索范围调研](2026-08-15_MULTI_COLLECTION_RETRIEVAL_SCOPE_RESEARCH.md)
- [OpenAI Chat Completions 兼容规划](2026-07-21_OPENAI_CHAT_COMPLETIONS_COMPATIBILITY_PLAN.md)

## 1. 执行摘要

本轮不是继续修补“找到某个关键词”一类自然语言句式，而是重建 Chat 的职责边界：

1. 使用 Spring AI 1.1.4 已提供的 `ChatClient`、Advisor、Tool Calling、RAG 和 Chat
   Memory 生命周期，不再维护语言相关的检索意图正则或自行实现工具调用循环。
2. 保留并适配本项目强于 Spring AI 默认实现的检索能力：
   Vector + 中英文全文检索、RRF 融合、Rerank、Embedding Profile、Collection/API Key
   ACL、document type 和 document ID 范围。
3. 把“对话”拆成三个明确模式，而不是用隐藏的自然语言规则猜测：
   - `KNOWLEDGE`：确定性知识库问答，每轮都执行 RAG；
   - `AGENT`：模型通过 Spring AI Tool Calling 按需、多轮调用知识检索工具；
   - `PLAIN`：普通模型对话，不检索知识库。
4. 现有 API 省略模式时继续使用 `KNOWLEDGE`，保持 RAG Chat 的兼容语义。WebUI
   显式展示模式；只有声明并验证支持 Tool Calling 的模型可选择 `AGENT`。
5. `KNOWLEDGE` 使用 Spring AI `RetrievalAugmentationAdvisor`，但注入项目自己的
   `DocumentRetriever`、`DocumentPostProcessor` 和 `QueryAugmenter`。
6. `AGENT` 使用 `@Tool` / `ToolCallback`、`ToolContext` 和 `ToolCallAdvisor`。模型只
   能决定检索词和不超过上限的结果数，不能在工具参数中传入或扩大 Collection、
   document、document type、API Key 等授权范围。
7. 流式和非流式统一到同一执行命令、同一模型候选链、同一来源收集器和同一持久化
   逻辑；SSE 增加 sources、tool lifecycle、done 和 error 的结构化事件。
8. 删除本轮 WIP 中的硬编码中英文检索 Pattern。全文检索 `@@` 命中不使用向量
   `min-score` 过滤属于独立正确修复，应保留。

最终目标不是“所有问题都强迫模型调用工具”，而是提供两种可验证、可选择的可靠路径：

```text
需要稳定、强制 grounding、低行为漂移  -> KNOWLEDGE
需要模型自主探索、改写、多次检索       -> AGENT
明确不需要知识库                       -> PLAIN
```

## 2. 问题定义与根因

### 2.1 用户可见故障

搜索页以短词 `风格基调` 检索时可以命中文档，但 Chat 输入
`找到 “风格基调” 相关的内容` 时曾回答没有找到。

已确认的直接原因：

1. Search 和 Chat 虽复用底层检索服务，实际送入 embedding 的文本不同。
2. Chat 把完整命令句送入检索，命令词改变向量邻居和较小 Top-K。
3. 旧 `QueryRewriteAdvisor` 产生改写结果，但检索链没有稳定消费“用于检索的聚焦
   query”。
4. `pg_jieba` 的布尔全文命中是有效命中，但曾被与向量相似度共用的
   `min-score` 错误过滤；`ts_rank` 与 cosine similarity 不是同一量纲。
5. PDF 提取文本可能含视觉相似但码位不同的兼容字符，例如 `风` 与 `⻛`，会影响精确
   全文匹配。

### 2.2 已验证但被否决的补丁

当前工作区曾增加：

- `RETRIEVAL_INTENT`
- 多组引号 Pattern
- `CHINESE_RETRIEVAL_WRAPPER`
- `resolveRetrievalQuery(...)`
- `rewrite.retrieval-query` Advisor context

该补丁能修复报告中的具体中文句式，并已通过真实 LLM 验证，但不能成为目标架构：

- 语言和句式不可穷举；
- 规则会逐步演变为隐式意图分类器；
- Java 代码承担了应由标准 Query Transformer 或模型 Tool Calling 处理的语义工作；
- 新语言、新标点和新表达必须继续修改代码；
- Search、RAG、Agent 工具容易形成三套 query 语义。

因此实施时应删除这组语言 Pattern 及其专用 context key，不继续扩展。

### 2.3 独立有效的检索修复

以下结论与 Chat 编排方案无关，不能在重构时回退：

- PostgreSQL 全文 `@@` 已表达布尔命中；
- provider-specific `ts_rank` 只用于同一全文通道内部排序；
- 向量 `min-score` 不能再次过滤全文命中；
- fused/RRF score 是排序信号，不是概率或百分比。

## 3. 当前代码事实

### 3.1 后端请求与执行链

[`ChatRequest`](../../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/ChatRequest.java)
当前包含：

- `message`、`sessionId`
- `maxResults`
- `useHybridSearch`、`useRerank`
- `domainId`、`model`
- `collectionScopeMode`
- `collectionKeys`、deprecated `collectionIds`
- `documentIds`
- `metadata`

Controller 在进入 service 前通过
[`CollectionRetrievalScopeResolver`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionRetrievalScopeResolver.java)
解析 Collection key/ID、API Key allow-list 和 document ID，形成不可扩权的
[`RetrievalScope`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalScope.java)。

当前 `RagChatService` 默认链是：

```text
QueryRewriteAdvisor (+10)
  -> HybridSearchAdvisor (+20)
  -> RerankAdvisor (+30)
  -> MessageChatMemoryAdvisor
  -> ChatModel
```

主要缺口：

| 主题 | 当前事实 | 影响 |
|---|---|---|
| 请求开关 | `useHybridSearch` / `useRerank` 没有进入 Chat Advisor context | API 字段看似可用，实际不生效 |
| Query Rewrite | 自定义 service 同时承担同义词、领域词、LLM 改写和语言 Pattern | 职责过宽，难以证明正确 |
| RAG 组件 | 三个自定义 Advisor 手工拼接常见 Modular RAG 流程 | 与 Spring AI 标准 RAG 生命周期重复 |
| Domain 配置 | Chat 使用 system prompt，但未真正消费 `getRetrievalConfig()` | 领域检索配置是死能力 |
| Domain hook | `postProcessAnswer()` / `isApplicable()` 未接入 | SPI 文档与运行行为不一致 |
| 来源 | 非流式从 `RerankAdvisor` response context 提取 sources | 绑定旧 Advisor key |
| 流式来源 | `.stream().content()` 丢弃结构化 response context | 无法发送 sources、usage、step metrics |
| fallback | 非流式按候选链重试；流式只解析一个显式 model | 同一请求因 stream 选择产生不同可靠性 |
| metadata | 非流式执行 PromptCustomizer；流式传 `null` | 同一请求产生不同 prompt |
| 历史 | 当前 WIP 已让流式完成后写业务历史 | 仍缺 sources、状态和取消语义 |
| 清空历史 | Repository 当前同时删除业务表和 `spring_ai_chat_memory` | Controller 注释仍声称不清 memory，文档漂移 |

### 3.2 当前检索能力必须保留

[`HybridRetrieverService`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java)
已具备：

- 活动 Embedding Profile 与固定维度校验；
- Vector 检索；
- 中文 `pg_jieba`、英文 PostgreSQL FTS、`pg_trgm` fallback；
- Vector/Full-text 并行执行与超时降级；
- RRF/加权融合；
- SQL 级 `RetrievalScope`；
- document type、document ID、Collection 范围下推；
- PDF 来源字段和 JSON record 隔离；
- 可选 rerank provider。

Spring AI 默认 `VectorStoreDocumentRetriever` 只覆盖简单向量检索，不能替换上述能力。
正确做法是实现 Spring AI 的 `DocumentRetriever` 接口，并在内部调用本项目检索服务。

### 3.3 当前模型路由

`ChatModelRouter` 与 `ConfiguredChatModelFactory` 已支持：

- `provider/modelId`；
- 同一 OpenAI-compatible endpoint 下多个 model；
- primary + fallback；
- OpenAI / Anthropic API 类型；
- 运行时模型列表和 WebUI 选择。

当前 `ModelItem` 没有 Tool Calling capability。虽然 Spring AI 1.1.4 的
`OpenAiChatOptions`、`AnthropicChatOptions` 和 `MiniMaxChatOptions` 都实现
`ToolCallingChatOptions`，这只证明 Java adapter 支持工具协议，不能证明某个上游
endpoint 或具体 model 真正支持 tools。

### 3.4 当前记忆和历史

项目有两类存储：

```text
spring_ai_chat_memory  -> Spring AI 给模型提供的短期窗口上下文
rag_chat_history       -> 业务查询、导出、审计使用的 user/assistant turn
```

当前 `RagChatHistoryRepository#deleteBySessionId` 已同时清理两者，但直接执行
`DELETE FROM spring_ai_chat_memory`，与 Spring AI repository schema 耦合。

`rag_chat_history.related_document_ids` 可以保存 document IDs，但当前成功路径传
`null`；历史 API 也没有完整 source snapshot，因此 WebUI 重载后会丢失引用。

另一个必须在本轮同时修复的安全边界：`sessionId` 当前由客户端可选传入，history
查询、导出、删除和 ChatMemory 都只按该值定位，没有绑定认证 principal。知道或猜到
另一个调用方的 session ID 就可能读取历史或混入模型记忆；sources snapshot 上线后还会
暴露当时的文档片段。因此不能只给 history 增加 sources，而不增加会话归属。

### 3.5 当前 WebUI 与 SSE

WebUI Chat 已有：

- POST SSE；
- URL 化 session；
- 历史加载；
- runtime model 选择；
- 三种 Collection scope；
- sources 展示容器；
- 新对话、侧栏和导出。

当前协议与 UI 存在漂移：

- Hook 能解析 `event:sources` 和 `event:error`，后端没有发送；
- done 事件当前 WIP 已增加 `sessionId`，正式 SSE 文档仍缺该字段；
- 历史记录只恢复文本，不恢复 sources；
- `ChatSource`、`ChatRequest`、`conversationId/sessionId` 在多个 TS 文件重复定义；
- 非流式 `chatApi.ask` 仍使用旧的 `response/conversationId` 类型，与 Java
  `answer/metadata.sessionId` 不一致；
- 前端把 score 乘 100 显示为百分比，但 fused/rerank score 不是校准概率；
- `reader.cancel()` 没有完整定义后端 subscription 取消、持久化和重试语义。

### 3.6 Spring AI 1.1.4 已确认 API

本规划已从本机 Maven 1.1.4 JAR、sources JAR 和 `javap` 交叉确认：

| 能力 | Spring AI 1.1.4 API |
|---|---|
| Modular RAG Advisor | `RetrievalAugmentationAdvisor` |
| 检索适配 | `DocumentRetriever` |
| Query 转换/扩展 | `QueryTransformer`、`CompressionQueryTransformer`、`MultiQueryExpander` |
| 检索后处理 | `DocumentPostProcessor` |
| Context 注入 | `QueryAugmenter`、`ContextualQueryAugmenter` |
| 工具声明 | `@Tool`、`@ToolParam`、`ToolCallback` |
| 服务端上下文 | `ChatClientRequestSpec.toolContext(Map)`、`org.springframework.ai.chat.model.ToolContext` |
| 工具循环 | model 内置执行和 `ToolCallAdvisor` |
| call/stream 工具循环 | `ToolCallAdvisor` 同时实现 `CallAdvisor` / `StreamAdvisor` |
| 记忆 | `MessageChatMemoryAdvisor`、`ChatMemory`、`ChatMemoryRepository` |
| 结构化流 | `StreamResponseSpec.chatClientResponse()` |

`spring-ai-rag` 当前只在父 POM dependency management 中管理版本，
`spring-ai-rag-core` 没有直接依赖它。实施标准 RAG Advisor 前需在 core POM 增加
显式 compile dependency。

### 3.7 参考项目可借鉴边界

本机参考项目
`/Users/yangjiefeng/Documents/wubuku/spring-ai-skills-demo` 实际使用 Spring AI
`1.1.2`。它展示了标准组合：

```java
builder.defaultAdvisors(
    skillsAdvisor,
    MessageChatMemoryAdvisor.builder(chatMemory).build(),
    VectorStoreChatMemoryAdvisor.builder(vectorStore).build(),
    QuestionAnswerAdvisor.builder(vectorStore).build()
).defaultTools(skillTools).build();
```

以及 `@Tool` / `@ToolParam` 让模型选择工具。

可借鉴：

- `defaultTools(...)` / request-level tools；
- Spring AI Memory Advisor；
- Advisor lifecycle；
- per-request conversation ID；
- Tool Calling 而不是自然语言 Pattern。

不能照搬：

- 项目中的 `JsonArgToolCallback` 是 1.1.2 workaround；
- AG-UI 代码有手工工具事件和文本 tool-call 解析；
- `QuestionAnswerAdvisor` 只有简单 VectorStore 检索；
- 参考项目部分说明与 POM 版本不一致。

## 4. 目标与非目标

### 4.1 目标

1. 用标准 Spring AI 扩展点重构 Chat 编排。
2. 提供确定性 RAG、agentic retrieval 和 plain chat 三种清晰模式。
3. Chat、Search、JSON record 继续共享底层混合检索语义。
4. API Key / Collection / document / document type 范围在任何工具调用中都不可扩大。
5. 删除语言相关检索意图 Pattern。
6. 让 `useHybridSearch`、`useRerank`、`maxResults` 和 domain retrieval config 真正生效。
7. 流式与非流式在 model routing、prompt、memory、sources、history、metrics 和错误上
   保持语义对等。
8. sources 可在实时响应和历史重载后恢复。
9. 工具调用有次数、重复 query、结果数、总耗时和输出大小预算。
10. 模型目录显式声明 Tool Calling capability。
11. WebUI 能选择模式、停止生成、查看可理解的工具进度和来源。
12. 提供一键验证脚本和真实 LLM 可选验收。
13. session history、export、clear 和 ChatMemory 按认证 principal 隔离；客户端
    `sessionId` 本身不作为授权凭据。

### 4.2 非目标

- 不实现通用多工具 Agent 平台、workflow engine 或 subagent scheduler。
- 不实现 MCP server；知识检索工具先作为进程内 Spring AI Tool。
- 不实现自然语言意图分类器。
- 不让模型选择或修改授权 Collection。
- 不允许模型调用写操作工具。
- 不在本轮实现长期语义记忆；`VectorStoreChatMemoryAdvisor` 不接入默认链。
- 不实施 `/v1/chat/completions`；只为其共享执行内核预留边界。
- 不实现断线后的 SSE replay/resume。
- 不自动把所有 configured model 推断为支持 tools。
- 不删除现有 `/api/v1/rag/chat`、`/ask`、`/stream`。
- 不用 Spring AI 简单 VectorStore RAG 替换项目混合检索。

## 5. 冻结的产品语义

### 5.1 三种模式

在 API 模块新增：

```java
public enum ChatMode {
    KNOWLEDGE,
    AGENT,
    PLAIN
}
```

| 模式 | 检索触发 | Spring AI 机制 | 适用场景 |
|---|---|---|---|
| `KNOWLEDGE` | 每轮固定执行一次 | `RetrievalAugmentationAdvisor` | 强 grounding、稳定引用、可预测延迟 |
| `AGENT` | 模型按需调用 0-N 次 | Tool Calling + `ToolCallAdvisor` | 探索、改写、多轮检索、比较材料 |
| `PLAIN` | 不检索 | ChatClient + Memory | 写作、闲聊、纯模型任务 |

规则：

- 现有 API 省略 `mode` 时推导为 `KNOWLEDGE`，保持兼容。
- 不提供基于关键词或语言 Pattern 的隐式模式选择。
- `AGENT` 请求若显式 model 不支持 tools，返回 `400 MODEL_CAPABILITY_UNSUPPORTED`，
  不静默降级为另一个语义。
- 未显式 model 时，router 可从候选链中选择第一个支持 tools 的候选。
- `PLAIN` 不执行 retrieval，并在响应 metadata 标明没有执行 retrieval。若请求显式携带
  collection/document scope 或通过 presence tracking 检测到 retrieval tuning override，
  返回 `400 RETRIEVAL_OPTIONS_NOT_ALLOWED`，避免“看似限制了范围但实际被忽略”的误解；
  DTO 自带而未显式提交的兼容默认不触发该错误。
- WebUI 显式展示三个模式；默认仍为 `KNOWLEDGE`。后续只有在真实评估证明
  `AGENT` 更稳定时才考虑改变 UI 默认值。

不增加 `AUTO` 模式。其行为会受模型能力、模型判断和配置影响，同一请求可能在不同部署
中走不同路径，不利于 API 可预测性、评估和排障。

### 5.2 Query 转换策略

`KNOWLEDGE` 不再调用 `QueryRewritingService.resolveRetrievalQuery()`。

推荐配置：

```yaml
rag:
  chat:
    knowledge:
      query-transformer: none       # application.yml 基础默认
      query-transform-timeout-seconds: 30
      query-expander-variants: 2
      query-expander-include-original: true
```

`application-prod.yml` 推荐：

```yaml
rag:
  chat:
    knowledge:
      query-transformer: spring-ai
```

`spring-ai` 策略只编排 Spring AI 内置能力：

- 无有效历史：不调用首轮 `RewriteQueryTransformer`，直接由内置
  `MultiQueryExpander` 生成检索变体；
- 有前序 user/assistant turn：先由内置 `CompressionQueryTransformer` 压缩为当前
  检索语义，再由内置 `MultiQueryExpander` 扩展检索查询；
- `MultiQueryExpander` 默认保留原始请求，并生成两个额外变体。项目只提供一段领域无关
  的提示词，要求至少一个变体逐字保留产品名、引号短语、编号、代码和其他特殊词；
- 内置 `ConcatenationDocumentJoiner` 负责合并和去重不同查询的结果。

项目侧 `HistoryAwareQueryTransformer implements QueryTransformer` 只负责从结构化
`Query.history()` 中识别是否存在前序对话，并在需要时委派内置 compression；它不解析
自然语言、不实现中文 Pattern、不自行合并查询。`none` 策略则不向 Advisor 注册查询
转换器或扩展器。

选择依据是消息结构，不是自然语言内容。路由外层只增加：

- 使用只绑定当前 resolved `ChatModel` 的专用 raw `ChatClient.Builder`，不继承业务
  ChatClient 的 Memory、RAG、Tool 或自定义 Advisor，避免 transformer 递归进入主链；
- 从 `Query.history()` 中排除 system message 和当前 user message，只把当前请求之前的
  user/assistant turn 交给 `CompressionQueryTransformer`；
- 超时；
- 异常时回退原 query；
- metrics；
- 不能把 transformer 的 prompt/response 当作用户对话写入 memory。

`AGENT` 不再预先做 LLM query rewrite。模型在第一轮 tool call 中直接生成
`searchKnowledge.query`，避免“planner rewrite + tool planning”双重额外调用。

取舍：

- 基础配置的 `none` 允许最小嵌入场景避免额外 LLM call；正常开发使用的
  `postgresql`/`local` profile 与 production 均启用 `spring-ai`。
- `spring-ai` 优先解决完整命令句、精确词保护和含上下文 follow-up 的检索质量。
- `AGENT` 的工具调用本身通常需要“生成 tool call + 根据结果作答”两次模型交互，
  但支持多次自主检索。
- 所有策略都必须用 goldenset 记录质量、延迟和调用次数，不能只凭主观选择默认值。

### 5.3 Memory 语义

模式与记忆正交。内部命令使用：

```java
public enum MemoryMode {
    SERVER,
    STATELESS
}
```

- 现有 `/api/v1/rag/chat/**` 默认 `SERVER`。
- WebUI 使用 `SERVER`。
- 未来 OpenAI-compatible adapter 默认 `STATELESS`，避免调用方 messages 与服务端历史
  重复。
- `SERVER` 使用 `MessageChatMemoryAdvisor` 和 `ChatMemory.CONVERSATION_ID`。
- `STATELESS` 不安装 Memory Advisor，不读写 `spring_ai_chat_memory`。
- 清空会话通过 `ChatMemory.clear(memoryConversationId)` 和业务 repository 删除完成，
  不再硬编码 Spring AI 内部表 SQL。
- 本轮不接入 `VectorStoreChatMemoryAdvisor`；知识文档与用户长期记忆是不同数据域和
  授权模型。

`MemoryMode` 是 core 内部执行策略，不加入当前公共 `ChatRequest`，也不放在 API
模块。现有 Chat/WebUI 固定映射 `SERVER`；未来 OpenAI-compatible adapter 映射
`STATELESS`。这样避免调用者关闭服务端记忆后绕过当前 history/审计语义，也不提前冻结
一个尚未发布的公共字段。

Memory 使用内部 namespace：

```text
public sessionId + authenticated principal stable ID
    -> deterministic 36-char memoryConversationId
    -> ChatMemory.CONVERSATION_ID
```

- public `sessionId` 仍用于 URL/API 展示；
- public `sessionId` 的唯一合法格式固定为 `[A-Za-z0-9._~-]{1,36}`。body、path、
  自动生成值和内部 service 入口统一经过同一个 validator；拒绝空白、斜杠、反斜杠、
  `%` 编码绕过、控制字符、Unicode 视觉混淆字符和超过 36 字符的值；
- `memoryConversationId` 使用稳定单向摘要/UUID 派生，满足 Spring AI JDBC schema
  `VARCHAR(36)`，不直接拼 raw API key；
- database key 使用 `keyId`，environment root、legacy static 和 auth-disabled local
  分别使用固定 principal 类型标识；
- 升级时不把旧的 raw-session ChatMemory 自动归属给任意 database key；这部分短期
  memory 采用 fail-secure reset，业务历史按第 9 节兼容规则保留。
- `SERVER` 模式对同一 `(principal, sessionId)` 只允许一个 in-flight turn；第二个请求
  立即返回 `409 SESSION_BUSY`，不排队，避免 Memory Advisor 写入、取消补偿和 history
  顺序互相覆盖。`STATELESS` 不受此限制。
- 该 single-flight 不能只用 JVM 内 `ConcurrentHashMap`。默认 PostgreSQL 实现使用
  `rag_chat_session_lease`：以 `(owner_principal_id, session_id)` 为主键，原子写入
  `owner_token`、`acquired_at`、`expires_at`，只有当前 token 可以续租和释放。
- call 与 stream 都必须周期续租，不能假设非流式一定短于初始 TTL。coordinator 为整个
  逻辑请求建立绝对 deadline：非流式来自 `rag.timeout.chat-ask-ms`，流式来自
  `rag.timeout.chat-stream-ms`；Query Transformer、retrieval、rerank、每个 candidate
  retry/fallback、tool rounds 和最终持久化事务共享剩余预算，不能每层
  重新获得一份完整 timeout。lease TTL 必须大于续租周期并留出数据库抖动余量。
- 任一次 token-fenced renew 返回 0 行或数据库失败，都视为失去 session 所有权：立即取消
  后续模型/工具工作，不再写 Memory/history，也不发送 `done`，只发送/抛出
  `CHAT_SESSION_LEASE_LOST`。不能在 lease 已丢失时提交 request-local Memory 或 release，
  因为新 owner 可能已经 acquire；共享持久化状态在模型执行期间未被旧请求修改。
- coordinator 到达绝对 deadline 时取消当前执行，在仍持有 lease 的前提下完成
  request-local 工作并 token-fenced 释放 lease，不提交 history/JDBC Memory，再以
  `CHAT_TIMEOUT` 终止；进程崩溃后 lease 自动过期，不永久阻塞会话。
- 不能让 `MessageChatMemoryAdvisor` 在长时间模型调用期间直接写共享
  `JdbcChatMemoryRepository`。它的 `before/after` 没有 lease token 参数；一旦续租失败，
  旧请求仍可能晚到写入新 owner 已重建的 Memory，无法实现 fencing。
- 每次成功 acquire 后，coordinator 从按 owner/session 查询到的已提交业务 history 读取
  最近 `maxMessages`，建立本次 attempt 专属的 Spring AI
  `MessageWindowChatMemory`（默认 `InMemoryChatMemoryRepository`），并交给
  `MessageChatMemoryAdvisor`。因此仍使用 Spring AI 的 Memory 生命周期和窗口规则，但模型
  执行期间只修改请求内状态，不触碰共享 JDBC memory。
- 每个 candidate/retry 都从同一已提交 baseline 创建新的 request-local ChatMemory。
  attempt 失败、超时、取消或丢租时直接丢弃该实例，不执行共享 Memory snapshot 恢复；
  fallback 不会继承失败 attempt 的 user/tool/assistant 消息。
- 最终成功 attempt 先得到完整 answer 和 request-local memory state，再进入一个短事务：
  coordinator 先通过原子状态机从 `RUNNING` 转为 `COMMITTING`，停止周期 renew 并等待正在
  执行的 renew 完成；只有尚未观察到 lease lost 才能进入事务。renew callback 在
  `COMMITTING/TERMINAL` 状态不得再把成功释放后的 0-row 结果误报为丢租。
- 最终持久化短事务
  对 `(ownerPrincipalId, sessionId, ownerToken)` 做未过期 token-fenced ownership check
  并锁住 lease 行，写入业务 history，把该 principal/session 的共享 JDBC ChatMemory
  通过 `ChatMemory.clear + add(committedMessages)` 同步到同一 committed window，最后
  删除/释放 lease。history 与 JDBC memory 使用同一 Spring transaction；任一步失败整体
  回滚，不发送成功响应或 `done`。事务持有 lease 行锁后，其他实例的 expiry takeover 会
  等待该短事务完成，不能与 commit 交叉。
- Spring AI 1.1.4 的 JDBC Memory 自动配置只向
  `JdbcChatMemoryRepository.builder()` 传入 `JdbcTemplate` 和 dialect，没有传入应用的
  `PlatformTransactionManager`；repository 因此会自行创建
  `DataSourceTransactionManager`。这不能作为与 JPA history 写入原子性的隐含保证。
  实施时由项目显式提供 `JdbcChatMemoryRepository` Bean，使用同一 `JdbcTemplate`、
  PostgreSQL dialect 和应用实际的 `PlatformTransactionManager` 构建，让 Spring AI 的
  `@ConditionalOnMissingBean` 自动配置退让。coordinator 的最终提交也使用这个 transaction
  manager；集成测试必须覆盖 Memory 写入后失败和 history 写入后失败两种故障注入，证明
  两张表都回滚。禁止依赖两个不同 transaction manager 对同一 DataSource “碰巧加入”
  同一事务。
- 业务 history 是崩溃恢复与下一 turn baseline 的 canonical source；共享 JDBC
  ChatMemory 是同步的 Spring AI 持久化投影，不作为 acquire 后模型上下文的唯一真相源。
  进程在模型调用期间崩溃不会留下共享孤儿 user message；若历史提交前崩溃，两者都不变。
- clear/delete 会话也必须先获取同一 lease；活动生成期间返回 `409 SESSION_BUSY`，避免
  一边清理 history/memory、一边由完成回调重新写回。clear、cancel release 和 TTL cleanup
  使用同一 `RUNNING -> COMMITTING -> TERMINAL` renewer 停止/等待协议。

### 5.4 来源与 score 语义

新增统一 `ChatSource` DTO，至少包含：

```text
citationId
documentId
chunkIndex
title
chunkText
score
vectorScore
fulltextScore
originalFilename
documentType
collectionKey
sourceType
metadata
```

兼容规则：

- 旧 `ChatResponse.SourceDocument` 可保留一个版本周期，内部映射到 `ChatSource`。
- `score` 文档改为“当前 query/config 内的排序信号”，不得描述为概率。
- WebUI 不再显示 `score * 100 + '%'`。
- 引用显示为“来源 1 / 来源 2 + 标题”，并可打开对应文档或 PDF 来源动作。
- 多次工具检索对相同 document chunk 去重，保留首次 citation ID 和最高相关排序信息。
- `metadata` 不是底层 Document metadata 的任意透传 Map；通过固定 allow-list 只暴露
  provenance、document type、collection key 等可公开字段。
- 默认 Chat response、tool result 和 history snapshot 都不包含完整
  `jsonbPayload`、未来 `xmlPayload` 或其他原始结构化 payload；需要原始记录时由已授权
  document/record API 按 ID 获取，避免把大 payload 送入模型、SSE 或历史表。
- `chunkText`/snippet 有服务端字符上限；history 保存的是同一经过裁剪和脱敏的 DTO。
- ChatSource 不保存或返回 PDF 的虚拟文件路径。WebUI 点击来源动作时只提交
  `documentId`，由 document-bound source endpoint 重新执行当前 API Key/Collection
  ACL，再通过现有 `RetrievalResultProvenance` 同等级校验解析实际文件；绝不依赖历史
  snapshot 中的路径，也不暴露宿主机绝对路径。

## 6. 目标架构

### 6.1 总体结构

```text
RagChatController / future protocol adapters
                  │
                  ▼
         ChatCommandMapper
                  │
                  ▼
          ChatExecutionService
     ┌────────────┼───────────────┐
     │            │               │
 KNOWLEDGE      AGENT           PLAIN
     │            │               │
 Retrieval      ToolCall          │
 Augmentation   Advisor           │
 Advisor          │               │
     └────────────┴───────────────┘
                  │
          ChatClientFactory
                  │
        ChatModelRouter candidates
                  │
                  ▼
       ChatExecutionResult / ChatEvent
                  │
       response / SSE / history mappers
```

共享检索内核：

```text
ProjectDocumentRetriever ─┐
                          ├─> AuthorizedKnowledgeRetriever
KnowledgeSearchTool ──────┘          │
                                     ▼
                           HybridRetrieverService
                                     │
                              ReRankingService
```

### 6.2 内部执行契约

不要继续让 Controller 直接驱动 `RagChatService` 的不同重载。新增内部不可变结构：

```java
record ChatCommand(
    List<Message> messages,
    String sessionId,
    ChatPrincipal principal,
    String memoryConversationId,
    ChatMode mode,
    MemoryMode memoryMode,
    String modelRef,
    String domainId,
    RetrievalScope retrievalScope,
    RetrievalOptions retrievalOptions,
    Map<String, Object> clientMetadata
) {}
```

`ChatPrincipal` 由认证 filter 的 request attributes 映射，至少区分 environment root、
database key 的 stable `keyId`、legacy static 和 auth-disabled local。它只携带稳定 ID、
类型和管理权限，不携带 raw credential。

`RetrievalOptions` 至少包含：

```text
maxResults
minScore
useHybridSearch
useRerank
vectorWeight
fulltextWeight
queryTransformerPolicy
```

当前 `ChatRequest` 的 `maxResults`、`useHybridSearch`、`useRerank` 是有默认值的
primitive，单看 getter 无法区分“JSON 省略”与“显式传入默认值”。为了让
request/domain/global 优先级真实可实现，同时不破坏既有 JSON 字段和 primitive getter：

- 保留现有字段、默认值、getter/setter 签名；
- setter 被 Jackson 或 Java 调用时设置对应的 `explicitlySet` transient flag；
- `ChatCommandMapper` 只在 flag 为 true 时生成 request override；
- 省略字段且没有显式 `domainId` 时保留当前 Chat API 兼容默认：
  `maxResults=5`、`useHybridSearch=true`、`useRerank=true`。不能直接改用
  `rag.retrieval.default-limit=10`，否则旧请求会静默改变结果集大小；
- 只有调用者显式选择一个存在的 `domainId` 且字段省略时，domain default 才参与合成；
- global retrieval properties 提供 weights、threshold 和 hard caps；Chat API compatibility
  defaults 是该 endpoint 的独立默认层。后续若要把 5 改成 10，应作为版本化行为变更，
  不能借本次 presence 修复顺带改变；
- 为 JSON 省略、显式 true/false/5 和 Java 直接调用分别写 contract tests。

客户端 `metadata` 与内部执行 context 必须分区：

- `ChatRequest.metadata` 只作为有大小、深度、key/value 类型限制的 `clientMetadata`
  传给 `PromptCustomizer` 和业务 history；默认上限 32 个 key、序列化后 16 KiB、
  最大深度 4，只允许 JSON scalar/list/object；
- 它不能再像当前 `metadata.forEach(a::param)` 一样写入 Advisor context，也不能写入
  `toolContext`，否则调用者可覆盖 conversation ID、retrieval scope、trace collector、
  tool budget 等服务端保留键；
- Advisor context 与 ToolContext 只由 `ChatCommandMapper` / coordinator 使用常量 key
  构造不可变 Map；若 client metadata 包含保留前缀 `rag.` / `spring.ai.` 或已知内部 key，
  保留在 client metadata 命名空间中供 customizer 读取，但绝不提升为内部参数；
- `PromptCustomizer` 收到 `Collections.unmodifiableMap(clientMetadata)`，不能回写执行状态。

`ChatExecutionResult` 至少包含：

```text
answer
sessionId
traceId
requestedModel
resolvedModel
mode
sources
usage
stepMetrics
finishReason
```

流式使用 sealed event：

```text
ContentDelta
ToolStarted
ToolFinished
SourcesAvailable
Completed
Failed
```

现有 DTO 和 SSE 只作为 mapper 层，不再决定内部执行逻辑。

### 6.3 确定性 RAG

在 `spring-ai-rag-core` 增加 `spring-ai-rag` compile dependency，并构建：

```java
RetrievalAugmentationAdvisor.builder()
    .queryTransformers(...)
    .documentRetriever(projectDocumentRetriever)
    .documentPostProcessors(projectRerankPostProcessor)
    .queryAugmenter(citationQueryAugmenter)
    .build();
```

组件职责：

#### `ProjectDocumentRetriever`

- 实现 Spring AI `DocumentRetriever`。
- 从 `Query.text()` 读取 transformer 后的检索 query。
- 从 `Query.context()` 读取服务端 `RetrievalExecutionContext`。
- 将本次实际使用的 transformer 后 query 写入 request-scoped
  `RetrievalTraceCollector`；不能只依赖 `DocumentPostProcessor` 收到的 `Query.text()`。
  Spring AI 1.1.4 的 `RetrievalAugmentationAdvisor` 在 post-processing 阶段传入的是
  `originalQuery`，而不是 `transformedQuery`。
- 调用扩展后的 `HybridRetrieverService.searchInScope(...)`，显式传入 effective
  `RetrievalOptions`。当前 service 的部分路径仍读取全局 `rag.retrieval.min-score`；
  实施时必须让 vector channel 使用 effective `minScore`，而 PostgreSQL FTS `@@`
  布尔命中不受 vector threshold 再过滤。
- 把 `RetrievalResult` 映射为 Spring AI `Document`。
- 所有来源、score、chunk 和 provenance 放入 metadata。
- 不解析 HTTP 请求，不读取 ThreadLocal API Key，不自行扩权。

#### `ProjectRerankPostProcessor`

- 实现 `DocumentPostProcessor`。
- `useRerank=false` 时原样返回。
- `useRerank=true` 时通过共享 mapper 调用 `ReRankingService`，rerank query 必须从
  request-scoped `RetrievalTraceCollector` 读取由 `ProjectDocumentRetriever` 登记的
  effective retrieval query，不能使用 Spring AI 传入的 `originalQuery.text()`。
- 不丢失 Spring AI Document metadata。
- 记录 rerank step metrics。

#### `CitationQueryAugmenter`

- 实现 `QueryAugmenter`。
- 使用 `[S1]`、`[S2]` 等稳定引用标签格式化 context。
- 空结果时生成明确的“当前授权范围内未检索到材料”提示。
- 保留原始用户 query 供最终回答；转换后的 query 只用于 retrieval/rerank。
- 来源提取依赖 `RetrievalAugmentationAdvisor` 已写入标准
  `RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT` 的最终 documents；augmenter 本身
  不重复维护另一份来源 context。

#### `RetrievalDocumentMapper`

- 唯一维护 `RetrievalResult <-> Document <-> ChatSource` 映射。
- 禁止各 Advisor、Tool、Service 各自复制 metadata key。

### 6.4 Agentic retrieval

新增只读工具：

```java
@Tool(
    name = "searchKnowledge",
    description = "Search the authorized knowledge base for evidence relevant to the query."
)
KnowledgeToolResult searchKnowledge(
    @ToolParam(description = "Concise standalone search query") String query,
    @ToolParam(required = false, description = "Requested result count") Integer maxResults,
    ToolContext toolContext
)
```

关键约束：

- Tool schema 只向模型暴露 `query` 和可选 `maxResults`。
- schema characterization test 必须确认 `ToolContext` 不出现在 schema，且
  `maxResults` 不在 required 列表；Spring AI 1.1.4 的 `@ToolParam` 默认
  `required=true`，不能只因 Java 类型是 `Integer` 就假设可选。
- `ToolContext` 由 Spring AI 自动注入，不进入模型参数 schema。
- `ToolContext` 包含不可变 `AuthorizedRetrievalContext`：
  - 已授权 `RetrievalScope`
  - server/domain/request 合成后的 retrieval options 与 hard caps
  - trace/session/request ID
  - request-scoped `RetrievalTraceCollector`
  - tool budget
- 模型不能提交 `collectionIds`、`collectionKeys`、`documentIds`、`documentType` 或
  API Key。
- effective max results 为 `min(modelRequested, authorizedCap)`。
- tool result 返回短、结构化 JSON：
  `query`、`resultCount`、`sources[{citationId,title,snippet,...}]`。
- 大 payload、完整 JSONB 和超长 chunk 不直接塞回模型；按字符/token 上限截断。
- Tool 是只读的；本轮不注册创建、修改、删除文档工具。

Tool 注册使用 Spring AI `.tools(knowledgeSearchTool)` 或对应 `ToolCallback`，工具循环使用
`ToolCallAdvisor`。不实现新的 while-loop。

为补足 Spring AI `ToolCallAdvisor` 默认无显式轮数上限的问题，增加薄适配：

- `BudgetedToolCallAdvisor` 继承 `ToolCallAdvisor`；
- 在 `doInitializeLoop*` 读取 request-scoped budget/trace；
- 在 `doAfterCall` / `doAfterStream` 检查模型响应中的 tool-call 数，在交给
  `ToolCallingManager` 执行前拒绝会超过 round/call hard limit 的一批调用；
- 同一 hook 从 `AssistantMessage.ToolCall` 读取 Spring AI 提供的
  `id/name/arguments`，用 Jackson 解析并裁剪可展示 query，登记 start time 后发送
  `tool_start`；
- 在 `doGetNextInstructionsForToolCall*` 接收已完成的 `ToolExecutionResult`，从最后
  一个 `ToolResponseMessage` 按 tool-call ID 关联结果，记录耗时并发送
  `tool_result`；tool callback 本身不假设能拿到当前 tool-call ID；
- tool 自身负责单次结果数和输出大小 hard cap；
- 使用 `streamToolCallResponses(false)`，不把模型原始 tool-call delta 当普通 content
  暴露给当前 SSE 客户端，只发送本项目定义的结构化 tool lifecycle event；
- 不复制 Spring AI 的 tool detection、execution、conversation history 或递归逻辑。

默认预算：

```text
maxToolRounds = 3
maxRetrievalCalls = 3
maxResultsPerCall = 10
maxUniqueSources = 20
maxToolResultCharacters = 24000
totalTimeout = 60s
```

重复 query 规则：

- 规范化只做 trim、Unicode normalization 和连续空白折叠；
- 同一执行中完全相同的 normalized query 第二次调用直接返回已有结果摘要；
- 不做中英文同义判断或自然语言 Pattern；
- 相似但不同 query 允许执行。

### 6.5 ACL 不可扩权

授权顺序固定：

```text
HTTP request
  -> authenticated RagApiKey
  -> CollectionRetrievalScopeResolver
  -> immutable RetrievalScope
  -> ChatCommand
  -> Query.context / ToolContext
  -> HybridRetrieverService SQL predicates
```

安全不变量：

1. LLM 输出永远不能改变 `RetrievalScope`。
2. Tool callback 不重新从模型参数解析 scope。
3. `CALLER_VISIBLE`、`ANY_COLLECTION`、`SELECTED_COLLECTIONS` 语义与 Search 完全相同。
4. restricted key 的 allow-list 仍由 server 取交集。
5. selected 空集合、未知/未授权 key 和 `matchNone` 均 fail closed。
6. 工具日志只记录 scope 摘要，不记录 raw API key。
7. 并发/异步流使用 request-scoped context object，不用 ThreadLocal 传授权。
8. history、export、clear 和 server memory 都使用
   `ChatPrincipal + public sessionId`，不能只按客户端 sessionId 访问。

必须有集成测试证明：即使模型工具参数伪造 `collectionIds` 字段，也不会进入方法参数或
SQL 范围；database key A 也不能读取、续写、导出或删除 key B 的同名 session。

### 6.6 模型 capability

能力是**模型实例级声明**，不是 provider/API 协议级推断。配置模型扩展
`models.json` / `MultiModelProperties.ModelItem`：

```json
{
  "id": "example-model",
  "type": "chat",
  "capabilities": {
    "streaming": true,
    "toolCalling": true
  }
}
```

Java 配置模型使用一个值对象，不让 nullable 语义散落到路由代码：

```java
public record ModelCapabilities(Boolean streaming, Boolean toolCalling) {
    public boolean supportsStreaming() {
        return streaming == null || streaming;
    }

    public boolean supportsToolCalling() {
        return Boolean.TRUE.equals(toolCalling);
    }
}
```

兼容默认值固定为：

- `streaming` 未配置时为 `true`。现有模型都已暴露 streaming API，升级不能因新增字段而
  把它们静默排除；operator 可显式写 `false` 禁止流式路由。
- `toolCalling` 未配置时为 `false`。工具支持需要 operator 明确确认，不能因某模型使用
  OpenAI/Anthropic 适配器就假定其真实端点、模型版本和网关都支持 tools。

该字段必须完整贯穿以下链路，不能只改 record：

1. `MultiModelProperties.ModelItem` 增加 `ModelCapabilities capabilities`。
2. `MultiModelConfigLoader.ModelsJsonRoot.ModelJson` 增加嵌套
   `CapabilitiesJson`；`toModelItem`、`equals/hashCode/toString` 同步。
3. `ConfiguredChatModelFactory.ModelDescriptor` 输出规范化后的
   `capabilities: {streaming: boolean, toolCalling: boolean}`。
4. `ChatModelRouter` 不再只在候选阶段传裸 `ChatModel`；内部使用包含 canonical ref、
   model 和 normalized capabilities 的 `ChatModelCandidate`，从而让 call/stream/mode
   过滤与最终 `resolvedModel` 使用同一真相源。
5. `GET /api/v1/rag/models` 保持现有顶层 DTO 兼容，在每个 `models[]` item 中新增
   `capabilities`；`ModelDetailResponse` 的 models 明细使用同一 descriptor。
6. `spring-ai-rag-webui/src/api/models.ts` 与相关 mock/fixture 同步类型，WebUI 只根据
   服务端返回的规范化布尔值控制模式和模型选项。

旧 Spring Boot 自动配置产生的 `ChatModel` Bean 不在 `ModelItem` 中，因此增加
`app.models.legacy-capabilities` 显式映射，key 使用 Router 已支持的 legacy provider
alias（当前即其 canonical ref）：

```yaml
app:
  models:
    legacy-capabilities:
      openai:
        streaming: true
        toolCalling: false
      anthropic:
        streaming: true
        toolCalling: false
```

- `MultiModelProperties` 增加 `Map<String, ModelCapabilities> legacyCapabilities`，默认
  空 map；查找 key 大小写不敏感。
- 外部 `models.json` 的 `models.legacyCapabilities` 使用相同结构。由于外部文件当前
  是完整覆盖而非 merge，加载时也必须覆盖/清空该 map，防止 YAML 声明残留。
- 未命中 legacy 映射时同样使用 `streaming=true/toolCalling=false`。
- 不按 `OpenAiChatModel`、`AnthropicChatModel`、`MiniMaxChatModel` class 或
  `apiType` 自动打开 tools；class 仍只用于当前 legacy provider alias 识别。
- 若同一 legacy provider 出现多个 Bean，当前 Router 的覆盖行为必须在 Phase 0
  characterization 后改为启动失败或要求显式唯一 alias，不能让 capability 配到一个
  Bean、实际却路由到另一个。

路由规则：

- `PLAIN` / `KNOWLEDGE` 非流式候选只要求 available。
- streaming endpoint 在候选构建阶段排除 `streaming=false`；显式选择该模型时返回
  `400 MODEL_STREAMING_UNSUPPORTED`，默认/fallback 路由则跳过并继续下一候选。
- `AGENT` 的 call 和 stream 都只考虑 `toolCalling=true`；stream 还同时要求
  `streaming=true`。
- `toolCalling=true` 仍不是充分条件。`ToolCallAdvisor` 要求本次 Prompt options 实现
  Spring AI `ToolCallingChatOptions`；candidate 初始化必须从该 model 的 default options
  复制出 request-local options，并验证其类型。声明支持 tools 但 adapter/default options
  不满足该接口时，该模型标记为配置错误/不可用于 AGENT；显式请求返回
  `MODEL_CAPABILITY_UNSUPPORTED`，默认链跳过。禁止到 tool loop 内才暴露
  `IllegalArgumentException`。
- request-local options copy 必须保留实际 model ID、temperature/token 等既有设置，再由
  Spring AI `.tools(...)` / `.toolContext(...)` 注入 tools；不能为了得到
  `ToolCallingChatOptions` 创建一个丢失 model 路由信息的空 options。
- 显式选择不支持当前 mode/transport 的模型返回清晰 400，不能悄悄换模型；未显式选模
  时才允许按过滤后的默认 fallback chain 继续。
- `AGENT` fallback 不能退化到非 tool model，也不能回落到 KNOWLEDGE/PLAIN。
- capability 是 operator 声明，不等于运行健康；真实 LLM smoke 至少验证一个
  `toolCalling=true` 的模型确实完成工具调用。

测试与文档同步范围：

- `MultiModelProperties` YAML binding、`MultiModelConfigLoader` JSON override/default、
  equality 和 external-file integration tests；
- `ConfiguredChatModelFactoryTest`、`ChatModelRouterTest`、`ModelController` 集成测试；
- `application.yml` 示例为现有 chat models 显式补充 capabilities；不确定工具支持的
  模型保持 `toolCalling:false`，由真实 smoke 验证后再开启；
- `docs/configuration*`、`docs/multi-model-external-config*` 说明两个默认值、完整覆盖
  语义、legacy 映射和“声明不等于健康”；
- WebUI model fixture 必须同时含支持/不支持工具和支持/不支持 streaming 的模型。

### 6.7 Advisor 与工具顺序

按模式构建独立 ChatClient，不在单个大链中通过大量 skip flag 切换：

```text
KNOWLEDGE:
  attempt-scoped custom advisors
  -> MessageChatMemoryAdvisor (SERVER only)
  -> RetrievalAugmentationAdvisor
  -> explicitly opted-in per-model-call advisors
  -> model

AGENT:
  attempt-scoped custom advisors
  -> MessageChatMemoryAdvisor (SERVER only)
  -> BudgetedToolCallAdvisor
  -> explicitly opted-in per-model-call advisors
  -> model

PLAIN:
  attempt-scoped custom advisors compatible with PLAIN
  -> MessageChatMemoryAdvisor (SERVER only)
  -> explicitly opted-in per-model-call advisors
  -> model
```

顺序理由：

- Spring AI 按 `getOrder()` 从小到大进入 request chain，response 按嵌套顺序反向返回；
- `KNOWLEDGE` 中 Memory 必须先注入历史，再由 RAG 创建 `Query.history()`；Memory 的
  `before` 仍保存原始 user message，不能保存已注入检索 context 的增强 user message；
- `AGENT` 中 Memory 只包围整次用户 turn，`ToolCallAdvisor` 使用自己的
  `conversationHistoryEnabled=true` 维护该 turn 内的 assistant tool call、
  `ToolResponseMessage` 和递归调用；Memory 不应在每个 tool round 重复运行；
- `STATELESS` 不安装 Memory Advisor，ToolCallAdvisor 仍保留内部单-turn history。
- `AGENT` 中位于 `BudgetedToolCallAdvisor` 内层的 Advisor 会被
  `callAdvisorChain.copy(this)` 带入每个 tool round；因此只有明确声明可重复、幂等且
  不写 turn 级状态的 provider 才能进入 per-model-call 作用域。
- Advisor 仍属于一次具体 ChatClient invocation。candidate fallback 或 retry 会创建新
  invocation，因此外层 provider 只能承诺“每个 model attempt 一次”，不能承诺整个逻辑
  user turn 只执行一次。真正的 turn 级鉴权、lease、审计 begin/end 和 persistence 由
  `ChatExecutionService` 的 core 内部 coordinator 在候选循环外执行。

实施时必须显式设置不重叠的 order band，不能依赖默认值或继续把公开 provider 的任意
整数直接混入内置链：

```text
HIGHEST_PRECEDENCE + 100..199  attempt-scoped custom advisors
HIGHEST_PRECEDENCE + 200       MessageChatMemoryAdvisor
HIGHEST_PRECEDENCE + 300       RetrievalAugmentationAdvisor / BudgetedToolCallAdvisor
HIGHEST_PRECEDENCE + 400..499  explicitly opted-in per-model-call advisors
```

同一 band 内先按 `RagAdvisorProvider#getOrder()` 排序，再由 factory 包装为该 band
中的稳定 order；超过 band 容量时启动失败，不能产生相同 order 的不确定链。Spring AI
1.1.4 的
`MessageChatMemoryAdvisor` 默认 order 为 `HIGHEST_PRECEDENCE + 1000`，
`ToolCallAdvisor` 默认 order 为 `HIGHEST_PRECEDENCE + 300`，默认组合不满足上述
AGENT 外层 Memory 语义。通过 call/stream characterization tests 固定实际 request、
response 和递归顺序。

`RagAdvisorProvider` 增加两个有 default 的兼容方法：

```java
default Set<ChatMode> supportedModes() {
    return Set.of(ChatMode.KNOWLEDGE);
}

default AdvisorScope advisorScope() {
    return AdvisorScope.ATTEMPT;
}
```

- 旧 provider 无需重新编译即可保持 `KNOWLEDGE + ATTEMPT`，避免在 `PLAIN` 中意外触发
  检索，也避免在 AGENT 每个 tool round 重复执行。
- 通用 safety/telemetry advisor 可显式 opt in 其他模式。
- `MODEL_CALL` 是高风险显式 opt in；其 call/stream 行为必须支持一次 turn 内执行多次。
- 本轮不新增公共逻辑请求拦截器 SPI。它若直接暴露 core 的 `ChatCommand` /
  `ChatExecutionResult` 会造成 API -> core 循环依赖；若未来确需第三方 turn hook，应
  另行设计完全位于 API 模块的安全 observation DTO，而不是泄露授权执行对象。
- 旧 Javadoc 推荐的 `+15/+25/+35` 曾分别表示“改写后/检索后/rerank 后”，但
  `RetrievalAugmentationAdvisor` 把这些步骤封装在一个 Advisor 内，不能再承诺仅靠
  `BaseAdvisor#getOrder()` 插入其内部阶段。Phase 0 必须清点项目和 demos 中的现有
  provider：每 attempt before/after 的迁移到 `ATTEMPT`；真正的逻辑 turn 行为放在
  core coordinator；依赖阶段中间结果的迁移到 Spring AI
  `QueryTransformer`、`DocumentPostProcessor`、`QueryAugmenter`，或项目的共享
  retrieval mapper/trace 扩展点。禁止用多个平行自定义 RAG Advisor 重建旧流水线。

### 6.8 Domain extension

激活当前已有但未使用的 `getRetrievalConfig()`：

```text
global hard caps
  > request explicit values（不得超过 cap）
  > explicitly selected domain defaults
  > Chat endpoint compatibility defaults
  > global retrieval defaults for fields not owned by Chat compatibility
```

这里的“request explicit values”依赖第 6.2 节的 presence tracking，不能把 primitive
getter 当前返回的默认值一律视为显式 override。

Domain 解析规则同时收口：

- `domainId` 省略时不使用 `DomainExtensionRegistry` 当前“第一个 bean 是默认值”的隐式
  规则；Chat 使用通用 system instruction 和 Chat endpoint compatibility defaults。
- 显式 `domainId` 必须命中已注册 extension；未知值返回 `400 UNKNOWN_DOMAIN`，不能静默
  回退为无 domain。
- 若产品需要默认 domain，未来增加显式配置 `rag.chat.default-domain-id` 并在启动时验证；
  不依赖 Spring bean 注入顺序。
- `DefaultDomainRagExtension` 可作为显式 `domainId=default` 的 extension 保留，但不能
  因为它恰好先注册就自动改变所有 Chat 请求。

`getSystemPromptTemplate()` 继续提供领域 instruction，但 context 注入归
`CitationQueryAugmenter` / `KnowledgeSearchTool`：

- 新版 domain template 不应包含 `{context}`，并按 mode 组合：
  - `KNOWLEDGE`：领域 instruction + `CitationQueryAugmenter` 的 context；
  - `AGENT`：领域 instruction + “仅通过 `searchKnowledge` 获取知识库证据”的 agent
    instruction，不能在 system prompt 中伪造空的“参考资料”段；
  - `PLAIN`：只保留领域 safety/style instruction，不加入 grounding/引用规则，也不注册
    retrieval tool。
- 为现有扩展兼容：发现 `{context}` 时以空串渲染并记录一次迁移 warning；migration
  adapter 同时移除仅用于占位的“References/参考资料”尾段。若无法可靠识别自定义模板，
  显式 domain 在 `AGENT/PLAIN` 返回配置错误并要求迁移，不能把误导性 prompt 静默上线。
- 默认 extension 更新为纯领域/grounding instruction。

`postProcessAnswer()` 暂不激活。它无法在不缓存全部 token 的情况下与真流式保持对等。
实施时标记为 deprecated，并在后续设计 stream-aware response customizer。

`isApplicable()` 也不用于自动路由；当前 `domainId` 是调用者显式选择。隐式领域分类不在
本轮范围。

## 7. 统一执行、fallback 与错误

### 7.1 ChatClient factory

新增 `ModeAwareChatClientFactory`。它可以按以下 key 缓存**不可变的无状态描述符**、
resolved model、query transformer 和共享只读组件：

```text
resolved model identity
chat mode
query transformer policy
domain policy identity/version
```

但不能缓存已经绑定某次 request-local `MessageWindowChatMemory`、
`MessageChatMemoryAdvisor`、`RetrievalTraceCollector`、ToolContext 或 event sink 的
`ChatClient`。每个 candidate/retry attempt 都必须：

1. 从 committed history baseline 创建新的 request-local Memory（`STATELESS` 则省略）；
2. 创建绑定该 Memory 的新 `MessageChatMemoryAdvisor`；
3. 将本 attempt 的 trace/tool/event context 作为 request-scoped context 注入；
4. 用缓存的无状态模板和当前 Advisor 列表构建 attempt-local `ChatClient`。

factory cache value 不得是可变 `ChatClient.Builder`，也不得引用 `ChatCommand`、
principal、session、scope、metadata 或任何可变请求状态；实现可缓存 immutable record
和纯 factory function，但每次都从 `ChatClient.builder(resolvedModel)` 开始装配。增加并发
测试证明两个 session/attempt 不共享 options、Memory 或 collector。

不能继续把 `RerankAdvisor` 的 API compatibility adapter 固定在应用启动时的单一
`spring.ai.openai.base-url`。重构后 context 统一注入 user prompt，避免动态模型使用
错误 adapter。

### 7.2 非流式与流式共享候选链

两条路径都调用同一候选解析：

```text
explicit requested model（若有）
  -> configured primary
  -> configured fallbacks
  -> legacy active bean（兼容）
```

附加过滤：

- `AGENT` 只保留 Tool Calling candidates；
- 显式 model 不可用或 capability 不满足时立即失败；
- fallback 成功后在结果中记录 resolved model。

流式 fallback 边界：

- 第一个 client-visible event 发送前失败，可以切到下一候选；
- 每个 candidate/retry 使用从同一 committed history baseline 创建的独立
  request-local ChatMemory；切换时直接丢弃失败 attempt；
- 发送任何 content/tool/source event 后，不得切换模型拼接响应；
- 已开始后失败，发送 `event:error` 并终止。

### 7.3 Resilience

- Circuit breaker、retry 和 metrics 统一包围每个 candidate attempt；breaker key 使用
  canonical model ref（至少 provider/modelId），不能让 primary 的故障打开一个全局
  breaker 后直接阻断健康 fallback。
- retry 只能在该 attempt 尚未向客户端发送事件时进行。
- `SERVER` 模式下每次失败 attempt/retry 都丢弃其 request-local ChatMemory；下一个
  attempt 从本逻辑 turn 开始时读取的 committed history baseline 新建窗口，不能复用未知
  attempt state。
- Tool callback 的 retrieval 超时独立于 LLM timeout。
- 用户取消不是模型失败，不计入 circuit breaker failure。
- Query Transformer 失败回退原 query，不使整个 `KNOWLEDGE` 请求失败。
- Retrieval 全通道失败与“合法零结果”必须使用不同 status/metric。

## 8. SSE 与前端契约

### 8.1 事件协议

为保持现有 WebUI 兼容，content chunk 暂时保留当前 OpenAI-like data 结构：

```text
data:{"choices":[{"delta":{"content":"..."}}]}
```

新增：

```text
event:tool_start
data:{"toolCallId":"...","tool":"searchKnowledge","query":"..."}

event:tool_result
data:{"toolCallId":"...","tool":"searchKnowledge","resultCount":5,"elapsedMs":23}

event:sources
data:{"sessionId":"...","sources":[...]}

event:done
data:{"traceId":"...","sessionId":"...","status":"complete",
      "mode":"AGENT","requestedModel":"...","resolvedModel":"...",
      "usage":{...},"stepMetrics":[...]}

event:error
data:{"traceId":"...","sessionId":"...","error":{"code":"...","message":"..."}}
```

顺序不变量：

1. `tool_start` 在实际 retrieval 前。
2. 对应 `tool_result` 在 retrieval 完成后。
3. 最终 `sources` 在最后一个 content 后、`done` 前发送。
4. 正常流只发送一次 `done`。
5. 错误流只发送一次 `error`，不再发送 `done`。
6. heartbeat comment 不进入业务事件解析。

### 8.2 服务端流实现

- 使用 `.stream().chatClientResponse()`，不再只取 `.content()`。
- 在统一 coordinator 中提取 delta、最终 response context、usage 和 finish reason。
- `RetrievalTraceCollector` 使用线程安全集合和 request-scoped event sink。
- request trace 下为每个 candidate/retry 创建独立 attempt collector。KNOWLEDGE 在首个
  client-visible event 前失败时丢弃该 attempt 的 sources、usage、step metrics 和
  transformed query；只有成功 attempt 原子 commit 到最终 result。AGENT 一旦发出
  `tool_start` 就已越过 fallback 边界，不再切换候选。
- Controller 保存 Reactor `Disposable`。
- `SseEmitter#onCompletion`、`onTimeout`、`onError` 都 dispose subscription。
- completion 后执行 token-fenced history + JDBC Memory 短事务；error/cancel 默认直接
  丢弃 request-local Memory，再按配置决定是否把 cancelled partial 构造成一个新的
  committed window 并原子提交。
- JSON 使用 Jackson DTO 序列化，不手工拼字符串。

### 8.3 取消与重连

- WebUI 使用 `AbortController` + reader cancel。
- 增加“停止生成”命令；取消后保留已显示文本，但标记为 stopped。
- 后端取消后停止后续 LLM/tool 工作。
- fetch abort 与网络断开都必须传播到 Reactor subscription；前端对主动 abort 不显示
  error toast，网络错误仍进入可重试 error 状态。
- POST SSE 不支持断点 replay；浏览器不自动重连。
- 用户主动“重试”创建新的 request attempt，复用 session 但不复用 tool budget。

### 8.4 WebUI

主要改动：

- `ChatModeSelector`：知识库、智能探索、普通对话三段选择。
- 选择不支持 tools 的 model 时禁用智能探索，并给出 model capability 原因。
- agent tool 状态显示可理解文本，例如“正在检索知识库”“已找到 5 条来源”，不显示
  框架名或内部 class。
- sources 显示引用编号和标题，不显示伪百分比。
- sources 复用文档/PDF 打开能力，但动作请求必须基于 document ID。
- 不直接把文档管理页的 `DocumentActionsMenu` 塞进 Chat；抽取共享的只读
  `SourceActionsMenu` / provenance action helper。Chat 来源只能预览/打开来源，不能
  暴露版本、重嵌入或删除命令。
- 新增 document-bound source contract，例如
  `GET /documents/{id}/source/preview`、`GET /documents/{id}/source/original`；每次调用
  都先 `requireDocumentAccess`，再安全映射到 PDF 虚拟文件。history 中即使保留旧来源，
  当前 key 已失去文档权限或文档已删除时也返回统一 not-found。
- 不从 Chat 直接调用 `/files/raw?path=...` 或客户端拼接 `/files/raw/{uuid}/{file}`。
- error 使用独立状态和 retry command，不把 `Error: ...` 拼进 assistant 正文。
- streaming 时显示停止按钮。
- route session、server-generated session ID 和 sidebar 保持一致。
- 历史加载恢复 sources、mode、model 和完成状态。
- 合并重复的 Chat TS types，以后端契约为单一前端类型源。
- `useChatSSE` 改为持有 `AbortController`，保留跨 chunk 的完整 SSE parser 状态，支持
  CRLF、多个 `data:` 行、comment/heartbeat 和 EOF 尾块；`done`/`error` 后立即停止
  读取，且每个 request 只触发一次终态 callback。

## 9. 历史持久化与数据库

为恢复引用并修复 session 归属，实施时添加下一个可用 Flyway migration；当前基线是
V31，但禁止预先占用固定编号。推荐：

```sql
ALTER TABLE rag_chat_history
    ADD COLUMN owner_principal_id VARCHAR(128),
    ADD COLUMN sources JSONB,
    ADD COLUMN turn_status VARCHAR(20) NOT NULL DEFAULT 'COMPLETE';

ALTER TABLE rag_chat_history
    ADD CONSTRAINT ck_rag_chat_history_session_id
        CHECK (session_id ~ '^[A-Za-z0-9._~-]{1,36}$') NOT VALID,
    ADD CONSTRAINT ck_rag_chat_history_owner_principal_id
        CHECK (
            owner_principal_id IS NULL
            OR char_length(owner_principal_id) BETWEEN 1 AND 128
        ),
    ADD CONSTRAINT ck_rag_chat_history_turn_status
        CHECK (turn_status IN ('COMPLETE', 'CANCELLED'));

CREATE INDEX ... ON rag_chat_history
    (owner_principal_id, session_id, created_at DESC, id DESC);
CREATE INDEX ... ON rag_chat_history (created_at, owner_principal_id, session_id);

CREATE TABLE rag_chat_session_lease (
    owner_principal_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    owner_token VARCHAR(36) NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (owner_principal_id, session_id),
    CONSTRAINT ck_rag_chat_session_lease_owner
        CHECK (char_length(owner_principal_id) BETWEEN 1 AND 128),
    CONSTRAINT ck_rag_chat_session_lease_session
        CHECK (session_id ~ '^[A-Za-z0-9._~-]{1,36}$'),
    CONSTRAINT ck_rag_chat_session_lease_token
        CHECK (owner_token ~ '^[A-Za-z0-9._~-]{1,36}$'),
    CONSTRAINT ck_rag_chat_session_lease_expiry
        CHECK (expires_at > acquired_at)
);

CREATE INDEX ... ON rag_chat_session_lease (expires_at);
```

V1 允许 `session_id VARCHAR(255)` 且没有字符约束，历史数据库可能存在不符合新公开合同的
值。因此迁移先以 `NOT VALID` 增加 history session check：新写入立即受约束，旧行不会
阻断升级；不得在本轮静默改写、截断或删除旧 session ID。待运维导出/清理遗留值后，可在
后续 migration 执行 `VALIDATE CONSTRAINT`。lease 表只接收新请求，直接使用严格约束。

保存规则：

- 所有新记录写 `owner_principal_id`；database key 使用 stable `keyId` 的 namespaced
  形式（推荐 `db:<keyId>`、`root:environment-root`、`legacy:static`、
  `local:auth-disabled`），不保存 raw credential；构造后必须满足 1-128 字符。
- repository 的 find/delete/export 均要求 principal，不再暴露只收 sessionId 的生产
  API；root 管理路径可显式跨 principal，不能靠传 `null` 绕过。
- lease acquire 使用单条 PostgreSQL `INSERT ... ON CONFLICT ... DO UPDATE ... WHERE
  expires_at < clock_timestamp()` 并检查 affected row；renew 必须同时匹配
  `owner_token` 且要求当前 `expires_at > clock_timestamp()`，过期 lease 不允许被迟到
  renew 复活。release 同时匹配 `owner_token`。不持有覆盖整个 LLM/SSE 生命周期的数据库
  transaction 或专用连接。
- 每次 lease acquire 成功后按 `created_at,id` 正序读取受控窗口内的
  COMPLETE/CANCELLED history，创建只属于本次 attempt 的
  `MessageWindowChatMemory` + `InMemoryChatMemoryRepository` baseline；读取/映射失败返回
  503 并保留 lease 到短 TTL 后重试，不能继续生成。acquire 时不先改写共享 JDBC memory。
- 旧记录保持 `owner_principal_id IS NULL`，永不自动 claim/backfill。environment root、
  legacy static 和 auth-disabled local 可从兼容/运维读取与导出路径访问这些旧记录；
  普通 database key 不得读取。任何 principal 都不得把无 owner turn 放入
  principal-scoped ChatMemory rebuild，否则会把旧共享上下文混入新安全 namespace。
- 只有 session ID 本身符合新 public 格式的 null-owner 记录可由普通兼容 read/export
  endpoint 访问。不符合 `[A-Za-z0-9._~-]{1,36}` 的旧 session 无法安全放入 path/body
  contract，只允许显式管理员迁移工具或数据库运维导出/删除；普通 validator 不为旧数据
  放宽，也不提供编码绕过。
- 普通 history clear/delete 只删除当前 principal 明确拥有的行；即使调用者是
  environment root、legacy static 或 auth-disabled local，也不得顺带删除
  `owner_principal_id IS NULL` 的旧共享记录。删除无 owner 记录只能走显式、仅管理员可用
  的 legacy cleanup contract，并记录审计；本轮若不新增该管理端点，则只支持数据库运维
  清理，不伪装成普通会话 clear。
- `related_document_ids` 暂时保留兼容，并由 sources 派生。
- `sources` 保存当时的 source snapshot，不在历史读取时重新检索。
- snapshot 只保存 `ChatSource` allow-list 字段和裁剪 snippet，不保存任意 metadata、
  raw JSONB/XML payload 或内部文件系统敏感路径。
- metadata 保存：
  `mode`、`memoryMode`、`requestedModel`、`resolvedModel`、`traceId`、
  `finishReason`、`usage`、`stepMetrics`。
- 正常完成保存完整 turn。
- 业务 history 现在同时承担下一 turn baseline，因此成功响应的 history 写入不再是可吞
  异常的 best-effort side effect。Repository 提供会抛错的 durable save；coordinator
  在短事务中锁住并校验未过期 lease token、写 history、通过 `ChatMemory.clear + add`
  替换共享 JDBC memory 投影，再释放 lease。只有整个事务提交成功才发送非流式成功响应
  或流式 `done`。
- 最终持久化事务任一步失败时整体回滚并发送结构化
  `CHAT_HISTORY_PERSIST_FAILED` / HTTP 503；已经发送的 stream content 保留为未提交
  partial，但不发送 `done`。该 code 表示 durable turn state（history + memory
  projection）提交失败；审计日志记录 traceId，不记录完整内容。
- 最终事务的 ownership check 使用
  `SELECT ... FOR UPDATE WHERE owner_principal_id=? AND session_id=? AND owner_token=?`
  并在锁内验证 `expires_at > clock_timestamp()`；未命中或已过期映射为
  `CHAT_SESSION_LEASE_LOST`，不是 persistence failure。事务超时使用逻辑 deadline 的剩余
  预算，deadline 已耗尽时不进入提交。
- `turn_status` 使用受控枚举值 `COMPLETE` / `CANCELLED`；普通历史默认返回两者，并由
  UI 明确标记 cancelled partial。
- 用户取消默认不保存 partial，直接丢弃 request-local Memory 并 token-fenced 释放
  lease；只有 `persist-cancelled-partial=true` 且已有非空 partial 时，才从 committed
  baseline 新建一个临时 `MessageWindowChatMemory`，依次追加原 user 和 partial
  assistant，然后在上述同一短事务中写业务 history、共享 JDBC memory 并标记
  `CANCELLED`。
- LLM 失败不写普通 history turn；通过结构化日志、metrics 和现有 audit 机制记录。
- TTL cleanup 不能只删除业务历史而保留不再访问的 JDBC memory。cleanup 先按 cutoff
  找出 `owner_principal_id IS NOT NULL` 的受影响 distinct
  `(ownerPrincipalId, sessionId)`，逐会话获取同一
  `rag_chat_session_lease`；成功后删除过期业务 history，并用剩余未过期的已提交 turn
  重建该会话 ChatMemory（无剩余则 clear），最后 token-fenced 释放。busy 会话跳过并在
  下轮重试，不能与活动生成并发清理。`owner_principal_id IS NULL` 没有唯一、安全的
  principal namespace，常规 TTL job 必须跳过，不能猜测 owner 或清理某个可能仍共享的
  legacy ChatMemory；它们只由上一条显式 legacy cleanup/数据库运维流程删除。cleanup
  必须分页/限批，避免一次 cron 长事务锁住全表。

数据库迁移不修改已执行的 V1-V31。迁移测试必须同时覆盖：

1. 空数据库从 V1-V{next} 全量启动；
2. 带合法旧 history、非法/超长 legacy session ID 和 null-owner history 的 V31
   fixture 原地升级；
3. 新写入非法 session/status/lease expiry 被约束拒绝；
4. owner/session 查询索引与 TTL/lease expiry 索引存在；
5. legacy 行不被改写、认领或删除，应用侧也不把它们重建进 principal ChatMemory。

## 10. API 兼容方案

### 10.1 `ChatRequest`

新增可选字段：

```json
{
  "mode": "KNOWLEDGE"
}
```

兼容：

- 省略 `mode` -> `KNOWLEDGE`。
- 现有 collection/model/session 字段不改名。
- `useHybridSearch`、`useRerank` 开始真实生效。
- 不新增 model 可控制的 scope 字段。

### 10.2 `ChatResponse`

推荐新增 first-class 字段：

```text
sessionId
mode
requestedModel
resolvedModel
usage
finishReason
```

同时保留一个版本周期：

- `metadata.sessionId`
- 旧 source 字段

`POST /chat` 与 `/chat/ask` 继续作为同义入口。

### 10.3 History

`ChatHistoryResponse` 增加：

```text
sources
status
mode
requestedModel
resolvedModel
```

旧字段保持。

History、export 和 clear endpoint 继续保留 URL，但都从 `HttpServletRequest` 解析
`ChatPrincipal` 并按 owner 查询。public `sessionId` 精确限制为
`[A-Za-z0-9._~-]{1,36}`；body/path/自动生成值执行同一个 validator。对“不存在”和
“属于其他 principal”返回相同的 not-found 语义，
避免 session 枚举。environment root 的跨 owner 管理能力如需开放，使用单独明确的
admin contract，不让普通 endpoint 隐式越权。旧 null-owner 记录只按第 9 节的只读
兼容规则暴露；普通 clear 不删除它们。

### 10.4 OpenAI compatibility 边界

本轮不新增 `/v1/chat/completions`。但 `ChatCommand` 支持 messages 列表和
`MemoryMode.STATELESS`，使后续兼容层只做协议 mapper，而不是再复制执行链。

现有自定义 Chat SSE 不能宣称完整 OpenAI Chat Completions streaming compatibility。

### 10.5 Typed errors

本轮新增的协议错误必须进入 API 模块的 `ErrorCode` 单一真相源，并由
`RagException`/专用子类映射；不能靠解析 `IllegalArgumentException` 文本：

```text
UNKNOWN_DOMAIN                         400
RETRIEVAL_OPTIONS_NOT_ALLOWED          400
MODEL_CAPABILITY_UNSUPPORTED           400
MODEL_STREAMING_UNSUPPORTED            400
CHAT_AGENT_DISABLED                    400
SESSION_NOT_FOUND                      404
SESSION_BUSY                           409
CHAT_SESSION_LEASE_LOST                409
CHAT_HISTORY_PERSIST_FAILED            503
CHAT_TIMEOUT                           504
```

- 对“不存在”和“属于其他 principal”的 history/export/clear 都返回同一个
  `SESSION_NOT_FOUND`，不暴露 owner。
- SSE 在 HTTP headers 已提交后使用同名 code 的结构化 `event:error`；首事件前失败可由
  Controller advice 返回普通 RFC 7807。
- `SESSION_BUSY` 与 lease lost 都是可重试冲突，但语义不同；客户端可保留已有 partial，
  不自动重放请求。
- `GlobalExceptionHandler`、OpenAPI 示例、WebUI error mapping 和 controller integration
  tests 必须同步这些 code/status。

## 11. 配置设计

建议新增：

```yaml
rag:
  chat:
    default-mode: KNOWLEDGE
    knowledge:
      query-transformer: none
      query-transform-timeout-seconds: 30
      query-expander-variants: 2
      query-expander-include-original: true
      allow-empty-context: false
    agent:
      enabled: true
      max-tool-rounds: 3
      max-retrieval-calls: 3
      max-results-per-call: 10
      max-unique-sources: 20
      max-tool-result-characters: 24000
      total-timeout: 60s
    history:
      persist-cancelled-partial: false
```

prod 覆盖：

```yaml
rag:
  chat:
    knowledge:
      query-transformer: spring-ai
```

配置规则：

- 所有数值做下限/上限校验。
- agent disabled 时 API 请求 `AGENT` 返回明确 400/feature disabled。
- request 只能收紧 server cap，不能扩大。
- `persist-cancelled-partial` 默认 false；开启时必须同时通过 memory/history 一致性和
  并发取消集成测试。
- 旧 `rag.query-rewrite.*` 在迁移期标记 deprecated；实现稳定后删除语言 rule 配置，
  可保留与 Spring AI transformer 对应的兼容映射一个版本。

## 12. 实施阶段

### Phase 0：Characterization 与 WIP 边界

1. 为当前 `/chat`、`/ask`、`/stream`、history、clear、Collection ACL、model routing
   增加 characterization tests。
2. 固定全文 `@@` 不受 vector min-score 过滤的正确行为。
3. 记录当前 dirty files，禁止批量回退。
4. 证明 `useHybridSearch` / `useRerank` 当前未生效，作为新测试的红线起点。
5. 清点所有 `RagAdvisorProvider` 实现，标记其真实依赖是 attempt before/after、增强后
   prompt，还是旧 query/retrieval/rerank 中间阶段；阶段级依赖必须列出目标标准扩展点。
6. 为 public session ID、null-owner history 读取/导出/clear/TTL 和 principal memory
   rebuild 增加安全 characterization/目标 contract tests，先固定不得 claim 或跨 owner
   混入 Memory 的边界。

完成门槛：不改变生产行为，测试能描述现状和目标差距。

### Phase 1：内部契约与共享检索适配

1. 新增 API `ChatMode`、`AdvisorScope`，以及 core 内部 `MemoryMode`、
   `ChatPrincipal`、`ChatCommand`、`RetrievalOptions`、`ChatExecutionResult`、
   `ChatEvent`。
2. 新增 `AuthorizedKnowledgeRetriever`、`RetrievalDocumentMapper`、
   `RetrievalTraceCollector`。
3. 让 request/domain/global retrieval options 真正合成。
4. 增加 `spring-ai-rag` core dependency。

完成门槛：不接 UI，内部 mapper 和 ACL tests 通过。

### Phase 2：`KNOWLEDGE` 标准 RAG

1. 实现 `ProjectDocumentRetriever`。
2. 实现 `ProjectRerankPostProcessor`。
3. 实现 `CitationQueryAugmenter`。
4. 实现 `HistoryAwareQueryTransformer`，确保每轮只执行 rewrite/compression 之一。
5. 组装 `RetrievalAugmentationAdvisor`。
6. 接入 Spring AI query transformers 和 resilient fallback。
7. 从标准 `DOCUMENT_CONTEXT` 提取 sources。
8. 让 hybrid/rerank/maxResults 生效。
9. 删除旧三 Advisor 的主生产注册；保留不含语言 Pattern 的短期 legacy retrieval
   adapter 和一次性迁移测试。
10. 删除 `QueryRewritingService` 的语言 Pattern 与专用 context key，不允许 legacy
   engine 继续依赖它们。

完成门槛：报告中的 `风格基调` 场景在真实 PostgreSQL fixture 中通过，且无语言 Pattern。

### Phase 3：`AGENT` Tool Calling

1. 实现 `KnowledgeSearchTool`。
2. 将授权 scope 放入 `ToolContext`。
3. 实现 budget、重复 query cache 和 result truncation。
4. 使用/扩展 `ToolCallAdvisor`，不手写循环。
5. 增加 model capability 配置、API 和 routing。
6. 接入 sources collector。

完成门槛：scripted ChatModel 实际完成
`tool call -> retrieval -> tool response -> final answer`，ACL 无法扩权。

### Phase 4：统一 call/stream、memory 与 history

1. 实现 `ChatExecutionService` 和 `ModeAwareChatClientFactory`。
2. 非流式与流式共享 model candidate、retry、fallback、prompt 和 persistence。
3. stream 改用 `ChatClientResponse`。
4. 使用 principal-namespaced memory ID 和 `ChatMemory.clear`。
5. 增加 history owner + sources migration 和 DTO。
6. 增加 PostgreSQL session lease、request-local Spring AI ChatMemory、token-fenced
   history/JDBC-memory 原子提交、结构化 SSE events、取消与 error。
7. 显式提供使用项目 `PlatformTransactionManager` 的
   `JdbcChatMemoryRepository` Bean，固定 history/Memory/lease 的共同事务边界。

完成门槛：同 fixture 下 call/stream answer、sources、mode、model 和 history 语义一致。

### Phase 5：WebUI

1. 模式选择与 capability gating。
2. tool lifecycle 展示。
3. sources 与文档/PDF 动作。
4. stop/retry/error。
5. 历史恢复 sources。
6. 合并 TS 契约。

完成门槛：Vitest、build、核心 Mock Playwright 通过，desktop/mobile 无重叠。

### Phase 6：清理、文档与一键验证

1. 删除语言 Pattern 和废弃 context key。
2. 删除未再使用的旧 Advisor wiring；若保留可回滚 adapter，它必须共享新 query
   transformer/retrieval options 且不含语言 Pattern。公开 SPI 按 deprecation policy
   处理。
3. 同步中英文正式文档。
4. 新增 `scripts/verify-chat-capability.sh`。
5. 扩展真实 LLM smoke。
6. 记录质量、延迟、tool-call 次数和 fallback 结果。

完成门槛：一键脚本、全量构建、文档门禁和可选真实 LLM 均有明确结果。

## 13. 预计文件改动

### API

```text
spring-ai-rag-api/.../dto/ChatRequest.java
spring-ai-rag-api/.../dto/ChatResponse.java
spring-ai-rag-api/.../dto/ChatHistoryResponse.java
spring-ai-rag-api/.../dto/ChatSource.java                 # new
spring-ai-rag-api/.../enums/ChatMode.java                 # new
spring-ai-rag-api/.../enums/ErrorCode.java
spring-ai-rag-api/.../service/AdvisorScope.java            # new
spring-ai-rag-api/.../service/RagAdvisorProvider.java
spring-ai-rag-api/.../service/DomainRagExtension.java
```

### Core

```text
spring-ai-rag-core/pom.xml
.../chat/ChatCommand.java                                 # new
.../chat/ChatPrincipal.java                               # new
.../chat/MemoryMode.java                                  # new/internal
.../chat/ChatExecutionResult.java                         # new
.../chat/ChatEvent.java                                   # new
.../chat/ChatExecutionService.java                        # new
.../chat/ModeAwareChatClientFactory.java                  # new
.../chat/ChatSessionLeaseCoordinator.java                 # new
.../chat/ChatSessionException.java                        # new/typed errors
.../chat/AuthorizedRetrievalContext.java                  # new
.../chat/RetrievalTraceCollector.java                     # new
.../chat/KnowledgeSearchTool.java                         # new
.../chat/BudgetedToolCallAdvisor.java                     # new
.../rag/ProjectDocumentRetriever.java                     # new
.../rag/ProjectRerankPostProcessor.java                   # new
.../rag/CitationQueryAugmenter.java                       # new
.../rag/RetrievalDocumentMapper.java                      # new
.../rag/HistoryAwareQueryTransformer.java                 # new
.../config/RagChatProperties.java                         # new
.../config/ChatMemoryRepositoryConfig.java                # new/shared transaction manager
.../config/MultiModelProperties.java
.../config/MultiModelConfigLoader.java
.../config/ConfiguredChatModelFactory.java
.../config/ChatModelRouter.java
.../config/RagChatService.java                            # shrink/delegate or replace
.../controller/RagChatController.java
.../controller/GlobalExceptionHandler.java
.../service/ChatHistoryCleanupService.java
.../service/ChatExportService.java
.../repository/RagChatHistoryRepository.java
.../repository/RagChatHistoryJpaRepository.java
.../entity/RagChatHistory.java
.../db/migration/V{next}__add_chat_history_owner_sources_lease.sql
```

旧实现候选清理：

```text
.../advisor/QueryRewriteAdvisor.java
.../advisor/HybridSearchAdvisor.java
.../advisor/RerankAdvisor.java
.../retrieval/QueryRewritingService.java
```

删除前必须确认外部 `RagAdvisorProvider` 或 starter contract 没有依赖具体 class；必要时先
deprecated 一个版本，而不是直接破坏公开 API。

### WebUI

```text
spring-ai-rag-webui/src/pages/Chat.tsx
spring-ai-rag-webui/src/api/chat.ts
spring-ai-rag-webui/src/hooks/useSSE.ts
spring-ai-rag-webui/src/types/api.ts
spring-ai-rag-webui/src/components/ChatModeSelector/       # new
spring-ai-rag-webui/src/components/ChatToolActivity/       # new
spring-ai-rag-webui/src/i18n/locales/{zh-CN,en}.json
相关 Vitest / Playwright
```

### 文档与脚本

```text
docs/architecture*.md
docs/configuration*.md
docs/rest-api*.md
docs/testing-guide*.md
docs/troubleshooting*.md
docs/project-context*.md
docs/SSE-PROTOCOL.md
spring-ai-rag-webui/README*.md
scripts/verify-chat-capability.sh                          # new
scripts/real-llm-e2e-smoke.sh
```

## 14. 测试策略

### 14.1 基本原则

- 先一次性设计覆盖矩阵，再实现；禁止“发现一个 bug 补一个孤立测试”的追赶模式。
- integration test 优先覆盖完整 Advisor/Tool/ACL/DB 链。
- review 不能替代运行验证。
- mock browser tests 不能替代真实 LLM smoke，但是真实 key 不作为默认 CI 前提。

### 14.2 后端单元测试

| 组件 | 必测 |
|---|---|
| `RetrievalDocumentMapper` | 字段完整、score 语义、metadata round trip |
| retrieval options merge | global/domain/request 优先级和 cap |
| Chat compatibility defaults | 省略字段无 domain 保持 5/true/true；显式 domain 才应用 domain defaults |
| client metadata isolation | 大小/深度限制、不可覆盖 Advisor/ToolContext 保留键、customizer 只读 |
| query transformer/expander | 首轮保留原始查询并扩展、follow-up compression、失败回退、结果合并去重 |
| effective retrieval query | transformer 后 query 同时用于 retrieve 与 rerank，最终回答仍保留原始 user query |
| advisor scope/order | ATTEMPT 每次 candidate/retry 执行；MODEL_CALL 在 AGENT 每轮执行；core coordinator 整回合一次；band 稳定 |
| ChatClient factory isolation | 只缓存无状态模板；每 attempt 新建 Memory Advisor/ChatClient；并发 session 不串状态 |
| model capability | streaming 缺省 true、tools 缺省 false；配置/legacy/JSON 三路径；声明与 `ToolCallingChatOptions` 双校验；call/stream/agent candidate 过滤；显式不支持报错 |
| tool budget | rounds、calls、重复 query、result/character cap |
| tool schema | ToolContext 不暴露，maxResults 明确 optional，重复工具名 fail fast |
| source collector | 多 call 去重、citation ID 稳定、并发安全 |
| attempt isolation | failed candidate 的 sources/usage/metrics/query 不进入 fallback 成功结果 |
| source projection | metadata allow-list、snippet cap、排除 raw JSONB/XML payload |
| principal namespace | 同 session 不同 principal 生成不同 memory ID，输出固定 36 字符 |
| session ID contract | body/path/自动生成统一 `[A-Za-z0-9._~-]{1,36}`；编码绕过和旧非法值兼容 |
| session lease | acquire/renew/release token fencing、busy、过期不可续租、expiry takeover、DB check/index、时钟来自 PostgreSQL |
| memory commit fencing | attempt 只写 request-local memory；成功时 lease 行锁 + token/expiry 校验；history/JDBC projection 同事务 |
| JDBC Memory transaction manager | 项目 Bean 显式注入共同 `PlatformTransactionManager`；两种写入顺序的故障注入都整体回滚 |
| renew/terminal race | COMMITTING 前停止并 join renewer；成功 release 后的 0-row renew 不得反向标记 lease lost |
| logical deadline | call/stream 共用绝对 deadline；nested retry/fallback/tool 不重置预算；renew 失败立即失去所有权 |
| history mapper | sources/status/model/mode round trip |
| TTL cleanup | owner 非空时分页按会话加 lease并重建/清空 memory；busy 下轮重试；null-owner 跳过 |
| typed errors | ErrorCode/status、RFC 7807 与 SSE error 同名映射 |
| SSE mapper | 所有 event JSON、转义、单 done/error |

### 14.3 Spring AI 真实链集成测试

使用 scripted/fake `ChatModel`，但运行真实 Spring AI Advisor 和 Tool Calling 代码：

1. `KNOWLEDGE`：
   - Query Transformer -> DocumentRetriever -> PostProcessor -> QueryAugmenter -> model；
   - PostProcessor 收到 Spring AI 的 original Query 时，仍从 request-scoped trace 使用
     DocumentRetriever 登记的 transformed query 做 rerank；
   - final response 含标准 document context；
   - useHybrid/useRerank 开关生效。
2. `AGENT`：
   - 模型第一轮返回 `searchKnowledge` tool call；
   - Spring AI 执行工具；
   - 第二轮收到 `ToolResponseMessage` 并生成最终答案；
   - streaming 使用同一 ToolCallAdvisor loop；
   - ATTEMPT provider 每次 candidate/retry 运行一次，MODEL_CALL provider 明确按 tool
     round 运行，core coordinator 的 lease/persistence 在整个逻辑请求只运行一次，
     Memory 不重复写入。
3. Memory：
   - 同 session 多轮；
   - 不同 session 隔离；
   - 不同 principal 使用同名 public session 时，memory/history 完全隔离；
   - 同 principal/session 并发第二请求返回 `409 SESSION_BUSY`；
   - STATELESS 不读写 memory；
   - clear 同时清业务历史和 ChatMemory；
   - failed candidate/retry 丢弃 request-local memory 后再 fallback，不重复写 user message；
   - history/JDBC memory 任一 durable write 失败则同事务回滚且不产生成功终态；
   - Spring context 使用项目显式的 `JdbcChatMemoryRepository` Bean；分别在 Memory 后续
     阶段和 history 后续阶段故障注入，数据库中不存在半提交；
   - stream error/cancel 丢弃 request-local memory，不修改共享投影；
   - 模型调用期间 lease expiry takeover 后，旧 request 的晚到 assistant 不会写入共享
     JDBC memory；
   - 开启 cancelled partial 持久化时，业务 history 与 ChatMemory 保持一致。
4. fallback：
   - 首 candidate 失败后成功；
   - stream 首 event 前可 fallback；
   - 首 event 后失败不拼接下一模型。

### 14.4 PostgreSQL/Testcontainers 集成

使用项目 PostgreSQL/pgvector 测试镜像和真实 Flyway：

- 建两个 Collection、两个 API Key scope、同关键词文档；
- Search 与 KNOWLEDGE 命中一致；
- `找到 “风格基调” 相关的内容` 无 Pattern 也能在 prod transformer 策略命中；
- AGENT tool 只能看到授权 Collection；
- forged tool arguments 无法越权；
- selected empty 和 `matchNone` 零命中；
- vector-only / hybrid / rerank 开关；
- FTS `@@` 命中不被 vector min-score 过滤；
- JSON record 不泄露到普通 document type 范围；
- owner + sources migration；从 V31 带合法/非法旧 session 与 null-owner 数据升级；
  新约束/索引；旧 null-owner 只读兼容、普通 clear/TTL 跳过且不进入 Memory rebuild。
- domain 未指定、显式已知和显式未知三种解析；bean 注册顺序不改变默认 Chat 行为。
- KNOWLEDGE/AGENT/PLAIN 的 domain prompt 组合不向 PLAIN 注入 grounding，也不向
  AGENT 注入空的 legacy context 占位段。
- 两个应用实例争抢同一 principal/session 时最多一个成功；lease 过期后重新 acquire
  会与每次正常 acquire 一样，从已提交 history 创建 request-local baseline；模拟旧
  owner 晚到完成时，token-fenced commit 失败且不会污染新 owner 的共享 JDBC projection。

### 14.5 Controller/SSE 集成

- `/chat`、`/ask` 默认 KNOWLEDGE。
- 三种 mode 的 validation。
- session auto generation。
- non-stream response sources/model/mode。
- SSE event 顺序。
- tool start/result。
- sources before done。
- history commit 发生在 done 前；commit 失败发送 error、不发送 done。
- error after stream started。
- emitter timeout/client cancel dispose。
- active stream 期间同 session 第二请求返回 409，取消完成后 lease 释放。
- stream lease 定期续租；complete/error/cancel/timeout/disconnect 后 token-fenced 释放。
- 非流式在长 transformer/retry/fallback 下也续租；模拟 renew 失败后终止且不提交成功
  history。
- 模拟 lease 已过期但尚未被新 owner takeover，旧 token 的迟到 renew 仍返回 0，不能复活
  lease 或继续提交。
- coordinator deadline 覆盖整个逻辑请求，candidate/retry/tool round 不能重置预算。
- ACL 403/zero-match。
- history reload/clear/export。
- key A 不能 read/continue/export/clear key B 的同名 session，且错误不泄露存在性。
- TTL cleanup 在 lease 内删除过期 turn 并从剩余 committed history 重建 memory；busy
  session 不被并发清理。

### 14.6 WebUI

Vitest：

- mode selector；
- model capability gating；
- request body；
- tool events；
- sources；
- stop/error/retry；
- active request 取消不显示连接错误，网络错误仍显示 retry；
- SSE CRLF、多 data 行、heartbeat、EOF 尾块与重复终态；
- route session；
- history sources。

Mock Playwright：

- KNOWLEDGE 完整流；
- AGENT tool activity；
- PLAIN 无 tool/source；
- selected Collections；
- model switching；
- cancel；
- reload history；
- mobile/desktop layout；
- 无横向溢出与文本重叠。

### 14.7 真实 LLM smoke

使用 `.env`，不输出 key。至少覆盖：

1. KNOWLEDGE：隔离 Collection 中导入唯一 token 文档，回答并返回来源。
2. 报告场景：`找到 “风格基调” 相关的内容`。
3. AGENT：已声明 tool-capable 的真实 model 必须产生至少一次 tool call。
4. ACL：受限 key 不能命中另一个 Collection 的唯一 token。
5. Streaming：content、sources、done；无 event duplication。
6. PLAIN：不产生 retrieval metrics/tool event。
7. Session ACL：用两个 database key 验证同名 session 的 history/memory 隔离。

若没有可用 tool-capable key，默认门禁仍通过 scripted model；真实 AGENT smoke 标为
明确 skipped，不得伪称通过。

## 15. 一键验证

新增：

```bash
./scripts/verify-chat-capability.sh
./scripts/verify-chat-capability.sh --with-real-llm
```

默认顺序：

1. focused backend unit/integration tests；
2. PostgreSQL/Testcontainers Chat tests；
3. `mvn clean compile test-compile`；
4. `mvn test`；
5. 使用隔离 PostgreSQL/Testcontainers 或现有测试数据库、dummy model credentials 启动
   Spring Boot，等待 `/actuator/health` 为 `UP` 后停止；该门禁不发起外部 LLM/Embedding
   调用，但必须实际执行 Flyway、JPA validate、Spring context 与 Web server 启动；
6. WebUI `npm run test:run`；
7. WebUI `npx tsc -b --pretty false`；
8. WebUI `npm run build`；
9. Chat 核心 Mock Playwright；
10. `./scripts/verify-project-docs.sh`；
11. `git diff --check`；
12. 输出日志目录和 gate summary。

默认启动 smoke 复用现有等待 health、PID 清理与端口隔离约定，但不能调用会加载 `.env`
并面向真实模型的 `start-real-e2e-server.sh`。若新增 helper，应由测试脚本自行启动临时
PostgreSQL/pgvector 容器或复用 Testcontainers，随机选择可用后端端口，注入 dummy
credentials，并在成功、失败和信号退出时都清理 Java 进程与容器。

`--with-real-llm` 追加：

- 启动隔离端口 backend；
- 创建隔离 Collection/key/fixture；
- 执行 KNOWLEDGE / AGENT / ACL / stream smoke；
- 清理测试数据和进程；
- key 只从环境读取且不写日志。

脚本应复用 `scripts/start-real-e2e-server.sh`、`scripts/real-llm-e2e-smoke.sh` 和
`scripts/verify-release.sh` 的现有 helper/约定，避免维护第二套启动逻辑。

## 16. 文档同步

实施完成后按 `project-docs` Skill 更新：

| 行为 | 文档 |
|---|---|
| 三模式和执行链 | `architecture*`、`project-context*` |
| 配置与 capability | `configuration*`、`multi-model-external-config*` |
| HTTP/DTO/history | `rest-api*` |
| SSE events | `SSE-PROTOCOL.md` |
| 验证命令 | `testing-guide*`、`developer-reference*` |
| Search 有结果但 Chat 没有 | `troubleshooting*`，删除 Pattern 作为正式解法 |
| WebUI 模式/stream | `spring-ai-rag-webui/README*` |
| 导航 | `docs/index*` 只在新正式专题需要入口时更新 |

本规划是单语 draft，不要求创建空壳英文副本；正式文档必须中英文成对同步。

## 17. 迁移、发布与回滚

### 17.1 Feature flags

实施期间保留：

```yaml
rag.chat.engine: legacy | spring-ai
rag.chat.agent.enabled: false | true
```

`rag.chat.engine` 只切换 KNOWLEDGE 的检索/Advisor 编排，不包围认证 principal、
session namespace、history ownership、ACL scope resolution、SSE serialization 或
source projection。这些属于共同安全与协议外壳，legacy/new engine 都必须经过，不能因
回滚而恢复旧的跨 key session 行为。

推荐发布步骤：

1. 新 engine 默认 off，测试和 internal environment 开启。
2. KNOWLEDGE shadow/对比评估，确认来源、延迟和召回。
3. 切换 prod KNOWLEDGE 到新 engine。
4. 开启 AGENT 给明确 tool-capable models。
5. 稳定一个版本后删除 legacy engine 和兼容 adapter。

语言 Pattern 不等待 legacy engine 删除：它们在新 engine 首次可发布时即从所有生产
路径移除。legacy 只允许作为“不含 Pattern 的旧 Advisor orchestration”临时回滚路径。

不能长期双写两个 ChatMemory 或两份业务历史。shadow 模式只比较 retrieval/构建结果，
不向两个模型同时生成用户可见回答。

### 17.2 回滚

- 应用回滚可切 `rag.chat.engine=legacy`，但该 adapter 不包含已否决的语言 Pattern。
- 回滚只影响 KNOWLEDGE retrieval engine；principal/session/history 安全修复不可关闭。
- 回滚配置也不能放宽 public session ID 校验、允许读取/claim 其他 owner、把 null-owner
  history 注入 ChatMemory，或绕过 lease/持久化顺序。
- 这里的“应用回滚”仅指新版本内切换 `rag.chat.engine=legacy`。部署旧二进制会重新使用
  未按 principal 限定的 repository/Memory 路径并写出新的 null-owner history，不是可接受
  的安全回滚；进入多 principal 生产阶段后禁止回滚到本迁移前二进制。若新 engine 故障，
  只能使用同一安全外壳内的 legacy engine flag 或向前修复。
- 新增 owner、sources、turn status 列都是 additive，可保留。
- 新 mode 字段省略仍兼容。
- capability 中 `toolCalling` 未声明默认 false，`streaming` 未声明默认 true，不影响
  现有 KNOWLEDGE/PLAIN call 与 stream。
- 不回滚已正确的 FTS threshold 修复。

### 17.3 可观测指标

至少新增/统一：

```text
rag.chat.requests{mode,status,model}
rag.chat.duration{mode,model}
rag.chat.fallbacks{mode,from,to}
rag.chat.retrieval.calls{mode}
rag.chat.retrieval.results{mode}
rag.chat.tools.rounds{tool,model}
rag.chat.tools.duration{tool}
rag.chat.tools.budget_exceeded{reason}
rag.chat.stream.cancelled{mode}
rag.chat.sources.count{mode}
```

日志使用 traceId/sessionId/modelRef/scope summary；不记录 raw key，不默认记录完整 prompt、
tool result 或 JSON payload。

## 18. 验收标准

全部满足才算实施完成：

1. 生产代码中不存在针对“找到/搜索/检索/引号”等自然语言意图的硬编码 Pattern。
2. KNOWLEDGE 使用 Spring AI `RetrievalAugmentationAdvisor` 和项目自定义检索组件。
3. AGENT 使用 Spring AI Tool Calling；没有自行实现 tool loop。
4. 项目混合检索、RRF、rerank、Embedding Profile、ACL 和 type scope 全部保留。
5. `useHybridSearch`、`useRerank`、`maxResults` 有真实集成测试证明生效。
6. ToolContext scope 不可被模型参数扩大，越权测试通过。
7. 同名 session 在不同 principal 间不能读取、续写、导出或删除，ChatMemory 也隔离。
8. 不支持 tools 的显式 model 不能进入 AGENT。
9. call/stream 在 sources、model routing、memory、history、errors 上对等。
10. SSE 有 tool/sources/done/error，取消会停止后端工作。
11. 同 principal/session 并发 turn 被拒绝；失败/取消不会留下孤儿 Memory message。
12. WebUI 不显示 score 百分比，能恢复历史来源并打开对应文档。
13. 清空 history 使用 ChatMemory API 并清两类存储。
14. 报告中的 `风格基调` 场景通过 PostgreSQL fixture 和真实 LLM smoke。
15. focused tests、`mvn clean compile test-compile`、`mvn test`、无外部模型调用的后端
    启动/health smoke、WebUI test/独立 tsc/build、Mock Playwright、文档门禁全部通过。
16. 一键脚本成功；真实 LLM 未运行时明确显示 skipped。
17. 正式中英文文档同步，不再把语言 Pattern 作为解决方案。

## 19. 风险与控制

| 风险 | 控制 |
|---|---|
| Spring AI Advisor order 与直觉不同 | characterization test 固定 1.1.4 实际顺序 |
| Tool model 不调用工具 | AGENT system instruction、真实 smoke、保留 KNOWLEDGE |
| Tool loop 失控 | BudgetedToolCallAdvisor、call/result/token/time caps |
| ToolContext 异步丢失 | request-scoped immutable object，不用 ThreadLocal |
| 流式工具事件竞态 | 单 coordinator、thread-safe collector、事件顺序测试 |
| Query Transformer 增加成本 | dev none、prod 显式 spring-ai、metrics/goldenset |
| 动态 model capability 误报 | tools 显式配置且默认 false、streaming 兼容默认 true、legacy 映射、真实 smoke |
| 来源 metadata 在 Document 转换中丢失 | 单一 mapper round-trip tests |
| domain hook 与流式冲突 | 不激活 text postProcess，先 deprecated |
| legacy WIP 被误删 | Phase 0 文件清单和 characterization，逐组件替换 |
| 自定义 Advisor SPI 回归 | supportedModes/advisorScope default compatibility + starter tests |
| 旧 Advisor order 假设能插入标准 RAG 内部阶段 | Phase 0 清点；迁移到标准 transformer/post-processor/augmenter；ATTEMPT/MODEL_CALL 明确作用域 |
| 历史 schema 回滚 | additive JSONB migration |
| session ID 被当作授权凭据 | owner principal 列、内部 memory namespace、跨 key 集成测试 |
| source snapshot 泄露结构化 payload | DTO allow-list、snippet cap、原始 payload 只走授权数据 API |
| 取消/失败/fallback 留下孤儿或重复 memory | PostgreSQL lease、每 attempt 独立 request-local ChatMemory、最终 token-fenced 原子提交 |

## 20. 实施时禁止事项

- 禁止继续增加自然语言 Pattern。
- 禁止让 Controller 调另一个 Controller。
- 禁止让 Tool 参数携带授权范围。
- 禁止把 raw API Key 放入 ToolContext、日志或模型 prompt。
- 禁止用 ThreadLocal 承载异步授权。
- 禁止把 fused score 显示成百分比。
- 禁止用 `.stream().content()` 作为需要来源/usage/错误语义的最终执行层。
- 禁止因 Spring AI 默认 RAG 简单而放弃项目混合检索。
- 禁止因项目检索更强而重新实现 Spring AI 的 tool loop、memory 或 advisor lifecycle。
- 禁止修改已执行 Flyway migration。
- 禁止 stash、reset 或丢弃其他人的工作区修改。
