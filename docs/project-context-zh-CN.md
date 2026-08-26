# 项目上下文

> [English](project-context.md) | [中文](project-context-zh-CN.md)

> **用途**：为开发者和 Agent 提供稳定、代码支撑的项目认知。
> **最近复核**：2026-08-19。
> 本文记录当前事实；目标设计和未实施能力必须明确标注为规划。

文档总入口：[index-zh-CN.md](index-zh-CN.md)。命令参考：[developer-reference-zh-CN.md](developer-reference-zh-CN.md)。

## 1. 项目定位

spring-ai-rag 是基于 Spring AI 的通用 RAG 框架，目标是：

- 模型无关：Chat 与 Embedding provider 解耦。
- 领域解耦：通过 `DomainRagExtension` 扩展 Prompt 和检索策略。
- 组件化：API、核心实现、Starter、文档处理和 WebUI 分离。
- 可观测：覆盖检索日志、评估、反馈、A/B、告警和指标。
- 可交付：提供 Docker、Helm、WebUI bundle 和发布门禁。

## 2. 模块边界

| 模块 | 职责 |
|------|------|
| `spring-ai-rag-api` | DTO、SPI；不承载业务实现 |
| `spring-ai-rag-core` | RAG 实现、Controller、Advisor、服务和可运行应用 |
| `spring-ai-rag-starter` | Spring Boot 自动配置和嵌入式集成 |
| `spring-ai-rag-documents` | 文档分块、清洗和处理 |
| `spring-ai-rag-webui` | React 管理台 |
| `demos` | basic、component、domain、multi-model 示例 |

系统支持两种运行拓扑：

1. 直接运行 Core 应用。
2. 由其他 Spring Boot 应用引入 Starter。

安全、限流和自动配置变更必须验证两种拓扑。

## 3. RAG 执行链

生产 Chat 使用显式的模式化执行路径：

```text
RagChatController
  -> CollectionRetrievalScopeResolver
  -> ChatCommandMapper
  -> ChatExecutionService
     -> KNOWLEDGE: Spring AI RetrievalAugmentationAdvisor
        + CompositeChatDocumentRetriever
          + ProjectDocumentRetriever
          + 可选 StaticKnowledgeDocumentRetriever
        + ProjectRerankPostProcessor
        + CitationQueryAugmenter
     -> AGENT: Spring AI ToolCallAdvisor
        + KnowledgeSearchTool
        + 可选静态知识 / Runtime Skill / allowlisted HTTP Tools
        + 服务端 ToolContext
     -> PLAIN: 仅 ChatClient + Memory
  -> 按 principal 隔离的 session lease
  -> 原子提交 history + source snapshot + JDBC-compatible Memory 投影
```

关键规则：

- `KNOWLEDGE` 是兼容默认值，每轮固定检索。
- `AGENT` 由支持工具调用的模型决定是否及调用多少次授权知识检索工具；工具 schema
  不能携带 Collection、document 或 credential 范围。
- `PLAIN` 不执行检索，并拒绝检索专用的请求覆盖项。
- Chat 与 Search 共享项目混合检索器，支持 Collection / Document 范围；非空范围解析后
  无文档时必须 fail closed，不能退化为全库检索。
- 可选静态知识在启动时从 classpath/filesystem/JAR 构建 `GLOBAL`、非 embedding 的
  immutable lexical snapshot；KNOWLEDGE 固定组合检索，AGENT 可按需调用
  `searchStaticKnowledge`，PLAIN 不读取。private/tenant 内容仍必须使用带 ACL 的项目文档库。
- Runtime Skill 仅为 AGENT 提供有界、不可信操作说明；`loadSkill` /
  `readSkillReference` 和配置生成的只读 HTTPS 工具共享 request-local budget，真正授权仍由
  server-owned Tool policy、Skill capability gate 和 endpoint allowlist 决定。
- 旧 `QueryRewriteAdvisor`、`HybridSearchAdvisor` 与 `RerankAdvisor` 仍是组件级/
  兼容 API，不是生产 Chat 路径。
- `RagAdvisorProvider` 默认只支持 `KNOWLEDGE + ATTEMPT`。框架把 ATTEMPT provider
  放在 Memory/模式 Advisor 外层；显式 `MODEL_CALL` provider 放在模式 Advisor 内层，
  因此会在 AGENT 每个工具调用轮次执行。provider 的任意原始 order 会映射到不重叠的
  稳定区间。
