package com.springairag.core.resource;

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

/**
 * ResourceCatalog JAR 发掘长尾（Batch 695，JaCoCo 驱动）：空白条
 * 目缀（根级条目）与非空条目缀（限定子目录）两种前缀拼接臂
 * （257）。
 *
 * 勿再投入：248-249 的 JAR 条目缀 ".." 穿越检查在 normalizeLocation
 * 源头（任何含 ".." 的 location 直接 IAE）就已拦截，discoverJarFile
 * 内层检查经公共 API 不可达；95-96 的跨根字节总上限为外层保险
 * （readBounded 已按 remainingTotalBytes 逐文件封顶）；427-428 的
 * "!/rootPart/" 标记分支为 "/rootPart/" 的子串包含关系，第二标记
 * 可命中时第一标记必已命中。
 */
class ResourceCatalogJarPrefixTailTest {

    private Path jarWithEntries(Path dir) throws Exception {
        Path jar = dir.resolve("knowledge.jar");
        try (JarOutputStream output = new JarOutputStream(
                Files.newOutputStream(jar))) {
            JarEntry rootEntry = new JarEntry("readme.md");
            output.putNextEntry(rootEntry);
            output.write("# root".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            JarEntry nested = new JarEntry("docs/note.md");
            output.putNextEntry(nested);
            output.write("- note".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    @Test
    void jarBlankPrefixDiscoversAllEntries(@TempDir Path tempDir)
            throws Exception {
        Path jar = jarWithEntries(tempDir);
        ResourceCatalog catalog = new ResourceCatalog();

        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of("jar:file:" + jar + "!/"),
                Set.of("md"),
                10, 1_000, 10_000,
                false);

        assertEquals(2, snapshot.entries().size());
        assertEquals("docs/note.md", snapshot.entries().get(0).relativePath());
        assertEquals("readme.md",
                snapshot.entries().get(1).relativePath());
    }

    @Test
    void jarNonBlankPrefixScopesToSubDirectory(@TempDir Path tempDir)
            throws Exception {
        Path jar = jarWithEntries(tempDir);
        ResourceCatalog catalog = new ResourceCatalog();

        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of("jar:file:" + jar + "!/docs"),
                Set.of("md"),
                10, 1_000, 10_000,
                false);

        assertEquals(1, snapshot.entries().size());
        assertEquals("note.md", snapshot.entries().get(0).relativePath());
    }
}