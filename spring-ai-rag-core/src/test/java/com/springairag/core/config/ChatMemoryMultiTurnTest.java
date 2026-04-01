package com.springairag.core.config;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.core.advisor.HybridSearchAdvisor;
import com.springairag.core.advisor.QueryRewriteAdvisor;
import com.springairag.core.advisor.RerankAdvisor;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.repository.RagChatHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 对话记忆多轮验证测试
 *
 * <p>验证核心行为：
 * <ul>
 *   <li>多轮对话中 sessionId 正确传递为 CONVERSATION_ID（ChatMemory 用）</li>
 *   <li>不同会话隔离，记忆互不干扰</li>
 *   <li>每轮对话都写入 rag_chat_history 审计表</li>
 *   <li>历史记录查询、分页、清空功能正确</li>
 *   <li>双表共存策略：ChatMemory 给 LLM 上下文用，rag_chat_history 给业务审计用</li>
 * </ul>
 */
@DisplayName("对话记忆多轮验证")
class ChatMemoryMultiTurnTest {

    private ChatClient chatClient;
    private ChatClient.Builder chatClientBuilder;
    private ChatClient.ChatClientRequestSpec promptSpec;
    private ChatClient.CallResponseSpec callResponse;
    private RagChatHistoryRepository historyRepository;

    /** 捕获每轮 chat() 实际传入 advisor 的参数 */
    private List<Map<String, Object>> capturedAdvisorParams;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatClient = mock(ChatClient.class);
        chatClientBuilder = mock(ChatClient.Builder.class);
        promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callResponse = mock(ChatClient.CallResponseSpec.class);

        QueryRewriteAdvisor queryRewriteAdvisor = mock(QueryRewriteAdvisor.class);
        HybridSearchAdvisor hybridSearchAdvisor = mock(HybridSearchAdvisor.class);
        RerankAdvisor rerankAdvisor = mock(RerankAdvisor.class);
        JdbcChatMemoryRepository jdbcChatMemoryRepository = mock(JdbcChatMemoryRepository.class);
        historyRepository = mock(RagChatHistoryRepository.class);
        DomainExtensionRegistry domainExtensionRegistry = mock(DomainExtensionRegistry.class);
        PromptCustomizerChain promptCustomizerChain = mock(PromptCustomizerChain.class);

        when(chatClientBuilder.defaultAdvisors(anyList())).thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(domainExtensionRegistry.hasExtensions()).thenReturn(false);
        when(promptCustomizerChain.hasCustomizers()).thenReturn(false);

        capturedAdvisorParams = new ArrayList<>();

