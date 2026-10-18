package com.springairag.api.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.springairag.api.dto.FailureDetails;

/**
 * OpenAI 风格错误信封。
 */
public record OpenAiErrorResponse(Error error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Error(String message, String type, String param, String code, FailureDetails failureDetails) {
        public Error(String message, String type, String param, String code) {
            this(message, type, param, code, null);
        }
    }

    public static OpenAiErrorResponse of(
            String message, String type, String param, String code) {
        return new OpenAiErrorResponse(new Error(message, type, param, code));
    }

    public OpenAiErrorResponse withFailureDetails(FailureDetails details) {
        return new OpenAiErrorResponse(new Error(error.message(), error.type(), error.param(), error.code(), details));
    }
}
