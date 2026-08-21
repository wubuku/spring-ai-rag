package com.springairag.core.chat;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds request-level tool batch reservation around Spring AI's standard manager.
 */
public final class BudgetedToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;

    public BudgetedToolCallingManager(ToolCallingManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(
            ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }

    @Override
    public ToolExecutionResult executeToolCalls(
            Prompt prompt,
            ChatResponse response) {
        AssistantMessage output = response != null && response.getResult() != null
                ? response.getResult().getOutput()
                : null;
        List<AssistantMessage.ToolCall> calls = output != null
                ? output.getToolCalls()
                : List.of();
        if (calls.isEmpty()) {
            return delegate.executeToolCalls(prompt, response);
        }
        ChatExecutionBudget budget = budget(prompt);
        if (budget == null) {
            return delegate.executeToolCalls(prompt, response);
        }
        int maxResultCharacters = 24_000;
        Object retrieval = toolContext(prompt)
                .get(com.springairag.core.rag.KnowledgeSearchTool.CONTEXT_KEY);
        if (retrieval instanceof AuthorizedRetrievalContext context) {
            maxResultCharacters = context.maxToolResultCharacters();
        }
        int reserved = Math.multiplyExact(calls.size(), maxResultCharacters);
        budget.reserveToolBatch(
                calls.stream().map(AssistantMessage.ToolCall::name).toList(),
                maxResultCharacters);
        try {
            ToolExecutionResult result = delegate.executeToolCalls(prompt, response);
            int actualCharacters = 0;
            int actualTokens = 0;
            for (Message message : result.conversationHistory()) {
                if (message instanceof ToolResponseMessage toolResponse) {
                    for (ToolResponseMessage.ToolResponse item
                            : toolResponse.getResponses()) {
                        String value = item.responseData() != null
                                ? item.responseData()
                                : "";
                        actualCharacters += value.length();
                        actualTokens += estimateTokens(value);
                    }
                }
            }
            budget.settleToolResults(
                    actualCharacters, actualTokens, reserved);
            return result;
        } catch (RuntimeException e) {
            budget.releaseToolReservation(reserved);
            throw e;
        }
    }

    private ChatExecutionBudget budget(Prompt prompt) {
        Object value = toolContext(prompt).get(ChatExecutionBudget.CONTEXT_KEY);
        return value instanceof ChatExecutionBudget found ? found : null;
    }

    private java.util.Map<String, Object> toolContext(Prompt prompt) {
        if (prompt != null && prompt.getOptions()
                instanceof ToolCallingChatOptions options
                && options.getToolContext() != null) {
            return options.getToolContext();
        }
        return java.util.Map.of();
    }

    private int estimateTokens(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        return Math.max(1, (value.codePointCount(0, value.length()) + 1) / 2);
    }
}
