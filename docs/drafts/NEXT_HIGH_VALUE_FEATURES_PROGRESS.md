# 下一轮高价值功能规划进度

> **状态**：规划完成，待用户审阅；尚未进入实施
>
> **当前分支**：`main`
>
> **当前 worktree**：`/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
>
> **规划基线**：`main` / `origin/main` @ `05a21706`
>
> **实施规划**：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)（待编写）

本文件是跨会话恢复账本，不替代代码与双语长青文档。每次取得关键进展时先更新本文件，
再进入下一阶段。

## 1. 当前阶段

| 阶段 | 状态 | 说明 |
|---|---|---|
| main 规划基线 | 已完成 | 按用户约定直接在最新本地 `main @ 05a21706` 的专用 main worktree 规划；实施阶段再建立隔离特性 worktree |
| 上一轮规划归档 | 已完成 | Chat turn 幂等 plan/progress 已归档为 `2026-08-22_*`，长期事实已在上一轮交付时同步至长青文档 |
| 代码与文档探索 | 已完成 | 已交叉核对 API Key、认证/ACL、principal owner、限流、迁移、两种装配拓扑、WebUI 与现有测试 |
| 功能筛选与方案冻结 | 已完成 | 本轮冻结为稳定受管 principal、版本化凭据、即时跨实例撤销与 PostgreSQL 共享配额 |
| 自包含规划编写 | 已完成 | 已冻结 V48、认证/ACL、管理 API、CAS、共享 quota、WebUI、rollout 与一次性验收矩阵 |
| 双语长青文档同步 | 已完成 | TODO/readiness 中英文已同步当前缺口、优先级与未实施边界 |
| 规划连续审查 | 最终收敛 | 状态元数据已冻结；连续轮次只在执行输出留证，达到 `3/3` 后不再修改文档 |
| Git 交付 | 未开始 | 规划完成后 commit、merge 最新远端分支变化、push，并确认工作区干净 |

## 2. 已确认事实

- 原始工作区仍在 `docs/chat-context-tool-orchestration-plan-20260821` 且干净，本轮不在该
  老分支叠加修改；规划工作在独立的 main worktree 进行。
- 最新本地 `main` 与规划开始时的 `origin/main` 均为 `05a21706`。
- 上一轮 Chat turn 幂等能力已合并、完整验收并推送；本轮不得重复规划已交付能力。
- 本轮只做规划、规划审查、必要的长青文档同步和 Git 交付，不修改生产代码、不开始实施。
- 当前 `RagApiKey` 同时承载 credential、role、Collection ACL 与 expiry；rotation 禁用旧
  `keyId` 后创建新的独立 Key，且 legacy rotation 会把 role 重置为 `NORMAL`。
- Chat history/memory/turn operation、evaluation suite/run、retrieval diagnostics 和多项持久化
  operation 使用 `db:{keyId}` 作为 owner。轮换改变 `keyId`，会切断同一调用方的 owner
  namespace；对已经发生的历史轮换不存在可靠的自动 family 推断方法。
- 认证存在 30 秒进程内正向缓存，吊销只清理当前 JVM；`last_used_at` 每次认证同步写库。
  当前限流是本进程 `ConcurrentHashMap`，多副本会放大 quota，legacy fallback 还可能把
  raw header 当 limiter identifier。
- V23 仍允许 `rag_api_key.api_key` 明文列和索引，尽管当前 service 不写该字段。
- `ApiKeyCollectionAccess`、Controller、异步 evaluation worker 与 Chat replay 授权广泛依赖
  `RagApiKey` 实体；实施必须一次性迁移到不可变认证 principal/policy snapshot，不能只改
  Chat principal 字符串。
- 两种运行拓扑必须共同覆盖：standalone core 负责认证装配，starter 负责限流装配；当前
  root-mode Web 集成测试仍以 mocked management service 为主，缺少真实 PostgreSQL、双实例
  撤销与共享 quota 证据。

## 3. 功能选择结论

### 3.1 本轮选中

**稳定受管 API principal 与多实例配额加固**：把长期 owner/policy 从可轮换 credential
中分离；引入版本化 credential family、无正向认证缓存的即时吊销、低写放大的使用时间、
PostgreSQL 原子共享 quota，以及与之配套的管理 API、WebUI 和跨实例验收。

选择理由：这是外部 Client 在生产环境长期调用 RAG 服务时的身份连续性、数据隔离和成本
安全基础，也直接修复现有 readiness 文档已明确列出的公开/多实例启用前置缺口。它复用
项目现有 PostgreSQL、条件 DML/CAS、唯一约束和 root-managed Key 边界，不引入 Redis、
OAuth 或新的身份体系。

### 3.2 本轮不选

- **Collection 级多 embedding profile 路由**：价值高，但当前写入、检索、job、readiness
  和完整性诊断都读取单一 active profile；实现还需模型 factory、向量空间分组检索、
  Collection profile 迁移和重嵌入协议，应独立规划。
- **`EACH_COLLECTION`**：正式 TODO 已冻结为无目标版本的非紧急 backlog，缺少明确覆盖率
  产品需求与质量证据。
- **继续扩展 Chat 编排**：会话摘要、工具预算、durable turn 和真实 provider 验证均已
  在前两轮交付；当前外部生产安全收益低于 principal/credential 基础加固。

## 4. 下一步

1. 按固定且互不重叠的范围执行连续三轮无修改审查；发现实质问题则修复并重置计数。
2. 运行文档门槛，合并最新 `origin/main` 变化，commit/push，并确认 `main` 工作区干净。

## 5. 规划审查记录

| 时间 | 范围 | 发现 | 处理 | 连续无修改计数 |
|---|---|---|---|---|
| 2026-08-23 12:02 CST | 事实、范围与代码交叉验证预检 | 重复 revoke 的“current”判定未冻结；`RagApiKey` 引用审计范围过宽 | 固定以 `nextCredentialVersion - 1` 判定最后一版；允许实体用于 credential persistence/management，但禁止进入数据面 ACL/request context | 重置为 `0/3` |
| 2026-08-23 12:05 CST | 状态元数据收尾 | 正文已通过首个 `3/3`，但 header/table 仍写“审查中/未开始” | 只更新最终状态；按最严格规则重置，随后重新执行三轮固定范围只读审查 | 重置为 `0/3` |

连续无问题轮次只在执行输出中留存总结，不修改本文件，避免破坏“连续三轮无修改”的终止
条件。若后续发现实质问题并修改，则在这里追加问题轮并重新从 `0/3` 开始。
