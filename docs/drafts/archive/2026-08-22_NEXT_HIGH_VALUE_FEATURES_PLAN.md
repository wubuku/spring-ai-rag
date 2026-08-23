# Chat turn 幂等、可靠重放与低基数观测实施规划

> **状态**：实施中
>
> **规划日期**：2026-08-22
>
> **代码基线**：`main` @ `e48fb192`，Spring Boot `3.5.16`，Spring AI `1.1.8`，
> Java `21`，Flyway V1–V46
>
> **规划分支**：`docs/next-high-value-features-plan-20260822`
>
> **当前 worktree**：`/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-chat-turn-idempotency`
>
> **实施分支**：`feat/chat-turn-idempotency-20260822`
>
> **配套进度**：[2026-08-22_NEXT_HIGH_VALUE_FEATURES_PROGRESS.md](2026-08-22_NEXT_HIGH_VALUE_FEATURES_PROGRESS.md)

本规划是下一轮实施的单一恢复入口。它冻结本轮要解决的问题、推荐默认、数据与 HTTP
契约、文件顺序、验收矩阵和明确非目标。实现者不应因为会话中断而重新猜测核心设计。

近距离上下文：

- [Chat 记忆、RAG 与工具调用调研](../../chat-memory-rag-tool-calling-zh-CN.md)
- [项目上下文](../../project-context-zh-CN.md)
- [REST API 参考](../../rest-api-zh-CN.md)
- [SSE 协议](../../SSE-PROTOCOL.md)
- [测试指南](../../testing-guide-zh-CN.md)
- [规划、实施与验收工作流](../../delivery-workflow-zh-CN.md)

## 1. 执行摘要

本轮只处理 Chat 的**请求级幂等、完成结果重放和低基数运维观测**。核心目标是让
客户端在 HTTP 超时、代理断线或 SSE reader 被取消后，可以安全地重试同一个逻辑 turn，
而不会因为服务端已经完成了模型调用又重复调用模型、重复写入历史或重复计费。

P0 是 durable turn operation：

```text
可选 Idempotency-Key
  -> principal + key hash 唯一寻址
  -> transport-neutral request fingerprint
  -> CAS/lease 获取执行权
  -> ChatExecutionService 执行一次逻辑 turn
  -> history + Spring AI Memory + operation 成功快照同事务提交
  -> 后续相同请求直接重放快照
```

P1 是低基数观测：记录 turn 的结果、传输方式、模式、耗时和固定预算原因，但不把
session、key、prompt、用户正文、工具参数或工具结果放进 metrics tag。

本轮不改变已有 Chat 三模式，不把 `KNOWLEDGE` 改造成 Function Calling，也不开放
客户端自定义工具。`EACH_COLLECTION` 仍留在独立 backlog，不与 turn 可靠性混合实施。

## 2. 当前事实与问题

以下事实已从当前代码、迁移和测试交叉核对：

1. 原生 Chat 入口是 `POST /api/v1/rag/chat`、`POST /api/v1/rag/chat/ask` 和
   `POST /api/v1/rag/chat/stream`；OpenAI 兼容入口是
   `POST /v1/chat/completions`。
2. 原生 JSON/SSE 入口先经过 `RagChatService`；原生 JSON 在 mode-aware execution
   service 和 command mapper 可用时委托 `ChatExecutionService`，否则仍有 legacy
   `executeChat` fallback；当前原生 SSE 的 `chatEvents` 则要求 mode-aware 依赖存在，
   不会自动切到 legacy stream。OpenAI 兼容入口直接调用 `ChatExecutionService`。
   Spring AI Memory、检索、Tool Calling、模型 fallback 和 session lease 只在
   mode-aware 路径上统一；因此带 key 的请求必须显式拒绝 legacy fallback，不能悄悄退回
   非幂等路径。
3. `ChatSessionCoordinator` 的 lease 提供 principal/session single-flight、续租、
   token fencing 和完成提交协调，但它只解决并发执行，不识别“同一客户端重试的同一
   逻辑请求”。
4. `rag_chat_history` 当前只保存已完成业务 turn，V32 的 `turn_status` 只有
   `COMPLETE` 和 `CANCELLED`。没有 durable `IN_PROGRESS` turn，也没有完成结果重放表。
5. 原生 SSE 客户端取消会 dispose Reactor subscription；未完成 turn 不写 history 和
   Spring AI Memory。客户端重试时没有协议级方式知道服务端是否已经在取消前完成。
6. WebUI `useSSE` 当前不发送 `Idempotency-Key`，也没有安全重试或 turn 状态查询。
7. 原生 `RagChatController` 在进入服务前会为缺省 `sessionId` 写入随机 UUID；
   `ChatCommandMapper` 和 `ChatCommand` 的现有构造路径也会通过
   `SessionIdValidator.resolve` 生成随机 session。OpenAI mapper 当前为每个请求随机生成
   `oai-*` session，OpenAI compatibility controller 尚未接收或传播幂等 header。若不在
   operation claim 前保留“请求声明中是否带 session”的信息，同一无 session 请求重试时
   会得到不同 fingerprint 和 session。
8. V44 的外部文档 operation 已证明本仓库可以使用 fingerprint、CAS、版本化响应快照和
   replay ACL，但 Chat operation 必须建立独立表和独立语义，不能复用文档 relocation
   的状态机。
9. 当前实现禁止显式悲观锁、`SKIP LOCKED` 和 advisory lock；V39/V44/V46 的条件 DML、
   CAS 和 lease 是本轮应沿用的并发模式。

当前真正的风险不是“同一 session 同时执行”而是：

- 网络失败发生在服务端 LLM 调用之后、响应提交之前，客户端无法判断是否可以重试；
- 同一用户重复发送同一请求可能产生两次 provider 调用和两个历史 turn；
- SSE 断线后的盲目重试会重复计费；
- 若将幂等状态塞进 `rag_chat_history`，会污染现有 `COMPLETE/CANCELLED` 业务语义；
- 没有低基数的 replay/conflict/in-progress 计数，无法判断重试风暴或 provider 失败影响。

## 3. 目标、非目标与完成定义

### 3.1 目标

1. 支持原生 JSON、原生项目 SSE 和 OpenAI 兼容 JSON/SSE 使用同一套 transport-neutral
   turn operation。
2. 不带 key 的旧客户端保持现有行为；带 key 的客户端获得明确的重复请求语义。
3. 同一 principal 下同一 key 只能绑定一个规范化请求 fingerprint。
4. 成功 operation 保存版本化响应快照，后续 replay 不再次调用模型、不新增历史 turn。
5. 执行中的重复请求返回可自动识别的 `409 IDEMPOTENCY_OPERATION_IN_PROGRESS` 和
   `Retry-After`。
6. stale worker 可以通过 lease expiry + CAS 被后续请求安全接管；活跃 worker 不能被
   第二个请求夺取。
7. 成功提交时 operation、业务 history、Spring AI Memory 和 session lease 的状态变更
   在同一短事务内完成。
8. 对会读取文档的 `KNOWLEDGE`/`AGENT` replay，重新检查当前 principal 以及 operation
   保存的有效 Collection 授权边界；`PLAIN` 不建立或复核 Collection 授权边界。
9. 提供不泄漏原始 key 的 opaque `turnId` 状态查询。
10. 以一次性设计好的 PostgreSQL/HTTP/SSE/WebUI/真实 provider 验收矩阵覆盖本轮代码。

### 3.2 非目标

- 不改变 `PLAIN / KNOWLEDGE / AGENT` 的模式和检索语义。
- 不把 `KNOWLEDGE` 检索改成 Function Calling；文档检索仍由 Spring AI Modular RAG
  或 AGENT 的服务端工具负责。
- 不实现 token 级 SSE 续传、`Last-Event-ID` 半流恢复或 provider 内部请求去重。
- 不新增持久化 Chat cancel API。keyed SSE 的浏览器 `stop`、reader cancel、代理断线和
  emitter timeout 只停止当前连接的事件投递，不把 durable operation 终结为取消；
  operation 继续由服务端协调器持有并完成或失败，之后可通过同一 key/status 恢复。
- 不保证“LLM 已返回但数据库进程在提交前崩溃”这一极窄窗口的 exactly-once provider
  调用；该窗口通过 operation lease reclaim 被显式记录为 at-least-once。
- 不让客户端提交 `tools`、`functions`、SQL 或任意 provider 参数。
- 不把 prompt、原始 Idempotency-Key、工具参数/结果、API key 或完整请求正文持久化到
  operation 表。
- 不引入新的会话摘要、上下文压缩、检索质量、`EACH_COLLECTION` 或多租户模型。
- 不把高基数 session、turn、trace、key hash 或 document ID 作为 metrics tag。

完成定义：本规划的实现只有在新增 API/数据库/前端契约、PostgreSQL 集成测试、Maven
门槛、前端构建与 Mock Playwright、隔离端口全栈验证、必要的真实 LLM 验证以及实现
`3/3` 收敛检查全部通过后，才可报告完成。

## 4. 冻结的公共契约

### 4.1 `Idempotency-Key`

- Header 名固定为 `Idempotency-Key`，大小写不敏感。HTTP 层先移除 header value
  两端的 OWS；请求必须恰好包含一个该 header（多行 header 或逗号拼接值都按非法处理）。
  规范值必须是 1–255 个可见 ASCII 字符，但明确排除逗号；不允许内部空白、控制字符或
  换行。排除逗号是为了让 servlet/proxy 把重复 header 合并为逗号列表时仍能 fail closed，
  避免把多值请求误当成一个合法 key。
- 服务端按这个规范值以 UTF-8 字节计算 SHA-256；
  只持久化 64 位小写十六进制 hash，不持久化原值。
- key 的作用域是认证后的 `principal.id + key hash`，不是 session。相同 key 在不同
  session 上使用会得到 fingerprint conflict，而不会越过 principal 隔离。
- header 缺失时不创建 operation，保持旧客户端的非幂等兼容语义。
- key 只用于客户端重试同一个逻辑 turn；想要有意发起新 turn 必须生成新 key。

### 4.2 Fingerprint

在 controller 完成认证、请求语法校验和请求声明规范化后，对一个独立的
transport-neutral canonical input（不是尚未完成 scope 解析的 `ChatCommand`）生成
fingerprint。这个 fingerprint 必须先于依赖当前 ACL/Collection 存在性的 scope 解析，
用于稳定寻址已有 operation；首次 claim 只有在当前 scope 解析成功后才开始执行。规范化
输入为版本化 canonical JSON，字段固定包括：

