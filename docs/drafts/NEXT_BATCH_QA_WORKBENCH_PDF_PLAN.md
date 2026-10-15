# 下一批高价值改进规划：问答评测、文档处理工作台与 PDF 资源边界

> 状态：实施中；规划检查已完成，代码与验证正在收敛。
> 中文主稿；本批次实施完成后，需将稳定事实同步到对应双语长青文档，再归档本稿。

## 1. 目标与冻结结论

本批次一次性实施以下三条互相独立但共享验收链路的能力：

1. 在现有受管 Evaluation Suite/Run 上增加版本化的答案级 KNOWLEDGE 评测：用例可声明预期来源、必要事实、禁止事实和证据不足语义；执行真实 Chat 路径；确定性断言决定通过与否，LLM judge 只能提供辅助证据；同一不可变版本可比较不同运行结果。
2. 在现有 Documents 页面增加文档处理 Run 工作台：查看阶段时间线、进度、重试/取消、死信和错误；不另建任务系统，不改变后端 Run/Stage 状态机。
3. 为 PDF 导入和两个转换器增加默认启用的有界资源预算：上传字节、页数、产物数量/总字节、转换超时和子进程/临时文件清理；超限在落库前失败，不能留下部分导入结果。

已冻结的产品决策：

- 保持现有 HTTP 路径和旧 Evaluation 定义兼容；旧 case 默认仍执行检索评测，新增答案评测由 `task: "KNOWLEDGE_QA"` 显式表达。
- 新增能力默认可用，不增加普通 Chat 的评测调用；只有主动创建 Evaluation Run 才调用真实 Chat，LLM judge 仅在用例显式启用时调用。
- 为保持旧 suite/API 兼容，旧 case 省略 `task` 时仍解释为 `RETRIEVAL`；这是输入兼容的默认映射，
  不是让新 QA 能力依赖 legacy 开关。受管 suite 默认启用，仍可通过显式环境变量关闭。
- 答案级硬门槛按确定性结果计算：来源覆盖、必要事实、禁止事实、证据不足声明、引用合法性、请求/模型错误。judge 分数不单独改变 PASS/FAILED。
- 评测执行使用 `MemoryMode.STATELESS`，不写入用户会话历史；使用 suite owner 的授权快照和显式 `RetrievalScope`，不依赖后台线程上的 Servlet 请求上下文。
- 评测结果继续写入 `rag_evaluation_case_results.metrics` JSONB，不增加 schema；现有迁移不改写，不需要数据迁移。
- 文档工作台只读详情与既有 cancel/retry 操作，沿用服务端 ACL、CAS 和授权；前端默认轮询正在运行的 Run，停止后保留最终快照。
- 对外 API 只做增量扩展：新增输入字段均可选，旧响应字段不删除/改名，新增诊断只进入既有 metrics 或新摘要区域。
- PDF 默认预算取保守值并允许环境变量覆盖；不引入 Parser SPI、对象存储或异步导入队列。本批次只修复现有 PDF 路径的内存/时间/产物边界。

## 2. 当前基线（已代码级核对）

### 2.1 评测

- 受管 suite、不可变 version、run、case result 已存在，定义存于 `rag_evaluation_suite_versions.definition`，结果存于 `rag_evaluation_case_results.metrics`；表结构见 `spring-ai-rag-core/src/main/resources/db/migration/V38__add_managed_evaluation_suites.sql`。
- `EvaluationSuiteDefinitionValidator` 当前要求 `cases[].query`、`scope.mode=SELECTED_COLLECTIONS`、`scope.collectionKeys`、`relevant`，variants 复用 `RetrievalConfig`；必须扩展为向后兼容的可选答案字段。
- `EvaluationSuiteService.executeCase` 当前只调用 `EvaluationCaseExecutor.search` 并计算 Hit Rate/MRR/nDCG/Precision/Recall；`EvaluationSuiteService.compare` 已验证同 suite version、variant 集合、embedding profile、代码版本和 corpus snapshot，可在其 delta 中增加 case 级答案字段。
- `ChatExecutionService.execute(ChatCommand)` 已返回答案、sources、usage、traceId、metadata；`ChatExecutionResult.metadata` 含 retrieval、citationValidation、citationRegistry 和上下文组装诊断，可作为“最终输入证据”摘要来源。
- `CitationValidator` 是协议合法性校验，不是事实覆盖率校验；本批次不改变其语义，只在答案评测中组合来源覆盖和事实断言。
- `SemanticEvaluationService` 已提供 FACT_CHECKING/RELEVANCY 的受控 LLM evaluator、超时和批量上限；答案评测复用它作为可选 judge，禁止把 judge 结果当唯一门槛。

