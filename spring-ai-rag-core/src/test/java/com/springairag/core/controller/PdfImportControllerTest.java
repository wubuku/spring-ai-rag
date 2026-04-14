package com.springairag.core.controller;

import com.springairag.api.dto.FileTreeResponse;
import com.springairag.core.entity.FsFile;
import com.springairag.core.service.MarkdownRendererService;
import com.springairag.core.service.PdfImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PdfImportController unit tests (pure mock approach, no Spring context).
 */
class PdfImportControllerTest {

    private PdfImportService pdfImportService;
    private MarkdownRendererService markdownRendererService;
    private PdfImportController controller;

    @BeforeEach
    void setUp() {
        pdfImportService = mock(PdfImportService.class);
        markdownRendererService = mock(MarkdownRendererService.class);
        controller = new PdfImportController(pdfImportService, markdownRendererService);
    }

    // ==================== importPdf tests ====================

    @Test
    void importPdf_validPdf_returnsOk() throws IOException {
        MockMultipartFile pdfFile = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "PDF content".getBytes());

        PdfImportService.PdfImportResult result =
                new PdfImportService.PdfImportResult("test-uuid", "test-uuid/default.md", 2);
        when(pdfImportService.importPdf(any(), eq("papers"))).thenReturn(result);

        ResponseEntity<Object> response = controller.importPdf(pdfFile, "papers");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().toString().contains("uuid=test-uuid"));
    }

    @Test
    void importPdf_noFile_returnsBadRequest() {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "", "application/pdf", new byte[0]);

        ResponseEntity<Object> response = controller.importPdf(emptyFile, null);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void importPdf_nonPdfFile_returnsBadRequest() {
        MockMultipartFile docFile = new MockMultipartFile(
                "file", "test.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "DOCX content".getBytes());

        ResponseEntity<Object> response = controller.importPdf(docFile, null);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void importPdf_serviceThrowsIllegalArgument_returnsBadRequest() throws IOException {
        MockMultipartFile pdfFile = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "PDF content".getBytes());

        when(pdfImportService.importPdf(any(), any()))
                .thenThrow(new IllegalArgumentException("PDF import is disabled"));

        ResponseEntity<Object> response = controller.importPdf(pdfFile, null);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void importPdf_serviceThrowsRuntimeException_returnsInternalServerError() throws IOException {
        MockMultipartFile pdfFile = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "PDF content".getBytes());

        when(pdfImportService.importPdf(any(), any()))
                .thenThrow(new RuntimeException("marker_single not found"));

        ResponseEntity<Object> response = controller.importPdf(pdfFile, null);

        assertEquals(500, response.getStatusCode().value());
    }

    // ==================== previewHtml tests ====================

    @Test
    void previewHtmlPage_existingMarkdown_returnsHtml() {
        FsFile markdownFile = new FsFile();
        markdownFile.setPath("/papers/test.md");
        markdownFile.setContentTxt("# Test\n\nHello world");

        when(pdfImportService.getFile(anyString())).thenReturn(Optional.of(markdownFile));
        when(markdownRendererService.renderToHtml(any(FsFile.class)))
                .thenReturn("<h1>Test</h1><p>Hello world</p>");

        ResponseEntity<String> response = controller.previewHtmlPage("/papers/test.pdf");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("<h1>Test</h1>"));
    }

    @Test
    void previewHtmlPage_fileNotFound_returnsNotFound() {
        when(pdfImportService.getFile(anyString())).thenReturn(Optional.empty());

        ResponseEntity<String> response = controller.previewHtmlPage("/papers/test.pdf");

        assertEquals(404, response.getStatusCode().value());
    }

    // ==================== getRawFile tests ====================

    @Test
    void getRawFile_existingFile_returnsFile() {
        byte[] content = "Hello, world!".getBytes();
        Resource resource = new ByteArrayResource(content);

        FsFile fsFile = new FsFile();
        fsFile.setPath("/papers/test.txt");
        fsFile.setMimeType("text/plain");

        when(pdfImportService.getFile(anyString())).thenReturn(Optional.of(fsFile));
        when(pdfImportService.loadFileAsResource(anyString())).thenReturn(Optional.of(resource));

        ResponseEntity<Resource> response = controller.getRawFile("/papers/test.txt");

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void getRawFile_fileNotFound_returnsNotFound() {
        when(pdfImportService.getFile(anyString())).thenReturn(Optional.empty());

        ResponseEntity<Resource> response = controller.getRawFile("/papers/nonexistent.txt");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void getRawFile_resourceLoadFails_returnsInternalServerError() {
        FsFile fsFile = new FsFile();
        fsFile.setPath("/papers/test.txt");
        fsFile.setMimeType("text/plain");

        when(pdfImportService.getFile(anyString())).thenReturn(Optional.of(fsFile));
        when(pdfImportService.loadFileAsResource(anyString())).thenReturn(Optional.empty());

        ResponseEntity<Resource> response = controller.getRawFile("/papers/test.txt");

        assertEquals(500, response.getStatusCode().value());
    }

    // ==================== listTree tests ====================

    @Test
    void listTree_withFiles_returnsTree() {
        FsFile subdirFile = new FsFile();
        subdirFile.setPath("/papers/subdir/file.txt");
        subdirFile.setIsText(true);

        FsFile mdFile = new FsFile();
        mdFile.setPath("/papers/test.md");
        mdFile.setMimeType("text/markdown");
        mdFile.setFileSize(1234L);
        mdFile.setIsText(true);

        when(pdfImportService.listChildren(anyString())).thenReturn(List.of(subdirFile, mdFile));

        ResponseEntity<FileTreeResponse> response = controller.listTree("/papers");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().total());
    }

    @Test
    void listTree_emptyDirectory_returnsEmptyList() {
        when(pdfImportService.listChildren(anyString())).thenReturn(List.of());

        ResponseEntity<FileTreeResponse> response = controller.listTree("/papers");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().total());
    }

    @Test
    void listTree_nullPath_defaultsToRoot() {
        when(pdfImportService.listChildren("/")).thenReturn(List.of());

        ResponseEntity<FileTreeResponse> response = controller.listTree(null);

        assertEquals(200, response.getStatusCode().value());
        verify(pdfImportService).listChildren("/");
    }
}
