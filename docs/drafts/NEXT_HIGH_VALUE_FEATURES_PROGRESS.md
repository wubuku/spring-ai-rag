# 下一批高价值功能实施进度：有界零停机 API Credential 轮换

> 日期：2026-08-27  
> 规划：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)

## 1. 恢复入口

- 工作区：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
- 当前规划基线：`main` / `origin/main` =
  `1da13c03f0c1df524a35733496ba502dde5caa23`
- 用户约束：只使用当前工作区；除非明确安排并行，不创建 worktree。
- 当前分支：`feature/api-credential-rotation-20260827`。
- 当前阶段：V55 实现、长青文档、完整 Mock/构建门槛、双实例真实 HTTP/WebUI、
  MiniMax 真实 Chat 生命周期和 SiliconFlow 真实 Embedding/RAG 均已通过；合并后
  基线的统一 13 项门禁和独立真实 RAG smoke 也已完成，正在收口 Git 交付。
- 下一步：提交并推送特性分支，合并并推送 `main`，再从最终提交重跑固定门禁并确认
  工作区干净。

## 2. 已完成

- [x] 确认 main、origin/main、HEAD一致且工作区起始干净。
- [x] 确认额外 worktree 已全部移除。
- [x] 读取 `AGENTS.md`、project-docs skill、交付工作流和草稿生命周期。
- [x] 深读 API principal entity/repository/service/controller/DTO、V48-V54 migration、
  capability discovery、WebUI、PostgreSQL integration、Mock/真实 Playwright和验收脚本。
- [x] 将外部 Client 的生产需求抽象为本仓自包含的通用 staged credential rotation合同；
  plan/code/docs 不依赖外部项目名称或背景。
- [x] 冻结 V55、retire_at current/retiring 状态、prepare/complete/cancel、deadline
  fail-closed、即时 rotate兼容和 family revoke方案。
- [x] 编写自包含规划与一次性验收矩阵。

## 3. 关键决策

1. 保留现有 `/rotate` 的即时旧 key失效合同，新增独立 staged endpoints。
2. 每个 principal至多一个 current和一个 retiring credential。
3. active current 由 `enabled=true AND retire_at IS NULL` 唯一确定，不能从 enabled row
   顺序推断。
4. `retire_at` 直接参与认证 SQL；cleanup延迟不扩大安全窗口。
5. cancel只在 deadline前允许，且不回收 credential version。
6. revoke禁用整个 family；quota、ACL、Chat owner和用量仍绑定 stable principal。
7. mixed V54/V55部署期间冻结管理写，全部实例升级后才允许 prepare。
8. rotation ledger只保存hash和metadata，不保存raw secret；prepare精确replay只返回
   metadata，不重放secret；不做secret manager集成。

## 4. 规划检查计数

当前连续无实质问题计数：`3/3`。

规划已完成连续三轮无实质问题检查。最终一轮覆盖数据模型、事务/并发、安全/兼容、
实施切片、验收矩阵、真实 LLM、部署回滚和 Git 交付；结果为 `3/3`。后续如修改规划正文，
必须重新从 `0/3` 开始。

### 已发现并修正的规划问题

| 时间 | 检查范围 | 发现 | 处理 | 计数 |
|------|----------|------|------|------|
| 2026-08-27 | 第 1 轮尝试：需求闭环、自包含和 Client 操作路径 | overlap 未限定 principal expiry；current version 没有数据库引用完整性；过期 retiring row 的投影、lazy cleanup 与即时轮换语义不完整 | actual deadline 截短到 principal expiry；新增 deferred 复合 FK；冻结 live/expired retiring 的投影和 lazy 收敛语义，并补时钟要求与验收项 | `0/3` |
| 2026-08-27 | 重新第 1 轮尝试：升级兼容和状态真相源 | principal current-version 指针与 credential 外键造成循环写入，并让 V54 rollback binary 难以维持新增字段；设计复杂度和回滚风险不必要 | 移除 principal 指针与外键；V55 只新增 `retire_at`，用两个 enabled partial unique index 定义 current/retiring；冻结 family revoke 时间戳幂等规则和无 pending 时 V54 写兼容 | `0/3` |
| 2026-08-27 | 再次第 1 轮尝试：响应丢失和 self-management 恢复 | prepare 响应丢失后，旧 retiring credential仍有效，但如果 complete/cancel只接受未知的新current keyId，调用方无法自救 | complete/cancel允许pending family任一active member keyId；legacy NORMAL按stable principal做self授权，跨principal仍拒绝 | `0/3` |
| 2026-08-27 | 第 1 轮恢复语义再收敛 | member-key scoped complete/cancel虽可恢复，但旧member完成后的网络重试仍有歧义，且轮换本质是principal级操作 | complete/cancel改为principal-scoped；用active current与max version区分complete/cancel幂等重试；prepare仍用current keyId提供并发前置条件 | `0/3` |
| 2026-08-27 | 第 1 轮操作身份与迟到重试检查 | principal-scoped action仍可能让旧complete/cancel重试误操作后来创建的pending rotation，无法满足安全幂等 | prepare强制Idempotency-Key；V55增加无secret rotation operation ledger与stable rotationId；prepare精确replay metadata，status/complete/cancel绑定rotationId，terminal状态有界保留 | `0/3` |

