package com.springairag.core.service;

import com.springairag.api.dto.BatchCreateResponse;
import com.springairag.api.dto.BatchCreateResponse.DocumentResult;
import com.springairag.api.dto.BatchDeleteItem;
import com.springairag.api.dto.BatchDeleteResponse;
import com.springairag.api.dto.BatchDeleteSummary;
import com.springairag.api.dto.DocumentDeleteResponse;
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
        if (requests == null) {
            throw new IllegalArgumentException("requests must not be null");
        }
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

            // Embed vectors (only for newly created or force=true documents)
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
            // Resilience: individual document creation failure must not abort the entire batch
            log.error("Failed to create document at index {}: {}", index, e.getMessage());
            return new DocumentResult(null, req.getTitle(), false, e.getMessage());
        }
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

        List<BatchDeleteItem> results = new ArrayList<>(ids.size());

        // Batch delete embedding vectors
        embeddingRepository.deleteByDocumentIdIn(ids);

        for (Long id : ids) {
            results.add(deleteSingleDocument(id));
        }

        int deleted = (int) results.stream().filter(r -> "DELETED".equals(r.status())).count();
        int notFound = results.size() - deleted;

        log.info("Batch delete completed: {} deleted, {} not found", deleted, notFound);

        return new BatchDeleteResponse(results, new BatchDeleteSummary(ids.size(), deleted, notFound));
    }

    private BatchDeleteItem deleteSingleDocument(Long id) {
        if (documentRepository.existsById(id)) {
            documentRepository.deleteById(id);
            return new BatchDeleteItem(id, "DELETED");
        } else {
            return new BatchDeleteItem(id, "NOT_FOUND");
        }
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
}
