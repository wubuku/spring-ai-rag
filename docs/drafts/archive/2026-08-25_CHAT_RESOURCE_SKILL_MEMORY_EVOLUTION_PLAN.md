# Chat 资源知识、运行时 Skill 与记忆演进实施规划

> **状态**：规划完成，已通过连续 `3/3` 实质审查，进入生产代码实施
>
> **规划日期**：2026-08-25
>
> **规划基线**：本地 `main` @ `d70556b0`；`origin/main` @ `97e946d3`；Spring Boot
> `3.5.16`；Spring AI `1.1.8`；Java `21`；Flyway `V1-V48`
>
> **规划工作区**：`/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
>
> **推荐实施分支**：`feat/chat-resource-skill-memory-evolution-20260825`
>
> **推荐实施 worktree**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-chat-resource-skill-memory`
>
> 本文是中文规划文档，不与英文文档成对维护。稳定行为只有在实施完成并验收后，才提升到
> `docs/` 下对应的双语长青文档。

## 1. 执行摘要

本轮规划承接三类明确需求：

1. 应用能够从打包进 Spring Boot JAR 的资源目录，或部署时指定的外部文件系统目录，加载
   一批不经过 embedding 的只读知识；Chat 在需要时可以检索这些知识并回答问题。
2. Chat 的 `AGENT` 模式支持运行时 Skill。Skill 描述能力和操作规程，真正的 HTTP、SQL
   或其他副作用由服务端拥有的受限 Tool 执行；Skill 不能变成权限旁路。
3. 深入比较参考项目与当前项目的会话记忆、持久化和上下文压缩，保留当前项目已经更强的
   principal/session 隔离、事务提交、预算和摘要 CAS，同时补齐工具消息、摘要生命周期、
   清理和可观测性边界。

推荐的总体方案是增加三层可组合能力，而不是重写现有 Chat 内核：

```text
部署配置
  -> 只读资源目录 Catalog
  -> immutable resource snapshot
      -> StaticKnowledge lexical index
      -> RuntimeSkill registry

KNOWLEDGE
  -> RetrievalAugmentationAdvisor
  -> 项目现有检索 + 静态知识检索的受控组合
  -> ChatSource(sourceType=STATIC_KNOWLEDGE)

AGENT
  -> ToolCallAdvisor
  -> Skill catalog/load/reference tools
  -> existing RagChatToolProvider registry
  -> allowlisted HTTP tools / domain-owned SQL tools
  -> shared ChatExecutionBudget

PLAIN
  -> 不自动注入静态知识，不启用 Skill 或工具

Memory
  -> existing JDBC Chat Memory + rag_chat_history + V46 summary CAS
  -> canonical tool-aware projection and observability
  -> no default semantic long-term memory in this batch
```

第一批实施的安全默认如下：

- 静态知识和 Skill 资源均由部署配置决定，模型输入不能指定路径、URL、文件或 JAR。
- 未配置静态知识目录时功能是 no-op；未配置额外 Skill 目录时不改变现有 Chat 行为。
- 静态知识只支持 UTF-8 Markdown/text，启动时建立有界内存索引；修改文件需要重启，
  P0 不做热加载。
- “无需 embedding”不等于把整个目录全文塞进 prompt；必须经过有界、确定性的检索和
  片段预算。
- Skill 只描述能力，不授予权限；`loadSkill` 是 request-local 门禁，HTTP/SQL Tool
  仍由服务端注册、授权、限时和限量。
- HTTP Tool 初期只允许显式配置的只读 GET endpoint；禁止任意 URL、任意 header、任意
  Authorization、任意 redirect 跳转和写操作。
- SQL 不提供 `executeSql(sql)`；只允许领域模块通过 SPI 注册固定形状、参数绑定、只读
  的业务查询 Tool。
- 当前 `MessageWindowChatMemory`、`rag_chat_history`、V46 摘要表、session lease、
  idempotency 和逻辑执行预算继续作为生产基线，不重复建设一套基础 Memory。
- 第一批不升级 Spring AI 到 `2.x`，不直接引入社区 Skills 库，不把 Spring AI 的
  `VectorStoreChatMemoryAdvisor` 作为默认长期记忆。

## 2. 当前基线与问题定义

### 2.1 当前项目的生产 Chat 内核

当前生产入口是：

```text
RagChatController
  -> ChatCommandMapper
  -> ChatExecutionService
  -> ModeAwareChatClientFactory
  -> ChatClient / Spring AI Advisor chain
```

三种模式已经明确区分：

| 模式 | 当前行为 | 本轮处理 |
|---|---|---|
| `PLAIN` | ChatClient + server/request memory，不做知识检索和工具调用 | 保持无自动静态知识、无 Skill |
| `KNOWLEDGE` | `RetrievalAugmentationAdvisor` + `ProjectDocumentRetriever` + 项目混合检索、join、rerank、引用 | 增加静态知识作为独立、可配置的只读检索源 |
| `AGENT` | `ToolCallAdvisor` + `searchKnowledge`，可选 `searchJsonRecords` 和 `RagChatToolProvider` | 增加 Skill 目录/加载工具及 allowlist Tool |

关键事实：

- `ModeAwareChatClientFactory` 为每个候选模型和每个请求创建隔离的 ChatClient、Advisor、
  Memory 和 `AuthorizedRetrievalContext`。
- `KNOWLEDGE` 使用 Spring AI `RetrievalAugmentationAdvisor`，不是 Function Call；
  当前查询改写、多查询扩展、项目混合检索、文档合并、rerank 和 prompt budget 均在
  服务端 Advisor/DocumentRetriever 链内完成。
- `AGENT` 使用 Spring AI `ToolCallAdvisor`；模型通过 `searchKnowledge` 和可选的
  `searchJsonRecords` 按需检索，工具参数不能扩大 Collection、Document、principal
  或 ACL 范围。
- `RagChatToolRegistry` 已将内置工具和外部 `RagChatToolProvider` 统一包装，启动时验证
  tool name、schema、metadata 和 policy；工具执行通过 `ToolContext` 接收服务端构造的
  principal/session/deadline/budget。
- `ChatExecutionBudget`、`BudgetedChatModel`、`BudgetedToolCallingManager`、
  `BudgetedToolCallAdvisor` 和 `RetrievalTraceCollector` 已限制 candidate、retry、
  model call、tool round、tool call、单工具调用数、结果字符/token 和 deadline。
- `MessageWindowChatMemory` 是 request-local 的 Spring AI Memory；已提交业务历史保存在
  `rag_chat_history`；两者在成功 turn 的协调提交边界写入。
- `ConversationSummaryService` 使用 V46 `rag_chat_memory_summary` 做可选、best-effort、
  forward-only cursor + optimistic CAS 的持久摘要；摘要默认关闭，并且被当作不可信历史
  数据，不是 citation evidence 或 instruction。
- session lease、single-flight、TTL 清理、V47 durable turn idempotency 和 V48 stable
  managed principal 已存在。当前项目明确禁止 `FOR UPDATE`、`SKIP LOCKED` 和 advisory
  lock。

稳定事实的近距离入口：

- [Chat、RAG 与工具调用](../../chat-memory-rag-tool-calling-zh-CN.md)
- [项目上下文](../../project-context-zh-CN.md)
- [架构说明](../../architecture-zh-CN.md)
- [配置参考](../../configuration-zh-CN.md)
- [REST API](../../rest-api-zh-CN.md)
- [测试指南](../../testing-guide-zh-CN.md)

### 2.2 当前仍存在的能力缺口

#### 静态知识资源

当前项目的主知识面向 `rag_document`、keyword/vector chunks、Collection ACL 和
embedding profile。虽然已经支持无 embedding 的 keyword chunk 派生能力，但没有一个
“随应用 JAR 或部署目录提供、启动时直接建立只读检索索引、无需写入业务文档表”的资源源。

因此，直接照搬参考项目的 `KnowledgeBaseInitializer` 不能满足本轮目标：它在
`ApplicationReadyEvent` 读取 Markdown 后写入 VectorStore，仍然需要 embedding，并且会把
部署资源生命周期和向量库生命周期耦合在一起。

#### 运行时 Skill

