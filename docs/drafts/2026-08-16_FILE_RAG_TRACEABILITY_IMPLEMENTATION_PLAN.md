# 文件 RAG 来源追溯实施规划

> 状态：规划、实施、验收与连续三轮无修改审查均已完成，待提交推送
> 日期：2026-08-16
> 配套进度：[文件 RAG 来源追溯实施进度](2026-08-16_FILE_RAG_TRACEABILITY_IMPLEMENTATION_PROGRESS.md)
> 长青事实：[文件管理、PDF 导入与 RAG 联动](../file-management-and-pdf-rag-zh-CN.md)

## 1. 目标

让 Search API 和 WebUI 中来自 PDF 文件导入的检索结果可以追溯到：

1. 创建 embedding 的逻辑 RAG 文档；
2. `fs_files` 中被索引的转换后 Markdown；
3. 对应的文件管理 UUID 目录；
4. 同一目录中的原始 PDF 二进制。

追溯入口必须对已有 PDF 导入记录生效，不要求重新嵌入，也不新增数据库迁移。

本轮同时把 Collection 归属语义写清楚：

- PDF 仅转换时不选择 RAG Collection；
- 在 **添加到 RAG** 时，WebUI 可以选择一个已有 Collection，并传递稳定
  `collectionKey`；
- 留空时创建无 Collection 的普通 RAG 文档；
- 普通文档和 PDF 导入文档可通过既有 Collection-document 后端接口重新关联；
- `externalId` 非空的外部托管文档禁止通过该兼容接口迁移 Collection，因为
  `collectionKey + externalId` 是其稳定身份；
- 本轮不新增文档管理页的 Collection 迁移控件。

## 2. 用户问题与当前结论

### 2.1 文件管理的价值边界

当前 PDF 导入会保留原始 PDF、转换后的 `default.md` 和提取资源，允许预览后再决定是否
加入 RAG。这一产物层是文件管理相对于“客户自行转换 Markdown 后直接上传”的主要价值之一。

但是当前检索结果没有把这条来源链路暴露给调用方：

- `PdfToRagService` 在 `rag_documents` 中保存了来源：
  - `source = pdf-import:{uuid}/default.md`
  - metadata 包含 `importedFrom=pdf`、`fsFilesPath` 和 `uuid`
- embedding 写入和检索返回主要使用 `rag_embeddings.metadata`；
- Vector、pg_trgm、English FTS 和 pg_jieba 查询没有返回
  `rag_documents.source`、`original_filename` 等字段；
- Search WebUI 只保留标题、文本和分数。

因此当前只能算数据库内部留痕，不能算用户可用的来源追溯。

### 2.2 Collection 当前能力

`POST /api/v1/rag/files/{uuid}/embed` 和
`POST /api/v1/rag/files/pdf-to-rag` 已接受推荐的 `collectionKey`，并兼容 deprecated
的 `collectionId`。文件管理 WebUI 在导入目录内提供现有 Collection 下拉选择。

`POST /api/v1/rag/collections/by-key/documents?collectionKey=...` 会把一个已有普通文档
关联到目标 Collection；由于 `rag_documents.collection_id` 是单值外键，这个操作对已经
归属其他 Collection 的普通文档实际表现为迁移。该操作：

- 要求调用方同时有来源文档和目标 Collection 的访问权；
- 不需要重新嵌入，因为 embedding 内容没有变化；
- 对 `externalId` 非空的外部托管文档返回冲突，防止稳定身份命名空间漂移；
- 当前没有对应的 WebUI 操作入口。

## 3. 设计原则

1. **来源事实来自 `rag_documents`**：标题、来源和原始文件名不依赖 embedding
   metadata，避免历史 embedding 缺少 metadata 时无法追溯。
2. **响应返回显式字段**：WebUI 不解析通用 metadata，也不自行猜测来源路径。
3. **兼容历史数据**：现有 PDF 文档的 `source` 已使用稳定
   `pdf-import:{uuid}/default.md` 约定，可在查询时推导路径。
