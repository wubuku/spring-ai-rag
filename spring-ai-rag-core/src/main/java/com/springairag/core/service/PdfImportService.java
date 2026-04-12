package com.springairag.core.service;

import com.springairag.core.config.RagPdfProperties;
import com.springairag.core.entity.FsFile;
import com.springairag.core.repository.FsFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * PDF Import Service
 *
 * <p>Handles PDF file import into the {@code fs_files} table.
 * The import pipeline:
 * <ol>
 *   <li>Save uploaded PDF to a temporary directory</li>
 *   <li>Run the configured external CLI (e.g., marker_single) to convert PDF → Markdown + images</li>
 *   <li>Import the entire output directory tree into {@code fs_files}</li>
 *   <li>Return metadata about the imported files</li>
 * </ol>
 *
 * <p>The entry Markdown file is determined by convention:
 * the PDF's base name + ".md" (e.g., "论文.pdf" → "论文.md").
 * The PDF itself is stored at the same level with its original filename.
 */
@Service
@ConditionalOnBean(FsFileRepository.class)
public class PdfImportService {

    private static final Logger log = LoggerFactory.getLogger(PdfImportService.class);

    private final FsFileRepository fsFileRepository;
    private final RagPdfProperties pdfProperties;

    public PdfImportService(FsFileRepository fsFileRepository, RagPdfProperties pdfProperties) {
        this.fsFileRepository = fsFileRepository;
        this.pdfProperties = pdfProperties;
    }

    // ==================== Public API ====================

    /**
     * Import a PDF file.
     *
     * <p>The PDF is converted to Markdown + images using the configured external CLI tool.
     * The resulting directory tree is stored in {@code fs_files} with the PDF's virtual path
     * as the root prefix.
     *
     * @param pdfFile   the uploaded PDF file (multipart)
     * @param collection Optional collection/subdirectory path prefix (e.g., "papers/烟酰胺文献")
     * @return import result containing the root path and number of files imported
     */
    @Transactional
    public PdfImportResult importPdf(MultipartFile pdfFile, String collection) {
        if (!pdfProperties.isEnabled()) {
            throw new IllegalStateException("PDF import is disabled (rag.pdf.enabled=false)");
        }

        String originalFilename = pdfFile.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("PDF filename must not be blank");
        }
        String pdfFileName = originalFilename.replaceAll("[\\\\/]", "_"); // sanitize path separators

        // Build the virtual root path for this PDF (e.g., "papers/论文.pdf")
        String virtualRoot;
        if (collection != null && !collection.isBlank()) {
            // collection may be a path like "papers/烟酰胺文献"
            String col = collection.replace('\\', '/').replaceAll("^/|/$", "");
            virtualRoot = col + "/" + pdfFileName;
        } else {
            virtualRoot = pdfFileName;
        }
        // virtualRoot example: "papers/论文.pdf"
        // The entry markdown will be at the same level: "papers/论文.md"
        String virtualBase = virtualRoot.substring(0, virtualRoot.lastIndexOf('/') + 1);
        // virtualBase example: "papers/" (empty string "" if no subdirectory)

        log.info("Importing PDF: original={}, virtualRoot={}", originalFilename, virtualRoot);

