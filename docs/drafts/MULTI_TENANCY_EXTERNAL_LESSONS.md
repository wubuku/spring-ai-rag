# 多租户外部参考经验教训（补充考古）

> **状态**：只读考古记录，中文单语；2026-11-13 落档。代码未实施，本文不改动
> [多租户现状调研](MULTI_TENANCY_STATUS.md) 与
> [多租户平台技术路线与可插拔方案](MULTI_TENANCY_TECH_ROADMAP.md) 的方向裁决。
>
> **写给谁**：准备实施或复审本仓多租户方案的 Agent。先读本文「第 1 节速查」和
> 「第 6 节影响清单」，再按相对路径下钻原始材料；不要重新做全盘规模探索。
>
> **配套文档**：[现状调研](MULTI_TENANCY_STATUS.md) · [技术路线](MULTI_TENANCY_TECH_ROADMAP.md)
>
> **一句话结论**：2026-11-02 / 11-04 的两份预研只考古了 BlueCortex EE/CE，**没有提到、也没有考察**
> `AI-Native-Business-Operating-System` 与 `seedance-research`；后者包含一个可运行的
> 多租户 PoC/验证项目，是本仓多租户方案最直接、最被遗漏的证据来源。

---

## 1. 速查：想知道什么，直接读哪里

| 想确认的问题 | 直接读 | 结论 |
|---|---|---|
| 之前有没有考察过这两个项目 | 第 3 节 | 没有。全仓检索三个关键词零命中 |
| 方案方向要不要推翻 | 第 3.3 节 | 不推翻；EE/CE 结论仍成立，但证据链不完整 |
| seedance PoC 到底验证了什么 | 第 4.2 节 | 7 个案例、红测—绿测、真实 PG 16、81 个测试 |
| 有哪些生产踩坑 | 第 4.3 节 | 事务内切租户、after-commit 脏连接、GUC 残留、注册路径 500 |
| AI-Native-BOS 有什么独特经验 | 第 5 节 | 术语分层、承重列、真实迁移演练、函数静默失效 |
| 具体要往本仓路线补哪些条目 | 第 6 节 | 已覆盖 / 需补强 / 新增盲点三张清单 |
| 怎么复核而不用重做探索 | 第 7 节 | 复现命令 + 事实快照 |
| 之前漏考察了哪些种子材料 | 第 4.5–4.8 节 | seedance drafts 目录 11 份文档、PoC `good/` 实现骨架与 SQL 模板 |
| 前人「走过弯路后否决/删除」的教训 | 第 4.6 节 | DISCRIMINATOR 否决、Filter/Interceptor/Interceptor 双机制收敛、connection-provider 伪 opt-out 删除、超级租户骨架不启用 |
| 前人实现细节可复用资产 | 第 4.7–4.8 节 | PoC 三角色 SQL 模板、good/ 推荐实现骨架、JdbcTemplate bootstrap 三段式、显式 setTenantId 双保险 |
| 本仓数字口径修订 | 第 7.3 节 | 「约 56 处唯一约束」实为 grep 行数；真实口径 23 条 CREATE UNIQUE INDEX + 11 条列级 UNIQUE |
| 三件套 vs transaction-local 的取舍依据 | 第 4.9 节 | 本仓 20 个含 jdbcTemplate.update 且无 @Transactional 的文件，决定主防线取向 |

---

## 2. 路径基准约定（重要）

本文对参考项目材料的引用**全部使用相对路径文本**，不写任何个人主目录绝对路径。
约定与 [docs/index-zh-CN.md](../index-zh-CN.md) §5 的 `../spring-ai-skills-demo` 一致，**以本仓库根
`spring-ai-rag/` 为起点**，`../` 表示团队统一工作区根。假设工作区根下同级摆放：

```text
<工作区根>/
  spring-ai-rag/                          # 本仓库
  AI-Native-Business-Operating-System/    # 参考项目一
  seedance-research/                      # 参考项目二
  Blueforce-Tech-Inc/
    BlueCortexEE/                         # 原预研已考察
    BlueCortexCE/                         # 原预研已考察
```

- 本文所有外部路径**按团队统一目录布局书写**（相对仓库根、以 `../` 指向工作区根内同级仓库）。
  当前本机实际落点与此约定存在两处偏差，按下列换算表解析；换算表随布局变更更新。

| 团队布局路径（本文正文用法） | 本机实际位置（相对本仓库根） |
|---|---|
| `../AI-Native-Business-Operating-System/…` | `../AI-Native-Business-Operating-System/…`（当前已同级，可直接解析） |
| `../seedance-research/…` | `../../../.hermes/workspace/seedance-research/…` |
| `../Blueforce-Tech-Inc/BlueCortexEE/` | `../../Blueforce-Tech-Inc/BlueCortexEE/` |
| `../Blueforce-Tech-Inc/BlueCortexCE/` | `../../Blueforce-Tech-Inc/BlueCortexCE/` |

- 相对 `docs/drafts/` 目录直接解析时，在仓库根相对路径前补 `../`：
  例如 `../seedance-research/…` → `../../../seedance-research/…`；本机 seedance 对应
  `../../../../../.hermes/workspace/seedance-research/…`。
- 若后续把仓库迁移进团队统一布局，只需删除上表"本机实际位置"列的偏差行，正文无需改动。
- **所有外部路径都是行内代码文本，不是仓内 Markdown 链接**。这是刻意的：文档门禁
  `scripts/verify-project-docs.sh` 会把跨仓 Markdown 链接判为「链接逃出仓库」。
- 行号均为 2026-11-13 调研时快照；优先按稳定文件名与章节标题定位，行号只作复核辅助。

---

## 3. 结论一：原预研的证据边界

### 3.1 检索证据

