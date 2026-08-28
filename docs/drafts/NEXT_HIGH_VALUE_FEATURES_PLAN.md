# 告警通知 Durable Outbox 与供应商投递回执实施规划

> 状态：规划已通过连续三轮无修改审查，待实施
> 日期：2026-08-27
> 当前基线：`main@a078babf`
> 配套进度：[NEXT_HIGH_VALUE_FEATURES_PROGRESS.md](NEXT_HIGH_VALUE_FEATURES_PROGRESS.md)
> 交付规则：[规划、实施与验收工作流](../delivery-workflow-zh-CN.md)

## 1. 问题、价值与本轮决策

当前系统已经把普通告警和受管 API principal 到期条件保存在 PostgreSQL。V57 进一步使用
active dedupe、`state_version`、`notified_version` 和 CAS 收敛到期告警阶段，并在事务提交后
通过 Spring Event 准实时对账。数据库中的 active alert 因而是可靠的运维事实。

剩余缺口位于外部通知：

- `AlertServiceImpl` 保存普通告警后立即调用异步 Email/DingTalk service；
- 到期告警在事务内先推进 `notified_version`，事务提交后才调用异步 provider；
- 进程可能在 claim 后、真正调用 provider 前退出，使该阶段通知永久丢失；
- provider 返回失败后只在当前线程内做三次短重试，服务重启后没有恢复入口；
- 多实例没有共享 delivery lease、attempt、next retry 或终态回执；
- operator 无法区分“未配置通知”“待发送”“重试中”“已送达”“永久失败”；
- 当前 DingTalk/Email 调用没有稳定投递 ID，供应商成功后本地崩溃时也无法解释可能的重复。

这不是告警事实本身的缺失，而是数据库事实到外部通知渠道之间的可靠投递缺口。对受管
credential 到期、SLO breach 和人工告警而言，漏发会把已经检测到的生产风险重新变成静默故障。

本轮选择“告警通知 durable outbox 与供应商投递回执”，而不是以下候选：

| 候选 | 本轮判断 |
|---|---|
| token/cost hard limit | 价值高，但需要预授权、预留、结算、provider usage 缺失和崩溃恢复，属于更大的独立批次 |
| 大 Collection 异步 purge | 当前同步 purge 已严格 fail closed；异步分片删除需要长期任务和跨表恢复协议 |
| OAuth/OIDC federation | 依赖部署方 IdP、tenant 映射与 token 校验策略，不是当前最近的可靠性缺口 |
| `EACH_COLLECTION` 召回覆盖 | 仍缺少 goldenset 或线上证据证明全局 top-k 存在系统性覆盖问题 |
| durable notification outbox | 缺口明确，直接补齐 V57 已暴露的进程退出窗口，并复用项目成熟的 PostgreSQL + Spring Event + Scheduled fallback 模式 |

本轮交付目标：

1. 告警记录与 provider delivery 在同一数据库事务中原子持久化；
2. 事务提交后 Spring Event 准实时唤醒有界 worker，默认一分钟 Scheduled 只做兜底；
3. 多实例以 PostgreSQL lease、条件更新/CAS 和唯一约束竞争，不引入消息代理或悲观锁；
4. provider 调用始终位于数据库事务外，失败按有界退避跨重启恢复；
5. operator 可查询低敏 delivery receipt，并对终态失败执行安全、幂等的人工重试；
6. Alerts WebUI 提供投递状态、过滤和重试入口；
7. 明确采用 at-least-once：不能在无供应商幂等协议时虚假承诺 exactly-once；
8. 不保存 webhook secret、SMTP password、raw credential、收件人列表、业务 payload、
   provider 响应正文或异常堆栈。

## 2. 已核对的代码与数据事实

### 2.1 告警持久化与到期条件

- [AlertServiceImpl.java](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/AlertServiceImpl.java)
  在 `@Transactional fireAlert` 中保存 `RagAlert`，然后直接调用所有 `NotificationService`；
- [ApiPrincipalExpiryAlertService.java](../../spring-ai-rag-core/src/main/java/com/springairag/core/apikeyalert/ApiPrincipalExpiryAlertService.java)
  在短事务中创建/更新 managed alert、推进 `notified_version`，事务返回后调用 provider；
- V57 的 `state_version` 从 1 开始表示 managed phase generation，普通历史告警保持
  `state_version=0`；
- V57 partial unique index 只约束同一 `dedupe_key` 的 active managed alert，不提供通知
  delivery 去重；
- managed alert 在 `WARNING -> CRITICAL -> EXPIRED` 时复用同一行；延期、吊销或人工
  resolve 会使 active 条件消失。

