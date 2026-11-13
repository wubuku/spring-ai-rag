# 多租户迁移升级演练记录（WP2 基线 + WP11 收口）

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
### 3.1 WP11 实测：V1–V84 双路径升级演练（2026-11-14）

本机 PostgreSQL 16.8（`localhost:5432`，脚本自建自删 `rag_flyway_*` 临时库）执行：

```bash
PATH="/usr/local/opt/libpq/bin:$PATH" ./scripts/verify-flyway-schema-paths.sh --early 74 --keep
```

**结果：PASS**（日志目录保留于 `/tmp/rag-flyway-verify.48XOOG` 供本次任务取证；不在仓库内留产物）：

| 断言 | 结果 |
|---|---|
| 路径 A：空库 → V84 全量迁移 | PASS，`Successfully applied 84 migrations` |
| 路径 B：空库 → V74 → 写入 `flyway-early-marker` → V84 增量迁移 | PASS |
| V74 标记行增量升级后仍在 | PASS（`count=1`） |
| 两条路径 `pg_dump --schema-only` 归一化逐字比对 | PASS（2293 行归一化 DDL 一致） |
| `flyway_schema_history` 成功迁移条数一致 | PASS（**84**） |
| V84 库再次 `migrate` 重跑不破坏数据 | PASS |

结论：V75–V84 全部为事务内可重放 DDL，**全新库与 V74 存量库收敛到同一 schema**；
WP2 的 V78 `CONCURRENTLY` 挂死事故不再存在于版本链。`docs/drafts/MULTI_TENANCY_IMPLEMENTATION_PROGRESS.md` §2a/§2b
记录的 WP2/WP3 历史演练（迁移历史 79/80）已被本次 V84 演练覆盖，不删除历史事实。

**仍未运行 / 不冒充已验**：有真实大表数据（百万级 `rag_embeddings`）的 V74 演练库上的
在线 `CONCURRENTLY` 锁窗口、每批 10k 行回填实测。本任务按规划 §7 的「真实依赖」门禁要求使用
PostgreSQL 16 + pgvector 可处置测试库；V74 双路径 schema/数据守恒已验，生产级大表在线索引重建
需运维独占连接与真实数据量，列为上线前专项演练，不以空库亚秒级结果外推。

## 4. 经验教训（防回归）

1. `CREATE INDEX CONCURRENTLY` 的隐含前置条件「无并发开放事务」在 Flyway 升级链里无法保证
   （schema history 连接 / 测试设施 / 运维连接都可能持有事务）；迁移链不应依赖它。
2. Flyway 非事务迁移里不能混 `SET` / `DO $$`（会被判为事务性语句并与 CONCURRENTLY 冲突）。
3. `IF NOT EXISTS` 对 INVALID 索引无效：CONCURRENTLY 失败留下的 INVALID 索引会让重跑静默跳过。
4. 框架表 `SPRING_AI_CHAT_MEMORY` 在 V2 未加引号创建，实际存储为小写
   `spring_ai_chat_memory`；迁移里必须按小写名引用（V76 实测踩过）。
5. 加 `lock_timeout` 后从「挂死 1 小时」变成「30s 快速失败并留下可诊断错误」，是有效的兜底，
   但兜底不等于修复；真正的修复是消除对并发事务缺席的隐含依赖。


## 5. SINGLE → STRICT 切换、回退与备份恢复（WP11）

> 本节是执行语义说明，不新增运行模式；权威配置与失败语义见
> [多租户化实施规划](MULTI_TENANCY_IMPLEMENTATION_PLAN.md) §8 与
> [配置文档](../configuration-zh-CN.md) / [配置文档 EN](../configuration.md)。

### 5.1 切换前置条件（SINGLE 默认 → STRICT 隔离环境）

`rag.tenant.mode=SINGLE` 固定 `default` 租户，不要求 RLS verifier 全绿；切到 `STRICT` 前必须同时具备：

1. 凭据已绑定租户：`rag_api_key` / `rag_api_principal` 的 `tenant_id` 与租户目录一致；
   非 `default` 凭据是 STRICT 的正常形态，但在 SINGLE 下是**启动拒绝条件**。
2. 数据库应用角色为 `NOSUPERUSER NOBYPASSRLS`，且不继承任何 `BYPASSRLS` 平台角色；
   `TenantRlsVerifier` 在 `TenantModeGuard` 的 STRICT 分支启动时逐项校验并 fail-closed。
