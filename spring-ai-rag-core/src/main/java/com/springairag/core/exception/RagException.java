package com.springairag.core.exception;

/**
 * RAG 业务异常基类
 *
 * <p>所有 RAG 相关业务异常的公共父类，提供统一的错误码和 HTTP 状态码。
 * 子类只需关注具体业务语义，GlobalExceptionHandler 统一处理。
 *
 * <p>使用示例：
 * <pre>
 * throw new DocumentNotFoundException(42L);
 * throw new EmbeddingException(42L, "嵌入模型超时");
 * </pre>
 */
public class RagException extends RuntimeException {

    private final String errorCode;
    private final int httpStatus;

    /**
     * @param errorCode  业务错误码（如 DOCUMENT_NOT_FOUND）
     * @param message    用户友好的错误描述
     * @param httpStatus HTTP 状态码
     */
    public RagException(String errorCode, String message, int httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    /**
     * @param errorCode  业务错误码
     * @param message    错误描述
     * @param httpStatus HTTP 状态码
     * @param cause      原始异常
     */
    public RagException(String errorCode, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
