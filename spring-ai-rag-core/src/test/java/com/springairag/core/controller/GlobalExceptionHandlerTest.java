package com.springairag.core.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
}