4. **不复制二进制或来源信息**：不向每个 embedding 重写文档 metadata，不要求重嵌入。
5. **鉴权保持一致**：原始 PDF 仍通过已有 `/api/v1/rag/files/raw` API 读取，并由
   WebUI API client 自动附加当前 API Key；不把凭据放入 URL。
6. **路径只由服务端确认**：只有满足 PDF 来源约定且通过安全虚拟路径校验时才返回
   文件路径，避免前端把任意 `source` 当作内部文件引用。
7. **PDF 按文件来源注册**：同一 UUID 目录重复添加时复用同一逻辑文档；不同 UUID
   即使转换文本相同也保留独立 RAG 文档，避免跨文件来源合并后丢失追溯关系或意外迁移
   Collection。
8. **控制改动范围**：本轮不改变文件产物生命周期、不增加删除/移动/重命名、不新增
   schema，也不改变外部文档同步语义。

## 4. API 契约

在 `RetrievalResult` 中新增以下可空字段：

| 字段 | 含义 | 普通文档 |
|---|---|---|
| `source` | `rag_documents.source` 的原始来源标识 | 按文档数据返回或 null |
| `originalFilename` | `rag_documents.original_filename` | 按文档数据返回或 null |
| `fileDirectoryPath` | 文件管理中的虚拟目录，末尾带 `/` | null |
| `indexedFilePath` | 实际用于创建 RAG 文档的转换产物路径 | null |
| `originalFilePath` | 对应原始 PDF 的 `fs_files` 路径 | null |

对于来源 `pdf-import:550e8400-e29b-41d4-a716-446655440000/default.md`：

```json
{
  "source": "pdf-import:550e8400-e29b-41d4-a716-446655440000/default.md",
  "originalFilename": "product-manual.pdf",
  "fileDirectoryPath": "550e8400-e29b-41d4-a716-446655440000/",
  "indexedFilePath": "550e8400-e29b-41d4-a716-446655440000/default.md",
  "originalFilePath": "550e8400-e29b-41d4-a716-446655440000/original.pdf"
}
```

历史两阶段导入记录可能没有真实上传文件名，其 `originalFilename` 可能为空或保存的是
转换文件路径。来源映射在 `originalFilename` 等于 `indexedFilePath` 或以
`/default.md` 形式充当兼容占位值时返回 null，WebUI 回退显示 `original.pdf`，不得误称
转换 Markdown 为原文件名。这不影响目录定位和原始 PDF 打开能力，也不需要修改历史数据。

历史版本曾按全局内容哈希复用 PDF 文档。若一次旧导入复用了不带 PDF source 的普通文档，
数据库中没有足够信息恢复该次导入关系，本轮不猜测或批量回填。已有
`pdf-import:` source 的历史文档可立即追溯；新注册从本轮开始保证来源级身份。

### 4.1 路径识别规则

只有同时满足以下条件才生成三个文件路径字段：

1. `source` 以 `pdf-import:` 开头；
2. 后续路径是相对虚拟路径，不以 `/` 开头；
3. 不含反斜杠、控制字符、空路径段、`.` 或 `..` 路径段；
4. 路径至少包含一个目录段，且最后一个路径段必须是 `default.md`；
5. indexed 文件是该路径本身，原始文件固定为同目录 `original.pdf`。

不满足规则时仍返回原始 `source`，文件路径字段保持 null。

## 5. 后端实施

### 5.1 检索 SQL

四条生产检索路径都已经通过 `EmbeddingProfileSqlScope` JOIN `rag_documents d`：

- `HybridRetrieverService` Vector 查询；
- `PgTrgmFulltextProvider`；
- `PgEnglishFtsProvider`；
- `PgJiebaFulltextProvider`。

在 SELECT 中增加带稳定别名的：

- `d.title AS document_title`
- `d.source AS document_source`
- `d.original_filename AS original_filename`

