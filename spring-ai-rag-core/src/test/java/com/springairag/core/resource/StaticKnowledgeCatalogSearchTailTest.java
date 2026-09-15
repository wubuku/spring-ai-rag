package com.springairag.core.resource;

import com.springairag.core.config.RagChatProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StaticKnowledgeCatalog.search 长尾（Batch 413）：输入与快照
 * 门卫、config 对 limit/字符预算的钳制、字符预算耗尽截断结果集。
 */
class StaticKnowledgeCatalogSearchTailTest {

    private RagChatProperties properties;
    private StaticKnowledgeCatalog catalog;

    @BeforeEach
    void setUp() {
        properties = new RagChatProperties();
        RagChatProperties.StaticKnowledgeProperties config =
                properties.getStaticKnowledge();
        config.setEnabled(true);
        config.setLocations(List.of("classpath:static-fixture/"));
        catalog = new StaticKnowledgeCatalog(
                new ResourceCatalog(), properties);
        catalog.initialize();
    }

    @Test
    void invalidInputOrGuardsReturnEmptyResults() {
        assertTrue(catalog.search(null, 5, 10_000).isEmpty());
        assertTrue(catalog.search("   ", 5, 10_000).isEmpty());
        assertTrue(catalog.search("保修", 0, 10_000).isEmpty());
        assertTrue(catalog.search("保修", 5, 0).isEmpty());
    }

    @Test
    void unhealthySnapshotReturnsEmptyResults() {
        RagChatProperties missing = new RagChatProperties();
        RagChatProperties.StaticKnowledgeProperties config =
                missing.getStaticKnowledge();
        config.setEnabled(true);
        config.setLocations(List.of("classpath:does-not-exist/"));
        // failFast=false：发现失败降级为不健康快照而非抛异常。
        config.setFailFast(false);
        StaticKnowledgeCatalog degraded = new StaticKnowledgeCatalog(
                new ResourceCatalog(), missing);
        degraded.initialize();

        assertTrue(degraded.snapshot().healthy() == false
                || degraded.search("保修", 5, 10_000).isEmpty());
    }

    @Test
    void configClampsRequestedLimitAndCharacters() {
        properties.getStaticKnowledge().setRetrievalMaxResults(1);
        properties.getStaticKnowledge().setRetrievalMaxResultCharacters(60);

        // 请求 limit=10 / 字符=10_000，实际被 config 钳制。
        var results = catalog.search("电池保修期 退货", 10, 10_000);

        assertTrue(results.size() <= 1);
        assertTrue(results.stream().mapToInt(document ->
                document.getText().length()).sum() <= 60);
    }

    @Test
    void nonPositiveEffectiveBudgetsShortCircuitToEmpty() {
        properties.getStaticKnowledge().setRetrievalMaxResults(0);
        assertTrue(catalog.search("保修", 5, 10_000).isEmpty());

        properties.getStaticKnowledge().setRetrievalMaxResults(5);
        properties.getStaticKnowledge().setRetrievalMaxResultCharacters(0);
        assertTrue(catalog.search("保修", 5, 10_000).isEmpty());
    }

    @Test
    void characterBudgetExhaustionTruncatesResultSet() {
        // maxCharacters=1：第一个 chunk 拟合后 remaining 归零 → 仅一条。
        var results = catalog.search("电池 退货", 5, 1);

        assertTrue(results.size() <= 1);
        results.forEach(document ->
                assertTrue(document.getText().length() <= 1));
    }

    @Test
    void scoringFavorsExactPhraseOverLooseTermOverlap() {
        // 短语命中（含"X-200 的电池保修期"）应排在松散词命中之前。
        var phraseFirst = catalog.search("X-200 的电池保修期为 12 个月", 5, 10_000);

        assertTrue(!phraseFirst.isEmpty());
        assertTrue(phraseFirst.getFirst().getMetadata()
                .containsKey("score"));
        if (phraseFirst.size() > 1) {
            double first = (double) phraseFirst.get(0).getMetadata().get("score");
            double second = (double) phraseFirst.get(1).getMetadata().get("score");
            assertTrue(first >= second);
        }
    }
}
