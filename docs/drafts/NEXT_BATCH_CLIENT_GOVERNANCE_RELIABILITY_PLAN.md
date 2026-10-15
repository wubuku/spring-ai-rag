# 下一批实施规划：Client 契约治理、数据生命周期与可靠性边界

> 状态：规划已冻结，实施与验收已完成；Git 交付前保留本文件作为可恢复的实施依据。
> 基线：`9bdcffef`（`main`，2026-10-15）
> 进度账本：[NEXT_BATCH_CLIENT_GOVERNANCE_RELIABILITY_PROGRESS.md](NEXT_BATCH_CLIENT_GOVERNANCE_RELIABILITY_PROGRESS.md)

## 1. 执行摘要

本批次面向当前项目的主要服务形态：由外部 Client 通过 HTTP、SSE 和 OpenAI-compatible
入口使用 RAG 服务。目标不是再增加 WebUI 功能，而是把以下三类已经部分存在的能力收敛成
可发现、可验证、可运维的服务契约：

1. **外部 Client API 契约生命周期**：将现有 OpenAPI/manifest/黑盒检查扩展为包含响应头、
   错误 envelope、幂等重放、SSE 序列和可靠性语义的发布门禁。
2. **数据生命周期与隐私治理**：统一暴露现有保留策略、导出/删除能力和审计能力；补齐
   审计日志、Client 错误、检索评估记录的默认有界清理，并在写入边界做敏感字段脱敏。
3. **可靠性契约标准化**：保留已有 timeout、retry、rate-limit、circuit-breaker 和
   provider fallback 实现，增量增加机器可读的 `failureClass`、可靠性能力发现和黑盒断言，
   让 Client 可以区分可重试、需要退避、需要修正请求和证据不足等结果。

 本批不改变已发布 endpoint 的语义，不删除字段，不改变既有必填字段，不要求 Client 迁移；
 新能力通过可选响应字段、响应头、能力发现字段和新的管理/诊断 endpoint 增加。代码、测试、
 正式文档和 release gate 一起交付。

## 2. 当前基线与已核对事实

### 2.1 代码、迁移和分支基线

- 当前分支为 `main`，实施前工作区必须保持干净；基线提交为 `9bdcffef`。
- 模块边界为 `spring-ai-rag-api`、`spring-ai-rag-core`、`spring-ai-rag-starter`、
  `spring-ai-rag-documents`、`spring-ai-rag-webui`；本批主要修改 API/Core、脚本和文档。
- Flyway 当前最高版本为 `V63`。本批默认不新增表和列，因此默认不需要迁移；如实施中发现
  必须改变 schema，先暂停代码实施，补写迁移/回滚设计并将规划计数重置为 `0/3`。
- 项目约束、测试命令、迁移规则和 API 兼容原则以
  [AGENTS.md](../../AGENTS.md)、[delivery-workflow-zh-CN.md](../delivery-workflow-zh-CN.md)、
  [testing-guide-zh-CN.md](../testing-guide-zh-CN.md) 为准。

### 2.2 已有外部契约能力

- 原生 Chat、Search、SSE、Retrieval Trace 和 OpenAI-compatible 路由已经存在；
  `testdata/api-contract/v1-compatibility.json` 与 `scripts/verify-api-compatibility.sh`
  已提供最低兼容基线和真实服务黑盒检查。
- `ErrorResponse` 已是 RFC 7807 兼容 envelope，保留 `error`、`message`、`path` 等历史字段，
  并已增量提供 `traceId`。
- `IntegrationCapabilitiesController` 已通过 `/api/v1/rag/integration-capabilities` 暴露
  principal、能力、限制、幂等、Collection、Sync Run、观测性和 purge 能力。
- `RateLimitFilter` 已返回 `429`、`Retry-After`、`X-RateLimit-*`；全局异常处理已经对
  `ChatTurnInProgressException` 返回 `Retry-After`。
- `RagTimeoutProperties`、`RagRetryProperties`、`LlmCircuitBreaker` 和 provider fallback
  已经存在。本批不重复实现第二套 retry/circuit-breaker，只将实际可用语义纳入能力发现和
  验收检查。

