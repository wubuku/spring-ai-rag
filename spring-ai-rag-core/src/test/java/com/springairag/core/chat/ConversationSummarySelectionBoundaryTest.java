package com.springairag.core.chat;

import com.springairag.api.dto.ChatHistoryResponse;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.MemoryMode;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.repository.RagChatMemorySummaryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;

import java.time.LocalDateTime;
import java.time.Instant;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * selectSourceRows 的选择边界（经 compactIfNeeded 间接驱动）：单次
 * 调用的最大轮数上限、最近受保护轮次的排除、非 COMPLETE 状态行跳
 * 过、以及已有摘要游标之后继续推进。
 */
class ConversationSummarySelectionBoundaryTest {

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
        RagChatProperties.ContextProperties context =
                ragProperties.getChat().getContext();
        context.setCompactionEnabled(true);
        context.setCompactionTriggerTokens(1);
        context.setCompactionMaxSourceTokens(4_000);
        context.setCompactionMaxOutputTokens(100);
        context.setCompactionMaxTurnsPerCall(2);
        // 单类运行时首调需冷加载大量类：放宽超时预算。
        context.setCompactionTimeoutMs(5_000);
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
                RetrievalScope.unscoped(),
                new RetrievalOptions(1, 0.0, false, false, 0.5, 0.5),
                Map.of()).withExecutionBudget(budget);
    }

    private ChatHistoryResponse row(long id, String question, String answer) {
        return row(id, question, answer, "COMPLETE");
    }

    private ChatHistoryResponse row(
            long id, String question, String answer, String status) {
        return new ChatHistoryResponse(
                id,
                "session-1",
                question,
                answer,
                List.of(),
                Map.of(),
                List.of(),
                status,
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

    private String capturedSource() {
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        return prompt.getValue().getInstructions().stream()
                .map(message -> message.getText())
                .reduce("", (left, right) -> left + "\n" + right);
    }

    @Test
    void maxTurnsPerCallCapsSelectedRows() {
        when(historyRepository.findOwnedBaseline(principal, "session-1", 1))
                .thenReturn(List.of(row(3L, "newest q", "newest a")));
        when(historyRepository.findOwnedAfterHistoryId(
                eq(principal), eq("session-1"), eq(0L), eq(3)))
                .thenReturn(List.of(
                        row(1L, "first q", "first a"),
                        row(2L, "second q", "second a"),
                        row(3L, "newest q", "newest a")));
        when(model.call(any(Prompt.class))).thenReturn(response("summary"));

        ConversationSummaryService.CompactionResult result =
                service.compactIfNeeded(command(budget()), candidate, List.of());

        assertTrue(result.updated());
        // 单次调用最多 2 轮：第 3 行留待下一次推进。
        assertEquals(2L, result.snapshot().summarizedThroughHistoryId());
        String source = capturedSource();
        assertTrue(source.contains("first q"));
        assertTrue(source.contains("second q"), () -> source);
        assertFalse(source.contains("newest q"));
    }

    @Test
    void protectedRecentTurnIsExcludedFromSelection() {
        when(historyRepository.findOwnedBaseline(principal, "session-1", 1))
                .thenReturn(List.of(row(3L, "newest q", "newest a")));
        when(historyRepository.findOwnedAfterHistoryId(
                eq(principal), eq("session-1"), eq(0L), eq(3)))
                .thenReturn(List.of(
                        row(1L, "old q", "old a"),
                        row(3L, "newest q", "newest a")));
        when(model.call(any(Prompt.class))).thenReturn(response("summary"));

        ConversationSummaryService.CompactionResult result =
                service.compactIfNeeded(command(budget()), candidate, List.of());

        assertTrue(result.updated());
        String source = capturedSource();
        assertTrue(source.contains("old q"));
        assertFalse(source.contains("newest q"));
    }

    @Test
    void nonCompleteRowsAreSkippedDuringSelection() {
        when(historyRepository.findOwnedBaseline(principal, "session-1", 1))
                .thenReturn(List.of(row(3L, "newest q", "newest a")));
        when(historyRepository.findOwnedAfterHistoryId(
                eq(principal), eq("session-1"), eq(0L), eq(3)))
                .thenReturn(List.of(
                        row(1L, "old q", "old a"),
                        row(2L, "pending q", "pending a", "PROCESSING"),
                        row(3L, "newest q", "newest a")));
        when(model.call(any(Prompt.class))).thenReturn(response("summary"));

        ConversationSummaryService.CompactionResult result =
                service.compactIfNeeded(command(budget()), candidate, List.of());

        assertTrue(result.updated());
        String source = capturedSource();
        assertTrue(source.contains("old q"));
        assertFalse(source.contains("pending q"));
        assertFalse(source.contains("newest q"));
    }

    @Test
    void selectionContinuesAfterExistingSummaryCursor() {
        when(summaryRepository.find(any(), eq("session-1")))
                .thenReturn(Optional.of(
                        new RagChatMemorySummaryRepository.SummaryRow(
                                4, 1, "prior summary", "test/model", 64,
                                Instant.now())));
        when(historyRepository.findOwnedBaseline(principal, "session-1", 1))
                .thenReturn(List.of(row(3L, "newest q", "newest a")));
        // 游标为 1：只读取 id > 1 的行。
        when(historyRepository.findOwnedAfterHistoryId(
                eq(principal), eq("session-1"), eq(1L), eq(3)))
                .thenReturn(List.of(
                        row(2L, "second q", "second a"),
                        row(3L, "newest q", "newest a")));
        when(model.call(any(Prompt.class))).thenReturn(response("summary"));

        ConversationSummaryService.CompactionResult result =
                service.compactIfNeeded(command(budget()), candidate, List.of());

        assertTrue(result.updated());
        assertEquals(5, result.snapshot().version());
        // 最近 1 轮受保护：游标推进到第 2 行为止。
        assertEquals(2L, result.snapshot().summarizedThroughHistoryId());
        String source = capturedSource();
        assertTrue(source.contains("prior summary"));
        assertTrue(source.contains("second q"));
        assertFalse(source.contains("newest q"));
    }
}
