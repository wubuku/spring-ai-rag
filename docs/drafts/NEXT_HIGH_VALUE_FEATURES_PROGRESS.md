# 模型调用级持久用量账本实施进度

> **状态**：已合并到 `main`，usage ledger worktree 待清理
>
> **对应规划**：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)
>
> **规划基线**：`main` / `origin/main` @ `7b6f01ad`（2026-08-26）
>
> **规划工作区**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
>
> 计划实施分支：`feat/llm-usage-ledger-20260826`
>
> 计划实施 worktree：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-llm-usage-ledger`

本文是跨会话恢复账本，不是稳定架构事实。不得记录 API key、Authorization、原始 prompt、
answer、tool 参数/结果、异常正文、`.env` 内容或外部项目路径。

## 1. 当前状态

- [x] 上一轮 Collection 幂等性规划/进度已归档。
- [x] 归档链接已修复，文档门禁通过。
- [x] 归档 checkpoint 已提交并推送到 `main@7b6f01ad`。
- [x] 已确认当前 main 工作区干净且 `main == origin/main`。
- [x] 已重新探索 Chat、Spring AI 1.1.8、模型路由、usage、cost、legacy 入口、Metrics
  和当前 V52 schema。
- [x] 已选定模型调用级持久用量账本与成本可观测性为下一轮主方向。
- [x] 已写入自包含活动规划。
- [x] 规划连续三轮无修改审查。
- [x] 规划 checkpoint 提交并推送：`main@81d5b131`。
- [x] 从最新 origin/main 创建专用 feature worktree：`origin/main@5f6d4eb0`。
- [x] Slice A：配置、领域对象、归因、usage/cost 归一化、预算包装与边界单测已完成；
  `BudgetedChatModelTest` 7 项与 `ChatExecutionBudgetTest` 5 项定向测试通过，共 12 项。
- [x] Slice B：repository、recorder、retention、PostgreSQL 集成；空库执行 V1-V53，
  `LlmUsagePostgresIntegrationTest` 4 项全部通过。
- [x] Slice C：mode-aware 与 legacy 全调用边界；定向调用链测试 45 项全绿，
  已覆盖 fallback、流式首事件边界、legacy 入口和模式工厂。
- [x] Slice D：usage API、WebUI、双语长青文档、项目门禁。
- [x] Slice E：专项脚本、真实全栈和真实 LLM。
- [x] 基本硬门槛（专项门禁 12/12 通过）。
- [x] 真实生命周期验收完成；明确记录 provider 不可用和模型能力限制。
- [x] 修改验收脚本/进度文档后的专项硬门槛重跑。
- [x] 按用户要求跳过额外三轮实现代码 review；以一次性完整自动化门禁和真实 provider 验收作为本轮收口证据。
- [x] merge 最新 origin/main 后完整复验。
- [x] feature 合回 main 并 push；`main` 与特性分支均指向 `89b73ad5`。
- [ ] usage ledger worktree 清理。

## 2. 已冻结的关键决策

1. 使用 V53 独立表，不修改 history 或旧 metrics 数据结构。
2. 记录边界是 `BudgetedChatModel` 的一次 call 或 stream subscription；legacy
   `RagChatService` 也必须包装。
3. purpose 固定为 `CHAT`、`QUERY_TRANSFORM`、`QUERY_EXPAND`、`SUMMARY`。
4. 每个 logical execution 使用 UUID 和从 1 开始的 call ordinal。
5. replay 不创建新的预算或 invocation；reclaim 重新执行使用新 logical execution。
6. stable principal 只从认证上下文捕获；普通 principal 只能查自己。
7. usage/pricing/cost 缺失显式表示，不猜测；cost 是配置估算而非供应商账单。
8. recorder fail-open；不得因 ledger 失败重试 provider。
9. 流式 JDBC 写入使用有界 executor；取消记录允许异步完成。
10. 不实施 hard limit、billing、tenant federation、Redis/Kafka 或 provider 内部 retry 拆分。
11. WebUI 只增加 typed usage 区域，保留旧 metrics 和 Raw JSON。
12. 所有规划、代码和文档保持通用、自包含，不依赖外部 Client 背景。

## 3. 规划审查账本

| 轮次 | 时间 | 范围 | 发现/处理 | 连续计数 |
|---|---|---|---|---:|
| 初稿 | 2026-08-26 | 价值、代码调用链、schema、API、前端和验收矩阵 | 已形成自包含方案，等待三轮固定范围检查 | 0 |
| 修复轮 | 2026-08-26 | 第 1 轮：legacy 入口预算、candidate attribution、流式最终一致性 | 发现 legacy 没有明确默认预算且裸 `ChatClient` 会丢失模型/价格归因；已补充默认预算、candidate descriptor 迁移约束和流式 recorder 排空验收；计数重置 | 0 |
| 修复轮 2 | 2026-08-26 | 第 1 轮：purpose attribution、模型价格快照 | 发现仅用 `summaryCall` 布尔值不足以区分四类模型调用，且价格不能在异步 recorder 中读取可变配置；已补充 purpose-aware wrapper 与 invocation 开始时不可变价格快照；计数重置 | 0 |
| 修复轮 3 | 2026-08-26 | 第 1 轮：数据库边界、保留时间和记账阻塞 | 发现“所有字段有界”没有具体数据库上限，retention 时间字段和同步记账超时未冻结；已补充字段上限、按 `created_at` 分批清理、可配置短 statement timeout 和 `nanoTime` duration 约束；计数重置 | 0 |
| 修复轮 4 | 2026-08-26 | 第 1 轮：时间语义与数据库约束 | 发现 duration 使用单调时钟仍不足以保证墙钟 `completed_at >= started_at`；NTP/人工校时回拨可能让正常 invocation 记账失败；已冻结可注入 `Clock`、`nanoTime` duration 与 completed time 钳制规则，并加入确定性测试；计数重置 | 0 |
| 修复轮 5 | 2026-08-26 | 第 1 轮：legacy Chat advisor 旁路与范围边界 | 发现 `RagChatService` 的可选 LLM `QueryRewriteAdvisor` 会直接调用 singleton `ChatModel`，漏记 Chat 成本；同时独立评估/比较调用不应被错误归因到 Chat execution；已冻结 advisor context 注入 candidate/budget/price、`QUERY_TRANSFORM` 记录规则，以及非 Chat 调用明确不在本轮范围；计数重置 | 0 |
| 修复轮 6 | 2026-08-26 | 第 1 轮：partial usage、聚合溢出、record timeout、V53 门禁同步 | 发现 partial prompt/completion 会造成不完整成本、PostgreSQL `BIGINT SUM` 可能溢出、record timeout 语义可能被误解为数据库绝对不存在，以及 V53 后 `verify-project-docs.sh` 版本断言未纳入切片；已冻结全组保守归一化、numeric/BigDecimal 聚合、statement/query timeout + 迟到写入语义，并把 V53 门禁同步加入实施范围；计数重置 | 0 |
| 修复轮 7 | 2026-08-26 | 第 1 轮：API 聚合响应契约 | 发现仅列出维度而未冻结 DTO 字段、scope 类型、cost unit 分组、breakdown 排序和 SQL 结果溢出语义；已补充 `totals/costs/by*` 字段、稳定排序、`BigDecimal` token/cost、计数校验和统一 `400` 日期/主体输入错误；计数重置 | 0 |
| 无问题轮 1 | 2026-08-26 | 第 2 轮：代码/Spring AI、schema、事务、流式终态、权限、legacy 覆盖和成本边界 | 未发现影响正确性、成本安全、兼容性、隐私、数据一致性或可实施性的实质问题；未修改规划正文 | 1 |
| 无问题轮 2 | 2026-08-26 | 第 2 轮：代码/Spring AI、schema、事务、流式终态、权限、legacy 覆盖和成本边界 | 未发现影响正确性、成本安全、兼容性、隐私、数据一致性或可实施性的实质问题；未修改规划正文 | 2 |
| 无问题轮 3 | 2026-08-26 | 第 3 轮：实施切片、验收矩阵、真实 LLM/全栈边界、发布回滚与 Git/worktree 恢复 | 未发现实质问题；规划达到连续 `3/3` 无修改，可以进入实施 | 3 |

## 4. 验证账本

| 时间 | 阶段 | 命令/范围 | 结果 | 证据 |
|---|---|---|---|---|
| 2026-08-26 | 文档归档 checkpoint | `verify-project-docs.sh`、`git diff --check`、commit/push | PASS | `main@7b6f01ad` |
| 2026-08-26 | 需求探索 | Chat execution、Budget、Spring AI 1.1.8、legacy API、model cost、Metrics、V52 | PASS | 当前代码与历史候选 |
| 2026-08-26 | 规划门禁 | 第 3 轮固定范围检查、`verify-project-docs.sh`、`git diff --check` | PASS；规划连续 `3/3` 无修改 | `main@81d5b131` |
| 2026-08-26 | Slice A 准备 | 核对 Spring AI 1.1.8 `Usage`/`EmptyUsage`、模型候选、预算、V52 迁移与现有测试基座 | PASS；开始分小切片实现，上一组补丁未产生部分修改 | 当前特性 worktree |
| 2026-08-27 | Slice A 实现 | 新增 invocation attribution、purpose/outcome、usage normalizer/cost、模型成本快照和 recorder 接口；定向编译通过 | 发现 `BudgetedChatModel` 流式/非流式标记及异步记录尚未符合规划，继续收口后再进入持久化 | 当前特性 worktree |
| 2026-08-27 | Slice A 核心验证 | `mvn -pl spring-ai-rag-core -am -Dtest=BudgetedChatModelTest,ChatExecutionBudgetTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS；6 项测试通过；开始接入 purpose-aware recorder 与持久化切片 | 当前特性 worktree |
| 2026-08-27 | Slice A 现场复核 | 复核 `BudgetedChatModel`、`ChatExecutionBudget`、`ChatModelRouter`、`ModeAwareChatClientFactory`、`ConversationSummaryService`、Maven 依赖和现有 JDBC/安全模式 | 确认摘要服务源码无重复行；确认流式 provider 直接抛错时仍错误标记 `streaming=false`；确认 recorder、V53、repository、聚合 API 和 WebUI 尚未实现；确认 candidate cost 已具备但工厂尚未传入 wrapper | 当前特性 worktree |
| 2026-08-27 | Slice A 边界验证 | `mvn -pl spring-ai-rag-core -am -Dtest=BudgetedChatModelTest,ChatExecutionBudgetTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS；12 项测试通过；确认同步/流式成功、失败、取消、同步建流失败、exactly-once、fail-open、usage/pricing/cost 快照 | 当前特性 worktree |
| 2026-08-27 | Slice A 完成 checkpoint | 同上；进入 Slice B 前复核工作区与恢复入口 | PASS；Slice A 已冻结，下一步为 V53、JDBC repository、fail-open recorder 与 retention | 当前特性 worktree |
| 2026-08-27 | Slice B 编译与定向测试 | `mvn -pl spring-ai-rag-core -am -DskipTests compile test-compile`；账本定向测试 | PASS；生产/测试编译通过，`BudgetedChatModelTest` 7 项、`ChatExecutionBudgetTest` 5 项通过；未开启 PostgreSQL 开关的集成类 0 tests 不计入验收 | 当前特性 worktree |
| 2026-08-27 | Slice B PostgreSQL 首次尝试 | `mvn ... -Dllm-usage.it.enabled=true` | BLOCKED；Docker 可用但 Testcontainers 拉取 `testcontainers/ryuk:0.11.0` 被 registry TLS 证书代理拒绝；未产生测试断言结果，不能计为通过 | Maven/Testcontainers 日志 |
| 2026-08-27 | Slice B PostgreSQL 本地镜像复验 | `TESTCONTAINERS_RYUK_DISABLED=true TESTCONTAINERS_CHECKS_DISABLE=true mvn ... -Dllm-usage.it.enabled=true -Dtestcontainers.pg.image=pgvector/pgvector:pg16` | FAIL（测试夹具）：V1-V53 迁移成功，4 项中 3 项通过；约束断言前的原生 `Instant` 参数绑定触发 `BadSqlGrammarException`，已改为显式 `Timestamp`，待重跑 | Maven/Testcontainers 日志 |
| 2026-08-27 | Slice B PostgreSQL 修复后复验 | `TESTCONTAINERS_RYUK_DISABLED=true TESTCONTAINERS_CHECKS_DISABLE=true mvn ... -Dllm-usage.it.enabled=true -Dtestcontainers.pg.image=pgvector/pgvector:pg16` | PASS；V1-V53 从空库迁移成功，`LlmUsagePostgresIntegrationTest` 4 项全部通过，Maven reactor 成功 | Maven/Testcontainers 日志 |
| 2026-08-27 | Slice C 入口 | 更新调用链接入边界：主回答、查询转换、查询扩展、摘要和兼容入口均须使用同一逻辑预算，并携带 principal/session/trace/mode、purpose、candidate 成本快照 | 进行中；先接入生产构造路径，再补 recorder fail-open 与端到端归因测试 | 当前特性 worktree |
| 2026-08-27 | Slice C legacy 接入 | `RagChatService` 兼容非流式入口改用 candidate descriptor、共享 `ChatExecutionBudget` 和 purpose-aware `BudgetedChatModel`；legacy LLM query rewrite 通过 advisor context 使用 `QUERY_TRANSFORM` wrapper；兼容流式入口在订阅时包装实际首候选并保留原有不 fallback 行为 | 编译通过；专项归因与 recorder failure 测试待补 | 当前特性 worktree |
| 2026-08-27 | Slice D usage API 入口 | 新增只读 `/api/v1/rag/usage` DTO、UTC 日期窗口/主体范围解析、固定维度聚合 repository 与 Metrics controller 入口；修正 PostgreSQL 按 UTC 日截断并保留旧 controller 构造签名 | 实现已落地；待执行编译和 API/聚合/权限测试 | 当前特性 worktree |
| 2026-08-27 | Slice D usage API 验证补强 | 修正查询服务严格 Mockito 测试的日期上限断言；补充 `RagMetricsControllerWebTest`，覆盖 self、ADMIN 全局/指定主体、非法日期、超长日期窗口、越权 403、problem+json 和 BigDecimal JSON；补正 PostgreSQL 聚合断言并验证单主体 cost unit 隔离 | 查询服务已通过；HTTP 与 PostgreSQL 定向测试待最终复验 | 当前特性 worktree |
| 2026-08-27 | Slice D HTTP 定向复验 | `mvn -pl spring-ai-rag-core -am -Dtest=RagMetricsControllerWebTest,LlmUsageQueryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS；9 项测试通过；修复多构造器 controller 未显式选择注入构造器的问题 | Maven/Surefire |
| 2026-08-27 | Slice C 调用边界复验 | `mvn -pl spring-ai-rag-core -am -Dtest=RagChatServiceTest,ChatExecutionServiceTest,ModeAwareChatClientFactoryTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS；45 项测试通过；覆盖 mode-aware、legacy、fallback、流式首事件边界和查询/摘要用途接入 | Maven/Surefire |
| 2026-08-27 | Slice D 契约与 recorder 复验 | `mvn -pl spring-ai-rag-core -am -Dtest=JdbcLlmUsageRecorderTest,OpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS；38 项测试通过；确认 `/rag/usage` OpenAPI 200/400/403 和同步失败、同步超时、异步失败 fail-open | Maven/Surefire |
| 2026-08-27 | Slice D WebUI 实现 | typed `/usage` client、Metrics 持久用量区域、成本/可用性/模型/用途/模式/日期展示和双语 i18n | 实现已落地；TypeScript、Vitest、build 与 Mock Playwright 待执行 | 当前特性 worktree |
| 2026-08-27 | Slice D WebUI Mock Playwright | `VITE_DEV_PORT=4176 npm run dev -- --host 127.0.0.1 --strictPort`；`BASE_URL=http://127.0.0.1:4176 npx playwright test e2e/pages.spec.ts` | PASS；14 项通过；网络断言确认 `/api/v1/rag/usage` 为 200，JSON scope/totals/cost unit 与 Metrics summary/table/免责声明均可见；前置无服务的 8081 运行仅为 `ERR_CONNECTION_REFUSED`，不计为实现结果 | Playwright/Surefire |
| 2026-08-26 | Slice D 文档收口 | 补齐 architecture、project-context、configuration、REST API、developer reference、testing guide、release checklist、TODO 的中英文长青事实，并修正架构章节编号；新增 usage ledger 一键门禁入口 | PASS；文档表述已区分 V53 当前事实、可观测性账本与 provider billing/hard limit；真实 LLM 尚未宣称通过 | 当前特性 worktree |
| 2026-08-26 | Slice D 专项门禁启动 | `./scripts/verify-llm-usage-ledger.sh` | 进行中；将依次执行聚焦后端、V1-V53 PostgreSQL、Maven、WebUI、Mock Playwright、禁锁、文档和空白检查 | 当前特性 worktree |
| 2026-08-26 | Slice D 专项门禁预检 | 首次执行因新脚本缺少 executable mode 返回 `126`；已补齐 `scripts/verify-llm-usage-ledger.sh` 可执行权限 | 修复后重跑；尚未计入通过 | 当前特性 worktree |
| 2026-08-26 | Slice D 专项门禁 PostgreSQL 预检 | 脚本自行创建一次性 PostgreSQL 后未传 `-Dllm-usage.it.clean-confirm=YES`，集成测试按保护规则拒绝执行；已在脚本中区分内部一次性库默认确认与外部 URL 显式确认 | 修复后重跑；该预检不计入通过 | 当前特性 worktree |
| 2026-08-27 | Slice D 专项门禁全量 Maven | 全量测试发现 `ChatMemoryMultiTurnTest.stream_passesConversationId` 仍按旧的 eager streaming 假设断言；实现使用 `Flux.defer` 将预算和 provider 调用绑定到订阅时刻 | 已将测试改为订阅后断言 advisor 参数；受影响测试和专项门禁需重跑 | Maven/Surefire |
| 2026-08-27 | Slice E 真实服务启动预检 | 使用主工作区 `.env`、隔离数据库 `pgvector/pg16`（随机端口）和 `openai` 兼容配置启动 `28081`；健康检查与 V1-V53 迁移通过，但启动脚本退出后后台 Java 进程未保持存活，首轮 smoke 无法连接 | 不计为真实验收结果；改用前台会话托管服务后重试，保留现有 `18081` 服务不变 | 隔离服务日志 `/tmp/spring-ai-rag-llm-usage-real-20260827.log` |
| 2026-08-27 | Slice E PLAIN 幂等验收前置预检 | 首次 PLAIN 请求未携带隔离服务 root API key，返回 `401`；未产生 provider 调用，也未计入验收通过 | 补齐进程内认证 header 后重跑同一组首次/重放/冲突/status 请求 | 隔离服务 HTTP 响应 |
| 2026-08-27 | Slice E PLAIN 幂等验收夹具预检 | 补齐认证后，测试生成的 session ID 超过接口 36 字符限制，返回 `400 VALIDATION_FAILED`；未产生 provider 调用 | 使用长度合规的隔离 session ID 重跑，继续核对真实 provider 单次调用和 turn 重放 | 隔离服务 HTTP 响应 |
| 2026-08-27 | Slice E 真实 keyed JSON/SSE 生命周期 | 隔离服务 `28081`、数据库 `rag-llm-usage-e2e-20260827`；真实模型由当前多模型路由解析为 `minimax/MiniMax-M2.7` | PASS；JSON 首次/重放均 `200` 且 turn/answer 一致，复用同 key 的不同请求为 `409 IDEMPOTENCY_KEY_REUSED`；SSE 首次/重放均 `200`，`done`、内容和 turn 一致；JSON 与 SSE 各只增加 1 次 provider call（计数 `3→4→5`）；两个 turn 均 `SUCCEEDED` 且 `replayAvailable=true` | `.verification/real-chat/ledger-real-20260827-084549-44268/`；只读 PostgreSQL 查询确认两个新 `CHAT` 事件各 `call_ordinal=1`、`usage_available=true`，模型、价格、token usage 与响应一致 |
| 2026-08-27 | Slice E 真实 provider 预检修复 | `real-llm-e2e-smoke.sh` 原先无视实际 `LLM_PROVIDER`、优先探测 MiniMax，可能把其他 provider 的不可用状态误判为可用；已改为严格按 `REAL_LLM_CHAT_PROVIDER` / `LLM_PROVIDER` / `APP_LLM_PROVIDER` 选择并仅发送对应协议请求 | PASS（脚本语法与 diff 检查）；后续真实验收必须使用该版本脚本，不能沿用旧预检结果 | 当前特性 worktree |
| 2026-08-27 | Slice E OpenAI-compatible 真实 provider 复核 | 隔离服务 `28083` 按 `LLM_PROVIDER=openai` 启动，健康检查、Flyway V1-V53 和真实 Embedding 已通过；配置的上游 Chat 预检返回 `503 no_available_account`，应用 Chat 最终返回 `504 CHAT_TIMEOUT` | 外部 provider 不可用，不能计为 OpenAI Chat 通过；保留失败证据并继续使用实际可用 provider 完成应用生命周期验收 | `/tmp/spring-ai-rag-llm-usage-openai-20260827.log`；隔离数据库 `rag-llm-usage-openai-20260827` |
| 2026-08-27 | Slice E PLAIN 真实生命周期 | 隔离服务 `28081`，显式 `mode=PLAIN`、独立 session 和幂等 key；响应由真实 `minimax/MiniMax-M2.7` 生成 | PASS；HTTP `200`、终态 `end_turn`、answer 非空、无检索 sources、响应 `resolvedModel` 与服务默认模型一致 | `.verification/real-chat/ledger-plain-20260827-092507-74452/`；证据只保留状态和非敏感摘要 |
| 2026-08-27 | Slice E AGENT 真实能力边界 | 使用隔离知识库、真实模型和 `mode=AGENT` 请求，要求通过检索工具返回一次性验证码 | 明确阻断：HTTP `400 MODEL_CAPABILITY_UNSUPPORTED`，当前实际模型 `minimax/MiniMax-M2.7` 未声明 tool-calling；不把该请求记为 AGENT 通过，已有 Mock/定向测试仍覆盖 AGENT 控制流 | 真实 HTTP 响应（未保存原始 prompt/answer）；后续必须使用声明 tool-calling 的真实模型才可补做 AGENT provider 验收 |
| 2026-08-27 | Slice E OpenAI provider failure 生命周期 | 独立服务 `28083`、独立 PostgreSQL、`LLM_PROVIDER=openai`，真实请求经过应用 retry 后收敛 | PASS（失败收敛语义）；HTTP `504 CHAT_TIMEOUT`，数据库只读查询确认新增 `QUERY_EXPAND/FAILED` 事件；该 provider 上游 `503 no_available_account`，不宣称 Chat 成功 | `.verification/real-chat/ledger-provider-failure-20260827-092905-77671/`；`rag-llm-usage-openai-20260827` |
| 2026-08-27 | Slice E 摘要压缩服务启动预检 | 独立 PostgreSQL `rag-llm-usage-summary-20260827`、端口 `28085`，Flyway V1-V53 与真实 MiniMax 初始化通过；后台启动方式在本地命令会话结束后未保持监听，首次请求为连接失败 | 不计入验收；改用前台托管 Java 进程重跑，保留同一隔离数据库和压缩配置 | `/tmp/spring-ai-rag-llm-usage-summary-20260827.log` |
| 2026-08-27 | Slice E 摘要压缩真实生命周期 | 独立服务 `28085`、独立 PostgreSQL、真实 MiniMax，启用低阈值 compaction；同一 session 连续 5 轮真实 Chat | PASS；第 4 轮触发并持久化版本 1 摘要，数据库确认 `CHAT/SUCCEEDED=5`、`SUMMARY/SUCCEEDED=2`；第 5 轮输出超限按设计有界降级，不破坏主 Chat 结果 | `.verification/real-chat/ledger-summary-20260827-093721-83249/summary.jsonl`；只读 PostgreSQL 查询确认摘要游标、模型、token 与终态 |
| 2026-08-27 | 最终专项硬门槛 | `LLM_USAGE_LEDGER_VERIFY_RUN_ID=ledger-final-20260827-094227 ./scripts/verify-llm-usage-ledger.sh` | PASS；12/12：定向后端、PostgreSQL V1-V53、`mvn clean compile test-compile`、Maven 全量 3088 项、WebUI typecheck/Vitest 222/build/alignment、Mock Playwright 14 项、禁锁、文档和 diff | `.verification/llm-usage-ledger/ledger-final-20260827-094227/summary.md` |
| 2026-08-27 | 合并后验证基线 | 先合并 `origin/main@19149aad` 到特性分支，再运行 `LLM_USAGE_LEDGER_VERIFY_RUN_ID=ledger-post-merge-20260827-131543 ./scripts/verify-llm-usage-ledger.sh` | PASS；12/12：合并后定向后端、PostgreSQL V1-V53、`mvn clean compile test-compile`、Maven 全量 3088 项、WebUI typecheck/Vitest 222/build/alignment、Mock Playwright 14 项、禁锁、文档和 diff；未发现合并回归 | `.verification/llm-usage-ledger/ledger-post-merge-20260827-131543/summary.md`；合并提交 `e49110e0` |
| 2026-08-27 | Git 交付 | 推送 `feat/llm-usage-ledger-20260826`，在 `main` worktree 快进合并并推送 `main` | PASS；`main`、`origin/main`、特性分支和远端特性分支均指向 `89b73ad5`；待删除旧特性 worktree | Git refs |

## 5. 当前实现收口

1. `BudgetedChatModel` 的同步、流式、取消和同步建流异常都记录单一终态事件；
2. Chat、查询转换/扩展、摘要、fallback 与 legacy 入口共享有界预算和用途归因；
3. V53 账本只保存有界维度、token、价格快照、成本估算和终态，不保存 prompt、answer、
   工具参数/结果或异常正文；
4. recorder fail-open；账本故障不能改变 provider 结果或触发 provider retry；
5. usage API 按 principal 隔离，WebUI 使用 typed usage 区域；旧 metrics 保持兼容；
6. 真实验收已证明 MiniMax 的 PLAIN、KNOWLEDGE、SSE、幂等、摘要压缩和失败收敛路径；
   AGENT 仍需声明 tool-calling 的真实模型，OpenAI-compatible 结果受上游可用性限制。

## 6. 恢复入口

usage ledger 实现已完成，合并后专项门禁已通过并已推送到 `main`；当前只剩旧特性
worktree 清理。不得把
本地真实 provider 失败或模型能力限制改写成通过结论。
