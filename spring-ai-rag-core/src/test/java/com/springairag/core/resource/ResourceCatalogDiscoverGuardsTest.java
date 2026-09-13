package com.springairag.core.resource;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * discover() 入口守卫（Batch 362）：全空位置空快照、kind 非空
 * 校验、列表内空白位置跳过、扩展名规范化（空白/点前缀/大小写）、
 * 空扩展名放行全部、总字节上限的诊断与 failFast 双路径、无效资源
 * 限制诊断。
 */
class ResourceCatalogDiscoverGuardsTest {

    private Resource fakeResource(String uri, String filename,
                                  String content, boolean readable) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new Resource() {
            @Override public boolean exists() { return true; }
            @Override public boolean isReadable() { return readable; }
            @Override public boolean isOpen() { return false; }
            @Override public boolean isFile() { return false; }
            @Override public java.net.URL getURL() { return null; }
            @Override public java.net.URI getURI() {
                return java.net.URI.create(uri);
            }
            @Override public File getFile() { return null; }
            @Override public ReadableByteChannel readableChannel() {
                return null;
            }
            @Override public long contentLength() { return bytes.length; }
            @Override public long lastModified() { return 0L; }
            @Override public Resource createRelative(String relative) {
                return null;
            }
            @Override public String getFilename() { return filename; }
            @Override public String getDescription() { return uri; }
            @Override public InputStream getInputStream() {
                if (!readable) {
                    throw new IllegalStateException("not readable");
                }
                return new java.io.ByteArrayInputStream(bytes);
            }
        };
    }

    private ResourceCatalog catalogReturning(Resource[] resources)
            throws IOException {
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        org.mockito.Mockito.doReturn(resources).when(resolver)
                .getResources(anyString());
        return new ResourceCatalog(resolver);
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
    void allBlankLocationsReturnEmptySnapshot() throws IOException {
        ResourceCatalog catalog = catalogReturning(new Resource[0]);

        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                Arrays.asList("  ", "", null),
                Set.of("md"), 10, 10_000, 20_000, true);

        assertNotNull(snapshot);
        assertTrue(snapshot.entries().isEmpty());
        assertTrue(snapshot.healthy());
    }

    @Test
    void nullKindWithNonBlankLocationsThrows() throws IOException {
        ResourceCatalog catalog = catalogReturning(new Resource[0]);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> catalog.discover(
                        null,
                        List.of("classpath*:knowledge/"),
                        Set.of("md"), 10, 10_000, 20_000, true));
        assertEquals("Resource kind must not be null", error.getMessage());
    }

    @Test
    void blankLocationWithinListIsSkippedAndExtensionsNormalized()
            throws IOException {
        Resource[] resources = {
                fakeResource("file:/kb/a.md", "a.md", "alpha", true),
                fakeResource("file:/kb/b.txt", "b.txt", "beta", true)};
        ResourceCatalog catalog = catalogReturning(resources);

        // 空白/ null 元素被过滤、点前缀与大写归一；".txt" 不在集合 → 跳过。
        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                Arrays.asList("   ", "classpath*:knowledge/", null),
                new HashSet<>(Arrays.asList(" .MD ", null, "", "md")),
                10, 10_000, 20_000, true);

        assertEquals(1, snapshot.entries().size());
        assertEquals("a.md", snapshot.entries().getFirst().relativePath());
        assertTrue(snapshot.diagnostics().isEmpty());
    }

    @Test
    void nullExtensionsAllowAllResources() throws IOException {
        Resource[] resources = {
                fakeResource("file:/kb/a.md", "a.md", "alpha", true),
                fakeResource("file:/kb/b.txt", "b.txt", "beta", true)};
        ResourceCatalog catalog = catalogReturning(resources);

        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of("classpath*:knowledge/"),
                null, 10, 10_000, 20_000, true);

        assertEquals(2, snapshot.entries().size());
    }

    @Test
    void totalBytesAtExactLimitStaysHealthy() throws IOException {
        // 读取层按剩余预算封顶：恰好触顶（10/10）不报诊断，
        // 外层 totalBytes > maxTotalBytes 仅作防御性兜底。
        Resource[] resources = {
                fakeResource("file:/kb/a.md", "a.md", "abcdef", true),
                fakeResource("file:/kb/b.md", "b.md", "ghij", true)};
        ResourceCatalog catalog = catalogReturning(resources);

        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of("classpath*:knowledge/"),
                Set.of("md"), 10, 10_000, 10, true);

        assertTrue(snapshot.healthy());
        assertEquals(2, snapshot.entries().size());
    }

    @Test
    void secondRootBeyondBudgetIsRejectedDiagnostically() throws IOException {
        // 多根累积：第一根耗尽预算后，第二根所有文件超剩余预算被拒
        // 绝，以诊断形式记录（failFast=false 不中断）。
        Resource[] resources = {
                fakeResource("file:/kb/a.md", "a.md", "abcdef", true),
                fakeResource("file:/kb/b.md", "b.md", "ghij", true)};
        ResourceCatalog catalog = catalogReturning(resources);

        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of("classpath*:knowledge/", "classpath*:other/"),
                Set.of("md"), 10, 10_000, 10, false);

        assertFalse(snapshot.healthy());
        assertTrue(snapshot.diagnostics().stream()
                .allMatch(line -> line.contains("file byte limit exceeded")));
    }

    @Test
    void invalidLimitsProduceDiagnosticWithoutFailFast() throws IOException {
        ResourceCatalog catalog = catalogReturning(new Resource[0]);

        // maxFilesPerRoot=0 在 discoverOne 入口校验失败 → 诊断而非中断。
        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of("classpath*:knowledge/"),
                Set.of("md"), 0, 10_000, 20_000, false);

        assertFalse(snapshot.healthy());
        assertTrue(snapshot.diagnostics().getFirst()
                .contains("invalid resource limits"));
    }
}
