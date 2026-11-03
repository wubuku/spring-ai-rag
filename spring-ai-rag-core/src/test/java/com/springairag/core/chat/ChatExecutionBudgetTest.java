package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.admission.BackgroundWorkScope;
import com.springairag.core.exception.RagException;
import com.springairag.core.http.HttpToolExecutionState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatExecutionBudgetTest {

    @Test
    void reservesToolBatchAtomicallyAndTracksPerName() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                3,
                8,
                3,
                3,
                2,
                4_000);

        budget.reserveToolBatch(List.of("lookup", "lookup"), 1_000);
        assertEquals(1, budget.toolRounds());
        assertEquals(2, budget.totalToolCalls());
        assertEquals(2, budget.toolCallsByName().get("lookup"));

        RagException error = assertThrows(
                RagException.class,
                () -> budget.reserveToolBatch(List.of("lookup", "lookup"), 1_000));
        assertEquals(ErrorCode.CHAT_BUDGET_EXHAUSTED, error.getErrorCodeEnum());
        assertEquals(2, budget.totalToolCalls());
    }

    @Test
    void failedToolExecutionKeepsCallCountsButReleasesReservation() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                3,
                8,
                3,
                6,
                3,
                2_000);

        budget.reserveToolBatch(List.of("search"), 1_000);
        budget.releaseToolReservation(1_000);
        budget.reserveToolBatch(List.of("search"), 1_000);
        budget.settleToolResults(120, 60, 1_000);

        assertEquals(2, budget.toolCallsByName().get("search"));
        assertEquals(120, budget.toolResultCharacters());
        assertEquals(60, budget.toolResultTokens());
    }

    @Test
    void modelCallsRespectDeadlineAndMaximum() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                1,
                1,
                1,
                1,
                1,
                1_000);
        budget.reserveModelCall();
        RagException error = assertThrows(
                RagException.class,
                budget::reserveModelCall);
        assertEquals(ErrorCode.CHAT_BUDGET_EXHAUSTED, error.getErrorCodeEnum());
        assertTrue(budget.snapshot().containsKey("modelCalls"));
    }

    @Test
    void toolResultReservationUsesPerToolLimitsAndReleasesUnusedCapacity() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                2,
                4,
                3,
                4,
                2,
                3_000);

        int reserved = budget.reserveToolBatch(
                List.of("small", "large"),
                java.util.Map.of("small", 500, "large", 1_000),
                2_000);
        assertEquals(1_500, reserved);
        budget.settleToolResults(200, 150, reserved);

        assertEquals(200, budget.toolResultCharacters());
        assertEquals(150, budget.toolResultTokens());
    }

    @Test
    void countsCandidatesModelCallsAndAttributionOrdinals() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                2,
                3,
                2,
                2,
                2,
                2_000,
                UUID.randomUUID(),
                "user-1",
                "session-1",
                "trace-1",
                ChatMode.PLAIN);

        assertTrue(budget.tryReserveCandidateAttempt());
        assertTrue(budget.tryReserveCandidateAttempt());
        assertFalse(budget.tryReserveCandidateAttempt());
        assertEquals(2, budget.candidateAttempts());

        assertEquals(1, budget.reserveModelCall());
        assertEquals(2, budget.reserveModelCall());
        assertEquals(2, budget.modelCalls());
        assertTrue(budget.hasModelCallCapacity());

        assertEquals(2, budget.attribution(2).callOrdinal());
        assertEquals("user-1", budget.attribution(2).principalId());
        assertTrue(budget.snapshot().containsKey("modelCalls"));
        assertTrue(budget.snapshot().containsKey("candidateAttempts"));
    }

    @Test
    void hasModelCallCapacityTurnsFalseWhenMaximumIsReached() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30), 1, 1, 1, 1, 1, 1_000);

        assertTrue(budget.hasModelCallCapacity());
        budget.reserveModelCall();
        assertFalse(budget.hasModelCallCapacity());
    }

    @Test
    void expiredBudgetRejectsReservations() {
        ChatExecutionBudget expired = new ChatExecutionBudget(
                Instant.now().minusSeconds(1), 3, 3, 2, 3, 2, 2_000);

        assertFalse(expired.hasModelCallCapacity());
        assertFalse(expired.tryReserveCandidateAttempt());
        RagException modelError = assertThrows(
                RagException.class, expired::reserveModelCall);
        assertEquals(ErrorCode.CHAT_BUDGET_EXHAUSTED, modelError.getErrorCodeEnum());
        RagException toolError = assertThrows(
                RagException.class,
                () -> expired.reserveToolBatch(List.of("lookup"), 1_000));
        assertEquals(ErrorCode.CHAT_BUDGET_EXHAUSTED, toolError.getErrorCodeEnum());
        RagException policyError = assertThrows(
                RagException.class,
                () -> expired.tryReservePolicyToolCall("lookup", 1));
        assertEquals(ErrorCode.CHAT_BUDGET_EXHAUSTED, policyError.getErrorCodeEnum());
    }

    @Test
    void toolBatchBoundariesAcceptExactlyTheConfiguredMaximum() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30), 3, 3, 2, 2, 2, 2_000);

        assertEquals(2, budget.reserveToolBatch(
                List.of("a", "b"), Map.of(), 1));

        RagException tooManyNames = assertThrows(
                RagException.class,
                () -> budget.reserveToolBatch(List.of("a", "b", "c"), 1));
        assertEquals(ErrorCode.CHAT_BUDGET_EXHAUSTED, tooManyNames.getErrorCodeEnum());
    }

    @Test
    void totalToolCallsBoundaryAllowsExactTotalAndRejectsOneMore() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30), 3, 3, 3, 3, 2, 2_000);

        budget.reserveToolBatch(List.of("a", "b"), 1);
        budget.reserveToolBatch(List.of("c"), 1);

        RagException error = assertThrows(
                RagException.class,
                () -> budget.reserveToolBatch(List.of("d"), 1));
        assertEquals(ErrorCode.CHAT_BUDGET_EXHAUSTED, error.getErrorCodeEnum());
    }

    @Test
    void characterBudgetAllowsExactReservationAndSettlementReleasesCapacity() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30), 3, 3, 3, 3, 3, 2_000);

        assertEquals(2_000, budget.reserveToolBatch(
                List.of("a"), Map.of(), 2_000));
        RagException exhausted = assertThrows(
                RagException.class,
                () -> budget.reserveToolBatch(List.of("b"), 1));
        assertEquals(ErrorCode.CHAT_BUDGET_EXHAUSTED, exhausted.getErrorCodeEnum());

        budget.settleToolResults(0, 0, 2_000);
        assertEquals(1, budget.reserveToolBatch(
                List.of("b"), Map.of(), 1));
    }

    @Test
    void policyToolCallsRespectMaximumAndRejectNonPositiveValues() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30), 3, 3, 3, 3, 3, 2_000);

        assertFalse(budget.tryReservePolicyToolCall("lookup", 0));
        assertFalse(budget.tryReservePolicyToolCall(" ", 1));
        assertTrue(budget.tryReservePolicyToolCall("lookup", 1));
        assertFalse(budget.tryReservePolicyToolCall("lookup", 1));
    }

    @Test
    void snapshotIncludesQueryEnhancementsAndRejectsUnknownStages() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30), 3, 3, 3, 3, 3, 2_000);

        budget.recordQueryEnhancement("QUERY_TRANSFORM", "APPLIED", -5);
        budget.recordQueryEnhancement("QUERY_EXPAND", "FAILED", 12);
        assertThrows(IllegalArgumentException.class,
                () -> budget.recordQueryEnhancement("OTHER", "APPLIED", 1));

        @SuppressWarnings("unchecked")
        Map<String, Object> enhancements = (Map<String, Object>)
                budget.snapshot().get("queryEnhancements");
        assertTrue(enhancements.containsKey("QUERY_TRANSFORM"));
        assertTrue(enhancements.containsKey("QUERY_EXPAND"));
        @SuppressWarnings("unchecked")
        Map<String, Object> transform =
                (Map<String, Object>) enhancements.get("QUERY_TRANSFORM");
        assertEquals(0L, transform.get("elapsedMs"));
    }

    @Test
    void attributionRejectsOverlongOrNonPrintableValuesButAcceptsBoundaries() {
        String maximumPrincipal = "a".repeat(126) + " ~";
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                1, 1, 1, 1, 1, 1_000,
                UUID.randomUUID(),
                maximumPrincipal,
                "session",
                "trace",
                ChatMode.PLAIN);
        assertEquals(maximumPrincipal, budget.principalId());

        assertThrows(IllegalArgumentException.class,
                () -> new ChatExecutionBudget(
                        Instant.now().plusSeconds(30),
                        1, 1, 1, 1, 1, 1_000,
                        UUID.randomUUID(),
                        "a".repeat(129),
                        "session", "trace", ChatMode.PLAIN));
        assertThrows(IllegalArgumentException.class,
                () -> new ChatExecutionBudget(
                        Instant.now().plusSeconds(30),
                        1, 1, 1, 1, 1, 1_000,
                        UUID.randomUUID(),
                        "bad\u0001id",
                        "session", "trace", ChatMode.PLAIN));
    }

    @Test
    void backgroundWorkloadComesFromTheAmbientScope() {
        ChatExecutionBudget foreground = new ChatExecutionBudget(
                Instant.now().plusSeconds(30), 1, 1, 1, 1, 1, 1_000);
        assertFalse(foreground.backgroundWorkload());

        try (BackgroundWorkScope ignored = BackgroundWorkScope.open()) {
            ChatExecutionBudget background = new ChatExecutionBudget(
                    Instant.now().plusSeconds(30), 1, 1, 1, 1, 1, 1_000);
            assertTrue(background.backgroundWorkload());
        }
        assertFalse(new ChatExecutionBudget(
                Instant.now().plusSeconds(30), 1, 1, 1, 1, 1, 1_000)
                .backgroundWorkload());
    }

    @Test
    void sharesHttpResponseBytesAcrossLogicalRequestAttempts() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                3,
                8,
                3,
                6,
                3,
                4_000);

        HttpToolExecutionState first = budget.httpToolExecutionState(100);
        HttpToolExecutionState.ResponseReservation reservation =
                first.reserveUpTo(80);
        first.commit(reservation, 60);
        HttpToolExecutionState fallback = budget.httpToolExecutionState(100);

        assertSame(first, fallback);
        assertEquals(60, fallback.responseBytes());
        assertEquals(40, fallback.remainingBytes());
        assertEquals(60L, budget.snapshot().get("httpResponseBytes"));
    }
}
