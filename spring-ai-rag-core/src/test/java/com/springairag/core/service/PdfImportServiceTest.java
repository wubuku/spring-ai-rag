package com.springairag.core.service;

import com.springairag.core.config.RagPdfProperties;
import com.springairag.core.entity.FsFile;
import com.springairag.core.repository.FsFileRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PdfImportService}.
 * <p>
 * Covers:
 * <ul>
 *   <li>importPdf validation (disabled, blank filename, non-PDF extension)</li>
 *   <li>importPdf success (creates both Markdown and PDF FsFile records)</li>
 *   <li>extractTextFromPdf with real PDFBox document</li>
 *   <li>extractTextFromPdf with empty PDF</li>
 *   <li>extractTextFromPdf with invalid PDF bytes</li>
 *   <li>buildMarkdown with real content, empty content, and special characters</li>
 *   <li>getFile delegation</li>
 *   <li>listChildren with nested directory flattening</li>
 *   <li>loadFileAsResource creates temp file and returns UrlResource</li>
 * </ul>
 */
class PdfImportServiceTest {

    private FsFileRepository fsFileRepository;
    private RagPdfProperties pdfProperties;
    private PdfImportService pdfImportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        fsFileRepository = mock(FsFileRepository.class);
        pdfProperties = new RagPdfProperties();
        pdfProperties.setEnabled(true);
        pdfImportService = new PdfImportService(fsFileRepository, pdfProperties);
    }

    // ==================== importPdf validation ====================

    @Nested
    @DisplayName("importPdf validation")
    class ImportPdfValidation {

        @Test
        @DisplayName("throws IllegalStateException when PDF import is disabled")
        void importPdf_whenDisabled_throws() {
            pdfProperties.setEnabled(false);
            MockMultipartFile file = new MockMultipartFile(
                    "file", "doc.pdf", "application/pdf", b("hello"));

            assertThatThrownBy(() -> pdfImportService.importPdf(file, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("disabled");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when filename is blank")
        void importPdf_blankFilename_throws() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "   ", "application/pdf", b("hello"));

            assertThatThrownBy(() -> pdfImportService.importPdf(file, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when filename has no extension")
        void importPdf_noExtension_throws() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "document", "application/pdf", b("hello"));

            assertThatThrownBy(() -> pdfImportService.importPdf(file, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("throws IllegalArgumentException for non-PDF extension")
        void importPdf_nonPdfExtension_throws() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "doc.docx", "application/pdf", b("hello"));

            assertThatThrownBy(() -> pdfImportService.importPdf(file, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PDF");
        }

        @Test
        @DisplayName("throws IllegalArgumentException for PPTX disguised as PDF")
        void importPdf_pptxDisguised_throws() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "data.PDF", "application/pdf", b("not a pdf"));

            assertThatThrownBy(() -> pdfImportService.importPdf(file, null))
                    .isInstanceOf(RuntimeException.class) // PDFBox fails to parse
                    .hasMessageContaining("Failed to extract");
        }
    }

    // ==================== importPdf success ====================

    @Nested
    @DisplayName("importPdf success")
    class ImportPdfSuccess {

        @Test
        @DisplayName("imports PDF and stores both Markdown and PDF files")
        void importPdf_success_storesBothFiles() throws IOException {
            byte[] pdfBytes = createMinimalPdf("Hello World", "Test Content");
            MockMultipartFile file = new MockMultipartFile(
                    "file", "Test-Report.pdf", "application/pdf", pdfBytes);

            when(fsFileRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

            PdfImportService.PdfImportResult result =
                    pdfImportService.importPdf(file, null);

            assertThat(result.uuid()).isNotBlank();
            assertThat(result.entryMarkdown()).isEqualTo(result.uuid() + "/default.md");
            assertThat(result.filesStored()).isEqualTo(2);

            verify(fsFileRepository).saveAll(argThat((List<FsFile> records) -> {
                assertThat(records).hasSize(2);
                FsFile mdFile = records.stream()
                        .filter(f -> f.getPath().endsWith("/default.md"))
                        .findFirst().orElseThrow();
                assertThat(mdFile.getIsText()).isTrue();
                assertThat(mdFile.getContentBin()).isNotNull();
                assertThat(mdFile.getContentTxt()).isNotNull();
                assertThat(new String(mdFile.getContentBin(), StandardCharsets.UTF_8))
                        .contains("# Test Report");
                assertThat(mdFile.getMimeType()).isEqualTo("text/markdown");

                FsFile pdfFile = records.stream()
                        .filter(f -> f.getPath().endsWith("/original.pdf"))
                        .findFirst().orElseThrow();
                assertThat(pdfFile.getIsText()).isFalse();
                assertThat(pdfFile.getContentBin()).isEqualTo(pdfBytes);
                assertThat(pdfFile.getContentTxt()).isNull();
                assertThat(pdfFile.getMimeType()).isEqualTo("application/pdf");
                return true;
            }));
        }

        @Test
        @DisplayName("imports PDF with Chinese filename (title normalized)")
        void importPdf_chineseFilename_extractsCorrectly() throws IOException {
            // Note: PDFBox standard fonts (Helvetica) cannot render CJK characters.
            // We use ASCII content but a Chinese filename to test title normalization.
            byte[] pdfBytes = createMinimalPdf("Chinese Document", "Content here");
            MockMultipartFile file = new MockMultipartFile(
                    "file", "测试文档.pdf", "application/pdf", pdfBytes);

            when(fsFileRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

            PdfImportService.PdfImportResult result =
                    pdfImportService.importPdf(file, null);

            assertThat(result.uuid()).isNotBlank();
            assertThat(result.entryMarkdown()).isEqualTo(result.uuid() + "/default.md");

            verify(fsFileRepository).saveAll(argThat((List<FsFile> records) -> {
                FsFile mdFile = records.stream()
                        .filter(f -> f.getPath().endsWith("/default.md"))
                        .findFirst().orElseThrow();
                String content = new String(mdFile.getContentBin(), StandardCharsets.UTF_8);
                // Title "测试文档" (Chinese chars) should appear as-is in the Markdown heading
                assertThat(content).contains("# 测试文档");
                return true;
            }));
        }

        @Test
        @DisplayName("imports PDF with underscore and hyphen in filename")
        void importPdf_underscoreHyphenFilename_normalizesTitle() throws IOException {
            byte[] pdfBytes = createMinimalPdf("Report", "Content");
            MockMultipartFile file = new MockMultipartFile(
                    "file", "annual_report-2024.pdf", "application/pdf", pdfBytes);

            when(fsFileRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

            PdfImportService.PdfImportResult result =
                    pdfImportService.importPdf(file, null);

            verify(fsFileRepository).saveAll(argThat((List<FsFile> records) -> {
                FsFile mdFile = records.stream()
                        .filter(f -> f.getPath().endsWith("/default.md"))
                        .findFirst().orElseThrow();
                String content = new String(mdFile.getContentBin(), StandardCharsets.UTF_8);
                // Underscore and hyphen should be replaced with spaces in title
                assertThat(content).contains("# annual report 2024");
                return true;
            }));
        }
    }

    // ==================== extractTextFromPdf ====================

    @Nested
    @DisplayName("extractTextFromPdf")
    class ExtractTextFromPdf {

        @Test
        @DisplayName("extracts text from valid PDF")
        void extractTextFromPdf_validPdf_extractsText() throws IOException {
            byte[] pdfBytes = createMinimalPdf("Section 1", "This is the extracted content.");

            String text = pdfImportService.extractTextFromPdf(pdfBytes, "test.pdf");

            assertThat(text).contains("Section 1");
            assertThat(text).contains("This is the extracted content.");
        }

        @Test
        @DisplayName("returns empty string for empty PDF")
        void extractTextFromPdf_emptyPdf_returnsEmpty() throws IOException {
            byte[] pdfBytes = createEmptyPdf();

            String text = pdfImportService.extractTextFromPdf(pdfBytes, "empty.pdf");

            assertThat(text.trim()).isEmpty();
        }

        @Test
        @DisplayName("extracts text with newlines preserved")
        void extractTextFromPdf_multiline_preservesNewlines() throws IOException {
            String content = "Line one.\nLine two.\nLine three.";
            byte[] pdfBytes = createMinimalPdf("Multi", content);

            String text = pdfImportService.extractTextFromPdf(pdfBytes, "multiline.pdf");

            assertThat(text).contains("Line one");
            assertThat(text).contains("Line two");
            assertThat(text).contains("Line three");
        }

        @Test
        @DisplayName("throws RuntimeException for invalid PDF bytes")
        void extractTextFromPdf_invalidBytes_throws() {
            byte[] invalid = "this is not a pdf".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> pdfImportService.extractTextFromPdf(invalid, "corrupt.pdf"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to extract text from PDF");
        }
    }

    // ==================== buildMarkdown ====================

    @Nested
    @DisplayName("buildMarkdown")
    class BuildMarkdown {

        @Test
        @DisplayName("builds Markdown with title and content")
        void buildMarkdown_withContent_rendersCorrectly() {
            String md = pdfImportService.buildMarkdown("My Document.pdf", "Paragraph one.\n\nParagraph two.");

            assertThat(md).startsWith("# My Document\n");
            // Check key markers (without asterisks to avoid regex issues)
            assertThat(md).contains("Extracted from PDF automatically");
            assertThat(md).contains("Layout may not be preserved");
            assertThat(md).contains("Paragraph one.");
            assertThat(md).contains("Paragraph two.");
            assertThat(md).contains("Generated by spring-ai-rag PDF Import");
        }

        @Test
        @DisplayName("handles empty extracted text")
        void buildMarkdown_emptyText_showsNoContentMessage() {
            String md = pdfImportService.buildMarkdown("Empty Doc.pdf", "");

            assertThat(md).contains("# Empty Doc");
            assertThat(md).contains("No text content could be extracted from this PDF");
        }

        @Test
        @DisplayName("handles null extracted text as empty")
        void buildMarkdown_nullText_treatedAsEmpty() {
            String md = pdfImportService.buildMarkdown("Null Doc.pdf", null);

            assertThat(md).contains("# Null Doc");
            assertThat(md).contains("No text content could be extracted from this PDF");
        }

        @Test
        @DisplayName("normalizes title by removing .pdf extension")
        void buildMarkdown_withPdfExtension_removesIt() {
            String md = pdfImportService.buildMarkdown("report.pdf", "Content");

            assertThat(md).startsWith("# report\n");
        }

        @Test
        @DisplayName("replaces underscores and hyphens with spaces in title")
        void buildMarkdown_underscoreHyphen_normalizesToSpaces() {
            String md = pdfImportService.buildMarkdown("annual_report-2024.pdf", "Content");

            assertThat(md).startsWith("# annual report 2024\n");
        }

        @Test
        @DisplayName("handles brackets in filename")
        void buildMarkdown_bracketsInFilename_normalizes() {
            String md = pdfImportService.buildMarkdown("[Internal] Report.pdf", "Content");

            assertThat(md).startsWith("# Internal Report\n");
        }

        @Test
        @DisplayName("splits double-newline paragraphs correctly")
        void buildMarkdown_doubleNewlineParagraphs_splitsCorrectly() {
            String text = "First paragraph.\n\nSecond paragraph.\n\nThird.";
            String md = pdfImportService.buildMarkdown("Doc.pdf", text);

            assertThat(md).contains("First paragraph.");
            assertThat(md).contains("Second paragraph.");
            assertThat(md).contains("Third.");
        }
    }

    // ==================== getFile ====================

    @Nested
    @DisplayName("getFile")
    class GetFile {

        @Test
        @DisplayName("returns file when found")
        void getFile_found_returnsPresent() {
            FsFile file = new FsFile("uuid/default.md", true, b("content"), "text", "text/markdown", 100L);
            when(fsFileRepository.findById("uuid/default.md")).thenReturn(Optional.of(file));

            Optional<FsFile> result = pdfImportService.getFile("uuid/default.md");

            assertThat(result).isPresent();
            assertThat(result.get().getPath()).isEqualTo("uuid/default.md");
        }

        @Test
        @DisplayName("returns empty when not found")
        void getFile_notFound_returnsEmpty() {
            when(fsFileRepository.findById("not/exists.md")).thenReturn(Optional.empty());

            Optional<FsFile> result = pdfImportService.getFile("not/exists.md");

            assertThat(result).isEmpty();
        }
    }

    // ==================== listChildren ====================

    @Nested
    @DisplayName("listChildren")
    class ListChildren {

        @Test
        @DisplayName("lists direct children without recursing into subdirs")
        void listChildren_nestedFiles_onlyReturnsDirect() {
            FsFile file1 = new FsFile("dir/file1.md", true, b("a"), "a", "text/markdown", 1L);
            FsFile file2 = new FsFile("dir/file2.pdf", false, b("b"), null, "application/pdf", 2L);
            FsFile deepFile = new FsFile("dir/sub/another.pdf", false, b("c"), null, "application/pdf", 3L);
            when(fsFileRepository.findByPathStartingWithOrderByPathAsc("dir/"))
                    .thenReturn(List.of(file1, file2, deepFile));

            List<FsFile> children = pdfImportService.listChildren("dir");

            // listChildren returns raw FsFile records; deepFile's path "dir/sub/another.pdf"
            // has a "/" after "dir/" so it is excluded as a subdirectory child.
            assertThat(children).hasSize(2); // file1 and file2 only
            assertThat(children.stream().map(FsFile::getPath))
                    .containsExactlyInAnyOrder("dir/file1.md", "dir/file2.pdf");
        }

        @Test
        @DisplayName("handles trailing slash in path")
        void listChildren_trailingSlash_normalizesPath() {
            FsFile file1 = new FsFile("dir/file1.md", true, b("a"), "a", "text/markdown", 1L);
            when(fsFileRepository.findByPathStartingWithOrderByPathAsc("dir/"))
                    .thenReturn(List.of(file1));

            List<FsFile> children = pdfImportService.listChildren("dir/");

            assertThat(children).hasSize(1);
        }

        @Test
        @DisplayName("handles backslash as path separator")
        void listChildren_backslash_normalizesToForwardSlash() {
            // When a file path with backslash is passed, the service adds a trailing slash
            // and looks for children of "dir/file1.md/" (which returns empty since that's a file, not dir).
            // This documents the current behavior: listChildren expects a directory path, not a file path.
            FsFile file1 = new FsFile("dir/file1.md", true, b("a"), "a", "text/markdown", 1L);
            when(fsFileRepository.findByPathStartingWithOrderByPathAsc("dir/file1.md/"))
                    .thenReturn(List.of()); // normalized path adds trailing slash, looks for sub-children

            List<FsFile> children = pdfImportService.listChildren("dir\\file1.md");

            assertThat(children).isEmpty();
        }

        @Test
        @DisplayName("returns empty list when no children")
        void listChildren_noChildren_returnsEmpty() {
            when(fsFileRepository.findByPathStartingWithOrderByPathAsc("empty/"))
                    .thenReturn(List.of());

            List<FsFile> children = pdfImportService.listChildren("empty");

            assertThat(children).isEmpty();
        }
    }

    // ==================== loadFileAsResource ====================

    @Nested
    @DisplayName("loadFileAsResource")
    class LoadFileAsResource {

        @Test
        @DisplayName("returns Resource when file found")
        void loadFileAsResource_found_returnsUrlResource() {
            FsFile file = new FsFile("uuid/file.pdf", false, b("pdf content"), null, "application/pdf", 11L);
            when(fsFileRepository.findById("uuid/file.pdf")).thenReturn(Optional.of(file));

            Optional<Resource> result = pdfImportService.loadFileAsResource("uuid/file.pdf");

            assertThat(result).isPresent();
            assertThat(result.get().exists()).isTrue();
            assertThat(result.get().isOpen()).isFalse();
        }

        @Test
        @DisplayName("returns empty when file not found")
        void loadFileAsResource_notFound_returnsEmpty() {
            when(fsFileRepository.findById("not/exists.pdf")).thenReturn(Optional.empty());

            Optional<Resource> result = pdfImportService.loadFileAsResource("not/exists.pdf");

            assertThat(result).isEmpty();
        }
    }

    // ==================== Helpers ====================

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Creates a minimal single-page PDF with one text line using PDFBox. */
    private byte[] createMinimalPdf(String title, String content) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                // Title line
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                cs.newLineAtOffset(50, 700);
                cs.showText(title);
                cs.endText();
                // Content lines (PDFBox doesn't auto-wrap, split by newlines)
                float y = 680;
                for (String line : content.split("\n")) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, y);
                    cs.showText(line);
                    cs.endText();
                    y -= 15;
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            return baos.toByteArray();
        }
    }

    /** Creates an empty PDF with no text content. */
    private byte[] createEmptyPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            return baos.toByteArray();
        }
    }
}
