package com.springairag.core.exception;

import com.springairag.api.enums.ErrorCode;

/**
 * Signals that another request currently owns the durable Chat operation lease.
 */
public class ChatTurnInProgressException extends RagException {

    private final int retryAfterSeconds;

    public ChatTurnInProgressException(int retryAfterSeconds) {
        super(ErrorCode.IDEMPOTENCY_OPERATION_IN_PROGRESS,
                "The Chat turn is still in progress");
        this.retryAfterSeconds = Math.max(1, Math.min(60, retryAfterSeconds));
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
