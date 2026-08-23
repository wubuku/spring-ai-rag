package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.fulltext.FulltextSearchProvider;
import com.springairag.core.retrieval.fulltext.FulltextSearchProviderFactory;
import com.springairag.core.retrieval.fulltext.QueryLang;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridRetrieverCandidatePoolTest {

    @Test
    void legacyListOverloadsKeepPureRetrievalLimit() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(embeddingModel.embed("query")).thenReturn(new float[1024]);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());

        RagProperties properties = new RagProperties();
        properties.getRetrieval().setFulltextEnabled(false);
        properties.getRerank().setEnabled(true);
        properties.getRerank().setProvider("heuristic");
        properties.getRerank().setCandidateLimit(20);
        HybridRetrieverService service = new HybridRetrieverService(
                embeddingModel, jdbcTemplate, properties, null, Runnable::run);

        service.search("query", null, null, 5);
        service.searchInScope("query", RetrievalScope.unscoped(), null, 5);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(2))
                .queryForList(contains("LIMIT ?"), args.capture());
        for (Object[] values : args.getAllValues()) {
            assertEquals(5, values[values.length - 1]);
        }
    }

    @Test
    void enabledRerankExpandsVectorLimit() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(embeddingModel.embed("query")).thenReturn(new float[1024]);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());

        RagProperties properties = new RagProperties();
        properties.getRerank().setEnabled(true);
        properties.getRerank().setProvider("heuristic");
        properties.getRerank().setCandidateLimit(20);
        HybridRetrieverService service = new HybridRetrieverService(
                embeddingModel, jdbcTemplate, properties, null, Runnable::run);

        service.searchInScopeDetailed(
                "query",
                RetrievalScope.unscoped(),
                List.of(),
                5,
                RetrievalConfig.builder()
                        .maxResults(5)
                        .useHybridSearch(false)
                        .useRerank(true)
                        .build(),
                RetrievalFilters.none());

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(contains("LIMIT ?"), args.capture());
        Object[] values = args.getValue();
        assertEquals(20, values[values.length - 1]);
    }

    @Test
    void expansionRequiresRequestGlobalAndProviderConditions() {
        for (Condition condition : Condition.values()) {
            EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
            when(embeddingModel.embed("query")).thenReturn(new float[1024]);
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                    .thenReturn(List.of());

            RagProperties properties = new RagProperties();
            properties.getRerank().setEnabled(condition.globalEnabled);
            properties.getRerank().setProvider(condition.provider);
            properties.getRerank().setCandidateLimit(20);
            HybridRetrieverService service = new HybridRetrieverService(
                    embeddingModel, jdbcTemplate, properties, null, Runnable::run);

            service.searchInScopeDetailed(
                    "query",
                    RetrievalScope.unscoped(),
                    List.of(),
                    5,
                    RetrievalConfig.builder()
                            .maxResults(5)
                            .useHybridSearch(false)
                            .useRerank(condition.requestEnabled)
                            .build(),
                    RetrievalFilters.none());

            ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).queryForList(contains("LIMIT ?"), args.capture());
            Object[] values = args.getValue();
            assertEquals(condition.expectedLimit, values[values.length - 1],
                    condition.name());
        }
    }

    @Test
    void hybridUsesTwoTimesCandidateLimitPerBranch() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(embeddingModel.embed("query")).thenReturn(new float[1024]);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());

        FulltextSearchProvider provider = mock(FulltextSearchProvider.class);
        when(provider.getName()).thenReturn("test-fulltext");
        when(provider.isAvailable()).thenReturn(true);
        when(provider.searchInScopeDetailed(
                eq("query"),
                any(),
                eq(List.of()),
                eq(40),
                eq(0.5),
                eq(1L),
                eq(RetrievalFilters.none())))
                .thenReturn(FulltextSearchProvider.SearchResult.success(List.of(), 0));
        FulltextSearchProviderFactory factory = mock(FulltextSearchProviderFactory.class);
        when(factory.detectLang("query")).thenReturn(QueryLang.EN_OR_OTHER);
        when(factory.getProvider(QueryLang.EN_OR_OTHER)).thenReturn(provider);

        RagProperties properties = new RagProperties();
        properties.getRerank().setEnabled(true);
        properties.getRerank().setProvider("heuristic");
        properties.getRerank().setCandidateLimit(20);
        HybridRetrieverService service = new HybridRetrieverService(
                embeddingModel, jdbcTemplate, properties, factory, Runnable::run);

        RetrievalOutcome outcome = service.searchInScopeDetailed(
                "query",
                RetrievalScope.unscoped(),
                List.of(),
                5,
                RetrievalConfig.builder()
                        .maxResults(5)
                        .useHybridSearch(true)
                        .useRerank(true)
                        .build(),
                RetrievalFilters.none());

        verify(provider).searchInScopeDetailed(
                eq("query"),
                any(),
                eq(List.of()),
                eq(40),
                eq(0.5),
                eq(1L),
                eq(RetrievalFilters.none()));
        assertEquals(0, outcome.fusionStage().resultCount());
    }

    private enum Condition {
        REQUEST_DISABLED(false, true, "heuristic", 5),
        GLOBAL_DISABLED(true, false, "heuristic", 5),
        NOOP_PROVIDER(true, true, "none", 5),
        EXPAND(true, true, "heuristic", 20);

        private final boolean requestEnabled;
        private final boolean globalEnabled;
        private final String provider;
        private final int expectedLimit;

        Condition(
                boolean requestEnabled,
                boolean globalEnabled,
                String provider,
                int expectedLimit) {
            this.requestEnabled = requestEnabled;
            this.globalEnabled = globalEnabled;
            this.provider = provider;
            this.expectedLimit = expectedLimit;
        }
    }
}
