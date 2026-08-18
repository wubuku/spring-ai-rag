package com.springairag.core.service;

/**
 * provider 返回后、替换向量前的 worker-owned 提交门。
 */
@FunctionalInterface
public interface EmbeddingCommitGuard {

    EmbeddingCommitGuard ALLOW_ALL = () -> {
    };

    void verify();

    static EmbeddingCommitGuard allowAll() {
        return ALLOW_ALL;
    }

    static boolean isAllowAll(EmbeddingCommitGuard guard) {
        return guard == ALLOW_ALL;
    }
}
