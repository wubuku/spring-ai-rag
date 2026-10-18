package com.springairag.core.config;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.resilience.UpstreamFailureClassifier;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.BackOffContext;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.BackOffInterruptedException;
import org.springframework.retry.backoff.Sleeper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

public final class UpstreamBackOffPolicy implements BackOffPolicy {
    public static final String DEADLINE_ATTRIBUTE = "rag.request.deadline";
    private final RagRetryProperties properties;
    private final Sleeper sleeper;
    private final DoubleSupplier random;
    private final Clock clock;

    public UpstreamBackOffPolicy(RagRetryProperties properties) {
        this(properties, Thread::sleep, () -> ThreadLocalRandom.current().nextDouble(), Clock.systemUTC());
    }

    UpstreamBackOffPolicy(RagRetryProperties properties, Sleeper sleeper, DoubleSupplier random, Clock clock) {
        this.properties = properties;
        this.sleeper = sleeper;
        this.random = random;
        this.clock = clock;
    }

    @Override
    public BackOffContext start(RetryContext context) {
        return new State(context);
    }

    @Override
    public void backOff(BackOffContext backOffContext) {
        RetryContext context = ((State) backOffContext).context();
        long maximum = Math.min(10_000, properties.getMaxBackoffMs());
        double exponential = properties.getInitialBackoffMs()
                * Math.pow(properties.getBackoffMultiplier(), Math.max(0, context.getRetryCount() - 1));
        long delay = Math.max(0, Math.min(maximum,
                (long) (exponential * (1 + properties.getJitterFactor() * (2 * random.getAsDouble() - 1)))));
        Long requested = UpstreamFailureClassifier.classify(context.getLastThrowable()).retryAfterMs();
        if (requested != null) {
            if (requested >= maximum) throw lastFailure(context);
            delay = Math.max(delay, requested);
        }
        if (context.getAttribute(DEADLINE_ATTRIBUTE) instanceof Instant deadline
                && Duration.between(clock.instant(), deadline).toMillis() <= delay) {
            throw new RagException(ErrorCode.CHAT_TIMEOUT, "Chat deadline exhausted before retry");
        }
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new BackOffInterruptedException("Retry wait interrupted", error);
        }
    }

    private RuntimeException lastFailure(RetryContext context) {
        return context.getLastThrowable() instanceof RuntimeException runtime ? runtime
                : new IllegalStateException("Retry wait exceeds configured bounds", context.getLastThrowable());
    }

    private record State(RetryContext context) implements BackOffContext { }
}
