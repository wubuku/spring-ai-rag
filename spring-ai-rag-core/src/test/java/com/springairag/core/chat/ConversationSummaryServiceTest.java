package com.springairag.core.chat;

import com.springairag.api.dto.ChatHistoryResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.repository.RagChatMemorySummaryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class ConversationSummaryServiceTest {

    private RagChatMemorySummaryRepository summaryRepository;
    private RagChatHistoryRepository historyRepository;
    private ChatModelRouter modelRouter;
    private RagProperties ragProperties;
    private ChatModel model;
    private ChatModelRouter.ChatModelCandidate candidate;
    private ConversationSummaryService service;
    private ChatPrincipal principal;

    @BeforeEach
    void setUp() {
        summaryRepository = mock(RagChatMemorySummaryRepository.class);
        historyRepository = mock(RagChatHistoryRepository.class);
        modelRouter = mock(ChatModelRouter.class);
        ragProperties = new RagProperties();
        com.springairag.core.config.RagChatProperties.ContextProperties context =
                ragProperties.getChat().getContext();
        context.setCompactionEnabled(true);
        context.setCompactionTriggerTokens(1);
        context.setCompactionMaxSourceTokens(4_000);
        context.setCompactionMaxOutputTokens(100);
        context.setCompactionMaxTurnsPerCall(2);
        context.setCompactionTimeoutMs(200);
        context.setMinimumRecentTurns(1);

        model = mock(ChatModel.class);
        candidate = new ChatModelRouter.ChatModelCandidate(
                "test/model",
                model,
                new MultiModelProperties.ModelCapabilities(true, false),
                8_192,
                512);
        when(modelRouter.resolveCandidateRequired("test/model"))
                .thenReturn(candidate);
        when(summaryRepository.find(any(), eq("session-1")))
                .thenReturn(Optional.empty());
        when(summaryRepository.saveCas(
                any(), eq("session-1"), anyLong(), anyLong(),
                any(), anyInt(), eq("test/model")))
                .thenReturn(true);

        principal = new ChatPrincipal("db:summary-test", "TEST", false);
        service = new ConversationSummaryService(
                summaryRepository,
                historyRepository,
                modelRouter,
                ragProperties);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void compactionUsesOldestRowsAndProtectsRecentTurn() {
        ChatHistoryResponse first = row(1L, "old question", "old answer");
        ChatHistoryResponse second = row(2L, "middle question", "middle answer");
        ChatHistoryResponse newest = row(3L, "newest question", "newest answer");
        when(historyRepository.findOwnedBaseline(principal, "session-1", 1))
                .thenReturn(List.of(newest));
        when(historyRepository.findOwnedAfterHistoryId(
                principal, "session-1", 0L, 3))
                .thenReturn(List.of(first, second, newest));
        when(model.call(any(Prompt.class))).thenReturn(response("durable summary"));

        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                2, 4, 2, 4, 2, 20_000);
        ConversationSummaryService.CompactionResult result =
                service.compactIfNeeded(
                        command(budget),
                        candidate,
                        List.of());

        assertTrue(result.updated());
        assertEquals(2L, result.snapshot().summarizedThroughHistoryId());
        assertTrue(result.snapshot().estimatedTokens() > 0);
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String source = prompt.getValue().getInstructions().stream()
                .map(message -> message.getText())
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(source.contains("old question"));
        assertTrue(source.contains("middle question"));
        assertFalse(source.contains("newest question"));
        verify(summaryRepository).saveCas(
                principal, "session-1", 0L, 2L,
                "durable summary",
                result.snapshot().estimatedTokens(),
                "test/model");
        assertEquals(1, budget.summaryCalls());
    }

    @Test
    void modelFailureDegradesWithoutPersistingSummary() {
        seedSource();
        when(model.call(any(Prompt.class)))
                .thenThrow(new IllegalStateException("provider down"));

        ConversationSummaryService.CompactionResult result =
                service.compactIfNeeded(command(budget()), candidate, List.of());

        assertTrue(result.degraded());
        assertEquals("summary_failed", result.reason());
        verify(summaryRepository, never()).saveCas(
                any(), any(), anyLong(), anyLong(), any(), anyInt(), any());
    }

    @Test
    void outputOverLimitDegradesWithoutPersistingTruncatedText() {
        seedSource();
        when(model.call(any(Prompt.class)))
                .thenReturn(response("x".repeat(2_000)));

        ConversationSummaryService.CompactionResult result =
                service.compactIfNeeded(command(budget()), candidate, List.of());

        assertTrue(result.degraded());
        assertEquals("summary_output_exceeded", result.reason());
        verify(summaryRepository, never()).saveCas(
                any(), any(), anyLong(), anyLong(), any(), anyInt(), any());
    }

    @Test
    void timeoutDegradesAndDoesNotBlockMainTurn() {
        seedSource();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(1_000);
            return response("late");
        });
        ragProperties.getChat().getContext().setCompactionTimeoutMs(20);

        ConversationSummaryService.CompactionResult result =
                service.compactIfNeeded(command(budget()), candidate, List.of());

        assertTrue(result.degraded());
        assertEquals("summary_timeout", result.reason());
        verify(summaryRepository, never()).saveCas(
                any(), any(), anyLong(), anyLong(), any(), anyInt(), any());
    }

    @Test
    void modelBudgetExhaustionIsRecordedAsBudgetSkip() {
        seedSource();
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                2, 1, 2, 4, 2, 20_000);
        budget.reserveModelCall();

        ConversationSummaryService.CompactionResult result =
                service.compactIfNeeded(command(budget), candidate, List.of());

        assertTrue(result.degraded());
        assertEquals("summary_budget_skipped", result.reason());
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void sourceRowsStopAtTokenBudgetWithoutAdvancingPastUnsummarizedRows() {
        RagChatProperties.ContextProperties context =
                ragProperties.getChat().getContext();
        JTokkitPromptTokenEstimator estimator =
                new JTokkitPromptTokenEstimator();
        int firstSourceTokens = estimator.estimate(
                "user: old question\nassistant: old answer\n");
        context.setCompactionMaxSourceTokens(firstSourceTokens);
        context.setCompactionTriggerTokens(1);

        ChatHistoryResponse first = row(1L, "old question", "old answer");
        ChatHistoryResponse second = row(2L, "middle question", "middle answer");
        ChatHistoryResponse newest = row(3L, "newest question", "newest answer");
        when(historyRepository.findOwnedBaseline(principal, "session-1", 1))
                .thenReturn(List.of(newest));
        when(historyRepository.findOwnedAfterHistoryId(
                principal, "session-1", 0L, 3))
                .thenReturn(List.of(first, second, newest));
        when(model.call(any(Prompt.class))).thenReturn(response("short summary"));

        ConversationSummaryService.CompactionResult result =
                service.compactIfNeeded(command(budget()), candidate, List.of());

        assertTrue(result.updated());
        assertEquals(1L, result.snapshot().summarizedThroughHistoryId());
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String source = prompt.getValue().getInstructions().stream()
                .map(message -> message.getText())
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(source.contains("old question"));
        assertFalse(source.contains("middle question"));
        assertFalse(source.contains("newest question"));
    }

    private void seedSource() {
        ChatHistoryResponse first = row(1L, "old question", "old answer");
        ChatHistoryResponse second = row(2L, "middle question", "middle answer");
        ChatHistoryResponse newest = row(3L, "newest question", "newest answer");
        when(historyRepository.findOwnedBaseline(principal, "session-1", 1))
                .thenReturn(List.of(newest));
        when(historyRepository.findOwnedAfterHistoryId(
                principal, "session-1", 0L, 3))
                .thenReturn(List.of(first, second, newest));
    }

    private ChatExecutionBudget budget() {
        return new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                2, 4, 2, 4, 2, 20_000);
    }

    private ChatCommand command(ChatExecutionBudget budget) {
        return new ChatCommand(
                "current question",
                "session-1",
                principal,
                principal.memoryConversationId("session-1"),
                ChatMode.PLAIN,
                MemoryMode.SERVER,
                "test/model",
                null,
                com.springairag.core.retrieval.RetrievalScope.unscoped(),
                new RetrievalOptions(1, 0.0, false, false, 0.5, 0.5),
                Map.of()).withExecutionBudget(budget);
    }

    private ChatHistoryResponse row(long id, String question, String answer) {
        return new ChatHistoryResponse(
                id,
                "session-1",
                question,
                answer,
                List.of(),
                Map.of(),
                List.of(),
                "COMPLETE",
                ChatMode.PLAIN,
                "test/model",
                "test/model",
                LocalDateTime.now());
    }

    private ChatResponse response(String text) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().build());
    }

}
