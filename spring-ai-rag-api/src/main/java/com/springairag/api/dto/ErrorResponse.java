package com.springairag.api.dto;

import com.springairag.api.enums.ErrorCode;

import java.time.Instant;

/**
 * 统一错误响应 DTO — RFC 7807 Problem Detail 兼容
 *
 * <p>所有 API 错误返回此格式。遵循 RFC 7807 (Problem Details for HTTP APIs)：
 * <ul>
 *   <li>{@code type} — 问题类型的 URI 标识（如 "https://springairag.dev/problems/validation-failed"）</li>
 *   <li>{@code title} — 问题类型的简短描述（对应 RFC title）</li>
 *   <li>{@code status} — HTTP 状态码</li>
 *   <li>{@code detail} — 具体错误消息（对应 RFC detail）</li>
 *   <li>{@code instance} — 发生问题的请求路径（对应 RFC instance）</li>
 * </ul>
 *
 * <p>同时保留向后兼容的字段：
 * <ul>
 *   <li>{@code error} — 错误码（同 title，保留向后兼容）</li>
 *   <li>{@code message} — 错误消息（同 detail，保留向后兼容）</li>
 *   <li>{@code timestamp} — 错误发生时间</li>
 *   <li>{@code path} — 请求路径（同 instance，保留向后兼容）</li>
 * </ul>
 */
public class ErrorResponse {

    /** RFC 7807: 问题类型 URI */
    private String type;

    /** RFC 7807: 问题标题（保留 error 向后兼容） */
    private String title;

    /** RFC 7807: HTTP 状态码 */
    private Integer status;

    /** RFC 7807: 详细描述（保留 message 向后兼容） */
    private String detail;

    /** RFC 7807: 问题实例 URI（保留 path 向后兼容） */
    private String instance;

    /** 错误码（向后兼容，等同 title） */
    private String error;

    /** 人类可读的错误消息（向后兼容，等同 detail） */
    private String message;

    /** 错误发生时间（ISO-8601） */
    private String timestamp;

    /** 可选：请求路径（向后兼容，等同 instance） */
    private String path;

    /** 默认问题类型 URI 前缀 */
    private static final String PROBLEM_TYPE_PREFIX = "https://springairag.dev/problems/";

    public ErrorResponse() {
        this.timestamp = Instant.now().toString();
    }

    public ErrorResponse(String error, String message) {
        this.error = error;
        this.title = error;
        this.message = message;
        this.detail = message;
        this.timestamp = Instant.now().toString();
    }

    public ErrorResponse(String error, String message, String path) {
        this.error = error;
        this.title = error;
        this.message = message;
        this.detail = message;
        this.path = path;
        this.instance = path;
        this.timestamp = Instant.now().toString();
    }

    // ==================== Builder ====================

    /** 简单错误消息工厂方法 */
    public static ErrorResponse of(String detail) {
        return builder()
                .detail(detail)
                .title("Bad Request")
                .status(400)
                .type(PROBLEM_TYPE_PREFIX + "bad-request")
                .build();
    }

    /**
     * Creates an ErrorResponse from a typed {@link ErrorCode} enum.
     *
     * @param code   the standardized error code (determines status, title, type)
     * @param detail human-readable error detail message
     */
    public static ErrorResponse of(ErrorCode code, String detail) {
        return builder()
                .error(code.getCode())
                .title(code.getTitle())
                .status(code.getHttpStatus())
                .type(code.getProblemTypeUri())
                .detail(detail)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ErrorResponse response = new ErrorResponse();

        public Builder type(String type) {
            response.type = type;
            return this;
        }

        public Builder title(String title) {
            response.title = title;
            response.error = title;
            return this;
        }

        public Builder status(int status) {
            response.status = status;
            return this;
        }

        public Builder detail(String detail) {
            response.detail = detail;
            response.message = detail;
            return this;
        }

        public Builder instance(String instance) {
            response.instance = instance;
            response.path = instance;
            return this;
        }

        /** 设置错误码，自动生成 type URI */
        public Builder error(String error) {
            response.error = error;
            response.title = error;
            response.type = PROBLEM_TYPE_PREFIX + error.toLowerCase().replace('_', '-');
            return this;
        }

        public Builder message(String message) {
            response.message = message;
            response.detail = message;
            return this;
        }

        public Builder path(String path) {
            response.path = path;
            response.instance = path;
            return this;
        }

        public ErrorResponse build() {
            // 确保 type 不为 null
            if (response.type == null && response.error != null) {
                response.type = PROBLEM_TYPE_PREFIX + response.error.toLowerCase().replace('_', '-');
            }
            // 确保 title/detail 同步
            if (response.title == null && response.error != null) {
                response.title = response.error;
            }
            if (response.detail == null && response.message != null) {
                response.detail = response.message;
            }
            if (response.instance == null && response.path != null) {
                response.instance = response.path;
            }
            return response;
        }
    }

    // ==================== Getters ====================

    public String getType() { return type; }
    public String getTitle() { return title; }
    public Integer getStatus() { return status; }
    public String getDetail() { return detail; }
    public String getInstance() { return instance; }
    public String getError() { return error; }
    public String getMessage() { return message; }
    public String getTimestamp() { return timestamp; }
    public String getPath() { return path; }

    public void setType(String type) { this.type = type; }
    public void setTitle(String title) { this.title = title; }
    public void setStatus(Integer status) { this.status = status; }
    public void setDetail(String detail) { this.detail = detail; }
    public void setInstance(String instance) { this.instance = instance; }
    public void setError(String error) { this.error = error; }
    public void setMessage(String message) { this.message = message; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public void setPath(String path) { this.path = path; }
}
