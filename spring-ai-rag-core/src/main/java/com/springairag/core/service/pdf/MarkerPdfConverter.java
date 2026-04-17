package com.springairag.core.service.pdf;

import com.springairag.core.config.RagPdfProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * PDF to Markdown converter using the marker CLI.
 *
 * <p>marker CLI is a deep-learning-based PDF conversion tool that extracts text with
 * high fidelity, preserves layout, and can extract images from PDFs.
 *
 * <p>Usage:
 * <pre>
 * marker_single &lt;PDF_FILE_PATH&gt; &lt;OUTPUT_DIR&gt; --langs 'en'
 * </pre>
 *
 * <p>Set the environment variable {@code TORCH_DEVICE=mps} (macOS) or {@code TORCH_DEVICE=cuda} (Linux).
 *
 * <p>Output structure:
 * {@code {outputDir}/{pdfName}/{pdfName}.md}
 * {@code {outputDir}/{pdfName}/{image_0}.png}
 */
@Component
public class MarkerPdfConverter implements PdfConverter {

    private static final Logger log = LoggerFactory.getLogger(MarkerPdfConverter.class);

    private final RagPdfProperties pdfProperties;

    public MarkerPdfConverter(RagPdfProperties pdfProperties) {
        this.pdfProperties = pdfProperties;
    }

    @Override
    public boolean convert(Path pdfPath, Path outputDir) {
        if (pdfPath == null) {
            log.error("pdfPath must not be null");
            return false;
        }
        if (outputDir == null) {
            log.error("outputDir must not be null");
            return false;
        }
        if (!isAvailable()) {
            log.warn("Marker CLI is not available: marker-cli={}", pdfProperties.getMarkerCli());
            return false;
        }

        String markerCli = pdfProperties.getMarkerCli();
        String langs = pdfProperties.getLangs();

        // Build command: marker_single <pdfPath> <outputDir> --langs <langs>
        ProcessBuilder pb = new ProcessBuilder(
                markerCli,
                pdfPath.toAbsolutePath().toString(),
                outputDir.toAbsolutePath().toString(),
                "--langs", langs
        );

        // Set environment variable for MPS acceleration (macOS)
        pb.environment().put("TORCH_DEVICE", "mps");

        // Merge stderr into stdout
        pb.redirectErrorStream(true);

        log.info("Running marker CLI: {} {} --langs {}", markerCli, pdfPath.toAbsolutePath(), langs);

        try {
            Process process = pb.start();

            // Read output
            StringBuilder output = new StringBuilder();
            try (var reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                int ch;
                while ((ch = reader.read()) != -1) {
                    output.append((char) ch);
                }
            }

            // Wait for process to complete, up to 5 minutes
            boolean finished = process.waitFor(5, TimeUnit.MINUTES);

            if (!finished) {
                log.error("Marker CLI timed out after 5 minutes");
                process.destroyForcibly();
                return false;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("Marker CLI failed with exit code {}: {}", exitCode, output);
                return false;
            }

            log.info("Marker CLI conversion successful: {}", output);
            return true;

        } catch (IOException e) {
            log.error("Failed to execute marker CLI: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Marker CLI was interrupted: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isAvailable() {
        String markerCli = pdfProperties.getMarkerCli();
        if (markerCli == null || markerCli.isBlank()) {
            return false;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(markerCli, "--help");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Wait up to 10 seconds
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }

            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            log.debug("Marker CLI not available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String getName() {
        return "marker-pdf (marker_single CLI)";
    }
}
