package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 固定维度向量列白名单：仅放行 1024 维。 */
class EmbeddingVectorColumnsTest {

    @Test
    void mapsSupportedDimensionsToTheFixedColumn() {
        assertEquals("embedding_1024", EmbeddingVectorColumns.columnFor(1024));
    }

    @Test
    void rejectsUnsupportedDimensions() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EmbeddingVectorColumns.columnFor(768));
        assertEquals("Unsupported embedding dimensions: 768", error.getMessage());
    }
}
