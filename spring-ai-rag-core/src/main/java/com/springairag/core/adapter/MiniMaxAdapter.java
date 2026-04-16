package com.springairag.core.adapter;

/**
 * MiniMax API Adapter
 *
 * <p>MiniMax API does not support role: system messages and will return an error.
 * All system messages are automatically converted to user messages (with [System] prefix).
 * Even when there is only one system message after conversion, it is still converted
 * to user because the system role is not supported.
 *
 * <p>Verified:
 * - role: system is not supported (returns 400 "invalid message role: system")
 * - All system messages are converted to user messages (with [System] prefix)
 */
public class MiniMaxAdapter implements ApiCompatibilityAdapter {

    @Override
    public boolean supportsSystemMessage() {
        return false; // MiniMax does not support system role
    }

    @Override
    public boolean supportsMultipleSystemMessages() {
        return false;
    }

    @Override
    public boolean requiresSystemMessageFirst() {
        return true;
    }
}
