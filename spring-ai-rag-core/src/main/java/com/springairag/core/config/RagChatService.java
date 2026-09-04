package com.springairag.core.config;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.dto.ChatResponse.StepMetricRecord;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.service.DomainRagExtension;
import com.springairag.api.service.RagAdvisorProvider;
import com.springairag.core.advisor.HybridSearchAdvisor;
import com.springairag.core.advisor.QueryRewriteAdvisor;
import com.springairag.core.advisor.RagPipelineMetrics;
import com.springairag.core.advisor.RerankAdvisor;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatCommandMapper;
import com.springairag.core.chat.ChatEvent;
import com.springairag.core.chat.ChatExecutionBudget;
import com.springairag.core.chat.ChatExecutionResult;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.BudgetedChatModel;
import com.springairag.core.chat.ModeAwareChatClientFactory;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalScopeSummary;
import com.springairag.core.retrieval.RetrievalTraceHeaders;
import com.springairag.core.exception.LlmCircuitOpenException;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.filter.RequestTraceFilter;
import com.springairag.core.metrics.RagMetricsService;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.resilience.LlmCircuitBreaker;
import com.springairag.core.service.CollectionDocumentResolver;
import com.springairag.core.usage.LlmInvocationPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.Ordered;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.UUID;

/**
 * RAG Chat Service
 *
 * <p>Calls LLM via ChatClient, automatically through the Advisor chain
 * (query rewriting → hybrid retrieval → reranking → memory).
 * Also writes to the rag_chat_history audit table (dual-table coexistence strategy).
 *
 * <p>Extension points:
 * <ul>
 *   <li>{@link RagAdvisorProvider} — Custom user-defined Advisor, auto-discovered and injected into the chain</li>
 *   <li>{@link DomainRagExtension} — Domain extension, provides domain-specific system prompts and retrieval config</li>
 *   <li>{@link com.springairag.api.service.PromptCustomizer} — Prompt customizer</li>
 * </ul>
 *
 * <p>Advisor execution order (determined by getOrder()):
 * <ol>
 *   <li>Custom user Advisor (order &lt; +10)</li>
 *   <li>QueryRewriteAdvisor (+10) — query rewriting</li>
 *   <li>Custom user Advisor (+11 ~ +19)</li>
 *   <li>HybridSearchAdvisor (+20) — hybrid retrieval</li>
 *   <li>Custom user Advisor (+21 ~ +29)</li>
 *   <li>RerankAdvisor (+30) — reranking + context injection</li>
 *   <li>Custom user Advisor (+31 ~ +39)</li>
 *   <li>MessageChatMemoryAdvisor — conversation memory</li>
 *   <li>Custom user Advisor (order &gt; +40)</li>
 * </ol>
 */
