# Client API 真实端到端、证据提交与验收可靠性改进规划

> 中文规划。实施与本规划同步记录在
> [进度账本](NEXT_BATCH_CLIENT_API_E2E_EVIDENCE_PROGRESS.md)。

## 1. 状态与基线

- 状态：规划已完成 `3/3`，实施与最终验收进行中。
- 规划日期：2026-10-17。
- 分支与基线：`main`，`6f9d1f3e`，开始时工作区干净且与 `origin/main` 同步。
- 实施前最高 Flyway 迁移为 V64；本批因未归属文档并发提交栅栏新增 V65，升级由 Flyway 自动执行。
- 成本边界：真实验收是显式运行的人工/发布门禁，不加入普通 Chat 请求。固定真实矩阵最多发出 15 次 Chat completion、18 次 Embedding provider 请求：Chat = provider preflight 1 + Native JSON/SSE 2 + AGENT（1 个 tool round、至多 2 次 completion）2 + Python/Node SDK 各 JSON/SSE 4 + 3 个 QA case 连跑两次供 compare 使用 6；Embedding = provider preflight 1 + 最多 3 个单 chunk 测试文档 3 + Native `/search` 1 + Native JSON/SSE/AGENT 查询 3 + Python/Node SDK 各 JSON/SSE 4 + 3 个 QA case 连跑两次 6。隔离服务将 Chat 与 Embedding 各自配置为一个 primary、空 fallback，`rag.chat.execution.max-candidate-attempts=1`；显式关闭 `rag.query-rewrite.llm-enabled`、将 `rag.chat.knowledge.query-transformer` 设为 `none`（保留的规则改写不调用模型）与关闭 Chat retry、把 embedding retry attempts 设为 1、把 SDK `max_retries` 设为 0、只选一个 evaluation variant、关闭 judge，并限制 AGENT 为一个 retrieval tool call/一轮。因此每个逻辑调用至多对应一次 provider 请求，不会故障切换到额外模型产生未计费调用。统一 runner 在执行前验证矩阵预算、在每次调用前预留额度并计数，达到上限即停止；任何实际调用数与预估不符都失败并报告，不自动重试。Mock、PostgreSQL 与 SDK 协议测试不调用模型。
- 环境注意：此前验收发现本机 `18081` 由其他项目的 UniAuth 服务占用；最终实施时重新探测端口，选择空闲 loopback 端口，不停止、不杀死、不复用任何非本批进程。
- 安全边界：真实测试只能使用本批启动并能确认所有权的隔离、可丢弃 PostgreSQL 数据库和服务进程；不得连 `.env` 指向的默认/共享数据库。端口在启动前做绑定/占用检查，容器、PID、日志目录均使用唯一 run ID；EXIT trap 只清理本批记录的容器/PID/目录，绝不按端口杀进程。不得写入或打印 `.env` 密钥、完整模型回答、prompt、source 正文。测试数据必须有唯一运行标识，清理失败要使验收失败。

## 2. 问题、证据与目标

最近真实验收已覆盖 MiniMax-M3 与 BGE-M3、支持性问答、引用、Native SSE、Python/Node OpenAI SDK，以及显式 Collection 范围；此前常见流程曾通过。但验收同时暴露如下确定问题或证据缺口：

1. `scripts/real-llm-e2e-smoke.sh` 创建时已读取 `documentRevision`，退出清理却没有把 revision 传给永久删除接口，并忽略删除失败。`RagDocumentController.deleteDocument` 要求 `expectedDocumentRevision`。之后脚本对测试 Collection 的 `DELETE` 只是软删除并解绑文档，因此失败会留下启用的未归属文档，污染后续省略范围的请求。
2. 对不受限调用者，REST 文档与 `CollectionRetrievalScopeResolver` 明确规定省略范围推导为 `CALLER_VISIBLE`，它包含未归属 Collection 的启用文档；`ANY_COLLECTION` 则排除未归属文档。授权快照允许此类来源，但 `RagChatHistoryRepository.loadActiveDocumentCollections` 使用内连接 Collection，最终持久化来源预留阶段拒绝未归属文档，并报 `COLLECTION_PURGE_CONFLICT`。这不是把默认范围改为 `ANY_COLLECTION` 的理由，而是“检索/授权承诺”与“durable commit”不一致。
3. SDK 示例声明 Node >=20 且固定 `openai` 版本，但没有锁文件；live quickstart 检查以临时目录 `npm install`，此前验收出现 Node import 失败，手工安装后通过。当前验收只运行 Python/Node JSON，未自动覆盖两个 SDK 的 SSE。
4. 真实质量证据曾有正确支持、证据不足和引文行为，但用例未全部固定成可复用的版本化 QA 套件。曾出现过的 409/502 不能只以“随后一次通过”结案：需区别预期生命周期并发冲突、错误的来源提交冲突、SDK 请求失败和上游 provider 故障。
5. 某些配置的 AGENT capability 或 OpenAI-compatible upstream 无法通过 preflight；这属于未验证边界，必须持续显式报告，不能借其他模型的通过结果替代。

