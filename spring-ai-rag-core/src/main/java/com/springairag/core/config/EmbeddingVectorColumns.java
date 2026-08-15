package com.springairag.core.config;

/**
 * 固定维度向量列白名单。
 */
public final class EmbeddingVectorColumns {

    private EmbeddingVectorColumns() {
    }

    public static String columnFor(int dimensions) {
        if (dimensions == 1024) {
            return "embedding_1024";
        }
        throw new IllegalArgumentException("Unsupported embedding dimensions: " + dimensions);
    }
}
