package com.springairag.core.usage;

import com.springairag.core.config.MultiModelProperties.ModelCost;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class LlmUsageCostCalculatorTest {

    @Test
    void calculatesCostFromValidTokensAndPricing() {
        LlmUsageSnapshot usage = new LlmUsageSnapshot(1_000_000, 100_000, 1_100_000, true);
        var result = LlmUsageCostCalculator.calculate(usage, pricing(), "USD");

        assertTrue(result.pricingAvailable());
        assertTrue(result.costAvailable());
        assertEquals(new BigDecimal("3.00000000"), result.inputCostPerMillion());
        assertEquals(new BigDecimal("15.00000000"), result.outputCostPerMillion());
        // configuredCost = (1M * 3 + 100K * 15) / 1M = 4.50
        assertEquals(new BigDecimal("4.50000000"), result.configuredCost());
    }

    @Test
    void nullPricingYieldsPricingAndCostUnavailable() {
        LlmUsageSnapshot usage = new LlmUsageSnapshot(100, 50, 150, true);
        var result = LlmUsageCostCalculator.calculate(usage, null, "USD");
        assertFalse(result.pricingAvailable());
        assertFalse(result.costAvailable());
    }

    @Test
    void unavailableUsageYieldsPricingOnlyWithoutCost() {
        var result = LlmUsageCostCalculator.calculate(
                LlmUsageSnapshot.unavailable(), pricing(), "USD");
        assertTrue(result.pricingAvailable());
        assertFalse(result.costAvailable());
    }

    @Test
    void unitIsNormalizedWhenNullOrBlank() {
        var result = LlmUsageCostCalculator.calculate(null, pricing(), null);
        assertEquals("CONFIGURED_MODEL_COST", result.unit());
    }

    private ModelCost pricing() {
        return new ModelCost(3.0, 15.0, 0, 0);
    }
}
