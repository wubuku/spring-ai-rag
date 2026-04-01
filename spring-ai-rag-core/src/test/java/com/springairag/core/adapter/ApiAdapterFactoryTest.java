package com.springairag.core.adapter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApiAdapterFactory 单元测试
 */
class ApiAdapterFactoryTest {

    private ApiAdapterFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ApiAdapterFactory();
    }

    // ==================== null base URL ====================

    @Test
    void getAdapter_nullUrl_returnsOpenAiAdapter() {
        ApiCompatibilityAdapter adapter = factory.getAdapter(null);
        assertInstanceOf(OpenAiCompatibleAdapter.class, adapter);
    }

    // ==================== MiniMax ====================

    @Test
    void getAdapter_minimaxiUrl_returnsMiniMaxAdapter() {
        ApiCompatibilityAdapter adapter = factory.getAdapter("https://api.minimaxi.com/v1");
        assertInstanceOf(MiniMaxAdapter.class, adapter);
    }

    @Test
    void getAdapter_minimaxInUrl_returnsMiniMaxAdapter() {
        ApiCompatibilityAdapter adapter = factory.getAdapter("https://minimax.example.com");
        assertInstanceOf(MiniMaxAdapter.class, adapter);
    }

    @Test
    void getAdapter_minimaxUpperCase_returnsMiniMaxAdapter() {
        ApiCompatibilityAdapter adapter = factory.getAdapter("https://api.MINIMAXI.COM/v1");
        assertInstanceOf(MiniMaxAdapter.class, adapter);
    }

    // ==================== OpenAI 兼容（默认） ====================

    @Test
    void getAdapter_openAiUrl_returnsOpenAiAdapter() {
        ApiCompatibilityAdapter adapter = factory.getAdapter("https://api.openai.com/v1");
        assertInstanceOf(OpenAiCompatibleAdapter.class, adapter);
    }

    @Test
    void getAdapter_deepSeekUrl_returnsOpenAiAdapter() {
        ApiCompatibilityAdapter adapter = factory.getAdapter("https://api.deepseek.com/v1");
        assertInstanceOf(OpenAiCompatibleAdapter.class, adapter);
    }

    @Test
    void getAdapter_zhipuUrl_returnsOpenAiAdapter() {
        ApiCompatibilityAdapter adapter = factory.getAdapter("https://open.bigmodel.cn/paas/v4");
        assertInstanceOf(OpenAiCompatibleAdapter.class, adapter);
    }

    @Test
    void getAdapter_unknownUrl_returnsOpenAiAdapterAsDefault() {
        ApiCompatibilityAdapter adapter = factory.getAdapter("https://unknown-provider.example.com");
        assertInstanceOf(OpenAiCompatibleAdapter.class, adapter);
    }

    @Test
    void getAdapter_emptyString_returnsOpenAiAdapter() {
        ApiCompatibilityAdapter adapter = factory.getAdapter("");
        assertInstanceOf(OpenAiCompatibleAdapter.class, adapter);
    }
}