当前项目有 Tool Provider SPI，但还没有通用的运行时 Skill resource catalog、frontmatter
解析、Level 1/2/3 渐进披露、request-local Skill load session 或“加载 Skill 后才允许
使用对应 endpoint”的后端门禁。

仅在 system prompt 中列出 Skill 或让模型自由读取文件都不够：

- 模型不应决定读取哪个绝对路径；
- Skill 文档不能自行授予 API 权限；
- HTTP 工具不能接受任意 URL；
- 外部 Tool 不能绕过 principal、domain、budget 和 timeout。

#### 会话记忆和上下文压缩

参考项目证明了 Spring AI 的基础拼装方式：`MessageChatMemoryAdvisor` +
`MessageWindowChatMemory` + JDBC，以及可选的 `QuestionAnswerAdvisor` 和
`VectorStoreChatMemoryAdvisor`。但参考项目没有当前项目已经具备的 lease、原子提交、
principal 隔离、上下文 token planner、失败 candidate 隔离和摘要 CAS。

当前项目的主要剩余风险不是“缺少一个基础 Advisor”，而是：

- durable `rag_chat_history` 只保存用户可见问答和 citation snapshot，工具调用/工具结果
  在 Spring AI request-local memory 中出现，但摘要输入仍主要从业务 history 重新投影；
- 工具调用产生的 `AssistantMessage` tool calls、`ToolResponseMessage` 配对、异常和
  截断结果需要更明确的持久/摘要/清理契约；Spring AI `1.1.8` JDBC Memory
  不能恢复原生工具消息，因此必须把可恢复的普通消息与有界工具交换投影分开；
- summary enabled 仍是 opt-in，缺少足够的运行时指标和管理可见性来判断延迟、质量和
  degraded 比例；
- `rag_chat_history`、`spring_ai_chat_memory` 和 summary 的删除/恢复语义虽然已经有
  协调清理代码，但需要针对工具消息和 summary cursor 补齐端到端回归；
- 不应因为参考项目有 `VectorStoreChatMemoryAdvisor` 就直接复制一个无 principal 的
  语义记忆层，造成跨用户泄露、重复写入和成本失控。

## 3. 参考项目的可借鉴内容与不照搬内容

参考项目为：
`/Users/yangjiefeng/Documents/wubuku/spring-ai-skills-demo`，当前 `master` 与
`origin/master` 一致，工作区干净；本轮只作为本地对照，不是当前项目的依赖或父仓库。

### 3.1 可借鉴点

| 参考能力 | 可借鉴结论 | 当前项目的落点 |
|---|---|---|
| `SkillResourceCatalog` | 将 classpath、filesystem、普通 JAR、Spring Boot nested JAR 统一为只读 source scope | 新建项目级 Runtime Skill/Static Resource Catalog 抽象，避免模型获得通用文件访问 |
| `SkillRegistry` | 启动扫描、frontmatter 校验、重复名称/links/API index fail-fast | 新建 immutable Skill registry，启动失败或明确 degraded，不在模型调用时临时解释未知资源 |
| Level 1/2/3 Skill | 目录摘要 -> `SKILL.md` -> references，降低初始 prompt 和工具 schema 压力 | 与当前 token-aware planner、max tool schema budget 对接 |
| `SkillLoadSession` | Skill 加载状态必须 request-local，不放在 singleton 全局可变集合 | 通过 `ToolContext` 传递，和 principal/session/budget 同请求隔离 |
| API index 强制门禁 | “先加载 Skill”必须由后端执行校验，不能只写在 prompt | HTTP Tool 根据已加载 Skill 和 server endpoint allowlist 双重校验 |
| `SkillReferenceReader` | 只允许注册 Skill 的 `references/` 相对路径，读取字节和返回字符都有限 | 复用同源、路径规范化、real root 和 bounded read 原则 |
| `KnowledgeBaseInitializer` | 启动时从 classpath/filesystem 发现资源的部署体验简单直接 | 只借鉴资源发现，不借鉴写 VectorStore/embedding 的行为 |
| `AgentService` | Memory/RAG 在 ToolCall 之前构造上下文，工具循环放在链尾 | 当前 `ModeAwareChatClientFactory` 已有类似边界，保持不变 |
| executable JAR smoke | 真实打包产物需要验证 nested resource，而不是只测 exploded classes | 规划中加入 packaged JAR / nested resource acceptance |
| 普通 Agent 与 AG-UI 工具分离 | 后端执行工具和前端确认工具不是同一执行边界 | 当前项目默认只规划 server-owned Tool，不打开 client-defined tool passthrough |

### 3.2 不照搬的内容

- 参考项目的 `KnowledgeBaseInitializer` 会把知识文档写入 VectorStore；本轮静态知识明确
  不做 embedding、不写 `rag_document`，避免与应用业务文档生命周期混淆。
- 参考项目使用简单 `conversationId` 作为 Memory 隔离；当前项目必须保留 stable principal
  + session + lease，不能退回到仅靠客户端 conversation ID 的模型。
- 参考项目的 `VectorStoreChatMemoryAdvisor` 是可选实验能力，不是本轮默认方案。若未来
  做语义长期记忆，必须先定义 principal、tenant、retention、consent、source type 和
  deletion 语义。
- 参考项目的 Skill `httpRequest` 主要围绕自身 PetStore API 和前端确认流程；当前项目
  只能借鉴 allowlist、参数校验和先加载门禁，不能把其 demo API、认证或客户背景带入本项目。
- 参考项目的 `SkillsTool`/社区 `spring-ai-agent-utils` 不是 Spring AI core 内置的
  `SKILL.md` 协议。本轮不因“Spring AI 支持 Skill”而升级依赖或声称已有标准兼容。
- 参考项目早期关于用户画像、跨会话偏好和通用长期记忆的草案，不属于本轮基础会话记忆
  收敛范围。

## 4. 冻结的目标、非目标和完成边界

### 4.1 本轮目标

1. 提供 server-owned、可审计、有界的静态知识资源源：
   - classpath/应用 JAR 内置目录；
   - 外部 filesystem 目录；
   - UTF-8 Markdown/text；
   - 启动构建、原子替换、确定性 lexical retrieval；
   - KNOWLEDGE 自动检索，AGENT 按需检索，PLAIN 不注入；
   - 可稳定引用且不泄露绝对路径。
2. 提供 runtime Skill resource layer：
   - Skill discovery、frontmatter、references、Level 1/2/3；
   - request-local load session；
   - load/reference 后端门禁；
   - 和现有 `RagChatToolRegistry`、预算、principal、domain 接口接合。
3. 提供一个可复用但不任意的 read-only HTTP Tool 形状：
   - endpoint allowlist；
   - 受限 method/path/query/header/schema；
   - SSRF、redirect、DNS/IP、timeout、response size 和结果预算防护；
   - Skill 先加载才可使用对应 endpoint；
   - credentials 由服务端注入，不接受模型传入。
4. 补齐当前 Chat Memory 对工具调用和摘要压缩的契约与测试：
   - 明确 canonical turn、tool call/result 配对；
   - summary 不当作 evidence/instruction；
   - summary、history、JDBC Memory 清理一致；
   - 记录 compaction/degraded/budget/tool outcomes。
5. 保持 Spring AI `1.1.8`、当前三种 Chat mode、现有 API compatibility、无悲观锁规则
   和无密钥入库规则不变。

### 4.2 明确非目标

- 不提供任意文件读取、shell、JavaScript 执行或目录浏览 Tool。
- 不提供 `executeSql(sql)`、任意表名/列名查询、DDL/DML 或自动推导数据库 schema 的 Tool。
- 不把外部客户端提交的 `tools`、`functions`、`tool_choice` 或 tool messages 直接透传
  到服务端执行。
- 不把 Skill frontmatter 的 `allowed-tools`、`model` 等描述字段当作安全授权。
- 不在 P0 做外部目录热加载、文件 watcher、用户上传 Skill、动态下载 Skill JAR。
- 不在本轮把静态知识持久化到业务 `rag_document`、创建新的静态知识数据库表或引入
  embedding。
- 不在本轮实现语义长期记忆、用户画像、跨会话偏好抽取或自动记忆删除策略。
- 不升级 Spring Boot/Spring AI 主版本，不把 Spring AI `2.x` 的 API 假设带入 `1.1.8`。
- 不为静态资源定义跨应用的客户/外部 Client 专用背景；资源可见性只描述为项目通用的
  server-owned source policy。
