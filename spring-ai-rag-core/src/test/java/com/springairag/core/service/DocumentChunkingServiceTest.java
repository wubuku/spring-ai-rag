package com.springairag.core.service;

import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.documents.chunk.TextChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DocumentChunkingService（Batch 401）：派生分块唯一协调入口的
 * 输入守卫、JSON_RECORD 单块直通、TEXT 层级切分与描述符一致性、
 * PreparedChunks 的空值/不可变契约。
 *
 * <p>Batch 807 补 {@code chunkerVersionFor}：只回答"这个文档会派生出版本号是什么"，
 * 不派生任何分块。它与 {@code prepare} 共用同一个 descriptor provider，所以两条路径
 * 必然给出同一个答案；测试同时钉住"答案相同"和"输入守卫不同"（空白内容下 prepare
 * 抛异常而它不抛），后者是前者没有偷偷走 prepare 的可观测证据。
 */
class DocumentChunkingServiceTest {

    private RagProperties properties;
    private DocumentChunkingService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        service = new DocumentChunkingService(
                properties,
                new DocumentDerivationDescriptorProvider(properties));
    }

    private RagDocument document(String documentType, String content) {
        RagDocument document = new RagDocument();
        document.setId(41L);
        document.setDocumentType(documentType);
        document.setContent(content);
        return document;
    }

    @Test
    void prepareRejectsNullDocument() {
        NullPointerException error = assertThrows(
                NullPointerException.class, () -> service.prepare(null));
        assertEquals("document", error.getMessage());
    }

    @Test
    void prepareRejectsMissingOrBlankContent() {
        RagDocument noContent = document(RagDocument.JSON_RECORD, null);
        IllegalArgumentException nullContent = assertThrows(
                IllegalArgumentException.class, () -> service.prepare(noContent));
        assertEquals("Document content is empty: documentId=41",
                nullContent.getMessage());

        RagDocument blankContent =
                document(RagDocument.JSON_RECORD, "   \n\t ");
        IllegalArgumentException blank = assertThrows(
                IllegalArgumentException.class,
                () -> service.prepare(blankContent));
        assertEquals("Document content is empty: documentId=41",
                blank.getMessage());
    }

    @Test
    void prepareUsesSingleFullSpanChunkForJsonRecord() {
        // 含 Markdown 标题也整体单块直通，不做层级切分。
        String content = "# Header\n\nparagraph one\n\n## Sub\n\nparagraph two";
        RagDocument record = document(RagDocument.JSON_RECORD, content);

        DocumentChunkingService.PreparedChunks prepared =
                service.prepare(record);

        assertEquals(1, prepared.chunks().size());
        TextChunk chunk = prepared.chunks().getFirst();
        assertEquals(content, chunk.text());
        assertEquals(0, chunk.startPos());
        assertEquals(content.length(), chunk.endPos());
        assertEquals("JSON_RECORD",
                prepared.descriptor().documentKind());
        assertEquals("json-record-v1:single",
                prepared.descriptor().chunkerVersion());
    }

    @Test
    void prepareSplitsTextDocumentsHierarchically() {
        properties.getChunk().setDefaultChunkSize(40);
        properties.getChunk().setMinChunkSize(10);
        properties.getChunk().setDefaultChunkOverlap(5);
        // 重建 service 使其用缩小后的 chunk 参数实例化 chunker。
        service = new DocumentChunkingService(
                properties,
                new DocumentDerivationDescriptorProvider(properties));
        String content = "# Alpha\n\n" + "word ".repeat(30)
                + "\n\n## Beta\n\n" + "term ".repeat(30);
        RagDocument text = document("TEXT", content);

        DocumentChunkingService.PreparedChunks prepared =
                service.prepare(text);

        assertTrue(prepared.chunks().size() > 1,
                "long markdown text should split into multiple chunks");
        // 所有 chunk 拼接覆盖原文文本（允许重叠，不允许丢失正文）。
        String joined = String.join(" ", prepared.chunks().stream()
                .map(TextChunk::text).toList());
        assertTrue(joined.contains("Alpha") && joined.contains("Beta"));
        assertEquals("TEXT", prepared.descriptor().documentKind());
        assertTrue(prepared.descriptor().chunkerVersion()
                .startsWith("hierarchical-v2:40:10:5"));
    }

    @Test
    void preparedChunksEnforcesNullAndImmutabilityContract() {
        DocumentDerivationDescriptorProvider.Descriptor descriptor =
                new DocumentDerivationDescriptorProvider(properties)
                        .textDescriptor();

        assertThrows(NullPointerException.class,
                () -> new DocumentChunkingService.PreparedChunks(
                        null, List.of(new TextChunk("t", 0, 1))));
        assertThrows(NullPointerException.class,
                () -> new DocumentChunkingService.PreparedChunks(
                        descriptor, null));

        List<TextChunk> mutable = new java.util.ArrayList<>();
        mutable.add(new TextChunk("t", 0, 1));
        DocumentChunkingService.PreparedChunks prepared =
                new DocumentChunkingService.PreparedChunks(
                        descriptor, mutable);
        // 构造时拷贝：修改入参列表不影响已构造记录。
        mutable.clear();
        assertEquals(1, prepared.chunks().size());
        assertThrows(UnsupportedOperationException.class,
                () -> prepared.chunks().add(new TextChunk("x", 0, 1)));
        assertNotNull(prepared.descriptor());
    }

    // ── chunkerVersionFor：只取身份，不派生分块（Batch 807）────────────────────

    @Test
    void chunkerVersionForAgreesWithPrepareForEveryDocumentKind() {
        String longText = "# Alpha\n\n" + "word ".repeat(30)
                + "\n\n## Beta\n\n" + "term ".repeat(30);

        for (String type : new String[]{RagDocument.JSON_RECORD, "TEXT", "markdown", null}) {
            RagDocument doc = document(type, longText);
            assertEquals(service.prepare(doc).descriptor().chunkerVersion(),
                    service.chunkerVersionFor(doc),
                    "descriptor-only lookup must not answer differently for type " + type);
        }
    }

    @Test
    void chunkerVersionForNeedsNoContentAndPrepareOnTheSameInputFails() {
        // The reason this method exists (Batch 807): cache-freshness checks were
        // calling prepare() for a version string, which split the whole document
        // and threw on a blank one. prepare() still rejecting the same input is
        // what proves the lookup is not routing through it.
        RagDocument blank = document(RagDocument.JSON_RECORD, "   \n\t ");

        assertThrows(IllegalArgumentException.class, () -> service.prepare(blank));

        assertEquals("json-record-v1:single", service.chunkerVersionFor(blank));
    }

    @Test
    void chunkerVersionForTreatsAnUnsetTypeAsText() {
        RagDocument noType = document(null, "body");

        assertEquals("TEXT", new DocumentDerivationDescriptorProvider(properties)
                .describe(noType).documentKind());
        assertEquals(service.prepare(noType).descriptor().chunkerVersion(),
                service.chunkerVersionFor(noType));
    }

    @Test
    void chunkerVersionForReadsLiveConfigurationRatherThanACachedConstant() {
        RagDocument doc = document("TEXT", "body");
        assertTrue(service.chunkerVersionFor(doc).startsWith("hierarchical-v2:1000:100:100"));

        properties.getChunk().setDefaultChunkSize(40);
        properties.getChunk().setMinChunkSize(10);
        properties.getChunk().setDefaultChunkOverlap(5);

        assertEquals("hierarchical-v2:40:10:5", service.chunkerVersionFor(doc));
    }

    @Test
    void chunkerVersionForRejectsNullDocument() {
        NullPointerException error = assertThrows(
                NullPointerException.class, () -> service.chunkerVersionFor(null));
        assertEquals("document", error.getMessage());
    }
}
