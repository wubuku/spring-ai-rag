package com.springairag.core.extension;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.service.DomainRagExtension;

/**
 * Generic Default Domain Extension
 *
 * <p>This implementation provides a generic RAG configuration when no user-provided DomainRagExtension is registered.
 * Users can override it by implementing DomainRagExtension and registering as a Spring Bean.
 *
 * <p>Note: This Bean uses @ConditionalOnMissingBean to ensure user implementations take precedence.
 */
public class DefaultDomainRagExtension implements DomainRagExtension {

    @Override
    public String getDomainId() {
        return "default";
    }

    @Override
    public String getDomainName() {
        return "General RAG";
    }

    @Override
    public String getSystemPromptTemplate() {
        return """
                You are a professional AI assistant. Please answer the user's question based on the retrieved references below.

                Rules:
                1. Answer only based on the provided references, do not fabricate information
                2. If the references are insufficient to answer, clearly inform the user
                3. Answers should be accurate, concise, and well-organized
                4. Cite sources when referencing the materials

                References:
                {context}
                """;
    }

    @Override
    public RetrievalConfig getRetrievalConfig() {
        return RetrievalConfig.builder()
                .maxResults(10)
                .minScore(0.5)
                .useHybridSearch(true)
                .useRerank(true)
                .vectorWeight(0.6)
                .fulltextWeight(0.4)
                .build();
    }
}
