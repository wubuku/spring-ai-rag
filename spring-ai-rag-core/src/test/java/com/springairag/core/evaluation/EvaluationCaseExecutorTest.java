package com.springairag.core.evaluation;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationCaseExecutorTest {

    @Test
    void rerankVariantUsesFinalRerankService() {
        HybridRetrieverService retriever = mock(HybridRetrieverService.class);
        ReRankingService reranking = mock(ReRankingService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        List<RetrievalResult> candidates = List.of(
                result("not-numeric-1"), result("not-numeric-2"));
        when(retriever.searchInScopeDetailed(
                anyString(), any(), anyList(), eq(2), any(), any()))
                .thenReturn(RetrievalOutcome.ofResults(candidates));
        when(reranking.rerank(anyString(), eq(candidates), eq(2)))
                .thenReturn(List.of(candidates.get(1), candidates.get(0)));

        EvaluationCaseExecutor executor =
                new EvaluationCaseExecutor(retriever, reranking, jdbcTemplate);
        executor.search(
                "query",
                RetrievalScope.unscoped(),
                RetrievalConfig.builder().maxResults(2).useRerank(true).build(),
                RetrievalFilters.none());

        verify(reranking).rerank("query", candidates, 2);
    }

    @Test
    void nonRerankVariantDoesNotCallRerankService() {
        HybridRetrieverService retriever = mock(HybridRetrieverService.class);
        ReRankingService reranking = mock(ReRankingService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        List<RetrievalResult> candidates = List.of(result("not-numeric"));
        when(retriever.searchInScopeDetailed(
                anyString(), any(), anyList(), eq(1), any(), any()))
                .thenReturn(RetrievalOutcome.ofResults(candidates));

        EvaluationCaseExecutor executor =
                new EvaluationCaseExecutor(retriever, reranking, jdbcTemplate);
        executor.search(
                "query",
                RetrievalScope.unscoped(),
                RetrievalConfig.builder().maxResults(1).useRerank(false).build(),
                RetrievalFilters.none());

        verify(reranking, never()).rerank(anyString(), anyList(), eq(1));
    }

    @Test
    void rerankFailurePropagatesSoEvaluationCannotReportUnrankedCandidates() {
        HybridRetrieverService retriever = mock(HybridRetrieverService.class);
        ReRankingService reranking = mock(ReRankingService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        List<RetrievalResult> candidates = List.of(result("not-numeric"));
        when(retriever.searchInScopeDetailed(
                anyString(), any(), anyList(), eq(1), any(), any()))
                .thenReturn(RetrievalOutcome.ofResults(candidates));
        when(reranking.rerank("query", candidates, 1))
                .thenThrow(new IllegalStateException("rerank unavailable"));
        EvaluationCaseExecutor executor =
                new EvaluationCaseExecutor(retriever, reranking, jdbcTemplate);

        assertThrows(
                IllegalStateException.class,
                () -> executor.search(
                        "query",
                        RetrievalScope.unscoped(),
                        RetrievalConfig.builder()
                                .maxResults(1)
                                .useRerank(true)
                                .build(),
                        RetrievalFilters.none()));
    }

    private RetrievalResult result(String id) {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(id);
        result.setChunkIndex(0);
        result.setChunkText("content " + id);
        result.setScore(0.8);
        return result;
    }
}
