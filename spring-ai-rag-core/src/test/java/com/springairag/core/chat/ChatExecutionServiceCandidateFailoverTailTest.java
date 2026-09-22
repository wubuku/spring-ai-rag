package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.repository.RagChatHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatExecutionService 候选降级编排长尾（Batch 568，JaCoCo 驱动）：
 * execute 对候选失败的降级重试（首个失败、次个成功）、全部失败抛出
 * 最后一次异常、无可用候选抛出 LLM_UNAVAILABLE。
 */
class ChatExecutionServiceCandidateFailoverTailTest {

    private ChatModelRouter modelRouter;
    private ModeAwareChatClientFactory clientFactory;
    private ChatExecutionService service;

    @BeforeEach
    void setUp() {
        modelRouter = mock(ChatModelRouter.class);
        clientFactory = mock(ModeAwareChatClientFactory.class);
        var historyRepository = mock(RagChatHistoryRepository.class);
        when(historyRepository.findBySessionId(anyString(), any(Integer.class)))
                .thenReturn(List.of());
        var promptCustomizers = mock(PromptCustomizerChain.class);
        when(promptCustomizers.hasCustomizers()).thenReturn(false);
        service = new ChatExecutionService(
                modelRouter,
                clientFactory,
                mock(KnowledgeSearchTool.class),
                historyRepository,
                mock(DomainExtensionRegistry.class),
                promptCustomizers,
                mock(RetrievalDocumentMapper.class),
                new ObjectMapper().findAndRegisterModules(),
                new RagProperties(),
                null,
                null);
    }

    private ChatCommand command(ChatMode mode) {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "问题", "session-1", principal,
                principal.memoryConversationId("session-1"),
                mode, MemoryMode.SERVER, null, null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of());
    }

    private ChatModelRouter.ChatModelCandidate candidate(String ref) {
        ChatModel model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(
                mock(ToolCallingChatOptions.class));
        return new ChatModelRouter.ChatModelCandidate(
                ref, model,
                com.springairag.core.config.MultiModelProperties
                        .ModelCapabilities.defaults());
    }

    private AuthorizedRetrievalContext retrievalContext() {
        return new AuthorizedRetrievalContext(
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                new RetrievalTraceCollector(),
                "session-1",
                ChatPrincipal.local());
    }

    private ChatClient failingClient(String reason) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.call()).thenThrow(new IllegalStateException(reason));
        return client;
    }

    private ChatClient successClient(String answer) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call =
                mock(ChatClient.CallResponseSpec.class);
        org.springframework.ai.chat.client.ChatClientResponse response =
                mock(org.springframework.ai.chat.client.ChatClientResponse.class);
        ChatResponse springResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage(answer))),
                ChatResponseMetadata.builder().build());
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.chatClientResponse()).thenReturn(response);
        when(response.chatResponse()).thenReturn(springResponse);
        when(response.context()).thenReturn(Map.of());
        return client;
    }

    private void stubFactory(ChatModelRouter.ChatModelCandidate candidate,
                             ChatClient client) {
        when(clientFactory.create(any(), eq(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, candidate, retrievalContext(), null));
    }

    @Test
    void firstCandidateFailureFallsOverToSecondCandidate() {
        var primary = candidate("provider/primary");
        var fallback = candidate("provider/fallback");
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(primary, fallback));
        stubFactory(primary, failingClient("provider down"));
        stubFactory(fallback, successClient("fallback answer"));

        ChatExecutionResult result = service.execute(
                command(ChatMode.KNOWLEDGE));

        assertEquals("fallback answer", result.answer());
        assertEquals("provider/fallback", result.resolvedModel());
    }

    @Test
    void allCandidatesFailSurfacesLastFailure() {
        var primary = candidate("provider/primary");
        var fallback = candidate("provider/fallback");
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(primary, fallback));
        stubFactory(primary, failingClient("first down"));
        stubFactory(fallback, failingClient("second down"));

        var error = assertThrows(IllegalStateException.class,
                () -> service.execute(command(ChatMode.KNOWLEDGE)));

        assertEquals("second down", error.getMessage());
    }

    @Test
    void noRouterCandidatesSurfaceCapabilityUnsupported() {
        // 路由器无任何候选 → eligibleCandidates 抛 MODEL_*（execute 的
        // LLM_UNAVAILABLE 分支要求 candidates 空但不抛，实际不可达）。
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of());

        var error = assertThrows(RagException.class,
                () -> service.execute(command(ChatMode.KNOWLEDGE)));

        assertEquals(ErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
                error.getErrorCodeEnum());
    }
}