### 2.2 Provider 当前语义

- `NotificationService.sendAlert` 返回 `CompletableFuture<Boolean>`，Email 和 DingTalk 都使用
  `@Async("taskExecutor")`；
- Email 在方法内部最多重试三次，DingTalk 对每个候选配置最多重试三次；
- DingTalk 当前把多个配置当作有序 failover：第一个成功后停止，而不是 fan-out 到全部渠道；
- `false` 同时表示 disabled、alert type 不匹配、配置缺失或所有尝试失败，不能形成可靠错误分类；
- `taskExecutor` 使用 `CallerRunsPolicy`。通知调用若继续直接依赖它，队列满时可能回到请求/
  scheduler 线程执行网络 I/O；
- 现有配置会掩码 secret 的 `toString`，但 delivery 状态没有持久化位置。

### 2.3 项目已有可靠异步模式

- embedding job 已采用“durable PostgreSQL job + after-commit Spring Event wake-up +
  低频 Scheduled fallback + lease worker”；
- principal expiry alert 已采用“after-commit event + 一小时公平扫描 + CAS”；
- 项目禁止 `SELECT ... FOR UPDATE`、`SKIP LOCKED`、advisory lock 和显式悲观锁；
- 现有 Sync Run receipt 已提供版本化 opaque cursor，可复用其 keyset pagination 设计；
- Alerts 全路由已经由 `AlertManagementAuthorization` 收紧为 operator 管理面。

### 2.4 WebUI 与文档

- Alerts 页现有 active alerts、SLO configs 和 silence schedules 三个 tab；
- active alerts 每 30 秒刷新，已有 Vitest 和 Mock Playwright；
- 当前没有通知 delivery API、类型或 UI；
- `docs/TODO*` 已明确记录 durable notification outbox/provider receipt 是 V57 后续缺口。

## 3. 冻结的语义与兼容边界

### 3.1 一致性承诺

权威顺序：

1. `rag_alerts` 是告警条件/事实；
2. `rag_alert_notification_delivery` 是“某个告警版本应通过某个 provider 投递”的 durable
   事实；
3. provider 的外部邮箱或 webhook 系统是最终接收方。

系统承诺：

- 告警与 delivery 要么同事务提交，要么同事务回滚；
- 已提交且未 supersede 的 delivery 最终进入 `DELIVERED` 或 `FAILED`；
- 服务重启、事件丢失、executor 拒绝和多实例竞争不会永久遗忘 eligible delivery；
- 同一 `alert + notificationVersion + provider` 最多存在一条 delivery row；
- 单个 lease owner 只能以匹配 token 完成本次 attempt。

系统不承诺：

- provider 调用 exactly-once；
- provider 成功后本地提交前崩溃不会重复发送；
- provider 自己对邮件、机器人消息或 webhook 的最终展示 exactly-once。

每条通知携带稳定 `deliveryId`，便于 operator 和接收方排查重复。重复优于静默漏发。

### 3.2 Notification version

- 普通不可变告警固定使用 `notificationVersion=1`；
- managed alert 使用当前 `state_version`；
- V57 的 `notified_version` 从“已在内存中 claim”收敛为“该 managed state 已完成 durable
  route evaluation/enqueue”的 watermark；
- 未配置任何匹配 provider 时仍推进 `notified_version`，表示该状态已按当时配置评估，
  后续启用渠道不会追发历史状态；
- 同版本重复事件只命中唯一约束，不重复插入 delivery。

### 3.3 状态机

| 状态 | 含义 | 可迁移到 |
|---|---|---|
| `PENDING` | 已原子入队，等待首次 claim | `IN_PROGRESS`、`SUPERSEDED` |
| `IN_PROGRESS` | 某实例持有有效 lease，正在事务外调用 provider | `DELIVERED`、`RETRY_WAIT`、`FAILED`；lease 过期后可被重新 claim |
| `RETRY_WAIT` | transient failure，等待 `next_attempt_at` | `IN_PROGRESS`、`SUPERSEDED` |
| `DELIVERED` | provider 调用返回成功 | 终态 |
| `FAILED` | permanent failure 或自动 attempt budget 耗尽 | 人工重试回到 `PENDING` |
| `SUPERSEDED` | managed 状态已升级或解除，旧的未开始投递不再有价值 | 终态 |

managed alert 写入更高版本或被解决时，只把旧 `PENDING/RETRY_WAIT` 标记为
`SUPERSEDED`。已经 `IN_PROGRESS` 的调用不能可靠撤回；它可能完成，因此文档明确允许极少量
阶段交叉。worker claim 后、provider 调用前再次核对 managed alert 当前状态；若 alert 已解决、
被删除或 `state_version` 已变化，用 lease CAS 把本 row 标记为 `SUPERSEDED`，不调用 provider。

