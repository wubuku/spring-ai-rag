# 受管 API Principal 到期预警实施进度

> 对应规划：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)
> 工作区：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
> 当前分支：`feature/managed-api-principal-expiry-alerts`
> 规划 checkpoint：`main@73ded395`（2026-08-27）

## 当前状态

- 阶段：主体实现、focused/完整前后端门槛、双语长青文档、项目门禁和真实
  LLM/Embedding 客户生命周期均已通过；准备同步最新 `origin/main` 并按合并后基线复验
- 规划审查计数：`3/3`
- 实现审查：按用户最新要求不执行重复三轮代码 review，以完整自动化验收和运行时证据收敛
- worktree：只使用主工作区；未创建额外 worktree，未使用 stash

## 已完成探索

- 核对外部采用方需求中 operation capability、最小权限 principal、provisioning 幂等、
  capability discovery、Sync Run receipt、operation observability 和 `429 Retry-After`
  均已交付，避免重复规划。
- 比较 async purge、token/cost hard limit、OAuth/OIDC、`EACH_COLLECTION` 和 credential
  expiry alerting，选择当前生产风险最明确且通用性最高的到期预警闭环。
- 核对 V7/V17 Alerts schema、AlertService、Email/DingTalk 通知、silence、Alerts WebUI。
- 核对 V48-V55 principal/credential/rotation 生命周期以及 create/update/revoke 事务边界。
- 核对现有 embedding job 的 after-commit Spring Event + Scheduled fallback 项目模式。
- 确认 Alerts WebUI 当前错误读取 `triggeredAt`，后端实际契约为 `firedAt`。

## 已冻结决策

- PostgreSQL 仍是唯一权威状态；Spring Event 只负责事务提交后的低延迟提示。
- Scheduled 默认每小时一次，只处理时间跨阈值和漏事件恢复，不做秒级轮询。
- 同一 principal 只保留一条 active expiry managed alert；阶段升级复用同一行。
- partial unique index + 条件更新/CAS 协调多实例，不使用悲观锁或外部消息代理。
- 通知使用独立 state/notified version claim；同一阶段重复事件不重复发送。
- 告警仅保存 stable principal ID、role、expiry、phase、剩余秒数和 policy version。
- WebUI 不自行计算阈值，避免浏览器时间和服务端配置漂移。

## 规划审查日志

### 第 1 轮：需求闭环与管理面安全

- 时间：2026-08-27
- 范围：目标、外部通知、API 可发现性、权限、低敏投影和兼容默认。
- 发现：
  - Email/DingTalk 默认 `alertTypes` 不包含新类型，默认启用渠道后仍不会外发到期通知；
  - `/alerts/**` 当前没有 operator 角色校验，数据库 `NORMAL` principal 可按通用 capability
    读取或修改全局告警；加入 principal ID 后会扩大管理面信息泄漏。
- 处理：
  - 规划要求默认通知 allow-list 增加 `API_PRINCIPAL_EXPIRY`，显式部署配置不被覆盖；
  - 全部 Alerts 路由统一收紧为 environment root、数据库 ADMIN、legacy static，以及
    auth-disabled 的直接 loopback；NORMAL 在 repository 读取前返回通用 `403`。
- 结果：已修改规划，计数重置为 `0`，重新开始连续三轮检查。

### 第 2 轮：schema、并发与异步通知可行性

- 时间：2026-08-27
- 范围：V57、JPA/JDBC 并发、事件消费者、fallback 扫描、公平性和通知执行合同。
- 发现：
  - `NotificationService` 当前在 primitive `boolean` 返回方法上使用 `@Async`，不符合
    Spring Async 代理合同，新增类型即使进入 allow-list 也可能无法发送；
  - managed alert 若用 raw JDBC 更新但不推进 `RagAlert.version`，会与 JPA 人工 resolve
    形成丢失更新；
  - 固定按 expiry 排序并截断的扫描会让前一批长期 active principal 永久占据 limit，
    后续 principal 在大规模场景下可能永远不被对账。
- 处理：
  - 把通知接口冻结为 `CompletableFuture<Boolean>`，保持有界异步和 best-effort 语义；
  - 所有 managed update 使用 `id + version + status` CAS，并同步推进 JPA version；
  - V57 为 principal 增加内部 `expiry_alert_checked_at`，扫描按最久未检查优先并在成功后
    推进，实现跨轮次公平覆盖。
- 结果：已修改规划，计数重置为 `0`，重新开始连续三轮检查。

## 连续无修改审查

### 第 1 轮：需求闭环与自包含性

