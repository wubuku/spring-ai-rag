package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.service.DocumentEmbedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;





/**
 * JsonRecordService batchUpsert 守卫（Batch 399）：空列表、批量
 * 上限、载荷上限。
 */
class JsonRecordServiceTailTest {

    private JsonRecordService service;

    @BeforeEach
    void setUp() {
        service = new JsonRecordService(
                mock(RagDocumentRepository.class),
                mock(DocumentVersionService.class),
                mock(DocumentEmbedService.class),
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                mock(com.springairag.core.config.EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                new RagProperties(),
                new ObjectMapper(),
                mock(JdbcTemplate.class),
                null,
                null);
    }

    private JsonRecordUpsertRequest request(String externalId) {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setCollectionId(7L);
        request.setExternalId(externalId);
        request.setTitle("T");
        request.setRetrievalText("text");
        request.setSource("upload-1");
        return request;
    }

    @Test
    void batchUpsertRejectsNullItems() {
        assertThrows(IllegalArgumentException.class,
                () -> service.batchUpsert(null));
    }

    @Test
    void batchUpsertRejectsEmptyItems() {
        assertThrows(IllegalArgumentException.class,
                () -> service.batchUpsert(java.util.List.of()));
    }

    @Test
    void batchUpsertRejectsMoreThanMaxBatchSize() {
        var items = new java.util.ArrayList<JsonRecordUpsertRequest>();
        for (int i = 0; i <= 20; i++) {
            items.add(request("doc-" + i));
        }
        assertThrows(IllegalArgumentException.class,
                () -> service.batchUpsert(items));
    }

    @Test
    void batchUpsertRejectsOversizedBatchPayload() {
        // 批量载荷守卫统计 jsonbPayload 序列化字节：单个超限载荷直接拒绝。
        var item = request("doc-big");
        item.setJsonbPayload(
                com.fasterxml.jackson.databind.node.TextNode.valueOf(
                        "x".repeat(10_485_761)));
        assertThrows(IllegalArgumentException.class,
                () -> service.batchUpsert(java.util.List.of(item)));
    }
}
