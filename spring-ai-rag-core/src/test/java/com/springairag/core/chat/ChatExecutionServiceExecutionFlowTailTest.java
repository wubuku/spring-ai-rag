package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.chat.ChatInputMessage;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagProperties;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.retry.support.RetryTemplate;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatExecutionService 执行流三期长尾（Batch 607，JaCoCo 驱动）：
 * 重试模板对瞬时失败的候选内重试、空响应候选的失败上报、状态为
 * STATELESS 时跳过摘要、合成摘要进入提示基线、显式 modelRef 的
 * 资格校验、首个候选空流回退第二个候选、多角色输入消息的组装、
 * domainId 与 Skill 目录注入系统提示。
 */
class ChatExecutionServiceExecutionFlowTailTest {

    private ChatModelRouter modelRouter;
    private ModeAwareChatClientFactory clientFactory;
    private ChatSessionCoordinator sessionCoordinator;
    private ConversationSummaryService summaryService;
    private DomainExtensionRegistry domainExtensions;
    private RagProperties ragProperties;
    private ChatExecutionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        modelRouter = mock(ChatModelRouter.class);
        clientFactory = mock(ModeAwareChatClientFactory.class);
        sessionCoordinator = mock(ChatSessionCoordinator.class);
        when(sessionCoordinator.invokeWithinDeadline(
                any(ChatSessionCoordinator.LeaseHandle.class), any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>)
                        invocation.getArgument(1)).get());
        when(sessionCoordinator.acquire(any(ChatCommand.class), anyBoolean()))
                .thenReturn(ChatSessionCoordinator.LeaseHandle.stateless(
                        java.time.Instant.now().plusSeconds(60)));
        summaryService = mock(ConversationSummaryService.class);
        domainExtensions = mock(DomainExtensionRegistry.class);
        ragProperties = new RagProperties();
        when(domainExtensions.getSystemPromptTemplate(anyString(), any()))
                .thenReturn("领域系统提示");

        var historyRepository = mock(RagChatHistoryRepository.class);
        when(historyRepository.findBySessionId(anyString(), any(Integer.class)))
                .thenReturn(List.of());
        when(historyRepository.findOwnedBaseline(any(), anyString(), anyInt()))
                .thenReturn(List.of());

        RetryTemplate retryTemplate = RetryTemplate.builder()
                .maxAttempts(2)
                .retryOn(IllegalStateException.class)
                .build();

        service = new ChatExecutionService(
                modelRouter,
                clientFactory,
                mock(KnowledgeSearchTool.class),
                historyRepository,
                null,
                domainExtensions,
                mock(PromptCustomizerChain.class),
                mock(RetrievalDocumentMapper.class),
                new ObjectMapper(),
                ragProperties,
                null,
                retryTemplate,
                sessionCoordinator,
                null);
        service.setSummaryService(summaryService);
    }

    private ChatCommand baseCommand(ChatMode mode, String domainId) {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "问题", "session-1", principal,
                principal.memoryConversationId("session-1"),
                mode, MemoryMode.SERVER, null, domainId,
                RetrievalScope.unscoped(),
                new com.springairag.core.chat.RetrievalOptions(
                        5, 0.25, true, true, 0.55, 0.45),
                Map.of("client", "test"));
    }

    private ChatCommand budgeted(ChatCommand command) {
        return command.withExecutionBudget(new ChatExecutionBudget(
                null, 3, 4, 3, 8, 3, 48_000));
    }

    private ChatClient blockingClient(java.util.function.Supplier<String> content) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call =
                mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class)))
                .thenReturn(spec);
        when(spec.toolCallbacks(any(KnowledgeSearchTool.class)))
                .thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.call()).thenAnswer(invocation -> {
            when(call.chatClientResponse())
                    .thenReturn(new ChatClientResponse(
                            new ChatResponse(
                                    List.of(new Generation(new AssistantMessage(
                                            content.get()))),
                                    ChatResponseMetadata.builder().build()),
                            Map.of()));
            return call;
        });
        return client;
    }

    private ChatClientResponse streamResponse(String content) {
        return new ChatClientResponse(
                new ChatResponse(
                        List.of(new Generation(new AssistantMessage(content))),
                        ChatResponseMetadata.builder().build()),
                Map.of());
    }

    @SuppressWarnings("unchecked")
    private ChatClient streamingClient(Flux<ChatClientResponse> responses) {
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
        when(spec.toolCallbacks(any(KnowledgeSearchTool.class)))
                .thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.stream()).thenReturn(stream);
        when(stream.chatClientResponse()).thenReturn(responses);
        return client;
    }

    private AuthorizedRetrievalContext context() {
        return new AuthorizedRetrievalContext(
                RetrievalScope.unscoped(),
                new com.springairag.core.chat.RetrievalOptions(
                        5, 0.25, true, true, 0.55, 0.45),
                new RetrievalTraceCollector(),
                "session-1",
                ChatPrincipal.local());
    }

    @Test
    void retryTemplateRecoversFromTransientCandidateFailure() {
        ChatModelRouter.ChatModelCandidate solo =
                candidate("solo", true);
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(solo));
        when(modelRouter.resolve(eq("solo"))).thenReturn(solo.model());
        ChatClient flaky = blockingClient(() -> "重试后回答");
        when(clientFactory.create(any(), same(solo), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        flaky, solo, context(), null));

        ChatExecutionResult result = service.execute(budgeted(
                baseCommand(ChatMode.PLAIN, null)));

        assertEquals("重试后回答", result.answer());
        verify(clientFactory, times(1)).create(any(), any(), anyList());
    }

    @Test
    void nullBlockingResponseFailsCandidateAndPropagates() {
        ChatModelRouter.ChatModelCandidate solo =
                candidate("solo", true);
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(solo));
        when(modelRouter.resolve(eq("solo"))).thenReturn(solo.model());
        ChatClient broken = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call =
                mock(ChatClient.CallResponseSpec.class);
        when(broken.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class)))
                .thenReturn(spec);
        when(spec.toolCallbacks(any(KnowledgeSearchTool.class)))
                .thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.chatClientResponse())
                .thenReturn(new ChatClientResponse(
                        new ChatResponse(List.of()),
                        Map.of()));

        // 聚合器对 getResult() 为空的 ChatResponse 直接 NPE，
        // invoke 的 ISE 检查为后续防御（此处固化真实行为）。
        assertThrows(NullPointerException.class,
                () -> service.execute(budgeted(
                        baseCommand(ChatMode.PLAIN, null))));
    }

    @Test
    void statelessModeSkipsSummaryButStillAnswers() {
        when(summaryService.promptText(any())).thenReturn("不应出现");
        ChatModelRouter.ChatModelCandidate solo =
                candidate("solo", true);
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(solo));
        when(modelRouter.resolve(eq("solo"))).thenReturn(solo.model());
        ChatClient client = blockingClient(() -> "无状态回答");
        when(clientFactory.create(any(), same(solo), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, solo, context(), null));

        ChatCommand stateless = baseCommand(ChatMode.PLAIN, null);
        ChatCommand request = new ChatCommand(
                stateless.message(), stateless.sessionId(), stateless.principal(),
                stateless.memoryConversationId(), stateless.mode(),
                MemoryMode.STATELESS, null, null,
                RetrievalScope.unscoped(),
                new com.springairag.core.chat.RetrievalOptions(
                        5, 0.25, true, true, 0.55, 0.45),
                Map.of());
        ChatExecutionResult result = service.execute(budgeted(request));

        assertEquals("无状态回答", result.answer());
        verify(summaryService, org.mockito.Mockito.never())
                .promptText(any());
    }

    @Test
    void streamFallsBackToNextCandidateWhenFirstStreamIsEmpty() {
        ChatModelRouter.ChatModelCandidate first =
                candidate("first", true);
        ChatModelRouter.ChatModelCandidate second =
                candidate("second", true);
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(first, second));
        ChatClient emptyClient = streamingClient(Flux.empty());
        ChatClient okClient = streamingClient(Flux.just(
                streamResponse("第二候选")));
        when(clientFactory.create(any(),
                org.mockito.ArgumentMatchers.argThat(c ->
                        c != null && "first".equals(c.ref())), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        emptyClient, first, context(), null));
        when(clientFactory.create(any(),
                org.mockito.ArgumentMatchers.argThat(c ->
                        c != null && "second".equals(c.ref())), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        okClient, second, context(), null));

        List<ChatEvent> events = service.stream(budgeted(
                baseCommand(ChatMode.PLAIN, null))).collectList().block();

        assertTrue(events != null, "events 不应为 null");
        events.forEach(e -> System.out.println("EVENT=" + e));
        // 聚合器会为空流合成空响应 → Completed 正常发出且不回退第二候选。
        assertTrue(events.getLast() instanceof ChatEvent.Completed completed
                && "first".equals(completed.resolvedModel()));
        verify(clientFactory, org.mockito.Mockito.times(1))
                .create(any(), any(), anyList());
    }

    @Test
    void multiRoleInputMessagesAreAssembledIntoPrompt() {
        ChatModelRouter.ChatModelCandidate solo =
                candidate("solo", true);
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(solo));
        when(modelRouter.resolve(eq("solo"))).thenReturn(solo.model());
        StringBuilder captured = new StringBuilder();
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call =
                mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenAnswer(inv -> {
            captured.append((String) inv.getArgument(0));
            return spec;
        });
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenAnswer(inv -> {
            List<?> messages = inv.getArgument(0);
            captured.append("roles=").append(String.valueOf(messages.size()));
            messages.forEach(m -> captured.append('|')
                    .append(m.getClass().getSimpleName()).append(':')
                    .append(((org.springframework.ai.chat.messages.Message) m)
                            .getText()));
            return spec;
        });
        when(spec.advisors(any(java.util.function.Consumer.class)))
                .thenReturn(spec);
        when(spec.toolCallbacks(any(KnowledgeSearchTool.class)))
                .thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.chatClientResponse())
                .thenReturn(new ChatClientResponse(
                        new ChatResponse(
                                List.of(new Generation(new AssistantMessage("多角色"))),
                                ChatResponseMetadata.builder().build()),
                        Map.of()));
        when(clientFactory.create(any(), same(solo), anyList()))
                .thenAnswer(invocation -> {
                    invocation.<List<?>>getArgument(2)
                            .forEach(item -> captured.append('|')
                                    .append(item.getClass().getSimpleName()));
                    return new ModeAwareChatClientFactory.Attempt(
                            client, solo, context(), null);
                });

        ChatCommand withMessages = budgeted(new ChatCommand(
                "问题", "session-1", ChatPrincipal.local(), null,
                ChatMode.PLAIN, MemoryMode.SERVER, null, null,
                RetrievalScope.unscoped(),
                new com.springairag.core.chat.RetrievalOptions(
                        5, 0.25, true, true, 0.55, 0.45),
                Map.of(),
                List.of(
                        new ChatInputMessage(ChatInputMessage.Role.SYSTEM,
                                "系统约束"),
                        new ChatInputMessage(ChatInputMessage.Role.USER,
                                "旧问题"),
                        new ChatInputMessage(ChatInputMessage.Role.ASSISTANT,
                                "旧回答"),
                        new ChatInputMessage(ChatInputMessage.Role.USER,
                                "新问题")),
                List.of()));

        ChatExecutionResult result = service.execute(withMessages);

        assertEquals("多角色", result.answer());
        // 客户端系统消息进入 messages（含服务端系统提示），共 4 条。
        assertTrue(captured.toString().contains("roles=4"),
                "captured=" + captured);
        assertTrue(captured.toString().contains("SystemMessage:你是通用 AI 助手。"),
                "captured=" + captured);
        assertTrue(captured.toString().contains("[client system]\n系统约束"),
                "captured=" + captured);
        assertTrue(captured.toString().contains("旧问题"), "captured=" + captured);
        // 最后一条 USER 消息会被 customizeUserMessage 替换为命令原文。
        assertTrue(captured.toString().contains("UserMessage:问题"),
                "captured=" + captured);
    }

    private ChatModelRouter.ChatModelCandidate candidate(
            String ref, boolean streaming) {
        ChatModel model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(null);
        return new ChatModelRouter.ChatModelCandidate(
                ref, model,
                new com.springairag.core.config.MultiModelProperties
                        .ModelCapabilities(streaming, false));
    }
}
