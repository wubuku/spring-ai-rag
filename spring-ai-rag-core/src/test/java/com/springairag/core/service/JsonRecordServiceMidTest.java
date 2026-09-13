package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.dto.JsonRecordUpsertResponse;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JsonRecordService 第二扫（Batch 377）：详细检索的 rerank 降级
 * 与查询守卫、getDetail 的缺失/类型拒绝、批量 upsert 汇总计数。
 */
class JsonRecordServiceMidTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RagDocumentRepository documentRepository;
    private HybridRetrieverService hybridRetrieverService;
    private ReRankingService reRankingService;
    private DocumentMutationService mutationService;
    private JsonRecordService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        hybridRetrieverService = mock(HybridRetrieverService.class);
        reRankingService = mock(ReRankingService.class);
        mutationService = mock(DocumentMutationService.class);
        service = new JsonRecordService(
                documentRepository,
                mock(DocumentVersionService.class),
                mock(DocumentEmbedService.class),
                hybridRetrieverService,
                reRankingService,
                mock(EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                new RagProperties(),
                MAPPER,
                mock(JdbcTemplate.class),
                null);
        service.setMutationService(mutationService);
    }

    private JsonRecordUpsertRequest validRequest(String externalId) {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setCollectionId(7L);
        request.setExternalId(externalId);
        request.setTitle("Title " + externalId);
        request.setRetrievalText("text");
        try {
            request.setJsonbPayload(MAPPER.readTree("{\"k\":1}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return request;
    }

    private JsonRecordUpsertResponse response(String action,
                                              String embeddingStatus) {
        return new JsonRecordUpsertResponse(
                41L, 7L, "kb", "doc-1", action, true, true, 1,
                embeddingStatus, "profile", null, null, null, null);
    }

    @Test
    void searchDetailedRejectsBlankQueryAndInvalidMaxResults() {
        RetrievalScope scope = RetrievalScope.anyAssigned(null, null);
        RetrievalConfig config = RetrievalConfig.builder().maxResults(5).build();

        assertThrows(IllegalArgumentException.class, () ->
                service.searchAuthorizedDetailed(
                        "  ", RetrievalFilters.none(), null, scope, config));
        assertThrows(IllegalArgumentException.class, () ->
                service.searchAuthorizedDetailed(
                        "query", RetrievalFilters.none(), null, scope,
                        RetrievalConfig.builder().maxResults(0).build()));
    }

    @Test
    void searchDetailedDegradesGracefullyWhenRerankFails() {
        List<RetrievalResult> results = List.of(
                new RetrievalResult(), new RetrievalResult());
        RetrievalOutcome outcome = RetrievalOutcome.ofResults(results);
        when(hybridRetrieverService.searchInScopeDetailed(
                anyString(), any(), any(), anyInt(),
                any(RetrievalConfig.class), any(RetrievalFilters.class)))
                .thenReturn(outcome);
        when(reRankingService.rerank(anyString(), any(List.class), anyInt()))
                .thenThrow(new IllegalStateException("rerank down"));

        var detail = service.searchAuthorizedDetailed(
                "query", RetrievalFilters.none(), null,
                RetrievalScope.anyAssigned(null, null),
                RetrievalConfig.builder().maxResults(5)
                        .useRerank(true).build());

        // rerank 降级后走 limitResults 保留原有结果集。
        assertNotNull(detail);
        assertNotNull(detail.response());
    }

    @Test
    void getDetailRejectsMissingAndNonJsonRecordDocuments() {
        when(documentRepository.findById(41L))
                .thenReturn(java.util.Optional.empty());
        assertThrows(DocumentNotFoundException.class,
                () -> service.getDetail(41L));

        com.springairag.core.entity.RagDocument textDocument =
                new com.springairag.core.entity.RagDocument();
        textDocument.setDocumentType("text");
        when(documentRepository.findById(41L))
                .thenReturn(java.util.Optional.of(textDocument));
        assertThrows(DocumentNotFoundException.class,
                () -> service.getDetail(41L));
    }

    private DocumentMutationService.JsonMutationResult result(
            String action, boolean contentChanged, boolean payloadChanged,
            int version) {
        com.springairag.core.entity.RagDocument document =
                new com.springairag.core.entity.RagDocument();
        document.setId(41L);
        document.setCollectionId(7L);
        document.setExternalId("doc-1");
        var lifecycle = new com.springairag.api.dto.DocumentLifecycleResponse(
                "ACTIVE", "SEARCHABLE", "CURRENT", "COMPLETED",
                "profile", null, null, null, false);
        return new DocumentMutationService.JsonMutationResult(
                document, action, contentChanged, payloadChanged,
                version, null, lifecycle);
    }

    @Test
    void batchUpsertAggregatesSummaryCounts() {
        JsonRecordUpsertRequest created = validRequest("doc-1");
        JsonRecordUpsertRequest updated = validRequest("doc-2");
        JsonRecordUpsertRequest unchanged = validRequest("doc-3");
        JsonRecordUpsertRequest broken = validRequest("doc-4");
        when(mutationService.upsertJsonRecord(
                eq(created), anyLong(), any(), any(), any()))
                .thenReturn(result("CREATED", true, true, 1));
        when(mutationService.upsertJsonRecord(
                eq(updated), anyLong(), any(), any(), any()))
                .thenReturn(result("UPDATED", true, true, 2));
        when(mutationService.upsertJsonRecord(
                eq(unchanged), anyLong(), any(), any(), any()))
                .thenReturn(result("UNCHANGED", false, false, 2));
        when(mutationService.upsertJsonRecord(
                eq(broken), anyLong(), any(), any(), any()))
                .thenThrow(new IllegalStateException("persist broken"));

        var batch = service.batchUpsert(List.of(
                created, updated, unchanged, broken));

        assertEquals(4, batch.summary().total());
        assertEquals(1, batch.summary().created());
        assertEquals(1, batch.summary().updated());
        assertEquals(1, batch.summary().unchanged());
        assertEquals(1, batch.summary().persistenceFailed());
        assertEquals(0, batch.summary().embeddingFailed());
    }
}
