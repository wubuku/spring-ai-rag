package com.springairag.core.chat;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.retrieval.RetrievalOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RetrievalTraceCollector 长尾（Batch 518，JaCoCo 驱动）：重载与
 * 空值守卫路径、预算耗尽上报、查询展开结果回写、缓存命中判定。
 */
class RetrievalTraceCollectorTailTest {

    private static RetrievalResult result(String documentId) {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(documentId);
        result.setChunkIndex(0);
        result.setChunkText("text-" + documentId);
        result.setScore(0.5);
        return result;
    }

    @Test
    void parentSessionAndAttemptKeyAreExposed() {
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(), "chat", "session-trace");
        RetrievalTraceCollector trace =
                session.newAttemptCollector("attempt-9", 3, 3, 10);

        assertSame(session, trace.parentSession());
        assertEquals("attempt-9", trace.attemptKey());
    }

    @Test
    void budgetExhaustionIsReportedThroughLastFlag() {
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(), "chat", "session-trace");
        RetrievalTraceCollector trace =
                session.newAttemptCollector("attempt-1", 1, 3, 10);

        assertTrue(trace.tryBeginRetrieval("q1"));
        assertFalse(trace.lastBudgetExhausted());
        assertFalse(trace.tryBeginRetrieval("q2"));
        assertTrue(trace.lastBudgetExhausted());
        assertEquals(1, trace.retrievalCalls());
    }

    @Test
    void queryExpansionOutcomeUpdatesConfiguredSummary() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector();
        // 未 configure 时记录结果是 no-op。
        trace.recordQueryExpansionOutcome(2, true);
        assertNull(trace.queryExpansion());

        trace.configureQueryExpansion(3, 2, true, 4, 3, false);
        trace.recordQueryExpansionOutcome(2, true);

        assertEquals(2, trace.queryExpansion().get("duplicateVariantsRemoved"));
        assertEquals(true, trace.queryExpansion().get("degraded"));
    }

    @Test
    void nullOutcomesAreIgnoredByAllRecordPaths() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector();

        trace.recordOutcome((RetrievalOutcome) null, 1);
        trace.recordCandidateOutcome(null);
        assertNull(trace.latestOutcome());
    }

    @Test
    void singleArgOutcomeOverloadRecordsLatestOutcome() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector();
        RetrievalOutcome outcome = RetrievalOutcome.ofResults(
                List.of(result("1")));

        trace.recordOutcome(outcome);

        assertNotNull(trace.latestOutcome());
        // record 已登记 source citation；sources() 只暴露 markExposed 后的键。
        assertEquals("S1", trace.citationId(result("1")));
    }

    @Test
    void rerankWithoutPreviousOutcomeSynthesizesBase() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector();

        trace.recordRerank(null, List.of(result("7")), true);

        assertNotNull(trace.latestOutcome());
        assertEquals("S1", trace.citationId(result("7")));
    }

    @Test
    void repeatedQueryDetectionUsesCacheAndNormalizes() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector();
        trace.record(" what is  rag ?  ", List.of(result("1")));

        assertTrue(trace.isRepeatedQuery("what is rag ?"));
        assertFalse(trace.isRepeatedQuery("different"));
        assertEquals(1, trace.cachedCoverageLimit("what is rag ?"));
    }

    @Test
    void blankOrUnknownCacheLookupsReturnNull() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector();

        assertNull(trace.cachedResults(null));
        assertNull(trace.cachedResults("   "));
        assertNull(trace.cachedResults("unknown"));
    }

    @Test
    void listOverloadRecordsSourcesWithoutQueryCache() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector();

        trace.record((List<RetrievalResult>) null);
        assertNull(trace.citationId(result("1")));

        trace.record(List.of(result("3")));
        assertEquals("S1", trace.citationId(result("3")));
        assertFalse(trace.isRepeatedQuery("anything"));
    }

    @Test
    void citationAndExposureGuardsTolerateNullInputs() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector();

        assertNull(trace.citationId(null));
        trace.markExposed(null);
        assertTrue(trace.sources().isEmpty());
    }

    @Test
    void normalizeQueryHandlesNullAndWhitespace() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector();

        assertEquals("", trace.normalizeQuery(null));
        assertEquals("a b", trace.normalizeQuery("  a   b  "));
    }
}
