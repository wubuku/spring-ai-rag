package com.springairag.core.retrieval.rerank;

import com.springairag.api.dto.RetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 No-op rerank：原样返回结果、按 ranking depth 截断、空与 null
 * 输入透传。
 */
class NoOpRerankProviderTest {

    private final NoOpRerankProvider provider = new NoOpRerankProvider();

    private RetrievalResult result(String id) {
        RetrievalResult result = new RetrievalResult();
        result.setChunkText(id);
        return result;
    }

    @Test
    void rerankReturnsResultsUnchangedWithinDepth() {
        List<RetrievalResult> results = List.of(result("a"), result("b"));

        List<RetrievalResult> reranked = provider.rerank("query", results, 5);

        assertEquals(results, reranked);
    }

    @Test
    void rerankTruncatesToRankingDepth() {
        List<RetrievalResult> results = List.of(
                result("a"), result("b"), result("c"));

        List<RetrievalResult> reranked = provider.rerank("query", results, 2);

        assertEquals(2, reranked.size());
        assertEquals("a", reranked.get(0).getChunkText());
        assertEquals("b", reranked.get(1).getChunkText());
    }

    @Test
    void rerankHandlesEmptyAndNullInputs() {
        // 空列表原样透传；null 按实现约定透传为 null。
        assertTrue(provider.rerank("query", List.of(), 5).isEmpty());
        assertNull(provider.rerank("query", null, 5));
    }
}
