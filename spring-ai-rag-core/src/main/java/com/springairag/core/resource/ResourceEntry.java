package com.springairag.core.resource;

import java.util.Arrays;

/**
 * Bounded immutable resource content loaded during snapshot construction.
 */
public record ResourceEntry(
        ResourceRoot root,
        String relativePath,
        byte[] content) {

    public ResourceEntry {
        if (root == null || relativePath == null || relativePath.isBlank()
                || content == null) {
            throw new IllegalArgumentException("Invalid resource entry");
        }
        content = Arrays.copyOf(content, content.length);
    }

    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }
}
