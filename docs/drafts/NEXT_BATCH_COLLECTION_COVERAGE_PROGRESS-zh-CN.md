# 下一批实施进度：有界 Collection 覆盖召回

> [English](NEXT_BATCH_COLLECTION_COVERAGE_PROGRESS.md) | [中文](NEXT_BATCH_COLLECTION_COVERAGE_PROGRESS-zh-CN.md)
>
> 对应规划：[中文](NEXT_BATCH_COLLECTION_COVERAGE_PLAN-zh-CN.md) · [English](NEXT_BATCH_COLLECTION_COVERAGE_PLAN.md)

## 当前状态

- 分支：`feat/collection-coverage`
- 基线：`b473b002`（已推送的 `main`）
- 工作区：本批次实现、双语文档与验收进度均已完成本地验证，尚未提交
- 规划检查：最新异步 embedding lifecycle 验收修订已于 2026-10-17 03:24–03:26 连续三轮无修改复核通过（`3/3`）；检查范围及结果见下文。
- 实施检查：`3/3`；2026-10-17 05:27–05:36 连续三轮只读复核无修改，范围包含检索/排序边界、API/幂等兼容、授权/恢复/资源限制及验收工具。
- 当前阶段：规划与实现复核、外部 Client API、数据库、真实模型、SDK、启动及补充 WebUI 验收均完成；仅剩本地提交、同步最新 `origin/main`、按需合并复验、合入并推送 `main`，然后归档本规划和进度。

## 最新规划复核记录

2026-10-17 03:06 的真实 API 验收发现，OpenAI 兼容控制器由
`RAG_OPENAI_COMPATIBILITY_ENABLED` 条件启用且默认关闭，公开模型别名为 `rag-default`；原隔离运行
overlay/脚本说明没有明确设置这两项，导致外部 Client 探针落到 404 静态资源处理路径。该验收可复现性
缺口已修订双语规划：仅在 disposable overlay 临时启用路由，并令 API compatibility probe 使用
`rag-default`；生产默认不变。规划复核计数重置为 `0/3`。后续复核还发现双语规划页眉及英文进度
Phase 保留旧 `3/3` 状态，与最新计数冲突；已修正这些状态行，并从 `0/3` 重新执行以下三轮：

| 时间（CST） | 检查范围 | 发现与处理 | 结果 |
|---|---|---|---|
| 03:12 | 首次阅读者视角、目标/非目标、完成定义、规划/进度状态链 | 无问题；未修改文档 | `1/3` |
| 03:12–03:13 | OpenAI 条件配置、模型 alias、`scripts/dev.sh` 环境导出、外部 Client 探针参数、临时 DB 安全边界 | 配置与代码一致；无问题、未修改文档 | `2/3` |
| 03:13 | 双语链接/状态、交付边界、文档完整性及空白检查 | `verify-project-docs.sh` 16 项通过；无问题、未修改文档 | `3/3` |

三轮之间未修改规划正文；三轮完成后才同步规划页眉和本进度记录。

上述 `03:12–03:13` 的 `3/3` 只适用于当时规划版本。2026-10-17 03:19 的真实回归发现，JSON record
upsert 的 `SYNC` 作业若先被后台 worker 抢占，可以合法返回 `INDEXING`，而 runner 立即要求 READY，
存在竞态误失败。双语规划已要求通过现有 document detail API 有界轮询至 lifecycle 可搜索，终态失败/
超时仍严格失败；规划复核再次重置为 `0/3`，此次修订后尚无干净轮次。
随后全库状态检索又发现活跃草稿索引与中英文 TODO 仍标记旧 `3/3`，现已同步改为当前 `0/3`；该
状态传播错误使本轮复核重置，以上修订后仍须重新从第 1 轮开始。

状态入口同步修正后，最新规划连续完成三轮互不重叠的只读复核，期间无修改：

| 时间（CST） | 检查范围 | 发现与处理 | 结果 |
|---|---|---|---|
| 03:24 | 计划/进度/TODO/草稿索引当前状态、首次阅读者路径、完成定义 | 所有活跃入口一致为 `0/3`；无新问题、未修改 | `1/3` |
| 03:25 | REST lifecycle 契约、SYNC worker 竞态、detail endpoint/status 字段、60 秒/500ms 界限 | 与实现及正式 REST 文档一致；无新问题、未修改 | `2/3` |
| 03:25–03:26 | 双语状态/链接、交付边界、全库活跃状态引用及文档门禁 | TODO/索引当前状态一致；`verify-project-docs.sh` 16 项通过；未修改 | `3/3` |

