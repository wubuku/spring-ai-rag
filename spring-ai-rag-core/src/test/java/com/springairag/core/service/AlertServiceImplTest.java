package com.springairag.core.service;

import com.springairag.core.entity.RagAlert;
import com.springairag.core.repository.AlertRepository;
import com.springairag.core.repository.RagRetrievalEvaluationRepository;
import com.springairag.core.repository.RagRetrievalLogRepository;
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

/**
 * AlertService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class AlertServiceImplTest {

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private RagRetrievalLogRepository retrievalLogRepository;

    @Mock
    private RagRetrievalEvaluationRepository evaluationRepository;

    private AlertService alertService;

    @BeforeEach
    void setUp() {
        alertService = new AlertServiceImpl(alertRepository, retrievalLogRepository, evaluationRepository);
    }

    // ==================== shouldAlert ====================

    @Test
    @DisplayName("THRESHOLD_HIGH: 超过阈值应告警")
    void shouldAlert_thresholdHigh_aboveThreshold() {
        assertTrue(alertService.shouldAlert("THRESHOLD_HIGH", "latency", 2500, 2000));
    }

    @Test
    @DisplayName("THRESHOLD_HIGH: 未超过阈值不应告警")
    void shouldAlert_thresholdHigh_belowThreshold() {
        assertFalse(alertService.shouldAlert("THRESHOLD_HIGH", "latency", 1500, 2000));
    }

    @Test
    @DisplayName("THRESHOLD_LOW: 低于阈值应告警")
    void shouldAlert_thresholdLow_belowThreshold() {
        assertTrue(alertService.shouldAlert("THRESHOLD_LOW", "hit_rate", 0.7, 0.85));
    }

    @Test
    @DisplayName("THRESHOLD_LOW: 未低于阈值不应告警")
    void shouldAlert_thresholdLow_aboveThreshold() {
        assertFalse(alertService.shouldAlert("THRESHOLD_LOW", "hit_rate", 0.9, 0.85));
    }

    @Test
    @DisplayName("SLO_BREACH: 延迟指标超过阈值应告警")
    void shouldAlert_sloBreach_latency() {
        assertTrue(alertService.shouldAlert("SLO_BREACH", "latency_p95", 3000, 2000));
    }

    @Test
    @DisplayName("SLO_BREACH: 质量指标低于阈值应告警")
    void shouldAlert_sloBreach_quality() {
        assertTrue(alertService.shouldAlert("SLO_BREACH", "mrr", 0.4, 0.6));
    }

    @Test
    @DisplayName("静默中的告警不应触发")
    void shouldAlert_silenced_returnsFalse() {
        alertService.silenceAlert("THRESHOLD_HIGH:latency", 60);
        assertFalse(alertService.shouldAlert("THRESHOLD_HIGH", "latency", 3000, 2000));
    }

    @Test
    @DisplayName("未知告警类型返回 false")
    void shouldAlert_unknownType_returnsFalse() {
        assertFalse(alertService.shouldAlert("UNKNOWN", "metric", 100, 50));
    }

    // ==================== fireAlert ====================

    @Test
    @DisplayName("fireAlert 创建告警记录并返回 ID")
    void fireAlert_createsAlert() {
        RagAlert savedAlert = new RagAlert();
        savedAlert.setId(42L);
        savedAlert.setAlertType("THRESHOLD_HIGH");
        savedAlert.setAlertName("高延迟告警");
        savedAlert.setSeverity("WARNING");
        savedAlert.setStatus("ACTIVE");

        when(alertRepository.save(any(RagAlert.class))).thenReturn(savedAlert);

        Long alertId = alertService.fireAlert(
                "THRESHOLD_HIGH", "高延迟告警", "P95 延迟超过 2000ms",
                "WARNING", Map.of("latency", 2500));

        assertEquals(42L, alertId);
        verify(alertRepository).save(argThat(alert ->
                "THRESHOLD_HIGH".equals(alert.getAlertType()) &&
                "WARNING".equals(alert.getSeverity()) &&
                "ACTIVE".equals(alert.getStatus())
        ));
    }

    // ==================== resolveAlert ====================

    @Test
    @DisplayName("resolveAlert 将告警标记为已解决")
    void resolveAlert_resolvesExistingAlert() {
        RagAlert alert = new RagAlert();
        alert.setId(1L);
        alert.setStatus("ACTIVE");

        when(alertRepository.findById(1L)).thenReturn(Optional.of(alert));
        when(alertRepository.save(any(RagAlert.class))).thenReturn(alert);

        alertService.resolveAlert(1L, "已修复");

        verify(alertRepository).save(argThat(a ->
                "RESOLVED".equals(a.getStatus()) &&
                "已修复".equals(a.getResolution()) &&
                a.getResolvedAt() != null
        ));
    }

    @Test
    @DisplayName("resolveAlert 对不存在的 ID 不抛异常")
    void resolveAlert_nonExistent_noException() {
        when(alertRepository.findById(999L)).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> alertService.resolveAlert(999L, "无"));
    }

    // ==================== getActiveAlerts ====================

    @Test
    @DisplayName("getActiveAlerts 返回活跃告警列表")
    void getActiveAlerts_returnsList() {
        RagAlert alert = new RagAlert();
        alert.setId(1L);
        alert.setAlertName("测试告警");
        alert.setStatus("ACTIVE");
        alert.setFiredAt(ZonedDateTime.now());

        when(alertRepository.findByStatusOrderByFiredAtDesc("ACTIVE")).thenReturn(List.of(alert));

        List<AlertService.AlertRecord> active = alertService.getActiveAlerts();
        assertEquals(1, active.size());
        assertEquals("测试告警", active.get(0).getAlertName());
    }

    // ==================== getAlertStats ====================

    @Test
    @DisplayName("getAlertStats 返回正确统计数据")
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

    // ==================== checkSlo ====================

    @Test
    @DisplayName("checkSlo: latency_p95 返回正确状态")
    void checkSlo_latencyP95() {
        ZonedDateTime start = ZonedDateTime.now().minusHours(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(retrievalLogRepository.findAvgTotalTime(start, end)).thenReturn(1500.0);

        AlertService.SloStatus status = alertService.checkSlo("latency_p95", start, end);

        assertEquals("latency_p95", status.getSloName());
        assertEquals(2000, status.getTarget());
        assertEquals(1500.0, status.getActual());
        assertTrue(status.isMet());
    }

    @Test
    @DisplayName("checkSlo: latency_p95 超过阈值不达标")
    void checkSlo_latencyP95_breached() {
        ZonedDateTime start = ZonedDateTime.now().minusHours(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(retrievalLogRepository.findAvgTotalTime(start, end)).thenReturn(3000.0);

        AlertService.SloStatus status = alertService.checkSlo("latency_p95", start, end);

        assertFalse(status.isMet());
    }

    @Test
    @DisplayName("checkSlo: availability 无数据时达标")
    void checkSlo_availability_noData() {
        ZonedDateTime start = ZonedDateTime.now().minusHours(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(retrievalLogRepository.countByCreatedAtBetween(start, end)).thenReturn(0L);

        AlertService.SloStatus status = alertService.checkSlo("availability", start, end);

        assertTrue(status.isMet());
        assertEquals(100.0, status.getActual());
    }

    @Test
    @DisplayName("checkSlo: 未知 SLO 名称返回未达标")
    void checkSlo_unknown_returnsNotMet() {
        AlertService.SloStatus status = alertService.checkSlo("unknown",
                ZonedDateTime.now().minusHours(1), ZonedDateTime.now());

        assertEquals("unknown", status.getSloName());
        assertFalse(status.isMet());
    }
}