本批目标是统一修复数据生命周期、提交一致性、SDK 复现性，并通过外部 HTTP/SDK 与真实 LLM 多场景验收闭环；不改变既有请求字段、响应字段语义、端点、状态码契约或默认检索范围。

## 3. 已核对的代码与契约事实

| 领域 | 当前事实与近距离入口 |
|---|---|
| 真实 smoke 清理 | `scripts/real-llm-e2e-smoke.sh` 的 `cleanup` trap 目前尝试调用文档硬删除但未带 revision，且忽略失败；随后尝试 Collection 软删除，后者会解绑仍存在的文档。创建响应已保存 `DOC_REVISION`，脚本没有验证清理后状态。 |
| 文档永久删除 | `spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagDocumentController.java` 的 `DELETE /api/v1/rag/documents/{id}` 要求 `expectedDocumentRevision`，不提供该值是请求错误；`DocumentMutationService.hardDeleteLocal` 校验 revision、删除向量并硬删文档。 |
| Collection 删除语义 | `RagCollectionController.deleteByKey` 转到软删除；软删除会解绑文档，不能用 404 作为文档清理证明。Collection 永久清理需 purge preview/apply，且可能受权限/功能开关限制、保留 key tombstone。 |
| 检索默认范围 | `docs/rest-api-zh-CN.md` §“Collection 检索范围语义”明确：省略 mode 与 Collection 字段为 `CALLER_VISIBLE`；其不受限调用者语义包含未归属文档，`ANY_COLLECTION` 只含已归属文档。生产解析器位于 `CollectionRetrievalScopeResolver.java`。 |
| 证据授权快照 | `ChatAuthorizationService` 的 snapshot 保存每个 source 的 `documentId` 与可空 `collectionId`，并对未归属来源按 `CALLER_VISIBLE + unrestricted` 校验；已有 `ChatAuthorizationEvidenceTailTest` 覆盖此授权边界。 |
| 持久化栅栏 | `RagChatHistoryRepository.reserveDurableContentReferences` 在提交事务中为已归属文档递增 `rag_collection.chat_commit_fence_version`，再次校验来源，再写历史与引用索引。V56 的来源索引刻意没有文档外键；Collection purge 用引用索引保护清理。 |
| 文档并发版本 | `RagDocument.version` 是 Hibernate `@Version`；`document_revision` 是外部 mutation API 的业务 revision。新栅栏必须与业务 revision 分离，不能让普通 Chat 改变 `documentRevision`。 |
| 评测能力 | 现有 managed Evaluation Suite 已支持不可变 version、`KNOWLEDGE_QA`、`requiredFacts`、`forbiddenFacts`、`evidenceExpectation`、确定性 hard gate、可选 judge、usage/latency/failure metrics 和同版本 run compare；见 `EvaluationSuiteService`、`KnowledgeQaEvaluator` 与 REST API §Evaluation Suites。优先复用，不另建第二套评测产品。 |
| SDK 验收 | `scripts/verify-client-sdk-quickstart.sh` 做离线语法/泄密检查及可选 live HTTP；Python/Node 示例均支持 `--stream`。`scripts/verify-openai-sdk-e2e.sh` 提供一次性 PostgreSQL 与模拟上游的 SDK 协议/错误路径门禁。 |
| 测试与文档门禁 | `scripts/verify-gated-it.sh` 聚合 PostgreSQL 门禁；`scripts/verify-release.sh` 有可选 OpenAI SDK、Client SDK、恢复门禁；REST、Testing Guide、Developer Reference、Release Checklist 均维护中英双语版本。 |

## 4. 范围与明确默认决策

### 4.1 外部 API 与证据提交一致性