静默规则继续只在 enqueue 时判断。delivery 已持久化后再新增 silence 不取消已经发生的告警事实；
否则长时间 retry 会因内存 silence 或规则变化永久失去确定性。

## 4. V58 数据模型

新增 `V58__durable_alert_notification_delivery.sql`。

### 4.1 `rag_alert_notification_delivery`

字段：

- `id UUID PRIMARY KEY`，由应用在序列化 payload 前生成；
- `alert_id BIGINT NOT NULL`，保存不可变的历史告警引用，但不建立级联外键。delivery 的
  retention 独立于 alert retention，不能因历史告警清理提前删除待处理的失败回执；
- `notification_version INTEGER NOT NULL CHECK (notification_version >= 1)`；
- `managed_condition BOOLEAN NOT NULL`，区分普通一次性告警和会升级/解除的受管条件；
- `provider VARCHAR(32) NOT NULL CHECK (provider IN ('EMAIL','DINGTALK'))`；
- `status VARCHAR(24) NOT NULL`，限定为第 3.3 节六种状态；
- `payload JSONB NOT NULL`，只保存发送所需的 alert type/name/severity/message/metrics 与
  stable `deliveryId`，不保存渠道配置；
- `attempt_count INTEGER NOT NULL DEFAULT 0`，累计值，人工 retry 不清零；
- `attempt_budget INTEGER NOT NULL`，初始为配置的单周期 `max-attempts`；人工 retry 追加一个
  当前配置大小的新预算周期；
- `manual_retry_count INTEGER NOT NULL DEFAULT 0`；
- `next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()`；
- `lease_token UUID`、`lease_until TIMESTAMPTZ`；
- `last_error_code VARCHAR(64)`、`last_http_status INTEGER`；
- `last_attempt_at`、`delivered_at`、`created_at`、`updated_at`。

约束与索引：

- `UNIQUE(alert_id, notification_version, provider)`；
- lease pair 必须同时为空或同时存在；
- 只有 `IN_PROGRESS` 可以有 lease；非 `IN_PROGRESS` lease 必须为空；
- `attempt_count/manual_retry_count >= 0`，`attempt_budget >= attempt_count`；
- `DELIVERED` 必须有 `delivered_at`，其他状态不得伪造；
- eligible scan partial index：
  `(next_attempt_at, id) WHERE status IN ('PENDING','RETRY_WAIT')`；
- expired lease partial index：
  `(lease_until, id) WHERE status='IN_PROGRESS'`；
- operator query index：`(created_at DESC, id DESC)`，以及有限的 provider/status 组合索引。

`payload` 示例：

```json
{
  "deliveryId": "8abf1f68-7ed4-4fca-b33e-2c9cb22c8d87",
  "alertType": "API_PRINCIPAL_EXPIRY",
  "alertName": "Managed API principal expiry",
  "severity": "CRITICAL",
  "message": "API principal ...",
  "metrics": {
    "principalId": "rag_k_example",
    "phase": "CRITICAL",
    "expiresAt": "2026-09-01T00:00:00+08:00"
  }
}
```

payload 复用已经允许进入 `rag_alerts` 的字段，不复制 provider URL、SMTP host、用户名、
password、secret、收件人或完整异常。入队前必须经过通知专用 sanitizer：

- message 和 metadata string 使用敏感模式掩码；
- key 命中 `password/secret/token/apiKey/authorization/credential/webhook/smtp` 等模式时
  值统一替换为固定标记；
- 最大深度 8、map/list item 100、单字符串 2048 字符；
- 序列化超过 `max-payload-bytes` 时先移除 metrics，再截断 message，最后保留最小
  alert type/name/severity 和 `payloadTruncated=true`；
- sanitizer 不修改 `rag_alerts` 历史事实，只避免 outbox 和 provider 再复制敏感内容。

不建立 alert 外键是有意的 ledger 边界：原子 enqueue 保证创建时引用真实 alert，后续 alert
retention 不影响 delivery；operator receipt 仍返回原始 `alertId`，但不能据此假设 alert 行永久存在。
`managed_condition` 不通过 API 暴露；它只让 worker 和人工 retry 在调用 provider 前重新核对
`rag_alerts.status='ACTIVE' AND state_version=notification_version`。普通告警继续按不可变 payload
发送，不因后来被人工 resolve 而撤回已经发生的通知。

### 4.2 历史兼容

