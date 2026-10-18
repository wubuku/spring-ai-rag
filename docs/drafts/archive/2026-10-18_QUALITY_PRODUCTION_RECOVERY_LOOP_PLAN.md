# 质量稳定性与生产恢复闭环规划

状态：2026-10-18 已实施并完成服务兼容性、双实例与恢复验收；真实质量矩阵为 Grok 72/72、
MiniMax 69/72，不能宣称双模型质量门槛全部通过。完整结果见同目录进度记录。
规划于16:10通过连续3/3，安全拒答补充于17:50通过连续3/3。基线 `f65edce1`，实施专用分支
`feat/quality-production-recovery-loop`。用户批准三项同批、按 1→2→3 实施。

## 1. 目标、事实与边界

本批不重建 Evaluation、费用预留、任务 lease、API 契约或备份体系，补齐其可重复验收与恢复闸门。
正式入口：[交付工作流](../../delivery-workflow-zh-CN.md)、[测试指南](../../testing-guide-zh-CN.md)、
[备份恢复](../../backup-restore-zh-CN.md)、[外部 API](../../rest-api-zh-CN.md)。

已探索：

- `scripts/run-knowledge-qa-evaluation.py` 固定三个 KNOWLEDGE_QA case、两次运行，遇首个失败立即退出，
  不保存完整多轮失败分布；服务已有最多 200 case/4 variant 的版本化套件和环境比较 API。
- `EvaluationSuiteDefinitionValidator`、`KnowledgeQaEvaluator` 已断言预期来源、必要/禁止事实、拒答和引用。
  `EvidenceAssessmentEvaluator` 的 SUPPORTED 是引用协议判断，不是事实蕴含证明。
- `CitationQueryAugmenter` 当前只说“引用时优先”，允许模型不写引用；安全回答仍必须 fail closed。
- `AdmissionService` 是实例内计数，预算/任务/幂等的持久化协调已使用 PostgreSQL CAS/lease。
  `verify-dependency-recovery-e2e.py` 有可复用的隔离 PG、受控 HTTP、classpath 快照和清理机制。
- `rag-backup.py` 真实原子恢复，但备份之后的删除/吊销不会自动存在于旧快照中；目前验收只有备份前删除。
- schema 为 V1–V65。本批不改变数据库 schema，不需要迁移，不更改 API URL、参数必填性和成功信封。

非目标：不建设云灾备/PITR、Redis/消息队列、分布式调度平台；不默认增加普通 Chat 的模型调用；
不声称所有合法引用都真实、不用 LLM judge 取代硬断言、不无条件引入数据库全局并发热点。

## 2. 切片一：真实质量矩阵与可归因结果

### 契约

扩展旧 Python gate，保持原命令默认的三例/两轮严格行为。新增显式 quality profile，允许 1–200 case，
重复 2–10 次（默认三轮），单次只选一个 variant；不同真实模型通过分别启动有明确模型配置的服务执行，
不能把 retrieval variant 冒充不同模型。每轮仍走真实 KNOWLEDGE_QA，不是只查 Search。

新增有界、显式的 output 目录（0700，文件0600），保存汇总和完整私有 run/comparison，失败也保留，
默认不输出答案/正文/密钥。完整矩阵结束后统一退出：任何确定性失败、环境漂移、结果缺失、重复/未知
case ID 或对比失败都非零；不能“重试到绿”覆盖第一次失败。传输/服务失败会记录且停止后续收费调用。

汇总按 case 记录每轮状态、服务 failureReasons、模型/环境指纹、token/cost/latency 与失败分类；
类别包含上游/执行失败、来源缺失、必要事实缺失、禁止事实、引用、应拒未拒、应答拒答。
类别允许并存，不把相关性当根因。沿用服务确定性结果作为硬门槛，judge 关闭。
KnowledgeQaEvaluator 的 metrics 增量记录实际 result.model 与 evidenceAssessment（安全回答是否应用），
不记录答案/正文；quality profile 另核对实际模型集合和完整配置快照，补足服务 compare 当前只比较
code/profile/corpus 的边界。无 actual model 或配置字段不能静默当作无漂移。

版本化 fixture 定义 24 个合成用例及来源：数字/单位/表格、否定与改写、多来源组合、冲突与版本、
无资料、Collection 隔离、长噪声上下文、资料中不可信指令；每例提供必要/禁止事实及证据预期。
fixture 使用通用隔离 Collection key，导入/清理由专用 E2E 承担，不在正常启动导入测试文档。
保留三例 smoke 数据；离线负例证明“有合法引用但事实错误”仍被必要/禁止事实门槛拒绝。