- 保持不带 `collectionScopeMode` 的旧请求继续推导到 `CALLER_VISIBLE`；不改成 `ANY_COLLECTION`，不要求旧 client 补参数。
- 在 `rag_documents` 增加内部 `chat_commit_fence_version BIGINT NOT NULL DEFAULT 0`，新增 V65 Flyway 迁移，不回填业务数据、不重嵌入、不改变 DTO/OpenAPI 请求响应字段。新列默认值为 0，旧数据直接可用，升级无需运维手工 SQL。
- `reserveDurableContentReferences` 在 Chat commit 事务开始预留时读取活动文档快照（document ID、Collection 归属、`enabled`、`source_deleted_at`、`document_revision`）。已归属来源继续按 Collection ID 升序递增 Collection 栅栏；未归属来源按 document ID 升序用条件 `UPDATE` 原子递增文档栅栏，条件验证 `collection_id IS NULL`、`enabled = TRUE`、`source_deleted_at IS NULL` 与本次预留读取的 `document_revision`，但**不**比较读取到的旧 fence 值。并发 Chat 因此会在数据库条件写入上顺序递增而不产生假冲突；真实 mutation 若在本次预留快照之后、条件写入之前删除/禁用/重分配/改 revision，条件写入失败关闭。
- 条件写入后重新读取并比较 document/Collection 归属、`enabled`、`source_deleted_at` 与业务 revision；未归属来源仅在数据库提交预留时符合当前状态才可写入来源索引。多个只读 Chat 对同一未归属文档必须都能按序成功递增 fence；与 mutation 在“预留快照→条件写入→历史提交”窗口竞争时按先后串行，mutation 先改变检查字段则 Chat 整体回滚，Chat 先完成则后续 mutation 仍可继续。该栅栏保证的是提交预留窗口，不声称捕获**在预留快照之前**已经完成的内容变更相对于更早检索结果的版本漂移；给检索结果新增 revision pin 属于另一项 API/数据语义设计，本批不扩展。不得使用显式悲观锁、`FOR UPDATE`、`SKIP LOCKED` 或 advisory lock。
- 受限 API Key 的 Collection allow-list、`SELECTED_COLLECTIONS` 和 `ANY_COLLECTION` 对未归属文档仍拒绝/排除；只有既有不受限 `CALLER_VISIBLE` 路径可使用它们。授权判断仍由既有 scope/authz 流程负责，持久化层只为当前合法来源提供一致性栅栏。
- Collection 的 V56 fence/purge 语义保持不变。测试同时验证文档 mutation 与 Chat commit 的先后提交：已提交的 Chat 可留下历史 source ID，后续 hard delete 不倒改 Chat 结果；mutation 先完成导致来源状态/业务 revision 改变时，Chat 提交应冲突且不写半截历史。

### 4.2 E2E 清理与 HTTP 可诊断性

- 保留 `scripts/real-llm-e2e-smoke.sh` 的现有入口，强化 EXIT trap：保留原始退出码；清理阶段逐个验证 HTTP 状态和响应形状，清理失败最终使脚本失败。
- 新增本批统一验收入口 `scripts/verify-client-api-e2e.sh`：启动一个本批所有的 `pgvector/pg16` 临时容器并使用随机本机端口/唯一数据库，注入 `POSTGRES_*` 覆盖；按唯一空闲端口启动服务、运行黑盒/API/SDK/真实 LLM 矩阵，最终仅停止自己创建的 PID 与容器。`scripts/dev.sh` 的启动/停止验收也使用临时 DB 和动态选定的空闲端口；不得把正常开发栈或共享数据库作为测试目标。若 Docker/provider 不可用，按具体阶段标 `SKIP/BLOCKED`，不宣称整批通过。
- 永久删除文档必须使用当前 `expectedDocumentRevision`。清理时可读取该唯一测试文档最新 revision，但要同时核验测试运行标记/标题以避免误删别人的资源；只允许有限次 CAS 冲突重试。文档删除成功并经 `GET /documents/{id}` 确认 404 后，才能软删除其 Collection，避免解绑后遗留 orphan。
- 结束验收检查：测试文档不存在、测试 Collection 不再 active、没有带本次唯一标记的 enabled orphan 文档。若 collection purge 被配置允许，可在预览/确认协议可完成时永久清除；默认不绕过 purge 权限，也不把永久保留的 tombstone 误报为活动残留。
- 所有 API 探测记录 method、path 类别、HTTP status、错误码与 traceId；不输出 Authorization/API key、完整 answer、source 内容或 provider 原始错误全文。预期 409 场景需匹配准确错误码；非预期 409/502 属失败并保留脱敏诊断。
- 使用唯一 `RUN_ID` 与权限受限临时目录，防止并行运行覆盖 `/tmp/rag-e2e-*`。trap 只处理本脚本拥有的 PID/容器/文件，不清理其他项目端口/进程。

