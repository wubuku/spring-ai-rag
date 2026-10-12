# WeKnora 参考项目调研与借鉴规划

> 状态：活跃调研草案；本文件记录候选方向，不代表已经承诺实施。
>
> 调研日期：2026-10-12
>
> 参考项目：`/Users/yangjiefeng/Documents/wubuku/Tencent/WeKnora`
>
> 参考项目基线：`97796c637aa53713edc846bd0a39470d687f0fdf`

## 1. 调研结论

WeKnora 最值得借鉴的不是某个单独算法，而是把 RAG 处理、异步任务、引用、权限和运维
组织成可恢复系统的设计方式。

对 `spring-ai-rag` 最有价值的方向依次是：

1. 文档处理阶段状态树、attempt、重试、死信和卡死恢复。
2. 请求级引用注册表、引用授权和流式输出安全。
3. 自适应分块、分块结果校验和分块诊断。
4. 在现有混合检索之上补上下文扩展、连续块合并和覆盖度诊断。
5. 声明式 API Key route policy 与默认拒绝自检。
6. 统一 Parser SPI、大文件流式解析和稳定资源句柄。

本调研文件只完成参考项目调研和借鉴建议整理；它不代表已经修改代码、数据库迁移、测试或正式长青文档。

## 2. 调研范围与方法

### 2.1 参考项目范围

重点阅读了以下 WeKnora 模块和文档：

- RAG 编排：`internal/application/service/chat_pipeline/`、
  `internal/application/service/session_knowledge_qa.go`。
- 文档处理：`internal/application/service/knowledge_process.go`、
  `internal/application/service/knowledge_span_tracker.go`、
  `internal/application/service/knowledge_housekeeping.go`。
- 检索和分块：`internal/infrastructure/chunker/`、检索 Repository、
  `internal/searchutil/`、`website-docs/03-features/04-chunking.md`、
  `website-docs/03-features/05-retrieval-engines.md`。
- 引用和流式：`internal/modelcontext/`、`internal/stream/`、
  `website-docs/02-architecture/04-rag-pipeline.md`。
- 解析器和文件：`internal/infrastructure/docparser/`、`docreader/`、
  `website-docs/03-features/03-document-parsing.md`、
  `website-docs/03-features/21-file-access.md`。
- 权限和客户端：`internal/middleware/api_key_gate.go`、`internal/router/rbac.go`、
  `internal/router/rbac_selfcheck_test.go`、`cli/AGENTS.md`、
  `cli/internal/mcp/tools.go`。

### 2.2 本项目对照范围

对照了当前 `spring-ai-rag` 的：

- `docs/project-context-zh-CN.md`
- `docs/architecture-zh-CN.md`
- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/ChatExecutionService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/`
- `spring-ai-rag-core/src/main/java/com/springairag/core/rag/`
- `spring-ai-rag-core/src/main/java/com/springairag/core/resource/`
- `spring-ai-rag-core/src/main/java/com/springairag/core/filter/ApiCapabilityFilter.java`
- `spring-ai-rag-documents/src/main/java/com/springairag/documents/chunk/HierarchicalTextChunker.java`

## 3. 当前能力盘点：不应重复建设

`spring-ai-rag` 已经覆盖了 WeKnora 的很多基础能力，后续不应为了“对齐参考项目”而
重复实现：

- **模式化 Chat 主链**：`KNOWLEDGE`、`AGENT`、`PLAIN`，并以 Spring AI Advisor、
  Tool Calling 和 `ChatExecutionService` 组织执行。
- **混合检索**：Vector + Full-text 并行、超时、降级、检索结果融合和诊断。
- **查询与排序**：query rewrite、bounded multi-query、rerank、文档合并和结果追踪。
- **范围与安全**：Collection scope、API Principal、Collection ACL、server-owned
  Tool Context，以及非空范围解析后的 fail-closed 语义。
- **Embedding Job 可靠性**：持久化任务、generation、lease、attempt、CAS/过期提交门、
  取消和失败处理。
- **外部文档同步**：稳定身份、source revision CAS、tombstone、幂等更新和旧 worker
  防止覆盖新内容。
- **基础分块**：标题继承、表格保护、句子切分、overlap，以及短文档保留至少一个 chunk。
- **质量与观测**：goldenset、Precision@K/MRR/nDCG、引用校验、Micrometer、usage、
  retrieval trace、反馈和告警。

结论是：优先补“过程模型、协议安全和可诊断性”，而不是重写现有主链。

## 4. 值得借鉴的方向

### 4.1 P0：统一文档处理阶段状态树

#### 参考设计

WeKnora 的文档入库不是一个不可见的长任务，而是多个明确阶段：解析、分块、索引、图片
多模态、摘要、问题生成、图谱和 Wiki。每次处理都有独立 attempt，并通过 SpanTracker
记录根 span、阶段 span、子 span、状态、耗时、错误和取消关系。

参考位置：

- `website-docs/02-architecture/03-document-pipeline.md`
- `internal/application/service/knowledge_span_tracker.go`
- `internal/application/repository/knowledge_span_repo.go`
- `website-docs/02-architecture/05-async-tasks.md`

WeKnora 还提供了重新解析、取消、死信回写和 housekeeping。处理卡死时，不只依赖 worker
自己的 `defer`，而是由巡检任务根据状态、心跳和队列存在性收尾。

#### 本项目现状

本项目的 `EmbeddingJobRepository` / `EmbeddingJobExecutor` 已经很好地解决了嵌入任务的
lease、attempt、generation 和提交 fencing 问题，但它的视角主要是“嵌入任务”，不是完整
的“文档处理运行”。解析、分块、嵌入、索引 freshness 和最终完成状态还缺少统一的阶段树。

#### 建议借鉴方式

不替换现有 Embedding Job。建议在其上增加文档处理运行模型：

```text
DocumentProcessingRun(attempt)
  ├─ parsing
  ├─ chunking
  ├─ embedding
  ├─ indexing/freshness
  └─ finalizing
