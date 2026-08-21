# Chat 上下文预算、持久记忆与工具治理进度

> **状态**：规划中，尚未开始生产代码实施
>
> **开始日期**：2026-08-21
>
> **规划分支**：`docs/chat-context-tool-orchestration-plan-20260821`
>
> **规划基线**：`main` @ `2ea56c9d`
>
> **实施规划**：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)
>
> **长青调研**：[Chat 记忆、RAG 与工具调用](../chat-memory-rag-tool-calling-zh-CN.md)
>
> **规划冻结 SHA-256**：
> `ffc2fefa9c7ea07079a9208b8161a9a36a2ccca8608a85f898ab37788eb5804d`

本文件是跨会话恢复账本，不替代代码、长青文档或实施规划。

## 1. 当前阶段

| 阶段 | 状态 | 说明 |
|---|---|---|
| 代码和文档调研 | 已完成 | 已核对 Chat 三模式、Memory、Modular RAG、Tool Calling、预算和测试 |
| 上一轮规划归档 | 已完成 | `docs/drafts/` 在本轮开始时为空；上一轮 plan/progress 已在 archive |
| 长青调研落档 | 已完成 | 新增中英文专题并准备加入索引 |
| 自包含实施规划 | 已完成 | 已通过连续三轮审查 |
| 规划连续审查 | `3/3` | 最终三轮均未修改规划正文 |
| 生产代码实施 | 未开始 | 必须从实施时最新本地 main 创建新特性分支 |

## 2. 已冻结的核心方向

- 保留 `PLAIN / KNOWLEDGE / AGENT`。
- `KNOWLEDGE` 继续使用 Spring AI Modular RAG，不改造成 Function Calling。
- 升级 Spring Boot `3.5.3 -> 3.5.16`、Spring AI `1.1.4 -> 1.1.8`，不升级 Boot 4 /
  Spring AI 2。
- 预算跨 candidate、retry、辅助模型调用和工具循环共享。
- 工具调用 batch 在执行前原子预留。
- 增加 server-owned Tool Provider SPI，不开放客户端 tools 透传。
- 不提供任意 SQL；只提供参数化只读 SQL 扩展示例。
- token budget 默认开启；持久摘要默认关闭、显式启用。
- 摘要不是引用证据，失败时确定性截断并继续主 Chat。
- V46 使用 CAS，不增加悲观锁。

## 3. 规划审查日志

当前连续无修改计数：`3/3`。

发现问题并修改规划的轮次记录在本节；连续无问题轮次只在完成 `3/3` 后一次性写入最终记录，
避免破坏审查期间“规划正文无修改”的证据。

### 2026-08-21 23:33 CST：第 1 轮发现工具结算与硬门槛设计缺口

- 检查范围：需求闭环、自包含性、默认决策、非目标，以及 Spring AI
  `ToolCallingManager` 的实际执行接口。
- 发现问题：
  - 仅在 Advisor response hook 计数，不能完整关联 batch tool call 的 ID、逐项结果和累计
    输出；
  - 自适应 token planner 的回滚开关与“每次模型调用必须防止上下文溢出”混在一起；
  - mode evidence、summary 和 recent history 的预算顺序没有冻结；
  - query expander 上限描述与现有 `1..5` 配置绑定不一致。
- 处理：
  - 改为 `BudgetedToolCallingManager` 在委托前整批预留、委托后按
    `ToolExecutionResult` 真实结果结算；
  - 每个 tool policy 预留最大结果字符，通用超大输出返回固定错误 JSON；
  - 把 adaptive planner 开关与不可关闭的 `BudgetedChatModel` prompt 硬门槛分离；
  - 冻结 evidence/summary/history 分配顺序和 KNOWLEDGE 最多 6 次扩展检索。
- 结果：规划已修改，连续无修改计数重置并保持 `0/3`。

### 2026-08-21 23:47 CST：第 2 轮发现依赖组合与 token 结算缺口

- 检查范围：Spring AI API、依赖基线、工具执行、模型窗口、授权、数据库与兼容可行性。
- 发现问题：
  - Spring AI `1.1.8` 发布基线已使用 Boot `3.5.15`，截至本次规划 Boot 3.5 维护线已有
    `3.5.16`；把 Spring AI 补丁直接叠加到 Boot `3.5.3` 会形成未纳入计划验证的旧组合；
  - 工具结果只按字符数预留，无法阻止中文等高 token 密度结果突破下一轮 prompt；
  - `max-tool-schema-tokens` 没有定义超限行为，自定义 `ToolCallingManager` 也未明确委托
    `resolveToolDefinitions`；
  - `CompressionQueryTransformer` 只读取 USER/ASSISTANT 历史，摘要消息 role 未冻结；
  - 外部 callback 的 `returnDirect=true` 可能绕过正常模型回答和响应语义；
  - callback 忽略中断或 delegate 抛错时，执行器饱和和预算预留释放语义未定义。