```text
schemaVersion
principal-independent command fields:
  declared sessionId（已验证的显式值，或 `AUTO_SESSION` 标记；不是 claim 前随机生成的
  sessionId）
  message
  inputMessages
  declared mode（原生稳定默认值可归一化；OpenAI alias 默认使用固定的 `DEFAULT` marker）
  declared memory override（显式值或固定的 `DEFAULT` marker）
  declared model identifier（原生 model 字符串或 OpenAI public alias）
  domainId
  declared retrieval scope marker
  declared retrieval options（保留显式/未提供语义，不展开当前服务端默认值）
  retrievalFilters
  declared collection scope:
    NOT_APPLICABLE for PLAIN, CALLER_VISIBLE marker, ANY_COLLECTION marker,
    or sorted explicit IDs/keys
  document IDs
  clientMetadata (行为 metadata，不包含 transport/trace 字段)
```

`declared retrieval scope marker` 对 `PLAIN` 模式固定为
`NOT_APPLICABLE`。`PLAIN` 不读取 Collection、文档或检索授权范围，即使原生
controller 为了兼容请求解析出默认 scope，也不能把当前 caller allow-list 纳入
fingerprint 或 replay ACL；`KNOWLEDGE` 和 `AGENT` 才使用
`CALLER_VISIBLE`、`ANY_COLLECTION` 或显式 Collection IDs/keys 标记。

`clientMetadata` 当前会进入 prompt customizer、system prompt 和最终 response metadata，
因此必须递归排序并纳入 fingerprint；实现不得把它误当成日志 metadata。以下字段明确
排除：transport 名称、HTTP trace ID、MDC、随机 completion ID、原始 Idempotency-Key、
API key、时间戳、日志 metadata 和 provider 诊断字段。

当前 `OpenAiChatRequestMapper` 会向 command metadata 写入 transport 和
`openaiModelAlias`。这些字段是 transport/协议诊断信息，必须在进入 transport-neutral
`clientMetadata` fingerprint 前移出或明确排除；实现还必须保证它们不再进入
`PromptCustomizerChain`、system/user prompt 或不可变业务 response metadata。OpenAI
协议 envelope 需要的 alias、transport 和 completion 字段从 declaration/envelope context
或 operation snapshot 单独生成；只有真正影响 prompt、检索或公开业务响应的客户端
metadata 才参与 fingerprint。

规范化规则：

1. object key 按字典序递归排序，使用 UTF-8、无空白 JSON；
2. 稳定的公开协议默认值（例如原生 `mode=KNOWLEDGE`）在显式提供与省略时统一；
   依赖 alias registry、domain extension 或可变部署配置的默认值保留固定
   `DEFAULT` marker，不能读取当前解析值后再写入 fingerprint；
3. 原生 Chat 没有公开多消息列表；其 canonical `inputMessages` 固定映射为一个
   `user` 消息，内容等于 canonical `message`。OpenAI 请求保留规范化后的完整消息顺序；
   因而只有语义上相同的单条 user 请求才能跨 native/OpenAI replay，带 system/assistant
   历史的 OpenAI 请求不会被错误地当作原生请求；
4. `inputMessages` 保持语义顺序；model candidate chain、alias registry 解析结果、
   domain 默认值和其他服务端派生配置不进入 fingerprint；
5. Explicit selected Collection IDs/keys 按请求中声明的语义去重并排序；同一请求同时
   声明 ID 与 key 时保留两者，仍由现有 scope resolver 验证它们指向同一 Collection；
   `CALLER_VISIBLE` 和 `ANY_COLLECTION` 使用固定 scope marker，不能把当前 caller
   的 allow-list 或 Collection 存在性展开结果写入 fingerprint；filters 的 object key
   递归排序；`documentIds` 去重后按数值升序规范化，因为它们只是检索过滤集合而不
   表示响应排序；
6. 文本不做大小写折叠，不做隐式 trim，不改变用户正文；
7. canonical JSON 只在内存中生成，持久化只保存 SHA-256；
8. 可选的语义字符串沿用现有请求语义规范化；其中空白 `domainId` 等同于未提供并规范
   为 null，不能因为 controller 与 mapper 的空白处理差异产生 fingerprint conflict；
9. OpenAI 的公开 `model` alias 是声明语义的一部分，不是 transport metadata；它应进入
   `declared model identifier` 的规范值，`openaiModelAlias` 仅作为被排除的诊断 metadata。
   不同 alias 即使当前 registry 恰好映射到相同 candidate，也视为不同逻辑请求并返回
   `IDEMPOTENCY_KEY_REUSED`，不假设 alias 可互换；
10. fingerprint 版本变化必须改变 `schemaVersion`，不得静默复用旧 hash。

实现必须先产生一个不触发随机 session 分配的 transport-neutral request envelope：
原生 controller 不得在 operation lookup 前改写缺省 `sessionId`，OpenAI mapper 不得在
lookup 前生成 `oai-*` session，`ChatCommand` 也不得把 `AUTO_SESSION` envelope 直接交给
`SessionIdValidator.resolve`。operation 首次 claim 时才生成并持久化合法 session；之后
再用该 session 构造真正执行用的 `ChatCommand`。显式 session 仍须在 lookup 前完成同样的
格式校验。OpenAI body/header 的 scope 解析也必须拆为“读取并规范化声明”与“按当前 ACL
解析有效 scope”两步：已有 operation 先使用前者完成 lookup，首次 claim 前才执行后者。

带 key 的请求对 `clientMetadata` 使用与现有请求相同的对象语义，但必须在 claim 前限制
canonical JSON 大小，并拒绝控制字符、循环结构和以下不区分大小写的 credential 字段：
`apiKey`、`authorization`、`token`、`secret`、`password`、`rawKey`、`accessToken`、
`refreshToken`。上限固定为 32 KiB；超过上限返回 `400 IDEMPOTENCY_REQUEST_TOO_LARGE`，
不创建 operation。operation 不另存一份原始 request metadata，只保存 fingerprint 和最终公开
response snapshot；snapshot 复用现有 ChatResponse 的字段边界，不新增 prompt、工具
参数或服务端凭据字段。

没有显式 session 的原生 Chat 请求使用 `AUTO_SESSION` 参与 fingerprint。第一次 claim
时只生成一次 UUID v4 字符串作为候选 session（小写、带连字符、长度 36，必须通过现有
`SessionIdValidator`），取得适用 lease 后持久化；后续相同 key 的请求复用 operation
中保存的 session，不能重新随机生成。`MemoryMode.STATELESS` 也使用这个稳定 operation
session 字段，但不取得 session lease。OpenAI compatibility mapper 必须把当前请求映射
成同一 `AUTO_SESSION` 规则，不能继续用“每次 mapper 调用都无条件随机 session”破坏 replay。

fingerprint 只识别客户端声明的逻辑请求，不重新读取当前模型 alias registry、domain
extension 或服务端默认配置。首次 claim 在当前认证和 scope 校验通过后，必须在调用
provider 前写入一个受控的 immutable `execution_snapshot`，至少包含已解析的 mode、
memory mode、model candidate chain、公开的 declared model identifier、retrieval
options、domain routing 和 scope 解析结果，但不包含 prompt、原始 key、工具参数/结果
或凭据。该快照必须有独立的 UTF-8 序列化大小上限（默认 64 KiB，可逆范围
16–256 KiB），并通过与 response/error snapshot 相同的 credential/正文敏感字段扫描；
超限或不安全时在 provider 调用前以稳定的
`IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID` 失败终态结束，不能写 history/Memory。
它必须使用显式 `executionSnapshotVersion=1` 的 JSON allow-list，而不是序列化任意
`ChatCommand`、domain 对象或 scope 实例；允许的顶层字段仅为
`mode`、`memoryMode`、`declaredModelIdentifier`、`resolvedCandidates`、
`domainId`、`retrievalOptions`、`effectiveScope` 和必要的
server-side routing flags，且每个字段的类型、枚举和值域都要固定。不得把 prompt、
`inputMessages`、`clientMetadata`、retrieval filter 原值、原始 key、API key、caller
allow-list、工具参数/结果或请求/trace metadata 写入该快照；重试请求继续从当前请求声明
取得这些客户端输入和 filter，fingerprint 相同才允许使用，
仅从快照恢复首次 claim 时解析出的服务端执行配置。授权证据单独存于
`authorization_scope_snapshot`，不能通过 execution snapshot 绕过 replay ACL。
stale reclaim 和同一 operation 的恢复执行使用这个 snapshot，而不是在后续请求中重新
解析可能已经变化的 registry/defaults；OpenAI replay 的 `model` 字段也必须从这里恢复，
不能从当前 registry 反推。如果首次 claim 尚未成功写入 snapshot 就发生未知崩溃，恢复
只能按有界 attempt 规则重新建立一次 snapshot，不能静默声称与首次执行完全相同。

已有 operation 的物理 lookup 必须先按当前 principal + key hash 读取唯一 operation
行，再比较声明语义 fingerprint；不能把 fingerprint 放进唯一寻址条件而让同 key
不同请求看起来像“没有 operation”。fingerprint 不同必须在 ACL/Collection 解析前返回
`IDEMPOTENCY_KEY_REUSED`。fingerprint 相同后，对 `KNOWLEDGE`/`AGENT` 再重新解析当前
Collection scope/ACL。因此 Collection
被删除、key 不再可解析或权限收窄时，不能在 operation lookup 前直接返回普通 Collection
404，也不能把 ACL 变化误判为 `IDEMPOTENCY_KEY_REUSED`，而应按 replay 的 fail-closed
规则返回 `403 FORBIDDEN`。`PLAIN` 的 `NOT_APPLICABLE` scope 不执行这项 Collection
ACL 复核。首次请求的认证、语法和当前 scope 校验仍在创建 operation 前完成，不为无效
请求留下 operation。

对带 key 的请求，lookup 前只能执行认证、HTTP/JSON 结构校验、公开字段的语法与大小
规范化，以及不依赖当前服务端 registry 的声明 envelope 构造。OpenAI `model` alias、
mode/memory override 的兼容语法和原生 `domainId`/model 字符串格式可以校验，但不能在
已有 operation lookup 前要求 alias registry、domain extension registry、当前部署默认值
或 candidate chain 仍然存在；已有 operation 在 fingerprint 相同且 replay ACL 通过时，
必须从 immutable `execution_snapshot` 恢复公开 envelope。只有首次 claim，或 snapshot
尚未建立且按有界 reclaim 规则允许恢复时，才解析当前 alias/domain registry 并写入
snapshot；解析失败则不创建 operation，或按稳定的执行快照错误终止。无 key 的旧路径
可以继续沿用现有即时解析和 circuit-breaker 行为。

