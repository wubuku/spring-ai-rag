package com.springairag.core.service;

import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.core.retrieval.RetrievalUtils;
import com.springairag.documents.chunk.HierarchicalTextChunker;
import com.springairag.documents.chunk.TextChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档嵌入服务
 *
 * <p>负责将文档内容分块、生成嵌入向量并存储。
 * 支持两种存储路径：
 * <ul>
 *   <li>JdbcTemplate 路径 — 存储到 rag_embeddings，支持 document_id 关联</li>
 *   <li>VectorStore 路径 — 存储到 rag_vector_store，Spring AI 自动管理</li>
 * </ul>
 */
@Service
public class DocumentEmbedService {

    private static final Logger log = LoggerFactory.getLogger(DocumentEmbedService.class);
    private static final HierarchicalTextChunker chunker = new HierarchicalTextChunker(1000, 100, 100);

    private final RagDocumentRepository documentRepository;
    private final RagEmbeddingRepository embeddingRepository;
    private final EmbeddingBatchService embeddingBatchService;
    private final JdbcTemplate jdbcTemplate;
    private final VectorStore vectorStore;

    public DocumentEmbedService(RagDocumentRepository documentRepository,
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
     * 为文档生成嵌入向量（JdbcTemplate 路径）
     *
     * <p>流程：获取文档 → 分块 → 生成嵌入 → 存储到 rag_embeddings → 更新状态
     *
     * <p>嵌入缓存：如果文档已有嵌入且状态为 COMPLETED，跳过重嵌入。
     * 传入 {@code force=true} 可强制重嵌入（例如嵌入模型变更后）。
     *
     * @param documentId 文档 ID
     * @return 操作结果（chunksCreated, embeddingsStored, status）
     */
    @Transactional
    public Map<String, Object> embedDocument(Long documentId) {
        return embedDocument(documentId, false);
    }

    /**
     * 为文档生成嵌入向量（JdbcTemplate 路径，支持强制重嵌入）
     *
     * @param documentId 文档 ID
     * @param force 是否强制重嵌入（跳过缓存检查）
     * @return 操作结果（chunksCreated, embeddingsStored, status）
     */
    @Transactional
    public Map<String, Object> embedDocument(Long documentId, boolean force) {
        log.info("Generating embeddings for document: id={}, force={}", documentId, force);

        EmbedPrepareResult prep = prepareForEmbedding(documentId, force);
        if (prep.cached() != null) return prep.cached();

        List<TextChunk> chunks = prep.chunks();

        RagDocument doc = prep.doc();
        doc.setProcessingStatus("PROCESSING");
        documentRepository.save(doc);

        // 删除旧向量 → 生成嵌入 → 存储
        embeddingRepository.deleteByDocumentId(documentId);
        List<String> texts = chunks.stream().map(TextChunk::text).toList();
        List<EmbeddingBatchService.EmbeddingResult> results =
                embeddingBatchService.createEmbeddingsBatch(texts);
        int stored = storeEmbeddings(documentId, chunks, results);

        completeEmbedding(doc, chunks.size());

        log.info("Document {} embedding completed: {}/{} chunks stored", documentId, stored, chunks.size());
        return buildSuccessResult(documentId, chunks.size(), stored, "COMPLETED");
    }

    /**
     * 为文档生成嵌入向量（VectorStore 简化路径）
     *
     * @param documentId 文档 ID
     * @return 操作结果
     * @throws IllegalStateException VectorStore 未配置时抛出
     */
    @Transactional
    public Map<String, Object> embedDocumentViaVectorStore(Long documentId) {
        return embedDocumentViaVectorStore(documentId, false);
    }

    /**
     * 为文档生成嵌入向量（VectorStore 简化路径，支持强制重嵌入）
     *
     * @param documentId 文档 ID
     * @param force 是否强制重嵌入（跳过缓存检查）
     * @return 操作结果
     * @throws IllegalStateException VectorStore 未配置时抛出
     */
    @Transactional
    public Map<String, Object> embedDocumentViaVectorStore(Long documentId, boolean force) {
        if (vectorStore == null) {
            throw new IllegalStateException("VectorStore 未配置，请使用 embedDocument 方法");
        }

        log.info("Generating embeddings via VectorStore for document: id={}, force={}", documentId, force);

        EmbedPrepareResult prep = prepareForEmbedding(documentId, force);
        if (prep.cached() != null) return prep.cached();

        List<TextChunk> chunks = prep.chunks();
        RagDocument doc = prep.doc();
        doc.setProcessingStatus("PROCESSING");
        documentRepository.save(doc);

        vectorStore.add(buildVectorStoreDocuments(documentId, chunks));
        completeEmbedding(doc, chunks.size());

        log.info("Document {} embedding via VectorStore completed: {} chunks stored", documentId, chunks.size());
        return buildSuccessResult(documentId, chunks.size(), chunks.size(), "COMPLETED",
                "storageTable", "rag_vector_store");
    }

    /**
     * 批量为多个文档生成嵌入向量
     *
     * @param documentIds 文档 ID 列表
     * @return 批量操作结果（results + summary）
     */
    @Transactional
    public Map<String, Object> batchEmbedDocuments(List<Long> documentIds) {
        if (documentIds.size() > 50) {
            throw new IllegalArgumentException("单次批量嵌入不超过 50 条（避免 API 限流）");
        }

        log.info("Batch embedding {} documents", documentIds.size());

        List<Map<String, Object>> results = new java.util.ArrayList<>(documentIds.size());
        int success = 0, failed = 0, skipped = 0, cached = 0;

        for (Long id : documentIds) {
            Map<String, Object> itemResult = embedSingleDocument(id);
            String status = (String) itemResult.get("status");
            switch (status) {
                case "COMPLETED" -> success++;
                case "FAILED" -> failed++;
                case "CACHED" -> cached++;
                default -> skipped++;
            }
            results.add(itemResult);
        }

        log.info("Batch embed completed: {} success, {} cached, {} failed, {} skipped",
                success, cached, failed, skipped);

        return Map.of(
                "results", results,
                "summary", Map.of(
                        "total", documentIds.size(),
                        "success", success,
                        "cached", cached,
                        "failed", failed,
                        "skipped", skipped
                )
        );
    }

    /**
     * 单文档嵌入处理（供 batchEmbedDocuments 调用）
     */
    private Map<String, Object> embedSingleDocument(Long id) {
        Map<String, Object> result = new HashMap<>();
        result.put("documentId", id);
        try {
            processSingleEmbedding(id, result);
        } catch (Exception e) {
            log.error("Failed to embed document {}: {}", id, e.getMessage());
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
        }
        return result;
    }

    /** 执行单文档嵌入的核心逻辑，结果写入 result Map */
    private void processSingleEmbedding(Long id, Map<String, Object> result) {
        RagDocument doc = documentRepository.findById(id).orElse(null);
        if (doc == null) {
            result.put("status", "NOT_FOUND");
            return;
        }

        Map<String, Object> cached = checkEmbeddingCache(doc);
        if (cached != null) {
            result.put("status", "CACHED");
            result.put("embeddingsStored", cached.get("embeddingsStored"));
            return;
        }

        String content = doc.getContent();
        if (content == null || content.isBlank()) {
            result.put("status", "SKIPPED");
            result.put("reason", "内容为空");
            return;
        }

        List<TextChunk> chunks = chunker.split(content);
        if (chunks.isEmpty()) {
            result.put("status", "SKIPPED");
            result.put("reason", "内容太短，无需分块");
            return;
        }

        doc.setProcessingStatus("PROCESSING");
        documentRepository.save(doc);
        embeddingRepository.deleteByDocumentId(id);

        int stored = storeEmbeddings(id, chunks,
                embeddingBatchService.createEmbeddingsBatch(
                        chunks.stream().map(TextChunk::text).toList()));
        completeEmbedding(doc, chunks.size());

        result.put("status", "COMPLETED");
        result.put("chunksCreated", chunks.size());
        result.put("embeddingsStored", stored);
    }

    /**
     * VectorStore 是否可用
     */
    public boolean isVectorStoreAvailable() {
        return vectorStore != null;
    }

    // ==================== 内部方法 ====================

    /**
     * 检查嵌入缓存 — 基于内容哈希判断是否需要重嵌入
     *
     * <p>检查策略（三层）：
     * <ol>
     *   <li>状态检查：非 COMPLETED 状态不命中缓存</li>
     *   <li>内容哈希检查：当前内容哈希与已嵌入哈希不一致 → 内容已变更，需重嵌入</li>
     *   <li>嵌入记录检查：无已有嵌入记录 → 需嵌入</li>
     * </ol>
     *
     * @return 缓存命中返回结果 Map，未命中返回 null
     */
    private Map<String, Object> checkEmbeddingCache(RagDocument doc) {
        if (!"COMPLETED".equals(doc.getProcessingStatus())) {
            return null;
        }

        // 内容哈希比对：如果内容已变更，即使有旧嵌入也需重新嵌入
        String currentHash = doc.getContentHash();
        String embeddedHash = doc.getEmbeddedContentHash();
        if (currentHash != null && embeddedHash != null && !currentHash.equals(embeddedHash)) {
            log.info("Content changed for document {}: currentHash={}, embeddedHash={}, re-embedding needed",
                    doc.getId(), currentHash, embeddedHash);
            return null;
        }

        long existingCount = embeddingRepository.countByDocumentId(doc.getId());
        if (existingCount > 0) {
            log.info("Embedding cache hit for document {}: {} existing embeddings, content unchanged, skipping",
                    doc.getId(), existingCount);
            return Map.of(
                    "message", "嵌入向量已存在且内容未变更，跳过重嵌入（使用 force=true 强制重嵌入）",
                    "documentId", doc.getId(),
                    "embeddingsStored", existingCount,
                    "status", "CACHED",
                    "cached", true
            );
        }
        return null;
    }

    private RagDocument findDocument(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    private String validateContent(RagDocument doc) {
        String content = doc.getContent();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("文档内容为空: documentId=" + doc.getId());
        }
        return content;
    }

    private int storeEmbeddings(Long documentId, List<TextChunk> chunks,
                                 List<EmbeddingBatchService.EmbeddingResult> results) {
        int stored = 0;
        for (int i = 0; i < results.size(); i++) {
            EmbeddingBatchService.EmbeddingResult result = results.get(i);
            if (result.isSuccess()) {
                TextChunk chunk = chunks.get(i);
                String vectorStr = RetrievalUtils.vectorToString(result.getEmbedding());
                jdbcTemplate.update(
                        "INSERT INTO rag_embeddings (document_id, chunk_text, chunk_index, embedding, chunk_start_pos, chunk_end_pos, created_at) " +
                                "VALUES (?, ?, ?, ?::vector, ?, ?, NOW())",
                        documentId, chunk.text(), i, vectorStr, chunk.startPos(), chunk.endPos());
                stored++;
            } else {
                log.warn("Embedding failed for chunk {} in doc {}: {}", i, documentId, result.getError());
            }
        }
        return stored;
    }

    private List<Document> buildVectorStoreDocuments(Long documentId, List<TextChunk> chunks) {
        List<Document> documents = new java.util.ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            documents.add(Document.builder()
                    .id(documentId + "-" + i)
                    .text(chunk.text())
                    .metadata(Map.of(
                            "documentId", String.valueOf(documentId),
                            "chunkIndex", String.valueOf(i),
                            "chunkStartPos", String.valueOf(chunk.startPos()),
                            "chunkEndPos", String.valueOf(chunk.endPos())
                    ))
                    .build());
        }
        return documents;
    }

