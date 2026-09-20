package com.springairag.core.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ResourceCatalog spring 资源路径长尾（Batch 551，JaCoCo 驱动）：
 * relativePath 的根标记/!/ 标记/文件名回退与 URI 异常回退、
 * springResourceRoot 的 classpath* 容器指纹与直通、readBounded 的
 * 超限拒绝与未知长度流读取。
 */
class ResourceCatalogSpringPathTailTest {

    @TempDir
    Path tempDir;

    private final ResourceCatalog catalog = new ResourceCatalog();

    private Object invoke(String name, Class<?>[] params, Object... args)
            throws Exception {
        var method = ResourceCatalog.class.getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method.invoke(catalog, args);
    }

    private Resource urlResource(Path file) throws Exception {
        return new UrlResource(file.toUri());
    }

    @Test
    void relativePathStripsConfiguredRootMarker() throws Exception {
        Path docs = tempDir.resolve("docs").resolve("notes");
        Files.createDirectories(docs);
        Path file = docs.resolve("guide.md");
        Files.write(file, "body".getBytes(StandardCharsets.UTF_8));

        String relative = (String) invoke("relativePath",
                new Class<?>[]{String.class, Resource.class},
                "classpath*:docs/", urlResource(file));

        assertEquals("notes/guide.md", relative);
    }

    @Test
    void relativePathHandlesJarMarkerAndFilenameFallback() throws Exception {
        Path file = tempDir.resolve("inner.md");
        Files.write(file, "body".getBytes(StandardCharsets.UTF_8));

        // !/root/ 标记分支（JAR 内条目）：file 资源 URI 与标记不匹配
        // → 文件名回退分支。
        String jarRelative = (String) invoke("relativePath",
                new Class<?>[]{String.class, Resource.class},
                "jar:file:/app.jar!/root/", urlResource(file));
        // file 资源 URI 与标记不匹配 → 文件名回退。
        assertEquals("inner.md", jarRelative);
    }

    @Test
    void relativePathFallsBackToFilenamWhenUriThrows() throws Exception {
        Resource broken = mock(Resource.class);
        when(broken.getURI()).thenThrow(new java.io.IOException("no uri"));
        when(broken.getFilename()).thenReturn("fallback.md");

        String relative = (String) invoke("relativePath",
                new Class<?>[]{String.class, Resource.class},
                "classpath*:anything/", broken);

        assertEquals("fallback.md", relative);
    }

    @Test
    void springResourceRootPassesThroughForNonClasspathStar()
            throws Exception {
        ResourceRoot configured = ResourceCatalog.root(
                ResourceKind.STATIC_KNOWLEDGE, "classpath:docs/");
        Path file = tempDir.resolve("x.md");
        Files.write(file, "x".getBytes(StandardCharsets.UTF_8));

        ResourceRoot result = (ResourceRoot) invoke("springResourceRoot",
                new Class<?>[]{ResourceRoot.class, String.class,
                        Resource.class, String.class},
                configured, "classpath:docs/", urlResource(file), "x.md");

        assertEquals(configured.rootKey(), result.rootKey());
    }

    @Test
    void springResourceRootComputesContainerScopedIdentity()
            throws Exception {
        ResourceRoot configured = ResourceCatalog.root(
                ResourceKind.STATIC_KNOWLEDGE, "classpath*:docs/");
        Path dirA = tempDir.resolve("containerA").resolve("docs");
        Files.createDirectories(dirA);
        Path fileA = dirA.resolve("same.md");
        Files.write(fileA, "same".getBytes(StandardCharsets.UTF_8));
        Path dirB = tempDir.resolve("containerB").resolve("docs");
        Files.createDirectories(dirB);
        Path fileB = dirB.resolve("same.md");
        Files.write(fileB, "same".getBytes(StandardCharsets.UTF_8));

        ResourceRoot rootA = (ResourceRoot) invoke("springResourceRoot",
                new Class<?>[]{ResourceRoot.class, String.class,
                        Resource.class, String.class},
                configured, "classpath*:docs/", urlResource(fileA),
                "same.md");
        ResourceRoot rootB = (ResourceRoot) invoke("springResourceRoot",
                new Class<?>[]{ResourceRoot.class, String.class,
                        Resource.class, String.class},
                configured, "classpath*:docs/", urlResource(fileB),
                "same.md");

        // 相对名相同但容器不同 → 根身份必须区分。
        assertTrue(!rootA.rootKey().equals(rootB.rootKey()));
    }

    @Test
    void readBoundedRejectsOversizedResourceByContentLength()
            throws Exception {
        Path big = tempDir.resolve("big.md");
        Files.write(big, "x".repeat(500).getBytes(StandardCharsets.UTF_8));

        var method = ResourceCatalog.class.getDeclaredMethod(
                "readBounded", Resource.class, int.class, int.class);
        method.setAccessible(true);

        assertThrows(Exception.class,
                () -> method.invoke(catalog, urlResource(big), 100, 10_000));
    }

    @Test
    void readBoundedStreamsWhenContentLengthUnknown() throws Exception {
        Path small = tempDir.resolve("small.md");
        Files.write(small, "abc".getBytes(StandardCharsets.UTF_8));
        Resource resource = new UrlResource(small.toUri()) {
            @Override
            public long contentLength() {
                return -1;
            }
        };

        var method = ResourceCatalog.class.getDeclaredMethod(
                "readBounded", Resource.class, int.class, int.class);
        method.setAccessible(true);

        byte[] bytes = (byte[]) method.invoke(catalog, resource, 100, 10_000);
        assertEquals(3, bytes.length);
    }
}
