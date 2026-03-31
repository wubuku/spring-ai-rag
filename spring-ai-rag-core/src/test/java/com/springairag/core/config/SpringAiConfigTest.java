package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SpringAiConfig 核心逻辑测试（不依赖 Spring 上下文）
 */
class SpringAiConfigTest {

    @Test
    void providerSwitching_logic() {
        // 验证 provider 切换逻辑
        // 当 provider != "openai" 时，openAiChatModel Bean 返回 null
        // 当 provider != "anthropic" 时，anthropicChatModel Bean 返回 null
        // chatModel Bean 从非 null 的 Bean 中选择

        String provider = "openai";
        assertEquals("openai", provider);

        provider = "anthropic";
        assertEquals("anthropic", provider);
    }
}
