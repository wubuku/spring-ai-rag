package com.springairag.core.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

/**
 * Shared utility methods for Advisors
 */
public final class AdvisorUtils {

    private AdvisorUtils() {}

    /**
     * Extracts the last non-empty UserMessage text from a ChatClientRequest
     *
     * @param request the chat request
     * @return user message text, or null if no valid message
     */
    public static String extractUserMessage(ChatClientRequest request) {
        if (request == null || request.prompt() == null) {
            return null;
        }
        List<Message> messages = request.prompt().getInstructions();
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof UserMessage um) {
                String text = um.getText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    /**
     * Returns the focused retrieval query when QueryRewriteAdvisor produced one,
     * otherwise falls back to the original user message.
     */
    public static String extractRetrievalQuery(ChatClientRequest request) {
        if (request != null && request.context() != null) {
            Object raw = request.context().get(
                    QueryRewriteAdvisor.CTX_RETRIEVAL_QUERY);
            if (raw instanceof String query && !query.isBlank()) {
                return query;
            }
        }
        return extractUserMessage(request);
    }
}
