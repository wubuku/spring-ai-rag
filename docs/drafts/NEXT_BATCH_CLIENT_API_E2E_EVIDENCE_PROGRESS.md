# Client API 真实端到端、证据提交与验收可靠性改进进度

> 对应规划：[实施规划](NEXT_BATCH_CLIENT_API_E2E_EVIDENCE_PLAN.md)。

## 状态

- 分支：`main`
- 基线：`6f9d1f3e`（开始时与 `origin/main` 同步）
- 开始时工作区：干净
- 当前阶段：实现、验收、三轮只读审查与 Git 交付均已完成；工作区已恢复干净
- 当前最高迁移：V65（Flyway 自动迁移）
- 当前真实模型预算：最多 15 次 Chat completion、18 次 Embedding provider 请求；固定矩阵、retry/query rewrite/judge 约束见规划 §1 与 §4.4
- 规划审查计数：3/3（2026-10-17 15:43 +0800 连续三轮只读检查通过；期间未修改规划正文）
- 实现审查计数：3/3（最终轮次连续三轮无实质问题；期间未修改实现或验收契约）

## 本批决策

- 保持对外 API 兼容；省略 Collection scope 仍为 `CALLER_VISIBLE`。
- 让持久化来源校验支持合法的未归属来源；为未归属来源增加独立的内部文档 CAS 栅栏，不改外部 `documentRevision`。
- E2E 清理必须先成功硬删测试文档，随后才能软删其 Collection；清理失败不能被吞掉，也不能制造 enabled orphan。
- Node 示例依赖通过锁文件确定安装；Python/Node JSON 与 SSE 均纳入 live SDK 验收。
- 真实 QA 复用现有版本化 Evaluation Suite 与确定性 hard gate，judge 不作为通过门槛。
- 真实 QA 固定夹具：`testdata/evaluation/knowledge-qa-v1.json`；夹具只保存合成身份、事实和约束，不保存真实答案、prompt 或 source 正文。

## 规划审查日志

| 轮次/状态 | 时间（Asia/Shanghai） | 范围 | 发现与处理 |
|---|---|---|---|
| 草拟 | 2026-10-17 | 工作流、E2E 清理、来源栅栏、SDK、Evaluation API、迁移现状 | 已探索代码与文档；规划审查待进行。 |
| 审查重置 | 2026-10-17 14:38 +0800 | E2E 清理与 Collection 删除语义交叉核对 | 发现“调用永久删除”易被理解为已删除；修正文案为脚本只是尝试删除、忽略失败，Collection 软删会解绑残留文档。计数归零，重新开始三轮。 |
| 再次重置 | 2026-10-17 14:40 +0800 | 并发 Chat 与文档栅栏条件写入 | 发现比较读取到的旧 fence 会让同一未归属文档的并发 Chat 产生伪冲突；改为只以 `document_revision`/当前有效状态作条件，fence 原子递增，并要求并发 Chat 双成功、与真实 mutation 竞争时按先后安全失败。计数归零，重新开始三轮。 |
| 第三次重置 | 2026-10-17 14:46 +0800 | 真实请求次数预算与完整验收矩阵 | 实际矩阵包含 provider preflight、文档 Embedding、Native/SDK/Evaluation 的查询 Embedding，6 次上限不成立；按最多 3 个共享文档及各查询请求上界调整至 16，并纳入 runner 预算预检/计数。计数归零，重新开始三轮。 |
| 第四次重置 | 2026-10-17 14:49 +0800 | 文档 revision 条件写入的并发时间窗口 | 澄清栅栏仅保证 commit 预留快照到历史提交的窗口，不声称追溯模型生成期间已经完成的内容更新；将 mutation race 验收锚定在预留 snapshot 与条件写入之间，避免高估证据新鲜度。计数归零，重新开始三轮。 |
| 第五次重置 | 2026-10-17 14:53 +0800 | 无人值守测试数据库、端口与进程所有权 | 为保证可恢复和不影响其他项目，新增统一 E2E 入口：只启动并清理本批所有的 PostgreSQL/服务资源，动态选择空闲端口；记录此前 18081 被其他项目服务占用的事实并要求运行时重新探测、绝不按端口杀进程。计数归零，重新开始三轮。 |
| 第六次重置 | 2026-10-17 14:54 +0800 | 统一验收入口的实施可执行性 | 发现入口名仍为“建议”，可能导致恢复实施时重复选择；已固定为 `scripts/verify-client-api-e2e.sh`，计数归零并重新开始三轮。 |
| 第七次重置 | 2026-10-17（本轮续审） | Evaluation run/compare 语义、真实调用总量与重试成本 | 发现 compare 只比较两个已完成 run，不会自动执行评测；为了有真实基线必须显式运行两次，原预算未把重复 QA completion、查询 embedding、Python/Node 四种 JSON/SSE 请求、AGENT 及应用/SDK 重试策略全部计入。已修订为固定 3 case × 1 variant × 2 run，并逐类核算 15 次 Chat / 18 次 Embedding；隔离真实矩阵关闭自动重试、query rewrite、judge，限制 AGENT 一轮/一次 retrieval，并规定预算外请求立即失败。计数归零，重新开始三轮。 |
| 第八次重置 | 2026-10-17 15:24 +0800 | 真实 provider 故障切换与调用预算封闭性 | 发现虽关闭重试但多模型 Router 的 fallback 与 `max-candidate-attempts=3` 仍可能在一次逻辑 Chat 中产生额外 provider 请求，使 15 次预算不能作为硬上限。已规定隔离服务只配置一个 Chat primary 和一个 Embedding primary、两者 fallbacks 为空，并设 `rag.chat.execution.max-candidate-attempts=1`；provider 失败即失败，不切换模型。计数归零，重新开始三轮。 |
| 第九次重置 | 2026-10-17 15:33 +0800 | Schema 字段精度、真实 query-rewrite 开关与可恢复实施 | 发现规划使用“tombstone”泛称，未明确当前 schema 的 `source_deleted_at`，且未写明实际关闭 LLM query rewrite 的配置键；这会让中断后的实施出现错误 SQL/配置。已补充 `enabled = TRUE`、`source_deleted_at IS NULL`、`document_revision` 条件，以及 `rag.query-rewrite.llm-enabled=false`、`rag.chat.knowledge.query-transformer=none`（规则改写不调用模型）的固定配置。计数归零，重新开始三轮。 |
| 第十次重置 | 2026-10-17 15:40 +0800 | 实施门禁可恢复性 | 发现规划只写“文档/禁锁门禁”，未明确列出 `verify-no-pessimistic-locks.sh` 与 `verify-project-docs.sh`，中断恢复时可能漏跑关键门禁。已在实施切片 7 固定这两个脚本及 `git diff --check` 的执行顺序。计数归零，重新开始三轮。 |
| 规划审查完成 | 2026-10-17 15:43 +0800 | 需求闭环与自包含性；代码、schema、事务、并发、权限、预算；实施顺序、验收、启停、回滚、门禁 | 连续 3 轮完整只读检查无实质问题，规划获准进入实施。检查脚本误报均仅修正检查锚点，未修改规划内容。 |