- 不为 V1–V57 历史告警回填 delivery，避免上线时突然发送旧通知；
- 不修改旧 alert 的 `notified_version`；
- V58 binary 启动后新产生的告警才进入 outbox；
- rolling deployment 期间，V57 实例仍可能按旧路径直接通知，V58 实例会持久化 delivery，
  因此启用 durable worker 前要求先完成 binary rollout；
- 增加 `rag.notifications.delivery.enabled`，默认 `false` 作为混合版本保护。全部实例升级并
  通过迁移后再切换为 `true`；关闭时保持旧直接通知兼容路径；
- 首次正式启用后不允许回退到只运行 V57 binary 承担告警通知，除非 operator 接受 pending
  delivery 暂停消费。

## 5. 事务、事件与多实例 worker

### 5.1 Provider route evaluation

把当前 `NotificationService` 重构为同步的 provider adapter：

- `provider()` 返回固定 `EMAIL` 或 `DINGTALK`；
- `isRoutedFor(alertType)` 只读取 global/channel enabled 和 alert type allow-list，决定是否
  原子创建 delivery；
- `isCurrentlyAvailable()` 检查当前进程是否具备发送所需的本地 adapter/config，例如
  `JavaMailSender` 是否存在；它不执行网络调用；
- `deliver(NotificationPayload)` 执行一次 provider attempt 并返回结构化结果；
- provider adapter 不再使用 `@Async`，不在内部 sleep/retry；
- DingTalk 保持现有有序 failover 语义，不隐式改成多渠道 fan-out；
- 启动时验证 provider ID 唯一、DingTalk 名称非空且不重复、基础配置自洽。

当 durable delivery disabled 时，`AlertServiceImpl` 和 managed expiry service 继续调用兼容
direct dispatcher，保持现有部署行为。direct dispatcher 继续在公共 async executor 上执行现有
三次短重试；provider adapter 本身始终只执行一次 attempt。enabled 时只写 outbox，不双发。

route 已声明但 adapter 当前不可用时仍创建 delivery，并在 worker 中进入
`FAILED/PERMANENT_CONFIGURATION`，使 operator 能发现配置问题。人工 retry 只有在
`isCurrentlyAvailable()` 为 true 时才重新入队。

### 5.2 原子 enqueue

新增 `AlertNotificationOutboxService`：

1. 枚举当前匹配的 provider；
2. 对每个 provider 插入唯一 delivery；
3. payload 只取 alert 已持久化字段并经过有界 sanitizer；即使 metadata 过大也降级为最小
   payload，不因可选通知详情导致权威告警丢失；
4. managed 状态在同一事务 supersede 旧 `PENDING/RETRY_WAIT`；
5. managed 状态在所有 route evaluation 成功后推进 `notified_version`；
6. 事务提交后发布一次可合并的 `AlertNotificationsAvailableEvent`。

普通 `AlertServiceImpl.fireAlert` 必须先 `saveAndFlush` 取得真实 alert ID，再在同一事务 enqueue。
任一 outbox SQL/序列化失败时，告警事务回滚，不能留下“已记录但 durable delivery 不存在”的
半事实。provider 未配置不是错误，只产生零 delivery。

本轮同时把 `FireAlertRequest` 的输入约束与 V7 schema 对齐，避免 outbox 实施后仍由数据库异常
产生 500：

- `alertType` 必填且最多 50；
- `alertName` 必填且最多 100；
- `message` 必填且最多 1024；
- `severity` 最多 20，继续保持现有字符串兼容语义；
- 违反约束统一在 controller 前返回 400。

### 5.3 Event publisher

`AlertNotificationWakeupPublisher` 复用 embedding job 模式：

- 事务中只注册一个 `TransactionSynchronization`；
- `afterCommit` 发布可合并事件；
- 无事务时立即发布；
- 发布失败只记录固定低敏 warning，Scheduled 最终恢复；
- 事件不携带 payload、credential 或 provider 配置。

### 5.4 Worker

`AlertNotificationDeliveryWorker` 使用独立、固定大小且带有显式有界队列的 daemon executor，
不复用 `taskExecutor`，避免邮件/webhook 延迟占用 Chat、summary 或其他通用 async 任务。
提交拒绝只丢失本地 wake-up，不丢失数据库 delivery。

worker：

1. Event 或 Scheduled 只调用 `wakeUp()`；
2. 合并重复 wake-up，并按可用并发槽位查询少量 candidate ID；
3. 对每个 ID 使用单条条件 `UPDATE ... RETURNING` claim；
4. claim 短事务提交后才调用 provider；
5. 用 `id + lease_token + status=IN_PROGRESS` CAS 写回；
6. 每次完成后继续 wake，直到没有 eligible row 或并发槽位已满；
7. 关闭时最多等待 5 秒，未完成 lease 由其他实例或 fallback 回收。

