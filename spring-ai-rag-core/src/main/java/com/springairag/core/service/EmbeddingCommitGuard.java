package com.springairag.core.service;

/**
 * provider 返回后、替换向量前的 worker-owned 提交门。
 */
@FunctionalInterface
public interface EmbeddingCommitGuard {

    void verify();

    static EmbeddingCommitGuard allowAll() {
        return () -> {
        };
    }
}
