package com.springairag.core.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagCollection entity unit tests
 */
class RagCollectionTest {

    @Test
    @DisplayName("Default constructor and field defaults")
    void defaultsAndSetters() {
        var entity = new RagCollection();

        assertNull(entity.getId());
        assertNull(entity.getName());
        assertNull(entity.getDescription());
        assertNull(entity.getEmbeddingModel());
        assertEquals(1024, entity.getDimensions());
        assertTrue(entity.getEnabled());
        assertNull(entity.getMetadata());
        assertNull(entity.getCreatedAt());
        assertNull(entity.getUpdatedAt());
        assertFalse(entity.getDeleted());
        assertNull(entity.getDeletedAt());
    }

    @Test
    @DisplayName("All fields populated via setters")
    void allFieldsPopulated() {
        var entity = new RagCollection();
        LocalDateTime now = LocalDateTime.now();

        entity.setId(5L);
        entity.setName("Medical Knowledge Base");
        entity.setDescription("Clinical guidelines and protocols");
        entity.setEmbeddingModel("BAAI/bge-m3");
        entity.setDimensions(1024);
        entity.setEnabled(false);
        entity.setMetadata(Map.of("department", "cardiology", "version", 2));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setDeleted(true);
        entity.setDeletedAt(now);

        assertEquals(5L, entity.getId());
        assertEquals("Medical Knowledge Base", entity.getName());
        assertEquals("Clinical guidelines and protocols", entity.getDescription());
        assertEquals("BAAI/bge-m3", entity.getEmbeddingModel());
        assertEquals(1024, entity.getDimensions());
        assertFalse(entity.getEnabled());
        assertEquals("cardiology", entity.getMetadata().get("department"));
        assertEquals(2, entity.getMetadata().get("version"));
        assertEquals(now, entity.getCreatedAt());
        assertEquals(now, entity.getUpdatedAt());
        assertTrue(entity.getDeleted());
        assertEquals(now, entity.getDeletedAt());
    }

    @Test
    @DisplayName("Dimensions default is 1024")
    void dimensionsDefault() {
        var entity = new RagCollection();
        assertEquals(1024, entity.getDimensions());
        entity.setDimensions(768);
        assertEquals(768, entity.getDimensions());
    }

    @Test
    @DisplayName("Enabled default is true")
    void enabledDefault() {
        var entity = new RagCollection();
        assertTrue(entity.getEnabled());
        entity.setEnabled(false);
        assertFalse(entity.getEnabled());
    }

    @Test
    @DisplayName("Deleted default is false (soft delete)")
    void deletedDefault() {
        var entity = new RagCollection();
        assertFalse(entity.getDeleted());
        assertNull(entity.getDeletedAt());
        LocalDateTime now = LocalDateTime.now();
        entity.setDeleted(true);
        entity.setDeletedAt(now);
        assertTrue(entity.getDeleted());
        assertEquals(now, entity.getDeletedAt());
    }

    @Test
    @DisplayName("Metadata can hold arbitrary JSON data")
    void metadataArbitraryJson() {
        var entity = new RagCollection();
        Map<String, Object> metadata = Map.of(
                "categories", java.util.List.of("legal", "compliance"),
                "sensitivity", "high",
                "retention_days", 365
        );
        entity.setMetadata(metadata);
        assertEquals("legal", ((java.util.List<?>) entity.getMetadata().get("categories")).get(0));
        assertEquals("high", entity.getMetadata().get("sensitivity"));
        assertEquals(365, entity.getMetadata().get("retention_days"));
    }
}