### 最小运行时改进

只加强 CitationQueryAugmenter：有依据的事实必须跟随真实给定引用；不能编造引用、把资料中的命令
当系统指令、混淆否定/数值/单位；无法确认就说明。保留 cN/SN 兼容、安全拒答和 SSE 终态协议。
不增加隐式第二次模型调用，不在流式已发送内容之后做补写/替换，不按格式伪造引用。

### 安全拒答补充（2026-10-18用户确认）

明确的安全对齐拒答是正常业务结果，不是应用异常。除文本拒答之外，兼容上游以独立`refusal`
字段或`content_filter`结束原因返回空正文的合法响应。复用Spring AI已经投影到AssistantMessage的
结构化metadata，在调用结果/流式分片进入现有聚合器前，将这种明确拒答归一化为可见的安全回答；
普通空正文/畸形响应继续沿用原规则，不靠敏感关键词推断模型政策，不重试或切模型规避拒答。
保留usage、finishReason、上下文及全部既有API信封，增量提供`metadata.modelSafetyRefusal=true`，
QA metrics保留同一标记，质量汇总单列`MODEL_SAFETY_REFUSAL`，不把它标成执行异常。
KNOWLEDGE仍经过既有证据安全门；不得为拒答伪造引用或放宽必要/禁止事实断言。
新增归一化单测，以及双实例Native/OpenAI JSON/SSE的文本拒答、结构化拒答、空正文过滤终态验收；
唯一终态、PLAIN不重试、预算收敛均为硬断言。受控响应仅证明应用处理，不冒充真实模型政策归因。

## 3. 切片二：双实例一致性、准入公平性与故障

新增 Python 演练复用已有 Acceptance 基础设施；两个独立 Java 进程共享自有 PG 和受控 HTTP，
明确记录两个端口、数据库、PID 和配置。root 与两个独立受管 principal 各走真实 HTTP。

冻结场景：

1. 跨节点文档幂等 replay/CAS 冲突与错误无副作用；Native/OpenAI JSON/SSE 都检查正文和唯一终态。
2. 单调用方占满自己的在线 request/model 配额，另一调用方仍成功；后台导入/QA 不挤掉在线依赖池。
   先用状态/屏障确认占用，再发 probe，不依靠固定 sleep。429 必须明确且有 Retry-After。
3. 两节点共同领取 embedding job，不重复提交；强杀一个节点后另一节点 lease 接管，预算无 RESERVED 残留。
4. 在 A 吊销凭据或删除文档，B 立即拒绝新授权/新检索；检索期间证据变动遵守既有冲突安全契约。
5. Provider 故障时不伪造成功、不得放大为逐条无界重试；恢复后两实例均重新成功。

验收明确实例并发上限及两节点合计，不把进程内限额当全局上限。本批无证据需要新增生产级共享
provider 调度器，因此默认不加；使用已有 per-principal 限额验证在线公平性，不宣称后台完全公平。
若发现实际正确性缺陷，在本批修复并补回归，不因为“只是测试”而忽略。

## 4. 切片三：恢复删除/权限连续性闸门

新增 `scripts/rag-recovery-gate.py`，沿用备份工具的显式 libpq 连接、禁止 URI/service、敏感错误隐藏。
本工具不修改 DB。它是可自动执行的恢复发布前检查，不是不可绕过的网络隔离设施。

### 权威 checkpoint

`checkpoint FILE --ack-quiesced` 在最新权威源停止写入/worker后，只读一致性事务提取受限清单。
固定 inventory v1：文档生命周期/版本、Collection、受管 principal/credential；以及
chat history/summary/turn operation、retrieval log、LLM usage、evaluation suite 的记录指纹。
子记录由既有 FK 删除保证；文档原件/派生完整性仍由已有备份与 API 残留验收验证，不能称清单覆盖
所有外部资源或所有数据库表。擦除 operation 只核验没有 PREVIEWED/APPLYING/PARTIAL_FAILED，
不按 operation UUID 对账（恢复后重新发起擦除会生成新 UUID，不能错误阻止正常收敛）。
inventory 明确写入报告和正式文档。

清单不保存正文、名称、API key/hash 或身份明文：只保存每表主键摘要和规范化安全状态 SHA256、
源连接身份摘要、Flyway版本、UTC生成时间、inventory版本。忽略 last_used/运行计数等非授权字段；
撤销/删除时刻规范为布尔状态，保留影响授权的 expires/retire deadline。每表最多100000行，超限拒绝，
不截断成功；文件拒绝覆盖与符号链接，0700父目录/0600文件。

