package com.springairag.core.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BudgetedToolCallingManager 残余分支（Batch 366）：双参构造的
 * fallback 钳制、定义解析委派、null 响应与无工具调用回退、无
 * options 提示词、null 结果净化、空 token 预算计划、无工具响应
 * 消息与 null 条目透传。
 */
class BudgetedToolCallingManagerResidualTest {

    private ChatResponse chatResponseWithToolCall(String name) {
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        null, "function", name, "{}")))
                .build();
        return new ChatResponse(
                List.of(new Generation(toolCall)),
                ChatResponseMetadata.builder().build());
    }

    @Test
    void resolveToolDefinitionsDelegates() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        List<ToolDefinition> definitions = List.of();
        when(delegate.resolveToolDefinitions(any())).thenReturn(definitions);

        assertSame(definitions, new BudgetedToolCallingManager(delegate)
                .resolveToolDefinitions(null));
    }

    @Test
    void nullResponseAndMissingToolCallsFallBackToDelegate() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        Prompt prompt = new Prompt(List.of(new UserMessage("question")));

        // 响应为 null：直接回退委派。
        new BudgetedToolCallingManager(delegate).executeToolCalls(prompt, null);
        verify(delegate).executeToolCalls(prompt, null);

        // 响应无工具调用：同样回退委派。
        ChatResponse plain = new ChatResponse(
                List.of(new Generation(AssistantMessage.builder()
                        .content("hello").build())),
                ChatResponseMetadata.builder().build());
        new BudgetedToolCallingManager(delegate).executeToolCalls(prompt, plain);
        verify(delegate).executeToolCalls(prompt, plain);
    }

    @Test
    void promptWithoutOptionsSkipsBudgetAndFallsBackToDelegate() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ChatResponse response = chatResponseWithToolCall("searchKnowledge");
        // 无 options 的 Prompt：toolContext 为空 → 无预算 → 委派。
        Prompt prompt = new Prompt(List.of(new UserMessage("question")));

        new BudgetedToolCallingManager(delegate).executeToolCalls(prompt, response);

        verify(delegate).executeToolCalls(prompt, response);
    }

    @Test
    void twoArgConstructorClampsFallbackCharacterLimit() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30), 1, 2, 2, 2, 2, 1_000);
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolResponseMessage raw = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        null, "searchKnowledge", "abcd")))
                .build();
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(ToolExecutionResult.builder()
                        .conversationHistory(List.of(raw))
                        .build());
        var options = org.springframework.ai.model.tool.ToolCallingChatOptions
                .builder()
                .toolContext(java.util.Map.of(
                        ChatExecutionBudget.CONTEXT_KEY, budget))
                .build();
        Prompt prompt = new Prompt(
                List.of(new UserMessage("question")), options);
        ChatResponse response = chatResponseWithToolCall("searchKnowledge");

        // fallback 钳制为 1：4 字符结果被整体替换为错误信封。
        ToolExecutionResult result = new BudgetedToolCallingManager(
                delegate, 0).executeToolCalls(prompt, response);

        ToolResponseMessage sanitized = (ToolResponseMessage) result
                .conversationHistory().getLast();
        assertEquals("{\"error\":\"tool_result_too_large\"}",
                sanitized.getResponses().getFirst().responseData());
    }

    @Test
    void nullDelegateResultIsSanitizedToNullWithoutSettlement() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30), 1, 2, 2, 2, 2, 1_000);
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(null);
        var options = org.springframework.ai.model.tool.ToolCallingChatOptions
                .builder()
                .toolContext(java.util.Map.of(
                        ChatExecutionBudget.CONTEXT_KEY, budget))
                .build();
        Prompt prompt = new Prompt(
                List.of(new UserMessage("question")), options);
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        null, "function", "searchKnowledge", "{}")))
                .build();
        ChatResponse response = new ChatResponse(
                List.of(new Generation(toolCall)),
                ChatResponseMetadata.builder().build());

        assertNull(new BudgetedToolCallingManager(delegate, 100)
                .executeToolCalls(prompt, response));
        assertEquals(0, budget.toolResultCharacters());
    }

    @Test
    void historyWithoutToolResponsesAndNullItemsPassThrough() {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30), 1, 2, 2, 2, 2, 1_000);
        // 空上下文计划：无 toolResultTokens → token 预算取 MAX。
        budget.recordContextPlan(java.util.Map.of());
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        List<ToolResponseMessage.ToolResponse> items = new ArrayList<>();
        items.add(null);
        items.add(new ToolResponseMessage.ToolResponse(
                "id-2", "searchKnowledge", ""));
        items.add(new ToolResponseMessage.ToolResponse(
                "id-3", "searchKnowledge", "ok"));
        ToolResponseMessage raw = ToolResponseMessage.builder()
                .responses(items)
                .build();
        List<Message> history = List.of(
                new UserMessage("question"), raw);
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(ToolExecutionResult.builder()
                        .conversationHistory(history)
                        .build());
        var options = org.springframework.ai.model.tool.ToolCallingChatOptions
                .builder()
                .toolContext(java.util.Map.of(
                        ChatExecutionBudget.CONTEXT_KEY, budget))
                .build();
        Prompt prompt = new Prompt(
                List.of(new UserMessage("question")), options);
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        null, "function", "searchKnowledge", "{}")))
                .build();
        ChatResponse response = new ChatResponse(
                List.of(new Generation(toolCall)),
                ChatResponseMetadata.builder().build());

        ToolExecutionResult result = new BudgetedToolCallingManager(
                delegate, 100).executeToolCalls(prompt, response);

        ToolResponseMessage sanitized = (ToolResponseMessage) result
                .conversationHistory().getLast();
        // null 条目原样透传，空串结果零计费，非空结果按值保留。
        assertNull(sanitized.getResponses().getFirst());
        assertEquals("", sanitized.getResponses().get(1).responseData());
        assertEquals("ok", sanitized.getResponses().get(2).responseData());
        assertEquals(2, budget.toolResultCharacters());
    }
}
