package com.springairag.core.controller;

import com.springairag.api.dto.AlertNotificationDeliveryPageResponse;
import com.springairag.api.dto.AlertNotificationDeliveryResponse;
import com.springairag.core.alertdelivery.AlertNotificationDeliveryService;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.service.AlertManagementAuthorization;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AlertNotificationDeliveryController.class)
@Import({
        GlobalExceptionHandler.class,
        ApiVersionConfig.class,
        AlertManagementAuthorization.class,
        AlertNotificationDeliveryControllerWebTest.RagPropertiesTestConfig.class
})
class AlertNotificationDeliveryControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlertNotificationDeliveryService deliveryService;

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
    void rootListsOnlyLowSensitivityReceiptFields() throws Exception {
        UUID id = UUID.randomUUID();
        when(deliveryService.query("FAILED", "DINGTALK", 42L, 25, null))
                .thenReturn(new AlertNotificationDeliveryPageResponse(
                        true,
                        true,
                        List.of("DINGTALK"),
                        List.of(receipt(id, "FAILED")),
                        25,
                        false,
                        null));

        mockMvc.perform(get(
                        "/api/v1/rag/alerts/notification-deliveries")
                        .param("status", "FAILED")
                        .param("provider", "DINGTALK")
                        .param("alertId", "42")
                        .param("limit", "25")
                        .requestAttr(
                                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                                ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durableDeliveryEnabled").value(true))
                .andExpect(jsonPath("$.configuredProviders[0]")
                        .value("DINGTALK"))
                .andExpect(jsonPath("$.items[0].id")
                        .value(id.toString()))
                .andExpect(jsonPath("$.items[0].lastErrorCode")
                        .value("PERMANENT_PROVIDER_REJECTED"))
                .andExpect(jsonPath("$.items[0].payload").doesNotExist())
                .andExpect(jsonPath("$.items[0].leaseToken").doesNotExist())
                .andExpect(jsonPath("$.items[0].recipient").doesNotExist());
    }

    @Test
    void normalPrincipalCannotReadOrRetry() throws Exception {
        UUID id = UUID.randomUUID();
        AuthenticatedApiPrincipal principal = new AuthenticatedApiPrincipal(
                "principal-1",
                "credential-1",
                1,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                ApiKeyRole.NORMAL,
                null,
                null,
                1,
                null);

        mockMvc.perform(get(
                        "/api/v1/rag/alerts/notification-deliveries")
                        .requestAttr(
                                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY)
                        .requestAttr(
                                ApiKeyAuthFilter
                                        .AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                                principal))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(
                        "/api/v1/rag/alerts/notification-deliveries/"
                                + id + "/retry")
                        .requestAttr(
                                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY)
                        .requestAttr(
                                ApiKeyAuthFilter
                                        .AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                                principal))
                .andExpect(status().isForbidden());

        verify(deliveryService, never()).query(
                any(), any(), any(), any(Integer.class), any());
        verify(deliveryService, never()).retry(any());
    }

    @Test
    void rootRetriesAndWritesLowSensitivityAudit() throws Exception {
        UUID id = UUID.randomUUID();
        when(deliveryService.retry(id))
                .thenReturn(receipt(id, "PENDING"));

        mockMvc.perform(post(
                        "/api/v1/rag/alerts/notification-deliveries/"
                                + id + "/retry")
                        .requestAttr(
                                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                                ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.manualRetryCount").value(1));

        verify(auditLogService).logUpdate(
                org.mockito.ArgumentMatchers.eq(
                        "AlertNotificationDelivery"),
                org.mockito.ArgumentMatchers.eq(id.toString()),
                any(),
                org.mockito.ArgumentMatchers.argThat(details ->
                        !details.containsKey("payload")
                                && !details.containsKey("endpoint")));
    }

    private AlertNotificationDeliveryResponse receipt(
            UUID id, String status) {
        OffsetDateTime now = OffsetDateTime.parse(
                "2026-08-28T08:00:00+08:00");
        return new AlertNotificationDeliveryResponse(
                id,
                42L,
                1,
                "DINGTALK",
                status,
                1,
                9,
                1,
                now,
                "PERMANENT_PROVIDER_REJECTED",
                400,
                now,
                null,
                now,
                now);
    }
}
