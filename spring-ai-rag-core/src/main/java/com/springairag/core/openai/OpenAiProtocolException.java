package com.springairag.core.openai;

/**
 * OpenAI 兼容入口的稳定协议错误。
 */
public class OpenAiProtocolException extends RuntimeException {

    private final int status;
    private final String type;
    private final String param;
    private final String code;

    public OpenAiProtocolException(
            int status,
            String message,
            String type,
            String param,
            String code) {
        super(message);
        this.status = status;
        this.type = type;
        this.param = param;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getType() {
        return type;
    }

    public String getParam() {
        return param;
    }

    public String getCode() {
        return code;
    }

    public static OpenAiProtocolException invalid(
            String message, String param, String code) {
        return new OpenAiProtocolException(
                400, message, "invalid_request_error", param, code);
    }

    public static OpenAiProtocolException modelNotFound(String alias) {
        return new OpenAiProtocolException(
                404,
                "The model '" + alias + "' does not exist or is not available",
                "invalid_request_error",
                "model",
                "model_not_found");
    }
}