- `DomainRagExtension` 只在请求显式选择 `domainId` 时生效；省略 domain 不使用“第一个
  Bean”。领域模板只提供 instruction，检索上下文由 RAG/工具注入。旧 `{context}` 模板
  不能直接用于 `AGENT/PLAIN`；`postProcessAnswer/isApplicable` 不参与新 Chat 主链。
- Spring AI Memory 与业务 history 分开存储，但完成 turn 会原子提交。Spring AI `1.1.8`
  JDBC 只保存可恢复的 user/plain assistant 消息；完整工具交换以有界 `toolTranscript`
  写入业务 history metadata，供后续摘要使用。

### Chat 会话与流式协议

- 会话 history、导出、清空、Memory baseline 和活动 lease 都按认证 principal 隔离。
- V32 为 `rag_chat_history` 增加 `owner_principal_id`、JSONB 来源快照与 turn status；
  `rag_chat_session_lease` 提供跨实例 single-flight 和 token fencing。
- 不存在的 session 与属于其他 principal 的 session 都返回 `SESSION_NOT_FOUND`。
- Chat SSE 发送 `content`、`tool_start`、`tool_result`、`sources`、`done` 和 `error`；
  `done` 与 `error` 是互斥终态。
- 客户端取消会 dispose 模型订阅，不持久化未完成 turn；流式 fallback 只允许发生在
  第一个客户端可见事件之前。
- 引用 score 是排序信号，不是经过校准的概率。

### Collection 当前语义

Collection 不是仅用于展示的分类字段，而是已经进入写入、检索和权限链路的知识库边界：

- `rag_collection.id` 是内部 `Long` 主键/外键身份；调用方提供的 `collectionKey` 是推荐
  外部身份。它只能包含 1-128 个可见 ASCII 字符，区分大小写，全局唯一、不可变，软删除后
  仍保持占用。
- `rag_documents.collection_id` 建立 Collection 与 Document 的一对多关系；单个文档最多属于一个
  Collection，也可以不属于任何 Collection。
- Chat 与 Search 支持 `CALLER_VISIBLE`、`ANY_COLLECTION` 和
  `SELECTED_COLLECTIONS`。省略 mode 但提供 Collection 列表时保持旧的 selected 语义；
  mode 与列表都省略时表示 `CALLER_VISIBLE`。受限 API Key 不会通过 mode 扩权：
  caller-visible 和 any-Collection 都收敛为该 Key 的 allow-list。
- `CollectionRetrievalScopeResolver` 负责请求校验、通过 `CollectionIdentityResolver`
  批量且区分大小写地解析 key、应用 `ApiKeyCollectionAccess`，并生成不可变
  `RetrievalScope`。显式 `documentIds` 是额外交集。selected 输入为空或非法返回 `400`；
  受限调用方的未知/未授权 key 返回 `403`；不受限调用方的未知 key 返回 `404`。
- Collection CRUD、恢复、克隆、文档关联、导入导出、文档写入、上传、PDF-to-RAG、
  WebUI 和 API Key 管理均在外部边界使用稳定 key；数据库关系和检索仍使用数字 ID。
- Collection 创建接受可选 `Idempotency-Key`。keyed 首次创建返回 `201`；同 owner
  精确 replay 返回 `200`、replay header 和 Collection 当前状态，不重复写创建审计。
  语义复用冲突，账本不可用时 fail closed。WebUI 每次提交生成一个 UUID，使 Axios
  自动重试复用同一个命令身份。
- WebUI Chat 与 Search 均提供三种模式。selected 模式支持服务端 Collection 搜索、
  每页 50 项、跨页多选和最多 100 个 key；Collections、Documents、Files 和 API Keys
  页面也在外部边界使用 key。

当前边界：

- Collection 的 `embeddingModel`、`dimensions` 当前是管理/导入导出元数据，不会为每个
  Collection 切换 EmbeddingModel；实际写入和查询仍使用全局 Embedding 配置。
- 向量检索会排除 disabled 文档，并要求活动 Embedding Profile 存在新鲜的
  completed 状态。全文检索使用与 Profile 无关的本地 chunk，并要求当前本地索引状态。
