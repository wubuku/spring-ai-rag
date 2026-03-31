package com.springairag.core.config;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.core.advisor.HybridSearchAdvisor;
import com.springairag.core.advisor.QueryRewriteAdvisor;
import com.springairag.core.advisor.RerankAdvisor;
import com.springairag.core.repository.RagChatHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * RAG 聊天服务
 *
 * <p>通过 ChatClient 调用 LLM，自动经过 Advisor 链（查询改写 → 混合检索 → 重排 → 记忆）。
 * 同时写入 rag_chat_history 业务审计表（双表共存策略）。
 *
 * <p>Advisor 执行顺序（由 getOrder() 决定）：
 * <ol>
 *   <li>客户自定义 Advisor（order < HIGHEST_PRECEDENCE + 10）</li>
 *   <li>QueryRewriteAdvisor（+10）— 查询改写</li>
 *   <li>HybridSearchAdvisor（+20）— 混合检索（结果存入 context attributes）</li>
 *   <li>RerankAdvisor（+30）— 重排序 + 上下文注入</li>
 *   <li>MessageChatMemoryAdvisor — 对话记忆</li>
 *   <li>客户自定义 Advisor（order > LOWEST_PRECEDENCE - 10）</li>
 * </ol>
 */
@Service
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    private final ChatClient chatClient;
    private final RagChatHistoryRepository historyRepository;

    @Value("${rag.memory.max-messages:20}")
    private int maxMessages;

    public RagChatService(
            ChatClient.Builder chatClientBuilder,
            QueryRewriteAdvisor queryRewriteAdvisor,
            HybridSearchAdvisor hybridSearchAdvisor,
            RerankAdvisor rerankAdvisor,
            JdbcChatMemoryRepository jdbcChatMemoryRepository,
            RagChatHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;

        // maxMessages 为 0 时（非 Spring 上下文 / 未配置），默认 20
        int effectiveMaxMessages = maxMessages > 0 ? maxMessages : 20;

        // 使用 JDBC 存储，保留最近 N 条消息的窗口
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(jdbcChatMemoryRepository)
                .maxMessages(effectiveMaxMessages)
                .build();

        // 构建 ChatClient，配置完整的 Advisor 链
        this.chatClient = chatClientBuilder
                .defaultAdvisors(
                        queryRewriteAdvisor,
                        hybridSearchAdvisor,
                        rerankAdvisor,
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();

        log.info("RagChatService initialized with {} max messages, advisors: QueryRewrite → HybridSearch → Rerank → ChatMemory",
                effectiveMaxMessages);
    }

    /**
     * RAG 问答（非流式）
     */
    public String chat(String userMessage, String sessionId) {
        String answer = chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();

        // 保存到业务审计表
        historyRepository.save(sessionId, userMessage, answer);

        return answer;
    }

    /**
     * RAG 问答（非流式，带自定义元数据）
     */
    public String chat(String userMessage, String sessionId, Map<String, Object> metadata) {
        String answer = chatClient.prompt()
                .user(userMessage)
                .advisors(a -> {
                    a.param(ChatMemory.CONVERSATION_ID, sessionId);
                    if (metadata != null) {
                        metadata.forEach(a::param);
                    }
                })
                .call()
                .content();

        // 保存到业务审计表
        historyRepository.save(sessionId, userMessage, answer, null, metadata);

        return answer;
    }

    /**
     * RAG 问答（从 ChatRequest 构建）
     */
    public ChatResponse chat(ChatRequest request) {
        String answer = chat(request.getMessage(), request.getSessionId(), request.getMetadata());

        return ChatResponse.builder()
                .answer(answer)
                .metadata(Map.of("sessionId", request.getSessionId()))
                .build();
    }

    /**
     * RAG 问答（流式，返回 Flux 逐 token 输出）
     *
     * <p>使用示例（Spring Web MVC + SseEmitter）：
     * <pre>
     * SseEmitter emitter = new SseEmitter();
     * ragChatService.chatStream(message, sessionId)
     *     .subscribe(
     *         chunk -> emitter.send(SseEmitter.event().data(chunk)),
     *         emitter::completeWithError,
     *         emitter::complete
     *     );
     * </pre>
     */
    public Flux<String> chatStream(String userMessage, String sessionId) {
        return chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .stream()
                .content();
    }
}
