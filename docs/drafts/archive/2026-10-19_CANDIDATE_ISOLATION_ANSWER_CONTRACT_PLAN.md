# 候选隔离、阶段时限、回答契约与分层取证

状态：已完成（2026-10-19）。基线 `d95885f5`；实施分支 `feat/candidate-contracts`，待合回 `main`。
实施顺序：1候选隔离 → 2可选阶段时限 → 3回答契约 → 4诊断完整性 → 统一验收。
遵循[交付工作流](../../delivery-workflow-zh-CN.md)，进度见[实施账本](2026-10-19_CANDIDATE_ISOLATION_ANSWER_CONTRACT_PROGRESS.md)。

## 1. 目标、兼容与完成边界

对外Client为主要交付面。保留既有端点、HTTP状态、错误码、finishReason、SSE终态顺序、旧Java构造器和
旧幂等请求指纹。新增参数均可选，新增返回字段均增量。普通Chat不增加judge、诊断或自动修复模型调用。
安全拒答是正常业务结果，不重试/换模型绕过。新隔离和阶段保护默认启用；业务特定格式由请求可选声明。
无数据库结构变化、无需迁移。明确不做跨实例共享熔断数据库、大Parser SPI、UI改版或语义事实裁判。
本批完成不等于所有模型事实/格式满分；服务处置、业务保护、模型质量分别报告，保留所有失败。

## 2. 已探索代码及实施落点

以下core/api路径分别相对于`spring-ai-rag-core/src/main/java/com/springairag/core/`与
`spring-ai-rag-api/src/main/java/com/springairag/api/`。

- `config/RagChatService`持有单个LlmCircuitBreaker，Controller的durable入口也会调用其总闸门。
  `chat/ModeAwareChatClientFactory.budgetedModelFor`覆盖CHAT、QUERY_TRANSFORM、QUERY_EXPAND、SUMMARY；
  `chat/BudgetedChatModel`负责真实调用许可、reservation与usage。应在预算包装器外获取候选许可，
  熔断拒绝不能被记为已调用、收费或消耗模型call预算。
- `config/ChatModelRouter.ChatModelCandidate`包含真实ChatModel与ref；配置工厂缓存实例。
  同一实例代表固定传输配置，不将URL/凭据当公开key。`resilience/LlmCircuitBreaker`已有clock注入、
  half-open与ignored路径；需核对OPEN→HALF_OPEN首次许可与并发探测，避免允许两个probe。
- `rag/HistoryAwareQueryTransformer`目前在commonPool等待超时，但不取消实际调用；
  `rag/BoundedMultiQueryExpander`只限数量、异常后回退，未限制独立等待时长。
  `chat/ChatExecutionBudget`已有请求deadline、atomic调用计数；复用而不创建第二套请求总预算。
- `api/dto/ChatRequest`、`api/openai/OpenAiChatCompletionRequest.RagOptions`是参数入口；
  `chat/ChatCommand`是不可变执行输入，所有with方法、snapshot mapper须保留新约束。
  `chat/ChatRequestFingerprint`手工canonicalize，缺省不加字段才能保留旧key语义。
- `chat/ChatExecutionService.toResult`已有引用/证据保护；KNOWLEDGE流先buffer再发安全内容。
  带契约的其他mode也必须先buffer，不能先发违约token再用completed掩盖。
- `evaluation/StructuredAnswerAssertions`已有有界TOKEN/QUANTITY实现，可复用判定，不复用评测期望作为事实。
  `chat/ChatTurnOperationService.stableMetadata`是白名单，须增量保留回答结果/证据保护/契约字段，
  keyed成功与精确重放均不得丢掉安全元数据；不存私有诊断正文。
- `evaluation/EvaluationDiagnosticCapture`已有整体截断/脱敏标记，`scripts/knowledge-qa-diagnostics.py`
  整体不完整时拒绝所有本地断言。保持完整重放的保守门槛，增量提供部分可复核范围。

稳定契约近距离入口：[上游故障与Client恢复](../../upstream-client-recovery-zh-CN.md)、
[双确认诊断](../../trustworthy-evaluation-admission-zh-CN.md)、[SSE协议](../../SSE-PROTOCOL.md)。

## 3. 冻结决策

### 3.1 候选隔离与许可所有权

新增有界CandidateCircuitRegistry，并扩展既有BudgetedChatModel外层保护，默认启用，保留`rag.circuit-breaker.enabled=false`
作为显式回退开关。沿用现有阈值/窗口/等待配置；registry最多512个真实模型身份，不无限缓存任意Client别名。
key为真实ChatModel对象身份和其配置模型名，同实例/同模型的别名共享故障域；不同实例（不同端点）隔离。
不输出对象、URL、凭据或用户请求值到指标。registry满则fail closed，不能淘汰仍故障/占用的key来洗白状态。
状态是单节点内存；双实例分别探测，不承诺全局一致，也不新增DB写入。

