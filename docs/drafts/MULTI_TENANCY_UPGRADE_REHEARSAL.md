# 多租户迁移升级演练记录（WP2）

> 任务专属 PostgreSQL：Testcontainers `pgvector/pgvector:pg16`（真实 PG 16 + pgvector），
> 显式 opt-in `-Dtenant-base.it.enabled=true`；不连接任何共享 `*_dev` 库。
> 本文记录大表专项的锁窗口、超时兜底、失败续跑，避免日后重复排查。

## 1. 迁移拆分与事务属性

| 版本 | 内容 | 事务 |
|---|---|---|
| V75 | `rag_tenant` 主表 + `default` + 终态断言 | 事务内 |
| V76 | 45 张租户私有表加 `tenant_id NOT NULL DEFAULT 'default'` + tenant-leading 索引 + 终态断言 | 事务内 |
| V77 | `collection_key` 改 `(tenant_id, collection_key)` + 旧全局唯一清理 + 终态断言 | 事务内 |
| V78 | `rag_embeddings` 索引 tenant-leading 改造（普通索引） | 事务内 |
| V79 | 索引终态断言（含 INVALID 检测与清理指引） | 事务内 |

**方案收敛（2026-11-13 二次）**：原 V78 用 `CREATE INDEX CONCURRENTLY` + Flyway
`executeInTransaction=false`，加 `lock_timeout=30s` 后仍稳定复现「等并发事务」→ 30s 超时失败。
Flyway 非事务迁移执行连接与 schema history 更新连接之间的并发事务无法由迁移脚本自身消除。
因此 **CONCURRENTLY 退出迁移链**：迁移链一律纯事务内 DDL，保证全新库与 V74 存量库走同一条可重放路径；
在线 `CONCURRENTLY` 只由 `scripts/verify-multi-tenancy-upgrade.sh` 在生产演练库（有真实大表数据、
运维独占连接）上单独执行并单独记录锁窗口，不进 Flyway 版本链。

测试侧迁移连接仍保留 `options=-c lock_timeout=30s -c statement_timeout=900000` 作为连接级兜底。

## 2. 实测事故与根因（2026-11-13，监控方通报）

**现象**：`TenantBaseMigrationPostgresIntegrationTest` 卡死约 1 小时（实际到 394s 时被干预取消），
V78 的 `CREATE UNIQUE INDEX CONCURRENTLY` 返回 `SQL State 57014 canceling statement due to user request`。

**权威证据**（监控方在测试容器内 `pg_stat_activity` 实测）：
- pid 68：`idle in transaction` 持续 6 分钟，最后语句 `SELECT COUNT(*) FROM pg_namespace`；
- pid 69：V78 的 `CREATE UNIQUE INDEX CONCURRENTLY` 等待 `Lock/virtualxid`；
- `CONCURRENTLY` 必须等待所有并发事务结束才继续，pid 68 不提交 → 无 `lock_timeout` 兜底 → 永久等待。

**根因分类**：测试设施 / 迁移健壮性缺陷（非方案级）。不改变核心方案、范围、迁移语义或验收标准，
按规划 §10 与 PLANNING §6.2 **不触发规划检查计数归零**；但必须在 WP2 收口前修完。

**修复**：
1. 测试侧：Flyway 使用独立 `migrationDs`（单连接跑迁移），断言用的 `jdbc` 在迁移完成前不触碰 DB；
   不在迁移前预热第二个 Hikari 池 / Spring 上下文。
2. 兜底：迁移连接 URL 显式带 `lock_timeout=30s` / `statement_timeout=900000`，同类问题快速失败；
   Flyway 非事务迁移里不能放 `SET`（会被判为事务性语句并与 CONCURRENTLY 冲突）。
3. 方案收敛：CONCURRENTLY 退出迁移链，V78 改事务内普通索引。这样不再依赖「无并发开放事务」
   这一隐含前置条件，也消除 INVALID 索引与幂等重跑陷阱；在线大表索引重建由演练脚本承担。
4. V79 终态断言保留 INVALID 检测与清理指引，供在线演练脚本结果核验。

## 3. 锁窗口与分批回填

- 本次演练库为**空库**（V1–V80 全新库路径），`rag_embeddings` 行数 0，
  事务内普通索引创建为亚秒级，无回填批次、无在线锁窗口。
- WP4 新增 V81（事务内普通 `CREATE INDEX`）为 `embedding_1024` 建 HNSW；沿用本节的
  「CONCURRENTLY 不进迁移链」决策：V81 无 `executeInTransaction=false`、无 INVALID 索引风险，
  终态断言检查索引存在且非 INVALID。双路径 `--early 74` 演练应随之看到迁移历史 81。
- 生产级 V74 存量库的回填策略：按 `document_id` 分批（每批 10k 行）更新 `tenant_id`，
  每批独立提交；在线 `CONCURRENTLY` 索引重建由演练脚本在独占连接下执行，持续观察
  `pg_stat_activity` 与 `pg_locks`，记录实际锁窗口；失败后 `DROP INDEX` 清理并重建。
- **未运行 / 待 WP2 收尾**：V74 存量库升级演练（`scripts/verify-flyway-schema-paths.sh --early 74`）、
  行数守恒与双路径 schema 逐字比对；大表在有数据演练库上的实际锁窗口与每批行数记录。

## 4. 经验教训（防回归）

1. `CREATE INDEX CONCURRENTLY` 的隐含前置条件「无并发开放事务」在 Flyway 升级链里无法保证
   （schema history 连接 / 测试设施 / 运维连接都可能持有事务）；迁移链不应依赖它。
2. Flyway 非事务迁移里不能混 `SET` / `DO $$`（会被判为事务性语句并与 CONCURRENTLY 冲突）。
3. `IF NOT EXISTS` 对 INVALID 索引无效：CONCURRENTLY 失败留下的 INVALID 索引会让重跑静默跳过。
4. 框架表 `SPRING_AI_CHAT_MEMORY` 在 V2 未加引号创建，实际存储为小写
   `spring_ai_chat_memory`；迁移里必须按小写名引用（V76 实测踩过）。
5. 加 `lock_timeout` 后从「挂死 1 小时」变成「30s 快速失败并留下可诊断错误」，是有效的兜底，
   但兜底不等于修复；真正的修复是消除对并发事务缺席的隐含依赖。