- 删除 Collection 会尝试软删除集合；若其中存在 `externalId` 非空的外部托管文档则返回
  `409`，不会执行删除，因为解除关联会破坏
  `collectionKey + sourceNamespace + externalId` 稳定身份。
  没有这类文档时，只解除普通 legacy 文档关联，不会删除文档或 embeddings；解除关联后的文档
  仍可能出现在未限定 Collection 的全库检索中；被删除 Collection 的 key 不可复用。
- `RetrievalScopeSql` 将 Collection 条件直接下推到 Vector 和所有 Full-text SQL：
  任意已归属 Collection 使用 `d.collection_id IS NOT NULL`，selected 使用
  `d.collection_id = ANY (?)` 和 JDBC `bigint[]` 参数；显式 document ID 使用独立的
  `bigint[]` predicate。
- 检索在有效 Collection 并集上计算一次全局 top-k；尚未提供保证每个 selected
  Collection 都贡献结果的 `EACH_COLLECTION` 模式。

详细设计见 [architecture-zh-CN.md](architecture-zh-CN.md)。

### 文件产物与 RAG 桥接

`fs_files` 保存按路径寻址的导入产物，并根据路径前缀合成目录。当前 WebUI 文件管理流程
专用于 PDF：每次导入创建一个 UUID 目录，其中包含原始 PDF、`default.md` 和转换资源。
这些产物可以预览，但还不是可检索的 RAG 文档。

**添加到 RAG** 会读取 `default.md`，按 `pdf-import:{uuid}/default.md` 来源创建或复用
`rag_documents` 记录，关联可选 Collection，并触发 embedding，从而桥接两个层次。不同
UUID 即使内容相同也保留独立文档；内容哈希只负责 embedding 新鲜度。Search API/WebUI
可进一步追溯到文件目录、被索引的 Markdown 和原始 PDF。文档管理的上传路径则把支持的
文本文件直接写入 `rag_documents`，不会创建 `fs_files` 产物。详见
[文件管理、PDF 导入与 RAG 联动](file-management-and-pdf-rag-zh-CN.md)。

### WebUI 浏览器导航契约

WebUI 使用 React Router `BrowserRouter`，生产 basename 为 `/webui`。能够标识稳定页面
上下文、并可通过后端重新加载的数据必须进入 path 或 query parameter，而不能只保存在
组件局部 state。当前可寻址状态包括：

- Search：已提交的 `query`、`hybrid`、`scopeMode` 和重复的 `collectionKey`；
- Files：目录 `path`、预览文件 `file` 和导入时间排序 `sort=asc`；
- Documents：`collectionKey`、`keyword` 和 `page`；
- Chat 会话与 A/B 实验详情：`/chat/{sessionId}`、`/abtest/{experimentId}`；
- Settings、Evaluation 和 Alerts 的活动标签页：`tab`。

因此跨页跳转、浏览器后退/前进和直接打开深链接都可以恢复这些页面上下文。Root API Key
仍只保存在页面内存；整页刷新会先进入 `/webui/unlock`，解锁后返回原始 pathname 和 query
并重新加载数据。API Key、原始文件内容、未提交表单草稿、弹窗、菜单、hover/focus 和上传中
状态不得写入 URL。

新增或修改页面时，凡是用户会合理期望通过后退、前进、刷新或分享地址恢复的状态，都应增加
对应的 Router 状态和 Mock Playwright 往返测试。仅瞬时 UI 状态继续使用局部 state。

## 4. 检索与质量

- Embedding 默认使用 SiliconFlow `BAAI/bge-m3`。
- Embedding 使用不可变的 `rag_embedding_profiles` 身份和固定长度的
  `rag_embeddings.embedding_1024 VECTOR(1024)` 列。文档只有在活动 Profile 的状态为
  `COMPLETED` 且 content hash 与当前文档一致时才算新鲜可服务。
- 支持 vector + full-text 混合检索。
- 生产 profile 推荐启用 query rewrite 和本地 heuristic rerank。
- Goldenset 使用 Precision@K、MRR 和 nDCG。
- `testdata/regression/retrieval-core-v1.json` 使用稳定
  `collectionKey + sourceNamespace + externalId` 身份固化真实检索回归；runner 同时检查质量下限、提交的
  baseline、Collection 泄漏和显式空结果。

小型在线 goldenset 的 baseline 与 quality 组合都达到满分；重排增益由确定性 MRR 测试证明，不能把该样本解释为统计显著提升。

详见 [quality-defaults-zh-CN.md](quality-defaults-zh-CN.md)。

