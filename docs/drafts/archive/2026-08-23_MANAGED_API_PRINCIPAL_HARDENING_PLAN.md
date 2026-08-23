# 稳定受管 API Principal、版本化凭据与共享配额实施规划

> **状态**：规划完成，待用户审阅；尚未实施
>
> **规划日期**：2026-08-23
>
> **代码基线**：`main` / `origin/main` @ `05a21706`，Spring Boot `3.5.16`，
> 规划基线：Spring AI `1.1.8`、Java `21`、Flyway V1–V47；本轮交付目标为 Flyway V48
>
> **规划工作区**：`/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
>
> **规划分支**：`main`
>
> **实施工作区/分支**：规划批准后再从最新 `origin/main` 创建隔离 worktree 和专用特性分支
>
> **配套进度**：[2026-08-23_MANAGED_API_PRINCIPAL_HARDENING_PROGRESS.md](2026-08-23_MANAGED_API_PRINCIPAL_HARDENING_PROGRESS.md)

本规划是下一轮实施的单一恢复入口。它冻结本轮的问题、设计默认、数据与 HTTP 契约、
并发顺序、兼容边界、实施步骤和一次性验收矩阵。实施阶段不应再次把核心身份模型、轮换
语义或配额后端留作临场决策。

近距离上下文：

- [OpenAI 兼容服务就绪度](../../openai-compatibility-readiness-zh-CN.md)
- [项目上下文](../../project-context-zh-CN.md)
- [REST API](../../rest-api-zh-CN.md)
- [配置参考](../../configuration-zh-CN.md)
- [测试指南](../../testing-guide-zh-CN.md)
- [规划、实施与验收工作流](../../delivery-workflow-zh-CN.md)
- [后续改进 TODO](../../TODO-zh-CN.md)

## 1. 执行摘要

本轮解决受管 API Key 在长期、外部、多实例使用下的四个同源问题：

1. credential 轮换会改变 `keyId`，而 Chat、评估、诊断和 durable operation 使用
   `db:{keyId}` 作为 owner，导致同一个外部 Client 轮换后进入新的数据命名空间；
2. 认证结果有 30 秒 JVM 正向缓存，撤销不能在其他实例立即生效；
3. 限流计数只在当前 JVM 内存中，多副本会放大 quota，轮换也会重置计数身份；
4. V23 仍允许明文 `api_key` 落库，每次认证还同步写 `last_used_at`，不满足最小化 secret
   持久化和高频调用的写入成本要求。

推荐方案是把长期身份与可轮换凭据分开：

```text
raw credential（只显示一次）
  -> SHA-256 indexed credential version
  -> stable managed principal / policy
       ├─ stable owner namespace: db:{principalId}
       ├─ role / Collection ACL / expiry / policyVersion
       ├─ optional requestsPerMinute
       └─ current credential version
  -> request-scoped immutable AuthenticatedApiPrincipal
  -> ACL / Chat owner / worker reauthorization / PostgreSQL quota
