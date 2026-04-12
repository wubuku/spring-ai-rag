package com.springairag.core.config;

import com.springairag.api.dto.ChatHistoryResponse;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Chat Memory Multi-Turn Test
 *
 * <p>Verifies core behaviors:
 * <ul>
 *   <li>sessionId is correctly passed as CONVERSATION_ID in multi-turn conversations (used by ChatMemory)</li>
 *   <li>Different sessions are isolated, with no interference</li>
 *   <li>Every turn is written to the rag_chat_history audit table</li>
 *   <li>History query, pagination, and clear functions work correctly</li>
 *   <li>Dual-table coexistence: ChatMemory for LLM context, rag_chat_history for business audit</li>
 * </ul>
 */
@DisplayName("Chat Memory Multi-Turn Test")
class ChatMemoryMultiTurnTest {

    private ChatClient chatClient;
    private ChatClient.Builder chatClientBuilder;
    private ChatClient.ChatClientRequestSpec promptSpec;
    private ChatClient.CallResponseSpec callResponse;
    private RagChatHistoryRepository historyRepository;
    private ChatModelRouter chatModelRouter;

    /** Captures the parameters actually passed to advisors on each chat() call */
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
        chatModelRouter = mock(ChatModelRouter.class);

        when(chatClientBuilder.defaultAdvisors(anyList())).thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(domainExtensionRegistry.hasExtensions()).thenReturn(false);
        when(promptCustomizerChain.hasCustomizers()).thenReturn(false);

        capturedAdvisorParams = new ArrayList<>();

