package com.springairag.core.embeddingjob;

import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** HTTP 层 embeddingPolicy 校验：显式 embed 入口拒绝 SKIP、任务门禁。 */
class EmbeddingPolicySupportTest {

    @Test
    void resolvesRequestedPoliciesThroughTheResolver() {
        assertSame(EmbeddingPolicy.ASYNC,
                EmbeddingPolicySupport.requireRequested(EmbeddingPolicy.ASYNC, true));
        assertSame(EmbeddingPolicy.SYNC,
                EmbeddingPolicySupport.requireRequested(null, true));
        assertSame(EmbeddingPolicy.SKIP,
                EmbeddingPolicySupport.requireRequested(null, false));
    }

    @Test
    void acceptsNonSkipPoliciesOnExplicitEmbedEndpoints() {
        assertSame(EmbeddingPolicy.SYNC,
                EmbeddingPolicySupport.requireEmbed(EmbeddingPolicy.SYNC, true));
        assertSame(EmbeddingPolicy.ASYNC,
                EmbeddingPolicySupport.requireEmbed(EmbeddingPolicy.ASYNC, true));
    }

    @Test
    void rejectsSkipOnExplicitEmbedEndpoints() {
        RagException error = assertThrows(RagException.class,
                () -> EmbeddingPolicySupport.requireEmbed(EmbeddingPolicy.SKIP, true));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCodeEnum());
    }

    @Test
    void rejectsNullDispatchServiceWithJobsDisabled() {
        RagException error = assertThrows(RagException.class,
                () -> EmbeddingPolicySupport.requireJobsEnabled(null));

        assertEquals(ErrorCode.EMBEDDING_JOBS_DISABLED, error.getErrorCodeEnum());
    }

    @Test
    void acceptsAPresentDispatchService() {
        EmbeddingPolicySupport.requireJobsEnabled(
                mock(EmbeddingDispatchService.class));
    }
}
