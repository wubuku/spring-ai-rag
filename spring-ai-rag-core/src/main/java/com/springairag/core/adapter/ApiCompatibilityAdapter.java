package com.springairag.core.adapter;

import java.util.List;

/**
 * API Compatibility Adapter Interface
 *
 * <p>Different LLM APIs have varying levels of OpenAI compatibility. This interface
 * defines behaviors that need adaptation, allowing each API provider to implement
 * its own adaptation strategy.
 */
public interface ApiCompatibilityAdapter {

    /**
     * Checks whether the API supports the system message role
     *
     * <p>MiniMax and some domestic models do not support role: system and will reject
     * it directly. OpenAI and DeepSeek support it.
     *
     * @return true = supports system role, false = not supported (will be converted to user role)
     */
    default boolean supportsSystemMessage() {
        return true;
    }

    /**
     * Checks whether the API supports multiple system messages
     *
     * <p>MiniMax and some domestic models only support a single system message
     * (and it must be first). OpenAI and Anthropic support multiple system messages.
     */
    boolean supportsMultipleSystemMessages();

    /**
     * Checks whether system messages must appear first in the list
     */
    boolean requiresSystemMessageFirst();

    /**
     * Normalizes the message list to comply with the target API's requirements
     *
     * <p>Processing strategy:
     * <ol>
     *   <li>If system role is not supported, convert all system messages to user messages (with [System] prefix)</li>
     *   <li>If multiple system messages are not supported, merge them into a single one</li>
     *   <li>If system must be first but isn't, reorder the messages</li>
     * </ol>
     */
    default List<ChatMessage> normalizeMessages(List<ChatMessage> messages) {
        List<ChatMessage> normalized = messages;

        // Step 1: Convert system messages to user if not supported
        if (!supportsSystemMessage()) {
            normalized = normalized.stream()
                    .map(msg -> "system".equals(msg.role())
                            ? new ChatMessage("user", "[System] " + msg.content())
                            : msg)
                    .toList();
        }

        // Step 2: Merge multiple system messages into one if not supported
        if (!supportsMultipleSystemMessages()) {
            normalized = mergeSystemMessages(normalized);
        }

        // Step 3: Reorder to put system message first if required
        if (requiresSystemMessageFirst() && !normalized.isEmpty()) {
            normalized = reorderSystemMessageFirst(normalized);
        }

        return normalized;
    }

    /** Merges multiple system messages into a single system message */
    private List<ChatMessage> mergeSystemMessages(List<ChatMessage> messages) {
        StringBuilder combinedSystem = new StringBuilder();
        List<ChatMessage> nonSystemMessages = new java.util.ArrayList<>();

        for (ChatMessage msg : messages) {
            if ("system".equals(msg.role())) {
                if (!combinedSystem.isEmpty()) {
                    combinedSystem.append("\n\n");
                }
                combinedSystem.append(msg.content());
            } else {
                nonSystemMessages.add(msg);
            }
        }

        List<ChatMessage> result = new java.util.ArrayList<>();
        if (!combinedSystem.isEmpty()) {
            result.add(new ChatMessage("system", combinedSystem.toString()));
        }
        result.addAll(nonSystemMessages);
        return result;
    }

    /** Moves system messages to the front of the list */
    private List<ChatMessage> reorderSystemMessageFirst(List<ChatMessage> messages) {
        List<ChatMessage> systemMsgs = new java.util.ArrayList<>();
        List<ChatMessage> otherMsgs = new java.util.ArrayList<>();
        for (ChatMessage msg : messages) {
            if ("system".equals(msg.role())) {
                systemMsgs.add(msg);
            } else {
                otherMsgs.add(msg);
            }
        }
        List<ChatMessage> result = new java.util.ArrayList<>(systemMsgs);
        result.addAll(otherMsgs);
        return result;
    }

    /**
     * Chat message record
     */
    record ChatMessage(String role, String content) {}
}
