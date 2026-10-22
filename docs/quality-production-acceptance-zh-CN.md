# 质量稳定性与生产恢复验收

本批增量能力：[可信断言、主动诊断与离线质量发布准入](trustworthy-evaluation-admission-zh-CN.md)；既有 API 契约不变，普通 Chat 不增加评测费用。

> [English](quality-production-acceptance.md) | [中文](quality-production-acceptance-zh-CN.md)

本页定义主动执行的质量、双实例和恢复检查；不是新调度平台，也不会为普通 Chat 增加评测费用。
外部契约仍以 [REST API](rest-api-zh-CN.md) 和 [Client Contract Kit](client-contract-kit-zh-CN.md) 为准。

## 版本化真实问答矩阵

旧 `run-knowledge-qa-evaluation.py` 命令默认仍为三例 smoke、两轮，旧定义继续可用。
新增 `--profile quality` 支持1–200个唯一case、2–10轮（默认3），一次选择一个variant。
服务负责真实 KNOWLEDGE_QA、预期来源、必要/禁止事实、证据不足与引用的确定性断言；judge必须关闭。
合法引用不是事实正确的证明，必要/禁止事实仍是独立门槛。

```bash
python3 scripts/test-support/knowledge-qa-quality-self-test.py
# 调用者安全设置 KNOWLEDGE_QA_BASE_URL/API_KEY/DEFINITION/SUITE_KEY 后运行：
python3 scripts/run-knowledge-qa-evaluation.py --profile quality --repeats 3 \
  --output-directory .verification/my-quality-run
```

输出目录必须尚不存在；目录0700、文件0600。run/comparison/summary包含私有来源/事实和trace，不能提交Git。
失败会保留；模型答案失败后继续收集有限轮次，不重试到绿。执行/provider/传输错误停止后续收费轮次。
缺失/重复/未知case、variant不符、确定性失败、环境漂移或不完整配置/model归因均非零退出。
客户端除服务compare外，还检查完整configurationSnapshot、代码、embedding profile、定义SHA和实际模型集合。

汇总按case保留轮次、实际模型、latency、usage、citation状态、trace、failureReasons和聚合指标。
为保持旧smoke输出兼容，顶层 `qaPassRate` / `totalTokens` 仍表示首轮；跨轮结果以
`caseResults` / `aggregateMetrics`为准。case usage是最终答案调用的上游报告，不包含独立查询扩展等调用；
需要全调用成本时核对现有LLM用量账本，不能把此值当全链路计费总额。
`ANSWERABLE_REFUSAL`表示应答场景出现安全替换/拒答，不表示已证明检索或模型中的具体根因。
没有配置价格或没有上游usage时不能伪造费用/精确token，也不能把延迟当生产SLA。
QA metrics新增 `actualModel`（provider报告型号，缺失时为配置型号）、`modelRef`（路由别名）、
`modelAttribution`（报告/配置/未知归因）、`modelDefaults`与 `evidenceAssessment`，旧字段/HTTP envelope不变。
`modelDefaults`只记录temperature/topP/topK/maxTokens/frequencyPenalty/presencePenalty的非空配置默认值，
不含凭据或请求内容，也不是单次请求预算调整后的有效参数；这些数值与路由/实际型号一并参与漂移检查。
主动QA沿用现有诊断开关、隐私投影与owner授权，case的 `traceId` 是可查询的检索诊断UUID，
不是HTTP请求trace字符串。诊断关闭/创建失败时仍可为空；诊断故障不改变评测断言结果，provider失败也尽力保留trace。

`testdata/knowledge-qa-quality/v1/`是24例合成语料/用例，不是真实客户goldenset：数字、单位、表格、
否定、改写、版本、跨文档、恶意资料指令、冲突、资料不足与Collection隔离。用例修改应建立新目录版本，
不要为了某个模型改低断言或抹掉失败。单元测试另覆盖上下文截断、安全引用以及“合法引用但错误事实”。

## 双实例与恢复的受控验收

```bash
python3 scripts/verify-quality-production-e2e.py
```

创建自有PG容器、两个独立后端和loopback受控上游。覆盖跨节点upsert replay/CAS无副作用、principal
过载公平性、吊销/删除即时生效、24例多轮正反例、依赖失败恢复、节点强杀后lease接管和费用预留终态，
以及旧备份恢复后的删除/权限闸门。私有证据位于 `.verification/quality-production/`，结束清理自有服务/容器。
受控回答只证明协议与断言路径，不能称真实模型质量通过。脚本中的默认数据库是可处置测试库，绝不清理用户开发库。

