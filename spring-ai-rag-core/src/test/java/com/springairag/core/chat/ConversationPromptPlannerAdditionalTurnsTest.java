package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagChatProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

/**
 * selectAdditionalTurns 追加回合选择（Batch 340）：在最小回合之
 * 外按剩余预算向前补充更旧回合、历史 token 上限约束、预算耗尽仅
 * 保留最近回合、空历史不产生降级标记。
 */
class ConversationPromptPlannerAdditionalTurnsTest {

    private static final PromptTokenEstimator LENGTH_ESTIMATOR =
            text -> text == null ? 0 : text.length();

    private ConversationPromptPlanner planner(RagChatProperties properties) {
        return new ConversationPromptPlanner(properties, LENGTH_ESTIMATOR);
    }

    private RagChatProperties properties(int maxHistory, int minimumTurns) {
        RagChatProperties properties = new RagChatProperties();
        properties.getContext().setFallbackContextWindow(10_000);
        properties.getContext().setOutputReserveTokens(10);
        properties.getContext().setSafetyMarginTokens(5);
        properties.getContext().setMaxHistoryTokens(maxHistory);
        properties.getContext().setMinimumRecentTurns(minimumTurns);
        return properties;
    }

    private ChatModelRouter.ChatModelCandidate candidate() {
        return new ChatModelRouter.ChatModelCandidate(
                "test/model",
                mock(ChatModel.class),
                MultiModelProperties.ModelCapabilities.defaults(),
                10_000,
                100);
    }

    private List<String> selectedTexts(ConversationPromptPlan plan) {
        return plan.selectedRecentMessages().stream()
                .map(Message::getText)
                .toList();
    }

    @Test
    void additionalOlderTurnsArePrependedWithinBudget() {
        List<Message> history = List.of(
                new UserMessage("u1"), new AssistantMessage("a1"),
                new UserMessage("u2"), new AssistantMessage("a2"));

        ConversationPromptPlan plan = planner(properties(1_000, 1)).plan(
                candidate(), command(), "system", history, "", List.of());

        // 最小 1 回合之外，剩余预算允许全部更旧回合按原顺序前置。
        assertEquals(List.of("u1", "a1", "u2", "a2"), selectedTexts(plan));
        assertEquals(8, plan.recentHistoryTokens());
        assertFalse(plan.degradedReasons().contains("recent_history_omitted"));
    }

    @Test
    void additionalTurnsStopWhenHistoryBudgetExhausted() {
        List<Message> history = List.of(
                new UserMessage("u1"), new AssistantMessage("a1"),
                new UserMessage("u2"), new AssistantMessage("a2"),
                new UserMessage("u3"), new AssistantMessage("a3"));

        // 最近回合 4 token，历史预算 10：可再补 1 回合（累计 8），
        // 第 3 回合超限被截断。
        ConversationPromptPlan plan = planner(properties(10, 1)).plan(
                candidate(), command(), "system", history, "", List.of());

        assertEquals(List.of("u2", "a2", "u3", "a3"), selectedTexts(plan));
        assertEquals(8, plan.recentHistoryTokens());
        assertFalse(plan.degradedReasons().contains("recent_history_omitted"));
    }

    @Test
    void exhaustedAdditionalBudgetKeepsRecentTurnOnly() {
        List<Message> history = List.of(
                new UserMessage("u1"), new AssistantMessage("a1"),
                new UserMessage("u2"), new AssistantMessage("a2"));

        // 历史预算恰好等于最近回合：追加预算为 0 → 仅保留最近回合。
        ConversationPromptPlan plan = planner(properties(4, 1)).plan(
                candidate(), command(), "system", history, "", List.of());

        assertEquals(List.of("u2", "a2"), selectedTexts(plan));
        assertEquals(4, plan.recentHistoryTokens());
    }

    @Test
    void emptyHistoryProducesPlanWithoutOmissionMarker() {
        ConversationPromptPlan plan = planner(properties(1_000, 1)).plan(
                candidate(), command(), "system", List.of(), "", List.of());

        assertEquals(List.of(), plan.selectedRecentMessages());
        assertEquals(0, plan.recentHistoryTokens());
        assertFalse(plan.degradedReasons().contains("recent_history_omitted"));
    }

    private ChatCommand command() {
        return new ChatCommand(
                "message",
                "session",
                ChatPrincipal.local(),
                null,
                ChatMode.PLAIN,
                MemoryMode.STATELESS,
                null,
                null,
                null,
                null,
                null);
    }
}