禁止在数据库事务中执行 DNS、SMTP、HTTP 或 backoff sleep。

### 5.5 Claim 与恢复

claim 条件：

- `PENDING/RETRY_WAIT AND next_attempt_at <= database_now`；或
- `IN_PROGRESS AND lease_until <= database_now AND attempt_count < attempt_budget`。

claim 成功时：

- `status=IN_PROGRESS`；
- `attempt_count=attempt_count+1`；
- 写入随机 `lease_token` 和 `lease_until`；
- `last_attempt_at=database_now`。

多实例先读到同一 candidate 不构成问题，只有一个条件 UPDATE 成功。过期 lease 重试可能在
供应商已成功但本地未确认时重复发送，这是 at-least-once 的必要代价。

fallback 每轮先以有界 CAS 把
`IN_PROGRESS AND lease_until <= database_now AND attempt_count >= attempt_budget`
转为 `FAILED/ATTEMPT_BUDGET_EXHAUSTED` 并清空 lease。否则这类 row 不再满足 claim 条件，会永久
卡住。cleanup 和 candidate scan 都不得把有效 `IN_PROGRESS` 当成可删除终态。

### 5.6 失败分类与退避

固定结果：

- `SUCCESS`；
- `TRANSIENT_NETWORK`；
- `TRANSIENT_RATE_LIMIT`；
- `TRANSIENT_PROVIDER_5XX`；
- `PERMANENT_CONFIGURATION`；
- `PERMANENT_PROVIDER_REJECTED`；
- `STALE_MANAGED_STATE`。

DingTalk：

- connect/read timeout、I/O、HTTP `429`、HTTP `5xx` 为 transient；
- 其他 `4xx` 和 HTTP 200 但业务 `errcode != 0` 为 permanent；
- 若存在合法 `Retry-After`，在配置上限内使用。

Email：

- SMTP transport/temporary failure 为 transient；
- sender/recipient/JavaMailSender 缺失等配置问题为 permanent；
- durable Email route 要求 Spring Boot 默认的 `JavaMailSenderImpl`，以便把
  `mail.smtp.connectiontimeout`、`mail.smtp.timeout` 和 `mail.smtp.writetimeout` 约束到
  `provider-attempt-timeout`；无法验证超时的自定义 sender 在 durable mode 启动时 fail fast，
  direct compatibility mode 不受影响；
- 不保存 recipient、SMTP response 或异常正文。

默认自动 attempt budget 为 8。退避从 30 秒开始指数增长，最多 1 小时并加入不超过 20%
jitter；所有时间以数据库时间生成并保存。attempt 耗尽进入 `FAILED`。

## 6. 配置

前缀：`rag.notifications.delivery`。

| 配置 | 默认 | 约束 |
|---|---:|---|
| `enabled` | `false` | rolling rollout 保护；启用后使用 durable 路径 |
| `fallback-scan-interval` | `PT1M` | 10 秒至 10 分钟；不是正常主触发 |
| `worker-concurrency` | `4` | 1–32 |
| `claim-batch-size` | `100` | 1–1000，且不小于 concurrency |
| `provider-attempt-timeout` | `PT30S` | 5–60 秒；内置 HTTP/SMTP adapter 的 connect/read/write 上限 |
| `lease-duration` | `PT2M` | 30 秒至 15 分钟，必须至少为 provider attempt timeout 的两倍 |
| `max-attempts` | `8` | 1–20，每个自动/人工 retry 周期追加的 attempt budget |
| `initial-backoff` | `PT30S` | 1 秒至 10 分钟 |
| `max-backoff` | `PT1H` | 不小于 initial，最多 24 小时 |
| `delivered-retention` | `P30D` | 1–365 天 |
| `failed-retention` | `P90D` | 7–730 天 |
| `cleanup-interval` | `PT1H` | 10 分钟至 24 小时 |
| `cleanup-batch-size` | `1000` | 100–10000 |
| `max-payload-bytes` | `65536` | 4096–1048576；超限时按第 4.1 节降级 |

配置非法时启动失败。Scheduled 默认一分钟一次；正常 delivery 应由 after-commit Event 在秒级
内唤醒，不允许通过秒级全表轮询替代事件路径。

## 7. Operator API

新增 controller，复用 `AlertManagementAuthorization`。

### 7.1 查询

```http
GET /api/v1/rag/alerts/notification-deliveries
  ?status=FAILED
  &provider=DINGTALK
  &alertId=42
  &limit=50
  &cursor=opaque
```

