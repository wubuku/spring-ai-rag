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
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void collectionCoverageMarksIncompleteCollectionsAsDegraded() {
        Map<String, Object> summary = RetrievalScopeSummary.withCollectionCoverage(
                Map.of("collectionScopeMode", "SELECTED_COLLECTIONS"),
                CollectionCoverageMode.EACH_COLLECTION,
                List.of(7L, 8L),
                Map.of(7L, 1, 8L, 1),
                List.of(8L),
                java.util.Map.of(8L, java.util.Map.of("vector", "TIMEOUT")),
                false);

        @SuppressWarnings("unchecked")
        Map<String, Object> coverage =
                (Map<String, Object>) summary.get("collectionCoverage");
        assertEquals(true, coverage.get("degraded"));
        assertEquals(1, coverage.get("incompleteCount"));
        assertEquals(List.of(8L), coverage.get("incompleteCollectionIds"));
        assertEquals(java.util.Map.of("8", java.util.Map.of("vector", "TIMEOUT")),
                coverage.get("collectionBranchStatuses"));
        assertEquals("SELECTED_COLLECTIONS", summary.get("collectionScopeMode"));
    }

    @Test
    void resultsOverlayRecomputesCountsAndKeepsBaseAndBranchStatuses() {
        Map<String, Object> base = Map.of("collectionScopeMode", "SELECTED_COLLECTIONS");
        Map<String, Object> initial = RetrievalScopeSummary.withCollectionCoverage(
                base,
                CollectionCoverageMode.EACH_COLLECTION,
                List.of(7L, 8L, 9L),
                Map.of(7L, 1, 8L, 0, 9L, 0),
                List.of(8L),
                java.util.Map.of(8L, java.util.Map.of("fulltext", "TIMEOUT")),
                true);
        RetrievalResult onlySeven = RetrievalUtils.createResult(
                "doc-7", "evidence", 0, 0.9);
        onlySeven.setCollectionId(7L);

        Map<String, Object> updated =
                RetrievalScopeSummary.withCollectionCoverageResults(
                        initial, List.of(onlySeven), true);

        @SuppressWarnings("unchecked")
        Map<String, Object> coverage =
                (Map<String, Object>) updated.get("collectionCoverage");
        assertEquals("SELECTED_COLLECTIONS", updated.get("collectionScopeMode"));
        assertEquals(1, coverage.get("returnedCount"));
        assertEquals(2, coverage.get("missingCount"));
        assertEquals(List.of(8L, 9L), coverage.get("missingCollectionIds"));
        assertEquals(true, coverage.get("degraded"));
        assertEquals(true, coverage.get("rerankDegraded"));
        assertEquals(java.util.Map.of("8", java.util.Map.of("fulltext", "TIMEOUT")),
                coverage.get("collectionBranchStatuses"));
    }

    @Test
    void resultsOverlayParsesStringIdsAndDropsMalformedOnes() {
        Map<String, Object> base = Map.of(
                "collectionScopeMode", "SELECTED_COLLECTIONS");
        Map<String, Object> initial = new HashMap<>(base);
        initial.put("collectionCoverage", Map.of(
                "mode", "EACH_COLLECTION",
                "requestedCollectionIds", java.util.Arrays.asList("7", null, "not-a-number", 8L),
                "incompleteCollectionIds", List.of("8"),
                "degraded", false));

        Map<String, Object> updated =
                RetrievalScopeSummary.withCollectionCoverageResults(
                        initial, List.of(), false);

        @SuppressWarnings("unchecked")
        Map<String, Object> coverage =
                (Map<String, Object>) updated.get("collectionCoverage");
        assertEquals(List.of(7L, 8L), coverage.get("requestedCollectionIds"));
        assertEquals(List.of(8L), coverage.get("incompleteCollectionIds"));
        assertEquals(true, coverage.get("degraded"));
        assertEquals("SELECTED_COLLECTIONS", updated.get("collectionScopeMode"));
    }

    @Test
    void filterSummaryReflectsNullAndNonEmptyFilters() {
        Map<String, Object> none = RetrievalScopeSummary.from(
                null,
                RetrievalScope.unscoped(),
                List.of(),
                null,
                null);
        @SuppressWarnings("unchecked")
        Map<String, Object> noneFilter =
                (Map<String, Object>) none.get("filter");
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata =
                (Map<String, Object>) noneFilter.get("metadataContains");
        assertEquals(false, metadata.get("present"));
        assertEquals(0, metadata.get("canonicalBytes"));
        assertEquals(List.of(), noneFilter.get("payloadContains"));

        RetrievalFilters filters = new RetrievalFilters(
                null,
                List.of(new JsonbContainmentFilter("{\"tenant\":\"a\"}")));
        Map<String, Object> filtered = RetrievalScopeSummary.from(
                null,
                RetrievalScope.unscoped(),
                List.of(),
                filters,
                null);
        @SuppressWarnings("unchecked")
        Map<String, Object> filterSummary =
                (Map<String, Object>) filtered.get("filter");
        assertEquals(1, ((List<?>) filterSummary.get("payloadContains")).size());
    }

    @Test
    void coverageOverlayPreservesExplicitDegradedFlagWithoutIncompleteIds() {
        Map<String, Object> degradedBase = new HashMap<>();
        degradedBase.put("collectionCoverage", new HashMap<>(Map.of(
                "mode", "EACH_COLLECTION",
                "requestedCollectionIds", List.of(7L),
                "incompleteCollectionIds", List.of(),
                "degraded", true)));
        @SuppressWarnings("unchecked")
        Map<String, Object> degraded = (Map<String, Object>)
                RetrievalScopeSummary.withCollectionCoverageResults(
                        degradedBase, List.of(), false).get("collectionCoverage");
        assertEquals(true, degraded.get("degraded"));

        Map<String, Object> cleanBase = new HashMap<>();
        cleanBase.put("collectionCoverage", new HashMap<>(Map.of(
                "mode", "EACH_COLLECTION",
                "requestedCollectionIds", List.of(7L),
                "incompleteCollectionIds", List.of(),
                "degraded", false)));
        @SuppressWarnings("unchecked")
        Map<String, Object> clean = (Map<String, Object>)
                RetrievalScopeSummary.withCollectionCoverageResults(
                        cleanBase, List.of(), false).get("collectionCoverage");
        assertEquals(false, clean.get("degraded"));

        Map<String, Object> global = RetrievalScopeSummary.withCollectionCoverage(
                Map.of("collectionScopeMode", "ANY_COLLECTION"),
                CollectionCoverageMode.GLOBAL,
                List.of(7L),
                Map.of(7L, 1));
        assertEquals(Map.of("collectionScopeMode", "ANY_COLLECTION"), global);
        assertFalse(global.containsKey("collectionCoverage"));

        Map<String, Object> withoutCoverage = new HashMap<>(
                Map.of("collectionScopeMode", "ANY_COLLECTION"));
        assertSame(withoutCoverage,
                RetrievalScopeSummary.withCollectionCoverageResults(
                        withoutCoverage, List.of(), false));
        assertEquals(Map.of(),
                RetrievalScopeSummary.withCollectionCoverageResults(
                        null, List.of(), false));
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
