package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.http.HttpToolExecutionState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatExecutionBudget 长尾（Batch 433）：过期截止时间下的各预留
 * 入口、httpToolExecutionState 的字节预算一致性、构造器归因字段
 * 校验与缺省归一、settle/release 的零值下限。
 */
class ChatExecutionBudgetTailTest {

    private ChatExecutionBudget expiredBudget() {
        return new ChatExecutionBudget(
                Instant.now().minusSeconds(1), 2, 2, 2, 2, 2, 10_000);
    }

    private HttpToolExecutionState httpState(ChatExecutionBudget budget,
                                             long maxResponseBytes)
            throws Exception {
        Method method = ChatExecutionBudget.class.getDeclaredMethod(
                "httpToolExecutionState", long.class);
        method.setAccessible(true);
        return (HttpToolExecutionState) method.invoke(budget, maxResponseBytes);
    }

    @Test
    void expiredDeadlineBlocksEveryReservationEntry() {
        ChatExecutionBudget budget = expiredBudget();
        assertFalse(budget.tryReserveCandidateAttempt());
        assertFalse(budget.hasModelCallCapacity());
        RagException reserveError = assertThrows(RagException.class,
                budget::reserveModelCall);
        assertEquals(ErrorCode.CHAT_BUDGET_EXHAUSTED,
                reserveError.getErrorCodeEnum());
        RagException batchError = assertThrows(RagException.class,
                () -> budget.reserveToolBatch(List.of("a"), 100));
        assertEquals(ErrorCode.CHAT_BUDGET_EXHAUSTED,
                batchError.getErrorCodeEnum());
    }

    @Test
    void httpToolBudgetStateIsStableAndRejectsBudgetChange() throws Exception {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(60), 1, 5, 5, 5, 5, 10_000);
        HttpToolExecutionState first = httpState(budget, 1000);
        assertNotNull(first);
        assertEquals(first, httpState(budget, 1000));
        // 反射调用会把目标异常包进 InvocationTargetException。
        try {
            httpState(budget, 2000);
            throw new AssertionError("expected IllegalStateException");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertEquals(IllegalStateException.class, e.getCause().getClass());
        }
    }

    @Test
    void policyToolCallCapRejectsInvalidInputThenEnforcesCap() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(60), 1, 5, 5, 5, 5, 10_000);
        assertFalse(budget.tryReservePolicyToolCall(null, 3));
        assertFalse(budget.tryReservePolicyToolCall("  ", 3));
        assertFalse(budget.tryReservePolicyToolCall("tool", 0));
        assertTrue(budget.tryReservePolicyToolCall("tool", 2));
        assertTrue(budget.tryReservePolicyToolCall("tool", 2));
        assertFalse(budget.tryReservePolicyToolCall("tool", 2));
    }

    @Test
    void constructorValidatesAttributionAndAppliesDefaults() {
        assertThrows(IllegalArgumentException.class, () -> new ChatExecutionBudget(
                Instant.now().plusSeconds(60), 1, 1, 1, 1, 1, 1000,
                UUID.randomUUID(), "bad\nid", "session-1", null,
                ChatMode.PLAIN));

        ChatExecutionBudget budget = new ChatExecutionBudget(
                null, 0, 0, 0, 0, 0, 0,
                null, "db:1", "session-1", "  ", null);
        assertNotNull(budget.logicalExecutionId());
        assertNotNull(budget.deadline());
        assertEquals("db:1", budget.principalId());
        assertEquals("session-1", budget.sessionId());
        assertFalse(budget.snapshot().containsKey("requestTraceId"));
        assertEquals(ChatMode.PLAIN, budget.chatMode());
        assertFalse(budget.isExpired());
        budget.recordSummaryCall();
        assertEquals(1, budget.summaryCalls());
    }

    @Test
    void settleAndReleaseClampToZeroFloor() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(60), 1, 5, 5, 5, 5, 10_000);
        budget.settleToolResults(50, 10, 0);
        assertEquals(50, budget.toolResultCharacters());
        assertEquals(10, budget.toolResultTokens());
        // 释放量大于当前预留也不会出现负数。
        budget.releaseToolReservation(999);
        budget.settleToolResults(-5, -5, -5);
        assertEquals(50, budget.toolResultCharacters());
        assertEquals(10, budget.toolResultTokens());
    }
}
