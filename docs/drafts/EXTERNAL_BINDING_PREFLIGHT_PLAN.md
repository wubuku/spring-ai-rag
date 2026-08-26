# 已部署业务绑定预检与可移交证据 P0 实施规划

> **状态**：规划连续三轮无修改审查已通过（`3/3`），可以实施
>
> **规划日期**：2026-08-26
>
> **规划基线**：`main == origin/main` @ `88f9314b`；Spring Boot `3.5.16`；
> Spring AI `1.1.8`；Java `21`；Flyway `V1-V48`
>
> **规划工作区**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
>
> **推荐实施分支**：`feat/deployed-binding-preflight-20260826`
>
> 本文是当前仓库的单语、自包含实施规划。它面向任何通过后端到后端 HTTP 调用接入
> spring-ai-rag 的业务服务，不依赖外部项目代码、术语、文档或业务模型。

## 1. 执行摘要

当前 `main` 已经具备生产外部投影所需的数据面 P0 合同：

- environment root、数据库业务 principal、版本化 credential、轮换/吊销、共享 quota；
- `/api/v1/rag/auth/me` 的 principal type、role、restricted/unrestricted 模式和自身
  Collection key allow-list；
- restricted principal 对 search、lookup、upsert、tombstone 的完整 Collection ACL 与
  防枚举语义；
- JSON Record 的稳定外部身份、revision CAS、精确重放、tombstone/恢复、
  `payloadContains` 和持久化 ASYNC embedding；
- provider 失败不回滚已提交 Record；
- 一次性 PostgreSQL、真实 Spring Boot、109 项真实 HTTP 合同和机器可读 release manifest。

现有 `scripts/verify-business-client-readiness.sh` 证明的是“某个源码候选在一次性 root-mode
环境中满足合同”。它会自行启动 PostgreSQL、创建 root、Collection 和业务 principal，因此
不适合直接放进业务服务的部署 binding、预发布环境 canary 或 credential 轮换后验证：

1. 业务调用方不应持有 root；
2. 只读 binding 检查不应写入任何 Collection；
3. mutation smoke 必须显式限制到专用 canary Collection，不能误写真实业务 Collection；
4. 调用方需要机器可判定的本次目标实例、身份策略、OpenAPI 和数据面结果证据，而不是复用
   源码构建门禁的内部日志；
5. 失败或中断时必须使用同一外部身份对账，并尽力留下 tombstone，而不是盲目生成新 identity。

本轮新增一个面向**已部署实例**的通用绑定预检脚本：

- 默认只读，只做 readiness、OpenAPI、`/auth/me` 和 Collection by-key 探针；
- 可选 mutation 模式要求双重显式确认，并只能写调用方指定的专用 canary Collection；
- mutation 模式验证 ASYNC、精确重放、CAS 409、payload 检索、tombstone 和恢复，最后再次
  tombstone；
- credential 只从权限受控文件读取，不进入环境值、命令参数、日志或报告；
- 始终生成无密钥、无 payload 的机器可读报告；
- 在现有 disposable 真实服务门禁中同时验证只读成功、allow-list 不匹配失败和 canary
  mutation 成功。

本轮不新增服务端 API、schema 或 WebUI 页面。价值在于把已经交付的 P0 服务合同转化为
业务调用方可以安全执行、可自动阻断错误 binding 的部署门禁。

## 2. P0 需求与当前状态差距矩阵

