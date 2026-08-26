# 操作级 API 能力控制实施规划

> 状态：规划完成，准备实施  
> 规划基线：`main` / `b755eb43`（2026-08-26）  
> 配套进度：[2026-08-26_OPERATION_SCOPED_API_CAPABILITIES_PROGRESS.md](2026-08-26_OPERATION_SCOPED_API_CAPABILITIES_PROGRESS.md)

## 1. 摘要

本轮为 API principal 增加真正生效的操作级能力控制。当前系统已经有稳定
principal、版本化 credential、Collection ACL、策略 CAS 和 `/api/v1/rag/auth/me`
自描述接口，但 `RAG_READ` 与 `RAG_WRITE` 目前只是静态返回的描述，数据库业务
principal 实际仍可访问完整的数据读写面。结果是外部客户端无法获得最小权限的
只读凭据，任何一个可用于 Chat/检索的数据库 Key 也可以调用文档、Collection、
JSON Record、同步任务等写入 API。

本轮只解决“数据面操作能力”这一独立边界：

- 数据库 API principal 可配置为只读或读写；
- 认证后的请求在中央 HTTP 过滤器中按路由和方法执行能力检查；
- `/auth/me`、创建/更新/轮换响应和 WebUI 管理界面反映真实能力；
- 数据库迁移、兼容默认值、测试和双语长青文档同步完成。

本轮不改变 Collection ACL、root 管理边界、legacy static key 语义、限流、
Chat 记忆、工具调用、Embedding、检索算法或数据库锁策略。

## 2. 当前基线与已确认事实

### 2.1 基线

- 本仓库是独立 Git 仓库，远端为 `origin`。
- 实施基线为最新本地 `main`：`b755eb43`，其已与 `origin/main` 对齐。
- main 工作区位于 `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`，
  当前干净。
- 特性实施必须使用从该 main 创建的专用 worktree，不得在旧的
  `feat/boundary-aware-heuristic-rerank-20260824` 工作区继续开发。
- 当前 Flyway 迁移为 V1–V48；本轮新增 V49。
- 默认 API 端口、profile、向量维度和构建命令遵循
  [AGENTS.md](../../../AGENTS.md) 与
  [开发者参考](../../developer-reference-zh-CN.md)。

### 2.2 已存在的认证与授权链

以下代码事实已在 main 上逐一核对：

- [`ApiKeyAuthFilter`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/filter/ApiKeyAuthFilter.java)
  先解析 root、数据库 credential 和 legacy static key，并把数据库认证结果作为
  不可变 [`AuthenticatedApiPrincipal`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/security/AuthenticatedApiPrincipal.java)
  放入 request attribute。
- [`ApiAccessPolicy`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiAccessPolicy.java)
  和 [`ApiKeyCollectionAccess`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java)
  已统一 Collection 范围与文档访问检查，但尚无操作能力检查。
- [`ApiKeyManagementService`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/ApiKeyManagementService.java)
  以 [`RagApiPrincipal`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagApiPrincipal.java)
  作为稳定策略权威，credential rotation 继承 principal 的名称、过期时间、角色、
  Collection ACL 和配额。
- [`RagApiKeyRepository`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/repository/RagApiKeyRepository.java)
  的认证投影从 principal 读取当前策略，符合本轮“能力存储在 principal 而非
  credential”的设计。
- [`RagWebSecurityConfiguration`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/config/RagWebSecurityConfiguration.java)
  已将认证过滤器置于限流过滤器之前；本轮能力过滤器必须位于认证之后、限流之前。
- [`ApiKeyIdentityController`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/ApiKeyIdentityController.java)
  当前把数据库 principal 的能力静态写成 `RAG_READ`、`RAG_WRITE`，正是本轮需要
  改为读取权威策略的地方。

### 2.3 已存在的 API 和 WebUI 表面

- 创建和管理 API principal 的接口位于
  [`ApiKeyController`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/ApiKeyController.java)：
  `POST /api/v1/rag/api-keys`、`GET /principals` 和
  `PUT /principals/{principalId}/policy`。
