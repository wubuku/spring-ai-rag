package com.springairag.core.service;

import com.springairag.api.dto.ExternalDocumentBatchUpsertResponse;
import com.springairag.api.dto.ExternalDocumentDeleteResponse;
import com.springairag.api.dto.ExternalDocumentUpsertRequest;
import com.springairag.api.dto.ExternalDocumentUpsertResponse;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外部文档批量与委托长尾（Batch 621 建立，Batch 837 改名并收缩）：
 * {@code upsert} / {@code sourceDelete} 在变更层在场时的委派，
 * 以及 {@code batchUpsert} 的空清单/超限拒绝与计数聚合。
 *
 * <p>Batch 837 删掉 legacy 内联写入路径后，墓碑重放、关键词索引协调、
 * 嵌入各臂与 SYNC 派发错误投影全部随 {@code finishUpsert} 一起消失。
 * 剩下的是本服务**自己**的职责：委派边界与批量计数。
 */
class ExternalDocumentBatchDelegateTailTest {

    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver collectionIdentityResolver;
    private EmbeddingDispatchService dispatchService;
    private DocumentMutationService mutationService;
    private DocumentVersionService documentVersionService;
    private RagCollection collection;
    private ExternalDocumentService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        RagCollectionRepository collectionRepository =
                mock(RagCollectionRepository.class);
        RagEmbeddingRepository embeddingRepository =
                mock(RagEmbeddingRepository.class);
        documentVersionService = mock(DocumentVersionService.class);
        DocumentEmbedService documentEmbedService =
                mock(DocumentEmbedService.class);
        EmbeddingProfileProvider embeddingProfileProvider =
                mock(EmbeddingProfileProvider.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        mutationService = mock(DocumentMutationService.class);

        collection = new RagCollection();
        collection.setId(10L);
        collection.setCollectionKey("customer-42:manual:v1");
        collection.setName("Manual");
        collection.setEnabled(true);

        lenient().when(collectionIdentityResolver.requireActive(
                        null, collection.getCollectionKey()))
                .thenReturn(collection);
        lenient().when(collectionIdentityResolver.beginActiveWrite(10L))
                .thenReturn(new CollectionIdentityResolver.ActiveCollectionToken(10L, 0L));
        lenient().when(embeddingProfileProvider.getActiveProfile())
                .thenReturn(new EmbeddingProfile(
                        9L, "profile", "test", "model", "v1",
                        1024, "COSINE", "NONE", true));
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        lenient().when(jdbcTemplate.execute(
                        any(org.springframework.jdbc.core.ConnectionCallback.class)))
                .thenReturn(null);
        lenient().when(collectionRepository.findById(10L))
                .thenReturn(Optional.of(collection));
        lenient().when(documentVersionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    RagDocumentVersion version = new RagDocumentVersion();
                    version.setVersionNumber(4);
                    return version;
                });
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument saved = invocation.getArgument(0);
                    if (saved.getId() == null) {
                        saved.setId(77L);
                    }
                    return saved;
                });

        service = new ExternalDocumentService(documentRepository,
                collectionRepository,
                embeddingRepository,
                embeddingProfileProvider,
                collectionIdentityResolver);
        service.setMutationService(mutationService);
    }

    private ExternalDocumentUpsertResponse upsertResponse(
            String externalId, String action) {
        return new ExternalDocumentUpsertResponse(
                77L, collection.getCollectionKey(), externalId, "rev-9",
                action, true, 1, "NOT_REQUESTED", "profile", false,
                "READY", null, null, null, "NONE", null, null,
                "default", 3L, null);
    }

    /** 该 externalId 抛异常、其余交回 CREATED——用来分派批量里的成败两种条目。 */
    private void stubExternalUpsertFailingOn(String failingExternalId) {
        lenient().when(mutationService.upsertExternal(any()))
                .thenAnswer(invocation -> {
                    ExternalDocumentUpsertRequest request =
                            invocation.getArgument(0);
                    if (failingExternalId.equals(request.getExternalId())) {
                        throw new IllegalArgumentException(
                                "title must not be blank");
                    }
                    return upsertResponse(request.getExternalId(), "CREATED");
                });
    }

    private ExternalDocumentUpsertRequest upsertRequest(
            String externalId, String revision) {
        ExternalDocumentUpsertRequest request =
                new ExternalDocumentUpsertRequest();
        request.setCollectionKey(collection.getCollectionKey());
        request.setExternalId(externalId);
        request.setSourceRevision(revision);
        request.setTitle("Title");
        request.setContent("Content");
        request.setSource("connector://manual");
        request.setDocumentType("text");
        request.setEmbed(true);
        return request;
    }

    @Test
    void sourceDeleteDelegatesToMutationServiceWhenPresent() {
        service.setMutationService(mutationService);
        var delegated = new ExternalDocumentDeleteResponse(
                88L, "kb", "doc-1", "rev-9", "DELETED",
                3, false, null, null, null);
        when(mutationService.tombstoneExternal(
                "kb", "default", "doc-1", "rev-9", null, false))
                .thenReturn(delegated);

        var response = service.sourceDelete("kb", "doc-1", "rev-9", null);

        assertEquals("DELETED", response.action());
        verify(mutationService).tombstoneExternal(
                "kb", "default", "doc-1", "rev-9", null, false);
    }

    @Test
    void batchUpsertRejectsEmptyAndOversizedBatches() {
        assertThrows(IllegalArgumentException.class,
                () -> service.batchUpsert(null));
        assertThrows(IllegalArgumentException.class,
                () -> service.batchUpsert(List.of()));

        List<ExternalDocumentUpsertRequest> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add(upsertRequest("d", "r"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> service.batchUpsert(tooMany));
    }

    @Test
    void batchUpsertCountsCreatedAndPersistenceFailures() {
        stubExternalUpsertFailingOn("bad-1");

        ExternalDocumentBatchUpsertResponse response =
                service.batchUpsert(List.of(
                        upsertRequest("ok-1", "rev-1"),
                        upsertRequest("bad-1", "rev-1")));

        // 计数聚合是本服务自己的职责：成功计 created，抛出的计 persistenceFailed。
        assertEquals(1, response.summary().created());
        assertEquals(1, response.summary().persistenceFailed());
        assertEquals(0, response.summary().unchanged());
        assertEquals(0, response.summary().embeddingFailed());
        assertEquals("CREATED", response.items().get(0).action());
        assertEquals("PERSISTENCE_FAILED", response.items().get(1).action());
    }

    @Test
    void batchUpsertIsolatesFailuresAndPreservesInputOrder() {
        stubExternalUpsertFailingOn("doc-invalid");

        ExternalDocumentBatchUpsertResponse response = service.batchUpsert(
                List.of(upsertRequest("doc-valid", "rev-1"),
                        upsertRequest("doc-invalid", "rev-1")));

        assertEquals(2, response.items().size());
        assertEquals("doc-valid", response.items().get(0).externalId());
        assertEquals("CREATED", response.items().get(0).action());
        assertEquals("doc-invalid", response.items().get(1).externalId());
        assertEquals("PERSISTENCE_FAILED", response.items().get(1).action());
        assertEquals("BAD_REQUEST", response.items().get(1).errorCode());
        assertEquals(1, response.summary().created());
        assertEquals(1, response.summary().persistenceFailed());
    }
}