```

建议至少具备：

- `pending → parsing → chunking → embedding → finalizing → completed` 状态流转。
- `failed`、`cancelled`、`stale` 等终态及稳定错误码。
- 每次重新处理生成新的 attempt，并使旧 attempt 不能写回新版本。
- 阶段开始、结束、耗时、重试次数、错误码、降级原因和最后心跳。
- 查询进度、查询失败详情、取消、重试、重新处理和 dead-letter。
- stuck-task 检测与有界自动恢复。
- WebUI 的阶段时间线和失败位置，而不是单一“处理中”。

#### 约束

参考项目的持久化队列部分使用了 `FOR UPDATE SKIP LOCKED`。本项目的硬性规则禁止显式
悲观锁、`SKIP LOCKED` 和 advisory lock，因此只能借鉴状态模型、attempt 语义、死信和
巡检，不复制其锁实现；并发认领继续使用条件写入、CAS、版本号、lease 和有界重试。

### 4.2 P0：请求级引用注册表与流式引用安全

#### 参考设计

WeKnora 使用请求级 source registry，将内部身份映射为只在本次回答有效的短句柄：

- `cN`：chunk；
- `dN`：document；
- `bN`：knowledge base；
- `wN`：web source。

模型只允许引用当前请求中已经注册且实际提供给模型的 source。输出阶段由服务端把有效
句柄展开成公开引用；未知句柄、历史句柄、越权句柄不会被接受。

参考位置：

- `internal/modelcontext/sources.go`
- `internal/modelcontext/citations.go`
- `internal/modelcontext/registry.go`
- `website-docs/02-architecture/04-rag-pipeline.md` 的引用与 SSE 小节

流式输出还处理了标签跨 chunk 被切断的情况，并确保引用事件先于最终完成事件；断线后可以
重放事件并恢复引用列表。

#### 本项目现状

本项目已有：

- `ChatSource.citationId`；
- `RetrievalTraceCollector` 的 source 聚合和引用编号；
- `CitationQueryAugmenter` 注入 `[S1]` 资料；
- Chat SSE 的 `sources`、`done`、`error` 终态约束；
- 来源快照和引用校验。

但 `[S1]` 仍主要是提示词中的编号资料，不是严格的请求级模型句柄协议。当前
`CitationValidator` 也明确只解析 `[S1]`，不做 claim-level coverage。

#### 建议借鉴方式

新增轻量的请求级 `SourceRegistry`，并保留现有 DTO 兼容层：

1. 检索结果进入 registry 时分配 opaque citation handle。
2. 模型上下文使用 handle，不暴露内部数据库 ID 作为可操作身份。
3. 只接受当前请求、当前授权范围、当前检索结果注册过的 handle。
4. 服务端解析输出后重新检查 Collection ACL、文档启用状态和来源版本。
5. 增加可跨 SSE 分片工作的 citation decoder。
6. 发送 `sources` 后再发送 `done`；错误终态不得同时发送成功终态。
7. 补充 page、段落、Markdown offset、文件路径等 source locator。
8. 对未注册、历史残留或越权引用统一 fail closed。

目标不是简单把 `[S1]` 改名为 `[c1]`，而是建立“模型引用不能越过服务端授权边界”的协议。

### 4.3 P0：自适应分块、结果校验与分块诊断

#### 参考设计

WeKnora 的分块路径是：

```text
文档画像 → 策略选择 → 分块 → 结果校验 → 逐级回退
```

支持 `auto`、`heading`、`heuristic`、`recursive` 和兼容旧行为的 `legacy`。它还保护
代码块、表格、链接、图片等区域，并使用 table header tracker 把表头带到后续表格块。

参考位置：

- `website-docs/03-features/04-chunking.md`
- `internal/infrastructure/chunker/strategy.go`
- `internal/infrastructure/chunker/profiler.go`
- `internal/infrastructure/chunker/validator.go`
- `internal/infrastructure/chunker/header_tracker.go`
- `internal/infrastructure/chunker/fenced_code.go`

Validator 会拒绝空输出、超大单块、碎片过多、所有块都远小于目标值或明显超过长度预算的
结果，然后回退到下一策略。

#### 本项目现状

`HierarchicalTextChunker` 已具备标题递归、标题上下文、表格保护、句子切分和 overlap，
是可以继续演进的基础。但当前策略选择、保护区、表格 header 继承和输出质量诊断还比较
轻量。

#### 建议借鉴方式

引入 `ChunkingProfile`，使分块行为可配置、可记录、可复现：

```text
ChunkingProfile
  -> strategy selection
  -> split
  -> validate
  -> fallback
  -> diagnostics
