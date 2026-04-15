package com.springairag.core.service;

import com.springairag.core.entity.FsFile;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.FsFileRepository;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PdfToRagService unit tests.
 */
@ExtendWith(MockitoExtension.class)
class PdfToRagServiceTest {

    @Mock
    private FsFileRepository fsFileRepository;

    @Mock
    private RagDocumentRepository documentRepository;

    @Mock
    private DocumentEmbedService documentEmbedService;

    private PdfToRagService service;

    @BeforeEach
    void setUp() {
        service = new PdfToRagService(fsFileRepository, documentRepository, documentEmbedService);
    }

    // ==================== importPdfToRag tests ====================

    @Test
    void importPdfToRag_newDocument_createsRagDocument() {
        String entryPath = "uuid-123/default.md";
        String filename = "test-paper.pdf";
        String markdown = "# Test\n\nThis is the content.";
        Long collectionId = 5L;

        FsFile fsFile = new FsFile(entryPath, true, null, markdown, "text/markdown", 100L);
        when(fsFileRepository.findById(entryPath)).thenReturn(Optional.of(fsFile));
        when(documentRepository.findByContentHash(anyString())).thenReturn(List.of());
        when(documentRepository.save(any(RagDocument.class))).thenAnswer(invocation -> {
            RagDocument doc = invocation.getArgument(0);
            doc.setId(42L);
            return doc;
        });
        // embed=false
        PdfToRagService.PdfToRagResult result = service.importPdfToRag(
                entryPath, filename, collectionId, false, false);

        assertEquals(42L, result.documentId());
        assertEquals("test-paper", result.title());
        assertTrue(result.newlyCreated());
        assertNull(result.embedStatus());

        ArgumentCaptor<RagDocument> docCaptor = ArgumentCaptor.forClass(RagDocument.class);
        verify(documentRepository).save(docCaptor.capture());
        RagDocument saved = docCaptor.getValue();
        assertEquals("test-paper", saved.getTitle());
        assertEquals(markdown, saved.getContent());
        assertEquals("pdf-import:" + entryPath, saved.getSource());
        assertEquals("markdown", saved.getDocumentType());
        assertEquals(filename, saved.getOriginalFilename());
        assertEquals(collectionId, saved.getCollectionId());
        assertNotNull(saved.getMetadata());
        assertEquals("pdf", saved.getMetadata().get("importedFrom"));
        assertEquals("uuid-123", saved.getMetadata().get("uuid"));
    }

    @Test
    void importPdfToRag_duplicateContent_returnsExistingDocument() {
        String entryPath = "uuid-456/default.md";
        String filename = "same-content.pdf";
        String markdown = "# Same Content";

        FsFile fsFile = new FsFile(entryPath, true, null, markdown, "text/markdown", 50L);
        RagDocument existing = new RagDocument();
        existing.setId(99L);
        existing.setTitle("Existing Doc");
        existing.setContent(markdown);

        when(fsFileRepository.findById(entryPath)).thenReturn(Optional.of(fsFile));
        when(documentRepository.findByContentHash(anyString())).thenReturn(List.of(existing));

        PdfToRagService.PdfToRagResult result = service.importPdfToRag(
                entryPath, filename, null, false, false);

        assertEquals(99L, result.documentId());
        assertFalse(result.newlyCreated());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void importPdfToRag_markdownNotFound_throwsException() {
        when(fsFileRepository.findById("nonexistent/default.md")).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.importPdfToRag("nonexistent/default.md", "test.pdf", null, false, false));

        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void importPdfToRag_emptyContent_throwsException() {
        FsFile emptyFile = new FsFile("uuid/default.md", true, null, null, "text/markdown", 0L);
        when(fsFileRepository.findById("uuid/default.md")).thenReturn(Optional.of(emptyFile));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.importPdfToRag("uuid/default.md", "test.pdf", null, false, false));

        assertTrue(ex.getMessage().contains("no text content"));
    }

