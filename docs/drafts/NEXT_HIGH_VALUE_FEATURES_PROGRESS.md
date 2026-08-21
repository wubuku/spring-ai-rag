# Chat 上下文预算、持久记忆与工具治理进度

> **状态**：生产代码实施中，当前处于 Phase 5
>
> **开始日期**：2026-08-21
>
> **规划分支**：`docs/chat-context-tool-orchestration-plan-20260821`
>
> **规划基线**：`main` @ `2ea56c9d`
>
> **实施分支**：`feat/chat-context-tool-orchestration-20260821`
>
> **实施 worktree**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-chat-context-tool-orchestration`
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
| 生产代码实施 | Phase 5 进行中 | 预算、上下文规划、Tool Provider、SQL 示例和 V46/摘要初版已实现，正在补齐专项集成、协议、前端和真实 LLM 验收 |

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

## 2.1 当前实施切片：上下文预算与配置校验

已完成但尚未提交：

- `ConversationPromptPlanner` 现在按完整 user/assistant turn 选择最近历史，避免把一个
  turn 拆开或改变消息顺序；
- summary 受 `max-summary-tokens` 限制，KNOWLEDGE 的 RAG 预算会在历史/summary 选择后
  使用剩余窗口扩展到配置上限；
- `adaptive-planning-enabled=false` 时保留旧 baseline 消息行为，但不关闭
  `BudgetedChatModel` 的模型调用预算和后续硬门槛；
- planner 的 mandatory token 估算包含 server prompt、当前用户输入和完整 transport
  input messages；
- RAG document postprocessor 在 adaptive planner 关闭时保持原始文档列表；
- 增加 `RagChatProperties.validate()` 与启动期 validator，覆盖工具调用、结果字符、执行器、
  context window、history/summary/evidence 的正数和交叉约束；
- 新增 planner、RAG token cap、配置校验专项测试，并补充 per-tool 结果预留结算测试。

### 2026-08-21：摘要游标、V46 CAS 与 SQL demo 收敛

- SQL demo 已改为显式 `PreparedStatement` 绑定，固定 SQL 仍包含服务端 owner 条件、查询超时
  和行数上限；H2 测试使用唯一数据库名并在每个用例后 shutdown，结果时间规范化为
  ISO-8601 字符串，避免测试污染和平台相关 epoch 序列化。
- `RagChatHistoryRepository` 增加 owner/session 有界 newest-first baseline 与
  `id > cursor` oldest-first compaction source 查询，避免摘要和上下文恢复把整个会话加载到
  Java 内存。
- V46 与摘要 repository 对齐规划：`summary_text`、`summarized_through_history_id`、
  `estimated_tokens`、`version`、`created_at/updated_at`；更新要求 version CAS 且 cursor
  严格前进。
- `ConversationSummaryService` 只压缩游标之后的 COMPLETE turns，排除最近
  `minimum-recent-turns`，摘要模型使用共享 model-call budget、candidate context gate、
  request deadline 和 daemon executor；失败、超时、输出超限、模型预算耗尽均降级而不影响
  已提交主 Chat。
- 新增摘要专项测试，覆盖 cursor 推进、最近 turn 保护、provider failure、超时、输出超限和
  summary model-call budget；测试尚待本轮编译运行确认。

本切片待验证：

- `mvn -pl spring-ai-rag-core -am -DskipTests compile test-compile`
- planner、postprocessor、budget、既有 Chat 专项测试

### 2026-08-21：恢复后专项基线与默认策略修复

- `RagChatToolRegistryTest` 3 项通过，包含 provider 包装 callback、领域过滤和 `returnDirect`
  启动校验；core reactor test 结果为 `BUILD SUCCESS`。
- 修复未声明 tool policy 时固定默认值超过全局收紧配置的问题：默认 policy 现在继承全局
  `max-tool-calls-per-name` 与 `max-tool-result-characters` 的较小值，不会因合理收紧配置
  阻止注册表启动。
- 新增收紧全局限制下省略 policy 的回归测试；后续仍需补齐 batch 预算真实结算与 token
  hard gate，不能把本次注册表测试视为整个 Phase 1 完成。

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

## 4. 实施进度

### 2026-08-21：创建实施分支并带入冻结规划

- 实施分支从本地 `main`、`origin/main` 的共同基线 `2ea56c9d` 创建。
- 已无损带入规划提交 `ec195188`；规划正文未改写。
- 下一项工作是记录版本与现有 Chat 门禁基线，然后只处理依赖升级带来的兼容性问题。

### 2026-08-21：Phase 0 修改前基线

- `./scripts/verify-chat-capability.sh` 已执行，基线日志目录为
  `.verification/chat-capability/20260822-014601/`。
- focused Chat 后端测试：`202` 项通过。
- `mvn clean compile test-compile`：通过。
- 完整 Maven 测试：`2847` 项通过、`7` 项既有跳过。
- 独立后端启动 smoke：通过，PostgreSQL/pgvector/Actuator readiness 均为 `UP`。
- PostgreSQL Chat 集成门禁：发现基线测试
  `fullMigrationThroughV39PreservesChatContractsAndRejectsInvalidNewRows` 仍期待版本
  `39`，而当前仓库迁移已到 `V45`；这是基线测试陈旧，不是本轮实现引入的失败，后续
  V46 集成矩阵会一并改为验证 V1–V46。
- WebUI Vitest、生产构建和 Mock Playwright 未执行成功：该 worktree 尚未安装
  `spring-ai-rag-webui/node_modules`，因此 `vitest`、`tsc` 和 Vite preview 不可用；
  后续先安装锁定依赖，再重新取得前端基线。

### 2026-08-21：Phase 0 前端基线补测

- 在 `spring-ai-rag-webui/` 执行 `npm ci`，按 `package-lock.json` 安装 351 个依赖包。
- `npm run typecheck`：通过。
- `npm run build`：通过，Vite production bundle 正常生成。
- `npm run test:run`：29 个测试文件、214 个测试全部通过。
- 使用隔离 Vite preview 端口 `4198`，以
  `BASE_URL=http://127.0.0.1:4198` 执行
  `e2e/chat.spec.ts` 与 `e2e/streaming-upload.spec.ts`：11 项全部通过。
