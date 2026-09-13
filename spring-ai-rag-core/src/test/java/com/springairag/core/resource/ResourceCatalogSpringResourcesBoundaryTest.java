package com.springairag.core.resource;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * discoverSpringResources 边界（Batch 355）：maxFiles 超限、不可
 * 读资源跳过、重复身份去重、读取失败的异常包装。
 */
class ResourceCatalogSpringResourcesBoundaryTest {

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
            @Override public InputStream getInputStream() throws IOException {
                if (!readable) {
                    throw new IllegalStateException("not readable");
                }
                return new java.io.ByteArrayInputStream(bytes);
            }
        };
    }

    private ResourcePatternResolver resolverReturning(Resource[] resources)
            throws IOException {
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        org.mockito.Mockito.doReturn(resources).when(resolver).getResources(anyString());
        return resolver;
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
    void maxFilesExceededAbortsSpringDiscovery() throws IOException {
        Resource[] resources = {
                fakeResource("jar:file:/x.jar!/knowledge/a.md", "a.md", "a", true),
                fakeResource("jar:file:/x.jar!/knowledge/b.md", "b.md", "b", true),
                fakeResource("jar:file:/x.jar!/knowledge/c.md", "c.md", "c", true)};
        ResourceCatalog catalog = new ResourceCatalog(
                resolverReturning(resources));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> catalog.discover(
                        ResourceKind.STATIC_KNOWLEDGE,
                        List.of("classpath*:knowledge/"),
                        Set.of("md"),
                        2, 10_000, 20_000, true));

        assertTrue(chainContains(error, "file count limit exceeded"));
    }

    @Test
    void unreadableResourcesAreSkipped() throws IOException {
        Resource[] resources = {
                fakeResource("jar:file:/x.jar!/knowledge/a.md", "a.md", "a", false)};
        ResourceCatalog catalog = new ResourceCatalog(
                resolverReturning(resources));

        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of("classpath*:knowledge/"),
                Set.of("md"),
                10, 10_000, 20_000, true);

        assertTrue(snapshot.healthy());
        assertTrue(snapshot.entries().isEmpty());
    }

    @Test
    void duplicateIdentitiesAreDeduplicated() throws IOException {
        Resource duplicate = fakeResource(
                "jar:file:/x.jar!/knowledge/a.md", "a.md", "first", true);
        Resource[] resources = {duplicate,
                fakeResource("jar:file:/x.jar!/knowledge/a.md", "a.md", "second", true)};
        ResourceCatalog catalog = new ResourceCatalog(
                resolverReturning(resources));

        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of("classpath*:knowledge/"),
                Set.of("md"),
                10, 10_000, 20_000, true);

        // 同一物理资源（相同 URI 身份）只保留首个。
        assertEquals(1, snapshot.entries().size());
        assertEquals("first", new String(
                snapshot.entries().getFirst().content(),
                StandardCharsets.UTF_8));
    }

    @Test
    void readFailureIsWrappedAsCatalogException() throws IOException {
        Resource bad = new Resource() {
            @Override public boolean exists() { return true; }
            @Override public boolean isReadable() { return true; }
            @Override public boolean isOpen() { return false; }
            @Override public boolean isFile() { return false; }
            @Override public java.net.URL getURL() { return null; }
            @Override public java.net.URI getURI() {
                return java.net.URI.create(
                        "jar:file:/x.jar!/knowledge/broken.md");
            }
            @Override public File getFile() { return null; }
            @Override public ReadableByteChannel readableChannel() {
                return null;
            }
            @Override public long contentLength() { return 1; }
            @Override public long lastModified() { return 0; }
            @Override public Resource createRelative(String relative) {
                return null;
            }
            @Override public String getFilename() { return "broken.md"; }
            @Override public String getDescription() { return "broken"; }
            @Override public InputStream getInputStream() throws IOException {
                throw new IOException("disk error");
            }
        };
        ResourceCatalog catalog = new ResourceCatalog(
                resolverReturning(new Resource[]{bad}));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> catalog.discover(
                        ResourceKind.STATIC_KNOWLEDGE,
                        List.of("classpath*:knowledge/"),
                        Set.of("md"),
                        10, 10_000, 20_000, true));

        assertTrue(chainContains(error, "classpath/JAR read failed"));
    }
}
