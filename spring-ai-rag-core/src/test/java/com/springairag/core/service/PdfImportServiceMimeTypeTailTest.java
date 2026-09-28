package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagPdfProperties;
import com.springairag.core.entity.FsFile;
import com.springairag.core.repository.FsFileRepository;
import com.springairag.core.repository.FsImportBatchRepository;
import com.springairag.core.service.pdf.PdfConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PdfImportService 长尾补充（Batch 694，JaCoCo 驱动）：probeContentType
 * 返回 null 时的 application/octet-stream 兜底、listChildren 对无
 * 斜杠前缀嵌套路径（uuidx/...）的排除、PdfImportResult 三参便捷构
 * 造器的 null 缺省。
 */
class PdfImportServiceMimeTypeTailTest {

    private FsFileRepository fsFileRepository;
    private FsImportBatchRepository fsImportBatchRepository;
    private PdfConverter converter;
    private PdfImportService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        fsFileRepository = mock(FsFileRepository.class);
        fsImportBatchRepository = mock(FsImportBatchRepository.class);
        converter = mock(PdfConverter.class);
        when(converter.isAvailable()).thenReturn(true);
        when(converter.getName()).thenReturn("fake");
        when(fsFileRepository.saveAllAndFlush(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(fsImportBatchRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new PdfImportService(
                fsFileRepository,
                fsImportBatchRepository,
                new RagPdfProperties(),
                List.of(converter));
    }

    private MockMultipartFile pdfFile() {
        return new MockMultipartFile(
                "file", "report.pdf", "application/pdf",
                "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void importAssignsDefaultMimeTypeWhenProbeReturnsNull(
            @TempDir Path tempDir) throws Exception {
        when(converter.convert(any(), any()))
                .thenAnswer(invocation -> {
                    Path outputDir = invocation.getArgument(1);
                    Path source = outputDir.resolve("source");
                    Files.createDirectories(source);
                    Files.write(source.resolve("a-markdown.md"),
                            "# Report".getBytes(StandardCharsets.UTF_8));
                    // 未知扩展名 → Files.probeContentType 返回 null。
                    Files.write(source.resolve("b-blob.xyz123"),
                            new byte[]{1, 2, 3});
                    return true;
                });

        var result = service.importPdf(pdfFile(), null);

        assertEquals(3, result.filesStored());
        var saved = captureSavedFiles();
        var blob = saved.stream()
                .filter(file -> file.getPath().endsWith("b-blob.xyz123"))
                .findFirst()
                .orElseThrow();
        assertEquals("application/octet-stream", blob.getMimeType());
    }

    private List<FsFile> captureSavedFiles() {
        var captor = org.mockito.ArgumentCaptor
                .forClass(java.util.List.class);
        org.mockito.Mockito.verify(fsFileRepository)
                .saveAllAndFlush(captor.capture());
        @SuppressWarnings("unchecked")
        List<FsFile> files = (List<FsFile>) captor.getValue();
        return files;
    }

    @Test
    void rootListingExcludesNonSlashPrefixedNestedPaths() {
        FsFile direct = mock(FsFile.class);
        when(direct.getPath()).thenReturn("uuid/default.md");
        FsFile nested = mock(FsFile.class);
        when(nested.getPath()).thenReturn("uuid/sub/deep.md");
        // 遗留数据：前缀命中但后续字符不是 "/" → 直接排除。
        FsFile prefixed = mock(FsFile.class);
        when(prefixed.getPath()).thenReturn("uuidx/sub/a.md");

        when(fsFileRepository.findByPathStartingWithOrderByPathAsc("uuid"))
                .thenReturn(List.of(direct, nested, prefixed));

        List<FsFile> children = service.listChildren("uuid");

        assertEquals(1, children.size());
        assertEquals("uuid/default.md", children.getFirst().getPath());
    }

    @Test
    void resultConvenienceConstructorDefaultsNulls() {
        var result = new PdfImportService.PdfImportResult(
                "uuid-1", "uuid-1/default.md", 2);

        assertEquals("uuid-1", result.uuid());
        assertEquals("uuid-1/default.md", result.entryMarkdown());
        assertEquals(2, result.filesStored());
        assertNull(result.originalFilename());
        assertNull(result.displayName());
    }
}
