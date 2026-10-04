package com.springairag.core.service;

import com.springairag.api.dto.BatchCreateResponse;
import com.springairag.api.dto.BatchCreateResponse.DocumentResult;
import com.springairag.api.dto.BatchDeleteItem;
import com.springairag.api.dto.BatchDeleteResponse;
import com.springairag.api.dto.BatchDeleteSummary;
import com.springairag.api.dto.DocumentDeleteResponse;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.embeddingjob.EmbeddingPolicyResolver;
import com.springairag.core.embeddingjob.EmbeddingPolicySupport;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.logging.SensitiveDataMaskingConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Batch Document Operations Service
 *
 * <p>Handles batch create/delete of documents, supports deduplication by content SHA-256 hash.
 * Batch create supports optional embedding vector generation (embed=true).
 */
@Service
public class BatchDocumentService {

    private static final Logger log = LoggerFactory.getLogger(BatchDocumentService.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final RagDocumentRepository documentRepository;
    private final RagEmbeddingRepository embeddingRepository;
    private EmbeddingDispatchService dispatchService; // optional-claim: unconditional @Service；守卫在同文件之外——使用点紧跟 `EmbeddingPolicySupport.requireJobsEnabled(dispatchService)`，它做 null 检查并抛 EMBEDDING_JOBS_DISABLED 而不是 NPE（Batch 850 普查时才发现这道检查一直在）
    private final DocumentMutationService documentMutationService; // Batch 855：`required = false` 的 setter 已删，改成第 3 个必填构造器参数。createLocal 与 hardDeleteLocal 两处都无条件调用它，缺 bean 时抛裸 NPE 而不是在启动时失败

    /**
     * 第三个参数 {@code documentEmbedService} 曾属于"没有
     * {@code DocumentMutationService} 就自己驱动嵌入"那条 legacy 分支，
     * 已随 Batch 832 删除。嵌入现在由 {@code DocumentMutationService}
     * 按策略执行——本服务既不调它、也不需要它。
     */
    public BatchDocumentService(RagDocumentRepository documentRepository,
                                 RagEmbeddingRepository embeddingRepository,
                                 DocumentMutationService documentMutationService) {
        this.documentRepository = documentRepository;
        this.embeddingRepository = embeddingRepository;
        this.documentMutationService = documentMutationService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDispatchService(EmbeddingDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    /**
     * Batch create documents (automatic deduplication)
     *
     * <p>By default, vectors are not embedded. To embed automatically after creation, use
     * {@link #batchCreateDocuments(List, boolean, Long, boolean)} and pass embed=true.
     *
     * @param requests document request list
     * @return batch creation result
     */
    public BatchCreateResponse batchCreateDocuments(List<DocumentRequest> requests) {
        if (requests == null) {
            throw new IllegalArgumentException("requests must not be null");
        }
        return batchCreateDocuments(requests, false, null, false);
    }

    /**
     * Batch create documents (with optional auto-embedding after creation)
     *
     * @param requests     document request list
     * @param embed        whether to embed vectors after creation
     * @param collectionId associated collection ID (effective only when embed=true, can be null)
     * @param force        whether to force re-embedding (effective only when embed=true)
     * @return batch creation result
     */
    public BatchCreateResponse batchCreateDocuments(List<DocumentRequest> requests,
                                                     boolean embed,
                                                     Long collectionId,
                                                     boolean force) {
        return batchCreateDocuments(requests, embed, collectionId, force, null);
    }

    public BatchCreateResponse batchCreateDocuments(List<DocumentRequest> requests,
                                                     boolean embed,
                                                     Long collectionId,
                                                     boolean force,
                                                     EmbeddingPolicy embeddingPolicy) {
        return batchCreateDocuments(
                requests, embed, collectionId, force, embeddingPolicy, null);
    }

    public BatchCreateResponse batchCreateDocuments(
            List<DocumentRequest> requests,
            boolean embed,
            Long collectionId,
            boolean force,
            EmbeddingPolicy embeddingPolicy,
            String idempotencyKeyPrefix) {
        if (requests == null) {
            throw new IllegalArgumentException("requests must not be null");
        }
        EmbeddingPolicy policy = EmbeddingPolicyResolver.resolve(embeddingPolicy, embed);
        if (policy == EmbeddingPolicy.ASYNC) {
            EmbeddingPolicySupport.requireJobsEnabled(dispatchService);
        }
        log.info("Batch creating {} documents (embed={}, policy={}, collectionId={}, force={})",
                requests.size(), embed, policy, collectionId, force);

        List<DocumentResult> results = new ArrayList<>(requests.size());
        int created = 0, skipped = 0, failed = 0;

        for (int i = 0; i < requests.size(); i++) {
            DocumentResult itemResult = createSingleDocumentSafely(
                    requests.get(i), i, policy, collectionId, force,
                    itemIdempotencyKey(idempotencyKeyPrefix, i));
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

    private DocumentResult createSingleDocumentSafely(
            DocumentRequest req,
            int index,
            EmbeddingPolicy policy,
            Long collectionId,
            boolean force,
            String idempotencyKey) {
        try {
            return createSingleDocumentWithCoordinator(
                    req, policy, collectionId, force, idempotencyKey);
        } catch (Exception e) {
            // 单条失败不能中止整个 batch；异常必须先逃出事务回调以触发回滚。
            String error = safeError(e.getMessage());
            log.error("Failed to create document at index {}: {}", index, error);
            return new DocumentResult(null, req.getTitle(), false, error);
        }
    }

    private DocumentResult createSingleDocumentWithCoordinator(
            DocumentRequest request,
            EmbeddingPolicy policy,
            Long defaultCollectionId,
            boolean force,
            String idempotencyKey) {
        Long collectionId = request.getCollectionId() != null
                ? request.getCollectionId() : defaultCollectionId;
        DocumentMutationService.CreatedLocal created =
                documentMutationService.createLocal(
                        request,
                        collectionId,
                        policy,
                        force,
                        "BATCH_CREATE",
                        idempotencyKey,
                        null,
                        null,
                        null);
        String action = created.mutation().action();
        return new DocumentResult(
                created.document().getId(),
                created.document().getTitle(),
                "CREATED".equals(action),
                null,
                created.mutation().embeddingAction(),
                created.mutation().embeddingJobId(),
                created.mutation().embeddingBatchId());
    }

    /**
     * Delete a single document (cascades to delete embedding vectors)
     *
     * @param id document ID
     * @return deletion result (including number of embedding vectors deleted)
     */
    @Transactional
    public DocumentDeleteResponse deleteDocument(Long id) {
        log.info("Deleting document: id={}", id);

        if (!documentRepository.existsById(id)) {
            throw new com.springairag.core.exception.DocumentNotFoundException(id);
        }

        long embCount = embeddingRepository.countByDocumentId(id);
        embeddingRepository.deleteByDocumentId(id);
        documentRepository.deleteById(id);

        log.info("Document deleted: id={}, embeddings removed: {}", id, embCount);

        return new DocumentDeleteResponse("Document deleted", id, embCount);
    }

    /**
     * Batch delete documents (cascades to delete embedding vectors)
     *
     * @param ids document ID list
     * @return batch operation result
     */
    @Transactional
    public BatchDeleteResponse batchDeleteDocuments(List<Long> ids) {
        if (ids == null) {
            throw new IllegalArgumentException("ids must not be null");
        }
        if (ids.size() > 100) {
            throw new IllegalArgumentException("Batch delete limited to 100 documents per request");
        }

        log.info("Batch deleting {} documents", ids.size());

        List<RagDocument> existing = documentRepository.findAllById(ids);
        boolean hasExternalManaged = existing.stream().anyMatch(document ->
                document.getExternalId() != null
                        && !document.getExternalId().isBlank());
        if (hasExternalManaged) {
            throw new com.springairag.core.exception.DocumentRevisionConflictException(
                    "Externally managed documents must be deleted by source identity");
        }

        List<BatchDeleteItem> results = new ArrayList<>(ids.size());
        for (Long id : ids) {
            results.add(deleteSingleDocument(id));
        }

        int deleted = (int) results.stream().filter(r -> "DELETED".equals(r.status())).count();
        int notFound = results.size() - deleted;

        log.info("Batch delete completed: {} deleted, {} not found", deleted, notFound);

        return new BatchDeleteResponse(results, new BatchDeleteSummary(ids.size(), deleted, notFound));
    }

    private BatchDeleteItem deleteSingleDocument(Long id) {
        RagDocument document = documentRepository.findById(id).orElse(null);
        if (document == null) {
            return new BatchDeleteItem(id, "NOT_FOUND");
        }
        long revision = document.getDocumentRevision() == null
                ? 1L : document.getDocumentRevision();
        documentMutationService.hardDeleteLocal(id, revision);
        return new BatchDeleteItem(id, "DELETED");
    }

    /**
     * Calculate SHA-256 hash of text
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

    private String safeError(String value) {
        String raw = value == null || value.isBlank()
                ? "Document creation failed"
                : value;
        String masked = SensitiveDataMaskingConverter.maskSensitiveData(raw);
        return masked.length() <= MAX_ERROR_LENGTH
                ? masked
                : masked.substring(0, MAX_ERROR_LENGTH);
    }

    private String itemIdempotencyKey(String prefix, int index) {
        if (prefix == null || prefix.isBlank()) {
            return null;
        }
        return prefix.trim() + ":" + index;
    }
}
