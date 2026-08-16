# 外部文档幂等更新与重索引实施进度

> 状态：实施进行中（规划基线已审查通过）  
> 日期：2026-08-16  
> 配套规划：[外部文档幂等更新与重索引实施规划](2026-08-16_EXTERNAL_DOCUMENT_UPSERT_AND_REINDEX_IMPLEMENTATION_PLAN.md)

## 1. 目标

为调用方提供基于稳定外部身份的文档同步能力。当外部文档新增、更新或删除时，RAG 服务能够幂等地维护同一个内部文档，安全地重新切分、生成嵌入并替换索引，同时向调用方暴露可验证的新鲜度与失败状态。

本进度文档只跟踪当前任务，不是长青架构真相源。实现完成后，稳定 API 契约和客户最佳实践将同步到既有中英文长青文档。

## 2. 执行硬规则

- 规划文档连续三轮系统性审查无实质问题且期间无修改后，才开始修改生产代码。
- 实施完成后，先通过后端相关集成测试、`mvn clean compile test-compile`、服务启动，以及前端 TypeScript、生产构建和核心 Mock Playwright。
- 基本验证全部通过后，再执行实现代码连续三轮无修改审查；任何代码修复都会将实现审查计数归零。
- 不执行 `git stash`，不回退、不覆盖、不丢弃其他开发者的工作区修改。
- 并行修改若阻塞测试，只做解除阻塞所需的最小测试夹具适配，不修改对方业务实现。
- 每次关键进展先更新本文件，再进入下一阶段。

## 3. 工作区并行修改基线

开始本任务时，工作区已有未提交的多集合检索改动，涉及 API DTO、检索作用域、Controller、Repository、WebUI 和测试，并已有以下并行规划/进度文档：

- `docs/drafts/2026-08-16_MULTI_COLLECTION_RETRIEVAL_IMPLEMENTATION_PLAN.md`
- `docs/drafts/2026-08-16_MULTI_COLLECTION_RETRIEVAL_IMPLEMENTATION_PROGRESS.md`

本任务不得修改或删除上述文档，也不得将多集合检索业务改动归入本任务。`JsonRecordService` 等少量潜在重叠文件必须基于当前工作树增量编辑，并在最终 `git diff` 中逐段复核。

## 4. 已确认的当前实现

- 普通文档只有创建、按内部 ID 查询/删除和单独嵌入接口，没有按 `collectionKey + externalId` 更新、查询或删除的通用 API。
- JSON structured record 已实现稳定外部身份、PostgreSQL 事务 advisory lock、`CREATED / UPDATED / UNCHANGED`、内容变化重嵌入和 payload-only 更新不重嵌入。
- 活动 Embedding Profile 的检索 SQL 只接受 `COMPLETED` 且状态 `content_hash` 等于文档当前 `content_hash` 的向量，因此旧内容向量不会在文档更新后继续被召回。
- Embedding 持久化先完成全部 provider 调用和向量校验，再在短事务内原子替换当前 Profile 的 chunks；提交时会校验文档版本、内容 hash 和 enabled 状态。
- 当前失败记录逻辑在旧 `COMPLETED` 状态存在时直接返回。旧向量虽已被 freshness 条件排除，但新内容的失败状态不会落库，是本次必须修复的可观测性缺口。
- 版本历史目前主要由 JSON record 和 Collection clone 路径写入；普通文档创建和嵌入路径没有自动形成完整内容版本。
- Collection export 已包含 `externalId`，但未包含来源版本；普通文档 import 直接保存，不走统一幂等服务。
- API Key 已支持完整 RAG 能力和 Collection ACL。本次要求每个 Connector 使用独立业务 Key，并限制到目标 Collection；不在本任务扩展 write-only 权限模型。

## 5. 已冻结的实施决策

- 新增稳定入口 `POST /api/v1/rag/documents/upsert`，身份为 `collectionKey + externalId`。
- 数据库将非空 `external_id` 的唯一范围统一为 Collection，不再按 `documentType` 分割。
- 新增调用方提供的 opaque `sourceRevision`，以及可选 `expectedSourceRevision` CAS；不暴露 JPA `@Version` 作为同步版本。
- 相同来源版本和相同内容是幂等重放；相同来源版本却内容不同返回 `409 Conflict`。
- 默认同步嵌入；本阶段不引入 outbox/worker，也不让核心服务主动抓取任意外部 URL。
- 内容变化时保留同一内部 `documentId`，写版本快照并把当前内容置为待索引；事务提交后执行嵌入。
- 仅标题、来源、metadata 或来源版本变化且内容不变时，不调用 embedding provider。
- 新内容嵌入失败时保留新文档数据、保留旧向量物理行但令其不可检索，并持久化当前内容 hash 对应的 `FAILED` 状态。
- 增加按稳定身份查询、批量 upsert 和幂等源删除。源删除使用 tombstone：禁用文档并记录删除时间；旧内部 ID 删除接口继续硬删除。
- PDF/文件上传在本阶段不增加外部身份参数；后续适配必须在文本提取后委托统一 service，
  不另造第二套更新语义。
