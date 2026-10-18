# 证据版本固定与在线容量隔离进度

对应 [实施规划](2026-10-18_EVIDENCE_SNAPSHOTS_ADMISSION_PLAN.md)。

## 当前状态

- 基线 `1e7c2802`；分支 `feat/evidence-snapshots-admission`；开始时工作区干净。
- 规划连续 3/3 通过（2026-10-18 11:45 +0800）；三轮分别核对范围、代码/兼容、验收/资源安全，期间未改规划正文。
- 证据 DTO/权威 SQL/映射/neighbor/引用/最终校验/事务栅栏已实现；请求、模型、后台容量及有界检索池已接入。
- 配置、REST API、SSE、测试指南和 WebUI 类型已增量更新；不改变既有 URL 与必填参数。
- 本批不包含备份/恢复平台，不声称跨实例全局并发配额。

## 实施与验收记录

### 2026-10-18 第一轮验证与修复

- 初次定向单测发现测试把构造时读取模型默认选项误当作 provider 调用；调整断言前清理构造调用记录，未放宽调用次数/费用预留断言。
- 真实 PostgreSQL 初次使用本地 amd64 测试镜像及未缓存 Ryuk 耗时；后续使用已有 arm64 pgvector 镜像和明确的 Ryuk 开关。
- 定向 SQL 测试发现空库没有默认 Profile，已显式建立测试 Profile；修复后该套件 10 项通过，含 65 个迁移与六类已提交 mutation。
- WebUI TypeScript、Vitest 1030 项、生产构建、Chat/Search Mock Playwright 23 项通过；这些不是模型质量证据。
- 禁止悲观锁、文档门禁（16 检查）、Client Consumer Contract 离线门禁通过。
- 首次全量 Maven：Core 7949 项，209 个装配错误（同一原因），0 个断言失败；新增 WebMvcConfigurer 被 WebMvcTest 切片自动扫描，但其 service 被排除。
  改为普通配置类中注册独立 WebMvcConfigurer bean，避免把运行时服务依赖强行拉入切片。修复后重新 clean compile/test-compile 和全量测试。
- 已用标准真实 E2E 启动脚本启动本批隔离 PostgreSQL 后端，真实 BGE-M3 写入与 Search 快照断言通过。
- 可控上游 Native/OpenAI 429、Retry-After、无额外 provider 调用、健康/Search 隔离和释放恢复已通过。
  竞争脚本初稿先后暴露未知可选字段、session 长度、同 session 抢占及查询扩展先于检索等夹具问题；已修正夹具，
  并使用 max-retrieval-queries=1 精确固定检索后的生成窗口，不能把此前 200 当作成功证明或当作版本检查失效。
- 本批自有后端使用 18191、自有 PostgreSQL 容器映射 55467、可控 provider 18186；未停止占用 18081/18082 的其他进程。

### 后续验证与修复

- 真实 Grok + BGE-M3 第二次端到端 13/13 通过，涵盖 Native JSON/SSE、真实 AGENT 和 OpenAI JSON/SSE；
  首次外部注册表夹具把 baseUrl 写成仅 API Key 字段支持的 placeholder，导致指定路由失败并发生 legacy fallback；
  已改为明确 base URL 后重跑，不将首次 fallback 作为指定模型成功证据。
- 真实 MiniMax Native KNOWLEDGE 必要事实/引用/快照通过，Python/Node 官方 SDK JSON/SSE 4 条 live 路径通过。
- 真实三用例 QA 揭示默认 runConcurrency=4 与后台模型上限=2 冲突，已有用例被 Admission 拒绝。
  已将含 KNOWLEDGE_QA 的单 run 调度上限自动收敛至后台容量；增加默认/关闭准入/单容量/纯检索回归。
  这是实现缺陷，修复后需重跑 Maven/相关集成与真实 QA，不能把原失败消掉不记录。