近距离参考：

- [质量默认值](../quality-defaults-zh-CN.md)
- [评测实现](../../spring-ai-rag-core/src/main/java/com/springairag/core/evaluation/EvaluationSuiteService.java)
- [Chat 执行结果](../../spring-ai-rag-core/src/main/java/com/springairag/core/chat/ChatExecutionResult.java)
- [Citation 校验](../../spring-ai-rag-core/src/main/java/com/springairag/core/evaluation/CitationValidator.java)

### 2.2 文档处理工作台

- `DocumentProcessingRunController` 已提供 `GET /rag/document-processing-runs/{runId}`、列表筛选、`POST /{runId}/cancel` 和 `POST /{runId}/retry`。
- `DocumentProcessingRunResponse` 已包含 Run 状态、active stage、attempt、retry/dead-letter/error 字段及所有 Stage 快照；`DocumentProcessingRunService`/Repository 已负责 ACL、CAS 和有界动作。
- Documents 页面目前显示 document lifecycle/error，并在动作菜单提供 embedding retry，但没有把 Run/Stage 账本呈现给用户。

近距离参考：

- [Run Controller](../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/DocumentProcessingRunController.java)
- [Run DTO](../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/DocumentProcessingRunResponse.java)
- [Documents 页面](../../spring-ai-rag-webui/src/pages/Documents.tsx)

### 2.3 PDF 导入

- `PdfImportService.importPdf` 在事务方法中调用 `MultipartFile.getBytes()`、写临时 PDF、选择 converter、收集产物后统一 `saveAllAndFlush`；`collectOutputRecords` 对每个产物调用 `Files.readAllBytes`。
- `MarkerPdfConverter` 目前固定最多等待 5 分钟，超时强杀进程但无可配置预算；`PdfBoxConverter` 目前对 PDF 和解析结果整体读入内存。
- Spring multipart 已有上传请求基础限制，但不能约束 PDF 页数、转换产物数和转换输出总量。

近距离参考：

- [PDF 导入](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/PdfImportService.java)
- [Marker 转换器](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/pdf/MarkerPdfConverter.java)
- [PDFBox 转换器](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/pdf/PdfBoxConverter.java)
- [PDF 配置](../../spring-ai-rag-core/src/main/java/com/springairag/core/config/RagPdfProperties.java)

## 3. 对外/持久化契约

### 3.1 版本化答案用例

在既有 `cases[]` 中新增以下可选字段；不出现 `task` 时按旧检索 case 处理：

```json
{
  "id": "refund-grounded-answer",
  "task": "KNOWLEDGE_QA",
  "query": "退款期限是多少？",
  "scope": {"mode": "SELECTED_COLLECTIONS", "collectionKeys": ["policy"]},
  "relevant": [
    {"collectionKey": "policy", "sourceNamespace": "default", "externalId": "refund.md"}
  ],
  "requiredFacts": ["30 天内", "原支付方式"],
  "forbiddenFacts": ["7 天内"],
  "evidenceExpectation": "SUFFICIENT",
  "judge": {"enabled": true, "evaluator": "FACT_CHECKING"}
}
```

规则：

