package com.springairag.core.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatHistoryResponse;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.filter.RequestTraceFilter;
import com.springairag.core.metrics.RagMetricsService;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.rag.ProjectDocumentRetriever;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.repository.RagChatHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 三种 Chat 模式共享的生产执行内核。
 */
@Service
public class ChatExecutionService {

    private static final Logger log =
            LoggerFactory.getLogger(ChatExecutionService.class);

    private final ChatModelRouter modelRouter;
    private final ModeAwareChatClientFactory clientFactory;
    private final KnowledgeSearchTool knowledgeSearchTool;
    private final RagChatHistoryRepository historyRepository;
    private final DomainExtensionRegistry domainExtensions;
    private final PromptCustomizerChain promptCustomizers;
    private final RetrievalDocumentMapper documentMapper;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;
    private final RagMetricsService metricsService;
    private final RetryTemplate retryTemplate;
    private final ChatSessionCoordinator sessionCoordinator;

    public ChatExecutionService(
            ChatModelRouter modelRouter,
            ModeAwareChatClientFactory clientFactory,
            KnowledgeSearchTool knowledgeSearchTool,
            RagChatHistoryRepository historyRepository,
            DomainExtensionRegistry domainExtensions,
            PromptCustomizerChain promptCustomizers,
            RetrievalDocumentMapper documentMapper,
            ObjectMapper objectMapper,
            RagProperties ragProperties,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            RagMetricsService metricsService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            RetryTemplate retryTemplate) {
        this(modelRouter, clientFactory, knowledgeSearchTool, historyRepository,
                domainExtensions, promptCustomizers, documentMapper, objectMapper,
                ragProperties, metricsService, retryTemplate, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ChatExecutionService(
            ChatModelRouter modelRouter,
            ModeAwareChatClientFactory clientFactory,
            KnowledgeSearchTool knowledgeSearchTool,
            RagChatHistoryRepository historyRepository,
            DomainExtensionRegistry domainExtensions,
            PromptCustomizerChain promptCustomizers,
            RetrievalDocumentMapper documentMapper,
            ObjectMapper objectMapper,
            RagProperties ragProperties,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            RagMetricsService metricsService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            RetryTemplate retryTemplate,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ChatSessionCoordinator sessionCoordinator) {
        this.modelRouter = modelRouter;
        this.clientFactory = clientFactory;
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.historyRepository = historyRepository;
        this.domainExtensions = domainExtensions;
        this.promptCustomizers = promptCustomizers;
        this.documentMapper = documentMapper;
        this.objectMapper = objectMapper;
        this.ragProperties = ragProperties;
        this.metricsService = metricsService;
        this.retryTemplate = retryTemplate;
        this.sessionCoordinator = sessionCoordinator;
    }

    public ChatExecutionResult execute(ChatCommand command) {
        validateMode(command);
        ChatSessionCoordinator.LeaseHandle lease =
                sessionCoordinator != null
                        ? sessionCoordinator.acquire(command, false)
                        : null;
        try {
            List<Message> baseline = loadBaseline(command);
            List<ChatModelRouter.ChatModelCandidate> candidates =
                    eligibleCandidates(command, false);
            RuntimeException lastFailure = null;

            for (int index = 0; index < candidates.size(); index++) {
                ChatModelRouter.ChatModelCandidate candidate = candidates.get(index);
                long startedAt = System.currentTimeMillis();
                try {
                    ModeAwareChatClientFactory.Attempt attempt =
                            clientFactory.create(command, candidate, baseline);
                    Supplier<ChatClientResponse> invocation =
                            () -> invoke(attempt, command);
                    Supplier<ChatClientResponse> retried = () ->
                            retryTemplate != null
                                    ? retryTemplate.execute(
                                            context -> invocation.get())
                                    : invocation.get();
                    ChatClientResponse response = sessionCoordinator != null
                            ? sessionCoordinator.invokeWithinDeadline(
                                    lease, retried)
                            : retried.get();
                    ChatExecutionResult result =
                            toResult(command, attempt, response);
                    ChatExecutionResult committedResult =
                            withPersistenceMetadata(command, result);
                    if (sessionCoordinator != null) {
                        List<Message> committedMessages =
                                attempt.memory() != null
                                        ? attempt.memory().get(
                                                command.memoryConversationId())
                                        : List.of();
                        sessionCoordinator.commit(
                                lease,
                                command,
                                committedResult,
                                committedMessages,
                                serializeDocumentIds(committedResult.sources()));
                    } else {
                        persist(command, committedResult);
                    }
                    if (metricsService != null) {
                        metricsService.recordSuccess(
                                System.currentTimeMillis() - startedAt,
                            tokenCount(committedResult.usage()));
                    }
                    if (index > 0) {
                        log.info("Chat fallback succeeded: requested={}, resolved={}",
                                command.modelRef(), candidate.ref());
                    }
                    return committedResult;
                } catch (RagException e) {
                    throw e;
                } catch (RuntimeException e) {
                    lastFailure = e;
                    if (metricsService != null) {
                        metricsService.recordFailure(
                                System.currentTimeMillis() - startedAt);
                    }
                    log.warn("Chat candidate {}/{} failed ({}): {}",
                            index + 1, candidates.size(), candidate.ref(),
                            e.getMessage());
                }
            }
            if (lastFailure != null) {
                throw lastFailure;
            }
            throw new RagException(
                    ErrorCode.LLM_UNAVAILABLE,
                    "No eligible chat model is available");
        } finally {
            if (sessionCoordinator != null) {
                sessionCoordinator.release(lease);
            }
        }
    }

    /**
     * Structured streaming execution shared by the HTTP SSE and other transports.
     *
     * <p>Spring AI owns the streaming tool-call recursion. This method only maps
     * response deltas to transport-neutral events and commits the completed turn
     * after the aggregated response is available.</p>
     */
    public Flux<ChatEvent> stream(ChatCommand command) {
        validateMode(command);
        return Flux.defer(() -> {
            ChatSessionCoordinator.LeaseHandle lease =
                    sessionCoordinator != null
                            ? sessionCoordinator.acquire(command, true)
                            : null;
            try {
                List<Message> baseline = loadBaseline(command);
                List<ChatModelRouter.ChatModelCandidate> candidates =
                        eligibleCandidates(command, true);
                return streamCandidates(
                        command, baseline, candidates, 0, lease)
                        .doFinally(signal -> {
                            if (sessionCoordinator != null) {
                                sessionCoordinator.release(lease);
                            }
                        });
            } catch (RuntimeException e) {
                if (sessionCoordinator != null) {
                    sessionCoordinator.release(lease);
                }
                return Flux.error(e);
            }
        });
    }

    private Flux<ChatEvent> streamCandidates(
            ChatCommand command,
            List<Message> baseline,
            List<ChatModelRouter.ChatModelCandidate> candidates,
            int index,
            ChatSessionCoordinator.LeaseHandle lease) {
        ChatModelRouter.ChatModelCandidate candidate = candidates.get(index);
            Flux<ChatEvent> attempt = Flux.defer(() ->
                streamCandidate(command, baseline, candidate, lease));
        return attempt.switchOnFirst((signal, flux) -> {
            boolean hasEvent = signal.hasValue();
            if (!hasEvent && signal.isOnError()
                    && index + 1 < candidates.size()) {
                log.warn("Streaming chat candidate {}/{} failed before first event ({}), trying fallback",
                        index + 1, candidates.size(), candidate.ref(),
                        signal.getThrowable() != null
                                ? signal.getThrowable().getMessage()
                                : "unknown error");
                return streamCandidates(
                        command, baseline, candidates, index + 1, lease);
            }
            if (!hasEvent && signal.isOnComplete()
                    && index + 1 < candidates.size()) {
                return streamCandidates(
                        command, baseline, candidates, index + 1, lease);
            }
            return flux;
        });
    }

    private Flux<ChatEvent> streamCandidate(
            ChatCommand command,
            List<Message> baseline,
            ChatModelRouter.ChatModelCandidate candidate,
            ChatSessionCoordinator.LeaseHandle lease) {
        ModeAwareChatClientFactory.Attempt attempt =
                clientFactory.create(command, candidate, baseline);
        AtomicReference<ChatClientResponse> aggregated =
                new AtomicReference<>();
        AtomicReference<ChatClientResponse> lastResponse =
                new AtomicReference<>();
        Flux<ChatClientResponse> streamResponses = invokeStream(attempt, command);
        if (lease != null) {
            Duration remaining = Duration.between(
                    java.time.Instant.now(), lease.deadline());
            streamResponses = streamResponses.timeout(
                    remaining.isPositive() ? remaining : Duration.ofMillis(1));
        }
        final Flux<ChatClientResponse> responses = streamResponses;

        return Flux.defer(() -> {
            Sinks.Many<ChatEvent> sink =
                    Sinks.many().unicast().onBackpressureBuffer();
            Flux<ChatClientResponse> aggregatedFlux =
                    new ChatClientMessageAggregator()
                            .aggregateChatClientResponse(
                                    responses.doOnNext(response -> {
                                        lastResponse.set(response);
                                        responseEvents(
                                                attempt.retrievalContext(),
                                                response)
                                                .forEach(event -> sink.tryEmitNext(event));
                                    }),
                                    aggregated::set);
            Disposable subscription = aggregatedFlux
                    .then(Mono.defer(() -> completeStreamAttempt(
                            command,
                            attempt,
                            lease,
                            aggregated.get() != null
                                    ? aggregated.get()
                                    : lastResponse.get())
                            .doOnNext(event -> sink.tryEmitNext(event))
                            .then()))
                    .subscribe(
                            ignored -> {
                            },
                            error -> sink.tryEmitError(error),
                            () -> sink.tryEmitComplete());
            return sink.asFlux()
                    .doOnCancel(subscription::dispose);
        });
    }

    private Flux<ChatEvent> completeStreamAttempt(
            ChatCommand command,
            ModeAwareChatClientFactory.Attempt attempt,
            ChatSessionCoordinator.LeaseHandle lease,
            ChatClientResponse response) {
        if (response == null
                || response.chatResponse() == null
                || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            return Flux.error(new IllegalStateException(
                    "LLM returned no usable streaming response"));
        }
        ChatExecutionResult result =
                withPersistenceMetadata(command, toResult(command, attempt, response));
        if (sessionCoordinator != null) {
            List<Message> committedMessages = attempt.memory() != null
                    ? attempt.memory().get(command.memoryConversationId())
                    : List.of();
            sessionCoordinator.commit(
                    lease,
                    command,
                    result,
                    committedMessages,
                    serializeDocumentIds(result.sources()));
        } else {
            persist(command, result);
        }
        List<ChatEvent> events = new ArrayList<>(
                attempt.retrievalContext().trace().drainToolEvents());
        events.add(new ChatEvent.SourcesAvailable(
                result.sessionId(), result.sources()));
        events.add(new ChatEvent.Completed(
                result.traceId(),
                result.sessionId(),
                result.requestedModel(),
                result.resolvedModel(),
                result.mode(),
                result.usage(),
                result.finishReason(),
                result.stepMetrics()));
        return Flux.fromIterable(events);
    }

    private List<ChatEvent> responseEvents(
            AuthorizedRetrievalContext context,
            ChatClientResponse response) {
        List<ChatEvent> events = new ArrayList<>(context.trace().drainToolEvents());
        if (response == null
                || response.chatResponse() == null
                || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            return events;
        }
        String content = response.chatResponse()
                .getResult().getOutput().getText();
        if (content != null && !content.isEmpty()) {
            events.add(new ChatEvent.ContentDelta(content));
        }
        return events;
    }

    private Flux<ChatClientResponse> invokeStream(
            ModeAwareChatClientFactory.Attempt attempt,
            ChatCommand command) {
        ChatClient.ChatClientRequestSpec spec = attempt.client().prompt();
        String systemPrompt = buildSystemPrompt(command);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            spec.system(systemPrompt);
        }
        spec.user(customizeUserMessage(command));
        spec.advisors(advisor -> advisor.param(
                ProjectDocumentRetriever.CONTEXT_KEY,
                attempt.retrievalContext()));
        if (command.mode() == ChatMode.AGENT) {
            spec.toolCallbacks(knowledgeSearchTool);
            spec.toolContext(Map.of(
                    KnowledgeSearchTool.CONTEXT_KEY,
                    attempt.retrievalContext()));
            if (attempt.candidate().model().getDefaultOptions()
                    instanceof ToolCallingChatOptions options) {
                spec.options(options.copy());
            }
        }
        return spec.stream().chatClientResponse();
    }

    private ChatClientResponse invoke(
            ModeAwareChatClientFactory.Attempt attempt,
            ChatCommand command) {
        ChatClient.ChatClientRequestSpec spec = attempt.client().prompt();
        String systemPrompt = buildSystemPrompt(command);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            spec.system(systemPrompt);
        }
        spec.user(customizeUserMessage(command));
        spec.advisors(advisor -> advisor.param(
                ProjectDocumentRetriever.CONTEXT_KEY,
                attempt.retrievalContext()));
        if (command.mode() == ChatMode.AGENT) {
            spec.toolCallbacks(knowledgeSearchTool);
            spec.toolContext(Map.of(
                    KnowledgeSearchTool.CONTEXT_KEY,
                    attempt.retrievalContext()));
            if (attempt.candidate().model().getDefaultOptions()
                    instanceof ToolCallingChatOptions options) {
                spec.options(options.copy());
            }
        }
        ChatClientResponse response = spec.call().chatClientResponse();
        if (response == null
                || response.chatResponse() == null
                || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            throw new IllegalStateException("LLM returned no usable chat response");
        }
        return response;
    }