- 创建、轮换和列表响应分别是
  [`ApiKeyCreatedResponse`](../../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/ApiKeyCreatedResponse.java)、
  [`ApiKeyResponse`](../../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/ApiKeyResponse.java)、
  [`ApiPrincipalResponse`](../../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/ApiPrincipalResponse.java)。
- 创建和策略更新请求分别是
  [`ApiKeyCreateRequest`](../../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/ApiKeyCreateRequest.java)
  与 [`ApiPrincipalPolicyUpdateRequest`](../../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/ApiPrincipalPolicyUpdateRequest.java)。
- WebUI API、类型和页面位于
  [`src/api/apikeys.ts`](../../../spring-ai-rag-webui/src/api/apikeys.ts)、
  [`src/api/auth.ts`](../../../spring-ai-rag-webui/src/api/auth.ts) 和
  [`src/pages/ApiKeys.tsx`](../../../spring-ai-rag-webui/src/pages/ApiKeys.tsx)；
  现有页面已经有创建、策略编辑、轮换和端到端 Mock/real 流程，可在相同工作流
  中增加能力选择和断言。
- 数据面 controller 覆盖 `/api/v1/rag/**` 及 OpenAI 兼容的 `/v1/**`。当前统一
  异常格式由 [`GlobalExceptionHandler`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/GlobalExceptionHandler.java)
  提供，但过滤器拒绝必须自行返回 JSON，因为请求尚未进入 controller。

## 3. 问题定义、目标与非目标

### 3.1 问题

`/auth/me` 返回的 `RAG_READ`、`RAG_WRITE` 没有对应的持久化策略或执行点。
数据库 NORMAL principal 只受 Collection ACL 和个别管理 controller 判断约束；
只要知道写入 API，就可以使用本来只用于 Chat/检索的凭据写入文档或结构化记录。

### 3.2 目标

1. 为数据库 principal 增加可持久化、可 CAS 更新、可轮换继承的能力策略。
2. 支持一个最小但明确的能力集合：
   - `["RAG_READ"]`；
   - `["RAG_READ", "RAG_WRITE"]`。
3. 对已有 V48 数据和省略字段的旧客户端保持读写兼容。
4. 在中央认证后过滤器执行能力检查，避免只保护少数 controller 而遗漏新路由。
5. 让创建/编辑 API、`/auth/me`、列表/轮换响应和 WebUI 显示同一份权威状态。
6. 用 PostgreSQL 迁移和尽可能端到端的 HTTP/集成测试证明：
   只读 principal 能检索和 Chat，但不能修改 RAG 数据；读写 principal 行为不回归；
   rotation、policy CAS 和 root/legacy 兼容路径保持正确。

### 3.3 非目标

- 不把能力扩展成任意字符串权限系统。
- 不增加用户/租户/Collection 之外的身份模型。
- 不改变已有 `ADMIN` 管理接口的角色判定。
- 不把 Chat 内部历史持久化视为外部 `RAG_WRITE`；Chat、检索、模型比较和只读
  评估查询属于读能力。
- 不改变 `/auth/me` 的 root 解锁要求。
- 不开放由普通数据库 principal 创建或修改其他 principal 的管理能力。
- 不重构现有 controller 或把所有安全判断移入业务 service。
- 不增加新的锁、advisory lock、`SKIP LOCKED`、缓存授权决定或后台同步任务。

## 4. 冻结的授权模型

### 4.1 能力名称与规范化

能力名称使用 API 字符串以保持现有 `/auth/me` 合同兼容：

| 能力 | 含义 |
|------|------|
| `RAG_READ` | 访问读类 RAG 检索、Chat、只读模型/评估接口 |
| `RAG_WRITE` | 访问写入、删除、恢复、Embedding、同步、Collection/Record 管理接口；同时隐含 `RAG_READ` |

