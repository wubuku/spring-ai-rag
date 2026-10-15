# Client API 兼容、运行时证据安全与逐请求 Trace 实施进度

> 对应规划：[Client API 兼容、运行时证据安全与逐请求 Trace 实施规划](NEXT_BATCH_API_EVIDENCE_TRACE_PLAN.md)
>
> 本文件是可恢复进度账本；稳定事实完成后提升到双语长青文档，并按 drafts 生命周期归档。

## 当前状态

- 日期：2026-10-15
- 分支：`main`
- 基线：`3482d702 feat: add QA evaluation workbench and bounded PDF import`
- 开始时工作区：干净，`main...origin/main = 0 0`
- 规划检查：规划正文 `3/3`；落档后最终复核 `3/3`；OpenAI required 语义修订后复核 `3/3`
- 实施状态：Slice A/B/C/D 已完成；实现阶段最终复核已完成，当前只剩 Git 交付
- 当前总体进度：约 98%（实现、测试、真实运行时验收、文档门禁和实现 `3/3` 完成；commit/push 待完成）
- 下一步：完成实现阶段连续 `3/3` 只读复核，回看最终 diff 后 commit/push 并确认工作区干净

## 已完成的探索

- 已核对 `AGENTS.md`、`docs/delivery-workflow-zh-CN.md`、`.agents/skills/project-docs/SKILL.md`、`docs/drafts/README-zh-CN.md`。
- 已核对 Native Chat、Search、OpenAI compatibility Controller、Chat DTO、ErrorResponse、SSE event、OpenAPI contract test、现有 contract/release scripts。
- 已核对 CitationValidator、CitationQueryAugmenter、RetrievalTraceCollector、PromptBudgetDocumentPostProcessor、RetrievalTraceSession、RetrievalDiagnosticsService、RequestTraceFilter、V35/V63 迁移事实。
- 已确认本批默认不需要 Flyway migration；request Trace 关联先复用现有 retrieval metadata JSONB，并保留自动迁移升级边界。

## 冻结决策

- 不删除/改名任何既有 Client API；所有变化采用可选字段/字段增量/response header/query 参数。
- `KNOWLEDGE` 确定性 evidence safety 默认启用；`PLAIN` 不适用；`AGENT` 本批只诊断不新增结论裁剪。
- 流式 `KNOWLEDGE` 先完成证据评估再输出内容，避免先泄漏未受支持答案；`PLAIN`/`AGENT` 保留现有 token streaming。
- `X-Trace-Id` 是请求级 correlation，`X-RAG-Retrieval-Trace-Id` 是检索级 UUID，两个语义不合并。
- 诊断和观测 fail-open；不新增高基数 metrics tag，不记录 secret/prompt/chunk。
- 默认不新增 Flyway；若实施证明确需 schema，必须回到规划修改并重做 `3/3`。

## 实施切片

- [x] Slice A：API compatibility manifest、Node helper、黑盒入口、release gate
- [x] Slice B：EvidenceAssessment、默认安全回答、JSON/SSE/replay/OpenAI 兼容
- [x] Slice C：request Trace 与 retrieval Trace 关联、错误响应、诊断过滤
- [x] Slice D：双语正式文档、基本硬门槛、全面回归、真实启动/LLM（如适用）

## 规划检查账本

### 第 1 轮

- 时间：2026-10-15（历史修订轮次；不计入最终收敛）
- 范围：需求闭环、自包含性、默认决策、非目标、迁移/回滚边界
- 发现/处理/结果：先后发现并修正状态枚举记录、record 构造器兼容、状态数量、request Trace 传递载体、OpenAI 请求字段基线和安全门开关边界问题；本历史轮次不计入最终 `3/3`。

### 第 2 轮

- 时间：2026-10-15（历史修订轮次；不计入最终收敛）
- 范围：代码、HTTP/SSE/OpenAI 契约、数据、ACL、并发、幂等、流式可行性
- 发现/处理/结果：历史修订期间已分别修正上述问题；本历史轮次不计入最终 `3/3`。

### 第 3 轮

