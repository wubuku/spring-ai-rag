package com.springairag.core.entity;

/**
 * Role of an API key.
 *
 * @see RagApiKey
 */
public enum ApiKeyRole {
    /**
     * Admin key: can create/delete any API key, manage collections.
     * There is no hierarchical elevation — admin keys are all equal.
     */
    ADMIN,

    /**
     * Normal key: can only access RAG functionality (chat, search, documents).
     * 在 environment root 模式下不能创建、轮换或吊销任何 API Key。
     */
    NORMAL
}
