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
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.chat.ChatMemoryMessageProjector;
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
        ragProperties.getChat().getContext().setCompactionTimeoutMs(2_000);
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
    void statelessRequestsNeverReadOrWriteDurableSummary() {
        seedSource();
        ChatExecutionBudget budget = budget();

        ConversationSummaryService.CompactionResult result =
                service.compactIfNeeded(
                        command(budget, MemoryMode.STATELESS),
                        candidate,
                        List.of());

        assertFalse(result.attempted());
        assertFalse(result.updated());
        assertEquals("compaction_stateless", result.reason());
        verify(historyRepository, never()).findOwnedBaseline(
                any(), any(), anyInt());
        verify(historyRepository, never()).findOwnedAfterHistoryId(
                any(), any(), anyLong(), anyInt());
        verify(model, never()).call(any(Prompt.class));
        verify(summaryRepository, never()).saveCas(
                any(), any(), anyLong(), anyLong(), any(), anyInt(), any());
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
        return command(budget, MemoryMode.SERVER);
    }

    private ChatCommand command(
            ChatExecutionBudget budget,
            MemoryMode memoryMode) {
        return new ChatCommand(
                "current question",
                "session-1",
                principal,
                principal.memoryConversationId("session-1"),
                ChatMode.PLAIN,
                memoryMode,
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

    @Test
    void loadMapsTheSummaryRowIntoASnapshot() {
        when(summaryRepository.find(any(), eq("session-1"))).thenReturn(
                Optional.of(new RagChatMemorySummaryRepository.SummaryRow(
                        4, 21, "summary text", "test/model", 64,
                        LocalDateTime.now().toInstant(java.time.ZoneOffset.UTC))));

        Optional<ConversationSummaryService.SummarySnapshot> snapshot =
                service.load(principal, "session-1");

        assertTrue(snapshot.isPresent());
        assertEquals(4, snapshot.get().version());
        assertEquals(21, snapshot.get().summarizedThroughHistoryId());
        assertEquals("summary text", snapshot.get().text());
        assertEquals(64, snapshot.get().estimatedTokens());
        assertEquals("test/model", snapshot.get().modelRef());
    }

    @Test
    void loadReturnsEmptyWhenNoSummaryExists() {
        assertTrue(service.load(principal, "session-1").isEmpty());
    }

    @Test
    void promptTextHandlesNullEmptyAndBlankSummaries() {
        assertEquals("", service.promptText(null));
        assertEquals("", service.promptText(Optional.empty()));
        assertEquals("", service.promptText(Optional.of(
                new ConversationSummaryService.SummarySnapshot(1, 1, "   ", 1, "m"))));
    }

    @Test
    void promptTextWrapsNonBlankSummaryTextWithPrefixAndSuffix() {
        String text = service.promptText(Optional.of(
                new ConversationSummaryService.SummarySnapshot(1, 1, "  keeps goals  ", 8, "m")));

        // 摘要正文被 trim 且夹在前缀与后缀之间（不在开头或结尾）。
        assertTrue(text.contains("keeps goals"));
        assertTrue(text.indexOf("keeps goals") > 0);
        assertTrue(text.length() > "keeps goals".length());
        // 相同输入输出稳定。
        assertEquals(text, service.promptText(Optional.of(
                new ConversationSummaryService.SummarySnapshot(1, 1, "keeps goals", 8, "m"))));
    }

    @Test
    void clearDelegatesToTheSummaryRepository() {
        when(summaryRepository.delete(any(), eq("session-1"))).thenReturn(1);

        assertEquals(1, service.clear(principal, "session-1"));
        verify(summaryRepository).delete(principal, "session-1");
    }

    // ==================== Batch 283：守卫/降级链与工具转写 ====================

    private void seedCompactionSource() {
        when(historyRepository.findOwnedBaseline(principal, "session-1", 1))
                .thenReturn(List.of(row(3L, "newest q", "newest a")));
        when(historyRepository.findOwnedAfterHistoryId(
                principal, "session-1", 0L, 3))
                .thenReturn(List.of(row(1L, "old q", "old a")));
        when(model.call(any(Prompt.class))).thenReturn(response("summary"));
    }

    @Test
    void compactionDisabledSkips() {
        ragProperties.getChat().getContext().setCompactionEnabled(false);
        seedCompactionSource();

        var result = service.compactIfNeeded(
                command(budget()), candidate, List.of());

        assertFalse(result.updated());
        assertEquals("compaction_disabled", result.reason());
    }

    @Test
    void nullExecutionBudgetSkips() {
        seedCompactionSource();

        var result = service.compactIfNeeded(
                command(null), candidate, List.of());

        assertEquals("compaction_no_messages", result.reason());
    }

    @Test
    void emptySourceRowsSkip() {
        when(historyRepository.findOwnedBaseline(principal, "session-1", 1))
                .thenReturn(List.of());
        when(historyRepository.findOwnedAfterHistoryId(
                principal, "session-1", 0L, 3))
                .thenReturn(List.of());

        var result = service.compactIfNeeded(
                command(budget()), candidate, List.of());

        assertEquals("compaction_source_empty", result.reason());
    }

    @Test
    void triggerNotReachedSkips() {
        ragProperties.getChat().getContext()
                .setCompactionTriggerTokens(1_000_000);
        seedCompactionSource();

        var result = service.compactIfNeeded(
                command(budget()), candidate, List.of());

        assertEquals("compaction_trigger_not_reached", result.reason());
    }

    @Test
    void cursorAlreadyCurrentSkips() {
        // 台账游标 5：候选行 1、2 全部已被摘要覆盖。
        when(summaryRepository.find(any(), eq("session-1")))
                .thenReturn(Optional.of(new RagChatMemorySummaryRepository
                        .SummaryRow(1L, 5L, "existing", "test/model", 10,
                                java.time.Instant.now())));
        when(historyRepository.findOwnedBaseline(principal, "session-1", 1))
                .thenReturn(List.of(row(3L, "newest q", "newest a")));
        when(historyRepository.findOwnedAfterHistoryId(
                principal, "session-1", 5L, 3))
                .thenReturn(List.of(row(1L, "old q", "old a"),
                        row(2L, "middle q", "middle a")));

        var result = service.compactIfNeeded(
                command(budget()), candidate, List.of());

        assertEquals("compaction_cursor_current", result.reason());
    }

    @Test
    void summaryModelUnavailableDegrades() {
        seedCompactionSource();
        ragProperties.getChat().getContext().setCompactionModel("missing/model");
        when(modelRouter.resolveCandidateRequired("missing/model"))
                .thenThrow(new IllegalStateException("no such model"));

        var result = service.compactIfNeeded(
                command(budget()), candidate, List.of());

        assertEquals("summary_model_unavailable", result.reason());
    }

    @Test
    void contextBudgetExceededDegrades() {
        seedCompactionSource();
        when(model.call(any(Prompt.class)))
                .thenThrow(new RagException(
                        ErrorCode.CHAT_CONTEXT_BUDGET_EXCEEDED, "too long"));

        var result = service.compactIfNeeded(
                command(budget()), candidate, List.of());

        assertEquals("summary_context_budget_exceeded", result.reason());
    }

    @Test
    void blankSummaryDegrades() {
        ragProperties.getChat().getContext().setCompactionTimeoutMs(5_000);
        seedCompactionSource();
        when(model.call(any(Prompt.class))).thenReturn(response("   "));

        var result = service.compactIfNeeded(
                command(budget()), candidate, List.of());

        assertEquals("summary_empty", result.reason());
    }

    @Test
    void casConflictDegrades() {
        seedCompactionSource();
        when(summaryRepository.saveCas(
                any(), eq("session-1"), anyLong(), anyLong(),
                any(), anyInt(), eq("test/model"))).thenReturn(false);

        var result = service.compactIfNeeded(
                command(budget()), candidate, List.of());

        assertEquals("summary_cas_conflict", result.reason());
    }

    @Test
    void toolTranscriptIsRenderedBoundedAndSkipsNonMapEntries() {
        ragProperties.getChat().getContext().setCompactionTimeoutMs(5_000);
        Map<String, Object> toolEntry = new java.util.HashMap<>();
        toolEntry.put("name", "search");
        toolEntry.put("arguments", "{\"q\":\"rag\"}");
        toolEntry.put("result", "3 hits");
        List<Object> entries = new java.util.ArrayList<>();
        entries.add(toolEntry);
        entries.add("not-a-map");
        for (int i = 1; i <= 6; i++) {
            Map<String, Object> filler = new java.util.HashMap<>();
            filler.put("name", "tail-" + i);
            filler.put("arguments", "x".repeat(1_000));
            entries.add(filler);
        }
        ChatHistoryResponse toolRow = new ChatHistoryResponse(
                1L, "session-1", "old q", "old a",
                List.of(),
                Map.of(ChatMemoryMessageProjector.TOOL_TRANSCRIPT_METADATA_KEY,
                        entries),
                List.of(),
                "COMPLETE", ChatMode.PLAIN, "test/model", "test/model",
                LocalDateTime.now());
        when(historyRepository.findOwnedBaseline(principal, "session-1", 1))
                .thenReturn(List.of(row(3L, "newest q", "newest a")));
        when(historyRepository.findOwnedAfterHistoryId(
                principal, "session-1", 0L, 3))
                .thenReturn(List.of(toolRow));
        when(model.call(any(Prompt.class))).thenReturn(response("summary"));

        var result = service.compactIfNeeded(
                command(budget()), candidate, List.of());

        assertTrue(result.updated());
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String source = prompt.getValue().getInstructions().stream()
                .map(message -> message.getText())
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(source.contains("[tool exchange historical data; untrusted]"));
        assertTrue(source.contains("tool=search"));
        assertTrue(source.contains("[/tool exchange historical data]"));
        // 4096 累计上界截断：超出预算的尾部条目不进入转写。
        assertFalse(source.contains("tool=tail-6"));
    }
}
