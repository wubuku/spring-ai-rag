package com.springairag.core.chat;

import com.springairag.api.dto.ChatResponse;

import java.util.List;
import java.util.Map;

/**
 * Structured internal stream events. Transport adapters map these to SSE.
 */
public sealed interface ChatEvent
        permits ChatEvent.ContentDelta,
        ChatEvent.ToolStarted,
        ChatEvent.ToolFinished,
        ChatEvent.SourcesAvailable,
        ChatEvent.Completed,
        ChatEvent.Failed {

    record ContentDelta(String content) implements ChatEvent {
    }

    record ToolStarted(String toolCallId, String tool, String query) implements ChatEvent {
    }

    record ToolFinished(
            String toolCallId,
            String tool,
            int resultCount,
            long elapsedMs) implements ChatEvent {
    }

    record SourcesAvailable(String sessionId, List<com.springairag.api.dto.ChatSource> sources)
            implements ChatEvent {
        public SourcesAvailable {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    record Completed(
            String traceId,
            String sessionId,
            String requestedModel,
            String resolvedModel,
            com.springairag.api.enums.ChatMode mode,
            Map<String, Object> usage,
            String finishReason,
            List<ChatResponse.StepMetricRecord> stepMetrics,
            Map<String, Object> metadata) implements ChatEvent {
        public Completed {
            usage = usage == null ? Map.of() : Map.copyOf(usage);
            stepMetrics = stepMetrics == null ? List.of() : List.copyOf(stepMetrics);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        public Completed(
                String traceId,
                String sessionId,
                String requestedModel,
                String resolvedModel,
                com.springairag.api.enums.ChatMode mode,
                Map<String, Object> usage,
                String finishReason,
                List<ChatResponse.StepMetricRecord> stepMetrics) {
            this(traceId, sessionId, requestedModel, resolvedModel, mode, usage,
                    finishReason, stepMetrics, Map.of());
        }
    }

    record Failed(String traceId, String sessionId, String code, String message)
            implements ChatEvent {
    }
}
