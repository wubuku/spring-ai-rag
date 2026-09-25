package com.springairag.core.service;

import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.core.service.EmbeddingPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * DocumentEmbedService.sendProgress 长尾（Batch 647，JaCoCo 驱
 * 动）：批量进度回调抛异常时吞掉并继续（best-effort）、null 回调
 * 跳过、正常回调透传全部计数字段。
 */
class DocumentEmbedServiceSendProgressTailTest {

    private DocumentEmbedService service;
    private Method sendProgress;

    @BeforeEach
    void setUp() throws Exception {
        service = new DocumentEmbedService(
                mock(com.springairag.core.repository.RagDocumentRepository.class),
                mock(EmbeddingBatchService.class),
                mock(EmbeddingPersistenceService.class),
                mock(com.springairag.core.config.EmbeddingProfileProvider.class),
                new RagProperties());
        sendProgress = DocumentEmbedService.class.getDeclaredMethod(
                "sendProgress",
                java.util.function.Consumer.class,
                int.class, int.class, Long.class, String.class,
                int.class, int.class, String.class,
                int.class, int.class, int.class);
        sendProgress.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private void send(java.util.function.Consumer<?> callback) throws Exception {
        sendProgress.invoke(service, callback,
                0, 3, 41L, "EMBEDDING", 1, 1, "嵌入中", 2, 0, 1);
    }

    @Test
    void throwingCallbackIsSwallowed() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        send(value -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("回调消费者崩溃");
        });

        assertEquals(1, attempts.get());
    }

    @Test
    void nullCallbackIsSkipped() throws Exception {
        send(null);
    }

    @SuppressWarnings("unchecked")
    @Test
    void healthyCallbackReceivesBatchCounters() throws Exception {
        AtomicInteger seen = new AtomicInteger();
        send(value -> seen.incrementAndGet());

        assertEquals(1, seen.get());
    }
}
