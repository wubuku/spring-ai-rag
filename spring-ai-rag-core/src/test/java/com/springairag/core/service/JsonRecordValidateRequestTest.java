package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * JsonRecordService.validateRequest 校验矩阵（Batch 336）：
 * 请求/集合/externalId/title/retrievalText/payload/source 逐守卫
 * 拒绝及上限边界。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JsonRecordValidateRequestTest {

    @Mock RagDocumentRepository documentRepository;

    private RagProperties properties;
    private JsonRecordService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        service = new JsonRecordService(
                documentRepository,
                mock(DocumentVersionService.class),
                mock(DocumentEmbedService.class),
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                () -> null,
                mock(CollectionIdentityResolver.class),
                properties,
                new ObjectMapper(),
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
    }

    private com.springairag.api.dto.JsonRecordUpsertRequest validRequest() {
        com.springairag.api.dto.JsonRecordUpsertRequest request =
                new com.springairag.api.dto.JsonRecordUpsertRequest();
        request.setCollectionId(10L);
        request.setExternalId("sku-1");
        request.setTitle("Product");
        request.setRetrievalText("searchable text");
        request.setJsonbPayload(parse("{\"sku\":\"S-1\"}"));
        return request;
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String validateError(Consumer<com.springairag.api.dto.JsonRecordUpsertRequest> tweak) {
        var request = validRequest();
        tweak.accept(request);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.upsert(request));
        return error.getMessage();
    }

    @Test
    void nullRequestRejected() {
        assertEquals("request must not be null",
                assertThrows(IllegalArgumentException.class,
                        () -> service.upsert(null)).getMessage());
    }

    @Test
    void collectionIdMustBePositive() {
        // upsert 先做集合解析：null 集合且无键时由集合解析先行拒绝。
        assertThrows(IllegalArgumentException.class,
                () -> service.upsert(validRequestWithCollectionId(null)));
        // 集合解析通过后（非空 id），由 validateRequest 拒绝非正值。
        assertTrue(validateError(r -> r.setCollectionId(0L))
                .contains("collectionId must be positive"));
    }

    private com.springairag.api.dto.JsonRecordUpsertRequest
            validRequestWithCollectionId(Long collectionId) {
        var request = validRequest();
        request.setCollectionId(collectionId);
        return request;
    }

    @Test
    void externalIdIsRequiredAndBounded() {
        assertTrue(validateError(r -> r.setExternalId(null))
                .contains("externalId must not be blank"));
        assertTrue(validateError(r -> r.setExternalId("   "))
                .contains("externalId must not be blank"));
        assertTrue(validateError(r -> r.setExternalId("x".repeat(256)))
                .contains("externalId must not exceed 255"));
    }

    @Test
    void titleIsRequiredAndBounded() {
        assertTrue(validateError(r -> r.setTitle(null))
                .contains("title must not be blank"));
        assertTrue(validateError(r -> r.setTitle("  "))
                .contains("title must not be blank"));
        assertTrue(validateError(r -> r.setTitle("t".repeat(256)))
                .contains("title must not exceed 255"));
    }

    @Test
    void retrievalTextIsRequiredAndBounded() {
        assertTrue(validateError(r -> r.setRetrievalText(null))
                .contains("retrievalText must not be blank"));
        assertTrue(validateError(r -> r.setRetrievalText(" "))
                .contains("retrievalText must not be blank"));
        int limit = properties.getStructuredRecords()
                .getMaxRetrievalTextChars();
        assertTrue(validateError(r ->
                        r.setRetrievalText("x".repeat(limit + 1)))
                .contains("retrievalText exceeds " + limit));
    }

    @Test
    void payloadMustBeNonNullObjectWithinByteBudget() {
        assertTrue(validateError(r -> r.setJsonbPayload(null))
                .contains("must be a non-null JSON value"));
        assertTrue(validateError(r -> r.setJsonbPayload(parse("null")))
                .contains("must be a non-null JSON value"));

        int budget = properties.getStructuredRecords()
                .getMaxJsonbPayloadBytes();
        properties.getStructuredRecords().setMaxJsonbPayloadBytes(8);
        assertTrue(validateError(r -> r.setJsonbPayload(
                        parse("{\"sku\":\"S-1\"}")))
                .contains("jsonbPayload exceeds 8 bytes"));
        // 恢复预算确认原负载可过校验边界（仅校验错误路径断言）。
        assertTrue(budget > 8);
    }

    @Test
    void sourceIsBounded() {
        assertTrue(validateError(r -> r.setSource("s".repeat(256)))
                .contains("source must not exceed 255"));
    }
}
