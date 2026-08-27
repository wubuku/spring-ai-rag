package com.springairag.core.controller;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.repository.RagSilenceScheduleRepository;
import com.springairag.core.repository.SloConfigRepository;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.service.AlertManagementAuthorization;
import com.springairag.core.service.AlertService;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AlertController.class)
@Import({
        GlobalExceptionHandler.class,
        ApiVersionConfig.class,
        AlertManagementAuthorization.class,
        AlertControllerWebTest.RagPropertiesTestConfig.class
})
class AlertControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlertService alertService;

    @MockitoBean
    private SloConfigRepository sloConfigRepository;

    @MockitoBean
    private RagSilenceScheduleRepository silenceScheduleRepository;

    @MockitoBean
    private AuditLogService auditLogService;

    @TestConfiguration
    static class RagPropertiesTestConfig {
        @Bean
        com.springairag.core.config.RagProperties ragProperties() {
            return new com.springairag.core.config.RagProperties();
        }
    }

    @Test
    void rootReadsAdditiveFiredAtAndConditionStateContract()
            throws Exception {
        AlertService.AlertRecord record = new AlertService.AlertRecord();
        record.setId(7L);
        record.setAlertType("API_PRINCIPAL_EXPIRY");
        record.setAlertName("Managed API principal expiry");
        record.setMessage("fixture");
        record.setSeverity("WARNING");
        record.setStatus("ACTIVE");
        record.setConditionState("WARNING");
        record.setFiredAt(ZonedDateTime.parse(
                "2026-08-27T12:00:00+08:00[Asia/Shanghai]"));
        record.setMetrics(Map.of(
                "principalId", "principal-1",
                "expiresAt", "2026-09-01T00:00:00+08:00[Asia/Shanghai]"));
        when(alertService.getActiveAlerts()).thenReturn(List.of(record));

        mockMvc.perform(get("/api/v1/rag/alerts/active")
                        .requestAttr(
                                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                                ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].firedAt").exists())
                .andExpect(jsonPath("$[0].triggeredAt").doesNotExist())
                .andExpect(jsonPath("$[0].conditionState")
                        .value("WARNING"))
                .andExpect(jsonPath("$[0].metrics.principalId")
                        .value("principal-1"))
                .andExpect(jsonPath("$[0].dedupeKey").doesNotExist());
    }

    @Test
    void normalPrincipalIsRejectedBeforeAlertServiceAccess()
            throws Exception {
        mockMvc.perform(get("/api/v1/rag/alerts/active")
                        .requestAttr(
                                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY)
                        .requestAttr(
                                ApiKeyAuthFilter
                                        .AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                                principal(ApiKeyRole.NORMAL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.detail")
                        .value("Alert management requires operator access"));

        verify(alertService, never()).getActiveAlerts();
    }

    @Test
    void databaseAdminAndAuthDisabledLoopbackRemainCompatible()
            throws Exception {
        when(alertService.getActiveAlerts()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/rag/alerts/active")
                        .requestAttr(
                                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY)
                        .requestAttr(
                                ApiKeyAuthFilter
                                        .AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                                principal(ApiKeyRole.ADMIN)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/rag/alerts/active")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            request.setAttribute(
                                    ApiKeyAuthFilter
                                            .AUTHENTICATION_REQUIRED_ATTRIBUTE,
                                    false);
                            return request;
                        }))
                .andExpect(status().isOk());

        verify(alertService, org.mockito.Mockito.times(2))
                .getActiveAlerts();
    }

    private AuthenticatedApiPrincipal principal(ApiKeyRole role) {
        return new AuthenticatedApiPrincipal(
                "principal-1",
                "credential-1",
                1,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                role,
                null,
                null,
                1,
                null);
    }
}
