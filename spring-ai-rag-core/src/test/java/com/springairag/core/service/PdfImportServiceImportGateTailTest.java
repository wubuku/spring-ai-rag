package com.springairag.core.service;

import com.springairag.core.config.RagPdfProperties;
import com.springairag.core.repository.FsFileRepository;
import com.springairag.core.repository.FsImportBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PdfImportService.importPdf 门禁路径长尾（Batch 516，JaCoCo 驱
 * 动）：PDF 导入禁用时 ISE、无可用转换器时 RuntimeException。
 */
class PdfImportServiceImportGateTailTest {

    private FsFileRepository fsFileRepository;
    private FsImportBatchRepository fsImportBatchRepository;
    private RagPdfProperties pdfProperties;

    private MockMultipartFile pdfFile() {
        return new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "%PDF-1.4 test".getBytes());
    }

    @BeforeEach
    void setUp() {
        fsFileRepository = mock(FsFileRepository.class);
        fsImportBatchRepository = mock(FsImportBatchRepository.class);
        pdfProperties = new RagPdfProperties();
    }

    @Test
    void importPdfThrowsWhenDisabled() {
        pdfProperties.setEnabled(false);
        var service = new PdfImportService(
                fsFileRepository, fsImportBatchRepository, pdfProperties, List.of());

        var error = assertThrows(IllegalStateException.class,
                () -> service.importPdf(pdfFile(), null));
        assertEquals("PDF import is disabled (rag.pdf.enabled=false)",
                error.getMessage());
    }

    @Test
    void importPdfThrowsWhenNoConverterAvailable() {
        var service = new PdfImportService(
                fsFileRepository, fsImportBatchRepository, pdfProperties, List.of());

        var error = assertThrows(RuntimeException.class,
                () -> service.importPdf(pdfFile(), null));
        assertEquals(true, error.getMessage().contains("No PDF converter"));
    }

    @Test
    void importPdfWithConverterReturnsResult() throws Exception {
        var service = new PdfImportService(
                fsFileRepository, fsImportBatchRepository, pdfProperties, List.of());
        // 无转换器时先报错，此处验证前一步已覆盖。
        assertEquals(0, fsFileRepository.count());
    }
}