```

同一 principal 的 rotation 只替换 credential version，不改变 `principalId`、role、ACL、
expiry、owner 或 quota。认证不再缓存正向授权决定；撤销事务提交后，任何实例的下一次认证
都必须查询 PostgreSQL 并失败。共享配额使用 PostgreSQL 固定 UTC 分钟 bucket 与原子条件
upsert，不引入 Redis，不使用显式悲观锁、`SKIP LOCKED` 或 advisory lock。

本轮包含必要的管理 API、WebUI、迁移、两种 Spring Boot 装配拓扑和真实 LLM 验证。它不
把 `/v1` 默认公开，也不引入 OAuth/OIDC、租户层级、计费系统或多 embedding profile。

## 2. 为什么这是下一批最高价值工作

### 2.1 候选比较

| 候选 | 用户/系统价值 | 当前依赖与风险 | 本轮结论 |
|---|---|---|---|
| 稳定 principal、可轮换凭据、即时撤销和共享 quota | 直接决定外部 Client 的身份连续性、数据隔离、撤销时效和多副本成本上限 | 触及安全主链，但可复用现有 PostgreSQL、root 管理面、CAS 与唯一约束 | **实施** |
| Collection 级多 embedding profile 路由 | 支持不同 Collection 使用不同向量模型/维度 | 写入、检索、job、readiness 和完整性诊断当前都依赖单一 active profile；还需模型 factory、向量空间分组和重嵌入协议 | 独立后续规划 |
| `EACH_COLLECTION` 召回覆盖 | 可为明确选择的多个知识库提供覆盖机会 | 需要 bounded fan-out、融合、质量指标；正式 TODO 已定为非紧急 backlog | 延后 |
| 继续扩展 Chat 工具/记忆 | 可增加 Agent 能力 | 会话摘要、工具预算、durable turn 和真实 provider 已连续交付；当前边际价值较低 | 不重复规划 |

### 2.2 选择依据

- 第一个外部 Client 只是需求信号，不是当前项目的文档或领域边界；本方案保持为通用 RAG
  服务能力，不写入任何 Client 特定表、字段、Prompt 或业务语义。
- 当前缺口会在凭据轮换时直接造成 owner namespace 变化，不是可选的 UI 优化。
- 当前 root-managed Key 已有创建、列出、轮换、撤销和 Collection ACL，下一步应补齐其
  identity lifecycle，而不是再叠加一种临时 key 类型。
- PostgreSQL 已是主 profile 和 durable coordination 基础，使用它实现共享 quota 比新增
  Redis 更符合当前部署与运维边界。
- 方案通过新增表、列和 API 保持可逆；既有 credential 初始 `principalId=keyId`，可保留
  历史 owner，不需要重写 Chat、评估、诊断或 operation 数据。

## 3. 当前代码事实与问题

以下事实已从生产代码、迁移、测试、WebUI 与长青文档交叉核对。

### 3.1 Credential 与 policy 仍是同一实体

- `RagApiKey` 保存 `keyId`、`keyHash`、name、role、expiry、enabled、Collection ACL 和
  `lastUsedAt`。
- `ApiKeyManagementService.rotateKey` / `rotateManagedKey` 先禁用旧 row，再调用创建逻辑
  生成新的独立 row；新 row 没有 family 标识。
- legacy rotation 通过 `generateKey` 创建 `NORMAL` row，不能保留旧 `ADMIN` role。
- root-managed rotation 保留 name、未来 expiry 和 ACL，但 owner identity 仍改变。
- `RagApiKeyRepository.disableByKeyId` 没有 current-version 条件；并发 rotate/revoke 缺少
  明确的单赢家契约。

### 3.2 `keyId` 已成为 durable owner

- `ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE` 对数据库 Key 保存当前 `keyId`。
- `ChatPrincipal` 把它转换为 `db:{keyId}`。
- Chat history、Memory namespace、session lease、conversation summary、turn operation、
  evaluation suite/run、retrieval diagnostics、embedding operation 的请求归属和文档 operation
  都直接或间接依赖这个 principal 字符串。
- `ChatAuthorizationService` 与 `EvaluationSuiteService` 的异步/重放路径会从
  `db:{keyId}` 反解现有 Key，再检查启用、过期和 Collection ACL。
- 所以 rotation 不只是“换 secret”，而是当前实现中的 owner 迁移；旧历史不会自动属于
  新 credential。

### 3.3 认证与撤销只具单进程近实时语义

- `ApiKeyManagementService` 使用静态 Caffeine cache 保存 30 秒正向认证结果。
- revoke/rotate 只 `invalidateAll` 当前 JVM；其他实例在 TTL 内仍可接受旧 credential。
- `validateKeyEntity` 每次成功请求都会同步执行 `updateLastUsed`，高频调用产生写放大。
- root 模式遇到 credential store `DataAccessException` 已返回 `503`，并且不走 legacy
  static fallback；这个 fail-closed 边界必须保留。

### 3.4 限流不是共享 quota

- `RateLimitFilter` 使用本地 `ConcurrentHashMap<String, WindowState>`。
- `user` / 已认证 `api-key` 策略优先使用认证 attribute，但计数仍只属于当前实例。
- legacy/未认证 `api-key` fallback 可能直接使用 raw `X-API-Key` 作为 map key 和日志字段。
- `key-limits` 是配置文件中的 identifier map，不是受管 principal policy；rotation 后无法
  稳定继承。
- core standalone 与 starter consumer 的 Filter 装配路径不同，安全改动必须同时覆盖。

### 3.5 Schema 仍允许明文 secret

- V18 正确声明只存 hash；V23 后来增加可空 `rag_api_key.api_key` 和索引。
- 当前 service 不给该字段赋值，但 JPA 实体仍映射它，数据库也没有 `IS NULL` 约束。
- “应用当前没写”不能替代 schema 级禁止持久化。

### 3.6 现有测试缺口

- API Key service/filter/controller 有较多单元或 MockMvc 覆盖。
- `ApiKeyRootModeWebIntegrationTest` 是 mocked management service 的 Web slice，不是完整
  PostgreSQL credential lifecycle。
- 当前没有两个真实应用实例共享同一数据库的即时撤销和 quota 证明。
- WebUI Mock Playwright 覆盖 root unlock、创建、轮换、撤销和 secret 不落浏览器存储，但
  不覆盖 stable principal、policy CAS、quota 或 stale credential conflict。

## 4. 目标、非目标与完成定义

### 4.1 目标

1. 一个受管调用方拥有稳定 `principalId`，可以有多个历史 credential version，但任一时刻
   最多一个 current credential。
2. rotation 不改变 owner、role、Collection ACL、expiry、policy version 语义或 quota。
3. 既有每一条 Key 回填成独立 principal，且 `principalId=旧 keyId`，保留所有既有
   `db:{keyId}` owner 的可访问性。
4. 不对历史上已经分裂的 rotation row 做猜测式 family 合并。
5. 认证每次以 PostgreSQL credential + principal 当前状态为准，不缓存正向授权决定。
6. revoke 提交后，其他实例的后续认证立即失败；已通过认证并正在执行的单个请求不承诺
   被异步中断。
7. `last_used_at` 变为近似审计时间，默认同一 principal 最多每 5 分钟发生一次真实更新，
   不影响认证决定。
8. 受管 principal 可配置可选每分钟 quota；未配置时使用全局默认。
9. PostgreSQL quota 在多实例间原子共享，rotation 不重置 bucket。
10. raw secret 只在 create/rotate 响应显示一次，实体、日志、缓存、数据库和浏览器持久存储
    都不保留；schema 阻止 `api_key` 写入非空值。
11. ACL 主链、Chat replay、evaluation worker 和所有 request-scope consumer 统一使用不可变
    principal/policy snapshot，不再把 JPA credential entity 当认证上下文。
12. root mode 和 legacy mode 保持明确边界；legacy 的显式 ADMIN revoke 具有事务化最后一个
    ADMIN 保护。

### 4.2 非目标

- 不实现 OAuth2/OIDC、用户登录、组织/租户层级、SCIM 或 RBAC policy language。
- 不把 environment root 存入数据库，也不允许业务 principal 管理 root。
- 不实现 token/cost billing、日/月额度、模型级额度或退款语义。
- 不引入 Redis、Kafka、外部分布式锁或新的基础设施依赖。
- 不开放客户端自定义 SQL、LLM tools、roles 或任意授权表达式。
- 不默认开启 `/v1`，不移除所有 legacy 认证兼容，也不在本批宣称公网 production-ready。
- 不实现多 embedding profile 路由或 `EACH_COLLECTION`。
- 不重写已有 owner 数据，不自动合并疑似属于同一调用方的历史 Key。
- 不保证已经通过认证的长请求在 revoke 提交瞬间被中断；即时语义从下一次认证开始。

### 4.3 完成定义

实现只有在以下全部成立后才可报告完成：

- V48 从空库和 V47 数据库迁移成功，回填、约束、索引和明文列禁写均有 PostgreSQL 证据；
- 管理 API、认证、ACL、owner continuity、并发 rotate/revoke/policy 和共享 quota 有真实
  PostgreSQL/HTTP 集成覆盖；
- 两个隔离端口的应用实例共享数据库时，即时撤销和 quota 断言通过；
- 后端相关集成测试、`mvn clean compile test-compile`、全量测试和目标 profile 启动通过；
- WebUI Vitest、TypeScript、production build、alignment、核心 Mock Playwright 和真实
  全栈 Playwright 通过，且前端验收不使用截图；
- Mock 主链通过后，使用 `.env` 中真实 LLM 做有界 create/chat/rotate/replay/revoke 验证；
- 合并最新 `origin/main` 后按固定顺序重新执行完整验收；
- 实现完成后连续三轮限定范围只读审查无实质问题、期间无修改；
- 文档、验证证据、Git 提交、push 和 worktree 清理完成。

## 5. 冻结的不变量

实施必须持续满足以下不变量：

1. `principalId` 是长期 owner；`credentialId` 只是某一版公开 credential 标识。
2. `principalId` 和所有 ID 都不包含 raw secret，也不能由 raw secret 可逆推导。
3. 同一 principal 同时最多一个 `enabled=true` credential。
4. rotate 只能作用于 current credential；历史/stale credential ID 返回冲突，不能轮换或
   撤销一个意外的新版本。
5. policy 的唯一事实源是 principal row；credential 上保留的旧字段只是兼容快照。
6. 数据面从 request-scoped immutable policy snapshot 授权；不得把 JPA entity 暴露为
   可变认证上下文。
7. DB credential 查询失败时 fail closed；root 模式不允许 static/query fallback。
8. PostgreSQL quota 不使用 raw header、不回退到 IP 或本地计数。
9. 任何并发协调只使用条件 DML/CAS、唯一约束和有界重试，不写显式悲观锁语句。
10. metrics tag 只能使用固定枚举/结果；principal、credential、hash、session 和 endpoint
    原始路径都不得成为 tag。
11. create/rotate raw secret response 必须 `Cache-Control: no-store`，且 DTO `toString`、日志、
    测试报告和验证摘要不得包含 secret。
12. root-managed 业务 principal 固定 `NORMAL/FULL_RAG`；role 不进入 public policy update。

## 6. V48 数据模型与迁移

### 6.1 `rag_api_principal`

V48 新增稳定 principal/policy 表：

| 列 | 类型/约束 | 语义 |
|---|---|---|
| `principal_id` | `VARCHAR(64) PRIMARY KEY` | 稳定公开 ID；新建时等于首版 `key_id` |
| `name` | `VARCHAR(255) NOT NULL` | 调用方可读名称 |
| `role` | `VARCHAR(20) NOT NULL CHECK IN ('ADMIN','NORMAL')` | 稳定角色；root-managed 固定 NORMAL |
| `allowed_collection_ids` | `VARCHAR(2048)` | 沿用现有 ACL 存储；null/blank 为 unrestricted |
| `expires_at` | `TIMESTAMP` | principal 级 expiry；root-managed 必填且在未来 |
| `requests_per_minute` | `INTEGER NULL CHECK 1..1000000` | 可选 quota override；null 使用全局默认 |
| `policy_version` | `BIGINT NOT NULL DEFAULT 1 CHECK > 0` | policy PUT 的 CAS version |
| `next_credential_version` | `INTEGER NOT NULL DEFAULT 2 CHECK > 0` | 下一版 credential 序号 |
| `last_used_at` | `TIMESTAMP NULL` | 近似审计时间，不参与授权 |
| `revoked_at` | `TIMESTAMP NULL` | 非空表示整个 principal family 已撤销 |
| `created_at` / `updated_at` | `TIMESTAMP NOT NULL` | 审计字段 |

不在本轮把 ACL 拆为关联表。当前每 Key 最多 100 个 Collection，沿用已验证的序列化逻辑
可以控制迁移风险；规范化 ACL 可在独立权限模型批次中实施。

### 6.2 扩展 `rag_api_key`

保留该表现有表名，将其语义明确为 credential version，并新增：

| 列 | 类型/约束 | 语义 |
|---|---|---|
| `principal_id` | `VARCHAR(64) NOT NULL` + FK | 所属稳定 principal |
| `credential_version` | `INTEGER NOT NULL CHECK > 0` | principal 内单调版本 |
| `revoked_at` | `TIMESTAMP NULL` | 该 credential 被 rotate/revoke 的时间 |

新增约束/索引：

- `UNIQUE(principal_id, credential_version)`；
- partial unique index：`UNIQUE(principal_id) WHERE enabled = TRUE`；
- credential hash 现有 unique index 保留；
- `principal_id` 普通索引支持历史列表与 join；
- `enabled=false` 必须同时有 `revoked_at`；新代码写入时遵守，迁移为旧 disabled row 填
  `revoked_at=COALESCE(last_used_at, created_at)`。为兼容旧历史数据，不通过猜测构造真实
  revoke 时刻。

`name`、`role`、`allowed_collection_ids`、`expires_at`、`last_used_at` 旧列本轮不删除，
作为旧二进制可读取的 compatibility snapshot。新代码认证与授权只使用 principal 表；
create/rotate/policy update 同事务刷新 current credential snapshot，便于短期 rollback。

### 6.3 既有数据回填

回填算法必须确定且不猜测：

1. 为每条既有 `rag_api_key` 创建一个 principal；`principal_id = key_id`。
2. principal 复制该 row 的 name、role、ACL、expiry、last used 和 created time。
3. 每条旧 credential 的 `credential_version=1`、`principal_id=key_id`。
4. enabled row 对应 active principal；disabled row 的 principal 同时设置 `revoked_at`。
5. `next_credential_version=2`。
6. 不按 name、时间、ACL 或 enabled 状态合并旧 rows。旧 rotation 关系无法被可靠证明，
   错误合并比保留独立 principal 风险更大。

这个策略保证所有既有 `db:{keyId}` owner 原样有效；它不承诺恢复过去已经丢失的 family
关系。

### 6.4 明文列收敛

V48 必须：

1. 将 `rag_api_key.api_key` 全部更新为 null；
2. 删除 `idx_rag_api_key_api_key`；
3. 增加已验证的 `CHECK (api_key IS NULL)`；
4. 从 `RagApiKey` entity 移除 `apiKey` 映射、getter 和 setter；
5. 不立即 drop 列，使旧 binary 仍可 select；任何旧 binary 尝试写明文会由数据库拒绝。

迁移测试要先插入带非空 `api_key` 的 V47 fixture，再证明 V48 清空并禁止重新写入。

### 6.5 `rag_api_rate_limit_bucket`

新增共享固定窗口表：

| 列 | 类型/约束 | 语义 |
|---|---|---|
| `principal_id` | `VARCHAR(128) NOT NULL` | 认证后的稳定 limiter ID；不设 FK，允许 root/legacy namespace |
| `window_start` | `TIMESTAMPTZ NOT NULL` | PostgreSQL 计算的 UTC minute start |
| `request_count` | `INTEGER NOT NULL CHECK >= 0` | 已接受请求数 |
| `updated_at` | `TIMESTAMPTZ NOT NULL` | 清理依据 |

主键为 `(principal_id, window_start)`。该表只保存稳定 ID，不保存 raw credential、hash、IP、
请求路径或正文。

### 6.6 Legacy ADMIN guard

新增单行 `rag_api_admin_guard`：

- 固定主键 `singleton=true`；
- `non_revoked_admin_count >= 0`；
- `version >= 0`；
- V48 按非 revoked ADMIN principal 回填计数。

legacy 模式显式 revoke ADMIN 时，通过条件 `UPDATE ... WHERE count > 1 RETURNING` 原子减一；
零行返回 `409 LAST_ADMIN_REQUIRED`。root 模式可把计数降到 0，因为 environment root 仍是
管理入口。rotation 不改变 role 或 guard。public policy API 不允许改 role；legacy ADMIN
的 expiry 也不允许通过新 policy API 修改，避免新接口制造定时失去最后管理员的路径。

该 guard 防止本轮管理操作显式移除最后一个 ADMIN；它不修复部署前已经存在的过期 legacy
ADMIN。启动时若 legacy 模式没有当前可用 ADMIN，应记录低基数 error 并明确要求 operator
配置 environment root，不得自动输出新的 raw ADMIN secret。

## 7. 运行时身份与授权模型

### 7.1 不可变认证 snapshot

新增 `AuthenticatedApiPrincipal`（名称可按包约定微调，但语义不可改变），至少包含：

```text
principalId
credentialId
credentialVersion
principalType
role
allowedCollectionIds（解析后的 immutable set / unrestricted marker）
expiresAt
policyVersion
requestsPerMinute
```

它是普通不可变 record/value object，不是 JPA entity，不包含 hash 或 raw credential。

`ApiKeyAuthFilter` 设置：

- `AUTHENTICATED_KEY_ATTRIBUTE`：继续保留 attribute 名以降低内部兼容风险，但数据库 Key
  的值改为稳定 `principalId`；environment root / legacy static 保持稳定固定 ID；
- 新增 `AUTHENTICATED_CREDENTIAL_ID_ATTRIBUTE`：当前 credentialId；root/legacy 为 null；
- 新增 `AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE`：immutable snapshot；
- 现有 `AUTHENTICATED_API_KEY_ENTITY` 标记 deprecated，并在生产代码全部迁移后不再设置。

`/api/v1/rag/auth/me` 保留现有 `principalType`、`principalId` 和 capabilities，增加可空
`credentialId`、`credentialVersion`、`policyVersion`。现有首版 Key 的 principalId 仍等于
旧 keyId；rotation 后 principalId 不变而 credentialId 改变。

### 7.2 ACL 主链迁移

新增只读 `ApiAccessPolicy` 接口或等价 value type，`ApiKeyCollectionAccess` 的授权方法改为
接收该 policy，而不是 `RagApiKey`。必须一次性迁移：

- Collection、Document、Search、Chat、PDF、JSONB、external document、sync run、
  relocation、derivation repair、embedding job 与 diagnostics；
- `CollectionRetrievalScopeResolver` 和 OpenAI request scope adapter；
- Chat source replay/turn status 授权；
- evaluation worker 的 owner reload；
- controller 中的 delegated ACL 和 legacy self-rotation 判断。

可以为 focused test 暂留从 `RagApiKey` 构造 policy 的 test helper，但生产 request context
和数据面 ACL consumer 不得再读取可变 entity。迁移后运行 `rg` 门槛；`RagApiKey` 只允许
出现在 credential persistence、management lifecycle、compatibility mapper 与聚焦测试，
不能重新进入 Controller 数据面授权或异步 worker 的 policy 参数。

### 7.3 Durable owner 与 worker reauthorization

- 数据库 principal 的 Chat owner 固定为 `db:{principalId}`。
- V48 不重写任何现有 owner；回填 `principalId=旧 keyId` 保证兼容。
- `ChatAuthorizationService`、`EvaluationSuiteService` 和其他 worker 从 `db:` 后解析
  `principalId`，加载当前 principal policy；不得再按 current credential keyId 查询。
- principal revoked/expired 时 worker fail closed；ACL 收紧后 replay/evaluation 按当前
  policy 拒绝越权。
- rotation 不影响 worker，因为 policy 与 owner 不变。

## 8. Authentication、撤销与使用时间

### 8.1 权威认证查询

`ApiKeyManagementService.validateKeyEntity` 替换为返回 immutable snapshot 的认证方法。
每次数据库 credential 请求执行一次 indexed join：

```text
SHA-256(raw credential)
  -> rag_api_key.key_hash unique lookup
  -> join rag_api_principal
  -> credential enabled and not revoked
  -> principal not revoked and not expired
  -> immutable snapshot
