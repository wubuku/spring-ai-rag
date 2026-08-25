# 业务服务接入指南

> [English](business-client-integration.md) | [中文](business-client-integration-zh-CN.md)

本文面向通过后端到后端 HTTP 调用接入 spring-ai-rag 的业务服务。它说明生产接入所需的
credential、Collection、JSON Record、重试、升级和验收边界。外部文档的完整同步算法另见
[外部文档同步 Client 指南](external-document-sync-client-guide-zh-CN.md)。

## 1. 权威边界

RAG 服务负责：

- Collection、文档、JSONB payload、派生索引和 embedding job 的持久状态；
- API principal、credential、Collection ACL、共享配额、轮换和吊销；
- `collectionKey + sourceNamespace + externalId` 地址上的 CAS、精确重放和 tombstone；
- 当前文档是否可检索的 lifecycle/readiness 判定。

调用方负责：

- 来源业务对象、稳定外部 ID、完整期望状态和 opaque `sourceRevision`；
- credential 的安全存储与部署 binding；
- 网络重试、checkpoint、dead letter 和对 `409` 的重新读取/人工修复；
- 决定何时等待 embedding readiness，不能把 mutation 成功等同于已经 `READY`。

Collection 是当前授权边界。`sourceNamespace` 只隔离外部身份，不限制一个 credential 在
Collection 内可读写的记录。浏览器、移动端和不可信客户端不得持有业务 credential。

## 2. Credential 与当前身份

### Environment root

`RAG_ROOT_API_KEY` 是 operator credential。它可以使用数据面并管理数据库业务
principal，只应存在于受控运维环境，不应进入业务服务或 WebUI 持久存储。

### 数据库业务 principal

root 创建的业务 principal 当前固定为 `NORMAL`，拥有产品级
`RAG_READ`、`RAG_WRITE`，但不能调用 API Key 管理端点。生产集成应给每个服务或
connector 分配独立的 restricted principal，并只允许目标 Collection。

原始 credential 仅在创建或轮换响应中展示一次。调用方必须在该响应事务边界内把它写入
secret manager；服务不会再次返回原值，列表和自省响应也不包含 raw secret 或 hash。

credential 只能放在 Header：

```http
X-API-Key: <business-credential>
```

也可使用 `Authorization: Bearer <business-credential>`。不要放入 query string、URL、
日志、命令行参数、数据库业务 payload 或浏览器 storage。root mode 会拒绝
`?apiKey=`。

### 自省与 binding

业务服务启动时调用：

```text
GET /api/v1/rag/auth/me
```

restricted principal 的核心响应：

```json
{
  "principalType": "DATABASE_API_KEY",
  "principalId": "rag_p_example",
  "rootMode": true,
  "capabilities": ["RAG_READ", "RAG_WRITE"],
  "credentialId": "rag_k_example",
  "credentialVersion": 1,
  "policyVersion": 1,
  "principalRole": "NORMAL",
  "collectionAccessMode": "RESTRICTED",
  "allowedCollectionKeys": ["customer-42:records:v1"]
}
```

`rootMode` 表示服务是否配置了 environment root，不表示当前 credential 就是 root。
调用方应先检查 `principalType`，再验证 `principalId`、credential/policy version、
`collectionAccessMode` 和完整 allow-list。unrestricted principal 返回
`collectionAccessMode=UNRESTRICTED`、`allowedCollectionKeys=null`。

自省只描述 policy。随后应对每个期望 key 调用
`GET /api/v1/rag/collections/by-key?collectionKey=...`，确认 Collection 当前存在且 active。
自省无法完整解析 ACL 时返回 `503`，调用方必须绑定失败关闭，不能接受部分 allow-list。
响应始终带 `Cache-Control: no-store`。

## 3. 稳定身份与长度