- 版本化 Native 路由、旧 `/rag/chat` 别名、Search 和 OpenAI 的请求容量黑盒通过，包含 JSON/SSE 释放及 health/discovery 旁路。
- 12:36 全部 gated PostgreSQL 首轮 Core 230 项中 1 项错误：真实向量结果与旧自定义全文 provider 的无快照结果融合时被误判版本冲突。
  修复为仅当两侧都有快照时比较版本，继续保留原结果的正文及快照，不把另一通道身份补写到旧正文；增加两种缺省方向和真实冲突的回归。
  该修复属于既定旧扩展兼容边界，未降低双快照冲突检查；需重新执行全部数据库矩阵。
- 12:43 真实 QA 使用默认后台容量 2，三用例连续双跑均通过，确定性门槛 100%、首 run 8732 tokens、环境漂移为 false。
  中间一次重跑先撞到不可变定义的重复版本 409，改为新 suite key；另一次由共库受控 worker 领取任务，
  已停止本批受控服务并重跑，不能将受控 provider 输出归因于真实模型质量。
- 12:47 统一 Client live 门禁发现 Consumer Kit 的 OpenAI 来源白名单遗漏新增 `evidenceSnapshot`。
  增量扩展 JSON/SSE 契约白名单和版本化 JSON fixture，并校验快照内部的字段/类型/digest，保留私有字段 fail-closed；
  增加缺省、显式 null 兼容及嵌套隐私字段拒绝自测，不以放开整个对象绕过门禁。

## 最终验收基线与结果（2026-10-18 12:55 +0800）

基线仍为 `1e7c2802`，末次 fetch 后与 `origin/main` 无分叉；以下证据来自本批最终代码工作树，
不是把旧提交的测试冒充为新实现通过。最后的修改仅同步文档与 Consumer Kit 验收白名单；
Java 生产代码自最后一次 clean、完整回归及下面的真实服务启动后未变更。

| 门槛 | 命令/场景 | 实际结果与本机证据 |
|---|---|---|
| 编译 | `mvn clean compile test-compile` | 通过；`/tmp/evidence-compile-final.log` |
| 全量后端 | `mvn test` | Maven 汇总 8656 项，失败/错误 0；215 项按 IT 开关跳过；`/tmp/evidence-maven-final.log` |
| 报告与代码门禁 | `scripts/verify-project-tests.sh` | 59 项通过；`/tmp/evidence-test-gates-final.log` |
| 真实数据库 | `TESTCONTAINERS_PG_IMAGE=pgvector/pgvector:pg16 scripts/verify-gated-it.sh` | 全部 28 个套件通过，Core 230 项与外部 Starter 1 项；含 V1–V65 迁移、生命周期/预算/删除与快照竞争；`/tmp/evidence-gated-final.log` |
| 外部 API 基线 | `scripts/verify-api-compatibility.sh` 与 live OpenAPI Consumer/governance 门禁 | 通过；`.verification/api-contract/evidence-final/` 与 `/tmp/evidence-api-compatibility-final.log` |
| 受控故障黑盒 | `scripts/verify-evidence-admission-e2e.py` 两种启动配置 | 模型/请求两层 429、Retry-After、无额外 provider 调用、旁路、JSON/SSE 释放恢复、更新冲突及无成功历史全部通过；`/tmp/evidence-model-blackbox-final.log`、`/tmp/evidence-request-blackbox-final.log` |
| 真实 QA | `scripts/run-knowledge-qa-evaluation.py` | Grok-4.5 + BGE-M3，充分事实/证据不足/跨 Collection 边界三个用例同版本双跑，6 个 case 全部确定性通过，环境无漂移；`/tmp/evidence-real-qa-isolated.log` |
| 统一 Client live | `scripts/verify-client-api-e2e.sh --live --skip-offline` | Native JSON/SSE、OpenAI KNOWLEDGE JSON/SSE、Python/Node SDK JSON/SSE 通过；OpenAI alias 固定 MiniMax-M3，Native 默认 Grok-4.5；`/tmp/evidence-client-live-final2.log` |
| 官方 SDK 双拓扑 | `scripts/verify-openai-sdk-e2e.sh --topology both` | Core + 外部 Starter，JSON/SSE、鉴权、provider 故障、功能关闭等 20 个验收步骤全部通过；这是受控 provider 契约证据，不是模型质量；`.verification/openai-sdk-e2e/evidence-admission-final/summary.md` |
| WebUI | `npm run typecheck`、`npm run test:run`、`npm run build` | 通过；Vitest 1030/1030；`/tmp/evidence-ui-*-final.log` |
| 核心 Mock 浏览器 | Chat/Search，`playwright.preview.config.ts` | 23/23；`/tmp/evidence-ui-playwright-final.log`，没有截图验收 |
| 真实前后端 | `chat-real.spec.ts` 经开发栈代理 | AGENT 工具调用/SSE/history 与 KNOWLEDGE 主路径通过；`/tmp/evidence-ui-real-final.log`，没有截图验收 |
| 开发栈实际启停 | `scripts/dev.sh`，18193/15183，隔离 DB | start、readiness、HMR、root 身份/管理写校验、真实浏览器及 stop/status 全通过；`/tmp/evidence-dev-launch-final.log`、`/tmp/evidence-dev-stop-final.log` |
| 最终真实模型专项 | `BASE_URL=http://127.0.0.1:18193 LLM_PROVIDER=openai REAL_LLM_CHAT_PROVIDER=openai scripts/real-llm-e2e-smoke.sh` | Grok-4.5 + BGE-M3，真实写入/检索、事实及 SUPPORTED、AGENT、Native/OpenAI JSON/SSE，13/13、FAIL=0；`/tmp/evidence-real-smoke-final.log` |
| 文档与并发约束 | `scripts/verify-project-docs.sh`、`scripts/verify-no-pessimistic-locks.sh`、`git diff --check` | 通过；文档门禁 16 项；归档后再次执行 |

