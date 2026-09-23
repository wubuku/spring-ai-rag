package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ChatExecutionBudget 工具批量预留长尾（Batch 584，JaCoCo 驱动）：
 * 空/超量名单、轮次与总量耗尽、per-name 上限、字符预算耗尽、策略
 * 工具调用上限、HTTP 字节状态惰性创建与一致性守卫。
 */
class ChatExecutionBudgetToolBatchTailTest {

    private ChatExecutionBudget budget(int maxToolRounds, int maxToolCalls,
                                       int maxToolCallsPerName,
                                       int maxToolResultCharactersTotal) {
        return new ChatExecutionBudget(
                Instant.now().plusSeconds(60),
                4, 8, maxToolRounds, maxToolCalls,
                maxToolCallsPerName, maxToolResultCharactersTotal);
    }

    private static final String PRINCIPAL = "local:auth-disabled";

    @Test
    void emptyToolNamesRejectedBeforeReservation() {
        var budget = budget(2, 4, 2, 4_000);

        var error = assertThrows(RagException.class,
                () -> budget.reserveToolBatch(List.of(), 500));
        assertTrue(error.getMessage().contains("batch exceeds"));
    }

    @Test
    void batchLargerThanTotalToolCallsRejected() {
        var budget = budget(2, 4, 2, 4_000);

        var error = assertThrows(RagException.class,
                () -> budget.reserveToolBatch(
                        List.of("a", "b", "c", "d", "e"), 500));
        assertTrue(error.getMessage().contains("batch exceeds"));
    }

    @Test
    void exhaustedToolRoundsRejected() {
        var budget = budget(1, 8, 1, 8_000);

        // 第一轮成功消耗唯一轮次。
        budget.reserveToolBatch(List.of("search"), 1_000);
        // 预算扣减成功：轮次计数已递增。
        assertEquals(1, budget.toolRounds());

        var error = assertThrows(RagException.class,
                () -> budget.reserveToolBatch(List.of("search"), 1_000));
        assertTrue(error.getMessage().contains("tool round"));
    }

    @Test
    void perNameLimitRejected() {
        var budget = budget(4, 8, 1, 8_000);

        budget.reserveToolBatch(List.of("search"), 500);
        var error = assertThrows(RagException.class,
                () -> budget.reserveToolBatch(List.of("search"), 500));
        assertTrue(error.getMessage().contains("search"));
    }

    @Test
    void characterBudgetExhaustedRejected() {
        // 总字符预算 600：首次预留 500，再次预留 500 超限 → 异常。
        var budget = budget(4, 8, 2, 600);

        budget.reserveToolBatch(List.of("big"), 500);
        var error = assertThrows(RagException.class,
                () -> budget.reserveToolBatch(List.of("big"), 500));
        assertTrue(error.getMessage().contains("character budget exhausted"));
    }

    @Test
    void tryReservePolicyToolCallEnforcesMaximum() {
        var budget = budget(2, 4, 2, 4_000);

        assertTrue(budget.tryReservePolicyToolCall("lookup", 2));
        assertTrue(budget.tryReservePolicyToolCall("lookup", 2));
        assertFalse(budget.tryReservePolicyToolCall("lookup", 2));
        assertFalse(budget.tryReservePolicyToolCall(null, 2));
        assertFalse(budget.tryReservePolicyToolCall(" ", 2));
        assertFalse(budget.tryReservePolicyToolCall("lookup", 0));
    }

    @Test
    void httpToolStateCreatedLazilyAndConsistencyGuarded() {
        var budget = budget(2, 4, 2, 4_000);

        var first = budget.httpToolExecutionState(1_000);
        var second = budget.httpToolExecutionState(1_000);

        assertTrue(first == second);
        assertEquals(1_000L, first.maxResponseBytes());

        var error = assertThrows(IllegalStateException.class,
                () -> budget.httpToolExecutionState(2_000));
        assertTrue(error.getMessage().contains("budget changed"));
    }

    @Test
    void requestTraceIdRoundTrips() {
        var budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(60), 2, 4, 2, 4, 2, 4_000,
                java.util.UUID.randomUUID(), "principal-1", "session-1",
                "trace-abc", ChatMode.PLAIN);

        assertEquals("trace-abc", budget.requestTraceId());
    }

    @Test
    void snapshotExposesCounters() {
        var budget = budget(2, 4, 2, 4_000);
        budget.reserveToolBatch(List.of("search"), 500);

        var snapshot = budget.snapshot();

        assertTrue(snapshot.containsValue(1L) || snapshot.containsValue(1));
        assertFalse(snapshot.isEmpty());
    }

}
