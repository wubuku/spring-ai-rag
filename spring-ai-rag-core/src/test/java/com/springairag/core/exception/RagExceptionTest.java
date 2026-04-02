package com.springairag.core.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务异常类完整测试
 */
class RagExceptionTest {

    @Test
    @DisplayName("RagException 基类构造和 getter")
    void baseException() {
        var ex = new RagException("TEST_CODE", "测试消息", 400);

        assertEquals("TEST_CODE", ex.getErrorCode());
        assertEquals("测试消息", ex.getMessage());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    @DisplayName("RagException 带 cause 构造")
    void baseExceptionWithCause() {
        var cause = new RuntimeException("原始异常");
        var ex = new RagException("TEST_CODE", "测试消息", 500, cause);

        assertSame(cause, ex.getCause());
        assertEquals("TEST_CODE", ex.getErrorCode());
        assertEquals(500, ex.getHttpStatus());
    }

    @Test
    @DisplayName("DocumentNotFoundException 通过 documentId 构造")
    void documentNotFoundById() {
        var ex = new DocumentNotFoundException(42L);

        assertEquals("DOCUMENT_NOT_FOUND", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("42"));
    }

    @Test
    @DisplayName("DocumentNotFoundException 通过 message 构造")
    void documentNotFoundByMessage() {
        var ex = new DocumentNotFoundException("自定义消息");

        assertEquals("DOCUMENT_NOT_FOUND", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
        assertEquals("自定义消息", ex.getMessage());
    }

    @Test
    @DisplayName("DocumentNotFoundException 是 RagException 子类")
    void documentNotFoundInheritance() {
        var ex = new DocumentNotFoundException(1L);
        assertInstanceOf(RagException.class, ex);
    }

    @Test
    @DisplayName("EmbeddingException 通过 documentId + detail 构造")
    void embeddingExceptionWithDetail() {
        var ex = new EmbeddingException(100L, "超时");

        assertEquals("EMBEDDING_FAILED", ex.getErrorCode());
        assertEquals(500, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("100"));
        assertTrue(ex.getMessage().contains("超时"));
    }

    @Test
    @DisplayName("EmbeddingException 通过 message 构造")
    void embeddingExceptionWithMessage() {
        var ex = new EmbeddingException("嵌入失败");

        assertEquals("EMBEDDING_FAILED", ex.getErrorCode());
        assertEquals(500, ex.getHttpStatus());
        assertEquals("嵌入失败", ex.getMessage());
    }

    @Test
    @DisplayName("EmbeddingException 带 cause 构造")
    void embeddingExceptionWithCause() {
        var cause = new RuntimeException("底层异常");
        var ex = new EmbeddingException("嵌入失败", cause);

        assertEquals("EMBEDDING_FAILED", ex.getErrorCode());
        assertEquals(500, ex.getHttpStatus());
        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("EmbeddingException 是 RagException 子类")
    void embeddingExceptionInheritance() {
        var ex = new EmbeddingException("test");
        assertInstanceOf(RagException.class, ex);
    }

    @Test
    @DisplayName("RetrievalException 通过 query + detail 构造")
    void retrievalExceptionWithDetail() {
        var ex = new RetrievalException("测试查询", "向量库连接失败");

        assertEquals("RETRIEVAL_FAILED", ex.getErrorCode());
        assertEquals(500, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("测试查询"));
        assertTrue(ex.getMessage().contains("向量库连接失败"));
    }

    @Test
    @DisplayName("RetrievalException 通过 message 构造")
    void retrievalExceptionWithMessage() {
        var ex = new RetrievalException("检索异常");

        assertEquals("RETRIEVAL_FAILED", ex.getErrorCode());
        assertEquals("检索异常", ex.getMessage());
    }

    @Test
    @DisplayName("RetrievalException 带 cause 构造")
    void retrievalExceptionWithCause() {
        var cause = new RuntimeException("DB异常");
        var ex = new RetrievalException("检索失败", cause);

        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("RetrievalException 是 RagException 子类")
    void retrievalExceptionInheritance() {
        var ex = new RetrievalException("test");
        assertInstanceOf(RagException.class, ex);
    }

    @Test
    @DisplayName("异常类都继承 RuntimeException")
    void allExtendRuntimeException() {
        assertInstanceOf(RuntimeException.class, new RagException("C", "M", 500));
        assertInstanceOf(RuntimeException.class, new DocumentNotFoundException(1L));
        assertInstanceOf(RuntimeException.class, new EmbeddingException("M"));
        assertInstanceOf(RuntimeException.class, new RetrievalException("M"));
    }
}
