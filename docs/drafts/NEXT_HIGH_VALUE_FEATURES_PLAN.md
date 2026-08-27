# 受管 API Principal 到期预警与生命周期收敛实施规划

> 状态：规划审查 `3/3` 通过，待实施
> 日期：2026-08-27
> 当前基线：`main@2291c60f`
> 配套进度：[NEXT_HIGH_VALUE_FEATURES_PROGRESS.md](NEXT_HIGH_VALUE_FEATURES_PROGRESS.md)
> 交付规则：[规划、实施与验收工作流](../delivery-workflow-zh-CN.md)

## 1. 问题、价值与本轮决策

当前系统已经把受管 API principal、版本化 credential、即时吊销、共享请求配额、
operation capability、创建幂等和分阶段轮换建立在 PostgreSQL 权威状态上。principal
在 root mode 下必须设置未来的 `expiresAt`，认证查询也会在到期后立即拒绝请求。

缺口在于“到期前的生产运维闭环”：

- operator 可以在 API Key 列表中看到到期时间，但系统不会主动形成“即将到期”告警；
- 时间跨过预警阈值没有业务事件，必须依赖某种低频时间对账；
- principal 被延期或吊销后，旧告警没有自动解决语义；
- 多实例若各自扫描并调用现有 `fireAlert`，会产生重复活跃告警和重复通知；
- 现有 `rag_alerts` 没有稳定 dedupe key、条件状态或通知版本，不能作为可收敛的受管告警；
- Alerts WebUI 把后端实际返回的 `firedAt` 读取为 `triggeredAt`，时间展示契约不一致。

本轮选择“受管 API principal 到期预警与生命周期收敛”，而不是以下候选：

| 候选 | 本轮判断 |
|---|---|
| 大 Collection 异步 purge | 有价值，但当前同步 purge 已 fail closed；异步清理需要新的长任务、分片删除和恢复协议，适合后续独立批次 |
| token/cost hard limit | 成本安全价值高，但必须设计预授权、预留、结算、provider usage 缺失和崩溃恢复，不能在观测账本上仓促加阈值 |
| OAuth/OIDC federation | 属身份体系扩展，依赖部署方 IdP、tenant 映射和 token 校验策略，不是当前外部接入的最近生产缺口 |
| `EACH_COLLECTION` 召回覆盖 | 是质量/延迟权衡能力；当前没有 goldenset 证明全局 top-k 正在造成系统性漏召回 |
| credential 到期预警 | 当前已存在权威 expiry、Alerts、通知和 WebUI，缺口明确，能以小而完整的通用能力显著降低生产中断风险 |

本轮交付目标：

1. principal 创建、expiry 策略修改和吊销事务提交后发布 Spring Event，异步触发单 principal
   对账；
2. 低频、有界 Scheduled 扫描只处理时间跨阈值和漏事件恢复，默认每小时一次；
3. 多实例共享 PostgreSQL dedupe/CAS 状态，任一 principal 同时最多一个活跃到期告警；
4. 告警在 `WARNING`、`CRITICAL`、`EXPIRED` 间升级，在延期出窗口或吊销后自动解决；
5. 同一状态重复事件、服务重启和多实例扫描不重复发送通知；
6. Alerts API/WebUI 使用一致的 `firedAt` 契约，并能可访问地展示 principal、阶段和到期时间；
7. 不保存或输出 raw credential、hash、完整 allow-list、业务 payload 或模型内容。

## 2. 已核对的代码与数据事实

### 2.1 Principal 生命周期

- [ApiKeyManagementService.java](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/ApiKeyManagementService.java)
  的 `createPrincipal` 在同一事务保存 principal 和 version 1 credential；
- `updatePolicy` 使用 `policyVersion` CAS，并在 root mode 下强制新 expiry 位于未来；
- `revoke` 原子写入 `revoked_at`、禁用当前/retiring credential，并收敛 pending rotation；
- credential 的 immediate/staged rotation 不改变 principal expiry；到期身份仍是 stable
  `principal_id`，因此告警必须归属于 principal 而不是 credential version；
