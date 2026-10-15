# Client Consumer Contract Kit 实施规划

> 状态：已完成；规划检查、实施、集成回归、真实 HTTP 黑盒验收和实施检查均已完成，本文与进度记录将在 Git 交付后归档。
>
> 编写日期：2026-10-16
>
> 实施位置：当前 `main` 工作区 / `main` 分支；不创建额外 worktree。
>
> 基线提交：`6340f5d2 feat: strengthen client governance and reliability contracts`
>
> 近距离规范：[规划、实施与验收工作流](../../delivery-workflow-zh-CN.md)、
> [项目文档 Skill](../../../.agents/skills/project-docs/SKILL.md)、[REST API](../../rest-api-zh-CN.md)、
> [SSE 协议](../../SSE-PROTOCOL.md)、[测试指南](../../testing-guide-zh-CN.md)。

## 1. 执行结论

本批只建设 **Client Consumer Contract Kit**，目标是把已经存在的 OpenAPI producer contract、
API compatibility manifest、黑盒门禁和 SSE 文档，整理成外部 Client 可以直接拿来验收和回归的
版本化契约包。

本批交付以下能力：

1. 版本化、语言无关的 consumer contract manifest、OpenAPI surface snapshot 和固定 fixture；
2. native JSON、native SSE、OpenAI-compatible JSON、OpenAI-compatible SSE 四类协议样例；
3. 旧 Client 最小请求/响应兼容 fixture，验证新增字段不会破坏未知字段忽略；
4. 可复用的 Node contract-kit validator，支持离线 fixture 校验和可选 live HTTP 校验；
5. 错误码、`FailureClass`、`X-Trace-Id`、`X-RAG-Failure-Class`、证据状态、重试信号和
   幂等重放 header 的统一机器可读映射；
6. 已知 deprecated numeric Collection 字段/路由的集中登记和生命周期说明；
7. Java/TypeScript/curl 的最小 Client 示例，示例只依赖公开 HTTP 契约；
8. 默认接入 release gate 的离线 consumer contract 检查，实时检查只在显式提供服务地址和
   API Key 时执行，不让普通发布额外调用 LLM。

本批不改变业务 API 的 endpoint、HTTP method、已有请求字段、已有响应字段、SSE event 名称、
OpenAI body 或数据库 schema。所有新增内容都是测试/文档/校验资产，必要时只增加可选的
fixture 元数据和诊断信息。

## 2. 当前基线与已核对事实

### 2.1 已有 producer contract 能力

- `testdata/api-contract/v1-compatibility.json` 已记录 `ADDITIVE_ONLY` 兼容政策、关键路径、
  schema 字段、错误 envelope、可靠性 header 和治理路由。
- `scripts/lib/api-contract-check.mjs` 已能从 `/v3/api-docs` 检查路径、method、请求/响应 schema、
  字段存在性、字段类型和可选/必填状态；目前是 producer-side 检查，不是 Client 端到端消费模型。
- `scripts/verify-api-compatibility.sh` 已覆盖静态 OpenAPI、参数错误、Trace、failure-class、
  retrieval trace、治理 policy/audit，以及可选的 live Search/OpenAI JSON smoke。
- `scripts/verify-client-contract-governance.sh` 已默认执行 manifest governance 检查，并接入
  `scripts/verify-release.sh`。
- `spring-ai-rag-core/src/test/java/com/springairag/core/contract/OpenApiContractTest.java` 已验证
  producer 生成的 OpenAPI 结构，但不保存可供外部 Client 直接使用的版本化 surface snapshot。

### 2.2 已有协议事实

- native JSON Chat 的稳定旧字段包括 `answer`、`traceId`、`sources`、`sessionId`、`turnId`、
  `mode`、`requestedModel`、`resolvedModel`、`usage`、`finishReason` 和 `metadata`。
- native SSE 使用 `content`、`tool_start`、`tool_result`、`sources`、`done`、`error`，客户端必须
  处理 CRLF、多行 `data:`、EOF 尾块、重复终态和 sequence 去重。
