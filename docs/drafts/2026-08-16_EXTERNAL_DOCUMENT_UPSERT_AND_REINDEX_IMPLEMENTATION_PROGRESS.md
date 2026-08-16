# 外部文档幂等更新与重索引实施进度

> 状态：实施已完成，最终验收通过（规划基线已审查通过）
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
| 后端与迁移实施 | 已完成 | 已新增外部同步 DTO、V30、实体字段、freshness 统计、失败状态修复、ExternalDocumentService 和四个 Controller endpoint；导出/导入、Collection clone 已保留 source revision/tombstone；普通 external document 与 JSON record 已统一 advisory lock 命名空间；API/core 增量编译已通过 |
| 后端状态机测试 | 已完成 | `ExternalDocumentServiceTest` 6/6、`ExternalDocumentControllerWebTest` 4/4、`DocumentEmbedServiceTest` 7/7、OpenAPI contract 52 项通过；修复并验证“fresh embedding 仍重复调用 provider”问题 |
| PostgreSQL 迁移测试 | 已完成（环境跳过） | `ExternalDocumentSyncPostgresIntegrationTest` 已加入并能在 Docker 不可用时明确 skip；当前机器 Testcontainers 因 Docker API client 1.32 < daemon 要求 1.40，未执行容器内断言 |
| WebUI 实施 | 已完成 | 文档 API 类型与既有 embed 重试入口已扩展；文档列表展示 externalId/sourceRevision、embedding freshness/processingError，并支持对 stale 文档重试 embedding；Vitest 7/7、生产构建通过，当前源码 Vite Mock Playwright 5/5 |
| WebUI 与长青文档实施 | 已完成 | 双语 REST、架构、项目上下文、开发者参考和测试指南已同步；新增 `scripts/external-documents-e2e.sh`，脚本语法与局部 diff 检查通过 |
| 基本集成验证 | 已完成 | `mvn clean compile test-compile` 已重新通过；任务相关 focused 后端回归最终 `142/142` 通过；PostgreSQL 容器测试因本机 Docker API client `1.32 < 1.40` 明确跳过；前端 `npx tsc -b`、Vitest `178/178`、生产构建和正确 Vite 目标 `BASE_URL=http://127.0.0.1:15175 npm run test:e2e` 的 Mock Playwright `36/36` 通过；项目文档门禁 10 项通过 |
| 实现三轮审查 | 已完成，连续无修改 `3/3` | 修复 live E2E 凭据前置条件后，连续三轮固定范围审查均未发现实质问题，期间未修改业务代码 |
| 最终 diff 与文档门禁 | 已完成 | 文档门禁、脚本语法、`git diff --check` 和最终差异边界核验通过；未使用 stash/reset/checkout，未清理并行工作区修改 |

## 7. 下一步

规划系统性审查已完成：修正后连续三轮无实质问题且未修改文档，计数 `3/3`。
当前已完成后端契约/迁移/服务主体实现，以及导入导出、clone、JSON advisory lock 收尾；
API/core 增量编译已通过。双语长青文档和真实 HTTP external-document smoke 已补齐；
前端 tsc/Vitest/build/Playwright 和真实服务 smoke 已通过；完整 Maven 门禁已因迁移测试夹具的
Flyway target API 调用错误完成最小适配并重新通过，任务相关 focused 后端回归最终 `142/142` 已通过。
PostgreSQL 容器断言仍受本机 Docker API 版本阻塞；前端 `npx tsc -b`、Vitest `178/178`、
生产构建，以及在临时 Vite `15175` 目标上运行的 Mock Playwright `36/36` 均已通过。
基本门槛完成后曾进入实现审查；普通文档导入的稳定身份规范化、Collection 生命周期
并发保护、批量逐项隔离、Collection 删除身份保护和旧 add-document ACL 均已修复并通过
相关 focused 回归。最新重新开始的第 1 轮发现 V30 只按 PostgreSQL 默认 `BTRIM`
规范化普通空格，未覆盖 Java `trim()` 会移除的 ASCII 1–32 控制空白，可能保留两个 API
层等价的逻辑身份。迁移现已构造 Java-compatible trim 字符集，在冲突预检和更新中统一
使用；并将 `generate_series` 的列别名改为显式 `AS codes(code)`，避免迁移解析歧义。
V29→V30 成功与 fail-closed 测试也扩展到制表符/换行。第 2 轮进一步发现
`scripts/external-documents-e2e.sh` 会创建临时 Collection，但前置说明错误地把受限业务
Key 列为可用凭据；现已限定为 root 或不受限 ADMIN Key。随后脚本检查和 Maven 硬门槛已重新
通过；当前实现审查已连续无修改 `3/3`，最终差异边界核验也已完成。

