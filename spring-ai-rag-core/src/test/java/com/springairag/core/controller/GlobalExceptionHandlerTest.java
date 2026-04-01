package com.springairag.core.controller;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.exception.EmbeddingException;
import com.springairag.core.exception.RetrievalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GlobalExceptionHandler 单元测试
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void handleException_returns500() {
        Exception e = new RuntimeException("something went wrong");

        ResponseEntity<ErrorResponse> response = handler.handleException(e);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("INTERNAL_ERROR", body.getError());
        assertEquals("something went wrong", body.getMessage());
        assertNotNull(body.getTimestamp());
    }

    @Test
    void handleException_nullMessage_usesDefault() {
        Exception e = new RuntimeException((String) null);

        ResponseEntity<ErrorResponse> response = handler.handleException(e);

        assertEquals("Unknown error", response.getBody().getMessage());
    }

    @Test
    void handleBadRequest_returns400() {
        IllegalArgumentException e = new IllegalArgumentException("invalid param");

        ResponseEntity<ErrorResponse> response = handler.handleBadRequest(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("BAD_REQUEST", body.getError());
        assertEquals("invalid param", body.getMessage());
        assertNotNull(body.getTimestamp());
    }

    @Test
    void handleBadRequest_nullMessage_stillReturns400() {
        IllegalArgumentException e = new IllegalArgumentException((String) null);

        ResponseEntity<ErrorResponse> response = handler.handleBadRequest(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("BAD_REQUEST", body.getError());
        assertEquals("Invalid argument", body.getMessage());
    }

    @Test
    void handleMissingParam_returns400() {
        MissingServletRequestParameterException e =
                new MissingServletRequestParameterException("sessionId", "String");

        ResponseEntity<ErrorResponse> response = handler.handleMissingParam(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().getMessage().contains("sessionId"));
    }

    @Test
    void handleUnreadableMessage_returns400() {
        HttpMessageNotReadableException e =
                new HttpMessageNotReadableException("parse error");

        ResponseEntity<ErrorResponse> response = handler.handleUnreadableMessage(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_REQUEST_BODY", response.getBody().getError());
        assertTrue(response.getBody().getMessage().contains("JSON"));
    }

    @Test
    void handleTypeMismatch_returns400() {
        MethodArgumentTypeMismatchException e = new MethodArgumentTypeMismatchException(
                "not-a-number", Integer.class, "limit", null, null);

        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatch(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().getMessage().contains("limit"));
    }

    @Test
    void handleMethodNotSupported_returns405() {
        HttpRequestMethodNotSupportedException e =
                new HttpRequestMethodNotSupportedException("PATCH");

        ResponseEntity<ErrorResponse> response = handler.handleMethodNotSupported(e);

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertTrue(response.getBody().getMessage().contains("PATCH"));
    }

    @Test
    void handleNotFound_returns404() throws Exception {
        NoHandlerFoundException e =
                new NoHandlerFoundException("GET", "/api/unknown", null);

        ResponseEntity<ErrorResponse> response = handler.handleNotFound(e);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("NOT_FOUND", response.getBody().getError());
    }

    @Test
    void handleDataAccess_returns500() {
        DataAccessException e = new DataAccessException("connection timeout") {};

        ResponseEntity<ErrorResponse> response = handler.handleDataAccess(e);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("DATABASE_ERROR", response.getBody().getError());
        assertEquals("数据库操作失败", response.getBody().getMessage());
    }

    @Test
    void handleValidation_singleFieldError_returns400WithFieldName() throws Exception {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "message", "消息内容不能为空"));

        MethodArgumentNotValidException e = new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_FAILED", response.getBody().getError());
        assertTrue(response.getBody().getMessage().contains("message"));
        assertTrue(response.getBody().getMessage().contains("消息内容不能为空"));
        assertNotNull(response.getBody().getTimestamp());
    }

    @Test
    void handleValidation_multipleFieldErrors_returns400WithAllMessages() throws Exception {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "message", "消息内容不能为空"));
        bindingResult.addError(new FieldError("request", "sessionId", "会话 ID 不能为空"));

        MethodArgumentNotValidException e = new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        String message = response.getBody().getMessage();
        assertTrue(message.contains("message"));
        assertTrue(message.contains("sessionId"));
        assertTrue(message.contains("; "));
    }

    @Test
    void handleDocumentNotFound_returns404() {
        DocumentNotFoundException e = new DocumentNotFoundException(42L);

        ResponseEntity<ErrorResponse> response = handler.handleRagException(e);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("DOCUMENT_NOT_FOUND", response.getBody().getError());
        assertTrue(response.getBody().getMessage().contains("42"));
    }

    @Test
    void handleEmbeddingException_returns500() {
        EmbeddingException e = new EmbeddingException(1L, "模型超时");

        ResponseEntity<ErrorResponse> response = handler.handleRagException(e);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("EMBEDDING_FAILED", response.getBody().getError());
        assertTrue(response.getBody().getMessage().contains("模型超时"));
    }

    @Test
    void handleRetrievalException_returns500() {
        RetrievalException e = new RetrievalException("Spring Boot", "数据库连接失败");

        ResponseEntity<ErrorResponse> response = handler.handleRagException(e);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("RETRIEVAL_FAILED", response.getBody().getError());
        assertTrue(response.getBody().getMessage().contains("Spring Boot"));
    }
}
