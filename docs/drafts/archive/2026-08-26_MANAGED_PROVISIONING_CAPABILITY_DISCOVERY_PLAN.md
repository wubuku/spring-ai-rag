# 托管调用方幂等 provisioning 与运行时能力发现实施规划

> **状态**：已实施、验收并合入 `main`；本文仅供历史追溯
>
> **规划日期**：2026-08-26
>
> **规划基线**：`main` / `origin/main` @ `0abc667e`；Spring Boot `3.5.16`；
> Spring AI `1.1.8`；Java `21`；Flyway `V1-V49`
>
> **规划工作区**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
>
> 本文是当前仓库的单语实施规划。它只描述通用 RAG 服务能力，不依赖任何外部项目的名称、
> 私有协议、领域模型或部署背景。稳定行为在实施和验收完成后提升到双语长青文档。

## 1. 执行摘要

本轮补齐托管 API principal 控制面的两个直接生产缺口：

1. **principal provisioning 幂等**：为 `POST /api/v1/rag/api-keys` 增加可选
   `Idempotency-Key`。网络超时或调用方丢失响应后，调用方可以安全重试，而不会重复创建
   principal。系统只持久化幂等键哈希、规范化请求指纹和结果元数据，绝不持久化或重放
   raw credential。
2. **运行时能力发现**：增加一个认证的、只读的、版本化的 capability discovery endpoint，
   让后端调用方能够从部署中的实例发现实际可用的协议、授权、Collection、结构化记录、
   异步派生和 binding 约束，而不必把 OpenAPI、Git SHA、离线清单和人工约定拼接成一套
   隐含判断。

这两个能力属于同一条外部后端集成控制面：前者保证 operator 自动化创建稳定、可对账的
principal，后者保证调用方在启动或升级时能以机器可读合同确认服务能力。它们不改变现有
JSON Record、Chat、Collection ACL、embedding job 或 tool calling 的业务语义。

### 1.1 本轮交付结果

- `POST /api/v1/rag/api-keys` 支持可选单值 `Idempotency-Key`；
- 首次成功返回 `201`、raw credential 一次性展示、`Cache-Control: no-store`；
- 同一 owner、同一幂等键、同一规范化请求的精确重试返回稳定元数据，不返回
  `rawKey`，并带 `X-RAG-Idempotent-Replay: true`；
- 同一幂等键对应不同请求返回 `409 IDEMPOTENCY_KEY_REUSED`；
- 同一幂等键的并发首请求由数据库唯一约束协调；竞争请求在有界次数内重新读取胜者，
  不对外伪造一个没有持久事实支撑的 `IN_PROGRESS` 状态；
- 幂等账本以 requester principal 归属，root 使用固定 environment-root owner；不同
  requester 不能通过碰撞的幂等键读取或影响对方的 provisioning 结果；
- capability discovery endpoint 返回稳定的协议版本、实际 capability、限制、失败语义
  和 binding 合同，不返回 secret、hash、数据库细节、provider 凭据或业务数据；
- WebUI 不执行 provisioning 幂等流程，也不接收或保存新的幂等键；管理界面继续保持
  raw credential 只在创建/轮换成功响应中展示一次。WebUI 的 TypeScript 不因新服务端
  字段而破坏构建；
- 双语长青文档补充 provisioning retry 和 capability discovery 的公共契约，TODO、
  REST API、测试和开发者参考同步更新。

### 1.2 关键安全结论

V48 已加入数据库约束 `rag_api_key.api_key IS NULL`。因此本轮禁止以下方案：

- 在幂等表保存 raw credential；
- 对 raw credential 做可逆加密后保存；
- 从 root secret、幂等键或请求指纹确定性推导 raw credential；
- replay 时重新生成一个不同 credential 并声称它是原始结果；
- 通过日志、响应 snapshot、测试摘要或 capability 文档泄露 secret。

原始 credential 丢失后的恢复路径是：从首次响应之外无法取回 secret；operator 使用稳定
`principalId` 或创建响应中的标识调用既有 rotate endpoint，重新在一次响应边界内保存新
credential。幂等 replay 明确告诉调用方 secret 不可用，而不是伪造可恢复的 secret。

## 2. 当前基线与问题定义

### 2.1 已核对的认证与 provisioning 事实

当前认证链为：

```text
ApiKeyAuthFilter
  -> environment root / database credential
  -> immutable AuthenticatedApiPrincipal
  -> central capability filter
  -> controller
```

当前托管创建路径：

```text
POST /api/v1/rag/api-keys
  -> require environment root (root mode)
  -> resolve allowedCollectionKeys to internal IDs
  -> ApiKeyManagementService.generateManagedKey(...)
  -> create principal + credential hash in one transaction
  -> 201 + rawKey
```

已核对的代码与文档事实：

- `ApiKeyController` 目前没有读取 `Idempotency-Key`；
- `ApiKeyManagementService.createPrincipal` 每次都会随机生成新的 principal/key ID 和
  raw credential；
- `RagApiPrincipal` / `RagApiKey` 只保存 credential hash，不保存 raw credential；
- V48 的 `ck_rag_api_key_plaintext_forbidden` 强制 `api_key IS NULL`；
- `IdempotencyKeyValidator` 已为 Chat、Document relocation 等公共语义提供单值、可见
  ASCII、1-255 字符和 SHA-256 规则；
