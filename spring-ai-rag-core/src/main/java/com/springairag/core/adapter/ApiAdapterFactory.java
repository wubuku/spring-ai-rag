package com.springairag.core.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * API Compatibility Adapter Factory
 *
 * <p>Automatically selects the appropriate adapter based on base-url.
 * Different API providers have varying levels of OpenAI compatibility.
 */
@Component
public class ApiAdapterFactory {

    private static final Logger log = LoggerFactory.getLogger(ApiAdapterFactory.class);

    /**
     * Selects an adapter based on base-url
     */
    public ApiCompatibilityAdapter getAdapter(String baseUrl) {
        if (baseUrl == null) {
            return new OpenAiCompatibleAdapter();
        }

        String lower = baseUrl.toLowerCase();

        if (lower.contains("minimaxi.com") || lower.contains("minimax")) {
            log.debug("Using MiniMax adapter for base URL: {}", baseUrl);
            return new MiniMaxAdapter();
        }

        // Default to OpenAI-compatible adapter
        log.debug("Using OpenAI compatible adapter for base URL: {}", baseUrl);
        return new OpenAiCompatibleAdapter();
    }
}
