# Chat 记忆、RAG 与工具调用

> [English](chat-memory-rag-tool-calling.md) | [中文](chat-memory-rag-tool-calling-zh-CN.md)
>
> 本文记录生产 Chat 对 Spring AI 内置能力的使用边界、当前项目增强、已知缺口和推荐演进
> 方向。配置项以[配置参考](configuration-zh-CN.md)为准，接口契约以
> [REST API](rest-api-zh-CN.md)和 [SSE 协议](SSE-PROTOCOL.md)为准。

## 1. 调研问题与结论

本次调研回答以下问题：

1. 当前 Chat 是否充分使用 Spring AI 的会话历史、记忆和上下文能力？
2. “上下文压缩”现在具体压缩什么，是否等同于长期会话摘要？
3. 文档检索使用 Function Call，还是 Spring AI 的专用 RAG 机制？
4. Agent 工具循环如何避免无限调用，当前预算是否完整？
5. 如何扩展非 embedding 检索工具，例如直接查询关系型数据？

结论是：项目已经实质接入 Spring AI 的 `ChatClient`、Advisor、Chat Memory、Modular
RAG、Tool Calling 和 `ToolContext`，不是自建一套与框架平行的 Chat 循环；同时已经补齐
token-aware prompt 规划、逻辑请求级模型/工具预算、带 CAS 的持久摘要、协调式 TTL 清理，
以及服务端拥有的 Tool Provider SPI。当前边界是：Spring AI 提供编排原语，项目负责授权、
上下文打包、持久化和有界执行策略。

## 2. 当前版本与生产执行入口

当前实现基线为：

- Spring Boot `3.5.16`
- Spring AI `1.1.8`
- Java `21`
- 生产 Chat 主链：
  `RagChatController -> ChatCommandMapper -> ChatExecutionService ->
  ModeAwareChatClientFactory`

`1.1.8` 的 API 变化已经显式适配：生产代码不再在
`MessageChatMemoryAdvisor.Builder` 上绑定 conversation ID，而是在每次请求的 advisor
context 中传入服务端派生的 `ChatMemory.CONVERSATION_ID`，从而保留非流式和流式请求的
principal/session 隔离。Spring AI 的 `ToolCallAdvisor` 仍会持续循环直到模型停止请求工具，
因此项目通过自有的逻辑请求预算包装模型与工具执行。

独立依赖探针还确认了一个必须显式处理的 1.1.8 API 变化：
`MessageChatMemoryAdvisor.Builder.conversationId(...)` 已不存在。生产
`ModeAwareChatClientFactory` 不能再把 conversation ID 固定在 advisor builder 上；必须在
每次非流式和流式请求的 advisor context 中传入
`ChatMemory.CONVERSATION_ID`。探针完成该迁移后，core 编译与 test-compile 通过，
`ModeAwareChatClientFactoryTest`、`ChatExecutionServiceTest` 和
`ChatMemoryMultiTurnTest` 共 30 个测试通过。该迁移只适配框架 API，不改变 principal/session
隔离和 request-local Memory 语义。

关键代码入口：

- [`ChatMode`](../spring-ai-rag-api/src/main/java/com/springairag/api/enums/ChatMode.java)
- [`ChatExecutionService`](../spring-ai-rag-core/src/main/java/com/springairag/core/chat/ChatExecutionService.java)
- [`ModeAwareChatClientFactory`](../spring-ai-rag-core/src/main/java/com/springairag/core/chat/ModeAwareChatClientFactory.java)
- [`ChatSessionCoordinator`](../spring-ai-rag-core/src/main/java/com/springairag/core/chat/ChatSessionCoordinator.java)

## 3. 三种 Chat 模式

生产 API 使用显式模式，不用语言正则猜测是否检索：

| 模式 | 检索行为 | Spring AI 机制 | 适用语义 |
|---|---|---|---|
| `PLAIN` | 不检索 | `ChatClient` + Memory | 普通模型对话 |
| `KNOWLEDGE` | 每轮固定检索 | `RetrievalAugmentationAdvisor` | 强 grounding、稳定来源 |
| `AGENT` | 模型按需调用零到多次工具 | `ToolCallAdvisor` | 多工具探索和动态决策 |

保留三种模式是有意的产品边界：

- 确定性文档问答不应为了“统一”而强制改造成 Agent。
- Agent 不应在用户明确要求普通模型任务时偷偷增加检索和成本。
- 模型是否需要检索是一种执行策略，不是所有 Chat 的共同前置步骤。

## 4. 文档检索不是单一路径

### 4.1 KNOWLEDGE：Spring AI Modular RAG

`KNOWLEDGE` 不依赖 Function Call。项目通过 Spring AI
`RetrievalAugmentationAdvisor` 组合：

