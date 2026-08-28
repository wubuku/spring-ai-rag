# 文件管理、PDF 导入与 RAG 联动

> [English](file-management-and-pdf-rag.md) | [中文](file-management-and-pdf-rag-zh-CN.md)

本文解释 WebUI **文件管理**、PDF 导入、**添加到 RAG**，以及独立的**文档管理**
页面之间的边界和联动关系。

## 1. 三个层次

| 层次 | 当前职责 | 主要存储 |
|------|----------|----------|
| 文件管理 | 浏览导入后的文件产物，预览转换结果 | `fs_files` |
| PDF 导入 | 把一个 PDF 转换为包含原文件、Markdown 和提取资源的虚拟目录 | `fs_files` |
| RAG 文档 | 管理可分块、嵌入、检索并供 Chat 使用的逻辑文档 | `rag_documents`、`rag_embeddings` |

`fs_files` 本身是通用的平面文件模型：`path` 是主键，API 根据路径前缀合成目录。
这个边界适合日后扩展更多文件类型和文件管理操作，但当前已交付的公开流程更窄：

- 文件管理页面当前只导入 PDF；
- 尚无通用文件上传、移动、重命名或删除 API；
- 每次 PDF 导入都会创建一个新的 UUID 目录；
- 文档管理页面操作的是 RAG 逻辑文档，不是 `fs_files` 文件产物。

因此，**文件管理**是文件产物管理边界和后续扩展点，但目前还不是完整的通用文件管理器。

## 2. PDF 导入做了什么

文件管理页面调用：

```http
POST /api/v1/rag/files/pdf
```

后端选择可用转换器、生成 UUID，并把类似以下结构存入数据库：

```text
{uuid}/
  original.pdf
  default.md
  image_0.png
  ...
```

UUID 目录是合成目录。数据库只在 `fs_files` 中保存文件，
`GET /api/v1/rag/files/tree` 根据路径重建目录项。

完成这一步后：

- 可以预览或读取原始文件；
- 还没有创建 `rag_documents` 记录；
- 还没有文本块和 embedding；
- Search 和 Chat 无法检索这份内容。

`/files/pdf` 的历史兼容表单字段 `collection` 当前会被接受但被忽略，导入结果始终使用
UUID 目录。它也不是 RAG 的 `collectionKey`。

## 3. 添加到 RAG 做了什么

进入导入后的 UUID 目录后，WebUI 允许选择一个 RAG Collection，再点击
**添加到 RAG**。页面调用：

```http
POST /api/v1/rag/files/{uuid}/embed
  ?embed=sync
  &forceReembed=false
  &collectionKey={可选稳定-key}
```

后端随后：

1. 从 `fs_files` 读取 `{uuid}/default.md`；
2. 计算 Markdown 内容哈希；
3. 按 `source = pdf-import:{uuid}/default.md` 创建或复用 `rag_documents` 记录；
4. 提供 Collection 时，把文档关联到该 Collection；
5. 对文档分块并生成 embedding；
6. 保存按 Embedding Profile 管理的状态和向量。

嵌入成功后，该文档会出现在**文档管理**中，并可由 Search 和 Chat 检索，实际范围仍受
Collection scope 和 API Key ACL 约束。内容未变化时再次添加可能返回 `CACHED`；
`forceReembed=true` 可以绕过 embedding 缓存。

同一 UUID/source 重复添加会复用同一逻辑文档。不同 UUID 即使转换文本相同也保留独立
文档，从而维持一条可追溯的文件来源关系。内容哈希只用于 embedding 新鲜度和缓存判断。

`rag_documents.collection_id` 是单值字段。创建后可调用
`POST /api/v1/rag/collections/by-key/documents?collectionKey=...` 把普通/PDF 文档重关联到
另一个 Collection，不需要重新嵌入。调用方必须同时能访问原文档和目标 Collection。
`externalId` 非空的外部托管文档不能用该接口迁移，因为它的稳定身份是
`collectionKey + sourceNamespace + externalId`。

## 4. 一步完成和直接文档上传

不需要中间预览的外部 client 可以调用：

```http
POST /api/v1/rag/files/pdf-to-rag
```