### JSON 结构化记录

JSON record API 将调用者负责的业务数据保存到
`RagDocument.jsonbPayload` / `rag_documents.jsonb_payload`，将调用者提供的自然语言描述
保存到现有 `content` 字段，并以 `retrievalText` 对外暴露。只有 `retrievalText` 会参与
hash、分块、全文索引、embedding 和普通 RAG Prompt 上下文；服务不会自动生成或校验
JSON 与描述是否一致。

JSON record 对外使用 `collectionKey + sourceNamespace + externalId` 作为稳定身份，并解析为内部
`(collectionId, sourceNamespace, documentType=json-record, externalId)` 幂等键。deprecated 的 ID 输入
继续兼容，响应同时返回两种身份。JSON record 不参与普通文档的全局 content-hash 去重，
因此不同 payload 可以拥有相同描述。仅更新 payload 会创建可审计版本，但不会使新鲜
embedding 失效；设计上没有 `payloadHash`。专用搜索 API 在检索排序完成后再批量补充当前
JSONB payload，不把 payload 复制到 embedding metadata，也不自动放入普通聊天 Prompt。
搜索可选 `payloadContains`，使用 PostgreSQL `jsonb @>` 子树包含语义，并下推到所有向量
与全文候选 SQL；V34 提供 partial GIN `jsonb_path_ops` 索引。默认关闭的
`searchJsonRecords` Spring AI Tool 复用同一服务和授权上下文，不接受模型提供的
Collection、SQL 或 JSONPath。

普通非空短文档至少保留一个 chunk；`minChunkSize` 是尽力而为的分块质量目标，不是
静默丢弃文档的准入过滤器。JSON record 固定使用一个 record-level chunk。

<a id="external-document-synchronization"></a>

### 外部文档同步

普通外部文档使用稳定三元身份：
`collectionKey + sourceNamespace + externalId`，并由调用方提供 opaque
`sourceRevision`。`POST /documents/upsert` 保留内部 `documentId`，支持精确重放和默认
严格的 `expectedSourceRevision` CAS，并记录完整版本快照。内容变化会先使检索 freshness
失效，再在同一事务中创建新 generation 的持久化 embedding 任务；metadata、payload 或
来源版本变化不调用 embedding provider。旧 worker 必须通过 generation、hash、chunker
version、Profile 和 lease 提交门，不能覆盖新正文。来源删除使用
`enabled=false` tombstone；之后使用与 tombstone 不同的后续 `sourceRevision` 可以恢复
同一个内部文档。外部 connector 可使用
`POST /documents/batch-upsert`、`GET /documents/by-external-id` 和对应的来源删除端点。
JSON record 仍保持专用的 payload/retrieval-text 语义。

普通外部文本文档同步要求 `collectionKey`，并且它必须解析到真实存在的活动 Collection。
JSON record upsert 仍兼容 deprecated 数字输入，但最终解析到同一个以 key 为准的规范地址。
本地文档的 `collection_id` 可以为 `NULL`，这表示未归属，而不是默认 Collection。数据库中
`source_namespace` 为非空列；请求省略或传空白时规范化为兼容值 `default`，配置允许时
调用方也可以选择其他 namespace。当前标识长度上限为：`collectionKey` 和
`sourceNamespace` 各 128 个字符，`externalId` 255 个字符；后续迁移不得缩短这些上限。

不定义 `__DEFAULT__` 这类特殊 sentinel。`default` 就是兼容 namespace 的字面值，也可以
由调用方显式发送；它不会创建或选择默认 Collection。`sourceNamespace` 是身份/来源对账
分区，不是检索范围或 ACL 维度。当前 Search 和 Chat 会在有效授权的 Collection 范围内跨
namespace 检索。

该三元组是当前投放地址和 ACL 作用域，不表示 `externalId` 在全服务全局唯一。普通
普通 upsert 不改变外部文档 Collection；修改 `collectionKey` 会寻址另一份投放。V44 的
显式 relocation 在双 Collection ACL、source revision CAS、Collection 生命周期 token 和
Sync Run namespace fencing 下原子改变 placement，保留同一 `documentId`、版本历史和派生
行。旧地址写入永久 retired-address ledger，延迟 lookup/mutation 返回稳定 409；反向迁移
由同一事务解除对应 marker。该写能力默认由 feature flag 关闭。

