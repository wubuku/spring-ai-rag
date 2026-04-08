package com.springairag.api.service;

import com.springairag.api.dto.RetrievalConfig;

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
     * Get domain-specific retrieval configuration.
     */
    default RetrievalConfig getRetrievalConfig() {
        return RetrievalConfig.builder().build();
    }

    /**
     * Post-process generated answer (optional).
     */
    default String postProcessAnswer(String answer) {
        return answer;
    }

    /**
     * Check if the query belongs to this domain (accepts all by default).
     */
    default boolean isApplicable(String query) {
        return true;
    }
}
