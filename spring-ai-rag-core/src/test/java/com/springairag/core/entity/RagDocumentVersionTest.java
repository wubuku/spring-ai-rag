package com.springairag.core.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RagDocumentVersion — Document Version History Entity")
class RagDocumentVersionTest {

    @Test
    @DisplayName("fromDocument factory method creates snapshot")
    void fromDocumentCreatesSnapshot() {
        RagDocument doc = new RagDocument();
        doc.setId(1L);
        doc.setTitle("测试文档");
        doc.setContent("这是文档内容");
        doc.setContentHash("sha256hash");
        doc.setSize(200L);
        doc.setMetadata(Map.of("author", "test"));

        RagDocumentVersion version = RagDocumentVersion.fromDocument(doc, "CREATE", "初始创建");

        assertEquals(1L, version.getDocumentId());
        assertEquals("sha256hash", version.getContentHash());
        assertEquals("这是文档内容", version.getContentSnapshot());
        assertEquals(200L, version.getSize());
        assertEquals("CREATE", version.getChangeType());
        assertEquals("初始创建", version.getChangeDescription());
        assertEquals(Map.of("author", "test"), version.getMetadataSnapshot());
    }

    @Test
    @DisplayName("fromDocument handles null metadata")
    void fromDocumentWithNullMetadata() {
        RagDocument doc = new RagDocument();
        doc.setId(2L);
        doc.setContent("内容");
        doc.setContentHash("hash123");

        RagDocumentVersion version = RagDocumentVersion.fromDocument(doc, "UPDATE", null);

        assertEquals(2L, version.getDocumentId());
        assertNull(version.getSize());
        assertNull(version.getMetadataSnapshot());
        assertNull(version.getChangeDescription());
    }

    @Test
    @DisplayName("setter/getter full coverage")
    void settersAndGetters() {
        RagDocumentVersion version = new RagDocumentVersion();

        version.setId(100L);
        version.setDocumentId(1L);
        version.setVersionNumber(3);
        version.setContentHash("hash");
        version.setContentSnapshot("snapshot");
        version.setSize(500L);
        version.setChangeType("EMBED");
        version.setChangeDescription("嵌入完成");
        version.setMetadataSnapshot(Map.of("key", "val"));

        assertEquals(100L, version.getId());
        assertEquals(1L, version.getDocumentId());
        assertEquals(3, version.getVersionNumber());
        assertEquals("hash", version.getContentHash());
        assertEquals("snapshot", version.getContentSnapshot());
        assertEquals(500L, version.getSize());
        assertEquals("EMBED", version.getChangeType());
        assertEquals("嵌入完成", version.getChangeDescription());
        assertEquals(Map.of("key", "val"), version.getMetadataSnapshot());
    }
}
