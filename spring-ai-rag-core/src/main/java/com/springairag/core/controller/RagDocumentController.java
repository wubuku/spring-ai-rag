package com.springairag.core.controller;

import com.springairag.api.dto.DocumentRequest;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagEmbedding;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.core.retrieval.RetrievalUtils;
import com.springairag.documents.chunk.HierarchicalTextChunker;
import com.springairag.documents.chunk.TextChunk;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档管理控制器
 *
 * <p>提供文档的 CRUD 操作和嵌入向量管理。
 * CRUD 使用 Spring Data JPA，嵌入向量写入保留 JdbcTemplate（向量列需要特殊处理）。
 */
@RestController
@RequestMapping("/api/v1/rag/documents")
@Tag(name = "RAG Documents", description = "文档管理（CRUD + 嵌入向量生成）")
public class RagDocumentController {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentController.class);

    private final RagDocumentRepository documentRepository;
    private final RagEmbeddingRepository embeddingRepository;
    private final EmbeddingBatchService embeddingBatchService;
    private final JdbcTemplate jdbcTemplate;

    private static final HierarchicalTextChunker chunker = new HierarchicalTextChunker(1000, 100, 100);

    public RagDocumentController(RagDocumentRepository documentRepository,
                                  RagEmbeddingRepository embeddingRepository,
                                  EmbeddingBatchService embeddingBatchService,
                                  JdbcTemplate jdbcTemplate) {
        this.documentRepository = documentRepository;
        this.embeddingRepository = embeddingRepository;
        this.embeddingBatchService = embeddingBatchService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 上传/创建文档
     */
    @Operation(summary = "创建文档", description = "上传文档内容，嵌入向量需通过 /embed 接口单独生成。")
    @PostMapping
    public ResponseEntity<Map<String, Object>> createDocument(@Valid @RequestBody DocumentRequest request) {
        log.info("Creating document: title={}", request.getTitle());

        RagDocument doc = new RagDocument();
        doc.setTitle(request.getTitle());
        doc.setContent(request.getContent());
        doc.setSource(request.getSource());
        doc.setDocumentType(request.getDocumentType());
        doc.setMetadata(request.getMetadata());

        doc = documentRepository.save(doc);

        log.info("Document created: id={}", doc.getId());

        return ResponseEntity.ok(Map.of(
                "id", doc.getId(),
                "title", doc.getTitle(),
                "status", "CREATED",
                "message", "文档已创建，嵌入向量需通过 /api/v1/rag/documents/{id}/embed 生成"
        ));
    }

    /**
     * 获取文档详情
     */
    @Operation(summary = "获取文档详情", description = "查询文档内容、元数据及嵌入向量数量。")
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getDocument(@PathVariable Long id) {
        log.info("Getting document: id={}", id);

        return documentRepository.findById(id)
                .map(doc -> {
                    long embeddingCount = embeddingRepository.countByDocumentId(id);

                    Map<String, Object> result = documentToMap(doc);
                    result.put("embeddingCount", embeddingCount);

                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 删除文档（级联删除嵌入向量）
     */
    @Operation(summary = "删除文档", description = "删除文档及其关联的嵌入向量（级联删除）。")
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Map<String, String>> deleteDocument(@PathVariable Long id) {
        log.info("Deleting document: id={}", id);

        return documentRepository.findById(id)
                .map(doc -> {
                    long embCount = embeddingRepository.countByDocumentId(id);
                    embeddingRepository.deleteByDocumentId(id);
                    documentRepository.deleteById(id);

                    log.info("Document deleted: id={}, embeddings removed: {}", id, embCount);
                    return ResponseEntity.ok(Map.of(
                            "message", "文档已删除",
                            "id", String.valueOf(id),
                            "embeddingsRemoved", String.valueOf(embCount)
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 列出文档（分页，支持过滤）
     */
    @Operation(summary = "列出文档", description = "分页查询文档列表，支持按标题/类型/状态过滤，按创建时间倒序。")
    @GetMapping
    public ResponseEntity<Map<String, Object>> listDocuments(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String documentType,
            @RequestParam(required = false) String processingStatus,
            @RequestParam(required = false) Boolean enabled) {

        int page = offset / limit;
        var pageable = PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt"));

        var pageResult = documentRepository.searchDocuments(
                title, documentType, processingStatus, enabled, pageable);

        List<Map<String, Object>> docs = pageResult.getContent().stream()
                .map(this::documentToMap)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "documents", docs,
                "total", pageResult.getTotalElements(),
                "offset", offset,
                "limit", limit
        ));
    }

    /**
     * 文档统计信息
     */
    @Operation(summary = "文档统计", description = "获取各处理状态的文档数量统计。")
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getDocumentStats() {
        List<Object[]> statusCounts = documentRepository.countByProcessingStatus();
        Map<String, Long> counts = new HashMap<>();
        long total = 0;
        for (Object[] row : statusCounts) {
            String status = (String) row[0];
            long count = (Long) row[1];
            counts.put(status != null ? status : "UNKNOWN", count);
            total += count;
        }

        return ResponseEntity.ok(Map.of(
                "total", total,
                "byStatus", counts
        ));
    }

    /**
     * 为文档生成嵌入向量
     *
     * <p>流程：获取文档内容 → 分块 → 生成嵌入 → 存储到 rag_embeddings → 更新状态
     * 保留 JdbcTemplate 写入向量列（pgvector 格式需要特殊处理）。
     */
    @Operation(summary = "生成嵌入向量", description = "对文档进行分块并生成嵌入向量，存储到 rag_embeddings 表。会覆盖旧向量。")
    @PostMapping("/{id}/embed")
    @Transactional
    public ResponseEntity<Map<String, Object>> embedDocument(@PathVariable Long id) {
        log.info("Generating embeddings for document: id={}", id);

        // 1. 获取文档
        RagDocument doc = documentRepository.findById(id).orElse(null);
        if (doc == null) {
            return ResponseEntity.notFound().build();
        }

        String content = doc.getContent();
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
        doc.setProcessingStatus("PROCESSING");
        documentRepository.save(doc);

        // 4. 删除旧的嵌入向量（重新生成）
        embeddingRepository.deleteByDocumentId(id);

        // 5. 生成嵌入并存储（用 JdbcTemplate 写入向量列）
        List<String> texts = chunks.stream().map(TextChunk::text).toList();
        List<EmbeddingBatchService.EmbeddingResult> results =
                embeddingBatchService.createEmbeddingsBatch(texts);

        int stored = 0;
        for (int i = 0; i < results.size(); i++) {
            EmbeddingBatchService.EmbeddingResult result = results.get(i);
            if (result.isSuccess()) {
                TextChunk chunk = chunks.get(i);
                String vectorStr = RetrievalUtils.vectorToString(result.getEmbedding());
                jdbcTemplate.update(
                        "INSERT INTO rag_embeddings (document_id, chunk_text, chunk_index, embedding, chunk_start_pos, chunk_end_pos, created_at) " +
                                "VALUES (?, ?, ?, ?::vector, ?, ?, NOW())",
                        id, chunk.text(), i, vectorStr, chunk.startPos(), chunk.endPos());
                stored++;
            } else {
                log.warn("Embedding failed for chunk {}: {}", i, result.getError());
            }
        }

        // 6. 更新状态为 COMPLETED
        doc.setProcessingStatus("COMPLETED");
        documentRepository.save(doc);

        log.info("Document {} embedding completed: {}/{} chunks stored", id, stored, chunks.size());

        return ResponseEntity.ok(Map.of(
                "message", "嵌入向量生成完成",
                "documentId", id,
                "chunksCreated", chunks.size(),
                "embeddingsStored", stored,
                "status", "COMPLETED"
        ));
    }

    /**
     * 将实体转换为 Map（保持 API 兼容）
     */
    private Map<String, Object> documentToMap(RagDocument doc) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", doc.getId());
        map.put("title", doc.getTitle());
        map.put("source", doc.getSource());
        map.put("document_type", doc.getDocumentType());
        map.put("enabled", doc.getEnabled());
        map.put("created_at", doc.getCreatedAt());
        map.put("updated_at", doc.getUpdatedAt());
        map.put("processing_status", doc.getProcessingStatus());
        map.put("size", doc.getSize());
        if (doc.getContent() != null) {
            map.put("content", doc.getContent());
        }
        if (doc.getMetadata() != null) {
            map.put("metadata", doc.getMetadata());
        }
        return map;
    }
}