完整请求/响应契约、冲突处理和客户同步最佳实践见
[REST API：外部文档幂等同步](rest-api-zh-CN.md)与
[外部文档同步 Client 指南](external-document-sync-client-guide-zh-CN.md)。

当前 reference client 同时覆盖 webhook/CDC 增量事件和权威全量快照协议。
Sync Run 绑定一个 `collectionKey + sourceNamespace`，只保存 lease hash 与 item fingerprint，
支持 `begin`、有界 `batch-upsert`、`preview-missing`、`complete` 和 `abort`。
V51 为既有 item ledger 增加游标索引，并公开需要 `RAG_READ` 的持久化 receipt 查询；
它返回当前状态摘要和脱敏错误，支持响应丢失恢复，但不返回正文、payload、metadata、
fingerprint、lease/hash 或 provider 信息。终态遍历稳定；active run 只提供最终一致观察，
Client 应在终态后从头复扫。
`ONLINE_CUT + TOMBSTONE` 是安全的全量删除模式；除非 connector 能建立来源一致性 cut，
否则 client 使用 `OFFLINE_MANIFEST + NONE`。只有 preview fingerprint 和删除保护确认通过后，
服务才会对 missing 文档生成 tombstone。begin 之后发生的来源 mutation 由 namespace
mutation sequence 保护，旧快照不能覆盖新状态。

### 本地文档生命周期

本地文档公开 `documentRevision` 作为业务 CAS token；JPA `rowVersion` 仍只用于内部
乐观并发。`PATCH /documents/{id}`、disable、restore 和永久删除都要求预期 revision。
正文 mutation 与业务 revision、完整快照、freshness state 和持久化 job 原子提交；
provider 调用发生在事务之后。正文提交后旧 chunk 立即退出检索，直到 lifecycle
`searchability=READY`。标题、来源、metadata 和 Collection-only 修改立即读取当前主记录，
不会重嵌入。外部托管文档拒绝本地 CRUD，必须通过来源身份和 tombstone 契约操作。

版本历史 API 支持列表、读取、diff 和受控恢复。恢复功能需要显式开启，只接受本地文档的
`FULL` 快照，并创建新的业务 revision 和 `RESTORE` 版本；不会回拨历史，也不会让外部
connector 修改来源拥有的文档。恢复正文会复用正常的 generation fencing；只恢复 metadata
时不会调用 embedding provider。

V43 将本地关键词派生与远程向量解耦。非 `SKIP` 的正文 mutation 会先把当前 chunk
写入 `rag_document_chunks`，并在 `rag_document_local_index_state` 记录 freshness，
再等待远程 provider。旧本地 generation 会立即退出检索，因此 provider 失败不会暴露旧文本。
当本地索引是当前版本、但活动 Profile 的向量仍排队、执行中、未请求或失败时，lifecycle
为 `KEYWORD_ONLY`；只有两条分支都当前时才是 `READY`。`embeddingFresh` 只表示向量
freshness，不能用来判断关键词检索是否可用。`SKIP` 会删除当前本地 chunk，并报告
`NOT_REQUESTED`。

V45 增加共享 `DerivationIntegrityRepository`。单文档 lifecycle/cache、旧 embedding
readiness 和新的 Collection derivation readiness 都核对同一组物理不变量，不再只凭 state
与行数判断 fresh。集合摘要在 SQL 中聚合，详情和 preview 最多返回 100 项。受控 repair
使用 token hash、fingerprint、owner/ACL、lease 和逐项持久账本；local rebuild 与 vector
job enqueue 分开提交，HTTP 不同步循环调用 provider。只读诊断默认开启，有副作用的 repair
默认由 feature flag 关闭。

## 5. 多模型

- 旧 provider Bean 路径仍用于兼容默认模型。
- `ConfiguredChatModelFactory` 按 `provider/modelId` 创建并缓存真实模型实例。
- `ChatModelRouter` 负责显式模型选择、默认模型和 fallback。
- Chat、Settings 和模型对比支持具体模型引用。
- 外部 `models.json` 可以覆盖 YAML 模型配置。
- 每个模型暴露规范化的 `capabilities.streaming` 与
  `capabilities.toolCalling`。省略 streaming 时兼容为 `true`；Tool Calling 必须显式
  配置为 `true`。
- `AGENT` 会拒绝不支持 Tool Calling 的显式模型；默认路由会跳过不满足能力的候选。

