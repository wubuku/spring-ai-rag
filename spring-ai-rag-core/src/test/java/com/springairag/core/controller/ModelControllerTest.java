package com.springairag.core.controller;

import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.ModelRegistry;
import com.springairag.core.config.RagCorsProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.service.ModelComparisonService;
import com.springairag.core.service.ModelComparisonService.ModelComparisonResult;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

/**
 * ModelController unit tests.
 */
@WebMvcTest(ModelController.class)
@Import({ApiVersionConfig.class, ModelControllerTest.RagPropertiesTestConfig.class})
class ModelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ModelRegistry modelRegistry;

    @MockBean
    private ChatModelRouter modelRouter;

    @MockBean
    private ModelComparisonService modelComparisonService;

    @Test
    void testListModels() throws Exception {
        when(modelRouter.isMultiModelEnabled()).thenReturn(true);
        when(modelRouter.getAvailableProviders()).thenReturn(List.of("openai", "minimax"));
        when(modelRouter.getFallbackChain()).thenReturn(List.of("openai", "minimax"));
        when(modelRegistry.getAllModelsInfo()).thenReturn(List.of(
                Map.of("provider", "openai", "available", true, "displayName", "OpenAI (DeepSeek/兼容)"),
                Map.of("provider", "minimax", "available", true, "displayName", "MiniMax")
        ));

        mockMvc.perform(get("/api/v1/rag/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.multiModelEnabled").value(true))
                .andExpect(jsonPath("$.availableProviders").isArray())
                .andExpect(jsonPath("$.fallbackChain").isArray());
    }

    @Test
    void testGetModel_existing() throws Exception {
        when(modelRouter.isProviderAvailable("openai")).thenReturn(true);
        when(modelRegistry.getModelInfo("openai")).thenReturn(
                Map.of("provider", "openai", "available", true, "displayName", "OpenAI (DeepSeek/兼容)")
        );

        mockMvc.perform(get("/api/v1/rag/models/openai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.provider").value("openai"))
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void testGetModel_notFound() throws Exception {
        when(modelRouter.isProviderAvailable("unknown")).thenReturn(false);

        mockMvc.perform(get("/api/v1/rag/models/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testCompareModels_success() throws Exception {
        when(modelComparisonService.compareProviders(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(
                        new ModelComparisonResult("openai", true, "OpenAI response", 100, 50, 30, 80, null),
                        new ModelComparisonResult("minimax", true, "MiniMax response", 80, 50, 25, 75, null)
                ));

        String body = """
                {"query": "hello", "providers": ["openai", "minimax"], "timeoutSeconds": 30}
                """;

        mockMvc.perform(post("/api/v1/rag/models/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("hello"))
                .andExpect(jsonPath("$.providers[0]").value("openai"))
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.results[0].modelName").value("openai"))
                .andExpect(jsonPath("$.results[0].success").value(true));
    }

    @Test
    void testCompareModels_emptyProviders() throws Exception {
        String body = """
                {"query": "hello", "providers": []}
                """;

        mockMvc.perform(post("/api/v1/rag/models/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testListModels_singleModel_disabledMultiModel() throws Exception {
        when(modelRouter.isMultiModelEnabled()).thenReturn(false);
        when(modelRouter.getAvailableProviders()).thenReturn(List.of("openai"));
        when(modelRouter.getFallbackChain()).thenReturn(null);
        when(modelRegistry.getAllModelsInfo()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/rag/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.multiModelEnabled").value(false))
                .andExpect(jsonPath("$.defaultProvider").value("openai"))
                .andExpect(jsonPath("$.availableProviders[0]").value("openai"))
                .andExpect(jsonPath("$.fallbackChain").value(nullValue()))
                .andExpect(jsonPath("$.models").isEmpty());
    }

    @TestConfiguration
    static class RagPropertiesTestConfig {
        @Bean
        RagProperties ragProperties() {
            return new RagProperties();
        }
    }
}
