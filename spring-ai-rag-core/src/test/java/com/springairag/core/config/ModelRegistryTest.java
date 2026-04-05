package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * ModelRegistry 单元测试（纯 Mock 方式，无需 Spring 上下文）
 */
class ModelRegistryTest {

    private ApplicationContext ctx;
    private ModelRegistry registry;

    private ChatModel mockOpenAiModel;
    private ChatModel mockMiniMaxModel;

    @BeforeEach
    void setUp() {
        ctx = mock(ApplicationContext.class);
        mockOpenAiModel = mock(ChatModel.class);
        mockMiniMaxModel = mock(ChatModel.class);
    }

    private ModelRegistry buildRegistry(String... availableProviders) {
        // 直接使用 when...thenReturn 链式调用
        // Mock 返回 null 表示 Bean 不存在
        when(ctx.getBean(anyString(), eq(ChatModel.class))).thenReturn(null);

        // 只为可用的 provider 配置返回值
        for (String p : availableProviders) {
            if ("openai".equals(p)) {
                when(ctx.getBean("openAiChatModel", ChatModel.class)).thenReturn(mockOpenAiModel);
            } else if ("anthropic".equals(p)) {
                ChatModel anthropic = mock(ChatModel.class);
                when(ctx.getBean("anthropicChatModel", ChatModel.class)).thenReturn(anthropic);
            } else if ("minimax".equals(p)) {
                when(ctx.getBean("miniMaxChatModel", ChatModel.class)).thenReturn(mockMiniMaxModel);
            }
        }

        registry = new ModelRegistry(ctx, new RagProperties(), null);
        registry.init();
        return registry;
    }

    // ========== init() 行为测试 ==========

    @Test
    @DisplayName("有可用 ChatModel 时注册到 providers 集合")
    void testInit_withAvailableModels() {
        ModelRegistry r = buildRegistry("openai");
        assertTrue(r.availableProviders().contains("openai"));
    }

    @Test
    @DisplayName("无任何可用 ChatModel 时 providers 为空")
    void testInit_withNoModels() {
        when(ctx.getBean(anyString(), eq(ChatModel.class))).thenReturn(null);
        registry = new ModelRegistry(ctx, new RagProperties(), null);
        registry.init();
        assertTrue(registry.availableProviders().isEmpty());
    }

    // ========== get() 测试 ==========

    @Test
    @DisplayName("get(openai) 返回对应的 ChatModel")
    void testGet_existingProvider() {
        ModelRegistry r = buildRegistry("openai");
        assertSame(mockOpenAiModel, r.get("openai"));
    }

    @Test
    @DisplayName("get(unknown) 抛出 IllegalArgumentException")
    void testGet_unknownProvider() {
        ModelRegistry r = buildRegistry("openai");
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> r.get("unknown"));
        assertTrue(ex.getMessage().contains("unknown"));
    }

    // ========== isAvailable() 测试 ==========

    @Test
    @DisplayName("已注册 provider 返回 true")
    void testIsAvailable_true() {
        assertTrue(buildRegistry("openai").isAvailable("openai"));
    }

    @Test
    @DisplayName("未注册 provider 返回 false")
    void testIsAvailable_false() {
        assertFalse(buildRegistry("openai").isAvailable("minimax"));
    }

    // ========== getDisplayName() 测试 ==========

    @Test
    @DisplayName("已知 provider 返回正确显示名")
    void testGetDisplayName_known() {
        assertEquals("OpenAI (DeepSeek/兼容)", buildRegistry("openai").getDisplayName("openai"));
        assertEquals("Anthropic (Claude)", buildRegistry("anthropic").getDisplayName("anthropic"));
        assertEquals("MiniMax", buildRegistry("minimax").getDisplayName("minimax"));
    }

    @Test
    @DisplayName("未知 provider 返回原标识")
    void testGetDisplayName_unknown() {
        assertEquals("unknown", buildRegistry("openai").getDisplayName("unknown"));
    }

    // ========== getModelInfo() 测试 ==========

    @Test
    @DisplayName("getModelInfo(openai) 返回完整信息")
    void testGetModelInfo() {
        ModelRegistry r = buildRegistry("openai");
        Map<String, Object> info = r.getModelInfo("openai");
        assertEquals("openai", info.get("provider"));
        assertEquals(true, info.get("available"));
        assertEquals("OpenAI (DeepSeek/兼容)", info.get("displayName"));
        assertNotNull(info.get("className"));
    }

    // ========== getAllModelsInfo() 测试 ==========

    @Test
    @DisplayName("getAllModelsInfo 返回所有已注册模型")
    void testGetAllModelsInfo() {
        ModelRegistry r = buildRegistry("openai", "minimax");
        List<Map<String, Object>> all = r.getAllModelsInfo();
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(m -> "openai".equals(m.get("provider"))));
        assertTrue(all.stream().anyMatch(m -> "minimax".equals(m.get("provider"))));
    }
}