- 不因为共享 ChatSource/metadata 发生变化就绕过前端构建和 Mock Playwright 验证。

### 4.3 可逆边界

- 静态知识根目录默认为空；关闭配置即可让 `KNOWLEDGE` 回到项目现有检索。
- Skill 根目录和 HTTP endpoint allowlist 默认为空；移除 provider 或关闭 feature flag
  即可回退到现有内置工具。
- summary 仍默认关闭；新增的指标和诊断不影响主 Chat 成功路径。
- 第一批不增加 Flyway migration；如后续要做 durable tool transcript 或语义记忆，另建
  新规划和新 migration，不在本轮混入。
- 所有新增 Tool 都通过现有 registry/policy wrapper；删除 provider Bean 即可回滚，不
  需要重写 `ChatExecutionService`。

## 5. 统一资源模型与数据流

### 5.1 资源 source 抽象

建议新建一个只读的资源抽象，供静态知识和 Skill 共享发现原则，但不共享业务解析：

```text
ConfiguredResourceRoot
  - rootKey: server-owned stable name
  - location: classpath:/file:/jar: configured value
  - kind: STATIC_KNOWLEDGE | SKILL
  - visibility: GLOBAL (P0; other scopes are rejected until a separate ACL design exists)
  - limits: file count / file bytes / total bytes / response tokens

ResourceCatalog
  -> discover()
  -> validate root and canonical scope
  -> normalize source identity
  -> return ResourceEntry(source, relativePath, bytes/text reader)

ImmutableResourceSnapshot
  - generation
  - loadedAt
  - entries
  - digest
  - diagnostics
```

当配置只提供 location 字符串时，服务端按
`SHA-256(kind + normalizedLocation)` 的固定短前缀生成 `rootKey`；规范化只在服务端
内部使用，绝不把原始绝对路径放入 `rootKey`、`sourceLabel`、citation、history 或日志。
同一 kind/location 在不同启动顺序下必须得到相同 rootKey；source label 使用
`static-knowledge/<rootKey>` 或 `skill/<rootKey>`，相对文件名另行 bounded 编码。

推荐不要把两个领域硬塞进同一个 `Document` 或 `Skill` 类：

- 静态知识的输出是用于 `DocumentRetriever` 的 `Document`，带 `sourceType` 和 chunk
  metadata；
- Skill 的输出是 `SkillDescriptor`、`SkillBody`、`ReferenceEntry` 和 API capability
  index；
- 两者可以共享“资源根扫描/同源解析/路径安全”底层，但必须有独立 parser、大小预算和
  对外权限。

快照使用 immutable collection，通过单次 volatile/atomic swap 发布。逻辑 Chat 开始时捕获
一个快照，并把同一个 generation 传给所有 candidate/retry attempt，不在同一个 Chat 中
跨 generation 读取，避免目录切换造成混合结果。P0 只在启动时构建；未来热刷新必须
复用同一原子快照协议。静态索引和 Skill registry 可以共享该只读快照，但
`SkillLoadSession` 必须按 attempt 创建；失败 candidate 的 loaded Skill/reference 状态
不得进入成功 candidate。

### 5.2 统一 Chat 数据流

```text
ChatCommand
  -> principal/session/mode/deadline/budget
  -> capture one immutable resource snapshot for the logical Chat
  -> create attempt-local SkillLoadSession (AGENT only; failed attempts discard it)
  -> create request-local Memory
  -> planner reserves:
       history + summary + static evidence + tool schemas + output
  -> mode:
       PLAIN:
         memory -> model
       KNOWLEDGE:
         query transform/expand (optional)
         -> composite retriever
         -> project documents + static knowledge
         -> join/rerank/prompt budget
         -> model
       AGENT:
         skill catalog/load/reference tools
         + server-owned search/tool callbacks
         -> bounded ToolCallAdvisor loop
         -> model
  -> citation/trace/metadata
  -> durable history + Spring AI Memory commit
  -> optional best-effort summary compaction
```

注意：静态知识检索和 Skill 文档读取是两种不同输入：

- 静态知识是“事实内容”，可在 `KNOWLEDGE` 中作为检索证据；
- Skill 是“如何操作服务”的不可信操作说明，默认只在 `AGENT` 中按需加载；
- Skill 正文不能自动成为 citation，HTTP/SQL 结果也不能冒充 document citation。

## 6. 主线 A：无需 embedding 的静态知识资源

### 6.1 资源目录和配置契约

本轮冻结配置前缀为：

```yaml
rag:
  chat:
    static-knowledge:
      enabled: false
      locations: []
      file-extensions: [md, markdown, txt]
      max-files-per-root: 200
      max-file-bytes: 262144
      max-total-bytes: 10485760
      chunk-max-characters: 4000
      chunk-overlap-characters: 200
      retrieval-max-results: 5
      retrieval-max-result-characters: 24000
      fail-fast: true
      visibility: GLOBAL
```

环境变量使用 Spring Boot 对 `rag.chat.*` 的 canonical 映射；以下名称在本轮冻结，
locations 使用逗号分隔的配置值：

```text
RAG_CHAT_STATIC_KNOWLEDGE_ENABLED
RAG_CHAT_STATIC_KNOWLEDGE_LOCATIONS
RAG_CHAT_STATIC_KNOWLEDGE_MAX_FILES_PER_ROOT
RAG_CHAT_STATIC_KNOWLEDGE_MAX_FILE_BYTES
RAG_CHAT_STATIC_KNOWLEDGE_MAX_TOTAL_BYTES
```

`locations` 每一项是部署配置，不得从 `ChatRequest.metadata` 或模型 tool arguments
取得。`locations` 允许的资源形式为：

```text
classpath:knowledge/
classpath*:knowledge/
file:/opt/company-policies/
jar:file:/opt/company-knowledge.jar!/knowledge/
```

Runtime Skill 使用同一资源发现边界，但配置独立、默认关闭：

```yaml
rag:
  chat:
    skills:
      enabled: false
      locations: []
      max-skills: 50
      max-skill-body-bytes: 131072
      max-reference-bytes: 262144
      max-catalog-characters: 24000
      max-loads-per-request: 4
      max-reference-reads-per-request: 8
      fail-fast: true
```

对应的基础环境变量名称冻结为
`RAG_CHAT_SKILLS_ENABLED`、`RAG_CHAT_SKILLS_LOCATIONS`、
`RAG_CHAT_SKILLS_MAX_SKILLS`、`RAG_CHAT_SKILLS_MAX_SKILL_BODY_BYTES` 和
`RAG_CHAT_SKILLS_MAX_REFERENCE_BYTES`；复杂 endpoint 列表使用 YAML 或外部配置文件，
不通过模型输入或未结构化的单个环境变量表达。

实现要求：

- 对 directory root 递归发现允许的扩展名；支持 Spring Boot executable JAR 的 nested
  resource；
- 不把 `src/main/resources`、绝对宿主路径或 JAR 内部路径暴露给用户；
- 每个 root 生成稳定 `rootKey` 和不含敏感绝对路径的 source label；
- 同一 canonical source 去重，排序按 `rootKey + relativePath`；
- 对 filesystem root 解析 real path，拒绝 root 外符号链接、`..`、NUL、反斜杠和编码
  escape；
- 对 JAR 只读 entry prefix，拒绝 `../` 和跨 entry prefix；
- UTF-8 解码失败、超大文件、超过 root/总预算、非法扩展名按 `fail-fast` 处理；
- 空配置是合法 no-op；显式配置的 root 无法读取时默认 fail-fast，避免应用健康但回答
  错误知识；
- 资源内容只在启动加载，不在 Chat 请求期间打开任意 filesystem stream。

### 6.2 文本解析和确定性索引

P0 不使用 embedding，也不把全文直接放到 system prompt。索引构建建议如下：

1. 读取文件文本并规范化换行，拒绝非 UTF-8 或超出字节上限的内容。
2. 识别 Markdown 标题，保留当前标题路径；普通 text 使用文件名作为标题。
3. 按标题边界和字符上限切分 chunk，尽量在段落/列表边界断开；单个超长段落按有界
   字符窗口切分，使用固定 overlap。
