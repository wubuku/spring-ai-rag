# Chat turn 幂等、可靠重放与低基数观测实施规划

> **状态**：规划候选，尚未开始生产代码实施
>
> **规划日期**：2026-08-22
>
> **代码基线**：`main` @ `e48fb192`，Spring Boot `3.5.16`，Spring AI `1.1.8`，
> Java `21`，Flyway V1–V46
>
> **规划分支**：`docs/next-high-value-features-plan-20260822`
>
> **当前 worktree**：`/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
>
> **实施分支要求**：实施前必须从届时最新本地 `main` 创建新的专用特性分支和隔离
> worktree；不得把本规划分支直接变成生产代码分支。
>
> **配套进度**：[NEXT_HIGH_VALUE_FEATURES_PROGRESS.md](NEXT_HIGH_VALUE_FEATURES_PROGRESS.md)

本规划是下一轮实施的单一恢复入口。它冻结本轮要解决的问题、推荐默认、数据与 HTTP
契约、文件顺序、验收矩阵和明确非目标。实现者不应因为会话中断而重新猜测核心设计。

近距离上下文：

- [Chat 记忆、RAG 与工具调用调研](../chat-memory-rag-tool-calling-zh-CN.md)
- [项目上下文](../project-context-zh-CN.md)
- [REST API 参考](../rest-api-zh-CN.md)
- [SSE 协议](../SSE-PROTOCOL.md)
- [测试指南](../testing-guide-zh-CN.md)
- [规划、实施与验收工作流](../delivery-workflow-zh-CN.md)

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
2. `ChatExecutionService` 统一非流式、原生 SSE 和 OpenAI 兼容内部执行；Spring AI
   Memory、检索、Tool Calling、模型 fallback 和 session lease 都在这条链上。
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
8. replay 前重新检查当前 principal 以及 operation 保存的有效 Collection 授权边界。
9. 提供不泄漏原始 key 的 opaque `turnId` 状态查询。
10. 以一次性设计好的 PostgreSQL/HTTP/SSE/WebUI/真实 provider 验收矩阵覆盖本轮代码。

### 3.2 非目标

- 不改变 `PLAIN / KNOWLEDGE / AGENT` 的模式和检索语义。
- 不把 `KNOWLEDGE` 检索改成 Function Calling；文档检索仍由 Spring AI Modular RAG
  或 AGENT 的服务端工具负责。
- 不实现 token 级 SSE 续传、`Last-Event-ID` 半流恢复或 provider 内部请求去重。
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
  规范值必须是 1–255 个可见 ASCII 字符，不允许内部空白、控制字符或换行。
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
  mode
  memoryMode
  modelRef
  modelCandidates
  domainId
  declared retrieval scope marker
  retrievalOptions
  retrievalFilters
  declared collection scope:
    CALLER_VISIBLE marker, ANY_COLLECTION marker, or sorted explicit IDs/keys
  document IDs
  clientMetadata (行为 metadata，不包含 transport/trace 字段)
```

`clientMetadata` 当前会进入 prompt customizer、system prompt 和最终 response metadata，
因此必须递归排序并纳入 fingerprint；实现不得把它误当成日志 metadata。以下字段明确
排除：transport 名称、HTTP trace ID、MDC、随机 completion ID、原始 Idempotency-Key、
API key、时间戳、日志 metadata 和 provider 诊断字段。

当前 `OpenAiChatRequestMapper` 会向 command metadata 写入 transport 和
`openaiModelAlias`。这些字段是 transport/协议诊断信息，必须在进入 transport-neutral
`clientMetadata` fingerprint 前移出或明确排除；只有真正影响 prompt、检索或公开业务
响应的客户端 metadata 才参与 fingerprint。

规范化规则：

1. object key 按字典序递归排序，使用 UTF-8、无空白 JSON；
2. null 和“未提供但等于服务端默认值”的字段统一为同一个规范值；
3. `modelCandidates`、`inputMessages` 保持语义顺序；
4. Explicit selected Collection IDs/keys 按请求中声明的语义去重并排序；同一请求同时
   声明 ID 与 key 时保留两者，仍由现有 scope resolver 验证它们指向同一 Collection；
   `CALLER_VISIBLE` 和 `ANY_COLLECTION` 使用固定 scope marker，不能把当前 caller
   的 allow-list 或 Collection 存在性展开结果写入 fingerprint；filters 的 object key
   递归排序；
