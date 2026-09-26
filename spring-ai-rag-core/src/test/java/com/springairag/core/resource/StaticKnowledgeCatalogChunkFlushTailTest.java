package com.springairag.core.resource;

import com.springairag.core.config.RagChatProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * StaticKnowledgeCatalog 分块刷新与短摘要长尾（Batch 659，JaCoCo
 * 驱动）：内容积累达到 chunkMaxCharacters 后遇到空行即时刷新分块
 * （避免在空行处继续堆积）、shortDigest 的 null/空白回退。
 */
class StaticKnowledgeCatalogChunkFlushTailTest {

    private String shortDigest(String value) throws Exception {
        Method method = StaticKnowledgeCatalog.class
                .getDeclaredMethod("shortDigest", String.class);
        method.setAccessible(true);
        return (String) method.invoke(
                new StaticKnowledgeCatalog(
                        mock(ResourceCatalog.class),
                        new com.springairag.core.config.RagChatProperties()),
                value);
    }

    @Test
    void shortDigestHandlesNullBlankAndShortValues() throws Exception {
        assertEquals("", shortDigest(null));
        assertEquals("", shortDigest("   "));
        assertEquals("abc", shortDigest("abc"));
        assertEquals("0123456789abcdef", shortDigest("0123456789abcdefgh"));
    }

    @Test
    void blankLineFlushesChunkWhenAccumulatedLengthReachesMax() {
        RagChatProperties properties = new RagChatProperties();
        var config = properties.getStaticKnowledge();
        config.setEnabled(true);
        config.setLocations(List.of("file:crafted"));
        config.setChunkMaxCharacters(50);
        config.setChunkOverlapCharacters(10);

        // 第一行恰好填满 50 字符缓冲，随后的空行应触发分块刷新。
        String line = "w".repeat(50);
        String markdown = line + "\n\nsecond section\n";
        ResourceRoot root = new ResourceRoot(
                ResourceKind.STATIC_KNOWLEDGE, "crafted", "crafted-root");
        ResourceEntry entry = new ResourceEntry(
                root, "doc.md", markdown.getBytes(StandardCharsets.UTF_8));
        ResourceSnapshot snapshot = new ResourceSnapshot(
                ResourceKind.STATIC_KNOWLEDGE, 1L, Instant.now(),
                List.of(entry), "digest-1", List.of(), true);

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

        StaticKnowledgeCatalog knowledgeCatalog = new StaticKnowledgeCatalog(
                catalog, properties);
        knowledgeCatalog.initialize();

        // 刷新出的首块以 50 个 w 开头（截断在空行处而非被合并）。
        assertTrue(knowledgeCatalog.snapshot().chunks().stream()
                .anyMatch(chunk -> chunk.text().startsWith("wwww")));
    }
}
