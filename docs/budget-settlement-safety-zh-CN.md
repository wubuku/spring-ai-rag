# LLM 预算不确定结算与成本安全

> [English](budget-settlement-safety.md) | [中文](budget-settlement-safety-zh-CN.md)

## 1. 默认行为

LLM 预算在调用 provider 前持久化预留，日/月额度跨实例共享；默认启用，普通 Chat 不增加额外
模型调用。预算不是供应商账单，配置价格和 tokenizer 估算不构成供应商实际消费的绝对上界。

TTL 到期不能证明上游未消费。进程崩溃、超时或清理线程先到时，预留进入 `EXPIRED`，估算
从 reserved 转入 settled，**不退款**。首个迟到结算可把 `EXPIRED` 转为 `SETTLED`，按真实
usage 与原估算之差校正原 UTC 日/月窗口；未知 usage 沿用估算。重复结算无副作用，CAS
竞争最多重试三次。原回答不被记账故障替换；未结算的预留由清理任务保守收敛。

`release` 仅用于明确尚未调用 provider 的预留，不能作为超时或取消的通用退款动作。
显式释放、已结算的记录不会被 TTL 或迟到回调重复扣款。清理后的历史回调不可再结算；
默认保留30天，超长迟到和已删除账本不能被伪造为实际 usage。
终态保留期清理修复了保留字SQL别名；活跃预留、其窗口以及未过期记录继续保留。

## 2. 成本约束

配置 `daily-cost-limit`、`monthly-cost-limit`、`max-request-cost` 中任一个时，模型必须具有
明确、有限、非负的输入/输出价格；缺失或非法价格在调用上游前返回既有
`503 LLM_BUDGET_SERVICE_UNAVAILABLE`。明确的 `0` 价格表示免费，不等于缺失价格。
未配置费用上限的 token-only 部署继续无需价格。超出 token/cost 上限沿用
`429 LLM_BUDGET_EXCEEDED`；流式响应继续使用原有 error 和终态协议。

同一 logical execution/call ordinal 只创建一个 reservation，并核验 owner 与估算不可变。
占位、日/月额度检查在同一事务中，超限回滚占位，不发生重复预留或跨 owner 复用。

## 3. 自动迁移与兼容

Flyway V66 自动回填仍保留的旧 `EXPIRED` 记录：旧程序曾退款的估算计入原窗口 settled，
支持窗口不存在时创建；SETTLED/RELEASED/RESERVED 不变。迁移执行一次，无手工补数据。
已被旧版本删除的账本无法重建，不声称已恢复。

此次修改不增加或移除 API 字段，不改 HTTP 路由、既有 JSON envelope、SSE 顺序、finishReason
或公开 Java 构造器。唯一策略收紧是有费用上限时不再把未知价格当零成本放行。
发布采用停止旧实例后启动新实例的迁移方式，不能让旧版清理 worker 与新版共用库并行退款。
回退旧程序不会破坏数据结构，但会恢复旧成本风险，建议前向修复；不要手工反向退款。

## 4. 验证入口

- `scripts/verify-gated-it.sh LlmBudgetAndGovernancePostgresIntegrationTest`：真实 PostgreSQL、
  V65→V66、fresh/repeated migrate、并发 reserve、TTL/迟到结算竞争、原窗口归因和价格策略。
- `python3 scripts/verify-budget-settlement-e2e.py`：隔离数据库、真实 HTTP、受控 provider，
  Native/OpenAI JSON/SSE、零调用拒绝、在途 TTL、幂等 replay、SIGKILL 与重启。
- [测试指南](testing-guide-zh-CN.md) 与 [配置参考](configuration-zh-CN.md)：全面 Client/SDK、
  真实模型和 `scripts/dev.sh` 验收；受控 provider 不代表真实模型质量。
