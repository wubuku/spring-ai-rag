package com.springairag.core.util;

import com.springairag.core.entity.RagCollection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CollectionMapper}.
 */
@DisplayName("CollectionMapper Tests")
class CollectionMapperTest {

    @Test
    @DisplayName("toMap returns all fields with document count")
    void toMap_returnsAllFields() {
        RagCollection collection = createCollection(1L, "Test Collection", "A test collection",
                "BAAI/bge-m3", 1024, true);

        Map<String, Object> result = CollectionMapper.toMap(collection, 42L);

        assertEquals(1L, result.get("id"));
        assertEquals("Test Collection", result.get("name"));
        assertEquals("A test collection", result.get("description"));
        assertEquals("BAAI/bge-m3", result.get("embeddingModel"));
        assertEquals(1024, result.get("dimensions"));
        assertEquals(true, result.get("enabled"));
        assertEquals(Map.of("key", "value"), result.get("metadata"));
        assertEquals(42L, result.get("documentCount"));
        assertNotNull(result.get("createdAt"));
        assertNotNull(result.get("updatedAt"));
        assertEquals(false, result.get("deleted"));
        assertNull(result.get("deletedAt"));
    }

    @Test
    @DisplayName("toMap handles zero document count")
    void toMap_zeroDocumentCount() {
        RagCollection collection = createCollection(2L, "Empty Collection", "", null, 0, true);

        Map<String, Object> result = CollectionMapper.toMap(collection, 0L);

        assertEquals(0L, result.get("documentCount"));
        assertEquals("", result.get("description"));
    }

    @Test
    @DisplayName("toMap handles disabled collection")
    void toMap_disabledCollection() {
        RagCollection collection = createCollection(3L, "Disabled", "Disabled collection", "BAAI/bge-m3", 1024, false);

        Map<String, Object> result = CollectionMapper.toMap(collection, 10L);

        assertEquals(false, result.get("enabled"));
        assertEquals(10L, result.get("documentCount"));
    }

    @Test
    @DisplayName("toMap handles soft-deleted collection with deletedAt timestamp")
    void toMap_softDeletedCollection() {
        RagCollection collection = createCollection(4L, "Deleted", "Was deleted", "BAAI/bge-m3", 1024, false);
        LocalDateTime deletedAt = LocalDateTime.of(2026, 4, 1, 12, 0, 0);
        collection.setDeleted(true);
        collection.setDeletedAt(deletedAt);

        Map<String, Object> result = CollectionMapper.toMap(collection, 5L);

        assertEquals(true, result.get("deleted"));
        assertEquals(deletedAt, result.get("deletedAt"));
        assertEquals(5L, result.get("documentCount"));
    }

    @Test
    @DisplayName("toMap throws IllegalArgumentException when collection is null")
    void toMap_nullCollection() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CollectionMapper.toMap(null, 0L));
        assertEquals("Collection must not be null", ex.getMessage());
    }

    @Test
    @DisplayName("toMap handles null embedding model")
    void toMap_nullEmbeddingModel() {
        RagCollection collection = createCollection(5L, "No Model", "No embedding model", null, 0, true);

        Map<String, Object> result = CollectionMapper.toMap(collection, 0L);

        assertNull(result.get("embeddingModel"));
        assertEquals(0, result.get("dimensions"));
    }

    @Test
    @DisplayName("toMap returns mutable map")
    void toMap_returnsMutableMap() {
        RagCollection collection = createCollection(6L, "Mutable", "", "BAAI/bge-m3", 1024, true);

        Map<String, Object> result = CollectionMapper.toMap(collection, 1L);

        result.put("customField", "customValue");
        assertEquals("customValue", result.get("customField"));
    }

    @Test
    @DisplayName("toMap preserves metadata JSON content")
    void toMap_preservesMetadataJson() {
        RagCollection collection = createCollection(7L, "With Metadata", "Has JSON metadata",
                "BAAI/bge-m3", 1024, true);

        Map<String, Object> result = CollectionMapper.toMap(collection, 0L);

        assertEquals(Map.of("key", "value"), result.get("metadata"));
    }

    private RagCollection createCollection(Long id, String name, String description,
                                          String embeddingModel, Integer dimensions, boolean enabled) {
        RagCollection collection = new RagCollection();
        collection.setId(id);
        collection.setName(name);
        collection.setDescription(description);
        collection.setEmbeddingModel(embeddingModel);
        collection.setDimensions(dimensions);
        collection.setEnabled(enabled);
        collection.setMetadata(Map.of("key", "value"));
        collection.setCreatedAt(LocalDateTime.now());
        collection.setUpdatedAt(LocalDateTime.now());
        collection.setDeleted(false);
        collection.setDeletedAt(null);
        return collection;
    }
}
