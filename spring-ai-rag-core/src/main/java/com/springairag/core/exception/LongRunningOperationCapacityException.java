package com.springairag.core.exception;

import com.springairag.api.enums.ErrorCode;

/** 长任务容量耗尽时提供短暂、有限的客户端重试提示。 */
public class LongRunningOperationCapacityException extends RagException {

    private static final int RETRY_AFTER_SECONDS = 5;

    public LongRunningOperationCapacityException(String message) {
        super(ErrorCode.LONG_RUNNING_OPERATION_CAPACITY_EXCEEDED,
                message, RETRY_AFTER_SECONDS);
    }
}
