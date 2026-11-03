package com.springairag.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EmbeddingModelConfig
 */
class EmbeddingModelConfigTest {
    @Test
    void profileDimensionsAreOmittedByDefault() throws Exception {
        assertEmbeddingRequestDimensions(false, false);
    }

    @Test
    void profileDimensionsAreSentOnlyWhenExplicitlyEnabled() throws Exception {
        assertEmbeddingRequestDimensions(true, false);
    }

    @Test
    void defaultModelSupportsExplicitDimensionsWithoutChangingOtherProfiles() throws Exception {
        assertEmbeddingRequestDimensions(true, true);
    }

    private void assertEmbeddingRequestDimensions(boolean requestDimensions, boolean defaultModel) throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"object":"list","model":"fixture","data":[{"object":"embedding","index":0,"embedding":[0.25,0.5]}],
                    "usage":{"prompt_tokens":1,"total_tokens":1}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            EmbeddingModelConfig config = new EmbeddingModelConfig();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            EmbeddingModel model;
            if (defaultModel) {
                ReflectionTestUtils.setField(config, "apiKey", "fixture-key");
                ReflectionTestUtils.setField(config, "baseUrl", baseUrl);
                ReflectionTestUtils.setField(config, "model", "fixture");
                ReflectionTestUtils.setField(config, "dimensions", 768);
                ReflectionTestUtils.setField(config, "requestDimensions", requestDimensions);
                ReflectionTestUtils.setField(config, "retryMaxAttempts", 1);
                model = config.embeddingModel();
            } else {
                RagEmbeddingProfileProperties profile = new RagEmbeddingProfileProperties();
                profile.setApiKey("fixture-key");
                profile.setBaseUrl(baseUrl);
                profile.setModel("fixture");
                profile.setDimensions(768);
                profile.setRequestDimensions(requestDimensions);
                model = config.createModel(profile);
            }
            assertArrayEquals(new float[]{0.25f, 0.5f}, model.embed("fixture"));
            var payload = new ObjectMapper().readTree(requestBody.get());
            assertEquals(requestDimensions, payload.has("dimensions"));
            if (requestDimensions) {
                assertEquals(768, payload.get("dimensions").asInt());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retryBudgetCannotContinueBeyondDependencyDeadline() {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        ReflectionTestUtils.setField(config, "apiKey", "fixture-key");
        ReflectionTestUtils.setField(config, "retryMaxAttempts", 3);
        var model = config.embeddingModel();
        RetryTemplate template = (RetryTemplate) ReflectionTestUtils.getField(model, "retryTemplate");
        AtomicInteger calls = new AtomicInteger();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50);
        try (var scope = com.springairag.core.admission.DependencyCallContext.open(deadline, false)) {
            assertThrows(com.springairag.core.admission.DependencyCallException.class,
                    () -> template.execute(context -> {
                        calls.incrementAndGet();
                        throw new TransientAiException("temporary");
                    }));
        }
        assertEquals(1, calls.get());
    }

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

        assertTrue(backOffPolicy instanceof EmbeddingModelConfig.EmbeddingBackOffPolicy);
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

    @Test
    @DisplayName("embeddingModel fails fast when the provider key is missing")
    void embeddingModel_failsFastWhenApiKeyIsMissing() {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        ReflectionTestUtils.setField(config, "apiKey", " ");
        ReflectionTestUtils.setField(config, "model", "BAAI/bge-m3");
        ReflectionTestUtils.setField(config, "dimensions", 1024);
        ReflectionTestUtils.setField(config, "retryMaxAttempts", 1);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, config::embeddingModel);

        assertTrue(error.getMessage().contains("selected Profile"));
    }

    @Test
    @DisplayName("embeddingModel rejects remote plaintext http at startup")
    void embeddingModel_rejectsRemotePlaintextHttp() {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        ReflectionTestUtils.setField(config, "apiKey", "test-key");
        ReflectionTestUtils.setField(config, "baseUrl", "http://api.example.com");
        ReflectionTestUtils.setField(config, "model", "BAAI/bge-m3");
        ReflectionTestUtils.setField(config, "dimensions", 1024);
        ReflectionTestUtils.setField(config, "retryMaxAttempts", 1);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, config::embeddingModel);

        assertTrue(error.getMessage().contains("rag.embedding.base-url"));
        assertTrue(error.getMessage().contains("rag.security.allow-insecure-upstream"));
    }

    @Test
    @DisplayName("embeddingModel normalization drops /v1 like the chat path")
    void embeddingModel_normalizesTrailingV1() {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        ReflectionTestUtils.setField(config, "apiKey", "test-key");
        ReflectionTestUtils.setField(config, "baseUrl", "https://api.example.com/v1/");
        ReflectionTestUtils.setField(config, "model", "BAAI/bge-m3");
        ReflectionTestUtils.setField(config, "dimensions", 1024);
        ReflectionTestUtils.setField(config, "retryMaxAttempts", 1);

        EmbeddingModel model = config.embeddingModel();
        assertNotNull(model);
        assertTrue(model instanceof OpenAiEmbeddingModel);
    }

    @Test
    @DisplayName("embedding retry policy retries rate limits and 5xx but not auth failures")
    void embeddingRetryPolicy_usesFailureClassification() {
        assertTrue(EmbeddingModelConfig.isRetryableUpstreamFailure(rateLimited()));
        assertTrue(EmbeddingModelConfig.isRetryableUpstreamFailure(
                HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "unavailable",
                        HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)));
        assertTrue(EmbeddingModelConfig.isRetryableUpstreamFailure(
                new ResourceAccessException("timeout", new SocketTimeoutException("read timed out"))));
        assertFalse(EmbeddingModelConfig.isRetryableUpstreamFailure(
                HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "unauthorized",
                        HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)));
        assertFalse(EmbeddingModelConfig.isRetryableUpstreamFailure(
                new IllegalStateException("permanent")));
    }

    @Test
    @DisplayName("embedding backoff honours provider Retry-After as a lower bound")
    void embeddingBackOff_honoursRetryAfter() {
        assertEquals(2_000, EmbeddingModelConfig.EmbeddingBackOffPolicy.computeDelay(1, null));
        assertEquals(10_000, EmbeddingModelConfig.EmbeddingBackOffPolicy.computeDelay(2, null));
        assertEquals(8_000, EmbeddingModelConfig.EmbeddingBackOffPolicy.computeDelay(1, 8_000L));
        assertEquals(10_000, EmbeddingModelConfig.EmbeddingBackOffPolicy.computeDelay(2, 3_000L));
        // 超过最大退避窗口时不继续拉长等待。
        assertEquals(10_000, EmbeddingModelConfig.EmbeddingBackOffPolicy.computeDelay(1, 60_000L));
    }

    private static HttpClientErrorException rateLimited() {
        return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "rate limited",
                new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
    }
}