三轮期间规划正文与状态文档均未修改；状态同步在第三轮完成后进行。

本轮在 2026-10-17 01:14 Native Chat 错误优先级/无副作用验收修订后重新计数。以下三轮互不重叠，
均为只读检查；轮间未修改规划正文，计数连续达到 `3/3`：

| 时间 | 检查范围 | 发现与处理 | 结果 |
|---|---|---|---|
| 01:17:04 | 首次阅读者视角、目标与非目标、API 范围、状态/双语入口 | 无问题；未修改 | `1/3` |
| 01:17:33 | Native/OpenAI DTO 与 mapper、授权和 fan-out 资源边界、Chat 幂等及响应兼容 | 无问题；代码事实与规划一致，未修改 | `2/3` |
| 01:18:26 | 验收矩阵、真实依赖/前端门禁、回滚恢复、Git 交付顺序 | 无问题；门槛闭环，未修改 | `3/3` |

上述 01:14–01:18 的 `3/3` 只针对当时版本；之后发现的实质性问题已重置计数并完成修订。最新连续复核如下：

| 时间（CST） | 检查范围 | 发现与处理 | 结果 |
|---|---|---|---|
| 02:23 | 目标/非目标、首次阅读者理解、默认 GLOBAL、Native/OpenAI 增量契约与外部 Client 验收 | 复核开始前发现规划状态行与后续重置记录冲突，已修正中英文状态行并将计数归零；本轮未再发现问题，未修改规划正文 | `1/3` |
| 02:25 | DTO/mapper、授权顺序、Collection 与 limit 边界、并发预算、Chat 快照及历史幂等指纹兼容 | 规划约束与现有代码路径一致；历史 GLOBAL 指纹缺陷在规划中已有明确修复和回归要求；未修改规划正文 | `2/3` |
| 02:25–02:27 | 验收矩阵、外部 API 黑盒优先级、真实运行补充边界、双语链接、回滚与 Git 交付 | `verify-project-docs.sh` 全部 16 项通过；无遗漏或矛盾，未修改规划正文 | `3/3` |

本次三轮期间没有修改规划正文；状态行/进度记录在三轮完成后一次性同步。之后若发现新的实质性
规划缺陷，仍须先修复规划并重新完成连续 `3/3` 检查。

## 实施检查点

- 总体批次进度：约 `95%`（功能实现和验收已完成，剩余 Git 交付）。所有对外 API 相关门槛独立于 WebUI 验收执行；WebUI 仅作为补充证据，不能替代外部 Client 黑盒与真实 SDK 测试。
- Native Chat `/ask`、`/chat` 共用非流式入口，`/stream` 独立入口，均在幂等 `prepareTurn()` 之前
  拒绝 `PLAIN + EACH_COLLECTION`，经既有 `GlobalExceptionHandler` 返回 `400 BAD_REQUEST`；PLAIN
  缺省/GLOBAL 与 KNOWLEDGE EACH 仍按原路径执行。
- 新增 `RagChatControllerCoverageValidationWebTest`：真实 `/api/v1` 路由覆盖 ask/chat/stream 错误、
  SSE Accept 下 HTTP problem+json、错误字段、无幂等服务交互/无模型调用，并覆盖 PLAIN 缺省/GLOBAL
  和 KNOWLEDGE EACH 正向路径。`mvn -pl spring-ai-rag-core -am -Dtest=RagChatControllerCoverageValidationWebTest -Dsurefire.failIfNoSpecifiedTests=false test`：6 项通过。