- OpenAI-compatible JSON 保留标准字段 `id`、`object`、`created`、`model`、`choices`、`usage`；
  OpenAI body 不加入私有 RAG 字段。
- OpenAI-compatible SSE 使用标准 Chat Completion chunk 和 `[DONE]`，native RAG 诊断通过 header、
  native contract 或请求级 trace 关联，不改变 OpenAI chunk body。
- 错误响应已经具有 Problem Detail 与兼容字段，并可通过 `failureClass`、
  `X-RAG-Failure-Class`、`X-Trace-Id` 和条件 `Retry-After` 表达机器可处理语义。
- `KNOWLEDGE` 的 `evidenceAssessment` 是确定性安全门结果；`PLAIN` 只能用于 transport-only
  probe，不能被 consumer kit 宣称为证据安全验收。

### 2.3 当前缺口

1. 外部 Client 需要自己拼接 native JSON/SSE/OpenAI 的契约知识，缺少同版本固定输入/输出样例。
2. 当前 manifest 是服务端兼容基线，不包含 Client 解析规则、事件终态不变量、deprecated 清单、
   失败重试映射和 fixture 版本。
3. 现有 live smoke 主要覆盖 Search 和 OpenAI JSON，native JSON/SSE、OpenAI SSE 的消费级校验
   尚未形成一个统一可执行入口。
4. OpenAPI 变更检查能发现 producer schema 删除/改型，但不能证明旧 Client fixture 仍能解析新增
   字段，也不能把响应字段的稳定语义和 header/SSE 终态规则作为一个包交付。
5. deprecated `collectionId(s)`、数字路由等兼容面散落在 REST 文档和 DTO 注解中，缺少机器可读
   的集中登记和“保留/迁移/禁止新使用”的验收约束。

## 3. 目标、非目标与冻结决策

### 3.1 目标

- 让没有 WebUI、没有项目内部 Java 依赖的外部 Client，也能按一套固定资料完成兼容验收。
- 让每次 API 变更都能同时通过 producer contract 和 consumer contract。
- 让 fixture 能覆盖成功、错误、证据不足、流式终态和 OpenAI 兼容路径。
- 让失败语义可由 Client 稳定映射为 retry、等待、降级、人工介入或不重试。
- 让 live consumer smoke 可在隔离端口运行，并且失败报告不输出 API Key、原始 prompt 或响应
  中的敏感字段。
- 将契约资产接入现有 release gate，而不让普通 `mvn test` 或普通 Chat 自动产生额外 LLM 费用。

### 3.2 非目标

- 不生成或维护一个未经验证的完整语言 SDK；本批只提供最小可运行示例和语言无关 fixture。
- 不改变 OpenAPI producer 的 endpoint 或 schema 语义；不通过删除路径来“清理”旧兼容路由。
- 不把 `evidenceAssessment`、Trace、usage 或 failure-class 追加进 OpenAI JSON/SSE body。
- 不引入 OpenAPI generator、外部契约测试 SaaS、额外运行时依赖或数据库表；snapshot 使用
  现有 `/v3/api-docs` 的稳定路径、method、request/response schema 和字段签名，不复制完整原始
  OpenAPI 文档。
- 不在普通 release gate 中启动真实 provider，不把 `PLAIN` transport probe 当成 KNOWLEDGE 质量验收。
- 不把截图、WebUI 页面可用性或 mock UI 通过作为 Client contract 通过依据。

### 3.3 冻结默认值与可逆边界

