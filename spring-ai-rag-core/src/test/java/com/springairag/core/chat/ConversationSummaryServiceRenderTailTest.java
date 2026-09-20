package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatHistoryResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.repository.RagChatMemorySummaryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConversationSummaryService 渲染与中断长尾（Batch 547，JaCoCo 驱
 * 动）：source 超限跳过、candidate 缺 contextWindow/maxTokens 回退、
 * future 超时预算耗尽前中断、ExecutionException 非 Runtime 原因包
 * 装、工具记录元数据缺失/非 Map 条目/超长名称截断、空消息文本回退。
 */
class ConversationSummaryServiceRenderTailTest {

    private RagChatMemorySummaryRepository summaryRepository;
    private RagChatHistoryRepository historyRepository;
    private ChatModel model;
    private RagProperties ragProperties;
    private ChatModelRouter.ChatModelCandidate candidate;
    private ConversationSummaryService service;
    private ChatPrincipal principal;

    @BeforeEach
    void setUp() {
        summaryRepository = mock(RagChatMemorySummaryRepository.class);
        historyRepository = mock(RagChatHistoryRepository.class);
        var modelRouter = mock(ChatModelRouter.class);
        ragProperties = new RagProperties();
        var context = ragProperties.getChat().getContext();
        context.setCompactionEnabled(true);
        context.setCompactionTriggerTokens(1);
        context.setCompactionMaxSourceTokens(4_000);
        context.setCompactionMaxOutputTokens(100);
        context.setCompactionMaxTurnsPerCall(2);
        context.setCompactionTimeoutMs(30_000);
        context.setMinimumRecentTurns(1);

        model = mock(ChatModel.class);
        candidate = new ChatModelRouter.ChatModelCandidate(
                "test/model", model,
                new MultiModelProperties.ModelCapabilities(true, false));
        when(modelRouter.resolveCandidateRequired("test/model"))
                .thenReturn(candidate);
        when(summaryRepository.find(any(), eq("session-1")))
                .thenReturn(Optional.empty());
        when(summaryRepository.saveCas(
                any(), eq("session-1"), anyLong(), anyLong(),
                any(), anyInt(), eq("test/model")))
                .thenReturn(true);

        principal = new ChatPrincipal("db:render-tail", "TEST", false);
        service = new ConversationSummaryService(
                summaryRepository,
                historyRepository,
                modelRouter,
                ragProperties);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        Thread.interrupted();
    }

    private ChatCommand command(ChatExecutionBudget budget) {
        ChatCommand command = new ChatCommand(
                "current question", "session-1", principal,
                principal.memoryConversationId("session-1"),
                ChatMode.PLAIN, MemoryMode.SERVER, "test/model", null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(1, 0.0, false, false, 0.5, 0.5),
                Map.of());
        return budget == null ? command : command.withExecutionBudget(budget);
    }

    private ChatExecutionBudget budget() {
        return new ChatExecutionBudget(
                Instant.now().plusSeconds(30), 2, 4, 2, 4, 2, 20_000);
    }

    private ChatHistoryResponse row(long id, String question, String answer,
                                    Map<String, Object> metadata) {
        return new ChatHistoryResponse(
                id, "session-1", question, answer,
                List.of(), metadata, List.of(),
                "COMPLETE", ChatMode.PLAIN, "test/model", "test/model",
                null);
    }

    private void stubBaseline(List<ChatHistoryResponse> rows) {
        when(historyRepository.findOwnedBaseline(principal, "session-1", 1))
                .thenReturn(List.of());
        when(historyRepository.findOwnedAfterHistoryId(
                principal, "session-1", 0L, 3))
                .thenReturn(rows);
    }

