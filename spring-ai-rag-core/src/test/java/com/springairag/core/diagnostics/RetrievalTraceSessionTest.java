package com.springairag.core.diagnostics;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖父级检索诊断会话：默认值、scope 附加、attempt 生命周期、
 * 检索替换、预算标记与 metadata 投影。
 */
class RetrievalTraceSessionTest {

    @Test
    void defaultsResolveNullPrincipalAndBlankOperation() {
        RetrievalTraceSession session = new RetrievalTraceSession(null, "  ", "s-1");

        assertEquals(ChatPrincipal.local().id(), session.ownerPrincipalId());
        assertEquals("SEARCH", session.operation());
        assertEquals("s-1", session.sessionId());
        assertNotNull(session.traceId());
        assertNotNull(session.createdAt());
        assertFalse(session.budgetExhausted());
        assertNull(session.latestOutcome());
        assertNull(session.citationValidation());
    }

    @Test
    void attachScopeFiltersNullEntriesAndSkipsNullInputs() {
        RetrievalTraceSession session = new RetrievalTraceSession(null, null, null);
        Map<String, Object> summary = new HashMap<>();
        summary.put("ok", "value");
        summary.put("nullValue", null);
        summary.put(null, "orphan");

        session.attachScope(summary, null);

        assertEquals(Map.of("ok", "value"), session.scopeSummary());
        assertEquals(RetrievalFilters.none(), session.filters());

        session.attachScope(null, null);

        assertEquals(Map.of("ok", "value"), session.scopeSummary());
    }

    @Test
    void attemptLifecycleReportsRunningThenSucceededThenFailed() {
        RetrievalTraceSession session = session();
        session.newAttemptCollector("a1", 3, 2, 10);

        assertEquals("RUNNING", attemptStatus(session, "a1"));

        session.markAttemptFinished("a1", true, "model-x");

        assertEquals("SUCCEEDED", attemptStatus(session, "a1"));

        session.markAttemptFinished("a1", false, null);

        assertEquals("FAILED", attemptStatus(session, "a1"));
        assertFalse(attemptMap(session, "a1").containsKey("modelRef"));

        session.markAttemptFinished("ghost", true, "model-y");

        assertEquals("FAILED", attemptStatus(session, "a1"));
    }

    @Test
    void nullAttemptKeyTargetsLatestAttempt() {
        RetrievalTraceSession session = session();
        session.newAttemptCollector("a1", 3, 2, 10);
        session.newAttemptCollector("a2", 3, 2, 10);

        session.recordToolCall(null, "searchKnowledge", 3, 10, false);

        assertEquals(List.of(), attemptMap(session, "a1").get("toolCalls"));
        assertEquals(1, ((List<?>) attemptMap(session, "a2").get("toolCalls")).size());
    }

    @Test
    void unknownAttemptKeyStillRecordsGlobalRetrievalAndBudget() {
        RetrievalTraceSession session = session();
        RetrievalOutcome outcome = outcome("query", 1);

        session.recordRetrieval("ghost", outcome);
        session.recordToolCall("ghost", "searchKnowledge", 2, 5, true);
        session.recordBudgetExhausted("ghost", "q");

        assertEquals(1, session.retrievals().size());
        assertTrue(session.budgetExhausted());
        session.newAttemptCollector("a1", 3, 2, 10);
        assertEquals(List.of(), attemptMap(session, "a1").get("toolCalls"));
        assertFalse(attemptMap(session, "a1").containsKey("budgetExhausted"));
    }

    @Test
    void replaceRetrievalSwapsLastMatchOrAppends() {
        RetrievalTraceSession session = session();
        RetrievalOutcome first = outcome("q1", 1);
        RetrievalOutcome second = outcome("q2", 2);
        session.newAttemptCollector("a1", 3, 2, 10);
        session.recordRetrieval("a1", first);
        session.recordRetrieval("a1", second);

        RetrievalOutcome secondReplacement = outcome("q2b", 2);
        session.replaceRetrieval("a1", second, secondReplacement);
        assertEquals(List.of(first, secondReplacement), session.retrievals());

        RetrievalOutcome unknownReplacement = outcome("q3", 3);
        session.replaceRetrieval("a1", outcome("missing", 9), unknownReplacement);
        assertEquals(
                List.of(first, secondReplacement, unknownReplacement),
                session.retrievals());

        session.replaceRetrieval("a1", first, null);
        assertEquals(
                List.of(first, secondReplacement, unknownReplacement),
                session.retrievals());
    }

