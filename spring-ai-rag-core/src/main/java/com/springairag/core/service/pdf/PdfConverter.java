package com.springairag.core.service.pdf;

import java.nio.file.Path;

/**
 * Abstract interface for PDF to Markdown conversion.
 *
 * <p>Different implementations use different techniques:
 * <ul>
 *   <li>{@link MarkerPdfConverter} — marker CLI (deep learning model, high quality, but requires GPU)</li>
 *   <li>{@link PdfBoxConverter} — Apache PDFBox (pure Java, text extraction only, no layout)</li>
 * </ul>
 */
public interface PdfConverter {

    /**
     * Converts a PDF file to Markdown.
     *
     * <p>Output Markdown file: {@code {outputDir}/{pdfName}/{pdfName}.md}
     * Image files (if any): {@code {outputDir}/{pdfName}/}
     *
     * @param pdfPath   source PDF file path
     * @param outputDir output directory (converter creates a subdirectory named after the PDF file)
     * @return true if conversion succeeded, false if conversion failed
     */
    boolean convert(Path pdfPath, Path outputDir);

    /**
     * Checks whether this converter is available.
     *
     * <p>For example, MarkerPdfConverter checks whether the marker CLI is installed.
     *
     * @return true if available, false if unavailable
     */
    boolean isAvailable();

    /**
     * Gets the converter name for logging and error messages.
     *
     * @return converter name
     */
    String getName();
}