        // Step 1: Save PDF to a temp working directory
        Path workDir;
        try {
            workDir = createWorkDir();
            Path pdfPath = workDir.resolve(pdfFileName);
            pdfFile.transferTo(pdfPath);
            log.debug("PDF saved to temp: {}", pdfPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save PDF to temp directory: " + e.getMessage(), e);
        }

        // Step 2: Convert PDF using external CLI
        Path outputDir;
        try {
            outputDir = convertPdfToMarkdown(workDir, pdfFileName, virtualRoot);
        } catch (Exception e) {
            throw new RuntimeException("PDF conversion failed: " + e.getMessage(), e);
        }

        // Step 3: Import the output directory tree into fs_files
        List<FsFile> imported = new ArrayList<>();
        int[] counts = {0};

        try (Stream<Path> walk = Files.walk(outputDir)) {
            walk.filter(Files::isRegularFile)
                .forEach(file -> {
                    String relativePath = outputDir.relativize(file).toString().replace('\\', '/');
                    // Map relative path to virtual path:
                    // The entry markdown convention: PDF base name + ".md"
                    // e.g., "论文.pdf" → conversion outputs "论文.md" and images "0_image_0.png", etc.
                    // We need to figure out the actual output file name for the markdown
                    String virtualPath = mapRelativeToVirtual(relativePath, virtualRoot, outputDir, file);
                    FsFile fsFile = buildFsFile(file, virtualPath);
                    imported.add(fsFile);
                    counts[0]++;
                });
        } catch (IOException e) {
            throw new RuntimeException("Failed to walk output directory: " + e.getMessage(), e);
        }

        // Also store the original PDF itself (for preview/download)
        try {
            byte[] pdfBytes = Files.readAllBytes(workDir.resolve(pdfFileName));
            FsFile pdfFsFile = new FsFile(
                    virtualRoot,
                    false,
                    pdfBytes,
                    null,
                    "application/pdf",
                    (long) pdfBytes.length
            );
            // Avoid duplicate if marker also produced a PDF (it won't, but guard anyway)
            fsFileRepository.findById(virtualRoot)
                    .ifPresentOrElse(
                            existing -> {
                                existing.setContentBin(pdfBytes);
                                existing.setContentTxt(null);
                                existing.setIsText(false);
                                existing.setMimeType("application/pdf");
                                existing.setFileSize((long) pdfBytes.length);
                                fsFileRepository.save(existing);
                            },
                            () -> {
                                fsFileRepository.save(pdfFsFile);
                                imported.add(pdfFsFile);
                                counts[0]++;
                            }
                    );
        } catch (IOException e) {
            log.warn("Failed to read original PDF bytes for storage: {}", e.getMessage());
        }

        // Persist all files
        fsFileRepository.saveAll(imported);

        // Determine the entry Markdown path for preview
        String defaultMarkdownPath = virtualRoot.substring(0, virtualRoot.lastIndexOf('.')) + ".md";
        String entryMarkdownPath = imported.stream()
                .filter(f -> f.getPath().endsWith(".md"))
                .map(FsFile::getPath)
                .findFirst()
                .orElse(defaultMarkdownPath);

        log.info("PDF import completed: virtualRoot={}, filesImported={}, entryMarkdown={}",
                virtualRoot, counts[0], entryMarkdownPath);

        return new PdfImportResult(virtualRoot, entryMarkdownPath, counts[0], imported.size());
    }

    /**
     * Get a file from fs_files by its path.
     *
     * @param path the file path (URL-decoded)
     * @return the FsFile, or empty if not found
     */
    public Optional<FsFile> getFile(String path) {
        return fsFileRepository.findById(path);
    }

    /**
     * List direct children (files and directories) under a virtual path prefix.
     *
     * @param virtualPath the virtual path prefix (e.g., "papers/")
     * @return list of FsFile entries directly under this prefix
     */
    public List<FsFile> listChildren(String virtualPath) {
        String normalizedRaw = virtualPath.replace('\\', '/');
        final String normalized = normalizedRaw.endsWith("/") ? normalizedRaw : normalizedRaw + "/";
        // Find entries whose path starts with normalized and whose immediate child path
        // doesn't contain another slash beyond the prefix
        return fsFileRepository.findByPathStartingWithOrderByPathAsc(normalized)
                .stream()
                .filter(f -> {
                    String remainder = f.getPath().substring(normalized.length());
                    // Only direct children: no more than one segment (no subdirectory)
                    int slashIdx = remainder.indexOf('/');
                    return slashIdx == -1;
                })
                .toList();
    }

    /**
     * Load a file as a Spring {@link Resource} for serving via controller.
     *
     * @param path the file path
     * @return Resource pointing to the file content, or empty if not found
     */
    public Optional<Resource> loadFileAsResource(String path) {
        return fsFileRepository.findById(path)
                .map(file -> {
                    try {
                        // Write content to a temporary file and serve as resource
                        Path tempFile = Files.createTempFile("fsfile-", "-" + path.substring(path.lastIndexOf('/') + 1));
                        tempFile.toFile().deleteOnExit();
                        Files.write(tempFile, file.getContentBin());
                        return new UrlResource(tempFile.toUri());
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to create temp file for: " + path, e);
                    }
                });
    }

    // ==================== Internal Helpers ====================

    private Path createWorkDir() throws IOException {
        Path base = Path.of(pdfProperties.getOutputBaseDir());
        Files.createDirectories(base);
        Path workDir = Files.createTempDirectory(base, "pdf-import-");
        log.debug("Created work directory: {}", workDir);
        return workDir;
    }

    /**
     * Run the external PDF→Markdown CLI tool.
     *
     * <p>Configurable via {@code rag.pdf.*}:
     * <ul>
     *   <li>{@code markerCli} — path to the CLI executable (default: "marker_single")</li>
     *   <li>{@code langs} — language hint (default: "zh")</li>
     *   <li>{@code extraArgs} — additional CLI arguments</li>
     * </ul>
     *
     * @param workDir      the working directory containing the PDF
     * @param pdfFileName  the PDF filename
     * @param virtualRoot  the virtual root path for this PDF (for logging)
     * @return the output directory containing the converted files
     */
    private Path convertPdfToMarkdown(Path workDir, String pdfFileName, String virtualRoot) throws Exception {
        Path pdfPath = workDir.resolve(pdfFileName);
        // marker_single convention: output to a directory named after the PDF (without extension)
        String baseName = pdfFileName.substring(0, pdfFileName.lastIndexOf('.'));
        Path outputDir = workDir.resolve(baseName);
        Files.createDirectories(outputDir);

        List<String> cmd = buildMarkerCommand(pdfPath, outputDir);
        log.info("Running PDF conversion: {}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(false);

        Process process = pb.start();

        // Stream stdout/stderr to log
        try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                log.debug("[marker stdout] {}", line);
            }
            while ((line = err.readLine()) != null) {
                log.warn("[marker stderr] {}", line);
            }
        }

