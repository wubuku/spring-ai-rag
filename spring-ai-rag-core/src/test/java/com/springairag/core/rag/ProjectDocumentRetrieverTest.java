package com.springairag.core.rag;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectDocumentRetrieverTest {

    @Test
    void rerankCandidateStageDoesNotConsumeCitationBudget() {
        HybridRetrieverService hybridRetriever = mock(HybridRetrieverService.class);
        RetrievalResult first = result("41");
        RetrievalResult second = result("42");
        RetrievalOutcome outcome = RetrievalOutcome.ofResults(List.of(first, second));
        RetrievalScope scope = RetrievalScope.unscoped();
        when(hybridRetriever.searchInScopeDetailed(
                eq("query"),
                same(scope),
                isNull(),
                eq(1),
                any(RetrievalConfig.class),
                eq(RetrievalFilters.none())))
                .thenReturn(outcome);
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(), "chat", "session-retriever");
        RetrievalTraceCollector trace = session.newAttemptCollector(
                "attempt-1", 3, 3, 1);
        AuthorizedRetrievalContext context = context(scope, trace, true);
        ProjectDocumentRetriever retriever = new ProjectDocumentRetriever(
                hybridRetriever, new RetrievalDocumentMapper());

        List<Document> documents = retriever.retrieve(new Query(
                "query",
                List.of(),
                Map.of(ProjectDocumentRetriever.CONTEXT_KEY, context)));

        assertEquals(2, documents.size());
        assertNull(trace.citationId(first));
        assertNull(trace.citationId(second));
        assertEquals(1, session.retrievals().size());
        assertEquals(2, session.latestOutcome().results().size());
        verify(hybridRetriever).searchInScopeDetailed(
                eq("query"),
                same(scope),
                isNull(),
                eq(1),
                any(RetrievalConfig.class),
                eq(RetrievalFilters.none()));
    }

    @Test
    void nonRerankResultBecomesFinalCitableOutcome() {
        HybridRetrieverService hybridRetriever = mock(HybridRetrieverService.class);
        RetrievalResult result = result("41");
        RetrievalScope scope = RetrievalScope.unscoped();
        when(hybridRetriever.searchInScopeDetailed(
                eq("query"),
                same(scope),
                isNull(),
                eq(1),
                any(RetrievalConfig.class),
                eq(RetrievalFilters.none())))
                .thenReturn(outcome("query", List.of(result)));
        RetrievalTraceCollector trace = new RetrievalTraceCollector(3, 3, 1);
        ProjectDocumentRetriever retriever = new ProjectDocumentRetriever(
                hybridRetriever, new RetrievalDocumentMapper());

        List<Document> documents = retriever.retrieve(new Query(
                "query",
                List.of(),
                Map.of(
                        ProjectDocumentRetriever.CONTEXT_KEY,
                        context(scope, trace, false))));

        assertEquals(1, documents.size());
        assertEquals("S1", trace.citationId(result));
        assertEquals(1, trace.cachedCoverageLimit("query"));
    }

    private AuthorizedRetrievalContext context(
            RetrievalScope scope,
            RetrievalTraceCollector trace,
            boolean useRerank) {
        return new AuthorizedRetrievalContext(
                scope,
                new RetrievalOptions(1, 0.0, false, useRerank, 1.0, 0.0),
                trace,
                "session-retriever",
                ChatPrincipal.local(),
                10_000,
                RetrievalFilters.none());
    }

    private RetrievalResult result(String documentId) {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(documentId);
        result.setChunkIndex(0);
        result.setTitle("title " + documentId);
        result.setChunkText("content " + documentId);
        result.setScore(0.8);
        result.setVectorScore(0.8);
        return result;
    }

    private RetrievalOutcome outcome(
            String query,
            List<RetrievalResult> results) {
        return new RetrievalOutcome(
                UUID.randomUUID(),
                results,
                query,
                List.of(new RetrievalOutcome.QueryStat(0, query.length())),
                Map.of(),
                Map.of(),
                List.of(),
                null,
                null,
                "RESULTS_RETURNED",
                null,
                0,
                results.size());
    }
}
