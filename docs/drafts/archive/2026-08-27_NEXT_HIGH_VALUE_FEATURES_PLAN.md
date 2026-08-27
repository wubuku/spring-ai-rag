# 下一批高价值功能实施规划：有界零停机 API Credential 轮换

> 状态：已实施、已合并到 `main`，最终验收通过
> 日期：2026-08-27  
> 最终提交：`main` / `origin/main` = `0873755d`
> 配套进度：[NEXT_HIGH_VALUE_FEATURES_PROGRESS.md](2026-08-27_NEXT_HIGH_VALUE_FEATURES_PROGRESS.md)

## 1. 摘要

当前项目已经具备 stable API principal、版本化 credential、principal 级 ACL/能力/配额、
即时轮换和即时撤销，但现有轮换事务会先禁用旧 credential，再创建新 credential。这只适合
单实例或可以原子替换 secret 的调用方，无法安全支持常见的生产流程：

```text
生成新 secret
  -> 写入 secret manager
  -> 滚动更新多个调用实例
  -> 用新 secret 健康验证
  -> 停用旧 credential
```

本轮新增**有界、两阶段、可取消的 staged rotation**，并保留现有
`POST /api/v1/rag/api-keys/{keyId}/rotate` 的即时切换语义。准备阶段允许同一 stable
principal 的 current credential 与一个 retiring credential 在受控窗口内同时认证；
完成、取消、principal 撤销或窗口到期都会收敛回至多一个可认证 credential。两个 credential
共享同一 principal、ACL、operation capabilities、policy version、会话 owner 和 PostgreSQL
quota，不会因轮换获得双倍配额或越权。

本轮使用 Flyway `V55`，新增 staged rotation API、WebUI 工作流、能力发现、真实 PostgreSQL/
双实例 HTTP/浏览器验收以及适用的真实 LLM 生命周期验证。

## 2. 为什么这是当前最高价值缺口

### 2.1 已确认的代码事实

- [`ApiKeyManagementService.rotate`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/ApiKeyManagementService.java)
  在一个事务中先调用 `disableByKeyId`，再保存新 credential。
- [`V48__managed_api_principals_and_shared_quota.sql`](../../../spring-ai-rag-core/src/main/resources/db/migration/V48__managed_api_principals_and_shared_quota.sql)
  的 `uk_rag_api_key_active_principal` 保证每个 principal 最多一个 `enabled=true` row。
- 认证由 [`RagApiKeyRepository.authenticate`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/repository/RagApiKeyRepository.java)
  每次权威联查 credential 与 principal；没有正向授权缓存。
- quota、Chat owner、幂等 owner 和 usage ledger 都使用 stable principal，因此允许短期双
  credential 不需要复制或迁移这些状态。
- [`ApiPrincipalResponse`](../../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/ApiPrincipalResponse.java)
  和 WebUI 当前只表达一个 current credential，没有 pending rotation 状态。
- [`verify-managed-api-principals.sh`](../../../scripts/verify-managed-api-principals.sh)
  与真实 WebUI Playwright 都把“旧 key 立即 401”作为现有即时轮换合同。

### 2.2 生产影响

如果调用方有多个副本，当前接口一返回新 secret，尚未更新的副本会立即收到 `401`。调用方只能
接受停机窗口、同时重启全部实例，或在服务外维护风险更高的共享 root/static credential。
这与 stable principal 已提供的身份连续性不匹配，也是生产扩大时最直接的可用性风险。

### 2.3 为什么不优先做其他候选

外部托管文档 purge、Collection 退役和更复杂的身份联合仍有价值，但它们涉及更大的数据删除、
保留和权限语义。零停机 credential 轮换有明确现状缺口，复用现有 principal 模型，风险和
收益边界更清晰，应先完成。

## 3. 目标、非目标与完成定义

### 3.1 目标

1. 新增 staged rotation 的 prepare、complete、cancel 三个动作。
2. prepare 成功后，新旧 credential 在有界窗口内都能跨实例认证。
3. complete 后旧 credential 立即失效，新 credential 保持 current。
4. cancel 只能在窗口内执行；取消后新 credential 失效，旧 credential 恢复 current。
5. 超过窗口后旧 credential 即使尚未被物理 cleanup，也必须在认证查询中立即失效。
6. 同一 principal 同时最多一个 current 和一个 retiring credential。
7. 即时 `/rotate` 保持向后兼容；存在 pending rotation 时拒绝再次轮换，避免三 key 状态。
8. principal revoke 一次禁用整个 family 的所有 active credentials。
9. policy、ACL、capabilities、quota、Chat/session、幂等 replay 和 usage 继续按 stable
   principal 工作。
10. API、WebUI、能力发现、双语长青文档和自动化验收完整交付。

### 3.2 非目标

- 不接入具体 secret manager、Kubernetes、Vault 或云厂商 API。
- 不实现 OAuth/OIDC、自动证书轮换或跨集群 principal federation。
- 不允许无限重叠、三个及以上 active credentials 或调用方自定义无上限窗口。
- 不恢复已经超过 deadline 的旧 credential；过期后只能使用 current credential 或重新处理。
- 不猜测 V48 之前历史 credential row 之间无法证明的 family 关系。
- 不改变 raw secret 只展示一次、header-only credential、root/legacy 管理权限或配额算法。

