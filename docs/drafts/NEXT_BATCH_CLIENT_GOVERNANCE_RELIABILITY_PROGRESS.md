# 下一批实施进度：Client 契约治理、数据生命周期与可靠性边界

## 当前状态

- 分支：`main`
- 基线：`9bdcffef`
- 工作区：实施完成，尚未执行最终 Git commit/push；不得使用 `git reset --hard`、`git stash` 或 `git clean`。
- 当前阶段：代码、文档、自动化门禁、真实 PostgreSQL/LLM 验收和实施 `3/3` 检查已完成；待执行最终 release gate、Git commit/push，并确认工作区干净。
- 规划入口：[NEXT_BATCH_CLIENT_GOVERNANCE_RELIABILITY_PLAN.md](NEXT_BATCH_CLIENT_GOVERNANCE_RELIABILITY_PLAN.md)

## 冻结范围

- Client API：扩展现有 manifest、OpenAPI checker、黑盒 gate、可靠性 header/field 和能力发现。
- 数据治理：新增 policy/audit 只读入口、Audit/Client Error/Evaluation 有界 cleanup 和写入脱敏；
  保留已有 Chat export/delete 与 Collection purge，不重写已有安全删除路径。
- 可靠性：新增统一 `FailureClass` 和 capability projection；不重写已有 timeout/retry/
  circuit-breaker/fallback。
- 迁移：默认无 schema 变化；最高版本保持 V63。若实施需要 schema，先修规划并重置规划检查。
- 兼容：既有 endpoint、字段、错误 envelope、OpenAI body、SSE event shape 保持；新增内容只
  通过可选字段、header、capability 或新 endpoint 提供。

## 规划检查账本

### 规划修订前第 1 轮：发现问题，计数重置为 `0`

- 时间：2026-10-15
- 范围：需求闭环、范围冻结、默认值、非目标和中断恢复。
- 发现：初稿把 `traceId` 写成所有错误 body 的非空必备字段，并把所有 `503` 暗示为有
  `Retry-After`；这与当前 direct filter/error 构造路径不完全相符，会导致兼容 gate 误报或
  虚构 Client 重试语义。
- 处理：将 body `traceId` 明确为可空、把 HTTP `X-Trace-Id` 作为强保证；将 `Retry-After`
  收敛为实际存在的 `429` 和 `CHAT_TURN_IN_PROGRESS` 条件信号，并明确不为所有 `503` 承诺
  固定退避。
- 结果：已修正文档，规划计数归零；必须重新连续完成三轮无修改检查。

### 规划修订前第 2 轮：发现问题，计数重置为 `0`

- 时间：2026-10-15
- 范围：代码、API、schema、权限、脱敏、并发、无锁约束和兼容可行性。
- 发现：仅在 GlobalExceptionHandler/RateLimitFilter/Auth filter 中补错误头，无法覆盖
  直接返回 `ResponseEntity<ErrorResponse>` 的管理 Controller；同时 policy 与 audit 的
  route access 未在设计中冻结，可能造成普通 Client 读不到 policy 或管理权限过宽。
- 处理：增加统一 `FailureClassResponseHeaderFilter` 覆盖所有受保护的非 2xx 响应；明确
  policy 使用 `ApiRoutePolicy.READ`，audit 使用 `ApiRoutePolicy.MANAGEMENT`，并保留
  OpenAI body 不增字段的边界。
- 结果：已修正文档，规划计数归零；必须重新连续完成三轮无修改检查。

### 规划修订前第 3 轮：发现问题，计数重置为 `0`

- 时间：2026-10-15
- 范围：实施顺序、验收矩阵、发布门禁、回滚、恢复、真实依赖和成本风险。
- 发现：HTTP `200` 后的 SSE 错误无法保证追加响应头；认证/限流 filter 直接写 body 时也
  可能已经提交 Servlet response。初稿若宣称 header 覆盖所有错误，会产生不可实施的
  流式契约；审计日期范围也缺少明确格式/上限。
- 处理：将 header 保证限定为尚未提交的非流式响应；要求 direct filter 在写 body 前调用
  同一 helper；SSE error event 增加 `failureClass`，并冻结 audit filter 为 ISO-8601、最大
  31 天。
- 结果：已修正文档，规划计数归零；必须重新连续完成三轮无修改检查。

### 修订后第 1 轮：通过

- 时间：2026-10-15
- 范围：需求闭环、三条推荐方向、默认启用、非目标、Client/API 兼容、WebUI/真实 LLM
  验收边界和中断恢复。
- 发现：无实质问题；检查脚本中的两次反引号/保留变量问题均为检查命令自身问题，未修改仓库。
- 处理：无需处理。
- 结果：规划计数 `1/3`。

### 修订后第 2 轮：通过

- 时间：2026-10-15
- 范围：实际 Controller/Filter/DTO/Service/Repository、V1-V63 表、route policy、现有
  cleanup、敏感信息和悲观锁禁用约束。
