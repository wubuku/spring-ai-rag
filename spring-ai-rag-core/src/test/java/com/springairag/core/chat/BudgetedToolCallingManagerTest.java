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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BudgetedToolCallingManagerTest {

    @Test
    void capsToolResultEvenWhenProviderOmitsToolCallId() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                1, 2, 2, 2, 2, 1_000);
        budget.recordContextPlan(Map.of("toolResultTokens", 100));

        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolResponseMessage rawToolResponse =
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                null,
                                "searchKnowledge",
                                "x".repeat(100))))
                        .build();
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(ToolExecutionResult.builder()
                        .conversationHistory(List.of(
                                new UserMessage("question"),
                                rawToolResponse))
                        .build());

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolContext(Map.of(ChatExecutionBudget.CONTEXT_KEY, budget))
                .build();
        Prompt prompt = new Prompt(List.of(new UserMessage("question")), options);
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        null,
                        "function",
                        "searchKnowledge",
                        "{\"query\":\"question\"}")))
                .build();
        ChatResponse response = new ChatResponse(
                List.of(new Generation(toolCall)),
                ChatResponseMetadata.builder().build());

        ToolExecutionResult result = new BudgetedToolCallingManager(
                delegate, 10).executeToolCalls(prompt, response);

        ToolResponseMessage sanitized = (ToolResponseMessage) result
                .conversationHistory().getLast();
        assertEquals("{\"error\":\"tool_result_too_large\"}",
                sanitized.getResponses().getFirst().responseData());
        assertEquals("{\"error\":\"tool_result_too_large\"}".length(),
                budget.toolResultCharacters());
    }

    @Test
    void preservesPreviousToolResultsWhenSanitizingTheCurrentRound() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                1, 2, 2, 2, 2, 1_000);
        budget.recordContextPlan(Map.of("toolResultTokens", 50));

        ToolCallingManager delegate = mock(ToolCallingManager.class);
        String previous = "p".repeat(120);
        String current = "c".repeat(120);
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(ToolExecutionResult.builder()
                        .conversationHistory(List.of(
                                new UserMessage("question"),
                                ToolResponseMessage.builder()
                                        .responses(List.of(
                                                new ToolResponseMessage.ToolResponse(
                                                        "previous-id",
                                                        "searchKnowledge",
                                                        previous)))
                                        .build(),
                                ToolResponseMessage.builder()
                                        .responses(List.of(
                                                new ToolResponseMessage.ToolResponse(
                                                        "current-id",
                                                        "searchKnowledge",
                                                        current)))
                                        .build()))
                        .build());

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolContext(Map.of(ChatExecutionBudget.CONTEXT_KEY, budget))
                .build();
        Prompt prompt = new Prompt(List.of(new UserMessage("question")), options);
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "current-id",
                        "function",
                        "searchKnowledge",
                        "{\"query\":\"question\"}")))
                .build();
        ChatResponse response = new ChatResponse(
                List.of(new Generation(toolCall)),
                ChatResponseMetadata.builder().build());

        ToolExecutionResult result = new BudgetedToolCallingManager(
                delegate, 1_000).executeToolCalls(prompt, response);

        ToolResponseMessage previousResponse =
                (ToolResponseMessage) result.conversationHistory().get(1);
        ToolResponseMessage currentResponse =
                (ToolResponseMessage) result.conversationHistory().get(2);
        assertEquals(previous,
                previousResponse.getResponses().getFirst().responseData());
        assertEquals("{\"error\":\"tool_result_too_large\"}",
                currentResponse.getResponses().getFirst().responseData());
    }
}
