package com.springairag.core.chat;

import com.springairag.api.dto.ChatHistoryResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.repository.RagChatMemorySummaryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConversationSummaryService 降级路径长尾（Batch 579，JaCoCo 驱
 * 动）：模型解析失败降级 summary_model_unavailable、模型返回空白降
 * 级 summary_empty、输出超限降级 summary_output_exceeded、CAS 冲突
 * 降级 summary_cas_conflict、压缩开关关闭短路 compaction_disabled、
 * 成功路径修剪答案并落库。
 */
class ConversationSummaryServiceDegradePathTailTest {

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

        principal = new ChatPrincipal("db:degrade-tail", "TEST", false);
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

    private ChatCommand command(ChatExecutionBudget budget) {
        ChatCommand command = new ChatCommand(
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
                Map.of());
        return budget == null ? command : command.withExecutionBudget(budget);
    }

    private ChatExecutionBudget budget() {
        return new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                2, 4, 2, 4, 2, 20_000);
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
                null);
    }

    private void stubSource() {
        ChatHistoryResponse old = row(1L, "old question", "old answer");
        when(historyRepository.findOwnedBaseline(principal, "session-1", 1))
                .thenReturn(List.of());
        when(historyRepository.findOwnedAfterHistoryId(
                principal, "session-1", 0L, 3))
                .thenReturn(List.of(old));
    }

    private void stubModelAnswer(String answer) {
        when(model.call(any(Prompt.class))).thenReturn(new org.springframework.ai.chat.model.ChatResponse(
                List.of(new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage(answer))),
                org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().build()));
    }

    @Test
    void modelResolutionFailureDegradesAsModelUnavailable() {
        stubSource();
        var brokenRouter = mock(ChatModelRouter.class);
        when(brokenRouter.resolveCandidateRequired("test/model"))
                .thenThrow(new IllegalStateException("router offline"));
        var brokenService = new ConversationSummaryService(
                summaryRepository,
                historyRepository,
                brokenRouter,
                ragProperties);

        var result = brokenService.compactIfNeeded(
                command(budget()), candidate, List.of());

        assertTrue(result.degraded());
        assertEquals("summary_model_unavailable", result.reason());
    }

    @Test
    void blankModelAnswerDegradesAsSummaryEmpty() {
        stubSource();
        stubModelAnswer("   ");

        var result = service.compactIfNeeded(command(budget()), candidate,
                List.of());

        assertTrue(result.degraded());
        assertEquals("summary_empty", result.reason());
        verify(summaryRepository, never()).saveCas(
                any(), any(), anyLong(), anyLong(), any(), anyInt(), any());
    }

    @Test
    void oversizedModelAnswerDegradesAsOutputExceeded() {
        stubSource();
        ragProperties.getChat().getContext().setCompactionMaxOutputTokens(5);
        stubModelAnswer("x".repeat(200));

        var result = service.compactIfNeeded(command(budget()), candidate,
                List.of());

        assertTrue(result.degraded());
        assertEquals("summary_output_exceeded", result.reason());
        verify(summaryRepository, never()).saveCas(
                any(), any(), anyLong(), anyLong(), any(), anyInt(), any());
    }

    @Test
    void casConflictDegradesAsCasConflict() {
        stubSource();
        stubModelAnswer("ok");
        when(summaryRepository.saveCas(
                any(), eq("session-1"), anyLong(), anyLong(),
                any(), anyInt(), eq("test/model")))
                .thenReturn(false);

        var result = service.compactIfNeeded(command(budget()), candidate,
                List.of());

        assertTrue(result.degraded());
        assertEquals("summary_cas_conflict", result.reason());
    }

    @Test
    void compactionDisabledShortCircuits() {
        ragProperties.getChat().getContext().setCompactionEnabled(false);

        var result = service.compactIfNeeded(command(budget()), candidate,
                List.of());

        assertFalse(result.updated());
        assertEquals("compaction_disabled", result.reason());
    }

    @Test
    void successfulCompactionTrimsAnswerAndPersists() {
        stubSource();
        stubModelAnswer("  ok summary  ");

        var result = service.compactIfNeeded(command(budget()), candidate,
                List.of());

        assertTrue(result.updated());
        assertEquals("ok summary", result.snapshot().text());
    }
}