- 本次补测只验证 Mock 前端行为；没有把它当作真实后端或真实 LLM 联调证据。

### 2026-08-21：Phase 0 依赖与 Memory advisor 兼容改造

- 根工程与四个独立 demo 一起升级到 Spring Boot `3.5.16`、Spring AI `1.1.8`。
- 适配 Spring AI `1.1.8` 移除的
  `MessageChatMemoryAdvisor.Builder.conversationId(...)` API：
  conversation ID 改为在每次非流式/流式 Chat 请求的 advisor 参数中显式注入
  `ChatMemory.CONVERSATION_ID`。
- 保留服务端派生的 principal/session 命名空间，不接受客户端提供的 Memory key。
- 新增服务层 characterization 测试，直接捕获 advisor 参数验证 `SERVER` Memory 的隔离 key。
- `mvn -pl spring-ai-rag-core -am -DskipTests compile test-compile`：通过。
- 相关测试
  `ChatExecutionServiceTest`、`ModeAwareChatClientFactoryTest`、
  `ChatMemoryMultiTurnTest`：31 项全部通过。

### 2026-08-21：Phase 1 预算内核与 Tool Provider 初版

- 新增 typed errors：`CHAT_BUDGET_EXHAUSTED`、`CHAT_CONTEXT_BUDGET_EXCEEDED`。
- `RagChatProperties` 增加逻辑请求执行预算、Agent 工具总量和上下文预算配置；
  `ChatExecutionBudget` 统一保存 candidate/model/tool 计数、工具结果累计量和 deadline。
