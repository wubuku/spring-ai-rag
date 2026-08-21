package com.springairag.core.chat;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