    private void stubModelSuccess(String answer) {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(answer))),
                ChatResponseMetadata.builder().build()));
    }

    @Test
    void sourceBudgetAccountsForPreviousSummaryWhenSelecting() {
        // 选择预算与最终渲染使用同一 render+estimator，总量恒不超限；
        // 原compaction_source_limit_exceeded 分支为不可达死代码已删除。
        // 此用例锁定"上一轮摘要计入选择预算"的语义：预算耗尽 → 选择
        // 为空 → compaction_source_empty。
        when(summaryRepository.find(any(), eq("session-1")))
                .thenReturn(Optional.of(new RagChatMemorySummaryRepository
                        .SummaryRow(
                        1L, 0L, "previous summary text", "test/model",
                        50, null)));
        stubBaseline(List.of(row(1L, "q", "answer", Map.of())));
        ragProperties.getChat().getContext().setCompactionMaxSourceTokens(10);

        var result = service.compactIfNeeded(command(budget()), candidate,
                List.of());

        assertFalse(result.updated());
        assertEquals("compaction_source_empty", result.reason());
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void candidateWithoutLimitsUsesConfiguredFallbacks() {
        candidate = new ChatModelRouter.ChatModelCandidate(
                "test/model", model,
                new MultiModelProperties.ModelCapabilities(true, false));
        stubBaseline(List.of(row(1L, "q", "answer", Map.of())));
        stubModelSuccess("ok");

        var result = service.compactIfNeeded(command(budget()), candidate,
                List.of());

        assertTrue(result.updated());
    }

    @Test
    void modelRuntimeFailureDegradesAsSummaryFailed() {
        stubBaseline(List.of(row(1L, "q", "a", Map.of())));
        when(model.call(any(Prompt.class)))
                .thenThrow(new IllegalStateException("provider gone"));

        var result = service.compactIfNeeded(command(budget()), candidate,
                List.of());

        assertTrue(result.degraded());
        assertEquals("summary_failed", result.reason());
        verify(summaryRepository, never()).saveCas(
                any(), any(), anyLong(), anyLong(), any(), anyInt(), any());
    }

    @Test
    void checkedFailureCauseIsWrappedAndDegrades() throws Exception {
        stubBaseline(List.of(row(1L, "q", "a", Map.of())));
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            throw new java.io.IOException("socket closed");
        });

        var result = service.compactIfNeeded(command(budget()), candidate,
                List.of());

        assertTrue(result.degraded());
        assertEquals("summary_failed", result.reason());
    }

    @Test
    void interruptedFutureDegradesAsSummaryFailed() throws Exception {
        var blocking = new CountDownLatch(1);
        stubBaseline(List.of(row(1L, "q", "a", Map.of())));
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            blocking.await(10, TimeUnit.SECONDS);
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage("late"))),
                    ChatResponseMetadata.builder().build());
        });

        Thread.currentThread().interrupt();
        try {
            var result = service.compactIfNeeded(
                    command(budget()), candidate, List.of());
            assertTrue(result.degraded());
            assertEquals("summary_failed", result.reason());
        } finally {
            blocking.countDown();
        }
    }

    @Test
    void rowsWithoutMetadataRenderNoToolTranscript() {
        stubBaseline(List.of(row(1L, "q", "a", Map.of())));
        stubModelSuccess("ok");

        var result = service.compactIfNeeded(command(budget()), candidate,
                List.of());

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String text = prompt.getValue().getInstructions().stream()
                .map(m -> m.getText())
                .reduce("", (l, r) -> l + "\n" + r);
        assertFalse(text.contains("tool exchange historical data"));
        assertTrue(result.updated());
    }

    @Test
    void toolTranscriptSkipsNonMapEntriesAndTruncatesLongNames() {
        Map<String, Object> metadata = Map.of(
                ChatMemoryMessageProjector.TOOL_TRANSCRIPT_METADATA_KEY,
                List.of(
                        "plain-string-entry",
                        Map.of("name", "n".repeat(200),
                                "arguments", "args",
                                "result", "res")));
        stubBaseline(List.of(new ChatHistoryResponse(
                1L, "session-1", "q", "a",
                List.of(), metadata, List.of(),
                "COMPLETE", ChatMode.PLAIN, "test/model", "test/model",
                null)));
        stubModelSuccess("ok");

        service.compactIfNeeded(command(budget()), candidate, List.of());

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String text = prompt.getValue().getInstructions().stream()
                .map(m -> m.getText())
                .reduce("", (l, r) -> l + "\n" + r);
        // 非 Map 条目被跳过；超长名称截断到 128。
        assertTrue(text.contains("tool=n"
                + "n".repeat(126)));
        assertFalse(text.contains("n".repeat(200)));
    }

    @Test
    void nullAnswerRendersEmptyTextFallback() {
        stubBaseline(List.of(new ChatHistoryResponse(
                1L, "session-1", "q", null,
                List.of(), Map.of(), List.of(),
                "COMPLETE", ChatMode.PLAIN, "test/model", "test/model",
                null)));
        stubModelSuccess("ok");

        var result = service.compactIfNeeded(command(budget()), candidate,
                List.of());

        assertTrue(result.updated());
    }

    @Test
    void summaryModelReturningNullDegradesAsEmpty() {
        stubBaseline(List.of(row(1L, "q", "a", Map.of())));
        when(model.call(any(Prompt.class))).thenReturn(null);

        var result = service.compactIfNeeded(command(budget()), candidate,
                List.of());

        assertTrue(result.degraded());
        assertEquals("summary_empty", result.reason());
    }
}