- 新增 `BudgetedChatModel`，对非流式 call 和流式 subscription 计数；服务端 Chat 请求
  创建单一 budget，并将预算快照写入成功响应 metadata。
- 新增 `BudgetedToolCallingManager`，在委托 Spring AI 标准工具执行前对完整 tool batch
  做 round/total/per-name/字符预留，异常时保留调用计数并释放未使用预留。
- 新增公共 `RagChatToolProvider`、`RagChatToolPolicy`、
  `RagChatToolRequestContext` 和稳定 context key；新增 core 注册表，启动期校验工具
  definition、schema、metadata、重复名和 policy，内置检索工具纳入注册表。
- Agent 生产路径已支持从注册表选择工具，并把服务端 principal/session/deadline 与预算
  放入 Spring AI ToolContext；单元测试构造路径继续保持兼容。
- 后端 `mvn -pl spring-ai-rag-core -am -DskipTests compile test-compile`：通过。
- 预算、Chat service、factory 相关测试：21 项全部通过。

### 2026-08-21：恢复实施上下文并确认当前交付边界

- 已核对实施 worktree
  `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-chat-context-tool-orchestration`
  当前工作区干净，分支 `feat/chat-context-tool-orchestration-20260821` 基于
  `main@2ea56c9d`，没有需要迁移或丢弃的未提交修改。
- 当前 HEAD 为 `e5c6078b`；该提交已完成 Phase 0 依赖兼容迁移及 Phase 1 的预算/工具注册表
  初版，但尚未达到规划中的完整功能完成定义。
- 后续实现必须先对现有初版执行代码级门槛和固定范围缺陷审计；确认后继续完成
  `BudgetedToolCallingManager` 的完整测试、token-aware context、V46/摘要、SQL demo、
  协议/前端/真实运行时验收，不把既有 21 项局部测试视为整项完成证据。
- 当前恢复入口：先运行 core focused compile/test，随后按规划中的 Phase 1→Phase 6 顺序推进；
  任一实质修复都要重新执行受影响门槛并将实现审查计数重置为 `0`。

### 2026-08-21：初版代码审计发现的阻断问题

- 初版 core 编译通过，但审计确认不能直接进入后续 Phase：
  - 非流式 `RetryTemplate` 在 attempt 外创建并重复调用同一个 `ChatClient`，失败调用可能把
    用户消息、工具对话或检索局部状态带入成功 retry；
  - `RagChatToolRegistry.PolicyToolCallback` 以 `Map<ChatExecutionBudget, ...>` 保存每个
    请求计数，长时间运行会保留已完成请求的 budget，形成无界生命周期泄漏；
  - AGENT 请求先写入 tool callbacks/context，再复制默认 `ToolCallingChatOptions`，存在覆盖
    server-owned tool context 的兼容风险；
  - 工具 callback timeout 只使用 policy timeout，没有与逻辑请求 deadline 取最小值；
  - 工具 batch 预算仍使用硬编码字符上限，未按注册 policy 预留，也没有 token-aware 结果
    降级和完整 batch/真实 tool-call-id 验收。
- 处理策略已冻结：先完成预算内核、retry attempt 生命周期和 registry 执行器的正确性修复，
  再开始 token-aware context、摘要和 V46；本阶段不改变三种 Chat 模式或客户端契约。
- 当前实现审查计数重置为 `0`；修复后必须重新通过 core focused tests 和
  `mvn clean compile test-compile`，再进入下一阶段。

### 2026-08-21：预算内核第一轮修复验证

- retry attempt 改为在每次应用层 retry callback 内重新创建，所有 retry/fallback 继续共享同一
  `ChatExecutionBudget`，成功提交只读取成功 attempt 的 request-local Memory。
- 工具结果字符预留改为支持按工具 policy 计算；policy 调用计数移入 request-local budget，
  注册表不再持有已完成请求的 budget 引用。
