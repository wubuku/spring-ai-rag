package com.springairag.core.service;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * AlertService 值对象长尾（Batch 652，JaCoCo 驱动）：AlertRecord
 * toString、AlertStats equals/hashCode/toString、SloStatus
 * toString 与时间窗访问器。
 */
class AlertServiceValueSemanticsTailTest {

    @Test
    void alertRecordToStringContainsIdentity() {
        AlertService.AlertRecord record = new AlertService.AlertRecord();
        record.setId(7L);
        record.setAlertType("latency");

        String text = record.toString();
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("id=7"));
    }

    @Test
    void alertStatsEqualityFollowsAllCountersAndRate() {
        AlertService.AlertStats stats = stats(10, 3, 1, 1, 1, 0.3);
        assertEquals(stats, stats(10, 3, 1, 1, 1, 0.3));
        assertEquals(stats.hashCode(), stats(10, 3, 1, 1, 1, 0.3).hashCode());

        assertNotEquals(stats, stats(11, 3, 1, 1, 1, 0.3));
        assertNotEquals(stats, stats(10, 4, 1, 1, 1, 0.3));
        assertNotEquals(stats, stats(10, 3, 2, 1, 1, 0.3));
        assertNotEquals(stats, stats(10, 3, 1, 2, 1, 0.3));
        assertNotEquals(stats, stats(10, 3, 1, 1, 2, 0.3));
        assertNotEquals(stats, stats(10, 3, 1, 1, 1, 0.4));
        assertNotEquals(stats, null);
        assertNotEquals(stats, new Object());

        org.junit.jupiter.api.Assertions.assertTrue(
                stats.toString().contains("totalAlerts=10"));
    }

    private AlertService.AlertStats stats(long total, long active,
                                          long critical, long warning,
                                          long info, double rate) {
        AlertService.AlertStats stats = new AlertService.AlertStats();
        stats.setTotalAlerts(total);
        stats.setActiveAlerts(active);
        stats.setCriticalAlerts(critical);
        stats.setWarningAlerts(warning);
        stats.setInfoAlerts(info);
        stats.setAlertRate(rate);
        return stats;
    }

    @Test
    void sloStatusToStringProjectsNameAndWindow() {
        AlertService.SloStatus status = new AlertService.SloStatus();
        status.setSloName("latency_p95");
        ZonedDateTime start = ZonedDateTime.now().minusHours(1);
        ZonedDateTime end = ZonedDateTime.now();
        status.setWindowStart(start);
        status.setWindowEnd(end);

        assertEquals("latency_p95", status.getSloName());
        assertEquals(start, status.getWindowStart());
        assertEquals(end, status.getWindowEnd());
        org.junit.jupiter.api.Assertions.assertTrue(
                status.toString().contains("sloName='latency_p95'"));
    }
}
