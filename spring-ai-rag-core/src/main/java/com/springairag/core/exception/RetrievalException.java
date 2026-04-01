package com.springairag.core.exception;

/**
 * 检索异常
 *
 * <p>混合检索、向量搜索、全文搜索失败时抛出。GlobalExceptionHandler 返回 500。
 */
public class RetrievalException extends RagException {

    public RetrievalException(String query, String detail) {
        super("RETRIEVAL_FAILED", "检索失败: query=" + query + ", " + detail, 500);
    }

    public RetrievalException(String message) {
        super("RETRIEVAL_FAILED", message, 500);
    }

    public RetrievalException(String message, Throwable cause) {
        super("RETRIEVAL_FAILED", message, 500, cause);
    }
}
