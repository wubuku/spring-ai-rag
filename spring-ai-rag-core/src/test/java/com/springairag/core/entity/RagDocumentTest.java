package com.springairag.core.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagDocument entity unit tests
 */
class RagDocumentTest {

    @Test
    @DisplayName("Default constructor and getter/setter")
    void defaultsAndSetters() {
        var entity = new RagDocument();

        assertNull(entity.getId());
        assertNull(entity.getCollectionId());
        assertNull(entity.getTitle());
        assertNull(entity.getContent());
        assertNull(entity.getSource());
        assertNull(entity.getDocumentType());
        assertNull(entity.getOriginalFilename());
        assertNull(entity.getContentHash());
        assertNull(entity.getEmbeddedContentHash());
        assertNull(entity.getSize());
        assertEquals("COMPLETED", entity.getProcessingStatus());
        assertNull(entity.getProcessingError());
        assertNull(entity.getMetadata());
        assertTrue(entity.getEnabled());
        assertNull(entity.getCreatedAt());
        assertNull(entity.getUpdatedAt());
    }

    @Test
    @DisplayName("All fields populated via setters")
    void allFieldsPopulated() {
        var entity = new RagDocument();
        LocalDateTime now = LocalDateTime.now();

        entity.setId(1L);
        entity.setCollectionId(10L);
        entity.setTitle("Spring AI Guide");
        entity.setContent("Content about Spring AI framework");
        entity.setSource("file:///docs/spring-ai.md");
        entity.setDocumentType("markdown");
        entity.setOriginalFilename("spring-ai.md");
        entity.setContentHash("abc123def456");
        entity.setEmbeddedContentHash("abc123def456");
        entity.setSize(4096L);
        entity.setProcessingStatus("PROCESSING");
        entity.setProcessingError("OutOfMemoryError");
        entity.setMetadata(Map.of("author", "test-author", "version", 1));
        entity.setEnabled(false);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        assertEquals(1L, entity.getId());
        assertEquals(10L, entity.getCollectionId());
        assertEquals("Spring AI Guide", entity.getTitle());
        assertEquals("Content about Spring AI framework", entity.getContent());
        assertEquals("file:///docs/spring-ai.md", entity.getSource());
        assertEquals("markdown", entity.getDocumentType());
        assertEquals("spring-ai.md", entity.getOriginalFilename());
        assertEquals("abc123def456", entity.getContentHash());
        assertEquals("abc123def456", entity.getEmbeddedContentHash());
        assertEquals(4096L, entity.getSize());
        assertEquals("PROCESSING", entity.getProcessingStatus());
        assertEquals("OutOfMemoryError", entity.getProcessingError());
        assertEquals("test-author", entity.getMetadata().get("author"));
        assertEquals(1, entity.getMetadata().get("version"));
        assertFalse(entity.getEnabled());
        assertEquals(now, entity.getCreatedAt());
        assertEquals(now, entity.getUpdatedAt());
    }

    @Test
    @DisplayName("Processing status default is COMPLETED")
    void processingStatusDefault() {
        var entity = new RagDocument();
        assertEquals("COMPLETED", entity.getProcessingStatus());
        entity.setProcessingStatus("FAILED");
        assertEquals("FAILED", entity.getProcessingStatus());
    }

    @Test
    @DisplayName("Enabled default is true")
    void enabledDefault() {
        var entity = new RagDocument();
        assertTrue(entity.getEnabled());
        entity.setEnabled(false);
        assertFalse(entity.getEnabled());
    }

    @Test
    @DisplayName("Metadata can hold arbitrary JSON data")
    void metadataArbitraryJson() {
        var entity = new RagDocument();
        Map<String, Object> metadata = Map.of(
                "tags", java.util.List.of("AI", "RAG", "Spring"),
                "score", 0.95,
                "verified", true
        );
        entity.setMetadata(metadata);
        assertEquals("AI", ((java.util.List<?>) entity.getMetadata().get("tags")).get(0));
        assertEquals(0.95, entity.getMetadata().get("score"));
        assertEquals(true, entity.getMetadata().get("verified"));
    }
}