- 处理：
  - Phase 0 改为一起升级 Boot `3.5.16` 与 Spring AI `1.1.8`，并保持独立 characterization；
  - 增加按真实 `ToolResponseMessage` 的 token 结算、结构化替换和最终 Prompt 硬门槛；
  - 冻结 schema 在首个模型调用前 fail-fast，并明确工具定义解析原样委托；
  - 摘要以带不可信数据边界的合成 `AssistantMessage` 进入 query compression；
  - 本轮 registry 拒绝 `returnDirect=true` callback；
  - 增加有界工具执行器、饱和/销毁规则，以及异常时保留调用计数但释放未用结果预留的语义。
- 结果：规划与双语长青调研已修改，连续无修改计数重置为 `0/3`。

### 2026-08-21 23:57 CST：审查重启前发现真实全栈验收缺口

- 检查范围：现有 Chat 验证脚本、WebUI Playwright 配置、Mock spec、`dev.sh` 和真实 LLM
  smoke 的职责边界。
- 发现问题：现有 `chat.spec.ts` 全部注册 API mock，真实 LLM smoke 只验证后端；计划没有
  指定 `dev.sh` 启动的非 Mock WebUI 如何对真实后端执行浏览器验收。
- 处理：新增独立 `chat-real.spec.ts` 规划，冻结隔离端口/数据库、真实请求与 SSE、DOM
  可访问状态、history 恢复、日志/数据库只读证据和清理流程；用户已允许真实 LLM，最终门禁
  不得跳过该 spec 与真实 provider smoke。
- 结果：规划已修改，连续无修改计数保持 `0/3`，下一轮从需求闭环重新开始。

### 2026-08-21 23:39 CST：重启第 1 轮发现配置与上下文优先级缺口

- 检查范围：用户问题闭环、自包含性、默认决策、非目标和规划内部一致性。
- 发现问题：
  - 新配置只有短名，没有冻结完整命名空间、启动校验和已有键兼容关系；
  - `RagChatToolPolicy` 没有列出本轮实际支持的字段，SQL timeout 与通用工具治理的责任边界
    不明确；
  - prompt 分配顺序先放 summary、后放 recent turns，与最低近期原始上下文要求冲突。
- 处理：
  - 冻结 `rag.chat.execution.*`、`rag.chat.agent.*` 和 `rag.chat.context.*` 完整配置键及基本
    校验；
  - 冻结只读工具 policy 的调用数、输出字符和 timeout 语义，并明确 I/O deadline 仍由
    provider 下推；
  - 把最低 recent raw turns 提升到 summary 之前，补充小窗口确定性降级顺序。
- 结果：规划已修改，连续无修改计数重置并保持 `0/3`。

### 2026-08-21：依赖可行性审查发现 Memory advisor API 兼容问题

- 检查范围：Spring Boot `3.5.16` + Spring AI `1.1.8` 的最小依赖升级、生产
  `MessageChatMemoryAdvisor` 构建方式、非流式/流式 request context 和现有会话测试。
- 发现问题：Spring AI `1.1.8` 已移除
  `MessageChatMemoryAdvisor.Builder.conversationId(...)`，原计划只写“修复 API
  兼容问题”，不足以保证实现时保留 principal/session Memory 隔离。
- 处理：
  - 在独立 detached worktree 中联合升级 Boot/Spring AI；
  - 移除 builder 上的 conversation ID；
  - 在 `ChatExecutionService` 的 call/stream advisor context 中显式传入
    `ChatMemory.CONVERSATION_ID=command.memoryConversationId()`；
  - `mvn compile test-compile -rf :spring-ai-rag-core` 通过；
  - `ModeAwareChatClientFactoryTest`、`ChatExecutionServiceTest`、
    `ChatMemoryMultiTurnTest` 共 30 个测试通过；
  - 将迁移步骤、隔离 characterization 和完成定义补入规划及双语长青调研。
- 结果：规划已修改，连续无修改计数重置并保持 `0/3`。探针仅证明方案可行，不替代实施
  分支上的正式门禁。

### 2026-08-21：代码与安全审查发现 Tool Registry 校验缺口