| 决策 | 默认值 | 理由 | 可逆边界 |
|---|---|---|---|
| fixture 版本 | `v1` | 与现有 `v1-compatibility.json` 对齐，避免重新解释旧契约 | 可新增 `v2`，不覆盖 v1 |
| 离线 gate | 默认执行 | 无模型、无数据库、低成本，适合每次提交 | 删除 gate 需同步修改 release 规则，不允许静默跳过 |
| live gate | 默认关闭，仅显式 `CLIENT_CONTRACT_LIVE=1` | 避免普通发布调用 LLM 或依赖部署环境 | 仅增加检查，不改变离线结果 |
| native Chat live mode | 默认使用 `PLAIN` transport probe | 避免没有知识库时误报证据质量；明确标注不等于 KNOWLEDGE 验收 | 真实 KNOWLEDGE 由既有 real LLM/evaluation gate 负责 |
| deprecated 使用 | 允许旧 fixture 验证，禁止新示例使用 | 保持兼容同时引导新 Client 使用 `collectionKey(s)` | 未来逐项变为 warning/error，必须修改 manifest 版本 |
| OpenAI 私有字段 | 禁止进入 body | 保持第三方 OpenAI Client 兼容 | 只能通过 header/native contract 增加诊断 |
| live 认证 | `Authorization: Bearer`，API Key 只来自环境变量 | 与现有脚本一致，避免 secret 进入 fixture/日志 | 可支持额外认证，但不得把 key 写入报告 |
| schema 迁移 | 不需要 | 本批只增加 Git 资产和测试门禁 | 若未来需持久化契约执行记录，另行规划 migration |

## 4. 目标设计

### 4.1 版本化 consumer contract 包

新增目录：

```text
testdata/api-contract/consumer/v1/
  consumer-manifest.json
  openapi-surface.json
  legacy-client-request.json
  native-chat-response.json
  native-error-response.json
  native-chat-stream.sse
  openai-chat-completion-response.json
  openai-chat-completion-stream.sse
  deprecations.json
```

`consumer-manifest.json` 必须自包含以下信息：

- `contractVersion`、`fixtureVersion` 和 `compatibilityMode=ADDITIVE_ONLY`；
- 每类 transport 的请求 fixture、响应 fixture、content type 和 endpoint；
- 旧 Client 只依赖的 required fields；
- 允许被忽略的新增字段；
- SSE 允许的 event、终态互斥规则、sequence 规则和 `[DONE]` 规则；
- 错误字段到 Client action 的映射；
- deprecated 字段/路由的 replacement、当前状态和禁止的新示例标记；
- live probe 是否需要 API Key、是否需要模型以及是否属于证据验收。

`openapi-surface.json` 是从 `/v3/api-docs` 提取的、可审查的最小 producer snapshot，至少包含
contract package 所覆盖的 path、method、request schema、response schema、content type 和
关键 schema 字段类型/required 状态。它不是完整 OpenAPI 文档，也不取代现有 producer manifest；
validator 必须检查当前 spec 对 snapshot 只发生 additive change，删除、改型或 required 化必须失败。

现有 `testdata/api-contract/v1-compatibility.json` 继续作为 producer compatibility baseline；
不复制已有路径/schema 清单，而是在其顶层增加 `consumerKit` 指向上述 package 和 validator 版本。

### 4.2 Fixture 语义

#### 旧 Client 请求

`legacy-client-request.json` 只使用已发布且稳定的最小字段，例如 native Chat 的 `message`、
`sessionId` 和基础模式，不依赖新增的 `evidenceAssessment`、`failureClass` 或诊断字段。
validator 要求它能被当前 request schema 接受，并验证新增字段不影响旧请求的语义。

#### native JSON

`native-chat-response.json` 同时包含旧字段和当前增量字段，validator 检查：

- 旧字段仍存在且类型稳定；
- `evidenceAssessment` 可选且结构正确；
- `traceId`、`sessionId`、`turnId` 不互相替代；
- `sources` 是数组，旧 Client 可以忽略新增诊断字段。

#### native SSE

`native-chat-stream.sse` 至少包含 `content -> sources -> done` 成功流和独立 error fixture；
validator 检查：