- [RagApiPrincipalRepository.java](../../spring-ai-rag-core/src/main/java/com/springairag/core/repository/RagApiPrincipalRepository.java)
  当前没有按到期窗口扫描的索引或查询。

### 2.2 Alerts 与通知

- V7 的 `rag_alerts` 保存 type、name、message、severity、metrics、status 和时间；
- V17 增加 JPA optimistic `version`，但没有业务 dedupe key；
- [AlertServiceImpl.java](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/AlertServiceImpl.java)
  的 `fireAlert` 每次都插入新行，适合人工/阈值事实，不适合多实例受管条件对账；
- Email 和 DingTalk 通知是 best-effort，并按 `alertType` allow-list 过滤；当前实现把
  `@Async` 用在 primitive `boolean` 返回值上，不符合 Spring Async 只支持
  `void`/`Future` 类返回值的代理合同，必须在本轮改为
  `CompletableFuture<Boolean>` 才能把新告警真实送入异步渠道；
- 两种通知渠道的默认 allow-list 都不包含本轮新类型；若不修改，operator 即使启用通知也只会
  在 WebUI 看到记录，不能形成主动预警；
- silence schedule 使用 `alertType + ":" + alertName`，受管告警可以在持久状态继续可见，
  但在发送外部通知前仍应尊重 silence；
- Alerts controller 已有 active/history/stats/resolve 等 API，不需要新增平行告警列表。
- Alerts controller 当前没有 operator 角色校验。普通数据库 `NORMAL` principal 会按 HTTP
  方法落入通用 `RAG_READ`/`RAG_WRITE` capability，可能读取或修改全局告警；本轮加入
  principal ID 前必须同步收紧该管理面边界。

### 2.3 事件与恢复扫描模式

- [EmbeddingJobWakeupPublisher.java](../../spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/EmbeddingJobWakeupPublisher.java)
  已证明“事务提交后 Spring Event 唤醒 + 低频 Scheduled 兜底”的项目模式；
- 事件只是低延迟提示，PostgreSQL 仍是权威状态；事件丢失不能影响最终收敛；
- 项目禁止显式悲观锁、`SKIP LOCKED` 和 advisory lock，必须使用唯一约束、条件更新/CAS
  和有界重试。

### 2.4 WebUI 契约

- 后端 `AlertRecord` 输出 `firedAt`；
- [alerts.ts](../../spring-ai-rag-webui/src/api/alerts.ts) 和
  [Alerts.tsx](../../spring-ai-rag-webui/src/pages/Alerts.tsx) 当前声明/读取 `triggeredAt`；
- Alerts 页已有 30 秒刷新，不需要增加更高频前端轮询；
- WebUI 由 environment root 解锁，受管告警不会暴露给普通业务 principal 的浏览器。

## 3. 冻结的状态与配置语义

### 3.1 条件阶段

对 `revoked_at IS NULL` 且 `expires_at IS NOT NULL` 的 principal，根据数据库当前时间计算：

| 条件 | 阶段 | severity |
|---|---|---|
| `expires_at > now + warningWindow` | `NONE` | 无活跃告警 |
| `now + criticalWindow < expires_at <= now + warningWindow` | `WARNING` | `WARNING` |
| `now < expires_at <= now + criticalWindow` | `CRITICAL` | `CRITICAL` |
| `expires_at <= now` | `EXPIRED` | `CRITICAL` |
| `revoked_at IS NOT NULL` | `NONE` | 自动解决已有活跃告警 |

默认：

- `warning-window=30d`；
- `critical-window=7d`；
- `fallback-scan-interval=PT1H`；
- `fallback-scan-limit=10000`；
- 事件消费者使用项目公共有界 async executor，不新建无界线程池。