### 3.3 完成定义

- V1 到 V55 空库迁移和 V54 升级迁移通过真实 PostgreSQL 验证。
- 后端 controller/service/repository/Flyway 的 staged lifecycle、并发和失败语义由集成测试覆盖。
- 双实例 HTTP 验证 prepare overlap、complete、cancel、deadline、revoke、policy 和共享 quota。
- WebUI TypeScript、Vitest、生产构建、核心 Mock Playwright 与真实后端 Playwright 通过；
  前端证据只使用 DOM、请求/响应和自动化断言。
- 真实 LLM 验证至少覆盖 overlap 期间旧、新 credential 的模型调用、同 session 连续性、
  complete 后旧 key 不触发 provider、cancel 恢复和最终 revoke。
- `mvn clean compile test-compile`、服务启动、项目文档、锁策略、diff 和密钥检查通过。
- 行为事实同步到中英文长青文档，特性分支同步最新 `origin/main` 后按合并基线完整复验并合回
  `main`。

## 4. 冻结的术语与状态模型

### 4.1 术语

- **principal**：稳定调用身份，拥有 role、ACL、capabilities、policy、expiry 和 quota。
- **current credential**：principal 当前推荐部署的 credential；每个非撤销 principal 至多一个。
- **retiring credential**：staged rotation 中等待完成或自动到期的旧 credential；至多一个。
- **pending rotation**：current 与尚未到期的 retiring credential 同时可认证。
- **immediate rotation**：现有 `/rotate` 行为；旧 credential 在事务提交后立即失效。

### 4.2 状态转换

```text
ACTIVE(v1 current)
  -- prepare(v1, overlap) -->
PENDING(v2 current, v1 retiring until T)
  -- complete(v2) -->
ACTIVE(v2 current, v1 disabled)

PENDING(v2 current, v1 retiring until T)
  -- cancel(v2), only before T -->
ACTIVE(v1 current, v2 disabled)

PENDING(v2 current, v1 retiring until T)
  -- T reached -->
EXPIRED(v2 current, v1 authentication-expired, operation terminal)
  -- scheduled/lazy cleanup -->
ACTIVE(v2 current, v1 disabled)

ACTIVE(vN current)
  -- immediate rotate(vN) -->
ACTIVE(vN+1 current, vN disabled)

ACTIVE or PENDING
  -- revoke(current keyId) -->
REVOKED(all active credentials disabled)
```

取消不会复用已经发出的 credential version。例：v2 被取消后，下一次 prepare 创建 v3；
`next_credential_version` 始终单调递增。

## 5. V55 数据模型

### 5.1 `rag_api_key.retire_at`

新增：

```sql
retire_at TIMESTAMP NULL
```

- active current credential：`enabled=true AND retire_at IS NULL`；
- retiring credential：`enabled=true AND retire_at IS NOT NULL`；
- disabled history：`enabled=false`；`retire_at` 是否为空不再参与 current 判断。

认证必须额外要求：

```sql
retire_at IS NULL OR retire_at > :now
```

因此安全边界不依赖 scheduler 是否准时运行。

current 的唯一真相是 active row 上的 `retire_at IS NULL`，不能再使用
`findFirstByPrincipalIdAndEnabledTrue` 或 `next_credential_version - 1` 推断。取消后可把旧
retiring row 清除 `retire_at` 恢复为 current，同时 `next_credential_version` 保持单调。

### 5.2 唯一约束和索引

V55 删除 V48 的 `uk_rag_api_key_active_principal`，新增：

```sql
CREATE UNIQUE INDEX uk_rag_api_key_current_principal
    ON rag_api_key(principal_id)
    WHERE enabled = TRUE AND retire_at IS NULL;
CREATE UNIQUE INDEX uk_rag_api_key_retiring_principal
    ON rag_api_key(principal_id)
    WHERE enabled = TRUE AND retire_at IS NOT NULL;
```

这在数据库层保证至多一个 active current row 和一个 active retiring row，也就是每个
principal 至多两个可认证 credential。disabled 历史 row 不受这两个索引限制。保留
`UNIQUE (principal_id, credential_version)`、plaintext secret 禁写和 principal FK。

V55 不需要回填历史 row：新增 nullable column 后，V54 中唯一 enabled credential 自然成为
current；disabled history 的 null `retire_at` 不会进入部分唯一索引。

### 5.3 `rag_api_key_rotation`

staged rotation 是可重试的跨系统部署操作，必须有稳定 operation identity，不能仅靠 principal
或 member credential 推断“这一次”轮换。V55 新增 operation ledger：

| 列 | 语义 |
|----|------|
| `rotation_id UUID` | 服务生成的公开 operation ID，主键 |
| `principal_id VARCHAR(64) NOT NULL` | stable principal FK |
| `idempotency_key_hash VARCHAR(64) NOT NULL` | 调用方 Header 的 SHA-256，不保存原值 |
| `request_fingerprint_sha256 VARCHAR(64) NOT NULL` | source credential ID + requested overlap 的规范化指纹 |
| `source_credential_id VARCHAR(64) NOT NULL` | prepare 前的 current credential |
| `target_credential_id VARCHAR(64) NOT NULL` | prepare 创建的新 current credential |
| `overlap_seconds INTEGER NOT NULL` | 调用方请求值 |
| `expires_at TIMESTAMP NOT NULL` | 实际 deadline，已按 principal expiry 截短 |
| `status VARCHAR(20) NOT NULL` | `PENDING` / `COMPLETED` / `CANCELED` / `EXPIRED` / `REVOKED` |
| `created_at TIMESTAMP NOT NULL` / `updated_at TIMESTAMP NOT NULL` / `terminal_at TIMESTAMP` | 生命周期时间 |

