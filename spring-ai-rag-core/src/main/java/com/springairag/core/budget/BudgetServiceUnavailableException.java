package com.springairag.core.budget;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;

/** 预算存储不可用；fail-closed，不能调用 provider。 */
public final class BudgetServiceUnavailableException extends RagException {

    public BudgetServiceUnavailableException(String message, Throwable cause) {
        super(ErrorCode.LLM_BUDGET_SERVICE_UNAVAILABLE, message, cause);
    }
}