### 2.3 已有数据生命周期能力

- `ChatHistoryCleanupService` 已按 `rag.memory.message-ttl-days` 定时清理聊天历史；Chat
  已有按 principal 隔离的 history、delete 和 JSON/Markdown export endpoint。
- `RetrievalDiagnosticsRetentionJob` 和 `RetrievalDiagnosticsService` 已按诊断保留天数清理
  retrieval trace；Integration observability、LLM usage、通知 delivery 等也有各自的有界
  cleanup worker。
- `CollectionPurgeService` 已提供带 preview/apply、token/fingerprint、fence、引用检查和
  上限保护的 Collection 级受保护清理；本批不重写它。
- `AuditLogService` 已写关键业务操作并对 Document/Collection 做 content-free details
  处理，但没有统一的 HTTP 查询入口和默认定时清理入口。
- `RagClientError` 和 `RagRetrievalEvaluation` 有持久化表和查询 repository，但没有统一的
  retention policy projection；客户端错误写入边界也没有和服务端统一使用敏感信息脱敏器。

### 2.4 当前缺口

1. Client 可以看到部分 capability，但不能从一个稳定的治理契约中知道保留期限、审计可见性、
   export/delete 能力、脱敏版本和自动清理是否启用。
2. API compatibility gate 目前主要验证路径、方法、schema 和基本错误形状；没有集中断言
   `failureClass`、可靠性能力、`Retry-After`/replay header 和治理 endpoint。
3. 同一类错误在不同入口可能只有 HTTP status 或机器码，Client 需要自行推断“请求错误、
   限流、依赖暂不可用、冲突、超时、内部错误”。
4. Audit log 的已有服务方法没有稳定的管理查询 API，且 Audit/Client Error/Evaluation 的
   cleanup 策略散落在不同模块，无法作为一个可审查的治理合同发现。

## 3. 目标、非目标与冻结决策

### 3.1 目标

- 保持所有现有 endpoint、HTTP 方法、旧请求字段、旧响应字段、旧错误字段和 OpenAI/SSE
  兼容入口可用。
- 新增机器可读 `failureClass` 和 `X-RAG-Failure-Class`，并确保所有由服务生成的 JSON 错误
  body（包括 rate-limit/auth filter 直接生成的 body 和 Controller 直接返回的
  `ResponseEntity<ErrorResponse>`）能够提供一致分类。
- 在 integration capabilities 中增加可靠性与治理能力投影，并新增一个只读的
  `/api/v1/rag/data-governance/policy` endpoint。
- 新增 root/ADMIN 受保护的分页审计查询 endpoint；只返回脱敏、非正文的审计视图，不暴露
  raw secret、credential hash、完整 Prompt、回答正文或 provider payload。
- 新增默认开启、批量有界、可重入的 Audit/Client Error/Evaluation retention cleanup；
  不和已有 retrieval/usage/chat/observability worker 重复删除同一表。
- 把现有 Client API manifest 扩展为可验证的 reliability/error/governance contract，
  接入 `verify-release.sh`，并提供不依赖 WebUI 的真实 HTTP 黑盒验收。

### 3.2 非目标

- 不在本批实现 OAuth/OIDC、独立 tenant hierarchy、billing、token hard limit 或 Redis；
  这些仍按 [TODO-zh-CN.md](../TODO-zh-CN.md) 中的独立规划处理。
- 不实现新的 Collection 召回 `EACH_COLLECTION` 策略。
- 不删除或改名任何 deprecated numeric field/route，不强制调用方改用新字段。
- 不把 LLM judge 放入普通 Chat 请求路径；claim-level semantic judge 若实施，只能是后续
  主动 Evaluation 能力，本批只标准化服务错误与治理契约。
- 不重写现有 timeout/retry/circuit/fallback 实现；本批只让其契约可发现、可测试。
- 不新增 WebUI 产品流程；共享 DTO/OpenAPI 变化只做现有 WebUI 构建和测试回归。

### 3.3 冻结默认值与可逆边界

