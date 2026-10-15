package com.springairag.core.service.pdf;

import com.springairag.core.config.RagPdfProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

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

            CompletableFuture<CapturedOutput> outputFuture = CompletableFuture.supplyAsync(
                    () -> captureOutput(process.getInputStream(), 32_000));
            boolean finished = process.waitFor(
                    pdfProperties.getConversionTimeout().toMillis(), TimeUnit.MILLISECONDS);

            if (!finished) {
                log.error("Marker CLI timed out after {}", pdfProperties.getConversionTimeout());
                process.destroyForcibly();
                outputFuture.cancel(true);
                return false;
            }

            int exitCode = process.exitValue();
            String output = outputFuture.get(5, TimeUnit.SECONDS).text();
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
        } catch (java.util.concurrent.ExecutionException
                 | java.util.concurrent.TimeoutException e) {
            log.error("Failed to collect Marker CLI output: {}", e.getMessage());
            return false;
        }
    }

    private CapturedOutput captureOutput(InputStream input, int limit) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream(Math.min(limit, 4096));
        byte[] buffer = new byte[8192];
        boolean truncated = false;
        try (input) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                int remaining = limit - captured.size();
                if (remaining > 0) {
                    captured.write(buffer, 0, Math.min(read, remaining));
                }
                if (read > Math.max(remaining, 0)) {
                    truncated = true;
                }
            }
        } catch (IOException e) {
            truncated = true;
        }
        return new CapturedOutput(
                new String(captured.toByteArray(), StandardCharsets.UTF_8), truncated);
    }

    private record CapturedOutput(String text, boolean truncated) {
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
            // 中断不能和 IOException 一样当成"CLI 不可用"处理：Process.waitFor 会
            // 清掉中断标志，吞掉之后本线程后续每一次阻塞调用都看不到这个取消信号，
            // isAvailable() 返回 false 之后调用方还会照常往下走。上面 convert() 的
            // catch 是本文件里同一件事的另一种写法——先恢复标志，再按自己的语义收场。
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("Marker CLI not available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String getName() {
        return "marker-pdf (marker_single CLI)";
    }
}
