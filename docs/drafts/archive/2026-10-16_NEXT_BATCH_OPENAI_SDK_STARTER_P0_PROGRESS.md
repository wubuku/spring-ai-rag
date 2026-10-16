# 下一批 P0 实施进度：OpenAI SDK 真实 HTTP E2E 与 Starter 完整 Bean Graph

> 对应规划：[NEXT_BATCH_OPENAI_SDK_STARTER_P0_PLAN.md](2026-10-16_NEXT_BATCH_OPENAI_SDK_STARTER_P0_PLAN.md)
>
> 本文只记录实施证据、失败与修复；规划决策以规划文档为准。

## 1. 基线

- 分支：`feat/openai-sdk-starter-e2e`
- 基线提交：`f0abad98 feat: add LLM budget and data governance`
- 实施目标：官方 Python/Node SDK 真实 HTTP E2E；Starter 外部 Consumer 完整自动装配。
- API 原则：只做增量验证和自动装配补齐，不修改既有端点、旧字段、SSE 事件或 `/v1` 默认 feature flag。
- 数据库原则：本批不新增迁移、表、索引或运维 SQL。

## 2. 进度

| Slice | 内容 | 状态 | 证据 |
|---|---|---|---|
| A | Starter 完整 Bean Graph 与外部 Consumer | 已完成 | 外部 Consumer 不扫描 `com.springairag`；真实 PostgreSQL/Flyway Context 通过 |
| B | 官方 Python/Node SDK 真实 HTTP E2E | 已完成 | Core standalone 与 Starter Consumer 两种拓扑均通过 Python `2.14.0`、Node `7.34.0` |
| C | 门禁、文档与脚本 | 已完成 | SDK 门禁、脱敏汇总、端口/代理/回收处理、双语长青文档和 release 入口均已完成 |
| D | 实施阶段连续 `3/3` 检查 | 已完成 | 修正证据表述及归档链接后，连续三轮无问题、无修改，见 §3.6 |
| E | 集成、回归、验收与 Git 交付 | 验收完成 | 全量 Maven、PostgreSQL 矩阵、官方 SDK、真实模型、Client API 和 `scripts/dev.sh` 均通过；Git 交付按 §4 核验 |

## 3. 命令与结果

实施过程中按时间顺序记录命令、隔离端口/数据库、结果、失败原因和修复。

### 3.1 Starter Bean Graph