在本仓库执行（排除 `node_modules` / `target` / `dist` / `*.lock`）：

```bash
rg -n -S "AI-Native-Business-Operating-System|seedance-research|multitenancy-verification" .
```

结果：**零命中**。两个参考项目名与 PoC 目录名从未出现在任何文档、代码或提交信息中。

### 3.2 原预研实际考察对象

`MULTI_TENANCY_TECH_ROADMAP.md:10` 明确写「外部考古对象」只有
`../Blueforce-Tech-Inc/BlueCortexEE/` 与 `../Blueforce-Tech-Inc/BlueCortexCE/`。
第 4 章「EE / CE 考古事实」、第 3 章路线代价对比、第 9 章六条决策均基于这两个仓库。

### 3.3 对方向裁决的影响

**不需要推翻 C 方案**（单仓单 schema、表永远租户化、单租户为固定 `default` 的 N=1、
多租户强制 RLS）。EE/CE 考古关于「双仓漂移」「双 schema 插件=仓内 fork」「RLS + GUC + 双连接池」
的结论依然有效，且被 seedance PoC 独立印证。

需要修正的是**证据链完整性**：原路线把「风险与验收」主要落在数据层、启动校验和任务上下文，
缺少应用授权分层、回调权威、结果 owner、SSE 逐次授权、事务/连接租借时序、真实迁移函数依赖等
一整类已被外部项目用实验或生产事故证明的经验。

---

## 4. seedance-research 考古（最高优先级）

### 4.1 材料清单

| 相对路径（相对仓库根） | 作用 |
|---|---|
| `../seedance-research/multitenancy-verification/` | 独立 Maven PoC 项目，Java 21 + Maven + Docker/Testcontainers，私有 `postgres:16` |
| `../seedance-research/multitenancy-verification/README.md` | 7 个案例总表、固定夹具、运行命令、反模式说明 |
| `../seedance-research/multitenancy-verification/docs/experiment-conclusions.md` | 红测—绿测证据、实验矩阵、逐项结论、结论使用规则 |
| `../seedance-research/multitenancy-verification/docs/scenemill-traceability.md` | 7 案例与生产审查入口的映射、证据标签定义 |
| `../seedance-research/multitenancy-verification/docs/case-01-tenant-boundary.md` … `case-07-sse-authorization.md` | 每案例自包含：故事、故意反模式、推荐合同、负向证据、测试命令 |
| `../seedance-research/multitenancy-verification/src/test/java/` | 反模式 fixture（`bad/`）、推荐实现场景测试、ArchUnit 边界测试 |
| `../seedance-research/docs/multitenancy/MULTITENANCY-CHECKLIST.md` | 生产级开发 checklist，含多条真实事故与踩坑记录 |
| `../seedance-research/docs/multitenancy/RLS-NOBYPASSRLS-GUC.md` | RLS / NOBYPASSRLS / GUC 三件套机制、4 真不变量、已知陷阱 |
| `../seedance-research/docs/multitenancy/drafts/多租户最佳实践验证项目-实施规划.md` | PoC 的立项与实施规划 |
| `../seedance-research/docs/multitenancy/drafts/设计-最佳实践-v5.2.md` | 设计基线 |
| `../seedance-research/docs/drafts/principal-architect-review-2026-10-07/MULTITENANCY-REVIEW-2026-10-18.md` | 多租户机制层独立审查报告 |
| `../seedance-research/docs/drafts/principal-architect-review-2026-10-07/PRINCIPAL-ARCHITECT-REVIEW.md` | 主审查报告（案例映射的 Δ / AD / G / DD 编号来源） |
| `../seedance-research/api-server/src/main/java/org/dddml/scenemill/config/RlsTenantTableCatalog.java` | 租户表权威 catalog，新表未同步则启动失败 |
| `../seedance-research/api-server/src/main/java/org/dddml/scenemill/config/StartupRlsVerifier.java` | 启动门禁实现 |
| `../seedance-research/api-server/src/main/java/org/dddml/scenemill/tenant/TenantAwareConnectionProvider.java` | GUC 注入实现 |
| `../seedance-research/api-server/src/main/java/org/dddml/scenemill/service/PlatformOpsService.java` | 平台跨租户能力的唯一入口 |

### 4.2 PoC 已经证明的机制结论

运行方式（`README.md` §Run）：`cd multitenancy-verification && mvn validate && mvn test && mvn verify`。
不读 `.env`、不连共享开发库、不调真实 Provider、不用 H2/MinIO。
`experiment-conclusions.md:17` 记录 2026-10-18 全量绿测：`mvn -f multitenancy-verification/pom.xml clean verify`，
Surefire **81 tests / 0 failures / 0 errors / 0 skipped**，`ArchitectureBoundaryTest` 的 3 条 ArchUnit 规则实际执行。

关键方法论：**先写反模式红测让错误可观察，再用推荐实现绿测**。临时红测不留在默认构建里，
失败结果记录在 `experiment-conclusions.md:14`，分别暴露「cache 命中即授权」「只靠物理连接初始化 SQL
清理 GUC」「撤权后仍按旧订阅推送」「未认证 task-existence oracle」「宽松 policy 逃过启动门禁」。

七个案例（`README.md` §Cases，各案例文档末尾有 `[CURRENT FACT — 实验结论]`）：

