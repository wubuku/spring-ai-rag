package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JsonRecordService 长尾补充（Batch 709，JaCoCo 驱动）：嵌入结果
 * 的 CACHED / COMPLETED / FAILED 三臂、请求集合解析对非唯一结果
 * 的拒绝、getDetail 在生命周期服务缺省时响应携带 null 生命周期、
 * payloadContains 过滤校验入口。
 */
class JsonRecordServiceEmbeddingOutcomeTailTest {

    private RagDocumentRepository documentRepository;
    private DocumentEmbedService documentEmbedService;
    private CollectionIdentityResolver collectionIdentityResolver;
    private EmbeddingProfileProvider profileProvider;
    private JsonRecordService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile())
                .thenReturn(new com.springairag.core.config.EmbeddingProfile(
                        1L, "bge-m3", "vendor", "bge-m3", "rev",
                        1024, "COSINE", "PROVIDER_DEFAULT", true));
        service = new JsonRecordService(
                documentRepository,
                mock(DocumentVersionService.class),
                documentEmbedService,
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                profileProvider,
                collectionIdentityResolver,
                new RagProperties(),
                new ObjectMapper(),
                mock(JdbcTemplate.class),
                null,
                null);
    }

    private JsonRecordUpsertRequest request(Long collectionId,
                                            String collectionKey) {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setCollectionId(collectionId);
        request.setCollectionKey(collectionKey);
        request.setExternalId("cms:article:1");
        request.setTitle("Record");
        return request;
    }

    private void invokeResolver(JsonRecordUpsertRequest request)
            throws Throwable {
        Method method = JsonRecordService.class.getDeclaredMethod(
                "resolveRequestCollection", JsonRecordUpsertRequest.class);
        method.setAccessible(true);
        try {
            method.invoke(service, request);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void resolverRejectsAmbiguousCollectionResolution() {
        when(collectionIdentityResolver.resolveActiveIds(any(), any()))
                .thenReturn(List.of(1L, 2L));

        var error = assertThrows(IllegalArgumentException.class,
                () -> invokeResolver(request(null, "kb-1")));
        assertEquals("Exactly one Collection must be provided",
                error.getMessage());
    }

    private Object persistedRecord(PersistedRecordShape shape)
            throws Exception {
        Class<?> persistedType = Class.forName(
                "com.springairag.core.service.JsonRecordService$PersistedRecord");
        Object persisted = mock(persistedType);
        RagDocument document = new RagDocument();
        document.setId(31L);
        when(persistedType.getMethod("action").invoke(persisted))
                .thenReturn(shape.action());
        when(persistedType.getMethod("contentChanged").invoke(persisted))
                .thenReturn(shape.contentChanged());
        when(persistedType.getMethod("document").invoke(persisted))
                .thenReturn(document);
        return persisted;
    }

    private Object invokeEmbedIfRequested(
            PersistedRecordShape shape, boolean embed) throws Exception {
        Class<?> persistedType = Class.forName(
                "com.springairag.core.service.JsonRecordService$PersistedRecord");
        Method method = JsonRecordService.class.getDeclaredMethod(
                "embedIfRequested", persistedType, boolean.class);
        method.setAccessible(true);
        return method.invoke(service, persistedRecord(shape), embed);
    }

    private String outcomeStatus(Object outcome) throws Exception {
        return (String) outcome.getClass().getMethod("status").invoke(outcome);
    }

    private String outcomeAction(Object outcome) throws Exception {
        return (String) outcome.getClass().getMethod("action").invoke(outcome);
    }

    private String outcomeError(Object outcome) throws Exception {
        Object error = outcome.getClass().getMethod("error").invoke(outcome);
        return error == null ? null : String.valueOf(error);
    }

    private record PersistedRecordShape(String action, boolean contentChanged) {
    }

    @Test
    void embedIfRequestedReturnsCachedWhenFreshVectorsExist()
            throws Exception {
        when(documentEmbedService.hasFreshEmbedding(any(RagDocument.class)))
                .thenReturn(true);

        Object outcome = invokeEmbedIfRequested(
                new PersistedRecordShape("UPDATED", false), true);

        assertEquals("CACHED", outcomeStatus(outcome));
    }

    @Test
    void embedIfRequestedMapsCompletedStatus() throws Exception {
        when(documentEmbedService.hasFreshEmbedding(any(RagDocument.class)))
                .thenReturn(false);
        when(documentEmbedService.embedDocument(anyLong(), anyBoolean()))
                .thenReturn(Map.of("status", "COMPLETED",
                        "embeddingProfileKey", "bge-m3"));

        Object outcome = invokeEmbedIfRequested(
                new PersistedRecordShape("CREATED", true), true);

        assertEquals("COMPLETED", outcomeStatus(outcome));
    }

    @Test
    void embedIfRequestedMapsFailuresToFailedOutcome() throws Exception {
        when(documentEmbedService.hasFreshEmbedding(any(RagDocument.class)))
                .thenReturn(false);
        when(documentEmbedService.embedDocument(anyLong(), anyBoolean()))
                .thenThrow(new IllegalStateException("profile offline"));

        Object outcome = invokeEmbedIfRequested(
                new PersistedRecordShape("CREATED", true), true);

        assertEquals("FAILED", outcomeStatus(outcome));
        assertNotNull(outcomeError(outcome));
    }

    @Test
    void getDetailToleratesMissingLifecycleService() {
        RagDocument doc = new RagDocument();
        doc.setId(31L);
        doc.setDocumentType(RagDocument.JSON_RECORD);
        doc.setCollectionId(7L);
        doc.setExternalId("cms:article:1");
        when(documentRepository.findById(31L)).thenReturn(Optional.of(doc));
        when(collectionIdentityResolver.mapKeys(anyList()))
                .thenReturn(Map.of(7L, "kb-7"));

        var detail = service.getDetail(31L);

        assertEquals("cms:article:1", detail.externalId());
        assertNull(detail.lifecycle());
    }
}
