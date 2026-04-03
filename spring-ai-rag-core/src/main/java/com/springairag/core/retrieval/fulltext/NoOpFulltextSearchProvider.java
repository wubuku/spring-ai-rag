package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;

import java.util.Collections;
import java.util.List;

/**
 * 空全文检索策略
 *
 * <p>当 pg_trgm 和 pg_jieba 都不可用时的降级方案。
 * 所有检索返回空列表，系统退化为纯向量检索。
 */
public class NoOpFulltextSearchProvider implements FulltextSearchProvider {

    @Override
    public String getName() {
        return "none";
    }

    @Override
    public boolean isAvailable() {
        return true; // 始终可用（返回空结果）
    }

    @Override
    public List<RetrievalResult> search(String query, List<Long> documentIds,
                                        List<Long> excludeIds, int limit, double minScore) {
        return Collections.emptyList();
    }
}