- 时间：2026-08-27
- 范围：目标、默认值、管理面安全、低敏数据边界、兼容策略和非目标。
- 发现问题：无实质问题。
- 处理措施：无修改。
- 结果：连续无修改计数 `1/3`。

### 第 2 轮：schema、事务与多实例收敛

- 时间：2026-08-27
- 范围：V57、JPA/JDBC version 协作、after-commit 事件、通知 claim、候选轮转和有界重试。
- 发现问题：无实质问题。
- 处理措施：无修改。
- 结果：连续无修改计数 `2/3`。

### 第 3 轮：实施、验收与交付

- 时间：2026-08-27
- 范围：实施切片、一次性测试矩阵、PostgreSQL/HTTP/WebUI 硬门槛、真实 provider 回归、
  origin/main 同步、完整复验和回滚边界。
- 发现问题：无实质问题。
- 处理措施：无修改。
- 结果：连续无修改计数 `3/3`，规划可进入实施。

## 下一步

1. 完成 V57、配置、受管告警协调器和通知异步合同；
2. 接入 principal 生命周期事件与低频 fallback；
3. 收紧 Alerts 管理面授权并修正 WebUI 契约；
4. 一次性补齐 PostgreSQL、HTTP、前端与真实 provider 验收。

## 实施日志

### 2026-08-27：实施基线建立

- 规划连续 `3/3` 无修改审查通过。
- `main@73ded395` 已推送到 `origin/main`。
- 在同一工作区创建 `feature/managed-api-principal-expiry-alerts`；没有创建额外 worktree。
- 已核对配置绑定、Alerts JPA version、通知实现、principal 事务边界、Testcontainers 与
  WebUI 测试入口，开始切片 A。

### 2026-08-27：主体实现与测试编译

- 已增加 V57 additive migration、到期告警配置、事务提交后 principal 生命周期事件、
  异步事件 worker、每小时有界 fallback scan 与 PostgreSQL CAS 对账。
- 已接入 principal 创建、策略更新和吊销路径；通知接口修正为合法的
  `CompletableFuture<Boolean>` Spring Async 合同。
- 已把全部 Alerts 路由收紧为 operator 管理面，并修复 WebUI `triggeredAt`/`firedAt`
  契约及到期阶段、principal、expiry 展示。
- 已完成第一批配置、事件、异步代理、授权、HTTP JSON 和前端组件测试。
- 验证证据：
  - `mvn -pl spring-ai-rag-core -am -DskipTests compile`：通过；
  - `mvn -pl spring-ai-rag-core -am -DskipTests test-compile`：通过；
  - `npm run typecheck`：通过。
- 下一硬门槛：一次性补齐并运行 V57 PostgreSQL 集成矩阵与核心 Mock Playwright。

### 2026-08-27：PostgreSQL 生命周期验收

- 新增 `ApiPrincipalExpiryAlertPostgresIntegrationTest`，在一次性
  `pgvector/pgvector:pg16` 数据库上从空库执行 V1-V57。
- 6 个场景全部通过：
  - V57 managed alert 检查约束与 active dedupe partial unique index；
  - 8 路并发对账只保留一个 active row、一个 state version 和一次通知 claim；
  - `WARNING -> CRITICAL -> EXPIRED` 复用同一行，同阶段刷新不重复通知；
  - expiry 延期、principal 吊销自动解决，条件重新进入窗口创建新历史；
  - active alert 漏事件恢复与 105 个候选跨 100 条 batch 的公平轮转；
  - 持久化 message/metrics 不包含 principal name、Collection allow-list、quota 或
    credential 信息。
- 命令：
  `TESTCONTAINERS_RYUK_DISABLED=true TESTCONTAINERS_CHECKS_DISABLE=true mvn -pl spring-ai-rag-core -Dtest=ApiPrincipalExpiryAlertPostgresIntegrationTest -Dapi-principal-expiry-alert.it.enabled=true test`
  通过，`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`。
- 本机 Docker Hub TLS 代理无法拉取 Ryuk，验收显式关闭 Ryuk 并复用本机已有 pgvector
  镜像；测试容器本身正常创建、执行并由测试结束流程停止。

### 2026-08-27：focused 基本集成硬门槛

- 新增 `scripts/verify-api-key-expiry-alerts.sh`，先执行受影响后端测试，再执行 V1-V57
  PostgreSQL 生命周期矩阵、前端 typecheck/Vitest/alignment/build 和 Alerts Mock
  Playwright；每一步独立保存日志和汇总。