5. 文本不做大小写折叠，不做隐式 trim，不改变用户正文；
6. canonical JSON 只在内存中生成，持久化只保存 SHA-256；
7. 可选的语义字符串沿用现有请求语义规范化；其中空白 `domainId` 等同于未提供并规范
   为 null，不能因为 controller 与 mapper 的空白处理差异产生 fingerprint conflict；
8. fingerprint 版本变化必须改变 `schemaVersion`，不得静默复用旧 hash。

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
时分配并持久化 server session；后续相同 key 的请求复用 operation 中保存的 session，
不能重新随机生成。OpenAI compatibility mapper 必须把当前请求映射成同一规则，不能
继续用“每次 mapper 调用都无条件随机 session”破坏 replay。

已有 operation 的请求必须先按当前 principal、key hash 和声明语义 fingerprint 完成
寻址，再重新解析当前 Collection scope/ACL；因此 Collection 被删除、key 不再可解析或
权限收窄时，不能在 operation lookup 前直接返回普通 Collection 404，也不能把 ACL
变化误判为 `IDEMPOTENCY_KEY_REUSED`，而应按 replay 的 fail-closed 规则返回
`403 FORBIDDEN`。首次请求的认证、语法和当前 scope 校验仍在创建 operation 前完成，
不为无效请求留下 operation。

### 4.3 状态与错误

公开错误码沿用 API `ErrorCode`：

| 场景 | HTTP | code | 语义 |
|---|---:|---|---|
| `Idempotency-Key` 为空、含控制字符/内部空白或超过 255 字符 | 400 | `IDEMPOTENCY_KEY_INVALID` | 不创建 operation |
| 同 key 不同 fingerprint | 409 | `IDEMPOTENCY_KEY_REUSED` | 不执行新 turn |
| `clientMetadata` 超过幂等请求限制 | 400 | `IDEMPOTENCY_REQUEST_TOO_LARGE` | 不创建 operation |
| 已有活跃 operation | 409 | `IDEMPOTENCY_OPERATION_IN_PROGRESS` | 不执行新 turn，带 `Retry-After` |
| operation/turn 不属于当前 principal | 404 | `CHAT_TURN_NOT_FOUND` | 不泄漏存在性 |
| 当前 replay 的 Collection ACL 已失效 | 403 | `FORBIDDEN` | 不返回旧 response |
| 带 key 但功能被运维开关关闭 | 503 | `IDEMPOTENCY_DISABLED` | 不静默退回非幂等执行 |
| 成功结果无法在 snapshot 大小上限内持久化 | 503 | `IDEMPOTENCY_RESPONSE_TOO_LARGE` | operation 进入 FAILED，不写 history/Memory |
| 客户端取消已被服务端确认 | 409 | `CHAT_TURN_CANCELLED` | 不写 history/Memory，需新 key 重试 |
| stale reclaim 达到 operation attempt 上限 | 503 | `IDEMPOTENCY_ATTEMPTS_EXHAUSTED` | 只重放稳定失败，不再调用 provider |
| operation 已成功 | 200 | 无错误 | 返回同一个版本化快照 |

如果执行在 operation 已 claim 后失败，服务端保存一个不含敏感正文的版本化稳定错误
快照并进入 `FAILED`；相同 key 后续重放同一失败，不自动重复 provider 调用。未进入
operation claim 的认证、参数和 scope 校验错误不创建 operation。故障恢复若需要重新
尝试，客户端应生成新 key。

## 5. Durable operation 设计

### 5.1 表与字段

新增 Flyway V47，创建 `rag_chat_turn_operations`，不改写 V32，不扩展
`rag_chat_history.turn_status`：