### 4.3 SDK 确定性与外部 client 回归

- 为 `examples/client-sdk/node/` 提交 lockfile，验证器用 lockfile 驱动临时隔离目录 `npm ci`；运行复制出的 `.mjs` 文件时从正确临时 package root 解析 `openai`，不依赖调用者已有 `node_modules`。
- Live quickstart 一次完整覆盖 Python JSON、Python SSE、Node JSON、Node SSE；每个组合验证 HTTP 成功、非空合法 JSON completion 或合法 SSE 结束、choices/content 形状，并使用已知测试 Collection 和受限范围。输出只报模式、状态、chunk 数与 trace/case 编号，不回显正文。
- 原有 OpenAI 官方 SDK Mock E2E 继续覆盖标准 SDK、Native/OpenAI API 兼容、JSON/SSE、正常/负向变异和 4xx/409/502 解析；补齐任何本批发现的遗漏，不把 Mock 上游失败冒充真实 provider 验证。
- 外部 API 黑盒覆盖：旧客户端省略可选 scope 字段；默认 `CALLER_VISIBLE` 可返回未归属来源并正常提交；`ANY_COLLECTION` 排除未归属来源；`SELECTED_COLLECTIONS` 不越界；DELETE 缺 revision 返回 4xx、不产生副作用；正确 revision 删除成功；过期 revision 产生稳定冲突且不删除；JSON/SSE 字段与既有错误 envelope 不破坏。

### 4.4 版本化真实问答、引用、安全回答与运行指标

- 在 `testdata/` 建立带 schema/version 的最小 Client API `KNOWLEDGE_QA` suite fixture，含：支持性问题（预期 source identity、必需事实、禁止事实）、证据不足问题（必要的不能回答声明、禁止编造事实）、跨 Collection 禁止泄漏问题。使用外部稳定身份 `(collectionKey, sourceNamespace, externalId)`，运行时仅注入随机测试 token；不得把运行密钥或生产数据放入 fixture。
- 复用现有 Evaluation Suite API 导入不可变版本并主动运行；每次只选一个 retrieval variant、关闭 judge，按 3 个 case 执行两个独立 run，再调用同版本 compare（compare 本身只读、不调用模型）。判定只依赖确定性 source coverage、required/forbidden facts、insufficient declaration 和引用合法性。两次 run 都必须通过 hard gate 且 compare 无环境漂移；第二次 run 用来检验重复性，不伪装成不同配置间的质量提升。
- 记录/比较 suite version、chat/embedding model 标识、retrieval variant、测试语料指纹、provider 配置摘要、每 case latency、usage、traceId、status、failure reason、环境漂移和 hard-gate 结果。报告只落在 `.verification/`，脱敏且不保存 prompt/answer/source 正文。
- 支持性 native JSON、native SSE、OpenAI-compatible SDK JSON/SSE 与 Evaluation QA runner 共同形成互补证据；用例间共享一次测试文档/embedding，防止重复建库与重复调用。
- 测试无答案但相关性较低的 query 时，不把“引用了明确说明资料范围的文档”机械判断成虚假支持；用明确 expected facts、forbidden facts、证据充分性与引用归属同时判定。评测只能证明测试场景，不宣称普遍模型质量。
- 对 AGENT capability 与额外 OpenAI-compatible upstream，先做 capability/health preflight。不可达或不支持时输出 `SKIP` 与具体原因；测试通过的 MiniMax KNOWLEDGE 路径不替代它们。

## 5. 文件级实施顺序

