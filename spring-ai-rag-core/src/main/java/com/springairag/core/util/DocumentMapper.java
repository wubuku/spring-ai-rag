package com.springairag.core.util;

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

    private DocumentMapper() {
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
     * Converts a document version entity to a Map.
     */
    public static Map<String, Object> toVersionMap(RagDocumentVersion v) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", v.getId());
        map.put("documentId", v.getDocumentId());
        map.put("versionNumber", v.getVersionNumber());
        map.put("contentHash", v.getContentHash());
        map.put("size", v.getSize());
        map.put("changeType", v.getChangeType());
        map.put("changeDescription", v.getChangeDescription());
        map.put("createdAt", v.getCreatedAt());
        // Content snapshot only returned in single version details, omitted in list to save bandwidth
        if (v.getContentSnapshot() != null) {
            map.put("contentSnapshot", v.getContentSnapshot());
        }
        return map;
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
}