- 最终发布门禁于 2026-10-17 05:26 完成：`./scripts/verify-release.sh --skip-docker --skip-helm --skip-npm-ci` 为 `18 passed, 0 failed, 13 skipped`；全 reactor `mvn clean test`、WebUI lint/TypeScript、1,030 项 Vitest、生产构建、525 项设计系统测试及 93 项 Mock Playwright 均通过。Docker、Helm、npm ci 及若干可选运行时 gate 在该命令中按参数跳过；本批相关外部 API、覆盖召回和 SDK 项另有下列独立真实验收，不能把 13 个 skip 误报为通过。
- 外部 Client API 黑盒在隔离真实服务上通过，证据位于 `.verification/api-contract/collection-coverage-live-20261017-050812/`；覆盖召回 HTTP 回归 `.verification/collection-coverage/20261017-050321/summary.md` 中 GLOBAL 与 EACH_COLLECTION 均达到 hit rate、MRR、Recall@K、nDCG `1.0000`，并校验返回来源与 coverage 摘要。
- 官方 Python/Node OpenAI SDK 真实 HTTP 验收均通过（模型列表、JSON、SSE、幂等、错误封套、认证）：`.verification/openai-sdk-e2e/collection-coverage-real-fixed-20261017-051508/summary.md`。此前独立完成的 Client Consumer 实时契约、真实模型 Search/Chat/Agent/SSE，以及隔离端口/数据库 `scripts/dev.sh` 启停验收仍是补充证据；不得用 WebUI 结果替代这些 API 证据。
- PostgreSQL/Testcontainers 的 26 个门控套件、`scripts/dev.sh` 隔离启动和临时数据库清理均已完成；最终 Playwright 93/93 仅作为 UI 补充。最终门禁摘要：`target/release-verification/collection-coverage-final-20261017/summary.md`。
- Native/OpenAI 的可选字段、授权范围、GLOBAL 旧指纹与旧 Java 构造器兼容，以及错误封套已由契约/集成测试和真实 Client 检查；无需数据迁移，未修改既有 V1–V60 Flyway 脚本。

## 最新实现复核记录

2026-10-17 连续完成三轮互不重叠、只读且期间未修改文件的复核：

| 时间（CST） | 检查范围 | 发现与处理 | 结果 |
|---|---|---|---|
| 05:27–05:31 | 覆盖候选合并/排序、跨集合去重、完整重排校验、每分支/每请求/每集合硬上限及 PostgreSQL 回归 | 无实质问题；实现与边界测试一致，未修改 | `1/3` |
| 05:31–05:34 | Native/OpenAI 请求与响应增量契约、SSE/错误路径、GLOBAL 历史幂等指纹、旧 Java 构造签名 | 无实质问题；legacy GLOBAL 指纹由固定 SHA-256 测试锁定，未修改 | `2/3` |
| 05:34–05:36 | API Key 授权、快照恢复、资源池/超时退化、coverage 隐私摘要、真实 HTTP 回归脚本及双语文档 | 无实质问题；无 schema 迁移需求，未修改 | `3/3` |

三轮期间没有代码、测试或规划正文修改。旧复核发现和修复过程保留在下方历史记录中。

## 最新实现复核发现

2026-10-17 02:07:50 的首次只读实现复核发现，`ChatRequestFingerprint` 为所有 KNOWLEDGE 请求都新增
`coverageMode=GLOBAL` 字段，而 `ChatTurnOperationService` 以 SHA-256 精确比较已保存 fingerprint。
因此升级前仍处于保留期内、且内容完全相同的成功幂等 operation 会在新版本被误判为 key reuse，返回
409 而非 replay。已同步修订中英文规划：GLOBAL 必须保留旧 canonical JSON/hash，仅 EACH 写入新字段；
要求以旧 hash 预置 operation 验证 replay，并验证 EACH/GLOBAL 隔离。规划复核和实现复核均重置为 `0`；
此问题修复与验收前不得继续交付。此前已通过的测试是修复前证据，最终门禁必须修复后重跑。

2026-10-17 02:12:20 的规划第 3 轮验收/交付复核发现，正式 `delivery-workflow-zh-CN.md` 要求共享 API
契约变更在独立端口和临时数据库上完成真实前后端 Playwright，而规划只列了 Mock Playwright 与
`scripts/dev.sh` 启停。现已把仓库可执行的 `e2e/api-key-real.spec.ts` 命令和“不替代外部 Client API
验收”的边界同步写入中英文完成定义、Slice D 与验收矩阵。该遗漏触发规划计数重置为 `0/3`；两项
规划修改完成后重新执行三轮无修改检查。

