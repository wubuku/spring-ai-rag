package com.springairag.core.service;

import com.springairag.api.dto.BatchCreateResponse;
import com.springairag.api.dto.BatchCreateResponse.DocumentResult;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Batch Document Operations Service
 *
 * <p>Handles batch create/delete of documents, supports deduplication by content SHA-256 hash.
 * Batch create supports optional embedding vector generation (embed=true).
 */
@Service
public class BatchDocumentService {

    private static final Logger log = LoggerFactory.getLogger(BatchDocumentService.class);

    private final RagDocumentRepository documentRepository;
    private final RagEmbeddingRepository embeddingRepository;
    private final DocumentEmbedService documentEmbedService;

    public BatchDocumentService(RagDocumentRepository documentRepository,
                                 RagEmbeddingRepository embeddingRepository,
                                 DocumentEmbedService documentEmbedService) {
        this.documentRepository = documentRepository;
        this.embeddingRepository = embeddingRepository;
        this.documentEmbedService = documentEmbedService;
    }

    /**
     * 批量创建文档（自动去重）
     *
     * <p>默认不嵌入向量。如需创建后自动嵌入，请使用
     * {@link #batchCreateDocuments(List, boolean, Long, boolean)} 并传入 embed=true。
     *
     * @param requests 文档请求列表
     * @return 批量创建结果
     */
    public BatchCreateResponse batchCreateDocuments(List<DocumentRequest> requests) {
        return batchCreateDocuments(requests, false, null, false);
    }

    /**
     * 批量创建文档（支持创建后自动嵌入向量）
     *
     * @param requests     文档请求列表
     * @param embed        是否在创建后自动嵌入向量
     * @param collectionId 关联的知识库 ID（仅 embed=true 时生效，可为 null）
     * @param force        是否强制重嵌入（仅 embed=true 时生效）
     * @return 批量创建结果
     */
    public BatchCreateResponse batchCreateDocuments(List<DocumentRequest> requests,
                                                     boolean embed,
                                                     Long collectionId,
                                                     boolean force) {
        log.info("Batch creating {} documents (embed={}, collectionId={}, force={})",
                requests.size(), embed, collectionId, force);

        List<DocumentResult> results = new ArrayList<>(requests.size());
        int created = 0, skipped = 0, failed = 0;

        for (int i = 0; i < requests.size(); i++) {
            DocumentResult itemResult = createSingleDocument(requests.get(i), i, embed, collectionId, force);
            results.add(itemResult);
            if (itemResult.error() != null) {
                failed++;
            } else if (!itemResult.newlyCreated()) {
                skipped++;
            } else {
                created++;
            }
        }

        log.info("Batch create completed: created={}, skipped={}, failed={}", created, skipped, failed);
        return new BatchCreateResponse(created, skipped, failed, results);
    }

    private DocumentResult createSingleDocument(DocumentRequest req, int index,
                                                boolean embed, Long collectionId, boolean force) {
        try {
            String contentHash = computeSha256(req.getContent());
            List<RagDocument> existing = documentRepository.findByContentHash(contentHash);

            RagDocument doc;
            boolean newlyCreated;

            if (!existing.isEmpty()) {
                doc = existing.get(0);
                newlyCreated = false;
                log.info("Duplicate content detected, using existing doc id={}", doc.getId());
            } else {
                doc = new RagDocument();
                doc.setTitle(req.getTitle());
                doc.setContent(req.getContent());
                doc.setSource(req.getSource());
                doc.setDocumentType(req.getDocumentType());
                doc.setMetadata(req.getMetadata());
                doc.setContentHash(contentHash);
                // Prefer per-doc collectionId, fall back to batch-level collectionId
                if (req.getCollectionId() != null) {
                    doc.setCollectionId(req.getCollectionId());
                } else {
                    doc.setCollectionId(collectionId);
                }
                doc = documentRepository.save(doc);
                newlyCreated = true;
                log.info("Document created: id={}", doc.getId());
            }

            // 嵌入向量（仅针对新建的或 force=true 的文档）
            if (embed && (newlyCreated || force)) {
                Map<String, Object> embedResult = documentEmbedService.embedDocument(doc.getId(), force);
                String status = (String) embedResult.get("status");
                if (!"COMPLETED".equals(status) && !"CACHED".equals(status)) {
                    String error = (String) embedResult.get("error");
                    // On embed failure, update document status to EMBEDDING_FAILED
                    doc.setProcessingStatus("EMBEDDING_FAILED");
                    documentRepository.save(doc);
                    return new DocumentResult(doc.getId(), doc.getTitle(), newlyCreated,
                            "Embedding failed: " + (error != null ? error : status));
                }
            }

            return new DocumentResult(doc.getId(), doc.getTitle(), newlyCreated, null);

        } catch (Exception e) {
            log.error("Failed to create document at index {}: {}", index, e.getMessage());
            return new DocumentResult(null, req.getTitle(), false, e.getMessage());
        }
    }

    /**
     * 删除单个文档（级联删除嵌入向量）
     *
     * @param id 文档 ID
     * @return 删除结果（含嵌入向量删除数量）
     */
    @Transactional
    public Map<String, String> deleteDocument(Long id) {
        log.info("Deleting document: id={}", id);

        if (!documentRepository.existsById(id)) {
            throw new com.springairag.core.exception.DocumentNotFoundException(id);
        }

        long embCount = embeddingRepository.countByDocumentId(id);
        embeddingRepository.deleteByDocumentId(id);
        documentRepository.deleteById(id);

        log.info("Document deleted: id={}, embeddings removed: {}", id, embCount);

        return Map.of(
                "message", "Document deleted",
                "id", String.valueOf(id),
                "embeddingsRemoved", String.valueOf(embCount)
        );
    }

    /**
     * 批量删除文档（级联删除嵌入向量）
     *
     * @param ids 文档 ID 列表
     * @return 批量操作结果
     */
    @Transactional
    public Map<String, Object> batchDeleteDocuments(List<Long> ids) {
        if (ids.size() > 100) {
            throw new IllegalArgumentException("Batch delete limited to 100 documents per request");
        }

        log.info("Batch deleting {} documents", ids.size());

        List<Map<String, Object>> results = new ArrayList<>(ids.size());

        // 批量删除嵌入向量
        embeddingRepository.deleteByDocumentIdIn(ids);

        for (Long id : ids) {
            results.add(deleteSingleDocument(id));
        }

        int deleted = (int) results.stream().filter(r -> "DELETED".equals(r.get("status"))).count();
        int notFound = results.size() - deleted;

        log.info("Batch delete completed: {} deleted, {} not found", deleted, notFound);

        return Map.of(
                "results", results,
                "summary", Map.of(
                        "total", ids.size(),
                        "deleted", deleted,
                        "notFound", notFound
                )
        );
    }

    private Map<String, Object> deleteSingleDocument(Long id) {
        Map<String, Object> itemResult = new java.util.HashMap<>();
        itemResult.put("id", id);

        if (documentRepository.existsById(id)) {
            documentRepository.deleteById(id);
            itemResult.put("status", "DELETED");
        } else {
            itemResult.put("status", "NOT_FOUND");
        }
        return itemResult;
    }

    /**
     * 计算文本的 SHA-256 哈希值
     */
    public static String computeSha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
