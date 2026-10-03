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
import com.springairag.core.config.RagChatProperties;
import com.springairag.api.dto.CitationValidation;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.evaluation.CitationValidator;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.exception.RagException;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.filter.RequestTraceFilter;
import com.springairag.core.http.HttpToolExecutionState;
import com.springairag.core.metrics.RagMetricsService;
import com.springairag.core.rag.JsonRecordSearchTool;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.rag.ProjectDocumentRetriever;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.skill.RuntimeSkillCatalog;
import com.springairag.core.skill.RuntimeSkillLoadSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final JsonRecordSearchTool jsonRecordSearchTool; // optional-claim: JsonRecordSearchTool 是无条件 @Component，null 臂只在不走 Spring 装配的构造路径可达；守卫真正的职责是 isEnabled() 功能开关——结构化记录检索可以被配置关掉，不是"工具可能不存在"
    private final RagChatHistoryRepository historyRepository;
    private final DomainExtensionRegistry domainExtensions;
    private final PromptCustomizerChain promptCustomizers;
    private final RetrievalDocumentMapper documentMapper;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;
    private final RagMetricsService metricsService; // optional-claim: RagMetricsService 是无条件 @Service，null 臂只在不走 Spring 装配的构造路径可达；守卫让指标缺席时不拖垮主调用——指标是旁路，不该是调用失败的原因
    private final RetryTemplate retryTemplate;
    private final ChatSessionCoordinator sessionCoordinator; // optional-claim: ChatSessionCoordinator 是无条件 @Component，null 臂只在不走 Spring 装配的构造路径可达；守卫真正的职责是"这次调用要不要会话租约"——无租约的单次执行/流式路径必须能跑
    private final RagChatToolRegistry toolRegistry; // optional-claim: RagChatToolRegistry 是无条件 @Component，null 臂只在不走 Spring 装配的构造路径可达；守卫真正的职责是"这次请求要不要挂服务端工具"，无工具时退化为纯知识检索
    private final ConversationPromptPlanner promptPlanner;
    private ConversationSummaryService summaryService; // optional-claim: ConversationSummaryService 是无条件 @Service，null 臂只在不走 Spring 装配的构造路径可达；守卫真正的职责是产出可观测的 summary 状态（summary_service_unavailable），而不是把缺失悄悄压成空串
    private RetrievalDiagnosticsService diagnosticsService; // optional-claim: RetrievalDiagnosticsService 是无条件 @Service，null 臂只在不走 Spring 装配的构造路径可达；守卫真正的职责是 traceSession 为空的早退——没开检索追踪时诊断写入就该是 no-op
    private CitationValidator citationValidator; // optional-claim: CitationValidator 是无条件 @Component，null 臂只在不走 Spring 装配的构造路径可达；守卫真正的职责是 isCitationValidationEnabled() 功能开关
    private ChatObservabilityService chatObservability; // optional-claim: ChatObservabilityService 是无条件 @Component，null 臂只在不走 Spring 装配的构造路径可达；守卫让观测计数缺席时不影响主流程——观测是旁路，不该是调用失败的原因
    private RuntimeSkillCatalog runtimeSkillCatalog; // optional-claim: RuntimeSkillCatalog 是无条件 @Component，null 臂只在不走 Spring 装配的构造路径可达；守卫真正的职责是 enabled() 功能开关

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
                null, domainExtensions, promptCustomizers, documentMapper,
                objectMapper, ragProperties, metricsService, retryTemplate, null,
                null);
    }

    public ChatExecutionService(
            ChatModelRouter modelRouter,
            ModeAwareChatClientFactory clientFactory,
            KnowledgeSearchTool knowledgeSearchTool,
            RagChatHistoryRepository historyRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            JsonRecordSearchTool jsonRecordSearchTool,
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
        this(modelRouter, clientFactory, knowledgeSearchTool, historyRepository,
                jsonRecordSearchTool, domainExtensions, promptCustomizers,
                documentMapper, objectMapper, ragProperties, metricsService,
                retryTemplate, sessionCoordinator, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ChatExecutionService(
            ChatModelRouter modelRouter,
            ModeAwareChatClientFactory clientFactory,
            KnowledgeSearchTool knowledgeSearchTool,
            RagChatHistoryRepository historyRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            JsonRecordSearchTool jsonRecordSearchTool,
            DomainExtensionRegistry domainExtensions,
            PromptCustomizerChain promptCustomizers,
            RetrievalDocumentMapper documentMapper,
            ObjectMapper objectMapper,
            RagProperties ragProperties,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            RagMetricsService metricsService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            RetryTemplate retryTemplate,
            ChatSessionCoordinator sessionCoordinator,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            RagChatToolRegistry toolRegistry) {
        this.modelRouter = modelRouter;
        this.clientFactory = clientFactory;
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.jsonRecordSearchTool = jsonRecordSearchTool;
        this.historyRepository = historyRepository;
        this.domainExtensions = domainExtensions;
        this.promptCustomizers = promptCustomizers;
        this.documentMapper = documentMapper;
        this.objectMapper = objectMapper;
        this.ragProperties = ragProperties;
        this.metricsService = metricsService;
        this.retryTemplate = retryTemplate;
        this.sessionCoordinator = sessionCoordinator;
        this.toolRegistry = toolRegistry;
        this.promptPlanner = new ConversationPromptPlanner(
                ragProperties.getChat(),
                new JTokkitPromptTokenEstimator());
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDiagnosticsService(RetrievalDiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCitationValidator(CitationValidator citationValidator) {
        this.citationValidator = citationValidator;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setChatObservability(ChatObservabilityService chatObservability) {
        this.chatObservability = chatObservability;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSummaryService(ConversationSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRuntimeSkillCatalog(RuntimeSkillCatalog runtimeSkillCatalog) {
        this.runtimeSkillCatalog = runtimeSkillCatalog;
    }

    public ChatExecutionResult execute(ChatCommand command) {
        validateMode(command);
        command = command.withExecutionBudget(newBudget(command, false));
        final ChatCommand request = command;
        ChatSessionCoordinator.LeaseHandle lease =
                sessionCoordinator != null
                        ? sessionCoordinator.acquire(command, false)
                        : null;
        try {
            List<Message> baseline = loadBaseline(command);
            String selectedSummary = summaryText(command);
            List<ChatModelRouter.ChatModelCandidate> candidates =
                    eligibleCandidates(command, false);
            RuntimeException lastFailure = null;

            for (int index = 0; index < candidates.size(); index++) {
                final ChatModelRouter.ChatModelCandidate candidate =
                        candidates.get(index);
                long startedAt = System.currentTimeMillis();
                AtomicReference<ModeAwareChatClientFactory.Attempt> successfulAttempt =
                        new AtomicReference<>();
                try {
                    Supplier<ChatClientResponse> retried = withRetries(
                            candidateInvocation(
                                    request, candidate, baseline,
                                    selectedSummary, successfulAttempt));
                    ChatClientResponse response = sessionCoordinator != null
                            ? sessionCoordinator.invokeWithinDeadline(
                                    lease, retried)
                            : retried.get();
                    return commitExecutedTurn(
                            command, candidate, lease, startedAt,
                            successfulAttempt.get(), response, baseline, index);
                } catch (RagException e) {
                    markAttempt(command, successfulAttempt.get(), false);
                    throw e;
                } catch (RuntimeException e) {
                    markAttempt(command, successfulAttempt.get(), false);
                    lastFailure = e;
                    recordCandidateFailure(
                            startedAt, index, candidates.size(),
                            candidate.ref(), e, "Chat candidate");
                }
            }
            if (lastFailure != null) {
                throw lastFailure;
            }
            throw new RagException(
                    ErrorCode.LLM_UNAVAILABLE,
                    "No eligible chat model is available");
        } finally {
            persistDiagnostics(command);
            if (sessionCoordinator != null) {
                sessionCoordinator.release(lease);
            }
        }
    }

    /**
     * Executes the model/tool portion of a durable operation without committing
     * history, shared memory, or a terminal operation state.
     *
     * <p>The caller owns the supplied session lease and must pass the returned
     * messages/result to the coordinated commit boundary. This method exists so
     * an idempotent turn cannot first commit business state and only afterwards
     * attempt to persist its replay snapshot.</p>
     */
    public PreparedExecution prepareForOperation(
            ChatCommand command,
            ChatSessionCoordinator.LeaseHandle lease,
            boolean streaming) {
        validateMode(command);
        ChatCommand request = command.withExecutionBudget(
                newBudget(command, streaming));
        List<Message> baseline = loadBaseline(request);
        String selectedSummary = summaryText(request);
        List<ChatModelRouter.ChatModelCandidate> candidates =
                eligibleCandidates(request, streaming);
        RuntimeException lastFailure = null;

        for (int index = 0; index < candidates.size(); index++) {
            ChatModelRouter.ChatModelCandidate candidate = candidates.get(index);
            long startedAt = System.currentTimeMillis();
            AtomicReference<ModeAwareChatClientFactory.Attempt> successfulAttempt =
                    new AtomicReference<>();
            try {
                Supplier<ChatClientResponse> retried = withRetries(
                        candidateInvocation(
                                request, candidate, baseline,
                                selectedSummary, successfulAttempt));
                ChatClientResponse response =
                        sessionCoordinator != null && lease != null
                                ? sessionCoordinator.invokeWithinDeadline(
                                        lease, retried)
                                : retried.get();
                return buildPreparedExecution(
                        request, candidate, startedAt,
                        successfulAttempt.get(), response);
            } catch (RagException error) {
                markAttempt(request, successfulAttempt.get(), false);
                throw error;
            } catch (RuntimeException error) {
                markAttempt(request, successfulAttempt.get(), false);
                lastFailure = error;
                recordCandidateFailure(
                        startedAt, index, candidates.size(),
                        candidate.ref(), error, "Durable Chat candidate");
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new RagException(
                ErrorCode.LLM_UNAVAILABLE,
                "No eligible chat model is available");
    }

    /** 单个候选的一次预算化调用：预算预留、上下文规划、模型创建与失败标记。 */
    private Supplier<ChatClientResponse> candidateInvocation(
            ChatCommand request,
            ChatModelRouter.ChatModelCandidate candidate,
            List<Message> baseline,
            String selectedSummary,
            AtomicReference<ModeAwareChatClientFactory.Attempt> successfulAttempt) {
        return () -> {
            if (!request.executionBudget()
                    .tryReserveCandidateAttempt()) {
                throw new RagException(
                        ErrorCode.CHAT_BUDGET_EXHAUSTED,
                        "Chat candidate-attempt budget exhausted");
            }
            ConversationPromptPlan promptPlan = promptPlanner.plan(
                    candidate,
                    request,
                    mandatoryPromptText(request),
                    baseline,
                    selectedSummary,
                    toolCallbacks(request));
            request.executionBudget().recordContextPlan(
                    promptPlan.snapshot());
            ModeAwareChatClientFactory.Attempt created =
                    clientFactory.create(
                            request,
                            candidate,
                            plannedMessages(promptPlan));
            try {
                ChatClientResponse response = invoke(created, request);
                successfulAttempt.set(created);
                return response;
            } catch (RuntimeException e) {
                markAttempt(request, created, false);
                throw e;
            }
        };
    }

    private Supplier<ChatClientResponse> withRetries(
            Supplier<ChatClientResponse> invocation) {
        return () -> retryTemplate != null
                ? retryTemplate.execute(context -> invocation.get())
                : invocation.get();
    }

    private RuntimeException recordCandidateFailure(
            long startedAt,
            int index,
            int candidateCount,
            String candidateRef,
            RuntimeException failure,
            String logLabel) {
        if (metricsService != null) {
            metricsService.recordFailure(System.currentTimeMillis() - startedAt);
        }
        log.warn("{} {}/{} failed ({}): {}",
                logLabel, index + 1, candidateCount, candidateRef,
                failure.getMessage());
        return failure;
    }

    /** execute 路径的成功收尾：提交记忆/历史、摘要压缩与指标标记。 */
    private ChatExecutionResult commitExecutedTurn(
            ChatCommand command,
            ChatModelRouter.ChatModelCandidate candidate,
            ChatSessionCoordinator.LeaseHandle lease,
            long startedAt,
            ModeAwareChatClientFactory.Attempt attempt,
            ChatClientResponse response,
            List<Message> baseline,
            int candidateIndex) {
        if (attempt == null) {
            throw new IllegalStateException(
                    "Chat retry completed without a successful attempt");
        }
        ChatExecutionResult result =
                toResult(command, attempt, response);
        ChatExecutionResult committedResult =
                withPersistenceMetadata(command, result);
        if (sessionCoordinator != null) {
            List<Message> committedMessages =
                    attempt.memory() != null
                            ? committedMemoryMessages(
                                    attempt.memory().get(
                                            command.memoryConversationId()))
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
        ConversationSummaryService.CompactionResult compaction =
                compactSummary(
                        command, candidate, attempt, baseline,
                        committedResult.answer());
        committedResult = withSummaryMetadata(
                committedResult, compaction);
        committedResult = withExecutionBudgetMetadata(
                committedResult, command.executionBudget());
        if (metricsService != null) {
            metricsService.recordSuccess(
                    System.currentTimeMillis() - startedAt,
                tokenCount(committedResult.usage()));
        }
        markAttempt(command, attempt, true);
        if (candidateIndex > 0) {
            log.info("Chat fallback succeeded: requested={}, resolved={}",
                    command.modelRef(), candidate.ref());
        }
        return committedResult;
    }

    /** prepareForOperation 路径的成功收尾：只组装结果，不提交任何持久状态。 */
    private PreparedExecution buildPreparedExecution(
            ChatCommand request,
            ChatModelRouter.ChatModelCandidate candidate,
            long startedAt,
            ModeAwareChatClientFactory.Attempt attempt,
            ChatClientResponse response) {
        if (attempt == null) {
            throw new IllegalStateException(
                    "Chat retry completed without a successful attempt");
        }
        ChatExecutionResult result = withExecutionBudgetMetadata(
                withPersistenceMetadata(
                        request,
                        toResult(request, attempt, response)),
                request.executionBudget());
        List<Message> committedMessages = attempt.memory() != null
                ? committedMemoryMessages(
                        attempt.memory().get(
                                request.memoryConversationId()))
                : List.of();
        markAttempt(request, attempt, true);
        if (metricsService != null) {
            metricsService.recordSuccess(
                    System.currentTimeMillis() - startedAt,
                    tokenCount(result.usage()));
        }
        return new PreparedExecution(
                request,
                result,
                committedMessages,
                serializeDocumentIds(result.sources()),
                attempt,
                candidate);
    }

    /**
     * Resolves the exact, capability-filtered candidate reference chain used
     * by a first durable operation claim. Provider model instances are not
     * part of the persisted snapshot.
     */
    public List<String> resolveCandidateRefs(
            ChatCommand command,
            boolean streaming) {
        validateMode(command);
        return eligibleCandidates(command, streaming).stream()
                .map(ChatModelRouter.ChatModelCandidate::ref)
                .toList();
    }

    /**
     * Persists diagnostics after a durable operation has reached its terminal
     * state. The response snapshot itself only contains the stable retrieval
     * trace reference created for the operation.
     */
    public void persistOperationDiagnostics(ChatCommand command) {
        persistDiagnostics(command);
    }

    /**
     * Completes the non-transactional side effects of a keyed turn after its
     * operation/history/Memory transaction has committed.
     *
     * <p>Conversation summary compaction is deliberately outside the frozen
     * response snapshot. It must still run for keyed requests so adding an
     * idempotency key does not disable the existing long-session behavior.</p>
     */
    public void finalizePreparedOperation(PreparedExecution prepared) {
        if (prepared == null) {
            return;
        }
        if (summaryService != null) {
            try {
                summaryService.compactIfNeeded(
                        prepared.command(),
                        prepared.candidate(),
                        prepared.committedMessages());
            } catch (RuntimeException error) {
                log.warn("Keyed Chat summary compaction failed for session {}: {}",
                        prepared.command().sessionId(), error.getMessage());
            }
        }
        persistDiagnostics(prepared.command());
    }

    public record PreparedExecution(
            ChatCommand command,
            ChatExecutionResult result,
            List<Message> committedMessages,
            String relatedDocumentIds,
            ModeAwareChatClientFactory.Attempt attempt,
            ChatModelRouter.ChatModelCandidate candidate) {
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
        ChatCommand request = command.withExecutionBudget(
                newBudget(command, true));
        return Flux.defer(() -> {
            ChatSessionCoordinator.LeaseHandle lease =
                    sessionCoordinator != null
                            ? sessionCoordinator.acquire(request, true)
                            : null;
            try {
                List<Message> baseline = loadBaseline(request);
                String selectedSummary = summaryText(request);
                List<ChatModelRouter.ChatModelCandidate> candidates =
                        eligibleCandidates(request, true);
                return streamCandidates(
                        request, baseline, selectedSummary, candidates, 0, lease)
                        .doFinally(signal -> {
                            persistDiagnostics(request);
                            if (sessionCoordinator != null) {
                                sessionCoordinator.release(lease);
                            }
                        });
            } catch (RuntimeException e) {
                persistDiagnostics(request);
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
            String selectedSummary,
            List<ChatModelRouter.ChatModelCandidate> candidates,
            int index,
            ChatSessionCoordinator.LeaseHandle lease) {
        ChatModelRouter.ChatModelCandidate candidate = candidates.get(index);
            Flux<ChatEvent> attempt = Flux.defer(() ->
                streamCandidate(command, baseline, selectedSummary, candidate, lease));
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
                        command, baseline, selectedSummary,
                        candidates, index + 1, lease);
            }
            if (!hasEvent && signal.isOnComplete()
                    && index + 1 < candidates.size()) {
                return streamCandidates(
                        command, baseline, selectedSummary, candidates, index + 1, lease);
            }
            return flux;
        });
    }

    private Flux<ChatEvent> streamCandidate(
            ChatCommand command,
            List<Message> baseline,
            String selectedSummary,
            ChatModelRouter.ChatModelCandidate candidate,
            ChatSessionCoordinator.LeaseHandle lease) {
        if (!command.executionBudget().tryReserveCandidateAttempt()) {
            return Flux.error(new RagException(
                    ErrorCode.CHAT_BUDGET_EXHAUSTED,
                    "Chat candidate-attempt budget exhausted"));
        }
        ModeAwareChatClientFactory.Attempt attempt =
                clientFactory.create(
                        command,
                        candidate,
                        plannedBaseline(
                                command, candidate, baseline, selectedSummary));
        AtomicReference<ChatClientResponse> aggregated =
                new AtomicReference<>();
        AtomicReference<ChatClientResponse> lastResponse =
                new AtomicReference<>();
        AtomicBoolean attemptFinished = new AtomicBoolean();
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
                                    responses.filter(this::survivesMessageAggregation)
                                            .doOnNext(response -> {
                                                lastResponse.set(response);
                                                responseEvents(
                                                        attempt.retrievalContext(),
                                                        response)
                                                        .forEach(event ->
                                                                sink.tryEmitNext(event));
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
                    .doOnCancel(() -> {
                        subscription.dispose();
                        if (attemptFinished.compareAndSet(false, true)) {
                            markAttempt(command, attempt, false);
                        }
                    });
        }).doOnComplete(() -> {
            if (attemptFinished.compareAndSet(false, true)) {
                markAttempt(command, attempt, true);
            }
        }).doOnError(error -> {
            if (attemptFinished.compareAndSet(false, true)) {
                markAttempt(command, attempt, false);
            }
        });
    }

    /**
     * 判断一个流式分片能否安全进入 Spring AI 的 {@code MessageAggregator}。
     *
     * <p>{@code MessageAggregator.aggregate} 只挡了 {@code getResult() == null}，
     * 进入该分支后对 {@code getResult().getOutput()} 做了三次裸解引用
     * （读取 text、读取 metadata、读取 toolCalls）。provider 只要吐出一个
     * {@code output} 为 {@code null} 的分片——被内容过滤器清空的块、只带
     * tool-call 的占位块、部分网关的心跳块——聚合器就会在 {@code doOnNext} 里
     * 抛**裸 NullPointerException**。该异常经 {@code then(...)} 直接跳过
     * {@link #completeStreamAttempt}，最终以一个没有错误码的 NPE 结束 SSE 流。
     *
     * <p>所以这里滤掉的正是那个会触发 NPE 的形状，而不是"一切不完整响应"：
     * 一个畸形分片不应该毁掉整条流，其余分片的内容照常送达。
     *
     * <p>特别地，{@code getResult() == null} 的分片**必须放行**：聚合器虽然不从它
     * 取文本，但会采纳它携带的 usage / id / model 元数据，提前丢掉会少计 token。
     * {@code chatResponse == null} 也放行——上游
     * {@code ChatClientMessageAggregator} 自己用 {@code mapNotNull} 处理这一形状。
     *
     * <p>若全部分片都被滤掉，本轮流会像"provider 一个分片都没给"那样以
     * {@code Completed} 收尾。这不是本方法该管的事：空流完成本轮是**既有契约**
     * （候选回退依赖它——空流不算错误，才轮得到下一个候选；
     * 见 {@code ChatExecutionStreamBudgetTest#allEmptyCandidateStreamsCompleteWithoutContentOrError}）。
     * "所有分片都残缺"与"内容为空"无法区分是一项已登记的设计取舍，不是缺陷。
     */
    private boolean survivesMessageAggregation(ChatClientResponse response) {
        var chatResponse = response == null ? null : response.chatResponse();
        if (chatResponse == null || chatResponse.getResult() == null) {
            return true;
        }
        boolean usable = chatResponse.getResult().getOutput() != null;
        if (!usable) {
            log.warn(
                    "丢弃 output 为 null 的流式分片：该形状会让 Spring AI MessageAggregator "
                            + "抛裸 NullPointerException 并中断整条流");
        }
        return usable;
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
                    ? committedMemoryMessages(
                            attempt.memory().get(command.memoryConversationId()))
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
        result = withSummaryMetadata(
                result,
                compactSummary(
                        command, attempt.candidate(), attempt, List.of(),
                        result.answer()));
        result = withExecutionBudgetMetadata(result, command.executionBudget());
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
                result.stepMetrics(),
                result.metadata()));
        return Flux.fromIterable(events);
    }

    private void markAttempt(
            ChatCommand command,
            ModeAwareChatClientFactory.Attempt attempt,
            boolean succeeded) {
        if (command.retrievalTraceSession() == null || attempt == null) {
            return;
        }
        command.retrievalTraceSession().markAttemptFinished(
                attempt.retrievalContext().trace().attemptKey(),
                succeeded,
                attempt.candidate() != null ? attempt.candidate().ref() : null);
    }

    private void persistDiagnostics(ChatCommand command) {
        if (diagnosticsService == null || command.retrievalTraceSession() == null) {
            return;
        }
        diagnosticsService.persist(command.retrievalTraceSession());
    }

    /**
     * 把一个流式分片转成对外事件。
     *
     * <p>前置条件：调用方已用 {@link #survivesMessageAggregation} 滤掉了
     * {@code getOutput() == null} 的分片，因此这里只需再挡 {@code chatResponse}
     * 与 {@code result} 为 {@code null} 的形状（聚合器会放行它们，但它们没有文本可发）。
     */
    private List<ChatEvent> responseEvents(
            AuthorizedRetrievalContext context,
            ChatClientResponse response) {
        List<ChatEvent> events = new ArrayList<>(context.trace().drainToolEvents());
        if (response == null
                || response.chatResponse() == null
                || response.chatResponse().getResult() == null) {
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
        if (chatObservability != null) {
            chatObservability.providerCall();
        }
        ChatClient.ChatClientRequestSpec spec = attempt.client().prompt();
        applyInputMessages(spec, command);
        spec.advisors(advisor -> advisor.param(
                ProjectDocumentRetriever.CONTEXT_KEY,
                attempt.retrievalContext()));
        applyMemoryConversation(spec, command);
        if (command.mode() == ChatMode.AGENT) {
            if (attempt.candidate().model().getDefaultOptions()
                    instanceof ToolCallingChatOptions options) {
                spec.options(options.copy());
            }
            applyAgentTools(
                    spec, command, attempt.candidate(), attempt.retrievalContext());
        }
        return spec.stream().chatClientResponse();
    }

    private ChatClientResponse invoke(
            ModeAwareChatClientFactory.Attempt attempt,
            ChatCommand command) {
        if (chatObservability != null) {
            chatObservability.providerCall();
        }
        ChatClient.ChatClientRequestSpec spec = attempt.client().prompt();
        applyInputMessages(spec, command);
        spec.advisors(advisor -> advisor.param(
                ProjectDocumentRetriever.CONTEXT_KEY,
                attempt.retrievalContext()));
        applyMemoryConversation(spec, command);
        if (command.mode() == ChatMode.AGENT) {
            if (attempt.candidate().model().getDefaultOptions()
                    instanceof ToolCallingChatOptions options) {
                spec.options(options.copy());
            }
            applyAgentTools(
                    spec, command, attempt.candidate(), attempt.retrievalContext());
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

    private void applyMemoryConversation(
            ChatClient.ChatClientRequestSpec spec,
            ChatCommand command) {
        if (command.memoryMode() != MemoryMode.SERVER) {
            return;
        }
        spec.advisors(advisor -> advisor.param(
                ChatMemory.CONVERSATION_ID,
                command.memoryConversationId()));
    }

    private void applyAgentTools(
            ChatClient.ChatClientRequestSpec spec,
            ChatCommand command,
            ChatModelRouter.ChatModelCandidate candidate,
            AuthorizedRetrievalContext retrievalContext) {
        if (toolRegistry != null) {
            spec.toolCallbacks(toolRegistry.callbacks(
                    command.mode(), command.domainId()));
        } else if (jsonRecordSearchTool != null
                && jsonRecordSearchTool.isEnabled()) {
            spec.toolCallbacks(
                    knowledgeSearchTool,
                    jsonRecordSearchTool);
        } else {
            spec.toolCallbacks(knowledgeSearchTool);
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(KnowledgeSearchTool.CONTEXT_KEY, retrievalContext);
        if (runtimeSkillCatalog != null && runtimeSkillCatalog.enabled()) {
            RagChatProperties.SkillProperties skills =
                    ragProperties.getChat().getSkills();
            context.put(
                    RuntimeSkillLoadSession.CONTEXT_KEY,
                    new RuntimeSkillLoadSession(
                            skills.getMaxLoadsPerRequest(),
                            skills.getMaxReferenceReadsPerRequest(),
                            skills.getMaxReferenceBytes()));
        }
        if (ragProperties.getChat().getHttpTools().isEnabled()) {
            long maxResponseBytes = ragProperties.getChat().getHttpTools()
                    .getMaxTotalResponseBytes();
            context.put(
                    HttpToolExecutionState.CONTEXT_KEY,
                    command.executionBudget() != null
                            ? command.executionBudget()
                                    .httpToolExecutionState(maxResponseBytes)
                            : new HttpToolExecutionState(maxResponseBytes));
        }
        if (retrievalContext.executionBudget() != null) {
            context.put(
                    ChatExecutionBudget.CONTEXT_KEY,
                    retrievalContext.executionBudget());
        }
        if (toolRegistry != null) {
            context.putAll(toolRegistry.requestContext(command, candidate));
        }
        spec.toolContext(Map.copyOf(context));
    }

    private List<Message> plannedBaseline(
            ChatCommand command,
            ChatModelRouter.ChatModelCandidate candidate,
            List<Message> baseline,
            String selectedSummary) {
        ConversationPromptPlan plan = promptPlanner.plan(
                candidate,
                command,
                mandatoryPromptText(command),
                baseline,
                selectedSummary,
                toolCallbacks(command));
        if (command.executionBudget() != null) {
            command.executionBudget().recordContextPlan(plan.snapshot());
        }
        return plannedMessages(plan);
    }

    private List<Message> plannedMessages(ConversationPromptPlan plan) {
        List<Message> messages = new ArrayList<>();
        if (plan.selectedSummary() != null
                && !plan.selectedSummary().isBlank()) {
            messages.add(AssistantMessage.builder()
                    .content(plan.selectedSummary())
                    .properties(Map.of(
                            ConversationSummaryService
                                    .SYNTHETIC_SUMMARY_MESSAGE_METADATA_KEY,
                            true))
                    .build());
        }
        messages.addAll(plan.selectedRecentMessages());
        return List.copyOf(messages);
    }

    private List<Message> committedMemoryMessages(List<Message> messages) {
        return ChatMemoryMessageProjector.forPersistence(messages);
    }

    private String summaryText(ChatCommand command) {
        if (summaryService == null
                || command.memoryMode() == MemoryMode.STATELESS) {
            return "";
        }
        return summaryService.promptText(
                summaryService.load(
                        command.principal(), command.sessionId()));
    }

    private ConversationSummaryService.CompactionResult compactSummary(
            ChatCommand command,
            ChatModelRouter.ChatModelCandidate candidate,
            ModeAwareChatClientFactory.Attempt attempt,
            List<Message> baseline,
            String answer) {
        if (summaryService == null) {
            return ConversationSummaryService.CompactionResult.skipped(
                    "summary_service_unavailable");
        }
        List<Message> messages = attempt.memory() != null
                ? attempt.memory().get(command.memoryConversationId())
                : new ArrayList<>(baseline);
        if (attempt.memory() == null) {
            messages.add(new UserMessage(command.message()));
            messages.add(new AssistantMessage(answer == null ? "" : answer));
        }
        return summaryService.compactIfNeeded(command, candidate, messages);
    }

    private ChatExecutionResult withSummaryMetadata(
            ChatExecutionResult result,
            ConversationSummaryService.CompactionResult compaction) {
        if (compaction == null || (!compaction.attempted()
                && !compaction.degraded())) {
            return result;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("attempted", compaction.attempted());
        summary.put("updated", compaction.updated());
        summary.put("degraded", compaction.degraded());
        summary.put("reason", compaction.reason() == null
                ? "" : compaction.reason());
        summary.put("version", compaction.snapshot() == null
                ? 0 : compaction.snapshot().version());
        if (compaction.snapshot() != null
                && compaction.snapshot().summarizedThroughHistoryId() > 0) {
            summary.put("summarizedThroughHistoryId",
                    compaction.snapshot().summarizedThroughHistoryId());
            summary.put("estimatedTokens",
                    compaction.snapshot().estimatedTokens());
        }
        metadata.put("summary", Map.copyOf(summary));
        return new ChatExecutionResult(
                result.answer(), result.sessionId(), result.traceId(),
                result.requestedModel(), result.resolvedModel(), result.mode(),
                result.sources(), result.usage(), result.finishReason(),
                result.stepMetrics(), metadata);
    }

    private ChatExecutionResult withExecutionBudgetMetadata(
            ChatExecutionResult result,
            ChatExecutionBudget budget) {
        if (budget == null) {
            return result;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put("executionBudget", budget.snapshot());
        return new ChatExecutionResult(
                result.answer(), result.sessionId(), result.traceId(),
                result.requestedModel(), result.resolvedModel(), result.mode(),
                result.sources(), result.usage(), result.finishReason(),
                result.stepMetrics(), metadata);
    }

    private List<ToolCallback> toolCallbacks(ChatCommand command) {
        if (command.mode() != ChatMode.AGENT) {
            return List.of();
        }
        if (toolRegistry != null) {
            return toolRegistry.callbacks(command.mode(), command.domainId());
        }
        if (jsonRecordSearchTool != null && jsonRecordSearchTool.isEnabled()) {
            return List.of(knowledgeSearchTool, jsonRecordSearchTool);
        }
        return List.of(knowledgeSearchTool);
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
        List<Map<String, Object>> toolTranscript =
                ToolTranscriptCollector.transcript(
                        response,
                        16,
                        8_000);
        if (toolTranscript.isEmpty() && attempt.memory() != null) {
            toolTranscript = ChatMemoryMessageProjector.toolTranscript(
                    attempt.memory().get(command.memoryConversationId()),
                    16,
                    8_000);
        }
        if (!toolTranscript.isEmpty()) {
            metadata.put(
                    ChatMemoryMessageProjector.TOOL_TRANSCRIPT_METADATA_KEY,
                    toolTranscript);
        }
        if (command.retrievalTraceSession() != null) {
            metadata.put(
                    "retrievalTraceId",
                    command.retrievalTraceSession().traceId().toString());
        }
        if (citationValidator != null
                && ragProperties.getEvaluation().isCitationValidationEnabled()) {
            CitationValidation validation = citationValidator.validate(
                    command.mode(), answer, sources);
            metadata.put("citationValidation", validation);
            if (command.retrievalTraceSession() != null) {
                command.retrievalTraceSession().setCitationValidation(
                        citationMap(validation));
            }
        }
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
        if (command.executionBudget() != null) {
            metadata.put("executionBudget",
                    command.executionBudget().snapshot());
        }
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

    private ChatExecutionBudget newBudget(
            ChatCommand command,
            boolean streaming) {
        RagChatProperties.AgentProperties agent = ragProperties.getChat().getAgent();
        RagChatProperties.ExecutionProperties execution =
                ragProperties.getChat().getExecution();
        int timeoutMs = streaming
                ? ragProperties.getTimeout().getChatStreamMs()
                : ragProperties.getTimeout().getChatAskMs();
        return new ChatExecutionBudget(
                java.time.Instant.now().plusMillis(Math.max(1_000, timeoutMs)),
                execution.getMaxCandidateAttempts(),
                execution.getMaxModelCalls(),
                agent.getMaxToolRounds(),
                agent.getMaxToolCalls(),
                agent.getMaxToolCallsPerName(),
                agent.getMaxToolResultCharactersTotal(),
                java.util.UUID.randomUUID(),
                command.principal().id(),
                command.sessionId(),
                MDC.get(RequestTraceFilter.TRACE_ID_KEY),
                command.mode());
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
        List<ChatHistoryResponse> chronological;
        if (sessionCoordinator != null) {
            chronological = historyRepository.findOwnedBaseline(
                    command.principal(),
                    command.sessionId(),
                    limit);
        } else {
            chronological = new ArrayList<>(historyRepository.findBySessionId(
                    command.sessionId(),
                    limit));
            Collections.reverse(chronological);
        }
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
        if (!command.modelCandidates().isEmpty()) {
            List<ChatModelRouter.ChatModelCandidate> configured = new ArrayList<>();
            boolean resolvedAny = false;
            for (String ref : command.modelCandidates()) {
                try {
                    ChatModelRouter.ChatModelCandidate candidate =
                            modelRouter.resolveCandidateRequired(ref);
                    resolvedAny = true;
                    if (isEligible(candidate, command.mode(), streaming)) {
                        configured.add(candidate);
                    } else {
                        log.warn("Configured chat model candidate is not eligible "
                                        + "for mode {}{}: {}",
                                command.mode(),
                                streaming ? " with streaming" : "",
                                candidate.ref());
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("Configured chat model candidate is unavailable: {}",
                            ref);
                }
            }
            if (!configured.isEmpty()) {
                return List.copyOf(configured);
            }
            if (!resolvedAny) {
                throw new RagException(
                        ErrorCode.SERVICE_UNAVAILABLE,
                        "No configured model candidate is available");
            }
            ErrorCode code = streaming
                    ? ErrorCode.MODEL_STREAMING_UNSUPPORTED
                    : ErrorCode.MODEL_CAPABILITY_UNSUPPORTED;
            throw new RagException(
                    code,
                    "No configured model candidate supports mode "
                            + command.mode()
                            + (streaming ? " with streaming" : ""));
        }
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
        if (command.mode() == ChatMode.AGENT
                && runtimeSkillCatalog != null
                && runtimeSkillCatalog.enabled()) {
            prompt += runtimeSkillCatalog.levelOnePrompt(
                    ragProperties.getChat().getSkills()
                            .getMaxCatalogCharacters());
        }
        if (promptCustomizers.hasCustomizers()) {
            prompt = promptCustomizers.customizeSystemPrompt(
                    prompt,
                    "",
                    command.clientMetadata());
        }
        return prompt;
    }

    private String mandatoryPromptText(ChatCommand command) {
        String systemPrompt = buildSystemPrompt(command);
        StringBuilder result = new StringBuilder(
                systemPrompt == null ? "" : systemPrompt);
        if (command.inputMessages().isEmpty()) {
            result.append("\n").append(customizeUserMessage(command));
            return result.toString();
        }
        int latestUserIndex = -1;
        for (int index = 0; index < command.inputMessages().size(); index++) {
            if (command.inputMessages().get(index).role()
                    == ChatInputMessage.Role.USER) {
                latestUserIndex = index;
            }
        }
        for (int index = 0; index < command.inputMessages().size(); index++) {
            ChatInputMessage input = command.inputMessages().get(index);
            String content = index == latestUserIndex
                    ? customizeUserMessage(command)
                    : input.content();
            result.append("\n[")
                    .append(input.role().name())
                    .append("]\n")
                    .append(content == null ? "" : content);
        }
        return result.toString();
    }

    private String customizeUserMessage(ChatCommand command) {
        if (!promptCustomizers.hasCustomizers()) {
            return command.message();
        }
        return promptCustomizers.customizeUserMessage(
                command.message(),
                command.clientMetadata());
    }

    private void applyInputMessages(
            ChatClient.ChatClientRequestSpec spec,
            ChatCommand command) {
        if (command.inputMessages().isEmpty()) {
            String systemPrompt = buildSystemPrompt(command);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                spec.system(systemPrompt);
            }
            spec.user(customizeUserMessage(command));
            return;
        }

        List<String> systemParts = new ArrayList<>();
        String serverPrompt = buildSystemPrompt(command);
        if (serverPrompt != null && !serverPrompt.isBlank()) {
            systemParts.add(serverPrompt);
        }
        List<Message> conversation = new ArrayList<>();
        int latestUserIndex = -1;
        for (int index = 0; index < command.inputMessages().size(); index++) {
            if (command.inputMessages().get(index).role()
                    == ChatInputMessage.Role.USER) {
                latestUserIndex = index;
            }
        }
        for (int index = 0; index < command.inputMessages().size(); index++) {
            ChatInputMessage input = command.inputMessages().get(index);
            switch (input.role()) {
                case SYSTEM -> systemParts.add(
                        "[client system]\n" + input.content());
                case DEVELOPER -> systemParts.add(
                        "[client developer]\n" + input.content());
                case USER -> conversation.add(new UserMessage(
                        index == latestUserIndex
                                ? customizeUserMessage(command)
                                : input.content()));
                case ASSISTANT -> conversation.add(
                        new AssistantMessage(input.content()));
            }
        }
        if (!systemParts.isEmpty()) {
            conversation.addFirst(new SystemMessage(
                    String.join("\n\n---\n\n", systemParts)));
        }
        spec.messages(List.copyOf(conversation));
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

    private Map<String, Object> citationMap(CitationValidation validation) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", validation.status());
        map.put("availableIds", validation.availableIds());
        map.put("citedIds", validation.citedIds());
        map.put("invalidIds", validation.invalidIds());
        map.put("citedSourceCount", validation.citedSourceCount());
        map.put("sourceCount", validation.sourceCount());
        return map;
    }
}
