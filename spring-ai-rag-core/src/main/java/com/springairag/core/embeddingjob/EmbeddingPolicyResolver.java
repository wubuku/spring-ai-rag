package com.springairag.core.embeddingjob;

import com.springairag.api.enums.EmbeddingPolicy;

/**
 * 兼容旧 embed 标志与新 embeddingPolicy。policy 优先。
 */
public final class EmbeddingPolicyResolver {

    private EmbeddingPolicyResolver() {
    }

    public static EmbeddingPolicy resolve(
            EmbeddingPolicy policy,
            Boolean embed,
            EmbeddingPolicy omittedDefault) {
        if (policy != null) {
            return policy;
        }
        if (embed == null) {
            return omittedDefault != null ? omittedDefault : EmbeddingPolicy.SYNC;
        }
        return Boolean.TRUE.equals(embed)
                ? EmbeddingPolicy.SYNC
                : EmbeddingPolicy.SKIP;
    }

    public static EmbeddingPolicy resolve(
            EmbeddingPolicy policy,
            boolean embed) {
        return resolve(policy, embed, EmbeddingPolicy.SYNC);
    }
}
