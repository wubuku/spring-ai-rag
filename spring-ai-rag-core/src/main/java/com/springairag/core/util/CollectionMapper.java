package com.springairag.core.util;

import com.springairag.core.entity.RagCollection;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility for converting RAG collection entities to Map representations.
 * <p>
 * Used by controllers to build JSON-serializable response maps.
 */
public final class CollectionMapper {

    private CollectionMapper() {
    }

    /**
     * Converts a collection entity to a response map with document count.
     *
     * @param c             the collection entity
     * @param documentCount number of documents in the collection
     * @return a map suitable for JSON serialization
     */
    public static Map<String, Object> toMap(RagCollection c, long documentCount) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", c.getId());
        map.put("name", c.getName());
        map.put("description", c.getDescription());
        map.put("embeddingModel", c.getEmbeddingModel());
        map.put("dimensions", c.getDimensions());
        map.put("enabled", c.getEnabled());
        map.put("metadata", c.getMetadata());
        map.put("createdAt", c.getCreatedAt());
        map.put("updatedAt", c.getUpdatedAt());
        map.put("deleted", c.getDeleted());
        map.put("deletedAt", c.getDeletedAt());
        map.put("documentCount", documentCount);
        return map;
    }
}
