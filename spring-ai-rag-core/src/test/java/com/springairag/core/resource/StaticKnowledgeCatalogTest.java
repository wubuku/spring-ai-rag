package com.springairag.core.resource;

import com.springairag.core.config.RagChatProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class StaticKnowledgeCatalogTest {

    @Test
    void retrievesEnglishChineseNumbersAndStableSourcesWithoutEmbedding() {
        RagChatProperties properties = new RagChatProperties();
        RagChatProperties.StaticKnowledgeProperties config =
                properties.getStaticKnowledge();
        config.setEnabled(true);
        config.setLocations(List.of("classpath:static-fixture/"));
        config.setChunkMaxCharacters(200);
        config.setRetrievalMaxResults(1);
        config.setRetrievalMaxResultCharacters(100);

        StaticKnowledgeCatalog catalog = new StaticKnowledgeCatalog(
                new ResourceCatalog(), properties);
        catalog.initialize();

        var chinese = catalog.search("X-200 电池保修期", 5, 10_000);
        var english = catalog.search("support account questions", 5, 10_000);
        var unrelated = catalog.search(
                "静态知识中是否写明了火星往返航班的免费托运行李额度？"
                        + "只根据已有证据回答。",
                5,
                10_000);

        assertTrue(chinese.stream().anyMatch(document ->
                document.getText().contains("12 个月")));
        assertTrue(english.stream().anyMatch(document ->
                document.getText().contains("support@example.test")));
        assertTrue(unrelated.isEmpty());
        assertTrue(chinese.size() <= 1);
        assertTrue(chinese.stream().mapToInt(document ->
                document.getText().length()).sum() <= 100);
        assertEquals(
                chinese.stream().map(document -> document.getId()).toList(),
                catalog.search("X-200 电池保修期", 5, 10_000).stream()
                        .map(document -> document.getId()).toList());
        assertTrue(chinese.getFirst().getMetadata()
                .containsKey("contentDigest"));
    }

    @Test
    void rejectsInvalidUtf8DuringSnapshotConstruction() {
        ResourceCatalog resourceCatalog = mock(ResourceCatalog.class);
        ResourceRoot root = ResourceCatalog.root(
                ResourceKind.STATIC_KNOWLEDGE, "classpath:static-fixture/");
        org.mockito.Mockito.when(resourceCatalog.discover(
                org.mockito.ArgumentMatchers.eq(ResourceKind.STATIC_KNOWLEDGE),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new ResourceSnapshot(
                        ResourceKind.STATIC_KNOWLEDGE,
                        1,
                        java.time.Instant.now(),
                        List.of(new ResourceEntry(root, "invalid.md",
                                new byte[] {(byte) 0xc3, 0x28})),
                        "digest",
                        List.of(),
                        true));

        RagChatProperties properties = new RagChatProperties();
        properties.getStaticKnowledge().setEnabled(true);
        properties.getStaticKnowledge().setLocations(
                List.of("classpath:static-fixture/"));
        StaticKnowledgeCatalog catalog = new StaticKnowledgeCatalog(
                resourceCatalog, properties);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                catalog::initialize);
    }

    @Test
    void doesNotPublishPartialSnapshotWhenResourceDiscoveryIsDegraded() {
        ResourceCatalog resourceCatalog = mock(ResourceCatalog.class);
        org.mockito.Mockito.when(resourceCatalog.discover(
                org.mockito.ArgumentMatchers.eq(ResourceKind.STATIC_KNOWLEDGE),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new ResourceSnapshot(
                        ResourceKind.STATIC_KNOWLEDGE,
                        2,
                        java.time.Instant.now(),
                        List.of(),
                        "degraded",
                        List.of("root unavailable"),
                        false));

        RagChatProperties properties = new RagChatProperties();
        properties.getStaticKnowledge().setEnabled(true);
        properties.getStaticKnowledge().setLocations(
                List.of("classpath:static-fixture/"));
        StaticKnowledgeCatalog catalog = new StaticKnowledgeCatalog(
                resourceCatalog, properties);

        catalog.initialize();

        assertTrue(!catalog.snapshot().healthy());
        assertTrue(catalog.snapshot().chunks().isEmpty());
        assertTrue(catalog.search("保修期", 5, 1_000).isEmpty());
    }

    @Test
    void splitsAnOversizedFirstParagraphWithoutCreatingAnEmptyChunk() {
        ResourceCatalog resourceCatalog = mock(ResourceCatalog.class);
        ResourceRoot root = ResourceCatalog.root(
                ResourceKind.STATIC_KNOWLEDGE, "classpath:long-static-fixture/");
        String content = "Warranty " + "x".repeat(120);
        org.mockito.Mockito.when(resourceCatalog.discover(
                org.mockito.ArgumentMatchers.eq(ResourceKind.STATIC_KNOWLEDGE),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new ResourceSnapshot(
                        ResourceKind.STATIC_KNOWLEDGE,
                        1,
                        java.time.Instant.now(),
                        List.of(new ResourceEntry(
                                root,
                                "long.md",
                                content.getBytes(StandardCharsets.UTF_8))),
                        "digest",
                        List.of(),
                        true));

        RagChatProperties properties = new RagChatProperties();
        properties.getStaticKnowledge().setEnabled(true);
        properties.getStaticKnowledge().setLocations(
                List.of("classpath:long-static-fixture/"));
        properties.getStaticKnowledge().setChunkMaxCharacters(32);
        properties.getStaticKnowledge().setChunkOverlapCharacters(4);
        StaticKnowledgeCatalog catalog = new StaticKnowledgeCatalog(
                resourceCatalog, properties);

        catalog.initialize();

        assertTrue(catalog.snapshot().chunks().size() >= 4);
        assertTrue(catalog.snapshot().chunks().stream()
                .allMatch(chunk -> !chunk.text().isBlank()
                        && chunk.text().length() <= 32));
        assertTrue(!catalog.search("Warranty", 5, 1_000).isEmpty());
    }
}
