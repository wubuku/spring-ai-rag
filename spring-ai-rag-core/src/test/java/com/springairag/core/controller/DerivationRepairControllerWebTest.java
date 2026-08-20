package com.springairag.core.controller;

import com.springairag.api.dto.DerivationRepairPreviewResponse;
import com.springairag.core.service.DerivationRepairService;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DerivationRepairController.class)
@Import({
        GlobalExceptionHandler.class,
        ApiVersionConfig.class,
        DerivationRepairControllerWebTest.RagPropertiesTestConfig.class
})
class DerivationRepairControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DerivationRepairService service;

    @TestConfiguration
    static class RagPropertiesTestConfig {
        @Bean
        com.springairag.core.config.RagProperties ragProperties() {
            return new com.springairag.core.config.RagProperties();
        }
    }

    @Test
    void previewIsBoundedByValidationAndNeverAcceptsMoreThanOneHundred() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.preview(any())).thenReturn(new DerivationRepairPreviewResponse(
                id, "collection-a", "fingerprint", "token", Instant.now(),
                List.of(new DerivationRepairPreviewResponse.Item(
                        1L, "REBUILD_LOCAL", "LOCAL_FAILED")),
                Map.of("REBUILD_LOCAL", 1L), 0));

        mockMvc.perform(post("/api/v1/rag/collections/derivation-repairs/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "collectionKey": "collection-a",
                                  "buckets": ["CORRUPT"],
                                  "vectorConditions": ["FAILED"],
                                  "maxDocuments": 100
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repairId").value(id.toString()))
                .andExpect(jsonPath("$.items[0].action").value("REBUILD_LOCAL"));

        mockMvc.perform(post("/api/v1/rag/collections/derivation-repairs/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "collectionKey": "collection-a",
                                  "buckets": ["CORRUPT"],
                                  "maxDocuments": 101
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
