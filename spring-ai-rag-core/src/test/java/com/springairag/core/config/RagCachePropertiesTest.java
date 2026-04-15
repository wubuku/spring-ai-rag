package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagCacheProperties.
 */
class RagCachePropertiesTest {

    @Test
    void defaults_areCorrect() {
        RagCacheProperties props = new RagCacheProperties();
        assertEquals(2000L, props.getMaximumSize());
        assertEquals(30, props.getExpireAfterWriteMinutes());
        assertEquals(10000L, props.getEmbeddingMaximumSize());
        assertEquals(2, props.getEmbeddingExpireAfterWriteHours());
    }

    @Test
    void setters_updateAllValues() {
        RagCacheProperties props = new RagCacheProperties();

        props.setMaximumSize(5000L);
        props.setExpireAfterWriteMinutes(60);
        props.setEmbeddingMaximumSize(20000L);
        props.setEmbeddingExpireAfterWriteHours(4);

        assertEquals(5000L, props.getMaximumSize());
        assertEquals(60, props.getExpireAfterWriteMinutes());
        assertEquals(20000L, props.getEmbeddingMaximumSize());
        assertEquals(4, props.getEmbeddingExpireAfterWriteHours());
    }

    @Test
    void setters_acceptBoundaryValues() {
        RagCacheProperties props = new RagCacheProperties();

        props.setMaximumSize(0L);
        props.setExpireAfterWriteMinutes(0);
        props.setEmbeddingMaximumSize(0L);
        props.setEmbeddingExpireAfterWriteHours(0);

        assertEquals(0L, props.getMaximumSize());
        assertEquals(0, props.getExpireAfterWriteMinutes());
        assertEquals(0L, props.getEmbeddingMaximumSize());
        assertEquals(0, props.getEmbeddingExpireAfterWriteHours());
    }
}