| 2026-08-27 | 规划复查：状态权威性、到期语义、并发清理、能力与回滚 | ledger冗余保存credential version可能与credential row漂移；deadline后的PENDING没有独立终态；scheduler与写操作的竞争顺序不够明确；能力发现和rollback gate缺少关键可操作字段；prepare惰性清理和WebUI仍残留“完成”语义 | ledger仅保存source/target credential ID并从权威row读取version；增加EXPIRED终态及status/complete/cancel的lazy transition；冻结scheduler“有界读ID→principal管理写资格→事务重检→条件推进→竞争失败继续”的顺序；补充idempotency/replay/retention能力字段，并要求回滚前同时清零retiring row和PENDING operation；所有到期路径统一推进EXPIRED并拒绝迟到 complete/cancel | `0/3` |
| 2026-08-27 | 规划复查：SQL索引、operation归属与响应缓存 | partial unique index若写成普通约束无法直接实施；仅有 credential 外键不能证明 source/target 属于 operation principal；staged action 的 no-store 范围不应只覆盖 prepare/status | 将示例冻结为 PostgreSQL `CREATE UNIQUE INDEX ... WHERE`；要求事务校验 source/target 两行均属于 operation principal；prepare/complete/cancel/status 全部返回 `Cache-Control: no-store` | `0/3` |
| 2026-08-27 | 规划复查：ledger 完整性约束 | operation ledger 的关键关联、状态和时间列若可为空，会允许无法表达的半成品状态；PENDING 唯一性仍需明确数据库实现形式 | 将 source/target、principal、fingerprint、overlap、deadline、status、created/updated 列冻结为 `NOT NULL`（terminal_at 仅终态必填），并将 single-PENDING 保证明确为 partial unique index | `0/3` |
| 2026-08-27 | 第 2 轮复查：幂等规范化、终态持久化和响应投影 | 省略 overlap 与显式默认值的指纹边界未冻结；ledger 状态与 terminal_at 的数据库一致性未明确；retention 后 status/replay 行为、NORMAL action 的 stable principal 传递和 credential 列表布尔投影仍有实施歧义 | 冻结 `DEFAULT`/显式秒数的规范化指纹；增加 status/terminal_at 与 source/target distinct 的 CHECK；retention 后统一 404；明确 controller 传递认证 principal、legacy static 禁止 staged、current/retiring 投影公式 | `0/3` |
| 2026-08-27 | 第 2 轮复查：敏感响应头和 capability DTO 兼容性 | 规划只要求 staged 正常响应 `no-store`，未覆盖 GlobalExceptionHandler 和认证过滤器直接返回的错误；`Features` 增加 `credentialRotation` 后若删除三参数构造器会破坏既有调用方 | 冻结统一 staged URI 判定/响应头策略，覆盖 controller 成功、MVC 异常和认证过滤器短路错误（含 OpenAI envelope）；保留三参数 `Features` 构造器并以默认无 staged 能力委托到新四参数构造器，正式 catalog 使用完整四参数对象 | `0/3` |
| 2026-08-27 | 第 2 轮复查：配置单位与能力投影一致性 | overlap 以整数秒进入 API、retention 以整数天进入 capability，但规划未禁止非整秒/非整天 `Duration`，可能出现配置与能力发现不一致 | 要求 default/max overlap 为整秒，operation retention 为整天；能力字段分别返回精确秒数和天数，不做静默截断 | `0/3` |
| 2026-08-27 | 第 2 轮复查：policy expiry 与 pending deadline | 现有 policy API 可以在 rotation 创建后提前 principal expiry；若 operation/retiring deadline 不同步，会出现超过 principal 有效期的悬挂 pending 状态 | 冻结 policy 缩短 expiry 时在同一事务中 clamp operation `expires_at` 与 retiring `retire_at`，已到期则推进 `EXPIRED`；policy 延长不得延长既有 rotation deadline，并加入 PostgreSQL/HTTP 验收项 | `0/3` |
| 2026-08-27 | 第 3 轮复查：root/legacy 授权验收一致性 | HTTP 契约规定 root mode 仅 environment root 可管理 staged rotation，但后端矩阵的通用条目未区分模式，可能导致测试错误地期待数据库 credential 在 root mode 自助完成轮换 | 明确 legacy NORMAL 才允许 old/new credential self status/complete/cancel；root mode 由 environment root 执行，跨 principal 始终拒绝 | `0/3` |
| 2026-08-27 | 第 3 轮最终检查：数据/API/实施交付闭环 | 未发现影响正确性、安全、兼容性、数据一致性或可实施性的实质问题 | 交叉核对 V55 索引与 ledger、事务/并发顺序、认证 deadline、授权模式、no-store、shown-once secret、前后端验收矩阵、混合版本回滚和 Git 交付顺序；文档门禁、锁策略、Shell 语法与 diff 检查均通过 | `3/3` |