`PLAIN` 的 scope 规则必须在 transport-neutral envelope 阶段统一执行：原生请求若显式
提供 `collectionScopeMode`、`collectionIds`、`collectionKeys`、`documentIds` 或
`filters`，以及 OpenAI 请求若提供 `rag.scope`、`rag.filters` 或
`X-RAG-Collection-Key`，都按现有 `RETRIEVAL_OPTIONS_NOT_ALLOWED`/对应 OpenAI
`unsupported_parameter` 语义拒绝；不得为了产生这个错误而调用 Collection resolver、
查询文档或读取 API key 的 Collection allow-list。原生请求显式覆盖
`maxResults/useHybridSearch/useRerank` 也继续沿用现有 PLAIN 非法参数语义。
`rag.openai-compatibility.require-explicit-scope` 只对 `KNOWLEDGE`/`AGENT` 生效，
不能让 PLAIN 因没有无意义的 Collection scope 而失败。

幂等 coordinator 的 operation lookup/replay 必须先于现有
`RagChatService.assertCircuitBreakerAllowsCall()` 及其他仅保护 provider 执行的可用性
门禁；已成功的 snapshot replay 不得因为当前 circuit breaker 打开、当前模型 provider
暂时不可用或当前候选链不可解析而被拦截，也不得增加 provider counter。首次 claim 和
stale reclaim 仍必须经过这些执行门禁，执行失败按本节状态机落为稳定 FAILED 或保持可
接管的 IN_PROGRESS。OpenAI 兼容路径也必须遵循同一顺序，不能在 declaration lookup
之前解析 alias/candidate 或创建 diagnostics session。

### 4.3 状态与错误

公开错误码沿用 API `ErrorCode`：

| 场景 | HTTP | code | 语义 |
|---|---:|---|---|
| `Idempotency-Key` 为空、含逗号/控制字符/内部空白或超过 255 字符 | 400 | `IDEMPOTENCY_KEY_INVALID` | 不创建 operation |
| 同 key 不同 fingerprint | 409 | `IDEMPOTENCY_KEY_REUSED` | 不执行新 turn |
| `clientMetadata` 超过幂等请求限制 | 400 | `IDEMPOTENCY_REQUEST_TOO_LARGE` | 不创建 operation |
| `clientMetadata` 含控制字符、循环结构或 credential 字段 | 400 | `IDEMPOTENCY_REQUEST_METADATA_INVALID` | 不创建 operation |
| 已有活跃 operation | 409 | `IDEMPOTENCY_OPERATION_IN_PROGRESS` | 不执行新 turn，带 `Retry-After` |
| operation/turn 不属于当前 principal | 404 | `CHAT_TURN_NOT_FOUND` | 不泄漏存在性 |
| 当前 replay 的 Collection ACL 已失效 | 403 | `FORBIDDEN` | 不返回旧 response |
| 带 key 但功能被运维开关关闭 | 503 | `IDEMPOTENCY_DISABLED` | 不静默退回非幂等执行 |
| 首次解析出的执行快照超限或包含敏感字段 | 503 | `IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID` | 不调用 provider，不写 history/Memory |
| 首次成功结果无法构造权威授权来源快照 | 503 | `IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID` | operation 进入 FAILED，不写 answer/history/Memory |
| 成功结果无法在 snapshot 大小上限内持久化 | 503 | `IDEMPOTENCY_RESPONSE_TOO_LARGE` | operation 进入 FAILED，不写 history/Memory |
| stale reclaim 达到 operation attempt 上限 | 503 | `IDEMPOTENCY_ATTEMPTS_EXHAUSTED` | 只重放稳定失败，不再调用 provider |
| operation 已成功 | 200 | 无错误 | 返回同一个版本化快照 |

`IDEMPOTENCY_OPERATION_IN_PROGRESS` 的 native Problem JSON、OpenAI error envelope 和
SSE claim 前的 HTTP 错误都必须设置 `Retry-After`。数值由 operation coordinator 根据
当前 operation lease 的剩余时间计算，向上取整为秒，最小为 `1`、最大为 `60`；不得
写死成与实际 lease 无关的常数，也不得把 key、prompt 或内部 token 放入错误 body/header。
coordinator exception 必须携带已计算的整数秒，两个 exception handler 通过共享
header-mapping helper 写入 `Retry-After`，不能从错误 message、数据库文本或异常堆栈
推断数值。`GlobalExceptionHandler` 与 `OpenAiCompatibilityExceptionHandler` 必须共享
这一映射规则。

如果执行在 operation 已 claim 后失败，服务端保存一个不含敏感正文的版本化稳定错误
快照并进入 `FAILED`；相同 key 后续重放同一失败，不自动重复 provider 调用。未进入
operation claim 的认证、参数和 scope 校验错误不创建 operation。故障恢复若需要重新
尝试，客户端应生成新 key。

稳定错误快照只保存 transport-neutral 的 HTTP status、`ErrorCode`、`retryable` 和固定
诊断字段，不保存首个请求的 RFC 7807/OpenAI/SSE envelope。终态 `FAILED` 在后续请求
lookup 时、SSE 200/header 提交前，按**当前** transport 投影：native JSON/SSE 返回
native Problem JSON，OpenAI JSON/SSE 返回 OpenAI error envelope；两者保持同一个
status/code，且不重新调用 provider。首次 SSE 若已开始传输后才失败，当前连接仍发送
项目 SSE `error` 或 OpenAI 兼容 error chunk；之后的同 key 请求则使用上述同步 HTTP
错误投影。status API 只公开稳定 `errorCode`，不返回 provider 原始错误正文。

失败终态必须区分两类：provider/模型/预算等已经明确得到稳定业务错误的执行失败可以
进入 `FAILED` 并保存固定错误快照；数据库提交、lease fencing、序列化或进程级未知故障
若不能证明稳定错误快照已经安全落库，则保持可接管的 `IN_PROGRESS`，由 lease expiry
恢复，不能先返回或持久化一个可能与 history/Memory 不一致的成功/失败终态。客户端
连接消失不是业务失败：keyed SSE 的执行生命周期必须与单个 emitter/subscriber 解耦，
断线只停止向该连接投递，operation 继续执行、续租并尝试 coordinated commit。普通 JSON
请求的客户端超时同样不推断服务端执行已取消。

## 5. Durable operation 设计

### 5.1 表与字段

新增 Flyway V47，创建 `rag_chat_turn_operations`，不改写 V32，不扩展
`rag_chat_history.turn_status`：

| 字段 | 约束/用途 |
|---|---|
| `id` | `BIGSERIAL PRIMARY KEY`，内部 operation identity |
| `owner_principal_id` | `VARCHAR(128) NOT NULL`，principal 隔离 |
| `idempotency_key_sha256` | `CHAR(64) NOT NULL`，只存 hash |
| `request_fingerprint_sha256` | `CHAR(64) NOT NULL`，规范请求 hash |
| `fingerprint_version` | 正整数，`NOT NULL` |
| `session_id` | `VARCHAR(36) NOT NULL`，operation 分配/复用的会话 |
| `turn_id` | `UUID NOT NULL`，首次 operation 建立时生成的对外 opaque identity；进行中和所有终态保持不变 |
| `transport` | `NOT NULL`；首次 claim 请求的固定枚举 `NATIVE_JSON/NATIVE_SSE/OPENAI_JSON/OPENAI_SSE`；跨 transport replay 不改写此字段，当前请求 transport 只用于 adapter、HTTP envelope 和 metrics |
| `status` | `NOT NULL`；`IN_PROGRESS/SUCCEEDED/FAILED` |
| `operation_token` | 当前执行权 token，随机 opaque；`IN_PROGRESS` 必须非空，终态必须为 `NULL` |
| `lease_expires_at` | 可回收执行租约；`IN_PROGRESS` 必须非空，终态必须为 `NULL` |
| `attempt_count` | `NOT NULL` 有界整数，用于诊断，不参与业务 fingerprint |
| `row_version` | `BIGINT NOT NULL DEFAULT 0`；每次 claim、reclaim、lease renewal 和终态转换成功时递增，用于 operation 行 CAS |
| `response_version` | `NOT NULL` 正整数；当前固定为 `1` |
| `execution_snapshot` | 首次 claim 后写入的服务端执行解析快照；恢复执行使用，不含 prompt/凭据；首次快照建立失败时可为 `NULL` |
| `response_payload` | 成功时的版本化 transport-neutral 业务结果 JSONB；仅 `SUCCEEDED` 非空，不得持久化首个 transport 的协议 envelope，当前 adapter 按请求 transport 重新投影 |
| `error_code` / `error_payload` | `FAILED` 时的 transport-neutral 稳定错误快照，不含正文；成功和进行中均为 `NULL` |
| `authorization_scope_snapshot` | replay 前重新校验的 scope/ACL 边界 JSONB，不保存 key |
| `created_at/updated_at/completed_at` | 服务端时间戳；`created_at/updated_at` 非空，`completed_at` 仅终态非空 |

V47 必须冻结 PostgreSQL 物理类型和约束，实施时不能用宽泛字符串替代：`id` 使用
`BIGSERIAL PRIMARY KEY`；principal、两个 hash、fingerprint/attempt/response version、
session、turn、transport、status、row version、authorization snapshot 和
`created_at/updated_at` 全部 `NOT NULL`。principal 使用 `VARCHAR(128)`；两个 hash 使用
`CHAR(64)` 并以小写十六进制 CHECK 约束；`fingerprint_version`、
`attempt_count`、`response_version` 使用有界正整数；
`session_id` 使用 `VARCHAR(36)` 并复用现有 session 字符集 CHECK；`turn_id` 使用
`UUID`，`operation_token` 使用 `UUID`；`transport`、`status`、`error_code` 使用有限长度
`VARCHAR` 加枚举 CHECK；租约和时间使用 `TIMESTAMPTZ`；execution、响应、错误和授权
快照全部使用 `JSONB`。`operation_token`、`lease_expires_at`、`execution_snapshot`、`response_payload`、
`error_code`、`error_payload` 和 `completed_at` 按上述状态语义可空；其中终态 token/lease
必须为 `NULL`，成功 response、失败 error 和 `completed_at` 必须非空。
`authorization_scope_snapshot` 不使用空对象默认值：首次 operation 只在 scope
校验成功后创建，并立即写入合法的 v1 基础授权快照；其内容按 scope mode 约束为固定
结构。所有 NOT NULL、状态组合和 JSON version 约束必须在 PostgreSQL 集成测试中直接
断言，而不能只依靠 Java validator。

