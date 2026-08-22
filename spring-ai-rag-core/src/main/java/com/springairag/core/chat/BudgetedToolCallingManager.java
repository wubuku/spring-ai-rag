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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds request-level tool batch reservation around Spring AI's standard manager.
 */
public final class BudgetedToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;
    private final int fallbackMaxResultCharacters;

    public BudgetedToolCallingManager(ToolCallingManager delegate) {
        this(delegate, 24_000);
    }

    public BudgetedToolCallingManager(
            ToolCallingManager delegate,
            int fallbackMaxResultCharacters) {
        this.delegate = delegate;
        this.fallbackMaxResultCharacters = Math.max(
                1, fallbackMaxResultCharacters);
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
        Map<String, Integer> resultCharacterLimits = Map.of();
        Object configuredLimits = toolContext(prompt).get(
                ChatExecutionBudget.TOOL_RESULT_CHARACTER_LIMITS_CONTEXT_KEY);
        if (configuredLimits instanceof Map<?, ?> rawLimits) {
            Map<String, Integer> parsedLimits = new java.util.LinkedHashMap<>();
            rawLimits.forEach((key, value) -> {
                if (key instanceof String name && value instanceof Number number) {
                    parsedLimits.put(name, number.intValue());
                }
            });
            resultCharacterLimits = Map.copyOf(parsedLimits);
        }
        int reserved = budget.reserveToolBatch(
                calls.stream().map(AssistantMessage.ToolCall::name).toList(),
                resultCharacterLimits,
                fallbackMaxResultCharacters);
        try {
            ToolExecutionResult result = delegate.executeToolCalls(prompt, response);
            int tokenBudget = toolResultTokenBudget(budget);
            SanitizedResult sanitized = sanitize(
                    result,
                    resultCharacterLimits,
                    tokenBudget);
            budget.settleToolResults(
                    sanitized.characters(), sanitized.tokens(), reserved);
            return sanitized.result();
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

    private int toolResultTokenBudget(ChatExecutionBudget budget) {
        Object value = budget.contextPlan().get("toolResultTokens");
        if (value instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        return Integer.MAX_VALUE;
    }

    private SanitizedResult sanitize(
            ToolExecutionResult result,
            Map<String, Integer> resultCharacterLimits,
            int tokenBudget) {
        if (result == null || result.conversationHistory() == null) {
            return new SanitizedResult(result, 0, 0);
        }
        int characters = 0;
        int tokens = 0;
        List<Message> messages = new ArrayList<>();
        List<Message> history = result.conversationHistory();
        int currentToolResponseIndex = -1;
        for (int index = history.size() - 1; index >= 0; index--) {
            if (history.get(index) instanceof ToolResponseMessage) {
                currentToolResponseIndex = index;
                break;
            }
        }
        for (int index = 0; index < history.size(); index++) {
            Message message = history.get(index);
            if (index != currentToolResponseIndex
                    || !(message instanceof ToolResponseMessage toolResponse)) {
                messages.add(message);
                continue;
            }
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (ToolResponseMessage.ToolResponse item
                    : toolResponse.getResponses()) {
                if (item == null) {
                    responses.add(item);
                    continue;
                }
                String value = item.responseData() == null
                        ? ""
                        : item.responseData();
                int limit = resultCharacterLimits.getOrDefault(
                        item.name(), fallbackMaxResultCharacters);
                int itemTokens = estimateTokens(value);
                boolean tooLarge = value.length() > limit
                        || tokens + itemTokens > tokenBudget;
                String normalized = tooLarge
                        ? "{\"error\":\"tool_result_too_large\"}"
                        : value;
                int normalizedTokens = estimateTokens(normalized);
                responses.add(new ToolResponseMessage.ToolResponse(
                        item.id(), item.name(), normalized));
                characters += normalized.length();
                tokens += normalizedTokens;
            }
            messages.add(ToolResponseMessage.builder()
                    .responses(responses)
                    .metadata(toolResponse.getMetadata())
                    .build());
        }
        return new SanitizedResult(
                ToolExecutionResult.builder()
                        .conversationHistory(messages)
                        .returnDirect(result.returnDirect())
                        .build(),
                characters,
                tokens);
    }

    private int estimateTokens(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        return Math.max(1, (value.codePointCount(0, value.length()) + 1) / 2);
    }

    private record SanitizedResult(
            ToolExecutionResult result,
            int characters,
            int tokens) {
    }
}
