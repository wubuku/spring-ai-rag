package com.springairag.core.usage;

import com.springairag.core.config.MultiModelProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 使用调用开始时快照的模型价格计算配置估算成本。
 */
public final class LlmUsageCostCalculator {

    public static final int SCALE = 8;
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);
    private static final BigDecimal MAX_PRICE = BigDecimal.valueOf(1_000_000L);
    private static final BigDecimal MAX_COST =
            new BigDecimal("9999999999.99999999");

    private LlmUsageCostCalculator() {
    }

    public static Result calculate(
            LlmUsageSnapshot usage,
            MultiModelProperties.ModelCost price,
            String unit) {
        String normalizedUnit = normalizeUnit(unit);
        BigDecimal input = price != null
                ? finiteNonNegative(price.input())
                : null;
        BigDecimal output = price != null
                ? finiteNonNegative(price.output())
                : null;
        if (input == null || output == null
                || input.compareTo(MAX_PRICE) > 0
                || output.compareTo(MAX_PRICE) > 0) {
            return Result.unavailable(normalizedUnit);
        }
        if (usage == null || !usage.available()) {
            return Result.withPricing(
                    input.setScale(SCALE, RoundingMode.HALF_UP),
                    output.setScale(SCALE, RoundingMode.HALF_UP),
                    normalizedUnit);
        }
        try {
            BigDecimal configuredCost = BigDecimal.valueOf(usage.promptTokens())
                    .multiply(input)
                    .add(BigDecimal.valueOf(usage.completionTokens())
                            .multiply(output))
                    .divide(MILLION, SCALE, RoundingMode.HALF_UP);
            if (configuredCost.signum() < 0
                    || configuredCost.compareTo(MAX_COST) > 0) {
                return Result.unavailable(normalizedUnit);
            }
            return new Result(
                    true,
                    true,
                    input.setScale(SCALE, RoundingMode.HALF_UP),
                    output.setScale(SCALE, RoundingMode.HALF_UP),
                    configuredCost.setScale(SCALE, RoundingMode.HALF_UP),
                    normalizedUnit);
        } catch (ArithmeticException error) {
            return Result.unavailable(normalizedUnit);
        }
    }

    private static BigDecimal finiteNonNegative(double value) {
        if (!Double.isFinite(value) || value < 0) {
            return null;
        }
        return BigDecimal.valueOf(value);
    }

    private static String normalizeUnit(String unit) {
        if (unit == null || unit.isBlank()) {
            return "CONFIGURED_MODEL_COST";
        }
        String value = unit.trim();
        if (value.length() > 32
                || value.chars().anyMatch(ch -> ch < 0x20 || ch > 0x7e)) {
            return "CONFIGURED_MODEL_COST";
        }
        return value;
    }

    public record Result(
            boolean pricingAvailable,
            boolean costAvailable,
            BigDecimal inputCostPerMillion,
            BigDecimal outputCostPerMillion,
            BigDecimal configuredCost,
            String unit) {

        public Result {
            inputCostPerMillion = inputCostPerMillion != null
                    ? inputCostPerMillion : BigDecimal.ZERO.setScale(SCALE);
            outputCostPerMillion = outputCostPerMillion != null
                    ? outputCostPerMillion : BigDecimal.ZERO.setScale(SCALE);
            configuredCost = configuredCost != null
                    ? configuredCost : BigDecimal.ZERO.setScale(SCALE);
            unit = normalizeUnit(unit);
        }

        public static Result unavailable(String unit) {
            return new Result(
                    false,
                    false,
                    BigDecimal.ZERO.setScale(SCALE),
                    BigDecimal.ZERO.setScale(SCALE),
                    BigDecimal.ZERO.setScale(SCALE),
                    normalizeUnit(unit));
        }

        public static Result withPricing(
                BigDecimal input,
                BigDecimal output,
                String unit) {
            return new Result(
                    true,
                    false,
                    input,
                    output,
                    BigDecimal.ZERO.setScale(SCALE),
                    unit);
        }
    }
}
