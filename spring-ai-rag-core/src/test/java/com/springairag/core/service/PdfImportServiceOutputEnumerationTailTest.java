package com.springairag.core.service;

import com.springairag.core.config.RagPdfProperties;
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PdfImportService 转换产物枚举长尾（Batch 660，JaCoCo 驱动）：
 * 伪转换器产出空白文件名触发 blank filename 拒绝、两个文件名在
 * trim 后归一为同一路径触发 duplicate output path 拒绝。
 */
class PdfImportServiceOutputEnumerationTailTest {

    private FsFileRepository fsFileRepository;
    private RagPdfProperties pdfProperties;
    private PdfConverter converter;
    private PdfImportService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        fsFileRepository = mock(FsFileRepository.class);
        pdfProperties = new RagPdfProperties();
        converter = mock(PdfConverter.class);
        when(converter.isAvailable()).thenReturn(true);
        when(converter.getName()).thenReturn("fake");
        when(fsFileRepository.saveAllAndFlush(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new PdfImportService(
                fsFileRepository,
                mock(FsImportBatchRepository.class),
                pdfProperties,
                List.of(converter));
    }

    private MockMultipartFile pdfFile() {
        return new MockMultipartFile(
                "file", "report.pdf", "application/pdf",
                "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void blankOutputFilenameIsRejected(@TempDir Path tempDir) throws Exception {
        when(fsFileRepository.saveAllAndFlush(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(converter.convert(any(Path.class), any(Path.class)))
                .thenAnswer(invocation -> {
                    Path outputDir = invocation.getArgument(1);
                    Path source = outputDir.resolve("source");
                    Files.createDirectories(source);
                    Files.write(source.resolve("default.md"),
                            "# Report".getBytes(StandardCharsets.UTF_8));
                    // 纯空白文件名：trim 后为空 → blank filename 拒绝。
                    Files.createFile(source.resolve(" "));
                    return true;
                });

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.importPdf(pdfFile(), null));

        assertTrue(error.getMessage().contains("blank filename"));
    }

    @Test
    void duplicateTrimmedOutputPathsAreRejected(@TempDir Path tempDir)
            throws Exception {
        when(fsFileRepository.saveAllAndFlush(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(converter.convert(any(Path.class), any(Path.class)))
                .thenAnswer(invocation -> {
                    Path outputDir = invocation.getArgument(1);
                    Path source = outputDir.resolve("source");
                    Files.createDirectories(source);
                    Files.write(source.resolve("default.md"),
                            "# Report".getBytes(StandardCharsets.UTF_8));
                    // 两个文件名 trim 后映射到同一条记录路径。
                    Files.write(source.resolve("dup.txt"),
                            new byte[]{1});
                    Files.write(source.resolve(" dup.txt"),
                            new byte[]{2});
                    return true;
                });

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.importPdf(pdfFile(), null));

        assertTrue(error.getMessage().contains("duplicate output path"));
    }
}
