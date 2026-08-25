package com.springairag.core.resource;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceCatalogTest {

    private final ResourceCatalog catalog = new ResourceCatalog();

    @Test
    void discoversExplodedClasspathResourcesWithStableRelativePaths() {
        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of("classpath:static-fixture/"),
                Set.of("md", "txt"),
                10,
                10_000,
                20_000,
                true);

        assertTrue(snapshot.healthy());
        assertEquals(List.of("policy.md", "readme.txt"),
                snapshot.entries().stream()
                        .map(ResourceEntry::relativePath)
                        .toList());
        assertTrue(snapshot.entries().stream()
                .allMatch(entry -> entry.root().sourceLabel()
                        .matches("static-knowledge/[0-9a-f]{16}")));
    }

    @Test
    void discoversFilesystemResourcesAndRejectsUnsafeConfiguredLocation() throws IOException {
        Path root = Files.createTempDirectory("resource-catalog-");
        Files.createDirectories(root.resolve("nested"));
        Files.writeString(root.resolve("nested/policy.md"), "保修期为两年。",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("ignored.bin"), "ignored",
                StandardCharsets.UTF_8);

        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of(root.toUri().toString()),
                Set.of("md"),
                10,
                10_000,
                20_000,
                true);

        assertEquals(List.of("nested/policy.md"),
                snapshot.entries().stream()
                        .map(ResourceEntry::relativePath)
                        .toList());
        assertFalse(snapshot.entries().getFirst().root().sourceLabel()
                .contains(root.toString()));
        assertThrows(IllegalArgumentException.class, () -> catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of(root.toUri() + "../outside"),
                Set.of("md"),
                10,
                10_000,
                20_000,
                true));
    }

    @Test
    void discoversResourcesFromOrdinaryJar() throws IOException {
        Path jar = Files.createTempFile("resource-catalog-", ".jar");
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream jarOutput = new JarOutputStream(output)) {
            add(jarOutput, "knowledge/terms.md", "型号 X-200 的保修期为一年。");
            add(jarOutput, "knowledge/ignored.bin", "ignored");
        }

        ResourceSnapshot snapshot = catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of("jar:" + jar.toUri() + "!/knowledge/"),
                Set.of("md"),
                10,
                10_000,
                20_000,
                true);

        assertEquals(List.of("terms.md"),
                snapshot.entries().stream()
                        .map(ResourceEntry::relativePath)
                        .toList());
        assertEquals("型号 X-200 的保修期为一年。",
                new String(snapshot.entries().getFirst().content(),
                        StandardCharsets.UTF_8));
    }

    @Test
    void enforcesFileAndTotalByteLimits() throws IOException {
        Path root = Files.createTempDirectory("resource-catalog-limit-");
        Files.writeString(root.resolve("a.md"), "12345", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("b.md"), "67890", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of(root.toUri().toString()),
                Set.of("md"),
                10,
                4,
                100,
                true));
        assertThrows(IllegalStateException.class, () -> catalog.discover(
                ResourceKind.STATIC_KNOWLEDGE,
                List.of(root.toUri().toString()),
                Set.of("md"),
                10,
                10,
                9,
                true));
    }

    @Test
    void classpathAllKeepsSameNamedFilesFromDifferentPhysicalRoots()
            throws IOException {
        Path firstJar = Files.createTempFile("resource-catalog-first-", ".jar");
        Path secondJar = Files.createTempFile("resource-catalog-second-", ".jar");
        writeJar(firstJar, "first policy");
        writeJar(secondJar, "second policy");

        try (URLClassLoader classLoader = new URLClassLoader(
                new java.net.URL[] {
                        firstJar.toUri().toURL(),
                        secondJar.toUri().toURL()},
                null)) {
            ResourceCatalog isolatedCatalog = new ResourceCatalog(
                    new PathMatchingResourcePatternResolver(classLoader));

            ResourceSnapshot snapshot = isolatedCatalog.discover(
                    ResourceKind.STATIC_KNOWLEDGE,
                    List.of("classpath*:knowledge/"),
                    Set.of("md"),
                    10,
                    10_000,
                    20_000,
                    true);

            assertEquals(2, snapshot.entries().size());
            assertEquals(2, snapshot.entries().stream()
                    .map(entry -> entry.root().rootKey())
                    .distinct()
                    .count());
            assertEquals(
                    Set.of("first policy", "second policy"),
                    snapshot.entries().stream()
                            .map(entry -> new String(
                                    entry.content(), StandardCharsets.UTF_8))
                            .collect(java.util.stream.Collectors.toSet()));
        }
    }

    private void writeJar(Path jar, String content) throws IOException {
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream jarOutput = new JarOutputStream(output)) {
            add(jarOutput, "knowledge/", "");
            add(jarOutput, "knowledge/shared.md", content);
        }
    }

    private void add(
            JarOutputStream output,
            String name,
            String content) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(content.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }
}
