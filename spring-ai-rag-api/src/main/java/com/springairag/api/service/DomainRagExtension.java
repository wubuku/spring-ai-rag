package com.springairag.api.service;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.enums.ChatMode;

/**
 * Domain RAG Extension Point
 * Domain-specific implementations (e.g., dermatology detection) can implement this interface
 * to provide domain-specific Prompt templates and configuration.
 */
public interface DomainRagExtension {

    /**
     * Get unique domain identifier.
     */
    String getDomainId();

    /**
     * Get domain display name.
     */
    String getDomainName();

    /**
     * Get domain-specific system prompt template.
     */
    String getSystemPromptTemplate();

    /**
     * 获取指定 Chat 模式下的领域提示词。
     *
     * <p>旧扩展默认复用 {@link #getSystemPromptTemplate()}。如果旧模板包含
     * {@code {context}}，它只兼容 KNOWLEDGE；扩展需要覆盖本方法后才能安全用于
     * AGENT 或 PLAIN。</p>
     */
    default String getSystemPromptTemplate(ChatMode mode) {
        return getSystemPromptTemplate();
    }

    /**
     * Get domain-specific retrieval configuration.
     */
    default RetrievalConfig getRetrievalConfig() {
        return RetrievalConfig.builder().build();
    }

    /**
     * Post-process generated answer (legacy API).
     *
     * @deprecated The mode-aware production Chat path does not call this method
     * because token streaming cannot apply a whole-answer transformation safely.
     */
    @Deprecated
    default String postProcessAnswer(String answer) {
        return answer;
    }

    /**
     * Check if the query belongs to this domain (legacy API).
     *
     * @deprecated Chat domains are selected explicitly through {@code domainId};
     * the production path does not perform implicit intent classification.
     */
    @Deprecated
    default boolean isApplicable(String query) {
        return true;
    }
}
