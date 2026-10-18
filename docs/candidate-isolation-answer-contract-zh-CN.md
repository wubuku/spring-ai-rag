# 候选隔离、阶段时限与回答契约

> [English](candidate-isolation-answer-contract.md) | [中文](candidate-isolation-answer-contract-zh-CN.md)

本能力服务于外部 Client：既有端点、必填参数、HTTP 状态、错误码、finishReason 和 SSE 终态顺序不变。
保护默认启用；业务格式约束由请求可选声明。没有 schema 变更，不需要迁移或人工数据处理。
普通 Chat 不增加 judge、主动诊断或“自动修复”模型调用。

## 1. 候选模型故障隔离

`BudgetedChatModel` 在 admission、调用计数和费用 reservation 之前获取候选许可。
OPEN 候选不产生虚假调用、usage 或费用；允许尝试下一候选，不重试同一个 OPEN 候选。
流式仅在首个事件之前允许 fallback，已经开始的输出不能拼接另一个模型的答案。

故障域是实际 `ChatModel` 对象身份与其配置模型名，而不是 Client 输入的别名。
共享同一个实例的别名共享状态，不同实例独立。不同端点通常对应不同实例；不把 URL、密钥或正文
放进公开 key。状态是单节点内存，重启清空；双实例分别探测，不承诺全局共享熔断。
最多512个故障域，满时 fail closed，不通过淘汰 OPEN key 掩盖故障。

仅明确的暂时上游错误计入熔断；永久请求错误、权限/预算拒绝、取消及正常安全拒答不计入。
HALF_OPEN 同时只允许一个探测；ignored/取消释放探测占位，实际调用成功/失败至多收尾一次。
许可绑定熔断代次，旧请求迟到的成功、失败或取消不能关闭/释放新代次的探测；窗口更新在短内存临界区原子完成。
所有候选 OPEN 仍返回既有 `LLM_CIRCUIT_OPEN`，不新增错误信封。
旧公开 service 熔断接口/构造器保留；Spring 生产路径使用候选闸门，不再让一个坏模型拦住所有健康模型。
错误分类与客户端恢复动作见[上游故障与 Client 恢复](upstream-client-recovery-zh-CN.md)。

## 2. 可选查询增强的独立时限

查询改写和扩展仍遵守既有启用条件；新增保护不强制开启原本禁用的增强调用。
实际等待时间为 `min(阶段上限, 请求剩余时间 - 最终回答预留时间)`。
不足则 `SKIPPED_DEADLINE`；零排队线程池满则 `SKIPPED_CAPACITY`；超时 `TIMED_OUT`；
普通依赖失败 `FAILED`。这些可选增强失败保留原查询，不吞掉权限、费用硬预算或其他业务拒绝。
调用线程被取消时传播取消，不继续主回答。

时间预留同时配合**同池容量预留**：QUERY_TRANSFORM/QUERY_EXPAND在收费与调用计数之前
使用可选准入，前台只有全局/主体/供应商占用均低于各自上限一半才运行；主调用仍遵守原总限额。
后台增强阈值为`maxBackgroundModelCalls - min(maxBackgroundModelCalls, runConcurrency * maxConcurrentRuns)`，
为评测并发保留最终回答槽。缺省2个后台槽、有效评测并发2时，后台增强自动SKIPPED_CAPACITY，
仍执行真实检索、最终LLM与断言，不偷偷扩容、关闭保护或注入评测事实；在线增强不因此关闭。
这属于显式诊断的资源降级，不是另一条伪造质量的执行链。低配额场景优先主答；缺槽不产生费用reservation、
provider调用或HTTP429，指标`rag.admission.optional.skipped`只使用固定池类别。
评测`configurationSnapshot.queryEnhancementPolicy`记录策略版本与准入限额，逐例`metrics.queryEnhancements`记录实际降级，
让质量比较识别有效执行环境，不把“配置了增强”等同于“增强实际执行”。
仍运行的迟到增强继续占同池物理许可，不能靠提前释放让总并发失真。

