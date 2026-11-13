# 多租户化实施进度（唯一账本）

> 中文单语。权威规划：[多租户化实施规划](MULTI_TENANCY_IMPLEMENTATION_PLAN.md)（本文与其开头互相链接）。
> 本文件是外部参考仓库 `../seedance-research/docs/PLANNING.md` §6 意义上的唯一恢复入口
> （跨仓路径为行内代码文本，非 Markdown 链接；本机落点见
> [外部参考经验教训](MULTI_TENANCY_EXTERNAL_LESSONS.md) §2 换算表），
> 只记录已发生的事实；未运行或失败的验证必须显式标注，不得提前写成完成。

## 0. 当前快照（每次恢复工作先读这里）

| 项 | 值 |
|---|---|
| 记录时间 | 2026-11-14 |
| 工作区 | 隔离 worktree `/Users/yangjiefeng/Documents/wubuku/spring-ai-rag-multi-tenancy` |
| 分支 | `feature/multi-tenancy-phase1` |
| 基线 commit | `04e2e678`（本地 `main`，规划终审核 APPROVE 后的规划 checkpoint） |
| 主工作区 | `/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`，`main` @ `04e2e678`，创建 worktree 时 clean |
| 工作区状态 | **WP3 已完成**（V80 RLS + 三角色契约 + verifier/guard + 负向启动集成绿，见 §2b），即将形成 commit |
| 当前唯一 `in_progress` | **WP4 — 双租户 RLS 集成测试与 HNSW 基准** |
| 已完成工作包 | WP1（表清单）、WP2（底座迁移 V75–V79）、**WP3 — RLS/三角色/verifier（V80）**（证据见 §2b） |
| 紧接着要做 | WP4：双租户 A/B 集成测试（RLS 隔离/无 GUC 0 行/跨租户写拒绝/GUC 串台/连接池复用）+ HNSW 基准 |
| 规划检查计数 | `3/3`（终审核 APPROVE，规划基线冻结；本阶段未触发归零） |
| 实现检查计数 | 未开始（按 delivery-workflow §7，编码完成后才计数） |

## 1. 恢复入口（当前：WP3 — RLS、三角色与启动 verifier）

**目标产物（规划 §6 WP3）**：`V7x__tenant_rls.sql`（ENABLE + FORCE + policy）、
`TenantRlsVerifier`、`TenantModeGuard`、`scripts/verify-multi-tenancy-rls.sh`；
先写负向启动红测，再实现；退出条件见规划 §6 WP3 五种负向用例。
WP3 起才进入运行时隔离，WP2 只完成了 schema 底座（见 §2a）。

### 1.1 WP2 目标产物（已交付，历史快照）



**目标产物（规划 §6 WP2）**
- `V75__tenant_base.sql` 起按实际拆分新增迁移（**不改写 V1–V74**）：建 `rag_tenant`、插 `default`、
  租户私有表加 `tenant_id NOT NULL DEFAULT 'default'`、复合唯一、复合外键、tenant-leading 索引、
  `collection_key` 改 `(tenant_id, collection_key)`、embedding 表冗余列。
- `rag_embeddings` 大表专项：`CREATE INDEX CONCURRENTLY` 需 Flyway 非事务迁移、分批回填、
  记录锁窗口/每批行数/失败续跑。
- 全新库 + V74 存量库升级演练脚本、迁移内建终态断言（双列 / 残留旧唯一 / 函数依赖）。

**恢复要点**
- WP1 已产出 69 张表逐张裁决：`docs/drafts/MULTI_TENANCY_TABLE_CATALOG.md`（TENANT_PRIVATE 45 /
  PLATFORM_GLOBAL 20 / CREDENTIAL 4），是 WP2 加列的权威清单；
  机器可读源 `TenantTableCatalog.java` + 8 项完整性单测（含「与迁移存活表集合一致」校验）。
- 已知例外写在裁决文档 §3：跨类外键（`rag_collection_purge_worker_slots` →
  `rag_long_running_operations`）、`collection_key` 全局唯一需改复合、按 principal 键控的
  budget/capacity 表 v1 不随租户隔离、`SPRING_AI_CHAT_MEMORY` 框架表适配。
