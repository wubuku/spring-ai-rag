package com.springairag.core.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLM 熔断器打开异常测试
 */
class LlmCircuitOpenExceptionTest {

    @Test
    @DisplayName("默认构造 - HTTP 503 和错误码 LLM_CIRCUIT_OPEN")
    void defaultConstructor() {
        var ex = new LlmCircuitOpenException();

        assertEquals("LLM_CIRCUIT_OPEN", ex.getErrorCode());
        assertEquals(503, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("unavailable"));
    }

    @Test
    @DisplayName("带自定义消息构造")
    void customMessage() {
        var ex = new LlmCircuitOpenException("Custom circuit open message");

        assertEquals("LLM_CIRCUIT_OPEN", ex.getErrorCode());
        assertEquals(503, ex.getHttpStatus());
        assertEquals("Custom circuit open message", ex.getMessage());
    }

    @Test
    @DisplayName("是 RagException 子类")
    void isRagExceptionSubclass() {
        var ex = new LlmCircuitOpenException();
        assertInstanceOf(RagException.class, ex);
    }

    @Test
    @DisplayName("是 RuntimeException 子类")
    void isRuntimeExceptionSubclass() {
        var ex = new LlmCircuitOpenException();
        assertInstanceOf(RuntimeException.class, ex);
    }
}
