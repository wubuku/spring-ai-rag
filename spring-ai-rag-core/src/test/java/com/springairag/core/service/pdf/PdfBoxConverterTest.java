package com.springairag.core.service.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PdfBoxConverter null-safety and edge cases.
 * Note: Full integration tests with actual PDFBox library require real PDF files.
 */
class PdfBoxConverterTest {

    private final PdfBoxConverter converter = new PdfBoxConverter();

    @Test
    @DisplayName("convert returns false when pdfPath is null")
    void convert_nullPdfPath_returnsFalse() {
        Path outputDir = mock(Path.class);
        boolean result = converter.convert(null, outputDir);
        assertFalse(result);
    }

    @Test
    @DisplayName("convert returns false when outputDir is null")
    void convert_nullOutputDir_returnsFalse() {
        Path pdfPath = mock(Path.class);
        when(pdfPath.getFileName()).thenReturn(mock(Path.class));
        boolean result = converter.convert(pdfPath, null);
        assertFalse(result);
    }

    @Test
    @DisplayName("isAvailable always returns true since PDFBox is a library dependency")
    void isAvailable_alwaysTrue() {
        assertTrue(converter.isAvailable());
    }

    @Test
    @DisplayName("getName returns descriptive name")
    void getName_returnsPdfBoxName() {
        assertEquals("Apache PDFBox (text only)", converter.getName());
    }
}
