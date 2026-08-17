# File Management, PDF Import, And RAG Integration

> [English](file-management-and-pdf-rag.md) | [中文](file-management-and-pdf-rag-zh-CN.md)

This guide explains the boundary and interaction among the WebUI **Files**
page, PDF import, **Add to RAG**, and the separate **Documents** page.

## 1. The Three Layers

| Layer | Current responsibility | Primary storage |
|-------|------------------------|-----------------|
| Files | Browse imported file artifacts and preview conversion output | `fs_files` |
| PDF import | Convert one PDF into a virtual directory containing the original PDF, Markdown, and extracted assets | `fs_files` |
| RAG documents | Manage logical documents that can be chunked, embedded, searched, and used by Chat | `rag_documents`, `rag_embeddings` |

`fs_files` is a generic flat file model: `path` is the primary key, and the
API synthesizes directories from path prefixes. This is a suitable foundation
for more file types and file-management operations. The delivered public
workflow is currently narrower:

- the Files page imports PDFs;
- there is no generic Files upload, move, rename, or delete API yet;
- each PDF import creates a new UUID directory;
- the Documents page handles logical RAG documents, not `fs_files` artifacts.

Therefore, **Files** is the artifact-management boundary and extension point,
but it is not yet a complete general-purpose file manager.

## 2. What PDF Import Does

The Files page calls:

```http
POST /api/v1/rag/files/pdf
```

The backend selects an available converter, creates a UUID, and stores output
similar to:

```text
{uuid}/
  original.pdf
  default.md
  image_0.png
  ...
```

The UUID directory is synthetic. Only files are persisted in `fs_files`;
`GET /api/v1/rag/files/tree` reconstructs directory entries from their paths.

At this point:

- preview and raw-file access work;
- no `rag_documents` row has been created;
- no chunks or embeddings exist;
- Search and Chat cannot retrieve the imported content.

The legacy `collection` form field on `/files/pdf` is accepted but currently
ignored. Imports always use the UUID directory. It is not a RAG
`collectionKey`.

## 3. What Add To RAG Does

After opening an imported UUID directory, the WebUI lets the operator choose a
RAG Collection and click **Add to RAG**. It calls:

```http
POST /api/v1/rag/files/{uuid}/embed
  ?embed=sync
  &forceReembed=false
  &collectionKey={optional-stable-key}
```

The backend then:

1. reads `{uuid}/default.md` from `fs_files`;
2. hashes its Markdown content;
3. creates or reuses a `rag_documents` row by
   `source = pdf-import:{uuid}/default.md`;
4. associates the document with the selected Collection when supplied;
5. chunks and embeds the document;
6. stores Profile-aware embedding state and vectors.

After a successful embedding, the document appears in **Documents** and can be
retrieved by Search and Chat, subject to Collection scope and API-key ACLs.
Repeating Add to RAG without content changes can return `CACHED`. Setting
`forceReembed=true` bypasses that embedding cache.

Repeating the same UUID/source reuses one logical document. Different UUIDs
retain distinct documents even when their converted text is identical, which
preserves a traceable file relationship. The content hash is used only for
embedding freshness and cache decisions.

`rag_documents.collection_id` is single-valued. After creation,
`POST /api/v1/rag/collections/by-key/documents?collectionKey=...` can
reassociate an ordinary/PDF document with another Collection without
re-embedding. The caller must be able to access both the source document and
target Collection. Externally managed documents with a nonblank `externalId`
cannot use that route because their stable identity is
`collectionKey + externalId`.

## 4. One-Step And Direct-Document Alternatives

External clients that do not need an intermediate preview can use:

```http
POST /api/v1/rag/files/pdf-to-rag
```

This combines PDF conversion, artifact storage, RAG document creation, and
optional embedding. See [REST API](rest-api.md#pdf-and-file-artifact-apis) for
JSON versus SSE behavior.

For text-family files (`txt`, `md`, `json`, `xml`, `html`, `csv`, `log`), the
Documents page uses:

```http
POST /api/v1/rag/documents/upload
```

That path creates and embeds logical RAG documents directly. It does not
create a browsable `fs_files` directory.

For externally managed content that can change over time, prefer
`POST /api/v1/rag/documents/upsert` with stable `collectionKey + externalId`
and an opaque `sourceRevision`. See
[External Documents - Idempotent Synchronization](rest-api.md#external-documents-idempotent-synchronization).

## 5. Search Result Provenance

For PDF-backed hits, the Search API returns:

- `source` and `originalFilename` when known;
- the Files directory as `fileDirectoryPath`;
- the converted artifact used for chunking and embedding as
  `indexedFilePath`;
- the original PDF as `originalFilePath`.

The Search WebUI provides **View file directory**, **View indexed file**, and
**Open original PDF**. The first two use
`/webui/files?path=...&file=...` deep links. The original PDF is fetched as a
Blob through the authenticated API client, so the API key remains in a request
header. Ordinary results and sources that fail the safe PDF-path convention do
not show file actions.

The Documents page consolidates each row's actions under an **...** menu. For
PDF documents whose source satisfies the safe
`pdf-import:<directory>/default.md` convention, its **File/PDF source
traceability** submenu provides the same **View file directory**, **View indexed
file**, and **Open original PDF** actions. Ordinary documents explicitly show
that no file source is available, and arbitrary `source` values are never
treated as file paths.

Historical rows that already have a `pdf-import:` source become traceable
without re-embedding. If an older import was globally content-hash merged into
a row without a PDF source, the database has no reliable relationship to
backfill.

## 6. WebUI Directory Ordering

`GET /api/v1/rag/files/tree` returns `createdAt` for each file. A synthetic
directory uses the latest `createdAt` among its descendant files, which
represents the most recent import activity in that directory.

The Files page defaults to newest imports first. The **Imported** button
switches between descending and ascending import-time order. Entries from an
older backend that omit `createdAt`, or legacy rows without a usable value,
remain visible after timestamped entries and use name order as a stable
fallback.

## 7. Current Lifecycle Boundaries

- Importing the same PDF again creates another `fs_files` UUID directory.
- Changed PDF content usually creates another RAG document because this path
  has no caller-supplied stable external identity.
- Deleting a RAG document does not delete its `fs_files` conversion artifacts.
- The Files page currently has no artifact delete, move, rename, tagging, or
  generic upload workflow.
- The PDF path should remain specialized because conversion produces multiple
  related artifacts. Future generic file support can reuse `fs_files` and the
  tree API while defining explicit lifecycle and RAG-registration contracts.

## 8. Code Entry Points

| Concern | Code |
|---------|------|
| WebUI Files workflow | `spring-ai-rag-webui/src/pages/Files.tsx` |
| Typed Files API client | `spring-ai-rag-webui/src/api/files.ts` |
| HTTP endpoints and synthetic tree | `PdfImportController` |
| PDF conversion and `fs_files` persistence | `PdfImportService` |
| `fs_files` to `rag_documents` bridge | `PdfToRagService` |
| File artifact entity | `FsFile` |
| Logical RAG document upload | `RagDocumentController` |

API details are in [REST API](rest-api.md#pdf-and-file-artifact-apis). Stable
runtime boundaries are summarized in [Project Context](project-context.md).
