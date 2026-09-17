package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.dto.JsonRecordUpsertResponse;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JsonRecordService 遗留持久化路径长尾（Batch 479，JaCoCo 驱动）：
 * ASYNC 策略下 enqueueInCurrentTransaction 的结果映射
 * （outcomeFromDispatch）、batchUpsert 的计数聚合（created/
 * unchanged/持久化失败/嵌入失败）、批次负载上限拒绝，以及
 * importRecord 的空文档拒绝。
 */
class JsonRecordServiceLegacyUpsertTailTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver resolver;
    private EmbeddingDispatchService dispatchService;
    private RagProperties properties;
    private JsonRecordService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        resolver = mock(CollectionIdentityResolver.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        properties = new RagProperties();
        var versionService = mock(DocumentVersionService.class);
        lenient().when(versionService.forceRecordVersion(
                any(com.springairag.core.entity.RagDocument.class),
                anyString(), anyString()))
                .thenAnswer(invocation -> {
                    var version = new com.springairag.core.entity.RagDocumentVersion();
                    version.setVersionNumber(1);
                    return version;
                });
        lenient().when(documentRepository.saveAndFlush(
                any(com.springairag.core.entity.RagDocument.class)))
                .thenAnswer(invocation -> {
                    com.springairag.core.entity.RagDocument value =
                            invocation.getArgument(0);
                    if (value.getId() == null) {
                        value.setId(41L);
                    }
                    return value;
                });
        lenient().when(documentRepository
                .findByCollectionIdAndDocumentTypeAndExternalId(
                        any(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(resolver.beginActiveWrite(7L))
                .thenReturn(new CollectionIdentityResolver.ActiveCollectionToken(7L, 0L));
        var profileProvider = mock(EmbeddingProfileProvider.class);
        lenient().when(profileProvider.getActiveProfile())
                .thenReturn(new EmbeddingProfile(
                        9L, "profile", "test", "model", "v1",
                        1024, "COSINE", "NONE", true));

        service = new JsonRecordService(
                documentRepository,
                versionService,
                mock(DocumentEmbedService.class),
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                profileProvider,
                resolver,
                properties,
                MAPPER,
                mock(JdbcTemplate.class),
                null);
        service.setDispatchService(dispatchService);
        // mutationService 保持 null → 走遗留持久化路径。
    }

    private JsonRecordUpsertRequest validRequest(String externalId) {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setCollectionId(7L);
        request.setExternalId(externalId);
        request.setTitle("Record " + externalId);
        request.setRetrievalText("text " + externalId);
        try {
            request.setJsonbPayload(MAPPER.readTree(
                    "{\"k\":\"" + externalId + "\"}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return request;
    }

    @Test
    void asyncUpsertEnqueuesAndMapsDispatchOutcome() {
        UUID jobId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        when(dispatchService.enqueueInCurrentTransaction(
                any(com.springairag.core.entity.RagDocument.class),
                anyBoolean(), anyBoolean(), eq("JSON_UPSERT")))
                .thenReturn(new EmbeddingDispatchService.Result(
                        EmbeddingAction.ASYNC_QUEUED, "QUEUED", "profile",
                        jobId, batchId, null));
        JsonRecordUpsertRequest request = validRequest("rec-1");
        request.setEmbeddingPolicy(EmbeddingPolicy.ASYNC);

        JsonRecordUpsertResponse response = service.upsert(request);

        assertEquals("CREATED", response.action());
        assertEquals("QUEUED", response.embeddingStatus());
        assertEquals("ASYNC_QUEUED", response.embeddingAction());
        assertEquals(jobId, response.embeddingJobId());
        assertEquals(batchId, response.embeddingBatchId());
    }

    @Test
    void batchUpsertAggregatesOutcomeCounters() {
        when(dispatchService.enqueueInCurrentTransaction(
                any(com.springairag.core.entity.RagDocument.class),
                anyBoolean(), anyBoolean(), eq("JSON_UPSERT")))
                .thenReturn(new EmbeddingDispatchService.Result(
                        EmbeddingAction.ASYNC_QUEUED, "FAILED", "profile",
                        null, null, "enqueue rejected"));

        JsonRecordUpsertRequest created = validRequest("rec-1");
        created.setEmbeddingPolicy(EmbeddingPolicy.ASYNC);
        JsonRecordUpsertRequest broken = validRequest("rec-2");
        broken.setJsonbPayload(null);

        var response = service.batchUpsert(List.of(created, broken));

        assertEquals(2, response.summary().total());
        assertEquals(1, response.summary().created());
        // 嵌入状态 FAILED → embeddingFailed 计数。
        assertEquals(1, response.summary().embeddingFailed());
        // payload 缺失 → 持久化失败 + FAILED 结果占位。
        assertEquals(1, response.summary().persistenceFailed());
        assertEquals("FAILED",
                response.results().get(1).action());
    }

    @Test
    void batchUpsertRejectsOversizedAggregatePayload() {
        properties.getStructuredRecords().setMaxBatchPayloadBytes(1);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.batchUpsert(List.of(validRequest("rec-1"))));
        assertTrue(error.getMessage().contains("payload exceeds"));
    }

    @Test
    void importRecordRejectsNullDocument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.importRecord(7L, null));
    }

    @Test
    void batchUpsertRejectsNullAndEmptyLists() {
        assertThrows(IllegalArgumentException.class,
                () -> service.batchUpsert(null));
        assertThrows(IllegalArgumentException.class,
                () -> service.batchUpsert(List.of()));
    }
}
