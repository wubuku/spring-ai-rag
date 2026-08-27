package com.springairag.core.usage;

import com.springairag.api.enums.ChatMode;

import java.util.UUID;

/**
 * 一次逻辑 Chat execution 的低基数归因快照。
 */
public record ChatExecutionAttribution(
        UUID logicalExecutionId,
        int callOrdinal,
        String principalId,
        String sessionId,
        String requestTraceId,
        ChatMode chatMode) {

    public ChatExecutionAttribution {
        logicalExecutionId = logicalExecutionId != null
                ? logicalExecutionId : UUID.randomUUID();
        if (callOrdinal < 1) {
            throw new IllegalArgumentException("callOrdinal must be positive");
        }
        principalId = required(principalId, 128, "principalId");
        sessionId = required(sessionId, 255, "sessionId");
        requestTraceId = optional(requestTraceId, 128, "requestTraceId");
        chatMode = chatMode != null ? chatMode : ChatMode.PLAIN;
    }

    private static String required(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(ch -> ch < 0x20 || ch > 0x7e)) {
            throw new IllegalArgumentException(
                    name + " must contain printable ASCII characters within 1-"
                            + maximum);
        }
        return value;
    }

    private static String optional(String value, int maximum, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return required(value, maximum, name);
    }
}
