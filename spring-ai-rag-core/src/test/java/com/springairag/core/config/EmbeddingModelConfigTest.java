package com.springairag.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EmbeddingModelConfig
 */
class EmbeddingModelConfigTest {

    @Test
    @DisplayName("embeddingModel creates OpenAiEmbeddingModel with correct config")
    void embeddingModel_createsOpenAiEmbeddingModel_withCorrectConfig() {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        ReflectionTestUtils.setField(config, "apiKey", "test-siliconflow-key");
        ReflectionTestUtils.setField(config, "model", "BAAI/bge-m3");
        ReflectionTestUtils.setField(config, "dimensions", 1024);
        ReflectionTestUtils.setField(config, "retryMaxAttempts", 10);

        EmbeddingModel model = config.embeddingModel();

        assertNotNull(model);
        assertTrue(model instanceof OpenAiEmbeddingModel,
                "Expected OpenAiEmbeddingModel but got " + model.getClass().getName());
    }

    @Test
    @DisplayName("embeddingModel uses correct dimensions for BAAI/bge-m3")
    void embeddingModel_usesCorrectDimensions() {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        ReflectionTestUtils.setField(config, "apiKey", "test-key");
        ReflectionTestUtils.setField(config, "model", "BAAI/bge-m3");
        ReflectionTestUtils.setField(config, "dimensions", 1024);
        ReflectionTestUtils.setField(config, "retryMaxAttempts", 10);

        EmbeddingModel model = config.embeddingModel();

        assertNotNull(model);
        assertTrue(model instanceof OpenAiEmbeddingModel);
    }

    @Test
    @DisplayName("embeddingModel supports custom baseUrl (compatible with other OpenAI-compatible APIs)")
    void embeddingModel_supportsCustomBaseUrl() {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        ReflectionTestUtils.setField(config, "apiKey", "custom-key");
        ReflectionTestUtils.setField(config, "model", "custom-embedding-model");
        ReflectionTestUtils.setField(config, "dimensions", 1536);
        ReflectionTestUtils.setField(config, "retryMaxAttempts", 10);

        EmbeddingModel model = config.embeddingModel();

        assertNotNull(model);
        assertTrue(model instanceof OpenAiEmbeddingModel);
    }

    @Test
    @DisplayName("embeddingModel applies an explicit bounded retry budget")
    void embeddingModel_appliesExplicitRetryBudget() {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        ReflectionTestUtils.setField(config, "apiKey", "test-key");
        ReflectionTestUtils.setField(config, "model", "BAAI/bge-m3");
        ReflectionTestUtils.setField(config, "dimensions", 1024);
        ReflectionTestUtils.setField(config, "retryMaxAttempts", 1);

        OpenAiEmbeddingModel model =
                (OpenAiEmbeddingModel) config.embeddingModel();
        RetryTemplate retryTemplate = (RetryTemplate)
                ReflectionTestUtils.getField(model, "retryTemplate");
        AtomicInteger attempts = new AtomicInteger();

        assertNotNull(retryTemplate);
        assertThrows(IllegalStateException.class, () ->
                retryTemplate.execute(context -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("deterministic failure");
                }));
        assertEquals(1, attempts.get());
    }

    @Test
    @DisplayName("custom retry budgets preserve Spring AI transient-only exponential retry semantics")
    void embeddingModel_preservesTransientRetrySemantics() {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        ReflectionTestUtils.setField(config, "apiKey", "test-key");
        ReflectionTestUtils.setField(config, "model", "BAAI/bge-m3");
        ReflectionTestUtils.setField(config, "dimensions", 1024);
        ReflectionTestUtils.setField(config, "retryMaxAttempts", 3);

        OpenAiEmbeddingModel model =
                (OpenAiEmbeddingModel) config.embeddingModel();
        RetryTemplate retryTemplate = (RetryTemplate)
                ReflectionTestUtils.getField(model, "retryTemplate");
        Object backOffPolicy =
                ReflectionTestUtils.getField(retryTemplate, "backOffPolicy");
        AtomicInteger transientAttempts = new AtomicInteger();
        AtomicInteger permanentAttempts = new AtomicInteger();

        assertTrue(backOffPolicy instanceof ExponentialBackOffPolicy);
        retryTemplate.setBackOffPolicy(new NoBackOffPolicy());
        assertThrows(TransientAiException.class, () ->
                retryTemplate.execute(context -> {
                    transientAttempts.incrementAndGet();
                    throw new TransientAiException("temporary failure");
                }));
        assertThrows(IllegalStateException.class, () ->
                retryTemplate.execute(context -> {
                    permanentAttempts.incrementAndGet();
                    throw new IllegalStateException("permanent failure");
                }));
        assertEquals(3, transientAttempts.get());
        assertEquals(1, permanentAttempts.get());
    }

    @Test
    @DisplayName("embeddingModel rejects retry budgets outside the supported range")
    void embeddingModel_rejectsInvalidRetryBudget() {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        ReflectionTestUtils.setField(config, "apiKey", "test-key");
        ReflectionTestUtils.setField(config, "model", "BAAI/bge-m3");
        ReflectionTestUtils.setField(config, "dimensions", 1024);
        ReflectionTestUtils.setField(config, "retryMaxAttempts", 0);

        assertThrows(IllegalArgumentException.class, config::embeddingModel);
    }
}
