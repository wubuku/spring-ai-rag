package com.springairag.core.chat;

import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes request-local Spring AI messages before durable Memory commit.
 *
 * <p>Spring AI 1.1.8's JDBC repository stores only message text and type. It
 * cannot round-trip assistant tool-call metadata or tool response payloads:
 * assistant tool calls are restored as plain text and tool responses are
 * restored with an empty response list. Durable JDBC Memory therefore keeps
 * only messages whose meaning survives that codec. Complete tool
 * call/result pairs are projected separately into bounded history metadata by
 * {@link #toolTranscript(List, int, int)}.</p>
 */
public final class ChatMemoryMessageProjector {

    public static final String TOOL_TRANSCRIPT_METADATA_KEY =
            "toolTranscript";

    private ChatMemoryMessageProjector() {
    }

    public static List<Message> forPersistence(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Message> result = new ArrayList<>();
        for (Message message : messages) {
            if (syntheticSummary(message)) {
                continue;
            }
            if (message instanceof ToolResponseMessage
                    || message instanceof AssistantMessage assistant
                    && !assistant.getToolCalls().isEmpty()) {
                continue;
            }
            result.add(message);
        }
        return List.copyOf(result);
    }

    /**
     * Returns a bounded, JSON-like projection suitable for history metadata
     * and summary input. It deliberately excludes arbitrary message metadata.
     */
    public static List<Map<String, Object>> toolTranscript(
            List<Message> messages,
            int maxCalls,
            int maxCharacters) {
        if (messages == null || messages.isEmpty()
                || maxCalls <= 0 || maxCharacters <= 0) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        int used = 0;
        for (int index = 0; index + 1 < messages.size(); index++) {
            if (!(messages.get(index) instanceof AssistantMessage assistant)
                    || assistant.getToolCalls().isEmpty()
                    || !(messages.get(index + 1)
                    instanceof ToolResponseMessage response)
                    || !matches(assistant, response)) {
                continue;
            }
            List<ToolResponseMessage.ToolResponse> responses =
                    pairedResponses(assistant, response);
            for (int callIndex = 0;
                    callIndex < assistant.getToolCalls().size();
                    callIndex++) {
                if (result.size() >= maxCalls) {
                    return List.copyOf(result);
                }
                AssistantMessage.ToolCall call =
                        assistant.getToolCalls().get(callIndex);
                int remaining = maxCharacters - used;
                if (remaining <= 0) {
                    return List.copyOf(result);
                }
                String id = bounded(call.id(), Math.min(128, remaining));
                remaining -= id.length();
                String name = bounded(call.name(), Math.min(128, remaining));
                remaining -= name.length();
                String arguments = bounded(call.arguments(), remaining / 2);
                remaining -= arguments.length();
                String body = bounded(
                        responses.get(callIndex).responseData(),
                        remaining);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", id);
                item.put("name", name);
                item.put("arguments", arguments);
                item.put("result", body);
                int itemCharacters = id.length() + name.length()
                        + arguments.length() + body.length();
                result.add(Map.copyOf(item));
                used += Math.max(1, itemCharacters);
            }
            index++;
        }
        return List.copyOf(result);
    }

    private static boolean syntheticSummary(Message message) {
        return message instanceof AbstractMessage abstractMessage
                && Boolean.TRUE.equals(abstractMessage.getMetadata().get(
                ConversationSummaryService.SYNTHETIC_SUMMARY_MESSAGE_METADATA_KEY));
    }

    private static boolean matches(
            AssistantMessage assistant,
            ToolResponseMessage response) {
        List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
        return !pairedResponses(assistant, response).isEmpty();
    }

    private static List<ToolResponseMessage.ToolResponse> pairedResponses(
            AssistantMessage assistant,
            ToolResponseMessage response) {
        List<ToolResponseMessage.ToolResponse> result = new ArrayList<>();
        List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
        List<ToolResponseMessage.ToolResponse> responses =
                response.getResponses();
        if (calls == null || responses == null
                || calls.size() != responses.size()) {
            return List.of();
        }
        boolean[] used = new boolean[responses.size()];
        for (AssistantMessage.ToolCall call : calls) {
            boolean found = false;
            for (int responseIndex = 0; responseIndex < responses.size();
                    responseIndex++) {
                if (used[responseIndex]) {
                    continue;
                }
                ToolResponseMessage.ToolResponse candidate =
                        responses.get(responseIndex);
                if (!sameCall(call, candidate)) {
                    continue;
                }
                used[responseIndex] = true;
                result.add(candidate);
                found = true;
                break;
            }
            if (!found) {
                return List.of();
            }
        }
        return List.copyOf(result);
    }

    private static boolean sameCall(
            AssistantMessage.ToolCall call,
            ToolResponseMessage.ToolResponse response) {
        if (call == null || response == null
                || !safeText(call.name()).equals(safeText(response.name()))) {
            return false;
        }
        String callId = safeText(call.id());
        String responseId = safeText(response.id());
        return callId.isBlank() || responseId.isBlank()
                ? callId.isBlank() && responseId.isBlank()
                : callId.equals(responseId);
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static String bounded(String value, int maxCharacters) {
        String text = value == null ? "" : value;
        int limit = Math.max(0, maxCharacters);
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
