package com.springairag.core.controller;

import com.springairag.core.service.AlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AlertController 单元测试
 */
class AlertControllerTest {

    private AlertService alertService;
    private AlertController controller;

    @BeforeEach
    void setUp() {
        alertService = mock(AlertService.class);
        controller = new AlertController(alertService);
    }

    // ==================== getActiveAlerts ====================

    @Test
    void getActiveAlerts_returnsList() {
        AlertService.AlertRecord a1 = new AlertService.AlertRecord();
        a1.setId(1L);
        a1.setAlertType("latency");
        AlertService.AlertRecord a2 = new AlertService.AlertRecord();
        a2.setId(2L);
        a2.setAlertType("error_rate");

        when(alertService.getActiveAlerts()).thenReturn(List.of(a1, a2));

        ResponseEntity<List<AlertService.AlertRecord>> response = controller.getActiveAlerts();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().size());
        assertEquals(1L, response.getBody().get(0).getId());
        assertEquals(2L, response.getBody().get(1).getId());
    }

    @Test
    void getActiveAlerts_empty_returnsEmptyList() {
        when(alertService.getActiveAlerts()).thenReturn(List.of());

        ResponseEntity<List<AlertService.AlertRecord>> response = controller.getActiveAlerts();

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().isEmpty());
    }

    // ==================== getAlertHistory ====================

    @Test
    void getAlertHistory_returnsFilteredList() {
        AlertService.AlertRecord a1 = new AlertService.AlertRecord();
        a1.setId(1L);
        a1.setSeverity("CRITICAL");

        when(alertService.getAlertHistory(any(), any(), eq("CRITICAL"), eq(null)))
                .thenReturn(List.of(a1));

        ResponseEntity<List<AlertService.AlertRecord>> response = controller.getAlertHistory(
                "2026-04-01T00:00:00+08:00", "2026-04-02T00:00:00+08:00", "CRITICAL", null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals("CRITICAL", response.getBody().get(0).getSeverity());
    }

    @Test
    void getAlertHistory_noSeverity_returnsAll() {
        when(alertService.getAlertHistory(any(), any(), isNull(), isNull()))
                .thenReturn(List.of());

        ResponseEntity<List<AlertService.AlertRecord>> response = controller.getAlertHistory(
                "2026-04-01T00:00:00+08:00", "2026-04-02T00:00:00+08:00", null, null);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().isEmpty());
    }

    // ==================== getAlertStats ====================

    @Test
    void getAlertStats_returnsStats() {
        AlertService.AlertStats stats = new AlertService.AlertStats();
        stats.setTotalAlerts(10L);
        stats.setCriticalAlerts(2L);
        stats.setWarningAlerts(8L);

        when(alertService.getAlertStats(any(), any())).thenReturn(stats);

        ResponseEntity<AlertService.AlertStats> response = controller.getAlertStats(
                "2026-04-01T00:00:00+08:00", "2026-04-02T00:00:00+08:00");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(10L, response.getBody().getTotalAlerts());
        assertEquals(2L, response.getBody().getCriticalAlerts());
    }

    // ==================== resolveAlert ====================

    @Test
    void resolveAlert_returnsOk() {
        ResponseEntity<Map<String, String>> response = controller.resolveAlert(
                1L, Map.of("resolution", "已修复"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("告警已解决", response.getBody().get("message"));
        assertEquals("1", response.getBody().get("alertId"));
        verify(alertService).resolveAlert(1L, "已修复");
    }

    @Test
    void resolveAlert_noResolution_usesDefault() {
        ResponseEntity<Map<String, String>> response = controller.resolveAlert(5L, Map.of());

        assertEquals(200, response.getStatusCode().value());
        verify(alertService).resolveAlert(5L, "");
    }

    // ==================== silenceAlert ====================

    @Test
    void silenceAlert_returnsOk() {
        ResponseEntity<Map<String, String>> response = controller.silenceAlert(
                Map.of("alertKey", "latency-high", "durationMinutes", 30));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("告警已静默", response.getBody().get("message"));
        assertEquals("latency-high", response.getBody().get("alertKey"));
        verify(alertService).silenceAlert("latency-high", 30);
    }

    @Test
    void silenceAlert_defaultDuration() {
        ResponseEntity<Map<String, String>> response = controller.silenceAlert(
                Map.of("alertKey", "test-key"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("60", response.getBody().get("durationMinutes"));
        verify(alertService).silenceAlert("test-key", 60);
    }

    // ==================== fireAlert ====================

    @Test
    void fireAlert_returnsOk() {
        when(alertService.fireAlert(eq("latency"), eq("延迟告警"), eq("检索延迟超过阈值"),
                eq("WARNING"), any())).thenReturn(42L);

        ResponseEntity<Map<String, Object>> response = controller.fireAlert(Map.of(
                "alertType", "latency",
                "alertName", "延迟告警",
                "message", "检索延迟超过阈值",
                "severity", "WARNING",
                "metrics", Map.of("p99", 800)
        ));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(42L, response.getBody().get("alertId"));
        assertEquals("ACTIVE", response.getBody().get("status"));
    }

    @Test
    void fireAlert_defaultSeverity() {
        when(alertService.fireAlert(eq("test"), eq("测试"), eq("测试告警"),
                eq("WARNING"), any())).thenReturn(1L);

        ResponseEntity<Map<String, Object>> response = controller.fireAlert(Map.of(
                "alertType", "test",
                "alertName", "测试",
                "message", "测试告警"
        ));

        assertEquals(200, response.getStatusCode().value());
        verify(alertService).fireAlert("test", "测试", "测试告警", "WARNING", Map.of());
    }

    // ==================== checkAllSlos ====================

    @Test
    void checkAllSlos_returnsStatusMap() {
        AlertService.SloStatus availabilitySlo = new AlertService.SloStatus();
        availabilitySlo.setSloName("availability");
        availabilitySlo.setTarget(99.9);
        availabilitySlo.setActual(99.95);
        availabilitySlo.setMet(true);

        AlertService.SloStatus latencySlo = new AlertService.SloStatus();
        latencySlo.setSloName("latency_p99");
        latencySlo.setTarget(500.0);
        latencySlo.setActual(480.0);
        latencySlo.setMet(true);

        when(alertService.checkAllSlos(any(), any()))
                .thenReturn(Map.of("availability", availabilitySlo, "latency_p99", latencySlo));

        ResponseEntity<Map<String, AlertService.SloStatus>> response = controller.checkAllSlos(
                "2026-04-01T00:00:00+08:00", "2026-04-02T00:00:00+08:00");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().size());
        assertTrue(response.getBody().get("availability").isMet());
        assertTrue(response.getBody().get("latency_p99").isMet());
    }

    // ==================== checkSlo ====================

    @Test
    void checkSlo_returnsStatus() {
        AlertService.SloStatus slo = new AlertService.SloStatus();
        slo.setSloName("availability");
        slo.setTarget(99.9);
        slo.setActual(95.0);
        slo.setMet(false);

        when(alertService.checkSlo(eq("availability"), any(), any())).thenReturn(slo);

        ResponseEntity<AlertService.SloStatus> response = controller.checkSlo(
                "availability",
                "2026-04-01T00:00:00+08:00",
                "2026-04-02T00:00:00+08:00");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("availability", response.getBody().getSloName());
        assertFalse(response.getBody().isMet());
        assertEquals(99.9, response.getBody().getTarget());
        assertEquals(95.0, response.getBody().getActual());
    }

    @Test
    void checkSlo_sloMet() {
        AlertService.SloStatus slo = new AlertService.SloStatus();
        slo.setSloName("latency_p99");
        slo.setTarget(500.0);
        slo.setActual(300.0);
        slo.setMet(true);

        when(alertService.checkSlo(eq("latency_p99"), any(), any())).thenReturn(slo);

        ResponseEntity<AlertService.SloStatus> response = controller.checkSlo(
                "latency_p99",
                "2026-04-01T00:00:00+08:00",
                "2026-04-02T00:00:00+08:00");

        assertTrue(response.getBody().isMet());
    }
}
