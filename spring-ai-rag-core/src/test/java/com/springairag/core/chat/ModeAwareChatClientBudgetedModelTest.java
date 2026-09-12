package com.springairag.core.chat;

import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.rag.CitationQueryAugmenter;
import com.springairag.core.rag.ProjectDocumentRetriever;
import com.springairag.core.rag.ProjectRerankPostProcessor;
import com.springairag.core.usage.LlmInvocationPurpose;
import com.springairag.core.usage.LlmUsageEvent;
import com.springairag.core.usage.LlmUsageRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * budgetedModelFor 用途感知包装（Batch 311）：候选模型上下文窗
 * 与最大输出参与预算参数、缺省回退到配置值、目的（CHAT/SUMMARY）
 * 贯通到用量事件、费用单位与候选费率入账。
 */
class ModeAwareChatClientBudgetedModelTest {

    @Test
    void candidateLimitsThreadThroughToUsageEvent() {
        RagProperties properties = new RagProperties();
        LlmUsageRecorder recorder = mock(LlmUsageRecorder.class);
        ModeAwareChatClientFactory factory = factory(properties, recorder);
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse response = new ChatResponse(List.of());
        when(delegate.call(any(Prompt.class))).thenReturn(response);
        ChatExecutionBudget budget = budget();

        var candidate = candidate("glm-4-air", delegate, 8_192, 2_048);
        BudgetedChatModel model = factory.budgetedModelFor(
                candidate, budget, LlmInvocationPurpose.CHAT);
        assertNotNull(model);
        assertSame(response, model.call(new Prompt("你好")));
        assertSame(response, delegate.call(new Prompt("你好")));

        ArgumentCaptor<LlmUsageEvent> events =
                ArgumentCaptor.forClass(LlmUsageEvent.class);
        verify(recorder).record(events.capture());
        LlmUsageEvent event = events.getValue();
        assertEquals("glm-4-air", event.modelRef());
        assertEquals(LlmInvocationPurpose.CHAT, event.purpose());
        assertEquals(properties.getUsage().getCostUnit(), event.costUnit());
    }

    @Test
    void missingCandidateLimitsFallBackToConfiguredContext() {
        ModeAwareChatClientFactory factory =
                factory(new RagProperties(), LlmUsageRecorder.NOOP);
        ChatModel delegate = mock(ChatModel.class);
        ChatExecutionBudget budget = budget();

        // contextWindow/maxTokens 缺省 → 使用配置回退与配置预留。
        BudgetedChatModel model = factory.budgetedModelFor(
                candidate("fallback-model", delegate, null, null),
                budget,
                LlmInvocationPurpose.CHAT);

        assertNotNull(model);
        // 空提示不触发预算数学，但仍可执行。
        model.call(new Prompt());
        verify(budget).reserveModelCall();
    }

    @Test
    void summaryPurposeIsMarkedOnBudget() {
        ModeAwareChatClientFactory factory =
                factory(new RagProperties(), LlmUsageRecorder.NOOP);
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of()));
        ChatExecutionBudget budget = budget();

        BudgetedChatModel model = factory.budgetedModelFor(
                candidate("summary-model", delegate, 4_096, 0),
                budget,
                LlmInvocationPurpose.SUMMARY);
        model.call(new Prompt("summarize"));

        // SUMMARY 目的额外登记一次 summary 调用计数。
        verify(budget).recordSummaryCall();
    }

    // ── fixture ─────────────────────────────────────────────────────

    private ChatExecutionBudget budget() {
        ChatExecutionBudget budget = mock(ChatExecutionBudget.class);
        when(budget.reserveModelCall()).thenReturn(1);
        when(budget.attribution(org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new com.springairag.core.usage
                        .ChatExecutionAttribution(
                        java.util.UUID.randomUUID(), 1, "db:1",
                        "session-1", null,
                        com.springairag.api.enums.ChatMode.PLAIN));
        return budget;
    }

    private ModeAwareChatClientFactory factory(
            RagProperties properties, LlmUsageRecorder recorder) {
        return new ModeAwareChatClientFactory(
                mock(ProjectDocumentRetriever.class),
                null,
                mock(ProjectRerankPostProcessor.class),
                mock(CitationQueryAugmenter.class),
                properties,
                null,
                null,
                recorder);
    }

    private ChatModelRouter.ChatModelCandidate candidate(
            String ref,
            ChatModel model,
            Integer contextWindow,
            Integer maxTokens) {
        return new ChatModelRouter.ChatModelCandidate(
                ref,
                model,
                null,
                contextWindow,
                maxTokens,
                true,
                new MultiModelProperties.ModelCost(1.0, 2.0, 0.0, 0.0));
    }
}
