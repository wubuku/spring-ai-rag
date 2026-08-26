# 业务绑定能力画像与 P0 发布验收闭环实施规划

> 状态：规划连续三轮无修改审查通过，允许进入实施  
> 规划基线：`main` / `48b09b37`（2026-08-26）  
> 配套进度：[2026-08-26_BUSINESS_BINDING_CAPABILITY_PROFILES_PROGRESS.md](2026-08-26_BUSINESS_BINDING_CAPABILITY_PROFILES_PROGRESS.md)

## 1. 摘要

当前系统已经具备生产级业务服务接入所需的大部分通用能力：稳定 Collection key、
数据库业务 principal、Collection ACL、`/auth/me` 权限自描述、JSON Record 幂等
upsert、revision CAS、tombstone/恢复、`payloadContains`、ASYNC embedding、部署
binding 预检和一键合同验收。上一轮还完成了数据库 principal 的 `RAG_READ` /
`RAG_WRITE` 操作级授权。

但操作级授权交付后，通用业务接入工具链仍停留在“所有业务 Key 都是完整读写”的旧假设：

- 已部署实例预检只核对 role 和 Collection allow-list，不核对 `capabilities`；
- “READ_ONLY” 目前只表示预检脚本不执行 mutation，并不能证明 credential 本身只读；
- HTTP 合同使用完整读写 Key 跑全部数据面，未证明只读查询 Key 能读但不能写；
- 一键 readiness 脚本仍把最新 Flyway migration 硬编码为 V48，在当前 V49 基线上会失败；
- 部分长青文档和 TODO 仍宣称 operation-scoped capability 尚未实施。

本轮不新增外部客户专用接口，也不改变 RAG 数据模型。目标是把已经存在的通用能力整理成
可部署、可锁定、可自动证明的最小权限接入合同：

1. binding 预检显式声明并严格核对 `READ_ONLY` 或 `READ_WRITE` 能力画像；
2. 合同测试分别使用查询 principal 和投递 principal，验证读写边界与 Collection ACL；
3. readiness 门禁从仓库迁移清单动态核对 V49，不再因版本硬编码失效；
4. 发布证据记录能力画像、Git commit、API 版本、Flyway 版本和合同检查数量；
5. 双语长青文档统一描述查询/投递凭据、预检方式和真实能力语义；
6. Mock 与真实 HTTP 门槛通过后，使用真实 LLM 验证只读 principal 的 Chat JSON/SSE、
   OpenAI 兼容 JSON/SSE、幂等 replay、轮换连续性和严格调用预算。

## 2. 基线、范围与事实源

### 2.1 Git 与工作区

- 本仓库是独立 Git 仓库，远端为 `origin`。
- 规划在 main worktree
  `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery` 完成。
