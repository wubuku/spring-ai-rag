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

    // ==================== getAlertHistory ====================

    @Test
    @DisplayName("getAlertHistory 返回告警历史列表")
    void getAlertHistory_returnsList() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(7);
        ZonedDateTime end = ZonedDateTime.now();

        RagAlert alert1 = new RagAlert();
        alert1.setId(1L);
        alert1.setAlertType("THRESHOLD_HIGH");
        alert1.setAlertName("高延迟");
        alert1.setSeverity("WARNING");
        alert1.setStatus("ACTIVE");
        alert1.setFiredAt(ZonedDateTime.now().minusDays(1));

        RagAlert alert2 = new RagAlert();
        alert2.setId(2L);
        alert2.setAlertType("SLO_BREACH");
        alert2.setAlertName("SLO 违约");
        alert2.setSeverity("CRITICAL");
        alert2.setStatus("RESOLVED");
        alert2.setFiredAt(ZonedDateTime.now().minusDays(2));

        when(alertRepository.findAlertHistory(start, end, "WARNING", null))
                .thenReturn(List.of(alert1));

        List<AlertService.AlertRecord> history = alertService.getAlertHistory(start, end, "WARNING", null);
        assertEquals(1, history.size());
        assertEquals("高延迟", history.get(0).getAlertName());
    }

    @Test
    @DisplayName("getAlertHistory 空结果返回空列表")
    void getAlertHistory_empty_returnsEmptyList() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(alertRepository.findAlertHistory(start, end, null, null)).thenReturn(List.of());

        List<AlertService.AlertRecord> history = alertService.getAlertHistory(start, end, null, null);
        assertTrue(history.isEmpty());
    }

    // ==================== checkAllSlos ====================

    @Test
    @DisplayName("checkAllSlos 返回全部 6 个 SLO 状态")
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
    @DisplayName("checkSlo: availability 有数据且延迟达标")
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
    @DisplayName("checkSlo: availability 有数据但延迟不达标")
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
    @DisplayName("checkSlo: latency_p50 达标")
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
    @DisplayName("checkSlo: latency_p99 超过阈值不达标")
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
    @DisplayName("checkSlo: latency 无数据时 actual 为 0")
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
    @DisplayName("checkSlo: mrr 达标")
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
    @DisplayName("checkSlo: mrr 无数据时 actual 为 0")
    void checkSlo_mrr_noData() {
        ZonedDateTime start = ZonedDateTime.now().minusHours(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(evaluationRepository.findAvgMrr(start, end)).thenReturn(null);

        AlertService.SloStatus status = alertService.checkSlo("mrr", start, end);

        assertEquals(0, status.getActual());
        assertFalse(status.isMet());
    }

    @Test
    @DisplayName("checkSlo: hit_rate 达标")
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
    @DisplayName("checkSlo: hit_rate 不达标")
    void checkSlo_hitRate_notMet() {
        ZonedDateTime start = ZonedDateTime.now().minusHours(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(evaluationRepository.findAvgHitRate(start, end)).thenReturn(0.005);

        AlertService.SloStatus status = alertService.checkSlo("hit_rate", start, end);

        assertFalse(status.isMet());
        assertEquals(0.5, status.getActual(), 0.01); // 0.005 * 100 = 0.5%
    }

    @Test
    @DisplayName("checkSlo: hit_rate 无数据")
    void checkSlo_hitRate_noData() {
        ZonedDateTime start = ZonedDateTime.now().minusHours(1);
        ZonedDateTime end = ZonedDateTime.now();

        when(evaluationRepository.findAvgHitRate(start, end)).thenReturn(null);

        AlertService.SloStatus status = alertService.checkSlo("hit_rate", start, end);

        assertEquals(0, status.getActual());
        assertFalse(status.isMet());
    }

    // ==================== getAlertStats 零小时 ====================

    @Test
    @DisplayName("getAlertStats 零小时窗口 alertRate 为 0")
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

    // ==================== 静默过期恢复 ====================

    @Test
    @DisplayName("静默过期后告警恢复触发")
    void shouldAlert_silenceExpired_resumesTrigger() {
        // 静默 -1 分钟（已过期）
        alertService.silenceAlert("THRESHOLD_HIGH:latency", -1);
        assertTrue(alertService.shouldAlert("THRESHOLD_HIGH", "latency", 3000, 2000));
    }

    // ==================== fireAlert 字段完整性 ====================

    @Test
    @DisplayName("fireAlert 设置所有字段")
    void fireAlert_setsAllFields() {
        RagAlert savedAlert = new RagAlert();
        savedAlert.setId(1L);

        when(alertRepository.save(any(RagAlert.class))).thenReturn(savedAlert);

        Map<String, Object> metrics = Map.of("latency", 3000, "p95", 2500);
        alertService.fireAlert("SLO_BREACH", "延迟 SLO", "P95 超标", "CRITICAL", metrics);

        verify(alertRepository).save(argThat(alert ->
                "SLO_BREACH".equals(alert.getAlertType()) &&
                "延迟 SLO".equals(alert.getAlertName()) &&
                "P95 超标".equals(alert.getMessage()) &&
                "CRITICAL".equals(alert.getSeverity()) &&
                alert.getMetrics() != null &&
                "ACTIVE".equals(alert.getStatus()) &&
                alert.getFiredAt() != null
        ));
    }

    // ==================== resolveAlert 完整字段验证 ====================

    @Test
    @DisplayName("resolveAlert 设置 resolvedAt 和 resolution")
    void resolveAlert_setsResolutionFields() {
        RagAlert alert = new RagAlert();
        alert.setId(5L);
        alert.setStatus("ACTIVE");

        when(alertRepository.findById(5L)).thenReturn(Optional.of(alert));

        alertService.resolveAlert(5L, "扩容处理");

        verify(alertRepository).save(argThat(a ->
                "RESOLVED".equals(a.getStatus()) &&
                "扩容处理".equals(a.getResolution()) &&
                a.getResolvedAt() != null
        ));
    }

    // ==================== SLO_BREACH: latency 不达标 ====================

    @Test
    @DisplayName("SLO_BREACH: time 指标超过阈值应告警")
    void shouldAlert_sloBreach_timeMetric() {
        assertTrue(alertService.shouldAlert("SLO_BREACH", "response_time", 3000, 2000));
    }

    // ==================== silenceAlert 覆盖过期清理路径 ====================

    @Test
    @DisplayName("多次 shouldAlert 清理过期静默记录")
    void shouldAlert_cleansExpiredSilence() {
        alertService.silenceAlert("THRESHOLD_HIGH:metric1", -1);
        alertService.silenceAlert("THRESHOLD_HIGH:metric2", 60);

        // 第一次调用触发 cleanup
        assertTrue(alertService.shouldAlert("THRESHOLD_HIGH", "metric1", 100, 50));
        // 已静默的仍不触发
        assertFalse(alertService.shouldAlert("THRESHOLD_HIGH", "metric2", 100, 50));
    }

    // ==================== AlertRecord 完整性 ====================

    @Test
    @DisplayName("getActiveAlerts 映射所有 AlertRecord 字段")
    void getActiveAlerts_mapsAllFields() {
        RagAlert alert = new RagAlert();
        alert.setId(10L);
        alert.setAlertType("THRESHOLD_LOW");
        alert.setAlertName("命中率低");
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
        assertEquals("命中率低", r.getAlertName());
        assertEquals("hit_rate < 0.85", r.getMessage());
        assertEquals("WARNING", r.getSeverity());
        assertEquals("ACTIVE", r.getStatus());
        assertNotNull(r.getMetrics());
        assertNotNull(r.getFiredAt());
    }
}
