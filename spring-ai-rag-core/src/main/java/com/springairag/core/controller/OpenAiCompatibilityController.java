package com.springairag.core.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.openai.OpenAiChatCompletionChunk;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.api.openai.OpenAiChatCompletionResponse;
import com.springairag.api.openai.OpenAiErrorResponse;
import com.springairag.api.openai.OpenAiModelResponse;
import com.springairag.core.chat.ChatEvent;
import com.springairag.core.chat.ChatExecutionResult;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.openai.OpenAiChatRequestMapper;
import com.springairag.core.openai.OpenAiModelAliasRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Exceptions;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI Chat Completions 的受控兼容入口。
 */
@RestController
@RequestMapping("/v1")
@ConditionalOnProperty(
        prefix = "rag.openai-compatibility",
        name = "enabled",
        havingValue = "true")
public class OpenAiCompatibilityController {

    private final OpenAiModelAliasRegistry aliasRegistry;
    private final OpenAiChatRequestMapper requestMapper;
    private final ChatExecutionService executionService;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibilityController(
            OpenAiModelAliasRegistry aliasRegistry,
            OpenAiChatRequestMapper requestMapper,
            ChatExecutionService executionService,
            ObjectMapper objectMapper) {
        this.aliasRegistry = aliasRegistry;
        this.requestMapper = requestMapper;
        this.executionService = executionService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/models")
    public OpenAiModelResponse.ListEnvelope listModels() {
        List<OpenAiModelResponse.Model> models = aliasRegistry.list().stream()
                .map(alias -> toModel(alias.alias()))
                .toList();
        return new OpenAiModelResponse.ListEnvelope("list", models);
    }

    @GetMapping("/models/{id}")
    public OpenAiModelResponse.Model getModel(@PathVariable String id) {
        return toModel(aliasRegistry.require(id).alias());
    }

    @PostMapping(
            value = "/chat/completions",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResponseBodyEmitter> chatCompletions(
            @RequestBody OpenAiChatCompletionRequest request,
            HttpServletRequest httpRequest) {
        OpenAiChatRequestMapper.MappedRequest mapped =
                requestMapper.map(request, httpRequest);
        String completionId = "chatcmpl-" + UUID.randomUUID()
                .toString().replace("-", "");
        long created = Instant.now().getEpochSecond();
        if (!mapped.stream()) {
            ChatExecutionResult result =
                    executionService.execute(mapped.command());
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonResponse(toResponse(
                            completionId,
                            created,
                            mapped.modelAlias(),
                            result)));
        }

        SseEmitter stream = streamResponse(
                completionId, created, mapped);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noCache())
                .body(stream);
    }

    private ResponseBodyEmitter jsonResponse(Object value) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
        try {
            emitter.send(value, MediaType.APPLICATION_JSON);
            emitter.complete();
        } catch (java.io.IOException error) {
            emitter.completeWithError(error);
        }
        return emitter;
    }

    private SseEmitter streamResponse(
            String id,
            long created,
            OpenAiChatRequestMapper.MappedRequest mapped) {
        String role = toJson(
                new OpenAiChatCompletionChunk(
                        id,
                        "chat.completion.chunk",
                        created,
                        mapped.modelAlias(),
                        List.of(new OpenAiChatCompletionChunk.Choice(
                                0,
                                new OpenAiChatCompletionChunk.Delta(
                                        "assistant", null),
                                null))));
        Flux<String> events =
                executionService.stream(mapped.command())
                        .concatMap(event -> mapEvent(
                                id, created, mapped.modelAlias(), event));
        Flux<String> response = Flux.concat(
                        Flux.just(role),
                        events,
                        Flux.just("[DONE]"))
                .onErrorResume(error -> Flux.just(
                        toJson(toStreamError(error)),
                        "[DONE]"));

        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        AtomicBoolean terminated = new AtomicBoolean();
        Runnable dispose = () -> {
            terminated.set(true);
            Disposable active = subscription.get();
            if (active != null) {
                active.dispose();
            }
        };
        emitter.onCompletion(dispose);
        emitter.onTimeout(dispose);
        emitter.onError(error -> dispose.run());

        Disposable active = response.subscribe(
                value -> send(emitter, value),
                emitter::completeWithError,
                emitter::complete);
        subscription.set(active);
        if (terminated.get()) {
            active.dispose();
        }
        return emitter;
    }

    private Flux<String> mapEvent(
            String id,
            long created,
            String model,
            ChatEvent event) {
        if (event instanceof ChatEvent.ContentDelta delta) {
            return Flux.just(toJson(
                    new OpenAiChatCompletionChunk(
                            id,
                            "chat.completion.chunk",
                            created,
                            model,
                            List.of(new OpenAiChatCompletionChunk.Choice(
                                    0,
                                    new OpenAiChatCompletionChunk.Delta(
                                            null, delta.content()),
                                    null)))));
        }
        if (event instanceof ChatEvent.Completed completed) {
            return Flux.just(toJson(
                    new OpenAiChatCompletionChunk(
                            id,
                            "chat.completion.chunk",
                            created,
                            model,
                            List.of(new OpenAiChatCompletionChunk.Choice(
                                    0,
                                    new OpenAiChatCompletionChunk.Delta(
                                            null, null),
                                    normalizeFinishReason(
                                            completed.finishReason()))))));
        }
        return Flux.empty();
    }

    private OpenAiChatCompletionResponse toResponse(
            String id,
            long created,
            String model,
            ChatExecutionResult result) {
        return new OpenAiChatCompletionResponse(
                id,
                "chat.completion",
                created,
                model,
                List.of(new OpenAiChatCompletionResponse.Choice(
                        0,
                        new OpenAiChatCompletionResponse.AssistantMessage(
                                "assistant", result.answer()),
                        normalizeFinishReason(result.finishReason()))),
                usage(result.usage()));
    }

    private OpenAiChatCompletionResponse.Usage usage(
            Map<String, Object> usage) {
        if (usage == null || usage.isEmpty()) {
            return null;
        }
        return new OpenAiChatCompletionResponse.Usage(
                integer(usage.get("promptTokens")),
                integer(usage.get("completionTokens")),
                integer(usage.get("totalTokens")));
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private String normalizeFinishReason(String value) {
        return value == null || value.isBlank()
                ? "stop"
                : value.toLowerCase(Locale.ROOT);
    }

    private OpenAiModelResponse.Model toModel(String alias) {
        return new OpenAiModelResponse.Model(
                alias, "model", 0L, "spring-ai-rag");
    }

    private OpenAiErrorResponse toStreamError(Throwable error) {
        if (error instanceof com.springairag.core.openai.OpenAiProtocolException e) {
            return OpenAiErrorResponse.of(
                    e.getMessage(), e.getType(), e.getParam(), e.getCode());
        }
        if (error instanceof com.springairag.core.exception.RagException e) {
            return OpenAiErrorResponse.of(
                    e.getMessage(),
                    e.getErrorCodeEnum().getHttpStatus() >= 500
                            ? "server_error"
                            : "invalid_request_error",
                    null,
                    e.getErrorCodeEnum().getCode());
        }
        return OpenAiErrorResponse.of(
                "The RAG service could not complete the streaming request",
                "server_error",
                null,
                "service_unavailable");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize OpenAI compatibility response", e);
        }
    }

    private void send(SseEmitter emitter, String value) {
        try {
            emitter.send(SseEmitter.event().data(value));
        } catch (java.io.IOException error) {
            throw Exceptions.propagate(error);
        }
    }
}