约束和索引：

- `UNIQUE (principal_id, idempotency_key_hash)`：同一 principal 内精确 retry；
- `CREATE UNIQUE INDEX ... ON rag_api_key_rotation(principal_id) WHERE status='PENDING'`：数据库
  层再保证每个 principal 至多一个 pending operation；
- `CHECK` 必须同时约束状态集合、状态与 `terminal_at` 的一致性：`PENDING` 只能有
  `terminal_at IS NULL`，其余四个 terminal status 必须有 `terminal_at IS NOT NULL`；
- `CHECK (source_credential_id <> target_credential_id)`，避免 operation ledger 表达自轮换；
- source/target key ID 外键到 `rag_api_key(key_id)`；服务事务还必须校验两行都属于 operation
  的 `principal_id`，source 是 prepare 前的 current，target 是本次新建 credential，并且
  target credential version 大于 source。之后所有响应从关联的 credential row 读取版本，不在
  operation ledger 冗余保存 version，避免两处版本字段漂移；
- hash 必须是 64 位小写十六进制，overlap 范围 1 到 86400 秒；
- status 与 `terminal_at` 一致：PENDING 必须 null，terminal status（包括 EXPIRED）必须非
  null。EXPIRED 表示 deadline 已到且系统已将 retiring credential 禁用；它与 COMPLETED 的
  区别是没有收到有效的 complete 动作。

ledger 不保存 raw secret、Header 原值、完整 request/response、ACL 或 provider信息。terminal
operation 按 `rag.api-key-rotation.operation-retention` 有界保留；保留期就是 prepare精确
replay和 terminal action幂等保证窗口。

### 5.4 Secret 与 replay

prepare 首次成功返回 raw secret；相同 principal/Idempotency-Key/指纹的 retry返回原
`rotationId` 和当前 operation metadata，但 `rawKey=null`、`secretAvailable=false`。
请求指纹采用稳定的规范化输入：`source credential ID` 加上 overlap 字段的
“是否省略”标记及其值。省略 overlap 使用 `DEFAULT` 标记，显式传值使用十进制秒数；
因此配置默认值变化不会把一次省略字段的重试误判为另一请求，也不会把两个不同的 HTTP
请求错误视为相同请求。同一 key 被用于不同 source credential、overlap 或字段省略状态时
返回 `409 IDEMPOTENCY_KEY_REUSED`。

如果首次响应丢失，调用方立即用同一 Idempotency-Key重试，获得 `rotationId` 后执行cancel或
complete；服务永远不重建或重放 raw secret。prepare、complete、cancel、status 的所有成功和
失败响应都使用 `Cache-Control: no-store`，WebUI只在页面内存保存首次secret。

为避免异常路径漏掉敏感响应头，实现必须有一个可复用的 staged endpoint 判定和响应头策略：

- 对 `/api/v1/rag/api-keys/{currentKeyId}/rotations` 及
  `/api/v1/rag/api-keys/rotations/{rotationId}` 和其 `complete`/`cancel` action，
  由认证过滤器在进入认证分支前标记敏感请求并设置 `Cache-Control: no-store`；
- `GlobalExceptionHandler` 根据该请求标记或同一 URI 判定，在参数校验、缺少 header/body、
  业务 `RagException`、数据库/策略异常、404/405 和未预期异常等所有 MVC 错误响应上再次
  保证 `no-store`；
- controller 成功响应也必须显式经过同一策略，不能只依赖某一个正常返回分支；
- 认证过滤器直接短路的缺少/非法/冲突 credential、credential store 不可用和 policy 不可用
  响应必须覆盖 `no-store`，包括 OpenAI 兼容错误 envelope；
- 测试至少断言 prepare 成功和精确 replay、complete/cancel/status 成功、校验失败、业务
  conflict、缺少 credential、非法 credential 以及认证存储不可用路径的响应头。staged URI
  的任意其他错误状态也不得返回可缓存响应。

## 6. HTTP API 契约

所有 staged endpoint 继承现有管理授权：

- 配置 environment root 时只有 root 可以调用；
- legacy 模式下 ADMIN 可以管理，NORMAL 只能操作自己的 current credential；
- raw credential 只在 prepare 首次成功响应中出现。

### 6.1 Prepare

```http
POST /api/v1/rag/api-keys/{currentKeyId}/rotations
Content-Type: application/json
Idempotency-Key: <caller-generated-stable-key>

{
  "overlapSeconds": 900
}
```

请求体可省略；省略时使用 `rag.api-key-rotation.default-overlap`。`Idempotency-Key` 必填并
复用现有长度、字符和重复 Header校验。`overlapSeconds` 必须为正数
且不超过 `max-overlap`，越界返回 `400`。实际 `rotationExpiresAt` 为
`min(now + requestedOverlap, principal.expiresAt)`；因此轮换窗口绝不会扩展 principal 本身的
有效期，响应始终返回服务端实际采用的 deadline。若计算出的 deadline 在事务校验时已不晚于
`now`，返回 `409 PRINCIPAL_NOT_ACTIVE`。

