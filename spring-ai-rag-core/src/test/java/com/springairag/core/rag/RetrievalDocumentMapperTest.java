package com.springairag.core.rag;

import com.springairag.api.dto.RetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖检索结果与 Spring AI Document/ChatSource 之间的双向映射：
 * metadata 白名单、默认值回退、来源类型推导与文本截断。
 */
class RetrievalDocumentMapperTest {

    private final RetrievalDocumentMapper mapper = new RetrievalDocumentMapper();

    @Test
    void toDocumentBuildsCompositeIdAndInjectsRequiredMetadata() {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId("doc-1");
        result.setChunkIndex(2);
        result.setTitle("Manual");
        result.setChunkText("body text");
        result.setScore(0.75);
        result.setVectorScore(0.8);
        result.setFulltextScore(0.7);

        var document = mapper.toDocument(result);

        assertEquals("doc-1:2", document.getId());
        assertEquals("body text", document.getText());
        assertEquals(0.75, document.getScore());
        assertEquals("doc-1", document.getMetadata().get("documentId"));
        assertEquals(2, document.getMetadata().get("chunkIndex"));
        assertEquals("Manual", document.getMetadata().get("title"));
        assertEquals("TEXT", document.getMetadata().get("documentType"));
    }

    @Test
    void toDocumentFiltersMetadataToTheAllowListAndDefaultsEmptyText() {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId("doc-1");
        result.setChunkIndex(0);
        result.setTitle("T");
        result.setScore(0.5);
        result.setVectorScore(0.4);
        result.setFulltextScore(0.3);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("collectionKey", "kb");
        metadata.put("secretColumn", "leak");
        metadata.put("internalNote", "drop");
        result.setMetadata(metadata);

        var document = mapper.toDocument(result);

        assertEquals("kb", document.getMetadata().get("collectionKey"));
        assertTrue(!document.getMetadata().containsKey("secretColumn"));
        assertTrue(!document.getMetadata().containsKey("internalNote"));
        assertEquals("", document.getText());
    }

    @Test
    void toDocumentKeepsConditionalFieldsWhenPresent() {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId("doc-1");
        result.setChunkIndex(0);
        result.setTitle("T");
        result.setScore(0.5);
        result.setVectorScore(0.4);
        result.setFulltextScore(0.3);
        result.setOriginalFilename("manual.pdf");
        result.setSource("pdf-import:u1");
        result.setMetadata(Map.of("documentType", "PDF"));

        var document = mapper.toDocument(result);

        assertEquals("manual.pdf", document.getMetadata().get("originalFilename"));
        assertEquals("pdf-import:u1", document.getMetadata().get("source"));
        assertEquals("PDF", document.getMetadata().get("documentType"));
    }

    @Test
    void toChatSourceDerivesSourceTypeAndTruncatesChunkText() {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId("doc-1");
        result.setChunkIndex(1);
        result.setChunkText("x".repeat(2500));

        var source = mapper.toChatSource(result, 3);

        assertEquals("S3", source.getCitationId());
        assertEquals("doc-1", source.getDocumentId());
        assertEquals(2000, source.getChunkText().length());
        assertEquals("DOCUMENT", source.getSourceType());
        assertNull(source.getMetadata());
    }

    @Test
    void toChatSourceFallsBackTitleAndPrefersMetadataSourceType() {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId("doc-2");
        result.setChunkIndex(0);
        result.setMetadata(Map.of("sourceType", "MARKDOWN", "collectionKey", "kb"));
        result.setSource("pdf-import:u2");

        var source = mapper.toChatSource(result, "C-9");

        assertEquals("C-9", source.getCitationId());
        assertEquals("doc-2", source.getTitle());
        assertEquals("MARKDOWN", source.getSourceType());
        assertEquals("kb", source.getCollectionKey());
        assertNull(source.getOriginalFilename());
    }

    @Test
    void toChatSourceDetectsPdfImportsFromSourcePrefix() {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId("doc-3");
        result.setChunkIndex(0);
        result.setSource("pdf-import:abc");

        assertEquals("PDF", mapper.toChatSource(result, 1).getSourceType());
    }

    @Test
    void toRetrievalResultRoundTripsThroughDocumentMetadata() {
        var document = org.springframework.ai.document.Document.builder()
                .id("doc-9:4")
                .text("round trip")
                .metadata(Map.of(
                        "title", "Titled",
                        "score", 0.5,
                        "chunkIndex", 4))
                .build();

        RetrievalResult result = mapper.toRetrievalResult(document);

        // metadata 缺失 documentId 时回退到完整 Document id（含 chunk 后缀）。
        assertEquals("doc-9:4", result.getDocumentId());
        assertEquals(4, result.getChunkIndex());
        assertEquals("Titled", result.getTitle());
        assertEquals(0.5, result.getScore());
        assertEquals(0.0, result.getVectorScore());
        assertEquals("round trip", result.getChunkText());
    }

    @Test
    void toChatSourceFromDocumentFallsBackIdForMissingFields() {
        var document = org.springframework.ai.document.Document.builder()
                .id("doc-7:0")
                .text("plain")
                .metadata(Map.of())
                .build();

        var source = mapper.toChatSource(document, 2);

        assertEquals("S2", source.getCitationId());
        assertEquals("doc-7:0", source.getDocumentId());
        assertEquals("doc-7:0", source.getTitle());
        assertEquals(0.0, source.getScore());
    }
}
