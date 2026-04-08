package com.springairag.api.service;

import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

/**
 * Custom Advisor Provider Interface
 * Clients implement this interface and register as Spring Beans to add custom Advisors to the RAG Pipeline.
 *
 * <p>The Starter auto-discovers all RagAdvisorProvider instances, sorts by getOrder(), and injects them into the Advisor chain.</p>
 *
 * <p>Recommended order values:
 * <ul>
 *   <li>HIGHEST_PRECEDENCE + 5  : Before query rewriting (e.g., rate limiting, security check)</li>
 *   <li>HIGHEST_PRECEDENCE + 15 : After query rewriting, before retrieval</li>
 *   <li>HIGHEST_PRECEDENCE + 25 : After retrieval, before reranking</li>
 *   <li>HIGHEST_PRECEDENCE + 35 : After reranking, before LLM call</li>
 *   <li>LOWEST_PRECEDENCE - 10  : After LLM call (e.g., logging)</li>
 * </ul>
 */
public interface RagAdvisorProvider {

    /**
     * Get Advisor name (for logging and debugging).
     */
    String getName();

    /**
     * Get Advisor execution order (smaller value = higher priority).
     */
    int getOrder();

    /**
     * Create Advisor instance.
     */
    BaseAdvisor createAdvisor();
}