```text
history-aware query transformation
  -> optional multi-query expansion
  -> ProjectDocumentRetriever
  -> project hybrid vector/full-text retrieval
  -> project rerank
  -> CitationQueryAugmenter
  -> ChatModel
```

项目没有直接使用简单的 `VectorStoreDocumentRetriever` 替换现有检索栈，而是由
`ProjectDocumentRetriever` 适配 Spring AI `DocumentRetriever` 接口。这样可以继续保留：

- Collection、Document 和 API Key ACL；
- 向量与中英文全文检索；
- RRF/加权融合；
- rerank；
- metadata/payload 过滤；
- 引用来源与诊断。

这条路径适合“必须查知识库”的请求，因为检索一定执行，延迟、来源和失败语义更可预测。

### 4.2 AGENT：Spring AI Tool Calling

`AGENT` 通过 Spring AI `ToolCallAdvisor` 把模型 tool call 交给服务端工具。当前工具为：

- `searchKnowledge`：授权范围内的项目混合文档检索；
- `searchJsonRecords`：可选的 JSON 结构化记录检索，默认关闭。

模型参数只包含查询词、可选结果数和工具允许的收窄条件。Collection、Document、ACL、
principal 和请求过滤由服务端通过 `ToolContext` 注入，模型不能通过参数扩大权限。

因此同一个文档检索能力有两种编排方式：

```text
KNOWLEDGE -> 服务端固定执行 Modular RAG
AGENT     -> 模型按需调用 searchKnowledge
```

二者共享底层检索器，但不能混淆执行语义。

## 5. 会话历史与 Chat Memory

项目有两个用途不同的持久化层：

| 存储 | 用途 | 当前管理方式 |
|---|---|---|
| `rag_chat_history` | 业务历史、owner、来源快照、模式、模型、usage、审计 | 应用事务 |
| `spring_ai_chat_memory` | 提供给模型的近期消息窗口 | Spring AI JDBC Chat Memory |

当前每个生产请求：

1. 按 principal 和 session 获取数据库 lease，保证同一会话 single-flight；
2. 从已提交的业务历史加载基线；
3. 创建 request-local `MessageWindowChatMemory`；
4. 由 `MessageChatMemoryAdvisor` 把历史加入 prompt；
5. 模型候选、重试或工具调用失败时不写正式记忆；
6. 最终成功 turn 的业务历史与 JDBC Memory 在同一事务提交。

这一设计优于让每个 fallback attempt 直接写共享 Memory，因为失败候选不会污染后续对话。
应用层 retry 和 fallback candidate 都从同一个 committed baseline 创建新的 request-local
ChatClient、Memory、advisor、检索和工具状态。失败 attempt 的局部状态整体丢弃；模型、工具
和检索已发生的消耗仍计入共享的逻辑请求预算。这样可以避免失败的 advisor 调用把重复 user
message 带入成功 attempt 的 JDBC Memory。

### 5.1 当前窗口的真实语义

Spring AI `MessageWindowChatMemory` 仍负责 request-local 的近期消息窗口，但生产 Chat 现在
会在模型调用前由项目 planner 进行输入规划。planner 在模型元数据有效时使用 context window，
预留 output 和 safety token，并约束 history、summary、RAG evidence 与 tool schema。关闭
adaptive packing 时仍保留旧 baseline 消息行为，但模型调用 prompt hard gate 和执行预算仍
生效。底层 Spring AI window 本身仍不会：

- 根据模型 `contextWindow` 计算可用 prompt；
- 为输出 token、RAG 证据或工具 schema 预留空间；
- 对淘汰历史生成摘要；
- 区分一条极长消息和一条极短消息的成本。

项目配置保存每个模型的 `context-window` 和 `max-tokens`。缺失值使用带诊断的保守
fallback；显式非正值使 candidate 不可用。非法限制不会被解释为无限窗口或零成本输出。
空间不足时按固定顺序裁剪低优先级 evidence/tool output、减少较旧历史但保留最少近期 turns、
保留 summary；mandatory input 仍超限时返回 typed context budget error。

### 5.2 TTL 清理与活跃 Chat 的并发边界

`rag_chat_history`、Spring AI JDBC Memory 和会话 lease 是同一会话的三个相互关联状态。
TTL 如果只删除旧 history，再异步清理其他状态，会留下两类风险：模型 Memory 仍保留已经
过期的消息；或者一个 Chat 请求先读取旧 baseline，TTL 随后删除数据，而该请求最后又把旧
baseline 写回。

当前 TTL 清理已经按有界批次发现候选 session，再复用
`rag_chat_session_lease` 申请独立 maintenance token。发现有效 Chat lease 时立即跳过，不
等待、不抢占；获得 maintenance lease 后，在短事务中以 token fencing 消费，并删除同一
principal/session 下的过期 history、summary 和 JDBC Memory。提交时 maintenance lease 消失，
回滚时相关状态保持一致。活跃 Chat 的最终提交同样必须经过自己的 token fencing，不能在 TTL
之后回写清理前读到的旧 baseline。该协议只使用条件写入、lease 和有界批次，不引入
`FOR UPDATE`、`SKIP LOCKED` 或 advisory lock。

