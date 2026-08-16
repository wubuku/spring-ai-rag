package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RetrievalResultProvenanceTest {

    @Test
    void applyDocumentFields_validPdfSourceDerivesArtifactPaths() {
        RetrievalResult result = new RetrievalResult();
        Map<String, Object> row = new HashMap<>();
        row.put("document_title", "Reference");
        row.put("document_source", "pdf-import:uuid-1/default.md");
        row.put("original_filename", "reference.pdf");

        RetrievalResultProvenance.applyDocumentFields(result, row);

        assertEquals("Reference", result.getTitle());
        assertEquals("pdf-import:uuid-1/default.md", result.getSource());
        assertEquals("reference.pdf", result.getOriginalFilename());
        assertEquals("uuid-1/", result.getFileDirectoryPath());
        assertEquals("uuid-1/default.md", result.getIndexedFilePath());
        assertEquals("uuid-1/original.pdf", result.getOriginalFilePath());
    }

    @Test
    void applyDocumentFields_markdownPlaceholderIsNotExposedAsOriginalFilename() {
        RetrievalResult result = new RetrievalResult();
        RetrievalResultProvenance.applyDocumentFields(result, Map.of(
                "document_source", "pdf-import:uuid-1/default.md",
                "original_filename", "uuid-1/default.md"));

        assertNull(result.getOriginalFilename());
        assertEquals("uuid-1/original.pdf", result.getOriginalFilePath());
    }

    @Test
    void applyDocumentFields_ordinarySourceDoesNotDeriveFilePaths() {
        RetrievalResult result = new RetrievalResult();
        RetrievalResultProvenance.applyDocumentFields(result, Map.of(
                "document_source", "https://example.test/manual",
                "original_filename", "manual.md"));

        assertEquals("https://example.test/manual", result.getSource());
        assertEquals("manual.md", result.getOriginalFilename());
        assertNull(result.getFileDirectoryPath());
        assertNull(result.getIndexedFilePath());
        assertNull(result.getOriginalFilePath());
    }

    @Test
    void applyDocumentFields_unsafePdfSourcesFailClosed() {
        for (String source : new String[] {
                "pdf-import:/absolute/default.md",
                "pdf-import:uuid/../default.md",
                "pdf-import:uuid/./default.md",
                "pdf-import:uuid//default.md",
                "pdf-import:uuid\\default.md",
                "pdf-import:uuid/\ndefault.md",
                "pdf-import:uuid/not-default.md",
                "pdf-import:default.md"
        }) {
            RetrievalResult result = new RetrievalResult();
            RetrievalResultProvenance.applyDocumentFields(
                    result, Map.of("document_source", source));

            assertEquals(source, result.getSource());
            assertNull(result.getFileDirectoryPath(), source);
            assertNull(result.getIndexedFilePath(), source);
            assertNull(result.getOriginalFilePath(), source);
        }
    }

    @Test
    void applyDocumentFields_documentTitleOverridesEmbeddingMetadata() {
        RetrievalResult result = new RetrievalResult();
        result.setMetadata(Map.of("title", "Stale embedding title"));

        RetrievalResultProvenance.applyDocumentFields(result, Map.of(
                "document_title", "Current document title"));

        assertEquals("Current document title", result.getTitle());
    }
}