- 时间：2026-10-15（历史修订轮次；不计入最终收敛）
- 范围：实施顺序、验收矩阵、发布门禁、恢复入口、Git 交付和文档同步
- 发现/处理/结果：历史修订期间已完成范围收敛；本历史轮次不计入最终 `3/3`。

### 落档后最终只读复核

- 第 1 轮：2026-10-15；范围为规划正文、progress 与工作流交叉一致性；未发现问题，未修改文档。
- 第 2 轮：2026-10-15；范围为代码事实、API/SSE/OpenAI、ACL、幂等、Trace 和迁移边界；未发现问题，未修改文档。
- 第 3 轮：2026-10-15；范围为实施顺序、测试验收、运行时启动、文档和 Git 交付；未发现问题，未修改文档。
- 结果：连续 `3/3` 无修改复核通过，允许开始 Slice A。

### OpenAI required 语义修订后最终复核

- 第 1 轮：2026-10-15；已确认 mapper 对 `model`、`messages` 的 required 语义与规划一致。
- 第 2 轮：2026-10-15；已重新核对 API/SSE/OpenAI、证据、Trace、ACL、幂等、迁移和并发边界，未发现问题。
- 第 3 轮：2026-10-15；已重新核对实施顺序、测试/启动/真实 LLM 边界、双语文档和 Git 交付，未发现问题。
- 结果：连续 `3/3` 无修改复核通过，允许开始 Slice A。

### 修订后最终复核

- 第 1 轮：2026-10-15；范围为实现代码、默认配置、证据状态、安全回答和测试 fixture；发现旧测试未表达新默认证据契约，已补充有效引用/检索 fixture，计数重置。
- 第 2 轮：2026-10-15；范围为双语正式文档、API/OpenAPI/SSE/OpenAI 增量兼容、Trace 关联和迁移边界；已发现并修正 OpenAPI 示例覆盖真实 schema、ErrorResponse 类型与 traceId 未稳定暴露的问题，计数重置。
- 第 3 轮：待执行；范围为修正后的全量回归、运行时启动、外部 Client 黑盒、真实 LLM（如适用）和 Git 交付。

## 实施/验收账本

### Slice A：Client API 契约与黑盒回归

- 状态：完成。
- 主要变更：`testdata/api-contract/v1-compatibility.json`、
  `scripts/lib/api-contract-check.mjs`、`scripts/verify-api-compatibility.sh`、
  `scripts/verify-release.sh`、OpenAI request schema 注解。
- 关键决策：manifest 只冻结既有外部契约；新增字段/查询参数按 optional 处理；黑盒门禁检查错误 envelope 和 `X-Trace-Id`，不依赖 WebUI。
- 已通过：`node scripts/lib/api-contract-check.mjs --self-test`；真实应用 OpenAPI 契约检查 `10` 个路径/`9` 个 schema 通过；`scripts/verify-api-compatibility.sh` 真实黑盒通过。

### Slice B：运行时证据充分性与安全回答

- 状态：完成。
- 主要变更：`EvidenceAssessment`、`EvidenceAssessmentEvaluator`、Chat 结果/JSON/SSE/回放链路、默认配置和测试。
- 关键决策：`KNOWLEDGE` 默认 fail-closed；`PLAIN` 不裁剪；`AGENT` 只诊断；不调用 LLM judge；流式 KNOWLEDGE 先有界缓存再评估。
- 已通过：`EvidenceAssessmentEvaluatorTest`、`ChatExecutionServiceEvidenceSafetyIntegrationTest`、原有 Chat 定向回归。

### Slice C：请求级 Trace 与检索 Trace 关联

- 状态：完成。
- 主要变更：`RequestTraceFilter`、Chat command/mapper、retrieval session/diagnostics/repository/controller、错误响应。
- 关键决策：`X-Trace-Id` 与 `X-RAG-Retrieval-Trace-Id` 保持不同语义；复用已有 metadata JSONB，不新增 Flyway migration；诊断 fail-open。
- 兼容处理：保留旧 Java 构造器、controller/service/repository 方法签名，并只增加可选字段/参数。

### Slice D：测试、文档与运行时验收

