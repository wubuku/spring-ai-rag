package com.springairag.core.resource;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Discovers bounded, read-only resources from configured classpath, filesystem
 * and JAR locations.
 *
 * <p>The catalog never exposes the configured location. It only publishes a
 * short stable root key and a relative path.</p>
 */
@Component
public final class ResourceCatalog {

    private static final int DIGEST_PREFIX_LENGTH = 16;
    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String CLASSPATH_ALL_PREFIX = "classpath*:";
    private static final String FILE_PREFIX = "file:";
    private static final String JAR_PREFIX = "jar:";

    private final ResourcePatternResolver resolver;
    private final AtomicLong generation = new AtomicLong();

    public ResourceCatalog() {
        this(new PathMatchingResourcePatternResolver());
    }

    ResourceCatalog(ResourcePatternResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public ResourceSnapshot discover(
            ResourceKind kind,
            List<String> locations,
            Set<String> extensions,
            int maxFilesPerRoot,
            int maxFileBytes,
            int maxTotalBytes,
            boolean failFast) {
        if (locations == null || locations.stream().allMatch(this::blank)) {
            return ResourceSnapshot.empty(kind);
        }
        if (kind == null) {
            throw new IllegalArgumentException("Resource kind must not be null");
        }
        Set<String> normalizedExtensions = normalizeExtensions(extensions);
        List<ResourceEntry> entries = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        int totalBytes = 0;
        for (String configuredLocation : locations) {
            if (blank(configuredLocation)) {
                continue;
            }
            String location = configuredLocation.trim();
            ResourceRoot root = root(kind, location);
            try {
                List<ResourceEntry> discovered = discoverOne(
                        root,
                        location,
                        normalizedExtensions,
                        maxFilesPerRoot,
                        maxFileBytes,
                        maxTotalBytes - totalBytes);
                entries.addAll(discovered);
                totalBytes += discovered.stream()
                        .mapToInt(entry -> entry.content().length)
                        .sum();
                if (totalBytes > maxTotalBytes) {
                    throw new ResourceCatalogException(
                            root.rootKey(), "total byte limit exceeded");
                }
            } catch (RuntimeException error) {
                String diagnostic = root.sourceLabel() + ": "
                        + boundedMessage(error.getMessage());
                diagnostics.add(diagnostic);
                if (failFast) {
                    throw new IllegalStateException(
                            "Failed to load configured " + kind + " resource root "
                                    + root.sourceLabel(), error);
                }
            }
        }

        entries.sort(Comparator
                .comparing((ResourceEntry entry) -> entry.root().rootKey())
                .thenComparing(ResourceEntry::relativePath));
        List<ResourceEntry> deduplicated = deduplicate(entries);
        return new ResourceSnapshot(
                kind,
                generation.incrementAndGet(),
                java.time.Instant.now(),
                deduplicated,
                digest(deduplicated),
                diagnostics,
                diagnostics.isEmpty());
    }

    public static ResourceRoot root(ResourceKind kind, String location) {
        String normalized = normalizeLocation(location);
        String digest = sha256(kind.name() + ":" + normalized);
        String prefix = kind == ResourceKind.SKILL
                ? "skill/"
                : "static-knowledge/";
        return new ResourceRoot(
                kind,
                prefix + digest.substring(0, DIGEST_PREFIX_LENGTH),
                prefix + digest.substring(0, DIGEST_PREFIX_LENGTH));
    }

    private List<ResourceEntry> discoverOne(
            ResourceRoot root,
            String location,
            Set<String> extensions,
            int maxFiles,
            int maxFileBytes,
            int remainingTotalBytes) {
        if (maxFiles < 1 || maxFileBytes < 1 || remainingTotalBytes < 0) {
            throw new ResourceCatalogException(root.rootKey(), "invalid resource limits");
        }
        if (location.startsWith(FILE_PREFIX)) {
            return discoverFilesystem(
                    root, location, extensions, maxFiles, maxFileBytes,
                    remainingTotalBytes);
        }
        if (location.startsWith(JAR_PREFIX)
                && location.startsWith("jar:file:")) {
            return discoverJarFile(
                    root, location, extensions, maxFiles, maxFileBytes,
                    remainingTotalBytes);
        }
        if (location.startsWith(CLASSPATH_PREFIX)
                || location.startsWith(CLASSPATH_ALL_PREFIX)
                || location.startsWith(JAR_PREFIX)) {
            return discoverSpringResources(
                    root, location, extensions, maxFiles, maxFileBytes,
                    remainingTotalBytes);
        }
        throw new ResourceCatalogException(
                root.rootKey(), "unsupported resource scheme");
    }

    private List<ResourceEntry> discoverFilesystem(
            ResourceRoot root,
            String location,
            Set<String> extensions,
            int maxFiles,
            int maxFileBytes,
            int remainingTotalBytes) {
        try {
            URI uri = URI.create(location);
            Path configured = Paths.get(uri);
            if (!Files.exists(configured)) {
                throw new ResourceCatalogException(root.rootKey(), "root does not exist");
            }
            Path realRoot = configured.toRealPath();
            List<Path> files;
            if (Files.isRegularFile(realRoot)) {
                files = List.of(realRoot);
            } else if (Files.isDirectory(realRoot)) {
                try (var stream = Files.walk(
                        realRoot, Integer.MAX_VALUE, new FileVisitOption[0])) {
                    files = stream
                            .filter(Files::isRegularFile)
                            .sorted()
                            .toList();
                }
            } else {
                throw new ResourceCatalogException(root.rootKey(), "root is not readable");
            }
            List<ResourceEntry> result = new ArrayList<>();
            for (Path file : files) {
                if (Files.isSymbolicLink(file)) {
                    continue;
                }
                Path realFile = file.toRealPath();
                if (!realFile.startsWith(realRoot)) {
                    throw new ResourceCatalogException(
                            root.rootKey(), "resource escapes configured root");
                }
                String relative = Files.isDirectory(realRoot)
                        ? realRoot.relativize(realFile).toString()
                        : realFile.getFileName().toString();
                relative = normalizeRelativePath(relative);
                if (!allowed(relative, extensions)) {
                    continue;
                }
                if (result.size() >= maxFiles) {
                    throw new ResourceCatalogException(
                            root.rootKey(), "file count limit exceeded");
                }
                byte[] bytes = readBounded(
                        realFile, maxFileBytes, remainingTotalBytes);
                result.add(new ResourceEntry(root, relative, bytes));
                remainingTotalBytes -= bytes.length;
            }
            return result;
        } catch (ResourceCatalogException e) {
            throw e;
        } catch (Exception e) {
            throw new ResourceCatalogException(root.rootKey(), "filesystem read failed", e);
        }
    }

    private List<ResourceEntry> discoverJarFile(
            ResourceRoot root,
            String location,
            Set<String> extensions,
            int maxFiles,
            int maxFileBytes,
            int remainingTotalBytes) {
        String nested = location.substring(JAR_PREFIX.length());
        int separator = nested.indexOf("!/");
        if (separator < 0) {
            throw new ResourceCatalogException(
                    root.rootKey(), "JAR location is missing entry prefix");
        }
        String fileLocation = nested.substring(0, separator);
        String prefix = nested.substring(separator + 2)
                .replace('\\', '/');
        prefix = prefix.replaceAll("^/+", "").replaceAll("/+$", "");
        if (prefix.contains("..")) {
            throw new ResourceCatalogException(
                    root.rootKey(), "JAR entry prefix is unsafe");
        }
        try (JarFile jar = new JarFile(Paths.get(
                URI.create(fileLocation)).toFile())) {
            List<ResourceEntry> result = new ArrayList<>();
            String prefixWithSlash = prefix.isBlank() ? "" : prefix + "/";
            int loadedBytes = 0;
            List<JarEntry> jarEntries = jar.stream()
                    .filter(entry -> !entry.isDirectory())
                    .sorted(Comparator.comparing(JarEntry::getName))
                    .toList();
            for (JarEntry entry : jarEntries) {
                String name = entry.getName().replace('\\', '/');
                if (!name.startsWith(prefixWithSlash)) {
                    continue;
                }
                String relative = normalizeRelativePath(
                        name.substring(prefixWithSlash.length()));
                if (!allowed(relative, extensions)) {
                    continue;
                }
                if (result.size() >= maxFiles) {
                    throw new ResourceCatalogException(
                            root.rootKey(), "file count limit exceeded");
                }
                byte[] bytes = readBounded(
                        jar, entry, maxFileBytes,
                        remainingTotalBytes - loadedBytes);
                result.add(new ResourceEntry(root, relative, bytes));
                loadedBytes += bytes.length;
            }
            return result;
        } catch (ResourceCatalogException e) {
            throw e;
        } catch (Exception e) {
            throw new ResourceCatalogException(root.rootKey(), "JAR read failed", e);
        }
    }

    private List<ResourceEntry> discoverSpringResources(
            ResourceRoot root,
            String location,
            Set<String> extensions,
            int maxFiles,
            int maxFileBytes,
            int remainingTotalBytes) {
        String directory = location.endsWith("/") ? location : location + "/";
        String pattern = directory + "**/*";
        try {
            Resource[] resources = resolver.getResources(pattern);
            List<ResourceEntry> result = new ArrayList<>();
            Set<String> identities = new LinkedHashSet<>();
            for (Resource resource : resources) {
                if (!resource.isReadable() || !resource.isFile()
                        && !resource.exists()) {
                    continue;
                }
                String relative = relativePath(location, resource);
                if (relative == null || !allowed(relative, extensions)) {
                    continue;
                }
                ResourceRoot entryRoot = springResourceRoot(
                        root, location, resource, relative);
                String identity = entryRoot.rootKey() + ":" + relative;
                if (!identities.add(identity)) {
                    continue;
                }
                if (result.size() >= maxFiles) {
                    throw new ResourceCatalogException(
                            root.rootKey(), "file count limit exceeded");
                }
                byte[] bytes = readBounded(
                        resource, maxFileBytes, remainingTotalBytes);
                result.add(new ResourceEntry(entryRoot, relative, bytes));
                remainingTotalBytes -= bytes.length;
            }
            return result;
        } catch (ResourceCatalogException e) {
            throw e;
        } catch (Exception e) {
            throw new ResourceCatalogException(root.rootKey(), "classpath/JAR read failed", e);
        }
    }

    private byte[] readBounded(
            Path file,
            int maxFileBytes,
            int remainingTotalBytes) throws IOException {
        long size = Files.size(file);
        if (size > maxFileBytes || size > remainingTotalBytes) {
            throw new ResourceCatalogException(
                    "resource", "file byte limit exceeded");
        }
        try (InputStream input = Files.newInputStream(file)) {
            return readBounded(input, maxFileBytes, remainingTotalBytes);
        }
    }

    private byte[] readBounded(
            Resource resource,
        int maxFileBytes,
            int remainingTotalBytes) throws IOException {
        long contentLength = resource.contentLength();
        if (contentLength >= 0
                && (contentLength > maxFileBytes
                || contentLength > remainingTotalBytes)) {
            throw new ResourceCatalogException(
                    "resource", "file byte limit exceeded");
        }
        try (InputStream input = resource.getInputStream()) {
            return readBounded(input, maxFileBytes, remainingTotalBytes);
        }
    }

    private byte[] readBounded(
            JarFile jar,
            JarEntry entry,
            int maxFileBytes,
            int remainingTotalBytes) throws IOException {
        long size = entry.getSize();
        if (size >= 0
                && (size > maxFileBytes || size > remainingTotalBytes)) {
            throw new ResourceCatalogException(
                    "resource", "file byte limit exceeded");
        }
        try (InputStream input = jar.getInputStream(entry)) {
            return readBounded(input, maxFileBytes, remainingTotalBytes);
        }
    }

    private byte[] readBounded(
            InputStream input,
            int maxFileBytes,
            int remainingTotalBytes) throws IOException {
        if (maxFileBytes < 0 || remainingTotalBytes < 0) {
            throw new ResourceCatalogException(
                    "resource", "file byte limit exceeded");
        }
        int limit = Math.min(maxFileBytes, remainingTotalBytes);
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                Math.min(limit, 8_192));
        byte[] buffer = new byte[8_192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) {
                continue;
            }
            if (read > limit - total) {
                throw new ResourceCatalogException(
                        "resource", "file byte limit exceeded");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private String relativePath(String location, Resource resource) {
        String resourceName = resource.getFilename();
        try {
            String external = resource.getURI().toASCIIString()
                    .replace('\\', '/');
            String rootPart = configuredRootPath(location);
            if (rootPart.isBlank()) {
                return resourceName == null
                        ? null
                        : normalizeRelativePath(resourceName);
            }
            String marker = "/" + rootPart + "/";
            int index = external.lastIndexOf(marker);
            if (index >= 0) {
                return normalizeRelativePath(
                        external.substring(index + marker.length()));
            }
            marker = "!/" + rootPart + "/";
            index = external.lastIndexOf(marker);
            if (index >= 0) {
                return normalizeRelativePath(
                        external.substring(index + marker.length()));
            }
        } catch (Exception ignored) {
            // The filename fallback is still bounded and cannot expose the source.
        }
        return resourceName == null ? null : normalizeRelativePath(resourceName);
    }

    private String configuredRootPath(String location) {
        String value;
        if (location.startsWith(CLASSPATH_ALL_PREFIX)) {
            value = location.substring(CLASSPATH_ALL_PREFIX.length());
        } else if (location.startsWith(CLASSPATH_PREFIX)) {
            value = location.substring(CLASSPATH_PREFIX.length());
        } else if (location.startsWith(JAR_PREFIX)) {
            value = location.substring(JAR_PREFIX.length());
            int separator = value.lastIndexOf("!/");
            value = separator >= 0
                    ? value.substring(separator + 2)
                    : value;
        } else {
            value = location;
        }
        value = value.replace('\\', '/');
        int separator = value.lastIndexOf("!/");
        if (separator >= 0) {
            value = value.substring(separator + 2);
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private ResourceRoot springResourceRoot(
            ResourceRoot configuredRoot,
            String location,
            Resource resource,
            String relativePath) {
        if (!location.startsWith(CLASSPATH_ALL_PREFIX)) {
            return configuredRoot;
        }
        try {
            String external = resource.getURI().toASCIIString()
                    .replace('\\', '/');
            String normalizedRelative = "/" + relativePath;
            int relativeIndex = external.lastIndexOf(normalizedRelative);
            String container = relativeIndex >= 0
                    ? external.substring(0, relativeIndex)
                    : external;
            String prefix = configuredRoot.kind() == ResourceKind.SKILL
                    ? "skill/"
                    : "static-knowledge/";
            String digest = sha256(
                    configuredRoot.kind().name() + ":"
                            + normalizeLocation(location) + ":" + container);
            return new ResourceRoot(
                    configuredRoot.kind(),
                    prefix + digest.substring(0, DIGEST_PREFIX_LENGTH),
                    prefix + digest.substring(0, DIGEST_PREFIX_LENGTH));
        } catch (Exception e) {
            throw new ResourceCatalogException(
                    configuredRoot.rootKey(),
                    "classpath resource identity failed",
                    e);
        }
    }

    private List<ResourceEntry> deduplicate(List<ResourceEntry> entries) {
        List<ResourceEntry> result = new ArrayList<>();
        Set<String> identities = new LinkedHashSet<>();
        for (ResourceEntry entry : entries) {
            String identity = entry.root().rootKey() + ":" + entry.relativePath();
            if (identities.add(identity)) {
                result.add(entry);
            }
        }
        return List.copyOf(result);
    }

    private Set<String> normalizeExtensions(Set<String> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return Set.of();
        }
        return extensions.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .map(value -> value.startsWith(".") ? value.substring(1) : value)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private boolean allowed(String relative, Set<String> extensions) {
        if (extensions.isEmpty()) {
            return true;
        }
        int dot = relative.lastIndexOf('.');
        return dot > 0 && extensions.contains(
                relative.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalizeLocation(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Resource location must not be blank");
        }
        String value = location.trim().replace('\\', '/');
        if (value.indexOf('\0') >= 0 || value.contains("..")) {
            throw new IllegalArgumentException("Resource location is unsafe");
        }
        if (value.startsWith(FILE_PREFIX)) {
            try {
                return Paths.get(URI.create(value)).toAbsolutePath().normalize().toString();
            } catch (Exception e) {
                throw new IllegalArgumentException("Resource file location is invalid", e);
            }
        }
        return value.replaceAll("/+$", "") + "/";
    }

    private static String normalizeRelativePath(String value) {
        String normalized = value == null ? "" : value.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank() || normalized.contains("\0")
                || normalized.contains("../") || normalized.equals("..")
                || normalized.contains("/..")) {
            throw new IllegalArgumentException("Resource relative path is unsafe");
        }
        return normalized;
    }

    private static String digest(List<ResourceEntry> entries) {
        MessageDigest digest = messageDigest();
        for (ResourceEntry entry : entries) {
            digest.update(entry.root().rootKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.relativePath().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.content());
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(String value) {
        MessageDigest digest = messageDigest();
        return HexFormat.of().formatHex(
                digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest messageDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String boundedMessage(String message) {
        if (message == null || message.isBlank()) {
            return "resource load failed";
        }
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    private static final class ResourceCatalogException extends RuntimeException {
        private ResourceCatalogException(String rootKey, String message) {
            super(rootKey + ": " + message);
        }

        private ResourceCatalogException(
                String rootKey, String message, Throwable cause) {
            super(rootKey + ": " + message, cause);
        }
    }
}
