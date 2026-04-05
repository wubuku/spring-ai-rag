package com.springairag.core.exception;

/**
 * 文档未找到异常
 *
 * <p>当请求的文档 ID 不存在时抛出。GlobalExceptionHandler 返回 404。
 */
public class DocumentNotFoundException extends RagException {

    public DocumentNotFoundException(Long documentId) {
        super("DOCUMENT_NOT_FOUND", "Document not found: id=" + documentId, 404);
    }

    public DocumentNotFoundException(String message) {
        super("DOCUMENT_NOT_FOUND", message, 404);
    }
}