只允许两个规范化集合，返回顺序固定为 `RAG_READ` 在前、`RAG_WRITE` 在后。

- 省略 `capabilities` 的创建请求：使用完整读写集合，保持旧客户端兼容。
- 创建请求明确传 `["RAG_READ"]`：创建只读 principal。
- 创建请求明确传 `["RAG_READ", "RAG_WRITE"]`：创建读写 principal。
- 空数组、重复值、未知值、只有 `RAG_WRITE`：返回 `400 BAD_REQUEST`，不写入数据库。
- policy update 省略 `capabilities`：保留当前 principal 能力，只更新请求中其他字段。
- policy update 明确传数组：按同样规则校验并通过现有 `expectedPolicyVersion` CAS 更新。
- `RAG_WRITE` 不允许脱离 `RAG_READ` 单独存在。

能力字段属于稳定 principal 的权威策略，不属于某一代 credential。credential
rotation 不接受新的能力参数，并自动继承当前 principal 能力；策略更新后，下一次
认证立即读取新能力。现有认证路径每次联表查询，不增加一个独立授权缓存。

V48 兼容读取中的 `NULL` 能力值可以按完整读写处理；任何非空但不属于两个规范
集合的持久化值都必须 fail-closed，不能回退成完整权限。认证路径将这类策略解析
失败作为 credential/policy service unavailable（HTTP 503）处理，并记录不含秘密的
诊断信息；列表、identity 和策略读取也不得把非法值展示或转换成更高权限。

### 4.2 兼容 principal

以下路径保持现有 unrestricted 语义，不被本轮收紧：

- `ENVIRONMENT_ROOT`；
- `LEGACY_STATIC`；
- auth disabled 时没有数据库 principal 的请求；
- 数据库 `ADMIN` principal：继续拥有完整管理/数据面语义，避免现有管理员被
  新字段意外锁死。

数据库 `NORMAL` principal 才按持久化能力集合执行数据面门禁。数据库 `ADMIN`
principal 的有效能力始终是完整读写；如果策略更新显式提交只读集合，服务层拒绝
该请求，避免“响应显示只读但执行仍为管理员全权限”的不一致。省略能力或显式提交
完整集合都保留管理员兼容语义。`API_KEY_MANAGE`
仍由 root/controller 的既有逻辑负责，不作为本轮可配置的第三种数据库能力。

### 4.3 路由分类

能力过滤器只检查已经成功认证且类型为数据库 API principal 的请求。管理和身份
端点交给现有逻辑，不将其误判为 RAG 写入：

- 排除 `/api/v1/rag/auth/**`；
- 排除 `/api/v1/rag/api-keys/**`；
- `/actuator/**`、Swagger、OpenAPI、health、error 等既有排除路径不变。

对其余 `/api/**` 和 `/v1/**` 请求：

1. `GET`、`HEAD`、`OPTIONS` 默认要求 `RAG_READ`；
2. 明确的读类 `POST` 要求 `RAG_READ`：
   - `/api/v1/rag/search`；
   - `/api/v1/rag/json-records/search`；
   - `/api/v1/rag/chat`、`/api/v1/rag/chat/ask`、
     `/api/v1/rag/chat/stream`；
   - `/api/v1/rag/models/compare`；
   - `/api/v1/rag/evaluation/answer-quality`、
     `/api/v1/rag/evaluation/semantic`、
     `/api/v1/rag/evaluation/semantic/batch`；
   - `/v1/chat/completions`。
3. 其他 `POST`、`PUT`、`PATCH`、`DELETE` 默认要求 `RAG_WRITE`。

上述默认策略以“新写入路由默认安全拒绝”为目的。`/rag/evaluation/evaluate`、
`/rag/evaluation/batch`、`/rag/evaluation/feedback` 等会持久化数据的接口继续
要求 `RAG_WRITE`；只读的 metrics/report/history 使用 GET，因此要求 `RAG_READ`。
如果未来新增一个不修改外部 RAG 状态的 POST，必须在能力过滤器的显式读路由表中
加入并配套测试；不能依赖模糊的路径前缀推断。

