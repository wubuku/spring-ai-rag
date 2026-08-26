# 操作级 API 能力控制实施进度

> 对应规划：[2026-08-26_OPERATION_SCOPED_API_CAPABILITIES_PLAN.md](2026-08-26_OPERATION_SCOPED_API_CAPABILITIES_PLAN.md)

## 1. 恢复入口

- 任务：为数据库 API principal 增加 `RAG_READ` / `RAG_WRITE` 操作级能力，并在
  HTTP 数据面真正执行。
- 规划基线：`main` / `15f3b3d3`，已与 `origin/main` 对齐。
- 规划工作区：`/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
- 规划分支：`main`
- 计划实施分支：`feat/operation-scoped-api-capabilities-20260826`
- 计划实施 worktree：`/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-operation-scoped-api-capabilities`
- Flyway 基线：V1–V48；目标 V49。
- 当前阶段：后端、WebUI 与双语长青文档完成；特性分支已推送并合并到 `main`，
  main 合并后完整验收与真实 LLM 阶段已通过，隔离特性 worktree 已安全移除。

## 2. 已完成探索

- 核对 `ApiKeyAuthFilter`、`ApiAccessPolicy`、`AuthenticatedApiPrincipal`、
  `ApiKeyCollectionAccess`、`ApiKeyManagementService`、principal/key entity 和
  authentication projection。
- 核对 API Key 创建、策略 CAS、轮换、identity `/auth/me` 和 root/legacy 兼容测试。
- 核对 RAG Search、JSON Record、Chat、OpenAI compatibility、Models、Evaluation
  controller 的方法与路由。
- 核对 WebUI API Key 页面、类型、Mock、Vitest 和 real E2E 覆盖。
- 确认当前能力只在 introspection 静态返回，未形成数据库策略和数据面门禁。

## 3. 已冻结的实现决策

- 只允许 `["RAG_READ"]` 和 `["RAG_READ", "RAG_WRITE"]`。
- 创建省略能力默认完整读写；policy 更新省略能力保留当前值。
- `RAG_WRITE` 必须包含 `RAG_READ`。
- 能力存储在 stable principal；credential rotation 继承。
- V49 新增 principal 能力列；credential 不新增权威能力列。
- root、legacy static、auth-disabled、ADMIN 保持 unrestricted 兼容。
- ADMIN 的有效能力固定为完整读写；显式只读策略更新拒绝，避免状态与执行语义不一致。
- V48 兼容 `NULL` 可按完整读写处理；非空非法持久化值必须 fail-closed，不得回退为完整权限。
- 中央能力过滤器在认证后、限流前执行；未知 mutating route 默认要求 `RAG_WRITE`。
- Chat、检索、模型比较和非持久化评估属于 `RAG_READ`。

## 4. 规划审查账本

计数器：`0`（第 1 轮发现并修复实质问题后重置）

### 轮次 1：已修复，计数器重置

- 时间：2026-08-26 09:56:44 CST
- 范围：需求闭环、自包含性、默认决策、非目标。
- 发现：V49 SQL 示例未包含正文承诺的数据库能力 CHECK；ADMIN 的有效完整能力与
  identity “真实能力”表述可能不一致。
- 处理：补充 `ck_rag_api_principal_capabilities` 约束，明确 V49 由 Flyway 从
  V48 一次性升级；明确 ADMIN 有效能力固定为完整读写，显式只读策略更新返回
  400，并补充验收场景。
- 结果：已修复，规划审查计数重置为 `0`。

### 重审轮次 1：已修复，计数器重置

- 时间：2026-08-26 09:59:00 CST
- 范围：需求闭环、自包含性、默认决策、非目标。
- 发现：规划未定义非空非法持久化能力值的 fail-closed 语义，存在解析异常时
  意外回退为完整权限的风险；响应层也未明确统一使用 ADMIN 的有效能力。
- 处理：明确 `NULL` 仅作 V48 兼容，非法非空值按 policy service unavailable
  处理；统一 response 使用有效能力，ADMIN 固定完整读写，并增加对应验收场景。
- 结果：已修复，规划审查计数重置为 `0`。

### 重审轮次 2：已修复，计数器重置

- 时间：2026-08-26 10:02:00 CST
- 范围：需求闭环、自包含性、默认决策、非目标；活动文档门禁。
- 发现：规划提前链接了尚未创建的 V49 migration 和 capability filter，导致
  `verify-project-docs.sh` 在实施前失败。
- 处理：改为代码路径，待实现文件创建后再由正式长青文档建立有效链接。
- 结果：已修复，规划审查计数重置为 `0`。

### 轮次 2：通过

- 时间：2026-08-26 10:03:00 CST
- 范围：代码、schema、API、安全、并发、兼容可行性。
- 发现：未发现会影响本轮正确性、数据一致性、兼容性或可实施性的实质问题。
- 处理：无需修改规划正文；确认 V49、principal snapshot、中央过滤器、兼容构造器、
  CAS/rotation 继承和 fail-closed 语义与现有实现边界相容。
- 结果：通过，连续无修改计数为 `1`。

### 轮次 3：通过

- 时间：2026-08-26 10:05:38 CST
- 范围：实施顺序、验收矩阵、发布、回滚、恢复、Git 交付风险。
- 发现：未发现会影响本轮正确性、验收可执行性、发布回滚或 Git 交付安全的实质问题。
- 处理：无需修改规划正文；确认实施顺序覆盖 schema、服务、过滤器、集成测试、
  WebUI、双语长青文档及合并后完整复验，且明确不使用破坏性 Git 操作。
- 结果：通过，连续无修改计数达到 `3/3`，允许进入实施。

## 5. 实施切片与状态

| 切片 | 状态 | 证据 |
|------|------|------|
| 规划与三轮审查 | 已完成 | 本文 §4，连续 `3/3` 无修改 |
| V49 与能力规范化 | 已完成 | `V49__operation_scoped_api_capabilities.sql`、`ApiCapabilitySupport` |
| DTO、principal snapshot、service 与响应 | 已完成 | DTO、认证投影、生命周期 service、`/auth/me` |
| 中央 capability filter 与后端测试 | 已完成 | 中央 `ApiCapabilityFilter`、WebMvc 真实过滤链、OpenAI 错误合同 |
| PostgreSQL/HTTP 集成验收 | 已完成聚焦矩阵 | V48→V49、只读创建/更新/轮换、ADMIN 降级保护；PostgreSQL 9/9 |
| WebUI、Mock、Vitest、Playwright | 已完成基本门槛 | typecheck、29 文件/218 项 Vitest、production build、alignment、核心 Mock Playwright 1/1 |
| 双语长青文档与仓库门禁 | 已完成 | configuration/API/context/architecture/testing/release/index 同步；project-docs 10/10 |
| merge main、push、清理 worktree | 待开始 | |

## 6. 验证记录

实施前已确认：

- [x] 最新 `main` 为 `15f3b3d3` 且与 `origin/main` 对齐。
- [x] main 规划工作区干净。
- [x] 当前 Flyway 为 V1–V48。
- [x] 规划三轮达到 `3/3`。
- [x] 从最新 `main` 创建专用特性分支和隔离 worktree。
- [x] 后端受影响单元/WebMvc/OpenAPI 测试：72 项通过。
- [x] PostgreSQL Testcontainers 集成：9 项通过，覆盖 V48→V49/backfill、创建/更新/
  轮换、能力省略保持与 ADMIN 降级拒绝。
- [x] `mvn clean compile test-compile`。
- [x] 前端 typecheck。
- [x] 前端 Vitest：29 个文件、218 项通过。
- [x] 前端 production build、alignment、核心 Mock Playwright：1/1。
- [x] 双语文档门禁：10/10；`git diff --check` 已通过。
- [x] 统一完整门禁、真实双实例 HTTP/Playwright 与真实 LLM JSON/SSE 验收。
- [x] 禁锁与最终密钥检查。
- [x] merge 最新 `origin/main` 后完整复验。
- [x] 特性分支和 main push，最终状态干净。

## 7. 已知限制

- 本轮本身不改变 Chat/LLM/provider 代码，真实 LLM 调用不是操作能力实现的唯一
  证明；但按验收要求仍执行了真实双实例 Chat JSON/SSE 合同，以确认能力过滤器、
  限流、幂等和生命周期变更没有破坏实际 provider 调用链。
- PostgreSQL 集成优先使用项目既有 Testcontainers 或明确标记的可处置数据库。
- 不记录任何 API key、Token、数据库密码或包含秘密的运行日志。

## 8. 完整验收执行记录

- 2026-08-26 10:47 CST：首次启动
  `verify-managed-api-principals.sh --with-real-llm`。PostgreSQL 日志确认空库完整执行
  49 个 migration 并到达 V49；矩阵随后暴露多个旧集成测试仍把“最新 migration”硬编码
  为 V48。已主动中止后续昂贵阶段，全面扫描出五个测试类中的六处同类断言；明确只迁移到
  历史目标版本（例如 V30）的测试不修改。修正后将从 PostgreSQL 矩阵重新开始，不沿用
  本次失败运行作为通过证据。
- 2026-08-26 10:50 CST：修正后的四组 PostgreSQL 测试实际执行 42 项并全部
  `errors=0/failures=0/skipped=0`；Maven 随后在解析上一次中断留下的截断
  `jacoco.exec` 时失败。聚焦矩阵已显式设置 `jacoco.skip=true`，覆盖率仍由后续从 clean
  状态执行的全量 `mvn test` 负责；矩阵将重新执行并以命令退出码和 XML 双重确认。
- 2026-08-26 10:53 CST：从修正后的配置重新执行 PostgreSQL 矩阵，命令以退出码 `0`
  完成；Surefire XML 再次确认 `ChatSession` 16 项、`ChatTurnOperation` 7 项、
  `NextHighValueFeatures` 10 项、`ManagedApiPrincipal` 9 项，共 42 项，全部
  `errors=0/failures=0/skipped=0`。该门槛已通过，开始执行包含 clean Maven、前端、
  Mock Playwright、双实例真实全栈与真实 LLM JSON/SSE 的统一验收。
- 2026-08-26 10:58 CST：统一验收的前 12 个步骤全部通过，但双实例合同在只读
  principal 创建处提前失败：验收脚本显式能力数组错误地把反斜杠传入 JSON，服务按
  预期返回 HTTP 400；该次运行尚未调用真实 LLM，不能作为产品失败或真实验收通过证据。
  已修正脚本为合法 JSON 数组，后续从完整验收重新开始，不沿用该次运行的全栈结果。
- 2026-08-26 11:04 CST：修正后的第二次完整验收仍未进入应用：隔离 PostgreSQL
  镜像在初始化阶段曾短暂 ready，但旧脚本的单次就绪判定在 Docker 时序下最终超时；
  手工复现确认镜像可正常启动。已将脚本改为先发现映射端口、连续 3 次确认数据库
  ready，并将有限等待窗口扩大到 180 秒；下一次从完整门禁重新执行。
- 2026-08-26 11:10 CST：第三次完整验收通过双实例后端合同和 V49 数据库事实，但
  真实 WebUI 测试在凭据摘要 `span` 与凭据列 `code` 同值时触发严格定位冲突；
  DOM 结构本身符合预期，已将测试定位收窄为凭据列的 `code` 语义角色，尚未进入
 真实 LLM 调用，后续从完整门禁重新开始。
- 2026-08-26 11:21 CST：第四次完整验收在真实 WebUI 中发现一次祖先容器定位错误；
  该次仍未进入真实 LLM，已改为按稳定行容器筛选。
- 2026-08-26 11:27 CST：第五次完整验收的 DOM 快照确认凭据列实际为 `<code>` 节点，
  但 Playwright 可访问查询不支持以 `code` 作为可靠 role；已改为行内
  `locator('code')` 精确文本匹配。此前失败运行均未调用真实 LLM，不能作为真实
  验收证据。
- 2026-08-26 11:31 CST：第六次完整验收通过全部 13 个门槛。真实双实例阶段确认：
  `grok-4.5` 经 `https://api.openai-next.com` 成功完成原生 JSON、跨实例幂等
  replay、凭据轮换后的会话连续性、原生 SSE、OpenAI 兼容 JSON 和 OpenAI 兼容 SSE；
  replay 未重复调用 provider，最终 provider 调用总数严格为 5。证据目录：
  `.verification/managed-api-principals/20260826-operation-capabilities-real7/`。