- 当前 `main` 为 `48b09b37`，已与 `origin/main` 对齐，工作区在规划开始前干净。
- 另一个 worktree
  `/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
  属于 `feat/boundary-aware-heuristic-rerank-20260824`，只读取其中 `.env` 作为获准的
  真实 provider 配置来源，不修改、不清理、不 stash。
- 规划完成并推送后，从最新 `main` 创建
  `feat/business-binding-capability-profiles-20260826` 和专用隔离 worktree。

### 2.2 当前版本

- Maven/API 版本为 `1.0.0`。
- Flyway 当前范围为 V1-V49；本轮不新增 migration。
- 默认服务端口、PostgreSQL profile、向量维度和真实 LLM 端口遵循
  [AGENTS.md](../../AGENTS.md)、
  [开发者参考](../developer-reference-zh-CN.md)和
  [测试指南](../testing-guide-zh-CN.md)。

### 2.3 已核对的稳定能力

- [`ApiKeyIdentityController`](../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/ApiKeyIdentityController.java)
  已对数据库 principal 返回 role、credential/policy version、实际 capabilities、
  `RESTRICTED`/`UNRESTRICTED` 和完整 `allowedCollectionKeys`，失败时 fail closed。
- [`ApiCapabilityFilter`](../../spring-ai-rag-core/src/main/java/com/springairag/core/filter/ApiCapabilityFilter.java)
  在认证后、共享限流前执行 `RAG_READ` / `RAG_WRITE`。
- [`ApiKeyManagementService`](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/ApiKeyManagementService.java)
  支持只读或读写 principal，策略 CAS 和 credential rotation 都保持 stable principal
  的能力策略。
- [`business-client-contract-e2e.sh`](../../scripts/business-client-contract-e2e.sh)
  已覆盖 root、restricted/unrestricted DB principal、Header 认证、query credential
  拒绝、跨 Collection 反枚举、JSON Record replay/CAS/payload/tombstone/恢复、
  ASYNC provider 失败保留和长度边界。
- [`business-client-binding-preflight.sh`](../../scripts/business-client-binding-preflight.sh)
  已具备无 root 的只读部署预检、可选专用 canary mutation、输入安全检查和低敏证据输出。
- [`verify-business-client-readiness.sh`](../../scripts/verify-business-client-readiness.sh)
  已串联 focused tests、PostgreSQL、Maven、WebUI、Mock Playwright、真实 HTTP、
  真实 WebUI 和发布 manifest，但其中 V48 常量已经落后于当前 V49。
- [`verify-managed-api-principals.sh`](../../scripts/verify-managed-api-principals.sh)
  已提供双实例、共享 PostgreSQL、Mock 门槛和有界真实 LLM 合同；真实 LLM principal
  当前仍省略 capabilities，因兼容默认而得到完整读写。

## 3. 问题定义

### 3.1 “只读预检”与“只读 credential”混淆

`RAG_BINDING_PREFLIGHT_MODE=READ_ONLY` 只约束脚本行为。一个完整读写 credential 也能通过
该模式，调用方无法据此证明部署使用了最小权限。反过来，一个只读 credential 也可能因为
预检不检查 capabilities 而被误当成投递 credential，直到生产 mutation 首次返回 403 才暴露。

### 3.2 合同测试没有按职责拆分 principal

现有 HTTP 合同主要使用一个 restricted 完整读写 principal 完成搜索和 mutation。虽然中央
过滤器另有测试，但“身份自描述、Collection ACL、JSON Record 数据面、部署预检”没有在同一条
真实 HTTP 合同中证明：

- 查询 principal 是 `["RAG_READ"]`；
- 查询 principal 可以 lookup/search，但 upsert/delete 必须 403；
- 投递 principal 是 `["RAG_READ", "RAG_WRITE"]`；
- rotation 后能力画像保持不变；
- 预检的期望画像与实际画像不一致时 fail closed。

### 3.3 readiness 已被 V49 漂移破坏

`verify-business-client-readiness.sh` 已能扫描最新 migration，但静态检查和运行时数据库事实仍
要求 V48。这会使当前 main 的通用业务接入门禁无法作为 P0 发布证据。固定版本断言必须改为
“运行时 migration 等于仓库扫描出的 latest migration”，同时仍在当前文档中明确本轮基线为 V49。

### 3.4 长青文档存在相互矛盾

`configuration*`、`project-context*` 和 `rest-api*` 已描述 operation capabilities；
`business-client-integration*` 和 `TODO*` 仍保留“业务 principal 固定完整读写/
operation capability 尚未实施”的旧说法。调用方可能因此继续给查询服务配置写权限。

## 4. 目标与非目标

### 4.1 目标

1. 为部署预检增加明确、可验证、可报告的能力画像。
2. 用一个真实 Spring Boot + PostgreSQL + HTTP 合同同时证明能力和 ACL 的组合边界。
3. 让一键 readiness 在 V49 及后续 migration 上通过仓库清单/运行时相等检查自动跟进。
4. 让发布 manifest 和低敏 preflight report 足以回答“验证了哪个 commit、哪个 API/
   migration、哪类 principal 和多少合同检查”。
5. 让查询 credential 默认推荐只读，投递 credential 明确为读写；两者都必须 restricted。
6. 在真实 provider 验收中使用只读 DB principal 调用 Chat，证明 `RAG_READ` 足以完成模型
   调用，同时写操作仍被拒绝，provider 总调用数有严格上限。

### 4.2 非目标

- 不新增客户、租户、项目、素材或 publication 等外部业务模型。
- 不新增客户专用 endpoint、webhook、outbox、receipt 或回源权限逻辑。
- 不改变 `/auth/me`、JSON Record 或 Collection API 的后端响应 schema。
- 不增加 V50，不修改 V1-V49 migration。
- 不实现 API Key provisioning 幂等键或机器可读全协议 capability endpoint。
- 不改变 root、legacy static、ADMIN、unrestricted principal 的既有语义。
- 不把预检报告变成秘密管理系统；报告仍不得保存 credential、Collection key、
  external ID、payload、URL 或响应正文。
- 不要求真实 LLM 参与 JSON Record/embedding 正确性证明；真实 LLM 只验证受本轮影响的
  Chat capability、认证、轮换、幂等和协议路径。

## 5. 冻结的对外与脚本契约

### 5.1 预检能力画像

新增环境变量：

```text
RAG_BINDING_EXPECTED_CAPABILITY_PROFILE=READ_ONLY|READ_WRITE
```

规则：

- 默认值为 `READ_WRITE`，保持历史业务 credential 的完整读写默认兼容。
- `READ_ONLY` 精确映射为 `["RAG_READ"]`。
- `READ_WRITE` 精确映射为 `["RAG_READ", "RAG_WRITE"]`。
- 不接受任意 capability 字符串、空值以外的其他拼写、大小写变体或逗号列表。
- 原始环境变量在创建证据目录后、注册退出报告前，必须先收敛到固定枚举；非法值将
  profile 变量清空并记录 `INVALID_CAPABILITY_PROFILE`，报告不得回显原始输入。
- identity 检查必须对规范数组做精确相等比较，不能只检查包含某个值。
- `READ_ONLY`/`READ_WRITE` 是部署 binding 画像，不等同于预检执行模式。

执行模式与画像矩阵：

| 预检模式 | READ_ONLY 画像 | READ_WRITE 画像 |
|---|---|---|
| `READ_ONLY` | 允许；查询 binding 的推荐组合 | 允许；对 dispatcher 做无 mutation 的安全预检 |
| `CANARY_MUTATION` | 输入阶段拒绝 | 允许；仅用于专用、非业务 canary Collection |

`CANARY_MUTATION + READ_ONLY` 使用稳定失败类别
`CAPABILITY_PROFILE_INCOMPATIBLE_WITH_MODE`，在任何网络请求前终止。

### 5.2 预检报告

`preflight-report.json` 保持 `schemaVersion=1`，additive 增加期望画像，并在
`principal` 对象中增加已经验证的实际画像：

```json
{
  "expectedCapabilityProfile": "READ_ONLY",
  "principal": {
    "capabilityProfile": "READ_ONLY"
  }
}
```

`expectedCapabilityProfile` 记录通过输入校验的调用方期望值；
`principal.capabilityProfile` 只有在 `/auth/me` 精确验证成功后才设置，否则为 `null`。
报告不记录原始响应。`result=PASS` 时两个字段必须非空且相等。现有消费者忽略未知字段即可
保持兼容。

### 5.3 通用 principal 职责

本轮文档冻结两个推荐职责，不引入外部项目术语：

| 职责 | capabilities | Collection scope | 允许操作 |
|---|---|---|---|
| Query principal | `RAG_READ` | 明确、非空 allow-list | lookup、search、Chat 和其他只读数据面 |
| Dispatcher principal | `RAG_READ`,`RAG_WRITE` | 明确、非空 allow-list | 目标 Collection 内的 upsert/delete/恢复及读取 |

生产业务 principal 推荐 `NORMAL + RESTRICTED`。unrestricted、ADMIN、environment root 和
legacy static 必须被 binding 预检拒绝，不能因为 capabilities 数组看似匹配而放行。

### 5.4 HTTP 合同

合同测试在一次可处置部署中创建至少：

- 一个 Collection A 的只读 query principal；
- 一个 Collection A 的读写 dispatcher principal；
- 一个 Collection B 的读写 dispatcher principal；
- 一个专用 canary Collection 的读写 principal；
- 一个 unrestricted principal，用于负向身份语义；
- environment root。

必须证明：

1. 创建响应和 `/auth/me` 返回各自精确 capabilities。
2. query principal 对 A 的 lookup/search 成功，对 upsert/delete 返回通用 403。
3. query principal 的拒绝发生在业务 mutation 前，既有 Record/revision 不改变。
4. dispatcher principal 对 allow-list 内 mutation 成功，对其他/未知 Collection 仍是
   反枚举安全的 403。
5. rotation 后 principal ID、ACL、policy version 和 capabilities 保持。
6. READ_ONLY 预检能分别验证 query 和 dispatcher 的期望画像。
7. 画像不匹配返回 `POLICY_MISMATCH`；canary 模式拒绝 READ_ONLY 画像。

## 6. 实施设计

### 6.1 Binding preflight

修改 `scripts/business-client-binding-preflight.sh`：

- 在 shell bootstrap 中先把 capability profile 收敛到安全枚举，避免退出 trap 把
  未验证环境输入写入报告；随后在 Python 输入校验中再次校验；
- 把规范期望数组写入 private `validated-input.json`；
- 在 `/auth/me` jq 断言中精确比较 `.capabilities`；
- 报告区分 `expectedCapabilityProfile` 与已验证的
  `principal.capabilityProfile`；
- canary 模式在输入阶段要求 READ_WRITE；
- summary 只显示画像名称，不显示身份或 Collection。

扩展 `scripts/test-support/business-client-binding-preflight-self-test.sh`：

- 非法画像；
- READ_ONLY 画像与 canary 模式不兼容；
- 非法画像值不会出现在 report/summary；
- 报告包含规范画像但不包含 credential/Collection；
- 更新固定负向用例计数。

### 6.2 真实 HTTP 业务合同

修改 `scripts/business-client-contract-e2e.sh`：

- `create_principal` 显式接收能力画像并生成规范 `capabilities`；
- 拆分只读 query 与读写 dispatcher；
- 将 query-string credential 拒绝测试复用可处置 principal，减少无意义 principal；
- 给 preflight 子调用传入期望画像，并断言报告画像；
- 在 dispatcher 建立 Record 后，用 query principal 验证 lookup/search；
- 使用只读 principal 调用 upsert/delete 并断言 403 和状态未变化；
- rotation 断言 capabilities 保持；
- summary 增加 capability contract，不写任何秘密或业务身份。

### 6.3 Readiness 与发布证据

修改 `scripts/verify-business-client-readiness.sh`：

- 所有 latest migration 判断统一使用 `LATEST_FLYWAY_MIGRATION`；
- 运行时 SQL 结果解析不再固定为 V48，当前应实际得到 V49；
- focused backend tests 纳入 `ApiCapabilityFilterTest`；
- release manifest 的 verification 部分 additive 记录已验证的 capability profiles，
  固定为 `["READ_ONLY","READ_WRITE"]`；
- manifest validator 要求 PASS 时 profiles 完整且 runtime Flyway 与仓库一致；
- 不改变 clean-tree、commit SHA、API version、HTTP checks 等既有证据。

### 6.4 真实 LLM 合同

修改 `scripts/verify-managed-api-principals.sh` 的真实 LLM principal：

- 创建时显式使用 `["RAG_READ"]`；
- provider 调用前确认 `/auth/me` 精确返回只读能力；
- provider 调用前执行一个写请求并确认 403，且 provider counter 不变；
- 保留原生 JSON、跨实例幂等 replay、credential rotation、会话历史连续性、
  原生 SSE、OpenAI 兼容 JSON/SSE；
- provider 调用总数继续严格等于 5，不因拒绝/replay 增加。

真实 LLM 使用 main 工作区 `.env` 中的 provider 配置，通过
`MANAGED_API_REAL_ENV_FILE` 传入；日志和证据不得包含 key。

### 6.5 双语长青文档

同步更新：

- `docs/business-client-integration*.md`：查询/投递职责、能力画像、预检变量和示例；
- `docs/testing-guide*.md`：画像矩阵、合同范围、真实 LLM 验收命令；
- `docs/release-checklist*.md`：V49 动态核对、能力画像证据、真实只读 Chat；
- `docs/TODO*.md`：把 operation-scoped capability 标记为已完成，保留真正未完成的
  provisioning idempotency 和 machine-readable protocol discovery。

`project-context*`、`architecture*`、`configuration*`、`rest-api*` 已包含稳定实现事实，
仅在实施核对发现缺失或矛盾时做最小同步，不复制本规划。

## 7. 文件级实施顺序

1. 完成并推送本规划，在最新 main 上创建隔离特性 worktree。
2. 修改 preflight 输入、identity 断言、report 和 self-test。
3. 修改真实 HTTP 合同的 principal 拆分和能力负向/正向断言。
4. 修复 readiness 的 V49 漂移并扩展 release manifest。
5. 将真实 LLM principal 改为显式只读，保留五次 provider 预算。
6. 一次性完成脚本静态/自测和 focused tests。
7. 同步双语长青文档。
8. 在允许 dirty tree 的开发模式跑完整 business readiness；通过后跑真实 LLM 完整门禁。
9. 创建本地特性提交，合并最新 `origin/main`。
10. 以 clean candidate 模式重新执行完整 business readiness 和真实 LLM。
11. 三轮限定范围实现审查通过后 push 特性分支，合并/push main。
12. 在最终 main commit 重新执行 clean candidate 完整验收，再创建并推送不可变交付 tag
    `business-client-p0-ready-2026-08-26`，再次核对 tag 指向、远端 main 和干净状态。
13. 安全移除隔离 worktree，不使用 `--force`。

## 8. 验收矩阵

### 8.1 快速与聚焦门槛

```bash
bash -n scripts/business-client-binding-preflight.sh
bash -n scripts/business-client-contract-e2e.sh
bash -n scripts/verify-business-client-readiness.sh
./scripts/test-support/business-client-binding-preflight-self-test.sh

