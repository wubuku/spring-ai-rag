# 对话能力重构实施规划

> **状态**：规划中，待用户 Review 后实施。
> **规划日期**：2026-08-17
> **代码基线**：`main` @ `a1e05ed5126a372a10229d6de936b1f8ac33f745`，Spring AI
> `1.1.4`，Flyway 当前上限 V31。
> **工作区说明**：规划期间工作区存在 Query Rewrite、Chat 会话导航、PDF 来源追踪等
> 并行 WIP。实施时不得 stash、reset、覆盖或丢弃这些修改；先用 characterization tests
> 固定有效行为，再逐步替换被本规划明确淘汰的实现。
> **规划边界**：本文只定义实施方案，不代表能力已经上线。连续三轮无修改检查的时间、
> 范围和结论在会话中汇报，不写入本文，以免检查记录本身破坏“连续无修改”条件。

相关现状与接口文档：

- [项目架构](../architecture-zh-CN.md)
- [REST API](../rest-api-zh-CN.md)
- [SSE 协议](../SSE-PROTOCOL.md)
- [配置参考](../configuration-zh-CN.md)
- [测试指南](../testing-guide-zh-CN.md)
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

[`ChatRequest`](../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/ChatRequest.java)
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
[`CollectionRetrievalScopeResolver`](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionRetrievalScopeResolver.java)
解析 Collection key/ID、API Key allow-list 和 document ID，形成不可扩权的
[`RetrievalScope`](../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalScope.java)。

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

[`HybridRetrieverService`](../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java)
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
| Query 转换 | `QueryTransformer`、`RewriteQueryTransformer`、`CompressionQueryTransformer` |
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
- `PLAIN` 忽略 retrieval tuning 字段，并在响应 metadata 标明没有执行 retrieval。
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
      query-transformer: none       # application.yml / 本地默认
      query-transform-timeout: 10s
```

`application-prod.yml` 推荐：

```yaml
rag:
  chat:
    knowledge:
      query-transformer: spring-ai
```

`spring-ai` 策略只编排 Spring AI 内置 transformer：

- 无有效历史：`RewriteQueryTransformer`
- 有前序 user/assistant turn：`CompressionQueryTransformer`

选择依据是消息结构，不是自然语言内容。外层只增加：

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

- 本地 `none` 避免每个简单请求多一次 LLM call。
- production `spring-ai` 优先解决完整命令句、含上下文 follow-up 的检索质量。
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
- `memoryConversationId` 使用稳定单向摘要/UUID 派生，满足 Spring AI JDBC schema
  `VARCHAR(36)`，不直接拼 raw API key；
- database key 使用 `keyId`，environment root、legacy static 和 auth-disabled local
  分别使用固定 principal 类型标识；
- 升级时不把旧的 raw-session ChatMemory 自动归属给任意 database key；这部分短期
  memory 采用 fail-secure reset，业务历史按第 9 节兼容规则保留。
- `SERVER` 模式对同一 `(principal, sessionId)` 只允许一个 in-flight turn；第二个请求
  立即返回 `409 SESSION_BUSY`，不排队，避免 Memory Advisor 写入、取消补偿和 history
  顺序互相覆盖。`STATELESS` 不受此限制。
- coordinator 在执行前保存 `ChatMemory.get(memoryConversationId)` 快照。成功完成由
  Memory Advisor 正常提交；在 error/cancel 时用 `clear + add(snapshot)` 恢复 turn 前
  状态。该补偿必须在 session guard 释放前完成。

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
    Map<String, Object> metadata
) {}
```

`ChatPrincipal` 由认证 filter 的 request attributes 映射，至少区分 environment root、
database key 的 stable `keyId`、legacy static 和 auth-disabled local。它只携带稳定 ID、
类型和管理权限，不携带 raw credential。

`RetrievalOptions` 至少包含：

```text
maxResults
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
- omitted request 仍保持现有 global default；只有选定 domain 且字段省略时，domain
  default 才参与合成；
- 为 JSON 省略、显式 true/false/5 和 Java 直接调用分别写 contract tests。

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
- 调用 `HybridRetrieverService.searchInScope(...)`。
- 把 `RetrievalResult` 映射为 Spring AI `Document`。
- 所有来源、score、chunk 和 provenance 放入 metadata。
- 不解析 HTTP 请求，不读取 ThreadLocal API Key，不自行扩权。

#### `ProjectRerankPostProcessor`

- 实现 `DocumentPostProcessor`。
- `useRerank=false` 时原样返回。
- `useRerank=true` 时通过共享 mapper 调用 `ReRankingService`。
- 不丢失 Spring AI Document metadata。
- 记录 rerank step metrics。

#### `CitationQueryAugmenter`

- 实现 `QueryAugmenter`。
- 使用 `[S1]`、`[S2]` 等稳定引用标签格式化 context。
- 空结果时生成明确的“当前授权范围内未检索到材料”提示。
- 保留原始用户 query 供最终回答；转换后的 query 只用于 retrieval/rerank。
- 把最终 documents 保留在标准
  `RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT` 中。

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
    @ToolParam(description = "Requested result count") Integer maxResults,
    ToolContext toolContext
)
```