详见 [multi-model-external-config-zh-CN.md](multi-model-external-config-zh-CN.md)。

## 6. 数据与 API

### 数据库

- PostgreSQL + pgvector。
- Flyway 当前为 V1–V52。
- V27/V28 负责新增、回填、校验、唯一约束及不可变 Collection 业务 key；V29 增加 JSONB
  结构化记录；V30 增加外部文档同步 schema；V31 在不改写已发布 V30 的前提下规范化
  已存储的外部文档身份；V32 增加按 principal 归属的 Chat history、来源快照、turn
  status 与 session lease；V33 增加持久化 embedding jobs、lease 与活动任务合并索引；
  V34 增加 JSON record payload containment 的 partial GIN 索引；V35 扩展检索诊断；
  V36 增加普通文档 metadata containment 索引；V37 扩展 embedding job 运营字段；
  V38 增加受管评估套件；V39 用原子计数器、并发槽位和 CAS 状态替换显式悲观协调；
  V40/V41 增加文档业务 revision、完整快照、来源 namespace、派生 generation 与
  lifecycle/idempotency schema，并收紧三元身份和活动任务约束；V42 增加权威外部快照
  run、幂等 item ledger 以及 SOURCE/RECONCILIATION 删除标记；V43 增加与 Profile 无关的
  本地关键词 chunk 及独立的本地索引生命周期状态；V44 增加 relocation 幂等响应和永久
  retired-address ledger；V45 增加派生 repair preview/item 控制面；V46 增加按
  owner/session 隔离的 `rag_chat_memory_summary` 表，以前进式历史游标和乐观
  version CAS 支持有界会话摘要；V47 增加按 principal 隔离的 durable Chat turn
  operation、不可变 replay 快照、有界 lease/接管状态，以及供 operation status 与
  业务 history 共用的 opaque turn identity；V48 增加 stable API principal、版本化
  credential、明文 secret 禁写约束、共享 quota bucket 与 legacy ADMIN guard；V49
  增加 principal 级 `RAG_READ` / `RAG_WRITE` 操作能力及数据库约束；V50 增加按
  requester 隔离的 provisioning 幂等账本，只保存 key/fingerprint hash 与结果 metadata，
  从不保存 raw credential；V51 为 Sync Run item ledger 增加按 run/status 的有界游标索引；
  V52 增加按 owner 隔离、具有受约束 Collection 外键的 Collection 创建幂等账本。
- 数据访问层禁止显式 `SELECT ... FOR UPDATE`、`SKIP LOCKED`、JPA
  `PESSIMISTIC_*` 与 PostgreSQL advisory lock。并发写使用条件
  `UPDATE/DELETE ... RETURNING`、`@Version`、唯一约束、lease 和有界重试；普通 DML
  触发的数据库内部短锁不属于该禁令。
- `vector` 必需，`pg_trgm` 推荐，`pg_jieba` 可选。
- `rag_document_chunks` 是全文检索的真相源；V43 会创建 English generated
  `tsvector`，并在对应数据库能力存在时创建 pg_trgm GIN 索引和 `jiebacfg` 表达式索引。
- `rag_document_local_index_state` 为每份文档保存当前本地 generation。它独立于
  embedding Profile 状态，通过条件 DML/generation 检查推进，不使用悲观锁。
- Chat memory、业务历史、检索日志、评估、反馈、A/B、告警、API Key 和文件数据分别持久化。

### HTTP

主要路径为 `/api/v1/rag/**`：

| 区域 | 能力 |
|------|------|
| `/chat`, `/chat/stream` | KNOWLEDGE / AGENT / PLAIN 对话与结构化 SSE |
| `/documents` | 本地文档 CRUD/lifecycle/embedding，以及外部文档幂等同步与原子 relocation |
| `/search` | 混合检索 |
| `/collections` | 知识库、embedding/derivation readiness 与有界派生 repair 控制面 |
| `/evaluation` | 评估与反馈 |
| `/api-keys` | API Key 管理与可选的 principal 幂等 provisioning |
| `/integration-capabilities` | 认证后可读取的版本化运行时集成合同 |
| `/files` | PDF / 文件导入 |
| `/json-records` | JSONB 结构化记录 upsert、检索与详情 |
| `/documents/upsert` | 普通外部文档三元身份、revision CAS 与 tombstone 同步 |
| `/document-sync-runs` | 权威外部快照对账与持久化 item receipt 查询 |
| `/embedding-jobs` | 默认开启的持久化 embedding/reindex 任务 |
| `/retrieval-traces` | 当前调用方可见的检索诊断 |
| `/collections/embedding-readiness` | Collection 嵌入就绪分类 |
| `/v1/models`, `/v1/chat/completions` | 默认关闭的 OpenAI 兼容受控预览 |

