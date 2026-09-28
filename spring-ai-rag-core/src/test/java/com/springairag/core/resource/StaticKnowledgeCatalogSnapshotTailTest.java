package com.springairag.core.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StaticKnowledgeCatalog Snapshot 归一长尾（Batch 693，JaCoCo 驱
 * 动）：紧凑构造器对 null digest 与 null chunks 的回退。
 */
class StaticKnowledgeCatalogSnapshotTailTest {

    @Test
    void snapshotNormalizesNullDigestAndChunks() {
        var snapshot = new StaticKnowledgeCatalog.Snapshot(
                3L, null, true, null);

        assertEquals(3L, snapshot.generation());
        assertEquals("", snapshot.digest());
        assertTrue(snapshot.healthy());
        assertNotNull(snapshot.chunks());
        assertTrue(snapshot.chunks().isEmpty());

        // 非 null 输入原样保留（拷贝为不可变列表）。
        var populated = new StaticKnowledgeChunk(
                "static:chunk-1", "root-1", "source", "doc.md", "digest-1",
                0, "title", "正文内容",
                java.util.List.of(),
                java.util.Map.of());
        var populatedSnapshot = new StaticKnowledgeCatalog.Snapshot(
                1L, "digest-1", false, java.util.List.of(populated));
        assertEquals(1, populatedSnapshot.chunks().size());
        assertEquals("digest-1", populatedSnapshot.digest());
    }
}
