# 下一批高价值功能实施进度

> 对应规划：[下一批高价值功能规划](2026-08-17_NEXT_MOST_WORTHWHILE_FEATURES_PLAN-zh-CN.md) /
> [English](2026-08-17_NEXT_MOST_WORTHWHILE_FEATURES_PLAN.md)
> 状态：实施与验证已完成（待提交/推送）
> 开始日期：2026-08-17
> 代码基线：`b8a478f`

本文件只记录本轮实施状态、验证证据和已知阻塞，不替代规划文档或正式 API/架构文档。
每次取得关键进展后先更新本文件，再进入下一阶段。不得使用 `stash`、`reset --hard`
或丢弃其他开发者的工作区修改。

## 1. 规划验收

规划文档已连续三轮系统检查无修改通过：

| 轮次 | 范围 | 结果 |
|---|---|---|
| 1 | 文档门禁、代码事实、依赖、Flyway、Collection/JSON 基线 | 通过，无修改 |
| 2 | Spring AI 内置类、ACL、安全边界、并发/幂等、任务 rollout | 通过，无修改 |
| 3 | 双语结构、语言链接、实施顺序、测试/脚本/回滚边界 | 通过，无修改 |

期间发现并修复的规划问题：

- 未知 model alias 的验收错误码从 `400/404` 收敛为 `404 model_not_found`；
- Batch B 增加 `rag.embedding-jobs.enabled=false` 默认 rollout gate；
- 修正英文规划误链中文境内网络指南；
- 补齐英文 OpenAI feature flag 回滚值。

## 2. 实施矩阵

| 批次 | 目标 | 状态 | 关键产物 |
|---|---|---|---|
| Phase 0 | 基线、进度台账和测试矩阵 | 已完成 | `mvn clean compile test-compile`、WebUI Vitest `209/209`、TypeScript 和 production build 通过 |
| Batch A | 请求级 Collection scope 的 OpenAI Chat Completions 适配层 | 已完成 | `/v1/models`、`/v1/chat/completions`、scope/ACL、验证脚本、正式文档 |
| Batch B | 持久化 embedding/reindex jobs | 已完成 | V33 migration、repository、worker、jobs API、验证脚本、正式文档 |
| Batch C-1 | JSONB payload containment 与 JSON Agent Tool | 已完成 | V34 index、filter API、可选 `searchJsonRecords`、验证脚本、正式文档 |
| Batch C-2 | 真实检索回归门禁 | 已完成 | 稳定身份 dataset、runner、baseline、release gate、正式文档 |
| 收敛 | 基本门禁后连续三轮实现检查 | 已完成 | 三轮互不重叠的限定范围只读审查，连续 `3/3` 无修改 |

## 3. 验证门槛

### 基线

- [x] `./scripts/verify-project-docs.sh`（10 项通过）
- [x] `git diff --check`
- [x] `mvn clean compile test-compile`
- [x] WebUI TypeScript、Vitest、production build

### 实施完成

- [x] 相关后端单元/Controller/契约测试
- [x] PostgreSQL/Testcontainers 集成测试
- [x] core standalone 与 starter consumer
- [x] 服务启动和 HTTP/JSON 验证
- [x] WebUI 只有在改动时执行 DOM/网络/JSON/断言验证，不使用截图
- [x] 每个新能力的一键验证脚本
- [x] 中英文正式文档同步
- [x] 连续三轮实现代码无修改收敛检查

## 4. 工作区边界

本轮开始时已有且必须保留的修改：

- `docs/index.md`
- `docs/index-zh-CN.md`
- `docs/drafts/archive/2026-08-17_NEXT_MOST_WORTHWHILE_FEATURES_PLAN.md`
- `docs/drafts/archive/2026-08-17_NEXT_MOST_WORTHWHILE_FEATURES_PLAN-zh-CN.md`

后续若发现其他开发者的 WIP，按文件和行为边界协作，不回滚、不覆盖；若只因测试夹具
阻塞编译，只做最小测试适配。

## 5. Batch A 实施记录

- 已增加默认关闭的 `rag.openai-compatibility` 配置与公开 model alias 注册表；
- alias 只保存 pipeline/model candidates，不保存固定 Collection；
- 已增加 OpenAI text-only DTO、`/v1/models`、`/v1/chat/completions`、
  非流式响应和标准 `data:` SSE 映射；
- 已增加 body `rag.scope` 与重复 `X-RAG-Collection-Key` 的统一 scope adapter，
  最终仍委托 `CollectionRetrievalScopeResolver` 与 API Key ACL；
