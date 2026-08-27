package com.springairag.core.usage;

import com.springairag.api.enums.ChatMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 一次模型 invocation 的不可变终态事实。
 *
 * <p>事件不包含 prompt、answer、工具参数或异常正文，只保留用于审计、聚合和成本估算的
 * 有界维度。价格是 invocation 开始时捕获的快照，不能在异步落库时重新读取配置。</p>
 */
public record LlmUsageEvent(
        UUID invocationId,
        UUID logicalExecutionId,
        int callOrdinal,
        String principalId,
        String sessionId,
        String requestTraceId,
        String modelRef,
        ChatMode chatMode,
        LlmInvocationPurpose purpose,
        boolean streaming,
        LlmInvocationOutcome outcome,
        LlmUsageSnapshot usage,
        BigDecimal inputCostPerMillion,
        BigDecimal outputCostPerMillion,
        boolean pricingAvailable,
        BigDecimal configuredCost,
        boolean costAvailable,
        String costUnit,
        long durationMs,
        Instant startedAt,
        Instant completedAt) {

    public static final long MAX_DURATION_MS = 86_400_000L;

    public LlmUsageEvent {
        invocationId = invocationId != null ? invocationId : UUID.randomUUID();
        logicalExecutionId = logicalExecutionId != null
                ? logicalExecutionId : UUID.randomUUID();
        if (callOrdinal < 1) {
            throw new IllegalArgumentException("callOrdinal must be positive");
        }
        principalId = boundedRequired(principalId, 128, "principalId");
        sessionId = boundedRequired(sessionId, 255, "sessionId");
        requestTraceId = boundedOptional(requestTraceId, 128, "requestTraceId");
        modelRef = boundedRequired(
                modelRef == null || modelRef.isBlank() ? "UNKNOWN" : modelRef,
                255,
                "modelRef");
        chatMode = chatMode != null ? chatMode : ChatMode.PLAIN;
        purpose = purpose != null ? purpose : LlmInvocationPurpose.CHAT;
        outcome = outcome != null ? outcome : LlmInvocationOutcome.FAILED;
        usage = usage != null ? usage : LlmUsageSnapshot.unavailable();
        if (!usage.available()
                && (usage.promptTokens() != 0
                || usage.completionTokens() != 0
                || usage.totalTokens() != 0)) {
            usage = LlmUsageSnapshot.unavailable();
        }
        inputCostPerMillion = normalizedDecimal(inputCostPerMillion);
        outputCostPerMillion = normalizedDecimal(outputCostPerMillion);
        configuredCost = normalizedDecimal(configuredCost);
        if (!pricingAvailable) {
            inputCostPerMillion = zero();
            outputCostPerMillion = zero();
        }
        costAvailable = costAvailable && usage.available() && pricingAvailable;
        if (!costAvailable) {
            configuredCost = zero();
        }
        costUnit = normalizeUnit(costUnit);
        durationMs = Math.max(0, Math.min(MAX_DURATION_MS, durationMs));
        startedAt = startedAt != null ? startedAt : Instant.now();
        completedAt = completedAt == null || completedAt.isBefore(startedAt)
                ? startedAt
                : completedAt;
    }

    public static LlmUsageEvent from(
            ChatExecutionAttribution attribution,
            String modelRef,
            com.springairag.core.config.MultiModelProperties.ModelCost price,
            LlmInvocationPurpose purpose,
            boolean streaming,
            LlmInvocationOutcome outcome,
            LlmUsageSnapshot usage,
            long durationMs,
            Instant startedAt,
            Instant completedAt,
            String costUnit) {
        LlmUsageCostCalculator.Result cost =
                LlmUsageCostCalculator.calculate(usage, price, costUnit);
        return new LlmUsageEvent(
                UUID.randomUUID(),
                attribution.logicalExecutionId(),
                attribution.callOrdinal(),
                attribution.principalId(),
                attribution.sessionId(),
                attribution.requestTraceId(),
                modelRef,
                attribution.chatMode(),
                purpose,
                streaming,
                outcome,
                usage,
                cost.inputCostPerMillion(),
                cost.outputCostPerMillion(),
                cost.pricingAvailable(),
                cost.configuredCost(),
                cost.costAvailable(),
                cost.unit(),
                durationMs,
                startedAt,
                completedAt);
    }

    private static String boundedRequired(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(ch -> ch < 0x20 || ch > 0x7e)) {
            throw new IllegalArgumentException(
                    name + " must contain printable ASCII characters within 1-"
                            + maximum);
        }
        return value;
    }

    private static String boundedOptional(String value, int maximum, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return boundedRequired(value, maximum, name);
    }

    private static BigDecimal normalizedDecimal(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            return zero();
        }
        return value.setScale(LlmUsageCostCalculator.SCALE);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(LlmUsageCostCalculator.SCALE);
    }

    private static String normalizeUnit(String value) {
        if (value == null || value.isBlank() || value.length() > 32
                || value.chars().anyMatch(ch -> ch < 0x20 || ch > 0x7e)) {
            return "CONFIGURED_MODEL_COST";
        }
        return value;
    }
}
