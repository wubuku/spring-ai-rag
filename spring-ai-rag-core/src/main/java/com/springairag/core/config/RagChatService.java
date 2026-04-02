package com.springairag.core.config;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.service.DomainRagExtension;
import com.springairag.api.service.RagAdvisorProvider;
import com.springairag.core.advisor.HybridSearchAdvisor;
import com.springairag.core.advisor.QueryRewriteAdvisor;
import com.springairag.core.advisor.RerankAdvisor;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.metrics.RagMetricsService;
import com.springairag.core.repository.RagChatHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * RAG 聊天服务
 *
 * <p>通过 ChatClient 调用 LLM，自动经过 Advisor 链（查询改写 → 混合检索 → 重排 → 记忆）。
 * 同时写入 rag_chat_history 业务审计表（双表共存策略）。
 *
 * <p>扩展点支持：
 * <ul>
 *   <li>{@link RagAdvisorProvider} — 客户自定义 Advisor，自动发现并注入链中</li>
 *   <li>{@link DomainRagExtension} — 领域扩展，提供领域特定的系统提示词和检索配置</li>
 *   <li>{@link com.springairag.api.service.PromptCustomizer} — Prompt 定制器</li>
 * </ul>
 *
 * <p>Advisor 执行顺序（由 getOrder() 决定）：
 * <ol>
 *   <li>客户自定义 Advisor（order < +10）</li>
 *   <li>QueryRewriteAdvisor（+10）— 查询改写</li>
 *   <li>客户自定义 Advisor（+11 ~ +19）</li>
 *   <li>HybridSearchAdvisor（+20）— 混合检索</li>
 *   <li>客户自定义 Advisor（+21 ~ +29）</li>
 *   <li>RerankAdvisor（+30）— 重排序 + 上下文注入</li>
 *   <li>客户自定义 Advisor（+31 ~ +39）</li>
 *   <li>MessageChatMemoryAdvisor — 对话记忆</li>
 *   <li>客户自定义 Advisor（order > +40）</li>
 * </ol>
 */