随后核对 `spring-ai-rag-webui/e2e/api-key-real.spec.ts` 的 `finally` 清理逻辑发现，该用例删除
最终 credential，但不删除 principal 行。中英文规划已更正为验证 credential 删除和 secret 不进入
URL/console/browser storage，并在运行后销毁整个 disposable 数据库；不要求用例逐行清 principal，
也绝不连接运维数据库。该验收预期修订同属规划复核中的实质准确性修复，规划计数继续保持 `0/3`。

## 规划复核重启原因

2026-10-17 01:14 的第二轮代码路径复核发现，`prepareTurn()` 会在 replay/claim 之前读取幂等记录，
若测试只验证“无 replay/claim/写入”，仍可能让非法 PLAIN 请求先执行幂等预查询或返回幂等键冲突，
从而使 400 优先级契约未被锁定。已在中英文契约、Slice A 与验收矩阵明确要求拒绝发生于幂等
prepare/lookup 前，并用真实 `GlobalExceptionHandler` 断言 problem+json `error/status/path`、
MockMvc 验证 prepare/inspectExisting/claim 与模型均未调用且无 operation 写入。该外部 API 错误
优先级缺口使规划检查重置为 `0/3`；修订后尚无干净轮次。

2026-10-17 01:12 的第三轮验收核对发现，前端回归虽然已纳入计划，但只称“核心 Mock Playwright
spec”，没有写仓库中可直接执行的具体命令。经核对 `spring-ai-rag-webui/package.json` 和
`playwright.preview.config.ts`，已将中英文验收固定为 `npx tsc -b --pretty false`、
`npm run test:run`、`npm run build`、`npm run test:e2e:mock`；最后一项使用 production preview
并排除 `-real` spec。实施者可直接恢复和执行，无需自行挑选测试。此实施可复现性缺口使规划检查
计数重置为 `0/3`；修订后尚无干净轮次。

2026-10-17 01:07 的第三轮交付门槛复核发现，本批新增共享 Native/OpenAI HTTP API 字段和响应扩展，
项目交付规范明确要求共享 API 变更即使未编辑 WebUI 文件也必须做 TypeScript、相关 Vitest、生产构建
和核心 Mock Playwright 验证。原规划把 WebUI 标为非受影响项，且未列前端验收，违反该门槛。现已
将这些前端检查作为补充兼容证据加入非目标说明、完成定义、Slice D 和验收矩阵，并明确其不能替代
Client API 黑盒/HTTP 集成测试。该实质验收缺口使规划计数重置为 `0/3`；修订后尚无干净轮次。

2026-10-17 01:03 的首次阅读者复核发现，`docs/drafts/README-zh-CN.md` 与英文草稿索引仍只把
当前 `0/3` 解释为双语结构修正，没有指向后续 Native Chat 契约、Slice A 和 SSE HTTP 错误验收
修订。已同步中英文索引描述，避免接手者误把旧重启原因为最新原因。索引状态内容修正后，规划检查
仍为 `0/3`，且尚无干净轮次。

2026-10-17 01:01 的双语核对发现，English Slice A 已明确要求 MockMvc 注册真实
`GlobalExceptionHandler` 并用 SSE `Accept` 断言 HTTP 错误响应，但中文 Slice A 只写了泛化的
MockMvc 验证。已补齐中文步骤以保持实施指引等价；该处属于可能使两种语言实施证据不同的实质差异，
故规划复核继续为 `0/3`，修正后尚无干净轮次。

2026-10-17 01:00 的第二轮技术验收复核发现，规划虽然要求 `/stream` 的非法组合返回 HTTP 400，
但未要求 MockMvc 注册项目的 `GlobalExceptionHandler`，也未明确用 `Accept: text/event-stream`
验证错误仍是既有 HTTP 错误响应，而不是 SSE 帧、未处理异常或不同错误 envelope。已在中英文规划的
Slice A 和验收矩阵补充真实异常处理器及 SSE `Accept` 断言，同时保留模型/operation 无调用断言。
这是外部 Client API 错误契约的重要验收细节，规划复核计数重置为 `0/3`；修订后尚无干净轮次。

2026-10-17 00:57 的第二轮交叉检查发现，规划虽已写入 Native Chat `PLAIN + EACH_COLLECTION`
的拒绝契约与 MockMvc 验收矩阵，但 Slice A 的文件级实施顺序仍未明确要求在 `/ask`、`/chat`、
`/stream` 的幂等 prepare/replay/claim 前执行该校验。已同步补入中英文实施步骤及“不得调用模型、
不得创建/认领 operation”的断言。这会让实现者漏做刚冻结的契约，属于实质执行闭环问题，故规划复核
计数重置为 `0/3`；最新修订后尚无干净轮次。

