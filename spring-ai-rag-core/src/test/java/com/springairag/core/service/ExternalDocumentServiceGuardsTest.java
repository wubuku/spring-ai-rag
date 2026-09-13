package com.springairag.core.service;

import com.springairag.api.dto.ExternalDocumentBatchUpsertResponse;
import com.springairag.api.dto.ExternalDocumentUpsertRequest;
import com.springairag.api.dto.ExternalDocumentUpsertResponse;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExternalDocumentService 守卫与委派（Batch 361）：batchUpsert
 * 参数守卫与汇总计数、mutationService 委派、ASYNC 策略在任务仓储
 * 缺失时的拒绝、双参按身份查询默认 namespace + 退役地址校验、
 * 事务管理器缺省构造。
 */
class ExternalDocumentServiceGuardsTest {

    private static final String KEY = "kb";

    private RagDocumentRepository documentRepository;
    private RagCollectionRepository collectionRepository;
    private CollectionIdentityResolver collectionIdentityResolver;
    private EmbeddingProfileProvider profileProvider;
    private ExternalDocumentService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        collectionRepository = mock(RagCollectionRepository.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        lenient().when(profileProvider.getActiveProfile()).thenReturn(profile());
        lenient().when(collectionIdentityResolver.requireActive(null, KEY))
                .thenReturn(collection());
        lenient().when(collectionIdentityResolver.beginActiveWrite(10L))
                .thenReturn(new CollectionIdentityResolver.ActiveCollectionToken(10L, 0L));
        lenient().when(collectionRepository.findById(10L))
                .thenReturn(Optional.of(collection()));
        service = new ExternalDocumentService(
                documentRepository,
                collectionRepository,
                mock(RagEmbeddingRepository.class),
                mock(DocumentVersionService.class),
                mock(DocumentEmbedService.class),
                profileProvider,
                collectionIdentityResolver,
                mock(JdbcTemplate.class),
                mock(PlatformTransactionManager.class));
    }

    private EmbeddingProfile profile() {
        return new EmbeddingProfile(
                7L, "test-profile", "test", "test-model", "v1",
                1024, "COSINE", "NONE", true);
    }

    private RagCollection collection() {
        RagCollection value = new RagCollection();
        value.setId(10L);
        value.setCollectionKey(KEY);
        value.setName("KB");
        value.setEnabled(true);
        return value;
    }

    private ExternalDocumentUpsertRequest request(String externalId, String content) {
        ExternalDocumentUpsertRequest request = new ExternalDocumentUpsertRequest();
        request.setCollectionKey(KEY);
        request.setExternalId(externalId);
        request.setSourceRevision("rev-1");
        request.setTitle("Title " + externalId);
        request.setContent(content);
        request.setSource("connector://manual");
        return request;
    }

    private ExternalDocumentUpsertResponse response(String action, String embeddingStatus) {
        return new ExternalDocumentUpsertResponse(
                41L, KEY, "doc-1", "rev-1", action, true, 1,
                embeddingStatus, "test-profile", false, "COMPLETED",
                null, null, null, null, null, null);
    }

    /** 事务管理器为 null 也能构造（transactionTemplate 置空）。 */
    private ExternalDocumentService serviceWithoutTransactionManager() {
        return new ExternalDocumentService(
                documentRepository,
                collectionRepository,
                mock(RagEmbeddingRepository.class),
                mock(DocumentVersionService.class),
                mock(DocumentEmbedService.class),
                profileProvider,
                collectionIdentityResolver,
                mock(JdbcTemplate.class),
                null);
    }

    @Test
    void batchUpsertRejectsEmptyItems() {
        ExternalDocumentService guardService = serviceWithoutTransactionManager();
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> guardService.batchUpsert(List.of()));
        assertEquals("items must not be empty", error.getMessage());