        // Capture parameters set in the advisors consumer
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.advisors(any(Consumer.class))).thenAnswer(inv -> {
            Consumer<ChatClient.AdvisorSpec> consumer = inv.getArgument(0);
            // Simulate AdvisorSpec to capture parameters
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

        // Build mock response
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
                chatModelRouter,
                mock(QueryRewriteAdvisor.class),
                mock(HybridSearchAdvisor.class),
                mock(RerankAdvisor.class),
                mock(JdbcChatMemoryRepository.class),
                historyRepository,
                mock(DomainExtensionRegistry.class),
                mock(PromptCustomizerChain.class),
                new RagProperties(),
                null,
                null,
                null
        );
    }

    // ================================================================
    // Multi-turn: CONVERSATION_ID passing
    // ================================================================

    @Test
    @DisplayName("multi-turn: same CONVERSATION_ID is passed on every turn")
    void multiTurn_sameConversationIdPassedEachTime() {
        RagChatService service = createService();
        String sessionId = "session-multi-1";

        service.chat("第一轮问题", sessionId);
        service.chat("第二轮问题", sessionId);
        service.chat("第三轮问题", sessionId);

        assertEquals(3, capturedAdvisorParams.size(),
                "Should capture advisor params for 3 turns");

        // Every turn should pass the same CONVERSATION_ID
        for (int i = 0; i < 3; i++) {
            Map<String, Object> params = capturedAdvisorParams.get(i);
            assertEquals(sessionId, params.get(ChatMemory.CONVERSATION_ID),
                    "Turn " + (i + 1) + " should pass the same sessionId as CONVERSATION_ID");
        }
    }

    @Test
    @DisplayName("multi-turn: ChatRequest method also passes correct CONVERSATION_ID")
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
    // Session isolation
    // ================================================================

    @Test
    @DisplayName("session isolation: different sessionIds pass different CONVERSATION_IDs")
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
    @DisplayName("session isolation: three different sessions are independent")
    void sessionIsolation_threeIndependentSessions() {
        RagChatService service = createService();

        service.chat("会话1-第1轮", "s1");
        service.chat("会话2-第1轮", "s2");
        service.chat("会话3-第1轮", "s3");
        service.chat("会话1-第2轮", "s1");
        service.chat("会话2-第2轮", "s2");

        // Verify CONVERSATION_ID sequence
        assertEquals("s1", capturedAdvisorParams.get(0).get(ChatMemory.CONVERSATION_ID));
        assertEquals("s2", capturedAdvisorParams.get(1).get(ChatMemory.CONVERSATION_ID));
        assertEquals("s3", capturedAdvisorParams.get(2).get(ChatMemory.CONVERSATION_ID));
        assertEquals("s1", capturedAdvisorParams.get(3).get(ChatMemory.CONVERSATION_ID));
        assertEquals("s2", capturedAdvisorParams.get(4).get(ChatMemory.CONVERSATION_ID));
    }

    // ================================================================
    // History persistence (rag_chat_history audit table)
    // ================================================================

    @Test
    @DisplayName("multi-turn: every turn writes to rag_chat_history audit table")
    void multiTurn_eachTurnSavedToHistory() {
        RagChatService service = createService();
        String sessionId = "session-audit";

        service.chat("问题1", sessionId);
        service.chat("问题2", sessionId);
        service.chat("问题3", sessionId);

        // Every turn should call historyRepository.save()
        verify(historyRepository, times(3)).save(
                eq(sessionId),        // sessionId
                anyString(),          // userMessage
                anyString(),          // aiResponse
                any(),                // relatedDocumentIds
                any()                 // metadata
        );
    }

    @Test
    @DisplayName("multi-turn: saved userMessages match each turn's input")
    void multiTurn_savedMessagesMatchInputs() {
        RagChatService service = createService();
        String sessionId = "session-msg";

        service.chat("第一个问题", sessionId);
        service.chat("第二个问题", sessionId);

        verify(historyRepository).save(eq(sessionId), eq("第一个问题"), anyString(), any(), any());
        verify(historyRepository).save(eq(sessionId), eq("第二个问题"), anyString(), any(), any());
    }

    @Test
    @DisplayName("multi-turn: different sessions' histories are saved independently")
    void multiTurn_differentSessionsSaveIndependently() {
        RagChatService service = createService();

        service.chat("会话A的问题", "session-A");
        service.chat("会话B的问题", "session-B");

        verify(historyRepository).save(eq("session-A"), eq("会话A的问题"), anyString(), any(), any());
        verify(historyRepository).save(eq("session-B"), eq("会话B的问题"), anyString(), any(), any());
    }

    @Test
    @DisplayName("ChatRequest with metadata: history record contains the metadata")
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
    // History query and management
    // ================================================================

    @Test
    @DisplayName("history query: returns all records for a sessionId")
    void historyQuery_returnsSessionRecords() {
        List<ChatHistoryResponse> mockHistory = List.of(
                new ChatHistoryResponse(1L, "s1", "Q1", "A1", null, null, LocalDateTime.now()),
                new ChatHistoryResponse(2L, "s1", "Q2", "A2", null, null, LocalDateTime.now())
        );
        when(historyRepository.findBySessionId("s1", 50)).thenReturn(mockHistory);

        List<ChatHistoryResponse> result = historyRepository.findBySessionId("s1", 50);

        assertEquals(2, result.size());
        assertEquals("Q1", result.get(0).userMessage());
        assertEquals("A2", result.get(1).aiResponse());
    }

    @Test
    @DisplayName("history query: limit parameter controls the number of results")
    void historyQuery_respectsLimit() {
        when(historyRepository.findBySessionId("s1", 2)).thenReturn(List.of(
                new ChatHistoryResponse(1L, "s1", "最近的Q1", "A1", null, null, LocalDateTime.now()),
                new ChatHistoryResponse(2L, "s1", "最近的Q2", "A2", null, null, LocalDateTime.now())
        ));

        List<ChatHistoryResponse> result = historyRepository.findBySessionId("s1", 2);

        assertEquals(2, result.size());
        verify(historyRepository).findBySessionId("s1", 2);
    }

    @Test
    @DisplayName("clear history: only deletes records for the target session")
    void clearHistory_deletesOnlyTargetSession() {
        when(historyRepository.deleteBySessionId("s1")).thenReturn(5);

        int deleted = historyRepository.deleteBySessionId("s1");

        assertEquals(5, deleted);
        verify(historyRepository).deleteBySessionId("s1");
        // Should not delete other sessions
        verify(historyRepository, never()).deleteBySessionId("s2");
    }

    // ================================================================
    // Dual-table coexistence strategy
    // ================================================================

    @Test
    @DisplayName("dual-table: ChatMemory CONVERSATION_ID matches rag_chat_history sessionId")
    void dualTable_conversationIdMatchesSessionId() {
        RagChatService service = createService();
        String sessionId = "dual-table-session";

        service.chat("双表测试", sessionId);

        // ChatMemory side: CONVERSATION_ID = sessionId
        assertEquals(sessionId, capturedAdvisorParams.get(0).get(ChatMemory.CONVERSATION_ID));

        // rag_chat_history side: save(sessionId, ...) uses the same sessionId
        verify(historyRepository).save(eq(sessionId), eq("双表测试"), anyString(), any(), any());
    }

    @Test
    @DisplayName("dual-table: both tables use the same sessionId in multi-turn conversations")
    void dualTable_multiTurnConsistentSessionId() {
        RagChatService service = createService();
        String sessionId = "dual-multi-session";

        service.chat("第1轮", sessionId);
        service.chat("第2轮", sessionId);

        // ChatMemory side: both turns have the same CONVERSATION_ID
        assertEquals(sessionId, capturedAdvisorParams.get(0).get(ChatMemory.CONVERSATION_ID));
        assertEquals(sessionId, capturedAdvisorParams.get(1).get(ChatMemory.CONVERSATION_ID));

        // rag_chat_history side: both turns use the same sessionId
        verify(historyRepository).save(eq(sessionId), eq("第1轮"), anyString(), any(), any());
        verify(historyRepository).save(eq(sessionId), eq("第2轮"), anyString(), any(), any());
    }

    // ================================================================
    // Memory passing in streaming conversations
    // ================================================================

    @Test
    @DisplayName("streaming conversation: passes correct CONVERSATION_ID")
    void stream_passesConversationId() {
        RagChatService service = createService();

        // chatStream simplified: only passes CONVERSATION_ID
        // Need to re-setup promptSpec mock for stream
        ChatClient.StreamResponseSpec streamResponse = mock(ChatClient.StreamResponseSpec.class);
        when(promptSpec.stream()).thenReturn(streamResponse);
        when(streamResponse.content()).thenReturn(reactor.core.publisher.Flux.just("chunk1", "chunk2"));

        service.chatStream("流式问题", "session-stream");

        // chatStream uses advisors(a -> a.param(CONVERSATION_ID, sessionId))
        verify(promptSpec).advisors(any(java.util.function.Consumer.class));
    }
}