4. 对每个 chunk 计算：
   - `contentDigest`；
   - `chunkIdentity = hash(rootKey + relativePath + chunkIndex + contentDigest)`；
   - `titlePath`、`relativePath`、`sourceLabel`；
   - normalized lexical terms；
   - bounded text length/token estimate。
5. 构建不可变 inverted index；索引 tokenization 复用项目现有 lexical normalization
   能力并补充 CJK 字符/短语处理，不引入另一个远程搜索服务。
6. 查询时使用固定、可解释的得分组合：
   - exact phrase/title match 优先；
   - normalized token coverage；
   - CJK character/bi-gram overlap；
   - relative path/title tie-break；
   - stable `chunkIdentity` tie-break。
7. 返回最多 `retrieval-max-results` 个 `Document`，每项不得超过 result character 和
   context token budget；无命中返回空列表，不把整个索引注入 prompt。

检索算法的目标是“可预测的补充知识源”，不是替代项目的向量/全文生产检索。P0 只要求：

- exact policy term 能稳定命中；
- 中英文、数字、型号和条款编号不被丢失；
- 同 query 多次结果顺序稳定；
- 结果和 chunk identity 不因 Resource resolver 返回顺序变化；
- 不额外调用 embedding、rerank provider 或 Chat model。

静态知识在当前项目的 rerank/post-process 链中必须保持来源隔离：组合 retriever
返回的 `STATIC_KNOWLEDGE` 文档不得送入项目的外部 rerank provider，也不得经过会把
来源重写为 `DOCUMENT` 的通用 mapper。实现时由 `ProjectRerankPostProcessor` 按
`sourceType` 分区：只对业务文档分区执行现有 rerank，静态分区保留确定性 lexical
排序和有限 score；随后在同一 `PromptBudgetDocumentPostProcessor` 中按共享
`max-rag-context-tokens` 合并裁剪。该分区规则必须覆盖静态文档的 `sourceType`、
稳定 id、citation 和 trace，不能只在最终 DTO 映射阶段补字段。

如果后续质量数据表明静态资料需要复杂全文查询，另建“静态资源 keyword index 持久化”
规划；不能在实施中临时把它写入 `rag_document` 以规避索引设计。

### 6.3 接入三种 Chat mode

#### `KNOWLEDGE`

本轮冻结在现有 `ProjectDocumentRetriever` 旁新增显式的 static source delegate，
由组合 `DocumentRetriever` 统一进入当前 RAG Advisor：

```text
authorized project retrieval
  -> bounded static knowledge retrieval
  -> source type tagging
  -> existing ProjectDocumentJoiner
  -> source-partitioned rerank / prompt budget / citation mapping
```

本轮冻结为单一 `RetrievalAugmentationAdvisor`：新增一个组合
`CompositeChatDocumentRetriever`，在一次 query 中分别调用现有授权
`ProjectDocumentRetriever` 和静态知识 delegate，合并后交给现有
`ProjectDocumentJoiner`、分区 rerank 和 prompt budget post-processors。不得通过两个
Advisor 各自注入证据，也不把静态知识绕过现有 Chat 的 context budget；这样能保持
Spring AI context 语义、现有授权检索链和 citation 收集只有一个入口。Slice 0 仍需用
fixture 验证该组合 retriever 返回的多个来源不会破坏当前 advisor context。一次
`Query` 只消耗一次逻辑 retrieval budget；组合内部的 project/static 两个 delegate
不得各自重复计数或绕过 `RetrievalTraceCollector` 的上限，静态分区需在同一 trace 中
以独立 source type 记录结果和裁剪。

静态 source 默认只在 `enabled=true` 且快照有命中时加入；不会改变项目 Collection/Document
ACL。由于 P0 只提供 `GLOBAL` visibility，必须在配置上显式声明“该资源对所有已通过 Chat
授权的 principal 可见”；未来的 principal/tenant visibility 需要独立 ACL 设计，不能仅
靠 metadata filter。

#### `AGENT`

本轮冻结：静态知识启用且快照成功时，`AGENT` 必须注册单独的
`searchStaticKnowledge` server-owned Tool，而不是默默改变 `searchKnowledge` 的含义：

- Tool schema 只接受 `query` 和 bounded `maxResults`；
- rootKey、filesystem path、source URL、principal 和 visibility 由服务端决定；
- 输出带 `sourceType=STATIC_KNOWLEDGE`、citationId、titlePath、sourceLabel、snippet；
- 复用 `AuthorizedRetrievalContext`、`RetrievalTraceCollector` 和 shared budget；
- 每 request、每 tool name、结果字符/token 和 deadline 均由 registry/policy wrapper
  限制；
- Tool 结果中的文本按不可信外部数据处理，不能变成系统指令。

`searchKnowledge` 是否在未来合并静态 source，留作兼容性评估项；本规划的默认决定是
不合并，以保持“项目 Collection 检索”和“部署静态资源检索”两种可观察、可授权的来源
语义。

#### `PLAIN`

不自动检索静态知识、不注册 static search tool、不注入 Skill catalog。用户若显式选择
`PLAIN`，即使静态目录已配置，也不应隐式增加检索成本。

### 6.4 Citation、历史和安全契约

每个静态 `ChatSource` 至少包含：

```text
sourceType = STATIC_KNOWLEDGE
documentId = stable static chunk identity
title = titlePath or filename
chunkText = bounded snippet
score = finite deterministic score
metadata = rootKey, relativePath, contentDigest, chunkIndex
```

对外响应和 `rag_chat_history` 不写入：

- 外部 root 的绝对路径；
- access token、credential ref、环境变量；
- JAR 本地文件名或宿主机用户名；
- 未经 bounded truncation 的全文。

静态 source 的 `documentId` 不是业务 `rag_document.id`，不能让现有 document controller
或 Document ACL 误把它当成数据库文档。`ChatAuthorizationService`、export 和 citation
validator 必须显式区分 `sourceType=STATIC_KNOWLEDGE`；若当前逻辑只支持数据库 document
ID，实施必须先扩展其稳定来源分支，再开放该 source 进入 Chat response。
`related_document_ids` 继续只保存正整数业务文档 ID；静态 source 只进入
`sources` 快照、citation 和受限 metadata，不得依赖“解析非数字后被过滤”的偶然行为。

## 7. 主线 B：运行时 Skill 与受限 Tool

### 7.1 Skill 资源格式

推荐最小布局：

```text
skills/
  weather/
    SKILL.md
    references/
      api.md
      schemas/
        forecast-response.md
```

`SKILL.md` 使用 YAML frontmatter + Markdown body：

```yaml
---
name: weather
description: 查询指定城市的当前天气和预报
version: 1.0
links: []
capabilities:
  - weather.read
---
```

P0 强制：

- `name` 只允许小写字母、数字和连字符，且与目录名一致；
- `description` 必填且有长度上限；
- `links` 必须指向已注册、非自身、无重复的 Skill；
- capability 名称只能引用 server-owned registry 中存在的 capability；
- reference 只能是所属 Skill 下的相对路径；
- 重复 name、悬空 link、非法 frontmatter、API/capability 冲突启动 fail-fast；
- Skill source 只读，不能由模型创建或修改。

Level 1/2/3：

```text
Level 1: AGENT-only RuntimeSkillCatalogAdvisor 注入 bounded name + description + capability summary
Level 2: loadSkill(name) 返回当前 Skill 的 bounded SKILL.md body
Level 3: readSkillReference(name, relativePath) 返回 references 下的 bounded 内容
```

目标项目不直接复用参考项目的 `SkillsAdvisor` 类；实施时新增
`RuntimeSkillCatalogAdvisor`（或同职责的项目内 Advisor），仅在 `AGENT` 且 Skill
快照启用时注入有界的 Level 1 摘要，并接入现有 token-aware context planner。
Level 1 不应把所有 Skill 正文放进 prompt。`loadSkill` 和 `readSkillReference` 都是
server-owned Tool，使用 attempt-local session 记录已加载 Skill、已读 references、字符/
token budget 和数量上限。目录摘要和 Skill 正文都必须放在明确的“不可信 Skill 数据”
边界内，不能改变 system/developer policy。P0 不增加 `listSkills` Tool，避免为目录发现额外消耗工具循环；
如未来需要动态目录浏览，再单独评估其 schema 和预算。

