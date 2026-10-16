package com.springairag.core.chat;

import com.springairag.core.budget.BudgetLimitException;
import com.springairag.core.budget.BudgetReservationService;
import com.springairag.core.budget.BudgetServiceUnavailableException;
import com.springairag.core.usage.LlmInvocationPurpose;
import com.springairag.core.usage.LlmUsageRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BudgetedChatModelReservationTest {

    @Test
    void hardLimitRejectsBeforeProviderCall() {
        ChatModel delegate = mock(ChatModel.class);
        BudgetReservationService reservationService = mock(BudgetReservationService.class);
        when(reservationService.reserve(any()))
                .thenThrow(new BudgetLimitException("limit", 60));
        BudgetedChatModel model = model(delegate, reservationService);

        assertThrows(BudgetLimitException.class, () -> model.call(new Prompt("question")));
        verify(delegate, never()).call(any(Prompt.class));
        verify(reservationService).reserve(any());
    }

    @Test
    void unavailableBudgetStoreFailsClosedBeforeProviderCall() {
        ChatModel delegate = mock(ChatModel.class);
        BudgetReservationService reservationService = mock(BudgetReservationService.class);
        when(reservationService.reserve(any()))
                .thenThrow(new BudgetServiceUnavailableException("unavailable", null));
        BudgetedChatModel model = model(delegate, reservationService);

        assertThrows(BudgetServiceUnavailableException.class,
                () -> model.call(new Prompt("question")));
        verify(delegate, never()).call(any(Prompt.class));
    }

    @Test
    void successfulCallSettlesDurableReservation() {
        ChatModel delegate = mock(ChatModel.class);
        BudgetReservationService reservationService = mock(BudgetReservationService.class);
        BudgetReservationService.Reservation reservation =
                new BudgetReservationService.Reservation(java.util.UUID.randomUUID(), true, true);
        ChatResponse response = new ChatResponse(List.of());
        when(reservationService.reserve(any())).thenReturn(reservation);
        when(delegate.call(any(Prompt.class))).thenReturn(response);
        BudgetedChatModel model = model(delegate, reservationService);

        assertEquals(response, model.call(new Prompt("question")));
        verify(reservationService).settle(any(), any(), any(), any(boolean.class));
    }

    @Test
    void cancelledStreamSettlesExactlyOnce() {
        ChatModel delegate = mock(ChatModel.class);
        BudgetReservationService reservationService = mock(BudgetReservationService.class);
        BudgetReservationService.Reservation reservation =
                new BudgetReservationService.Reservation(java.util.UUID.randomUUID(), true, true);
        ChatResponse response = new ChatResponse(List.of());
        when(reservationService.reserve(any())).thenReturn(reservation);
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(response));
        BudgetedChatModel model = model(delegate, reservationService);

        model.stream(new Prompt("question")).take(1).blockLast();
        verify(reservationService).settle(any(), any(), any(), any(boolean.class));
    }

    private static BudgetedChatModel model(
            ChatModel delegate,
            BudgetReservationService reservationService) {
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30), 2, 2, 2, 2, 2, 10_000,
                null, "db:test", "session-1", "trace-1",
                com.springairag.api.enums.ChatMode.KNOWLEDGE);
        return new BudgetedChatModel(
                delegate, budget, 10_000, 1_000, 10, 100,
                new JTokkitPromptTokenEstimator(), LlmInvocationPurpose.CHAT,
                LlmUsageRecorder.NOOP, null, "CONFIGURED_MODEL_COST", "test/model",
                reservationService);
    }
}