| 案例 | 独立证明的合同 | 关键测试 |
|---|---|---|
| 01 租户行边界 | 业务角色必须 `NOBYPASSRLS`；`FORCE RLS`；会话级 GUC 在连接池复用会残留，事务局部绑定才是主防线；verifier 关闭即拒启动 | `TenantBoundaryScenarioTest`、`RlsStartupVerifierScenarioTest`、`StartupVerificationGateScenarioTest` |
| 02 授权分层 | 认证、外部身份绑定、租户成员资格、项目 ACL、RLS 行边界、端点 guard 是**六层独立检查**，不能互相替代 | `ProjectAuthorizationScenarioTest`、`ExternalIdentityBindingScenarioTest`、`EndpointAuthorizationCoverageScenarioTest` |
| 03 异步上下文 | worker / 嵌套 scope / CallerRuns 同线程 / 异常清理 / 缺失上下文必须显式 bootstrap—断言—恢复—清理；**合法但错误绑定的租户会让 RLS 正常返回该租户行，RLS 自身发现不了工作项串台** | `AsyncContextScenarioTest` |
| 04 平台能力 | `BYPASSRLS` 只能进登记的 capability adapter；跨租户 discovery 只返回最小身份投影；operator 动作需审计；普通业务包不得依赖平台实现 | `PlatformCapabilityScenarioTest`、`ArchitectureBoundaryTest` |
| 05 回调权威 | 按 operation 区分 signed/unsigned；receipt、client-token intent、replay nonce、bootstrap owner、response-lost、manual resolution 与 audit principal 连续性分别验证；不能信任请求体 | `CallbackAuthorityScenarioTest`、`DurableCallbackEvidenceScenarioTest`、`ProviderReferenceCorrelationScenarioTest`、`ManualResolutionAuditScenarioTest` |
| 06 结果 owner | volatile registry 的 cache hit 不查 owner、cache miss 会把任意 task ID 变成 Provider 查询；必须持久化 owner/tenant/project + ACL；消费者是否存在不改变安全合同 | `VolatileResultExperimentTest`、`ResultOwnerScenarioTest`、`MpsConsumerIndependenceScenarioTest` |
| 07 SSE 授权 | 旧订阅在 ACL 撤销后仍收事件是可复现反模式；每次投递重查当前权限并校验事件 tenant/project 与订阅一致；多副本逐个验证 | `SseAuthorizationAntiPatternExperimentTest`、`SseAuthorizationScenarioTest`、`SseMultiReplicaScenarioTest` |

证据标签规则（`scenemill-traceability.md`）：`CURRENT FACT`（demo 可重复实验证明）、
`CORROBORATED FACT`（与生产源码互证）、`RISK INFERENCE`（推导，非事故）、
`UNKNOWN`（尚未实验或尚未取生产事实）、`TARGET INVARIANT`（未来必须满足，不代表现状已满足）。
**不要把 PoC 绿测倒写成生产已接线**；生产迁移要跑同一实验矩阵。

### 4.3 生产 checklist 里的血泪教训

`MULTITENANCY-CHECKLIST.md` 与 `RLS-NOBYPASSRLS-GUC.md` 记录的是实际生产实现踩坑，
比 PoC 更贴近本仓的 Spring / JPA / JdbcTemplate 混合栈：

- **事务内切租户是三层不一致大坑**（checklist §B，`@Transactional` 条目）：Hibernate Session
  创建时已捕获 `tenantIdentifier` 并绑定连接 GUC，方法体内再 `TenantContext.setTenantId()`
  不会更新 Session / GUC / RLS，后果是幽灵空结果、`@TenantId` 写入仍是旧租户（数据污染）、
  关联加载报错。跨租户遍历必须「外层无事务 → 循环 set → 内部 `@Transactional` 新 Session → finally 清理」。
- **注册路径真 bug**（checklist §A，v0.7.1 教训）：`c9f024a` 只在 user save 前设
  `AuditUserContext`，漏了 tenant save，生产 500；`7e3acee` 改成预分配 userId 且第一个实体
  save 之前就设置。说明「第一个写库动作之前的上下文」必须显式清点。
- **after-commit 脏连接**（checklist §G，2026-06-30 事故）：`BillingOutboxListener` 的 FREEZE 事件
  `event not found` 导致 `frozen_credits` 变负；修法是 `@Async` 化让监听器用新连接新 GUC，
  或 `REQUIRES_NEW` + `JdbcTemplate`，不能用 JPA `findById`（GUC 已清空被 RLS 挡掉）。
- **GUC 三件套缺一不可**（`RLS-NOBYPASSRLS-GUC.md` §7 / §9 / §11）：`set_config` 缺了读 0 行；
  checkout reset 缺了连接复用残留上家 GUC 直接跨租户泄露；`connection-init-sql` 只是第二层卫生措施，
  不能证明 checkout 清理。三角色为 app（`NOBYPASSRLS`）/ platform（`BYPASSRLS`，需白名单 + 审计）/
  migrator（superuser）。
- **重复手写谓词会漏行**（checklist §A）：JPQL 不要再带 `WHERE u.tenantId = :tid`，
  RLS 已过滤，应用层与 GUC 不一致时反而漏行；裸 `findById` 可能命中一级缓存假绿，
  跨租户读走 `BaseRepository` 的 `findByIdActive`。
- **新表必须同步 catalog**（checklist §A）：迁移加列加索引、加 `ENABLE+FORCE RLS` + 规范 policy、
  同步 `RlsTenantTableCatalog.TENANT_TABLES`，三者缺一启动 verifier 直接失败。
- **verifier 要验语义不只验数量**（`experiment-conclusions.md:14` 红测）：只数 policy 数量的
  verifier 会接受 `USING (true) WITH CHECK (true)`，必须校验 policy 表达式与 GUC 绑定。
- **superuser / `BYPASSRLS` 是头号陷阱**（`RLS-NOBYPASSRLS-GUC.md` §12）：业务代码绝不能用
  postgres 连接；`SUPER_TENANT_ID = "*"` 之类 stub 实际行为不是「看全平台」而是查不到。

### 4.4 对本仓的七条增量经验

