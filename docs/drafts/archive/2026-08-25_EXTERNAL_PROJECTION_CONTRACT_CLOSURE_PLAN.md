# 外部投影生产合同闭环 P0 实施规划

> **状态**：规划连续三轮无修改审查已通过（`3/3`），可以实施
>
> **规划日期**：2026-08-25
>
> **规划基线**：`main == origin/main` @ `2fae5748`；Spring Boot `3.5.16`；
> Spring AI `1.1.8`；Java `21`；Flyway `V1-V48`
>
> **规划工作区**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
>
> **推荐实施分支**：`feat/external-projection-contract-closure-20260825`
>
> 本文是当前仓库的单语、自包含实施规划。它描述通用后端业务服务把权威数据投影为
> JSON Record 时所需的生产合同，不依赖任何外部项目的代码、术语、文档或业务模型。

## 1. 执行摘要

当前项目已经具备后端业务服务接入 RAG 数据面的主体能力：

- environment root、数据库业务 principal、版本化 credential、轮换/吊销和 Collection ACL；
- `/api/v1/rag/auth/me` 的 principal role、restricted/unrestricted 模式和自身 allow-list；
- JSON Record 的稳定三元身份、revision CAS、精确重放、tombstone/恢复、
  `payloadContains` 和持久化 ASYNC embedding job；
- 从一次性 PostgreSQL、真实 Spring Boot 到真实 HTTP/API Key WebUI 的一键接入验收。

本轮不是再造数据面，而是关闭剩余 P0 合同缺口：

1. JSON Record lookup/tombstone 的 query parameter 目前没有与 upsert DTO 对等的
   controller 级边界声明；lookup 的 service helper 也没有完整执行 namespace/externalId
   长度与字符校验。
2. 真实 HTTP 合同只证明 restricted credential 的跨 Collection **search** 被拒绝，没有
   逐项证明 lookup、upsert 和 tombstone 同样失败关闭。
3. 真实合同只覆盖 Collection key 的 128 成功/129 失败，没有覆盖最小 key、非法 key、
   namespace 128/129、externalId 255/256 和 revision 255/256。
4. 验证摘要虽记录短 Git commit，但没有形成可机器读取、可核对 clean/dirty、版本和迁移
   基线的发布证据清单。
5. 部署文档仍有 Flyway `V47` 的陈旧说明，健康探针与 embedding readiness 的职责边界
   需要明确。

因此本轮交付以“统一边界校验 + 扩展真实合同 + 可锁定验证清单 + 运维文档纠偏”为范围。
不新增 schema，不新增调用方专用 API，不提前实现 P1/P2 权限与能力发现。

## 2. 已核对的当前事实

### 2.1 JSON Record HTTP 面

当前公开端点：

```text
POST   /api/v1/rag/json-records/upsert
POST   /api/v1/rag/json-records/batch-upsert
POST   /api/v1/rag/json-records/search
GET    /api/v1/rag/json-records/{documentId}
GET    /api/v1/rag/json-records/by-external-id
DELETE /api/v1/rag/json-records/by-external-id
```

`JsonRecordUpsertRequest` 已声明：

- `collectionKey` 使用 `@ValidCollectionKey`；
- `externalId` 为 `@NotBlank @Size(max=255)`；
- `sourceNamespace` 为 `@Size(max=128)`；
- `sourceRevision` / `expectedSourceRevision` 为 `@Size(max=255)`。

但 `RagJsonRecordController` 的 lookup/tombstone query parameters 目前没有等价 Bean
Validation 注解，controller 也没有显式 `@Validated`。tombstone 最终进入
`DocumentMutationService`，会执行完整校验；lookup 进入 `JsonRecordService` 自有 helper，
其 `normalizeNamespace` 只 trim/default，`requireExternalId` 只检查非空，未检查最大长度和
显式 namespace 的可见 ASCII。

近距离事实入口：