- `failureClass` 默认由 HTTP status/稳定 error code 确定；未知错误归类为 `INTERNAL`，不
  将上游异常原文暴露给 Client。
- 新的 `rag.data-governance` 默认启用：audit retention `400` 天、client error retention
  `30` 天、evaluation retention `400` 天；cleanup 每次最多 `500` 行、最多 `10` 批，默认
  每小时执行。默认值可通过配置调整，但保留最小/最大边界。
- 已有独立 cleanup 的对象仍由原 worker 负责；治理 policy 同时报告其实际 retention，
  不强行合并调度器。
- Audit 查询默认 page size `50`，最大 `200`；只允许 root/ADMIN，默认按 `createdAt`
  倒序。详情字段默认不返回，避免 audit API 变成内容导出 API。
- 治理 policy 对普通已认证 Client 只返回策略和能力，不返回其他 principal、内部路径或
  数据库信息；审计记录始终是当前实例 root/ADMIN 管理面能力。
- 需要应急时可以关闭新增治理 cleanup 或 policy endpoint，但不能关闭错误脱敏，也不能让
  `failureClass` 恢复为不确定的 legacy 推断；应急开关只用于排障，不是默认运行方式。

## 4. 目标设计

### 4.1 Client API Contract Pack

保留 `testdata/api-contract/v1-compatibility.json` 的现有字段含义，在其顶层新增兼容的
`contractPolicy`、`reliability` 和 `governance` 描述，不把 manifest version 当成 HTTP API
version。静态 checker 必须做到：旧 manifest 仍能校验，新字段缺失只在新 gate 中失败。

合同最少声明：

```json
{
  "contractPolicy": {
    "changeMode": "ADDITIVE_ONLY",
    "requiredErrorProperties": ["error", "message", "status", "failureClass"],
    "nullableErrorProperties": ["traceId"],
    "responseTraceHeader": "X-Trace-Id"
  },
  "reliability": {
    "failureClassProperty": "failureClass",
    "failureClassHeader": "X-RAG-Failure-Class",
    "retryAfterStatuses": [429],
    "conditionalRetryAfterSignals": ["CHAT_TURN_IN_PROGRESS"],
    "idempotentReplayHeader": "X-RAG-Idempotent-Replay"
  },
  "governance": {
    "policyPath": "/rag/data-governance/policy",
    "auditPath": "/rag/audit-logs",
    "auditRequiresManagementAccess": true
  }
}
```

实现规则：

- OpenAPI/schema checker 校验新增字段类型和新增 endpoint 存在；仍只阻断删除、改类型、
  旧必填字段删除或新增旧必填字段。
- 黑盒脚本验证 Chat/Search/SSE/Trace 代表路径、invalid request、rate-limit/冲突可用时的
  error envelope、`X-Trace-Id`、非空 `failureClass` 和实际适用的 `Retry-After`；不会因为
  一个本来没有退避承诺的 `503` 缺少该 header 而误报失败。
- 黑盒脚本验证 policy endpoint 在受保护服务上返回 `200/401/403` 中符合部署模式的结果，
  不能把管理面未授权误报成服务故障；Audit endpoint 必须验证非 ADMIN 不会泄露记录。
- 保持 OpenAI-compatible error shape 不增加非标准 body 字段；OpenAI 入口只通过已有 header
  和 capability/诊断关联，避免破坏 OpenAI Client JSON decoder。

### 4.2 统一错误可靠性分类

在 API 模块增加稳定的 `FailureClass` 枚举，初始值冻结为：

| 类别 | 典型状态/错误 | Client 行为 |
|---|---|---|
| `CLIENT_REQUEST` | 400、422、未知请求字段 | 修正请求，不重试 |
| `AUTHENTICATION` | 401 | 更新凭据，不重试 |
| `AUTHORIZATION` | 403 | 检查 capability/ACL，不盲目重试 |
| `NOT_FOUND` | 404 | 检查资源地址，不重试 |
| `CONFLICT` | 409、幂等冲突、lease 冲突 | 刷新 revision/状态后按语义重试 |
| `RATE_LIMITED` | 429、`TOO_MANY_REQUESTS` | 尊重 `Retry-After` |
| `DEPENDENCY_UNAVAILABLE` | 502/503、provider/数据库不可用 | 有界退避，可重试 |
| `TIMEOUT` | 408/504、明确 timeout error | 尊重 deadline，有界重试 |
| `INTERNAL` | 500、未知内部错误 | 记录 trace，避免无限重试 |

