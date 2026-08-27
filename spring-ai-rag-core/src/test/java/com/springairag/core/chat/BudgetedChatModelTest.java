package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.usage.LlmInvocationOutcome;
import com.springairag.core.usage.LlmInvocationPurpose;
import com.springairag.core.usage.LlmUsageEvent;
import com.springairag.core.usage.LlmUsageRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BudgetedChatModelTest {

    @Test
    void callAndStreamSubscriptionShareTheSameModelBudgetAndRecordOnce() {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse response = responseWithUsage(3, 5);
        when(delegate.call(any(Prompt.class))).thenReturn(response);
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(response));
        RecordingRecorder recorder = new RecordingRecorder();
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                2,
                2,
                1,
                1,
                1,
                1_000);

        BudgetedChatModel model = model(delegate, budget, recorder);
        model.call(new Prompt("one"));
        model.stream(new Prompt("two")).blockLast();

        assertEquals(2, budget.modelCalls());
        assertEquals(1, recorder.syncEvents().size());
        assertEquals(1, recorder.asyncEvents().size());
        assertEquals(1, recorder.syncEvents().getFirst().callOrdinal());
        assertEquals(2, recorder.asyncEvents().getFirst().callOrdinal());
        assertEquals(8, recorder.syncEvents().getFirst().usage().totalTokens());
        assertEquals(8, recorder.asyncEvents().getFirst().usage().totalTokens());
        assertTrue(recorder.asyncEvents().getFirst().streaming());
        verify(delegate).call(any(Prompt.class));
        verify(delegate).stream(any(Prompt.class));
    }

    @Test
    void nonStreamingFailureIsRecordedAndPropagated() {
        ChatModel delegate = mock(ChatModel.class);
        RuntimeException failure = new IllegalStateException("provider failure");
        when(delegate.call(any(Prompt.class))).thenThrow(failure);
        RecordingRecorder recorder = new RecordingRecorder();
        BudgetedChatModel model = model(delegate, budget(1), recorder);

        try {
            model.call(new Prompt("failure"));
        } catch (RuntimeException actual) {
            assertEquals(failure, actual);
        }

        assertEquals(1, recorder.syncEvents().size());
        LlmUsageEvent event = recorder.syncEvents().getFirst();
        assertEquals(LlmInvocationOutcome.FAILED, event.outcome());
        assertFalse(event.streaming());
        assertFalse(event.usage().available());
    }

    @Test
    void streamErrorIsRecordedAsStreamingFailureExactlyOnce() {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse response = responseWithUsage(4, 6);
        when(delegate.stream(any(Prompt.class))).thenReturn(
                Flux.concat(Flux.just(response),
                        Flux.error(new IllegalStateException("stream failure"))));
        RecordingRecorder recorder = new RecordingRecorder();
        BudgetedChatModel model = model(delegate, budget(1), recorder);

        try {
            model.stream(new Prompt("stream")).blockLast();
        } catch (IllegalStateException expected) {
            assertEquals("stream failure", expected.getMessage());
        }

        assertEquals(1, recorder.asyncEvents().size());
        LlmUsageEvent event = recorder.asyncEvents().getFirst();
        assertEquals(LlmInvocationOutcome.FAILED, event.outcome());
        assertTrue(event.streaming());
        assertEquals(10, event.usage().totalTokens());
    }

    @Test
    void synchronousStreamCreationFailureIsRecordedAsStreamingFailure() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.stream(any(Prompt.class)))
                .thenThrow(new IllegalStateException("creation failure"));
        RecordingRecorder recorder = new RecordingRecorder();
        BudgetedChatModel model = model(delegate, budget(1), recorder);

        try {
            model.stream(new Prompt("creation")).blockLast();
        } catch (IllegalStateException expected) {
            assertEquals("creation failure", expected.getMessage());
        }

        assertEquals(1, recorder.asyncEvents().size());
        LlmUsageEvent event = recorder.asyncEvents().getFirst();
        assertEquals(LlmInvocationOutcome.FAILED, event.outcome());
        assertTrue(event.streaming());
    }

    @Test
    void cancelledStreamIsRecordedOnceWithLatestKnownUsage() {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse response = responseWithUsage(7, 2);
        when(delegate.stream(any(Prompt.class))).thenReturn(
                Flux.concat(Flux.just(response), Flux.never()));
        RecordingRecorder recorder = new RecordingRecorder();
        BudgetedChatModel model = model(delegate, budget(1), recorder);

        assertEquals(response, model.stream(new Prompt("cancel"))
                .take(1)
                .blockLast());

        assertEquals(1, recorder.asyncEvents().size());
        LlmUsageEvent event = recorder.asyncEvents().getFirst();
        assertEquals(LlmInvocationOutcome.CANCELLED, event.outcome());
        assertTrue(event.streaming());
        assertEquals(9, event.usage().totalTokens());
    }

    @Test
    void recorderFailureDoesNotChangeModelResult() {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse response = responseWithUsage(1, 1);
        when(delegate.call(any(Prompt.class))).thenReturn(response);
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(response));
        LlmUsageRecorder recorder = new LlmUsageRecorder() {
            @Override
            public void record(LlmUsageEvent event) {
                throw new IllegalStateException("ledger unavailable");
            }

            @Override
            public void recordAsync(LlmUsageEvent event) {
                throw new IllegalStateException("ledger unavailable");
            }
        };
        BudgetedChatModel model = model(delegate, budget(2), recorder);

        assertEquals(response, model.call(new Prompt("call")));
        assertEquals(response, model.stream(new Prompt("stream")).blockLast());
        verify(delegate).call(any(Prompt.class));
        verify(delegate).stream(any(Prompt.class));
    }

    @Test
    void eventCapturesPurposeModeModelAndConfiguredCostSnapshot() {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse response = responseWithUsage(1_000, 500);
        when(delegate.call(any(Prompt.class))).thenReturn(response);
        RecordingRecorder recorder = new RecordingRecorder();
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                1, 1, 1, 1, 1, 1_000,
                null,
                "db:principal",
                "session-1",
                "trace-1",
                ChatMode.KNOWLEDGE);
        BudgetedChatModel model = new BudgetedChatModel(
                delegate,
                budget,
                10_000,
                1_000,
                10,
                100,
                new JTokkitPromptTokenEstimator(),
                LlmInvocationPurpose.QUERY_TRANSFORM,
                recorder,
                new MultiModelProperties.ModelCost(2.0, 4.0, 0, 0),
                "CONFIGURED_MODEL_COST",
                "openai/gpt-test");

        model.call(new Prompt("transform"));

        LlmUsageEvent event = recorder.syncEvents().getFirst();
        assertEquals("openai/gpt-test", event.modelRef());
        assertEquals(ChatMode.KNOWLEDGE, event.chatMode());
        assertEquals(LlmInvocationPurpose.QUERY_TRANSFORM, event.purpose());
        assertTrue(event.pricingAvailable());
        assertTrue(event.costAvailable());
        assertEquals("0.00400000", event.configuredCost().toPlainString());
        assertNotNull(event.startedAt());
        assertTrue(!event.completedAt().isBefore(event.startedAt()));
    }

    private static BudgetedChatModel model(
            ChatModel delegate,
            ChatExecutionBudget budget,
            LlmUsageRecorder recorder) {
        return new BudgetedChatModel(
                delegate,
                budget,
                10_000,
                1_000,
                10,
                100,
                new JTokkitPromptTokenEstimator(),
                LlmInvocationPurpose.CHAT,
                recorder,
                null,
                "CONFIGURED_MODEL_COST",
                "test/model");
    }

    private static ChatExecutionBudget budget(int maxModelCalls) {
        return new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                2,
                maxModelCalls,
                2,
                2,
                2,
                10_000);
    }

    private static ChatResponse responseWithUsage(
            int promptTokens,
            int completionTokens) {
        return new ChatResponse(
                List.of(),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(promptTokens, completionTokens))
                        .build());
    }

    private static final class RecordingRecorder implements LlmUsageRecorder {
        private final List<LlmUsageEvent> sync = new CopyOnWriteArrayList<>();
        private final List<LlmUsageEvent> async = new CopyOnWriteArrayList<>();

        @Override
        public void record(LlmUsageEvent event) {
            sync.add(event);
        }

        @Override
        public void recordAsync(LlmUsageEvent event) {
            async.add(event);
        }

        List<LlmUsageEvent> syncEvents() {
            return sync;
        }

        List<LlmUsageEvent> asyncEvents() {
            return async;
        }
    }
}