        boolean finished = process.waitFor(5, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("PDF conversion timed out after 5 minutes");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("PDF conversion failed with exit code: " + exitCode);
        }

        log.debug("PDF conversion output directory: {}", outputDir);
        return outputDir;
    }

    /**
     * Build the marker_single command from configuration.
     */
    List<String> buildMarkerCommand(Path pdfPath, Path outputDir) {
        List<String> cmd = new ArrayList<>();
        cmd.add(pdfProperties.getMarkerCli());
        cmd.add(pdfPath.toString());
        cmd.add(outputDir.toString());
        cmd.add("--langs");
        cmd.add(pdfProperties.getLangs());
        if (pdfProperties.getExtraArgs() != null && !pdfProperties.getExtraArgs().isBlank()) {
            for (String arg : pdfProperties.getExtraArgs().split("\\s+")) {
                if (!arg.isBlank()) cmd.add(arg);
            }
        }
        return cmd;
    }

    /**
     * Map a relative path from the marker output directory to a virtual path in fs_files.
     *
     * <p>Marker outputs files like:
     * <pre>
     * /tmp/pdf-import-xxx/论文.pdf         ← input (not part of output)
     * /tmp/pdf-import-xxx/论文/论文.md     ← entry markdown (special!)
     * /tmp/pdf-import-xxx/论文/0_image_0.png
     * /tmp/pdf-import-xxx/论文/1_image_0.png
     * /tmp/pdf-import-xxx/论文/论文_meta.json
     * </pre>
     *
     * <p>We need to strip the workDir prefix and prefix with virtualRoot's base directory.
     * The entry markdown (论文.md) gets placed at virtualRoot's parent level (same dir as PDF).
     */
    private String mapRelativeToVirtual(String relativePath, String virtualRoot,
                                         Path outputDir, Path actualFile) {
        // Example:
        //   outputDir: /tmp/.../论文
        //   relativePath: 论文.md  or  0_image_0.png
        //   virtualRoot: papers/论文.pdf
        //   virtualBase: papers/
        //   entryMarkdown: papers/论文.md  (virtualRoot without extension)
        //   image: papers/0_image_0.png

        String virtualBase = virtualRoot.substring(0, virtualRoot.lastIndexOf('/') + 1);
        String pdfBaseName = virtualRoot.substring(virtualRoot.lastIndexOf('/') + 1,
                virtualRoot.lastIndexOf('.'));

        // Is this the entry markdown? Marker convention: baseName.md at the root of output dir
        if (relativePath.equals(pdfBaseName + ".md")) {
            // Entry markdown goes to virtualBase + pdfBaseName.md
            return virtualBase + pdfBaseName + ".md";
        }

        // Is this the meta JSON? Skip storage
        if (relativePath.endsWith("_meta.json")) {
            return null; // signal to skip
        }

        // Images and other files: virtualBase + relativePath
        // (e.g., virtualBase="" → "0_image_0.png" stays at root, or "papers/0_image_0.png")
        String virtualPath = virtualBase.isEmpty() ? relativePath : virtualBase + relativePath;
        return virtualPath;
    }

    private FsFile buildFsFile(Path file, String virtualPath) {
        if (virtualPath == null) return null; // skip

        String mimeType = probeMimeType(file.toString());
        boolean isText = mimeType != null && (
                mimeType.startsWith("text/") ||
                mimeType.equals("application/json") ||
                mimeType.equals("application/xml")
        );

        byte[] bytes;
        String text = null;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            log.warn("Failed to read file {}: {}", file, e.getMessage());
            return null;
        }

        if (isText) {
            try {
                text = new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception e) {
                isText = false; // fall back to binary
            }
        }

        return new FsFile(
                virtualPath,
                isText,
                bytes,
                text,
                mimeType,
                (long) bytes.length
        );
    }

    private String probeMimeType(String filename) {
        String name = filename.toLowerCase();
        if (name.endsWith(".md")) return "text/markdown";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    // ==================== Result Record ====================

    public record PdfImportResult(
            String virtualRoot,    // e.g., "papers/论文.pdf"
            String entryMarkdown,  // e.g., "papers/论文.md"
            int filesImported,    // total files stored (including PDF)
            int totalRecords      // same as filesImported
    ) {}
}
