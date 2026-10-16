# LLM 预算与数据治理实施进度

## 当前状态

- 分支：`main`
- 基线：`001300f1 feat: add client consumer contract kit`
- 当前阶段：代码实施、集成验证和回归验收已完成，等待最终 Git 交付。
- 规划入口：[LLM_BUDGET_AND_DATA_GOVERNANCE_PLAN.md](LLM_BUDGET_AND_DATA_GOVERNANCE_PLAN.md)
- 恢复限制：不得使用 `git reset --hard`、`git stash` 或 `git clean`。

## 冻结范围

- 本批只实施 LLM durable budget reservation/hard limit 与统一 retention/erasure governance。
- 外部 Client API 是主要验收边界；WebUI 不是完成依据。
- 既有 endpoint、HTTP method、旧请求字段、旧响应字段、SSE event 和 OpenAI 标准 body 不删除、
  不改名、不改类型；新能力以新增错误、可选字段、header、capability 和新 endpoint 增量交付。
- 需要 schema 的地方新增 Flyway migration，自动回填已有 managed principal，不要求运维手工修库。
- 无真实 LLM 的普通 gate 不调用模型；真实 provider 只在显式验收阶段有界调用。

## 实施结果

- 新增 `V64__llm_budget_and_data_governance.sql`，自动创建预算 policy/window/reservation 和
  data-erasure operation 表，并为 `rag_client_error` 增加 owner/trace 字段；不要求人工改库。
- `BudgetedChatModel` 覆盖同步、流式和取消路径，在 provider 调用前做 durable reservation，
  调用后 settle/release；按 canonical principal、logical execution 和 call ordinal 幂等，
  支持 TTL 恢复、终态账本保留和并发 CAS 硬限制。
- 预算默认启用：日 `5,000,000` tokens、月 `50,000,000` tokens、单次 `200,000` tokens、
  reservation TTL `900s`、终态账本保留 `30d`。预算不足返回 `429 LLM_BUDGET_EXCEEDED`，
  预算存储不可用返回 `503 LLM_BUDGET_SERVICE_UNAVAILABLE` 并带 `Retry-After`；旧请求体、
  响应字段、SSE event 和 OpenAI 标准 body 保持兼容。
- 新增 owner-scoped data erasure preview/status/apply API；confirmation token 只存 hash，
  preview/fingerprint 有效期受限，操作支持 lease、CAS、分步批量删除、部分失败恢复和幂等重试，
  不删除 audit log，也不绕过 Collection purge fence。
- 新增 `DataGovernanceRetentionCoordinator`，统一调度 audit、client error、evaluation、
  chat history、retrieval diagnostics、LLM usage、integration observability 和 budget ledger；
  旧清理入口保留为兼容/测试调用，不再形成重复默认调度。
- 新能力默认启用；正式生产装配不再允许静默绕过 durable budget，只有测试兼容路径可以注入替身。

## 规划检查账本

规划最终 `3/3` 已通过。第 1、2、3 轮均发现并修正规划实质缺口，随后连续三轮只读复核无修改：补充了 canonical principal 命名空间、
非 managed 身份的默认 policy 物化、具体预算默认值、client-error 历史无主行边界、重复
retention scheduler 收敛方式、`Retry-After` 语义、生产 raw-model fallback 禁止规则、现有
retention 默认值、erasure DTO/状态/确认/HTTP 语义、全部仓库 gate 命令、默认 policy
配置 fingerprint/CAS 自动收敛以及真实 E2E server/smoke 的正确启动顺序。规划检查计数为
`3/3`，现在进入代码实施。

## 实施进度

| 切片 | 状态 | 备注 |
|---|---|---|
| 规划与规划 `3/3` | 已完成 | 三轮连续无修改复核通过 |
| A：API 契约与 V64 migration | 已完成 | 新增预算/擦除表；旧 API 只做增量扩展 |
| B：Budget Reservation + Hard Limit | 已完成 | CAS、settle/release/expire、同步/流式/取消全路径接入 |
| C：统一 retention 与 erasure API | 已完成 | coordinator、重复 scheduler 收敛、preview/status/apply |
| D：集成、回归、真实 Client 验收 | 已完成 | 外部 API、PostgreSQL、OpenAPI、发布门禁已验证；真实 LLM 仅在凭据可用时执行 |
| 实施检查 `3/3` | 已完成 | 最终文档状态更新后再次连续三轮只读交叉检查通过 |
| Git commit/push/工作区清理 | 进行中 | 检查已完成，当前执行最终 Git 交付 |

## 已知风险与默认处理

1. provider 未返回 usage：以保守 reservation 结算，不把未知量当作零。
2. budget DB 在 provider 调用前不可用：返回 503，不调用 provider；结算后短暂失败由 TTL 恢复。
3. 流式取消：`doFinally` 只允许一次 settlement，重复信号不可重复扣减。
4. erasure 部分类别失败：保留 operation 状态和已完成计数，重试只推进未完成步骤，不回滚已完成删除。
5. audit log：不在普通 erasure 中删除，只按 retention 清理，并记录低敏 erasure 审计事件。
6. 旧 scheduler：central coordinator 默认接管；旧 job 保留兼容入口但不得重复执行。

## 验收记录

本次实施已完成以下证据链；最后一次普通全量 Maven 测试在文档更新前完成，之后还需按下方
收尾步骤重新跑聚合门禁，避免 gated 测试覆盖 Surefire 报告：

- Maven 全量：API `562`、Documents `89`、Core `7870`（跳过 `204`）、Starter `45`，
  `0` failures、`0` errors，构建成功。
- 本批 PostgreSQL/Flyway 集成：`3/3` 通过；完整 gated PostgreSQL inventory：`26` 套件、
  `215` 个测试、`0` 失败；日志确认全新数据库自动应用到 Flyway `v64`。
- `verify-project-tests.sh`：`59` 项通过；`verify-project-docs.sh`：`16` 项通过；
  `verify-no-pessimistic-locks.sh`：通过。
- `verify-release.sh`：`20` 项通过、`0` 失败、`6` 项条件跳过；OpenAPI/Client contract
  相关测试通过。
- WebUI 辅助证据：design-system `525/525`、Vitest `1030/1030`、Playwright `93/93`、
  production build 通过；这不替代外部 Client API 验收。
- Helm lint/template、Docker image build 通过。
- `scripts/dev.sh` 真实启停：后端 `18082`、前端 `15173` 均启动成功，`--status` 正确识别，
  `--stop` 后端口均释放。
- 真实 LLM 黑盒：隔离端口 `19084`，真实 embedding、collection/document、向量搜索、JSON
  chat、SSE chat 均通过，最终 `PASS=11 FAIL=0`；Agent 分支因当前 provider 未声明 AGENT
  capability 按设计安全跳过。真实服务已在验收后停止。

已完成上述测试、验收和实施检查 `3/3`；当前正在执行最终 `git diff --check`、提交、push，
并将在推送后确认工作区干净。
