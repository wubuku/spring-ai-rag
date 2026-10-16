package com.springairag.api.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI Chat Completions 非流式响应。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiChatCompletionResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        Usage usage,
        OpenAiRagExtension rag) {

    /** 保留现有六参数构造签名，确保 API 模块 Java 调用方兼容。 */
    public OpenAiChatCompletionResponse(
            String id,
            String object,
            long created,
            String model,
            List<Choice> choices,
            Usage usage) {
        this(id, object, created, model, choices, usage, null);
    }

    public record Choice(
            int index,
            AssistantMessage message,
            @JsonProperty("finish_reason") String finishReason) {
    }

    public record AssistantMessage(String role, String content) {
    }

    public record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            @JsonProperty("total_tokens") Integer totalTokens) {
    }
}
