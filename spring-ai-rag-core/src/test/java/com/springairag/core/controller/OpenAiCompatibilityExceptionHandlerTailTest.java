package com.springairag.core.controller;

import com.springairag.core.exception.ChatTurnInProgressException;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.api.openai.OpenAiErrorResponse;
import com.springairag.core.openai.OpenAiProtocolException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAI 兼容错误信封长尾（Batch 583，JaCoCo 驱动）：handleUnreadable
 * 400 固定信封、handleRag 状态码透传 + Retry-After 头 + 5xx/4xx
 * type 切换 + param 置空、handleSecurity 403 固定信封、handleArgument
 * 400 透传消息、handleUnexpected 503 固定信封、handleProtocol 保留
 * type/param/code。
 */
class OpenAiCompatibilityExceptionHandlerTailTest {

    private final OpenAiCompatibilityExceptionHandler handler =
            new OpenAiCompatibilityExceptionHandler();

    private OpenAiErrorResponse.Error errorOf(
            ResponseEntity<com.springairag.api.openai.OpenAiErrorResponse> response) {
        return response.getBody().error();
    }

    @Test
    void unreadableBodyReturnsFixed400Envelope() {
        var response = handler.handleUnreadable(
                new HttpMessageNotReadableException("bad json"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        var error = errorOf(response);
        assertEquals("Invalid JSON request body", error.message());
        assertEquals("invalid_request_error", error.type());
        assertEquals("invalid_request_body", error.code());
    }

    @Test
    void ragExceptionMapsStatusAndTypeByHttpStatus() {
        var serverError = handler.handleRag(new RagException(
                ErrorCode.CHAT_HISTORY_PERSIST_FAILED, "persist down"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                serverError.getStatusCode());
        assertEquals("server_error",
                serverError.getBody().error().type());
        assertEquals("CHAT_HISTORY_PERSIST_FAILED",
                serverError.getBody().error().code());

        var clientError = handler.handleRag(new RagException(
                ErrorCode.NOT_FOUND, "missing"));
        assertEquals(HttpStatus.NOT_FOUND, clientError.getStatusCode());
        assertEquals("invalid_request_error",
                clientError.getBody().error().type());
        assertEquals("NOT_FOUND", clientError.getBody().error().code());
    }

    @Test
    void chatTurnInProgressAddsRetryAfterHeader() {
        var response = handler.handleRag(
                new com.springairag.core.exception.ChatTurnInProgressException(
                        17));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("17",
                response.getHeaders().getFirst("Retry-After"));
        assertTrue(response.getBody().error().message()
                .contains("progress"));
    }

    @Test
    void securityExceptionReturnsPermissionDeniedEnvelope() {
        var response = handler.handleSecurity(
                new SecurityException("no access"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        var error = response.getBody().error();
        assertEquals("permission_denied", error.code());
        assertEquals("permission_error", error.type());
        assertNotNull(error.message());
    }

    @Test
    void illegalArgumentReturns400WithOriginalMessage() {
        var response = handler.handleArgument(
                new IllegalArgumentException("maxResults must be >= 1"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("maxResults must be >= 1",
                response.getBody().error().message());
        assertEquals("invalid_request", response.getBody().error().code());
    }

    @Test
    void unexpectedExceptionReturns503ServiceUnavailable() {
        var response = handler.handleUnexpected(
                new IllegalStateException("boom"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                response.getStatusCode());
        assertEquals("service_unavailable",
                response.getBody().error().code());
        assertEquals("The RAG service is temporarily unavailable",
                response.getBody().error().message());
    }

    @Test
    void protocolExceptionPreservesTypeParamAndCode() {
        OpenAiProtocolException error = OpenAiProtocolException.invalid(
                "rag.mode override is not allowed",
                "rag.mode",
                "unsupported_parameter");

        var response = handler.handleProtocol(error);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("invalid_request_error",
                response.getBody().error().type());
        assertEquals("rag.mode", response.getBody().error().param());
    }
}