约束和索引：

- `(owner_principal_id, idempotency_key_sha256)` 唯一；
- `turn_id` 全局唯一；
- `rag_chat_history.turn_id` 增加非空 partial unique index，确保一个 durable
  operation 最多对应一条业务 history；旧的 `NULL` turn_id 行不参与唯一性约束；
- `rag_chat_history.turn_id` 不建立指向 operation 表的外键：operation 按 24 小时窗口
  清理而 history 保留更久，外键会阻断合法 cleanup 或迫使历史行丢失 turn identity；
- `status`、`lease_expires_at`、`updated_at` 建维护索引；
- `row_version` 必须非负；`response_version` 是响应快照 schema 版本，不能替代
  operation 行的 `row_version`；
- `IN_PROGRESS` 必须有非空 token 和非空 lease 时间；lease 可以已过期，过期的
  `IN_PROGRESS` 正是 stale reclaim 的输入，不能在数据库 CHECK 中要求
  `lease_expires_at > now()`；`SUCCEEDED/FAILED` 不再拥有执行 token；
- `SUCCEEDED` 必须有 response payload 和 completed time；
- `SUCCEEDED` 必须有已建立的 `execution_snapshot`；
- `FAILED` 必须有稳定 error code/payload；
- `response_payload` 不允许保存原始 prompt、工具参数、工具结果或 key；
- `response_payload` 序列化后的 UTF-8 大小默认不超过 512 KiB，可逆配置边界为
   64 KiB–2 MiB。超过上限时，在写入 history/Memory 前把 operation 记录为
   `FAILED/IDEMPOTENCY_RESPONSE_TOO_LARGE`，保存稳定错误快照，不留下业务 history；
- `error_payload` 也必须使用固定字段和有限大小：顶层必须包含
   `errorSnapshotVersion=1`，默认序列化后不超过 16 KiB，只允许稳定 `httpStatus`、
   `errorCode`、`retryable`、`attempt` 等诊断字段，不得保存 provider 原始错误、prompt、
   工具参数/结果、请求正文或凭据；超限统一降级为不含正文的 `INTERNAL_ERROR` 错误快照；
- operation retention 默认 24 小时，可配置为 1–168 小时；仅清理终态过期行。TTL 到期后
  operation replay/status 不再可用，但保留的 `rag_chat_history` 仍可通过 session history
  查询；history 不反向延长 operation retention。claim 遇到已过期终态时，必须用
  `completed_at + TTL` 条件删除并重新走唯一键 claim；删除失败则重新读取并按当前状态
  返回，不能无条件覆盖或复用旧 snapshot。并发 cleanup 与 claim 也只能通过条件 DML/CAS
  协调。

`attempt_count` 默认最多允许 3 次 operation execution attempt，可逆配置边界为
1–8；首次 claim 计为第 1 次。stale operation 已达到上限时，不再取得新执行权或调用
provider：coordinator 必须先按 operation 的 principal/session 确认不存在仍有效的 session
lease，再使用旧 `status/token/lease_expires_at/row_version` 和 lease 已过期条件直接 CAS
为 `FAILED/IDEMPOTENCY_ATTEMPTS_EXHAUSTED`。若仍有有效 session lease，则保持
`IN_PROGRESS` 并返回 in-progress/busy，不能抢先写失败终态。CAS 失败后重新读取当前状态；
CAS 成功后，后续相同 key 只重放该稳定错误。这个上限只保护 operation lease 恢复，不
替代一次执行内部已有的模型、候选、工具和 deadline 预算。

`rag_chat_history` 新增 nullable `turn_id UUID` 和 owner/turn 索引。旧行保持 null；
新成功 turn 必须写入 operation 的 `turn_id`。history 仍只使用 `COMPLETE/CANCELLED`，
不得把 `IN_PROGRESS` 写进业务 history。

`authorization_scope_snapshot` 至少包含以下服务端派生字段：

```json
{
  "authorizationSnapshotVersion": 1,
  "scopeMode": "NOT_APPLICABLE|CALLER_VISIBLE|ANY_COLLECTION|SELECTED_COLLECTIONS",
  "callerAccessMode": "NOT_APPLICABLE|UNRESTRICTED|RESTRICTED",
  "effectiveSelectedCollectionIds": [1, 2],
  "callerAllowList": [1, 2],
  "unassignedDocumentsAllowed": true,
  "sourceCollectionIdsObserved": [1, 2],
  "sourceDocumentCollectionSnapshot": [
    {"documentId": 101, "collectionId": 1},
    {"documentId": 102, "collectionId": null}
  ]
}
```

首次 INSERT 时就必须保存 `authorizationSnapshotVersion=1`、scope mode、caller access
mode、effective selected IDs、首次 allow-list 和 unassigned 规则；此时来源数组为空。
`IN_PROGRESS/FAILED` 可以保留该基础快照，`SUCCEEDED` 必须在同一个 coordinated commit
中补齐 `sourceCollectionIdsObserved` 和 `sourceDocumentCollectionSnapshot`。快照更新
只能由当前 operation token/row version 的成功提交完成，不能由 HTTP adapter 事后改写。
所有 Collection ID 数组都按正整数数值排序、去重；document mapping 按正整数
`documentId` 排序、去重并保证每个 document 只出现一次，不能依赖检索返回顺序。

当 `scopeMode` 为 `NOT_APPLICABLE` 时，其余范围字段必须保存为空数组、`false` 或
等价的固定空值，`callerAccessMode` 必须为 `NOT_APPLICABLE`；replay 不查询
Collection 或文档 ACL。`unassignedDocumentsAllowed` 的值按 resolved scope 固定：
只有 unrestricted `CALLER_VISIBLE` 为 `true`；restricted `CALLER_VISIBLE`、
`ANY_COLLECTION`、`SELECTED_COLLECTIONS` 和 `NOT_APPLICABLE` 全部为 `false`。
`CALLER_VISIBLE`/`ANY_COLLECTION` 的首次授权证据必须明确记录
`UNRESTRICTED` 或 `RESTRICTED`；restricted 时 `callerAllowList` 保存首次有效的
allow-list，unrestricted 时保存空数组。replay 若发现首次为 `UNRESTRICTED` 而当前
调用方已变为 restricted，必须 fail closed；首次为 `RESTRICTED` 时当前 allow-list
必须至少覆盖保存的原始集合，不能因为当前权限变宽而扩大旧答案的可见范围。
`SELECTED_COLLECTIONS` 的 `callerAllowList` 只保存本 operation 的有效 selected
Collection IDs，不展开或持久化与本 operation 无关的权限；replay 重新验证全部 selected
和 observed source Collection 当前仍 active 且可读即可。首次 unrestricted 后当前变为
restricted，只要当前 allow-list 仍覆盖这些 operation-relevant IDs 就允许 replay；
丢失无关 Collection 的权限不能阻断 replay。其 `callerAccessMode` 只作首次授权证据，
不能替代 selected/source Collection 的当前 active/ACL 检查。

replay 必须重新用当前 principal/API key 解析授权边界，并证明当前调用方仍能读取
snapshot 中所有 operation-relevant 原始有效范围和已观察来源；对
`CALLER_VISIBLE`/`ANY_COLLECTION` 从 unrestricted 变为 restricted、对 selected scope
失去任一 selected/source Collection、或无法证明来源权限时一律 fail closed 返回
`FORBIDDEN`。当前权限变宽不需要重算旧答案，但不能扩大旧 snapshot 的可见范围。
`callerAccessMode`、`effectiveSelectedCollectionIds`、`callerAllowList`、
`sourceCollectionIdsObserved` 和 `sourceDocumentCollectionSnapshot` 只是首次执行时的
服务端授权证据，不能在 replay 时直接
当作“仍然存在且可读”的事实。replay verifier 必须按当前 principal/API key 重新解析
范围，并通过 `CollectionIdentityResolver` 的 active 语义（当前为存在且未
soft-delete）回查每个相关 Collection；不能只检查 API key allow-list 中是否有数字 ID。
`sourceCollectionIdsObserved` 和 `sourceDocumentCollectionSnapshot` 必须由服务端根据
本次结果中的 document ID 反查权威 `rag_documents.collection_id`，再回查对应
Collection，而不能信任 `ChatSource.metadata`、`collectionKey` 展示字段、模型输出或
客户端提交值。首次 coordinated commit 前，每个将进入 response snapshot 的文档来源都
必须提供可解析的正整数 document ID；服务端必须批量回查到唯一、enabled 的权威文档行，
并证明其当前 Collection 归属符合本次 resolved scope 与当前 principal。任一来源 ID
缺失/非法、权威行缺失、同一 ID 出现无法规范化的冲突，或权威归属超出本次授权范围时，
以 `FAILED/IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID` 结束 operation，不提交 answer、
history 或 Memory；不能保存残缺来源快照后返回成功。

replay 必须证明每个来源 document 仍存在且其当前
`collection_id` 与首次快照一致：首次为具体 Collection ID 时，该 Collection 必须仍
active 且当前调用方有权读取；首次为 `NULL` 时，仅当首次
`scopeMode=CALLER_VISIBLE`、`callerAccessMode=UNRESTRICTED`、
`unassignedDocumentsAllowed=true` 且当前 document 仍为未归属时允许 replay。任何
来源 document 缺失、当前 `enabled=false`（包括本地 disable 与 source tombstone）、
Collection 被删除或来源权限无法证明时都必须 fail closed。内容 revision/hash 在同一
24 小时幂等窗口内变化不改写既有 snapshot；只有当前可读性和授权边界失效时拒绝 replay。
`PLAIN` 的 `NOT_APPLICABLE` 快照不执行这些 Collection/Document 查询。

### 5.2 Claim、replay 与 lease

`ChatTurnOperationCoordinator` 负责以下状态转换：

