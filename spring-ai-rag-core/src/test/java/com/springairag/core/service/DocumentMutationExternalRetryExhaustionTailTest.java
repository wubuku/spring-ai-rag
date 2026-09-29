package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.exception.StructuredRecordConflictException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * external 事务重试耗尽长尾（Batch 717，JaCoCo 驱动）：
 * executeExternalInTransaction 在连续可重试并发失败达到
 * MAX_EXTERNAL_TRANSACTION_ATTEMPTS 后转为冲突异常（1393-1395），
 * 每次尝试均经事务模板执行。
 */
class DocumentMutationExternalRetryExhaustionTailTest {

    private PlatformTransactionManager transactionManager;
    private DocumentMutationService service;

    @BeforeEach
    void setUp() {
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        service = new DocumentMutationService(
                mock(RagDocumentRepository.class),
                mock(RagEmbeddingRepository.class),
                mock(CollectionIdentityResolver.class),
                mock(DocumentVersionService.class),
                mock(com.springairag.core.embeddingjob.EmbeddingDispatchService.class),
                mock(DocumentEmbedService.class),
                mock(DocumentLifecycleService.class),
                mock(JdbcTemplate.class),
                new ObjectMapper(),
                new com.springairag.core.config.RagProperties(),
                transactionManager);
    }

    private Object invokeExecute(boolean jsonRecord,
                                 java.util.function.Supplier<Object> callback)
            throws Exception {
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "executeExternalInTransaction",
                boolean.class, java.util.function.Supplier.class);
        method.setAccessible(true);
        try {
            return method.invoke(service, jsonRecord, callback);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void retryExhaustionConvertsToDocumentRevisionConflict() {
        AtomicInteger attempts = new AtomicInteger();

        DocumentRevisionConflictException error =
                assertThrows(DocumentRevisionConflictException.class,
                        () -> invokeExecute(false, () -> {
                            attempts.incrementAndGet();
                            throw new DataIntegrityViolationException("cas");
                        }));

        assertEquals(3, attempts.get());
        verify(transactionManager, times(3)).getTransaction(any());
        assertTrue(error.getMessage().contains("did not converge after 3"));
    }

    @Test
    void retryExhaustionConvertsJsonRecordConflict() {
        StructuredRecordConflictException error =
                assertThrows(StructuredRecordConflictException.class,
                        () -> invokeExecute(true, () -> {
                            throw new DataIntegrityViolationException("cas");
                        }));

        assertTrue(error.getMessage().contains("did not converge after 3"));
    }

}
