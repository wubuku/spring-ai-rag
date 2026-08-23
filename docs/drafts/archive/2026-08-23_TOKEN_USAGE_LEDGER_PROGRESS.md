# Token 用量账本与成本估算进度

> **状态**：规划审查中，尚未修改生产代码
>
> **开始日期**：2026-08-23
>
> **当前分支**：`codex/token-usage-ledger-20260823`
>
> **当前 worktree**：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> **代码基线**：`main` / `origin/main` @ `7dc7ab9d`
>
> **实施规划**：[2026-08-23_TOKEN_USAGE_LEDGER_PLAN.md](2026-08-23_TOKEN_USAGE_LEDGER_PLAN.md)

本文件是跨会话恢复账本。每次取得关键进展时，先更新本文件，再进入下一阶段；它不替代
代码、迁移或双语长青文档。

## 1. 当前阶段

| 阶段 | 状态 | 说明 |
|---|---|---|
| 最新 main 与候选功能探索 | 已完成 | 已确认 Chat 编排/V48 已入 main；token/cost ledger 是当前真实缺口 |
| 上一轮 plan/progress 归档 | 已完成 | 已归档为 `2026-08-23_MANAGED_API_PRINCIPAL_HARDENING_*` |
| 长青事实核对 | 已完成 | V48、usage、ModelCost、Metrics 和双语 docs 已交叉核对 |
| 自包含规划编写 | 已完成 | 已冻结 V49、账本语义、API、WebUI、真实验收和交付顺序 |
| 规划连续审查 | `0/3` | 规划正文已写成，尚待三轮固定范围无修改审查 |
| 生产代码实施 | 未开始 | 规划 3/3 通过后开始 |

## 2. 已冻结决策

- 只记录完成并提交的 coordinated Chat turn；
- `rag_chat_history.id` 是账本唯一幂等键；
- native 与 OpenAI-compatible Chat 共享 `ChatSessionCoordinator` 事务；
- replay 不再次执行模型，也不增加账本；
- 缺失 usage/pricing 显式计数，不伪造 token 或供应商账单；
- API 默认当前 Principal 最近 30 个 UTC 日，最大 366 日；
- root/ADMIN 可按 principal 查询，普通调用方只能查询自己；
- V49 为 additive migration，不删除旧 history metadata；
- 不引入 billing enforcement、OAuth、租户或 embedding 用量。

## 3. 规划审查记录

规划审查计数从 `0/3` 开始。无问题轮次只在最终达到 `3/3` 后一次性补记，避免在连续
无修改证据期间改写本文件或规划正文。

## 4. 实施进度

尚未开始生产代码实施。规划通过后恢复入口：

1. 记录规划正文 SHA-256、版本工具和 `git diff --check`；
2. 先实现 normalization/cost calculator 和 V49 repository 测试；
3. 更新本文件后再接入 `ChatSessionCoordinator.commit/commitOperation`；
4. 依次完成 HTTP、WebUI、PostgreSQL、Mock、真实 provider 和最终 Git 门禁。

## 5. 实现审查记录

基本集成硬门槛通过后，按规划固定的三轮范围执行。任何实质修复都将计数重置为 `0/3`，
并在本节记录受影响的验证重跑。