```

逐步增加：

- `auto / heading / heuristic / recursive` 策略及稳定版本号。
- Markdown code fence、公式、链接、图片和表格保护区。
- 表格 header tracking，保证拆分后的数据行仍能解释列含义。
- `parentChunkId`、`previousChunkId`、`nextChunkId`。
- `contextHeader`、标题 breadcrumb 和 source locator。
- parent-child chunk：子块用于召回，父块用于上下文回答。
- 空输出、过多碎片、超长块、单块覆盖大文档等 validator。
- 不落库的 chunk preview/debug API，供 WebUI 对比策略和结果。

不要把 `minChunkSize` 变成静默丢弃短文档的门槛；普通短文档仍应至少保留一个可检索块。

### 4.4 P1：RAG 阶段化编排，但保留 Spring AI 主链

#### 参考设计

WeKnora 用 `Plugin + EventManager + PipelineBuilder` 表达查询理解、问题改写、并行检索、
重排、网页抓取、合并、过滤、结构化分析、上下文组装和流式生成。它支持按请求动态跳过
某些阶段，并记录阶段级日志和降级原因。

参考位置：

- `internal/application/service/chat_pipeline/chat_pipeline.go`
- `internal/application/service/session_knowledge_qa.go`
- `website-docs/02-architecture/04-rag-pipeline.md`

#### 建议借鉴方式

不要把整个 Go responsibility chain 搬到 Java。当前项目已有 Spring AI Advisor 生命周期，
完整替换会产生两套重叠抽象。

建议在 `ChatExecutionService` 外围增加轻量：

- `RagExecutionPlan`：本次请求有哪些阶段、阶段顺序和跳过条件。
- `RagExecutionContext`：请求不可变输入、授权范围、候选结果、诊断和 runtime handle。
- 阶段事件：`started`、`completed`、`skipped`、`degraded`、`failed`。

这样既能保留 Spring AI Advisor，又可以统一记录检索、重排、引用和模型调用的阶段耗时。

### 4.5 P1：检索融合后的上下文组装

WeKnora 的价值不只在多引擎，而在于检索结果在重排后还会进行：

- parent chunk 扩展；
- 前后邻居扩展；
- 连续 chunk 合并；
- overlap 去重；
- 文档覆盖度控制；
- MMR 或其他多样性控制。

参考位置：

- `internal/application/service/chat_pipeline/merge.go`
- `internal/application/service/chat_pipeline/rerank.go`
- `website-docs/02-architecture/04-rag-pipeline.md`
- `website-docs/03-features/05-retrieval-engines.md`

本项目已有 Vector + Full-text、weighted RRF、rerank、`ProjectDocumentJoiner` 和
`RetrievalTraceCollector`，建议只补最有价值的部分：

1. 明确 vector/full-text score contract，并补 RRF 测试矩阵。
2. rerank 后按 document/parent/neighbor 做受预算约束的上下文扩展。
3. 对连续块进行稳定合并和 overlap 去重。
4. 将候选池、最终 top-k、文档覆盖、上下文字符/token 预算写入 diagnostics。
5. 依据 goldenset 和真实请求数据决定是否引入 MMR，而不是预先增加复杂度。

暂不建议仅为了“支持多向量数据库”引入 WeKnora 那样的多引擎 registry；当前 PostgreSQL
单引擎路径足以支持下一阶段质量建设。

### 4.6 P1：统一 Parser 契约与大文件流式处理

WeKnora 的 `docreader` 以统一请求和结果模型支持多种解析器，并通过 `ReadStream` 分批
返回大型扫描 PDF 的图片，避免一次性消息和内存峰值。

参考位置：

- `website-docs/03-features/03-document-parsing.md`
- `docreader/proto/docreader.proto`
- `internal/infrastructure/docparser/engine_registry.go`
- `internal/infrastructure/docparser/grpc_parser.go`

如果本项目后续扩展 DOCX、PPTX、Excel、HTML、OCR 或远程解析器，建议先在 Java 内建立：

```text
DocumentReader
DocumentReadResult
ImageRef
SourceBlock
ParserAvailability
```

统一输出 Markdown、图片引用、metadata、source locator、解析器版本和降级原因。暂不需要
立即引入完整 Python 微服务；先稳定 SPI 和结果模型。

### 4.7 P1：声明式 API Key route policy

WeKnora 将 API Key capability、知识库 allow-list 和具体 route policy 分离，并通过测试
自检路由与 policy 是否一致。未声明的路由默认拒绝，避免新增 Controller 后遗漏授权。

参考位置：

- `internal/middleware/api_key_gate.go`
- `internal/router/rbac.go`
- `internal/router/rbac_selfcheck_test.go`
- `website-docs/03-features/01-tenant-auth.md`

本项目已有 `ApiCapabilityFilter`、`RAG_READ/RAG_WRITE` 和 Collection ACL。后续可增加：

- `ApiRoutePolicy`：route、method、required capability、scope 和写入敏感度。
- 启动或测试阶段的 route-policy coverage 自检。
- 未声明数据面 route 的默认 deny。
- `retrieve/chat/ingest/manage_collections/run_evaluations` 等更细 capability。

应保留当前 Collection allow-list 和 server-owned scope，不把用户传入的 scope 直接当成授权。

### 4.8 P2：稳定资源句柄

WeKnora 使用 `resource://` 句柄隔离逻辑资源身份与物理存储路径，再按渠道选择鉴权代理、
短期 grant 或预签名 URL。

