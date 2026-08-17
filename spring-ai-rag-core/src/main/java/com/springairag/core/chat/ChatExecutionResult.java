package com.springairag.core.chat;

import com.springairag.api.dto.ChatResponse;
import com.springairag.api.enums.ChatMode;

import java.util.List;
import java.util.Map;

/**
 * Mode-independent result of one logical chat turn.
 */
public record ChatExecutionResult(
        String answer,
        String sessionId,
        String traceId,
        String requestedModel,
        String resolvedModel,
        ChatMode mode,
        List<com.springairag.api.dto.ChatSource> sources,
        Map<String, Object> usage,
        String finishReason,
        List<ChatResponse.StepMetricRecord> stepMetrics,
        Map<String, Object> metadata) {

    public ChatExecutionResult {
        sources = sources == null ? List.of() : List.copyOf(sources);
        usage = usage == null ? Map.of() : Map.copyOf(usage);
        stepMetrics = stepMetrics == null ? List.of() : List.copyOf(stepMetrics);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
