# Sync Run 持久化 item receipt 与游标状态查询实施进度

> **状态**：规划审查 `3/3` 通过，等待隔离 worktree 实施
>
> **对应规划**：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)
>
> **规划基线**：`main` / `origin/main` @ `67f69bfe`（2026-08-26）
>
> **规划工作区**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`

本文是跨会话恢复账本，不是稳定架构事实。不得记录 raw credential、cursor、externalId、
完整错误、业务 payload、Authorization、API Key、`.env` 内容或外部项目路径。

## 1. 当前状态

- [x] 上一轮 plan/progress 已按主题归档。
- [x] 已确认上一轮 feature 合入并推送 `main`，隔离 worktree 已清理。
- [x] 已核对通用外部 Client P1 类需求均已交付。
- [x] 已探索 JSON Record batch、Document Sync Run、embedding job、ACL 和 capability 现状。
- [x] 已选定 durable Sync Run item receipt/status 查询作为下一轮高价值功能。
- [x] 已编写自包含实施规划。
- [x] 规划连续 `3/3` 无修改审查。
- [ ] 提交并推送规划 checkpoint。
- [ ] 创建最新 `main` 基础的隔离特性 worktree。
- [ ] Slice A：DTO、cursor 和 capability contract。
- [ ] Slice B：V51、repository、service/controller。
- [ ] Slice C：PostgreSQL/HTTP/权限/真实全栈验收。
- [ ] Slice D：双语长青文档。
- [ ] 基本硬门槛、实现 `3/3`、同步 `origin/main` 后最终完整复验。
- [ ] 推送特性分支、合入并推送 `main`、清理 worktree。

## 2. 已冻结的关键决策

1. 扩展已有 Sync Run ledger，不创建第二套通用 mutation operation 表。
2. 新增 `GET /document-sync-runs/{runId}/items`，需要 Collection binding，不需要 lease。
3. GET 由中央 capability filter 要求 `RAG_READ`。
4. 使用 `seen_at + external_id` opaque keyset cursor；terminal 稳定，active 仅作最终一致观察，
   并在进入终态后从无 cursor 起点复扫。
5. response 不返回 fingerprint、payload、metadata、lease/hash 或 credential。
6. `currentSummary` 从 item ledger 实时聚合，不重定义既有累计 run counters。
7. V51 只新增 cursor/status 索引。
8. receipt 不代表 embedding readiness；派生状态继续使用 lifecycle/readiness/job API。
9. 新错误写入与即时 batch response 先 masking 再截断；receipt 读取再次 masking，兼容
   V42 以来可能存在的历史未脱敏错误。
10. cursor 使用 Jackson JSON 并绑定 run/status；先做 Collection/run authorization，再解码
    cursor。V51 普通索引迁移安排维护窗口并观察 PostgreSQL lock wait。
11. capability protocol 保持 `1.0`；`optional` 新字段是旧 Client 必须忽略的 additive
    JSON 扩展，并保留旧双参数 Java constructor。
12. filtered/unfiltered page 使用两条固定参数化 SQL，避免 nullable status OR 影响 V51
    索引计划。
13. Sync Run HTTP acceptance 改为认证模式，用临时 root 创建 restricted read-write/read-only
    principal 验证权限矩阵；真实全栈固定运行 business-client readiness 与
    managed-principal `--with-real-llm`。

## 3. 规划审查账本

发现实质问题并修改规划时记录在这里并把计数重置为 `0`。无问题轮次只在会话输出中总结；
达到连续 `3/3` 后一次性记录最终结果。

| 轮次 | 时间 | 范围 | 发现/处理 | 连续计数 |
|---|---|---|---|---:|
| 1 | 2026-08-26 22:08 CST | 需求闭环、自包含性、默认决策和非目标 | 发现 `seen_at` 不能保证并发事务提交顺序，active cursor 不应宣称 at-least-once 或不漏项。已收紧为 eventually-consistent 观察，并要求 terminal 后从头稳定复扫；计数重置。 | 0 |
| 2 | 2026-08-26 22:10 CST | 规划与恢复账本交叉一致性 | 发现进度账本的冻结决策仍残留 active cursor `at-least-once` 表述，与规划正文冲突。已统一为最终一致观察和终态后从头复扫；计数重置。 | 0 |
| 3 | 2026-08-26 22:12 CST | cursor 安全语义与可实施性 | 发现 Base64 只能隐藏内部结构，不能保护 `externalId` 的机密性。已明确 opaque 不等于加密、cursor 可解码且必须按业务敏感数据处理，并禁止承载任何 secret；计数重置。 | 0 |
| 4 | 2026-08-26 22:16 CST | ledger error 安全事实与公开读取边界 | 发现现有 `error_message` 只截断、不保证脱敏，规划原先错误假设它已 masking。已冻结新写入与即时响应统一 masking，并在 receipt 读取时再次 masking 以兼容历史行；计数重置。 | 0 |
| 5 | 2026-08-26 22:17 CST | cursor binding、授权顺序与迁移风险 | 发现 cursor 未显式绑定 run、解码先于 ACL 会造成差异化错误，且普通索引的写阻塞风险未写入发布边界。已改为 Jackson JSON 绑定 run/status、授权后解码，并冻结维护窗口与锁等待处置；计数重置。 | 0 |
| 6 | 2026-08-26 22:19 CST | capability 版本与旧 Client 兼容 | 发现“新增字段但保持 `1.0`”缺少兼容规则；直接升 `1.1` 又会破坏现有指南要求精确 `1.0` 的 Client。已冻结为保持 `1.0`、旧 Client 忽略未知 optional field，并增加旧 constructor/JSON 序列化兼容测试；计数重置。 | 0 |
| 7 | 2026-08-26 22:20 CST | PostgreSQL 查询计划与索引匹配 | 发现 nullable status OR 可能削弱 status cursor 索引的可预测性。已冻结 filtered/unfiltered 两条固定参数化 SQL，分别匹配两个 V51 索引；计数重置。 | 0 |
| 8 | 2026-08-26 22:25 CST | HTTP 权限验收与真实运行门禁 | 发现现有 Sync Run 脚本关闭认证，无法证明规划中的 `RAG_READ`/ACL 合同。已冻结临时 root + restricted read-write/read-only principal 的真实 HTTP 矩阵，并写明 business-client、managed-principal 与真实 LLM 命令；计数重置。 | 0 |

## 4. 验证账本

| 时间 | 阶段 | 命令/范围 | 结果 | 证据 |
|---|---|---|---|---|
| 2026-08-26 22:00 CST | 下一轮探索 | main/远端状态、V42/V50、Sync Run service/schema/script、JSON Record batch、embedding jobs、capability endpoint、P1/P2 通用缺口 | PASS | 本地代码、迁移、测试与长青文档 |
| 2026-08-26 22:28 CST | 规划最终审查 | 需求闭环 → schema/SQL/cursor/ACL/兼容 → 测试/发布/回滚/Git；最终 plan/progress SHA-256 分别为 `8a6b973e...` / `bf7ba555...` | PASS（连续 `3/3` 无修改） | 三轮会话审查输出与固定文件哈希 |

## 5. 恢复入口

规划已达到连续 `3/3`。下一步运行文档门禁，commit/push main 规划 checkpoint，再从该
最新 main 创建隔离特性 worktree，按 Slice A→D 实施。