2026-10-17 00:47 的第三轮规划复核发现，Native Chat 的 `resolveScope()` 在 `ChatMode.PLAIN` 时
提前返回 unscoped scope，因此会静默忽略新增的 `EACH_COLLECTION` 请求参数；OpenAI 兼容入口虽已
规划为对 PLAIN 显式报错，Native Chat 却没有对应契约。已将 `/ask`、`/chat`、`/stream` 的
HTTP 400 `BAD_REQUEST` 校验前置时点、旧 PLAIN 行为保持规则及 API 回归断言补入中英文规划。
这是兼容性/外部 API 契约缺口，故规划复核计数重置为 `0/3`，修正后尚无干净轮次。

2026-10-17 00:44 的首次阅读者状态核对发现，规划页、正式 TODO 与草稿索引仍把此前 `3/3`
写成当前状态，与本进度页 `0/3` 相矛盾。现已同步标注此前 `3/3` 为历史记录、当前复核为 `0/3`；
这是规划状态实质不一致，修正后尚未开始新的干净轮次。

2026-10-16 21:04 的代码交叉核验发现，`CompositeChatDocumentRetriever` 会把独立的
`STATIC_KNOWLEDGE` 结果与 Collection 检索结果合并，而覆盖摘要原先将 `returnedCount` 描述为
“最终用户可见列表长度”，又要求各 selected Collection 计数之和等于该值。非 Collection 静态
知识没有授权 selected ID，二者无法同时成立。已修订中英文规划：`returnedCount` 和
`perCollectionResultCounts` 只统计 rerank 后最终 Collection-scoped 检索列表，排除静态/其他非
Collection 上下文；明确 Chat 仍可把这些上下文提供给模型，并补充对应验收断言。该实质修订使规划
检查计数重置为 `0/3`；之前报告的 `1/3` 不计入新一轮连续检查。

2026-10-16 21:06 的首次阅读者审查又发现，决策摘要把小 Collection 被 global top-k
“系统性淹没”写成当前事实，与同一规划中“没有线上质量证据证明它必须成为默认”的说明矛盾，
也超出现有代码和指标能证明的范围。已将中英文摘要修正为条件性算法效果，并明确没有生产或
goldenset 证据证明该现象已在当前部署系统性发生。规划检查从 `0/3` 重新开始，尚无新的干净轮次。

2026-10-16 21:11 的 Client 契约复核发现，`CorsConfig` 只配置允许的来源、方法和请求头，未设置
`Access-Control-Expose-Headers`。因此即使浏览器来源获准，浏览器代码仍读不到新增的
`X-RAG-Collection-Coverage`。中英文规划现要求在现有 `/api/**` 与 `/v1/**` CORS 映射中暴露该响应头，
不改变允许来源/请求头策略或默认关闭 CORS 的行为，并增加 `CorsConfigTest` 与配置文档验收。该实质
契约缺口使规划维持 `0/3`；本次修订后尚无新的干净轮次。

2026-10-16 21:16 的首次阅读者复核发现，规划把 Client 契约、安全预算、数据治理、恢复验收和
SDK 接入概括成“已经完成”，但同目录活跃规划索引仍标注相关交付在实施/待 Git 交付，形成
不必要且未经本批审计证明的完成状态依赖。已删除该完成性断言，改为以当前代码中已核实的
GLOBAL 检索与授权范围为依据，并明确不假设其他活跃批次已全部交付。双语规划已修订，计数维持
`0/3`；尚无新的干净轮次。

