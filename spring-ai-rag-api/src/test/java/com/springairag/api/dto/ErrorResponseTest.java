package com.springairag.api.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ErrorResponse DTO 单元测试
 */
class ErrorResponseTest {

    @Test
    void defaultConstructor_setsTimestamp() {
        ErrorResponse response = new ErrorResponse();
        assertNotNull(response.getTimestamp());
        assertNull(response.getError());
        assertNull(response.getMessage());
    }

    @Test
    void constructorWithErrorAndMessage() {
        ErrorResponse response = new ErrorResponse("NOT_FOUND", "资源不存在");
        assertEquals("NOT_FOUND", response.getError());
        assertEquals("资源不存在", response.getMessage());
        assertNotNull(response.getTimestamp());
        assertNull(response.getPath());
    }

    @Test
    void constructorWithErrorMessageAndPath() {
        ErrorResponse response = new ErrorResponse("BAD_REQUEST", "参数无效", "/api/v1/rag/documents");
        assertEquals("BAD_REQUEST", response.getError());
        assertEquals("参数无效", response.getMessage());
        assertEquals("/api/v1/rag/documents", response.getPath());
        assertNotNull(response.getTimestamp());
    }

    @Test
    void builderPattern() {
        ErrorResponse response = ErrorResponse.builder()
                .error("VALIDATION_FAILED")
                .message("title: 不能为空")
                .path("/api/v1/rag/documents")
                .build();

        assertEquals("VALIDATION_FAILED", response.getError());
        assertEquals("title: 不能为空", response.getMessage());
        assertEquals("/api/v1/rag/documents", response.getPath());
        assertNotNull(response.getTimestamp());
    }

    @Test
    void builderWithoutPath() {
        ErrorResponse response = ErrorResponse.builder()
                .error("INTERNAL_ERROR")
                .message("服务器内部错误")
                .build();

        assertEquals("INTERNAL_ERROR", response.getError());
        assertEquals("服务器内部错误", response.getMessage());
        assertNull(response.getPath());
    }

    @Test
    void settersWork() {
        ErrorResponse response = new ErrorResponse();
        response.setError("CUSTOM_ERROR");
        response.setMessage("自定义错误");
        response.setPath("/test");
        response.setTimestamp("2026-04-02T00:00:00Z");

        assertEquals("CUSTOM_ERROR", response.getError());
        assertEquals("自定义错误", response.getMessage());
        assertEquals("/test", response.getPath());
        assertEquals("2026-04-02T00:00:00Z", response.getTimestamp());
    }
}