| 字段 | 约束/用途 |
|---|---|
| `id` | `BIGSERIAL`，内部 operation identity |
| `owner_principal_id` | `VARCHAR(128)`，principal 隔离 |
| `idempotency_key_sha256` | `CHAR(64)`，只存 hash |
| `request_fingerprint_sha256` | `CHAR(64)`，规范请求 hash |
| `fingerprint_version` | 正整数 |
| `session_id` | `VARCHAR(36)`，operation 分配/复用的会话 |
| `turn_id` | `UUID`，对外 opaque identity |
| `transport` | 固定枚举 `NATIVE_JSON/NATIVE_SSE/OPENAI_JSON/OPENAI_SSE` |
| `status` | `IN_PROGRESS/SUCCEEDED/FAILED/CANCELLED` |
| `operation_token` | 当前执行权 token，随机 opaque |
| `lease_expires_at` | 可回收执行租约 |
| `attempt_count` | 有界整数，用于诊断，不参与业务 fingerprint |
| `row_version` | `BIGINT NOT NULL DEFAULT 0`；每次 claim、reclaim、lease renewal 和终态转换成功时递增，用于 operation 行 CAS |
| `response_version` | 正整数；当前固定为 `1` |
| `response_payload` | 成功时的版本化 transport-neutral 结果 JSONB；可包含受控的协议 envelope 子快照 |
| `error_code` / `error_payload` | FAILED/CANCELLED 时的稳定错误快照，不含正文 |
| `authorization_scope_snapshot` | replay 前重新校验的 scope/ACL 边界 JSONB，不保存 key |
| `created_at/updated_at/completed_at` | 服务端时间戳 |

约束和索引：

- `(owner_principal_id, idempotency_key_sha256)` 唯一；
- `turn_id` 全局唯一；
- `status`、`lease_expires_at`、`updated_at` 建维护索引；
- `row_version` 必须非负；`response_version` 是响应快照 schema 版本，不能替代
  operation 行的 `row_version`；
- `IN_PROGRESS` 必须有 token 和未完成 lease；`SUCCEEDED/FAILED/CANCELLED` 不再拥有执行 token；
- `SUCCEEDED` 必须有 response payload 和 completed time；
- `FAILED/CANCELLED` 必须有稳定 error code/payload；
- `response_payload` 不允许保存原始 prompt、工具参数、工具结果或 key；
- `response_payload` 序列化后的 UTF-8 大小默认不超过 512 KiB，可逆配置边界为
  64 KiB–2 MiB。超过上限时，在写入 history/Memory 前把 operation 记录为
  `FAILED/IDEMPOTENCY_RESPONSE_TOO_LARGE`，保存稳定错误快照，不留下业务 history；
- operation retention 默认 24 小时，可配置为 1–168 小时；仅清理终态过期行。TTL 到期后
  operation replay/status 不再可用，但保留的 `rag_chat_history` 仍可通过 session history
  查询；history 不反向延长 operation retention。claim 遇到已过期终态时，必须用
  `completed_at + TTL` 条件删除并重新走唯一键 claim；删除失败则重新读取并按当前状态
  返回，不能无条件覆盖或复用旧 snapshot。并发 cleanup 与 claim 也只能通过条件 DML/CAS
  协调。

`attempt_count` 默认最多允许 3 次 operation execution attempt，可逆配置边界为
1–8；首次 claim 计为第 1 次。stale reclaim 达到上限时，operation 进入
`FAILED/IDEMPOTENCY_ATTEMPTS_EXHAUSTED`，后续相同 key 只重放该稳定错误，不再继续调用
provider。这个上限只保护 operation lease 恢复，不替代一次执行内部已有的模型、候选、
工具和 deadline 预算。

`rag_chat_history` 新增 nullable `turn_id UUID` 和 owner/turn 索引。旧行保持 null；
新成功 turn 必须写入 operation 的 `turn_id`。history 仍只使用 `COMPLETE/CANCELLED`，
不得把 `IN_PROGRESS` 写进业务 history。

`authorization_scope_snapshot` 至少包含以下服务端派生字段：

```json
{
  "scopeMode": "CALLER_VISIBLE|ANY_COLLECTION|SELECTED_COLLECTIONS",
  "effectiveSelectedCollectionIds": [1, 2],
  "callerAllowList": [1, 2],
  "unassignedDocumentsAllowed": true,
  "sourceCollectionIdsObserved": [1, 2]
}
```