| 通用 P0 能力 | 当前状态 | 本轮结论 |
|---|---|---|
| root 创建未来过期、`NORMAL`、restricted principal；secret 一次性返回 | 已由 V48 管理面和真实合同覆盖 | 不重复实现 |
| Bearer / `X-API-Key` 数据面认证，拒绝 query credential | 已覆盖 | 不重复实现 |
| `/auth/me` 返回 role、access mode 和完整 allow-list | 已覆盖并写入 OpenAPI/双语文档 | 预检消费并严格校验 |
| restricted principal 的全数据面 ACL 与未知 Collection 防枚举 | 已覆盖双向真实合同 | 预检以期望 allow-list fail closed |
| JSON Record replay、CAS、lookup、tombstone、恢复 | 已覆盖 | canary 模式复用这些公开合同 |
| ASYNC 持久化与 readiness 分离，provider 失败保留 Record | 已覆盖真实 Spring AI HTTP 路径 | canary 模式验证部署实例的成功路径 |
| `payloadContains` 数据库下推且不绕过 ACL | 已覆盖 | canary 模式执行唯一 marker 检索 |
| identity/revision/Collection key 边界 | 已覆盖真实合同 | 不在远端预检重复破坏性边界测试 |
| 健康、OpenAPI 版本、迁移与源码候选 release manifest | 已覆盖源码候选 | 预检读取部署实例 readiness 和 OpenAPI |
| 可锁定的不可变源码 tag 或镜像 digest | 当前只有完整 Git SHA；release checklist 的 tag 项仍未完成 | **本轮在最终验证 main 上创建并推送通用源码 tag** |
| 无 root 的部署 binding 与专用 canary smoke | 缺少可复用 runner；当前只能手工 curl | **本轮实现** |
| 部署 binding 的机器可读、无密钥证据 | 当前 release manifest 只描述源码候选 | **本轮实现** |
| operation-scoped `RAG_READ` / `RAG_WRITE` | 当前仍为产品级描述 | P1，非本轮目标 |
| provisioning 幂等键 | 当前需 operator 对账 | P1，非本轮目标 |
| 独立 capability discovery endpoint | 当前使用 OpenAPI + `/auth/me` | P2，非本轮目标 |
| batch receipt / 大规模同步状态查询 | 首期不依赖 | P2，非本轮目标 |

## 3. 已核对的当前事实

### 3.1 公开端点

预检只使用现有公开合同：

```text
GET    /actuator/health/readiness
GET    /v3/api-docs
GET    /api/v1/rag/auth/me
GET    /api/v1/rag/collections/by-key
POST   /api/v1/rag/json-records/upsert
POST   /api/v1/rag/json-records/search
GET    /api/v1/rag/json-records/by-external-id
DELETE /api/v1/rag/json-records/by-external-id
```

近距离事实入口：

