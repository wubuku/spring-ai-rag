# Embedding Profile 与固定维度向量迁移实施进度

**日期**：2026-08-15
**状态**：实施与验证完成，待提交
**对应规划**：[2026-08-15_EMBEDDING_PROFILE_VECTOR_MIGRATION_PLAN.md](2026-08-15_EMBEDDING_PROFILE_VECTOR_MIGRATION_PLAN.md)
**实施基线**：`main`，开始时 HEAD `2fb37de`、Flyway `V1-V24`；当前验证快照已包含并行 Collection Key/JSONB 变更，Flyway 为 `V1-V29`

## 1. 不可遗忘的交付约束

1. 只在规划连续三轮无修改审查通过后实施；已于 2026-08-15 完成 `3/3`。
2. 不静默认领未知 Legacy 向量，不自动删除非空 `rag_vector_store`。
3. 模型调用必须在长事务外；任一 chunk 失败不得破坏已完成向量。
4. 在线检索和所有全文分支必须绑定同一个活动 Embedding Profile。
5. 固定长度列首版为 `embedding_1024 VECTOR(1024)`；跨维度必须重嵌入。
6. 一次性规划并建立核心验收测试，不把 review 当正确性证明。
7. 代码 review 前的硬门槛：
   - 本任务后端集成测试通过；
   - `mvn clean compile test-compile` 通过；
   - 后端全量测试通过；
   - 服务可真实启动；
   - WebUI `npx tsc -b`、生产构建和核心 Mock Playwright 通过。
8. 基本验证通过后执行固定范围代码审查；发现问题即修复、重验并把连续计数归零，
   只有连续三轮无代码修改才结束。
9. 每次关键进展先更新本文，再继续下一阶段。

## 2. 阶段状态

| 阶段 | 状态 | 结果 |
|------|------|------|
| 规划初稿与索引 | 完成 | 文档门禁通过 |
| 规划系统审查 | 完成 | 最后连续三轮无修改 `3/3` |
| Progress ledger | 完成 | 本文件已建立并记录硬门槛 |
| 一次性验收测试骨架 | 完成 | 已覆盖 Profile 写入、检索/状态/计数隔离、Legacy 认领和 V26 数据保护 |
| Flyway expand / cleanup | 完成 | V25/V26 已通过真实 PostgreSQL + pgvector 集成验证 |
| Profile runtime / Legacy guard | 完成 | Profile 注册、启动校验、索引管理与显式 Legacy 认领已通过集成验证 |
| 原子写入 / 缓存 / 状态 | 完成 | 事务外生成、短事务替换、失败回滚、缺失向量与 chunkCount 的活动 Profile 语义均已验证 |
| Profile-aware retrieval | 完成 | 向量、pg_trgm、pg_jieba、English FTS 已统一绑定活动 Profile 与新鲜 completed 状态 |
| 移除旧 VectorStore 路径 | 完成 | 生产端点、配置、依赖、迁移保护、测试和正式文档均已同步 |
| 正式文档同步 | 完成 | 中英文配置、架构、REST、部署、测试、项目上下文、索引和文档门禁均已同步 |
| 后端硬门槛 | 完成 | 聚焦测试、PostgreSQL Embedding Profile 集成测试、隔离 `mvn clean compile test-compile` 和全量 `mvn test` 均通过；JSONB PostgreSQL 集成测试另行开启验证 |
| 前端硬门槛 | 完成 | 当前快照 `npx tsc -b`、生产构建和核心 Mock Playwright 38 项均通过 |
| 代码连续审查 | 完成 | 修复 HTTP 错误契约问题及正式 REST 文档契约问题后，重新完成连续三轮无修改审查，最终计数 `3/3` |
| 当前快照真实启动与 API E2E | 完成 | 独立临时 PostgreSQL、后端 18082 启动；root -> 业务 key -> collection -> document -> embedding -> search 全链路通过 |
| 最终 diff / 文档门禁 | 完成 | `git diff --check` 通过；`./scripts/verify-project-docs.sh` 10 项通过；全量工作区 diff 已复核，待提交和推送 |

## 3. 验证记录

