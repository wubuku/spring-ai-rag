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
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import reactor.core.publisher.Flux;

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
                20,
                null,
                null
        );
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

        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponse);
        when(callResponse.content()).thenReturn("AI 回答");

        String result = service.chat("你好", "session-1");

        assertEquals("AI 回答", result);
        verify(historyRepository, times(1)).save(eq("session-1"), eq("你好"), eq("AI 回答"), any(), any());
    }

    @Test
    @DisplayName("chat 带 metadata 保存时包含元数据")
    void chat_withMetadata_savesWithMetadata() {
        RagChatService service = createService();

        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponse);
        when(callResponse.content()).thenReturn("回答");

        Map<String, Object> metadata = Map.of("source", "test");
        String result = service.chat("问题", "session-1", null, metadata);

        assertEquals("回答", result);
        verify(historyRepository).save(eq("session-1"), eq("问题"), eq("回答"), any(), eq(metadata));
    }

    @Test
    @DisplayName("chat 从 ChatRequest 构建返回 ChatResponse")
    void chat_fromChatRequest_returnsChatResponse() {
        RagChatService service = createService();

        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponse);
        when(callResponse.content()).thenReturn("回答内容");

        ChatRequest request = new ChatRequest("测试问题", "session-2");
        ChatResponse response = service.chat(request);

        assertEquals("回答内容", response.getAnswer());
        assertEquals("session-2", response.getMetadata().get("sessionId"));
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
        org.springframework.ai.chat.client.advisor.api.BaseAdvisor mockAdvisor = mock(org.springframework.ai.chat.client.advisor.api.BaseAdvisor.class);
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
                20,
                null,
                List.of(mockProvider)
        );

        assertNotNull(service);
        verify(chatClientBuilder).defaultAdvisors(anyList());
    }
}
