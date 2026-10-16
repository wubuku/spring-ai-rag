package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.enums.CollectionCoverageMode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.fulltext.FulltextSearchProvider;
import com.springairag.core.retrieval.fulltext.FulltextSearchProviderFactory;
import com.springairag.core.retrieval.fulltext.QueryLang;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

class HybridRetrieverCollectionCoverageTest {

    @Test
    void eachCollectionUsesOneEmbeddingAndReportsMissingCollections() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RagProperties properties = new RagProperties();
        HybridRetrieverService service = new HybridRetrieverService(
                embeddingModel, jdbcTemplate, properties, null, null);
        float[] queryVector = new float[1024];
        Arrays.fill(queryVector, 0.1f);
        when(embeddingModel.embed("coverage")).thenReturn(queryVector);

        Map<String, Object> first = row("doc-1", 1L, queryVector);
        Map<String, Object> third = row("doc-3", 3L, queryVector);
        when(jdbcTemplate.queryForList(contains("embedding_1024 <=>"), any(Object[].class)))
                .thenReturn(List.of(first), List.of(), List.of(third));

        RetrievalScope scope = RetrievalScope.selectedCollections(
                List.of(1L, 2L, 3L), List.of(), null,
                CollectionCoverageMode.EACH_COLLECTION);
        RetrievalConfig config = RetrievalConfig.builder()
                .maxResults(3)
                .useHybridSearch(false)
                .useRerank(false)
                .build();

        RetrievalOutcome outcome = service.searchInScopeDetailed(
                "coverage", scope, null, 3, config, RetrievalFilters.none());

        assertEquals(List.of(1L, 3L), outcome.results().stream()
                .map(RetrievalResult::getCollectionId).toList());
        assertEquals(List.of(2L), collectionCoverage(outcome)
                .get("missingCollectionIds"));
        verify(embeddingModel, times(1)).embed("coverage");
    }

    @Test
    void fulltextBranchCompletesWhileQueryEmbeddingIsBlocked() throws Exception {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        FulltextSearchProviderFactory factory = mock(FulltextSearchProviderFactory.class);
        FulltextSearchProvider fulltext = mock(FulltextSearchProvider.class);
        RagProperties properties = new RagProperties();
        properties.getAsync().setRetrievalTimeoutSeconds(3);
        when(factory.detectLang(anyString())).thenReturn(QueryLang.EN_OR_OTHER);
        when(factory.getProvider(QueryLang.EN_OR_OTHER)).thenReturn(fulltext);
        when(fulltext.isAvailable()).thenReturn(true);
        when(fulltext.getName()).thenReturn("test-fulltext");

        CountDownLatch embeddingStarted = new CountDownLatch(1);
        CountDownLatch releaseEmbedding = new CountDownLatch(1);
        CountDownLatch fulltextCompleted = new CountDownLatch(1);
        float[] queryVector = new float[1024];
        Arrays.fill(queryVector, 0.1f);
        when(embeddingModel.embed("coverage")).thenAnswer(invocation -> {
            embeddingStarted.countDown();
            assertTrue(releaseEmbedding.await(2, TimeUnit.SECONDS));
            return queryVector;
        });
        RetrievalResult fulltextResult = RetrievalUtils.createResult(
                "fulltext-doc", "fulltext match", 0, 0.8);
        fulltextResult.setCollectionId(1L);
        when(fulltext.searchInScopeDetailed(
                anyString(), any(RetrievalScope.class), any(), anyInt(),
                anyDouble(), anyLong(), any(RetrievalFilters.class)))
                .thenAnswer(invocation -> {
                    fulltextCompleted.countDown();
                    return FulltextSearchProvider.SearchResult.success(
                            List.of(fulltextResult));
                });

        ExecutorService searchPool = Executors.newFixedThreadPool(4);
        ExecutorService embeddingPool = Executors.newSingleThreadExecutor();
        ExecutorService coordinator = Executors.newSingleThreadExecutor();
        try {
            HybridRetrieverService service = new HybridRetrieverService(
                    embeddingModel,
                    () -> new com.springairag.core.config.EmbeddingProfile(
                            1L, "test", "test", "test", "test", 1024,
                            "COSINE", "PROVIDER_DEFAULT", true),
                    jdbcTemplate,
                    properties,
                    factory,
                    searchPool);
            service.setCoverageEmbeddingExecutor(embeddingPool);
            RetrievalScope scope = RetrievalScope.selectedCollections(
                    List.of(1L), List.of(), null,
                    CollectionCoverageMode.EACH_COLLECTION);
            RetrievalConfig config = RetrievalConfig.builder()
                    .maxResults(1)
                    .useHybridSearch(true)
                    .useRerank(false)
                    .build();

            Future<RetrievalOutcome> outcome = coordinator.submit(() ->
                    service.searchInScopeDetailed(
                            "coverage", scope, null, 1, config,
                            RetrievalFilters.none()));

            assertTrue(embeddingStarted.await(1, TimeUnit.SECONDS));
            assertTrue(fulltextCompleted.await(1, TimeUnit.SECONDS),
                    "full-text must not wait for the blocked embedding call");
            releaseEmbedding.countDown();
            assertEquals(List.of("fulltext-doc"), outcome.get(2, TimeUnit.SECONDS)
                    .results().stream().map(RetrievalResult::getDocumentId).toList());
            verify(embeddingModel, times(1)).embed("coverage");
        } finally {
            releaseEmbedding.countDown();
            coordinator.shutdownNow();
            embeddingPool.shutdownNow();
            searchPool.shutdownNow();
        }
    }

    private Map<String, Object> row(
            String documentId, long collectionId, float[] embedding) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", collectionId);
        row.put("document_id", documentId);
        row.put("chunk_index", 0);
        row.put("chunk_text", documentId);
        row.put("embedding", embedding);
        row.put("metadata", null);
        row.put("document_title", documentId);
        row.put("document_source", null);
        row.put("original_filename", null);
        row.put("collection_id", collectionId);
        return row;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> collectionCoverage(RetrievalOutcome outcome) {
        return (Map<String, Object>) outcome.scopeSummary()
                .get("collectionCoverage");
    }
}