| 时间 | 命令 / 范围 | 结果 |
|------|-------------|------|
| 2026-08-15 | `./scripts/verify-project-docs.sh` | 规划阶段 10 项通过 |
| 2026-08-15 | `git diff --check` | 规划阶段通过 |
| 2026-08-15 21:09 CST | `mvn -pl spring-ai-rag-core -am -DskipTests compile` | 最新生产代码编译通过 |
| 2026-08-15 | 聚焦单元测试：`DocumentEmbedServiceTest`、`HybridRetrieverServiceTest`、三个全文 Provider 测试 | 56 项通过 |
| 2026-08-15 | `EmbeddingProfilePostgresIntegrationTest`（显式 JDBC，PostgreSQL 16 + pgvector） | 5 项通过；覆盖 V1-V26、固定列/索引、V26 非空保护、原子回滚、Profile 检索隔离与 Legacy 认领 |
| 2026-08-15 21:42 CST | Profile-aware 聚焦测试（Controller、Mapper、Web MVC、写入与检索） | 178 项通过 |
| 2026-08-15 21:44 CST | `EmbeddingProfilePostgresIntegrationTest`（增加实际 Spring Data Repository） | 6 项通过；新增覆盖状态统计、批量补嵌候选与 chunkCount 的 Profile/新鲜度语义 |
| 2026-08-15 | 收尾交叉核对 | 修正 Legacy 确认值示例、启动错误提示属性路径、项目文档门禁的 V26 断言，并为三种全文 Provider 增加 Profile/state/freshness SQL 断言；待硬门禁重新验证 |
| 2026-08-15 22:04 CST | 收尾聚焦测试 + 项目文档门禁 | 三种全文 Provider 27 项通过；`verify-project-docs.sh` 10 项通过；`git diff --check` 通过 |
| 2026-08-15 22:05 CST | `mvn clean compile test-compile` | 全 reactor 编译与测试编译通过（API、documents、core、starter） |
| 2026-08-15 22:07 CST | `mvn test` | 失败：22 个 Web 上下文错误，`LegacyEmbeddingMigrationService` 在非 PostgreSQL profile 下要求不存在的 `TransactionTemplate` Bean；已定位为实现接入问题，修复后重跑 |
| 2026-08-15 22:10 CST | `OpenApiContractTest` + `EmbeddingProfilePostgresIntegrationTest` | 修复后分别通过 22 项和 6 项；PostgreSQL 测试实际执行 V1–V26，覆盖固定列/索引、V26 数据保护、原子回滚、Profile 检索隔离、Legacy 认领和 Repository 语义 |
| 2026-08-15 22:14 CST | `HybridRetrieverServiceTest` | 全测试类 22 项通过；异步降级失败未能在聚焦类中复现，继续以全量测试作为硬门槛 |
| 2026-08-15 22:16 CST | 第二次全量 `mvn test` | 仍有 1 项失败；根因是 `AsyncTimeoutFallbackTests` 内残留 5 维 query/result 测试向量，与当前 Profile 的 1024 维校验不一致；已确认只需修正测试夹具后重跑 |
| 2026-08-15 22:18 CST | `AsyncTimeoutFallbackTests` | 修正测试夹具后 5 项通过；开始第三次全量 `mvn test` |
| 2026-08-15 22:20 CST | 第三次全量 `mvn test` | 全 reactor 通过：API 530、documents 74、core 2568（失败 0、跳过 6）、starter 48 |
| 2026-08-15 22:25 CST | 开发库 Legacy 只读核验 | 未绑定 Profile 的 180 条向量全部为原 `embedding` 列中的 1024 维向量；`embedding_1024` 尚未填充；没有已绑定 Profile 的混合行；当前 `.env` 配置模型为 `BAAI/bge-m3`、维度 1024 |
| 2026-08-15 22:25 CST | Legacy 认领前置决策 | 依据上述维度一致性和当前部署配置，使用显式 `adopt-legacy`、Profile `siliconflow-bge-m3-1024-v1` 及固定确认值认领；不自动推断其他模型，不写入或输出 API key |
| 2026-08-15 22:32 CST | `scripts/dev.sh`（显式 Legacy 认领） | 后端 readiness、Vite HMR、root 身份探针和 root 管理写入探针均通过；后端日志确认认领 54 个文档 |
| 2026-08-15 22:43 CST | 真实开发栈 API / 数据库核验 | readiness `UP`；root `/auth/me` 返回 `ENVIRONMENT_ROOT`、`API_KEY_MANAGE`；空 API Key 创建请求返回 `400 VALIDATION_FAILED`；真实 `/search` 成功返回向量检索结果；数据库确认未认领 0、空固定向量 0、185/185 向量已绑定且为 1024 维，57/57 状态为 `COMPLETED` |
| 2026-08-15 22:44 CST | WebUI `npx tsc -b` | 通过 |
| 2026-08-15 22:44 CST | WebUI `npm run build` | 通过，Vite 生产构建完成 |
| 2026-08-15 22:45 CST | `BASE_URL=http://127.0.0.1:15173 npm run test:e2e` | 38 项通过，0 失败 |
| 2026-08-15 22:48 CST | `mvn clean compile test-compile` | 全 reactor 成功；API、documents、core、starter 均 compile/test-compile 通过 |
| 2026-08-15 22:50 CST | 第一轮审查发现并修复 Legacy NULL 向量缺口 | `LegacyEmbeddingMigrationService` 现在将 NULL 或非 1024 维向量视为不可认领；新增 `legacyAdoptionRejectsMissingVectorWithoutMarkingCompleted` PostgreSQL 集成测试；代码审查计数重置为 `0/3` |
| 2026-08-15 23:00 CST | 并行迁移版本冲突修复 | 保留 Collection Key 的 `V27/V28` 顺序，将不含业务内容改动的 JSONB migration 从 `V27` 顺延为 `V29`；避免全量 Flyway 和真实服务重启因重复 V27 失败 |
| 2026-08-15 23:10 CST | Legacy 边界测试夹具修复 | V1 的旧 `embedding` 列是 `VECTOR(1024) NOT NULL`，原 768 维夹具无法落库；改为可落库但 chunk index 非连续的 Legacy 行，继续验证拒绝认领且不创建 `COMPLETED` 状态 |
| 2026-08-15 23:13 CST | Legacy PostgreSQL 集成测试重跑 | 7 项全通过；真实 PostgreSQL + pgvector 完整执行 Flyway V1–V29，固定向量列、V26 非空保护、原子替换、Profile 检索隔离、Legacy 正常认领及非法 chunk 拒绝均通过 |
| 2026-08-15 23:14 CST | 全 reactor clean 门禁的并行测试阻塞 | `DocumentAddedResponse` 新增 `collectionKey` 后，`DtoTest` 仍有 5 个旧构造器调用；仅在测试中补 `null` 兼容字段，未修改 DTO 或业务实现 |
| 2026-08-15 23:15 CST | `mvn clean compile test-compile` | 全 reactor 成功；API、documents、core、starter 的生产代码和测试代码均编译通过 |
| 2026-08-15 23:16 CST | 首次全量 `mvn test` | API 的 `DtoTest.class` 被并行编译进程覆盖为带 Eclipse 未解析类型 stub 的产物，导致 153 个运行时错误；源码类型均存在，未发现业务失败 |
| 2026-08-15 23:19 CST | API 模块独立 clean test | `mvn -pl spring-ai-rag-api clean test -Dtest=DtoTest` 通过，467 项全通过；确认前述问题为测试产物并行污染 |
| 2026-08-15 23:23 CST | 全量 core 上下文阻塞定位与最小启动修复 | 并行 Collection Key 改动新增了两个 controller 兼容构造器，主构造器缺少 `@Autowired`，Spring 尝试无参构造器；仅为 `PdfImportController`、`RagChatController` 主构造器补注入标记，不改业务逻辑，代码审查计数保持 `0/3` |
| 2026-08-15 23:27 CST | 异常 class 与并行进程复核 | `javap` 确认 `RagChatController`、`RagChatService` 字节码完整；用户启动的后端和 Vite 进程只读取 `target/classes`，未停止；发现 Cursor JDT 进程但当前 class 不是错误 stub |
| 2026-08-15 23:28 CST | 聚焦测试依赖与上下文阻塞定位 | core 单模块命令因读取本地仓库旧 API 而看不到并行新增 DTO，改用 `-am` 后解除；reactor 测试进一步确认仅缺 OpenAPI 的 `JsonRecordService` mock 和 MVC 的 `CollectionIdentityResolver` mock，按测试夹具做最小适配，不修改并行业务实现 |
| 2026-08-15 23:31 CST | 第二轮聚焦测试结果 | 测试进入 OpenAPI/MVC 启动阶段；共享 `target` 再次出现缺类/缺配置假象，报告包含 `AbTestService.class` 暂时不可见和 MVC 找不到 `SpringAiRagApplication`，未作为业务失败处理 |
| 2026-08-15 23:33 CST | 共享构建产物隔离决策 | 发现另一开发者正在运行 `mvn -pl spring-ai-rag-core -am -Dtest=RagJsonRecordControllerWebTest ...`（PID 89747），与用户 dev server、Cursor JDT 共享 `target`；不停止、不干预其进程，后续验证复制到 `/tmp` 独立工作树执行 |
| 2026-08-15 23:35 CST | 最新快照复核 | 隔离副本在复制瞬间早于其他开发者对 `createCollection` 测试夹具的更新，故出现旧测试失败；当前共享工作区已包含 service stub 与 `collectionKey` 请求适配，不重复修改，重新复制最新快照验证 |
| 2026-08-15 23:39 CST | 最新快照聚焦后端门禁 | 在独立 `/tmp` 工作树中，`OpenApiContractTest` 22 项与 `RagControllerIntegrationTest` 72 项全部通过；确认测试夹具与当前并行 Collection Key/JSONB 接口一致 |
| 2026-08-15 23:41 CST | PostgreSQL 集成验收 | 按测试指南使用 `jdbc:postgresql://127.0.0.1:35267/embedding_profile_test`，PostgreSQL 16.12 + pgvector 实际执行 Flyway V1-V29；`EmbeddingProfilePostgresIntegrationTest` 7/7 通过，0 跳过 |
| 2026-08-15 23:42 CST | 隔离 clean 编译门禁 | `/tmp/spring-ai-rag-verify.BgBBF2` 执行 `mvn clean compile test-compile` 全 reactor 通过；API、documents、core、starter 均 compile/test-compile 成功 |
| 2026-08-15 23:44 CST | 隔离全量测试门禁第一次重跑 | 2579 项中 2578 项无失败；`RagDocumentControllerTest.getDocument_found` 因并行 Collection Key 变更后的测试工厂未设置 `collectionKey` 触发 `Map.of` NPE；仅补充测试夹具默认 key，未修改业务实现 |
| 2026-08-15 23:47 CST | `RagDocumentControllerTest` 最小夹具适配复验 | 44 项通过；确认 `collectionKey` 默认值已解除 NPE，未改变控制器业务实现 |
| 2026-08-15 23:49 CST | 隔离全量 `mvn test` | 全 reactor 通过：API 530、Documents 74、Core 2579（失败 0、跳过 7）、Starter 48；其中 PostgreSQL 专项因未开启开关而跳过，随后单独执行 |
| 2026-08-15 23:51 CST | JSONB PostgreSQL 集成验收 | `TESTCONTAINERS_RYUK_DISABLED=true -Dapi.version=1.40 -Djsonb.it.enabled=true` 下真实 pgvector PostgreSQL 2/2 通过；Flyway V1–V29 空库迁移成功 |
| 2026-08-15 23:52 CST | WebUI 当前快照硬门槛 | `npx tsc -b`、`npm run build` 均通过；`BASE_URL=http://127.0.0.1:15173 npm run test:e2e` 38/38 通过 |
| 2026-08-15 23:53 CST | 既有开发服务版本核对 | 8081 readiness 为 `UP`，但该进程启动日志只执行至 Flyway V26，早于当前 V27–V29；保留用户进程不动，改用独立副本、临时数据库和新端口验证当前代码启动 |
| 2026-08-15 23:58 CST | 当前代码隔离服务真实启动 | 临时 PostgreSQL `spring-ai-rag-runtime-verify-20260815`（映射 35268）与后端 18082 readiness `UP`；Flyway V1–V29 完整执行，活动 Profile 初始化成功 |
| 2026-08-15 23:59 CST | 当前快照 root / business key E2E | root `/auth/me` 返回 `ENVIRONMENT_ROOT`；root 创建业务 key，`expiresAt=2099-12-31T23:59:59`；业务 key `/auth/me` 返回 `DATABASE_API_KEY`；集合、文档、真实 embedding、集合范围搜索均返回成功 |
| 2026-08-15 23:59 CST | 当前快照数据库结构核验 | `rag_embedding_profiles` 存在活动 `siliconflow-bge-m3-1024-v1`（1024）；`rag_embeddings.embedding_1024` 为 vector 列且存在 Profile 专属 HNSW 索引；测试行向量已绑定 Profile；`rag_vector_store` 不存在；Embedding State 为 `COMPLETED` |
| 2026-08-15（当前快照收尾） | 最新并行变更后的全量 Maven 测试 | API 533、Documents 74、Core 2606（失败 0、跳过 7）、Starter 48；未修改并行业务实现 |
| 2026-08-15（当前快照收尾） | 隔离服务 18083 真实探针 | readiness `200/UP`；未认证 `/api/v1/rag/auth/me` 为 `401`；root `/api/v1/rag/auth/me` 为 `200`，principal `ENVIRONMENT_ROOT` 且包含 `API_KEY_MANAGE`；旧 `/api/v1/rag/documents/1/embed/vs` 为标准 `404 NOT_FOUND`；OpenAPI 不含旧路径或 `rag_vector_store` |
| 2026-08-15（当前快照收尾） | WebUI 最终硬门槛复核 | `npx tsc -b`、`npm run build` 均通过；`BASE_URL=http://127.0.0.1:15173 npm run test:e2e` 通过 40/40 |
| 2026-08-15 00:02 CST | 代码审查第 1 轮 | 交叉检查 V25–V29、Legacy 认领、Profile 注册/索引、原子事务和 PostgreSQL 集成测试；未发现实质问题，计数 `1/3` |
| 2026-08-15 00:08 CST | 代码审查第 2 轮 | 交叉检查 Profile-aware 写入、缓存/失败/重试、删除、向量检索、三种全文检索、JSON/PDF/批量入口；未发现实质问题，计数 `2/3` |
| 2026-08-15 00:26 CST | 代码审查第 3 轮发现并修复 | 真实服务探针发现未匹配的 `/api/v1/rag/documents/1/embed/vs` 被静态资源兜底异常转换为 `500 INTERNAL_ERROR`；为 `NoResourceFoundException` 增加统一 `404 NOT_FOUND` 处理，并补充 404 响应单测；审查计数重置为 `0/3` |
| 2026-08-15 00:25 CST | 修复后聚焦验证 | `mvn -pl spring-ai-rag-core -am -Dtest=GlobalExceptionHandlerTest,RagControllerIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` 通过：77 项，失败 0，错误 0 |
| 2026-08-15 00:29 CST | 全量测试夹具阻塞 | 隔离副本 `mvn test` 运行至 core 时仅 `CollectionIdentityResolverTest.mapsKeysInOneRepositoryCall` 失败；生产代码传入去重后的 `Set`，测试按 `List` 严格匹配；仅将测试 stub/verify 适配为 `Iterable`，不修改并行业务实现 |
| 2026-08-15（收尾） | 正式 REST 文档契约复核 | 修正集合创建示例缺少必填 `collectionKey`，并将集合/文档列表参数改为代码实际支持的 `offset/limit` 及过滤参数；同步更新 `docs/rest-api.md` 与 `docs/rest-api-zh-CN.md`；审查计数重置为 `0/3` |
| 2026-08-15（收尾） | 文档修正后的连续收敛审查 | 固定范围连续完成第 1、2、3 轮，分别复核迁移/数据安全、Profile-aware 写入与检索、API/配置/测试/文档；三轮均未发现实质问题，最终计数 `3/3` |
| 2026-08-15（收尾） | 最终仓库门禁 | `git diff --check` 通过；`./scripts/verify-project-docs.sh` 10 项全部通过；确认未新增真实 API key、Token 或密码；确认生产代码没有旧 `rag_vector_store` 写入路径 |
| 2026-08-15（收尾） | 规划状态落档 | 将主规划页从“实施中”更新为“已实施并验证”，补充当前 V29/`embedding_1024` 实施快照并链接本进度账本；未修改实现代码 |
| 2026-08-15（收尾） | 临时验证资源清理 | 删除本次验证创建的 PostgreSQL 容器 `spring-ai-rag-embedding-it-20260815`、`spring-ai-rag-runtime-verify-20260815` 及临时目录；未停止用户现有服务或其他开发者进程 |
| 2026-08-15（提交前最终快照） | `mvn clean compile test-compile` | 全 reactor 成功；API 94 个生产类、Core 208 个生产类与 197 个测试类等均编译通过，仅有既有 deprecated API 警告 |
| 2026-08-15（提交前最终快照） | JSONB/Collection Key 聚焦后端测试 | `OpenApiContractTest`、`RagJsonRecordControllerWebTest`、`JsonRecordServiceTest` 共 42 项通过，失败 0 |

## 4. 代码审查计数

**当前连续无修改计数**：`3/3`，已达到终止条件

审查固定范围：

1. schema、Legacy 数据安全、事务与并发。
2. Profile 模型绑定、写入/缓存/删除、所有检索分支与索引。
3. API/配置兼容、测试充分性、文档和运维可执行性。

在基本集成验证全部通过前，不开始计数。

基本集成验证、隔离服务探针、全量工作区 diff 复核和文档门禁均已通过。文档修正后重新完成固定范围的三轮收敛审查且没有再次修改代码或文档，已达到交付前终止条件。下一步仅执行临时资源清理、提交、推送和工作区干净校验。