## 6. “上下文压缩”需要区分两件事

项目可以启用 Spring AI `CompressionQueryTransformer`。它的职责是把“前序对话 + 当前
追问”改写成可独立检索的 query。例如：

```text
历史：用户在讨论 BGE-M3
追问：那它的维度呢？
检索 query：BGE-M3 embedding 维度
```

这是**检索查询压缩**，不是**会话上下文压缩**。项目现在另外提供可选的、项目自有的持久
摘要压缩路径，但两者仍然分工明确：

- `CompressionQueryTransformer` 不会把旧对话总结后写入持久记忆、减少最终 Chat prompt
  中的历史 token，或建立摘要层级。
- `ConversationSummaryService` 只压缩受保护近期 turns 之前的 COMPLETE turns，保存前进式
  history cursor，并通过 V46 的 optimistic version CAS 更新摘要行。
- 摘要默认关闭，受共享 model-call budget 和 deadline 约束；超时、provider 失败、输出超限
  或 CAS 冲突时降级，不阻断主 Chat。摘要是会话记忆，不是 citation evidence。

主 `application.yml` 默认 `query-transformer: none`，而 `postgresql` 和 `prod` profile
启用 `spring-ai`。因此判断运行行为时必须同时看活动 profile，不能只看主配置。

## 7. 当前工具循环与预算

Spring AI `ToolCallAdvisor` 负责 call 和 stream 的标准工具循环：

```text
模型响应 tool calls
  -> ToolCallingManager 执行工具
  -> 工具结果加入 conversation history
  -> 再次调用模型
  -> 直到模型不再请求工具
```

项目通过 `BudgetedChatModel`、`BudgetedToolCallingManager`、`ChatExecutionBudget`、
`RagChatToolRegistry` 和 `RetrievalTraceCollector` 增加了逻辑请求级与工具 policy 限制：

- 最大工具轮数默认 `3`；
- 最大未缓存检索次数默认 `3`；
- 单次检索结果默认最多 `10`；
- 累计唯一 source 默认最多 `20`；
- 单次工具结果默认最多 `24,000` 字符；
- 总工具调用默认最多 `6`，单工具名默认最多 `3`；
- 工具结果累计字符默认最多 `48,000`；
- 逻辑请求 deadline 默认非流式 `120s`、流式 `180s`。

共享预算跨 candidate、retry/fallback、model call、tool round、tool call、每工具名调用数、
累计 tool-result 字符、检索 trace 和摘要压缩。`BudgetedToolCallingManager` 在执行前预留
完整 tool-call batch，并按真实 `ToolExecutionResult` 结算；policy wrapper 另行限制单工具
超时、执行器饱和、只读效果和结果大小。超出预算时返回有界工具错误或 typed Chat error，
不会继续无界工作。

默认模型仍可能声明不支持 Tool Calling；只有明确声明
`capabilities.toolCalling=true` 且经过 provider 验证的模型才应进入 WebUI 或 API 的
`AGENT` 路径。

## 8. 外部 Function Call 与 SQL 检索

core registry 负责内置 `searchKnowledge` 和可选 `searchJsonRecords`，同时发现额外的
服务端 `RagChatToolProvider` Bean。Provider 声明支持的 mode/domain、callback 定义和可选
收紧 policy；启动期校验会拒绝重复名、非法 schema/metadata、`returnDirect=true` 和未知
policy key。Registry 通过 Spring AI `ToolContext` 注入 principal/session/deadline 和 budget。

OpenAI 兼容端点仍明确拒绝客户端提交：

- `tools`
- `tool_choice`
- `functions`
- `function_call`
- tool/function 类型消息

这是有意的信任边界。客户端自定义工具透传意味着服务端要管理外部工具生命周期、回调协议、
身份传播、超时和结果可信度，与“服务端拥有并执行工具”是不同产品，不应顺带开放。

### 8.1 推荐的 SQL 方式

不要向模型提供：

```text
executeSql(sql)
```

应提供领域化、只读、参数化的工具，例如：

```text
lookupInventory(sku, warehouse, maxResults)
searchOrders(status, createdAfter, maxResults)
queryAssetStatus(assetIds)
```

实现原则：

- 工具 schema 只暴露业务参数，不暴露 SQL、表名或数据库凭据；
- 服务端固定 SQL 或 approved query template；
- 使用参数绑定，不拼接模型输入；
- 独立只读数据库角色；
- 允许的 schema/view 和字段白名单；
- principal ownership 条件由服务端注入；如果消费者有 tenant 概念，必须通过服务端
  principal/ACL 映射解析，不能从请求 metadata 或模型参数取得；