配置前缀为 `rag.api-key-expiry-alerts`：

| 配置 | 默认 | 约束 |
|---|---:|---|
| `enabled` | `true` | 关闭后不创建/升级新告警，但仍允许显式 API 查询历史 |
| `warning-window` | `P30D` | 1 天至 180 天 |
| `critical-window` | `P7D` | 1 小时以上且严格小于 warning window |
| `fallback-scan-interval` | `PT1H` | 10 分钟至 24 小时 |
| `fallback-scan-limit` | `10000` | 100 至 100000 |
| `event-retry-attempts` | `3` | 1 至 10，处理唯一约束/CAS 竞争 |

配置非法时启动失败。Scheduled 不是主路径，不允许把默认值降为秒级轮询。

### 3.2 告警投影

固定字段：

- `alertType=API_PRINCIPAL_EXPIRY`；
- `alertName=Managed API principal expiry`；
- `dedupeKey=api-principal-expiry:{principalId}`，只保存在数据库控制字段，不包含 secret；
- `conditionState=WARNING|CRITICAL|EXPIRED`；
- `message` 只包含 stable principal ID、阶段和带明确 zone ID 的 expiry；
- `metrics` 只允许：
  - `principalId`；
  - `principalRole`；
  - `expiresAt`；
  - `timeZone`；
  - `phase`；
  - `secondsRemaining`，到期后为 `0`；
  - `policyVersion`。

不写 principal name、credential ID/version、raw key/hash、Collection allow-list、quota、
last-used 详情或请求内容。principal ID 是管理面已有稳定标识，不是 credential。

Email 与 DingTalk 的默认 `alertTypes` 增加 `API_PRINCIPAL_EXPIRY`。只有全局通知和对应渠道
都启用时才外发；既有部署显式配置了 allow-list 时保持配置优先，不被代码静默扩大。
`NotificationService.sendAlert` 改为 `CompletableFuture<Boolean>`，两个实现继续通过
`@Async` 有界执行并返回完成结果；现有 `AlertService.fireAlert` 可以保持 fire-and-forget，
但不再触发非法 async return type。外部渠道仍是 best-effort，不承诺 exactly-once：
数据库 active alert 才是权威运维状态，重复事件只保证不会重复发起同一 state version 的
发送尝试。

### 3.3 自动解决与人工操作

- expiry 被延期到 warning window 之外：自动 `RESOLVED`，resolution 为固定低敏原因；
- principal 被吊销：自动 `RESOLVED`；
- expiry 在窗口内被修改：同一活跃行更新 metrics/message；只有阶段改变才增加通知版本；
- `WARNING -> CRITICAL -> EXPIRED` 复用同一活跃行，不制造三条同时活跃的告警；
- 条件解除后再次进入窗口：创建新的活跃告警，保留上一轮 resolved 历史；
- operator 人工 resolve 仍可用；若条件仍成立，下一次事件或 fallback scan 会新建告警。
  需要临时抑制外部通知时应使用 silence，而不是把尚未解除的条件标为已解决。

## 4. V57 数据模型

V57 对 `rag_alerts` 做 additive 迁移：

- `dedupe_key VARCHAR(160)`，nullable，旧告警和人工 fire 保持 `NULL`；
- `condition_state VARCHAR(32)`，nullable；
- `state_version INTEGER NOT NULL DEFAULT 0`；
- `notified_version INTEGER NOT NULL DEFAULT 0`；
- `updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()`；
- `rag_api_principal.expiry_alert_checked_at TIMESTAMPTZ`，只记录最近一次成功对账时间，
  不参与认证、policy CAS 或对外 principal DTO；
- 检查约束：
  - managed 行必须同时拥有 `dedupe_key` 与 `condition_state`；
  - `state_version >= 0`；
  - `0 <= notified_version <= state_version`；
- partial unique index：
  `UNIQUE(dedupe_key) WHERE dedupe_key IS NOT NULL AND status='ACTIVE'`；
