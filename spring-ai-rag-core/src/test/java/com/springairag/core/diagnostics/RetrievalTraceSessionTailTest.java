package com.springairag.core.diagnostics;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.retrieval.RetrievalOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RetrievalTraceSession 长尾（Batch 587，JaCoCo 驱动）：空结果忽略、
 * 未知 attempt 的降级路径、previous 为空的追加语义、默认 attempt 名、
 * 查询原文为空时的投影策略。
 */
class RetrievalTraceSessionTailTest {

    @Test
    void recordRetrievalIgnoresNullOutcome() {
        RetrievalTraceSession session = session();

        session.recordRetrieval("a1", null);

        assertTrue(session.retrievals().isEmpty());
        assertFalse(session.budgetExhausted());
    }

    @Test
    void replaceRetrievalWithUnknownAttemptOnlyTouchesGlobalList() {
        RetrievalTraceSession session = session();
        RetrievalOutcome first = outcome("q1", 1);
        session.newAttemptCollector("a1", 3, 2, 10);
        session.recordRetrieval("a1", first);
        RetrievalOutcome replacement = outcome("q2", 2);

        session.replaceRetrieval("ghost", first, replacement);

        assertEquals(List.of(replacement), session.retrievals());
        // attempt 未命中：attempt 内的检索记录保持不变。
        List<Map<String, Object>> attemptRetrievals = attemptRetrievals(session, "a1");
        assertEquals(1, attemptRetrievals.size());
        assertEquals(1, attemptRetrievals.get(0).get("resultCount"));
    }

    @Test
    void replaceRetrievalWithNullPreviousAppendsAtBothLevels() {
        RetrievalTraceSession session = session();
        session.newAttemptCollector("a1", 3, 2, 10);
        RetrievalOutcome existing = outcome("q1", 1);
        session.recordRetrieval("a1", existing);
        RetrievalOutcome appended = outcome("q9", 9);

        // previous 为 null：无法匹配，既追加到全局也追加到 attempt。
        session.replaceRetrieval("a1", null, appended);

        assertEquals(List.of(existing, appended), session.retrievals());
        assertEquals(2, attemptRetrievals(session, "a1").size());
    }

    @Test
    void queryExpansionAndDocumentJoinWithUnknownAttemptAreIgnored() {
        RetrievalTraceSession session = session();
        session.newAttemptCollector("a1", 3, 2, 10);

        session.recordQueryExpansion("ghost", Map.of("expanded", 3));
        session.recordDocumentJoin("ghost", Map.of("joined", 2));

        Map<String, Object> attempt = attemptMap(session, "a1");
        assertFalse(attempt.containsKey("queryExpansion"));
        assertFalse(attempt.containsKey("documentJoin"));
    }

    @Test
    void nullAttemptKeyFallsBackToDefaultAttemptName() {
        RetrievalTraceSession session = session();

        session.newAttemptCollector(null, 3, 2, 10);

        Map<String, Object> attempt = attemptMap(session, "attempt");
        assertEquals("attempt", attempt.get("key"));
        assertEquals("RUNNING", attempt.get("status"));
    }

    @Test
    void toMetadataWithQueryTextSkipsNullOriginalQuery() {
        RetrievalTraceSession session = session();
        session.newAttemptCollector("a1", 3, 2, 10);
        RetrievalOutcome noQuery = new RetrievalOutcome(
                UUID.randomUUID(),
                List.of(new RetrievalResult()),
                null,
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                null,
                null,
                "RESULTS_RETURNED",
                null,
                10L,
                1);
        session.recordRetrieval("a1", noQuery);

        Map<String, Object> metadata = session.toMetadata(true);

        List<Map<String, Object>> retrievals = attemptRetrievals(session, "a1");
        assertTrue(metadata.containsKey("attempts"));
        assertFalse(retrievals.get(0).containsKey("query"));
    }

    private RetrievalTraceSession session() {
        return new RetrievalTraceSession(
                ChatPrincipal.local(), "CHAT", "session-tail");
    }

    private RetrievalOutcome outcome(String originalQuery, int resultCount) {
        List<RetrievalResult> results = new ArrayList<>();
        for (int index = 0; index < resultCount; index++) {
            results.add(new RetrievalResult());
        }
        return new RetrievalOutcome(
                UUID.randomUUID(),
                results,
                originalQuery,
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                null,
                null,
                resultCount > 0 ? "RESULTS_RETURNED" : "NO_CANDIDATES",
                resultCount > 0 ? null : "NO_CANDIDATES",
                10L,
                resultCount);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> attemptMap(RetrievalTraceSession session, String key) {
        List<Map<String, Object>> attempts =
                (List<Map<String, Object>>) session.toMetadata(false).get("attempts");
        return attempts.stream()
                .filter(attempt -> key.equals(attempt.get("key")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> attemptRetrievals(
            RetrievalTraceSession session, String key) {
        return (List<Map<String, Object>>) attemptMap(session, key).get("retrievals");
    }
}
