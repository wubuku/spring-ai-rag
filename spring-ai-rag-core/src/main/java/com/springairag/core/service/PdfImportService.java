package com.springairag.core.service;

import com.springairag.core.config.RagPdfProperties;
import com.springairag.core.entity.FsFile;
import com.springairag.core.entity.FsImportBatch;
import com.springairag.core.repository.FsFileRepository;
import com.springairag.core.repository.FsImportBatchRepository;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.service.pdf.PdfConverter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.*;

/**
 * PDF Import Service
 *
 * <p>Handles PDF file import into the {@code fs_files} table using marker CLI
 * for high-quality text extraction with layout preservation and image extraction.
 *
 * <p>Import pipeline:
 * <ol>
 *   <li>Generate a UUID as the virtual directory name</li>
 *   <li>Copy uploaded PDF to temp location</li>
 *   <li>Run marker CLI to convert PDF to Markdown + images in temp directory</li>
 *   <li>Import entire directory tree (md + images) into fs_files table</li>
 *   <li>Copy original PDF to {uuid}/original.pdf</li>
 *   <li>Return metadata about the imported files</li>
 * </ol>
 *
 * <p>File storage layout (path = virtual directory + filename):
 * <ul>
 *   <li>{@code {uuid}/default.md} — entry Markdown file</li>
 *   <li>{@code {uuid}/original.pdf} — original PDF binary</li>
 *   <li>{@code {uuid}/{image_0}.png} — extracted images (if any)</li>
 *   <li>{@code {uuid}/{other_files} — any other output from marker</li>
 * </ul>
 *
 * <p>Preview URL: {@code GET /preview/{uuid}/default.html}
 * <p>Image URL: {@code GET /preview/{uuid}/{imageName}}
 */
@Service
public class PdfImportService {

    private static final Logger log = LoggerFactory.getLogger(PdfImportService.class);

    private final FsFileRepository fsFileRepository;
    private final FsImportBatchRepository fsImportBatchRepository;
    private final RagPdfProperties pdfProperties;
    private final List<PdfConverter> converters;

    public PdfImportService(FsFileRepository fsFileRepository,
                            FsImportBatchRepository fsImportBatchRepository,
                            RagPdfProperties pdfProperties,
                            List<PdfConverter> converters) {
        this.fsFileRepository = fsFileRepository;
        this.fsImportBatchRepository = fsImportBatchRepository;
        this.pdfProperties = pdfProperties;
        // Sort by priority: marker CLI first, then PDFBox
        this.converters = converters.stream()
                .sorted(Comparator.comparing(PdfConverter::getName))
                .toList();
    }

    // ==================== Public API ====================

    /**
     * Import a PDF file: convert with marker CLI and store all output in fs_files.
     *
     * @param pdfFile     the uploaded PDF file (multipart)
     * @param collection  Optional collection/subdirectory path prefix (ignored;
     *                    the UUID serves as the virtual directory)
     * @return import result containing the virtual directory UUID and file count
     */
    @Transactional
    public PdfImportResult importPdf(MultipartFile pdfFile, String collection) throws IOException {
        return importPdf(pdfFile, collection, null, null);
    }