- 每个 event 的 data 是合法 JSON（heartbeat comment 除外）；
- `done` / `error` 互斥且只能出现一个终态；
- `sources` 不晚于成功 `done`；
- sequence 单调递增，重复 sequence 可被安全忽略；
- EOF 尾块、CRLF 和多行 `data:` 的解析结果稳定。

#### OpenAI-compatible JSON/SSE

fixture 只包含标准 OpenAI 字段。validator 禁止发现私有 RAG 字段进入 body，同时允许通过
header 传递 `X-Trace-Id`、`X-RAG-Failure-Class` 和 idempotent replay 信息。

### 4.3 Validator 与命令入口

新增 `scripts/lib/client-consumer-contract.mjs`，保持 Node 内置依赖，提供：

- manifest/fixture 文件存在性和版本一致性检查；
- JSON required/type/unknown-field-ignore 断言；
- native SSE parser，覆盖 CRLF、多行 data、EOF 尾块、comment、重复 sequence 和终态；
- OpenAI body 私有字段隔离断言；
- failure-class、trace、retry-after、idempotent replay header 映射断言；
- deprecated registry 的 replacement/status/usage policy 断言；
- `--self-test`，主动证明 malformed JSON、乱序终态、缺字段和私有字段泄漏会失败。

新增 `scripts/verify-client-consumer-contract.sh`：

- 默认执行离线 fixture/validator gate；
- `CLIENT_CONTRACT_LIVE=1` 时调用 `/v3/api-docs`、native JSON、native SSE、OpenAI JSON、
  OpenAI SSE（若部署启用）并对真实响应做同一套消费级断言；
- 失败输出只包含 status、endpoint、contract rule、trace id 的脱敏摘要，不输出 Authorization、
  request message、完整响应或 API Key；
- live 模式使用 `CLIENT_CONTRACT_BASE_URL`、`CLIENT_CONTRACT_API_KEY`、
  `CLIENT_CONTRACT_QUERY`、可选 `CLIENT_CONTRACT_MODEL`；无这些变量时明确跳过 live 而不是伪造通过。

### 4.4 失败与重试映射

将当前稳定失败语义登记为机器可读 action：

| 信号 | Client 默认动作 |
|---|---|
| `failureClass=RATE_LIMITED`（HTTP `429`） | 按 `Retry-After` 有界退避并重试 |
| `failureClass=CONFLICT` + `error=CHAT_TURN_IN_PROGRESS` | 读取 `Retry-After` 后查询/重放，不并发拼接回答 |
| `failureClass=DEPENDENCY_UNAVAILABLE` | 有界重试；超过次数转人工或降级 |
| `failureClass=TIMEOUT` | 仅对幂等/可安全重放的请求有界重试，否则查询 trace |
| `failureClass=CLIENT_REQUEST` / `AUTHENTICATION` / `AUTHORIZATION` | 不重试，修正请求或凭据 |
| `evidenceAssessment.status=NO_EVIDENCE/MISSING_CITATION/INVALID_CITATION/PARTIAL_CITATION/CONTEXT_LIMITED` | 不重试同一回答；展示安全回答并提示补充范围 |
| `error=MODEL_CAPABILITY_UNSUPPORTED` | 不重试同一模型；按能力发现选择其他模型 |
| `failureClass=NOT_FOUND` / `INTERNAL` | 默认不自动重试；刷新资源或使用 trace 查询日志 |

该表是 Client kit 的解释层，只使用当前公开的 `FailureClass`、既有 error code 和
`EvidenceAssessment.status`；不改变服务端 ErrorCode/FailureClass 的既有枚举。新增映射必须
先在 manifest 增量登记并补 fixture。

### 4.5 Deprecated registry

`deprecations.json` 集中登记当前仍兼容但不应出现在新示例中的：

- `collectionId` / `collectionIds` 输入字段；
- 数字 Collection 路由；
- 已标记 deprecated 的 batch create-and-embed 路径；
- `allowedCollectionIds` 等旧权限投影。

