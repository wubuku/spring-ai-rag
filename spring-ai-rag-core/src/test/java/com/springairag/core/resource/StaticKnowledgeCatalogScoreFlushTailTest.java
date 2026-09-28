package com.springairag.core.resource;

import com.springairag.core.config.RagChatProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * StaticKnowledgeCatalog 长尾补充（Batch 698，JaCoCo 驱动）：
 * 拉丁词命中的词覆盖率计分臂（168）、缓冲积累达到
 * chunkMaxCharacters 后遇空行的即时刷新（240-243）。
 *
 * 勿再投入：193（fitCharacters 空结果 break）要求存在空文本
 * chunk，而分块/入缓冲全链路保证 chunk 文本非空；291（chunk()
 * 超长截断）的所有 flush 点均保证文本 ≤ chunkMaxCharacters（长行
 * 由 while 窗口切片）；368/379-380 为 SHA-256 不可用的防御。
 */
class StaticKnowledgeCatalogScoreFlushTailTest {

    private StaticKnowledgeCatalog catalogWithDocument(
            RagChatProperties properties, String markdown) {
        ResourceRoot root = new ResourceRoot(
                ResourceKind.STATIC_KNOWLEDGE, "crafted-698", "crafted-698");
        ResourceEntry entry = new ResourceEntry(
                root, "doc.md", markdown.getBytes(StandardCharsets.UTF_8));
        ResourceSnapshot snapshot = new ResourceSnapshot(
                ResourceKind.STATIC_KNOWLEDGE, 1L, Instant.now(),
                List.of(entry), "digest-698", List.of(), true);
        ResourceCatalog catalog = mock(ResourceCatalog.class);
        when(catalog.discover(
                org.mockito.ArgumentMatchers.eq(ResourceKind.STATIC_KNOWLEDGE),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(snapshot);
        StaticKnowledgeCatalog knowledgeCatalog =
                new StaticKnowledgeCatalog(catalog, properties);
        knowledgeCatalog.initialize();
        return knowledgeCatalog;
    }

    @Test
    void latinTermQueryScoresTermCoverage() {
        RagChatProperties properties = new RagChatProperties();
        RagChatProperties.StaticKnowledgeProperties config =
                properties.getStaticKnowledge();
        config.setEnabled(true);
        config.setLocations(List.of("file:crafted"));
        config.setChunkMaxCharacters(500);
        config.setChunkOverlapCharacters(10);

        StaticKnowledgeCatalog knowledgeCatalog = catalogWithDocument(
                properties, "warranty covers battery replacement\n");

        var results = knowledgeCatalog.search("warranty", 5, 10_000);

        assertFalse(results.isEmpty());
    }

    @Test
    void blankLineFlushesBufferFilledToMaxCharacters() {
        RagChatProperties properties = new RagChatProperties();
        RagChatProperties.StaticKnowledgeProperties config =
                properties.getStaticKnowledge();
        config.setEnabled(true);
        config.setLocations(List.of("file:crafted"));
        config.setChunkMaxCharacters(50);
        config.setChunkOverlapCharacters(10);

        // 两行各 24 字符经 ≤ 路径恰好把缓冲填到 50（含换行），随后的
        // 空行应立即刷新分块。
        String markdown = "w".repeat(24) + "\n"
                + "w".repeat(24) + "\n"
                + "\n"
                + "after section\n";
        StaticKnowledgeCatalog knowledgeCatalog =
                catalogWithDocument(properties, markdown);

        List<StaticKnowledgeChunk> chunks =
                knowledgeCatalog.snapshot().chunks();
        // 50 字符缓冲（含换行）在空行处刷新，trim 后为 49 字符且不含
        // 后续小节内容。
        assertTrue(chunks.stream()
                .anyMatch(chunk -> chunk.text().startsWith("wwww")
                        && chunk.text().endsWith("www")
                        && !chunk.text().contains("after")));
        assertTrue(chunks.stream()
                .anyMatch(chunk -> chunk.text().contains("after section")));
    }
}
