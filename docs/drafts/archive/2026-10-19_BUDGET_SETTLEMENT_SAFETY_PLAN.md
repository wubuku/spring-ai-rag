# 预算不确定结算与成本约束闭环

日期：2026-10-19；基线：`main@6b546752`；范围：api 不改、core/budget、core/chat、Flyway、测试与文档。
交付遵循 [工作流](../../delivery-workflow-zh-CN.md)，进度见 [实施记录](2026-10-19_BUDGET_SETTLEMENT_SAFETY_PROGRESS.md)。

## 1. 推荐理由与已核对基线

本批不重复开发已经交付的引用、候选隔离、回答契约、预算预留或 Collection 覆盖。
直接修复现有成本安全链三个确定缺口：

- `BudgetReservationService.expireReservations` 对 RESERVED 执行退款；但超时、进程崩溃或清理任务
  看不到 provider 是否仍在生成，退款后可再次预留同一配额。
- `settleInTransaction` 只接受 RESERVED；过期后到达的真实 usage 永久丢失。
- `BudgetedChatModel.estimatedCost` 将未配置价格当作零；启用费用上限仍可免费预授权未知成本模型。

另有同一次调用并发 reserve 的窗口：先增加日/月 window 再插入唯一 reservation，冲突后事务内
查询可能遭遇 PostgreSQL aborted transaction，也存在重复预留风险。共同纳入本批。

代码入口：
`spring-ai-rag-core/src/main/java/com/springairag/core/budget/BudgetReservationService.java`、
`core/chat/BudgetedChatModel.java`、`core/config/RagLlmBudgetProperties.java`（后两项相对同一包根）。
原有集成基线：`LlmBudgetAndGovernancePostgresIntegrationTest`；运行时夹具：
`scripts/verify-dependency-recovery-e2e.py`。V64 已有 state/version、估算/实际值、日/月窗口，当前最新迁移 V65。

## 2. 目标、默认值和非目标

1. TTL 只是“不确定结算”的触发器，绝不是没有发生费用的证明。默认对过期 reservation
   把估算从 reserved 转入 settled，state 仍为 EXPIRED；不会增加在线模型调用或用户操作。
2. 首个迟到结算允许 EXPIRED → SETTLED，日/月按真实值与此前估算的差额校正；重复结算不再变账。
3. 配置任一费用上限时，未知/负数/非有限价格拒绝预授权；复用既有
   `LLM_BUDGET_SERVICE_UNAVAILABLE`（503），不增加旧错误码/状态的变更。
   明确配置 0 价格代表免费，允许；不配置费用上限的 token-only 部署继续工作。
4. 同一次 invocation 的原子占位先于窗口累加，ON CONFLICT DO NOTHING，重复只返回原 reservation。
   重复身份必须匹配 owner、token/cost 估算；不允许跨 owner 或不同估算复用。

非目标：供应商账单结算、跨币种汇率、精确保证供应商不超过预估 token、自动绕过安全拒答、
新预算管理 UI、OAuth、Embedding Profile 切换。本批不声称 tokenizer 估算是供应商的绝对费用上界。

## 3. 冻结的实现契约

### 3.1 结算状态机

| 输入状态/操作 | 终态 | 日/月窗口操作 |
|---|---|---|
| RESERVED + 正常 settle | SETTLED | reserved 减估算、settled 加真实；未知 usage 沿用估算 |
| RESERVED + TTL 已到 | EXPIRED | reserved 减估算、settled 加估算，不退款 |
| EXPIRED + 首次迟到 settle | SETTLED | reserved 不动，settled 加（真实－原估算） |
| SETTLED/RELEASED + settle | 不变 | 不变 |
| RESERVED + 明确 release | RELEASED | 仅退尚未调用 provider 的预留，既有 Java 方法保留 |

所有转换用 state/version CAS，窗口更新在同一事务；TTL CAS 同时验证 expires_at。
日/月按原 reservation.window_start 结算，跨 UTC 日/月也不转记到新窗口。
不引入悲观锁、advisory lock、SKIP LOCKED。settle/cleanup 的数据库故障不替换原回答，
下一轮 expiry 保守记账；在线 reserve 数据库失败仍 fail closed、不得调用 provider。
终态保留期清理使用合法 SQL 别名，修复现有 `window` 保留字导致的静默失败；真实PG断言
删除过期终态及空窗口、保留 RESERVED 和仍被其引用的窗口，不扩大清理范围。
已过期并被估算结算的 invocation 不可重新派发，沿用 reusable=false。

### 3.2 自动迁移 V66

增加 `V66__conservative_expired_budget_settlement.sql`，不改旧迁移。
历史 EXPIRED 是旧程序已退款的 reservation；对仍保留的 EXPIRED 行按日/月聚合估算，
INSERT/ON CONFLICT 累加到 settled，再填充 actual_tokens/actual_cost 为估算。
历史 SETTLED、RELEASED、活跃 RESERVED 不变；旧版已清理的行无法重建，明确不编造。
新旧程序不得混跑本次 expiry 语义：发布先停旧 worker/实例再自动迁移启动；否则旧 expiry
还会继续退款。迁移是加法且不改 schema，回退旧程序不会损坏已有数据，但会恢复旧风险；
推荐前向修复，不执行破坏性反向退款。

