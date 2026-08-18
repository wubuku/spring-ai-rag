package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.fulltext.FulltextSearchProvider;
import com.springairag.core.retrieval.fulltext.FulltextSearchProviderFactory;
import com.springairag.core.retrieval.fulltext.QueryLang;
import com.springairag.core.retrieval.fulltext.SearchCapabilities;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridRetrieverOutcomeTest {

    @Test
    void matchNoneScopeIsFailClosed() {
        HybridRetrieverService service = service();
        RetrievalOutcome outcome = service.searchInScopeDetailed(
                "query",
                RetrievalScope.noMatches(),
                null,
                5,
                RetrievalConfig.builder().maxResults(5).build(),
                RetrievalFilters.none());
        assertTrue(outcome.results().isEmpty());
        assertEquals(RetrievalOutcomeCodes.SCOPE_MATCH_NONE, outcome.outcomeCode());
    }

    @Test
    void listApiStillReturnsResultsOnly() {
        HybridRetrieverService service = service();
        List<RetrievalResult> results = service.searchInScope(
                "query",
                RetrievalScope.noMatches(),
                null,
                5);
        assertTrue(results.isEmpty());
    }

    @Test
    void detailedSearchReportsProviderFailureWithoutBreakingListApi() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed("query")).thenReturn(new float[1024]);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());
        FulltextSearchProvider provider = mock(FulltextSearchProvider.class);
        when(provider.getName()).thenReturn("test-fulltext");
        when(provider.isAvailable()).thenReturn(true);
        when(provider.searchInScopeDetailed(
                eq("query"), any(RetrievalScope.class), eq(List.of()), anyInt(), eq(0.0),
                eq(1L), eq(RetrievalFilters.none())))
                .thenReturn(FulltextSearchProvider.SearchResult.failure(
                        "DataAccessResourceFailureException"));
        FulltextSearchProviderFactory factory =
                mock(FulltextSearchProviderFactory.class);
        when(factory.detectLang("query")).thenReturn(QueryLang.EN_OR_OTHER);
        when(factory.getProvider(QueryLang.EN_OR_OTHER)).thenReturn(provider);

        HybridRetrieverService service = new HybridRetrieverService(
                embeddingModel, jdbcTemplate, new RagProperties(), factory, Runnable::run);
        RetrievalConfig config = RetrievalConfig.builder()
                .maxResults(10)
                .minScore(0.0)
                .useHybridSearch(true)
                .build();
        RetrievalOutcome outcome = service.searchInScopeDetailed(
                "query", RetrievalScope.unscoped(), List.of(), 10,
                config, RetrievalFilters.none());

        RetrievalBranchStage fulltext = outcome.branchStages().stream()
                .filter(stage -> RetrievalBranchStage.FULLTEXT.equals(stage.branch()))
                .findFirst()
                .orElseThrow();
        assertEquals(RetrievalBranchStage.ERROR, fulltext.status());
        assertEquals("DataAccessResourceFailureException", fulltext.errorCode());
        assertTrue(service.searchInScope(
                "query", RetrievalScope.noMatches(), List.of(), 10, config).isEmpty());
    }

    @Test
    void vectorOnlySearchUsesConfiguredTimeout() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RagProperties properties = new RagProperties();
        properties.getAsync().setRetrievalTimeoutSeconds(0);
        HybridRetrieverService service = new HybridRetrieverService(
                embeddingModel,
                jdbcTemplate,
                properties,
                null,
                command -> {
                    // Keep the future incomplete so the timeout path is deterministic.
                });

        RetrievalOutcome outcome = service.searchInScopeDetailed(
                "query",
                RetrievalScope.unscoped(),
                List.of(),
                5,
                RetrievalConfig.builder()
                        .maxResults(5)
                        .useHybridSearch(false)
                        .minScore(0.0)
                        .build(),
                RetrievalFilters.none());

        assertEquals(RetrievalOutcomeCodes.VECTOR_TIMEOUT, outcome.outcomeCode());
        assertEquals(RetrievalBranchStage.TIMEOUT,
                outcome.vectorStage().status());
        verify(embeddingModel, never()).embed("query");
    }

    @Test
    void detailedSearchKeepsCandidatesThatFailMinScoreInDiagnostics() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed("query")).thenReturn(new float[1024]);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());
        FulltextSearchProvider provider = mock(FulltextSearchProvider.class);
        when(provider.getName()).thenReturn("test-fulltext");
        when(provider.isAvailable()).thenReturn(true);
        when(provider.searchInScopeDetailed(
                eq("query"), any(RetrievalScope.class), eq(List.of()), anyInt(), eq(0.8),
                eq(1L), eq(RetrievalFilters.none())))
                .thenReturn(FulltextSearchProvider.SearchResult.success(List.of(), 1));
        FulltextSearchProviderFactory factory =
                mock(FulltextSearchProviderFactory.class);
        when(factory.detectLang("query")).thenReturn(QueryLang.EN_OR_OTHER);
        when(factory.getProvider(QueryLang.EN_OR_OTHER)).thenReturn(provider);

        HybridRetrieverService service = new HybridRetrieverService(
                embeddingModel, jdbcTemplate, new RagProperties(), factory, Runnable::run);
        RetrievalOutcome outcome = service.searchInScopeDetailed(
                "query",
                RetrievalScope.unscoped(),
                List.of(),
                5,
                RetrievalConfig.builder()
                        .maxResults(5)
                        .minScore(0.8)
                        .useHybridSearch(true)
                        .build(),
                RetrievalFilters.none());

        assertEquals(RetrievalOutcomeCodes.BELOW_MIN_SCORE, outcome.outcomeCode());
        assertEquals(1, outcome.fulltextStage().candidateCount());
        assertEquals(0, outcome.fulltextStage().resultCount());
    }

    private HybridRetrieverService service() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SearchCapabilities caps = new SearchCapabilities(jdbcTemplate, false);
        caps.setHasPgVector(true);
        caps.setHasPgTrgm(true);
        caps.setHasTrgmIndex(true);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("not found"));
        when(jdbcTemplate.queryForObject(contains("pg_trgm"), eq(Integer.class)))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("gin_trgm_ops"), eq(Boolean.class)))
                .thenReturn(true);
        FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(
                jdbcTemplate, "auto", caps);
        return new HybridRetrieverService(
                embeddingModel, jdbcTemplate, new RagProperties(), factory, null);
    }
}