## 5. 实施状态

- [x] 规划连续 `3/3`
- [x] 创建同工作区专用特性分支
- [x] Slice A：API DTO、error、V55、properties、capability schema
- [x] Slice B：repository/service生命周期和PostgreSQL矩阵
- [x] Slice C：controller合同与聚焦测试（双实例HTTP合同待统一门禁运行验收）
- [x] Slice D：WebUI、Vitest、Mock Playwright（真实Playwright待统一门禁运行验收）
- [x] Slice E：双语长青文档、真实LLM、完整门槛
- [x] 同步最新 origin/main并按合并后基线完整复验
- [ ] 合并推送 main、归档 plan/progress、进入下一轮

## 6. 验证证据

- `mvn -q -pl spring-ai-rag-api,spring-ai-rag-core -am -DskipTests compile`：通过。
- `git diff --check`：通过。
- 已确认生产代码不再调用模糊的
  `findFirstByPrincipalIdAndEnabledTrue`；兼容 repository 方法暂时保留供旧测试/扩展迁移。
- 后端冻结矩阵已一次性补齐：V54→V55、部分唯一索引与 ledger约束、
  prepare/replay/conflict、complete/cancel/expiry/revoke、principal expiry clamp、
  双 credential共享principal/quota、并发prepare单赢家、cleanup/retention、
  ledger跨principal引用fail-closed、controller与认证短路`no-store`。
- 尚未执行 PostgreSQL、MockMvc、前端和真实 LLM 验收。

### 2026-08-27 后端验收 checkpoint

- `mvn -pl spring-ai-rag-core -am -DskipTests test-compile`：PASS。
- API/Core聚焦快速测试：PASS；API `DtoTest` 468项，Core 109项，覆盖 DTO脱敏、
  properties、service、controller、capability discovery、认证过滤器和异常响应。
- 首次 PostgreSQL运行：21项测试均PASS，但此前中断遗留的JaCoCo执行数据导致 report
  `Unknown block type 65`，未计为门槛通过。
- 清理生成物后原样复验：
  `TESTCONTAINERS_RYUK_DISABLED=true TESTCONTAINERS_CHECKS_DISABLE=true mvn -pl
  spring-ai-rag-core -am clean -Dtest=ManagedApiPrincipalPostgresIntegrationTest
  -Dsurefire.failIfNoSpecifiedTests=false -Dmanaged-api-principal.it.enabled=true
  -Dtestcontainers.pg.image=pgvector/pgvector:pg16 test`：PASS，21/21，reactor
  `BUILD SUCCESS`。
- PostgreSQL证据覆盖V1→V55空库、V47与V54升级、current/retiring与single-PENDING
  部分唯一索引、prepare/replay/conflict、complete/cancel/expiry/revoke、principal
  expiry/policy clamp、双credential共享principal/quota、并发prepare单赢家、
  cleanup/retention、plaintext=0和跨principal ledger引用fail-closed。

