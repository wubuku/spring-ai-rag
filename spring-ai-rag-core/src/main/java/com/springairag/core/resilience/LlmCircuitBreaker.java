package com.springairag.core.resilience;

import com.springairag.core.config.EmbeddingCircuitBreakerProperties;
import com.springairag.core.config.RagCircuitBreakerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker for LLM calls
 *
 * <p>Three-state state machine:
 * <ul>
 *   <li>CLOSED — normal, allows calls; failure count threshold triggers OPEN</li>
 *   <li>OPEN — open, immediately rejects calls; cooldown expires transitions to HALF_OPEN</li>
 *   <li>HALF_OPEN — probing; allows one test call: success→CLOSED, failure→OPEN</li>
 * </ul>
 */
public class LlmCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(LlmCircuitBreaker.class);

    /** Slot state: true = success, false = failure */
    private static final boolean SUCCESS = true;
    private static final boolean FAILURE = false;

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    /** Ring buffer entries: index = callCount % windowSize */
    private final boolean[] results;
    /** How many calls have been made (determines ring buffer position) */
    private final AtomicInteger callCount = new AtomicInteger(0);
    /** How many slots in results[] are currently valid (0..windowSize) */
    private final AtomicInteger filledSlots = new AtomicInteger(0);
    private final AtomicInteger successes = new AtomicInteger(0);
    private final AtomicInteger failures = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicInteger halfOpenAttempts = new AtomicInteger(0);

    private final int failureRateThreshold;
    private final int minimumNumberOfCalls;
    private final long waitDurationInOpenStateMillis;
    private final int windowSize;

    public LlmCircuitBreaker(RagCircuitBreakerProperties config) {
        this.windowSize = config.getSlidingWindowSize() > 0 ? config.getSlidingWindowSize() : 20;
        this.results = new boolean[windowSize];
        this.failureRateThreshold = config.getFailureRateThreshold();
        this.minimumNumberOfCalls = config.getMinimumNumberOfCalls();
        this.waitDurationInOpenStateMillis = config.getWaitDurationInOpenStateSeconds() * 1000L;
    }

    /**
     * Constructor for embedding circuit breaker, accepting EmbeddingCircuitBreakerProperties.
     * Both property types have identical structure, so the logic is identical.
     */
    public LlmCircuitBreaker(EmbeddingCircuitBreakerProperties config) {
        this.windowSize = config.getSlidingWindowSize() > 0 ? config.getSlidingWindowSize() : 20;
        this.results = new boolean[windowSize];
        this.failureRateThreshold = config.getFailureRateThreshold();
        this.minimumNumberOfCalls = config.getMinimumNumberOfCalls();
        this.waitDurationInOpenStateMillis = config.getWaitDurationInOpenStateSeconds() * 1000L;
    }

    /** Attempt to acquire execution permit */
    public boolean allowCall() {
        State current = state.get();
        if (current == State.CLOSED) {
            return true;
        }
        if (current == State.OPEN) {
            if (shouldAttemptReset()) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    halfOpenAttempts.set(0);
                    log.info("Circuit breaker transitioning OPEN → HALF_OPEN");
                    return true;
                }
            }
            return false;
        }
        // HALF_OPEN: one test call at a time
        return halfOpenAttempts.compareAndSet(0, 1);
    }

    private boolean shouldAttemptReset() {
        return System.currentTimeMillis() - lastFailureTime.get() >= waitDurationInOpenStateMillis;
    }

    /** Record success */
    public void recordSuccess() {
        State current = state.get();
        if (current == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                resetWindow();
                log.info("Circuit breaker HALF_OPEN → CLOSED (test call succeeded)");
            }
        } else if (current == State.CLOSED) {
            addResult(SUCCESS);
        }
    }

    /** Record failure */
    public void recordFailure() {
        lastFailureTime.set(System.currentTimeMillis());
        State current = state.get();
        if (current == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                log.info("Circuit breaker HALF_OPEN → OPEN (test call failed)");
            }
        } else if (current == State.CLOSED) {
            addResult(FAILURE);
            checkFailureRate();
        }
    }

    private void addResult(boolean result) {
        int idx = callCount.getAndIncrement();
        int pos = idx % windowSize;

        if (filledSlots.get() < windowSize) {
            // Buffer not yet full — just add
            filledSlots.incrementAndGet();
            results[pos] = result;
            if (result) {
                successes.incrementAndGet();
            } else {
                failures.incrementAndGet();
            }
        } else {
            // Buffer full — evict slot at pos, then overwrite
            boolean evicted = results[pos];
            results[pos] = result;
            if (evicted) {
                successes.decrementAndGet();
            } else {
                failures.decrementAndGet();
            }
            if (result) {
                successes.incrementAndGet();
            } else {
                failures.incrementAndGet();
            }
        }
    }

    private void checkFailureRate() {
        int total = successes.get() + failures.get();
        if (total < minimumNumberOfCalls) {
            return;
        }
        int rate = (failures.get() * 100) / total;
        if (rate >= failureRateThreshold) {
            if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                log.warn("Circuit breaker CLOSED → OPEN (failure rate {}% >= {}%, {}/{} calls)",
                        rate, failureRateThreshold, failures.get(), total);
            }
        }
    }

    private void resetWindow() {
        successes.set(0);
        failures.set(0);
        filledSlots.set(0);
        callCount.set(0);
    }

    public State getState() {
        return state.get();
    }

    public int getSuccesses() {
        return successes.get();
    }

    public int getFailures() {
        return failures.get();
    }

    public int getFilledSlots() {
        return filledSlots.get();
    }

    public String getStats() {
        return String.format("state=%s successes=%d failures=%d filled=%d lastFailureAgeMs=%d",
                state.get(), successes.get(), failures.get(), filledSlots.get(),
                state.get() == State.CLOSED ? 0 : System.currentTimeMillis() - lastFailureTime.get());
    }

    /**
     * Get timestamp of most recent failure (milliseconds)
     *
     * @return Most recent failure timestamp, or 0 if never failed
     */
    public long getLastFailureTimeMillis() {
        return lastFailureTime.get();
    }
}