replay 必须重新用当前 principal/API key 解析授权边界，并证明当前调用方仍能读取
snapshot 中所有原始有效范围和已观察来源；对 `CALLER_VISIBLE` 从 unrestricted 变为
restricted、对 selected scope 失去任一原始 Collection、或无法证明来源权限时一律
fail closed 返回 `FORBIDDEN`。当前权限变宽不需要重算旧答案，但不能扩大旧 snapshot
的可见范围。`sourceCollectionIdsObserved` 必须由服务端根据本次结果中的 document
ID 反查权威 `rag_documents.collection_id` 与 Collection 关系得到，不能信任
`ChatSource.metadata`、`collectionKey` 展示字段、模型输出或客户端提交值；任何来源
文档无法解析或来源权限无法证明时都必须 fail closed。

### 5.2 Claim、replay 与 lease

`ChatTurnOperationCoordinator` 负责以下状态转换：

```text
不存在
  -> 选择/校验 session，取得 session lease，再 INSERT IN_PROGRESS + token + operation lease
     （session lease 失败不创建 operation）
IN_PROGRESS + 当前 lease 有效
  -> duplicate => 409 IN_PROGRESS
IN_PROGRESS + lease 已过期
  -> UPDATE ... WHERE token/expiry/version 条件成立，接管并递增 attempt_count
IN_PROGRESS + 当前 token
  -> 成功事务 => SUCCEEDED + response snapshot
IN_PROGRESS + 当前 token
  -> 稳定失败事务 => FAILED + error snapshot
IN_PROGRESS + stale reclaim 达到 attempt 上限
  -> FAILED/IDEMPOTENCY_ATTEMPTS_EXHAUSTED，不再调用 provider
IN_PROGRESS + 当前 token + 明确客户端取消
  -> CANCELLED + stable cancellation error
SUCCEEDED/FAILED/CANCELLED + fingerprint 相同
  -> replay，不调用模型
终态 operation 已超过 retention TTL
  -> 通过 completed_at/TTL 条件 CAS 删除后，允许同 key 创建全新 operation
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
  若 session lease 获取失败，不创建持久 operation，直接返回现有 session busy 语义，避免
  留下无法执行的 `IN_PROGRESS` 行；
- 对已有
  `IN_PROGRESS` operation 的 stale reclaim 必须先用 operation 中保存的 session
  成功取得/确认 session lease，再用 token/expiry/version 条件 CAS 接管 operation。
  如果 session lease 仍由旧执行持有，返回 in-progress/busy，不能先夺取 operation
  token；
- operation lease 必须按请求 deadline 加 grace 设计，至少覆盖正常 Chat deadline，并由
  与 session lease 同步的有界 renew 保护；任一 lease 丢失都禁止提交；
- `MemoryMode.STATELESS` 不使用 `rag_chat_session_lease`，只取得 operation lease；
  其成功提交不消费 session lease，也不向共享 Spring AI Memory 写入内容。服务端仍须
  为 operation 分配稳定 `session_id`，用于 fingerprint、状态查询和 response snapshot，
  但不能把这个 ID 误当作已启用的会话记忆；
- 进程崩溃后只允许过期 operation 被新请求接管，不能由普通 replay 直接绕过
  fingerprint 或 ACL。

### 5.3 原子提交

成功路径的短事务顺序固定为：

1. 以 operation token CAS 把 operation 从 `IN_PROGRESS` 变为 `SUCCEEDED`，写入
   response snapshot、turn ID、completed time 和可重放授权边界；
2. 仅对 `MemoryMode.SERVER` 消费当前 session lease 的 token fence；
3. 写入 `rag_chat_history` 的 `COMPLETE` turn，并设置 `turn_id`；
4. 清理/写入 Spring AI JDBC Memory；
5. 提交事务后释放非数据库续租资源。

任何一步失败，事务回滚，不能留下“operation 成功但没有 history/Memory”的半成功状态。
如果 provider 已经返回而提交事务失败，operation 仍保持可接管的 `IN_PROGRESS`，不能
伪造成功快照；该恢复窗口按 at-least-once 记录。

SSE 在模型执行过程中不能提前把 operation 标记成功。只有聚合完成、history/Memory
提交成功后才发送 `done`。明确到达服务端的客户端取消只能通过
`WHERE status = 'IN_PROGRESS' AND operation_token = ?` 的 CAS 变为 `CANCELLED`，
成功提交已经把 operation 变为 `SUCCEEDED` 时成功终态优先，取消不得覆盖它；取消不写
history/Memory。如果只观察到进程/网络消失而没有执行取消回调，operation 保持
`IN_PROGRESS`，由 lease expiry reclaim 处理。

对于携带 `Idempotency-Key` 的请求，response snapshot 必须在上述短事务开始前
冻结，并且是首次响应与后续 replay 共同使用的唯一公开响应。当前实现会在业务 turn
提交之后再执行 best-effort 的会话摘要压缩；这类提交后可变的摘要 metadata 不得在
operation 成功后再追加到幂等响应，否则首次响应和 replay 会不一致。推荐实现是：
幂等路径以提交前已经完成的稳定结果（包括执行预算 metadata）生成 snapshot，摘要压缩
仍可在提交后 best-effort 执行，但不得修改已返回的响应或 operation snapshot。无 key
路径可以保留当前摘要 metadata 行为。未来若要把摘要纳入幂等响应，必须先设计独立的
预处理/提交流程并重新冻结这一响应一致性契约。

这里的“同一响应”指持久化的 response body/envelope、`turnId`、session、completion
identity、公开业务 metadata，以及已经写入 `ChatResponse` 的业务/检索诊断引用
（例如 `traceId` 或 `metadata.retrievalTraceId`）；这样 replay 仍能指向首次执行的
同一诊断记录。请求级 HTTP trace header、MDC 和本次请求的 span 仍是传输诊断信息，
不属于 snapshot，replay 可以重新生成。`X-RAG-Idempotent-Replay` 是每次传输单独生成的
诊断 header，首次为 `false`、replay 为 `true`，也不属于不可变 snapshot。
`X-RAG-Turn-Id` 和 OpenAI completion ID 则必须在同一 operation 的对应协议语义内保持稳定。

### 5.4 TTL 与清理

新增维护任务只清理 `SUCCEEDED/FAILED/CANCELLED` 且 `completed_at` 超过 idempotency TTL 的
operation；`IN_PROGRESS` 只在下一次 claim 时按 lease expiry 回收，不由无条件 DELETE
删除。清理必须：

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
- response body 使用版本化 snapshot 恢复，replay 不生成新的 trace/completion 内容。

首次请求无 session 时，body 返回 operation 分配的 session；同 key 重试必须返回同一
session。无 key 请求不新增 header 语义，仍沿用现有自动生成 session。

### 6.2 原生 SSE

`POST /api/v1/rag/chat/stream` 接受相同 header。首个成功执行：

- 在 HTTP header 尽早写入 `X-RAG-Turn-Id`，但 header 不表示已提交成功；
- 在返回 HTTP 200、创建 `SseEmitter` 或订阅执行 Flux 之前同步完成 operation claim；
  若已有活跃 operation，直接返回 409 和 `Retry-After`，不能把 claim 冲突延迟到已开始
  的 SSE 流中；
- `done` payload 增加 `turnId`、`idempotentReplay=false`；
- 只有 `done` 后才表示 operation/history/Memory 已提交。

已成功 operation 的 replay 不重新连接 provider，只发送一组完整、有限的业务事件：

```text
content (完整 answer 一次)
sources (若有)
done (完整 metadata、turnId、idempotentReplay=true)
```

replay 不发送旧的 tool activity，不承诺原始 token chunk 的节奏。`IN_PROGRESS` 重试在
HTTP headers 尚未提交前返回 409；若调用方已经拥有一条旧 SSE 连接，仍由旧连接继续
接收其结果或失败。服务端不实现 `Last-Event-ID`。

### 6.3 OpenAI 兼容 JSON/SSE

`POST /v1/chat/completions` 从 `HttpServletRequest` 读取同一 header，并将其传给
transport-neutral operation。operation snapshot 由共同的 Chat result 和受控的
`transportEnvelopes` 组成：对于带 `Idempotency-Key` 的 operation，completion ID 由
`turnId` 以稳定、不可逆的格式派生，不依赖随机数，也不在终态 operation 上做事后可变
更新；首次通过原生 transport 后再通过 OpenAI transport，仍可由共同结果生成同一个协议
envelope。后续跨 transport replay 可以由共同结果生成协议所需 envelope，但不调用模型。
对于 keyed operation，首次确定的 OpenAI `model` envelope 字段和 `created` epoch 也必须
从 operation 的稳定语义/创建时间派生并持久化在版本化 envelope 中；后续请求不能因为
传入不同但语义等价的 alias 而改变这些字段。对 OpenAI 同一 transport 的 replay 返回同一个
版本化 `chat.completion` snapshot 和同一个 completion ID；SSE replay 发送与首次相同的
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
  "status": "IN_PROGRESS|SUCCEEDED|FAILED|CANCELLED",
  "transport": "NATIVE_SSE",
  "createdAt": "...",
  "updatedAt": "...",
  "completedAt": null,
  "replayAvailable": false,
  "errorCode": null,
  "response": null
}
```