mvn -pl spring-ai-rag-core -am \
  -Dtest=ApiKeyIdentityControllerTest,ApiKeyRootModeWebIntegrationTest,\
ApiCapabilityFilterTest,OpenApiContractTest,ApiKeyAuthFilterTest,\
ApiKeyCollectionAccessTest,RagJsonRecordControllerWebTest,JsonRecordServiceTest,\
CollectionKeyValidatorTest,SourceNamespaceValidatorTest,EmbeddingModelConfigTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

### 8.2 完整 Mock/真实 HTTP 门槛

开发态首次完整验证允许 manifest 如实记录 dirty tree：

```bash
BUSINESS_CLIENT_VERIFY_RUN_ID=<run-id> \
BUSINESS_CLIENT_REQUIRE_CLEAN_GIT=false \
./scripts/verify-business-client-readiness.sh
```

创建本地候选提交、合并最新 `origin/main` 后，以及最终 main 上，都必须使用 clean
candidate 模式重新执行：

```bash
BUSINESS_CLIENT_VERIFY_RUN_ID=<run-id> \
BUSINESS_CLIENT_REQUIRE_CLEAN_GIT=true \
./scripts/verify-business-client-readiness.sh
```

该门槛必须通过：

- 三组 PostgreSQL 集成矩阵和运行时 V49；
- `mvn clean compile test-compile`；
- WebUI TypeScript、Vitest、production build；
- 核心 Mock Playwright（只使用 DOM/网络/自动化断言，不以截图验收）；
- 脚本自测、禁锁、文档、密钥和 whitespace；
- 真实 Spring Boot + PostgreSQL + embedding stub HTTP 合同；
- 真实 WebUI Playwright；
- release manifest 完整性。

