package com.springairag.core.service;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JsonRecordService search 详细管道（Batch 389）：rerank 正常重排
 * 与 SUCCESS 标记、去重/可见性过滤/limit 截断、jsonRecordScope 的
 * 类型守卫与 JSON_RECORD 强制转换。
 */
class JsonRecordServiceSearchPipelineTest {

    private RagDocumentRepository documentRepository;
    private HybridRetrieverService hybridRetrieverService;
    private ReRankingService reRankingService;
    private CollectionIdentityResolver resolver;
    private JsonRecordService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        hybridRetrieverService = mock(HybridRetrieverService.class);
        reRankingService = mock(ReRankingService.class);
        resolver = mock(CollectionIdentityResolver.class);
        service = new JsonRecordService(
                documentRepository,
                mock(DocumentVersionService.class),
                mock(DocumentEmbedService.class),
                hybridRetrieverService,
                reRankingService,
                mock(EmbeddingProfileProvider.class),
                resolver,
                new RagProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(JdbcTemplate.class),
                null);
    }

    private RetrievalResult ranked(String documentId, double score) {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(documentId);
        result.setChunkText("text");
        result.setScore(score);
        return result;
    }

    private com.springairag.core.entity.RagDocument jsonDocument(long id) {
        com.springairag.core.entity.RagDocument doc =
                new com.springairag.core.entity.RagDocument();
        doc.setId(id);
        doc.setEnabled(Boolean.TRUE);
        doc.setDocumentType(com.springairag.core.entity.RagDocument.JSON_RECORD);
        doc.setCollectionId(7L);
        doc.setTitle("Doc " + id);
        doc.setExternalId("rec-" + id);
        return doc;
    }

    private void stubResolverKeys() {
        when(resolver.mapKeys(List.of(7L))).thenReturn(Map.of(7L, "kb"));
    }

    private void stubOutcome(RetrievalOutcome outcome) {
        when(hybridRetrieverService.searchInScopeDetailed(
                anyString(), any(), any(), anyInt(),
                any(RetrievalConfig.class), any(RetrievalFilters.class)))
                .thenReturn(outcome);
    }

    @Test
    void searchDetailedNormalRerankMarksSuccessAndMapsCollectionKeys() {
        stubOutcome(RetrievalOutcome.ofResults(List.of(
                ranked("1", 0.9), ranked("2", 0.5), ranked("3", 0.3))));
        when(reRankingService.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(ranked("3", 0.95), ranked("1", 0.8)));
        when(documentRepository.findByIdInAndDocumentTypeAndEnabledTrue(
                any(), eq(com.springairag.core.entity.RagDocument.JSON_RECORD)))
                .thenReturn(List.of(jsonDocument(1L), jsonDocument(3L)));
        stubResolverKeys();

        var detail = service.searchAuthorizedDetailed(
                "query", RetrievalFilters.none(), null,
                RetrievalScope.anyAssigned(null, null),
                RetrievalConfig.builder().maxResults(5).useRerank(true).build());

        // rerank 重排后：排名 3 在前，排名 1 在后，排名 2 被丢弃。
        assertEquals(List.of(3L, 1L),
                detail.response().results().stream()
                        .map(r -> r.documentId()).toList());
        assertEquals("kb", detail.response().results().getFirst().collectionKey());
    }

    @Test
    void searchDetailedStopsAtLimit() {
        stubOutcome(RetrievalOutcome.ofResults(List.of(
                ranked("1", 0.9), ranked("2", 0.5), ranked("3", 0.3))));
        when(reRankingService.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(ranked("3", 0.95), ranked("1", 0.8),
                        ranked("2", 0.1)));
        when(documentRepository.findByIdInAndDocumentTypeAndEnabledTrue(
                any(), eq(com.springairag.core.entity.RagDocument.JSON_RECORD)))
                .thenReturn(List.of(jsonDocument(1L), jsonDocument(2L),
                        jsonDocument(3L)));
        stubResolverKeys();

        var detail = service.searchAuthorizedDetailed(
                "query", RetrievalFilters.none(), null,
                RetrievalScope.anyAssigned(null, null),
                RetrievalConfig.builder().maxResults(2).useRerank(true).build());

        // 候选 3 个但 limit=2：遍历在收满 2 条后 break。
        assertEquals(2, detail.response().results().size());
        assertEquals(3L, detail.response().results().get(0).documentId());
    }

    @Test
    void searchDetailedSkipsDisabledOrForeignTypeDocuments() {
        com.springairag.core.entity.RagDocument disabled = jsonDocument(1L);
        disabled.setEnabled(Boolean.FALSE);
        com.springairag.core.entity.RagDocument textDoc = jsonDocument(2L);
        textDoc.setDocumentType("text");
        when(documentRepository.findByIdInAndDocumentTypeAndEnabledTrue(
                any(), eq(com.springairag.core.entity.RagDocument.JSON_RECORD)))
                .thenReturn(List.of());
        stubOutcome(RetrievalOutcome.ofResults(List.of(
                ranked("1", 0.9), ranked("2", 0.5))));

        var detail = service.searchAuthorizedDetailed(
                "query", RetrievalFilters.none(), null,
                RetrievalScope.anyAssigned(null, null),
                RetrievalConfig.builder().maxResults(5).build());

        // 禁用与非 json-record 文档都被 scopeAllows 过滤。
        assertEquals(0, detail.response().results().size());
    }

    @Test
    void searchDetailedForcesJsonRecordScopeOnAuthorizedScope() {
        ArgumentCaptor<RetrievalScope> scopeCaptor =
                ArgumentCaptor.forClass(RetrievalScope.class);
        when(hybridRetrieverService.searchInScopeDetailed(
                anyString(), scopeCaptor.capture(), any(), anyInt(),
                any(RetrievalConfig.class), any(RetrievalFilters.class)))
                .thenReturn(RetrievalOutcome.ofResults(List.of()));

        // text 类型授权范围 → noMatches（matchNone）。
        service.searchAuthorizedDetailed(
                "query", RetrievalFilters.none(), null,
                new RetrievalScope(
                        RetrievalScope.CollectionFilter.SELECTED,
                        List.of(7L), List.of(), "text", false),
                RetrievalConfig.builder().maxResults(5).build());
        assertTrue(scopeCaptor.getValue().matchNone());

        // 兼容类型（null）→ 强制改写为 json-record，保留 SELECTED 过滤。
        service.searchAuthorizedDetailed(
                "query", RetrievalFilters.none(), null,
                new RetrievalScope(
                        RetrievalScope.CollectionFilter.SELECTED,
                        List.of(7L), List.of(), null, false),
                RetrievalConfig.builder().maxResults(5).build());
        assertEquals(com.springairag.core.entity.RagDocument.JSON_RECORD,
                scopeCaptor.getValue().documentType());
        assertEquals(RetrievalScope.CollectionFilter.SELECTED,
                scopeCaptor.getValue().collectionFilter());
    }
}