1. 认证 / 租户 / 成员资格 / 资源 ACL 四层分离，不能只用「API Key 绑定租户」一笔带过。
2. RLS 只保证「绑定租户合法」，不保证「工作项属于该租户」；任务载荷必须自带 tenant/actor/audit
   并在执行前做一致性断言。
3. 平台池窄化为登记 capability：最小 discovery 投影、`PlatformReadQuery` 命名枚举、白名单、审计、
   依赖方向静态检查。
4. 外部 Provider 回调按 operation 建立权威：receipt、幂等 intent、replay、owner、bootstrap、
   manual audit principal。
5. 长任务/导入/评估结果必须有持久化 owner，volatile registry 与任意 task ID 查询是反模式。
6. SSE / MCP 每次投递按当前 ACL 与 tenant/project 重新授权，而不是只在建连时或只按 tenant 过滤。
7. 测试矩阵要含「反模式红测 + 推荐绿测 + ArchUnit 边界 + 端点 guard 覆盖 + 多副本」，
   不能只有「A 读不到 B」一条。

---

### 4.5 2026-11-13 复核补遗（原考古遗漏的材料，本轮补齐）

本节是 2026-11-13 对第 4.1 节清单的**增量复核**：原考古只下钻了 PoC 的 `docs/`、
`MULTITENANCY-CHECKLIST.md`、`RLS-NOBYPASSRLS-GUC.md`、两份审查报告和 2 份 drafts；
实际 `../seedance-research/docs/multitenancy/drafts/` 有 **11 份文档**、
PoC `src/main/java/.../good/` 有整套推荐实现骨架、`src/main/resources/db/init/` 有可直接复用的
三角色 SQL 模板。原考古漏掉了这些「前人走过弯路后否决 / 删除 / 收口」的一手教训。

### 4.6 seedance drafts 目录其余 9 份文档的教训

| 文档（相对仓库根，团队统一布局） | 一手教训 |
|---|---|
| `../seedance-research/docs/multitenancy/drafts/基于DISCRIMINATOR-实现规划.md` | Hibernate 原生 `DISCRIMINATOR` 策略从未完整实现（HHH-6054 / HHH-7249 长期未解决）；业界通行做法是 Filter + Interceptor 组合替代，而 seedance 后续又进一步退役了 Filter/Interceptor，改为 RLS + GUC 数据库兜底。本仓技术路线 C「表永远租户化 + RLS」与该演进终点一致，且本仓无 Filter/Interceptor 双机制包袱，可直接走终态。 |
| `../seedance-research/docs/multitenancy/drafts/删除TenantInterceptor-实施方案.md` | 应用层双写机制最终**全部退役**，写期填列收敛到 `@TenantId` 单一机制；收口原则是「同一写期机制只留一个，冗余兜底物理删除」。本仓没有 JPA `TenantEntity` 体系，统一靠 `TenantContext` + RLS 兜底，原则同构。 |
| `../seedance-research/docs/multitenancy/drafts/删除scenemill.multitenancy.connection-provider.enabled-实施记录.md` | 曾有 `connection-provider.enabled` 作为「伪 opt-out」：schema、角色、GUC、实体继承、verifier 五层已硬绑定多租户，一个配置开关并不能真正关闭，只会让人误以为可关。教训：**不要引入无法真正关闭多租户的配置开关**；本仓 `rag.tenant.mode=SINGLE` 必须是有真实语义的模式（关 RLS + 有守卫），不是伪 opt-out。 |
| `../seedance-research/docs/multitenancy/drafts/超级租户-运维预留设计.md` | 超级租户 `super-tenant.enabled=false` **骨架先行、默认关闭**；激活必须叠加受信网络 / 专用凭据 / 专用入口 / 权限模型 / 审计至少两层控制；`X-Tenant-Id: *` 类 header 激活被明确否决。本仓阶段三的平台 capability 白名单 + 审计即此设计的同构落地。 |
| `../seedance-research/docs/multitenancy/drafts/租户级Credits-分配与回收-实施规划.md` | 租户管理员分配 / 收回成员配额的完整需求拆解：9 个原始子需求 + 12 条隐含需求；关键裁决是**复用余额字段但按角色重新解读语义**（root = 未分配池，member = 个人配额）+ 新增分配审计表。本仓 WP10 的租户级配额聚合可借鉴其语义分层与审计表设计。 |
| `../seedance-research/docs/multitenancy/drafts/用户自助注册B4三段式单事务化-实施方案.md` | 「幽灵租户」问题：注册先提交 tenant 行再写 user，user 写失败即产生无主租户，需人工清理。修法：`JdbcTemplate` bootstrap + `SECURITY DEFINER` 查前置数据 → `setTenantId` → 才第一次 JPA 访问 → 单事务原子。本仓 WP9 租户创建需保证**租户行 + 初始 principal 原子**。 |
| `../seedance-research/docs/multitenancy/drafts/邀请码用户注册B5三段式单事务化-实施方案.md` | 5 层失配模型（GUC、`@TenantId`、Session 缓存、ThreadLocal、RLS）与 Hibernate 6.2.7 `Session.tenantIdentifier` 在 Session 构造时缓存锁死的硬约束；**显式 `setTenantId` + 字段生成器双保险**；邀请码并发读改写 race 需 CAS。本仓用 `JdbcTemplate` 不走 Hibernate Session，多数失配不适用，但「显式设置 + 生成器双保险」与「并发配额回收用 CAS」两条通则直接适用。 |
| `../seedance-research/docs/multitenancy/drafts/实施记录.md` | P0–P6 + L3 完整演进：角色拆分（app / platform，以及 NOLOGIN BYPASSRLS 的第三角色）→ GUC → DEFINER 函数 → 平台池 → 删除 Filter/Interceptor → 启动 verifier → 合 `main` 时 978 测试全绿。**分期交付纪律**：短命子分支 → PR 合 feature → L3 验收 → 一次合 main，每期都有可复现验收。 |
| `../seedance-research/docs/multitenancy/drafts/部署指南.md` | 三库对照（dev / test / staging）、幂等角色脚本、生产与 staging 角色不走本地默认密码脚本、V183 清理 `sse_tickets` 表的幂等可重跑设计；**生产 provisioning 与本地开发脚本分离**。本仓 WP11 升级演练脚本同样要分开「本地演示」与「生产 provisioning」。 |

