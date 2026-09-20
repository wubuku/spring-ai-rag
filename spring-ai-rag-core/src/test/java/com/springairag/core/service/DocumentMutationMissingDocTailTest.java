package com.springairag.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.dto.DocumentSyncRunItemRequest;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentMutationService 文档消失与解析长尾（Batch 544，JaCoCo 驱
 * 动）：createLocal/upsertSyncRunItem 在 finish 阶段文档不存在的
 * DocumentNotFoundException、allocateSourceSequenceForSnapshot 委
 * 托、resolveUpdateCollection 无键 unrestricted 返回 null、local
 * CreateFingerprint 序列化失败包装。
 */
class DocumentMutationMissingDocTailTest {

    private static final long COLLECTION_ID = 30L;

    private RagDocumentRepository documentRepository;
    private DocumentVersionService versionService;
    private JdbcTemplate jdbcTemplate;
    private DocumentMutationService service;
    private ObjectMapper brokenMapper;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        versionService = mock(DocumentVersionService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        brokenMapper = mock(ObjectMapper.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    RagDocumentVersion version = new RagDocumentVersion();
                    version.setVersionNumber(5);
                    return version;
                });
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument saved = invocation.getArgument(0);
                    if (saved.getId() == null) {
                        saved.setId(41L);
                    }
                    saved.setSourceMutationSequence(30L);
                    return saved;
                });
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(
                contains("RETURNING mutation_sequence"),
                eq(Long.class), any(Object[].class)))
                .thenReturn(30L);

        service = new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                mock(CollectionIdentityResolver.class),
                versionService,
                mock(EmbeddingDispatchService.class),
                mock(DocumentEmbedService.class),
                mock(DocumentLifecycleService.class),
                jdbcTemplate,
                new ObjectMapper(),
                new RagProperties(),
                transactionManager);
    }

    private DocumentRequest localRequest() {
        DocumentRequest request =
                new DocumentRequest("Local Doc", "local body");
        request.setSource("connector://local");
        request.setDocumentType("text");
        return request;
    }

    @Test
    void allocateSourceSequenceForSnapshotDelegatesToSequenceAllocator() {
        long sequence = service.allocateSourceSequenceForSnapshot(
                COLLECTION_ID, "catalog");

        assertEquals(30L, sequence);
    }

    @Test
    void createLocalFailsWhenDocumentVanishesBeforeFinish() {
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        // finish 阶段 findById 未打桩 → 默认 Optional.empty。

        var error = assertThrows(
                com.springairag.core.exception.DocumentNotFoundException.class,
                () -> service.createLocal(
                        localRequest(), COLLECTION_ID,
                        EmbeddingPolicy.SKIP, "LOCAL_IMPORT"));

        assertTrue(error.getMessage().contains("id=41"));
    }

    @Test
    void syncRunItemFinishFailsWhenDocumentVanishes() {
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        var request = new DocumentSyncRunItemRequest(
                com.springairag.api.enums.DocumentSyncDocumentKind.TEXT,
                "article-new", "r2", "Article New", "Fresh body",
                null, null, "cms", "text", Map.of(),
                EmbeddingPolicy.SKIP);

        var error = assertThrows(
                com.springairag.core.exception.DocumentNotFoundException.class,
                () -> service.upsertSyncRunItem(
                        COLLECTION_ID, "catalog", "catalog",
                        request, 20L));

        assertTrue(error.getMessage().contains("id=41"));
    }

    @Test
    void resolveUpdateCollectionReturnsNullWithoutKeyAndRestriction()
            throws Exception {
        var method = DocumentMutationService.class.getDeclaredMethod(
                "resolveUpdateCollection", RagDocument.class, String.class);
        method.setAccessible(true);
        RagDocument doc = new RagDocument();
        doc.setId(7L);

        Object resolved = method.invoke(service, doc, (String) null);

        assertNull(resolved);
    }

    @Test
    void localCreateFingerprintWrapsSerializationFailure() throws Exception {
        var broken = new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                mock(CollectionIdentityResolver.class),
                versionService,
                mock(EmbeddingDispatchService.class),
                mock(DocumentEmbedService.class),
                mock(DocumentLifecycleService.class),
                jdbcTemplate,
                brokenMapper,
                new RagProperties(),
                mock(PlatformTransactionManager.class));
        org.mockito.Mockito.when(brokenMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("jackson down") {});

        var method = DocumentMutationService.class.getDeclaredMethod(
                "localCreateFingerprint", DocumentRequest.class,
                Long.class, EmbeddingPolicy.class, boolean.class,
                String.class,
                com.fasterxml.jackson.databind.JsonNode.class,
                Boolean.class);
        method.setAccessible(true);

        var error = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(broken, localRequest(), COLLECTION_ID,
                        EmbeddingPolicy.SKIP, false, null, null, null));

        assertTrue(error.getCause() instanceof IllegalArgumentException);
        assertTrue(error.getCause().getMessage()
                .contains("Cannot canonicalize idempotent document request"));
    }

}