- WP2 起需要**任务专属**真实 PostgreSQL（WORKTREE.md 硬门禁）；当前未建库、未分配隔离端口。
- 先红测：双列表/残留旧唯一/函数断裂等迁移终态断言先写红，再实现迁移。

## 2. 验证台账

| 验证 | 状态 | 结果 / 备注 |
|---|---|---|
| `git worktree add -b feature/multi-tenancy-phase1 ../spring-ai-rag-multi-tenancy main` | **已运行** | 成功，HEAD `04e2e678` |
| `git worktree list` | **已运行** | 主工作区 + 本 worktree 两条，分支归属正确 |
| WP1 红测：`mvn -o -pl spring-ai-rag-core -am test-compile` | **已运行（预期红）** | `TenantTableCatalog` 不存在，编译失败 —— 红测确认 |
| WP1 实现后绿测：`mvn -o -pl spring-ai-rag-core test -Dtest=TenantTableCatalogTest` | **已运行（绿）** | `tests=8 errors=0 failures=0 skipped=0`；含 catalog↔迁移存活表集合一致校验 |
| `mvn -o -pl spring-ai-rag-core -am test-compile` | **已运行（绿）** | 全模块 test-compile 通过 |
| `./scripts/verify-project-docs.sh` | **已运行（绿）** | 17/17（期间抓到并修正了 1 处跨仓 Markdown 链接） |
| `./scripts/verify-no-pessimistic-locks.sh` | **已运行（绿）** | 无显式悲观锁 / advisory lock |
| `node scripts/verify-dependency-versions.mjs` | **已运行（绿）** | 55 模块依赖 / 1658 管理版本，无手写版本与分叉 |
| `git diff --check` | **已运行（绿）** | 干净 |
| 真实 PostgreSQL 集成（WP2/WP4） | WP1 未适用；**WP2 已运行（绿）** | `TenantBaseMigrationPostgresIntegrationTest` 5/5（Testcontainers `pgvector/pgvector:pg16`，opt-in `-Dtenant-base.it.enabled=true`） |
| 双路径升级演练（WP2） | **已运行（绿）** | `./scripts/verify-flyway-schema-paths.sh --early 74` 全 PASS：全新库 + V74 存量库，schema 一致、迁移历史 79、数据守恒、重跑安全 |
| `TenantTableCatalogTest`（动态基线版） | **已运行（绿）** | 8/8，不再写死数字 |
| 全模块回归 `mvn -o -pl spring-ai-rag-core -am test`（WP2 收尾） | **已运行（绿）** | 8444 tests / 0 failures / 0 errors，BUILD SUCCESS（动态基线修正后重跑仍 8444 全绿） |
| WebUI typecheck / build / Mock Playwright | **未运行 / 未适用** | WP1/WP2 均不触碰 WebUI |
| WP3 红测 `mvn test-compile` | **已运行（预期红）** | TenantModeGuard/TenantRlsVerifier 不存在，编译失败 |
| WP3 静态 RLS 门禁 `./scripts/verify-multi-tenancy-rls.sh` | **已运行（绿）** | guard 契约 / V80↔catalog 双向一致（45=45）/ policy GUC 绑定 / 凭据契约 / 负向用例存在性 |
| WP3 真实 PG 负向集成 | **已运行（绿）** | `TenantRlsVerifierPostgresIntegrationTest` 7/7：正向基线 + 宽松 policy/BYPASSRLS/漏表/宽松 platform 角色/SINGLE 非 default 凭据全部被拒绝 |
| WP3 全模块 JVM 回归 | **已运行（绿）** | 8457 tests / 0 failures / 0 errors |
| WP3 双路径演练重验 | **已运行（绿）** | `--early 74` 全 PASS，迁移历史 80 |
| WebUI typecheck / build / Mock Playwright | **未运行 / 未适用** | WP1 不触碰 WebUI |


## 2a. WP2 恢复入口与收尾快照（2026-11-14）

