# Client Consumer Contract Kit 实施进度

## 当前状态

- 分支：`main`
- 基线：`6340f5d2 feat: strengthen client governance and reliability contracts`
- 工作区：已完成 consumer fixture、validator、shell gate、双语验收入口和发布门禁接入；未修改业务 endpoint、请求/响应兼容语义或数据库 schema。
- 当前阶段：规划、实施、集成回归、真实 HTTP 黑盒验收、实施检查和草稿归档均已完成；本次交付正在执行最后的 Git commit/push 与干净工作区确认。
- 规划入口：[CLIENT_CONSUMER_CONTRACT_KIT_PLAN.md](2026-10-16_CLIENT_CONSUMER_CONTRACT_KIT_PLAN.md)
- 恢复限制：不得使用 `git reset --hard`、`git stash` 或 `git clean`。

## 冻结范围

- 只建设外部 Client 可消费的版本化契约包、fixture、validator、最小示例和发布门禁。
- 保留现有 producer manifest、OpenAPI checker、API compatibility gate 和 SSE 协议。
- 不修改业务 endpoint、HTTP method、旧请求字段、旧响应字段、SSE event 名称、OpenAI body 或数据库 schema。
- 离线检查默认启用；live 检查必须显式配置服务地址、API Key 和 query。
- 不在普通 gate 中调用真实 LLM；PLAIN live probe 只验证 transport，不替代 KNOWLEDGE 证据验收。

## 规划检查账本

### 第 1 轮：通过

- 时间：2026-10-16
- 范围：需求闭环、自包含上下文、默认行为、非目标、API 兼容和成本安全边界。
- 发现：无实质问题；确认包含 OpenAPI surface snapshot、四类 transport、旧 Client fixture、
  deprecated registry、错误/Trace/证据/重试映射和离线默认 gate。
- 处理：无需处理。
- 结果：规划计数 `1/3`。

### 第 2 轮：通过

- 时间：2026-10-16
- 范围：现有 DTO/Controller、SSE 协议、OpenAI body、API manifest、deprecated 文档和 release wiring。
- 发现：无实质问题；规划没有改变现有 endpoint、旧字段、SSE event 或 OpenAI body 语义，PLAIN
  只用于 transport probe 的边界与现有文档一致。
- 处理：无需处理。
- 结果：规划计数 `2/3`。

### 初始第 3 轮：通过（后续发现问题）

- 时间：2026-10-16
- 范围：文件级实施顺序、Node/Bash 运行条件、离线/live gate、条件 skip、secret 安全、测试矩阵、
  回滚和中断恢复。
- 发现：无实质问题；确认无 schema migration、默认 gate 不调用模型，OpenAI 未启用时可明确 skip，
  且实现可接入现有 release gate。
- 处理：无需处理。
- 结果：当时连续 `3/3` 通过；后续复核发现失败映射问题，因此该计数不再作为实施准入依据。

### 规划复核后第 1 轮：发现问题，计数重置为 `0`

- 时间：2026-10-16
- 范围：失败/重试映射与当前 `FailureClass`、`ErrorCode`、`EvidenceAssessment.status` 的逐项对拍。
- 发现：原规划表把 `TRANSIENT_PROVIDER`、`UPSTREAM_TIMEOUT`、`EVIDENCE_INSUFFICIENT` 写成
  Client 稳定信号，但这些名称不是当前对外稳定枚举；直接照抄会让 consumer kit 伪造不存在的契约。
- 处理：改用 `RATE_LIMITED`、`CONFLICT`、`DEPENDENCY_UNAVAILABLE`、`TIMEOUT`、真实错误码和
  `EvidenceAssessment` 的实际状态集合；明确映射层不得扩展服务端枚举。
- 结果：规划已修正，计数归零；必须重新连续完成三轮无修改检查。

### 修正后第 1 轮：通过

- 时间：2026-10-16
- 范围：修正后的失败/重试映射、`FailureClass`、`EvidenceAssessment.status`、OpenAPI snapshot 和
  fixture 语义逐项对拍。
- 发现：无实质问题；所有映射均落在当前代码已有稳定信号，且旧字段、新增字段和 OpenAI 私有字段
  隔离规则边界清晰。
- 处理：无需处理。
- 结果：修正后计数 `1/3`。

### 修正后第 2 轮：通过

- 时间：2026-10-16
- 范围：文件级实施顺序、Node/Bash 运行条件、离线默认 gate、显式 live gate、OpenAI 条件 skip、
  secret/成本边界和无数据库迁移边界。
- 发现：无实质问题；方案可以接入现有 release gate，不会默认调用模型或要求运维迁移数据。
- 处理：无需处理。
- 结果：修正后计数 `2/3`。

### 修正后第 3 轮：通过

- 时间：2026-10-16
- 范围：首次看到规划的开发者可执行性、现有 REST/SSE/OpenAI 文档一致性、deprecated registry、
  验收矩阵、回滚与中断恢复路径。
- 发现：无实质问题；规划自包含、路径和命令可落地，明确区分离线通过、transport live 通过和
  KNOWLEDGE 证据验收，不会把 WebUI 或条件 skip 误判为 Client contract 完成。