```text
不存在
  -> 选择/校验 session，取得适用的 session lease，再 INSERT IN_PROGRESS + token
     + operation lease（SERVER lease 失败不创建 operation；STATELESS 不取得 session lease）
IN_PROGRESS + 当前 lease 有效
  -> duplicate => 409 IN_PROGRESS
IN_PROGRESS + lease 已过期
  -> UPDATE ... WHERE status/token/旧 expiry/version 条件成立，接管并递增 attempt_count
IN_PROGRESS + 当前 token
  -> 成功事务 => SUCCEEDED + response snapshot
IN_PROGRESS + 当前 token
  -> 稳定失败事务 => FAILED + error snapshot
IN_PROGRESS + stale reclaim 达到 attempt 上限
  -> 确认无有效 session lease，再按旧 status/token/expiry/version CAS 为
     FAILED/IDEMPOTENCY_ATTEMPTS_EXHAUSTED，不再调用 provider
SUCCEEDED/FAILED + fingerprint 相同
  -> replay，不调用模型
终态 operation 已超过 retention TTL
  -> 通过 completed_at/TTL 条件 CAS 删除后，允许同 key 创建全新 operation
IN_PROGRESS + lease 已过期且 updated_at 已超过 retention TTL
  -> 通过 status/token/expiry/version 条件 CAS 删除孤儿行，允许同 key 创建全新 operation
任意已存在状态 + fingerprint 不同
  -> 409 KEY_REUSED
```

实现必须使用条件 `INSERT ... ON CONFLICT`、`UPDATE ... WHERE` 和 version/token
fencing。不能使用 `SELECT ... FOR UPDATE`、`SKIP LOCKED` 或 advisory lock。claim
和 replay 读取必须按当前 principal 过滤，不能用只凭 key hash 的全局查询。

执行期间 operation token 与现有 session lease 是两层边界：

- session lease 防止同一 principal/session 的不同 turn 并发破坏 Memory；
- operation lease 防止同一 key 的重复请求并发执行；
- 两者都必须在模型调用前取得，任一失效都终止提交；
- 对新 operation，先完成请求声明 fingerprint lookup；若没有已有 operation，先为
  `AUTO_SESSION` 选择候选 session（显式 session 直接使用请求值），尝试取得 session
  lease，再执行带 operation token/session 的唯一 claim。若唯一 claim 因并发已存在而失败，
  必须释放刚取得的 session lease、重新读取 operation 并按 fingerprint 返回 replay/conflict；
  若 session lease 获取失败，先按当前 principal、key hash 和 fingerprint 重新读取
  operation：如果首个请求已经完成 claim，按 operation 状态返回
  `IDEMPOTENCY_OPERATION_IN_PROGRESS`/replay/conflict；只有确认没有对应 operation
  时，才不创建持久 operation 并返回现有 session busy 语义，避免把同 key 的窄并发窗口
  错报为普通 session 冲突或留下无法执行的 `IN_PROGRESS` 行；
- stale reclaim 必须先从已有 immutable `execution_snapshot` 确定 memory mode；若快照
  因首次 claim 后崩溃仍为 `NULL`，只能按 4.2 节的有界恢复规则从 fingerprint 一致的当前
  请求声明和当前 registry 重建一次受控候选快照，并在 reclaim CAS 中一并持久化，不能
  在未知 memory mode 下先夺取 operation token。确定为 `MemoryMode.SERVER` 后，必须先用
  operation 中保存的 session 成功取得/确认 session lease，再用 token/expiry/version
  条件 CAS 接管 operation；确定为 `STATELESS` 时不创建或取得 session lease，直接走
  operation CAS 接管。
  如果 session lease 仍由旧执行持有，返回 in-progress/busy，不能先夺取 operation
  token；如果已取得 session lease 但 operation CAS 输给其他请求，必须立即按 token
  释放刚取得的 session lease并重新读取 operation，不能泄漏一条无执行者的会话 lease；
- stale operation 已达到 attempt 上限时不走上述接管路径；按本节前述规则确认无有效
  session lease 后直接条件写入稳定失败终态。这个失败 CAS 同样必须检查旧 token、
  expiry、version、`status='IN_PROGRESS'` 和 lease 已过期；
- operation lease 必须按请求 deadline 加 grace 设计，至少覆盖正常 Chat deadline，并由
  与 session lease 同步的有界 renew 保护；任一 lease 丢失都禁止提交；
- operation renew 必须使用
  `UPDATE ... WHERE status='IN_PROGRESS' AND operation_token = ?
  AND row_version = ? AND lease_expires_at > clock_timestamp()
  RETURNING row_version` 或等价 CAS，把递增后的最新版本同步写回当前 execution handle；
  renewal 必须同时更新 `updated_at`，并使用数据库时钟写入新的绝对 expiry。已过期 lease
  不能被原 worker 续活。renew 与
  coordinated commit 在同一 handle monitor 下串行；commit 先停止并等待在途 renewal，
  再使用 handle 持有的最新 `row_version` 执行终态 CAS。成功/失败终态 CAS 都必须包含
  `status='IN_PROGRESS'`、当前 token、最新 row version 和
  `lease_expires_at > clock_timestamp()`，并原子清空 token/lease、递增 row version；
  已过期 worker 即使尚未被 reclaim 也不能提交。不能让续租线程递增数据库版本却让提交
  继续使用旧版本，也不能在 commit 开始后再次续租；
- `MemoryMode.STATELESS` 不使用 `rag_chat_session_lease`，只取得 operation lease；
  其成功提交不消费 session lease，也不向共享 Spring AI Memory 写入内容。服务端仍须
  为 operation 分配稳定 `session_id`，用于 fingerprint、状态查询和 response snapshot，
  但不能把这个 ID 误当作已启用的会话记忆；
- 进程崩溃后只允许过期 operation 被新请求接管，不能由普通 replay 直接绕过
  fingerprint 或 ACL。

### 5.3 原子提交

现有 `ChatExecutionService.execute()` 和 `completeStreamAttempt()` 会在内部调用
`ChatSessionCoordinator.commit()`，随后再做摘要压缩和结果 metadata 装饰；它们不能
被一个外层 coordinator 直接包住来“事后”写 operation snapshot。实现必须先把
mode-aware Chat 执行拆成两个明确阶段：

1. **prepare/execute**：由 operation coordinator 将已经取得的 operation claim、session
   `LeaseHandle`（如适用）和续租上下文传给 Chat 执行内核；执行内核不得再次
   `acquire()` 或自行 `release()` session lease。它完成模型、fallback、工具预算和
   响应聚合，只返回待提交的 `ChatExecutionResult`、request-local memory 的 committed
   messages、稳定 sources/授权材料和执行预算快照；这一阶段不得写
   `rag_chat_history`、共享 Spring AI JDBC Memory 或 operation 终态，也不得释放仍需用于
   提交 fencing 的 lease。keyed native/OpenAI SSE 的模型执行 subscription 由 operation
   coordinator 持有；HTTP emitter 只是事件观察者，断开时只能解除观察者和停止发送，
   不能 dispose 协调器拥有的执行 subscription。无 key SSE 保留现有连接驱动的取消行为；
2. **coordinated commit**：由 `ChatTurnOperationCoordinator` 统一开启短事务，在同一个
   `PlatformTransactionManager` 上按 operation token/row version CAS 写入 operation
   终态和 response/error snapshot，调用带 `turn_id` 的 durable history 写入，并在
   `MemoryMode.SERVER` 下消费 session lease token fence、写入共享 Spring AI JDBC
   Memory；全部成功才提交事务，之后才释放非数据库资源。

这里的“同一个事务管理器”是硬约束：operation `JdbcTemplate`、session lease
`JdbcTemplate`、JPA history 和 Spring AI `JdbcChatMemoryRepository` 必须共享同一个
PostgreSQL `DataSource` 与 `PlatformTransactionManager`，不得为 operation 另建独立
transaction manager。PostgreSQL 集成测试必须用故障注入分别证明 history、Memory 或
operation 任一步失败时其余写入和 session lease consume 全部回滚。

无 key 的旧路径可以继续使用现有 `ChatExecutionService` 提交行为；带 key 的路径必须
使用上述 prepare/commit API，不能先调用旧 `execute()`/`completeStreamAttempt()` 再
补写 operation。该边界也适用于 native 与 OpenAI 两类 JSON/SSE，避免不同 transport
各自复制一套不完整的原子性实现。摘要压缩若继续在提交后 best-effort 执行，只能更新
独立摘要表，不能修改已冻结的 response snapshot。

成功路径的短事务顺序固定为：

1. 在 response snapshot 已通过大小和敏感字段校验后，以 operation token、最新
   row version 和未过期 lease CAS 把 operation
   从 `IN_PROGRESS` 变为 `SUCCEEDED`，写入
   response snapshot、turn ID、completed time 和可重放授权边界；
2. 仅对 `MemoryMode.SERVER` 消费当前 session lease 的 token fence；
3. 写入 `rag_chat_history` 的 `COMPLETE` turn，并设置 `turn_id`；
4. 清理/写入 Spring AI JDBC Memory；
5. 提交事务后释放非数据库续租资源。

任何一步失败，事务回滚，不能留下“operation 成功但没有 history/Memory”的半成功状态。
如果 provider 已经返回而 coordinated commit 失败，operation 仍保持可接管的
`IN_PROGRESS`，不能伪造成功快照；该恢复窗口按 at-least-once 记录。

稳定的 provider/模型/预算失败必须走协调器的短事务：用 operation token、最新 row
version 和未过期 lease CAS 写入 `FAILED`、固定错误快照和 `completed_at`，并在
`MemoryMode.SERVER` 下消费
适用的 session lease；不得写 history 或共享 Memory，`STATELESS` 不创建也不消费 session
lease。CAS 输给已提交的 `SUCCEEDED` 时，成功终态优先；CAS 或事务失败时不能伪造失败
终态，operation 保持可接管的 `IN_PROGRESS`。因此失败与成功路径拥有同等的 fencing 和
恢复语义，而不是在 HTTP handler 中事后更新状态。

SSE 在模型执行过程中不能提前把 operation 标记成功。只有聚合完成、history/Memory
提交成功后才向仍连接的观察者发送 `done`。`SseEmitter.onTimeout()`、发送失败、
`onError()`、reader cancel 和代理断开都只停止该 emitter 的心跳与事件投递；不得取消
operation coordinator 的模型执行、停止 operation/session lease renewal，或写终态。
如果 JVM/执行线程本身消失，operation 才因续租停止而自然过期并由 stale reclaim 接管。
这一区分保证“传输断开”不会被误写成不可重试的业务取消。