已通过：

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -pl spring-ai-rag-starter -am clean test \
  -Dtest=ExternalStarterConsumerContextTest \
  -Dstarter-consumer.it.enabled=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```

证据：外部 Consumer 包名为 `example.consumer`，未扫描 `com.springairag`；Testcontainers
启动 `pgvector/pgvector:pg16`，Flyway 从空库执行至 V64，Controller、预算、治理、Trace
和 Health Bean Graph 成功初始化。

### 3.2 官方 SDK 黑盒门禁

已通过：

```bash
OPENAI_SDK_E2E_LOG_DIR=.verification/openai-sdk-e2e/recheck-random-20261016-123041 \
./scripts/verify-openai-sdk-e2e.sh
```

该次运行在 Core standalone 与外部 Starter Consumer 两种拓扑下，均通过 Python/Node 的
`/v1/models`、JSON Chat、SSE、`finish_reason`、幂等重放、无效模型错误信封和无效凭据。
默认使用仓库 Mock 上游，不产生真实模型费用；结果摘要为
`.verification/openai-sdk-e2e/recheck-random-20261016-123041/summary.md`。

此前失败及修复：

1. 覆盖 mock embedding identity 时缺少显式 profile key；门禁现在声明
   `RAG_EMBEDDING_PROFILE_KEY=sdk-e2e-mock-embedding-1024-v1` 及 revision/provider。
2. Python SDK 的 `httpx` 受本机代理影响访问回环地址；门禁为 mock 模式设置
   `NO_PROXY/no_proxy`，不改变 real-LLM 模式的调用方代理配置。
3. Starter 生产依赖不传递测试 Actuator；就绪探测以主要 Client API `/v1/models` 为后备，
   不把 WebUI 或 Actuator 当成外部服务验收的唯一条件。
4. Shell 管道可能掩盖 runner 退出和阻止清理；门禁现已记录每个 SDK 步骤、继续执行配对
   runner，并显式等待/回收自身进程。
5. 默认 Mock 端口可能被本机其他服务/代理路径在选端口后抢占，单纯 `/health` 会误判；门禁
   现在默认使用内核分配的随机回环端口，并同时校验 Mock health 的 `status/model` 和真实
   Chat probe。自动选择端口在身份校验失败后重新取随机端口；显式指定端口则直接失败，
   不会静默使用其他服务。

### 3.3 API 兼容性边界

本批只新增 Starter 自动装配和测试/门禁能力；没有改变既有 HTTP endpoint、method、旧请求
字段、旧响应字段、SSE event、认证默认值或 `/v1` feature flag 默认值。没有新增 Flyway
迁移；SDK runner 只验证现有外部 Client 契约。

### 3.4 加强验收后的问题修复（2026-10-16）

再次 review 发现 Node runner 对应失败请求缺少“意外成功也失败”的断言、SSE 原始
`[DONE]` 与顺序未锁定、Core/Starter 共用 classpath 会掩盖依赖缺口。现已增加 20 项
runner 正/负变异自测，分别构造两个生产 runtime classpath，并加入 ACL、PostgreSQL
quota、feature-disabled、上游不可用与 provider 调用计数断言；实现检查重置为 `0/3`。

隔离 Starter runtime 真实暴露：Core 的 Actuator 是 optional 依赖，Starter 过去只有
测试 Actuator，生产消费缺少 `HealthIndicator`。Starter 现在显式传递 Actuator，符合
默认提供健康/指标 Bean Graph 的产品边界；不要求运维手工补依赖。门禁同时显式配置
PostgreSQL quota 的 `strategy=principal`。Maven install 后再计算分拓扑 runtime
classpath，避免消费本地旧 POM。前次失败证据保留在 `.verification/openai-sdk-e2e/`。

新增 Starter gated IT 登记到聚合 runner，清单校验扩展到 Core/Starter；显式开启 IT
但 Docker 不可用时必须失败，不再静默 skip。新增 release `--with-openai-sdk` 入口。

加强版门禁已在 `.verification/openai-sdk-e2e/verified-20261016-141112/summary.md`
通过：两种独立生产 classpath、
Python/Node 正/负自测、JSON/SSE 原始终态、provider 计数幂等、ACL/quota、上游拒绝
映射 `503 server_error`、feature-disabled + Native 可用。修复了 SSE `data:` 可带或
不带空格的合法格式差异，以及 Native Collection 无 key 创建仍返回 `200` 的旧契约。
上游 503 场景通过显式 Mock probe 触发非重试的上游 401，验证 RAG 对外 503；不冒充
真实断网或真实 provider 故障证据。

全量 Maven 首次发现 OpenAPI 共享 Context 的测试夹具未提供
`RagRetrievalEvaluationRepository`，导致 40 个契约用例启动失败。该 Context 显式排除
真实 DataSource/JPA，故为它补齐 Repository mock，而不把生产治理依赖改为可选。修复后
重跑 focused OpenAPI 与全量 Maven，实施收敛仍为 `0/3`。

### 3.5 最终集成、回归与真实模型验收（2026-10-16）

以下验证均在本批代码完成后执行，未把 WebUI 通过当作外部服务完成依据：

- `mvn clean test`：API 562、Documents 89、Core 7870（204 条条件 skip）、Starter 49（1 条条件 skip），失败 0，错误 0；`BUILD SUCCESS`。
- `./scripts/verify-project-tests.sh`：仓库测试门禁 59 项通过。
- `./scripts/verify-gated-it.sh`：Core/Starter PostgreSQL 集成矩阵 27 个 suite，实际执行 Core 215、Starter 1，共 216 个测试，0 失败、0 错误、0 skip；静态开关清单的 201 项不是运行计数。Starter 外部 Consumer 真实执行，Flyway V1–V64 可用。
- `./scripts/verify-openai-compatibility.sh`：4 步、55 个聚焦测试通过。
- `npm run typecheck`、`npm run test:run`、`npm run build`、`npm run test:e2e:mock`：前端 1030 个 Vitest、93 个 Mock Playwright 通过；lint 全链路（含设计系统 525 项）通过。
- `scripts/dev.sh`：隔离端口 `19182/15183` 与临时 PostgreSQL 启动、健康、真实 WebUI API-Key 工作流、Native API 检查和停止均通过，最终状态 stopped。
- 官方 SDK Mock 双拓扑：`.verification/openai-sdk-e2e/verified-20261016-141112/summary.md`，20 步通过；Core standalone 与 Starter-only Consumer 均覆盖 Python `2.14.0`、Node `7.34.0`。
- 官方 SDK real-LLM：`.verification/openai-sdk-e2e/runtime-final-20261016/sdk-real-pinned/summary.md`，Python/Node 均通过；隔离服务使用 MiniMax/SiliconFlow，未提交密钥或模型答案。
- 外部 Client live：`verify-api-compatibility.sh`、`verify-client-consumer-contract.sh` 均通过；真实模型完整冒烟 `PASS=11 FAIL=0`，覆盖建库、嵌入、检索、KNOWLEDGE、AGENT 工具/引用与 SSE。

一次真实 MiniMax SDK 运行曾因默认 `minimax/MiniMax-M2.7` 上游响应超时而失败；未将其计为通过，也未改生产默认模型。随后使用隔离、固定的 SiliconFlow SDK 验收模型注册表重跑成功；这明确了真实验收结果的模型/Provider 边界。

外部 Consumer 夹具实际放在 Starter 的 `src/test/java/example/consumer/`，复用于 Context 与 HTTP 验收；未按早期规划另建 `scripts/test-support/starter-consumer/` 副本。HTTP 启动分别使用 Core/Starter 的生产依赖 classpath，仅为启动入口加入 Starter test-classes，不引入测试依赖；此边界发现并修复了 Actuator 传递缺失。

真实服务统一使用临时 PostgreSQL（host 端口 `33109`）、服务端口 `19181`、开发栈 `19182/15183`；只回收本任务创建的服务和容器，未停止他人服务。最终开发栈 stopped，任务端口无监听。

发布边界：已接入并独立执行 SDK 发布子门禁、文档/锁/契约/编译测试门禁与生产依赖 HTTP 启动；未整体执行 `verify-release.sh`，未宣称 Docker image、Helm 或全 provider 生产环境已验收。本批没有改变部署模板、schema 或已有 API 协议。

### 3.6 实施收敛检查

在所有基本门槛完成后进行限界只读检查。早期发现集成测试静态清单与实际执行数混淆、接入指南完成度过度表述、真实模式 alias 策略说明及归档链接问题，均立即修正并重置计数。

最终连续检查均为 2026-10-16、机器本地 CST：

| 时间 | 范围 | 问题/处理 | 结果 |
|---|---|---|---|
| 15:25 | Starter Bean Graph、Actuator、治理有界事务、V64、锁策略和文档链接 | 无问题，无修改 | 1/3 |
| 15:29 | endpoint 默认值、SDK 20 项变异、OpenAPI/Consumer 契约、SSE/幂等/认证 | 无问题，无修改 | 2/3 |
| 15:32 | 脚本语法/回收、开关清单、16 项文档门禁、归档、启动停止态和差异卫生 | 无问题，无修改 | 3/3 |

表格在检查全部结束后一次性记账；三轮期间未修改实现或文档。本次记录之外只删除一条无行为影响的行尾解释性注释，协议 runner 变异自测继续通过。

## 4. 最终交付

- 实现提交：与本记录同批的 `feat/openai-sdk-starter-e2e` 提交；具体 hash 以 `git log --oneline --all --grep='OpenAI SDK'` 和回合并记录为准，避免在文件内自引用自身 commit。
- 交付顺序：提交特性 → fetch/merge 已推送的 `origin/main` → 无变化时保留已验收基线，否则重新复验 → merge 到 `main` → push → 比对 `main`、`origin/main` 与工作区状态。
- 干净与同步判据：`git rev-parse HEAD` 等于 `git rev-parse origin/main`，`git status --porcelain` 为空；最终核验结果由交付终端和会话汇报记录。