- `ChatTurnOperationService` 已有 `IDEMPOTENCY_KEY_REUSED`、`IDEMPOTENCY_OPERATION_IN_PROGRESS`
  和 bounded retry-after 语义，但其 operation 表是 Chat 专用，不应被 provisioning
  隐式复用；
- `ApiCapabilityFilter` 将 API key management 和 identity 路径排除在数据面 capability
  enforcement 之外，root 是管理端点的当前授权边界；
- `/api/v1/rag/auth/me` 已能返回当前数据库 principal 的 role、capability、access mode
  和 allow-list，但它不是完整协议能力目录；
- `business-client-binding-preflight.sh` 已在客户端侧验证一部分部署能力，但当前还需
  结合 OpenAPI、Git SHA 和固定脚本知识，无法仅从运行实例得到版本化能力声明；
- WebUI 的 Axios client 对网络/5xx 有自动 retry；管理界面的 create mutation 当前没有
  发送幂等键，无法安全区分同一表单提交的自动重试。

近距离事实入口：

- [REST API：认证和 API Key 管理](../../rest-api-zh-CN.md#认证)
- [业务服务接入指南](../../business-client-integration-zh-CN.md)
- [项目上下文：安全与 Collection ACL](../../project-context-zh-CN.md#安全与-collection-acl)
- [交付工作流](../../delivery-workflow-zh-CN.md)
- `spring-ai-rag-core/src/main/java/com/springairag/core/controller/ApiKeyController.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/service/ApiKeyManagementService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/IdempotencyKeyValidator.java`
- `spring-ai-rag-core/src/main/resources/db/migration/V48__managed_api_principals_and_shared_quota.sql`
- `spring-ai-rag-core/src/main/resources/db/migration/V49__operation_scoped_api_capabilities.sql`

### 2.2 问题场景

没有幂等键时，以下时序无法安全处理：

```text
operator/client -> POST create
server          -> principal + credential committed
network         X response lost
operator/client -> retry POST create
server          -> creates a second principal and second credential
```

调用方既不能凭名称判断哪一个 principal 是本次结果，也无法重新取得第一条 credential。
结果可能是 orphan principal、错误 binding 或不必要的权限对象。

没有机器可读 capability discovery 时，调用方必须自行假设：

- 当前服务是否支持 operation-scoped capability；
- 当前服务是否支持 JSON Record CAS、tombstone/restore、`payloadContains`；
- ASYNC embedding 的 mutation/readiness 语义；
- capability profile、Collection key 限制和 binding preflight 约束；
- 服务端协议版本和需要重新执行的合同测试范围。

这些假设会随部署版本漂移，且调用方可能在能力不满足时继续发送数据。

## 3. 目标、非目标与冻结决策

### 3.1 目标

1. 为 root-managed provisioning 提供可选的 HTTP 幂等键，保持没有该 header 的旧调用方
   继续得到既有 `201 + rawKey` 语义。
2. 以服务端根据认证上下文生成的 `requester_owner + idempotency_key_hash` 唯一定位一个
   provisioning operation：root、数据库 principal、legacy static 和 auth-disabled
   分别使用稳定的服务端 owner 映射。
3. 对请求使用服务端有效语义生成 canonical fingerprint，而不是直接使用 JSON 文本。
4. 在数据库约束和条件写入保护下支持并发首请求；不得使用 `FOR UPDATE`、`SKIP LOCKED`
   或 PostgreSQL advisory lock。
5. 精确重放只返回结果 metadata，显式标记 `secretAvailable=false` 和
   `idempotentReplay=true`，并提供 `principalId`、当前 credential ID/version 和恢复
   所需的稳定标识。
6. 以版本化 JSON 提供认证 capability discovery，且输出只包含低敏、可审计的能力和限制。
7. 让客户端可以在服务启动、部署 binding 和升级时依据 capability endpoint fail closed。
8. 通过 MockMvc、PostgreSQL 并发集成、真实 HTTP 合同和 WebUI type/build 证明契约。

### 3.2 非目标

- 不持久化、加密或确定性推导 raw credential；
- 不改变 credential rotation、revoke、policy CAS、ACL、quota、Chat 或 JSON Record 语义；
- 不把历史无幂等创建自动合并到新账本；历史 orphan principal 由 operator 对账；
- 不为普通业务 principal 开放创建、列出或修改其他 principal；当前管理授权边界保持不变；
- 不把 capability endpoint 变成 OpenAPI 的替代品，不返回完整 schema、SQL、数据库版本、
  provider 配置、模型 credential、业务数据或部署 secret；
- 不实现 OAuth/OIDC、tenant hierarchy、secret manager、收费/计费、distributed lock 或
  跨服务 provisioning saga；
- 不让 capability endpoint 根据调用方提供的 principal ID 读取别人的能力；
- 不要求本轮真实 Chat/LLM 调用来证明未触及的 Chat 行为；真实 provider 门禁只在既有
  全量 readiness gate 要求且环境可用时执行；
- 不在 WebUI 中提供 capability endpoint 的管理面板；该 endpoint 是后端集成契约。

### 3.3 推荐默认与可逆边界

| 事项 | 冻结默认 | 理由与可逆边界 |
|---|---|---|
| 幂等 header | 可选 `Idempotency-Key` | 旧调用方兼容；未来可在新 API 版本要求必填 |
| 幂等键规则 | 复用 `IdempotencyKeyValidator` | 避免不同 endpoint 出现不一致的 header 语义 |
| 幂等 owner | 按 3.4 的服务端身份映射生成 stable owner | rotation 不改变 owner；未来 federation 可增加 owner 类型 |
| replay 状态码 | 首次 `201`；精确 replay `200` | 反映未创建新资源；未来可新增 response header 不破坏 body |
| replay secret | 永不返回；`secretAvailable=false` | V48 明文禁存；只能 rotate 恢复 |
| 并发首请求 | 数据库唯一约束 + 有界重试；无法确认结果时 `503` | 不持久化未完成 placeholder，避免崩溃后留下无法恢复的“进行中”资源 |
| 账本 retention | 默认 400 天，配置范围 7-3650 天 | 防止无限增长；幂等保证只覆盖账本保留期，调用方必须在该期限内重试 |
| cleanup 方式 | 定时/启动时批量删除已完成且超过 retention 的条目 | 不影响当前请求；批量大小有界，失败可重试 |
| discovery authentication | 鉴权启用或 root mode 时需要现有 API credential；auth-disabled 仅保留本地开发兼容语义 | capability 与当前 principal 的可用操作有关；生产部署不应依赖 auth-disabled |
| discovery caching | `Cache-Control: no-store` | capability 可能随部署配置和 policy 变化，调用方应按启动/升级重新读取 |
| protocol version | 服务端维护整数 major/minor 字符串，首版 `1.0` | 不把 Git SHA 当协议版本；minor 向后兼容，major 需要重新合同验收 |

### 3.4 Provisioning owner 与语义规范化

幂等账本的 owner 由服务端认证上下文决定，绝不由请求体、header 或 query 参数提供。
首版 owner 映射固定如下：

| 当前请求身份 | 幂等 owner |
|---|---|
| `ENVIRONMENT_ROOT` | `root:environment-root` |
| `DATABASE_API_KEY` | `db:{principalId}`，使用认证快照中的 stable principal ID |
| `LEGACY_STATIC` | `legacy:static` |
| auth-disabled 本地开发请求 | `local:auth-disabled` |

legacy/static 和 auth-disabled 只是兼容部署投影：同一部署中的调用方共享对应 owner，
不能借此获得生产环境的身份隔离；启用 root 或数据库 principal 后，owner 由新的认证身份
决定。owner 字符串只在服务端生成和比较，不能被调用方覆盖。

canonical fingerprint 必须基于 controller/service 已完成授权解析后的**有效语义**构造：

- `allowedCollectionKeys` 只作为输入解析，fingerprint 只使用排序、去重后的内部
  `allowedCollectionIds`；同一 Collection 通过 key 或 numeric ID 表达时必须命中同一账本；
- `capabilities` 使用 `ApiCapabilitySupport` 归一化后的有效列表；
- `null` 与“无限制”在允许的字段上采用同一 canonical 表示；
- 字段排序、数组排序和 UTF-8 JSON 编码稳定；不会把原始 key header、原始 JSON 字段顺序、
  URL、Authorization、provider 配置或客户端未生效字段纳入 fingerprint。

## 4. 对外 HTTP 契约

### 4.1 Create API 的幂等请求

请求仍为：

```http
POST /api/v1/rag/api-keys
Idempotency-Key: provision-2026-08-26-0001
X-API-Key: <environment-root>
Content-Type: application/json
```

`Idempotency-Key`：

- 至多一个 header 值；
- trim 后 1-255 个可见 ASCII 字符；
- 不允许逗号、空白或控制字符；
- header 原文不进入数据库；数据库只保存 SHA-256 小写十六进制摘要；
- 同一个 raw header 经现有 OWS 规范化后才参与 hash。

请求指纹使用 controller 完成 Collection key 解析、默认 capability 归一化和 delegated
ACL 解析后的有效语义。canonical JSON 必须只使用解析后的内部 Collection ID，不使用原始
`allowedCollectionKeys` 表达；必须稳定排序字段和 Collection ID，至少包含：

```json
{
  "name": "Indexer Service",
  "expiresAt": "2027-12-31T23:59:00",
  "allowedCollectionIds": [3, 7],
  "capabilities": ["RAG_READ"],
  "requestsPerMinute": 75,
  "role": "NORMAL"
}
```

不得把 raw credential、header 原文、URL、Authorization、provider 配置、日志字段或任意
未持久化的客户端 metadata 放入 fingerprint。指纹计算采用 canonical UTF-8 JSON 的
SHA-256；同一有效请求字段顺序不同仍视为同一请求。

### 4.2 Create API 响应

首次成功保持现有响应，并额外返回：

```json
{
  "keyId": "rag_k_abc",
  "principalId": "rag_k_abc",
  "credentialVersion": 1,
  "policyVersion": 1,
  "rawKey": "rag_sk_<64 hex>",
  "secretAvailable": true,
  "idempotentReplay": false,
  "currentCredentialActive": true,
  "name": "Indexer Service",
  "warning": "Save this key now — it will not be shown again."
}
```

约束：

- `201`；
- `Cache-Control: no-store`；
- `secretAvailable=true`，`idempotentReplay=false`；
- `rawKey` 仅首个成功响应存在；
- 旧客户端忽略新增字段仍可工作。

精确 replay：

```json
{
  "keyId": "rag_k_abc",
  "principalId": "rag_k_abc",
  "credentialVersion": 1,
  "policyVersion": 1,
  "rawKey": null,
  "secretAvailable": false,
  "idempotentReplay": true,
  "currentCredentialActive": true,
  "name": "Indexer Service",
  "warning": "The principal already exists. The raw credential cannot be shown again; rotate the current credential if the original secret was not saved."
}
```

约束：

- `200`；
- `X-RAG-Idempotent-Replay: true`；
- `Cache-Control: no-store`；
- `rawKey` 必须显式 JSON `null`，不能被全局 null inclusion 配置省略；
- `secretAvailable=false`，`idempotentReplay=true`；
- replay 的 `keyId` 和 `credentialVersion` 表示当前仍可用 credential 的 public ID/version；
  账本中的首次 credential ID/version 仅用于审计，不应在 rotation 后继续被误报为 current；
  不返回其他 principal 数据；
- 如当前 principal 已在 replay 前被 revoked/expired，仍返回账本记录的资源 metadata，但响应
  设置 `currentCredentialActive=false`，并把 `keyId`、`credentialVersion` 置为 `null`；
  调用方必须转入 operator recovery，不得把 replay 当作可用 credential。
- replay 只在对应账本记录仍处于 retention 期内时成立。记录被 cleanup 删除后，再次使用同一
  owner/key 会按新的 provisioning 请求处理，可能创建新的 principal；调用方不得把该 key
  当作永久去重标识，超出 retention 的重试应先由 operator 对账或改用新的 key。

本轮不改变现有 ID 兼容约定：新建 principal 的 `principalId` 继续等于首个 credential
的 `keyId`（当前生成格式均为 `rag_k_...`）；credential rotation 只生成新的 `keyId`，
并保持 `principalId` 稳定。只有未来单独设计并迁移 principal ID namespace 时，才允许
引入 `rag_p_...` 等新格式。

### 4.3 冲突和失败

| 条件 | HTTP | error code/header | 语义 |
|---|---:|---|---|
| key 的首请求并发提交 | 由服务端有界等待/重试后返回 `200` replay 或 `201` 首次结果；无法确认时 `503` | 不暴露没有 durable 状态依据的假 `IN_PROGRESS` |
| 同 owner/key 对应不同 canonical fingerprint | 409 | `IDEMPOTENCY_KEY_REUSED` | 禁止把同一 key 重新用于另一资源 |
| key 格式非法/多值 | 400 | `IDEMPOTENCY_KEY_INVALID` | 与 Chat 等 endpoint 一致 |
| 幂等功能关闭但请求携带 key | 503 | `API_KEY_PROVISIONING_IDEMPOTENCY_DISABLED` | fail closed，不退回非幂等创建 |
| service 事务失败且未提交结果 | 原有错误 | 原有错误码 | 失败记录可重试，不能留下“成功但无结果”的账本 |
| 数据库故障读取/写入账本 | 503/500 | `SERVICE_UNAVAILABLE` 或 `DATABASE_ERROR` | 不创建第二个 principal，不绕过幂等 |

本轮复用已有 `IDEMPOTENCY_KEY_INVALID`、`IDEMPOTENCY_KEY_REUSED`；provisioning 关闭需要新增
专用 `API_KEY_PROVISIONING_IDEMPOTENCY_DISABLED`，不能复用标题明确为 Chat 的
`IDEMPOTENCY_DISABLED`；并发竞争的内部重试次数默认 3，范围 1-8，每次重试采用有限退避，
不能由客户端输入控制。已有 `IDEMPOTENCY_OPERATION_IN_PROGRESS` 继续服务 Chat 等已有
operation 语义，本轮 provisioning 不使用它。

### 4.4 Capability discovery endpoint

新增：

```text
GET /api/v1/rag/integration-capabilities
```

认证：

- 在启用鉴权或 root mode 时需要现有 API credential；auth-disabled 模式沿用当前服务的
  公开本地开发语义，并将 principal 投影为 `LOCAL_AUTH_DISABLED`，生产部署不应依赖该模式；
- root、数据库 principal、legacy static（在 legacy 模式）的身份语义沿用当前 `/auth/me`；
- auth-disabled 模式不设置认证 request attribute，capability endpoint 应显式构造
  `principalType=LOCAL_AUTH_DISABLED`、`principalRole=null`、`collectionAccessMode=UNRESTRICTED`
  的开发投影；该投影只继承既有未鉴权 API 的本地语义，不表示生产调用方可以省略认证；
- 数据库 NORMAL principal 只能看到其自身有效 capability 与“当前请求可使用”的合同；
- endpoint 不接受 principal ID、Collection ID 或 Collection key 查询参数来切换观察对象。

首版响应：

```json
{
  "protocol": {
    "name": "spring-ai-rag-integration",
    "version": "1.0",
    "apiVersion": "1.0.0"
  },
  "principal": {
    "principalType": "DATABASE_API_KEY",
    "principalRole": "NORMAL",
    "capabilities": ["RAG_READ", "RAG_WRITE"],
    "collectionAccessMode": "RESTRICTED",
    "allowedCollectionKeys": ["customer-42:records:v1"]
  },
  "features": {
    "provisioning": {
      "idempotencyKey": true,
      "replayReturnsSecret": false,
      "rawCredentialShownOnce": true
    },
    "dataPlane": {
      "collectionKey": true,
      "jsonRecords": {
        "upsert": true,
        "search": true,
        "payloadContains": true,
        "revisionCas": true,
        "exactReplay": true,
        "tombstoneRestore": true
      },
      "embedding": {
        "asyncPolicy": true,
        "readinessEndpoint": true
      },
      "bindingPreflight": true
    },
    "optional": {
      "documentSyncRuns": false,
      "openAiCompatibility": false
    }
  },
  "limits": {
    "maxCollectionKeysPerPrincipal": 100,
    "collectionKeyMaxLength": 128,
    "sourceNamespaceMaxLength": 128,
    "externalIdMaxLength": 255,
    "sourceRevisionMaxLength": 255
  }
}
```

`principal` projection 固定如下：

| 身份 | `principalType` | `principalRole` | `capabilities` | Collection access |
|---|---|---|---|---|
| environment root | `ENVIRONMENT_ROOT` | `null` | `RAG_READ`, `RAG_WRITE`, `API_KEY_MANAGE` | `UNRESTRICTED`，allow-list 为 `null` |
| database API key | `DATABASE_API_KEY` | 实际 role | 认证快照中的 effective capability | 按实际 policy；restricted 必须返回完整 allow-list |
| legacy static key | `LEGACY_STATIC` | `null` | `RAG_READ`, `RAG_WRITE` | `UNRESTRICTED`，allow-list 为 `null` |
| auth-disabled 本地请求 | `LOCAL_AUTH_DISABLED` | `null` | `RAG_READ`, `RAG_WRITE` | `UNRESTRICTED`，allow-list 为 `null` |

`bindingPreflight=true` 的含义是：该服务实例提供了预检脚本所需的服务端合同字段和
只读探测路径；它不表示服务端执行或拥有调用方的预检脚本。调用方仍需在自己的部署流程
中执行预检。

冻结规则：

- 顶层字段 `protocol`、`principal`、`features`、`limits` 是首版必需字段；
- boolean 能力只描述已经由当前仓库实现和测试证明的行为；
- `allowedCollectionKeys=null` 表示 unrestricted，restricted principal 必须返回完整
  allow-list；无法完整解析策略时 endpoint 返回 `503`，不能返回部分数据；
- capability endpoint 的 `principal` 不包含 credential ID、credential version、policy
  version、hash、raw secret；如调用方需要 binding 版本，继续使用 `/auth/me`；
- 不返回 active model/provider、database host/schema、Flyway 表、部署路径、环境变量、
  LLM API key、工具 endpoint allow-list 或业务数据；
- 列表顺序稳定；能力名和 feature key 只能新增，删除/改语义必须提升 major；
- response `Cache-Control: no-store`；
- OpenAPI 必须描述 response schema、认证、503 和字段语义。

### 4.5 capability endpoint 与 `/auth/me` 的职责

两者不合并：

- `/auth/me`：当前 credential 的身份、版本、实际 policy 和 Collection allow-list；
- `/integration-capabilities`：该实例对集成调用方承诺的协议能力、功能开关和限制；
- 调用方先读取 capability contract，再读取 `/auth/me` 做身份/权限 binding，最后运行
  Collection by-key probe 或既有 preflight。

这样 capability contract 不需要重复暴露 credential 生命周期字段，也不把 `/auth/me` 变成
不可演进的大型 feature catalog。

## 5. 数据模型与并发设计

### 5.1 Flyway V50

新增 `rag_api_provisioning_operation`，建议字段：

| 字段 | 类型/约束 | 用途 |
|---|---|---|
| `id` | `BIGINT` identity primary key | 内部行标识 |
| `owner_id` | `VARCHAR(128)` not null | 3.4 定义的 root、database、legacy/static 或 auth-disabled stable owner |
| `idempotency_key_hash` | `CHAR(64)` not null | SHA-256，不保存 header 原文 |
| `request_fingerprint_sha256` | `CHAR(64)` not null | 有效请求 canonical fingerprint |
| `principal_id` | `VARCHAR(64)` not null | 创建结果 stable principal |
| `credential_id` | `VARCHAR(64)` not null | 首次创建结果 credential ID |
| `credential_version` | `INTEGER` not null | 首次结果 credential version |
| `created_at` | `TIMESTAMP` not null | operation 创建时间 |
| `updated_at` | `TIMESTAMP` not null | 状态/审计时间 |
| `completed_at` | `TIMESTAMP` nullable | 成功结果可 replay 的时间 |

本轮只登记成功且已提交的 provisioning 结果；未成功的事务由数据库回滚，不写失败占位行。
这使 retry 在首个事务失败后仍能重新尝试，而不会把 transient failure 永久封死。

约束：

- `UNIQUE(owner_id, idempotency_key_hash)`；
- owner/key/fingerprint/principal/credential 长度和非空检查；
- `credential_version > 0`；
- `completed_at IS NOT NULL` 的成功记录才能被视为 replay；
- `principal_id`、`credential_id` 使用现有 key 格式长度约束；
- 外键可选：不对 `rag_api_principal` 使用默认 `ON DELETE` 级联，以免 cleanup 或历史
  运维删除破坏 provisioning ledger；replay 对缺失结果返回 `503`，而不是创建第二资源。

不保存 canonical JSON 本文；只保存 fingerprint hash。审计需要时由无敏日志记录 owner、
hash 前缀和资源标识，不能记录 header、请求体或 secret。

### 5.2 事务顺序

首个 keyed create：

```text
1. Controller 验证 header、解析 collection keys、归一化 capabilities
2. 计算 owner、canonical request、fingerprint
3. 读取 owner+key 的已有 operation
4. 已有 operation:
   a. fingerprint 不同 -> KEY_REUSED
   b. 成功 -> replay metadata
   c. 未完成（本轮不会持久化此状态） -> 由数据库提交结果后重读
5. 生成随机 principal ID、credential ID、raw credential
6. 同一个短事务写 principal + credential hash + provisioning ledger（ledger 最后写入）
7. 唯一约束竞争失败 -> 当前事务整体回滚；在事务外按有限退避重新读取 operation，并按
   4a-4b 处理
8. commit 后返回 201 + raw credential
```

controller 解析得到的有效 request 必须在 service 内再次形成 canonical request，防止未来
新增入口绕过 controller 时产生不同指纹；service 接收的内部
`ManagedProvisioningRequest` 必须携带已解析的 Collection ID，不依赖再次解释外部 key。
service 不接受 raw header，只接受 owner、经过验证的 idempotency key hash 和有效请求字段。

### 5.3 并发与事务边界

本轮不引入独立 `IN_PROGRESS` 持久状态，也不在数据库中创建未完成 placeholder。由于
principal、credential 和 ledger 在同一事务内提交：

- 同一 key 的第二请求如果在首事务提交前读不到 ledger，可能与首请求并行进入；
- 两个事务各自生成资源，只有一个能赢得 `UNIQUE(owner,keyHash)`；PostgreSQL 会在唯一
  索引竞争上等待，不需要应用显式锁；
- 失败事务必须整体回滚 principal/credential，胜者 ledger 成为唯一事实；
- 失败事务捕获唯一约束冲突后必须退出当前事务，再在新的只读事务中读取并返回 replay；
- 若数据库隔离级别/异常无法安全判断唯一竞争，返回 `503` 并让客户端稍后重试，不产生
  第二个已提交 principal。

为避免 JPA 在唯一约束异常后把当前事务标记为 rollback-only，外层 keyed create 不使用
一个包住全流程的 `@Transactional` 方法。它通过 `TransactionTemplate`（或独立的
`REQUIRES_NEW` transaction component）执行一次完整创建；唯一竞争异常离开事务后，最多
重试 3 次。普通无幂等 create 和既有 rotate 继续沿用当前事务边界。

### 5.4 Cleanup

新增 `rag.api-key.provisioning.*` 配置：

- `enabled`：默认 `true`，允许关闭 ledger 读取/写入前必须 fail closed；
- `retention`：默认 `400d`，范围 7-3650 天；
- `cleanup-batch-size`：默认 500，范围 10-5000；
- `concurrent-retry-attempts`：默认 3，范围 1-8。

cleanup 只删除 `completed_at < now-retention` 的记录，按 `id` 有界批次执行；不删除
principal、credential 或 Collection。cleanup 失败只记录脱敏 warning，不能影响正常认证；
读取/写入 ledger 的数据库故障仍按请求失败关闭，避免重复创建。

若实现采用 `@Scheduled`，必须保证未配置调度器时不阻塞启动，并在 PostgreSQL 多实例下
允许多个实例安全地重复尝试删除同一批已过期行；删除使用条件批量 SQL，不使用显式锁。

## 6. Capability contract 的实现来源

capability 响应不得通过扫描 controller 或读取任意配置动态猜测。新增一个 core 内部
`IntegrationCapabilityCatalog`，集中维护：

- contract name/version；
- 已验证的数据面 feature；
- 已验证的限制常量；
- feature flag 影响的能力（例如 disabled 的服务端能力必须返回 `false`，不能静态写
  `true`）；
- 对当前 authenticated principal 的 projection。

能力值的来源规则：

1. 协议能力和数据合同来自代码/测试固定声明；
2. feature flag 从配置读取：`embedding.asyncPolicy` 反映
   `rag.embedding-jobs.enabled`，`optional.documentSyncRuns` 反映
   `rag.document-lifecycle.sync-runs-enabled`，`optional.openAiCompatibility` 反映
   `rag.openai-compatibility.enabled`；
3. principal capability/access mode 来自当前 request 的 immutable auth snapshot；root、
   legacy/static 和 auth-disabled 使用本节固定 projection；
4. Collection allow-list 只从当前 snapshot + identity resolver 完整解析；
5. 不把模型 provider 是否在线、当前数据库连接细节或 secret 状态当作 capability。

capability endpoint 需要在无 Collection resolver、ACL 解析不完整或 feature catalog 无法
构造时返回 `503`，不返回部分 JSON。

## 7. 文件级实施顺序

以下顺序在新特性 worktree 中执行，规划和最终文档提升在 `main` 完成：

### Slice A：公共 API 与 schema

- `spring-ai-rag-api`：新增 provisioning response 的 `secretAvailable`、
  `idempotentReplay`、`currentCredentialActive`，必要时新增 replay warning 常量；
- 新增 capability DTO、feature DTO、limits DTO 和 protocol DTO；
- 增加 `V50__api_provisioning_idempotency.sql`；
- 增加 `ApiKeyProvisioningOperation` entity/repository；
- 明确 Jackson `null` 序列化和 OpenAPI schema。

### Slice B：service/controller

- 新增 immutable canonical request/fingerprint builder；
- 在 `ApiKeyManagementService` 增加 keyed create、精确 replay、唯一竞争重读和 retention
  cleanup；
- `ApiKeyController` 读取 `HttpServletRequest` 的所有幂等 header 值，复用 validator，
  解析完 ACL 后传入 service；
- 新增 `IntegrationCapabilityCatalog` 和 `IntegrationCapabilitiesController`；
- 将 endpoint 纳入认证/普通数据库 capability filter 的 identity 例外；认证仍由现有
  `ApiKeyAuthFilter` 执行，identity 例外只是不额外要求 `RAG_READ`；
- root 与数据库 principal 权限保持与 `/auth/me` 一致，不允许通过参数观察他人。

### Slice C：测试

- API DTO/Jackson/OpenAPI contract；
- controller MockMvc：首次、replay、fingerprint conflict、invalid/multi-value、
  root/normal/legacy-static/auth-disabled、owner 隔离、no-store、secret null；并发竞争不要求伪造
  `IN_PROGRESS`，改为验证有限竞争恢复；
- service 并发/唯一竞争和 cleanup 单元；
- cleanup 后复用同一 owner/key 的行为必须明确测试为“新的 provisioning 周期”，不把过期
  ledger 误判为永久幂等；
- PostgreSQL V50 migration、明文 secret 约束、同 owner/key 只有一条 principal、
  不同 owner 隔离、rotation/revoke 后 replay metadata；
- 真实 HTTP contract：root create timeout-like replay、ACL canonicalization、capability
  response、restricted/unrestricted、feature flag、敏感字段不泄露；
- WebUI typecheck/Vitest/build，确保新增 response 字段不破坏现有一次性 secret UI；
- 核心 Mock Playwright 继续覆盖创建和轮换，不把 raw secret 写入 URL/storage/log；
- Playwright 仅用 DOM、可访问状态、网络和 JSON 断言，不使用截图。

### Slice D：文档与运行脚本

- 双语 `business-client-integration`：加入 keyed provisioning retry、secret recovery
  和 capability discovery fast path；
- 双语 `rest-api`：加入 endpoint/schema/error；
- 双语 `project-context`、`architecture`、`testing-guide`、`developer-reference`、
  `release-checklist` 和 `TODO` 最小同步；
- 扩展 `business-client-contract-e2e.sh` 或新增通用合同脚本，覆盖 provisioning replay
  和 capability projection；
- 新增/扩展 `verify-business-client-readiness.sh`，确保新门禁实际被执行；
- `AGENTS.md` 只在命令/事实入口变化时增加短链接，不复制规划正文。

## 8. 一次性验收矩阵

| 层级 | 必须证明 | 证据 |
|---|---|---|
| API DTO/Jackson | 首次 raw secret、replay null、boolean 标志、旧字段兼容 | API module tests |
| Controller | header 规范化、owner、精确冲突、状态码/header/no-store、权限边界 | `ApiKeyControllerTest`、identity/capability controller tests |
| Service | canonical fingerprint、唯一竞争、失败回滚、replay current metadata、cleanup | service tests |
| PostgreSQL/Flyway | V50 从 V49 可迁移；唯一约束；不存 raw；并发只留一个 principal/ledger | `ManagedApiPrincipalPostgresIntegrationTest` 或专项 IT |
| Auth/filter | database/root/legacy/auth-disabled 只看到允许的 projection；路径受保护 | web integration/filter tests |
| Capability | fixed contract、feature flag、restricted allow-list、503 fail closed、无敏感字段 | OpenAPI + HTTP contract |
| Real HTTP | 一次创建、同 key 精确 retry、不同 body conflict、不同 owner isolation、rotation/revoke、capability JSON | disposable PostgreSQL + real Boot |
| WebUI | create/rotate DOM 可见，secret 不入 storage/URL/log，build/typecheck 通过 | Vitest + Mock/real Playwright |
| Backend build | compile/test-compile，目标 profile 可启动、health OK | Maven + startup smoke |
| Docs/security | 双语结构、链接、空白、禁锁、secret scan | project-docs/readiness gates |
| Real LLM | 本轮不改 Chat/LLM 路径，真实 LLM 行为 N/A；若全量 readiness gate 含既有 provider smoke，则按既有门禁执行并记录 | existing real-provider gate |

### 8.1 真实服务合同用例

必须一次性规划并实现以下顺序，测试 fixture 使用随机 run ID，结束由 trap 清理：

1. root 创建 restricted read-only principal，发送唯一幂等键；
2. 首次响应 `201`、raw secret 存在、secret not persisted；
3. 重复完全相同 JSON 且字段顺序变化，响应 `200`、replay header、raw `null`、资源
   identity 相同；
4. 同 key 修改 name/capability/Collection，得到 `409 IDEMPOTENCY_KEY_REUSED`，数据库
   principal 数量不增加；
5. 用另一个 owner 不能读取或复用当前 owner 的账本；测试覆盖 root、database、
   legacy/static 和 auth-disabled 的服务端 owner 映射，并以 service/PostgreSQL owner
   predicate 断言，不能把 owner 作为客户端可控字段；
6. 轮换 replay 返回的 current credential，旧 credential 失效、新 credential 可认证；
7. capability endpoint 用 restricted credential 返回精确 allow-list、capability 和
   feature contract，确认不含 raw/hash/provider/db 字段；
8. revoke 后 capability endpoint 返回认证失败；数据库 ledger 仍不生成第二 principal；
9. cleanup 只清理 ledger，不删除 principal/credential。

## 9. 真实 LLM 与成本边界

本轮是 credential/control-plane 改动，不改变 Chat model、embedding model、tool loop、
memory 或 retrieval。Mock 和真实 HTTP contract 足以覆盖本轮新增行为，不应为了“看起来
完整”向真实 LLM 发送无关请求。

如果最终使用既有全量 readiness 门禁，该门禁中的真实 provider/LLM 用例仍必须按项目规则
执行；本轮进度只记录其实际结果，不把已有 Chat 通过结果冒充 provisioning 正确性证据。

## 10. 发布、回滚与兼容

### 10.1 发布

1. Flyway 先执行 V50；
2. 部署包含新 API/API docs；
3. root operator 升级后用新幂等键创建一个受限 principal；
4. 调用方先读取 capability contract，再读取 `/auth/me` 和运行 binding preflight；
5. 观察 provisioning conflict/replay/in-progress、capability 503 和 cleanup 指标/日志。

### 10.2 回滚

- 代码回滚到旧版本后，V50 表可以保留；旧代码不读取它，不影响 V49 认证；
- 不回滚已执行 Flyway migration，不手工删除表；
- 若 capability endpoint 发现严重问题，可停止调用该 endpoint，旧的 OpenAPI +
  `/auth/me` + preflight 路径仍可工作；
- 若 keyed create 发现账本问题，应暂时关闭 provisioning idempotency 配置并停止携带
  header，不能静默退回带 key 的非幂等创建；无 key 的旧路径仍按兼容语义运行；
- 任何数据库 constraint 修复必须新增后续 migration，不改写 V50。

### 10.3 可观测性

- Micrometer 计数：首次 keyed create、replay、fingerprint conflict、in-progress、
  unique-race recovery、capability 200/503、cleanup deleted/failed；
- 日志仅记录 owner 类型、principal/credential public ID、结果类别和 hash 前缀；
- 禁止记录 `Idempotency-Key` 原文、Authorization、X-API-Key、raw credential、完整
  request body 或 capability response；
- response body 中的 `rawKey` 仍只允许在首个成功 create/rotate response。

## 11. 工作流与完成定义

### 11.1 规划阶段

- 在 `main` 写本规划和 `NEXT_HIGH_VALUE_FEATURES_PROGRESS.md`；
- 规划检查固定为三轮：需求闭环与自包含性；代码/schema/API/安全/并发可实施性；
  验收/发布/回滚/文档闭环；
- 任何实质问题修正文档后计数归零；连续三轮无修改才进入实施；
- 规划完成后先 commit/push，并建立保护 checkpoint。

### 11.2 实施阶段

- 从最新本地 `main` 创建新专用分支和隔离 worktree；
- 每个关键切片先更新 progress，再修改代码；
- 基本硬门槛先行：

  ```bash
  mvn clean compile test-compile
  ./scripts/verify-no-pessimistic-locks.sh
  ./scripts/verify-project-docs.sh
  ```

  然后执行本任务 PostgreSQL/HTTP 集成、WebUI `tsc`/Vitest/build/核心 Mock Playwright、
  真实服务合同和适用的真实 provider gate；
- 基本门槛全部通过后，按固定范围完成实现 `3/3` 无修改审查；任何实质修复计数归零并
  重跑受影响门槛；
- 合并最新 `origin/main` 到特性分支后，以合并后代码重跑完整顺序，不沿用合并前结果。

### 11.3 交付

- 更新双语长青文档，先归档本轮 plan/progress；
- 特性分支 commit/push；
- 合并并推送 `main`；
- 确认 `main == origin/main`、工作区干净；
- 安全移除仅本轮创建的隔离特性 worktree；
- 进入下一轮高价值需求探索，直到用户要求停止。

## 12. 规划完成后的检查记录

规划正文已于 2026-08-26 完成三轮连续无实质修改审查，达到 `3/3`。检查范围依次为：

1. 需求闭环、自包含性、默认决策与非目标；
2. 代码、schema、API、安全、并发与兼容可实施性；
3. 实施顺序、验收矩阵、发布、回滚、文档闭环与交付风险。

检查期间未发现需要修正规划正文的实质问题。下一步是提交并推送规划 checkpoint，
再从最新 `main` 创建隔离特性 worktree 开始实施。
