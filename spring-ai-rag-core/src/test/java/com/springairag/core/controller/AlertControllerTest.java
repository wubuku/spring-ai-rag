package com.springairag.core.controller;

import com.springairag.api.dto.AlertActionResponse;
import com.springairag.api.dto.FireAlertRequest;
import com.springairag.api.dto.FireAlertResponse;
import com.springairag.api.dto.ResolveAlertRequest;
import com.springairag.api.dto.SilenceAlertRequest;
import com.springairag.api.dto.SilenceScheduleRequest;
import com.springairag.api.dto.SloConfigRequest;
import com.springairag.core.entity.RagSilenceSchedule;
import com.springairag.core.entity.RagSloConfig;
import com.springairag.core.repository.RagSilenceScheduleRepository;
import com.springairag.core.repository.SloConfigRepository;
import com.springairag.core.service.AlertService;
import com.springairag.core.service.AuditLogService;
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
    private AuditLogService auditLogService;
    private AlertController controller;

    @BeforeEach
    void setUp() {
        alertService = mock(AlertService.class);
        sloConfigRepository = mock(SloConfigRepository.class);
        silenceScheduleRepository = mock(RagSilenceScheduleRepository.class);
        auditLogService = mock(AuditLogService.class);
        controller = new AlertController(alertService, sloConfigRepository, silenceScheduleRepository, auditLogService);
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

    // ==================== SLO Config CRUD ====================

    @Test
    void createSloConfig_success() {
        SloConfigRequest request = new SloConfigRequest();
        request.setSloName("latency_p99");
        request.setSloType("LATENCY");
        request.setTargetValue(200.0);
        request.setUnit("ms");
        request.setDescription("P99 latency SLO");

        RagSloConfig saved = new RagSloConfig();
        saved.setSloName("latency_p99");
        saved.setSloType("LATENCY");
        saved.setTargetValue(200.0);
        saved.setUnit("ms");

        when(sloConfigRepository.findBySloName("latency_p99")).thenReturn(java.util.Optional.empty());
        when(sloConfigRepository.save(any(RagSloConfig.class))).thenReturn(saved);

        ResponseEntity<RagSloConfig> response = controller.createSloConfig(request);

        assertEquals(201, response.getStatusCode().value());
        assertEquals("latency_p99", response.getBody().getSloName());
        assertEquals("LATENCY", response.getBody().getSloType());
    }

    @Test
    void createSloConfig_duplicate_returns409() {
        SloConfigRequest request = new SloConfigRequest();
        request.setSloName("existing_slo");
        request.setSloType("AVAILABILITY");
        request.setTargetValue(99.9);
        request.setUnit("%");

        RagSloConfig existing = new RagSloConfig();
        existing.setSloName("existing_slo");
        when(sloConfigRepository.findBySloName("existing_slo")).thenReturn(java.util.Optional.of(existing));

        ResponseEntity<RagSloConfig> response = controller.createSloConfig(request);

        assertEquals(409, response.getStatusCode().value());
        verify(sloConfigRepository, never()).save(any());
    }

    @Test
    void listSloConfigs_returnsList() {
        RagSloConfig c1 = new RagSloConfig();
        c1.setSloName("slo1");
        RagSloConfig c2 = new RagSloConfig();
        c2.setSloName("slo2");

        when(sloConfigRepository.findAll()).thenReturn(List.of(c1, c2));

        ResponseEntity<List<RagSloConfig>> response = controller.listSloConfigs();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().size());
    }

    @Test
    void getSloConfig_found() {
        RagSloConfig config = new RagSloConfig();
        config.setSloName("latency");
        config.setTargetValue(500.0);
        when(sloConfigRepository.findBySloName("latency")).thenReturn(java.util.Optional.of(config));

        ResponseEntity<RagSloConfig> response = controller.getSloConfig("latency");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("latency", response.getBody().getSloName());
    }

    @Test
    void getSloConfig_notFound_returns404() {
        when(sloConfigRepository.findBySloName("nonexistent")).thenReturn(java.util.Optional.empty());

        ResponseEntity<RagSloConfig> response = controller.getSloConfig("nonexistent");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void deleteSloConfig_existing_returns204() {
        RagSloConfig config = new RagSloConfig();
        config.setSloName("to_delete");
        when(sloConfigRepository.findBySloName("to_delete")).thenReturn(java.util.Optional.of(config));
        doNothing().when(sloConfigRepository).deleteBySloName("to_delete");

        ResponseEntity<Void> response = controller.deleteSloConfig("to_delete");

        assertEquals(204, response.getStatusCode().value());
        verify(sloConfigRepository).deleteBySloName("to_delete");
    }

    @Test
    void deleteSloConfig_notFound_returns404() {
        when(sloConfigRepository.findBySloName("nonexistent")).thenReturn(java.util.Optional.empty());

        ResponseEntity<Void> response = controller.deleteSloConfig("nonexistent");

        assertEquals(404, response.getStatusCode().value());
        verify(sloConfigRepository, never()).deleteBySloName(any());
    }

    // ==================== Silence Schedule CRUD ====================

    @Test
    void createSilenceSchedule_success() {
        SilenceScheduleRequest request = new SilenceScheduleRequest();
        request.setName("weekend_maint");
        request.setAlertKey("high-latency");
        request.setSilenceType("ONE_TIME");
        request.setStartTime("2026-04-10T02:00:00+08:00");
        request.setEndTime("2026-04-10T04:00:00+08:00");
        request.setDescription("Weekend maintenance");

        RagSilenceSchedule saved = new RagSilenceSchedule();
        saved.setName("weekend_maint");
        saved.setAlertKey("high-latency");
        saved.setSilenceType("ONE_TIME");

        when(silenceScheduleRepository.findByName("weekend_maint")).thenReturn(java.util.Optional.empty());
        when(silenceScheduleRepository.save(any(RagSilenceSchedule.class))).thenReturn(saved);

        ResponseEntity<RagSilenceSchedule> response = controller.createSilenceSchedule(request);

        assertEquals(201, response.getStatusCode().value());
        assertEquals("weekend_maint", response.getBody().getName());
    }

    @Test
    void createSilenceSchedule_duplicate_returns409() {
        SilenceScheduleRequest request = new SilenceScheduleRequest();
        request.setName("existing_schedule");
        request.setSilenceType("RECURRING");
        request.setStartTime("2026-04-10T02:00:00+08:00");
        request.setEndTime("2026-04-10T04:00:00+08:00");

        RagSilenceSchedule existing = new RagSilenceSchedule();
        existing.setName("existing_schedule");
        when(silenceScheduleRepository.findByName("existing_schedule")).thenReturn(java.util.Optional.of(existing));

        ResponseEntity<RagSilenceSchedule> response = controller.createSilenceSchedule(request);

        assertEquals(409, response.getStatusCode().value());
        verify(silenceScheduleRepository, never()).save(any());
    }

    @Test
    void listSilenceSchedules_returnsList() {
        RagSilenceSchedule s1 = new RagSilenceSchedule();
        s1.setName("schedule1");
        RagSilenceSchedule s2 = new RagSilenceSchedule();
        s2.setName("schedule2");

        when(silenceScheduleRepository.findAll()).thenReturn(List.of(s1, s2));

        ResponseEntity<List<RagSilenceSchedule>> response = controller.listSilenceSchedules();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().size());
    }

    @Test
    void getSilenceSchedule_found() {
        RagSilenceSchedule schedule = new RagSilenceSchedule();
        schedule.setName("maintenance");
        schedule.setSilenceType("ONE_TIME");
        when(silenceScheduleRepository.findByName("maintenance")).thenReturn(java.util.Optional.of(schedule));

        ResponseEntity<RagSilenceSchedule> response = controller.getSilenceSchedule("maintenance");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("maintenance", response.getBody().getName());
    }

    @Test
    void getSilenceSchedule_notFound_returns404() {
        when(silenceScheduleRepository.findByName("nonexistent")).thenReturn(java.util.Optional.empty());

        ResponseEntity<RagSilenceSchedule> response = controller.getSilenceSchedule("nonexistent");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void deleteSilenceSchedule_existing_returns204() {
        RagSilenceSchedule schedule = new RagSilenceSchedule();
        schedule.setName("to_delete");
        when(silenceScheduleRepository.findByName("to_delete")).thenReturn(java.util.Optional.of(schedule));
        doNothing().when(silenceScheduleRepository).deleteByName("to_delete");

        ResponseEntity<Void> response = controller.deleteSilenceSchedule("to_delete");

        assertEquals(204, response.getStatusCode().value());
        verify(silenceScheduleRepository).deleteByName("to_delete");
    }

    @Test
    void deleteSilenceSchedule_notFound_returns404() {
        when(silenceScheduleRepository.findByName("nonexistent")).thenReturn(java.util.Optional.empty());

        ResponseEntity<Void> response = controller.deleteSilenceSchedule("nonexistent");

        assertEquals(404, response.getStatusCode().value());
        verify(silenceScheduleRepository, never()).deleteByName(any());
    }
}
