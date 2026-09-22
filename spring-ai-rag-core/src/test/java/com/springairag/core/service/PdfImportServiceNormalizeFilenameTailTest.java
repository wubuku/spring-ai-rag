package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagPdfProperties;
import com.springairag.core.entity.FsFile;
import com.springairag.core.repository.FsFileRepository;
import com.springairag.core.repository.FsImportBatchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PdfImportService 文件名规范化长尾（Batch 567，JaCoCo 驱动）：
 * normalizeOriginalFilename 的空文件/null 文件名/空白名/路径剥离/
 * 超 512 字符拒绝。
 */
class PdfImportServiceNormalizeFilenameTailTest {

    private static final FsFileRepository FS_FILES =
            mock(FsFileRepository.class);
    private static final FsImportBatchRepository FS_BATCHES =
            mock(FsImportBatchRepository.class);

    @Test
    void normalizeRejectsEmptyUpload() {
        var service = new PdfImportService(
                FS_FILES, FS_BATCHES, new RagPdfProperties(), List.of());
        MockMultipartFile empty = new MockMultipartFile(
                "file", "a.pdf", "application/pdf", new byte[0]);

        assertThrows(IllegalArgumentException.class,
                () -> service.importPdf(empty, null));
    }

    @Test
    void normalizeRejectsNullAndBlankOriginalNames() {
        MockMultipartFile nullName = new MockMultipartFile(
                "file", null, "application/pdf", "x".getBytes());
        assertThrows(IllegalArgumentException.class,
                () -> PdfImportService.normalizeOriginalFilename(nullName));

        MockMultipartFile slashOnly = new MockMultipartFile(
                "file", "dir/", "application/pdf", "x".getBytes());
        assertThrows(IllegalArgumentException.class,
                () -> PdfImportService.normalizeOriginalFilename(slashOnly));
    }

    @Test
    void normalizeStripsDirectorySegmentsAndTrims() {
        MockMultipartFile windowsPath = new MockMultipartFile(
                "file", "C:\\temp\\ report.pdf ", "application/pdf",
                "x".getBytes());

        assertEquals("report.pdf",
                PdfImportService.normalizeOriginalFilename(windowsPath));
    }

    @Test
    void normalizeRejectsOverLongNames() {
        MockMultipartFile longName = new MockMultipartFile(
                "file", "n".repeat(513) + ".pdf", "application/pdf",
                "x".getBytes());

        assertThrows(IllegalArgumentException.class,
                () -> PdfImportService.normalizeOriginalFilename(longName));
    }

    @Test
    void importPdfWithNullFilenameRejectedBeforeTempDir() {
        var service = new PdfImportService(
                FS_FILES, FS_BATCHES, new RagPdfProperties(), List.of());
        MultipartFile nullName = mock(MultipartFile.class);
        when(nullName.isEmpty()).thenReturn(false);
        when(nullName.getOriginalFilename()).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.importPdf(nullName, null));
    }

    @Test
    void listChildrenDirectChildFilteringForLegacyWhitespacePaths()
            throws Exception {
        FsFile direct = new FsFile(" uuid/default.md", true,
                "m".getBytes(StandardCharsets.UTF_8), "m",
                "text/markdown", 1L);
        FsFile deep = new FsFile("uuid/sub/deep/x.md", true,
                "m".getBytes(StandardCharsets.UTF_8), "m",
                "text/markdown", 1L);
        when(FS_FILES.findAll()).thenReturn(java.util.List.of(direct));
        when(FS_FILES.findByPathStartingWithOrderByPathAsc("uuid"))
                .thenReturn(java.util.List.of(direct, deep));

        var service = new PdfImportService(
                FS_FILES, FS_BATCHES, new RagPdfProperties(), List.of());

        // 根列举：findAll 的带空白路径直达子命中。
        assertEquals(1, service.listChildren(null).size());
        // 前缀模式：uuid 下仅 default.md 直达。
        assertEquals(1, service.listChildren("uuid").size());
    }
}
