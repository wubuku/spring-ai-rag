package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ConversationPromptPlanner 预算边界长尾（Batch 517，JaCoCo 驱动）：
 * 非正上下文窗口、summary 裁剪/丢弃、历史截断标记、null 消息分组、
 * 关闭自适应时的模式证据目标、fitText 零上限路径。
 */
class ConversationPromptPlannerBudgetTailTest {

    private static final PromptTokenEstimator LENGTH_ESTIMATOR =
            text -> text == null ? 0 : text.length();

    @Test
    void nonPositiveContextWindowIsRejected() {
        var error = assertThrows(RagException.class, () ->
                planner(properties()).plan(
                        candidate(0, 10),
                        command(ChatMode.PLAIN),
                        "m",
                        null,
                        "",
                        List.of()));

        assertEquals(ErrorCode.CHAT_CONTEXT_BUDGET_EXCEEDED.name(),
                error.getErrorCode());
        assertEquals("model context window is not positive", error.getMessage());
    }

    @Test
    void oversizedSummaryIsOmittedWithDegradedMarker() {
        RagChatProperties properties = properties();
        // window 26 - outputReserve 10 - safety 5 - mandatory 6 = remaining 5，
        // summary 6 token 放不下 → 整体丢弃。
        properties.getContext().setFallbackContextWindow(26);
        properties.getContext().setOutputReserveTokens(10);
        properties.getContext().setSafetyMarginTokens(5);
        properties.getContext().setMinimumModeEvidenceTokens(0);
        properties.getContext().setMinimumRecentTurns(0);
        properties.getContext().setMaxSummaryTokens(100);

        ConversationPromptPlan plan = planner(properties).plan(
                candidate(26, 10),
                command(ChatMode.PLAIN),
                "system",
                null,
                "123456",
                List.of());

        assertEquals(0, plan.summaryTokens());
        assertEquals("", plan.selectedSummary());
        assertTrue(plan.degradedReasons().contains("summary_omitted"));
    }

    @Test
    void historyThatCannotFitYieldsTruncationAndOmissionMarkers() {
        RagChatProperties properties = properties();
        // window 23 - outputReserve 10 - safety 5 - mandatory 7 = remaining 1，
        // 最近一轮 2 token 放不下 → history_truncated + recent_history_omitted。
        properties.getContext().setFallbackContextWindow(23);
        properties.getContext().setOutputReserveTokens(10);
        properties.getContext().setSafetyMarginTokens(5);
        properties.getContext().setMinimumModeEvidenceTokens(0);
        properties.getContext().setMinimumRecentTurns(1);
        properties.getContext().setMaxHistoryTokens(100);
        properties.getContext().setMaxSummaryTokens(0);

        ConversationPromptPlan plan = planner(properties).plan(
                candidate(23, 10),
                command(ChatMode.PLAIN),
                "systemX",
                List.of(new UserMessage("u1")),
                "",
                List.of());

        assertTrue(plan.degradedReasons().contains("history_truncated"));
        assertTrue(plan.degradedReasons().contains("recent_history_omitted"));
        assertTrue(plan.selectedRecentMessages().isEmpty());
        assertEquals(0, plan.recentHistoryTokens());
    }

    @Test
    void recentTurnsStopBeforeMinimumIsReached() {
        RagChatProperties properties = properties();
        // window 25 - outputReserve 10 - safety 5 - mandatory 1 = remaining 9：
        // [u3](2)+[u2,a2](4) 放得下，[u1,a1](4) 放不下 → 2/3 轮即截断。
        properties.getContext().setFallbackContextWindow(25);
        properties.getContext().setOutputReserveTokens(10);
        properties.getContext().setSafetyMarginTokens(5);
        properties.getContext().setMinimumModeEvidenceTokens(0);
        properties.getContext().setMinimumRecentTurns(3);
        properties.getContext().setMaxHistoryTokens(100);
        properties.getContext().setMaxSummaryTokens(0);

        ConversationPromptPlan plan = planner(properties).plan(
                candidate(25, 10),
                command(ChatMode.PLAIN),
                "s",
                List.of(
                        new UserMessage("u1"),
                        new AssistantMessage("a1"),
                        new UserMessage("u2"),
                        new AssistantMessage("a2"),
                        new UserMessage("u3")),
                "",
                List.of());

        assertTrue(plan.degradedReasons().contains("history_truncated"));
        assertEquals(List.of("u2", "a2", "u3"),
                plan.selectedRecentMessages().stream()
                        .map(Message::getText)
                        .toList());
        assertEquals(6, plan.recentHistoryTokens());
    }

