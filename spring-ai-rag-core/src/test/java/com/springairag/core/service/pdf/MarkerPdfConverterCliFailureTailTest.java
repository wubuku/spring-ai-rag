package com.springairag.core.service.pdf;

import com.springairag.core.config.RagPdfProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * MarkerPdfConverter CLI 失败长尾（Batch 655，JaCoCo 驱动）：
 * marker 命令不存在时 convert 捕获 IOException 并以 false 收场。
 */
class MarkerPdfConverterCliFailureTailTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("convert returns false when the marker CLI cannot start")
    void convertReturnsFalseWhenCliMissing() {
        RagPdfProperties pdfProperties = new RagPdfProperties();
        pdfProperties.setMarkerCli("definitely-not-a-real-cli-6f3a");
        MarkerPdfConverter converter = new MarkerPdfConverter(pdfProperties);

        Path pdf = tempDir.resolve("input.pdf");
        boolean result = converter.convert(pdf, tempDir);

        assertFalse(result);
    }
}
