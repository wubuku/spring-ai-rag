package com.springairag.core.controller;

import com.springairag.api.openai.OpenAiErrorResponse;
import com.springairag.core.exception.RagException;
import com.springairag.core.openai.OpenAiProtocolException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 仅作用于 OpenAI 兼容 Controller 的错误信封。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = OpenAiCompatibilityController.class)
public class OpenAiCompatibilityExceptionHandler {

    @ExceptionHandler(OpenAiProtocolException.class)
    public ResponseEntity<OpenAiErrorResponse> handleProtocol(
            OpenAiProtocolException error) {
        return ResponseEntity.status(error.getStatus())
                .body(OpenAiErrorResponse.of(
                        error.getMessage(),
                        error.getType(),
                        error.getParam(),
                        error.getCode()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<OpenAiErrorResponse> handleUnreadable(
            HttpMessageNotReadableException error) {
        return ResponseEntity.badRequest()
                .body(OpenAiErrorResponse.of(
                        "Invalid JSON request body",
                        "invalid_request_error",
                        null,
                        "invalid_request_body"));
    }

    @ExceptionHandler(RagException.class)
    public ResponseEntity<OpenAiErrorResponse> handleRag(RagException error) {
        int status = error.getErrorCodeEnum().getHttpStatus();
        return ResponseEntity.status(status)
                .body(OpenAiErrorResponse.of(
                        error.getMessage(),
                        status >= 500
                                ? "server_error"
                                : "invalid_request_error",
                        null,
                        error.getErrorCodeEnum().getCode()));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<OpenAiErrorResponse> handleSecurity(
            SecurityException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(OpenAiErrorResponse.of(
                        "The current API key is not allowed to access "
                                + "the requested resource",
                        "permission_error",
                        null,
                        "permission_denied"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<OpenAiErrorResponse> handleArgument(
            IllegalArgumentException error) {
        return ResponseEntity.badRequest()
                .body(OpenAiErrorResponse.of(
                        error.getMessage(),
                        "invalid_request_error",
                        null,
                        "invalid_request"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OpenAiErrorResponse> handleUnexpected(
            Exception error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(OpenAiErrorResponse.of(
                        "The RAG service is temporarily unavailable",
                        "server_error",
                        null,
                        "service_unavailable"));
    }
}