- 产物：`V75__tenant_base.sql`（`rag_tenant` + `default` 种子 + slug CHECK + 终态断言）、
  `V76__tenant_columns_and_backfill.sql`（45 张租户私有表加 `tenant_id VARCHAR(64) NOT NULL DEFAULT 'default'` +
  tenant-leading 索引；框架表 `SPRING_AI_CHAT_MEMORY` 按实际小写名 `spring_ai_chat_memory` 处理）、
  `V77__tenant_scoped_constraints.sql`（`collection_key` 改 `(tenant_id, collection_key)` 复合唯一，清理旧全局唯一）、
  `V78__tenant_big_table_indexes.sql`、`V79__tenant_big_table_index_assertions.sql`。
- 集成测试：`TenantBaseMigrationPostgresIntegrationTest`（Testcontainers `pgvector/pgvector:pg16`，
  opt-in `-Dtenant-base.it.enabled=true`），**5/5 绿**。
- 双路径演练：`./scripts/verify-flyway-schema-paths.sh --early 74` 全 PASS（全新库 + V74 存量库升级，
  schema 一致、迁移历史 79、数据守恒、重跑安全）。
- 表清单从 69 张扩为 70 张（框架表 `SPRING_AI_CHAT_MEMORY` 单独裁决），`TenantTableCatalog` 与
  `TenantTableCatalogTest` 已改为**动态基线**（连续无缺号 + 与迁移存活表集合一致 + isEmpty 守卫），
  不再写死 74/69/70/45/25 等数字（吸取 BOS V126 写死数字会过时的教训）。
- 文档同步：AGENTS.md / developer-reference / verification-baseline / testing-guide / README /
  index / project-context / business-client-integration / release-checklist 等全部 `V1–V74`→`V1–V80`；
  `.agents/skills/project-docs/` 两文件同步。
- 全模块回归：`mvn -o -pl spring-ai-rag-core -am test` **8444 tests / 0 failures / 0 errors**，BUILD SUCCESS。
- 工作区状态：以上全部改动（迁移、测试、文档、catalog、plan）已 staged，随本条账本更新一并提交为 WP2 commit。


## 2b. WP3 收尾快照（2026-11-14）

- **产物**：
  - `V80__tenant_rls.sql`：45 张 TENANT_PRIVATE 表 `ENABLE + FORCE ROW LEVEL SECURITY` +
    规范 policy `tenant_isolation`（USING/WITH CHECK 绑定 `app.current_tenant_id`）；
    4 张凭据表补 `tenant_id NOT NULL DEFAULT 'default'` 绑定列（规划 §0/§5.2 要求，WP1 落地遗漏，
    本迁移补齐；无 RLS，认证先于租户解析）；终态断言含凭据表无 RLS、清单逐表 policy 存在、
    恒真表达式拒绝、非清单表误开 RLS 差集检测。
  - `TenantMode`（SINGLE/STRICT）、`TenantModeGuard`（SINGLE 校验凭据全 default 否则拒绝启动；
    STRICT 委托 verifier）、`TenantRlsVerifier`（接口 + JDBC 默认实现：角色非 superuser/BYPASSRLS、
    禁止继承/使用 BYPASSRLS 平台角色、catalog↔pg_class 双向一致、policy 语义校验、
    无 GUC 裸连接读 0 行 fail-closed 采样）。
  - `scripts/verify-multi-tenancy-rls.sh`：静态门禁（guard 契约 / V80↔catalog 双向一致 /
    policy 命名与 GUC 绑定 / 凭据契约 / 负向用例存在性）+ `--pg` 可选真实演练。
- **红→绿**：先写 `TenantModeGuardTest`/`TenantRlsVerifierContractTest`（编译失败红测确认），
  再实现；真实 PG 集成 `TenantRlsVerifierPostgresIntegrationTest` **7/7 绿**
  （正向基线 + 宽松 policy/BYPASSRLS/漏 RLS 表/宽松 platform 角色/SINGLE 非 default 凭据五种负向，
  Testcontainers `pgvector/pgvector:pg16`，opt-in `-Dtenant-rls.it.enabled=true`）。
