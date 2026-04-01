package com.springairag.api.dto;

import java.time.Instant;

/**
 * 统一错误响应 DTO
 *
 * <p>所有 API 错误返回此格式，替代散落各处的 Map<String, Object>。
 */
public class ErrorResponse {

    /** 错误码（如 VALIDATION_FAILED、NOT_FOUND） */
    private String error;

    /** 人类可读的错误消息 */
    private String message;

    /** 错误发生时间（ISO-8601） */
    private String timestamp;

    /** 可选：请求路径 */
    private String path;

    public ErrorResponse() {
        this.timestamp = Instant.now().toString();
    }

    public ErrorResponse(String error, String message) {
        this.error = error;
        this.message = message;
        this.timestamp = Instant.now().toString();
    }

    public ErrorResponse(String error, String message, String path) {
        this.error = error;
        this.message = message;
        this.path = path;
        this.timestamp = Instant.now().toString();
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ErrorResponse response = new ErrorResponse();

        public Builder error(String error) {
            response.error = error;
            return this;
        }

        public Builder message(String message) {
            response.message = message;
            return this;
        }

        public Builder path(String path) {
            response.path = path;
            return this;
        }

        public ErrorResponse build() {
            return response;
        }
    }

    // ==================== Getters ====================

    public String getError() { return error; }
    public String getMessage() { return message; }
    public String getTimestamp() { return timestamp; }
    public String getPath() { return path; }

    public void setError(String error) { this.error = error; }
    public void setMessage(String message) { this.message = message; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public void setPath(String path) { this.path = path; }
}