首次成功：`201 Created`；精确 replay：`200 OK` +
`X-RAG-Idempotent-Replay: true`。两者都带 `Cache-Control: no-store`，返回新的
`ApiKeyRotationResponse`：

```json
{
  "rotationId": "c675b6d2-f9b2-47aa-b7c0-cc46cd70e02b",
  "status": "PENDING",
  "keyId": "rag_k_new",
  "principalId": "rag_k_stable_principal",
  "credentialVersion": 2,
  "rawKey": "rag_sk_once_only",
  "currentCredentialActive": true,
  "rotationPending": true,
  "retiringCredentialId": "rag_k_old",
  "retiringCredentialVersion": 1,
  "rotationExpiresAt": "2026-08-27T20:15:00"
}
```

replay返回相同字段，但 `rawKey=null`、`secretAvailable=false`、
`idempotentReplay=true`。

失败：

| 情况 | HTTP / code |
|------|-------------|
| keyId 不存在 | `404` |
| keyId 不是 current | `409 CREDENTIAL_NOT_CURRENT` |
| principal revoked/expired | `409 PRINCIPAL_NOT_ACTIVE` |
| 已有未到期 pending rotation | `409 CREDENTIAL_ROTATION_PENDING` |
| Idempotency-Key 被不同请求复用 | `409 IDEMPOTENCY_KEY_REUSED` |
| overlap 非法 | `400 BAD_REQUEST` |

prepare 开始时若发现 retiring row 已经过期但尚未 cleanup，先在同一 principal 串行化事务中
禁用它并把对应 operation条件推进为 `EXPIRED`，再创建新 rotation。只有
`retire_at > now` 才算 pending；过期 row 不阻断prepare。事务先查询同principal/key的
operation replay，再校验 current，因此首次响应丢失后即使source已变为retiring，精确retry
仍可恢复metadata。

### 6.2 Complete

```http
POST /api/v1/rag/api-keys/rotations/{rotationId}/complete
```

- PENDING：仅在 deadline 之前禁用 source retiring credential，清理 `retire_at` 不作要求，
  operation 标记 `COMPLETED`。
- 已 `COMPLETED`：返回当前 metadata，作为精确幂等 retry。
- 已 `CANCELED` 或 `REVOKED`：返回 `409 CREDENTIAL_ROTATION_NOT_PENDING`，不会影响后来
  operation。
- 已 `EXPIRED` 或 deadline已到但 scheduler 未收敛：本请求先禁用 retiring credential、将
  operation 标记 `EXPIRED`，再返回 `409 CREDENTIAL_ROTATION_EXPIRED`；不能把迟到请求伪装成
  successful complete。

成功：`200 ApiKeyRotationResponse`，永不返回 raw secret。

### 6.3 Cancel

```http
POST /api/v1/rag/api-keys/rotations/{rotationId}/cancel
```

- 只允许在 `rotationExpiresAt` 之前取消。
- 先禁用新 current，再清除旧 credential 的 `retire_at` 并恢复为 current。
- `next_credential_version` 不回退。
- operation标记 `CANCELED`；重复cancel返回相同terminal metadata。
- 已 `COMPLETED` 或 `REVOKED` 返回 `409 CREDENTIAL_ROTATION_NOT_PENDING`。
- 已 `EXPIRED` 返回 `409 CREDENTIAL_ROTATION_EXPIRED`；deadline已到但 scheduler 未收敛时，
  本请求先完成相同的过期收敛。旧 credential 保持失效，不恢复为 current。

成功：`200 ApiKeyRotationResponse`，永不返回 raw secret。

### 6.4 Status

```http
GET /api/v1/rag/api-keys/rotations/{rotationId}
```

返回低敏 operation状态和 current/retiring credential metadata，不返回secret/hash。operation
超过 retention 被物理清理后，status 和使用原 Idempotency-Key 的 prepare replay 都返回
`404`；调用方必须重新发起一轮新的 rotation。
root/legacy ADMIN可查询任意rotation；legacy NORMAL只可查询自身principal的rotation。
响应带 `Cache-Control: no-store`。

### 6.5 Immediate rotate 兼容

现有：

```http
POST /api/v1/rag/api-keys/{currentKeyId}/rotate
```

继续返回 `201` 和一次性新 secret，旧 credential 立即失效。若存在 live pending rotation，返回
`409 CREDENTIAL_ROTATION_PENDING`；调用方必须先 complete 或 cancel，不能生成第三个 active
credential。若 retiring row 已过期但尚未 cleanup，先 lazy disable，再执行即时轮换。

### 6.6 Revoke 与 policy

- `DELETE /api-keys/{currentKeyId}` 撤销 stable principal，并在同一事务禁用 current 和
  retiring credential。重复删除撤销时使用的 current keyId仍为幂等 `204`。
