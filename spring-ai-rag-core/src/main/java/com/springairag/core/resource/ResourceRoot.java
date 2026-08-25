package com.springairag.core.resource;

/**
 * Stable, non-sensitive identity for one configured resource location.
 */
public record ResourceRoot(
        ResourceKind kind,
        String rootKey,
        String sourceLabel) {
}
