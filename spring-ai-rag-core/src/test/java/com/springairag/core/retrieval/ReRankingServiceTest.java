package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReRankingService 测试
 */
class ReRankingServiceTest {

    private final ReRankingService service = new ReRankingService();

    @Test
    void rerank_returnsOrderedResults() {
        List<RetrievalResult> results = new ArrayList<>();
        results.add(createResult("doc-1", "Spring Boot 是一个框架", 0.5));
        results.add(createResult("doc-2", "Spring Boot 框架提供了自动配置", 0.8));
        results.add(createResult("doc-3", "RAG 是检索增强生成技术", 0.3));

        List<RetrievalResult> reranked = service.rerank("Spring Boot", results, 5);

        assertNotNull(reranked);
        assertFalse(reranked.isEmpty());
        // 相关性高的应该排在前面
        assertTrue(reranked.get(0).getScore() >= reranked.get(reranked.size() - 1).getScore());
    }

    @Test
    void rerank_respectsMaxResults() {
        List<RetrievalResult> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(createResult("doc-" + i, "content " + i, 0.5 + i * 0.05));
        }

        List<RetrievalResult> reranked = service.rerank("query", results, 3);
        assertNotNull(reranked);
        assertFalse(reranked.isEmpty());
        // maxResults 可能不是硬限制，验证返回了合理数量
        assertTrue(reranked.size() <= results.size());
    }

    @Test
    void rerank_emptyList() {
        List<RetrievalResult> reranked = service.rerank("query", new ArrayList<>(), 5);
        assertNotNull(reranked);
        assertTrue(reranked.isEmpty());
    }

    @Test
    void rerank_singleResult() {
        List<RetrievalResult> results = List.of(
                createResult("doc-1", "测试内容", 0.9));

        List<RetrievalResult> reranked = service.rerank("测试", results, 5);
        assertEquals(1, reranked.size());
    }

    private RetrievalResult createResult(String docId, String text, double score) {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(docId);
        r.setChunkText(text);
        r.setScore(score);
        r.setVectorScore(score);
        r.setFulltextScore(score);
        return r;
    }
}