对于携带 `Idempotency-Key` 的请求，response snapshot 必须在上述短事务开始前
冻结，并且是首次响应与后续 replay 共同使用的唯一公开响应。当前实现会在业务 turn
提交之后再执行 best-effort 的会话摘要压缩；这类提交后可变的摘要 metadata 不得在
operation 成功后再追加到幂等响应，否则首次响应和 replay 会不一致。推荐实现是：
幂等路径以提交前已经完成的稳定结果（包括执行预算 metadata）生成 snapshot，摘要压缩
仍可在提交后 best-effort 执行，但不得修改已返回的响应或 operation snapshot。无 key
路径可以保留当前摘要 metadata 行为。未来若要把摘要纳入幂等响应，必须先设计独立的
预处理/提交流程并重新冻结这一响应一致性契约。

这里的“同一响应”指持久化的 transport-neutral 业务结果、`turnId`、session、公开业务
metadata，以及 `metadata.retrievalTraceId` 等已经写入响应的稳定业务/检索诊断引用；
协议 envelope 由当前 transport adapter 从这些稳定字段重新投影，不能因为 operation 首次
由 native 或 OpenAI transport claim 就锁死后续 replay 的协议格式。OpenAI 的 completion
identity 只在 OpenAI adapter 的 keyed envelope 中稳定派生，不属于跨 transport 的业务
snapshot。这样 replay 仍能指向首次执行的同一诊断记录。snapshot
builder 必须显式构造受控 DTO/JSON，不能直接序列化完整 `ChatResponse`、
`ChatExecutionResult` 或 persistence metadata；必须移除 top-level `traceId`、
`metadata.traceId`/request trace 字段、HTTP trace header、MDC、span 和其他请求级
diagnostic context。当前 `ChatExecutionResult.traceId` 来自请求级 MDC，
`ChatResponse.traceId` 也属于请求/传输诊断，不属于不可变 snapshot；replay 可以重新生成
这些字段，验收时必须允许它们变化。只有绑定到 operation 的稳定
`metadata.retrievalTraceId` 才能进入 snapshot；请求级 trace 不能冒充业务诊断引用。
`X-RAG-Idempotent-Replay` 是每次传输单独生成的诊断 header，首次为 `false`、replay
为 `true`，也不属于不可变 snapshot。原生 SSE `done.idempotentReplay` 与该 header
具有相同的 per-request 诊断语义，首次为 `false`、replay 为 `true`，不得写入
`response_payload`；因此“完整 done”只表示恢复同一业务结果和稳定业务 metadata，
不表示恢复这两个本次传输的 replay 标记。验收时必须分别断言稳定业务字段相同，以及
本次请求的 replay 标记随首次/replay 改变。
`X-RAG-Turn-Id` 和 OpenAI completion ID 则必须在同一 operation 的对应协议语义内保持稳定。

keyed 请求的 retrieval diagnostics session 必须在 operation lookup/claim 之后创建：
首次执行在 claim 成功后创建一个 session，并把其 UUID 作为稳定的
`metadata.retrievalTraceId` 绑定到 operation；replay 直接复用这个引用，不创建第二个
`RetrievalTraceSession`，也不重复写入 `rag_retrieval_logs`。若 diagnostics 被关闭，
snapshot 不填充该字段。`X-RAG-Retrieval-Trace-Id` 是这个稳定业务诊断引用，
`X-Trace-Id`/MDC 是每个 HTTP 请求独立生成的请求 trace，二者必须在 handler、snapshot
和验收断言中明确区分。无 key 旧路径可以保留现有 controller 先建 diagnostics session
的行为。

### 5.4 TTL 与清理

新增维护任务清理两类记录：

1. `SUCCEEDED/FAILED` 且 `completed_at` 超过 idempotency TTL 的终态；
2. `IN_PROGRESS`、operation lease 已过期且 `updated_at` 超过同一 retention TTL 的孤儿。
   孤儿删除必须带 `status + operation_token + lease_expires_at + row_version` 条件，并对
   所有 mode 都按 operation 的 principal/session 确认不存在仍有效的对应 session lease；
   `STATELESS` 正常情况下自然查不到 lease，不能因此省略保护查询。它不是对活动 operation
   的无条件 DELETE。删除后旧 worker 的 renew/commit CAS 必须失败。

清理必须：

- 使用有界批次；
- 不删除仍有有效 operation/session lease 的行；
- 采用条件 DML/CAS，遵循现有 Chat history TTL 的 maintenance lease；
- 记录删除数量和固定原因，不记录 key、prompt 或 response 内容。

推荐默认保留 24 小时，理由是覆盖常见网关重试窗口而不让响应快照无限增长；可逆边界为
1–168 小时。修改 retention 不影响 fingerprint 和状态机。

本轮配置键固定为：

| 配置键 | 默认值 | 可逆范围/用途 |
|---|---:|---|
| `rag.chat.idempotency.enabled` | `true` | 运维总开关；关闭时带 key 返回 `IDEMPOTENCY_DISABLED` |
| `rag.chat.idempotency.retention-hours` | `24` | `1–168`；终态 operation replay/status 保留时间 |
| `rag.chat.idempotency.response-snapshot-max-bytes` | `524288` | `65536–2097152`；按 UTF-8 序列化结果限制 |
| `rag.chat.idempotency.execution-snapshot-max-bytes` | `65536` | `16384–262144`；按 UTF-8 序列化执行解析快照限制 |
| `rag.chat.idempotency.max-attempts` | `3` | `1–8`；stale reclaim 总执行次数上限 |
| `rag.chat.idempotency.lease-grace-ms` | `10000` | `1000–60000`；追加到 ask/stream deadline 的 operation lease 宽限 |
| `rag.chat.idempotency.cleanup-batch-size` | `500` | `1–5000`；终态 cleanup 单批最大行数 |

operation lease 的首次 expiry 和每次 renew 都必须覆盖当前 endpoint deadline 加
`lease-grace-ms`；不能通过把宽限配置为极小值来绕过 provider/数据库提交的正常尾延迟。

## 6. 各 transport 的行为

### 6.1 原生 JSON

`POST /api/v1/rag/chat` 与 `/api/v1/rag/chat/ask` 接受可选
`Idempotency-Key`。成功和 replay：

- HTTP 200；
- `X-RAG-Turn-Id: <opaque UUID>`；
- `X-RAG-Idempotent-Replay: true|false`；
- `ChatResponse.metadata.turnId` 与 header 一致；
- response body 的业务字段使用版本化 snapshot 恢复；请求级 trace 字段可以按请求重新
  生成，不能被验收误判为业务结果变化。

首次请求无 session 时，body 返回 operation 分配的 session；同 key 重试必须返回同一
session。无 key 请求不新增 header 语义，仍沿用现有自动生成 session。

### 6.2 原生 SSE

`POST /api/v1/rag/chat/stream` 接受相同 header。首个成功执行：

- 在 HTTP header 尽早写入 `X-RAG-Turn-Id`，但 header 不表示已提交成功；
- 在返回 HTTP 200、创建 `SseEmitter` 或订阅执行 Flux 之前同步完成 operation claim；
  若已有活跃 operation，直接返回 409 和 `Retry-After`，不能把 claim 冲突延迟到已开始
  的 SSE 流中；
- `done` payload 增加 `turnId`、`idempotentReplay=false`；其中
  `idempotentReplay` 是本次传输的诊断字段，不进入 immutable response snapshot；
- 只有 `done` 后才表示 operation/history/Memory 已提交。

已成功 operation 的 replay 不重新连接 provider，只发送一组完整、有限的业务事件：

```text
content (完整 answer 一次)
sources (若有)
done (完整稳定 metadata、turnId、idempotentReplay=true)
```

replay 不发送旧的 tool activity，不承诺原始 token chunk 的节奏；`idempotentReplay=true`
只标识本次传输是 snapshot replay，不改变 snapshot 中的稳定业务字段。`IN_PROGRESS` 重试在
HTTP headers 尚未提交前返回 409；若调用方已经拥有一条旧 SSE 连接，仍由旧连接继续
接收其结果或失败。服务端不实现 `Last-Event-ID`。

### 6.3 OpenAI 兼容 JSON/SSE

`POST /v1/chat/completions` 从 `HttpServletRequest` 读取同一 header，并将其传给
transport-neutral operation。对于带 `Idempotency-Key` 的 operation，completion ID 由
`turnId` 以稳定、不可逆的格式派生：固定使用
`chatcmpl-rag-` 前缀加 `SHA-256("openai-completion-v1:" + turnId)` 的完整小写十六进制
结果，不依赖随机数、API key 或可变 registry 状态，也不在终态 operation 上做事后可变
更新。`created` 固定为不可变 operation `created_at` 的 epoch seconds（向下取整），
`model` 固定为 canonical request envelope 中的公开 OpenAI alias；replay 不重新从当前
registry 解析或改写这两个字段。跨 transport replay 仅在 canonical `declared model
identifier`、消息、scope、mode 等字段完全相同且该 identifier 本身就是同一公开 alias
时成立；否则在
fingerprint 阶段得到 `IDEMPOTENCY_KEY_REUSED`，不通过“语义等价 alias”兜底。对 OpenAI
同一 transport 的 replay 从同一个 transport-neutral snapshot 重新投影出稳定的
`chat.completion` envelope，并使用同一个 completion ID；SSE replay 发送与首次相同的
assistant role 前导 chunk、完整 answer delta、finish chunk 和 `[DONE]`，
不泄漏项目专用 `tool_start/sources` event name。OpenAI `tools/functions` 仍在 mapper
层拒绝。

不带 `Idempotency-Key` 的 OpenAI 请求保留当前每次请求生成 completion ID 的兼容行为；稳定
派生 ID 只适用于 keyed operation。

OpenAI JSON 和 SSE 的首次响应、replay 响应都必须设置
`X-RAG-Turn-Id: <opaque UUID>`；该 header 是 OpenAI 兼容客户端获取状态查询入口的
唯一项目扩展，不改变 OpenAI body schema。SSE 在返回 200 和创建 emitter/subscription
之前必须同步完成 operation claim；若已有活跃 operation，应直接返回 409 和
`Retry-After`，不能把该错误延迟到已经开始的流中。

