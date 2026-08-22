package com.springairag.core.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.openai.OpenAiChatCompletionChunk;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.api.openai.OpenAiChatCompletionResponse;
import com.springairag.api.openai.OpenAiErrorResponse;
import com.springairag.api.openai.OpenAiModelResponse;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatEvent;
import com.springairag.core.chat.ChatExecutionResult;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatRequestFingerprint;
import com.springairag.core.chat.ChatTurnOperation;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.IdempotencyKeyValidator;
import com.springairag.api.dto.ChatResponse;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.retrieval.RetrievalTraceHeaders;
import com.springairag.core.openai.OpenAiChatRequestMapper;
import com.springairag.core.openai.OpenAiModelAliasRegistry;
import com.springairag.core.openai.OpenAiRequestRetrievalScopeAdapter;
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
import java.util.Collections;
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
    private ChatTurnOperationService turnOperationService;
    private RetrievalDiagnosticsService diagnosticsService;

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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDiagnosticsService(RetrievalDiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTurnOperationService(ChatTurnOperationService service) {
        this.turnOperationService = service;
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
        boolean streamRequested = Boolean.TRUE.equals(request.getStream());
        requestMapper.validateDeclaration(
                request,
                httpRequest == null
                        ? List.of()
                        : Collections.list(httpRequest.getHeaders(
                                OpenAiRequestRetrievalScopeAdapter
                                        .COLLECTION_KEY_HEADER)));
        ChatTurnOperationService.Prepared prepared =
                prepareTurn(request, httpRequest);
        ChatTurnOperationService.Claim claim =
                turnOperationService != null
                        ? turnOperationService.inspectExisting(prepared)
                        : null;
        if (claim != null && claim.replay()) {
            ChatResponse replay = turnOperationService.replay(claim);
            String completionId = keyedCompletionId(claim.operation().turnId());
            long created = claim.operation().createdAt().getEpochSecond();
            if (streamRequested) {
                return keyedReplayStream(
                        completionId,
                        created,
                        modelFor(claim.operation(), replay, request.getModel()),
                        replay,
                        claim);
            }
            return responseBuilder(
                    jsonResponse(toResponse(
                            completionId,
                            created,
                            modelFor(claim.operation(), replay, request.getModel()),
                            replay)),
                    claim,
                    null,
                    true);
        }

        OpenAiChatRequestMapper.MappedRequest mapped;
        if (prepared != null && prepared.keyed()) {
            mapped = prepared.operation() != null
                    && prepared.operation().executionSnapshot() != null
                    ? requestMapper.mapFromExecutionSnapshot(
                            request,
                            httpRequest,
                            prepared.operation().sessionId(),
                            prepared.operation().executionSnapshot())
                    : requestMapper.map(request, httpRequest);
            claim = turnOperationService.claim(
                    prepared,
                    mapped.command(),
                    streamRequested
                            ? ChatTurnOperation.Transport.OPENAI_SSE
                            : ChatTurnOperation.Transport.OPENAI_JSON,
                    streamRequested);
            if (claim.replay()) {
                ChatResponse replay = turnOperationService.replay(claim);
                String completionId = keyedCompletionId(claim.operation().turnId());
                long created = claim.operation().createdAt().getEpochSecond();
                if (streamRequested) {
                    return keyedReplayStream(
                            completionId, created,
                            modelFor(claim.operation(), replay, request.getModel()),
                            replay, claim);
                }
                return responseBuilder(
                        jsonResponse(toResponse(
                                completionId, created,
                                modelFor(claim.operation(), replay, request.getModel()),
                                replay)),
                        claim, null, true);
            }
            mapped = new OpenAiChatRequestMapper.MappedRequest(
                    mapped.modelAlias(),
                    mapped.stream(),
                    turnOperationService.commandForClaim(
                            mapped.command(), claim));
        } else {
            mapped = requestMapper.map(request, httpRequest);
        }
        ChatCommand command = claim != null && claim.keyed()
                ? attachTrace(mapped.command())
                : attachTrace(mapped.command());
        String completionId = claim != null && claim.keyed()
                ? keyedCompletionId(claim.operation().turnId())
                : "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        long created = claim != null && claim.keyed()
                ? claim.operation().createdAt().getEpochSecond()
                : Instant.now().getEpochSecond();
        String traceId = command.retrievalTraceSession() != null
                ? command.retrievalTraceSession().traceId().toString()
                : null;
        if (!mapped.stream()) {
            try {
                if (claim != null && claim.keyed()) {
                    ChatExecutionService.PreparedExecution preparedExecution =
                            executionService.prepareForOperation(
                                    command,
                                    claim.sessionLease(),
                                    false);
                    ChatResponse nativeResponse =
                            turnOperationService.completePrepared(
                                    claim, preparedExecution);
                    executionService.finalizePreparedOperation(
                            preparedExecution);
                    return responseBuilder(
                            jsonResponse(toResponse(
                                    completionId,
                                    created,
                                    mapped.modelAlias(),
                                    nativeResponse)),
                            claim,
                            traceId,
                            false);
                }
                ChatExecutionResult result = executionService.execute(command);
                ChatResponse nativeResponse = toNativeResponse(result);
                return responseBuilder(
                        jsonResponse(toResponse(
                                completionId, created, mapped.modelAlias(),
                                nativeResponse)),
                        claim,
                        traceId,
                        false);
            } catch (RuntimeException error) {
                if (claim != null && claim.keyed()) {
                    turnOperationService.fail(claim, error);
                }
                throw error;
            } finally {
                if (claim != null && claim.keyed()
                        && claim.sessionLease() != null) {
                    // The durable commit consumes the lease; release is a
                    // token-fenced no-op after a successful transaction.
                    // It also prevents a failed provider attempt from
                    // leaving an orphan session lease.
            releaseSessionLease(claim);
                }
            }
        }

        if (claim != null && claim.keyed()) {
            try {
                ChatExecutionService.PreparedExecution preparedExecution =
                        executionService.prepareForOperation(
                                command,
                                claim.sessionLease(),
                                true);
                ChatResponse nativeResponse =
                        turnOperationService.completePrepared(
                                claim, preparedExecution);
                executionService.finalizePreparedOperation(
                        preparedExecution);
                return keyedSnapshotStream(
                        completionId,
                        created,
                        mapped.modelAlias(),
                        nativeResponse,
                        claim,
                        false);
            } catch (RuntimeException error) {
                turnOperationService.fail(claim, error);
                throw error;
            } finally {
                releaseSessionLease(claim);
            }
        }
        SseEmitter streamEmitter = streamResponse(
                completionId, created,
                new OpenAiChatRequestMapper.MappedRequest(
                        mapped.modelAlias(), mapped.stream(), command),
                claim);
        return responseBuilder(streamEmitter, claim, traceId, false);
    }

    private void releaseSessionLease(
            ChatTurnOperationService.Claim claim) {
        if (turnOperationService != null) {
            turnOperationService.release(claim);
        }
    }

    private ChatCommand attachTrace(ChatCommand command) {
        if (diagnosticsService == null || !diagnosticsService.isEnabled()) {
            return command;
        }
        RetrievalTraceSession session = diagnosticsService.createSession(
                command.principal(),
                RetrievalTraceHeaders.OPERATION_OPENAI_CHAT,
                command.sessionId());
        return command.withTraceSession(session);
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
            OpenAiChatRequestMapper.MappedRequest mapped,
            ChatTurnOperationService.Claim claim) {
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

    private ResponseEntity<ResponseBodyEmitter> keyedReplayStream(
            String id,
            long created,
            String model,
            ChatResponse response,
            ChatTurnOperationService.Claim claim) {
        return keyedSnapshotStream(
                id, created, model, response, claim, true);
    }

    private ResponseEntity<ResponseBodyEmitter> keyedSnapshotStream(
            String id,
            long created,
            String model,
            ChatResponse response,
            ChatTurnOperationService.Claim claim,
            boolean replay) {
        String role = toJson(new OpenAiChatCompletionChunk(
                id, "chat.completion.chunk", created, model,
                List.of(new OpenAiChatCompletionChunk.Choice(
                        0,
                        new OpenAiChatCompletionChunk.Delta("assistant", null),
                        null))));
        String content = toJson(new OpenAiChatCompletionChunk(
                id, "chat.completion.chunk", created, model,
                List.of(new OpenAiChatCompletionChunk.Choice(
                        0,
                        new OpenAiChatCompletionChunk.Delta(null,
                                response.getAnswer()),
                        null))));
        String finish = toJson(new OpenAiChatCompletionChunk(
                id, "chat.completion.chunk", created, model,
                List.of(new OpenAiChatCompletionChunk.Choice(
                        0,
                        new OpenAiChatCompletionChunk.Delta(null, null),
                        normalizeFinishReason(response.getFinishReason())))));
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().data(role));
            emitter.send(SseEmitter.event().data(content));
            emitter.send(SseEmitter.event().data(finish));
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (java.io.IOException error) {
            emitter.completeWithError(error);
        }
        return responseBuilder(emitter, claim, null, replay);
    }

    private ResponseEntity.BodyBuilder responseHeaders(
            ChatTurnOperationService.Claim claim,
            String traceId,
            boolean replay) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (claim != null && claim.keyed()) {
            builder.header("X-RAG-Turn-Id",
                    claim.operation().turnId().toString());
            builder.header("X-RAG-Idempotent-Replay",
                    Boolean.toString(replay));
        }
        if (traceId != null) {
            builder.header(RetrievalTraceHeaders.TRACE_ID, traceId);
        }
        return builder;
    }

    private ResponseEntity<ResponseBodyEmitter> responseBuilder(
            ResponseBodyEmitter body,
            ChatTurnOperationService.Claim claim,
            String traceId,
            boolean replay) {
        return responseHeaders(claim, traceId, replay)
                .contentType(body instanceof SseEmitter
                        ? MediaType.TEXT_EVENT_STREAM
                        : MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noCache())
                .body(body);
    }

    private ChatTurnOperationService.Prepared prepareTurn(
            OpenAiChatCompletionRequest request,
            HttpServletRequest httpRequest) {
        if (turnOperationService == null) {
            return null;
        }
        List<String> idempotencyKeys = httpRequest == null
                ? List.of()
                : Collections.list(httpRequest.getHeaders("Idempotency-Key"));
        if (IdempotencyKeyValidator.normalize(idempotencyKeys) == null) {
            return turnOperationService.prepare(
                    ChatPrincipal.from(httpRequest),
                    idempotencyKeys,
                    null);
        }
        return turnOperationService.prepare(
                ChatPrincipal.from(httpRequest),
                idempotencyKeys,
                ChatRequestFingerprint.openAiRequest(
                        request,
                        objectMapper,
                        httpRequest == null
                                ? List.of()
                                : Collections.list(httpRequest.getHeaders(
                                        OpenAiRequestRetrievalScopeAdapter
                                                .COLLECTION_KEY_HEADER))));
    }

    private ChatTurnOperationService.Claim claimExisting(
            ChatTurnOperationService.Prepared prepared,
            boolean stream,
            OpenAiChatCompletionRequest request) {
        if (prepared == null || !prepared.keyed()
                || prepared.operation() == null) {
            return null;
        }
        return turnOperationService.claim(
                prepared,
                null,
                stream
                        ? ChatTurnOperation.Transport.OPENAI_SSE
                        : ChatTurnOperation.Transport.OPENAI_JSON);
    }

    private ChatResponse toNativeResponse(ChatExecutionResult result) {
        return ChatResponse.builder()
                .answer(result.answer())
                .sessionId(result.sessionId())
                .traceId(result.traceId())
                .mode(result.mode())
                .requestedModel(result.requestedModel())
                .resolvedModel(result.resolvedModel())
                .sources(result.sources())
                .usage(result.usage())
                .finishReason(result.finishReason())
                .metadata(result.metadata())
                .stepMetrics(result.stepMetrics())
                .build();
    }

    private String modelFor(ChatResponse response, String fallback) {
        return response.getRequestedModel() != null
                ? response.getRequestedModel()
                : fallback;
    }

    private String modelFor(
            ChatTurnOperation operation,
            ChatResponse response,
            String fallback) {
        if (operation != null && operation.executionSnapshot() != null) {
            try {
                JsonNode snapshot = objectMapper.readTree(
                        operation.executionSnapshot());
                String publicAlias = snapshot.path("publicModelAlias")
                        .asText(null);
                if (publicAlias != null && !publicAlias.isBlank()
                        && !"DEFAULT".equals(publicAlias)) {
                    return publicAlias;
                }
                String declared = snapshot.path("declaredModelIdentifier")
                        .asText(null);
                if (declared != null && !declared.isBlank()
                        && !"DEFAULT".equals(declared)) {
                    return declared;
                }
                if ("DEFAULT".equals(declared)) {
                    return fallback;
                }
            } catch (JsonProcessingException ignored) {
                // Fall back to the immutable business response field below.
            }
        }
        return modelFor(response, fallback);
    }

    private String keyedCompletionId(UUID turnId) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("openai-completion-v1:" + turnId)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "chatcmpl-rag-" + java.util.HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
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

    private OpenAiChatCompletionResponse toResponse(
            String id,
            long created,
            String model,
            ChatResponse response) {
        return new OpenAiChatCompletionResponse(
                id,
                "chat.completion",
                created,
                model,
                List.of(new OpenAiChatCompletionResponse.Choice(
                        0,
                        new OpenAiChatCompletionResponse.AssistantMessage(
                                "assistant", response.getAnswer()),
                        normalizeFinishReason(response.getFinishReason()))),
                usage(response.getUsage()));
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