@Service
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    private final ChatClient chatClient;
    private final ChatClient.Builder chatClientBuilder; // for dynamic model routing
    private final List<Advisor> sortedAdvisors; // for rebuilding ChatClient during dynamic model routing
    private final ChatModelRouter chatModelRouter; // optional, null when not configured
    private final RagChatHistoryRepository historyRepository;
    private final DomainExtensionRegistry domainExtensionRegistry;
    private final PromptCustomizerChain promptCustomizerChain;
    private final RagMetricsService metricsService; // optional, null when actuator is not present
    private final LlmCircuitBreaker circuitBreaker; // optional, null when not enabled
    private final RetryTemplate retryTemplate; // LLM call retry template, optional
    private final CollectionDocumentResolver collectionDocumentResolver; // optional for unit tests
    private final ModeAwareChatClientFactory usageClientFactory;
    private final RagProperties ragProperties;
    private ChatExecutionService modeAwareExecutionService;
    private ChatCommandMapper chatCommandMapper;
    private RetrievalDiagnosticsService diagnosticsService;

    /**
     * Returns the LLM circuit breaker instance (may be null when not enabled)
     */
    public LlmCircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public RagChatService(
            ChatClient.Builder chatClientBuilder,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ChatModelRouter chatModelRouter,
            QueryRewriteAdvisor queryRewriteAdvisor,
            HybridSearchAdvisor hybridSearchAdvisor,
            RerankAdvisor rerankAdvisor,
            JdbcChatMemoryRepository jdbcChatMemoryRepository,
            RagChatHistoryRepository historyRepository,
            DomainExtensionRegistry domainExtensionRegistry,
            PromptCustomizerChain promptCustomizerChain,
            RagProperties ragProperties,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            RagMetricsService metricsService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            List<RagAdvisorProvider> customAdvisorProviders,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            RetryTemplate retryTemplate,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            CollectionDocumentResolver collectionDocumentResolver) {
        this(
                chatClientBuilder,
                chatModelRouter,
                queryRewriteAdvisor,
                hybridSearchAdvisor,
                rerankAdvisor,
                jdbcChatMemoryRepository,
                historyRepository,
                domainExtensionRegistry,
                promptCustomizerChain,
                ragProperties,
                metricsService,
                customAdvisorProviders,
                retryTemplate,
                collectionDocumentResolver,
                null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RagChatService(
            ChatClient.Builder chatClientBuilder,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ChatModelRouter chatModelRouter,
            QueryRewriteAdvisor queryRewriteAdvisor,
            HybridSearchAdvisor hybridSearchAdvisor,
            RerankAdvisor rerankAdvisor,
            JdbcChatMemoryRepository jdbcChatMemoryRepository,
            RagChatHistoryRepository historyRepository,
            DomainExtensionRegistry domainExtensionRegistry,
            PromptCustomizerChain promptCustomizerChain,
            RagProperties ragProperties,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            RagMetricsService metricsService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            List<RagAdvisorProvider> customAdvisorProviders,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            RetryTemplate retryTemplate,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            CollectionDocumentResolver collectionDocumentResolver,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ModeAwareChatClientFactory usageClientFactory) {

        this.chatClientBuilder = chatClientBuilder;
        this.chatModelRouter = chatModelRouter;
        this.retryTemplate = retryTemplate;
        this.historyRepository = historyRepository;
        this.domainExtensionRegistry = domainExtensionRegistry;
        this.promptCustomizerChain = promptCustomizerChain;
        this.metricsService = metricsService;
        this.collectionDocumentResolver = collectionDocumentResolver;
        this.usageClientFactory = usageClientFactory;
        this.ragProperties = ragProperties;

        if (ragProperties.getCircuitBreaker().isEnabled()) {
            this.circuitBreaker = new LlmCircuitBreaker(ragProperties.getCircuitBreaker());
            log.info("LLM circuit breaker enabled: failureRateThreshold={}%, minimumCalls={}, waitDuration={}s, windowSize={}",
                    ragProperties.getCircuitBreaker().getFailureRateThreshold(),
                    ragProperties.getCircuitBreaker().getMinimumNumberOfCalls(),
                    ragProperties.getCircuitBreaker().getWaitDurationInOpenStateSeconds(),
                    ragProperties.getCircuitBreaker().getSlidingWindowSize());
        } else {
            this.circuitBreaker = null;
        }

        int maxMessages = ragProperties.getMemory().getMaxMessages();
        ChatMemory chatMemory = buildChatMemory(jdbcChatMemoryRepository, maxMessages);
        this.sortedAdvisors = buildSortedAdvisors(
                queryRewriteAdvisor, hybridSearchAdvisor, rerankAdvisor,
                customAdvisorProviders, chatMemory);

        this.chatClient = chatClientBuilder.defaultAdvisors(this.sortedAdvisors).build();

        String advisorNames = sortedAdvisors.stream()
                .map(a -> a.getClass().getSimpleName())
                .reduce((a, b) -> a + " → " + b).orElse("none");
        log.info("RagChatService initialized with {} max messages, advisors: {}", maxMessages, advisorNames);
    }

    /**
     * 生产环境启用三模式执行链。保留旧构造器，供兼容 API 和既有手工单测夹具使用。
     */
    @org.springframework.beans.factory.annotation.Autowired
    void configureModeAwareExecution(
            ChatExecutionService executionService,
            ChatCommandMapper commandMapper) {
        this.modeAwareExecutionService = executionService;
        this.chatCommandMapper = commandMapper;
        log.info("RagChatService mode-aware execution enabled");
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureDiagnostics(RetrievalDiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    private ChatMemory buildChatMemory(JdbcChatMemoryRepository repo, int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repo)
                .maxMessages(maxMessages)
                .build();
    }

    private List<Advisor> buildSortedAdvisors(
            QueryRewriteAdvisor queryRewriteAdvisor,
            HybridSearchAdvisor hybridSearchAdvisor,
            RerankAdvisor rerankAdvisor,
            List<RagAdvisorProvider> customProviders,
            ChatMemory chatMemory) {

        List<BaseAdvisor> advisors = new ArrayList<>();
        advisors.add(queryRewriteAdvisor);
        advisors.add(hybridSearchAdvisor);
        advisors.add(rerankAdvisor);

        if (customProviders != null) {
            for (RagAdvisorProvider provider : customProviders) {
                BaseAdvisor advisor = provider.createAdvisor();
                if (advisor != null) {
                    advisors.add(advisor);
                    log.info("Added custom advisor: {} (order={})",
                            provider.getName(), provider.getOrder());
                }
            }
        }

        List<Advisor> sorted = new ArrayList<>(advisors);
        sorted.sort(Comparator.comparingInt(a -> {
            if (a instanceof Ordered) {
                return ((Ordered) a).getOrder();
            }
            return Ordered.LOWEST_PRECEDENCE;
        }));
        sorted.add(MessageChatMemoryAdvisor.builder(chatMemory).build());
        return sorted;
    }

    /**
     * RAG Q&amp;A (non-streaming)
     */
    public String chat(String userMessage, String sessionId) {
        return chat(userMessage, sessionId, null, null);
    }

    /**
     * RAG Q&amp;A (non-streaming, with domain support)
     *
     * @param userMessage user message
     * @param sessionId   session ID
     * @param domainId    domain identifier (null uses default domain)
     * @param metadata    extra metadata
     * @return answer text
     */
    public String chat(String userMessage, String sessionId, String domainId, Map<String, Object> metadata) {
        return executeChat(userMessage, sessionId, domainId, metadata, null).getAnswer();
    }

    /**
     * RAG Q&amp;A (built from ChatRequest), returns full response with citation sources
     */
    public ChatResponse chat(ChatRequest request) {
        return chat(request, resolveLegacyRetrievalScope(request));
    }

    /**
     * 使用 Controller 已完成 ACL 解析的范围执行聊天。
     */
    public ChatResponse chat(ChatRequest request, RetrievalScope scope) {
        return chat(request, scope, null);
    }

    public ChatResponse chat(
            ChatRequest request,
            RetrievalScope scope,
            RetrievalTraceSession session) {
        if (modeAwareExecutionService != null && chatCommandMapper != null) {
            assertCircuitBreakerAllowsCall();
            try {
                ChatCommand command = attachDiagnostics(
                        chatCommandMapper.map(
                                request,
                                scope,
                                ChatPrincipal.fromCurrentRequest()),
                        request,
                        scope,
                        session);
                ChatExecutionResult result =
                        modeAwareExecutionService.execute(command);
                if (circuitBreaker != null) {
                    circuitBreaker.recordSuccess();
                }
                return toChatResponse(result);
            } catch (RuntimeException e) {
                if (circuitBreaker != null) {
                    circuitBreaker.recordFailure();
                }
                throw e;
            }
        }
        return executeChat(
                request.getMessage(),
                request.getSessionId(),
                request.getDomainId(),
                request.getMetadata(),
                request.getModel(),
                scope,
                request.getMaxResults()
        );
    }

    /**
     * Structured streaming entry point used by the SSE controller.
     */
    public Flux<ChatEvent> chatEvents(
            ChatRequest request,
            RetrievalScope scope) {
        return chatEvents(request, scope, null);
    }

    public Flux<ChatEvent> chatEvents(
            ChatRequest request,
            RetrievalScope scope,
            RetrievalTraceSession session) {
        if (modeAwareExecutionService == null || chatCommandMapper == null) {
            return Flux.error(new IllegalStateException(
                    "Mode-aware chat execution is not configured"));
        }
        assertCircuitBreakerAllowsCall();
        ChatCommand command = attachDiagnostics(
                chatCommandMapper.map(
                        request,
                        scope,
                        ChatPrincipal.fromCurrentRequest()),
                request,
                scope,
                session);
        return modeAwareExecutionService.stream(command)
                .doOnComplete(() -> {
                    if (circuitBreaker != null) {
                        circuitBreaker.recordSuccess();
                    }
                })
                .doOnError(error -> {
                    if (circuitBreaker != null) {
                        circuitBreaker.recordFailure();
                    }
                });
    }

    public Flux<ChatEvent> chatEvents(ChatRequest request) {
        return chatEvents(request, resolveLegacyRetrievalScope(request), null);
    }

    private ChatCommand attachDiagnostics(
            ChatCommand command,
            ChatRequest request,
            RetrievalScope scope,
            RetrievalTraceSession session) {
        ChatCommand next = command;
        RetrievalFilters filters = command.retrievalFilters() != null
                ? command.retrievalFilters()
                : RetrievalFilters.none();
        RetrievalTraceSession effective = session;
        if (effective == null
                && diagnosticsService != null
                && diagnosticsService.isEnabled()) {
            effective = diagnosticsService.createSession(
                    command.principal(),
                    RetrievalTraceHeaders.OPERATION_CHAT,
                    command.sessionId());
        }
        if (effective != null) {
            try {
                effective.attachScope(
                        RetrievalScopeSummary.from(
                                request.getCollectionScopeMode(),
                                scope,
                                request.getCollectionKeys(),
                                filters,
                                null),
                        filters);
                next = next.withTraceSession(effective).withFilters(filters);
            } catch (Exception e) {
                log.warn("Retrieval diagnostics failed to attach chat scope: {}", e.getMessage());
            }
        }
        return next;
    }

    private ChatResponse toChatResponse(ChatExecutionResult result) {
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

    /**
     * Core chat execution method, returns full response with sources
     *
     * @param model optional model reference (e.g. "minimax"), null uses default model
     */
    private ChatResponse executeChat(String userMessage, String sessionId, String domainId,
            Map<String, Object> metadata, String model) {
        return executeChat(userMessage, sessionId, domainId, metadata,
                model, RetrievalScope.unscoped(), 0);
    }

    private ChatResponse executeChat(String userMessage, String sessionId, String domainId,
            Map<String, Object> metadata, String model,
            RetrievalScope scope, int maxResults) {
        assertCircuitBreakerAllowsCall();

        String systemPrompt = buildSystemPrompt(domainId, metadata);
        String finalMessage = customizeUserMessage(userMessage, metadata);
        final RetrievalScope finalScope = scope != null ? scope : RetrievalScope.unscoped();

        ChatExecutionBudget executionBudget = newLegacyBudget(sessionId, false);
        List<ChatModelRouter.ChatModelCandidate> candidates =
                resolveLegacyModelCandidates(model);
        List<ChatClient> clients = candidates.isEmpty()
                ? List.<ChatClient>of(this.chatClient)
                : candidates.stream()
                        .map(candidate -> buildLegacyClient(
                                candidate,
                                executionBudget,
                                LlmInvocationPurpose.CHAT))
                        .toList();
        RuntimeException lastFailure = null;

        for (int i = 0; i < clients.size(); i++) {
            ChatClient client = clients.get(i);
            final ChatClient attemptClient = client;
            final int attemptIndex = i;
            final ChatModelRouter.ChatModelCandidate candidate =
                    candidates.isEmpty() ? null : candidates.get(i);
            try {
                Supplier<LlmCallResult> llmCall = () -> {
                    if (!candidates.isEmpty()
                            && !executionBudget.tryReserveCandidateAttempt()) {
                        throw new com.springairag.core.exception.RagException(
                                com.springairag.api.enums.ErrorCode.CHAT_BUDGET_EXHAUSTED,
                                "Chat candidate-attempt budget exhausted");
                    }
                    ChatClient.ChatClientRequestSpec s = attemptClient.prompt();
                    if (systemPrompt != null) {
                        s.system(systemPrompt);
                    }
                    s.user(finalMessage);
                    s.advisors(buildAdvisorParams(
                            sessionId,
                            domainId,
                            metadata,
                            finalScope,
                            maxResults,
                            candidate != null
                                    ? buildLegacyModel(
                                            candidate,
                                            executionBudget,
                                            LlmInvocationPurpose.QUERY_TRANSFORM)
                                    : null));
                    return invokeChatClient(s, System.currentTimeMillis());
                };
                LlmCallResult callResult = invokeWithRetry(llmCall);

                if (metricsService != null) {
                    metricsService.recordSuccess(callResult.elapsedMs, 0);
                }
                historyRepository.save(sessionId, userMessage, callResult.answer, null, metadata);

                if (attemptIndex > 0) {
                    log.info("Chat succeeded on fallback candidate index {} after earlier model failures",
                            attemptIndex);
                }

                ChatResponse response = new ChatResponse(callResult.answer);
                response.setTraceId(MDC.get(RequestTraceFilter.TRACE_ID_KEY));
                response.setSources(callResult.sources);
                response.setMetadata(Map.of("sessionId", sessionId));
                response.setStepMetrics(callResult.pipelineMetrics);
                return response;
            } catch (LlmCircuitOpenException e) {
                // Do not try other models when circuit is open
                throw e;
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("Chat model candidate {}/{} failed after retries: {} — trying next fallback if any",
                        attemptIndex + 1, clients.size(), e.getMessage());
            }
        }

        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new IllegalStateException("No chat model available to handle request");
    }

    /**
     * Applies the provider execution gate without affecting operation replay.
     * Durable Chat adapters call this only after an operation claim exists.
     */
    public void assertCircuitBreakerAllowsCall() {
        if (circuitBreaker != null && !circuitBreaker.allowCall()) {
            log.warn("LLM circuit breaker is OPEN, rejecting request");
            throw new LlmCircuitOpenException();
        }
    }

    /**
     * Resolve ordered ChatClient candidates: preferred model (if any) then configured fallbacks.
     * Always includes at least the default chatClient.
     */
    private List<ChatClient> resolveChatClientCandidates(String model) {
        List<ChatClient> clients = new ArrayList<>();
        if (chatModelRouter != null) {
            List<ChatModel> models = chatModelRouter.orderedCandidates(model);
            for (ChatModel m : models) {
                clients.add(ChatClient.builder(m).defaultAdvisors(sortedAdvisors).build());
            }
        } else if (model != null && !model.isBlank()) {
            log.warn("Model '{}' requested but ChatModelRouter is not available — using default client", model);
        }
        // Always ensure default client is available as last resort
        if (clients.isEmpty()) {
            clients.add(this.chatClient);
        } else if (!clients.contains(this.chatClient)) {
            // Prefer not to compare ChatClient identity; default already covered if router empty
        }
        return clients;
    }

    private List<ChatModelRouter.ChatModelCandidate> resolveLegacyModelCandidates(
            String model) {
        if (chatModelRouter == null) {
            return List.of();
        }
        List<ChatModelRouter.ChatModelCandidate> candidates =
                chatModelRouter.orderedCandidateDescriptors(model);
        if (candidates != null && !candidates.isEmpty()) {
            return candidates;
        }
        List<ChatModel> legacyModels = chatModelRouter.orderedCandidates(model);
        if (legacyModels == null || legacyModels.isEmpty()) {
            return List.of();
        }
        return legacyModels.stream()
                .filter(java.util.Objects::nonNull)
                .map(RagChatService::candidateForLegacyModel)
                .toList();
    }

    private static ChatModelRouter.ChatModelCandidate candidateForLegacyModel(
            ChatModel model) {
        ChatOptions options = model.getDefaultOptions();
        String ref = options != null ? options.getModel() : null;
        return new ChatModelRouter.ChatModelCandidate(
                ref == null || ref.isBlank() ? "UNKNOWN" : ref,
                model,
                MultiModelProperties.ModelCapabilities.defaults());
    }

    private ChatClient buildLegacyClient(
            ChatModelRouter.ChatModelCandidate candidate,
            ChatExecutionBudget budget,
            LlmInvocationPurpose purpose) {
        ChatModel executionModel = buildLegacyModel(candidate, budget, purpose);
        ChatClient.Builder builder = ChatClient.builder(executionModel)
                .defaultAdvisors(sortedAdvisors);
        ChatOptions options = candidate.model().getDefaultOptions();
        if (options != null) {
            builder.defaultOptions(options.copy());
        }
        return builder.build();
    }

    private ChatModel buildLegacyModel(
            ChatModelRouter.ChatModelCandidate candidate,
            ChatExecutionBudget budget,
            LlmInvocationPurpose purpose) {
        if (usageClientFactory == null) {
            return candidate.model();
        }
        return usageClientFactory.budgetedModelFor(candidate, budget, purpose);
    }

    private ChatExecutionBudget newLegacyBudget(
            String sessionId,
            boolean streaming) {
        RagChatProperties.AgentProperties agent =
                ragProperties.getChat().getAgent();
        RagChatProperties.ExecutionProperties execution =
                ragProperties.getChat().getExecution();
        int timeoutMs = streaming
                ? ragProperties.getTimeout().getChatStreamMs()
                : ragProperties.getTimeout().getChatAskMs();
        ChatPrincipal principal = ChatPrincipal.fromCurrentRequest();
        return new ChatExecutionBudget(
                Instant.now().plusMillis(Math.max(1_000, timeoutMs)),
                execution.getMaxCandidateAttempts(),
                execution.getMaxModelCalls(),
                agent.getMaxToolRounds(),
                agent.getMaxToolCalls(),
                agent.getMaxToolCallsPerName(),
                agent.getMaxToolResultCharactersTotal(),
                UUID.randomUUID(),
                safeAttribution(principal.id(), "local:auth-disabled", 128),
                safeAttribution(sessionId, "legacy", 255),
                safeAttribution(
                        MDC.get(RequestTraceFilter.TRACE_ID_KEY),
                        null,
                        128),
                com.springairag.api.enums.ChatMode.KNOWLEDGE);
    }

    private static String safeAttribution(
            String value,
            String fallback,
            int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(ch -> ch < 0x20 || ch > 0x7e)) {
            return fallback;
        }
        return value;
    }

    /**
     * Execute the LLM call with retry if configured.
     * Falls back to direct call if RetryTemplate is not available.
     */
    private LlmCallResult invokeWithRetry(Supplier<LlmCallResult> llmCall) {
        if (retryTemplate != null) {
            try {
                return retryTemplate.execute(status -> {
                    int attempt = status.getRetryCount() + 1;
                    if (attempt > 1) {
                        log.info("LLM call retry attempt {}", attempt);
                    }
                    return llmCall.get();
                });
            } catch (Exception e) { // Retry exhausted: extract root cause and propagate as RuntimeException
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.error("LLM call failed after all retry attempts: {}", cause.getMessage());
                throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(cause);
            }
        }
        return llmCall.get();
    }

    /** LLM call + response extraction; on exception, records circuit breaker/metrics then rethrows */
    private LlmCallResult invokeChatClient(ChatClient.ChatClientRequestSpec spec, long startTime) {
        try {
            ChatClientResponse chatClientResponse = spec.call().chatClientResponse();
            var result = chatClientResponse.chatResponse().getResult();
            if (result == null || result.getOutput() == null) {
                throw new IllegalStateException("LLM returned null result (authentication failure or API error)");
            }
            String answer = result.getOutput().getText();
            List<ChatSource> sources = extractSources(chatClientResponse);
            List<StepMetricRecord> pipelineMetrics = extractPipelineMetrics(chatClientResponse);
            if (circuitBreaker != null) {
                circuitBreaker.recordSuccess();
            }
            return new LlmCallResult(answer, sources, pipelineMetrics, System.currentTimeMillis() - startTime);
        } catch (Exception e) { // Resilience: record metrics + circuit breaker before rethrow
            long elapsed = System.currentTimeMillis() - startTime;
            if (metricsService != null) {
                metricsService.recordFailure(elapsed);
            }
            if (circuitBreaker != null) {
                circuitBreaker.recordFailure();
            }
            throw e;
        }
    }

    /** LLM call result record */
    private record LlmCallResult(String answer, List<ChatSource> sources,
            List<StepMetricRecord> pipelineMetrics, long elapsedMs) {}

    /** Builds domain extension system prompt; returns null if no extensions */
    private String buildSystemPrompt(String domainId, Map<String, Object> metadata) {
        if (!domainExtensionRegistry.hasExtensions()) {
            return null;
        }
        String template = domainExtensionRegistry.getSystemPromptTemplate(domainId);
        if (template == null) {
            return null;
        }
        if (promptCustomizerChain.hasCustomizers()) {
            return promptCustomizerChain.customizeSystemPrompt(
                    template, "", metadata != null ? metadata : Map.of());
        }
        return template;
    }

    /** Applies PromptCustomizer to customize the user message */
    private String customizeUserMessage(String userMessage, Map<String, Object> metadata) {
        if (!promptCustomizerChain.hasCustomizers()) {
            return userMessage;
        }
        return promptCustomizerChain.customizeUserMessage(
                userMessage, metadata != null ? metadata : Map.of());
    }

    /** Builds Advisor parameters (session ID, domain, metadata, retrieval scope) */
    private java.util.function.Consumer<ChatClient.AdvisorSpec> buildAdvisorParams(
            String sessionId, String domainId, Map<String, Object> metadata) {
        return buildAdvisorParams(
                sessionId, domainId, metadata, RetrievalScope.unscoped(), 0);
    }

    private java.util.function.Consumer<ChatClient.AdvisorSpec> buildAdvisorParams(
            String sessionId, String domainId, Map<String, Object> metadata,
            RetrievalScope scope, int maxResults) {
        return buildAdvisorParams(
                sessionId,
                domainId,
                metadata,
                scope,
                maxResults,
                null);
    }

    private java.util.function.Consumer<ChatClient.AdvisorSpec> buildAdvisorParams(
            String sessionId,
            String domainId,
            Map<String, Object> metadata,
            RetrievalScope scope,
            int maxResults,
            ChatModel executionModel) {
        RetrievalScope s = scope != null ? scope : RetrievalScope.unscoped();
        return a -> {
            a.param(ChatMemory.CONVERSATION_ID, sessionId);
            if (metadata != null) {
                metadata.forEach(a::param);
            }
            if (domainId != null) {
                a.param("domainId", domainId);
            }
            a.param(HybridSearchAdvisor.RETRIEVAL_SCOPE_KEY, s);
            if (maxResults > 0) {
                a.param(HybridSearchAdvisor.MAX_RESULTS_KEY, maxResults);
            }
            if (executionModel != null) {
                a.param(
                        QueryRewriteAdvisor.CTX_EXECUTION_MODEL,
                        executionModel);
            }
        };
    }

    /**
     * Resolve collection/document filters from ChatRequest into a retrieval scope.
     */
    RetrievalScope resolveLegacyRetrievalScope(ChatRequest request) {
        if (request == null) {
            return RetrievalScope.unscoped();
        }
        boolean collectionFilter = CollectionDocumentResolver.hasCollectionFilter(request.getCollectionIds());
        boolean documentFilter = request.getDocumentIds() != null && !request.getDocumentIds().isEmpty();
        boolean filterRequested = collectionFilter || documentFilter;

        List<Long> resolved = null;
        if (collectionDocumentResolver != null && (collectionFilter || documentFilter)) {
            resolved = collectionDocumentResolver.resolveDocumentIds(
                    request.getDocumentIds(), request.getCollectionIds());
        } else if (documentFilter) {
            resolved = request.getDocumentIds();
        }

        if (filterRequested && (resolved == null || resolved.isEmpty())) {
            return RetrievalScope.noMatches();
        }
        return RetrievalScope.forDocumentIds(resolved);
    }

    /** Extracts reranked retrieval results from advisor context as citation sources */
    @SuppressWarnings("unchecked")
    private List<ChatSource> extractSources(ChatClientResponse chatClientResponse) {
        List<RetrievalResult> reranked = (List<RetrievalResult>) chatClientResponse.context()
                .get(RerankAdvisor.RERANKED_RESULTS_KEY);
        if (reranked == null || reranked.isEmpty()) {
            return null;
        }
        List<ChatSource> sources = new ArrayList<>();
        for (RetrievalResult r : reranked) {
            ChatSource doc = new ChatSource();
            doc.setDocumentId(r.getDocumentId());
            doc.setTitle(r.getTitle() != null ? r.getTitle() : r.getDocumentId());
            doc.setChunkText(r.getChunkText());
            doc.setScore(r.getScore());
            sources.add(doc);
        }
        return sources;
    }

    /** Extracts Pipeline step metrics from advisor response context */
    private List<StepMetricRecord> extractPipelineMetrics(ChatClientResponse chatClientResponse) {
        RagPipelineMetrics pipelineMetrics = RagPipelineMetrics.get(chatClientResponse.context());
        if (pipelineMetrics == null || pipelineMetrics.getSteps().isEmpty()) {
            return null;
        }
        return pipelineMetrics.getSteps().stream()
                .map(s -> new StepMetricRecord(s.stepName(), s.durationMs(), s.resultCount()))
                .toList();
    }

    /**
     * RAG Q&amp;A (streaming, returns Flux with per-token output)
     */
    public Flux<String> chatStream(String userMessage, String sessionId) {
        return chatStream(userMessage, sessionId, null, null,
                RetrievalScope.unscoped(), 0);
    }

    /**
     * RAG Q&amp;A (streaming, with domain support)
     */
    public Flux<String> chatStream(String userMessage, String sessionId, String domainId) {
        return chatStream(userMessage, sessionId, domainId, null,
                RetrievalScope.unscoped(), 0);
    }

    /**
     * RAG Q&amp;A (streaming from full ChatRequest — collection/model parity with non-stream).
     */
    public Flux<String> chatStream(ChatRequest request) {
        return chatStream(request, resolveLegacyRetrievalScope(request));
    }

    public Flux<String> chatStream(
            ChatRequest request, RetrievalScope scope) {
        return chatStream(
                request.getMessage(),
                request.getSessionId(),
                request.getDomainId(),
                request.getModel(),
                scope,
                request.getMaxResults());
    }

    private Flux<String> chatStream(String userMessage, String sessionId, String domainId,
                                    String model, RetrievalScope scope,
                                    int maxResults) {
        ChatExecutionBudget executionBudget = newLegacyBudget(sessionId, true);
        List<ChatModelRouter.ChatModelCandidate> candidates =
                resolveLegacyModelCandidates(model);
        ChatModelRouter.ChatModelCandidate candidate = candidates.isEmpty()
                ? null
                : candidates.get(0);
        ChatClient effectiveClient = candidates.isEmpty()
                ? this.chatClient
                : buildLegacyClient(
                        candidate,
                        executionBudget,
                        LlmInvocationPurpose.CHAT);

        String systemPrompt = buildSystemPrompt(domainId, null);
        return Flux.defer(() -> {
            if (candidate != null
                    && !executionBudget.tryReserveCandidateAttempt()) {
                return Flux.error(new com.springairag.core.exception.RagException(
                        com.springairag.api.enums.ErrorCode.CHAT_BUDGET_EXHAUSTED,
                        "Chat candidate-attempt budget exhausted"));
            }
            ChatClient.ChatClientRequestSpec spec = effectiveClient.prompt();
            if (systemPrompt != null) {
                spec.system(systemPrompt);
            }
            spec.user(userMessage);
            spec.advisors(buildAdvisorParams(
                    sessionId,
                    domainId,
                    null,
                    scope,
                    maxResults,
                    candidate != null
                            ? buildLegacyModel(
                                    candidate,
                                    executionBudget,
                                    LlmInvocationPurpose.QUERY_TRANSFORM)
                            : null));

            StringBuilder accumulatedAnswer = new StringBuilder();
            return spec.stream().content()
                    .doOnNext(accumulatedAnswer::append)
                    .doOnComplete(() -> historyRepository.save(
                            sessionId,
                            userMessage,
                            accumulatedAnswer.toString(),
                            null,
                            Map.of("streaming", true)));
        });
    }
}