参考位置：

- `internal/modelcontext/resources.go`
- `website-docs/03-features/21-file-access.md`

本项目已经有 `ResourceCatalog` 和 protected resource 方向。后续可补稳定句柄、owner/
Collection 绑定、短期 grant、ACL 撤销后的 fail-closed 以及文档移动后的引用失效语义。

## 5. 不建议当前阶段照搬

### 5.1 完整 Go 插件框架

保留 Spring AI Advisor 作为底层扩展机制，仅增加轻量 execution plan/context。否则会出现
Advisor、插件链和 ChatExecutionService 三套执行抽象。

### 5.2 完整多租户组织与 RBAC 产品模型

当前 Collection、Principal 和 ACL 已经解决框架主要边界。只有当产品明确进入协作平台路线
时，才评估组织共享、角色继承、资源 owner 和跨空间授权。

### 5.3 多向量数据库和多进程部署拓扑

WeKnora 支持多个向量引擎和 Go/Python 多进程协作，这是其产品范围的结果，不是当前项目
必须具备的能力。当前优先级应放在 PostgreSQL 检索质量、处理稳定性和可观测性。

### 5.4 图谱、Wiki、Agent Sandbox、BrowserSkill 和 IM 多渠道

这些能力可以作为未来产品线扩展，但成本和边界都明显超出本次 RAG 框架能力对齐，不应因为
参考项目存在就提前引入。

### 5.5 WeKnora 的锁实现

特别不复制 `FOR UPDATE SKIP LOCKED`、advisory lock 和显式悲观锁。并发设计必须遵守本项目
已有的数据访问规则。

## 6. 建议实施路线

以下顺序按收益、风险和与现有代码的耦合度排序：

### Stage 1：文档处理运行模型

- 设计 processing run、attempt、stage 和 terminal status。
- 复用现有 Embedding Job 的 generation/lease/fencing 语义。
- 补进度查询、取消、重试、死信和 stuck-task 处理。
- 增加数据库迁移、单元测试和 PostgreSQL 集成测试；先提供稳定 HTTP/生命周期摘要和 Mock 可消费字段，
  任务时间线 WebUI 后置为独立批次，避免同时耦合后端状态机与前端设计语言。

### Stage 2：引用协议与 SSE 安全

- 建立 request-local SourceRegistry。
- 将模型引用和实际证据、授权范围绑定。
- 增加跨分片 citation decoder 和 `sources → done` 顺序测试。
- 对历史引用、未注册引用、ACL 变化和文档删除做 fail-closed 测试。

### Stage 3：分块 Profile 与 Validator

- 明确策略、配置版本和文档画像。
- 增加代码/公式/表格保护、表头继承和 parent-child 元数据。
- 增加 chunk preview/debug API 和分块质量指标。
- 使用固定 fixture 验证策略回退和输出稳定性。

### Stage 4：检索后上下文组装

- 增加 parent/neighbor 扩展、连续块合并和 overlap 去重。
- 将候选池、覆盖度和上下文预算写入 diagnostics。
- 用 goldenset 验证是否需要 MMR 或更强 coverage 策略。

### Stage 5：API Key route policy

- 将路由 policy 从路径推导逐步迁移为声明式注册。
- 增加 route-policy coverage 自检和默认 deny。
- 再拆分检索、聊天、入库、集合管理和评估 capability。

### Stage 6：Parser SPI 与资源句柄

