package com.springairag.core.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatExecutionBudget 预算守卫长尾（Batch 630，JaCoCo 驱动）：
 * tryReservePolicyToolCall 的空白名/零上限/递增/CAS 竞争、
 * recordContextPlan 的 null 与非 null 投影、httpToolExecutionState
 * 的类型变更拒绝与复用。
 */
class ChatExecutionBudgetGuardTailTest {

    private ChatExecutionBudget newBudget() {
        return new ChatExecutionBudget(
                null, 5, 10, 6, 20, 3, 48_000);
    }

    @Test
    void tryReservePolicyToolCallRejectsBlankNameAndNonPositiveLimit() {
        var budget = newBudget();
        assertFalse(budget.tryReservePolicyToolCall(null, 3));
        assertFalse(budget.tryReservePolicyToolCall("  ", 3));
        assertFalse(budget.tryReservePolicyToolCall("tool", 0));
        assertFalse(budget.tryReservePolicyToolCall("tool", -1));
    }

    @Test
    void tryReservePolicyToolCallIncrementsThenRejectsAtLimit() {
        var budget = newBudget();
        assertTrue(budget.tryReservePolicyToolCall("search", 2));
        assertTrue(budget.tryReservePolicyToolCall("search", 2));
        // 已达上限 2 → 第 3 次拒绝。
        assertFalse(budget.tryReservePolicyToolCall("search", 2));
    }

    @Test
    void recordContextPlanStoresNonNullAndNullPlan() {
        var budget = newBudget();
        budget.recordContextPlan(null);
        assertTrue(budget.contextPlan().isEmpty());

        budget.recordContextPlan(Map.of("key", "value"));
        assertEquals("value", budget.contextPlan().get("key"));

        budget.recordContextPlan(null);
        assertTrue(budget.contextPlan().isEmpty());
    }

    @Test
    void httpToolExecutionStateRejectsBudgetChangeWithinRequest() {
        var budget = newBudget();
        var first = budget.httpToolExecutionState(64);
        assertEquals(64, first.maxResponseBytes());
        assertThrows(IllegalStateException.class,
                () -> budget.httpToolExecutionState(128));
    }

    @Test
    void httpToolExecutionStateReturnsSameInstanceForSameLimit() {
        var budget = newBudget();
        var first = budget.httpToolExecutionState(64);
        var second = budget.httpToolExecutionState(64);
        assertTrue(first == second);
    }
}
