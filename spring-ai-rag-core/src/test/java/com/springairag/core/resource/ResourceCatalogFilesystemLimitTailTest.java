package com.springairag.core.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ResourceCatalog 发现长尾（Batch 480，JaCoCo 驱动）：单根文件数
 * 上限在宽松模式（整根丢弃 + 记入 diagnostics）与 failFast 模式
 * （异常上抛）下的行为、JAR 文件不可读降级、非法 file: URI 参数
 * 校验、以及空白 location 的静态守卫。
 *
 * <p>注：discover 循环中的「累计字节上限」分支经真实文件读取不可
 * 达（readBounded 按剩余配额先行抛错），属防御性代码，不在本批
 * 覆盖范围。
 */
class ResourceCatalogFilesystemLimitTailTest {

    @TempDir
    Path tempDir;

    private final ResourceCatalog catalog = new ResourceCatalog();

    private void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
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
    void fileCountLimitExceededInLenientModeDropsRootWithDiagnostic()
            throws Exception {
        Path root = tempDir.resolve("knowledge");
        for (int i = 0; i < 3; i++) {
            write(root.resolve("doc-" + i + ".md"), "content-" + i);
        }

        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of(root.toUri().toString()),
                Set.of("md"),
                2, 10_000, 1_000_000, false);

        // discoverOne 在第 3 个文件处抛错 → 整根结果被丢弃，
        // 仅在 diagnostics 中记录 file count limit exceeded。
        assertFalse(snapshot.healthy());
        assertTrue(snapshot.entries().isEmpty());
        assertTrue(snapshot.diagnostics().stream()
                .anyMatch(line -> line.contains("file count limit exceeded")));
    }

    @Test
    void fileCountLimitExceededInFailFastModeThrows() throws Exception {
        Path root = tempDir.resolve("knowledge");
        for (int i = 0; i < 3; i++) {
            write(root.resolve("doc-" + i + ".md"), "content-" + i);
        }

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> catalog.discover(
                        ResourceKind.STATIC_KNOWLEDGE,
                        List.of(root.toUri().toString()),
                        Set.of("md"),
                        2, 10_000, 1_000_000, true));
        assertTrue(chainContains(error, "file count limit exceeded"),
                "应报文件数超限: " + error.getMessage());
    }

    @Test
    void unreadableJarFileDegradesToJarReadFailed() {
        Path missingJar = tempDir.resolve("missing.jar");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> catalog.discover(
                        ResourceKind.STATIC_KNOWLEDGE,
                        List.of("jar:" + missingJar.toUri() + "!/docs"),
                        Set.of("md"),
                        10, 10_000, 1_000_000, true));
        assertTrue(chainContains(error, "JAR read failed"),
                "应报 JAR 读取失败: " + error.getMessage());
    }

    @Test
    void malformedFileUriRejectedAsInvalidLocation() {
        assertThrows(IllegalArgumentException.class,
                () -> catalog.discover(
                        ResourceKind.STATIC_KNOWLEDGE,
                        List.of("file://[bad-uri"),
                        Set.of("md"),
                        10, 10_000, 1_000_000, true));
    }

    @Test
    void blankLocationIsRejectedByStaticRootGuard() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ResourceCatalog.root(
                        ResourceKind.STATIC_KNOWLEDGE, "   "));
        assertTrue(error.getMessage().contains("must not be blank"),
                "应报空白 location: " + error.getMessage());
    }
}