| 配置 / 环境变量 | 默认值 |
|---|---:|
| `rag.circuit-breaker.enabled` / `RAG_CIRCUIT_BREAKER_ENABLED` | `true` |
| `rag.chat.knowledge.query-transform-timeout-seconds` | `30`（既有设置） |
| `rag.chat.knowledge.query-expansion-timeout-seconds` / `RAG_CHAT_QUERY_EXPANSION_TIMEOUT_SECONDS` | `5` |
| `rag.chat.knowledge.query-enhancement-answer-reserve-seconds` / `RAG_CHAT_QUERY_ENHANCEMENT_ANSWER_RESERVE_SECONDS` | `10` |
| `rag.chat.knowledge.query-enhancement-max-concurrency` / `RAG_CHAT_QUERY_ENHANCEMENT_MAX_CONCURRENCY` | `8` |

新增时间配置1–300秒、并发1–64。既有熔断窗口/阈值/冷却配置沿用。
增强使用独立有界工作线程，不继承认证或私有诊断 ThreadLocal；授权和预算由不可变执行上下文传递。
超时会请求取消，但供应商不响应中断时，实际线程/许可/费用仍保持到物理调用结束，不能提前假装回收。
超时后主回答可继续；这不是强制杀死上游。`metadata.executionBudget.queryEnhancements` 增量记录两个固定
阶段的状态与耗时，不包含问题或正文。必要时可调时限/并发或关闭增强，不需迁移。
迟到物理调用进入既有有界结算/usage阶段时，临时清除进入时的中断标志并在finally恢复，避免已完成调用
仅因取消标志而丢失记账；不改变provider取消、许可占用、原异常或记账超时，不增加模型重试。

## 3. 请求级回答契约

Native `POST /api/v1/rag/chat/ask` 与 `/stream` 可增量传入：

```json
{
  "message": "返回确认过的数量及状态",
  "mode": "KNOWLEDGE",
  "answerRequirements": {
    "rules": [
      {"id":"code","type":"TOKEN","value":"SAFE-42"},
      {"id":"count","type":"QUANTITY","value":"0","unit":"条"},
      {"id":"shape","type":"JSON_FIELD","field":"status"}
    ]
  }
}
```

这只是约束形状示例，不意味着上述事实存在于所有知识库；调用方仍需提供原有 scope。
OpenAI 兼容 `POST /v1/chat/completions` 使用 `rag.answer_requirements`，也接受 `answerRequirements` alias。
整个字段缺省或显式 null 保留旧行为及旧幂等指纹；规则变化会改变指纹，同 key 不同规则返回既有409。
坏规则在调用前拒绝，JSON返回400；Native未带key的流沿用HTTP200 + error/BAD_REQUEST，不伪造成功终态。
带key的指纹校验发生在claim前。旧服务可能忽略未知参数，Client 必须检查契约摘要，不能假定其执行。

- `rules` 为1–16条，id 唯一、1–64字符；禁止未知字段/类型及任意正则表达式。
- `TOKEN`：value 最多128字符，复用确定性标识符边界，避免 `SAFE-42` 被 `SAFE-420` 冒充。
- `QUANTITY`：value 最多32字符、unit 最多32字符；复用有界十进制断言（18位整数/9位小数），
  数值和单位必须匹配，不补造单位或事实；支持范围见[结构化断言](trustworthy-evaluation-admission-zh-CN.md)。
- `JSON_FIELD`：field 为顶层简单标识符（字母开头，后接字母/数字/下划线，最多64字符）。
  整个答案必须是单个 JSON 对象，指定字段非 null；拒绝代码围栏、尾随文本、重复键、深层 JSONPath。
  JSON 解析限制为64层、数字128字符，最多检查262144字符。