- AGENT 默认 `ToolCallingChatOptions` 先复制，再追加 server-owned callbacks/context；
  工具执行等待时间与 request deadline 取最小值。
- 重新执行 `mvn -pl spring-ai-rag-core -am -DskipTests compile test-compile`：通过。
- 这些修复属于实现修改，后续实现审查计数仍为 `0`，必须在所有功能完成后重新进行连续
  `3/3` 检查。

### 2026-08-21：继续实施前的状态核对

- 当前 worktree 为
  `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-chat-context-tool-orchestration`，
  分支为 `feat/chat-context-tool-orchestration-20260821`，基线仍为
  `main@2ea56c9d`。
- 工作区存在本轮未提交的生产代码、测试、V46 migration、SQL demo 和本进度文档修改；
  未使用 `stash`、强制回退或覆盖操作，所有现有修改继续保留。
- 已确认两个本轮先修复的实现缺口：摘要源没有落实
  `compaction-max-source-tokens` 的 token 上限；配置校验没有要求
  `compaction-max-output-tokens < compaction-max-source-tokens`。
- V46 PostgreSQL 迁移已存在，Chat 会话集成测试已改为期待 V46；尚待补齐 summary
  owner/session 隔离、CAS、游标单调性、非法数据约束和 clear 删除的端到端断言。

### 2026-08-21：摘要源上限与 V46 集成矩阵

- `ConversationSummaryService` 现在按完整历史 turn 逐条构造 compaction source，并对包含
  旧摘要边界文本的完整 source 执行 `compaction-max-source-tokens` 硬上限；超限 turn 不会
  被部分发送，也不会推进摘要游标。
- 启动期配置校验新增
  `compaction-max-output-tokens < compaction-max-source-tokens` 约束。
- `ConversationSummaryServiceTest` 新增 source token cap/cursor 不越界回归测试；
  `RagChatPropertiesValidationTest` 新增不可能 compaction 配置回归测试；两组共 12 项
  focused 测试通过。
- `ChatSessionPostgresIntegrationTest` 新增 V46 summary 的 owner/session 隔离、CAS、
  cursor 单调前进、约束拒绝和 owner-scoped delete 测试；待用真实 PostgreSQL 执行。

### 2026-08-21：V46 PostgreSQL 端到端门槛通过

- 使用本地 Testcontainers Docker 环境和 `pgvector/pgvector:pg16`，从空库执行 Flyway
  V1–V46；`ChatSessionPostgresIntegrationTest` 的 12 项测试全部通过。
- 真实验证了 V46 summary 表迁移、owner/session 隔离、insert/update CAS、旧 version/
  非前进 cursor 拒绝、数据库约束以及 owner-scoped delete。
- 因本机 Docker registry 对 `testcontainers/ryuk:0.11.0` 的证书错误，测试使用
  `TESTCONTAINERS_RYUK_DISABLED=true`；PostgreSQL 测试容器正常启动并在测试完成后退出。

### 2026-08-21：SSE 协议测试夹具修复

- `RagChatControllerTest` 的新增 SSE metadata/error 测试首次运行时发现测试夹具错误地把
  Spring `SseEventBuilder` 合并的 `event:` 与 `data:` 文本块整体当作事件名。
- 仅修正测试夹具为按行提取事件名，未修改生产 SSE 发送逻辑。
- `RagChatControllerTest` 与 `GlobalExceptionHandlerTest`：60 项全部通过。

### 2026-08-21：前端构建与核心 Mock Playwright 门槛通过

- `spring-ai-rag-webui`: `npm run build` 通过，包含 TypeScript 增量编译与 Vite 生产构建。
- 使用隔离 Vite 端口 `4198`，运行 `e2e/chat.spec.ts` 与
  `e2e/streaming-upload.spec.ts`，11 项全部通过。
