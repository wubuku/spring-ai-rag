package com.springairag.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.LongRunningOperationResponse;
import com.springairag.core.service.EmbeddingProfileOperationService;
import com.springairag.core.service.LongRunningOperationService;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({LongRunningOperationController.class, EmbeddingProfileOperationController.class})
@Import({GlobalExceptionHandler.class, ApiVersionConfig.class,
        LongRunningOperationControllerWebTest.TestConfig.class})
class LongRunningOperationControllerWebTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        com.springairag.core.config.RagProperties ragProperties() {
            return new com.springairag.core.config.RagProperties();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LongRunningOperationService operationService;

    @MockitoBean
    private EmbeddingProfileOperationService profileOperationService;

    @Test
    void operationReceiptAndResultAreAdditiveDirectJsonResponses() throws Exception {
        UUID operationId = UUID.randomUUID();
        when(operationService.get(eq(operationId), any()))
                .thenReturn(receipt(operationId, "RUNNING", null));
        when(operationService.getResult(eq(operationId), any()))
                .thenReturn(objectMapper.readTree("{\"status\":\"SUCCEEDED\"}"));

        mockMvc.perform(get("/api/v1/rag/operations/{id}", operationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(operationId.toString()))
                .andExpect(jsonPath("$.operationKind").value("EMBEDDING_PROFILE_SWITCH"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.progress.phase").value("SCAN"))
                .andExpect(jsonPath("$.statusUrl")
                        .value("/api/v1/rag/operations/" + operationId))
                .andExpect(jsonPath("$.resultUrl")
                        .value("/api/v1/rag/operations/" + operationId + "/result"));

        mockMvc.perform(get("/api/v1/rag/operations/{id}/result", operationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void cancelAndRetryReturnAcceptedReceiptWithoutChangingLegacyRunRoutes()
            throws Exception {
        UUID operationId = UUID.randomUUID();
        when(operationService.cancel(eq(operationId), any()))
                .thenReturn(receipt(operationId, "RUNNING", true));
        when(operationService.retry(eq(operationId), any()))
                .thenReturn(receipt(operationId, "QUEUED", null));

        mockMvc.perform(post("/api/v1/rag/operations/{id}/cancel", operationId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.cancelRequested").value(true));
        mockMvc.perform(post("/api/v1/rag/operations/{id}/retry", operationId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        verify(operationService).cancel(eq(operationId), any());
        verify(operationService).retry(eq(operationId), any());
    }

    @Test
    void profileSwitchRequiresCallerSuppliedIdempotencyKeyAndReturnsLocation()
            throws Exception {
        UUID operationId = UUID.randomUUID();
        when(profileOperationService.create(any(), any(), any()))
                .thenReturn(receipt(operationId, "QUEUED", null));

        mockMvc.perform(post("/api/v1/rag/collections/by-key/embedding-profile/operations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "profile-switch-1")
                        .content("""
                                {
                                  "collectionKey": "knowledge-a",
                                  "targetProfileKey": "embedding-768-v1",
                                  "expectedCollectionVersion": 4
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location",
                        "/api/v1/rag/operations/" + operationId))
                .andExpect(jsonPath("$.operationId").value(operationId.toString()));

        verify(profileOperationService).create(any(), eq(List.of("profile-switch-1")), any());
    }

    private static LongRunningOperationResponse receipt(
            UUID operationId, String status, Boolean cancelRequested) {
        OffsetDateTime now = OffsetDateTime.of(
                2026, 10, 20, 4, 0, 0, 0, ZoneOffset.UTC);
        return new LongRunningOperationResponse(
                operationId, "EMBEDDING_PROFILE_SWITCH", "COLLECTION",
                11L, "knowledge-a", status, Map.of("phase", "SCAN"),
                now, now, "/api/v1/rag/operations/" + operationId,
                "/api/v1/rag/operations/" + operationId + "/result",
                "embedding-768-v1", null, null, cancelRequested, null);
    }
}