| 字段 | 规则 |
|---|---|
| `collectionKey` | 1-128 个 `0x21..0x7e` 可见 ASCII 字符；区分大小写、全局唯一、创建后不可变，软删除后仍保留占用 |
| `sourceNamespace` | 省略或空白规范化为 `default`；显式值 trim 后最多 128 字符，只允许 `0x20..0x7e` |
| `externalId` | trim 后非空、最多 255 字符；保持 opaque/Unicode，必须来自来源系统的稳定不可变 ID |
| `sourceRevision` | 调用方提供的非空 opaque 完整状态版本，trim 后最多 255 字符，不按字符串或数字比较大小 |
| `expectedSourceRevision` | 可选 CAS 前置版本；非空时 trim 后最多 255 字符 |

JSON Record 分开保存：

- `retrievalText`：参与分块、关键词索引和 embedding 的自然语言文本；
- `jsonbPayload`：结构化返回并通过 `payloadContains` 做 PostgreSQL JSONB containment。

只修改 payload 不调用 embedding provider；修改 `retrievalText` 才会使派生状态更新。

## 4. Provisioning 与部署 binding

推荐顺序：

1. operator 使用 environment root 创建目标 Collection。
2. operator 创建 restricted business principal，并设置唯一名称、到期时间、RPM 和
   `allowedCollectionKeys`。
3. 在创建响应中一次性接收 raw credential，立即写入 secret manager。
4. 业务服务只从环境变量、挂载 secret 或等价 secret provider 读取 credential。
5. 服务启动时执行 `/auth/me`，精确核对 principal 与 allow-list。
6. 对目标 Collection 执行只读 by-key 探针，再开始消费业务事件。
7. 发布后运行本指南第 8 节的合同门禁。

当前 provisioning 没有幂等键。创建请求超时后，不要盲目重复创建；先由 operator 在管理面
按稳定名称/目标 binding 对账。若 principal 已存在但 raw credential 未安全保存，轮换其当前
credential，而不是再创建一个无主 principal。

## 5. JSON Record mutation 合同

推荐用 `embeddingPolicy=ASYNC` 调用
`POST /api/v1/rag/json-records/upsert`：

- 新地址不发送 `expectedSourceRevision`；
- 更新发送服务端最后接受的 revision 作为 `expectedSourceRevision`；
- 同 revision、同完整受管内容是精确幂等重放；
- 同 revision、不同内容或错误 CAS 返回 `409`；
- mutation 成功先保证主记录和持久化 job 已提交，不保证 embedding 已 fresh；
- provider 最终失败时，主记录、revision、payload 和 enabled 状态仍保留；lifecycle 会报告
  `embeddingStatus=FAILED`，不会通过第二次业务 mutation 删除或覆盖记录；
- 来源删除使用 `DELETE /json-records/by-external-id` 创建 tombstone；
- 之后使用新的 revision upsert 会恢复同一个 `documentId`。

需要检索就绪时，读取文档 lifecycle 或 Collection embedding readiness：

| `searchability` | 含义 |
|---|---|
| `READY` | 当前关键词和向量派生都已就绪 |
| `KEYWORD_ONLY` | 当前关键词可用，向量仍排队、失败或未请求 |
| `INDEXING` | 当前本地派生尚未就绪 |
| `FAILED` | 当前派生失败，结合 `retryable` 和错误码处理 |
| `NOT_REQUESTED` | 使用了 `SKIP` |
| `DISABLED` | 文档已禁用或 tombstone |

## 6. 重试与 credential 生命周期

| 结果 | 调用方动作 |
|---|---|
| 网络超时、`408`、`425`、`429`、`5xx` | 有界指数退避和 jitter，精确重放同一请求 |
| `409` | 停止该 identity，读取当前状态并重新生成期望 mutation；禁止自动覆盖 |
| `400` | 契约或数据错误，进入 dead letter |
| `401` | credential 无效、过期、已轮换或已吊销；停止数据投递 |
| `403` | ACL/binding 错误；不要根据响应猜测 Collection 是否存在 |

restricted principal 对未授权或未知 Collection 的 search、lookup、upsert 和 tombstone
统一先返回通用 `403`；错误信封不回显目标 key、Collection 是否存在或内部 ID。