初始实现审计发现：`ReRankingService.rerank(..., finalLimit)` 会先把 provider 结果截断到
最终 limit，随后再调用 `retainCoverage` 无法恢复已被截掉的 Collection。修正规划为：EACH
路径保留有界候选池并以其大小作为单次 rerank ranking depth，provider 返回候选池排序后再选
最终 limit 并保覆盖；provider 异常、返回条数少于输入候选池或遗漏有命中的 Collection 时，
对原始候选池执行相同的覆盖选择。EACH 的
`limit/maxResults` 另限 100（GET Search 的 GLOBAL 旧 1000 上限不变），以控制 fan-out 成本。
技术复核还要求 fan-out 共用不超过现有 retrieval timeout 的 deadline，已排队任务逾期时跳过
SQL；并在 rerank 后重算 trace 中最终可见结果的 Collection 计数，避免诊断过期。GLOBAL 路径
不变。第 2 轮交叉核对确认 `KnowledgeSearchTool` 有独立检索/rerank 路径；OpenAI 请求没有
maxResults 字段，故 EACH 时 effective limit 自动提升到 `max(OpenAI 默认值, selectedCount)`，
最大 selectedCount 为 20；不新增请求字段且 GLOBAL 默认不变。
另将 rerank “部分返回”的判定冻结为：异常、返回条数小于输入候选池、或遗漏任一有命中的
Collection，任一情况都回退原候选池。OpenAI 的 EACH 还仅限 `rag.mode=KNOWLEDGE`；PLAIN
组合应在 `rag.scope.coverage_mode` 返回 `unsupported_parameter`，不能静默忽略。以上影响正确性
和外部 API 验收。第 2 轮又确认 `KnowledgeSearchTool` 的模型可见 `maxResults` 可小于 selected
Collection 数；方案冻结为在 Chat caller 上限内向上钳制到 selected 数量，测试该边界。规划检查
计数保持 `0/3`，修订后从第 1 轮重审。

2026-10-16 21:19 的 API/成本复核发现，规划虽使用“rerank 已启用”决定是否扩展每个
Collection 的候选池，却没有冻结启用判定。现有代码要求请求 `useRerank=true`、服务端
`rag.rerank.enabled=true`，且 provider 不是 `none`/`noop`/`off`，才扩展候选检索。否则在服务端
禁用 rerank 或使用 no-op provider 时，仍扩大 EACH 检索会产生无用的候选成本。中英文规划现与
`candidateRetrievalLimit` 一致：EACH 覆盖仍生效，但 rerank 未实际启用时只取 `finalLimit` 候选且
不调用 provider。该实质修订将规划检查重置为 `0/3`。

随后对已有部分实现的审计发现，`ReRankingService.rerank(..., finalLimit)` 可能在覆盖选择前就将
provider 输出截到最终 limit，无法恢复被截掉的 Collection。规划因此冻结：EACH 必须保留有界候选池，
以候选池深度调用一次 rerank，再选最终 limit 并保留各有命中 Collection；provider 异常、短返回或
漏掉有命中的 Collection 时，改用原始候选池执行相同覆盖选择。EACH limit 上限 100，GLOBAL 的 GET
旧上限保持不变。计划也纳入 `KnowledgeSearchTool` 独立 rerank 路径、OpenAI 无 maxResults 时的有效
limit、PLAIN 模式拒绝、Chat 工具 limit 向上钳制和 rerank 后重算最终覆盖计数。上述已冻结在规划，
当前仍无新的干净复核轮次。

最近一次并发交叉核对又发现两个实质缺口：当前 `HybridRetrieverService` 同步调用
`embeddingModel.embed`，因此既不能由 retrieval deadline 及时返回，也会阻塞全文分支；并且
`PerformanceConfig` 的 `ragSearchExecutor` 使用 `Executors.newFixedThreadPool(4)`，其队列无界，
仅声明“单请求最多 41 个任务”不能限制并发请求造成的队列增长。规划现已改为：embedding 使用
4 线程、32 队列槽、饱和立即拒绝的隔离执行器；EACH 分支使用非阻塞进程级许可，排队及运行总量
最多 80；所有调用共享绝对 deadline，过期排队分支不执行 SQL，容量拒绝和超时进入诊断。同步的
底层 provider/SQL 调用不能被 `CompletableFuture` 强制取消，许可只在实际任务结束后释放；这项
限制和底层模型客户端超时已写入规划。中英文规划需完成同步后，才从 `0/3` 重新开始只读复核。

需求/客户端契约复核又发现：容量/分支失败允许部分返回，但原方案把完整摘要只放进内部 trace；
Search 的 POST body 是裸列表，客户端无法可靠判断缺失是“无命中”还是“分支未完成”。已通过增量
契约修正：仅 EACH 的 Native GET/POST Search 增加 `X-RAG-Collection-Coverage` 紧凑 JSON header，
Chat 使用既有 metadata，OpenAI 使用既有 `rag.collectionCoverage`；增加 `incompleteCollectionIds`、
`degraded` 和固定原因码，GLOBAL 旧请求无新增字段/header，Search body 与 SSE event 不变。此修订
属于实质发现，规划检查从 `0/3` 开始，尚未计入任何干净轮次。

