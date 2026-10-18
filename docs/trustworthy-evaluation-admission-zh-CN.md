# 可信断言、主动诊断与质量准入

字段级完整性及保守部分复核见[诊断完整性 §4](candidate-isolation-answer-contract-zh-CN.md#4-字段级诊断完整性)。
整体不完整时仍为 INCONCLUSIVE；部分答案断言不证明证据或授权正确，且不升级原失败。

> [English](trustworthy-evaluation-admission.md) | [中文](trustworthy-evaluation-admission-zh-CN.md)

本能力默认可用，不改变已有 Chat、Search、Evaluation 的 URL、成功信封或必填参数。
普通 Chat 不额外调用评测模型；本批没有新增持久表或 schema 迁移。
完整交付基线见[质量与恢复验收](quality-production-acceptance-zh-CN.md)。

## 可选的确定性断言

KNOWLEDGE_QA case 可以增量提供 `assertions` 和 `expectedOutcome`；旧定义与旧 Java 构造签名继续可用。
旧 `requiredFacts` / `forbiddenFacts` 仍按原来的 NFKC、小写、contains 执行，与新断言是 AND；不回写历史结果。
`testdata/knowledge-qa-quality/v1/` 保持不变；`v2/definition.json` 是独立的结构化断言版本，不能用 v2 通过覆盖 v1 失败。

```json
{
  "assertions": [
    {"id":"count","type":"QUANTITY","value":"0","unit":"条"},
    {"id":"code","type":"TOKEN","value":"SAFE-42"},
    {"id":"phrase","type":"TEXT_ANY","alternatives":["不支持离线"]},
    {"id":"range","type":"NUMBER_RANGE","min":"10","max":"20","unit":"ms"},
    {"id":"forbidden-code","type":"TOKEN","value":"UNSAFE","forbidden":true}
  ],
  "expectedOutcome":"ANSWER"
}
```

- 最多32条、唯一 id 1–64字符；拒绝未知类型/字段、非布尔 forbidden、用户正则表达式和类型混用。
- TOKEN 使用 ASCII 标识符边界，`0` 不匹配 `10`，`SAFE-42` 不匹配 `SAFE-420`；不是自然语言分词。
- TEXT_ANY 是1–8个明确短语的字面量择一，不推理同义、否定作用域、意图或注入是否被执行。
- QUANTITY 支持有限十进制、规范千分位和常用中文整数，数值必须紧邻指定单位（允许空白）。不跨句拼接或换算单位。
- NUMBER_RANGE 是同单位闭区间；十进制限制18位整数、9位小数。value/min/max 为字符串，单位最多32字符。
- 指标仅保存 matched/passed、NFKC小写后 Unicode code-point 起止位置；不保存答案或 matchedText。
  纯本地重放额外返回最多128 code-point 的匹配文本。共享 Java/Python 边界夹具位于 `testdata/answer-assertions/v1/`。

expectedOutcome 允许 `ANSWER`、`INSUFFICIENT_EVIDENCE`、`MODEL_SAFETY_REFUSAL`，缺省维持旧行为。
结构化 `refusal` / `content_filter` 才代表明确模型安全拒答，不从正文猜测。它是正常业务结果，不重试或切模型绕过。
明确期待拒答时可无引用，但授权、必要/禁止事实仍执行；没有回答必要事实，仍可质量失败，不是服务故障。
新增 metrics 为 `assertions` / `observedOutcome`；失败理由为 `STRUCTURED_ASSERTION_FAILED` / `OUTCOME_MISMATCH`。
Judge 仍仅作辅助，不能覆盖确定性失败。

断言必须与问题的输出要求对齐：QUANTITY有意不把“记录数为0”当作“0条”的单位字面量命中。
需要单位时在问题中明确数值+单位；只要求数值时先定义清楚输出格式，再设计匹配规则，而不是暗加单位要求。
TOKEN仅提供ASCII标识符边界，不是通用裸数值或语义解析器。
词法/格式门槛失败不能直接宣布答案事实错误；v2是严格字面量示例，不是覆盖所有合理表达的语义金标准。
用例校准应创建新的不可变定义并重新执行，不得覆盖原失败制品；诊断的新回答也不能冒充历史答案。

## 显式主动诊断 API

`POST /api/v1/rag/evaluation/suites/{suiteKey}/versions/{version}/diagnose`

```json
{"caseId":"numeric-limit","variantKey":"default","ackPaidModels":true,"ackSensitiveContent":true}
```

这是**新的付费同步执行**，不是恢复历史模型调用，响应 `executionKind=NEW_PAID_EXECUTION_NOT_HISTORICAL_REPLAY`。
case/variant 必须属于不可变版本，只接受 KNOWLEDGE_QA 且 judge 关闭。两个确认缺任一个返回400且不调用模型。
沿用 WRITE capability、suite owner、当前 Collection ACL；另一 owner 返回404；模型执行后再复核 ACL，禁止撤权后返回正文。
scope 与所有权仍是现有服务的隔离机制，不是任意运维根身份读取他人诊断的入口。

成功响应为原生对象，`Cache-Control: no-store`；包括版本摘要、case、variant、status、metrics、traceId、latencyMs、capture。
执行失败可返回 HTTP200 的 `status=FAILED` 诊断结果，不表示模型执行成功；权限、校验等仍返回既有错误信封。
capture 包含最后一次实际模型调用消息 `lastInvocationMessages`、最终 `answer`、真实 advisor `DOCUMENT_CONTEXT` 的 `evidence`、
客户端来源投影 `sourceProjections`，二者不可混为全文。没有采集到实际 prompt 时不能标 complete。
新增同次调用安全替换前的modelAnswer及answerOutcome、citationValidation；answer仍是最终回答，见
[上游与Client恢复](upstream-client-recovery-zh-CN.md)。prompt、modelAnswer、answer各32KiB UTF-8；
evidence/来源投影分别最多16项，每项4KiB。文本总量最多224KiB，JSON转义后可能更大。
敏感数据脱敏和截断明确标记；只有实际prompt、modelAnswer、answer、evidence均采集到且未脱敏/截断才 `complete=true`。
不写正文到 Evaluation case metrics，不新增历史正文存储；已有 usage/trace 按既有保留与擦除机制管理。

## 私有取证与纯本地重放

服务根地址与 API Key 仅从 `KNOWLEDGE_QA_BASE_URL` / `KNOWLEDGE_QA_API_KEY` 环境注入，不把凭据写进命令或文档。

```bash
python3 scripts/knowledge-qa-diagnostics.py record \
  --suite-key my-quality-suite --version 1 --case-id numeric-limit \
  --definition testdata/knowledge-qa-quality/v2/definition.json \
  --ack-paid-models --ack-sensitive-content
python3 scripts/knowledge-qa-diagnostics.py replay --directory /private/diagnostic-directory
python3 scripts/knowledge-qa-diagnostics.py purge --root /private/diagnostics-root
```

默认根 `.verification/knowledge-diagnostics`；目录0700、文件0600；三个JSON文件与SHA256 manifest，TTL24小时。
拒绝符号链接路径、超大文件、重复JSON键、非有限数字、篡改与不匹配定义；下载响应上限1MiB。
record 之前自动有界清理过期自有目录；离线环境可显式 purge（每次最多扫描1000项），不是后台常驻定时任务。
purge 仅删除符合文件清单且摘要匹配的过期自有目录；篡改目录不猜测归属、不擅自删除。
replay 不发网络、不调模型，只重跑词法断言并保留原始硬门槛；原 FAILED 不能因新断言通过变绿。
不完整/过期/脱敏制品不能获得完整重放通过。摘要证明内部一致性，不证明模型来源或签名身份。
制品由下载者负责保管，服务端擦除不能撤回已下载文件，禁止提交到Git或共享日志。

## 变更与发布准入

主动质量 runner 的 quality 输出增量保存 definition.json 与 SHA manifest；新 run 的 configurationSnapshot
增量保存 `collectionContentSha256`。每 Collection 按稳定外部身份、原件内容、metadata、启用/删除状态摘要，
不依赖数据库ID或updated_at；语料摘要最多读取100001条，超过100000文档时部分摘要标为null，后续质量准入失败关闭，
但不阻断原有评测Run API，避免给老Client增加必需条件。旧 run 不补写、不自动批准。
必须先注入 `KNOWLEDGE_QA_BASE_URL` / `KNOWLEDGE_QA_API_KEY`。独立runner不自动导入或清理文档：
v2共用v1的合成corpus，受控/真实验收脚本会自建隔离语料；生产评测应使用指向已有Collection的自定义版本化定义。

```bash
python3 scripts/run-knowledge-qa-evaluation.py --profile quality --repeats 3 \
  --definition testdata/knowledge-qa-quality/v2/definition.json --suite-key candidate-quality \
  --output-directory .verification/candidate-quality
python3 scripts/verify-knowledge-quality-admission.py \
  --candidate-directory /private/candidate --baseline-directory /private/approved-baseline \
  --baseline-sha256 "$APPROVED_BASELINE_MANIFEST_SHA256"
```

基线必须人工/独立审核并从可信发布配置传入 manifest SHA；工具不会自动接纳最新结果、批准基线或切换模型。
2–10轮完整 case/variant 集合、确定性结果、配置指纹、语料与定义摘要必须可信；缺失、不完整、篡改、漂移均阻断。
候选/基线必须同定义、同语料、同variant；允许模型/配置/代码/Embedding Profile 改动并明确报告变化。
逐例列出质量退化、latency/token比例和失败原因。默认成本/时延只报告；`--max-latency-ratio` / `--max-token-ratio`
显式设置硬限制，缺对应指标也阻断。不将质量未达标计为服务异常率；正常安全拒答单列。

发布入口 `scripts/verify-release.sh --with-knowledge-quality` 使用
`KNOWLEDGE_QUALITY_CANDIDATE`、`KNOWLEDGE_QUALITY_BASELINE`、`KNOWLEDGE_QUALITY_BASELINE_SHA256`。
该门禁完全离线；缺有效制品非零退出，不偷偷付费补测。未指定选项仍保留既有发版行为。

## 验证入口

```bash
python3 scripts/test-support/trustworthy-evaluation-self-test.py
python3 scripts/verify-trustworthy-evaluation-e2e.py
# 先注入已授权的真实模型与Embedding环境；显式新增两次收费诊断：
python3 scripts/verify-quality-real-e2e.py --ack-paid-models \
  --definition testdata/knowledge-qa-quality/v2/definition.json \
  --with-diagnostics --ack-sensitive-content
```

受控验收覆盖24例多轮、审批基线/语料漂移反例、真实PG摘要、双确认、权限撤回、无正文落库、provider失败和本地重放。
真实验收默认两provider各24例×3轮，另加每provider一次新诊断及Client JSON/SSE/官方SDK、真实Embedding/rerank。
这不保证任意模型或客户语料100%事实正确；失败必须保留，受控服务成功不能冒充真实质量通过。