        IllegalArgumentException nullError = assertThrows(
                IllegalArgumentException.class,
                () -> guardService.batchUpsert(null));
        assertEquals("items must not be empty", nullError.getMessage());
    }

    @Test
    void batchUpsertRejectsMoreThanFiftyItems() {
        ExternalDocumentService guardService = serviceWithoutTransactionManager();
        List<ExternalDocumentUpsertRequest> items = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            items.add(request("doc-" + i, "content"));
        }
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> guardService.batchUpsert(items));
        assertEquals("External document batch is limited to 50 items",
                error.getMessage());
    }

    @Test
    void batchUpsertRejectsOversizedBatchContent() {
        ExternalDocumentService guardService = serviceWithoutTransactionManager();
        List<ExternalDocumentUpsertRequest> items = List.of(
                request("doc-1", "x".repeat(5_000_001)));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> guardService.batchUpsert(items));
        assertEquals("External document batch content exceeds 5,000,000 characters",
                error.getMessage());
    }

    @Test
    void upsertDelegatesToMutationServiceWhenPresent() {
        DocumentMutationService mutationService = mock(DocumentMutationService.class);
        service.setMutationService(mutationService);
        ExternalDocumentUpsertRequest request = request("doc-1", "content");
        ExternalDocumentUpsertResponse canned = response("CREATED", "COMPLETED");
        when(mutationService.upsertExternal(request)).thenReturn(canned);

        assertSame(canned, service.upsert(request));
        verify(mutationService).upsertExternal(request);
    }

    @Test
    void upsertRejectsAsyncPolicyWhenJobDispatchMissing() {
        ExternalDocumentUpsertRequest request = request("doc-1", "content");
        request.setEmbeddingPolicy(EmbeddingPolicy.ASYNC);

        RagException error = assertThrows(RagException.class,
                () -> service.upsert(request));
        assertEquals(ErrorCode.EMBEDDING_JOBS_DISABLED, error.getErrorCodeEnum());
    }

    @Test
    void batchUpsertAggregatesSummaryCounts() {
        DocumentMutationService mutationService = mock(DocumentMutationService.class);
        service.setMutationService(mutationService);
        ExternalDocumentUpsertRequest created = request("doc-1", "c1");
        ExternalDocumentUpsertRequest updated = request("doc-2", "c2");
        ExternalDocumentUpsertRequest unchanged = request("doc-3", "c3");
        ExternalDocumentUpsertRequest embedFailed = request("doc-4", "c4");
        ExternalDocumentUpsertRequest broken = request("doc-5", "c5");
        when(mutationService.upsertExternal(created))
                .thenReturn(response("CREATED", "COMPLETED"));
        when(mutationService.upsertExternal(updated))
                .thenReturn(response("UPDATED", "COMPLETED"));
        when(mutationService.upsertExternal(unchanged))
                .thenReturn(response("UNCHANGED", "COMPLETED"));
        when(mutationService.upsertExternal(embedFailed))
                .thenReturn(response("CREATED", "FAILED"));
        when(mutationService.upsertExternal(broken))
                .thenThrow(new IllegalStateException("persist broken"));

        ExternalDocumentBatchUpsertResponse batch = service.batchUpsert(
                List.of(created, updated, unchanged, embedFailed, broken));

        assertEquals(5, batch.summary().total());
        assertEquals(2, batch.summary().created());
        assertEquals(1, batch.summary().updated());
        assertEquals(1, batch.summary().unchanged());
        assertEquals(1, batch.summary().persistenceFailed());
        assertEquals(1, batch.summary().embeddingFailed());
    }

    @Test
    void getByExternalIdentityDefaultsNamespaceAndChecksRetirement() {
        var retirementService = mock(ExternalAddressRetirementService.class);
        service.setAddressRetirementService(retirementService);
        RagDocument document = new RagDocument();
        document.setId(41L);
        document.setCollectionId(10L);
        document.setSourceNamespace("default");
        document.setExternalId("doc-1");
        document.setDocumentType("text");
        document.setTitle("Doc");
        document.setEnabled(true);
        when(documentRepository.findByCollectionIdAndSourceNamespaceAndExternalId(
                10L, "default", "doc-1")).thenReturn(Optional.of(document));

        var detail = service.getByExternalIdentity(KEY, "doc-1");

        assertNotNull(detail);
        verify(retirementService).requireNotRetired(10L, "default", "doc-1");
    }
}