    @Test
    void nullMessagesAreSkippedWhenGroupingTurns() {
        RagChatProperties properties = properties();
        properties.getContext().setFallbackContextWindow(100);
        properties.getContext().setOutputReserveTokens(10);
        properties.getContext().setSafetyMarginTokens(5);
        properties.getContext().setMinimumModeEvidenceTokens(0);
        properties.getContext().setMinimumRecentTurns(1);
        properties.getContext().setMaxHistoryTokens(100);

        ConversationPromptPlan plan = planner(properties).plan(
                candidate(100, 10),
                command(ChatMode.PLAIN),
                "",
                java.util.Arrays.asList(null, new UserMessage("u1"), null,
                        new AssistantMessage("a1")),
                "",
                List.of());

        assertEquals(List.of("u1", "a1"),
                plan.selectedRecentMessages().stream()
                        .map(Message::getText)
                        .toList());
        assertEquals(4, plan.recentHistoryTokens());
        assertTrue(plan.degradedReasons().isEmpty());
    }

    @Test
    void nullBaselineProducesEmptyPlanWithoutOmissionMarker() {
        RagChatProperties properties = properties();
        properties.getContext().setFallbackContextWindow(100);
        properties.getContext().setOutputReserveTokens(10);
        properties.getContext().setSafetyMarginTokens(5);
        properties.getContext().setMinimumModeEvidenceTokens(0);
        properties.getContext().setMinimumRecentTurns(1);

        ConversationPromptPlan plan = planner(properties).plan(
                candidate(100, 10),
                command(ChatMode.PLAIN),
                "m",
                null,
                "",
                List.of());

        assertTrue(plan.selectedRecentMessages().isEmpty());
        assertTrue(plan.degradedReasons().isEmpty());
    }

    @Test
    void legacyPlainModeOverLimitHistoryRecordsDegradation() {
        RagChatProperties properties = properties();
        properties.getContext().setAdaptivePlanningEnabled(false);
        properties.getContext().setFallbackContextWindow(100);
        properties.getContext().setOutputReserveTokens(10);
        properties.getContext().setSafetyMarginTokens(5);
        properties.getContext().setMaxHistoryTokens(2);

        ConversationPromptPlan plan = planner(properties).plan(
                candidate(100, 10),
                command(ChatMode.PLAIN),
                "m",
                List.of(new UserMessage("abc")),
                "ignored",
                List.of());

        assertTrue(plan.degradedReasons()
                .contains("adaptive_planning_disabled_history_over_limit"));
        assertTrue(plan.degradedReasons()
                .contains("adaptive_planning_disabled"));
        // PLAIN 模式下 legacy 路径的证据目标为 0。
        assertEquals(0, plan.ragReserveTokens());
        assertEquals(0, plan.toolResultReserveTokens());
        assertEquals(3, plan.recentHistoryTokens());
    }

    @Test
    void legacyKnowledgeModeKeepsModeEvidenceTarget() {
        RagChatProperties properties = properties();
        properties.getContext().setAdaptivePlanningEnabled(false);
        properties.getContext().setFallbackContextWindow(100);
        properties.getContext().setOutputReserveTokens(10);
        properties.getContext().setSafetyMarginTokens(5);
        properties.getContext().setMaxHistoryTokens(10);
        properties.getContext().setMinimumModeEvidenceTokens(4);

        ConversationPromptPlan plan = planner(properties).plan(
                candidate(100, 10),
                command(ChatMode.KNOWLEDGE),
                "m",
                List.of(new UserMessage("a")),
                "ignored",
                List.of());

        // 历史未超限时无 over-limit 标记；KNOWLEDGE 保留证据目标。
        assertTrue(!plan.degradedReasons()
                .contains("adaptive_planning_disabled_history_over_limit"));
        assertEquals(4, plan.ragReserveTokens());
        assertEquals(0, plan.toolResultReserveTokens());
    }

