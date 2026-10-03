package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.dto.JsonRecordUpsertResponse;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JsonRecordService 批量与导入长尾（Batch 479 建立，Batch 834 改名）：
 * batchUpsert 的计数聚合（created/unchanged/持久化失败/嵌入失败）、
 * 批次负载上限与空列表拒绝，以及 importRecord 的空文档拒绝。
 *
 * <p>Batch 834 删掉 legacy 内联落库路径后，ASYNC 派发结果映射
 * （outcomeFromDispatch）已随 {@code embedIfRequested} 一并消失；
 * 剩下的批量计数与导入拒绝仍由本服务自己负责，断言对象相应
 * 换成「交给变更层什么」与「聚出什么数」。
 */
class JsonRecordServiceBatchImportTailTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver resolver;
    private DocumentMutationService mutationService;
    private RagProperties properties;
    private JsonRecordService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        resolver = mock(CollectionIdentityResolver.class);
        mutationService = mock(DocumentMutationService.class);
        properties = new RagProperties();
        lenient().when(resolver.mapKeys(any()))
                .thenReturn(Map.of(7L, "kb-7"));

        service = new JsonRecordService(
                documentRepository,
                mock(DocumentVersionService.class),
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                resolver,
                properties,
                MAPPER,
                mock(JdbcTemplate.class),
                null);
        service.setMutationService(mutationService);
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
    void batchUpsertAggregatesOutcomeCounters() {
        JsonRecordUpsertRequest created = validRequest("rec-1");
        created.setEmbeddingPolicy(EmbeddingPolicy.ASYNC);
        JsonRecordUpsertRequest broken = validRequest("rec-2");
        broken.setJsonbPayload(null);
        when(mutationService.upsertJsonRecord(
                same(created), eq(7L), eq("kb-7"), isNull(), isNull()))
                .thenReturn(new DocumentMutationService.JsonMutationResult(
                        JsonRecordMutationFixture.document(71L, "rec-1"),
                        "CREATED", true, false, 1,
                        new EmbeddingDispatchService.Result(
                                EmbeddingAction.ASYNC_QUEUED, "FAILED",
                                "profile", null, null, "enqueue rejected"),
                        JsonRecordMutationFixture.lifecycle("FAILED")));

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
