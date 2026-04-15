package com.springairag.core.service.pdf;

import com.springairag.core.config.RagPdfProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MarkerPdfConverter null-safety and configuration edge cases.
 * Note: Tests that invoke the actual marker CLI process are integration tests.
 */
class MarkerPdfConverterTest {

    private final RagPdfProperties pdfProperties = new RagPdfProperties();
    private final MarkerPdfConverter converter = new MarkerPdfConverter(pdfProperties);

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
        when(pdfPath.toAbsolutePath()).thenReturn(pdfPath);
        when(pdfPath.toString()).thenReturn("/test.pdf");
        boolean result = converter.convert(pdfPath, null);
        assertFalse(result);
    }

    @Test
    @DisplayName("isAvailable returns false when marker CLI is null")
    void isAvailable_nullMarkerCli_returnsFalse() {
        pdfProperties.setMarkerCli(null);
        assertFalse(converter.isAvailable());
    }

    @Test
    @DisplayName("isAvailable returns false when marker CLI is blank")
    void isAvailable_blankMarkerCli_returnsFalse() {
        pdfProperties.setMarkerCli("   ");
        assertFalse(converter.isAvailable());
    }

    @Test
    @DisplayName("isAvailable returns false when marker CLI is empty")
    void isAvailable_emptyMarkerCli_returnsFalse() {
        pdfProperties.setMarkerCli("");
        assertFalse(converter.isAvailable());
    }

    @Test
    @DisplayName("getName returns descriptive name")
    void getName_returnsMarkerName() {
        assertEquals("marker-pdf (marker_single CLI)", converter.getName());
    }
}