### 7.2 “先加载 Skill”必须是后端门禁

建议新增：

```text
SkillResourceCatalog
  -> SkillRegistry
  -> SkillLoadSession (attempt-local)
  -> SkillToolsProvider
```

`SkillToolsProvider` 通过现有 `RagChatToolProvider` 注册：

- `loadSkill`
- `readSkillReference`

真正的业务 Tool 在执行前必须同时通过：

1. endpoint/capability 属于 server-owned registry；
2. 当前 principal/domain 有权使用；
3. 当前 attempt 的 `SkillLoadSession` 已加载声明该能力的 Skill；
4. 当前 `ChatExecutionBudget` 和该 Tool policy 允许执行；
5. 输入 schema、timeout、结果大小和安全策略验证通过。

缺少 Skill session、Skill 未加载或引用不匹配时，Tool 返回 bounded error，并保证不产生
下游请求。Prompt 中的“必须先加载”只负责帮助模型，后端 gate 才是正确性边界。

### 7.3 Allowlist HTTP Tool

天气查询是 HTTP Tool 的一个示例，但本项目不能实现“模型给 URL，服务端直接请求”的通用
代理。推荐配置形状：

```yaml
rag:
  chat:
    http-tools:
      enabled: false
      endpoints:
        - capability: weather.read
          skill: weather
          base-url: https://weather.example.com
          path-template: /v1/forecast
          methods: [GET]
          query-parameters: [city, units]
          response-content-types: [application/json]
          timeout-ms: 5000
          max-response-bytes: 65536
          credential-ref: WEATHER_API_TOKEN
```

实际 binder 可以采用更符合项目现有配置风格的命名，但实施前必须冻结以下语义：

- `base-url`、scheme、host、port、path prefix 由部署配置固定；
- 模型只传 schema 中声明的业务参数，不传完整 URL、代理、DNS、Authorization、Cookie；
- endpoint 与 Skill capability 一一绑定，未加载对应 Skill 不允许调用；
- 默认只读 GET/HEAD，写 method 在本轮 disabled；
- URL path/query 使用结构化 URI builder，拒绝 `..`、反斜杠、control char、encoded
  slash/dot、fragment 和内嵌 query；
- 禁止 localhost、loopback、link-local、RFC1918、云 metadata service 和未配置端口；
- DNS 解析后的 IP 重新检查；防止 DNS rebinding；
- 默认禁止 redirect；如未来允许，必须对每一跳重新执行 scheme/host/IP/allowlist
  检查，不能跟随到任意 host；
- 允许 header 采用显式白名单，Authorization/Cookie/Proxy-Authorization 永不接受模型
  输入；
- credentials 由服务端 secret/environment resolver 注入，不写入日志、Skill body、
  ChatSource、history 或 Tool result；
- response content type、byte size、解码字符数、JSON depth/array size 和结果 token
  均有上限；
- timeout、连接池、并发、per request call count 和总执行 deadline 复用现有 registry
  policy 和 `ChatExecutionBudget`；
- HTTP status、timeout、invalid content 和 policy rejection 都返回可识别但不泄露内部
  配置的 bounded error。

这套 Tool 的核心目标是让 Skill 描述“怎样调用天气服务”，而不是让 Skill 或模型获得
任意网络能力。

### 7.4 SQL Tool 扩展边界

当前项目已有 `searchJsonRecords`，并且 `RagChatToolProvider` 已能承接只读业务查询。
下一步如需要直接 SQL 检索，推荐只在具体领域模块中注册如下形状：

```java
lookupAssetStatus(assetId, includeHistory)
searchOrders(status, createdAfter, maxResults)
lookupInventory(sku, warehouse, maxResults)
```

实施约束：

- Tool schema 只暴露业务参数，不暴露 SQL、表、schema、列、order by、limit 任意值或
  credentials；
- SQL 固定在代码或 approved query template 中，所有值使用参数绑定；
- principal/tenant/owner 条件由 `RagChatToolRequestContext` 映射后服务端注入；
- 使用只读数据库角色或只读事务，单语句，明确 schema/view/field allowlist；
- PostgreSQL statement timeout、行数、序列化字节、单次/总 Tool 结果都有上限；
- 不使用显式悲观锁、`SKIP LOCKED` 或 advisory lock；
- 记录 tool name、脱敏参数摘要、耗时、行数、预算结果和错误类别；
- 关系数据输出若不是文档证据，使用 `sourceType=STRUCTURED_TOOL_RESULT` 或 tool
  event，不冒充 `STATIC_KNOWLEDGE`/document citation；
- 领域模块自行提供 schema-specific integration test；core 不提供通用任意 SQL Tool。

SQL Tool 不是本轮静态知识 P0 的阻断依赖。先完成 SPI、ToolContext、policy 和测试
契约；具体业务查询作为独立 provider 示例或后续领域模块切片实施。

### 7.5 Skill/Tool 预算

所有 Skill 和 HTTP Tool 必须继续受现有预算约束，并增加以下 request-local 计数：

- loaded Skill 数；
- `loadSkill` 调用数；
- reference read 数和总字符/token；
- HTTP endpoint 调用总数、每 capability 调用数；
- 单次 response bytes/chars/tokens；
- HTTP 总 bytes、总耗时和共享 deadline；
- 工具循环 `maxToolRounds`、`maxToolCalls`、per-tool limit；
- candidate/fallback/retry 的模型调用预算。

不能只限制“模型请求工具次数”，却让 Skill reference 或 HTTP response 无限增长。工具循环
有界的证据必须同时来自 Mock integration test、预算单测和真实 provider 的有界 smoke。

## 8. 主线 C：记忆、持久化和上下文压缩演进

### 8.1 取长补短结论

参考项目的基础链路：

```text
MessageChatMemoryAdvisor
  -> MessageWindowChatMemory
  -> JdbcChatMemoryRepository
```

可选 `VectorStoreChatMemoryAdvisor` 能做相似历史检索，但它不是完整的长期记忆治理系统。
它没有自动解决 principal isolation、用户画像可信度、retention、删除、重复写入、工具
消息一致性或成本预算。

当前项目已比参考项目强的部分应继续保留：

- stable principal/session 隔离；
- request-local Memory；
- 失败 candidate/retry 不污染成功 Memory；
- history + JDBC Memory 协调提交；
- lease/single-flight/TTL；
- token-aware prompt planner；
- summary cursor/version CAS；
- summary failure/degraded 不阻断主 Chat；
- model/tool/summary 共享 budget。

因此本轮不新增第二套基础 Memory，而是建立一个“tool-aware memory contract”。

### 8.2 Canonical turn 和工具消息契约

对一次成功的 `AGENT` turn，request-local Memory 中可能存在：

```text
UserMessage
AssistantMessage(tool calls)
ToolResponseMessage(tool results)
AssistantMessage(final answer)
```

规划中的 canonical contract：

- 只有成功且已提交的 turn 进入 durable Memory；
- 每一个 tool call 必须能按 tool call id 与对应 tool result 配对；
- 工具结果被 `BudgetedToolCallingManager` 截断后，后续 prompt、trace、summary 和诊断
  必须看到同一 bounded representation；
- request-local memory window 在一次模型执行中保留完整工具交换；durable JDBC commit
  只保存其 codec 能恢复的用户/助手消息，不能持久化会失去 call/result 内容的原生工具
  消息；
- 中途失败 candidate 的未提交 tool messages 不得写入成功 candidate 的 Memory；
- durable `rag_chat_history` 继续保存用户可见问答、sources、mode/model/usage 等业务
  审计字段；本轮不为每个 tool message 增加新的永久明文 transcript 表；
- 需要在 metadata/trace 中保留 bounded tool transcript，不能把未受限的敏感 tool
  result 复制到 history；
- 成功 turn 的 `rag_chat_history.metadata` 只增加有界的 `toolTranscript` 投影
  （工具名、截断参数和截断结果），
  `ConversationSummaryService` 只能消费该投影，不能从数据库或日志回读完整工具 payload；
