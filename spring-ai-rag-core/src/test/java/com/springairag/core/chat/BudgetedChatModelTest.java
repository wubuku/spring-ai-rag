package com.springairag.core.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BudgetedChatModelTest {

    @Test
    void callAndStreamSubscriptionShareTheSameModelBudget() {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        when(delegate.call(any(Prompt.class))).thenReturn(response);
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(response));
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                2,
                2,
                1,
                1,
                1,
                1_000);

        BudgetedChatModel model = new BudgetedChatModel(delegate, budget);
        model.call(new Prompt("one"));
        model.stream(new Prompt("two")).blockLast();

        assertEquals(2, budget.modelCalls());
        verify(delegate).call(any(Prompt.class));
        verify(delegate).stream(any(Prompt.class));
    }
}
