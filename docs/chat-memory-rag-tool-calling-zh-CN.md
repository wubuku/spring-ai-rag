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
RAG、Tool Calling 和 `ToolContext`，不是自建一套与框架平行的 Chat 循环；但仍缺少
token-aware prompt 预算、持久摘要记忆、逻辑请求级工具/模型预算，以及面向外部扩展的
服务端工具 Provider SPI。因此当前状态应描述为“Spring AI 编排基础已充分复用，生产级
上下文和工具治理仍需补齐”。

## 2. 当前版本与生产执行入口

调研基线为：

- Spring Boot `3.5.3`
- Spring AI `1.1.4`
- Java `21`
- 生产 Chat 主链：
  `RagChatController -> ChatCommandMapper -> ChatExecutionService ->
  ModeAwareChatClientFactory`

Spring AI `1.1.8` 是继续留在 Boot 3.5 / Spring AI 1.1 维护线时可采用的后续补丁版本；
其发布基线已更新到 Boot `3.5.15`，而截至 2026-08-21，Boot 3.5 维护线已有 `3.5.16`。
因此依赖升级应把 Boot 补丁版本与 Spring AI 一起验证，不应把 Spring AI `1.1.8` 直接叠加
在旧 Boot `3.5.3` 上后假定组合已受支持。升级也不能被误当成上下文预算或工具治理已经自动
解决；Spring AI 1.1.8 的 `ToolCallAdvisor` 仍持续递归到模型停止请求工具，没有稳定的内置
总调用次数预算。

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
但当前非流式 `RetryTemplate` 在同一个 request-local attempt 上重复调用。Spring AI
`MessageChatMemoryAdvisor.before()` 会在委托模型前先把当前用户消息加入局部 Memory；
如果第一次调用随后失败，下一次 retry 会继承该局部状态并再次加入用户消息，最终成功提交时
可能把失败尝试留下的重复上下文带入 JDBC Memory。它不是“失败 turn 直接持久化”，但仍是
需要修复的 retry 隔离缺口。正确方向是：每次应用层 retry 都从同一 committed baseline 创建
新的 request-local ChatClient/Memory，失败 attempt 整体丢弃，而模型、工具和检索消耗继续
计入同一个逻辑请求预算。

### 5.1 当前窗口的真实语义

`rag.memory.max-messages=20` 是消息数量限制，不是 token 限制。Spring AI
`MessageWindowChatMemory` 超出上限时淘汰旧的非 System Message；它不会：

- 根据模型 `contextWindow` 计算可用 prompt；
- 为输出 token、RAG 证据或工具 schema 预留空间；
- 对淘汰历史生成摘要；
- 区分一条极长消息和一条极短消息的成本。

项目配置已经保存每个模型的 `context-window` 和 `max-tokens`，但生产 Chat 尚未用这些值
完成候选模型级 prompt 打包，外部 `models.json` 也尚未拒绝显式的零值或负值。后续实现应
区分“缺失”和“非法”：缺失时使用带诊断的保守 fallback，显式非正值则使该 candidate
不可用，不能把非法窗口解释为无限或零成本输出。

### 5.2 TTL 清理与活跃 Chat 的并发边界

`rag_chat_history`、Spring AI JDBC Memory 和会话 lease 是同一会话的三个相互关联状态。
TTL 如果只删除旧 history，再异步清理其他状态，会留下两类风险：模型 Memory 仍保留已经
过期的消息；或者一个 Chat 请求先读取旧 baseline，TTL 随后删除数据，而该请求最后又把旧
baseline 写回。

因此后续 TTL 实现必须先有界发现候选 session，再复用
`rag_chat_session_lease` 申请独立的维护 token。发现有效 Chat lease 时应跳过，不等待、不
抢占；获得维护 lease 后，在短事务中以 token fencing 消费 lease，并同时删除本批过期的
owned history、对应 summary 和按同一 principal/session 规则派生的 JDBC Memory。成功提交
时维护 lease 消失，事务回滚时四者一起回滚。活跃 Chat 的最终提交同样必须先通过自己的
token fencing，因此不能在 TTL 后重新提交清理前读取的旧 baseline。该协议只使用条件写入、
lease 和有界批次，不引入 `FOR UPDATE`、`SKIP LOCKED` 或 advisory lock。

