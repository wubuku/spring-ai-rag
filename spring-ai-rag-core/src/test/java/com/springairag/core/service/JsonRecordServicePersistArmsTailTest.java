package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JsonRecordService 长尾补充（Batch 736，JaCoCo 驱动）：getDetail
 * 携带生命周期与集合键（390/497）、1 参 persist 便捷入口（550）、
 * 5 参 persist 的 originalFilename/enabledOverride 变更臂
 * （669-673）、payload 序列化失败包装（899-900）、失败响应双臂
 * （983-985）。
 */
class JsonRecordServicePersistArmsTailTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver resolver;
    private DocumentVersionService versionService;
    private DocumentLifecycleService lifecycleService;
    private com.springairag.core.config.EmbeddingProfileProvider profileProvider;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        resolver = mock(CollectionIdentityResolver.class);
        versionService = mock(DocumentVersionService.class);
        lifecycleService = mock(DocumentLifecycleService.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile())
                .thenReturn(new com.springairag.core.config.EmbeddingProfile(
                        9L, "profile", "test", "model", "v1",
                        1024, "COSINE", "NONE", true));
    }

    private JsonRecordService serviceWithLifecycle(
            DocumentLifecycleService lifecycle) {
        JsonRecordService service = new JsonRecordService(
                documentRepository,
                versionService,
                mock(DocumentEmbedService.class),
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                profileProvider,
                resolver,
                new RagProperties(),
                MAPPER,
                mock(JdbcTemplate.class),
                null,
                null);
        service.setLifecycleService(lifecycle);
        return service;
    }

    private JsonRecordUpsertRequest request() {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setCollectionId(7L);
        request.setExternalId("rec-1");
        request.setTitle("Record");
        request.setRetrievalText("text");
        request.setSource("crm");
        try {
            request.setJsonbPayload(MAPPER.readTree("{\"k\":1}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return request;
    }

    private RagDocument jsonDoc() {
        RagDocument doc = new RagDocument();
        doc.setId(41L);
        doc.setCollectionId(7L);
        doc.setDocumentType(RagDocument.JSON_RECORD);
        doc.setExternalId("rec-1");
        doc.setTitle("Record");
        doc.setContent("text");
        doc.setSourceNamespace("crm");
        return doc;
    }

    @Test
    void getByExternalIdentityCarriesLifecycleAndCollectionKeys() {
        when(documentRepository.findByCollectionIdAndSourceNamespaceAndDocumentTypeAndExternalId(
                7L, "crm", RagDocument.JSON_RECORD, "rec-1"))
                .thenReturn(Optional.of(jsonDoc()));
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(jsonDoc()));
        when(resolver.resolveActiveIds(
                org.mockito.ArgumentMatchers.isNull(), any()))
                .thenReturn(List.of(7L));
        when(resolver.mapKeys(List.of(7L))).thenReturn(Map.of(7L, "kb-7"));
        when(versionService.getLatestVersion(41L))
                .thenReturn(Optional.<RagDocumentVersion>empty());
        when(lifecycleService.read(any(RagDocument.class)))
                .thenReturn(new com.springairag.api.dto.DocumentLifecycleResponse(
                        "READY", null, null, null, null,
                        null, null, null, false));

        var detail = serviceWithLifecycle(lifecycleService)
                .getByExternalIdentity("kb-7", "crm", "rec-1");

        Assertions.assertNotNull(detail);
        Assertions.assertEquals("kb-7", detail.collectionKey());
    }

    @Test
    void legacyOneArgPersistCreatesRecord() throws Exception {
        when(documentRepository
                .findByCollectionIdAndDocumentTypeAndExternalId(
                        7L, RagDocument.JSON_RECORD, "rec-1"))
                .thenReturn(Optional.empty());
        when(resolver.beginActiveWrite(7L))
                .thenReturn(new CollectionIdentityResolver.ActiveCollectionToken(7L, 0L));
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument doc = invocation.getArgument(0);
                    doc.setId(41L);
                    return doc;
                });
        when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    var version = new RagDocumentVersion();
                    version.setVersionNumber(1);
                    return version;
                });

        Method persist = JsonRecordService.class.getDeclaredMethod(
                "persist", JsonRecordUpsertRequest.class);
        persist.setAccessible(true);
        Object persisted = persist.invoke(
                serviceWithLifecycle(null), request());

        Assertions.assertNotNull(persisted);
    }

    @Test
    void persistFiveArgDetectsChangedOriginalFilenameAndEnabledOverride()
            throws Exception {
        RagDocument existing = requestShapedDoc();
        existing.setOriginalFilename("old.pdf");
        existing.setEnabled(Boolean.TRUE);
        when(documentRepository
                .findByCollectionIdAndDocumentTypeAndExternalId(
                        7L, RagDocument.JSON_RECORD, "rec-1"))
                .thenReturn(Optional.of(existing));
        when(resolver.beginActiveWrite(7L))
                .thenReturn(new CollectionIdentityResolver.ActiveCollectionToken(7L, 0L));
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    var version = new RagDocumentVersion();
                    version.setVersionNumber(2);
                    return version;
                });

        Method persist = JsonRecordService.class.getDeclaredMethod(
                "persist", JsonRecordUpsertRequest.class, String.class,
                Boolean.class, EmbeddingPolicy.class,
                com.springairag.core.embeddingjob.EmbeddingDispatchService.Result[].class);
        persist.setAccessible(true);
        Object persisted = persist.invoke(
                serviceWithLifecycle(null), request(), "new-name.pdf",
                Boolean.FALSE, EmbeddingPolicy.SKIP, (Object) null);

        Assertions.assertNotNull(persisted);
        Assertions.assertEquals("new-name.pdf", existing.getOriginalFilename());
        Assertions.assertEquals(Boolean.FALSE, existing.getEnabled());
    }

    private RagDocument requestShapedDoc() {
        RagDocument doc = new RagDocument();
        doc.setId(41L);
        doc.setCollectionId(7L);
        doc.setDocumentType(RagDocument.JSON_RECORD);
        doc.setExternalId("rec-1");
        doc.setTitle("Old Title");
        doc.setContent("text");
        doc.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("text"));
        doc.setSource("crm");
        doc.setSourceNamespace("crm");
        doc.setSourceRevision("rev-1");
        doc.setSourceDeletedAt(null);
        doc.setDeletionOrigin(null);
        doc.setReconciliationTombstoneRunId(null);
        doc.setDisabledAt(null);
        doc.setProcessingStatus("COMPLETED");
        doc.setEnabled(Boolean.TRUE);
        return doc;
    }

    // ── 899-900 / 983-985 ────────────────────────────────────────

    @Test
    void serializePayloadFailureWrapsAsIllegalArgument() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper failingMapper =
                mock(com.fasterxml.jackson.databind.ObjectMapper.class);
        when(failingMapper.writeValueAsBytes(any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException(
                        "boom") {
                });
        JsonRecordService service = new JsonRecordService(
                documentRepository,
                versionService,
                mock(DocumentEmbedService.class),
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                profileProvider,
                resolver,
                new RagProperties(),
                failingMapper,
                mock(JdbcTemplate.class),
                null,
                null);
        Method method = JsonRecordService.class.getDeclaredMethod(
                "serializePayload",
                com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);

        assertThrows(IllegalArgumentException.class,
                () -> {
                    try {
                        method.invoke(service, MAPPER.nullNode());
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedResponseHandlesNullAndNonNullRequests() throws Exception {
        JsonRecordService service = serviceWithLifecycle(null);
        Method method = JsonRecordService.class.getDeclaredMethod(
                "failedResponse", JsonRecordUpsertRequest.class,
                RuntimeException.class);
        method.setAccessible(true);
        when(resolver.mapKeys(any())).thenReturn(Map.of());
        when(resolver.resolveActiveIds(any(), any())).thenReturn(List.of());

        var withRequest = (com.springairag.api.dto.JsonRecordUpsertResponse)
                method.invoke(service, request(),
                        new IllegalStateException("x"));
        var withoutRequest = (com.springairag.api.dto.JsonRecordUpsertResponse)
                method.invoke(service, (Object) null,
                        new IllegalStateException("x"));

        Assertions.assertEquals("FAILED", withRequest.action());
        Assertions.assertEquals("FAILED", withoutRequest.action());
        Assertions.assertNull(withoutRequest.externalId());
    }
}