- 发现：无实质问题；确认本批可复用已有表和 cleanup 边界，不需要 schema 迁移。
- 处理：无需处理。
- 结果：规划计数 `2/3`。

### 修订后第 3 轮：通过

- 时间：2026-10-15
- 范围：文件级实施顺序、验收矩阵、release gate、真实启动/LLM、回滚、失败恢复和 Git
  交付边界。
- 发现：无实质问题；检查脚本的反引号截断已改用固定字符串重跑，未修改仓库。
- 处理：无需处理。
- 结果：规划连续 `3/3` 通过，可以进入代码实施。

## 实施检查账本

### 初始第 1 轮：通过

- 时间：2026-10-16
- 范围：外部 API 契约、OpenAPI、权限、failure-class header/body/SSE、OpenAI body 和敏感信息边界。
- 发现：无实质问题；静态契约门禁和定向测试通过，未修改实现或文档。
- 处理：无需处理。
- 结果：实施计数 `1/3`。

### 初始第 2 轮：通过

- 时间：2026-10-16
- 范围：全量 Maven、迁移版本、无锁规则、真实 PostgreSQL/LLM、`scripts/dev.sh` 和 release gate。
- 发现：无实质问题；全量测试、真实 LLM、启动栈和发布门禁均通过，未修改实现或文档。
- 处理：无需处理。
- 结果：实施计数 `2/3`。

### 初始第 3 轮：发现问题，计数重置为 `0`

- 时间：2026-10-16 02:21:42 +0800
- 范围：双语测试文档、验收证据口径、发布 run id、WebUI 测试计数和可恢复进度账本。
- 发现：新增的 `API_CONTRACT_RAG_MODE` 只存在于脚本，正式中英文测试文档未说明；账本仍记录旧的全量测试总数 `8,547`、旧 run id `final-20261016-core`，并把设计系统测试 `525` 误写成全部 WebUI Vitest 口径，无法准确追溯本次最终验收。
- 处理：在中英文 `testing-guide` 增加该开关的用途、默认行为和“不能替代 KNOWLEDGE 证据验收”的边界；待本轮检查重新通过后，账本改用当前实际的模块计数、发布 run id 和 WebUI 两类测试计数。
- 结果：文档已修正；实施计数归零，重新开始三轮检查。

### 重新检查第 2 轮：发现问题，计数重置为 `0`

- 时间：2026-10-16
- 范围：全量测试计数的权威来源和进度账本可追溯性。
- 发现：重新执行干净的 `mvn clean test -q` 后确认，模块权威汇总是 API `562`、Documents `89`、Core `7,852`、Starter `44`，合计 `8,547`；此前修订把发布脚本控制台中另一组显示值误当成 clean test 的权威计数，可能造成验收证据失真。
- 处理：将账本恢复为 clean test 的实际汇总；发布门禁只记录自身的 `15 passed, 0 failed, 10 skipped`，不再混用其内部模块显示值。
- 结果：已修正文档；实施计数再次归零，重新从第 1 轮开始。

### 最终重新检查第 1 轮：通过

- 时间：2026-10-16
- 范围：双语正式文档、API 契约与错误分类、数据治理权限/脱敏、测试证据和进度账本。
- 发现：无实质问题；确认 `API_CONTRACT_RAG_MODE` 已在中英文测试文档说明，账本使用 clean test 的权威计数，且旧 endpoint、字段、OpenAI body 和 SSE 兼容边界未被改写。
- 处理：无需处理。
- 结果：实施检查计数 `1/3`。

### 最终重新检查第 2 轮：通过

- 时间：2026-10-16
- 范围：全量 Maven 回归、定向测试、Flyway 版本、无锁门禁、真实 PostgreSQL/LLM、`scripts/dev.sh` 和 WebUI 辅助验收。
- 发现：无实质问题；`mvn clean test -q` 为 API `562`、Documents `89`、Core `7,852`、Starter `44`，合计 `8,547`，失败/错误均为 `0`；真实依赖与开发栈均通过。
- 处理：无需处理。
- 结果：实施检查计数 `2/3`。

### 最终重新检查第 3 轮：通过

- 时间：2026-10-16
- 范围：发布门禁、Client 黑盒契约、错误响应兼容性、敏感信息边界、文档同步和 Git 交付前状态。
- 发现：无实质问题；发布门禁、Client contract governance、项目文档门禁和差异检查均无新增问题，当前仅剩按用户要求执行 commit/push。
- 处理：无需处理。
- 结果：实施检查连续 `3/3` 通过；可以进入 Git 交付。

## 实施进度