契约见 [rest-api-zh-CN.md](rest-api-zh-CN.md) 和 [SSE-PROTOCOL.md](SSE-PROTOCOL.md)。

## 7. 安全与 Collection ACL

当前支持两种兼容运行模式。

独立服务 MVP 模式由 `RAG_ROOT_API_KEY` 显式启用：

- environment root 自动保护 `/api/**`，不依赖 legacy 认证开关。
- root 可通过 `/webui/unlock` 解锁管理台；凭据只保存在页面内存，刷新后重新输入。
- 只有 root 能创建、列出、轮换和吊销业务 Key。
- root 创建的 NORMAL Key 可选择只读 `RAG_READ` 或完整
  `RAG_READ + RAG_WRITE` 数据面能力，可限制 Collection，但不能管理其他 Key；省略
  capabilities 时兼容为完整读写。
- 业务 Key expiry 必填、必须在未来且不设固定最长有效期；raw secret 仅在创建或轮换
  响应中显示一次。
- root 模式只接受 Bearer / `X-API-Key` Header，拒绝 query credential，并禁用旧 ADMIN
  bootstrap/raw 日志分发。

未配置 root 时保留 legacy ADMIN/NORMAL/static-key 行为。

数据库受管调用方由 stable `rag_api_principal` 和版本化 `rag_api_key` credential 组成：

- principal 持有 `ADMIN` / `NORMAL`、Collection ACL、expiry、policy version、可选 quota
  与规范化 operation capabilities；
  credential 只持有 hash、version 和启停状态。
- V48 对既有 Key确定性回填 `principalId=旧 keyId`，历史 `db:{keyId}` owner 因而保持
  可读；之后的 rotation 只替换 credential，稳定 owner 不变。
- 每次认证都执行 credential/principal 权威联查并把不可变 policy snapshot 放入 request；
  吊销提交后其他实例的下一次认证立即拒绝。`last_used_at` 是五分钟粒度的近似审计字段。
- schema 清空 legacy 明文列、移除索引并约束 `api_key IS NULL`；raw secret 只在创建或轮换
  响应中出现一次。
- 管理写入按 principal row 串行化，rotation 版本单调，policy 使用 CAS，legacy 模式由
  singleton guard 防止并发吊销最后一个 ADMIN。
- `backend=postgresql` 时，按 stable principal 使用共享 UTC 固定分钟 quota；rotation
  不重置用量，存储故障 fail closed 返回 `503`。
- 数据库 NORMAL principal 由认证后的中央 capability filter 执行 `RAG_READ` /
  `RAG_WRITE`；读取和显式只读 POST 需要 read，其他 mutation 默认需要 write。能力
  `403` 在 quota 计数前返回；rotation 继承能力，policy CAS 可更新能力。ADMIN、root、
  legacy static 与 auth-disabled 路径保持 unrestricted。
- root 管理的 principal 创建支持可选 `Idempotency-Key`。首次成功返回 `201` 并仅展示
  一次 raw credential；精确重放返回 `200`、当前 credential metadata 与显式
  `rawKey: null`。同一 owner/key 被用于不同有效语义时返回 `409`。后续 rotation 或
  revoke 会改变 replay 返回的当前 credential 投影，但不会使原 secret 可恢复。
- Collection 创建也支持可选 `Idempotency-Key`，但使用独立 V52 账本且不保存响应
  snapshot。replay 返回绑定 Collection 的当前状态和当前文档数；软删除保持可见且绝不
  被逆转。keyed provisioning 关闭或不可用时返回 `503`，不会退化为普通创建。
- `GET /api/v1/rag/integration-capabilities` 提供认证、`no-store`、低敏的运行时合同，
  返回协议版本、当前调用方有效能力与 Collection 范围、数据面行为、可选特性和稳定输入
  上限，其中 `documentSyncRunItemReceipts` 明确表示持久化回执查询是否可用，
  `features.provisioning.collectionCreateIdempotencyKey` 表示 V52 控制面能力。restricted
  ACL 无法完整解析为 Collection key 时以 `503` fail closed。
