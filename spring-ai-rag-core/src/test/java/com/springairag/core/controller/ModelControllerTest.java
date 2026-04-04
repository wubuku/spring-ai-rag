package com.springairag.core.controller;

import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.ModelRegistry;
import com.springairag.core.service.ModelComparisonService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ModelController 单元测试
 */
@WebMvcTest(ModelController.class)
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
                Map.of("provider", "openai", "available", true, "displayName", "OpenAI"),
                Map.of("provider", "minimax", "available", true, "displayName", "MiniMax")
        ));
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
                .andExpect(jsonPath("$.provider").value("openai"))
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void testGetModel_notFound() throws Exception {
        when(modelRouter.isProviderAvailable("unknown")).thenReturn(false);

        mockMvc.perform(get("/api/v1/rag/models/unknown"))
                .andExpect(status().isNotFound());
    }
}