- summary 输入应以 committed business history 为主，并明确说明工具调用对用户可见
 结论的影响；未经明确需要，不把 credentials、内部错误堆栈和整个工具 payload 写入
  summary。

实施时必须通过测试确认 Spring AI JDBC repository 的真实序列化边界。当前 `1.1.8`
探针已证明它只保存 message text/type，不能恢复 tool call id、函数名、参数或工具结果。
因此本轮冻结 fallback：JDBC Memory 只保存可恢复的用户/助手消息；只有完整且配对的
工具交换才以 bounded `toolTranscript` 写入业务 history metadata，并以明确的
`untrusted historical tool data` 形式参与摘要输入。不得把原生工具消息伪装成可恢复的
JDBC Memory。

### 8.3 Summary/compaction 运行契约

保持现有 V46 表和 CAS 语义：

- summary 是 untrusted historical data；
- prompt 中有明确 delimiters；
- summary 不能作为 instruction 或 citation；
- cursor 只向前推进；
- 只压缩已提交、非 protected recent turns；
- model call 不在持有数据库事务时执行；
- 超时、provider error、budget exhausted、output exceeded、CAS conflict 都降级，不
  阻断成功 Chat；
- 清理/删除 session 时 summary 与 history/JDBC Memory 一起按既有 lease 协议处理；
- summary enabled 默认仍为 false，先增加指标和验证再考虑 profile 默认值。

本轮新增的可观测性建议：

```text
rag.chat.memory.compaction.attempted
rag.chat.memory.compaction.updated
rag.chat.memory.compaction.degraded{reason}
rag.chat.memory.compaction.source_tokens
rag.chat.memory.compaction.output_tokens
rag.chat.memory.compaction.duration
rag.chat.memory.tool_projection{mode,status}
rag.chat.memory.cleanup{history,summary,jdbc_memory}
```

指标标签不得包含完整 principal、session、message、tool input 或内容；只允许经过低基数
规范化的 mode/model/result/reason 标签。结构化 Chat metadata 也只保留 bounded counts、
status 和 model ref，不记录密钥或全文。

### 8.4 不实施的语义长期记忆

本轮不启用 `VectorStoreChatMemoryAdvisor` 或用户画像抽取。未来如果决定做 semantic
memory，实施前必须单独冻结：

- memory source type 与 citation/authority 语义；
- stable principal/tenant/session filters；
- retention、删除、export、consent 和 backfill；
- embedding profile 和 provider 成本；
- summary 与 semantic memory 的重复/冲突策略；
- Tool/HTTP/SQL 结果是否允许进入 semantic memory；
- PostgreSQL migration、repair、测试数据库和真实 LLM 预算。

没有这些契约，向量化历史会把“相关”误当成“可信”，同时扩大成本和隐私风险。

## 9. 配置、API 和模块影响范围

### 9.1 配置变更

建议新增配置类，不直接在业务类中散落 `@Value`：

```text
RagChatProperties.StaticKnowledgeProperties
RagChatProperties.SkillProperties
RagChatProperties.HttpToolProperties
```

配置 validator 必须校验：

- root/location 非空时 scheme 合法；
- file/total/count/response/description/token 上限为正且满足交叉约束；
- visibility、method、content type、redirect policy 在 allowlist；
- endpoint capability、skill name、path template 唯一；
- HTTP timeout 小于逻辑 Chat deadline；
- per-tool result 上限不超过全局 tool result 上限；
- 未配置 credential ref 时不能声明需要认证的 endpoint；
- 不接受 `http://` 内网/localhost 等默认不安全地址。

配置文档在实施完成后同步：

- `docs/configuration-zh-CN.md`
- `docs/configuration.md`
- `docs/chat-memory-rag-tool-calling-zh-CN.md`
- `docs/chat-memory-rag-tool-calling.md`

### 9.2 API/响应契约

P0 不新增“模型自由浏览资源”的 HTTP endpoint。若需要运维观察，建议提供只读、低敏的
admin/actuator 诊断信息：

- resource generation、rootKey、entry count、bytes、digest、last load status；
- Skill name、description、version、capability count；
- 不返回绝对 path、Skill reference 正文、credential、完整文件内容。

是否公开 HTTP route 应在实施时根据当前 admin API 认证边界单独决定；未确认前优先使用
日志、metrics 和测试 helper，不新建一个可能绕过 API Key ACL 的公开 route。

`ChatSource.sourceType`、metadata 和 Chat history source snapshot 如果发生结构扩展，
必须同时核对：

- `ChatResponse`/`ChatHistoryResponse`；
- citation validator；
- export/Markdown rendering；
- OpenAI compatibility response；
- WebUI source rendering 和 TypeScript 类型；
- mock Playwright 网络断言。

### 9.3 生产代码文件地图

文件名是实施入口，不是允许在不了解代码后直接机械编辑的清单。

新增或主要修改区域建议为：

```text
spring-ai-rag-api/
  .../service/RagChatToolProvider.java
  .../service/RagChatToolPolicy.java
  .../dto/ChatSource.java                         # 如需 source metadata 扩展

spring-ai-rag-core/
  .../config/RagChatProperties.java               # static/skill/http 配置
  .../config/RagChatPropertiesValidator.java
  .../resource/ResourceCatalog.java               # 共享只读发现边界
  .../resource/ResourceSnapshot.java
  .../staticknowledge/StaticKnowledgeCatalog.java
  .../staticknowledge/StaticKnowledgeIndex.java
  .../staticknowledge/StaticKnowledgeDocumentRetriever.java
  .../staticknowledge/StaticKnowledgeTool.java
  .../rag/CompositeChatDocumentRetriever.java # 单一 RetrievalAugmentationAdvisor 的组合入口
  .../rag/ProjectRerankPostProcessor.java       # 按 sourceType 分区，静态知识不走外部 rerank
  .../rag/RetrievalDocumentMapper.java          # 保留 STATIC_KNOWLEDGE source metadata
  .../skill/SkillResourceCatalog.java
  .../skill/SkillRegistry.java
  .../skill/SkillLoadSession.java
  .../skill/SkillReferenceReader.java
  .../skill/SkillToolsProvider.java
  .../skill/RuntimeSkillCatalogAdvisor.java
  .../tool/AllowlistedHttpToolProvider.java
  .../chat/ModeAwareChatClientFactory.java        # mode wiring/snapshot/context
  .../chat/ConversationPromptPlanner.java          # static/tool evidence budget
  .../chat/ConversationSummaryService.java         # tool-aware contract/metrics
  .../chat/RagChatToolRegistry.java                # provider registration/policy
  .../chat/RetrievalTraceCollector.java            # source/tool outcome trace
  .../chat/ChatExecutionService.java               # source merge/result metadata
  .../service/ChatHistoryCleanupService.java       # summary/memory/tool contract
```

测试区域：

```text
spring-ai-rag-core/src/test/java/.../resource/
spring-ai-rag-core/src/test/java/.../staticknowledge/
spring-ai-rag-core/src/test/java/.../skill/
spring-ai-rag-core/src/test/java/.../tool/
spring-ai-rag-core/src/test/java/.../chat/
spring-ai-rag-core/src/test/java/.../integration/
```

可执行 JAR 验收使用现有 `demos/demo-basic-rag`，而不是把
`spring-ai-rag-core` 当作 fat JAR：core 明确是供其他模块依赖的库模块。需要在 demo
中增加与客户无关的中性验证 fixture，例如：

```text
demos/demo-basic-rag/src/main/resources/verification/chat-resources/knowledge/
demos/demo-basic-rag/src/main/resources/verification/chat-resources/skills/
```

该 fixture 只用于证明 classpath 与 Spring Boot nested-JAR 发现边界，不代表生产默认
配置；打包 smoke 通过启动日志中的低敏 snapshot generation/entry-count/health 状态
确认资源已发现，不能把绝对路径或文件正文写入日志。

不要为静态知识建立新的 Flyway 表，除非实施中发现内存索引无法满足已冻结的边界；那将
触发重新规划，不属于隐含变更。

## 10. 分期实施顺序

### Slice 0：契约探针和测试夹具

先不接入生产 Chat：

1. 核对 Spring AI `1.1.8` 的 `MessageWindowChatMemory`、JDBC repository、ToolCall
   message round-trip、`RetrievalAugmentationAdvisor` 的 context 语义。