- 验证范围包括 Chat 三种模式请求体、Agent 工具事件/来源 DOM、SSE、会话恢复、移动端
  横向溢出、文档上传入口；前端验收仅使用 DOM、网络请求和自动化断言。

### 2026-08-21：真实服务首次启动缺陷与修复

- 使用主工作区 `.env` 的真实 OpenAI-compatible Chat 配置
  `grok-4.5`、SiliconFlow BGE-M3 Embedding 和 PostgreSQL，在隔离端口 `18081` 启动。
- 首次启动发现 `RagChatToolRegistry` 直接依赖的 `RagChatProperties` 没有独立 Spring bean；
  新增 `RagChatPropertiesConfig`，暴露统一 `RagProperties.getChat()` 的同一实例，避免重复
  配置绑定。
- 第二次启动继续暴露 `RagChatMemorySummaryRepository` 为 `final`，而 Spring 事务切面需要
  CGLIB 子类；移除该类的 `final` 修饰，保留 repository API 和 CAS SQL 不变。
- 两项修复后的配置/摘要/工具 focused tests：21 项通过；真实服务需重新启动确认。

## 5. 下一步恢复入口

1. 运行 core focused compile/test，确认当前初版的实际可编译和测试状态。
2. 对预算、工具 registry、Chat retry/fallback 和接口兼容性执行固定范围代码审计，修复实质
   缺陷并补齐一次性验收测试。
3. 依规划完成 token-aware context、V46/摘要、SQL demo、协议/脚本/文档和真实运行时门禁。

## 6. 2026-08-21：真实服务前的 focused 测试收尾

- 修复后的配置、工具注册表、提示规划器、摘要服务和 PostgreSQL 会话集成矩阵已完成一次
  focused 执行：`33` 项测试全部通过，Maven 构建成功。
- 本次 PostgreSQL 集成测试使用 Testcontainers 从空库执行 Flyway V1–V46，确认当前
  worktree 的 V46 迁移和 Chat 会话摘要持久化行为可启动、可迁移、可验证。
- 下一步是使用主工作区 `.env` 中的真实 OpenAI-compatible Chat/Embedding 配置，在隔离
  端口 `18081` 启动服务并执行真实 LLM smoke/e2e；密钥不复制、不打印、不写入仓库。

## 7. 2026-08-21：真实 LLM 基础 E2E smoke 通过

- 使用隔离端口 `18081`、主工作区 `.env` 的真实配置完成仓库自带
  `scripts/real-llm-e2e-smoke.sh`。
- 真实验证链路全部通过：健康检查、SiliconFlow BGE-M3 embedding API、Chat API 探针、
  隔离 collection/document、真实 embedding、向量/混合检索、非流式 Chat、SSE 流式 Chat。
- 本次结果为 `PASS=10 FAIL=0`；非流式和流式答案都包含本次隔离文档生成的验证码，脚本
  退出清理了测试 collection/document。
- 该 smoke 覆盖通用 RAG 主路径；本轮新增的 `KNOWLEDGE / AGENT / PLAIN` 模式、工具
  调用预算、会话记忆与摘要压缩仍需单独做真实运行时验证。

## 8. 2026-08-21：真实 PLAIN 会话记忆通过

- 使用真实 Chat 模型、独立 session 连续执行两次 `mode=PLAIN` 请求，并查询该 session
  历史。
- 第二轮回答准确返回第一轮写入的 marker，响应 `mode=PLAIN` 且 `sources=[]`；历史接口
  返回至少两条业务消息。
- 临时命令首次漏带 root API key 得到 `401`，随后按正式 smoke 的认证方式重试通过；
  未修改服务配置或测试代码。

## 9. 2026-08-21：真实 AGENT Tool Calling 与 SSE 通过

- 在隔离端口运行时显式声明真实 `grok-4.5` 的 legacy `openai` Tool Calling 能力，
  以保持生产 YAML 的保守默认不变；未修改仓库配置。
