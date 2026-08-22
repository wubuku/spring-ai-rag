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

## 5. 规划交付前门禁记录

### 2026-08-22 16:39 CST

- 规划连续无修改复核：`3/3`，三轮范围依次为需求闭环/自包含性、代码与并发可行性、
  实施顺序与交付恢复。
- `./scripts/verify-project-docs.sh`：通过，10 项检查全部通过；Markdown 链接检查
  `files=139`、`relative_links=981`。
- `git diff --check`：通过。
- 归档索引：已在双语 `docs/drafts/archive/README*` 中列出
  `2026-08-22_NEXT_HIGH_VALUE_FEATURES_*`。
- 下一步：提交当前全部规划文档修改，merge 最新 `origin/main`，重新运行文档门禁后
  push；本轮仍不实施生产代码。