同 key 不同 messages/model/rag scope/mode 返回 OpenAI error envelope，内部 error code
仍为 `IDEMPOTENCY_KEY_REUSED`。`IN_PROGRESS` 返回 409 对应的兼容 error envelope，并
设置 `Retry-After`。

### 6.4 Turn 状态查询

新增 `GET /api/v1/rag/chat/turns/{turnId}`，只接受 opaque `turnId`，不接受 key hash
或原始 key。当前 principal 只能看到自己的 operation：

默认只返回状态元数据；调用方显式传 `?includeResponse=true` 时，成功 operation 才附带
response snapshot。未知或不允许的 query 参数按现有 API 参数校验返回 400，不因默认状态
查询而返回 prompt、工具输入或部分答案。

```json
{
  "turnId": "opaque-uuid",
  "sessionId": "session-001",
  "status": "IN_PROGRESS|SUCCEEDED|FAILED",
  "transport": "NATIVE_SSE",
  "createdAt": "...",
  "updatedAt": "...",
  "completedAt": null,
  "replayAvailable": false,
  "errorCode": null,
  "response": null
}
```

状态中的 `transport` 表示首次 claim 的 transport。`replayAvailable` 只有在
`status=SUCCEEDED`、operation 未过期且当前 principal/API key 通过普通 replay 的同一
Collection/source ACL verifier 时才为 `true`；`IN_PROGRESS/FAILED` 固定为
`false`。默认状态查询可以为计算该布尔值执行授权复核，但不得返回旧 answer/sources；
ACL 已失效时仍返回 200 状态元数据并令 `replayAvailable=false`，只有调用方要求
`includeResponse=true` 时才返回 `403 FORBIDDEN`。成功状态的 `includeResponse=true`
始终返回基于 transport-neutral snapshot 投影的原生
`ChatResponse` 结构；即使 operation 首次由 OpenAI transport claim，也不能把 OpenAI
envelope 原样嵌入 status response。IN_PROGRESS 不返回 prompt、工具输入或部分答案；
FAILED 只返回稳定 error code。其他 principal、未知 turn 或已过期 operation 统一为 not
found，不暴露存在性。

当状态为 `SUCCEEDED` 且请求方要求返回 response snapshot 时，对
`KNOWLEDGE`/`AGENT` 必须重新执行与普通 replay 相同的 principal、Collection scope 和
来源 ACL 校验；权限收窄时返回 `403 FORBIDDEN`，不能因为状态查询是 GET 就绕过 replay
的 fail-closed 规则。`PLAIN` 不执行 Collection ACL 复核。仅返回状态字段时可以不返回
快照，但仍须按当前 principal 查询。

## 7. WebUI 语义

WebUI `useSSE` 在每次用户发送 turn 时生成一个 UUID 作为 `Idempotency-Key`，并在
同一次 `send` 的网络重试中复用它。一次 logical send 最多自动尝试两次：首次请求失败
后只允许一次有界重试；`409 IDEMPOTENCY_OPERATION_IN_PROGRESS` 按响应
`Retry-After` 等待，但总等待时间不得超过 60 秒，超出或第二次仍失败就停止自动重试。
用户主动 `stop/close`、参数校验失败、`KEY_REUSED`、`FORBIDDEN` 或其他明确业务错误
不得自动重试；`stop/close` 只中止本地投递，服务端 keyed operation 可能继续完成。
需要重新发起新 turn 时生成新 key；若要恢复刚停止的逻辑 turn，只能继续使用内存中原
key 或根据已经收到的 `turnId` 查询状态，本轮不新增显式取消按钮/API。

hook 在收到成功 HTTP response、开始读取 SSE body 前，必须立即读取
`X-RAG-Turn-Id`，通过新增的 `onTurnClaimed(turnId)`（或等价 typed callback）绑定到
当前 logical turn；`done.turnId` 必须与它一致。这样即使连接在 `done` 前断开或用户
主动 stop，页面内存仍有不泄漏 key 的 status 查询 identity。header 缺失、不是合法 UUID
或与 `done.turnId` 不一致都视为协议错误，不自动生成替代 turn ID。

hook 必须把 network attempt 与 logical turn 分离：服务端已可能发送部分 SSE content
或 tool activity 时，重试前清空当前 assistant bubble 的临时 content/tool state，再
接收第二次 attempt；replay 的完整 answer 只能继续写入同一个 assistant bubble，不能
追加第二个 bubble 或把第一次的 partial answer 与 replay 拼接。收到
`done.idempotentReplay=true` 时只完成这个已有 bubble，并把 turn ID 绑定到内存中的
logical turn。key、request body、attempt count 和 pending turn 只保存在当前页面内存，
不写入 URL、localStorage、history 或 DOM。

`ChatDoneEvent` 增加 `turnId` 和 `idempotentReplay`；页面可在内存中记录当前 turn，
收到 done 后正常加载 history。收到 409 `IN_PROGRESS` 或 `KEY_REUSED` 时展示稳定错误
状态并保留用户输入，不重复追加 assistant bubble。页面不做截图式验收；Playwright
只断言 DOM、可访问状态、请求 header/body、响应 JSON 和 SSE 事件。

首次请求无 session 的导航行为继续依赖 `done.sessionId`；replay 不创建第二个 session。
如果未来加入“恢复 turn”按钮，应调用 turn status API，不能把原始 key 暴露给浏览器。

## 8. 低基数观测

使用 Micrometer 固定枚举 tag：

### Counters

```text
rag.chat.turns.total
tags: result=success|failure|replay|conflict|in_progress,
      transport=native_json|native_sse|openai_json|openai_sse,
      mode=plain|knowledge|agent|unknown

rag.chat.budget.exhausted.total
tags: reason=model_calls|tool_rounds|tool_calls|result_chars|context|
       deadline|candidate_attempts|summary_calls|unknown

rag.chat.provider.calls.total
tags: transport=native_json|native_sse|openai_json|openai_sse,
      mode=plain|knowledge|agent|unknown,
      result=success|failure
```

### Timer

```text
rag.chat.turn.duration
tags: transport, mode, result=success|failure|replay
```

要求：

- `replay` 的 duration 只计 operation snapshot 读取与序列化，不伪装成 LLM duration；
- 不使用 sessionId、turnId、traceId、principalId、key hash、model name、collection ID、
  document ID、prompt、tool name、tool arguments 或 response text 作为 tag；
- `reason` 只能使用固定枚举值；具体工具名只能进入受控日志或 trace 字段，绝不能作为
  metrics tag；
- provider/model 维度如确有现有低基数 registry 才沿用固定 alias，否则不新增；
- 每一次实际 Chat provider invocation（包括 fallback/retry）只增加一次
  `rag.chat.provider.calls.total`；operation snapshot replay 不增加该 counter。该 counter
  是真实 LLM replay 验收的应用内证据，不记录 provider、model、turn 或 prompt 标签；
- 日志可记录 turn ID 的 hash 或 trace 关联，但不记录原始 key、prompt、工具输入输出；
- metric 名称和 tag 集合写入测试，防止后续引入高基数标签。

## 9. 文件级实施顺序

实施 worktree 从最新 `main` 创建后，按以下切片推进，每个关键切片先更新
`NEXT_HIGH_VALUE_FEATURES_PROGRESS.md`：

1. **契约与规范化**：API DTO/header、canonical fingerprint、key validator、operation
   model；先写 unit tests，冻结 canonical JSON 边界。
2. **数据库与 repository**：V47、operation repository、history `turn_id`、PostgreSQL
   唯一约束/CAS/lease/ACL 集成测试。
3. **协调器接入**：`ChatTurnOperationCoordinator`、`ChatExecutionService`、
   `ChatSessionCoordinator`；先落地 prepare/execute 与 coordinated commit 边界，确保
   operation/history/Memory/session fence 同事务提交；无 key 旧路径继续兼容。
4. **原生 controller/API**：JSON/SSE headers、done metadata、replay events、status API。
5. **OpenAI compatibility**：header、稳定 session、JSON/SSE replay，保持 tools/functions
   拒绝和现有 error envelope；为 `IN_PROGRESS` error envelope 设置与 native 相同的
   `Retry-After` 退避 header。
6. **WebUI**：`useSSE.ts`、Chat 类型和错误处理；Vitest、Mock Playwright、生产 bundle。
7. **观测与清理**：Micrometer、TTL maintenance、固定 tag 合约和隐私测试。
8. **门禁脚本与文档交付**：扩展现有 `scripts/verify-chat-capability.sh`，让默认
   Mock/PostgreSQL 门禁实际执行本轮幂等矩阵，并让 `--with-real-llm` 调用独立的
   `scripts/real-llm-chat-idempotency-smoke.sh`；随后更新双语长青文档、归档
   plan/progress。最终先把最新已推送的 `origin/main` merge 到特性分支，按下述固定顺序
   完整复验，再合回并 push `main`；确认远端和工作区状态后安全移除隔离特性 worktree。

不得在步骤 1–8 之间先开放真实 provider；先完成一次性验收矩阵，再按门槛顺序执行。

如果运行时未启用 mode-aware `ChatExecutionService`、operation repository 或所需
PostgreSQL 能力，带 `Idempotency-Key` 的请求必须返回 `IDEMPOTENCY_DISABLED`，不能退回
当前 `RagChatService` 的 legacy `executeChat` 非幂等路径；不带 key 的原生 JSON 请求
继续保留现有 legacy fallback，原生 SSE 继续保留其当前的 mode-aware 依赖错误语义，不
借本轮幂等改造暗中新增另一条 stream 执行链。

## 10. 一次性验收矩阵

### 10.1 后端快速与 PostgreSQL 集成

必须新增覆盖本次代码路径的集成/E2E 测试，而不是只测 coordinator 单元：