关键约束：

- Tool schema 只向模型暴露 `query` 和可选 `maxResults`。
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

扩展 `models.json` / `MultiModelProperties.ModelItem`：

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

规则：

- `toolCalling` 未配置时保守视为 `false`。
- 不仅按 `apiType=openai` 或 Java class 推断。
- `GET /api/v1/rag/models` 返回 capabilities。
- WebUI 根据 capabilities 启用/禁用 `AGENT`。
- `AGENT` fallback 只考虑 `toolCalling=true` 的候选。
- 显式选择不支持 tools 的模型时返回清晰 400。
- capability 是 operator 声明，不等于运行健康；真实 LLM smoke 验证至少一个工具模型。

### 6.7 Advisor 与工具顺序

按模式构建独立 ChatClient，不在单个大链中通过大量 skip flag 切换：

```text
KNOWLEDGE:
  custom pre-advisors
  -> MessageChatMemoryAdvisor (SERVER only)
  -> RetrievalAugmentationAdvisor
  -> custom advisors that must observe the augmented request
  -> model

AGENT:
  custom pre-advisors
  -> MessageChatMemoryAdvisor (SERVER only)
  -> BudgetedToolCallAdvisor
  -> model

PLAIN:
  custom advisors compatible with PLAIN
  -> MessageChatMemoryAdvisor (SERVER only)
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

实施时必须显式设置 order，不能依赖两者默认值：Spring AI 1.1.4 的
`MessageChatMemoryAdvisor` 默认 order 为 `HIGHEST_PRECEDENCE + 1000`，
`ToolCallAdvisor` 默认 order 为 `HIGHEST_PRECEDENCE + 300`，默认组合不满足上述
AGENT 外层 Memory 语义。通过 call/stream characterization tests 固定实际 request、
response 和递归顺序。

`RagAdvisorProvider` 增加向后兼容的 `supportedModes()` default method，旧 provider
默认只应用于 `KNOWLEDGE`，避免现有 RAG-oriented provider 在 `PLAIN` 中意外触发检索；
明确通用的 safety/telemetry advisor 由实现者 opt in 到其他模式。

### 6.8 Domain extension

激活当前已有但未使用的 `getRetrievalConfig()`：

```text
global hard caps
  > request explicit values（不得超过 cap）
  > domain defaults
  > global defaults
```

这里的“request explicit values”依赖第 6.2 节的 presence tracking，不能把 primitive
getter 当前返回的默认值一律视为显式 override。

`getSystemPromptTemplate()` 继续提供领域 instruction，但 context 注入归
`CitationQueryAugmenter` / `KnowledgeSearchTool`：

- 新版 domain template 不应包含 `{context}`。
- 为现有扩展兼容：发现 `{context}` 时以空串渲染并记录一次迁移 warning。
- 默认 extension 更新为纯领域/grounding instruction。

`postProcessAnswer()` 暂不激活。它无法在不缓存全部 token 的情况下与真流式保持对等。
实施时标记为 deprecated，并在后续设计 stream-aware response customizer。

`isApplicable()` 也不用于自动路由；当前 `domainId` 是调用者显式选择。隐式领域分类不在
本轮范围。

## 7. 统一执行、fallback 与错误

### 7.1 ChatClient factory

新增 `ModeAwareChatClientFactory`，按以下 key 创建或缓存客户端：

```text
resolved model identity
chat mode
memory mode
query transformer policy
domain policy identity/version
```

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
- 发送任何 content/tool/source event 后，不得切换模型拼接响应；
- 已开始后失败，发送 `event:error` 并终止。

### 7.3 Resilience

- Circuit breaker、retry 和 metrics 统一包围每个 candidate attempt。
- retry 只能在该 attempt 尚未向客户端发送事件时进行。
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
- Controller 保存 Reactor `Disposable`。
- `SseEmitter#onCompletion`、`onTimeout`、`onError` 都 dispose subscription。
- completion 后统一提交 history；error/cancel 在 session guard 内恢复 Memory 快照，
  再按配置决定是否提交 cancelled partial。
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