@Service
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    private final ChatClient chatClient;
    private final RagChatHistoryRepository historyRepository;
    private final DomainExtensionRegistry domainExtensionRegistry;
    private final PromptCustomizerChain promptCustomizerChain;
    private final RagMetricsService metricsService; // 可选，未引入 actuator 时为 null

    public RagChatService(
            ChatClient.Builder chatClientBuilder,
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
            List<RagAdvisorProvider> customAdvisorProviders) {

        this.historyRepository = historyRepository;
        this.domainExtensionRegistry = domainExtensionRegistry;
        this.promptCustomizerChain = promptCustomizerChain;
        this.metricsService = metricsService;

        int maxMessages = ragProperties.getMemory().getMaxMessages();

        // 使用 JDBC 存储，保留最近 N 条消息的窗口
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(jdbcChatMemoryRepository)
                .maxMessages(maxMessages)
                .build();

        // 构建 Advisor 列表
        List<BaseAdvisor> advisors = new ArrayList<>();

        // 1. 添加默认 Advisor
        advisors.add(queryRewriteAdvisor);
        advisors.add(hybridSearchAdvisor);
        advisors.add(rerankAdvisor);

        // 2. 添加客户自定义 Advisor（通过 RagAdvisorProvider）
        if (customAdvisorProviders != null && !customAdvisorProviders.isEmpty()) {
            for (RagAdvisorProvider provider : customAdvisorProviders) {
                BaseAdvisor advisor = provider.createAdvisor();
                if (advisor != null) {
                    advisors.add(advisor);
                    log.info("Added custom advisor: {} (order={})", provider.getName(), provider.getOrder());
                }
            }
        }

        // 3. 转换为 Advisor 列表并按 order 排序
        List<Advisor> sortedAdvisors = new ArrayList<>(advisors);
        sortedAdvisors.sort(Comparator.comparingInt(a -> {
            if (a instanceof Ordered) {
                return ((Ordered) a).getOrder();
            }
            return Ordered.LOWEST_PRECEDENCE;
        }));

        // 4. 添加对话记忆 Advisor（放在最后，不受自定义 Advisor 排序影响）
        sortedAdvisors.add(MessageChatMemoryAdvisor.builder(chatMemory).build());

        // 5. 构建 ChatClient
        this.chatClient = chatClientBuilder
                .defaultAdvisors(sortedAdvisors)
                .build();

        String advisorNames = sortedAdvisors.stream()
                .map(a -> a.getClass().getSimpleName())
                .reduce((a, b) -> a + " → " + b)
                .orElse("none");
        log.info("RagChatService initialized with {} max messages, advisors: {}", maxMessages, advisorNames);
    }

    /**
     * RAG 问答（非流式）
     */
    public String chat(String userMessage, String sessionId) {
        return chat(userMessage, sessionId, null, null);
    }

    /**
     * RAG 问答（非流式，带领域支持）
     *
     * @param userMessage 用户消息
     * @param sessionId   会话 ID
     * @param domainId    领域标识（null 则使用默认领域）
     * @param metadata    额外元数据
     * @return 回答文本
     */
    public String chat(String userMessage, String sessionId, String domainId, Map<String, Object> metadata) {
        return executeChat(userMessage, sessionId, domainId, metadata).getAnswer();
    }

    /**
     * RAG 问答（从 ChatRequest 构建），返回含引用来源的完整响应
     */
    public ChatResponse chat(ChatRequest request) {
        return executeChat(
                request.getMessage(),
                request.getSessionId(),
                request.getDomainId(),
                request.getMetadata()
        );
    }

    /**
     * 核心聊天执行方法，返回含 sources 的完整响应
     */
    private ChatResponse executeChat(String userMessage, String sessionId, String domainId, Map<String, Object> metadata) {
        String systemPrompt = buildSystemPrompt(domainId, metadata);
        String finalMessage = customizeUserMessage(userMessage, metadata);

        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (systemPrompt != null) {
            spec.system(systemPrompt);
        }
        spec.user(finalMessage);
        spec.advisors(buildAdvisorParams(sessionId, domainId, metadata));

        long startTime = System.currentTimeMillis();
        String answer;
        List<ChatResponse.SourceDocument> sources = null;
        try {
            ChatClientResponse chatClientResponse = spec.call().chatClientResponse();
            answer = chatClientResponse.chatResponse().getResult().getOutput().getText();
            sources = extractSources(chatClientResponse);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            if (metricsService != null) {
                metricsService.recordFailure(elapsed);
            }
            throw e;
        }
        long elapsed = System.currentTimeMillis() - startTime;
        if (metricsService != null) {
            metricsService.recordSuccess(elapsed, 0);
        }

        historyRepository.save(sessionId, userMessage, answer, null, metadata);

        ChatResponse response = new ChatResponse(answer);
        response.setSources(sources);
        response.setMetadata(Map.of("sessionId", sessionId));
        return response;
    }

    /** 构建领域扩展的系统提示词，无扩展返回 null */
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

    /** 应用 PromptCustomizer 定制用户消息 */
    private String customizeUserMessage(String userMessage, Map<String, Object> metadata) {
        if (!promptCustomizerChain.hasCustomizers()) {
            return userMessage;
        }
        return promptCustomizerChain.customizeUserMessage(
                userMessage, metadata != null ? metadata : Map.of());
    }

    /** 构建 Advisor 参数（会话 ID、领域、元数据） */
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

    /** 从 advisor context 提取重排后的检索结果作为引用来源 */
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

    /**
     * RAG 问答（流式，返回 Flux 逐 token 输出）
     */
    public Flux<String> chatStream(String userMessage, String sessionId) {
        return chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .stream()
                .content();
    }

    /**
     * RAG 问答（流式，带领域支持）
     */
    public Flux<String> chatStream(String userMessage, String sessionId, String domainId) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();

        // 应用领域扩展的系统提示词
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