- **对齐参考项目**（`../seedance-research` `StartupRlsVerifier` 教训）：验语义不只验数量；
  差集自检防未登记表误开；无 GUC 0 行作为启动断言；policy 命名 tenant_isolation 对齐其 V175 惯例。
- **规划一致性修正**（WP1 遗漏补齐，非方案变更）：catalog 的 CREDENTIAL 4 张由「不加列」修正为
  「V80 起带 tenant_id 绑定列、无 RLS、单列 tenant 索引」；`MULTI_TENANCY_TABLE_CATALOG.md`
  与 `TenantTableCatalog`/`TenantTableCatalogTest` 同步；rag_tenant 主键即 tenant_id 的例外在
  WP2 集成测试中显式排除并注释。未触发规划归零（§6.2）：规划冻结口径本就要求凭据表带 tenant_id。
- **回归**：JVM 全量 `mvn -o -pl spring-ai-rag-core -am test` **8457 tests / 0 failures**；
  WP2 集成 5/5（V80 加入后重验）；双路径演练 `--early 74` 全 PASS（迁移历史 80）；
  `verify-multi-tenancy-rls.sh` 静态门禁全 PASS；文档全量 `V1–V79`→`V1–V80` 同步。

## 3. 环境与隔离边界（WORKTREE.md 硬门禁）

- 本 worktree 目前**未创建任何 `.env` / `.env.*`**，未改主工作区任何环境文件。
- **禁止**使用 `dev` profile、`scripts/dev.sh`、`seedance_dev` 或任何共享 `*_dev` 数据库做测试、迁移、启动 smoke、夹具写入。
- WP2 起若需要真实 PostgreSQL，必须使用**任务专属**隔离库，并显式覆盖业务 / Flyway / platform 三套连接；配置只能用命令级环境变量，不写回文件。
- 隔离端口：暂未分配；如需启动服务，使用与日常 8081 / 18081 / 18082 不冲突的专属端口并在此登记。
- 后台任务 / 临时数据库 / 临时资源：WP1 全程静态；WP2 期间的 Testcontainers `pgvector/pgvector:pg16` 容器与 `rag_flyway_*` 演练库均已随脚本自动回收，**当前无**遗留。WP2 曾出现一次被外部监控方 cancel 的测试容器，同批回收。
- 主工作区 `main` 与 `origin/main`（`3b83ad35`）存在本地领先；按 delivery-workflow §8，开发期间需 `git fetch origin` 并持续合并已 push 的 `origin/main`，最终交付前合并最新 `origin/main`。

## 4. 决策、偏离与风险

| 项 | 内容 |
|---|---|
| 规划基线 | `04e2e678`；终审核 APPROVE（用户转述），无剩余实质性修改意见 |
| 实施起点 | 用户明确要求「创建隔离 worktree，开始实施」，按 PLANNING.md §5 已获批准 |
| 本轮新增决策 | 分支名 `feature/multi-tenancy-phase1`；worktree 目录 `../spring-ai-rag-multi-tenancy`；阶段一先做 WP1–WP4；凭据类别细化为 4 张（含 rotation / provisioning 生命周期表，与规划 §5.2「凭据及其生命周期」一致）；平台全局 20 张逐张裁决理由见裁决文档 |
| 相对规划的偏离 | 无方案级偏离；规划头部补「进度账本」链接属 PLANNING §6 要求的配套编辑，不改变方案语义。红测中「凭据表应为 2 张」的断言口径偏窄，已按实现契约修正为 4 张并补「凭据表不得启用 RLS」断言 |
| 已知风险 | `origin/main` 落后本地 main，后期合并需按 §8 顺序；在线 CONCURRENTLY 演练仅限演练库，不进迁移链 |
| 阻塞 | 无 |

## 5. 工作包复盘（PLANNING §6.1）

