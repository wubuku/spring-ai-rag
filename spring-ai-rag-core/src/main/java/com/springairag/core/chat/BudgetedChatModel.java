package com.springairag.core.chat;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.Objects;

/**
 * Counts every model call/subscription against the logical request budget.
 */
public final class BudgetedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final ChatExecutionBudget budget;

    public BudgetedChatModel(ChatModel delegate, ChatExecutionBudget budget) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        budget.reserveModelCall();
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            budget.reserveModelCall();
            return delegate.stream(prompt);
        });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
