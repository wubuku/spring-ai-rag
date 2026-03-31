package com.springairag.core.config;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * RAG 聊天服务
 *
 * <p>通过 ChatClient 调用 LLM，自动经过 Advisor 链（查询改写 → 混合检索 → 重排 → 记忆）。
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

    public RagChatService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * RAG 问答（非流式）
     */
    public String chat(String userMessage, String sessionId) {
        return chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }

    /**
     * RAG 问答（非流式，带自定义元数据）
     */
    public String chat(String userMessage, String sessionId, Map<String, Object> metadata) {
        return chatClient.prompt()
                .user(userMessage)
                .advisors(a -> {
                    a.param(ChatMemory.CONVERSATION_ID, sessionId);
                    if (metadata != null) {
                        metadata.forEach(a::param);
                    }
                })
                .call()
                .content();
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
}
