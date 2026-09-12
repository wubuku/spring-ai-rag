package com.springairag.core.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import java.nio.channels.ReadableByteChannel;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * discoverFilesystem 与 configuredRootPath（Batch 350）：单文件位
 * 置发现、根不存在拒绝、符号链接逃逸拒绝、不支持协议拒绝、
 * classpath 根为空时回退文件名、jar 位置剥离条目前缀。
 */
class ResourceCatalogFilesystemRootTest {

    @TempDir
    Path tempDir;

    private final ResourceCatalog catalog = new ResourceCatalog();

    private void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    @Test
    void singleFileLocationDiscoveredWithFilenameRelative() throws Exception {
        Path file = tempDir.resolve("note.md");
        write(file, "hello");

        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of("file:" + file),
                Set.of("md"),
                10, 10_000, 20_000, true);

        assertTrue(snapshot.healthy());
        assertEquals(List.of("note.md"),
                snapshot.entries().stream()
                        .map(ResourceEntry::relativePath)
                        .toList());
    }

    private boolean chainContains(Throwable error, String fragment) {
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null
                    && current.getMessage().contains(fragment)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Test
    void missingFilesystemRootRejected() {
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> catalog.discover(
                        ResourceKind.STATIC_KNOWLEDGE,
                        List.of(tempDir.resolve("missing-dir").toUri().toString()),
                        Set.of("md"),
                        10, 10_000, 20_000, true));

        assertTrue(chainContains(error, "root does not exist"),
                "应报根不存在: " + error.getMessage());
    }

    @Test
    void symlinkEscapeRejected() throws Exception {
        Path outside = tempDir.resolve("outside.md");
        write(outside, "outside content");
        Path root = tempDir.resolve("knowledge-root");
        Files.createDirectories(root);

        Path link = root.resolve("leak.md");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // 环境不支持符号链接时跳过该用例。
        }
        write(root.resolve("real.md"), "inside content");

        // 符号链接被跳过（不跟随、不暴露外部内容），其余文件正常发现。
        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of(root.toUri().toString()),
                Set.of("md"),
                10, 10_000, 20_000, true);

        assertTrue(snapshot.healthy());
        assertEquals(List.of("real.md"),
                snapshot.entries().stream()
                        .map(ResourceEntry::relativePath)
                        .toList());
    }

    @Test
    void unsupportedSchemeRejected() {
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> catalog.discover(
                        ResourceKind.STATIC_KNOWLEDGE,
                        List.of("ftp://example.test/knowledge/"),
                        Set.of("md"),
                        10, 10_000, 20_000, true));

        assertTrue(chainContains(error, "unsupported resource scheme"));
    }

    @Test
    void blankConfiguredRootFallsBackToFilename() throws Exception {
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        Resource resource = fakeResource(
                "file:/elsewhere/deep/x.md", "x.md", "fallback content");
        when(resolver.getResources(anyString()))
                .thenReturn(new Resource[]{resource});

        ResourceCatalog custom = new ResourceCatalog(resolver);
        ResourceSnapshot snapshot = custom.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of("classpath:"),
                Set.of("md"),
                10, 10_000, 20_000, true);

        // 根路径为空 → 相对路径回退为资源文件名。
        assertEquals(List.of("x.md"),
                snapshot.entries().stream()
                        .map(ResourceEntry::relativePath)
                        .toList());
    }

    @Test
    void jarConfiguredRootStripsEntryPrefixForRelativePaths() throws Exception {
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        Resource resource = fakeResource(
                "jar:file:/libs/x.jar!/knowledge/sub/a.md",
                "a.md", "jar content");
        when(resolver.getResources(anyString()))
                .thenReturn(new Resource[]{resource});

        ResourceCatalog custom = new ResourceCatalog(resolver);
        ResourceSnapshot snapshot = custom.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of("jar:central:/libs/x.jar!/knowledge/"),
                Set.of("md"),
                10, 10_000, 20_000, true);

        // jar 位置的条目前缀被剥离，仅保留前缀之后相对路径。
        assertEquals(List.of("sub/a.md"),
                snapshot.entries().stream()
                        .map(ResourceEntry::relativePath)
                        .toList());
    }

    // ── 受控 Resource 桩 ────────────────────────────────────────────

    private Resource fakeResource(String uri, String filename, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new Resource() {
            @Override public boolean exists() { return true; }
            @Override public boolean isReadable() { return true; }
            @Override public boolean isOpen() { return false; }
            @Override public boolean isFile() { return false; }
            @Override public java.net.URL getURL() { return null; }
            @Override public java.net.URI getURI() {
                return java.net.URI.create(uri);
            }
            @Override public File getFile() { return null; }
            @Override public ReadableByteChannel readableChannel() {
                return null;
            }
            @Override public long contentLength() { return bytes.length; }
            @Override public long lastModified() { return 0L; }
            @Override public Resource createRelative(String relative) {
                return null;
            }
            @Override public String getFilename() { return filename; }
            @Override public String getDescription() { return uri; }
            @Override public InputStream getInputStream() {
                return new ByteArrayInputStream(bytes);
            }
        };
    }
}
