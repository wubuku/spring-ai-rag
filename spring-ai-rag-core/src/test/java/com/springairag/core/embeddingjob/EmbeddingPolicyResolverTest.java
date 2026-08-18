package com.springairag.core.embeddingjob;

import com.springairag.api.enums.EmbeddingPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbeddingPolicyResolverTest {

    @Test
    void policyIsAuthoritativeOverLegacyEmbedFlag() {
        assertEquals(EmbeddingPolicy.ASYNC,
                EmbeddingPolicyResolver.resolve(EmbeddingPolicy.ASYNC, false));
        assertEquals(EmbeddingPolicy.SKIP,
                EmbeddingPolicyResolver.resolve(null, false));
        assertEquals(EmbeddingPolicy.SYNC,
                EmbeddingPolicyResolver.resolve(null, true));
    }
}
