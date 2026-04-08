package com.springairag.core.adapter;

/**
 * OpenAI-compatible API adapter.
 *
 * <p>Applicable to: OpenAI, DeepSeek, Zhipu, and other fully OpenAI-compatible APIs.
 * Supports multiple system messages without special handling.
 */
public class OpenAiCompatibleAdapter implements ApiCompatibilityAdapter {

    @Override
    public boolean supportsMultipleSystemMessages() {
        return true;
    }

    @Override
    public boolean requiresSystemMessageFirst() {
        return false;
    }
}