- `rag_api_principal(expires_at, principal_id) WHERE revoked_at IS NULL AND expires_at IS NOT NULL`
  的 partial index，支持小时级窗口扫描；
- `rag_api_principal(expiry_alert_checked_at NULLS FIRST, principal_id)` 的候选轮转索引。

不修改旧行状态，不回填 dedupe key，不改变手工/SLO 告警允许重复的历史语义。

`RagAlert` 增加相应字段映射；`AlertRecord` 不返回 `dedupeKey`，避免把内部协调 key 扩展成
HTTP 契约。它可以返回 `conditionState` 和现有 metrics，便于 WebUI 稳定显示阶段。

## 5. 事件、对账与并发设计

### 5.1 事务提交后事件

新增：

- `ApiPrincipalLifecycleChangedEvent(principalId)`；
- `ApiPrincipalLifecycleEventPublisher.publishAfterCommit(principalId)`。

publisher 使用 transaction synchronization：

- 有事务时只在 `afterCommit` 发布；
- 无事务时立即发布；
- 发布失败记录不含 secret 的 warning，Scheduled 最终恢复；
- 同一事务重复登记同一 principal 时合并，避免 update/rotation helper 重复唤醒。

`ApiKeyManagementService` 在以下成功写路径登记事件：

- 新 principal 创建；
- policy update，特别是 expiry 修改；
- family revoke。

rotation 不改变 expiry，不单独发布；若未来 rotation 改写 principal expiry，必须同步补事件。

### 5.2 异步事件消费者

`ApiPrincipalExpiryAlertWorker`：

- `@EventListener` 接收事件，`@Async("taskExecutor")` 执行；
- 每次只按 principal ID 权威重读当前行；
- 调用 `ManagedAlertService.reconcilePrincipalExpiry`；
- DataAccess/optimistic conflict 使用配置的 1-10 次有界重试和短退避；
- 事件线程不持有 API 管理事务，不向请求返回路径传播告警故障。
- 成功完成告警 create/update/resolve 后，单独条件更新该 principal 的
  `expiry_alert_checked_at`；失败不推进，留给下一次事件/扫描恢复。

### 5.3 Scheduled 兜底

每小时执行一个有界集合查询：

- 选出当前在 warning window 内的未吊销 principal；
- 并集选出仍有活跃 expiry managed alert 的 principal，以便恢复漏掉的延期/吊销解决；
- 去重后最多处理 `fallback-scan-limit` 个；
- 按 `expiry_alert_checked_at NULLS FIRST`、expiry、principal ID 排序；本轮成功对象会推进
  checked time，因此即使候选总数长期超过单轮 limit，后续轮次也会公平覆盖；
- 超过上限记录低敏 counter/warning，不在单轮无限循环；operator 可增大上限或缩短至不低于
  10 分钟的间隔。

扫描只读一次候选集合，再逐 principal 执行短事务。默认一小时一次，避免频繁轮询数据库。

### 5.4 Managed alert CAS

`ManagedAlertService` 使用唯一约束和条件写入，不使用锁：

1. 条件为 `NONE` 时，按 alert `id + version + status=ACTIVE` 条件更新当前 active dedupe
   行为 `RESOLVED`；
2. 条件存在且无 active 行时插入 `state_version=1, notified_version=0`；
3. 唯一冲突表示其他实例已创建，重新读取后进入更新；
4. 同阶段按 `id + version + status=ACTIVE` 更新 message/metrics/expiry，但不增加
   `state_version`；
5. 阶段改变时用相同 CAS 更新 `condition_state` 并令
   `state_version=state_version+1`；
6. 通知 claim 使用
   `UPDATE ... SET notified_version=state_version, version=version+1
   WHERE id=? AND version=? AND notified_version<state_version`
   的 CAS；只有成功 claim 的实例发起一次 best-effort 通知；
