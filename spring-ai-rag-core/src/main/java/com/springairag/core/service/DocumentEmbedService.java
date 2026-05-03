package com.springairag.core.service;

import com.springairag.api.dto.BatchCreateAndEmbedRequest;
import com.springairag.api.dto.BatchEmbedProgressEvent;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.dto.EmbedProgressEvent;
import com.springairag.core.config.RagProperties;
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
import java.util.function.Consumer;

/**
 * Document embedding service
 *
 * <p>Responsible for chunking document content, generating embedding vectors, and storing them.
 * Supports two storage paths:
 * <ul>
 *   <li>JdbcTemplate path: stores to rag_embeddings, supports document_id association</li>
 *   <li>VectorStore path: stores to rag_vector_store, managed by Spring AI</li>
 * </ul>
 */
@Service
public class DocumentEmbedService {

    private static final Logger log = LoggerFactory.getLogger(DocumentEmbedService.class);

    private final HierarchicalTextChunker chunker;
    private final RagProperties ragProperties;
    private final RagDocumentRepository documentRepository;
    private final RagEmbeddingRepository embeddingRepository;
    private final EmbeddingBatchService embeddingBatchService;
    private final JdbcTemplate jdbcTemplate;
    private final VectorStore vectorStore;

    public DocumentEmbedService(RagDocumentRepository documentRepository,
                                 RagEmbeddingRepository embeddingRepository,
                                 EmbeddingBatchService embeddingBatchService,
                                 JdbcTemplate jdbcTemplate,
                                 @Autowired(required = false) VectorStore vectorStore,
                                 RagProperties ragProperties) {
        this.documentRepository = documentRepository;
        this.embeddingRepository = embeddingRepository;
        this.embeddingBatchService = embeddingBatchService;
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStore = vectorStore;
        this.chunker = new HierarchicalTextChunker(
                ragProperties.getChunk().getDefaultChunkSize(),
                ragProperties.getChunk().getMinChunkSize(),
                ragProperties.getChunk().getDefaultChunkOverlap());
        this.ragProperties = ragProperties;
    }

    /**
     * Generates embedding vectors for a document (JdbcTemplate path)
     *
     * <p>Flow: fetch document → chunk → generate embeddings → store to rag_embeddings → update status
     *
     * <p>Embedding cache: if document already has embeddings with COMPLETED status, skips re-embedding.
     * Pass {@code force=true} to force re-embedding (e.g., after embedding model change).
     *
     * @param documentId document ID
     * @return operation result (chunksCreated, embeddingsStored, status)
     */
    @Transactional
    public Map<String, Object> embedDocument(Long documentId) {
        return embedDocument(documentId, false);
    }

    /**
     * Generates embedding vectors for a document (JdbcTemplate path, supports force re-embedding)
     *
     * @param documentId document ID
     * @param force whether to force re-embedding (skip cache check)
     * @return operation result (chunksCreated, embeddingsStored, status)
     */
    @Transactional
    public Map<String, Object> embedDocument(Long documentId, boolean force) {
        return embedDocumentWithProgress(documentId, force, null);
    }

