package com.springairag.core.rag;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.RetrievalBranchStage;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectRerankPostProcessorTest {

    @Test
    void rerankReplacesRetrievalOutcomeInsteadOfAppendingAnotherOne() {
        ReRankingService rerankingService = mock(ReRankingService.class);
        RetrievalResult result = result("41", 0.9);
        when(rerankingService.rerank(
                eq("query"), anyList(), anyInt()))
                .thenReturn(List.of(result));

        RetrievalDocumentMapper mapper = new RetrievalDocumentMapper();
        ProjectRerankPostProcessor processor =
                new ProjectRerankPostProcessor(rerankingService, mapper);
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(), "chat", "session-1");
        RetrievalTraceCollector trace = session.newAttemptCollector(
                "attempt-1", 3, 3, 10);
        RetrievalOutcome initial = new RetrievalOutcome(
                null,
                List.of(result),
                "query",
                List.of(new RetrievalOutcome.QueryStat(0, 5)),
                Map.of(),
                Map.of(),
                List.of(
                        new RetrievalBranchStage(
                                RetrievalBranchStage.VECTOR,
                                "embedding",
                                RetrievalBranchStage.SUCCESS,
                                1,
                                1,
                                1,
                                null),
                        new RetrievalBranchStage(
                                RetrievalBranchStage.FULLTEXT,
                                "none",
                                RetrievalBranchStage.DISABLED,
                                0,
                                0,
                                0,
                                null)),
                null,
                null,
                "RESULTS_RETURNED",
                null,
                1,
                1);
        trace.recordOutcome(initial);

        AuthorizedRetrievalContext context = new AuthorizedRetrievalContext(
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.0, true, true, 0.5, 0.5),
                trace,
                "session-1",
                ChatPrincipal.local(),
                10_000,
                RetrievalFilters.none());
        Query query = new Query(
                "query",
                List.of(),
                Map.of(ProjectDocumentRetriever.CONTEXT_KEY, context));

        List<Document> output = processor.process(
                query, List.of(mapper.toDocument(result)));

        assertEquals(1, output.size());
        assertEquals(1, session.retrievals().size());
        RetrievalOutcome latest = session.latestOutcome();
        assertNotNull(latest);
        assertEquals(RetrievalBranchStage.SUCCESS,
                latest.vectorStage().status());
        assertNotNull(latest.rerankStage());
        assertEquals(1, latest.effectiveQueries().size());
        assertEquals(5, latest.effectiveQueries().getFirst().charCount());
    }

    private RetrievalResult result(String documentId, double score) {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(documentId);
        result.setChunkIndex(0);
        result.setChunkText("matched content");
        result.setTitle("title");
        result.setScore(score);
        result.setVectorScore(score);
        return result;
    }
}
