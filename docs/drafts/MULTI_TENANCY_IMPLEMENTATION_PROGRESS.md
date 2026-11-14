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
| 工作区 | 隔离 worktree `../spring-ai-rag-multi-tenancy` |
| 分支 | `feature/multi-tenancy-phase1` |
| 基线 commit | `04e2e678`（本地 `main`，规划终审核 APPROVE 后的规划 checkpoint） |
| 主工作区 | `../spring-ai-rag`，`main` @ `04e2e678`，创建 worktree 时 clean |
| 工作区状态 | **WP12 已收口**：中英正式文档成对收口、「单租户关 RLS ≠ 多租户」写入配置/架构文档、陈旧迁移版本标签清理、端点 guard 启动期全覆盖（describe() 真实快照 + 人工平台白名单裁决）、总门禁 24/24、全量 `clean test` 8595/0/0、发布脚本 19/0/16。见 §2k |
| 当前唯一 `in_progress` | 无（WP1–WP12 全部完成；等待实施审核与用户批准后合并回 `main`） |
| 已完成工作包 | WP1–WP12；证据见 §2b/…/§2i/§2j/§2k |
| 紧接着要做 | 用户批准后按 delivery-workflow §8 合并回 `main` 并推送；两处**已知未验项**如实留档（大表在线 CONCURRENTLY 演练属上线前运维项、`--with-local-runtime` HTTP E2E 需真实 LLM `.env`） |
| 规划检查计数 | `3/3`（终审核 APPROVE，规划基线冻结；本阶段未触发归零） |
| 实现检查计数 | `3/3`（WP12 实现检查中发现 2 个自纠缺陷 A/B，已修复并重跑取证；按 delivery-workflow §7 归零后重新计数，结论记于 §2k） |

## 1. 恢复入口（当前：WP12 — 文档、门禁、发布；WP11 及以下为历史快照）

**WP10 目标产物（规划 §6 WP10）**：在既有 per-principal quota 上增加 tenant 维度
聚合与硬阈值（**不是 billing / hard-limit 结算**）；平台全局容量表逐张裁决结果落地；
用量 API 增加 tenant 聚合且不暴露跨租户数据；`docs/configuration*`、`rest-api*`
中英成对同步。参考 `../seedance-research/docs/multitenancy/drafts/租户级Credits-分配与回收-实施规划.md`
的分层语义，但改写为本仓自包含通用能力（delivery-workflow §2）。
退出：配额冲突 / 并发 CAS / 超限 / 恢复测试绿；用量 API 无跨租户可见数据；
文档门禁绿。先红后绿，逐包提交后更新本账本。

**WP9 目标产物（规划 §6 WP9，已完成，见 §2h）**：`TenantLifecycleService` 创建/暂停/
恢复/禁用/软删 + 状态机；`rag_api_key` / `rag_api_principal` 绑定租户；
管理端点仅平台 capability（root + 白名单 + 审计）可调；WebUI 只读展示当前租户与模式提示。
SINGLE 下创建非 `default` 租户返回冲突并记审计（与 `TenantModeGuard` 同契约）；
租户行 + 初始 principal 单事务原子，避免幽灵租户；不实现租户切换器与邀请码。

**WP8 目标产物（规划 §6 WP8，已完成，见 §2g）**：长任务/导入/评估结果 owner/tenant/receipt；
任意 task ID 查询不得触发外部 Provider；Provider 回调按 operation 校验 receipt、
幂等 intent、replay、owner、manual audit principal；SSE 每次投递重查 ACL
（撤权后旧订阅不再收到事件）；内层 worker 显式传播租户上下文。
注意：WP7 已把 3 处 after-commit 回调的租户上下文传播落地（§2f），WP8 的「回调权威」
说的是 **Provider 回调**（签名/幂等/receipt），与 after-commit 是两件事，不要混做。

**WP7 目标产物（规划 §6 WP7，已完成）**：`TenantJobDispatcher`；逐个改造
`@Scheduled` / `@Async` / 3 处 after-commit 回调；任务载荷加 tenant/actor/audit。
MCP 不在本仓当前实现面，不列为打勾项。

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


| WP8 红测 `mvn test-compile` | **已运行（预期红）** | 反模式与合同类尚不存在，编译失败 —— 红测确认 |
| WP8 合同测试 `TenantWp8ContractTest` | **已运行（绿）** | 10/10：volatile registry / body-trusting / 建连一次授权 SSE 三类反模式被拒绝；durable reader / callback 权威 / replay / response-lost / SSE 撤权多副本通过 |
| WP8 真实 PG `TenantWp8EvidencePostgresIntegrationTest` | **已运行（绿）** | 4/4（opt-in `-Dtenant-wp8.it.enabled=true`）：V82 迁移 + RLS 隔离 + owner 四元组 + catalog 48 张 TENANT_PRIVATE；单连接 `ConnectionCallback` 内完成 GUC/查询/写入，避免第二条连接持开放事务 |
| WP8 全模块 JVM 回归 `mvn -o -pl spring-ai-rag-core -am clean test` | **已运行（绿）** | 8540 tests / 0 failures / 0 errors，BUILD SUCCESS（含 `TenantContextTest.supplierWrapCapturesSnapshotAtSubmissionTime` 回归） |
| WP8 静态门禁 `verify-multi-tenancy-wp8.sh` / `verify-multi-tenancy-rls.sh` | **已运行（绿）** | 两者全 PASS；WP8 门禁已接入 `verify-project-docs.sh`（20 → 21 项） |
| 门控开关对账 `node scripts/verify-integration-test-switches.mjs` | **已运行（绿）** | 40 个开关 / 285 个测试 / 40 个门控类全部可发现且由 `verify-gated-it.sh` 聚合；修复了 WP2–WP8 期间 7 个租户 IT 未登记聚合 runner 的累积漂移 |
| 编译复核 `mvn -o -q -pl spring-ai-rag-core -am test-compile` | **已运行（绿）** | 监控方提示的「checker/controller patch 后仍有编译错误」已不存在，红点清零 |

| WP9 红点证据 | **已运行（红）** | WP9 装配引入的红点已实测留证：全量 `clean test` 41 个 `OpenApiContractTest` 上下文错误（`tenantLifecycleController` 缺 `TenantLifecycleService` bean），修复后 8568 全绿；WP9 合同测试的「先红」阶段未单独留日志，如实标注为缺口（不计入 §7 验收证据） |
| WP9 合同测试 `TenantWp9ContractTest` | **已运行（绿）** | 5/5：原子创建 / SINGLE 拒绝非 default / 凭据绑定 / 软删幂等 / capability 守卫 |
| WP9 凭据绑定投影测试 `TenantCredentialBindingProjectionTest` | **已运行（绿）** | 2/2：认证投影带 tenantId/tenantStatus；`credential()` 继承 principal.tenantId 不回落 default |
| WP9 HTTP 契约 `TenantLifecycleControllerTest` + `ApiKeyIdentityControllerTest` | **已运行（绿）** | 12 + 9 用例绿（403 / 409 / 404 / 幂等 / root fail-closed / identity 投影） |
| WP9 真实 PG `TenantWp9PostgresIntegrationTest` | **已运行（绿）** | 4/4（opt-in `-Dtenant-wp9.it.enabled=true`）：V83 迁移 + 软删 + 状态机 + 凭据绑定；连接带 `lock_timeout=30s` / `statement_timeout=900000` |
| WP9 全模块 JVM 回归 `mvn -o -pl spring-ai-rag-core -am clean test` | **已运行（绿）** | 8568 tests / 0 failures / 0 errors / 296 skipped，BUILD SUCCESS |
| WP9 静态门禁 `verify-multi-tenancy-lifecycle.sh` | **已运行（绿）** | PASS；已接入 `verify-project-docs.sh` 总入口（21 → 22 项） |
| WP9 文档门禁 `verify-project-docs.sh` | **已运行（绿）** | 22/22（须先 `git add -A`，latest 迁移号按 `git ls-files` + 磁盘 `find` 双口径） |
| 门控开关对账 `node scripts/verify-integration-test-switches.mjs` | **已运行（绿）** | 41 开关 / 289 测试 / 41 门控类全部可发现且由 `verify-gated-it.sh` 聚合 |
| WebUI typecheck / build / Vitest / lint | **已运行（绿）** | `npm run typecheck`、`npm run build`、`npm run test:run`（93 文件 / 1143 测试）、`npm run lint`（含设计系统 525 测试、对齐与 i18n 键检查）全 PASS |

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

## 2c. WP4 收尾快照（2026-11-14）

- **产物**：
  - `V81__tenant_embedding_hnsw.sql`：为活动向量列 `embedding_1024` 建 HNSW
    （`idx_rag_embeddings_embedding_1024_hnsw`，`vector_cosine_ops`，m=16/ef_construction=64），
    终态断言索引存在且非 INVALID；纯事务内 DDL，CONCURRENTLY 不进迁移链（WP2 教训沿用）。
    背景：V1 的旧 `embedding` 列在 V68 DROP COLUMN 时索引一并消失，V1–V80 从未给
    `embedding_1024` 建 ANN 索引。
  - `TenantRlsIsolationPostgresIntegrationTest`：双租户真实 PG 集成五类断言
    （A 读不到 B / 无 GUC 0 行 / 跨租户写拒绝 / 错误 GUC 0 行不串台 / 连接复用无残留 GUC），
    单连接显式 set/reset GUC，只证明数据库层 RLS 隔离合同（请求链路接线属 WP5），
    opt-in `-Dtenant-isolation.it.enabled=true`。
  - `TenantHnswRlsBenchmarkPostgresIntegrationTest`：HNSW+RLS 基准（2000 行 1024 维确定性合成向量，
    Testcontainers 任务专属库），写 `spring-ai-rag-core/target/tenant-hnsw-rls-benchmark.json`，
    opt-in `-Dtenant-hnsw-bench.it.enabled=true`。
- **红→绿 / 复现证据**：隔离测试 5/5、基准 1/1（两次独立运行均绿）；Flyway 全新库 81 步成功。
- **基准结论（2026-11-14 第二次运行）**：`annIndexOnEmbedding1024=1`（V81 修复后达标）、
  `annIndexUsable=true`、`pureAnnUsesHnsw=true`、`rlsQueryMs=8 / noRlsQueryMs=5`、
  `rlsOverheadRatio=1.600`、`latencyGatePassed=true`（≤3×）。
