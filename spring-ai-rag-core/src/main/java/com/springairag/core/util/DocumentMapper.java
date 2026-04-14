package com.springairag.core.util;

import com.springairag.api.dto.DocumentDetailResponse;
import com.springairag.api.dto.DocumentSummary;
import com.springairag.api.dto.DocumentVersionResponse;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagEmbeddingRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Utility for converting RAG domain entities to Map representations.
 * <p>
 * Used by controllers to build JSON-serializable response maps.
 */
public final class DocumentMapper {

    /** Maximum characters for content preview in list views. */
    private static final int CONTENT_PREVIEW_MAX_LEN = 200;

    private DocumentMapper() {
    }

    /**
     * List-friendly document-to-map conversion.
     * Uses a pre-built collection-name map to avoid N+1 queries.
     * Returns a content preview (truncated) instead of full content to reduce response size.
     */
    public static Map<String, Object> toListMap(RagDocument doc,
                                                Map<Long, String> collectionNameMap,
                                                RagEmbeddingRepository embeddingRepository) {
        Map<String, Object> map = new HashMap<>();
        putCoreFields(map, doc);

        Long collectionId = doc.getCollectionId();
        map.put("collectionId", collectionId);
        if (collectionId != null) {
            String name = collectionNameMap.get(collectionId);
            if (name != null) map.put("collectionName", name);
        }

        long chunkCount = embeddingRepository.countByDocumentId(doc.getId());
        map.put("chunkCount", chunkCount);

        // Content preview: truncated, not full content
        if (doc.getContent() != null) {
            map.put("contentPreview", truncate(doc.getContent(), CONTENT_PREVIEW_MAX_LEN));
        }

        if (doc.getMetadata() != null) {
            map.put("metadata", doc.getMetadata());
        }

        return map;
    }

    /**
     * Batch-friendly document-to-map conversion.
     * Uses a pre-built collection-name map to avoid N+1 queries.
     */
    public static Map<String, Object> toMap(RagDocument doc,
                                            Map<Long, String> collectionNameMap,
                                            RagEmbeddingRepository embeddingRepository) {
        Map<String, Object> map = new HashMap<>();
        putCoreFields(map, doc);

        // Collection association (pre-fetched)
        Long collectionId = doc.getCollectionId();
        map.put("collectionId", collectionId);
        if (collectionId != null) {
            String name = collectionNameMap.get(collectionId);
            if (name != null) map.put("collectionName", name);
        }

        // Chunk count
        long chunkCount = embeddingRepository.countByDocumentId(doc.getId());
        map.put("chunkCount", chunkCount);

        putOptionalFields(map, doc);
        return map;
    }

    /**
     * Single-document variant: fetches collection name on demand.
     */
    public static Map<String, Object> toMap(RagDocument doc,
                                            RagCollectionRepository collectionRepository,
                                            RagEmbeddingRepository embeddingRepository) {
        Map<String, Object> map = new HashMap<>();
        putCoreFields(map, doc);

        // Collection association (fetched on demand)
        Long collectionId = doc.getCollectionId();
        map.put("collectionId", collectionId);
        if (collectionId != null) {
            var collection = collectionRepository.findById(collectionId);
            collection.ifPresent(c -> map.put("collectionName", c.getName()));
        }

        // Chunk count
        long chunkCount = embeddingRepository.countByDocumentId(doc.getId());
        map.put("chunkCount", chunkCount);

        putOptionalFields(map, doc);
        return map;
    }

    /**
     * Converts a document version entity to a typed response DTO.
     */
    public static DocumentVersionResponse toVersionResponse(RagDocumentVersion v) {
        return new DocumentVersionResponse(
                v.getId(),
                v.getDocumentId(),
                v.getVersionNumber(),
                v.getContentHash(),
                v.getSize(),
                v.getChangeType(),
                v.getChangeDescription(),
                v.getCreatedAt(),
                v.getContentSnapshot()
        );
    }

    /**
     * Converts a RagDocument to DocumentSummary (list/detail view, contentPreview not full content).
     */
    public static DocumentSummary toSummary(RagDocument doc,
                                           Map<Long, String> collectionNameMap,
                                           RagEmbeddingRepository embeddingRepository) {
        Long collectionId = doc.getCollectionId();
        String collectionName = collectionId != null ? collectionNameMap.get(collectionId) : null;
        long chunkCount = embeddingRepository.countByDocumentId(doc.getId());

        return new DocumentSummary(
                doc.getId(),
                doc.getTitle(),
                doc.getSource(),
                doc.getDocumentType(),
                doc.getProcessingStatus(),
                doc.getCreatedAt(),
                doc.getSize(),
                doc.getContentHash(),
                doc.getEnabled(),
                doc.getUpdatedAt(),
                collectionId,
                collectionName,
                chunkCount,
                doc.getContent() != null ? truncate(doc.getContent(), CONTENT_PREVIEW_MAX_LEN) : null,
                null, // content is null in list view
                doc.getMetadata()
        );
    }

    /**
     * Converts a RagDocument to DocumentDetailResponse (full detail view).
     */
    public static DocumentDetailResponse toDetailResponse(RagDocument doc,
                                                          Map<Long, String> collectionNameMap,
                                                          RagEmbeddingRepository embeddingRepository) {
        Long collectionId = doc.getCollectionId();
        String collectionName = collectionId != null ? collectionNameMap.get(collectionId) : null;
        long chunkCount = embeddingRepository.countByDocumentId(doc.getId());

        return new DocumentDetailResponse(
                doc.getId(),
                doc.getTitle(),
                doc.getSource(),
                doc.getDocumentType(),
                doc.getProcessingStatus(),
                doc.getCreatedAt(),
                doc.getUpdatedAt(),
                doc.getSize(),
                doc.getContentHash(),
                doc.getEnabled(),
                collectionId,
                collectionName,
                chunkCount,
                doc.getContent(),
                doc.getMetadata()
        );
    }

    private static void putCoreFields(Map<String, Object> map, RagDocument doc) {
        map.put("id", doc.getId());
        map.put("title", doc.getTitle());
        map.put("source", doc.getSource());
        map.put("documentType", doc.getDocumentType());
        map.put("enabled", doc.getEnabled());
        map.put("createdAt", doc.getCreatedAt());
        map.put("updatedAt", doc.getUpdatedAt());
        map.put("processingStatus", doc.getProcessingStatus());
        map.put("size", doc.getSize());
        map.put("contentHash", doc.getContentHash());
    }

    private static void putOptionalFields(Map<String, Object> map, RagDocument doc) {
        if (doc.getContent() != null) {
            map.put("content", doc.getContent());
        }
        if (doc.getMetadata() != null) {
            map.put("metadata", doc.getMetadata());
        }
    }

    /**
     * Truncates a string to at most maxLen characters.
     * If truncation occurs, appends "..." to indicate ellipsis.
     */
    static String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
