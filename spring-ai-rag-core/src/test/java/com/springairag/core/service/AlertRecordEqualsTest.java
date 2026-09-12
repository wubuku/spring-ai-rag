package com.springairag.core.service;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AlertService.AlertRecord 的 equals/hashCode 全字段矩阵
 * （Batch 310）：自反、对称、null 与异型拒绝、12 个字段逐一
 * 影响相等性、全 null 字段相等且哈希一致。
 */
class AlertRecordEqualsTest {

    private AlertService.AlertRecord record() {
        AlertService.AlertRecord record = new AlertService.AlertRecord();
        record.setId(7L);
        record.setAlertType("threshold");
        record.setAlertName("latency");
        record.setMessage("latency too high");
        record.setSeverity("WARNING");
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("p95", 800);
        record.setMetrics(metrics);
        record.setStatus("ACTIVE");
        record.setResolution("none");
        record.setConditionState("breached");
        record.setFiredAt(ZonedDateTime.parse("2026-09-12T08:00:00Z"));
        record.setResolvedAt(ZonedDateTime.parse("2026-09-12T09:00:00Z"));
        record.setSilencedUntil(ZonedDateTime.parse("2026-09-12T10:00:00Z"));
        return record;
    }

    @Test
    void equalRecordsShareHashCode() {
        AlertService.AlertRecord left = record();
        AlertService.AlertRecord right = record();

        assertEquals(left, right);
        assertEquals(right, left);
        assertEquals(left.hashCode(), right.hashCode());
    }

    @Test
    void reflexiveNullAndForeignTypeRejected() {
        AlertService.AlertRecord record = record();

        assertTrue(record.equals(record));
        assertFalse(record.equals(null));
        assertFalse(record.equals("not a record"));
    }

    @Test
    void everyFieldParticipatesInEquality() {
        Map<String, Consumer<AlertService.AlertRecord>> mutations = new HashMap<>();
        mutations.put("id", r -> r.setId(8L));
        mutations.put("alertType", r -> r.setAlertType("anomaly"));
        mutations.put("alertName", r -> r.setAlertName("error-rate"));
        mutations.put("message", r -> r.setMessage("different"));
        mutations.put("severity", r -> r.setSeverity("CRITICAL"));
        mutations.put("metrics", r -> r.setMetrics(Map.of("p95", 900)));
        mutations.put("status", r -> r.setStatus("RESOLVED"));
        mutations.put("resolution", r -> r.setResolution("acked"));
        mutations.put("conditionState", r -> r.setConditionState("cleared"));
        mutations.put("firedAt", r ->
                r.setFiredAt(ZonedDateTime.parse("2026-09-12T08:30:00Z")));
        mutations.put("resolvedAt", r -> r.setResolvedAt(null));
        mutations.put("silencedUntil", r -> r.setSilencedUntil(null));

        mutations.forEach((field, mutation) -> {
            AlertService.AlertRecord left = record();
            AlertService.AlertRecord right = record();
            mutation.accept(right);

            assertNotEquals(left, right, field + " 应参与相等性");
        });
    }

    @Test
    void allNullFieldsAreEqualWithStableHash() {
        AlertService.AlertRecord left = new AlertService.AlertRecord();
        AlertService.AlertRecord right = new AlertService.AlertRecord();

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
    }

    @Test
    void metricsMapContentMattersNotIdentity() {
        AlertService.AlertRecord left = record();
        AlertService.AlertRecord right = record();

        // 相同内容的等值 Map 视为相等，引用不同不影响。
        left.setMetrics(new HashMap<>(Map.of("p95", 800)));
        right.setMetrics(new HashMap<>(Map.of("p95", 800)));
        assertEquals(left, right);

        right.setMetrics(new HashMap<>(Map.of("p95", 950)));
        assertNotEquals(left, right);
    }
}
