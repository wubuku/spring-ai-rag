package com.springairag.core.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ResourceCatalog 发现预算长尾（Batch 563，JaCoCo 驱动）：全空白
 * location 回退空快照、JAR 条目按 size 超限拒绝、单一文件超限拒
 * 绝、maxFiles=0 非法限制拒绝。
 */
class ResourceCatalogLimitsDiscoveryTest {

    @TempDir
    Path tempDir;

    private final ResourceCatalog catalog = new ResourceCatalog();

    private static java.util.Set<String> mdSet() {
        return new java.util.HashSet<>(java.util.List.of("md"));
    }

    @Test
    void allBlankLocationsReturnEmptyHealthySnapshot() {
        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                java.util.List.of("   ", ""), mdSet(),
                50, 1_000, 10_000, true);

        assertEquals(0, snapshot.entries().size());
        assertTrue(snapshot.healthy());
    }

    @Test
    void jarEntryOverFileLimitRejectedDuringDiscovery() throws Exception {
        Path jar = tempDir.resolve("oversize-" + System.nanoTime() + ".jar");
        try (JarOutputStream out = new JarOutputStream(
                Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("docs/big.md"));
            out.write("x".repeat(200).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        var error = assertThrows(IllegalStateException.class,
                () -> catalog.discover(
                        ResourceKind.STATIC_KNOWLEDGE,
                        java.util.List.of("jar:file:" + jar.toAbsolutePath()
                                + "!/docs"),
                        mdSet(), 50, 10, 1_000_000, true));
        assertTrue(error.getCause().getMessage()
                .contains("file byte limit exceeded"));
    }

    @Test
    void filesystemFileOverLimitRejectedDuringDiscovery() throws Exception {
        Path big = tempDir.resolve("big.md");
        Files.write(big, "x".repeat(300).getBytes(StandardCharsets.UTF_8));

        var error = assertThrows(IllegalStateException.class,
                () -> catalog.discover(
                        ResourceKind.STATIC_KNOWLEDGE,
                        java.util.List.of("file:" + big.toAbsolutePath()),
                        mdSet(), 50, 100, 10_000, true));
        assertTrue(error.getCause().getMessage()
                .contains("file byte limit exceeded"));
    }

    @Test
    void invalidLimitsRejectedBeforeAnyDiscovery() throws Exception {
        Path file = tempDir.resolve("ok.md");
        Files.write(file, "ok".getBytes(StandardCharsets.UTF_8));

        // maxFiles=0 → "invalid resource limits"。
        var error = assertThrows(IllegalStateException.class,
                () -> catalog.discover(
                        ResourceKind.STATIC_KNOWLEDGE,
                        java.util.List.of("file:" + file.toAbsolutePath()),
                        mdSet(), 0, 1_000, 10_000, true));
        assertTrue(error.getCause().getMessage()
                .contains("invalid resource limits"));
    }
}