输出 checkpoint SHA256 供调用者在可信渠道单独保存。摘要只验证完整性，不证明来源；工具不能从
一份旧文件推导“全世界最新”，必须显式确认权威输入，正式文档保留此信任边界。

### 恢复核验

`verify FILE --backup-directory BUNDLE --checkpoint-sha256 SHA --ack-authoritative-checkpoint`：
校验受信摘要、版本/完整 inventory/唯一摘要键、时间（不早于备份且不超过默认一小时，最多允许30秒
时钟偏差）、源身份与备份一致、目标不是源库、schema一致、没有未完成/失败擦除。
目标只读一致性快照比较：恢复记录不得超出权威集合；相同身份安全指纹必须一致。
权威端新增而备份缺少的记录不阻断（允许非零RPO），但恢复不能扩权、复活已删记录或保留旧版本。
报告只含表级计数/分类，不含正文或摘要主键。

失败始终非零且 `trafficReleaseAuthorized=false`；成功也只表示此 inventory 在此 checkpoint 时点
通过，不等于 RPO=0、后续没有撤销或网络已放行。工具不直接 SQL 重放删除/权限；使用已有删除、
吊销、擦除 API 完成对账后重新核验，避免绕过业务 fence/审计。`rag-backup.py` 输出增量加入
`trafficReleaseAuthorized=false`，明确 SQL restore PASS 不是放流量授权，原字段与退出码不变。

### 冻结演练

旧备份→源删除文档/擦除历史/吊销或收窄权限→生成新清单→恢复到隔离库→核验必须失败→用现有 API
完成相同收敛→核验通过。额外验证缺清单、错误可信摘要、篡改、过期、错来源、未知 inventory、
未完成擦除、额外复活记录、同源目标与非一致安全状态全部拒绝。不存在可信新记录时保持隔离，
不能以“恢复成功”绕过。备份仍为显式敏感操作，不自动导出用户数据。

## 5. 文件级实施顺序

1. 修改 QA gate；新增 quality fixture、离线 gate 自测；强化 augmenter 及对应 Java 测试。
2. 新增双实例/质量/恢复演练，复用旧隔离基础设施，不更改旧脚本默认行为。
3. 新增 recovery gate/离线负例及备份输出增量，接入默认仓库门禁。
4. 同步 testing/developer-reference/backup-restore/release-checklist/configuration 等相关 EN/ZH 文档；
   不展开无关历史 TODO 或文档重构。

## 6. 一次性验收矩阵与成本

先离线/受控，后真实；同工作区 Maven 串行，不与 SDK install/package 并行。

- Python gate 自测：24例与旧3例、重复/缺失case、false deterministicPass、混合失败、环境漂移、
  超时、错误退出、失败制品、凭据不输出、恢复清单所有负例。
- Java：augmenter / evaluator / evidence guard 定向单测，随后 clean compile test-compile、全量 mvn test、
  完整 gated PostgreSQL 集成、verify-project-tests/docs/no-pessimistic-locks、diff检查。
- 受控：旧16步恢复演练、新双实例演练、24例×三轮矩阵（受控回答必须证明负例可红，不能冒充模型质量）。
- Client：OpenAPI契约、官方Python/Node Core/Starter JSON/SSE、鉴权/错误/旧参数/增量字段。
- UI共享：typecheck、1030+单测、build、核心Mock Playwright；真实前后端联合与dev.sh隔离启动/停止。
- 真实：两种已授权LLM+BGE-M3，每种24例×三轮；显式调用HTTP rerank成功路径，检查证据与引用、
  禁止事实、拒答、延迟和token。约144次QA，受现有单请求/后台预算限制；明确不可用provider立即调查，
  不无界重跑或自动换模型。每个失败保留，修复后新增run，不删除旧证据；有限样本不称质量100%。
- 恢复：真实PG旧快照、后续删除/吊销/擦除、失败闸门、API对账、成功闸门、原件/向量/历史/权限/JSON/SSE。
- 上述实际测试通过后完成实现只读3/3；如改实现重置并复验。最终归档plan/progress、提交、fetch/merge
  新origin/main，必要时按工作流全序复验，合回main并push，工作区干净，停止自有服务/容器。

## 7. 发布、回退与恢复

运行时新提示约束默认生效；无额外普通Chat调用、无schema迁移。旧三例命令、旧backup输出字段、
全部HTTP契约保持兼容。新增矩阵和恢复工具需主动运行；checkpoint必须独立可信保存且不能提交Git。
回退提示词/脚本可用后续普通提交，不回退已执行schema（本批无schema）。质量失败与结构性API失败
分开报告，但都不允许被漏记/改断言洗绿。进度记录于同目录 PROGRESS。