```

不保留正向或负向认证 decision cache。hash 只在当前请求内存中存在，不写日志。比较与 root
credential 继续使用 constant-time 比较。

### 8.2 撤销时效

“即时跨实例撤销”定义为：revoke transaction commit 后，任一实例收到的下一次使用旧 raw
credential 的请求返回 `401`；在 commit 前已经完成 Filter 认证的请求可继续执行。系统不做
长请求异步 kill，也不把这一点包装成更强保证。

credential store 查询失败：

- native API 返回现有 `503` envelope；
- `/v1/*` 返回 OpenAI `server_error` envelope；
- 不使用 stale snapshot、local allow-list、static fallback 或 IP fallback 放行。

### 8.3 `last_used_at` 低写放大

授权查询和 last-used 审计分离：

1. 每个实例维护一个仅用于 touch 抑制的短期 cache，key 为 `principalId`，默认 5 分钟；
2. cache miss 后执行条件 DML：只有 DB 值为空或早于 5 分钟阈值才更新；
3. 多实例同时 touch 最多一个产生实际 row update，其他为 0 rows；
4. touch 失败记录低基数 warning，不改变已经由权威查询得出的认证结果；
5. cache 不包含授权结果、raw credential、hash 或 ACL。

`last_used_at` 是近似审计字段，不提供逐请求计数；精确用量由 quota bucket/metrics 提供。

## 9. 管理 API 契约

现有 root-mode 管理权限不变：只有 environment root 可管理；数据库业务 Key 仍返回 `403`。
legacy 模式保留 ADMIN 管理、NORMAL 仅轮换自己 current credential 的兼容边界。

### 9.1 保留并扩展的端点

#### `POST /api/v1/rag/api-keys`

现有 request 字段保留，新增可选 `requestsPerMinute`（1–1,000,000）。root-managed expiry
仍必填且未来。response 在现有字段上增加：

```json
{
  "principalId": "rag_k_...",
  "keyId": "rag_k_...",
  "credentialVersion": 1,
  "policyVersion": 1,
  "requestsPerMinute": 120,
  "rawKey": "shown-once"
}
```

首版 `principalId == keyId`。raw response 继续 `no-store`。

#### `GET /api/v1/rag/api-keys`

保持 credential history 列表兼容，不改变旧客户端对 disabled 历史 row 的可见性。每项增加
`principalId`、`credentialVersion` 和 `currentCredential`；policy 字段来自 principal，
不是历史 snapshot。

#### `POST /api/v1/rag/api-keys/{keyId}/rotate`

- `{keyId}` 必须是 current enabled credential；
- 成功返回 `201` 和新 raw credential；
- stable `principalId`、policyVersion、role、ACL、expiry、quota 不变；
- `credentialVersion` 单调 +1；
- stale/disabled key 返回 `409 CREDENTIAL_NOT_CURRENT`；
- principal revoked/expired 返回 `409 PRINCIPAL_NOT_ACTIVE`；
- 不存在返回 `404`。

#### `DELETE /api/v1/rag/api-keys/{keyId}`

- `{keyId}` 必须是 current credential；
- 成功撤销整个 principal family 并返回 `204`；
- stale key 返回 `409 CREDENTIAL_NOT_CURRENT`；
- 已撤销 family 的 current/history 查询按是否存在返回幂等 `204` 或明确冲突，实施固定为
  **credentialVersion 等于 `nextCredentialVersion - 1` 的最后一版重复 DELETE 返回 `204`，
  更早历史版本返回 409**；
- legacy 最后一个 ADMIN 返回 `409 LAST_ADMIN_REQUIRED`。

### 9.2 新增 principal 管理视图

#### `GET /api/v1/rag/api-keys/principals`

返回每个稳定 principal 一项，按创建时间倒序，至少包含：

```text
principalId, name, role, allowedCollectionKeys,
expiresAt, requestsPerMinute, policyVersion,
status(ACTIVE|EXPIRED|REVOKED), lastUsedAt,
currentCredentialId, currentCredentialVersion, createdAt, updatedAt
```

raw credential、hash 和完整 credential history 不进入该响应。WebUI 改用此端点。

#### `PUT /api/v1/rag/api-keys/principals/{principalId}/policy`

使用完整可变 policy replacement，避免 PATCH 的 omitted/null 歧义：

```json
{
  "expectedPolicyVersion": 3,
  "name": "Indexer service",
  "expiresAt": "2027-08-23T00:00:00",
  "allowedCollectionKeys": ["docs:public:v1"],
  "requestsPerMinute": 120
}
```

规则：

- `expectedPolicyVersion` 必填；CAS 不匹配返回 `409 POLICY_VERSION_CONFLICT` 和当前 version；
- name 非空且最多 255；root-managed expiry 必须未来；
- `allowedCollectionKeys=null` 表示 unrestricted，空数组非法，最多 100；deprecated IDs 不
  出现在新 endpoint；
- quota null 表示使用 global，非空 1–1,000,000；
- role、principalId、credential version 不可修改；
- policy update 与 current credential compatibility snapshot 同事务更新；
- revoked principal 返回 `409 PRINCIPAL_NOT_ACTIVE`；
- legacy ADMIN 的 expiry 不允许由此端点修改。

### 9.3 错误与并发响应

native API 继续使用 `ErrorResponse`；`/v1` 数据面认证/限流继续使用 OpenAI envelope。管理
错误码至少固定：

- `CREDENTIAL_NOT_CURRENT`
- `PRINCIPAL_NOT_ACTIVE`
- `POLICY_VERSION_CONFLICT`
- `LAST_ADMIN_REQUIRED`
- `RATE_LIMIT_STORE_UNAVAILABLE`

冲突不返回 raw credential，不泄漏某个未经授权 principal 是否存在。root/ADMIN 管理调用
可以看到明确 404/409；普通业务 Key 仍先被权限边界拒绝。

## 10. Rotation、revoke 与 policy CAS

所有管理写入使用同一锁顺序和短事务：**principal row -> ADMIN guard（仅需要时） ->
credential rows**。这里的 row lock 来自普通条件 `UPDATE`，不写 `SELECT ... FOR UPDATE`。

### 10.1 Rotation 单赢家

事务步骤：

1. 通过 current `{keyId}` 定位 principal，执行条件 `UPDATE rag_api_principal` 原子领取
   `next_credential_version`；principal 必须未 revoked/expired。
2. 条件禁用 `{keyId}`：要求 `enabled=true` 且属于该 principal；零行则抛冲突并回滚步骤 1。
3. 生成新 raw、hash 和公开 keyId；插入新 credential version，复制当前 principal policy
   到 compatibility snapshot。
4. commit 后才返回 raw secret。

并发两个 rotate 最多一个成功；失败事务不能消耗 version。partial unique index 是最后一道
一致性保护，constraint violation 映射为稳定 409，不做无界重试。

### 10.2 Revoke 与 rotation 竞态

- revoke 先成功：principal `revoked_at` 非空，后续 rotate 返回 `PRINCIPAL_NOT_ACTIVE`。
- rotate 先成功：使用旧 `{keyId}` 的 revoke 发现它不再 current，返回
  `CREDENTIAL_NOT_CURRENT`，不会误撤销刚轮换出的 family。
- 要撤销 family，调用方刷新 principals/current credential 后再提交 current keyId。
- 相同 current DELETE 重试在 family 已撤销时，以 principal 保存的
  `nextCredentialVersion - 1` 判断它是否为最后一版；是则返回 `204`，保持 HTTP retry
  可用，更早历史版本仍返回冲突。

### 10.3 Policy 与 rotation 竞态

policy PUT 通过 `policy_version` CAS；rotation 不改变 policyVersion，但两者都先更新同一
principal row，因此串行：

- policy 先提交，rotation 新 credential snapshot 使用新 policy；
- rotation 先提交，policy PUT 同事务刷新新的 current credential snapshot；
- 两个 policy PUT 只有一个 expected version 可成功。

事务失败不得返回已经生成的 raw secret；可在取得 DB 执行权后再生成，失败 raw 仅存在于
短期进程内存并立即丢弃。

## 11. PostgreSQL 共享 quota

### 11.1 配置契约

在现有 `rag.rate-limit` 下新增：

```yaml
rag:
  rate-limit:
    enabled: true
    backend: postgresql       # local | postgresql
    strategy: principal       # postgresql backend 必须是 principal
    requests-per-minute: 60
    bucket-retention-minutes: 1440
    cleanup-interval-seconds: 300
    cleanup-batch-size: 10000
```

默认保持 `backend=local`、`strategy=ip`，不意外改变本地开发。兼容的 `ip/api-key/user` 和
`key-limits` 只用于 local backend；`postgresql` backend 遇到非 `principal` strategy 或
非空 `key-limits` 时启动失败，避免看似共享却实际按 raw/static map 运行。

生产/多实例参考配置使用：认证开启 + `backend=postgresql` + `strategy=principal`。

### 11.2 Limiter identity 与 limit

- 数据库业务 Key：stable `principalId`；
- environment root / legacy static：现有固定认证 principal ID；
- PostgreSQL backend 只读取认证 Filter 的 attribute，不解析 raw header、不回退 IP；
- 找不到认证 principal 表示装配/顺序错误，返回 `503 RATE_LIMIT_STORE_UNAVAILABLE` 并记录
  低基数错误，不放行；
- 数据库 principal 的 `requestsPerMinute` 非空时覆盖全局；root/legacy 使用全局。

rotation 期间 limiter ID 不变，因此当前分钟计数不会清零。

### 11.3 原子固定窗口

数据库以自身时钟计算 UTC minute，单条条件 upsert：

```sql
INSERT INTO rag_api_rate_limit_bucket
    (principal_id, window_start, request_count, updated_at)
VALUES
    (?, date_trunc('minute', clock_timestamp()), 1, clock_timestamp())
ON CONFLICT (principal_id, window_start) DO UPDATE
SET request_count = rag_api_rate_limit_bucket.request_count + 1,
    updated_at = clock_timestamp()
WHERE rag_api_rate_limit_bucket.request_count < ?
RETURNING request_count, window_start;
```

返回 row 表示接受；零 row 表示 quota 已满。拒绝请求不继续增加 count。`Retry-After` 使用
数据库窗口结束时间计算为 1–60 秒；继续返回 `X-RateLimit-Limit` 和
`X-RateLimit-Remaining`。native/OpenAI 429 envelope 保持各自协议。

这是一分钟固定窗口，不宣称 sliding window。长青文档在实现后必须纠正当前“滑动窗口”
表述，并明确 local 与 PostgreSQL backend 的差异。

### 11.4 故障与清理

- quota decision SQL 的 `DataAccessException` 一律 `503`，不回退 local；否则 DB 故障会
  绕过成本上限。
- 清理是 best-effort scheduled job，按 `updated_at` 删除 retention 外 rows，每批最多
  `cleanupBatchSize`，通过有界 CTE + DELETE 实现。
- 清理失败记录 warning/metric，不影响当前 quota decision；bucket 有主键和 retention，
  不执行无界表扫描。
- metrics 只标记 `backend=local|postgresql`、`result=allowed|rejected|error`、固定
  `principalType`，不标记 ID、raw path 或 credential。

## 12. WebUI 实施范围

`/webui/api-keys` 从 credential history 管理视图改为 principal 管理视图：

- 表格一行一个 principal；展示 name、stable principal ID、current credential ID/version、
  role、Collection scope、effective quota、last used、expiry、status；
- Create modal 增加可选 requests/minute；
- 新增 Edit policy modal，加载当前 version 并 PUT 完整 policy；
- Rotate 仅对 ACTIVE principal 的 current credential开放，成功 raw secret 仍只在 modal
  当前状态显示，关闭后销毁；
- Revoke 明确撤销 principal family；stale conflict 后刷新列表，不盲重试写操作；
- `POLICY_VERSION_CONFLICT` 显示当前数据已变化并刷新，用户重新提交；
- 保持 root credential 只在 React page memory，不写 localStorage/sessionStorage/URL；
- i18n 中英文 UI 文案同步；按钮使用现有图标库时优先 icon + tooltip，不做无关视觉改版。

前端 API type 增加 principal DTO 和 identity additive fields。旧 credential history client
保留，避免删除现有 public API binding。

## 13. 后端模块与实施顺序

实施按以下顺序执行，每个关键阶段先更新配套进度文档：

### 阶段 A：隔离分支与基线

1. 用户批准后，fetch 最新远端并在 `main` 合并已推送的 `origin/main`。
2. 从最新 main 创建专用 feature branch 和隔离 worktree；记录 commit、端口、测试数据库
   命名与验证目录。
3. 先编写/冻结本轮全部验收测试骨架和证据脚本，避免 review 阶段零碎补测试。

### 阶段 B：V48 与持久化边界

1. 新增 V48 migration，完成 principal、credential version、raw-null guard、quota bucket、
   admin guard 和回填。
2. 新增 principal entity/repository 或以 `JdbcTemplate` 实现条件 DML；并发路径优先使用
   显式 SQL，避免 JPA read-modify-write 掩盖 CAS 条件。
3. 保留 `RagApiKey` 作为 credential persistence model，移除 raw field。
4. 先通过迁移/backfill/约束 PostgreSQL focused tests。

### 阶段 C：认证 snapshot 与 ACL 迁移

1. 新增 `AuthenticatedApiPrincipal` / `ApiAccessPolicy`。
2. 重写权威 credential join 查询，删除认证 decision cache，增加 bounded last-used touch。
3. 更新 Filter attributes、`/auth/me` 和错误映射。
4. 一次性迁移全部 ACL consumer、Chat owner 和 worker reload；执行 production `rg` 审计。
5. 覆盖 core standalone 与 starter consumer Filter 顺序和 Bean 替换边界。

### 阶段 D：管理生命周期

1. 重写 create/list/rotate/revoke 为 principal + credential transaction。
2. 实现 principals list 和 policy PUT。
3. 实现 current credential conflict、policy CAS、legacy ADMIN guard 与幂等 revoke。
4. 更新 OpenAPI DTO、Controller tests 和 PostgreSQL 并发测试。

### 阶段 E：共享 quota

1. 在 `RagRateLimitProperties` 增加 backend/principal/retention 配置与 fail-fast validation。
2. 把 Filter 计数抽为 local/PostgreSQL decision service；local 行为保持兼容。
3. 实现 atomic bucket repository、headers、native/OpenAI errors、cleanup 与低基数 metrics。
4. 覆盖多线程 repository、Filter HTTP 和两种 topology。

### 阶段 F：WebUI 与文档

1. 更新 WebUI API client/types、principal table、create/edit/rotate/revoke modal 和 i18n。
2. 一次性完成 Vitest 与核心 Mock Playwright。
3. 实现后同步 `rest-api*`、`configuration*`、`project-context*`、`architecture*`、
   `testing-guide*`、readiness/TODO 和 migration version 浅索引。
4. 不在实现文档中留下真实 key、数据库密码或 provider secret。

## 14. 一次性验收矩阵

验收测试在实现审查开始前一次性补齐并跑通。只有影响正确性、成本安全、兼容性或数据
一致性的后续缺陷才允许修改测试；任何实质修改都重置实现审查计数。

### 14.1 PostgreSQL migration 与 repository

新增 `ManagedApiCredentialPostgresIntegrationTest`（可按职责拆类，但由同一 gate 串行执行）：

1. 空库从 V1–V48 成功；所有新约束/index 存在。
2. V47 fixture 含 active/disabled、ADMIN/NORMAL、ACL、expiry、last-used 和非空 raw column；
   V48 后每 row 独立回填，owner-compatible principalId 正确，raw 清空且非空写入失败。
3. create 同事务产生 principal + v1 credential，raw 不落库。
4. rotate 保持 principal/policy/owner/quota，旧 credential 失效，新 version 单调。
5. 20–50 个并发 rotate 只有一个成功；没有两个 active credential、没有 version gap。
6. rotate/revoke 竞态满足固定赢家语义；stale key 不能撤销新 credential。
7. 两个相同 expectedPolicyVersion 的 PUT 只有一个成功，snapshot 与 principal 一致。
8. legacy 两个并发 ADMIN revoke 不能把 count 降到 0；root 模式可显式撤销最后一个。
9. 认证无 decision cache：一个 service instance revoke，另一个 service/authenticator 下一次
   查询立即拒绝。
10. last-used 高频并发只产生有界实际 update，认证结果不依赖 touch。
11. quota 在 2 个 repository/service instance、并发 N 请求下精确接受 limit 个，其他拒绝；
    rotate 前后共用同一 bucket。
12. quota DB failure 返回 error decision，不降级 local；cleanup 每批有界。

PostgreSQL tests 必须 `skipped=0`；Docker 不可用导致 skip 不算通过。

### 14.2 HTTP / security / ACL

1. root create/list-principals/policy/rotate/revoke 完整 HTTP 契约与 `no-store`。
2. business Key 不能管理其他 principal；legacy NORMAL 只能 rotate 自己 current credential。
3. old raw credential 在 rotate/revoke 后 401；新 credential 访问相同 ACL 成功。
4. `/auth/me` 在 rotation 前后 principalId 相同、credentialId/version 改变。
5. rotation 前创建的 Chat session/history/turn status 在新 credential 下可读；旧 credential
   不可读；不同 principal 得到 anti-enumeration 404。
6. evaluation worker/replay 使用 principal policy；ACL 收紧后 fail closed，rotation 后继续。
7. Collection、Document、Search、PDF、JSON、sync、relocation、repair、embedding jobs 的
   restricted/unrestricted ACL 回归。
8. native 与 `/v1` 的 401、429、503 envelope 分别正确。
9. credential store 与 quota store 故障均不走 root/static/local fallback。
10. core standalone 与 starter consumer 都注册 authentication -> rate limit 的固定顺序；
    custom bean replacement 不产生双 Filter。

### 14.3 双实例真实服务验证

新增 `scripts/verify-managed-api-principals.sh` 作为本轮单一 gate 和证据归档入口：

1. 建立一次性 PostgreSQL/pgvector 数据库并跑 V1–V48；
2. 在隔离端口启动 backend A/B，共用数据库、root 配置和 PostgreSQL principal quota；
3. A 创建 business principal，A/B 交替请求达到同一 quota，合计只接受配置上限；
4. A rotate，B 立即拒绝旧 credential并接受新 credential；
5. A revoke，B 下一次立即 401；
6. 停止数据库或使 quota query 失败，验证 503 而非 local 放行；
7. 检查日志、接口 JSON 和数据库只读查询，不依赖截图；
8. 无论成功失败都清理自己启动的进程、端口、临时数据库/container，并写 summary。

### 14.4 后端门槛

固定执行：

```bash
# 本任务 focused unit/web tests
# 本任务 PostgreSQL integration matrix，skipped=0
mvn clean compile test-compile
mvn test
./scripts/verify-no-pessimistic-locks.sh
# postgresql profile 启动并通过 health/API smoke
```

服务启动是独立门槛，不能由 `@SpringBootTest` 或 compile 代替。

### 14.5 WebUI 门槛

```bash
cd spring-ai-rag-webui
npm run test:run
npm run typecheck
npm run build
npm run check:alignment
# 只跑本轮核心 Mock Playwright spec
```

Mock Playwright 必须断言：root unlock、principal list、create quota、policy PUT CAS、rotate
stable ID、stale 409 refresh、family revoke、raw secret 不进入 URL/storage/console。验收只以
DOM 可见性与可访问状态、request/response 和自动化断言为证据，不截图。

随后以 `scripts/dev.sh` 或本轮 gate 在隔离端口运行真实 frontend + backend + PostgreSQL，
执行同样的核心 Playwright 流程并检查网络 JSON。

### 14.6 真实 LLM 验证

所有 Mock 和基础门槛通过后，使用 main 工作区 `.env` 中的真实 provider 配置；不输出或
复制 key。调用保持有界并持续观察 backend/provider 日志：

1. root 创建低 quota、受限 Collection 的 business principal v1；
2. v1 credential 创建一个明确 session，执行 native JSON Chat 并读取 history/turn status；
3. rotate 为 v2；用 v2 在同一 session 继续 Chat，证明 history、Memory/summary 和 owner
   continuity；
4. 使用旧 raw credential 请求 Chat，必须在 provider 前 401；通过 provider 调用计数和
   日志证明没有真实 LLM 调用；
5. v2 执行 native SSE 和一次 `/v1` JSON/SSE smoke（仅当 compatibility flag 在隔离实例
   显式开启）；
6. 两个 backend 交替请求验证 quota 共用；revoke 后另一实例立即 401；
7. 总 provider 调用设置明确上限，超时/失败立即从日志判断，不空等。

真实 LLM smoke 失败不能用 Mock 结果替代。若 `.env` key 不可用，记录明确 blocker 和日志，
不得宣称充分真实验证完成。

## 15. Rollout、兼容与 rollback

### 15.1 Rollout

1. V48 是 additive migration；不重写 owner，不 drop 旧 policy snapshot 列。
2. 大块特性实施期间持续 merge 已 push 的 `origin/main`；最终合并前再 merge 一次并全量
   重验。
3. rolling deployment 期间冻结 API Key create/rotate/revoke/policy 写操作；先迁移 DB，
   再更新所有实例，最后解除管理写冻结。
4. 新实例可读取所有旧 credential；旧实例可读取新 current credential 的兼容 snapshot，
   但旧实例不理解 stable principal。
5. 所有实例升级并通过双实例 smoke 后，才启用 PostgreSQL quota backend。

### 15.2 Rollback

- 应用可回滚到旧 binary，因为旧列和 credential hash 保留；新 credential snapshot 足以让
  旧认证路径工作。
- rollback 期间必须再次冻结管理写操作；旧 binary 的 owner 会退回 `db:{credentialId}`，
  因此 rollback 只用于短时恢复，不宣称继续保持 rotation owner continuity。
- quota backend 可配置回 `local` 以恢复服务，但这会失去共享 quota；必须作为显式降级并
  告警，不能静默自动 fallback。
- 不提供 destructive down migration，不删除 principal、bucket 或 credential history。
- raw `api_key IS NULL` constraint 保留；rollback 不允许重新持久化明文。

## 16. 风险与控制

| 风险 | 控制 |
|---|---|
| ACL consumer 数量多，漏迁移会越权或误拒绝 | 引入单一 policy type；生产 `rg` 审计；覆盖所有 controller/service/worker 路径 |
| 回填错误改变历史 owner | principalId 严格等于旧 keyId；不做 family 猜测；迁移 fixture 断言 |
| 并发 rotate 产生两个 active credential | principal 条件 update + current credential CAS + partial unique；并发 PostgreSQL 测试 |
| revoke/rotate 竞态误撤销新版本 | 路径必须是 current credential；固定 409 语义；同一 principal row 串行 |
| 每请求 DB auth 增加延迟 | hash unique index + 单次 join；用 metrics/基准观察，不以缓存牺牲撤销一致性 |
| last-used 抑制 cache 被误用作授权 cache | 类型和 Bean 分离；cache 只保存 stable ID/下一次 touch 时间；安全测试撤销立即生效 |
| shared quota DB 故障放大成本 | decision fail closed 503；无自动 local fallback；低基数告警 |
| fixed window 被误认为 sliding | API/配置文档明确 backend 语义；边界秒测试 |
| mixed-version 管理写破坏 family | rollout 明确管理写冻结；旧 binary 插入缺 principal 时 fail closed |
| secret 进入证据或浏览器 | schema check、DTO/log tests、Playwright storage/URL/console assertions、日志脱敏审查 |
| legacy 最后 ADMIN 并发丢失 | singleton conditional counter；固定锁顺序；并发测试；建议生产使用 environment root |

## 17. 文档更新范围

规划阶段只更新具有长期价值的现状与方向：

- `TODO-zh-CN.md` / `TODO.md`：记录本轮选中的 production-readiness batch 和仍延后的候选；
- `openai-compatibility-readiness-zh-CN.md` / `.md`：更新代码复核日期，明确 owner continuity、
  cache、shared quota 与 schema gap；链接本规划但不宣称已实现。

实施完成后再同步行为型长青文档：

- `project-context*`、`architecture*`：stable principal、credential family、owner 与 ACL；
- `rest-api*`：新增字段、principals list、policy PUT、409/503；
- `configuration*`：local/PostgreSQL backend 与 fail-closed 配置；
- `testing-guide*`：单一 gate、双实例与真实 LLM 证据；
- `openai-compatibility-readiness*` / `TODO*`：把已完成项从 gap 更新为事实；
- `AGENTS.md` 的 Flyway 版本浅索引仅在 V48 实施落地后更新。

规划/进度文档只使用中文；以上正式长青文档保持中英文同步。

## 18. 规划审查与实施交接

本规划完成后执行三轮互不重叠、只读、限定范围审查：

1. **事实与范围轮**：代码、迁移、owner consumer、装配拓扑和候选比较是否准确；
2. **数据/并发/兼容轮**：V48 回填、CAS 顺序、API retry、rollout/rollback 是否闭合；
3. **验收/成本/安全轮**：测试矩阵是否覆盖所有新代码，真实 LLM 是否有界，secret、quota、
   fail-closed 与低基数观测是否可证明。

任何实质问题立即修正文档并把全局计数重置为 0；措辞、格式或实施时自然暴露的文件行号
不触发重置。只有连续三轮无修改才结束规划。

规划通过、双语长青文档同步、`verify-project-docs` / `git diff --check` 通过并将 main
commit/push 后暂停，等待用户审阅。**不得在同一规划工作区直接开始生产代码实施。**
用户批准后才创建隔离 feature worktree，并从本文件和配套进度账本恢复。