## 8. 规划审查记录

| 时间 | 轮次与范围 | 发现问题 | 处理与结果 |
|---|---|---|---|
| 2026-08-16 11:06 CST | 初始第 1 轮：API、validation、导入兼容、事务后响应 | 旧 export 的 null revision 缺少兼容导入；query 参数约束缺少 `@Validated` 落点；embedding 后响应可能使用旧实体状态 | 已补充 legacy null revision 首次认领、Controller + Service 双层校验、embedding 后 reload；规划已修改，计数归零 |
| 2026-08-16 11:12 CST | 重启后第 2 轮：迁移、版本、导入导出、Embedding freshness、ACL | 进度文档把 multipart/PDF 适配误列为首阶段，与规划正文的非目标冲突；公共写入入口没有明确写出既有 writable collection resolver；metadata-only 更新与默认 `embed=true` 的 freshness miss 语义不完整；opaque revision 不应使用大小比较表述 | 已统一为后续扩展，补充稳定 key 解析后的 writable resolver，明确 freshness miss 时补嵌入，并改为只做 opaque revision 相等/CAS 判断；文档已修改，计数归零 |
| 2026-08-16 12:19 CST | 实现审查第 2 轮：旧 Collection add-document 兼容路径 | 规划要求 external-managed 文档不得通过旧 add-document API 改变 Collection，但实现仍直接修改 `collectionId`，会造成稳定身份命名空间漂移 | `RagCollectionController.addDocument` 对非空 `externalId` 返回 `DOCUMENT_REVISION_CONFLICT`；新增回归测试并重置实现审查计数为 `0` |
| 2026-08-16 12:22 CST | 实现审查第 2 轮：Embedding 失败错误持久化 | provider 异常文本原样写入文档和 embedding state 的 processing error，可能把 API key/token 等敏感信息保存到数据库；仅响应层脱敏不够 | 在 `DocumentEmbedService` 生成失败结果前脱敏截断，并在 `EmbeddingPersistenceService` 入库前再次防御性脱敏截断；补回归断言，计数仍为 `0` |
| 2026-08-16 12:35 CST | 重启后的实现审查第 1 轮：批量 API 与稳定身份兼容路径 | 批量请求的嵌套 `@Valid` 会让单个非法 item 导致整个请求 400，无法按项返回 `PERSISTENCE_FAILED`；Collection 软删除会解绑所有文档，令 external-managed 文档失去 `collectionKey + externalId` 身份 | 已移除 item 级入口 Bean Validation，保留容器限制并由 service 逐项校验；对包含 external-managed 文档的旧 Collection 软删除路径 fail closed，要求先显式 purge；已补测试与双语 REST 说明，计数保持 `0` |
| 2026-08-16 12:42 CST | 重新开始的实现审查第 1 轮：旧 add-document 来源 ACL | 兼容路径在来源文档权限检查前判断 `externalId`，受限 Key 可区分未授权文档类型；来源 `collectionId=null` 时还会跳过 ACL 并允许受限 Key 认领文档 | 已先统一执行 `requireDocumentAccess`，再判断 external-managed 冲突和移动；补未授权 external 文档及 null Collection 文档回归测试，计数保持 `0` |
| 2026-08-16 12:46 CST | 重新开始的实现审查第 1 轮：Collection 生命周期并发 | 软删除先计数再解绑，而外部 upsert/delete 只锁文档身份、不锁 Collection；两事务可交错，使计数保护失效或把文档写入已删除 Collection | 已使用 JPA 行级共享/排他锁：普通外部文档与 JSON record 写事务持 Collection `PESSIMISTIC_READ`，软删除持 `PESSIMISTIC_WRITE`；锁后再次确认 Collection active，并补锁解析/调用测试，计数保持 `0` |
| 2026-08-16 12:50 CST | 实现审查第 1 轮：`embed=false` 响应语义 | 实现会在已有 fresh embedding 时把 `embed=false` 报为 `CACHED`，与已冻结的 `NOT_REQUESTED` 契约不一致，调用方无法判断本次是否执行 embedding | 移除错误的状态覆盖逻辑；新增 fresh-cache 场景回归测试；同步中英文 REST 说明；实现审查计数重置为 `0/3` |
| 2026-08-16 12:56 CST | 实现审查第 1 轮：真实 E2E 清理 | Collection 软删除已对 external-managed 文档 fail closed，但 E2E `EXIT` 清理仍只删除 Collection，会留下临时文档和 Collection | 清理阶段先硬删除脚本创建的普通 external 文档，再删除 Collection；记录 batch 文档 ID；脚本语法检查后实现审查计数仍为 `0/3` |
| 2026-08-16 13:02 CST | 实现审查第 1 轮：V29→V30 外部 ID 规范化 | 服务入口按 trim 语义查找身份，但 V30 只按原始 external_id 预检/建索引，历史 `"id"` 与 `" id "` 可能形成两个逻辑身份 | V30 按 `BTRIM` 预检，先在无冲突时规范化历史值，空白值排除唯一身份；补 V29→V30 成功与冲突 fail-closed 测试，并同步规划；实现审查计数重置为 `0/3` |
| 2026-08-16 13:16 CST | 实现审查第 2 轮：Collection 导入身份规范化 | 普通文档导入直接保存 raw `externalId`/`sourceRevision`，与 API upsert 的 trim 语义不一致；导入 `" id "` 后 upsert `"id"` 可能创建第二条稳定身份 | 导入路径统一 trim，空白值转为 `null`，长度超过 255 直接拒绝；新增 Controller 回归测试；实现审查计数重置为 `0/3`，待硬门槛重新验证 |
| 2026-08-16 13:32 CST | 重新开始的实现审查第 1 轮：V30 与 Java trim 语义 | PostgreSQL 默认 `BTRIM` 只移除普通空格；历史 `"\tid\n"` 与 `"id"` 仍可能在 API 层成为同一身份，却绕过迁移预检和唯一索引 | V30 构造 ASCII 1–32 trim 字符集并用于冲突预检与规范化；迁移测试增加制表符/换行成功和冲突场景；审查计数保持 `0/3` |
| 2026-08-16 13:35 CST | 重新开始的实现审查第 1 轮：迁移 SQL 可执行性 | 新增 `generate_series` 生成 trim 字符集时未显式声明输出列名，可能导致 SQL 解析依赖方言默认行为；规划稿索引谓词仍保留旧 `BTRIM` 示例 | 改为 `AS codes(code)`，规划稿同步为实际 `trim_chars` 和 `external_id <> ''` 语义；代码审查计数保持 `0/3`，需重新通过硬门槛 |
| 2026-08-16 13:39 CST | 重新开始的实现审查第 2 轮：live E2E 凭据前置条件 | 脚本创建临时 Collection，但说明允许受限业务 Key；当前 ACL 会拒绝受限 Key 创建 Collection，导致验收路径误导或直接失败 | 将脚本前置条件改为 root 或不受限 ADMIN Key，并保留受限业务 Key 仅用于已存在 Collection 的 API 调用语义；审查计数重置为 `0/3` |