### 2026-08-27 WebUI 实施 checkpoint

- 已核对后端最终 staged HTTP 合同和 DTO：prepare 使用
  `POST /api-keys/{keyId}/rotations` 且必须携带 `Idempotency-Key`；status、complete、
  cancel 均绑定稳定 `rotationId`。
- 前端实施范围冻结为：API types/client、principal pending 投影、staged 主路径、
  shown-once secret、同 key 重试/replay 恢复、complete/cancel、明确的 immediate
  次级路径、EN/ZH 文案、Vitest 与 Mock/真实 Playwright。
- 当前开始一次性修改前端实现和冻结测试矩阵；完成前不把局部代码检查当作验收结论。

### 2026-08-27 WebUI Mock 验收 checkpoint

- WebUI 已完成 staged rotation API client、principal current/retiring/deadline 投影、
  prepare/complete/cancel、shown-once secret、replay恢复提示和 immediate兼容路径。
- `npm run typecheck`：PASS。
- API Key 聚焦 Vitest：3个文件、20项全部PASS，覆盖API header/path、首次secret、
  同Idempotency-Key人工retry、replay raw null、pending complete/cancel和immediate endpoint。
- 核心 Mock Playwright：
  `BASE_URL=http://127.0.0.1:15173 npx playwright test
  e2e/api-key-mvp.spec.ts --project=chromium`：PASS，1/1。
- 浏览器证据覆盖：首次prepare显示secret/deadline；关闭后secret从DOM消失；模拟服务端
  commit后首响应503，Axios自动retry使用同一Idempotency-Key且replay不显示secret；
  pending表格执行complete/cancel；immediate仍命中旧endpoint；所有raw/root credential
  均未进入URL、localStorage、sessionStorage或console。
- 下一步执行完整前端Vitest、生产构建和alignment门禁，然后扩展真实后端Playwright。

### 2026-08-27 WebUI 完整门槛 checkpoint

- `npm run typecheck`：PASS。
- `npm run test:run`：PASS，32个文件、229项测试全部通过。
- `npm run build`：PASS，Vite生产构建完成。
- `npm run check:alignment`：PASS。
- `git diff --check -- spring-ai-rag-webui`：PASS。
- Slice D 的 Mock与构建硬门槛已满足；下一步只在真实后端运行后补充真实Playwright证据。

### 2026-08-27 真实验收矩阵扩展 checkpoint

- 已确认 `.env` 提供 PostgreSQL、environment root 和 OpenAI-compatible Chat所需配置；
  密钥只在进程环境中读取，不写入日志、文档或Git。
- 已确认现有 `verify-managed-api-principals.sh --with-real-llm` 会创建隔离PostgreSQL、
  双后端实例和隔离Vite，并持续记录后端/前端/provider evidence。
- 发现现有统一门禁仍只验证即时轮换；这不能证明本轮 staged合同。当前在同一门禁中一次性
  补齐跨实例prepare/replay/conflict/complete/cancel/deadline/revoke、shared principal/quota、
  WebUI真实Playwright和真实LLM overlap/cancel/revoke矩阵。

### 2026-08-27 第一次完整门禁反馈

- 第一次 `verify-managed-api-principals.sh --with-real-llm` 在 PostgreSQL integration
  阶段准确失败，未进入昂贵的真实LLM阶段。
- 失败原因：3个既有集成测试仍把最新Flyway版本硬编码为V54，实际数据库已正确迁移到V55；
  不是staged lifecycle行为失败。
- 全仓扫描又发现4个同类 PostgreSQL断言和项目文档门禁也固定为V54。当前一次性把所有
  “latest migration”事实更新为V55；保留专门构造V54升级fixture的migration target。
- 修复后先重跑受影响的PostgreSQL矩阵和文档门禁，再从头执行完整统一门禁。

### 2026-08-27 V55 受影响 PostgreSQL 矩阵复验

- 修复所有把“当前最新迁移”误写为 V54 的既有集成断言后，执行 4 个聚焦
  PostgreSQL 集成测试类。