- [REST API：JSON 结构化记录](../../rest-api-zh-CN.md#json-结构化记录jsonb-payload-检索)
- [业务服务接入指南](../../business-client-integration-zh-CN.md)
- `spring-ai-rag-api/.../dto/JsonRecordUpsertRequest.java`
- `spring-ai-rag-core/.../controller/RagJsonRecordController.java`
- `spring-ai-rag-core/.../service/JsonRecordService.java`
- `spring-ai-rag-core/.../service/DocumentMutationService.java`

### 2.2 冻结的字段语义

本轮不重新定义已有兼容语义：

| 字段 | 冻结语义 |
|---|---|
| `collectionKey` | 1-128 个 `0x21..0x7e` 可见 ASCII，区分大小写，禁止空格和控制字符 |
| `sourceNamespace` | 省略或空白兼容地规范化为 `default`；显式值 trim 后最多 128 字符，并且只能包含 `0x20..0x7e` |
| `externalId` | trim 后非空、区分大小写、最多 255 字符；保持 opaque/Unicode 兼容，不新增 ASCII 限制 |
| `sourceRevision` | 业务调用方应提供的 opaque 完整状态版本，trim 后非空时最多 255 字符，不比较大小 |
| `expectedSourceRevision` | 可选 CAS 前置版本，trim 后空白继续按未提供处理，非空最多 255 字符 |

JSON Record 仍兼容不带 `sourceRevision` 的旧调用；生产外部投影合同要求新调用显式携带
revision。lookup 不携带 revision；tombstone 必须携带新的 `sourceRevision`，严格 CAS
开启时更新/删除必须携带匹配的 `expectedSourceRevision`。

### 2.3 授权与防枚举

Collection 是授权边界；`sourceNamespace`、`externalId`、metadata 和 JSONB payload 都不是
授权边界。restricted principal 的请求顺序必须保持：

```text
解析并校验目标 Collection key
  -> 对 request-scoped principal 执行 Collection ACL
  -> 只在授权成功后查询或修改 document identity
```

越权和未知 Collection 对 restricted caller 均返回通用 `403`，响应不得回显目标 key、
Collection 是否存在、内部 ID 或其他 principal 信息。lookup/upsert/tombstone 不能因为目标
record 不存在而先返回 `404`，从而泄漏 Collection 存在性。

### 2.4 当前一键验收

`scripts/verify-business-client-readiness.sh` 已串行执行：

- focused 后端/合同测试；
- 三组一次性 PostgreSQL 集成矩阵；
- `mvn clean compile test-compile`；
- WebUI typecheck、Vitest、生产 build、核心 Mock Playwright；
- 文档、禁悲观锁、added-line secret 和 diff 门禁；
- 真实 Spring Boot、确定性 OpenAI-compatible embedding HTTP stub；
- 64 项业务 credential HTTP 合同；
- Flyway V48/明文 credential/embedding job 数据库事实；
- 真实 API Key Playwright。

当前摘要是 Markdown + TSV，包含 branch 和短 commit。它尚未输出独立 JSON 发布清单，也
不能在最终交付运行中强制要求 clean Git tree。

## 3. 目标、非目标与默认决策

### 3.1 本轮目标

1. lookup/tombstone 的 controller、service 和 OpenAPI 参数约束一致。
2. 真实 HTTP 合同证明 restricted principal 对允许 Collection 的读写成功，对未授权和未知
   Collection 的 search/lookup/upsert/tombstone 全部失败关闭。
3. 真实合同覆盖字段有效上界、越界和 Collection 非法字符，不只依赖单元测试。
4. 验收脚本生成无密钥的机器可读发布清单，记录完整 commit、tree state、项目/API 版本、
   Flyway 基线、验证阶段和结果。
5. 最终交付运行可以通过配置要求 clean Git tree，防止把未提交修改的证据当作可复现版本。
6. 双语长青文档统一长度、健康、迁移、版本锁定、证据目录和回滚说明。

### 3.2 明确非目标

- 不新增外部项目、租户、组织或其他调用方领域模型。
- 不新增调用方专用 endpoint、Webhook、inbox/outbox 或 payload schema。
- 不实现 operation-scoped `RAG_READ` / `RAG_WRITE` 服务端强制；该项仍是 P1。
- 不实现 API principal/credential provisioning 幂等键；该项仍是 P1。
- 不新增独立 capability discovery endpoint；该项仍是 P2。
- 不创建或伪造生产 Git tag、容器 digest、TLS 地址或预发布环境；本轮提供能锁定到完整
  Git commit 的证据，实际 tag/digest 仍由发布流水线生成。
- 不新增 Flyway migration；数据库目标仍为 V48。
- 不改变 Chat prompt、Tool Calling、模型路由或 LLM 输出，不以真实 Chat LLM 调用作为本轮
  主证据。ASYNC 路径继续通过真实 Spring AI embedding HTTP client + 确定性 stub 验证。
- 不新增 WebUI 功能或页面；前端只执行共享契约回归门槛。

### 3.3 安全默认

- controller 校验负责尽早返回稳定 400；service 校验继续作为非 HTTP 调用和防御性边界，
  不能只依赖注解。
- 不在错误响应、日志、summary、JSON manifest、Git diff 或命令参数中记录 raw credential。
- 合同脚本继续把 credential 放在 `0600` private 文件和 curl config 中；退出 trap 必须
  删除 private 目录并吊销临时 credential。
- 发布清单只记录非敏感构建/验证元数据；不得记录 base URL 中的 credential、完整 payload、
  externalId fixture 或环境变量值。
- 最终交付运行设置 `BUSINESS_CLIENT_REQUIRE_CLEAN_GIT=true`；开发中间运行默认允许 dirty，
  但 manifest 必须如实记录 `DIRTY`，不得显示为可发布 clean 证据。

## 4. 实施设计

### 4.1 Controller 与 service 边界

`RagJsonRecordController`：

- 增加 `@Validated`；
- lookup：
  - `collectionKey`：`@ValidCollectionKey`
  - `sourceNamespace`：`@Size(max=128) @ValidSourceNamespace`
  - `externalId`：`@NotBlank @Size(max=255)`
- tombstone：
  - 复用 lookup 三个约束；
  - `sourceRevision`：`@NotBlank @Size(max=255)`
  - `expectedSourceRevision`：`@Size(max=255)`

在 `spring-ai-rag-api` 增加 `@ValidSourceNamespace` 与 validator，并同步用于
`JsonRecordUpsertRequest.sourceNamespace`。validator 接受 null/blank 兼容值；非空输入先
trim，再检查规范化结果只包含 `0x20..0x7e`。`@Size(max=128)` 继续按当前 HTTP DTO 语义限制
raw input 长度，避免无界 padding；首尾可被现有 trim 移除的空白不因新增注解发生破坏性变化。

`JsonRecordService`：

- `normalizeNamespace` 与 mutation path 对齐：default/trim、128 上限、显式可见 ASCII；
- `requireExternalId` 与 upsert/mutation 对齐：trim 后非空、255 上限；
- direct service tests 证明 controller 之外的调用不能绕过这些约束。

不把 `externalId` 或 revision 限制为 ASCII，避免破坏现有 opaque identity/revision 调用方。

### 4.2 OpenAPI 与 focused 测试

`RagJsonRecordControllerWebTest` 新增：

- max-length lookup 参数可到达 service；
- 129 namespace、256 externalId、非法 namespace 在 controller 层返回 400 且 service 未调用；
- 256 sourceRevision/expectedSourceRevision 的 tombstone 返回 400；
- 合法 tombstone 参数按原值转发。

`JsonRecordServiceTest` 新增 direct-call 防御测试：

- lookup 接受 128 namespace 和 255 externalId；
- 拒绝 129 namespace、非可见 ASCII namespace、256 externalId；
- 拒绝发生在 repository identity lookup 之前。

controller query parameters 使用显式 OpenAPI `@Parameter/@Schema` 声明 required、min/max
length 和规范化说明，不依赖 Springdoc 推断自定义 constraint。`OpenApiContractTest` 断言
lookup/tombstone parameters 包含对应限制；不对无关排序和描述文本做脆弱比较。

### 4.3 真实 HTTP 合同扩展

`scripts/business-client-contract-e2e.sh` 使用同一 disposable root-mode 服务扩展合同。

#### Collection 边界

- 1 字符 key 创建成功；
- 128 字符 key 创建成功；
- 129 字符 key 返回 400；
- 含空格、控制字符或非 ASCII 的 key 返回 400。

#### identity/revision 边界

使用 `embeddingPolicy=SKIP` 的独立 boundary records，避免增加无关 provider 调用：

- 128 namespace + 255 externalId + 255 sourceRevision upsert 成功，lookup 返回同一 identity；
- 129 namespace 返回 400；
- 非可见 ASCII namespace 返回 400；
- 256 externalId 返回 400；
- 256 sourceRevision 返回 400；
- tombstone 的 256 expectedSourceRevision 返回 400。

边界 fixture 使用可预测但无业务含义的字符串；summary 只记录“边界通过”，不记录完整 fixture。
合法 boundary Record 由 A principal 写入 Collection A，但固定排在主 Record 完成
tombstone/恢复和第二次 `enabledDocuments == 1 && freshDocuments == 1` 断言之后；此后不再
对 A 做精确文档数 readiness 断言。这样既验证 restricted 业务 credential 的真实边界，又不
增加第三个 principal 或污染主路径计数。

#### 跨 Collection 全数据面

对 restricted Collection A credential，Collection B 和随机未知 key 分别验证：

- search 返回 403；
- lookup 返回 403；
- upsert 返回 403；
- tombstone 返回 403；
- 错误信封不包含目标 key、内部 ID 或存在性细节。

至少创建一个只允许 Collection B 的第二 restricted principal，并验证它不能读取或修改
Collection A，从而证明策略来自当前 principal，而不是脚本固定的单向 deny。该 principal 的
secret 生命周期与现有临时 principal 使用相同 private-file/trap 规则。

#### 保留现有主路径

现有 `/auth/me`、header/query authentication、secret 一次性展示、精确重放、CAS、
payload-only update、payloadContains、tombstone/恢复、ASYNC readiness、轮换/吊销断言全部
保留。测试计数从当前 64 项自然增长，不把固定数量作为逻辑正确性的唯一依据。

确定性 embedding stub 增加可选 `--fail-marker` 参数和 failed request 计数；未传参数时不
改变现有成功行为。验证器生成无敏感含义的 marker，通过
`BUSINESS_CLIENT_EMBEDDING_FAIL_MARKER` 传给合同和 stub。第二个 restricted principal 在
Collection B 以独立 identity 提交 `embeddingPolicy=ASYNC`，retrieval text 包含 marker 时
stub 返回 503。该真实失败 Record 同时作为 A principal 越权 lookup/tombstone 的目标；B
principal 反向访问 A 的正常 Record 也必须返回 403。这样 Collection A 现有
`enabledDocuments == 1 && freshDocuments == 1` 主路径 readiness 断言不被失败 fixture
污染。真实服务测试环境同时设置 `RAG_EMBEDDING_JOBS_DEFAULT_MAX_ATTEMPTS=1` 和
`RAG_EMBEDDING_JOBS_MAX_ATTEMPTS=1`，使该 job 在有界时间内进入 FAILED。合同必须断言：

- upsert 已返回持久化成功和 durable job；
- lifecycle 最终报告 embedding FAILED；
- lookup 仍返回同一 `documentId`、revision、payload 和 enabled Record；
- 失败不会触发第二次业务 mutation，也不会删除主记录。

测试 marker 只属于本地 stub，不进入生产代码或公开 API 语义。

### 4.4 可锁定发布证据

`scripts/verify-business-client-readiness.sh` 在每次运行结束时除 `summary.md/tsv` 外生成：

```text
.verification/business-client-readiness/<run-id>/release-manifest.json
```

固定 schema：

```json
{
  "schemaVersion": 1,
  "runId": "...",
  "result": "PASS",
  "verificationPhase": "all",
  "git": {
    "branch": "...",
    "commit": "<40-char sha>",
    "treeState": "CLEAN"
  },
  "artifact": {
    "projectVersion": "1.0.0",
    "apiVersion": "1.0.0",
    "apiBasePath": "/api/v1/rag",
    "latestFlywayMigration": 48
  },
  "verification": {
    "passedSteps": 16,
    "postgresImage": "...",
    "httpContractChecks": 80
  }
}
```

具体规则：

- 使用 Python `xml.etree.ElementTree` 读取根 `pom.xml`，不通过正则解析 XML；
- 真实服务启动后请求公开的 `/v3/api-docs`，把 `.info.version` 作为 API version 的唯一
  运行时事实源并保存为非敏感 evidence；最终 PASS manifest 要求其存在且等于 focused
  OpenAPI contract 已验证的 `1.0.0`；
- latest migration 通过受限文件名扫描取得，并与运行时数据库事实交叉验证为 V48；
- HTTP check 数从合同 `summary.txt` 读取，不从 stdout 猜测；
- cleanup 即使在失败时也写 manifest，`result=FAIL` 并保留已完成步骤；
- 如果运行在真实服务或 HTTP 合同之前失败，`apiVersion` / `httpContractChecks` 为 JSON
  `null`，不能填入猜测值；PASS 时二者必须是非空事实；
- `BUSINESS_CLIENT_REQUIRE_CLEAN_GIT=true` 时，前置检查发现 tracked/untracked 非忽略修改
  就立即失败；默认值为 `false`；
- manifest 不含端口 secret、credential、完整 URL、payload 或 private 路径。

最终特性分支证据必须来自 clean、已提交、已合并最新 `origin/main` 且已经完成活动草稿归档的
分支 tip。特性分支合入 `main` 后，还要在 clean 的最终 merge commit 上再运行一次完整
readiness，最终对外交付的 manifest 必须锁定该 `main` commit，而不是归档前或 merge 前的
旧 SHA。

### 4.5 长青文档

更新双语：

- `docs/business-client-integration*.md`
  - 全字段边界；
  - `/actuator/health/liveness`、`/actuator/health/readiness`；
  - readiness group 只代表进程/Spring readiness/数据库，不承诺 embedding provider；
  - embedding 可用性看 durable job 与 Collection derivation/embedding readiness；
  - release manifest 与 clean final command。
- `docs/rest-api*.md`
  - lookup/tombstone query parameter 限制与兼容 default；
  - 越权优先于 record existence 的 403 语义。
- `docs/testing-guide*.md`、`docs/developer-reference*.md`
  - 扩展合同矩阵、manifest 路径和 final clean gate 命令。
- `docs/release-checklist*.md`
  - 记录 P0 全数据面 ACL/边界/可锁定证据门禁。

更新单语 `docs/DEPLOYMENT.md`：

- Flyway 最新版本从陈旧 V47 修正为 V48；
- 明确 liveness/readiness 端点及 embedding readiness 不属于通用健康探针；
- 指向业务服务接入 runbook 和 release manifest。

本轮不修改 `AGENTS.md`：端口、迁移和交付规则仍准确；不在入口重复专题内容。

## 5. 文件级实施切片

### Slice A：边界校验

预计文件：

- `spring-ai-rag-api/.../validation/ValidSourceNamespace.java`
- `spring-ai-rag-api/.../validation/SourceNamespaceValidator.java`
- `spring-ai-rag-api/.../dto/JsonRecordUpsertRequest.java`
- `spring-ai-rag-core/.../controller/RagJsonRecordController.java`
- `spring-ai-rag-core/.../service/JsonRecordService.java`
- `RagJsonRecordControllerWebTest.java`
- `JsonRecordServiceTest.java`
- `OpenApiContractTest.java`

完成标准：HTTP 与 direct service 调用对 lookup/tombstone identity 字段执行一致的 400 边界，
OpenAPI 可发现限制，合法最大值保持兼容。

### Slice B：P0 真实合同

预计文件：

- `scripts/business-client-contract-e2e.sh`
- `scripts/test-support/openai-embedding-stub.py`
- 必要时少量现有测试 fixture/helper

完成标准：两个 restricted principal、允许/未授权/未知 Collection、全数据面 deny、字段边界、
原有 CAS/tombstone/ASYNC/rotation 合同，以及 provider 失败后主记录保留，在一次真实服务
运行中通过；private artifacts 清理。

### Slice C：发布证据

预计文件：

- `scripts/verify-business-client-readiness.sh`
- 相关脚本静态测试或 focused shell/Python assertions

完成标准：成功与失败都生成 schema 稳定的 release manifest；final clean gate 可执行；版本、
迁移、commit 和合同计数来自事实源且不含 secret。

### Slice D：文档

预计文件：

- `docs/business-client-integration*.md`
- `docs/rest-api*.md`
- `docs/testing-guide*.md`
- `docs/developer-reference*.md`
- `docs/release-checklist*.md`
- `docs/DEPLOYMENT.md`

完成标准：中英文事实等价，V48/健康/边界/证据/回滚说明一致，文档门禁通过。

## 6. 一次性验收矩阵

### 6.1 后端 focused

一次性运行受影响测试集：

```bash
mvn -pl spring-ai-rag-core -am test \
  -Dtest=RagJsonRecordControllerWebTest,JsonRecordServiceTest,OpenApiContractTest,\
ApiKeyIdentityControllerTest,ApiKeyRootModeWebIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

检查 Surefire failures/errors/skipped，任务相关测试不得意外跳过。

### 6.2 PostgreSQL 与 Maven 门槛

复用 readiness 脚本中的三个隔离数据库矩阵，至少覆盖：

- managed principal/credential V48；
- document lifecycle/external identity；
- JSONB payload containment 与 structured record。

然后运行：

```bash
mvn clean compile test-compile
```

真实服务必须使用 `postgresql` profile 启动，Flyway 到 V48，`/actuator/health/readiness` 为 UP。

### 6.3 前端门槛

虽然本轮不修改 WebUI 行为，但共享 API、认证和真实服务合同发生变化，仍执行：

```bash
cd spring-ai-rag-webui
npm run typecheck
npm run test:run
npm run build
npx playwright test e2e/api-key-mvp.spec.ts --project=chromium
```

真实全栈阶段保留 `e2e/api-key-real.spec.ts`。只使用 DOM、可访问状态、网络、JSON 和自动化
断言，不使用截图作为验收证据。

### 6.4 一键全量

实现完成后的初始提交、同步最新 `origin/main` 后的候选提交，以及完成进度整理/活动草稿归档的
最终特性分支 tip 分阶段运行；其中最终特性分支运行必须使用 clean gate：

```bash
BUSINESS_CLIENT_REQUIRE_CLEAN_GIT=true \
BUSINESS_CLIENT_VERIFY_RUN_ID=<run-id> \
./scripts/verify-business-client-readiness.sh
```

预期完整阶段：

1. prerequisites/isolated ports；
2. frontend dependency readiness；
3. focused backend tests；
4. disposable PostgreSQL；
5. PostgreSQL matrix；
6. Maven compile/test-compile；
7. WebUI typecheck；
8. Vitest；
9. production build；
10. Mock Playwright；
11. script/static manifest checks；
12. no pessimistic locks；
13. project docs；
14. added-line secret scan；
15. git diff check；
16. real service HTTP/WebUI acceptance。

特性分支合入 `main` 并形成最终 merge commit 后，必须在 clean `main` 上用新的 run id 再执行
同一完整命令。最终检查 `summary.md`、`summary.tsv`、`release-manifest.json` 与合同
`summary.txt` 一致，并确认 manifest 的完整 SHA 等于当次 `git rev-parse HEAD`。

### 6.5 静态与安全门禁

```bash
./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-project-docs.sh
git diff --check
```

新增/修改 shell 运行 `bash -n`；Python 片段使用内存 `compile()` 或直接执行测试，不留下
`__pycache__`。added-line secret 扫描必须通过。

### 6.6 真实依赖判断

本轮改变的是 HTTP 校验、ACL 合同、发布证据与文档，不改变 Chat/LLM。真实 Chat LLM 调用
不提供额外的本任务覆盖，因此标记为 N/A。真实 Spring AI embedding HTTP path 继续通过
确定性 OpenAI-compatible stub 覆盖，避免把外部 provider 波动当作 P0 合同证据。

## 7. 规划与实现收敛

### 7.1 规划 `3/3`

固定范围：

1. P0 需求闭环、通用项目边界、自包含性、兼容默认和非目标。
2. controller/service/OpenAPI、ACL、防枚举、secret、manifest 和发布可行性。
3. 实施切片、一次性验收矩阵、健康/迁移、回滚、Git/worktree 交付。

发现实质问题时修改规划、计数归零；只有连续三轮无修改才实施。无问题轮次只在会话输出总结，
完成 `3/3` 后一次性把最终结果写入 progress。

### 7.2 实现 `3/3`

完整自动化硬门槛通过后执行三轮互不重叠、限时、只读检查：

1. identity 字段、ACL 顺序、防枚举、secret/private cleanup。
2. manifest 事实源、失败摘要、版本/迁移、脚本退出码和兼容性。
3. 测试证据、OpenAPI/双语文档、健康/回滚和 Git 交付。

只有影响正确性、安全、兼容或可复现性的本任务缺陷才修改；任何实质修改重置计数并重跑受影响
门槛。风格和 P1/P2 优化不在本轮扩展。

## 8. 风险、回滚与可逆边界

| 风险 | 控制 |
|---|---|
| controller 校验改变错误信封 | 保持 HTTP 400；focused WebMvc/OpenAPI 断言稳定错误类别，不依赖冗长 message |
| lookup service 与 mutation validator 漂移 | 在 `JsonRecordService` 增加 direct-call tests；文档冻结共同规则 |
| 边界合同显著拉长或污染真实 E2E | boundary records 使用 `SKIP` 并排在 A 最终 freshness 之后；失败 fixture 独占 B；端口/超时仍有界 |
| provider 失败测试等待重试或污染正常 readiness | 测试 profile 把目标 job 最大尝试固定为 1；失败 fixture 独占 Collection B，stub 只对显式 marker 失败 |
| 跨 Collection deny 先命中 404 | 为 B principal 建立真实 fixture，并断言 A/B 双向 403 |
| manifest 错报可发布状态 | 完整 SHA + treeState；final run 强制 clean；运行时数据库事实交叉验证 V48 |
| manifest 泄漏环境信息 | schema allow-list，只写非敏感版本/结果；secret scan 和 private cleanup |
| 部署文档承诺 embedding provider health | 明确 actuator readiness 只覆盖 Spring readiness + DB，embedding 用 job/readiness API |
| 范围膨胀到 capability endpoint | 本轮只生成离线验证 manifest，运行时 discovery 保留 P2 |

回滚不涉及 schema：

1. 恢复 controller/service 校验和测试；
2. 恢复合同/manifest 脚本；
3. 恢复文档。

旧客户端在回滚前后仍使用同一 JSON Record API。依赖更严格发布证据的部署流水线应先取消
`BUSINESS_CLIENT_REQUIRE_CLEAN_GIT` 门槛，再回滚脚本，不得伪造 manifest。

## 9. 进度与 Git/worktree 交付

进度记录：

```text
docs/drafts/archive/2026-08-25_EXTERNAL_PROJECTION_CONTRACT_CLOSURE_PROGRESS.md
```

关键进展先更新 progress，再执行下一阶段；不记录 raw credential、完整业务 payload 或外部
项目路径。

规划阶段在 `main`：

1. 完成规划 `3/3`；
2. 更新活动草稿索引；
3. 运行文档/密钥/diff 门禁；
4. commit 并 push `main`，确认干净。

实施阶段：

1. 基于最新已推送 `origin/main` 创建专用分支和隔离 worktree；
2. 完成 Slice A-D 与基础硬门槛；
3. 本地提交后 fetch/merge 最新 `origin/main`，按合并后候选基线完成基本硬门槛与完整
   readiness；
4. 实现检查达到 `3/3`；
5. 提升稳定事实、完成进度账本并归档 plan/progress，提交形成最终特性分支 tip；
6. 在 clean 的最终特性分支 tip 上运行完整 16 阶段 readiness，manifest 必须记录该 tip；
7. 推送特性分支，合并并推送 `main`；
8. 在 clean 的最终 `main` merge commit 上再次运行完整 readiness，manifest 必须记录该
   merge commit；
9. 确认两个 worktree 均干净并安全移除隔离特性 worktree。

不得 stash、丢弃或覆盖其他 worktree 的修改。

## 10. 完成定义

只有以下条件全部满足才可报告完成：

1. lookup/tombstone controller、service、OpenAPI 边界一致，合法最大值与旧兼容语义不变。
2. 真实 HTTP 合同覆盖 Collection 最小/最大/非法值、namespace/externalId/revision 边界。
3. 两个 restricted principal 的 search/lookup/upsert/tombstone 跨 Collection与未知
   Collection 均返回防枚举 403。
4. 原有 `/auth/me`、secret、CAS、精确重放、payload-only、payloadContains、
   tombstone/恢复、ASYNC、provider 失败保留主记录、轮换/吊销合同继续通过。
5. 最终 `main` release manifest 记录最终 merge commit、clean tree、版本、V48、合同计数和
   成功结果，不含 secret。
6. focused 后端、PostgreSQL 矩阵、`mvn clean compile test-compile` 和 postgresql profile
   服务启动通过。
7. WebUI typecheck、Vitest、production build、Mock 与真实 API Key Playwright 通过。
8. 双语长青文档与 `DEPLOYMENT.md` 更新，文档/锁/密钥/diff 门禁通过。
9. 规划与实现分别达到连续 `3/3` 无修改检查。
10. 特性分支和 `main` 均提交推送，`main == origin/main`，隔离 worktree 安全移除。
