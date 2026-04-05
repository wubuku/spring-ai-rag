package com.springairag.api.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link ErrorCode} enum.
 */
class ErrorCodeTest {

    @Test
    @DisplayName("All error codes have valid HTTP status in 4xx/5xx range")
    void allCodesHaveValidHttpStatus() {
        for (var code : ErrorCode.values()) {
            int status = code.getHttpStatus();
            assertTrue(status >= 400 && status < 600,
                    () -> code + " has invalid HTTP status: " + status);
        }
    }

    @Test
    @DisplayName("All error codes have non-blank title")
    void allCodesHaveNonBlankTitle() {
        for (var code : ErrorCode.values()) {
            assertNotNull(code.getTitle());
            assertFalse(code.getTitle().isBlank());
        }
    }

    @Test
    @DisplayName("All error codes have valid problem type URI")
    void allCodesHaveValidProblemTypeUri() {
        for (var code : ErrorCode.values()) {
            String uri = code.getProblemTypeUri();
            assertTrue(uri.startsWith("https://springairag.dev/problems/"));
            String suffix = uri.substring("https://springairag.dev/problems/".length());
            assertEquals(code.name().toLowerCase().replace('_', '-'), suffix);
        }
    }

    @Test
    @DisplayName("getCode() returns the enum name")
    void getCodeReturnsEnumName() {
        assertEquals("DOCUMENT_NOT_FOUND", ErrorCode.DOCUMENT_NOT_FOUND.getCode());
        assertEquals("RETRIEVAL_FAILED", ErrorCode.RETRIEVAL_FAILED.getCode());
        assertEquals("LLM_CIRCUIT_OPEN", ErrorCode.LLM_CIRCUIT_OPEN.getCode());
    }

    @Test
    @DisplayName("getTitle() returns human-readable title")
    void getTitleReturnsHumanReadableTitle() {
        assertEquals("Document Not Found", ErrorCode.DOCUMENT_NOT_FOUND.getTitle());
        assertEquals("Retrieval Failed", ErrorCode.RETRIEVAL_FAILED.getTitle());
        assertEquals("LLM Circuit Breaker Open", ErrorCode.LLM_CIRCUIT_OPEN.getTitle());
        assertEquals("Bad Request", ErrorCode.BAD_REQUEST.getTitle());
    }

    @Test
    @DisplayName("ErrorCode can be looked up by name via valueOf")
    void errorCodeValueOfLookup() {
        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, ErrorCode.valueOf("DOCUMENT_NOT_FOUND"));
        assertEquals(ErrorCode.BAD_REQUEST, ErrorCode.valueOf("BAD_REQUEST"));
        assertEquals(ErrorCode.LLM_CIRCUIT_OPEN, ErrorCode.valueOf("LLM_CIRCUIT_OPEN"));
    }



    @Test
    @DisplayName("DOCUMENT_NOT_FOUND HTTP status is 404")
    void documentNotFoundIs404() {
        assertEquals(404, ErrorCode.DOCUMENT_NOT_FOUND.getHttpStatus());
    }

    @Test
    @DisplayName("LLM_CIRCUIT_OPEN HTTP status is 503")
    void llmCircuitOpenIs503() {
        assertEquals(503, ErrorCode.LLM_CIRCUIT_OPEN.getHttpStatus());
    }

    @Test
    @DisplayName("INTERNAL_ERROR HTTP status is 500")
    void internalErrorIs500() {
        assertEquals(500, ErrorCode.INTERNAL_ERROR.getHttpStatus());
    }

    @Test
    @DisplayName("RATE_LIMIT_EXCEEDED HTTP status is 429")
    void rateLimitExceededIs429() {
        assertEquals(429, ErrorCode.RATE_LIMIT_EXCEEDED.getHttpStatus());
    }
}