- 检查范围：Spring AI `ToolCallingManager`/`ToolCallback` 1.1.8 源码、API 模块依赖、
  provider SPI、tool context、policy 映射、启动与请求错误语义。
- 发现问题：
  - policy map 以 tool name 为 key，但规划未规定未知 key 的处理；restrictive policy
    拼写错误可能静默回退到更宽松默认值；
  - 非法 provider/重复工具名被描述为“启动或首次请求时 fail fast”，同时又规划了 HTTP
    `CHAT_TOOL_REGISTRATION_INVALID`，启动失败与请求级错误契约互相矛盾。
- 处理：
  - registry 改为 Bean 发现后立即构建不可变快照并完成校验，不支持运行时动态注册；
  - 冻结 provider 返回值、callback、policy map 的非空规则，未知/空 policy key 和
    `null` policy 均启动失败；
  - 删除不可达的请求级注册错误码，启动异常只包含 provider/tool 名称，不包含参数或凭据；
  - 补充 registry 专项测试要求。
- 结果：规划已修改，连续无修改计数重置并保持 `0/3`。

### 2026-08-21：审查前交叉验证发现非流式 retry 的局部 Memory 污染风险

- 检查范围：`ChatExecutionService` 的 `RetryTemplate` 边界、
  `ModeAwareChatClientFactory` attempt 生命周期，以及 Spring AI 1.1.4/1.1.8
  `MessageChatMemoryAdvisor.before()` 源码。
- 发现问题：当前非流式路径在 `RetryTemplate` 外创建一次 attempt 并重复调用它；Memory
  advisor 在模型委托前写入当前用户消息，因此失败调用留下的 request-local 状态可被下一次
  retry 继承，并在最终成功时提交重复或部分上下文。
- 处理：
  - 双语调研明确区分“失败 turn 未直接持久化”和“成功 retry 可能携带失败 attempt 局部
    残留”；
  - 规划冻结每次应用层 retry 从同一 committed baseline 创建新 attempt；
  - 失败 attempt 的 Memory、advisor/RAG context 和工具 conversation 丢弃，已发生的
    model/tool/retrieval 预算不退回；
  - 增加直接验证最终 Memory 无重复、失败工具结果不泄漏、预算仍累计的专项测试。
- 结果：规划已修改，连续无修改计数保持 `0/3`。

### 2026-08-21：第 2 轮发现模型限值与 context 配置校验缺口

- 检查范围：`MultiModelProperties.ModelItem`、外部 `models.json` loader、
  `ChatModelCandidate` 元数据、prompt/output reserve 和 compaction 配置关系。
- 发现问题：
  - 当前 loader 不拒绝显式为 `0` 或负数的 `contextWindow/maxTokens`；
  - 规划只定义“缺失时 fallback”，没有区分缺失与非法，非正 `maxTokens` 可破坏输出预留；
  - context/summary/compaction 新配置只有单字段正数校验，没有冻结不可能组合的启动期处理。
- 处理：
  - 缺失 limit 保留带诊断的保守 fallback；
  - 显式非正 limit 使该 candidate unavailable，显式请求沿用现有不可用模型错误，不静默
    fallback；
  - 冻结 fallback window、output/safety、history/summary、evidence/RAG 和 compaction 的
    交叉约束；
  - 增加 model routing 与配置启动校验专项测试。
- 结果：规划和双语长青调研已修改，连续无修改计数重置为 `0/3`。

### 2026-08-21：审查前交叉验证发现 TTL 与活跃 Chat 的 baseline 回写竞态

- 检查范围：`ChatHistoryCleanupService`、`RagChatHistoryRepository`、
  `ChatSessionCoordinator`、V32 会话 lease schema、JDBC Memory 清理语义，以及 V46
  PostgreSQL 验收矩阵。
- 发现问题：当前 TTL 直接删除 `rag_chat_history`，既不协调活跃
  `principal/session` lease，也不清理 JDBC Memory。一个 Chat 请求可能先读取旧 baseline，
  TTL 随后删除历史，最后该请求仍尝试把清理前的历史/Memory 写回，造成保留期和数据一致性
  破坏。
- 处理：
  - TTL 改为有界候选发现，再逐 session 申请现有 lease 表中的独立 maintenance token；
  - 有效 Chat lease 的 session 跳过，不等待、不抢占；获得 maintenance lease 后，在短事务
    中 token-fenced 消费 lease，并原子清理本批 history、summary 和有 owner namespace 的
    JDBC Memory；
  - 事务失败时四类状态一起回滚；活跃 Chat 的旧 token 无法在 TTL 后通过 commit fencing；
  - 补充 PostgreSQL 集成测试：active lease 跳过、maintenance 原子清理、完整回滚、旧
    baseline 禁止回写和有界批次推进；
  - 同步更新中英文长青调研和实施规划。