检查双语一致性时发现英文规划缺少与中文对应的目标、完成定义、实现语义和验收矩阵，无法让
只读英文版的接手者独立继续。已补齐结构和关键事实；这是规划文档修订，当前规划检查计数仍为
`0/3`，需要从第 1 轮重新开始。

随后检查响应摘要契约发现：字段示例未明确 requested/missing/incomplete/degraded 的计算关系，
且“固定原因码”没有列出允许值与顺序。已冻结字段语义、7 个固定脱敏原因码及稳定顺序，区分
完整无命中与分支不完整、以及 rerank 回退；此项影响外部 API 契约，计数仍为 `0/3`。

需求基线核对还发现：现有正式 TODO 说明普通 global top-k 并集检索是正确语义、尚无实证要求把
覆盖设为默认；规划原先称其为“当前最明显质量缺口”，属于未经代码/指标支持的过度判断。已修正为
显式 opt-in 的增量质量策略，并明确不声称当前行为有缺陷。另发现草稿索引写“生产代码尚未修改”，
与工作区已有未提交 WIP 冲突；中英文索引现改为准确记录部分实现已存在、规划 `3/3` 前暂停新增生产
改动。两项修订后规划计数保持 `0/3`。

再次对照 TODO 与现有评测发现，TODO 要求延迟、候选数、覆盖率和质量 goldenset；原验收矩阵仅有
功能集成和真实 smoke，缺少 GLOBAL/EACH 同夹具对照。现已规划版本化多 Collection goldenset，
确定性覆盖/来源/ACL/资源断言作为硬门槛，并记录 MRR、Recall@K、nDCG、候选数、p95；不要求 EACH
因策略不同而必然胜过 GLOBAL，也不以单一 LLM judge 判定通过。规划检查计数仍为 `0/3`。

2026-10-16 22:04 的首次阅读者复核发现，“引用/证据组装只看最终列表”表述可能被接手者理解为
静态知识也不再参与 Chat 引用，与规划中静态知识仍进入 prompt、只排除在覆盖计数之外的决定不够明确。
代码核实 `CompositeChatDocumentRetriever` 会独立合并静态文档，`StaticKnowledgeDocumentRetriever` 会记录
其来源，Chat 则从组装后的文档上下文提取来源。中英文规划现明确：Collection 引用只使用最终覆盖选择
列表（不使用 rerank 前候选），非 Collection 上下文仍依既有流程参与引用/证据，且不计入覆盖计数。
该实质澄清使规划复核计数重置为 `0/3`；修订后没有可计入的干净轮次。

2026-10-16 22:08 的技术/成本复核发现，合并候选池上限 100 与分支任务上限并未明确限制融合前
各查询分支读取的原始行数。当前 EACH 实现对 vector/full-text 每个分支使用 `2 * retrievalLimit`；
若 retrieval limit 为 100、selected Collection 为 20 且各有两个分支，每请求融合前最多会读取
8,000 行。中英文规划现冻结沿用现有 2 倍 over-fetch、每分支最多 200 行、每请求融合前最多
8,000 行，并保留合并候选池/最终结果最多 100 项；验收矩阵新增对应硬断言。该成本边界修订使规划
复核重置为 `0/3`，修订后尚无干净轮次。

2026-10-16 22:24 的交付范围复核发现，正式中英文 TODO 把“WebUI 高级选项”列为未来实施硬约束，
但本批明确以外部 HTTP Client/API 为重点并排除 WebUI 改造。为服从项目服务形态优先级且不让 UI
替代外部契约验收，双语 TODO 和规划现明确：有界覆盖进入当前实施批次；发布前旧 API 语义不变；
WebUI 高级选项可后续单独做，不是本批硬门槛；外部 API/Client 验收仍必须通过。此范围/文档冲突
属于实质问题，规划检查重置为 `0/3`，修订后尚无干净轮次。

