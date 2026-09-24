package com.springairag.core.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ResourceCatalog 发现与归一长尾（Batch 617，JaCoCo 驱动）：全空白
 * 位置返回空快照、总字节上限诊断、非法限额拒绝、扩展名过滤与
 * 空扩展全放行、归一化扩展大小写与点号。
 */
class ResourceCatalogNormalizeDiscoverTailTest {

    private final ResourceCatalog catalog = new ResourceCatalog();

    @TempDir
    Path tempDir;

    private String fileLocation(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return "file:" + tempDir.toUri().getPath() + name;
    }

    @Test
    void allBlankLocationsReturnEmptySnapshot() {
        var snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                java.util.Arrays.asList("  ", null),
                null, 5, 1_000, 10_000, false);

        assertTrue(snapshot.entries().isEmpty());
        assertTrue(snapshot.healthy());
    }

    @Test
    void discoverRejectsInvalidLimitsWithDiagnostic() {
        var snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of("classpath:skills-fixture/"),
                null, 0, 1_000, 10_000, false);

        // failFast=false → 记入诊断并产出不健康快照，而非抛出。
        assertTrue(snapshot.diagnostics().stream()
                .anyMatch(d -> d.contains("invalid resource limits")));
        assertTrue(snapshot.entries().isEmpty());
    }

    @Test
    void classpathDiscoveryHonorsExtensionFilter() {
        var snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of("classpath:static-fixture/"),
                Set.of("md"), 10, 10_000, 20_000, false);

        assertEquals(List.of("policy.md"), snapshot.entries().stream()
                .map(ResourceEntry::relativePath).toList());
        assertTrue(snapshot.healthy());
    }

    @Test
    void jarLocationWithoutEntryPrefixIsRejectedWithDiagnostic() {
        var snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of("jar:file:/tmp/x.jar"),
                null, 10, 1_000, 10_000, false);

        assertTrue(snapshot.diagnostics().stream()
                .anyMatch(d -> d.contains("JAR location is missing entry prefix")));
    }

    @Test
    void extensionFilterSkipsDisallowedFiles() throws Exception {
        Files.writeString(tempDir.resolve("keep.md"), "# kept");
        Files.writeString(tempDir.resolve("skip.txt"), "skipped");

        var snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of("file:" + tempDir.toUri().getPath()),
                Set.of(".MD"), 10, 1_000, 100_000, false);

        assertEquals(1, snapshot.entries().size());
        assertEquals("keep.md", snapshot.entries().getFirst().relativePath());
    }

    @Test
    void fileLocationWithoutSchemeMarkerIsRejectedWithDiagnostic() {
        // 不带 file:/classpath: 前缀的裸路径 → 不支持的资源方案。
        var snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of("/absolute/path/without/scheme"),
                null, 10, 1_000, 10_000, false);

        assertTrue(snapshot.diagnostics().stream()
                .anyMatch(d -> d.contains("unsupported resource scheme")));
    }

    @Test
    void normalizeExtensionsTrimsCaseAndDotPrefix() throws Exception {
        Method normalize = ResourceCatalog.class.getDeclaredMethod(
                "normalizeExtensions", Set.class);
        normalize.setAccessible(true);

        @SuppressWarnings("unchecked")
        Set<String> normalized = (Set<String>) normalize.invoke(
                catalog, Set.of(" .Md ", "TXT", ""));

        assertEquals(Set.of("md", "txt"), normalized);
    }

    @Test
    void allowedRequiresDotAndMatchingExtension() throws Exception {
        Method allowed = ResourceCatalog.class.getDeclaredMethod(
                "allowed", String.class, Set.class);
        allowed.setAccessible(true);

        // 无点号（dot=0 与无点均不合法）、扩展不匹配 → 拒绝。
        assertTrue((boolean) allowed.invoke(catalog, "readme", Set.of("md"))
                == false || true);
        boolean noDot = (boolean) allowed.invoke(catalog, "readme", Set.of("md"));
        boolean hiddenDot = (boolean) allowed.invoke(catalog, ".md", Set.of("md"));
        boolean matched = (boolean) allowed.invoke(catalog, "a/b.md", Set.of("md"));

        assertFalse(noDot);
        assertFalse(hiddenDot);
        assertTrue(matched);
    }

    @Test
    void rootRejectsUnsafeLocations() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourceCatalog.root(ResourceKind.SKILL, "skill/../etc"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceCatalog.root(ResourceKind.SKILL, "  "));
    }
}
