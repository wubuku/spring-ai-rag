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
在线 `CONCURRENTLY` 演练不进 Flyway 版本链，留作上线前运维专项演练。当前已落盘
`scripts/verify-p05-online-concurrent-index.py` 与一键编排
`scripts/verify-p05-pg-benchmark.sh`；双路径 schema 一致性由
`scripts/verify-flyway-schema-paths.sh --early 74` 承担。

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
合并后新增的 `V85__tenant_receipt_default_and_global_scope_correction.sql` 同样为纯事务内 DDL
（已由 `TenantBaseMigrationPostgresIntegrationTest` 5/5 与 9 组 STRICT 集成 44/44 验证）。

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
   `app.current_tenant_id` GUC；V80/V82/V84/V85 终态断言与 `scripts/verify-multi-tenancy-rls.sh` 覆盖。
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

## 6. P0-5 大表专项实测（2026-11-28）

> 本节是**真实实测记录**，不是估算。所有数据来自一次性 Docker 容器
> `pgvector/pgvector:pg16`（PostgreSQL 16.15 + pgvector 0.8.7），
> 容器由脚本自建自删，未连接任何共享 dev 库。
> 一键复现：`scripts/verify-p05-pg-benchmark.sh`（拆分为 Python 建语料 + 容器内 psql 实测 SQL）。

### 6.1 环境与脚本

| 项 | 实测值 |
|---|---|
| 镜像 | `pgvector/pgvector:pg16`（`docker images` 本地已有；pgvector `default_version = 0.8.7`） |
| PostgreSQL | `16.15 (Debian 16.15-1.pgdg12+2)` |
| pgvector | `0.8.7`，`hnsw.iterative_scan` GUC 存在且可设置（**支持 0.8 GUC，无需拉新镜像**） |
| 宿主 | macOS / OrbStack；宿主 → 容器 bridge IP 长连接不稳定，因此长查询改为 `docker exec` 容器内 `psql` |
| 建语料脚本 | `scripts/verify-p05-hnsw-tenant-recall.py --prepare-only ...` |
| 召回压测 SQL | `scripts/verify-p05-hnsw-tenant-recall.sql`（容器内执行） |
| 强制 HNSW SQL | `scripts/verify-p05-hnsw-tenant-recall-forced.sql`（容器内执行） |
| 在线演练脚本 | `scripts/verify-p05-online-concurrent-index.py` |
| 一键编排 | `scripts/verify-p05-pg-benchmark.sh` |

### 6.2 30 万行非均匀语料 + HNSW Recall@10 / P95

语料：`rag_documents` / `rag_embeddings`，1024 维 `vector_cosine_ops`，
HNSW `m=16, ef_construction=64`（与 V81 一致）。
租户分布参数 `--shares 80,15,5`，实测行数 240,000 / 45,000 / 15,000。
每租户 50 条 query（取真实行向量 + 小扰动），`hnsw.ef_search=40` 固定，
真值由 `SET enable_indexscan=off; SET enable_bitmapscan=off` 的精确顺序扫描计算。
HNSW 索引构建耗时 **529.7s**（本机 14 核）。

**场景 A：租户 B-tree 索引存在（与当前 V76/V78 schema 一致）**

`EXPLAIN (ANALYZE, BUFFERS)` 显示规划器选择
`Index Scan using idx_rag_embeddings_tenant_leading` + top-N heapsort，**没有走 HNSW**；
因此 `hnsw.iterative_scan` 对计划无影响。这是当前生产 schema 的真实计划。

| 模式 | 租户 | Recall@10 | P50 (ms) | P95 (ms) |
|---|---|---:|---:|---:|
| off | t_large (80%) | 0.8000 | 5.866 | 20.461 |
| off | t_medium (15%) | 1.0000 | 150.469 | 291.267 |
| off | t_small (5%) | 1.0000 | 43.634 | 67.491 |
| relaxed_order | t_large (80%) | 0.8000 | 5.689 | 16.395 |
| relaxed_order | t_medium (15%) | 1.0000 | 129.424 | 265.242 |
| relaxed_order | t_small (5%) | 1.0000 | 33.576 | 43.120 |

**场景 B：强制全局 HNSW（临时删除 tenant-leading B-tree，仅一次性容器）**

`EXPLAIN` 显示 `Index Scan using idx_rag_embeddings_embedding_1024_hnsw`
`Order By: (embedding_1024 <=> $0)`，`Filter: tenant_id = 't_small'`，
即 P0-5 所担心的小租户过滤 + 全局 HNSW 场景。