- 结果：54/54 PASS，Failures=0，Errors=0，Skipped=0：
  `ChatSessionPostgresIntegrationTest` 16项、
  `ChatTurnOperationPostgresIntegrationTest` 7项、
  `NextHighValueFeaturesPostgresIntegrationTest` 10项、
  `ManagedApiPrincipalPostgresIntegrationTest` 21项。
- 日志确认空库完整应用 55 个 migration 并到达 V55；专门验证 V54→V55 升级的 fixture
  仍明确停在 V54 后再升级，未被错误改写。
- 下一步进入双语长青文档、V55 inventory 与文档门禁更新；完成后从头重跑统一门禁，
  不沿用本次聚焦矩阵替代最终结论。

### 2026-08-27 V55 双语长青文档 checkpoint

- Flyway 当前版本、staged rotation API/配置/架构/能力发现/测试/发布事实已同步到
  `AGENTS.md`、project-docs skill、REST API、configuration、architecture、
  project context、business client integration、developer reference、testing guide 和
  release checklist 的中英文长青文档。
- `DEPLOYMENT.md` 已补充 PostgreSQL 多实例边界、staged rotation 操作顺序、
  V54/V55 混合 fleet 管理写冻结、全量升级后启用和回滚到 V54 前清零 retiring/PENDING
  状态的要求；不再使用过时的“当前 MVP 只承诺单实例”概括。
- `TODO.md` / `TODO-zh-CN.md` 已把 V55 有界 staged credential rotation 标记为已交付，
  并保留 OAuth/OIDC、hard limit/billing 与 legacy recovery 等独立后续边界。
- `openai-compatibility-readiness*.md` 已从 V48 基线更新为 V55 当前事实，明确即时与
  staged rotation、deadline 认证强制、共享 quota、混合版本发布和回滚约束。
- 下一步运行 `verify-project-docs.sh`、全仓 stale-fact 扫描和 `git diff --check`；
  通过后从头执行统一 managed-principal 门禁。

### 2026-08-27 第一次统一门禁与真实 LLM 反馈

- `verify-project-docs.sh`：11/11 PASS；`git diff --check`、V55 stale-fact、外部客户项目名
  与新增行密钥扫描均通过。
- 统一门禁基础阶段全部 PASS：PostgreSQL integration matrix、`mvn clean compile
  test-compile`、完整 Maven test、WebUI 229 项 Vitest、TypeScript、生产构建、alignment、
  核心 Mock Playwright、禁止悲观锁与文档门禁。
- 完整 Maven 汇总：API 542、Documents 74、Core 3193（7 skipped）、Starter 44，
  failures/errors 均为 0。
- 双实例真实 HTTP 与真实 WebUI PASS：provisioning replay/conflict、能力发现、共享 quota、
  operation capabilities、staged prepare/replay/complete/cancel/expiry/family revoke；
  数据库为 migration=55、raw credential=0、重复 current/retiring=0、残留 PENDING=0。
- 真实 LLM 阶段先确认只读 principal 的写请求返回 `403` 且 provider delta=0；首个允许的
  native Chat 请求随后返回 `504`，统一门禁按预期停止，未把后续 lifecycle 计为通过。
- 当前从 `.verification/managed-api-principals/20260828-000124/` 的脱敏响应、后端日志和
  provider 配置定位超时根因；修复或收敛验收后重跑受影响门槛。

### 2026-08-28 真实 provider 恢复 checkpoint

- OpenAI-compatible 配置实际指向 `https://api.openai-next.com` 和 `grok-4.5`；后端日志
  确认上游持续返回 HTTP `503`、`no_available_account`，有界重试耗尽后由本服务返回
  `CHAT_TIMEOUT` 504。该结果不证明 V55 失败，但也不能作为真实模型验收通过。
- 使用同一 `.env` 中的 MiniMax 配置执行脱敏直连探针：
  `MiniMax-M3` 在约 8 秒内返回 HTTP 200，响应包含非空 id、一个 choice 和 usage。
  原始响应仅保存在 gitignored 的
  `.verification/provider-probes/20260828-001447/`。
- 统一门禁正在增加 `MANAGED_API_REAL_LLM_PROVIDER` 选择器，支持 `openai`、`minimax`
  和 `anthropic`，只验证所选 provider 的变量，并在摘要中记录非敏感 provider 名称。
  下一步以 `minimax` 从头执行完整统一门禁，不能沿用失败运行的前置结果作为最终结论。