- retiring/stale keyId 不能撤销 family，返回 `409 CREDENTIAL_NOT_CURRENT`。
- family revoke 使用同一个 `now` 写入 principal 和当时所有 active credential 的
  `revoked_at`。principal 已经撤销时，只有
  `credential.revoked_at == principal.revoked_at` 的 keyId 可幂等重试；这包括 pending
  期间被同一次 family revoke 禁用的两个 credential。更早轮换、取消或历史禁用的 keyId
  仍返回 `409 CREDENTIAL_NOT_CURRENT`。
- revoke若存在PENDING operation，在同一principal串行化事务内标记 `REVOKED`；
  complete/cancel重试不会影响已撤销family。
- policy update 仍按 principal row 串行化和 policy version CAS；认证权威联查 principal，
  因此新旧 credential立即看到相同策略。兼容 snapshot 同步更新所有 enabled credentials；
 过期 retiring row 可以在同一事务中顺便 lazy disable，但不能影响 policy 成功语义。
  如果 policy 把 `expires_at` 提前到当前 PENDING operation 的 deadline 之前，必须在同一
  principal 事务中把 operation `expires_at` 和 retiring credential `retire_at` 一起提前，
  不能扩大已有窗口；如果新的 deadline 已到，则同步禁用 retiring、将 operation 推进为
  `EXPIRED`。policy 延长 principal 有效期不得延长已经创建的 rotation deadline。该规则保证
  principal policy 的有效期始终是 credential overlap 的上界，status、认证和 cleanup 看到同一
  个最终 deadline。

## 7. 配置与自动收敛

新增 `RagApiKeyRotationProperties`：

```yaml
rag:
  api-key-rotation:
    default-overlap: ${RAG_API_KEY_ROTATION_DEFAULT_OVERLAP:15m}
    max-overlap: ${RAG_API_KEY_ROTATION_MAX_OVERLAP:1h}
    operation-retention: ${RAG_API_KEY_ROTATION_OPERATION_RETENTION:400d}
    cleanup-interval-ms: ${RAG_API_KEY_ROTATION_CLEANUP_INTERVAL_MS:60000}
    cleanup-batch-size: ${RAG_API_KEY_ROTATION_CLEANUP_BATCH_SIZE:500}
```

约束：

- default/max overlap 必须是整秒 `Duration`，default overlap 大于零且不超过 max；
- max overlap 范围固定为 1 秒到 24 小时；
- operation retention 必须是整天 `Duration`，范围 7 到 3650 天；能力发现中的
  `operationRetentionDays` 直接返回该精确天数，不能对小时/分钟截断；
- cleanup batch 限制为 10 到 5000；
- cleanup 仅物理收敛过期 retiring row和标记过期 operation，认证 deadline 是真正安全边界；
- cleanup同时按有界batch删除超过retention的terminal operation，不删除credential history；
- 多实例 cleanup 使用条件更新和有界 batch，不使用悲观锁、`SKIP LOCKED` 或 advisory lock。
  每轮先有界读取到期的 operation ID；随后对每个 ID 获取该 principal 的管理写资格，在事务
  内重新检查 operation 仍为 `PENDING` 且 `expires_at <= now`，再条件禁用 retiring
  credential并将 operation 推进为 `EXPIRED`。竞争实例若发现状态、deadline或条件更新已变化，
  只记录本项未获胜并继续处理剩余 batch，不能无限重试或阻塞整个清理轮次。
- 所有应用实例和 PostgreSQL 主机必须保持时钟同步；API deadline、认证和 cleanup 使用同一
  服务端时间语义，Client 本地时钟不参与授权判断。

## 8. Service、Repository 与并发语义

所有 prepare/complete/cancel/immediate/revoke/policy 写入先调用
`RagApiPrincipalRepository.acquireManagementWrite(principalId)`，复用现有 principal-row
serialization。

legacy模式的NORMAL self-management按rotation operation的stable principal判断。prepare目标
必须是调用者自己的active current；status/complete/cancel可由同principal的新旧credential
执行。实现上 controller 必须把认证过滤器解析出的 stable principal ID 传入 service；
root 请求显式使用 environment-root 管理身份。NORMAL不能操作其他principal；root/ADMIN
保持现有管理能力。环境root配置后仍只有root可以管理credential。没有数据库 principal
身份的 legacy static credential 不能调用 staged endpoint。

Repository 新增明确查询/条件写入，生产代码不再使用语义含糊的
`findFirstByPrincipalIdAndEnabledTrue`：

- 按 principal + `enabled=true` + `retire_at IS NULL` 读取 active current；
- 读取 live enabled retiring credential，以及用于 cleanup 的 expired retiring credential；
- 条件设置 current 为 retiring；
- 条件禁用一个 credential；
- 条件禁用 principal 全部 active credentials；
- 条件把 retiring 恢复为 current；
- 批量同步 enabled credential compatibility snapshot；
- 有界禁用 `retire_at <= now` 的 rows。
- operation按principal/key replay查询、rotationId查询、PENDING条件状态推进和terminal retention
  清理。
- 到期 operation 的条件过期推进必须同时完成 retiring credential 的条件禁用；scheduler、
  status、complete、cancel 和新的 prepare 共用这条惰性/定时收敛语义。

写入顺序必须满足部分唯一索引：

- prepare：先把旧 current 标记 retiring并 flush，再插入新 current；
- cancel：先禁用新 current，再清除旧 `retire_at`；
- immediate：先禁用旧 current，再插入新 current；
- revoke：先标记 principal revoked，再禁用 family active rows。
- operation ledger与credential变更位于同一事务；不存在“operation成功但credential未切换”或
  相反的中间提交。

