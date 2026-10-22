package com.springairag.core.exception;

import com.springairag.api.enums.ErrorCode;

/** 异步清理预览扫描超过服务端硬 deadline。 */
public class CollectionPurgePreviewTimeoutException extends RagException {

    private static final int RETRY_AFTER_SECONDS = 5;

    public CollectionPurgePreviewTimeoutException() {
        super(ErrorCode.COLLECTION_PURGE_PREVIEW_TIMEOUT,
                "Collection purge preview scan exceeded its time budget",
                RETRY_AFTER_SECONDS);
    }
}
