package com.springairag.core.service;

import com.springairag.api.dto.BatchEmbedProgressEvent;
import com.springairag.api.dto.EmbedProgressEvent;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.logging.SensitiveDataMaskingConverter;
import com.springairag.core.repository.RagDocumentRepository;

import java.util.Objects;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.documents.chunk.HierarchicalTextChunker;
import com.springairag.documents.chunk.TextChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Document embedding service
 *
 * <p>Responsible for chunking document content, generating embedding vectors, and storing them.
 * All writes use the profile-aware rag_embeddings path.
 */
@Service
public class DocumentEmbedService {

    private static final Logger log = LoggerFactory.getLogger(DocumentEmbedService.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final HierarchicalTextChunker chunker;
    private final RagProperties ragProperties;
    private final RagDocumentRepository documentRepository;
    private final EmbeddingBatchService embeddingBatchService;
    private final EmbeddingPersistenceService persistenceService;
    private final EmbeddingProfileProvider profileProvider;

    public DocumentEmbedService(RagDocumentRepository documentRepository,
                                 EmbeddingBatchService embeddingBatchService,
                                 EmbeddingPersistenceService persistenceService,
                                 EmbeddingProfileProvider profileProvider,
                                 RagProperties ragProperties) {
        this.documentRepository = documentRepository;
        this.embeddingBatchService = embeddingBatchService;
        this.persistenceService = persistenceService;
        this.profileProvider = profileProvider;
        this.chunker = new HierarchicalTextChunker(
                ragProperties.getChunk().getDefaultChunkSize(),
                ragProperties.getChunk().getMinChunkSize(),
                ragProperties.getChunk().getDefaultChunkOverlap());
        this.ragProperties = ragProperties;
    }

    /**
     * 为文档生成活动 Profile 的向量。
     */
    public Map<String, Object> embedDocument(Long documentId) {
        Objects.requireNonNull(documentId);
        return embedDocument(documentId, false);
    }

    public Map<String, Object> embedDocument(Long documentId, boolean force) {
        Objects.requireNonNull(documentId);
        return embedDocumentWithProgress(documentId, force, null);
    }

    /**
     * Checks the active profile cache without invoking the embedding provider.
     *
     * <p>Structured-record payload-only updates use this to avoid a redundant
     * provider call while still allowing a record first persisted with
     * {@code embed=false} to be embedded by a later upsert.
     */
    public boolean hasFreshEmbedding(RagDocument document) {
        if (document == null || document.getId() == null
                || document.getContentHash() == null
                || document.getContentHash().isBlank()) {
            return false;
        }
        EmbeddingProfile profile = profileProvider.getActiveProfile();
        return persistenceService.findCacheState(
                document.getId(),
                profile,
                document.getContentHash(),
                buildChunkerVersion(document)).hit();
    }

    /**
     * Generates embedding vectors for a document with progress callback (for SSE streaming)
     *
     * @param documentId document ID
     * @param force whether to force re-embedding
     * @param progressCallback progress callback, can be null
     * @return operation result
     */
    public Map<String, Object> embedDocumentWithProgress(Long documentId, boolean force,
            java.util.function.Consumer<EmbedProgressEvent> progressCallback) {
        return embedDocumentInternal(
                documentId,
                force,
                progressCallback,
                EmbeddingCommitGuard.allowAll(),
                true);
    }

    /**
     * 持久化 worker 专用入口。provider 失败由 job 状态机记录，不改写文档版本；
     * provider 成功后必须先通过提交门。
     */
    public Map<String, Object> embedDocumentForJob(
            Long documentId,
            boolean force,
            EmbeddingCommitGuard commitGuard) {
        return embedDocumentInternal(
                documentId,
                force,
                null,
                Objects.requireNonNull(commitGuard),
                false);
    }

    private Map<String, Object> embedDocumentInternal(
            Long documentId,
            boolean force,
            java.util.function.Consumer<EmbedProgressEvent> progressCallback,
            EmbeddingCommitGuard commitGuard,
            boolean recordProviderFailure) {
        Objects.requireNonNull(documentId);
        EmbeddingProfile profile = profileProvider.getActiveProfile();
        log.info("Generating embeddings for document: id={}, force={}, profile={}",
                documentId, force, profile.profileKey());

        maybeEmit(progressCallback, EmbedProgressEvent.preparing(documentId));

        EmbedPrepareResult prep = prepareForEmbedding(documentId, force, profile);
        if (prep.cached() != null) {
            maybeEmit(progressCallback, EmbedProgressEvent.completed(documentId, 0));
            return prep.cached();
        }

        List<TextChunk> chunks = prep.chunks();
        maybeEmit(progressCallback, EmbedProgressEvent.chunking(documentId, chunks.size()));

        List<String> texts = chunks.stream().map(TextChunk::text).toList();
        List<EmbeddingBatchService.EmbeddingResult> results;
        try {
            results = embeddingBatchService.createEmbeddingsBatch(texts);
        } catch (RuntimeException e) {
            String error = safeError("Embedding provider call failed: " + e.getMessage());
            if (recordProviderFailure) {
                persistenceService.recordFailureIfNoCompleted(
                        documentId,
                        prep.documentVersion(),
                        prep.contentHash(),
                        profile,
                        prep.chunkerVersion(),
                        error);
            }
            log.warn("Document {} embedding provider call failed without replacing old vectors",
                    documentId, e);
            return buildResult(documentId, 0, 0, "FAILED", profile, error);
        }
        emitEmbeddingProgress(
                progressCallback, documentId, results == null ? 0 : results.size());

        String validationError = validateEmbeddingResults(chunks, results, profile);
        if (validationError != null) {
            validationError = safeError(validationError);
            if (recordProviderFailure) {
                persistenceService.recordFailureIfNoCompleted(
                        documentId,
                        prep.documentVersion(),
                        prep.contentHash(),
                        profile,
                        prep.chunkerVersion(),
                        validationError);
            }
            log.warn("Document {} embedding failed without replacing old vectors: {}",
                    documentId, validationError);
            return buildResult(documentId, 0, 0, "FAILED", profile, validationError);
        }

        maybeEmit(progressCallback,
                EmbedProgressEvent.storing(documentId, chunks.size(), chunks.size()));
        replaceWithOneVersionRetry(
                documentId, prep, profile, chunks, results, commitGuard);
        maybeEmit(progressCallback, EmbedProgressEvent.completed(documentId, chunks.size()));
        log.info("Document {} embedding completed: chunks={}, profile={}",
                documentId, chunks.size(), profile.profileKey());
        return buildResult(
                documentId, chunks.size(), chunks.size(), "COMPLETED", profile, null);
    }

    /** Safely emits a progress callback (null-safe) */
    private void maybeEmit(java.util.function.Consumer<EmbedProgressEvent> cb, EmbedProgressEvent event) {
        if (cb != null) cb.accept(event);
    }

    /** Emits embedding progress in batches (one notification per item) */
    private void emitEmbeddingProgress(java.util.function.Consumer<EmbedProgressEvent> cb,
                                       Long documentId, int total) {
        if (cb == null) return;
        for (int i = 0; i < total; i++) {
            cb.accept(EmbedProgressEvent.embedding(documentId, i + 1, total));
        }
    }

    /**
     * Batch generates embedding vectors for multiple documents
     *
     * @param documentIds list of document IDs
     * @return batch operation result (results + summary)
     */
    public Map<String, Object> batchEmbedDocuments(List<Long> documentIds) {
        if (documentIds == null) {
            throw new IllegalArgumentException("documentIds must not be null");
        }
        if (documentIds.size() > 50) {
            throw new IllegalArgumentException("Batch embedding limited to 50 documents per request (API rate limit)");
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
     * Batch embeds documents with SSE progress callback
     *
     * @param documentIds list of document IDs
     * @param progressCallback progress callback, called after each document is processed
     * @return batch operation result (results + summary)
     */
    public Map<String, Object> batchEmbedDocumentsWithProgress(
            List<Long> documentIds,
            Consumer<BatchEmbedProgressEvent> progressCallback) {
        if (documentIds.size() > 50) {
            throw new IllegalArgumentException("Batch embedding limited to 50 documents per request (API rate limit)");
        }

        log.info("Batch embedding with progress: {} documents", documentIds.size());

        List<Map<String, Object>> results = new java.util.ArrayList<>(documentIds.size());
        int[] counters = {0, 0, 0, 0}; // success, failed, skipped, cached

        for (int i = 0; i < documentIds.size(); i++) {
            Long id = documentIds.get(i);
            Map<String, Object> itemResult = embedSingleDocument(id);
            results.add(itemResult);

            counters = updateBatchCounters(itemResult, counters);
            sendDocumentProgress(progressCallback, i, documentIds.size(), id, itemResult, counters);
        }

        log.info("Batch embed with progress completed: {} success, {} cached, {} failed, {} skipped",
                counters[3], counters[0], counters[1], counters[2]);

        return buildBatchResult(documentIds.size(), results, counters);
    }

    /** Updates running counters {success, failed, skipped, cached} from itemResult, returns updated array */
    private int[] updateBatchCounters(Map<String, Object> itemResult, int[] counters) {
        int[] updated = counters.clone();
        String status = (String) itemResult.get("status");
        switch (status) {
            case "COMPLETED" -> updated[0]++;
            case "FAILED" -> updated[1]++;
            case "CACHED" -> updated[3]++;
            default -> updated[2]++; // SKIPPED or other
        }
        return updated;
    }

    /** Sends PREPARING event and final phase event for a single document in batch */
    private void sendDocumentProgress(Consumer<BatchEmbedProgressEvent> callback, int docIndex,
                                      int totalDocs, Long docId, Map<String, Object> itemResult,
                                      int[] counters) {
        String status = (String) itemResult.get("status");
        int success = counters[0], failed = counters[1], skipped = counters[2], cached = counters[3];
        int docNum = docIndex + 1;
        String phase = phaseForStatus(status);

        // Send PREPARING event before processing
        sendProgress(callback, docIndex, totalDocs, docId, "PREPARING", 0, 0,
                "Preparing document " + docNum + "/" + totalDocs, success, failed, cached);

        // Send final phase event
        int current = 0, total = 0;
        String message = phaseMessage(status, itemResult, docNum, totalDocs);
        if ("COMPLETED".equals(status) || "CACHED".equals(status)) {
            Object chunksOrStored = itemResult.getOrDefault("chunksCreated",
                    itemResult.getOrDefault("embeddingsStored", 0));
            current = total = (chunksOrStored instanceof Number n) ? n.intValue() : 0;
        }
        sendProgress(callback, docIndex, totalDocs, docId, phase, current, total,
                message, success, failed, cached);
    }

    /** Maps status string to SSE phase name */
    private String phaseForStatus(String status) {
        return switch (status) {
            case "COMPLETED" -> "COMPLETED";
            case "FAILED" -> "FAILED";
            case "CACHED" -> "CACHED";
            default -> "SKIPPED";
        };
    }

    /** Builds user-friendly message for a batch document progress event */
    private String phaseMessage(String status, Map<String, Object> itemResult, int docNum, int totalDocs) {
        return switch (status) {
            case "COMPLETED" -> "Document " + docNum + "/" + totalDocs + " completed";
            case "FAILED" -> "Document " + docNum + "/" + totalDocs + " failed: " + itemResult.get("error");
            case "CACHED" -> "Document " + docNum + "/" + totalDocs + " (cached)";
            default -> "Document " + docNum + "/" + totalDocs + " skipped";
        };
    }

    /** Builds the final batch result Map from counters */
    private Map<String, Object> buildBatchResult(int total, List<Map<String, Object>> results, int[] counters) {
        return Map.of(
                "results", results,
                "summary", Map.of(
                        "total", total,
                        "success", counters[0],
                        "cached", counters[3],
                        "failed", counters[1],
                        "skipped", counters[2]
                )
        );
    }

    private void sendProgress(Consumer<BatchEmbedProgressEvent> callback, int docIndex, int totalDocs,
                              Long docId, String phase, int current, int total,
                              String message, int success, int failed, int cached) {
        if (callback != null) {
            try {
                callback.accept(new BatchEmbedProgressEvent(
                        docIndex, totalDocs, docId, phase, current, total,
                        message, success, failed, cached));
            } catch (Exception e) {
                // Best-effort: progress reporting failure must not abort the batch operation
                log.warn("Progress callback failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Single document embedding processing (called by batchEmbedDocuments)
     */
    private Map<String, Object> embedSingleDocument(Long id) {
        try {
            return embedDocument(id, false);
        } catch (Exception e) { // Resilience: single document embed failure, record error
            log.error("Failed to embed document {}: {}", id, e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("documentId", id);
            result.put("status", e instanceof DocumentNotFoundException ? "NOT_FOUND" : "FAILED");
            result.put("error", e.getMessage());
            return result;
        }
    }

    // ==================== Internal Methods ====================

    private RagDocument findDocument(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    private String validateContent(RagDocument doc) {
        String content = doc.getContent();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Document content is empty: documentId=" + doc.getId());
        }
        return content;
    }

    // ==================== Extracted Shared Logic ====================

    private record EmbedPrepareResult(
            Map<String, Object> cached,
            List<TextChunk> chunks,
            String contentHash,
            long documentVersion,
            String chunkerVersion) {
    }

    /**
     * Unified embedding preparation flow: find document → check cache → validate content → chunk
     * @return preparation result; non-null cached means cache hit, return directly
     */
    private EmbedPrepareResult prepareForEmbedding(
            Long documentId, boolean force, EmbeddingProfile profile) {
        RagDocument doc = findDocument(documentId);
        String content = validateContent(doc);
        long version = doc.getVersion() == null ? 0 : doc.getVersion();
        String contentHash = doc.getContentHash();
        if (contentHash == null || contentHash.isBlank()) {
            contentHash = BatchDocumentService.computeSha256(content);
            persistenceService.ensureContentHash(documentId, version, contentHash);
            version++;
            doc.setContentHash(contentHash);
            doc.setVersion(version);
        }
        String chunkerVersion = buildChunkerVersion(doc);

        if (!force) {
            EmbeddingPersistenceService.CacheState cache = persistenceService.findCacheState(
                    documentId,
                    profile,
                    contentHash,
                    chunkerVersion);
            if (cache.hit()) {
                Map<String, Object> cached = buildResult(
                        documentId, cache.chunkCount(), cache.chunkCount(),
                        "CACHED", profile, null);
                cached.put("cached", true);
                cached.put("message",
                        "Embedding already exists for the active profile and content");
                return new EmbedPrepareResult(cached, null, contentHash, version, chunkerVersion);
            }
        }

        List<TextChunk> chunks = splitForEmbedding(doc, content);
        if (chunks.isEmpty()) {
            Map<String, Object> failed = buildResult(
                    documentId, 0, 0, "FAILED", profile,
                    "Non-blank document produced no chunks");
            return new EmbedPrepareResult(failed, null, contentHash, version, chunkerVersion);
        }
        log.info("Document {} split into {} chunks", documentId, chunks.size());
        return new EmbedPrepareResult(null, chunks, contentHash, version, chunkerVersion);
    }

    private String validateEmbeddingResults(
            List<TextChunk> chunks,
            List<EmbeddingBatchService.EmbeddingResult> results,
            EmbeddingProfile profile) {
        if (results == null || results.size() != chunks.size()) {
            return "Embedding result count mismatch: expected=" + chunks.size()
                    + ", actual=" + (results == null ? 0 : results.size());
        }
        for (int i = 0; i < results.size(); i++) {
            EmbeddingBatchService.EmbeddingResult result = results.get(i);
            if (result == null || !result.isSuccess()) {
                return "Embedding failed for chunk " + i + ": "
                        + (result == null ? "missing result" : result.getError());
            }
            if (!Objects.equals(chunks.get(i).text(), result.getText())) {
                return "Embedding response order mismatch at chunk " + i;
            }
            float[] vector = result.getEmbedding();
            if (vector == null) {
                return "Embedding is null at chunk " + i;
            }
            if (vector.length != profile.dimensions()) {
                return "Embedding dimension mismatch at chunk " + i
                        + ": expected=" + profile.dimensions()
                        + ", actual=" + vector.length;
            }
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    return "Embedding contains non-finite value at chunk " + i;
                }
            }
        }
        return null;
    }

    private void replaceWithOneVersionRetry(
            Long documentId,
            EmbedPrepareResult prep,
            EmbeddingProfile profile,
            List<TextChunk> chunks,
            List<EmbeddingBatchService.EmbeddingResult> results,
            EmbeddingCommitGuard commitGuard) {
        commitGuard.verify();
        try {
            persistenceService.replace(
                    documentId,
                    prep.documentVersion(),
                    prep.contentHash(),
                    profile,
                    prep.chunkerVersion(),
                    chunks,
                    results);
        } catch (IllegalStateException firstFailure) {
            RagDocument current = findDocument(documentId);
            long currentVersion = current.getVersion() == null ? 0 : current.getVersion();
            boolean reusable = Boolean.TRUE.equals(current.getEnabled())
                    && Objects.equals(prep.contentHash(), current.getContentHash())
                    && Objects.equals(prep.chunkerVersion(), buildChunkerVersion(current));
            if (!reusable || currentVersion == prep.documentVersion()) {
                throw firstFailure;
            }
            commitGuard.verify();
            persistenceService.replace(
                    documentId,
                    currentVersion,
                    prep.contentHash(),
                    profile,
                    prep.chunkerVersion(),
                    chunks,
                    results);
        }
    }

    private List<TextChunk> splitForEmbedding(RagDocument doc, String content) {
        if (RagDocument.JSON_RECORD.equals(doc.getDocumentType())) {
            return List.of(new TextChunk(content, 0, content.length()));
        }
        return chunker.split(content);
    }

    private String buildChunkerVersion(RagDocument doc) {
        if (RagDocument.JSON_RECORD.equals(doc.getDocumentType())) {
            return "json-record-v1:single";
        }
        return "hierarchical-v2:"
                + ragProperties.getChunk().getDefaultChunkSize() + ":"
                + ragProperties.getChunk().getMinChunkSize() + ":"
                + ragProperties.getChunk().getDefaultChunkOverlap();
    }

    private Map<String, Object> buildResult(
            Long docId,
            int chunks,
            int stored,
            String status,
            EmbeddingProfile profile,
            String error) {
        Map<String, Object> result = new HashMap<>();
        result.put("message", "COMPLETED".equals(status)
                ? "Embedding generation completed"
                : "Embedding generation " + status.toLowerCase());
        result.put("documentId", docId);
        result.put("chunksCreated", chunks);
        result.put("embeddingsStored", stored);
        result.put("status", status);
        result.put("embeddingProfileKey", profile.profileKey());
        result.put("embeddingDimensions", profile.dimensions());
        if (error != null) {
            result.put("error", safeError(error));
        }
        return result;
    }

    private String safeError(String error) {
        if (error == null || error.isBlank()) {
            return "Embedding failed";
        }
        String masked = SensitiveDataMaskingConverter.maskSensitiveData(error);
        return masked.length() <= MAX_ERROR_LENGTH
                ? masked : masked.substring(0, MAX_ERROR_LENGTH);
    }
}
