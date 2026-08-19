# Collection Key 实施进度

> 对应规划：[2026-08-15_COLLECTION_KEY_IMPLEMENTATION_PLAN.md](2026-08-15_COLLECTION_KEY_IMPLEMENTATION_PLAN.md)
> 开始日期：2026-08-15
> 当前状态：ACL 修复后的最终硬门槛全部通过，正在执行连续三轮无修改收敛检查，计数器为 0

## 1. 执行约束

- 保留 `Long id` 作为数据库内部主键和内部外键。
- 新增 `String collectionKey` 作为外部稳定业务键。
- key 必须由调用方显式提供；允许 UUID 或业务字段拼接。
- 合法范围：1-128 个 `U+0021` 至 `U+007E` ASCII 字符；区分大小写；不 trim、不归一化、不截断。
- key 全局唯一、创建后不可变、软删除后不释放。
- 不能回退或覆盖工作区中其他并行修改；禁止 `git stash`、`git reset --hard`、`git checkout --`。

## 2. 工作区基线

开始实施时已确认工作区存在未提交的 embedding/retrieval 改动、V25/V26 迁移及多份正式/草稿文档。Collection Key 只修改本能力涉及的文件，并与这些变更共存。

当前已占用迁移：

- `V25__embedding_profile_expand.sql`
- `V26__remove_unused_rag_vector_store.sql`

Collection Key 迁移使用 `V27`、`V28`；如实施前发现新冲突，两个迁移文件整体顺延，不改写他人迁移。

## 3. 实施阶段

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | 代码盘点、规划和设计冻结 | 已完成 |
| 2 | 进度记录、V27/V28、实体、Repository、key 校验和统一 Resolver 基础 | 已完成 |
| 3 | Collection 生命周期 API、冲突错误和 API Key ACL | 已完成 |
| 4 | Document/Chat/Search/PDF/批量/SSE 入口 | 已完成 |
| 5 | WebUI、脚本、正式中英文文档 | 已完成 |
| 6 | 后端编译、相关集成测试、前端 tsc/构建/Mock Playwright | 已完成 |
| 7 | 基本门禁通过后的连续三轮代码收敛检查 | 进行中，计数器 0/3 |
| 8 | 最终验证和交付 | 待开始 |

## 4. 测试门禁

基本门禁必须全部通过后，才开始三轮固定范围代码检查：

```text
mvn clean compile test-compile
mvn -pl spring-ai-rag-core -Dtest='*Collection*,*ApiKey*,OpenApiContractTest,RagControllerIntegrationTest' test
cd spring-ai-rag-webui && npm run test:run && npm run build
```

必要时追加 PostgreSQL/Testcontainers 迁移和端到端集成测试。三轮检查使用计数器：发现问题并修改代码则归零，只有连续三轮无修改、无问题才结束。

## 5. 关键决策记录

- 新 key 定位接口使用 query/body，不把任意 key 放进 path segment。
- 旧 ID 输入继续可用并标记 deprecated；同时传 ID 和 key 时必须一致。
- API Key 数据库暂时继续存内部 ID；外部 `allowedCollectionKeys` 解析后写入既有 ID 列。
- 显式空 collection scope 必须返回 400，不能退化为全局检索。
- `PdfImportController` 的 `collection` 参数继续表示文件虚拟目录前缀；RAG 归属使用新增 `collectionKey`。

## 6. 变更日志

### 2026-08-15

