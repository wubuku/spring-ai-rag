package com.springairag.core.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.Map;

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

        ResponseEntity<Map<String, Object>> response = handler.handleException(e);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("RuntimeException", body.get("error"));
        assertEquals("something went wrong", body.get("message"));
        assertNotNull(body.get("timestamp"));
    }

    @Test
    void handleException_nullMessage_usesDefault() {
        Exception e = new RuntimeException((String) null);

        ResponseEntity<Map<String, Object>> response = handler.handleException(e);

        assertEquals("Unknown error", response.getBody().get("message"));
    }

    @Test
    void handleBadRequest_returns400() {
        IllegalArgumentException e = new IllegalArgumentException("invalid param");

        ResponseEntity<Map<String, Object>> response = handler.handleBadRequest(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("Bad Request", body.get("error"));
        assertEquals("invalid param", body.get("message"));
        assertNotNull(body.get("timestamp"));
    }

    @Test
    void handleBadRequest_nullMessage_stillReturns400() {
        IllegalArgumentException e = new IllegalArgumentException((String) null);

        ResponseEntity<Map<String, Object>> response = handler.handleBadRequest(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("Bad Request", body.get("error"));
        assertEquals("Invalid argument", body.get("message"));
    }

    @Test
    void handleMissingParam_returns400() {
        MissingServletRequestParameterException e =
                new MissingServletRequestParameterException("sessionId", "String");

        ResponseEntity<Map<String, Object>> response = handler.handleMissingParam(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().get("message").toString().contains("sessionId"));
    }

    @Test
    void handleUnreadableMessage_returns400() {
        HttpMessageNotReadableException e =
                new HttpMessageNotReadableException("parse error");

        ResponseEntity<Map<String, Object>> response = handler.handleUnreadableMessage(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid Request Body", response.getBody().get("error"));
        assertTrue(response.getBody().get("message").toString().contains("JSON"));
    }

    @Test
    void handleTypeMismatch_returns400() {
        // MethodArgumentTypeMismatchException needs (Class, Parameter, name, type, value)
        // Simplified: use the handler directly
        MethodArgumentTypeMismatchException e = new MethodArgumentTypeMismatchException(
                "not-a-number", Integer.class, "limit", null, null);

        ResponseEntity<Map<String, Object>> response = handler.handleTypeMismatch(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().get("message").toString().contains("limit"));
    }

    @Test
    void handleMethodNotSupported_returns405() {
        HttpRequestMethodNotSupportedException e =
                new HttpRequestMethodNotSupportedException("PATCH");

        ResponseEntity<Map<String, Object>> response = handler.handleMethodNotSupported(e);

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertTrue(response.getBody().get("message").toString().contains("PATCH"));
    }

    @Test
    void handleNotFound_returns404() throws Exception {
        NoHandlerFoundException e =
                new NoHandlerFoundException("GET", "/api/unknown", null);

        ResponseEntity<Map<String, Object>> response = handler.handleNotFound(e);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("Not Found", response.getBody().get("error"));
    }

    @Test
    void handleDataAccess_returns500() {
        DataAccessException e = new DataAccessException("connection timeout") {};

        ResponseEntity<Map<String, Object>> response = handler.handleDataAccess(e);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Database Error", response.getBody().get("error"));
        // 不暴露数据库细节
        assertEquals("数据库操作失败", response.getBody().get("message"));
    }
}
