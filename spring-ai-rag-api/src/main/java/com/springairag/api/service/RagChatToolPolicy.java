package com.springairag.api.service;

import java.time.Duration;

/**
 * Restrictive policy for one server-owned tool.
 */
public record RagChatToolPolicy(
        Effect effect,
        int maxCallsPerRequest,
        int maxResultCharacters,
        Duration timeout) {

    public enum Effect {
        READ_ONLY
    }

    public RagChatToolPolicy {
        effect = effect != null ? effect : Effect.READ_ONLY;
        timeout = timeout != null ? timeout : Duration.ofSeconds(30);
    }

    public static RagChatToolPolicy defaults() {
        return new RagChatToolPolicy(
                Effect.READ_ONLY, 3, 24_000, Duration.ofSeconds(30));
    }
}
