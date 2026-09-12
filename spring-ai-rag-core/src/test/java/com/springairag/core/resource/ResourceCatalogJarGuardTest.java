package com.springairag.core.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * discoverJarFile 的 JAR 位置守卫（Batch 334）：缺条目前缀、不安
 * 全前缀、文件数上限；子目录相对路径保持。
 */
class ResourceCatalogJarGuardTest {

    @TempDir
    Path tempDir;

    private final ResourceCatalog catalog = new ResourceCatalog();

    private Path jarWithEntries(String... entries) throws IOException {
        Path jar = Files.createTempFile(tempDir, "catalog-", ".jar");
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream jarOutput = new JarOutputStream(output)) {
            for (String entry : entries) {
                String name = entry.endsWith("/")
                        ? entry
                        : entry;
                jarOutput.putNextEntry(new java.util.jar.JarEntry(name));
                jarOutput.write((name + " content")
                        .getBytes(StandardCharsets.UTF_8));
                jarOutput.closeEntry();
            }
        }
        return jar;
    }

    private RuntimeException discoverFailure(String location) {
        return assertThrows(RuntimeException.class, () -> catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of(location),
                Set.of("md"),
                10,
                10_000,
                20_000,
                true));
    }

    /** 目录会包装内层异常：沿 cause 链匹配消息片段。 */
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
    void jarLocationWithoutEntryPrefixRejected() throws IOException {
        Path jar = jarWithEntries("knowledge/terms.md");

        RuntimeException error = discoverFailure("jar:" + jar.toUri());

        assertTrue(chainContains(error, "missing entry prefix"),
                "应报缺失条目前缀: " + error.getMessage());
    }

    @Test
    void jarUnsafeEntryPrefixRejected() throws IOException {
        Path jar = jarWithEntries("knowledge/terms.md");

        RuntimeException error = discoverFailure(
                "jar:" + jar.toUri() + "!/../evil");

        assertTrue(chainContains(error, "unsafe"),
                "应报前缀不安全: " + error.getMessage());
    }

    @Test
    void jarFileCountLimitExceededAbortsDiscovery() throws IOException {
        Path jar = jarWithEntries(
                "knowledge/a.md", "knowledge/b.md", "knowledge/c.md");

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> catalog.discover(
                        ResourceKind.STATIC_KNOWLEDGE,
                        List.of("jar:" + jar.toUri() + "!/knowledge/"),
                        Set.of("md"),
                        2,
                        10_000,
                        20_000,
                        true));

        assertTrue(chainContains(error, "file count limit exceeded"),
                "应报文件数超限: " + error.getMessage());
    }

    @Test
    void jarSubdirectoryEntriesKeepRelativeSubpath() throws IOException {
        Path jar = jarWithEntries(
                "knowledge/b.md",
                "knowledge/sub/a.md",
                "knowledge/ignored.bin");

        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of("jar:" + jar.toUri() + "!/knowledge/"),
                Set.of("md"),
                10,
                10_000,
                20_000,
                true);

        assertEquals(List.of("b.md", "sub/a.md"),
                snapshot.entries().stream()
                        .map(ResourceEntry::relativePath)
                        .toList());
        assertEquals("knowledge/b.md content",
                new String(snapshot.entries().getFirst().content(),
                        StandardCharsets.UTF_8));
    }
}
