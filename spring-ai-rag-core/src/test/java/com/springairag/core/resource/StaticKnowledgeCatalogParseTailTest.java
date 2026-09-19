package com.springairag.core.resource;

import com.springairag.core.config.RagChatProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * StaticKnowledgeCatalog 解析与检索长尾（Batch 530，JaCoCo 驱
 * 动）：标题路径压栈与回退、块超限切分（含 overlap 回看）、空白
 * 行边界落块、单块截断、CJK 覆盖与拉丁词项评分、多块排序。
 */
class StaticKnowledgeCatalogParseTailTest {

    private static final String MARKDOWN =
            "# Top Guide\n"
            + "intro paragraph about setup\n"
            + "\n"
            + "## Alpha Section\n"
            + "X".repeat(80)
            + "\n"
            + "### Deep Beta\n"
            + "beta explains keyword battery\n"
            + "\n"
            + "## Gamma Returns\n"
            + "gamma mentions 电池保修期 twelve months\n";

    private StaticKnowledgeCatalog catalogWith(RagChatProperties properties,
                                               String markdown) {
        ResourceCatalog resourceCatalog = mock(ResourceCatalog.class);
        ResourceRoot root = ResourceCatalog.root(
                ResourceKind.STATIC_KNOWLEDGE, "classpath:fixture/");
        when(resourceCatalog.discover(
                eq(ResourceKind.STATIC_KNOWLEDGE),
                anyList(), anySet(), anyInt(), anyInt(), anyInt(),
                anyBoolean()))
                .thenReturn(new ResourceSnapshot(
                        ResourceKind.STATIC_KNOWLEDGE,
                        1,
                        Instant.now(),
                        List.of(new ResourceEntry(root, "guide.md",
                                markdown.getBytes(StandardCharsets.UTF_8))),
                        "digest-value",
                        List.of(),
                        true));
        StaticKnowledgeCatalog catalog =
                new StaticKnowledgeCatalog(resourceCatalog, properties);
        catalog.initialize();
        return catalog;
    }

    private RagChatProperties properties(int chunkMax, int overlap) {
        RagChatProperties properties = new RagChatProperties();
        RagChatProperties.StaticKnowledgeProperties config =
                properties.getStaticKnowledge();
        config.setEnabled(true);
        config.setLocations(List.of("classpath:fixture/"));
        config.setChunkMaxCharacters(chunkMax);
        config.setChunkOverlapCharacters(overlap);
        config.setRetrievalMaxResults(10);
        config.setRetrievalMaxResultCharacters(10_000);
        return properties;
    }

    @Test
    void parseSplitsHeadingsLongLinesAndBlankBoundaries() {
        StaticKnowledgeCatalog catalog =
                catalogWith(properties(60, 10), MARKDOWN);

        var chunks = catalog.snapshot().chunks();

        assertTrue(chunks.size() >= 3, "标题/超长行/边界都应产块");
        assertTrue(chunks.stream().allMatch(chunk ->
                chunk.id().startsWith("static:")));
        // 标题路径压栈：Alpha 与 Deep Beta 的 titlePath 均含父级。
        assertTrue(chunks.stream().anyMatch(chunk ->
                chunk.titlePath().contains("Top Guide / Alpha Section")));
        assertTrue(chunks.stream().anyMatch(chunk ->
                chunk.titlePath().contains(
                        "Top Guide / Alpha Section / Deep Beta")));
        // ## Gamma 使标题路径回退到两层。
        assertTrue(chunks.stream().anyMatch(chunk ->
                chunk.titlePath().equals("Top Guide / Gamma Returns")));
        // 单块超限被截断到 chunkMaxCharacters。
        assertTrue(chunks.stream().allMatch(chunk ->
                chunk.text().length() <= 60));
        // 快照摘要指标暴露块数与字节数。
        assertTrue(catalog.snapshot().healthy());
    }

    @Test
    void searchScoresCjkOverlapLatinTermsAndOrdersByScore() {
        StaticKnowledgeCatalog catalog =
                catalogWith(properties(60, 10), MARKDOWN);

        // CJK 覆盖查询：电池保修期 三个字符全部命中 Gamma 块。
        var cjk = catalog.search("电池保修期", 10, 10_000);
        assertTrue(cjk.stream().anyMatch(document ->
                document.getText().contains("电池保修期")));
        assertTrue(((Number) cjk.getFirst().getMetadata().get("score"))
                .doubleValue() > 0);

        // 拉丁词项查询：battery 命中 Deep Beta 块。
        var latin = catalog.search("battery", 10, 10_000);
        assertTrue(latin.stream().anyMatch(document ->
                document.getText().contains("battery")));

        // 短语查询：标题短语命中 Alpha 标题路径。
        var phrase = catalog.search("Alpha Section", 10, 10_000);
        assertTrue(phrase.stream().anyMatch(document ->
                document.getMetadata().get("titlePath")
                        .toString().contains("Alpha Section")));

        // 结果按分数降序排列（多块排序比较器被触发）。
        var scores = cjk.stream().map(document ->
                ((Number) document.getMetadata().get("score")).doubleValue())
                .toList();
        for (int i = 1; i < scores.size(); i++) {
            assertTrue(scores.get(i - 1) >= scores.get(i));
        }
    }

    @Test
    void searchRespectsLimitAndCharacterBudget() {
        StaticKnowledgeCatalog catalog =
                catalogWith(properties(60, 10), MARKDOWN);

        var limited = catalog.search("gamma 电池 battery top intro",
                1, 30);

        assertTrue(limited.size() <= 1);
        assertTrue(limited.stream().mapToInt(document ->
                document.getText().length()).allMatch(len -> len <= 30));
    }

    @Test
    void unhealthySnapshotReturnsNoResults() {
        ResourceCatalog resourceCatalog = mock(ResourceCatalog.class);
        ResourceRoot root = ResourceCatalog.root(
                ResourceKind.STATIC_KNOWLEDGE, "classpath:fixture/");
        when(resourceCatalog.discover(
                eq(ResourceKind.STATIC_KNOWLEDGE),
                anyList(), anySet(), anyInt(), anyInt(), anyInt(),
                anyBoolean()))
                .thenReturn(new ResourceSnapshot(
                        ResourceKind.STATIC_KNOWLEDGE,
                        1,
                        Instant.now(),
                        List.of(),
                        "digest",
                        List.of("degraded-root"),
                        false));
        StaticKnowledgeCatalog catalog =
                new StaticKnowledgeCatalog(resourceCatalog, properties(60, 10));
        catalog.initialize();

        assertTrue(catalog.search("anything", 5, 100).isEmpty());
    }
}