任何条件更新行数不符合预期都回滚并返回明确 conflict；不做无界重试。

## 9. DTO、能力发现与 WebUI

### 9.1 DTO

新增：

- `ApiKeyRotationPrepareRequest.overlapSeconds`；
- 新 `ApiKeyRotationResponse`，包含rotation/status/principal/current/retiring/deadline、
  shown-once rawKey、secretAvailable与idempotentReplay；
- `ApiPrincipalResponse.rotationPending`、
  `pendingRotationId`、`retiringCredentialId/version`、`rotationExpiresAt`；
- `ApiKeyResponse.currentCredential`、`retiringCredential`（均为 Boolean）与 `retireAt`，
  供兼容 credential 历史列表使用。
- `IntegrationCapabilitiesResponse.Features.credentialRotation` 及其 DTO。该字段必须是
 追加式能力扩展：保留现有三参数 `Features(provisioning, dataPlane, optional)` 公有构造器，
 由它委托到带新字段的构造器（默认不声明 staged 能力），因此既不破坏现有 Java 源码/二进制
 依赖，也不要求旧 Client 理解新字段；运行时 capability catalog 使用四参数构造器返回完整
  `credentialRotation` 对象。对旧构造器生成的 JSON 可省略该新增对象，对正式 capability
  响应则必须返回它。

`ApiPrincipalResponse.currentCredential*` 始终来自明确的 active current 条件，不会因
两个 enabled rows而随机变化。`ApiKeyResponse.currentCredential` 仅在
`enabled=true AND retire_at IS NULL AND principal active` 时为 true；
`retiringCredential` 仅在 `enabled=true AND retire_at IS NOT NULL AND retire_at > now`
时为 true。只有 `retire_at > now` 的 row 才投影
`rotationPending=true` 和 retiring metadata；过期但尚未 cleanup 的 row 对 Client 隐藏，
因此列表、complete 和新的 prepare 都把它视为已结束的 overlap。

### 9.2 能力发现

`features` 新增 additive `credentialRotation`：

```json
{
  "immediate": true,
  "staged": true,
  "cancel": true,
  "idempotencyKeyRequired": true,
  "defaultOverlapSeconds": 900,
  "maxOverlapSeconds": 3600,
  "replayReturnsSecret": false,
  "operationRetentionDays": 400
}
```

该对象位于 `features.credentialRotation` 顶层。保留现有 protocol/API version；新增字段是向后
兼容的能力扩展。`operationRetentionDays` 是服务端实际配置的整数天数，便于 Client 判断
rotation metadata 可查询时长；`replayReturnsSecret=false` 是不可变的安全合同。

### 9.3 WebUI

API Keys页面把“轮换”改为staged主路径：

1. active 且无 pending 时，打开 prepare modal；
2. 选择overlap并为每次用户提交生成稳定Idempotency-Key；请求超时后的自动/人工retry复用同一
   值，关闭后重新发起才生成新值；
3. prepare 成功后只在 modal 内存展示新 secret、旧 credential ID 和 deadline；
4. 表格在 pending 时显示 current + retiring metadata；
5. pending行按`rotationId`提供“完成轮换”和“取消轮换”动作；
6. immediate rotate 保留为 modal 内明确标注会立即使旧 key 失效的次级动作；
7. refresh 后 raw secret 不可恢复，但 pending metadata可恢复；
8. mutation error 显示后端原因，不把 secret 写入 URL、console、storage 或 toast。

前端不轮询 deadline；列表刷新时以后端当前时间投影为准。deadline 后 cancel 和 complete
都会由后端拒绝并返回明确的过期语义；Client 应读取 status 或列表中的最终 metadata，再修复
新 credential 的部署或重新发起下一次轮换。

## 10. 兼容、部署、回滚与恢复

### 10.1 API 兼容

- 现有 `/rotate` path、状态码和即时旧 key 失效语义不变。
- principal/key response 只新增字段。
- 旧 client 忽略新 capability 和 rotation metadata 即可。
- 新 staged client 必须先读取 capability；缺少 `features.credentialRotation.staged=true`
  时不得调用新 endpoint。

### 10.2 混合版本部署

V54 binary 不理解 `retire_at`，在 pending rotation 中可能把任一 enabled row误认为 current，
并且在 cleanup 前继续认证超过 deadline 的 retiring credential。因此：

1. 部署 V55 前冻结 API credential 管理写入；
2. 先运行 V55 migration，再滚动升级全部实例；
3. 确认所有实例 capability 都支持 staged rotation 后解除冻结；
4. 混合 V54/V55 fleet 期间禁止 prepare；
5. 回滚到 V54 前必须确认数据库中不存在 `enabled=true AND retire_at IS NOT NULL` 的
   credential row，也不存在 `rag_api_key_rotation.status='PENDING'` 的 operation。terminal
   operation history（COMPLETED/CANCELED/EXPIRED/REVOKED）可以保留，不是回滚阻断条件。

V55 schema 对“没有 pending rotation”的 V54 binary 保持写兼容：每个 principal 仍只有一个
enabled current row，V54 create/rotate/revoke 写入也不会违反 V55 的部分唯一索引。迁移不删除
历史数据；应用回滚时保留 V55 column/indexes。若 pending 已存在，必须先用 V55 complete、
cancel 或 revoke 收敛，再回滚 binary。

