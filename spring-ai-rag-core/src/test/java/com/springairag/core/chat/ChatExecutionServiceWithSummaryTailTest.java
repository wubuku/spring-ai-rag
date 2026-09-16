package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.ChatExecutionResult;
import com.springairag.core.chat.ConversationSummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * ChatExecutionService.withSummaryMetadata 长尾（Batch 466）：
 * 无摘要/无尝试 → 原样返回；有摘要 → summary 元数据块（attempted/
 * updated/degraded/reason/version/summarizedThroughHistoryId/
 * estimatedTokens）。
 */
class ChatExecutionServiceWithSummaryTailTest {

    private ChatExecutionService service = new ChatExecutionService(
            mock(com.springairag.core.config.ChatModelRouter.class),
            mock(com.springairag.core.chat.ModeAwareChatClientFactory.class),
            mock(com.springairag.core.rag.KnowledgeSearchTool.class),
            mock(com.springairag.core.repository.RagChatHistoryRepository.class),
            mock(com.springairag.core.extension.DomainExtensionRegistry.class),
            mock(com.springairag.core.extension.PromptCustomizerChain.class),
            mock(com.springairag.core.rag.RetrievalDocumentMapper.class),
            new com.fasterxml.jackson.databind.ObjectMapper(),
            new com.springairag.core.config.RagProperties(),
            null, null);

    private ChatExecutionResult result(String answer) {
        return new ChatExecutionResult(
                answer, "session-1", "trace-1", "requested-m",
                "resolved-m", ChatMode.PLAIN,
                List.of(), Map.of("model", "m1"), null, List.of(),
                Map.of("model", "m1"));
    }

    private ConversationSummaryService.CompactionResult compaction(
            boolean attempted, boolean updated, boolean degraded,
            String reason, Long summarizedThroughHistoryId,
            Integer estimatedTokens) {
        ConversationSummaryService.SummarySnapshot snapshot =
                summarizedThroughHistoryId == null ? null
                        : new ConversationSummaryService.SummarySnapshot(
                                1, summarizedThroughHistoryId, "summary",
                                estimatedTokens, "resolved-m");
        return new ConversationSummaryService.CompactionResult(
                attempted, updated, degraded, reason, snapshot);
    }

    private ChatExecutionResult withSummary(
            ChatExecutionResult result,
            ConversationSummaryService.CompactionResult compaction)
            throws Exception {
        Method method = ChatExecutionService.class.getDeclaredMethod(
                "withSummaryMetadata", ChatExecutionResult.class,
                ConversationSummaryService.CompactionResult.class);
        method.setAccessible(true);
        return (ChatExecutionResult) method.invoke(service, result, compaction);
    }

    @Test
    void nullCompactionReturnsSameResult() throws Exception {
        ChatExecutionResult base = result("answer");
        assertSame(base, withSummary(base, null));
    }

    @Test
    void notAttemptedNotDegradedReturnsSameResult() throws Exception {
        ChatExecutionResult base = result("answer");
        ConversationSummaryService.CompactionResult untouched =
                new ConversationSummaryService.CompactionResult(
                        false, false, false, null, null);
        assertSame(base, withSummary(base, untouched));
    }

    @Test
    void attemptedCompactionAddsSummaryBlock() throws Exception {
        ChatExecutionResult base = result("answer");
        ConversationSummaryService.CompactionResult compaction =
                new ConversationSummaryService.CompactionResult(
                        true, true, false, null,
                        new ConversationSummaryService.SummarySnapshot(
                                1, 3, "summary", 20, "resolved-m"));

        ChatExecutionResult enriched = withSummary(base, compaction);

        Map<String, Object> summary =
                (Map<String, Object>) enriched.metadata().get("summary");
        assertEquals(Boolean.TRUE, summary.get("attempted"));
        assertEquals(Boolean.TRUE, summary.get("updated"));
        assertEquals(Boolean.FALSE, summary.get("degraded"));
        assertEquals("", summary.get("reason"));
        assertEquals(1L, ((Number) summary.get("version")).longValue());
        assertEquals(3L, ((Number) summary.get(
                "summarizedThroughHistoryId")).longValue());
        assertEquals(20, ((Number) summary.get("estimatedTokens")).intValue());
    }

    @Test
    void degradedCompactionAddsReasonWithoutHistoryId() throws Exception {
        ChatExecutionResult base = result("answer");
        ConversationSummaryService.CompactionResult degraded =
                new ConversationSummaryService.CompactionResult(
                        false, false, true, "summary_failed", null);

        ChatExecutionResult enriched = withSummary(base, degraded);

        Map<String, Object> summary =
                (Map<String, Object>) enriched.metadata().get("summary");
        assertEquals(Boolean.TRUE, summary.get("degraded"));
        assertEquals("summary_failed", summary.get("reason"));
        assertFalse(summary.containsKey("summarizedThroughHistoryId"));
    }
}