`ErrorResponse.failureClass` 和 `X-RAG-Failure-Class` 是新增契约；旧字段不变。`ErrorResponse`
builder/静态 factory 负责保证 native JSON body 有非空分类；新增一个低开销的
`FailureClassResponseHeaderFilter` 根据最终 HTTP status 为尚未提交的受保护非流式响应补齐
`X-RAG-Failure-Class`，从而覆盖异常处理器和 Controller 直接返回的 `ResponseEntity`。
认证/限流 filter 在写 body 前也必须调用同一 header helper，因为写入后 Servlet response
可能已经 committed。OpenAI-compatible body 仍不增加非标准字段，但非流式错误可以共享该
响应头。HTTP `200` 后发生错误的 SSE 不能再修改 HTTP headers，因此 SSE `error` event
增量携带 `failureClass`，且黑盒检查以 event 为准；这不改变既有 `code`、`message`、
`traceId` 和 sequence 字段。原有 `traceId` body 字段继续保留，但允许为 `null`（例如没有
经过请求 Trace filter 的嵌入式调用或直接构造的兼容错误），HTTP 层的 `X-Trace-Id` 才是
外部关联的强保证。未知错误码不通过猜测 provider 文本分类，默认安全归为 `INTERNAL`。
只有实际附带退避语义的错误才要求 `Retry-After`，本批不把所有 `503` 误宣称为可按固定秒数
重试。

### 4.3 数据治理策略与审计视图

新增 `RagDataGovernanceProperties`，挂到现有 `RagProperties`：

- `enabled=true`
- `audit-log-retention-days=400`
- `client-error-retention-days=30`
- `evaluation-retention-days=400`
- `cleanup-enabled=true`
- `cleanup-batch-size=500`
- `cleanup-max-batches=10`
- `cleanup-cron=0 40 * * * *`

新增 `GET /api/v1/rag/data-governance/policy`，明确标记为 `ApiRoutePolicy.READ`，返回
`DataGovernancePolicyResponse`，只包含：

- policy version、redaction version、当前调用方可见的 retention days；
- `auditRead`、`chatExport`、`chatDelete`、`collectionPurge`、`automaticCleanup` 能力；
- 每次 cleanup 的 batch/max-batch 上限；
- 已有 trace/usage/observability/chat retention 的实际配置摘要；
- 不返回 provider secret、数据库信息、其他 principal 或原始内容。

新增 `GET /api/v1/rag/audit-logs`，明确标记为 `ApiRoutePolicy.MANAGEMENT`，支持 `page`、`size`、
`operation`、`entityType`、`entityId`、`from`、`to`（ISO-8601 instant，最大查询范围为
31 天）；返回 `AuditLogResponse` 分页，去掉
`details` 原文，只返回安全的 operation/entity/session/trace/time 摘要。

新增 cleanup worker 只处理：

1. `rag_audit_log`；
2. `rag_client_error`；
3. `rag_retrieval_evaluations`。

每次先按时间和 id 取有限 id，再按 id 批量删除；不使用显式悲观锁、`SKIP LOCKED` 或
advisory lock。已有其他表的 worker 不被治理 worker 再次删除。cleanup 失败记录日志并在
下一周期重试，不影响正常请求；policy endpoint 反映开关和边界。

写入脱敏：

- Audit description/details、Client Error message/stack/page URL 经过现有
  `SensitiveDataMaskingConverter`；
- 不改变已有 content-free audit 策略；新增脱敏是额外保护；
- 原始 secret、Authorization、API key、token、常见密码字段不得持久化或返回。

### 4.4 可靠性能力发现

扩展 `IntegrationCapabilitiesResponse` 的可选字段：