- 已扩展内部 `ChatCommand` 支持完整消息列表和 alias 限定的 backend candidates，
  保留原 11 参数构造器和旧 `/api` DTO；
- 已将认证、限流、CORS、SLO 路径覆盖扩展到 `/v1/**`，OpenAI 路径使用独立错误信封；
- 已先补 alias、scope/ACL、message mapping、Controller envelope、旧 Chat
  多消息兼容及 starter filter focused tests。

下一门槛：Batch A focused tests 与 `test-compile` 通过后，再补一键 HTTP 验证脚本和正式文档。

## 6. Batch B 实施记录

- 已增加默认关闭的 `rag.embedding-jobs.enabled` 及有界 claim、lease、重试配置；
- 已增加 V33 `rag_embedding_jobs`、活动任务 partial unique index、claim/batch/document 索引；
- 已实现 JDBC job 状态机：原子 create/coalesce/force upgrade、`SKIP LOCKED`
  claim、lease 恢复、取消、重试、FAILED/STALE/SUCCEEDED 终态；
- 已增加 `DocumentEmbedService.embedDocumentForJob` 最小 worker commit guard；
  旧同步入口继续使用 allow-all guard；
- worker 在 provider 前和向量替换前校验活动 Profile、任务 lease、取消标记、
  document version 与 content hash；
- 已增加 `/api/v1/rag/embedding-jobs` 创建、查询、列表、取消、重试端点与 ACL 检查；
- 已补 service/worker 单测和可选真实 PostgreSQL V33/claim/coalesce 集成测试。

验证证据：

- `EmbeddingJobServiceTest`、`EmbeddingJobWorkerTest`、
  `DocumentEmbedServiceTest` 共 13 个 focused tests 通过；
- `mvn ... test-compile` 通过；
- `EmbeddingJobsPostgresIntegrationTest` 在隔离 PostgreSQL 16 数据库完成
  V1–V33 全量迁移，并验证 active-job coalesce、`force` 原子升级和双 worker
  `SKIP LOCKED` claim，1 个测试通过；
- 当前 Testcontainers 1.20.4 与本机 Docker 29 的最低 API 版本不兼容，测试夹具已支持
  外部隔离 PostgreSQL URL；默认 Testcontainers 路径保持不变。

下一门槛：Batch C JSONB containment、Agent Tool 与真实检索回归门禁。

## 7. Batch C-1 实施记录

- 已为 `JsonRecordSearchRequest` 增加可选 `payloadContains`，只接受非空 JSON
  object，默认限制 16 KiB、最大深度 8；
- payload containment 使用 PostgreSQL `jsonb @>`，并在向量、pg_trgm、English FTS、
  Jieba FTS 的 SQL `LIMIT` 前下推；
- JSON record 检索强制收窄到 `document_type=json-record`，复用调用者 scope 与 API Key
  ACL；空召回不调用 reranker；
- 已增加默认关闭的 Spring AI 工具 `searchJsonRecords`，Collection/ACL 由服务端上下文
  注入，模型不能提供 collection、SQL 或 JSONPath；
- 工具默认最多返回 5 条，单 payload 32 KiB，超限时整条省略，并保留 citation trace
  与总字符预算；
- 已增加 V34 partial GIN `jsonb_path_ops` 索引，加速 enabled JSON record 的
  containment 查询。

验证证据：

- `JsonRecordServiceTest`、`JsonRecordSearchToolTest`、
  `HybridRetrieverServiceTest`、`RetrievalScopeSqlTest`、
  `PgTrgmFulltextProviderTest`、`RagJsonRecordControllerWebTest`、
  `ChatExecutionServiceTest` 共 76 个 focused tests 通过；
- `mvn -pl spring-ai-rag-core -am test-compile -DskipTests` 通过；
- `JsonbStructuredRecordsPostgresIntegrationTest` 在隔离 PostgreSQL 16 数据库完成
  V1–V34 全量迁移，3 个测试通过：JSONB round-trip、级联删除、嵌套 containment
  与 V34 GIN planner 采用；
- planner 测试使用 5000 条低选择性噪声记录并执行 `ANALYZE`，避免小样本下 PostgreSQL
  合理选择普通 `document_type` 索引造成假阴性。

下一门槛：为 Batch A/B/C-1 固化一键验收脚本，并完成 Batch C-2 检索回归数据集与门禁。

