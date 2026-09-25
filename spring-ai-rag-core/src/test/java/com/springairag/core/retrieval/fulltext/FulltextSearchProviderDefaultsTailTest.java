package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.JsonbContainmentFilter;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FulltextSearchProvider 默认方法长尾（Batch 652，JaCoCo 驱动）：
 * filters 过载的三路分发（null / 单 payload / metadata+多 payload
 * fail closed）、detailed 包装的成功与失败臂、SearchResult 记录的
 * 规范构造与候选数钳制。
 */
class FulltextSearchProviderDefaultsTailTest {

    private final List<RetrievalResult> answers = List.of();

    private FulltextSearchProvider provider() {
        return new FulltextSearchProvider() {
            @Override
            public String getName() {
                return "stub";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<RetrievalResult> search(
                    String query, List<Long> documentIds,
                    List<Long> excludeIds, int limit, double minScore,
                    long embeddingProfileId) {
                return answers;
            }
        };
    }

    @Test
    void nullFiltersDelegateToUnfilteredScopeSearch() {
        List<RetrievalResult> results = provider().searchInScope(
                "查询", RetrievalScope.unscoped(), null, 10,
                0.25, 1L, (RetrievalFilters) null);

        assertEquals(answers, results);
    }

    @Test
    void singlePayloadFilterDelegatesToPayloadOverload() {
        RetrievalFilters filters = new RetrievalFilters(
                null, List.of(new JsonbContainmentFilter("{\"k\":\"v\"}")));

        List<RetrievalResult> results = provider().searchInScope(
                "查询", RetrievalScope.unscoped(), null, 10,
                0.25, 1L, filters);

        // 极简 provider 未覆写 payload 过载 → fail closed 空列表。
        assertTrue(results.isEmpty());
    }

    @Test
    void metadataOrMultiPayloadFiltersFailClosed() {
        RetrievalFilters withMetadata = new RetrievalFilters(
                new JsonbContainmentFilter("{\"m\":1}"), List.of());
        assertTrue(provider().searchInScope(
                "查询", RetrievalScope.unscoped(), null, 10,
                0.25, 1L, withMetadata).isEmpty());

        RetrievalFilters withTwoPayloads = new RetrievalFilters(
                null, List.of(
                new JsonbContainmentFilter("{\"a\":1}"),
                new JsonbContainmentFilter("{\"b\":2}")));
        assertTrue(provider().searchInScope(
                "查询", RetrievalScope.unscoped(), null, 10,
                0.25, 1L, withTwoPayloads).isEmpty());
    }

    @Test
    void detailedWrapsSuccessAndFailure() {
        var success = provider().searchInScopeDetailed(
                "查询", RetrievalScope.unscoped(), null, 10,
                0.25, 1L, (RetrievalFilters) null);
        assertNull(success.errorCode());
        assertEquals(answers, success.results());

        FulltextSearchProvider failing = new FulltextSearchProvider() {
            @Override
            public String getName() {
                return "failing";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<RetrievalResult> search(
                    String query, List<Long> documentIds,
                    List<Long> excludeIds, int limit, double minScore,
                    long embeddingProfileId) {
                throw new IllegalStateException("全文本检索崩溃");
            }
        };

        var failure = failing.searchInScopeDetailed(
                "查询", RetrievalScope.unscoped(), null, 10,
                0.25, 1L, (RetrievalFilters) null);
        assertEquals("IllegalStateException", failure.errorCode());
        assertTrue(failure.results().isEmpty());
    }

    @Test
    void searchResultRecordNormalizesAndClampsCandidateCount() {
        var empty = new FulltextSearchProvider.SearchResult(null, "ERR");
        assertTrue(empty.results().isEmpty());
        assertEquals(0, empty.candidateCount());

        var clamped = new FulltextSearchProvider.SearchResult(
                answers, "ERR", -5);
        assertEquals(answers.size(), clamped.candidateCount());
    }
}