### 4.7 PoC 可复用资产（原考古未提取）

原考古只提了 `src/test/java/` 的反模式与场景测试；实际还有三类**可直接对照搬运**的资产：

1. **角色与表 owner 模板**：`../seedance-research/multitenancy-verification/src/main/resources/db/init/002_roles.sql`
   给出 `demo_owner`（NOLOGIN、NOSUPERUSER、NOBYPASSRLS，专做表 owner）、`demo_app`（NOBYPASSRLS）、
   `demo_platform`（BYPASSRLS，**用列级 GRANT 收窄到最小列集**）、`demo_unrestricted_platform`
   （BYPASSRLS + 全表 GRANT，专作 verifier 负向对照）、`demo_super_nobypass`
   （**SUPERUSER + NOBYPASSRLS**，专作「superuser 仍绕过 RLS」的负向对照）、`demo_migrator`
   （BYPASSRLS 但 NOSUPERUSER，专跑迁移）**六个角色**。列级 GRANT 收窄、owner 与 app 角色分离、
   独立负向对照角色，以及「superuser 属性即便带 NOBYPASSRLS 也不可当业务角色」都是原考古没提到的细节。
2. **RLS 与复合外键模板**：`../seedance-research/multitenancy-verification/src/main/resources/db/init/001_schema.sql`
   是 12 张租户表的完整模板，每张
   `ENABLE + FORCE ROW LEVEL SECURITY` 加同式 policy；`callback_receipt` 用
   `(job_id, tenant_id, project_id, actor_id)` 四列复合外键指向 `job`，是「结果 / 回调行自带 owner 四元组」
   的可执行写法。本仓 WP2 复合外键、WP8 结果 owner 表可直接对照。
3. **`good/` 推荐实现骨架**：`../seedance-research/multitenancy-verification/src/main/java/org/dddml/multitenancy/verification/good/`
   下 `audit/`、`authorization/`（含 `EndpointAuthorizationCatalog`）、`bootstrap/`
   （含 `CallbackTenantBootstrapper` / `DurableCallbackTenantBootstrapper`）、`callback/`
   （receipt / registry / verifier / processor / owner-discovery-port）、`context/`、`identity/`、`platform/`
   （discovery / operator port）、`result/`、`sse/`、`tenant/`、`web/` **共 11 组**，均有 Java 接口与配套场景测试。
   本仓 WP5–WP8 的 `TenantResolver`、`TenantMembershipGuard`、`TenantJobDispatcher`、
   回调 receipt / intent / replay / owner / audit 契约、SSE 逐次授权，在 PoC 里已有**同构参考实现**，
   实施时按本仓命名重写，不重复发明。

### 4.8 搬运纪律（补遗过程中得到的直接教训）

本节写作时，第一次插入曾因手抄路径与术语出现错字和占位符，已整体重写清理。这与
BOS「函数体以文本存储、`RENAME COLUMN` 后真断裂」是同一类问题：**文本层搬运不可靠**。
把参考项目的 SQL / 接口 / 模板落到本仓时，必须整体复制后用工具校验（diff / lint / 编译），
不要靠手抄改写；文档里引用外部材料也优先按稳定文件名与目录定位，不手抄行号。
本补遗在 2026-11-13 的第二次只读核查中又发现「5 角色 / 9 组」两处手抄计数误差与
`.../` 省略前缀，已按实际目录与 `002_roles.sql` 全文修正；这正是本节纪律的第二次实例证据。

### 4.9 本仓数字口径修订与 GUC 主防线取舍证据

- 第 7.3 节原写「约 56 唯一约束」实为 `grep -i UNIQUE` 的**行数**（含注释、SQL 字符串、
  多行语句）。真实口径：`CREATE UNIQUE INDEX` 23 条、列级 inline `UNIQUE` 11 条、
  `CREATE UNIQUE` / `ADD CONSTRAINT ... UNIQUE` 语句合计 24 条。规划 §3 若引用该数字，
  必须标注「行数口径」或改用「23 条唯一索引 + 11 条列级唯一」的口径，避免对抗式审核误判。
- 2026-11-13 对抗式审核（Herdr 会话 `d3c09735-fc3e-4d0f-ae20-f1fb3d50f6b4`）独立复核：
  本仓有 **20 个文件含 `jdbcTemplate.update/batchUpdate` 且全文无 `@Transactional`**。
  这正是 seedance / EE 否决 `SET LOCAL`（transaction-local GUC）作为唯一主防线的同一类现实：
  自动提交连接上 `set_config(..., true)` 在语句结束即失效，下一条语句 RLS 读 0 行。
  因此本仓 GUC 主防线应取 **session 级 `set_config(..., false)` + checkout/归还 `RESET`
  + `connection-init-sql` 三件套**，transaction-local 仅作事务内加固；或显式把
  「非事务租户作用域 DB 访问清零」列为工作包与门禁。该结论已作为规划 FAIL 项进入修订清单（第 6.5 节）。

## 5. AI-Native-Business-Operating-System 考古

### 5.1 材料清单