## 8. 一键验证与 Batch C-2 实施记录

- 新增 `scripts/verify-openai-compatibility.sh`，覆盖 alias、scope/ACL、完整 messages、
  非流式 JSON、OpenAI error envelope、SSE chunk 顺序与 `[DONE]`；
- 验证发现 Spring MVC 无法把 `ResponseEntity<?>` 中的动态 `Flux`/`SseEmitter`
  识别为异步响应，已收敛为声明明确的 `ResponseBodyEmitter`，非流式 JSON 与流式 SSE
  复用同一端点且 HTTP 契约不变；
- 新增 `scripts/verify-embedding-jobs.sh`，自动启动隔离 pgvector/PostgreSQL，
  覆盖 service、worker、HTTP API、V33、coalesce 与 `SKIP LOCKED`；
- 升级 `scripts/verify-jsonb-records.sh`，使用外部 JDBC 参数绕过本机
  Testcontainers 1.20.4 / Docker 29 API 不兼容，并纳入 containment、Agent Tool 与
  V34 planner；
- 新增 `testdata/regression/retrieval-core-v1.json`，用稳定的
  `collectionKey + externalId` 表达 fixture/relevant identity；
- 新增 `scripts/run-retrieval-regression.sh` 与
  `scripts/verify-quality-regression.sh`，输出 JSON artifact、人类摘要，并对
  provider、数据库、embedding、scope 泄漏、空结果、minimum 和 baseline regression
  统一非零失败；
- 新增已验证 baseline `retrieval-core-v1-baseline.json`，并将质量门禁接入
  `verify-release.sh --with-quality-regression`；`--with-local-runtime` 默认包含该门禁。

验证证据：

- OpenAI compatibility 一键验证 4 步通过，focused 30 tests 通过；
- embedding jobs 一键验证 6 步通过，focused 15 tests 与 PostgreSQL 集成测试通过；
- 真实服务使用 `siliconflow-bge-m3-1024-v1` 执行 6 个回归 case，两次运行均通过；
- 5 个计分 case aggregate：Hit Rate=1.0、MRR=1.0、Recall@5=1.0、nDCG=1.0；
- JSONB 明确空结果 case 返回 0 命中，selected Collection 未泄漏 decoy identity。

正式文档已同步：

- `configuration*`、`rest-api*`、`architecture*`、`project-context*`；
- `testing-guide*`、`developer-reference*`、`quality-defaults*`、`release-checklist*`；
- `openai-compatibility-readiness*`、`SSE-PROTOCOL.md`、`README*`、`CHANGELOG*`、
  `docs/index*`、`AGENTS.md` 与 project-docs Skill/checklist；
- Flyway live 口径统一为 V1–V34，OpenAI 兼容明确为默认关闭的受控预览。

下一门槛：执行全量验证硬门槛；全部通过后开始三轮限定范围只读审查。

## 9. 全量硬门槛收敛记录

第一次执行 `scripts/verify-jsonb-records.sh` 已通过：

- API DTO 467 个测试；
- documents chunker 26 个测试；
- core JSONB/controller/OpenAPI/chat/retrieval focused tests；
- 隔离 PostgreSQL V1–V34 迁移与 JSONB 集成测试；
- `mvn clean compile test-compile`；
- WebUI Vitest 209 个测试、TypeScript 与 production build。

该次运行在 Playwright 阶段暴露验证脚本自身缺陷，而非产品失败：

- 首选端口 `4174` 已被其他项目占用；
- Vite preview 绑定失败后，旧脚本仅以该端口 `/webui/` 返回 200 为就绪条件，
  因而误把其他应用当作当前 WebUI；
- 中断时 `RETURN` trap 又访问了已离开作用域的局部 PID，产生
  `preview_pid: unbound variable`。

已完成修复：

- `verify-jsonb-records.sh` 与 `verify-release.sh` 会优先尝试配置端口，被占用时自动选择
  本机空闲端口；
- preview 就绪必须同时满足：当前进程仍存活、Vite 日志已输出 `Local:`、页面包含
  `spring-ai-rag WebUI` 标识；
- preview PID 提升为脚本级状态，由显式清理和退出清理共同兜底；
- 删除 `alignment.spec.ts` 的截图采集，前端验收仅保留 DOM、CSS、布局和请求断言。

下一门槛：在 `4174` 保持被占用的条件下重跑完整 JSONB 一键门禁，确认自动换端口与
58 个 Mock Playwright 用例全部通过。

重跑结果：