成功状态可以返回与原生 Chat 相同的 response snapshot；IN_PROGRESS 不返回 prompt、
工具输入或部分答案；FAILED 只返回稳定 error code。其他 principal、未知 turn 或已过期
operation 统一为 not found，不暴露存在性。

当状态为 `SUCCEEDED` 且请求方要求返回 response snapshot 时，必须重新执行与普通 replay
相同的 principal、Collection scope 和来源 ACL 校验；权限收窄时返回 `403 FORBIDDEN`，
不能因为状态查询是 GET 就绕过 replay 的 fail-closed 规则。仅返回状态字段时可以不返回
快照，但仍须按当前 principal 查询。

## 7. WebUI 语义

WebUI `useSSE` 在每次用户发送 turn 时生成一个 UUID 作为 `Idempotency-Key`，并在
同一次 `send` 的网络重试中复用它。它不在未知失败后自动无限重试，也不把 key 写入 URL、
localStorage、history 或 DOM。

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
tags: result=success|failure|replay|conflict|in_progress|cancelled,
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
tags: transport, mode, result=success|failure|replay|cancelled
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
   `ChatSessionCoordinator`；确保 operation/history/Memory/session fence 同事务提交。
4. **原生 controller/API**：JSON/SSE headers、done metadata、replay events、status API。
5. **OpenAI compatibility**：header、稳定 session、JSON/SSE replay，保持 tools/functions
   拒绝和现有 error envelope。