| 相对路径（相对仓库根） | 作用 |
|---|---|
| `../AI-Native-Business-Operating-System/docs/context-boundaries.md` | Tenant / Workspace / 应用版本 / 场景对象的分层与 Context Bar 契约 |
| `../AI-Native-Business-Operating-System/docs/drafts/BOS-Tenant与Organization术语分层及重命名决策备忘-2026-11-10.md` | 决策备忘；六轮独立复核 + 真实 PG 16.8 实测修订记录 |
| `../AI-Native-Business-Operating-System/docs/drafts/BOS-Tenant重命名详细实施规划-2026-11-10.md` | 配套实施规划（DB-1..DB-18 验收门） |
| `../AI-Native-Business-Operating-System/docs/drafts/verification/2026-11-12-tenant-rename-fullstack-v126/README.md` | V126 全栈交付验收记录，逐门裁决与实测数字 |
| `../AI-Native-Business-Operating-System/backend/src/main/resources/db/migration/bos/V126__tenant_rename.sql` | 实际落地的改名迁移，含内建终态断言 |
| `../AI-Native-Business-Operating-System/backend/src/test/java/` | RLS 负向矩阵、`RuntimeDatabaseRoleVerifierAcceptanceTest` 等 |
| `../AI-Native-Business-Operating-System/verification/` | 大量按批次归档的升级演练与门禁证据 |

### 5.2 术语分层（`context-boundaries.md` §Phase qualification / §Three Layers / §Context Bar Contract）

- Tenant 来自**认证身份**，Workspace 是成员授权后才能选择的业务上下文。
- BOS-native / Business application / Workspace-scenario object 三层；Product、Environment
  只是某个不可变 `ApplicationVersion.contextLevels` 声明的场景层级，绝不是全局层级。
- 只有 Tenant 与 Workspace 是全局 shell 层级；切换 Workspace 或应用要清掉不属于新版本的 lens。
- 结论：租户是第一等安全边界，Collection 一类概念是租户内知识库，**不能升格成租户替代物**；
  这与本仓路线第 9.2 节一致，但多了一层「应用版本声明的上下文层级」可借鉴到 WebUI 租户选择器设计。

### 5.3 承重列与真实迁移演练（决策备忘头部 + §13）

- 曾评估「丢弃 `organization_id`、只靠 `workspace_id`」的方案 B，被否决。实测该列是**承重列**：
  46 个 migration 中出现 896 次、45 处外键声明、18 处指向隔离根表的外键、27 个具名 DB 对象、
  42 处 `bos.organization_id` GUC 读写并驱动 RLS 判定链。丢弃等于放弃多租户能力。
- 六轮独立复核逐轮推翻前一轮结论：双列表 11 → 14；函数体断裂数 38 → 41 → **43**；
  并发现只改名不重建 `agent_profile` policy 会**静默少一条隔离策略**（policy 总数 96 而非 97）。
- 真实 PG 16.8 实测：**RLS policy、CHECK、FK/UNIQUE 列清单、索引、`update of` 触发器列清单会
  自动随 `RENAME COLUMN` 改写；函数体以文本存储，是真断裂项**。断裂原因有三类：列名、
  隔离根表名/表别名、GUC 名；单靠 `prosrc ~ '\yorganization_id\y'` 正则会漏掉后两类。
- 三个函数在 GUC 为空时**静默返回 NULL、不抛异常**；只验「函数存在/能编译」会漏掉静默隔离失效，
  必须在 GUC 有值条件下逐个调用（DB-17）。
- 顺序不可颠倒：遗留列让路 → 归属列改名 → 重建函数体 → 重建个别 policy → 终态断言内建到迁移里。

### 5.4 V126 验收记录里的新发现

- 2026-11-12 合并 `main`（merge `28359f69`），DB + Java + 前端 + 文档作为一个可运行整体交付；
  全新库与真实 V124 存量库升级两条路径都跑通，终态逐项一致。
- 基线漂移：规划写死「policy 97 / 引用 33」，实际 V124 基线已是 **102 / 34**（V117–V124 新增 5 条），
  守恒结论仍成立（drop 17 / create 17 / 净 0）。**写死数字会过时，门禁应比对动态基线**。
- `tenant_id` 列数有 32（superuser 视角）与 29（运行时 `bos_app` 视角）两种合法口径，引用必须标口径。
- 复核反向验证发现 `bos_current_tenant_key()` 的 legacy 回退仍存活，6 条 policy 在 canonical 缺失时
  仍会读 legacy key；属 pre-existing，登记为待决策而非实施轮擅改。
- 交付中还修了函数体漏改导致运行时崩溃、`drop function` + `create or replace` 丢失原有 ACL
  使 `bos_app` 获得多余 EXECUTE 两个真实缺陷。

### 5.5 对本仓的六条增量经验

1. 租户隔离根列必须一次性定死并全链路承重；不要中途引入第二列或让业务列兼任。
2. 迁移验收必须在**真实 PG + 完整迁移链**上做，且同时覆盖全新库与存量库升级。
3. 门禁不能只数 policy 数量或只做静态列名扫描；要验表达式、函数体六类匹配面、
   GUC 有值条件下的逐个调用、policy 总数守恒。
4. 涉及 DB 函数 / 触发器 / RLS helper 时，把「函数逐个可调用」列为独立验收门。
5. 迁移脚本自带终态断言（如双列表冲突检测），而不是靠人工检查或外部脚本补跑。
6. 长青文档里的计数要标口径与基线版本，避免升级后变成「过时数字即缺陷」的误判。

---

## 6. 对 spring-ai-rag 路线的影响清单

### 6.1 已被原路线覆盖，无需改写

RLS `ENABLE + FORCE`、`NOBYPASSRLS` 应用角色、无 GUC fail-closed、migrator/app/platform 三角色、
启动 verifier、平台池、`TenantResolver` / `TenantJobDispatcher`、SSE/MCP 需租户上下文、
双租户真实 PG 集成测试、单租户默认关 RLS 但 CI 跑双租户、HNSW + RLS 基准、禁止生产 header trust、
`collection_key` 改 `(tenant_id, collection_key)`、全局表逐张裁决。

