package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
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
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ConversationPromptPlannerTest {

    private static final PromptTokenEstimator LENGTH_ESTIMATOR =
            text -> text == null ? 0 : text.length();

    @Test
    void selectsCompleteRecentTurnsWithoutReorderingOrDuplicatingMessages() {
        RagChatProperties properties = properties();
        properties.getContext().setFallbackContextWindow(100);
        properties.getContext().setOutputReserveTokens(10);
        properties.getContext().setSafetyMarginTokens(5);
        properties.getContext().setMaxHistoryTokens(8);
        properties.getContext().setMinimumRecentTurns(2);

        ConversationPromptPlan plan = planner(properties).plan(
                candidate(100, 10),
                command(ChatMode.PLAIN),
                "system user",
                List.of(
                        new UserMessage("u1"),
                        new AssistantMessage("a1"),
                        new UserMessage("u2"),
                        new AssistantMessage("a2")),
                "",
                List.of());

        assertEquals(List.of("u1", "a1", "u2", "a2"),
                plan.selectedRecentMessages().stream()
                        .map(Message::getText)
                        .toList());
        assertEquals(8, plan.recentHistoryTokens());
        assertFalse(plan.degradedReasons().contains("history_truncated"));
    }

    @Test
    void capsSummaryAndUsesRemainingCapacityForKnowledgeEvidence() {
        RagChatProperties properties = properties();
        properties.getContext().setFallbackContextWindow(100);
        properties.getContext().setOutputReserveTokens(10);
        properties.getContext().setSafetyMarginTokens(5);
        properties.getContext().setMaxHistoryTokens(8);
        properties.getContext().setMinimumRecentTurns(1);
        properties.getContext().setMaxSummaryTokens(4);
        properties.getContext().setMinimumModeEvidenceTokens(5);
        properties.getContext().setMaxRagContextTokens(20);

        ConversationPromptPlan plan = planner(properties).plan(
                candidate(100, 10),
                command(ChatMode.KNOWLEDGE),
                "system",
                List.of(new UserMessage("u")),
                "123456789",
                List.of());

        assertEquals(4, plan.summaryTokens());
        assertEquals(20, plan.ragReserveTokens());
        assertEquals(0, plan.toolResultReserveTokens());
    }

    @Test
    void failsBeforeCallWhenMandatoryPromptOrSchemaCannotFit() {
        RagChatProperties properties = properties();
        properties.getContext().setFallbackContextWindow(20);
        properties.getContext().setOutputReserveTokens(5);
        properties.getContext().setSafetyMarginTokens(5);

        assertThrows(
                RagException.class,
                () -> planner(properties).plan(
                        candidate(20, 5),
                        command(ChatMode.PLAIN),
                        "1234567890123456",
                        List.of(),
                        "",
                        List.of()));

        properties.getContext().setFallbackContextWindow(100);
        properties.getContext().setMaxToolSchemaTokens(3);
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        org.mockito.Mockito.when(callback.getToolDefinition()).thenReturn(definition);
        org.mockito.Mockito.when(definition.name()).thenReturn("lookup");
        org.mockito.Mockito.when(definition.description()).thenReturn("long");
        org.mockito.Mockito.when(definition.inputSchema()).thenReturn("{}");

        assertThrows(
                RagException.class,
                () -> planner(properties).plan(
                        candidate(100, 5),
                        command(ChatMode.AGENT),
                        "system",
                        List.of(),
                        "",
                        List.of(callback)));
    }

    @Test
    void disabledAdaptivePlanningPreservesBaselineAndRecordsDegradedReason() {
        RagChatProperties properties = properties();
        properties.getContext().setAdaptivePlanningEnabled(false);
        List<Message> baseline = List.of(
                new UserMessage("u1"),
                new AssistantMessage("a1"));

        ConversationPromptPlan plan = planner(properties).plan(
                candidate(100_000, 1_000),
                command(ChatMode.PLAIN),
                "system",
                baseline,
                "summary that must not be used",
                List.of());

        assertEquals(baseline, plan.selectedRecentMessages());
        assertTrue(plan.degradedReasons().contains("adaptive_planning_disabled"));
        assertEquals(0, plan.summaryTokens());
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