    private ChatExecutionResult toResult(
            ChatCommand command,
            ModeAwareChatClientFactory.Attempt attempt,
            ChatClientResponse response) {
        var springResponse = response.chatResponse();
        String answer = springResponse.getResult().getOutput().getText();
        List<ChatSource> sources = extractSources(command.mode(), attempt, response);
        Map<String, Object> usage = usage(springResponse.getMetadata().getUsage());
        String finishReason = springResponse.getResult().getMetadata() != null
                ? springResponse.getResult().getMetadata().getFinishReason()
                : null;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sessionId", command.sessionId());
        metadata.put("retrieval", attempt.retrievalContext().trace().summary());
        metadata.put("retrievalExecuted",
                attempt.retrievalContext().trace().retrievalCalls() > 0);
        return new ChatExecutionResult(
                answer,
                command.sessionId(),
                MDC.get(RequestTraceFilter.TRACE_ID_KEY),
                command.modelRef(),
                attempt.candidate().ref(),
                command.mode(),
                sources,
                usage,
                finishReason,
                List.of(),
                metadata);
    }

    @SuppressWarnings("unchecked")
    private List<ChatSource> extractSources(
            ChatMode mode,
            ModeAwareChatClientFactory.Attempt attempt,
            ChatClientResponse response) {
        if (mode == ChatMode.PLAIN) {
            return List.of();
        }
        if (mode == ChatMode.KNOWLEDGE) {
            Object value = response.context().get(
                    RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
            if (value instanceof List<?> values) {
                List<Document> documents = values.stream()
                        .filter(Document.class::isInstance)
                        .map(Document.class::cast)
                        .toList();
                List<ChatSource> sources = new ArrayList<>();
                for (int index = 0; index < documents.size(); index++) {
                    sources.add(documentMapper.toChatSource(
                            documents.get(index), index + 1));
                }
                return List.copyOf(sources);
            }
        }
        List<RetrievalResult> results =
                attempt.retrievalContext().trace().sources();
        List<ChatSource> sources = new ArrayList<>();
        for (RetrievalResult result : results) {
            String citationId =
                    attempt.retrievalContext().trace().citationId(result);
            if (citationId != null) {
                sources.add(documentMapper.toChatSource(result, citationId));
            }
        }
        return List.copyOf(sources);
    }

    private void persist(ChatCommand command, ChatExecutionResult result) {
        historyRepository.save(
                command.sessionId(),
                command.message(),
                result.answer(),
                serializeDocumentIds(result.sources()),
                result.metadata());
    }

    private ChatExecutionResult withPersistenceMetadata(
            ChatCommand command,
            ChatExecutionResult result) {
        Map<String, Object> metadata = new LinkedHashMap<>(
                command.clientMetadata());
        metadata.putAll(result.metadata());
        metadata.put("mode", result.mode().name());
        metadata.put("memoryMode", command.memoryMode().name());
        putIfNotNull(metadata, "requestedModel", result.requestedModel());
        putIfNotNull(metadata, "resolvedModel", result.resolvedModel());
        putIfNotNull(metadata, "traceId", result.traceId());
        putIfNotNull(metadata, "finishReason", result.finishReason());
        metadata.put("usage", result.usage());
        metadata.put("stepMetrics", result.stepMetrics());
        return new ChatExecutionResult(
                result.answer(),
                result.sessionId(),
                result.traceId(),
                result.requestedModel(),
                result.resolvedModel(),
                result.mode(),
                result.sources(),
                result.usage(),
                result.finishReason(),
                result.stepMetrics(),
                metadata);
    }

    private void putIfNotNull(
            Map<String, Object> metadata,
            String key,
            Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private String serializeDocumentIds(List<ChatSource> sources) {
        List<Long> ids = sources.stream()
                .map(ChatSource::getDocumentId)
                .map(this::parseLong)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chat source IDs", e);
        }
    }

    private Long parseLong(String value) {
        try {
            return value != null ? Long.valueOf(value) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<Message> loadBaseline(ChatCommand command) {
        if (command.memoryMode() == MemoryMode.STATELESS) {
            return List.of();
        }
        int limit = Math.max(1, ragProperties.getMemory().getMaxMessages());
        List<ChatHistoryResponse> history = sessionCoordinator != null
                ? historyRepository.findOwnedBaseline(
                        command.principal(),
                        command.sessionId(),
                        limit)
                : historyRepository.findBySessionId(
                        command.sessionId(),
                        limit);
        List<ChatHistoryResponse> chronological = new ArrayList<>(history);
        Collections.reverse(chronological);
        List<Message> messages = new ArrayList<>();
        for (ChatHistoryResponse item : chronological) {
            if (item.userMessage() != null && !item.userMessage().isBlank()) {
                messages.add(new UserMessage(item.userMessage()));
            }
            if (item.aiResponse() != null && !item.aiResponse().isBlank()) {
                messages.add(new AssistantMessage(item.aiResponse()));
            }
        }
        return List.copyOf(messages);
    }

    private List<ChatModelRouter.ChatModelCandidate> eligibleCandidates(
            ChatCommand command,
            boolean streaming) {
        List<ChatModelRouter.ChatModelCandidate> all =
                modelRouter.orderedCandidateDescriptors(command.modelRef());
        if (command.modelRef() != null && !command.modelRef().isBlank()) {
            ChatModelRouter.ChatModelCandidate requested = all.isEmpty()
                    ? modelRouter.resolveCandidateRequired(command.modelRef())
                    : all.getFirst();
            validateCandidate(requested, command.mode(), streaming, true);
        }
        List<ChatModelRouter.ChatModelCandidate> eligible = all.stream()
                .filter(candidate -> isEligible(candidate, command.mode(), streaming))
                .toList();
        if (eligible.isEmpty()) {
            ErrorCode code = streaming
                    ? ErrorCode.MODEL_STREAMING_UNSUPPORTED
                    : ErrorCode.MODEL_CAPABILITY_UNSUPPORTED;
            throw new RagException(
                    code,
                    "No configured model supports mode " + command.mode()
                            + (streaming ? " with streaming" : ""));
        }
        return eligible;
    }

    private boolean isEligible(
            ChatModelRouter.ChatModelCandidate candidate,
            ChatMode mode,
            boolean streaming) {
        if (streaming && !candidate.supportsStreaming()) {
            return false;
        }
        if (mode == ChatMode.AGENT) {
            return candidate.supportsToolCalling()
                    && candidate.model().getDefaultOptions()
                    instanceof ToolCallingChatOptions;
        }
        return true;
    }

    private void validateCandidate(
            ChatModelRouter.ChatModelCandidate candidate,
            ChatMode mode,
            boolean streaming,
            boolean explicitlyRequested) {
        if (streaming && !candidate.supportsStreaming()) {
            throw new RagException(
                    ErrorCode.MODEL_STREAMING_UNSUPPORTED,
                    "Model '" + candidate.ref() + "' does not support streaming");
        }
        if (mode == ChatMode.AGENT
                && (!candidate.supportsToolCalling()
                || !(candidate.model().getDefaultOptions()
                instanceof ToolCallingChatOptions))) {
            throw new RagException(
                    ErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
                    "Model '" + candidate.ref()
                            + "' does not support Spring AI tool calling");
        }
    }

    private void validateMode(ChatCommand command) {
        if (command.mode() == ChatMode.AGENT
                && !ragProperties.getChat().getAgent().isEnabled()) {
            throw new RagException(
                    ErrorCode.CHAT_AGENT_DISABLED,
                    "Agent chat mode is disabled");
        }
    }

    private String buildSystemPrompt(ChatCommand command) {
        String modePrompt = switch (command.mode()) {
            case KNOWLEDGE -> "你是知识库问答助手。只依据检索到的资料回答，"
                    + "资料不足时明确说明，并使用 [S1] 形式标注来源。";
            case AGENT -> "你是知识探索助手。需要知识库证据时调用 "
                    + "searchKnowledge；不得声称访问了工具未返回的资料。";
            case PLAIN -> "你是通用 AI 助手。";
        };
        String domainPrompt = null;
        if (command.domainId() != null && !command.domainId().isBlank()) {
            domainPrompt = domainExtensions.getSystemPromptTemplate(
                    command.domainId(),
                    command.mode());
        }
        String prompt = domainPrompt == null || domainPrompt.isBlank()
                ? modePrompt
                : domainPrompt + "\n\n" + modePrompt;
        if (promptCustomizers.hasCustomizers()) {
            prompt = promptCustomizers.customizeSystemPrompt(
                    prompt,
                    "",
                    command.clientMetadata());
        }
        return prompt;
    }

    private String customizeUserMessage(ChatCommand command) {
        if (!promptCustomizers.hasCustomizers()) {
            return command.message();
        }
        return promptCustomizers.customizeUserMessage(
                command.message(),
                command.clientMetadata());
    }

    private Map<String, Object> usage(Usage usage) {
        if (usage == null) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (usage.getPromptTokens() != null) {
            result.put("promptTokens", usage.getPromptTokens());
        }
        if (usage.getCompletionTokens() != null) {
            result.put("completionTokens", usage.getCompletionTokens());
        }
        if (usage.getTotalTokens() != null) {
            result.put("totalTokens", usage.getTotalTokens());
        }
        return Map.copyOf(result);
    }

    private int tokenCount(Map<String, Object> usage) {
        Object value = usage.get("totalTokens");
        return value instanceof Number number ? number.intValue() : 0;
    }
}