    @Test
    void importPdfToRag_withEmbedding_triggersEmbed() {
        String entryPath = "embed-uuid/default.md";
        String filename = "embed-me.pdf";
        String markdown = "# Embed\n\nContent here.";

        FsFile fsFile = new FsFile(entryPath, true, null, markdown, "text/markdown", 80L);
        when(fsFileRepository.findById(entryPath)).thenReturn(Optional.of(fsFile));
        when(documentRepository.findByContentHash(anyString())).thenReturn(List.of());
        when(documentRepository.save(any(RagDocument.class))).thenAnswer(invocation -> {
            RagDocument doc = invocation.getArgument(0);
            doc.setId(7L);
            return doc;
        });
        when(documentEmbedService.embedDocument(eq(7L), eq(false)))
                .thenReturn(Map.of("status", "COMPLETED", "chunksCreated", 3, "message", "OK"));

        PdfToRagService.PdfToRagResult result = service.importPdfToRag(
                entryPath, filename, null, true, false);

        assertEquals(7L, result.documentId());
        assertTrue(result.newlyCreated());
        assertEquals("COMPLETED", result.embedStatus());
        assertEquals(3, result.chunksCreated());

        verify(documentEmbedService).embedDocument(7L, false);
    }

    @Test
    void importPdfToRag_withEmbedding_failure_returnsFailedStatus() {
        String entryPath = "fail-uuid/default.md";
        FsFile fsFile = new FsFile(entryPath, true, null, "Content", "text/markdown", 50L);
        when(fsFileRepository.findById(entryPath)).thenReturn(Optional.of(fsFile));
        when(documentRepository.findByContentHash(anyString())).thenReturn(List.of());
        when(documentRepository.save(any(RagDocument.class))).thenAnswer(invocation -> {
            RagDocument doc = invocation.getArgument(0);
            doc.setId(1L);
            return doc;
        });
        when(documentEmbedService.embedDocument(eq(1L), anyBoolean()))
                .thenThrow(new RuntimeException("Embedding service unavailable"));

        PdfToRagService.PdfToRagResult result = service.importPdfToRag(
                entryPath, "f.pdf", null, true, false);

        assertEquals("FAILED", result.embedStatus());
        assertNull(result.chunksCreated());
    }

    @Test
    void importPdfToRag_titleDerivation_handlesVariousFilenames() {
        // Test: filename without .pdf
        FsFile fsFile = new FsFile("u/default.md", true, null, "x", "text/markdown", 1L);
        when(fsFileRepository.findById(anyString())).thenReturn(Optional.of(fsFile));
        when(documentRepository.findByContentHash(anyString())).thenReturn(List.of());
        when(documentRepository.save(any(RagDocument.class))).thenAnswer(inv -> {
            RagDocument d = inv.getArgument(0);
            d.setId(1L);
            return d;
        });

        PdfToRagService.PdfToRagResult r = service.importPdfToRag("u/default.md", "noextension", null, false, false);
        assertEquals("noextension", r.title());

        r = service.importPdfToRag("u/default.md", "very-long-name.pdf", null, false, false);
        assertEquals("very-long-name", r.title());
        assertTrue(r.title().length() <= 200);
    }

    @Test
    void importPdfToRagWithEmbedding_sseProgressForwardsEvents() {
        String entryPath = "sse-uuid/default.md";
        FsFile fsFile = new FsFile(entryPath, true, null, "Content here", "text/markdown", 50L);
        when(fsFileRepository.findById(entryPath)).thenReturn(Optional.of(fsFile));
        when(documentRepository.findByContentHash(anyString())).thenReturn(List.of());
        when(documentRepository.save(any(RagDocument.class))).thenAnswer(inv -> {
            RagDocument d = inv.getArgument(0);
            d.setId(10L);
            return d;
        });

        @SuppressWarnings("unchecked")
        Consumer<com.springairag.api.dto.EmbedProgressEvent> cb = mock(Consumer.class);
        when(documentEmbedService.embedDocumentWithProgress(eq(10L), eq(false), any()))
                .thenAnswer(inv -> {
                    // Simulate SSE progress events
                    var callback = inv.getArgument(2, Consumer.class);
                    callback.accept(new com.springairag.api.dto.EmbedProgressEvent("PREPARING", 0, 0, "prep", 10L));
                    callback.accept(new com.springairag.api.dto.EmbedProgressEvent("COMPLETED", 5, 5, "done", 10L));
                    return Map.of("status", "COMPLETED", "chunksCreated", 5, "message", "Done");
                });

        PdfToRagService.PdfToRagResult result = service.importPdfToRagWithEmbedding(
                entryPath, "sse-test.pdf", null, false, cb);

        assertEquals(10L, result.documentId());
        assertEquals("COMPLETED", result.embedStatus());
        assertEquals(5, result.chunksCreated());

        verify(cb, times(2)).accept(any()); // PREPARING + COMPLETED
    }
}