规则不是事实依据、不凌驾于安全和授权，也不执行语义真值裁判。
Native `metadata.answerContract`、OpenAI `rag.answerContract` 增量返回 `status`、
`rules[{id,type,passed}]`、`safeAnswerApplied`，正常检查含低敏 `reasonCode`，不回显匹配正文。
状态为 `PASSED` / `FAILED` / `SKIPPED_SAFETY_REFUSAL`。

不满足时不调用另一个模型“修复”，而是返回固定安全说明及 `answerOutcome=SAFE_FALLBACK`、
`reasonCode=answer_contract_failed`；既有证据安全兜底优先，不能用格式约束绕过它。
显式模型安全拒答仍是正常业务结果，跳过契约、不重试/切模型规避；HTTP 和 finishReason 不变。
带契约的所有 mode 流式先有界缓冲，完成校验后才发 content，不能先泄露违约 token 再更正。
缓冲溢出强制安全失败。无契约 PLAIN/AGENT 保留既有实时流，KNOWLEDGE 保留既有证据缓冲。
keyed 成功、状态投影及精确重放保留契约/证据/回答结果摘要；不持久化私有诊断正文。
Client 应检查 answerOutcome/answerContract，而不是只看 HTTP200 或 `[DONE]`。

## 4. 字段级诊断完整性

双确认、ACL、期限、制品完整性、no-store 和采集总上限仍按
[主动诊断](trustworthy-evaluation-admission-zh-CN.md)；普通 Chat 不主动采集正文。
`capture.captureCompleteness` 为以下固定字段提供独立计数：
`lastInvocationMessages`、`evidence`、`sourceProjections`、`modelAnswer`、`answer`。
每项含 `present/complete/truncated/redacted/originalBytes/capturedBytes/reasons`。
计数累计本次采集过程中的同字段输入，不表示只对最后一次覆盖后的正文计数；截断/脱敏 sticky，
后一次小输入不能洗掉前一次不完整。UTF-8 在 code point 边界裁剪，不泄露被省略/脱敏正文的 hash。

`knowledge-qa-diagnostics.py replay` 保留原整体硬门槛。整体不完整但最终答案字段完整时，
可输出 `partialVerification`：只做最终答案 lexical 断言；原始答案也完整时附模型答案断言。
顶层始终 `INCONCLUSIVE`，scope 为 `FINAL_ANSWER_LEXICAL_ONLY_NOT_EVIDENCE_OR_AUTHORIZATION`，
不能称为证据/授权/最终输入核验通过，也不升级原始 FAILED。
旧制品无字段信息、字段缺失/脱敏、制品校验失败、断言引擎不一致均不能冒充部分通过。
本地 replay 的 `networkCalls=0`，不触发付费模型。

## 5. 验收入口

```bash
TESTCONTAINERS_PG_IMAGE=postgres:16-pgvector python3 scripts/verify-candidate-contracts-e2e.py
python3 scripts/test-support/trustworthy-evaluation-self-test.py
python3 scripts/verify-dev-lifecycle-e2e.py --ack-paid-models
python3 scripts/verify-quality-real-e2e.py --ack-paid-models --providers openai,minimax --repeats 2 \
  --definition testdata/knowledge-qa-quality/v2/definition.json --with-diagnostics --ack-sensitive-content --with-answer-contracts
```

第一条使用隔离真实 PostgreSQL、真实 HTTP 与受控 provider，验证恢复/契约/预算，**不代表真实模型质量**。
dev验收私下读取`.env`，隔离数据库/前后端端口，验证真实模型、旧OpenAPI及浏览器、启停所有权；不得与另一dev.sh共享状态。
真实模型、双实例、恢复及完整兼容性门槛见[测试指南](testing-guide-zh-CN.md)；同工作区 Maven 严格串行。
本批不引入 Client 必迁移步骤；显式禁用熔断仅为可逆诊断开关，不是默认 legacy。