- **硬门槛落档**：`docs/quality-defaults-zh-CN.md` / `docs/quality-defaults.md` 新增
  「多租户 HNSW + RLS 检索基准（WP4）」节：ANN 索引存在 + 非INVALID、RLS 延迟 ≤3× 两条硬门槛，
  并同步改写「检索结果可复现性」节（V81 起由「精确扫描完全一致」改为「分数容差内一致」）。
- **已知缺口 / 后续阶段阻断项（如实落档，不静默判通过）**：
  1. `planUsesIndex=false`：合成 2000 行下 planner 选 Seq Scan，属数据集小、全表更便宜，
     不是 ANN 缺失；`annIndexUsable/pureAnnUsesHnsw` 证明 HNSW 本身有效。
  2. 多租户混合语料的 Recall@K 未测：规划 §9 已将「全局 HNSW top-k 被其他租户占满」列为
     第一阶段性能门；本基准只覆盖延迟与索引存在性。生产规模重测或 iterative scan /
     over-fetch / 分区评估任一通过前，不得宣称生产多租户检索质量达标。
  这两条已写入 quality-defaults「记录项与已知缺口」，进入 WP5+ 不表示消除。
- **文档同步**：全仓当前事实文档 `V1–V80`→`V1–V81`（AGENTS/README/developer-reference/
  testing-guide/verification-baseline/index/project-context/business-client-integration/
  release-checklist 中英成对 + `.agents/skills/project-docs/` 两文件）；
  `MULTI_TENANCY_UPGRADE_REHEARSAL.md` §3 补 V81 事实。
- **门禁**：`verify-project-docs.sh` 17/17（V81 先 `git add` 再跑门禁）；
  `git diff --check` 干净；完整门禁与 JVM 全量回归随 WP5 开工前复跑（见 §2 台账）。

## 2d. WP5 收尾快照（2026-11-14）

- **产物（core 包 `com.springairag.core.tenant`）**：
  - `TenantContext`：不可变租户作用域（tenantId / actorId / mode / auditId），线程绑定；
    事务作用域内切换租户直接拒绝（规划 §4.5「事务内禁止切租户」）。
  - `TenantResolver` SPI + `SingleTenantResolver`（固定 default）+ `PrincipalTenantResolver`
    （凭据绑定 + 租户状态 ACTIVE 校验，失败一律 fail-closed，不落回 default）。
  - `TenantContextFilter`：order -8（认证 -10 → MDC -9 → 租户上下文 -8 → capability -5 → 限流 0），
    借连接/开事务前绑定上下文，请求结束 finally 清理；MDC 写 tenantId/actorId，不写 raw credential。
  - `TenantGucDataSource` + `TenantGucConnection`：app DataSource 的 session 级 GUC 三件套
    （checkout RESET → `set_config(..., false)` → 归还 RESET），并做 GUC 回读断言
    （回显值必须 == TenantContext.tenantId，不一致即关闭连接并抛错 fail-closed）。
    归还 RESET 由连接代理的 close() 触发。
  - `TenantDataSourceBeanPostProcessor`：包装 Spring Boot 自动配置的 app DataSource
    （只包一次；不声明 `@Bean DataSource`，避免与自动配置冲突）。
  - `TenantProperties`（`rag.tenant.mode`，默认 SINGLE）+ `RagProperties.getTenant()`。
  - `TenantConfiguration`：resolver / filter / `TenantModeGuard` / 启动 runner / DataSource 包装装配。
  - `AuthenticatedApiPrincipal` 扩展 `tenantId` + `tenantStatus`；保留 9/10 参兼容构造器
    （默认落 default/ACTIVE），既有 49 处构造调用与 8463 个测试零改动。
- **红→绿证据**：`TenantContextTest` 5/5、`TenantResolverTest` 5/5、`TenantContextFilterTest` 3/3、
  `TenantPropertiesTest` 3/3、`TenantGucDataSourcePostgresIntegrationTest` **4/4（真实 PG）**；
  JVM 全量 **8483 tests / 0 failures**。
- **真实 PG 覆盖的六条退出条件**：先设上下文再借连接 ✅、checkout/归还 RESET ✅、
  非事务自动提交路径 GUC 仍有效 ✅（证明 `set_config(..., false)` 而非 SET LOCAL）、
  GUC 回读不一致即拒绝 ✅、无上下文不设 GUC 且裸读 0 行 ✅、事务内禁止切租户 ✅（单元层）。
- **静态门禁**：`scripts/verify-multi-tenancy-guc.sh`（新增）核对 session 级 set_config(false)、
  checkout/归还 RESET、GUC 回读断言、非事务 JDBC 现场清单（**按 rg 动态计算，不写死数量**；
  实测 20 个，与规划 §3#14「以现场清单为准」口径一致）、DataSource 包装已装配；
  已接入 `verify-project-docs.sh` 门禁总入口（**17 → 18 项**），并按仓纪律补 rg 预检（fail-closed）。
- **文档**：`docs/configuration-zh-CN.md` / `docs/configuration.md` 新增「多租户配置（WP5）」节
  （模式表、三件套、回读断言、解析顺序、fail-closed 语义）。
- **待办（不阻断 WP6，但阻断发布）**：凭据加载投影（`ApiKeyManagementService.authenticate`）
  尚未带出 `tenant_id` 与租户状态——`PrincipalTenantResolver` 的数据来源需在 WP9 租户绑定
  落地时打通；本版 STRICT 解析依赖 principal 快照中的 tenantId，缺值即 fail-closed。

## 2e. WP6 收尾快照（2026-11-14）

- **产物（core 包 `com.springairag.core.tenant`）**：
  - `TenantMembershipGuard`：上下文存在 / 工作项 tenant 与上下文一致 / 租户状态 ACTIVE 三项校验，
    补上「RLS 只保证绑定租户合法，不保证工作项属于该租户」的第二层断言（规划 §4.4 / §5.3.6）。
  - `TenantScope` / `AclPolicy` / `EndpointDeclaration` / `TenantEndpointGuard`：
    端点 tenant + ACL + capability 声明快照；漏声明、缺 tenant、缺 ACL、平台路径未显式
    `PLATFORM_ONLY` 一律启动失败（新增端点漏声明即红）。
  - `PlatformCapability` 命名枚举 + `PlatformCapabilityRegistry`：跨租户 discovery 只返回
    `TenantProjection`（tenantId + status）最小身份投影；actor 白名单 + 每次调用
    `PlatformAuditEvent` 审计；未登记 capability / 白名单外 actor 拒绝。
  - `PlatformBoundaryArchTest` + `scripts/verify-multi-tenancy-endpoint-guard.sh`：
    业务包（alertdelivery/budget/chat/ratelimit/repository/service/usage/retrieval 等）
    不得 import 平台实现（规划 §4.6 / §6 WP6）。
  - `TenantConfiguration` 追加 registry / membership guard / endpoint guard 三个 bean。
- **红→绿证据**：`TenantEndpointGuardTest` 5/5、`PlatformCapabilityRegistryTest` 4/4
  （其中 1 例红测暴露测试自身漏 `allowActor`，按仓纪律修测试不放宽实现）、
  `PlatformBoundaryArchTest` 1/1、`TenantMembershipGuardTest` 4/4；合计 14/14。
- **静态门禁**：`verify-multi-tenancy-endpoint-guard.sh` 接入 `verify-project-docs.sh`
  （**18 → 19 项**），并带 rg 预检 fail-closed。
- **待办（不阻断 WP7，但阻断发布）**：`TenantEndpointGuard` 当前由装配层注入空声明集合，
  实际受保护端点清单需在 WP12 门禁收口时从 `ApiRoutePolicyRegistry.describe()` 驱动，
  把「所有写/读端点必须声明」从测试夹具升级为启动期全覆盖；WP6 已提供可拒绝漏声明的组件与红测。


## 2f. WP7 收尾快照（2026-11-14）

- **产物（core 包 `com.springairag.core.tenant`）**：
  - `TenantJob`：任务载荷三元组（tenant / actor / audit）。允许 `tenantId == null`，
    由 dispatcher 统一 fail-closed 拒绝；不在构造器抛参数异常，避免绕过 dispatcher 审计。
  - `TenantJobDispatcher`：`dispatch`（载荷 tenant 与当前上下文一致性断言 + 无上下文显式
    建立 scope + finally 清理）、`scoped`（CallerRuns / 异步显式传播，**载荷缺 tenant
    直接拒绝，不隐式兜 default**）、`dispatchForEachActiveTenant`（SINGLE 只跑 default；
    STRICT 由 `TenantDirectory` 枚举 ACTIVE 租户逐租户执行，单租户失败不阻断其余，
    失败汇总抛出；目录枚举为空/失败 → fail-closed，不降级）。
  - `TenantDirectory`：ACTIVE 租户目录 SPI（`rag_tenant` 是 PLATFORM_GLOBAL，由平台池读）。
  - `TenantScopedJob`：标记「租户私有数据作用域」的定时/异步任务类。
  - `TenantTaskScopeDecorator`：方法级 bootstrap—assert—restore—clear
    （SINGLE 无上下文 → 显式 bootstrap `default`；STRICT 无上下文 → 不执行）。
  - `TenantTaskDecorator`：`taskExecutor` 与调度线程统一装饰器
    （有上下文 → `TenantContext.wrap` 显式跨线程传播；无上下文 → 走 scope 装饰器）。
  - `TenantTaskInventory`：**现场清单**（TENANT_SCOPED 18 个文件 /
    PLATFORM_GLOBAL 5 个文件+豁免理由 / AFTER_COMMIT 3 个文件 / INFRASTRUCTURE 3 个文件），
    数量以现场清点为准，不写死总数。
  - `TenantContext.wrap(Runnable/Supplier)`：显式携带当前租户上下文跨线程，
    结束时还原目标线程原值（不隐式清空复用线程）。
- **after-commit 上下文传播（3 处，全部改）**：
  `AlertNotificationWakeupPublisher` / `EmbeddingJobWakeupPublisher` /
  `ApiPrincipalLifecycleEventPublisher` 在**注册 TransactionSynchronization 时**
  捕获 `TenantContext.current()` 快照，`afterCommit()` 里显式 set → 执行 → finally
  还原/清理。依据：参考项目 `../seedance-research/...` checklist §G「after-commit 脏连接」
  事故（回调在事务提交后跑，连接/GUC 已清理，若回调读租户私有表会 0 行或串台）。
