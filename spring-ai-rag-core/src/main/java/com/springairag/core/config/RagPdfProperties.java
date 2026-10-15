package com.springairag.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * PDF Import Configuration
 *
 * <p>Configures the external CLI tool used to convert PDF files into
 * Markdown + images directory trees.
 *
 * <p>Example:
 * <pre>
 * rag:
 *   pdf:
 *     enabled: true
 *     marker-cli: /usr/local/bin/marker_single
 *     langs: "zh"
 *     output-base-dir: /tmp/spring-ai-rag/pdf-imports
 * </pre>
 */
@ConfigurationProperties(prefix = "rag.pdf")
public class RagPdfProperties {

    /**
     * Enable PDF import functionality.
     */
    private boolean enabled = true;

    /**
     * Path to the marker_single CLI executable.
     * Example: /usr/local/bin/marker_single
     */
    private String markerCli = "marker_single";

    /**
     * Language hint passed to marker (comma-separated, e.g., "zh,en").
     */
    private String langs = "zh";

    /**
     * Base directory for temporary output when converting PDFs.
     * Each conversion creates a subdirectory under this path.
     */
    private String outputBaseDir = "/tmp/spring-ai-rag/pdf-imports";

    /**
     * Command-line arguments passed as-is after the output directory argument.
     * Optional. Leave blank to use marker_single defaults.
     * Example: "--no-markdown-template"
     */
    private String extraArgs = "";

    private long maxUploadBytes = 20L * 1024 * 1024;
    private int maxPages = 500;
    private int maxOutputFiles = 256;
    private long maxOutputBytes = 200L * 1024 * 1024;
    private Duration conversionTimeout = Duration.ofMinutes(5);
    private long maxInMemoryBytes = 32L * 1024 * 1024;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getMarkerCli() { return markerCli; }
    public void setMarkerCli(String markerCli) { this.markerCli = markerCli; }

    public String getLangs() { return langs; }
    public void setLangs(String langs) { this.langs = langs; }

    public String getOutputBaseDir() { return outputBaseDir; }
    public void setOutputBaseDir(String outputBaseDir) { this.outputBaseDir = outputBaseDir; }

    public String getExtraArgs() { return extraArgs; }
    public void setExtraArgs(String extraArgs) { this.extraArgs = extraArgs; }

    public long getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(long maxUploadBytes) {
        this.maxUploadBytes = requirePositive(maxUploadBytes, "maxUploadBytes");
    }

    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int maxPages) {
        this.maxPages = (int) requirePositive(maxPages, "maxPages");
    }

    public int getMaxOutputFiles() { return maxOutputFiles; }
    public void setMaxOutputFiles(int maxOutputFiles) {
        this.maxOutputFiles = (int) requirePositive(maxOutputFiles, "maxOutputFiles");
    }

    public long getMaxOutputBytes() { return maxOutputBytes; }
    public void setMaxOutputBytes(long maxOutputBytes) {
        this.maxOutputBytes = requirePositive(maxOutputBytes, "maxOutputBytes");
    }

    public Duration getConversionTimeout() { return conversionTimeout; }
    public void setConversionTimeout(Duration conversionTimeout) {
        if (conversionTimeout == null || conversionTimeout.isZero() || conversionTimeout.isNegative()) {
            throw new IllegalArgumentException("conversionTimeout must be positive");
        }
        this.conversionTimeout = conversionTimeout;
    }

    public long getMaxInMemoryBytes() { return maxInMemoryBytes; }
    public void setMaxInMemoryBytes(long maxInMemoryBytes) {
        this.maxInMemoryBytes = requirePositive(maxInMemoryBytes, "maxInMemoryBytes");
    }

    private long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
