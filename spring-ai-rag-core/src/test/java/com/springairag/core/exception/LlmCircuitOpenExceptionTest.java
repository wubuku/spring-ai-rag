package com.springairag.core.exception;

import com.springairag.api.enums.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLM circuit breaker open exception tests — migrated to typed {@link ErrorCode}.
 */
class LlmCircuitOpenExceptionTest {

    @Test
    @DisplayName("Default constructor: HTTP 503 and error code LLM_CIRCUIT_OPEN")
    void defaultConstructor() {
        var ex = new LlmCircuitOpenException();

        assertEquals("LLM_CIRCUIT_OPEN", ex.getErrorCode());
        assertEquals(ErrorCode.LLM_CIRCUIT_OPEN, ex.getErrorCodeEnum());
        assertEquals(503, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("unavailable"));
    }

    @Test
    @DisplayName("Custom message constructor")
    void customMessage() {
        var ex = new LlmCircuitOpenException("Custom circuit open message");

        assertEquals("LLM_CIRCUIT_OPEN", ex.getErrorCode());
        assertEquals(ErrorCode.LLM_CIRCUIT_OPEN, ex.getErrorCodeEnum());
        assertEquals(503, ex.getHttpStatus());
        assertEquals("Custom circuit open message", ex.getMessage());
    }

    @Test
    @DisplayName("Is RagException subclass")
    void isRagExceptionSubclass() {
        var ex = new LlmCircuitOpenException();
        assertInstanceOf(RagException.class, ex);
    }

    @Test
    @DisplayName("Is RuntimeException subclass")
    void isRuntimeExceptionSubclass() {
        var ex = new LlmCircuitOpenException();
        assertInstanceOf(RuntimeException.class, ex);
    }
}