1. **迁移与来源 fence**：新增 V65；更新 `RagChatHistoryRepository` 的来源快照/预留与重查逻辑；保留现有公开记录构造和 `documentRevision` 语义；增加 repository 与 PostgreSQL 测试。
2. **数据库/API 黑盒回归**：扩展 Chat/Collection purge PostgreSQL IT，增加未归属默认范围、范围拒绝、文档 revision 删除与并发提交矩阵；让真实 HTTP endpoint 通过 MockMvc/随机端口走外部序列化与状态码。
3. **E2E 生命周期修复**：加强 `real-llm-e2e-smoke.sh` 的 status-aware request helper、唯一 run dir、失败关闭清理及残留后置条件；加 self-test fixture 验证清理失败不会被吞。
4. **Node/Python SDK 验收**：锁定 Node 依赖；升级 `verify-client-sdk-quickstart.sh`，让 `--self-test` 验证锁文件/隔离依赖解析及四路调用 wiring，live 模式完整跑 JSON/SSE。
5. **版本化 QA 用例和真实矩阵**：增加 QA suite fixture 与 runner，将测试集合/文档以稳定外部身份创建/嵌入，调用 suite import/run/compare 与 Chat JSON/SSE/SDK；收集无敏感信息的逐 case 报告；统一清理。
6. **Provider 错误与运维文档**：用模拟 provider 对 409/502、超时、协议异常验证错误分类与 trace 关联；更新双语 REST/Testing Guide/Developer Reference/Release Checklist/版本清单与必要架构文档；没有需要时不改 WebUI。
7. **集成验收与收敛**：先基本硬门槛，再完整 PostgreSQL 门禁、全 Maven、API/SDK contract、统一入口的一次性 DB/服务生命周期验收、`scripts/dev.sh` 隔离端口启动停止、真实 LLM 多场景验收；随后明确执行 `./scripts/verify-no-pessimistic-locks.sh`、`./scripts/verify-project-docs.sh`、`git diff --check`，最后完成实现三轮只读检查。

预计主要修改入口：

- `spring-ai-rag-core/src/main/resources/db/migration/V65__*.sql`
- `spring-ai-rag-core/src/main/java/com/springairag/core/repository/RagChatHistoryRepository.java`
- `spring-ai-rag-core/src/test/java/com/springairag/core/repository/`、`spring-ai-rag-core/src/test/java/com/springairag/core/integration/`
- `scripts/real-llm-e2e-smoke.sh`、`scripts/verify-client-sdk-quickstart.sh`、`examples/client-sdk/node/`
- 新增 `scripts/verify-client-api-e2e.sh` 统一隔离资源和成本/结果报告
- 新增 `testdata/` QA fixture、必要的 `scripts/` runner/self-test
- `docs/rest-api*`、`docs/testing-guide*`、`docs/developer-reference*`、`docs/release-checklist*`、`docs/architecture*`、`docs/project-context*` 中受影响的版本说明

## 6. 一次性验收矩阵

| 层 | 必须证据 | 通过标准 |
|---|---|---|
| 迁移 | 空 PostgreSQL 从 V1 升到 V65；既有 V64 schema 自动升级；V65 默认值检查 | 自动升级成功，无手工 SQL/backfill；新旧数据库的列与默认值一致 |
| 来源 reservation | Collection 归属与未归属文档单测/PG IT；同文档并发 Chat；mutation 在预留 snapshot 与条件写入间竞争；多个来源确定性排序；无引用旧路径 | 合法未归属来源可持久化；同文档并发 Chat 不产生 fence 假冲突；本次预留窗口内文档删除、disabled、tombstone、重分配或 revision 改变时按安全错误失败；回滚无半截 history/source index |
| 范围与权限 | 不带 scope 的旧 JSON、显式 `CALLER_VISIBLE`、`ANY_COLLECTION`、`SELECTED_COLLECTIONS`；受限 API Key | 外部语义不变；未归属只在不受限 `CALLER_VISIBLE` 可检索与提交，不扩大受限调用方权限 |
| 删除契约 | 外部 HTTP DELETE 缺失/当前/过期 revision；清理后 GET 与搜索检查 | 缺失/过期 revision 不删除；当前 revision 删除成功；脚本报告无本次运行的 enabled orphan；collection 不再 active |
| E2E 清理失败路径 | stub HTTP 404/409/500、超时、已不存在资源、trap 原始退出码 | 清理成功不覆盖主失败；任何本应删除但失败的资源导致非零退出并给出安全诊断；不会把 doc 解绑为 orphan |
| SDK | quickstart offline/self-test；OpenAI SDK Mock JSON/SSE/负向矩阵；Python/Node live JSON/SSE | 全新 checkout/隔离目录不依赖本机依赖；HTTP envelope 与 SSE terminator/chunk 兼容；OpenAI 旧字段不回归 |
| 版本化真实 QA | 支持、证据不足、跨 Collection 禁止泄漏；run metrics/同版本 compare | 所有确定性 hard gate 通过；required/forbidden facts、来源、引用、拒答语义按 case 断言；无 `environmentDrift` 才可比较质量变化 |
| 错误与可观测性 | provider stub 409/502/timeout/invalid payload；traceId/status/安全日志 | 意料中的 conflict 可分类；意外冲突能定位到 trace；不回显 key、prompt、答案、来源正文；真实上游失败与服务端契约失败分开报告 |
| 服务启停与整体回归 | `scripts/dev.sh` 在隔离端口/一次性 PostgreSQL 健康、外部 API 请求、停止；`verify-gated-it.sh`；`mvn clean test`；文档/禁锁/SDK 脚本 | 服务 ready、真实 HTTP API 可调用、退出后自有端口释放；全回归无失败/意外 skip；WebUI 不作为 Client API 通过的替代证据 |
| 真实 provider 约束 | MiniMax-M3 + 当前 embedding provider；preflight、最多 3 个单 chunk 文档、限定搜索、Native JSON/SSE、受限 AGENT、Python/Node JSON/SSE、3-case Evaluation 两次 run + compare | 最多 15 次 Chat completion / 18 次 Embedding 请求；启动前检查矩阵并按 provider 调用预留额度；关闭重试、query rewrite 与 judge，预算外/意外调用立即失败；记录实际调用数、模型与 usage；provider 能力不支持/不可达时准确标为未验证 |

