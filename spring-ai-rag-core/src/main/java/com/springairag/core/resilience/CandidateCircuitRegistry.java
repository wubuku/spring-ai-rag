package com.springairag.core.resilience;

import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.LlmCircuitOpenException;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public final class CandidateCircuitRegistry {
    private final RagProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<Key, LlmCircuitBreaker> circuits = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public CandidateCircuitRegistry(RagProperties properties) {
        this(properties, Clock.systemUTC());
    }

    CandidateCircuitRegistry(RagProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Permit acquire(ChatModel model) {
        if (!properties.getCircuitBreaker().isEnabled()) return new Permit(null, 0);
        String configuredModel = model.getDefaultOptions() == null ? "" : model.getDefaultOptions().getModel();
        var key = new Key(model, configuredModel);
        LlmCircuitBreaker circuit = circuits.get(key);
        if (circuit == null) {
            synchronized (circuits) {
                circuit = circuits.get(key);
                if (circuit == null) {
                    if (circuits.size() >= 512) throw new LlmCircuitOpenException();
                    circuit = new LlmCircuitBreaker(properties.getCircuitBreaker(), clock);
                    circuits.put(key, circuit);
                }
            }
        }
        long generation = circuit.acquireGeneration();
        if (generation < 0) throw new LlmCircuitOpenException();
        return new Permit(circuit, generation);
    }

    public static final class Permit implements AutoCloseable {
        private final LlmCircuitBreaker circuit;
        private final long generation;
        private final AtomicBoolean finished = new AtomicBoolean();

        private Permit(LlmCircuitBreaker circuit, long generation) {
            this.circuit = circuit;
            this.generation = generation;
        }

        public void success() {
            if (finished.compareAndSet(false, true) && circuit != null) circuit.recordGenerationSuccess(generation);
        }

        public void failure(Throwable error) {
            if (finished.compareAndSet(false, true) && circuit != null) {
                if (UpstreamFailureClassifier.dependencyFailure(error)) circuit.recordGenerationFailure(generation);
                else circuit.recordGenerationIgnored(generation);
            }
        }

        @Override
        public void close() {
            if (finished.compareAndSet(false, true) && circuit != null) circuit.recordGenerationIgnored(generation);
        }
    }

    private record Key(ChatModel model, String configuredModel) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && model == key.model
                    && java.util.Objects.equals(configuredModel, key.configuredModel);
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(model) + java.util.Objects.hashCode(configuredModel);
        }
    }
}