    // ==================== 提取的共享逻辑 ====================

    /** 嵌入准备结果：文档 + 缓存检查 + 分块结果 */
    private record EmbedPrepareResult(RagDocument doc, Map<String, Object> cached, List<TextChunk> chunks) {}

    /**
     * 统一的嵌入准备流程：查找文档 → 检查缓存 → 验证内容 → 分块
     * @return 准备结果；cached 非 null 表示缓存命中直接返回
     */
    private EmbedPrepareResult prepareForEmbedding(Long documentId, boolean force) {
        RagDocument doc = findDocument(documentId);

        if (!force) {
            Map<String, Object> cached = checkEmbeddingCache(doc);
            if (cached != null) {
                return new EmbedPrepareResult(doc, cached, null);
            }
        }

        String content = validateContent(doc);
        List<TextChunk> chunks = chunker.split(content);
        if (chunks.isEmpty()) {
            return new EmbedPrepareResult(doc, Map.of(
                    "message", "文档内容太短，无需分块",
                    "documentId", documentId,
                    "chunksCreated", 0
            ), null);
        }
        log.info("Document {} split into {} chunks", documentId, chunks.size());
        return new EmbedPrepareResult(doc, null, chunks);
    }

    /** 标记嵌入完成：COMPLETED + 更新内容哈希 */
    private void completeEmbedding(RagDocument doc, int chunkCount) {
        doc.setProcessingStatus("COMPLETED");
        doc.setEmbeddedContentHash(doc.getContentHash());
        documentRepository.save(doc);
    }

    /** 构建成功响应 Map */
    private Map<String, Object> buildSuccessResult(Long docId, int chunks, int stored, String status,
                                                     String... extraEntries) {
        Map<String, Object> result = new HashMap<>();
        result.put("message", "嵌入向量生成完成");
        result.put("documentId", docId);
        result.put("chunksCreated", chunks);
        result.put("embeddingsStored", stored);
        result.put("status", status);
        for (int i = 0; i < extraEntries.length; i += 2) {
            result.put(extraEntries[i], extraEntries[i + 1]);
        }
        return result;
    }
}
