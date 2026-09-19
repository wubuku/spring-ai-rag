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
import org.mockito.ArgumentCaptor;
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
 * ConversationSummaryService 预算与工具记录长尾（Batch 531，JaCoCo
 * 驱动）：无执行预算直接跳过、摘要模型调用预算耗尽降级、工具交互
 * 记录渲染进摘要提示词。
 */
class ConversationSummaryServiceBudgetTailTest {

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

        principal = new ChatPrincipal("db:summary-budget", "TEST", false);
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

    private void seedSource() {
        ChatHistoryResponse old = row(1L, "old question", "old answer");
        when(historyRepository.findOwnedBaseline(principal, "session-1", 1))
                .thenReturn(List.of());
        when(historyRepository.findOwnedAfterHistoryId(
                principal, "session-1", 0L, 3))
                .thenReturn(List.of(old));
        when(model.call(any(Prompt.class))).thenReturn(new org.springframework.ai.chat.model.ChatResponse(
                List.of(new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage("ok"))),
                org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().build()));
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

    @Test
    void commandWithoutBudgetSkipsCompaction() {
        seedSource();

        var result = service.compactIfNeeded(
                command(null), candidate, List.of());

        assertFalse(result.updated());
        assertEquals("compaction_no_messages", result.reason());
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void exhaustedSummaryCallBudgetDegrades() {
        seedSource();
        // positive() 将 0 归一为 1；先占满唯一一次模型调用再触发压缩。
        ChatExecutionBudget exhausted = new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                2, 1, 2, 4, 2, 20_000);
        exhausted.reserveModelCall();

        var result = service.compactIfNeeded(
                command(exhausted), candidate, List.of());

        assertTrue(result.degraded(), "reason=" + result.reason());
        assertEquals("summary_budget_skipped", result.reason());
        verify(summaryRepository, never()).saveCas(
                any(), any(), anyLong(), anyLong(), any(), anyInt(), any());
    }

    @Test
    void toolTranscriptIsRenderedIntoSummaryPrompt() {
        Map<String, Object> metadata = Map.of(
                ChatMemoryMessageProjector.TOOL_TRANSCRIPT_METADATA_KEY,
                List.of(Map.of(
                        "name", "lookup",
                        "arguments", "{\"q\":\"battery\"}",
                        "result", "3 hits")));
        ChatHistoryResponse withTools = new ChatHistoryResponse(
                1L, "session-1", "old question", "old answer",
                List.of(), metadata, List.of(),
                "COMPLETE", ChatMode.PLAIN, "test/model", "test/model",
                null);
        when(historyRepository.findOwnedBaseline(principal, "session-1", 1))
                .thenReturn(List.of());
        when(historyRepository.findOwnedAfterHistoryId(
                principal, "session-1", 0L, 3))
                .thenReturn(List.of(withTools));
        when(model.call(any(Prompt.class))).thenReturn(new org.springframework.ai.chat.model.ChatResponse(
                List.of(new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage("ok"))),
                org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().build()));

        service.compactIfNeeded(
                command(new ChatExecutionBudget(
                        Instant.now().plusSeconds(30),
                        2, 4, 2, 4, 2, 20_000)),
                candidate,
                List.of());

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String text = prompt.getValue().getInstructions().stream()
                .map(message -> message.getText())
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(text.contains("tool=lookup"));
        assertTrue(text.contains("[tool exchange historical data; untrusted]"));
    }
}