- 按真实文件类型需求建立 `DocumentReader` SPI。
- 对大型 PDF/图片增加流式产物处理。
- 将资源引用、临时 grant 和访问代理统一起来。

## 7. 验收原则

任何一个方向真正进入实施前，都必须满足：

- 先形成自包含的三阶段规划，并按项目交付工作流完成规划收敛。
- 代码、迁移、测试和相关中文文档同步交付。
- 不以 review、静态分析或首次手测替代测试证据。
- 新增并发路径通过 `scripts/verify-no-pessimistic-locks.sh`。
- 真实检索或真实 LLM 行为变更时，使用 goldenset、PostgreSQL 集成测试和必要的真实 E2E。
- 任何引用、资源或 scope 相关变更都要覆盖越权、历史数据和 fail-closed 场景。
- 本草案进入实施后，应拆分为独立 plan/progress；完成、取消或替代后再按规则归档。

## 8. 参考项目文件索引

### WeKnora 架构与流程

- `website-docs/02-architecture/03-document-pipeline.md`
- `website-docs/02-architecture/04-rag-pipeline.md`
- `website-docs/02-architecture/05-async-tasks.md`

### WeKnora 特性

- `website-docs/03-features/01-tenant-auth.md`
- `website-docs/03-features/03-document-parsing.md`
- `website-docs/03-features/04-chunking.md`
- `website-docs/03-features/05-retrieval-engines.md`
- `website-docs/03-features/21-file-access.md`

### 本项目对应入口

- `docs/project-context-zh-CN.md`
- `docs/architecture-zh-CN.md`
- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/ChatExecutionService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/`
- `spring-ai-rag-core/src/main/java/com/springairag/core/rag/`
- `spring-ai-rag-documents/src/main/java/com/springairag/documents/chunk/HierarchicalTextChunker.java`

## 9. 会话内调研过程回顾

本节保留本次会话实际完成的调研路径，避免以后只看到结论而丢失判断依据。

### 9.1 初始状态核验

- 核验了本项目工作区，开始调研时无未提交改动。
- 核验了参考项目工作区，开始调研时无未提交改动。
- 记录了参考项目当前 HEAD：`97796c637aa53713edc846bd0a39470d687f0fdf`。
- 记录了参考项目 HEAD 时间：2026-10-12 18:10:29 +0800。
- 检查了参考项目的目录结构，确认它包含 Go 主服务、Python `docreader`、CLI、前端、
  MCP、桌面端、部署配置和多种存储适配器。
- 检查了参考项目的 `AGENTS.md`，发现只有 `cli/AGENTS.md` 是额外的局部协作约束。

### 9.2 先看项目文档，再回到实现

先阅读了 WeKnora 的架构和特性文档，再使用实现、测试和前端文件交叉核对，重点避免把
“设计文档宣称的能力”误判为“当前代码已经实现的能力”。核验过的主文档包括：

- `website-docs/02-architecture/03-document-pipeline.md`
- `website-docs/02-architecture/04-rag-pipeline.md`
- `website-docs/02-architecture/05-async-tasks.md`
- `website-docs/03-features/01-tenant-auth.md`
- `website-docs/03-features/03-document-parsing.md`
- `website-docs/03-features/04-chunking.md`
- `website-docs/03-features/05-retrieval-engines.md`
- `website-docs/03-features/21-file-access.md`

随后对照了本项目的：

- `docs/project-context-zh-CN.md`
- `docs/architecture-zh-CN.md`
- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/ChatExecutionService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/`
- `spring-ai-rag-core/src/main/java/com/springairag/core/rag/`
- `spring-ai-rag-core/src/main/java/com/springairag/core/resource/`
- `spring-ai-rag-core/src/main/java/com/springairag/core/filter/ApiCapabilityFilter.java`
- `spring-ai-rag-documents/src/main/java/com/springairag/documents/chunk/HierarchicalTextChunker.java`

### 9.3 调研结论的收敛过程

最初容易被 WeKnora 的功能广度吸引，例如多向量引擎、Wiki、图谱、Agent Sandbox、
BrowserSkill 和 IM 渠道。对照本项目后，判断这些不是当前 RAG 框架最应该复制的部分。

真正收敛出来的是四个系统性问题：

1. 文档处理过程是否可恢复、可解释、可取消。
2. 模型引用是否被请求级证据和服务端授权严格约束。
3. 分块结果是否可验证、可预览、可调试。
4. 检索和生成各阶段是否能观察耗时、降级和部分失败。

因此最终建议没有要求重写当前 Spring AI 主链，而是围绕已有能力补状态、协议和诊断层。

## 10. 参考项目实现级观察

### 10.1 RAG Pipeline 的真实执行形态

WeKnora 的事件链不是固定的单函数调用，而是根据请求是否需要知识检索、网页搜索和历史，
动态组装阶段。典型阶段顺序是：