### 3.3 成本预授权与并发身份

包装器返回 null 表示未知价格，不再返回 0；服务在有费用上限时拒绝未知/负值，
无费用上限时只对 tokens 预留、cost 记 0 且不伪称已知。
`MultiModelProperties.ModelCost` 目前把负的 input/output 静默归零，必须保留原非法数值，
让估算器和已有 usage calculator 将其识别为不可用，而不是免费；record/构造器签名不变，
仅收紧非法配置。cacheRead/cacheWrite 既有兼容规范化不属于本批改动。
先插入 RESERVED 占位，再条件更新日/月窗口；任何上限失败使整个事务（含占位）回滚。
同身份冲突不捕获违反唯一约束异常，而是使用 ON CONFLICT；重读后核验不可变身份和估算。
不同调用并发时依靠窗口条件写入控制总额；相同调用不会双计。不改变 Reservation/ReservationRequest
的 Java record 构造器或公开方法签名。

## 4. API、隐私与运维兼容

既有 URL、body/envelope、HTTP status、错误码、finishReason、Native/OpenAI SSE 终态顺序不变。
缺少价格且已经配置费用上限的部署由原来的 fail-open 修正为 503，明确属于成本策略强制执行；
token-only 默认部署无需增加模型价格或配置。旧 Java 构造器与 record 保留。
不得把 secret、prompt、答案写入预算表或日志；`.env` 只在私有运行时加载，不打印敏感配置。
新语义直接启用，不新增 legacy 开关；Flyway 自动升级，无手工补数据操作。

## 5. 实施切片与一次性验收矩阵

1. 新增 V66 和 PostgreSQL 回归：V65→V66 历史退款回填、fresh V1→V66、重复 migrate；
   TTL 不退款、迟到 usage 高/低于估算、未知 usage、settle/expiry 竞争、重复 settle/release、
   跨窗口归因、同 invocation 并发、不同 invocation 总额不超、owner/估算冲突、未知价格 fail closed。
2. 修改 service/model，补包装器价格传播单测；保留 provider 失败/安全拒答/取消原语义。
3. 增加真实 HTTP 受控验收：Native/OpenAI JSON/SSE，价格缺失不调用上游、费用上限生效、
   正常请求/replay 不重复记账；与既有 SDK/恢复脚本组合验证。
4. 更新双语配置/API/测试文档和 Agent 迁移版本号；本批不修改 WebUI，但完整回归其消费契约。

最终固定命令（日志只进 `.verification/budget-settlement/`）：

- 严格串行 Maven：`mvn clean compile test-compile`、`mvn test`，相关 PG 测试显式启用
  `-Dllm-budget-governance.it.enabled=true`，及项目现有 PG 矩阵。不得把 skipped 算 passed。
- `scripts/verify-release.sh --skip-npm-ci --skip-helm --skip-docker`（含 Maven、构建、
  Client contract、SDK helper、WebUI 与完整 Mock Playwright）；
  `scripts/verify-project-docs.sh`、`scripts/verify-no-pessimistic-locks.sh`。
- WebUI typecheck、单测、build、核心 Mock Playwright（命令沿用 package.json）。
- `scripts/verify-upstream-recovery-e2e.py`、`scripts/verify-candidate-contracts-e2e.py`、
  `scripts/verify-dependency-recovery-e2e.py`、`scripts/verify-quality-production-e2e.py`：
  对外四协议、Python/Node SDK、双实例与恢复；新增预算专项 HTTP 夹具。
- `scripts/verify-dev-lifecycle-e2e.py --ack-paid-models`：真实启动/停止、真实 Embedding/LLM、浏览器；
  `scripts/verify-quality-real-e2e.py --ack-paid-models`：保留双模型真实质量和业务处置结果。
  模型内容质量失败单列，不能放宽断言洗绿；应用预算、API、恢复失败必须修复。

## 6. 检查、交付与回滚边界

规划分别做需求/可理解性、状态/SQL/兼容性、验收/迁移/发布三轮只读检查；修正文档即重置0。
实施先基本编译/PG/HTTP验证，再做完整验收，最后实现连续3/3。
每轮输出时间、范围、发现和处理；无问题轮次不改文档，最后统一记录。
专用分支 `feat/budget-settlement-safety`，不创建 worktree、不覆盖 WIP；完成后 commit、fetch、
必要时 merge origin/main 并重验，再 merge/push main，核对 HEAD/main/origin/main 与干净状态。
API 兼容失败、窗口变负/重复计费、迁移破坏性变化、provider 在未授权前被调用均阻断交付。
