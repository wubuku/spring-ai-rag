package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.dto.JsonRecordUpsertResponse;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JsonRecordService 校验/变更原因/嵌入结果长尾（Batch 618，JaCoCo
 * 驱动）：validateRequest 对 null 请求与非法 collectionId 的拒绝、
 * buildUpdateReason 三分支投影、SKIP 策略下关键词索引 markNotRequested、
 * SYNC 嵌入的 CACHED 与 FAILED 投影。
 */
class JsonRecordValidationEmbedTailTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RagDocumentRepository documentRepository;
    private DocumentEmbedService documentEmbedService;
    private KeywordIndexPersistenceService keywordIndexPersistenceService;
    private RagProperties properties;
    private JsonRecordService service;
    private RagDocument existing;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        keywordIndexPersistenceService = mock(KeywordIndexPersistenceService.class);
        properties = new RagProperties();
        var versionService = mock(DocumentVersionService.class);
        lenient().when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    var version = new com.springairag.core.entity.RagDocumentVersion();
                    version.setVersionNumber(1);
                    return version;
                });
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument value = invocation.getArgument(0);
                    if (value.getId() == null) {
                        value.setId(41L);
                    }
                    return value;
                });
        var profileProvider = mock(EmbeddingProfileProvider.class);
        lenient().when(profileProvider.getActiveProfile())
                .thenReturn(new com.springairag.core.config.EmbeddingProfile(
                        9L, "profile", "test", "model", "v1",
                        1024, "COSINE", "NONE", true));
        var resolver = mock(CollectionIdentityResolver.class);
        lenient().when(resolver.beginActiveWrite(7L))
                .thenReturn(new CollectionIdentityResolver.ActiveCollectionToken(7L, 0L));

        service = new JsonRecordService(
                documentRepository,
                versionService,
                documentEmbedService,
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                profileProvider,
                resolver,
                properties,
                MAPPER,
                mock(JdbcTemplate.class),
                null,
                null);
        service.setKeywordIndexPersistenceService(keywordIndexPersistenceService);
        service.setDispatchService(mock(EmbeddingDispatchService.class));

        existing = existingDocument();
        lenient().when(documentRepository
                .findByCollectionIdAndDocumentTypeAndExternalId(
                        eq(7L), eq(RagDocument.JSON_RECORD), eq("rec-1")))
                .thenReturn(Optional.of(existing));
    }

    private RagDocument existingDocument() {
        RagDocument value = new RagDocument();
        value.setId(41L);
        value.setCollectionId(7L);
        value.setExternalId("rec-1");
        value.setDocumentType(RagDocument.JSON_RECORD);
        value.setTitle("Record");
        value.setContent("text");
        value.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("text"));
        value.setSourceRevision("rev-1");
        value.setEnabled(Boolean.TRUE);
        try {
            value.setJsonbPayload(MAPPER.readTree("{\"k\":1}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return value;
    }

    private JsonRecordUpsertRequest request() {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setCollectionId(7L);
        request.setExternalId("rec-1");
        request.setTitle("Record");
        request.setRetrievalText("text");
        request.setSourceRevision("rev-1");
        try {
            request.setJsonbPayload(MAPPER.readTree("{\"k\":1}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return request;
    }

    @Test
    void upsertRejectsNullRequestAndBlankCollection() {
        IllegalArgumentException nullRequest = assertThrows(
                IllegalArgumentException.class, () -> service.upsert(null));
        assertEquals("request must not be null", nullRequest.getMessage());

        JsonRecordUpsertRequest noCollection = request();
        noCollection.setCollectionId(null);
        IllegalArgumentException noId = assertThrows(
                IllegalArgumentException.class, () -> service.upsert(noCollection));
        assertEquals("collectionKey or collectionId must be provided",
                noId.getMessage());
    }

    @Test
    void buildUpdateReasonProjectsChangedFields() throws Exception {
        Method reason = JsonRecordService.class.getDeclaredMethod(
                "changedFields", boolean.class, boolean.class,
                RagDocument.class);
        reason.setAccessible(true);

        assertEquals("JSON structured record updated: retrievalText",
                reason.invoke(service, true, false, existing));
        assertEquals("JSON structured record updated: jsonbPayload",
                reason.invoke(service, false, true, existing));
        assertEquals("JSON structured record updated: metadata/title/source",
                reason.invoke(service, false, false, existing));
    }

    @Test
    void skipUpsertWithContentChangeMarksKeywordIndexNotRequested() {
        JsonRecordUpsertRequest request = request();
        request.setRetrievalText("new text");
        request.setEmbeddingPolicy(EmbeddingPolicy.SKIP);
        request.setEmbed(false);

        var response = service.upsert(request);

        assertEquals("UPDATED", response.action());
        verify(keywordIndexPersistenceService).markNotRequested(
                any(RagDocument.class));
    }

    @Test
    void unchangedUpsertWithFreshEmbeddingReportsCached() {
        request();
        when(documentEmbedService.hasFreshEmbedding(existing))
                .thenReturn(true);
        JsonRecordUpsertRequest request = request();
        request.setEmbed(true);

        JsonRecordUpsertResponse response = service.upsert(request);

        assertEquals("UNCHANGED", response.action());
        assertEquals("CACHED", response.embeddingStatus());
        assertNull(response.error());
    }

    private static void assertNull(Object value) {
        org.junit.jupiter.api.Assertions.assertNull(value);
    }

    @Test
    void syncUpsertWithEmbedderFailureReportsFailed() {
        JsonRecordUpsertRequest request = request();
        request.setRetrievalText("new text");
        request.setEmbed(true);
        when(documentEmbedService.embedDocument(any(), anyBoolean()))
                .thenThrow(new IllegalStateException("embedder down"));

        JsonRecordUpsertResponse response = service.upsert(request);

        assertEquals("FAILED", response.embeddingStatus());
        assertTrue(response.error() != null
                && response.error().contains("embedder down"));
    }
}
