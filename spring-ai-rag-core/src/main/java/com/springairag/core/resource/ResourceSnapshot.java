package com.springairag.core.resource;

import java.time.Instant;
import java.util.List;

/**
 * Immutable startup snapshot shared by resource consumers.
 */
public record ResourceSnapshot(
        ResourceKind kind,
        long generation,
        Instant loadedAt,
        List<ResourceEntry> entries,
        String digest,
        List<String> diagnostics,
        boolean healthy) {

    public ResourceSnapshot {
        entries = entries == null ? List.of() : List.copyOf(entries);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        loadedAt = loadedAt == null ? Instant.now() : loadedAt;
        digest = digest == null ? "" : digest;
    }

    public static ResourceSnapshot empty(ResourceKind kind) {
        return new ResourceSnapshot(
                kind, 0, Instant.now(), List.of(), "", List.of(), true);
    }
}
