# Collection 创建持久化幂等性实施规划

> **状态**：规划已封板（连续 `3/3` 无修改审查通过），尚未开始生产代码实施
>
> **规划日期**：2026-08-26
>
> **规划基线**：`main` / `origin/main` @ `61c728c2`；Spring Boot `3.5.16`；
> Spring AI `1.1.8`；Java `21`；Flyway `V1-V51`
>
> **规划工作区**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
>
> 本文是当前仓库的单语过程文档。它描述通用 RAG 服务的 Collection provisioning
> 可靠性，不依赖任何外部项目的名称、领域模型、私有协议或部署背景。实施完成后，稳定
> 事实提升到双语长青文档，本文与配套进度账本归档。

## 1. 执行摘要

本轮为既有 Collection 创建 API 增加可选、按认证调用方隔离、可跨实例和重启恢复的
`Idempotency-Key`：

```text
POST /api/v1/rag/collections
```

当前 `collectionKey` 已是全局唯一、不可变、软删除后不复用的稳定业务身份，但它只能防止
数据库中出现两个同 key Collection。调用方在“服务端已经提交、响应在网络中丢失”的情况下
重试，只能得到 `409 DUPLICATE_RESOURCE`，无法自动判断：

- 这是自己上一请求的成功结果；
- 还是另一个请求、另一个 operator 或另一套自动化创建的同 key Collection；
- 原始请求与当前重试是否具有完全相同的有效语义。

新增能力以 `owner + idempotency-key hash` 记录成功创建操作，并把请求的规范化语义摘要与
Collection ID 放在同一事务中提交。精确重试返回既有 Collection，不重新创建、不恢复软删除
资源，也不在 replay 时重复写创建审计；同一个 owner/key 携带不同有效请求则稳定返回
`409 IDEMPOTENCY_KEY_REUSED`。

本轮是 control-plane 可靠性补强，不改变 Collection ACL、文档同步、embedding、Chat、
Tool Calling 或检索语义。它复用 V50 principal provisioning 已验证的 header 规则、owner
隔离、数据库唯一约束和有界竞争恢复模式，但使用独立表、独立配置与独立服务，避免两类资源
共享错误生命周期。WebUI 当前会对网络错误和 `5xx` 自动重试 POST，因此 Collection create
client 也必须在一次用户提交开始时生成 key，并让 Axios 的全部自动重试复用同一 key。

## 2. 为什么这是当前最高价值缺口

### 2.1 已交付能力排除了旧候选

截至规划基线，通用业务 Client 的以下生产接入能力已经交付：

- 稳定 API principal、版本化 credential、即时吊销和 PostgreSQL 共享 quota；
- `RAG_READ` / `RAG_WRITE` operation-scoped capability 强制执行；
- restricted principal、Collection allow-list 与 `/auth/me` 自省；
- API principal 创建的可选 `Idempotency-Key`；
- `/integration-capabilities` 机器可读运行时合同；
- JSON Record 外部三元身份、revision CAS、exact replay、tombstone/restore；
- Document Sync Run、持久 item receipt 与游标状态查询。

Collection 创建是剩余 provisioning 链中的第一步。业务 Client 或 operator 自动化通常需要：

```text
ensure Collection
  -> ensure restricted principal
  -> verify binding
  -> start data-plane delivery
```

后半段已经可安全重试，第一步仍要求人工通过 by-key 查询对账。补齐 Collection create
idempotency 后，整条控制面才可以在超时、进程重启、双实例和部署重试下自动收敛。

### 2.2 当前失败场景

```text
client -> POST /collections (collectionKey=tenant-42:records:v1)
server -> rag_collection committed
network X response lost
client -> retry the same POST
server -> 409 DUPLICATE_RESOURCE
```

`409` 只能证明全局 key 已存在，不能证明重试者拥有原始操作。调用方如果把所有 duplicate
都当成功，会把由错误配置、跨环境 key 冲突或另一 operator 创建的资源误绑定；如果把所有
duplicate 都当失败，则必须人工查询和比较，无法形成无人值守 provisioning。

`GET /collections/by-key` 也不能替代幂等账本：Collection 的 name、description、enabled、
metadata 等字段可以在创建后被更新，当前状态不再等于原始创建请求；仅比较当前资源无法证明
原始请求是否一致。

### 2.3 为什么不只依赖 `collectionKey`

`collectionKey` 是资源身份，`Idempotency-Key` 是一次命令的身份，两者解决不同问题：

