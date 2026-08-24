package com.springairag.core.chat;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.retrieval.RetrievalBranchStage;
import com.springairag.core.retrieval.RetrievalOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalTraceCollectorTest {

    @Test
    void candidateOutcomeDoesNotConsumeSourceBudgetAndFinalOutcomeReplacesIt() {
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(), "chat", "session-trace");
        RetrievalTraceCollector trace = session.newAttemptCollector(
                "attempt-1", 3, 3, 1);
        RetrievalResult first = result("1");
        RetrievalResult selected = result("2");
        RetrievalOutcome candidate = RetrievalOutcome.ofResults(
                List.of(first, selected));

        trace.recordCandidateOutcome(candidate);
        assertNull(trace.citationId(first));
        assertNull(trace.citationId(selected));

        trace.recordRerank(
                new RetrievalBranchStage(
                        RetrievalBranchStage.RERANK,
                        "heuristic",
                        RetrievalBranchStage.SUCCESS,
                        1,
                        2,
                        1,
                        null),
                List.of(selected),
                false,
                "query",
                1);

        assertEquals("S1", trace.citationId(selected));
        trace.markExposed(List.of(selected));
        assertEquals(1, trace.sources().size());
        assertEquals(1, session.retrievals().size());
        assertEquals(1, session.latestOutcome().results().size());
        assertEquals("2", session.latestOutcome().results().getFirst().getDocumentId());
    }

    @Test
    void cacheCoverageAllowsSmallerRequestsButMissesLargerRequests() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector(3, 3, 10);
        List<RetrievalResult> results = List.of(result("1"), result("2"));

        trace.record("query", results, 4);

        assertEquals(1, trace.cachedResults("query", 1).size());
        assertEquals(2, trace.cachedResults("query", 4).size());
        assertNull(trace.cachedResults("query", 5));
        assertEquals(4, trace.cachedCoverageLimit("query"));
    }

    @Test
    void queryExpansionSummaryIsSharedWithPersistedAttemptMetadata() {
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(), "chat", "session-expansion");
        RetrievalTraceCollector trace = session.newAttemptCollector(
                "attempt-1", 3, 3, 10);
        trace.configureQueryExpansion(5, 2, true, 3, 3, true);
        trace.recordQueryExpansionOutcome(1, false);

        assertEquals(
                trace.queryExpansion(),
                trace.summary().get("queryExpansion"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attempts =
                (List<Map<String, Object>>) session.toMetadata(false).get("attempts");
        assertEquals(
                trace.queryExpansion(),
                attempts.getFirst().get("queryExpansion"));
        assertTrue(!attempts.getFirst().toString().contains("原始问题"));
    }

    @Test
    void documentJoinSummaryIsSharedWithPersistedAttemptMetadata() {
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(), "chat", "session-document-join");
        RetrievalTraceCollector trace = session.newAttemptCollector(
                "attempt-1", 3, 3, 10);
        Map<String, Object> summary = Map.of(
                "inputDocuments", 9,
                "uniqueDocuments", 6,
                "duplicateDocumentsRemoved", 3,
                "scoreReplacements", 2);

        trace.recordDocumentJoin(9, 6, 2);

        assertEquals(summary, trace.documentJoin());
        assertEquals(
                summary,
                trace.summary().get("documentJoin"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attempts =
                (List<Map<String, Object>>) session.toMetadata(false).get("attempts");
        assertEquals(
                summary,
                attempts.getFirst().get("documentJoin"));
        assertTrue(!attempts.getFirst().toString().contains("document-123"));
    }

    @Test
    void documentJoinRejectsInconsistentCounts() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector();

        assertThrows(
                IllegalArgumentException.class,
                () -> trace.recordDocumentJoin(2, 3, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> trace.recordDocumentJoin(2, 1, 2));
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