若真实 LLM provider 质量断言失败，先保留该 case 的脱敏指标与 trace，确认是模型非确定性、测试语料/评测断言错误还是产品缺陷；只有可重复且不削弱确定性安全要求的修正可纳入本批。不得为得到绿灯放宽禁止事实、来源范围或引用 hard gate。

## 7. 兼容、安全、失败与回退

- **API 兼容**：不移除/重命名字段、route、event、状态码或 error code；旧省略 scope 的行为仍是 `CALLER_VISIBLE`；新增的只是内部数据库栅栏与测试资产。
- **迁移/升级**：V65 增列默认 0；Flyway 自动应用，无逐行回填；若迁移失败，由 Flyway 启动失败保护，不允许以降级到旧检索默认掩盖。
- **并发**：条件 `UPDATE` 检查行数和 revision；Collection ID 与未归属 Document ID 固定排序；冲突事务整体回滚。禁止显式悲观锁，静态禁锁门禁必须通过。
- **隐私/成本**：真实 suite 主动执行才产生 LLM 费用；judge 默认 disabled；临时 answer/source 原文不进入 artifacts；密钥仅通过环境读取。
- **回退**：软件版本回退可继续使用向后兼容的 V65 列；不要删除或重写已应用 migration。若需暂时回退提交逻辑，保留 V65 未使用列即可。测试脚本失败时保留唯一测试 Collection 与文档关联，不得通过软删 Collection 制造 orphan。
- **明确非目标**：不改默认范围为 `ANY_COLLECTION`；不扩展新的公开 API 功能；不建设第二套 QA 评测服务或 UI；不以 judge 单模型代替确定性门槛；不要求运营手工清理测试数据；不测试不具备 capability 的 AGENT 模型并声称覆盖；不改与本批无关的旧 API 或 WebUI。

## 8. 实施进度与检查要求

- 对应进度账本记录规划三轮、实现切片、每条实际命令与退出码、真实 provider 使用的非敏感模型标识、调用数量/usage、环境限制、实现审查计数和恢复入口。
- 本规划须先经过连续三轮完整、无修改审查，才允许改生产代码。检查范围依次为：需求/兼容/自包含；代码/schema/事务/并发/权限；文件顺序/验收/成本/失败恢复/交付。
- 若审查发现实质性错误，先修正文档并在进度账本记录触发重置的原因，规划检查归零重新做三轮；无问题轮次只在当前会话进度消息汇报，不修改规划正文。
- 实施后先通过基本集成硬门槛，再做实现三轮只读检查；任一轮发现本批实质缺陷即修复并把实现审查计数重置为 0，重跑硬门槛。
- 完成定义：API/数据库/SDK/真实 LLM/启停验收均按上表留有可信证据；无 secrets 或 test data 污染；双语长青文档、迁移清单和自测门禁同步；工作区结果如实交接。提交/推送不在本请求中默认执行。

## 9. 规划检查日志

实施前连续三轮检查在当前会话汇报并计数；发现问题导致修改时只记录重置轮次与原因。三轮通过后，在进度账本最终写入检查摘要与时间，避免在无问题轮次之间修改本规划正文。