### 6.2 原路线提了一句，需要补强为硬合同

| 原路线位置 | 现状 | 补强为 |
|---|---|---|
| 风险 2「后台任务丢上下文」 | 只说统一 `TenantJobDispatcher` / `TenantContext.wrap` | 任务载荷自带 tenant/actor/audit，执行前一致性断言；覆盖 executor / scheduler / listener / callback / after-commit / CallerRuns；外层事务边界约束 |
| 阶段二「SSE / MCP 广播带 tenant」 | 只说按 tenant 过滤、无上下文 fail-closed | 每次投递重查当前 ACL + 校验事件 tenant/collection 与订阅一致；多副本逐个验证；撤权后立即停止推送 |
| 9.3 / 风险 1「平台池」 | 只说独立凭据、允许枚举租户 | 登记 capability、最小 discovery 投影、命名查询枚举、白名单 + 审计、ArchUnit 禁止业务包依赖平台实现 |
| 验收清单「启动 verifier 负向测试」 | 只列 superuser / BYPASSRLS / 漏 policy / 漏 FORCE | 增加：宽松 policy 表达式被拒、verifier 被关闭即拒启动、新表未入 catalog 即拒启动、函数逐个可调用（GUC 有值） |
| 阶段一「迁移分批、可回滚」 | 只说升级演练 | 增加真实 PG 完整迁移链 + 全新库/存量库双路径 + policy/函数依赖终态守恒 + 迁移内建断言 |

### 6.3 原路线基本缺失，建议新增

| 新增条目 | 来源 | 建议落点 |
|---|---|---|
| 认证 / 租户 / 成员资格 / Collection ACL 四层独立检查；端点 guard 覆盖测试 | seedance 案例 02 | 阶段二 + 验收清单 |
| 外部 Provider 回调按 operation 的 receipt / intent / replay / owner / audit 权威 | seedance 案例 05 | 阶段二（外部模型与长任务回调面） |
| 持久化结果 owner；禁止 volatile registry 与任意 task ID 触发外部查询 | seedance 案例 06 | 阶段二/三（长任务、导入、评估、导出结果面） |
| Hibernate / 事务 / 连接租借时序：先设上下文再开事务；禁止事务内切租户；after-commit 用新连接 | seedance checklist §B / §G | 阶段二实施约束 + 专项测试 |
| 反模式红测 + 推荐绿测 + ArchUnit 边界 + 端点覆盖的测试形态 | seedance PoC 方法论 | 阶段四 + `testing-guide*` |
| DB 函数 / 触发器 / policy 依赖的真实迁移演练与逐个调用验收 | BOS §13 / V126 DB-16..18 | 阶段一 + 验收清单 |
| 文档与门禁计数标口径、比对动态基线，不写死数字 | BOS V126 §2.1.x | 验收清单 + 文档治理 |
| Tenant / Workspace / 应用版本声明层级的术语分层 | BOS `context-boundaries.md` | 阶段三 WebUI 与管理面设计 |

| 不要引入无法真正关闭多租户的伪 opt-out 配置；`SINGLE` 必须是有语义的模式 | seedance 删除 connection-provider.enabled | §5.6 兼容模式定义 + WP3 守卫 |
| 超级租户骨架先行、默认关闭，激活叠加至少两层控制；否决 `*:` 类 header 提权 | seedance 超级租户运维预留 | §9 遗留项 + 平台 capability 设计 |
| 租户创建保证「租户行 + 初始 principal」单事务原子，避免幽灵租户 | seedance B4 三段式 | WP9 生命周期 |
| 配额聚合复用「root 池 / 成员配额」语义分层 + 分配审计表；并发回收用 CAS | seedance 租户级 Credits / B5 race | WP10 配额 |
| 生产 provisioning 与本地开发脚本分离；升级清理脚本幂等可重跑 | seedance 部署指南 | WP11 升级演练 |
| 搬运参考 SQL / 接口模板必须整体复制 + 工具校验，禁止手抄改写 | 本文 §4.8 / BOS §5.3 | WP2 / WP8 实施纪律 |
| GUC 主防线取 session 级三件套，transaction-local 仅作事务内加固 | 本文 §4.9 + EE 教训 | §0 / §5.3.4 / WP5 |
| SINGLE 下凭据 tenant 必须全为 `default` 且拒绝创建非 default 租户 | 对抗式审核 FAIL 项 | §5.6 / WP3 / WP9 |
| 凭据表为无 RLS 平台表，app 角色按 digest 精确查找；禁止 migrator 走业务认证 | 对抗式审核 CONCERN | §5.2 / WP5 |
| 读路径 GUC 回读断言，落实「读 0 行 ≠ 成功」 | 对抗式审核 CONCERN | §5.3 / §7 |
| 验收矩阵补 WP9/WP10；WP2 补 embedding 大表 CONCURRENTLY / 分批回填 / 锁窗口 | 对抗式审核 CONCERN | §7 / WP2 |
| MCP / `@TransactionalEventListener` 幽灵范围按实际清点降级（当前 0 / 3） | 对抗式审核 CONCERN | §5.5 / WP7 / §7 |

### 6.4 仍未被任何参考项目解决、不要误当成已有答案

HNSW + RLS 的召回/吞吐（原路线风险 3 仍是开放项）；租户级删除/导出覆盖 vector/bytea 的
可证明性；非 PostgreSQL 后端的多租户语义；真实 LLM/Embedding Provider 的逐项 operation 合同；
日志、媒体 URL、保留期治理与租户保密性的相邻问题。seedance `experiment-conclusions.md` 明确把
billing ledger、Provider 非幂等 create、对象存储 recovery、动态 OpenAPI、备份故障域列为范围外。

