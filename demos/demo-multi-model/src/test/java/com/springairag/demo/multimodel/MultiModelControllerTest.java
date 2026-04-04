package com.springairag.demo.multimodel;

import com.springairag.api.dto.ChatResponse;
import com.springairag.core.config.ModelRegistry;
import com.springairag.core.controller.GlobalExceptionHandler;
import com.springairag.core.service.ModelComparisonService;
import com.springairag.core.service.ModelComparisonService.ModelComparisonResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MultiModelController 单元测试
 *
 * <p>验证多模型演示控制器的三个核心功能：
 * <ul>
 *   <li>GET /demo/models — 模型注册中心查询</li>
 *   <li>POST /demo/chat?provider=xxx — 指定模型问答</li>
 *   <li>POST /demo/compare — 模型并行对比</li>
 * </ul>
 */
@WebMvcTest(MultiModelController.class)
@Import(GlobalExceptionHandler.class)
class MultiModelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ModelRegistry modelRegistry;

    @MockBean
    private ModelComparisonService modelComparisonService;

    @BeforeEach
    void setUp() {
        when(modelRegistry.availableProviders()).thenReturn(
                java.util.Set.of("openai", "anthropic"));
    }

    // ─────────────────────────────────────────────────────────
    // GET /demo/models
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /demo/models — 返回模型注册中心信息")
    void listModels_returnsModelRegistryInfo() throws Exception {
        when(modelRegistry.getAllModelsInfo()).thenReturn(List.of(
                Map.of("provider", "openai", "available", true, "displayName", "DeepSeek"),
                Map.of("provider", "anthropic", "available", true, "displayName", "Claude")
        ));

        mockMvc.perform(get("/demo/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableProviders[0]").value("openai"))
                .andExpect(jsonPath("$.availableProviders[1]").value("anthropic"))
                .andExpect(jsonPath("$.models").isArray())
                .andExpect(jsonPath("$.models.length()").value(2));
    }

    @Test
    @DisplayName("GET /demo/models — 无可用模型时返回空列表")
    void listModels_whenNoProvidersAvailable() throws Exception {
        when(modelRegistry.availableProviders()).thenReturn(java.util.Set.of());
        when(modelRegistry.getAllModelsInfo()).thenReturn(List.of());

        mockMvc.perform(get("/demo/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableProviders").isEmpty())
                .andExpect(jsonPath("$.models").isEmpty());
    }

    // ─────────────────────────────────────────────────────────
    // GET /demo/models/{provider}
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /demo/models/{provider} — 返回指定模型详情")
    void getModel_returnsModelDetails() throws Exception {
        when(modelRegistry.isAvailable("openai")).thenReturn(true);
        when(modelRegistry.getModelInfo("openai")).thenReturn(
                Map.of("provider", "openai", "displayName", "DeepSeek", "available", true));

        mockMvc.perform(get("/demo/models/openai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("openai"))
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    @DisplayName("GET /demo/models/{provider} — provider 不存在时返回 404")
    void getModel_returns404_whenNotAvailable() throws Exception {
        when(modelRegistry.isAvailable("unknown")).thenReturn(false);

        mockMvc.perform(get("/demo/models/unknown"))
                .andExpect(status().isNotFound());
    }

    // ─────────────────────────────────────────────────────────
    // POST /demo/chat
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /demo/chat — 使用 openai 模型回答")
    void chatWithProvider_returnsAnswer() throws Exception {
        when(modelRegistry.isAvailable("openai")).thenReturn(true);
        when(modelComparisonService.compareProviders(eq("什么是 RAG？"), anyList(), anyInt()))
                .thenReturn(List.of(new ModelComparisonResult(
                        "deepseek-chat", true, "DeepSeek 的回答", 150, 100, 50, 150, null)));

        mockMvc.perform(post("/demo/chat")
                        .param("provider", "openai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "什么是 RAG？"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("DeepSeek 的回答"));
    }

    @Test
    @DisplayName("POST /demo/chat — provider 不可用时返回 400")
    void chatWithProvider_throws_whenNotAvailable() throws Exception {
        when(modelRegistry.isAvailable("minimax")).thenReturn(false);

        mockMvc.perform(post("/demo/chat")
                        .param("provider", "minimax")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "测试"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /demo/chat — 模型调用失败时抛出异常")
    void chatWithProvider_throws_whenModelFails() throws Exception {
        when(modelRegistry.isAvailable("openai")).thenReturn(true);
        when(modelComparisonService.compareProviders(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new ModelComparisonResult(
                        "deepseek-chat", false, null, 0, 0, 0, 0, "API rate limit exceeded")));

        mockMvc.perform(post("/demo/chat")
                        .param("provider", "openai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "测试"}
                                """))
                .andExpect(status().is5xxServerError());
    }

    // ─────────────────────────────────────────────────────────
    // POST /demo/compare
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /demo/compare — 并行对比两个模型")
    void compareModels_returnsBothResults() throws Exception {
        when(modelComparisonService.compareProviders(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(
                        new ModelComparisonResult("deepseek-chat", true, "DeepSeek 回答", 150, 100, 50, 150, null),
                        new ModelComparisonResult("claude-3-5-sonnet", true, "Claude 回答", 200, 120, 80, 200, null)
                ));

        mockMvc.perform(post("/demo/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "什么是向量检索？",
                                  "providers": ["openai", "anthropic"],
                                  "timeoutSeconds": 30
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("什么是向量检索？"))
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].success").value(true))
                .andExpect(jsonPath("$.results[0].latencyMs").value(150));
    }

    @Test
    @DisplayName("POST /demo/compare — 并行对比超时行为")
    void compareModels_respectsTimeout() throws Exception {
        when(modelComparisonService.compareProviders(anyString(), anyList(), eq(10)))
                .thenReturn(List.of(new ModelComparisonResult(
                        "deepseek-chat", true, "回答内容", 100, 50, 50, 100, null)));

        mockMvc.perform(post("/demo/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "测试超时",
                                  "providers": ["openai"],
                                  "timeoutSeconds": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1));
    }
}
