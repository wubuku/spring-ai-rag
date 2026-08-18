package com.springairag.core.retrieval;

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
}