每项包括 `kind`、`location`、`replacement`、`status`、`introducedAt`、`removalPolicy` 和
`consumerRule`。validator 必须保证：

- listed deprecated 内容仍可在旧 fixture 中被识别；
- 新 Java/TypeScript/curl 示例不使用 deprecated 内容；
- replacement 文档链接存在；
- 没有不受登记的“deprecated”文字漂移进 kit。

### 4.6 最小 Client 示例

新增 `docs/client-contract-kit.md` 和 `docs/client-contract-kit-zh-CN.md`，提供等价的：

- curl：JSON、SSE、OpenAI JSON/SSE；
- TypeScript：`fetch` + SSE line parser 的最小消费骨架；
- Java：`HttpClient` + `ObjectMapper`/行解析的最小消费骨架。

示例只使用公开 endpoint 和 `CLIENT_CONTRACT_*` 环境变量，不引用项目内部类、数据库 ID、
WebUI API 或私有 prompt。文档明确区分：离线 contract 通过、transport live 通过和 KNOWLEDGE
证据质量通过。

## 5. 文件级实施顺序

### Slice A：契约包与 fixture

1. 扩展 `testdata/api-contract/v1-compatibility.json` 的 `consumerKit` 元数据。
2. 生成并登记 `openapi-surface.json`；新增 `testdata/api-contract/consumer/v1/` manifest、四类 transport fixture、旧 Client 请求
   fixture 和 deprecated registry。
3. 用 fixture 内容与 `docs/SSE-PROTOCOL.md`、`docs/rest-api-zh-CN.md` 逐项对拍；任何矛盾先修
   规划，不在实现阶段临时改变语义。

### Slice B：validator 与 gate

1. 实现 `scripts/lib/client-consumer-contract.mjs` 及 self-test；支持从 OpenAPI spec 生成/校验
   `openapi-surface.json`，并检查 additive-only 演进。
2. 实现 `scripts/verify-client-consumer-contract.sh`，默认离线、显式 live。
3. 更新 `scripts/gate-registry.mjs`、`scripts/verify-release.sh` 和必要的 project-docs gate。
4. 保证 shell syntax、Node syntax 和 gate fail-closed 自测覆盖新增入口。

### Slice C：文档和示例

1. 新增双语 `docs/client-contract-kit*`。
2. 更新 `docs/testing-guide*`、`docs/developer-reference*`、`docs/index*` 和
   `docs/business-client-integration*` 的入口链接/命令。
3. 保证双语结构、相对链接、事实和命令同步；不在 `AGENTS.md` 展开正文。

### Slice D：集成、实时验证与归档

1. 运行离线 consumer gate 和 API producer gate。
2. 使用隔离端口启动服务，执行 live native/OpenAI JSON/SSE probe；若 OpenAI 兼容路径未启用，
   记录安全跳过原因，不把 skip 当 pass。
3. 执行 Maven、必要 PostgreSQL/HTTP 集成、WebUI 现有回归、`scripts/dev.sh` readiness 和
   release gate。
4. 经过实现 `3/3` 后，将稳定事实同步到双语长青文档，再按 drafts 生命周期把 plan/progress
   归档到 `docs/drafts/archive/`，保留近距离历史入口。

## 6. API 兼容、安全与成本边界

- 旧 endpoint、method、request required fields、response old fields、SSE event name 和 OpenAI
  body 不删除、不改名、不改类型。
- consumer kit 允许严格检查“旧字段仍存在”，但不得把新增 response 字段当作旧 Client 必须理解
  的字段。
- live probe 的 request message 使用固定、无敏感内容的环境参数；报告不打印完整请求/响应。
- fixture 不得包含真实 API Key、Token、文档正文、真实客户 Collection key 或内部数据库 ID。
- 只读 contract probe 不执行写操作；如果必须触发 Chat，只使用显式 `PLAIN`/stateless transport
  probe，并在结果中标注不代表 KNOWLEDGE 证据质量。
