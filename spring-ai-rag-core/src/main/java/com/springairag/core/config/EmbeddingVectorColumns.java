package com.springairag.core.config;

/**
 * 固定维度向量列白名单。
 */
public final class EmbeddingVectorColumns {

    private EmbeddingVectorColumns() {
    }

    public static String columnFor(int dimensions) {
        if (dimensions == 384) {
            return "embedding_384";
        }
        if (dimensions == 768) {
            return "embedding_768";
        }
        if (dimensions == 1024) {
            return "embedding_1024";
        }
        if (dimensions == 1536) {
            return "embedding_1536";
        }
        throw new IllegalArgumentException("Unsupported embedding dimensions: " + dimensions);
    }
}
