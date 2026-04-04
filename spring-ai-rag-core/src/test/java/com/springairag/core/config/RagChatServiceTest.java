package com.springairag.core.config;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.dto.RetrievalResult;
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
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagChatService 单元测试
 */
class RagChatServiceTest {

    private ChatClient chatClient;
    private ChatClient.Builder chatClientBuilder;
    private ChatClient.ChatClientRequestSpec promptSpec;
    private ChatClient.CallResponseSpec callResponse;
    private ChatClient.StreamResponseSpec streamResponse;
    private QueryRewriteAdvisor queryRewriteAdvisor;
    private HybridSearchAdvisor hybridSearchAdvisor;
    private RerankAdvisor rerankAdvisor;
    private JdbcChatMemoryRepository jdbcChatMemoryRepository;
    private RagChatHistoryRepository historyRepository;
    private DomainExtensionRegistry domainExtensionRegistry;
    private PromptCustomizerChain promptCustomizerChain;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatClient = mock(ChatClient.class);
        chatClientBuilder = mock(ChatClient.Builder.class);
        promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callResponse = mock(ChatClient.CallResponseSpec.class);
        streamResponse = mock(ChatClient.StreamResponseSpec.class);
        queryRewriteAdvisor = mock(QueryRewriteAdvisor.class);
        hybridSearchAdvisor = mock(HybridSearchAdvisor.class);
        rerankAdvisor = mock(RerankAdvisor.class);
        jdbcChatMemoryRepository = mock(JdbcChatMemoryRepository.class);
        historyRepository = mock(RagChatHistoryRepository.class);
        domainExtensionRegistry = mock(DomainExtensionRegistry.class);
        promptCustomizerChain = mock(PromptCustomizerChain.class);

