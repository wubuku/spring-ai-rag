package com.springairag.core.usage;

import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.MultiModelProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 LLM 用量审计事件的归一化与校验：默认值、文本边界、
 * usage/价格一致性、成本与单位归一、时间边界。
 */
class LlmUsageEventTest {

    private static final Instant START = Instant.parse("2026-09-05T10:00:00Z");

    @Test
    void fillsDefaultsForNullComponents() {
        LlmUsageEvent event = event("principal", "session-1", null);

        assertNotNull(event.invocationId());
        assertNotNull(event.logicalExecutionId());
        assertNotEquals(event.invocationId(), event.logicalExecutionId());
        assertEquals(ChatMode.PLAIN, event.chatMode());
        assertEquals(LlmInvocationPurpose.CHAT, event.purpose());
        assertEquals(LlmInvocationOutcome.FAILED, event.outcome());
        assertFalse(event.usage().available());
        assertEquals(0, event.usage().promptTokens());
        assertEquals(START, event.startedAt());
        assertEquals(START, event.completedAt());
    }

    @Test
    void rejectsNonPositiveCallOrdinal() {
        assertThrows(IllegalArgumentException.class,
                () -> event(0, "principal", "session-1", null, null));
    }

    @Test
    void rejectsInvalidRequiredText() {
        assertThrows(IllegalArgumentException.class,
                () -> event(1, null, "session-1", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> event(1, " ", "session-1", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> event(1, "p".repeat(129), "session-1", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> event(1, "principal", "s".repeat(256), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> event(1, "principal", "session-1", null, "模型"));
        assertThrows(IllegalArgumentException.class,
                () -> event(1, "principal", "session-1", "trace\t1", null));
    }

    @Test
    void optionalRequestTraceIdIsDroppedWhenBlank() {
        LlmUsageEvent event = event(1, "principal", "session-1", "  ", null);

        assertNull(event.requestTraceId());
    }

    @Test
    void modelRefFallsBackToUnknownAndRejectsOversize() {
        assertEquals("UNKNOWN", event("principal", "session-1", null).modelRef());
        assertEquals("UNKNOWN", event("principal", "session-1", "  ").modelRef());
        assertEquals(255, event("principal", "session-1", "m".repeat(255)).modelRef().length());
        assertThrows(IllegalArgumentException.class,
                () -> event(1, "principal", "session-1", null, "m".repeat(256)));
    }

    @Test
    void unavailableUsageWithTokensNormalizesToUnavailable() {
        LlmUsageEvent event = new LlmUsageEvent(
                null, null, 1, "principal", "session-1", null, "model",
                ChatMode.AGENT, null, false, null,
                new LlmUsageSnapshot(5, 3, 8, false),
                null, null, false, null, false, null,
                10, START, null);

        assertEquals(LlmUsageSnapshot.unavailable(), event.usage());
    }

    @Test
    void zeroesCostsWhenPricingUnavailable() {
        LlmUsageEvent event = new LlmUsageEvent(
                null, null, 1, "principal", "session-1", null, "model",
                null, null, false, null, LlmUsageSnapshot.unavailable(),
                new BigDecimal("2.5"), new BigDecimal("9.9"), false,
                new BigDecimal("4.0"), false, "USD",
                10, START, null);

        assertEquals(new BigDecimal("0.00000000"), event.inputCostPerMillion());
        assertEquals(new BigDecimal("0.00000000"), event.outputCostPerMillion());
        assertFalse(event.costAvailable());
        assertEquals(new BigDecimal("0.00000000"), event.configuredCost());
    }

    @Test
    void costAvailableRequiresAvailableUsageAndPricing() {
        LlmUsageSnapshot usage = new LlmUsageSnapshot(1_000, 500, 1_500, true);

        LlmUsageEvent available = new LlmUsageEvent(
                null, null, 1, "principal", "session-1", null, "model",
                null, null, false, null, usage,
                new BigDecimal("3"), new BigDecimal("15"), true,
                new BigDecimal("4.5"), true, "USD",
                10, START, null);
        assertTrue(available.costAvailable());
        assertEquals(new BigDecimal("4.50000000"), available.configuredCost());

        LlmUsageEvent withoutUsage = new LlmUsageEvent(
                null, null, 1, "principal", "session-1", null, "model",
                null, null, false, null, LlmUsageSnapshot.unavailable(),
                new BigDecimal("3"), new BigDecimal("15"), true,
                new BigDecimal("4.5"), true, "USD",
                10, START, null);
        assertFalse(withoutUsage.costAvailable());
        assertEquals(new BigDecimal("0.00000000"), withoutUsage.configuredCost());
    }

    @Test
    void normalizesDecimalsDurationAndCompletionTime() {
        LlmUsageEvent event = new LlmUsageEvent(
                null, null, 1, "principal", "session-1", null, "model",
                null, null, false, null, LlmUsageSnapshot.unavailable(),
                new BigDecimal("-1.5"), null, true,
                new BigDecimal("1.5"), false, null,
                -5, START, START.minusSeconds(60));

        assertEquals(new BigDecimal("0.00000000"), event.inputCostPerMillion());
        // costAvailable=false 强制归零 configuredCost；负价格归零路径同用例覆盖
        assertFalse(event.costAvailable());
        assertEquals(new BigDecimal("0.00000000"), event.configuredCost());
        assertEquals(0, event.durationMs());
        assertEquals(START, event.completedAt());
    }

    @Test
    void clampsDurationToDailyMaximum() {
        LlmUsageEvent event = new LlmUsageEvent(
                null, null, 1, "principal", "session-1", null, "model",
                null, null, false, null, LlmUsageSnapshot.unavailable(),
                null, null, false, null, false, null,
                LlmUsageEvent.MAX_DURATION_MS + 1, START, null);

        assertEquals(LlmUsageEvent.MAX_DURATION_MS, event.durationMs());
    }

    @Test
    void normalizesUnit() {
        assertEquals("CONFIGURED_MODEL_COST", event("principal", "session-1", null).costUnit());
        assertEquals("CONFIGURED_MODEL_COST", event("principal", "session-1", null).costUnit());
        assertEquals("CONFIGURED_MODEL_COST", unitEvent("u".repeat(33)));
        assertEquals("CONFIGURED_MODEL_COST", unitEvent("USD\u0001"));
        assertEquals("USD", unitEvent("USD"));
    }

    @Test
    void fromBuildsEventWithCalculatorCosts() {
        ChatExecutionAttribution attribution = new ChatExecutionAttribution(
                UUID.randomUUID(), 2, "principal", "session-1", "trace-1",
                ChatMode.AGENT);
        MultiModelProperties.ModelCost price =
                new MultiModelProperties.ModelCost(3.0, 15.0, 0, 0);

        LlmUsageEvent event = LlmUsageEvent.from(
                attribution,
                "gpt-test",
                price,
                LlmInvocationPurpose.CHAT,
                true,
                LlmInvocationOutcome.SUCCEEDED,
                new LlmUsageSnapshot(1_000_000, 100_000, 1_100_000, true),
                1_200,
                START,
                START.plusMillis(1_200),
                "USD");

        assertEquals(2, event.callOrdinal());
        assertEquals("principal", event.principalId());
        assertEquals("session-1", event.sessionId());
        assertEquals("trace-1", event.requestTraceId());
        assertEquals(ChatMode.AGENT, event.chatMode());
        assertEquals("gpt-test", event.modelRef());
        assertTrue(event.streaming());
        assertEquals(LlmInvocationOutcome.SUCCEEDED, event.outcome());
        assertTrue(event.pricingAvailable());
        assertTrue(event.costAvailable());
        assertEquals(new BigDecimal("4.50000000"), event.configuredCost());
        assertEquals(1_200, event.durationMs());
        assertEquals(START.plusMillis(1_200), event.completedAt());
    }

    private LlmUsageEvent event(String principalId, String sessionId, String modelRef) {
        return event(1, principalId, sessionId, null, modelRef);
    }

    private LlmUsageEvent event(
            int callOrdinal,
            String principalId,
            String sessionId,
            String requestTraceId,
            String modelRef) {
        return new LlmUsageEvent(
                null, null, callOrdinal, principalId, sessionId, requestTraceId,
                modelRef, null, null, false, null, null,
                null, null, false, null, false, null,
                10, START, null);
    }

    private String unitEvent(String costUnit) {
        return new LlmUsageEvent(
                null, null, 1, "principal", "session-1", null, "model",
                null, null, false, null, null,
                null, null, false, null, false, costUnit,
                10, START, null).costUnit();
    }
}