- 2026-08-26 11:34 CST：完成 `git fetch origin main` 并将最新
  `origin/main` 合并到特性分支；合并后基线仍为 `15f3b3d3`，无冲突、无新增
  上游提交。按交付规则，后续验收全部以该合并后基线重新执行。
- 2026-08-26 11:35 CST：合并后完整验收运行
  `20260826-operation-capabilities-postmerge-real8` 通过全部 13 个门槛；
  PostgreSQL、Maven clean compile/test-compile、全量 Maven、WebUI typecheck/
  Vitest/build、Mock Playwright、禁锁、文档门禁和双实例真实全栈均通过。真实
  provider 再次完成原生 JSON/SSE 与 OpenAI 兼容 JSON/SSE，幂等 replay 未重复
  调用，provider 调用总数严格为 5。最终证据目录：
  `.verification/managed-api-principals/20260826-operation-capabilities-postmerge-real8/`。
- 2026-08-26 11:44 CST：特性分支 `daa0d787` 已合并到干净的 `main` worktree，
  生成 main 合并提交；合并后的完整验收运行
  `20260826-operation-capabilities-main-final9` 通过全部 13 个门槛。该次再次
  使用真实 `grok-4.5` provider 完成原生 JSON/SSE、OpenAI 兼容 JSON/SSE、幂等
  replay、凭据轮换后的会话连续性和严格 5 次调用预算验证。证据目录：
  `.verification/managed-api-principals/20260826-operation-capabilities-main-final9/`。
- 2026-08-26 11:52 CST：main 已推送到 `8607e75c`，远端特性分支为
  `daa0d787`；两处 worktree 均干净，已使用不带 `--force` 的
  `git worktree remove` 安全移除隔离特性 worktree。