- statement timeout、结果行数和序列化字节上限；
- 单语句、无 DDL/DML；
- 工具调用、参数摘要、耗时、行数和预算结果可审计；
- 数据结果不是文档来源时，不伪装成 RAG 文档 citation。

通用框架提供工具扩展 SPI 和安全上下文，但不在 core 内预置可查询任意业务库的通用 SQL
Agent。SQL demo 使用固定 PostgreSQL `SELECT`、命名参数、服务端 principal 过滤、20 行
上限、受 policy 限制的 statement timeout 和序列化结果上限，落实了推荐形状。

## 9. 当前架构与剩余工作

### 9.1 Token-aware Prompt Budget

当前实现为每个逻辑请求建立候选模型级预算，使用模型 `contextWindow` 和 `maxTokens`，为以下部分
分别计量和预留：

1. system/domain instruction；
2. 当前用户消息；
3. tool schema；
4. 输出 token；
5. safety margin；
6. RAG 证据或工具结果；
7. 会话摘要；
8. 最近原始 turns。

预算不足时按确定顺序降级：

```text
裁剪低优先级 RAG/工具结果
  -> 减少最近历史但保留最少 turns
  -> 保留摘要
  -> 若 mandatory prompt 仍超限则返回 typed error
```

不能依赖 provider 在超出上下文后返回模糊的 400 错误。

### 9.2 持久摘要 + 最近原始 turns

建议保留：

```text
rag_chat_history       -> 完整业务历史与审计
spring_ai_chat_memory  -> 最近原始消息窗口
chat summary table     -> 旧历史的持久摘要和压缩游标
```

摘要只表示会话记忆，不是外部事实证据。`KNOWLEDGE` 回答仍必须以检索来源 grounding；
摘要中出现的事实不能自动变成 citation。V46 schema 和 CAS 服务已经实现该路径；摘要压缩
默认关闭。

压缩失败时应保留成功主路径：按 token 预算确定性截断旧历史，记录 degraded 状态，不删除
原始业务历史。摘要生成不得在持有数据库事务时调用模型。

### 9.3 逻辑请求级统一预算

预算对象必须跨 candidate、retry 和工具循环共享，至少包含：

- deadline；
- candidate attempt 数；
- 模型调用总数；
- 工具轮数；
- tool call 总数；
- 每个工具的调用数；
- 累计工具结果字符/token；
- 唯一来源数；
- 工具特定的时间、行数和成本。

模型一次返回多个工具调用时，应在执行任何工具前原子预留整批预算；无法完整预留则整批拒绝，
避免只执行半批产生不可解释状态。

共享预算不等于共享可变 attempt 状态。candidate fallback 或同模型 retry 分别创建新的
request-local Memory、advisor chain 和工具对话历史；失败 attempt 的局部消息不能进入后续
attempt，但已经发生的模型、工具和检索成本不能退回。

### 9.4 服务端 Tool Provider SPI

工具应由 Spring Bean Provider 声明，注册表负责：

- 模式和 domain 选择；
- 工具名冲突 fail fast；
- 启动期校验工具定义、metadata 和输入 schema；
- 稳定排序；
- 统一包装预算、超时、输出限制和诊断；
- 注入不含凭据的 principal/session 上下文；
- 保留 Collection/ACL 等服务端授权范围；
- 为外部模块提供编译稳定的扩展面。

内置 `searchKnowledge` 和 `searchJsonRecords` 已迁移到同一注册表，内外部工具共享同一安全
边界。

## 10. 后续工作

1. 依赖继续演进时，保持 Boot/Spring AI 兼容矩阵和 provider 能力声明同步。
2. 增加预算耗尽、工具 policy、摘要降级和 provider 成本/延迟的生产观测。
3. 在不开放客户端 tools 透传的前提下，扩充外部 Provider 示例和真实 Tool Calling 覆盖。
4. 只有在延迟、质量和保留策略证据充分后，才评估是否把持久摘要改为默认开启。
5. 修改 Chat、Memory、检索或工具公共契约时，继续执行 PostgreSQL 和隔离真实 LLM 回归。

当前活跃实施方案见
[下一轮高价值功能规划](drafts/NEXT_HIGH_VALUE_FEATURES_PLAN.md)。

## 11. 参考

- [Spring AI Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Spring AI Modular RAG](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)
- [Spring AI 1.1.8 Release](https://github.com/spring-projects/spring-ai/releases/tag/v1.1.8)
- [Spring Boot 3.5.16 Release](https://github.com/spring-projects/spring-boot/releases/tag/v3.5.16)
- [项目架构](architecture-zh-CN.md)
- [配置参考](configuration-zh-CN.md)
- [测试指南](testing-guide-zh-CN.md)
