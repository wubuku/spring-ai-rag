package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * eligibleCandidates 候选资格矩阵（Batch 332）：显式 modelCandidates
 * 列表的逐候选过滤与兜底错误、不可用候选的降级、modelRef 显式校
 * 验（工具调用/流式能力），AGENT 与 KNOWLEDGE 模式差异。
 */
class ChatExecutionEligibleCandidatesTest {

    private ChatModelRouter modelRouter;
    private ModeAwareChatClientFactory clientFactory;
    private RagChatHistoryRepository historyRepository;

    @BeforeEach
    void setUp() {
        modelRouter = mock(ChatModelRouter.class);
        clientFactory = mock(ModeAwareChatClientFactory.class);
        historyRepository = mock(RagChatHistoryRepository.class);
        when(historyRepository.findBySessionId(anyString(),
                any(Integer.class))).thenReturn(List.of());
    }

    // ── 候选工厂 ────────────────────────────────────────────────────

    private ChatModelRouter.ChatModelCandidate eligibleAgent(String ref) {
        ChatModel model = mock(ChatModel.class);
        ToolCallingChatOptions options = mock(ToolCallingChatOptions.class);
        when(model.getDefaultOptions()).thenReturn(options);
        when(options.copy()).thenReturn(options);
        return new ChatModelRouter.ChatModelCandidate(
                ref, model,
                new MultiModelProperties.ModelCapabilities(true, true));
    }

    /** 流式可用但不支持工具调用的候选：AGENT 不合格、KNOWLEDGE 合格。 */
    private ChatModelRouter.ChatModelCandidate noToolCallingAgent(String ref) {
        ChatModel model = mock(ChatModel.class);
        return new ChatModelRouter.ChatModelCandidate(
                ref, model,
                new MultiModelProperties.ModelCapabilities(true, false));
    }

    /** 不支持流式的候选：流式请求下不合格。 */
    private ChatModelRouter.ChatModelCandidate nonStreaming(String ref) {
        return new ChatModelRouter.ChatModelCandidate(
                ref, mock(ChatModel.class),
                new MultiModelProperties.ModelCapabilities(false, true));
    }

    private ChatCommand command(
            ChatMode mode, String modelRef, List<String> modelCandidates) {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "问题", "session-1", principal,
                principal.memoryConversationId("session-1"),
                mode, MemoryMode.SERVER, modelRef, null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of(), java.util.List.of(), modelCandidates,
                null, null, null);
    }

    private ChatExecutionService service() {
        PromptCustomizerChain promptCustomizers =
                mock(PromptCustomizerChain.class);
        when(promptCustomizers.hasCustomizers()).thenReturn(false);
        return new ChatExecutionService(
                modelRouter,
                clientFactory,
                mock(KnowledgeSearchTool.class),
                historyRepository,
                mock(DomainExtensionRegistry.class),
                promptCustomizers,
                mock(RetrievalDocumentMapper.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new RagProperties(),
                null,
                null);
    }

    @SuppressWarnings("unchecked")
    private void attempt(ChatModelRouter.ChatModelCandidate candidate) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream =
                mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class)))
                .thenReturn(spec);
        when(spec.options(any(ToolCallingChatOptions.class))).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.toolCallbacks(any(ToolCallback[].class))).thenReturn(spec);
        when(spec.toolCallbacks(any(java.util.List.class))).thenReturn(spec);
        when(spec.toolCallbacks(any(ToolCallback.class))).thenReturn(spec);
        when(spec.toolContext(any(Map.class))).thenReturn(spec);
        when(spec.stream()).thenReturn(stream);
        when(stream.chatClientResponse()).thenReturn(Flux.just(
                new ChatClientResponse(
                        new org.springframework.ai.chat.model.ChatResponse(
                                List.of(new Generation(
                                        new AssistantMessage("ok"))),
                                ChatResponseMetadata.builder().build()),
                        Map.of())));
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, candidate,
                        new AuthorizedRetrievalContext(
                                RetrievalScope.unscoped(),
                                new RetrievalOptions(5, 0.25, true, true,
                                        0.55, 0.45),
                                new RetrievalTraceCollector(),
                                "session-1",
                                ChatPrincipal.local()),
                        null));
    }

    // ── 显式 modelCandidates 列表 ───────────────────────────────────

    @Test
    void configuredCandidatesFallThroughToEligibleOne() {
        ChatModelRouter.ChatModelCandidate good = eligibleAgent("good");
        when(modelRouter.resolveCandidateRequired("no-tools"))
                .thenReturn(noToolCallingAgent("no-tools"));
        when(modelRouter.resolveCandidateRequired("good")).thenReturn(good);
        attempt(good);

        List<ChatEvent> events = service()
                .stream(command(ChatMode.AGENT, null,
                        List.of("no-tools", "good")))
                .collectList()
                .block();

        assertNotNull(events);
        // 不合格候选被跳过，合格候选胜出。
        verify(clientFactory).create(any(), same(good), anyList());
    }

    @Test
    void allConfiguredCandidatesIneligibleSurfacesStreamingUnsupported() {
        when(modelRouter.resolveCandidateRequired("a"))
                .thenReturn(noToolCallingAgent("a"));
        when(modelRouter.resolveCandidateRequired("b"))
                .thenReturn(noToolCallingAgent("b"));

        RagException error = assertThrows(RagException.class,
                () -> service().stream(command(ChatMode.AGENT, null,
                        List.of("a", "b"))).collectList().block());

        assertEquals(ErrorCode.MODEL_STREAMING_UNSUPPORTED,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("with streaming"));
    }

    @Test
    void allConfiguredCandidatesUnavailableSurfacesServiceUnavailable() {
        when(modelRouter.resolveCandidateRequired(anyString()))
                .thenThrow(new IllegalArgumentException("unknown ref"));

        RagException error = assertThrows(RagException.class,
                () -> service().stream(command(ChatMode.AGENT, null,
                        List.of("ghost-a", "ghost-b")))
                        .collectList().block());

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage()
                .contains("No configured model candidate is available"));
    }

    // ── modelRef 显式校验 ───────────────────────────────────────────

    @Test
    void modelRefCandidateWithoutToolCallingIsRejectedForAgent() {
        ChatModelRouter.ChatModelCandidate explicit =
                noToolCallingAgent("explicit");
        when(modelRouter.orderedCandidateDescriptors(eq("explicit")))
                .thenReturn(List.of(explicit));

        RagException error = assertThrows(RagException.class,
                () -> service().stream(command(ChatMode.AGENT, "explicit",
                        java.util.List.of())).collectList().block());

        assertEquals(ErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("'explicit'"));
        assertTrue(error.getMessage().contains("tool calling"));
    }

    @Test
    void modelRefCandidateWithoutStreamingIsRejectedForKnowledge() {
        ChatModelRouter.ChatModelCandidate explicit = nonStreaming("slow");
        when(modelRouter.orderedCandidateDescriptors(eq("slow")))
                .thenReturn(List.of(explicit));

        RagException error = assertThrows(RagException.class,
                () -> service().stream(command(ChatMode.KNOWLEDGE, "slow",
                        java.util.List.of())).collectList().block());

        assertEquals(ErrorCode.MODEL_STREAMING_UNSUPPORTED,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("does not support streaming"));
    }
}
