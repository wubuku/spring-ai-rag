package com.springairag.core.admission;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class DependencyCallContext implements AutoCloseable {
    private static final ThreadLocal<DependencyCallContext> CURRENT = new ThreadLocal<>();
    private final DependencyCallContext previous;
    private final long deadline;
    private final boolean background;

    private DependencyCallContext(long deadline, boolean background) {
        this.previous = CURRENT.get();
        this.deadline = previous == null ? deadline : Math.min(deadline, previous.deadline);
        this.background = background;
        CURRENT.set(this);
    }

    public static DependencyCallContext open(long deadline, boolean background) {
        return new DependencyCallContext(deadline, background);
    }

    public static DependencyCallContext forBudget(com.springairag.core.chat.ChatExecutionBudget budget) {
        if (budget == null) {
            return open(deadline(Integer.MAX_VALUE), background());
        }
        long remaining = java.time.Duration.between(java.time.Instant.now(), budget.deadline()).toNanos();
        return open(System.nanoTime() + remaining, budget.backgroundWorkload());
    }

    public static boolean background() {
        return CURRENT.get() != null ? CURRENT.get().background : BackgroundWorkScope.isActive();
    }

    public static long deadline(int timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        return CURRENT.get() == null ? deadline : Math.min(deadline, CURRENT.get().deadline);
    }

    public static long remainingMillis() {
        if (Thread.currentThread().isInterrupted()) {
            throw new DependencyCallException("DEPENDENCY_CANCELLED");
        }
        long remaining = CURRENT.get() == null ? Integer.MAX_VALUE
                : TimeUnit.NANOSECONDS.toMillis(CURRENT.get().deadline - System.nanoTime());
        if (remaining <= 0) {
            throw new DependencyCallException("DEPENDENCY_TIMEOUT");
        }
        return remaining;
    }

    public static <Result> Supplier<Result> capture(Supplier<Result> task, int timeoutMs) {
        long deadline = deadline(timeoutMs);
        boolean background = background();
        return () -> {
            try (var scope = open(deadline, background)) {
                remainingMillis();
                return task.get();
            }
        };
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
