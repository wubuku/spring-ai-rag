package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatExecutionBudget 归因与清洗长尾（Batch 695，JaCoCo 驱动）：
 * reserveToolBatch 对 null/空白工具名归一为 &lt;unknown&gt;、限额
 * 表按名取值并对缺省名回退、contextPlan 携带 requestTraceId。
 */
class ChatExecutionBudgetAttributionTailTest {

    private ChatExecutionBudget budget(int maxToolCallsPerName) {
        return new ChatExecutionBudget(
                Instant.now().plusSeconds(60),
                4, 8, 2, 4,
                maxToolCallsPerName, 8_000,
                UUID.randomUUID(),
                "principal-695",
                "session-695",
                "trace-695",
                ChatMode.KNOWLEDGE);
    }

    @Test
    void reserveToolBatchNormalizesBlankNamesAndAppliesLimitMap() {
        var budget = budget(2);

        // null/空白名归一为 <unknown>；限额表按归一名取值，未登记
        // 的名字回退到 fallback。
        int reservation = budget.reserveToolBatch(
                Arrays.asList(null, "  ", "search"),
                Map.of("search", 700),
                500);

        assertEquals(700 + 500 + 500, reservation);
    }

    @Test
    void snapshotIncludesRequestTraceIdWhenPresent() {
        Map<String, Object> snapshot = budget(2).snapshot();

        assertEquals("trace-695", snapshot.get("requestTraceId"));
        assertEquals("principal-695", snapshot.get("principalId"));
        assertEquals("session-695", snapshot.get("sessionId"));
        assertEquals(ChatMode.KNOWLEDGE.name(), snapshot.get("chatMode"));
        assertEquals(0, ((Number) snapshot.get("toolRounds")).intValue());
    }
}
