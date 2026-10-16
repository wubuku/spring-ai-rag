package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagRerankProperties;
import com.springairag.core.retrieval.rerank.RerankProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectionCoverageRerankerTest {

    @Test
    void reranksTheWholeCandidatePoolThenRetainsEveryCollection() {
        RagRerankProperties config = new RagRerankProperties();
        config.setEnabled(true);
        RerankProvider provider = mock(RerankProvider.class);
        when(provider.getName()).thenReturn("test-reranker");
        ReRankingService service = new ReRankingService(config, provider);
        List<RetrievalResult> candidates = List.of(
                result("doc-a", 0.90, 1L),
                result("doc-a-2", 0.70, 1L),
                result("doc-b", 0.80, 2L),
                result("doc-c", 0.60, 3L));
        List<RetrievalResult> ranked = List.of(
                candidates.get(1), candidates.get(0),
                candidates.get(3), candidates.get(2));
        when(provider.rerank("query", candidates, candidates.size()))
                .thenReturn(ranked);

        CollectionCoverageReranker.Result result =
                CollectionCoverageReranker.rerank(
                        "query", candidates, List.of(1L, 2L, 3L), 3, service);

        assertFalse(result.degraded());
        assertTrue(result.executed());
        assertEquals(List.of("doc-a-2", "doc-b", "doc-c"),
                result.results().stream().map(RetrievalResult::getDocumentId).toList());
        verify(provider).rerank("query", candidates, candidates.size());
    }

    @Test
    void incompleteProviderOutputFallsBackToOriginalPoolAndKeepsCoverage() {
        RagRerankProperties config = new RagRerankProperties();
        config.setEnabled(true);
        RerankProvider provider = mock(RerankProvider.class);
        when(provider.getName()).thenReturn("test-reranker");
        ReRankingService service = new ReRankingService(config, provider);
        List<RetrievalResult> candidates = List.of(
                result("doc-a", 0.90, 1L),
                result("doc-b", 0.80, 2L),
                result("doc-c", 0.70, 1L));
        when(provider.rerank("query", candidates, candidates.size()))
                .thenReturn(List.of(candidates.get(2), candidates.get(0)));

        CollectionCoverageReranker.Result result =
                CollectionCoverageReranker.rerank(
                        "query", candidates, List.of(1L, 2L), 2, service);

        assertTrue(result.degraded());
        assertEquals("RERANK_INCOMPLETE", result.errorCode());
        assertEquals(List.of(1L, 2L), result.results().stream()
                .map(RetrievalResult::getCollectionId).toList());
    }

    @Test
    void noopProviderSkipsInvocationButStillAppliesCoverage() {
        RagRerankProperties config = new RagRerankProperties();
        config.setEnabled(true);
        RerankProvider provider = mock(RerankProvider.class);
        when(provider.getName()).thenReturn("noop");
        ReRankingService service = new ReRankingService(config, provider);
        List<RetrievalResult> candidates = List.of(
                result("doc-a", 0.90, 1L),
                result("doc-b", 0.80, 2L));

        CollectionCoverageReranker.Result result =
                CollectionCoverageReranker.rerank(
                        "query", candidates, List.of(1L, 2L), 2, service);

        assertFalse(result.degraded());
        assertFalse(result.executed());
        assertEquals(2, result.results().size());
        verify(provider, never()).rerank("query", candidates, candidates.size());
    }

    @Test
    void fillsRemainingSlotsInProviderRankOrderAfterCoveragePass() {
        List<RetrievalResult> ranked = List.of(
                result("a-top", 0.40, 1L),
                result("b-top", 0.90, 2L),
                result("b-next", 0.80, 2L),
                result("a-next", 0.10, 1L));

        List<RetrievalResult> selected = CollectionCoverageMerger
                .retainCoverageInRankOrder(ranked, List.of(1L, 2L), 3);

        assertEquals(List.of("a-top", "b-top", "b-next"),
                selected.stream().map(RetrievalResult::getDocumentId).toList());
    }

    private RetrievalResult result(String documentId, double score, long collectionId) {
        RetrievalResult result = RetrievalUtils.createResult(
                documentId, documentId, 0, score);
        result.setCollectionId(collectionId);
        return result;
    }
}
