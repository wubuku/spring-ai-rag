package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * JsonRecordService 第一扫（Batch 376）：批量 upsert 的空列表、
 * 批量上限与载荷总量守卫、可选协作对象 setter 装配。
 */
class JsonRecordServiceFrontTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RagProperties properties;
    private JsonRecordService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        service = new JsonRecordService(
                mock(RagDocumentRepository.class),
                mock(DocumentVersionService.class),
                mock(DocumentEmbedService.class),
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                mock(EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                properties,
                MAPPER,
                mock(JdbcTemplate.class),
                null);
    }

    private JsonRecordUpsertRequest request() {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setCollectionId(7L);
        return request;
    }

    @Test
    void batchUpsertRejectsEmptyOrNullItems() {
        IllegalArgumentException empty = assertThrows(
                IllegalArgumentException.class,
                () -> service.batchUpsert(List.of()));
        assertEquals("items must not be empty", empty.getMessage());

        IllegalArgumentException nullItems = assertThrows(
                IllegalArgumentException.class,
                () -> service.batchUpsert(null));
        assertEquals("items must not be empty", nullItems.getMessage());
    }

    @Test
    void batchUpsertRejectsMoreThanMaxBatchSize() {
        List<JsonRecordUpsertRequest> items = new java.util.ArrayList<>();
        int limit = properties.getStructuredRecords().getMaxBatchSize();
        for (int i = 0; i <= limit; i++) {
            items.add(request());
        }

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.batchUpsert(items));
        assertEquals("JSON record batch is limited to " + limit + " items",
                error.getMessage());
    }

    @Test
    void batchUpsertRejectsOversizedBatchPayload() throws Exception {
        properties.getStructuredRecords().setMaxBatchPayloadBytes(10);
        List<JsonRecordUpsertRequest> items = List.of(requestWithPayload(
                MAPPER.readTree("{\"key\":\"value\"}")));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.batchUpsert(items));
        assertTrue(error.getMessage()
                .contains("JSON record batch payload exceeds"));
    }

    @Test
    void optionalCollaboratorSettersWireWithoutError() {
        JsonRecordService wired = new JsonRecordService(
                mock(RagDocumentRepository.class),
                mock(DocumentVersionService.class),
                mock(DocumentEmbedService.class),
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                mock(EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                properties,
                MAPPER,
                mock(JdbcTemplate.class),
                null);

        assertDoesNotThrow(() -> {
            wired.setDispatchService(
                    mock(EmbeddingDispatchService.class));
            wired.setMutationService(mock(DocumentMutationService.class));
            wired.setLifecycleService(
                    mock(DocumentLifecycleService.class));
            wired.setKeywordIndexPersistenceService(
                    mock(KeywordIndexPersistenceService.class));
            wired.setAddressRetirementService(
                    mock(ExternalAddressRetirementService.class));
        });
    }

    private JsonRecordUpsertRequest requestWithPayload(
            com.fasterxml.jackson.databind.JsonNode payload) {
        JsonRecordUpsertRequest request = request();
        request.setJsonbPayload(payload);
        return request;
    }
}