CREATE INDEX ... ON rag_chat_history(owner_principal_id, session_id, created_at);
```

保存规则：

- 所有新记录写 `owner_principal_id`；database key 使用 stable `keyId` 的 namespaced
  形式，不保存 raw credential。
- repository 的 find/delete/export 均要求 principal，不再暴露只收 sessionId 的生产
  API；root 管理路径可显式跨 principal，不能靠传 `null` 绕过。
- 旧记录保持 `owner_principal_id IS NULL`：environment root、legacy static 和
  auth-disabled local 可作为兼容/运维入口访问；普通 database key 不得读取或 claim，
  避免把历史错误归属给第一个请求者。
- `related_document_ids` 暂时保留兼容，并由 sources 派生。
- `sources` 保存当时的 source snapshot，不在历史读取时重新检索。
- snapshot 只保存 `ChatSource` allow-list 字段和裁剪 snippet，不保存任意 metadata、
  raw JSONB/XML payload 或内部文件系统敏感路径。
- metadata 保存：
  `mode`、`memoryMode`、`requestedModel`、`resolvedModel`、`traceId`、
  `finishReason`、`usage`、`stepMetrics`。
- 正常完成保存完整 turn。
- `turn_status` 使用受控枚举值 `COMPLETE` / `CANCELLED`；普通历史默认返回两者，并由
  UI 明确标记 cancelled partial。
- 用户取消默认不保存 partial，且恢复 turn 前 ChatMemory 快照；只有
  `persist-cancelled-partial=true` 且已有非空 partial 时，才在 guard 内先恢复快照，
  再把原 user + partial assistant 同步追加到 ChatMemory 和业务历史并标记
  `CANCELLED`。
- LLM 失败不写普通 history turn；通过结构化日志、metrics 和现有 audit 机制记录。
- TTL cleanup 同时清理业务历史；Spring AI memory 继续按会话 clear/窗口策略管理。

数据库迁移不修改已执行的 V1-V31。

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
`ChatPrincipal` 并按 owner 查询。public `sessionId` 限制为 1-36 个允许字符；path
参数执行同样校验。对“不存在”和“属于其他 principal”返回相同的 not-found 语义，
避免 session 枚举。environment root 的跨 owner 管理能力如需开放，使用单独明确的
admin contract，不让普通 endpoint 隐式越权。

### 10.4 OpenAI compatibility 边界

本轮不新增 `/v1/chat/completions`。但 `ChatCommand` 支持 messages 列表和
`MemoryMode.STATELESS`，使后续兼容层只做协议 mapper，而不是再复制执行链。

现有自定义 Chat SSE 不能宣称完整 OpenAI Chat Completions streaming compatibility。

## 11. 配置设计

建议新增：

```yaml
rag:
  chat:
    default-mode: KNOWLEDGE
    knowledge:
      query-transformer: none
      query-transform-timeout: 10s
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

完成门槛：不改变生产行为，测试能描述现状和目标差距。

### Phase 1：内部契约与共享检索适配

1. 新增 API `ChatMode`，以及 core 内部 `MemoryMode`、`ChatPrincipal`、
   `ChatCommand`、`RetrievalOptions`、`ChatExecutionResult`、`ChatEvent`。
2. 新增 `AuthorizedKnowledgeRetriever`、`RetrievalDocumentMapper`、
   `RetrievalTraceCollector`。
3. 让 request/domain/global retrieval options 真正合成。
4. 增加 `spring-ai-rag` core dependency。

完成门槛：不接 UI，内部 mapper 和 ACL tests 通过。

### Phase 2：`KNOWLEDGE` 标准 RAG

1. 实现 `ProjectDocumentRetriever`。
2. 实现 `ProjectRerankPostProcessor`。
3. 实现 `CitationQueryAugmenter`。
4. 组装 `RetrievalAugmentationAdvisor`。
5. 接入 Spring AI query transformers 和 resilient fallback。
6. 从标准 `DOCUMENT_CONTEXT` 提取 sources。
7. 让 hybrid/rerank/maxResults 生效。
8. 删除旧三 Advisor 的主生产注册；保留不含语言 Pattern 的短期 legacy retrieval
   adapter 和一次性迁移测试。