| 工作包 | 收尾时填写：是否关闭可计分交付、验证证据、推进节奏结论 |
|---|---|
| WP1 | **已关闭可计分交付**：3 个产物齐备（69 张逐张裁决文档 / `TenantTableCatalog.java` / 8 项完整性单测），红→绿证据与 4 项门禁全绿。结论：有效推进，无平台期；下一包 WP2 立即进入迁移落地（需先建任务专属 PG）。 |
| WP3 | **已关闭可计分交付**：V80 迁移 + 三角色契约 + verifier/guard + 演练脚本齐备；真实 PG 7/7（五种负向全部拒绝）；JVM 8457 全绿；双路径演练 PASS。**教训**：WP1 把凭据表裁决成「不加列」，与规划 §0/§5.2「凭据表带 tenant_id」矛盾，实施时才暴露——逐张裁决文档必须逐条对照规划冻结口径，不能只按类别规则机械推导。verifier 负向用例的 INSERT 要先满足 V48 的 principal FK/NOT NULL 约束；`rag_tenant` 主键即 tenant_id 需在「平台表无 tenant_id」断言中显式排除。 |
| WP2 | **已关闭可计分交付**（含补充 commit：catalog/测试注释中的写死 69/70/79 数字改为动态基线口径，避免机械漂移）：V75–V79 迁移落地，双路径演练全 PASS、真实 PG 集成 5/5、全模块 8444 tests 全绿。**事故与方案收敛**：V78 原计划 `CREATE INDEX CONCURRENTLY`（Flyway 非事务迁移）被并发事务（pid68 idle in transaction）无限阻塞约 1 小时（外部监控方通报并 cancel）；加 `lock_timeout=30s` 兜底后变 30s 快速失败（SQLState 55P03），但仍暴露「非事务迁移执行期间任何第二条连接持有开放事务都会永久等待」的结构性问题。**收敛结论：迁移链一律纯事务内 DDL，V78 改用普通索引；CONCURRENTLY 仅在演练库在线步骤由脚本承担，不进迁移链**（V78/V79 注释与演练文档均已记录）。教训：Flyway 非事务迁移不能混 `SET`/`DO $$`；`IF NOT EXISTS` 对 INVALID 索引无效（V79 已加 INVALID 检测与清理指引）；PL/pgSQL `RAISE EXCEPTION` 不支持 `||` 拼接；测试侧 Flyway 连接显式加 `lock_timeout=30s` 与 `statement_timeout=900000` 兜底；门禁 latest 迁移号来自 `git ls-files`，未 add 的新文件会导致误报（先 add 再跑门禁）。 |

### 5.1 事故严重度判定与解堵复核（2026-11-14，监控方解堵通报）

**严重度判定（按 delivery-workflow §10 / PLANNING §6.2 口径）**：
- **严重度：S2（高）** — 非事务 CONCURRENTLY 迁移在测试设施下可无限挂死，曾在 WP2 期间实际挂死约 1 小时、需外部干预才能解堵；属「实施级事故」而非「方案级失效」。
- **根因分类：测试设施 / 迁移健壮性缺陷（非方案级）** — 不改变核心方案、范围、迁移语义或验收标准，不触发规划检查计数归零。
- **补救与加固现状（2026-11-14 复核）**：
  1. 结构性修复：CONCURRENTLY 整体退出迁移链（V78/V79 注释 + 演练文档 §1/§2/§4 + 账本 §5 WP2 行；后续 WP4 新增 V81 亦沿用纯事务内 DDL）；迁移链一律纯事务内 DDL，不再依赖「无并发开放事务」的隐含前置条件。
  2. 连接级兜底：所有真实 PG 集成测试（`TenantBaseMigration` / `TenantRlsVerifier` / `TenantRlsIsolation` / `TenantHnswRlsBenchmark`）的迁移与断言连接均显式带 `options=-c lock_timeout=30s -c statement_timeout=900000`，同类问题 30s 快速失败而非挂死。
  3. 幂等与 INVALID 防护：迁移链不再产生 INVALID 索引（无 CONCURRENTLY）；V79 / V81 终态断言保留 INVALID 检测与清理指引，覆盖在线 CONCURRENTLY 演练的残留风险。
  4. 监控方通报的三条建议全部落地，无需额外修复动作；本次复核只落档，不改代码。