    /**
     * 导入 PDF 并记录归属。
     *
     * @param ownerPrincipalId 当前调用 principal；未启用认证时为 null
     * @param collectionId     PDF-to-RAG 的目标 Collection；仅存储文件时为 null
     */
    @Transactional
    public PdfImportResult importPdf(MultipartFile pdfFile, String collection,
                                     String ownerPrincipalId, Long collectionId) throws IOException {
        if (!pdfProperties.isEnabled()) {
            throw new IllegalStateException("PDF import is disabled (rag.pdf.enabled=false)");
        }

        if (pdfFile == null || pdfFile.getSize() > pdfProperties.getMaxUploadBytes()) {
            throw new IllegalArgumentException(
                    "PDF upload exceeds max-upload-bytes (" + pdfProperties.getMaxUploadBytes() + ")");
        }
        String originalFilename = normalizeOriginalFilename(pdfFile);

        // Generate UUID as virtual directory name
        UUID importId = UUID.randomUUID();
        String uuid = importId.toString();
        log.info("Importing PDF: originalFilename={}, uuid={}", originalFilename, uuid);

        // Create temp working directory
        Path tempWorkDir;
        try {
            tempWorkDir = Files.createTempDirectory("pdf-import-");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp working directory: " + e.getMessage(), e);
        }

        try {
            // 客户端文件名只作为元数据，绝不参与临时路径。
            Path tempPdfPath = tempWorkDir.resolve("source.pdf");
            copyUploadBounded(pdfFile, tempPdfPath);
            validatePageCount(tempPdfPath);

            // Find first available converter
            PdfConverter converter = findAvailableConverter();
            if (converter == null) {
                throw new RuntimeException("No PDF converter is available. " +
                        "Please ensure marker CLI is installed or PDFBox is on the classpath.");
            }

            log.info("Using converter: {}", converter.getName());

            // Convert PDF to Markdown + images
            boolean success = converter.convert(tempPdfPath, tempWorkDir);
            if (!success) {
                throw new RuntimeException("PDF conversion failed using " + converter.getName());
            }

            Path markerOutputDir = tempWorkDir.resolve("source");
            if (!Files.isDirectory(markerOutputDir)) {
                throw new IllegalStateException(
                        "PDF converter did not create the expected source/ output directory");
            }

            byte[] pdfBytes = Files.readAllBytes(tempPdfPath);
            List<FsFile> records =
                    collectOutputRecords(uuid, markerOutputDir, pdfBytes, pdfProperties);
            records.forEach(record -> {
                record.setOwnerPrincipalId(ownerPrincipalId);
                record.setCollectionId(collectionId);
            });
            String originalPath = uuid + "/original.pdf";
            String entryMarkdownPath = uuid + "/default.md";

            fsFileRepository.saveAllAndFlush(records);
            FsImportBatch batch = new FsImportBatch(
                    importId,
                    "PDF",
                    originalFilename,
                    originalFilename,
                    entryMarkdownPath,
                    originalPath,
                    records.size()
            );
            batch.setOwnerPrincipalId(ownerPrincipalId);
            batch.setCollectionId(collectionId);
            fsImportBatchRepository.save(batch);

            log.info("PDF import completed: uuid={}, entryMarkdown={}, files={}",
                    uuid, entryMarkdownPath, records.size());

            return new PdfImportResult(
                    uuid,
                    entryMarkdownPath,
                    records.size(),
                    originalFilename,
                    originalFilename
            );

        } finally {
            // Cleanup temp directory
            try {
                Files.walk(tempWorkDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException e) {
                                log.warn("Failed to delete temp file: {}", p);
                            }
                        });
            } catch (IOException e) {
                log.warn("Failed to cleanup temp directory: {}", tempWorkDir);
            }
        }
    }

    /**
     * Find the first available PDF converter.
     */

    /**
     * 装配导入产物：原始 PDF + converter 输出目录中的全部文件。
     * 入口 Markdown 必须恰好一个；输出路径不得重复。
     */
    private static List<FsFile> collectOutputRecords(
            String uuid,
            Path markerOutputDir,
            byte[] pdfBytes,
            RagPdfProperties properties) throws IOException {
        List<FsFile> records = new ArrayList<>();
        String originalPath = uuid + "/original.pdf";
        String entryMarkdownPath = uuid + "/default.md";
        records.add(new FsFile(
                originalPath,
                false,
                pdfBytes,
                null,
                "application/pdf",
                (long) pdfBytes.length
        ));

        List<Path> outputFiles;
        try (Stream<Path> stream = Files.list(markerOutputDir)) {
            outputFiles = stream
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        }
        if (outputFiles.size() > properties.getMaxOutputFiles()) {
            throw new IllegalArgumentException(
                    "PDF conversion produced too many output files (max "
                            + properties.getMaxOutputFiles() + ")");
        }

        List<Path> markdownFiles = outputFiles.stream()
                .filter(file -> file.getFileName().toString()
                        .toLowerCase(Locale.ROOT).endsWith(".md"))
                .toList();
        if (markdownFiles.size() != 1) {
            throw new IllegalStateException(
                    "PDF conversion must produce exactly one entry Markdown file");
        }

        Set<String> recordPaths = new HashSet<>();
        long totalBytes = pdfBytes.length;
        recordPaths.add(originalPath);
        for (Path file : outputFiles) {
            String filename = file.getFileName().toString().trim();
            if (filename.isEmpty()) {
                throw new IllegalStateException("PDF converter produced a blank filename");
            }
            boolean isMarkdown = file.equals(markdownFiles.getFirst());
            String recordPath = isMarkdown
                    ? entryMarkdownPath
                    : uuid + "/" + filename;
            if (!recordPaths.add(recordPath)) {
                throw new IllegalStateException(
                        "PDF converter produced duplicate output path: " + recordPath);
            }

            long size = Files.size(file);
            if (size > properties.getMaxInMemoryBytes()) {
                throw new IllegalArgumentException(
                        "PDF conversion produced a file above max-in-memory-bytes");
            }
            totalBytes = Math.addExact(totalBytes, size);
            if (totalBytes > properties.getMaxOutputBytes()) {
                throw new IllegalArgumentException(
                        "PDF conversion output exceeds max-output-bytes ("
                                + properties.getMaxOutputBytes() + ")");
            }
            byte[] content = Files.readAllBytes(file);
            String mimeType = isMarkdown ? "text/markdown" : Files.probeContentType(file);
            records.add(new FsFile(
                    recordPath,
                    isMarkdown,
                    content,
                    isMarkdown ? new String(content, StandardCharsets.UTF_8) : null,
                    mimeType != null ? mimeType : "application/octet-stream",
                    (long) content.length
            ));
            log.info("Imported file: {} -> {}", file, recordPath);
        }
        return records;
    }

    private void copyUploadBounded(MultipartFile pdfFile, Path target) throws IOException {
        long maxUploadBytes = pdfProperties.getMaxUploadBytes();
        long maxInMemoryBytes = pdfProperties.getMaxInMemoryBytes();
        try (InputStream input = pdfFile.getInputStream();
                OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            long copied = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                copied = Math.addExact(copied, read);
                if (copied > maxUploadBytes) {
                    throw new IllegalArgumentException(
                            "PDF upload exceeds max-upload-bytes (" + maxUploadBytes + ")");
                }
                if (copied > maxInMemoryBytes) {
                    throw new IllegalArgumentException(
                            "PDF upload exceeds max-in-memory-bytes (" + maxInMemoryBytes + ")");
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private void validatePageCount(Path pdfPath) throws IOException {
        try {
            try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
                if (document.getNumberOfPages() > pdfProperties.getMaxPages()) {
                    throw new IllegalArgumentException(
                            "PDF exceeds max-pages (" + pdfProperties.getMaxPages() + ")");
                }
            }
        } catch (IOException invalidPdf) {
            // 让选定 converter 返回其既有的格式错误；不能把所有 converter 的
            // 输入校验提前绑定到 PDFBox，否则 Marker/Fake converter 的兼容路径会失效。
        }
    }

    private PdfConverter findAvailableConverter() {
        for (PdfConverter converter : converters) {
            if (converter.isAvailable()) {
                log.info("Found available converter: {}", converter.getName());
                return converter;
            }
        }
        log.warn("No PDF converter is available");
        return null;
    }

    public static String normalizeOriginalFilename(MultipartFile pdfFile) {
        if (pdfFile == null || pdfFile.isEmpty()) {
            throw new IllegalArgumentException("No file uploaded");
        }
        String raw = pdfFile.getOriginalFilename();
        if (raw == null) {
            throw new IllegalArgumentException("PDF filename must not be blank");
        }
        String normalizedPath = raw.replace('\\', '/');
        String filename = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1).trim();
        if (filename.isEmpty()) {
            throw new IllegalArgumentException("PDF filename must not be blank");
        }
        if (filename.length() > 512) {
            throw new IllegalArgumentException("PDF filename must not exceed 512 characters");
        }
        for (int i = 0; i < filename.length(); i++) {
            char character = filename.charAt(i);
            if (character == 0 || character < 32 || character == 127) {
                throw new IllegalArgumentException("PDF filename contains control characters");
            }
        }
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF files are supported");
        }
        return filename;
    }

    public Optional<FsImportBatch> getImportBatch(UUID importId) {
        return fsImportBatchRepository.findById(importId);
    }

    /** 受限调用方只能读取其可见文件对应的导入批次元数据。 */
    public Optional<FsImportBatch> getVisibleImportBatch(
            UUID importId, ApiAccessPolicy policy) {
        return fsImportBatchRepository.findById(importId)
                .filter(batch -> ApiKeyCollectionAccess.canAccessFile(
                        policy, batch.getOwnerPrincipalId(), batch.getCollectionId()));
    }

    public Map<UUID, FsImportBatch> getImportBatches(Collection<UUID> importIds) {
        if (importIds == null || importIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, FsImportBatch> result = new HashMap<>();
        for (FsImportBatch batch : fsImportBatchRepository.findAllByImportIdIn(importIds)) {
            result.put(batch.getImportId(), batch);
        }
        return result;
    }

    /**
     * Get a file from fs_files by its path.
     */
    public Optional<FsFile> getFile(String path) {
        return fsFileRepository.findById(path);
    }

    /**
     * Get a file only when the calling policy may see it.
     *
     * <p>受限 key 对不可见文件返回 empty（映射为 404），避免暴露文件是否存在。
     */
    public Optional<FsFile> getVisibleFile(String path, ApiAccessPolicy policy) {
        return fsFileRepository.findById(path)
                .filter(file -> ApiKeyCollectionAccess.canAccessFile(
                        policy, file.getOwnerPrincipalId(), file.getCollectionId()));
    }

    /**
     * List direct children (files) under a virtual path prefix.
     */
    public List<FsFile> listChildren(String virtualPath) {
        Listing listing = normalizeListing(virtualPath);
        List<FsFile> files = listing.root()
                ? fsFileRepository.findAll()
                : fsFileRepository.findByPathStartingWithOrderByPathAsc(listing.queryPrefix());
        return listing.root() ? files : filterDirectChildren(files, listing.normalized());
    }

    /**
     * 受限 key 下的目录列举：只返回本人上传或允许 Collection 内的文件。
     * root/不受限调用方与 {@link #listChildren(String)} 完全一致。
     */
    public List<FsFile> listVisibleChildren(String virtualPath, ApiAccessPolicy policy) {
        if (ApiKeyCollectionAccess.isUnrestricted(policy)) {
            return listChildren(virtualPath);
        }
        Listing listing = normalizeListing(virtualPath);
        List<Long> allowed = ApiKeyCollectionAccess
                .restrictedCollectionIds(policy).orElseThrow().stream().toList();
        String owner = policy == null ? null : policy.getPrincipalId();
        List<FsFile> files = listing.root()
                ? fsFileRepository.findVisibleRootFiles(owner, allowed)
                : fsFileRepository.findVisibleByPathPrefix(
                        owner, allowed, listing.queryPrefix());
        return listing.root() ? files : filterDirectChildren(files, listing.normalized());
    }

    private List<FsFile> filterDirectChildren(List<FsFile> files, String normalized) {
        return files.stream()
                .filter(f -> {
                    // Handle leading whitespace in DB paths (legacy data)
                    String cleanPath = f.getPath().trim();
                    String remainder = cleanPath.substring(normalized.length());
                    // A direct child has no "/" in remainder (e.g. "default.md")
                    // or remainder starts with "/" and has no more "/" (e.g. "/default.md")
                    if (remainder.indexOf('/') == -1) {
                        return true;
                    }
                    if (remainder.startsWith("/")) {
                        return remainder.indexOf('/', 1) == -1;
                    }
                    return false;
                })
                .toList();
    }

    private record Listing(boolean root, String normalized, String queryPrefix) {
    }

    private static Listing normalizeListing(String virtualPath) {
        String normalizedRaw = (virtualPath == null || virtualPath.isBlank())
                ? "/" : virtualPath.replace('\\', '/');
        if (normalizedRaw.equals("/") || normalizedRaw.isEmpty()) {
            return new Listing(true, "/", "");
        }
        final String normalized = normalizedRaw.endsWith("/")
                ? normalizedRaw.substring(0, normalizedRaw.length() - 1).trim()
                : normalizedRaw.trim();
        final String queryPrefix = normalized.startsWith("/")
                ? normalized.substring(1).trim()
                : normalized;
        return new Listing(false, normalized, queryPrefix);
    }

    /**
     * 校验虚拟路径：拒绝空字节、控制字符以及 "." / ".." 片段。
     *
     * <p>路径只用于查询数据库，不会拼接真实文件系统路径；这里的拒绝是为了
     * 让穿越尝试稳定失败（400），而不是依赖“数据库里恰好没有这一行”。
     */
    public static String validateVirtualPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        for (int i = 0; i < normalized.length(); i++) {
            char character = normalized.charAt(i);
            if (character == 0 || character < 32 || character == 127) {
                throw new IllegalArgumentException("Path contains control characters");
            }
        }
        for (String segment : normalized.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Path traversal is not allowed");
            }
        }
        return normalized;
    }

    /**
     * Load a file as a Spring Resource for serving via controller.
     */
    public Optional<Resource> loadFileAsResource(String path) {
        return fsFileRepository.findById(path)
                .map(file -> {
                    try {
                        String filename = path.substring(path.lastIndexOf('/') + 1);
                        if (filename.isEmpty()) {
                            filename = "file";
                        }
                        // 临时文件名只使用安全字符，避免路径分隔符进入文件系统。
                        String safeSuffix = filename.replaceAll("[^A-Za-z0-9._-]", "_");
                        Path tempFile = Files.createTempFile("fsfile-", "-" + safeSuffix);
                        tempFile.toFile().deleteOnExit();
                        Files.write(tempFile, file.getContentBin());
                        return new UrlResource(tempFile.toUri());
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to create temp file for: " + path, e);
                    }
                });
    }

    // ==================== Result Record ====================

    public record PdfImportResult(
            /** Virtual directory UUID */
            String uuid,
            /** Path to the entry Markdown file, e.g. "{uuid}/default.md" */
            String entryMarkdown,
            /** Total number of files stored */
            int filesStored,
            /** Normalized original upload filename */
            String originalFilename,
            /** Human-readable display name */
            String displayName
    ) {
        public PdfImportResult(String uuid, String entryMarkdown, int filesStored) {
            this(uuid, entryMarkdown, filesStored, null, null);
        }
    }
}
