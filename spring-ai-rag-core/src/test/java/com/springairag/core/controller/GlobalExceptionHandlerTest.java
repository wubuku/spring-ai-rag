package com.springairag.core.controller;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.exception.EmbeddingException;
import com.springairag.core.exception.RetrievalException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GlobalExceptionHandler 单元测试 — 含 RFC 7807 字段验证
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest();
        ((MockHttpServletRequest) request).setRequestURI("/api/v1/rag/test");
    }

    // ==================== RFC 7807 字段验证 ====================

    @Test
    void rfc7807_errorResponse_includesTypeField() {
        ResponseEntity<ErrorResponse> response = handler.handleException(new RuntimeException("x"), request);
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("https://springairag.dev/problems/internal-error", body.getType());
        assertEquals("INTERNAL_ERROR", body.getTitle());
        assertEquals(500, body.getStatus());
        assertEquals("/api/v1/rag/test", body.getInstance());
    }

    @Test
    void rfc7807_errorResponse_typeGeneratedFromErrorCode() {
        ResponseEntity<ErrorResponse> response = handler.handleBadRequest(new IllegalArgumentException("x"), request);
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("https://springairag.dev/problems/bad-request", body.getType());
        assertEquals("BAD_REQUEST", body.getTitle());
    }

    // ==================== 核心处理器测试 ====================

    @Test
    void handleException_returns500() {
        ResponseEntity<ErrorResponse> response = handler.handleException(new RuntimeException("something went wrong"), request);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("INTERNAL_ERROR", body.getError());
        assertEquals("something went wrong", body.getMessage());
        assertNotNull(body.getTimestamp());
        assertEquals("/api/v1/rag/test", body.getPath());
    }

    @Test
    void handleException_nullMessage_usesDefault() {
        ResponseEntity<ErrorResponse> response = handler.handleException(new RuntimeException((String) null), request);
        assertEquals("Unknown error", response.getBody().getMessage());
    }

    @Test
    void handleBadRequest_returns400() {
        ResponseEntity<ErrorResponse> response = handler.handleBadRequest(new IllegalArgumentException("invalid param"), request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertEquals("BAD_REQUEST", body.getError());
        assertEquals("invalid param", body.getMessage());
    }

    @Test
    void handleBadRequest_nullMessage_stillReturns400() {
        ResponseEntity<ErrorResponse> response = handler.handleBadRequest(new IllegalArgumentException((String) null), request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid argument", response.getBody().getMessage());
    }

    @Test
    void handleMissingParam_returns400() {
        MissingServletRequestParameterException e = new MissingServletRequestParameterException("sessionId", "String");
        ResponseEntity<ErrorResponse> response = handler.handleMissingParam(e, request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().getMessage().contains("sessionId"));
    }

    @Test
    void handleUnreadableMessage_returns400() {
        HttpMessageNotReadableException e = new HttpMessageNotReadableException("parse error");
        ResponseEntity<ErrorResponse> response = handler.handleUnreadableMessage(e, request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_REQUEST_BODY", response.getBody().getError());
        assertTrue(response.getBody().getMessage().contains("JSON"));
    }

    @Test
    void handleTypeMismatch_returns400() {
        MethodArgumentTypeMismatchException e = new MethodArgumentTypeMismatchException(
                "not-a-number", Integer.class, "limit", null, null);
        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatch(e, request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().getMessage().contains("limit"));
    }

    @Test
    void handleMethodNotSupported_returns405() {
        HttpRequestMethodNotSupportedException e = new HttpRequestMethodNotSupportedException("PATCH");
        ResponseEntity<ErrorResponse> response = handler.handleMethodNotSupported(e, request);
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertTrue(response.getBody().getMessage().contains("PATCH"));
    }

    @Test
    void handleNotFound_returns404() throws Exception {
        NoHandlerFoundException e = new NoHandlerFoundException("GET", "/api/unknown", null);
        ResponseEntity<ErrorResponse> response = handler.handleNotFound(e, request);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("NOT_FOUND", response.getBody().getError());
    }

    @Test
    void handleDataAccess_returns500() {
        DataAccessException e = new DataAccessException("connection timeout") {};
        ResponseEntity<ErrorResponse> response = handler.handleDataAccess(e, request);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("DATABASE_ERROR", response.getBody().getError());
        assertEquals("数据库操作失败", response.getBody().getMessage());
    }

    @Test
    void handleValidation_singleFieldError_returns400WithFieldName() throws Exception {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "message", "消息内容不能为空"));
        MethodArgumentNotValidException e = new MethodArgumentNotValidException(null, bindingResult);
        ResponseEntity<ErrorResponse> response = handler.handleValidation(e, request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_FAILED", response.getBody().getError());
        assertTrue(response.getBody().getMessage().contains("message"));
    }

    @Test
    void handleValidation_multipleFieldErrors_returns400WithAllMessages() throws Exception {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "message", "消息内容不能为空"));
        bindingResult.addError(new FieldError("request", "sessionId", "会话 ID 不能为空"));
        MethodArgumentNotValidException e = new MethodArgumentNotValidException(null, bindingResult);
        ResponseEntity<ErrorResponse> response = handler.handleValidation(e, request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        String message = response.getBody().getMessage();
        assertTrue(message.contains("message"));
        assertTrue(message.contains("sessionId"));
        assertTrue(message.contains("; "));
    }

    @Test
    void handleDocumentNotFound_returns404() {
        DocumentNotFoundException e = new DocumentNotFoundException(42L);
        ResponseEntity<ErrorResponse> response = handler.handleRagException(e, request);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("DOCUMENT_NOT_FOUND", response.getBody().getError());
        assertTrue(response.getBody().getMessage().contains("42"));
    }

    @Test
    void handleEmbeddingException_returns500() {
        EmbeddingException e = new EmbeddingException(1L, "模型超时");
        ResponseEntity<ErrorResponse> response = handler.handleRagException(e, request);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("EMBEDDING_FAILED", response.getBody().getError());
        assertTrue(response.getBody().getMessage().contains("模型超时"));
    }

    @Test
    void handleRetrievalException_returns500() {
        RetrievalException e = new RetrievalException("Spring Boot", "数据库连接失败");
        ResponseEntity<ErrorResponse> response = handler.handleRagException(e, request);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("RETRIEVAL_FAILED", response.getBody().getError());
        assertTrue(response.getBody().getMessage().contains("Spring Boot"));
    }

    // ==================== ErrorResponse DTO 测试 ====================

    @Test
    void errorResponse_builder_syncsRfc7807Fields() {
        ErrorResponse response = ErrorResponse.builder()
                .error("VALIDATION_FAILED")
                .message("参数错误")
                .path("/api/v1/rag/test")
                .build();

        assertEquals("VALIDATION_FAILED", response.getError());
        assertEquals("VALIDATION_FAILED", response.getTitle());
        assertEquals("参数错误", response.getMessage());
        assertEquals("参数错误", response.getDetail());
        assertEquals("/api/v1/rag/test", response.getPath());
        assertEquals("/api/v1/rag/test", response.getInstance());
        assertEquals("https://springairag.dev/problems/validation-failed", response.getType());
    }

    @Test
    void errorResponse_builder_explicitStatus() {
        ErrorResponse response = ErrorResponse.builder()
                .error("RATE_LIMITED")
                .status(429)
                .build();

        assertEquals(429, response.getStatus());
    }

    @Test
    void errorResponse_backwardCompatible_fieldsPreserved() {
        ErrorResponse response = new ErrorResponse("TEST_ERROR", "测试错误", "/path");

        assertEquals("TEST_ERROR", response.getError());
        assertEquals("测试错误", response.getMessage());
        assertEquals("/path", response.getPath());
        assertNotNull(response.getTimestamp());
        assertEquals("TEST_ERROR", response.getTitle());
        assertEquals("测试错误", response.getDetail());
        assertEquals("/path", response.getInstance());
    }

    // ==================== ConstraintViolationException 测试 ====================

    @Test
    void handleConstraintViolation_returns400WithMessages() {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation = org.mockito.Mockito.mock(ConstraintViolation.class);
        org.mockito.Mockito.when(violation.getMessage()).thenReturn("消息内容不能为空");
        Set<ConstraintViolation<?>> violations = Set.of(violation);
        ConstraintViolationException e = new ConstraintViolationException(violations);
        ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(e, request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_FAILED", response.getBody().getError());
        assertTrue(response.getBody().getMessage().contains("消息内容不能为空"));
    }

    @Test
    void handleConstraintViolation_multipleViolations_joined() {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> v1 = org.mockito.Mockito.mock(ConstraintViolation.class);
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> v2 = org.mockito.Mockito.mock(ConstraintViolation.class);
        org.mockito.Mockito.when(v1.getMessage()).thenReturn("名称不能为空");
        org.mockito.Mockito.when(v2.getMessage()).thenReturn("长度不超过 255");
        Set<ConstraintViolation<?>> violations = Set.of(v1, v2);
        ConstraintViolationException e = new ConstraintViolationException(violations);
        ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(e, request);
        String message = response.getBody().getMessage();
        assertTrue(message.contains("名称不能为空"));
        assertTrue(message.contains("长度不超过 255"));
    }

    // ==================== Content-Type 验证 ====================

    @Test
    void allHandlers_returnProblemJsonContentType() {
        ResponseEntity<ErrorResponse> r1 = handler.handleException(new RuntimeException("x"), request);
        assertEquals("application/problem+json", r1.getHeaders().getContentType().toString());

        ResponseEntity<ErrorResponse> r2 = handler.handleBadRequest(new IllegalArgumentException("x"), request);
        assertEquals("application/problem+json", r2.getHeaders().getContentType().toString());

        MissingServletRequestParameterException e3 = new MissingServletRequestParameterException("q", "String");
        ResponseEntity<ErrorResponse> r3 = handler.handleMissingParam(e3, request);
        assertEquals("application/problem+json", r3.getHeaders().getContentType().toString());

        DataAccessException e4 = new DataAccessException("x") {};
        ResponseEntity<ErrorResponse> r4 = handler.handleDataAccess(e4, request);
        assertEquals("application/problem+json", r4.getHeaders().getContentType().toString());
    }

    // ==================== 敏感数据脱敏验证 ====================

    @Test
    void handleException_sensitiveDataInMessage_maskedInResponse() {
        // 异常消息含 API Key/Token 时，handleException 应脱敏后再返回
        RuntimeException e = new RuntimeException("Request failed: apiKey=sk-12345678 and token=eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJodHRwczovL3NvbWV0aGluZy5jb20ifQ.sig");
        ResponseEntity<ErrorResponse> response = handler.handleException(e, request);

        String message = response.getBody().getMessage();
        assertFalse(message.contains("sk-12345678"), "API Key should be masked");
        assertFalse(message.contains("eyJ"), "JWT token should be masked");
        assertTrue(message.contains("***REDACTED***"), "Message should contain redaction markers");
        assertEquals(500, response.getStatusCode().value());
    }

    @Test
    void handleException_sensitiveDataInMessage_maskedInLog() {
        // 异常消息脱敏同时，verify 日志调用的也是脱敏后的消息（间接验证）
        RuntimeException e = new RuntimeException("Password=super-secret and Bearer token=abc123xyz");
        ResponseEntity<ErrorResponse> response = handler.handleException(e, request);

        assertFalse(response.getBody().getMessage().contains("super-secret"));
        assertFalse(response.getBody().getMessage().contains("abc123xyz"));
        assertTrue(response.getBody().getMessage().contains("***REDACTED***"));
    }
}