### 10.3 故障恢复

- 新 secret 分发或健康验证失败且窗口未到：cancel；
- prepare 响应丢失：root/ADMIN查询 principal状态；self caller使用自身principal ID调用
  同一Idempotency-Key重试prepare，取得rotationId且不重放secret，再cancel后重新prepare；
- deadline 已到且新部署失败：旧 secret 不可恢复，修复新 secret部署或由 root立即重新轮换；
- complete 请求超时：可安全重复 complete；
- revoke 请求超时：使用撤销时 current keyId重复 DELETE；
- scheduler 停止：认证仍按 `retire_at` fail closed，后续 prepare 会 lazy cleanup。

## 11. 实施切片

### Slice A：API 与 V55 schema

- 新 DTO/error code；
- V55 credential retire_at、rotation operation ledger、索引与升级测试；
- 配置 properties 和 capability response schema。

退出条件：API 模块与 migration 聚焦测试通过。

### Slice B：credential lifecycle service

- repository 明确 current/retiring 查询与条件写；
- prepare/complete/cancel/immediate/revoke/policy；
- operation replay/status、auth deadline、scheduled/lazy cleanup与retention；
- provisioning replay 和 principal/key response 映射。

退出条件：service 单测和真实 PostgreSQL lifecycle/concurrency 矩阵通过。

### Slice C：Controller 与 HTTP 合同

- staged endpoints、授权、成功/失败全路径 `no-store` 和错误映射；
- 双实例 HTTP lifecycle；
- capability discovery 与脚本 database facts。

退出条件：MockMvc 和 `verify-managed-api-principals.sh` 非 LLM阶段通过。

### Slice D：WebUI

- API types/client；
- principal table pending 状态；
- staged prepare/complete/cancel 和明确 immediate action；
- EN/ZH UI 文案；
- Vitest、Mock Playwright、真实后端 Playwright。

退出条件：前端完整门槛通过，DOM/网络断言证明主路径。

### Slice E：长青文档、真实 LLM 与交付

- 双语 REST API、配置、架构、项目上下文、业务接入、测试、开发者参考、发布清单和 TODO；
- Flyway 全局事实更新为 V55；
- 真实 LLM overlap/complete/cancel/revoke 生命周期；
- 同步 `origin/main`、合并后完整复验、合并推送 main、归档 plan/progress。

## 12. 一次性验收矩阵

### 12.1 后端快速与数据库

1. DTO serialization：新增字段 additive，raw key 不进入 `toString`。
2. properties：default/max/非法 duration/batch 边界。
3. service：
   - prepare create/exact replay/fingerprint conflict；
   - complete、complete retry；
   - cancel、cancel retry、cancel after deadline；
   - legacy 模式下 old/new credential 都可按 stable principal self 执行 status/complete/cancel，
     root 模式下这些管理动作由 environment root 执行，跨 principal 始终被拒绝；
   - pending 时 immediate/second prepare 冲突；
   - revoke disables family；
   - policy updates both enabled snapshots；
   - provisioning replay current projection；
   - auth deadline。
4. PostgreSQL：
   - V54 fixture 升级到 V55；
   - current/retiring partial unique indexes；
   - operation owner/key和single-PENDING唯一约束；
   - prepare 两 key认证；
   - principal expiry截短 overlap，deadline 后旧 key不认证；
   - complete/cancel/revoke；
   - cancel 后 version 不复用；
   - 并发 prepare只有一个 winner；
   - prepare 与 revoke/policy 串行化；
   - policy 缩短 expiry 会同步 clamp pending deadline，policy 延长不会延长既有 deadline；
   - quota 在新旧 key下共享；
   - cleanup batch、到期 operation 的 EXPIRED 状态和竞争收敛；
   - terminal operation retention；
   - plaintext secret 仍为零。
5. `mvn clean compile test-compile` 和 `mvn test`。
6. postgresql profile 实际启动和健康检查。

### 12.2 HTTP 与双实例

1. root create -> prepare on A -> old/new `/auth/me` on B 均 `200`。
2. 两 key identity 的 principal/policy/capabilities/ACL 相同，credential version 不同。
3. old/new交替请求共用同一 quota bucket。
4. 同Idempotency-Key prepare replay为`200`、同rotationId、raw null；不同overlap为`409`。
5. complete on B -> old on A `401`、new `200`；complete retry仍`200 COMPLETED`。
6. 第二 principal prepare -> cancel -> new `401`、old恢复 `200`；cancel retry仍
   `200 CANCELED`。
7. 第三 principal短窗口 prepare -> deadline -> old `401`，不依赖 cleanup。
8. pending revoke -> old/new跨实例都 `401`，operation为`REVOKED`。
9. staged endpoint 的成功、MVC 异常和认证过滤器短路响应均为 `no-store`；列表/capability
   不含 raw/hash。
10. stale、live pending、expired retiring、principal expiry、权限和非法 overlap错误码精确；
   legacy NORMAL用同 principal旧/新 credential认证执行self complete/cancel成功，跨
   principal拒绝。
11. database facts：V55、每 principal current <=1、retiring <=1、PENDING operation<=1、
    plaintext=0。

