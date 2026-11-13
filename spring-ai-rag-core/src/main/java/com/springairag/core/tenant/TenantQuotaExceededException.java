package com.springairag.core.tenant;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;

/** WP10：租户级聚合硬阈值已耗尽（不是 billing 结算失败）。 */
public final class TenantQuotaExceededException extends RagException {

    public TenantQuotaExceededException(String message, int retryAfterSeconds) {
        super(ErrorCode.LLM_BUDGET_EXCEEDED, message, Math.max(1, retryAfterSeconds));
    }
}
