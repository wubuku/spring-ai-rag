package com.springairag.core.embeddingjob;

import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;

/**
 * HTTP 层 embeddingPolicy 校验：显式 embed 入口拒绝 SKIP。
 */
public final class EmbeddingPolicySupport {

    private EmbeddingPolicySupport() {
    }

    public static EmbeddingPolicy requireRequested(
            EmbeddingPolicy policy,
            boolean embedDefault) {
        return EmbeddingPolicyResolver.resolve(policy, embedDefault);
    }

    public static EmbeddingPolicy requireEmbed(
            EmbeddingPolicy policy,
            boolean embedDefault) {
        EmbeddingPolicy resolved = requireRequested(policy, embedDefault);
        if (resolved == EmbeddingPolicy.SKIP) {
            throw new RagException(
                    ErrorCode.BAD_REQUEST,
                    "embeddingPolicy=SKIP is not allowed on explicit embed endpoints");
        }
        return resolved;
    }

    public static void requireJobsEnabled(EmbeddingDispatchService dispatchService) {
        if (dispatchService == null) {
            throw new RagException(
                    ErrorCode.EMBEDDING_JOBS_DISABLED,
                    "Persistent embedding jobs are disabled");
        }
    }
}