6. **WebUI**：`useSSE.ts`、Chat 类型和错误处理；Vitest、Mock Playwright、生产 bundle。
7. **观测与清理**：Micrometer、TTL maintenance、固定 tag 合约和隐私测试。
8. **文档与交付**：双语长青文档、plan/progress 归档、merge 最新 `origin/main`、复验、
   合回 `main`、push。

不得在步骤 1–7 之间先开放真实 provider；先完成一次性验收矩阵，再按门槛顺序执行。

如果运行时未启用 mode-aware `ChatExecutionService`、operation repository 或所需
PostgreSQL 能力，带 `Idempotency-Key` 的请求必须返回 `IDEMPOTENCY_DISABLED`，不能退回
当前 `RagChatService` 的 legacy `executeChat` 非幂等路径；不带 key 的旧请求才允许保留
legacy 兼容行为。

## 10. 一次性验收矩阵

### 10.1 后端快速与 PostgreSQL 集成

必须新增覆盖本次代码路径的集成/E2E 测试，而不是只测 coordinator 单元：

| 场景 | 证据 |
|---|---|
| 首次 native JSON 成功 | HTTP 200、turn header/body、一次 provider stub、history/Memory/operation 一致 |
| 幂等输入边界 | 非法 key、32 KiB metadata、credential 字段、snapshot 超限均返回冻结错误码，且无不完整 history/Memory |
| 同 key 完成 replay | 第二次 body/header 相同、provider 调用次数仍为 1、无第二条 history |
| 同 key fingerprint 冲突 | 409 + `IDEMPOTENCY_KEY_REUSED`，无模型调用 |
| 同 key 并发 | PostgreSQL 真实唯一约束，只有一个执行者，另一个 409 + Retry-After |
| stale operation reclaim | lease expiry 后 CAS 接管；旧 token 不能提交 |
| principal 隔离 | 另一个 principal 不能 replay 或查询 turn |
| ACL 变化 | Collection 失权后 replay 返回 403，不返回旧 response |
| provider/commit failure | FAILED 或可接管状态符合契约，不出现孤儿 history/Memory |
| native SSE 首次/重连 replay | 首次有工具/内容事件和最终 done；replay 只发送完整 answer、sources、done |
| OpenAI JSON/SSE | snapshot replay、错误 envelope、同 key 冲突、tools/functions 仍拒绝 |
| STATELESS 与 legacy 兼容 | STATELESS 不创建 session lease；mode-aware 依赖不可用时带 key 拒绝、无 key 仍按旧路径工作 |
| status API | IN_PROGRESS/SUCCEEDED/FAILED/CANCELLED、principal scope、过期/未知 not found、`includeResponse=true` 的快照与 ACL 复核 |
| cleanup | 只清理终态过期行，不删除有效 lease/operation |

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
覆盖每个发送请求的 key、同一次 retry 的 key 复用、新 turn 新 key、done turn ID、
409 输入保留、replay 不重复 assistant bubble 和 status JSON 映射。禁止用截图作为
验收证据。

