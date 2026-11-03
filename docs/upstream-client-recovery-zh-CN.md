# 上游故障与 Client 恢复

熔断现默认采用[候选模型隔离](candidate-isolation-answer-contract-zh-CN.md#1-候选模型故障隔离)，
而非让单个故障拦住健康候选；拒答仍为正常结果，既有错误信封不变。

> [English](upstream-client-recovery.md) | [中文](upstream-client-recovery-zh-CN.md)

## 兼容与默认行为

新分类和保护默认启用，不需要迁移或人工改环境。已有HTTP状态、error/code、failureClass、JSON/SSE
信封保持；Native未知异常仍500，OpenAI未知异常仍503。不能仅根据SSE的HTTP200判成功。
既有粗分类见[Client Kit](client-contract-kit-zh-CN.md#5-错误动作映射)。

错误对象增量提供 `failureDetails`：

```json
{"origin":"UPSTREAM","reason":"UPSTREAM_RATE_LIMITED","retryable":true,"retryAfterMs":2000,"upstreamStatus":429}
```

origin为UPSTREAM/REQUEST/SERVICE；reason是低敏原因，不包含provider正文、URL或凭据。
Native放在错误正文或SSE的error对象，OpenAI放在error对象。旧错误快照可能没有该字段。
HTTP401/403的上游认证错误不转成Client自身401；正文中出现“安全”不被猜测为模型安全拒答。

## 原因与执行决策

| reason | 默认行为 |
|---|---|
| UPSTREAM_AUTHENTICATION / UPSTREAM_REJECTED | 不重试或fallback，修正服务配置或不支持的请求 |
| UPSTREAM_RATE_LIMITED / UPSTREAM_UNAVAILABLE / UPSTREAM_TIMEOUT / UPSTREAM_CONNECTION | 按既有开关，在次数、deadline和费用预算内重试；仅首个流事件前允许fallback |
| UPSTREAM_RESPONSE_INVALID | 不把解析失败当网络故障反复请求 |
| REQUEST_DEADLINE / CANCELLED / BUSINESS_REJECTED / UNCLASSIFIED | 不自动重试或切模型，不计依赖熔断失败 |

识别有界异常链和结构化状态，不解析provider错误正文猜测原因。无法识别的供应商SDK异常保守归类，
不承诺所有SDK都能提供HTTP状态。Chat模型关闭供应商SDK内部的额外重试，由应用统一预算控制。
Anthropic流式在传输filter先保留结构化状态、丢弃错误正文，避免SDK把正文拼进普通异常或日志。
退避使用配置的指数和抖动；合法Retry-After支持秒/HTTP日期，返回建议最多10秒。
等待达到配置上界或无法在剩余deadline内完成时直接结束，不提前于provider建议再次调用。
排除的业务错误/取消会释放half-open探测占位，而不是把模型判定故障。

`retryable=true`仅表示原因可能暂时恢复，不保证请求可重放。Client默认不自动重试：
keyed请求先查turn；已收到部分流时报告部分输出，不自动拼接第二次答案。仅明确可安全重放时做有界重试。
失败turn同key仍精确重放失败，不会因为新增retryable就重新调用模型。
Python/Node示例显式关闭SDK默认自动重试，提供纯本地recovery决策函数。

## 回答业务结果

Native `metadata.answerOutcome`、OpenAI `rag.answerOutcome`提供status和reasonCode：

- MODEL_SAFETY_REFUSAL：provider有明确refusal/content_filter，是正常结果，不重试或切模型规避。
- SAFE_FALLBACK：应用证据保护替换了输出；不意味着已经回答事实问题。
- OUTPUT_TRUNCATED：provider以length/max_tokens结束；保留原finishReason。
- OUTPUT_INCOMPLETE：缺少模型finishReason，不把缺失结束信号的输出称为完整回答。
  此时Native终态仍可发送`done`，但`status=incomplete`，不会伪装成`complete`。
- ANSWER：其他已完成响应；不保证事实正确，引用合法也不等于事实正确。

优先级为拒答→安全兜底→截断/不完整→回答；失败/取消仍走原有错误路径，不伪造业务回答。
不改变既有空流兼容行为；Consumer Kit继续拒绝缺少终态的流。

## Embedding 故障降级

上传/导入（SYNC/ASYNC job）与显式`POST /rag/documents/{id}/embed`在provider
5xx/429/超时/连接中断/畸形响应时都返回`FAILED`而不是500堆栈；`/embed`响应体带
`failureClass`、`failureDetails`、`retryable`，并回写`X-RAG-Failure-Class`头。
提交阶段遇到版本/Collection并发冲突返回409+`CONFLICT`，不泄露堆栈。

检索侧query embedding失败时降级到全文检索（混合检索开启时仍返回全文结果），
指标记为`operation=retrieval.embedding`。整批上游故障不再逐条fan-out，
避免provider调用量随chunk数放大；批内局部错误仍逐条回退。

重试是有界的：transport层`RetryTemplate`默认3次、指数退避（2s起、最多10s、
尊重Retry-After），job层`default-max-attempts`默认3次。单次provider调用受
`rag.embedding.request-timeout-ms`约束（默认30000，范围100–120000），没有
deadline时也不会无限等待。可用`ceil(chunks/batchSize) × transport × job`
估算单文档故障的调用上限。

未归属Collection的文档（`collection_id IS NULL`）仍可生成/替换向量；只有
Collection处于不可写状态时才拒绝提交。失败时`rag_embeddings`保持不变，
job表记录`last_error`，不会留下半写向量。

可观测性：`rag.upstream.failures`指标按`operation/provider/reason/retryable`
打标签，`operation`覆盖`chat`、`chat.stream`、`embedding.document`、
`embedding.job`、`retrieval.embedding`；结构化日志包含`provider`、`reason`、
`retryable`以及`jobId/documentId/profileId/traceId`，可直接定位失败调用。

## WebUI 失败呈现

WebUI 不直接展示 `X-RAG-Failure-Class`、`HTTP 503` 或供应商英文正文；
`spring-ai-rag-webui/src/utils/failureClass.ts` 是唯一解释层：

- 分类与 `failureDetails.reason` 映射到中英文案（`failures.*`），reason 优先于粗分类。
  响应没带分类头时按 HTTP 状态兜底：409→冲突、429→限流、502/503→上游不可用、504→超时。
- 只有 `CLIENT_REQUEST`/`CONFLICT`/`NOT_FOUND` 把服务端 reason 附在文案后；
  限流/上游不可用/超时/鉴权只给本地化解释，避免把技术栈贴到用户脸上。
- `AUTHENTICATION`/`AUTHORIZATION`/`CLIENT_REQUEST`/`NOT_FOUND` 不给重试入口；
  其余分类与未知分类都可重试。
- Chat 的 `done.status=incomplete` 与 SSE 中途断流都是终态：显示不完整标记与重试入口，
  不渲染成正常完成，也不残留 loading 光标；重试复用同一 prompt。
- `/embed` 的 HTTP 200 + `status=FAILED` 按失败提示，上传失败读分类而不是展示 HTTP 状态。

对应 Mock Playwright 见 `spring-ai-rag-webui/e2e/failure-modes.spec.ts`（[测试指南](testing-guide-zh-CN.md)）。

## 私有诊断与验收

[双确认主动诊断](trustworthy-evaluation-admission-zh-CN.md)增量提供modelAnswer（安全替换前同次输出）、
answer（最终交付内容）、answerOutcome和citationValidation。modelAnswer最多32KiB，合计文本最多224KiB；
脱敏/截断会标记不完整。普通Chat不采集、不增加模型调用，不把正文写入metrics或共享诊断日志。
本地replay分别给出原始/最终断言摘要，但仍以原运行硬门槛决定通过，不恢复或覆盖历史。
引用prompt明确列出请求内标识，不修补无效引用；仅用户要求单位时约束数值+原文单位。

验收命令：`python3 scripts/verify-upstream-recovery-e2e.py`（chat故障矩阵：隔离PG、真实HTTP、受控provider）；
`python3 scripts/verify-embedding-failure-e2e.py`（embedding上传/导入/检索降级、调用上限、指标与日志）；
`bash scripts/verify-client-consumer-contract.sh`（离线契约与两语言恢复决策）。
两条故障脚本不需要真实模型，已在nightly的`failure-modes`job中执行。
受控故障不能冒充真实模型质量；真实Embedding/LLM、双实例恢复和dev启停分别验收。