许可位于BudgetedChatModel.call/stream最外层、admission与reserve之前，保持工厂budgetedModelFor返回类型及
现有构造器不变；成功/暂时上游失败/ignored/取消分别收尾且至多一次。
先OPEN拒绝不创建reservation、不记录虚假usage。仅明确暂时故障计熔断，业务/权限/永久错误/拒答不计。
候选OPEN允许尝试下一候选，但不触发同候选应用重试；流首事件后保持不fallback。
所有候选OPEN时仍用旧LLM_CIRCUIT_OPEN；不改旧错误信封。现有service级公开getter/检查方法保留签名，
Spring生产装配时不再作为跨候选总闸门；无registry的手工构造兼容路径保留旧行为。

### 3.2 可选阶段等待、取消与成本

新增Spring管理的QueryEnhancementExecutor：默认最多8个并行worker、零排队、daemon线程，关闭时有界清理。
transform保留原配置时限；新增expansion默认5秒，最终回答预留默认10秒，均可配置正数。
每阶段实际等待=min(阶段配置, 请求剩余时间-回答预留)；不足直接保留输入查询，不启动模型。
池满跳过可选增强，不使普通Chat新增429；既有模型准入/授权/预算拒绝仍权威传播，不伪装成功。
真实模型验证补充（01:40）：仅预留时间不够，不能让迟到增强占满主回答物理配额。
在既有AdmissionService同一model/background池中增加optionalModel，不另建绕开总限额的收费池。
前台可选调用仅在总量/主体/供应商使用量均低于各自上限的一半时获取同池许可；后台按
`maxBackgroundModelCalls - min(maxBackgroundModelCalls, runConcurrency * maxConcurrentRuns)`作为增强准入阈值，
为配置的评测并发保留主答槽。缺省2后台槽与评测并发4（有效2）意味着后台增强自动SKIPPED_CAPACITY，
仍执行完整真实检索/最终LLM；在线不被一并关闭。此降级必须在executionBudget/检索诊断中明确，不偷改评测事实。
OptionalModelCapacityException仅作为可选阶段容量信号，准入拒绝发生于call计数/reservation/provider之前，
不计HTTP429、不计故障熔断；主调用仍使用既有权威准入。资源不足不能提前释放仍运行的增强。
BudgetedChatModel按QUERY_TRANSFORM/QUERY_EXPAND目的选optionalModel，其余purpose保留原model准入；
Executor有界检查cause链，专用容量信号降级，wrapped预算/权限拒绝和取消仍传播。
新增默认后台零额外增强收费、可配置后台余量、前台低配额、迟到调用与主答共存测试；
真实HTTP增加双case评测与迟到增强同时存在的断言，不通过放宽配额或质量断言让矩阵变绿。
超时请求cancel(true)，但不假定provider响应中断：实际worker返回前占用池槽，模型许可/reservation及usage
仍由真实BudgetedChatModel调用finally结算。不能future取消就提前释放真实调用许可。
请求线程记录低敏阶段status/耗时；后台线程不继承诊断capture、认证ThreadLocal或新增正文日志。
原HistoryAware/Bounded构造器保留，生产工厂使用新executor+budget重载；不新增按请求线程池。

### 3.3 可选回答契约与流式安全

Native新增`answerRequirements`；OpenAI新增`rag.answer_requirements`（接受camelCase别名）。
结构为`{"rules":[...]}`，1–16条、唯一id<=64；拒绝未知字段/类型、任意regex、深层JSONPath及超界文本。
规则为TOKEN（value<=128，ASCII标识符边界）、QUANTITY（十进制value与unit<=32，复用现有精确同单位规则）、
JSON_FIELD（field为最多64字符的简单顶层字段名；回答必须是完整JSON object，字段存在且非null）。
数值限制复用现有18位整数/9位小数。规则是输出契约，不是事实真值；不绕过授权/引用/安全策略。

在mapper与指纹入口共同校验并冻结不可变规则，invalid返回旧400/BAD_REQUEST且不claim/调用模型；旧Java构造器保留。
canonical仅非null加入规则，旧请求hash不变；不同契约同key冲突，snapshot执行/重放必须保留约束。
prompt附加有界契约要求，但不得让Client规则凌驾于系统安全。使用规则不另发模型请求。
元数据新增`answerContract`（PASSED/FAILED/SKIPPED_SAFETY_REFUSAL及逐规则id/type/passed），不返回匹配正文。
校验针对原始模型答案；显式安全拒答优先跳过。失败保留已有证据兜底；否则返回固定安全说明，
`answerOutcome=SAFE_FALLBACK/answer_contract_failed`，不修改原finishReason/HTTP状态、不伪造字段、数值或引用。
无声明时不强制客户业务格式。带声明的PLAIN/AGENT也复用有界buffer后发安全内容；任一mode在此buffer溢出时
强制契约失败并返回固定安全说明，不能因PLAIN的证据判定NOT_APPLICABLE而放出不完整/未验证内容。
OpenAI rag扩展保留所有旧构造器；Native/兼容JSON、SSE、keyed结果和replay提供等价摘要。
不把契约失败计为供应商故障或自动修复重试；普通Chat不因新功能增加费用。