### 8.3 真实 LLM 门槛

只有 §8.2 的 Mock/Stub/合同流程通过后执行：

```bash
MANAGED_API_VERIFY_RUN_ID=<run-id> \
MANAGED_API_REAL_ENV_FILE=/Users/yangjiefeng/Documents/wubuku/spring-ai-rag/.env \
./scripts/verify-managed-api-principals.sh --with-real-llm
```

必须确认：

- 实际 provider base URL/model 从 `.env` 装载但不写入 Git 或公开证据；
- 只读 principal 的 Chat 原生 JSON/SSE 和 OpenAI 兼容 JSON/SSE 成功；
- 写请求 403，幂等 replay 和无效旧 credential 不调用 provider；
- rotation 后 session/principal 连续；
- 两实例共享 PostgreSQL 行为正确；
- provider 调用总数严格为 5；
- 运行期间持续观察 backend/verification 日志，认证、模型或协议失败时立即终止排查。

### 8.4 仓库门槛

```bash
./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-project-docs.sh
git diff --check
git diff --unified=0 | <added-line secret scan>
```

不得出现真实 key、Token、credential、Collection 业务标识或 payload。

## 9. 合并后最终验证

特性实现完成后执行：

1. `git fetch origin main`；
2. merge 最新 `origin/main` 到特性分支，不 rebase；
3. 记录特性 HEAD、`origin/main`、隔离端口、数据库名和证据目录；
4. 以 clean candidate 模式重新执行 §8.2 完整 readiness；
5. 重新执行 §8.3 真实 LLM；
6. 完成三轮限定范围、互不重叠的只读实现审查；
7. 合并到 main 后，再以最终 clean main commit 执行 §8.2 和 §8.3；
8. 验收通过后 push main、创建/push tag，并核对 `main == origin/main == tag`。