- `task` 只允许 `RETRIEVAL`（默认）或 `KNOWLEDGE_QA`。
- `relevant` 对 `RETRIEVAL` 继续要求非空；`KNOWLEDGE_QA` 在 `evidenceExpectation=SUFFICIENT` 时要求非空，在 `INSUFFICIENT` 场景允许空数组，表示该用例专门验证无证据时的安全拒答。非空来源覆盖按稳定身份 `collectionKey + sourceNamespace + externalId` 判断。
- `requiredFacts`、`forbiddenFacts` 可为空，最多 32 条，每条最多 512 字符；匹配为 Unicode 不区分大小写的规范化包含判断，不声称语义等价。
- `evidenceExpectation` 默认 `SUFFICIENT`；可取 `SUFFICIENT` 或 `INSUFFICIENT`。后者要求答案明确表达未找到/无法从资料确认/证据不足等拒答或不确定语义，并且不得通过“编造必要事实”硬门槛。
- `judge` 可省略或 `enabled:false`；启用时只允许 `FACT_CHECKING` 或 `RELEVANCY`，模型为空走当前默认 ChatClient，不伪造或要求名为 `default` 的模型引用；实现通过 `SemanticEvaluationService` 增加默认 builder 入口完成。judge 输入为 query、授权 sources 的拼接上下文和答案；不把原始上下文持久化到结果。
- 用例若 `task=KNOWLEDGE_QA`，variant 的 `RetrievalConfig` 仍控制 hybrid/rerank/maxResults；模型通过已有运行时默认路由选择，不新增 suite 私有密钥字段。

### 3.2 运行结果

答案 case 的 `metrics` 追加以下脱敏、可比较字段：

```json
{
  "task": "KNOWLEDGE_QA",
  "deterministicPass": true,
  "sourceCoverage": {"expected": 1, "matched": 1, "ratio": 1.0},
  "requiredFacts": {"total": 2, "matched": 2, "missing": []},
  "forbiddenFacts": {"total": 1, "found": []},
  "evidenceExpectation": "SUFFICIENT",
  "citationStatus": "VALID",
  "retrievalExecuted": true,
  "sourceCount": 1,
  "usage": {"promptTokens": 100, "completionTokens": 30, "totalTokens": 130},
  "judge": {"status": "COMPLETED", "pass": true, "score": 0.9},
  "failureReasons": []
}
```

只保存统计、状态、稳定身份和低基数 metadata，不保存答案全文、prompt、chunk 原文或 API key。`retrieved_identities` 继续保存稳定来源身份；答案文本只在内存中参与断言和 judge。

`status` 规则：确定性断言全部通过且 provider 成功为 `PASSED`；来源/事实/证据/引用失败或 provider 失败为 `FAILED`；历史缺失 fixture 仍 `SKIPPED`。judge timeout/disabled/failed 不改变确定性通过结果，但写入 judge 状态和 `judge_*` 诊断。

### 3.3 PDF 资源预算

`rag.pdf` 新增默认启用的配置（名称冻结，环境变量按现有命名规则）：

| 配置 | 默认值 | 边界 |
|---|---:|---|
| `max-upload-bytes` | 20 MiB | 不得超过 multipart 总请求上限 |
| `max-pages` | 500 | `<=0` 拒绝启动 |
| `max-output-files` | 256 | 包含入口 markdown 和图片 |
| `max-output-bytes` | 200 MiB | 聚合产物预算 |
| `conversion-timeout` | 5m | Marker 子进程和 PDFBox 解析总预算 |
| `max-in-memory-bytes` | 32 MiB | PDFBox 读取与单个产物读取上限；超限失败关闭 |

PDF 导入必须先校验 multipart 实际大小，再在 converter 内校验页数/超时，收集产物时以 `Files.size` 累计预算，只有全部通过才读取并写入数据库。任一步失败均删除临时目录并让事务回滚；不改变已存在的导入记录。

## 4. 文件级实施顺序

1. 扩展 evaluation definition record、validator 和 focused tests，确保旧 JSON 全部不变。
2. 新增无副作用 `EvaluationAnswerExecutor`/确定性断言器，复用 `ChatCommandMapper` + `ChatExecutionService` 的 KNOWLEDGE 路径和 `SemanticEvaluationService` judge；将 QA 分支接入 `EvaluationSuiteService`，补 usage/diagnostics/case compare。
3. 扩展 API response 只增加 JSON 字段或复用现有 metrics，不破坏旧客户端；更新 OpenAPI/REST/质量文档。
4. 新增 WebUI processing-runs API 与 modal；在 Documents action menu 增加“处理记录”，展示阶段时间线和 retry/cancel；在 Evaluation Runs 页面展示 QA hard gate/judge/usage 摘要。
5. 扩展 `RagPdfProperties`、`PdfImportService`、`MarkerPdfConverter`、`PdfBoxConverter`；增加超限、超时、cleanup 和边界测试。
6. 增加一键验收脚本，串联 focused backend、managed evaluation integration、PDF resource tests、WebUI tests/build、docs/no-lock/shell checks；发布脚本增加可选 QA suite gate，但不让普通 release 在没有 provider 时误调用模型。
7. 更新中英文长青文档、进度账本；完成实现连续 3/3 检查后提交、push，确认 `main` 工作区干净并停止服务。

