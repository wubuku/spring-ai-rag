package com.springairag.core.chat;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.Objects;

/**
 * Counts every model call/subscription against the logical request budget.
 */
public final class BudgetedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final ChatExecutionBudget budget;
    private final int contextWindow;
    private final int outputReserveTokens;
    private final int safetyMarginTokens;
    private final int maxToolSchemaTokens;
    private final PromptTokenEstimator estimator;
    private final boolean summaryCall;

    public BudgetedChatModel(ChatModel delegate, ChatExecutionBudget budget) {
        this(delegate, budget, 0, 0, 0, Integer.MAX_VALUE,
                new JTokkitPromptTokenEstimator(), false);
    }

    public BudgetedChatModel(
            ChatModel delegate,
            ChatExecutionBudget budget,
            int contextWindow,
            int outputReserveTokens,
            int safetyMarginTokens,
            int maxToolSchemaTokens,
            PromptTokenEstimator estimator) {
        this(delegate, budget, contextWindow, outputReserveTokens,
                safetyMarginTokens, maxToolSchemaTokens, estimator, false);
    }

    public BudgetedChatModel(
            ChatModel delegate,
            ChatExecutionBudget budget,
            int contextWindow,
            int outputReserveTokens,
            int safetyMarginTokens,
            int maxToolSchemaTokens,
            PromptTokenEstimator estimator,
            boolean summaryCall) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.contextWindow = Math.max(0, contextWindow);
        this.outputReserveTokens = Math.max(0, outputReserveTokens);
        this.safetyMarginTokens = Math.max(0, safetyMarginTokens);
        this.maxToolSchemaTokens = Math.max(1, maxToolSchemaTokens);
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.summaryCall = summaryCall;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        validatePrompt(prompt);
        budget.reserveModelCall();
        if (summaryCall) {
            budget.recordSummaryCall();
        }
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            validatePrompt(prompt);
            budget.reserveModelCall();
            if (summaryCall) {
                budget.recordSummaryCall();
            }
            return delegate.stream(prompt);
        });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private void validatePrompt(Prompt prompt) {
        if (contextWindow < 1 || prompt == null) {
            return;
        }
        int promptTokens = prompt.getInstructions().stream()
                .mapToInt(estimator::estimate)
                .sum();
        int schemaTokens = 0;
        if (prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolCallbacks() != null) {
            for (ToolCallback callback : options.getToolCallbacks()) {
                schemaTokens += estimator.estimate(
                        callback.getToolDefinition());
            }
        }
        if (schemaTokens > maxToolSchemaTokens
                || promptTokens + schemaTokens + outputReserveTokens
                        + safetyMarginTokens >= contextWindow) {
            throw new RagException(
                    ErrorCode.CHAT_CONTEXT_BUDGET_EXCEEDED,
                    "Chat prompt exceeds the resolved model context window");
        }
    }
}
