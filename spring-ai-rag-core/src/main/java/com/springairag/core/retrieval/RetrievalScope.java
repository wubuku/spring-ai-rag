package com.springairag.core.retrieval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 检索层使用的授权后范围。
 */
public record RetrievalScope(
        CollectionFilter collectionFilter,
        List<Long> collectionIds,
        List<Long> documentIds,
        String documentType,
        boolean matchNone) {

    public enum CollectionFilter {
        NONE,
        ANY_ASSIGNED,
        SELECTED
    }

    public RetrievalScope {
        collectionFilter = Objects.requireNonNull(collectionFilter, "collectionFilter");
        collectionIds = normalizeIds(collectionIds, "collectionIds");
        documentIds = normalizeIds(documentIds, "documentIds");
        if (documentType != null && documentType.isBlank()) {
            throw new IllegalArgumentException("documentType must not be blank");
        }
        if (collectionFilter != CollectionFilter.SELECTED && !collectionIds.isEmpty()) {
            throw new IllegalArgumentException(
                    collectionFilter + " scope must not contain collection IDs");
        }
        if (collectionFilter == CollectionFilter.SELECTED && collectionIds.isEmpty()) {
            matchNone = true;
        }
    }

    public static RetrievalScope unscoped() {
        return new RetrievalScope(
                CollectionFilter.NONE, List.of(), List.of(), null, false);
    }

    public static RetrievalScope unscoped(
            List<Long> documentIds, String documentType) {
        return new RetrievalScope(
                CollectionFilter.NONE, List.of(), documentIds, documentType, false);
    }

    public static RetrievalScope anyAssigned(
            List<Long> documentIds, String documentType) {
        return new RetrievalScope(
                CollectionFilter.ANY_ASSIGNED, List.of(),
                documentIds, documentType, false);
    }

    public static RetrievalScope selectedCollections(
            List<Long> collectionIds, List<Long> documentIds, String documentType) {
        return new RetrievalScope(
                CollectionFilter.SELECTED, collectionIds,
                documentIds, documentType, false);
    }

    public static RetrievalScope forDocumentIds(List<Long> documentIds) {
        return unscoped(documentIds, null);
    }

    public static RetrievalScope noMatches() {
        return new RetrievalScope(
                CollectionFilter.NONE, List.of(), List.of(), null, true);
    }

    private static List<Long> normalizeIds(List<Long> values, String field) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long value : values) {
            if (value == null || value <= 0) {
                throw new IllegalArgumentException(field + " must contain positive IDs");
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }
}