```text
LOAD_HISTORY
  → MEMORY_RECALL
  → QUERY_UNDERSTAND
  → CHUNK_SEARCH_PARALLEL
  → CHUNK_RERANK
  → WEB_FETCH
  → CHUNK_MERGE
  → FILTER_TOP_K
  → DATA_ANALYSIS
  → INTO_CHAT_MESSAGE
  → CHAT_COMPLETION_STREAM
```

如果没有知识库检索目标，也没有 Web Search，请求会降级为纯 Chat，而不是无条件执行完整
检索链。

`internal/application/service/chat_pipeline/chat_pipeline.go` 中的核心抽象是：

- `Plugin.OnEvent(ctx, eventType, chatManage, next)`；
- `Plugin.ActivationEvents()`；
- `EventManager` 维护 `eventType → []Plugin`；
- 注册顺序决定同一事件的责任链顺序；
- 插件可以在 `next()` 前做前置处理，也可以在 `next()` 后做后置处理；
- `PluginError` 区分“没有检索结果”和“真正失败”，例如 `ErrSearchNothing` 不一定是
  系统错误，而可能触发兜底回复。

这说明值得借鉴的是“阶段契约和可跳过性”，不是照搬 Go 的责任链实现。对本项目更适合的
等价模型是 `RagExecutionPlan + RagExecutionContext + Spring AI Advisor`。

### 10.2 SSE、断线续传和停止控制

WeKnora 的生成线程不直接依赖当前 HTTP 连接：

1. 每个请求创建独立 `EventBus`。
2. `AgentStreamHandler` 订阅事件并写入 `StreamManager`。
3. `StreamManager` 可以使用内存实现或 Redis 实现。
4. HTTP SSE 层以约 100ms 轮询事件并发送给客户端。
5. `continue-stream` 从指定消息的 offset 重放事件，生成任务与 SSE 连接解耦。
6. 连续的 answer/thinking/reflection 增量在重放时合并，避免长回答重放成数万帧。
7. 独立的 stop watcher 轮询停止事件，即使原 SSE 连接已经断开也能取消生成。
8. Redis 事件带 TTL；事件写入、读取、活动运行标记和控制事件各有明确边界。

参考实现：

- `internal/stream/memory_manager.go`
- `internal/stream/redis_manager.go`
- `internal/handler/session/stream.go`
- `website-docs/02-architecture/04-rag-pipeline.md` 的 SSE 与断线续传小节

本项目已经有 session lease、终态互斥和来源快照，但这套“生成与连接解耦、事件可重放”的
语义仍值得作为未来 SSE 增强方向。尤其是 sources 必须在 done 之前可重放且顺序稳定。

### 10.3 Source Registry 的严格边界

WeKnora 的 `internal/modelcontext` 不只是把来源编号，它还区分“可寻址”与“可引用”：

- 历史消息、目录或工具参数里的 ID 只是导航身份，不自动成为当前证据。
- 只有当前工具结果实际提供过的来源才会进入 citable 集合。
- `cN` 和 `wN` 可以作为引用；`dN` 和 `bN` 只能用于需要文档或知识库范围的工具参数。
- 模型不能自行发明句柄，也不能把句柄当作新的数据库 ID。
- 注册表只在一次 assistant response 生命周期内有效，包括 Agent 的多个 tool round。
- 输出解码时只展开已知、已授权且有证据支持的句柄。

这比“给检索结果按顺序编号”多了一层安全语义，是本项目 Citation Registry 设计必须保留
的核心。

### 10.4 检索引擎分层和故障语义

WeKnora 的检索实现分层为：

```text
Repository
  → KVHybridRetrieveEngine
  → CompositeRetrieveEngine
  → RetrieveEngineRegistry
```

具体观察：

- Repository 只需要声明向量、关键词等能力并实现统一保存、删除和检索契约。
- Composite 按能力把 vector/keyword 请求路由到可用引擎，写操作可以广播。
- Registry 同时支持环境级引擎和数据库绑定的实例级引擎。
- 多存储检索按 `(VectorStoreID, owner tenant)` 分组并发执行。
- 多组 fan-out 有上限，默认每组有超时；部分引擎失败不会无界拖住整次请求。
- 分数跨引擎时先做归一化，混合检索在上层统一使用 rank-based RRF。
- 启动时不可用的存储支持按需 rehydrate，而不是永久缺失。
- rehydrate 有单次构建超时、singleflight、失败冷却和 generation 检查，避免旧构建结果
  覆盖更新后的注册表。

参考实现和说明：

- `website-docs/03-features/05-retrieval-engines.md`
- `internal/application/repository/retriever/`
- `internal/infrastructure/`

本项目目前不需要复制多引擎 Registry，但应借鉴其中的能力声明、超时、部分失败、统一 score
contract 和按需恢复思想；这些都可以在现有 `HybridRetrieverService` 内部实现。

### 10.5 异步任务、死信和卡死恢复

WeKnora 的文档处理任务至少体现了以下可靠性约束：