合并前结果只作为历史证据，不替代合并后或最终 main 的结果。任何实质修复都重置实现审查
计数，并重跑受影响门槛；影响共享脚本/合同基线时重跑完整序列。

## 10. 风险、回滚与安全

### 10.1 兼容风险

- 预检默认期望 READ_WRITE，保持旧 full-capability principal 的行为；只读调用方必须显式
  配置 READ_ONLY。
- 报告只 additive 增加字段，schemaVersion 保持 1。
- HTTP API 不变，旧业务 client 不受影响。

### 10.2 误授权风险

- 画像比较必须精确，不能把 READ_WRITE 接受为 READ_ONLY。
- mode 和 profile 分开建模，避免把“脚本没写数据”误当成“credential 没写权限”。
- role、access mode、allow-list 和 capabilities 必须同时通过才允许 binding。

### 10.3 测试成本

- 先运行秒级脚本自测和 focused tests；
- 再运行 embedding stub/Mock 完整门槛；
- 最后才运行真实 LLM，调用预算固定 5；
- 失败时根据日志尽早停止，不在已知错误上等待模型超时。

### 10.4 回滚

本轮不改数据库和后端 API。脚本问题可回滚到上一版本，但不得把降级后的旧 preflight 当作
最小权限证明。若新增严格检查发现旧部署不合规，应修正 credential policy 或显式选择正确
画像，不应削弱检查。