    @Test
    void agentModeRaisesToolResultReserveUpToMaximum() {
        RagChatProperties properties = properties();
        properties.getContext().setFallbackContextWindow(200);
        properties.getContext().setOutputReserveTokens(10);
        properties.getContext().setSafetyMarginTokens(5);
        properties.getContext().setMinimumModeEvidenceTokens(2);
        properties.getContext().setMinimumRecentTurns(0);
        // "tool_result_too_large".length() == 21 → AGENT 最大证据 21。
        properties.getContext().setMaxRagContextTokens(5);

        ConversationPromptPlan plan = planner(properties).plan(
                candidate(200, 10),
                command(ChatMode.AGENT),
                "m",
                null,
                "",
                List.of());

        assertEquals(21, plan.toolResultReserveTokens());
        assertEquals(0, plan.ragReserveTokens());
    }

    @Test
    void zeroSummaryTokenLimitClearsSummaryWithoutEstimation() {
        RagChatProperties properties = properties();
        properties.getContext().setFallbackContextWindow(100);
        properties.getContext().setOutputReserveTokens(10);
        properties.getContext().setSafetyMarginTokens(5);
        properties.getContext().setMinimumModeEvidenceTokens(0);
        properties.getContext().setMinimumRecentTurns(0);
        properties.getContext().setMaxSummaryTokens(0);

        ConversationPromptPlan plan = planner(properties).plan(
                candidate(100, 10),
                command(ChatMode.PLAIN),
                "m",
                null,
                "12345",
                List.of());

        assertEquals("", plan.selectedSummary());
        assertEquals(0, plan.summaryTokens());
        assertTrue(plan.degradedReasons().isEmpty());
    }

    @Test
    void toolSchemaBeyondBudgetIsRejected() {
        RagChatProperties properties = properties();
        properties.getContext().setFallbackContextWindow(100);
        properties.getContext().setOutputReserveTokens(10);
        properties.getContext().setSafetyMarginTokens(5);
        properties.getContext().setMaxToolSchemaTokens(3);

        ToolCallback callback = mock(ToolCallback.class);
        org.springframework.ai.tool.definition.ToolDefinition definition =
                mock(org.springframework.ai.tool.definition.ToolDefinition.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(definition.name()).thenReturn("lookup");
        when(definition.description()).thenReturn("tool-schema-long");
        when(definition.inputSchema()).thenReturn("{}");

        var error = assertThrows(RagException.class, () ->
                planner(properties).plan(
                        candidate(100, 10),
                        command(ChatMode.AGENT),
                        "m",
                        null,
                        "",
                        List.of(callback)));

        assertEquals("tool schema exceeds configured token budget",
                error.getMessage());
    }

    private ConversationPromptPlanner planner(RagChatProperties properties) {
        return new ConversationPromptPlanner(properties, LENGTH_ESTIMATOR);
    }

    private RagChatProperties properties() {
        return new RagChatProperties();
    }

    private ChatCommand command(ChatMode mode) {
        return new ChatCommand(
                "message",
                "session",
                ChatPrincipal.local(),
                null,
                mode,
                MemoryMode.STATELESS,
                null,
                null,
                null,
                null,
                null);
    }

    private ChatModelRouter.ChatModelCandidate candidate(
            int contextWindow,
            int maxTokens) {
        return new ChatModelRouter.ChatModelCandidate(
                "test/model",
                mock(ChatModel.class),
                MultiModelProperties.ModelCapabilities.defaults(),
                contextWindow,
                maxTokens);
    }
}