- **线程池统一包装（`AsyncConfig`）**：`taskExecutor` 的 `TaskDecorator` 与
  调度线程 `TaskDecorator` 均改为 `TenantTaskDecorator` + `MdcContext` 组合；
  CallerRunsPolicy 保留（同线程执行 → 装饰器在同一线程 restore 原上下文）。
  **注意**：调度器装饰器在容器刷新早期无法可靠读取 `RagProperties`，故改为
  非 static BeanPostProcessor 直接持有 `RagProperties`（避免用 System property 兜底）。
- **红→绿证据**：`TenantWp7ContractTest` 7/7（CallerRuns 还原 / SINGLE bootstrap default /
  STRICT 无上下文拒绝 / scoped 缺 tenant 拒绝 / SINGLE 只跑 default / STRICT 逐租户枚举+异常汇总 /
  `TenantContext.wrap` 跨线程）、`TenantAfterCommitContextTest` 3/3（3 处 publisher 回调可见租户上下文
  且结束后清理）、`TenantJobDispatcherTest` 5/5、`TenantJobInventoryGateTest` 5/5、
  `TenantJobDispatcherPostgresIntegrationTest` **4/4（真实 PG + RLS）**；
  既有 3 个 publisher 回归 10/10 绿；JVM 全量 **8521 tests / 0 failures**。
- **真实 PG 覆盖的退出条件**：STRICT 逐租户分发 A 只读到 `[ck-a]`、B 只读到 `[ck-b]` ✅；
  无 GUC 裸读 0 行（证明「静默 0 行」是真实风险，必须靠 dispatcher 规避）✅；
  载荷 tenant 与上下文不一致被拒 ✅；分发结束无 GUC 残留（连接复用不串号）✅。
- **静态门禁**：`scripts/verify-multi-tenancy-jobs.sh`（新增）核对现场任务文件全部登记、
  租户私有任务带 `@TenantScopedJob`、after-commit 捕获/传播上下文、
  `AsyncConfig` 装配 `TenantTaskDecorator`、dispatcher 提供逐租户分发合同、清单无幽灵条目；
  接入 `verify-project-docs.sh`（**19 → 20 项**）。并发红线
  `scripts/verify-no-pessimistic-locks.sh` 通过；依赖版本检查通过；`git diff --check` 干净。
- **现场清点口径修订（以现场为准，不写死数字）**：@Scheduled 24 处 / 21 个文件；
  @Async 3 处 / 3 个文件（`ApiPrincipalExpiryAlertWorker` / `DingTalkNotificationService` /
  `EmailNotificationService`；`AsyncConfig` 与 `MdcContext` 只是提及 @Async，不是任务）；
  TransactionSynchronization 3 处（与规划 §3#9 一致），无 `@TransactionalEventListener`。
  计划中「21 scheduled + 5 async」是 2026-11-13 的旧口径，实际以 `TenantTaskInventory`
  现场清单为准（`MdcContext` / `AsyncConfig` / 两个 publisher 计入旧口径）。
- **待办（不阻断 WP8，但阻断发布）**：
  1. `JdbcTenantDirectory`（读 `rag_tenant` 平台表）已落地并装配为默认 bean，
     `TenantScheduledTaskDecorator` 在调度线程按 ACTIVE 租户**逐租户驱动**租户私有任务
     （SINGLE 只跑 default；STRICT 枚举后逐租户、单租户失败不阻断、枚举失败 fail-closed 跳过本轮）。
  2. **逐租户调度的覆盖边界（如实声明）**：`TenantScheduledTaskDecorator` 依赖
     Spring 把任务包成 `ScheduledMethodRunnable`（反射取目标 bean 类判断 `@TenantScopedJob`）。
     **经 `@Scheduled` 注解进入调度器的任务已全覆盖**；但 18 个租户私有文件里
     `@Scheduled` 方法内部自建线程池（`EmbeddingJobWorker` / `EvaluationSuiteWorker` /
     `AlertNotificationDeliveryWorker` 等 worker 的 `workers.execute(...)`），
     这些**内层线程池任务**仍由调度线程的租户作用域经 `TenantContext` 延续——
     worker 线程是在调度线程里 `submit`/`execute` 的 Runnable，不会自动带走 ThreadLocal。
     **已知风险与 WP8 工作**：内层线程池任务需显式 `TenantContext.wrap` 或由
     `TenantJobDispatcher.scoped` 包装（载荷 tenant 来自 claim 到的任务行）。
     不得宣称「内层异步已全部租户化」。
  3. `ApiKeyManagementService.authenticate` 的凭据投影仍未带出 `tenant_id` 与租户状态
     （WP5 遗留，WP9 落地）。

## 2g. WP8 收尾快照（2026-11-14）

- **产物 A：结果 owner / 回调权威（core 包 `com.springairag.core.tenant`）**：
  - `TenantResultReceipt`：owner 四元组载体（tenant / collection / ownerPrincipal / receipt）；
    `TenantResultOwnerReader` 按 operationId 查持久化 receipt，无权 / 跨租户 / 未知 ID
    一律 `Optional.empty()`，**任意 task ID 不触发外部 Provider 查询**
    （`withProviderQuery` 是测试 no-op probe，非生产路径）。
  - `TenantCallbackProcessor`：按「验签 → receipt → replay → 权威查询」顺序判定；
    body 不具权威，无 receipt / identity 错配返回 UNKNOWN（不写事实、不查 Provider）。
    `TenantDurableCallbackEvidenceStore.claimReplayNonce` 是**进程内合同载体**
    （生产 DB 唯一约束由 V82 承载）；`TenantProviderReferenceCoordinator` 的
    `recoverOrCreate` 先查已有 PENDING intent，只有查不到且 claim 成功才 create，
    覆盖 response-lost。callback actor 与 manual resolution 的 audit principal 分列。
  - **既有业务表继续充当各自结果的 durable receipt**：`rag_long_running_operations`
    的 `owner_principal_id` + `idempotency_key_sha256` + `result_payload`、
    `fs_import_batches` / `rag_evaluation_runs` 的 `owner_principal_id`；
    V71 明确禁止猜测回填历史 owner，故不逐表加列。`LongRunningOperationService.load()`
    的 `owner_principal_id = ?` 过滤保留为生产接线点。
- **产物 B：V82 迁移 + catalog**：
  - `V82__tenant_result_callback_evidence.sql` 新建 3 张证据表
    （`rag_tenant_result_receipt` / `rag_tenant_callback_receipt` /
    `rag_tenant_callback_replay_nonce`），自带 `ENABLE + FORCE RLS` + 规范 policy
    `tenant_isolation` + 终态断言；**纯事务内 DDL、无 CONCURRENTLY**（承接 §5.1 事故收敛）。
  - `TenantTableCatalog` 登记三表，TENANT_PRIVATE 45 → 48；逐张裁决文档
    `MULTI_TENANCY_TABLE_CATALOG.md` 同步为 V1–V83 / 73 张存活表 / 48+21+4，
    并补上此前漏列的 `rag_tenant` 平台主数据行（69 → 73 行）。
  - `verify-multi-tenancy-rls.sh` 改为「V80 清单 ⊆ catalog，catalog − V80 == V82 三表」；
    `TenantRlsVerifier` 只校验 V80 清单（V80 是已执行迁移，不回写），V82 表由
    V82 自身断言 + `TenantWp8EvidencePostgresIntegrationTest` 真实 PG 校验。
- **产物 C：SSE 逐次授权生产接线**：
  - `TenantAuthorizedSsePublisher` 无上下文 fail-closed；每次允许路径投递都调用
    `TenantSseAccessChecker.canRead` 重查当前 ACL；多副本各自独立拒绝撤权后的旧订阅。
  - `TenantCurrentAclSseChecker`（`@Component`，JdbcTemplate 实时查
    `rag_api_principal` JOIN `rag_api_key`，按 tenant_id 过滤，revoked / expires /
    ADMIN / allow-list 全查）经 `RagDocumentController.embedDocumentStream` 的
    progress 回调接入 `publishIfAuthorized`；checker 走
    `@Autowired(required=false)` setter 注入，不破坏 19 个手工构造测试。
  - **如实边界**：当前只接 `embedDocumentStream` 一个端点，request-scoped 短流，
    无长期订阅注册表；其余 SSE 入口按同一组件在 WP12 门禁收口前逐步扩展。
    `docs/SSE-PROTOCOL.md` §8.1 已写明这一边界，不宣称「所有 SSE 全覆盖」。
- **产物 D：内层 worker 租户传播（WP7 遗留收口）**：
  `EmbeddingJobWorker` / `EvaluationSuiteWorker` / `AlertNotificationDeliveryWorker`
  的 `workers.submit(...)` / `executor.execute(...)` 全部改为 `TenantContext.wrap(...)`，
  eval 心跳线程也包了；新增 `TenantWorkItemAssert`：claim 行 tenant 非空才做
  「claim tenant == scope tenant」断言，null 兼容旧 fixture / PLATFORM_GLOBAL。
  **判定顺序教训**：必须「先判 null 再判无上下文」，否则旧 fixture 无 tenant 列时
  会误 fail-closed（已修一次红）。`EmbeddingJob` record 末尾加 `tenantId` 组件
  （canonical 26 参 + 25 参兼容构造器）；`EmbeddingJobRepository` /
  `EvaluationSuiteRepository` 投影补 `tenant_id`，`tenantColumnOrNull` 兼容无列 fixture。
- **产物 E：`TenantContext.wrap(Supplier)` 修复 + 回归**：
  snapshot 捕获移出 lambda（提交时捕获而非执行时读取），新增
  `TenantContextTest.supplierWrapCapturesSnapshotAtSubmissionTime()` 防回归。
- **红→绿证据**：`TenantWp8ContractTest` 10/10（三类反模式：volatile registry /
  body-trusting callback / 建连一次授权 SSE 均被拒绝；durable reader / callback 权威 /
  replay / response-lost / SSE 撤权多副本通过）；`TenantWp8EvidencePostgresIntegrationTest`
  **4/4（真实 PG）**；全模块 `mvn -o -pl spring-ai-rag-core -am clean test`
  **8540 tests / 0 failures / 0 errors**；静态门禁 `verify-multi-tenancy-wp8.sh` 与
  `verify-multi-tenancy-rls.sh` 全 PASS。