- Chat、Search、Collection、Document、PDF-to-RAG、评估与后台 worker 都使用统一 ACL
  snapshot 或按 stable owner 重载当前 policy。

这完成了受管 API principal 的多实例基础加固，但不是完整的租户身份平台：OAuth/OIDC、
租户层级、token/cost billing、管理面 recovery 和关闭全部 legacy static/query 兼容仍不在
本轮范围。公网启用还需要部署级 TLS、网络隔离、密钥轮换流程和容量观测。

这些边界及公开启用前置条件见
[openai-compatibility-readiness-zh-CN.md](openai-compatibility-readiness-zh-CN.md)。

## 8. OpenAI 兼容方向

不要混淆两个方向：

```text
已有：spring-ai-rag -> OpenAI-compatible provider
受控预览：OpenAI client / Agent -> spring-ai-rag
```

设置 `rag.openai-compatibility.enabled=true` 后，项目提供 `GET /v1/models`、
`GET /v1/models/{id}` 与 text-only `POST /v1/chat/completions`。model alias 表示
RAG mode、memory 和后端候选链，不保存固定 Collection；请求通过 `rag.scope` 或重复
`X-RAG-Collection-Key` 选择范围，再经过统一 Collection resolver 与 API Key ACL。
非流式和标准 SSE 都复用 transport-neutral `ChatCommand` / `ChatExecutionService`。

该能力默认关闭，当前兼容子集为 `n=1`、text-only messages，不支持 tools、structured
output 或采样参数。原生 `/api/v1/rag/chat/stream` 仍保留项目专用工具、来源和终态事件；
两种 SSE 协议不能混用。

受控预览可用于可信网络集成。stable principal、共享 quota 与即时多实例吊销已经落地，
但这本身不等于公网 production-ready；剩余运营与 legacy 边界仍见 readiness 文档。

当前状态与边界见 [OpenAI 兼容就绪度](openai-compatibility-readiness-zh-CN.md)。

## 9. 1.0 稳定基线

已落地：

- 生产质量默认值和 goldenset。
- Collection ↔ API Key ACL。
- 运行时多模型实例和 UI 选模。
- Maven、Demo、OpenAPI、Helm、Docker 统一为 `1.0.0`。
- WebUI 生产 bundle 内嵌到 Core。
- 中国境内友好的 Docker 构建路径。
- 一键发布验证。
- 请求级 Collection scope 的 OpenAI 兼容受控预览。
- 默认开启的持久化 embedding jobs 与文档生命周期协调器。
- 本地文档 revision CAS CRUD、外部来源三元身份和可运行 reference client。
- JSONB containment / Agent Tool 与版本化真实检索回归门禁。

2026-07-21 完整门禁：

```text
19 passed, 0 failed, 0 skipped
Maven 3213 tests
Vitest 153
Playwright 37
HTTP E2E 66/66
Real LLM 10/10
```

当前可重复验证入口见 [测试指南](testing-guide-zh-CN.md) 和
[1.0 发布门禁](release-checklist-zh-CN.md)；历史计数只作为归档证据保留。

## 10. 明确边界

- 不可变 `1.0.0` source/image Tag 尚未创建，留给正式发布流水线。
- OpenAI 服务端兼容已实现为默认关闭的受控预览，不代表公网或多实例生产就绪。
- 持久化 embedding worker 默认开启；生产需监控容量和 provider 成本。JSON Agent Tool
  仍默认关闭。
- 并发控制不使用应用显式悲观锁；`scripts/verify-no-pessimistic-locks.sh` 是回归门禁。
- OpenClaw 的 `TOOLS.md`、`MEMORY.md`、`memory/`、`HEARTBEAT.md` 等是本地状态，不属于项目文档体系。
- 项目级 Skills 位于 `.agents/skills/`，工作流可以引用本文，但不复制项目事实。

## 11. 真相顺序

发生冲突时按以下顺序判断：

1. 当前代码和迁移。
2. `docs/` 中的 live reference / guide。
3. `AGENTS.md` 和 `CLAUDE.md` 的入口规则。
4. `docs/drafts/` 中的当前活跃规划。
5. `docs/drafts/archive/` 中的历史规划与实施记录。
6. 本地 Agent 状态文件。
