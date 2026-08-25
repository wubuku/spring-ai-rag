# 业务服务接入就绪 P0 实施规划

> **状态**：规划已完成，最终文本已连续通过 `3/3` 实质审查，待实施
>
> **规划日期**：2026-08-25
>
> **规划基线**：`main` / `origin/main` @ `c97167ec`；Spring Boot `3.5.16`；
> Spring AI `1.1.8`；Java `21`；Flyway `V1-V48`
>
> **规划工作区**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
>
> **推荐实施分支**：`feat/business-client-integration-readiness-20260825`
>
> 本文是当前仓库的单语实施规划。它只描述通用 RAG 服务能力，不依赖任何外部业务项目的
> 代码、术语或文档。稳定行为在实施和验收完成后提升到双语长青文档。

## 1. 执行摘要

本轮目标是让一个只持有数据库业务 API Key 的外部后端服务，可以自动证明自己的身份和
Collection 授权范围，并能用一条可重复执行的合同测试确认 JSON Record 数据面已经满足
生产接入要求。

当前系统已经具备大部分底座：

- environment root、数据库业务 principal、版本化 credential、轮换/吊销和 PostgreSQL
  共享配额；
- Collection key、Collection ACL 和 restricted caller 的防枚举语义；
- JSON Record 的稳定外部身份、revision CAS、精确重放、tombstone/恢复、
  `payloadContains` 和 `SYNC/ASYNC/SKIP` embedding policy；
- Flyway `V48`、PostgreSQL 集成测试、真实 HTTP E2E 和 API Key WebUI。

当前 P0 缺口不是新增一套数据面，而是把这些能力组成一个可验证的生产契约：

1. 向后兼容增强 `GET /api/v1/rag/auth/me`，返回当前数据库 principal 的 role、实际
   Collection access mode 和自身 allow-list。
2. 冻结 root、数据库 restricted/unrestricted、数据库 ADMIN 与 legacy static 的
   introspection 语义；不返回 raw secret、hash 或其他 principal 的信息。
3. 把 OpenAPI、WebUI 类型、双语 API 文档和通用业务服务接入 runbook 同步到同一契约。
4. 提供一条自启动隔离 PostgreSQL、确定性 embedding stub 和真实 Spring Boot 的 P0
   合同门禁，覆盖鉴权、ACL、JSON Record、CAS、tombstone/恢复和 ASYNC。

本轮不新增租户或业务实体，不增加外部项目专用 API，不实现 operation-scoped
`RAG_READ`/`RAG_WRITE` 强制授权，不引入新的 Flyway migration，也不要求真实 LLM 调用。

## 2. 当前基线与问题定义

### 2.1 认证与 principal 基线

生产认证链为：

```text
ApiKeyAuthFilter
  -> EnvironmentRootCredentialResolver
  -> ApiKeyManagementService.authenticate(raw credential)
  -> request-scoped AuthenticatedApiPrincipal
  -> Controller / ApiKeyCollectionAccess
```

关键事实：

- `RAG_ROOT_API_KEY` 配置后进入 root mode，所有 `/api/**` 要求 environment root 或有效的
  数据库 credential；query-string `apiKey` 被拒绝。
- environment root 的 `principalType=ENVIRONMENT_ROOT`，可使用数据面和 API Key 管理面。
- 数据库 credential 的 `principalType=DATABASE_API_KEY`，认证结果携带稳定
  `principalId`、当前 `credentialId/version`、`ApiKeyRole`、内部 Collection ID
  allow-list、`policyVersion` 和可选 RPM。
- root mode 中数据库业务 principal 固定为 `NORMAL`；legacy 模式仍可能存在数据库
  `ADMIN` principal 和 `LEGACY_STATIC` credential。
- `ApiKeyCollectionAccess.isUnrestricted` 的真实授权语义是：
  `policy == null`、role 为 `ADMIN`，或 allow-list 为空时 unrestricted；其他情况
  restricted。