- **真实 PG 接线教训**：测试曾因 JdbcTemplate 分散借连接导致 GUC 与查询不在同一连接，
  改为单连接 `ConnectionCallback` 内完成 GUC + 查询 + 写入断言后 4/4 绿；
  迁移/断言连接继续显式带 `lock_timeout=30s` / `statement_timeout=900000`。
- **门禁与文档收口**：
  - `verify-multi-tenancy-wp8.sh` 接入 `verify-project-docs.sh` 总入口（20 → 21 项）；
    `verify-gated-it.sh` 补登记 WP2–WP8 共 7 个租户 IT 套件，`testing-guide*`
    中英成对补「多租户真实 PostgreSQL 门控套件」命令清单；
    `node scripts/verify-integration-test-switches.mjs` 从红（12 项违规）转绿
    （40 开关 / 285 测试 / 40 类全部可发现且聚合）。
  - 全仓当前事实文档 `V1–V81`→`V1–V83`（18 个文件，含 `.agents/skills/project-docs/`）；
    `SSE-PROTOCOL.md` §8.1、`architecture*`、`security-boundary*`、`testing-guide*`
    中英成对同步；`docs/drafts/MULTI_TENANCY_TABLE_CATALOG.md` 补 V82 三表与
    §4 RLS 覆盖口径。`docs/drafts/MULTI_TENANCY_EXTERNAL_LESSONS.md` 与
    `MULTI_TENANCY_IMPLEMENTATION_PLAN.md` 的 `V1–V81` 属历史/规划口径，不改。
  - **门禁自测**：`node scripts/lib/docs-integrity-check.mjs bilingual` 59/59 绿、
    `node scripts/verify-zh-translation.mjs` 59 文档全绿。
- **如实声明的缺口（不阻断 WP9，阻断发布与「生产多租户已完成」宣称）**：
  1. **无真实外部 Provider 调用**：WP8 按规划退出条件只用计数器/探针证明
     「不触发 Provider」，未接真实 Provider 回调链路；`TenantResultOwnerReader.withProviderQuery`
     是 no-op probe（测试用计数器替代），`TenantDurableCallbackEvidenceStore` 是
     进程内合同载体（生产 DB 唯一约束在 V82 承载）。WP11 红队需补真实回调模拟。
  2. **SSE 接线边界**：只接 `embedDocumentStream` 一个端点，无长期订阅注册表；
     多副本验证是合同级模拟，非跨进程真实部署验证。
  3. **凭据绑定租户（WP5 遗留）**：`ApiKeyManagementService.authenticate` 投影
     仍未带出 `tenant_id` / 租户状态，STRICT 解析依赖 principal 快照 fallback；
     WP9 落地，本版不得宣称 STRICT 端到端可用。
  4. `TenantEndpointGuard` 仍由装配层注入空声明集合（WP6 遗留），
     WP12 门禁收口时从 `ApiRoutePolicyRegistry.describe()` 驱动全覆盖。

## 2h. WP9 收尾快照（2026-11-14）

- **产物 A：租户生命周期与状态机（core 包 `com.springairag.core.tenant`）**：
  - `TenantStatus`（`ACTIVE` / `SUSPENDED` / `DISABLED`）、`TenantCreateCommand`、
    `TenantLifecycleAudit`（`Logging` 实现）、`TenantSqlExecutor` / `JdbcTenantSqlExecutor`、
    `TenantLifecycleService`、`RagTenant` JPA 实体。
  - 状态机：`ACTIVE → SUSPENDED/DISABLED`，`SUSPENDED → ACTIVE`，
    `DISABLED` 终态；`SUSPENDED → DISABLED` 禁止（非法迁移 → 409）。
  - **幽灵租户教训落实**：租户行 + 初始 principal 写入同一个
    `TransactionTemplate.execute`；初始 principal 失败即整体 rollback，
    不留「有租户无凭据」的孤儿行。
  - **SINGLE 守卫**：创建非 `default` 租户直接拒绝并记 `REJECT` 审计，
    与 WP3 `TenantModeGuard` 同一契约（启动期校验凭据全 `default`）。
- **产物 B：V83 迁移（软删）**：
  `V83__tenant_lifecycle_soft_delete.sql` 为 `rag_tenant` 增 `deleted_at` 软删列 +
  `idx_rag_tenant_active` 部分索引 + 终态断言；**纯事务内 DDL、无 CONCURRENTLY**
  （承接 §5.1 事故收敛），不改写 V1–V82 已执行迁移。
- **产物 C：凭据绑定租户（WP5 遗留收口）**：
  - `RagApiKey` / `RagApiPrincipal` 增 `tenant_id`（默认 `default`）。
  - `RagApiKeyRepository.AuthenticationProjection` 增 `getTenantId()` / `getTenantStatus()`；
    `authenticate` 的 JPQL 加 `p.tenantId` 谓词 + `RagTenant` 状态子查询，
    软删后查不到状态 → 认证失败。
  - `ApiKeyManagementService.authenticate(...)` / `findActivePrincipal(...)` 显式传
    `tenantId` / `tenantStatus`，状态为空降级为 `DISABLED`（fail-closed）。
  - `credential(...)` 改为 `credential.setTenantId(principal.getTenantId())`，
    修掉「`rag_api_key.tenant_id` 悄悄回落 `default`」的静默降级缺陷。
  - `TenantLifecycleService.readStatus(...)` 捕获 `EmptyResultDataAccessException`
    返回 `null`（无行不是 500）。
- **产物 D：平台 capability 守卫（管理面）**：
  - `PlatformCapability.TENANT_LIFECYCLE` 登记；`TenantConfiguration` 注册 handler
    为 `lifecycleService::describe`（最小身份投影，不返回私有正文）。
  - `TenantLifecycleController` 暴露 `POST /api/v1/rag/tenants`、
    `POST .../{tenantId}/suspend|resume|disable`、`DELETE .../{tenantId}`、
    `GET .../{tenantId}`；双重门槛：**root 凭据已配置且已通过 root 认证**
    （未配置 root 也拒绝，fail-closed）+ capability actor 白名单；
    拒绝与放行都记 `PlatformAuditEvent`。
  - `TenantProperties.lifecycleActors` 默认 `environment-root`；
    `application.yml` 新增 `rag.tenant.mode` 与 `rag.tenant.lifecycle-actors`。
  - **装配教训**：`tenantLifecycleService` 最初放在 `RagWebSecurityConfiguration`，
    要求 `JdbcTemplate` + `PlatformTransactionManager`，导致无 JDBC 的轻量切片上下文
    启动失败（41 个 `OpenApiContractTest` 上下文错误）。修法：bean 移到
    `TenantConfiguration` 并加 `@ConditionalOnBean({JdbcTemplate, PlatformTransactionManager})`，
    controller 再加 `@ConditionalOnBean(TenantLifecycleService.class)`，
    无 JDBC 上下文直接不装配该平台面。
- **产物 E：身份响应与 WebUI 只读展示**：
  - `ApiKeyIdentityResponse` 增 `tenantId` / `tenantMode` / `tenantStatus`；
    `ApiKeyIdentityController` 改为 3 参构造（root / legacy-static 填
    `default` + 当前 mode + `ACTIVE`，数据库 principal 用认证投影覆盖）。
  - WebUI `ApiKeyIdentity` 类型同步三字段；`Layout.tsx` 侧栏新增
    `data-testid="tenant-info"` 只读条（`text-align: start`）；
    i18n 中英新增 `tenant.readOnly`；`Layout.test.tsx` 增 WP9 展示用例（17/17 绿）。
  - **明确不做**：租户切换器、邀请码（规划 §6 WP9 显式排除）。
- **红→绿证据**：`TenantWp9ContractTest` 5/5、`TenantCredentialBindingProjectionTest` 2/2、
  `TenantLifecycleControllerTest` + `ApiKeyIdentityControllerTest` 21 用例绿、
  `TenantWp9PostgresIntegrationTest` **4/4（真实 PG）**、
  全模块 `clean test` **8568 tests / 0 failures / 0 errors**、
  `verify-multi-tenancy-lifecycle.sh` PASS、总门禁 **22/22**、
  WebUI typecheck + build + 1143 Vitest + lint 全绿。
- **门禁与文档收口**：
  - 新增 `scripts/verify-multi-tenancy-lifecycle.sh` 并接入 `verify-project-docs.sh`
    （21 → 22 项）；`verify-gated-it.sh` 登记 `tenant-wp9:TenantWp9PostgresIntegrationTest`；
    `testing-guide*` 中英成对补 `-Dtenant-wp9.it.enabled=true` 运行路径。
  - 全仓当前事实文档 `V1–V82`→`V1–V83`（含 `.agents/skills/project-docs/`）；
    `configuration*` / `rest-api*` / `architecture*` / `security-boundary*` /
    `verification-baseline*` / `release-checklist*` 中英成对同步；
    `MULTI_TENANCY_TABLE_CATALOG.md` 补 `rag_tenant.deleted_at` 与部分索引。
  - 账本 §0 的 worktree 绝对路径改为相对路径 `../spring-ai-rag-multi-tenancy`
    （跨仓材料一律相对路径，交付纪律）。
- **如实声明的缺口（不阻断 WP10，阻断发布与「生产多租户已完成」宣称）**：
  1. **单连接执行问题已规避但未做连接池整体断言**：真实 PG 用例走单连接
     `ConnectionCallback`，未覆盖「同一池内多连接并发切换租户」的端到端回归。
  2. **生命周期端点无真实外部凭据体系对接**：root 门槛复用现有
     `EnvironmentRootCredentialResolver`，没有独立的平台账号体系。
  3. **`TenantEndpointGuard` 仍注入空声明集合**（WP6 遗留），WP12 门禁收口时
     从 `ApiRoutePolicyRegistry.describe()` 驱动全覆盖。
  4. **用量/配额维度尚未落地**（WP10）。



## 2j. WP11 收尾快照（2026-11-14）

**WP11 目标产物（规划 §6 WP11，已交付）**：红队矩阵 10 维负向证据被门禁钉死；
函数体依赖断裂进入启动 verifier；V74→V84 双路径升级演练实测 PASS；
SINGLE→STRICT 切换 / 回退 / 备份恢复执行语义落档。详细演练事实见
[升级演练记录](MULTI_TENANCY_UPGRADE_REHEARSAL.md) §3.1 / §5。

