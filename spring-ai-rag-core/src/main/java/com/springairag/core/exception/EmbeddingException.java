package com.springairag.core.exception;

/**
 * 嵌入向量生成异常
 *
 * <p>嵌入模型调用失败、向量存储异常等场景抛出。GlobalExceptionHandler 返回 500。
 */
public class EmbeddingException extends RagException {

    public EmbeddingException(Long documentId, String detail) {
        super("EMBEDDING_FAILED", "嵌入向量生成失败: documentId=" + documentId + ", " + detail, 500);
    }

    public EmbeddingException(String message) {
        super("EMBEDDING_FAILED", message, 500);
    }

    public EmbeddingException(String message, Throwable cause) {
        super("EMBEDDING_FAILED", message, 500, cause);
    }
}