### 12.3 前端

1. `npm run typecheck`。
2. `npm run test:run`，聚焦新增 rotation 组件状态与 API payload。
3. `npm run build`、`npm run check:alignment`。
4. Mock Playwright：
   - root unlock；
   - prepare 显示 shown-once secret 和 deadline；
   - prepare网络retry复用同一Idempotency-Key且replay不显示secret；
   - close 后 secret消失；
   - pending 行可完成和取消；
   - immediate action请求旧 endpoint；
   - secret 不进入 URL、storage、console。
5. 真实后端 Playwright复用同一组 DOM/网络断言，不使用截图。

### 12.4 真实 LLM

Mock、PostgreSQL 和双实例 HTTP 全部通过后执行：

1. 创建 read-only principal并 prepare；
2. old credential 发起一次 native JSON Chat；
3. new credential在同 session继续 native JSON Chat并读取 history；
4. complete 后 old credential请求 `401`，provider counter不增加；
5. new credential执行 native SSE 和 OpenAI-compatible JSON/SSE；
6. 另一 principal prepare后用 new credential调用，再 cancel，确认 old恢复并能继续 session，
   new credential `401` 且不触发 provider；
7. pending family revoke 后 old/new都 `401`；
8. 持续观察后端日志，记录 provider调用数、replay零增量、principal/session连续性和无 secret
   泄漏证据。

不人为限制必要的真实调用次数；每次调用服务一个不同合同场景，不用重复相同 prompt代替覆盖。

### 12.5 治理与 Git

- `./scripts/verify-no-pessimistic-locks.sh`
- `./scripts/verify-project-docs.sh`
- Shell/Python语法检查和相关一键门禁
- `git diff --check`
- added-line secret scan
- `git status` 与工作区全部修改核对
- fetch/merge 最新 `origin/main` 后重跑完整矩阵

## 13. 固定范围规划审查

连续三轮分别检查：

1. 需求闭环、自包含、默认决策、非目标和 Client 可操作性；
2. V55 schema、事务顺序、并发、安全、兼容和恢复可实施性；
3. 文件切片、一次性验收、真实 LLM、文档、部署、回滚和 Git 交付。

发现会影响正确性、安全、兼容性、数据一致性或可实施性的实质问题时立即修改并把计数归零。
措辞、格式和实施中自然暴露的行号漂移不触发重置。达到连续 `3/3` 后把最终记录一次性写入
progress，再开始生产代码修改。

## 14. 预计修改范围

### API

- `spring-ai-rag-api/.../dto/ApiKeyRotationPrepareRequest.java`
- `ApiKeyCreatedResponse.java`
- `ApiPrincipalResponse.java`
- `ApiKeyResponse.java`
- `IntegrationCapabilitiesResponse.java`
- `ErrorCode.java`

### Core

- V55 migration
- `RagApiPrincipal`、`RagApiKey`
- `RagApiKeyRepository`
- `ApiKeyManagementService`
- `ApiKeyController`
- `RagProperties` 与新 rotation properties
- `IntegrationCapabilityCatalog`
- controller/service/config/PostgreSQL integration tests

### WebUI

- `src/api/apikeys.ts`
- `src/pages/ApiKeys.tsx`、CSS、Vitest
- EN/ZH locale
- `e2e/api-key-mvp.spec.ts`
- `e2e/api-key-real.spec.ts`

### Scripts 与文档

- `scripts/verify-managed-api-principals.sh`
- 必要时更新 business Client 通用合同脚本
- 双语 configuration/rest-api/architecture/project-context/business-client-integration/
  testing-guide/developer-reference/release-checklist/TODO/index
- `AGENTS.md`、`verify-project-docs.sh` 中最新 Flyway事实

## 15. 风险清单

| 风险 | 控制 |
|------|------|
| 两个 enabled row让旧查询随机取 current | current 查询显式要求 `retire_at IS NULL`；移除生产中的模糊查询 |
| deadline依赖 scheduler导致旧 key超时仍有效 | 认证 SQL直接检查 `retire_at > now`；过期 row在投影和写操作中视为已结束 |
| 并发 prepare产生三个 key | principal row串行化 + 两个 partial unique index |
| cancel复用 version或误恢复过期 secret | next version不回退；deadline后 cancel明确拒绝 |
| overlap超过 principal expiry | actual deadline取两者最小值并在响应中明确返回 |
| revoke只禁用 current | family级条件更新禁用全部 active rows |
| 双 credential绕过 quota | rate-limit key保持 stable principal |
| policy在 overlap期间分叉 | 认证联查 principal；兼容 snapshot同步所有 enabled rows |
| mixed V54/V55误解双 enabled row | 升级期间冻结管理写；全实例升级后才 prepare；回滚前清空 pending |
| prepare响应丢失导致 secret不可恢复 | 同Idempotency-Key精确replay metadata并取得rotationId；随后cancel，不重放secret |
| 旧complete/cancel retry误操作后来rotation | action严格绑定immutable rotationId；ledger记录terminal状态 |
| WebUI泄漏 shown-once secret | 仅组件内存；no-store；URL/storage/console自动化断言 |
| review阶段发散 | 规划先冻结测试矩阵；实现只修本轮正确性、安全、兼容和一致性问题 |
