package com.springairag.core.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QueryRewritingService 测试
 */
class QueryRewritingServiceTest {

    private QueryRewritingService service;

    @BeforeEach
    void setUp() {
        service = new QueryRewritingService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "paddingCount", 2);
    }

    // ========== rewriteQuery ==========

    @Test
    void rewriteQuery_noSynonyms_returnsOriginalOnly() {
        List<String> results = service.rewriteQuery("什么是 Spring Boot？");
        assertEquals(1, results.size());
        assertEquals("什么是 Spring Boot？", results.get(0));
    }

    @Test
    void rewriteQuery_withSynonyms_expandsQuery() {
        service.setSynonymDictionary(Map.of(
                "退货", new String[]{"退款", "退换货"}
        ));

        List<String> results = service.rewriteQuery("如何退货？");
        assertTrue(results.size() > 1, "同义词扩展应产生多个查询");
        assertTrue(results.get(0).contains("退货"), "原始查询应保留");

        boolean hasReplacement = results.stream()
                .anyMatch(q -> q.contains("退款") || q.contains("退换货"));
        assertTrue(hasReplacement, "应包含同义词替换的查询");
    }

    @Test
    void rewriteQuery_synonymCaseInsensitive() {
        service.setSynonymDictionary(Map.of(
                "spring", new String[]{"springboot"}
        ));

        List<String> results = service.rewriteQuery("Spring Boot");
        assertTrue(results.size() > 1, "大小写不敏感匹配应生效");
    }

    @Test
    void rewriteQuery_multipleSynonyms() {
        service.setSynonymDictionary(Map.of(
                "皮肤", new String[]{"肌肤", "肤质"},
                "敏感", new String[]{"过敏", "易敏"}
        ));

        List<String> results = service.rewriteQuery("敏感皮肤怎么办");
        assertTrue(results.size() >= 3, "多个关键词同义词应都展开");
    }

    @Test
    void rewriteQuery_withDomainQualifiers() {
        service.setDomainQualifiers(List.of("皮肤科", "医学"));

        List<String> results = service.rewriteQuery("什么是向量检索？");
        assertTrue(results.size() > 1, "领域限定词应扩展查询");

        boolean hasQualifier = results.stream()
                .anyMatch(q -> q.contains("皮肤科") || q.contains("医学"));
        assertTrue(hasQualifier, "应包含领域限定词的查询");
    }

    @Test
    void rewriteQuery_qualifierAlreadyInQuery_notAdded() {
        service.setDomainQualifiers(List.of("皮肤科"));

        List<String> results = service.rewriteQuery("皮肤科挂号");
        boolean hasDuplicate = results.stream()
                .anyMatch(q -> q.equals("皮肤科挂号 皮肤科"));
        assertFalse(hasDuplicate, "查询中已有的限定词不应重复添加");
    }

    @Test
    void rewriteQuery_emptyQuery_returnsOriginal() {
        List<String> results = service.rewriteQuery("");
        assertFalse(results.isEmpty());
    }

    @Test
    void rewriteQuery_disabled_returnsOriginalOnly() {
        ReflectionTestUtils.setField(service, "enabled", false);
        service.setSynonymDictionary(Map.of("test", new String[]{"synonym"}));

        List<String> results = service.rewriteQuery("test query");
        assertEquals(1, results.size(), "禁用时不应扩展");
        assertEquals("test query", results.get(0));
    }

    @Test
    void rewriteQuery_sameSynonymDistinct() {
        // 同义词替换结果 + 组合结果会产生重复，distinct 保留不同形式
        service.setSynonymDictionary(Map.of(
                "test", new String[]{"test"}
        ));

        List<String> results = service.rewriteQuery("test");
        // "test"(原) + "test"(替换) + "test test"(组合) → distinct 后为 ["test", "test test"]
        assertEquals(2, results.size());
        assertTrue(results.contains("test"));
        assertTrue(results.contains("test test"));
    }

    // ========== generatePaddingQueries ==========

    @Test
    void generatePaddingQueries_addsPrefixes() {
        ReflectionTestUtils.setField(service, "paddingCount", 10);
        List<String> padding = service.generatePaddingQueries("向量检索");
        assertFalse(padding.isEmpty());
        boolean hasPrefix = padding.stream()
                .anyMatch(q -> q.startsWith("如何") || q.startsWith("怎么")
                        || q.startsWith("怎样") || q.startsWith("什么"));
        assertTrue(hasPrefix, "应添加疑问前缀");
    }

    @Test
    void generatePaddingQueries_addsSuffixes() {
        ReflectionTestUtils.setField(service, "paddingCount", 10);
        List<String> padding = service.generatePaddingQueries("向量检索");
        boolean hasSuffix = padding.stream()
                .anyMatch(q -> q.endsWith("怎么办") || q.endsWith("如何解决") || q.endsWith("用什么"));
        assertTrue(hasSuffix, "应添加解决方案后缀");
    }

    @Test
    void generatePaddingQueries_respectsPaddingCount() {
        ReflectionTestUtils.setField(service, "paddingCount", 2);
        List<String> padding = service.generatePaddingQueries("Spring Boot 配置");
        assertTrue(padding.size() <= 2, "应受 paddingCount 限制");
    }

    @Test
    void generatePaddingQueries_customPaddingCount() {
        ReflectionTestUtils.setField(service, "paddingCount", 1);
        List<String> padding = service.generatePaddingQueries("向量检索");
        assertEquals(1, padding.size(), "应受自定义 paddingCount=1 限制");
    }

    @Test
    void generatePaddingQueries_emptyQuery_returnsEmpty() {
        List<String> padding = service.generatePaddingQueries("");
        assertTrue(padding.isEmpty());
    }

    @Test
    void generatePaddingQueries_nullQuery_returnsEmpty() {
        List<String> padding = service.generatePaddingQueries(null);
        assertTrue(padding.isEmpty());
    }

    @Test
    void generatePaddingQueries_disabled_returnsEmpty() {
        ReflectionTestUtils.setField(service, "enabled", false);
        List<String> padding = service.generatePaddingQueries("向量检索");
        assertTrue(padding.isEmpty(), "禁用时不应生成 padding 查询");
    }

    @Test
    void generatePaddingQueries_keywordCombination() {
        ReflectionTestUtils.setField(service, "paddingCount", 10);
        List<String> padding = service.generatePaddingQueries("向量 数据库 检索");
        boolean hasCombo = padding.stream().anyMatch(q -> q.contains("和"));
        assertTrue(hasCombo, "多关键词应生成组合查询");
    }

    // ========== cleanQuery ==========

    @Test
    void cleanQuery_removesExtraSpaces() {
        assertEquals("hello world", service.cleanQuery("  hello   world  "));
    }

    @Test
    void cleanQuery_removesSpecialChars() {
        assertEquals("hello world", service.cleanQuery("hello, world!"));
    }

    @Test
    void cleanQuery_stripsChinesePunctuation() {
        // 中文标点被移除，字符直接相连
        assertEquals("你好世界", service.cleanQuery("你好，世界！"));
    }

    @Test
    void cleanQuery_preservesAlphanumeric() {
        assertEquals("abc123 test", service.cleanQuery("abc123 test!@#"));
    }

    @Test
    void cleanQuery_null_returnsEmpty() {
        assertEquals("", service.cleanQuery(null));
    }

    @Test
    void cleanQuery_emptyString() {
        assertEquals("", service.cleanQuery(""));
    }
}