- `CollectionIdentityResolver.mapKeys(ids)` 能把 principal 自身的内部 Collection ID
  映射为稳定外部 `collectionKey`，包含软删除 Collection，但不会查询或返回 allow-list
  之外的 Collection。

近距离事实入口：

- [REST API：认证与 API Key](../rest-api-zh-CN.md#认证)
- [架构：受管 API principal](../architecture-zh-CN.md)
- [测试指南：受管 API Principal PostgreSQL 矩阵](../testing-guide-zh-CN.md#受管-api-principal-postgresql-矩阵)
- `spring-ai-rag-core/.../filter/ApiKeyAuthFilter.java`
- `spring-ai-rag-core/.../security/ApiKeyCollectionAccess.java`
- `spring-ai-rag-core/.../service/ApiKeyManagementService.java`

### 2.2 `/auth/me` 的现状缺口

当前响应已包含：

```json
{
  "principalType": "DATABASE_API_KEY",
  "principalId": "rag_p_...",
  "rootMode": true,
  "capabilities": ["RAG_READ", "RAG_WRITE"],
  "credentialId": "rag_k_...",
  "credentialVersion": 1,
  "policyVersion": 1
}
```

但外部服务无法据此自动判断：

- 当前数据库 principal 是 `NORMAL` 还是 legacy `ADMIN`；
- 当前授权是 restricted 还是 unrestricted；
- restricted principal 的当前外部 Collection key allow-list 是否与部署 binding 一致。

管理端点虽已返回 `allowedCollectionKeys`，但调用方不能使用 root 权限读取管理面来证明
自己的业务 credential。要求外部服务人工核对数据库或管理控制台，会破坏最小权限和自动化
部署验证。

### 2.3 JSON Record 数据面基线

现有 `json-records` API 已支持：

```text
POST   /api/v1/rag/json-records/upsert
POST   /api/v1/rag/json-records/search
GET    /api/v1/rag/json-records/{documentId}
GET    /api/v1/rag/json-records/by-external-id
DELETE /api/v1/rag/json-records/by-external-id
```

稳定外部身份为：

```text
collectionKey + sourceNamespace + externalId
```

已有实现将 JSON payload 保存为 PostgreSQL JSONB，只对 `retrievalText` 建立检索派生；
`sourceRevision` 是 opaque token，`expectedSourceRevision` 执行 CAS；相同 revision 和相同
受管内容精确重放幂等，不同内容复用 revision 返回冲突；source delete 创建 tombstone，
后续更高 revision upsert 恢复同一 document identity。`payloadContains` 在数据库候选阶段
使用 JSONB containment，Collection ACL 在 scope 解析前生效。

现有能力分散在多个单元、PostgreSQL 和脚本门禁中，但缺少一条以“外部业务 credential”
为主体的端到端合同测试，把 provisioning、introspection 和完整数据面串起来。

## 3. 冻结的目标、非目标与默认决策

### 3.1 本轮目标

1. `/auth/me` 增加：
   - `principalRole`
   - `collectionAccessMode`
   - `allowedCollectionKeys`
2. 新字段反映当前请求实际使用的 principal/policy，不接受 principal ID、Collection key
   或其他请求参数来选择被查看对象。
3. OpenAPI、Java DTO、WebUI TypeScript 类型、Mock 和真实浏览器断言保持一致。
4. 新增通用业务服务接入双语 runbook，覆盖：
   - root 与业务 credential 边界；
   - Collection 与 JSON Record 身份约定；
   - provisioning、binding 验证、轮换、吊销和过期；
   - CAS、tombstone、ASYNC、重试和回源边界；
   - 版本锁定、迁移、健康检查和合同测试命令。
5. 新增一键 P0 门禁，真实覆盖：
   - root/业务 credential header 认证和 query credential 拒绝；
   - restricted introspection 和跨 Collection deny；
   - 精确重放、CAS、lookup、tombstone 和恢复；
   - `payloadContains`；
   - ASYNC 先持久化、后就绪；
   - raw credential 一次性展示与轮换/吊销。

### 3.2 明确非目标

- 不新增外部业务项目、租户、组织、项目、素材或 publication 模型。
- 不让 RAG 服务消费外部 outbox、Webhook 或业务数据库。
- 不新增客户专用 endpoint、payload schema 或 Collection 命名规则。
- 不把 `sourceNamespace`、payload 或 metadata 当作 Collection ACL。
- 不允许浏览器直接获得业务 API Key，也不把 raw secret 写入日志、测试摘要或 Git。
- 不在本轮实现 operation-scoped capability enforcement。数据库业务 principal 的
  `capabilities=["RAG_READ","RAG_WRITE"]` 继续表示当前产品级全数据面能力。
- 不实现 API Key provisioning idempotency、批量 mutation receipt 或协议 capability
  discovery endpoint；这些保留为后续 P1/P2。
- 不新增 Flyway migration；本轮只读取 V48 已有 principal policy 和 Collection 表。
- 不要求真实 Chat/LLM 调用；本轮使用确定性 embedding stub 验证 ASYNC 数据路径。

### 3.3 字段语义

#### 数据库业务 principal

restricted `NORMAL`：

```json
{
  "principalType": "DATABASE_API_KEY",
  "principalRole": "NORMAL",
  "collectionAccessMode": "RESTRICTED",
  "allowedCollectionKeys": ["customer-a:records:v1"]
}
```

unrestricted `NORMAL`：

```json
{
  "principalType": "DATABASE_API_KEY",
  "principalRole": "NORMAL",
  "collectionAccessMode": "UNRESTRICTED",
  "allowedCollectionKeys": null
}
```

数据库 `ADMIN` 始终按实际授权返回 `UNRESTRICTED` 和 `allowedCollectionKeys=null`，
即使 legacy 行中残留了 allowed IDs；不能把一个实际上 unrestricted 的 ADMIN 描述为
restricted。

restricted allow-list 的 key 顺序按持久化的规范化 ID 顺序稳定输出。映射包含已软删除但仍
被策略引用的 Collection key，使响应描述的是 principal policy；业务 binding 还必须用
Collection 只读探针确认 Collection 当前 active。若 legacy policy 引用了已不存在的内部 ID，
端点不得返回部分 allow-list；返回服务端错误并记录无 secret 的策略诊断，调用方绑定失败关闭。

#### Environment root 与 legacy static

这两类 principal 没有数据库 `ApiKeyRole`：

```json
{
  "principalRole": null,
  "collectionAccessMode": "UNRESTRICTED",
  "allowedCollectionKeys": null
}
```

调用方必须首先依据 `principalType` 判断身份，不能把 `rootMode=true` 解释为“当前 credential
就是 root”。`rootMode` 继续只表示服务是否配置 environment root。

#### 兼容性

- 只增加字段，不删除或重命名已有字段。
- `capabilities`、credential version 和 policy version 保持原语义。
- JSON `null` 是 unrestricted/non-database 语义的一部分，不省略
  `principalRole`、`collectionAccessMode` 或 `allowedCollectionKeys`。
- DTO 对这三个新增字段显式使用始终序列化语义，不能依赖全局 Jackson null-inclusion
  默认值。
- 响应继续使用 `Cache-Control: no-store`。
- DTO/OpenAPI 将 access mode 限定为 `RESTRICTED|UNRESTRICTED`，role 限定为
  `NORMAL|ADMIN` 或 `null`。

## 4. 实施设计

### 4.1 API DTO

在 `spring-ai-rag-api` 增加公共枚举：

```text
CollectionAccessMode
  - RESTRICTED
  - UNRESTRICTED
```

扩展 `ApiKeyIdentityResponse`：

```text
String principalRole
CollectionAccessMode collectionAccessMode
List<String> allowedCollectionKeys
```

字段带明确 OpenAPI description、nullable 和 allowable values。DTO 不依赖 core 的
`ApiKeyRole`，controller 只序列化其 `name()`。

### 4.2 Controller 映射

`ApiKeyIdentityController` 注入 `CollectionIdentityResolver`，按下列顺序构造响应：

1. 从认证过滤器 request attributes 取得当前 `principalType/principalId`。
2. `rootMode` 仍由 `EnvironmentRootCredentialResolver.isConfigured()` 决定。
3. environment root / legacy static：
   - role `null`
   - unrestricted
   - keys `null`
4. database principal：
   - 从同一 request-scoped `AuthenticatedApiPrincipal` 读取 role、policy 和 versions；
   - 使用 `ApiKeyCollectionAccess.isUnrestricted(principal)` 计算 access mode；
   - restricted 时解析内部 IDs，并通过 `CollectionIdentityResolver.mapKeys` 只映射自身
     policy；
   - 映射数量不完整时返回 `503 SERVICE_UNAVAILABLE`，不返回部分 scope；
   - unrestricted 时不做 Collection 查询。
5. 未识别或属性不完整时保持现有 401/安全失败，不通过 query/header 选择其他 principal。

不新增 repository 查询 principal ID 的入口，避免 introspection 变成越权读取接口。

### 4.3 OpenAPI 与 WebUI

- `OpenApiContractTest` 把 `/rag/auth/me` 和 `ApiKeyIdentityResponse` 加入必需契约，并断言
  新字段及 access-mode enum 出现在 schema 中。
- `spring-ai-rag-webui/src/api/auth.ts` 同步新增 nullable 字段。
- `e2e/api-mocks.ts` 的 root/business identity fixture 同步新契约。
- `api-key-real.spec.ts` 在真实 credential 轮换后断言数据库 business principal 的 role、
  access mode 和 allow-list；现有管理台仍只用 `principalType + API_KEY_MANAGE` 解锁，
  不扩大前端权限。

### 4.4 通用合同门禁

新增两层脚本：

```text
scripts/business-client-contract-e2e.sh
  -> 对一个已经启动的 root-mode 服务执行纯 HTTP/JSON 合同

scripts/verify-business-client-readiness.sh
  -> 准备隔离 PostgreSQL 与确定性 embedding stub
  -> 启动真实 Spring Boot
  -> 调用 business-client-contract-e2e.sh
  -> 执行后端/前端/文档硬门槛
  -> 清理进程、端口、数据库和含临时 raw secret 的 private artifacts
```

HTTP 合同固定创建两个 active Collection 和两个 principal：

- restricted principal：只允许 Collection A；
- unrestricted principal：用于 introspection 负向契约，不承担业务写入。

合同测试断言：

1. environment root identity 为 root 类型、role null、unrestricted，不含 secret/hash。
2. restricted principal 创建响应只在当次返回 raw secret，后续 principal/list/auth 响应
   均不含该值。
3. restricted principal 用 `X-API-Key` 与 `Authorization: Bearer` 均可认证；
   query-string credential 返回 401。
4. `/auth/me` 返回 `NORMAL/RESTRICTED/[Collection A key]` 及正确 credential/policy version。
5. unrestricted DB principal 返回 `NORMAL/UNRESTRICTED/null`。
6. Collection A 的 JSON Record upsert/search/lookup 成功；Collection B 和随机未知 key
   对 restricted principal 均返回 403，响应不包含目标 key 或存在性细节。
7. 相同 identity/revision/内容重放保持同一 document ID；同 revision 不同内容冲突；
   错误 expected revision 返回稳定 409。
8. 正确 CAS 更新成功；payload-only 更新不使 fresh embedding 无意义失效。
9. tombstone 保留 identity；lookup 可观察 disabled/sourceDeletedAt；更高 revision upsert
   恢复相同 document ID。
10. `payloadContains` 命中和明确不命中都在 Collection ACL 内执行。
11. `embeddingPolicy=ASYNC` 的 upsert 先返回持久化成功与 queued/coalesced 状态，随后通过
    readiness/lookup 或数据库只读断言达到 fresh；embedding 失败不得删除 record。
12. 轮换后旧 credential 401、新 credential 保持同 principal 和 policy；吊销后新
    credential 401。
13. Collection key 1/128、namespace 128、externalId 255 的有效边界和超限/非法 key 的
    400 契约由 focused Web/API 测试覆盖；HTTP 合同至少覆盖 128 成功与 129 失败。

HTTP 合同不得把 root 或长期业务 credential 放入 shell argv、stdout/stderr、summary 或
持久化证据。脚本把 credential 写入权限 `0600` 的 private curl config/secret 文件，
`curl` 进程参数只出现文件路径。query-string 拒绝测试必须使用专门创建的一次性数据库
credential，并通过 `--data-urlencode apiKey@<private-file>` 构造请求：该 credential 只会
在这一次测试请求的 HTTP query 中短暂出现，这是验证“有效 credential 也不能通过 query
认证”不可避免的协议事实；脚本不得开启 curl verbose/trace，不得打印最终 URL，服务测试
配置不得记录 query string，断言完成后立即吊销该 credential。退出 trap 删除全部 private
文件；若中途失败，trap 也必须尝试吊销该一次性 principal/credential。

确定性 embedding stub 只实现测试所需的 OpenAI-compatible `/v1/embeddings`，返回固定
1024 维有限向量，记录有界调用计数，不读取真实 API Key。它不替代真实 LLM 测试，因为本轮
没有 Chat/LLM 行为变化。

### 4.5 文档与发布准备

新增双语长青文档：

```text
docs/business-client-integration-zh-CN.md
docs/business-client-integration.md
```

内容包括：

- 服务与调用方的权威数据边界；
- root/业务 credential 及 secret 一次性展示；
- Collection/JSON Record 身份和长度限制；
- provisioning、binding、rotation、revoke、expiry runbook；
- retry/CAS/tombstone/ASYNC 语义；
- 健康检查、Flyway 顺序、升级与回滚；
- P0 合同测试入口和证据目录；
- 当前数据库业务 principal 默认同时具有读写能力，operation-scoped capability 尚未实施。

同步更新：

- `docs/rest-api*.md`
- `docs/testing-guide*.md`
- `docs/developer-reference*.md`
- `docs/index*.md`
- `docs/release-checklist*.md`
- `docs/TODO*.md`：记录 operation-scoped capability、provisioning idempotency 和
  machine-readable capability endpoint 的后续边界。

可锁定版本使用最终推送的 Git commit；Maven/API 版本继续为 `1.0.0`。容器 digest/tag 由
既有发布流水线在该 commit 上生成，本功能分支不伪造未构建的生产镜像 digest。无 schema
变化时，迁移说明明确为“仍需按顺序执行 Flyway V1-V48，无新增迁移”。

## 5. 文件级实施切片

### Slice A：Introspection 契约

预计文件：

- `spring-ai-rag-api/.../enums/CollectionAccessMode.java`
- `spring-ai-rag-api/.../dto/ApiKeyIdentityResponse.java`
- `spring-ai-rag-core/.../controller/ApiKeyIdentityController.java`
- `ApiKeyIdentityControllerTest`
- `ApiKeyRootModeWebIntegrationTest`
- `OpenApiContractTest`

完成标准：root、restricted/unrestricted NORMAL、ADMIN、legacy、缺失身份和不完整 ACL 映射
均有确定性测试。

### Slice B：业务客户端 HTTP 合同

预计文件：

- `scripts/business-client-contract-e2e.sh`
- `scripts/verify-business-client-readiness.sh`
- 必要的脚本 fixture 或测试专用 embedding stub
- 现有 managed-principal / JSON Record / lifecycle focused tests 的少量补强

完成标准：从空 PostgreSQL 到真实 HTTP 的 P0 合同一次执行通过，所有 temporary secret
只在权限受限的 private 目录中短暂存在并在退出时删除。

### Slice C：WebUI 与 OpenAPI 消费

预计文件：

- `spring-ai-rag-webui/src/api/auth.ts`
- `spring-ai-rag-webui/e2e/api-mocks.ts`
- `spring-ai-rag-webui/e2e/api-key-real.spec.ts`

完成标准：旧管理台行为不变，Mock 与真实 Playwright 都断言 additive contract。

### Slice D：双语长青文档与发布入口

预计文件：

- `docs/business-client-integration*.md`
- `docs/rest-api*.md`
- `docs/testing-guide*.md`
- `docs/developer-reference*.md`
- `docs/index*.md`
- `docs/release-checklist*.md`
- `docs/TODO*.md`
- `docs/drafts/README*.md`

完成标准：文档自包含、双语结构同步、无外部项目背景或 secret，项目文档门禁通过。

## 6. 验收矩阵

### 6.1 快速 focused 门槛

```bash
mvn -pl spring-ai-rag-core -am \
  -Dtest=ApiKeyIdentityControllerTest,ApiKeyRootModeWebIntegrationTest,\
OpenApiContractTest,ApiKeyAuthFilterTest,ApiKeyCollectionAccessTest,\
RagJsonRecordControllerWebTest,JsonRecordServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

另运行 API DTO 测试、Shell syntax 和脚本自身静态检查。

### 6.2 PostgreSQL 与真实 HTTP

一键主门槛：

```bash
./scripts/verify-business-client-readiness.sh
```

必须实际执行、不得 skip：

- `ManagedApiPrincipalPostgresIntegrationTest`
- `DocumentLifecyclePostgresIntegrationTest`
- `JsonbStructuredRecordsPostgresIntegrationTest`
- 新增/补强的 business-client HTTP contract
- Flyway 从空库迁移到 `V48`

若 Testcontainers 不可用，使用 `.env` PostgreSQL 创建独立一次性数据库；不得使用日常业务
数据库。脚本退出必须清理数据库、端口、stub、服务进程和 temporary secret artifacts。

### 6.3 后端硬门槛

```bash
mvn clean compile test-compile
```

并确认 PostgreSQL profile 服务可以启动、健康为 `UP`、Flyway 为 `V48`。任务相关集成测试
应比 controller 单元测试更高优先，结果必须检查 Surefire `skipped=0`。

### 6.4 前端硬门槛

```bash
cd spring-ai-rag-webui
npm run typecheck
npm run test:run
npm run build
npx playwright test e2e/api-key-mvp.spec.ts --project=chromium
```

真实全栈阶段运行 `e2e/api-key-real.spec.ts`。Playwright 保持 `screenshot: off`，只使用
DOM、可访问状态、请求/响应、JSON 和自动化断言。

### 6.5 静态门禁

```bash
./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-project-docs.sh
git diff --check
```

另对新增行执行 secret 扫描；测试响应文件不得进入 Git。

### 6.6 真实依赖判断

本轮不改变 Chat prompt、模型路由、Tool Calling 或 LLM 输出，因此真实 LLM 调用不适用。
ASYNC embedding 使用确定性 OpenAI-compatible stub 完整验证持久化、job 和 readiness
路径；最终可选用 `.env` 真实 embedding provider 做一次有界交叉验证，但它不是替代本地
确定性合同的主证据。

## 7. 收敛检查范围

规划 `3/3` 固定范围：

1. 需求闭环、通用项目边界、自包含性、目标/非目标和默认语义。
2. DTO/OpenAPI、认证属性、ACL 映射、数据库/并发、安全和兼容可行性。
3. 脚本清理、验收矩阵、运行时、文档、发布、回滚和 Git 交付。

实现硬门槛通过后，执行三轮互不重叠的限时只读检查：

1. principal/credential/Collection ACL、anti-enumeration、secret 和失败关闭。
2. JSON Record identity、revision CAS、tombstone/恢复、ASYNC 和 HTTP/OpenAPI 兼容。
3. 测试证据、脚本清理、前端契约、双语文档、发布与 Git 交付。

发现影响正确性、安全、兼容或数据一致性的实质问题时修复，计数重置为 `0`，并重跑受影响
门槛。风格偏好和 P1/P2 不在收敛阶段扩展。

## 8. 风险、回滚与可逆边界

| 风险 | 控制 |
|---|---|
| Introspection 返回错误 scope | 只使用 request-scoped principal；restricted 才映射自身 IDs；映射不完整返回 503 |
| ADMIN 残留 allow-list 被误报 restricted | access mode 复用生产 `ApiKeyCollectionAccess.isUnrestricted` |
| 软删除 Collection 造成 scope 歧义 | auth/me 描述 policy；binding 另做 active Collection 探针 |
| Additive JSON 破坏旧客户端 | 只增加字段；已有字段、状态码和 Cache-Control 不变 |
| 合同脚本泄漏 raw secret | private 目录 `0700`、credential 文件 `0600`、不进入 argv/日志/证据；query 拒绝仅使用一次性 credential，禁用 URL 日志并立即吊销；trap 删除 |
| ASYNC 测试依赖外部网络 | 本地固定维度 embedding stub，无真实 key |
| 合同脚本成为第二套业务实现 | 只调用公开 API 和只读 DB 断言，不复制 service 规则 |
| 规划膨胀到 P1/P2 | operation scope、provisioning idempotency、capability endpoint 明确延期 |

回滚只需恢复 additive DTO/controller/WebUI 类型和新脚本文档；没有 migration 和持久数据格式
变化。旧客户端在回滚前后都只依赖已有字段。若外部调用方已经要求新字段，则应先停止新的
binding，再回滚服务，不得静默把 unrestricted credential 当作 restricted。

## 9. 进度与 Git 交付

实施开始时创建：

```text
docs/drafts/BUSINESS_CLIENT_INTEGRATION_READINESS_PROGRESS.md
```

每个关键切片完成前先更新进度账本，记录分支、基线、验证结果、临时数据库/端口和下一步，
不记录 raw key、完整 payload 或外部绝对路径。

规划在 `main` 上完成并通过 `3/3` 后：

1. 提交并推送规划/归档文档，使 `main` 干净。
2. 基于最新已推送 `origin/main` 创建专用分支和隔离 worktree。
3. 实施期间跟进 `origin/main`；最终 merge 后重新执行完整验收。
4. 推送特性分支，合并并推送 `main`。
5. 确认主工作区干净，安全移除特性 worktree。

## 10. 完成定义

只有以下条件全部满足才可报告完成：

1. `/auth/me` additive contract 在 Java DTO、controller、OpenAPI 和 WebUI 类型中一致。
2. root、restricted/unrestricted NORMAL、ADMIN、legacy 和未认证语义均有测试。
3. 通用 P0 HTTP 合同从空 PostgreSQL 到真实服务完整通过且无 secret 泄漏。
4. JSON Record 的 ACL、幂等/CAS、tombstone/恢复、payload filter 和 ASYNC 被自动化覆盖。
5. `mvn clean compile test-compile`、相关 PostgreSQL 测试和服务启动通过。
6. WebUI typecheck、Vitest、生产 build、Mock 与真实 API Key Playwright 通过。
7. 双语长青文档、OpenAPI、测试/开发者入口和发布说明同步，文档门禁通过。
8. 实现连续三轮限定范围审查无修改。
9. 特性已合并并推送 `main`，工作区干净，隔离 worktree 已移除。
