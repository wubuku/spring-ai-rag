package com.springairag.core.chat;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable state of one principal-scoped Chat turn operation.
 */
public record ChatTurnOperation(
        long id,
        String ownerPrincipalId,
        String idempotencyKeySha256,
        String requestFingerprintSha256,
        int fingerprintVersion,
        String sessionId,
        UUID turnId,
        Transport transport,
        Status status,
        UUID operationToken,
        Instant leaseExpiresAt,
        int attemptCount,
        long rowVersion,
        int responseVersion,
        String executionSnapshot,
        String responsePayload,
        String errorCode,
        String errorPayload,
        String authorizationScopeSnapshot,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public enum Status {
        IN_PROGRESS,
        SUCCEEDED,
        FAILED
    }

    public enum Transport {
        NATIVE_JSON,
        NATIVE_SSE,
        OPENAI_JSON,
        OPENAI_SSE
    }

    public boolean terminal() {
        return status != Status.IN_PROGRESS;
    }
}