## 11. 规划与实现审查范围

规划三轮固定范围：

1. 需求闭环、自包含、通用项目边界、默认值和非目标；
2. 脚本/API/安全/兼容/数据可行性；
3. 实施顺序、验收、真实 LLM 预算、发布、回滚和 Git 交付。

实现三轮固定范围：

1. 输入验证、秘密处理、fail-closed、ACL/能力组合和报告安全；
2. HTTP 合同、迁移动态核对、兼容性、Mock/真实 provider 调用预算；
3. 测试证据、双语文档、release manifest/tag、合并与 worktree 清理。

## 12. 完成定义

只有同时满足以下条件才算完成：

- 新预检能精确验证 READ_ONLY/READ_WRITE，并拒绝不兼容组合；
- HTTP 合同证明查询 principal 可读不可写、dispatcher 可在 allow-list 内读写；
- readiness 在 V49 上完整通过并输出可验证 release manifest；
- 后端编译、PostgreSQL、前端 typecheck/Vitest/build/Mock Playwright 和真实运行时通过；
- 真实 LLM 五次有界调用全部通过，且使用显式只读 principal；
- 双语长青文档与 TODO 无矛盾，文档/禁锁/密钥/whitespace 门槛通过；
- 规划与实现分别达到连续 `3/3`；
- 特性分支合并到 main，main/tag 推送，工作区干净，隔离 worktree 安全移除。
