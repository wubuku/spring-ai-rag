package com.springairag.core.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagEmbedding Entity Unit Test
 */
class RagEmbeddingTest {

    @Test
    @DisplayName("Default constructor and getters/setters")
    void defaultsAndSetters() {
        var entity = new RagEmbedding();

        assertNull(entity.getId());
        assertNull(entity.getDocumentId());
        assertNull(entity.getChunkText());
        assertEquals(0, entity.getChunkIndex());
        assertNull(entity.getChunkStartPos());
        assertNull(entity.getChunkEndPos());
        assertNull(entity.getEmbedding());
        assertNull(entity.getMetadata());
        assertNull(entity.getCreatedAt());

        entity.setId(1L);
        entity.setDocumentId(100L);
        entity.setChunkText("测试文本");
        entity.setChunkIndex(3);
        entity.setChunkStartPos(10);
        entity.setChunkEndPos(20);
        float[] vec = new float[1024];
        vec[0] = 0.1f;
        entity.setEmbedding(vec);
        entity.setMetadata(Map.of("key", "value"));
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);

        assertEquals(1L, entity.getId());
        assertEquals(100L, entity.getDocumentId());
        assertEquals("测试文本", entity.getChunkText());
        assertEquals(3, entity.getChunkIndex());
        assertEquals(10, entity.getChunkStartPos());
        assertEquals(20, entity.getChunkEndPos());
        assertSame(vec, entity.getEmbedding());
        assertEquals("value", entity.getMetadata().get("key"));
        assertEquals(now, entity.getCreatedAt());
    }
}
