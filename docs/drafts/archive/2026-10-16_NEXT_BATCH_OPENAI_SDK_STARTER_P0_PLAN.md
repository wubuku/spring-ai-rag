# 下一批 P0 实施规划：OpenAI SDK 真实 HTTP E2E 与 Starter 完整 Bean Graph

> 状态：规划与实施均已通过连续 `3/3` 检查，实施及本批集成/回归/验收已完成，现归档。
> 实施差异、实际验收边界与交付核验方式见同目录的
> [实施记录](2026-10-16_NEXT_BATCH_OPENAI_SDK_STARTER_P0_PROGRESS.md)。
>
> 本批面向外部 Client 的服务形态。WebUI 只作为辅助验证，不作为完成依据。

## 1. 执行结论

本批一次性实施两个相互关联、但可独立验证的 P0：

1. **OpenAI 官方 SDK 真实 HTTP E2E**：用官方 Python SDK 与官方 JavaScript/TypeScript
   SDK，通过真实 HTTP 调用隔离的 Spring Boot 服务，覆盖 `/v1/models`、非流式 JSON Chat
   Completions、SSE streaming、`[DONE]`、认证、错误 envelope、Collection ACL、quota、
   feature flag 和 `Idempotency-Key`。默认使用仓库已有的确定性 mock 上游模型，不把普通门禁
   变成真实模型计费；通过显式环境变量才执行真实 LLM 变体。
2. **Starter 完整 Bean Graph / 产品边界验收**：只依赖 `spring-ai-rag-starter` 的外部
   Consumer 不再需要额外扫描 `com.springairag` 才能获得核心 RAG 服务、OpenAI 兼容入口、
   安全过滤器、预算/治理、trace 和健康能力。Starter 自动装配默认启用；用户仍可通过已有
   `general.rag.enabled=false` 关闭整套能力。

### 本批的安全默认

- Starter 的完整自动装配是新增能力，默认启用，且保留 `@ConditionalOnMissingBean` 扩展点。
- `/v1` 公开兼容入口的既有 `rag.openai-compatibility.enabled=false` 默认值本批不改变。
  这是认证、ACL、上游模型和成本边界的安全开关；本批验证“启用后官方 SDK 可用”，不把
  一个原本关闭的服务入口无条件暴露给现有部署。
- 不修改已有 `/api/v1/rag/**`、`/v1/**` 的 HTTP method、旧请求字段、旧响应字段、SSE
  event 名称或 OpenAI 标准 body。新验证脚本、Starter 自动装配和测试夹具均为增量能力。

## 2. 当前基线与已核对事实

### 2.1 Git 与交付基线

- 基线提交：`f0abad98 feat: add LLM budget and data governance`。
- 基线工作区干净，远端 `origin/main` 已同步。
- 本批特性分支：`feat/openai-sdk-starter-e2e`；完成后按
  [交付工作流](../../delivery-workflow-zh-CN.md) 以 merge 方式回到 `main`，不改写历史。
- 不新增 Flyway 迁移；本批不改变数据库 schema，也不要求运维执行 SQL。

### 2.2 现有 OpenAI 兼容事实

- `/v1/models` 与 `/v1/chat/completions` 由
  `spring-ai-rag-core/src/main/java/com/springairag/core/controller/OpenAiCompatibilityController.java`
  提供，控制器由 `rag.openai-compatibility.enabled` 条件装配。
- 现有 mapper、alias registry、scope adapter、错误映射和 SSE 终态已有单元/Web 层合同测试；
  现有 `scripts/verify-openai-compatibility.sh` 主要执行 Maven/静态检查，尚未调用官方 SDK
  穿过真实 HTTP。
- `/v1` 使用 Bearer 或 `X-API-Key`，非 root 模式仍保留历史 static/query 兼容边界；测试不得
  把 query credential 当作新的推荐接入方式。
- OpenAI response 继续只返回 OpenAI 标准字段；Native API 的 sources、evidence、trace 等
  结构化扩展不在本批塞入标准 OpenAI body。
- `Idempotency-Key` 的精确重放、stream 终态和 `[DONE]` 已有服务端实现，本批只补 SDK 级
  黑盒证据，不重写协议。

近距离事实入口：

- [OpenAI 兼容性 readiness](../../openai-compatibility-readiness-zh-CN.md)
- [REST API](../../rest-api-zh-CN.md)
- [Client Contract Kit](../../client-contract-kit-zh-CN.md)
- [SSE 协议](../../SSE-PROTOCOL.md)

### 2.3 现有 Starter 缺口