准入仍为**实例内**限制；两副本上限不能冒充共享Provider的全局上限。
在线公平性由现有principal限额保护，不承诺后台任务严格公平。共享配额、预算和任务由已有DB机制协调。
恢复闸门的可信输入、覆盖inventory及排除项见 [备份与恢复](backup-restore-zh-CN.md#恢复后的删除与权限闸门)。

## 主动调用真实模型

先运行离线与受控检查，再安全导出已授权的模型/embedding环境变量：

```bash
python3 scripts/verify-quality-real-e2e.py --ack-paid-models --embedding-profiles profile_a,profile_b,profile_c
```

默认依次使用 `openai,minimax`；每个 LLM × 每个 Embedding Profile 执行24例×3轮，共432次QA，另有Client JSON/SSE/SDK与HTTP rerank检查。
三个 Profile 必须包含两个同维不同模型与一个不同维模型。对每个 Profile，脚本建立独立 Collection 副本并改写同一评测定义，验证真实写入和检索路由。
可用 `--providers openai` 或 `--repeats 2`缩小明确标注的验收范围；不能把单模型结果报告成双模型通过。
`--definition /private/subset.json`可选择引用同一合成语料的版本化子集；结果数量以实际case为准，不能冒充完整24例矩阵。
此执行器显式启用已有诊断持久化，要求case trace齐全，并抽查引用可读；不改变服务部署的诊断开关默认值。
它不会读取或打印`.env`，由调用者向环境注入 `SPRING_AI_OPENAI_*`、`SPRING_AI_MINIMAX_*`，以及每个 Profile 对应的
`RAG_QUALITY_PROFILE_<KEY>_API_KEY`、`_BASE_URL`、`_PROVIDER`、`_MODEL`、`_DIMENSIONS`；base URL 不得包含 `/v1`。Profile 参数经私有进程环境传给 Spring，不写入命令行和结果摘要。外部models文件禁用，以明确被测provider。HTTP rerank凭据沿用现有工厂配置继承。
真实执行在自有隔离库，日志/逐轮结果在 `.verification/quality-real/`，不是服务默认启动任务。

支持可变输出维度的模型还可显式设置 `RAG_QUALITY_PROFILE_<KEY>_REQUEST_DIMENSIONS=true`，
默认不发送该字段。执行器在启动数据库前逐 Profile 请求 `/v1/models` 和 `/v1/embeddings`，
验证模型存在、真实返回维度及非零有限向量；失败仅记录脱敏状态，不进入矩阵。可用
`--preflight-only` 单独执行该检查；通过预检不等于质量验收通过。

真实质量失败与结构性/API失败分别报告；两者均不能靠review、换模型后重跑或删除原始run洗绿。
组合执行器因任一模型质量门槛非零退出时，必须保留该失败结论；即使其Client API检查通过，
也不能称该模型已取得矩阵质量准入。服务处理、模型事实质量与正常安全拒答应分别解释。
有限用例重复通过提供可追溯信心，不意味着事实正确率100%，更不意味着任意客户语料都有同样表现。
默认问答提示在资料/问题之后再次强调真实引用与证据不足的明确声明，减少长上下文造成的约束遗忘；
这不是自动补引用或第二次模型调用，硬门槛及安全拒答规则不变。
模型明确的安全对齐拒答是正常业务结果，不是服务异常，也不能被忽略或通过重试/切模型规避。
验收分别检查JSON/SSE成功信封、明确拒答或受控安全回答、唯一终态和预算收敛；质量门禁仍保留
“预期事实未回答”等结果，不把质量未达标直接当作应用故障。没有上游证据时，缺引用不能直接归因为安全拦截。
双实例演练显式模拟安全拒答，检查PLAIN/KNOWLEDGE及Native/OpenAI协议；模拟结果不冒充真实模型安全策略证据。
当上游明确通过独立`refusal`字段或`content_filter`终态表达拒答时，即使正文为空也归一化为可见安全回答，
增量标记`metadata.modelSafetyRefusal=true`；QA metrics沿用该标记，汇总单列`MODEL_SAFETY_REFUSAL`，
不作为上游执行异常。普通空正文或仅含“安全”字样的文本不会被猜测为结构化拒答。
OpenAI兼容响应在可选`rag.modelSafetyRefusal`中投影同一信号；既有Java四/五参数构造器保留。
汇总在`refusalOutcomes`及逐例`refusalType`记录此正常结果，不塞进`failureCategories`；
若必要事实没有回答，则仍单独保留事实层面的质量未达标，不等同服务异常。
KNOWLEDGE对明确的结构化拒答使用受控安全提示和`reasonCode=provider_safety_refusal`，不把安全政策
冒充成资料不足。归一化先于ChatClient advisor/流聚合和共享记忆提交，避免空正文造成持久化失败或丢失拒答信号。
这不是独立内容审核器，不能撤回PLAIN流中已经发送的片段；KNOWLEDGE仍先完成既有证据安全门。

## 完整交付

仍执行 [交付工作流](delivery-workflow-zh-CN.md)：Maven与SDK构建串行；后端全量/gated PostgreSQL、
仓库门禁、外部Client/OpenAPI、前端/真实联合、dev.sh启停、真实模型，随后实现只读3/3。
备份/闸门成功不替代API、安全、原件/向量或外部资源验收。