| 场景 | 证据 |
|---|---|
| 首次 native JSON 成功 | HTTP 200、turn header/body、一次 provider stub、history/Memory/operation 一致 |
| 幂等输入/快照边界 | 非法 key、32 KiB metadata、credential 字段、execution/response snapshot 超限或敏感字段，以及来源 ID 缺失/非法、权威行缺失或归属冲突均返回冻结错误码，且无不完整 answer/history/Memory |
| 同 key 完成 replay | 第二次稳定业务字段、turn/session/completion identity 相同；请求级 trace 可变化，replay header/事件从 false 变 true；provider 调用次数仍为 1、无第二条 history |
| 同 key fingerprint 冲突 | 409 + `IDEMPOTENCY_KEY_REUSED`，无模型调用 |
| 同 key 并发 | PostgreSQL 真实唯一约束，只有一个执行者，另一个 409 + Retry-After |
| stale operation reclaim | lease expiry 后 CAS 接管；旧 token 不能提交；达到 attempt 上限时先证明无有效 session lease，再 CAS 为稳定失败且不调用 provider |
| principal 隔离 | 另一个 principal 不能 replay 或查询 turn |
| ACL/来源可读性变化 | `CALLER_VISIBLE/ANY_COLLECTION` 从 unrestricted 变 restricted、selected/source Collection 失权、来源文档禁用/tombstone/缺失后 replay 返回 403；`SELECTED_COLLECTIONS` 丢失无关权限仍可 replay；仅内容 revision 变化保持不可变 snapshot |
| PLAIN replay 与 ACL 变化 | `PLAIN` 使用 `NOT_APPLICABLE` scope，不因无关 Collection ACL 变化拒绝 replay；不得读取文档 |
| provider/commit failure | FAILED 或可接管状态符合契约，不出现孤儿 history/Memory |
| native SSE 首次/重连 replay | 首次有工具/内容事件和最终 done；replay 只发送完整 answer、sources、done |
| OpenAI JSON/SSE | snapshot replay、错误 envelope、同 key 冲突、tools/functions 仍拒绝 |
| STATELESS 与 legacy 兼容 | STATELESS 不创建 session lease；mode-aware 依赖不可用时带 key 拒绝、无 key 仍按旧路径工作 |
| status API | IN_PROGRESS/SUCCEEDED/FAILED、principal scope、过期/未知 not found、准确的 `replayAvailable`、`includeResponse=true` 的快照与 ACL 复核 |
| cleanup | 终态和 stale orphan 仅按有界 CAS 清理；所有 mode 均先排除有效 principal/session lease，不删除活动 operation |

实现必须把上述矩阵接入现有 `scripts/verify-chat-capability.sh`，不能只保留为手工
命令或未绑定的测试类：默认模式必须运行幂等专用的 focused HTTP tests、PostgreSQL
测试方法、Maven 编译门槛、WebUI Mock Playwright 和文档/空白检查；PostgreSQL
报告必须断言本轮方法实际执行且 `skipped=0`。如果复用
`NextHighValueFeaturesPostgresIntegrationTest`，必须同步更新脚本的测试选择器和期望
数量，避免新测试被 Maven 的旧 selector 静默漏掉。

至少运行：

```bash
mvn clean compile test-compile
./scripts/verify-no-pessimistic-locks.sh
```

并运行本任务的 PostgreSQL HTTP 集成矩阵。数据库必须是真实 PostgreSQL，不能用 H2
替代 operation 的唯一约束、JSONB、数组和时间条件语义。

### 10.2 前端

在 `spring-ai-rag-webui`：

```bash
npx tsc -b --pretty false
npm run test:run
npm run build
```

核心 Mock Playwright 必须只使用 DOM 可见性与可访问状态、网络请求/响应和自动化断言，
覆盖每个发送请求的 key、同一次 retry 的 key 复用、部分 SSE 后 replay 不重复内容/气泡、
用户 stop 不重试且只停止本地投递、新 turn 新 key、done turn ID、409 输入保留、replay
不重复 assistant bubble、response header turn ID 的即时绑定/UUID 校验、header 与 done
一致性，以及 status JSON 映射。禁止用截图作为
验收证据。

### 10.3 隔离端口运行时

在非 main worktree 使用独立后端、前端和测试数据库，记录合并后验证基线。启动
`postgresql` profile 的服务，使用项目已有 dev launcher/真实 E2E 脚本，必要时为本轮
增加 idempotency smoke。必须用 `curl` 或 Playwright 观察真实 HTTP header、JSON、SSE
和只读数据库状态，确认服务可启动、operation 迁移可执行、旧无 key 客户端仍兼容。

### 10.4 真实 LLM

用户已明确允许使用 `.env` 中的真实 provider。顺序固定：Mock/PostgreSQL/前端门槛
全部通过后，使用隔离端口加载 `.env`，由
`scripts/real-llm-chat-idempotency-smoke.sh` 先用 `PLAIN` 模式执行有界 native JSON
和 native SSE 幂等/replay 场景；这条路径不需要创建文档或调用 Embedding provider。
当 `.env` 仅提供 `SPRING_AI_OPENAI_BASE_URL`、
`SPRING_AI_OPENAI_API_KEY` 和 `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL` 时，启动脚本必须
显式使用 `LLM_PROVIDER=openai`（或等价的 `APP_LLM_PROVIDER=openai`），并对
`SPRING_AI_OPENAI_BASE_URL` 只移除末尾 `/v1` 后再交给 Spring AI；启动脚本、provider
preflight 和 smoke 请求必须读取同一个已选择的 provider，禁止在 `openai` 模式下回退或
优先探测 MiniMax，也不能把“其他 provider 探测成功”当作本次真实 Chat 证据。所选
provider 的 key、base URL 或 model 缺失/不可用时必须失败并明确指出配置缺口，不得静默
换路由。日志只记录脱敏的 provider/model/base-url 和隔离端口，不打印原始 key。真实
smoke 应记录这些脱敏配置作为证据。
如果 `RAG_OPENAI_COMPATIBILITY_ENABLED=true`，再执行有界 OpenAI JSON/SSE 场景；只有
Embedding key 可用时才追加 `KNOWLEDGE`/真实检索场景。现有
`scripts/real-llm-e2e-smoke.sh` 会强制执行 Embedding preflight，因此不能单独作为本轮
PLAIN 幂等验收入口；应新增或扩展一个只覆盖 Chat turn 的真实 smoke，避免把 Embedding
环境缺口误报为 Chat 幂等失败。
`verify-chat-capability.sh --with-real-llm` 必须调用该专用 smoke，并在隔离环境中显式
暴露受保护的 `metrics`/`prometheus` 观测端点或使用等价的固定 counter 证据；不能
依赖旧的 Embedding preflight 才算通过。对同一成功 key 重复请求并确认 provider 调用
计数不增加；调用计数必须通过
`/actuator/metrics/rag.chat.provider.calls` 或 `/actuator/prometheus` 中固定 tag 的
前后差值与脱敏日志交叉确认，不能以外部 provider 控制台或猜测代替证据。人为制造
客户端超时/断开后查询 turn status，再验证 replay；持续观察日志，清理测试
Collection、history、operation 和临时文件，只保留脱敏证据。真实 LLM 不代替并发矩阵、
前端 Mock Playwright 或 code review。

## 11. 规划与实施收敛流程

规划完成后按三轮固定范围检查：需求闭环/自包含性/默认决策；代码/数据库/API/安全/
并发/兼容性；实施顺序/验收/回滚/恢复/文档/Git。发现实质缺陷立即修正规划并将计数
重置为 `0`；连续三轮无修改才可实施。问题轮次写入 progress，无问题轮次只在任务
汇报中说明，不在三轮之间修改规划正文。

生产代码实施后，先过后端 PostgreSQL 集成与 `mvn clean compile test-compile`、前端
tsc/build/核心 Mock Playwright、隔离端口真实全栈验证，再做三轮互不重叠的只读实现审查：
事务/迁移/并发/恢复/安全；API/SSE/OpenAI/WebUI/ACL/成本；测试/运行时/文档/发布/
回滚/Git。任何实质修复都重置计数并重跑受影响门槛。

特性分支准备交付时，如果 `origin/main` 已前进，必须先 fetch 并以 merge 方式跟进，
记录 merge 后的特性 HEAD、`origin/main`、隔离数据库和端口基线；合并前的测试结论不能
作为最终证据。合并后的最终顺序固定为：

```text
后端本任务 PostgreSQL 集成矩阵 + mvn clean compile test-compile
  -> 前端 tsc、production build、核心 Mock Playwright
  -> 隔离端口真实全栈 Playwright、dev.sh 与 HTTP/数据库断言
  -> 获准且适用的真实 LLM 幂等 smoke
  -> 连续三轮限时实现审查
  -> commit/push 特性分支
  -> merge 特性分支到 main 并 push main
  -> 确认 main、origin/main 和两个 worktree 均无未提交修改
  -> 安全移除已合并的隔离特性 worktree
```

任何实质修复都会把实现审查计数重置为 `0`，并重跑受影响门槛；影响共享契约、运行拓扑
或测试基线时重跑整条最终序列。移除 worktree 前必须确认其分支提交已被已推送的 `main`
包含、worktree 无未提交修改且没有仍由该 worktree 启动的服务/测试进程；不得用强制删除
掩盖 WIP。

## 12. 发布、回滚与 Git 交付

- `Idempotency-Key` 可选，旧客户端不受影响。
- `rag.chat.idempotency.enabled` 默认开启；关闭时带 header 的请求返回
  `503 IDEMPOTENCY_DISABLED`，不静默退回非幂等执行；无 header 请求照旧。以下错误码
  必须在 `spring-ai-rag-api` 的 `ErrorCode` 中存在并覆盖测试。当前已有的
  `IDEMPOTENCY_KEY_REUSED` 与 `IDEMPOTENCY_OPERATION_IN_PROGRESS` 必须直接复用，
  不得重复添加；本轮需要新增并冻结：
  `IDEMPOTENCY_KEY_INVALID`、`IDEMPOTENCY_REQUEST_TOO_LARGE`、
  `IDEMPOTENCY_REQUEST_METADATA_INVALID`、
  `CHAT_TURN_NOT_FOUND`、`IDEMPOTENCY_DISABLED`、
  `IDEMPOTENCY_RESPONSE_TOO_LARGE`、`IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID`、
  `IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID` 和
  `IDEMPOTENCY_ATTEMPTS_EXHAUSTED`。
- V47 是纯增量迁移；回滚应用版本时保留表和数据，不执行破坏性 down migration。
- 出现异常时先关闭带 key 流量入口，保留 operation 数据诊断，不能删除表绕过冲突。
- snapshot 使用版本号和严格 JSON schema，未来变更先增加版本，不覆盖旧快照。
- 本规划阶段只提交规划、进度、归档修复、长青文档和导航修改，不创建实施 worktree。
  本地 commit 后 merge 最新 `origin/main`、重跑文档门禁、push 并确认干净，然后暂停
  等待用户 review。生产代码、真实 LLM 验收和特性分支合回 `main` 不属于本轮。