## 实施切片

| 切片 | 结果 | 证据/恢复点 |
|---|---|---|
| V65 内部文档提交栅栏 | 已完成 | `V65__unassigned_document_chat_commit_fence.sql`；默认值 0、非负约束；不改变 `document_revision` |
| 未归属文档 durable Chat commit | 已完成 | `RagChatHistoryRepository` 改为 `LEFT JOIN` + 文档条件 CAS；Collection 映射形状保持兼容；新增单测已通过 |
| 真实 smoke 清理闭环 | 已完成 | `real-llm-e2e-smoke.sh` 清理前重新读取最新 revision，硬删后要求 404、列表无残留；清理失败非零且保留脱敏证据目录 |
| Node SDK 可复现安装 | 已完成 | 新增示例 `package-lock.json`；quickstart live 改为 `npm ci`，并覆盖 Python/Node JSON 与 SSE |
| 外部 Client 统一门禁 | 已完成 | 新增 `scripts/verify-client-api-e2e.sh`；离线 Native/OpenAI fixture、SDK 协议负向自测已通过 |
| 版本化 KNOWLEDGE QA runner | 已完成 | 新增 `scripts/run-knowledge-qa-evaluation.py`；固定三 case、单 variant、两次 run、deterministic hard gate 与无 drift compare |
| PostgreSQL/Flyway V65 集成测试 | 已完成 | 新增 `UnassignedDocumentChatFencePostgresIntegrationTest`；一次性 PostgreSQL 已验证 V1→V65、3/3 通过，测试库已删除 |
| Evaluation 真实链路修复 | 已完成 | 修复评测 session ID 超过 36 字符、JSONB 快照数值节点/字段顺序导致的 `CORPUS_CHANGED` 误报；事实匹配忽略空白差异；新增 9 项聚焦回归通过 |
| 基本集成硬门槛 | 已完成 | 全量 Maven、V65 集成、禁锁、文档、API/SDK 黑盒、`scripts/dev.sh`、MiniMax 与 OpenAI-compatible 真实验收均已通过；实现审查从 0/3 开始 |
| 实现阶段连续三轮只读审查 | 已完成 | 三轮分别覆盖事务/迁移/并发/恢复/安全、Client API/SDK/SSE/权限/成本、测试/启动/文档/回滚/Git；最终连续 3/3 无实质问题 |

