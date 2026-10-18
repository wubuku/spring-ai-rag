package com.springairag.core.controller;

import com.springairag.api.openai.OpenAiErrorResponse;
import com.springairag.api.enums.FailureClass;
import com.springairag.core.exception.RagException;
import com.springairag.core.exception.ChatTurnInProgressException;
import com.springairag.core.filter.FailureClassHttpSupport;
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
        return errorResponse(error.getStatus(), error.getCode())
                .body(OpenAiErrorResponse.of(
                        error.getMessage(),
                        error.getType(),
                        error.getParam(),
                        error.getCode()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<OpenAiErrorResponse> handleUnreadable(
            HttpMessageNotReadableException error) {
        return errorResponse(HttpStatus.BAD_REQUEST.value(), "invalid_request_body")
                .body(OpenAiErrorResponse.of(
                        "Invalid JSON request body",
                        "invalid_request_error",
                        null,
                        "invalid_request_body"));
    }

    @ExceptionHandler(RagException.class)
    public ResponseEntity<OpenAiErrorResponse> handleRag(RagException error) {
        int status = error.getErrorCodeEnum().getHttpStatus();
        ResponseEntity.BodyBuilder builder = errorResponse(
                status, error.getErrorCodeEnum().getCode());
        if (error instanceof ChatTurnInProgressException inProgress) {
            builder.header("Retry-After",
                    Integer.toString(inProgress.retryAfterSeconds()));
        } else if (error.getRetryAfterSeconds() != null) {
            builder.header("Retry-After", Integer.toString(error.getRetryAfterSeconds()));
        }
        return builder.body(OpenAiErrorResponse.of(
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
        return errorResponse(HttpStatus.FORBIDDEN.value(), "permission_denied")
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
        return errorResponse(HttpStatus.BAD_REQUEST.value(), "invalid_request")
                .body(OpenAiErrorResponse.of(
                        error.getMessage(),
                        "invalid_request_error",
                        null,
                        "invalid_request"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OpenAiErrorResponse> handleUnexpected(
            Exception error) {
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE.value(), "service_unavailable")
                .body(OpenAiErrorResponse.of(
                        "The RAG service is temporarily unavailable",
                        "server_error",
                        null,
                        "service_unavailable"));
    }

    private ResponseEntity.BodyBuilder errorResponse(int status, String errorCode) {
        return ResponseEntity.status(status)
                .header(FailureClassHttpSupport.HEADER,
                        FailureClass.from(status, errorCode).name());
    }
}