### 10.3 隔离端口运行时

在非 main worktree 使用独立后端、前端和测试数据库，记录合并后验证基线。启动
`postgresql` profile 的服务，使用项目已有 dev launcher/真实 E2E 脚本，必要时为本轮
增加 idempotency smoke。必须用 `curl` 或 Playwright 观察真实 HTTP header、JSON、SSE
和只读数据库状态，确认服务可启动、operation 迁移可执行、旧无 key 客户端仍兼容。

### 10.4 真实 LLM

用户已明确允许使用 `.env` 中的真实 provider。顺序固定：Mock/PostgreSQL/前端门槛
全部通过后，使用隔离端口加载 `.env`，执行有界 native JSON、native SSE、OpenAI
JSON/SSE（若启用）场景；对同一成功 key 重复请求并确认 provider 调用计数不增加；
调用计数必须通过 `rag.chat.provider.calls.total` 的前后差值与脱敏日志交叉确认，
不能以外部 provider 控制台或猜测代替证据；
人为制造客户端超时/断开后查询 turn status，再验证 replay；持续观察日志，清理测试
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

## 12. 发布、回滚与 Git 交付

- `Idempotency-Key` 可选，旧客户端不受影响。
- `rag.chat.idempotency.enabled` 默认开启；关闭时带 header 的请求返回
  `503 IDEMPOTENCY_DISABLED`，不静默退回非幂等执行；无 header 请求照旧。以下错误码
  必须在 `spring-ai-rag-api` 的 `ErrorCode` 中存在并覆盖测试。当前已有的
  `IDEMPOTENCY_KEY_REUSED` 与 `IDEMPOTENCY_OPERATION_IN_PROGRESS` 必须直接复用，
  不得重复添加；本轮需要新增并冻结：
  `IDEMPOTENCY_KEY_INVALID`、`IDEMPOTENCY_REQUEST_TOO_LARGE`、
  `CHAT_TURN_NOT_FOUND`、`CHAT_TURN_CANCELLED`、`IDEMPOTENCY_DISABLED`、
  `IDEMPOTENCY_RESPONSE_TOO_LARGE` 和 `IDEMPOTENCY_ATTEMPTS_EXHAUSTED`。
- V47 是纯增量迁移；回滚应用版本时保留表和数据，不执行破坏性 down migration。
- 出现异常时先关闭带 key 流量入口，保留 operation 数据诊断，不能删除表绕过冲突。
- snapshot 使用版本号和严格 JSON schema，未来变更先增加版本，不覆盖旧快照。
- 本规划阶段只提交规划、进度、归档修复、长青文档和导航修改，不创建实施 worktree。
  本地 commit 后 merge 最新 `origin/main`、重跑文档门禁、push 并确认干净，然后暂停
  等待用户 review。生产代码、真实 LLM 验收和特性分支合回 `main` 不属于本轮。
