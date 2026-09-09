package com.springairag.core.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BudgetedToolCallingManager 分支：空工具调用与缺失预算时直通委
 * 托、token 预算触发的结果替换、按工具名单独限额解析、委派异常时
 * 释放预留并原样抛出。
 */
class BudgetedToolCallingManagerBranchTest {

    private ChatExecutionBudget budget() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                1, 2, 2, 2, 2, 10_000);
        budget.recordContextPlan(Map.of("toolResultTokens", 1_000));
        return budget;
    }

    private Prompt promptWithBudget(ChatExecutionBudget budget) {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolContext(Map.of(ChatExecutionBudget.CONTEXT_KEY, budget))
                .build();
        return new Prompt(List.of(new UserMessage("question")), options);
    }

    private ChatResponse toolCallResponse(String name) {
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "current-id", "function", name,
                        "{\"query\":\"question\"}")))
                .build();
        return new ChatResponse(
                List.of(new Generation(toolCall)),
                ChatResponseMetadata.builder().build());
    }

    @Test
    void passesThroughToDelegateWhenResponseHasNoToolCalls() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolExecutionResult passthrough = ToolExecutionResult.builder().build();
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(passthrough);
        ChatResponse plainResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("final"))),
                ChatResponseMetadata.builder().build());

        ToolExecutionResult result = new BudgetedToolCallingManager(delegate, 10)
                .executeToolCalls(promptWithBudget(budget()), plainResponse);

        assertSame(passthrough, result);
    }

    @Test
    void passesThroughToDelegateWhenBudgetIsMissing() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolExecutionResult passthrough = ToolExecutionResult.builder().build();
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(passthrough);
        // 无预算的提示词：管理器不得干预。
        Prompt prompt = new Prompt(List.of(new UserMessage("question")));
        ChatResponse response = toolCallResponse("searchKnowledge");

        ToolExecutionResult result = new BudgetedToolCallingManager(delegate, 10)
                .executeToolCalls(prompt, response);

        assertSame(passthrough, result);
    }

    @Test
    void replacesResultWhenTokenBudgetIsExhausted() {
        ChatExecutionBudget budget = budget();
        // token 预算压到 1：任何非空结果都会超出 token 预算。
        budget.recordContextPlan(Map.of("toolResultTokens", 1));
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(ToolExecutionResult.builder()
                        .conversationHistory(List.of(
                                new UserMessage("question"),
                                ToolResponseMessage.builder()
                                        .responses(List.of(
                                                new ToolResponseMessage.ToolResponse(
                                                        "id-1", "searchKnowledge",
                                                        "short but tokenized")))
                                        .build()))
                        .build());

        ChatExecutionBudget localBudget = budget();
        localBudget.recordContextPlan(Map.of("toolResultTokens", 1));
        ToolExecutionResult result = new BudgetedToolCallingManager(delegate, 10_000)
                .executeToolCalls(promptWithBudget(localBudget), toolCallResponse("searchKnowledge"));

        ToolResponseMessage sanitized = (ToolResponseMessage) result
                .conversationHistory().getLast();
        assertEquals("{\"error\":\"tool_result_too_large\"}",
                sanitized.getResponses().getFirst().responseData());
    }

    @Test
    void appliesPerToolCharacterLimitsFromToolContext() {
        ChatExecutionBudget budget = budget();
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(ToolExecutionResult.builder()
                        .conversationHistory(List.of(
                                new UserMessage("question"),
                                ToolResponseMessage.builder()
                                        .responses(List.of(
                                                new ToolResponseMessage.ToolResponse(
                                                        "id-1", "search", "s".repeat(30)),
                                                new ToolResponseMessage.ToolResponse(
                                                        "id-2", "fetch", "f".repeat(30))))
                                        .build()))
                        .build());
        // search 限额 10、fetch 限额 500：仅 search 被替换。
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolContext(Map.of(
                        ChatExecutionBudget.CONTEXT_KEY, budget,
                        ChatExecutionBudget.TOOL_RESULT_CHARACTER_LIMITS_CONTEXT_KEY,
                        Map.of("search", 10, "fetch", 500)))
                .build();
        Prompt prompt = new Prompt(List.of(new UserMessage("question")), options);

        ToolExecutionResult result = new BudgetedToolCallingManager(delegate, 10_000)
                .executeToolCalls(prompt, toolCallResponse("search"));

        ToolResponseMessage sanitized = (ToolResponseMessage) result
                .conversationHistory().getLast();
        assertEquals("{\"error\":\"tool_result_too_large\"}",
                sanitized.getResponses().get(0).responseData());
        assertEquals("f".repeat(30),
                sanitized.getResponses().get(1).responseData());
    }

    @Test
    void releasesReservationAndRethrowsWhenDelegateFails() {
        ChatExecutionBudget budget = budget();
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenThrow(new IllegalStateException("provider down"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new BudgetedToolCallingManager(delegate, 10_000)
                        .executeToolCalls(promptWithBudget(budget()),
                                toolCallResponse("searchKnowledge")));

        assertEquals("provider down", error.getMessage());
        // 预留被释放：后续仍可再次预约全部字符预算。
        assertEquals(10_000, budget.toolResultCharacters() == 0 ? 10_000 : 0);
    }
}
