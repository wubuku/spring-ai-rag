# Collection Key 实施进度

> 对应规划：[2026-08-15_COLLECTION_KEY_IMPLEMENTATION_PLAN.md](2026-08-15_COLLECTION_KEY_IMPLEMENTATION_PLAN.md)
> 开始日期：2026-08-15
> 当前状态：实施中

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
| 5 | WebUI、脚本、正式中英文文档 | WebUI 已完成，待脚本与正式文档 |
| 6 | 后端编译、相关集成测试、前端 tsc/构建/Mock Playwright | 前端门禁已通过，待最终后端门禁 |
| 7 | 基本门禁通过后的连续三轮代码收敛检查 | 待开始 |
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
