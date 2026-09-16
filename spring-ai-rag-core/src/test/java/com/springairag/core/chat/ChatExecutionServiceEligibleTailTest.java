package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.chat.ModeAwareChatClientFactory;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatExecutionService.eligibleCandidates 矩阵（Batch 451）：
 * 显式候选链的解析失败跳过/能力过滤/全不可用错误码分派、空候选
 * 链的显式 modelRef 校验与默认链回退。
 */
class ChatExecutionServiceEligibleTailTest {

    private ChatModelRouter modelRouter;
    private ChatExecutionService service;

    @BeforeEach
    void setUp() {
        modelRouter = mock(ChatModelRouter.class);
        service = new ChatExecutionService(
                modelRouter,
                mock(ModeAwareChatClientFactory.class),
                mock(KnowledgeSearchTool.class),
                mock(RagChatHistoryRepository.class),
                mock(DomainExtensionRegistry.class),
                mock(PromptCustomizerChain.class),
                mock(RetrievalDocumentMapper.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new com.springairag.core.config.RagProperties(),
                null,
                null);
    }

    private ChatModelRouter.ChatModelCandidate candidate(
            String ref, boolean streaming, boolean toolCalling,
            boolean toolOptions) {
        ChatModel model = mock(ChatModel.class);
        if (toolOptions) {
            when(model.getDefaultOptions()).thenReturn(
                    ToolCallingChatOptions.builder().model(ref).build());
        }
        return new ChatModelRouter.ChatModelCandidate(
                ref, model,
                new MultiModelProperties.ModelCapabilities(streaming, toolCalling));
    }

    private ChatCommand command(ChatMode mode, String modelRef,
                                List<String> candidates) {
        return new ChatCommand(
                "问题", "session-1", ChatPrincipal.local(),
                ChatPrincipal.local().memoryConversationId("session-1"),
                mode, MemoryMode.SERVER, modelRef, null,
                com.springairag.core.retrieval.RetrievalScope.unscoped(),
                new com.springairag.core.chat.RetrievalOptions(
                        5, 0.25, true, true, 0.55, 0.45),
                java.util.Map.of(), List.of(), candidates);
    }

    @SuppressWarnings("unchecked")
    private List<ChatModelRouter.ChatModelCandidate> eligible(
            ChatCommand command, boolean streaming) throws Exception {
        Method method = ChatExecutionService.class.getDeclaredMethod(
                "eligibleCandidates", ChatCommand.class, boolean.class);
        method.setAccessible(true);
        try {
            return (List<ChatModelRouter.ChatModelCandidate>)
                    method.invoke(service, command, streaming);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (RagException) e.getCause();
        }
    }

    @Test
    void configuredChainFiltersIneligibleAndUnavailableCandidates()
            throws Exception {
        ChatModelRouter.ChatModelCandidate eligible =
                candidate("acme/ok", true, true, true);
        ChatModelRouter.ChatModelCandidate notStreaming =
                candidate("acme/slow", false, true, true);
        when(modelRouter.resolveCandidateRequired("acme/ok"))
                .thenReturn(eligible);
        when(modelRouter.resolveCandidateRequired("acme/slow"))
                .thenReturn(notStreaming);

        List<ChatModelRouter.ChatModelCandidate> eligibleList = eligible(
                command(ChatMode.AGENT, null,
                        List.of("acme/ok", "acme/slow")), true);

        // 不支持流式的候选被能力过滤掉。
        assertEquals(List.of("acme/ok"), refs(eligibleList));
    }

    @Test
    void configuredChainSkipsUnavailableCandidates() throws Exception {
        when(modelRouter.resolveCandidateRequired("ghost"))
                .thenThrow(new IllegalArgumentException("unknown"));
        ChatModelRouter.ChatModelCandidate eligible =
                candidate("acme/ok", true, true, true);
        when(modelRouter.resolveCandidateRequired("acme/ok"))
                .thenReturn(eligible);

        List<ChatModelRouter.ChatModelCandidate> eligibleList = eligible(
                command(ChatMode.AGENT, null,
                        List.of("ghost", "acme/ok")), true);

        assertEquals(List.of("acme/ok"), refs(eligibleList));
    }

    @Test
    void fullyUnavailableConfiguredChainThrowsServiceUnavailable() {
        when(modelRouter.resolveCandidateRequired(anyString()))
                .thenThrow(new IllegalArgumentException("unknown"));

        RagException error = assertThrows(RagException.class,
                () -> eligible(command(ChatMode.AGENT, null,
                        List.of("ghost1", "ghost2")), true));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,
                error.getErrorCodeEnum());
    }

    @Test
    void resolvedButIneligibleChainThrowsModeOrStreamingError() {
        // 既不支持流式也不支持工具调用的文本模型。
        ChatModelRouter.ChatModelCandidate textOnly =
                candidate("acme/text", false, false, false);
        when(modelRouter.resolveCandidateRequired("acme/text"))
                .thenReturn(textOnly);

        RagException streamingError = assertThrows(RagException.class,
                () -> eligible(command(ChatMode.AGENT, null,
                        List.of("acme/text")), true));
        assertEquals(ErrorCode.MODEL_STREAMING_UNSUPPORTED,
                streamingError.getErrorCodeEnum());

        RagException capabilityError = assertThrows(RagException.class,
                () -> eligible(command(ChatMode.AGENT, null,
                        List.of("acme/text")), false));
        assertEquals(ErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
                capabilityError.getErrorCodeEnum());
    }

    @Test
    void defaultChainWithoutModelRefFiltersOrderedDescriptors()
            throws Exception {
        ChatModelRouter.ChatModelCandidate eligible =
                candidate("acme/ok", true, true, true);
        ChatModelRouter.ChatModelCandidate ineligible =
                candidate("acme/no-tools", true, false, true);
        when(modelRouter.orderedCandidateDescriptors(org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(List.of(eligible, ineligible));

        List<ChatModelRouter.ChatModelCandidate> eligibleList = eligible(
                command(ChatMode.AGENT, null, List.of()), true);

        // AGENT 模式要求工具调用能力：无工具能力的候选被过滤。
        assertEquals(List.of("acme/ok"), refs(eligibleList));
    }

    @Test
    void defaultChainAllIneligibleThrowsModeError() throws Exception {
        ChatModelRouter.ChatModelCandidate ineligible =
                candidate("acme/plain", true, false, true);
        when(modelRouter.orderedCandidateDescriptors(org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(List.of(ineligible));

        RagException error = assertThrows(RagException.class,
                () -> eligible(command(ChatMode.AGENT, null, List.of()), false));
        assertEquals(ErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
                error.getErrorCodeEnum());
    }

    private List<String> refs(List<ChatModelRouter.ChatModelCandidate> candidates) {
        return candidates.stream()
                .map(ChatModelRouter.ChatModelCandidate::ref)
                .toList();
    }
}