- `reliability.failureClasses`：上述稳定类别列表；
- `reliability.responseTraceHeader`、`reliability.failureClassHeader`；
- `reliability.retryAfterStatuses`；
- `reliability.nonStreamingRetryEnabled`、`streamingRetry=false`；
- 当前 timeout/retry/circuit-breaker 是否启用及其**非敏感边界**，不发布 provider URL/key。

旧 Client 忽略这些新增 JSON 字段即可继续运行。OpenAI-compatible response 不增加这些
字段，只保留已有 OpenAI envelope。

## 5. 数据、事务、并发与安全边界

- 本批默认不新增 schema，Flyway 最高版本保持 `V63`；所有新状态来自配置和已有表。
- policy 查询只读；audit 查询使用当前调用方管理权限，不跨 principal 透露 Collection 或
  Chat 内容。对于 auth-disabled 本地模式，继续遵循现有 management route policy 和
  loopback/root 规则，不新增远程匿名管理能力。
- cleanup 使用有界批处理和数据库唯一主键，单批独立事务；单批失败不回滚之前已完成批次，
  也不阻塞 API 请求。批处理按 `created_at ASC, id ASC` 保证最终收敛。
- 删除不写入新的正文副本；审计删除本身只使用现有 logger，不把被删除内容写进日志。
- 所有新增请求参数做范围校验；page/size、日期范围、filter 长度和 cleanup 上限必须有硬边界。
- 不在日志、异常响应、能力发现、audit response 中输出密钥、hash、Prompt、回答全文、
  文档正文或 provider 原始响应。

## 6. 文件级实施顺序

### Slice A：规划冻结与 API 基础契约

1. 增加共享 `FailureClass` 映射/header helper；统一 GlobalExceptionHandler、认证/限流
   filter、OpenAI 以外的直接错误 body 分类和 header；为 native SSE error event 增加分类。
2. 新增 `FailureClass`，扩展 `ErrorResponse`，保留旧构造器、builder、equals/hashCode 语义。
3. 扩展 `IntegrationCapabilitiesResponse`/`IntegrationCapabilityCatalog` 的可靠性投影。
4. 更新 `testdata/api-contract/v1-compatibility.json`、checker 和黑盒脚本。

### Slice B：数据治理配置、清理与脱敏

1. 新增 `RagDataGovernanceProperties` 并挂入 `RagProperties` 与 `application.yml`。
2. 扩展 Audit/Client Error/Evaluation repository 的有界 id 查询和删除路径。
3. 新增 `DataGovernanceCleanupService`，加入定时任务、失败日志和单元测试。
4. 加强 `AuditLogService`、`ClientErrorServiceImpl` 写入脱敏；添加敏感字段测试。

### Slice C：治理和审计 HTTP 只读入口

1. 新增 API DTO：`DataGovernancePolicyResponse`、`AuditLogResponse`。
2. 新增 `DataGovernanceController` 的 policy endpoint 和管理权限下的 audit endpoint。
3. 为 OpenAPI、route policy、权限、分页、过滤、无内容泄露增加 Spring MVC 集成测试。

### Slice D：文档、发布门禁和全面验收

1. 更新中英文 `rest-api`、`configuration`、`architecture`、`testing-guide`、
   `developer-reference` 和 `business-client-integration`；明确新字段均可选。
2. 新增 `scripts/verify-client-contract-governance.sh` 或等价 gate，并接入
   `scripts/verify-release.sh`。
3. 执行 API/Core/Starter/Ddocuments 全量测试、PostgreSQL/Flyway 集成、黑盒、真实 LLM、
   `scripts/dev.sh` readiness、锁检查、文档检查和 bundle/现有 WebUI 回归。
4. 实施完成后执行连续三轮实现检查；如任何实质问题导致修改，计数归零。

## 7. 验收矩阵

