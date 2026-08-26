# 操作级 API 能力控制实施进度

> 对应规划：[2026-08-26_OPERATION_SCOPED_API_CAPABILITIES_PLAN.md](2026-08-26_OPERATION_SCOPED_API_CAPABILITIES_PLAN.md)

## 1. 恢复入口

- 任务：为数据库 API principal 增加 `RAG_READ` / `RAG_WRITE` 操作级能力，并在
  HTTP 数据面真正执行。
- 规划基线：`main` / `b755eb43`，已与 `origin/main` 对齐。
- 规划工作区：`/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
- 规划分支：`main`
- 计划实施分支：`feat/operation-scoped-api-capabilities-20260826`
- 计划实施 worktree：`/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-operation-scoped-api-capabilities`
- Flyway 基线：V1–V48；目标 V49。
- 当前阶段：规划审查已完成 `3/3`，待提交规划基线。

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
| V49 与能力规范化 | 待开始 | |
| DTO、principal snapshot、service 与响应 | 待开始 | |
| 中央 capability filter 与后端测试 | 待开始 | |
| PostgreSQL/HTTP 集成验收 | 待开始 | |
| WebUI、Mock、Vitest、Playwright | 待开始 | |
| 双语长青文档与仓库门禁 | 待开始 | |
| merge main、push、清理 worktree | 待开始 | |

## 6. 验证记录

实施前已确认：

- [x] 最新 `main` 为 `b755eb43` 且与 `origin/main` 对齐。
- [x] main 规划工作区干净。
- [x] 当前 Flyway 为 V1–V48。
- [x] 规划三轮达到 `3/3`。
- [ ] 本任务 PostgreSQL/HTTP 集成测试。
- [ ] `mvn clean compile test-compile`。
- [ ] 前端 typecheck、Vitest、production build、核心 Mock Playwright。
- [ ] 文档、锁策略、diff、密钥检查。
- [ ] merge 最新 `origin/main` 后完整复验。
- [ ] 特性分支和 main push，最终状态干净。

## 7. 已知限制

- 本轮本身不改变 Chat/LLM/provider 代码，因此真实 LLM 调用不是能力边界的必要
  证据；如联合运行触及 Chat，使用已有 Mock/HTTP 断言即可。
- PostgreSQL 集成优先使用项目既有 Testcontainers 或明确标记的可处置数据库。
- 不记录任何 API key、Token、数据库密码或包含秘密的运行日志。
