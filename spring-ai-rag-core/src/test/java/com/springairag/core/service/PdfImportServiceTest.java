package com.springairag.core.service;

import com.springairag.core.config.RagPdfProperties;
import com.springairag.core.entity.FsFile;
import com.springairag.core.repository.FsFileRepository;
import com.springairag.core.service.pdf.PdfConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static java.nio.file.Files.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PdfImportService}.
 *
 * Tests the PDF import flow with the new PdfConverter interface.
 * Note: The actual PDF conversion is delegated to PdfConverter implementations.
 */
class PdfImportServiceTest {

    private FsFileRepository fsFileRepository;
    private RagPdfProperties pdfProperties;
    private PdfImportService pdfImportService;
    private PdfConverter mockConverter;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        fsFileRepository = mock(FsFileRepository.class);
        pdfProperties = new RagPdfProperties();
        pdfProperties.setEnabled(true);

        // Create a mock converter that succeeds
        mockConverter = mock(PdfConverter.class);
        when(mockConverter.isAvailable()).thenReturn(true);
        when(mockConverter.getName()).thenReturn("mock-converter");
        when(mockConverter.convert(any(), any())).thenAnswer(inv -> {
            createDirectories(inv.getArgument(1));
            return true;
        });

        pdfImportService = new PdfImportService(fsFileRepository, pdfProperties, List.of(mockConverter));
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
        @DisplayName("throws IllegalArgumentException for non-PDF extension")
        void importPdf_nonPdfExtension_throws() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "doc.docx", "application/pdf", b("hello"));

            assertThatThrownBy(() -> pdfImportService.importPdf(file, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PDF");
        }

        @Test
        @DisplayName("throws RuntimeException when all converters fail")
        void importPdf_allConvertersFail_throws() {
            // Configure all converters to fail
            PdfConverter failingConverter = mock(PdfConverter.class);
            when(failingConverter.isAvailable()).thenReturn(true);
            when(failingConverter.convert(any(), any())).thenReturn(false);

            pdfImportService = new PdfImportService(fsFileRepository, pdfProperties, List.of(failingConverter));

            MockMultipartFile file = new MockMultipartFile(
                    "file", "doc.pdf", "application/pdf", b("hello"));

            assertThatThrownBy(() -> pdfImportService.importPdf(file, null))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("failed");
        }
    }

    // ==================== importPdf success ====================

    @Nested
    @DisplayName("importPdf success")
    class ImportPdfSuccess {

        @Test
        @DisplayName("imports PDF with successful conversion")
        void importPdf_success_storesFiles() {
            byte[] pdfBytes = b("fake pdf content");
            MockMultipartFile file = new MockMultipartFile(
                    "file", "Test-Report.pdf", "application/pdf", pdfBytes);

            when(fsFileRepository.save(any(FsFile.class))).thenAnswer(inv -> inv.getArgument(0));
            when(fsFileRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

            PdfImportService.PdfImportResult result =
                    pdfImportService.importPdf(file, null);

            assertThat(result.uuid()).isNotBlank();
            assertThat(result.entryMarkdown()).isEqualTo(result.uuid() + "/default.md");
            assertThat(result.filesStored()).isGreaterThan(0);

            verify(mockConverter).convert(any(), any());
        }

        @Test
        @DisplayName("stores original PDF as {uuid}/original.pdf")
        void importPdf_storesOriginalPdf() {
            byte[] pdfBytes = b("fake pdf content");
            MockMultipartFile file = new MockMultipartFile(
                    "file", "doc.pdf", "application/pdf", pdfBytes);

            when(fsFileRepository.save(any(FsFile.class))).thenAnswer(inv -> inv.getArgument(0));
            when(fsFileRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

            PdfImportService.PdfImportResult result =
                    pdfImportService.importPdf(file, null);

            verify(fsFileRepository).save(argThat((FsFile f) ->
                    f.getPath().equals(result.uuid() + "/original.pdf")));
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

            assertThat(children).hasSize(2);
            assertThat(children.stream().map(FsFile::getPath))
                    .containsExactlyInAnyOrder("dir/file1.md", "dir/file2.pdf");
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
}