### 2026-08-28 合并前完整真实验收 checkpoint

- `scripts/verify-managed-api-principals.sh` 已支持
  `MANAGED_API_REAL_LLM_PROVIDER=openai|minimax|anthropic`，只校验所选 provider 的
  key/base URL/model，并在摘要记录 provider 名称，不记录密钥。
- 从头执行：
  `MANAGED_API_VERIFY_RUN_ID=v55-minimax-premerge-20260828-r1
  MANAGED_API_REAL_ENV_FILE=.env MANAGED_API_REAL_LLM_PROVIDER=minimax
  ./scripts/verify-managed-api-principals.sh --with-real-llm`，使用隔离端口后结果
  `13 passed, 0 failed`。证据：
  `.verification/managed-api-principals/v55-minimax-premerge-20260828-r1/summary.md`。
- 统一门禁重新通过 PostgreSQL 54/54、`mvn clean compile test-compile`、完整 Maven
  API 542 / Documents 74 / Core 3193（7 skipped）/ Starter 44、WebUI 229 项 Vitest、
  TypeScript、生产构建、alignment、Mock Playwright、禁锁、文档与 diff 门禁。
- 双实例真实 HTTP/WebUI 再次通过 provisioning、capability discovery、共享 quota、
  staged prepare/replay/complete/cancel/expiry/family revoke；数据库事实为 migration
  55、明文 credential 0、重复 current/retiring 0、PENDING rotation 0；真实 WebUI
  Playwright 1/1 通过。
- MiniMax-M3 真实 Chat 生命周期恰好产生 9 次成功 provider 调用；写拒绝、幂等 replay、
  complete 后旧 key、cancel 后 replacement 和 family revoke 后两个 key 均为 provider
  增量 0。覆盖 native JSON/SSE、OpenAI-compatible JSON/SSE、会话连续性、complete、
  cancel 恢复和 pending-family revoke。
- 另以一次性 pgvector、隔离端口 `18381`、MiniMax-M3 与 SiliconFlow BGE-M3 执行
  `real-llm-e2e-smoke.sh`，结果 `PASS=10 FAIL=0`。真实 embedding 写入 1 个 1024 维
  vector，隔离检索只返回目标文档，ask 和 SSE 均准确返回唯一校验码。
- 独立 smoke 的最终 PostgreSQL 只读事实：migration 55，永久删除后 document=0、
  embedding=0，retrieval log=3，Chat history=2，LLM usage=4 且全部 `SUCCEEDED`、
  model ref 仅 `minimax`；被删文档 GET 为 404。证据位于
  `.verification/real-rag/v55-minimax-premerge-20260828-r1/`。
- 本轮隔离端口均已释放，一次性容器已由 `--rm` 清理；没有终止或修改机器上的其他服务。
- `git fetch origin --prune` 后 `HEAD`、`main`、`origin/main` 仍同为 `1da13c03`。
  下一步仍按交付顺序显式执行 merge 检查，并把完整验证作为合并后新基线重新运行。

### 合并后统一门禁第 1 次运行反馈

- 已显式执行 `git merge --no-edit origin/main`，结果为 `Already up to date`；合并后基线仍为
  `1da13c03`。
- 从头执行 `v55-minimax-postmerge-20260828-r1`。PostgreSQL 54/54、Maven 编译与完整
  测试、WebUI 229 项 Vitest/typecheck/build/alignment、Mock Playwright、禁锁、文档和
  diff 门禁均通过；双实例 HTTP 合同及数据库事实也通过。
- 真实 WebUI 在新建 principal 后关闭 shown-once secret 弹窗，5 秒内没有在列表中观察到
  新 credential，统一门禁最终为 `12 passed, 1 failed`。后端日志证明创建事务已成功，
  Playwright `finally` 也使用返回的 credential ID 完成撤销，因此不是后端创建或清理失败。
- 页面快照仍是创建前的列表状态，根因收敛为创建成功后的 React Query 列表刷新竞态：
  当前代码只异步 invalidate，没有先把 POST 返回的非敏感 principal metadata 写入缓存。
- 修复策略：创建成功时立即以响应中的非敏感字段 upsert `api-principals` 缓存，shown-once
  raw secret 只留在 modal state；查询仅标记 stale，避免紧随其后的旧列表响应覆盖新条目。
  同时加入真实 QueryClient 回归测试，证明关闭弹窗后列表立即可操作且缓存不含 raw secret。