- `limit` 默认 50，范围 1–100；
- 使用 `(created_at DESC, id DESC)` keyset cursor；
- cursor 绑定 status/provider/alertId 过滤条件，非法或篡改返回 400；
- 响应包含：
  - `notificationsEnabled`；
  - `durableDeliveryEnabled`；
  - `configuredProviders`，只返回固定 `EMAIL/DINGTALK` 枚举，不返回 route 名称或地址；
  - `items`、`limit`、`hasMore`、`nextCursor`；
- receipt 字段：
  `id, alertId, notificationVersion, provider, status, attemptCount,
  attemptBudget, manualRetryCount, nextAttemptAt, lastErrorCode, lastHttpStatus,
  lastAttemptAt, deliveredAt, createdAt, updatedAt`；
- 不返回 payload、lease token、provider URL、recipient、secret 或错误正文。

### 7.2 人工重试

```http
POST /api/v1/rag/alerts/notification-deliveries/{deliveryId}/retry
```

语义：

- `deliveryId` 为 UUID；
- `FAILED` 原子改为 `PENDING`，清空 lease/error，
  `attempt_budget=attempt_count+current max-attempts`，
  `manual_retry_count+1`，`next_attempt_at=database_now`；
- provider 当前 route 已移除或 adapter 不可用时返回 409，不制造必然失败的 pending row；
- managed delivery 必须重新读取 source alert；alert 缺失、非 ACTIVE 或
  `state_version != notificationVersion` 时先收敛为 `SUPERSEDED` 并返回 409；
- ordinary delivery 若 source alert 已按独立 retention 删除，仍允许使用已脱敏 immutable
  payload 重试；
- `PENDING/RETRY_WAIT/IN_PROGRESS` 重放返回当前 receipt，不重复建 row；
- `DELIVERED/SUPERSEDED` 返回 409；
- 成功事务提交后发布 wake-up Event；
- 写 operator audit，只记录 delivery ID、provider、旧状态与 retry count。

## 8. WebUI

Alerts 页增加第四个 `notification-deliveries` tab：

- 根据查询 envelope 区分全局 notifications disabled、direct compatibility mode、
  durable enabled 但没有 configured provider，以及正常 durable mode；
- 默认显示最近 50 条 receipt；
- 提供 status 和 provider 下拉过滤；
- 表格展示 alert ID、provider、状态、attempt、下一次尝试、最后错误码和更新时间；
- `FAILED` 行提供带 retry 图标的按钮，pending 时禁用并显示可访问状态；
- 30 秒自动刷新，retry 成功后失效查询；
- 空态、loading、失败态和无权限响应均有明确 DOM；
- 不显示 payload、provider endpoint、recipient 或任何 secret；
- URL query 保存 tab/status/provider，浏览器前后退可恢复；
- 使用现有图标库和 8px 以内圆角，不新增说明性营销内容。

前端验收只使用 DOM/ARIA、URL、network request/response 与 JSON 断言，不使用截图。

## 9. 实施切片

### Slice A：schema、配置和 DTO

- V58 migration、配置类与启动校验；
- delivery status/provider/result enums；
- query/retry DTO、cursor codec；
- migration/latest-version 测试同步到 V58。

### Slice B：provider adapter 与兼容 direct path

- 把 Email/DingTalk 改为单次同步 attempt；
- 提供结构化 success/transient/permanent result；
- 保持 DingTalk 有序 failover；
- durable disabled 时由 direct dispatcher 保持现有异步 best-effort 行为，避免 rolling
  deployment 突然改变。

### Slice C：原子 outbox 与 managed alert 集成

- ordinary `fireAlert` 使用 `saveAndFlush + enqueue`；
- managed create/transition 使用同事务 enqueue、supersede 和 watermark；
- resolution supersede 未开始 delivery；
- transaction-aware event publisher。

### Slice D：lease worker、retry 和 retention

- 独立有界 worker；
- candidate、claim、finalize、expired lease recovery；
- backoff、Retry-After、attempt exhaustion；
- bounded retention cleanup 和低基数 metrics。

### Slice E：operator API 与 WebUI

- keyset list、manual retry、authorization、audit；
- WebUI tab、filters、retry、i18n、Vitest 和 Mock Playwright。

### Slice F：文档、门禁与真实验收

- 双语 architecture/project-context/configuration/rest-api/testing/developer-reference/
  release-checklist/TODO；
- capability/版本/迁移索引和项目门禁；
- 新增 `scripts/verify-alert-notification-delivery.sh`，并扩展 managed-principal 真实 runner
  的 durable-notification 模式；
- 真实 LLM/Embedding 回归。