- 真实 `mode=AGENT` 非流式请求通过：模型调用 `searchKnowledge` 获取隔离文档后返回
  验证码；持久化 metadata 证明 `toolCalls=1`、`toolRounds=1`、`modelCalls=2`、
  `toolCallsByName.searchKnowledge=1`，并返回 1 个来源。
- 真实 `mode=AGENT` SSE 通过：事件包含 `tool_start`、`tool_result`、多段 `content`、
  `sources`、`done`；`done.metadata.executionBudget` 与非流式结果一致，答案包含隔离
  文档验证码。
- 首次 SSE 断言失败源于临时验收脚本错误读取内容字段；读取实际协议后按
  `choices[].delta.content` 修正断言并重跑通过，未发现生产实现缺陷。

## 10. 2026-08-21：真实摘要验证发现生产 Bean 注册缺陷

- 使用真实 Chat 连续执行多轮 `PLAIN` 会话时，所有请求都能回答，但 V46 摘要 metadata
  始终没有出现。
- 通过只读 PostgreSQL 查询确认这些业务历史行的 `owner_principal_id` 为 `NULL`；
  生产执行路径没有进入 `ChatSessionCoordinator.commit(...)`，而是回退到非 owner-scoped
  的兼容持久化路径，因此摘要服务的 owner/session 查询找不到历史。
- 根因定位为 `ChatSessionCoordinator` 自身使用 `@ConditionalOnBean` 组件条件，依赖的
  `JdbcChatMemoryRepository`/事务 bean 在组件扫描条件评估时尚未可见。该问题影响 owner
  隔离、协调提交、摘要压缩和清理语义，属于必须修复的生产正确性问题。
- 本次摘要请求使用了正确的 `RAG_CHAT_COMPACTION_*` 变量；此前错误的临时变量命名只导致
  一次“未启用摘要”的测试，不作为生产缺陷。

## 11. 2026-08-21：协调器注册修复

- 移除 `ChatSessionCoordinator` 的组件级 `@ConditionalOnBean`，避免依赖 bean 定义时序
  导致生产 bean 缺失；生产 `ChatExecutionService` 注入协调器改为必需依赖，避免再次静默
  回退到旧的无 owner 持久化路径。
- 新增 `ChatSessionCoordinatorBeanRegistrationTest`，用最小 Spring 上下文验证组件扫描后
  协调器可被注册；该测试通过。
- 这是本轮实质实现修复，后续必须用新编译产物重新启动服务并重新执行真实 owner/history/
  summary 验收；此前真实 AGENT/PLAIN/smoke 结果不作为修复后的最终结论。

## 12. 2026-08-21：修复后真实 owner/history/summary 验收通过

- 使用修复后的编译产物和真实 Chat 模型重新执行多轮 `PLAIN` 会话，响应在第 3–5 轮
  报告 summary `updated=true`，版本从 `1` 递增到 `3`，cursor 从 history `105` 前进到
  `107`。
- PostgreSQL 只读查询确认该 session 的 5 条业务 history 全部为 `COMPLETE`，且
  `owner_principal_id=root:environment-root`；V46 summary 行同 owner/session，最终为
  `version=3`、`summarized_through_history_id=107`、`estimated_tokens=94`。
- cursor 停在 `107`，保留最新两条 history（108/109）未压缩，符合最近 turn 保护策略。
- 中间一次终端断言错误地读取了 API 未公开的 owner 字段，并期待摘要 metadata 回写历史
  行；改用数据库只读查询后验证通过，未发现生产实现问题。

## 13. 2026-08-21：修复后真实 PLAIN 与协议边界回归通过

- 使用修复后的服务、真实 `grok-4.5` 和隔离 session 连续执行两轮 `mode=PLAIN`：
  第二轮准确返回第一轮写入的唯一 marker；两轮响应都报告 `sources=[]`、
  `metadata.retrievalExecuted=false`，历史接口返回至少两条记录。
