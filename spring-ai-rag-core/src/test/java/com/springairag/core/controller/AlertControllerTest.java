package com.springairag.core.controller;

import com.springairag.api.dto.AlertActionResponse;
import com.springairag.api.dto.FireAlertRequest;
import com.springairag.api.dto.FireAlertResponse;
import com.springairag.api.dto.ResolveAlertRequest;
import com.springairag.api.dto.SilenceAlertRequest;
import com.springairag.core.repository.RagSilenceScheduleRepository;
import com.springairag.core.repository.SloConfigRepository;
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
    private SloConfigRepository sloConfigRepository;
    private RagSilenceScheduleRepository silenceScheduleRepository;
    private AlertController controller;

    @BeforeEach
    void setUp() {
        alertService = mock(AlertService.class);
        sloConfigRepository = mock(SloConfigRepository.class);
        silenceScheduleRepository = mock(RagSilenceScheduleRepository.class);
        controller = new AlertController(alertService, sloConfigRepository, silenceScheduleRepository);
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
        ResponseEntity<AlertActionResponse> response = controller.resolveAlert(
                1L, new ResolveAlertRequest("已修复"));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().success());
        assertEquals("Alert resolved", response.getBody().message());
        verify(alertService).resolveAlert(1L, "已修复");
    }

    @Test
    void resolveAlert_noResolution_usesDefault() {
        ResponseEntity<AlertActionResponse> response = controller.resolveAlert(5L, new ResolveAlertRequest(null));

        assertEquals(200, response.getStatusCode().value());
        verify(alertService).resolveAlert(5L, "");
    }

    // ==================== silenceAlert ====================

    @Test
    void silenceAlert_returnsOk() {
        ResponseEntity<AlertActionResponse> response = controller.silenceAlert(
                new SilenceAlertRequest("latency-high", 30));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().success());
        assertTrue(response.getBody().message().contains("latency-high"));
        verify(alertService).silenceAlert("latency-high", 30);
    }

    @Test
    void silenceAlert_defaultDuration() {
        ResponseEntity<AlertActionResponse> response = controller.silenceAlert(
                new SilenceAlertRequest("test-key", null));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().success());
        verify(alertService).silenceAlert("test-key", 60);
    }

    // ==================== fireAlert ====================

    @Test
    void fireAlert_returnsOk() {
        when(alertService.fireAlert(eq("latency"), eq("延迟告警"), eq("检索延迟超过阈值"),
                eq("WARNING"), any())).thenReturn(42L);

        ResponseEntity<FireAlertResponse> response = controller.fireAlert(
                new FireAlertRequest("latency", "延迟告警", "检索延迟超过阈值",
                        "WARNING", Map.of("p99", 800)));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(42L, response.getBody().alertId());
    }

    @Test
    void fireAlert_defaultSeverity() {
        when(alertService.fireAlert(eq("test"), eq("测试"), eq("测试告警"),
                eq("WARNING"), any())).thenReturn(1L);

        ResponseEntity<FireAlertResponse> response = controller.fireAlert(
                new FireAlertRequest("test", "测试", "测试告警", null, null));

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