## 10. 一次性验收矩阵

验收测试在 review 前一次性规划和补齐，不以“review 发现一个点再补一个测试”为工作方式。

### 10.1 后端 focused 与 PostgreSQL

V1–V58 空库迁移后覆盖：

1. 表约束、unique key、eligible/lease/query index；
2. UUID 在 payload 前生成，payload 与 row 使用同一稳定 delivery ID；
3. alert 与 delivery 同事务提交；outbox SQL 失败时 alert 不残留；
4. alert 历史行删除不级联删除 delivery；普通失败回执仍可重试，managed 回执在 source alert
   缺失时收敛为 SUPERSEDED；
5. provider 未配置时零 delivery，managed watermark 仍稳定；
6. 同一 alert/version/provider 并发 enqueue 只产生一行；
7. 8 路多实例 claim 只允许一个有效 lease owner；
8. transient `503 -> 429 Retry-After -> success` 跨 attempt 收敛；
9. permanent `4xx/configuration` 直接 `FAILED`，DingTalk/JavaMail 网络超时不超过冻结上限；
10. 自动 attempt budget 耗尽、累计 attempt 保留、人工追加预算和重复 retry 幂等；
11. expired lease 被另一实例恢复；
12. 已消耗最后 attempt 的 expired lease 由 recovery CAS 进入 FAILED，不永久卡住；
13. managed `WARNING -> CRITICAL` supersede 未开始旧 delivery；
14. managed 延期/吊销/人工 resolve supersede 未开始 delivery；
15. stale managed claim 和 stale manual retry 在 provider 调用前被拒绝；
16. retention 每轮只删配置 batch，保留 pending/in-progress；
17. query cursor 过滤绑定、分页稳定、非法 cursor 400；
18. NORMAL principal 403，root/ADMIN/允许的本地 auth-disabled operator 可查询和 retry；
19. sanitizer 对敏感 key、嵌入式 credential、过深/过大 metadata 产生有界低敏 payload；
20. 数据库不包含 webhook URL、SMTP password、recipient、credential、provider body 或
    exception stack。

### 10.2 Maven 与服务启动

```bash
mvn clean compile test-compile
mvn test
```

`postgresql` profile 在隔离端口启动，Flyway 到 V58，liveness/readiness 为 `UP`。

### 10.3 前端

```bash
cd spring-ai-rag-webui
npx tsc -b --pretty false
npm run test:run
npm run check:alignment
npm run build
npx playwright test e2e/alerts.spec.ts
```

Mock Playwright 一次覆盖：

- receipt tab URL 恢复；
- disabled/direct/no-provider/durable 四种运行模式的可访问状态；
- status/provider query 参数；
- receipt JSON 与 DOM/ARIA 展示；
- FAILED retry 的 POST、按钮 pending/disabled 和刷新；
- payload/secret 字段不进入 DOM；
- 403 与空态。

### 10.4 真实通知 transport

在隔离 PostgreSQL、两实例后端和本地 HTTP provider stub 上执行两个互不依赖的场景：

共同基线：

1. durable delivery enabled，fallback 固定为 1 分钟；
2. 通过真实 HTTP fire endpoint 产生告警；
3. 首次 attempt 必须在 fallback 前由 Spring Event 启动；
4. API 与只读 PostgreSQL 共同证明 attempt、lease、状态和时间；
5. WebUI 通过非 Mock network/JSON/DOM 看到 receipt，不使用截图。

场景 A：transient retry

1. stub 第一次返回 503，receipt 进入 `RETRY_WAIT`；
2. 第二次返回 200 且 DingTalk 业务 `errcode=0`，receipt 进入 `DELIVERED`；
3. 两次请求使用相同 `deliveryId`，没有 secret、credential 或 provider 配置。

场景 B：进程崩溃与 lease 恢复

1. stub 阻塞第一个请求，等待 receipt 进入 `IN_PROGRESS`；
2. 终止持有 lease 的实例，使其不能写回结果；
3. 第二实例在 lease 到期后重新 claim 同一 UUID；
4. stub 对恢复请求返回成功，receipt 进入 `DELIVERED`；
5. 证据明确标注该窗口可能产生重复，不能把请求次数断言为 exactly-once。

本地 stub 验证真实 HTTP transport、状态码和响应解析，但不冒充真实 DingTalk 账号验收。
Email 使用 `JavaMailSender` contract test double 验证单次 adapter 调用、配置失败和 transient
分类；不为此引入新的 SMTP server 依赖，也不要求保存真实收件人。

### 10.5 真实 LLM/Embedding 回归

