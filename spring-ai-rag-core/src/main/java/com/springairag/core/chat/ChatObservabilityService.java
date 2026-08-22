package com.springairag.core.chat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality Chat operation metrics.
 *
 * <p>No principal, key, session, model, provider, prompt, tool name or
 * document identifier is placed in a metric tag.</p>
 */
@Component
public class ChatObservabilityService {

    private final Counter providerCalls;
    private final Counter operationClaims;
    private final Counter operationReplays;
    private final Counter operationFailures;
    private final Counter operationInProgress;

    public ChatObservabilityService(ObjectProvider<MeterRegistry> registries) {
        MeterRegistry registry = registries.getIfAvailable();
        providerCalls = counter(registry, "rag.chat.provider.calls.total");
        operationClaims = counter(registry, "rag.chat.turns.claimed.total");
        operationReplays = counter(registry, "rag.chat.turns.replayed.total");
        operationFailures = counter(registry, "rag.chat.turns.failed.total");
        operationInProgress = counter(registry, "rag.chat.turns.in_progress.total");
    }

    public void providerCall() {
        increment(providerCalls);
    }

    public void claimed() {
        increment(operationClaims);
    }

    public void replayed() {
        increment(operationReplays);
    }

    public void failed() {
        increment(operationFailures);
    }

    public void inProgress() {
        increment(operationInProgress);
    }

    private static Counter counter(MeterRegistry registry, String name) {
        return registry == null
                ? null
                : Counter.builder(name)
                        .description("Low-cardinality Chat operation metric")
                        .register(registry);
    }

    private static void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }
}
