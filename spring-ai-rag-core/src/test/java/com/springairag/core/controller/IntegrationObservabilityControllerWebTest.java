package com.springairag.core.controller;

import com.springairag.api.dto.IntegrationObservabilityResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.api.enums.IntegrationObservabilityBucket;
import com.springairag.core.exception.RagException;
import com.springairag.core.observability.IntegrationObservabilityQueryService;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        value = IntegrationObservabilityController.class,
        properties = {
                "rag.cors.enabled=false",
                "rag.slo.enabled=false"
        })
@Import({GlobalExceptionHandler.class, ApiVersionConfig.class})
class IntegrationObservabilityControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IntegrationObservabilityQueryService queryService;

    @Test
    void returnsBoundedJsonAndDisablesCaching() throws Exception {
        when(queryService.query(
                any(),
                eq("2026-08-26T00:00:00Z"),
                eq("2026-08-27T00:00:00Z"),
                eq("DAY"),
                eq("JSON_RECORD_SEARCH"),
                eq("manual"),
                eq("rag_p_1")))
                .thenReturn(response());

        mockMvc.perform(get("/api/v1/rag/integration-observability")
                        .param("from", "2026-08-26T00:00:00Z")
                        .param("to", "2026-08-27T00:00:00Z")
                        .param("bucket", "DAY")
                        .param("operation", "JSON_RECORD_SEARCH")
                        .param("collectionKey", "manual")
                        .param("principalId", "rag_p_1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.scope.bucket").value("DAY"))
                .andExpect(jsonPath("$.scope.principalId").value("rag_p_1"))
                .andExpect(jsonPath("$.scope.collectionKey").value("manual"))
                .andExpect(jsonPath("$.scope.operation")
                        .value("JSON_RECORD_SEARCH"))
                .andExpect(jsonPath("$.completeness.mode").value("BEST_EFFORT"))
                .andExpect(jsonPath("$.totals.requestCount").value(2))
                .andExpect(jsonPath("$.totals.estimated").value(true))
                .andExpect(jsonPath("$.byStatus[0].httpStatus").value(200))
                .andExpect(jsonPath("$.byOperation[0].operation")
                        .value("JSON_RECORD_SEARCH"))
                .andExpect(jsonPath("$.collectionContributions[0].collectionKey")
                        .value("manual"))
                .andExpect(jsonPath("$.timeline[0].bucketStart")
                        .value("2026-08-26"));
    }

    @Test
    void mapsBadRequestToRfc7807Problem() throws Exception {
        when(queryService.query(
                any(), eq("invalid"), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenThrow(new IllegalArgumentException(
                        "from must use an ISO-8601 instant"));

        mockMvc.perform(get("/api/v1/rag/integration-observability")
                        .param("from", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type")
                        .value("https://springairag.dev/problems/bad-request"))
                .andExpect(jsonPath("$.title").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail")
                        .value("from must use an ISO-8601 instant"))
                .andExpect(jsonPath("$.instance")
                        .value("/api/v1/rag/integration-observability"));
    }

    @Test
    void mapsForbiddenToRfc7807Problem() throws Exception {
        when(queryService.query(
                any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenThrow(new RagException(
                        ErrorCode.FORBIDDEN,
                        "The current principal is not allowed to query this scope"));

        mockMvc.perform(get("/api/v1/rag/integration-observability"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.type")
                        .value("https://springairag.dev/problems/forbidden"));
    }

    @Test
    void mapsDisabledAndUnresolvedScopeToServiceUnavailableProblems()
            throws Exception {
        when(queryService.query(
                any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenThrow(new RagException(
                        ErrorCode.INTEGRATION_OBSERVABILITY_DISABLED,
                        "Integration observability is disabled"));

        mockMvc.perform(get("/api/v1/rag/integration-observability"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.error")
                        .value("INTEGRATION_OBSERVABILITY_DISABLED"))
                .andExpect(jsonPath("$.status").value(503));

        reset(queryService);
        when(queryService.query(
                any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenThrow(new RagException(
                        ErrorCode.SERVICE_UNAVAILABLE,
                        "The integration observability scope cannot be resolved completely"));

        mockMvc.perform(get("/api/v1/rag/integration-observability"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503));
    }

    private static IntegrationObservabilityResponse response() {
        IntegrationObservabilityResponse.Totals totals =
                new IntegrationObservabilityResponse.Totals(
                        2,
                        new BigDecimal("37.50"),
                        50,
                        50,
                        50,
                        true);
        return new IntegrationObservabilityResponse(
                new IntegrationObservabilityResponse.Scope(
                        Instant.parse("2026-08-26T00:00:00Z"),
                        Instant.parse("2026-08-27T00:00:00Z"),
                        IntegrationObservabilityBucket.DAY,
                        "rag_p_1",
                        "manual",
                        "JSON_RECORD_SEARCH"),
                new IntegrationObservabilityResponse.Completeness(
                        "BEST_EFFORT",
                        true,
                        90,
                        0,
                        Instant.parse("2026-08-26T00:00:00Z")),
                totals,
                List.of(new IntegrationObservabilityResponse.StatusBreakdown(
                        200, "SUCCESS", totals)),
                List.of(new IntegrationObservabilityResponse.OperationBreakdown(
                        "JSON_RECORD_SEARCH", totals)),
                List.of(new IntegrationObservabilityResponse.CollectionContribution(
                        "manual", totals)),
                List.of(new IntegrationObservabilityResponse.TimelineBucket(
                        "2026-08-26", totals)));
    }
}