- 处理：无需处理。
- 结果：修正后连续 `3/3` 通过，允许进入实施。

## 实施检查账本

### 第 1 轮：通过

- 时间：2026-10-16
- 范围：版本化 manifest/fixture、Native JSON/SSE、OpenAI JSON/SSE、旧 Client 请求、错误/Trace/Retry/证据映射、deprecated registry、validator 的 fail-closed 自测。
- 发现：无影响正确性、兼容性或安全边界的实质问题。
- 处理：无需处理。
- 结果：实施检查计数 `1/3`。

### 第 2 轮：通过

- 时间：2026-10-16
- 范围：外部 API 兼容性、OpenAPI additive-only 约束、OpenAI 私有字段隔离、API Key/权限边界、live skip 与成本边界、`ROUTE_NOT_DECLARED` 错误码登记、release/test/docs gate wiring。
- 发现：无影响正确性、兼容性或安全边界的实质问题。
- 处理：无需处理。
- 结果：实施检查计数 `2/3`。

### 第 3 轮：通过

- 时间：2026-10-16
- 范围：Maven 全量回归、项目测试/文档门禁、release gate、真实 HTTP 黑盒、`scripts/dev.sh` 启停与残留端口、Git diff/secret 边界。
- 发现：无影响正确性、兼容性或可交付性的实质问题。
- 处理：无需处理。
- 结果：实施检查连续 `3/3` 通过。

## 实施进度

| 切片 | 状态 | 备注 |
|---|---|---|
| 规划与规划 `3/3` | 已完成 | 修正后连续三轮无修改通过 |
| A：版本化 manifest 与 fixture | 已完成 | Native/OpenAI JSON/SSE、旧请求、OpenAPI surface、错误和 deprecated registry 已落地 |
| B：validator 与离线/live gate | 已完成 | Node fail-closed self-test、离线默认 gate、显式 live probe 已落地 |
| C：双语文档与最小 Client 示例 | 已完成 | 双语 Kit 文档、curl、TypeScript、Java 示例和导航入口已落地 |
| D：集成、回归、发布验收 | 已完成 | Maven、项目测试/文档门禁、release gate、真实 HTTP 黑盒和 `scripts/dev.sh` 均已验收；实现检查连续 `3/3` 通过 |
| Git commit/push/工作区清理 | 已完成 | 最终 diff/secret/状态复核通过；本次交付完成 commit、push 并确认工作区干净 |

## 集成、回归与验收证据

### 后端与仓库门禁

- `mvn clean test`：通过；API `562`、Documents `89`、Core `7860`、Starter `45` 个测试通过，Core 有 `201` 个既有条件跳过，无失败。
- `./scripts/verify-project-tests.sh`：通过，报告 `Repository gate chain: 59 checks passed.`。
- `./scripts/verify-project-docs.sh`：通过，报告 `Project documentation verification: 16 checks passed.`。
- `./scripts/verify-release.sh --skip-npm-ci --skip-playwright --skip-helm --skip-docker`：通过，`16 passed, 0 failed, 10 skipped`；跳过项为显式配置的 Docker/Helm/Playwright/runtime/goldenset/quality/real-LLM 可选检查，不代表 Client contract 失败。

### 外部 Client 黑盒

- 使用 `.env` 中配置的真实模型服务执行了真实 HTTP 调用，不是 mock。
- Native JSON、Native SSE、OpenAI JSON、OpenAI SSE、OpenAPI surface、producer compatibility、API error/trace/failure-class probes 均通过。
- `18090` 临时实例验证了默认 KNOWLEDGE alias 的数据库文档竞争会返回 `COLLECTION_PURGE_CONFLICT`；`18092`、`18093` 临时实例验证了 OpenAI 标准 body、SSE 以及显式 `rag.mode=PLAIN` 的兼容边界。
- 所有临时实例已停止；`18087`、`18090`、`18092`、`18093` 无残留监听。PLAIN 只作为 transport probe，不冒充 KNOWLEDGE 证据质量验收。

### 启动与文档

- `scripts/dev.sh` 已验证 PostgreSQL profile、backend readiness、Vite、HMR、WebUI proxy、root identity 和 root management write，并通过 `./scripts/dev.sh --stop` 清理，无残留端口。
- 双语 Client Contract Kit、curl、TypeScript SSE parser、Java `HttpClient` 示例、错误动作映射、deprecated 迁移规则和 live/KNOWLEDGE 验收边界已写入长青文档。
- 本批没有数据库 schema 变化，不需要 Flyway migration；没有改变既有 endpoint、HTTP method、旧请求字段、旧响应字段、SSE event 名称或 OpenAI 标准 body。

## 恢复入口

中断后先阅读规划文档，再检查 `git status --short --branch`、当前分支、现有 API contract gate、
manifest 和 SSE 文档。规划和实施检查均已完成 `3/3`；继续任务时只需完成最终 diff/secret/status
复核、commit、push 和归档。不要把 live skip 当作 pass，不要把 WebUI 通过当成 Client contract 完成。
