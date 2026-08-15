package com.springairag.core.config;

/**
 * 为写入和检索提供同一个活动 Embedding Profile。
 */
@FunctionalInterface
public interface EmbeddingProfileProvider {

    EmbeddingProfile getActiveProfile();
}
