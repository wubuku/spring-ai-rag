package com.springairag.core.controller;

import com.springairag.api.dto.AlertActionResponse;
import com.springairag.core.service.AlertService;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.repository.RagSilenceScheduleRepository;
import com.springairag.core.repository.SloConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlertController 静默查询与解除长尾（Batch 643，JaCoCo 驱动）：
 * getSilencedAlerts 的映射投影、unsilenceAlert 对已静默 / 未静默
 * 两臂的响应消息与审计消息联动。
 */
class AlertControllerSilenceAuditTailTest {

    private AlertService alertService;
    private AuditLogService auditLogService;
    private AlertController controller;

    @BeforeEach
    void setUp() {
        alertService = mock(AlertService.class);
        controller = new AlertController(
                alertService,
                mock(SloConfigRepository.class),
                mock(RagSilenceScheduleRepository.class),
                auditLogService = mock(AuditLogService.class));
    }

    @Test
    void getSilencedAlertsReturnsExpirationMap() {
        ZonedDateTime expiration = ZonedDateTime.now().plusHours(1);
        when(alertService.getSilencedAlerts())
                .thenReturn(Map.of("latency:p95", expiration));

        ResponseEntity<Map<String, ZonedDateTime>> response =
                controller.getSilencedAlerts();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(expiration, response.getBody().get("latency:p95"));
    }

    @Test
    void unsilencePreviouslySilencedAlertAuditsAndRespondsOk() {
        when(alertService.unsilenceAlert("latency:p95")).thenReturn(true);

        ResponseEntity<AlertActionResponse> response =
                controller.unsilenceAlert("latency:p95");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Alert unsilenced: latency:p95",
                response.getBody().message());
        verify(auditLogService).logUpdate(
                AuditLogService.ENTITY_ALERT, "latency:p95",
                "Alert unsilenced: latency:p95");
    }

    @Test
    void unsilenceNonSilencedAlertReportsNotSilenced() {
        when(alertService.unsilenceAlert("latency:p95")).thenReturn(false);

        ResponseEntity<AlertActionResponse> response =
                controller.unsilenceAlert("latency:p95");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Alert was not silenced: latency:p95",
                response.getBody().message());
        verify(auditLogService).logUpdate(
                AuditLogService.ENTITY_ALERT, "latency:p95",
                "Alert was not silenced: latency:p95");
    }
}