- 发送 `mode=PLAIN` 且显式设置 `maxResults` 的请求在未触发 LLM 前返回 HTTP `400`，
  错误明确为 `RETRIEVAL_OPTIONS_NOT_ALLOWED`；证明 PLAIN 不会因客户端误传检索参数而
  偷偷执行 RAG。

## 14. 2026-08-21：修复后真实 AGENT 非流式与 SSE 回归通过

- 创建并嵌入隔离文档后，真实 `mode=AGENT` 非流式请求要求使用
  `searchKnowledge` 返回唯一 marker。答案包含 marker，来源仅指向隔离文档；
  `metadata.executionBudget` 证明 `toolCalls=1`、`toolRounds=1`、`modelCalls=2`，
  `toolCallsByName.searchKnowledge=1`。
- 同一场景重新执行真实 SSE：事件依次包含 `tool_start`、`tool_result`、多个
  `content`、`sources` 和 `done`；`done.metadata.executionBudget` 仍报告至少一次
  工具调用和两次模型调用，答案内容包含唯一 marker。
- 首次 SSE 自动化断言误将 `event:` 解析为必须带一个空格的 `event: `，未指向生产
  协议缺陷；按实际 SSE 行格式修正验收解析后重跑通过。临时 collection/document 已由
  `trap` 清理。

## 15. 2026-08-21：真实 context budget 保护路径通过

- 临时以隔离服务进程覆盖
  `RAG_CHAT_CONTEXT_FALLBACK_CONTEXT_WINDOW=120`、较小 output reserve/safety margin，
  发送 10,000 字符的 `mode=PLAIN` 请求。
- 请求返回 HTTP `422`，错误为 `CHAT_CONTEXT_BUDGET_EXCEEDED`；服务在规划 prompt
  阶段拒绝请求，没有产生模型调用。随后已停止临时进程并恢复正常真实 LLM 服务。

## 16. 2026-08-21：真实测试数据清理完成

- 通过带 `expectedDocumentRevision=2` 的硬删除接口清理本轮文档 `173–179`；本轮
  collection `66–72` 已由服务清理为 deleted 状态。
- 在一次 PostgreSQL 事务中精确删除本轮 12 个测试 session 的业务 history、summary、
  retrieval logs、lease 和对应 Spring AI memory。前后计数为：
  `history 25→0`、`summary 1→0`、`retrieval 26→0`、`lease 0→0`。
- 只读复核确认当前测试文档、测试 history 和测试 summary 均为 `0`；未使用广泛
  `LIKE` 删除，避免影响其他历史数据。

## 17. 2026-08-21：进入全量硬门槛验收

- 当前真实 LLM 服务已停止，隔离端口 `18081` 已释放。
- 特性分支仍保留全部未提交实现、测试、V46 migration、SQL demo 和进度账本修改；
  未使用 `stash`、强制回退或覆盖操作。
- 下一步按固定顺序执行：后端 `mvn clean compile test-compile` 与 SQL demo，
  前端 typecheck/build/核心 Mock Playwright；之后先合并最新 `origin/main`，再按合并后
  基线重新执行完整验收。

## 18. 2026-08-21：合并前基本集成硬门槛通过

- 后端 `mvn clean compile test-compile` 通过；API、documents、core、starter 全部成功
  编译，core 测试源码成功编译。
- reactor 安装 `mvn clean install -DskipTests` 通过；新增
  `demos/demo-tool-calling-sql` 测试 2/2 通过。
- 前端 `npm run typecheck`、`npm run build` 均通过。
- 核心 Mock Playwright 使用隔离 Vite 端口 `4198` 执行
  `e2e/chat.spec.ts` 与 `e2e/streaming-upload.spec.ts`，11/11 通过；端口原有服务已
  核验为当前 worktree 的 Vite 服务并复用，未终止其他项目进程。
