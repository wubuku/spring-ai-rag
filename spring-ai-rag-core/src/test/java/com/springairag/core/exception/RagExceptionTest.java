package com.springairag.core.exception;

import com.springairag.api.enums.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Business exception class tests — fully migrated to typed {@link ErrorCode} enum.
 */
class RagExceptionTest {

    @Test
    @DisplayName("RagException base class: constructor and getters")
    void baseException() {
        var ex = new RagException(ErrorCode.BAD_REQUEST, "test message");

        assertEquals("BAD_REQUEST", ex.getErrorCode());
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCodeEnum());
        assertEquals("test message", ex.getMessage());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    @DisplayName("RagException with cause")
    void baseExceptionWithCause() {
        var cause = new RuntimeException("original");
        var ex = new RagException(ErrorCode.INTERNAL_ERROR, "test", cause);

        assertSame(cause, ex.getCause());
        assertEquals("INTERNAL_ERROR", ex.getErrorCode());
        assertEquals(ErrorCode.INTERNAL_ERROR, ex.getErrorCodeEnum());
        assertEquals(500, ex.getHttpStatus());
    }

    @Test
    @DisplayName("DocumentNotFoundException via documentId")
    void documentNotFoundById() {
        var ex = new DocumentNotFoundException(42L);

        assertEquals("DOCUMENT_NOT_FOUND", ex.getErrorCode());
        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, ex.getErrorCodeEnum());
        assertEquals(404, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("42"));
    }

    @Test
    @DisplayName("DocumentNotFoundException via message")
    void documentNotFoundByMessage() {
        var ex = new DocumentNotFoundException("custom message");

        assertEquals("DOCUMENT_NOT_FOUND", ex.getErrorCode());
        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, ex.getErrorCodeEnum());
        assertEquals(404, ex.getHttpStatus());
        assertEquals("custom message", ex.getMessage());
    }

    @Test
    @DisplayName("DocumentNotFoundException is RagException subclass")
    void documentNotFoundInheritance() {
        var ex = new DocumentNotFoundException(1L);
        assertInstanceOf(RagException.class, ex);
    }

    @Test
    @DisplayName("EmbeddingException via documentId + detail")
    void embeddingExceptionWithDetail() {
        var ex = new EmbeddingException(100L, "timeout");

        assertEquals("EMBEDDING_FAILED", ex.getErrorCode());
        assertEquals(ErrorCode.EMBEDDING_FAILED, ex.getErrorCodeEnum());
        assertEquals(500, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("100"));
        assertTrue(ex.getMessage().contains("timeout"));
    }

    @Test
    @DisplayName("EmbeddingException via message")
    void embeddingExceptionWithMessage() {
        var ex = new EmbeddingException("embedding failed");

        assertEquals("EMBEDDING_FAILED", ex.getErrorCode());
        assertEquals(ErrorCode.EMBEDDING_FAILED, ex.getErrorCodeEnum());
        assertEquals(500, ex.getHttpStatus());
        assertEquals("embedding failed", ex.getMessage());
    }

    @Test
    @DisplayName("EmbeddingException with cause")
    void embeddingExceptionWithCause() {
        var cause = new RuntimeException("underlying");
        var ex = new EmbeddingException("embedding failed", cause);

        assertEquals("EMBEDDING_FAILED", ex.getErrorCode());
        assertEquals(ErrorCode.EMBEDDING_FAILED, ex.getErrorCodeEnum());
        assertEquals(500, ex.getHttpStatus());
        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("EmbeddingException is RagException subclass")
    void embeddingExceptionInheritance() {
        var ex = new EmbeddingException("test");
        assertInstanceOf(RagException.class, ex);
    }

    @Test
    @DisplayName("RetrievalException via query + detail")
    void retrievalExceptionWithDetail() {
        var ex = new RetrievalException("test query", "vector DB connection failed");

        assertEquals("RETRIEVAL_FAILED", ex.getErrorCode());
        assertEquals(ErrorCode.RETRIEVAL_FAILED, ex.getErrorCodeEnum());
        assertEquals(500, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("test query"));
        assertTrue(ex.getMessage().contains("vector DB connection failed"));
    }

    @Test
    @DisplayName("RetrievalException via message")
    void retrievalExceptionWithMessage() {
        var ex = new RetrievalException("retrieval error");

        assertEquals("RETRIEVAL_FAILED", ex.getErrorCode());
        assertEquals(ErrorCode.RETRIEVAL_FAILED, ex.getErrorCodeEnum());
        assertEquals("retrieval error", ex.getMessage());
    }

    @Test
    @DisplayName("RetrievalException with cause")
    void retrievalExceptionWithCause() {
        var cause = new RuntimeException("DB error");
        var ex = new RetrievalException("retrieval failed", cause);

        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("RetrievalException is RagException subclass")
    void retrievalExceptionInheritance() {
        var ex = new RetrievalException("test");
        assertInstanceOf(RagException.class, ex);
    }

    @Test
    @DisplayName("All exceptions extend RuntimeException")
    void allExtendRuntimeException() {
        assertInstanceOf(RuntimeException.class, new RagException(ErrorCode.BAD_REQUEST, "m"));
        assertInstanceOf(RuntimeException.class, new DocumentNotFoundException(1L));
        assertInstanceOf(RuntimeException.class, new EmbeddingException("m"));
        assertInstanceOf(RuntimeException.class, new RetrievalException("m"));
        assertInstanceOf(RuntimeException.class, new LlmCircuitOpenException());
    }

    @Test
    @DisplayName("LlmCircuitOpenException returns correct error code and status")
    void llmCircuitOpenException() {
        var ex = new LlmCircuitOpenException();

        assertEquals("LLM_CIRCUIT_OPEN", ex.getErrorCode());
        assertEquals(ErrorCode.LLM_CIRCUIT_OPEN, ex.getErrorCodeEnum());
        assertEquals(503, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("retry later"));
    }
}