| 切片 | 状态 | 备注 |
|---|---|---|
| 规划与规划 `3/3` | 已完成 | 修订后连续三轮无修改通过 |
| A：Client Contract Pack 与 FailureClass | 已完成 | 增量 `failureClass`、`X-RAG-Failure-Class`、SSE error 分类、OpenAPI/manifest/黑盒门禁；保留旧错误字段和 OpenAI body |
| B：数据治理配置、清理与脱敏 | 已完成 | 默认开启有界清理；审计、Client Error、Evaluation 复用已有表；写入和输出均脱敏 |
| C：policy/audit HTTP 入口 | 已完成 | 新增 policy/audit 只读入口；权限、分页、时间范围和 `details` 隔离已覆盖 |
| D：文档、门禁、集成/回归/验收 | 已完成 | 中英文正式文档、门禁、真实启动、真实 PostgreSQL/LLM、Client 黑盒均已执行 |
| 实现 `3/3` | 已完成 | 最终重新检查连续三轮无修改通过 |
| Git commit/push/工作区清理 | 待开始 | 用户要求完成后执行 |

## 实施产物

- API 契约：`FailureClass`、`ErrorResponse.failureClass`、`X-RAG-Failure-Class`、统一响应体 advice、OpenAI 兼容错误 header、能力发现字段。
- 数据治理：`RagDataGovernanceProperties`、启动校验、`DataGovernanceCleanupService`、有界主键删除、`/api/v1/rag/data-governance/policy`、`/api/v1/rag/audit-logs`。
- Client 门禁：`testdata/api-contract/v1-compatibility.json`、`scripts/lib/api-contract-check.mjs`、`scripts/verify-api-compatibility.sh`、`scripts/verify-client-contract-governance.sh`。
- 数据库：没有新增 Flyway migration、表或列；真实启动日志确认 PostgreSQL schema 仍为 `V63`，清理和审计查询复用已有表。
- 文档：中英文 `rest-api`、`configuration`、`architecture`、`testing-guide`、`developer-reference`、`business-client-integration` 已同步。

## 测试与验收证据

- `mvn clean test -q`：全模块回归通过；本次 clean test 的模块汇总为 API `562`、Documents `89`、Core `7,852`、Starter `44`，合计 `8,547` tests，`0` failures，`0` errors，`201` skipped。
- 定向测试：OpenAPI schema、FailureClass response body/header、Data Governance Controller/WebMvc/cleanup、Audit/Client Error 脱敏、OpenAI compatibility handler 均通过。
- 静态门禁：`verify-project-docs.sh` 的 `16` 项全部通过；`verify-no-pessimistic-locks.sh` 通过；shell syntax、Node syntax、API contract helper self-test、client contract governance 全部通过。
- 发布门禁：`VERIFY_RUN_ID=final-20261016-check2 ./scripts/verify-release.sh --skip-npm-ci --skip-playwright --skip-helm --skip-docker` 结果为 `15 passed, 0 failed, 10 skipped`；跳过项均由命令行显式关闭。
- 最终发布门禁：`VERIFY_RUN_ID=final-20261016-final ./scripts/verify-release.sh --skip-npm-ci --skip-playwright --skip-helm --skip-docker` 结果为 `15 passed, 0 failed, 10 skipped`；Maven 全量回归、WebUI lint/Vitest/build、嵌入式 WebUI bundle 均在本次 run 内再次通过。
- `scripts/dev.sh`：使用 `BACKEND_PORT=18082`、`FRONTEND_PORT=15173`、`postgresql` profile 真实启动；backend health `UP`、frontend HTTP `200`、status 显示两个受管进程 running，随后已正常 stop。
- Client API 黑盒：最终编译实例通过 OpenAPI/static contract、Chat/Search 参数错误、retrieval trace、policy、audit、`X-Trace-Id`、`X-RAG-Failure-Class` 和敏感字段边界；真实 PostgreSQL audit 查询返回 `200`。
- 真实 LLM：`real-llm-e2e-smoke.sh` 最终实例 `PASS=11 FAIL=0`，覆盖 embedding、Search、KNOWLEDGE JSON、引用、SSE、证据充分性；当前 provider 不具备 AGENT capability，因此 AGENT 子项按能力契约安全跳过。
- 条件性 OpenAI 兼容入口：默认关闭行为保持；在隔离端口启用并允许请求级 `PLAIN` 覆盖后，`verify-api-compatibility.sh` 通过 `live Search/OpenAI smoke: 200`；`/v1` 错误 handler 的 failure-class header 也有单元/WebMvc 覆盖。默认 KNOWLEDGE 探针命中既有 `COLLECTION_PURGE_CONFLICT` 安全保护，未将其误判为 transport 契约成功。
- WebUI 辅助验收：lint/typecheck、alignment、design-system、可访问性/错误/国际化等规则、设计系统 Vitest `525` tests、完整 Vitest `1,030` tests、生产 build 均通过；仅有既存 Fast Refresh warning，无 error。

## 恢复入口

中断后先阅读本进度文档和规划文档，再检查 `git status`、当前最高 Flyway 版本、规划计数和
最后一个已完成切片。不得使用 `git reset --hard`、`git stash` 或 `git clean`。任何实质设计
问题先修规划并把对应检查计数重置为 `0`，不能直接边写代码边临时决定核心契约。
