package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentLifecycleResponse;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.dto.JsonRecordUpsertResponse;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.entity.RagDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JsonRecordService 变更管道响应映射：upsert 委托
 * DocumentMutationService.upsertJsonRecord 后，JsonMutationResult
 * （含/不含派发结果）到 JsonRecordUpsertResponse 全字段装配。
 */
@ExtendWith(MockitoExtension.class)
class JsonRecordUpsertResponseMappingTest {

    private static final long COLLECTION_ID = 10L;

    @Mock com.springairag.core.repository.RagDocumentRepository documentRepository;
    @Mock DocumentVersionService documentVersionService;
    @Mock DocumentEmbedService documentEmbedService;
    @Mock HybridRetrieverService hybridRetrieverService;
    @Mock ReRankingService reRankingService;
    @Mock EmbeddingProfileProvider embeddingProfileProvider;
    @Mock CollectionIdentityResolver collectionIdentityResolver;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock DocumentMutationService mutationService;

    private JsonRecordService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lenient().when(embeddingProfileProvider.getActiveProfile())
                .thenReturn(new EmbeddingProfile(7L, "bge-m3", "vendor",
                        "bge-m3", "rev-1", 1024, "cosine", "normalize", true));
        lenient().when(collectionIdentityResolver.mapKeys(any()))
                .thenReturn(Map.of(COLLECTION_ID, "collection-10"));
        lenient().when(collectionIdentityResolver.resolveActiveIds(
                        isNull(), eq(List.of("records:v1"))))
                .thenReturn(List.of(COLLECTION_ID));
        service = new JsonRecordService(
                documentRepository,
                documentVersionService,
                documentEmbedService,
                hybridRetrieverService,
                reRankingService,
                embeddingProfileProvider,
                collectionIdentityResolver,
                new RagProperties(),
                new ObjectMapper(),
                jdbcTemplate,
                null);
        service.setMutationService(mutationService);
    }

    private JsonRecordUpsertRequest request() {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setCollectionKey("records:v1");
        request.setExternalId("record-1");
        request.setTitle("Record record-1");
        request.setRetrievalText("Customer record.");
        try {
            request.setJsonbPayload(new ObjectMapper().readTree("{\"name\":\"one\"}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        request.setEmbed(false);
        return request;
    }

    private RagDocument document() {
        RagDocument document = new RagDocument();
        document.setId(41L);
        document.setCollectionId(COLLECTION_ID);
        document.setExternalId("record-1");
        document.setSourceNamespace("default");
        document.setSourceRevision("rev-9");
        document.setDocumentRevision(3L);
        return document;
    }

    private DocumentLifecycleResponse lifecycle() {
        return new DocumentLifecycleResponse(
                "LIVE", "SEARCHABLE", "COMPLETED", "COMPLETED", "bge-m3",
                UUID.randomUUID(), null, null, false);
    }

    @Test
    void upsertDelegatesToMutationServiceAndMapsFullResponse() {
        JsonRecordUpsertRequest request = request();
        RagDocument document = document();
        UUID jobId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        EmbeddingDispatchService.Result dispatch = new EmbeddingDispatchService.Result(
                EmbeddingAction.ASYNC_QUEUED, "QUEUED", "bge-m3", jobId, batchId,
                null);
        DocumentLifecycleResponse lifecycle = lifecycle();
        DocumentMutationService.JsonMutationResult result =
                new DocumentMutationService.JsonMutationResult(
                        document, "UPDATED", true, false, 2, dispatch, lifecycle);
        when(mutationService.upsertJsonRecord(same(request), eq(COLLECTION_ID),
                eq("records:v1"), isNull(), isNull())).thenReturn(result);

        JsonRecordUpsertResponse response = service.upsert(request);

        assertEquals(41L, response.documentId());
        assertEquals(COLLECTION_ID, response.collectionId());
        assertEquals("collection-10", response.collectionKey());
        assertEquals("record-1", response.externalId());
        assertEquals("UPDATED", response.action());
        assertTrue(response.contentChanged());
        assertFalse(response.payloadChanged());
        assertEquals(2, response.versionNumber());
        assertEquals("COMPLETED", response.embeddingStatus());
        assertEquals("bge-m3", response.embeddingProfileKey());
        assertNull(response.error());
        assertEquals("ASYNC_QUEUED", response.embeddingAction());
        assertEquals(jobId, response.embeddingJobId());
        assertEquals(batchId, response.embeddingBatchId());
        assertEquals("default", response.sourceNamespace());
        assertEquals("rev-9", response.sourceRevision());
        assertEquals(3L, response.documentRevision());
        assertSame(lifecycle, response.lifecycle());
    }

    @Test
    void upsertMapsNullDispatchToNoneAction() {
        JsonRecordUpsertRequest request = request();
        DocumentMutationService.JsonMutationResult result =
                new DocumentMutationService.JsonMutationResult(
                        document(), "UNCHANGED", false, false, 1, null,
                        lifecycle());
        when(mutationService.upsertJsonRecord(same(request), eq(COLLECTION_ID),
                eq("records:v1"), isNull(), isNull())).thenReturn(result);

        JsonRecordUpsertResponse response = service.upsert(request);

        assertEquals("UNCHANGED", response.action());
        assertNull(response.error());
        assertEquals("NONE", response.embeddingAction());
        assertNull(response.embeddingJobId());
        assertNull(response.embeddingBatchId());
    }
}
