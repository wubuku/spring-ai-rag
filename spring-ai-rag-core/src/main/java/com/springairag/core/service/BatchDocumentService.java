package com.springairag.core.service;

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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 批量文档操作服务
 *
 * <p>负责批量创建、删除文档，支持按内容 SHA-256 哈希去重。
 */
@Service
public class BatchDocumentService {

    private static final Logger log = LoggerFactory.getLogger(BatchDocumentService.class);

    private final RagDocumentRepository documentRepository;
    private final RagEmbeddingRepository embeddingRepository;

    public BatchDocumentService(RagDocumentRepository documentRepository,
                                 RagEmbeddingRepository embeddingRepository) {
        this.documentRepository = documentRepository;
        this.embeddingRepository = embeddingRepository;
    }

    /**
     * 批量创建文档（自动去重）
     *
     * @param requests 文档请求列表
     * @return 批量操作结果（results + summary）
     */
    public Map<String, Object> batchCreateDocuments(List<DocumentRequest> requests) {
        log.info("Batch creating {} documents", requests.size());

        List<Map<String, Object>> results = new ArrayList<>(requests.size());
        int created = 0, duplicated = 0, failed = 0;

        for (int i = 0; i < requests.size(); i++) {
            Map<String, Object> itemResult = createSingleDocument(requests.get(i), i);
            String status = (String) itemResult.get("status");
            switch (status) {
                case "CREATED" -> created++;
                case "DUPLICATE" -> duplicated++;
                default -> failed++;
            }
            results.add(itemResult);
        }

        log.info("Batch create completed: {} created, {} duplicated, {} failed", created, duplicated, failed);

        return Map.of(
                "results", results,
                "summary", Map.of(
                        "total", requests.size(),
                        "created", created,
                        "duplicated", duplicated,
                        "failed", failed
                )
        );
    }

    private Map<String, Object> createSingleDocument(DocumentRequest req, int index) {
        Map<String, Object> itemResult = new HashMap<>();
        itemResult.put("index", index);
        itemResult.put("title", req.getTitle());

        try {
            String content = req.getContent();
            String contentHash = computeSha256(content);

            List<RagDocument> existing = documentRepository.findByContentHash(contentHash);
            if (!existing.isEmpty()) {
                RagDocument dup = existing.get(0);
                itemResult.put("status", "DUPLICATE");
                itemResult.put("id", dup.getId());
                itemResult.put("message", "内容已存在");
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
            }
        } catch (Exception e) { // Resilience: single item failure, continue batch
            log.error("Failed to create document at index {}: {}", index, e.getMessage());
            itemResult.put("status", "FAILED");
            itemResult.put("error", e.getMessage());
        }
        return itemResult;
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
                "message", "文档已删除",
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
            throw new IllegalArgumentException("单次批量删除不超过 100 条");
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

        return Map.of(
                "results", results,
                "summary", Map.of(
                        "total", ids.size(),
                        "deleted", deleted,
                        "notFound", notFound
                )
        );
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