本功能不调用模型，但会修改共享 async/alert 路径。Mock/PostgreSQL 门槛通过后，使用 `.env`
运行扩展后的 managed-principal 真实门禁。`--with-durable-notifications` 由 runner 自动启动
隔离的 DingTalk-compatible HTTP stub，向双后端注入 durable delivery 配置，并在结束时清理：

```bash
MANAGED_API_REAL_ENV_FILE=.env \
MANAGED_API_REAL_LLM_PROVIDER=minimax \
./scripts/verify-managed-api-principals.sh \
  --with-real-llm \
  --with-durable-notifications
```

必须确认：

- 真实 Chat provider 调用、SSE/OpenAI compatibility、credential rotation 继续通过；
- 真实 1024 维 Embedding、Event-driven ASYNC job、vector Search、KNOWLEDGE Chat 与引用通过；
- runner 对 `WARNING -> CRITICAL -> RESOLVED` 的每个状态读取 V58 receipt/API 与只读
  PostgreSQL；匹配 provider 的状态必须创建唯一 delivery，未开始的旧状态按规划
  supersede，最新状态由 stub 确认送达；
- stub 请求中的稳定 UUID 与 receipt 相同，且不含 root/API credential、webhook URL、
  SMTP 配置或未脱敏 payload；
- durable 模式的第一条 delivery 在一分钟 fallback 前由 after-commit Spring Event 启动；
- 运行期间持续观察后端日志，不对已确认不可用的 provider 盲等。

runner 新增独立 stub 端口与日志目录，并把 durable 模式写入 summary。未传该开关时仍保持原有
快速/真实模型门禁行为，避免所有历史调用被迫启动通知 stub。

### 10.6 静态与文档门禁

```bash
./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-project-docs.sh
bash -n scripts/*.sh
git diff --check
```

新增行密钥扫描必须通过，仓库不得出现任何真实 token、webhook、SMTP password 或外部客户
项目背景。

## 11. 发布、回滚与完成定义

本轮直接在唯一 main 工作区实施，不创建 worktree、不使用 stash。

固定顺序：

1. 规划连续 `3/3` 无修改审查；
2. 提交并推送规划 checkpoint；
3. 实施并维护进度账本；
4. focused PostgreSQL/Maven/frontend/Mock 门槛；
5. 双实例真实通知 transport；
6. 真实 LLM/Embedding 回归；
7. 双语长青文档和静态门禁；
8. fetch 并 merge 最新 `origin/main`；
9. 按合并后基线重跑完整门槛；
10. 提交并推送 `main`，确认工作区干净。

回滚边界：

- V58 additive migration 不删除旧字段；
- rollout 初始 `rag.notifications.delivery.enabled=false`，V58 binary 保持 direct path；
- 全量升级后启用 durable delivery；
- 启用后临时关闭 worker不会丢行，只会积压；
- 已产生 pending delivery 时回退 V57 binary 会停止消费，不得把“没有外发”误判为“没有告警”；
- 表回滚不在运行时自动执行。

完成必须同时满足：

- V58、原子 enqueue、event wake-up、lease/retry/recovery、API 与 WebUI 落地；
- provider 调用不在事务内，Scheduled 默认一分钟且只是兜底；
- PostgreSQL、Maven、前端、Mock、真实 HTTP transport、真实 LLM/Embedding 均通过；
- 双语长青文档同步；
- `main == origin/main` 且工作区干净。

## 12. 明确非目标

- 不引入 Kafka、RabbitMQ、Redis Streams 或云消息队列；
- 不承诺 Email/DingTalk exactly-once；
- 不实现任意用户 webhook、自定义脚本或动态 provider plugin；
- 不把多个 DingTalk 配置从 failover 改成 fan-out；
- 不保存 provider endpoint、secret、recipient、响应正文或异常堆栈；
- 不用 notification receipt 替代 alert、审计、usage、quota 或 mutation receipt；
- 不在本轮实现 token/cost hard limit、OAuth/OIDC、异步 Collection purge 或
  `EACH_COLLECTION`；
- 不为 V1–V57 历史告警补发通知。

## 13. 规划审查协议

连续三轮固定范围：

1. 需求闭环、自包含性、兼容默认、运维价值与非目标；
2. V58 schema、事务原子性、事件、lease/CAS、重试、at-least-once 与数据安全；
3. API/WebUI、一次性验收、真实 transport、真实模型回归、rollout 与 Git 交付。

发现影响正确性、成本安全、兼容性、数据一致性或可实施性的实质问题即修改本文并将计数归零。
措辞、格式、行号或实施时自然暴露的局部适配不触发归零。只有连续三轮未修改规划正文才进入
实施。
