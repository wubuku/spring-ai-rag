package com.springairag.core.service;

import com.springairag.core.repository.RagDocumentRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Resolves collection IDs (and optional document ID filters) into document IDs
 * for retrieval scoping. Shared by Search and Chat paths.
 *
 * <p>Isolation semantics: when the caller <em>intends</em> a collection filter
 * ({@code collectionIds} non-null and non-empty) and the resolution yields an
 * empty list, the result is an empty list — callers must <strong>not</strong>
 * treat empty as "search all documents".
 */
@Component
public class CollectionDocumentResolver {

    private final RagDocumentRepository documentRepository;

    public CollectionDocumentResolver(RagDocumentRepository documentRepository) {
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository");
    }

    /**
     * Resolve document IDs for retrieval.
     *
     * @param documentIds   optional explicit document IDs (may be null)
     * @param collectionIds optional collection IDs to expand (may be null)
     * @return null if no filter should apply; otherwise a (possibly empty) list of document IDs
     */
    public List<Long> resolveDocumentIds(List<Long> documentIds, List<Long> collectionIds) {
        if (collectionIds == null || collectionIds.isEmpty()) {
            return documentIds;
        }
        List<Long> idsFromCollections = documentRepository.findIdsByCollectionIdIn(collectionIds);
        if (idsFromCollections == null) {
            idsFromCollections = List.of();
        }
        if (documentIds == null || documentIds.isEmpty()) {
            return idsFromCollections;
        }
        List<Long> intersection = new ArrayList<>();
        for (Long id : documentIds) {
            if (idsFromCollections.contains(id)) {
                intersection.add(id);
            }
        }
        return intersection;
    }

    /**
     * Whether the caller requested a collection-scoped search.
     */
    public static boolean hasCollectionFilter(List<Long> collectionIds) {
        return collectionIds != null && !collectionIds.isEmpty();
    }
}