- 完成规划文档三轮无修改检查。
- 完成实施前工作区、迁移编号和 Collection/Document/Chat/Search/PDF/ACL 入口盘点。
- 建立本进度文档，开始实现基础契约。
- 完成 V27/V28、`RagCollection.collectionKey`、唯一约束映射、ASCII Bean Validation 和 `CollectionIdentityResolver`。
- Collection 创建/克隆 Service 开始使用 `saveAndFlush`，仅将命名唯一约束冲突转换为 `DUPLICATE_RESOURCE`。
- 首次编译发现并修复 PostgreSQL 驱动类型不可见问题；同时对并行 JSONB 改动做了最小 `ConnectionCallback` 类型消歧，`mvn ... compile` 已通过。
- 完成 Collection by-key CRUD、恢复、克隆、导入导出、文档关联响应，以及 Document/Chat/Search/PDF/批量入口的 key 解析。
- 完成 API Key `allowedCollectionKeys` 外部契约、受限 key 解析和 ID/key 集合一致性校验；显式空 scope 采用 fail-closed 400。
- 修正 V27/V28 contract 迁移与不可变 trigger 的执行顺序：允许首次 `NULL -> legacy key` 回填，已有 key 仍禁止更新。
- `CollectionUpdateRequest` 不暴露可写 key；ID/key mismatch 统一作为 400；软删除 key 继续保留。
- `OpenApiContractTest`、API 模块 test-compile 和 Collection 创建组件 8 项测试已通过。
- 更新旧 clone 测试夹具，所有克隆测试改为显式提供目标 `collectionKey`，controller 测试调用实际 HTTP 映射方法。
- 后端 Collection/Chat/Search/API Key 首批专项回归已串行通过：155 项测试、0 failure、0 error。
- 补齐 key 的 1/128 字符边界、空白/Unicode/控制字符/129 字符拒绝、大小写保持、Resolver active/deleted/mismatch、ACL 防枚举和显式空 scope 测试。
- 补齐 Collection create duplicate 预检查、命名唯一约束竞态 409、非目标数据库异常保留、clone 显式目标 key 测试。
- 补齐 API Key `allowedCollectionKeys` 创建、ID/key set 比较、404/403、内部 ID 持久化、响应映射和 rotation scope 测试。
- 修复更新 DTO 对 `collectionKey` 的静默忽略：JSON 中一旦出现该字段即由 controller 明确返回 400；创建 DTO 同步声明 key 必填和 OpenAPI 长度/pattern。
- 批量 ID -> key 映射改为按请求 ID 顺序稳定输出；遗留 ACL 缺失 ID 记录诊断且不扩大权限。
- 新增专项测试、Web 层集成测试及 `mvn ... test-compile` 已全部通过。
- 新增真实 PostgreSQL/Testcontainers 验收：V27 expand、V28 contract、legacy 候选冲突避让、ASCII/长度/大小写、软删除不释放、SQL trigger 不可变和并发唯一性全部通过。
- OpenAPI 严格契约已通过：创建 key 必填及 1-128/pattern、update schema 不暴露 key、by-key/clone 路由、跨域 key 字段、旧 ID deprecated 和 409/403 声明。
- WebUI 的 Collection 创建/显示、Documents、Chat、Search、Files 和 API Key ACL 已改用 `collectionKey(s)`；数字 ID 仅保留在兼容类型和未迁移的内部操作。
- 补齐 Search、Files、Chat、SSE 和 API Key ACL 的 Collection Key 单测，相关 42 项测试全部通过。
- WebUI 全量单测 170 项、TypeScript/生产构建和 Mock Playwright 40 项全部通过；Playwright 已覆盖 Search/Files 的实际 `collectionKey(s)` 请求。
- 文档交叉验证发现并补齐 `POST /collections/clone`：请求体使用 `sourceCollectionKey` 和新 `collectionKey`，旧 `/{id}/clone` 继续兼容；Controller、MockMvc 与 OpenAPI 专项测试已通过。
- 常规 E2E、真实 LLM smoke 与三个 k6 脚本已改为每次运行显式生成唯一 key，并在 Collection、Document、Search、Chat 调用中优先使用 key；脚本语法检查已通过。
- 正式 REST/架构/上下文/配置/测试/SSE/versioning 文档已同步双标识契约、by-key 路由、显式空 scope、ACL 防枚举、V27/V28 和验收命令。
- 交叉检查并行 JSONB 实现时发现其外部请求仍只接受数字 Collection ID；已补充 `collectionKey(s)`、保留 deprecated ID、统一 Resolver/ACL、响应 key 映射、OpenAPI 和 key-only E2E 脚本。
- JSONB key-only upsert/search、ID/key mismatch、显式空 key scope、Controller 绑定和 OpenAPI 聚焦 clean 测试已通过：42 项、0 failure、0 error。
- 数字 Collection 生命周期路由及 Search/Document/PDF 的兼容 ID 参数开始补齐 OpenAPI deprecated 标记和契约断言。
- 常规与真实 LLM E2E 的文档创建已直接携带 `collectionKey`；常规 E2E 的 Search、Chat、SSE 也显式使用 `collectionKey(s)`，避免验收脚本意外退化为全局范围。
- 首次最终 clean 编译发现 `RagSearchController` 的 OpenAPI `@Parameter` 缺少 import；已补齐并从 clean 聚焦测试重新验证。
- 修复后 clean OpenAPI/JSONB 聚焦测试 43 项通过；全 reactor `mvn clean compile test-compile` 通过。
- Collection/API Key/JSONB/OpenAPI/Chat/Search/PDF 专项回归 421 项通过，PostgreSQL 16 Testcontainers Collection Key 迁移与数据库约束集成测试 3 项通过。
- 前端最终门禁重跑通过：Vitest 170 项、TypeScript/生产构建、独立 4180 预览上的 Mock Playwright 40 项全部成功；预览进程已停止。
- 使用独立 PostgreSQL/pgvector 容器和后端 18082 端口完成真实运行时冒烟：V1-V29 启动迁移、create/by-key get/update、不可变字段 400、重复 key 409、key-only 文档与 JSON record、clone、export/import、软删除后不可复用及 restore 全部通过。
- 并行工作区补充了健康检查真实表名修正（`rag_collections` -> `rag_collection`）；最终树重新执行全 reactor clean 编译/test-compile 成功，相关健康/就绪测试 14 项通过。
- 收敛检查发现 import 直接保存 Collection 会绕过命名唯一约束到 409 的转换、restore 更新未限定软删除状态，以及规划文档与已执行 V27/V28 的长度函数/约束名/发布编排不一致；已统一修复，检查计数器重置为 0。
- 修复后重新执行 `mvn clean compile test-compile` 成功；Collection Service/Controller、MockMvc 和 OpenAPI 聚焦测试 134 项通过，Collection/API Key/JSONB/OpenAPI/Chat/Search/PDF 宽范围回归 423 项通过。
- 文档门禁 10/10、shell/k6 语法和 `git diff --check` 通过；Testcontainers 使用本机兼容参数 `-Dapi.version=1.40` 与 `TESTCONTAINERS_RYUK_DISABLED=true`，PostgreSQL 16/pgvector Collection Key 测试 3 项通过。
- 使用隔离数据库和 18082 端口验证修复后的真实链路：创建 200、active restore 404、首次 import 200、重复 import 409 且错误码为 `DUPLICATE_RESOURCE`、软删除 200、删除后 restore 200；临时后端和数据库已停止。
- 收敛检查第 1 轮未发现问题；第 2 轮发现 Spring Boot 内嵌 WebUI 仍引用旧 hash 产物，API Key Mock Playwright 也只建模 `allowedCollectionIds`，未实际覆盖新建受限 key 时提交 `allowedCollectionKeys`。该问题会导致发布 JAR 中的前端继续使用旧外部契约，因此检查计数器重置为 0。
- API Key Mock Playwright 已改为实际选择 `sample-collection`，断言创建请求/响应中的 `allowedCollectionKeys`，同时保留响应 `allowedCollectionIds` 以覆盖兼容显示。下一步通过既有 Maven `webui` profile 重建并同步内嵌静态资源，然后重跑全部硬门禁。
- Maven `webui` profile 已完成 TypeScript、生产构建和 40 个静态资源同步，`dist/` 与 Spring Boot 内嵌目录逐文件一致；定向 Playwright 首次运行已验证请求断言通过，仅因结果框与后台列表同时显示相同 key 触发 strict locator 歧义，已将可见性断言限定到一次性密钥结果框后重跑。
- 收窄 locator 后 API Key 定向 Mock Playwright 1/1 通过；临时 Vite preview 已停止。开始重跑全量 Vitest、TypeScript/生产构建、全部 Mock Playwright、后端 clean 编译及专项回归。
- 前端完整硬门禁通过：Vitest 170/170、`tsc -b` 与 Vite 生产构建成功、Mock Playwright 40/40；重建后的 `dist/` 与 Spring Boot 内嵌静态目录逐文件一致，临时 preview 已停止。
- 全 reactor `mvn clean compile test-compile` 通过。首次专项回归误用 core 单模块命令，运行时加载本地仓库中的旧 API JAR，导致 Collection/JSONB 新 DTO 方法出现 `NoSuchMethodError` 并连带阻断 WebMvc context；源码已在 reactor clean 编译中成功，现改用 `-pl spring-ai-rag-core -am` 将 API/documents 纳入同一 reactor 后重跑，不将该命令依赖错误计为实现缺陷。
- 修正 reactor 范围后专项回归通过：API Collection Key 校验 3 项、Core 481 项，失败 0、错误 0；覆盖 Collection、API Key ACL、JSONB、OpenAPI、Chat、Search、PDF 及控制器集成。
- PostgreSQL 16/pgvector Testcontainers 验收 3/3 通过，真实执行并校验 Flyway V1-V29、V27/V28 分阶段迁移及 Collection Key 数据库约束；健康检查 WIP 与内嵌 WebUI 路由补充回归 21/21 通过。
- 项目文档门禁 10/10、`git diff --check`、静态目录逐文件一致性和文本差异密钥扫描均通过。
- 启动正式收敛检查前再次复核 API Key 响应兼容边界，发现 WebUI 范围显示先判断旧 `allowedCollectionIds`，key-only 新响应会误显示为“全部集合”；已改为 `allowedCollectionKeys` 优先、ID 仅作旧响应降级，并让 Vitest/Playwright 覆盖不含旧 ID 的 key-only 响应和原始 POST body。检查计数仍为 0，前端与静态资源门禁需重跑。
- key-only 响应修复后的前端完整门禁再次通过：Vitest 170/170、`tsc -b` 与生产构建成功、Mock Playwright 40/40；API Key 场景明确断言创建 POST body 只含 `allowedCollectionKeys` 且结果页可在没有旧 ID 时显示业务 key。最新 40 个构建资源已重新同步，临时 preview 已停止。
- 最终前端/静态资源快照后再次执行全 reactor `mvn clean compile test-compile` 成功，最新内嵌 WebUI 路由测试 7/7 通过。下一步冻结文件并按固定范围执行连续三轮无修改检查；无问题轮次仅在会话中输出 UTC 时间和总结，不再改写本进度文档。
- 收敛检查第 1 轮发现 by-key Collection CRUD/文档操作以及 Document/PDF 单 key 写入先做全局 key 查询、后做 ACL，受限 API Key 可通过 404/403 差异探测未授权 key 是否存在，违反规划第 8 节防枚举顺序；检查计数重置为 0。
- 已增加受限 ID 范围内的单 Collection active/including-deleted 解析入口，Collection by-key Controller 统一使用 ACL-aware 解析，Document/PDF 单 key 写入改为复用 `ApiKeyCollectionAccess.resolveCollectionIds`；不受限未知 key 保持 404，受限未知或未授权 key 统一 403。已一次性补充 Resolver、ACL 和 Collection Controller 覆盖，下一步执行编译及完整硬门禁。
- ACL 修复后的聚焦回归 120 项通过；最终全 reactor `mvn clean compile test-compile` 通过，宽范围回归 API 15 项、Core 657 项通过，PostgreSQL 16/pgvector Collection Key 验收 3/3 通过。
- 最终前端与发布产物门禁通过：Vitest 170/170、TypeScript/生产构建、Mock Playwright 40/40，`dist/` 与 Spring Boot 内嵌 WebUI 均为 40 个文件且逐文件一致。
- 并行健康检查修复与内嵌 WebUI 回归 21/21 通过；项目文档门禁 10/10、Git whitespace 和 added-line secret scan 通过。自此冻结工作区，连续三轮无问题检查只在会话中记录，不再修改本进度文档。
- 收敛检查重新开始后，第 1 轮无问题；第 2 轮发现 `ChatRequest.collectionKeys` 的 OpenAPI 描述把显式空列表误写为全量范围，与实际 400 fail-closed 契约冲突，前端兼容类型也仍把旧单值字段引导到数字 `collectionIds`。已统一修正 API 描述和前端开发注释，检查计数重置为 0。
- 描述修正后的完整硬门禁再次通过：全 reactor clean compile/test-compile、API 15 + Core 657、PostgreSQL 3/3、Vitest 170/170、TypeScript/生产构建、Mock Playwright 40/40、文档 10/10，以及 40 个 WebUI 文件的内嵌产物一致性。工作区重新冻结，收敛检查从 0/3 开始。
