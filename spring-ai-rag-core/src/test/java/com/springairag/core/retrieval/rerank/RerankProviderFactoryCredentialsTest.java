package com.springairag.core.retrieval.rerank;

import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * RerankProviderFactory 凭证继承守卫（Batch 407）：rerank 专属
 * api-key/baseUrl 缺失时从 embedding 配置继承、已有值不被覆盖、
 * provider 名称的 trim/lowercase 归一化与 null 回退。
 */
class RerankProviderFactoryCredentialsTest {

    private RagProperties props;

    @BeforeEach
    void setUp() {
        props = new RagProperties();
    }

    @Test
    void nullProviderFallsBackToHeuristic() {
        props.getRerank().setProvider(null);
        assertEquals("heuristic", new RerankProviderFactory(props).create().getName());
    }

    @Test
    void providerAliasIsTrimmedAndLowercased() {
        props.getRerank().setProvider("  HTTP  ");
        props.getRerank().setApiKey("sk");
        assertEquals("http", new RerankProviderFactory(props).create().getName());
    }

    @Test
    void blankRerankKeyInheritsEmbeddingKey() {
        props.getRerank().setProvider("http");
        props.getRerank().setApiKey("");
        props.getEmbedding().setApiKey("emb-sk");

        new RerankProviderFactory(props).create();

        assertEquals("emb-sk", props.getRerank().getApiKey());
    }

    @Test
    void existingRerankKeyIsNotOverwritten() {
        props.getRerank().setProvider("http");
        props.getRerank().setApiKey("own-sk");
        props.getEmbedding().setApiKey("emb-sk");

        new RerankProviderFactory(props).create();

        assertEquals("own-sk", props.getRerank().getApiKey());
    }

    @Test
    void blankEmbeddingKeyLeavesRerankKeyUnchanged() {
        props.getRerank().setProvider("http");
        props.getRerank().setApiKey(null);
        props.getEmbedding().setApiKey("  ");

        new RerankProviderFactory(props).create();

        assertNull(props.getRerank().getApiKey());
    }

    @Test
    void blankRerankBaseUrlInheritsEmbeddingBaseUrl() {
        props.getRerank().setProvider("http");
        props.getRerank().setApiKey("sk");
        // rerank baseUrl 有非空默认值，继承分支需先显式置空。
        props.getRerank().setBaseUrl("");
        props.getEmbedding().setBaseUrl("https://embedding.example.com");

        new RerankProviderFactory(props).create();

        assertEquals("https://embedding.example.com",
                props.getRerank().getBaseUrl());
    }

    @Test
    void existingRerankBaseUrlIsNotOverwritten() {
        props.getRerank().setProvider("http");
        props.getRerank().setApiKey("sk");
        props.getRerank().setBaseUrl("https://rerank.example.com");
        props.getEmbedding().setBaseUrl("https://embedding.example.com");

        new RerankProviderFactory(props).create();

        assertEquals("https://rerank.example.com",
                props.getRerank().getBaseUrl());
    }

    @Test
    void missingEmbeddingBaseUrlLeavesRerankDefaultUnchanged() {
        props.getRerank().setProvider("http");
        props.getRerank().setApiKey("sk");
        String defaultBaseUrl = props.getRerank().getBaseUrl();

        new RerankProviderFactory(props).create();

        // embedding 未配置 baseUrl → rerank 保留自身默认值。
        assertEquals(defaultBaseUrl, props.getRerank().getBaseUrl());
    }
}