- `scripts/verify-jsonb-records.sh` 共 12 个步骤全部通过；
- 首选端口 `4174` 保持被占用，脚本自动选择 `58742`；
- 58 个 Mock Playwright 用例全部通过，仅使用 DOM、CSS、布局、URL、请求体和响应断言；
- 文档门禁 10 项与 `git diff --check` 通过；
- 证据摘要：
  `.verification/jsonb-verification/20260818-072142/summary.md`。

下一门槛：补齐 core/starter 消费侧和服务启动健康证据，然后进入三轮限定范围只读审查。

补充硬门槛结果：

- Chat focused backend tests：199 个通过；
- 完整 Maven：API 539、documents 74、core 2771、starter 48 个测试通过，零失败；
- 安装当前 reactor 后，独立 `demo-domain-extension` consumer 19 个测试通过；
- 隔离 PostgreSQL 与 dummy 模型端点下启动当前 Spring Boot 应用，
  `/actuator/health` 返回 `UP`，数据库、pgvector、核心表、liveness 与 readiness 均正常；
- 复用本轮已通过的 PostgreSQL JSONB 门禁和 58 个 Mock Playwright 门禁，因此
  `verify-chat-capability.sh` 本次只显式跳过重复的 PostgreSQL/Playwright；
- 证据摘要：
  `.verification/chat-capability/20260818-072637/summary.md`。

时间口径说明：项目与本进度记录日期按 `2026-08-17`；上述 `20260818-*` 目录名由
时间超前的宿主机时钟自动生成，只是本地验证 artifact 标识，不改变项目日期。

## 10. 最终正确性修复与收敛

统一硬门槛后，限定范围审查发现并修复了以下会影响正确性、兼容性或数据一致性的问题：

- OpenAI 兼容消息映射保留服务端 prompt customizer，并按原顺序传递客户端
  `system`、`developer`、`user`、`assistant` 消息；alias 限定的候选链只在该链内
  跳过不可用/能力不匹配模型，不会静默回退到全局模型；
- embedding job 的活动任务合并会原子升级 `force`、document version 与
  `maxAttempts`；worker 会在 provider 调用前后和向量替换前复核 lease、取消、
  Profile、document version/content hash，并处理执行期间发生的 `force` 升级；
- retry 会与同一快照的既有活动任务合并，配置层保证
  `default-max-attempts <= max-attempts`，降低上限时同步收窄默认值；
- 修复最终尝试发生 worker 崩溃时的状态机缺陷：已耗尽尝试次数的过期
  `RUNNING` lease 会原子转为 `FAILED`、清空 lease 字段并记录有界错误，不会永久
  卡在 `RUNNING`；真实 PostgreSQL 集成测试
  `expiredLeaseAfterLastAttemptBecomesFailedInsteadOfStayingRunning` 覆盖该路径。

最终验证证据：

- OpenAI compatibility：
  `.verification/openai-compatibility/20260818-073625/summary.md`；
- embedding jobs：
  `.verification/embedding-jobs/20260818-075038/summary.md`，19 个 focused
  service/worker/controller tests 与 2 个真实 PostgreSQL V1–V34 集成测试通过；
- JSONB/API/构建：
  `.verification/jsonb-verification/20260818-074432/summary.md`，API DTO 467、
  documents 26、core focused 169 个测试通过，`mvn clean compile test-compile`、
  WebUI Vitest 209 个测试和 production build 通过；
- Mock Playwright：
  `.verification/jsonb-verification/20260818-072142/summary.md`，58 个用例通过，
  只使用 DOM、CSS、URL、网络请求/响应与断言，未使用截图；
- 完整 Maven、starter consumer 与服务启动：
  `.verification/chat-capability/20260818-072637/summary.md`；
- 真实检索回归：`.verification/quality-regression/20260817-final/summary.md`，
  Hit Rate、MRR、Recall@5 与 nDCG 均为 `1.0`。

最终限定范围只读审查连续三轮无修改通过：

| 轮次 | 固定范围 | 结果 |
|---|---|---|
| 1 | OpenAI transport、安全、Collection scope、model fallback | 通过，无修改 |
| 2 | Embedding jobs、JSONB、并发与数据一致性 | 通过，无修改 |
| 3 | 一键脚本、回归 artifact、release 集成、双语文档与配置 | 通过，无修改 |

连续无修改计数：`3/3`。本轮实施范围已收敛，剩余动作仅为最终轻量门禁、提交全部
工作区修改、合并远端并推送。
