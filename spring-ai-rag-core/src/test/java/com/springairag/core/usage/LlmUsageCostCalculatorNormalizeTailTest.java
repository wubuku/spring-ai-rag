package com.springairag.core.usage;

import com.springairag.core.config.MultiModelProperties.ModelCost;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用例成本计算缺省长尾（Batch 718，JaCoCo 驱动）：价格越界 / 非
 * 有限负数输入的不可用降级、无可用用量时仅发布定价、configuredCost
 * 越界降级、unit 空白与超长/控制字符归一。
 */
class LlmUsageCostCalculatorNormalizeTailTest {

    @Test
    void overLimitPricingYieldsUnavailable() {
        var result = LlmUsageCostCalculator.calculate(
                new LlmUsageSnapshot(1, 1, 2, true),
                new ModelCost(1_000_001.0, 1.0, 0, 0), "USD");

        assertFalse(result.pricingAvailable());
        assertFalse(result.costAvailable());
    }

    @Test
    void nonFinitePriceYieldsUnavailable() {
        var nonFinite = LlmUsageCostCalculator.calculate(
                new LlmUsageSnapshot(1, 1, 2, true),
                new ModelCost(Double.NaN, 1.0, 0, 0), "USD");
        assertFalse(nonFinite.pricingAvailable());

        var infinite = LlmUsageCostCalculator.calculate(
                new LlmUsageSnapshot(1, 1, 2, true),
                new ModelCost(Double.POSITIVE_INFINITY, 1.0, 0, 0), "USD");
        assertFalse(infinite.pricingAvailable());
    }

    @Test
    void unavailableUsageStillPublishesPricing() {
        var result = LlmUsageCostCalculator.calculate(
                LlmUsageSnapshot.unavailable(),
                new ModelCost(3.0, 15.0, 0, 0), "USD");

        assertTrue(result.pricingAvailable());
        assertFalse(result.costAvailable());
        assertEquals(new BigDecimal("3.00000000"),
                result.inputCostPerMillion());
        assertEquals(new BigDecimal("15.00000000"),
                result.outputCostPerMillion());
    }

    @Test
    void negativeConfiguredCostYieldsCostUnavailable() {
        var result = LlmUsageCostCalculator.calculate(
                new LlmUsageSnapshot(-1_000_000, 0, -1_000_000, true),
                new ModelCost(3.0, 15.0, 0, 0), "USD");

        assertFalse(result.pricingAvailable());
        assertFalse(result.costAvailable());
    }

    @Test
    void blankUnitNormalizesToConfiguredModelCost() {
        var result = LlmUsageCostCalculator.calculate(
                LlmUsageSnapshot.unavailable(),
                new ModelCost(3.0, 15.0, 0, 0), "   ");

        assertEquals("CONFIGURED_MODEL_COST", result.unit());
    }

    @Test
    void overLongOrControlCharUnitNormalizesToConfiguredModelCost() {
        var overLong = LlmUsageCostCalculator.calculate(
                LlmUsageSnapshot.unavailable(),
                new ModelCost(3.0, 15.0, 0, 0), "u".repeat(33));
        assertEquals("CONFIGURED_MODEL_COST", overLong.unit());

        var controlChar = LlmUsageCostCalculator.calculate(
                LlmUsageSnapshot.unavailable(),
                new ModelCost(3.0, 15.0, 0, 0), "US\u0007D");
        assertEquals("CONFIGURED_MODEL_COST", controlChar.unit());

        var ok = LlmUsageCostCalculator.calculate(
                LlmUsageSnapshot.unavailable(),
                new ModelCost(3.0, 15.0, 0, 0), " usd ");
        assertEquals("usd", ok.unit());
    }
}
