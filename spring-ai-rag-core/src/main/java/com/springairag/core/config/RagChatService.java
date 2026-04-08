package com.springairag.core.config;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.dto.ChatResponse.StepMetricRecord;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.service.DomainRagExtension;
import com.springairag.api.service.RagAdvisorProvider;
import com.springairag.core.advisor.HybridSearchAdvisor;
import com.springairag.core.advisor.QueryRewriteAdvisor;
import com.springairag.core.advisor.RagPipelineMetrics;
import com.springairag.core.advisor.RerankAdvisor;
import com.springairag.core.exception.LlmCircuitOpenException;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.filter.RequestTraceFilter;
import com.springairag.core.metrics.RagMetricsService;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.resilience.LlmCircuitBreaker;
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
            RetryTemplate retryTemplate) {

        this.chatClientBuilder = chatClientBuilder;
        this.chatModelRouter = chatModelRouter;
        this.retryTemplate = retryTemplate;
        this.historyRepository = historyRepository;
        this.domainExtensionRegistry = domainExtensionRegistry;
        this.promptCustomizerChain = promptCustomizerChain;
        this.metricsService = metricsService;

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
        return executeChat(
                request.getMessage(),
                request.getSessionId(),
                request.getDomainId(),
                request.getMetadata(),
                request.getModel()
        );
    }

    /**
     * Core chat execution method, returns full response with sources
     *
     * @param model optional model reference (e.g. "minimax"), null uses default model
     */
    private ChatResponse executeChat(String userMessage, String sessionId, String domainId,
            Map<String, Object> metadata, String model) {
        // Circuit breaker check (fast-fail if OPEN)
        if (circuitBreaker != null && !circuitBreaker.allowCall()) {
            log.warn("LLM circuit breaker is OPEN, rejecting request");
            throw new LlmCircuitOpenException();
        }

        // Resolve model dynamically if specified and router is available (M5: MultiModel integration)
        ChatClient effectiveClient = this.chatClient;
        if (model != null && !model.isBlank() && chatModelRouter != null) {
            ChatModel resolved = chatModelRouter.resolve(model);
            if (resolved != null) {
                effectiveClient = ChatClient.builder(resolved)
                        .defaultAdvisors(sortedAdvisors).build();
                log.info("Routing request to model '{}' via ChatModelRouter", model);
            } else {
                log.warn("Model '{}' could not be resolved, using default", model);
            }
        }

        String systemPrompt = buildSystemPrompt(domainId, metadata);
        String finalMessage = customizeUserMessage(userMessage, metadata);

        // Capture in final variable for lambda (effectiveClient may be reassigned above)
        final ChatClient finalEffectiveClient = effectiveClient;

        long startTime = System.currentTimeMillis();
        // Retryable LLM call: rebuild spec from captured parameters on each retry
        Supplier<LlmCallResult> llmCall = () -> {
            ChatClient.ChatClientRequestSpec s = finalEffectiveClient.prompt();
            if (systemPrompt != null) {
                s.system(systemPrompt);
            }
            s.user(finalMessage);
            s.advisors(buildAdvisorParams(sessionId, domainId, metadata));
            return invokeChatClient(s, System.currentTimeMillis());
        };
        LlmCallResult callResult = invokeWithRetry(llmCall);

        if (metricsService != null) {
            metricsService.recordSuccess(callResult.elapsedMs, 0);
        }
        historyRepository.save(sessionId, userMessage, callResult.answer, null, metadata);

        ChatResponse response = new ChatResponse(callResult.answer);
        response.setTraceId(MDC.get(RequestTraceFilter.TRACE_ID_KEY));
        response.setSources(callResult.sources);
        response.setMetadata(Map.of("sessionId", sessionId));
        response.setStepMetrics(callResult.pipelineMetrics);
        return response;
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
            List<ChatResponse.SourceDocument> sources = extractSources(chatClientResponse);
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
    private record LlmCallResult(String answer, List<ChatResponse.SourceDocument> sources,
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

    /** Builds Advisor parameters (session ID, domain, metadata) */
    private java.util.function.Consumer<ChatClient.AdvisorSpec> buildAdvisorParams(
            String sessionId, String domainId, Map<String, Object> metadata) {
        return a -> {
            a.param(ChatMemory.CONVERSATION_ID, sessionId);
            if (metadata != null) {
                metadata.forEach(a::param);
            }
            if (domainId != null) {
                a.param("domainId", domainId);
            }
        };
    }

    /** Extracts reranked retrieval results from advisor context as citation sources */
    @SuppressWarnings("unchecked")
    private List<ChatResponse.SourceDocument> extractSources(ChatClientResponse chatClientResponse) {
        List<RetrievalResult> reranked = (List<RetrievalResult>) chatClientResponse.context()
                .get(RerankAdvisor.RERANKED_RESULTS_KEY);
        if (reranked == null || reranked.isEmpty()) {
            return null;
        }
        List<ChatResponse.SourceDocument> sources = new ArrayList<>();
        for (RetrievalResult r : reranked) {
            ChatResponse.SourceDocument doc = new ChatResponse.SourceDocument();
            doc.setDocumentId(r.getDocumentId());
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
        return chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .stream()
                .content();
    }

    /**
     * RAG Q&amp;A (streaming, with domain support)
     */
    public Flux<String> chatStream(String userMessage, String sessionId, String domainId) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();

        // Apply domain extension system prompt
        if (domainExtensionRegistry.hasExtensions()) {
            String template = domainExtensionRegistry.getSystemPromptTemplate(domainId);
            if (template != null) {
                spec.system(template);
            }
        }

        spec.user(userMessage);
        spec.advisors(a -> {
            a.param(ChatMemory.CONVERSATION_ID, sessionId);
            if (domainId != null) {
                a.param("domainId", domainId);
            }
        });

        return spec.stream().content();
    }
}
