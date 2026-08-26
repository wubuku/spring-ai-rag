package com.springairag.core.controller;

import com.springairag.api.dto.IntegrationCapabilitiesResponse;
import com.springairag.api.enums.CollectionAccessMode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.service.IntegrationCapabilityCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IntegrationCapabilitiesController.class)
@Import(IntegrationCapabilitiesControllerTest.RagPropertiesTestConfig.class)
class IntegrationCapabilitiesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IntegrationCapabilityCatalog catalog;

    @Test
    void returnsVersionedContractWithoutSensitiveIdentityFields() throws Exception {
        IntegrationCapabilitiesResponse response = response(
                new IntegrationCapabilitiesResponse.Principal(
                        ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                        "NORMAL",
                        List.of("RAG_READ"),
                        CollectionAccessMode.RESTRICTED,
                        List.of("tenant:manual:v1")));
        when(catalog.describe(org.mockito.ArgumentMatchers.any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/rag/integration-capabilities")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.protocol.name")
                        .value("spring-ai-rag-integration"))
                .andExpect(jsonPath("$.protocol.version").value("1.0"))
                .andExpect(jsonPath("$.principal.principalType")
                        .value("DATABASE_API_KEY"))
                .andExpect(jsonPath("$.principal.allowedCollectionKeys[0]")
                        .value("tenant:manual:v1"))
                .andExpect(jsonPath("$.features.provisioning.replayReturnsSecret")
                        .value(false))
                .andExpect(jsonPath(
                        "$.features.optional.documentSyncRunItemReceipts")
                        .value(false))
                .andExpect(jsonPath("$.limits.collectionKeyMaxLength").value(128))
                .andExpect(jsonPath("$.credentialId").doesNotExist())
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(jsonPath("$.database").doesNotExist());
    }

    @Test
    void propagatesFailClosedCapabilityResolution() throws Exception {
        when(catalog.describe(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RagException(
                        com.springairag.api.enums.ErrorCode.SERVICE_UNAVAILABLE,
                        "unavailable"));

        mockMvc.perform(get("/api/v1/rag/integration-capabilities"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
    }

    private IntegrationCapabilitiesResponse response(
            IntegrationCapabilitiesResponse.Principal principal) {
        return new IntegrationCapabilitiesResponse(
                new IntegrationCapabilitiesResponse.Protocol(
                        "spring-ai-rag-integration", "1.0", "1.0.0"),
                principal,
                new IntegrationCapabilitiesResponse.Features(
                        new IntegrationCapabilitiesResponse.Provisioning(true, false, true),
                        new IntegrationCapabilitiesResponse.DataPlane(
                                true,
                                new IntegrationCapabilitiesResponse.JsonRecords(
                                        true, true, true, true, true, true),
                                new IntegrationCapabilitiesResponse.Embedding(true, true),
                                true),
                        new IntegrationCapabilitiesResponse.OptionalFeatures(false, false)),
                new IntegrationCapabilitiesResponse.Limits(100, 128, 128, 255, 255));
    }

    @TestConfiguration
    static class RagPropertiesTestConfig {
        @Bean
        RagProperties ragProperties() {
            return new RagProperties();
        }
    }
}