## 实现审查日志

| 轮次 | 范围 | 结果 |
|---|---|---|
| 1/3 | V65 迁移、事务边界、未归属文档 CAS、并发、恢复、安全 | 无实质问题；计数 1/3 |
| 2/3 | 外部 Client API、SDK/SSE、权限、评测 hard gate、成本与隐私 | 无实质问题；计数 2/3 |
| 3/3 | 测试证据、`scripts/dev.sh`/真实服务残留、文档同步、回滚、密钥与 Git 交付卫生 | 无实质问题；计数 3/3 |

## 验收记录

| 时间 | 范围 | 结果 |
|---|---|---|
| 2026-10-17 16:15 +0800 | `bash -n`、Node helper self-test、Python 编译/帮助、`verify-client-api-e2e.sh --offline-only` | 通过；包含 Consumer Contract、SDK quickstart、官方 SDK 20 项协议变异测试 |
| 2026-10-17 20:38 +0800 | `KnowledgeQaEvaluatorTest`、`EvaluationSuiteRunCompareTailTest` | 通过；9/9，无失败；覆盖空白归一化和 corpus snapshot canonical 比较 |
| 2026-10-17 20:40 +0800 | 隔离 PostgreSQL + MiniMax `KNOWLEDGE_QA` | 通过；3 case × 2 run，`qaPassRate=1.0`，`environmentDrift=false`，只记录聚合 token/延迟，不记录答案/source |
| 2026-10-17（实现审查第 2 轮） | `real-llm-e2e-smoke.sh` 清理顺序 | 发现文档删除失败时仍可能继续删除 Collection；已修复为仅在文档确认 404 且 enabled 列表无残留后删除 Collection，审查计数重置为 0/3 |
| 2026-10-17（实现审查第 2 轮） | 长青文档迁移版本事实 | 发现 `project-context`/`testing-guide` 仍写 V64；已同步为当前 V65（预算验收明确为 V64/V65），审查计数保持重置为 0/3 |
| 2026-10-17（实现审查第 3 轮） | `docs/drafts/README*` 活动规划入口 | 发现本批仍写“规划审查中”；已同步为“规划、实施与验收已完成，剩余 Git 交付”，审查计数重置为 0/3 |
| 2026-10-17（本轮） | V65 PostgreSQL/Flyway 集成 | 通过；V1→V65 一次性数据库迁移，`3/3` 断言通过，测试数据库已删除 |
| 2026-10-17（本轮） | 全量 Maven 回归 | 通过；Core `7915`（失败 0、错误 0、跳过 210）、Starter `49`（失败 0、错误 0、跳过 1）、Documents `89`（失败 0），`BUILD SUCCESS` |
| 2026-10-17（本轮） | `verify-client-api-e2e.sh --offline-only` | 通过；Consumer Contract、Python/Node SDK JSON/SSE、官方 SDK 负向协议测试 `20/20` |
| 2026-10-17（本轮） | MiniMax 真实 Chat/QA 验收 | 通过；真实 smoke `PASS=11 FAIL=0`；QA 为 3 case × 2 run，`qaPassRate=1.0`、`environmentDrift=false` |
| 2026-10-17（本轮） | OpenAI-compatible `grok-4.5` 真实验收 | 通过；真实 smoke `PASS=11 FAIL=0`，覆盖 JSON Chat、Agent tool calling、SSE、Citation Registry/source handle 对齐 |
| 2026-10-17（本轮） | `scripts/dev.sh` 持久在线会话与停止验收 | 通过；后端 `18082`、前端 `15173` readiness/HTTP 通过，`--stop` 后自有端口释放；外部 `/auth/me` 返回 `200` |
| 2026-10-17（本轮） | 本批临时资源清理 | 通过；OpenAI 真实服务、28182 端口、两个一次性 PostgreSQL 数据库及共享库四组 Collection/Document、两套 QA suite/run 精确清理，残留断言通过 |
| 2026-10-17（本轮） | 实现阶段连续 3/3 只读审查 | 通过；最终三轮无实质问题，期间未修改实现或验收契约 |
| 2026-10-17（本轮） | 最终 Git 交付 | 已完成；`main` 提交 `73cd8a73` 已推送到 `origin/main`，随后完成文档状态收尾 |
