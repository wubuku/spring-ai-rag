package com.springairag.core.service;

import com.springairag.core.config.RagAlertProperties;
import com.springairag.core.entity.RagAlert;
import com.springairag.core.entity.RagSilenceSchedule;
import com.springairag.core.repository.AlertRepository;
import com.springairag.core.repository.RagRetrievalEvaluationRepository;
import com.springairag.core.repository.RagRetrievalLogRepository;
import com.springairag.core.repository.RagSilenceScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * AlertService unit tests
 */
@ExtendWith(MockitoExtension.class)
class AlertServiceImplTest {

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private RagRetrievalLogRepository retrievalLogRepository;

    @Mock
    private RagRetrievalEvaluationRepository evaluationRepository;

    @Mock
    private RagSilenceScheduleRepository silenceScheduleRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private RagAlertProperties alertProperties;

    private AlertService alertService;

    @BeforeEach
    void setUp() {
        // Configure alertProperties mock to return default SLO values (matching the static constants)
        lenient().when(alertProperties.getAvailabilitySlo()).thenReturn(99.9);
        lenient().when(alertProperties.getLatencyP50SloMs()).thenReturn(500.0);
        lenient().when(alertProperties.getLatencyP95SloMs()).thenReturn(2000.0);
        lenient().when(alertProperties.getLatencyP99SloMs()).thenReturn(5000.0);
        lenient().when(alertProperties.getMrrSlo()).thenReturn(0.6);
        lenient().when(alertProperties.getHitRateSlo()).thenReturn(0.85);

        alertService = new AlertServiceImpl(alertRepository, retrievalLogRepository, evaluationRepository, silenceScheduleRepository, List.of(notificationService), alertProperties);
    }

    // ==================== shouldAlert ====================

    @Test
    @DisplayName("THRESHOLD_HIGH: should trigger alert when exceeding threshold")
    void shouldAlert_thresholdHigh_aboveThreshold() {
        assertTrue(alertService.shouldAlert("THRESHOLD_HIGH", "latency", 2500, 2000));
    }

    @Test
    @DisplayName("THRESHOLD_HIGH: should not trigger alert when not exceeding threshold")
    void shouldAlert_thresholdHigh_belowThreshold() {
        assertFalse(alertService.shouldAlert("THRESHOLD_HIGH", "latency", 1500, 2000));
    }

    @Test
    @DisplayName("THRESHOLD_LOW: should trigger alert when below threshold")
    void shouldAlert_thresholdLow_belowThreshold() {
        assertTrue(alertService.shouldAlert("THRESHOLD_LOW", "hit_rate", 0.7, 0.85));
    }

    @Test
    @DisplayName("THRESHOLD_LOW: should not trigger alert when not below threshold")
    void shouldAlert_thresholdLow_aboveThreshold() {
        assertFalse(alertService.shouldAlert("THRESHOLD_LOW", "hit_rate", 0.9, 0.85));
    }

    @Test
    @DisplayName("SLO_BREACH: should trigger alert when latency exceeds threshold")
    void shouldAlert_sloBreach_latency() {
        assertTrue(alertService.shouldAlert("SLO_BREACH", "latency_p95", 3000, 2000));
    }

    @Test
    @DisplayName("SLO_BREACH: should trigger alert when quality metric is below threshold")
    void shouldAlert_sloBreach_quality() {
        assertTrue(alertService.shouldAlert("SLO_BREACH", "mrr", 0.4, 0.6));
    }

    @Test
    @DisplayName("alert in silence period should not trigger")
    void shouldAlert_silenced_returnsFalse() {
        alertService.silenceAlert("THRESHOLD_HIGH:latency", 60);
        assertFalse(alertService.shouldAlert("THRESHOLD_HIGH", "latency", 3000, 2000));
    }

    @Test
    @DisplayName("unknown alert type returns false")
    void shouldAlert_unknownType_returnsFalse() {
        assertFalse(alertService.shouldAlert("UNKNOWN", "metric", 100, 50));
    }

    // ==================== fireAlert ====================

