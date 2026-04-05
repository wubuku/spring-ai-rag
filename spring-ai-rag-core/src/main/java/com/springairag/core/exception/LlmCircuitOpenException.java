package com.springairag.core.exception;

import com.springairag.api.enums.ErrorCode;

/**
 * LLM circuit breaker open exception — thrown when the LLM circuit breaker is in OPEN
 * state and all LLM calls fail immediately to avoid resource exhaustion.
 * GlobalExceptionHandler returns 503.
 */
public class LlmCircuitOpenException extends RagException {

    public LlmCircuitOpenException() {
        super(ErrorCode.LLM_CIRCUIT_OPEN,
                "LLM service temporarily unavailable due to high failure rate. Please retry later.");
    }

    public LlmCircuitOpenException(String message) {
        super(ErrorCode.LLM_CIRCUIT_OPEN, message);
    }
}