---

## 7. 不要再重复做的探索：复现命令与事实快照

### 7.1 一条命令确认「原预研是否考察过」

```bash
rg -n -S "AI-Native-Business-Operating-System|seedance-research|multitenancy-verification" \
  docs/drafts/MULTI_TENANCY_STATUS.md docs/drafts/MULTI_TENANCY_TECH_ROADMAP.md
```

期望零命中；本仓其他位置的历史零命中结论见第 3.1 节。

### 7.2 一条命令列出本仓现有多租户预研

```bash
ls docs/drafts/MULTI_TENANCY*
```

### 7.3 事实快照（2026-11-13）

| 项 | 值 |
|---|---|
| 本仓预研文件 | `MULTI_TENANCY_STATUS.md`（208 行）、`MULTI_TENANCY_TECH_ROADMAP.md`（658 行） |
| 本仓预研提交 | `39c06936`（11-02 现状）、`4af0f63f`（11-04 对账）、`e921e1ec`（11-04 路线） |
| 原预研外部对象 | 仅 `../Blueforce-Tech-Inc/BlueCortexEE/`、`../Blueforce-Tech-Inc/BlueCortexCE/` |
| seedance PoC | `multitenancy-verification/`，7 案例、81 tests 全绿（2026-10-18）、PG 16 Testcontainers |
| seedance 生产材料 | `MULTITENANCY-CHECKLIST.md`、`RLS-NOBYPASSRLS-GUC.md`、principal-architect-review 系列 |
| BOS 决策备忘 | 2026-11-10，六轮复核，真实 PG 16.8，函数体断裂 43、policy 基线 102/34 |
| BOS 交付 | 2026-11-12 合并 `main`，`28359f69`，V126，DB-1..DB-18 逐门裁决 |
| 本仓规模（原路线统计，已按 2026-11-13 复核） | 69 表、**79** 迁移（V1–V80）、55 `JdbcTemplate` 文件、22 JPA Repository、21 `@Scheduled`、5 `@Async`；「约 56 唯一约束」为 `grep UNIQUE` 行数口径，真实为 23 条 `CREATE UNIQUE INDEX` + 11 条列级 inline `UNIQUE`（见 §4.9） |
| 补遗：seedance drafts 目录 | `../seedance-research/docs/multitenancy/drafts/` 共 11 份文档，原考古只列 2 份；其余 9 份教训见 §4.6 |
| 补遗：PoC 可复用资产 | `multitenancy-verification/src/main/resources/db/init/001_schema.sql`（12 表 RLS + 复合 FK 模板）、`002_roles.sql`（6 角色 + 列级 GRANT + superuser 负向对照）、`src/main/java/.../good/`（11 组推荐实现骨架） |
| 补遗：本仓非事务 JDBC 面 | 20 个文件含 `jdbcTemplate.update/batchUpdate` 且无 `@Transactional`；决定 GUC 主防线取向（§4.9） |
| 对抗式审核结论 | Herdr 会话 `d3c09735-fc3e-4d0f-ae20-f1fb3d50f6b4`，2026-11-13，REQUEST_CHANGES：2 FAIL（GUC 主防线 / SINGLE 越权守卫）+ 4 CONCERN；整改清单见 §6.3 末 6 行 |

### 7.4 下钻顺序建议

1. 本文第 6 节 → 确定要补哪几条。
2. `../seedance-research/multitenancy-verification/docs/experiment-conclusions.md` → 拿实验矩阵与证据标签。
3. 对应 `case-0N-*.md` → 拿反模式、推荐合同、测试命令。
4. `../seedance-research/docs/multitenancy/MULTITENANCY-CHECKLIST.md` → 拿 Spring/JPA 生产约束。
5. `../AI-Native-Business-Operating-System/docs/drafts/verification/2026-11-12-tenant-rename-fullstack-v126/README.md`
   → 拿真实迁移验收门设计。
6. `../seedance-research/docs/multitenancy/drafts/` 11 份文档（尤其 §4.6 的 9 份否决 / 删除 / 收口记录）。
7. `../seedance-research/multitenancy-verification/src/main/resources/db/init/001_schema.sql` 与 `002_roles.sql`
   → 拿 RLS / 复合 FK / 列级 GRANT 模板；`src/main/java/.../good/` → 拿推荐实现骨架。
8. 回到本仓 `MULTI_TENANCY_TECH_ROADMAP.md` 第 8 / 11 节落地条目与验收清单。

---

## 8. 维护约定

- 本文是**考古快照**，不是实施方案；实施时把第 6 节条目并入技术路线与验收清单后，
  在本节记一句「已并入」及对应提交，不要删除本文（它承担「避免重复探索」的索引职责）。
- 参考仓库若有重大更新，优先更新第 7.3 节事实快照与第 4/5 节材料清单，不重写结论叙述。
- 引用参考项目一律用第 2 节约定的相对路径文本；禁止写个人主目录绝对路径，
  禁止把跨仓路径做成 Markdown 链接。
- 中文单语文档；按项目文档治理规则，`docs/drafts/README.md` 与 `docs/drafts/README-zh-CN.md`
  都指向本文件并标注「仅中文」。
- 2026-11-13 追加 §4.5–4.9 补遗：seedance drafts 11 份文档、PoC `good/` 与 SQL 模板、
  本仓数字口径修订、GUC 主防线取舍证据；同时把对抗式审核的整改清单并入 §6.3。
  本轮补遗**已覆盖原考古的材料盲点**，但规划文档的整改仍按 `MULTI_TENANCY_IMPLEMENTATION_PLAN.md`
  单独推进，两者不混写。
