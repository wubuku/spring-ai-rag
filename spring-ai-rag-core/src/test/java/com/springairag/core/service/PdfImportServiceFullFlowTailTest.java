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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PdfImportService 全流程长尾（Batch 545，JaCoCo 驱动）：伪转换器
 * 产出 source/ 目录的端到端导入、转换器未产出 source 目录拒绝、
 * getFile 委托、listChildren 根列举与前缀直达子过滤、loadFileAs
 * Resource 临时资源生成。
 */
class PdfImportServiceFullFlowTailTest {

    private FsFileRepository fsFileRepository;
    private FsImportBatchRepository fsImportBatchRepository;
    private RagPdfProperties pdfProperties;
    private PdfConverter converter;
    private PdfImportService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        fsFileRepository = mock(FsFileRepository.class);
        fsImportBatchRepository = mock(FsImportBatchRepository.class);
        pdfProperties = new RagPdfProperties();
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
                pdfProperties,
                List.of(converter));
    }

    private MockMultipartFile pdfFile() {
        return new MockMultipartFile(
                "file", "report.pdf", "application/pdf",
                "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void importPdfFullFlowWithFakeConverterCollectsOutputs(
            @TempDir Path tempDir) throws Exception {
        when(converter.convert(any(Path.class), any(Path.class)))
                .thenAnswer(invocation -> {
                    Path outputDir = invocation.getArgument(1);
                    Path source = outputDir.resolve("source");
                    Files.createDirectories(source);
                    Files.write(source.resolve("a-markdown.md"),
                            "# Report".getBytes(StandardCharsets.UTF_8));
                    Files.write(source.resolve("b-image.png"),
                            new byte[]{1, 2, 3});
                    return true;
                });

        PdfImportService.PdfImportResult result =
                service.importPdf(pdfFile(), null);

        // original.pdf + 1 markdown = 2 条记录。
        assertEquals(3, result.filesStored());
        assertEquals("report.pdf", result.originalFilename());
        assertTrue(result.entryMarkdown().endsWith("/default.md"));
    }

    @Test
    void importPdfFailsWhenConverterSkipsSourceDirectory() {
        when(converter.convert(any(Path.class), any(Path.class)))
                .thenReturn(true);

        assertThrows(IllegalStateException.class,
                () -> service.importPdf(pdfFile(), null));
    }

    @Test
    void getFileDelegatesToRepository() {
        FsFile stored = new FsFile("uuid/default.md", true,
                "md".getBytes(StandardCharsets.UTF_8), "md",
                "text/markdown", 2L);
        when(fsFileRepository.findById("uuid/default.md"))
                .thenReturn(Optional.of(stored));

        assertTrue(service.getFile("uuid/default.md").isPresent());
        assertTrue(service.getFile("missing").isEmpty());
    }

    @Test
    void listChildrenFiltersDirectChildrenOnly() {
        FsFile root = new FsFile("uuid/default.md", true,
                "md".getBytes(), "md", "text/markdown", 2L);
        FsFile direct = new FsFile("uuid/sub/file.md", true,
                "md".getBytes(), "md", "text/markdown", 2L);
        FsFile deep = new FsFile("uuid/sub/deep/x.md", true,
                "md".getBytes(), "md", "text/markdown", 2L);
        when(fsFileRepository.findAll()).thenReturn(List.of(root));
        when(fsFileRepository.findByPathStartingWithOrderByPathAsc("uuid"))
                .thenReturn(List.of(direct, deep, root));

        assertEquals(1, service.listChildren(null).size());
        assertEquals(1, service.listChildren("/").size());
        // 直达子：uuid/sub/file.md（remainder "/file.md" 无第二斜杠）。
        // 直达子仅剩 uuid/default.md（sub 下还有更深层级被过滤）。
        assertEquals(List.of("uuid/default.md"),
                service.listChildren("uuid").stream()
                        .map(FsFile::getPath).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadFileAsResourceWrapsContentBinInReadableResource()
            throws Exception {
        FsFile stored = new FsFile("uuid/default.md", true,
                "# body".getBytes(StandardCharsets.UTF_8), "# body",
                "text/markdown", 6L);
        when(fsFileRepository.findById("uuid/default.md"))
                .thenReturn(Optional.of(stored));

        var resource = service.loadFileAsResource("uuid/default.md");
        assertTrue(resource.isPresent());
        try (InputStream input = resource.get().getInputStream()) {
            assertEquals("# body",
                    new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }

        when(fsFileRepository.findById("missing"))
                .thenReturn(Optional.empty());
        assertTrue(service.loadFileAsResource("missing").isEmpty());
    }
}
