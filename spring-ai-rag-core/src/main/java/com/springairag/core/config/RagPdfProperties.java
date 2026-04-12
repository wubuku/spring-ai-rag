package com.springairag.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
}