### WP11 交付内容

1. **函数体依赖断裂（红→绿）**：`JdbcTenantRlsVerifier.verifyTriggerFunctionBodies` 检查
   `fn_rag_collection_key_immutable` 函数存在、`trg_rag_collection_key_immutable` 在
   `rag_collection` 上存在、函数体含 `RAISE` 拒绝路径（空实现视为静默失效）。
   新增 PG 负向 `#triggerFunctionBodyBreakIsRejected`（掏空函数体）与
   `#missingImmutableTriggerIsRejected`（丢 trigger），均以 `restoreImmutableFunction`
   自清理并在 finally 后复验正向通过——不污染后续测试。
2. **header 伪造**：`TenantHeaderForgeryTest`（2 例）：无 principal 时伪造
   `X-Tenant-Id` 不产生 TenantContext；有 principal 时租户来自认证链绑定，header 无法覆盖。
3. **红队矩阵门禁**：`scripts/verify-multi-tenancy-redteam.sh`（rg 预检 fail-closed），
   10 维映射到既有负向测试/门禁：漏写谓词、平台池误用、header 伪造、异步丢上下文
   （4 层：用量 recorder / after-commit / scheduled / dispatcher）、函数体断裂、
   policy 宽松、superuser/BYPASSRLS/verifier 关闭/漏 RLS/宽松 platform 角色/SINGLE 凭据、
   SSE 撤权、任意 task ID、总入口自登记。已注册 `verify-project-docs.sh`
   （documented executable 清单 + run_check），总门禁 23→**24 项**。
4. **RLS 门禁扩清单**：`verify-multi-tenancy-rls.sh` 负向用例存在性校验补入两个 WP11 用例，
   并同步措辞（不再写「5 类」固定口径）。
5. **升级演练实测（真实 PostgreSQL 16.8，本机 5432，脚本自建自删临时库）**：
   `./scripts/verify-flyway-schema-paths.sh --early 74 --keep` 全 PASS——
   空库→V84（84 migrations）、V74 标记行守恒、双路径 `pg_dump --schema-only`
   归一化逐字一致（2293 行）、迁移历史条数一致（84）、重跑不破坏数据。
   日志目录 `/tmp/rag-flyway-verify.48XOOG`（任务级取证，不入仓）。
   **诚实标注未运行项**：有真实大表数据的在线 `CONCURRENTLY` 锁窗口/每批 10k 行回填实测，
   列为上线前运维专项演练，不以空库结果外推。
6. **SINGLE→STRICT / 回退 / 备份恢复**：`MULTI_TENANCY_UPGRADE_REHEARSAL.md` §5 新增：
   切换 5 项前置条件、STRICT→SINGLE 回退的 `TenantModeGuard` fail-closed 语义与
   清理/备份恢复两条前置路径（不删业务行、保留审计）、`rag-backup.py` 逻辑备份边界
   （非 PITR/非 RPO=0，停写 quiesced 前置），并链接既有中英备份文档与演练脚本。
7. **账本勘误**：WP10 提交 hash 修正 `484d1174`→`6fc3f760`（amend 后最终值）。

### WP11 验证台账（本包即时取证）

| 验证 | 状态 | 结果 |
|---|---|---|
| `mvn -o -q -pl spring-ai-rag-core -am test-compile` | **已运行（绿）** | 编译红点清零 |
| focused：`-Dtest='TenantHeaderForgeryTest,TenantRlsVerifierContractTest,TenantRlsVerifierPostgresIntegrationTest' -Dtenant-rls.it.enabled=true`（实现前红基线运行 11/11；新负向加入后） | **已运行（绿）** | **13/13**（PG 9/9 + forgery 2/2 + contract 2/2），0 fail 0 skip |
| `./scripts/verify-multi-tenancy-redteam.sh`（首跑预期红：`WHERE tenant_id` 口径、总门禁登记、canRead 转义） | **已运行（红→修→绿）** | 3 处门禁断言口径修正后 **PASS 10/10 维** |
| `./scripts/verify-flyway-schema-paths.sh --early 74 --keep` | **已运行（绿）** | 双路径全 PASS，迁移历史 84，日志 /tmp/rag-flyway-verify.48XOOG |
| `git add -A && ./scripts/verify-project-docs.sh` | **已运行（绿）** | **24/24**（含新红队门禁） |
| `./scripts/verify-no-pessimistic-locks.sh` | **已运行（绿）** | 无显式悲观锁 / advisory lock |
| `node scripts/verify-dependency-versions.mjs` | **已运行（绿）** | 55 依赖 / 1658 管理版本 |
| `node scripts/verify-integration-test-switches.mjs` | **已运行（绿）** | 42 开关 / 299 测试 / 42 类全部有 run path |
| `mvn -o -pl spring-ai-rag-core -am clean test` | **已运行（绿）** | **8587 tests, 0 failures, 0 errors, 306 skipped** |
| 文档绝对路径检查（升级演练草稿） | **已运行（绿）** | 以用户主目录绝对路径字面量为模式的 `rg` 复核零命中，跨仓引用为相对路径文本 |

### WP11 提交

WP11 收口提交标题：`feat(multi-tenancy): WP11 红队矩阵、函数体断裂负向与升级演练收口`。
hash 以 `git log -1 --oneline` 为准；本账本不内嵌自身提交 hash，避免 amend 后自指陈旧
（WP10 曾因「先插 hash 再 amend」产生 `484d1174`/`6fc3f760` 不一致，已在 §2i 修正）。

### WP11 决策与教训

- 红队门禁映射「证据存在性」而非硬编码数量，新增负向用例时先改门禁再改文档（红→绿顺序保持）。
- verifier 函数体检查按 `prosrc` 判 `RAISE`，避免把「函数在但恒不抛」误判为健康；
  负向用例必须自清理并在 finally 复验正向，否则污染同容器后续断言。
- V84 双路径演练覆盖了 WP2/WP3 历史演练结论（79/80 迁移历史），但**不覆盖**大表在线
  `CONCURRENTLY` 锁窗口；两者都是上线前演练矩阵的一部分，文档不得互相替代。
- 升级演练日志保留 `/tmp`（任务级取证），仓库只落结论与命令复现路径，避免二进制产物入 git。


## 2k. WP12 收口快照（2026-11-14，发布候选）

**WP12 目标产物（规划 §6 WP12）**：中英正式文档成对收口；多租户门禁全部进总入口；
明确「单租户关 RLS ≠ 多租户」；端点 guard 升级为启动期全覆盖；退出条件全部取证。

### WP12 交付内容

1. **「单租户关 RLS ≠ 多租户」明确写入正式文档**（成对）：
   - `docs/configuration-zh-CN.md` / `docs/configuration.md`：多租户配置章节新增
     显式说明——`SINGLE` 只是兼容性底座，`tenant_id` 是普通列而非安全边界，
     绕过应用路径的直接 SQL 写入不会被拦截；只有 `STRICT` + `TenantRlsVerifier`
     启动校验通过 + 应用角色 `NOSUPERUSER NOBYPASSRLS` 才成立行级隔离保证。
   - `docs/architecture-zh-CN.md` / `docs/architecture.md`：§9 多租户隔离架构的
     「模式与守卫」条目同步上述语义。
2. **端点 guard 启动期全覆盖（WP6 待办清零）**：
   新增 `TenantEndpointDeclarationCatalog`（core/tenant 包）：输入契约是
   `ApiRoutePolicyRegistry.describe()` 的启动期真实 MVC 快照
   （`METHOD /pattern -> DECISION`）。平台 capability 端点由
   `PLATFORM_CAPABILITY_OVERRIDES` 人工语义白名单显式裁决
   `PLATFORM_ONLY` + `PLATFORM_CAPABILITY`（当前唯一平台端点
   `/api/v1/rag/tenants`；**不按 `/api/v1/platform/` 前缀猜**）；其余路径
   `TENANT_REQUIRED` + `COLLECTION_ACL`（含约 28 个租户内 MANAGEMENT 端点：
   api-keys / alerts / audit-logs / auth 等）；白名单端点若出现在快照中其
   decision 必须为 MANAGEMENT（防注解变更让平台裁决静默失效）；格式畸形 fail-closed。
   `TenantConfiguration.tenantStartupGuardRunner` 在模式守卫之后调用
   `validate()`——**装配层不再注入空声明集合**（WP6 遗留项已消除）；
   `tenantEndpointGuard` bean 保留 `ObjectProvider` 空安全装配。
   红绿测试 `TenantEndpointDeclarationCatalogTest` 7/7；`TenantEndpointGuardTest` 5/5；
   `OpenApiContractTest` 新增「真实路由快照被声明基线完整覆盖」契约测试，
   42/42（共享契约上下文显式调用 validate，补上 `@SpringBootTest` 不执行
   ApplicationRunner 的取证缺口）。
3. **陈旧口径清理**：`docs/release-checklist-zh-CN.md` / `docs/release-checklist.md`
   与 `scripts/verify-release-artifact.py` 的 `V1..V73` 改为「V1..最新版本」
   （断言本身动态取实际迁移清单，只清理写死标签）；
   `docs/postgres-support-matrix*.md` 的 `V1–V73` → `V1–V84`；
   `docs/DEPLOYMENT.md` 补多租户运维入口（迁移范围 V1–V84、`SINGLE`/`STRICT`
   启动守卫、`STRICT→SINGLE` 回退前置条件、备份点要求）。
4. **门禁收口**：`verify-multi-tenancy-endpoint-guard.sh` 增补 WP12 段
   （catalog 存在性、`fromDescriptions(registry.describe())` 接线、启动期 `validate()`、
   测试存在性）；总门禁仍为 **24/24**（红队门禁已在 WP11 登记，本包不重复计数）。

### WP12 验证台账（本包即时取证）