embedding provider 的单次模型调用重试预算由
`rag.embedding.retry-max-attempts` / `RAG_EMBEDDING_RETRY_MAX_ATTEMPTS` 控制，范围
1-10，默认 10 以保持现有行为；仅 transient/network 错误会按指数退避重试。它与
durable embedding job 的 `default-max-attempts`/`max-attempts` 是两层独立预算；
生产配置必须同时有界。

轮换由 operator 使用 root 发起。新响应中的 credential 仍只展示一次；旧 credential
立即失效，新 credential 保持同一 `principalId` 和 policy。推荐先安全分发新 secret，
滚动更新所有实例并通过 `/auth/me` 验证，再结束旧版本部署。吊销和到期都应视为终止错误，
不能无界重试。

## 7. 部署、升级与回滚

- liveness 使用 `/actuator/health/liveness`；readiness 使用
  `/actuator/health/readiness`。readiness group 表示进程、Spring readiness 与数据库可用，
  不承诺外部 embedding provider 或某个 Collection 已检索就绪。
- embedding 可用性读取文档 lifecycle 或
  `/api/v1/rag/collections/embedding-readiness`；业务 binding 另用 `/auth/me` 和
  Collection by-key。
- 空库或升级环境必须按顺序执行 Flyway V1-V48。本能力没有新增 migration，不能因此跳过
  既有迁移。
- 生产调用方应锁定已验收的 Git commit 或由该 commit 构建的不可变镜像。当前 Maven/API
  版本仍为 `1.0.0`。
- `/auth/me` 本次只增加字段。旧 client 会忽略新增字段；依赖新增字段的 client 必须先运行
  合同门禁，再升级业务实例。
- 回滚本功能不需要 schema 回退。若回滚到不含新增自省字段的服务版本，依赖 binding 自检的
  新 client 必须停止发布或回滚，不能降级为“允许启动”。

## 8. 一键接入验收

完整门禁：

```bash
./scripts/verify-business-client-readiness.sh
```

最终候选 commit 的可复现门禁：

```bash
BUSINESS_CLIENT_REQUIRE_CLEAN_GIT=true \
./scripts/verify-business-client-readiness.sh
```

仅复跑真实服务、HTTP 和真实前端阶段：

```bash
BUSINESS_CLIENT_VERIFY_PHASE=real \
./scripts/verify-business-client-readiness.sh
```

完整门禁串行执行 focused 后端测试、三个隔离 PostgreSQL 集成矩阵、
`mvn clean compile test-compile`、WebUI typecheck/Vitest/生产构建、核心 Mock
Playwright、文档/禁锁/密钥/diff 门禁，以及真实 Spring Boot、109 项 HTTP 合同和真实
API Key Playwright。

脚本默认使用隔离端口 `18084`、`18085`、`15184`、`15185` 和一次性
`pgvector/pgvector:pg16`。证据写入
`.verification/business-client-readiness/<run-id>/`；private credential 文件由退出 trap
删除。`release-manifest.json` 记录运行结果、验证阶段、完整 Git SHA、初始 tree state、
项目/OpenAPI 版本、API base path、最新 Flyway migration、passed steps、PostgreSQL image
和 HTTP contract check 数；未到达的运行时事实使用 JSON `null`，不保存 credential、URL、
payload、external ID 或 private path。确定性 embedding stub 验证真实 Spring AI
embedding HTTP 路径及 503 失败保留合同，但本能力不改变 Chat，因此该门禁不调用 Chat LLM。

## 9. 当前限制

- `RAG_READ`/`RAG_WRITE` 当前是产品级描述，尚未作为 operation-scoped 权限独立强制。
- principal provisioning 尚无幂等键。
- 尚无独立的运行时 capability discovery 端点；当前以 OpenAPI、Git commit 和离线
  `release-manifest.json` 锁定兼容性。
- 当前身份体系是 environment root + 数据库业务 principal，不提供 OAuth/OIDC federation
  或独立 tenant 层级。

这些后续边界见 [TODO](TODO-zh-CN.md#受管-api-principal-后续边界)。
