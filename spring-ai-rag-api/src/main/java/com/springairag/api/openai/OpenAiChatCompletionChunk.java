package com.springairag.api.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI Chat Completions SSE chunk。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiChatCompletionChunk(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        OpenAiRagExtension rag) {

    /** 保留现有五参数构造签名，确保 API 模块 Java 调用方兼容。 */
    public OpenAiChatCompletionChunk(
            String id,
            String object,
            long created,
            String model,
            List<Choice> choices) {
        this(id, object, created, model, choices, null);
    }

    public record Choice(
            int index,
            Delta delta,
            @JsonProperty("finish_reason") String finishReason) {
    }

    public record Delta(String role, String content) {
    }
}