| 验证 | 状态 | 结果 |
|---|---|---|
| `mvn -o -pl spring-ai-rag-core -am test-compile` | **已运行（绿）** | 编译通过 |
| `TenantEndpointDeclarationCatalogTest` + `TenantEndpointGuardTest` | **已运行（绿）** | 12/12（catalog 7 + guard 5；红测含「漏声明差集」「平台白名单误标」「平台端点条件装配缺席不误报」「格式畸形 fail-closed」） |
| `OpenApiContractTest`（Spring 上下文装配回归 + 真实路由快照契约） | **已运行（绿）** | 42/42；新增「真实 describe() 快照被声明基线完整覆盖」断言（共享契约上下文显式调用 validate） |
| `./scripts/verify-multi-tenancy-endpoint-guard.sh` | **已运行（绿）** | WP6/WP12 段通过 |
| `git add -A && ./scripts/verify-project-docs.sh` | **已运行（绿）** | 24/24 |
| `./scripts/verify-no-pessimistic-locks.sh` | **已运行（绿）** | 无显式悲观锁 / advisory lock |
| `node scripts/verify-dependency-versions.mjs` | **已运行（绿）** | 55 依赖 / 1658 管理版本 |
| `node scripts/verify-integration-test-switches.mjs` | **已运行（绿）** | 42 开关 / 299 测试 / 42 类 |
| `mvn -o -pl spring-ai-rag-core -am clean test` | **已运行（绿）** | 见下方「WP12 收口总账」（端点声明重构后重跑） |
| WebUI `npm run typecheck` / `test:run` / `build` / `test:e2e:mock` | **已运行（绿）** | 见下方「WP12 收口总账」 |
| `scripts/verify-release.sh`（按发布清单） | **已运行（绿）** | 见下方「WP12 收口总账」 |

### WP12 收口总账（2026-11-14 实测回填）

| 项 | 结果 |
|---|---|
| `mvn -o -pl spring-ai-rag-core -am clean test`（端点声明重构后重跑） | **8595 tests, 0 failures, 0 errors, 306 skipped**（surefire 汇总 core 8595；较重构前 8582 增 13 = catalog 测试 7 + OpenAPI 快照契约等） |
| WebUI `npm run typecheck` | PASS（exit 0） |
| WebUI `npm run test:run`（Vitest） | **1143/1143 passed（93 files）** |
| WebUI `npm run build`（生产构建） | PASS（`built in 410ms`，产物齐） |
| WebUI `npm run test:e2e:mock`（Mock Playwright） | 首跑 **1 failed / 319 passed**（`responsive.spec.ts` 1440px chat 页 unlock 重定向 5s 超时，与并发 Maven 构建抢占资源相关）；**单跑重试 117/117 全绿**（含 chat 1440px，4 引擎），判定为环境性 flake，非产品缺陷；不改代码 |
| `./scripts/verify-release.sh --skip-npm-ci --skip-playwright --skip-docker --skip-helm`（串行重跑） | **19 passed, 0 failed, 16 skipped**；跳过项均为本机不具备/显式 opt-in 门禁（Docker 镜像、Helm、全量 Playwright、真实 LLM、runtime E2E、goldenset 等），无一假绿 |
| §7「联合运行时」 | `verify-release.sh` 的文档/Maven/发布产物契约全绿；带真实 LLM 的 `--with-local-runtime` HTTP E2E 属显式 opt-in（需 `.env` 凭据与独占 18081 端口），本任务未运行，**如实标注不冒充已验** |
| 教训（并发构建互踩） | 同一 worktree 内**禁止并发两个 Maven 构建**：`clean` 与对方正在编译的模块产物互删（`spring-ai-rag-starter` 报 `程序包com.springairag.api.service不存在` 的假红）。重跑后确认无真实缺陷；后续长任务必须串行或分目录 |

### WP12 决策与教训

- 「装配层注入空声明集合 + 只有测试夹具证明」是典型的**门禁假绿**：组件存在、
  红测存在，但生产启动路径从未调用。WP6 把它记为待办而没有当作已交付，是正确的。
- 从 `describe()` 这类既有稳定契约派生声明，避免复制 Controller 路径字符串
  （真实受保护路由含 200+ 具体 pattern 且随版本漂移，手写清单必腐烂）。
- **实现检查自纠缺陷 A**：首版用 `describe()` 同时生成声明与 actualPaths，
  两者永远相等，「漏声明红测」退化为不可失败的门禁；已改为 describe() 只出
  actualPaths、声明由本类解析裁决并留差集红测（`Projection` 手工注入额外
  actualPath 即红）。
- **实现检查自纠缺陷 B**：首版按 `/api/v1/platform/` 前缀猜平台端点，把真实
  平台端点 `/api/v1/rag/tenants` 误标 `TENANT_REQUIRED`；已改为人工语义白名单
  显式裁决，并把「租户内 MANAGEMENT（api-keys/alerts 等）」与「平台
  MANAGEMENT（租户生命周期）」分开。
- 平台端点由 `@ConditionalOnBean` 条件装配：无 `TenantLifecycleService` 的
  上下文（契约测试切片）里快照不含 `/api/v1/rag/tenants`，「白名单端点缺失」
  不判错；只有「白名单端点出现但 decision 非 MANAGEMENT」才 fail-closed。
  平台端点已装配的断言由平台面专项测试负责，不与端点声明基线耦合。
- 启动期接线必须避免 Bean 循环：`ApiRoutePolicyRegistry` 是
  `SmartInitializingSingleton`，`describe()` 在单例初始化后可用；
  `TenantEndpointGuard` bean 用 `ObjectProvider` 取 registry，非 Web 上下文为 null 时跳过。
- 正式文档的「V1..Vn」标签会随迁移链漂移；断言是动态的、标签却写死，
  两者都会误导读者。改为「V1..最新版本」+ 权威处写死当前值。

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
| 已知风险 | `origin/main` 落后本地 main，后期合并需按 §8 顺序；在线 CONCURRENTLY 演练仅限演练库，不进迁移链；WP8 无真实外部 Provider 调用（合同级探针），SSE 只接一个端点（见 §2g 缺口）；`TenantEndpointGuard` 声明全覆盖留 WP12；WP9 单连接 PG 用例未覆盖连接池内多连接并发切换；用量/配额维度未落地（WP10） |
| WP8 新增决策 | V82 证据表自带 RLS 与终态断言，不回写已执行的 V80；`verify-multi-tenancy-rls.sh` 按「V80 ⊆ catalog，catalog−V80 == V82 三表」覆盖；WP2–WP8 的 7 个租户 IT 全部登记 `verify-gated-it.sh`，`testing-guide*` 中英成对补命令清单（修复门控开关对账 12 项红） |
| WP9 新增决策 | V83 纯事务 DDL（`deleted_at` + 部分索引 + 终态断言），不改写 V1–V82；凭据表 `tenant_id` 默认 `default` 且认证投影绑租户状态，不静默回落；租户行 + 初始 principal 单事务原子（承接 `../seedance-research/multitenancy-verification/` 幽灵租户教训）；生命周期端点 root + capability 双门槛、`@ConditionalOnBean` 避免无 JDBC 切片上下文失败；`TenantLifecycleController` 不实现切换器/邀请码；账本与参考材料统一相对路径 |
| 阻塞 | 无 |

## 5. 工作包复盘（PLANNING §6.1）

| 工作包 | 收尾时填写：是否关闭可计分交付、验证证据、推进节奏结论 |
|---|---|
| WP1 | **已关闭可计分交付**：3 个产物齐备（69 张逐张裁决文档 / `TenantTableCatalog.java` / 8 项完整性单测），红→绿证据与 4 项门禁全绿。结论：有效推进，无平台期；下一包 WP2 立即进入迁移落地（需先建任务专属 PG）。 |
| WP3 | **已关闭可计分交付**：V80 迁移 + 三角色契约 + verifier/guard + 演练脚本齐备；真实 PG 7/7（五种负向全部拒绝）；JVM 8457 全绿；双路径演练 PASS。**教训**：WP1 把凭据表裁决成「不加列」，与规划 §0/§5.2「凭据表带 tenant_id」矛盾，实施时才暴露——逐张裁决文档必须逐条对照规划冻结口径，不能只按类别规则机械推导。verifier 负向用例的 INSERT 要先满足 V48 的 principal FK/NOT NULL 约束；`rag_tenant` 主键即 tenant_id 需在「平台表无 tenant_id」断言中显式排除。 |
| WP2 | **已关闭可计分交付**（含补充 commit：catalog/测试注释中的写死 69/70/79 数字改为动态基线口径，避免机械漂移）：V75–V79 迁移落地，双路径演练全 PASS、真实 PG 集成 5/5、全模块 8444 tests 全绿。**事故与方案收敛**：V78 原计划 `CREATE INDEX CONCURRENTLY`（Flyway 非事务迁移）被并发事务（pid68 idle in transaction）无限阻塞约 1 小时（外部监控方通报并 cancel）；加 `lock_timeout=30s` 兜底后变 30s 快速失败（SQLState 55P03），但仍暴露「非事务迁移执行期间任何第二条连接持有开放事务都会永久等待」的结构性问题。**收敛结论：迁移链一律纯事务内 DDL，V78 改用普通索引；CONCURRENTLY 仅在演练库在线步骤由脚本承担，不进迁移链**（V78/V79 注释与演练文档均已记录）。教训：Flyway 非事务迁移不能混 `SET`/`DO $$`；`IF NOT EXISTS` 对 INVALID 索引无效（V79 已加 INVALID 检测与清理指引）；PL/pgSQL `RAISE EXCEPTION` 不支持 `||` 拼接；测试侧 Flyway 连接显式加 `lock_timeout=30s` 与 `statement_timeout=900000` 兜底；门禁 latest 迁移号来自 `git ls-files`，未 add 的新文件会导致误报（先 add 再跑门禁）。 |
| WP7 | **核心机制收口（调度层全覆盖；内层 worker 线程池传播留 WP8）**：TenantJobDispatcher + 逐租户分发合同 + 统一 TenantTaskDecorator + 3 处 after-commit 上下文传播 + 现场清单门禁（20 项）全部落地，JVM 8521 全绿、真实 PG 4/4。**教训**：① 参考项目的「单租户下能跑通会掩盖多租户静默读 0 行」直接决定了 SINGLE 必须显式 bootstrap `default`、STRICT 必须拒绝执行，不能留「无租户也能跑」的旁路；② after-commit 必须在**注册时**捕获上下文快照（回调时上下文已清理），不能回调时再读；③ 现场清点与旧计划数字不一致（@Async 3 处而非 5 处、`@Scheduled` 24 处/21 文件），门禁必须按现场动态计算，禁止写死数量；④ 增量构建下 surefire 会跳过测试并报 BUILD SUCCESS，取证必须 `clean test` 或即时读 surefire 报告，否则会把「跳过」写成「通过」。**未完成项见 §2f 待办（TenantDirectory JDBC 实现 + 18 个任务的逐调用点接入），不得对外宣称后台任务多租户化已完成。** |
| WP4 | **已收口（带已知缺口，不阻断进入 WP5，但阻断发布与生产多租户声明）**：V81 补齐 `embedding_1024` ANN 索引；双租户 RLS 集成 5/5、HNSW+RLS 基准 1/1（ANN 存在性与延迟 ≤3× 两条硬门槛通过）；quality-defaults 中英成对落门槛并同步可复现性契约；全仓 `V1–V81` 文档同步。**教训**：规划 §3 曾把旧 `embedding` 列的 V1 HNSW 当作当前事实，V68 已 DROP COLUMN 导致 `embedding_1024` 长期无 ANN 索引，基准测试先暴露「Seq Scan」才补 V81——「事实表」必须按迁移净终态核对，不能按早期迁移文本推断。V81 后 `planUsesIndex=false`（小数据集 planner 选 Seq Scan）与「多租户混合 Recall@K 未测」如实记为已知缺口，不静默判通过；门禁 latest 由 `git ls-files` + 磁盘 `find` 双口径判定，新迁移必须先 `git add` 再跑 `verify-project-docs.sh`。 |