## 5. 验收矩阵

| 范围 | 必须验证 | 失败语义 |
|---|---|---|
| Definition | 旧检索 JSON；QA 完整/缺字段/重复 facts/超限/非法 task | 400，明确字段错误 |
| QA deterministic | 来源全中/部分/零；required/forbidden；SUFFICIENT/INSUFFICIENT；citation invalid；provider error | case FAILED，原因可定位，run 聚合失败 |
| QA judge | disabled、completed、timeout、非法响应、无模型 | 只记录辅助状态，不绕过 hard gate |
| QA compare | 同版本不同 variant/run 的 aggregate、case delta、usage/latency、environment drift | 不同 suite/version/variant 拒绝比较 |
| ACL/concurrency | owner ACL、后台 explicit scope、并发 slot、租约丢失 | fail closed，不越权、不重复结果 |
| Workbench | list/detail；stage progress；queued/running/dead-letter；retry/cancel；错误脱敏；轮询停止 | 失败操作不改变错误 Run |
| PDF | upload/page/output/memory/time budgets；Marker kill；PDFBox limit；临时目录清理；事务回滚 | 4xx/明确错误，不留半成品 |
| Regression | `mvn clean compile test-compile`、相关 PG/HTTP 集成、WebUI typecheck/test/build、`scripts/dev.sh` readiness | 任一必需门禁失败不得交付 |
| Real provider | 仅在 `.env` 可用时执行有限 QA smoke；记录模型/耗时/usage，不记录密钥 | 外部环境缺失明确为 N/A，不冒充通过 |

## 6. 风险、回滚与非目标

- LLM 答案非确定：hard gate 只用低歧义包含断言、来源身份、citation 状态和证据不足声明；judge 不作唯一判定。
- 评测成本：主动 Run 才调用 Chat；并发、case、variant 和 judge 数量沿用现有上限；不把 QA 自动挂到每次 Chat。
- 后台授权：显式传入 owner principal 和 scope；不得依赖 `RequestContextHolder`。失去 owner ACL 时整 run fail closed。
- PDF 内存/长任务：预算在读取和转换前后双重校验；默认值可通过环境变量调低/调高，关闭预算不是正常回滚路径。
- 回滚：代码回滚不会破坏旧 suite JSON；新增字段可被旧版本忽略。PDF 新配置缺省由配置类提供，旧环境无需迁移。
- 非目标：不实现通用 Parser SPI、资源句柄、MCP/CLI；不实现账单 hard limit；不改变现有 Search 排序和 Chat 响应主字段；不把原文全文落到评测账本。

## 7. 规划检查日志

检查计数器最终值：`3/3`。三轮检查均未发现实质问题；期间曾修正一次文档入口和两处契约边界，修正后重新从 0 开始计数。

### 检查 1/3（重置后）

- 时间：2026-10-15 09:58 CST。
- 范围：评测定义兼容性、答案级字段、后台授权、PDF 转换边界、前端现有入口。
- 发现/处理：无实质问题；确认旧 case 默认保持检索语义，QA 仅由新 task 进入。
- 结果：通过，计数 `1/3`。

### 检查 2/3

- 时间：2026-10-15 09:58 CST。
- 范围：首次接手实现者视角复核字段约束、失败语义、测试落点、usage 来源、Run/Stage DTO 和 WebUI Dialog 模式。
- 发现/处理：无实质问题。
- 结果：通过，计数 `2/3`。

### 检查 3/3

- 时间：2026-10-15 09:58 CST。
- 范围：复核工作区基线、迁移边界、检索上下文/citation registry、文档处理状态树、发布脚本与质量门禁。
- 发现/处理：无实质问题。
- 结果：通过，计数 `3/3`，进入实施。
