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
 * 使用 marker CLI 进行 PDF 转 Markdown。
 *
 * <p>marker CLI 是基于深度学习的 PDF 转换工具，可以高质量地提取文本、保留布局，
 * 并且能够提取 PDF 中的图片。
 *
 * <p>使用方式：
 * <pre>
 * marker_single &lt;PDF文件路径&gt; &lt;输出目录&gt; --langs 'zh'
 * </pre>
 *
 * <p>需要设置环境变量 {@code TORCH_DEVICE=mps} (macOS) 或 {@code TORCH_DEVICE=cuda} (Linux)。
 *
 * <p>输出结构：
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
        if (!isAvailable()) {
            log.warn("Marker CLI is not available: marker-cli={}", pdfProperties.getMarkerCli());
            return false;
        }

        String markerCli = pdfProperties.getMarkerCli();
        String langs = pdfProperties.getLangs();

        // 构建命令：marker_single <pdfPath> <outputDir> --langs <langs>
        ProcessBuilder pb = new ProcessBuilder(
                markerCli,
                pdfPath.toAbsolutePath().toString(),
                outputDir.toAbsolutePath().toString(),
                "--langs", langs
        );

        // 设置环境变量，使用 MPS 加速 (macOS)
        pb.environment().put("TORCH_DEVICE", "mps");

        // 合并错误流到标准流
        pb.redirectErrorStream(true);

        log.info("Running marker CLI: {} {} --langs {}", markerCli, pdfPath.toAbsolutePath(), langs);

        try {
            Process process = pb.start();

            // 读取输出
            StringBuilder output = new StringBuilder();
            try (var reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                int ch;
                while ((ch = reader.read()) != -1) {
                    output.append((char) ch);
                }
            }

            // 等待进程完成，最多5分钟
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

            // 等待最多10秒
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