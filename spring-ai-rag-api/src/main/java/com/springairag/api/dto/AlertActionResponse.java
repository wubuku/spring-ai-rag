package com.springairag.api.dto;

/**
 * Alert action response
 *
 * @param success Whether the operation succeeded
 * @param message Operation result message
 */
public record AlertActionResponse(boolean success, String message) {
    public static AlertActionResponse ok(String message) {
        return new AlertActionResponse(true, message);
    }

    public static AlertActionResponse fail(String message) {
        return new AlertActionResponse(false, message);
    }
}
