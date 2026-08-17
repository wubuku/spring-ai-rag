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
        List<Choice> choices) {

    public record Choice(
            int index,
            Delta delta,
            @JsonProperty("finish_reason") String finishReason) {
    }

    public record Delta(String role, String content) {
    }
}