- WebUI 本阶段展示外部身份、来源版本、新鲜度/失败状态并支持重试，不建设外部数据源调度中心。

## 6. 当前阶段

| 阶段 | 状态 | 结果 |
|---|---|---|
| 代码库与文档探索 | 已完成 | 已确认普通文档、JSON record、Embedding、版本、导入导出、ACL、OpenAPI、WebUI 和测试主路径 |
| 规划初稿 | 已完成 | 已冻结 API、数据模型、并发、失败恢复、兼容、迁移和验收方案 |
| 规划三轮审查 | 已完成 | 修正后连续无修改 `3/3`，已允许实施 |
| 后端与迁移实施 | 进行中 | 已新增外部同步 DTO、V30、实体字段、freshness 统计、失败状态修复、ExternalDocumentService 和四个 Controller endpoint；导出/导入、Collection clone 已保留 source revision/tombstone；普通 external document 与 JSON record 已统一 advisory lock 命名空间；`mvn -pl spring-ai-rag-core -am -DskipTests compile` 已通过 |
| 后端状态机测试 | 已完成 | `ExternalDocumentServiceTest` 6/6、`ExternalDocumentControllerWebTest` 4/4、`DocumentEmbedServiceTest` 7/7、OpenAPI contract 52 项通过；修复并验证“fresh embedding 仍重复调用 provider”问题 |
| PostgreSQL 迁移测试 | 已完成（环境跳过） | `ExternalDocumentSyncPostgresIntegrationTest` 已加入并能在 Docker 不可用时明确 skip；当前机器 Testcontainers 因 Docker API client 1.32 < daemon 要求 1.40，未执行容器内断言 |
| WebUI 实施 | 已完成 | 文档 API 类型与既有 embed 重试入口已扩展；文档列表展示 externalId/sourceRevision、embedding freshness/processingError，并支持对 stale 文档重试 embedding；Vitest 7/7、生产构建通过，当前源码 Vite Mock Playwright 5/5 |
| WebUI 与长青文档实施 | 未开始 | 后端契约稳定后开始 |
| 基本集成验证 | 进行中 | 任务相关后端测试与前端门禁已通过；待执行 `mvn clean compile test-compile`、真实服务启动/HTTP smoke、文档门禁 |
| 实现三轮审查 | 未开始 | 计数 `0/3` |
| 最终 diff 与文档门禁 | 未开始 | 只确认本任务增量，不清理并行修改 |

## 7. 下一步

规划系统性审查已完成：修正后连续三轮无实质问题且未修改文档，计数 `3/3`。
当前已完成后端契约/迁移/服务主体实现，以及导入导出、clone、JSON advisory lock 收尾；
API/core 增量编译已通过。下一步补齐后端测试，先完成后端基本验证，再进入 WebUI。

## 8. 规划审查记录

| 时间 | 轮次与范围 | 发现问题 | 处理与结果 |
|---|---|---|---|
| 2026-08-16 11:06 CST | 初始第 1 轮：API、validation、导入兼容、事务后响应 | 旧 export 的 null revision 缺少兼容导入；query 参数约束缺少 `@Validated` 落点；embedding 后响应可能使用旧实体状态 | 已补充 legacy null revision 首次认领、Controller + Service 双层校验、embedding 后 reload；规划已修改，计数归零 |
| 2026-08-16 11:12 CST | 重启后第 2 轮：迁移、版本、导入导出、Embedding freshness、ACL | 进度文档把 multipart/PDF 适配误列为首阶段，与规划正文的非目标冲突；公共写入入口没有明确写出既有 writable collection resolver；metadata-only 更新与默认 `embed=true` 的 freshness miss 语义不完整；opaque revision 不应使用大小比较表述 | 已统一为后续扩展，补充稳定 key 解析后的 writable resolver，明确 freshness miss 时补嵌入，并改为只做 opaque revision 相等/CAS 判断；文档已修改，计数归零 |