7. 所有竞争重试有界，耗尽后保留数据库事实并等待下一次事件/扫描。

所有 managed JDBC update 都同时推进 JPA `@Version` 和 `updated_at`，避免 controller 的
JPA resolve 与异步 reconcile 发生丢失更新。外部通知在数据库事务提交后发起；通知失败不
回滚告警状态，也不无界重试，现有通知渠道仍保留各自的短重试预算。进程在 claim 提交后、
异步渠道真正发送前退出时，可能丢失该 state version 的外部通知，但 active alert 仍可靠
存在，下一阶段会产生新的发送尝试；本轮不把通知系统扩展为通用持久 outbox。

## 6. API 与 WebUI

### 6.1 API

不新增 principal expiry 专用 HTTP endpoint。现有接口即为稳定入口：

- `GET /api/v1/rag/alerts/active`；
- `GET /api/v1/rag/alerts/history`；
- `GET /api/v1/rag/api-keys/principals`。

`AlertRecord` 保持 additive：

- 继续输出 `firedAt`；
- 新增可选 `conditionState`；
- `metrics` 使用第 3.2 节的低敏固定字段。

既有手工 fire、SLO、resolve、silence API 不改变。OpenAPI 合同测试必须覆盖 `firedAt`，
防止前端再次使用不存在的 `triggeredAt`。

所有 `/api/v1/rag/alerts/**` 明确归类为 operator 管理面：

- environment root：允许；
- 数据库 `ADMIN` principal：允许；
- root mode 关闭时的 legacy static credential：为兼容现有单管理员部署，允许；
- auth-disabled：只允许直接 Servlet `remoteAddr` 为 loopback，且不信任 forwarded header；
- 数据库 `NORMAL` principal：无论拥有 `RAG_READ` 还是 `RAG_WRITE` 都返回通用 `403`。

该限制应用于 active/history/stats、manual fire、resolve、silence、SLO config 和 silence
schedule 全部路由，避免调用方从另一条 Alerts 路径读取 principal ID 或修改运维状态。
WebUI 当前只接受 environment root 解锁，不受影响。新增复用型
`AlertManagementAuthorization`，controller 入口统一调用，不能只在 expiry 结果序列化时过滤。

### 6.2 WebUI

Alerts active 列表：

- TypeScript 字段改为 `firedAt`；
- 展示 alert type、condition state、message、severity 和触发时间；
- 对 `API_PRINCIPAL_EXPIRY` 显示 principal ID 与 expiry，数据只来自 allow-list metrics；
- 缺失/非法时间显示稳定 fallback，不渲染 `Invalid Date`；
- 保持 30 秒刷新，不增加截图验收。

API Keys 列表继续显示权威 `expiresAt`，本轮不复制一套独立前端阈值逻辑。告警阶段以服务端
数据库时间和配置为准，避免浏览器时区/配置漂移。

## 7. 失败、安全、兼容与运维边界

- 告警子系统 fail-open：创建/更新 principal 的权威事务成功后，告警故障不能把管理 API
  变成失败；低频扫描负责恢复。
- 认证仍 fail closed：到期判断继续由 credential/principal 联表查询执行，不依赖告警。
- 多实例共享同一 partial unique index、state/notified version；本地事件不承担跨实例真相。
- API Key secret、hash、Authorization header、Collection key/allow-list 和请求 payload
  不进入 event、alert、日志、metric label 或验证证据。
- Alerts API 的 operator 授权发生在读取 repository 之前；`NORMAL` principal 的 `403`
  不返回 alert 数量、principal ID 或目标是否存在。
- Micrometer 仅增加低基数 counter：
  - reconcile success/failure；
  - phase transition；
  - fallback scan truncated；
  标签只允许 outcome/phase，不允许 principal ID。
- V57 是 additive schema。旧 binary 可以忽略新列，但混合 fleet 中只有新实例会维护告警；
  该能力不参与认证正确性，因此允许滚动升级，不需要像 credential rotation/purge 一样冻结
  数据面。
