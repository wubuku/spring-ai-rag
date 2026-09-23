package com.springairag.core.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ResourceCatalog 总量与逃逸守卫长尾（Batch 578，JaCoCo 驱动）：
 * 累计字节超限抛 total byte limit、符号链接逃逸配置根抛 escapes、
 * failFast=false 时降级为诊断不抛出。
 */
class ResourceCatalogTotalBytesTailTest {

    @TempDir
    Path tempDir;

    private final ResourceCatalog catalog = new ResourceCatalog();

    @Test
    void cumulativeTotalBytesOverLimitRejectedInFailFastMode()
            throws Exception {
        Path root = tempDir.resolve("docs");
        Files.createDirectories(root);
        Files.write(root.resolve("a.md"), "x".repeat(80)
                .getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("b.md"), "y".repeat(80)
                .getBytes(StandardCharsets.UTF_8));

        var error = assertThrows(IllegalStateException.class,
                () -> catalog.discover(
                        ResourceKind.STATIC_KNOWLEDGE,
                        java.util.List.of("file:" + root.toAbsolutePath()),
                        java.util.Set.of("md"), 50, 1_000, 100, true));
        // b.md 超出剩余预算 → readBounded 抛 file byte limit exceeded。
        assertTrue(error.getCause().getMessage()
                .contains("file byte limit exceeded"));
    }

    @Test
    void cumulativeOverLimitDegradesToDiagnosticWhenNotFailFast()
            throws Exception {
        Path root = tempDir.resolve("docs2");
        Files.createDirectories(root);
        Files.write(root.resolve("a.md"), "x".repeat(80)
                .getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("b.md"), "y".repeat(80)
                .getBytes(StandardCharsets.UTF_8));

        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                java.util.List.of("file:" + root.toAbsolutePath()),
                java.util.Set.of("md"), 50, 1_000, 100, false);

        assertTrue(!snapshot.healthy());
        assertEquals(1, snapshot.diagnostics().size());
    }

    @Test
    void symlinkEntriesAreSkippedDuringDiscovery() throws Exception {
        Path root = tempDir.resolve("root");
        Files.createDirectories(root);
        Files.write(root.resolve("real.md"), "real"
                .getBytes(StandardCharsets.UTF_8));
        Path outside = tempDir.resolve("outside.md");
        Files.write(outside, "secret".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(root.resolve("link.md"), outside);

        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                java.util.List.of("file:" + root.toAbsolutePath()),
                java.util.Set.of("md"), 50, 1_000, 10_000, true);

        // 符号链接条目被静默跳过，仅常规文件入选。
        assertEquals(1, snapshot.entries().size());
        assertEquals("real.md", snapshot.entries().getFirst().relativePath());
    }
}
