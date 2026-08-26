# Collection 创建持久化幂等性实施进度

> **状态**：规划已封板（连续 `3/3` 无修改审查通过），等待规划 checkpoint
>
> **对应规划**：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)
>
> **规划基线**：`main` / `origin/main` @ `61c728c2`（2026-08-26）
>
> **规划工作区**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
>
> **实施分支 / worktree**：规划 `3/3` 和 checkpoint push 后创建

本文是跨会话恢复账本，不是稳定架构事实。不得记录 credential、原始 idempotency key、
Authorization、完整 metadata、业务 payload、`.env` 内容或外部项目路径。

## 1. 当前状态

- [x] 上一轮 plan/progress 已按主题归档。
- [x] 上一轮功能已合入并推送 `main`，对应 feature worktree 已移除。
- [x] `main == origin/main == 61c728c2`，规划工作区干净。
- [x] 核对通用业务 Client 的 P1/P2 类生产接入缺口与当前实现。
- [x] 确认 operation-scoped capability、最小权限 principal、principal provisioning
  idempotency、capability discovery 和 Sync Run item receipt 已交付。
- [x] 选定 Collection 创建持久化幂等性作为当前最高价值缺口。
- [x] 编写自包含活动规划和本恢复账本。
- [x] 规划连续 `3/3` 无修改审查。
- [ ] 提交并推送规划 checkpoint。
- [ ] 从最新 `main` 创建专用 feature 分支与隔离 worktree。
- [ ] Slice A：公共 owner/fingerprint、配置、V52、entity/repository。
- [ ] Slice B：Collection provisioning service 与 HTTP 契约。
- [ ] Slice C：capability、PostgreSQL、双实例 HTTP 和业务 Client gate。
- [ ] Slice D：双语长青文档。
- [ ] 后端、前端、真实全栈与真实 LLM 完整验收。
- [ ] 同步最新 `origin/main` 后按新基线完整复验。
- [ ] 提交/push feature，合入/push `main`，归档文档并清理 worktree。

## 2. 已冻结的关键决策

1. 可选 `Idempotency-Key` 只作用于 `POST /api/v1/rag/collections`，不扩展 import/clone。
2. 无 header 创建继续返回 `200`；keyed 首次 `201`，exact replay `200` 并带 replay header。
3. owner 由认证上下文派生，database 使用 stable principal ID。
   root/legacy/auth-disabled 是部署级共享 owner，只有 database principal 提供逐 principal
   隔离。
4. 原始 key 不落库，只保存 SHA-256；Collection 请求只保存 canonical fingerprint。
5. V52 使用独立 `rag_collection_provisioning_operation`，不复用 principal ledger。
6. Collection 与 operation 同事务提交；唯一约束和有界新事务重读处理并发。
7. replay 返回 Collection 当前状态和当前 document count；软删除不恢复，物理缺失 fail closed。
8. replay 不重复写 create audit。
9. keyed feature disabled 或 ledger unavailable 返回 `503`，不退回普通创建。
10. capability protocol 保持 `1.0`，增加 additive
    `collectionCreateIdempotencyKey` 并保留旧 Java constructor。
11. WebUI 的 `collectionsApi.create` 每次调用生成一个 key，Axios 自动重试复用同一值；
    不新增用户输入、持久化或可见状态。
12. 实施前一次性完成 PostgreSQL、双实例/restart、ACL、故障恢复、前端和真实 provider
    验收矩阵，避免 review 阶段零碎补测试。
13. 仓库代码和文档只描述通用业务 Client 需求，不引入任何外部项目背景。

## 3. 规划审查账本

发现实质问题并修改规划时在此记录，连续计数重置为 `0`。无问题轮次不在三轮之间修改
plan/progress；达到 `3/3` 后一次性写入最终结果。

| 轮次 | 时间 | 范围 | 发现/处理 | 连续计数 |
|---|---|---|---|---:|
| 初稿 | 2026-08-26 | 当前代码、V27/V28/V50/V51、Collection API/ACL、principal provisioning、capability、测试与长青文档 | 已形成完整方案，等待固定范围审查 | 0 |
| 1 | 2026-08-26 | 需求闭环、自包含性、审计与异常恢复可实施性 | 发现现有 audit 是事务提交后的 best-effort 记录，不能宣称跨崩溃必有一条；另发现 `ON DELETE RESTRICT` 下不能在正常 PostgreSQL 中直接制造 dangling ledger。已收紧为首次路径至多一次/replay 不调用，并将异常引用验证拆为 FK 数据库测试与 service fail-closed 测试；计数重置。 | 0 |
| 2 | 2026-08-26 | API、事务、权限与现有 Client 重试行为 | 发现 WebUI Axios 对网络错误和 5xx 自动重试 POST；若 WebUI 保持无 header，后端能力无法解决其响应丢失问题。已把每次 create 调用生成一次 key、同次 Axios retry 复用、后续提交换 key及网络断言纳入范围；计数重置。 | 0 |
| 3 | 2026-08-26 | owner 安全边界与多调用方隔离 | 发现规划只列 owner 映射但未明确 root、legacy 和 auth-disabled 都是部署级共享作用域，容易被误读为逐调用者隔离。已补充隔离强度、生产限制和必须使用 database principal 证明不同 owner 的验收方式；计数重置。 | 0 |
| 4 | 2026-08-26 | schema、事务、cleanup 与运行配置可实施性 | 发现规划要求 scheduled cleanup，却没有冻结调度间隔，会形成未文档化的隐式运维参数。已增加默认一小时、范围 10 秒至 24 小时的 `cleanup-interval-ms` 与环境变量，并纳入属性测试；计数重置。 | 0 |

最终连续三轮在封板前保持 plan/progress 哈希不变：

| 连续轮次 | 时间 | 固定范围 | 发现问题 | 处理措施 | 结果 |
|---|---|---|---|---|---|
| 1/3 | 2026-08-26 | 需求闭环、自包含性、推荐默认、非目标、通用 Client 表述 | 无 | 无修改 | PASS |
| 2/3 | 2026-08-26 | Java/Spring 事务、PostgreSQL 约束、owner、Axios retry、capability、锁策略 | 无 | 无修改 | PASS |
| 3/3 | 2026-08-26 | 验收矩阵、故障注入、启动、发布/回滚、双语文档、Git/worktree | 无 | 无修改 | PASS |

封板前哈希：

```text
plan     b9b5dc25759b107f912b8b116d54e046c8e77cdd0a17832a5a157b15353e13b2
progress dce13f02913fd506099477cb9ba2f529fbf25bd27fc03b0867a7a681e188a140
```

## 4. 验证账本

| 时间 | 阶段 | 命令/范围 | 结果 | 证据 |
|---|---|---|---|---|
| 2026-08-26 | 基线 | `git status`、`main`/`origin/main`、worktree list | PASS | `61c728c2`，规划 worktree 干净 |
| 2026-08-26 | 需求探索 | Collection controller/service/entity/repository、V27/V28、V50 ledger、capability DTO/catalog、P1/P2 接入缺口、现有 PostgreSQL/HTTP gates | PASS | 当前代码、迁移、测试和双语长青文档 |

## 5. 恢复入口

下一步运行文档、锁策略、diff 和密钥门禁，提交并推送规划 checkpoint，再从最新 `main`
创建隔离 feature worktree。生产代码尚未修改。
