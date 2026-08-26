# 托管调用方幂等 provisioning 与运行时能力发现实施进度

> **状态**：规划已完成，待实施
>
> **对应规划**：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)
>
> **规划基线**：`main` / `origin/main` @ `0abc667e`（2026-08-26）
>
> **实施分支**：`feat/managed-provisioning-capability-discovery-20260826`
>
> **实施 worktree**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-managed-provisioning-capability-discovery`

本文是跨会话恢复账本，不是稳定架构事实。不得记录 raw credential、完整
`Idempotency-Key`、Authorization、API Key、完整请求体、业务 payload 或外部项目路径。

## 1. 当前状态

- [x] 已核对上一轮归档规划，确认 provisioning 幂等和 capability discovery 是明确的
  后续独立缺口。
- [x] 已核对当前 `main`、`origin/main`、工作区和 worktree；当前规划工作区干净且同步。
- [x] 已核对 V48 明文 credential 禁止约束，冻结本轮不保存/不重放 raw secret。
- [x] 已核对现有 `IdempotencyKeyValidator`、Chat 幂等错误码、API principal 认证和
  WebUI create retry 相关实现。
- [x] 完成本规划三轮无修改审查。
- [x] 提交并推送规划，建立保护 checkpoint。
- [x] 创建最新 `main` 基础的隔离特性 worktree。
- [ ] 实施 API、迁移、测试、脚本和文档。
- [ ] 完成基本硬门槛和必要真实服务验收。
- [ ] 完成实现三轮无修改审查。
- [ ] 合并、推送 `main`、确认干净并移除特性 worktree。

## 2. 已冻结的关键决策

1. `Idempotency-Key` 可选；有 key 时 fail closed，不能在账本不可用时静默走非幂等路径。
2. 幂等 owner 是请求实际认证 principal 的稳定身份；root 使用固定
   `root:environment-root`。
3. 数据库只保存 key hash、请求 fingerprint hash 和结果 metadata，绝不保存 raw secret。
4. 首次 keyed create 返回 `201 + rawKey`；精确 replay 返回 `200 + rawKey:null`、
   `secretAvailable:false`、`idempotentReplay:true` 和 replay header。
5. 仍使用唯一约束/CAS/条件写入，不使用显式悲观锁、`SKIP LOCKED` 或 advisory lock。
6. capability discovery 需要认证，返回版本化、低敏、与当前 principal projection 对齐
   的能力合同；ACL 无法完整解析时 `503`。
7. 本轮不改变 Chat/LLM 行为；真实 LLM 仅在既有全量 gate 适用时执行，不作为本轮
   provisioning 正确性替代证据。

## 3. 规划审查账本

| 轮次 | 时间 | 范围 | 发现/处理 | 计数 |
|---|---|---|---|---:|
| 1 | 2026-08-26 19:09 CST | 需求闭环、自包含性、默认决策与非目标 | 发现幂等 owner 未覆盖 legacy static/auth-disabled；未明确 Collection key 与 numeric ID 的等价请求必须共享解析后内部 ID 指纹。已记录并修正规划，计数重置。 | 0 |
| 1（复查） | 2026-08-26 19:13 CST | 需求闭环、自包含性、默认决策与非目标 | 发现目标摘要仍遗漏新增的兼容 owner 映射，已修正规划，计数继续为 0。 | 0 |
| 1（复查 2） | 2026-08-26 19:16 CST | 需求闭环、自包含性、默认决策与非目标 | 发现默认表、V50 字段说明和真实合同用例仍残留不完整 owner 摘要，已修正规划，计数继续为 0。 | 0 |
| 2 | 2026-08-26 19:24 CST | 代码、schema、API、安全、并发和兼容可实施性 | 发现 retention 到期后的幂等保证边界未明示；已补充保证期限和调用方约束，计数重置。 | 0 |
| 2（复查） | 2026-08-26 19:31 CST | 代码、schema、API、安全、并发和兼容可实施性 | 发现 principal/credential ID 既有兼容约定、bindingPreflight 语义和四类 capability projection 未冻结；已记录并修正规划，计数重置。 | 0 |
| 2（复查 2） | 2026-08-26 19:37 CST | 代码、schema、API、安全、并发和兼容可实施性 | 发现 capability feature flag 字段和 replay 在 rotation/revoke 后的 current credential response schema 未定义；已记录并修正规划，计数重置。 | 0 |
| 2 | 2026-08-26 | 代码、schema、API、安全、并发和兼容可实施性 | 对照现有 JPA/JDBC 事务、V48/V49 约束、认证与 capability filter、配置绑定、错误处理和 PostgreSQL 测试模式，未发现实质问题。 | 2 |
| 3 | 2026-08-26 | 实施顺序、验收矩阵、发布、回滚、文档和交付风险 | 对照验收矩阵、服务启动、前端门禁、文档/锁/密钥检查、发布回滚、Flyway 兼容和 worktree 交付顺序，未发现实质问题。规划达到连续 3/3。 | 3 |

## 4. 验证账本

| 时间 | 阶段 | 命令/范围 | 结果 | 证据 |
|---|---|---|---|---|
| 2026-08-26 | 规划前探索 | 当前 main、V48/V49、principal/service/controller/repository、历史 plan/TODO、WebUI retry | PASS | 本地代码与归档文档 |

## 5. 恢复入口

规划已完成连续 `3/3` 无实质问题审查。下一步提交并推送规划，建立保护 checkpoint，
然后基于最新本地 `main` 创建专用特性分支和隔离 worktree。实施时每个关键切片先更新
本进度文档；若实现阶段发现影响正确性、成本安全、兼容性或数据一致性的实质问题，修复
代码后重跑受影响门槛，并将实现审查计数重置为 `0`。