9. 删除 `QueryRewritingService` 的语言 Pattern 与专用 context key，不允许 legacy
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
6. 增加结构化 SSE events、取消与 error。

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
.../chat/AuthorizedRetrievalContext.java                  # new
.../chat/RetrievalTraceCollector.java                     # new
.../chat/KnowledgeSearchTool.java                         # new
.../chat/BudgetedToolCallAdvisor.java                     # new
.../rag/ProjectDocumentRetriever.java                     # new
.../rag/ProjectRerankPostProcessor.java                   # new
.../rag/CitationQueryAugmenter.java                       # new
.../rag/RetrievalDocumentMapper.java                      # new
.../config/RagChatProperties.java                         # new
.../config/MultiModelProperties.java
.../config/ConfiguredChatModelFactory.java
.../config/ChatModelRouter.java
.../config/RagChatService.java                            # shrink/delegate or replace
.../controller/RagChatController.java
.../repository/RagChatHistoryRepository.java
.../entity/RagChatHistory.java
.../db/migration/V{next}__add_chat_history_owner_sources.sql
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
| query transformer router | first turn rewrite、follow-up compression、失败回退 |
| model capability | 未声明 false、agent candidate 过滤、显式不支持报错 |
| tool budget | rounds、calls、重复 query、result/character cap |
| source collector | 多 call 去重、citation ID 稳定、并发安全 |
| source projection | metadata allow-list、snippet cap、排除 raw JSONB/XML payload |
| principal namespace | 同 session 不同 principal 生成不同 memory ID，输出固定 36 字符 |
| history mapper | sources/status/model/mode round trip |
| SSE mapper | 所有 event JSON、转义、单 done/error |

### 14.3 Spring AI 真实链集成测试

使用 scripted/fake `ChatModel`，但运行真实 Spring AI Advisor 和 Tool Calling 代码：

1. `KNOWLEDGE`：
   - Query Transformer -> DocumentRetriever -> PostProcessor -> QueryAugmenter -> model；
   - final response 含标准 document context；
   - useHybrid/useRerank 开关生效。
2. `AGENT`：
   - 模型第一轮返回 `searchKnowledge` tool call；
   - Spring AI 执行工具；
   - 第二轮收到 `ToolResponseMessage` 并生成最终答案；
   - streaming 使用同一 ToolCallAdvisor loop。
3. Memory：
   - 同 session 多轮；
   - 不同 session 隔离；
   - 不同 principal 使用同名 public session 时，memory/history 完全隔离；
   - 同 principal/session 并发第二请求返回 `409 SESSION_BUSY`；
   - STATELESS 不读写 memory；
   - clear 同时清业务历史和 ChatMemory；
   - stream error/cancel 恢复 turn 前 memory snapshot；
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
- owner + sources migration、旧 null-owner 兼容规则和历史读取。

### 14.5 Controller/SSE 集成

- `/chat`、`/ask` 默认 KNOWLEDGE。
- 三种 mode 的 validation。
- session auto generation。
- non-stream response sources/model/mode。
- SSE event 顺序。
- tool start/result。
- sources before done。
- error after stream started。
- emitter timeout/client cancel dispose。
- active stream 期间同 session 第二请求返回 409，取消完成后 guard 释放。
- ACL 403/zero-match。
- history reload/clear/export。
- key A 不能 read/continue/export/clear key B 的同名 session，且错误不泄露存在性。

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
5. WebUI `npm run test:run`；
6. WebUI `npm run build`；
7. Chat 核心 Mock Playwright；
8. `./scripts/verify-project-docs.sh`；
9. `git diff --check`；
10. 输出日志目录和 gate summary。

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
- 新增 owner、sources、turn status 列都是 additive，可保留。
- 新 mode 字段省略仍兼容。
- capability 字段未声明默认 false，不影响 KNOWLEDGE。
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
15. focused tests、`mvn clean compile test-compile`、`mvn test`、WebUI test/build、
    Mock Playwright、文档门禁全部通过。
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
| 动态 model capability 误报 | 显式配置默认 false、真实 smoke |
| 来源 metadata 在 Document 转换中丢失 | 单一 mapper round-trip tests |
| domain hook 与流式冲突 | 不激活 text postProcess，先 deprecated |
| legacy WIP 被误删 | Phase 0 文件清单和 characterization，逐组件替换 |
| 自定义 Advisor SPI 回归 | supportedModes default compatibility + starter tests |
| 历史 schema 回滚 | additive JSONB migration |
| session ID 被当作授权凭据 | owner principal 列、内部 memory namespace、跨 key 集成测试 |
| source snapshot 泄露结构化 payload | DTO allow-list、snippet cap、原始 payload 只走授权数据 API |
| 取消/失败留下孤儿 user memory | per-session single-flight、turn 前快照、guard 内补偿恢复 |

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
