package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentLifecycleResponse;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentMutationService 完成阶段长尾（Batch 714，JaCoCo 驱
 * 动）：finish 在 SYNC 策略且已有派发结果时于重载前完成派发
 * （1666-1667）、文档在完成阶段消失时抛 DocumentNotFound
 * （1670-1671，反射 + mock 私有 record Prepared）。
 */
class DocumentMutationFinishTailTest {

    private RagDocumentRepository documentRepository;
    private EmbeddingDispatchService dispatchService;
    private DocumentMutationService service;
    private RagDocument document;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        document = new RagDocument();
        document.setId(41L);
        service = new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                mock(CollectionIdentityResolver.class),
                mock(DocumentVersionService.class),
                dispatchService,
                mock(DocumentEmbedService.class),
                mock(DocumentLifecycleService.class),
                mock(JdbcTemplate.class),
                new ObjectMapper(),
                new com.springairag.core.config.RagProperties(),
                transactionManager);
    }

    private Object prepared(EmbeddingPolicy policy,
                            EmbeddingDispatchService.Result dispatch)
            throws Exception {
        Class<?> type = Class.forName(
                "com.springairag.core.service.DocumentMutationService$Prepared");
        Object prepared = mock(type);
        when(type.getMethod("documentId").invoke(prepared)).thenReturn(41L);
        when(type.getMethod("action").invoke(prepared)).thenReturn("RESTORED");
        when(type.getMethod("documentRevision").invoke(prepared)).thenReturn(6L);
        when(type.getMethod("versionNumber").invoke(prepared)).thenReturn(9);
        when(type.getMethod("contentChanged").invoke(prepared)).thenReturn(true);
        when(type.getMethod("metadataChanged").invoke(prepared)).thenReturn(false);
        when(type.getMethod("scopeChanged").invoke(prepared)).thenReturn(false);
        when(type.getMethod("dispatch").invoke(prepared)).thenReturn(dispatch);
        when(type.getMethod("policy").invoke(prepared)).thenReturn(policy);
        return prepared;
    }

    private com.springairag.api.dto.DocumentMutationResponse invokeFinish(
            Object prepared) throws Exception {
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "finish",
                Class.forName(
                        "com.springairag.core.service.DocumentMutationService$Prepared"));
        method.setAccessible(true);
        try {
            return (com.springairag.api.dto.DocumentMutationResponse)
                    method.invoke(service, prepared);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void finishCompletesSyncDispatchBeforeReloadingDocument()
            throws Exception {
        EmbeddingDispatchService.Result queued =
                new EmbeddingDispatchService.Result(
                        EmbeddingAction.SYNC_COMPLETED, "EMBEDDED", "bge-m3",
                        UUID.randomUUID(), null, null);
        EmbeddingDispatchService.Result completed =
                new EmbeddingDispatchService.Result(
                        EmbeddingAction.SYNC_COMPLETED, "COMPLETED", "bge-m3",
                        UUID.randomUUID(), null, null);
        when(dispatchService.completeAfterCommit(queued))
                .thenReturn(completed);
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document));

        var response = invokeFinish(prepared(EmbeddingPolicy.SYNC, queued));

        assertEquals("RESTORED", response.action());
        org.mockito.Mockito.verify(dispatchService)
                .completeAfterCommit(queued);
    }

    @Test
    void finishThrowsWhenDocumentVanishedBeforeReload() throws Exception {
        when(documentRepository.findById(41L))
                .thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class,
                () -> invokeFinish(prepared(EmbeddingPolicy.ASYNC, null)));
    }
}
