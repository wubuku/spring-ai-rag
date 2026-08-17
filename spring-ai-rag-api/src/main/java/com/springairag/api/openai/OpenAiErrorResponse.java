package com.springairag.api.openai;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAI 风格错误信封。
 */
public record OpenAiErrorResponse(Error error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Error(String message, String type, String param, String code) {
    }

    public static OpenAiErrorResponse of(
            String message, String type, String param, String code) {
        return new OpenAiErrorResponse(new Error(message, type, param, code));
    }
}