- focused 后端门槛通过：`Tests run: 218, Failures: 0, Errors: 0, Skipped: 0`。
- PostgreSQL 验收通过：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`。
- WebUI 门槛通过：
  - TypeScript typecheck；
  - Vitest `32` 个测试文件、`234` 个测试；
  - alignment policy；
  - Vite production build；
  - Alerts Mock Playwright `1/1`，同时断言请求 header、响应 JSON、DOM 可见性和可访问
    状态，不使用截图作为证据。
- 验证汇总：
  `.verification/api-key-expiry-alerts/20260828-064719/summary.md`。

### 2026-08-27：双语长青文档与项目门禁

- 已同步配置、架构、项目上下文、REST API、业务接入、开发者参考、测试指南、发布清单、
  TODO、索引、`AGENTS.md` 与 project-docs Skill。
- 活跃项目事实与最新迁移断言已从 V56 提升为 V57；V56 专属功能说明和历史归档证据保持
  原版本语义。
- `scripts/verify-project-docs.sh` 已纳入新的一键门禁，并强制业务接入指南可发现
  `/api/v1/rag/alerts/active` 与 `API_PRINCIPAL_EXPIRY`。
- `./scripts/verify-project-docs.sh`：`11/11` 通过，覆盖 194 个 Markdown 文件和
  1234 个相对链接；`bash -n` 与 `git diff --check` 同时通过。

### 2026-08-27：完整专用硬门槛

- 命令：
  `API_KEY_EXPIRY_ALERT_VERIFY_RUN_ID=v57-full-20260827-r1 ./scripts/verify-api-key-expiry-alerts.sh`。
- `11/11` 步骤通过：
  - focused 后端 `218/218`；
  - 空库 V1-V57 PostgreSQL 生命周期 `6/6`；
  - WebUI typecheck、Vitest `234/234`、alignment、production build；
  - Alerts Mock Playwright `1/1`；
  - `mvn clean compile test-compile`；
  - 禁悲观锁、双语文档、Shell、diff 与新增行密钥门禁。
- 证据：
  `.verification/api-key-expiry-alerts/v57-full-20260827-r1/summary.md`。
- 下一步：在隔离双实例、一次性 PostgreSQL 和真实 LLM/Embedding 上补充 V57
  到期告警客户生命周期断言，并持续观察 backend 日志。

### 2026-08-28：真实客户生命周期门禁补齐

- 已扩展 `scripts/verify-managed-api-principals.sh --with-real-llm`，真实模式从 `.env`
  加载 Chat 与 SiliconFlow Embedding 配置，Mock 模式仍使用不可达 dummy Embedding，
  避免快速门禁误发真实请求。
- 新增普通受管 principal 的真实客户合同：
  - 用受限 Collection ACL 和 `RAG_READ`/`RAG_WRITE` 完成外部文档 `ASYNC` upsert；
  - 在 60 秒恢复扫描之前确认 Spring Event 已启动持久 embedding job，再等待真实向量就绪；
  - 通过另一个实例执行 vector-only Search 和带真实引用的 `KNOWLEDGE` Chat；
  - 确认普通 principal 读取 Alerts 返回通用 `403`；
  - 创建后跨实例观察 `WARNING`，策略更新后复用同一告警行升级到 `CRITICAL`，延期到
    60 天后自动 `RESOLVED`；
  - 三次状态收敛均限定在 30 秒轮询窗口内，显著早于显式配置的一小时 Scheduled 兜底；
  - API 与只读 PostgreSQL 事实共同确认单行 dedupe、state/notified version 和低敏字段。
- 脚本静态门槛：`bash -n` 与 `git diff --check` 通过。
- 下一步：执行完整真实 provider 门禁并持续观察双后端日志；若失败，只修复由证据定位的
  本批次正确性问题，再重跑受影响门槛。

### 2026-08-28：首次完整真实门禁与定向修复

- 首次命令：
  `MANAGED_API_REAL_ENV_FILE=.env MANAGED_API_REAL_LLM_PROVIDER=openai MANAGED_API_VERIFY_RUN_ID=v57-real-20260828-r1 ./scripts/verify-managed-api-principals.sh --with-real-llm`。
- 结果：`10 passed, 3 failed`；证据：
  `.verification/managed-api-principals/v57-real-20260828-r1/summary.md`。
- 失败均发生在真实 provider 调用之前：
  - PostgreSQL 矩阵的 4 处 latest-migration 断言仍停留在 V55；
  - full Maven 中 Email/DingTalk 默认类型测试仍使用旧列表长度；
  - full-stack preflight 的脚本检查已提升到 capability protocol `1.1`，但 WebMvc
    fixture 与成功报告内部自校验仍构造或要求 `1.0`。
- 已统一 latest migration 为 V57、通知默认类型包含 `API_PRINCIPAL_EXPIRY`、能力协议
  为 `1.1`；历史归档中的旧版本证据不改写。
- 定向复验：
  - PostgreSQL/Maven 组合矩阵除 WebMvc fixture 外全部通过，定位后修正 fixture；
  - `IntegrationCapabilitiesControllerTest`：`2/2` 通过；
  - binding preflight 自测：`11` 个输入/安全负例与 `5` 个能力契约场景全部通过；
  - `bash -n`、Python 语法编译与 `git diff --check` 通过。
- 下一步：使用新 run ID 重跑完整真实 LLM/Embedding 与到期告警生命周期门禁，并持续
  观察双实例日志。

### 2026-08-28：第二次完整门禁与真实 provider 可用性切换

- 第二次命令继续使用用户指定的 `openai-next/grok-4.5`：
  `MANAGED_API_REAL_LLM_PROVIDER=openai MANAGED_API_VERIFY_RUN_ID=v57-real-20260828-r2 ./scripts/verify-managed-api-principals.sh --with-real-llm`。
- 结果：`12 passed, 1 failed`；PostgreSQL、完整 Maven、WebUI、Mock Playwright、文档、
  禁锁和双实例非模型合同全部通过。
- 唯一失败发生在第一个真实 Chat 请求：服务端最终返回 `504`；后端日志确认上游在应用
  HTTP retry 与 Spring Retry 的多轮尝试中持续返回 `503 no_available_account`。
  证据：`.verification/managed-api-principals/v57-real-20260828-r2/summary.md`。
- 为避免对同一不可用上游盲目重试，已使用 `.env` 中不输出密钥的最小真实请求探测备用
  provider：
  - MiniMax 原生 `/v1/chat/completions`：HTTP `200`；
  - Anthropic Messages 兼容端点：HTTP `200`。
- 决策：下一轮完整门禁切换到项目一等支持的 `minimax/MiniMax-M3`，真实 Embedding 仍使用
  `siliconflow/BAAI/bge-m3`；保持全部生命周期断言不变。

### 2026-08-28：完整真实 LLM/Embedding 客户生命周期通过

- 成功命令：
  `MANAGED_API_REAL_ENV_FILE=.env MANAGED_API_REAL_LLM_PROVIDER=minimax MANAGED_API_VERIFY_RUN_ID=v57-real-20260828-r3 ./scripts/verify-managed-api-principals.sh --with-real-llm`。
- 结果：`13 passed, 0 failed`；证据：
  `.verification/managed-api-principals/v57-real-20260828-r3/summary.md`。
- 合并前完整门槛再次通过：
  - PostgreSQL integration matrix；
  - `mvn clean compile test-compile`；
  - full Maven test，core `3232` tests、starter `44` tests 均无失败；
  - WebUI Vitest `234/234`、TypeScript、production build、alignment；
  - Mock Playwright 与真实后端 WebUI Playwright；
  - 禁悲观锁、项目文档、Shell、diff 和新增行密钥检查。
- 真实 Chat 生命周期：
  - `minimax/MiniMax-M3` 完成 `9` 次真实模型调用；
  - 覆盖原生 JSON、幂等重放零 provider 增量、原生 SSE、OpenAI 兼容 JSON/SSE；
  - 覆盖 staged rotation 完成、取消、pending family 吊销、principal/session 连续性和
    read-only 写入拒绝。
- 真实 Embedding 与到期告警生命周期：
  - `siliconflow/BAAI/bge-m3` 完成真实 1024 维嵌入；
  - 外部文档 `ASYNC` 写入后由 after-commit Spring Event 在 60 秒 fallback 前启动并完成
    embedding job；
  - vector-only Search 命中文档，`KNOWLEDGE` Chat 返回目标事实与引用；
  - 普通 principal 访问 Alerts 为 `403`；
  - 双实例下 `WARNING -> CRITICAL -> RESOLVED` 分别在 `0s`、`1s`、`0s` 内收敛，
    显著早于一小时 Scheduled fallback；
  - 同一告警行复用，state/notified version、低敏 metrics、API/history/数据库事实一致。
- 下一步：更新最终证据索引，拉取并合并最新 `origin/main`，按合并后代码完整重跑门槛和
  真实 provider 生命周期，再完成特性分支与 `main` 交付。

## 后续硬门槛

1. 同步 V57、配置、API、运维和验收命令到双语长青文档与项目文档门禁；
2. 执行完整 Maven、WebUI、服务启动、禁锁、文档、diff、shell 与密钥检查；
3. 使用 `.env` 中的真实 LLM/Embedding 配置完成受管 principal、异步文档嵌入、
   Chat、到期告警延期解决和清理生命周期验收，并持续观察日志；
4. 合并最新 `origin/main` 后按新基线完整复验，再完成分支与 `main` 交付。