- 结果：规划正文发生实质修改，规划连续无修改计数重置为 `0/3`。

### 2026-08-21：第 2 轮发现 Tool Registry 启动期 callback 元数据校验缺口

- 检查范围：`ToolCallback`/`ToolDefinition`/`ToolMetadata` 的现有内置实现、
  `RagChatToolProvider` 注册规则、domain 选择语义和 registry 验收矩阵。
- 发现问题：规划只要求 provider、callback 列表和工具名非空，没有冻结
  `getToolDefinition()`、`getToolMetadata()` 和 input schema 的启动期校验。非法外部 Bean
  可能把配置错误推迟到首次 Chat 请求，且默认 domain 的 `null/blank` 匹配规则没有写死。
- 处理：
  - registry 构建不可变快照时立即校验 definition、metadata、非空唯一 name、可解析 JSON
    object schema 和 `returnDirect=false`；
  - 冻结 `null/blank domainId` 为默认领域：空 supported domain 集合匹配默认及显式领域，
    非空集合只匹配同名显式领域；
  - 将空 definition、空 metadata、非法 schema、重复名和默认 domain 纳入 registry 测试。
- 结果：规划和双语长青调研已修改，连续无修改计数重置为 `0/3`。

### 2026-08-21：第 2 轮发现 SQL 示例的 tenant 授权语义缺口

- 检查范围：`RagChatToolRequestContext` 字段、SQL demo 的参数化查询约束、现有 principal/
  ACL 边界，以及长青文档中的 SQL 安全表述。
- 发现问题：规划要求 SQL 工具注入 tenant 条件，但公共 context 没有通用 tenant 字段，也
  没有定义 principal 到 tenant 的可信映射；如果实现者自行补齐，可能把客户端 metadata 或
  模型参数误当授权来源。
- 处理：
  - SQL demo 固定使用 server-owned `principal.id` 的 owner 条件；
  - 明确本项目不提供通用 tenant contract；消费者如需 tenant，必须经服务端 principal/ACL
    映射解析后由 provider 固定注入；
  - 明确禁止从 ChatRequest、client metadata 或模型工具参数读取 tenant；
  - 同步更新双语长青文档和 PostgreSQL SQL demo 验收要求。
- 结果：规划和双语长青调研已修改，连续无修改计数重置为 `0/3`。

### 2026-08-21：第 2 轮发现 Tool Policy 数值边界未冻结

- 检查范围：`RagChatToolPolicy` 默认值、`AuthorizedRetrievalContext` 的现有最小结果
  保护、全局 agent budget 配置和 registry 启动校验设计。
- 发现问题：规划只说明 policy 不能放宽全局预算，没有定义
  `maxCallsPerRequest`、`maxResultCharacters` 的非正值和过小值如何处理；非法 provider
  配置可能在首次工具调用时才失败，或为了错误包络被静默放宽。
- 处理：
  - 冻结 `maxCallsPerRequest` 为 `1..全局 max-tool-calls-per-name`；
  - 冻结 `maxResultCharacters` 为 `1024..全局 max-tool-result-characters`，与现有最小
    结构化工具结果保护一致；
  - 冻结正 timeout 和启动期 fail-fast，并将 policy 数值边界纳入 registry 测试。
- 结果：规划正文发生实质修改，规划连续无修改计数重置为 `0/3`。

### 2026-08-21：规划审查第 3 轮通过

- 检查范围：实施切片、验收矩阵、Mock 与真实 LLM 门禁、前后端构建与集成顺序、
  worktree/分支交付、回滚边界，以及第 9 节最终顺序的内部一致性。
- 发现问题：无。
- 处理措施：无；规划正文保持不变。
- 结果：连续无修改审查达到 `3/3`，规划可进入实施阶段。

## 4. 下一步恢复入口

1. 计算并记录规划正文 SHA-256。
2. 运行 `./scripts/verify-project-docs.sh`、`./scripts/verify-no-pessimistic-locks.sh`
   与 `git diff --check`。
3. 提交、合并远端变化并 push 规划分支；确认工作区干净。
4. 从届时最新本地 `main` 创建新的实现分支和隔离 worktree，开始生产代码实施。
