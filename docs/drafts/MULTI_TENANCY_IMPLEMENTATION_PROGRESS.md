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
| 工作区状态 | **WP7 已收口**：TenantJobDispatcher + 逐租户分发 + TenantTaskDecorator 统一线程池包装 + after-commit 上下文传播 + 现场清单门禁（20 项）。见 §2f |
| 当前唯一 `in_progress` | **WP8 — 结果 owner、回调权威、SSE 逐次授权** |
| 已完成工作包 | WP1–WP7；证据见 §2b/§2c/§2d/§2e/§2f |
| 紧接着要做 | WP8：长任务/导入/评估结果补 owner/tenant/receipt；Provider 回调 receipt/intent/replay/manual audit；SSE 每次投递重查 ACL；先红后绿；退出条件见规划 §6 WP8 |
| 规划检查计数 | `3/3`（终审核 APPROVE，规划基线冻结；本阶段未触发归零） |
| 实现检查计数 | 未开始（按 delivery-workflow §7，编码完成后才计数） |

## 1. 恢复入口（当前：WP8 — 结果 owner / 回调权威 / SSE 逐次授权；WP3 以下为历史快照）

**WP8 目标产物（规划 §6 WP8）**：长任务/导入/评估结果表补 owner/tenant/receipt；
任意 task ID 查询不得触发外部 Provider；Provider 回调按 operation 校验 receipt、
幂等 intent、replay、owner、manual audit principal；SSE 每次投递重查 ACL
（撤权后旧订阅不再收到事件，多副本逐个验证）。
退出：反模式红测先失败、推荐实现绿测通过；SSE 撤权后立即停止推送；无外部 Provider 真实调用。
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
  1. `TenantDirectory` 目前只有 SPI，**没有 JDBC 实现**（读 `rag_tenant` 的平台池实现），
     STRICT 下逐租户枚举需在 WP8/WP9 补齐；当前 `dispatchForEachActiveTenant` 由调用方注入。
  2. 18 个 `@TenantScopedJob` 标注只是「声明该任务是租户私有作用域」；
     **逐租户枚举调用点尚未逐个接入**（例如 `EmbeddingJobWorker` / `EvaluationSuiteWorker`
     仍按自身 claim 逻辑读任务表，未改成 dispatcher 逐租户驱动）。
     这两项是 WP8 的实质工作，不得当作已完成。
  3. `ApiKeyManagementService.authenticate` 的凭据投影仍未带出 `tenant_id` 与租户状态
     （WP5 遗留，WP9 落地）。

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
| WP7 | **部分收口（核心机制完成，逐调用点接入未完成）**：TenantJobDispatcher + 逐租户分发合同 + 统一 TenantTaskDecorator + 3 处 after-commit 上下文传播 + 现场清单门禁（20 项）全部落地，JVM 8521 全绿、真实 PG 4/4。**教训**：① 参考项目的「单租户下能跑通会掩盖多租户静默读 0 行」直接决定了 SINGLE 必须显式 bootstrap `default`、STRICT 必须拒绝执行，不能留「无租户也能跑」的旁路；② after-commit 必须在**注册时**捕获上下文快照（回调时上下文已清理），不能回调时再读；③ 现场清点与旧计划数字不一致（@Async 3 处而非 5 处、`@Scheduled` 24 处/21 文件），门禁必须按现场动态计算，禁止写死数量；④ 增量构建下 surefire 会跳过测试并报 BUILD SUCCESS，取证必须 `clean test` 或即时读 surefire 报告，否则会把「跳过」写成「通过」。**未完成项见 §2f 待办（TenantDirectory JDBC 实现 + 18 个任务的逐调用点接入），不得对外宣称后台任务多租户化已完成。** |
| WP4 | **已收口（带已知缺口，不阻断进入 WP5，但阻断发布与生产多租户声明）**：V81 补齐 `embedding_1024` ANN 索引；双租户 RLS 集成 5/5、HNSW+RLS 基准 1/1（ANN 存在性与延迟 ≤3× 两条硬门槛通过）；quality-defaults 中英成对落门槛并同步可复现性契约；全仓 `V1–V81` 文档同步。**教训**：规划 §3 曾把旧 `embedding` 列的 V1 HNSW 当作当前事实，V68 已 DROP COLUMN 导致 `embedding_1024` 长期无 ANN 索引，基准测试先暴露「Seq Scan」才补 V81——「事实表」必须按迁移净终态核对，不能按早期迁移文本推断。V81 后 `planUsesIndex=false`（小数据集 planner 选 Seq Scan）与「多租户混合 Recall@K 未测」如实记为已知缺口，不静默判通过；门禁 latest 由 `git ls-files` + 磁盘 `find` 双口径判定，新迁移必须先 `git add` 再跑 `verify-project-docs.sh`。 |

### 5.1 事故严重度判定与解堵复核（2026-11-14，监控方解堵通报）

**严重度判定（按 delivery-workflow §10 / PLANNING §6.2 口径）**：
- **严重度：S2（高）** — 非事务 CONCURRENTLY 迁移在测试设施下可无限挂死，曾在 WP2 期间实际挂死约 1 小时、需外部干预才能解堵；属「实施级事故」而非「方案级失效」。
- **根因分类：测试设施 / 迁移健壮性缺陷（非方案级）** — 不改变核心方案、范围、迁移语义或验收标准，不触发规划检查计数归零。
- **补救与加固现状（2026-11-14 复核）**：
  1. 结构性修复：CONCURRENTLY 整体退出迁移链（V78/V79 注释 + 演练文档 §1/§2/§4 + 账本 §5 WP2 行；后续 WP4 新增 V81 亦沿用纯事务内 DDL）；迁移链一律纯事务内 DDL，不再依赖「无并发开放事务」的隐含前置条件。
  2. 连接级兜底：所有真实 PG 集成测试（`TenantBaseMigration` / `TenantRlsVerifier` / `TenantRlsIsolation` / `TenantHnswRlsBenchmark`）的迁移与断言连接均显式带 `options=-c lock_timeout=30s -c statement_timeout=900000`，同类问题 30s 快速失败而非挂死。
  3. 幂等与 INVALID 防护：迁移链不再产生 INVALID 索引（无 CONCURRENTLY）；V79 / V81 终态断言保留 INVALID 检测与清理指引，覆盖在线 CONCURRENTLY 演练的残留风险。
  4. 监控方通报的三条建议全部落地，无需额外修复动作；本次复核只落档，不改代码。