### 复现实验与边界

- 配置和重新执行方法已提炼到 `docs/testing-guide*`；运行配置只覆盖本批隔离服务，不修改 `.env`。
  最终受控故障服务使用同一自有容器中的独立 `rag_evidence_fault` 数据库，避免领取真实 Evaluation 的任务。
- 为定位并复验 QA 并发、旧 provider 兼容和 Consumer Kit 白名单三项问题，以及完成双 provider 与真实开发栈验证，
  追加了有界真实调用，超过规划中预估的 24 次软目标；没有取消服务端费用硬限制，也没有循环到模型碰巧通过。
- PostgreSQL 镜像提供真实 vector/English FTS/trgm；本机无 pg_jieba，因此不声称实际执行了 jieba 扩展的 SQL。
  Jieba 的投影改动有单元 SQL 覆盖，已说明环境限制，不把这个限制当成真实验证通过。
- 无 schema 修改，无新 Flyway 迁移；快照、准入默认可用。旧来源可无快照，不伪造历史；实例准入不代表跨实例全局配额。
- 真实 LLM 结果只支持上述有界场景的质量结论；不承诺所有问题、模型输出和极端容量场景都绝无风险。
- 本批自有开发栈、两种测试后端、provider 和 PostgreSQL 容器均在交付前清理；未停止其他项目占用的 18081/18082 等端口。

### 收敛与交付

本记录在基本验收完成后冻结并归档。最终三轮只读实现检查逐轮在交付会话输出时间、范围、问题、措施和结果，
无问题轮次不回写本文；发现实质缺陷则修复、重测并重置计数。随后提交特性分支、无损合回并推送 main，
以 Git 提交记录及最终工作区/远端核对作为交付证据。本机 `/tmp` 与 `.verification` 文件是可复现实验的临时产物，
不是仓库事实入口，也不提交其中的凭据或原始响应。
