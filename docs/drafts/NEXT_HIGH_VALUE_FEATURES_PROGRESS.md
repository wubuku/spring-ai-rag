# Chat turn 幂等、可靠重放与低基数观测进度

> **状态**：规划阶段；生产代码尚未实施
>
> **当前分支**：`docs/next-high-value-features-plan-20260822`
>
> **当前 worktree**：`/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
>
> **规划基线**：`main` / `origin/main` @ `e48fb192`
>
> **实施规划**：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)

本文件是跨会话恢复账本，不替代代码和长青文档。每次进入下一阶段前，先更新本文件
再执行下一步。

## 1. 当前阶段

| 阶段 | 状态 | 说明 |
|---|---|---|
| 代码与文档探索 | 已完成 | 已核对 Chat controller/service、session lease、history、V32/V46、OpenAI mapper/controller、SSE、WebUI |
| 上一轮规划归档 | 已完成 | 上一轮 plan/progress 已移入 `docs/drafts/archive/`，并修正迁移后的相对链接 |
| 新规划编写 | 已完成 | P0 durable turn operation；P1 低基数 Chat 观测 |
| 规划连续审查 | 进行中 | 当前计数见第 2 节 |
| 长青文档同步 | 已完成 | 已同步当前“无 request-level 幂等/重放”的边界与后续入口 |
| 生产代码实施 | 未开始 | 用户要求本轮在规划交付后暂停 |

## 2. 规划审查账本

规则：发现实质问题并修改规划正文时，计数重置为 `0`；只有连续三轮无修改才算
完成。无问题轮次不在文档中追加，避免破坏连续无修改证据；问题轮次记录在这里。

**当前有效计数：`3/3`。** 早于下表最后一次实质修改的通过记录均已作废。

### 2026-08-22 初始审查前置记录

- 范围：从最新 `main @ e48fb192` 重建当前方向。
- 结论：本轮不实施上下文预算、持久摘要、Tool Provider 或 `EACH_COLLECTION`；只处理
  Chat turn 幂等、完成快照重放、状态查询和低基数指标。
- 关键默认：可选 header、principal + hash 唯一、SHA-256 fingerprint、CAS/lease、
  24 小时 operation retention、SSE 完整 replay、不承诺 token 级续传。
- 生产代码、实施 worktree、真实 LLM 测试均明确留到用户 review 后。

### 发现问题时追加

| 时间 | 检查范围 | 发现 | 处理 | 计数 |
|---|---|---|---|---:|
| 2026-08-22 | 需求闭环/自包含性 | 第 1 轮发现跨 transport response snapshot、turn not found/disabled 错误码、operation retention，以及 `clientMetadata` 未纳入 fingerprint 的语义需要进一步冻结 | 修正规划正文：加入行为 metadata、公共响应快照的大小/敏感字段边界，并重启第 1 轮 | 0 |
| 2026-08-22 | 代码/数据库/API/安全/并发 | 第 2 轮发现 replay ACL 仅保存 Collection ID 不足、stale operation 接管顺序可能夺取仍活跃的 session lease、SSE 取消终态和 metadata 输入上限未冻结 | 修正规划正文：保存可重放授权边界、先确认 session lease 再接管、增加 cancellation 终态，并冻结 metadata 超限/credential 字段错误；计数重置为 0 | 0 |
| 2026-08-22 15:55 CST | 代码/数据库/API/安全/并发 | 第 2 轮继续复核发现当前摘要压缩在 history/Memory 提交后追加 response metadata，若直接快照可能导致首次响应与 replay 不一致；同时 `tool_name` 作为 metrics reason 会形成高基数标签 | 修正规划正文：幂等路径以提交前稳定结果作为唯一 snapshot，摘要压缩改为不改变幂等响应的提交后 best-effort；预算指标 reason 冻结为固定枚举，工具名仅进入受控日志/trace；计数重置为 0 | 0 |
| 2026-08-22 15:55 CST | 代码/数据库/API/安全/并发 | 第 2 轮再次交叉核对发现 STATELESS 不应取得会话 lease，SSE 必须在 200/emitter 前完成 claim，状态查询快照不能绕过 ACL，OpenAI 客户端还需要获取 `turnId` 的响应 header | 修正规划正文：冻结 STATELESS 例外、SSE 同步 claim、状态快照 ACL 复核和 OpenAI `X-RAG-Turn-Id` header；计数继续为 0 | 0 |
| 2026-08-22 15:55 CST | 代码/数据库/API/安全/并发 | 第 2 轮再次交叉核对发现 OpenAI mapper 的 transport/alias metadata 可能污染跨 transport fingerprint，legacy Chat 路径可能绕过幂等协调器，response snapshot 没有持久化大小边界，验收矩阵也未覆盖这些契约 | 修正规划正文：排除 transport/alias metadata、依赖不可用时带 key 明确拒绝、冻结 512 KiB 默认 snapshot 上限与稳定失败码，并补充一次性验收场景；计数继续为 0 | 0 |
| 2026-08-22 15:55 CST | 代码/数据库/API/安全/并发 | 第 2 轮复核发现原生 SSE 小节没有与 OpenAI 小节同等明确 claim 必须发生在 HTTP 200/emitter/Flux 之前 | 修正规划正文：补充 native SSE 的同步 claim 与直接 409 语义；计数继续为 0 | 0 |
| 2026-08-22 15:55 CST | 代码/数据库/API/安全/并发 | 第 2 轮复核发现终态 operation 到期后的 key 复用路径没有状态转换，可能让已过期 key 永久 replay 或与 cleanup 竞态 | 修正规划正文：冻结终态 TTL 到期时的条件删除、重新 claim 和 cleanup/claim CAS 协调；并补充新增 snapshot 错误码的 API 枚举要求；计数继续为 0 | 0 |
| 2026-08-22 15:55 CST | 代码/数据库/API/安全/并发 | 第 2 轮复核发现当前 Hybrid Retriever 不保证在结果 metadata 中提供权威 Collection 信息，replay ACL 若依赖 ChatSource 展示字段会有越权风险 | 修正规划正文：要求根据结果 document ID 反查权威 `rag_documents.collection_id`/Collection，并在来源无法解析或授权无法证明时 fail closed；计数继续为 0 | 0 |
| 2026-08-22 15:55 CST | 代码/数据库/API/安全/并发 | 第 2 轮复核发现把 CALLER_VISIBLE 当前 allow-list 展开结果纳入 fingerprint 会把 ACL 收窄误判为 key conflict，绕过设计中的 replay 403 | 修正规划正文：fingerprint 改为稳定的语义 scope marker/显式 selected IDs，当前 ACL 与来源范围只保存在 authorization snapshot 并在 replay 时复核；计数继续为 0 | 0 |
| 2026-08-22 15:55 CST | 代码/数据库/API/安全/并发 | 第 2 轮复核发现先做 ACL-dependent scope 解析再 lookup operation，会让 Collection key 失效/权限收窄时无法稳定得到 replay 的 403 语义 | 修正规划正文：请求声明 fingerprint 前置，已有 operation 先按 principal/key/fingerprint 寻址，再重新解析 ACL；首次请求仍在 claim 前完成 scope 校验；计数继续为 0 | 0 |
| 2026-08-22 16:09 CST | 代码/数据库/API/安全/并发 | 复核进一步确认 fingerprint 必须基于独立的 transport-neutral canonical input；若直接复用尚未完成 scope 解析的 `ChatCommand`，容易把 transport 或 ACL 派生字段带入寻址语义 | 修正规划正文：明确 canonical input 与 `ChatCommand` 解耦，先依据请求声明完成 operation lookup，再在首次 claim 前解析当前 scope；计数继续为 0 | 0 |
| 2026-08-22 16:14 CST | 代码/API/兼容性/恢复 | 第 2 轮复核发现现有随机 session 生成点、非法 `Idempotency-Key` 错误语义、status API 的快照选择，以及 operation reclaim 的尝试上限未完全冻结；此外“不可变响应快照”与 replay 诊断 header 的边界存在歧义 | 修正规划正文：增加声明 session envelope 与 scope 两阶段解析、`IDEMPOTENCY_KEY_INVALID`、`includeResponse=true`、3 次（1–8 可配）reclaim 上限及 `IDEMPOTENCY_ATTEMPTS_EXHAUSTED`，并明确 `X-RAG-Idempotent-Replay` 不属于不可变 snapshot；计数重置为 0 | 0 |
| 2026-08-22 16:17 CST | API/跨 transport/输入规范化 | 复核发现多值幂等 header、空白可选 `domainId` 与“先原生后 OpenAI”时的 completion ID 若不冻结，会导致相同请求出现不同 fingerprint 或要求终态快照可变更新 | 修正规划正文：要求单一 `Idempotency-Key`、空白 `domainId` 规范为 null，并将 keyed OpenAI completion ID 改为由 `turnId` 稳定派生；补充 status 的稳定 `errorCode` 字段；计数重置为 0 | 0 |
| 2026-08-22 16:19 CST | OpenAI 兼容性/状态机/验收 | 复核发现 keyed OpenAI replay 除 completion ID 外还可能改变 `model`、`created` 或缺少 role 前导 chunk；reclaim 上限虽已定义但状态机和矩阵未完全体现 | 修正规划正文：冻结 keyed OpenAI envelope 的稳定 model/created、SSE role 前导 chunk、attempt-exhausted 终态及 status/ACL 验收；计数重置为 0 | 0 |
| 2026-08-22 16:21 CST | 并发/事务/SSE 取消 | 复核发现新 key 在 session lease 获取失败时若先创建 `IN_PROGRESS` operation 会留下无法执行的 durable 行；SSE 提交成功与断开取消也需要明确终态优先级 | 修正规划正文：新 operation 先完成 lookup、获取 session lease 再做唯一 claim，claim 竞争失败释放 lease 并重读；取消仅能 CAS `IN_PROGRESS`，`SUCCEEDED` 成功终态优先；计数重置为 0 | 0 |
| 2026-08-22 16:22 CST | 配置/恢复/成本 | 复核发现 retention、snapshot 上限、reclaim 次数、lease 宽限和 cleanup 批量虽给出默认值与范围，但未定义稳定配置键，实施时可能出现不可审计的隐式默认 | 修正规划正文：冻结 `rag.chat.idempotency.*` 配置键、默认值、范围及 deadline+grace lease 规则；计数重置为 0 | 0 |
| 2026-08-22 16:23 CST | 规划内部一致性/并发 | 第 2 轮收敛检查发现状态图仍写成先 INSERT operation 再取得 session lease，与正文冻结的“session lease 成功后再唯一 claim”相矛盾 | 修正规划正文：状态图改为先选择/校验 session、取得 session lease，再 INSERT operation；session busy 不创建 operation；计数重置为 0 | 0 |
| 2026-08-22 16:23 CST | 规划内部一致性/STATELESS | 第 2 轮收敛检查发现成功事务步骤无条件消费 session lease，与 `MemoryMode.STATELESS` 不创建/不消费 session lease 的设计相矛盾 | 修正规划正文：成功事务仅在 `MemoryMode.SERVER` 消费 session lease token fence；计数重置为 0 | 0 |
| 2026-08-22 16:34 CST | 代码/API/规划准确性 | 第 2 轮复核发现当前 `ErrorCode` 已存在 `IDEMPOTENCY_KEY_REUSED` 与 `IDEMPOTENCY_OPERATION_IN_PROGRESS`，但发布章节把后者列为新增错误码，实施时可能重复添加 | 修正规划正文：明确复用已有枚举，仅新增缺失的 Chat turn 错误码；计数重置为 0 | 0 |
| 2026-08-22 16:39 CST | 规划内部一致性/并发 | 第 2 轮复核发现状态机要求 `token/expiry/version` CAS，但 operation 字段表没有独立的行版本字段，容易把 `response_version` 错当并发版本 | 修正规划正文：增加 `row_version`，规定其与 `response_version` 分离并在每次 operation CAS 成功时递增；计数重置为 0 | 0 |
| 2026-08-22 16:44 CST | 规划内部一致性/API replay | 第 2 轮复核发现现有 `ChatResponse` 的业务/检索诊断 `traceId` 与请求级 HTTP trace/MDC 未在快照边界中区分，replay 可能出现响应体或诊断引用不稳定 | 修正规划正文：保留业务诊断引用于不可变 snapshot，明确请求级 trace header/MDC 可按请求重新生成；计数重置为 0 | 0 |
| 2026-08-22 16:49 CST | 验收可执行性/真实 LLM | 第 2 轮复核发现现有 `ModelMetricsService` 未接入 mode-aware Chat 主路径，验收矩阵无法直接证明 replay 未再次调用真实 provider | 修正规划正文：冻结低基数 `rag.chat.provider.calls.total`，以应用内 counter 前后差值和脱敏日志作为真实 replay 证据；计数重置为 0 | 0 |
| 2026-08-22 17:12 CST | 当前实现事实/入口兼容性 | 复核发现 native Chat 实际经由 `RagChatService` 选择 mode-aware 路径或 legacy fallback，规划原文把 `ChatExecutionService` 描述成无条件统一入口，可能导致实施遗漏 legacy 拒绝边界 | 修正规划正文：明确 native adapter、mode-aware delegation、legacy fallback 和“带 key 禁止退回 legacy”语义；计数重置为 0 | 0 |
| 2026-08-22 17:12 CST | replay 诊断一致性 | 复核发现当前 `ChatExecutionResult.traceId` 来自请求级 MDC，不能与 `metadata.retrievalTraceId` 一起作为稳定业务诊断快照；原文会导致 replay 持久化错误的 request trace | 修正规划正文：仅稳定业务/检索引用进入 snapshot，请求级 `traceId`、MDC、HTTP trace 和 span 可按请求变化，并更新 native JSON 验收语义；计数重置为 0 | 0 |
| 2026-08-22 17:12 CST | OpenAI fingerprint/兼容性 | 复核发现“语义等价 alias 不改变 envelope”与 fingerprint 未定义 alias 归一化相互矛盾，alias registry 变化时可能出现不可判定的 key conflict | 修正规划正文：将公开 alias 作为声明语义纳入 `modelRef`，不同 alias 即使映射同一 candidate 也按 `KEY_REUSED`，不假设 alias 可互换；计数重置为 0 | 0 |
| 2026-08-22 17:12 CST | 错误安全/真实 LLM 可执行性 | 复核发现 `error_payload` 没有独立大小/敏感字段约束，且现有真实 smoke 强制 Embedding preflight，无法在仅有 Chat key 时证明 PLAIN 幂等 replay | 修正规划正文：增加 16 KiB 固定错误快照边界，并将真实验收先固定为无需 Embedding 的 PLAIN Chat smoke，明确 actuator counter 证据和 Embedding 条件分支；计数重置为 0 | 0 |
| 2026-08-22 17:13 CST | 规划内部一致性/OpenAI replay | 修正 fingerprint 规则后，OpenAI transport 小节仍保留“语义等价 alias 不改变 envelope”的旧表述，可能让实现者误以为 alias 可以绕过 `KEY_REUSED` | 修正规划正文：明确不同 alias 直接返回 `IDEMPOTENCY_KEY_REUSED`，同一 alias replay 使用已保存 envelope；计数继续为 0 | 0 |
| 2026-08-22 17:22 CST | 原子提交/执行边界 | 现有 `ChatExecutionService` 在内部提交 history/Memory 并释放 session lease，外层事后写 operation snapshot 无法保证 durable operation 与业务 turn 原子一致；同时 `PLAIN` 的无关 ACL 变化和 keyed SSE 取消边界未冻结 | 修正规划正文：增加 prepare/execute 与 coordinated commit 两阶段边界；明确 provider/基础设施失败的 `FAILED`/可接管分类；增加 `PLAIN` 的 `NOT_APPLICABLE` scope 和仅对服务端确认的 keyed SSE 取消适用规则；计数重置为 0 | 0 |
| 2026-08-22 17:22 CST | 规划内部一致性/状态查询 | `PLAIN` 新增 `NOT_APPLICABLE` scope 后，fingerprint/授权示例和 status API 的 ACL 语句仍可能要求所有模式执行 Collection 复核 | 修正规划正文：补齐 `NOT_APPLICABLE` marker/空授权快照，并明确 `PLAIN` 的 replay 与 status snapshot 不执行 Collection ACL 复核；计数重置为 0 | 0 |
| 2026-08-22 17:24 CST | API 错误契约/兼容性 | 规划要求 `IDEMPOTENCY_OPERATION_IN_PROGRESS` 返回 `Retry-After`，但现有 native 与 OpenAI 异常处理器没有统一 header 映射和剩余 lease 数值规则 | 修正规划正文：冻结按 operation lease 剩余时间向上取整、1–60 秒边界，并要求两个异常处理器共享映射；计数重置为 0 | 0 |
| 2026-08-22 17:25 CST | 并发/执行边界 | 现有 `ChatExecutionService.execute/stream` 会自行 acquire/release session lease；若幂等 coordinator 只在外层保存 lease，SSE 订阅仍可能绕过 operation 取消与 token fence | 修正规划正文：要求将已有 operation/session context 显式传入 prepare/execute，禁止内部二次 acquire/release，并要求 native/OpenAI SSE 取消回调同一 operation context；计数重置为 0 | 0 |
| 2026-08-22 17:27 CST | 跨 transport fingerprint/兼容性 | canonical input 同时要求 `inputMessages` 且宣称 native/OpenAI 可共享 operation，但原生请求没有多消息字段，未定义其 canonical 映射，可能让相同单条 user 请求产生不同 hash | 修正规划正文：原生 canonical `inputMessages` 固定为单条 user 消息，OpenAI 保留完整消息顺序；明确只有 canonical 语义相同的单条 user 请求跨 transport replay；计数重置为 0 | 0 |
| 2026-08-22 17:27 CST | OpenAI 兼容性/公开稳定性 | keyed OpenAI completion ID 要求稳定且不可逆派生，但原规划未冻结算法；跨 transport replay 可能出现不同 ID 或把随机生成留到实施阶段 | 修正规划正文：冻结 `chatcmpl-rag-` + `SHA-256("openai-completion-v1:" + turnId)` 完整小写 hex 的确定性格式，并要求 JSON/SSE replay 验收；计数重置为 0 | 0 |
| 2026-08-22 17:41 CST | 代码/安全/并发/兼容性 | 第 2 轮继续复核发现 PLAIN 可能在 lookup 前触发无意义的 scope/ACL 解析；replay 授权证据不能替代当前权威 Collection/Document 状态；请求级 trace 可能被整体 metadata 快照带入；diagnostics session 创建早于 claim；FAILED/CANCELLED 收尾、SSE timeout 与客户端取消边界、Retry-After 携带方式、OpenAI `model/created` 派生和 V47 物理约束仍有实施歧义 | 修正规划正文：冻结 PLAIN 先拒绝 scope 且不查询 ACL、权威来源回查与 fail-closed、显式 snapshot allow-list 和稳定 retrieval trace 绑定、失败/取消协调事务、timeout 不等于取消、共享 Retry-After 映射、基于 immutable operation 时间/alias 的 OpenAI envelope、PostgreSQL 类型与 CHECK 约束；计数重置为 0 | 0 |
| 2026-08-22 | 规划可实施性/规范化/SSE 生命周期 | 第 2 轮重启后的第一遍发现 `AUTO_SESSION` 首次分配算法、`documentIds` 顺序规范化和服务端 timeout 触发 dispose 时的取消原因隔离仍未冻结，实施时可能产生不同 fingerprint 或误写 `CANCELLED` | 修正规划正文：冻结 UUID v4 候选 session、document ID 数值排序去重，以及 timeout/client-disconnect 分离的 cancellation reason；计数重置为 0 | 0 |
| 2026-08-22 17:47 CST | 幂等语义/配置漂移/恢复 | 复核发现 fingerprint 若包含当前 alias registry、服务端默认值或 resolved candidate chain，配置变化会让同一声明请求无法稳定 replay，stale reclaim 也可能改变执行语义 | 修正规划正文：fingerprint 改为客户端声明字段；新增 immutable `execution_snapshot` 保存首次解析的 mode/memory/candidates/retrieval/domain routing，恢复执行优先使用该快照；计数重置为 0 | 0 |
| 2026-08-22 17:48 CST | 规划内部一致性/规范化 | `DEFAULT` marker 与原先“未提供且等于服务端默认值统一”的规则冲突，可能让稳定公共默认值和可变部署默认值产生不一致 fingerprint | 修正规划正文：仅稳定公开协议默认值允许显式/省略归一化；alias/domain/部署配置默认值固定使用 `DEFAULT` marker；计数重置为 0 | 0 |
| 2026-08-22 | 规划内部一致性/术语 | fingerprint 字段改名后，OpenAI 跨 transport 小节仍引用 `modelRef`，且 mode 默认 marker 描述未区分原生稳定默认值与 alias 默认值 | 修正规划正文：统一使用 `declared model identifier`，并明确 native public default 与 OpenAI alias `DEFAULT` marker 的归一化规则；计数重置为 0 | 0 |
| 2026-08-22 | OpenAI replay/恢复 | 第 1/3 遍复核发现仅保存 fingerprint hash 和 resolved candidate chain 无法在重启或跨 transport replay 时恢复公开 `model` alias，重新读取当前 registry 又会破坏稳定性 | 修正规划正文：将 declared model identifier 纳入 immutable `execution_snapshot`，OpenAI replay 从快照恢复；计数重置为 0 | 0 |
| 2026-08-22 | 规划内部一致性/并发/数据一致性 | 第 3 轮复核发现同一显式 session 的同 key 请求在“session lease 已取得但 operation 尚未 INSERT”的窗口可能被错误返回普通 `SESSION_BUSY`；同时 history 未明确用数据库唯一性保护 `turn_id` | 修正规划正文：session lease 获取失败后按 principal + key hash + fingerprint 重读 operation，已存在则返回幂等状态；为 `rag_chat_history.turn_id` 增加非空 partial unique index；计数重置为 0，旧的第 3/3 结论作废 | 0 |
| 2026-08-22 | 规划内部一致性/数据库约束 | 第 3 轮复核发现 `authorization_scope_snapshot` 同时被描述为默认 `{}` 与“非空”，在 PostgreSQL 约束语义上自相矛盾 | 修正规划正文：改为默认 `{}` 且非 `NULL`，并要求按 `scopeMode` 校验固定结构；计数重置为 0 | 0 |
| 2026-08-22 | 规划安全/ACL replay | 第 2 轮复核发现授权快照只有 `callerAllowList` 数组，不能无歧义表达首次调用方是 unrestricted 还是 restricted，无法可靠检测权限收窄 | 修正规划正文：增加 `callerAccessMode=NOT_APPLICABLE|UNRESTRICTED|RESTRICTED`，冻结两种模式的快照内容与 replay 覆盖规则；计数重置为 0 | 0 |
| 2026-08-22 | 规划安全/ACL/检索语义 | 第 2 轮继续复核发现正式语义允许 `CALLER_VISIBLE` 读取未归属文档，但原规划对 `collection_id=NULL` 来源一律 fail closed，且只保存 Collection ID 集合无法区分首次未归属与后续移出 Collection | 修正规划正文：增加 `sourceDocumentCollectionSnapshot` 的逐文档首次归属映射；仅在首次未归属、CALLER_VISIBLE、unrestricted 且当前仍未归属时允许 replay；统一补充 ANY_COLLECTION/SELECTED_COLLECTIONS 的 caller access 校验；计数重置为 0 | 0 |
| 2026-08-22 | 规划内部一致性/幂等寻址 | 第 2 轮复核发现“按 principal + key hash + fingerprint 寻址”的表述可能导致实现把 fingerprint 放入物理 lookup 条件，无法稳定返回同 key 不同请求的 `IDEMPOTENCY_KEY_REUSED` | 修正规划正文：明确先按 principal + key hash 读取唯一 operation，再比较 fingerprint，并在 ACL/Collection 解析前处理冲突；计数重置为 0 | 0 |
| 2026-08-22 18:09 CST | 交付可实施性/验收证据/协议一致性 | 第 3 轮发现 native SSE 的 `done.idempotentReplay` 未明确排除在 immutable response snapshot 之外；同时现有 Chat 一键门禁没有被规划明确绑定到本轮幂等 PostgreSQL/真实 Chat smoke，可能出现测试存在但脚本未执行的假通过 | 修正规划正文：冻结该字段为 per-request 诊断值，并要求扩展 `verify-chat-capability.sh` 与新增专用真实 Chat 幂等 smoke；补充固定 counter 观测端点要求；计数重置为 0，重新开始三轮审查 | 0 |
| 2026-08-22 | 规划可实施性/恢复与成本安全 | 第二轮复核发现 keyed replay 仍可能先经过现有 circuit breaker/provider 可用性门禁；同时 alias/domain registry 变化时，若在 operation lookup 前解析当前 registry，已成功 operation 无法从 execution snapshot 稳定恢复；execution snapshot 自身也缺少独立大小与敏感字段边界 | 修正规划正文：冻结 declaration lookup/replay 先于 provider 可用性门禁；已有 operation 不依赖当前 alias/domain registry，首次 claim 才解析并写入 immutable snapshot；增加 64 KiB 默认、16–256 KiB 可逆范围的 execution snapshot 限制及 `IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID`；计数重置为 0 | 0 |
| 2026-08-22 | 规划可实施性/配置与验收闭环 | 修订后复核发现 execution snapshot 已有默认大小和错误码，但未冻结稳定配置键，也未在一次性验收矩阵中直接覆盖该错误终态 | 修正规划正文：增加 `rag.chat.idempotency.execution-snapshot-max-bytes`（默认 65536，范围 16384–262144），并将 execution snapshot 超限/敏感字段纳入幂等输入边界验收；计数重置为 0 | 0 |
| 2026-08-22 | 规划安全/恢复一致性 | 修订后第一轮复核发现 execution snapshot 仅列举字段类别，仍可能被实现为任意对象，无法可靠阻止 prompt、工具/凭据或未受控 domain/scope 对象进入快照，也未明确 stale reclaim 如何组合当前客户端声明与首次服务端解析配置 | 修正规划正文：冻结 `executionSnapshotVersion=1` 的显式 JSON allow-list、字段类型和值域及客户端输入/服务端快照的恢复边界；授权证据继续独立存放并在 replay 时复核；计数重置为 0 | 0 |
| 2026-08-22 | 当前入口事实/兼容性 | 修订后第一轮交叉核对发现“无 key 旧路径继续兼容”的表述覆盖了原生 SSE，但当前 `RagChatService.chatEvents` 在 mode-aware 依赖缺失时直接返回错误，不会自动切换 legacy stream | 修正规划正文：按 endpoint 冻结兼容边界；原生 JSON 保留 legacy fallback，原生 SSE 保留现有 mode-aware 依赖错误语义；带 key 仍统一返回 `IDEMPOTENCY_DISABLED`，不退回 legacy；计数重置为 0 | 0 |
| 2026-08-22 | 跨 transport 行为一致性/Prompt 安全 | 第一轮复核发现仅把 OpenAI `transport/openaiModelAlias` 排除出 fingerprint 仍不够；若它们继续留在 `clientMetadata`，现有 prompt customizer 可能读取它们，造成 native/OpenAI 共享 operation 时 prompt 不同 | 修正规划正文：明确 transport/alias 诊断字段不得进入 prompt、fingerprint 或稳定业务 response metadata，协议 envelope 从独立 declaration/operation context 生成；计数重置为 0 | 0 |
| 2026-08-22 | WebUI 重试/用户体验一致性 | 第一轮复核发现“同一次 send 复用 key”的 UI 重试没有冻结尝试上限、Retry-After 等待、主动 stop 边界，以及部分 SSE 后 replay 如何避免重复答案/assistant bubble | 修正规划正文：冻结 logical send 最多两次、Retry-After 总等待不超过 60 秒、主动 stop/明确业务错误不重试；重试前清空临时 bubble/tool state，只复用一个 assistant bubble，并将这些场景纳入 Mock Playwright；计数重置为 0 | 0 |
| 2026-08-22 | 跨 transport response snapshot/真实 provider 证据 | 第 1 轮发现 `response_payload` 同时允许协议 envelope 与 transport-neutral 结果，可能让首个 transport 锁死跨 transport replay；同时现有 real smoke 会先探测 MiniMax，不能证明显式选择的 OpenAI provider 真正执行 | 修正规划正文：冻结 `response_payload` 只保存 transport-neutral 业务结果，status 统一投影原生 `ChatResponse`；real smoke 的启动、preflight 和请求必须使用同一显式 provider，禁止静默回退；计数重置为 0 | 0 |
| 2026-08-22 | 跨 transport 状态语义 | 第 1 轮复核发现 operation 的 `transport` 字段未明确是首次请求还是当前 replay，跨 transport 状态查询与 metrics 可能使用不一致语义 | 修正规划正文：冻结 operation `transport` 为首次 claim transport；当前请求 transport 只用于 adapter、HTTP envelope 和 metrics | 0 |
| 2026-08-22 | 输入错误契约/安全 | 第 2 轮发现规划要求拒绝 `clientMetadata` 中的控制字符、循环结构和 credential 字段，但错误表只有超限码，native/OpenAI 可能产生不一致错误语义 | 修正规划正文：新增并冻结 `IDEMPOTENCY_REQUEST_METADATA_INVALID`（400），补入输入边界与发布错误码清单；计数重置为 0 | 0 |
| 2026-08-22 | V47 schema/状态约束 | 第 2 轮复核发现 operation token、lease、execution/response/error snapshot 和 completed time 的可空性未与状态机对齐，直接落 DDL 可能无法表达进行中与终态 | 修正规划正文：明确进行中/终态各列的 nullability 和 PostgreSQL CHECK 关系；计数重置为 0 | 0 |
| 2026-08-22 | V47 schema/恢复语义 | 第 2 轮继续复核发现若把 `IN_PROGRESS` lease 约束为未过期，会阻断 stale reclaim；同时 `SUCCEEDED` 未明确要求 execution snapshot 非空 | 修正规划正文：允许 `IN_PROGRESS` 保存已过期 lease 并明确由 reclaim 处理；要求 `SUCCEEDED` 必须有 execution snapshot；计数重置为 0 | 0 |
| 2026-08-22 | 快照隐私/turn identity/status ACL | 第 2 轮复核发现 execution snapshot 持久化客户端 retrieval filter 值会扩大敏感数据面，`turn_id` 未明确全状态非空，且 `replayAvailable` 在不复核当前 ACL 时可能虚报可重放 | 修正规划正文：filter 值仅从 fingerprint 一致的重试请求恢复；`turn_id` 首次建立即非空且不可变；成功 status 用同一 ACL verifier 计算 `replayAvailable`，仅 includeResponse 失权时返回 403；计数重置为 0 | 0 |
| 2026-08-22 | operation renewal/CAS 可实施性 | 第 2 轮复核发现 lease renewal 每次递增 `row_version`，但 commit 若继续使用旧版本会被自身续租阻断；stale reclaim CAS 失败也可能泄漏刚取得的 session lease | 修正规划正文：operation handle 原子跟踪 renewal 返回的新版本，commit 与 renewal 串行交接；reclaim CAS 失败立即按 token 释放 session lease并重读；计数重置为 0 | 0 |
| 2026-08-22 | replay ACL/文档生命周期 | 第 2 轮对照真实检索 SQL 发现 replay verifier 只检查来源存在与 Collection 归属，没有要求 `rag_documents.enabled=true`，可能在本地禁用或 source tombstone 后继续返回旧内容 | 修正规划正文：来源文档当前禁用/tombstone/缺失统一 fail closed；内容 revision 变化不改写同一幂等 snapshot；补入验收矩阵；计数重置为 0 | 0 |
| 2026-08-22 | 需求闭环/SSE 断线恢复/错误契约 | 新一轮第 1 轮发现规划同时要求 SSE 断线后使用同一 key 恢复，又允许把 reader cancel、代理断线或发送失败终结为不可重试的 `CANCELLED`；同时终态失败在 native/OpenAI/SSE 之间的重放 envelope 未冻结 | 移除本轮不可靠的 durable cancel 语义：keyed SSE 断开只解除当前投递，operation 由协调器继续执行/续租/提交；明确本轮不新增 cancel API，并冻结 transport-neutral error snapshot 按当前 transport 同步投影的规则；计数重置为 0 | 0 |
| 2026-08-22 | WebUI 恢复身份/跨 transport 响应断言 | 第 1 轮继续复核发现页面若只在 `done` 读取 turn ID，连接在完成前断开后无法使用 status API；同时“第二次 body/header 相同”和“request trace/replay 标记按请求变化”相互矛盾，OpenAI 小节仍残留协议 envelope 被称作持久 snapshot 的表述 | 要求 hook 在读取 SSE body 前从 `X-RAG-Turn-Id` 绑定并校验 UUID，且与 `done.turnId` 一致；验收改为稳定业务字段/identity 相同、请求级 trace 可变化、replay 标记必须变化；OpenAI envelope 明确由 transport-neutral snapshot 投影；计数保持 0 | 0 |
| 2026-08-22 | Header 公共契约/多值防歧义 | 第 1 轮复核发现规范值允许全部可见 ASCII（包含逗号），同时又要求逗号拼接的多值 header 非法；单值合法逗号与代理合并后的重复 header 无法可靠区分 | 从合法 key 字符集中明确排除逗号，并同步错误表，确保重复 header 被 servlet/proxy 合并后仍 fail closed；计数保持 0 | 0 |
| 2026-08-22 | V47 物理约束/lease fencing/孤儿回收 | 第 2 轮发现核心 identity/version/status 列未全部显式冻结 `NOT NULL/PRIMARY KEY`；operation renew/terminal CAS 未要求当前 lease 仍有效，旧 worker 可能在 expiry 后续活或提交；长期无人重试的 stale `IN_PROGRESS` 会永久占 key；授权/错误 JSON 也缺少完整版本与状态生命周期 | 冻结所有核心列 nullability、主键和 history `turn_id` 无外键边界；renew/成功/失败 CAS 必须包含 status、token、最新 version 和未过期 lease；增加 retention 后 stale orphan 的条件删除；授权快照从 INSERT 起即为 versioned v1 基础结构，成功事务补齐来源；错误快照加入显式版本；计数重置为 0 | 0 |
| 2026-08-22 | 原子事务资源边界 | 第 2 轮对照现有 `ChatSessionCoordinator` 与 `ChatMemoryRepositoryConfig` 后确认，若 operation repository 使用独立 transaction manager，operation/history/Memory/session lease 的“同事务”承诺会失效 | 明确四类写入必须共享同一个 PostgreSQL DataSource 与 PlatformTransactionManager，并要求故障注入分别证明任一步失败时其余写入与 lease consume 全部回滚；计数保持 0 | 0 |
| 2026-08-22 | ACL 最小边界/来源快照/恢复清理 | 第 2 轮继续复核发现 selected replay 若比较完整历史 allow-list 会被无关权限变化误阻断；首次来源无法权威映射时缺少稳定失败；attempt 耗尽和 stale orphan 清理也未对所有 mode 统一排除有效 session lease | 将 selected 授权快照收窄到本 operation 的 selected/source IDs；新增 `IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID` 与来源规范化规则；attempt 耗尽直接做过期 CAS 失败终态，reclaim/cleanup 前统一检查 principal/session lease；同步验收矩阵和错误码清单；计数重置为 0 | 0 |
| 2026-08-22 | V47 类型/scope ACL/STATELESS reclaim | 新一轮第 2 轮发现 execution snapshot 的 PostgreSQL 类型未明确冻结；未归属文档许可没有按 resolved scope 逐项定义；stale reclaim 的 session lease 前置描述也与 STATELESS 无 lease 规则冲突 | 明确 execution snapshot 为 JSONB；冻结仅 unrestricted CALLER_VISIBLE 允许未归属文档；SERVER reclaim 先取得 session lease，STATELESS 直接走 operation CAS；计数重置为 0 | 0 |
| 2026-08-22 | crash recovery/memory mode fencing | 第 2 轮继续复核发现 stale operation 的 execution snapshot 可能仍为 NULL，此时无法安全判断应获取 SERVER session lease 还是按 STATELESS 接管 | 冻结 NULL snapshot 的有界重建顺序：先从 fingerprint 一致声明重建受控候选快照并确定 memory mode，再按 mode 获取适用 lease，并在 reclaim CAS 中一并持久化；计数重置为 0 | 0 |
| 2026-08-22 | 最终验收/Git/worktree 清理 | 第 3 轮发现实施顺序虽写了合回 main，但没有冻结跟进最新 origin/main 后的完整复验顺序，也遗漏用户要求的合并后安全移除隔离特性 worktree | 增加 merge 后固定验收序列、修复后的门槛重跑规则、feature/main push 状态确认，以及仅在提交已进入远端 main、worktree 干净且进程已停止后移除 worktree；计数重置为 0 | 0 |

### 2026-08-22 19:10 CST 最终规划审查记录

- **结论：`3/3`，允许进入实施前交付。**
- 第 1 轮：需求闭环、自包含性、公共契约与默认决策；无修改。
- 第 2 轮：代码、数据库、API、ACL、安全、并发、恢复与跨 transport 兼容性；无修改。
- 第 3 轮：实施顺序、一次性验收矩阵、真实 LLM 证据、门禁脚本、回滚、Git/worktree
  交付与清理；无修改。
- 交叉验证结果：规划正文与当前 Chat controller/service、OpenAI mapper/handler、
  session lease、V32/V44/V46、现有幂等错误码、WebUI SSE、测试门禁和
  `delivery-workflow-zh-CN.md` 一致；`git diff --check` 与
  `./scripts/verify-no-pessimistic-locks.sh` 通过。
- 本记录之后若修改规划正文，审查计数必须重置为 `0`，重新完成连续三轮无修改审查。

## 3. 已核对的实施入口

- `spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagChatController.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/controller/OpenAiCompatibilityController.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/openai/OpenAiChatRequestMapper.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/ChatExecutionService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/ChatSessionCoordinator.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/repository/RagChatHistoryRepository.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagChatHistory.java`
- `spring-ai-rag-core/src/main/resources/db/migration/V32__add_chat_history_owner_sources_lease.sql`
- `spring-ai-rag-core/src/main/resources/db/migration/V46__add_chat_memory_summary.sql`
- `spring-ai-rag-webui/src/hooks/useSSE.ts`
- `spring-ai-rag-webui/src/pages/Chat.tsx`
- `spring-ai-rag-webui/e2e/chat.spec.ts`
- `spring-ai-rag-webui/e2e/chat-real.spec.ts`

## 4. 当前恢复入口

规划审查完成后：

1. 确认连续 `3/3` 无修改；
2. 运行 `./scripts/verify-project-docs.sh` 和 `git diff --check`；
3. 先更新本文件记录门禁结果；
4. 创建本地 commit；
5. `git fetch origin --prune`，merge 最新 `origin/main` 到本规划分支；
6. merge 后重新运行文档门禁，更新本文件；
7. push 并确认 `git status` 干净；
8. 只汇报规划交付，暂停，不创建实施 worktree。

若用户批准实施，必须从最新 `main` 新建实施分支；本规划分支不能直接承载生产代码。

### 2026-08-22 19:10 CST 文档门禁

- `./scripts/verify-project-docs.sh`：通过，10 项检查全部通过；Markdown 链接检查
  `files=139`、`relative_links=981`。
- `git diff --check`：通过。
- `./scripts/verify-no-pessimistic-locks.sh`：通过。
- 下一步：提交当前全部规划文档修改；fetch 并 merge 最新 `origin/main`，合并后重跑
  文档门禁，再 push 规划分支并确认工作区干净。

### 2026-08-22 19:10 CST 合并基线

- `git fetch origin --prune`：完成。
- `origin/main`：`e48fb192`；当前规划分支已包含该提交。
- `git merge --no-edit origin/main`：`Already up to date`，无冲突、无新增工作区修改。
- 合并后仍保持规划阶段；未创建实施 worktree，未修改生产代码。

## 5. 已作废的早期门禁记录

### 2026-08-22 16:39 CST

- **状态：已作废。** 此记录之后规划发生了多次实质修改，不能作为当前 `3/3` 或门禁证据。
- 规划连续无修改复核：`3/3`，三轮范围依次为需求闭环/自包含性、代码与并发可行性、
  实施顺序与交付恢复。
- `./scripts/verify-project-docs.sh`：通过，10 项检查全部通过；Markdown 链接检查
  `files=139`、`relative_links=981`。
- `git diff --check`：通过。
- 归档索引：已在双语 `docs/drafts/archive/README*` 中列出
  `2026-08-22_NEXT_HIGH_VALUE_FEATURES_*`。
- 下一步：提交当前全部规划文档修改，merge 最新 `origin/main`，重新运行文档门禁后
  push；本轮仍不实施生产代码。