Chat 请求即使会写入内部会话历史，也归入 `RAG_READ`，因为该能力控制的是
对外 RAG 数据面写入，不是 Chat 会话本身的生命周期。

### 4.4 拒绝语义

- 缺少 `RAG_READ` 或 `RAG_WRITE` 时返回 HTTP `403`，不进入 controller，不消耗
  PostgreSQL 共享限流配额。
- `/api/**` 返回现有 `ErrorResponse` 形状，错误码为 `FORBIDDEN`，detail/message
  明确指出所需能力但不泄露数据库内容。
- `/v1/**` 返回现有 OpenAI 兼容错误形状，使用 `403` 和稳定的
  `insufficient_permissions` 错误 code。
- 能力过滤器不得把未认证请求转换为 403；认证缺失/错误仍由
  `ApiKeyAuthFilter` 返回 401。
- 能力拒绝不改动 principal、credential、Collection ACL、审计时间或限流计数。

## 5. 数据模型与 API 契约

### 5.1 V49 迁移

新建
`spring-ai-rag-core/src/main/resources/db/migration/V49__operation_scoped_api_capabilities.sql`：

```sql
ALTER TABLE rag_api_principal
    ADD COLUMN capabilities VARCHAR(64) NOT NULL DEFAULT 'RAG_READ,RAG_WRITE';

ALTER TABLE rag_api_principal
    ADD CONSTRAINT ck_rag_api_principal_capabilities
    CHECK (capabilities IN ('RAG_READ', 'RAG_READ,RAG_WRITE'));
```

迁移必须保证：

- V48 创建的所有 principal 自动得到完整读写能力；
- 新列非空且通过数据库约束限制到两个规范字符串；
- 由 Flyway 从 V48 一次性升级到 V49，不能改写既有 V1–V48，也不以重复执行同一
  migration 作为运行时能力；
- `rag_api_key` 不新增能力列，避免 credential 与 stable policy 再次分叉。

实体 [`RagApiPrincipal`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagApiPrincipal.java)
增加持久化字段并与 V49 名称一致。服务层统一负责字符串与规范 `List<String>`
之间的转换和校验；数据库检查约束是最后一道防线，不取代 Java 校验。

### 5.2 请求

在 `ApiKeyCreateRequest` 增加可选字段：

```json
{
  "name": "Search Client",
  "expiresAt": "2027-08-26T00:00:00",
  "capabilities": ["RAG_READ"]
}
```

在 `ApiPrincipalPolicyUpdateRequest` 增加同名可选字段。字段为 `null` 时分别执行
“创建默认完整读写”和“策略更新保留当前值”；数组的大小、枚举值、重复项和隐含
关系由共享能力规范化器校验。

### 5.3 响应

以下响应增加 `capabilities`：

- `ApiKeyCreatedResponse`：创建和轮换时返回本次 credential 对应的 principal 能力；
- `ApiKeyResponse`：兼容的 API key 列表元数据；
- `ApiPrincipalResponse`：WebUI principal 列表和策略编辑的权威状态；
- `ApiKeyIdentityResponse`：数据库 principal 返回真实能力，root/legacy 仍返回
  兼容的完整能力集合；数据库 ADMIN 也返回其有效的完整能力集合。

`/api/v1/rag/auth/me` 的字段不改名、不改变数组语义。能力顺序和规范化结果稳定，
便于外部 client 做严格比较。

## 6. 实施设计

### 6.1 共享能力规范化器

在 core security 包增加小型、无状态的能力支持类（推荐命名
`ApiCapabilitySupport`）：

- 定义能力常量和完整集合；
- 将 nullable 请求数组解析为规范 `List<String>`；
- 拒绝非法集合；
- 将数据库字符串反序列化为规范列表；
- 提供 `hasRead`、`hasWrite` 和“数据库 ADMIN/兼容 principal 是否 unrestricted”
  所需的集中判断。