3. 所有 `TENANT_PRIVATE` 表 `ENABLE + FORCE ROW LEVEL SECURITY`，policy 绑定
   `app.current_tenant_id` GUC；V80/V82/V84 终态断言与 `scripts/verify-multi-tenancy-rls.sh` 覆盖。
4. 请求链路使用 session 级 GUC 三件套（不是 transaction-local），非事务自动提交路径同样有效；
   `scripts/verify-multi-tenancy-guc.sh` 与 PG 回读测试覆盖。
5. 先完成 V74 → 最新版本的升级演练（§3.1）与备份点（§5.3），再切模式；切换本身不执行数据迁移。

### 5.2 STRICT → SINGLE 回退：fail-closed 不是「只改一个配置」

`TenantModeGuard` 在 `SINGLE` 启动时校验所有凭据必须 `tenant_id='default'`。若 STRICT 期间创建了
B 租户及其非 `default` 凭据，直接把 `rag.tenant.mode` 改回 `SINGLE` 会**拒绝启动**——这是正确的
fail-closed，防止以单租户 fixed-default 身份读到多租户数据。因此回退前置条件二选一：

- **清理路径**：按受控、审计的流程停用/清理非 `default` 凭据与租户绑定（不删除业务数据行），
  确认凭据表全为 `default` 后再切回 `SINGLE` 并使用原应用角色；
- **恢复路径**：从 SINGLE 兼容的备份点重建库（见 §5.3），不回滚已执行 Flyway 迁移、不删
  `tenant_id` 列/约束。

两种路径都要保留 `rag_api_key_rotation` / `rag_api_provisioning_operation` 等审计事实，不能用
`TRUNCATE` 抹掉凭据生命周期记录。回退后 STRICT 专属角色/RLS 可保留（SINGLE 不依赖它们），
但应用连接仍不得使用 superuser / BYPASSRLS 身份。

### 5.3 备份、恢复与 RPO 边界

- 权威流程：[备份与恢复（中文）](../backup-restore-zh-CN.md) / [Backup & Restore (EN)](../backup-restore.md)，
  演练脚本 `scripts/verify-backup-restore-drill.py`。
- `scripts/rag-backup.py` 是主动执行的 PostgreSQL **逻辑备份**（`pg_dump` custom archive，含 schema /
  数据 / 序列 / 扩展声明），不是物理卷快照、增量备份、PITR 或密钥托管；正常启动不自动导出。
- 备份/恢复前需**停写并确认写入静默**（quiesced writes），否则逻辑快照不保证跨表一致性边界；
  文档明确「不构成 RPO=0」。按备份频率定义 RPO、按演练测量 RTO，不把单次测试耗时当生产 SLA。
- 升级不可逆点（`tenant_id` 回填、复合唯一、外键重建、角色切换、旧列删除）前必须留备份点；
  本规划第一版**不删旧列**，遗留无归属行沿用 V71 fail-closed 语义。
- 多租户回退不要手工把未完成 reservation 改成已结算；恢复目标库保持隔离，明确恢复点。

### 5.4 WP11 红队与启动守卫取证

红队 10 维证据矩阵由 `scripts/verify-multi-tenancy-redteam.sh` 静态钉死（已登记
`scripts/verify-project-docs.sh`）；真实数据库负向由 opt-in 集成测试承担：

| 维度 | 真实 PG / HTTP 证据 |
|---|---|
| 函数体 / trigger 断裂 | `TenantRlsVerifierPostgresIntegrationTest#triggerFunctionBodyBreakIsRejected`、`#missingImmutableTriggerIsRejected`（带 `restoreImmutableFunction` 自清理） |
| policy 恒真 / BYPASSRLS / 漏表 / 宽松平台角色 / SINGLE 非 default 凭据 | 同类 `#permissivePolicyIsRejected` … `#singleModeWithNonDefaultCredentialsIsRejected` |
| 无 GUC 读 0 行 | `JdbcTenantRlsVerifier.verifyNoGucReadsZeroRows` + 双租户隔离 PG IT |
| header 伪造 / 异步上下文 / SSE 撤权 / 任意 task ID | 对应 `TenantHeaderForgeryTest`、`TenantAfterCommitContextTest`、`TenantWp8ContractTest` 与红队门禁映射 |

启动 verifier 被关闭的风险由 `TenantModeGuard` 唯一委托点 + RLS 静态门禁双重守住；
MCP 不在当前实现面，不设无法验证的 MCP 验收项。
