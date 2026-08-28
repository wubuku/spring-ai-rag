📖 [English](rest-api.md) · 📖 [中文](rest-api-zh-CN.md)

---

# REST API 参考

📖 [English](rest-api.md) · 📖 [中文](rest-api-zh-CN.md)

> 启动后可通过 `/swagger-ui.html` 访问 Swagger UI。
>
> 主要基础路径：`/api/v1/rag`。默认关闭的 OpenAI 兼容预览使用 `/v1`。

---

## 通用约定

### 认证

请求凭据使用 Header：

```
Authorization: Bearer your-api-key
X-API-Key: your-api-key
```

同时提供两个 Header 且值不一致时返回 `401`。

配置 `RAG_ROOT_API_KEY` 后进入独立服务 root 模式：

- 所有 `/api/**` 自动要求 environment root 或有效数据库业务 Key。
- query credential（`?apiKey=`）返回 `401`；SSE 使用 `fetch` + Header。
- environment root 可访问 RAG 数据面并管理 API Key。
- 数据库 NORMAL 业务 Key 按 principal policy 具有只读 `RAG_READ` 或完整
  `RAG_READ + RAG_WRITE` 能力；调用 `/api-keys` 管理端点仍返回 `403`。
- legacy `rag.security.api-key` 在 root 模式下不参与认证。

未配置 root 时保持 legacy 模式：`rag.security.enabled=true` 后接受上述 Header，并继续
兼容 `?apiKey=`；数据库 ADMIN/NORMAL 与静态 Key 语义保持不变。

#### `GET /api/v1/rag/auth/me`

返回当前 principal 和能力。WebUI 使用该端点确认输入的是 environment root：

```json
{
  "principalType": "ENVIRONMENT_ROOT",
  "principalId": "environment-root",
  "rootMode": true,
  "capabilities": ["RAG_READ", "RAG_WRITE", "API_KEY_MANAGE"],
  "principalRole": null,
  "collectionAccessMode": "UNRESTRICTED",
  "allowedCollectionKeys": null
}
```

restricted 数据库业务 Key 的响应示例：

```json
{
  "principalType": "DATABASE_API_KEY",
  "principalId": "rag_p_service",
  "rootMode": true,
  "capabilities": ["RAG_READ", "RAG_WRITE"],
  "credentialId": "rag_k_current",
  "credentialVersion": 2,
  "policyVersion": 3,
  "principalRole": "NORMAL",
  "collectionAccessMode": "RESTRICTED",
  "allowedCollectionKeys": ["customer-42:records:v1"]
}
```

`principalRole` 只适用于数据库 principal；environment root 和 legacy static 返回
`null`。`collectionAccessMode` 是当前请求 principal 的实际授权语义：
`RESTRICTED` 返回自身完整稳定 key allow-list，`UNRESTRICTED` 返回
`allowedCollectionKeys=null`。legacy `ADMIN` 即使存在残留 allowed IDs，也按实际权限返回
unrestricted。

`rootMode=true` 只表示服务配置了 environment root，不表示当前 credential 就是 root。
调用方必须先判断 `principalType`。ACL 引用无法完整映射时端点返回 `503`，不会返回部分
allow-list。数据库业务 Key 不能用该端点查看其他 principal；WebUI 也不允许其解锁管理台。
响应带 `Cache-Control: no-store`。完整生产 binding 和轮换流程见
[业务服务接入指南](business-client-integration-zh-CN.md)。

#### `GET /api/v1/rag/integration-capabilities`

返回按当前调用方投影的版本化、低敏集成合同。启用认证时必须提供有效 credential；
数据库 NORMAL principal 仅为发现自身合同不额外要求 `RAG_READ`。响应带
`Cache-Control: no-store`。

```json
{
  "protocol": {
    "name": "spring-ai-rag-integration",
    "version": "1.1",
    "apiVersion": "1.0.0"
  },
  "principal": {
    "principalType": "DATABASE_API_KEY",
    "principalRole": "NORMAL",
    "capabilities": ["RAG_READ"],
    "collectionAccessMode": "RESTRICTED",
    "allowedCollectionKeys": ["customer-42:records:v1"]
  },
  "features": {
    "provisioning": {
      "idempotencyKey": true,
      "replayReturnsSecret": false,
      "rawCredentialShownOnce": true,
      "collectionCreateIdempotencyKey": true
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
      "documentSyncRunItemReceipts": false,
      "openAiCompatibility": false,
      "integrationObservability": true,
      "collectionPurge": false
    },
    "credentialRotation": {
      "immediate": true,
      "staged": true,
      "cancel": true,
      "idempotencyKeyRequired": true,
      "defaultOverlapSeconds": 900,
      "maxOverlapSeconds": 3600,
      "replayReturnsSecret": false,
      "operationRetentionDays": 400
    }
  },
  "limits": {
    "maxCollectionKeysPerPrincipal": 100,
    "collectionKeyMaxLength": 128,
    "sourceNamespaceMaxLength": 128,
    "externalIdMaxLength": 255,
    "sourceRevisionMaxLength": 255,
    "structuredRecords": {
      "maxJsonbPayloadBytes": 1048576,
      "maxRetrievalTextChars": 10000,
      "maxBatchItems": 20,
      "maxBatchPayloadBytes": 10485760,
      "maxSearchResults": 20,
      "maxPayloadFilterBytes": 16384,
      "maxPayloadFilterDepth": 8
    },
    "syncRuns": {
      "maxBatchItems": 100,
      "maxItemReceiptPageItems": 200,
      "maxRunListPageItems": 100
    },
    "observability": {
      "retentionDays": 90,
      "maxQueryRangeDays": 31,
      "maxCollectionBreakdownItems": 100
    },
    "collectionPurge": {
      "maxDocuments": 10000,
      "maxEmbeddings": 100000,
      "maxVersions": 100000,
      "maxDerivedRows": 250000,
      "maxAffectedChatSessions": 1000,
      "maxChatRows": 50000
    }
  }
}
```

响应有意不暴露 credential/principal ID、provider 配置、数据库细节或 secret，并直接反映
运行时 feature flag、可配置 structured-record/observability 限制；固定 Sync Run 上限
与请求校验复用同一组常量。restricted ACL 无法完整映射为稳定 Collection key 时返回
`503 SERVICE_UNAVAILABLE`，不会发布部分合同。客户端先用该端点选择受支持行为，再用
`/auth/me` 和 Collection 探针验证精确部署 binding。

`features.optional.collectionPurge` 是调用方感知字段：只有服务开关开启，且当前身份是
environment root、数据库 `ADMIN`，或显式允许的 auth-disabled 直接 loopback 调用时才为
`true`。数据库 `NORMAL`、legacy static 和普通远程匿名调用始终看到 `false`。限制字段可
用于提前判断同步清理上限，但不替代 preview 时的实时冲突和引用完整性检查。

#### `GET /api/v1/rag/integration-observability`

返回服务稳定外部接入 operation 的有界、best-effort UTC 聚合。数据库 NORMAL
principal 调用时要求 `RAG_READ`，响应始终带 `Cache-Control: no-store`。

查询参数：

| 参数 | 默认值 | 合同 |
|---|---|---|
| `from` | `to` 之前 24 小时 | 包含边界的 ISO-8601 instant |
| `to` | 当前时间 | 不包含边界的 ISO-8601 instant |
| `bucket` | `HOUR` | `HOUR` 或 `DAY` |
| `operation` | 全部受支持 operation | `IntegrationOperation` 枚举值 |
| `collectionKey` | 当前有权访问的全部 Collection | active Collection key |
| `principalId` | 当前 principal；管理调用方默认全部 | 仅 root/ADMIN 可指定 |

查询窗口默认最多 31 天。数据库 NORMAL principal 只能查询自身 stable principal 和当前
Collection 授权范围；root 与数据库 ADMIN 可查询全局或指定一个数据库 principal。
legacy static 与匿名身份被拒绝；关闭认证的本地模式只提供本地全局视图。非法输入返回
`400`，越权范围返回 `403`，观测关闭或授权范围无法完整解析时返回 `503`。

响应包含请求总量、平均/最大耗时、由 histogram 估算的 P50/P95 上界、status/operation
拆分、已授权 Collection contribution 以及小时/日时间线。每个请求总量只计一次；
Collection contribution 表示已授权参与关系，不能相加冒充请求总量。
`completeness.mode=BEST_EFFORT` 与 `currentInstanceDropped` 明确说明其不是计费账本。

V54 只在 `rag_api_operation_hourly` 与
`rag_api_collection_operation_hourly` 保存 UTC 小时聚合，不保存请求/响应正文、query、
payload、external ID、credential、动态 URL 或异常正文。记录器使用异步有界队列，故障
对业务请求 fail-open。Micrometer 标签仅允许固定 operation、status class、principal
type、result 和 drop reason；principal ID 与 Collection key 不会进入 metric 标签。

对数据库 NORMAL principal，`GET`/`HEAD`/`OPTIONS` 和明确的只读 POST
（Search、Chat、JSON Record search、model compare、非持久化 evaluation、
`/v1/chat/completions`）需要 `RAG_READ`；其他 `POST`/`PUT`/`PATCH`/`DELETE`
默认需要 `RAG_WRITE`。缺少能力时在进入 controller 和共享限流计数前返回 `403`。
原生 `/api/**` 返回 `FORBIDDEN`，OpenAI 兼容 `/v1/**` 返回
`insufficient_permissions`。environment root、legacy static、数据库 ADMIN 与关闭
认证的兼容路径不受该 NORMAL 数据面门禁限制。

数据库业务 Key 对外使用 `allowedCollectionKeys`；响应中继续保留 deprecated 的
`allowedCollectionIds` 兼容字段：

- 省略范围表示可访问全部 Collection。
- 非空列表会把 Search、Chat、Collection、Document、上传和 PDF-to-RAG 限制在这些
  Collection 内。
- 受限 Key 显式请求未知或未授权 key 时返回 `403`，避免枚举 Collection。
- 受限 Key 未提供 Collection 过滤时，检索自动收敛到其允许列表。

### Collection 身份

Collection 同时具有两个标识：

- `id` 是内部数据库 `BIGINT` 主键和外键。
- `collectionKey` 是调用方提供的稳定外部业务标识，也是 API 推荐身份。

创建、导入和克隆目标必须提供 `collectionKey`。它只能包含 1-128 个可见 ASCII
字符（`U+0021` 至 `U+007E`），区分大小写，并按原值保存。服务不会 trim、归一化、
转换大小写、截断或自动生成。key 全局唯一，创建后不可变，软删除后仍被占用。不具备
业务命名方案的调用方可在本地生成 UUID。

key 可能包含 URL 保留标点，因此 by-key 路由使用 query parameter，调用方必须正确进行
URL 编码。数字路径及 `collectionId(s)` 字段继续兼容但已 deprecated。同时提供 ID 和
key 时，两者解析出的 Collection 集合必须一致；比较时忽略顺序，不一致返回 `400`。

### Collection 检索范围语义

Chat 和 Search 接受 `collectionScopeMode`：

| 模式 | 不受限调用方 | 受限 API Key |
|------|--------------|---------------|
| `CALLER_VISIBLE` | 全部可检索文档，包括未归属 Collection 的文档 | Key 的 Collection allow-list 内文档 |
| `ANY_COLLECTION` | 所有 `collection_id IS NOT NULL` 的可检索文档 | Key 的 Collection allow-list 内文档，不会扩大权限 |
| `SELECTED_COLLECTIONS` | 显式指定 Collection 的并集 | 指定 Collection 必须是 allow-list 子集 |

兼容推导规则：

- 同时省略 mode 和所有 Collection 字段：推导为 `CALLER_VISIBLE`。
- 省略 mode 但传入非空 `collectionKeys` 或 deprecated `collectionIds`：
  推导为 `SELECTED_COLLECTIONS`。
- `CALLER_VISIBLE` 与 `ANY_COLLECTION` 不允许出现任何 Collection 列表。
- `SELECTED_COLLECTIONS` 必须提供非空 key 或 ID 列表。
- 显式空 Collection 列表返回 `400`。
- Collection 身份最多 100 个，`documentIds` 最多 1000 个。
- 同时提供 key 和 ID 时，两者必须标识同一集合。
- `documentIds` 与内部使用的 `documentType` 会和授权后的 Collection 范围取交集。

不受限调用方传入未知 key 返回 `404`；受限调用方传入未知或未授权 key 返回 `403`，
避免泄露 Collection 是否存在。deprecated 的未知数字 ID 对不受限调用方只会零命中。

向量、English FTS、pg_jieba 与 pg_trgm 检索会直接在 PostgreSQL 使用
`d.collection_id = ANY (?)`、`d.collection_id IS NOT NULL` 和可选 JDBC
`bigint[]` 文档过滤，不再把 Collection 展开为全部 document IDs。多个 Collection
组成一个候选并集并统一竞争 global top-k；本次不支持按每个 Collection 保底召回的
`EACH_COLLECTION`。

#### 外部客户端最佳实践

1. 新客户端应显式发送 `collectionScopeMode`；兼容推导主要用于旧客户端平滑迁移，
   不应作为新集成的隐式业务规则。
2. 对外持久化和传输 `collectionKey`，不要保存数据库 `collectionId`。数字 ID 仅用于
   旧客户端兼容和内部诊断。
3. 用户明确选择一个或多个知识库时使用 `SELECTED_COLLECTIONS + collectionKeys`；
   只有确实需要调用方全部默认可见内容时才使用 `CALLER_VISIBLE`；需要排除未归属文档
   但不限定具体知识库时使用 `ANY_COLLECTION`。