| WP8 | **已关闭可计分交付**：V82 三表 + 结果 owner / 回调权威 / SSE 逐次授权 + 内层 worker 租户传播 + `TenantContext.wrap(Supplier)` 修复全部落地；合同测试 10/10、真实 PG 4/4、JVM 8540 全绿、WP8 静态门禁 + RLS 门禁全绿、门禁总入口 20 → 21 项。**教训**：① 参考项目 `../seedance-research/multitenancy-verification/...` 的 callback_receipt / replay_nonce / job_result 三表模板直接决定了 V82 表结构（按本仓自包含对象模型重写，不引外部私有协议）；② 任意 task ID 查询必须走持久化 receipt，body / 进程内 volatile registry 一律不具权威；③ SSE「建连一次授权」是真实越权路径，撤权后旧订阅必须逐次投递重查 ACL，但当前只接 `embedDocumentStream` 一个 request-scoped 短流端点，如实记为边界而非宣称全覆盖；④ `TenantWorkItemAssert` 必须「先判 tenant 列 null 再判无上下文」，否则旧 fixture 误 fail-closed；⑤ `TenantContext.wrap(Supplier)` 的快照必须在提交时捕获，不能放进 lambda 执行时读；⑥ 真实 PG 集成里 JdbcTemplate 分散借连接会让 GUC 与查询不在同一连接，改单连接 `ConnectionCallback` 后 4/4 绿；⑦ 门控 IT 累积 7 个未登记聚合 runner，`verify-integration-test-switches.mjs` 一次报 12 项红——「先跑门禁再补文档」的顺序不能省，且新增 opt-in IT 必须同步 `verify-gated-it.sh` + `testing-guide*`。 |

| WP9 | **已关闭可计分交付**：V83 软删列 + 生命周期服务/状态机 + 凭据绑定租户 + 平台 capability/root/审计 + WebUI 只读展示全部落地；合同 5/5、投影 2/2、HTTP 21 用例、真实 PG 4/4、JVM 8568 全绿、WebUI 1143 测试 + lint 全绿、总门禁 22/22。**教训**：① 参考项目 `../seedance-research/multitenancy-verification/` 的「幽灵租户」反模式直接决定单事务原子创建；② 平台面 bean 不能放进安全配置强依赖 `JdbcTemplate` + `PlatformTransactionManager`，否则无 JDBC 的 41 个 `OpenApiContractTest` 上下文一起红——条件装配必须对称（服务 bean + controller 都加 `@ConditionalOnBean`）；③ `mvn -pl core test` 不带 `-am` 时依赖模块可能用旧编译产物（`NoSuchMethodError: setTenantId`），取证必须 `-am clean test` 或即时 surefire 报告；④ `git add -A` 是文档门禁 latest 迁移号判定的前置条件，新增迁移/脚本未 tracked 会误报；⑤ 跨仓材料一律相对路径，账本 §0 的 worktree 绝对路径本包已清理。 |

### 5.1 事故严重度判定与解堵复核（2026-11-14，监控方解堵通报）

**严重度判定（按 delivery-workflow §10 / PLANNING §6.2 口径）**：
- **严重度：S2（高）** — 非事务 CONCURRENTLY 迁移在测试设施下可无限挂死，曾在 WP2 期间实际挂死约 1 小时、需外部干预才能解堵；属「实施级事故」而非「方案级失效」。
- **根因分类：测试设施 / 迁移健壮性缺陷（非方案级）** — 不改变核心方案、范围、迁移语义或验收标准，不触发规划检查计数归零。
- **补救与加固现状（2026-11-14 复核）**：
  1. 结构性修复：CONCURRENTLY 整体退出迁移链（V78/V79 注释 + 演练文档 §1/§2/§4 + 账本 §5 WP2 行；后续 WP4 新增 V81 亦沿用纯事务内 DDL）；迁移链一律纯事务内 DDL，不再依赖「无并发开放事务」的隐含前置条件。
  2. 连接级兜底：所有真实 PG 集成测试（`TenantBaseMigration` / `TenantRlsVerifier` / `TenantRlsIsolation` / `TenantHnswRlsBenchmark`）的迁移与断言连接均显式带 `options=-c lock_timeout=30s -c statement_timeout=900000`，同类问题 30s 快速失败而非挂死。
  3. 幂等与 INVALID 防护：迁移链不再产生 INVALID 索引（无 CONCURRENTLY）；V79 / V81 终态断言保留 INVALID 检测与清理指引，覆盖在线 CONCURRENTLY 演练的残留风险。
  4. 监控方通报的三条建议全部落地，无需额外修复动作；本次复核只落档，不改代码。


## 2i. WP10 收尾快照（2026-11-14）

- **产物 A：V84 迁移（纯事务 DDL，无 CONCURRENTLY）**：
  `V84__tenant_quota_windows.sql` 新增 3 张 PLATFORM_GLOBAL 表
  `rag_tenant_quota_policy` / `rag_tenant_quota_window` /
  `rag_tenant_quota_reservation`，全部按 `tenant_id` 键控、无 RLS、含终态断言。
  承接 `../seedance-research/docs/multitenancy/drafts/租户级Credits-分配与回收-实施规划.md`
  的「租户池 / 成员配额 / 分配审计」分层语义，改写为本仓自包含的聚合硬阈值；
  **不做充值、邀请码、成员间分配/收回、billing / hard-limit 结算**。
- **产物 B：租户级硬阈值执行链**：
  `TenantQuotaPolicy` / `TenantQuotaPolicyRepository` / `TenantQuotaLedger`
  （条件 UPDATE/CAS 预留，版本列，禁止阻塞式协调）/ `TenantQuotaEnforcer`
  （DAY/MONTH 两窗口；MONTH 失败补偿回退 DAY；`reservation_id` 映射让异步
  settle/release/TTL 从 DB 恢复租户窗口，不依赖 ThreadLocal）/
  `TenantQuotaExceededException`（复用 `LLM_BUDGET_EXCEEDED` → 429）。
  `BudgetReservationService` 在同一事务里先 per-principal CAS、再 tenant CAS；
  任一层超限整体回滚；`@Autowired` 明确 4 参构造器装配，保留 3 参向后兼容构造器。
- **产物 C：用量事件与 API 租户隔离**：
  `LlmUsageEvent` 增 `tenantId`（旧 21 参构造器从 `TenantContext` 捕获，
  无上下文落 `default`）；`LlmUsageRepository.INSERT_SQL` 显式写 `tenant_id`。
  `JdbcLlmUsageRecorder` 的同步/异步线程池改用 `TenantContext.wrap`，
  修复「异步线程丢上下文 → STRICT 下 RLS WITH CHECK 拒写流式用量事件」缺陷。
  `GET /api/v1/rag/usage?tenantScope=current|TENANT` 只按当前上下文租户聚合，
  不接受任意租户 ID；无上下文 fail-closed；省略时保持旧响应形状（无 `tenantTotals`）。
- **产物 D：门禁与文档**：
  `scripts/verify-multi-tenancy-quota.sh` + `verify-project-docs.sh` 第 23 项；
  `verify-gated-it.sh` 登记 `tenant-wp10:TenantWp10PostgresIntegrationTest`；
  `testing-guide*` 中英成对登记开关；`configuration*` / `rest-api*` /
  `architecture*` / `security-boundary*` 中英成对；全仓 Flyway 事实 V1–V84 同步。

### WP10 验证台账（本包即时取证）

| 验证 | 结果 |
|---|---|
| WP10 focused（合同 4 + 用量 3） | **7/7 绿**（`-am`，避免 api 旧 jar 的 NoSuchMethod/NoClassDefFound） |
| WP10 真实 PG `TenantWp10PostgresIntegrationTest` | **8/8 绿**（opt-in `-Dtenant-wp10.it.enabled=true`：V84 三表/RLS 裁决、DAY+MONTH 预留、超限、settle/release、两租户隔离、DB 驱动跨线程结算、用量 tenant_id、事务内整体生效） |
| PG IT 连接兜底 | 迁移连接 `lock_timeout=30s` / `statement_timeout=900000`；迁移链无 CONCURRENTLY |
| `verify-project-docs.sh` | **23/23** |
| `verify-no-pessimistic-locks.sh` / dependency versions / integration switches | 全绿；42 switches / 297 tests / 42 classes |
| 文档中文/英文成对 | 59 对双语标题结构一致、中文无未翻译英文段落 |

### WP10 新增决策与教训

| 项 | 内容 |
|---|---|
| 语义边界 | 租户级聚合硬阈值是「租户已确定之后」的第二层容量控制，不替代租户解析；无上下文按 `default` 施加阈值（不让漏上下文绕过），上游 fail-closed 另外拒绝请求 |
| 跨线程 | 配额映射与用量事件都不得依赖 ThreadLocal；`reservation_id` DB 映射 + `TenantContext.wrap` 是两条独立防线 |
| 装配 | Spring 组件扫描对多构造器必须显式 `@Autowired`（本包曾因双构造器漏标造成启动装配风险） |
| 真实 PG 先红 | PG 集成测试先暴露 3 个 mock 测不出的生产缺陷：MONTH 窗口行缺失导致永远「月度超限」、`chat_mode` 约束合法值、测试间账本污染；均按真实库反馈修复后才转绿 |
| 静态门禁误报 | `verify-no-pessimistic-locks.sh` 会把 Javadoc 里「禁止 SKIP LOCKED」的说明文字当违规，注释改用「不引入阻塞式协调」措辞；门禁脚本自身也只扫 SQL 语句行 |
| 参考材料 | 只用相对路径引用 `../seedance-research/...`；本包不复制其充值/邀请/成员分配语义 |