- 解析、摘要、问题生成、图谱、Wiki 等任务使用不同队列和 worker 池。
- Payload 带 attempt，旧 attempt 的任务不能影响新 attempt。
- 最后一次重试失败时才创建 dead-letter，避免瞬时错误制造大量死信。
- dead-letter 保留任务 payload、推断出的作用域和截断后的错误信息，便于运维查询和未来重放。
- 死信回调使用条件更新，只有文档仍处于对应 processing 状态时才标记失败。
- housekeeping 定期扫描超时的 pending/processing/finalizing 记录；只有队列中不存在覆盖任务
  时才收尾，避免把仍在运行的任务误判为卡死。
- 任务检查器提供队列深度、worker 心跳、分页查看、cancel、run_now、delete 和归档清理。

参考位置：

- `internal/router/task.go`
- `internal/router/task_inspector.go`
- `internal/application/service/knowledge_housekeeping.go`
- `website-docs/02-architecture/05-async-tasks.md`

对本项目的直接启示是：Embedding Job 的失败状态还可以被纳入统一 processing run，但不需要
替换其现有的 CAS/lease 实现。

### 10.6 分块配置、保护区和预览能力

WeKnora 的默认分块参数和策略具有明确的产品语义：

- 默认 `chunk_size=512`、`chunk_overlap=80`。
- parent-child 模式默认 parent 约 4096、child 约 384。
- `auto` 根据文档画像在 heading、heuristic、recursive 等策略间选择。
- 代码块、表格、链接、图片等保护区不会被任意边界切断。
- 表格块会追踪表头，避免后续数据块失去列名。
- Validator 检查空结果、过多 tiny chunks、单块覆盖大文档、超过长度预算等异常。
- `POST /api/v1/chunker/preview` 只预览，不写数据库；实现对输入、返回 chunk 数和执行
  时间设有上限，避免调试接口反过来拖垮服务。

预览实现可参考：

- `internal/handler/chunker_debug.go`
- `frontend/src/api/chunker/index.ts`
- `frontend/src/types/chunker.ts`

这里的“预览接口 + validator + 诊断”比单独增加一种切分算法更值得优先借鉴。

### 10.7 Parser Registry 与流式读取

WeKnora 的 `docreader` 是一个具有统一协议的解析服务：

- `Read` 返回 Markdown、图片引用、metadata 和 source blocks。
- `ReadStream` 先发 metadata，再逐帧发图片，避免扫描 PDF 的图片全部堆在一个 unary 消息中。
- `SourceBlock` 用 Markdown 的 Unicode offset 对应回原文件 locator，供引用定位使用。
- Go 侧有 engine registry，解析器可以报告支持的文件类型、可用性和不可用原因。
- gRPC 支持 token、TLS/mTLS、健康检查和消息大小限制。
- 简单文本格式可以在主服务原生处理，复杂 PDF/OCR 使用外部 reader。

参考位置：

- `docreader/proto/docreader.proto`
- `docreader/parser/`
- `internal/infrastructure/docparser/engine_registry.go`
- `internal/infrastructure/docparser/grpc_parser.go`
- `website-docs/03-features/03-document-parsing.md`

对本项目的建议仍是先抽象 Java `DocumentReader` SPI，不直接复制 Python sidecar；但
`source locator` 和大文件流式产物应在 SPI 设计中预留。

### 10.8 API Key、资源访问、CLI 和 MCP

#### API Key 与 RBAC

WeKnora 的 `internal/router/rbac.go` 对新增 route 的权限轴有很明确的判断：

- 资源是否有 creator；
- 操作是私有状态还是会影响他人；
- 新建资源使用角色门槛；
- 已有资源的修改使用 owner-or-admin；
- 租户级基础设施使用 admin；
- API Key route policy 由单独 registry 管理，并有 route self-check。

它的价值是把“调用者是谁”“能访问哪个资源”“这条路由需要什么能力”分开，不是简单按
HTTP method 猜权限。

#### 资源访问

WeKnora 默认返回 `resource://`，客户端通过凭证访问代理；只有明确允许时才签发短期 grant
或对象存储预签名 URL。消息级文件访问还会验证资源确实被该消息引用，避免历史消息变成永久
资源授权。

参考位置：`internal/modelcontext/resources.go`、`internal/handler/files.go`、
`website-docs/03-features/21-file-access.md`。

#### CLI 与 MCP

`cli/AGENTS.md` 和 `cli/internal/mcp/tools.go` 展现了值得借鉴的接口原则：

- CLI 使用 hard-required flags，不依赖 TTY 交互才能完成关键动作。
- `status` 与 `check` 分离，分别表达低成本读取和可能触发实际检查的操作。
- `wait` 使用有界轮询、退避和稳定退出码。
- MCP 只暴露人工挑选的只读工具，避免把内部管理面原样暴露给 Agent。
- CLI 参数先服从 SDK/API 契约，再设计命令行形态。