它把 PDF 转换、文件产物存储、RAG 文档创建和可选 embedding 合并在一次请求中。
JSON 与 SSE 行为见 [REST API](rest-api-zh-CN.md#pdf-与文件产物-api)。

对于 `txt`、`md`、`json`、`xml`、`html`、`csv`、`log` 等文本类文件，
文档管理页面使用：

```http
POST /api/v1/rag/documents/upload
```

这条路径直接创建并嵌入 RAG 逻辑文档，不会创建可在文件管理中浏览的 `fs_files` 目录。

对于会持续更新的外部内容，推荐使用 `POST /api/v1/rag/documents/upsert`，并提供稳定的
`collectionKey + sourceNamespace + externalId` 与 opaque `sourceRevision`。详见
[外部文档：幂等同步](rest-api-zh-CN.md#external-documents-idempotent-synchronization)和
[外部同步 Client 指南](external-document-sync-client-guide-zh-CN.md)。

## 5. 检索结果来源追溯

Search API 对 PDF-backed 结果返回：

- `source` 和可用时的 `originalFilename`；
- 文件管理目录 `fileDirectoryPath`；
- 实际用于分块/嵌入的 `indexedFilePath`；
- 原始 PDF 的 `originalFilePath`。

Search WebUI 提供**查看文件目录**、**查看索引文件**和**打开原始 PDF**。前两个入口通过
`/webui/files?path=...&file=...` 深链接打开文件管理页；原始 PDF 通过认证 API client
获取 Blob，API Key 继续放在请求头中。普通文档和不满足安全 PDF source 约定的结果不会
显示文件操作。

文档管理页面将每行操作收纳在**…**菜单中。对于满足
`pdf-import:<目录>/default.md` 安全约定的 PDF 文档，菜单内的**文件/PDF 来源追溯**
子菜单同样提供**查看文件目录**、**查看索引文件**和**打开原始 PDF**；普通文档会明确
显示无文件来源，且不会把任意 `source` 当作文件路径。

已有 `pdf-import:` source 的历史记录无需重新嵌入即可追溯。早期若因全局内容哈希复用而
没有保存 PDF source，数据库缺少可靠关系，本轮不会猜测回填。

## 6. WebUI 目录排序

`GET /api/v1/rag/files/tree` 为每个文件返回 `createdAt`。合成目录使用其后代文件中最新的
`createdAt`，表示该目录最近一次导入活动的时间。

文件管理页面默认让最新导入项排在前面。**导入时间**按钮可以在倒序与正序之间切换。
旧版本后端未返回 `createdAt`，或历史数据没有可用时间时，这些条目仍会显示在有时间的条目
之后，并使用名称排序作为稳定回退。

## 7. 文件工作区交互

文件管理页面采用稳定的双栏工作区：

- 位置栏只承载面包屑和刷新操作；
- 左栏列出当前文件夹的直接子项，桌面端可以通过分隔条调节宽度；
- 右栏预览选中的文件，或展示当前文件夹摘要；
- 当前文件夹的**添加到 RAG** 操作只出现在文件夹摘要中，不作为根目录级全局控件。

已导入目录以可读的 PDF 名称作为主标签，同时保留可选择的 UUID 次级身份。没有批次元数据
的历史目录明确回退到 UUID。查找输入框只筛选当前文件夹，并且在中文等输入法组合期间不会
改写 URL，直到组合结束才提交查询。切换文件夹会清除当前文件夹筛选、保持工作区几何稳定，
并在空目录或无匹配结果时保留返回上一级入口。目录列表和预览区分别滚动，选中文件的路径
仍可通过 `/webui/files?path=...&file=...` 分享和恢复。

工作区只记忆当前标签页内低风险且有界的状态，例如所选 RAG 集合和目录列表宽度。不持久化
上传 `File` 对象、凭据、预览正文、mutation 或弹层打开状态。导入成功后会失效全部文件树
查询缓存，使新目录可以立即从根目录按可读名称找到。

## 8. 当前生命周期边界

- 再次导入同一 PDF 会创建新的 `fs_files` UUID 目录。
- PDF 内容变化后通常会创建另一条 RAG 文档，因为这条路径没有调用方提供的稳定外部身份。
- 删除 RAG 文档不会删除对应的 `fs_files` 转换产物。
- 文件管理页面目前没有产物删除、移动、重命名、标签或通用上传流程。
- PDF 路径应继续保持专用，因为转换会生成多个关联产物。未来的通用文件能力可以复用
  `fs_files` 和目录树 API，但需要明确生命周期与注册到 RAG 的契约。

## 9. 代码入口

| 关注点 | 代码 |
|--------|------|
| WebUI 文件管理流程 | `spring-ai-rag-webui/src/pages/Files.tsx` |
| 文件 API 类型客户端 | `spring-ai-rag-webui/src/api/files.ts` |
| HTTP 端点与合成目录树 | `PdfImportController` |
| PDF 转换与 `fs_files` 持久化 | `PdfImportService` |
| `fs_files` 到 `rag_documents` 的桥接 | `PdfToRagService` |
| 文件产物实体 | `FsFile` |
| RAG 逻辑文档上传 | `RagDocumentController` |

API 细节见 [REST API](rest-api-zh-CN.md#pdf-与文件产物-api)，稳定运行边界摘要见
[项目上下文](project-context-zh-CN.md)。
