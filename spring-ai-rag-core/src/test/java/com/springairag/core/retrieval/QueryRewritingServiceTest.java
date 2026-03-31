package com.springairag.core.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QueryRewritingService 测试
 */
class QueryRewritingServiceTest {

    @Test
    void rewriteQuery_noSynonyms() {
        QueryRewritingService service = new QueryRewritingService();
        List<String> results = service.rewriteQuery("什么是 Spring Boot？");

        assertNotNull(results);
        assertFalse(results.isEmpty());
        assertEquals("什么是 Spring Boot？", results.get(0));
    }

    @Test
    void rewriteQuery_withSynonyms() {
        QueryRewritingService service = new QueryRewritingService();
        service.setSynonymDictionary(java.util.Map.of(
                "退货", new String[]{"退款", "退换货"}
        ));

        List<String> results = service.rewriteQuery("如何退货？");

        assertNotNull(results);
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).contains("退货"), "原始查询应保留在结果中");
    }

    @Test
    void rewriteQuery_emptyQuery() {
        QueryRewritingService service = new QueryRewritingService();
        List<String> results = service.rewriteQuery("");

        assertNotNull(results);
        assertFalse(results.isEmpty());
    }

    @Test
    void rewriteQuery_nullQuery() {
        QueryRewritingService service = new QueryRewritingService();
        // null 查询可能抛异常，验证异常处理
        assertThrows(Exception.class, () -> service.rewriteQuery(null));
    }

    @Test
    void rewriteQuery_withDomainQualifiers() {
        QueryRewritingService service = new QueryRewritingService();
        service.setDomainQualifiers(List.of("在 AI 领域"));

        List<String> results = service.rewriteQuery("什么是向量检索？");

        assertNotNull(results);
        assertFalse(results.isEmpty());
    }
}