不选择 `d.content`，避免扩大查询数据量；不依赖 `d.metadata` 的 JDBC JSONB 映射。

### 5.2 PDF 来源级注册

`PdfToRagService` 当前使用 `findByContentHash` 做全局复用。这会产生两个核心问题：

- 新 PDF 可能复用普通上传文档，导致没有 `pdf-import:` 来源；
- 相同内容的不同 PDF/Collection 共享一条文档，后一次添加可能迁移前一次文档。

改为：

1. 计算规范来源 `pdf-import:{uuid}/default.md`；
2. 按该 source 查找已有文档；
3. 找到时复用同一文档，并按当前既有语义更新可选 Collection；
4. 未找到时创建新文档，即使其他来源具有相同 `contentHash`；
5. `contentHash` 继续用于 embedding cache/freshness，不再作为 PDF 来源身份。

Repository 增加确定性查询方法，例如 `findFirstBySourceOrderByIdAsc`。本轮不增加唯一约束，
不处理既有重复 source 数据；选择最早记录可以保持兼容且避免部署迁移风险。并发重复点击的
唯一性加强可在后续通过专用来源身份约束解决。

### 5.3 统一映射与复制

新增一个检索结果来源映射工具，负责：

- 从查询行设置权威标题、source 和 original filename；
- 验证并推导 PDF 文件路径；
- 清除两阶段导入遗留的 `default.md` 原文件名占位值；
- 为融合与 rerank 提供完整复制，避免新增字段在中间阶段丢失。

以下复制点必须保留新增字段：

- `RetrievalUtils.fuseResults`
- `HeuristicRerankProvider`
- `HttpRerankProvider`

现有 `metadata` 继续表示 embedding chunk metadata，不改变其含义。

### 5.4 后端测试

一次性覆盖：

1. 相同 UUID/source 重复添加复用同一文档；
2. 不同 UUID 但内容相同创建独立文档；
3. 合法 PDF source 生成三个路径字段；
4. `default.md` 原文件名占位值不会作为原始 PDF 名称返回；
5. 普通 source 不生成文件路径；
6. `..`、反斜杠、绝对路径和控制字符 fail closed；
7. Vector 查询返回文档来源字段；
8. 三个全文 provider 的 SQL 和映射保留来源字段；
9. fusion 和两种 rerank 不丢来源字段；
10. Search Controller JSON 契约暴露新增字段；
11. 既有无来源行继续正常映射。

## 6. WebUI 实施

### 6.1 Search 结果

Search API 类型和页面映射完整保留新增字段。只有结果带文件路径时才显示来源操作：

- **查看文件目录**：导航到
  `/webui/files?path={URL-encoded fileDirectoryPath}`；
- **查看索引文件**：导航到
  `/webui/files?path={URL-encoded fileDirectoryPath}&file={URL-encoded indexedFilePath}`，
  进入目录后自动选择并预览 `default.md`；
- **打开原始 PDF**：通过 `filesApi.getRawFile(originalFilePath)` 获取 Blob，再打开
  object URL，保证认证信息仍通过 header 发送；
- 显示 `originalFilename`；缺失时使用 `original.pdf` 作为克制的回退标签。

这些操作左对齐放在结果摘要下方，不改变现有检索、排序和 Collection scope 控件。

### 6.2 Files 深链接

Files 页面首次挂载时读取 `path` 和可选 `file` query 参数：

- 去除开头 `/`；
- 使用 `/` 分隔；
- 非空目录确保以 `/` 结尾；
- 拒绝 `.`、`..` 和控制字符。

`file` 必须位于规范化后的目录中；目录列表加载后匹配对应 `TreeEntry`，自动选中并使用
既有 FilePreview。无效参数回退到根目录或仅打开目录。进入页面后，既有目录导航逻辑保持
不变。

### 6.3 前端测试

一次性覆盖：

