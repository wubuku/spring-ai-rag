package com.springairag.core.controller;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.core.exception.RagException;
import com.springairag.core.logging.SensitiveDataMaskingConverter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.List;

/**
 * Global exception handler — RFC 7807 Problem Detail.
 *
 * <p>All exceptions return a unified {@link ErrorResponse} format, following RFC 7807 standard fields:
 * type / title / status / detail / instance.
 *
 * <p>Response Content-Type is set to {@code application/problem+json} (RFC 7807 §3).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** RFC 7807 standard Content-Type */
    private static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");

    // ==================== 400 Bad Request ====================

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e,
                                                            HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER",
                "Missing required parameter: " + e.getParameterName(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e,
                                                                      HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type not supported: " + e.getMessage(), request);
    }

    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(org.springframework.web.multipart.support.MissingServletRequestPartException e,
                                                           HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "MISSING_PART",
                "Missing required file part: " + e.getRequestPartName(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException e,
                                                                 HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY",
                "Invalid request body, please check JSON format", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e,
                                                          HttpServletRequest request) {
        List<String> violations = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        String detail = String.join("; ", violations);

        ErrorResponse body = ErrorResponse.builder()
                .error("VALIDATION_FAILED")
                .status(400)
                .message(detail)
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(PROBLEM_JSON)
                .body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e,
                                                                   HttpServletRequest request) {
        List<String> violations = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .toList();
        String detail = String.join("; ", violations);

        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                            HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "TYPE_MISMATCH",
                "Parameter '" + e.getName() + "' has wrong type", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e,
                                                          HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                e.getMessage() != null ? e.getMessage() : "Invalid argument", request);
    }

    // ==================== 404 Not Found ====================

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoHandlerFoundException e,
                                                        HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Endpoint not found: " + e.getRequestURL(), request);
    }

    // ==================== 405 Method Not Allowed ====================

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                                  HttpServletRequest request) {
        return buildResponse(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "HTTP method not supported: " + e.getMethod(), request);
    }

    // ==================== 500 Internal Server Error ====================

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException e,
                                                          HttpServletRequest request) {
        log.error("Database error: {}", e.getMessage(), e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "DATABASE_ERROR",
                "Database operation failed", request);
    }

    @ExceptionHandler(RagException.class)
    public ResponseEntity<ErrorResponse> handleRagException(RagException e,
                                                            HttpServletRequest request) {
        var code = e.getErrorCodeEnum();
        log.warn("RAG business error: [{}] {}", code.getCode(), e.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .error(code.getCode())
                .status(code.getHttpStatus())
                .type(code.getProblemTypeUri())
                .detail(e.getMessage())
                .instance(request.getRequestURI())
                .build();
        // Set human-readable title without triggering the builder's title() side effect
        body.setTitle(code.getTitle());
        return ResponseEntity.status(code.getHttpStatus())
                .contentType(PROBLEM_JSON)
                .body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e,
                                                         HttpServletRequest request) {
        // Exception messages may contain internal paths, SQL errors, etc.; desensitize before returning to user
        String rawMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
        String safeMessage = SensitiveDataMaskingConverter.maskSensitiveData(rawMessage);
        log.error("Request failed: {}", safeMessage, e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", safeMessage, request);
    }

    // ==================== Helper ====================

    /**
     * Build RFC 7807 Problem Detail response, Content-Type set to application/problem+json.
     */
    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String error,
                                                         String detail, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .error(error)
                .status(status.value())
                .message(detail)
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(status)
                .contentType(PROBLEM_JSON)
                .body(body);
    }
}
