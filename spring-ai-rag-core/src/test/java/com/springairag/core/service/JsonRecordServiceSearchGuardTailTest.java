package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordSearchRequest;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalScope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JsonRecordService search 路径守卫（Batch 400）：null 请求、
 * 空/超长 query、maxResults 下限与上限裁剪。作用域守卫由
 * JsonRecordServiceTest 覆盖，此处不重复。
 */
class JsonRecordServiceSearchGuardTailTest {

    private static final RetrievalScope SCOPE = RetrievalScope
            .selectedCollections(List.of(7L), null, null);

    private HybridRetrieverService hybridRetrieverService;
    private RagProperties properties;
    private JsonRecordService service;

    @BeforeEach
    void setUp() {
        hybridRetrieverService = mock(HybridRetrieverService.class);
        properties = new RagProperties();
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        service = new JsonRecordService(
                mock(RagDocumentRepository.class),
                mock(DocumentVersionService.class),
                mock(DocumentEmbedService.class),
                hybridRetrieverService,
                mock(ReRankingService.class),
                mock(com.springairag.core.config.EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                properties,
                new ObjectMapper(),
                mock(JdbcTemplate.class),
                null,
                transactionManager);
    }

    @Test
    void searchRejectsNullRequest() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> service.search(null));
        assertEquals("request must not be null", error.getMessage());
    }

    @Test
    void searchAuthorizedRejectsBlankQuery() {
        IllegalArgumentException nullQuery = assertThrows(
                IllegalArgumentException.class,
                () -> service.searchAuthorized(
                        null, RetrievalFilters.none(), null, SCOPE, null));
        assertEquals("query must not be blank", nullQuery.getMessage());

        IllegalArgumentException blankQuery = assertThrows(
                IllegalArgumentException.class,
                () -> service.searchAuthorized(
                        "   ", RetrievalFilters.none(), null, SCOPE, null));
        assertEquals("query must not be blank", blankQuery.getMessage());
    }

    @Test
    void searchAuthorizedRejectsOverlongQuery() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.searchAuthorized(
                        "x".repeat(10_001),
                        RetrievalFilters.none(), null, SCOPE, null));
        assertEquals("query must not exceed 10000 characters",
                error.getMessage());
    }

    @Test
    void searchAuthorizedRejectsNonPositiveMaxResults() {
        RetrievalConfig config = RetrievalConfig.builder()
                .maxResults(0)
                .build();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.searchAuthorized(
                        "spring", RetrievalFilters.none(), null, SCOPE,
                        config));
        assertEquals("maxResults must be at least 1", error.getMessage());
    }

    @Test
    void searchAuthorizedClampsMaxResultsToConfiguredCap() {
        properties.getStructuredRecords().setMaxSearchResults(3);
        when(hybridRetrieverService.searchInScopeDetailed(
                anyString(), any(RetrievalScope.class), any(),
                anyInt(), any(RetrievalConfig.class),
                any(RetrievalFilters.class)))
                .thenReturn(RetrievalOutcome.ofResults(List.of()));
        RetrievalConfig config = RetrievalConfig.builder()
                .maxResults(50)
                .build();

        service.searchAuthorized(
                "spring", RetrievalFilters.none(), null, SCOPE, config);

        // 检索器收到的 topK 与 effectiveConfig.maxResults 均被裁到上限 3；
        // scope 的 documentType 被收窄为 json-record。
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<RetrievalScope> effectiveScope =
                ArgumentCaptor.forClass(RetrievalScope.class);
        ArgumentCaptor<RetrievalConfig> effective =
                ArgumentCaptor.forClass(RetrievalConfig.class);
        verify(hybridRetrieverService).searchInScopeDetailed(
                eq("spring"), effectiveScope.capture(), any(), limit.capture(),
                effective.capture(), any(RetrievalFilters.class));
        assertEquals(3, limit.getValue());
        assertEquals(3, effective.getValue().getMaxResults());
        assertEquals(RagDocument.JSON_RECORD,
                effectiveScope.getValue().documentType());
        assertEquals(List.of(7L), effectiveScope.getValue().collectionIds());
    }
}