- 相同 `collectionKey`、不同 owner：应保持 `DUPLICATE_RESOURCE`，不能跨 owner 认领结果；
- 相同 owner/key、不同请求：应返回 `IDEMPOTENCY_KEY_REUSED`；
- 相同 owner/key、相同请求：应重放原操作绑定的 Collection；
- 不同 idempotency key、相同 `collectionKey`：仍是普通 duplicate；
- 账本超过保留期后，同 key 再次使用不再有 replay 保证，Collection 全局唯一约束仍阻止
  重复创建。

## 3. 已核对的代码、数据与契约事实

### 3.1 当前调用链

```text
RagCollectionController.create(...)
  -> ApiKeyCollectionAccess.requireCollectionCreationAllowed(...)
  -> RagCollectionService.createCollection(...)
     -> existsByCollectionKey(...)
     -> saveAndFlush(rag_collection)
  -> controller writes one CREATE audit event
  -> 200 + Collection map
```

代码锚点：

- `spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagCollectionController.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/service/RagCollectionService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagCollection.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/repository/RagCollectionRepository.java`
- `spring-ai-rag-api/src/main/java/com/springairag/api/dto/CollectionRequest.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/util/CollectionMapper.java`

近距离长青入口：

- [REST API：Collection 管理](../../rest-api-zh-CN.md#collections--knowledge-base-management)
- [业务服务接入指南](../../business-client-integration-zh-CN.md)
- [项目上下文](../../project-context-zh-CN.md)
- [交付工作流](../../delivery-workflow-zh-CN.md)

### 3.2 Collection 当前语义

- `collection_key` 在 V27/V28 后为非空、全局唯一、最大 128 个可见 ASCII 字符；
- JPA 将 `collectionKey` 标记为 `updatable=false`；
- 删除是软删除，`collectionKey` 不释放、不复用；
- 创建时 `dimensions=null` 的有效值为 `1024`，`enabled=null` 的有效值为 `true`；
- metadata 以 PostgreSQL `jsonb` 持久化；
- controller 当前返回 `CollectionMapper.toMap(collection, 0)`；
- 无 header 的既有创建成功状态码为 `200`，虽然 REST 语义更适合 `201`，本轮不能破坏旧
  Client；
- restricted principal 不能创建、导入或克隆 Collection；root、legacy/auth-disabled
  和 unrestricted principal 沿用现有授权行为；
- controller 负责普通 create 的创建审计，service 不写该审计。

### 3.3 可复用的 V50 模式

API principal provisioning 已提供：

- 单值、trim OWS、1-255 visible ASCII 且禁逗号/空白的 `Idempotency-Key` 校验；
- raw key 只计算 SHA-256 后进入账本；
- 服务端认证上下文派生 owner；
- canonical request fingerprint；
- `owner + key hash` 唯一约束；
- 同一事务写资源和成功 operation；
- 数据库唯一竞争后的有界重读；
- retention cleanup；
- 首次 `201`、replay `200` 和 `X-RAG-Idempotent-Replay: true`。

本轮复用原则和通用工具，但不复用 `rag_api_provisioning_operation` 表。principal 的 secret
一次性展示、rotation/revoke 等生命周期与 Collection 完全不同，共表会产生错误字段、清理
和恢复语义。

## 4. 目标、非目标与冻结决策

### 4.1 目标

1. 为 `POST /api/v1/rag/collections` 增加可选单值 `Idempotency-Key`。
2. 无 header 路径保持当前授权、body、错误和 `200` 成功状态码。
3. keyed 首次创建返回 `201`；精确 replay 返回 `200` 和
   `X-RAG-Idempotent-Replay: true`。
4. owner 只从已认证请求上下文派生，调用方不能通过 body/header/query 覆盖。
5. fingerprint 反映实际持久化语义，而不是原始 JSON 文本顺序。
6. Collection 与成功 operation 在同一事务提交；任何一侧失败都不得留下半条事实。
7. 并发同 owner/key 由数据库唯一约束和有界重试收敛，不使用悲观锁、`SKIP LOCKED` 或
   advisory lock。
8. replay 返回 operation 绑定 Collection 的**当前状态**；资源已软删除时明确返回
   `deleted=true`，不恢复、不重建、不改 key。
9. replay 不写第二条 Collection create audit；现有审计仍保持 best-effort、非业务事务
   真相源的边界。
10. capability endpoint 以 additive 字段声明 Collection create idempotency。
11. 通过真实 PostgreSQL、真实双实例 HTTP、重启恢复、owner 隔离和既有业务 Client
    生命周期门禁证明能力。
12. WebUI 每次用户提交只生成一个 idempotency key；Axios 自动重试复用它，新的用户提交
    使用新 key。

### 4.2 非目标

- 不让 `Idempotency-Key` 成为 `collectionKey` 的替代品；
- 不改变 Collection key 的全局唯一、不可变和软删除保留规则；
- 不为 import、clone、restore、update 或 delete 增加本轮幂等键；
- 不自动把历史 Collection 绑定到新的 idempotency operation；
- 不根据当前 Collection 字段猜测一个历史 duplicate 是否为精确重试；
- 不保存原始 `Idempotency-Key`、Authorization、API Key、完整请求 JSON 或响应 snapshot；
- 不允许跨 owner 查询或重放 operation；
- 不引入分布式锁、数据库悲观锁、advisory lock、无限重试或后台补偿 saga；
- 不在 WebUI 暴露幂等键输入框；key 由 API client 内部生成，用户不读取、不编辑、不保存；
- 不改变 Chat/LLM/embedding 执行路径，也不把真实 LLM 结果当作本功能正确性的主要证据；
- 不升级 integration capability protocol major/minor；本轮字段为 `1.0` 下的 additive
  扩展。

### 4.3 推荐默认与可逆边界

| 事项 | 冻结默认 | 理由与可逆边界 |
|---|---|---|
| header | 可选 `Idempotency-Key` | 旧 Client 无需修改；未来新 API 版本可要求自动化调用必填 |
| 无 header 成功 | 保持 `200` | 避免破坏旧 Client；新 WebUI 改用 keyed 路径 |
| keyed 首次/replay | `201` / `200` | 明确区分本次是否创建资源 |
| replay 标记 | `X-RAG-Idempotent-Replay: true` | 与 principal provisioning 一致，不污染 Collection body |
| owner | 第 6.1 节固定映射 | rotation 不改变 owner；未来 federation 可增加 owner 类型 |
| key 规则 | 复用 `IdempotencyKeyValidator` | 所有 endpoint 保持一致的 header 语义 |
| retention | 默认 `400d`，范围 `7d..3650d` | 覆盖常见部署重试窗口且有界；超期后不承诺 replay |
| cleanup batch | 默认 `500`，范围 `10..5000` | 避免大事务和无界表增长 |
| cleanup interval | 默认 `3600000ms`，范围 `10000..86400000ms` | 每小时清理且可运维调整，禁止紧循环 |
| 并发尝试 | 默认 `3`，范围 `1..8` | 足以观察短事务胜者；无法确认时 fail closed |
| replay body | Collection 当前状态 | 不保存 response snapshot；能反映后续 update/delete，但不改变 operation 原始指纹 |
| 账本关闭/不可用 | keyed request 返回 `503` | 禁止静默退回非幂等创建 |
| capability 版本 | 保持 `1.0` | 新 JSON 字段 additive；旧 Client 必须忽略未知字段 |

## 5. 对外 HTTP 契约

### 5.1 无 header 兼容路径

```http
POST /api/v1/rag/collections
Content-Type: application/json
```

- 继续调用现有 `RagCollectionService.createCollection`；
- 成功继续返回 `200` 和既有 Collection map；
- duplicate 继续返回 `409 DUPLICATE_RESOURCE`；
- 不创建 provisioning operation；
- 旧 Client 无需改变；升级后的 WebUI 使用第 5.5 节 keyed 路径。

### 5.2 keyed 首次创建

```http
POST /api/v1/rag/collections
Idempotency-Key: collection-create-2026-08-26-0001
Content-Type: application/json
```

首次成功：

```http
HTTP/1.1 201 Created
Content-Type: application/json
```

body 保持既有 Collection map，不增加 operation ID、owner、key hash 或 fingerprint。

### 5.3 精确 replay

同一个认证 owner、同一个规范化 key、同一个有效请求：

```http
HTTP/1.1 200 OK
X-RAG-Idempotent-Replay: true
Content-Type: application/json
```

replay 规则：

- 返回账本绑定 Collection 的当前表示和当前 `documentCount`；
- 正常首次创建时为 `0`，后续 replay 应查询当前计数，不能永久伪造为 `0`；
- Collection 创建后被 update，replay 可以返回更新后的 name/metadata/version；
- Collection 被软删除，replay 返回该 Collection 且 `deleted=true`，不恢复资源；
- Collection 物理缺失或账本引用无法完整解析时返回 `503 SERVICE_UNAVAILABLE`；
- 不写新的 create audit，不更新 Collection，不延长 operation 的业务语义。

### 5.4 冲突与错误

| 场景 | HTTP / error | 语义 |
|---|---|---|
| 多个 header、空值、超长、非法字符 | `400 IDEMPOTENCY_KEY_INVALID` | 复用公共 validator |
| 同 owner/key、不同 canonical fingerprint | `409 IDEMPOTENCY_KEY_REUSED` | 不创建或修改 Collection |
| 不同 owner、相同 key、不同 Collection key | 各自独立 | key 不跨 owner 泄漏 |
| 不同 owner 或不同 idempotency key、相同 `collectionKey` | `409 DUPLICATE_RESOURCE` | 不认领别人的资源 |
| keyed feature disabled | `503 COLLECTION_PROVISIONING_IDEMPOTENCY_DISABLED` | fail closed |
| ledger/replay lookup 不可用 | `503 SERVICE_UNAVAILABLE` | 不退回普通创建 |
| 唯一竞争后超过有界尝试仍不能确认胜者 | `503 SERVICE_UNAVAILABLE` | Client 复用同 key 重试 |
| 无 header duplicate | `409 DUPLICATE_RESOURCE` | 完全保持旧行为 |

错误响应沿用全局 RFC 7807 格式。任何响应、日志、metric 或验证证据都不得包含原始
`Idempotency-Key`。

### 5.5 WebUI 自动重试

`spring-ai-rag-webui/src/api/client.ts` 当前对网络错误和 `5xx` 最多重试三次，也会覆盖 POST。
因此 `collectionsApi.create` 必须：

1. 每次 API 方法调用开始时生成一个 `crypto.randomUUID()`；
2. 将其放入 `Idempotency-Key` header；
3. 不在 retry callback 中重新生成；
4. Axios 对同一个 request config 的自动重试复用原 header；
5. 用户修正表单后重新点击提交属于新命令，生成新 key；
6. key 不进入 React state、URL、toast、console、local/session storage 或错误文本。

旧后端会忽略未知 header，因此前端字段在滚动发布期间保持向后兼容；只有新后端能提供跨请求
replay 保证。WebUI 不通过 capability endpoint 动态切换，也不向用户展示幂等概念。

## 6. Owner 与 canonical fingerprint

### 6.1 Owner 映射

新增可复用的请求 owner resolver，并让 API principal 与 Collection provisioning 共用，
避免两个 controller 各自维护容易漂移的私有逻辑：

| 认证身份 | owner |
|---|---|
| environment root | `root:environment-root` |
| database API principal | `db:{stablePrincipalId}` |
| legacy static | `legacy:static` |
| auth-disabled 本地请求 | `local:auth-disabled` |

database owner 必须使用认证快照中的 stable principal ID，而不是可轮换 credential ID。
若 principal type、ID 和 immutable principal snapshot 不一致，keyed create fail closed，
不能退回 legacy/local owner。

owner 隔离强度取决于认证模式：

- database principal 按 stable principal ID 隔离，credential rotation 不改变 owner；
- environment root 是整个部署共享的 operator owner，所有 root 持有者必须在同一 key
  namespace 内生成全局唯一命令 key；
- legacy static 和 auth-disabled 也是部署级共享兼容 owner，不提供调用者级隔离，不应作为
  多方生产自动化的安全边界；
- 验收中的“不同 owner 隔离”必须使用两个 database principal，或 root 与 database
  principal；不能把两个 root 请求误当作不同 owner。

### 6.2 指纹字段

fingerprint 只包含创建后会影响持久状态的字段：

```text
collectionKey
name
description
embeddingModel
effectiveDimensions
effectiveEnabled
canonicalMetadata
```

规范化规则：

- `dimensions=null` 与 `1024` 等价；
- `enabled=null` 与 `true` 等价；
- 字符串按实际持久化值精确比较，不做额外 trim、大小写折叠或 Unicode 归一化；
- metadata object 递归按 key 排序；
- metadata array 保留原顺序；
- metadata number 使用不丢精度的十进制规范形式，`1` 与 `1.0` 视为同一 JSONB 数值；
- `null` 与空 object/array 不等价；
- 使用固定 UTF-8 JSON 编码并计算 SHA-256；
- 不纳入 header、owner、Authorization、请求字段顺序、HTTP URL 或当前数据库状态。

指纹 builder 必须是独立纯函数并有边界测试。不得直接依赖 `Map.toString()`、默认
`HashMap` 顺序或原始 request body。

### 6.3 原始请求与当前资源的关系

operation 保存的是**首次创建命令**的 fingerprint。后续 Collection update/delete 不会
改变 fingerprint。精确 replay 的判断只比较本次重试与原始创建命令，response 则读取当前
Collection 状态。这一区分避免：

- 因正常 update 导致原始重试被误判冲突；
- 保存一份会过时、可能包含 metadata 的 response snapshot；
- replay 隐式回滚或恢复当前资源。

## 7. 数据模型与迁移

新增不可改写的 Flyway migration：

```text
V52__collection_provisioning_idempotency.sql
```

表：

```text
rag_collection_provisioning_operation
```

建议列：

| 列 | 类型 | 约束/语义 |
|---|---|---|
| `id` | BIGINT identity | 主键 |
| `owner_id` | VARCHAR(128) | 服务端 owner，非空 |
| `idempotency_key_hash` | VARCHAR(64) | lowercase SHA-256 hex |
| `request_fingerprint_sha256` | VARCHAR(64) | canonical request SHA-256 |
| `collection_id` | BIGINT | FK `rag_collection(id)`，`ON DELETE RESTRICT` |
| `created_at` | TIMESTAMP | 首次 operation 时间 |
| `updated_at` | TIMESTAMP | 保留一致的实体审计字段 |
| `completed_at` | TIMESTAMP | 成功完成和 retention 基准 |

约束与索引：

- unique `(owner_id, idempotency_key_hash)`；
- hash/fingerprint 格式 check；
- owner 长度 check；
- `completed_at` cleanup index；
- 不保存 raw key、Collection request JSON、metadata snapshot、credential 或响应 body。

迁移验证必须覆盖：

1. 空库从 V1 迁移到 V52；
2. V51 现有数据升级到 V52；
3. unique、FK、hash check 生效；
4. `rag_collection` 历史数据不需要 backfill；
5. Hibernate `ddl-auto=validate` 可启动；
6. 既有“最新 migration=51”的测试和文档基线全部更新为 V52，但描述历史功能来源的
   `V50` / `V51` 文字不能机械改写。

## 8. 服务、事务与并发设计

### 8.1 新服务边界

新增 `CollectionProvisioningService`，职责仅包括：

- keyed create 的 feature gate；
- owner/key/fingerprint 校验；
- operation lookup、create、replay 和 cleanup；
- 数据库竞争后的有界恢复；
- 把 `RagCollection` 与 `replay` 标志返回 controller。

`RagCollectionService` 继续拥有普通 Collection 创建和唯一 key 错误映射。controller：

```text
no Idempotency-Key
  -> RagCollectionService.createCollection
  -> audit once
  -> 200

with Idempotency-Key
  -> CollectionProvisioningService.createOrReplay
  -> audit only when replay=false
  -> 201 or 200 + replay header
```

### 8.2 首次创建事务

一个新的 transaction 中固定顺序：

1. 按 owner/key hash 查询 operation；
2. 若存在，比较 fingerprint 并执行 replay；
3. 若不存在，调用 `RagCollectionService.createCollection`；
4. 插入 operation，引用刚创建 Collection；
5. flush 并提交。

Collection insert 或 operation insert 任一失败，整个 transaction 回滚。禁止先提交
Collection 再异步补账本。

### 8.3 并发首请求

需要覆盖两类竞争：

1. **相同请求、相同 Collection key**：竞争者可能先撞
   `uk_rag_collection_collection_key`，而不是 operation unique；
2. **不同请求、相同 owner/key**：两个不同 Collection 都可能先插入，之后一个撞
   operation unique；失败 transaction 必须连同自己的 Collection 一起回滚。

处理算法：

- 每次尝试使用独立 transaction；
- 捕获 operation unique violation 和可能由同请求竞争造成的 `DUPLICATE_RESOURCE`；
- 在新的 transaction 中重读 owner/key operation；
- 找到且 fingerprint 相同则 replay；
- 找到且 fingerprint 不同则 `IDEMPOTENCY_KEY_REUSED`；
- 没找到则按有界短 backoff 重试观察；
- 尝试耗尽时：
  - 已确认是无 operation 支撑的 Collection key duplicate，返回原
    `DUPLICATE_RESOURCE`；
  - 无法判断提交状态或 operation unique 竞争，返回 `SERVICE_UNAVAILABLE`。

不得无限循环，不得把睡眠放在持有数据库 transaction/connection 的范围内。

### 8.4 Replay 与删除

replay 使用 `findById`，不是 `findByIdAndDeletedFalse`：

- active Collection：返回当前状态和当前 document count；
- soft-deleted Collection：返回当前 tombstone 状态和当前 document count，不恢复；
- 物理缺失：`503`，因为账本与资源事实不一致；
- 账本清理只删除 operation，不删除 Collection。

### 8.5 Cleanup

配置前缀：

```yaml
rag:
  collection-provisioning:
    enabled: true
    retention: 400d
    cleanup-batch-size: 500
    cleanup-interval-ms: 3600000
    concurrent-retry-attempts: 3
```

环境变量：

```text
RAG_COLLECTION_PROVISIONING_ENABLED
RAG_COLLECTION_PROVISIONING_RETENTION
RAG_COLLECTION_PROVISIONING_CLEANUP_BATCH_SIZE
RAG_COLLECTION_PROVISIONING_CLEANUP_INTERVAL_MS
RAG_COLLECTION_PROVISIONING_CONCURRENT_RETRY_ATTEMPTS
```

scheduled cleanup 按有界 fixed delay 运行，只批量删除 `completed_at < cutoff` 的
operation。DataAccessException 只记录低敏告警，不影响请求线程；keyed 请求自身遇到账本
不可用时必须返回 `503`。

## 9. Capability、审计与可观测性

### 9.1 Capability contract

在 `features.provisioning` 增加：

```json
{
  "idempotencyKey": true,
  "replayReturnsSecret": false,
  "rawCredentialShownOnce": true,
  "collectionCreateIdempotencyKey": true
}
```

- 原三个字段语义继续专指 API principal provisioning；
- 新字段明确表示 Collection create 支持可选幂等键；
- `Provisioning` record 保留三参数兼容 constructor；
- protocol 继续为 `1.0`；
- 字段值反映 `rag.collection-provisioning.enabled`。

### 9.2 Audit

`AuditLogService` 当前明确是失败不阻断业务的 best-effort 记录，并且 controller 在 Collection
事务提交后调用它。本轮不把审计改造成 outbox，也不宣称“资源提交与审计原子”：

- 无 header 首次创建：保持现有 best-effort create audit；
- keyed 首次创建：HTTP 正常路径至多调用一次 create audit；
- exact replay：零次新增 create audit 调用；
- conflict/invalid/503：零次 create audit 调用；
- 进程在 Collection/operation commit 后、audit 前崩溃时，允许缺少该条审计；operation
  ledger 和 Collection 才是幂等事实源；
- audit 中不得包含原始 idempotency key 或 hash。

### 9.3 Metrics 与日志

沿用 `rag.collection.create` timer，并增加低基数计数：

- keyed created；
- replay；
- fingerprint conflict；
- unique-race recovery；
- keyed unavailable/disabled；
- cleanup deleted/failed。

日志可记录 Collection public ID/key、owner 类型和结果类别；不记录 owner 全值、原始 key、
key hash、完整 metadata 或 Authorization。

## 10. 文件级实施切片

### Slice A：公共语义和 schema

- 新增 `RagCollectionProvisioningProperties` 并接入 `RagProperties` / `application.yml`；
- 新增共享 `ProvisioningOwnerResolver`，替换 `ApiKeyController` 私有 owner 逻辑；
- 新增 `CollectionProvisioningFingerprint`；
- 新增 V52、entity 和 repository；
- 新增 error code `COLLECTION_PROVISIONING_IDEMPOTENCY_DISABLED`。

### Slice B：服务与 HTTP

- 新增 `CollectionProvisioningService`；
- 调整 `RagCollectionController.create` 接收 `HttpServletRequest`，区分 keyed/unkeyed；
- 首次/replay 状态、header、document count 和审计严格按第 5 节；
- 不改变 import/clone 和兼容 overload；
- controller/OpenAPI 明确 header、`200/201/400/409/503`。

### Slice C：WebUI、Capability 与自动化验收

- `collectionsApi.create` 为每次调用生成一个 key，并让 Axios retry 复用；
- 增加 API client/Vitest 与 Mock Playwright 网络断言，不增加用户可见 UI；
- additive 扩展 `IntegrationCapabilitiesResponse.Provisioning`；
- 更新 `IntegrationCapabilityCatalog` 和兼容测试；
- 新增或扩展真实 PostgreSQL integration test；
- 新增 Collection provisioning 一键真实 HTTP gate，并纳入通用业务 Client readiness；
- 更新所有 V52 migration 基线；
- WebUI 只做契约回归，不新增 UI 功能。

### Slice D：双语长青文档与交付

- `rest-api*`：header、状态码、replay/current-state/deleted 语义；
- `business-client-integration*`：ensure Collection 的安全重试顺序；
- `configuration*`：五个配置项；
- `architecture*` / `project-context*`：V52 账本和事务边界；
- `testing-guide*` / `developer-reference*`：一键门禁与真实 HTTP 场景；
- `release-checklist*` / `TODO*`：交付状态；
- `AGENTS.md` 和 `project-docs` 仅更新 V1-V52 数字，不复制实现正文；
- 完成后归档 plan/progress。

## 11. 一次性验收矩阵

### 11.1 后端快速测试

| 层级 | 必须证明 |
|---|---|
| fingerprint | 默认值等价、字符串精确、metadata key 顺序无关、array 顺序敏感、JSON 数值规范化、null/empty 区分 |
| owner resolver | root/database/legacy/auth-disabled 映射、database stable principal、共享 owner 边界；不一致身份 fail closed |
| properties | 默认值、retention/interval 边界、batch/retry clamp |
| controller | 无 header 200、keyed 201、replay 200/header、invalid/multi header 400、冲突 409、disabled/unavailable 503、首次至多一次 audit 调用且 replay 不调用 |
| OpenAPI | header schema、`200/201/400/409/503` 契约 |
| capability | 新字段跟随 flag，旧 constructor 可编译和序列化 |
| ACL | restricted principal 在读取/哈希/账本之前被拒绝，不能借 key 探测全局资源 |
| WebUI API | 每次 create 调用生成一个 key；同一次 Axios 网络/5xx retry 复用；下一次用户提交换 key；key 不泄漏到可见状态或存储 |

### 11.2 真实 PostgreSQL

新增专项集成测试，至少覆盖：

1. V1-V52 空库迁移和 V51→V52 增量迁移；
2. 首次 keyed create 只产生一条 Collection 和一条 operation；
3. 精确 replay 返回同 ID；
4. canonical metadata/default 等价 replay；
5. fingerprint conflict 不新增 Collection；
6. 两个 database principal 使用同 key 独立；root/legacy/local 各自保持部署级共享 owner；
7. 不同 owner 创建同 `collectionKey` 不会认领结果；
8. 并发同 owner/key/同请求：一名 creator、一名 replay、一条 Collection、一条 ledger；
9. 并发同 owner/key/不同请求：一个成功、一个 conflict，失败侧 Collection 回滚；
10. soft delete 后 replay 返回 deleted，不恢复；
11. FK 阻止存在 ledger 时物理删除 Collection；service 层模拟异常缺失引用时 fail closed；
12. schema/ledger 不可用时 keyed create fail closed；
13. cleanup 只删除过期 operation，不删除 Collection；
14. 无 header duplicate 行为不变；
15. `ddl-auto=validate` 和 service startup 通过。

### 11.3 真实 HTTP 与双实例

使用可处置 PostgreSQL、两个隔离 backend 端口和真实认证：

1. root 对实例 A keyed create，断言 `201`；
2. 同请求发送到实例 B，断言 `200 + replay header + same id`；
3. 字段顺序和 metadata key 顺序变化仍 replay；
4. 同 key 修改 name/metadata/default-effective 字段得到预期 replay 或 conflict；
5. 使用独立 database principal 的另一个 owner 发送相同 idempotency key，不读取 root
   operation；两个 root 请求仍属于同一 owner；
6. restricted principal create 被 `403`，不产生 operation；
7. 删除 Collection 后跨实例 replay 返回 `deleted=true` 且数据库 version 不因 replay
   改变；
8. 停止两个实例后重启，再次 replay 成功；
9. 数据库只读查询确认 Collection/ledger 数量；无故障正常路径确认首次审计不重复；
10. capability JSON 显示新字段，且不含 key/hash/credential/provider 信息；
11. 故障注入使 ledger 不可访问时，keyed create 不退回普通创建；
12. 证据目录不保存 credential、原始 key、完整 metadata 或外部业务标识。

### 11.4 固定构建与前端门槛

后端：

```bash
mvn clean compile test-compile
# 本任务 PostgreSQL/HTTP integration matrix
mvn test
# postgresql profile 启动并检查 health
```

前端：

```bash
cd spring-ai-rag-webui
npx tsc -b --pretty false
npm run test:run
npm run build
# 核心 Mock Playwright，禁止使用截图作为通过证据
```

前端不新增幂等 UI，但会修改 Collection API client。除完整 typecheck/Vitest/build 外，
核心 Mock Playwright 必须通过网络请求断言证明：首次请求与模拟 retry 的 header 相同，新的
创建提交使用不同 header；不得使用截图。

### 11.5 安全、文档与真实 provider

```bash
./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-project-docs.sh
git diff --check
# Bash syntax、added-line secret scan、外部项目名称扫描
```

本功能不调用 LLM 或 embedding。Mock、PostgreSQL 和真实 HTTP 是直接正确性证据；在这些
门槛通过后，仍按用户要求执行现有真实 LLM/full-stack regression gate，确认 control-plane
变化没有破坏认证、Collection binding、Chat、OpenAI-compatible JSON/SSE 和 credential
rotation。真实调用期间持续观察日志，不设置人为调用次数预算，但不以重复相同请求替代场景
覆盖。

## 12. 发布、回滚与兼容

### 12.1 发布

1. 先执行 V52；
2. 部署代码并确认 `ddl-auto=validate`；
3. 读取 capability endpoint；
4. 使用新的随机 idempotency key 创建隔离 canary Collection；
5. 跨实例 replay 并查询数据库计数；
6. 删除 canary 后验证 replay 不恢复；
7. 观察 409/503、race recovery、cleanup 和审计数量。

### 12.2 回滚

- 旧代码可忽略 V52 表，表保留不删除；
- 不回滚或改写已执行 Flyway migration；
- 发现 keyed 路径问题时可关闭 `rag.collection-provisioning.enabled`，此后携带 header 的
  请求明确 `503`，无 header 旧路径继续工作；
- 禁止在关闭 feature 时静默忽略 header；
- 修复 schema 必须新增 V53+ migration。

### 12.3 兼容

- 无 header Client 的状态码/body/duplicate 保持不变；
- WebUI 自动发送 header，但不改变表单、成功提示或 Collection body contract；
- 新 Client 可通过 capability field 决定是否启用安全自动重试；
- 超过 retention 后，旧 key 不再保证 replay，调用方应生成新 provisioning command 并
  通过 `collectionKey` duplicate + operator reconciliation 处理；
- 旧 capability Client 忽略未知字段即可继续工作。

## 13. 工作流与完成定义

### 13.1 规划

- 当前 plan/progress 保持单语；
- 三轮检查固定为：
  1. 需求闭环、自包含性、默认决策与非目标；
  2. API、schema、事务、并发、安全、兼容可实施性；
  3. 验收矩阵、故障恢复、发布、回滚、文档和 Git 交付；
- 发现实质问题立即修复并把计数重置为 `0`；
- 连续 `3/3` 无修改后，提交并推送规划 checkpoint。

### 13.2 实施

- 从最新本地 `main` 创建新的专用分支和隔离 worktree；
- 每个关键切片先更新 progress，再修改代码；
- 实施过程中跟进已推送的 `origin/main`；
- 最终 merge `origin/main` 后记录新基线并完整重跑第 11 节，不沿用 merge 前结果；
- 基本门槛通过后执行限定范围实现收敛检查；任何实质修复重跑受影响门槛。

### 13.3 Git 交付

- 特性分支先 commit/push；
- 合入并 push `main`；
- 确认 `main == origin/main`、工作区干净；
- 安全移除仅本轮创建的隔离 feature worktree；
- 归档 plan/progress，并把长期事实提升到双语长青文档；
- 进入下一轮高价值需求探索，直到用户明确停止。

### 13.4 完成定义

只有以下条件全部满足才算完成：

1. 规划连续 `3/3` 无修改并已推送；
2. keyed/unkeyed HTTP 契约、owner、fingerprint、并发、删除 replay 和 fail-closed 均有
   自动化断言；
3. V52 在真实 PostgreSQL 迁移并通过双实例/restart 验收；
4. 后端 compile/test-compile、相关/全量测试和服务启动通过；
5. 前端 typecheck、Vitest、build、核心 Mock Playwright 通过；
6. 真实 LLM/full-stack regression 按许可完成并有日志/摘要证据；
7. 双语长青文档、文档门禁、锁策略、diff 和密钥扫描通过；
8. 特性合入并推送 `main`，worktree 清理完成。

## 14. 规划检查记录

发现实质问题并修改本文时，记录到配套
[进度账本](2026-08-27_COLLECTION_CREATE_IDEMPOTENCY_PROGRESS.md) 并将连续计数重置为 `0`。无问题轮次
只在会话进展中总结；达到连续 `3/3` 后，再一次性把最终范围和结果写入本文，避免三轮检查
本身破坏“无修改”条件。

规划于 2026-08-26 完成连续三轮无修改审查并封板。最终三轮分别覆盖：

1. 需求闭环、自包含性、推荐默认、非目标与通用 Client 表述；
2. Java/Spring 事务可实施性、PostgreSQL 约束、owner 派生、Axios 重试、capability
   兼容与禁悲观锁策略；
3. 一次性验收矩阵、故障注入、启动与运行时验证、发布与回滚、双语长青文档以及
   Git/worktree 交付。

三轮均未发现需要修改规划的实质问题。封板前 plan SHA-256 为
`b9b5dc25759b107f912b8b116d54e046c8e77cdd0a17832a5a157b15353e13b2`。
