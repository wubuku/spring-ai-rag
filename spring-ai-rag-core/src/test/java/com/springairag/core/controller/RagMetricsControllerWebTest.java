package com.springairag.core.controller;

import com.springairag.api.dto.LlmUsageResponse;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.ModelRegistry;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.metrics.ApiSloTrackerService;
import com.springairag.core.metrics.ModelMetricsService;
import com.springairag.core.metrics.RagMetricsService;
import com.springairag.core.metrics.SlowQueryMetricsService;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.usage.LlmUsageQueryService;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RagMetricsController.class)
@Import({
        ApiVersionConfig.class,
        GlobalExceptionHandler.class,
        RagMetricsControllerWebTest.RagPropertiesTestConfig.class
})
@TestPropertySource(properties = {
        "rag.cors.enabled=false",
        "rag.slo.enabled=false"
})
class RagMetricsControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RagMetricsService metricsService;

    @MockitoBean
    private ModelMetricsService modelMetricsService;

    @MockitoBean
    private ModelRegistry modelRegistry;

    @MockitoBean
    private ChatModelRouter modelRouter;

    @MockitoBean
    private SlowQueryMetricsService slowQueryMetricsService;

    @MockitoBean
    private ApiSloTrackerService sloTrackerService;

    @MockitoBean
    private LlmUsageQueryService usageQueryService;

    @TestConfiguration
    static class RagPropertiesTestConfig {

        @Bean
        RagProperties ragProperties() {
            return new RagProperties();
        }
    }

    @Test
    void selfScopeReturnsTypedUsageJsonWithNumericCost() throws Exception {
        when(usageQueryService.query(
                any(ChatPrincipal.class),
                eq("2026-08-01"),
                eq("2026-08-02"),
                isNull()))
                .thenReturn(response("SELF", "db:caller"));

        mockMvc.perform(get("/api/v1/rag/usage")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-02")
                        .accept(MediaType.APPLICATION_JSON)
                        .with(databasePrincipal("caller", ApiKeyRole.NORMAL)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.recordingEnabled").value(true))
                .andExpect(jsonPath("$.scope.type").value("SELF"))
                .andExpect(jsonPath("$.scope.principalId").value("db:caller"))
                .andExpect(jsonPath("$.from").value("2026-08-01"))
                .andExpect(jsonPath("$.to").value("2026-08-02"))
                .andExpect(jsonPath("$.totals.promptTokens").value(10))
                .andExpect(jsonPath("$.totals.totalTokens").value(30))
                .andExpect(jsonPath("$.costs[0].unit").value("USD_ESTIMATE"))
                .andExpect(jsonPath("$.costs[0].configuredCost").isNumber())
                .andExpect(content().string(containsString("\"configuredCost\":0.12500000")));

        verify(usageQueryService).query(
                any(ChatPrincipal.class),
                eq("2026-08-01"),
                eq("2026-08-02"),
                isNull());
    }

    @Test
    void adminCanRequestGlobalAndSpecifiedPrincipalScopes() throws Exception {
        when(usageQueryService.query(
                any(ChatPrincipal.class), isNull(), isNull(), isNull()))
                .thenReturn(response("ALL", null));
        when(usageQueryService.query(
                any(ChatPrincipal.class), isNull(), isNull(), eq("db:target")))
                .thenReturn(response("PRINCIPAL", "db:target"));

        mockMvc.perform(get("/api/v1/rag/usage")
                        .with(databasePrincipal("admin", ApiKeyRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.type").value("ALL"))
                .andExpect(jsonPath("$.scope.principalId").doesNotExist());

        mockMvc.perform(get("/api/v1/rag/usage")
                        .param("principalId", "db:target")
                        .with(databasePrincipal("admin", ApiKeyRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.type").value("PRINCIPAL"))
                .andExpect(jsonPath("$.scope.principalId").value("db:target"));
    }

    @Test
    void invalidDateIsMappedToBadRequest() throws Exception {
        when(usageQueryService.query(
                any(ChatPrincipal.class),
                eq("2026-08-01"),
                eq("not-a-date"),
                isNull()))
                .thenThrow(new IllegalArgumentException("to must use YYYY-MM-DD format"));

        mockMvc.perform(get("/api/v1/rag/usage")
                        .param("from", "2026-08-01")
                        .param("to", "not-a-date")
                        .with(databasePrincipal("caller", ApiKeyRole.NORMAL)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type",
                        containsString("application/problem+json")))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void overlongDateRangeIsMappedToBadRequest() throws Exception {
        when(usageQueryService.query(
                any(ChatPrincipal.class),
                eq("2024-12-31"),
                eq("2026-01-01"),
                isNull()))
                .thenThrow(new IllegalArgumentException(
                        "usage date range must not exceed 366 UTC days"));

        mockMvc.perform(get("/api/v1/rag/usage")
                        .param("from", "2024-12-31")
                        .param("to", "2026-01-01")
                        .with(databasePrincipal("caller", ApiKeyRole.NORMAL)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("usage date range must not exceed 366 UTC days"));
    }

    @Test
    void forbiddenScopeIsMappedToForbidden() throws Exception {
        when(usageQueryService.query(
                any(ChatPrincipal.class), isNull(), isNull(), eq("db:other")))
                .thenThrow(new SecurityException(
                        "Only root or ADMIN principals can query another principal"));

        mockMvc.perform(get("/api/v1/rag/usage")
                        .param("principalId", "db:other")
                        .with(databasePrincipal("caller", ApiKeyRole.NORMAL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    private static RequestPostProcessor databasePrincipal(
            String keyId,
            ApiKeyRole role) {
        return request -> {
            request.setAttribute(
                    ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                    ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, keyId);
            request.setAttribute(
                    ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                    new AuthenticatedApiPrincipal(
                            keyId,
                            "credential-" + keyId,
                            1,
                            ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                            role,
                            null,
                            null,
                            1L,
                            60));
            return request;
        };
    }

    private static LlmUsageResponse response(
            String scopeType,
            String principalId) {
        LlmUsageResponse.Totals totals = new LlmUsageResponse.Totals(
                1,
                2,
                1,
                1,
                0,
                new BigDecimal("10"),
                new BigDecimal("20"),
                new BigDecimal("30"),
                2,
                0,
                0,
                0);
        return new LlmUsageResponse(
                true,
                0,
                new LlmUsageResponse.Scope(scopeType, principalId),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 2),
                totals,
                List.of(new LlmUsageResponse.CostBreakdown(
                        "USD_ESTIMATE",
                        new BigDecimal("0.12500000"),
                        2,
                        2)),
                List.of(new LlmUsageResponse.ModelBreakdown("provider/model", totals)),
                List.of(new LlmUsageResponse.PurposeBreakdown("CHAT", totals)),
                List.of(new LlmUsageResponse.ModeBreakdown("KNOWLEDGE", totals)),
                List.of(new LlmUsageResponse.DayBreakdown(
                        LocalDate.of(2026, 8, 1), totals)));
    }
}