2. 建立 classpath、exploded filesystem、普通 JAR、Spring Boot nested JAR 的资源 fixture；
   nested-JAR fixture 固定放在 `demos/demo-basic-rag` 的中性验证资源目录，并用该 demo
   的可执行产物做真实装载验证。
3. 建立静态 Markdown/text、Skill、reference、坏 frontmatter、符号链接越界、超大文件、
   重名 source 和 HTTP allowlist fixture。
4. 固定 limits、stable identity、sourceType、低基数诊断和错误类型。
5. 一次性写出后续 integration/E2E 的验收测试，不在 review 阶段逐项补洞。

完成标准：测试夹具能在无真实 Embedding/LLM/数据库时证明资源发现和安全边界；Spring AI
message round-trip 的结论已记录在进度文档，不以猜测实施。

### Slice A：静态知识 P0

1. 实现配置、validator、resource catalog、bounded loader 和 immutable snapshot。
2. 实现标题/段落切分、确定性 lexical index、CJK/Latin/数字边界和稳定排序。
3. 接入 `KNOWLEDGE` 的组合 retriever/advisor，确保已有 project retrieval、ACL、
   rerank、join、prompt budget 和 trace 不退化。
4. 在静态知识启用且快照成功时，必定注册 `searchStaticKnowledge`；复用现有
   registry/budget/trace，并为静态知识未命中、关闭和快照失败分别提供确定性结果。
5. 扩展 source mapping、citation validation、history snapshot 和 WebUI 类型/渲染。
6. 为 `PLAIN` 加负向断言，确认不会读取静态索引。

### Slice B：Runtime Skill P0/P1

1. 实现 Skill resource catalog、frontmatter parser、links/capability index 和 startup
   validation。
2. 实现 AGENT-only `RuntimeSkillCatalogAdvisor`，将 Level 1 摘要纳入现有上下文预算。
3. 实现 request-local `SkillLoadSession`、`loadSkill`、`readSkillReference`。
4. 将 Skill Tools 注册到现有 `RagChatToolRegistry`，纳入 schema/result/token/call
   budgets。
5. 为 Skill API/capability 做后端 gate：未加载 Skill、错误 Skill、非法 reference、
   缺少 server context 均不得访问资源或下游服务。
6. 使用测试专用的 mock transport/server fixture 验证 Skill-to-tool contract；不得在
   生产配置中开放 loopback/private IP，也不把第三方服务写入 core 默认配置。

### Slice C：Allowlist HTTP Tool P1

1. 实现 endpoint config binder、URI/path/query/header schema 校验和 credential resolver。
2. 实现 SSRF/IP/redirect/timeout/body/response content safety。
3. 实现 HTTP result bounded projection、脱敏日志和 policy/budget accounting。
4. 用 `weather` fixture 完成：
   `loadSkill -> allowlisted HTTP Tool -> bounded result -> final answer` 的 Mock
   integration test。测试通过注入 mock transport 或测试替身隔离生产 DNS/IP/redirect
   policy，不通过放宽生产 SSRF 规则来访问 loopback。
5. 明确 GET 自动执行、写操作 disabled；不要把前端确认协议顺带引入当前 server-owned
   Tool。

### Slice D：Memory/Compaction 收敛 P0/P1

1. 建立 Spring AI JDBC codec probe，确认当前版本不能恢复原生 tool call/result；
   随后验证成功/失败 candidate 使用 JDBC-compatible projection，且只有完整配对工具
   交换进入 bounded history metadata。
2. 建立 summary 对工具结果、异常、截断结果和 user-visible answer 的投影规则。
3. 增加 summary enabled/disabled/degraded/CAS/timeout/budget metrics 和低基数 metadata。
4. 增加 summary/history/JDBC Memory/TTL cleanup 的 PostgreSQL integration matrix。
5. 保持 V46/V47 迁移不改写；如果必须修改 schema，暂停实施并另建 migration plan。
6. 文档中明确 Spring AI 基础能力与项目自有增强的边界，不把 summary 宣称为 Spring AI
   内置长期记忆。

### Slice E：SQL provider 示例与后续候选

1. 先提供一个测试/示例领域 provider，固定 SQL、参数绑定、principal predicate、
   statement timeout、row/byte limit。
2. 使用现有 `RagChatToolProvider` 和 policy wrapper，不改变 core 的任意 SQL 边界。
3. 验收 `allowed -> denied -> timeout -> over-limit -> principal isolation`。
4. 具体真实业务 schema 未知时不创建生产表或伪造业务 API；将 provider 保留为可移植
   extension example。

推荐实施顺序为 `Slice 0 -> A -> B -> C -> D`；`E` 可与具体领域需求一起单独交付。

## 11. 一次性验收矩阵

### 11.1 资源和静态检索

| 场景 | 证据 |
|---|---|
| classpath exploded 目录 | catalog integration test，断言 entry、排序、digest |
| 普通 dependency JAR | JAR fixture test，断言同源 references |
| Spring Boot executable nested JAR | 先 `mvn clean install -DskipTests` 安装当前 feature 产物，再执行 `mvn -f demos/demo-basic-rag/pom.xml clean package -DskipTests`；用 `java -jar demos/demo-basic-rag/target/demo-basic-rag-1.0.0.jar` 在隔离端口启动，断言低敏启动诊断/health 表明静态 source 与 Skill snapshot 已发现 |
| filesystem root | temporary directory test，断言 root scope 和 restart semantics |
| symlink/path traversal/encoded escape | 负向测试，断言不读取 root 外内容 |
| file/total/count limits | validator/loader test，断言 fail-fast 和 bounded diagnostics |
| UTF-8/empty/unsupported/duplicate source | parser/catalog tests |
| CJK/Latin/digits/model number/exact phrase | lexical retrieval test，断言 stable top-k |
| no embedding/no model call | spy/fake service test，断言索引和检索不触发外部调用 |
| KNOWLEDGE integration | `RetrievalAugmentationAdvisor` + fake project/static retriever，断言合并、
  rerank、prompt budget、sourceType 和 citation |
| AGENT static tool | fake ToolCalling model，断言 query/maxResults/trace/budget/result cap |
| PLAIN negative path | fake model，断言无 static retrieval、无 tool schema |

### 11.2 Skill 和 HTTP Tool

| 场景 | 证据 |
|---|---|
| frontmatter/name/links/capability | registry unit/integration test |
| Level 1/2/3 | tool contract test，断言不会把全部正文一次注入 |
| request-local isolation | 并发 request test，断言 A 的 loaded skill 不影响 B |
| missing/incorrect Skill | negative tool test，断言无下游调用 |
| reference path traversal | bounded reader test |
| endpoint allowlist | URI/method/query/header/path tests |
| SSRF/private IP/metadata/redirect | HTTP client security test，断言请求未发出或跳转被拒 |
| timeout/body/response/content-type | mock HTTP server test |
| credential handling | log/result/metadata assertion，断言 secret 不出现 |
| skill + HTTP happy path | Spring `@SpringBootTest` + mock downstream + fake/mock LLM，
  断言完整 Tool loop |
| tool call budget | `maxToolRounds`、total/per-name、bytes/chars/tokens、deadline assertions |

### 11.3 Memory 和上下文

| 场景 | 证据 |
|---|---|
| PLAIN multi-turn | current Chat integration + JDBC Memory read/write |
| KNOWLEDGE multi-turn | history-aware query + selected summary + evidence budget |
| AGENT tool messages | Spring AI message round-trip test，断言 call/result pairing |
| failed candidate/fallback | `ChatExecutionService` integration，断言失败局部消息不进入成功 Memory |
| summary CAS | existing V46 repository integration，断言 forward-only/version conflict |
| summary timeout/budget/provider error | service test + metrics assertion，主 Chat 不被阻断 |
| summary untrusted boundary | prompt assertion，summary 不进入 citation/evidence/instruction path |
| TTL cleanup | PostgreSQL integration，history/summary/JDBC Memory/lease consistency |
| idempotent replay | existing V47 HTTP integration，replay 不重复执行 Tool/model |
| principal isolation | PostgreSQL Chat integration，A 不读取 B 的 history/summary/tool scope |

