package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 固定维度向量列白名单。 */
class EmbeddingVectorColumnsTest {

    @Test
    void mapsSupportedDimensionsToTheFixedColumn() {
        assertEquals("embedding_384", EmbeddingVectorColumns.columnFor(384));
        assertEquals("embedding_768", EmbeddingVectorColumns.columnFor(768));
        assertEquals("embedding_1024", EmbeddingVectorColumns.columnFor(1024));
        assertEquals("embedding_1536", EmbeddingVectorColumns.columnFor(1536));
    }

    @Test
    void rejectsUnsupportedDimensions() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EmbeddingVectorColumns.columnFor(512));
        assertEquals("Unsupported embedding dimensions: 512", error.getMessage());
    }
}