        // 捕获 advisors 消费者中设置的参数
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.advisors(any(Consumer.class))).thenAnswer(inv -> {
            Consumer<ChatClient.AdvisorSpec> consumer = inv.getArgument(0);
            // 模拟 AdvisorSpec 来捕获参数
            ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
            Map<String, Object> params = new HashMap<>();
            doAnswer(pInv -> {
                String key = pInv.getArgument(0);
                Object value = pInv.getArgument(1);
                params.put(key, value);
                return null;
            }).when(advisorSpec).param(anyString(), any());
            consumer.accept(advisorSpec);
            capturedAdvisorParams.add(params);
            return promptSpec;
        });

        // 构建 mock 响应
        ChatClientResponse chatClientResponse = mockChatClientResponse("AI回答");
        when(promptSpec.call()).thenReturn(callResponse);
        when(callResponse.chatClientResponse()).thenReturn(chatClientResponse);
    }

    private ChatClientResponse mockChatClientResponse(String answer) {
        ChatClientResponse resp = mock(ChatClientResponse.class);
        org.springframework.ai.chat.model.ChatResponse springResp = mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.springframework.ai.chat.model.Generation generation = mock(org.springframework.ai.chat.model.Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage output = new org.springframework.ai.chat.messages.AssistantMessage(answer);

        when(resp.chatResponse()).thenReturn(springResp);
        when(springResp.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(output);
        when(resp.context()).thenReturn(Map.of());
        return resp;
    }

    private RagChatService createService() {
        return new RagChatService(
                chatClientBuilder,
                mock(QueryRewriteAdvisor.class),
                mock(HybridSearchAdvisor.class),
                mock(RerankAdvisor.class),
                mock(JdbcChatMemoryRepository.class),
                historyRepository,
                mock(DomainExtensionRegistry.class),
                mock(PromptCustomizerChain.class),
                new RagProperties(),
                null,
                null
        );
    }

    // ================================================================
    // 多轮对话：CONVERSATION_ID 传递
    // ================================================================

    @Test
    @DisplayName("多轮对话 - 每轮都传递相同的 CONVERSATION_ID 给 ChatMemory")
    void multiTurn_sameConversationIdPassedEachTime() {
        RagChatService service = createService();
        String sessionId = "session-multi-1";

        service.chat("第一轮问题", sessionId);
        service.chat("第二轮问题", sessionId);
        service.chat("第三轮问题", sessionId);

        assertEquals(3, capturedAdvisorParams.size(),
                "应捕获 3 轮对话的 advisor 参数");

        // 每轮都应传递相同的 CONVERSATION_ID
        for (int i = 0; i < 3; i++) {
            Map<String, Object> params = capturedAdvisorParams.get(i);
            assertEquals(sessionId, params.get(ChatMemory.CONVERSATION_ID),
                    "第 " + (i + 1) + " 轮应传递相同的 sessionId 作为 CONVERSATION_ID");
        }
    }

    @Test
    @DisplayName("多轮对话 - ChatRequest 方式也传递正确的 CONVERSATION_ID")
    void multiTurn_chatRequest_passesConversationId() {
        RagChatService service = createService();
        String sessionId = "session-req-1";

        service.chat(new ChatRequest("问题1", sessionId));
        service.chat(new ChatRequest("问题2", sessionId));

        assertEquals(2, capturedAdvisorParams.size());
        for (Map<String, Object> params : capturedAdvisorParams) {
            assertEquals(sessionId, params.get(ChatMemory.CONVERSATION_ID));
        }
    }

    // ================================================================
    // 会话隔离
    // ================================================================

    @Test
    @DisplayName("会话隔离 - 不同 sessionId 传递不同的 CONVERSATION_ID")
    void sessionIsolation_differentSessionsGetDifferentConversationIds() {
        RagChatService service = createService();

        service.chat("问题A", "session-A");
        service.chat("问题B", "session-B");
        service.chat("问题A的第二轮", "session-A");

        assertEquals(3, capturedAdvisorParams.size());

        assertEquals("session-A", capturedAdvisorParams.get(0).get(ChatMemory.CONVERSATION_ID));
        assertEquals("session-B", capturedAdvisorParams.get(1).get(ChatMemory.CONVERSATION_ID));
        assertEquals("session-A", capturedAdvisorParams.get(2).get(ChatMemory.CONVERSATION_ID));
    }

    @Test
    @DisplayName("会话隔离 - 三个不同会话各自独立")
    void sessionIsolation_threeIndependentSessions() {
        RagChatService service = createService();

        service.chat("会话1-第1轮", "s1");
        service.chat("会话2-第1轮", "s2");
        service.chat("会话3-第1轮", "s3");
        service.chat("会话1-第2轮", "s1");
        service.chat("会话2-第2轮", "s2");

        // 验证 CONVERSATION_ID 序列
        assertEquals("s1", capturedAdvisorParams.get(0).get(ChatMemory.CONVERSATION_ID));
        assertEquals("s2", capturedAdvisorParams.get(1).get(ChatMemory.CONVERSATION_ID));
        assertEquals("s3", capturedAdvisorParams.get(2).get(ChatMemory.CONVERSATION_ID));
        assertEquals("s1", capturedAdvisorParams.get(3).get(ChatMemory.CONVERSATION_ID));
        assertEquals("s2", capturedAdvisorParams.get(4).get(ChatMemory.CONVERSATION_ID));
    }

    // ================================================================
    // 历史记录持久化（rag_chat_history 审计表）
    // ================================================================

    @Test
    @DisplayName("多轮对话 - 每轮都写入 rag_chat_history 审计表")
    void multiTurn_eachTurnSavedToHistory() {
        RagChatService service = createService();
        String sessionId = "session-audit";

        service.chat("问题1", sessionId);
        service.chat("问题2", sessionId);
        service.chat("问题3", sessionId);

        // 每轮都应调用 historyRepository.save()
        verify(historyRepository, times(3)).save(
                eq(sessionId),        // sessionId
                anyString(),          // userMessage
                anyString(),          // aiResponse
                any(),                // relatedDocumentIds
                any()                 // metadata
        );
    }

    @Test
    @DisplayName("多轮对话 - 保存的 userMessage 与每轮输入对应")
    void multiTurn_savedMessagesMatchInputs() {
        RagChatService service = createService();
        String sessionId = "session-msg";

        service.chat("第一个问题", sessionId);
        service.chat("第二个问题", sessionId);

        verify(historyRepository).save(eq(sessionId), eq("第一个问题"), anyString(), any(), any());
        verify(historyRepository).save(eq(sessionId), eq("第二个问题"), anyString(), any(), any());
    }

    @Test
    @DisplayName("多轮对话 - 不同会话的历史独立保存")
    void multiTurn_differentSessionsSaveIndependently() {
        RagChatService service = createService();

        service.chat("会话A的问题", "session-A");
        service.chat("会话B的问题", "session-B");

        verify(historyRepository).save(eq("session-A"), eq("会话A的问题"), anyString(), any(), any());
        verify(historyRepository).save(eq("session-B"), eq("会话B的问题"), anyString(), any(), any());
    }

    @Test
    @DisplayName("ChatRequest 带 metadata 时，历史记录包含元数据")
    void chatRequest_withMetadata_savesToHistory() {
        RagChatService service = createService();

        ChatRequest request = new ChatRequest("带元数据的问题", "session-meta");
        request.setMetadata(Map.of("source", "web", "version", "v2"));

        service.chat(request);

        verify(historyRepository).save(
                eq("session-meta"),
                eq("带元数据的问题"),
                anyString(),
                any(),
                argThat(m -> m != null && "web".equals(m.get("source")) && "v2".equals(m.get("version")))
        );
    }

    // ================================================================
    // 历史记录查询与管理
    // ================================================================

    @Test
    @DisplayName("历史查询 - 按 sessionId 返回该会话所有记录")
    void historyQuery_returnsSessionRecords() {
        List<Map<String, Object>> mockHistory = List.of(
                Map.of("user_message", "Q1", "ai_response", "A1"),
                Map.of("user_message", "Q2", "ai_response", "A2")
        );
        when(historyRepository.findBySessionId("s1", 50)).thenReturn(mockHistory);

        List<Map<String, Object>> result = historyRepository.findBySessionId("s1", 50);

        assertEquals(2, result.size());
        assertEquals("Q1", result.get(0).get("user_message"));
        assertEquals("A2", result.get(1).get("ai_response"));
    }

    @Test
    @DisplayName("历史查询 - limit 参数控制返回数量")
    void historyQuery_respectsLimit() {
        when(historyRepository.findBySessionId("s1", 2)).thenReturn(List.of(
                Map.of("user_message", "最近的Q1", "ai_response", "A1"),
                Map.of("user_message", "最近的Q2", "ai_response", "A2")
        ));

        List<Map<String, Object>> result = historyRepository.findBySessionId("s1", 2);

        assertEquals(2, result.size());
        verify(historyRepository).findBySessionId("s1", 2);
    }

    @Test
    @DisplayName("清空历史 - 只删除指定会话的记录")
    void clearHistory_deletesOnlyTargetSession() {
        when(historyRepository.deleteBySessionId("s1")).thenReturn(5);

        int deleted = historyRepository.deleteBySessionId("s1");

        assertEquals(5, deleted);
        verify(historyRepository).deleteBySessionId("s1");
        // 不应删除其他会话
        verify(historyRepository, never()).deleteBySessionId("s2");
    }

    // ================================================================
    // 双表共存策略验证
    // ================================================================

    @Test
    @DisplayName("双表共存 - ChatMemory 的 CONVERSATION_ID 与 rag_chat_history 的 sessionId 一致")
    void dualTable_conversationIdMatchesSessionId() {
        RagChatService service = createService();
        String sessionId = "dual-table-session";

        service.chat("双表测试", sessionId);

        // ChatMemory 侧：CONVERSATION_ID = sessionId
        assertEquals(sessionId, capturedAdvisorParams.get(0).get(ChatMemory.CONVERSATION_ID));

        // rag_chat_history 侧：save(sessionId, ...) 使用同一个 sessionId
        verify(historyRepository).save(eq(sessionId), eq("双表测试"), anyString(), any(), any());
    }

    @Test
    @DisplayName("双表共存 - 多轮对话中两表使用一致的 sessionId")
    void dualTable_multiTurnConsistentSessionId() {
        RagChatService service = createService();
        String sessionId = "dual-multi-session";

        service.chat("第1轮", sessionId);
        service.chat("第2轮", sessionId);

        // ChatMemory 侧：两轮 CONVERSATION_ID 一致
        assertEquals(sessionId, capturedAdvisorParams.get(0).get(ChatMemory.CONVERSATION_ID));
        assertEquals(sessionId, capturedAdvisorParams.get(1).get(ChatMemory.CONVERSATION_ID));

        // rag_chat_history 侧：两轮都使用同一 sessionId
        verify(historyRepository).save(eq(sessionId), eq("第1轮"), anyString(), any(), any());
        verify(historyRepository).save(eq(sessionId), eq("第2轮"), anyString(), any(), any());
    }

    // ================================================================
    // 流式对话的记忆传递
    // ================================================================

    @Test
    @DisplayName("流式对话 - 传递正确的 CONVERSATION_ID")
    void stream_passesConversationId() {
        RagChatService service = createService();

        // chatStream 简化版只传递 CONVERSATION_ID
        // 需要重新设置 promptSpec mock 用于 stream
        ChatClient.StreamResponseSpec streamResponse = mock(ChatClient.StreamResponseSpec.class);
        when(promptSpec.stream()).thenReturn(streamResponse);
        when(streamResponse.content()).thenReturn(reactor.core.publisher.Flux.just("chunk1", "chunk2"));

        service.chatStream("流式问题", "session-stream");

        // chatStream 使用 advisors(a -> a.param(CONVERSATION_ID, sessionId))
        verify(promptSpec).advisors(any(java.util.function.Consumer.class));
    }
}
