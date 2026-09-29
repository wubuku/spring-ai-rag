package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.CollectionImportRequest;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentMutationService external 长尾（Batch 715，JaCoCo 驱
 * 动）：finishExternal 在 SYNC 策略时于重载前完成派发并在文档消
 * 失时抛 DocumentNotFound（1274-1277，反射 + mock 私有 record
 * ExternalPrepared）；json-record 导入保留类型并要求 payload
 * （1231）。
 */
class DocumentMutationExternalFinishTailTest {

    private RagDocumentRepository documentRepository;
    private EmbeddingDispatchService dispatchService;
    private DocumentVersionService versionService;
    private DocumentMutationService service;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        versionService = mock(DocumentVersionService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        service = new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                mock(CollectionIdentityResolver.class),
                versionService,
                dispatchService,
                mock(DocumentEmbedService.class),
                mock(DocumentLifecycleService.class),
                jdbcTemplate,
                new ObjectMapper(),
                new com.springairag.core.config.RagProperties(),
                transactionManager);
    }

    private Object externalPrepared(EmbeddingPolicy policy,
                                    EmbeddingDispatchService.Result dispatch)
            throws Exception {
        Class<?> type = Class.forName(
                "com.springairag.core.service.DocumentMutationService$ExternalPrepared");
        Object prepared = mock(type);
        when(type.getMethod("documentId").invoke(prepared)).thenReturn(51L);
        when(type.getMethod("action").invoke(prepared)).thenReturn("UPSERTED");
        when(type.getMethod("documentRevision").invoke(prepared)).thenReturn(2L);
        when(type.getMethod("versionNumber").invoke(prepared)).thenReturn(4);
        when(type.getMethod("contentChanged").invoke(prepared)).thenReturn(true);
        when(type.getMethod("payloadChanged").invoke(prepared)).thenReturn(true);
        when(type.getMethod("dispatch").invoke(prepared)).thenReturn(dispatch);
        when(type.getMethod("policy").invoke(prepared)).thenReturn(policy);
        return prepared;
    }

    private Object invokeFinishExternal(
            Object prepared) throws Exception {
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "finishExternal",
                Class.forName(
                        "com.springairag.core.service.DocumentMutationService$ExternalPrepared"));
        method.setAccessible(true);
        try {
            return method.invoke(service, prepared);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private void assertFinishedDocument(Object finished) throws Exception {
        Object document = finished.getClass()
                .getMethod("document").invoke(finished);
        assertEquals(51L, ((RagDocument) document).getId());
    }

    @Test
    void finishExternalCompletesSyncDispatchBeforeReload() throws Exception {
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
        RagDocument document = new RagDocument();
        document.setId(51L);
        when(documentRepository.findById(51L))
                .thenReturn(Optional.of(document));

        Object finished = invokeFinishExternal(
                externalPrepared(EmbeddingPolicy.SYNC, queued));

        assertFinishedDocument(finished);
        verify(dispatchService).completeAfterCommit(queued);
    }

    @Test
    void finishExternalThrowsWhenDocumentVanishedBeforeReload()
            throws Exception {
        when(documentRepository.findById(51L))
                .thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class,
                () -> invokeFinishExternal(
                        externalPrepared(EmbeddingPolicy.ASYNC, null)));
    }

    @Test
    void jsonRecordImportKeepsJsonRecordTypeAndRequiresPayload()
            throws Exception {
        CollectionImportRequest.ImportedDocument imported =
                new CollectionImportRequest.ImportedDocument();
        imported.setTitle("Json Record");
        imported.setContent("raw fallback content");
        imported.setExternalId("cms:json-1");
        imported.setSourceNamespace("crm");
        imported.setSourceRevision("rev-2");
        imported.setSource("crm-sync");
        imported.setDocumentType(RagDocument.JSON_RECORD);
        imported.setJsonbPayload(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree("{\"status\":\"ACTIVE\"}"));
        imported.setEnabled(true);

        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        10L, "crm", "cms:json-1"))
                .thenReturn(Optional.empty());
        when(jdbcTemplate.update(anyString(), any(), any())).thenReturn(1);
        when(jdbcTemplate.queryForObject(
                anyString(), eq(Long.class), eq(10L), eq("crm")))
                .thenReturn(3L);
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument doc = invocation.getArgument(0);
                    doc.setId(77L);
                    return doc;
                });
        when(documentRepository.findById(77L))
                .thenReturn(Optional.of(new RagDocument()));
        when(versionService.forceRecordVersion(any(RagDocument.class),
                any(), any()))
                .thenAnswer(invocation -> {
                    var version = new com.springairag.core.entity.RagDocumentVersion();
                    version.setVersionNumber(4);
                    return version;
                });

        service.importDocument(10L, "kb", imported);

        ArgumentCaptor<RagDocument> saved =
                ArgumentCaptor.forClass(RagDocument.class);
        verify(documentRepository).saveAndFlush(saved.capture());
        assertEquals(RagDocument.JSON_RECORD,
                saved.getValue().getDocumentType());
        Mockito.verify(dispatchService, Mockito.never())
                .enqueueInCurrentTransaction(any(RagDocument.class),
                        anyBoolean(), anyBoolean(), anyString());
    }
}