4. `collectionKeys` 只随 `SELECTED_COLLECTIONS` 发送。不要发送显式空列表；客户端应
   先去重并限制在 100 个以内，稳定排序有利于缓存键、日志和测试保持一致。
5. 对生产 connector 或业务服务创建受限 API Key，并配置 `allowedCollectionKeys`。
   请求级 selected 范围用于表达本次业务意图，API Key allow-list 用作独立的权限上限。
6. Chat、SSE Chat、GET/POST Search 应传递相同的 scope 语义。上线前先用 Search 端点
   观察直接召回结果，再验证 Chat 生成，便于区分检索问题和 LLM 生成问题。
7. 多个 selected Collection 共享一个 global top-k，不保证每个 Collection 都返回结果。
   需要逐 Collection 覆盖时不要把当前语义当作保证；后续设计边界见
   [TODO：`EACH_COLLECTION`](TODO-zh-CN.md#each_collection-召回覆盖模式)。
8. 对 `400` 修正请求组合或上限；对 restricted caller 的 `403` 按未授权处理，不根据
   错误猜测 Collection 是否存在；不受限调用方的未知 key 返回 `404`。

### API 密钥管理

root 模式下，本节所有管理端点只允许 environment root。通过 root 创建的 Key固定为
数据库 `NORMAL` 角色，可配置为只读或读写 RAG 数据面，但不能管理 Key。
未配置 root 时保留 legacy ADMIN/NORMAL 管理语义。

#### `GET /api/v1/rag/api-keys`

兼容性与审计用途的 credential history。响应不会包含原始密钥或 hash；新管理界面应使用
下方 principal 端点：

```json
[{
  "keyId": "rag_k_abc123",
  "principalId": "rag_p_service",
  "credentialVersion": 2,
  "currentCredential": true,
  "retiringCredential": false,
  "retireAt": null,
  "name": "Production Server",
  "role": "NORMAL",
  "capabilities": ["RAG_READ", "RAG_WRITE"],
  "allowedCollectionKeys": ["customer-42:manual:v3"],
  "allowedCollectionIds": [1, 2],
  "enabled": true,
  "createdAt": "2026-08-14T00:00:00",
  "lastUsedAt": null,
  "expiresAt": "2026-10-01T00:00:00"
}]
```

#### `GET /api/v1/rag/api-keys/principals`

按稳定调用主体返回，每个 principal 一行。响应只带当前 credential 元数据，不返回 raw
secret、hash 或完整 history：

```json
[{
  "principalId": "rag_p_service",
  "name": "Production Server",
  "role": "NORMAL",
  "capabilities": ["RAG_READ"],
  "allowedCollectionKeys": ["customer-42:manual:v3"],
  "requestsPerMinute": 120,
  "policyVersion": 3,
  "status": "ACTIVE",
  "currentCredentialId": "rag_k_abc123",
  "currentCredentialVersion": 2,
  "rotationPending": true,
  "pendingRotationId": "c675b6d2-f9b2-47aa-b7c0-cc46cd70e02b",
  "retiringCredentialId": "rag_k_previous",
  "retiringCredentialVersion": 1,
  "rotationExpiresAt": "2026-08-27T20:15:00",
  "lastUsedAt": "2026-08-23T12:00:00",
  "expiresAt": "2026-10-01T00:00:00"
}]
```

#### `POST /api/v1/rag/api-keys`

root 模式下 `expiresAt` 必填且必须在未来，不设固定的最长有效期。
`allowedCollectionKeys` 可省略；省略表示可访问全部 Collection。
`allowedCollectionIds` 已 deprecated。`capabilities` 只接受 `["RAG_READ"]` 或
`["RAG_READ", "RAG_WRITE"]`；省略时默认完整读写。

可选 `Idempotency-Key` Header 使 provisioning 在配置的保留窗口内可以安全重试。key
必须是 1-255 个可见 ASCII 字符，并按实际认证 provisioning principal 隔离。同一个 key
只能与同一个规范化请求复用；Collection key 与解析后等价的 numeric ID 会生成相同
fingerprint。非法或重复 Header 返回 `400 IDEMPOTENCY_KEY_INVALID`。

```json
{
  "name": "My API Key",
  "expiresAt": "2026-10-01T00:00:00",
  "allowedCollectionKeys": ["customer-42:manual:v3"],
  "requestsPerMinute": 120,
  "capabilities": ["RAG_READ"]
}
```

原始密钥仅在首次 `201 Created` 响应中返回一次，响应带
`Cache-Control: no-store`：

```json
{
  "keyId": "rag_k_xyz789",
  "principalId": "rag_k_xyz789",
  "credentialVersion": 1,
  "policyVersion": 1,
  "rawKey": "rag_sk_...",
  "name": "My API Key",
  "allowedCollectionKeys": ["customer-42:manual:v3"],
  "allowedCollectionIds": [1, 2],
  "expiresAt": "2026-10-01T00:00:00",
  "requestsPerMinute": 120,
  "capabilities": ["RAG_READ"],
  "secretAvailable": true,
  "idempotentReplay": false,
  "currentCredentialActive": true
}
```

精确 keyed replay 返回 `200 OK`、`X-RAG-Idempotent-Replay: true` 和
`Cache-Control: no-store`，绝不重放 raw secret：

```json
{
  "keyId": "rag_k_xyz789",
  "principalId": "rag_k_xyz789",
  "credentialVersion": 1,
  "rawKey": null,
  "name": "My API Key",
  "secretAvailable": false,
  "idempotentReplay": true,
  "currentCredentialActive": true
}
```

同一 owner/key 携带不同请求时返回 `409 IDEMPOTENCY_KEY_REUSED`。ledger 只保存 hash
和结果 metadata，不保存 raw credential。轮换后 replay 返回当前 credential ID/version，
仍保持 `rawKey=null`；吊销或到期后返回 `keyId=null`、
`credentialVersion=null`、`currentCredentialActive=false`。ledger 被关闭或不可用时，
keyed 请求 fail closed 返回 `503`，不会静默退化为非幂等 create。

#### `PUT /api/v1/rag/api-keys/principals/{principalId}/policy`

原子更新 name、expiry、Collection ACL、可选 principal quota 与操作能力。
`expectedPolicyVersion` 必填，版本过期返回 `409 POLICY_VERSION_CONFLICT`。
省略 `allowedCollectionKeys` 表示不限制 Collection；省略 `requestsPerMinute` 表示使用
全局配额；省略 `capabilities` 保留当前能力。数据库 ADMIN 不能降级为只读。

```json
{
  "expectedPolicyVersion": 1,
  "name": "My API Key",
  "expiresAt": "2027-10-01T00:00:00",
  "allowedCollectionKeys": ["customer-42:manual:v3"],
  "requestsPerMinute": 240,
  "capabilities": ["RAG_READ", "RAG_WRITE"]
}
```

#### `POST /api/v1/rag/api-keys/{keyId}/rotate`

这是即时切换的兼容路径：在一个事务中禁用当前 credential，并创建同一 stable principal
的下一个 credential version。owner、role、policy version、ACL、expiry、quota 与
capabilities 均保持不变。使用旧 credential ID 返回
`409 CREDENTIAL_NOT_CURRENT`；已有 staged rotation 时返回
`409 CREDENTIAL_ROTATION_PENDING`；raw secret 仅在本次 `201 Created` 响应中返回。

生产滚动部署应优先使用下面的有界 staged 工作流。

#### `POST /api/v1/rag/api-keys/{currentKeyId}/rotations`

准备分阶段轮换。`Idempotency-Key` 必填；可选请求体以秒指定 overlap，省略时使用服务端
默认值：

```http
POST /api/v1/rag/api-keys/rag_k_current/rotations
Idempotency-Key: deploy-2026-08-27-service-a
Content-Type: application/json

{"overlapSeconds":900}
```

首次成功返回 `201 Created`、`Cache-Control: no-store`、稳定 `rotationId`，并且只展示
一次新 raw credential：

```json
{
  "rotationId": "c675b6d2-f9b2-47aa-b7c0-cc46cd70e02b",
  "status": "PENDING",
  "principalId": "rag_p_service",
  "keyId": "rag_k_new",
  "credentialVersion": 2,
  "rawKey": "rag_sk_...",
  "secretAvailable": true,
  "idempotentReplay": false,
  "currentCredentialActive": true,
  "rotationPending": true,
  "retiringCredentialId": "rag_k_current",
  "retiringCredentialVersion": 1,
  "rotationExpiresAt": "2026-08-27T20:15:00"
}
```

`PENDING` 期间，新 current credential 与 retiring credential 都可以使用同一 stable
principal 认证。两者共享 ACL、capabilities、Chat/session owner、用量归因和 PostgreSQL
quota；overlap 不会创建第二个身份或配额 bucket。实际 deadline 会被 principal expiry
截短，并直接进入认证条件，因此 cleanup 延迟不会延长旧 credential 的有效期。

请求超时后使用同一个 `Idempotency-Key` 精确重试。响应为 `200 OK`、
`X-RAG-Idempotent-Replay: true`、相同 `rotationId` 和 `rawKey:null`；服务绝不重建
一次性 secret。同一个 key 改用其他 current credential 或 overlap 时返回
`409 IDEMPOTENCY_KEY_REUSED`。

#### `GET /api/v1/rag/api-keys/rotations/{rotationId}`

读取当前 operation 状态。pending operation 到达 deadline 后会推进为 `EXPIRED`，并禁用
retiring credential。终态包括 `COMPLETED`、`CANCELED`、`EXPIRED` 和 `REVOKED`。
响应绝不包含 raw credential，并始终带 `Cache-Control: no-store`。

#### `POST /api/v1/rag/api-keys/rotations/{rotationId}/complete`

所有调用实例已经部署并验证新 credential 后完成 pending rotation。retiring credential
立即失效，新 credential 保持 current。对同一个已完成 operation 重复 complete 幂等；
deadline 后调用返回 `409 CREDENTIAL_ROTATION_EXPIRED`。

#### `POST /api/v1/rag/api-keys/rotations/{rotationId}/cancel`

在 deadline 前取消 pending rotation。replacement credential 被禁用，retiring credential
恢复为 current。对同一个已取消 operation 重复 cancel 幂等。credential version 永不复用，
之后再次轮换会创建更高版本。deadline 后调用返回
`409 CREDENTIAL_ROTATION_EXPIRED`。

四个 staged endpoint 的成功和错误响应都带 `Cache-Control: no-store`。root 模式只允许
environment root 调用；legacy 模式下数据库 ADMIN 可管理任意 principal，NORMAL principal
只能从自己当前认证的 credential 发起 prepare，也只能查询、完成或取消自己的 operation。

#### `DELETE /api/v1/rag/api-keys/{keyId}`

通过当前 credential ID 吊销整个 principal family。对最后一个版本重复 DELETE 幂等；
旧版本返回 `409 CREDENTIAL_NOT_CURRENT`。legacy 模式通过事务 guard 防止并发吊销最后一个
ADMIN（`409 LAST_ADMIN_REQUIRED`）；environment root 模式可显式吊销。若存在 staged
rotation，吊销会同时禁用 current 与 retiring credential，并把 operation 标为 `REVOKED`。
成功返回 `204 No Content`。

WebUI 管理台入口为 `/webui/unlock`。root credential 只保存在页面内存，刷新或退出后
需要重新输入；外部调用方不需要 WebUI，只需持有分发的业务 Key：

```bash
curl "http://localhost:8081/api/v1/rag/search?query=Spring%20AI" \
  -H "Authorization: Bearer ${RAG_BUSINESS_API_KEY}"
```

### API 限流

`rag.rate-limit.backend=local` 保留进程内固定分钟窗口，可使用 `ip`、`api-key` 或
`user` 策略，只适合单实例。

`backend=postgresql` 必须配合 `strategy=principal`，按认证后的 stable database principal
在 PostgreSQL UTC 分钟 bucket 中计数，所有实例共享。principal 的
`requestsPerMinute` 可覆盖全局默认值，轮换 credential 不重置 bucket。该 backend
不读取 raw credential，也不回退 IP；quota store 故障时返回 `503`
（native 为 `RATE_LIMIT_STORE_UNAVAILABLE`，`/v1` 为 OpenAI error envelope），不会静默
降级到本地计数。

**正常响应中的限流 Header：**

| Header | 说明 |
|--------|------|
| `X-RateLimit-Limit` | 每分钟最大请求数 |
| `X-RateLimit-Remaining` | 当前窗口剩余额度 |

**超限响应：**

```
HTTP/1.1 429 Too Many Requests
Retry-After: 17
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
```

### Error Responses (RFC 7807 Problem Detail)

All error responses follow [RFC 7807](https://datatracker.ietf.org/doc/html/rfc7807) `application/problem+json` format:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "message content must not be blank",
  "instance": "/api/v1/rag/chat/ask"
}
```

| Field | Description |
|-------|-------------|
| `type` | Problem type URI (defaults to `about:blank`) |
| `title` | HTTP status text |
| `status` | HTTP status code |
| `detail` | Specific error description |
| `instance` | Request path where the error occurred |

**Parameter validation errors** (400) merge multiple field errors:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "message: must not be blank; sessionId: must not be blank",
  "instance": "/api/v1/rag/chat/ask"
}
```

---

## OpenAI Chat Completions 兼容预览

设置 `RAG_OPENAI_COMPATIBILITY_ENABLED=true` 后注册以下端点：

- `GET /v1/models`
- `GET /v1/models/{id}`
- `POST /v1/chat/completions`

model ID 是服务端配置的 RAG alias，例如 `rag-default`，表示 Chat mode、memory 策略和
后端模型候选链。alias 不保存 Collection；每次请求通过 `rag.scope` 或重复的
`X-RAG-Collection-Key` Header 表达检索范围，再与当前 API Key ACL 取交集。

```json
{
  "model": "rag-default",
  "messages": [
    {"role": "system", "content": "回答时引用知识库内容。"},
    {"role": "user", "content": "查找风格基调"}
  ],
  "stream": false,
  "rag": {
    "scope": {
      "mode": "SELECTED_COLLECTIONS",
      "collection_keys": ["brand:guides"]
    },
    "document_ids": [42]
  }
}
```

也可以按 Collection 重复 Header，不使用逗号拼接：

```bash
curl http://localhost:8081/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${RAG_BUSINESS_API_KEY}" \
  -H 'X-RAG-Collection-Key: brand:guides' \
  -H 'X-RAG-Collection-Key: brand:faq' \
  -d '{"model":"rag-default","messages":[{"role":"user","content":"查找风格基调"}]}'
```

body 与 Header 同时出现时必须表示相同的 Collection key 集合。省略两者时默认使用
`CALLER_VISIBLE`；若 `RAG_OPENAI_REQUIRE_EXPLICIT_SCOPE=true`，省略范围返回 `400`。

当前兼容子集：

- 支持 text-only `system`、`developer`、`user`、`assistant` 消息；
- 支持字符串 content 或仅包含 `{type:"text", text:"..."}` 的 content parts；
- `n` 只支持 `1`，`stream` 支持 `false/true`；
- 暂不支持 `temperature`、`top_p`、token 上限、tools/functions、logprobs、
  structured output 和 `stream_options`；传入时返回明确的 OpenAI 错误信封；
- 未知 alias 返回 `404 model_not_found`。

`/v1` 当前没有客户端可指定的 session ID。不同请求默认使用不同内部 session；多轮调用方
应在每次请求中重传完整 `messages`。即使 alias 配置为 `SERVER` memory，也不能把它理解为
对外稳定、可由客户端寻址的跨请求会话合同。

可选 `Idempotency-Key` Header 会复用持久化 Chat turn。精确重放返回稳定的 completion
结果，并通过 `X-RAG-Turn-Id` 与 `X-RAG-Idempotent-Replay` 标识。携带该 Header 时，
`stream=true` 会先完成并持久化 turn，再发送快照式 SSE；需要实时逐 token 首包的调用方
不要在该请求上使用幂等 Header。完整可靠性语义见
[Chat turn 持久化幂等](#chat-turn-持久化幂等)。

非流式返回 `chat.completion`。流式返回标准 `data: <chunk>`，最后发送
`data: [DONE]`；项目专用 tool/source 事件不会泄漏到该协议。认证、限流和运行时错误
同样使用 `{ "error": { "message", "type", "param", "code" } }` 信封。

该入口默认关闭，只定位为受控网络预览；公网、多实例生产 readiness 边界见
[OpenAI 兼容就绪度](openai-compatibility-readiness-zh-CN.md)。

## Chat — RAG 问答

### `POST /api/v1/rag/chat/ask`

非流式 Chat，返回完整答案。同一端点支持三种显式执行模式：

| 模式 | 行为 |
|---|---|
| `KNOWLEDGE` | 始终通过项目混合检索器执行 Spring AI Modular RAG；省略时默认使用 |
| `AGENT` | 由支持 Tool Calling 的模型按需调用授权 `searchKnowledge` 工具 |
| `PLAIN` | 仅模型 + 对话 Memory，不检索知识库 |

**请求体：**

```json
{
  "message": "什么是 Spring AI？",
  "sessionId": "session-001",
  "mode": "KNOWLEDGE",
  "domainId": "medical",
  "model": "openrouter/xiaomi/mimo-v2-pro",
  "collectionScopeMode": "SELECTED_COLLECTIONS",
  "collectionKeys": ["medical:guidelines:v3", "medical:drugs:v2"],
  "documentIds": [10, 20],
  "maxResults": 5,
  "useHybridSearch": true,
  "useRerank": true,
  "filters": {
    "metadataContains": { "source": "manual" }
  },
  "metadata": {}
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | string | ✅ | 问题内容（不超过 10000 字符） |
| `sessionId` | string | | 会话 ID，最长 36 字符；省略时自动生成 |
| `mode` | enum | | `KNOWLEDGE`、`AGENT` 或 `PLAIN`；默认 `KNOWLEDGE` |
| `domainId` | string | | 领域扩展 ID |
| `model` | string | | `GET /rag/models` 返回的运行时模型引用；省略时使用默认链 |
| `collectionScopeMode` | enum | | `CALLER_VISIBLE`、`ANY_COLLECTION` 或 `SELECTED_COLLECTIONS` |
| `collectionKeys` | string[] | | 推荐的稳定 Collection 范围 |
| `collectionIds` | long[] | | deprecated 数字兼容范围 |
| `documentIds` | long[] | | 仅检索这些文档；与集合范围取交集 |
| `maxResults` | int | | 检索结果数，默认 5 |
| `useHybridSearch` | boolean | | 是否启用向量 + 全文检索，默认 true |
| `useRerank` | boolean | | 是否启用重排序，默认 true |
| `filters` | object | | 可选 JSONB containment；`PLAIN` 传入时返回 `400` |
| `metadata` | object | | 扩展元数据。启用时包含协议级 `citationValidation` |

`citationValidation` 只解析约定的 `[S1]` token，不是覆盖率评分。

`maxResults`、`useHybridSearch` 与 `useRerank` 会真实覆盖 `KNOWLEDGE` 和
`AGENT` 的执行参数。`PLAIN` 显式传入这些字段或任何 Collection/document 检索范围时
返回 `400`。

`AGENT` 要求模型注册项声明 `capabilities.toolCalling=true`，且 Spring AI adapter
提供工具选项。显式选择不兼容模型时返回 `MODEL_CAPABILITY_UNSUPPORTED`；默认路由会
跳过不兼容候选。

`domainId` 只按显式 ID 选择领域扩展；未知 ID 返回 `UNKNOWN_DOMAIN`。领域 Prompt
不负责拼接检索上下文。仍含 `{context}` 的 legacy 模板可继续用于 `KNOWLEDGE`，但在
`AGENT` 或 `PLAIN` 中会返回 `DOMAIN_MODE_UNSUPPORTED`，除非扩展实现
`getSystemPromptTemplate(ChatMode)` 提供对应模式的安全 instruction。

**响应：**

```json
{
  "answer": "Spring AI 是 Spring 生态的 AI 应用框架……",
  "traceId": "a1b2c3",
  "sessionId": "session-001",
  "mode": "KNOWLEDGE",
  "requestedModel": "openrouter/xiaomi/mimo-v2-pro",
  "resolvedModel": "openrouter/xiaomi/mimo-v2-pro",
  "usage": {
    "promptTokens": 120,
    "completionTokens": 36,
    "totalTokens": 156
  },
  "finishReason": "STOP",
  "sources": [
    {
      "citationId": "S1",
      "documentId": "1",
      "chunkIndex": 0,
      "title": "Spring AI 介绍",
      "score": 0.92,
      "chunkText": "Spring AI provides ChatClient...",
      "collectionKey": "spring-ai:docs",
      "documentType": "PDF"
    }
  ],
  "metadata": {
    "sessionId": "session-001",
    "retrievalExecuted": true,
    "retrieval": {
      "retrievalCalls": 3,
      "toolRounds": 0,
      "sourceCount": 1,
      "documentJoin": {
        "inputDocuments": 9,
        "uniqueDocuments": 6,
        "duplicateDocumentsRemoved": 3,
        "scoreReplacements": 2
      }
    }
  },
  "stepMetrics": []
}
```

来源 score 是当前查询/配置下的排序信号，不是概率或百分比。`PLAIN` 返回空来源列表。
`metadata.retrievalExecuted` 依据实际检索尝试生成：`PLAIN` 始终为 `false`，
完成 `KNOWLEDGE` pipeline 后始终为 `true`；`AGENT` 若模型未调用
`searchKnowledge`，则可以为 `false`。

对 `KNOWLEDGE`，`metadata.retrieval.documentJoin` 是 rerank 前项目 join 步骤的
additive、低基数摘要，四个字段均为非负整数。它不包含 query 文本、Document ID、
文档正文、metadata 值或模型输出。AGENT、PLAIN、直接 Search、Evaluation 和旧
Advisor 执行不会出现该字段。

---

### `POST /api/v1/rag/chat/stream`

SSE 流式问答，逐块返回答案。

**请求体：** 与 `/ask` 相同。

`sessionId` 同样限制为最长 36 字符；超限请求在进入 Chat Memory 前返回 `400 VALIDATION_FAILED`。

**响应：** `text/event-stream`，包含 `content`、`tool_start`、`tool_result`、
`sources`、`done` 与 `error`。`done` 和 `error` 是互斥终态。事件 payload、顺序、
heartbeat、取消和 fallback 语义见 [SSE-PROTOCOL.md](SSE-PROTOCOL.md)。

**curl 示例：**

```bash
curl -N -X POST http://localhost:8081/api/v1/rag/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"什么是 RAG？","sessionId":"s1","mode":"KNOWLEDGE","model":"openrouter/xiaomi/mimo-v2-pro"}'
```

### Chat turn 持久化幂等

`POST /api/v1/rag/chat/ask`、`POST /api/v1/rag/chat` 和
`POST /api/v1/rag/chat/stream` 都接受一个可选的 `Idempotency-Key` header。
该值按 principal 隔离，服务端只保存 SHA-256 hash。带 key 的请求成功后，会在
`X-RAG-Turn-Id` response header 返回 opaque UUID `turnId`；JSON 响应的
`ChatResponse.turnId` 与 `metadata.turnId` 也保持一致。

第一次成功请求会在同一个 PostgreSQL 事务内持久化与 provider 无关的业务响应、
history 行和 server Memory 更新。相同 key、相同 canonical request 的重试会重放
不可变响应，不会再次调用 provider，也不会新增 history 行。第一次响应的
`X-RAG-Idempotent-Replay` 为 `false`，重放时为 `true`。原生 SSE 的 `done` 事件也会
包含相同的 `turnId` 和 `idempotentReplay`；重放只发送完整 answer/source/done 快照，
不重复原始 tool event 的时间节奏。

同一个 key 对应不同请求时返回 `409 IDEMPOTENCY_KEY_REUSED`。已有请求持有 operation
lease 时返回 `409 IDEMPOTENCY_OPERATION_IN_PROGRESS`，并带有有界的 `Retry-After`
header。非法或重复的 `Idempotency-Key` header 返回 `400 IDEMPOTENCY_KEY_INVALID`。
如果 durable 协调不可用或已关闭，带 key 的请求会 fail closed，返回
`503 IDEMPOTENCY_DISABLED`，不会退回非幂等执行路径。终态 operation 被有界 retention
cleanup 删除后，原 key 才允许重新使用。

### `GET /api/v1/rag/chat/turns/{turnId}`

查询 opaque Chat turn 在当前 principal 下的状态；该接口不接受幂等 key 或 key hash。

```json
{
  "turnId": "opaque-uuid",
  "sessionId": "session-001",
  "status": "SUCCEEDED",
  "transport": "NATIVE_SSE",
  "createdAt": "2026-08-22T15:00:00Z",
  "updatedAt": "2026-08-22T15:00:01Z",
  "completedAt": "2026-08-22T15:00:01Z",
  "replayAvailable": true,
  "response": {
    "answer": "..."
  }
}
```

默认 `includeResponse=false` 只返回状态和经过当前授权复核的
`replayAvailable`，不返回响应快照。`includeResponse=true` 仅在当前 principal 仍有权
读取所有引用来源时返回快照，否则返回 `403`。未知、过期或其他 principal 的 turn ID
返回 `404 CHAT_TURN_NOT_FOUND`。

---

### `GET /api/v1/rag/chat/history/{sessionId}`

查询认证 principal 所拥有的 session history，按创建时间倒序返回。

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `limit` | int | 50 | 返回记录数；服务端限制为 1–500 |

**响应：** `ChatHistoryResponse[]`

```json
[
  {
    "id": 1,
    "sessionId": "s1",
    "userMessage": "什么是 RAG？",
    "aiResponse": "RAG 是检索增强生成。",
    "relatedDocumentIds": [1],
    "metadata": {},
    "sources": [{
      "citationId": "S1",
      "documentId": "1",
      "title": "RAG 指南",
      "chunkText": "RAG 结合检索与生成。"
    }],
    "status": "COMPLETE",
    "mode": "KNOWLEDGE",
    "requestedModel": null,
    "resolvedModel": "minimax/MiniMax-M2.7",
    "createdAt": "2026-08-17T12:00:00"
  }
]
```

不存在的 session 与属于其他 principal 的 session 都返回
`404 SESSION_NOT_FOUND`。

---

### `DELETE /api/v1/rag/chat/history/{sessionId}`

在 lease 保护下同时清理当前 principal 的业务 history 与 Spring AI Memory。session
存在活动请求时返回 `409 SESSION_BUSY`。

**Response:**

```json
{
  "message": "Session history cleared",
  "sessionId": "s1",
  "deletedCount": 10
}
```

---

### `GET /api/v1/rag/chat/export/{sessionId}`

将当前 principal 的会话导出为下载文件。不存在或属于其他 principal 的 session 返回
`404 SESSION_NOT_FOUND`。

| 参数 | 类型 | 位置 | 说明 |
|---|---|---|---|
| `format` | string | query | `json` 或 `md`，默认 `json` |
| `limit` | int | query | 最大 turn 记录数；`0` 表示全部，默认 `0` |

响应为 `application/json; charset=utf-8` 或
`text/markdown; charset=utf-8`，文件名为 `{sessionId}.{format}`。JSON 和 Markdown
中的 assistant 消息都会保留完成 turn 时保存的来源快照；CSV 不属于该 HTTP 端点支持的
格式。

---

## Search — Direct Retrieval

> Does not go through LLM generation; used for debugging and previewing retrieval results.

### `GET /api/v1/rag/search`

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `query` | string | ✅ | Search query text |
| `limit` | int | 10 | Number of results to return |
| `useHybrid` | bool | true | Use hybrid search |
| `vectorWeight` | double | 0.5 | Vector search weight |
| `fulltextWeight` | double | 0.5 | Full-text search weight |
| `collectionScopeMode` | enum | `CALLER_VISIBLE` | 显式 Collection 范围模式 |
| `collectionKeys` | string[] | | 推荐的重复 Collection 范围参数 |
| `collectionIds` | long[] | | deprecated 的重复数字范围参数 |

**Response:**

```json
{
  "results": [
    {
      "documentId": 1,
      "title": "Introduction to Spring AI",
      "score": 0.5,
      "vectorScore": 0.92,
      "fulltextScore": 0.0,
      "chunkText": "Retrieved text snippet...",
      "source": "pdf-import:550e8400-e29b-41d4-a716-446655440000/default.md",
      "originalFilename": "spring-ai-reference.pdf",
      "fileDirectoryPath": "550e8400-e29b-41d4-a716-446655440000/",
      "indexedFilePath": "550e8400-e29b-41d4-a716-446655440000/default.md",
      "originalFilePath": "550e8400-e29b-41d4-a716-446655440000/original.pdf",
      "metadata": {}
    }
  ],
  "total": 3,
  "query": "Spring AI"
}
```

分数字段语义：

- 混合检索模式下，`score` 是同一次查询、同一套检索配置内用于排序的缩放加权
  Reciprocal Rank Fusion（RRF）信号。`K=60` 时，每个通道按
  `(K+1) * weight / (K+rank)` 贡献；同一候选同时被两个通道命中时会累加两项贡献。
  提供方原始分数只用于确定通道内名次，不在不同通道之间直接比较。它不是经过校准的
  概率或相关性百分比；当两个配置权重之和大于 `1.0` 时，结果可能超过 `1.0`。
- `vectorScore` 是原始向量余弦相似度；在融合结果中，`0` 表示该结果没有向量检索贡献。
- `fulltextScore` 是全文检索提供方产生的原始分数；在融合结果中，`0` 表示该结果没有
  全文检索贡献。
- 使用方应优先看结果顺序。组件分数适合诊断结果来自语义向量检索、关键词检索还是两者
  共同命中，但不同组件的原始分数不能直接互换比较。
- 最终融合分数相同时，按确定性的 `documentId`、再按 `chunkIndex` 排序。

来源字段语义：

- `source` 和 `originalFilename` 来自当前 `rag_documents` 记录；普通文档也可能返回这两个
  字段。
- 只有服务端确认 `source` 是安全的 `pdf-import:{uuid}/default.md` 相对路径时，才返回
  `fileDirectoryPath`、`indexedFilePath` 和 `originalFilePath`。
- 三个文件路径字段允许调用方追溯到文件管理目录、实际用于嵌入的 Markdown 和原始 PDF。
  读取原始文件仍必须调用带 API Key 请求头的 `/api/v1/rag/files/raw`，不要把凭据放入
  URL。
- 历史 PDF 文档若已保存 `pdf-import:` source，无需重新嵌入即可获得这些字段；早期被全局
  内容哈希合并、因而没有 PDF source 的记录无法可靠反推来源。

---

### `POST /api/v1/rag/search`

Submit more complex retrieval configuration via request body.

**Request body:**

```json
{
  "query": "Spring AI",
  "collectionScopeMode": "SELECTED_COLLECTIONS",
  "collectionKeys": ["customer-42:manual:v3"],
  "documentIds": [1, 2, 3],
  "config": {
    "maxResults": 10,
    "useHybridSearch": true,
    "vectorWeight": 0.6,
    "fulltextWeight": 0.4,
    "minScore": 0.3
  },
  "filters": {
    "metadataContains": {"source": "manual"},
    "payloadContains": {"status": "active"}
  }
}
```

`filters.metadataContains` / `filters.payloadContains` 必须是非空 JSON 对象，使用
PostgreSQL `@>` 下推到全部候选 SQL。非法对象、超限或未知字段返回 `400`。
响应头 `X-RAG-Retrieval-Trace-Id` 指向当前调用方可查询的诊断。

---

## 检索诊断

| 端点 | 说明 |
|------|------|
| `GET /api/v1/rag/retrieval-traces` | 当前调用方可见的诊断分页；可按 `outcomeCode`、`emptyReasonCode`、`citationStatus` 过滤 |
| `GET /api/v1/rag/retrieval-traces/{traceId}` | 单条诊断详情 |

默认不返回 query 明文。写入失败 fail-open。

---

## JSON 结构化记录：JSONB Payload 检索

结构化记录端点将两个由调用者提供的值明确分开：

- `retrievalText` 是用于 `content_hash`、分块、全文检索和 embedding 的自然语言描述。
- `jsonbPayload` 是保存为 PostgreSQL JSONB 的业务 JSON，在通过范围检索后返回。

服务不会自动生成或校验这两个字段之间的对应关系。调用方使用
`collectionKey + sourceNamespace + externalId` 作为稳定外部身份；服务会解析为内部
`(collectionId, sourceNamespace, documentType=json-record, externalId)` 身份。deprecated 的
`collectionId` 继续兼容。JSON record 不参与普通文档的全局 content-hash 去重，也不存在
`payloadHash`。

### `POST /api/v1/rag/json-records/upsert`

创建或更新一条记录。新外部 client 应显式使用 `embeddingPolicy`；legacy `embed`
仍兼容映射为 `SYNC`/`SKIP`。

```json
{
  "collectionKey": "customer-42:catalog:v1",
  "sourceNamespace": "catalog-main",
  "externalId": "product:sku-10001",
  "sourceRevision": "row-version:42",
  "expectedSourceRevision": "row-version:41",
  "title": "紧凑型无线键盘",
  "retrievalText": "这是一款支持蓝牙与双模 2.4G 连接的紧凑型无线键盘。",
  "jsonbPayload": {
    "sku": "10001",
    "protocols": ["bluetooth", "2.4g"],
    "stock": 42
  },
  "source": "catalog",
  "metadata": {
    "tenant": "demo"
  },
  "embeddingPolicy": "ASYNC"
}
```

仅更新 payload 会创建版本快照，但不会使新鲜 embedding 失效。更新 `retrievalText` 会使
活动 embedding 失效，并按 `embeddingPolicy` 持久化重嵌入任务。已有 identity 的新
revision 默认必须提供 `expectedSourceRevision`；精确重放保持幂等。

### `POST /api/v1/rag/json-records/batch-upsert`

请求体为 `{ "items": [ ... ] }`。各 item 按输入顺序独立处理；单项失败会记录在该项结果中，
不会回滚已经成功的其他项。

### `POST /api/v1/rag/json-records/search`

只在必填的 `collectionKeys` 范围内搜索 JSON record，复用普通 Search 的混合检索链。
deprecated 的 `collectionIds` 继续兼容。响应保持排序，并为每条结果返回当前
`collectionKey`、`retrievalText` 和 `jsonbPayload`。

```json
{
  "query": "支持蓝牙的无线键盘",
  "collectionKeys": ["customer-42:catalog:v1"],
  "payloadContains": {
    "status": "active",
    "attributes": {"wireless": true}
  },
  "config": {
    "maxResults": 10,
    "useHybridSearch": true,
    "useRerank": true
  }
}
```

检索前会应用 API Key 的 Collection ACL；受限 Key 不能超出自身
`allowedCollectionKeys`，未知或未授权 key 返回 `403`。
可选 `payloadContains` 必须是非空 JSON object，使用 PostgreSQL `jsonb @>` 精确子树
包含语义，并在向量、pg_trgm、English FTS、pg_jieba 的候选 SQL 中下推。默认限制为
16 KiB、最大 8 层；数组遵循 PostgreSQL JSONB containment 语义。

设置 `RAG_JSON_AGENT_TOOL_ENABLED=true` 后，`AGENT` 模式额外注册
`searchJsonRecords`。模型只能提供 `query`、可选 `payloadContains` 和 `maxResults`；
Collection、document 与 API Key 范围由服务端上下文注入，工具不会接受 SQL、JSONPath
或 Collection 参数。

### `GET /api/v1/rag/json-records/{documentId}`

按内部 document ID 返回当前结构化记录，包括 `collectionKey`、deprecated
`collectionId`、`externalId`、`retrievalText` 和 `jsonbPayload`。upsert 与 search
响应也同时返回两种 Collection 身份。Collection export/import、clone 和文档版本响应会
保留结构化字段及 payload 快照。

### JSON record 外部身份查询与 tombstone

```text
GET /api/v1/rag/json-records/by-external-id
  ?collectionKey=customer-42%3Acatalog%3Av1
  &sourceNamespace=catalog-main
  &externalId=product%3Asku-10001

DELETE /api/v1/rag/json-records/by-external-id
  ?collectionKey=customer-42%3Acatalog%3Av1
  &sourceNamespace=catalog-main
  &externalId=product%3Asku-10001
  &sourceRevision=row-version%3A43
  &expectedSourceRevision=row-version%3A42
```

删除创建 tombstone，不删除 JSONB 历史或稳定身份。之后使用新的 source revision upsert
会恢复同一内部文档。

lookup 与 tombstone query parameter 使用与 upsert 相同的外部身份边界：

- `collectionKey` 必须为 1–128 个可见 ASCII 字符；
- `sourceNamespace` 最多 128 个字符，省略或空白会规范化为 `default`；非空值只能包含
  可打印 ASCII；
- `externalId` 最多 255 个字符；
- tombstone 的 `sourceRevision` 与可选 `expectedSourceRevision` 最多 255 个字符。

受限 principal 对 search、lookup、upsert 和 tombstone 的未知或未授权 Collection 都会在
查询记录存在性前返回相同的通用 `403`，响应不泄漏 Collection、record 或内部 ID。
embedding provider 失败不会回滚已经提交的外部 Record：identity、source revision、
payload、enabled 状态和 document revision 保持已接受值，派生 job 独立进入 `FAILED`。

<a id="external-documents-idempotent-synchronization"></a>

## 外部文档：幂等同步

这些端点用于同步由外部系统负责身份和版本的普通文本文档。服务不会主动抓取 URL
或文件；调用方读取外部来源后，将当前文档表示提交给 RAG 服务。

稳定外部地址是 `collectionKey + sourceNamespace + externalId`。普通外部文本文档端点要求
提供 `collectionKey`，且它必须指向真实存在的活动 Collection。JSON record upsert 仍为
兼容保留 deprecated `collectionId` 输入，但最终解析到同一个以 key 为准的规范地址。
`sourceNamespace` 可省略；
省略或空白会规范化为兼容值 `default`。如果多个 connector 共用一个 Collection，或启用
来源全量对账，connector 应选择并显式发送稳定的 namespace。它是身份边界而不是授权边界；
互不信任的 connector 应使用不同 Collection。`externalId` 会去除首尾空白、区分大小写，
长度最多 255 个字符，并且在来源对象生命周期内必须保持稳定。`collectionKey` 和
`sourceNamespace` 长度最多 128 个字符；后续迁移不得缩短这些上限。
`sourceRevision` 是调用方提供的非空 opaque 版本令牌，例如 ETag、上游行版本、
commit ID 或 canonical payload hash。服务不会按 opaque 版本的大小比较新旧。

外部地址、状态版本和内部 ID 是三个不同概念。调用方后续寻址始终使用三元地址；
`sourceRevision` 只表示该地址上的完整期望状态；返回的 `documentId` 只用于诊断。当前
Collection 同时是投放目标和 ACL 边界，因此 `externalId` 不要求全局唯一，同一来源对象也
可以被显式投放到多个 Collection。
`sourceNamespace=default` 不代表默认 Collection。`NULL` Collection 归属是本地文档的
未归属状态，不是外部同步的替代目标。

### `POST /api/v1/rag/documents/upsert`

创建或更新一条普通文档。当前 API Key 必须对目标 Collection 具有写权限。

```json
{
  "collectionKey": "customer-42:manual:v3",
  "sourceNamespace": "cms-main",
  "externalId": "cms:article:10001",
  "sourceRevision": "etag:8b4d9f",
  "expectedSourceRevision": "etag:7a3c21",
  "title": "退款政策",
  "content": "当前退款政策是……",
  "source": "cms",
  "documentType": "markdown",
  "metadata": {
    "locale": "zh-CN"
  },
  "embeddingPolicy": "ASYNC"
}
```

`title` 和 `content` 必填。`documentType` 默认是 `text`；`json-record` 必须使用
JSON 结构化记录 API。新 client 推荐显式发送 `embeddingPolicy=ASYNC`；legacy
`embed=true/false` 继续映射为 `SYNC/SKIP`。请求内容最多 1,000,000 个字符。

服务在更新来源文档时保留同一个内部 `documentId`。内容变化会创建版本快照、使旧
chunk/embedding 立即不再参与检索，并在同一事务中持久化新 generation 的任务。
`SYNC` 对该任务有界等待，`ASYNC` 立即返回。只有 metadata 或来源版本变化且当前
embedding 已新鲜时，不会调用 embedding provider。

普通 upsert 不用于改变 Collection；改变 `collectionKey` 会寻址另一组三元地址。需要保留
同一个内部 ID、版本历史和派生行时，使用下述显式 relocation API。

即使文档持久化成功但 embedding 失败，响应仍为 HTTP 200：

```json
{
  "documentId": 42,
  "collectionKey": "customer-42:manual:v3",
  "externalId": "cms:article:10001",
  "sourceRevision": "etag:8b4d9f",
  "action": "UPDATED",
  "contentChanged": true,
  "versionNumber": 4,
  "embeddingStatus": "COMPLETED",
  "embeddingProfileKey": "bge-m3-1024",
  "embeddingFresh": true,
  "processingStatus": "COMPLETED",
  "sourceDeletedAt": null,
  "sourceNamespace": "cms-main",
  "documentRevision": 4,
  "embeddingAction": "QUEUED",
  "embeddingJobId": "67b62d78-9358-4fe4-b0f5-1fb8af34e2d5",
  "lifecycle": {
    "documentState": "ACTIVE",
    "searchability": "INDEXING",
    "localIndexStatus": "READY",
    "embeddingStatus": "INDEXING",
    "activeEmbeddingProfileKey": "bge-m3-1024",
    "activeJobId": "67b62d78-9358-4fe4-b0f5-1fb8af34e2d5",
    "lastErrorCode": null,
    "lastError": null,
    "retryable": true
  },
  "errorCode": null,
  "error": null
}
```

`action` 为 `CREATED`、`UPDATED` 或 `UNCHANGED`。嵌套的 lifecycle 是规范的可检索就绪契约：

- `localIndexStatus=READY`：当前关键词 chunk 已存在；
- `embeddingStatus=READY`：活动 Profile 的当前向量已存在；
- `searchability=READY`：两条分支都已针对当前正文；
- `searchability=KEYWORD_ONLY`：关键词分支当前可用，但向量分支处于
  `INDEXING`、`FAILED` 或 `NOT_REQUESTED`；
- `searchability=NOT_REQUESTED`：两条分支都未请求；
- `searchability=DISABLED`：文档已禁用或 tombstone。

兼容性的顶层 `embeddingStatus` 和 `embeddingFresh` 只描述远程 embedding 分支，
不能用来判断关键词检索是否可用。provider 失败不会回滚文档，也不会暴露旧 chunk：
新的本地 chunk 仍以 `KEYWORD_ONLY` 可检索，旧正文由 hash 和 generation freshness
排除。重放同一请求或调用 embedding 重试操作后，可以恢复为 `searchability=READY`。

生产默认 `strictExternalCas=true`：已有 identity 的新 revision 必须携带
`expectedSourceRevision`。服务会先判断精确重放，
所以客户端可以安全重试“请求已成功但响应丢失”的场景。同一 revision 对应不同受管
字段时返回 `409`；expected revision 与当前版本不匹配时也返回 `409`。新 identity 不提供
expected revision；兼容部署可以关闭严格 CAS，但不推荐 connector 依赖 last-write-wins。

### `POST /api/v1/rag/documents/batch-upsert`

请求体为 `{ "items": [ ... ] }`，最多 50 项，累计内容最多 5,000,000 个字符。每项
独立处理并保持输入顺序；某项可能以 `action=PERSISTENCE_FAILED` 表示持久化失败，
或以 `embeddingStatus=FAILED` 表示文档已持久化但 embedding 失败，不会回滚同一批中
已经成功的其他项。

### `POST /api/v1/rag/documents/relocate`

该端点默认关闭，通过 `RAG_DOCUMENT_RELOCATION_ENABLED=true` 开启。它只改变外部文档的
Collection 投放位置，不修改 namespace、external ID、source revision、正文或派生行，也
不会调用 embedding provider。调用方必须同时拥有源和目标 Collection 权限，并为每个业务
迁移生成一个 `Idempotency-Key` header；网络重试必须复用同一个 key。

```json
{
  "sourceCollectionKey": "customer-42:draft:v1",
  "targetCollectionKey": "customer-42:published:v1",
  "sourceNamespace": "cms-main",
  "externalId": "cms:article:10001",
  "expectedSourceRevision": "etag:8b4d9f"
}
```

成功返回同一个 `documentId`、保留的 `sourceRevision`、新的 document revision、目标
Collection、`derivationAction=PRESERVED` 和迁移后的真实 lifecycle。相同 principal/key/
请求会精确重放首次成功响应；同 key 不同请求返回
`409 IDEMPOTENCY_KEY_REUSED`。源或目标存在活动 Sync Run、revision/CAS 冲突、目标身份已
存在或已由其他迁移退休时返回稳定 `409`。

提交后旧地址被永久记录为 retired。旧地址的 lookup/upsert/delete/batch/Sync Run item
返回 `409 EXTERNAL_IDENTITY_RELOCATED`，防止延迟事件重建重复文档；只有同一文档的显式
反向 relocation 才会原子解除目标旧 marker。响应只在调用方仍具备目标 ACL 时披露目标
Collection key。

### `GET /api/v1/rag/documents/by-external-id`

按外部身份查询当前普通文档，不要求外部系统把内部 ID 作为自己的身份：

```text
GET /api/v1/rag/documents/by-external-id?collectionKey=customer-42%3Amanual%3Av3&sourceNamespace=cms-main&externalId=cms%3Aarticle%3A10001
```

响应沿用普通文档详情结构，并包含 `externalId`、`sourceRevision`、
`sourceDeletedAt`、处理状态和 embedding freshness。未授权 Collection 返回 `403`，
不存在的身份返回 `404`。

### `DELETE /api/v1/rag/documents/by-external-id`

记录外部来源删除，但保留稳定身份：

```text
DELETE /api/v1/rag/documents/by-external-id
  ?collectionKey=customer-42%3Amanual%3Av3
  &sourceNamespace=cms-main
  &externalId=cms%3Aarticle%3A10001
  &sourceRevision=etag%3Adeleted-9
  &expectedSourceRevision=etag%3A8b4d9f
```

该操作创建 tombstone（`enabled=false`、设置 `sourceDeletedAt`），返回 `DELETED`。
重放同一个删除版本返回 `UNCHANGED`。之后使用与 tombstone 不同的后续
`sourceRevision` upsert 可以恢复同一个内部 `documentId`。服务不比较 revision 的大小
或新旧；旧删除版本不能再次作为 upsert 重放。旧的
`DELETE /documents/{documentId}` 仍然是硬删除，语义不同。

### 推荐同步模式

1. 为每个 connector 固定 `sourceNamespace`，并从来源对象的不可变身份生成稳定 `externalId`。
2. 每次 upsert 和删除都携带来源当前的 opaque revision。
3. 保存响应中的 revision 和内部 ID 仅用于诊断；后续调用继续使用外部身份。
4. 如果投递可能重复或乱序，使用 `expectedSourceRevision`。遇到 `409` 时重新读取
   来源再同步，不要把它当成创建失败。
5. 将 `embeddingFresh=false` 或 `embeddingStatus=FAILED` 视为需要运维重试的状态；
   重放相同请求是安全的。
6. 每个 connector 使用独立 API Key，并限制到它负责的 Collection；不要把 root key
   分发给外部 connector。
7. 首次导入前固定 Collection 投放规则；不要通过修改普通 upsert 的 `collectionKey`
   模拟原子移动。

完整的增量投递、重试、checkpoint、dead-letter 和上线检查见
[外部文档同步 Client 指南](external-document-sync-client-guide-zh-CN.md)。可运行参考实现
位于 `examples/external-sync-client/`。

---

## 外部快照同步 Run

权威快照对账默认关闭，通过 `RAG_DOCUMENT_SYNC_RUNS_ENABLED=true` 开启。一个 run 绑定一个
`collectionKey + sourceNamespace`；lease token 通过 `X-RAG-Sync-Lease` 传入，但数据库只
保存其 SHA-256 hash。Run item 只保存身份、fingerprint、状态和错误信息，绝不保存正文、
JSONB payload 或 lease 明文。

### `POST /api/v1/rag/document-sync-runs`

开始一个 run，或者精确重放已有 run：

```json
{
  "collectionKey": "customer-42:catalog:v1",
  "sourceNamespace": "cms-main",
  "clientRunId": "catalog-cut-2026-08-19T12:00:00Z",
  "snapshotMode": "ONLINE_CUT",
  "missingPolicy": "TOMBSTONE",
  "leaseSeconds": 900
}
```

`snapshotMode` 与 `missingPolicy` 必须显式给出。`ONLINE_CUT` 只有在 connector 建立来源
一致性 cut 后才能使用 `TOMBSTONE`。`OFFLINE_MANIFEST` 只允许 `NONE`；reference client
对静态 manifest 使用这一安全组合。`EXCLUSIVE_OFFLINE + TOMBSTONE` 是危险的显式选项，
还必须发送 `"confirmExclusiveOffline": true`；这个字段在其他 mode/policy 组合中都会被
拒绝。一个 Collection 和 namespace 同时最多一个 active run。同 `clientRunId` 重放必须
使用相同 lease 和契约；不同 lease 或契约返回 `409`。每个 run mutation 都会重新检查当前
API Key 的 Collection ACL；lease token 不能绕过后续收紧的 ACL。

### `POST /api/v1/rag/document-sync-runs/{runId}/batch-upsert`

发送最多 100 个有界 item；Collection 和 namespace 继承自 run，item 必须包含稳定的
`externalId` 和 opaque `sourceRevision`，并使用已有 TEXT 或 JSON_RECORD 表示。精确重放
幂等；如果 item 在快照边界之后已被更新，返回 `SKIPPED_NEWER_MUTATION`，不会被旧快照覆盖。
失败 item 可用相同 fingerprint 重试。

### `GET /api/v1/rag/document-sync-runs/{runId}/items`

返回当前 run 已持久化的低敏 item 回执，适用于 batch 响应丢失后的恢复、失败项重试和
终态审计。该端点需要 `RAG_READ`，并重新验证 `collectionKey`、`sourceNamespace`、
run binding 与 Collection ACL；不需要也不接受 lease token。

```http
GET /api/v1/rag/document-sync-runs/{runId}/items
    ?collectionKey=customer-42:catalog:v1
    &sourceNamespace=cms-main
    &status=FAILED
    &limit=100
    &cursor=<opaque>
```

`status` 可选，取值为 `APPLIED`、`UNCHANGED`、`SKIPPED_NEWER_MUTATION` 或 `FAILED`。
`limit` 默认为 100，范围为 1–200。`cursor` 是最多 1024 字符、与 run 和 status filter
绑定的不透明 keyset cursor；它不是授权 token，Client 不应解析或记录它。响应始终带
`Cache-Control: no-store`。

```json
{
  "runId": "2e3be660-4c08-4d07-9607-7ccca4c0ae4e",
  "runStatus": "ACTIVE",
  "statusFilter": "FAILED",
  "items": [
    {
      "externalId": "record-42",
      "documentKind": "JSON_RECORD",
      "status": "FAILED",
      "documentId": null,
      "sourceRevision": "opaque-r7",
      "errorCode": "BAD_REQUEST",
      "error": "jsonbPayload is required",
      "seenAt": "2026-08-26T13:55:00Z"
    }
  ],
  "currentSummary": {
    "total": 101,
    "applied": 96,
    "unchanged": 2,
    "skippedNewerMutation": 1,
    "failed": 2
  },
  "limit": 100,
  "hasMore": false,
  "nextCursor": null
}
```

`currentSummary` 是 ledger 中每个唯一 item 的当前状态分布，不受本页或 `status` 过滤影响；
它与 run 响应中的累计处理计数语义不同。回执不返回 item fingerprint、正文、JSONB
payload、metadata、lease/hash、credential 或 provider 信息。`error` 在写入和读取时都会
脱敏并限制为 500 字符。

终态 run 的 cursor 遍历稳定。active run 仍可接收新 item 或更新失败项的 `seenAt`，
因此遍历只提供最终一致观察，可能重复或暂时遗漏；Client 应按 `externalId` 去重，并在
run 进入 `COMPLETED`、`ABORTED` 或 `EXPIRED` 后从无 cursor 起点重新完整扫描。

### `POST /api/v1/rag/document-sync-runs/{runId}/preview-missing`

返回有界的身份摘要、candidate fingerprint、按文档类型统计的数量，以及被更新 mutation
保护的对象数量。`complete` 必须携带 preview token；token 绑定当前 candidate fingerprint。

### `POST /api/v1/rag/document-sync-runs/{runId}/complete`

```json
{
  "previewToken": "preview 返回的 token",
  "confirmMissingCount": 1
}
```

当 `missingPolicy=TOMBSTONE` 时，preview 之后 candidate 数量必须保持不变。绝对数量/百分比
删除阈值用于防止不完整 manifest 造成大面积删除；超过阈值时必须显式提交相同的
`confirmMissingCount`。Reconciliation tombstone 会设置 `enabled=false` 和
`deletionOrigin=RECONCILIATION`，不会伪造 source revision。后续来源 upsert 或显式来源删除
仍走正常外部 CAS 路径。

如果当前 run 仍有状态为 `FAILED` 的 item，`missingPolicy=TOMBSTONE` 的 complete 会返回
`409 SYNC_RUN_INCOMPLETE`，不会执行任何 missing tombstone；client 必须先用相同 fingerprint
重试失败 item，或 abort 该 run。`missingPolicy=NONE` 的 run 可以在保留失败 item 的情况下
完成，因为它不会根据 missing 推断删除。

### `POST /api/v1/rag/document-sync-runs/{runId}/abort`

终止 active run。过期 lease 通过条件更新 fencing，不使用悲观数据库锁。
`GET /{runId}` 和 `GET /` 提供授权后的 run 状态和历史。

## 本地版本恢复

版本恢复默认关闭，通过 `RAG_DOCUMENT_VERSION_RESTORE_ENABLED=true` 开启。它只允许本地文档
和 `snapshotCompleteness=FULL` 的版本；外部文档仍由来源 connector 管理。

### `POST /api/v1/rag/documents/{documentId}/versions/{versionNumber}/restore`

```json
{
  "expectedDocumentRevision": 7,
  "embeddingPolicy": "ASYNC",
  "visibilityMode": "KEEP_CURRENT"
}
```

请求使用当前 document revision 作为 CAS token。恢复成功后创建新的业务 revision 和新的
`RESTORE` 版本，不回拨或删除后续历史。`visibilityMode=SNAPSHOT` 同时恢复快照的 enabled
状态；`KEEP_CURRENT` 保留当前可见性。正文变化的恢复会进入正常的新 generation 派生路径；
只恢复 metadata 时不会调用 embedding provider。

---

## Documents — Document Management

### `POST /api/v1/rag/documents`

创建文档。使用 `collectionKey` 关联 Collection；deprecated 的 `collectionId` 继续兼容。
同时提供两者时，必须指向同一个活动 Collection。

```json
{
  "title": "Introduction to Spring AI",
  "content": "Spring AI is...",
  "source": "manual",
  "documentType": "text",
  "metadata": {},
  "collectionKey": "customer-42:manual:v3"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `title` | string | ✅ | Document title |
| `content` | string | ✅ | Document content |
| `source` | string | | Source identifier |
| `documentType` | string | | Document type |
| `metadata` | object | | Extended metadata |
| `collectionKey` | string | | 推荐的稳定 Collection key |
| `collectionId` | long | | deprecated 数字兼容字段 |

文档详情、列表、版本、Collection-document 及创建响应在保留数字 ID 的同时，会在返回
Collection 身份的位置增加 `collectionKey`。

---

### `GET /api/v1/rag/documents`

Paginated document query.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `offset` | int | 0 | Number of documents to skip |
| `limit` | int | 20 | Maximum number of documents to return |
| `title` | string | | Optional title filter |
| `documentType` | string | | Optional document-type filter |
| `processingStatus` | string | | Optional processing-status filter |
| `enabled` | boolean | | Optional enabled-state filter |
| `collectionId` | long | | Deprecated Collection ID filter |
| `collectionKey` | string | | Preferred stable Collection key filter |
| `createdAfter` | timestamp | | Lower bound for `createdAt` |
| `createdBefore` | timestamp | | Upper bound for `createdAt` |

---

### `GET /api/v1/rag/documents/{id}`

按内部 ID 返回正文、metadata、`documentRevision` 和 lifecycle。`lifecycle.searchability`
为 `READY`、`KEYWORD_ONLY`、`INDEXING`、`FAILED`、`NOT_REQUESTED` 或 `DISABLED`。
请分别检查 `localIndexStatus` 和 `embeddingStatus`；`embeddingFresh=false` 不等于
关键词检索不可用。

---

### `PATCH /api/v1/rag/documents/{id}`

只用于本地管理文档的 presence-aware merge patch。必须发送
`expectedDocumentRevision`；可修改 `title`、`content`、`source`、`metadata` 和
`collectionKey`。未知字段或没有任何可变字段返回 `400`。

```json
{
  "expectedDocumentRevision": 3,
  "content": "更新后的正文",
  "embeddingPolicy": "ASYNC"
}
```

正文变化会在同一事务中增加 revision、记录完整快照、使旧派生结果 stale 并排队新任务。
只改标题、来源、metadata 或 Collection 不会调用 embedding provider。
显式 `expectedDocumentRevision` 已过期时返回 `409 DOCUMENT_REVISION_CONFLICT`；两个请求
同时通过前置校验、但在提交阶段发生乐观锁竞争时返回 `409 CONCURRENT_MODIFICATION`。
客户端对两者都应重新读取文档及最新 revision 后再决定是否重试，不能自动覆盖。

### `POST /api/v1/rag/documents/{id}/disable`

请求体为 `{"expectedDocumentRevision":4}`。禁用立即使文档退出全部检索并取消活动任务，
但保留正文、版本和派生数据以便恢复。

### `POST /api/v1/rag/documents/{id}/restore`

请求体包含 `expectedDocumentRevision` 和可选 `embeddingPolicy`。如果当前正文的派生结果
仍 fresh，恢复后直接 `READY`；否则按策略重建。

---

### `DELETE /api/v1/rag/documents/{id}`

本地文档永久删除。必须使用 query parameter
`expectedDocumentRevision`，并级联清理版本、状态和任务。外部托管文档必须通过来源
tombstone 端点操作，不能使用本地 PATCH/disable/restore/permanent-delete。

---

### `GET /api/v1/rag/documents/stats`

Get document statistics (total count, embedded count, etc.).

---

### `POST /api/v1/rag/documents/{id}/embed`

Generate embedding vectors for a specified document.

---

## Embedding Jobs — 持久化重嵌入任务

持久化、可取消和可重试的 embedding/reindex worker 默认开启，因为正文 mutation 的
`SYNC`/`ASYNC` 都依赖持久化任务。显式关闭后，正文 mutation 无法调度并返回
`503 EMBEDDING_JOBS_DISABLED`；读请求和不影响派生输入的更新仍可用。

### `POST /api/v1/rag/embedding-jobs`

按显式 document IDs 或 Collection scope 创建任务，两者必须二选一。成功返回
`202 Accepted`。同一 document/Profile/content hash 的活动任务会合并，并在 item 上
返回 `coalesced=true`。

```json
{
  "collectionScopeMode": "SELECTED_COLLECTIONS",
  "collectionKeys": ["customer-42:manual:v3"],
  "force": false,
  "maxAttempts": 3
}
```

Collection scope 最多展开 1000 个 enabled 文档；也可改为
`{"documentIds":[1,2,3],"force":true}`。所有目标都执行 API Key Collection ACL。

### 查询与控制

| 端点 | 说明 |
|------|------|
| `GET /api/v1/rag/embedding-jobs/{id}` | 获取一个授权可见的任务 |
| `GET /api/v1/rag/embedding-jobs?batchId=&status=&collectionKey=&page=0&size=50` | 分页过滤任务；先按 Collection ACL 过滤再 LIMIT |
| `GET /api/v1/rag/collections/embedding-readiness?collectionKey=` | Collection 就绪分类：fresh/queued/running/failed/stale，互斥计数 |
| `POST /api/v1/rag/embedding-jobs/{id}/cancel` | 请求取消 |
| `POST /api/v1/rag/embedding-jobs/{id}/retry?maxAttempts=4` | 重试 `FAILED`、`STALE` 或 `CANCELLED` 任务 |

状态为 `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED`、`STALE`。任务表不复制
文档正文，只记录 document/Profile/content hash/version、lease、重试和终态信息。

## Collection 派生完整性与受控修复

`GET /api/v1/rag/collections/derivation-readiness?collectionKey=` 返回活动 Profile 下的集合级
互斥摘要：`READY`、`KEYWORD_ONLY`、`INDEXING`、`NOT_REQUESTED`、
`LOCAL_UNAVAILABLE` 和 `CORRUPT`。分类会核对当前 local generation 的连续 chunk、hash、
chunker、文本/位置，以及向量与 local chunk 的一一对应和固定维度；旧
`embedding-readiness` 也复用相同物理真相源，不再只凭 state 和行数判定 fresh。

`GET /api/v1/rag/collections/derivation-readiness/documents` 接受 `collectionKey`、可选
`bucket`、`page` 和 `size`。`size` 限制为 1–100；响应不包含正文、metadata、JSON payload、
chunk 文本或向量，只返回受控状态、计数、最多 500 字符错误和推荐动作。

有副作用的修复默认关闭，通过 `RAG_DOCUMENT_DERIVATION_REPAIR_ENABLED=true` 开启：

| 端点 | 语义 |
|------|------|
| `POST /api/v1/rag/collections/derivation-repairs/preview` | 按 bucket/vector condition 生成最多 100 项的稳定计划；明文 token 只在本响应返回，数据库仅保存 hash |
| `POST /api/v1/rag/collections/derivation-repairs/apply` | 校验 `repairId + Collection + token + fingerprint + owner + ACL` 后执行；local 重建与 vector 排队使用独立短事务 |
| `GET /api/v1/rag/collections/derivation-repairs/{repairId}` | 中断或重启后读取持久化逐项结果和 embedding job ID；每次重新校验 owner 与 Collection ACL |

preview 默认 15 分钟内开始 apply，单个 operation 最长 1 小时，终态保留 24 小时。apply
不会同步循环调用 provider；它复用正式 local chunk 路径，并只把需要的 vector 工作持久化
入队。文档 revision/hash/Collection/可见性在 preview 后变化时该项返回
`SKIPPED_CHANGED`，不会对新状态执行旧计划。

---

---

### `POST /api/v1/rag/documents/batch`

批量创建文档。顶层 `collectionKey` 是所有 item 的默认 Collection；item 也可提供自己的
key。item 级身份覆盖默认值，但仍执行 ID/key 一致性和 ACL 校验。`embed=true` 表示在同一
请求中完成 embedding。可选 `embeddingPolicy` 覆盖 `embed`：`SYNC` / `ASYNC` /
`SKIP`。`ASYNC` 在同一事务入队并返回 `embeddingJobId`。

```json
{
  "collectionKey": "customer-42:manual:v3",
  "embed": true,
  "force": false,
  "documents": [
    { "title": "doc1", "content": "content 1" },
    {
      "title": "doc2",
      "content": "content 2",
      "collectionKey": "customer-42:faq:v1"
    }
  ]
}
```

顶层和 item 的 deprecated `collectionId` 字段继续兼容。
`POST /documents/batch/create-and-embed` 已 deprecated；请使用本端点并设置
`embed=true`。

---

### `DELETE /api/v1/rag/documents/batch`

Batch delete documents.

```json
{
  "documentIds": [1, 2, 3]
}
```

---

### `POST /api/v1/rag/documents/batch/embed`

Batch embed documents (documents must already exist).

```json
{
  "documentIds": [1, 2, 3]
}
```

---

### `POST /api/v1/rag/documents/upload`

Upload text files and embed in one step. Suitable for direct file submission from frontend.

**Content-Type:** `multipart/form-data`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `files` | MultipartFile[] | ✅ | File list (max 100) |
| `collectionKey` | string | No | 推荐的目标 Collection key |
| `collectionId` | long | No | deprecated 数字 Collection ID |
| `force` | boolean | No | `true` = force re-embed |

**Supported file types:** txt / md / json / xml / html / csv / log

**Response:**

```json
{
  "processed": 10,
  "success": 10,
  "failed": 0,
  "results": [
    {
      "filename": "Product Manual.txt",
      "documentId": 1,
      "title": "Product Manual",
      "embedded": true,
      "chunks": 5,
      "error": null
    }
  ]
}
```

---

<a id="pdf-与文件产物-api"></a>

### PDF 与文件产物 API

`POST /api/v1/rag/files/pdf` 转换一个 PDF，并把原始文件、`default.md` 和提取资源保存到
`fs_files` 中新的 UUID 路径下。它不会创建可检索的 RAG 文档。历史兼容表单字段
`collection` 当前会被忽略，也不是 RAG `collectionKey`。

`GET /api/v1/rag/files/tree?path=...` 返回直接文件和合成目录。每个条目包含：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | string | 最后一个路径段 |
| `path` | string | 完整虚拟路径 |
| `type` | string | `file` 或 `directory` |
| `mimeType` | string/null | 文件 MIME 类型；目录为 null |
| `size` | long | 文件字节数；目录为 0 |
| `createdAt` | timestamp/null | 文件存储时间；目录取其后代文件中的最新时间 |

`POST /api/v1/rag/files/{uuid}/embed` 读取 `{uuid}/default.md`，按稳定来源
`pdf-import:{uuid}/default.md` 创建或复用 RAG 文档，并触发 embedding。相同 UUID/source
重复调用会复用同一逻辑文档；不同 UUID 即使转换内容相同也创建不同文档。内容哈希继续用于
判断 embedding 新鲜度，不再承担 PDF 文件身份。`POST /api/v1/rag/files/pdf-to-rag`
合并导入和注册到 RAG。

数据层关系和 WebUI 行为见
[文件管理、PDF 导入与 RAG 联动](file-management-and-pdf-rag-zh-CN.md)。

#### PDF-to-RAG Collection 范围

以下 multipart 端点推荐使用 `collectionKey`，同时兼容 deprecated 的 `collectionId`：

- `POST /api/v1/rag/files/pdf-to-rag`
- `POST /api/v1/rag/files/{uuid}/embed`

`/pdf-to-rag` 的 `embed=false` 立即返回 JSON；`embed=true` 或省略 `embed` 时返回 SSE
进度流。`/{uuid}/embed` 的 `embed=sync` 返回 JSON，`embed=sse` 或省略时返回 SSE。
同时提供两个 Collection 标识时，两者必须一致。

`POST /api/v1/rag/files/pdf` 的历史兼容 `collection` 参数与此无关，且当前会被忽略；
导入始终使用 UUID 目录。它不是 RAG Collection 身份。

---

### `GET /api/v1/rag/documents/{id}/versions`

Get document version history (recorded automatically when content_hash changes, newest first).

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `page` | int | 0 | Page number |
| `size` | int | 20 | Page size |

**Response:**

```json
{
  "documentId": 1,
  "totalVersions": 5,
  "page": 0,
  "size": 20,
  "versions": [
    {
      "versionNumber": 5,
      "contentHash": "a1b2c3...",
      "title": "Introduction to Spring AI",
      "size": 2048,
      "changeType": "CONTENT_CHANGED",
      "createdAt": "2026-04-03T00:30:00Z"
    }
  ]
}
```

---

### `GET /api/v1/rag/documents/{id}/versions/{versionNumber}`

Get a specific version of a document (includes content snapshot).

**Response:**

```json
{
  "versionNumber": 3,
  "contentHash": "d4e5f6...",
  "title": "Introduction to Spring AI",
  "content": "Full content of version 3...",
  "size": 1024,
  "changeType": "INITIAL",
  "createdAt": "2026-04-02T10:00:00Z"
}
```

---

## Collections — Knowledge Base Management

### `POST /api/v1/rag/collections`

创建 Collection。`collectionKey` 必填并遵循前述身份契约。重复 key 返回 `409`，
软删除 Collection 保留的 key 也视为重复。

```json
{
  "collectionKey": "medical-knowledge-base",
  "name": "Medical Knowledge Base",
  "description": "Medical domain document collection",
  "embeddingModel": "BAAI/bge-m3",
  "dimensions": 1024,
  "enabled": true,
  "metadata": {}
}
```

响应同时包含内部 `id` 和外部 `collectionKey`。

自动化 provisioning 应为一个逻辑创建命令生成一个 `Idempotency-Key`，并在超时、响应
丢失、进程重启或切换到另一服务实例重试时复用同一个 key：

```http
POST /api/v1/rag/collections
Idempotency-Key: collection-create-20260826-0001
```

- 不带 header 的请求保持旧合同，成功返回 `200`。
- keyed 首次创建返回 `201`。
- 同一认证 owner 的精确 replay 返回 `200` 和
  `X-RAG-Idempotent-Replay: true`。
- 同 owner/key 携带不同有效创建语义时返回
  `409 IDEMPOTENCY_KEY_REUSED`。
- 非法 header 返回 `400 IDEMPOTENCY_KEY_INVALID`。
- keyed provisioning 被关闭或账本不可置信时 fail closed 返回 `503`，绝不退化为
  无幂等创建。

replay 返回 operation 绑定 Collection 的当前状态和当前文档数，因此会反映后续 update；
软删除后返回 `deleted=true`，但不会恢复或重建 Collection。replay 也不会重复写
Collection 创建审计。key 按服务端派生的 provisioning owner 隔离；数据库只保存 key
hash 和规范化请求 fingerprint，不保存原始 key 或请求体。

---

### `GET /api/v1/rag/collections`

Paginated collection query.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `offset` | int | 0 | Number of collections to skip |
| `limit` | int | 20 | Maximum number of collections to return |
| `name` | string | | Optional collection-name filter |
| `query` | string | | 对名称或原样存储 key 执行不区分大小写的子串匹配 |
| `enabled` | boolean | | Optional enabled-state filter |

受限 API Key 只能看到允许范围内的 Collection。

---

### By-Key 生命周期路由

以下为推荐的生命周期端点。`collectionKey` 位于 query parameter，必须进行 URL 编码：

- `GET /api/v1/rag/collections/by-key?collectionKey=...`
- `PUT /api/v1/rag/collections/by-key?collectionKey=...`
- `DELETE /api/v1/rag/collections/by-key?collectionKey=...`
- `POST /api/v1/rag/collections/by-key/restore?collectionKey=...`
- `GET /api/v1/rag/collections/by-key/documents?collectionKey=...`
- `POST /api/v1/rag/collections/by-key/documents?collectionKey=...`
- `GET /api/v1/rag/collections/by-key/export?collectionKey=...`

更新请求体只包含可变字段：`name`、`description`、`embeddingModel`、`dimensions`、
`enabled` 和 `metadata`。更新请求体出现 `collectionKey` 时返回 `400`，不支持重命名。

删除为软删除，并解除普通旧文档关联，但不会删除文档或 embedding。若 Collection 中
存在非空 `externalId` 的外部托管文档，则返回 `409`，因为解绑会破坏
`collectionKey + sourceNamespace + externalId` 稳定身份。需要永久删除整个 Collection
内容时，使用下方受保护的 preview/apply；不要用普通软删除模拟 purge。key 仍保持占用；
恢复沿用原 key，且不会自动恢复普通旧文档关联。

---

### 受保护的 Collection 清理与退役

该能力默认关闭。先读取
`GET /api/v1/rag/integration-capabilities`，只有
`features.optional.collectionPurge=true` 时才显示或调用。允许的身份是 environment root
或数据库 `ADMIN`；auth-disabled 模式还必须显式开启本地开发开关，且 Servlet 直接来源
必须是 loopback。该判断不信任 `Forwarded` 或 `X-Forwarded-For`。

先创建短期预览：

```http
POST /api/v1/rag/collections/by-key/purge/preview?collectionKey=customer-42%3Amanual%3Av3
```

响应不包含正文，但会返回待删除的文档、embedding、version、关键词派生、feedback、
审计和受影响 Chat 会话计数，以及一次性 `confirmationToken`、`fingerprint`、
Collection version、Chat commit fence version 和两个过期时间。调用方不得记录或持久化
明文 token。

确认实时计划未变化后提交：

```json
{
  "collectionKey": "customer-42:manual:v3",
  "previewId": "33333333-3333-4333-8333-333333333333",
  "confirmationToken": "returned-only-by-preview",
  "fingerprint": "sha256",
  "expectedCollectionVersion": 7,
  "expectedChatCommitFenceVersion": 12
}
```

```http
POST /api/v1/rag/collections/by-key/purge
```

apply 在一个事务中重新校验 owner、token、fingerprint、版本、活动 Sync Run/repair、
有效 Chat lease 和完整引用索引。计划改变、存在活动写入或超过配置上限时返回 `409`，
不会部分删除。成功结果为 `status=RETIRED`，并返回删除文档数、`deletedAt`、`purgedAt`
和最终 Collection version。结果保留窗口内精确重试同一 preview 会返回相同最小结果；
窗口外返回稳定的 preview unavailable/expired 冲突。

清理会物理删除 Collection 下全部本地、外部和 JSON 文档，以及它们的 embedding、任务、
版本、关键词分块和派生状态。引用目标文档的 feedback 会整体删除；曾引用目标文档的持久化
Chat 会话会按 owner/session 整体删除 history、Spring AI memory、summary 和 turn replay，
避免保留回答或工具结果中的正文摘录。精确归属的 Document/Collection 内容审计也会删除。

Collection 行保留为最小退役 tombstone：ID、永久 `collectionKey`、技术 embedding profile、
版本和时间保留，name 固定化，description/metadata 清空。key 永久不能复用；这不是稳定
标识匿名化。`fs_files` 属于独立文件子系统，没有可靠 Collection 外键，因此不会根据文件名
或路径猜测并删除。退役后 restore、写入、显式检索/Chat、导出和 clone 返回
`409 COLLECTION_ALREADY_RETIRED`；未显式指定 Collection 的检索只排除退役 tombstone。

主要错误：

- `403 COLLECTION_PURGE_FORBIDDEN`
- `409 COLLECTION_PURGE_CONFLICT`
- `409 COLLECTION_PURGE_PREVIEW_EXPIRED`
- `409 COLLECTION_PURGE_CONFIRMATION_INVALID`
- `409 COLLECTION_ALREADY_RETIRED`
- `503 COLLECTION_PURGE_DISABLED`

---

### `POST /api/v1/rag/collections/clone`

使用稳定的源 key 和目标 key 克隆 Collection：

```json
{
  "sourceCollectionKey": "customer-42:manual:v3",
  "collectionKey": "customer-42:manual:v4"
}
```

目标 key 必填且必须未被占用。文档以待处理状态复制，embedding 不会复制。受限 API Key
不能创建、导入或克隆 Collection。

---

### Collection 文档

列出文档：

```http
GET /api/v1/rag/collections/by-key/documents?collectionKey=customer-42%3Amanual%3Av3
```

把一个已有文档关联到 Collection：

```json
{
  "documentId": 1,
  "expectedDocumentRevision": 3
}
```

请求地址：

```http
POST /api/v1/rag/collections/by-key/documents?collectionKey=customer-42%3Amanual%3Av3
```

`rag_documents.collection_id` 是单值外键，因此该操作对已经属于其他 Collection 的普通
文档表现为重关联/迁移，不需要重新嵌入。调用方必须同时有原文档和目标 Collection 的访问
权。`expectedDocumentRevision` 必填且必须等于文档当前公开 revision；过期写入返回 `409`。
`externalId` 非空的外部托管文档会返回 `409`；此类文档必须继续通过稳定的
`collectionKey + sourceNamespace + externalId` 同步，不能用兼容关联接口改变身份命名空间。

---

### Collection 导出与导入

导出地址：

```http
GET /api/v1/rag/collections/by-key/export?collectionKey=customer-42%3Amanual%3Av3
```

**Response:**

```json
{
  "collectionKey": "customer-42:manual:v3",
  "name": "Medical Knowledge Base",
  "description": "Medical domain document collection",
  "embeddingModel": "BAAI/bge-m3",
  "dimensions": 1024,
  "enabled": true,
  "metadata": {},
  "documents": [
    {
      "title": "doc1",
      "content": "...",
      "source": "manual",
      "documentType": "text",
      "metadata": {},
      "size": 1024
    }
  ],
  "exportedAt": "2026-04-03T00:00:00Z",
  "documentCount": 10
}
```

---

### `POST /api/v1/rag/collections/import`

从导出的 JSON 数据创建新 Collection 并导入文档。`collectionKey` 必填。导出结果保留
源 key，而 key 全局唯一，因此作为第二个 Collection 导入前必须修改 key。

**请求体：** 使用 `/export` 返回的 JSON，并设置目标 `collectionKey`。

**Response:**

```json
{
  "id": 5,
  "collectionKey": "customer-42:manual:import-2026-08",
  "name": "Medical Knowledge Base",
  "importedDocuments": 10,
  "documentCount": 10
}
```

---

### Deprecated 数字 Collection 路由

以下兼容路由继续可用，但在 OpenAPI 中标记为 deprecated：

- `GET`、`PUT`、`DELETE /api/v1/rag/collections/{id}`
- `POST /api/v1/rag/collections/{id}/restore`
- `POST /api/v1/rag/collections/{id}/clone?collectionKey=...`
- `GET /api/v1/rag/collections/{id}/documents`
- `POST /api/v1/rag/collections/{id}/documents`
- `GET /api/v1/rag/collections/{id}/export`

这些路由与推荐路由使用相同的 ACL、不可变、软删除和目标 key 规则。

---

## Evaluation — Retrieval Evaluation

### `POST /api/v1/rag/evaluation/evaluate`

Execute a single retrieval evaluation.

---

### `POST /api/v1/rag/evaluation/batch`

Execute batch evaluations.

---

### `GET /api/v1/rag/evaluation/metrics/calculate`

Calculate retrieval metrics (Precision, Recall, MRR, etc.).

---

### `GET /api/v1/rag/evaluation/report`

Get evaluation report.

---

### `GET /api/v1/rag/evaluation/history`

Get evaluation history.

---

### `GET /api/v1/rag/evaluation/metrics/aggregated`

Get aggregated metrics.

---

### `POST /api/v1/rag/evaluation/feedback`

Submit user feedback.

```json
{
  "query": "What is RAG?",
  "feedbackType": "helpful",
  "comment": "Accurate answer",
  "rating": 5
}
```

---

### `GET /api/v1/rag/evaluation/feedback/stats`

Get feedback statistics.

---

### `GET /api/v1/rag/evaluation/feedback/history`

Get feedback history.

---

### `GET /api/v1/rag/evaluation/feedback/type/{feedbackType}`

Query feedback by type.

---

## 受管质量套件

设置 `RAG_EVALUATION_MANAGED_SUITES_ENABLED=true` 后可用。关闭时返回
`503 EVALUATION_SUITES_DISABLED`。suite version 一经创建不可变；相关文档必须使用
`collectionKey + sourceNamespace + externalId`，不得使用内部文档 ID 作为长期基准。
省略 `sourceNamespace` 时兼容为 `default`。

| 端点 | 说明 |
|------|------|
| `POST /api/v1/rag/evaluation/suites` | 创建套件 |
| `GET /api/v1/rag/evaluation/suites` | 列出当前 principal 的套件 |
| `GET /api/v1/rag/evaluation/suites/{suiteKey}` | 查询当前 principal 拥有的单个套件 |
| `POST /api/v1/rag/evaluation/suites/{suiteKey}/versions` | 导入不可变 version |
| `POST /api/v1/rag/evaluation/runs` | 创建 PENDING run |
| `GET /api/v1/rag/evaluation/runs/{runId}` | 查询 run 与 case 结果 |
| `GET /api/v1/rag/evaluation/runs/compare` | 比较同一 version 的两次 run |
| `POST /api/v1/rag/evaluation/semantic` | 可选 Spring AI FactChecking/Relevancy 适配；不可用时 `DISABLED` |
| `POST /api/v1/rag/evaluation/semantic/batch` | 同一适配器的有界批量接口 |

citation 校验只检查 `[S1]` token。compare 在 embedding profile、代码修订或语料快照
不同时标记 `environmentDrift`，不把漂移报告成质量提升。

---

## A/B Tests — Experiment Management

### `POST /api/v1/rag/ab/experiments`

Create an A/B experiment.

### `PUT /api/v1/rag/ab/experiments/{id}`

Update an experiment.

### `POST /api/v1/rag/ab/experiments/{id}/start`

Start an experiment.

### `POST /api/v1/rag/ab/experiments/{id}/pause`

Pause an experiment.

### `POST /api/v1/rag/ab/experiments/{id}/stop`

Stop an experiment.

### `GET /api/v1/rag/ab/experiments/running`

Get running experiments.

### `GET /api/v1/rag/ab/experiments/{id}/variant`

Get experiment variant assignment.

### `POST /api/v1/rag/ab/experiments/{id}/results`

Record experiment results.

### `GET /api/v1/rag/ab/experiments/{id}/analysis`

Get experiment analysis report.

### `GET /api/v1/rag/ab/experiments/{id}/results`

Get experiment results list.

---

## Alerts — Monitoring & Alerting

本节全部路由属于 operator 管理面。允许 environment root、数据库 `ADMIN`、legacy static，
以及关闭认证时的 direct loopback；数据库 `NORMAL` principal 会在读取告警数据前收到通用
`403 FORBIDDEN`。

### `GET /api/v1/rag/alerts/active`

返回所有 active 告警。V57 的 `API_PRINCIPAL_EXPIRY` 受管告警会输出服务端权威
`conditionState` 和低敏 `metrics`：

```json
[{
  "id": 42,
  "alertType": "API_PRINCIPAL_EXPIRY",
  "alertName": "Managed API principal expiry",
  "message": "Managed API principal rag_p_example is in WARNING phase and expires at 2026-09-20T00:00+08:00[Asia/Shanghai]",
  "severity": "WARNING",
  "status": "ACTIVE",
  "conditionState": "WARNING",
  "metrics": {
    "principalId": "rag_p_example",
    "principalRole": "NORMAL",
    "expiresAt": "2026-09-20T00:00+08:00[Asia/Shanghai]",
    "timeZone": "Asia/Shanghai",
    "phase": "WARNING",
    "secondsRemaining": 1987200,
    "policyVersion": 3
  },
  "firedAt": "2026-08-27T12:00:00+08:00"
}]
```

同一 principal 同时最多一条 active 到期告警；阶段在原行上升级。expiry 延期出窗口或
principal 吊销后自动解决。响应不包含 raw credential、hash、principal name、Collection
allow-list、quota 或业务 payload。时间字段名是 `firedAt`，不是 `triggeredAt`。

### `GET /api/v1/rag/alerts/notification-deliveries`

按 keyset 游标查询 V58 durable provider delivery receipt。可选参数：

- `status`：`PENDING`、`IN_PROGRESS`、`RETRY_WAIT`、`DELIVERED`、`FAILED`、
  `SUPERSEDED`；
- `provider`：`EMAIL` 或 `DINGTALK`；
- `alertId`：正整数；
- `limit`：1–100，默认 50；
- `cursor`：上页返回的不透明 `nextCursor`，与当前过滤条件绑定。

```json
{
  "notificationsEnabled": true,
  "durableDeliveryEnabled": true,
  "configuredProviders": ["DINGTALK"],
  "items": [{
    "id": "8abf1f68-7ed4-4fca-b33e-2c9cb22c8d87",
    "alertId": 42,
    "notificationVersion": 2,
    "provider": "DINGTALK",
    "status": "DELIVERED",
    "attemptCount": 2,
    "attemptBudget": 8,
    "manualRetryCount": 0,
    "nextAttemptAt": "2026-08-28T09:00:00+08:00",
    "lastErrorCode": null,
    "lastHttpStatus": 200,
    "lastAttemptAt": "2026-08-28T09:00:31+08:00",
    "deliveredAt": "2026-08-28T09:00:31+08:00",
    "createdAt": "2026-08-28T09:00:00+08:00",
    "updatedAt": "2026-08-28T09:00:31+08:00"
  }],
  "limit": 50,
  "hasMore": false,
  "nextCursor": null
}
```

envelope 的三个模式字段使空页仍可区分全局通知关闭、兼容直发、未配置 provider 和
durable mode 暂无 receipt。响应不返回 provider endpoint、收件人、payload、lease、
secret、错误正文或堆栈。

### `POST /api/v1/rag/alerts/notification-deliveries/{deliveryId}/retry`

对 `FAILED` delivery 增加一个当前配置大小的 attempt budget 周期并重新入队；
`attemptCount` 不清零。`PENDING`、`RETRY_WAIT`、`IN_PROGRESS` 返回当前 receipt，
不会重复入队。`DELIVERED`、`SUPERSEDED`、provider 不可用，或 managed source
状态已经过期时返回 `409 ALERT_NOTIFICATION_DELIVERY_CONFLICT`；过期 managed receipt
会先持久化为 `SUPERSEDED`。成功与幂等响应均为 `200`。

### `GET /api/v1/rag/alerts/history`

查询告警历史；支持现有时间、severity 和 alert type 过滤。

### `GET /api/v1/rag/alerts/stats`

Get alert statistics.

### `POST /api/v1/rag/alerts/{alertId}/resolve`

Resolve an alert.

### `POST /api/v1/rag/alerts/silence`

Silence an alert.

### `POST /api/v1/rag/alerts/fire`

Manually trigger an alert (for testing).

### `GET /api/v1/rag/alerts/slos`

Get all SLO definitions.

### `GET /api/v1/rag/alerts/slos/{sloName}`

Get details of a specific SLO.

---

## Health — Health Checks

### `GET /api/v1/rag/health`

Service health check.

**Response:**

```json
{
  "status": "UP",
  "timestamp": "2026-08-15T16:00:00Z",
  "components": {
    "database": "UP",
    "pgvector": "UP",
    "tables": "UP",
    "cache": "UP"
  }
}
```

详细组件接口为 `GET /api/v1/rag/health/components`，会返回相同的组件名称以及延迟、
扩展、表计数和缓存详情。

---

## Cache — Cache Monitoring

### `GET /api/v1/rag/cache/stats`

Get embedding cache statistics.

**Response:**

```json
{
  "hitCount": 1523,
  "missCount": 478,
  "hitRate": 0.761,
  "totalRequests": 2001,
  "cacheSize": 342,
  "timestamp": "2026-04-03T10:00:00Z"
}
```

---

## Metrics — RAG Metrics Monitoring

### `GET /api/v1/rag/metrics`

Get RAG service key metrics summary (request count, success rate, retrieval result count, token consumption).

**Response:**

```json
{
  "totalRequests": 1523,
  "successfulRequests": 1498,
  "failedRequests": 25,
  "successRate": 0.984,
  "totalRetrievalResults": 8764,
  "totalLlmTokens": 245321
}
```

**Field descriptions:**

| Field | Type | Description |
|-------|------|-------------|
| `totalRequests` | long | Total requests since service startup |
| `successfulRequests` | long | Successful requests (LLM returned normally) |
| `failedRequests` | long | Failed requests (LLM call exception) |
| `successRate` | double | Success rate (successful/total) |
| `totalRetrievalResults` | long | Cumulative retrieval result count |
| `totalLlmTokens` | long | Cumulative LLM token consumption |

---

### `GET /api/v1/rag/usage`

按包含边界的 UTC 日期范围聚合持久化模型调用用量。本端点要求
`RAG_READ`。普通数据库 principal 只能查询自身事件；ADMIN 或
environment root 可以查询全部 principal，或通过 `principalId` 查询指定
principal。

默认范围为最近 30 个 UTC 日历日，最大范围为 366 天。`from` 和 `to`
使用 `YYYY-MM-DD`，两端都包含。成本是调用开始时捕获的模型价格快照
计算出的配置估算，不是 provider 账单、计费记录或 hard-limit 结算数据。

**查询参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `from` | date | 否 | UTC 起始日期（包含）；默认取 `to` 往前 30 天 |
| `to` | date | 否 | UTC 结束日期（包含）；默认取当前 UTC 日期 |
| `principalId` | string | 否 | 查询主体；只有 ADMIN/root 可以选择其他主体 |

**响应：**

```json
{
  "recordingEnabled": true,
  "localLostEventsSinceStart": 0,
  "scope": {
    "type": "SELF",
    "principalId": "rag_p_service"
  },
  "from": "2026-08-01",
  "to": "2026-08-27",
  "totals": {
    "logicalExecutionCount": 12,
    "invocationCount": 18,
    "succeededCount": 16,
    "failedCount": 1,
    "cancelledCount": 1,
    "promptTokens": 12500,
    "completionTokens": 3800,
    "totalTokens": 16300,
    "usageAvailableCount": 17,
    "usageUnavailableCount": 1,
    "pricingUnavailableCount": 2,
    "costUnavailableCount": 3
  },
  "costs": [
    {
      "unit": "CONFIGURED_MODEL_COST",
      "configuredCost": 0.24560000,
      "invocationCount": 18,
      "costAvailableCount": 15
    }
  ],
  "byModel": [
    {
      "modelRef": "openrouter/example-model",
      "totals": {}
    }
  ],
  "byPurpose": [
    {
      "purpose": "CHAT",
      "totals": {}
    }
  ],
  "byMode": [
    {
      "mode": "KNOWLEDGE",
      "totals": {}
    }
  ],
  "byDay": [
    {
      "day": "2026-08-27",
      "totals": {}
    }
  ]
}
```

每个嵌套 `totals` 对象与顶层 `totals` 使用相同字段。
`byModel`、`byPurpose`、`byMode` 和 `byDay` 按稳定的升序返回。
purpose 值为 `CHAT`、`QUERY_TRANSFORM`、`QUERY_EXPAND`、`SUMMARY`；
mode 值为 `PLAIN`、`KNOWLEDGE`、`AGENT`。

`localLostEventsSinceStart` 统计当前进程因有界 recorder 超时、被拒绝或
数据库失败而无法确认记账的事件。它是进程本地指标，不等同于全局持久化
的丢失事件计数。provider 没有返回 usage 或模型没有配置价格时，对应
计数会增加，token/cost 不会被猜测为零。账本不会保存 prompt、answer、
工具 payload、凭据或异常正文。

---

## Models — 运行时选模

### `GET /api/v1/rag/models`

获取可写入 `ChatRequest.model` 的 provider/model 引用。

**响应：**

```json
{
  "multiModelEnabled": true,
  "defaultProvider": "minimax",
  "defaultModel": "minimax/MiniMax-M2.7",
  "availableProviders": ["openrouter", "minimax"],
  "fallbackChain": ["openrouter/xiaomi/mimo-v2-pro"],
  "models": [
    {
      "ref": "openrouter/xiaomi/mimo-v2-pro",
      "provider": "openrouter",
      "providerName": "OpenRouter",
      "modelId": "xiaomi/mimo-v2-pro",
      "name": "MiMo V2 Pro",
      "apiType": "openai-completions",
      "available": true,
      "reasoning": false,
      "contextWindow": 600000,
      "maxTokens": 32000,
      "capabilities": {
        "streaming": true,
        "toolCalling": true
      },
      "source": "configured"
    },
    {
      "ref": "minimax/MiniMax-M2.7",
      "provider": "minimax",
      "providerName": "MiniMax",
      "modelId": "MiniMax-M2.7",
      "name": "MiniMax M2.7",
      "apiType": "anthropic-messages",
      "available": true,
      "reasoning": false,
      "contextWindow": 200000,
      "maxTokens": 8192,
      "capabilities": {
        "streaming": true,
        "toolCalling": false
      },
      "source": "configured"
    }
  ]
}
```

已配置但缺少凭据的模型仍会返回，并带有 `available: false` 和
`unavailableReason`。

省略 `capabilities.streaming` 时为兼容旧配置默认按 `true` 处理。
`capabilities.toolCalling` 默认 `false`，只有在具体上游模型/端点验证支持后才应显式
开启。`AGENT` 模式要求 Tool Calling；WebUI 也使用该字段禁用不兼容选择。

### `GET /api/v1/rag/models/{provider}`

获取 provider 摘要及其模型级条目。

**路径参数：**

| 参数 | 类型 | 说明 |
|------|------|------|
| `provider` | string | Provider 标识，例如 `openrouter` 或 `minimax` |

**Provider 存在时：**

```json
{
  "available": true,
  "details": {
    "provider": "openrouter",
    "available": true,
    "displayName": "OpenRouter",
    "models": [
      {
        "ref": "openrouter/xiaomi/mimo-v2-pro",
        "modelId": "xiaomi/mimo-v2-pro",
        "available": true,
        "capabilities": {
          "streaming": true,
          "toolCalling": true
        }
      }
    ]
  }
}
```

**Provider 不存在：** `404 Not Found`

### `POST /api/v1/rag/models/compare`

并行比较多个模型引用的响应。

**请求体：**

```json
{
  "query": "解释量子计算的基本原理",
  "providers": [
    "openrouter/xiaomi/mimo-v2-pro",
    "minimax/MiniMax-M2.7"
  ],
  "timeoutSeconds": 30
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `query` | string | ✅ | 问题文本 |
| `providers` | string[] | ✅ | 要比较的 provider 或 `provider/model` 引用 |
| `timeoutSeconds` | int | ❌ | 单模型超时秒数，默认 30 |

**响应：**

```json
{
  "query": "解释量子计算的基本原理",
  "providers": [
    "openrouter/xiaomi/mimo-v2-pro",
    "minimax/MiniMax-M2.7"
  ],
  "results": [
    {
      "modelName": "openrouter/xiaomi/mimo-v2-pro",
      "success": true,
      "response": "Quantum computing is...",
      "latencyMs": 1200,
      "promptTokens": 50,
      "completionTokens": 180,
      "totalTokens": 230,
      "error": null
    },
    {
      "modelName": "minimax/MiniMax-M2.7",
      "success": true,
      "response": "The core of quantum computing is...",
      "latencyMs": 950,
      "promptTokens": 50,
      "completionTokens": 165,
      "totalTokens": 215,
      "error": null
    }
  ]
}
```

### `GET /api/v1/rag/metrics/models`

获取每个模型的调用次数和错误率。

**响应：**

```json
{
  "multiModelEnabled": true,
  "models": [
    {
      "provider": "openai",
      "calls": 1523,
      "errors": 25,
      "errorRate": 0.016,
      "displayName": "OpenAI (DeepSeek/Compatible)"
    },
    {
      "provider": "minimax",
      "calls": 234,
      "errors": 3,
      "errorRate": 0.013,
      "displayName": "MiniMax"
    }
  ]
}
```
