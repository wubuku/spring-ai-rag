package com.springairag.core.controller;

import com.springairag.api.dto.CollectionEmbeddingReadinessResponse;
import com.springairag.api.dto.EmbeddingJobBatchResponse;
import com.springairag.api.dto.EmbeddingJobResponse;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingJobService;
import com.springairag.core.embeddingjob.EmbeddingJobStatus;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({
        EmbeddingJobController.class,
        CollectionEmbeddingReadinessController.class
})
@Import({
        GlobalExceptionHandler.class,
        ApiVersionConfig.class,
        EmbeddingJobControllerWebTest.RagPropertiesTestConfig.class
})
class EmbeddingJobControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmbeddingJobService service;

    @TestConfiguration
    static class RagPropertiesTestConfig {
        @Bean
        RagProperties ragProperties() {
            return new RagProperties();
        }
    }

    @Test
    void createReturnsAcceptedBatchEnvelope() throws Exception {
        UUID batchId = UUID.randomUUID();
        EmbeddingJobResponse job = response(
                UUID.randomUUID(), batchId, "QUEUED", false);
        when(service.create(any())).thenReturn(
                new EmbeddingJobBatchResponse(
                        batchId, 1, 1, 0, List.of(job)));

        mockMvc.perform(post("/api/v1/rag/embedding-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "collectionScopeMode": "SELECTED_COLLECTIONS",
                                  "collectionKeys": ["customer-42:manual:v3"],
                                  "force": true,
                                  "maxAttempts": 3
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.batchId")
                        .value(batchId.toString()))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.jobs[0].status").value("QUEUED"));
    }

    @Test
    void getListCancelAndRetryReturnStableJobFields() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        when(service.get(jobId)).thenReturn(
                response(jobId, batchId, "RUNNING", false));
        when(service.listPage(eq(batchId), eq(EmbeddingJobStatus.RUNNING),
                eq(null), eq(0), eq(10))).thenReturn(
                new com.springairag.api.dto.EmbeddingJobPageResponse(
                        List.of(response(jobId, batchId, "RUNNING", false)),
                        0, 10, 1, 1));
        when(service.cancel(jobId)).thenReturn(
                response(jobId, batchId, "CANCELLED", false));
        when(service.retry(jobId, 4)).thenReturn(
                response(jobId, batchId, "QUEUED", true));

        mockMvc.perform(get("/api/v1/rag/embedding-jobs/{id}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("RUNNING"));

        mockMvc.perform(get("/api/v1/rag/embedding-jobs")
                        .param("batchId", batchId.toString())
                        .param("status", "RUNNING")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].documentId").value(42))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(post(
                        "/api/v1/rag/embedding-jobs/{id}/cancel", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(post(
                        "/api/v1/rag/embedding-jobs/{id}/retry", jobId)
                        .param("maxAttempts", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.coalesced").value(true));
    }

    @Test
    void embeddingReadinessReturnsExclusiveBuckets() throws Exception {
        when(service.readiness("customer-42:manual:v3")).thenReturn(
                new CollectionEmbeddingReadinessResponse(
                        "customer-42:manual:v3",
                        "bge-m3",
                        5, 2, 1, 1, 0, 1));

        mockMvc.perform(get("/api/v1/rag/collections/embedding-readiness")
                        .param("collectionKey", "customer-42:manual:v3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectionKey")
                        .value("customer-42:manual:v3"))
                .andExpect(jsonPath("$.activeEmbeddingProfileKey")
                        .value("bge-m3"))
                .andExpect(jsonPath("$.enabledDocuments").value(5))
                .andExpect(jsonPath("$.freshDocuments").value(2))
                .andExpect(jsonPath("$.queuedDocuments").value(1))
                .andExpect(jsonPath("$.runningDocuments").value(1))
                .andExpect(jsonPath("$.failedDocuments").value(0))
                .andExpect(jsonPath("$.staleOrMissingDocuments").value(1));
    }

    private EmbeddingJobResponse response(
            UUID id, UUID batchId, String status, boolean coalesced) {
        OffsetDateTime now = OffsetDateTime.parse(
                "2026-08-17T00:00:00Z");
        return new EmbeddingJobResponse(
                id,
                batchId,
                42L,
                7L,
                true,
                "0123456789abcdef0123456789abcdef"
                        + "0123456789abcdef0123456789abcdef",
                3L,
                status,
                1,
                4,
                now,
                null,
                null,
                null,
                now,
                null,
                null,
                now,
                coalesced);
    }
}