    @Test
    void recordToolCallMarksBudgetExhausted() {
        RetrievalTraceSession session = session();
        session.newAttemptCollector("a1", 3, 2, 10);

        session.recordToolCall("a1", "searchKnowledge", 2, 50, false);
        assertFalse(session.budgetExhausted());

        session.recordToolCall("a1", "searchKnowledge", 1, 20, true);

        assertTrue(session.budgetExhausted());
        List<Map<String, Object>> toolCalls = toolCalls(session, "a1");
        assertEquals(2, toolCalls.size());
        assertEquals(Boolean.TRUE, toolCalls.get(1).get("budgetExhausted"));
        assertEquals("searchKnowledge", toolCalls.get(1).get("tool"));
    }

    @Test
    void recordBudgetExhaustedCapturesQueryLengthOrNull() {
        RetrievalTraceSession session = session();
        session.newAttemptCollector("a1", 3, 2, 10);

        session.recordBudgetExhausted("a1", "hello");
        assertEquals(5, attemptMap(session, "a1").get("lastBudgetQueryChars"));
        assertTrue(session.budgetExhausted());

        session.recordBudgetExhausted("a1", null);
        assertEquals(0, attemptMap(session, "a1").get("lastBudgetQueryChars"));
    }

    @Test
    void recordQueryExpansionAndDocumentJoinIgnoreNull() {
        RetrievalTraceSession session = session();
        session.newAttemptCollector("a1", 3, 2, 10);

        session.recordQueryExpansion("a1", null);
        session.recordDocumentJoin("a1", null);
        assertFalse(attemptMap(session, "a1").containsKey("queryExpansion"));
        assertFalse(attemptMap(session, "a1").containsKey("documentJoin"));

        session.recordQueryExpansion("a1", Map.of("expanded", 3));
        session.recordDocumentJoin("a1", Map.of("joined", 2));

        assertEquals(Map.of("expanded", 3), attemptMap(session, "a1").get("queryExpansion"));
        assertEquals(Map.of("joined", 2), attemptMap(session, "a1").get("documentJoin"));
    }

    @Test
    void citationValidationIsSettableAndClearable() {
        RetrievalTraceSession session = session();
        session.newAttemptCollector("a1", 3, 2, 10);

        assertFalse(session.toMetadata(true).containsKey("citationValidation"));

        session.setCitationValidation(Map.of("valid", true));
        assertEquals(
                Map.of("valid", true),
                session.toMetadata(true).get("citationValidation"));

        session.setCitationValidation(null);
        assertNull(session.citationValidation());
        assertFalse(session.toMetadata(true).containsKey("citationValidation"));
    }

    @Test
    void toMetadataProjectsSchemaQueryStatsAndQueryTextPolicy() {
        RetrievalTraceSession session = session();
        session.newAttemptCollector("a1", 3, 2, 10);
        session.recordRetrieval("a1", new RetrievalOutcome(
                UUID.randomUUID(),
                List.of(new RetrievalResult()),
                "raw question",
                List.of(new RetrievalOutcome.QueryStat(0, 6),
                        new RetrievalOutcome.QueryStat(1, 4)),
                Map.of(), Map.of(), List.of(), null, null,
                "RESULTS_RETURNED", null, 30L, 1));
        session.recordRetrieval("a1", outcome("second question", 0));

        Map<String, Object> metadata = session.toMetadata(false);

        assertEquals(1, metadata.get("schemaVersion"));
        assertEquals(Boolean.FALSE, metadata.get("budgetExhausted"));
        // outcome1 走 effectiveQueries（6+4），outcome2 回退 originalQuery（15 字符）
        assertEquals(Map.of("count", 3, "charCount", 25),
                metadata.get("queryStats"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> retrievals =
                (List<Map<String, Object>>) attemptMap(session, "a1").get("retrievals");
        assertEquals(2, retrievals.size());
        assertFalse(retrievals.get(0).containsKey("query"));
        assertEquals(1, retrievals.get(0).get("resultCount"));
        assertEquals("RESULTS_RETURNED", retrievals.get(0).get("outcomeCode"));

        Map<String, Object> withQueryText = session.toMetadata(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> retrievalsWithText =
                (List<Map<String, Object>>)
                        ((List<?>) ((Map<String, Object>)
                                ((List<?>) withQueryText.get("attempts")).getFirst())
                                .get("retrievals"));
        assertEquals("raw question", retrievalsWithText.get(0).get("query"));
        assertEquals(30L, retrievalsWithText.get(0).get("elapsedMs"));
    }

    private RetrievalTraceSession session() {
        return new RetrievalTraceSession(
                ChatPrincipal.local(), "CHAT", "session-1");
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

    private String attemptStatus(RetrievalTraceSession session, String key) {
        return (String) attemptMap(session, key).get("status");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toolCalls(
            RetrievalTraceSession session, String key) {
        return (List<Map<String, Object>>) attemptMap(session, key).get("toolCalls");
    }
}
