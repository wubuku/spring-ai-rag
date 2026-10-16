package com.springairag.core.chat;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.budget.BudgetReservationService;
import com.springairag.core.budget.BudgetServiceUnavailableException;
import com.springairag.core.exception.RagException;
import com.springairag.core.usage.LlmUsageCostCalculator;
import com.springairag.core.usage.LlmInvocationOutcome;
import com.springairag.core.usage.LlmInvocationPurpose;
import com.springairag.core.usage.LlmUsageEvent;
import com.springairag.core.usage.LlmUsageRecorder;
import com.springairag.core.usage.LlmUsageSnapshot;
import com.springairag.core.config.MultiModelProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    private final LlmInvocationPurpose purpose;
    private final LlmUsageRecorder usageRecorder;
    private final MultiModelProperties.ModelCost modelCost;
    private final String costUnit;
    private final String modelRef;
    private final BudgetReservationService reservationService;

    public BudgetedChatModel(ChatModel delegate, ChatExecutionBudget budget) {
        this(delegate, budget, 0, 0, 0, Integer.MAX_VALUE,
                new JTokkitPromptTokenEstimator(),
                LlmInvocationPurpose.CHAT, LlmUsageRecorder.NOOP, null,
                "CONFIGURED_MODEL_COST", null);
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
                safetyMarginTokens, maxToolSchemaTokens, estimator,
                LlmInvocationPurpose.CHAT, LlmUsageRecorder.NOOP, null,
                "CONFIGURED_MODEL_COST", null);
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
        this(delegate, budget, contextWindow, outputReserveTokens,
                safetyMarginTokens, maxToolSchemaTokens, estimator,
                summaryCall
                        ? LlmInvocationPurpose.SUMMARY
                        : LlmInvocationPurpose.CHAT,
                LlmUsageRecorder.NOOP, null, "CONFIGURED_MODEL_COST", null);
    }

    public BudgetedChatModel(
            ChatModel delegate,
            ChatExecutionBudget budget,
            int contextWindow,
            int outputReserveTokens,
            int safetyMarginTokens,
            int maxToolSchemaTokens,
            PromptTokenEstimator estimator,
            LlmInvocationPurpose purpose,
            LlmUsageRecorder usageRecorder,
            MultiModelProperties.ModelCost modelCost,
            String costUnit) {
        this(
                delegate,
                budget,
                contextWindow,
                outputReserveTokens,
                safetyMarginTokens,
                maxToolSchemaTokens,
                estimator,
                purpose,
                usageRecorder,
                modelCost,
                costUnit,
                null,
                null);
    }

    public BudgetedChatModel(
            ChatModel delegate,
            ChatExecutionBudget budget,
            int contextWindow,
            int outputReserveTokens,
            int safetyMarginTokens,
            int maxToolSchemaTokens,
            PromptTokenEstimator estimator,
            LlmInvocationPurpose purpose,
            LlmUsageRecorder usageRecorder,
            MultiModelProperties.ModelCost modelCost,
            String costUnit,
            String modelRef) {
        this(delegate, budget, contextWindow, outputReserveTokens, safetyMarginTokens,
                maxToolSchemaTokens, estimator, purpose, usageRecorder, modelCost,
                costUnit, modelRef, null);
    }

    public BudgetedChatModel(
            ChatModel delegate,
            ChatExecutionBudget budget,
            int contextWindow,
            int outputReserveTokens,
            int safetyMarginTokens,
            int maxToolSchemaTokens,
            PromptTokenEstimator estimator,
            LlmInvocationPurpose purpose,
            LlmUsageRecorder usageRecorder,
            MultiModelProperties.ModelCost modelCost,
            String costUnit,
            String modelRef,
            BudgetReservationService reservationService) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.contextWindow = Math.max(0, contextWindow);
        this.outputReserveTokens = Math.max(0, outputReserveTokens);
        this.safetyMarginTokens = Math.max(0, safetyMarginTokens);
        this.maxToolSchemaTokens = Math.max(1, maxToolSchemaTokens);
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.purpose = purpose != null ? purpose : LlmInvocationPurpose.CHAT;
        this.usageRecorder = usageRecorder != null
                ? usageRecorder : LlmUsageRecorder.NOOP;
        this.modelCost = modelCost;
        this.costUnit = costUnit != null ? costUnit : "CONFIGURED_MODEL_COST";
        this.modelRef = modelRef != null && !modelRef.isBlank()
                ? modelRef.trim()
                : defaultModelRef(delegate);
        this.reservationService = reservationService;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        validatePrompt(prompt);
        int ordinal = budget.reserveModelCall();
        BudgetReservationService.Reservation reservation = reserve(ordinal, prompt);
        if (purpose == LlmInvocationPurpose.SUMMARY) {
            budget.recordSummaryCall();
        }
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();
        try {
            ChatResponse response = delegate.call(prompt);
            record(
                    ordinal,
                    false,
                    LlmInvocationOutcome.SUCCEEDED,
                    usage(response),
                    startedAt,
                    startedNanos,
                    true,
                    reservation);
            return response;
        } catch (RuntimeException error) {
            record(
                    ordinal,
                    false,
                    LlmInvocationOutcome.FAILED,
                    LlmUsageSnapshot.unavailable(),
                    startedAt,
                    startedNanos,
                    true,
                    reservation);
            throw error;
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            validatePrompt(prompt);
            int ordinal = budget.reserveModelCall();
            BudgetReservationService.Reservation reservation = reserve(ordinal, prompt);
            if (purpose == LlmInvocationPurpose.SUMMARY) {
                budget.recordSummaryCall();
            }
            Instant startedAt = Instant.now();
            long startedNanos = System.nanoTime();
            AtomicReference<LlmUsageSnapshot> latestUsage =
                    new AtomicReference<>(LlmUsageSnapshot.unavailable());
            AtomicReference<LlmInvocationOutcome> outcome =
                    new AtomicReference<>(LlmInvocationOutcome.CANCELLED);
            AtomicBoolean recorded = new AtomicBoolean();
            Flux<ChatResponse> delegateFlux;
            try {
                delegateFlux = Objects.requireNonNull(
                        delegate.stream(prompt),
                        "delegate.stream(prompt) must not return null");
            } catch (RuntimeException error) {
                record(
                        ordinal,
                        true,
                        LlmInvocationOutcome.FAILED,
                        LlmUsageSnapshot.unavailable(),
                        startedAt,
                        startedNanos,
                        true,
                        reservation);
                throw error;
            }
            return delegateFlux
                    .doOnNext(response -> {
                        LlmUsageSnapshot snapshot = usage(response);
                        if (snapshot.available()) {
                            latestUsage.set(snapshot);
                        }
                    })
                    .doOnComplete(() ->
                            outcome.set(LlmInvocationOutcome.SUCCEEDED))
                    .doOnError(error ->
                            outcome.set(LlmInvocationOutcome.FAILED))
                    .doFinally(signal -> record(
                            ordinal,
                            true,
                            outcomeFor(signal, outcome.get()),
                            latestUsage.get(),
                            startedAt,
                            startedNanos,
                            recorded.compareAndSet(false, true),
                            reservation));
        });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private LlmInvocationOutcome outcomeFor(
            SignalType signal,
            LlmInvocationOutcome observed) {
        if (signal == SignalType.ON_ERROR) {
            return LlmInvocationOutcome.FAILED;
        }
        if (signal == SignalType.ON_COMPLETE) {
            return LlmInvocationOutcome.SUCCEEDED;
        }
        return observed != null ? observed : LlmInvocationOutcome.CANCELLED;
    }

    private LlmUsageSnapshot usage(ChatResponse response) {
        return response == null || response.getMetadata() == null
                ? LlmUsageSnapshot.unavailable()
                : LlmUsageSnapshot.normalize(response.getMetadata().getUsage());
    }

    private void record(
            int ordinal,
            boolean streaming,
            LlmInvocationOutcome outcome,
            LlmUsageSnapshot usage,
            Instant startedAt,
            long startedNanos,
            boolean shouldRecord,
            BudgetReservationService.Reservation reservation) {
        if (!shouldRecord) {
            return;
        }
        long elapsedNanos = Math.max(0L, System.nanoTime() - startedNanos);
        long durationMs = Math.min(
                LlmUsageEvent.MAX_DURATION_MS,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
        Instant completedAt = Instant.now();
        if (completedAt.isBefore(startedAt)) {
            completedAt = startedAt;
        }
        LlmUsageEvent event = LlmUsageEvent.from(
                budget.attribution(ordinal),
                modelRef,
                modelCost,
                purpose,
                streaming,
                outcome,
                usage,
                durationMs,
                startedAt,
                completedAt,
                costUnit);
        LlmUsageCostCalculator.Result cost = LlmUsageCostCalculator.calculate(
                usage, modelCost, costUnit);
        if (reservationService != null) {
            reservationService.settle(
                    reservation, usage, cost.configuredCost(), cost.costAvailable());
        }
        try {
            if (streaming) {
                usageRecorder.recordAsync(event);
            } else {
                usageRecorder.record(event);
            }
        } catch (RuntimeException ignored) {
            // 记账故障不能替换原始模型结果或触发模型重试。
        }
    }

    private static String defaultModelRef(ChatModel delegate) {
        ChatOptions options = delegate.getDefaultOptions();
        if (options != null && options.getModel() != null
                && !options.getModel().isBlank()) {
            return options.getModel();
        }
        return "UNKNOWN";
    }

    private void validatePrompt(Prompt prompt) {
        if (contextWindow < 1 || prompt == null) {
            return;
        }
        long promptTokens = prompt.getInstructions().stream()
                .mapToLong(estimator::estimate)
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

    private BudgetReservationService.Reservation reserve(int ordinal, Prompt prompt) {
        if (reservationService == null) {
            return BudgetReservationService.Reservation.disabled();
        }
        long estimatedTokens = Math.max(1L,
                estimatedPromptTokens(prompt) + outputReserveTokens + safetyMarginTokens);
        BudgetReservationService.Reservation reservation = reservationService.reserve(
                new BudgetReservationService.ReservationRequest(
                        budget.principalId(), budget.logicalExecutionId(), ordinal,
                        estimatedTokens, estimatedCost(estimatedTokens), budget.deadline()));
        if (!reservation.reusable()) {
            throw new BudgetServiceUnavailableException(
                    "LLM invocation reservation has already been finalized", null);
        }
        return reservation;
    }

    private long estimatedPromptTokens(Prompt prompt) {
        if (prompt == null) {
            return 0;
        }
        long promptTokens = prompt.getInstructions().stream()
                .mapToLong(estimator::estimate)
                .sum();
        long schemaTokens = 0;
        if (prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolCallbacks() != null) {
            for (ToolCallback callback : options.getToolCallbacks()) {
                schemaTokens += estimator.estimate(callback.getToolDefinition());
            }
        }
        return Math.max(0L, promptTokens + schemaTokens);
    }

    private java.math.BigDecimal estimatedCost(long tokens) {
        if (modelCost == null || !Double.isFinite(modelCost.input())
                || !Double.isFinite(modelCost.output())) {
            return java.math.BigDecimal.ZERO.setScale(LlmUsageCostCalculator.SCALE);
        }
        return java.math.BigDecimal.valueOf(tokens)
                .multiply(java.math.BigDecimal.valueOf(
                        Math.max(modelCost.input(), modelCost.output())))
                .divide(java.math.BigDecimal.valueOf(1_000_000L),
                        LlmUsageCostCalculator.SCALE,
                        java.math.RoundingMode.HALF_UP);
    }
}