不要把用户提交的 list 直接保存到数据库，也不要在 controller、filter、service
分别复制校验规则。静态 `ApiAccessPolicy` 增加默认完整能力，保证旧测试 double、
legacy entity 和扩展实现不会因新增接口方法而失效；数据库认证 snapshot 覆盖为
principal 的真实能力。persisted capability 的 `null` 仅用于 V48 兼容；非空非法值
必须抛出可识别的 policy 解析错误，由认证过滤链 fail-closed，不得默认完整读写。

### 6.2 认证 snapshot 与查询

- `AuthenticatedApiPrincipal` 增加能力组件，并保留当前 9 参数构造器作为兼容
  辅助构造器，默认完整读写，避免大量旧测试和扩展源码无意义破坏。
- `RagApiKeyRepository.AuthenticationProjection` 增加 `capabilities` 投影。
- `ApiKeyManagementService.authenticate` 与 `findActivePrincipal` 将 principal
  能力映射进 snapshot。
- `createPrincipal` 在保存前规范化请求能力并设置 V49 字段。
- `updatePolicy` 在 request 能力为 null 时读取并保留当前值，否则使用规范化结果；
  对 ADMIN principal 将显式只读请求拒绝，保证持久化值、identity 和实际执行语义
  一致；
  更新 principal 后，继续同步当前 credential 的兼容字段，不新增 credential 能力
  权威。
- `createdResponse`、`toResponse`、`toPrincipalResponse` 使用同一个有效能力结果；
  ADMIN 的有效结果固定为完整读写，不能只返回其原始存储值。

### 6.3 中央能力过滤器

推荐新增
`spring-ai-rag-core/src/main/java/com/springairag/core/filter/ApiCapabilityFilter.java`
而不是把大量路由判断继续塞进认证过滤器：

- 读取 `AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE`；
- 非数据库 principal、ADMIN 或无 snapshot 时保持兼容放行；
- 管理/身份端点跳过能力门禁；
- 依据 §4.3 的有限显式读 POST 表及方法默认值计算所需能力；
- 不满足时直接写 `/api` 或 `/v1` 对应错误 JSON；
- 不访问数据库、不缓存授权决定、不改变 request principal；
- 被认证过滤器以后的顺序注册为 `-5`，限流过滤器仍为 `0`。

过滤器中使用精确路径匹配或受控的 `startsWith` 范围，避免将
`/api/v1/rag/chat-history` 等未来路径意外归类为 Chat 读操作。类中保留一个
可直接测试的 package-private route classifier，测试覆盖方法、路径、管理排除和
未知 mutating route 默认写能力。

### 6.4 WebUI

在现有 API Key 管理页面内增量修改：

- 创建 principal 增加“只读 / 读写”单选项，默认读写；
- 编辑 principal 增加同一选择，初始化为服务返回的真实能力；
- 列表增加能力列或明确能力 badge；不再仅用 `NORMAL`/`fullRag` 文案暗示权限；
- 创建成功、轮换成功的 shown-once 对话框显示能力；
- `src/api/apikeys.ts`、`src/api/auth.ts` 类型同步；
- Mock 身份和 API 返回加入能力字段，核心 Playwright 断言请求 JSON 和 DOM；
- 现有 root 解锁逻辑继续只检查 `API_KEY_MANAGE`，不要求 root 的 RAG 能力字段。

不新增独立页面、不把能力控制移到前端。前端只负责编辑和展示，后端过滤器是
唯一有效的执行边界。

### 6.5 文档

行为落地后更新双语长青文档：

- `docs/configuration*.md`：API principal capability 字段、默认值和兼容模式；
- `docs/rest-api*.md`：创建/策略更新/identity 响应示例、读写路由语义和 403；
- `docs/project-context*.md` 或 `docs/architecture*.md`：稳定安全边界摘要；
- `docs/testing-guide*.md`：V49 和本轮 PostgreSQL/HTTP 验收命令；
- `docs/release-checklist*.md`：Flyway V1–V49 与 capability 门禁。