| 层次 | 验收内容 | 必须结果 |
|---|---|---|
| API 单元 | FailureClass 映射、ErrorResponse 旧字段/旧 builder、敏感信息 mask | 全通过，旧 equals/JSON 断言不回退 |
| Core 单元 | cleanup batch 上限、id 顺序、失败后可重试、policy 投影 | 无未界定删除，无正文泄露 |
| MVC 集成 | policy 200/401/403、audit root/ADMIN、NORMAL 拒绝、分页/过滤 | response shape 稳定，trace/header 正确 |
| OpenAPI | 新 DTO、字段、endpoint、错误 schema | 静态 checker 与真实 spec 一致 |
| Client 黑盒 | Chat/Search/SSE/Trace、错误 body/header、治理 endpoint | 不删除旧字段；新增字段类型正确 |
| 迁移 | V63 现有库启动、无 schema 变更、迁移版本测试 | 无手工 SQL；旧数据透明可用 |
| 清理集成 | 注入过期 audit/client error/evaluation，执行 worker，校验只删上限内记录 | 多批最终收敛，其他表不误删 |
| 真实运行时 | `scripts/dev.sh`、readiness、OpenAPI、Native Client、trace、policy | 后端可启动，18082 无残留进程 |
| 真实 LLM | Search、Knowledge JSON/SSE、证据安全和新 error contract | 不新增普通 Chat 评测调用；失败可诊断 |
| WebUI 回归 | TypeScript、Vitest、生产 build、已有 Mock Playwright | 共享 DTO 增量字段不破坏客户端 |
| 静态发布 | docs、shell、lock、secret、diff、release gate | 全部通过 |

## 8. 失败、回滚与发布策略

- 无 schema 变更时，旧服务可以读取新配置以外的所有旧数据；回滚只回滚代码和配置，不需要
  手工 SQL。新增 cleanup 默认只删除超过保留期的数据，因此回滚不会恢复已删除历史；发布前
  必须在正式文档明确默认 retention。
- 若新增 schema 成为必需，必须补 `V64` 或更高版本 Flyway，提供向前迁移、旧服务读取边界、
  自动回填和集成测试；不得修改 V63。
- `failureClass` 是增量 JSON/header，旧 Client 忽略即可；OpenAI body 保持原形，避免其
  兼容性回退。
- 新 governance endpoint 失败不影响 Chat/Search；cleanup 失败只产生可脱敏日志和下一周期
  重试，不把维护错误转换为普通 Client 请求失败。
- release gate 失败时阻止 push；不得用跳过真实外部 Client 检查来宣称完成。环境性 Docker
  网络失败要按项目网络指南处理并单独记录，不修改 Dockerfile 硬编码区域镜像。

## 9. 规划检查范围与完成定义

规划检查必须按以下顺序执行，连续三轮无实质修改后才可进入代码：

1. 需求闭环：三条推荐方向、外部 Client 重点、API 增量兼容、默认启用、无人值守执行是否
   全部落实；确认非目标没有偷偷变成阻断依赖。
2. 代码/数据/安全/并发：逐项核对 controller、DTO、filter、properties、repository、
   现有 cleanup、权限、migration、OpenAPI 和无锁规则；重点检查不会重复删除、跨 principal
   泄露或破坏 OpenAI/SSE。
3. 实施/验收/回滚：检查文件顺序、测试矩阵、真实 LLM 与 WebUI 取舍、脚本集成、迁移无变更
   边界、回滚和中断恢复是否可执行。

只有以下条件全部满足才算本批完成：

- 规划 `3/3`、实施 `3/3` 连续无修改检查通过；
- API 兼容 manifest、OpenAPI、真实黑盒和可靠性 gate 通过；
- policy/audit/cleanup 的权限、脱敏、边界和最终一致测试通过；
- `mvn test`、适用 PostgreSQL/Flyway、`scripts/dev.sh`、真实 LLM（如配置可用）、文档和
  静态门禁通过；
- 双语正式文档完成，本 draft/progress 更新为可归档状态；
- 用户要求的 Git 交付步骤完成后，`main` 与 `origin/main` 同步且工作区干净。

## 10. 规划检查记录

检查记录由进度账本维护。发现实质问题时，必须先修正文档并将规划计数重置为 `0/3`；无修改
轮次只在会话输出中总结，不在连续三轮之间改写规划正文。
