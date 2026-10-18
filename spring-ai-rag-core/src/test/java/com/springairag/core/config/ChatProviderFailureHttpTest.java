package com.springairag.core.config;

import com.springairag.core.resilience.UpstreamFailureClassifier;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ChatProviderFailureHttpTest {
    static Stream<Arguments> failures() {
        return Stream.of("openai", "anthropic", "minimax").flatMap(provider ->
                Stream.of(400, 401, 403, 429, 503, 504).flatMap(status ->
                        Stream.of(false, true).map(stream -> Arguments.of(provider, status, stream))));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void providersPreserveStatusWithoutHiddenRetries(String provider, int status, boolean stream) throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            byte[] body = "{\"error\":{\"message\":\"private-provider-body\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Retry-After", "2");
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var config = new SpringAiConfig(new RagProperties());
            ReflectionTestUtils.setField(config, "provider", provider);
            String prefix = provider.equals("openai") ? "openAi" : provider;
            ReflectionTestUtils.setField(config, prefix + "BaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(config, prefix + "ApiKey", "fixture-key");
            ReflectionTestUtils.setField(config, prefix + "Model", "fixture-model");
            ReflectionTestUtils.setField(config, prefix + "Temperature", 0.0);
            ReflectionTestUtils.setField(config, "anthropicMaxTokens", 1024);
            ChatModel model = switch (provider) {
                case "openai" -> config.openAiChatModel();
                case "anthropic" -> config.anthropicChatModel();
                default -> config.miniMaxChatModel();
            };
            var error = assertThrows(RuntimeException.class, () -> {
                if (stream) model.stream(new Prompt("fixture")).blockLast(Duration.ofSeconds(5));
                else model.call(new Prompt("fixture"));
            });
            var details = UpstreamFailureClassifier.classify(error);
            assertEquals(status, details.upstreamStatus());
            assertEquals(status == 429 || status >= 500, details.retryable());
            assertEquals(2000L, details.retryAfterMs());
            assertEquals(1, calls.get());
            assertFalse(error.toString().contains("private-provider-body"));
            assertFalse(UpstreamFailureClassifier.safeMessage(error).contains("private-provider-body"));
        } finally {
            server.stop(0);
        }
    }
}