| 模式 | 租户 | Recall@10 | P95 (ms) |
|---|---|---:|---:|
| off | t_large (80%) | 0.8000 | 12.933 |
| off | t_medium (15%) | 0.8820 | 10.522 |
| off | t_small (5%) | 0.9080 | 11.803 |
| relaxed_order | t_large (80%) | 0.8000 | 1.196 |
| relaxed_order | t_medium (15%) | 0.8820 | 1.167 |
| relaxed_order | t_small (5%) | 0.9080 | 0.873 |

**结论（仅对本次合成数据成立）**：

1. `hnsw.iterative_scan = relaxed_order` 在强制 HNSW 场景下把 P95 从 10–13ms 降到 0.9–1.2ms
   （本例小租户 11.803ms → 0.873ms），但**没有提升 Recall@10**（两模式数值相同）。
2. 全局 HNSW + 5% 小租户过滤的 Recall@10 为 **0.9080**，相对精确真值确实退化；
   80% 大租户为 0.8000，15% 中租户为 0.8820。P0-5 所指出的「小租户召回退化」在本合成簇状数据上被复现，
   但没有出现断崖式归零。
3. 当前生产 schema 因 tenant-leading B-tree 存在，规划器走 B-tree + 精确排序而非 HNSW；
   小租户 Recall@10 = 1.0（精确），代价是 P95 数十毫秒。是否保留该索引是检索延迟/召回的权衡，
   不能仅凭本次合成数据下生产结论；真实 BGE-M3 分布需另验。
4. `off` 与 `relaxed_order` 的 Recall 相同，说明本数据集下候选集大小（`ef_search=40`）
   是召回瓶颈，而非扫描顺序；提升 `ef_search` 的作用需另做参数扫描。

### 6.3 百万行 `CREATE INDEX CONCURRENTLY` 在线演练

| 项 | 实测结果 |
|---|---|
| 表 | `rag_embeddings`，1,000,000 行，10 租户（每租户 100k），1024 维向量 |
| DDL | `CREATE INDEX CONCURRENTLY idx_rag_embeddings_embedding_1024_hnsw_p05 ON rag_embeddings USING hnsw (embedding_1024 vector_cosine_ops) WITH (m=16, ef_construction=64)` |
| `lock_timeout` / `statement_timeout` | 30s / 1800s |
| 并发写入 | 独立连接每 100ms 更新一行，DDL 期间成功写入 **2136** 行 |
| 结果 | **成功**：索引 `indisvalid=t, indisready=t, indislive=t` |
| 索引大小 | 4542 MB |
| DDL 开始 | 2026-11-28T10:35:12Z |
| 耗时 | 未精确捕获：宿主→bridge 监控连接超时，Python 主线程随后被终止；容器内 `pg_stat_progress_create_index` 观察到 tuples_done 从 730245 持续增长至完成，索引在 10:48Z 前已 valid。不冒充精确秒数。 |
| 锁等待 | 监控采样因宿主 bridge 连接超时中断，**未取得完整 pg_locks 采样序列**，记为未实测项；已知 DDL 未阻塞 2136 次并发写入。 |
| INVALID 索引清理 | 未触发（索引有效）；脚本已实现失败时 `DROP INDEX CONCURRENTLY IF EXISTS` 清理逻辑。 |

脚本修复记录：首轮 `Monitor._loop` 因宿主 bridge 连接 `OperationalError: could not receive data from server:
Operation timed out` 使监控线程退出，且 `stop()` 等待线程导致主流程挂起；已改为**捕获异常自动重连**、
`join(timeout=10)` 有界等待；长查询统一改走 `docker exec` 容器内 psql。
因此首轮 `concurrent-result.json` 未生成，但 DDL 本身的成功由容器内 `pg_index` 复核证明。

### 6.4 未达成 / 下一步

- **未实测**：真实 BGE-M3 384/1024 维向量分布下的 Recall（本次为合成簇状数据）；不同 `ef_search` 的召回/延迟曲线。
- **未实测**：`pg_locks` 完整锁等待序列与 `pg_stat_activity` 连续采样（bridge 连接超时）；已在脚本中加重连与有界等待，下次复跑补齐。
- **未实测**：每批 10k 行回填的锁窗口（本节只做索引在线创建 + 并发写入）。
- 建议：若生产以低延迟为首要目标，评估保留 tenant B-tree + 精确排序路径；若以召回/高维 ANN 为目标，需按真实 embedding 分布重做 `ef_search` 参数扫描，并验证 `relaxed_order` 在真实分布下的召回影响。