- 状态：完成；Git 交付尚未执行。
- 文档：双语 `rest-api`、`configuration`、`architecture`、`testing-guide`、`SSE-PROTOCOL` 已同步；`verify-project-docs.sh` 通过 16 项。
- 编译与回归：`mvn clean compile test-compile` 通过；随后完整 `mvn test` 通过，API/Core/Starter 等模块均 `BUILD SUCCESS`，无失败/错误；定向证据安全回归 39/39 通过；迁移版本测试 4/4 通过。
- 静态门禁：`verify-no-pessimistic-locks.sh`、`git diff --check`、脚本 `bash -n`、契约 helper self-test 均通过；schema 仍为 Flyway V63，本批无需迁移。
- `scripts/dev.sh`：`postgresql` profile、后端 `18082`、readiness UP，启动/停止均验证；真实 API contract 使用独立开发栈验证通过。
- 外部 Client 黑盒：Native Search GET/POST 均保持既有响应 shape；错误响应保留旧字段并增量返回 `traceId`；请求头 `X-Trace-Id` 原样回传；检索 Trace 页面可按 `requestTraceId` 查询并返回关联记录。
- 真实 LLM：使用 `.env` 配置的真实 MiniMax Chat + SiliconFlow Embedding，在独立 `18083` 服务上通过隔离集合创建、Embedding、Search、Knowledge JSON、Knowledge SSE；模型未配置 `AGENT` capability 时按既有 `400 MODEL_CAPABILITY_UNSUPPORTED` 明确跳过 Agent 断言。首次 smoke 暴露旧脚本把“无引用的模型答案”误判为失败，已改为验证默认 evidence safety；同时补充 provider 传递与预检超时，最终 `PASS=11 FAIL=0`。
- 运行限制：`18081` 当时被其他项目 UniAuth 占用，遵循启动脚本安全策略未误杀，改用 `18083` 完成真实验收。

### 实施最终验收检查账本

- 第 1 轮：2026-10-15；范围为真实 OpenAPI、manifest、Native Search/Chat/SSE、ErrorResponse、Trace 关联、默认 evidence safety、迁移和并发规则；发现并修正 OpenAPI 定制器与真实 schema 不一致，计数重置。
- 第 2 轮：2026-10-15；范围为修正后编译、完整 Maven 回归、文档/脚本/锁门禁、`scripts/dev.sh` readiness 和真实契约黑盒；未发现问题，未修改实现或文档。
- 第 3 轮：2026-10-15；范围为真实 Embedding/LLM、Knowledge JSON/SSE 安全回答、Agent capability gating、Native Search GET/POST、错误 Trace 和 retrieval Trace 过滤；发现并修正真实 smoke 对新安全契约和 provider 继承的错误断言，计数重置。
- 第 4 轮：2026-10-15；范围为修正后的全量回归、运行时启动、外部 Client 黑盒、真实 LLM（如适用）和 Git 前最终审查；未发现问题，未修改文件。
- 结果：实现阶段连续三轮无修改复核（第 1/2/3 轮）通过；第 4 轮为最终运行时复核记录，不改变通过结论。

### 最终复核后的交付状态

- 全量 `mvn test`：API、Documents、Core、Starter 均 `BUILD SUCCESS`，无失败/错误。
- 真实运行时：`scripts/dev.sh` readiness、真实 OpenAPI contract、外部 Client 黑盒、Trace 关联均通过；独立真实 LLM smoke 最终 `PASS=11 FAIL=0`。
- 文档/静态门禁：双语文档、项目文档 16 项、脚本语法、禁锁检查、`git diff --check` 均通过。
- 数据迁移：无新增 schema 需求，Flyway 最高版本仍为 V63；request Trace 继续复用既有 JSONB metadata。
- Git 交付：待执行 `git diff` 最终审查、commit、push，并确认 `main` 工作区干净且与 `origin/main` 同步。

### 实施阶段检查规则

实现和文档每次出现实质修复都将计数归零；只有连续三轮系统性检查均未修改任何文件才算实现 `3/3`。与代码实际行号、格式化等实施自然细节不触发重置；API 字段、默认值、错误语义、SSE 顺序、ACL、迁移和测试缺口属于必须修复的问题。