- 应用回滚保留 V57；旧 binary 的手工 alert 行 `dedupe_key=NULL` 不受影响。

## 8. 实施切片

### 切片 A：配置、迁移与受管告警协调器

- 新增 V57；
- 扩展 `RagAlert` / repository；
- 新增 `RagApiKeyExpiryAlertProperties` 与启动校验；
- 修正 `NotificationService` 的 Spring Async 返回合同；
- 实现 `ManagedAlertService` 的 dedupe、阶段升级、自动解决和通知 claim。

### 切片 B：Principal 生命周期事件

- 新增 event/publisher/worker；
- 接入 create/update/revoke 成功路径；
- 增加 fallback scan 和低基数指标。

### 切片 C：API/WebUI

- `AlertRecord.conditionState` additive 输出；
- 为全部 Alerts 路由增加 root/ADMIN/legacy-static/loopback operator 授权；
- 更新 Email/DingTalk 默认 alert type allow-list；
- 修正 `firedAt` TypeScript/DOM；
- 增加 expiry managed alert 的可访问展示和前端测试。

### 切片 D：长青文档与门禁

双语同步：

- `configuration*`：配置和默认扫描频率；
- `architecture*` / `project-context*`：事件提示、PostgreSQL 真相和 CAS；
- `rest-api*`：managed alert 投影与 `firedAt`；
- `business-client-integration*`：到期前运维闭环；
- `testing-guide*` / `release-checklist*`：专项门禁和发布检查；
- `TODO*`：把 credential expiry alerting 标记为已交付；
- `AGENTS.md`、project-docs Skill 和迁移索引更新到 V57。

新增 `scripts/verify-api-key-expiry-alerts.sh`，并接入适当的项目/发布门禁入口。

## 9. 一次性验收矩阵

### 9.1 快速测试

- properties 边界：窗口顺序、扫描间隔、limit/retry；
- publisher：commit 后发布、rollback 不发布、无事务立即发布、同事务去重；
- notification：`CompletableFuture<Boolean>` 通过真实 Spring proxy 异步执行，不出现非法
  return type；
- managed alert：
  - 首次 WARNING；
  - 同阶段重复不增加通知版本；
  - WARNING -> CRITICAL -> EXPIRED 复用同一 active 行并各通知一次；
  - 延期出窗口、吊销自动解决；
  - 解除后再次进入窗口创建新历史；
  - 人工/SLO 告警仍可重复；
- API key service：create/update/revoke 登记事件，rotation 不误登记；
- Alert controller：root/ADMIN/legacy static/loopback 兼容通过，数据库 NORMAL 在 repository
  访问前得到不泄露细节的 `403`；
- notification：默认 allow-list 包含新类型，显式配置仍保持配置优先；
- WebUI：`firedAt`、phase、principal/expiry 展示和非法时间 fallback。

### 9.2 PostgreSQL 集成

在空库真实执行 V1-V57，并验证：

1. partial unique index 只限制 active managed dedupe，不影响旧/人工告警；
2. 两个协调器并发 reconcile 同一 principal，只产生一条 active 行；
3. 通知版本 CAS 在多实例竞争下只允许一次 claim；
4. direct SQL 写入模拟漏事件后，fallback scan 创建 WARNING；
5. direct SQL 延期/吊销模拟漏事件后，fallback scan 自动解决；
6. 时间推进跨 critical/expired 阈值后阶段升级；
7. alert/event/log 数据不包含 raw credential/hash；
8. 扫描上限有界且索引存在。
9. 候选数量超过单轮 limit 时，连续 fallback scan 会推进
   `expiry_alert_checked_at` 并覆盖后一批 principal，不发生永久饥饿。

### 9.3 HTTP 与前端