规划和进度文档保持中文单语；完成后将本规划和进度移入
`docs/drafts/archive/`，归档只保留实施历史，稳定事实以双语长青文档为准。

## 7. 文件级实施顺序

1. 在特性 worktree 确认基线、创建 progress 账本并记录 worktree/分支。
2. 增加 V49、principal entity、API capability support、access policy 和认证
   projection。
3. 扩展 API DTO、service、identity/controller 响应并完成后端单元测试。
4. 注册中央能力过滤器，完成方法/路径分类、错误序列化和 HTTP 集成测试。
5. 扩展 PostgreSQL principal 生命周期测试：V49 backfill、只读/读写创建、
   policy CAS、rotation inheritance、真实数据面读写边界。
6. 更新 WebUI 类型、页面、i18n、Vitest 和核心 Mock Playwright。
7. 更新双语长青文档、OpenAPI/合同断言、测试指南和发布清单。
8. 先执行基本集成硬门槛，再执行用户要求的固定范围验证；任何实质修复都要重新
   跑受影响门槛。

## 8. 一次性验收矩阵

### 8.1 API 与 PostgreSQL

| 场景 | 证据 |
|------|------|
| V48 数据迁移到 V49 后旧 principal 为完整读写 | PostgreSQL migration test + SQL read-back |
| 新建 `RAG_READ` principal | HTTP/service integration，数据库列和 response 一致 |
| 新建省略能力字段 | HTTP/service integration，默认完整读写 |
| 非法能力集合 | DTO/controller/service test，HTTP 400 且无落库 |
| policy CAS 更新只读/读写 | PostgreSQL integration，版本递增、stale version 409 |
| policy 省略能力 | PostgreSQL integration，能力保持不变 |
| ADMIN 显式降为只读 | service/HTTP test 返回 400，数据库能力不被改写 |
| 非法持久化能力值 | policy parser/authentication test fail-closed，不回退为完整权限 |
| rotation | PostgreSQL integration，principal ID、能力和 quota 保持，旧 credential 失效 |
| 只读 GET/search/chat | MockMvc/HTTP integration 返回非 403 |
| 只读写入/删除/Embedding/sync | MockMvc/HTTP integration 返回 403，service 不执行 |
| 读写 principal | 同一数据面请求允许通过既有 controller |
| root/legacy/auth-disabled/ADMIN | 既有兼容测试 + capability filter test |
| OpenAI 兼容路径 | `/v1/chat/completions` 只读允许，未知 mutating route 需要写，错误 JSON 合同正确 |
| 新未知 POST/PUT/PATCH/DELETE | route classifier test 默认 `RAG_WRITE` |

后端最低门槛：

```bash
mvn -pl spring-ai-rag-core -am -DskipTests=false \
  -Dmanaged-api-principal.it.enabled=true \
  -Dapi-capability.it.enabled=true test
mvn clean compile test-compile
```

如果 PostgreSQL 集成测试使用外部数据库，必须使用可处置数据库并设置项目既有的
清理确认变量；不得把连接串、密码或 API key 写入文档。

### 8.2 WebUI

```bash
cd spring-ai-rag-webui
npx tsc -b --pretty false
npm run test:run
npm run build
npx playwright test e2e/api-keys.spec.ts --project=chromium
```

Playwright 只用 DOM 可见性、可访问角色/名称、请求方法与 JSON、响应状态和断言；
不使用截图作为验收证据。

核心断言至少包括：

- 只读/读写控件正确初始化；
- create 请求携带选定能力；
- edit policy 请求携带能力和 `expectedPolicyVersion`；
- 列表与 shown-once 成功对话框展示能力；
- root 解锁与既有页面导航不回归。

### 8.3 运行时与仓库门禁

按项目既有脚本执行：

```bash
./scripts/verify-project-docs.sh
./scripts/verify-no-pessimistic-locks.sh
git diff --check
```

