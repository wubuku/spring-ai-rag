package com.springairag.core.resource;

import com.springairag.core.config.RagChatProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ResourceCatalog 发现路径长尾（Batch 533，JaCoCo 驱动）：JAR 前
 * 缀安全检查、空前缀全量列举、前缀过滤、文件系统单文件根、受限
 * 目录读取失败诊断。
 */
class ResourceCatalogDiscoveryTailTest {

    @TempDir
    Path tempDir;

    private static final Set<String> MD = Set.of("md");

    private Path buildJar(String... entries) throws Exception {
        Path jar = tempDir.resolve("catalog-" + System.nanoTime() + ".jar");
        try (JarOutputStream out = new JarOutputStream(
                Files.newOutputStream(jar))) {
            for (String entry : entries) {
                out.putNextEntry(new JarEntry(entry));
                out.write(("content-of-" + entry)
                        .getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    private ResourceCatalog catalog() {
        return new ResourceCatalog();
    }

    private ResourceSnapshot discover(String location) {
        return catalog().discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of(location), MD, 50, 1_000_000, 10_000_000, true);
    }

    @Test
    void missingJarFileSurfacesReadFailureDiagnostic() {
        // normalizeLocation 在更外层已拒绝 ".."，prefix 的 unsafe 分支
        // 属于纵深防御；此处覆盖 JAR 打开失败的包装路径。
        var error = assertThrows(IllegalStateException.class,
                () -> discover("jar:file:" + tempDir.resolve("missing.jar")
                        .toAbsolutePath() + "!/docs"));
        assertTrue(error.getCause().getMessage()
                .contains("JAR read failed"));
    }

    @Test
    void blankJarPrefixListsAllMatchingEntries() throws Exception {
        Path jar = buildJar("docs/a.md", "docs/b.txt", "other/c.md");

        ResourceSnapshot snapshot = discover(
                "jar:file:" + jar.toAbsolutePath() + "!/");

        assertEquals(2, snapshot.entries().size());
        assertEquals(List.of("docs/a.md", "other/c.md"),
                snapshot.entries().stream()
                        .map(ResourceEntry::relativePath).toList());
    }

    @Test
    void jarEntriesOutsidePrefixAreSkipped() throws Exception {
        Path jar = buildJar("docs/a.md", "other/c.md");

        ResourceSnapshot snapshot = discover(
                "jar:file:" + jar.toAbsolutePath() + "!/docs");

        assertEquals(List.of("a.md"),
                snapshot.entries().stream()
                        .map(ResourceEntry::relativePath).toList());
    }

    @Test
    void filesystemRootMayPointAtASingleFile() throws Exception {
        Path file = tempDir.resolve("single.md");
        Files.writeString(file, "# single\ncontent");

        ResourceSnapshot snapshot = discover(
                "file:" + file.toAbsolutePath());

        assertEquals(List.of("single.md"),
                snapshot.entries().stream()
                        .map(ResourceEntry::relativePath).toList());
    }

    @Test
    void unreadableDirectoryDegradesToDiagnosticWhenNotFailFast() throws Exception {
        Path dir = tempDir.resolve("locked");
        try {
            Files.createDirectory(dir);
            Files.write(dir.resolve("inner.md"), "x".getBytes());
            Files.setPosixFilePermissions(
                    dir, java.nio.file.attribute.PosixFilePermissions
                            .fromString("rwx------"));
            boolean denyRead = dir.toFile().setReadable(false, false)
                    && dir.toFile().setExecutable(false, false);
            org.junit.jupiter.api.Assumptions.assumeTrue(denyRead,
                    "无法在当前用户下收回目录读权限");

            ResourceSnapshot snapshot = catalog().discover(
                    ResourceKind.STATIC_KNOWLEDGE,
                    List.of("file:" + dir.toAbsolutePath()), MD,
                    50, 1_000_000, 10_000_000, false);

            assertTrue(!snapshot.healthy());
            assertEquals(1, snapshot.diagnostics().size());
        } finally {
            dir.toFile().setReadable(true, false);
            dir.toFile().setExecutable(true, false);
        }
    }
}
