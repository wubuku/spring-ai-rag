package com.springairag.core.controller;

import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.documents.chunk.HierarchicalTextChunker;
import com.springairag.documents.chunk.TextChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 文档管理控制器
 *
 * <p>提供文档的 CRUD 操作和嵌入向量管理。
 */
@RestController
@RequestMapping("/api/v1/rag/documents")
public class RagDocumentController {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentController.class);

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingBatchService embeddingBatchService;

    private static final HierarchicalTextChunker chunker = new HierarchicalTextChunker(1000, 100, 100);

    public RagDocumentController(JdbcTemplate jdbcTemplate, EmbeddingBatchService embeddingBatchService) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingBatchService = embeddingBatchService;
    }

    /**
     * 上传/创建文档
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createDocument(@RequestBody DocumentRequest request) {
        log.info("Creating document: title={}", request.getTitle());

        String sql = "INSERT INTO rag_documents (title, content, source, document_type, metadata, created_at) " +
                "VALUES (?, ?, ?, ?, ?::jsonb, ?) RETURNING id";

        String metadataJson = request.getMetadata() != null ? toJson(request.getMetadata()) : null;

        Long docId = jdbcTemplate.queryForObject(sql, Long.class,
                request.getTitle(),
                request.getContent(),
                request.getSource(),
                request.getDocumentType(),
                metadataJson,
                Timestamp.from(Instant.now()));

        log.info("Document created: id={}", docId);

        return ResponseEntity.ok(Map.of(
                "id", docId,
                "title", request.getTitle(),
                "status", "CREATED",
                "message", "文档已创建，嵌入向量需通过 /api/v1/rag/documents/{id}/embed 生成"
        ));
    }

    /**
     * 获取文档详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getDocument(@PathVariable Long id) {
        log.info("Getting document: id={}", id);

        List<Map<String, Object>> docs = jdbcTemplate.queryForList(
                "SELECT id, title, content, source, document_type, metadata, enabled, " +
                        "created_at, updated_at, processing_status, size " +
                        "FROM rag_documents WHERE id = ?", id);

        if (docs.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // 查询嵌入向量数量
        Integer embeddingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_embeddings WHERE document_id = ?", Integer.class, id);

        Map<String, Object> doc = docs.get(0);
        doc.put("embeddingCount", embeddingCount);

        return ResponseEntity.ok(doc);
    }

    /**
     * 删除文档（级联删除嵌入向量）
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteDocument(@PathVariable Long id) {
        log.info("Deleting document: id={}", id);

        // 先删嵌入向量（外键约束）
        int embDeleted = jdbcTemplate.update("DELETE FROM rag_embeddings WHERE document_id = ?", id);
        int docDeleted = jdbcTemplate.update("DELETE FROM rag_documents WHERE id = ?", id);

        if (docDeleted == 0) {
            return ResponseEntity.notFound().build();
        }

        log.info("Document deleted: id={}, embeddings removed: {}", id, embDeleted);
        return ResponseEntity.ok(Map.of(
                "message", "文档已删除",
                "id", String.valueOf(id),
                "embeddingsRemoved", String.valueOf(embDeleted)
        ));
    }

    /**
     * 列出文档（分页）
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listDocuments(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {

        List<Map<String, Object>> docs = jdbcTemplate.queryForList(
                "SELECT id, title, source, document_type, enabled, created_at, processing_status, size " +
                        "FROM rag_documents ORDER BY created_at DESC LIMIT ? OFFSET ?",
                limit, offset);

        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rag_documents", Integer.class);

        return ResponseEntity.ok(Map.of(
                "documents", docs,
                "total", total != null ? total : 0,
                "offset", offset,
                "limit", limit
        ));
    }

    /**
     * 为文档生成嵌入向量
     *
     * <p>流程：获取文档内容 → 分块 → 生成嵌入 → 存储到 rag_embeddings → 更新状态
     */
    @PostMapping("/{id}/embed")
    public ResponseEntity<Map<String, Object>> embedDocument(@PathVariable Long id) {
        log.info("Generating embeddings for document: id={}", id);

        // 1. 获取文档内容
        List<Map<String, Object>> docs = jdbcTemplate.queryForList(
                "SELECT id, content FROM rag_documents WHERE id = ?", id);
        if (docs.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String content = (String) docs.get(0).get("content");
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "文档内容为空",
                    "documentId", id
            ));
        }

        // 2. 分块
        List<TextChunk> chunks = chunker.split(content);
        if (chunks.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "message", "文档内容太短，无需分块",
                    "documentId", id,
                    "chunksCreated", 0
            ));
        }

        log.info("Document {} split into {} chunks", id, chunks.size());

        // 3. 更新状态为 PROCESSING
        jdbcTemplate.update(
                "UPDATE rag_documents SET processing_status = 'PROCESSING', updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), id);

        // 4. 删除旧的嵌入向量（重新生成）
        jdbcTemplate.update("DELETE FROM rag_embeddings WHERE document_id = ?", id);

        // 5. 生成嵌入并存储
        List<String> texts = chunks.stream().map(TextChunk::text).toList();
        List<EmbeddingBatchService.EmbeddingResult> results =
                embeddingBatchService.createEmbeddingsBatch(texts);

        int stored = 0;
        for (int i = 0; i < results.size(); i++) {
            EmbeddingBatchService.EmbeddingResult result = results.get(i);
            if (result.isSuccess()) {
                TextChunk chunk = chunks.get(i);
                // 将 float[] 转为 pgvector 格式 "[f1,f2,...]"
                String vectorStr = toVectorString(result.getEmbedding());
                jdbcTemplate.update(
                        "INSERT INTO rag_embeddings (document_id, chunk_text, chunk_index, embedding, chunk_start_pos, chunk_end_pos, created_at) " +
                                "VALUES (?, ?, ?, ?::vector, ?, ?, ?)",
                        id, chunk.text(), i, vectorStr, chunk.startPos(), chunk.endPos(),
                        Timestamp.from(Instant.now()));
                stored++;
            } else {
                log.warn("Embedding failed for chunk {}: {}", i, result.getError());
            }
        }

        // 6. 更新状态为 COMPLETED
        jdbcTemplate.update(
                "UPDATE rag_documents SET processing_status = 'COMPLETED', updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), id);

        log.info("Document {} embedding completed: {}/{} chunks stored", id, stored, chunks.size());

        return ResponseEntity.ok(Map.of(
                "message", "嵌入向量生成完成",
                "documentId", id,
                "chunksCreated", chunks.size(),
                "embeddingsStored", stored,
                "status", "COMPLETED"
        ));
    }

    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 简单的 Map → JSON 字符串转换
     */
    private String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                sb.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else {
                sb.append("\"").append(escapeJson(value.toString())).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 文档请求体
     */
    public static class DocumentRequest {
        private String title;
        private String content;
        private String source;
        private String documentType;
        private Map<String, Object> metadata;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }

        public String getDocumentType() { return documentType; }
        public void setDocumentType(String documentType) { this.documentType = documentType; }

        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }
}
