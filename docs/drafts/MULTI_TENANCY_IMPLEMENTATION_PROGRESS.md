# 多租户化实施进度（唯一账本）

> 中文单语。权威规划：[多租户化实施规划](MULTI_TENANCY_IMPLEMENTATION_PLAN.md)（本文与其开头互相链接）。
> 本文件是外部参考仓库 `../seedance-research/docs/PLANNING.md` §6 意义上的唯一恢复入口
> （跨仓路径为行内代码文本，非 Markdown 链接；本机落点见
> [外部参考经验教训](MULTI_TENANCY_EXTERNAL_LESSONS.md) §2 换算表），
> 只记录已发生的事实；未运行或失败的验证必须显式标注，不得提前写成完成。

## 0. 当前快照（每次恢复工作先读这里）

| 项 | 值 |
|---|---|
| 记录时间 | 2026-11-13 |
| 工作区 | 隔离 worktree `/Users/yangjiefeng/Documents/wubuku/spring-ai-rag-multi-tenancy` |
| 分支 | `feature/multi-tenancy-phase1` |
| 基线 commit | `04e2e678`（本地 `main`，规划终审核 APPROVE 后的规划 checkpoint） |
| 主工作区 | `/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`，`main` @ `04e2e678`，创建 worktree 时 clean |
| 工作区状态 | WP1 已完成并即将形成 commit；**WP2 未开始，尚无业务表结构/迁移改动** |
| 当前唯一 `in_progress` | **WP2 — `rag_tenant` 主表、`tenant_id` 回填、复合约束** |
| 已完成工作包 | **WP1 — 表归属清单与 catalog 骨架**（证据见 §2） |
| 紧接着要做 | WP2 先红测 → 写 V75+ 迁移 → 全新库/V74 存量库双路径升级演练 → 绿测（见 §1） |
| 规划检查计数 | `3/3`（终审核 APPROVE，规划基线冻结；本阶段未触发归零） |
| 实现检查计数 | 未开始（按 delivery-workflow §7，编码完成后才计数） |

## 1. 恢复入口（WP2）

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
| 真实 PostgreSQL 集成（WP2/WP4） | **未运行 / 未适用** | WP1 为静态清单，不需 PG；任务专属库尚未创建 |
| WebUI typecheck / build / Mock Playwright | **未运行 / 未适用** | WP1 不触碰 WebUI |

## 3. 环境与隔离边界（WORKTREE.md 硬门禁）

- 本 worktree 目前**未创建任何 `.env` / `.env.*`**，未改主工作区任何环境文件。
- **禁止**使用 `dev` profile、`scripts/dev.sh`、`seedance_dev` 或任何共享 `*_dev` 数据库做测试、迁移、启动 smoke、夹具写入。
- WP2 起若需要真实 PostgreSQL，必须使用**任务专属**隔离库，并显式覆盖业务 / Flyway / platform 三套连接；配置只能用命令级环境变量，不写回文件。
- 隔离端口：暂未分配；如需启动服务，使用与日常 8081 / 18081 / 18082 不冲突的专属端口并在此登记。
- 后台任务 / 临时数据库 / 临时资源：**当前无**。WP1 全程为静态解析 + JVM 单测，未连任何数据库；有任何新增必须在此登记归属与清理责任。
- 主工作区 `main` 与 `origin/main`（`3b83ad35`）存在本地领先；按 delivery-workflow §8，开发期间需 `git fetch origin` 并持续合并已 push 的 `origin/main`，最终交付前合并最新 `origin/main`。

## 4. 决策、偏离与风险

| 项 | 内容 |
|---|---|
| 规划基线 | `04e2e678`；终审核 APPROVE（用户转述），无剩余实质性修改意见 |
| 实施起点 | 用户明确要求「创建隔离 worktree，开始实施」，按 PLANNING.md §5 已获批准 |
| 本轮新增决策 | 分支名 `feature/multi-tenancy-phase1`；worktree 目录 `../spring-ai-rag-multi-tenancy`；阶段一先做 WP1–WP4；凭据类别细化为 4 张（含 rotation / provisioning 生命周期表，与规划 §5.2「凭据及其生命周期」一致）；平台全局 20 张逐张裁决理由见裁决文档 |
| 相对规划的偏离 | 无方案级偏离；规划头部补「进度账本」链接属 PLANNING §6 要求的配套编辑，不改变方案语义。红测中「凭据表应为 2 张」的断言口径偏窄，已按实现契约修正为 4 张并补「凭据表不得启用 RLS」断言 |
| 已知风险 | WP2 大表 `rag_embeddings` 的 `CREATE INDEX CONCURRENTLY` 需 Flyway 非事务迁移；`origin/main` 落后本地 main，后期合并需按 §8 顺序 |
| 阻塞 | 无 |

## 5. 工作包复盘（PLANNING §6.1）

| 工作包 | 收尾时填写：是否关闭可计分交付、验证证据、推进节奏结论 |
|---|---|
| WP1 | **已关闭可计分交付**：3 个产物齐备（69 张逐张裁决文档 / `TenantTableCatalog.java` / 8 项完整性单测），红→绿证据与 4 项门禁全绿。结论：有效推进，无平台期；下一包 WP2 立即进入迁移落地（需先建任务专属 PG）。 |
