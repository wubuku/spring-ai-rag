package com.springairag.core.admission;

public final class BackgroundWorkScope implements AutoCloseable {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();
    private final Boolean previous = ACTIVE.get();

    private BackgroundWorkScope() {
        ACTIVE.set(true);
    }

    public static BackgroundWorkScope open() { return new BackgroundWorkScope(); }
    public static boolean isActive() { return Boolean.TRUE.equals(ACTIVE.get()); }

    @Override
    public void close() {
        if (previous == null) {
            ACTIVE.remove();
        } else {
            ACTIVE.set(previous);
        }
    }
}
