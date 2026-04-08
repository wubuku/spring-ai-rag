package com.springairag.api.service;

import java.util.Map;

/**
 * Prompt Customizer Interface
 *
 * <p>Clients implement this interface to customize the system prompt and user messages.
 * Multiple PromptCustomizer implementations are chained by priority order.
 *
 * <p>Usage: implement the interface + annotate with @Component to register as a Spring Bean;
 * the Starter auto-discovers it.
 */
public interface PromptCustomizer {

    /**
     * Customize the system prompt
     *
     * @param originalSystemPrompt original system prompt
     * @param context              RAG context (retrieved document fragments)
     * @param metadata             metadata (sessionId, domainId, etc.)
     * @return customized system prompt
     */
    default String customizeSystemPrompt(String originalSystemPrompt,
                                          String context,
                                          Map<String, Object> metadata) {
        return originalSystemPrompt;
    }

    /**
     * Customize the user prompt
     *
     * @param originalUserMessage original user message
     * @param metadata            metadata
     * @return customized user message
     */
    default String customizeUserMessage(String originalUserMessage,
                                         Map<String, Object> metadata) {
        return originalUserMessage;
    }

    /**
     * Execution order (smaller value = higher priority)
     */
    default int getOrder() {
        return 0;
    }
}