### 3.4 字段级取证与有限本地复核

保留整体complete/truncated/redacted及现有采集字节上限、双确认、ACL、过期、no-store和不落库规则。
增量`captureCompleteness`按lastInvocationMessages/evidence/sourceProjections/modelAnswer/answer标注
present、complete、truncated、redacted、原始/采集bytes及截断原因；记录至多现有字段数，不输出未脱敏原文或其摘要。
UTF-8边界按code point裁剪；不得把源全文hash当匿名化保证。每个字段整体独立计数，不让最后写入掩盖前项截断。
本地replay保持原status硬门槛；完整capture仍走原路径。整体不完整但原始/最终答案完整时，增量输出有限
lexical断言复核，status仍INCONCLUSIVE，scope明确不含证据/授权/最终输入核验，networkCalls=0。
missing/脱敏答案、制品校验失败、断言引擎不一致不允许借部分复核宣称通过。旧制品无分字段信息继续保守。

### 3.5 迟到物理调用的中断与记账

真实前台扩展超时后，部分provider仍成功返回，但工作线程保留interrupt标志，可能使同步usage等待
立即失败，并干扰JDBC结算。物理调用结束后的既有有界记账阶段应临时读取并清除进入时的interrupt标志，
在finally恢复；只保护reservation结算/usage记录，不清除模型调用期间的取消，不重试provider，不提前释放许可。
同步/流式共用record入口，沿用既有记账超时和fail-open；取消结果与调用方中断语义不变。
先用确定性回归重现“provider返回时带interrupt、记账必须无interrupt且结束后恢复”，再修复。
真实PG补充成功/失败调用的结算与usage测试，确保数据库真正写入而非仅mock断言。
真实前台验收必须在每个provider停机前等待自己的物理调用和reservation收敛，不能用强制关闭服务
制造迟到记账丢失，再把它误报为正常运行缺陷。停机TTL恢复仍由已有恢复验收覆盖。
修复影响共享记账，重新执行最终后端、PG、前端、外部API/双实例恢复/dev.sh和真实模型序列。

## 4. 实施顺序与证据

1. registry/wrapper及默认配置、候选fallback、legacy工厂接入；并发、单次收尾、A坏B好/探测恢复测试。
2. executor与两种增强器重载、配置、budget阶段摘要；超时/拒绝/不响应中断/真实费用生命周期测试。
3. API规则/mapper/command/prompt/result/fingerprint/snapshot/兼容投影；参数反例、四协议和replay测试。
4. capture字段统计与本地复核；UTF-8/脱敏/截断/旧制品/篡改/零网络测试。
5. 双语正式reference和相关API/配置/命令/测试文档；先统一基本硬门槛，再实现连续3/3和Git交付。

证据目录`.verification/candidate-contracts/`，Maven严格串行，不与dev.sh中的Maven并行。
统一执行clean compile/test-compile、全量mvn test、63仓库门禁、全部真实PostgreSQL集成；相关配置变更
覆盖schema V1–V65启动及旧数据兼容，不添加虚假迁移。
外部HTTP专项覆盖四协议/幂等冲突与重放/旧SDK、A503→B成功/全OPEN/恢复、扩展卡住但主回答完成、
契约失败无违约token泄露、拒答正常、不增加调用、许可/预算收敛；使用真实PG和受控provider，不冒充真实LLM。
共享API同时跑WebUI TypeScript、1030项附近单测、production build、核心Mock Playwright；不截图验收。
再跑双实例/恢复、隔离端口dev.sh真实前后端启停/API/真实浏览器。最后授权真实Grok/MiniMax、BGE-M3与rerank
多场景矩阵，并增加两模型契约场景；真实质量失败保留、正常拒答单列、有限轮次，不反复重跑到绿。
门禁包括docs/consumer/禁悲观锁/脚本语法/diff/敏感值检查。无故障日志或mock不能代替端到端断言。

## 5. 回退与非阻断边界

候选状态重启即清空，仍受调用/费用硬预算保护；显式禁用熔断是可逆诊断开关，不默认legacy总闸门。
增强超时/回答预留可调，必要时关闭queryTransformer；这些回退不需要数据迁移。
回答契约是可选参数，旧Client无需升级；新增Client在旧服务上不能假定约束已生效，应按能力/响应验证。
诊断只增字段，旧离线工具忽略；新工具遇旧制品维持保守判定。
不在本批推断自然语言语义、自动变换单位或修复事实，也不解决供应商不支持取消的物理限制。
完成后提升稳定事实、归档plan/progress，特性分支合回main并push，核对干净工作区后停止。