- `spring-ai-rag-starter` 当前只在 `GeneralRagAutoConfiguration` 中注册通用 properties、
  API SLO、安全配置、健康与 metrics 等 Bean。
- 当前 `GeneralRagAutoConfiguration` 没有扫描或导入完整的 `com.springairag.core` 组件树；
  外部应用需要自行扫描 `com.springairag`，这不是“只依赖 Starter 即可运行”的稳定产品边界。
- Core standalone 依赖 `SpringAiRagApplication` 的 `@SpringBootApplication(scanBasePackages =
  "com.springairag")` 和 `@ConfigurationPropertiesScan("com.springairag.core.config")`。
- Starter 补齐自动装配时必须排除 `SpringAiRagApplication` 本身，避免外部应用中嵌套第二个
  `@SpringBootApplication` 和重复扫描。
- `RagProperties` 当前通过显式 `@Bean` 注册；部分其他 properties 由 core 配置类的
  `@EnableConfigurationProperties` 注册，另有 `MultiModelProperties`、`RagProxyProperties`、
  `RagSseProperties` 依赖主应用的 configuration-properties scan。Starter 必须显式覆盖这些
  缺口，不能用可能造成重复 Bean 的全量 scan 替代。

近距离事实入口：

- `spring-ai-rag-starter/src/main/java/com/springairag/starter/GeneralRagAutoConfiguration.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/SpringAiRagApplication.java`
- `spring-ai-rag-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `spring-ai-rag-starter/src/test/java/com/springairag/starter/GeneralRagAutoConfigurationIntegrationTest.java`

## 3. 目标、非目标与冻结决策

### 3.1 目标

1. 只依赖 Starter 的 Consumer 在不写 `scanBasePackages = "com.springairag"` 的情况下，能
   自动获得完整 Core 服务端 Bean Graph。
2. 官方 Python SDK 和官方 JavaScript/TypeScript SDK 通过真实 HTTP 调用 `/v1`，并且断言
   客户可观察的 JSON/SSE/HTTP status/header 行为。
3. 测试覆盖 Core standalone 与 Starter consumer 两类拓扑；至少有一类通过真实服务进程
   验证 SDK，另一类通过真实 Spring ApplicationContext/HTTP 容器验证装配边界。
4. 为后续发布验收提供一个可重复脚本，输出 secret-safe summary，不记录 API key、prompt、
   完整 answer、工具参数或异常正文。
5. 维持已有 Native API 和 OpenAI-compatible API 的 additive-only 兼容策略。

### 3.2 非目标

- 不新增 OpenAI Responses API、Assistants、Agents、tool orchestration 或多模态协议。
- 不修改标准 OpenAI response 增加 `sources`、`evidenceAssessment`、`traceId` 等私有字段。
- 不将 `/v1` feature flag 默认改为公开启用。
- 不新增 OAuth/OIDC、TLS、Secret Manager、备份恢复或多租户体系；这些属于独立生产化
  readiness 批次。
- 不新增数据库表、索引、迁移或手工运维步骤。
- 不把 WebUI 页面可用作为本批成功条件。

### 3.3 关键决策

| 决策 | 默认方案 | 可逆边界 |
|---|---|---|
| Starter 装配方式 | 在 Starter 自动配置中做限定的 Core component scan，排除 `SpringAiRagApplication`，并显式注册缺少的 configuration properties | 可改为独立 `RagCoreAutoConfiguration`，不改变对外 API；不得退回要求用户手工 scan |
| Bean 冲突 | 继续使用 Spring Boot 条件装配和已有 `@ConditionalOnMissingBean`；不得扩大 allow-bean-definition-overriding 依赖 | 用户显式 Bean 优先，冲突时由测试发现并修正 |
| `/v1` 默认值 | 保持现有关闭默认值；测试 profile 显式启用 | 若未来完成公网安全 readiness，可独立调整，不在本批隐式改变 |
| SDK 上游 | 默认仓库 mock LLM；Python/Node SDK 均通过 HTTP 调用被测 RAG 服务 | `--with-real-llm` 或同等显式开关才访问 `.env` provider |
| Node 依赖 | 使用独立 `scripts/openai-sdk-e2e/` fixture 和锁定依赖，不污染 WebUI `package.json` | 仅升级 fixture 版本，不影响生产构建 |
| Python 依赖 | 使用独立 requirements 文件和 `.verification` 临时 venv，禁止修改开发者全局 Python 环境 | venv 可删除；服务端测试不依赖本机已有 SDK |
| 数据库 | 默认使用脚本自建的可丢弃 PostgreSQL/现有 gated IT 入口；不得写用户开发库 | SDK gate 不提供任意外部 DB 写入，真实模式仅连接调用方已启动的 RAG 服务且不创建资源 |
| 兼容证据 | 先跑既有 contract fixture，再跑真实 HTTP SDK，最后跑旧 Native smoke | 失败时保留日志和失败状态，不把 conditional skip 记成通过 |

## 4. 对外契约与运行时行为

### 4.1 Starter Consumer 契约

只依赖 Starter 的外部应用应满足：

- `general.rag.enabled` 缺省或为 `true` 时，Core controllers、services、repositories、
  filters、configuration 和 health/metrics Bean 可自动装配。
- 用户可用自己的 `@SpringBootApplication`、自己的包扫描范围和自己的 `DomainRagExtension`；
  Starter 不要求用户扫描 `com.springairag`。
- 用户提供同类型自定义 Bean 时，已有 `@ConditionalOnMissingBean` 语义继续生效；不以
  duplicate bean 覆盖作为正常路径。
- `general.rag.enabled=false` 时，Starter 自身的新增 Core scan 不产生完整服务 Bean；现有
  GeneralRag properties 绑定行为保持不变。

### 4.2 官方 SDK 黑盒契约

脚本必须通过真实 TCP/HTTP 调用，不调用 Java Controller、Service 或 MockMvc。至少覆盖：

| 场景 | Python SDK | Node/TS SDK | 硬断言 |
|---|---:|---:|---|
| `GET /v1/models` | ✓ | ✓ | list envelope、alias 可见、Bearer 认证 |
| 非流式 Chat | ✓ | ✓ | `chat.completion`、model alias、assistant message、usage/choices |
| 流式 Chat | ✓ | ✓ | chunk 序列、role/content/stop、最终 `[DONE]` |
| 错误 envelope | ✓ | ✓ | 4xx/429/503 的 HTTP status 和 `error` shape |
| 无效 Bearer / scope ACL | ✓ | — | 未授权不能扩展 Collection，不泄漏内部异常 |
| `Idempotency-Key` | ✓ | — | 精确重放 ID/结果一致，provider 不重复调用 |
| feature flag | 脚本级 | 脚本级 | 关闭时 `/v1` 不伪造成功，记录明确 conditional result |
| 旧 Native API | curl/既有脚本 | curl/既有脚本 | `/api/v1/rag/**` 仍可用 |

Python/Node 测试都不假设 WebUI 存在；只读取 HTTP body、SSE、status、headers 和 mock provider
计数器。SDK 失败时输出 endpoint、状态分类和脱敏摘要，不输出密钥和完整响应正文。

## 5. 文件级实施顺序

### Slice A：Starter 自动装配

1. 修改 `GeneralRagAutoConfiguration`：增加限定 Core component scan，排除
   `SpringAiRagApplication`；显式启用缺失的 Core configuration properties。
2. 保留现有 `RagProperties`、GeneralRag properties、SLO、安全、健康和 metrics 的条件与
   自定义 Bean 优先行为。
3. 更新 Starter 注解测试，使其断言 scan 的 base package、应用入口排除项和 properties
   注册清单。
4. 新增一个最小 external consumer context test：应用自身不扫描 `com.springairag`，仅依赖
   Starter auto-configuration；验证关键 `/v1` controller、route policy、auth filter、
   budget/governance、trace 和 health Bean 存在。
5. 新增 `scripts/test-support/starter-consumer/StarterConsumerApplication.java`，包名不在
   `com.springairag` 下、只使用 `@SpringBootApplication` 默认扫描自身包；由验收脚本用
   Starter classpath 编译和启动，作为真实 HTTP Starter consumer，不把测试应用加入生产
   Maven modules。

### Slice B：SDK fixture 与真实 HTTP runner

1. 新增独立 Node fixture（`package.json` + lockfile + runner），依赖官方 OpenAI JavaScript
   SDK；新增 `requirements.txt` 和 Python runner，脚本在 `.verification` 下创建临时 venv，
   按锁定版本安装官方 OpenAI Python SDK，不污染开发者全局环境。
2. runner 只接收 `BASE_URL`、`API_KEY`、`MODEL`、Collection scope 和可选 feature flags；
   不读取或打印 `.env` 原文。
3. 复用已有 `scripts/mock-llm-server.js` 或为它补充稳定 request counter/stream 行为，
   让上游响应和调用次数可确定断言。
4. 新增 `scripts/verify-openai-sdk-e2e.sh`：检查依赖、启动/探测 mock upstream，分别启动
   Core standalone 与上述 Starter consumer（默认 `both`，可用 `--topology` 缩小范围），
   执行 Python/Node 两套 runner，并生成 `summary.md`。
5. 脚本默认使用 mock provider；真实 LLM 只有显式 `OPENAI_SDK_E2E_WITH_REAL_LLM=1` 才可
   运行，真实 key 不进入 summary。

### Slice C：门禁、文档与进度

1. 将 SDK E2E 接入 `scripts/verify-openai-compatibility.sh` 或建立清晰的独立发布门禁，
   不把依赖外部服务的 live run 偷换成静态通过。
2. 更新 `docs/openai-compatibility-readiness*.md`、`docs/client-contract-kit*.md`、
   `docs/testing-guide*.md`、`docs/developer-reference*.md`，同步中英文事实。
3. 更新 `docs/drafts/README*.md`，登记本批活动规划；实施阶段维护对应 `*_PROGRESS.md`。
4. 若 Starter 产品边界变化影响架构/配置说明，同步 `architecture*`、`configuration*`。

## 6. 验收矩阵

### 6.1 规划前置门禁

- 代码、schema、配置、现有 OpenAI/Starter 测试与脚本已交叉阅读。
- 规划连续三轮检查，每轮检查范围不同；发现实质问题立即修复并将计数重置为 `0`。
- 规划检查结束前不修改生产代码。

### 6.2 实施基本硬门槛

1. `mvn -pl spring-ai-rag-starter -am clean compile test-compile`。
2. Starter focused tests 和 Core OpenAI focused tests。
3. Starter external consumer context test。
4. Python/Node runner self-test、Shell syntax、fixture dependency install。
5. `git diff --check`、无 secret 检查、无悲观锁检查。
6. OpenAPI/client contract 静态门禁。

### 6.3 最终集成、回归与验收

- Maven 全量测试：API、Documents、Core、Starter。
- PostgreSQL/Flyway gated integration：至少验证 V64 后 schema 可用，且本批不新增迁移。
- mock upstream + 隔离 PostgreSQL 的真实 HTTP SDK E2E：Python/Node JSON + SSE。
- 认证、ACL、quota、feature flag、错误 envelope、幂等重放和 `[DONE]` 黑盒断言。
- 既有 Native API/Client Contract/OpenAPI contract 回归。
- `scripts/dev.sh` 启动、状态、健康、停止；不依赖 WebUI 成功推断后端成功。
- 适用时执行真实 LLM 黑盒 smoke；没有配置真实 provider 时必须明确记录跳过原因。
- 发布门禁、文档门禁、脚本入口门禁和连续实施 `3/3` 检查。

## 7. 风险、回滚与恢复

### 高风险

1. **全量 Core scan 引入缺失依赖或重复 Bean**：通过排除应用入口、显式 properties、
   条件装配和最小 external consumer context 及真实 PostgreSQL 启动验证控制。
2. **SDK 流式解析在真实网络下与 MockMvc 不同**：Python/Node 都必须读取真实 SSE，断言
   chunk、终态和 `[DONE]`；不能只检查最终文本。
3. **SDK fixture 依赖网络**：锁定版本并提供 self-test；服务端协议测试仍独立可运行，fixture
   依赖缺失必须报告失败/条件跳过，不可伪造通过。
4. **OpenAI 兼容入口误变为公开默认**：不修改现有 feature flag 默认值，脚本显式启用且
   配置认证；回滚只需移除新增 Starter scan/脚本即可。

### 回滚边界

- Starter 回滚：恢复 `GeneralRagAutoConfiguration` 的原有 import/property 行为，已有 Core
  standalone 不受影响。
- SDK 回滚：移除 runner/fixture/门禁，不改变服务 API。
- 文档回滚：按代码事实回退对应长青文档；不修改已执行迁移。
- 不使用 `git reset --hard`、`git stash`、`git clean`；不覆盖并发 WIP。

## 8. 进度记录与完成定义

实施阶段在 `NEXT_BATCH_OPENAI_SDK_STARTER_P0_PROGRESS.md` 记录：基线、每个 Slice、命令、
结果、隔离端口/数据库、失败与修复、最终 commit/remote 状态。完成必须同时满足：

1. 规划连续 `3/3` 且实施连续 `3/3`；
2. Starter 只依赖自动装配的外部 Consumer 证据成立；
3. Python/Node 官方 SDK 真实 HTTP JSON/SSE 证据成立；
4. 既有 Client API、Native API、OpenAI body/SSE 和安全边界回归通过；
5. `scripts/dev.sh` 可启动、健康、停止；
6. 相关中英文长青文档同步；
7. 特性分支 commit、merge 到 `main`、push `origin/main`，最终工作区干净。