- [业务服务接入指南](../business-client-integration-zh-CN.md)
- [REST API：认证与 JSON Record](../rest-api-zh-CN.md)
- [业务服务接入测试](../testing-guide-zh-CN.md#业务服务接入就绪验证)
- `scripts/business-client-contract-e2e.sh`
- `scripts/verify-business-client-readiness.sh`

### 3.2 当前身份与 ACL

生产 binding 只接受满足以下全部条件的当前 credential：

```text
principalType == DATABASE_API_KEY
principalRole == NORMAL
collectionAccessMode == RESTRICTED
allowedCollectionKeys 与调用方期望集合精确相等
```

不能只检查“包含目标 Collection”。存在额外 allow-list 项也应失败，因为部署配置可能绑定了
错误 credential，或者 operator 已意外扩大权限。数组顺序不构成语义，比较前应排序并去重；
输入期望列表包含重复项时直接视为配置错误。

`rootMode` 只表示服务配置了 environment root，不能用于判断当前 credential 身份。
environment root、legacy/static、数据库 `ADMIN` 和 unrestricted database principal 都不能
通过本预检。

### 3.3 当前 mutation 语义

canary mutation 使用稳定三元身份：

```text
canaryCollectionKey
  + spring-ai-rag.binding-preflight.v1
  + preflight-<run-id>
```

每次运行使用唯一 `run-id`，同一次运行的重试和对账必须复用同一 external ID 与 revision
快照。流程中的 revision 是脚本固定生成的 opaque token，不按大小比较：

```text
created -> updated -> tombstoned -> restored -> cleanup-tombstoned
```

同 revision、同内容重放必须返回同一 `documentId`。错误
`expectedSourceRevision` 必须返回 `409`。每次成功 mutation 后，脚本记录服务端已接受的
revision；网络结果不确定时先 GET 对账，不生成新 external ID。

### 3.4 现有验证边界

`scripts/business-client-contract-e2e.sh` 需要 root credential 文件，并在 disposable 服务中
创建多个 Collection 和 principal。它适合作为仓库发布合同，不应修改成直接面向共享环境的
runner。本轮新增独立脚本，并在该合同脚本中把新 runner 当作黑盒调用，以避免用静态 review
证明脚本正确。

## 4. 目标、非目标与安全默认

### 4.1 目标

1. 提供默认无副作用的已部署实例 read-only binding preflight。
2. 严格验证数据库 `NORMAL + RESTRICTED` principal 与完整期望 allow-list。
3. 验证 readiness、OpenAPI 版本和所需 P0 path 均可访问。
4. 提供显式 opt-in 的专用 canary mutation smoke。
5. canary smoke 覆盖 ASYNC、精确重放、CAS、payload search、tombstone、恢复和最终清理。
6. credential 仅从文件读取，所有临时 curl config 为 `0600`，退出时删除。
7. 无论 PASS/FAIL 都生成 schema 固定的 JSON 报告与简短 Markdown 摘要。
8. 把 runner 纳入真实 Spring Boot、真实 PostgreSQL 和真实 Spring AI embedding HTTP
   验收。
9. 更新双语接入、测试、开发者参考和发布文档。

### 4.2 明确非目标

- 不新增调用方专用 API、服务端 preflight endpoint 或 capability endpoint。
- 不新增 operation-scoped authorization、provisioning 幂等键或 OAuth/OIDC。
- 不创建 Collection、principal 或 credential；预检永远不需要 root。
- 不把 runner 作为生产数据迁移、批量回填或数据修复工具。
- 不在真实业务 Collection 中运行 mutation smoke。
- 不做跨 Collection 越权攻击矩阵；仓库级 109 项合同继续负责该证明。
- 不修改 JSON Record schema、数据库表或 Flyway migration，目标仍为 V48。
- 不修改 WebUI 页面、前端 API client 或浏览器 credential 流程。
- 不调用 Chat LLM、Tool Calling 或 rerank provider；本轮不改变这些路径。
- 不构建或推送容器镜像，不生成镜像 digest、TLS 证书或预发布地址；这些属于部署流水线输入。
  本轮会对最终已验证 main commit 创建不可变的通用源码 tag，作为可部署源码版本。

### 4.3 安全默认

- 默认 `READ_ONLY`，未提供任何 mutation 开关时绝不写数据。
- mutation 必须同时满足：
  - `RAG_BINDING_PREFLIGHT_MODE=CANARY_MUTATION`
  - `RAG_BINDING_CANARY_CONFIRM=YES`
  - 非空 `RAG_BINDING_CANARY_COLLECTION_KEY`
  - canary key 精确属于期望 allow-list
- runner 无法判断 Collection 是否承载真实业务，因此文档明确要求使用预创建的专用 canary
  Collection；双重确认用于阻止误操作，不伪装成服务端业务隔离。
- credential 只允许通过可读普通文件传入；runner 拒绝符号链接、拒绝 group/other 可读权限，
  并要求内容精确匹配当前数据库业务 credential 格式 `rag_sk_[0-9a-f]{64}`。这既排除
  root/legacy 值，也阻止引号、反斜杠或换行进入 curl config；最终身份仍以 `/auth/me`
  为权威。
- `BASE_URL` 不允许包含 user-info、query 或 fragment。非本机目标必须使用 HTTPS；
  HTTP 只允许精确 loopback host 且调用方显式开启本地测试开关。curl 保持证书校验且不跟随
  redirect，避免 credential 被重定向或明文发送。
- 认证 Header 只能由枚举选择 `X-API-Key` 或 `Authorization: Bearer`，不能接受调用方提供
  任意 Header 文本。私有 CA 通过独立证书文件传入；不提供关闭 TLS 校验的选项。
- `targetLabel` 和 `runId` 只允许 `[A-Za-z0-9._-]`，长度分别不超过 64 和 96；报告不保存
  完整部署 URL。
- 不输出 raw response；失败日志只包含 step、HTTP status 和低基数错误类别。
- curl response、header 和 stderr 只写入退出时删除的 private 目录；stderr 不直接透传到
  stdout/stderr，避免错误 URL 或 query 中的 Collection key 进入普通日志。
- 报告不包含 credential、principal ID、Collection key、external ID、payload、URL 或远端
  response body。
- curl 不启用 mutation 自动重试。脚本自己用 GET 对账处理不确定结果，所有轮询和清理都有界。

## 5. CLI 与输入合同

新增：

```text
scripts/business-client-binding-preflight.sh
```

### 5.1 必填输入

| 环境变量 | 语义 |
|---|---|
| `RAG_BINDING_BASE_URL` | 目标实例 origin；无 user-info/query/fragment，末尾 `/` 可接受 |
| `RAG_BINDING_CREDENTIAL_FILE` | 仅含一个业务 credential 的本地普通文件 |
| `RAG_BINDING_EXPECTED_COLLECTIONS_FILE` | UTF-8 JSON 文件，内容为 1-100 个无重复的 Collection key 字符串数组；每项满足 1-128 可见 ASCII |
| `RAG_BINDING_TARGET_LABEL` | 写入报告的低敏目标标签，例如 `staging-a` |

不使用逗号分隔 Collection key，因为合法可见 ASCII key 可以包含逗号；结构化 JSON 文件避免
歧义和 shell word splitting。

### 5.2 可选输入

| 环境变量 | 默认 | 语义 |
|---|---:|---|
| `RAG_BINDING_PREFLIGHT_MODE` | `READ_ONLY` | `READ_ONLY` 或 `CANARY_MUTATION` |
| `RAG_BINDING_AUTH_SCHEME` | `X_API_KEY` | `X_API_KEY` 或 `BEARER`，决定 credential Header |
| `RAG_BINDING_CANARY_CONFIRM` | 空 | mutation 模式必须为 `YES` |
| `RAG_BINDING_CANARY_COLLECTION_KEY` | 空 | 专用 canary Collection key |
| `RAG_BINDING_CA_CERT_FILE` | 空 | HTTPS 私有 CA PEM 文件；为空时使用系统 trust store |
| `RAG_BINDING_PREFLIGHT_RUN_ID` | 时间戳 + PID + 随机后缀 | 本次运行标识，只允许安全字符 |
| `RAG_BINDING_PREFLIGHT_EVIDENCE_DIR` | `.verification/business-client-binding/<run-id>` | 报告目录 |
| `RAG_BINDING_REQUEST_TIMEOUT_SECONDS` | `30` | 单 HTTP 请求上限，范围 `1..120` |
| `RAG_BINDING_READY_TIMEOUT_SECONDS` | `180` | ASYNC Record readiness 总预算，范围 `1..900` |
| `RAG_BINDING_ALLOW_HTTP_LOOPBACK` | `false` | 仅本地 disposable 验收可设为 `true`；非 loopback HTTP 始终拒绝 |

mutation 模式固定使用 `embeddingPolicy=ASYNC`，不提供 `SKIP` 降级开关。若部署实例的
embedding provider 无法在预算内让当前 Record 达到 `READY`，预检失败；已持久化 Record
仍按 cleanup 流程 tombstone。

### 5.3 输出

stdout 只输出 step 级 PASS/FAIL 与报告路径。evidence 目录包含：

```text
preflight-report.json
summary.md
steps.tsv
```

`preflight-report.json` schema version 1：

```json
{
  "schemaVersion": 1,
  "runId": "safe-run-id",
  "result": "PASS",
  "mode": "READ_ONLY",
  "targetLabel": "staging-a",
  "apiVersion": "1.0.0",
  "credentialTransport": "X_API_KEY",
  "principal": {
    "type": "DATABASE_API_KEY",
    "role": "NORMAL",
    "accessMode": "RESTRICTED",
    "credentialVersion": 2,
    "policyVersion": 4,
    "expectedCollectionCount": 2
  },
  "verification": {
    "passedSteps": 6,
    "requiredOperationCount": 6,
    "canaryFinalState": null
  }
}
```

mutation PASS 时 `canaryFinalState` 必须为 `TOMBSTONED`。FAIL 报告允许未到达字段为 `null`，
并增加低基数 `failedStep` / `failureCategory`，不保存响应正文。

## 6. Read-only 流程

固定顺序：

1. 先校验 run ID、target label、mode、auth scheme 和 evidence 路径并创建 evidence 目录，使后续输入/HTTP
   失败都能写入结构完整的 FAIL 报告；只有 evidence 目录本身无法创建时不能保证报告。
2. 校验命令、credential/Collection/可选 CA 文件、URL、TLS/loopback 规则和数值预算。
3. 创建 private 目录和 `0600` curl config。
4. 请求 `/actuator/health/readiness`，要求 HTTP 200 且 `.status == "UP"`。
5. 请求 `/v3/api-docs`：
   - HTTP 200；
   - `info.version == "1.0.0"`；
   - 以下 6 个 method operation 全部存在：
     `GET /rag/auth/me`、`GET /rag/collections/by-key`、
     `POST /rag/json-records/upsert`、`POST /rag/json-records/search`、
     `GET /rag/json-records/by-external-id`、
     `DELETE /rag/json-records/by-external-id`。
6. 请求 `/api/v1/rag/auth/me`：
   - HTTP 200；
   - `Cache-Control` 含 `no-store`；
   - 身份满足第 3.2 节；
   - allow-list 与期望 JSON 数组集合精确相等；
   - 响应不含 `rawKey`、hash 或 secret 字段。
7. 对每个期望 Collection key 请求 `/collections/by-key`：
   - HTTP 200；
   - 响应 `collectionKey` 精确匹配；
   - Collection 未删除且未显式禁用。
8. 写入 PASS 报告并删除 private 临时目录。

若任何一步失败，后续步骤不执行，写入 FAIL 报告并返回非零。

## 7. Canary mutation 流程

先完整通过 read-only 流程，再执行：

1. 确认 canary key 是期望 allow-list 的成员。
2. 生成一次性 external ID、run marker、固定请求快照和 revision token。
3. `upsert(created, ASYNC)`：
   - HTTP 200；
   - 保存 `documentId`；
   - source revision 与请求一致；
   - Record 已 active；
   - 不要求响应时 embedding 已 READY。
4. 精确重放同一个 request file：
   - HTTP 200；
   - `documentId` 不变；
   - 不创建新的 document revision。
5. 轮询 external identity lookup：
   - 总预算受 `RAG_BINDING_READY_TIMEOUT_SECONDS` 限制；
   - 要求同一 `documentId`、active、source revision 不变；
   - lifecycle 最终达到 `searchability=READY`；
   - `FAILED`、`DISABLED` 或超时立即失败。
6. 使用唯一 run marker 执行 `payloadContains` search：
   - HTTP 200；
   - query 使用与本次 canary `retrievalText` 相同的唯一短语；
   - `maxResults=10`、`minScore=0`、`useRerank=false`，避免远端 rerank 配置影响合同；
   - 结果包含当前 `documentId`；
   - 返回 payload marker 与本次运行一致。
7. `upsert(updated, expected=created)` 成功。
8. `upsert(stale, expected=created)` 返回稳定 `409`，随后 GET 证明当前 revision 仍为 updated。
9. `DELETE(tombstoned, expected=updated)` 成功，GET 证明同一 `documentId` 已 tombstone。
10. `upsert(restored, expected=tombstoned)` 成功并复用同一 `documentId`。
11. `DELETE(cleanup, expected=restored)` 成功，GET 证明最终状态 `TOMBSTONED`。
12. 写入 PASS 报告。

### 7.1 失败与中断清理

只要 initial upsert 可能已到达服务端，EXIT trap 就执行有界 best-effort cleanup：

1. GET 当前外部身份；
2. `404` 表示没有可清理 Record；
3. active 时读取当前 `sourceRevision`，使用固定 cleanup revision 和
   `expectedSourceRevision` tombstone；
4. 已 tombstone 时不重复 mutation；
5. cleanup 失败不会覆盖原始失败，但报告把 `canaryFinalState` 记录为 `UNKNOWN`。

cleanup 不使用 root、不永久删除、不生成第二个 external ID，也不隐藏主流程退出码。

## 8. 实施切片与文件范围

### Slice A：独立 binding preflight runner

新增：

- `scripts/business-client-binding-preflight.sh`

实现：

- 输入与权限校验；
- credential-safe curl config；
- read-only 流程；
- canary mutation 状态机与 cleanup；
- JSON/Markdown/TSV 报告；
- Bash 3.2 兼容，不依赖 associative array、`mapfile` 或 GNU-only 参数。

### Slice B：真实服务黑盒验收

修改：

- `scripts/business-client-contract-e2e.sh`
- `scripts/verify-business-client-readiness.sh`

在 restricted principal 轮换前、所有精确 Collection readiness 断言之后执行：

1. 使用 `X_API_KEY` 执行 read-only preflight，期望 PASS；
2. 使用错误的额外期望 Collection 执行 read-only preflight，期望非零且 FAIL 报告不泄漏
   key/credential；
3. 使用 `BEARER` 对 Collection A 执行 canary mutation preflight，期望 PASS 且最终
   tombstone；
4. 使用包含现有 embedding stub fail marker 的独立安全 run ID 执行第二个 canary：
   - canary retrieval text 必须包含 run ID，因此确定性 provider 返回 `503`；
   - runner 在 external identity lifecycle 观察到 `FAILED` 后返回非零；
   - EXIT cleanup 使用 GET 对账并 tombstone 同一 Record；
   - FAIL 报告必须为 schema-valid，`canaryFinalState=TOMBSTONED`，且不包含 marker 对应的
     external ID、payload 或 Collection key；
5. parent 合同把四个 runner 结果计入自己的 PASS_COUNT；
6. readiness manifest 继续记录总 HTTP contract check 数，不改变 schema。

静态门禁增加新脚本 `bash -n`，并检查报告 schema。

### Slice C：双语长青文档

更新：

- `docs/business-client-integration.md`
- `docs/business-client-integration-zh-CN.md`
- `docs/testing-guide.md`
- `docs/testing-guide-zh-CN.md`
- `docs/developer-reference.md`
- `docs/developer-reference-zh-CN.md`
- `docs/release-checklist.md`
- `docs/release-checklist-zh-CN.md`
- `docs/TODO.md`
- `docs/TODO-zh-CN.md`

固化：

- 仓库源码候选 readiness 与已部署实例 binding preflight 的职责差异；
- 默认只读和显式 canary mutation 命令；
- credential 文件、期望 allow-list JSON 和报告 schema；
- canary Collection 必须专用，最终只保证 tombstone，不物理删除；
- operation-scoped capability/provisioning 幂等仍为后续项。

### Slice D：进度、归档和 Git 交付

- 使用 `docs/drafts/EXTERNAL_BINDING_PREFLIGHT_PROGRESS.md` 持续记录。
- 功能完成后把稳定事实提升到双语长青文档。
- 用 `git mv` 将 plan/progress 归档为带 `2026-08-26_` 前缀的历史文档。
- 更新 active draft README 为无活动规划。

## 9. 一次性验收矩阵

### 9.1 脚本聚焦验证

| 场景 | 证据 |
|---|---|
| 缺少 credential/expected Collections/target label | 启动前失败，不发 HTTP 请求 |
| credential 文件为 symlink、权限过宽或不匹配 `rag_sk_[0-9a-f]{64}` | 启动前失败 |
| URL 含 user-info/query/fragment | 启动前失败 |
| 非 loopback 明文 HTTP、未显式允许的 loopback HTTP | credential 请求前失败 |
| 未知 auth scheme、不可读 CA 文件 | credential 请求前失败 |
| 超过 100 项、重复/空/非法 expected Collection key | 启动前失败 |
| read-only 正确 binding | 真实 Spring Boot + PostgreSQL PASS |
| allow-list 少项、多项或 unrestricted/admin/root | fail closed；现有身份合同覆盖其他类型，本轮真实 runner 覆盖多项不匹配 |
| OpenAPI 缺 path/version 错误 | runner 失败；报告只记低基数类别 |
| canary 未双重确认或不在 allow-list | mutation 前失败 |
| canary ASYNC/replay/search/CAS/tombstone/restore | 真实 Spring Boot + PostgreSQL + Spring AI embedding HTTP stub |
| provider 失败 cleanup | 真实 embedding stub 返回 503，runner 观察 `FAILED` 后 GET 对账并最终 tombstone；若清理也失败则报告 UNKNOWN |
| 报告安全 | 不含 credential、Collection key、external ID、payload、URL 或 raw response |
| 报告失败完整性 | evidence 目录可创建时，输入校验失败和 HTTP 失败均生成 schema-valid FAIL |

配置级纯负向场景使用脚本自检函数或受控子进程一次性执行，不启动重复服务。HTTP 成功、策略
不匹配和 mutation 状态机在同一个 disposable 真实服务中完成。

### 9.2 后端与真实服务硬门槛

执行：

```bash
./scripts/verify-business-client-readiness.sh
mvn clean compile test-compile
```

门禁必须继续证明：

- focused API/Core 测试；
- 三组隔离 PostgreSQL/Flyway V48 集成矩阵；
- 真实 Spring Boot readiness；
- 原 109 项合同及新增 preflight 黑盒场景；
- provider HTTP 成功/失败路径和数据库只读事实；
- release manifest PASS。

本轮不改服务端 Java 或 API schema，但 preflight 直接依赖公开端点，因此完整后端门禁仍适用。

### 9.3 前端硬门槛

虽然不改 WebUI，业务 credential、Collection 和 JSON Record 是共享契约，仍执行：

```bash
cd spring-ai-rag-webui
npx tsc -b --pretty false
npm run test:run
npm run build
npx playwright test e2e/api-key-mvp.spec.ts --project=chromium
```

验收只使用 DOM、网络和自动化断言，不使用截图。

### 9.4 真实外部模型

本轮不改变 Chat、LLM、Tool Calling、rerank 或 provider SDK。canary ASYNC 路径通过真实
Spring Boot 与真实 Spring AI `OpenAiEmbeddingModel` HTTP client、确定性 provider stub
验证协议和状态机。真实收费 LLM 调用不提供额外覆盖，因此不作为本轮门槛。

### 9.5 文档与安全门槛

```bash
./scripts/verify-project-docs.sh
./scripts/verify-no-pessimistic-locks.sh
git diff --check
```

另执行：

- 新增行 secret scan；
- `bash -n`；
- report schema/assertion；
- 确认 `.verification/` 仍被 ignore；
- 确认文档示例不含真实 host、credential 或业务 Collection key。

## 10. 可观测性、成本与有界预算

- runner step 数固定，不会按未知 Collection 或服务端分页无界展开。
- read-only 每个期望 Collection 恰好一个 by-key 请求；现有 allow-list 上限为 100。
- mutation 只创建一个 canary Record；最多两次 readiness 轮询阶段，轮询总时间受 900 秒硬上限。
- mutation 不自动重试 `429`/`5xx`；失败交由调用方部署流程决定是否重新运行一个新 run。
- 单次运行只触发必要的 embedding，不调用 Chat LLM。
- 报告使用低基数字段，不把 principal/Collection/external ID 写入普通日志或提交物。

## 11. 兼容性、发布与回滚

### 11.1 兼容性

- 不改变任何 HTTP response、数据库 schema、配置属性或 WebUI bundle。
- 新脚本是 additive；未使用它的部署和 client 行为不变。
- readiness 总合同数会增加，但 release manifest schema 不变。
- runner 固定要求 OpenAPI `1.0.0`，与当前发布契约一致；未来 API 版本升级时必须显式更新
  runner 和合同测试，不能静默接受未知 major contract。
- 最终源码 tag 格式为
  `external-binding-p0-20260826-<final-main-short-sha>`，使用 annotated tag 并指向完成
  全量验证的最终 main commit。tag 不复用、不强制移动；若本地或远端同名 tag 指向不同对象，
  必须重新 fetch 并使用新的最终 SHA 重新生成名称，禁止覆盖。

### 11.2 发布

规划在 `main` 提交并推送后，从最新 `origin/main` 创建专用 feature worktree。实现完成后：

1. 完成 dirty-tree 全量 readiness；
2. 归档 plan/progress 并提交 feature tip；
3. fetch 并 merge 最新 `origin/main`；
4. 在 clean feature tip 上完整复验；
5. 推送 feature；
6. merge 到 `main` 并推送；
7. 在最终 clean main merge commit 上再次完整 readiness；
8. fetch 确认 `origin/main` 未变化后，为该已验证 commit 创建并推送 annotated source tag；
9. 确认 tag 指向最终 main、`main == origin/main` 且工作区干净；
10. 安全移除隔离 worktree。

### 11.3 回滚

无 schema 回滚。回滚 commit 后：

- 已部署服务行为不变；
- 新 preflight 命令不可用；
- 既有 canary tombstone 保留为正常历史，不物理删除；
- 已发布 tag 不移动、不删除；需要回滚时创建新的修复 commit/tag，并由部署系统选择目标版本；
- 业务调用方可临时退回手工 `/auth/me` + Collection by-key 检查，但不得把这种降级宣称为
  机器可重复的完整 P0 binding 证据。

## 12. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 误把真实业务 Collection 当 canary | 默认只读、双重确认、明确专用 Collection 文档、最终 tombstone |
| credential 泄漏到命令行或报告 | 仅文件输入、curl config 0600、private trap、报告字段白名单 |
| credential 经 HTTP 或 redirect 泄漏 | 远端强制 HTTPS；本机 HTTP 双重显式允许；不跟随 redirect；保持 TLS 校验 |
| 私有 CA 环境诱发 `-k` 绕过 | 支持独立 CA PEM 文件；runner 不提供 insecure TLS 开关 |
| 网络不确定导致 orphan active Record | 同一 identity GET 对账、有界 cleanup、最终状态写入报告 |
| allow-list 顺序差异误报 | 集合比较；输入重复直接失败 |
| 合法 key 含逗号导致解析错误 | JSON 数组文件，不使用 CSV |
| Bash 3.2 不兼容 | 不使用关联数组/mapfile/GNU-only 功能，macOS 本机执行真实门禁 |
| readiness 依赖 Collection 总数受其他数据污染 | 只轮询当前 external identity lifecycle，不断言 Collection 精确总数 |
| provider 暂时失败导致部署被阻断 | 这是 canary mutation 模式的预期 fail-closed；read-only 模式仍可独立用于 credential binding |
| cleanup 失败掩盖主错误 | 保留原退出码，报告单独记录 final state |

## 13. 规划与实现检查范围

规划三轮固定范围：

1. P0 差距闭环、自包含、默认安全和非目标；
2. CLI、HTTP 状态机、credential/ACL、失败恢复和 Bash 可实施性；
3. 验收矩阵、成本预算、文档、回滚和 Git 交付。

实现完成后的检查按项目工作流执行；任何实质修复都会重跑受影响硬门槛并重置计数。本轮用户若
后续明确缩减实现 review，以最新指示为准，但不能缩减自动化验收硬门槛。

## 14. 完成定义

只有以下全部成立才算完成：

1. plan 连续三轮无修改审查通过；
2. runner 默认只读，错误配置在请求前失败；
3. runner 对真实 disposable 服务完成 read-only PASS、policy mismatch FAIL 和 canary
   mutation PASS；
4. canary 成功或失败清理后最终状态有机器证据，PASS 必须为 `TOMBSTONED`；
5. 报告不泄漏 credential、URL、Collection key、external ID、payload 或 raw response；
6. `verify-business-client-readiness.sh`、PostgreSQL 矩阵和
   `mvn clean compile test-compile` 通过，服务可启动；
7. WebUI typecheck、Vitest、production build 和核心 Mock Playwright 通过；
8. 文档、禁锁、secret、diff 和脚本语法门禁通过；
9. 最新 `origin/main` 合并后的 feature tip 与最终 main merge commit 均完整复验；
10. feature/main 与最终 annotated source tag 均已推送，tag 指向已验证 main，
    `main == origin/main`，隔离 worktree 已安全移除。