### 11.4 后端、前端和运行时门槛

基本硬门槛必须在实现修改后、三轮实现 review 前通过：

```bash
mvn clean compile test-compile
# 本任务相关的 PostgreSQL integration matrix，skipped=0
./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-project-docs.sh
git diff --check
```

如果共享 `ChatSource`、metadata、API schema 或 WebUI source rendering 发生变化，必须
执行：

```bash
cd spring-ai-rag-webui
npx tsc -b --pretty false
npm run test:run
npm run build
# 核心 Mock Playwright：只用 DOM、可访问状态、网络/JSON 和断言，不用截图
```

如果没有前端源码变更，但共享 Chat JSON 完全兼容且没有 source UI 影响，仍要执行至少
tsc/build 和受影响的 Mock Playwright spec，并在进度文档说明 `N/A` 依据。

隔离端口真实全栈：

```text
main 之外的实施 worktree
  -> 独立 BACKEND_PORT/FRONTEND_PORT
  -> scripts/dev.sh 加载 .env
  -> PostgreSQL 一次性测试数据库
  -> curl/Playwright 验证静态 knowledge、Skill tool、Chat JSON/SSE、history
```

可执行 JAR 的 smoke 与日常 `scripts/dev.sh` classpath 运行是两条独立证据：前者验证
普通依赖 JAR 与 Spring Boot nested-JAR 的资源发现，后者验证前后端代理、SSE、数据库和
浏览器工作流。任一条失败都不能用另一条的结果替代。

真实 LLM 只在 Mock 流程通过后执行，且必须有界：

1. PLAIN：多轮记忆/summary disabled baseline；
2. KNOWLEDGE：静态条款 exact query 与无命中 query；
3. AGENT：先 `loadSkill`，再调用测试控制的 allowlisted weather tool fixture；
4. budget：重复工具调用、超大 response、deadline/timeout；
5. fallback/retry：不超过 configured model-call budget；
6. stream：至少一条 AGENT 或 KNOWLEDGE SSE。

真实 provider 测试期间持续观察日志；模型、token、密钥、URL 和 response body 中的敏感
内容不能写入文档或 Git。真实 LLM 结果用于验证 provider/tool-call 协议和主路径，不替代
确定性 integration tests。

### 11.5 三轮实现收敛

基本门槛全部通过后，执行固定范围的三轮只读检查：

1. 资源 scope、HTTP/SQL 安全、principal、事务、并发、预算、failure/recovery；
2. Chat mode、Advisor/Tool wiring、Memory/summary、API/JSON/SSE、WebUI compatibility；
3. 测试覆盖、真实运行时、文档/配置、启动/回滚、Git 交付。

发现会影响正确性、成本安全、兼容性或数据一致性的实质问题，立即修复并重跑受影响
门槛，计数器重置为 `0`。只有连续三轮无修改才完成实现收敛。review 不能代替上述测试。

## 12. 失败语义、可观测性与成本控制

### 12.1 静态资源失败

- 配置为空：no-op，服务可启动；
- 显式 root 不可读、解析失败、超出硬上限：默认 fail-fast；
- 显式设置 `fail-fast=false` 时，应用可启动但该 root 进入 degraded 状态、不会发布
  部分快照；`KNOWLEDGE` 只使用仍健康的现有业务检索源，`AGENT` 的
  `searchStaticKnowledge` 返回 bounded unavailable error，不能把缺失资料解释成
  “没有命中”；
- 单次检索索引异常：返回 typed retrieval error，不把文件全文作为 fallback；
- 快照不存在：readiness/degraded 状态可观察，不能静默回答“没有资料”；
- 资源更新：P0 重启后生效，旧进程继续使用旧 immutable snapshot。

### 12.2 Skill/HTTP Tool 失败

- Skill 未加载/不存在/路径非法：bounded tool error，不访问文件；
- capability 未登记/未授权：bounded policy error，不发 HTTP；
- SSRF/redirect/header/path/credential violation：拒绝并记录低敏 reason；
- downstream timeout/5xx/invalid body：bounded error，计入 Tool policy 和 Chat budget；
- budget exhausted：不继续调用，不用 retry 绕过；
- executor 饱和：快速失败，不阻塞整个服务线程池。

### 12.3 Memory/summary 失败

- summary 失败不阻断主 Chat；
- 如果 prompt planner 无法容纳必要输入，返回 typed context budget error；
- CAS 冲突只丢弃本次 summary 更新，不覆盖新版本；
- cleanup 失去 maintenance lease 时回滚，不删除未授权状态；
- 指标只使用低基数 labels，不能把内容当作 observability。

### 12.4 成本控制

静态 lexical retrieval本身不调用 embedding/LLM；Skill catalog/load/reference 只消耗
有限本地读取和 prompt tokens；HTTP/SQL 受现有共享预算限制。真实 provider smoke 每类
场景设置独立 session、短文档和低 tool call 上限，不使用开放式对话作为验收。

## 13. 中断恢复指南

实施开始后创建：

```text
docs/drafts/CHAT_RESOURCE_SKILL_MEMORY_EVOLUTION_PROGRESS.md
```

每个 Slice 开始前先更新 progress，至少记录：

- 特性分支、worktree、merge 后基线；
- 已完成的资源/Skill/Memory 切片和关键决定；
- 已运行的命令、测试、端口、数据库和证据目录；
- 当前基本硬门槛状态；
- 实现 review counter；
- 已知风险、下一步和恢复命令；
- 真实 LLM 是否执行及是否有外部限制。

禁止把 API key、Token、密码、完整工具输入、完整对话或外部文件绝对路径写入 progress。

恢复顺序固定：

```text
读取本 plan + progress
  -> git status/worktree/branch/HEAD
  -> 检查 origin/main 是否前进
  -> 补读受影响代码和测试
  -> 更新 progress
  -> 继续下一个 Slice
```

特性完成后，如 `origin/main` 相对特性分支有新提交，必须先 merge `origin/main` 到特性
分支，记录 merge 后基线，并按以下顺序重新验证，不能沿用 merge 前结果：

```text
merge 后基线
  -> PostgreSQL integration matrix + mvn clean compile test-compile
  -> tsc/build/core Mock Playwright
  -> 隔离端口真实全栈 + scripts/dev.sh
  -> 获准时真实 LLM smoke
  -> 连续三轮实现收敛
  -> merge feature -> main -> push -> git status
```

## 14. 规划审查记录与完成定义

### 14.1 规划三轮审查范围

本规划必须在实施前连续三轮无实质问题：

1. **需求闭环与自包含性**：三项用户需求是否都得到具体方案；当前事实、目标、非目标、
   默认值、可逆边界、阻断决策是否清楚；是否过度引入参考项目背景。
2. **技术、安全和一致性**：资源加载、JAR/filesystem scope、静态检索、Skill gate、
   HTTP SSRF、SQL、principal、预算、Memory/summary、Spring AI `1.1.8` 兼容、Flyway
   和并发规则是否可实施且无矛盾。
3. **测试和交付可行性**：slice 顺序、文件地图、后端集成、PostgreSQL、前端 Mock、
   packaged JAR、真实 LLM、三轮实现 review、回滚和中断恢复是否能提供可靠证据。

发现影响正确性、成本安全、兼容性、数据一致性或可实施性的实质问题，立即修改本文并把
计数器重置为 `0`。措辞、格式和实施时自然出现的行号漂移不触发重置。无问题轮次不修改
本文。

### 14.2 规划完成定义

本轮规划在以下条件全部满足后才可交给用户 Review：

- 规划文档位于当前 main worktree 的 `docs/drafts/`；
- `docs/drafts/README-zh-CN.md` 和 `docs/drafts/README.md` 已列出当前活跃规划；
- 上一轮 plan/progress 已在 `docs/drafts/archive/`，本轮没有把历史稿当成事实入口；
- 当前代码/迁移/测试基线已核对；
- 规划审查达到连续 `3/3` 无实质修改；
- `./scripts/verify-project-docs.sh`、`git diff --check` 和 markdown 链接检查通过；
- 尚未切换到特性 worktree、尚未修改生产代码、尚未创建 progress 文档；
- 最终汇报明确停在“实施前”，等待用户下一步指示。