        when(chatClientBuilder.defaultAdvisors(anyList())).thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(domainExtensionRegistry.hasExtensions()).thenReturn(false);
        when(promptCustomizerChain.hasCustomizers()).thenReturn(false);
    }

    private RagChatService createService() {
        return new RagChatService(
                chatClientBuilder,
                queryRewriteAdvisor,
                hybridSearchAdvisor,
                rerankAdvisor,
                jdbcChatMemoryRepository,
                historyRepository,
                domainExtensionRegistry,
                promptCustomizerChain,
                new com.springairag.core.config.RagProperties(),
                null,
                null
        );
    }

    /**
     * 创建模拟的 ChatClientResponse，返回指定回答文本和空 context
     */
    private ChatClientResponse mockChatClientResponse(String answer) {
        return mockChatClientResponse(answer, Map.of());
    }

    /**
     * 创建模拟的 ChatClientResponse，返回指定回答文本和指定 context
     */
    private ChatClientResponse mockChatClientResponse(String answer, Map<String, Object> context) {
        ChatClientResponse resp = mock(ChatClientResponse.class);
        org.springframework.ai.chat.model.ChatResponse springResp = mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.springframework.ai.chat.model.Generation generation = mock(org.springframework.ai.chat.model.Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage output = new org.springframework.ai.chat.messages.AssistantMessage(answer);

        when(resp.chatResponse()).thenReturn(springResp);
        when(springResp.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(output);
        when(resp.context()).thenReturn(context);
        return resp;
    }

    @Test
    @DisplayName("构造函数正确构建 ChatClient")
    void constructor_buildsChatClientWithAdvisors() {
        RagChatService service = createService();

        verify(chatClientBuilder, times(1)).defaultAdvisors(anyList());
        verify(chatClientBuilder, times(1)).build();
        assertNotNull(service);
    }

    @Test
    @DisplayName("chat 方法返回回答并保存历史")
    void chat_returnsAnswerAndSavesHistory() {
        RagChatService service = createService();

        ChatClientResponse chatClientResponse = mockChatClientResponse("AI 回答");

        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponse);
        when(callResponse.chatClientResponse()).thenReturn(chatClientResponse);

        String result = service.chat("你好", "session-1");

        assertEquals("AI 回答", result);
        verify(historyRepository, times(1)).save(eq("session-1"), eq("你好"), eq("AI 回答"), any(), any());
    }

    @Test
    @DisplayName("chat 带 metadata 保存时包含元数据")
    void chat_withMetadata_savesWithMetadata() {
        RagChatService service = createService();

        ChatClientResponse chatClientResponse = mockChatClientResponse("回答");

        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponse);
        when(callResponse.chatClientResponse()).thenReturn(chatClientResponse);

        Map<String, Object> metadata = Map.of("source", "test");
        String result = service.chat("问题", "session-1", null, metadata);

        assertEquals("回答", result);
        verify(historyRepository).save(eq("session-1"), eq("问题"), eq("回答"), any(), eq(metadata));
    }

    @Test
    @DisplayName("chat 从 ChatRequest 构建返回含 sources 的 ChatResponse")
    void chat_fromChatRequest_returnsChatResponseWithSources() {
        RagChatService service = createService();

        // 模拟重排后的检索结果
        RetrievalResult r1 = new RetrievalResult();
        r1.setDocumentId("doc-1");
        r1.setChunkText("皮肤类型分类标准");
        r1.setScore(0.95);

        RetrievalResult r2 = new RetrievalResult();
        r2.setDocumentId("doc-2");
        r2.setChunkText("干性皮肤护理指南");
        r2.setScore(0.87);

        Map<String, Object> context = new HashMap<>();
        context.put(RerankAdvisor.RERANKED_RESULTS_KEY, List.of(r1, r2));

        ChatClientResponse chatClientResponse = mockChatClientResponse("根据参考资料，皮肤类型分为...", context);

        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponse);
        when(callResponse.chatClientResponse()).thenReturn(chatClientResponse);

        ChatRequest request = new ChatRequest("皮肤有哪些类型", "session-2");
        ChatResponse response = service.chat(request);

        assertEquals("根据参考资料，皮肤类型分为...", response.getAnswer());
        assertEquals("session-2", response.getMetadata().get("sessionId"));

        // 验证 sources 被正确填充
        assertNotNull(response.getSources());
        assertEquals(2, response.getSources().size());
        assertEquals("doc-1", response.getSources().get(0).getDocumentId());
        assertEquals("皮肤类型分类标准", response.getSources().get(0).getChunkText());
        assertEquals(0.95, response.getSources().get(0).getScore(), 0.001);
        assertEquals("doc-2", response.getSources().get(1).getDocumentId());
    }

    @Test
    @DisplayName("chat 无检索结果时 sources 为 null")
    void chat_withoutRetrievalResults_sourcesIsNull() {
        RagChatService service = createService();

        // 空 context（没有 RERANKED_RESULTS_KEY）
        ChatClientResponse chatClientResponse = mockChatClientResponse("直接回答", Map.of());

        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponse);
        when(callResponse.chatClientResponse()).thenReturn(chatClientResponse);

        ChatRequest request = new ChatRequest("简单问题", "session-3");
        ChatResponse response = service.chat(request);

        assertEquals("直接回答", response.getAnswer());
        assertNull(response.getSources());
    }

    @Test
    @DisplayName("chatStream 返回 Flux")
    void chatStream_returnsFlux() {
        RagChatService service = createService();

        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamResponse);
        when(streamResponse.content()).thenReturn(Flux.just("Hello", " ", "World"));

        Flux<String> result = service.chatStream("你好", "session-1");

        assertNotNull(result);
        String joined = result.collectList().block().stream().reduce("", String::concat);
        assertEquals("Hello World", joined);
    }

    @Test
    @DisplayName("有自定义 RagAdvisorProvider 时也构建成功")
    void constructor_withCustomAdvisors_buildsSuccessfully() {
        com.springairag.api.service.RagAdvisorProvider mockProvider = mock(com.springairag.api.service.RagAdvisorProvider.class);
        BaseAdvisor mockAdvisor = mock(BaseAdvisor.class);
        when(mockProvider.getName()).thenReturn("CustomAdvisor");
        when(mockProvider.getOrder()).thenReturn(5);
        when(mockProvider.createAdvisor()).thenReturn(mockAdvisor);

        RagChatService service = new RagChatService(
                chatClientBuilder,
                queryRewriteAdvisor,
                hybridSearchAdvisor,
                rerankAdvisor,
                jdbcChatMemoryRepository,
                historyRepository,
                domainExtensionRegistry,
                promptCustomizerChain,
                new com.springairag.core.config.RagProperties(),
                null,
                List.of(mockProvider)
        );

        assertNotNull(service);
        verify(chatClientBuilder).defaultAdvisors(anyList());
    }

    @Test
    @DisplayName("chat 异常时记录失败指标")
    void chat_exception_recordsFailureMetric() {
        RagChatService service = createService();

        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponse);
        when(callResponse.chatClientResponse()).thenThrow(new RuntimeException("LLM 超时"));

        assertThrows(RuntimeException.class, () -> service.chat("问题", "session-err"));
        // 不应保存历史
        verify(historyRepository, never()).save(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("buildSystemPrompt 有扩展无定制器时使用模板作为系统提示词")
    void buildSystemPrompt_withExtensionButNoCustomizer_setsSystemPrompt() {
        // 覆盖 BeforeEach 的默认值
        when(domainExtensionRegistry.hasExtensions()).thenReturn(true);
        when(domainExtensionRegistry.getSystemPromptTemplate(isNull())).thenReturn("领域系统提示词模板");
        when(promptCustomizerChain.hasCustomizers()).thenReturn(false);

        RagChatService service = createService();
        ChatClientResponse chatClientResponse = mockChatClientResponse("回答");

        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponse);
        when(callResponse.chatClientResponse()).thenReturn(chatClientResponse);

        ChatRequest request = new ChatRequest("皮肤类型有哪些", "session-ext");
        ChatResponse response = service.chat(request);

        assertEquals("回答", response.getAnswer());
        // 验证 spec.system() 被调用，传入了领域模板
        verify(promptSpec).system(eq("领域系统提示词模板"));
    }

    @Test
    @DisplayName("buildSystemPrompt 有扩展且有定制器时使用定制后的系统提示词")
    void buildSystemPrompt_withExtensionAndCustomizer_setsCustomizedSystemPrompt() {
        when(domainExtensionRegistry.hasExtensions()).thenReturn(true);
        when(domainExtensionRegistry.getSystemPromptTemplate(isNull())).thenReturn("原始模板");
        when(promptCustomizerChain.hasCustomizers()).thenReturn(true);
        when(promptCustomizerChain.customizeSystemPrompt(eq("原始模板"), eq(""), any())).thenReturn("定制后的系统提示词");

        RagChatService service = createService();
        ChatClientResponse chatClientResponse = mockChatClientResponse("回答");

        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponse);
        when(callResponse.chatClientResponse()).thenReturn(chatClientResponse);

        ChatResponse response = service.chat(new ChatRequest("问题", "session-custom"));

        assertEquals("回答", response.getAnswer());
        // 验证 spec.system() 被调用，传入的是定制后的提示词
        verify(promptSpec).system(eq("定制后的系统提示词"));
        // 验证定制器被调用
        verify(promptCustomizerChain).customizeSystemPrompt(eq("原始模板"), eq(""), any());
    }

    @Test
    @DisplayName("customizeUserMessage 有定制器时使用定制后的用户消息")
    void customizeUserMessage_withCustomizer_setsCustomizedUserMessage() {
        when(promptCustomizerChain.hasCustomizers()).thenReturn(true);
        when(promptCustomizerChain.customizeUserMessage(eq("原始用户消息"), any())).thenReturn("【定制】原始用户消息");

        RagChatService service = createService();
        ChatClientResponse chatClientResponse = mockChatClientResponse("回答");

        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponse);
        when(callResponse.chatClientResponse()).thenReturn(chatClientResponse);

        ChatResponse response = service.chat(new ChatRequest("原始用户消息", "session-user-custom"));

        assertEquals("回答", response.getAnswer());
        // 验证 spec.user() 被调用，传入的是定制后的用户消息
        verify(promptSpec).user(eq("【定制】原始用户消息"));
    }
}
