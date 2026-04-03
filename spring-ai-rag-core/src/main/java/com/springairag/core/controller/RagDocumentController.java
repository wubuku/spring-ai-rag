package com.springairag.core.controller;

import com.springairag.api.dto.BatchDocumentRequest;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.dto.EmbedProgressEvent;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.versioning.ApiVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档管理控制器
 *
 * <p>提供文档的 CRUD 操作和嵌入向量管理。
 * 业务逻辑委托给 {@link DocumentEmbedService} 和 {@link BatchDocumentService}。
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/documents")
@Tag(name = "RAG Documents", description = "文档管理（CRUD + 嵌入向量生成）")
public class RagDocumentController {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentController.class);

    private final RagDocumentRepository documentRepository;
    private final RagEmbeddingRepository embeddingRepository;
    private final DocumentEmbedService documentEmbedService;
    private final BatchDocumentService batchDocumentService;
    private final DocumentVersionService documentVersionService;

    public RagDocumentController(RagDocumentRepository documentRepository,
                                  RagEmbeddingRepository embeddingRepository,
                                  DocumentEmbedService documentEmbedService,
                                  BatchDocumentService batchDocumentService,
                                  DocumentVersionService documentVersionService) {
        this.documentRepository = documentRepository;
        this.embeddingRepository = embeddingRepository;
        this.documentEmbedService = documentEmbedService;
        this.batchDocumentService = batchDocumentService;
        this.documentVersionService = documentVersionService;
    }

    // ==================== CRUD ====================

    @Operation(summary = "创建文档", description = "上传文档内容，自动计算内容哈希用于去重。嵌入向量需通过 /embed 接口单独生成。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "创建成功（或检测到重复内容）"),
            @ApiResponse(responseCode = "400", description = "请求参数校验失败")
    })
    @PostMapping
    public ResponseEntity<Map<String, Object>> createDocument(@Valid @RequestBody DocumentRequest request) {
        log.info("Creating document: title={}", request.getTitle());

        String content = request.getContent();
        String contentHash = BatchDocumentService.computeSha256(content);

        // 去重检查
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

    @Operation(summary = "删除文档", description = "删除文档及其关联的嵌入向量（级联删除）。")
    @ApiResponse(responseCode = "200", description = "删除成功")
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteDocument(@PathVariable Long id) {
        return ResponseEntity.ok(batchDocumentService.deleteDocument(id));
    }

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
        return ResponseEntity.ok(Map.of("total", total, "byStatus", counts));
    }

    // ==================== 嵌入向量 ====================

    @Operation(summary = "生成嵌入向量", description = "对文档进行分块并生成嵌入向量，存储到 rag_embeddings 表。已有嵌入时默认跳过，传入 force=true 强制重嵌入。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "嵌入向量生成成功"),
            @ApiResponse(responseCode = "404", description = "文档不存在")
    })
    @PostMapping("/{id}/embed")
    public ResponseEntity<Map<String, Object>> embedDocument(
            @PathVariable Long id,
            @Parameter(description = "强制重嵌入（跳过缓存）")
            @RequestParam(defaultValue = "false") boolean force) {
        try {
            Map<String, Object> result = documentEmbedService.embedDocument(id, force);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "documentId", id
            ));
        }
    }

    @Operation(summary = "生成嵌入向量（SSE 流式进度）",
            description = "与 POST /embed 类似，但通过 Server-Sent Events 实时推送处理进度。客户端可监听 progress 事件获取当前阶段、已处理数量和总数量。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE 流已建立，进度事件将陆续推送"),
            @ApiResponse(responseCode = "404", description = "文档不存在")
    })
    @PostMapping("/{id}/embed/stream")
    public SseEmitter embedDocumentStream(
            @PathVariable Long id,
            @Parameter(description = "强制重嵌入（跳过缓存）")
            @RequestParam(defaultValue = "false") boolean force) {
        SseEmitter emitter = new SseEmitter(0L); // 无超时
        try {
            documentEmbedService.embedDocumentWithProgress(id, force, event -> {
                try {
                    emitter.send(SseEmitter.event()
                            .name("progress")
                            .data(event));
                } catch (Exception ex) {
                    log.warn("SSE send failed for document {}: {}", id, ex.getMessage());
                }
            });
            emitter.send(SseEmitter.event().name("done").data(Map.of("documentId", id)));
            emitter.complete();
        } catch (IllegalArgumentException e) {
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of(
                        "error", e.getMessage(),
                        "documentId", id
                )));
            } catch (Exception ex) { /* ignore */ }
            emitter.completeWithError(e);
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @Operation(summary = "通过 VectorStore 生成嵌入向量",
            description = "使用 VectorStore.add() 自动完成嵌入生成和存储，代码更简洁。存储到 rag_vector_store 表。已有嵌入时默认跳过，传入 force=true 强制重嵌入。")
    @PostMapping("/{id}/embed/vs")
    public ResponseEntity<Map<String, Object>> embedDocumentViaVectorStore(
            @PathVariable Long id,
            @Parameter(description = "强制重嵌入（跳过缓存）")
            @RequestParam(defaultValue = "false") boolean force) {
        try {
            Map<String, Object> result = documentEmbedService.embedDocumentViaVectorStore(id, force);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "documentId", id
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "documentId", id
            ));
        }
    }

    // ==================== 批量操作 ====================

    @Operation(summary = "批量创建文档", description = "一次上传多个文档（最多 100 条），自动去重。单条失败不影响其他文档。")
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> batchCreateDocuments(
            @Valid @RequestBody BatchDocumentRequest request) {
        Map<String, Object> result = batchDocumentService.batchCreateDocuments(request.getDocuments());
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "批量删除文档", description = "按 ID 列表批量删除文档及其嵌入向量。单条不存在不影响其他文档。")
    @DeleteMapping("/batch")
    public ResponseEntity<Map<String, Object>> batchDeleteDocuments(
            @RequestBody Map<String, List<Long>> request) {
        List<Long> ids = request.get("ids");
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ids 列表不能为空"));
        }
        try {
            Map<String, Object> result = batchDocumentService.batchDeleteDocuments(ids);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @Operation(summary = "批量生成嵌入向量", description = "对多个文档批量执行分块和嵌入生成。单个文档失败不影响其他文档。")
    @PostMapping("/batch/embed")
    public ResponseEntity<Map<String, Object>> batchEmbedDocuments(
            @RequestBody Map<String, List<Long>> request) {
        List<Long> ids = request.get("ids");
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ids 列表不能为空"));
        }
        try {
            Map<String, Object> result = documentEmbedService.batchEmbedDocuments(ids);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== 版本历史 ====================

    @Operation(summary = "获取文档版本历史", description = "分页查询文档的内容变更版本记录，最新在前。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回版本历史"),
            @ApiResponse(responseCode = "404", description = "文档不存在")
    })
    @GetMapping("/{id}/versions")
    public ResponseEntity<Map<String, Object>> getVersionHistory(
            @Parameter(description = "文档 ID") @PathVariable Long id,
            @Parameter(description = "页码") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") int size) {

        if (!documentRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        var versions = documentVersionService.getVersionHistory(id, PageRequest.of(page, size));
        Map<String, Object> result = new HashMap<>();
        result.put("documentId", id);
        result.put("totalVersions", versions.getTotalElements());
        result.put("page", page);
        result.put("size", size);
        result.put("versions", versions.getContent().stream().map(this::versionToMap).collect(Collectors.toList()));
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "获取指定版本", description = "查询文档的指定版本详情（含内容快照）。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回版本详情"),
            @ApiResponse(responseCode = "404", description = "版本不存在")
    })
    @GetMapping("/{id}/versions/{versionNumber}")
    public ResponseEntity<Map<String, Object>> getVersion(
            @Parameter(description = "文档 ID") @PathVariable Long id,
            @Parameter(description = "版本号") @PathVariable int versionNumber) {

        return documentVersionService.getVersion(id, versionNumber)
                .map(v -> ResponseEntity.ok(versionToMap(v)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== 辅助方法 ====================

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

    private Map<String, Object> versionToMap(RagDocumentVersion v) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", v.getId());
        map.put("documentId", v.getDocumentId());
        map.put("versionNumber", v.getVersionNumber());
        map.put("contentHash", v.getContentHash());
        map.put("size", v.getSize());
        map.put("changeType", v.getChangeType());
        map.put("changeDescription", v.getChangeDescription());
        map.put("createdAt", v.getCreatedAt());
        // 仅在单版本详情中返回内容快照，列表中省略以节省带宽
        if (v.getContentSnapshot() != null) {
            map.put("contentSnapshot", v.getContentSnapshot());
        }
        return map;
    }
}