    @Test
    @DisplayName("fireAlert creates alert record and returns ID")
    void fireAlert_createsAlert() {
        RagAlert savedAlert = new RagAlert();
        savedAlert.setId(42L);
        savedAlert.setAlertType("THRESHOLD_HIGH");
        savedAlert.setAlertName("High Latency Alert");
        savedAlert.setSeverity("WARNING");
        savedAlert.setStatus("ACTIVE");

        when(alertRepository.save(any(RagAlert.class))).thenReturn(savedAlert);

        Long alertId = alertService.fireAlert(
                "THRESHOLD_HIGH", "High Latency Alert", "P95 exceeds 2000ms",
                "WARNING", Map.of("latency", 2500));

        assertEquals(42L, alertId);
        verify(alertRepository).save(argThat(alert ->
                "THRESHOLD_HIGH".equals(alert.getAlertType()) &&
                "WARNING".equals(alert.getSeverity()) &&
                "ACTIVE".equals(alert.getStatus())
        ));
    }

    @Test
    @DisplayName("fireAlert sends notification after saving alert")
    void fireAlert_sendsNotification() {
        RagAlert savedAlert = new RagAlert();
        savedAlert.setId(1L);
        when(alertRepository.save(any(RagAlert.class))).thenReturn(savedAlert);

        alertService.fireAlert("SLO_BREACH", "P99 Latency", "P99 exceeded",
                "CRITICAL", Map.of("p99_latency_ms", 5500));

        verify(notificationService).sendAlert(
                eq("SLO_BREACH"),
                eq("P99 Latency"),
                eq("CRITICAL"),
                eq("P99 exceeded"),
                eq(Map.of("p99_latency_ms", 5500))
        );
    }

    // ==================== resolveAlert ====================

    @Test
    @DisplayName("resolveAlert marks alert as resolved")
    void resolveAlert_resolvesExistingAlert() {
        RagAlert alert = new RagAlert();
        alert.setId(1L);
        alert.setStatus("ACTIVE");

        when(alertRepository.findById(1L)).thenReturn(Optional.of(alert));
        when(alertRepository.save(any(RagAlert.class))).thenReturn(alert);

        alertService.resolveAlert(1L, "Fixed");

        verify(alertRepository).save(argThat(a ->
                "RESOLVED".equals(a.getStatus()) &&
                "Fixed".equals(a.getResolution()) &&
                a.getResolvedAt() != null
        ));
    }