2026-10-16 22:26 的首次阅读者核对还发现，中文 TODO 状态误写成“规划已通过初步审查”，与当前
规划计数 `0/3` 冲突；英文状态也未准确表达已有部分实现且暂停扩展。现已将双语 TODO 同步为：
规划复核 `0/3`、现存部分 WIP、完成规划复核前不扩展生产实现、验收发布前外部 API 契约不变。
此状态信息修正与前述范围修订一并纳入当前 `0/3`，修订后尚无干净轮次。

2026-10-16 22:09 的双语验收矩阵核对发现，英文规划将每分支 200 行、每请求融合前 8,000 行、
合并/最终 100 项列为明确硬断言，而中文验收行仍只写“有界”，不能保证实施者在两种语言下
执行同一门槛。中文矩阵现已补齐完全相同的数值上限；此契约验收遗漏属于实质问题，规划复核重置为
`0/3`，修订后尚无干净轮次。

2026-10-16 22:17 的并发可行性复核发现，若 vector 任务在 `ragSearchExecutor` 内等待异步 embedding，
四个慢 embedding wait 可能占满检索 worker，令本可独立运行的全文分支无法推进。中英文规划现冻结
安全调度顺序：先独立提交全文分支；仅由请求协调线程在共享 deadline 内等待 embedding；成功后再提交
vector 分支；embedding 失败时全文仍继续。验收矩阵增加 latch 测试，证明 embedding 卡住时全文仍能
完成。该并发语义修订使规划复核重置为 `0/3`，修订后尚无干净轮次。

## 已冻结决策

- 本批只做有界 `EACH_COLLECTION` 覆盖召回；不做 OAuth/OIDC、租户层级、billing 结算或 WebUI 重构。
- `GLOBAL` 保持既有默认行为；显式 `EACH_COLLECTION` 无需运维开关即可使用。
- 只允许显式 `SELECTED_COLLECTIONS`，最多 20 个；`limit/maxResults` 不足以覆盖 selected 数量时 400。
- EACH 专属 `limit/maxResults` 上限为 100；GET Search 的 GLOBAL 旧 1000 上限不变，以限制显式 fan-out 的数据库资源消耗。
- Query embedding 每请求一次，使用隔离有界执行器；EACH 排队/运行分支总量最多 80；无命中 Collection 不填充伪结果。
- 旧端点、字段、构造器、SSE、错误语义和幂等行为保持兼容；OpenAI RAG 扩展新增字段但保留原四参数构造器。

## 规划检查记录

以下三轮是 2026-10-16 完成的上一轮规划复核，作为历史追溯保留。2026-10-17 文档完整性门禁
发现双语规划标题层级不一致，英文规划已按中文章节树重排；该结构性问题使当前规划复核重置为
`0/3`。修订后必须重新完成连续三轮只读复核；无问题轮次总结保留在会话记录，不另写入本文。

| 轮次 | 时间 | 检查范围 | 发现/处理 | 结果 |
|---|---|---|---|---|
| 第 1 轮 | 2026-10-16 | 需求闭环、自包含性、默认值、非目标、恢复入口和代码锚点 | 无问题、无修改 | `1/3` |
| 第 2 轮 | 2026-10-16 | API/DTO、授权 scope、OpenAI adapter、并发 executor、结果映射和源码兼容 | 无问题、无修改 | `2/3` |
| 第 3 轮 | 2026-10-16 | 实施顺序、测试矩阵、门禁、双语文档、回滚和 Git 交付 | 无问题、无修改 | `3/3` |

## 实施切片

| Slice | 内容 | 状态 |
|---|---|---|
| A | API DTO、授权 scope、OpenAI scope 映射 | 已有部分实现，待复核 |
| B | 一次 embedding、bounded fan-out、稳定融合和诊断 | 已有部分实现；重排覆盖闭环待补 |
| C | Chat fingerprint/snapshot、JSON/SSE/replay | 已有部分实现，待专项测试 |
| D | PostgreSQL/HTTP/SDK/启动/真实模型/文档/Git 验收 | 未开始 |

## 恢复入口

1. `git status --short --branch`
2. 阅读规划 §4–§9 和本文件最新 checkpoint。
3. 不使用 `git reset --hard`、`git stash` 或 `git clean`。
4. 若规划检查发现实质问题，先修正文档并将规划计数重置为 `0/3`，不得修改生产代码。
