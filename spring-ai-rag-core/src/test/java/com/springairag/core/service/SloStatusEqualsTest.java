package com.springairag.core.service;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AlertService.SloStatus 的 equals/hashCode 全字段矩阵
 * （Batch 333）：等值哈希一致、自反/null/异型拒绝、10 个字段逐
 * 一影响相等性、默认实例等值。
 */
class SloStatusEqualsTest {

    private AlertService.SloStatus status() {
        AlertService.SloStatus status = new AlertService.SloStatus();
        status.setSloName("latency");
        status.setSloType("p95");
        status.setTarget(0.99);
        status.setActual(0.97);
        status.setUnit("ratio");
        status.setMet(false);
        status.setErrorBudget(0.25);
        status.setErrorBudgetRemaining(0.05);
        status.setWindowStart(ZonedDateTime.parse("2026-09-13T00:00:00Z"));
        status.setWindowEnd(ZonedDateTime.parse("2026-09-13T01:00:00Z"));
        return status;
    }

    @Test
    void equalStatusesShareHashCode() {
        AlertService.SloStatus left = status();
        AlertService.SloStatus right = status();

        assertEquals(left, right);
        assertEquals(right, left);
        assertEquals(left.hashCode(), right.hashCode());
    }

    @Test
    void reflexiveNullAndForeignTypeRejected() {
        AlertService.SloStatus record = status();

        assertTrue(record.equals(record));
        assertEquals(false, record.equals(null));
        assertEquals(false, record.equals("not a status"));
    }

    @Test
    void everyFieldParticipatesInEquality() {
        Map<String, Consumer<AlertService.SloStatus>> mutations = new HashMap<>();
        mutations.put("sloName", s -> s.setSloName("error-rate"));
        mutations.put("sloType", s -> s.setSloType("availability"));
        mutations.put("target", s -> s.setTarget(0.999));
        mutations.put("actual", s -> s.setActual(0.98));
        mutations.put("unit", s -> s.setUnit("ms"));
        mutations.put("met", s -> s.setMet(true));
        mutations.put("errorBudget", s -> s.setErrorBudget(0.5));
        mutations.put("errorBudgetRemaining", s ->
                s.setErrorBudgetRemaining(0.2));
        mutations.put("windowStart", s ->
                s.setWindowStart(ZonedDateTime.parse("2026-09-12T00:00:00Z")));
        mutations.put("windowEnd", s ->
                s.setWindowEnd(ZonedDateTime.parse("2026-09-13T02:00:00Z")));

        mutations.forEach((field, mutation) -> {
            AlertService.SloStatus left = status();
            AlertService.SloStatus right = status();
            mutation.accept(right);

            assertNotEquals(left, right, field + " 应参与相等性");
        });
    }

    @Test
    void defaultInstancesAreEqual() {
        AlertService.SloStatus left = new AlertService.SloStatus();
        AlertService.SloStatus right = new AlertService.SloStatus();

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
    }
}