## 6. “上下文压缩”需要区分两件事

项目可以启用 Spring AI `CompressionQueryTransformer`。它的职责是把“前序对话 + 当前
追问”改写成可独立检索的 query。例如：

```text
历史：用户在讨论 BGE-M3
追问：那它的维度呢？
检索 query：BGE-M3 embedding 维度
```

这是**检索查询压缩**，不是**会话上下文压缩**：

- 它不会把旧对话总结后写入长期 Memory；
- 不会减少最终 Chat prompt 中的历史 token；
- 不会建立“摘要 + 最近原始 turns”的分层记忆；
- 只在 `KNOWLEDGE` 的检索前阶段生效。

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

项目通过 `BudgetedToolCallAdvisor` 和 `RetrievalTraceCollector` 增加了：

- 每 attempt 最大工具轮数：`3`
- 每 attempt 最大未缓存检索次数：`3`
- 单次检索结果上限：`10`
- 累计唯一来源上限：`20`
- 单次工具序列化输出上限：`24,000` 字符
- 逻辑请求 deadline：非流式 `120s`，流式 `180s`

这些控制已经可以阻止最直接的无限工具轮询，但还不是完整预算：

1. 一轮模型响应可以包含多个并行 tool call，轮数不等于总调用数。
2. 结果字符上限按单次调用计算，不是逻辑请求的累计上限。
3. retry 和 fallback 可以创建新的 attempt 预算。
4. 没有按工具名称的调用次数、SQL 时间、行数或成本预算。
5. 没有统一统计 query transform、query expansion、summary 和主回答的模型调用数。
6. 当前没有直接断言“第 4 轮在工具执行前被拒绝”的专项测试。
7. 工具事件在 callback 内暂时无法取得真实 `toolCallId`，目前记录为 `null`。

此外，默认内置模型全部声明 `tool-calling: false`。这表示代码具备 AGENT 路径，但默认模型
矩阵不会选择它；只有外部 `models.json` 显式声明并经过真实 provider 验证的模型才应开启。

## 8. 外部 Function Call 与 SQL 检索

当前 `ChatExecutionService` 硬编码注册两个服务端工具，没有公共 Tool Provider SPI。
OpenAI 兼容端点也明确拒绝客户端提交：

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

通用框架应提供工具扩展 SPI 和安全上下文，不应在 core 内预置一个可查询任意业务库的通用
SQL Agent。

## 9. 推荐目标架构

### 9.1 Token-aware Prompt Budget

为每个逻辑请求建立候选模型级预算，使用模型 `contextWindow` 和 `maxTokens`，为以下部分
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
摘要中出现的事实不能自动变成 citation。

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

共享预算不等于共享可变 attempt 状态。candidate fallback 或同模型 retry 必须分别创建新的
request-local Memory、advisor chain 和工具对话历史；失败 attempt 的局部消息不能进入后续
attempt，但它已经发生的模型、工具和检索成本不能退回。

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

内置 `searchKnowledge` 和 `searchJsonRecords` 也应迁移到同一注册表，避免“内置工具”和
“外部工具”形成两套安全机制。

## 10. 推荐实施顺序

1. 一起升级并验证 Spring Boot `3.5.16` 与 Spring AI `1.1.8`，固定现有行为。
2. 先实现逻辑请求级模型/工具预算和专项 call/stream 测试。
3. 增加 Tool Provider SPI，迁移两个内置工具。
4. 增加参数化只读 SQL 扩展示例，不提供任意 SQL。
5. 接入模型元数据和 token-aware prompt 打包。
6. 增加持久摘要 schema、压缩服务、CAS 和 TTL/clear 语义。
7. 补齐 metadata、指标、验证脚本、真实 Tool Calling 和真实摘要冒烟。

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