本项目若未来面向外部业务服务、Agent 或运维自动化，应优先借鉴这些契约和安全原则，暂不
复制完整命令面。

### 10.9 前端产品体验

WeKnora 前端把“异步处理可见性”做成了工作区能力，而不是只在后端日志中体现：

- 上传任务面板展示进度、失败和进行中的任务。
- 知识库页面支持处理状态刷新、文件排序和来源定位。
- 系统页面提供运行时任务分页和运维观察入口。
- Chat 中提供 citation popover、附件预览和资源缓存。
- 分块预览 API 有对应的前端类型和调用层，便于调试召回质量。

可参考的前端文件：

- `frontend/src/components/upload-tasks/UploadTasksPanel.vue`
- `frontend/src/components/upload-tasks/ProgressRing.vue`
- `frontend/src/stores/uploadTasksState.ts`
- `frontend/src/types/knowledgeProcess.ts`
- `frontend/src/api/chunker/index.ts`
- `frontend/src/composables/useChatCitationPopover.ts`
- `frontend/src/views/system/runtimeTaskPagination.ts`
- `frontend/src/views/knowledge/KnowledgeBase.vue`

本项目 WebUI 已有文档、搜索、Chat、Collection 和任务相关页面基础，但如果实施 Stage 1–3，
应优先借鉴这类“处理阶段时间线、失败重试、分块预览、引用预览”的局部体验，不要直接照搬
整个 WeKnora 前端信息架构。

## 11. 调研后形成的最终判断矩阵

| WeKnora 能力 | 本项目现状 | 借鉴结论 |
|---|---|---|
| 事件驱动 RAG 插件链 | Spring AI Advisor + ChatExecutionService 已有稳定主链 | 借鉴阶段契约，不复制责任链 |
| 动态跳过检索阶段 | `KNOWLEDGE/AGENT/PLAIN` 已有显式模式 | 保留显式模式，再引入轻量 plan |
| Vector + keyword + RRF | 已有混合检索、加权 RRF、超时和诊断 | 补 score contract、上下文 merge、coverage |
| 多引擎 Registry | 当前 PostgreSQL/pgvector/全文已足够 | 暂不扩展，借鉴能力矩阵和故障语义 |
| 文档阶段树 | Embedding Job 有任务可靠性，但缺统一 processing run | P0 借鉴 |
| attempt/span/dead-letter | 部分已有 generation、lease、attempt 和失败处理 | 统一到文档处理状态树 |
| 请求级 Source Registry | 已有 `ChatSource`、编号引用和来源快照 | P0 升级为严格 handle 协议 |
| SSE 断线续传 | 已有受控 SSE 和终态约束 | 借鉴事件持久化、重放、coalesce、stop watcher |
| 自适应分块 | 已有标题、表格、句子和 overlap | P0 增加 profile、validator、保护区、preview |
| parent/neighbor 上下文 | 已有 document joiner，粒度扩展有限 | P1 增加受预算约束的上下文组装 |
| Parser Registry | 当前 PDF 导入有明确服务入口 | P1 抽象统一 Reader SPI 和 source locator |
| API Key route policy | 已有读写 capability 和 ACL | P1 增加声明式 route registry/self-check |
| `resource://` 资源句柄 | 已有 ResourceCatalog 和受保护资源方向 | P2 对齐稳定句柄和短期 grant |
| Wiki/图谱/FAQ/多模态 | 当前不是主线框架能力 | 由真实产品需求触发，不提前复制 |
| CLI/MCP | 当前不是同等产品面 | 未来借鉴契约、安全和只读原则 |
| 前端任务/引用/分块工作台 | 当前有 WebUI 基础，但体验可继续增强 | 先做局部任务时间线和预览 |

## 12. 当前落档状态与后续使用方式

- 本文件是 `docs/drafts/` 下的活跃调研草案，不是当前代码事实的替代来源。
- 本文件没有改变 `spring-ai-rag` 的实现、迁移、测试或运行配置。
- 真正开始某个 Stage 前，应从本文件拆出独立 plan，并按
  `docs/delivery-workflow-zh-CN.md` 完成规划、实施、基本集成硬门槛和验收。
- 某项能力落地后，应把仍然有效的事实提炼到 `docs/architecture-zh-CN.md`、
  `docs/project-context-zh-CN.md`、`docs/rest-api-zh-CN.md` 或对应专题文档；本草案随后
  按生命周期移动到 `docs/drafts/archive/`。
- 本轮最终只新增/修改文档，未修改生产代码、迁移、配置、测试或 WebUI：
  `docs/drafts/WEKNORA_REFERENCE_RESEARCH.md`、
  `docs/drafts/WEKNORA_FIRST_BATCH_DOCUMENT_PROCESSING_PLAN.md`、
  `docs/drafts/README-zh-CN.md`、`docs/drafts/README.md`。