    /**
     * Generates embedding vectors for a document with progress callback (for SSE streaming)
     *
     * @param documentId document ID
     * @param force whether to force re-embedding
     * @param progressCallback progress callback, can be null
     * @return operation result
     */
    @Transactional
    public Map<String, Object> embedDocumentWithProgress(Long documentId, boolean force,
            java.util.function.Consumer<EmbedProgressEvent> progressCallback) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId must not be null");
        }
        log.info("Generating embeddings for document: id={}, force={}", documentId, force);

        maybeEmit(progressCallback, EmbedProgressEvent.preparing(documentId));

        EmbedPrepareResult prep = prepareForEmbedding(documentId, force);
        if (prep.cached() != null) {
            maybeEmit(progressCallback, EmbedProgressEvent.completed(documentId, 0));
            return prep.cached();
        }

        List<TextChunk> chunks = prep.chunks();
        RagDocument doc = prep.doc();
        doc.setProcessingStatus("PROCESSING");
        documentRepository.save(doc);

        maybeEmit(progressCallback, EmbedProgressEvent.chunking(documentId, chunks.size()));

        // Delete old vectors → generate embeddings → store
        embeddingRepository.deleteByDocumentId(documentId);
        List<String> texts = chunks.stream().map(TextChunk::text).toList();
        List<EmbeddingBatchService.EmbeddingResult> results =
                embeddingBatchService.createEmbeddingsBatch(texts);

        emitEmbeddingProgress(progressCallback, documentId, results.size());
        int stored = storeEmbeddings(documentId, chunks, results);

        maybeEmit(progressCallback, EmbedProgressEvent.storing(documentId, stored, chunks.size()));

        // Only mark as COMPLETED if at least some embeddings were successfully stored
        if (stored > 0) {
            completeEmbedding(doc, chunks.size());
            maybeEmit(progressCallback, EmbedProgressEvent.completed(documentId, stored));
            log.info("Document {} embedding completed: {}/{} chunks stored", documentId, stored, chunks.size());
            return buildSuccessResult(documentId, stored, stored, "COMPLETED");
        } else {
            // All embeddings failed - mark as FAILED, not COMPLETED
            doc.setProcessingStatus("FAILED");
            documentRepository.save(doc);
            maybeEmit(progressCallback, EmbedProgressEvent.completed(documentId, 0));
            log.warn("Document {} embedding failed: 0/{} chunks stored - check embedding service availability",
                    documentId, chunks.size());
            return buildSuccessResult(documentId, 0, 0, "FAILED");
        }
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
     * Generates embedding vectors for a document (VectorStore simplified path)
     *
     * @param documentId document ID
     * @return operation result
     * @throws IllegalStateException thrown when VectorStore is not configured
     */
    @Transactional
    public Map<String, Object> embedDocumentViaVectorStore(Long documentId) {
        return embedDocumentViaVectorStore(documentId, false);
    }

    /**
     * Generates embedding vectors for a document (VectorStore simplified path, supports force re-embedding)
     *
     * @param documentId document ID
     * @param force whether to force re-embedding (skip cache check)
     * @return operation result
     * @throws IllegalStateException thrown when VectorStore is not configured
     */
    @Transactional
    public Map<String, Object> embedDocumentViaVectorStore(Long documentId, boolean force) {
        if (vectorStore == null) {
            throw new IllegalStateException("VectorStore not configured, use embedDocument method instead");
        }

        log.info("Generating embeddings via VectorStore for document: id={}, force={}", documentId, force);

        EmbedPrepareResult prep = prepareForEmbedding(documentId, force);
        if (prep.cached() != null) return prep.cached();

        List<TextChunk> chunks = prep.chunks();
        RagDocument doc = prep.doc();
        doc.setProcessingStatus("PROCESSING");
        documentRepository.save(doc);

        vectorStore.add(buildVectorStoreDocuments(documentId, doc.getTitle(), chunks));
        completeEmbedding(doc, chunks.size());

        log.info("Document {} embedding via VectorStore completed: {} chunks stored", documentId, chunks.size());
        return buildSuccessResult(documentId, chunks.size(), chunks.size(), "COMPLETED",
                "storageTable", "rag_vector_store");
    }

    /**
     * Batch generates embedding vectors for multiple documents
     *
     * @param documentIds list of document IDs
     * @return batch operation result (results + summary)
     */
    @Transactional
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
    @Transactional
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
        Map<String, Object> result = new HashMap<>();
        result.put("documentId", id);
        try {
            processSingleEmbedding(id, result);
        } catch (Exception e) { // Resilience: single document embed failure, record error
            log.error("Failed to embed document {}: {}", id, e.getMessage());
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
        }
        return result;
    }

    /** Executes core logic for single document embedding; writes result to result Map */
    private void processSingleEmbedding(Long id, Map<String, Object> result) {
        RagDocument doc = findAndValidateDocument(id, result);
        if (doc == null) {
            return;
        }

        List<TextChunk> chunks = prepareChunks(doc, result);
        if (chunks == null) {
            return;
        }

        doc.setProcessingStatus("PROCESSING");
        documentRepository.save(doc);
        embeddingRepository.deleteByDocumentId(id);

        int stored = storeEmbeddings(id, chunks,
                embeddingBatchService.createEmbeddingsBatch(
                        chunks.stream().map(TextChunk::text).toList()));

        if (stored > 0) {
            completeEmbedding(doc, chunks.size());
            result.put("status", "COMPLETED");
            result.put("chunksCreated", stored);
            result.put("embeddingsStored", stored);
        } else {
            doc.setProcessingStatus("FAILED");
            documentRepository.save(doc);
            result.put("status", "FAILED");
            result.put("chunksCreated", 0);
            result.put("embeddingsStored", 0);
        }
    }

    /** Finds document and checks cache; returns null when status already written to result */
    private RagDocument findAndValidateDocument(Long id, Map<String, Object> result) {
        RagDocument doc = documentRepository.findById(id).orElse(null);
        if (doc == null) {
            result.put("status", "NOT_FOUND");
            return null;
        }
        Map<String, Object> cached = checkEmbeddingCache(doc);
        if (cached != null) {
            result.put("status", "CACHED");
            result.put("embeddingsStored", cached.get("embeddingsStored"));
            return null;
        }
        return doc;
    }

    /** Handles chunking; returns null when no embedding needed (reason already written to result) */
    private List<TextChunk> prepareChunks(RagDocument doc, Map<String, Object> result) {
        String content = doc.getContent();
        if (content == null || content.isBlank()) {
            result.put("status", "SKIPPED");
            result.put("reason", "Content is empty");
            return null;
        }
        List<TextChunk> chunks = chunker.split(content);
        if (chunks.isEmpty()) {
            result.put("status", "SKIPPED");
            result.put("reason", "Content too short, no chunking needed");
            return null;
        }
        return chunks;
    }

    /**
     * Checks whether VectorStore is available
     */
    public boolean isVectorStoreAvailable() {
        return vectorStore != null;
    }

    // ==================== Internal Methods ====================

    /**
     * Checks embedding cache — determines whether re-embedding is needed based on content hash
     *
     * <p>Check strategy (three layers):
     * <ol>
     *   <li>Status check: non-COMPLETED status does not hit cache</li>
     *   <li>Content hash check: current content hash differs from embedded hash → content changed, needs re-embedding</li>
     *   <li>Embedding record check: no existing embedding records → needs embedding</li>
     * </ol>
     *
     * @return result Map on cache hit, null on miss
     */
    private Map<String, Object> checkEmbeddingCache(RagDocument doc) {
        if (!"COMPLETED".equals(doc.getProcessingStatus())) {
            return null;
        }

        // Content hash comparison: if content changed, need to re-embed even if old embeddings exist
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
                    "message", "Embedding already exists and content unchanged, skipping (use force=true to re-embed)",
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
            throw new IllegalArgumentException("Document content is empty: documentId=" + doc.getId());
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

    private List<Document> buildVectorStoreDocuments(Long documentId, String documentTitle, List<TextChunk> chunks) {
        List<Document> documents = new java.util.ArrayList<>(chunks.size());
        String title = documentTitle != null ? documentTitle : String.valueOf(documentId);
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            documents.add(Document.builder()
                    .id(documentId + "-" + i)
                    .text(chunk.text())
                    .metadata(Map.of(
                            "documentId", String.valueOf(documentId),
                            "title", title,
                            "chunkIndex", String.valueOf(i),
                            "chunkStartPos", String.valueOf(chunk.startPos()),
                            "chunkEndPos", String.valueOf(chunk.endPos())
                    ))
                    .build());
        }
        return documents;
    }

    // ==================== Extracted Shared Logic ====================

    /** Embedding preparation result: document + cache check + chunking result */
    private record EmbedPrepareResult(RagDocument doc, Map<String, Object> cached, List<TextChunk> chunks) {}

    /**
     * Unified embedding preparation flow: find document → check cache → validate content → chunk
     * @return preparation result; non-null cached means cache hit, return directly
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
                    "message", "Document content too short, no chunking needed",
                    "documentId", documentId,
                    "chunksCreated", 0,
                    "status", "FAILED"
            ), null);
        }
        log.info("Document {} split into {} chunks", documentId, chunks.size());
        return new EmbedPrepareResult(doc, null, chunks);
    }

    /** Marks embedding as complete: COMPLETED + updates content hash */
    private void completeEmbedding(RagDocument doc, int chunkCount) {
        doc.setProcessingStatus("COMPLETED");
        doc.setEmbeddedContentHash(doc.getContentHash());
        documentRepository.save(doc);
    }

    /** Builds success response Map */
    private Map<String, Object> buildSuccessResult(Long docId, int chunks, int stored, String status,
                                                     String... extraEntries) {
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Embedding generation completed");
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
