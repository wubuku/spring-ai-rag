package com.springairag.core.budget;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;

/** LLM 账户预算已达到硬限制。 */
public final class BudgetLimitException extends RagException {

    private final int retryAfterSeconds;

    public BudgetLimitException(String message, int retryAfterSeconds) {
        super(ErrorCode.LLM_BUDGET_EXCEEDED, message, Math.max(1, retryAfterSeconds));
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