- validator 对未知新增字段默认忽略；对既有字段缺失、类型变更、终态矛盾、OpenAI 私有字段泄漏
  和未登记 deprecated 使用必须失败。
- 不新增数据库表、列或 Flyway migration；不修改 `V63`。

## 7. 验收矩阵

| 层级 | 验收内容 | 必须通过 |
|---|---|---|
| Fixture | JSON/SSE 文件、manifest 版本和路径完整 | 是 |
| Validator | 正常 fixture 全绿；malformed/乱序/私有字段 fixture fail-closed | 是 |
| Producer | 现有 `api-contract-check` 与 OpenAPI contract test | 是 |
| Consumer | 旧 Client request + 当前 additive response 可解析 | 是 |
| Native SSE | CRLF、多行 data、EOF、sequence、终态互斥 | 是 |
| OpenAI | JSON/SSE body 只有标准字段；诊断走 header | 是 |
| Deprecation | registry 完整，新示例无 deprecated 字段 | 是 |
| Live HTTP | native JSON/SSE、OpenAI JSON/SSE 按环境执行；未启用明确 skip | 条件性 |
| Backend | `mvn clean compile test-compile`、相关 Maven/HTTP/PostgreSQL 测试 | 是 |
| Runtime | `scripts/dev.sh` readiness，隔离端口退出无残留 | 是 |
| Docs | 双语、死链、命令、secret、diff 门禁 | 是 |
| Release | consumer gate 接入并可 fail closed | 是 |

## 8. 规划检查与实现检查

### 8.1 规划检查

在任何实现代码修改前，至少完成三轮系统性检查：

1. 需求闭环、自包含性、兼容边界、默认值和非目标；
2. 现有 OpenAPI/DTO/SSE/脚本/文档交叉验证，确认文件级方案可实施；
3. 测试、发布 gate、live 依赖、secret、回滚和中断恢复检查。

发现内容错误、逻辑矛盾、信息缺失或不可实施设计时，立即修正文档并将计数重置为 `0`；
连续三轮无修改后才允许实施。格式细节和可自然暴露的行号漂移不触发重置。

### 8.2 实现检查

实现后基本硬门槛全部通过，再连续完成三轮只读检查：

1. validator、fixture、SSE/OpenAI 语义、deprecated registry 和 fail-closed 边界；
2. API/Client 兼容、权限、敏感信息、live skip 和成本边界；
3. Maven、启动、文档、release gate、Git 交付和归档。

任一轮发现影响正确性、兼容性、安全或可验收性的实质问题，立即修复、重跑受影响验证并
将计数重置为 `0`。

## 9. 回滚与恢复

- 本批无 schema 迁移；回滚只需回滚 Git 文件和 release gate wiring，不涉及数据修复。
- 如果 live probe 在某 provider 上不支持 OpenAI SSE，只将该 transport 标为条件 skip，不删掉
  offline fixture 或放宽 validator。
- 如果当前部署没有 OpenAI compatibility，native contract 仍必须完整通过；OpenAI live skip
  只能由 capability/HTTP 404/feature disabled 明确证明。
- 中断后先读本文件和对应 progress，再执行 `git status`、`git diff`、离线 gate；不得使用
  `git reset --hard`、`git stash` 或 `git clean`。

## 10. 完成定义

只有以下条件全部满足才算完成：

1. 规划和进度账本可恢复，规划检查 `3/3`；
2. 版本化 consumer manifest、fixture、validator、self-test 和 gate 均存在；
3. native JSON/SSE、OpenAI JSON/SSE、旧 Client、错误/Trace/证据/重试映射均有自动断言；
4. producer contract 与 consumer contract 均接入 release gate；
5. 双语长青文档和示例同步，项目文档门禁通过；
6. 后端相关编译/测试、`scripts/dev.sh`、必要 live HTTP 验收和实现 `3/3` 通过；
7. commit、push 后 `main` 与 `origin/main` 同步且工作区干净。