1. Search 页面不丢弃来源字段；
2. 普通结果不显示文件操作；
3. PDF 结果显示原文件名、目录入口和原始 PDF 操作；
4. 打开 PDF 时调用认证 API client；
5. Files 页面能从 query 参数直接打开目标目录并自动预览 indexed 文件；
6. Mock Playwright 从 Search 点击目录或 indexed 文件后进入对应位置；
7. Mock Playwright 验证原始 PDF 请求路径和认证 header。

## 7. 文档实施

按 `project-docs` 规范同步：

- `docs/file-management-and-pdf-rag*.md`
  - 把追溯能力改为当前事实；
  - 解释 `fs_files` 的价值；
  - 写清添加时选择 Collection 和创建后迁移边界。
- `docs/rest-api*.md`
  - 更新 Search 响应字段；
  - 记录 PDF 来源字段与路径含义；
  - 明确 Collection-document 接口对普通文档是重关联，对 external-managed 文档拒绝。
- `docs/project-context*.md`
  - 摘要文件产物到检索结果的追溯闭环。
- 既有索引已经能发现文件管理专题，不再新增平行导航正文。

规划和进度文档保持单语；对外 API 用法和客户最佳实践保持中英文成对。

## 8. 验证门槛

### 8.1 后端

- 任务相关 API/core/retrieval/controller 测试；
- 尽可能使用 PostgreSQL 集成测试覆盖真实 SELECT 映射；
- `mvn clean compile test-compile`；
- 服务可通过 `./scripts/dev.sh` 稳定启动；
- 使用当前 `.env` 中的凭据进行真实 Search 和 raw PDF HTTP 冒烟，输出不得泄露密钥。

### 8.2 前端

- `npx tsc -b`
- 任务相关 Vitest；
- `npm run lint`
- `npm run build`
- 核心 Mock Playwright；
- 已启动真实后端/WebUI 上的 Search → Files → original PDF 冒烟。

### 8.3 文档与差异

- `./scripts/verify-project-docs.sh`
- `git diff --check`
- added-line secret scan
- `git diff` 逐段确认未覆盖其他开发者修改

## 9. 固定范围实现审查

基本验证全部通过后才开始。每轮只检查以下范围：

1. 来源字段是否覆盖 Vector、三种全文、fusion、rerank；
2. 路径推导是否 fail closed，是否存在越权或凭据进入 URL；
3. Search 与 Files 深链接是否保留现有功能；
4. Collection 说明是否符合现有 ACL 和 external identity 约束；
5. API、WebUI、长青文档和测试是否一致。

只有会导致不可实施、越权、数据风险或核心验收无法验证的问题才触发修改和计数归零。
连续三轮无修改后结束审查。

## 10. 非目标与后续边界

- 不把文件产物 ACL 独立于 RAG API Key ACL；
- 不新增通用文件上传、删除、移动、重命名或标签；
- 不为历史 PDF 回填真实上传文件名；
- 不保留 PDF 的跨来源内容哈希合并；PDF 注册改为来源级复用，内容哈希只承担新鲜度和
  embedding cache；
- 不新增 Documents WebUI 的 Collection 迁移控件；
- 不把来源字段同步扩展到 Chat citation DTO；本轮先完成直接 Search API 和 WebUI，
  后续可复用同一来源映射扩展 Chat。

## 11. 完成定义

1. PDF 检索结果通过 API 返回明确来源路径；
2. Search WebUI 可进入对应文件目录、预览实际 indexed 文件并打开原始 PDF；
3. 新 PDF 注册按来源复用，不因相同内容丢失追溯关系或迁移其他来源文档；
4. 已有 `pdf-import:` source 的历史 PDF 文档无需重嵌入即可获得追溯能力；
5. 普通文档不显示错误的文件操作；
6. 添加到 RAG 的 Collection 选择和文档迁移边界已在长青文档明确；
7. 后端、前端、Mock/真实冒烟和文档门禁通过；
8. 实现连续三轮无修改审查通过；
9. 最终 diff 已复核，提交并推送后工作区干净。