- 修复后先重跑前端聚焦测试、完整 Vitest/typecheck/build 与核心 Mock Playwright，再从头
  执行统一 13 项门禁；不能把本次 12/13 结果作为最终结论。

### 合并后最终独立真实 RAG 生命周期 checkpoint

- 运行 `v55-minimax-postmerge-20260827-r5` 使用一次性 `pgvector/pgvector:pg16`、
  隔离端口 `18384`、真实 MiniMax-M3 Chat 和 SiliconFlow BGE-M3 Embedding；服务从当前
  工作树启动并成功应用 V1-V55。
- `real-llm-e2e-smoke.sh` 结果为 `PASS=10 FAIL=0`：健康检查、真实 embedding
  provider、真实 Chat provider、Collection/Document 创建与关联、1024 维 embedding、
  隔离 Search、真实 ask、真实 SSE stream 均通过。ask 与 stream 都返回本次唯一校验码，
  Search 只返回隔离文档。
- 使用 root credential 和创建响应中的 `expectedDocumentRevision` 执行永久删除；
  返回 `embeddingsRemoved=1`，随后文档 GET 为 `404`。
- 最终数据库只读事实：`migration=55`、`documents=0`、`embeddings=0`、
  `retrieval_logs=3`、`chat_history=2`、`usage_rows=4`、`usage_succeeded=4`、
  `usage_failed=0`、`usage_model_refs=minimax`。
- 脱敏证据位于 `.verification/real-rag/v55-minimax-postmerge-20260827-r5/`；
  真实 key 不写入证据。应用进程和一次性 PostgreSQL 已在退出时清理，其他运行中的服务
  未被终止或修改。

### 2026-08-27 提交前门禁 checkpoint

- `./scripts/verify-project-docs.sh`：11/11 PASS，包含双语链接/结构、项目不变量、
  命令文档、Shell 语法、空白和新增行密钥扫描。
- `./scripts/verify-no-pessimistic-locks.sh`：PASS；未发现生产代码中的显式悲观锁、
  `SKIP LOCKED` 或 PostgreSQL advisory lock。
- `git diff --check`、三个本轮验收脚本的 `bash -n` 和严格新增行密钥字面量扫描均通过。
- 非归档项目文档、生产代码和验收脚本未发现外部项目名称残留，保持当前仓库文档自包含。
- 下一步：提交、推送特性分支；合并到 `main` 后按最终提交重新执行完整验收。

### 合并后 WebUI 列表一致性修复 checkpoint

- 创建成功前先取消正在运行的 `api-principals` 查询，避免创建前旧响应在 mutation 提交后
  覆盖列表；创建成功后把响应中的 principal ID、credential ID/version、policy、capability、
  scope、quota 和 expiry 等非敏感白名单字段立即 upsert 到现有 QueryClient 缓存。
- raw credential 仍只保存在 shown-once modal state；不会进入 QueryClient、URL、console、
  localStorage 或 sessionStorage。缓存同步后立即 invalidate 并重新获取服务端权威列表。
- 新增使用真实 QueryClient 的回归测试，覆盖关闭 shown-once 弹窗后新主体行和 Edit 动作立即
  可见、缓存不含 raw secret，以及权威列表重取。
- 聚焦测试：3 个文件、21 项 PASS；完整前端：32 个文件、230 项 Vitest PASS，
  `npm run typecheck`、`npm run build`、`npm run check:alignment` 全部 PASS。
- 核心 Mock Playwright `api-key-mvp.spec.ts`：1/1 PASS；浏览器证据继续覆盖 staged rotation、
  shown-once secret 清除、丢响应幂等 replay、complete/cancel/immediate 兼容和存储安全。
- 下一步从头执行新的合并后统一 13 项门禁，包含真实 WebUI 和真实 MiniMax 生命周期；任何
  旧运行结果均不替代该最终结论。

## 7. 已知风险与下一步

- V54 binary 不认识双 enabled credential；部署和回滚必须遵守 plan 的管理写冻结条件。
- staged prepare的 raw secret仍只展示一次；响应丢失通过查询 pending状态和 cancel恢复，
  不能重放 secret。
- 下一步一次性编写并运行后端 lifecycle、并发、HTTP/no-store 与能力发现测试。
