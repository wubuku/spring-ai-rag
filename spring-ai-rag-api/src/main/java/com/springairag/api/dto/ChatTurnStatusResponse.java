package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Opaque, principal-scoped durable Chat turn status.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatTurnStatusResponse(
        String turnId,
        String sessionId,
        String status,
        String transport,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        boolean replayAvailable,
        String errorCode,
        ChatResponse response) {
}
