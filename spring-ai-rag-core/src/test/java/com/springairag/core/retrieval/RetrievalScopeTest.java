package com.springairag.core.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalScopeTest {

    @Test
    void normalizesIdsAndKeepsScopeImmutable() {
        RetrievalScope scope = RetrievalScope.selectedCollections(
                List.of(2L, 1L, 2L),
                List.of(9L, 8L, 9L),
                "json-record");

        assertEquals(List.of(2L, 1L), scope.collectionIds());
        assertEquals(List.of(9L, 8L), scope.documentIds());
        assertThrows(UnsupportedOperationException.class,
                () -> scope.collectionIds().add(3L));
    }

    @Test
    void selectedEmptyFailsClosed() {
        RetrievalScope scope = RetrievalScope.selectedCollections(
                List.of(), null, null);

        assertTrue(scope.matchNone());
    }

    @Test
    void rejectsCollectionIdsForNonSelectedScope() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetrievalScope(
                        RetrievalScope.CollectionFilter.NONE,
                        List.of(1L), List.of(), null, false));
    }

    @Test
    void documentOnlyCompatibilityTreatsEmptyAsUnscoped() {
        RetrievalScope scope = RetrievalScope.forDocumentIds(List.of());

        assertEquals(RetrievalScope.CollectionFilter.NONE,
                scope.collectionFilter());
        assertTrue(scope.documentIds().isEmpty());
        assertTrue(!scope.matchNone());
    }
}
