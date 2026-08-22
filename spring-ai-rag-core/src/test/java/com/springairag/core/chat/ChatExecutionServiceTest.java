package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.ChatHistoryResponse;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.exception.RagException;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.rag.ProjectDocumentRetriever;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatExecutionServiceTest {

    private ChatModelRouter modelRouter;
    private ModeAwareChatClientFactory clientFactory;
    private KnowledgeSearchTool knowledgeSearchTool;
    private RagChatHistoryRepository historyRepository;
    private DomainExtensionRegistry domainExtensions;
    private PromptCustomizerChain promptCustomizers;
    private RetrievalDocumentMapper documentMapper;
    private ChatExecutionService service;

    @BeforeEach
    void setUp() {
        modelRouter = mock(ChatModelRouter.class);
        clientFactory = mock(ModeAwareChatClientFactory.class);
        knowledgeSearchTool = mock(KnowledgeSearchTool.class);
        historyRepository = mock(RagChatHistoryRepository.class);
        domainExtensions = mock(DomainExtensionRegistry.class);
        promptCustomizers = mock(PromptCustomizerChain.class);
        documentMapper = mock(RetrievalDocumentMapper.class);
        when(historyRepository.findBySessionId(anyString(), any(Integer.class)))
                .thenReturn(List.of());
        when(promptCustomizers.hasCustomizers()).thenReturn(false);
        service = new ChatExecutionService(
                modelRouter,
                clientFactory,
                knowledgeSearchTool,
                historyRepository,
                domainExtensions,
                promptCustomizers,
                documentMapper,
                new ObjectMapper(),
                new RagProperties(),
                null,
                null);
    }

    @Test
    void resolvesCapabilityFilteredCandidateRefsForDurableSnapshot() {
        ChatModelRouter.ChatModelCandidate primary =
                candidate("provider/primary", true, false, false);
        ChatModelRouter.ChatModelCandidate fallback =
                candidate("provider/fallback", true, false, false);
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(primary, fallback));

        assertEquals(
                List.of("provider/primary", "provider/fallback"),
                service.resolveCandidateRefs(command(ChatMode.PLAIN, null), false));
    }

    @Test
    void knowledgeModeUsesAdvisorDocumentContextAndPersistsSources() {
        ChatModelRouter.ChatModelCandidate candidate =
                candidate("knowledge-model", true, false, false);
        AuthorizedRetrievalContext context = context();
        ClientFixture client = clientFixture("基于资料的回答", Map.of(
                RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT,
                List.of(Document.builder()
                        .id("10:2")
                        .text("风格基调是克制、清晰。")
                        .metadata(Map.of(
                                "documentId", "10",
                                "chunkIndex", 2,
                                "title", "品牌手册",
                                "score", 0.84))
                        .build())));
        ChatSource source = new ChatSource();
        source.setCitationId("S1");
        source.setDocumentId("10");
        source.setChunkIndex(2);
        source.setTitle("品牌手册");
        source.setScore(0.84);
        when(documentMapper.toChatSource(any(Document.class), eq(1)))
                .thenReturn(source);
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(candidate));
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client.client(), candidate, context, null));
        assertTrue(context.trace().tryBeginRetrieval("风格基调"));

        ChatExecutionResult result = service.execute(
                command(ChatMode.KNOWLEDGE, null));

        assertEquals("基于资料的回答", result.answer());
        assertEquals("knowledge-model", result.resolvedModel());
        assertEquals(1, result.sources().size());
        assertEquals("S1", result.sources().getFirst().getCitationId());
        assertEquals(true, result.metadata().get("retrievalExecuted"));
        verify(historyRepository).save(
                eq("session-1"),
                eq("问题"),
                eq("基于资料的回答"),
                eq("[10]"),
                any());
        verify(client.spec(), never()).toolCallbacks(any(KnowledgeSearchTool.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void agentModeInjectsOnlyServerOwnedAuthorizedToolContext() {
        ChatModelRouter.ChatModelCandidate candidate =
                candidate("agent-model", true, true, true);
        AuthorizedRetrievalContext context = context();
        ClientFixture client = clientFixture("工具回答", Map.of());
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(candidate));
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client.client(), candidate, context, null));

        ChatExecutionResult result = service.execute(
                command(ChatMode.AGENT, null));

        assertEquals(ChatMode.AGENT, result.mode());
        verify(client.spec()).toolCallbacks(knowledgeSearchTool);
        ArgumentCaptor<Map<String, Object>> toolContext =
                ArgumentCaptor.forClass(Map.class);
        verify(client.spec()).toolContext(toolContext.capture());
        assertEquals(1, toolContext.getValue().size());
        assertSame(context, toolContext.getValue().get(
                KnowledgeSearchTool.CONTEXT_KEY));
        assertEquals(0, context.trace().retrievalCalls());
        assertEquals(false, result.metadata().get("retrievalExecuted"));
    }

    @Test
    void transportMessagesArePassedInOrderWithoutServerHistory() {
        ChatModelRouter.ChatModelCandidate candidate =
                candidate("plain-model", true, false, false);
        AuthorizedRetrievalContext context = context();
        ClientFixture client = clientFixture("answer", Map.of());
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(candidate));
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client.client(), candidate, context, null));
        when(promptCustomizers.hasCustomizers()).thenReturn(true);
        when(promptCustomizers.customizeUserMessage(
                eq("latest"), eq(Map.of())))
                .thenReturn("customized latest");
        ChatPrincipal principal = ChatPrincipal.local();
        ChatCommand command = new ChatCommand(
                "latest",
                "session-1",
                principal,
                principal.memoryConversationId("session-1"),
                ChatMode.PLAIN,
                MemoryMode.STATELESS,
                null,
                null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of(),
                List.of(
                        new ChatInputMessage(
                                ChatInputMessage.Role.SYSTEM, "client system"),
                        new ChatInputMessage(
                                ChatInputMessage.Role.USER, "first"),
                        new ChatInputMessage(
                                ChatInputMessage.Role.ASSISTANT, "reply"),
                        new ChatInputMessage(
                                ChatInputMessage.Role.USER, "latest")),
                List.of());

        service.execute(command);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> messages =
                ArgumentCaptor.forClass(List.class);
        verify(client.spec()).messages(messages.capture());
        assertEquals(4, messages.getValue().size());
        assertTrue(messages.getValue().getFirst()
                instanceof org.springframework.ai.chat.messages.SystemMessage);
        assertEquals("first", messages.getValue().get(1).getText());
        assertEquals("customized latest",
                messages.getValue().getLast().getText());
        verify(historyRepository, never()).findBySessionId(
                anyString(), any(Integer.class));
    }

    @Test
    void serverMemoryConversationIdIsInjectedIntoEveryAdvisorRequest() {
        ChatModelRouter.ChatModelCandidate candidate =
                candidate("plain-model", true, false, false);
        AuthorizedRetrievalContext context = context();
        ClientFixture client = clientFixture("answer", Map.of());
        Map<String, Object> advisorParams = new HashMap<>();
        when(client.spec().advisors(any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<ChatClient.AdvisorSpec> consumer = invocation.getArgument(0);
            ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
            doAnswer(paramInvocation -> {
                advisorParams.put(
                        paramInvocation.getArgument(0),
                        paramInvocation.getArgument(1));
                return advisorSpec;
            }).when(advisorSpec).param(anyString(), any());
            consumer.accept(advisorSpec);
            return client.spec();
        });
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(candidate));
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client.client(), candidate, context, null));

        ChatCommand command = command(ChatMode.PLAIN, null);
        service.execute(command);

        assertEquals(
                command.memoryConversationId(),
                advisorParams.get(ChatMemory.CONVERSATION_ID));
    }

    @Test
    void agentModeReportsRetrievalOnlyAfterTheToolStartsARealSearch() {
        ChatModelRouter.ChatModelCandidate candidate =
                candidate("agent-model", true, true, true);
        AuthorizedRetrievalContext context = context();
        assertTrue(context.trace().tryBeginRetrieval("风格基调"));
        ClientFixture client = clientFixture("工具回答", Map.of());
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(candidate));
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client.client(), candidate, context, null));

        ChatExecutionResult result = service.execute(
                command(ChatMode.AGENT, null));

        assertEquals(true, result.metadata().get("retrievalExecuted"));
    }

    @Test
    void agentModePreservesStableCitationIdsInFinalSources() {
        ChatModelRouter.ChatModelCandidate candidate =
                candidate("agent-model", true, true, true);
        AuthorizedRetrievalContext context = context();
        RetrievalResult omitted = retrievalResult("10", "Too large");
        RetrievalResult exposed = retrievalResult("11", "Visible source");
        context.trace().record("first query", List.of(omitted));
        context.trace().record("second query", List.of(exposed));
        context.trace().markExposed(List.of(exposed));
        ClientFixture client = clientFixture("工具回答 [S2]", Map.of());
        ChatSource source = new ChatSource();
        source.setCitationId("S2");
        source.setDocumentId("11");
        source.setTitle("Visible source");
        when(documentMapper.toChatSource(same(exposed), eq("S2")))
                .thenReturn(source);
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(candidate));
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client.client(), candidate, context, null));

        ChatExecutionResult result = service.execute(
                command(ChatMode.AGENT, null));

        assertEquals(1, result.sources().size());
        assertEquals("S2", result.sources().getFirst().getCitationId());
        verify(documentMapper).toChatSource(same(exposed), eq("S2"));
    }

    @Test
    void plainModeDoesNotInstallRetrievalOrToolInputs() {
        ChatModelRouter.ChatModelCandidate candidate =
                candidate("plain-model", true, false, false);
        AuthorizedRetrievalContext context = context();
        ClientFixture client = clientFixture("直接回答", Map.of());
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(candidate));
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client.client(), candidate, context, null));

        ChatExecutionResult result = service.execute(
                command(ChatMode.PLAIN, null));

        assertTrue(result.sources().isEmpty());
        assertEquals(false, result.metadata().get("retrievalExecuted"));
        verify(client.spec(), never()).toolCallbacks(any(KnowledgeSearchTool.class));
        verify(client.spec(), never()).toolContext(any());
    }

    @Test
    void coordinatedBaselineRemainsChronological() {
        ChatSessionCoordinator coordinator = mock(ChatSessionCoordinator.class);
        ChatSessionCoordinator.LeaseHandle lease =
                mock(ChatSessionCoordinator.LeaseHandle.class);
        when(coordinator.acquire(any(), anyBoolean())).thenReturn(lease);
        when(coordinator.invokeWithinDeadline(same(lease), any()))
                .thenAnswer(invocation ->
                        ((java.util.function.Supplier<?>) invocation.getArgument(1)).get());
        when(historyRepository.findOwnedBaseline(
                any(), eq("session-1"), any(Integer.class)))
                .thenReturn(List.of(
                        history("old question", "old answer", 1L),
                        history("new question", "new answer", 2L)));

        ChatModelRouter.ChatModelCandidate candidate =
                candidate("plain-model", true, false, false);
        AuthorizedRetrievalContext context = context();
        ClientFixture client = clientFixture("当前回答", Map.of());
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(candidate));
        ArgumentCaptor<List<Message>> baseline =
                ArgumentCaptor.forClass(List.class);
        when(clientFactory.create(any(), same(candidate), baseline.capture()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client.client(), candidate, context, null));

        ChatExecutionService coordinatedService = new ChatExecutionService(
                modelRouter,
                clientFactory,
                knowledgeSearchTool,
                historyRepository,
                null,
                domainExtensions,
                promptCustomizers,
                documentMapper,
                new ObjectMapper(),
                new RagProperties(),
                null,
                null,
                coordinator,
                null);

        coordinatedService.execute(command(ChatMode.PLAIN, null));

        assertEquals(List.of("old question", "old answer", "new question", "new answer"),
                baseline.getValue().stream().map(Message::getText).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void finalExecutionBudgetIncludesSummaryModelCall() {
        ChatModelRouter.ChatModelCandidate candidate =
                candidate("plain-model", true, false, false);
        AuthorizedRetrievalContext context = context();
        ClientFixture client = clientFixture("直接回答", Map.of());
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(candidate));
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client.client(), candidate, context, null));

        ConversationSummaryService summaryService =
                mock(ConversationSummaryService.class);
        when(summaryService.compactIfNeeded(any(), same(candidate), anyList()))
                .thenAnswer(invocation -> {
                    ChatCommand request = invocation.getArgument(0);
                    request.executionBudget().recordSummaryCall();
                    return ConversationSummaryService.CompactionResult.updated(
                            new ConversationSummaryService.SummarySnapshot(
                                    1, 1, "summary", 1, candidate.ref()));
                });
        service.setSummaryService(summaryService);

        ChatExecutionResult result = service.execute(
                command(ChatMode.PLAIN, null));

        Map<String, Object> executionBudget =
                (Map<String, Object>) result.metadata().get("executionBudget");
        assertEquals(1, executionBudget.get("summaryCalls"));
        Map<String, Object> summary =
                (Map<String, Object>) result.metadata().get("summary");
        assertEquals(true, summary.get("updated"));
    }

    @Test
    void keyedOperationFinalizationKeepsPostCommitSummaryCompaction() {
        ChatModelRouter.ChatModelCandidate candidate =
                candidate("plain-model", true, false, false);
        ConversationSummaryService summaryService =
                mock(ConversationSummaryService.class);
        service.setSummaryService(summaryService);
        ChatCommand command = command(ChatMode.PLAIN, null);
        ChatExecutionService.PreparedExecution prepared =
                new ChatExecutionService.PreparedExecution(
                        command,
                        null,
                        List.of(),
                        null,
                        null,
                        candidate);

        service.finalizePreparedOperation(prepared);

        verify(summaryService).compactIfNeeded(
                same(command),
                same(candidate),
                eq(List.of()));
    }

    @Test
    void syntheticSummaryIsUsedForPromptButNeverCommittedToDurableMemory() {
        ChatSessionCoordinator coordinator = mock(ChatSessionCoordinator.class);
        ChatSessionCoordinator.LeaseHandle lease =
                mock(ChatSessionCoordinator.LeaseHandle.class);
        when(coordinator.acquire(any(), anyBoolean())).thenReturn(lease);
        when(coordinator.invokeWithinDeadline(same(lease), any()))
                .thenAnswer(invocation ->
                        ((java.util.function.Supplier<?>) invocation.getArgument(1))
                                .get());

        ChatModelRouter.ChatModelCandidate candidate =
                candidate("plain-model", true, false, false);
        AuthorizedRetrievalContext context = context();
        ClientFixture client = clientFixture("当前回答", Map.of());
        ChatPrincipal principal = ChatPrincipal.local();
        ChatCommand command = new ChatCommand(
                "当前问题",
                "summary-memory-session",
                principal,
                principal.memoryConversationId("summary-memory-session"),
                ChatMode.PLAIN,
                MemoryMode.SERVER,
                null,
                null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of());
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        memory.add(
                command.memoryConversationId(),
                List.of(
                        AssistantMessage.builder()
                                .content("old durable summary")
                                .properties(Map.of(
                                        ConversationSummaryService
                                                .SYNTHETIC_SUMMARY_MESSAGE_METADATA_KEY,
                                        true))
                                .build(),
                        new org.springframework.ai.chat.messages.UserMessage(
                                "old question"),
                        new AssistantMessage("old answer"),
                        new org.springframework.ai.chat.messages.UserMessage(
                                "当前问题"),
                        new AssistantMessage("当前回答")));
        ModeAwareChatClientFactory.Attempt attempt =
                new ModeAwareChatClientFactory.Attempt(
                        client.client(), candidate, context, memory);
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(candidate));
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(attempt);

        ChatExecutionService coordinatedService = new ChatExecutionService(
                modelRouter,
                clientFactory,
                knowledgeSearchTool,
                historyRepository,
                null,
                domainExtensions,
                promptCustomizers,
                documentMapper,
                new ObjectMapper(),
                new RagProperties(),
                null,
                null,
                coordinator,
                null);

        coordinatedService.execute(command);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> committed =
                ArgumentCaptor.forClass(List.class);
        verify(coordinator).commit(
                same(lease),
                any(ChatCommand.class),
                any(ChatExecutionResult.class),
                committed.capture(),
                isNull());
        assertEquals(
                List.of("old question", "old answer", "当前问题", "当前回答"),
                committed.getValue().stream().map(Message::getText).toList());
    }

    @Test
    void explicitAgentModelWithoutToolCapabilityFailsWithoutFallback() {
        ChatModelRouter.ChatModelCandidate unsupported =
                candidate("requested", true, false, false);
        ChatModelRouter.ChatModelCandidate fallback =
                candidate("fallback", true, true, true);
        when(modelRouter.orderedCandidateDescriptors("requested"))
                .thenReturn(List.of(unsupported, fallback));

        RagException error = assertThrows(
                RagException.class,
                () -> service.execute(command(ChatMode.AGENT, "requested")));

        assertEquals(
                ErrorCode.MODEL_CAPABILITY_UNSUPPORTED.getCode(),
                error.getErrorCode());
        verify(clientFactory, never()).create(any(), any(), anyList());
    }

    @Test
    void implicitAgentRoutingSkipsUnsupportedCandidate() {
        ChatModelRouter.ChatModelCandidate unsupported =
                candidate("unsupported", true, false, false);
        ChatModelRouter.ChatModelCandidate supported =
                candidate("supported", true, true, true);
        AuthorizedRetrievalContext context = context();
        ClientFixture client = clientFixture("ok", Map.of());
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(unsupported, supported));
        when(clientFactory.create(any(), same(supported), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client.client(), supported, context, null));

        ChatExecutionResult result = service.execute(
                command(ChatMode.AGENT, null));

        assertEquals("supported", result.resolvedModel());
        verify(clientFactory, never()).create(any(), same(unsupported), anyList());
        verify(clientFactory).create(any(), same(supported), anyList());
    }

    @Test
    void streamCancellationDisposesUnderlyingModelFluxWithoutPersisting() {
        ChatModelRouter.ChatModelCandidate candidate =
                candidate("stream-model", true, false, false);
        AuthorizedRetrievalContext context = context();
        AtomicBoolean cancelled = new AtomicBoolean();
        StreamClientFixture client = streamClientFixture(
                Flux.<ChatClientResponse>never()
                        .doOnCancel(() -> cancelled.set(true)));
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(candidate));
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client.client(), candidate, context, null));

        Disposable subscription = service.stream(
                command(ChatMode.PLAIN, null)).subscribe();

        assertFalse(cancelled.get());
        subscription.dispose();

        assertTrue(cancelled.get());
        verify(historyRepository, never()).save(
                anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void streamingFallbackOccursOnlyBeforeTheFirstClientEvent() {
        ChatModelRouter.ChatModelCandidate primary =
                candidate("primary", true, false, false);
        ChatModelRouter.ChatModelCandidate fallback =
                candidate("fallback", true, false, false);
        AuthorizedRetrievalContext primaryContext = context();
        AuthorizedRetrievalContext fallbackContext = context();
        StreamClientFixture failed = streamClientFixture(
                Flux.error(new IllegalStateException("primary unavailable")));
        StreamClientFixture succeeded = streamClientFixture(
                Flux.just(streamResponse("fallback answer", Map.of())));
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(primary, fallback));
        when(clientFactory.create(any(), same(primary), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        failed.client(), primary, primaryContext, null));
        when(clientFactory.create(any(), same(fallback), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        succeeded.client(), fallback, fallbackContext, null));

        List<ChatEvent> events = service.stream(
                command(ChatMode.PLAIN, null)).collectList().block();

        assertTrue(events.stream().anyMatch(event ->
                event instanceof ChatEvent.ContentDelta delta
                        && "fallback answer".equals(delta.content())));
        assertTrue(events.stream().anyMatch(event ->
                event instanceof ChatEvent.Completed completed
                        && "fallback".equals(completed.resolvedModel())));
        verify(clientFactory).create(any(), same(primary), anyList());
        verify(clientFactory).create(any(), same(fallback), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void completedStreamingAttemptIsMarkedTerminalBeforeDiagnosticsPersist() {
        ChatModelRouter.ChatModelCandidate candidate =
                candidate("stream-model", true, false, false);
        RetrievalTraceSession traceSession = new RetrievalTraceSession(
                ChatPrincipal.local(), "CHAT", "session-1");
        String attemptKey = "stream-attempt";
        AuthorizedRetrievalContext context = new AuthorizedRetrievalContext(
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                traceSession.newAttemptCollector(attemptKey, 3, 3, 20),
                "session-1",
                ChatPrincipal.local());
        StreamClientFixture client = streamClientFixture(
                Flux.just(streamResponse("answer", Map.of())));
        RetrievalDiagnosticsService diagnosticsService =
                mock(RetrievalDiagnosticsService.class);
        service.setDiagnosticsService(diagnosticsService);
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(candidate));
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client.client(), candidate, context, null));

        service.stream(command(ChatMode.PLAIN, null)
                .withTraceSession(traceSession))
                .collectList()
                .block();

        ArgumentCaptor<RetrievalTraceSession> persisted =
                ArgumentCaptor.forClass(RetrievalTraceSession.class);
        verify(diagnosticsService).persist(persisted.capture());
        List<Map<String, Object>> attempts =
                (List<Map<String, Object>>) persisted.getValue()
                        .toMetadata(false)
                        .get("attempts");
        assertEquals("SUCCEEDED", attempts.getFirst().get("status"));
    }

    @Test
    void streamingDoesNotFallbackAfterContentWasExposedToTheClient() {
        ChatModelRouter.ChatModelCandidate primary =
                candidate("primary", true, false, false);
        ChatModelRouter.ChatModelCandidate fallback =
                candidate("fallback", true, false, false);
        AuthorizedRetrievalContext primaryContext = context();
        StreamClientFixture failedAfterContent = streamClientFixture(
                Flux.concat(
                        Flux.just(streamResponse("partial", Map.of())),
                        Flux.error(new IllegalStateException("late failure"))));
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(primary, fallback));
        when(clientFactory.create(any(), same(primary), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        failedAfterContent.client(), primary, primaryContext, null));

        List<ChatEvent> events = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        service.stream(command(ChatMode.PLAIN, null))
                .subscribe(events::add, failure::set);

        assertTrue(events.stream().anyMatch(event ->
                event instanceof ChatEvent.ContentDelta delta
                        && "partial".equals(delta.content())));
        assertEquals("late failure", failure.get().getMessage());
        verify(clientFactory).create(any(), same(primary), anyList());
        verify(clientFactory, never()).create(
                any(), same(fallback), anyList());
        verify(historyRepository, never()).save(
                anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void configuredCandidateChainSkipsUnavailableAndIneligibleModels() {
        ChatModelRouter.ChatModelCandidate noStreaming =
                candidate("no-streaming", false, false, false);
        ChatModelRouter.ChatModelCandidate fallback =
                candidate("fallback", true, false, false);
        AuthorizedRetrievalContext context = context();
        StreamClientFixture client = streamClientFixture(
                Flux.just(streamResponse("fallback answer", Map.of())));
        when(modelRouter.resolveCandidateRequired("missing"))
                .thenThrow(new IllegalArgumentException("not registered"));
        when(modelRouter.resolveCandidateRequired("no-streaming"))
                .thenReturn(noStreaming);
        when(modelRouter.resolveCandidateRequired("fallback"))
                .thenReturn(fallback);
        when(clientFactory.create(any(), same(fallback), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client.client(), fallback, context, null));
        ChatCommand base = command(ChatMode.PLAIN, "missing");
        ChatCommand command = new ChatCommand(
                base.message(),
                base.sessionId(),
                base.principal(),
                base.memoryConversationId(),
                base.mode(),
                base.memoryMode(),
                base.modelRef(),
                base.domainId(),
                base.retrievalScope(),
                base.retrievalOptions(),
                base.clientMetadata(),
                base.inputMessages(),
                List.of("missing", "no-streaming", "fallback"));

        List<ChatEvent> events = service.stream(command)
                .collectList()
                .block();

        assertTrue(events.stream().anyMatch(event ->
                event instanceof ChatEvent.Completed completed
                        && "fallback".equals(completed.resolvedModel())));
        verify(clientFactory, never()).create(
                any(), same(noStreaming), anyList());
        verify(clientFactory).create(any(), same(fallback), anyList());
    }

    private ChatCommand command(ChatMode mode, String modelRef) {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "问题",
                "session-1",
                principal,
                principal.memoryConversationId("session-1"),
                mode,
                MemoryMode.SERVER,
                modelRef,
                null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of("client", "test"));
    }

    private AuthorizedRetrievalContext context() {
        return new AuthorizedRetrievalContext(
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                new RetrievalTraceCollector(),
                "session-1",
                ChatPrincipal.local());
    }

    private RetrievalResult retrievalResult(String documentId, String title) {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(documentId);
        result.setChunkIndex(0);
        result.setTitle(title);
        result.setChunkText(title + " content");
        result.setScore(0.8);
        return result;
    }

    private ChatHistoryResponse history(
            String userMessage,
            String aiResponse,
            long id) {
        return new ChatHistoryResponse(
                id,
                "session-1",
                userMessage,
                aiResponse,
                null,
                Map.of(),
                LocalDateTime.now());
    }

    private ChatModelRouter.ChatModelCandidate candidate(
            String ref,
            boolean streaming,
            boolean toolCalling,
            boolean toolOptions) {
        ChatModel model = mock(ChatModel.class);
        if (toolOptions) {
            when(model.getDefaultOptions()).thenReturn(
                    ToolCallingChatOptions.builder().model(ref).build());
        }
        return new ChatModelRouter.ChatModelCandidate(
                ref,
                model,
                new MultiModelProperties.ModelCapabilities(
                        streaming,
                        toolCalling));
    }

    @SuppressWarnings("unchecked")
    private ClientFixture clientFixture(
            String answer,
            Map<String, Object> context) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        ChatClientResponse response = mock(ChatClientResponse.class);
        org.springframework.ai.chat.model.ChatResponse springResponse =
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage(answer))),
                        ChatResponseMetadata.builder().build());

        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.toolCallbacks(any(KnowledgeSearchTool.class))).thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.options(any(ToolCallingChatOptions.class))).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.chatClientResponse()).thenReturn(response);
        when(response.chatResponse()).thenReturn(springResponse);
        when(response.context()).thenReturn(context);
        return new ClientFixture(client, spec);
    }

    @SuppressWarnings("unchecked")
    private StreamClientFixture streamClientFixture(
            Flux<ChatClientResponse> responses) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream =
                mock(ChatClient.StreamResponseSpec.class);

        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.toolCallbacks(any(KnowledgeSearchTool.class))).thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.options(any(ToolCallingChatOptions.class))).thenReturn(spec);
        when(spec.stream()).thenReturn(stream);
        when(stream.chatClientResponse()).thenReturn(responses);
        return new StreamClientFixture(client, spec);
    }

    private ChatClientResponse streamResponse(
            String content,
            Map<String, Object> context) {
        return new ChatClientResponse(
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage(content))),
                        ChatResponseMetadata.builder().build()),
                context);
    }

    private record ClientFixture(
            ChatClient client,
            ChatClient.ChatClientRequestSpec spec) {
    }

    private record StreamClientFixture(
            ChatClient client,
            ChatClient.ChatClientRequestSpec spec) {
    }
}
