# 模型调用级持久用量账本实施进度

> **状态**：实施进行中，Slice A 启动
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
- [x] 从最新 origin/main 创建专用 feature worktree（准备执行）。
- [ ] Slice A：配置、领域对象、V53、normalizer/cost 单测。
- [ ] Slice B：repository、recorder、retention、PostgreSQL 集成。
- [ ] Slice C：mode-aware 与 legacy 全调用边界。
- [ ] Slice D：usage API、WebUI、双语长青文档。
- [ ] Slice E：专项脚本、真实全栈和真实 LLM。
- [ ] 基本硬门槛。
- [ ] 实现连续三轮无实质修改审查。
- [ ] merge 最新 origin/main 后完整复验。
- [ ] feature 合回 main、push、状态干净、worktree 清理。

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

## 5. 恢复入口

规划三轮无修改后，已提交并推送规划 checkpoint；接下来从最新 `origin/main` 建立
`feat/llm-usage-ledger-20260826` 专用 worktree。实施中每次关键进展先更新本文件，再执行
下一步。Mock 和 PostgreSQL 通过后才使用 `.env` 做真实 LLM 验证；provider 不可用时保留
明确失败证据，不把 Mock 结果写成真实通过。
