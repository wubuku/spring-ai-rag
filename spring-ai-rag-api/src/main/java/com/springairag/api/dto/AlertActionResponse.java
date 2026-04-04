package com.springairag.api.dto;

/**
 * 告警操作响应
 *
 * @param success 是否成功
 * @param message 操作结果消息
 */
public record AlertActionResponse(boolean success, String message) {
    public static AlertActionResponse ok(String message) {
        return new AlertActionResponse(true, message);
    }

    public static AlertActionResponse fail(String message) {
        return new AlertActionResponse(false, message);
    }
}
