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
import com.springairag.core.repository.RagChatHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatExecutionService 候选筛选与校验长尾（Batch 550，JaCoCo 驱
 * 动）：eligibleCandidates 的配置解析/能力过滤/解析失败/全不可用分
 * 流、validateCandidate 的流式与工具调用能力守卫。
 */
class ChatExecutionServiceEligibleCandidatesTailTest {

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
                new ObjectMapper().findAndRegisterModules(),
                new RagProperties(),
                null,
                null);
    }

    private ChatCommand commandWithCandidates(List<String> refs,
                                              ChatMode mode) {
        return new ChatCommand(
                "question", "session-1", ChatPrincipal.local(),
                null, mode, MemoryMode.STATELESS,
                null, null, null,
                null,
                Map.of(), List.of(), refs, null, null, null);
    }

    private List<String> eligibleRefs(ChatCommand command, boolean streaming) {
        return service.resolveCandidateRefs(command, streaming);
    }

    private void validate(ChatModelRouter.ChatModelCandidate candidate,
                          ChatMode mode, boolean streaming,
                          boolean explicitlyRequested) {
        try {
            Method method = ChatExecutionService.class.getDeclaredMethod(
                    "validateCandidate",
                    ChatModelRouter.ChatModelCandidate.class, ChatMode.class,
                    boolean.class, boolean.class);
            method.setAccessible(true);
            method.invoke(service, candidate, mode, streaming,
                    explicitlyRequested);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ChatModelRouter.ChatModelCandidate candidate(
            String ref, boolean streaming, boolean toolCalling,
            ChatModel model) {
        return new ChatModelRouter.ChatModelCandidate(
                ref, model,
                new com.springairag.core.config.MultiModelProperties
                        .ModelCapabilities(streaming, toolCalling));
    }

    @Test
    void configuredCandidatesAreResolvedAndFilteredByEligibility() {
        ChatModel capable = mock(ChatModel.class);
        when(capable.getDefaultOptions())
                .thenReturn(mock(ToolCallingChatOptions.class));
        var good = candidate("vendor/good", true, true, capable);
        var noStreaming = candidate("vendor/nostream", false, true, capable);
        when(modelRouter.resolveCandidateRequired("vendor/good"))
                .thenReturn(good);
        when(modelRouter.resolveCandidateRequired("vendor/nostream"))
                .thenReturn(noStreaming);

        assertEquals(List.of("vendor/good"),
                eligibleRefs(commandWithCandidates(
                        List.of("vendor/good", "vendor/nostream"),
                        ChatMode.KNOWLEDGE), true));
    }

    @Test
    void filteredOutCandidatesSurfaceCapabilityUnsupported() {
        // AGENT 模式下 getDefaultOptions 非 ToolCallingChatOptions →
        // isEligible=false → 过滤为空 → MODEL_CAPABILITY_UNSUPPORTED。
        var ineligible = candidate("vendor/m", true, false,
                mock(ChatModel.class));
        when(modelRouter.resolveCandidateRequired("vendor/m"))
                .thenReturn(ineligible);

        var error = assertThrows(RagException.class,
                () -> eligibleRefs(commandWithCandidates(
                        List.of("vendor/m"), ChatMode.AGENT), false));
        assertEquals(ErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
                error.getErrorCodeEnum());
    }

    @Test
    void filteredOutStreamingCandidatesSurfaceStreamingUnsupported() {
        var ineligible = candidate("vendor/m", false, false,
                mock(ChatModel.class));
        when(modelRouter.resolveCandidateRequired("vendor/m"))
                .thenReturn(ineligible);

        var error = assertThrows(RagException.class,
                () -> eligibleRefs(commandWithCandidates(
                        List.of("vendor/m"), ChatMode.KNOWLEDGE), true));
        assertEquals(ErrorCode.MODEL_STREAMING_UNSUPPORTED,
                error.getErrorCodeEnum());
    }

    @Test
    void allConfiguredCandidatesUnresolvableSurfacesServiceUnavailable() {
        when(modelRouter.resolveCandidateRequired("vendor/gone"))
                .thenThrow(new IllegalArgumentException("gone"));

        var error = assertThrows(RagException.class,
                () -> eligibleRefs(commandWithCandidates(
                        List.of("vendor/gone"), ChatMode.KNOWLEDGE), false));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
    }

    @Test
    void emptyCandidateListFallsBackToRouterDescriptors() {
        var fallback = candidate("vendor/fallback", true, true,
                mock(ChatModel.class));
        when(modelRouter.orderedCandidateDescriptors(null))
                .thenReturn(List.of(fallback));

        // 空 modelCandidates：默认路径由路由器的有序候选描述提供。
        assertEquals(List.of("vendor/fallback"),
                eligibleRefs(commandWithCandidates(
                        List.of(), ChatMode.KNOWLEDGE), false));
    }

    @Test
    void explicitlyRequestedModelIsValidatedEvenWhenDescriptorsEmpty() {
        var requested = candidate("vendor/req", true, true,
                mock(ChatModel.class));
        when(modelRouter.resolveCandidateRequired("vendor/req"))
                .thenReturn(requested);

        ChatCommand command = new ChatCommand(
                "question", "session-1", ChatPrincipal.local(),
                null, ChatMode.KNOWLEDGE, MemoryMode.STATELESS,
                "vendor/req", null, null, null,
                Map.of(), List.of(), List.of(), null, null, null);

        // 描述为空时显式请求的模型仍会被校验，但合格列表为空 → 异常。
        var error = assertThrows(RagException.class,
                () -> eligibleRefs(command, false));
        assertEquals(ErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
                error.getErrorCodeEnum());
    }

    @Test
    void validateCandidateRejectsStreamingOnNonStreamingModel() {
        var error = assertThrows(RagException.class,
                () -> validate(candidate("vendor/m", false, true,
                        mock(ChatModel.class)), ChatMode.KNOWLEDGE,
                        true, true));
        assertEquals(ErrorCode.MODEL_STREAMING_UNSUPPORTED,
                error.getErrorCodeEnum());
    }

    @Test
    void validateCandidateRejectsAgentWithoutToolCallingOptions() {
        ChatModel plain = mock(ChatModel.class);
        when(plain.getDefaultOptions()).thenReturn(null);
        var error = assertThrows(RagException.class,
                () -> validate(candidate("vendor/m", true, false, plain),
                        ChatMode.AGENT, false, true));
        assertEquals(ErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
                error.getErrorCodeEnum());
    }

    @Test
    void validateCandidateAcceptsAgentWithToolCallingOptions() {
        ChatModel capable = mock(ChatModel.class);
        when(capable.getDefaultOptions())
                .thenReturn(mock(ToolCallingChatOptions.class));
        validate(candidate("vendor/m", true, true, capable),
                ChatMode.AGENT, false, true);
    }
}
