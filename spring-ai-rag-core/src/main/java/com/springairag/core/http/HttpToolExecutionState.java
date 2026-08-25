package com.springairag.core.http;

/**
 * Request-local byte budget shared by all allowlisted HTTP endpoint calls.
 */
public final class HttpToolExecutionState {

    public static final String CONTEXT_KEY = "rag.chat.http-tool-state";

    private final long maxResponseBytes;
    private long responseBytes;
    private long reservedBytes;

    public HttpToolExecutionState(long maxResponseBytes) {
        this.maxResponseBytes = Math.max(1L, maxResponseBytes);
    }

    public synchronized ResponseReservation reserveUpTo(long requestedBytes) {
        if (requestedBytes < 1) {
            return null;
        }
        long remaining = maxResponseBytes - responseBytes - reservedBytes;
        if (remaining <= 0) {
            return null;
        }
        long reserved = Math.min(requestedBytes, remaining);
        reservedBytes += reserved;
        return new ResponseReservation(reserved);
    }

    public synchronized void commit(
            ResponseReservation reservation,
            long actualBytes) {
        if (reservation == null || !reservation.settle()) {
            return;
        }
        reservedBytes = Math.max(0, reservedBytes - reservation.maximumBytes());
        responseBytes += Math.max(
                0,
                Math.min(actualBytes, reservation.maximumBytes()));
    }

    public synchronized void release(ResponseReservation reservation) {
        if (reservation == null || !reservation.settle()) {
            return;
        }
        reservedBytes = Math.max(0, reservedBytes - reservation.maximumBytes());
    }

    public synchronized long responseBytes() {
        return responseBytes;
    }

    public synchronized long reservedBytes() {
        return reservedBytes;
    }

    public synchronized long remainingBytes() {
        return maxResponseBytes - responseBytes - reservedBytes;
    }

    public long maxResponseBytes() {
        return maxResponseBytes;
    }

    public static final class ResponseReservation {
        private final long maximumBytes;
        private boolean settled;

        private ResponseReservation(long maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        public long maximumBytes() {
            return maximumBytes;
        }

        private synchronized boolean settle() {
            if (settled) {
                return false;
            }
            settled = true;
            return true;
        }
    }
}