    @Test
    @DisplayName("resolveAlert does not throw for non-existent ID")
    void resolveAlert_nonExistent_noException() {
        when(alertRepository.findById(999L)).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> alertService.resolveAlert(999L, "none"));
    }

    // ==================== getActiveAlerts ====================

    @Test
    @DisplayName("getActiveAlerts returns active alert list")
    void getActiveAlerts_returnsList() {
        RagAlert alert = new RagAlert();
        alert.setId(1L);
        alert.setAlertName("Test Alert");
        alert.setStatus("ACTIVE");
        alert.setFiredAt(ZonedDateTime.now());

        when(alertRepository.findByStatusOrderByFiredAtDesc("ACTIVE")).thenReturn(List.of(alert));

        List<AlertService.AlertRecord> active = alertService.getActiveAlerts();
        assertEquals(1, active.size());
        assertEquals("Test Alert", active.get(0).getAlertName());
    }

    // ==================== getAlertStats ====================

    @Test
    @DisplayName("getAlertStats returns correct statistics")
    void getAlertStats_returnsStats() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(alertRepository.countByFiredAtBetween(start, end)).thenReturn(10L);
        when(alertRepository.countActiveAlerts(start, end)).thenReturn(3L);
        when(alertRepository.countBySeverity(start, end, "CRITICAL")).thenReturn(1L);
        when(alertRepository.countBySeverity(start, end, "WARNING")).thenReturn(6L);
        when(alertRepository.countBySeverity(start, end, "INFO")).thenReturn(3L);

        AlertService.AlertStats stats = alertService.getAlertStats(start, end);

        assertEquals(10, stats.getTotalAlerts());
        assertEquals(3, stats.getActiveAlerts());
        assertEquals(1, stats.getCriticalAlerts());
        assertEquals(6, stats.getWarningAlerts());
        assertEquals(3, stats.getInfoAlerts());
        assertTrue(stats.getAlertRate() > 0);
    }

    // ==================== getAlertHistory ====================

    @Test
    @DisplayName("getAlertHistory returns alert history list")
    void getAlertHistory_returnsList() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(7);
        ZonedDateTime end = ZonedDateTime.now();

        RagAlert alert1 = new RagAlert();
        alert1.setId(1L);
        alert1.setAlertType("THRESHOLD_HIGH");
        alert1.setAlertName("High Latency");
        alert1.setSeverity("WARNING");
        alert1.setStatus("ACTIVE");
        alert1.setFiredAt(ZonedDateTime.now().minusDays(1));

        RagAlert alert2 = new RagAlert();
        alert2.setId(2L);
        alert2.setAlertType("SLO_BREACH");
        alert2.setAlertName("SLO Breach");
        alert2.setSeverity("CRITICAL");
        alert2.setStatus("RESOLVED");
        alert2.setFiredAt(ZonedDateTime.now().minusDays(2));

        when(alertRepository.findAlertHistory(start, end, "WARNING", null))
                .thenReturn(List.of(alert1));

        List<AlertService.AlertRecord> history = alertService.getAlertHistory(start, end, "WARNING", null);
        assertEquals(1, history.size());
        assertEquals("High Latency", history.get(0).getAlertName());
    }

    @Test
    @DisplayName("getAlertHistory empty result returns empty list")
    void getAlertHistory_empty_returnsEmptyList() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(alertRepository.findAlertHistory(start, end, null, null)).thenReturn(List.of());

        List<AlertService.AlertRecord> history = alertService.getAlertHistory(start, end, null, null);
        assertTrue(history.isEmpty());
    }

    // ==================== checkAllSlos ====================

    @Test
    @DisplayName("checkAllSlos returns all 6 SLO statuses")
    void checkAllSlos_returnsSixSlos() {
        ZonedDateTime start = ZonedDateTime.now().minusHours(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(retrievalLogRepository.countByCreatedAtBetween(start, end)).thenReturn(100L);
        when(retrievalLogRepository.findAvgTotalTime(start, end)).thenReturn(800.0);
        when(evaluationRepository.findAvgMrr(start, end)).thenReturn(0.7);
        when(evaluationRepository.findAvgHitRate(start, end)).thenReturn(0.9);

        Map<String, AlertService.SloStatus> statuses = alertService.checkAllSlos(start, end);

        assertEquals(6, statuses.size());
        assertTrue(statuses.containsKey("availability"));
        assertTrue(statuses.containsKey("latency_p50"));
        assertTrue(statuses.containsKey("latency_p95"));
        assertTrue(statuses.containsKey("latency_p99"));
        assertTrue(statuses.containsKey("mrr"));
        assertTrue(statuses.containsKey("hit_rate"));

        // latency_p50 (800ms < 500ms threshold should NOT be met)
        assertFalse(statuses.get("latency_p50").isMet());
        // latency_p95 (800ms < 2000ms threshold should be met)
        assertTrue(statuses.get("latency_p95").isMet());
        // mrr (0.7 >= 0.6 threshold)
        assertTrue(statuses.get("mrr").isMet());
    }

    // ==================== checkSlo: availability with data ====================

    @Test
    @DisplayName("checkSlo: availability with data and latency met")
    void checkSlo_availability_withData_met() {
        ZonedDateTime start = ZonedDateTime.now().minusHours(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(retrievalLogRepository.countByCreatedAtBetween(start, end)).thenReturn(100L);
        when(retrievalLogRepository.findAvgTotalTime(start, end)).thenReturn(1000.0);

        AlertService.SloStatus status = alertService.checkSlo("availability", start, end);

        assertTrue(status.isMet());
        assertEquals(100.0, status.getActual());
        assertEquals("AVAILABILITY", status.getSloType());
        assertTrue(status.getErrorBudget() > 0);
        assertEquals(100.0, status.getErrorBudgetRemaining());
    }

    @Test
    @DisplayName("checkSlo: availability with data but latency not met")
    void checkSlo_availability_withData_notMet() {
        ZonedDateTime start = ZonedDateTime.now().minusHours(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(retrievalLogRepository.countByCreatedAtBetween(start, end)).thenReturn(100L);
        when(retrievalLogRepository.findAvgTotalTime(start, end)).thenReturn(6000.0);

        AlertService.SloStatus status = alertService.checkSlo("availability", start, end);

        assertFalse(status.isMet());
        assertEquals(99.0, status.getActual());
        assertEquals(0.0, status.getErrorBudgetRemaining());
    }

    // ==================== checkSlo: latency p50/p99 ====================

    @Test
    @DisplayName("checkSlo: latency_p50 met")
    void checkSlo_latencyP50_met() {
        ZonedDateTime start = ZonedDateTime.now().minusHours(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(retrievalLogRepository.findAvgTotalTime(start, end)).thenReturn(300.0);

        AlertService.SloStatus status = alertService.checkSlo("latency_p50", start, end);

        assertEquals("latency_p50", status.getSloName());
        assertEquals(500, status.getTarget());
        assertEquals("LATENCY", status.getSloType());
        assertTrue(status.isMet());
    }

    @Test
    @DisplayName("checkSlo: latency_p99 exceeds threshold not met")
    void checkSlo_latencyP99_breached() {
        ZonedDateTime start = ZonedDateTime.now().minusHours(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(retrievalLogRepository.findAvgTotalTime(start, end)).thenReturn(6000.0);

        AlertService.SloStatus status = alertService.checkSlo("latency_p99", start, end);

        assertEquals("latency_p99", status.getSloName());
        assertEquals(5000, status.getTarget());
        assertFalse(status.isMet());
    }

    @Test
    @DisplayName("checkSlo: latency with no data returns 0")
    void checkSlo_latency_noData() {
        ZonedDateTime start = ZonedDateTime.now().minusHours(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(retrievalLogRepository.findAvgTotalTime(start, end)).thenReturn(null);

        AlertService.SloStatus status = alertService.checkSlo("latency_p95", start, end);

        assertEquals(0, status.getActual());
        assertTrue(status.isMet()); // 0 <= 2000
    }

    // ==================== checkSlo: quality mrr/hit_rate ====================

    @Test
    @DisplayName("checkSlo: mrr met")
    void checkSlo_mrr_met() {
        ZonedDateTime start = ZonedDateTime.now().minusHours(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(evaluationRepository.findAvgMrr(start, end)).thenReturn(0.75);

        AlertService.SloStatus status = alertService.checkSlo("mrr", start, end);

        assertEquals("mrr", status.getSloName());
        assertEquals("QUALITY", status.getSloType());
        assertEquals("score", status.getUnit());
        assertTrue(status.isMet());
        assertEquals(0.75, status.getActual(), 0.01);
    }

    @Test
    @DisplayName("checkSlo: mrr with no data returns 0")
    void checkSlo_mrr_noData() {
        ZonedDateTime start = ZonedDateTime.now().minusHours(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(evaluationRepository.findAvgMrr(start, end)).thenReturn(null);

        AlertService.SloStatus status = alertService.checkSlo("mrr", start, end);

        assertEquals(0, status.getActual());
        assertFalse(status.isMet());
    }

    @Test
    @DisplayName("checkSlo: hit_rate met")
    void checkSlo_hitRate_met() {
        ZonedDateTime start = ZonedDateTime.now().minusHours(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(evaluationRepository.findAvgHitRate(start, end)).thenReturn(0.92);

        AlertService.SloStatus status = alertService.checkSlo("hit_rate", start, end);

        assertEquals("hit_rate", status.getSloName());
        assertEquals("%", status.getUnit());
        assertTrue(status.isMet());
        assertEquals(92.0, status.getActual(), 0.01);
    }

    @Test
    @DisplayName("checkSlo: hit_rate not met")
    void checkSlo_hitRate_notMet() {
        ZonedDateTime start = ZonedDateTime.now().minusHours(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(evaluationRepository.findAvgHitRate(start, end)).thenReturn(0.005);

        AlertService.SloStatus status = alertService.checkSlo("hit_rate", start, end);

        assertFalse(status.isMet());
        assertEquals(0.5, status.getActual(), 0.01); // 0.005 * 100 = 0.5%
    }

    @Test
    @DisplayName("checkSlo: hit_rate with no data")
    void checkSlo_hitRate_noData() {
        ZonedDateTime start = ZonedDateTime.now().minusHours(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(evaluationRepository.findAvgHitRate(start, end)).thenReturn(null);

        AlertService.SloStatus status = alertService.checkSlo("hit_rate", start, end);

        assertEquals(0, status.getActual());
        assertFalse(status.isMet());
    }

    // ==================== getAlertStats zero-hour window ====================

    @Test
    @DisplayName("getAlertStats zero-hour window alertRate is 0")
    void getAlertStats_zeroHours_alertRateZero() {
        ZonedDateTime now = ZonedDateTime.now();

        when(alertRepository.countByFiredAtBetween(now, now)).thenReturn(0L);
        when(alertRepository.countActiveAlerts(now, now)).thenReturn(0L);
        when(alertRepository.countBySeverity(now, now, "CRITICAL")).thenReturn(0L);
        when(alertRepository.countBySeverity(now, now, "WARNING")).thenReturn(0L);
        when(alertRepository.countBySeverity(now, now, "INFO")).thenReturn(0L);

        AlertService.AlertStats stats = alertService.getAlertStats(now, now);

        assertEquals(0, stats.getTotalAlerts());
        assertEquals(0, stats.getAlertRate());
    }

    // ==================== Silence expired recovery ====================

    @Test
    @DisplayName("silence expired: alert should resume triggering")
    void shouldAlert_silenceExpired_resumesTrigger() {
        // Silence -1 minute (already expired)
        alertService.silenceAlert("THRESHOLD_HIGH:latency", -1);
        assertTrue(alertService.shouldAlert("THRESHOLD_HIGH", "latency", 3000, 2000));
    }

    // ==================== fireAlert field completeness ====================

    @Test
    @DisplayName("fireAlert sets all fields")
    void fireAlert_setsAllFields() {
        RagAlert savedAlert = new RagAlert();
        savedAlert.setId(1L);

        when(alertRepository.save(any(RagAlert.class))).thenReturn(savedAlert);

        Map<String, Object> metrics = Map.of("latency", 3000, "p95", 2500);
        alertService.fireAlert("SLO_BREACH", "Latency SLO", "P95 exceeded", "CRITICAL", metrics);

        verify(alertRepository).save(argThat(alert ->
                "SLO_BREACH".equals(alert.getAlertType()) &&
                "Latency SLO".equals(alert.getAlertName()) &&
                "P95 exceeded".equals(alert.getMessage()) &&
                "CRITICAL".equals(alert.getSeverity()) &&
                alert.getMetrics() != null &&
                "ACTIVE".equals(alert.getStatus()) &&
                alert.getFiredAt() != null
        ));
    }

    // ==================== resolveAlert complete field validation ====================

    @Test
    @DisplayName("resolveAlert sets resolvedAt and resolution")
    void resolveAlert_setsResolutionFields() {
        RagAlert alert = new RagAlert();
        alert.setId(5L);
        alert.setStatus("ACTIVE");

        when(alertRepository.findById(5L)).thenReturn(Optional.of(alert));

        alertService.resolveAlert(5L, "Scaled up");

        verify(alertRepository).save(argThat(a ->
                "RESOLVED".equals(a.getStatus()) &&
                "Scaled up".equals(a.getResolution()) &&
                a.getResolvedAt() != null
        ));
    }

    // ==================== SLO_BREACH: latency breach ====================

    @Test
    @DisplayName("SLO_BREACH: time metric should trigger alert when exceeding threshold")
    void shouldAlert_sloBreach_timeMetric() {
        assertTrue(alertService.shouldAlert("SLO_BREACH", "response_time", 3000, 2000));
    }

    // ==================== silenceAlert covers expired cleanup path ====================

    @Test
    @DisplayName("multiple shouldAlert calls should clean up expired silence records")
    void shouldAlert_cleansExpiredSilence() {
        alertService.silenceAlert("THRESHOLD_HIGH:metric1", -1);
        alertService.silenceAlert("THRESHOLD_HIGH:metric2", 60);

        // First call triggers cleanup
        assertTrue(alertService.shouldAlert("THRESHOLD_HIGH", "metric1", 100, 50));
        // Silenced ones still do not trigger
        assertFalse(alertService.shouldAlert("THRESHOLD_HIGH", "metric2", 100, 50));
    }

    // ==================== AlertRecord completeness ====================

    @Test
    @DisplayName("getActiveAlerts maps all AlertRecord fields")
    void getActiveAlerts_mapsAllFields() {
        RagAlert alert = new RagAlert();
        alert.setId(10L);
        alert.setAlertType("THRESHOLD_LOW");
        alert.setAlertName("Low Hit Rate");
        alert.setMessage("hit_rate < 0.85");
        alert.setSeverity("WARNING");
        alert.setMetrics(Map.of("hit_rate", 0.7));
        alert.setStatus("ACTIVE");
        alert.setFiredAt(ZonedDateTime.now().minusMinutes(5));

        when(alertRepository.findByStatusOrderByFiredAtDesc("ACTIVE")).thenReturn(List.of(alert));

        List<AlertService.AlertRecord> records = alertService.getActiveAlerts();
        assertEquals(1, records.size());

        AlertService.AlertRecord r = records.get(0);
        assertEquals(10L, r.getId());
        assertEquals("THRESHOLD_LOW", r.getAlertType());
        assertEquals("Low Hit Rate", r.getAlertName());
        assertEquals("hit_rate < 0.85", r.getMessage());
        assertEquals("WARNING", r.getSeverity());
        assertEquals("ACTIVE", r.getStatus());
        assertNotNull(r.getMetrics());
        assertNotNull(r.getFiredAt());
    }

    // ==================== Silence Schedule Integration ====================

    @Test
    @DisplayName("shouldAlert returns false when alert is silenced by active ONE_TIME schedule")
    void shouldAlert_silencedBySchedule() {
        ZonedDateTime now = ZonedDateTime.now();
        RagSilenceSchedule schedule = new RagSilenceSchedule();
        schedule.setSilenceType("ONE_TIME");
        schedule.setStartTime(now.minusHours(1).toString());
        schedule.setEndTime(now.plusHours(1).toString());
        schedule.setEnabled(true);

        when(silenceScheduleRepository.findByAlertKeyAndEnabledTrue("THRESHOLD_HIGH:latency"))
                .thenReturn(List.of(schedule));

        assertFalse(alertService.shouldAlert("THRESHOLD_HIGH", "latency", 3000, 2000));
    }

    @Test
    @DisplayName("shouldAlert returns true when schedule is expired")
    void shouldAlert_scheduleExpired() {
        ZonedDateTime now = ZonedDateTime.now();
        RagSilenceSchedule schedule = new RagSilenceSchedule();
        schedule.setSilenceType("ONE_TIME");
        schedule.setStartTime(now.minusHours(2).toString());
        schedule.setEndTime(now.minusHours(1).toString());
        schedule.setEnabled(true);

        when(silenceScheduleRepository.findByAlertKeyAndEnabledTrue("THRESHOLD_HIGH:latency"))
                .thenReturn(List.of(schedule));

        assertTrue(alertService.shouldAlert("THRESHOLD_HIGH", "latency", 3000, 2000));
    }

    @Test
    @DisplayName("shouldAlert returns false for wildcard schedule (null alertKey) covering all alerts")
    void shouldAlert_wildcardSchedule() {
        ZonedDateTime now = ZonedDateTime.now();
        RagSilenceSchedule schedule = new RagSilenceSchedule();
        schedule.setSilenceType("ONE_TIME");
        schedule.setStartTime(now.minusHours(1).toString());
        schedule.setEndTime(now.plusHours(1).toString());
        schedule.setEnabled(true);

        when(silenceScheduleRepository.findByAlertKeyAndEnabledTrue("THRESHOLD_HIGH:latency"))
                .thenReturn(List.of());
        when(silenceScheduleRepository.findByAlertKeyAndEnabledTrue(null))
                .thenReturn(List.of(schedule));

        assertFalse(alertService.shouldAlert("THRESHOLD_HIGH", "latency", 3000, 2000));
    }

    @Test
    @DisplayName("fireAlert returns null when alert is silenced by schedule")
    void fireAlert_silencedBySchedule() {
        ZonedDateTime now = ZonedDateTime.now();
        RagSilenceSchedule schedule = new RagSilenceSchedule();
        schedule.setSilenceType("ONE_TIME");
        schedule.setStartTime(now.minusHours(1).toString());
        schedule.setEndTime(now.plusHours(1).toString());
        schedule.setEnabled(true);

        when(silenceScheduleRepository.findByAlertKeyAndEnabledTrue("THRESHOLD_HIGH:latency"))
                .thenReturn(List.of(schedule));

        Long alertId = alertService.fireAlert("THRESHOLD_HIGH", "latency",
                "P99 exceeded", "WARNING", Map.of());

        assertNull(alertId);
        verify(alertRepository, never()).save(any());
    }

    @Test
    @DisplayName("shouldAlert skips schedule check when repository is null (defensive)")
    void shouldAlert_nullRepository() {
        AlertServiceImpl serviceWithoutRepo = new AlertServiceImpl(
                alertRepository, retrievalLogRepository, evaluationRepository, null, List.of(), alertProperties);
        assertTrue(serviceWithoutRepo.shouldAlert("THRESHOLD_HIGH", "latency", 3000, 2000));
    }

    // ==================== unsilenceAlert ====================

    @Test
    @DisplayName("unsilenceAlert removes existing silence entry and returns true")
    void unsilenceAlert_existingKey_returnsTrue() {
        alertService.silenceAlert("THRESHOLD_HIGH:latency", 60);
        assertTrue(alertService.unsilenceAlert("THRESHOLD_HIGH:latency"));
        // Verify alert can fire again
        assertTrue(alertService.shouldAlert("THRESHOLD_HIGH", "latency", 3000, 2000));
    }

    @Test
    @DisplayName("unsilenceAlert returns false when alert was not silenced")
    void unsilenceAlert_notSilenced_returnsFalse() {
        assertFalse(alertService.unsilenceAlert("THRESHOLD_HIGH:latency"));
    }

    // ==================== getSilencedAlerts ====================

    @Test
    @DisplayName("getSilencedAlerts returns all silenced alerts with expiration times")
    void getSilencedAlerts_multipleEntries_returnsAll() {
        alertService.silenceAlert("THRESHOLD_HIGH:latency", 60);
        alertService.silenceAlert("SLO_BREACH:mrr", 30);

        Map<String, ZonedDateTime> silenced = alertService.getSilencedAlerts();

        assertEquals(2, silenced.size());
        assertTrue(silenced.containsKey("THRESHOLD_HIGH:latency"));
        assertTrue(silenced.containsKey("SLO_BREACH:mrr"));
        assertNotNull(silenced.get("THRESHOLD_HIGH:latency"));
        assertNotNull(silenced.get("SLO_BREACH:mrr"));
    }

    @Test
    @DisplayName("getSilencedAlerts returns empty map when no alerts are silenced")
    void getSilencedAlerts_empty_returnsEmpty() {
        Map<String, ZonedDateTime> silenced = alertService.getSilencedAlerts();
        assertTrue(silenced.isEmpty());
    }
}