- root 创建短期 principal 后，事件路径在有界等待内出现在 `/alerts/active`；
- policy 延期后 active alert 消失，history 中为 resolved；
- 吊销同样收敛；
- `/alerts/active` JSON 使用 `firedAt` 和 `conditionState`；
- 数据库 `NORMAL` principal 无法读取、fire、resolve 或配置 Alerts；root/ADMIN 可以；
- TypeScript、全量 Vitest、生产构建；
- 核心 Mock Playwright 通过 DOM、可访问性和网络断言验证 Alerts 页，不使用截图。

### 9.4 基本硬门槛

```bash
./scripts/verify-api-key-expiry-alerts.sh
mvn clean compile test-compile
cd spring-ai-rag-webui
npx tsc -b --pretty false
npm run test:run
npm run build
npx playwright test <本任务核心 spec>
./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-project-docs.sh
git diff --check
```

服务必须以 `postgresql` profile 在隔离端口启动并通过 liveness/readiness。

### 9.5 真实 provider 回归

先完成 Mock/PostgreSQL 门槛，再使用 `.env` 的真实 Chat/Embedding provider：

1. 创建隔离 Collection 和处于 WARNING 窗口的受管 principal；
2. 使用该 principal 写入 ASYNC JSON Record，等待真实 embedding READY；
3. 使用相同 principal 进行真实 Chat，证明预警不改变授权或模型路径；
4. 查询 active alert，核对低敏字段；
5. 延期 principal 后确认告警自动解决；
6. 清理测试 principal/Collection，保留脱敏 summary。

真实调用期间持续观察日志。该验证不是用模型判断告警，而是证明新增事件/扫描和 schema
没有破坏真实生产调用生命周期。

## 10. 发布、同步与完成定义

实施使用当前工作区中的专用分支，不创建 worktree。规划在 `main` 收敛并提交后创建分支。

交付前：

1. `git fetch origin` 并 merge 最新 `origin/main` 到特性分支；
2. 记录合并后 commit、数据库和端口基线；
3. 按第 9 节完整重跑，不沿用合并前结论；
4. 提交并推送特性分支；
5. merge 到 `main`、推送 `main`；
6. 将本 plan/progress 的长期事实提升到双语长青文档并归档；
7. 确认 `main == origin/main` 且工作区干净。

本轮完成必须同时满足：

- V57、事件、fallback、CAS 和 WebUI 全部落地；
- PostgreSQL/Maven/前端/Mock Playwright/服务启动通过；
- 真实 provider 生命周期回归通过；
- 双语长青文档与门禁同步；
- Git 交付完成且没有密钥或外部 Client 项目背景进入仓库。

## 11. 明确非目标

- 不实现 credential 自动轮换、自动延期或 secret manager 写入；
- 不发送 principal name、credential ID 或 allow-list 到外部通知；
- 不把告警状态作为认证、授权或请求限流依据；
- 不新增秒级定时扫描、Kafka、RabbitMQ 或其他消息代理；
- 不重构整个旧 Alerts/SLO API，不修复与本轮无关的告警产品设计；
- 不在本轮实现 async Collection purge、token/cost hard limit、OAuth/OIDC 或
  `EACH_COLLECTION`。

## 12. 规划审查协议

连续三轮固定范围：

1. 需求闭环、自包含性、默认值和非目标；
2. schema、事务、事件、多实例 CAS、安全和兼容可行性；
3. 实施切片、验收矩阵、真实 provider、发布和恢复。

发现影响正确性、成本安全、兼容性、数据一致性或可实施性的实质问题即修订本文并将计数归零。
只有连续三轮未修改规划正文才进入实施。

规划已于 2026-08-27 完成连续三轮固定范围无修改审查：

1. 需求闭环、自包含性、默认值、管理面安全和非目标；
2. V57、事务后事件、多实例 CAS、通知 claim、扫描公平性和兼容性；
3. 实施切片、一次性验收矩阵、真实 provider 回归、发布和回滚。

结论：`3/3` 通过，可以按本文进入实施。
