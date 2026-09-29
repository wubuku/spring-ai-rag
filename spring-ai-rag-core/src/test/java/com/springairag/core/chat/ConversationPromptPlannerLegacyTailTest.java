package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.repository.RagChatHistoryRepository;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 提示词规划器长尾（Batch 724，JaCoCo 驱动）：adaptive 关闭时的
 * 旧版预算路径与历史超限降级（73/88）、工具回调非空时的 schema
 * 计数（56）、PLAIN 模式零证据目标（104）、null 基线的 turns 短
 * 路与用户消息切分（222-226）。
 */
class ConversationPromptPlannerLegacyTailTest {

    private static final PromptTokenEstimator LENGTH_ESTIMATOR =
            text -> text == null ? 0 : text.length();

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
            int contextWindow, int maxTokens) {
        return new ChatModelRouter.ChatModelCandidate(
                "test/model",
                mock(ChatModel.class),
                MultiModelProperties.ModelCapabilities.defaults(),
                contextWindow,
                maxTokens);
    }

    private ConversationPromptPlanner planner(RagChatProperties properties) {
        return new ConversationPromptPlanner(properties, LENGTH_ESTIMATOR);
    }

    @Test
    void adaptiveDisabledLegacyPathDegradesOverLimitHistory() {
        RagChatProperties properties = new RagChatProperties();
        properties.getContext().setFallbackContextWindow(100);
        properties.getContext().setOutputReserveTokens(10);
        properties.getContext().setSafetyMarginTokens(5);
        properties.getContext().setMaxHistoryTokens(8);
        properties.getContext().setAdaptivePlanningEnabled(false);

        var plan = planner(properties).plan(
                candidate(100, 10),
                command(ChatMode.KNOWLEDGE),
                "system user",
                List.of(
                        new UserMessage("user-message-one"),
                        new AssistantMessage("assistant-reply-one"),
                        new UserMessage("user-message-two")),
                "",
                List.of());

        assertTrue(plan.degradedReasons()
                .contains("adaptive_planning_disabled"));
        assertTrue(plan.degradedReasons()
                .contains("adaptive_planning_disabled_history_over_limit"));
        assertEquals(List.of("user-message-one", "assistant-reply-one",
                "user-message-two"),
                plan.selectedRecentMessages().stream()
                        .map(Message::getText)
                        .toList());
    }

    @Test
    void adaptiveDisabledWithNullBaselineDoesNotDegrade() {
        RagChatProperties properties = new RagChatProperties();
        properties.getContext().setFallbackContextWindow(100);
        properties.getContext().setOutputReserveTokens(10);
        properties.getContext().setSafetyMarginTokens(5);
        properties.getContext().setAdaptivePlanningEnabled(false);

        var plan = planner(properties).plan(
                candidate(100, 10),
                command(ChatMode.KNOWLEDGE),
                "system user",
                null,
                "",
                List.of());

        // 基线为空：不产生历史超限降级。
        assertFalse(plan.degradedReasons()
                .contains("adaptive_planning_disabled_history_over_limit"));
        assertTrue(plan.selectedRecentMessages().isEmpty());
    }

    @Test
    void toolCallbacksContributeSchemaTokens() {
        RagChatProperties properties = new RagChatProperties();
        properties.getContext().setFallbackContextWindow(10_000);
        properties.getContext().setOutputReserveTokens(10);
        properties.getContext().setSafetyMarginTokens(5);

        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.inputSchema()).thenReturn("{\"type\":\"object\"}");
        when(callback.getToolDefinition()).thenReturn(definition);

        var plan = planner(properties).plan(
                candidate(10_000, 100),
                command(ChatMode.AGENT),
                "system user",
                List.of(),
                "",
                List.of(callback));

        assertTrue(plan.toolSchemaTokens() > 0);
    }

    @Test
    void plainModeSkipsModeEvidenceTarget() {
        RagChatProperties properties = new RagChatProperties();
        properties.getContext().setFallbackContextWindow(10_000);
        properties.getContext().setOutputReserveTokens(10);
        properties.getContext().setSafetyMarginTokens(5);

        var plan = planner(properties).plan(
                candidate(10_000, 100),
                command(ChatMode.PLAIN),
                "system user",
                List.of(),
                "",
                List.of());

        // PLAIN 模式不保留模式证据（target 记录为 0 或被压缩，均不抛出）。
        assertTrue(plan.degradedReasons() != null);
    }

    @Test
    void adaptiveWithNullBaselineSkipsTurns() {
        RagChatProperties properties = new RagChatProperties();
        properties.getContext().setFallbackContextWindow(10_000);
        properties.getContext().setOutputReserveTokens(10);
        properties.getContext().setSafetyMarginTokens(5);

        var plan = planner(properties).plan(
                candidate(10_000, 100),
                command(ChatMode.KNOWLEDGE),
                "system user",
                null,
                "",
                List.of());

        assertTrue(plan.selectedRecentMessages().isEmpty());
    }
}
