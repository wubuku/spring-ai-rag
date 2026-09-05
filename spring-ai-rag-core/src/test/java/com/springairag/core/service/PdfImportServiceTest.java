package com.springairag.core.service;

import com.springairag.core.config.RagPdfProperties;
import com.springairag.core.entity.FsFile;
import com.springairag.core.entity.FsImportBatch;
import com.springairag.core.repository.FsFileRepository;
import com.springairag.core.repository.FsImportBatchRepository;
import com.springairag.core.service.pdf.PdfConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PdfImportService#importPdf} 的服务级护栏：用 Fake converter 驱动
 * 完整导入管线（无需真实 PDF/CLI），锁定 records 装配、入口 markdown 校验
 * 与临时目录清理契约。
 */
class PdfImportServiceTest {

    @TempDir
    Path tempDir;

    private FsFileRepository fsFileRepository;
    private FsImportBatchRepository fsImportBatchRepository;
    private RagPdfProperties pdfProperties;

    /** 按脚本在 outputDir/source 下产出文件的 Fake converter。 */
    private static class FakeConverter implements PdfConverter {
        final String name;
        final boolean available;
        boolean convertResult = true;
        IOException convertError;
        List<String> outputFiles = List.of("default.md");
        Path seenOutputDir;

        FakeConverter(String name, boolean available) {
            this.name = name;
            this.available = available;
        }

        @Override
        public boolean convert(Path pdfPath, Path outputDir) {
            seenOutputDir = outputDir;
            if (convertError != null) {
                throw new RuntimeException(convertError);
            }
            if (!convertResult) {
                return false;
            }
            try {
                Path source = outputDir.resolve("source");
                Files.createDirectories(source);
                for (String file : outputFiles) {
                    Files.writeString(source.resolve(file), "content of " + file);
                }
                Files.write(pdfPath, new byte[] {1, 2, 3});
                return true;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public String getName() {
            return name;
        }
    }

    private MultipartFile upload(String filename) {
        return new MockMultipartFile(
                "file", filename, "application/pdf", new byte[] {1, 2, 3});
    }

    private PdfImportService service(FakeConverter converter) {
        fsFileRepository = mock(FsFileRepository.class);
        fsImportBatchRepository = mock(FsImportBatchRepository.class);
        pdfProperties = new RagPdfProperties();
        pdfProperties.setEnabled(true);
        return new PdfImportService(
                fsFileRepository, fsImportBatchRepository, pdfProperties,
                List.of(converter));
    }

    @BeforeEach
    void resetRepositories() {
        // repositories are recreated per service(); nothing to reset here
    }

    @Test
    void importsOriginalEntryMarkdownAndImagesWithCleanTempDirectory() throws IOException {
        FakeConverter converter = new FakeConverter("fake", true);
        converter.outputFiles = List.of("default.md", "img0.png");
        PdfImportService service = service(converter);

        PdfImportService.PdfImportResult result = service.importPdf(
                upload("manual.pdf"), null);

        UUID.fromString(result.uuid());
        assertEquals(result.uuid() + "/default.md", result.entryMarkdown());
        assertEquals("manual.pdf", result.originalFilename());
        assertEquals(3, result.filesStored());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FsFile>> records = ArgumentCaptor.forClass(List.class);
        verify(fsFileRepository).saveAllAndFlush(records.capture());
        List<FsFile> saved = records.getValue();
        assertEquals(3, saved.size());

        FsFile original = saved.get(0);
        assertEquals(result.uuid() + "/original.pdf", original.getPath());
        assertFalse(original.getIsText());

        FsFile entry = saved.get(1);
        assertEquals(result.uuid() + "/default.md", entry.getPath());
        assertTrue(entry.getIsText());
        assertEquals("content of default.md", entry.getContentTxt());
        assertEquals("text/markdown", entry.getMimeType());

        FsFile image = saved.get(2);
        assertEquals(result.uuid() + "/img0.png", image.getPath());
        assertFalse(image.getIsText());

        verify(fsImportBatchRepository).save(
            org.mockito.ArgumentMatchers.any(FsImportBatch.class));

        // 临时目录在导入完成后必须被清理
        assertFalse(Files.exists(converter.seenOutputDir));
    }

    @Test
    void disabledImportFailsFast() {
        FakeConverter converter = new FakeConverter("fake", true);
        PdfImportService service = service(converter);
        pdfProperties.setEnabled(false);

        assertThrows(IllegalStateException.class,
                () -> service.importPdf(upload("a.pdf"), null));
    }

    @Test
    void missingConverterFailsWithActionableMessage() {
        FakeConverter unavailable = new FakeConverter("fake", false);
        PdfImportService service = service(unavailable);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.importPdf(upload("a.pdf"), null));

        assertTrue(error.getMessage().contains("No PDF converter is available"));
    }

    @Test
    void failedConversionSurfacesConverterName() {
        FakeConverter converter = new FakeConverter("marker-cli", true);
        converter.convertResult = false;
        PdfImportService service = service(converter);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.importPdf(upload("a.pdf"), null));

        assertTrue(error.getMessage().contains("marker-cli"));
    }

    @Test
    void missingSourceDirectoryIsRejected() throws IOException {
        FakeConverter converter = new FakeConverter("fake", true);
        converter.outputFiles = List.of(); // 不创建 source/ 目录
        PdfImportService service = service(converter);

        assertThrows(IllegalStateException.class,
                () -> service.importPdf(upload("a.pdf"), null));
    }

    @Test
    void multipleEntryMarkdownFilesAreRejected() throws IOException {
        FakeConverter converter = new FakeConverter("fake", true);
        converter.outputFiles = List.of("default.md", "other.md");
        PdfImportService service = service(converter);

        assertThrows(IllegalStateException.class,
                () -> service.importPdf(upload("a.pdf"), null));
    }
}
