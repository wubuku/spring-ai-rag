package com.springairag.core.controller;

import com.springairag.api.dto.BatchDocumentRequest;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagEmbedding;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.core.retrieval.RetrievalUtils;
import com.springairag.documents.chunk.HierarchicalTextChunker;
import com.springairag.documents.chunk.TextChunk;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
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
    private final VectorStore vectorStore;

    private static final HierarchicalTextChunker chunker = new HierarchicalTextChunker(1000, 100, 100);

    public RagDocumentController(RagDocumentRepository documentRepository,
                                  RagEmbeddingRepository embeddingRepository,
                                  EmbeddingBatchService embeddingBatchService,
                                  JdbcTemplate jdbcTemplate,
                                  @Autowired(required = false) VectorStore vectorStore) {
        this.documentRepository = documentRepository;
        this.embeddingRepository = embeddingRepository;
        this.embeddingBatchService = embeddingBatchService;
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStore = vectorStore;
    }

    /**
     * 上传/创建文档
     *
     * <p>自动计算内容 SHA-256 哈希值用于去重。
     * 如果内容已存在，返回已有文档信息（不重复创建）。
     */
    @Operation(summary = "创建文档", description = "上传文档内容，自动计算内容哈希用于去重。嵌入向量需通过 /embed 接口单独生成。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "创建成功（或检测到重复内容）"),
            @ApiResponse(responseCode = "400", description = "请求参数校验失败")
    })
    @PostMapping
    public ResponseEntity<Map<String, Object>> createDocument(@Valid @RequestBody DocumentRequest request) {
        log.info("Creating document: title={}", request.getTitle());

        String content = request.getContent();
        String contentHash = computeSha256(content);

        // 去重检查：相同内容不重复创建
        List<RagDocument> existing = documentRepository.findByContentHash(contentHash);
        if (!existing.isEmpty()) {
            RagDocument dup = existing.get(0);
            log.info("Duplicate content detected: existing doc id={}, hash={}", dup.getId(), contentHash);
            return ResponseEntity.ok(Map.of(
                    "id", dup.getId(),
                    "title", dup.getTitle(),
                    "status", "DUPLICATE",
                    "message", "内容已存在，文档ID: " + dup.getId(),
                    "existingDocumentId", dup.getId(),
                    "contentHash", contentHash
            ));
        }

        RagDocument doc = new RagDocument();
        doc.setTitle(request.getTitle());
        doc.setContent(content);
        doc.setSource(request.getSource());
        doc.setDocumentType(request.getDocumentType());
        doc.setMetadata(request.getMetadata());
        doc.setContentHash(contentHash);

        doc = documentRepository.save(doc);

        log.info("Document created: id={}, hash={}", doc.getId(), contentHash);

        return ResponseEntity.ok(Map.of(
                "id", doc.getId(),
                "title", doc.getTitle(),
                "status", "CREATED",
                "message", "文档已创建，嵌入向量需通过 /api/v1/rag/documents/{id}/embed 生成",
                "contentHash", contentHash
        ));
    }

    /**
     * 获取文档详情
     */
    @Operation(summary = "获取文档详情", description = "查询文档内容、元数据及嵌入向量数量。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回文档详情"),
            @ApiResponse(responseCode = "404", description = "文档不存在")
    })
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
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    /**
     * 删除文档（级联删除嵌入向量）
     */
    @Operation(summary = "删除文档", description = "删除文档及其关联的嵌入向量（级联删除）。")
    @ApiResponse(responseCode = "200", description = "删除成功")
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
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    /**
     * 列出文档（分页，支持过滤）
     */
    @Operation(summary = "列出文档", description = "分页查询文档列表，支持按标题/类型/状态过滤，按创建时间倒序。")
    @ApiResponse(responseCode = "200", description = "返回文档分页列表")
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
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "嵌入向量生成成功"),
            @ApiResponse(responseCode = "404", description = "文档不存在")
    })
    @PostMapping("/{id}/embed")
    @Transactional
    public ResponseEntity<Map<String, Object>> embedDocument(@PathVariable Long id) {
        log.info("Generating embeddings for document: id={}", id);

        // 1. 获取文档
        RagDocument doc = documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));

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
     * 通过 VectorStore 生成嵌入向量（简化路径）
     *
     * <p>使用 Spring AI {@link VectorStore#add(List)} 自动完成嵌入生成和存储。
     * 相比 {@link #embedDocument(Long)} 手动调用 EmbeddingBatchService + JdbcTemplate，
     * 此方法代码更简洁，但存储在 VectorStore 管理的表中（默认 rag_vector_store）。
     *
     * <p>两种路径对比：
     * <ul>
     *   <li>{@code /embed} — 完全控制，存储到 rag_embeddings（支持 document_id 关联、自定义分块）</li>
     *   <li>{@code /embed/vs} — 简化路径，存储到 rag_vector_store（VectorStore 自动管理）</li>
     * </ul>
     */
    @Operation(summary = "通过 VectorStore 生成嵌入向量",
            description = "使用 VectorStore.add() 自动完成嵌入生成和存储，代码更简洁。存储到 rag_vector_store 表。")
    @PostMapping("/{id}/embed/vs")
    @Transactional
    public ResponseEntity<Map<String, Object>> embedDocumentViaVectorStore(@PathVariable Long id) {
        if (vectorStore == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "VectorStore 未配置，请使用 /embed 端点",
                    "documentId", id
            ));
        }

        log.info("Generating embeddings via VectorStore for document: id={}", id);

        RagDocument doc = documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));

        String content = doc.getContent();
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "文档内容为空",
                    "documentId", id
            ));
        }

        List<TextChunk> chunks = chunker.split(content);
        if (chunks.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "message", "文档内容太短，无需分块",
                    "documentId", id,
                    "chunksCreated", 0
            ));
        }

        doc.setProcessingStatus("PROCESSING");
        documentRepository.save(doc);

        List<Document> documents = new java.util.ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            documents.add(Document.builder()
                    .id(id + "-" + i)
                    .text(chunk.text())
                    .metadata(Map.of(
                            "documentId", String.valueOf(id),
                            "chunkIndex", String.valueOf(i),
                            "chunkStartPos", String.valueOf(chunk.startPos()),
                            "chunkEndPos", String.valueOf(chunk.endPos())
                    ))
                    .build());
        }

        vectorStore.add(documents);

        doc.setProcessingStatus("COMPLETED");
        documentRepository.save(doc);

        log.info("Document {} embedding via VectorStore completed: {} chunks stored", id, chunks.size());

        return ResponseEntity.ok(Map.of(
                "message", "嵌入向量生成完成（VectorStore 路径）",
                "documentId", id,
                "chunksCreated", chunks.size(),
                "embeddingsStored", chunks.size(),
                "storageTable", "rag_vector_store",
                "status", "COMPLETED"
        ));
    }

    // ==================== 批量操作 ====================

    /**
     * 批量创建文档
     *
     * <p>支持一次上传多个文档，自动去重（按内容 SHA-256）。
     * 单条失败不影响其他文档，结果中报告每条的状态。
     */
    @Operation(summary = "批量创建文档", description = "一次上传多个文档（最多 100 条），自动去重。单条失败不影响其他文档。")
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> batchCreateDocuments(
            @Valid @RequestBody BatchDocumentRequest request) {
        List<DocumentRequest> docs = request.getDocuments();
        log.info("Batch creating {} documents", docs.size());

        List<Map<String, Object>> results = new ArrayList<>(docs.size());
        int created = 0, duplicated = 0, failed = 0;

        for (int i = 0; i < docs.size(); i++) {
            DocumentRequest req = docs.get(i);
            Map<String, Object> itemResult = new HashMap<>();
            itemResult.put("index", i);
            itemResult.put("title", req.getTitle());

            try {
                String content = req.getContent();
                String contentHash = computeSha256(content);

                // 去重检查
                List<RagDocument> existing = documentRepository.findByContentHash(contentHash);
                if (!existing.isEmpty()) {
                    RagDocument dup = existing.get(0);
                    itemResult.put("status", "DUPLICATE");
                    itemResult.put("id", dup.getId());
                    itemResult.put("message", "内容已存在");
                    duplicated++;
                } else {
                    RagDocument doc = new RagDocument();
                    doc.setTitle(req.getTitle());
                    doc.setContent(content);
                    doc.setSource(req.getSource());
                    doc.setDocumentType(req.getDocumentType());
                    doc.setMetadata(req.getMetadata());
                    doc.setContentHash(contentHash);
                    doc = documentRepository.save(doc);

                    itemResult.put("status", "CREATED");
                    itemResult.put("id", doc.getId());
                    created++;
                }
            } catch (Exception e) {
                log.error("Failed to create document at index {}: {}", i, e.getMessage());
                itemResult.put("status", "FAILED");
                itemResult.put("error", e.getMessage());
                failed++;
            }

            results.add(itemResult);
        }

        log.info("Batch create completed: {} created, {} duplicated, {} failed", created, duplicated, failed);

        return ResponseEntity.ok(Map.of(
                "results", results,
                "summary", Map.of(
                        "total", docs.size(),
                        "created", created,
                        "duplicated", duplicated,
                        "failed", failed
                )
        ));
    }

    /**
     * 批量删除文档（级联删除嵌入向量）
     */
    @Operation(summary = "批量删除文档", description = "按 ID 列表批量删除文档及其嵌入向量。单条不存在不影响其他文档。")
    @DeleteMapping("/batch")
    @Transactional
    public ResponseEntity<Map<String, Object>> batchDeleteDocuments(
            @RequestBody Map<String, List<Long>> request) {
        List<Long> ids = request.get("ids");
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ids 列表不能为空"));
        }
        if (ids.size() > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "单次批量删除不超过 100 条"));
        }

        log.info("Batch deleting {} documents", ids.size());

        List<Map<String, Object>> results = new ArrayList<>(ids.size());
        int deleted = 0, notFound = 0;

        // 批量删除嵌入向量
        embeddingRepository.deleteByDocumentIdIn(ids);

        for (Long id : ids) {
            Map<String, Object> itemResult = new HashMap<>();
            itemResult.put("id", id);

            if (documentRepository.existsById(id)) {
                documentRepository.deleteById(id);
                itemResult.put("status", "DELETED");
                deleted++;
            } else {
                itemResult.put("status", "NOT_FOUND");
                notFound++;
            }

            results.add(itemResult);
        }

        log.info("Batch delete completed: {} deleted, {} not found", deleted, notFound);

        return ResponseEntity.ok(Map.of(
                "results", results,
                "summary", Map.of(
                        "total", ids.size(),
                        "deleted", deleted,
                        "notFound", notFound
                )
        ));
    }

    /**
     * 批量生成嵌入向量
     *
     * <p>对多个文档并行执行分块 + 嵌入生成。使用 JdbcTemplate 路径存储到 rag_embeddings。
     * 单个文档失败不影响其他文档。
     */
    @Operation(summary = "批量生成嵌入向量", description = "对多个文档批量执行分块和嵌入生成。单个文档失败不影响其他文档。")
    @PostMapping("/batch/embed")
    @Transactional
    public ResponseEntity<Map<String, Object>> batchEmbedDocuments(
            @RequestBody Map<String, List<Long>> request) {
        List<Long> ids = request.get("ids");
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ids 列表不能为空"));
        }
        if (ids.size() > 50) {
            return ResponseEntity.badRequest().body(Map.of("error", "单次批量嵌入不超过 50 条（避免 API 限流）"));
        }

        log.info("Batch embedding {} documents", ids.size());

        List<Map<String, Object>> results = new ArrayList<>(ids.size());
        int success = 0, failed = 0, skipped = 0;

        for (Long id : ids) {
            Map<String, Object> itemResult = new HashMap<>();
            itemResult.put("documentId", id);

            try {
                RagDocument doc = documentRepository.findById(id).orElse(null);
                if (doc == null) {
                    itemResult.put("status", "NOT_FOUND");
                    skipped++;
                    results.add(itemResult);
                    continue;
                }

                String content = doc.getContent();
                if (content == null || content.isBlank()) {
                    itemResult.put("status", "SKIPPED");
                    itemResult.put("reason", "内容为空");
                    skipped++;
                    results.add(itemResult);
                    continue;
                }

                List<TextChunk> chunks = chunker.split(content);
                if (chunks.isEmpty()) {
                    itemResult.put("status", "SKIPPED");
                    itemResult.put("reason", "内容太短，无需分块");
                    skipped++;
                    results.add(itemResult);
                    continue;
                }

                doc.setProcessingStatus("PROCESSING");
                documentRepository.save(doc);

                // 删除旧向量
                embeddingRepository.deleteByDocumentId(id);

                // 生成嵌入
                List<String> texts = chunks.stream().map(TextChunk::text).toList();
                List<EmbeddingBatchService.EmbeddingResult> embResults =
                        embeddingBatchService.createEmbeddingsBatch(texts);

                int stored = 0;
                for (int j = 0; j < embResults.size(); j++) {
                    EmbeddingBatchService.EmbeddingResult result = embResults.get(j);
                    if (result.isSuccess()) {
                        TextChunk chunk = chunks.get(j);
                        String vectorStr = RetrievalUtils.vectorToString(result.getEmbedding());
                        jdbcTemplate.update(
                                "INSERT INTO rag_embeddings (document_id, chunk_text, chunk_index, embedding, chunk_start_pos, chunk_end_pos, created_at) " +
                                        "VALUES (?, ?, ?, ?::vector, ?, ?, NOW())",
                                id, chunk.text(), j, vectorStr, chunk.startPos(), chunk.endPos());
                        stored++;
                    }
                }

                doc.setProcessingStatus("COMPLETED");
                documentRepository.save(doc);

                itemResult.put("status", "COMPLETED");
                itemResult.put("chunksCreated", chunks.size());
                itemResult.put("embeddingsStored", stored);
                success++;

            } catch (Exception e) {
                log.error("Failed to embed document {}: {}", id, e.getMessage());
                itemResult.put("status", "FAILED");
                itemResult.put("error", e.getMessage());
                failed++;
            }

            results.add(itemResult);
        }

        log.info("Batch embed completed: {} success, {} failed, {} skipped", success, failed, skipped);

        return ResponseEntity.ok(Map.of(
                "results", results,
                "summary", Map.of(
                        "total", ids.size(),
                        "success", success,
                        "failed", failed,
                        "skipped", skipped
                )
        ));
    }

    /**
     * 计算文本的 SHA-256 哈希值
     */
    static String computeSha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
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