服务必须能以 `postgresql` profile 启动；需要真实 HTTP 时使用隔离端口，先验证
`/api/v1/rag/health`，再用 root/业务 Key 调用能力相关接口。真实 LLM 不属于本轮
必要验证，因为本轮不改变 Chat prompt、模型路由或 provider 调用；若联合运行触及
Chat，只做已有 Mock 断言，不把模型调用结果当作 capability 正确性的替代证据。

### 8.4 Git 交付

特性分支完成后：

1. `git status`、`git diff` 检查全部修改；
2. 本地 commit；
3. `git fetch origin`，把最新 `origin/main` merge 到特性分支；
4. 记录 merge 后基线并完整重跑 PostgreSQL/Maven、前端和运行时门槛；
5. push 特性分支；
6. 将特性分支 merge 到 main，push `origin/main`；
7. 确认 main、origin/main 和工作区状态；
8. 安全移除隔离 worktree，不使用 `stash`、`reset --hard` 或破坏性 checkout。

合并 main 后的最终验证顺序遵循
[delivery-workflow-zh-CN.md §8](../../delivery-workflow-zh-CN.md)，合并前结果只能作为
历史证据。

## 9. 风险、回滚与可逆边界

### 9.1 主要风险

- 路由分类过宽会误阻止已有只读 POST，过窄会让只读 Key 继续写入；
- DTO 能力字段与数据库 CSV 不一致会造成 `/auth/me`、列表和过滤器看到不同状态；
- 只更新 principal 而遗漏旧 credential 兼容字段，可能破坏旧扩展读取；
- 过滤器顺序错误会在认证前拿不到 principal，或在限流后错误消耗配额；
- WebUI 只显示角色、不显示真实能力，会造成运维误判。

### 9.2 缓解

- 路由表采用显式读 POST 白名单，其余 mutating 方法默认写；
- 规范化器作为唯一 Java 规则源，V49 CHECK 作为数据库兜底；
- 认证投影、identity、service response 和 WebUI 使用同一能力语义；
- 以 `-10` 认证、`-5` 能力、`0` 限流固定顺序注册；
- 用真实 PostgreSQL migration/lifecycle 与 MockMvc route tests 同时覆盖；
- 前端验收检查网络 JSON 和 DOM，不依赖截图。

### 9.3 回滚

- 代码回滚前不能删除 V49；Flyway 已执行迁移只允许通过新的前向迁移修复。
- 若需暂时关闭门禁，可在代码中仅对过滤器注册增加显式配置开关，但本轮默认
  不增加开关，避免形成“描述有权限、执行未生效”的隐性状态。若实现阶段发现
  必须提供紧急回退，开关必须 fail-closed 说明、测试和文档同步。
- 数据库字段保留不会影响旧代码读取；旧代码默认视为完整读写，满足兼容回退。

## 10. 完成定义

只有同时满足以下条件才算本轮完成：

1. 规划三轮固定范围审查达到 `3/3`，期间无未处理实质问题；
2. V49、DTO、service、认证 snapshot、中央过滤器和 WebUI 完成；
3. 只读 principal 的读/写边界由 HTTP/数据库集成测试证明；
4. `mvn clean compile test-compile` 和本任务相关 PostgreSQL 测试通过；
5. 前端 typecheck、Vitest、生产构建和核心 Mock Playwright 通过；
6. 文档、锁策略、diff 和密钥门禁通过；
7. 合并最新 `origin/main` 后重新完成最终验证；
8. 特性分支和 `main` 均按要求 commit/push，最终 worktree 干净并安全移除。

## 11. 规划审查记录

规划审查由配套 progress 文档记录。审查范围固定为：

1. 需求闭环、自包含性、默认决策与非目标；
2. 代码、schema、API、安全、并发与兼容可行性；
3. 实施顺序、验收矩阵、发布、回滚、恢复与 Git 交付风险。

只有连续三轮无实质问题且规划正文未修改，才进入实施。
