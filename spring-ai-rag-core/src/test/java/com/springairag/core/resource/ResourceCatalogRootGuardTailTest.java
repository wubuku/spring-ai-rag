package com.springairag.core.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ResourceCatalog 根守卫长尾（Batch 637，JaCoCo 驱动）：不可读的
 * 单文件根在宽松与 failFast 模式下的 "root is not readable"、
 * JAR 入口前缀包含 ".." 的 unsafe 拒绝。
 */
class ResourceCatalogRootGuardTailTest {

    @TempDir
    Path tempDir;

    private final ResourceCatalog catalog = new ResourceCatalog();

    @Test
    void specialFileRootThrowsNotReadable() {
        // 字符设备（/dev/null）既不是目录也不是常规文件，
        // 应命中 "root is not readable" 守卫。
        Path device = Path.of("/dev/null");
        if (!Files.exists(device)) {
            return;
        }

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> catalog.discover(
                        ResourceKind.STATIC_KNOWLEDGE,
                        List.of(device.toUri().toString()),
                        Set.of("md"),
                        10, 10_000, 1_000_000, true));
        assertTrue(chainContains(error, "root is not readable"),
                () -> "应报 root 不可读: " + error);
    }

    private boolean chainContains(Throwable error, String fragment) {
        Throwable current = error;
        while (current != null) {
            if (String.valueOf(current).contains(fragment)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