### WP10 提交

`6fc3f760` — `feat(multi-tenancy): WP10 租户级配额与用量硬阈值`（amend 后最终 hash）。

### WP12 提交与完成定义（2026-11-14 收口）

- WP12 提交：`git log -1`（`feat(multi-tenancy): WP12 文档收口、端点声明启动期全覆盖与发布验证`）。
  本账本按纪律不嵌入自身提交 hash。
- **实现检查 `3/3`**：按 delivery-workflow §7，编码完成后重新计数（此前「未开始」）。
  实现检查过程中自纠 2 个真实缺陷（缺陷 A：describe() 自产自销使漏声明红测不可失败；
  缺陷 B：按 `/api/v1/platform/` 前缀猜平台端点、误标真实平台端点
  `/api/v1/rag/tenants`），修复后归零重跑：R1 冷读者/范围/冻结决策无实质问题 →
  `1/3`；R2 代码/数据/API/安全锚点与静态门禁（24/24 + 端点守卫门禁）→ `2/3`；
  R3 实施顺序/§7 矩阵/发布回滚/交付风险（全量 `clean test` 8595/0/0、
  `verify-release.sh` 19/0/16）→ `3/3`。
- **§7 验收矩阵**：迁移（V74→V84 双路径演练）、RLS（A/B 双租户 + 无 GUC + 跨租户写 +
  错误 GUC + 连接池复用）、启动 verifier（负向集）、请求链路（GUC 回读 + 非事务自动提交）、
  Endpoint guard（describe() 真实快照 + 漏声明红测）、后台任务（21/5/3 清单 + 上下文清理）、
  结果/回调（owner/receipt/任意 task ID 拒绝）、SSE（逐次重查 ACL）、SINGLE 守卫
  （凭据全 default + 拒绝非 default）逐行有证据，见 §2b–§2k 各包验证台账。
- **完成定义达成**：WP1–WP12 全部收口；规划检查 `3/3`、实现检查 `3/3`；
  总门禁 24/24；全量 JVM 测试 8595/0/0；发布脚本 19/0/16（跳过项均为显式 opt-in，不假绿）。
- **两处已知未验项（如实留档，不冒充已验）**：
  1. **大表在线 CONCURRENTLY / 分批回填 / 锁窗口演练**：V78 事故后迁移链已改为纯事务
     DDL，V79/V81 保留 INVALID 检测与清理指引；本任务未在生产规模 embedding 大表上做
     在线索引演练，属**上线前运维专项**（规划 §7 已列为阻断项，实施阶段以「迁移链不产生
     INVALID + 测试连接 lock_timeout/statement_timeout 兜底」替代，不改写为「已验」）。
  2. **`--with-local-runtime` / `--with-runtime-e2e` HTTP E2E 与真实 LLM smoke**：需
     `.env` 凭据、独占 18081 端口及真实模型服务，本任务环境不具备；`verify-release.sh`
     相关 16 个 SKIP 均为显式 opt-in，无一假绿。
- **外部参考经验教训充分性复核**：[外部参考经验教训](MULTI_TENANCY_EXTERNAL_LESSONS.md)
  §1 速查 / §4.2–4.9（seedance `multitenancy-verification/` 7 案例、红绿证据、否决弯路、
  可复用 SQL/骨架）/ §5（AI-Native-BOS 术语分层、承重列、函数静默失效）/ §6 影响清单 /
  §7 复现命令完整落档；实施期 WP3/WP5/WP9/WP10/WP11 逐包引用其教训（GUC 三件套、
  幽灵租户、异步上下文、RLS 负向、凭据表不启用 RLS 等）。三份 `docs/drafts/` 文档对参考
  项目材料**全部使用相对路径文本**，无绝对路径；以用户主目录绝对路径字面量为模式对三份 `MULTI_TENANCY*.md` 做 `rg` 复核，零命中。
- **Git 交付边界**：按用户/监控方指令「未获明确授权不 merge/push」，本分支停在
  `feature/multi-tenancy-phase1` 本地提交，等待用户最终批准后按 delivery-workflow §8
  合并回 `main` 并推送；本任务不主动观察对抗式审核 agent 状态。

## 6. V85 纠正迁移（2026-11-14，合并 main 后 STRICT 真实 PG 复跑缺陷修复）

> 监控方在 main 上复跑 9 组 STRICT 真实 PG 集成（44 跑 2 败）通报两个缺陷；
> 用户已批准合并 main 并 push（`1ad305c7`），V82/V84 视为已执行迁移，禁止改写。

### 6.1 缺陷与裁决

| 项 | 严重度 | 现象 | 根因 | 处置 |
|---|---|---|---|---|
| 缺陷 1 | **S2 一致性** | `TenantBaseMigrationPostgresIntegrationTest#platformGlobalTablesDoNotCarryTenantIdButCredentialsDo` 断言 `rag_tenant_quota_policy` 不应有 tenant_id，实际有 | **测试断言口径错，不是 schema 错**：WP10 设计本意是「PLATFORM_GLOBAL 按 tenant_id 键控、无 RLS」，`TenantTableCatalog` / `MULTI_TENANCY_TABLE_CATALOG.md` §5 已明确裁决；「PLATFORM_GLOBAL 无 tenant_id 列」把「无 RLS」误写成「无键控列」，全仓只有 `rag_tenant` 主表与 WP10 三张配额表带 tenant_id，其余 20 张 PLATFORM_GLOBAL 均无 | **catalog 裁决正确，不改**；测试改为「PLATFORM_GLOBAL 表不得启用 RLS」+ 显式断言三张配额表保持 tenant_id 键控（查询由 `TenantQuotaPolicyRepository` / `TenantQuotaLedger` 显式带谓词，登记访问点） |
| 缺陷 2 | **S1 安全** | 同测试 `#everyTenantPrivateTableHasTenantIdColumn` 断言 `rag_tenant_result_receipt` 缺 `tenant_id NOT NULL DEFAULT 'default'` | V82 的 3 张 TENANT_PRIVATE 证据表 tenant_id 均 `NOT NULL` 但**缺 DEFAULT**（主键首列，回填需谨慎）；SINGLE 下若写入方漏显式 tenant_id，无 DB 默认兜底；STRICT 的 RLS WITH CHECK 会拒写但 DB 层缺统一口径 | **新增 V85 纠正迁移**：三表统一 `ALTER COLUMN tenant_id SET DEFAULT 'default'`（纯事务 DDL，不改历史行——列已 NOT NULL 均有值）；补 tenant-leading 复合索引 `idx_rag_tenant_result_receipt_scope (tenant_id, collection_id, owner_principal_id)`（与 V82 owner 索引/主键/唯一约束互补）；终态断言：三表 DEFAULT + RLS 仍 ENABLE+FORCE、索引存在、三张 PLATFORM_GLOBAL 配额表**不得启用 RLS**且保持键控 |

### 6.2 V85 红绿证据

| 验证 | 结果 |
|---|---|
| 修复前（main `1ad305c7` 干净库复跑，`-Dtenant-base.it.enabled=true`） | **5 跑 2 败**（与监控方通报一致：缺陷 1 `rag_tenant_quota_policy 不应有 tenant_id 列`；缺陷 2 `rag_tenant_result_receipt 缺少 tenant_id NOT NULL DEFAULT 'default'`） |
| 修复后同开关复跑 | **5/5 绿**（V85 迁移按序应用，断言通过） |
| 9 组 STRICT 真实 PG 集成（`verify-gated-it.sh` 全 tenant 开关） | **44/44 绿**（HNSW 基准 1 + RLS verifier 负向 9 + 基类迁移 5 + WP8 证据 4 + RLS 双租户 5 + GUC 三件套 4 + JobDispatcher 4 + WP10 配额 8 + WP9 生命周期 4） |
| 静态门禁（端点守卫 / WP10 配额 / WP3+WP11 RLS / 依赖版本 / 集成开关 / 无悲观锁） | 全绿 |
| `mvn -o clean test`（全 reactor，SINGLE 回归） | **BUILD SUCCESS**：API 568 + documents 90 + core 8595 + starter 49，**0 failures / 0 errors**（core 306 skipped 为 `-D` 门控集成测试，已由上表 9 组 44/44 覆盖） |
| 文档门禁 `verify-project-docs.sh` | **24/24**（迁移范围标签同步 V85：AGENTS / developer-reference* / README* / project-docs skill；其余文档按 WP12 教训改「V1..最新版本」防漂移） |

### 6.3 教训

- 「平台全局表无 tenant_id 列」与「平台全局表无 RLS」是两个不同不变量；V84 按键控
  设计被断言口径误伤。PLATFORM_GLOBAL 的安全边界是**无 RLS + 显式谓词访问点登记**，
  不是无键控列。
- V82 三张证据表漏 DEFAULT 是与 V80「统一加列口径」不一致的同类漂移；主键首列加
  DEFAULT 不能回填历史行（NOT NULL 已保证），只能前向纠正。
- WP12 的「V1..Vn 标签随迁移链漂移」教训再次应验：本次门禁揪出 5 处过期标签
  （AGENTS / developer-reference* / README* / project-docs skill），按门禁契约写死
  当前值 V85；其余文档改为「V1..最新版本」。
- 首次 `verify-release.sh` 因新建 worktree 未装 `node_modules` 报 6 项 WebUI 假红
  （`npm run lint/test:run/build` exit 127，连带 Maven webui package / 内嵌 bundle /
  发布产物契约）。**不是产品缺陷**：按 WORKTREE.md 不跨 worktree 共享运行时资源，
  在 worktree 内 `npm ci` 后重跑取证；不把假红写成修复完成。
- `verify-release.sh --skip-playwright --skip-docker --skip-helm`（worktree 内 npm ci 后）
  **20 passed / 0 failed / 15 skipped**（跳过项均为显式 opt-in，不假绿）。
