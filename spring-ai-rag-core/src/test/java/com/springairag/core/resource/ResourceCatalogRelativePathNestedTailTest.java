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
 * ResourceCatalog 相对路径与 spring 根身份长尾（Batch 580，JaCoCo
 * 驱动）：relativePath 的 "!/" JAR 嵌套标记分支、空根 + null 文件
 * 名回退 null、configuredRootPath 的 JAR "!/" 剥离、springResource
 * Root 对非 SKILL 类型使用 static-knowledge/ 前缀。
 */
class ResourceCatalogRelativePathNestedTailTest {

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
    void relativePathUsesExclamationMarkerForNestedJarEntries()
            throws Exception {
        Path file = tempDir.resolve("inner.md");
        Files.write(file, "x".getBytes(StandardCharsets.UTF_8));

        // jar URI 形如 file:/.../app.jar!/root/inner.md —— "!/root/"
        // 标记分支命中（"/root/" 也会命中，故用位置更靠后的 "!/"）。
        Resource resource = new UrlResource(file.toUri()) {
            @Override
            public java.net.URI getURI() {
                return java.net.URI.create("jar:file:/app.jar!/root/inner.md");
            }

            @Override
            public String getFilename() {
                return "inner.md";
            }
        };

        String relative = (String) invoke("relativePath",
                new Class<?>[]{String.class, Resource.class},
                "jar:file:/app.jar!/root/", resource);

        assertEquals("inner.md", relative);
    }

    @Test
    void relativePathWithBlankRootAndNullFilenameReturnsNull()
            throws Exception {
        Resource withoutName = mock(Resource.class);
        when(withoutName.getFilename()).thenReturn(null);

        String relative = (String) invoke("relativePath",
                new Class<?>[]{String.class, Resource.class},
                "classpath*:", withoutName);

        assertEquals(null, relative);
    }

    @Test
    void configuredRootPathStripsNestedJarMarker() throws Exception {
        String root = (String) invoke("configuredRootPath",
                new Class<?>[]{String.class},
                "jar:file:/app.jar!/nested/root/");

        assertEquals("nested/root", root);
    }

    @Test
    void springResourceRootUsesSkillPrefixForSkillKind() throws Exception {
        ResourceRoot configured = ResourceCatalog.root(
                ResourceKind.SKILL, "classpath*:skills/");
        Path dir = tempDir.resolve("container").resolve("skills");
        Files.createDirectories(dir);
        Path file = dir.resolve("same.md");
        Files.write(file, "same".getBytes(StandardCharsets.UTF_8));

        ResourceRoot root = (ResourceRoot) invoke("springResourceRoot",
                new Class<?>[]{ResourceRoot.class, String.class,
                        Resource.class, String.class},
                configured, "classpath*:skills/", urlResource(file),
                "same.md");

        assertTrue(root.rootKey().startsWith("skill/"));
    }

    @Test
    void springResourceRootWrapsIdentityFailures() throws Exception {
        ResourceRoot configured = ResourceCatalog.root(
                ResourceKind.STATIC_KNOWLEDGE, "classpath*:docs/");
        Resource broken = mock(Resource.class);
        when(broken.getURI()).thenThrow(new java.io.IOException("no uri"));

        // 反射调用抛 InvocationTargetException，cause 为包装后的
        // IllegalStateException（内嵌 ResourceCatalogException）。
        var error = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> invoke("springResourceRoot",
                        new Class<?>[]{ResourceRoot.class, String.class,
                                Resource.class, String.class},
                        configured, "classpath*:docs/", broken, "same.md"));
        assertTrue(error.getCause().getMessage()
                .contains("classpath resource identity failed"));
    }
}
