package com.springairag.core.retrieval;

import com.springairag.api.enums.CollectionCoverageMode;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.retrieval.RetrievalOutcome;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalScopeSummaryTest {

    @Test
    void omitsNullCollectionCountForUnboundedScope() {
        Map<String, Object> summary = RetrievalScopeSummary.from(
                null,
                RetrievalScope.unscoped(),
                List.of("alpha"),
                RetrievalFilters.none(),
                null);

        assertFalse(summary.containsKey("collectionCount"));
        assertFalse(summary.containsKey("documentType"));
        assertEquals("CALLER_VISIBLE", summary.get("collectionScopeMode"));
        assertEquals(Boolean.FALSE, summary.get("matchNone"));
    }

    @Test
    void attachScopeIgnoresNullMapValues() {
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(),
                RetrievalTraceHeaders.OPERATION_SEARCH,
                null);
        Map<String, Object> dirty = new HashMap<>();
        dirty.put("collectionScopeMode", "ANY_COLLECTION");
        dirty.put("collectionCount", null);
        dirty.put("documentType", null);
        dirty.put("documentIdCount", 1);

        session.attachScope(dirty, RetrievalFilters.none());

        Map<String, Object> attached = session.scopeSummary();
        assertEquals("ANY_COLLECTION", attached.get("collectionScopeMode"));
        assertEquals(1, attached.get("documentIdCount"));
        assertFalse(attached.containsKey("collectionCount"));
        assertFalse(attached.containsKey("documentType"));
        assertTrue(attached.containsValue("ANY_COLLECTION"));
    }

    @Test
    void countsOnlyTopLevelObjectFields() {
        assertEquals(
                2,
                RetrievalScopeSummary.countTopLevelKeys(
                        "{\"tenant\":\"a\",\"nested\":{\"value\":\"b\"}}"));
    }

    @Test
    void queryStatsDoNotCountOriginalAndEffectiveQueryTwice() {
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(),
                RetrievalTraceHeaders.OPERATION_SEARCH,
                null);
        RetrievalOutcome outcome = new RetrievalOutcome(
                null,
                List.of(),
                "original",
                List.of(new RetrievalOutcome.QueryStat(0, 9)),
                Map.of(),
                Map.of(),
                List.of(),
                null,
                null,
                RetrievalOutcomeCodes.NO_CANDIDATES,
                RetrievalOutcomeCodes.NO_CANDIDATES,
                0,
                0);

        session.recordRetrieval(null, outcome);

        @SuppressWarnings("unchecked")
        Map<String, Object> stats =
                (Map<String, Object>) session.toMetadata(false).get("queryStats");
        assertEquals(1, stats.get("count"));
        assertEquals(9, stats.get("charCount"));
    }

    @Test
    void collectionCoverageSummaryContainsOnlyCountsAndAuthorizedIds() {
        Map<String, Object> summary = RetrievalScopeSummary.withCollectionCoverage(
                Map.of("collectionScopeMode", "SELECTED_COLLECTIONS"),
                CollectionCoverageMode.EACH_COLLECTION,
                List.of(7L, 8L, 9L),
                Map.of(7L, 2, 8L, 0, 9L, 1));

        @SuppressWarnings("unchecked")
        Map<String, Object> coverage =
                (Map<String, Object>) summary.get("collectionCoverage");
        assertEquals(3, coverage.get("requestedCount"));
        assertEquals(2, coverage.get("returnedCount"));
        assertEquals(List.of(8L), coverage.get("missingCollectionIds"));
        assertEquals(Map.of("7", 2, "8", 0, "9", 1),
                coverage.get("perCollectionResultCounts"));
    }

    @Test
    void rerankRecomputesCoverageCountsAndPreservesBranchDiagnostics() {
        Map<String, Object> initialSummary = RetrievalScopeSummary.withCollectionCoverage(
                Map.of("collectionScopeMode", "SELECTED_COLLECTIONS"),
                CollectionCoverageMode.EACH_COLLECTION,
                List.of(7L, 8L),
                Map.of(7L, 2, 8L, 1),
                List.of(8L),
                Map.of(7L, Map.of("vector", "SUCCESS", "fulltext", "SUCCESS"),
                        8L, Map.of("vector", "TIMEOUT", "fulltext", "SUCCESS")),
                true);
        RetrievalResult onlySeven = RetrievalUtils.createResult(
                "doc-7", "evidence", 0, 0.9);
        onlySeven.setCollectionId(7L);
        RetrievalOutcome initial = new RetrievalOutcome(
                null, List.of(onlySeven), "query", List.of(), initialSummary,
                Map.of(), List.of(), null, null,
                RetrievalOutcomeCodes.RESULTS_RETURNED, null, 0, 3);

        RetrievalOutcome reranked = initial.withRerank(
                new RetrievalBranchStage(
                        RetrievalBranchStage.RERANK, "rerank",
                        RetrievalBranchStage.ERROR, 1, 3, 1,
                        "RERANK_INCOMPLETE"),
                List.of(onlySeven),
                true);

        @SuppressWarnings("unchecked")
        Map<String, Object> coverage = (Map<String, Object>) reranked.scopeSummary()
                .get("collectionCoverage");
        assertEquals(1, coverage.get("returnedCount"));
        assertEquals(List.of(8L), coverage.get("missingCollectionIds"));
        assertEquals(List.of(8L), coverage.get("incompleteCollectionIds"));
        assertEquals(true, coverage.get("degraded"));
        assertEquals(true, coverage.get("rerankDegraded"));
        assertEquals(Map.of("7", 1, "8", 0),
                coverage.get("perCollectionResultCounts"));
        assertEquals(1, reranked.results().size());
    }
}
