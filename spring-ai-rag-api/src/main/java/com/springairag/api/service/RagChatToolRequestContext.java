package com.springairag.api.service;

import com.springairag.api.enums.ChatMode;

import java.time.Instant;

/**
 * Trusted request identity passed to external tool callbacks.
 */
public record RagChatToolRequestContext(
        String principalId,
        String principalType,
        boolean admin,
        String sessionId,
        String domainId,
        ChatMode mode,
        String resolvedModelRef,
        Instant deadline) {
}
